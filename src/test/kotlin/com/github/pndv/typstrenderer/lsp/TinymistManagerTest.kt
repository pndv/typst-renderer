package com.github.pndv.typstrenderer.lsp

import com.github.pndv.typstrenderer.lsp.TinymistManager.Companion.addUnixDirs
import com.github.pndv.typstrenderer.lsp.TinymistManager.Companion.addWindowsDirs
import com.github.pndv.typstrenderer.lsp.TinymistManager.Companion.isBinaryExecutable
import com.github.pndv.typstrenderer.lsp.TinymistManager.Companion.isLinux
import com.github.pndv.typstrenderer.lsp.TinymistManager.Companion.isMacOS
import com.github.pndv.typstrenderer.lsp.TinymistManager.Companion.isWindows
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Unit tests for host-platform helpers in [TinymistManager.Companion].
 *
 *  - Well-known install directory enumeration for Windows and Unix
 *  - isBinaryExecutable checks for Unix (canExecute) and Windows (extension fallback)
 *
 * Host-sensitive tests use JUnit 4's [assumeTrue] to skip (rather than silently no-op)
 * when the current host doesn't exercise the required OS branch. `addWindowsDirs`
 * is testable on any host because it only builds path strings; `addUnixDirs` branches
 * on `isMacOS()` / `isLinux()` so the macOS- and linux-specific assertions are gated.
 */
class TinymistManagerTest {


    @Test
    fun addWindowsDirs_includesCargoScoopWingetChocolateyProgramFilesLocalBin() {
        val fakeHome = File(System.getProperty("user.home"))
        val dirs = buildList { addWindowsDirs(fakeHome) }
        val joined = dirs.joinToString("\n")

        assertTrue("expected .cargo\\bin in: $joined", dirs.any { it.endsWith(".cargo${File.separator}bin") })
        assertTrue("expected scoop\\shims in: $joined", dirs.any { it.contains("scoop${File.separator}shims") })
        assertTrue("expected chocolatey path in: $joined", dirs.any { it.contains("chocolatey") })
        assertTrue("expected .local\\bin in: $joined", dirs.any { it.endsWith(".local${File.separator}bin") })

        // WinGet / ProgramFiles / LocalAppData entries only appear when envs are set — don't assert.
    }


    @Test
    fun addUnixDirs_macOS_includesCargoHomebrewUsrLocal() {
        assumeTrue("macOS-only branch", isMacOS())
        val fakeHome = File(System.getProperty("user.home"))
        val dirs = buildList { addUnixDirs(fakeHome) }

        assertTrue(dirs.any { it.endsWith(".cargo/bin") })
        assertTrue("expected /opt/homebrew/bin (Apple Silicon)", dirs.contains("/opt/homebrew/bin"))
        assertTrue("expected /usr/local/bin (Intel Mac / general)", dirs.contains("/usr/local/bin"))
        assertTrue(dirs.contains("/usr/bin"))
    }


    @Test
    fun addUnixDirs_linux_includesCargoLinuxbrewNixLocalBin() {
        assumeTrue("Linux-only branch", isLinux())
        val fakeHome = File(System.getProperty("user.home"))
        val dirs = buildList { addUnixDirs(fakeHome) }

        assertTrue(dirs.any { it.endsWith(".cargo/bin") })
        assertTrue("expected linuxbrew path", dirs.any { it.contains("linuxbrew") })
        assertTrue(
            "expected Nix profile",
            dirs.any { it.contains(".nix-profile/bin") || it == "/run/current-system/sw/bin" })
        assertTrue(dirs.any { it.endsWith(".local/bin") })
    }

    @Test
    fun isBinaryExecutable_windowsWithExeExtension_returnsTrue() {
        assumeTrue("Windows-only", isWindows())
        val tmp = Files.createTempFile("foo", ".exe").toFile()
        try {
            assertTrue(isBinaryExecutable(tmp))
        } finally {
            tmp.delete()
        }
    }


    @Test
    fun isBinaryExecutable_windowsWithCanExecuteFlag_returnsTrue() {
        assumeTrue("Windows-only", isWindows())
        val tmp = Files.createTempFile("foo", ".dat").toFile()
        try {
            tmp.setExecutable(true)
            assertTrue(isBinaryExecutable(tmp))
        } finally {
            tmp.delete()
        }
    }


    @Test
    fun isBinaryExecutable_unixWithCanExecute_returnsTrue() {
        assumeTrue("Unix-only", !isWindows())
        val tmp = Files.createTempFile("foo", "").toFile()
        try {
            assertTrue("could not set executable bit", tmp.setExecutable(true))
            assertTrue(isBinaryExecutable(tmp))
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun isBinaryExecutable_unixWithoutCanExecute_returnsFalse() {
        assumeTrue("Unix-only", !isWindows())
        val tmp = Files.createTempFile("foo", "").toFile()
        try {
            assertTrue(tmp.setExecutable(false))
            assertFalse(isBinaryExecutable(tmp))
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun isBinaryExecutable_nonexistentFile_returnsFalse() {
        assertFalse(isBinaryExecutable(File("/definitely/does/not/exist/foo")))
    }

    @Test
    fun isBinaryExecutable_directory_returnsFalse() {
        val dir = Files.createTempDirectory("foo").toFile()
        try {
            assertFalse(isBinaryExecutable(dir))
        } finally {
            dir.delete()
        }
    }
}
