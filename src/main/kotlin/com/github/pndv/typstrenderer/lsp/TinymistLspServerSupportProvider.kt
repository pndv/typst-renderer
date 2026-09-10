package com.github.pndv.typstrenderer.lsp

import com.github.pndv.typstrenderer.TYPST_NOTIFICATION_GROUP_ID
import com.github.pndv.typstrenderer.TypstBundle.message
import com.github.pndv.typstrenderer.language.TypstFileType
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.platform.lsp.api.LspIntegrationProvider
import com.intellij.platform.lsp.api.startClientsIfNeeded
import java.util.concurrent.ConcurrentHashMap

private val log = logger<TinymistLspServerSupportProvider>()

// Notified once per distro per session rather than on every file open — the previous
// per-open-event failure notification (before it was throttled) produced 830 balloons in
// seven seconds for a comparable trigger (issue #105).
internal val notifiedMissingWslDistros: MutableSet<String> = ConcurrentHashMap.newKeySet()

internal fun notifyWslTinymistMissing(project: Project, distroId: String) {
    if (!notifiedMissingWslDistros.add(distroId)) return
    NotificationGroupManager.getInstance()
        .getNotificationGroup(TYPST_NOTIFICATION_GROUP_ID)
        .createNotification(
            message("notification.tinymist.wslNotFound.title"),
            message("notification.tinymist.wslNotFound.body", distroId),
            NotificationType.WARNING,
        )
        .notify(project)
}

/**
 * Decision for what [TinymistLspServerSupportProvider.fileOpened] should do
 * given the (IDE-side) inputs it observes when a file is opened.
 *
 * Kept as a separate sealed class so the decision can be tested without
 * standing up an IDE fixture or mocking the LSP framework's `LspServerStarter`.
 */
internal sealed class LspStartAction {
    object Skip : LspStartAction()
    data class StartServer(val tinymistPath: String, val wslTarget: WslTarget? = null) : LspStartAction()
    object TriggerDownload : LspStartAction()
    data class TriggerWslNotFound(val wslTarget: WslTarget) : LspStartAction()
}

/**
 * [wslTarget] is the WSL distribution execution was decided for (see [WslExecution.kt]),
 * independent of whether the binary was actually found there. When [tinymistPath] is `null`
 * and [wslTarget] is non-null, the binary is specifically missing *inside that distro* — the
 * Windows-side auto-download is meaningless there, so that case gets its own action rather than
 * falling into [LspStartAction.TriggerDownload]. Defaults to `null` so existing native-only
 * call sites are unaffected.
 */
internal fun decideLspAction(
    isUnitTestMode: Boolean,
    isTypstFile: Boolean,
    tinymistPath: String?,
    wslTarget: WslTarget? = null,
): LspStartAction = when {
    isUnitTestMode -> LspStartAction.Skip
    !isTypstFile -> LspStartAction.Skip
    tinymistPath != null -> LspStartAction.StartServer(tinymistPath, wslTarget)
    wslTarget != null -> LspStartAction.TriggerWslNotFound(wslTarget)
    else -> LspStartAction.TriggerDownload
}

/**
 * Starts the project-wide tinymist client when a `.typ` file inside the project's content
 * roots is opened, downloading the binary first when it is missing.
 *
 * The platform only calls [fileOpened] for files that pass `ProjectFileIndex.isInContent`
 * (and `startClientsIfNeeded` below applies the same filter), so this provider structurally
 * never sees a `.typ` file opened from outside the project. Those are handled by
 * [TypstExternalFileLspStarter], which starts a folder-rooted client through the public
 * `LspClientManager.ensureClientStarted` API instead.
 */
class TinymistLspServerSupportProvider : LspIntegrationProvider {

    override fun fileOpened(
        project: Project, file: VirtualFile, clientStarter: LspIntegrationProvider.LspClientStarter
    ) {
        val isUnitTestMode = ApplicationManager.getApplication().isUnitTestMode
        val isTypstFile = file.fileType == TypstFileType
        val skipResolve = isUnitTestMode || !isTypstFile

        // Skip the binary resolve (and the WSL distro check) when we already know we will skip —
        // avoids touching the TinymistManager service from contexts where it may not be
        // initialised (notably unit-test mode).
        val wslTarget = if (skipResolve) null else resolveWslTargetForProject(project)
        val tinymistPath = when {
            skipResolve -> null
            wslTarget != null -> TinymistManager.getInstance().resolveTinymistPathForWsl(wslTarget.distroId)
            else -> TinymistManager.getInstance().resolveTinymistPath()
        }

        when (val action = decideLspAction(isUnitTestMode, isTypstFile, tinymistPath, wslTarget)) {
            LspStartAction.Skip -> {
                log.debug("Skipping LSP start for file ${file.path}")
                return
            }
            is LspStartAction.StartServer -> {
                val wslNote = action.wslTarget?.let { " (WSL distro '${it.distroId}')" } ?: ""
                log.info("Starting tinymist LSP from: ${action.tinymistPath}$wslNote for file ${file.path}")
                clientStarter.ensureClientStarted(
                    TinymistLspServerDescriptor(project, action.tinymistPath, action.wslTarget)
                )
            }
            is LspStartAction.TriggerWslNotFound -> {
                log.warn(
                    "Tinymist not found in WSL distro '${action.wslTarget.distroId}'; " +
                        "no LSP for file ${file.path}"
                )
                notifyWslTinymistMissing(project, action.wslTarget.distroId)
            }
            LspStartAction.TriggerDownload -> {
                log.info("Tinymist not found, triggering auto-download for file ${file.path}")
                TinymistDownloadService.getInstance().downloadInBackground(project) { success ->
                    if (success) {
                        log.info("Tinymist downloaded successfully; requesting LSP (re)start")
                        LspClientManager.getInstance(project).startClientsIfNeeded<TinymistLspServerSupportProvider>()
                    } else { // Log only — TinymistDownloadService owns the user-facing failure
                        // notification and deduplicates it across a failure streak. Notifying
                        // here as well double-reported every failure, and worse: `onComplete(false)`
                        // also fires for requests the service *declined* to run (back-off, or a
                        // download already in flight), so throttled no-ops each raised a balloon.
                        // That is what kept the count high after the deduplication went in (#105).
                        log.warn("Tinymist download failed or was skipped; LSP server will not be started")
                    }
                }
            }
        }
    }
}
