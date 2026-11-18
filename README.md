### This is a Refined Unofficial Slang LSP

---

### Example
https://github.com/user-attachments/assets/f1ee4a74-58b1-4589-bf29-3fdd20842cc7

---

### Base JetBrains plugin distribution page
https://plugins.jetbrains.com/plugin/26239-slang-unofficial-
This plugin was forked from that one, all the props to the author, I refined it and added a lot of features that I personally needed.
If you want any additional features, please open an issue on github and I will try to add it ASAP.

---

### To Build
1. Install JDK 21–27 and optionally IntelliJ IDEA for development.
2. Fetch code with `git clone --recursive https://github.com/machinemaslo/slang-jetbrains-extension.git`
3. The minimum supported IntelliJ IDEA version is 2025.2.6.4 (platform build `252.28539.149`), which is also the default Gradle SDK. To build and test against a local IDE instead, pass `-PlocalIdePath=/path/to/intellij-idea` to Gradle. **Run Plugin (IJ)** independently uses the newest IDEA installed through Toolbox.
4. Open the project in IDEA, select the Gradle wrapper and JDK 21–27 as the project/Gradle JVM, then run `test` and `buildPlugin` (or `./gradlew test buildPlugin` from the project directory). Tests require the native prerequisites below. Compilation targets Java 21.
5. Install the plugin ZIP from `build/distributions/`

---

### Language server
Use a current `slangd` installation. Configure the server directory in Settings → Tools → Slang → General, or make it available through vcpkg or PATH.

---

### Formatting
Choose formatting under **Editor → Code Style → Slang → General**:

- **Slang formatter** (default): uses IDE settings without an executable. It can import supported indentation, line-width, and brace settings from `.clang-format`.
- **Clang-Format**: requires the full path to an external executable unless CLion or Rider provides a bundled copy. It reads `.clang-format` and disables the native formatting inspection.

With **Read code style from .clang-format files**, the Slang formatter starts in each source file’s directory and walks up to the nearest `.clang-format` or `_clang-format`. A root config is conventional for shared defaults, but subdirectories can have their own. Set `BasedOnStyle: InheritParentConfig` to inherit a parent config and override selected settings locally.

The native importer supports indentation and tabs, `ColumnLimit`, `IndentCaseLabels`, `NamespaceIndentation`, blank-line limits, spacing around generic angle brackets and after logical `!`, and selected `BraceWrapping` options with `BreakBeforeBraces: Custom`. It also respects `DisableFormat` and `clang-format off` / `clang-format on` comments. It ignores options it cannot reproduce; select **Clang-Format** to use the executable’s broader option set.

Use **Write current style to .clang-format…** to export the options that have a clang-format equivalent. Slang-only controls and naming rules are not exported.

Other tabs control indentation, naming, braces, blank lines, wrapping, spacing, and alignment, with live previews. **Set from → Slang** applies default naming rules.

**Inconsistent Naming** checks the first matching rule and offers a reference-aware, undoable **Rename to ‘suggested_name’** action. Rules can match multiple kinds of Slang elements and accept multiple styles. The first style is used for suggestions when none of the styles match. 
Use **Reformat Code** for a file or selection. The native formatter preserves Slang-specific layouts; optional live checks use **Incorrect formatting**.

New projects have no predefined macros. Edit project macros under **General**.

### Development playground

Select **Run Plugin (IJ)** (`./gradlew runPlugin`) or **Run Plugin (CLion)** (`./gradlew runPluginCLion`). Both read JetBrains Toolbox's local inventory and launch the newest installed build of the requested IDE in a separate sandbox with sample Slang files. No IDE download or per-developer path setting is needed; install the IDE in Toolbox if it isn't listed there.
The CLion playground disables its bundled Python plugin to avoid a startup error in some CLion builds, use `-PplaygroundPython=true` to enable it in that sandbox.
Install `slangd` on PATH or use existing vcpkg tools. Native formatting is the default; clang-format is optional.
Edit `dev/playground` for lasting changes—samples and generated settings reset each launch. Extra files and dictionaries remain.
Plugin DevKit is optional for development assistance.

### Smart editing

