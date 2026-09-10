package com.github.pndv.typstrenderer.lsp

import com.github.pndv.typstrenderer.settings.TypstSettingsState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.wsl.WSLCommandLineOptions
import com.intellij.execution.wsl.WSLDistribution
import com.intellij.execution.wsl.WslDistributionManager
import com.intellij.execution.wsl.WslPath
import com.intellij.openapi.project.Project

/**
 * How the plugin decides whether tinymist should run inside a WSL distribution rather than
 * natively on the Windows host.
 *
 * `AUTO` (the default) follows the project: a project opened from a `\\wsl$\<distro>\...` or
 * `\\wsl.localhost\<distro>\...` path runs tinymist inside that distro, anything else runs the
 * native binary. `ALWAYS`/`NEVER` let a user override that inference — e.g. force WSL execution
 * for a project that happens to live on an ordinary Windows path, or force the native binary
 * despite a WSL-hosted project.
 */
enum class WslExecutionMode {
    AUTO, ALWAYS, NEVER;

    companion object {
        /** Falls back to [AUTO] for a blank or unrecognised (hand-edited XML) stored value. */
        fun fromStoredValue(value: String): WslExecutionMode = entries.find { it.name == value } ?: AUTO
    }
}

/** Identifies the WSL distribution tinymist should be launched in. */
data class WslTarget(val distroId: String)

/**
 * Resolves the WSL distribution id encoded in a `\\wsl$\<distro>\...` / `\\wsl.localhost\<distro>\...`
 * UNC path, or `null` if [path] is not such a path (an ordinary Windows path, or unset).
 *
 * Thin wrapper over the platform's own UNC parser — no reimplementation of the two accepted
 * UNC prefixes or their quirks.
 */
fun detectWslDistroFromPath(path: String?): String? =
    path?.let { WslPath.parseWindowsUncPath(it)?.distributionId }

/**
 * Pure decision core: given the configured [mode] and distro override, and the distro (if any)
 * [detectedDistro] from the project's own path, decides whether tinymist should run inside WSL
 * and in which distribution.
 *
 * - `NEVER` always runs natively.
 * - `AUTO` runs in WSL only when a distro was detected from the project path; [configuredDistro]
 *   overrides *which* distro is used but does not by itself turn WSL execution on.
 * - `ALWAYS` runs in WSL whenever a distro is known from either source, preferring the explicit
 *   override; with neither source available it falls back to native (there is nothing to launch
 *   in).
 */
internal fun decideWslTarget(
    mode: WslExecutionMode,
    configuredDistro: String,
    detectedDistro: String?,
): WslTarget? = when (mode) {
    WslExecutionMode.NEVER -> null
    WslExecutionMode.AUTO -> detectedDistro?.let { WslTarget(configuredDistro.ifBlank { it }) }
    WslExecutionMode.ALWAYS -> configuredDistro.ifBlank { detectedDistro }?.let(::WslTarget)
}

/**
 * [decideWslTarget] wired to the real settings and the given [project]'s own path. `null` on a
 * non-Windows host — WSL only exists on Windows, and [TinymistManager.isWindows] gates every
 * other WSL code path the same way.
 */
fun resolveWslTargetForProject(project: Project): WslTarget? {
    if (!TinymistManager.isWindows()) return null
    val settings = TypstSettingsState.getInstance()
    return decideWslTarget(settings.wslMode, settings.wslDistro, detectWslDistroFromPath(project.basePath))
}

/** Resolves the installed [WSLDistribution] for [distroId], or `null` if it is no longer installed. */
fun findInstalledWslDistribution(distroId: String): WSLDistribution? =
    WslDistributionManager.getInstance().installedDistributions.find { it.id == distroId }

/**
 * Wraps [commandLine] — built with [distribution]'s own Linux-native executable path and
 * arguments — for execution inside [distribution] via `wsl.exe`.
 *
 * [remoteWorkingDir], when given, must already be a Linux-native path (see
 * [WSLDistribution.getWslPath]) — it becomes the working directory *inside* the distro, which is
 * distinct from (and more useful here than) [GeneralCommandLine.getWorkDirectory], a Windows-side
 * concept that means little for a process actually running inside WSL.
 */
fun patchCommandLineForWsl(
    commandLine: GeneralCommandLine,
    project: Project?,
    distribution: WSLDistribution,
    remoteWorkingDir: String?,
): GeneralCommandLine {
    val options = WSLCommandLineOptions()
    remoteWorkingDir?.let { options.setRemoteWorkingDirectory(it) }
    return distribution.patchCommandLine(commandLine, project, options)
}

/**
 * The path a tinymist client descriptor should report for [windowsPath] (a `VirtualFile.path`)
 * when tinymist runs inside [distribution] — `null` when no translation applies, so the caller
 * falls back to the platform's own default.
 *
 * The platform's default `getFilePath`/`getFileUri` use `VirtualFile.path` verbatim. For a file
 * hosted at `\\wsl.localhost\<distro>\...` (or `\\wsl$\<distro>\...`), that path is a VFS
 * UNC-style string (`//wsl.localhost/<distro>/...`); building a `file://` URI directly from it
 * makes the distro name the URI's *authority* rather than part of the path, so tinymist — running
 * natively inside the distro, expecting a plain `file:///home/...` URI — cannot match it against
 * its own `$root`, and every export/compile silently fails to resolve a destination. Translating
 * to the distro's own native path first avoids that mismatch entirely.
 */
@Suppress("DEPRECATION") // see the suppression on buildTinymistCommandLine in TinymistLspServerDescriptor.kt
fun wslAwareFilePath(windowsPath: String, distribution: WSLDistribution?): String? =
    distribution?.getWslPath(windowsPath)

/**
 * Reverse of [wslAwareFilePath]: translates a Linux-native path tinymist reports back (e.g. in a
 * go-to-definition result pointing at another file) to the Windows-side path the IDE's VFS
 * actually indexes. Returns [linuxPath] unchanged when [distribution] is `null`.
 */
fun windowsPathFromWsl(linuxPath: String, distribution: WSLDistribution?): String =
    distribution?.getWindowsPath(linuxPath) ?: linuxPath
