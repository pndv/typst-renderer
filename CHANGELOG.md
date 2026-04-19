<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# typst-renderer Changelog

## [Unreleased]

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

[Unreleased]: https://github.com/pndv/typst-renderer/compare/0.0.1...HEAD

[0.0.1]: https://github.com/pndv/typst-renderer/commits/0.0.1
