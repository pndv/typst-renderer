package com.github.pndv.typstrenderer.lsp

import com.github.pndv.typstrenderer.language.TypstFileType
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspServerSupportProvider

private val LOG = logger<TinymistLspServerSupportProvider>()

class TinymistLspServerSupportProvider : LspServerSupportProvider {

    override fun fileOpened(
        project: Project,
        file: VirtualFile,
        serverStarter: LspServerSupportProvider.LspServerStarter
    ) {
        if (file.fileType != TypstFileType) return

        val manager = TinymistManager.getInstance()
        val tinymistPath = manager.resolveTinymistPath()

        if (tinymistPath != null) {
            LOG.info("Starting tinymist LSP from: $tinymistPath")
            serverStarter.ensureServerStarted(TinymistLspServerDescriptor(project, tinymistPath))
        } else {
            LOG.info("Tinymist not found, triggering auto-download")
            TinymistDownloadService.getInstance().downloadInBackground(project) { success ->
                if (success) {
                    // After download, resolve again and start the server
                    val downloadedPath = manager.resolveTinymistPath()
                    if (downloadedPath != null) {
                        serverStarter.ensureServerStarted(TinymistLspServerDescriptor(project, downloadedPath))
                    }
                } else {
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup("Typst")
                        .createNotification(
                            "Tinymist not found",
                            "Tinymist language server is not installed. Install it manually or configure the path in Settings > Tools > Typst.",
                            NotificationType.WARNING
                        ).notify(project)
                }
            }
        }
    }
}
