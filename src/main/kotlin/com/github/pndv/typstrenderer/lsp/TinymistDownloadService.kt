package com.github.pndv.typstrenderer.lsp

import com.github.pndv.typstrenderer.TYPST_NOTIFICATION_GROUP_ID
import com.github.pndv.typstrenderer.TypstBundle
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.util.io.HttpRequests
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private val LOG = logger<TinymistDownloadService>()

/**
 * What [TinymistDownloadService.downloadInBackground] should do with an incoming request, given
 * how the previous attempts went. Pure so the throttling — the part that misbehaves invisibly —
 * can be unit-tested without a network, a project, or the notification subsystem.
 */
internal sealed interface DownloadAttempt {
    /** No attempt is in flight and no back-off applies — run the download. */
    data object Proceed : DownloadAttempt

    /** Another download is already running; this request is redundant. */
    data object AlreadyRunning : DownloadAttempt

    /** The last attempts failed and the back-off window has not elapsed. */
    data class BackOff(val remainingMs: Long) : DownloadAttempt
}

/**
 * Decides whether to attempt a download.
 *
 * Downloads fail for reasons that are almost always *persistent* — offline, an unsupported
 * platform, a 404 on the pinned asset — so retrying immediately cannot succeed and only produces
 * another balloon. Each consecutive failure therefore doubles the wait, from [baseBackoffMs] up to
 * [maxBackoffMs]. A success resets the streak, so a genuinely transient failure recovers promptly.
 *
 * [consecutiveFailures] of 0 always proceeds: the first attempt, and the first after any success.
 */
internal fun decideDownloadAttempt(
    isDownloading: Boolean,
    consecutiveFailures: Int,
    lastFailureAtMs: Long,
    nowMs: Long,
    baseBackoffMs: Long,
    maxBackoffMs: Long,
): DownloadAttempt {
    if (isDownloading) return DownloadAttempt.AlreadyRunning
    if (consecutiveFailures <= 0) return DownloadAttempt.Proceed

    val shift = (consecutiveFailures - 1).coerceIn(0, 20)
    val window = (baseBackoffMs shl shift).coerceAtMost(maxBackoffMs)
    val elapsed = nowMs - lastFailureAtMs
    return if (elapsed >= window) DownloadAttempt.Proceed else DownloadAttempt.BackOff(window - elapsed)
}

/**
 * Whether a failure should raise a user-visible notification.
 *
 * Only the **first** failure of a streak does. A repeat tells the user nothing new, and the
 * balloons stack: with the binary missing and several `.typ` files open, every attempt raised its
 * own, producing hundreds of identical notifications (issue #105). [consecutiveFailures] is the
 * count *including* the failure being reported, so 1 is the first.
 */
internal fun shouldNotifyDownloadFailure(consecutiveFailures: Int): Boolean = consecutiveFailures <= 1

/**
 * Downloads the tinymist language server binary from GitHub releases.
 */
@Service(Service.Level.APP)
class TinymistDownloadService {

    val isDownloading: AtomicBoolean = AtomicBoolean(false)

    /** Consecutive failed attempts; reset to 0 by a success. Drives back-off and notification. */
    private val consecutiveFailures = AtomicInteger(0)
    private val lastFailureAt = AtomicLong(0)

