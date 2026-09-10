package com.github.pndv.typstrenderer.lsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for the pure decision core of WSL execution: [decideWslTarget] (does tinymist run
 * inside WSL, and in which distribution) and [detectWslDistroFromPath] (parsing the distro out
 * of a project's UNC path). Same fixture-free pattern as [TypstRootResolverTest] and
 * [BinaryResolutionTest] — no IDE fixture or real WSL installation needed.
 */
class WslExecutionTest {

    // ---- decideWslTarget ----

    @Test
    fun decideWslTarget_never_alwaysRunsNatively() {
        assertNull(decideWslTarget(WslExecutionMode.NEVER, "", null))
        assertNull(decideWslTarget(WslExecutionMode.NEVER, "Ubuntu", "Ubuntu"))
    }

    @Test
    fun decideWslTarget_auto_noDetectedDistro_runsNatively() { // Even an explicit distro override should not turn WSL on by itself in
        // AUTO mode: it only picks *which* distro once one was already detected
        // from the project path.
        assertNull(decideWslTarget(WslExecutionMode.AUTO, "Ubuntu", null))
    }

    @Test
    fun decideWslTarget_auto_detectedDistro_usesDetectedDistro() {
        val target = decideWslTarget(WslExecutionMode.AUTO, "", "Ubuntu-22.04")
        assertEquals(WslTarget("Ubuntu-22.04"), target)
    }

    @Test
    fun decideWslTarget_auto_detectedDistroWithOverride_usesOverride() {
        val target = decideWslTarget(WslExecutionMode.AUTO, "Debian", "Ubuntu-22.04")
        assertEquals(WslTarget("Debian"), target)
    }

    @Test
    fun decideWslTarget_always_noDistroKnown_fallsBackToNative() {
        assertNull(decideWslTarget(WslExecutionMode.ALWAYS, "", null))
    }

    @Test
    fun decideWslTarget_always_usesConfiguredDistroOverDetected() {
        val target = decideWslTarget(WslExecutionMode.ALWAYS, "Debian", "Ubuntu-22.04")
        assertEquals(WslTarget("Debian"), target)
    }

    @Test
    fun decideWslTarget_always_fallsBackToDetectedDistroWhenNoOverride() {
        val target = decideWslTarget(WslExecutionMode.ALWAYS, "", "Ubuntu-22.04")
        assertEquals(WslTarget("Ubuntu-22.04"), target)
    }

    // ---- WslExecutionMode.fromStoredValue ----

    @Test
    fun fromStoredValue_recognisedValue_roundTrips() {
        assertEquals(WslExecutionMode.ALWAYS, WslExecutionMode.fromStoredValue("ALWAYS"))
        assertEquals(WslExecutionMode.NEVER, WslExecutionMode.fromStoredValue("NEVER"))
    }

    @Test
    fun fromStoredValue_blankOrUnrecognised_fallsBackToAuto() { // A hand-edited or downgraded XML settings file must degrade to the
        // default rather than throwing.
        assertEquals(WslExecutionMode.AUTO, WslExecutionMode.fromStoredValue(""))
        assertEquals(WslExecutionMode.AUTO, WslExecutionMode.fromStoredValue("not-a-real-mode"))
    }

    // ---- detectWslDistroFromPath ----

    @Test
    fun detectWslDistroFromPath_wslDollarUncPath_returnsDistroId() {
        assertEquals("Ubuntu-22.04", detectWslDistroFromPath("""\\wsl$\Ubuntu-22.04\home\user\project"""))
    }

    @Test
    fun detectWslDistroFromPath_wslLocalhostUncPath_returnsDistroId() {
        assertEquals("Ubuntu-22.04", detectWslDistroFromPath("""\\wsl.localhost\Ubuntu-22.04\home\user\project"""))
    }

    @Test
    fun detectWslDistroFromPath_ordinaryWindowsPath_returnsNull() {
        assertNull(detectWslDistroFromPath("""C:\Users\me\project"""))
    }

    @Test
    fun detectWslDistroFromPath_null_returnsNull() {
        assertNull(detectWslDistroFromPath(null))
    }

    // ---- wslAwareFilePath / windowsPathFromWsl (null-distribution fallback) ----
    //
    // The `distribution != null` branches need a real WSLDistribution, which isn't fixture-free
    // constructible — same rationale TinymistCommandsTest gives for leaving the LSP send-side to
    // manual verification. The fallback branches below are the pure, testable part.

    @Test
    fun wslAwareFilePath_noDistribution_returnsNull() {
        assertNull(wslAwareFilePath("""\\wsl.localhost\Ubuntu\home\user\project.typ""", null))
    }

    @Test
    fun windowsPathFromWsl_noDistribution_returnsPathUnchanged() {
        assertEquals("/home/user/project.typ", windowsPathFromWsl("/home/user/project.typ", null))
    }
}
