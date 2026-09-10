package com.github.pndv.typstrenderer.lsp

import com.github.pndv.typstrenderer.lsp.TinymistManager.Companion.resolveWslBinaryPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the 2-stage WSL binary resolution fallback in [TinymistManager].
 *
 * [resolveWslBinaryPath] is the pure core of [TinymistManager.resolveTinymistPathForWsl];
 * `findOnPath` stands in for the real `which tinymist` probe (which spawns `wsl.exe`), same
 * fixture-free pattern as [BinaryResolutionTest] for the native resolver. Unlike the native
 * resolver's stages, a configured path here is never checked for existence: it lives inside the
 * distro's own filesystem, which the host cannot reliably stat.
 */
class TinymistManagerWslTest {

    @Test
    fun resolveWslBinary_configuredPathSet_returnsConfiguredPathWithoutProbing() {
        var probed = false

        val result = resolveWslBinaryPath(
            configuredPath = "/usr/local/bin/tinymist",
            findOnPath = { probed = true; "/should/not/be/used" },
        )

        assertEquals("/usr/local/bin/tinymist", result)
        assertEquals("configured path must short-circuit before probing the distro", false, probed)
    }

    @Test
    fun resolveWslBinary_configuredPathBlank_fallsThroughToWhichProbe() {
        val result = resolveWslBinaryPath(
            configuredPath = "",
            findOnPath = { "/home/user/.cargo/bin/tinymist" },
        )

        assertEquals("/home/user/.cargo/bin/tinymist", result)
    }

    @Test
    fun resolveWslBinary_configuredPathBlankAndProbeMisses_returnsNull() {
        val result = resolveWslBinaryPath(
            configuredPath = "",
            findOnPath = { null },
        )

        assertNull("Should return null so the caller surfaces the WSL-not-found notification", result)
    }

    @Test
    fun resolveWslBinary_configuredPathBlankButWhitespace_treatedAsBlank() {
        val result = resolveWslBinaryPath(
            configuredPath = "   ",
            findOnPath = { "/usr/bin/tinymist" },
        )

        assertEquals("/usr/bin/tinymist", result)
    }
}
