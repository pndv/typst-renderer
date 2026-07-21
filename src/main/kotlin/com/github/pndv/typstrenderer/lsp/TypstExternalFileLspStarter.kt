package com.github.pndv.typstrenderer.lsp

import com.github.pndv.typstrenderer.language.TypstFileType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.debug
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.lsp.api.LspClientManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val log = logger<TypstExternalFileLspStarter>()

/**
 * Starts a tinymist client for `.typ` files that live *outside* the project's content roots.
 *
 * The platform's LSP integration only calls `LspIntegrationProvider.fileOpened` — the hook
 * [TinymistLspServerSupportProvider] hangs the auto-download and LSP start off — for files
 * that pass `ProjectFileIndex.isInContent`. A standalone document opened from anywhere else
 * (a résumé in the home directory, a note on another drive) therefore never starts a server
 * through that path, no matter how often it is opened: the hook is structurally unreachable
 * for it (issue #92).
 *
 * This listener fills the gap: on every editor open of an out-of-content `.typ` file it
 * resolves the tinymist binary (downloading it when missing, same as the provider) and starts
 * a client through the public `LspClientManager.ensureClientStarted` API, which bypasses the
 * content gate. The client is rooted at the file's folder via
 * [TinymistExternalFileLspServerDescriptor] — see there for why the root matters and how the
 * platform deduplicates repeated starts for the same folder.
 *
 * [TypstExternalFileLspStartupActivity] runs the same handling for files that are already
 * open when the project opens or when the plugin is installed dynamically — moments when no
 * fresh editor-open event will ever fire for them.
 */
class TypstExternalFileLspStarter : FileEditorManagerListener {

    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        handleOpenedFileForExternalLsp(source.project, file)
    }
}

/**
 * Covers `.typ` files that are already open when this code first gets a chance to run:
 * project startup with restored editor tabs, and dynamic plugin install with the file
 * open — the exact onboarding flow of issue #92, where the install happens *because* the
 * user just opened a `.typ` file the IDE had no plugin for.
 */
class TypstExternalFileLspStartupActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        if (ApplicationManager.getApplication().isUnitTestMode) return
        val openFiles = FileEditorManager.getInstance(project).openFiles
        log.debug { "Startup sweep over ${openFiles.size} open file(s) for external .typ files" }
        for (file in openFiles) {
            if (project.isDisposed) break
            resolveExternalLspForOpenFile(project, file)
        }
    }
}

/**
 * Decision table for [handleOpenedFileForExternalLsp], mirroring [decideLspAction] on the
 * provider side. Kept pure so the branching can be unit-tested without an IDE fixture.
 *
 * In-content files are skipped: for those the platform itself calls the provider's
 * `fileOpened`, and handling them here as well would race it. A file with no parent
 * directory (never the case for a local `.typ` file on disk) cannot anchor a workspace
 * root, so it is skipped rather than mis-rooted.
 */
internal fun decideExternalLspAction(
    isUnitTestMode: Boolean,
    isTypstFile: Boolean,
    isInContent: Boolean,
    hasParentDir: Boolean,
    tinymistPath: String?,
): LspStartAction = when {
    isUnitTestMode -> LspStartAction.Skip
    !isTypstFile -> LspStartAction.Skip
    isInContent -> LspStartAction.Skip
    !hasParentDir -> LspStartAction.Skip
    tinymistPath != null -> LspStartAction.StartServer(tinymistPath)
    else -> LspStartAction.TriggerDownload
}

/**
 * Editor-open entry point (the [TypstExternalFileLspStarter] listener). Hops straight off the
 * EDT the listener fires on: the binary resolve stats the PATH and well-known install dirs, and
 * the content check takes a read lock — neither belongs on the EDT.
 *
 * The startup sweep uses [resolveExternalLspForOpenFile] instead: it runs inside a coroutine,
 * where `executeOnPooledThread` and blocking read actions are out of place.
 */
