# typst-renderer

![Build](https://github.com/pndv/typst-renderer/workflows/Build/badge.svg)
[![Version](https://img.shields.io/jetbrains/plugin/v/31308.svg)](https://plugins.jetbrains.com/plugin/31308-typst-renderer)
[![Downloads](https://img.shields.io/jetbrains/plugin/d/31308.svg)](https://plugins.jetbrains.com/plugin/31308-typst-renderer)

<!-- Plugin description -->
**Typst** support for IntelliJ-based IDEs — edit, preview, and compile [Typst](https://typst.app) documents without leaving your IDE.

## Features

- **Syntax highlighting** and **comment toggling** (<kbd>Ctrl+/</kbd> / <kbd>Ctrl+Shift+/</kbd>) for `.typ` files
- **Full LSP integration** via [tinymist](https://github.com/Myriad-Dreamin/tinymist):
  - Code completion, real-time diagnostics, hover documentation, go-to-definition, formatting, and rename refactoring
- **Live PDF preview** — a split editor shows the compiled PDF alongside your source, auto-refreshing on every save
- **Compile action** (<kbd>Ctrl+Shift+T</kbd>) for single-shot compilation, and a **Watch mode** toggle for continuous background compilation
- **Auto-download** of `tinymist` and `typst` CLI binaries from GitHub if they are not already installed
- **Settings page** under <kbd>Settings</kbd> > <kbd>Tools</kbd> > <kbd>Typst</kbd> to configure custom binary paths
- **Typst Output** tool window for viewing compilation logs

## Requirements

No manual installation of external tools is required — the plugin will automatically download `tinymist` and `typst` on first use. If you already have them installed (via Cargo, Homebrew, Scoop, etc.), they will be detected automatically.
<!-- Plugin description end -->

## Installation

- Using the IDE built-in plugin system:

  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>Marketplace</kbd> > <kbd>Search for "typst-renderer"</kbd> >
  <kbd>Install</kbd>

- Using JetBrains Marketplace:

  Go to [JetBrains Marketplace](https://plugins.jetbrains.com/plugin/31308-typst-renderer) and install it by clicking
  the <kbd>Install to ...</kbd> button in case your IDE is running.

  You can also download the [latest release](https://plugins.jetbrains.com/plugin/31308-typst-renderer/versions) from
  JetBrains Marketplace and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>

- Manually:

  Download the [latest release](https://github.com/pndv/typst-renderer/releases/latest) and install it manually using
  <kbd>Settings/Preferences</kbd> > <kbd>Plugins</kbd> > <kbd>⚙️</kbd> > <kbd>Install plugin from disk...</kbd>


---
Plugin based on the [IntelliJ Platform Plugin Template][template].

[template]: https://github.com/JetBrains/intellij-platform-plugin-template
[docs:plugin-description]: https://plugins.jetbrains.com/docs/intellij/plugin-user-experience.html#plugin-description-and-presentation
