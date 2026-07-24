# Typst Renderer Plugin — Architecture

> **Single-binary model (since 0.4.0–0.4.1).** The plugin depends on **one** external
> binary: `tinymist`. It provides code intelligence *and* PDF export, both over the Language
> Server Protocol. The standalone `typst` CLI, `typst watch`, and the separate download/watch
> services were retired when compile/export moved onto tinymist's `workspace/executeCommand`
> surface — see `docs/IMPROVEMENTS_COMPLETED.md` (Tier 3.6). Anything below that still mentions
> a `typst` subprocess would be describing the pre-0.4.0 design.

## What is Tinymist and Why Is It Needed?

**Tinymist** is a **Language Server** for the **Typst** markup language. It implements the **Language Server Protocol (LSP)** — a standardized JSON-RPC protocol (created by Microsoft for VS Code, now universal) that lets any editor communicate with a language-specific backend to get smart features.

### What the Language Server Protocol Does

```
┌──────────────────┐         JSON-RPC (stdio)           ┌──────────────────┐
│   IDE / Editor   │  ──── LSP Messages ──────────────> │  Language Server │
│  (IntelliJ IDEA) │ <──── LSP Responses ────────────── │   (tinymist)     │
└──────────────────┘                                    └──────────────────┘
```

The IDE sends **requests** like:
- `textDocument/completion` — "What completions are available at line 5, col 12?"
- `textDocument/hover` — "What's the type/docs for the symbol under the cursor?"
- `textDocument/definition` — "Go to definition of this function"
- `textDocument/diagnostics` — "Are there any errors in this file?"
- `textDocument/formatting` — "Format this document"
- `workspace/executeCommand` — server-specific commands; the plugin uses `tinymist.exportPdf`
  to produce the preview/output PDF and `tinymist.pinMain` (planned) for multi-file entry.

The language server **responds** with structured data (completions, diagnostics, locations, a written PDF path, etc.).

### Why Tinymist Specifically?

Typst is a relatively new language (alternative to LaTeX). Tinymist is the **official/community LSP server** for Typst (GitHub: `Myriad-Dreamin/tinymist`). It provides:

- **Code completion** — function names, parameters, packages
- **Diagnostics** — real-time error and warning highlighting
- **Hover documentation** — inline docs for functions/types
- **Go to definition** — navigate to symbol declarations
- **Semantic tokens** — rich syntax highlighting beyond regex-based patterns
- **Document symbols** — outline view, breadcrumbs
- **Formatting** — auto-format Typst code (via typstyle)
- **PDF export** — `tinymist.exportPdf` compiles the document in-process and writes the PDF

**Without tinymist**, the plugin would only have basic text editing with no intelligence — no autocomplete, no error
checking, no navigation, and no preview.

### How It Integrates in This Plugin

1. When a `.typ` file is opened, `TinymistLspServerSupportProvider` is triggered (for files inside the project's content
   roots).
2. It finds the `tinymist` binary (user path > system PATH > well-known dirs > downloaded copy).
3. It launches `tinymist lsp` as a subprocess.
4. IntelliJ's built-in LSP client handles the JSON-RPC communication over stdio.
5. The IDE automatically gets completions, diagnostics, etc.; the preview and the *Compile* action drive PDF export
   through the same server via `tinymist.exportPdf`.

**Note:** `tinymist` is the **only** external binary. The same running server that powers completions and diagnostics
also performs the PDF export, so the preview and the shipped PDF are always produced by the same engine and the same
project state — no version drift, no `--root`/`--font-path` argv to keep in sync across two tools.

**Out-of-project files.** The platform only calls `fileOpened` for files that pass `ProjectFileIndex.isInContent`, so a
`.typ` opened from outside the project (a résumé in the home directory, a one-off letter on another drive) never starts
a server through the provider. `TypstExternalFileLspStarter` fills that gap: it starts a tinymist client rooted at the
file's own folder on editor open and at project startup (issue #92).

---

## Skills Needed for Plugin Development