internal fun handleOpenedFileForExternalLsp(project: Project, file: VirtualFile) {
    if (project.isDisposed || ApplicationManager.getApplication().isUnitTestMode) return // Same pre-filter the platform's own LSP file-open dispatch applies: files inside
    // archives or on remote filesystems have no local folder to root a client at.
    if (!file.isInLocalFileSystem) return
    ApplicationManager.getApplication().executeOnPooledThread {
        if (project.isDisposed || !file.isValid) return@executeOnPooledThread

        val isTypstFile =
            file.fileType == TypstFileType // Resolve lazily: the content check and binary resolve are pointless for the // overwhelmingly common non-.typ case.
        val isInContent = isTypstFile && isInProjectContent(project, file)
        val needsBinary = isTypstFile && !isInContent && file.parent != null
        val tinymistPath = if (needsBinary) TinymistManager.getInstance().resolveTinymistPath() else null

        val action = decideExternalLspAction(
            isUnitTestMode = false,
            isTypstFile = isTypstFile,
            isInContent = isInContent,
            hasParentDir = file.parent != null,
            tinymistPath = tinymistPath,
        )
        runExternalLspAction(project, file, action)
    }
}

/**
 * Coroutine-native counterpart of [handleOpenedFileForExternalLsp] for the startup sweep, which
 * runs inside [TypstExternalFileLspStartupActivity.execute]'s suspend context. It classifies the
 * file with a suspend read action and resolves the binary on the IO dispatcher, then hands off to
 * the shared [runExternalLspAction] — deliberately avoiding the blocking-context primitives
 * (`executeOnPooledThread`, `runReadActionBlocking`) that must not be called from a coroutine.
 */
private suspend fun resolveExternalLspForOpenFile(
    project: Project, file: VirtualFile
) { // Same pre-filter as the listener path: files inside archives or on remote filesystems have
    // no local folder to root a client at.
    if (!file.isInLocalFileSystem) return

    val isTypstFile = readAction { file.isValid && file.fileType == TypstFileType }
    if (!isTypstFile) {
        log.debug { "Skipping external LSP start for file ${file.path}" }
        return
    }

    val isInContent = readAction { isFileInProjectContent(project, file) }
    val hasParentDir = file.parent != null
    val tinymistPath = if (!isInContent && hasParentDir) {
        withContext(Dispatchers.IO) { TinymistManager.getInstance().resolveTinymistPath() }
    } else {
        null
    }

    val action = decideExternalLspAction(
        isUnitTestMode = false,
        isTypstFile = true,
        isInContent = isInContent,
        hasParentDir = hasParentDir,
        tinymistPath = tinymistPath,
    )
    runExternalLspAction(project, file, action)
}

/**
 * Acts on a resolved [action] for [file]: start the client, trigger a download-then-start, or
 * skip. Side-effect-only and non-suspend, shared by [handleOpenedFileForExternalLsp] and
 * [resolveExternalLspForOpenFile]. Both invoke it off the EDT (a pooled thread and a background
 * coroutine respectively), which `startExternalClient` requires. The only thread hop it makes
 * lives inside the download callback, which the download service invokes in its own context.
 */
private fun runExternalLspAction(project: Project, file: VirtualFile, action: LspStartAction) {
    when (action) {
        LspStartAction.Skip -> log.debug { "Skipping external LSP start for file ${file.path}" }

        is LspStartAction.StartServer -> startExternalClient(project, action.tinymistPath, file)

        LspStartAction.TriggerDownload -> {
            log.info("Tinymist not found, triggering auto-download for external file ${file.path}")
            TinymistDownloadService.getInstance()
                .downloadInBackground(project) { success -> // Re-resolve instead of trusting the flag: a download already in flight
                    // (settings button, another project) reports failure here yet still
                    // delivers the binary moments later; resolution is the ground truth.
                    // Failure notifications are the download service's job.
                    ApplicationManager.getApplication().executeOnPooledThread {
                        if (project.isDisposed || !file.isValid) return@executeOnPooledThread
                        val resolved = TinymistManager.getInstance().resolveTinymistPath()
                        when {
                            resolved != null && FileEditorManager.getInstance(project)
                                .isFileOpen(file) -> startExternalClient(project, resolved, file)

                            resolved == null -> log.warn(
                                "Tinymist unavailable after download attempt (success=$success); " + "no LSP for external file ${file.path}"
                            )

                            else -> log.debug { "External file ${file.path} closed before tinymist became available" }
                        }
                    }
                }
        }
    }
}

private fun startExternalClient(project: Project, tinymistPath: String, file: VirtualFile) {
    val rootDir = file.parent ?: return
    log.info("Starting external-file tinymist LSP from: $tinymistPath rooted at ${rootDir.path} for file ${file.path}")
    LspClientManager.getInstance(project).ensureClientStarted(
        TinymistLspServerSupportProvider::class.java,
        TinymistExternalFileLspServerDescriptor(project, tinymistPath, rootDir),
    )
}
