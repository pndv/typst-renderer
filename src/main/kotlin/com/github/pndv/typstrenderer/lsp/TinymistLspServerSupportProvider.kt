package com.github.pndv.typstrenderer.lsp

import com.github.pndv.typstrenderer.language.TypstFileType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.platform.lsp.api.LspIntegrationProvider
import com.intellij.platform.lsp.api.startClientsIfNeeded

private val log = logger<TinymistLspServerSupportProvider>()

/**
 * Decision for what [TinymistLspServerSupportProvider.fileOpened] should do
 * given the (IDE-side) inputs it observes when a file is opened.
 *
 * Kept as a separate sealed class so the decision can be tested without
 * standing up an IDE fixture or mocking the LSP framework's `LspServerStarter`.
 */
internal sealed class LspStartAction {
    object Skip : LspStartAction()
    data class StartServer(val tinymistPath: String) : LspStartAction()
    object TriggerDownload : LspStartAction()
}

internal fun decideLspAction(
    isUnitTestMode: Boolean,
    isTypstFile: Boolean,
    tinymistPath: String?,
): LspStartAction = when {
    isUnitTestMode -> LspStartAction.Skip
    !isTypstFile -> LspStartAction.Skip
    tinymistPath != null -> LspStartAction.StartServer(tinymistPath)
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

        // Skip the binary resolve when we already know we will skip — avoids
        // touching the TinymistManager service from contexts where it may not
        // be initialised (notably unit-test mode).
        val tinymistPath = if (isUnitTestMode || !isTypstFile) null
                           else TinymistManager.getInstance().resolveTinymistPath()

        when (val action = decideLspAction(isUnitTestMode, isTypstFile, tinymistPath)) {
            LspStartAction.Skip -> {
                log.debug("Skipping LSP start for file ${file.path}")
                return
            }
            is LspStartAction.StartServer -> {
                log.info("Starting tinymist LSP from: ${action.tinymistPath} for file ${file.path}")
                clientStarter.ensureClientStarted(TinymistLspServerDescriptor(project, action.tinymistPath))
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