    /**
     * Downloads tinymist in a background task with a progress indicator.
     * Calls [onComplete] on the EDT when done (true = success, false = failure).
     *
     * Repeated failures back off and stop notifying — see [decideDownloadAttempt] and
     * [shouldNotifyDownloadFailure].
     */
    fun downloadInBackground(project: Project?, onComplete: ((Boolean) -> Unit)? = null) {
        val decision = decideDownloadAttempt(
            isDownloading = isDownloading.get(),
            consecutiveFailures = consecutiveFailures.get(),
            lastFailureAtMs = lastFailureAt.get(),
            nowMs = System.currentTimeMillis(),
            baseBackoffMs = BASE_BACKOFF_MS,
            maxBackoffMs = MAX_BACKOFF_MS,
        )
        if (decision is DownloadAttempt.BackOff) {
            LOG.debug("Skipping tinymist download: ${consecutiveFailures.get()} consecutive failures, retrying in ${decision.remainingMs}ms")
            onComplete?.let { ApplicationManager.getApplication().invokeLater { it(false) } }
            return
        }
        if (!isDownloading.compareAndSet(false, true)) {
            onComplete?.let { ApplicationManager.getApplication().invokeLater { it(false) } }
            return
        }

        // Use Task.Backgroundable.queue() rather than ProgressManager.getInstance().run(task).
        // The latter, when called off the EDT (e.g. inside an LSP-framework read action),
        // synchronously invokeAndWait()s onto the EDT to set up the indicator UI, which
        // IntelliJ's deadlock detector rightly refuses (read-action + invokeAndWait is a
        // classic deadlock pattern). queue() schedules asynchronously and is thread-safe
        // from any caller context.
        object : Task.Backgroundable(project, TypstBundle.message("download.tinymist.task.title"), true) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.isIndeterminate = false
                    indicator.text = TypstBundle.message("download.tinymist.resolving")
                    indicator.fraction = 0.0

                    val assetName = TinymistManager.getPlatformAssetName()
                    if (assetName == null) {
                        recordFailureAndNotify(project, unsupportedPlatformMessage())
                        onComplete?.let { ApplicationManager.getApplication().invokeLater { it(false) } }
                        return
                    }

                    // Resolve the latest release download URL.
                    // PlatformConfig.tinymistBaseUrl resolves to the real GitHub releases
                    // URL in production and to a test-only override (e.g. a MockWebServer)
                    // when one has been set — keeps tests offline and hermetic.
                    val downloadUrl = resolveLatestDownloadUrl(PlatformConfig.tinymistBaseUrl, assetName)
                    if (downloadUrl == null) {
                        recordFailureAndNotify(project, TypstBundle.message("download.tinymist.notFound", assetName))
                        onComplete?.let { ApplicationManager.getApplication().invokeLater { it(false) } }
                        return
                    }

                    indicator.text = TypstBundle.message("download.tinymist.downloading")
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
                    indicator.text = TypstBundle.message("download.tinymist.success")

                    // Success clears the streak, so a later transient failure notifies
                    // and retries promptly instead of inheriting an old back-off.
                    consecutiveFailures.set(0)
                    LOG.info("Tinymist downloaded to: ${targetFile.absolutePath}")

                    NotificationGroupManager.getInstance()
                        .getNotificationGroup(TYPST_NOTIFICATION_GROUP_ID)
                        .createNotification(
                            TypstBundle.message("notification.tinymist.downloaded.title"),
                            TypstBundle.message("notification.tinymist.downloaded.body"),
                            NotificationType.INFORMATION
                        ).notify(project)

                    onComplete?.let { ApplicationManager.getApplication().invokeLater { it(true) } }

                } catch (e: Exception) {
                    if (indicator.isCanceled) {
                        LOG.info("Tinymist download cancelled by user")
                    } else {
                        LOG.warn("Failed to download tinymist", e)
                        recordFailureAndNotify(
                            project,
                            TypstBundle.message("download.tinymist.failed", e.message ?: "")
                        )
                    }
                    onComplete?.let { ApplicationManager.getApplication().invokeLater { it(false) } }
                } finally {
                    isDownloading.set(false)
                }
            }
        }.queue()
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

            atomicMove(tempFile, target)
        } finally { // Clean up the temporary file if it still exists (e.g. on error)
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }

    private fun notifyError(project: Project?, message: String) {
        val groupManager = NotificationGroupManager.getInstance()
        val notificationGroup: NotificationGroup? = groupManager.getNotificationGroup(TYPST_NOTIFICATION_GROUP_ID)
        if (notificationGroup == null) {
            val pluginId = PluginId.getId("com.github.pndv.typstrenderer")
            val isPluginInstalledAndEnabled =
                PluginManagerCore.isPluginInstalled(pluginId) && !PluginManagerCore.isDisabled(pluginId)
            LOG.debug("notifyError: isPluginInstalledAndEnabled=$isPluginInstalledAndEnabled")
            LOG.error("Notification group not found")
            throw IllegalStateException("Notification group not found")
        }
        notificationGroup.createNotification(
                TypstBundle.message("notification.tinymist.download.failed.title"),
                message,
                NotificationType.ERROR
            ).notify(project)
    }

    /**
     * Records a failed attempt and raises a notification only for the first failure of a streak.
     * Suppressed repeats are still logged, so the log keeps the full picture while the user sees
     * one actionable balloon.
     */
    private fun recordFailureAndNotify(project: Project?, message: String) {
        val failures = consecutiveFailures.incrementAndGet()
        lastFailureAt.set(System.currentTimeMillis())
        if (shouldNotifyDownloadFailure(failures)) {
            notifyError(project, message)
        } else {
            LOG.info("Tinymist download failed again (attempt $failures), notification suppressed: $message")
        }
    }

    companion object {
        /** First retry window after a failure; doubles per consecutive failure. */
        internal const val BASE_BACKOFF_MS = 30_000L

        /** Ceiling for the retry window. */
        internal const val MAX_BACKOFF_MS = 600_000L

        fun getInstance(): TinymistDownloadService =
            ApplicationManager.getApplication().getService(TinymistDownloadService::class.java)

        /**
         * Moves [tempFile] to [target], overwriting if [target] exists.
         * Tries a fast rename first, falling back to copy and delete when the
         * rename isn't possible (e.g. across filesystems).
         */
        internal fun atomicMove(tempFile: File, target: File) {
            if (target.exists()) {
                target.delete()
            }
            if (!tempFile.renameTo(target)) {
                tempFile.copyTo(target, overwrite = true)
                tempFile.delete()
            }
        }

        internal fun unsupportedPlatformMessage(): String {
            val os = System.getProperty("os.name")
            val arch = System.getProperty("os.arch")
            return "Your platform (os=$os, arch=$arch) is not fully supported. " + "The plugin requires tinymist, available on: " + "${PlatformConfig.supportedPlatformsDescription()}. " + "On other platforms, install tinymist manually and set its path " +
                    "in Settings → Tools → Typst."
        }
    }
}
