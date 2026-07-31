package com.github.pndv.typstrenderer.lsp

import com.github.pndv.typstrenderer.settings.TypstProjectSettingsState
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.io.File
import java.nio.file.Path

/**
 * Resolves the Typst project root that should be passed via `--root` to the
 * typst CLI and used as the working directory for the tinymist LSP (which
 * drives the LSP `initialize` request's `rootUri`).
 *
 * Resolution order:
 * 1. Configured override from [TypstProjectSettingsState.typstProjectRoot]
 *    when non-blank AND the path exists as a directory.
 * 2. `project.basePath` if non-null.
 * 3. `null` — callers should not pass `--root`, and tinymist will fall back
 *    to its own root auto-detection.
 *
 * The override-must-exist guard means a stale or mistyped setting silently
 * falls through to `project.basePath` rather than producing a typst CLI
 * error for an invalid path. (A user-facing warning for the "override set
 * but invalid" case is tracked under Tier 1.2.2 Part B's notification
 * scope; today it's a silent fallthrough, matching the pre-Part-A
 * behaviour where no override existed.)
 */
fun resolveTypstRoot(project: Project): String? {
    if (project.isDisposed) return null
    return resolveTypstRoot(
        configuredOverride = TypstProjectSettingsState.getInstance(project).typstProjectRoot,
        projectBasePath = project.basePath,
    )
}

/**
 * Pure-function core of [resolveTypstRoot]. Exposed for unit testing without
 * an IntelliJ fixture — the `Project` overload is a thin wrapper that pulls
 * `configuredOverride` from [TypstProjectSettingsState] and reads
 * `project.basePath` before delegating here.
 */
internal fun resolveTypstRoot(
    configuredOverride: String,
    projectBasePath: String?,
): String? {
    if (configuredOverride.isNotBlank() && File(configuredOverride).isDirectory) {
        return configuredOverride
    }
    return projectBasePath
}

/**
 * Resolves the font search path to pass via `--font-path` to the typst CLI
 * and tinymist LSP.
 *
 * Returns [TypstProjectSettingsState.typstFontPath] when it is non-blank and
 * points to an existing directory, otherwise `null`. A `null` return means
 * the flag should be omitted entirely — typst will use its built-in font
 * discovery.
 *
 * Unlike [resolveTypstRoot] there is no project-base-path fallback: font
 * directories are rarely co-located with the project root, so falling through
 * silently would be more surprising than returning `null`.
 */
fun resolveTypstFontPath(project: Project): String? {
    if (project.isDisposed) return null

    val fontPath: String = TypstProjectSettingsState.getInstance(project).typstFontPath
    if (fontPath.isNotBlank() && File(fontPath).isDirectory) {
        return fontPath
    }
    return null
}

/**
 * Resolves the main entry file (`main.typ`) to pin as tinymist's compile entry
 * via `tinymist.pinMain` in a multi-file project.
 *
 * Returns [TypstProjectSettingsState.typstMainFile] when it is non-blank, points
 * to an existing regular file, and has a `.typ` extension; otherwise `null`. A
 * `null` return means no pin should be sent (or an existing pin cleared) — tinymist
 * falls back to compiling the focused file.
 *
 * Like [resolveTypstFontPath] there is no project-base-path fallback: the main file
 * is an explicit, deliberate per-project choice, so a stale or mistyped path falls
 * through to `null` rather than pinning something the user did not intend.
 */
fun resolveTypstMainFile(project: Project): String? {
    if (project.isDisposed) return null
    return resolveTypstMainFile(TypstProjectSettingsState.getInstance(project).typstMainFile)
}

/**
 * Pure-function core of [resolveTypstMainFile]. Exposed for unit testing without an
 * IntelliJ fixture — the `Project` overload is a thin wrapper that reads
 * `typstMainFile` from [TypstProjectSettingsState] before delegating here.
 */
internal fun resolveTypstMainFile(configuredMainFile: String): String? {
    if (configuredMainFile.isBlank()) return null
    val file = File(configuredMainFile)
    if (file.isFile && file.extension.equals("typ", ignoreCase = true)) {
        return configuredMainFile
    }
    return null
}

/**
 * Resolves which file an export (`tinymist.exportPdf`) should actually target when the
 * user asked to compile [focusedFile].
 *
 * `tinymist.pinMain` only redirects the LSP's *primary* task — the one that produces
 * diagnostics. `tinymist.exportPdf` takes an explicit path argument and spawns its own
 * export task for exactly that path, so it compiles the passed file standalone and
 * ignores the pin entirely (verified against tinymist's own log: pinning switches the
 * primary entry and main compiles clean, while a concurrent `exportPdf(chapter.typ)`
 * still reports `label … does not exist`).
 *
 * So the redirection has to happen on our side: when a main entry is pinned, every
 * export targets it, and the chapter file the user is looking at is rendered as part of
 * the whole document rather than compiled in isolation. With no pin configured the
 * focused file is returned unchanged, preserving single-file behaviour.
 *
 * The redirect applies **only to files inside the project's content roots**. A pinned main is
 * a project-scoped setting, and a `.typ` file outside those roots cannot be `#include`d by it
 * (it sits outside `$root`, which is exactly why such a file gets its own folder-rooted client
 * — see [TinymistExternalFileLspServerDescriptor]). Redirecting it would export the project's
 * main through the *project-wide* client and render that document in the standalone file's
 * preview, so out-of-content files always compile themselves.
 */
fun resolveTypstExportTarget(project: Project, focusedFile: VirtualFile): Path {
    val focusedPath = Path.of(focusedFile.path)
    if (project.isDisposed) return focusedPath

    // Cheap exit before taking a read lock: with no main configured there is nothing to
    // redirect to, which is the common single-file case.
    val configured = TypstProjectSettingsState.getInstance(project).typstMainFile
    if (configured.isBlank()) return focusedPath

    return resolveTypstExportTarget(configured, focusedPath, isInProjectContent(project, focusedFile))
}

/**
 * [Path]-based convenience overload for callers that only hold a path (the Compile action
 * routes through `TypstCompileService`, which is handed a `String`).
 *
 * A path the VFS cannot resolve gets no redirect: without a [VirtualFile] there is no way to
 * establish project membership, and failing toward "compile what was asked for" is safer than
 * silently exporting a different document.
 */
fun resolveTypstExportTarget(project: Project, focusedFile: Path): Path {
    val virtualFile = LocalFileSystem.getInstance().findFileByNioFile(focusedFile) ?: return focusedFile
    return resolveTypstExportTarget(project, virtualFile)
}

/**
 * Pure-function core of [resolveTypstExportTarget]. Exposed for unit testing without an
 * IntelliJ fixture — the `Project` overload supplies `configuredMainFile` from
 * [TypstProjectSettingsState] and computes [focusedFileInProject] from the project's content
 * roots before delegating here.
 *
 * A file outside the project short-circuits first: it must never be redirected, whatever the
 * setting says.
 */
internal fun resolveTypstExportTarget(
    configuredMainFile: String,
    focusedFile: Path,
    focusedFileInProject: Boolean,
): Path {
    if (!focusedFileInProject) return focusedFile
    val mainFile = resolveTypstMainFile(configuredMainFile) ?: return focusedFile
    return Path.of(mainFile)
}
