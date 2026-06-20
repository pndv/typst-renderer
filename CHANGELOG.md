<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# typst-renderer Changelog

## [Unreleased]

## [0.4.1] - 2026-06-19

A maintenance release: bug fixes, responsiveness improvements, and removal of the unwired typst CLI code that
0.4.0 kept in the tree as a revert hatch.

### Fixed

- Console output produced **before** the Typst Output tool window is opened is no longer dropped. Such messages are
  buffered and flushed in order once the console becomes available, so a freshly-opened project keeps its first
  compile's diagnostics.
- **Rename** (`prepareRename`/`rename`) no longer blocks the EDT on the tinymist round-trip. The requests run off the
  EDT under a cancellable modal progress, and cancelling the dialog aborts quietly instead of being reported as a
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
- `downloadSources = true` added for IntelliJ Platform artifacts.

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

[Unreleased]: https://github.com/pndv/typst-renderer/compare/0.4.0...HEAD
[0.4.0]: https://github.com/pndv/typst-renderer/compare/0.3.0...0.4.0
[0.3.0]: https://github.com/pndv/typst-renderer/compare/0.2.0...0.3.0
[0.2.0]: https://github.com/pndv/typst-renderer/compare/0.1.2...0.2.0
[0.1.2]: https://github.com/pndv/typst-renderer/compare/0.1.1...0.1.2
[0.1.1]: https://github.com/pndv/typst-renderer/compare/0.1.0...0.1.1
[0.1.0]: https://github.com/pndv/typst-renderer/compare/0.0.1...0.1.0
[0.0.1]: https://github.com/pndv/typst-renderer/commits/0.0.1
