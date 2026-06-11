package com.github.pndv.typstrenderer.toolWindow

import com.github.pndv.typstrenderer.lsp.TinymistCommands
import com.intellij.openapi.util.SystemInfo
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tests for the pure-function core of [TypstConsoleFilter] — the regex
 * extraction in [analyseLine] and the path-resolution helper
 * [computeAbsolutePath]. The IntelliJ-bound parts (Project, VirtualFile,
 * Filter.Result wiring) are thin wrappers tested by manual smoke run,
 * not here; the regex and path rules are where the actual behaviour lives.
 *
 * Same fixture-free pattern as [com.github.pndv.typstrenderer.lsp.TypstRootResolverTest]
 * and [com.github.pndv.typstrenderer.lsp.BinaryResolutionTest]: real temp
 * directories rather than mocks, so the production code paths run verbatim.
 */
class TypstConsoleFilterTest {

    private var workDir: File? = null

    @Before
    fun setUp() {
        workDir = Files.createTempDirectory("typst-console-filter-test").toFile()
    }

    @After
    fun tearDown() {
        workDir?.deleteRecursively()
    }

    // ----- Rich-format anchor: regex / format coverage -----

    @Test
    fun richAnchor_simpleRelativePath_extractsPathLineColumn() {
        val match = analyseLine("  ┌─ chapters/intro.typ:12:3")

        assertNotNull("Anchor at start of line should match", match)
        assertEquals("chapters/intro.typ", match!!.path)
        assertEquals(12, match.lineNumber)
        assertEquals(3, match.column)
    }

    @Test
    fun richAnchor_highlightSpansPathLineColOnly_notTheBoxDrawingPrefix() {
        val line = "  ┌─ chapters/intro.typ:12:3"
        val match = analyseLine(line)!!

        // The highlight should cover "chapters/intro.typ:12:3", not the
        // leading "  ┌─ " — the user clicks the path, the prefix stays plain.
        val highlighted = line.substring(match.highlightStartInLine, match.highlightEndInLine)
        assertEquals("chapters/intro.typ:12:3", highlighted)
    }

    @Test
    fun richAnchor_pathWithSpaces_isMatched() {
        val match = analyseLine("  ┌─ my folder/intro.typ:12:3")!!

        assertEquals("my folder/intro.typ", match.path)
        assertEquals(12, match.lineNumber)
        assertEquals(3, match.column)
    }

    @Test
    fun richAnchor_windowsBackslashPath_isMatched() {
        val match = analyseLine("  ┌─ chapters\\intro.typ:12:3")!!

        assertEquals("chapters\\intro.typ", match.path)
        assertEquals(12, match.lineNumber)
        assertEquals(3, match.column)
    }

    @Test
    fun richAnchor_windowsAbsolutePathWithDriveLetter_isMatched() {
        // Drive letter introduces an extra colon — non-greedy regex
        // backtracking must skip past it and only treat the trailing
        // `:line:col` as the line/column suffix.
        val match = analyseLine("  ┌─ C:\\Users\\me\\intro.typ:12:3")!!

        assertEquals("C:\\Users\\me\\intro.typ", match.path)
        assertEquals(12, match.lineNumber)
        assertEquals(3, match.column)
    }

    @Test
    fun richAnchor_posixAbsolutePath_isMatched() {
        // POSIX absolute paths carry no drive-letter colon, so the regex's
        // non-greedy capture has fewer trailing colons to backtrack past.
        // Pure regex behaviour — no platform conditioning needed; the
        // pattern is a string match, and the path shape is just a fixture.
        val match = analyseLine("  ┌─ /home/me/projects/intro.typ:12:3")!!

        assertEquals("/home/me/projects/intro.typ", match.path)
        assertEquals(12, match.lineNumber)
        assertEquals(3, match.column)
    }

    @Test
    fun richAnchor_multiDigitLineAndColumn_isMatched() {
        val match = analyseLine("  ┌─ chapters/intro.typ:1234:567")!!

        assertEquals(1234, match.lineNumber)
        assertEquals(567, match.column)
    }

    @Test
    fun richAnchor_lineWithTrailingNewline_extractsCorrectly() {
        // applyFilter may receive the line with the trailing newline still attached;
        // the regex must still work.
        val match = analyseLine("  ┌─ chapters/intro.typ:12:3\n")!!

        assertEquals("chapters/intro.typ", match.path)
        assertEquals(12, match.lineNumber)
        assertEquals(3, match.column)
    }