| Skill Area                         | Why                                                                                                      |
|------------------------------------|----------------------------------------------------------------------------------------------------------|
| **Kotlin**                         | All source code is in Kotlin — the standard language for modern IntelliJ plugins                         |
| **IntelliJ Platform SDK**          | Plugin extension points, services, actions, file editors, tool windows, notifications                    |
| **LSP (Language Server Protocol)** | Integrating tinymist, including the `workspace/executeCommand` send-side used for PDF export             |
| **JCEF (Chromium Embedded)**       | The PDF preview uses an embedded Chromium browser (`JBCefBrowser`) rendering a vendored PDF.js viewer    |
| **Netty / HTTP**                   | PDF.js is served to JCEF over a local `HttpRequestHandler` (`PdfjsRequestHandler`) rather than `file://` |
| **Gradle**                         | Build system with IntelliJ Platform Gradle Plugin for packaging/publishing                               |
| **Typst language basics**          | Understanding what the end-user needs from the editor                                                    |

---

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────┐
│                        IntelliJ IDEA IDE                            │
│                                                                     │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │                    Plugin: typst-renderer                     │  │
│  │                                                               │  │
│  │  ┌─────────────────────────────────────────────────────────┐  │  │
│  │  │              File Type & Language Layer                 │  │  │
│  │  │                                                         │  │  │
│  │  │  TypstLanguage ─ TypstFileType ─ TypstIcons ─ Lexer/    │  │  │
│  │  │  (defines "Typst") (maps .typ)  (icon)   ParserDef.     │  │  │
│  │  └─────────────────────────────────────────────────────────┘  │  │
│  │                          │                                    │  │
│  │          ┌───────────────┼───────────────┐                    │  │
│  │          ▼               ▼               ▼                    │  │
│  │  ┌──────────────┐ ┌──────────────┐ ┌──────────────────────┐   │  │
│  │  │  LSP Layer   │ │ Editor Layer │ │  Compile Layer       │   │  │
│  │  │              │ │              │ │                      │   │  │
│  │  │ SupportProv. │ │ SplitEditor  │ │ CompileService ──┐   │   │  │
│  │  │ Descriptor   │ │  ┌────────┐  │ │ CompileAction    │   │   │  │
│  │  │ Manager      │ │  │FilePrev│  │ │ LastCompiled     │   │   │  │
│  │  │ Commands ────┼─┼──│(JCEF + │  │ │ Tracker          │   │   │  │
│  │  │ DownloadSvc  │ │  │PDF.js) │  │ └────────┬─────────┘   │   │  │
│  │  │ ExternalStrt │ │  └───┬────┘  │          │             │   │  │
│  │  └──────┬───────┘ └──────┼───────┘          │             │   │  │
│  │         │                │  ▲  local PDF.js │             │   │  │
│  │         │                │  └── HTTP ── PdfjsRequestHandler│   │  │
│  │  ┌──────────────┐ ┌─────────────┐ ┌───────────────────────┐   │  │
│  │  │ Settings     │ │ Tool Window │ │ Theme + Viewport      │   │  │
│  │  │ App +        │ │ (Output     │ │ (dark/light sync,     │   │  │
│  │  │ Project      │ │  Console)   │ │  scroll persistence)  │   │  │
│  │  └──────────────┘ └─────────────┘ └───────────────────────┘   │  │
│  └───────────────────────────────────────────────────────────────┘  │
│                          │                                          │
└──────────────────────────┼──────────────────────────────────────────┘
                           │  JSON-RPC over stdio
              ┌────────────▼─────────────────────────────────┐
              │        External Process (one binary)         │
              │   ┌──────────────────────────────────────┐   │
              │   │            tinymist lsp              │   │
              │   │                                      │   │
              │   │  Code intel:        PDF export:      │   │
              │   │  - completions      workspace/       │   │
              │   │  - diagnostics       executeCommand  │   │
              │   │  - hover docs        → tinymist.     │   │
              │   │  - go-to-def           exportPdf     │   │
              │   │  - semantic tokens  writes .pdf to   │   │
              │   │  - formatting        outputPath      │   │
              │   └──────────────────────────────────────┘   │
              └──────────────────────────────────────────────┘
