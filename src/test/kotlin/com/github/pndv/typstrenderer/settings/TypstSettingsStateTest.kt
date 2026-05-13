package com.github.pndv.typstrenderer.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.reflect.full.memberProperties

/**
 * Regression tests for [TypstSettingsState] persistence behaviour.
 *
 * **Background.** Versions 0.1.0 and 0.1.1 shipped with
 * `override fun loadState(state: State) { this.state = state }` — i.e. reassigning
 * the field reference rather than mutating the existing instance. IntelliJ's
 * XML-serialization machinery tracks the field's identity; reassigning it caused
 * the framework to lose the binding to the live object, and user-edited settings
 * appeared to revert after a settings reload.
 *
 * Fixed in 0.1.2 (PR #17) by switching to
 * `XmlSerializerUtil.copyBean(state, this.state)`, which mutates the existing
 * instance in place.
 *
 * These tests lock that fix in. If anyone refactors `loadState` back to
 * `this.state = state`, [loadStateMutatesInPlace] fails — directly. The
 * regression class shipped twice already (0.1.0 → 0.1.1 undetected); these
 * tests are insurance against round three.
 *
 * Instantiates the service via its no-arg constructor (rather than through
 * `service()`) so the suite stays IDE-fixture-free — same pattern as
 * [com.github.pndv.typstrenderer.lsp.DownloadUrlResolutionTest].
 */
class TypstSettingsStateTest {

    private fun newService(): TypstSettingsState =
        TypstSettingsState::class.java.getDeclaredConstructor().newInstance()

    @Test
    fun roundTripViaAccessors() {
        val service = newService()
        service.tinymistPath = "X"
        service.typstPath = "Y"
        service.autoCompileOnSave = true
        service.rememberPreviewScrollAcrossRestart = true

        // Each accessor reads back what was written.
        assertEquals("X", service.tinymistPath)
        assertEquals("Y", service.typstPath)
        assertTrue(service.autoCompileOnSave)
        assertTrue(service.rememberPreviewScrollAcrossRestart)

        // Same values surface via getState() — the canonical serialization path.
        val state = service.getState()
        assertEquals("X", state.tinymistPath)
        assertEquals("Y", state.typstPath)
        assertTrue(state.autoCompileOnSave)
        assertTrue(state.rememberPreviewScrollAcrossRestart)
    }

    @Test
    fun loadStateMutatesInPlace() {
        val service = newService()
        // Capture the State instance identity before loadState.
        val originalStateInstance = service.getState()

        // Load fresh state with different values.
        val fresh = TypstSettingsState.State(
            tinymistPath = "new-tinymist",
            typstPath = "new-typst",
            autoCompileOnSave = true,
            rememberPreviewScrollAcrossRestart = true,
        )
        service.loadState(fresh)

        // Critical assertion: the service's State field is the SAME instance as before
        // (not reassigned to the `fresh` reference). This is the property `copyBean`
        // provides that `this.state = state` does not — and the property IntelliJ's
        // serialization machinery depends on for change tracking.
        assertSame(
            "loadState must mutate the existing State instance in place; reassigning the " +
                "field reference breaks IntelliJ's serialization tracking and silently loses " +
                "subsequent user edits (regression seen in 0.1.0/0.1.1, fixed in 0.1.2 / PR #17).",
            originalStateInstance,
            service.getState(),
        )
        // And the loaded values are now reflected.
        assertEquals("new-tinymist", service.tinymistPath)
        assertEquals("new-typst", service.typstPath)
        assertTrue(service.autoCompileOnSave)
        assertTrue(service.rememberPreviewScrollAcrossRestart)
    }

    @Test
    fun everyStateFieldHasMatchingServiceAccessor() {
        // Schema-drift guard: every property on the State data class should have
        // a corresponding service-level var with the same name and matching type.
        // Catches the case where a new field is added to State but the developer
        // forgets to add the accessor — resulting in the field being persisted
        // but invisible (and unwritable) through the service API.
        val stateProps = TypstSettingsState.State::class.memberProperties.associateBy { it.name }
        val serviceProps = TypstSettingsState::class.memberProperties.associateBy { it.name }

        for ((name, stateProp) in stateProps) {
            val serviceProp = serviceProps[name]
            assertNotNull(
                "TypstSettingsState should expose '$name' as an accessor (matching " +
                    "TypstSettingsState.State.$name). Missing accessor means the field can " +
                    "be persisted via the State data class but cannot be read or written " +
                    "through TypstSettingsState.getInstance().",
                serviceProp,
            )
            assertEquals(
                "TypstSettingsState.$name should have the same type as State.$name",
                stateProp.returnType.classifier,
                serviceProp!!.returnType.classifier,
            )
        }
    }
}
