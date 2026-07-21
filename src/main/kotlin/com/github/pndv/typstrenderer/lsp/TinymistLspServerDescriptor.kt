package com.github.pndv.typstrenderer.lsp

import com.github.pndv.typstrenderer.language.TypstFileType
import com.github.pndv.typstrenderer.settings.TypstProjectSettingsState
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClientDescriptor
import com.intellij.platform.lsp.api.ProjectWideLspClientDescriptor
import com.intellij.platform.lsp.api.customization.LspCustomization
import com.intellij.platform.lsp.api.customization.LspFormattingSupport
import com.intellij.platform.lsp.api.customization.LspSemanticTokensSupport
import com.intellij.psi.PsiFile
import java.nio.file.Path

/**
 * Descriptor for the project-wide tinymist client: serves `.typ` files that live inside the
 * project's content roots, with the workspace root resolved from the project (or its
 * per-project override).
 *
 * Files *outside* the content roots are deliberately not claimed here — they belong to a
 * [TinymistExternalFileLspServerDescriptor] rooted at their own folder (see
 * [TypstExternalFileLspStarter]). The split keeps exactly one client responsible for any
 * given file, so diagnostics are not duplicated and exports always run against a root the
 * file actually lives under.
 */
class TinymistLspServerDescriptor(
    project: Project, private val tinymistPath: String
) : ProjectWideLspClientDescriptor(project, "Tinymist") {
    private val log = logger<TinymistLspServerDescriptor>()

    override val lspCustomization = tinymistLspCustomization()

    override fun isSupportedFile(file: VirtualFile): Boolean = projectClientClaims(
        isTypstFile = file.fileType == TypstFileType,
        isInContent = isInProjectContent(project, file),
    )

    override fun createInitializationOptions(): Any = buildTinymistInitializationOptions(project, log)

    override fun createCommandLine(): GeneralCommandLine {
        log.debug { "Creating Tinymist LSP command line" }

        val typstRoot = resolveTypstRoot(project)
        log.debug { "Typst project root: $typstRoot" }

        return buildTinymistCommandLine(tinymistPath, project, typstRoot?.let { Path.of(it) }, log)
    }
}

/**
 * Descriptor for a tinymist client serving `.typ` files that sit *outside* the project's
 * content roots (a résumé in the home directory, a one-off letter on another drive, …).
 *
 * The platform's LSP integration never calls `LspIntegrationProvider.fileOpened` for such
 * files — it gates that hook on `ProjectFileIndex.isInContent` — so this descriptor is
 * started explicitly via `LspClientManager.ensureClientStarted` by
 * [TypstExternalFileLspStarter].
 *
 * The client is rooted at the file's own folder ([rootDir]): the workspace root drives both
 * tinymist's import resolution and its `outputPath` template — `$root/<exportDir>/$dir/$name`
 * cannot substitute a main file outside `$root` (the substitution silently yields no
 * destination and the export returns no path). Rooting at the folder makes the PDF land in
 * `<folder>/<exportDir>/<name>.pdf`, and one client serves every external `.typ` file in
 * that folder. The platform identifies clients by descriptor class + name + roots, so
 * repeated `ensureClientStarted` calls for the same folder reuse the running client.
 */
class TinymistExternalFileLspServerDescriptor(
    project: Project, private val tinymistPath: String, private val rootDir: VirtualFile
) : LspClientDescriptor(project, "Tinymist (${rootDir.name})", rootDir) {
    private val log = logger<TinymistExternalFileLspServerDescriptor>()

    override val lspCustomization = tinymistLspCustomization()

    override fun isSupportedFile(file: VirtualFile): Boolean = externalClientClaims(
        isTypstFile = file.fileType == TypstFileType,
        isInContent = isInProjectContent(project, file),
        isUnderRoot = VfsUtilCore.isAncestor(rootDir, file, false),
    )

    override fun createInitializationOptions(): Any = buildTinymistInitializationOptions(project, log)

    override fun createCommandLine(): GeneralCommandLine {
        log.debug { "Creating Tinymist LSP command line for external root ${rootDir.path}" }
        return buildTinymistCommandLine(tinymistPath, project, rootDir.toNioPath(), log)
    }
}

/**
 * File-claim predicate of the project-wide client: `.typ` files inside the project's
 * content roots. Pure so the routing table is unit-testable without a project fixture.
 */
internal fun projectClientClaims(isTypstFile: Boolean, isInContent: Boolean): Boolean = isTypstFile && isInContent

/**
 * File-claim predicate of an external-folder client: `.typ` files under its root that the
 * project-wide client does not claim. The `!isInContent` guard keeps the partition strict
 * even if a content root is later added underneath the external folder.
 */
internal fun externalClientClaims(isTypstFile: Boolean, isInContent: Boolean, isUnderRoot: Boolean): Boolean =
    isTypstFile && !isInContent && isUnderRoot

/**
 * The content-membership check itself: whether [file] lies within [project]'s content roots.
 * Assumes the caller already holds a read lock — [isInProjectContent] wraps it for callers on
 * threads that hold none, and the coroutine startup sweep runs it inside a suspend `readAction`.
 */
