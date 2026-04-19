package com.github.pndv.typstrenderer.lsp

import com.github.pndv.typstrenderer.settings.TypstSettingsState
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import java.io.File

/**
 * Resolves the tinymist and typst binary paths using the following priority:
 * 1. User-configured path in settings
 * 2. Binary found on system PATH and well-known install locations
 * 3. Previously downloaded binary in the plugin data directory
 * 4. `null` (not found)
 *
 * Note: IntelliJ as a GUI app on macOS/Windows may not inherit the user's full shell/terminal
 * PATH, so we also probe well-known directories where cargo, homebrew, scoop, etc. install binaries.
 */
@Service(Service.Level.APP)
class TinymistManager {

    /**
     * Resolves the tinymist binary path, or null if not available anywhere.
     */
    fun resolveTinymistPath(): String? = resolveBinaryPath(
        configuredPath = TypstSettingsState.getInstance().tinymistPath,
        findOnPath = { findBinary("tinymist") },
        downloadedBinary = getDownloadedBinaryPath(),
    )

    /**
     * Resolves the typst CLI binary path, or null if not available.
     */
    fun resolveTypstPath(): String? = resolveBinaryPath(
        configuredPath = TypstSettingsState.getInstance().typstPath,
        findOnPath = { findBinary("typst") },
        downloadedBinary = getDownloadedTypstPath(),
    )

    /**
     * Returns the directory where the downloaded tinymist binary is stored.
     */
    fun getDownloadDir(): File {
        val dir = File(PathManager.getPluginsPath(), "typst-renderer${File.separator}bin")
        dir.mkdirs()
        return dir
    }

    /**
     * Returns the expected path for the downloaded tinymist binary.
     */
    fun getDownloadedBinaryPath(): File {
        val binaryName = if (isWindows()) "tinymist.exe" else "tinymist"
        return File(getDownloadDir(), binaryName)
    }

    /**
     * Returns the expected path for the downloaded typst CLI binary.
     */
    fun getDownloadedTypstPath(): File {
        val binaryName = if (isWindows()) "typst.exe" else "typst"
        return File(getDownloadDir(), binaryName)
    }

