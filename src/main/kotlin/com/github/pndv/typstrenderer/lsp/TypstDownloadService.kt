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
import java.util.zip.ZipInputStream

private val log = logger<TypstDownloadService>()

/**
 * Downloads the Typst CLI binary from GitHub releases.
 *
 * Unlike tinymist (which ships as a standalone binary), Typst releases are compressed
 * archives (.tar.xz on macOS/Linux, .zip on Windows). This service handles downloading
 * the archive and extracting the typst binary from it.
 */
@Service(Service.Level.APP)
class TypstDownloadService {

    @Volatile
    var isDownloading: Boolean = false
        private set

    /**
     * Downloads Typst CLI in a background task with a progress indicator.
     * Calls [onComplete] on the EDT when done (true = success, false = failure).
     */
    fun downloadInBackground(project: Project?, onComplete: ((Boolean) -> Unit)? = null) {
        if (isDownloading) {
            onComplete?.let { ApplicationManager.getApplication().invokeLater { it(false) } }
            return
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Downloading Typst CLI", true) {
            override fun run(indicator: ProgressIndicator) {
                isDownloading = true
                try {
                    indicator.isIndeterminate = false
                    indicator.text = "Resolving latest Typst release..."
                    indicator.fraction = 0.0

                    val assetName = TinymistManager.getTypstPlatformAssetName()
                    if (assetName == null) {
                        notifyError(project, TinymistDownloadService.unsupportedPlatformMessage())
                        onComplete?.let { ApplicationManager.getApplication().invokeLater { it(false) } }
                        return
                    }

                    val downloadUrl = resolveLatestDownloadUrl(PlatformConfig.typst.baseUrl, assetName)
                    if (downloadUrl == null) {
                        notifyError(project, "Could not find Typst release for this platform ($assetName)")
                        onComplete?.let { ApplicationManager.getApplication().invokeLater { it(false) } }
                        return
                    }

                    indicator.text = "Downloading Typst CLI..."
                    indicator.fraction = 0.1

                    val manager = TinymistManager.getInstance()
                    val downloadDir = manager.getDownloadDir()
                    val archiveFile = File(downloadDir, assetName)
                    val targetBinary = manager.getDownloadedTypstPath()

                    // Download the archive
                    downloadFile(downloadUrl, archiveFile, indicator)

                    indicator.text = "Extracting Typst binary..."
                    indicator.fraction = 0.8

                    // Extract the typst binary from the archive
                    extractTypstBinary(archiveFile, targetBinary)

                    // Clean up archive
                    archiveFile.delete()

                    // Make executable on Unix
                    if (!TinymistManager.isWindows()) {
                        targetBinary.setExecutable(true, false)
                    }

                    indicator.fraction = 1.0
                    indicator.text = "Typst CLI downloaded successfully"

                    log.info("Typst CLI downloaded to: ${targetBinary.absolutePath}")

                    NotificationGroupManager.getInstance()
                        .getNotificationGroup("Typst")
                        .createNotification(
                            "Typst CLI downloaded",
                            "Typst CLI has been downloaded and is ready to use.",
                            NotificationType.INFORMATION
                        ).notify(project)

                    onComplete?.let { ApplicationManager.getApplication().invokeLater { it(true) } }

                } catch (e: Exception) {
                    if (indicator.isCanceled) {
                        log.info("Typst CLI download cancelled by user")
                    } else {
                        log.warn("Failed to download Typst CLI", e)
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

        return try {
            HttpRequests.head(url)
                .tuner { connection ->
                    (connection as? HttpURLConnection)?.instanceFollowRedirects = true
                }
                .tryConnect()
            url
        } catch (e: IOException) {
            log.warn("Could not resolve download URL for $assetName: ${e.message}")
            null
        }
    }

    private fun downloadFile(url: String, target: File, indicator: ProgressIndicator) {
        target.parentFile.mkdirs()

        val tempFile = File(target.parent, "${target.name}.download")
        try {
            HttpRequests.request(url)
                .forceHttps(true)
                .saveToFile(tempFile, indicator)

            TinymistDownloadService.atomicMove(tempFile, target)
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    internal fun extractTypstBinary(archiveFile: File, targetBinary: File) {
        if (TinymistManager.isWindows()) {
            extractFromZip(archiveFile, targetBinary)
        } else {
            extractFromTarXz(archiveFile, targetBinary)
        }
    }

    /**
     * Extracts the typst binary from a .tar.xz archive using the system `tar` command.
     * The archive structure is: {dirname}/typst
     */
    internal fun extractFromTarXz(archiveFile: File, targetBinary: File) {
        val tempExtractDir = File(archiveFile.parent, "typst-extract-tmp")
        tempExtractDir.mkdirs()
        try {
            val process = ProcessBuilder("tar", "xf", archiveFile.absolutePath, "-C", tempExtractDir.absolutePath)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw IOException("tar extraction failed (exit $exitCode): $output")
            }

            val extractedBinary = tempExtractDir.walk()
                .firstOrNull { it.name == "typst" && it.isFile }
                ?: throw IOException("typst binary not found in archive")

            if (targetBinary.exists()) targetBinary.delete()
            if (!extractedBinary.renameTo(targetBinary)) {
                extractedBinary.copyTo(targetBinary, overwrite = true)
            }
        } finally {
            tempExtractDir.deleteRecursively()
        }
    }

    /**
     * Extracts the typst.exe binary from a .zip archive using Java's built-in ZipInputStream.
     * The archive structure is: {dirname}/typst.exe
     */
    internal fun extractFromZip(archiveFile: File, targetBinary: File) {
        ZipInputStream(archiveFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.endsWith("typst.exe")) {
                    targetBinary.parentFile.mkdirs()
                    targetBinary.outputStream().buffered().use { out ->
                        zis.copyTo(out)
                    }
                    return
                }
                entry = zis.nextEntry
            }
        }
        throw IOException("typst.exe not found in zip archive")
    }

    private fun notifyError(project: Project?, message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Typst")
            .createNotification(
                "Typst CLI download failed",
                message,
                NotificationType.ERROR
            ).notify(project)
    }

    companion object {
        fun getInstance(): TypstDownloadService =
            ApplicationManager.getApplication().getService(TypstDownloadService::class.java)
    }
}
