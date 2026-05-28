package com.github.pndv.typstrenderer.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.reflect.full.memberProperties

/**
 * Regression tests for [TypstProjectSettingsState] persistence behaviour,
 * mirroring [TypstSettingsStateTest] for the project-scoped service.
 *
 * The same `loadState` in-place mutation contract applies here: reassigning
 * the field would break IntelliJ's serialization tracking. See
 * [TypstSettingsStateTest] for the 0.1.0/0.1.1 → 0.1.2 history that motivated
 * the fix — the project-scoped state uses the same `copyBean` pattern and
 * would break the same way under the same regression.
 *
 * As of the font-path work the State carries both `typstProjectRoot` and
 * `typstFontPath`; the schema-drift guard ([everyStateFieldHasMatchingServiceAccessor])
 * catches future additions that forget to expose a matching service-level
 * accessor.
 *
 * Instantiates the service via its no-arg constructor (rather than through
 * `service()`) so the suite stays IDE-fixture-free — same pattern as
 * [TypstSettingsStateTest].
 */
class TypstProjectSettingsStateTest {

    private fun newService(): TypstProjectSettingsState =
        TypstProjectSettingsState::class.java.getDeclaredConstructor().newInstance()

    @Test
    fun roundTripViaAccessors() {
        val service = newService()
        service.typstProjectRoot = "/some/root"
        service.typstFontPath = "/some/fonts"

        // Each accessor reads back what was written.
        assertEquals("/some/root", service.typstProjectRoot)
        assertEquals("/some/fonts", service.typstFontPath)

        // Same values surface via getState() — the canonical serialization path.
        val state = service.getState()
        assertEquals("/some/root", state.typstProjectRoot)
        assertEquals("/some/fonts", state.typstFontPath)
    }

    @Test
    fun loadStateMutatesInPlace() {
        val service = newService()
        // Capture the State instance identity before loadState.
        val originalStateInstance = service.getState()

        // Load fresh state with different values.
        val fresh = TypstProjectSettingsState.State(
            typstProjectRoot = "new-root",
            typstFontPath = "new-fonts",
        )
        service.loadState(fresh)

        // Critical assertion: the service's State field is the SAME instance as before
        // (not reassigned to the `fresh` reference). This is the property `copyBean`
        // provides that `this.state = state` does not — and the property IntelliJ's
        // serialization machinery depends on for change tracking. The same regression
        // shipped twice in TypstSettingsState (0.1.0/0.1.1) before the 0.1.2 fix; this
        // assertion is the equivalent insurance for the project-scoped settings.
        assertSame(
            "loadState must mutate the existing State instance in place; reassigning the " +
                "field reference breaks IntelliJ's serialization tracking and silently loses " +
                "subsequent user edits.",
            originalStateInstance,
            service.getState(),
        )
        // And the loaded values are now reflected.
        assertEquals("new-root", service.typstProjectRoot)
        assertEquals("new-fonts", service.typstFontPath)
    }

    @Test
    fun everyStateFieldHasMatchingServiceAccessor() {
        // Schema-drift guard: every property on the State data class should have
        // a corresponding service-level var with the same name and matching type.
        // Catches the case where a new field is added to State but the developer
        // forgets to add the accessor — resulting in the field being persisted
        // but invisible (and unwritable) through the service API.
        val stateProps = TypstProjectSettingsState.State::class.memberProperties.associateBy { it.name }
        val serviceProps = TypstProjectSettingsState::class.memberProperties.associateBy { it.name }

        for ((name, stateProp) in stateProps) {
            val serviceProp = serviceProps[name]
            assertNotNull(
                "TypstProjectSettingsState should expose '$name' as an accessor (matching " +
                    "TypstProjectSettingsState.State.$name). Missing accessor means the field " +
                    "can be persisted via the State data class but cannot be read or written " +
                    "through TypstProjectSettingsState.getInstance().",
                serviceProp,
            )
            assertEquals(
                "TypstProjectSettingsState.$name should have the same type as State.$name",
                stateProp.returnType.classifier,
                serviceProp!!.returnType.classifier,
            )
        }
    }
}
