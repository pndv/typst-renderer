package com.github.pndv.typstrenderer.lsp

import com.github.pndv.typstrenderer.settings.TypstSettingsState
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.util.io.FileUtil
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves the tinymist binary path using the following priority:
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

    private val log = logger<TinymistManager>()
    private lateinit var downloadDir: File

    /**
     * Resolves the tinymist binary path, or null if not available anywhere.
     */
    fun resolveTinymistPath(): String? = resolveBinaryPath(
        configuredPath = TypstSettingsState.getInstance().tinymistPath,
        findOnPath = { findBinary("tinymist") },
        downloadedBinary = getDownloadedBinaryPath(),
    )

    /**
     * Returns the directory where the downloaded tinymist binary is stored.
     *
     * Under the IDE's **system** directory rather than inside the plugin's own installation
     * folder. Installing a plugin update deletes that folder and unpacks the new distribution in
     * its place, taking anything not in the distribution with it — so the binary was thrown away
     * on every update and silently downloaded again, a missing binary being indistinguishable
     * from a first run. The system directory survives updates, and is where downloaded tooling
     * belongs.
     *
     * No migration from the old location: the same deletion that caused the bug guarantees there
     * is nothing left to move by the time this version runs.
     */
    fun getDownloadDir(): File {
        if (::downloadDir.isInitialized) {
            return downloadDir
        }

        downloadDir = File(PathManager.getSystemPath(), "typst-renderer${File.separator}bin")
        return downloadDir
    }

    /**
     * Returns the expected path for the downloaded tinymist binary: inside a folder named after
     * the version this plugin pins, such as `bin/0.15.8/tinymist.exe`.
     *
     * The folder is what makes a downloaded tinymist follow the plugin. A binary used to be
     * downloaded once to a fixed path and kept forever, so a plugin update that moved to a newer
     * tinymist never reached an existing install. Now a new pin names a folder that does not
     * exist yet, the binary resolves as missing, and the ordinary first-install download fetches
     * the pinned version. Pinning back to an earlier release rolls users back the same way — and
     * because the new binary never lands on the path of the one running, nothing has to be
     * replaced underneath a live language server.
     */
    fun getDownloadedBinaryPath(): File =
        managedBinaryPath(getDownloadDir(), PlatformConfig.tinymistPin?.version, isWindows())

    /**
     * Removes downloaded binaries that no longer match the pin, once the pinned one is in place.
     * Leaves everything alone until then: deleting the old binary before the new one has arrived
     * would gain nothing, since only the pinned one is ever resolved.
     */
    fun cleanUpStaleDownloads() {
        val current = getDownloadedBinaryPath()
        if (!isBinaryExecutable(current)) {
            log.debug("Pinned tinymist not downloaded yet; leaving older downloads in place")
            return
        }
        val configured = TypstSettingsState.getInstance().tinymistPath.takeIf { it.isNotBlank() }?.let(::File)
        cleanUpStaleManagedBinaries(getDownloadDir(), current, preserve = configured)
    }

    companion object {
        private val log = logger<TinymistManager>()
        private val binaryCache: MutableMap<String, String> = ConcurrentHashMap<String, String>()

        fun getInstance(): TinymistManager = ApplicationManager.getApplication().getService(TinymistManager::class.java)

        val osName: String? = System.getProperty("os.name")
        val osArch: String? = System.getProperty("os.arch")

        fun isWindows(): Boolean = osName?.lowercase()?.contains("win") ?: false
        fun isMacOS(): Boolean = osName?.lowercase()?.contains("mac") ?: false
        fun isLinux(): Boolean = osName?.lowercase()?.contains("linux") ?: false

        /**
         * Where the downloaded binary for [pinned] lives under [downloadDir]. Falls back to the
         * folder itself when there is no pin — the layout used before downloads were versioned.
         */
        internal fun managedBinaryPath(downloadDir: File, pinned: TinymistVersion?, windows: Boolean): File {
            val binaryName = if (windows) "tinymist.exe" else "tinymist"
            return if (pinned == null) File(downloadDir, binaryName) else File(
                File(downloadDir, pinned.toString()), binaryName
            )
        }

        /**
         * Deletes every download under [downloadDir] other than [current]: folders named after
         * other versions, plus tinymist files at the top level — the unversioned layout used
         * before, binaries moved aside during a replacement, interrupted downloads.
         *
         * Only entries this plugin creates are candidates, so nothing else a user might keep there
         * is touched. [preserve] is a path the user configured explicitly; an entry holding it is
         * kept even when stale, because deleting a binary the settings point at would be a
         * surprise. Deletion is best-effort: on Windows a binary a live language server is still
         * running from cannot be deleted, and simply goes on the next sweep.
         */
        internal fun cleanUpStaleManagedBinaries(downloadDir: File, current: File, preserve: File?) {
            val currentDir = current.parentFile ?: return
            if (FileUtil.filesEqual(currentDir, downloadDir)) return // Unversioned layout: nothing to compare against.

            for (entry in downloadDir.listFiles().orEmpty()) {
                val ours =
                    if (entry.isDirectory) TinymistVersion.parse(entry.name) != null else entry.name.startsWith("tinymist")
                if (!ours || FileUtil.filesEqual(entry, currentDir)) continue
                if (preserve != null && FileUtil.isAncestor(entry, preserve, false)) {
                    log.info("Keeping stale tinymist download ${entry.absolutePath}: the settings point at it")
                    continue
                }
                if (entry.deleteRecursively()) {
                    log.info("Removed stale tinymist download ${entry.absolutePath}")
                } else {
                    log.debug("Stale tinymist download ${entry.absolutePath} is still in use; leaving it for later")
                }
            }
        }

        /**
         * Determines the GitHub release asset name for tinymist on the current platform.
         * Returns null if the host platform is not in tinymist's supported matrix.
         */
        fun getPlatformAssetName(): String? {
            val key = PlatformKey.currentHost(osName, osArch) ?: return null
            return PlatformConfig.tinymist.assetFor(key)?.asset
        }

        /**
         * Checks whether a file exists and is a runnable binary.
         *
         * On Unix, [File.canExecute] checks the executable permission bit.
         * On Windows, [File.canExecute] returns true for any readable file with certain
         * extensions, so we additionally verify the file has a recognised executable extension.
         *
         * Pure-function core of the 3-stage binary resolution fallback.
         * The instance method [resolveTinymistPath] is a thin wrapper that
         * supplies the real settings, PATH lookup, and downloaded file.
         * Exposed for unit testing without an IntelliJ fixture.
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
                return ext in listOf("exe", "cmd", "bat", "com")
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
        internal fun MutableList<String>.addWindowsDirs(home: File) { // Cargo (Rust) — most common install method for both tinymist and typst
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
        internal fun MutableList<String>.addUnixDirs(home: File) { // Cargo (Rust) — most common install method for both tinymist and typst
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
        fun findBinary(binaryName: String): String? { // Return cached binary if it exists and is executable, else invalidate cache entry
            binaryCache[binaryName]?.let {
                if (isBinaryExecutable(File(it))) {
                    return it
                } else {
                    binaryCache.remove(binaryName)
                }
            }

            val extensions = if (isWindows()) listOf(".exe", ".cmd", ".bat", "") else listOf("")

            // Combine system PATH dirs with well-known dirs
            val pathDirs = System.getenv("PATH")?.split(File.pathSeparator).orEmpty()
            val allDirs = (pathDirs + getWellKnownDirs()).distinct()

            for (dir in allDirs) {
                for (ext in extensions) {
                    val candidate = File(dir, binaryName + ext)
                    if (isBinaryExecutable(candidate)) {
                        val binaryPath = candidate.absolutePath
                        binaryCache[binaryName] = binaryPath
                        return binaryPath
                    }
                }
            }

            log.debug("Binary $binaryName not found on system PATH or well-known install directories")
            return null
        }
    }
}
