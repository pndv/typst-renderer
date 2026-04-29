package com.github.pndv.typstrenderer.lsp

import org.junit.Assert.*
import org.junit.Test
import java.net.URI

/**
 * Unit tests for [PlatformConfig], [PlatformKey], and [ToolConfig].
 *
 *  - DisSupported for every intersection platform returns true
 *  - DisSupported for unsupported platforms returns false
 *  - Dper-tool assetFor still resolves on tool-specific platforms
 *  - OS/arch normalisation
 *  - platforms.json schema smoke tests
 *  - URL construction from baseUrl + asset
 *  - Unsupported-platform error enumerates supported platforms
 *
 * Plain JUnit 4 — no IntelliJ fixture required. `BasePlatformTestCase`-backed tests
 * remain JUnit 3-style in other files since that base class requires it.
 */
class PlatformConfigTest {


    @Test
    fun isSupported_forEveryIntersectionPlatform_returnsTrue() {
        val intersection = PlatformConfig.supported
        assertTrue("Intersection must be non-empty", intersection.isNotEmpty())
        for (key in intersection) {
            assertTrue("Expected isSupported($key) == true", PlatformConfig.isSupported(key))
        }
    }


    @Test
    fun isSupported_forUnsupportedPlatform_returnsFalse() {
        val negatives = listOf(
            PlatformKey("freebsd", "x64"),
            PlatformKey("solaris", "sparc"),
            PlatformKey("darwin", "riscv"),
            // windows/arm64: typst has it, tinymist does not → excluded by intersection
            PlatformKey("windows", "arm64"),
        )
        for (key in negatives) {
            assertFalse("Expected isSupported($key) == false", PlatformConfig.isSupported(key))
        }
    }


    @Test
    fun assetFor_perToolStillResolvesOnToolSpecificPlatforms() {
        val winArm = PlatformKey("windows", "arm64")
        // typst ships a windows/arm64 build
        val typstEntry = PlatformConfig.typst.assetFor(winArm)
        assertNotNull("typst should have an asset for $winArm", typstEntry)
        assertEquals("typst-aarch64-pc-windows-msvc.zip", typstEntry!!.asset)
        assertEquals("zip", typstEntry.archive)

        // tinymist does NOT — that's why the intersection excludes this platform
        assertNull("tinymist should not have an asset for $winArm", PlatformConfig.tinymist.assetFor(winArm))

        // And the gating predicate reflects the intersection
        assertFalse(PlatformConfig.isSupported(winArm))
    }


    @Test
    fun osArchNormalization_macFromSystemProperty_producesDarwin() {
        assertEquals("darwin", PlatformKey.normalizeOs("Mac OS X"))
        assertEquals("darwin", PlatformKey.normalizeOs("macOS 14.0"))
    }

    @Test
    fun osArchNormalization_windowsFromSystemProperty_producesWindows() {
        assertEquals("windows", PlatformKey.normalizeOs("Windows 11"))
        assertEquals("windows", PlatformKey.normalizeOs("Windows Server 2022"))
    }

    @Test
    fun osArchNormalization_linuxFromSystemProperty_producesLinux() {
        assertEquals("linux", PlatformKey.normalizeOs("Linux"))
    }

    @Test
    fun osArchNormalization_unknownOs_returnsNull() {
        assertNull(PlatformKey.normalizeOs("FreeBSD"))
        assertNull(PlatformKey.normalizeOs(null))
        assertNull(PlatformKey.normalizeArch("riscv64"))
        assertNull(PlatformKey.normalizeArch(null))
    }

    @Test
    fun osArchNormalization_archVariantsCollapseToCanonical() {
        assertEquals("arm64", PlatformKey.normalizeArch("aarch64"))
        assertEquals("arm64", PlatformKey.normalizeArch("arm64"))
        assertEquals("x64", PlatformKey.normalizeArch("x86_64"))
        assertEquals("x64", PlatformKey.normalizeArch("amd64"))
        assertEquals("x64", PlatformKey.normalizeArch("x64"))
    }

    @Test
    fun currentHost_forUnknownOs_returnsNull() {
        assertNull(PlatformKey.currentHost("BeOS", "x86_64"))
        assertNull(PlatformKey.currentHost("Linux", "sparc"))
    }

    @Test
    fun currentHost_forKnownHost_returnsNormalizedKey() {
        assertEquals(PlatformKey("darwin", "arm64"), PlatformKey.currentHost("Mac OS X", "aarch64"))
        assertEquals(PlatformKey("windows", "x64"), PlatformKey.currentHost("Windows 11", "amd64"))
    }

    // ---- D.34–38  platforms.json schema smoke tests ----

    @Test
    fun platformsJsonSchema_allAssetsNonEmpty() {
        for (tool in listOf(PlatformConfig.tinymist, PlatformConfig.typst)) {
            for ((key, entry) in tool.platforms) {
                assertTrue("asset empty for $key", entry.asset.isNotBlank())
            }
        }
    }

    @Test
    fun platformsJsonSchema_archiveIsNullOrRecognized() {
        val allowed = setOf(null, "tarxz", "zip")
        for (tool in listOf(PlatformConfig.tinymist, PlatformConfig.typst)) {
            for ((key, entry) in tool.platforms) {
                assertTrue(
                    "archive for $key is not one of $allowed: ${entry.archive}",
                    entry.archive in allowed,
                )
            }
        }
    }

    @Test
    fun platformsJsonSchema_baseUrlsAreValid() {
        for (tool in listOf(PlatformConfig.tinymist, PlatformConfig.typst)) {
            val uri = URI(tool.baseUrl)
            assertEquals("https", uri.scheme)
            assertNotNull(uri.host)
        }
    }

    @Test
    fun platformsJsonSchema_noDuplicatePlatformKeys() {
        for (tool in listOf(PlatformConfig.tinymist, PlatformConfig.typst)) {
            val keys = tool.platforms.keys
            assertEquals(keys.size, keys.toSet().size)
        }
    }

    @Test
    fun platformsJsonSchema_intersectionIsNonEmpty() {
        assertFalse(
            "Intersection of tinymist and typst platforms must be non-empty — " + "if someone drops a platform from one tool and the overlap vanishes, catch it here.",
            PlatformConfig.supported.isEmpty(),
        )
    }

    // ---- Supported-platforms description ----

    @Test
    fun supportedPlatformsDescription_listsAllIntersectionPlatforms() {
        val desc = PlatformConfig.supportedPlatformsDescription()
        for (key in PlatformConfig.supported) {
            assertTrue(
                "Description should mention $key, got: $desc",
                desc.contains(key.toString()),
            )
        }
    }


    @Test
    fun downloadUrl_isBaseUrlSlashAsset_forEveryEntry() {
        for ((toolName, tool) in mapOf("tinymist" to PlatformConfig.tinymist, "typst" to PlatformConfig.typst)) {
            for ((key, entry) in tool.platforms) {
                val expected = "${tool.baseUrl}/${entry.asset}"
                val uri = URI(expected)
                assertEquals("$toolName $key: scheme", "https", uri.scheme)
                assertTrue("$toolName $key: path should end with asset", expected.endsWith(entry.asset))
            }
        }
    }


    @Test
    fun unsupportedPlatformError_listsAllSupportedPlatformsFromConfig() {
        val msg = TinymistDownloadService.unsupportedPlatformMessage()
        for (key in PlatformConfig.supported) {
            assertTrue(
                "Error message should enumerate supported platform $key — got:\n$msg",
                msg.contains(key.toString()),
            )
        }
        assertTrue(msg.contains("Settings"))
    }
}
