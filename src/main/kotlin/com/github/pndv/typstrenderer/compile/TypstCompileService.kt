package com.github.pndv.typstrenderer.compile

import com.github.pndv.typstrenderer.Common.printToConsole
import com.github.pndv.typstrenderer.TypstBundle
import com.github.pndv.typstrenderer.lsp.ExportPdfResult
import com.github.pndv.typstrenderer.lsp.TypstExportService
import com.github.pndv.typstrenderer.lsp.resolveTypstExportTarget
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import java.nio.file.Path

/**
 * Manual `Compile` action entry-point. Routes through tinymist's
 * `workspace/executeCommand` (`tinymist.exportPdf`) so the same engine that
 * powers diagnostics also produces the PDF — no second toolchain to install,
 * no `--font-path` / `--root` argv drift, no stderr scraping. `tinymist` returns
 * a failed compile as a JSON-RPC error carrying the formatted diagnostic, which
 * we forward verbatim to the Typst Output console (see [ExportPdfResult]).
 *
 * The output destination is controlled centrally by `tinymist.outputPath` sent
 * in `initializationOptions` (see [com.github.pndv.typstrenderer.lsp.TinymistLspServerDescriptor]).
 * Per-call output paths are *not* a thing in tinymist's protocol, so callers
 * no longer pass one — there used to be an `outputPath` parameter; it was
 * dead for both call-sites and is gone.
 */
@Service(Service.Level.PROJECT)
class TypstCompileService(private val project: Project) {
    private val log = logger<TypstCompileService>()

    fun compile(inputPath: String) { // `tinymist.exportPdf` compiles the file as it exists ON DISK, so unsaved editor changes
        // are invisible to it: pressing Compile on a modified document produced a PDF of the last
        // *saved* text while reporting success. The previewer never hit this — it only re-exports
        // in reaction to a save — but the manual action goes straight from the keystroke to the
        // export, so it has to flush first.
        //
        // saveAllDocuments, not just this file's document: with a main entry pinned the export
        // target is the pinned main while the unsaved edits may sit in a chapter it `#include`s,
        // and there is no dependency graph here to work out which files matter. Saving everything
        // is also what an IDE conventionally does before a build.
        flushUnsavedDocuments()

        // Record the target before the LSP send so the Recompile toolbar action
        // still knows what to recompile even if this attempt fails (LSP not
        // attached, server error, etc.) — same intent as before the migration.
        log.debug { "TypstCompileService will track $inputPath for compilation" }
        project.service<TypstLastCompiledTracker>().record(inputPath)

        // Immediate feedback in the tool window before the pooled-thread work starts;
        // also pops the window open so the user sees the subsequent outcome message.
        printToConsole(
            project, log,
            TypstBundle.message("console.compile.starting", inputPath),
            ConsoleViewContentType.SYSTEM_OUTPUT,
        )

        runExportOnPooledThread(inputPath)
    }

    /**
     * Writes every modified document to disk. Document saving is EDT-only, and `compile` is
     * invoked from an action (already on the EDT) — so run it directly there and fall back to
     * `invokeAndWait` for any caller that is not, rather than assuming either.
     */
    private fun flushUnsavedDocuments() {
        val app = ApplicationManager.getApplication()
        val save = { FileDocumentManager.getInstance().saveAllDocuments() }
        if (app.isDispatchThread) save() else app.invokeAndWait(save)
    }

    private fun runExportOnPooledThread(inputPath: String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try { // A pinned main entry redirects the export: tinymist.exportPdf compiles the
                // exact path it is handed and ignores tinymist.pinMain, so compiling a
                // chapter directly would still fail on cross-file references.
                val target = resolveTypstExportTarget(project, Path.of(inputPath))
                log.debug { "[TypstCompileService] compile -> exporting $target (requested $inputPath)" }
                val result = project.service<TypstExportService>().exportPdf(target)
                log.debug { "[TypstCompileService] compile -> exporting $target. Result: $result" }

                when (result) {
                    is ExportPdfResult.Exported -> printToConsole(
                        project, log,
                        TypstBundle.message("console.compile.success", result.pdf.toString()),
                        ConsoleViewContentType.SYSTEM_OUTPUT,
                    )

                    // tinymist rejected the document — print its formatted diagnostic
                    // (label clashes, syntax errors, missing imports) straight to the
                    // console. In multi-file projects this is the user's primary signal:
                    // the error frequently lives in an #include-d chapter file, not the
                    // focused entry file, so the editor gutter on what they are looking
                    // at may show nothing.
                    is ExportPdfResult.Failed -> printToConsole(
                        project, log,
                        TypstBundle.message("console.compile.failed", result.detail),
                        ConsoleViewContentType.ERROR_OUTPUT,
                    )

                    // The compile never reached tinymist (LSP not attached / round-trip
                    // timed out). There is no document diagnostic to show — point the
                    // user at the IDE log, where the platform records the transport
                    // failure.
                    ExportPdfResult.Unavailable -> printToConsole(
                        project, log,
                        TypstBundle.message(
                            "console.compile.failed",
                            TypstBundle.message("console.compile.failed.lspUnavailable"),
                        ),
                        ConsoleViewContentType.ERROR_OUTPUT,
                    )
                }
            } catch (pce: ProcessCanceledException) {
                throw pce
            } catch (e: Exception) {
                printToConsole(
                    project, log,
                    TypstBundle.message("console.compile.error", e.message ?: ""),
                    ConsoleViewContentType.ERROR_OUTPUT,
                )
            }
        }
    }
}
