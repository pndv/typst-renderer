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

private val LOG = logger<TinymistLspServerSupportProvider>()

class TinymistLspServerSupportProvider : LspServerSupportProvider {

    override fun fileOpened(
        project: Project,
        file: VirtualFile,
        serverStarter: LspServerSupportProvider.LspServerStarter
    ) {
        if (ApplicationManager.getApplication().isUnitTestMode) return

        if (file.fileType != TypstFileType) return

        val manager = TinymistManager.getInstance()
        val tinymistPath = manager.resolveTinymistPath()

        if (tinymistPath != null) {
            LOG.info("Starting tinymist LSP from: $tinymistPath for file ${file.path}")
            serverStarter.ensureServerStarted(TinymistLspServerDescriptor(project, tinymistPath))
        } else {
            LOG.info("Tinymist not found, triggering auto-download for file ${file.path}")
            TinymistDownloadService.getInstance().downloadInBackground(project) { success ->
                if (success) {
                    // After download, resolve again and start the server
                    val downloadedPath = manager.resolveTinymistPath()
                    if (downloadedPath != null) {
                        LOG.info("Tinymist downloaded successfully, starting LSP from: $downloadedPath")
                        serverStarter.ensureServerStarted(TinymistLspServerDescriptor(project, downloadedPath))
                    }
                } else {
                    LOG.warn("Tinymist download failed; LSP server will not be started")
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
