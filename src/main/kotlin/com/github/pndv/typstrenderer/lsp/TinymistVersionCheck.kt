package com.github.pndv.typstrenderer.lsp

import com.github.pndv.typstrenderer.TYPST_UPDATE_NOTIFICATION_GROUP_ID
import com.github.pndv.typstrenderer.TypstBundle.message
import com.github.pndv.typstrenderer.settings.TypstSettingsState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.util.ExecUtil
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.io.FileUtil
import com.intellij.util.concurrency.annotations.RequiresBackgroundThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

private val LOG = logger<TinymistVersionCheck>()

/** Who owns the tinymist binary in use, which decides what the plugin may do about its version. */
internal enum class TinymistInstallKind {
    /** Downloaded by this plugin into its own directory; it always matches the pin. */
    PLUGIN_MANAGED,

    /** Installed by the user — a configured path, or one found on PATH — and never touched. */
    USER_MANAGED,
}

/** What the startup check decided to do about the tinymist in use. */
internal sealed interface TinymistVersionAction {
    /** Already the version this plugin is tested with. */
    data object InStep : TinymistVersionAction

    /** Tell the user their tinymist is behind, and leave the updating to them. */
    data object Prompt : TinymistVersionAction

    /** Say nothing, for [reason]. */
    data class Ignore(val reason: String) : TinymistVersionAction
}

/**
 * Decides whether the tinymist in use calls for a prompt.
 *
 * A plugin-managed binary never does: it lives in a folder named after the pinned version, so it
 * matches the pin by construction (see [TinymistManager.getDownloadedBinaryPath]). A user-managed
 * one is reported only when it is *behind* the version this plugin is tested with — that is the
 * one thing a user's package manager cannot tell them. One that is ahead is their own informed
 * choice, and is left alone.
 *
 * [skippedVersion] suppresses the prompt for that pinned version and anything older, so a plugin
 * update that moves the pin raises it afresh.
 */
internal fun decideVersionAction(
    installKind: TinymistInstallKind,
    installed: TinymistVersion,
    pinned: TinymistVersion,
    promptsEnabled: Boolean,
    skippedVersion: TinymistVersion?,
): TinymistVersionAction = when {
    installKind == TinymistInstallKind.PLUGIN_MANAGED -> TinymistVersionAction.Ignore("plugin-managed binaries follow the pin")
    installed == pinned -> TinymistVersionAction.InStep
    installed > pinned -> TinymistVersionAction.Ignore("tinymist $installed is newer than the tested $pinned")
    !promptsEnabled -> TinymistVersionAction.Ignore("version prompts are switched off")
    skippedVersion != null && pinned <= skippedVersion -> TinymistVersionAction.Ignore("version $pinned was skipped")
    else -> TinymistVersionAction.Prompt
}

/**
 * Whether [resolvedPath] is a binary this plugin downloaded, meaning anywhere under [downloadDir].
 * Canonical paths, so a symlink or Windows' case-blind filenames cannot disguise the plugin's own
 * binary as the user's.
 */
internal fun isPluginManagedBinary(resolvedPath: String, downloadDir: File): Boolean {
    val resolved = File(resolvedPath)
    val (file, dir) = runCatching { resolved.canonicalFile to downloadDir.canonicalFile }.getOrDefault(resolved.absoluteFile to downloadDir.absoluteFile)
    return FileUtil.isAncestor(dir, file, true)
}

/**
 * Runs once per IDE session, as the first project opens: tidies downloads left behind by earlier
 * pins, and checks a user-managed tinymist against the version this plugin is tested with.
 */
internal object TinymistVersionCheck {

    private const val VERSION_TIMEOUT_MS = 10_000

