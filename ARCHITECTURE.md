# Typst Renderer Plugin — Architecture

## What is Tinymist and Why Is It Needed?

**Tinymist** is a **Language Server** for the **Typst** markup language. It implements the **Language Server Protocol (LSP)** — a standardized JSON-RPC protocol (created by Microsoft for VS Code, now universal) that lets any editor communicate with a language-specific backend to get smart features.

### What the Language Server Protocol Does

```
┌──────────────────┐         JSON-RPC (stdio)          ┌──────────────────┐
│   IDE / Editor   │  ──── LSP Messages ──────────────> │  Language Server  │
│  (IntelliJ IDEA) │ <──── LSP Responses ────────────── │   (tinymist)     │
└──────────────────┘                                    └──────────────────┘
```

The IDE sends **requests** like:
- `textDocument/completion` — "What completions are available at line 5, col 12?"
- `textDocument/hover` — "What's the type/docs for the symbol under the cursor?"
- `textDocument/definition` — "Go to definition of this function"
- `textDocument/diagnostics` — "Are there any errors in this file?"
- `textDocument/formatting` — "Format this document"

The language server **responds** with structured data (completions, diagnostics, locations, etc.).

### Why Tinymist Specifically?

Typst is a relatively new language (alternative to LaTeX). Tinymist is the **official/community LSP server** for Typst (GitHub: `Myriad-Dreamin/tinymist`). It provides:

- **Code completion** — function names, parameters, packages
- **Diagnostics** — real-time error and warning highlighting
- **Hover documentation** — inline docs for functions/types
- **Go to definition** — navigate to symbol declarations
- **Semantic tokens** — rich syntax highlighting beyond regex-based patterns
- **Document symbols** — outline view, breadcrumbs
- **Formatting** — auto-format Typst code

**Without tinymist**, the plugin would only have basic text editing with no intelligence — no autocomplete, no error checking, no navigation. The LSP integration is what makes it a *smart* editor rather than just a text editor with a file icon.

### How It Integrates in This Plugin

1. When a `.typ` file is opened, `TinymistLspServerSupportProvider` is triggered
2. It finds the `tinymist` binary (user path > system PATH > Homebrew/Cargo > downloaded copy)
3. It launches `tinymist lsp` as a subprocess
4. IntelliJ's built-in LSP client handles the JSON-RPC communication over stdio
5. The IDE automatically gets completions, diagnostics, etc.

**Note:** **tinymist** provides code intelligence. **typst** CLI (separate binary) handles compilation to PDF. The plugin uses both.

---

## Skills Needed for Plugin Development

| Skill Area                         | Why                                                                                                               |
|------------------------------------|-------------------------------------------------------------------------------------------------------------------|
| **Kotlin**                         | All source code is in Kotlin — the standard language for modern IntelliJ plugins                                  |
| **IntelliJ Platform SDK**          | Plugin extension points, services, actions, file editors, tool windows, notifications                             |
| **LSP (Language Server Protocol)** | Understanding the protocol to integrate tinymist — though IntelliJ's LSP module handles most of the heavy lifting |
| **JCEF (Chromium Embedded)**       | The PDF preview uses an embedded Chromium browser (`JBCefBrowser`)                                                |
| **Process Management**             | Spawning and managing `typst watch` and `tinymist lsp` as subprocesses                                            |
| **Gradle**                         | Build system with IntelliJ Platform Gradle Plugin for packaging/publishing                                        |
| **Typst language basics**          | Understanding what the end-user needs from the editor                                                             |

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
│  │  │              File Type & Language Layer                  │  │  │
│  │  │                                                         │  │  │
│  │  │  TypstLanguage ─── TypstFileType ─── TypstIcons         │  │  │
│  │  │  (defines "Typst")  (maps .typ)     (file icon)         │  │  │
│  │  └─────────────────────────────────────────────────────────┘  │  │
│  │                          │                                    │  │
│  │          ┌───────────────┼───────────────┐                    │  │
│  │          ▼               ▼               ▼                    │  │
│  │  ┌──────────────┐ ┌──────────────┐ ┌──────────────────────┐  │  │
│  │  │  LSP Layer   │ │ Editor Layer │ │  Compilation Layer   │  │  │
│  │  │              │ │              │ │                      │  │  │
│  │  │ SupportProv. │ │ SplitEditor  │ │ CompileService       │  │  │
│  │  │ Descriptor   │ │ Provider     │ │ WatchService         │  │  │
│  │  │ Manager      │ │  ┌────────┐  │ │ CompileAction        │  │  │
│  │  │ DownloadSvc  │ │  │Preview │  │ │ WatchAction          │  │  │
│  │  └──────┬───────┘ │  │FileEd. │  │ └──────────┬───────────┘  │  │
│  │         │         │  │(JCEF)  │  │            │              │  │
│  │         │         │  └───┬────┘  │            │              │  │
│  │         │         └──────┼───────┘            │              │  │
│  │         │                │                    │              │  │
│  │  ┌──────────────┐ ┌─────────────┐ ┌───────────────────────┐  │  │
│  │  │ Settings     │ │ Tool Window │ │ Notifications         │  │  │
│  │  │ State +      │ │ (Output     │ │ (Balloon alerts)      │  │  │
│  │  │ Configurable │ │  Console)   │ │                       │  │  │
│  │  └──────────────┘ └─────────────┘ └───────────────────────┘  │  │
│  └───────────────────────────────────────────────────────────────┘  │
│                          │                    │                      │
└──────────────────────────┼────────────────────┼─────────────────────┘
                           │                    │
              ┌────────────┼────────────────────┼──────────┐
              │     External Processes (subprocess via stdio)│
              │            │                    │          │
              │   ┌────────▼─────────┐  ┌──────▼────────┐ │
              │   │   tinymist lsp   │  │  typst watch  │ │
              │   │                  │  │  typst compile │ │
              │   │  Code intel:     │  │                │ │
              │   │  - completions   │  │  Compiles .typ │ │
              │   │  - diagnostics   │  │  to .pdf       │ │
              │   │  - hover docs    │  │                │ │
              │   │  - go-to-def     │  │  Watch mode:   │ │
              │   │  - formatting    │  │  auto-recompile│ │
              │   │                  │  │  on file change│ │
              │   └──────────────────┘  └───────────────┘ │
              └────────────────────────────────────────────┘
