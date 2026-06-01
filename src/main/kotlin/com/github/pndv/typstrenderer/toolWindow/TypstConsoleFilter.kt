package com.github.pndv.typstrenderer.toolWindow

import com.github.pndv.typstrenderer.lsp.resolveTypstRoot
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.execution.filters.Filter
import com.intellij.execution.filters.OpenFileHyperlinkInfo
import com.intellij.openapi.diagnostic.trace
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.nio.file.Path

/**
 * Console filter that hyperlinks typst diagnostic locations in the Typst
 * Output tool window.
 *
 * Recognises two diagnostic formats emitted by the typst CLI:
 *
 *   1. **Rich** (the typst default — `--diagnostic-format=human`):
 *      ```
 *      error: file not found
 *        ┌─ chapters/intro.typ:12:3
 *      ```
 *      The path-bearing line is the `┌─` anchor. The regex pins it to
 *      start-of-line (allowing leading whitespace) so a literal
 *      `┌─ path:line:col` quoted mid-prose never matches.
 *
 *   2. **Compact** (`--diagnostic-format=short`):
 *      ```
 *      chapters/intro.typ:12:3: error: file not found
 *      ```
 *      Self-contained on a single line — the trailing `error:` /
 *      `warning:` token is on the same line as the path.
 *
 * Two gates protect against false positives; both must pass:
 *
 *   - **Regex anchor** — both patterns require the path token at the start
 *     of the line, so embedded `┌─` or `path:line:col` substrings inside
 *     unrelated prose never trip a match.
 *   - **File existence check** — the resolved path must locate to a
 *     [VirtualFile] that exists on disk. A stale or deleted path falls
 *     through to plain text, so the user never sees a broken link.
 *
 * The filter is stateless across lines; every line is judged on its own.
 */
class TypstConsoleFilter(private val project: Project) : Filter {

    private val log = logger<TypstConsoleFilter>()
    private val typstRoot: String? by lazy {
        val root = resolveTypstRoot(project)
        log.debug { "Typst project root: $root" }
        root
    }

    override fun applyFilter(line: String, entireLength: Int): Filter.Result? {
        log.debug { "Processing line: $line" }

        if (line.isBlank()) {
            log.trace { "Blank line, skipping" }
            return null
        }

        val match: FilterMatch = analyseLine(line).also { match ->
            if (match == null) {
                log.trace { "No match for line: $line" }
            }
        } ?: return null

        log.debug { "Filter match for line: $line: $match" }

        val virtualFile = resolveExistingVirtualFile(match.path) ?: return null

        val lineStart = entireLength - line.length
        val hyperlinkInfo = OpenFileHyperlinkInfo(
            project,
            virtualFile,
            (match.lineNumber - 1).coerceAtLeast(0),
            (match.column - 1).coerceAtLeast(0),
        )
        return Filter.Result(
            lineStart + match.highlightStartInLine,
            lineStart + match.highlightEndInLine,
            hyperlinkInfo,
        )
    }

    private fun resolveExistingVirtualFile(rawPath: String): VirtualFile? {
        log.debug { "Resolving raw path: $rawPath" }

        val absolutePath: Path? = computeAbsolutePath(rawPath, typstRoot)
        log.debug { "Raw path $rawPath -> Absolute path: $absolutePath"}
        if (absolutePath == null) {
            return null
        }

        val virtualFile: VirtualFile? = LocalFileSystem.getInstance().findFileByNioFile(absolutePath)
        log.debug { "Absolute path: $absolutePath -> Virtual file: ${virtualFile.toString()}"}
        if (virtualFile == null || !virtualFile.exists()) {
            log.debug { "Virtual file does not exist: $absolutePath" }
            return null
        }

        log.debug { "Resolved path: $virtualFile" }
        return virtualFile
    }
}

/**
 * The information extracted from a diagnostic line that participates in
 * building the hyperlink: the raw path string (later resolved against the
 * project root), the 1-indexed line and column from typst, and the
 * highlight span within the console line (relative to the start of the
 * line, not the console).
 */
internal data class FilterMatch(
    val path: String,
    val lineNumber: Int,
    val column: Int,
    val highlightStartInLine: Int,
    val highlightEndInLine: Int,
)

// Rich format anchor: ┌─ <path>:<line>:<col>
// Box-drawing characters: U+250C ("┌"), U+2500 ("─").
// `\S.*?` is non-greedy, so backtracking lets us correctly skip past
// embedded colons in paths (e.g. Windows drive letters: "C:\foo\bar.typ").
// Anchored to start-of-line (allowing leading whitespace) so the `┌─`
// token has to *open* the line — a literal `┌─ path:line:col` appearing
// mid-prose in a `= help:` follow-up or quoted example never matches.
private val RICH_ANCHOR_REGEX = Regex("""^\s*┌─\s+(\S.*?):(\d+):(\d+)""")

// Compact format: <path>:<line>:<col>: (error|warning): ...
// Anchored to the start of the line (allowing leading whitespace) so
// the path token has to *be* the line, not occur mid-prose.
private val COMPACT_REGEX = Regex("""^\s*(\S.*?):(\d+):(\d+):\s+(error|warning):""")

/**
 * Pure decision function: given a console line, return the match to
 * hyperlink or null. Exposed `internal` so unit tests can drive it
 * directly without an IntelliJ test fixture.
 *
 * Both patterns are anchored to start-of-line and the downstream
 * file-existence check gates synthetic paths, so no lookback state is
 * needed — every line is judged on its own.
 */
internal fun analyseLine(line: String): FilterMatch? {
    // Analyse Rich Anchor first, as it's more specific
    RICH_ANCHOR_REGEX.find(line)?.let { return buildMatch(it) }
    COMPACT_REGEX.find(line)?.let { return buildMatch(it) }
    return null
}

private fun buildMatch(result: MatchResult): FilterMatch {
    val pathRange = result.groups[1]!!.range
    val colRange = result.groups[3]!!.range
    return FilterMatch(
        path = result.groupValues[1],
        lineNumber = result.groupValues[2].toInt(),
        column = result.groupValues[3].toInt(),
        highlightStartInLine = pathRange.first,
        highlightEndInLine = colRange.last + 1,
    )
}

/**
 * Combine a raw path emitted by typst with the project root (when the
 * path is relative) and return it as an absolute, normalised
 * [Path].
 *
 * The returned Path is consumed by `LocalFileSystem.findFileByNioFile`,
 * which performs its own OS-specific translation to the VFS canonical
 * form — so this helper deliberately keeps the value in NIO's
 * platform-native shape rather than imposing its own slash convention.
 *
 * Returns null when the path is relative *and* no project root is
 * available — there is nothing meaningful to resolve against.
 *
 * Exposed `internal` so the path-handling rules can be tested without
 * touching the VFS.
 */
internal fun computeAbsolutePath(rawPath: String, projectRoot: String?): Path? {
    val path = Path.of(rawPath)
    val absolute = if (path.isAbsolute) {
        path.normalize()
    } else {
        if (projectRoot == null) return null
        Path.of(projectRoot).resolve(path).toAbsolutePath().normalize()
    }
    return absolute
}