internal fun isFileInProjectContent(project: Project, file: VirtualFile): Boolean =
    !project.isDisposed && ProjectFileIndex.getInstance(project).isInContent(file)

/**
 * Whether [file] is inside [project]'s content roots — the same test the platform's LSP
 * integration applies before it lets a provider start a client for an opened file.
 * Wrapped in an explicit non-cancellable read action because `isSupportedFile` is called from
 * both the LSP framework's read actions and plugin threads that hold no lock.
 */
internal fun isInProjectContent(project: Project, file: VirtualFile): Boolean =
    runReadActionBlocking { isFileInProjectContent(project, file) }

/**
 * LSP feature customisation shared by the project-wide and external-file descriptors.
 *
 * Most features are enabled by default in [LspCustomization]: Go to Definition, Hover,
 * Completion, Diagnostics, Find References, Code Actions, Semantic Tokens, Code Folding,
 * Inlay Hints, Document Links. Formatting is pinned to the server so tinymist always
 * handles `.typ` formatting regardless of whether the IDE has its own formatter, and
 * semantic tokens are force-enabled to bypass the platform's TEXT/textmate language-id
 * restriction.
 */
internal fun tinymistLspCustomization(): LspCustomization = object : LspCustomization() {

    override val formattingCustomizer = object : LspFormattingSupport() {
        override fun shouldFormatThisFileExclusivelyByServer(
            file: VirtualFile,
            ideCanFormatThisFileItself: Boolean,
            serverExplicitlyWantsToFormatThisFile: Boolean,
        ): Boolean {
            return file.fileType == TypstFileType
        }
    }

    override val semanticTokensCustomizer = object : LspSemanticTokensSupport() {
        override fun shouldAskServerForSemanticTokens(psiFile: PsiFile): Boolean = true
    }
}

/**
 * Configures tinymist via `initializationOptions` sent in the LSP `initialize` request.
 *
 * `outputPath` template variables (`$root`, `$dir`, `$name`) are documented in
 * docs/platform_gotchas.md under "tinymist controls export destination via global config".
 * The export directory (the segment between `$root` and the mirrored `$dir/$name`) is the
 * per-project [TypstProjectSettingsState.typstExportPath] setting, defaulting to `"target"`,
 * so the resulting `$root/<exportDir>/$dir/$name` shape mirrors the source tree under that
 * directory. It relies on tinymist v0.14.18+ for the workspace-root-file fix
 * (PR #2473 — see the matching gotcha entry).
 *
 * `exportPdf` is held at `"never"` — the previewer and the Compile action trigger exports
 * explicitly through `tinymist.exportPdf`.
 *
 * `formatterProseWrap` is on because typstyle's default (`false`) makes it return zero
 * edits for over-long prose lines — Reformat Code then looks like a dead action on
 * prose-heavy documents (issue #88). With it on, prose reflows to typstyle's print
 * width (default 120). See docs/issue-88-autoformat-diagnosis.md for the evidence.
 *
 * The export directory is read at LSP init, so the project settings page restarts the
 * server when it changes — same pattern as the project-root and font-path overrides.
 */
internal fun buildTinymistInitializationOptions(project: Project, log: Logger): Any {
    val exportPath = TypstProjectSettingsState.getInstance(project).typstExportPath
    val outputPath = buildOutputPathTemplate(exportPath)
    val options = mapOf(
        "outputPath" to outputPath,
        "exportPdf" to "never",
        "formatterProseWrap" to true,
    )
    log.debug { "tinymist initializationOptions: $options" }
    return options
}

/**
 * Builds the `tinymist lsp` command line. The per-project font-path override applies to
 * both descriptor flavours; [workingDir] is the workspace root the client is rooted at —
 * the resolved project root for the project-wide client, the file's folder for an
 * external-file client.
 */
internal fun buildTinymistCommandLine(
    tinymistPath: String,
    project: Project,
    workingDir: Path?,
    log: Logger,
): GeneralCommandLine {
    val fontPath = resolveTypstFontPath(project)
    log.debug { "Typst project Font path: $fontPath" }

    val commandLine = GeneralCommandLine(buildList {
        add(tinymistPath)
        add("lsp")
        fontPath?.let { add("--font-path"); add(it) }
    }).apply {
        withCharset(Charsets.UTF_8)
        workingDir?.let { withWorkingDirectory(it) }
    }

    log.debug { "TinyMist LSP Server CommandLine is: $commandLine" }

    return commandLine
}

/**
 * Builds tinymist's `outputPath` template from the per-project export directory.
 *
 * The [exportPath] is treated as a directory relative to the workspace root; the
 * source tree is mirrored beneath it via tinymist's `$dir`/`$name` variables.
 * Surrounding whitespace and leading/trailing path separators are stripped so a
 * value like `"/out/"` does not produce `$root//out//$dir/$name`, and a blank
 * value falls back to [TypstProjectSettingsState.DEFAULT_EXPORT_PATH].
 */
internal fun buildOutputPathTemplate(exportPath: String): String {
    val exportDir = exportPath.trim().trim('/', '\\').ifEmpty { TypstProjectSettingsState.DEFAULT_EXPORT_PATH }
    return $$"$root/$${exportDir}/$dir/$name"
}
