package com.github.pndv.typstrenderer.lsp

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.util.io.HttpRequests
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection

private val LOG = logger<TinymistDownloadService>()

/**
 * Downloads the tinymist language server binary from GitHub releases.
 */
@Service(Service.Level.APP)
class TinymistDownloadService {

    @Volatile
    var isDownloading: Boolean = false
        private set

    /**
     * Downloads tinymist in a background task with a progress indicator.
     * Calls [onComplete] on the EDT when done (true = success, false = failure).
     */
    fun downloadInBackground(project: Project?, onComplete: ((Boolean) -> Unit)? = null) {
        if (isDownloading) {
            onComplete?.let { ApplicationManager.getApplication().invokeLater { it(false) } }
            return
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Downloading Tinymist language server", true) {
            override fun run(indicator: ProgressIndicator) {
                isDownloading = true
                try {
                    indicator.isIndeterminate = false
                    indicator.text = "Resolving latest tinymist release..."
                    indicator.fraction = 0.0

                    val assetName = TinymistManager.getPlatformAssetName()
                    if (assetName == null) {
                        notifyError(project, unsupportedPlatformMessage())
                        onComplete?.let { ApplicationManager.getApplication().invokeLater { it(false) } }
                        return
                    }

                    // Resolve the latest release download URL
                    val downloadUrl = resolveLatestDownloadUrl(PlatformConfig.tinymist.baseUrl, assetName)
                    if (downloadUrl == null) {
                        notifyError(project, "Could not find tinymist release for this platform ($assetName)")
                        onComplete?.let { ApplicationManager.getApplication().invokeLater { it(false) } }
                        return
                    }

                    indicator.text = "Downloading tinymist..."
                    indicator.fraction = 0.1

                    val manager = TinymistManager.getInstance()
                    val targetFile = manager.getDownloadedBinaryPath()

                    // Download the binary
                    downloadFile(downloadUrl, targetFile, indicator)

                    // Make executable on Unix
                    if (!TinymistManager.isWindows()) {
                        targetFile.setExecutable(true, false)
                    }

                    indicator.fraction = 1.0
                    indicator.text = "Tinymist downloaded successfully"

                    LOG.info("Tinymist downloaded to: ${targetFile.absolutePath}")

                    NotificationGroupManager.getInstance()
                        .getNotificationGroup("Typst")
                        .createNotification(
                            "Tinymist downloaded",
                            "Tinymist language server has been downloaded and is ready to use.",
                            NotificationType.INFORMATION
                        ).notify(project)

                    onComplete?.let { ApplicationManager.getApplication().invokeLater { it(true) } }

                } catch (e: Exception) {
                    if (indicator.isCanceled) {
                        LOG.info("Tinymist download cancelled by user")
                    } else {
                        LOG.warn("Failed to download tinymist", e)
                        notifyError(project, "Download failed: ${e.message}")
                    }
                    onComplete?.let { ApplicationManager.getApplication().invokeLater { it(false) } }
                } finally {
                    isDownloading = false
                }
            }
        })
    }

    fun resolveLatestDownloadUrl(baseUrl: String, assetName: String): String? {
        val url = "$baseUrl/$assetName"

        // Verify the URL is valid by sending a HEAD request
        return try {
            HttpRequests.head(url)
                .tuner { connection ->
                    (connection as? HttpURLConnection)?.instanceFollowRedirects = true
                }
                .tryConnect()
            url
        } catch (e: IOException) {
            LOG.warn("Could not resolve download URL for $assetName: ${e.message}")
            null
        }
    }

    private fun downloadFile(url: String, target: File, indicator: ProgressIndicator) {
        target.parentFile.mkdirs()

        // Use a temp file to avoid leaving a corrupt binary if download is interrupted
        val tempFile = File(target.parent, "${target.name}.download")
        try {
            HttpRequests.request(url)
                .forceHttps(true)
                .saveToFile(tempFile, indicator)

            // Atomic-ish move: delete target first, then rename temp
            if (target.exists()) {
                target.delete()
            }
            if (!tempFile.renameTo(target)) {
                // Fallback: copy + delete
                tempFile.copyTo(target, overwrite = true)
                tempFile.delete()
            }
        } finally {
            // Clean up temp file if it still exists (e.g., on error)
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    private fun notifyError(project: Project?, message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Typst")
            .createNotification(
                "Tinymist download failed",
                message,
                NotificationType.ERROR
            ).notify(project)
    }

    companion object {
        fun getInstance(): TinymistDownloadService =
            ApplicationManager.getApplication().getService(TinymistDownloadService::class.java)

        internal fun unsupportedPlatformMessage(): String {
            val os = System.getProperty("os.name")
            val arch = System.getProperty("os.arch")
            return "Your platform (os=$os, arch=$arch) is not fully supported. " +
                    "The plugin requires both tinymist and typst, available on: " +
                    "${PlatformConfig.supportedPlatformsDescription()}. " +
                    "On other platforms, install the tools manually and set their paths " +
                    "in Settings → Tools → Typst."
        }
    }
}