    @Test
    fun richAnchor_multipleAnchorsInSameDiagnostic_allLink() {
        // Typst's rich format can emit multiple `┌─` anchors per diagnostic —
        // primary span plus secondary `note:` spans pointing at related code.
        // Without lookback state, every well-formed anchor matches on its own
        // merits, so secondary spans are hyperlinked too.
        val primary = analyseLine("  ┌─ chapters/intro.typ:42:5")
        val secondary = analyseLine("  ┌─ chapters/utils.typ:17:5")

        assertNotNull("Primary anchor must match", primary)
        assertNotNull("Secondary anchor must match", secondary)
        assertEquals("chapters/utils.typ", secondary!!.path)
    }

    // ----- Compact format -----

    @Test
    fun compactFormat_basicErrorLine_producesHyperlink() {
        val match = analyseLine("chapters/intro.typ:12:3: error: undefined")!!

        assertEquals("chapters/intro.typ", match.path)
        assertEquals(12, match.lineNumber)
        assertEquals(3, match.column)
    }

    @Test
    fun compactFormat_warningLine_producesHyperlink() {
        val match = analyseLine("chapters/intro.typ:5:1: warning: unused parameter")
        assertNotNull(match)
    }

    @Test
    fun compactFormat_highlightSpansPathLineColOnly_notTheTrailingErrorToken() {
        val line = "chapters/intro.typ:12:3: error: undefined"
        val match = analyseLine(line)!!

        val highlighted = line.substring(match.highlightStartInLine, match.highlightEndInLine)
        assertEquals("chapters/intro.typ:12:3", highlighted)
    }

    @Test
    fun compactFormat_windowsBackslashPath_isMatched() {
        val match = analyseLine("chapters\\intro.typ:12:3: error: undefined")!!
        assertEquals("chapters\\intro.typ", match.path)
    }

    @Test
    fun compactFormat_posixAbsolutePath_isMatched() {
        // Typst's compact diagnostics can emit absolute paths after
        // canonicalisation; the leading slash and intermediate '/'
        // separators must not derail the path / line / column split.
        val match = analyseLine("/home/me/projects/intro.typ:12:3: error: undefined")!!

        assertEquals("/home/me/projects/intro.typ", match.path)
        assertEquals(12, match.lineNumber)
        assertEquals(3, match.column)
    }

    // ----- False-positive guard -----

    @Test
    fun codeExcerptLineContainingTypReference_doesNotMatch() {
        // Combined regex shape (rich anchor requires `┌─`, compact requires
        // the trailing `error|warning:` token) makes this impossible. Lock it
        // in with an explicit assertion.
        val match = analyseLine("12 │   #import \"chapters/intro.typ\"")
        assertNull("Code-excerpt line that *mentions* a .typ path must not be hyperlinked", match)
    }

    @Test
    fun proseMentioningTypPath_doesNotMatch() {
        // A stray prose line like "see foo.typ:12:3 for details" should
        // never become a hyperlink — no `┌─`, no `: error:` suffix.
        val match = analyseLine("see foo.typ:12:3 for details")
        assertNull(match)
    }

    @Test
    fun richAnchor_midLineInProse_doesNotMatch() {
        // The rich-anchor regex requires `┌─` at the start of the line
        // (allowing leading whitespace). A literal `┌─ path:line:col`
        // embedded mid-prose — e.g. inside a `= help:` follow-up that
        // quotes an example — must not be hyperlinked.
        val match = analyseLine("  help: see ┌─ example.typ:1:1 for syntax")
        assertNull("Mid-line `┌─` in prose must not be hyperlinked", match)
    }

    @Test
    fun emptyLine_producesNoMatch() {
        val match = analyseLine("")
        assertNull(match)
    }

    // ----- Regression: a real tinymist `exportPdf` error must end up linkable -----
    //
    // These guard the formatter↔filter seam end-to-end. tinymist returns a failed
    // export as a single string with the typst diagnostic Rust-Debug-escaped inside
    // quotes. TinymistCommands.formatExportError lifts and unescapes it; the result
    // must split into lines whose `┌─` anchor TypstConsoleFilter can hyperlink. If
    // either the unescaping or the regex regresses, the link silently disappears —
    // exactly the bug these lock down.