```

---

## Data Flow — Opening a `.typ` File

```
User opens file.typ
       │
       ├──► TypstFileType recognizes .typ extension
       │
       ├──► TypstSplitEditorProvider creates split view:
       │       ├── Left:  Standard text editor (with LSP features)
       │       └── Right: TypstPreviewFileEditor
       │                    │
       │                    ├── Launches: typst watch file.typ /tmp/output.pdf
       │                    ├── Listens for "compiled"/"writing to" in stdout
       │                    ├── Reloads PDF in JCEF browser on each compile
       │                    └── Also listens to VFS for PDF file changes
       │
       └──► TinymistLspServerSupportProvider.fileOpened()
               │
               ├── TinymistManager.resolveTinymistPath()
               │     (settings → PATH → well-known dirs → downloaded binary)
               │
               ├── If found: starts "tinymist lsp" subprocess
               │     IntelliJ LSP client ←──JSON-RPC──► tinymist
               │
               └── If not found: TinymistDownloadService downloads from GitHub
                     then starts the LSP server
```

---

## Project Structure

```
src/main/kotlin/com/github/pndv/typstrenderer/
├── TypstBundle.kt                          — Resource bundle (i18n strings)
├── language/
│   ├── TypstLanguage.kt                    — Language definition ("Typst")
│   ├── TypstFileType.kt                    — File type for .typ files
│   └── TypstIcons.kt                       — File icon (typst.svg)
├── lsp/
│   ├── TinymistLspServerSupportProvider.kt — LSP entry point (triggered on file open)
│   ├── TinymistLspServerDescriptor.kt      — LSP server command config ("tinymist lsp")
│   ├── TinymistManager.kt                  — Binary resolution (PATH, well-known dirs, download)
│   ├── TinymistDownloadService.kt          — Auto-download tinymist from GitHub
│   └── TypstDownloadService.kt             — Auto-download typst CLI from GitHub
├── editor/
│   ├── TypstSplitEditorProvider.kt         — Split editor (code + preview)
│   └── TypstPreviewFileEditor.kt           — Live PDF preview via JCEF + typst watch
├── compile/
│   ├── TypstCompileService.kt              — Single-shot compilation
│   └── TypstWatchService.kt                — Continuous watch mode
├── actions/
│   ├── TypstCompileAction.kt               — Compile menu action (Ctrl+Shift+T)
│   └── TypstWatchAction.kt                 — Watch toggle action
├── settings/
│   ├── TypstSettingsState.kt               — Persistent settings (paths, flags)
│   └── TypstSettingsConfigurable.kt        — Settings UI (Tools > Typst)
└── toolWindow/
    └── TypstOutputToolWindowFactory.kt     — Output console (bottom panel)
```

---

## Binary Resolution Strategy

Both `tinymist` and `typst` binaries are resolved using the same priority order:

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
4. Auto-download from GitHub releases, then use downloaded binary
```

---

## Known Issues and Notes

### Preview Not Showing

The preview relies on `TypstPreviewFileEditor` running `typst watch <input> <output.pdf>` and displaying the result in JCEF. Possible causes if it doesn't work:

1. **Typst CLI not found** — resolved by auto-download (triggers automatically)
2. **JCEF not supported** — some IDE configurations (custom JDKs, Linux without required libraries) don't support embedded Chromium
3. **`typst watch` fails silently** — check IDE logs (`Help > Show Log in Finder`)
4. **PDF reload detection misses** — the code watches for "writing to" or "compiled" in stdout; if the Typst CLI version uses different messages, reload won't fire
5. **VFS doesn't detect temp file changes** — PDF is written to `/tmp/typst-preview/`, outside the project

### Gradle Verify Warnings

All warnings from `./gradlew verifyPlugin` originate from `TypstOutputToolWindowFactory.kt` extending `ToolWindowFactory`. The IntelliJ Platform has deprecated/experimentalized several inherited default methods:

- **Deprecated:** `isApplicable(Project)`, `isDoNotActivateOnStart()`
- **Experimental:** `manage()`, `getAnchor()`, `getIcon()`

These are cosmetic — the plugin is "Compatible" across all tested IDE versions and the warnings don't prevent publishing.