    companion object {
        fun getInstance(): TinymistManager =
            ApplicationManager.getApplication().getService(TinymistManager::class.java)

        val osName: String? = System.getProperty("os.name")
        val osArch: String? = System.getProperty("os.arch")
        fun isWindows(): Boolean = osName?.lowercase()?.contains("win") ?: false
        fun isMacOS(): Boolean = osName?.lowercase()?.contains("mac") ?: false
        fun isLinux(): Boolean = osName?.lowercase()?.contains("linux") ?: false
        fun isArm64(): Boolean = osArch?.let { it == "aarch64" || it == "arm64" } ?: false

        /**
         * Determines the GitHub release asset name for tinymist on the current platform.
         * Returns null if the host platform is not in tinymist's supported matrix.
         */
        fun getPlatformAssetName(): String? {
            val key = PlatformKey.currentHost(osName, osArch) ?: return null
            return PlatformConfig.tinymist.assetFor(key)?.asset
        }

        /**
         * Determines the GitHub release asset name for the Typst CLI on the current platform.
         * Typst releases are compressed archives (.tar.xz on Unix, .zip on Windows).
         * Returns null if the host platform is not in typst's supported matrix.
         */
        fun getTypstPlatformAssetName(): String? {
            val key = PlatformKey.currentHost(osName, osArch) ?: return null
            return PlatformConfig.typst.assetFor(key)?.asset
        }

        /**
         * Checks whether a file exists and is a runnable binary.
         *
         * On Unix, [File.canExecute] checks the executable permission bit.
         * On Windows, [File.canExecute] returns true for any readable file with certain
         * extensions, so we additionally verify the file has a recognized executable extension.
         */
        /**
         * Pure-function core of the 3-stage binary resolution fallback.
         * The instance methods [resolveTinymistPath] / [resolveTypstPath] are
         * thin wrappers that supply the real settings, PATH lookup, and
         * downloaded file. Exposed for unit testing without an IntelliJ fixture.
         */
        internal fun resolveBinaryPath(
            configuredPath: String,
            findOnPath: () -> String?,
            downloadedBinary: File,
        ): String? {
            if (configuredPath.isNotBlank() && isBinaryExecutable(File(configuredPath))) {
                return configuredPath
            }
            findOnPath()?.let { return it }
            if (isBinaryExecutable(downloadedBinary)) {
                return downloadedBinary.absolutePath
            }
            return null
        }

        internal fun isBinaryExecutable(file: File): Boolean {
            if (!file.isFile) return false
            if (isWindows()) {
                val ext = file.extension.lowercase()
                return ext in listOf("exe", "cmd", "bat", "com") || file.canExecute()
            }
            return file.canExecute()
        }

        /**
         * Well-known directories where tools like tinymist/typst are commonly installed.
         * Returns only directories relevant to the current OS.
         */
        private fun getWellKnownDirs(): List<String> {
            val home = File(System.getProperty("user.home"))

            return buildList {
                if (isWindows()) {
                    addWindowsDirs(home)
                } else {
                    addUnixDirs(home)
                }
            }.distinct()
        }

        /**
         * Windows-specific well-known install directories.
         */
        internal fun MutableList<String>.addWindowsDirs(home: File) {
            // Cargo (Rust) — most common install method for both tinymist and typst
            add(File(home, ".cargo${File.separator}bin").absolutePath)

            // Scoop
            add(File(home, "scoop${File.separator}shims").absolutePath)

            // WinGet / App Installer default paths
            val localAppData = System.getenv("LOCALAPPDATA")
            if (localAppData != null) {
                add(File(localAppData, "Microsoft${File.separator}WinGet${File.separator}Links").absolutePath)
                add(File(localAppData, "Programs${File.separator}tinymist").absolutePath)
                add(File(localAppData, "Programs${File.separator}typst").absolutePath)
            }

            // Chocolatey
            val chocoInstall = System.getenv("ChocolateyInstall")
            if (chocoInstall != null) {
                add(File(chocoInstall, "bin").absolutePath)
            } else {
                add(File("C:${File.separator}ProgramData${File.separator}chocolatey${File.separator}bin").absolutePath)
            }

            // Program Files
            val programFiles = System.getenv("ProgramFiles")
            if (programFiles != null) {
                add(File(programFiles, "tinymist").absolutePath)
                add(File(programFiles, "typst").absolutePath)
            }

            // Common user-local bin
            add(File(home, ".local${File.separator}bin").absolutePath)
        }

        /**
         * macOS and Linux well-known install directories.
         */
        internal fun MutableList<String>.addUnixDirs(home: File) {
            // Cargo (Rust) — most common install method for both tinymist and typst
            add(File(home, ".cargo/bin").absolutePath)

            // Homebrew
            if (isMacOS()) {
                add("/opt/homebrew/bin")           // Apple Silicon
                add("/usr/local/bin")              // Intel Mac
            }
            if (isLinux()) {
                add("/home/linuxbrew/.linuxbrew/bin")
                add(File(home, ".linuxbrew/bin").absolutePath)
            }

            // Common system paths
            add("/usr/local/bin")
            add("/usr/bin")

            // Nix
            add(File(home, ".nix-profile/bin").absolutePath)
            add("/run/current-system/sw/bin")

            // User local
            add(File(home, ".local/bin").absolutePath)
            add(File(home, ".volta/bin").absolutePath)
        }

        /**
         * Searches for a binary by name on the system PATH and well-known install directories.
         */
        fun findBinary(binaryName: String): String? {
            val extensions = if (isWindows()) listOf(".exe", ".cmd", ".bat", "") else listOf("")

            // Combine system PATH dirs with well-known dirs
            val pathDirs = System.getenv("PATH")?.split(File.pathSeparator).orEmpty()
            val allDirs = (pathDirs + getWellKnownDirs()).distinct()

            for (dir in allDirs) {
                for (ext in extensions) {
                    val candidate = File(dir, binaryName + ext)
                    if (isBinaryExecutable(candidate)) {
                        return candidate.absolutePath
                    }
                }
            }
            return null
        }
    }
}