#### Slang tools for JetBrains Air and MCP

With JetBrains' MCP Server enabled, the plugin contributes three read-only tools.

- `slang_lookup` searches the official [Slang LLM index](https://shader-slang.org/docs/llms.txt) and [full reference](https://shader-slang.org/docs/llms-full.txt), follows supported links to guides and examples, and looks up exact intrinsic names in the Core Module Reference. The plugin caches public pages, ranks them locally, and sends only requests for the selected pages.
- `slang_check_files` checks the current editor contents of up to 16 project Slang files with `slangc`, using configured include paths and macros. Imported dependencies are read from disk.
- `slang_symbol` uses `slangd` to resolve a symbol at a file position and can also find project references.

Search Settings for **MCP Server** to enable it and configure clients. Newer IDEs also list the tools under **Exposed Tools**. For standalone Air connected to a running IDE, use **Clients Auto-Configuration**. Keep the IDE server disabled if you don't want to expose tools to MCP clients.

The plugin compiles against the MCP Server bundled with the minimum IDEA SDK. Run `./gradlew test -PmcpSmokeTest=true` to check that the server discovers and invokes all three tools on that SDK.

To diagnose slow inspections or generic-bracket typing on large files, add `#slanglsp.editor.AnalysisTiming` to **Help → Diagnostic Tools → Debug Log Settings**. The IDE log reports analysis duration and the count and combined wait time of unused-symbol reference checks.

Comments and strings use the IDE’s spellchecker and dictionaries, including bundled Slang terms. Use Alt+Enter for corrections or to save words. Identifiers aren’t checked.

Brackets, quotes, indentation, and smart editing follow IDE settings. Generic `<T>` pairing supports recognized Slang types and declarations without guessing; comparisons and shifts stay unchanged.

Typing never triggers server formatting. Explicit formatting and reformat-on-paste use your chosen formatter.

---

### Rename

Use **Refactor → Rename**. The plugin uses `slangd`, with a reference-verified fallback for unsupported targets. Ordinary verified fallback renames apply directly as one undoable operation. Choose **Preview** to review changes; special cases such as generated-code references, read-only usages, or unloaded modules require a preview. Unsaved edits are supported. The fallback cannot check external/inactive code or name conflicts. Unverifiable targets are rejected; retry if files change.

---

### Unused symbols

Unreferenced declarations are marked automatically, excluding entry points, exports, and uncertain cases. Disable under **Settings → Tools → Slang → General → Check unused symbols**; server diagnostics stay enabled. Checks cover project Slang references only.

Supported declarations offer an undoable **Remove unused** action after references are rechecked. Shared variable declarations and potentially side-effecting initializers are excluded from removal. Unused messages take precedence in hover tooltips while naming underlines and rename actions remain available.

---

### To Run Tests
Tests cover editor features, formatting, naming, refactoring, settings, the native language server, and real vcpkg installation and discovery.

You need JDK 21–27, a bootstrapped vcpkg checkout set as `VCPKG_ROOT`, CMake, Ninja, and native build tools.

Select **Run Tests** in IDEA. Gradle installs Slang and prebuilt clang-format automatically, reuses cached packages, and runs every test. Missing prerequisites cause failure.

`builtin-baseline` in `vcpkg.json` controls tool versions. Changing it regenerates the checksum-verified clang-format package; network access is required. Keep vcpkg updated. Unsupported or unavailable binaries fail without a fallback.

Results appear in IDEA; reports are saved under `build/reports/tests/test/`.

**On Windows:**
```powershell
.\gradlew.bat test
```

**On macOS/Linux:**
```bash
./gradlew test
```

### vcpkg discovery project bundle

Run the self-contained project fixtures for CMake, MSBuild, Xmake, and Meson:

```bash
./gradlew test --tests '*VcpkgProjectBundleTest' --rerun
```

Tests check discovery paths, priority, host selection, missing servers, and Windows filenames. A real vcpkg/CMake install verifies `slangd`; other build systems use fixtures.

JUnit removes temporary projects; installed dependencies and caches remain. Use IDEA’s Gradle test runner. Results: `build/reports/tests/test/index.html`.
