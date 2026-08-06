package com.github.pndv.typstrenderer.editor

import com.github.pndv.typstrenderer.lsp.resolveTypstExportTarget
import org.junit.AfterClass
import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for the rule that decides when two preview panes share one tinymist preview task.
 *
 * [TypstLivePreviewRegistry] keys its tasks on the *resolved compile target* rather than on the
 * focused file, which is what makes the sharing rule fall out with no "is a main pinned?" branch
 * anywhere in the registry. Because the key is the whole rule, testing the key is testing the
 * rule — and the pure resolver underneath needs no IDE fixture.
 *
 * The intended behaviour, stated once:
 *  - a main file is pinned  -> in-project panes share one preview of the whole document;
 *  - no main is pinned      -> every pane previews its own file, independently;
 *  - a file outside the project is never shared, pinned or not.
 *
 * The files are real, built in a temp directory in `@BeforeClass` and torn down in `@AfterClass`,
 * following [com.github.pndv.typstrenderer.lsp.TypstMainFileResolverTest]. They have to exist:
 * `resolveTypstMainFile` validates the configured pin with `File.isFile`, so a path that is merely
 * plausible resolves to "no pin configured" and every sharing assertion below silently inverts.
 * An earlier version of this test used hard-coded paths from a real project on one machine, which
 * passed there and would have failed anywhere else — including CI.
 */
class TypstLivePreviewSharingTest {

    companion object {
        private var projectDir: File? = null
        private var elsewhereDir: File? = null

        /** The pinned main entry, as the settings store it — a path string. */
        private lateinit var main: String
        private lateinit var chapterA: Path
        private lateinit var chapterB: Path
        private lateinit var outside: Path

        @JvmStatic
        @BeforeClass
        fun setUpClass() {
            projectDir = Files.createTempDirectory("typst-live-preview-sharing").toFile()
            main =
                File(
                    projectDir,
                    "main.typ"
                ).apply { writeText("= Document\n#include \"chapters/word-form-first.typ\"\n") }.absolutePath

            val chapters = File(projectDir, "chapters").apply { mkdirs() }
            chapterA = File(chapters, "word-form-first.typ").apply { writeText("== First\n") }.toPath()
            chapterB = File(chapters, "word-form-second.typ").apply { writeText("== Second\n") }.toPath()

            // A second root, not under the project's: a file the project main could not include
            // even if it wanted to.
            elsewhereDir = Files.createTempDirectory("typst-live-preview-elsewhere").toFile()
            outside = File(elsewhereDir, "resume.typ").apply { writeText("= CV\n") }.toPath()
        }

        @JvmStatic
        @AfterClass
        fun tearDownClass() {
            projectDir?.deleteRecursively()
            elsewhereDir?.deleteRecursively()
        }
    }

    /** The registry's key for a pane previewing [focused]. */
    private fun key(configuredMain: String, focused: Path, inProject: Boolean = true): Path =
        resolveTypstExportTarget(configuredMain, focused, inProject)

    @Test
    fun `with a main pinned, two chapter panes share one preview`() { // The exact case that spawned 24 preview servers for one document: every open chapter
        // resolves to the pinned main, so one key, one task, one compile per keystroke.
        assertEquals(key(main, chapterA), key(main, chapterB))
        assertEquals(Path.of(main), key(main, chapterA))
    }

    @Test
    fun `with a main pinned, the main's own pane shares that same preview`() {
        assertEquals(key(main, Path.of(main)), key(main, chapterA))
    }

    @Test
    fun `with no main pinned, every pane gets its own preview`() {
        val unpinned = ""
        assertNotEquals(key(unpinned, chapterA), key(unpinned, chapterB))
        assertEquals(chapterA, key(unpinned, chapterA))
        assertEquals(chapterB, key(unpinned, chapterB))
    }

    @Test
    fun `a file outside the project is never shared, even with a main pinned`() { // It cannot be #include-d by the project main (it sits outside $root), so sharing would
        // render the wrong document in its pane.
        assertEquals(outside, key(main, outside, inProject = false))
        assertNotEquals(key(main, chapterA), key(main, outside, inProject = false))
    }

    @Test
    fun `task ids are stable per target and distinct across targets`() { // Stability is what lets a start reclaim a task orphaned by a crash or a missed release:
        // it kills the previous task under the same id before starting. A random id per start
        // would leave the orphan running and holding its port for the rest of the session.
        assertEquals(
            TypstLivePreviewRegistry.taskIdFor(chapterA),
            TypstLivePreviewRegistry.taskIdFor(chapterA),
        )
        assertNotEquals(
            TypstLivePreviewRegistry.taskIdFor(chapterA),
            TypstLivePreviewRegistry.taskIdFor(chapterB),
        )
        assertTrue(TypstLivePreviewRegistry.taskIdFor(chapterA).startsWith("typst-renderer-"))
    }

    @Test
    fun `panes sharing a target share a task id`() { // The registry never derives an id from the focused file, so two panes that resolve to
        // the same main cannot end up on two tasks.
        assertEquals(
            TypstLivePreviewRegistry.taskIdFor(key(main, chapterA)),
            TypstLivePreviewRegistry.taskIdFor(key(main, chapterB)),
        )
    }
}