```

The PDF is **written by tinymist in-process** to a path derived from the `tinymist.outputPath`
config template (see below); the plugin never spawns a compiler subprocess of its own.

---

## Data Flow — Opening and Previewing a `.typ` File

```
User opens file.typ
       │
       ├──► TypstFileType recognizes .typ extension
       │
       ├──► TypstSplitEditorProvider creates split view:
       │       ├── Left:  Standard text editor (with LSP features)
       │       └── Right: TypstFilePreviewer
       │                    │
       │                    ├── Requests a PDF export via tinymist.exportPdf
       │                    │     (TinymistCommands.exportPdf → executeCommand),
       │                    │     re-triggered on each save (Pull model)
       │                    ├── Listens on VFS for the written PDF and reloads
       │                    │     (hot-swap in place, or full-navigate to the viewer,
       │                    │      decided from the browser's live URL)
       │                    └── Serves the vendored PDF.js viewer to JCEF over a local
       │                          HttpRequestHandler (PdfjsRequestHandler); viewport
       │                          scroll is persisted via a JS↔Kotlin bridge (JBCefJSQuery)
       │
       └──► TinymistLspServerSupportProvider.fileOpened()   (in-content files)
               │
               ├── TinymistManager.resolveTinymistPath()
               │     (settings → PATH → well-known dirs → downloaded binary)
               │
               ├── If found: starts "tinymist lsp" subprocess
               │     IntelliJ LSP client ←──JSON-RPC──► tinymist
               │     (initializationOptions carry outputPath, exportPdf="never",
               │      formatterProseWrap=true — see TinymistLspServerDescriptor)
               │
               └── If not found: TinymistDownloadService downloads from GitHub,
                     then starts the LSP server

Out-of-content file.typ  ──► TypstExternalFileLspStarter starts a client
                              rooted at the file's own folder (issue #92)
```

### Where the PDF lands — `tinymist.outputPath`

Export destination is **not** a per-call argument; it is controlled globally by the
`tinymist.outputPath` config sent in `initializationOptions`. The plugin builds the template as
`$root/<exportDir>/$dir/$name`, where `<exportDir>` is the per-project
`TypstProjectSettingsState.typstExportPath` (default `target`). This mirrors the source tree under the export directory
and guarantees per-file uniqueness (`$dir/$name`). The template is read at LSP init, so changing the export directory in
project settings restarts the server — the same pattern used for the project-root and font-path overrides. Requires
tinymist v0.14.18+ for the workspace-root-file output-path fix (upstream PR #2473).

---

## Project Structure

```
src/main/kotlin/com/github/pndv/typstrenderer/
├── TypstBundle.kt                          — Resource bundle (i18n strings)
├── Common.kt                               — Console helpers (printToConsole / clearConsoleView)
├── Constants.kt                            — Shared constants
├── language/
│   ├── TypstLanguage.kt                    — Language definition ("Typst")
│   ├── TypstFileType.kt                    — File type for .typ files
│   ├── TypstFile.kt                        — PSI file
│   ├── TypstIcons.kt                       — File icon (typst.svg)
│   ├── TypstLexer.kt                       — Lexer
│   ├── TypstParserDefinition.kt            — Parser definition
│   ├── TypstSyntaxHighlighter.kt           — Token → TextAttributesKey mapping
│   ├── TypstTokenType.kt                   — Token types
│   └── TypstCommenter.kt                   — Line/block comment support
├── lsp/
│   ├── TinymistLspServerSupportProvider.kt — LSP entry point (in-content files)
│   ├── TinymistLspServerDescriptor.kt      — LSP command + initializationOptions + customisers
│   ├── TinymistManager.kt                  — tinymist binary resolution (PATH, well-known dirs, download)
│   ├── TinymistDownloadService.kt          — Auto-download tinymist from GitHub
│   ├── TinymistCommands.kt                 — executeCommand wrappers (exportPdf, pinMain, getServerInfo)
│   ├── TypstExternalFileLspStarter.kt      — LSP for out-of-content .typ files (#92)
│   ├── TypstLspRenameHandler.kt            — Off-EDT cancellable rename
│   ├── TypstParamResolver.kt               — Resolves project root / font path (disposed-safe)
│   └── PlatformConfig.kt                   — Per-platform tinymist asset/download config
├── editor/
│   ├── TypstSplitEditorProvider.kt         — Split editor provider (code + preview)
│   ├── TypstSplitEditor.kt                 — Split editor
│   ├── TypstFilePreviewer.kt               — Live PDF preview via JCEF + PDF.js, LSP export
│   ├── PdfjsRequestHandler.kt              — Local HTTP server serving the PDF.js viewer + PDFs
│   └── PdfViewportState.kt                 — Persisted scroll/zoom state
├── compile/
│   ├── TypstCompileService.kt              — Manual Compile → tinymist.exportPdf
│   └── TypstLastCompiledTracker.kt         — Last-compiled target (for toolbar recompile)
├── actions/
│   ├── TypstCompileAction.kt               — Compile menu action (Ctrl+Shift+T)
│   ├── TypstRecompileFromOutputAction.kt   — Recompile from the Output tool window
│   ├── TypstClearOutputConsoleAction.kt    — Clear console toolbar action
│   ├── TypstScrollOutputToEndAction.kt     — Scroll-to-end toolbar action
│   └── TypstActionTargetResolver.kt        — Resolves the target .typ from context
├── settings/
│   ├── TypstSettingsState.kt               — Application-level persistent settings
│   ├── TypstSettingsConfigurable.kt        — Application settings UI (Tools > Typst)
│   ├── TypstProjectSettingsState.kt        — Per-project settings (root, font path, export dir)
│   └── TypstProjectSettingsConfigurable.kt — Project settings UI (Project Overrides)
├── theme/
│   ├── TypstThemeService.kt                — Preview light/dark theme state
│   └── TypstThemeListener.kt               — Syncs preview to IDE theme changes
└── toolWindow/
    ├── TypstOutputToolWindowFactory.kt     — Output console (bottom panel)
    ├── TypstConsoleHolder.kt               — Shared ConsoleView holder + pre-open buffer
    └── TypstConsoleFilter.kt               — Clickable file:line:col hyperlinks
```

---

## Binary Resolution Strategy

Only the `tinymist` binary is resolved (the `typst` CLI is no longer used). Priority order:

```
1. User-configured path (Settings > Tools > Typst)
       │ (if empty or invalid)
       ▼
2. System PATH + well-known install directories
   (Cargo, Homebrew, Scoop, Nix, Chocolatey, etc.)
       │ (if not found)
       ▼
3. Previously downloaded binary in plugin data directory
   ({pluginsPath}/typst-renderer/bin/)
       │ (if not found)
       ▼
4. Auto-download from GitHub releases (pinned version), then use downloaded binary
```

---

## Known Issues and Notes

### Preview Not Showing

The preview relies on `TypstFilePreviewer` asking tinymist to export the PDF (`tinymist.exportPdf`) and rendering it via
the vendored PDF.js viewer in JCEF. Possible causes if it doesn't work:

1. **tinymist not found** — resolved by auto-download (triggers automatically); a toolchain-setup failure surfaces a
   balloon (the one case that isn't routed to the console).
2. **JCEF not available** — some IDE configurations (custom JDKs, Linux without the required libraries) don't support
   embedded Chromium. On the 2026.2 line JCEF is a separate bundled plugin (`com.intellij.modules.jcef`); the plugin
   declares a dependency on it so the previewer loads (fixed in 0.4.3).
3. **Export failed** — a compile error comes back as a structured `ExportPdfResult.Failed`
   and is printed to the Typst Output console; check there first.
4. **PDF write not observed** — reload is driven by a VFS listener on the written PDF; the old stdout-marker parsing
   (`"writing to"` / `"compiled"`) was removed with the typst-CLI retirement, so there are no fragile output markers
   left to miss.

### PDF.js Version Pin

The vendored PDF.js viewer is pinned to the 4.10.x line by a Renovate `<5.0.0` ceiling plus a build-gate assertion.
PDF.js 5.x needs Chromium 141+, above what the bundled JCEF ships; the pin lifts once JCEF's Chromium catches up.
Tracking the explicit JCEF-Chromium version and an upgrade doc is planned as Tier 4.3 in `docs/IMPROVEMENTS.md`.

### Gradle Verify Warnings

Warnings from `./gradlew verifyPlugin` originating from `TypstOutputToolWindowFactory.kt`
extending `ToolWindowFactory` are cosmetic — the platform has deprecated/experimentalized several inherited default
methods. The plugin is reported "Compatible" by the Plugin Verifier across the supported build range.