    @Test
    fun tinymistExportError_afterFormatting_linksTheAnchorLineToItsSourceFile() { // The payload as tinymist Debug-escapes it: literal \n line breaks, doubled
        // \\ path separators, \u{..} for the Devanagari source excerpt. Built from raw
        // segments (literal backslashes) and wrapped in real quotes so the formatter's
        // quote-extraction has a pair to find.
        val debugPayload =
            """error: label `<ch:cases>` occurs multiple times in the document\n""" + """  ┌─ d:\\Projects\\ru-hi\\chapters\\intro\\spelling-rules.typ:6:59\n""" + """  │\n6 │ स\u{94d}मरित\n  │     ^^^^^^^^^\n\n"""
        val rawExportError =
            """crates\tinymist\src\task\export.rs:579:17: ExportTask(0): document is not available for export: """ + "\"" + debugPayload + "\""

        val formatted = TinymistCommands.formatExportError(rawExportError)
        val lines = formatted.lines()

        // Exactly one line is a diagnostic anchor — the ┌─ location. The leading
        // `error:` line and the `6 │ …` source excerpt must not hyperlink.
        val matches = lines.mapNotNull { analyseLine(it) }
        assertEquals("Only the ┌─ anchor line should hyperlink", 1, matches.size)

        val match = matches.single()
        assertEquals("""d:\Projects\ru-hi\chapters\intro\spelling-rules.typ""", match.path)
        assertEquals(6, match.lineNumber)
        assertEquals(59, match.column)
    }

    @Test
    fun tinymistExportError_devanagariExcerpt_decodesToGlyphsNotEscapeSequences() { // The \u{94d} (Devanagari virama) in the excerpt must render as the real
        // combining mark, not leak through as a literal "\u{94d}" in the console.
        val raw = """x: """ + "\"" + """line one\n6 │ स\u{94d}मरित""" + "\""
        val formatted = TinymistCommands.formatExportError(raw)

        assertTrue("unicode escape must decode to the glyph: $formatted", formatted.contains("स्मरित"))
        assertTrue("no literal \\u{ escape may survive: $formatted", !formatted.contains("""\u{"""))
    }

    // ----- computeAbsolutePath: path resolution rules -----

    @Test
    fun computeAbsolutePath_relativePath_joinedAgainstProjectRoot() {
        val root = realDir("project-root").absolutePath
        val resolved: Path? = computeAbsolutePath("chapters/intro.typ", root)

        assertNotNull(resolved)
        // Path.startsWith / endsWith are component-based, not string-based,
        // so they work uniformly across host OSes without manual slash
        // normalisation.
        assertTrue(
            "Resolved path should start with the project root",
            resolved!!.startsWith(Path.of(root))
        )
        assertTrue(
            "Resolved path should end with the relative segment",
            resolved.endsWith(Path.of("chapters/intro.typ"))
        )
    }

    @Test
    fun computeAbsolutePath_absolutePath_returnedAsIs() {
        val absoluteFile = File(workDir, "intro.typ").apply { writeText("") }
        val resolved = computeAbsolutePath(absoluteFile.absolutePath, projectRoot = null)

        // Absolute paths bypass the project-root requirement entirely.
        // The function returns a Path in NIO's platform-native shape — the
        // VFS performs its own translation when findFileByNioFile is called.
        assertEquals(absoluteFile.toPath(), resolved)
    }

    @Test
    fun computeAbsolutePath_relativePathWithNullRoot_returnsNull() {
        // No way to resolve a relative path without a root — should fall
        // through to no-hyperlink rather than guessing.
        val resolved = computeAbsolutePath("chapters/intro.typ", projectRoot = null)
        assertNull(resolved)
    }

    @Test
    fun computeAbsolutePath_windowsBackslashPath_acceptedOnWindows() {
        // On Windows, Path.of treats '/' and '\' as interchangeable
        // separators — both inputs parse to the same Path. Slash
        // normalisation for VFS consumption is now the responsibility of
        // findFileByNioFile, not of this helper.
        //
        // On POSIX, '\' is a legal filename character, so passing a
        // backslash-laden path produces a single-segment Path with literal
        // backslashes — not equivalent to the forward-slash form. The
        // assertion only makes sense on Windows.
        assumeTrue(SystemInfo.isWindows)

        val absoluteFile = File(workDir, "intro.typ").apply { writeText("") }
        val winLookingPath = absoluteFile.absolutePath.replace('/', '\\')

        val resolved = computeAbsolutePath(winLookingPath, projectRoot = null)
        assertEquals(absoluteFile.toPath(), resolved)
    }

