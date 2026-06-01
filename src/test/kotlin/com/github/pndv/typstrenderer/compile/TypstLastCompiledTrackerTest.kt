package com.github.pndv.typstrenderer.compile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests for [TypstLastCompiledTracker].
 *
 * The tracker is an in-memory project-scoped service with a get / set surface
 * the size of a postage stamp; testing it directly via the no-arg constructor
 * (rather than going through `project.service<TypstLastCompiledTracker>()`)
 * keeps the suite fixture-free — same pattern as
 * [com.github.pndv.typstrenderer.settings.TypstSettingsStateTest].
 *
 * These tests guard the contract powering the toolbar actions:
 * Recompile / Toggle Watch on the Typst Output tool window read from this
 * tracker as their primary target source. A regression where `getLast()`
 * returns a stale or wrong path would surface as the wrong file getting
 * compiled when the user clicks Recompile.
 */
class TypstLastCompiledTrackerTest {

    private fun newTracker(): TypstLastCompiledTracker =
        TypstLastCompiledTracker::class.java.getDeclaredConstructor().newInstance()

    @Test
    fun initialStateIsNull() {
        assertNull(
            "A fresh tracker should hold null so the toolbar actions fall through " +
                "to the active-editor fallback rather than pointing at a phantom path.",
            newTracker().getLast(),
        )
    }

    @Test
    fun recordThenGetReturnsPath() {
        val tracker = newTracker()
        tracker.record("/tmp/foo.typ")
        assertEquals("/tmp/foo.typ", tracker.getLast())
    }

    @Test
    fun secondRecordOverwritesFirst() {
        val tracker = newTracker()
        tracker.record("/tmp/foo.typ")
        tracker.record("/tmp/bar.typ")
        assertEquals(
            "record() must overwrite the previously stored path — Recompile is " +
                "meant to retry the most recent compile, not the first one.",
            "/tmp/bar.typ",
            tracker.getLast(),
        )
    }

    @Test
    fun recordEmptyStringIsTreatedAsAPath() {
        // The tracker is a dumb pass-through by design; it does not validate paths.
        // Validation (file exists, is a .typ, etc.) is the caller's job — the
        // compile service is the canonical place for that, not the tracker.
        // Pinning the no-validation contract here guards against well-meaning
        // future refactors that try to "be helpful" by filtering inputs.
        val tracker = newTracker()
        tracker.record("")
        assertEquals("", tracker.getLast())
    }
}
