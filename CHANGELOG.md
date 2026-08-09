<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# typst-renderer Changelog

## [Unreleased]

### Added

- **Live preview that updates as you type.** The preview pane can now render through tinymist's own preview engine
  instead of exporting a PDF and displaying it. Edits appear in the pane within a few milliseconds of the keystroke — no
  save, no export, and only the changed part of the page is redrawn. A **Live / PDF** toggle in the preview toolbar
  chooses the renderer per editor tab, so several `.typ` files can be open in different modes; the new *Default preview
  mode* setting picks what a freshly opened tab starts in. Live preview is the default. If it cannot start — an old
  tinymist, a refused port — the pane falls back to the PDF renderer on its own and says so in the Typst Output console.
  **Compile** and **Export** write a PDF and report to the console whichever renderer the pane is using, and never
  switch it: a pane in PDF mode reloads, a pane in Live mode carries on rendering live (issue #109).

- **Editors showing the same document share one live preview.** With a main entry pinned, every open chapter compiles
  through that main, so they all display the same document — one render serves them all, started when the first such tab
  opens and stopped when the last one closes. With no main entry pinned, every file gets its own live preview, as do
  `.typ` files outside the project (issue #109).

- **The live preview follows the cursor.** It scrolls to the part of the document you are editing, keeps up as you move
  around, and re-syncs when you switch back to a tab — so the preview shows the passage you are working on rather than
  wherever you last left it. Turn it off with *Scroll the live preview to follow the cursor* in Settings > Tools >
  Typst. Two things to know: editors sharing one live preview scroll together, because the scroll belongs to the shared
  render rather than to a pane; and a file opened for the very first time has its cursor at the start, so the preview
  begins at the top of the document until you move it (issue #109).

- **Click the preview to jump to the source.** Clicking a spot in the live preview takes the editor to the matching
  place in the `.typ` file that produced it, opening the file if it is not already open — useful in a multi-file
  project, where the passage you are looking at often lives in a chapter you do not have in front of you (issue #109).

- **Pin a main entry file for multi-file projects.** In a project where `main.typ` `#include`s a tree of chapter files,
  opening a chapter on its own used to compile it in isolation, so every cross-file reference (`@ch:cases` pointing at a
  label declared elsewhere) reported "label does not exist" — errors that vanished only when `main.typ` was reopened.
  Right-click a `.typ` file and choose **Pin as Typst Main File** (or set it in Settings > Tools > Typst > Project
  Overrides) and the whole project is compiled through that entry instead: cross-references resolve, the editor stops
  reporting phantom errors, and the preview shows the complete document while you edit any chapter. **Unpin Typst Main
  File** reverts to compiling whichever file is focused. The pin is re-applied automatically whenever the language
  server restarts (issue #97).

### Fixed

- **tinymist is no longer re-downloaded after every plugin update.** The managed binary was kept inside the plugin's
  own installation folder, which an update deletes and replaces, so each upgrade silently threw it away and downloaded
  it again. It now lives alongside the IDE's other cached data and survives updates. Upgrading to this version
  downloads it once more, into the new location.
- The preview no longer gets stuck on "Compiling…" after a compile fails. A failed compile now keeps its error pane on
  screen until the next successful compile, instead of being painted over by the waiting splash and hiding the
  diagnostic (issue #98).
- `.typ` files outside the project are compiled as themselves even when a main entry is pinned, rather than being
  redirected to the project's document (issue #99).
- Several reliability problems on the compile path (issue #101):
  - Compiling no longer fails with "failed to persist temporary file: Access is denied" when several editor tabs refresh
    at once; simultaneous compiles of the same document are now merged into one.
  - Compile requests are no longer sent to the wrong language server, which reported "file not found" for files that
    exist — most visibly after a file was created or replaced outside the IDE.
  - Applying a project setting no longer leaves out-of-project `.typ` files without a language server until their editor
    is closed and reopened.
  - **Compile** now saves modified files first, so it no longer produces a PDF of the previously saved text while
    reporting success.
  - A language server that stops unexpectedly is restarted automatically, instead of leaving the preview waiting
    indefinitely.

### Changed

- **Saving no longer writes a PDF while the preview is in Live mode.** Previously the preview refreshed by exporting, so
  every save left an updated PDF in the export directory (`target/` by default) as a side effect. A live preview renders
  without producing a file, so with the new default the PDF on disk is written only when you ask for one — **Compile**
  (<kbd>Ctrl+Shift+T</kbd>) or **Export**. If you relied on save-writes-a-PDF (a build script watching
  `target/`, an external viewer open on the file), switch the preview toolbar toggle to **PDF**, or set *Default preview
  mode* to *PDF* to restore the previous behaviour everywhere.
- **The preview follows your typing rather than your saves.** In Live mode the pane no longer waits for
  <kbd>Ctrl+S</kbd>. It can be put back on a save cadence with the *Update live preview on every keystroke* setting
  without leaving Live mode.
- **A compile error leaves the Live preview showing the last good version, not an error page.** The live renderer keeps
  the most recent successful render, so a document that has never compiled shows an empty pane instead of a message.
  The errors themselves are unaffected — they appear in the editor gutter and in the **Typst Output** console as
  before. Switch the pane to **PDF** for the previous behaviour, where a failed compile replaces the preview with an
  error page.
- **Jumping from the preview to the source does not move the keyboard focus** when the target file is already open in
  front of you: the line is highlighted, but the cursor stays where it was and typing still goes to the preview. Click
  into the editor to carry on. Jumping to a file that is *not* already open behaves normally, placing the cursor and
  moving focus with it. This is the IDE's handling of the language server's request rather than a limit of Typst
  itself, and it is being tracked for a follow-up.
- The bundled tinymist language server is now pinned to **v0.15.2** (from v0.14.18). Users who already have an
  auto-downloaded tinymist keep their existing binary — the new pin applies to fresh downloads.

## [0.4.3] - 2026-07-21

### Fixed

- The live PDF preview no longer crashes with `NoClassDefFoundError: com/intellij/ui/jcef/JBCefApp`
  on the 2026.2 line. From build 262 the platform ships JCEF as a separate bundled plugin (`com.intellij.modules.jcef`)
  that is no longer on every plugin's classpath by default; the plugin now declares a dependency on it so the embedded
  browser previewer loads.
- `.typ` files opened from **outside the project** (a résumé in the home directory, a one-off letter on another drive)
  now get a language server, so compile and the live preview work for them. The platform only lets a plugin start an LSP
  for files inside the project's content roots; such files now get their own tinymist client rooted at the file's
  folder, started explicitly on editor open and swept up at project startup. The exported PDF lands in the configured
  export directory (default `target/`) next to the file (issue #92).

### Changed

- The minimum supported build is now `262` (2026.2); the supported range is `262.*`. The 2026.1 (`261`)
  line is no longer supported — the JCEF split that broke the preview only exists from 262, and pinning the baseline
  there keeps the plugin descriptor honest.

### Infrastructure

- Six extension-dependent integration tests are now gated behind a plugin-loaded check. The minimal 2026.2 test
  framework omits `intellij.libraries.lucene.common`, which excludes the plugin from plugin-set resolution so its
  extensions never register; the affected tests skip with a logged reason on that platform and run in full again once a
  complete test framework ships. The real IDE is unaffected — the JetBrains Plugin Verifier reports the plugin
  compatible with `IU-262.8665.258`.

## [0.4.2] - 2026-07-13

A maintenance release: auto-format now works on prose-heavy documents, and the plugin is compatible with the 2026.2
line following the migration to the platform's `LspClient` integration API.

### Fixed

- **Reformat Code** no longer looks like a dead action on prose-heavy documents. tinymist's `formatterProseWrap`
  option is now enabled, so over-long prose lines reflow to typstyle's print width instead of returning zero edits
  (issue #88).

### Changed

- Migrated the LSP integration from the platform's `LspServer` API to the newer `LspClient` /
  `LspIntegrationProvider` API. This lifts the 0.4.1 compatibility pin: the supported build range is now
  `261`–`262.*`, so the plugin runs on the 2026.2 line.
- Debug logging of tinymist's initialisation options now records the full options map for easier traceability.

### Infrastructure

- The GitHub workflows now derive the JDK version from `gradle.properties` instead of hard-coding it.
- Gradle updated to 9.6.1 and the IntelliJ Platform Gradle plugin to 2.17.0, alongside routine dependency bumps.

## [0.4.1] - 2026-06-19

A maintenance release: bug fixes, responsiveness improvements, and removal of the unwired typst CLI code that
0.4.0 kept in the tree as a revert hatch.

### Fixed

- Console output produced **before** the Typst Output tool window is opened is no longer dropped. Such messages are
  buffered and flushed in order once the console becomes available, so a freshly-opened project keeps its first
  compile's diagnostics.
- **Rename** (`prepareRename`/`rename`) no longer blocks the EDT on the tinymist round-trip. The requests run off the
  EDT under a cancellable modal progress, and cancelling the dialogue aborts quietly instead of being reported as a
  failure.
- Exceptions thrown during a PDF export are now surfaced to the preview pane instead of escaping unhandled;
  `ProcessCanceledException` is always re-thrown.
- The tinymist "already downloading" guard is now atomic, so two near-simultaneous triggers can no longer queue
  duplicate downloads.
- After a successful tinymist auto-download, the language server is (re)started through the platform rather than via a
  stale starter captured from the original file-open, so the server reliably comes up post-download.
- The PDF.js preview server now returns a proper **404** for an unknown previewer resource, instead of a 200 with an
  empty body.
- The Typst project root used for console hyperlink resolution is now read fresh on each use, so a changed root takes
  effect without restarting.
- Windows executable detection no longer relies on `File.canExecute()`, which could misclassify non-executable files.

### Changed

- Readiness polling for the preview now uses an exponential backoff with a capped delay, balancing responsiveness
  against polling cost.
- PDF reload compares paths with `FileUtil.pathsEqual()` for cross-platform, case-insensitive matching.
- `exportJob`, `reloadJob`, and `outputPdf` are now `@Volatile` for safer cross-thread access.
- Declared an explicit upper compatibility bound (`until-build = 261.*`). The LSP integration calls
  `LspServer.sendRequestSync`, which 2026.2 relocates to the `LspClient` super-interface; until that migration lands
  the plugin is pinned to the 2026.1 line to avoid a runtime `NoSuchMethodError`.

### Removed

- The unwired typst CLI code retained as a revert hatch in 0.4.0: `TypstCommandBuilder`, `TypstDownloadService`, and
  the corresponding `TypstCommandBuilderTest` and `ArchiveExtractionTest` suites.
- The unused `<applicationService>` entry from `plugin.xml`.

## [0.4.0] - 2026-06-10

The plugin now compiles entirely through the bundled **tinymist** language server — the standalone `typst`
command-line binary is no longer invoked. This release *stops using* the typst CLI; the CLI-backed code remains
in the tree, unwired, as a revert hatch and will be removed in a later release.

### Added

- Per-project **export directory** setting (`typstExportPath`, default `target`) on the project-scoped settings page.
  The plugin wraps it as tinymist's `$root/<dir>/$dir/$name` output template, so the source tree is mirrored under the
  chosen directory and every file keeps a unique output path.
- `TinymistCommands` — a typed wrapper over tinymist's `workspace/executeCommand` surface (`tinymist.exportPdf`,
  `tinymist.pinMain`, `tinymist.getServerInfo`), with a structured `ExportPdfResult` (`Exported` / `Failed` /
  `Unavailable`) that carries tinymist's formatted compile diagnostic instead of scraping stderr.

### Changed

- **Manual Compile** now triggers a tinymist `tinymist.exportPdf` export rather than spawning a `typst compile`
  subprocess. The PDF is written to the configured export directory and the path is taken from tinymist's response.
- **PDF preview** now drives compilation itself: opening a preview, or saving the previewed file, runs a debounced
  `tinymist.exportPdf` and reloads the panel from the returned path — there is no `typst watch` subprocess. A successful
  render hot-swaps the PDF in place (preserving scroll); the panel falls back to a full reload only when the viewer page
  is not currently shown.
- Preview startup is gated on the language server being ready: while tinymist is still starting, the panel shows a
  waiting page and polls for readiness rather than reporting spurious "no PDF returned" errors. Transient export
  failures while the server is up are retried briefly before surfacing a hard error.
- The compile diagnostic shown in the **Typst Output** console is now tinymist's own formatted `error: …` text
  (multi-line, with file-location links preserved), replacing the previous stderr parsing.
- The tinymist binary is pinned to **v0.14.18** as the download target, which includes the workspace-root output-path
  fix required for the default export layout.

### Removed

- The explicit **Watch** action — both the editor right-click "Watch Typst File" entry and the Watch toggle on the Typst
  Output toolbar. An open preview now recompiles automatically on every save and so serves as the live watch; the
  standalone "watch and write to the project without an open preview" workflow is dropped for the time being.
- The **typst CLI settings UI** — the "Compilation (Typst CLI)" group (binary-path field, "Download typst" button,
  status row, and auto-compile-on-save checkbox) has been removed from the Typst settings page, since compilation now
  goes through tinymist. The underlying settings keys and download service are retained internally for now.

### Tests

- `TinymistCommandsTest` — covers the export-path extraction, error formatting, and Rust-debug unescaping helpers.
- `TypstFilePreviewerDecisionsTest` — covers the preview pipeline's readiness-poll, transient-retry, and
  hot-swap-vs-full-navigation decisions, plus the `ExportPdfResult` contract.
- `TinymistLspServerDescriptorTest` — covers the export-directory → output-path template construction.

### Infrastructure

- PDF.js held at the 4.10.x line with a Renovate `<5.0.0` ceiling and a build-gate assertion, so an automated bump can't
  silently move past what the bundled JCEF Chromium supports.

## [0.3.0] - 2026-05-27

### Added

- Per-project font path setting (`typstFontPath`) in `TypstProjectSettingsState` — a folder-picker row in the new project-scoped settings page lets users point the compiler and LSP at a project-local `fonts/` directory.
- `resolveTypstFontPath(project)` resolver helper alongside the existing `resolveTypstRoot` — returns the configured path if non-blank and the directory exists, otherwise `null`.
- Font path wired into all three typst CLI invocations (`TypstCompileService`, `TypstWatchService`, `TypstFilePreviewer`) and into the tinymist LSP descriptor via `--font-path`.
- `TypstProjectSettingsConfigurable` — dedicated project-scoped settings page under **Settings → Tools → Typst → Project Overrides**, surfacing both the project root and font path overrides.
- Applying a changed project root or font path via settings now bounces the tinymist LSP so the new argv takes effect immediately without an IDE restart.

### Changed

- `TypstCommandBuilder.buildCompileCommand` and `buildWatchCommand` now return `GeneralCommandLine` instead of `List<String>`, constructing the full command — including charset and working directory — in one place. Root and font path are resolved once per call, so `--root` and `withWorkingDirectory` always agree.
- `TypstRootResolver` renamed to `TypstParamResolver`; the file now owns both `resolveTypstRoot` and `resolveTypstFontPath`, each with a `project.isDisposed` guard.
- `TypstConsoleFilter` refactored to a stateless per-line approach — the `LineKind` state machine is replaced by two anchored regexes, with the rich-format anchor (`┌─`) tried before the compact format to prevent false positives on box-drawing characters.

### Fixed

- `resolveTypstRoot(Project)` and `resolveTypstFontPath(Project)` now check `project.isDisposed` before calling `project.getService()`, preventing exceptions when the project is torn down during shutdown.
- `TypstProjectSettingsConfigurable.apply()`: LSP restart baselines are now updated inside the `finally` block of the pooled restart thread. Previously, updating them synchronously on the EDT before the restart ran meant a rapid second Apply() saw no diff and silently skipped its own restart.

### Tests

- `TypstCommandBuilderTest` updated for the `GeneralCommandLine` return type; the shared `@Before` mock setup replaced with explicit per-test `whenever(...)` stubs for correct isolation; `GeneralCommandLine.argv()` extension function added for assertion clarity.

### Infrastructure

- Sandbox builds switched from `intellijIdeaUltimate` to `intellijIdea`.
- Mockito-Kotlin added as a test dependency.
- `downloadSources = true` added for IntelliJ Platform artefacts.

## [0.2.0] - 2026-05-21

### Added

- Compilation errors now route to the Typst Output Console instead of notification bubbles.
- Recompile, watch-toggle, scroll-to-bottom, and clear actions added directly to the Output Console toolbar.
- `TypstConsoleHolder` introduced to hold a reference to the console window and place toolbar icons on the left per IntelliJ platform conventions.
- `TypstLastCompiledTracker` to track the most recently compiled file across sessions.
- Project-scoped settings (`TypstProjectSettingsState`) persisted to `TypstProjectSettings.xml`.
- `TypstRootResolver` to determine the Typst project root via a priority chain (explicit project override → auto-detection), ensuring the correct `--root` argument is passed to the CLI.

### Changed

- Typst compilation moved off the Event Dispatch Thread onto a pooled thread, preventing "Synchronous execution on EDT" exceptions.
- Debug logging added across `TypstWatchService` and `TinymistLspServerDescriptor` for improved traceability.
- PDF.js vendored assets updated to v5.

### Tests

- `TypstLastCompiledTrackerTest` — last-compiled-file tracking behaviour.
- `TypstRootResolverTest` — root resolution priority order and fallback logic.
- `TypstSettingsStateTest` — round-trip persistence, accessor/state-field alignment, and mutation-in-place behaviour.
- CI step added to verify vendored PDF.js assets are present, catching accidental `.gitignore` changes early.

### Infrastructure

- Switched from Dependabot to Renovate for Gradle and library dependency management.
- Gradle wrapper updated to 9.5.1.
- CodeQL and Qodana workflow fixes.

## [0.1.2] - 2026-05-07

### Added

- CodeQL workflow for automated security scanning.
- Reusable GitHub Actions for Java and Gradle setup across workflows.

### Fixed

- Fixed an issue where user settings were not correctly persisted after a settings reload. Thanks to @ixmoyren for the report and fix!
- Improved settings UI reliability by refactoring the configuration page to use modern IntelliJ UI DSL binding.
- Formatting in ARCHITECTURE.md.

### Changed

- Localised all strings on the Typst Settings configuration page using `TypstBundle`.
- Refactored `TypstSettingsConfigurable` to bind UI components directly to the settings state, eliminating redundant local variables.

## [0.1.1] - 2026-05-01

### Fixed

- Action menu showed literal `%compile.action.text` placeholder instead of the resolved bundle string. 
  - Switched to the IntelliJ Platform's automatic key-derivation convention (`action.<id>.text` keys + plugin-level `<resource-bundle>`, no `text=` attribute on actions).
- Manual compile (<kbd>Ctrl+Shift+T</kbd>) and watch toggle now pass `--root <project root>` to typst, matching the auto-watch path. 
  - Fixes "cannot read file outside project root" errors when a `.typ` imports across directories (e.g. `#import "../../template.typ"`).
- PDF preview now refreshes correctly after the user fixes a compilation error. 
  - Previously the preview pane stayed stuck on the error HTML; the cause was `viewerLoaded` not being reset when `loadHTML(errorHtml(...))` replaced the PDF.js viewer page.
- `<br>` tags in preview-pane error messages now render as line breaks instead of literal `&lt;br&gt;` text. The `errorHtml()` helper no longer over-escapes its input; user-supplied substitutions are escaped at the call site via `StringUtil.escapeXmlEntities()`.

### Changed

- Centralised user-visible strings into `TypstBundle` for localisation:
  - Replaced hardcoded messages in `TypstFilePreviewer`, `TypstDownloadService`, `TypstCompileService`, and related classes.
  - Added ~40 new keys to `TypstBundle.properties` covering notifications, download progress text, previewer HTML, and console output.
- Replaced magic strings with named constants: `TYPST_OUTPUT_TOOL_WINDOW_ID` and `TYPST_NOTIFICATION_GROUP_ID` in a new `Constants.kt`.
- Reorganised action definitions:
  - Adopted the IntelliJ Platform `action.<id>.text` / `action.<id>.description` auto-derivation convention.
  - Removed `text=` / `description=` / `resource-bundle=` attributes from `<actions>` block in `plugin.xml`.
- Simplified `TypstParserDefinition` — replaced anonymous inner class with a lambda for creating `PsiParser`.
- Streamlined Gradle `downloadPdfJs` task:
  - Cleaner temporary-file handling.
  - Tightened code comments.

### Tests

- Added `TypstBundleTest`:
  - Validates that every bundle key referenced from Kotlin resolves at runtime.
  - Verifies action registration in the IntelliJ ActionManager.

## [0.1.0] - 2026-04-28

### Added

- PDF preview now remembers scroll position across recompiles, powered by a vendored PDF.js viewer served over IntelliJ's built-in Netty HTTP server
- Gradle task `downloadPdfJs` that vendors a pinned PDF.js distribution into the plugin resources; the resulting assets are committed to git so casual contributors don't need network access to build
- `platforms.json` declarative platform matrix for `tinymist` and `typst` downloads
  - Per-tool base URLs and per-platform asset/archive entries
  - Authoritative `supported` set computed as the intersection of both tools' platforms
- `PlatformConfig` API to parse `platforms.json`, normalise host OS/arch, and gate the auto-download flow on the supported intersection
- `TinymistDownloadService.atomicMove` utility for robust file moves with copy-and-delete fallback when rename isn't possible (e.g. cross-filesystem)
- Plugin screenshots in the README
- Test seam: `PlatformConfig.tinymistBaseUrlOverride` lets tests point the download service at a `MockWebServer` for hermetic offline tests

### Changed

- Replaced hardcoded GitHub URLs in `TinymistDownloadService` and `TypstDownloadService` with dynamic resolution from the `platforms.json` matrix
- Refactored `TinymistManager.resolveTinymistPath` / `resolveTypstPath` onto a single `resolveBinaryPath` helper that centralises the 3-stage lookup (user-configured path → system `PATH` → downloaded binary)
- Switched archive-extraction methods (`extractTypstBinary`, `extractFromTarXz`, `extractFromZip`) from `private` to `internal` to enable focused tests
- Switched the test framework back to JUnit 4 to match the IntelliJ Platform plugin template's convention
- `TinymistDownloadService.downloadInBackground` now uses `Task.Backgroundable.queue()` instead of `ProgressManager.getInstance().run(task)` to avoid `invokeAndWait` from inside read-action contexts (the LSP `fileOpened` callback path)
- `PlatformConfig` JSON loading rewritten on top of `jacksonObjectMapper()` + typed DTOs — eliminates manual map walking and unchecked casts

### Fixed

- README plugin ID and JetBrains Marketplace links corrected
- Cross-platform handling of `BinaryResolutionTest` (now passes on Windows, macOS, and Linux)
- Detailed `unsupportedPlatformMessage` enumerating which platforms are actually supported by both tools

### Tests

- `PlatformConfigTest` — OS/arch normalisation, `platforms.json` schema invariants, intersection logic
- `DownloadUrlResolutionTest` — happy path, 404, network errors, server unavailable, 5xx; runs against a local `MockWebServer`
- `TinymistManagerTest` — platform directory enumeration and binary presence checks
- `ArchiveExtractionTest` — `.tar.xz` (Unix) and `.zip` (Windows) extraction; happy path, invalid archive, missing-binary, temp-dir cleanup; archives are built dynamically in temp dirs so the suite is independent of any vendored binaries
- `AtomicMoveTest` — target overwrite, cross-directory, temp-to-target
- `BinaryResolutionTest` — priority order of the three lookup stages, cross-platform path handling
- `TinymistDownloadThreadingTest` — regression test that exercises `downloadInBackground` from inside a read action against a `MockWebServer`, guarding the `Task.Backgroundable.queue()` fix

## [0.0.1] - 2026-04-15

### Added

- Typst (`.typ`) file type recognition with dedicated file icon
- Syntax highlighting via token boundaries
- Comment toggling with <kbd>Ctrl+/</kbd> and <kbd>Ctrl+Shift+/</kbd>
- LSP integration via [tinymist](https://github.com/Myriad-Dreamin/tinymist) language server:
  - Code completion, diagnostics, hover documentation, go-to-definition, and formatting
  - Rename support via `textDocument/rename`
- Split editor with live PDF preview powered by JCEF (Chromium Embedded)
- `typst watch` integration — preview auto-reloads on every file save
- Single-shot compilation action (<kbd>Ctrl+Shift+T</kbd>), available in editor and project view context menus
- Watch mode toggle action for continuous background compilation
- Auto-download of `tinymist` and `typst` CLI binaries from GitHub releases if not found on PATH
- Binary resolution from user-configured paths, system PATH, well-known install directories (Cargo, Homebrew, Scoop, Nix, Chocolatey), and plugin data directory
- Settings page under <kbd>Settings</kbd> > <kbd>Tools</kbd> > <kbd>Typst</kbd> for configuring binary paths
- "Typst Output" tool window for viewing compilation output

[Unreleased]: https://github.com/pndv/typst-renderer/compare/0.4.3...HEAD
[0.4.3]: https://github.com/pndv/typst-renderer/compare/0.4.2...0.4.3
[0.4.2]: https://github.com/pndv/typst-renderer/compare/0.4.1...0.4.2
[0.4.1]: https://github.com/pndv/typst-renderer/compare/0.4.0...0.4.1
[0.4.0]: https://github.com/pndv/typst-renderer/compare/0.3.0...0.4.0
[0.3.0]: https://github.com/pndv/typst-renderer/compare/0.2.0...0.3.0
[0.2.0]: https://github.com/pndv/typst-renderer/compare/0.1.2...0.2.0
[0.1.2]: https://github.com/pndv/typst-renderer/compare/0.1.1...0.1.2
[0.1.1]: https://github.com/pndv/typst-renderer/compare/0.1.0...0.1.1
[0.1.0]: https://github.com/pndv/typst-renderer/compare/0.0.1...0.1.0
[0.0.1]: https://github.com/pndv/typst-renderer/commits/0.0.1
