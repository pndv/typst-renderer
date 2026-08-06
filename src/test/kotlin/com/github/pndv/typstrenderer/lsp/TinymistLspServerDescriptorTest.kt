package com.github.pndv.typstrenderer.lsp

import com.github.pndv.typstrenderer.settings.TypstProjectSettingsState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-function tests for [buildOutputPathTemplate].
 *
 * The template is the one piece of [TinymistLspServerDescriptor]'s
 * `initializationOptions` that carries real logic; it is split out as a
 * top-level `internal` function so it can be exercised without standing up an
 * LSP server or a Project.
 */
class TinymistLspServerDescriptorTest {

    @Test
    fun `default export directory mirrors the source tree under target`() {
        assertEquals(
            $$"$root/target/$dir/$name",
            buildOutputPathTemplate(TypstProjectSettingsState.DEFAULT_EXPORT_PATH),
        )
    }

    @Test
    fun `custom export directory is substituted verbatim`() {
        assertEquals($$"$root/dist/$dir/$name", buildOutputPathTemplate("dist"))
    }

    @Test
    fun `a blank export directory falls back to the default`() { // A user who clears the field must not get `$root//$dir/$name`.
        assertEquals($$"$root/target/$dir/$name", buildOutputPathTemplate(""))
        assertEquals($$"$root/target/$dir/$name", buildOutputPathTemplate("   "))
    }

    @Test
    fun `surrounding whitespace and path separators are trimmed`() { // Guards against `$root//out//$dir/$name` from stray slashes the user types.
        assertEquals($$"$root/out/$dir/$name", buildOutputPathTemplate("  /out/  "))
        assertEquals($$"$root/out/$dir/$name", buildOutputPathTemplate("\\out\\"))
    }

    @Test
    fun `a nested export directory is preserved`() {
        assertEquals($$"$root/build/pdf/$dir/$name", buildOutputPathTemplate("build/pdf"))
    }
}
