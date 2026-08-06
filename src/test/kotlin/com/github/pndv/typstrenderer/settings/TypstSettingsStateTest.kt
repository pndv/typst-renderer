package com.github.pndv.typstrenderer.settings

import com.github.pndv.typstrenderer.editor.TypstPreviewMode
import org.junit.Assert.*
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
        service.rememberPreviewScrollAcrossRestart = true

        // Each accessor reads back what was written.
        assertEquals("X", service.tinymistPath)
        assertTrue(service.rememberPreviewScrollAcrossRestart)

        // Same values surface via getState() — the canonical serialization path.
        val state = service.getState()
        assertEquals("X", state.tinymistPath)
        assertTrue(state.rememberPreviewScrollAcrossRestart)
    }

    @Test
    fun previewModeRoundTripsAsItsPersistedId() {
        val service = newService() // Live is the shipped default: a fresh install gets type-and-see without opting in.
        assertEquals(TypstPreviewMode.LIVE, service.defaultPreviewMode)
        assertTrue(service.livePreviewOnType)

        service.defaultPreviewMode = TypstPreviewMode.PDF
        service.livePreviewOnType = false

        assertEquals(TypstPreviewMode.PDF, service.defaultPreviewMode)
        assertFalse(service.livePreviewOnType) // The enum is persisted as its id, not as the enum constant.
        assertEquals("pdf", service.getState().defaultPreviewMode)
        assertFalse(service.getState().livePreviewOnType)
    }

    @Test
    fun anUnreadablePersistedPreviewModeFallsBackToTheDefault() {
        val service = newService()
        service.loadState(TypstSettingsState.State(defaultPreviewMode = "no-such-renderer"))
        assertEquals(TypstPreviewMode.LIVE, service.defaultPreviewMode)
    }

    @Test
    fun loadStateMutatesInPlace() {
        val service = newService()
        // Capture the State instance identity before loadState.
        val originalStateInstance = service.getState()

        // Load fresh state with different values.
        val fresh = TypstSettingsState.State(
            tinymistPath = "new-tinymist",
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
            if (name in TYPE_ADAPTING_ACCESSORS) continue
            assertEquals(
                "TypstSettingsState.$name should have the same type as State.$name",
                stateProp.returnType.classifier,
                serviceProp!!.returnType.classifier,
            )
        }
    }

    private companion object {
        /**
         * Fields whose service accessor deliberately exposes a richer type than the persisted
         * one, listed here so the guard above still catches an accidental type mismatch.
         *
         * `defaultPreviewMode` persists as the enum's id rather than the enum: an id that no
         * longer exists (downgrade, hand-edited XML) then degrades to the default instead of
         * failing to deserialise the whole settings object.
         */
        val TYPE_ADAPTING_ACCESSORS = setOf("defaultPreviewMode")
    }
}