    @RequiresBackgroundThread
    fun run(project: Project?) {
        val manager = TinymistManager.getInstance()

        // Downloads made for an earlier pin, and binaries moved aside during a re-download, could
        // not be deleted while a language server was still running from them. Nothing holds them
        // at startup.
        TinymistDownloadService.cleanUpReplacedBinaries(manager.getDownloadedBinaryPath())
        manager.cleanUpStaleDownloads()

        val binaryPath = manager.resolveTinymistPath()
        if (binaryPath == null) {
            LOG.debug { "No tinymist resolved; the first .typ file opened will download the pinned version" }
            return
        }
        if (isPluginManagedBinary(binaryPath, manager.getDownloadDir())) {
            LOG.debug { "Tinymist at $binaryPath is plugin-managed and matches the pin" }
            return
        }

        val settings =
            TypstSettingsState.getInstance() // Checked before running the binary: with prompts off there is nothing to use the answer for.
        if (!settings.notifyTinymistUpdates) {
            LOG.debug { "Version prompts are switched off; not checking the user-managed tinymist" }
            return
        }
        val pin = PlatformConfig.tinymistPin ?: return
        val installed = readInstalledVersion(binaryPath)
        if (installed == null) {
            LOG.info("Could not read a version from $binaryPath; skipping the tinymist version check")
            return
        }

        val action = decideVersionAction(
            installKind = TinymistInstallKind.USER_MANAGED,
            installed = installed,
            pinned = pin.version,
            promptsEnabled = settings.notifyTinymistUpdates,
            skippedVersion = TinymistVersion.parse(settings.skippedTinymistVersion),
        )
        when (action) {
            TinymistVersionAction.InStep -> LOG.debug { "User-managed tinymist $installed matches the tested version" }
            TinymistVersionAction.Prompt -> {
                LOG.info("User-managed tinymist $installed is older than the tested ${pin.version}; prompting")
                notifyBehindPin(project, installed, pin, binaryPath)
            }

            is TinymistVersionAction.Ignore -> LOG.info("Not prompting about tinymist $installed: ${action.reason}")
        }
    }

    /**
     * Runs `tinymist -V`, whose output is a single `tinymist <version>` line. The long
     * `--version` form is deliberately not used: it prints a build-info block in which the
     * version is one labelled field among several.
     */
    @RequiresBackgroundThread
    internal fun readInstalledVersion(binaryPath: String): TinymistVersion? = try {
        val output = ExecUtil.execAndGetOutput(GeneralCommandLine(binaryPath, "-V"), VERSION_TIMEOUT_MS)
        TinymistVersion.parseFirst(output.stdout) ?: TinymistVersion.parseFirst(output.stderr)
    } catch (e: Exception) {
        LOG.debug(e) { "Could not run $binaryPath -V: ${e.message}" }
        null
    }

    private fun notifyBehindPin(project: Project?, installed: TinymistVersion, pin: TinymistPin, binaryPath: String) {
        val settings = TypstSettingsState.getInstance()
        NotificationGroupManager.getInstance()
            .getNotificationGroup(TYPST_UPDATE_NOTIFICATION_GROUP_ID)
            .createNotification(
                message("notification.tinymist.behind.title", pin.version),
                message("notification.tinymist.behind.body", pin.version, installed, binaryPath),
                NotificationType.INFORMATION,
            )
            .addAction(NotificationAction.createSimple(message("notification.tinymist.behind.action.releaseNotes")) {
                BrowserUtil.browse(pin.releaseUrl)
            })
            .addAction(NotificationAction.createSimpleExpiring(message("notification.tinymist.behind.action.skip")) {
                settings.skippedTinymistVersion = pin.version.toString()
                LOG.info("Skipping the tinymist ${pin.version} prompt at the user's request")
            })
            .addAction(NotificationAction.createSimpleExpiring(message("notification.tinymist.behind.action.disable")) {
                settings.notifyTinymistUpdates = false
                LOG.info("Tinymist version prompts switched off at the user's request")
            })
            .notify(project)
    }
}

/**
 * Runs [TinymistVersionCheck] as the first project opens. Once per IDE session: the binary is
 * shared by every project, so a second project opening has nothing new to say about it. A plugin
 * update reloads this class, which is what lets a newly pinned version be checked straight away.
 */
class TinymistVersionStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        if (ApplicationManager.getApplication().isUnitTestMode) return
        if (!checkedThisSession.compareAndSet(false, true)) return
        withContext(Dispatchers.IO) { TinymistVersionCheck.run(project) }
    }
}

/**
 * Set once the startup check has run in this IDE session. Kept at file level rather than in a
 * companion object, since extension implementations may only hold a logger and constants there.
 */
private val checkedThisSession = AtomicBoolean(false)