    @Test
    fun computeAbsolutePath_posixAbsolutePath_acceptedOnPosix() {
        // Mirror of _windowsBackslashPath_acceptedOnWindows for POSIX:
        // a path that starts at root '/' is recognised as absolute and
        // returned as-is. On Windows, Path.of("/tmp/foo") is *not*
        // absolute because Windows roots require a drive letter — hence
        // the platform gate.
        assumeTrue(!SystemInfo.isWindows)

        val absoluteFile = File(workDir, "intro.typ").apply { writeText("") }
        val resolved = computeAbsolutePath(absoluteFile.absolutePath, projectRoot = null)

        assertEquals(absoluteFile.toPath(), resolved)
    }

    @Test
    fun computeAbsolutePath_posixAbsolutePathWithSpaces_acceptedOnPosix() {
        // Spaces inside POSIX paths must round-trip through the helper
        // intact — no over-eager escaping or splitting on whitespace.
        assumeTrue(!SystemInfo.isWindows)

        val folder = File(workDir, "my folder").apply { mkdirs() }
        val absoluteFile = File(folder, "intro.typ").apply { writeText("") }
        val resolved = computeAbsolutePath(absoluteFile.absolutePath, projectRoot = null)

        assertEquals(absoluteFile.toPath(), resolved)
        assertTrue("The resolved file should actually exist", resolved!!.toFile().exists())
    }

    @Test
    fun computeAbsolutePath_posixAbsolutePath_bypassesProjectRoot() {
        // Absolute paths must short-circuit past the root-join branch
        // even when a root is supplied — otherwise an absolute typst
        // diagnostic path would be wrongly re-rooted under the project,
        // producing nonsense like '/proj/home/me/intro.typ'.
        assumeTrue(!SystemInfo.isWindows)

        val unrelatedRoot = realDir("unrelated-root").absolutePath
        val absoluteFile = File(workDir, "intro.typ").apply { writeText("") }
        val resolved = computeAbsolutePath(absoluteFile.absolutePath, projectRoot = unrelatedRoot)

        assertEquals(absoluteFile.toPath(), resolved)
    }

    @Test
    fun computeAbsolutePath_posixPathWithLiteralBackslash_treatedAsFilenameChar() {
        // POSIX-specific filename semantics: '\' is a legal character,
        // not a path separator. A raw input like 'chapters\intro.typ' on
        // POSIX is a *single* filename, not a two-segment relative path,
        // so it joins under the root as one segment containing a literal
        // backslash.
        assumeTrue(!SystemInfo.isWindows)

        val root = realDir("project-root").absolutePath
        val resolved = computeAbsolutePath("""chapters\intro.typ""", root)

        assertNotNull(resolved)
        assertTrue(
            "The whole string, including the backslash, should be one path segment",
            resolved!!.endsWith(Path.of("""chapters\intro.typ"""))
        )
    }


    @Test
    fun computeAbsolutePath_relativePath_locatesRealFileWhenItExists() {
        val root = realDir("project-root")
        val realFile = File(root, "chapters/intro.typ").apply {
            parentFile.mkdirs()
            writeText("typst content")
        }

        val resolved = computeAbsolutePath("chapters/intro.typ", root.absolutePath)
        assertNotNull(resolved)
        // The resolved path must match the actual file we just created —
        // proving the join logic agrees with the filesystem.
        assertEquals(realFile.toPath().toAbsolutePath().normalize(), resolved)
        assertTrue("The resolved file should actually exist on disk", resolved!!.toFile().exists())
    }

    @Test
    fun computeAbsolutePath_relativePath_returnsPathEvenWhenFileMissing() {
        // computeAbsolutePath is purely about path arithmetic. The
        // file-existence gate is enforced in TypstConsoleFilter.applyFilter
        // (via VirtualFile.exists()), not here. This test pins that
        // contract so a future refactor doesn't accidentally fold the
        // file-existence check into the pure helper.
        val root = realDir("project-root").absolutePath
        val resolved = computeAbsolutePath("nope/missing.typ", root)
        assertNotNull(resolved)
        assertTrue("Path should be returned even though file does not exist", !resolved!!.toFile().exists())
    }

    private fun realDir(name: String): File =
        File(workDir, name).apply { mkdirs() }
}
