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
import java.nio.file.Files
import java.nio.file.StandardCopyOption
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

    /** The last attempt failed, and the back-off window has not elapsed. */
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

                    // The pinned binary is in place, so downloads made for earlier pins can go.
                    cleanUpReplacedBinaries(targetFile)
                    manager.cleanUpStaleDownloads()

                    val pinned = PlatformConfig.tinymistPin?.version
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup(TYPST_NOTIFICATION_GROUP_ID)
                        .createNotification(
                            TypstBundle.message("notification.tinymist.downloaded.title"), if (pinned != null) {
                                TypstBundle.message("notification.tinymist.downloaded.version.body", pinned)
                            } else {
                                TypstBundle.message("notification.tinymist.downloaded.body")
                            },
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
         * Marks a binary that was moved aside to free its name. Swept up by
         * [cleanUpReplacedBinaries] once whatever was running it has exited.
         */
        internal const val REPLACED_SUFFIX = ".replaced-"

        /**
         * Moves [tempFile] onto [target], replacing whatever is there.
         *
         * The straightforward replace covers a first install and any Unix host. It cannot cover
         * replacing a binary that is *running*, which is what "Download Tinymist" in the settings
         * does while a language server is up: Windows refuses to delete or overwrite the image
         * file of a live process. It does allow that file to be **renamed**, so the old binary is
         * moved aside to free the name and the new one takes its place — the running language
         * server carries on from the renamed file until it is restarted. If the second move then
         * fails, the old binary is put back, because leaving no tinymist at all is far worse than
         * leaving an old one.
         *
         * [replace] is injectable so the move-aside path can be exercised in a test: the case it
         * exists for — a live process holding the target — cannot be staged portably.
         */
        internal fun atomicMove(
            tempFile: File,
            target: File,
            replace: (File, File) -> Boolean = ::replaceInPlace,
        ) {
            if (replace(tempFile, target)) return

            val aside = File(target.parentFile, "${target.name}$REPLACED_SUFFIX${System.currentTimeMillis()}")
            if (!target.renameTo(aside)) {
                throw IOException("Could not move ${target.absolutePath} aside to replace it")
            }
            if (!replace(tempFile, target)) {
                if (!aside.renameTo(target)) { // Both moves failed: the binary is now only at the aside path. Loud, because
                    // the next resolve will find nothing and start an unexplained re-download.
                    LOG.warn("Tinymist binary left at ${aside.absolutePath}; ${target.absolutePath} is missing")
                }
                throw IOException("Could not move ${tempFile.absolutePath} into place at ${target.absolutePath}")
            }
            if (!aside.delete()) { // Expected while the old binary still has a live process: the next sweep gets it.
                LOG.debug("Replaced binary ${aside.absolutePath} is still in use; leaving it for later")
                aside.deleteOnExit()
            }
        }

        /**
         * Deletes binaries left behind by earlier replacements. Best-effort by design: one that
         * is still running simply stays until a later sweep, and a sweep never fails a download.
         */
        internal fun cleanUpReplacedBinaries(target: File) {
            val leftovers =
                target.parentFile?.listFiles { file -> file.name.startsWith("${target.name}$REPLACED_SUFFIX") }
                    .orEmpty()
            for (file in leftovers) {
                if (file.delete()) {
                    LOG.debug("Removed replaced tinymist binary ${file.absolutePath}")
                } else {
                    LOG.debug("Replaced tinymist binary ${file.absolutePath} is still in use")
                }
            }
        }

        private fun replaceInPlace(source: File, target: File): Boolean = try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            true
        } catch (e: IOException) {
            LOG.debug("Could not replace ${target.absolutePath} directly: ${e.message}")
            false
        }

        internal fun unsupportedPlatformMessage(): String {
            val os = System.getProperty("os.name")
            val arch = System.getProperty("os.arch")
            return "Your platform (os=$os, arch=$arch) is not fully supported. " + "The plugin requires tinymist, available on: " + "${PlatformConfig.supportedPlatformsDescription()}. " + "On other platforms, install tinymist manually and set its path " +
                    "in Settings → Tools → Typst."
        }
    }
}
