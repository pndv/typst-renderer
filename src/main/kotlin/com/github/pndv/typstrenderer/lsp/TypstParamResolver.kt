package com.github.pndv.typstrenderer.lsp

import com.github.pndv.typstrenderer.settings.TypstProjectSettingsState
import com.intellij.openapi.project.Project
import java.io.File

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
