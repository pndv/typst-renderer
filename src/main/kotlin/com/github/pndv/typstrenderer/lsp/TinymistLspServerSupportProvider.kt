package com.github.pndv.typstrenderer.lsp

import com.github.pndv.typstrenderer.TYPST_NOTIFICATION_GROUP_ID
import com.github.pndv.typstrenderer.TypstBundle
import com.github.pndv.typstrenderer.language.TypstFileType
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServerSupportProvider

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

class TinymistLspServerSupportProvider : LspServerSupportProvider {

    override fun fileOpened(
        project: Project,
        file: VirtualFile,
        serverStarter: LspServerSupportProvider.LspServerStarter
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
                serverStarter.ensureServerStarted(TinymistLspServerDescriptor(project, action.tinymistPath))
            }
            LspStartAction.TriggerDownload -> {
                log.info("Tinymist not found, triggering auto-download for file ${file.path}")
                TinymistDownloadService.getInstance().downloadInBackground(project) { success ->
                    if (success) {
                        val downloadedPath = TinymistManager.getInstance().resolveTinymistPath()
                        if (downloadedPath != null) {
                            log.info("Tinymist downloaded successfully, starting LSP from: $downloadedPath")
                            serverStarter.ensureServerStarted(TinymistLspServerDescriptor(project, downloadedPath))
                        }
                    } else {
                        log.warn("Tinymist download failed; LSP server will not be started")
                        NotificationGroupManager.getInstance()
                            .getNotificationGroup(TYPST_NOTIFICATION_GROUP_ID)
                            .createNotification(
                                TypstBundle.message("notification.tinymist.notFound.title"),
                                TypstBundle.message("notification.tinymist.notFound.body"),
                                NotificationType.WARNING
                            ).notify(project)
                    }
                }
            }
        }
    }
}
