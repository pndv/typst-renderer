<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# typst-renderer Changelog

## [Unreleased]

## [0.1.1] - 2026-05-01

### Fixed
* Action menu showed literal `%compile.action.text` placeholder instead of the resolved bundle string. 
  - Switched to the IntelliJ Platform's automatic key-derivation convention (`action.<id>.text` keys + plugin-level `<resource-bundle>`, no `text=` attribute on actions).
* Manual compile (<kbd>Ctrl+Shift+T</kbd>) and watch toggle now pass `--root <project root>` to typst, matching the auto-watch path. 
  - Fixes "cannot read file outside project root" errors when a `.typ` imports across directories (e.g. `#import "../../template.typ"`).
* PDF preview now refreshes correctly after the user fixes a compilation error. 
  - Previously the preview pane stayed stuck on the error HTML; the cause was `viewerLoaded` not being reset when `loadHTML(errorHtml(...))` replaced the PDF.js viewer page.
* `<br>` tags in preview-pane error messages now render as line breaks instead of literal `&lt;br&gt;` text. The `errorHtml()` helper no longer over-escapes its input; user-supplied substitutions are escaped at the call site via `StringUtil.escapeXmlEntities()`.

### Changed
* Centralised user-visible strings into `TypstBundle` for localisation:
  - Replaced hardcoded messages in `TypstFilePreviewer`, `TypstDownloadService`, `TypstCompileService`, and related classes.
  - Added ~40 new keys to `TypstBundle.properties` covering notifications, download progress text, previewer HTML, and console output.
* Replaced magic strings with named constants: `TYPST_OUTPUT_TOOL_WINDOW_ID` and `TYPST_NOTIFICATION_GROUP_ID` in a new `Constants.kt`.
* Reorganised action definitions:
  - Adopted the IntelliJ Platform `action.<id>.text` / `action.<id>.description` auto-derivation convention.
  - Removed `text=` / `description=` / `resource-bundle=` attributes from `<actions>` block in `plugin.xml`.
* Simplified `TypstParserDefinition` — replaced anonymous inner class with a lambda for creating `PsiParser`.
* Streamlined Gradle `downloadPdfJs` task:
  - Cleaner temporary-file handling.
  - Tightened code comments.

### Tests
* Added `TypstBundleTest`:
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

[Unreleased]: https://github.com/pndv/typst-renderer/compare/0.1.0...HEAD

[0.1.1]: https://github.com/pndv/typst-renderer/compare/0.1.0...0.1.1

[0.1.0]: https://github.com/pndv/typst-renderer/compare/0.0.1...0.1.0

[0.0.1]: https://github.com/pndv/typst-renderer/commits/0.0.1
