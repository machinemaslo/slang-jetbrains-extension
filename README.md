### This is a Refined Unofficial Slang LSP

---

### Example
![Example](./images/example.gif)

---

### Base JetBrains plugin distribution page
https://plugins.jetbrains.com/plugin/26239-slang-unofficial-
This plugin was forked from that one, all the props to the author, I refined it and added a lot of features that I personally needed.
If you want any additional features, please open an issue on github and I will try to add it ASAP.

---

### To Build
1. Install IntelliJ IDEA and JDK 21 or newer
2. Fetch code with `git clone --recursive https://github.com/16-Bit-Dog/slang-intellj-extenson.git`
3. Release builds use IDEA 2024.2.6. For local development, pass `-PlocalIdePath=/path/to/intellij-idea` to Gradle.
4. Open the project in IDEA, select the Gradle wrapper and a supported JDK (21–27) as the project/Gradle JVM, then run the `test` and `buildPlugin` tasks. Compilation targets Java 21.
5. Install the plugin ZIP from `build/distributions/`

---

### Language server
Use a current `slangd` installation. Configure the server directory in Settings → Tools → Slang → General, or make it available through vcpkg or PATH.

---

### Formatting
Choose the engine under **Editor → Code Style → Slang → General**:

- **Slang formatter** (default): uses the native JetBrains model and the Slang
  code-style tabs. No executable is required.
- **Read code style from .clang-format files**: keeps the native engine and
  imports supported settings from the nearest `.clang-format` or `_clang-format`.
  Supported options are `IndentWidth`, `ContinuationIndentWidth`, `TabWidth`,
  `UseTab`, `ColumnLimit`, and `BreakBeforeBraces` (`Allman`, `GNU`, `Attach`).
  Common `BasedOnStyle` presets supply the corresponding native values;
  `InheritParentConfig` and default/C++ YAML document sections are supported.
  Unsupported options do not change the native settings.
- **Clang-Format**: runs the IDE's bundled executable when detected (CLion/Rider),
  an executable on PATH, or a custom executable selected with Browse.
  It uses `.clang-format`, falling back to LLVM when no file exists.
  Native Code Style formatting options do not affect this engine.
  Missing tools produce a formatting error rather than silently switching engines.

**Editor → Code Style → Slang** controls tabs, indentation, continuation indentation, line length, and opening-brace placement.
The page opens on **General** and uses standard JetBrains option tables and live previews:

- **Tabs and Indents** — spaces/tabs, indentation, continuation indentation, tab width, smart tabs.
- **Naming** — ordered Slang naming rules with previews, casing, prefixes/suffixes, and abbreviations.
- **Braces Layout** — default placement plus separate function, type/namespace, and control-flow overrides;
  placement of `else`, `catch`, and `while`.
- **Blank Lines** — retained blank lines in code/declarations, before closing braces, and between top-level functions.
- **Line Breaks and Wrapping** — line width, preserving existing breaks, argument/parameter lists, assignments.
- **Spaces** — control/function parentheses, parentheses/bracket interiors, commas, semicolons, and operators.
- **Indentation and Alignment** — multiline argument/parameter alignment and switch/case/break indentation.

