# Slang [Unofficial] Refined

Slang shader language support for JetBrains IDEs, using [LSP4IJ](https://plugins.jetbrains.com/plugin/23257-lsp4ij) and a local `slangd` installation. A fork of [Slang [Unofficial]](https://plugins.jetbrains.com/plugin/26239-slang-unofficial-) by 16-Bit-Dog.

![Example](./images/example.gif)

## Installation and compatibility

- Supports IntelliJ Platform **2024.2.6–2026.2** (build `242.26775.15` through `262.*`) with LSP4IJ installed. Install or update LSP4IJ to **0.21.0** before installing this plugin.
- Uses shared platform and language APIs, without requiring Java, C++, or a particular IDE product. The verifier matrix covers IDEA Community 2024.2.6, IDEA 2026.2.3, and CLion 2026.2.2; other products in this platform range are not individually verified.
- The same plugin ZIP works on Windows, macOS, and Linux. Install a `slangd` binary for your operating system and CPU. Windows uses `slangd.exe`; macOS and Linux use `slangd`.
- Future IDE branches, including 263 EAP builds, are deliberately excluded until both the plugin and LSP4IJ can be verified against them.

LSP4IJ is published by Red Hat. Its plugin ID, `com.redhat.devtools.lsp4ij`, is a required language-server integration dependency, not an operating-system dependency. A verifier message saying this dependency could not be resolved means a compatible dependency was unavailable to that verification run. It does not mean the plugin requires Red Hat Linux.

Install the plugin ZIP from **Settings → Plugins → Install Plugin from Disk**. Configure the server directory in **Settings → Tools → Slang → General**, or make it available through a local vcpkg installation or `PATH`. Paths containing spaces are supported.

## Features

Completion, diagnostics, syntax and semantic highlighting, hover documentation, go to definition, find usages, rename, document symbols, and configurable inlay hints. Language-server capabilities depend on the installed Slang version. Colors inherit the IDE's Language Defaults and can be customized under **Editor → Color Scheme → Slang**.

Formatting requires `clang-format`. Configure it under **Tools → Slang → Formatting**, or leave its executable field empty to search `PATH`.

Use **Refactor → Rename** on a Slang identifier. When `slangd` supports rename, LSP4IJ handles it directly. Otherwise, the plugin verifies occurrences through go to definition and opens a preview. This fallback includes unsaved edits and supports Undo, but only covers project `.slang` and `.slangh` files under the current server configuration. It cannot check inactive code, external references, or new-name conflicts. Built-in/external declarations, ambiguous targets, and unverified declarations are rejected. If project files change during search or preview, run Rename again.

## Development

Use **JDK 21** and the included Gradle wrapper:

```bash
git clone https://github.com/machinemaslo/slang-jetbrains-extension.git
cd slang-jetbrains-extension
./gradlew test buildPlugin
```

On Windows, use `gradlew.bat`. The build downloads the pinned IDEA Community 2024.2.6 SDK and LSP4IJ 0.21.0. It does not implicitly use `local.properties` or a locally installed IDE. Output is in `build/distributions/`.

To explicitly compile/test against an installed IDE:

```bash
./gradlew test buildPlugin -PlocalIdePath=/path/to/ide
```

Use a JDK capable of reading that IDE's classes (JDK 25 for 2026.2); generated plugin bytecode still targets Java 21. For release builds, omit this override to compile against the oldest tested SDK.

## Verification

```bash
./gradlew verifyPlugin
# Tests plus the verifier:
./gradlew check
```

The verifier checks the packaged ZIP against the pinned IDE matrix and fails on binary incompatibilities, missing dependencies, deprecated APIs, internal APIs, prohibited inheritance/overrides, and invalid plugins. Reports are in `build/reports/pluginVerifier/`. Experimental LSP4IJ feature-extension APIs remain reported: they are the library's customization interface and should be rechecked whenever updating LSP4IJ.

Existing IDE installations can replace downloads for a local verification run:

```bash
./gradlew verifyPlugin -PverificationIdePaths=/path/to/idea,/path/to/clion
```

`--offline` additionally requires IDE/build dependencies and a compatible LSP4IJ ZIP to be cached by the verifier. Check `dependencies.txt` in each report to see the actual LSP4IJ version resolved; the verifier's dependency resolution is separate from Gradle's compile dependency.

CI builds and runs tests on Windows, macOS, and Linux, and runs the verifier matrix on Linux. To include the real-server integration tests locally, set `SLANGD_TEST_EXECUTABLE` to the absolute path of `slangd`:

```bash
SLANGD_TEST_EXECUTABLE=/path/to/slangd ./gradlew test
```

These tests exercise import resolution, hover, built-in navigation, find usages, and rename with unsaved edits and Undo. Without the variable, the three real-server tests are skipped.
