package com.github.pndv.typstrenderer.lsp

import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for [TinymistVersion] and for reading the pinned version out of `platforms.json`'s
 * download URL ([parseTinymistPin]).
 */
class TinymistVersionTest {

    // ---- parsing ----

    @Test
    fun parsesAReleaseTagWithOrWithoutTheVPrefix() {
        assertEquals(TinymistVersion(0, 15, 2), TinymistVersion.parse("v0.15.2"))
        assertEquals(TinymistVersion(0, 15, 2), TinymistVersion.parse("0.15.2"))
        assertEquals(TinymistVersion(0, 15, 2), TinymistVersion.parse("  v0.15.2  "))
    }

    @Test
    fun parsesAPreReleaseTag() {
        val version = TinymistVersion.parse("v0.15.4-rc1")
        assertEquals(TinymistVersion(0, 15, 4, "rc1"), version)
    }

    @Test
    fun ignoresBuildMetadata() {
        assertEquals(TinymistVersion(1, 2, 3), TinymistVersion.parse("v1.2.3+abc123"))
    }

    @Test
    fun rejectsTextThatIsNotJustAVersion() {
        assertNull(TinymistVersion.parse("tinymist 0.15.2"))
        assertNull(TinymistVersion.parse("nightly"))
        assertNull(TinymistVersion.parse("v0.15"))
    }

    @Test
    fun readsTheVersionOutOfTheBinarysOwnOutput() { // Exactly what `tinymist -V` prints.
        assertEquals(TinymistVersion(0, 15, 2), TinymistVersion.parseFirst("tinymist 0.15.2\n"))
    }

    @Test
    fun readsNoVersionFromOutputOfSomethingElseEntirely() { // A binary that is not tinymist at all — the path the user pointed at the wrong file.
        assertNull(TinymistVersion.parseFirst("bash: no such file or directory"))
    }

    // ---- ordering ----

    @Test
    fun ordersByMajorThenMinorThenPatch() {
        assertTrue(TinymistVersion.parse("0.15.8")!! > TinymistVersion.parse("0.15.2")!!)
        assertTrue(TinymistVersion.parse("0.16.0")!! > TinymistVersion.parse("0.15.8")!!)
        assertTrue(TinymistVersion.parse("1.0.0")!! > TinymistVersion.parse("0.99.99")!!)
    }

    @Test
    fun aPreReleaseSortsBeforeTheReleaseItLeadsUpTo() { // A user who built a release candidate of the pinned version is still behind it.
        assertTrue(TinymistVersion.parse("0.15.8-rc1")!! < TinymistVersion.parse("0.15.8")!!)
        assertTrue(TinymistVersion.parse("0.15.4-rc1")!! < TinymistVersion.parse("0.15.4-rc2")!!)
    }

    @Test
    fun preReleaseIdentifiersFollowTheSpecRatherThanCountingOrder() { // Semantic versioning compares alphanumeric identifiers in ASCII order, so "rc10" ranks
        // below "rc2" however odd that reads. Kept to the spec rather than quietly deviating.
        assertTrue(TinymistVersion.parse("0.15.4-rc10")!! < TinymistVersion.parse("0.15.4-rc2")!!)
    }

    @Test
    fun printsTheWayItParses() { // The version doubles as the name of the download folder, so the round trip matters.
        assertEquals("0.15.8", TinymistVersion.parse("v0.15.8").toString())
        assertEquals("0.15.4-rc1", TinymistVersion.parse("v0.15.4-rc1").toString())
    }

    // ---- the pin ----

    @Test
    fun readsThePinnedVersionAndReleasePageOutOfTheDownloadUrl() {
        val pin = parseTinymistPin("https://github.com/Myriad-Dreamin/tinymist/releases/download/v0.15.8")

        assertNotNull(pin)
        assertEquals(TinymistVersion(0, 15, 8), pin!!.version)
        assertEquals("https://github.com/Myriad-Dreamin/tinymist/releases/tag/v0.15.8", pin.releaseUrl)
    }

    @Test
    fun readsNoPinFromAUrlThatPinsNothing() { // "latest" pins nothing, and a test override pointing at a local server is not a release.
        assertNull(parseTinymistPin("https://github.com/Myriad-Dreamin/tinymist/releases/latest/download"))
        assertNull(parseTinymistPin("http://localhost:1234/download"))
    }
}