The organization follows [JetBrains' C/C++ Code Style settings](https://www.jetbrains.com/help/clion/settings-code-style-c-cpp.html),
with options implemented for Slang's native formatter. Previews include shader attributes and Slang declarations.

**Inconsistent Naming** (under **Editor → Inspections → Slang**) checks declarations against the
first matching naming rule. Its enable checkbox is also available on the Naming tab.
Warnings underline the first two characters and offer **Rename to 'suggested_name'** throughout the identifier.
Ordinary renames update verified declarations and references directly as one undoable operation.
Use **Preview** in the Rename dialog to review changes explicitly. Generated-code references,
read-only occurrences, and unloaded project modules trigger a preview automatically.
The **Slang** defaults follow upstream: `UpperCamelCase` types, `lowerCamelCase` values,
`ITransformer` interfaces, `gViewParams` globals, and `kSampleCount` static constants.
See [the per-category conventions and upstream examples](docs/slang-naming.md).
Declaration discovery uses `slangd`, supplemented by conservative parameter/local detection.
Shader entry-point names, explicit external/override declarations, and interface requirements are excluded.
Unused declarations take precedence in hover tooltips and offer **Remove unused function**,
**Remove unused struct**, or the corresponding action for methods, classes, enums, fields, constants,
and variables. Naming still underlines the first two characters and offers **Rename to …** in
the lightbulb menu. Removal rechecks references and supports undo. Shared variable declarations and
initializers whose evaluation cannot safely be discarded retain the warning without a removal action.
Use **Set from → Slang** to apply the default naming rules and formatting, following shader-slang v2026.22's
[`.clang-format`](https://github.com/shader-slang/slang/blob/v2026.22/.clang-format): four-space indentation,
100 columns, Allman braces.
These settings are editable and stored in the IDE's code-style scheme.

The native formatter handles braces, spacing, indentation, continuation indentation, and wrapping,
and powers the Code Style preview. It preserves token contents (including strings, comments, and
multiline preprocessor directives) and keeps ambiguous pointer/generic spacing unchanged.
clang-format parses Slang as C++; choose the native engine to preserve Slang-specific
attribute and visibility-modifier layouts.

Use the IDE's normal
**Reformat Code** action to format the entire file or an explicit selection.
For optional live checks with the native engine, enable the built-in
**Editor → Inspections → General → Incorrect formatting** inspection.
The platform skips this inspection when clang-format is selected. There is no
separate Slang formatting inspection.

New projects start with no predefined macros. Existing project macros remain editable under General; examples are shown as help text rather than definitions.

### Development playground

Select **Run Plugin** (or run `./gradlew runPlugin`) to launch the latest release
of IntelliJ IDEA in a dedicated plugin sandbox. Gradle downloads/caches the IDE,
its runtime, and a compatible LSP4IJ. Every launch refreshes `build/playground` from `dev/playground` with open
Slang files, cross-file function calls, unused symbols, spelling mistakes, and
formatting examples.

Put installed `slangd` on PATH or use the repository's existing `vcpkg_installed`
tools. The playground defaults to the native formatter; select clang-format in
Code Style if desired. Launching does not install native tools. Template-owned files
and generated executable settings are overwritten on each launch. Edit `dev/playground`
to make lasting changes to the examples; additional files and dictionaries are retained.
The Slang executable-directory setting accepts paths relative to the project root.
The playground uses `../../vcpkg_installed/<host-triplet>/tools/shader-slang`.
On each launch, appearance settings (including normal UI zoom, theme, and fonts)
are inherited from the most recently used IntelliJ IDEA configuration in the
OS's standard JetBrains settings directory. With no existing configuration,
the sandbox keeps its own settings. Presentation Mode zoom is a separate setting
and only applies while Presentation Mode is active.

Plugin DevKit can be enabled in your development IDE for plugin.xml assistance
and plugin development inspections. The shared Gradle run configuration handles
building, sandbox dependencies, launching, and debugging across developer machines.

### Smart editing
Comments and strings use the IDE's **Proofreading → Typo** inspection and its built-in, project,
application, and custom dictionaries. Standard Alt+Enter spelling suggestions and **Save to dictionary**
actions are available; the IDE's spelling settings control whether saving asks for a project/application
dictionary or uses a default. Code identifiers are not spellchecked.
A small [Slang terminology dictionary](docs/spelling-dictionary.md) is bundled with the plugin.

Slang provides local bracket and quote pairing, paired deletion, selection wrapping, smart Enter and Backspace,
comment continuation, closing-brace indentation, and paste indentation. These follow **Editor → General → Smart Keys**
and the IDE's indentation options. Enter in a populated inline body expands it onto separate lines.

Generic angle-bracket pairing understands Slang's `name<T>`, `__generic<T>`, and `extension<T>` forms,
including value parameters, type packs, nested arguments, paired deletion, type-over, selection wrapping,
and Tab-out. It recognizes local declarations, directly imported indexed declarations, and common built-in
generic types. Comparisons and shifts remain operators; unresolved or expensive symbol lookups are left
unpaired rather than guessing from capitalization. Generic argument lists also support multiline indentation.

Ordinary typing does not request server-side on-type formatting, so entering a statement does not unexpectedly
rewrite its surrounding spaces. Explicit formatting and reformat-on-paste use the configured formatter.

---

### Rename
Use Refactor → Rename on a Slang identifier. When `slangd` supports rename, LSP4IJ handles it directly. Otherwise, the plugin verifies matching occurrences through go-to-definition and opens a native preview before changing the declaration and its usages together. The change supports Undo and includes unsaved edits.
The fallback searches project `.slang` and `.slangh` files under the current server configuration. It cannot check inactive code, external references, or new-name conflicts. Built-in/external declarations, ambiguous targets, and declarations the server cannot verify are rejected. If project files change during the search or preview, run Rename again.

---

### Unused symbols
Structs, functions, fields, globals, and supported local variable declarations with no references in project Slang files use the IDE's unused-symbol style. Analysis runs in the background and reuses the reference finder. Public declarations are checked across project Slang files. Shader entry points and explicitly exported/external declarations are excluded; unresolved reference candidates are left unmarked.

Under **Settings → Tools → Slang → General**, **Check unused symbols** enables the plugin analysis and is checked by default. Uncheck it to disable the plugin analysis. Server diagnostics remain enabled in either mode.

The fallback cannot see usage outside project Slang files, host-side resource bindings, or inactive configurations. Local declaration discovery is conservative; function bodies containing preprocessor directives are skipped. This checks references, not whether a variable's value is ever read or a function is reachable from an entry point.

---

### To Run Tests
The test suite includes native language-server integration and real vcpkg installation/discovery tests. Prerequisites:

- JDK 21 or newer.
- The `VCPKG_ROOT` environment variable set to a bootstrapped vcpkg checkout. Gradle runs the executable in that directory; it does not need to be on `PATH`.
- CMake, Ninja, and the native build toolchain required by `shader-slang`.

Gradle's `installTestDependencies` task installs `shader-slang` and prebuilt `clang-format` into `vcpkg_installed/` before tests run. No separate Slang, clang-format, or Python installation is needed, and clang-format does not build LLVM from source. Subsequent runs reuse vcpkg's installed packages and caches. Missing prerequisites fail the run rather than skipping integration tests.

**`builtin-baseline` in `vcpkg.json` is the single version selector.** Before installation, `generateClangFormatPort` reads LLVM's version from that exact official vcpkg commit, resolves the same clang-format release on PyPI, verifies the archive against PyPI's SHA-256 digest, and generates a SHA-512-pinned overlay port under `build/generated/vcpkg-ports/clang-format/`. The generated `provenance.json` records the baseline, version, URL, and checksums.

Changing only `builtin-baseline` regenerates the overlay on the next **Run Tests**, and vcpkg installs the matching tools. Discovery fixtures also read that same manifest. If the new baseline selects the same LLVM version, clang-format naturally stays at that version. Keep your local vcpkg checkout updated so its version index includes the selected official packages.

Prebuilt binaries come from the community [ssciwr clang-format-wheel distribution](https://github.com/ssciwr/clang-format-wheel) on PyPI. The native executable is extracted directly; pip and Python wrappers are not used. Supported hosts are x64/arm64 Windows and macOS, and glibc Linux. OS/runtime minimums depend on the chosen release (23.1.5 requires glibc 2.27+ on x64 and 2.26+ on arm64). Missing, yanked, or ambiguous matching binaries fail clearly; there is no LLVM source-build or alternate-version fallback. Generating a new overlay requires network access; unchanged task inputs reuse it.

In IDEA, select the shared **Run Tests** configuration. It inherits `VCPKG_ROOT` from IDEA's environment, runs Gradle's `test` task, and displays test results and standard output/error in the Run tool window. It forces tests to run even when Gradle considers them up to date. Reports are saved under `build/reports/tests/test/`, with machine-readable results under `build/test-results/test/`; no shell redirection to `/tmp` is used.

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

The bundle generates temporary project files and vcpkg installation trees, then calls the plugin's production discovery code. It checks custom paths with spaces, all three conventional install directories in priority order, host-versus-target selection, missing servers, and Windows executable naming.

The real-install test uses the same vcpkg checkout, installs `shader-slang` into a temporary custom directory, configures CMake, and asks every adapter to locate the real installed `slangd`. Its baseline matches the root manifest. MSBuild, Xmake, and Meson use generated metadata fixtures; their build tools are not invoked. Windows executable naming on a non-Windows machine is likewise a fixture test, not a Windows build.

Temporary projects are cleaned up by JUnit. The root `vcpkg_installed/` directory and vcpkg's download/build/binary caches persist for reuse. Results are in `build/reports/tests/test/index.html`. For IDE test runs, use the Gradle test runner so dependency installation and test properties are configured.
