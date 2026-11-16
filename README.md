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
3. The plugin requires a JetBrains IDE 2025.1 or newer. Builds use the Gradle-managed IDEA 2025.1 SDK by default. To build and test against a local IDE instead, pass `-PlocalIdePath=/path/to/intellij-idea` to Gradle. **Run Plugin** independently uses the latest IDEA.
4. Open the project in IDEA, select the Gradle wrapper and JDK 21–27 as the project/Gradle JVM, then run `test` and `buildPlugin` (or `./gradlew test buildPlugin` from the project directory). Tests require the native prerequisites below. Compilation targets Java 21.
5. Install the plugin ZIP from `build/distributions/`

---

### Language server
Use a current `slangd` installation. Configure the server directory in Settings → Tools → Slang → General, or make it available through vcpkg or PATH.

---

### Formatting
Choose formatting under **Editor → Code Style → Slang → General**:

- **Slang formatter** (default): uses IDE settings without an executable. It can import supported indentation, line-width, and brace settings from `.clang-format`.
- **Clang-Format**: uses a bundled, PATH, or custom executable. It reads `.clang-format`, defaults to LLVM, and ignores native formatting settings. Missing tools cause an error.

Other tabs control indentation, naming, braces, blank lines, wrapping, spacing, and alignment, with live previews. **Set from → Slang** applies default naming rules, four-space indentation, a **Tab width** of four columns, 100 columns, and Allman braces.

**Inconsistent Naming** checks the first matching rule and offers a reference-aware, undoable **Rename to ‘suggested_name’** action. Rules support custom casing, prefixes, suffixes, and abbreviations. [Upstream-derived defaults](docs/slang-naming.md) include `UpperCamelCase` types, `lowerCamelCase` values, `I` interfaces, `g` globals, and `k` static constants. Entry points and required external/interface names are excluded.

Use **Reformat Code** for a file or selection. The native formatter preserves Slang-specific layouts; optional live checks use **Incorrect formatting**.

New projects have no predefined macros. Edit project macros under **General**.

### Development playground

Select **Run Plugin** or run `./gradlew runPlugin`. Gradle downloads the latest IntelliJ IDEA and compatible dependencies, then launches a separate sandbox with sample Slang files.
Install `slangd` on PATH or use existing vcpkg tools. Native formatting is the default; clang-format is optional.
Edit `dev/playground` for lasting changes—samples and generated settings reset each launch. Extra files and dictionaries remain.
Plugin DevKit is optional for development assistance.

### Smart editing

Comments and strings use the IDE’s spellchecker and dictionaries, including bundled [Slang terms](docs/spelling-dictionary.md). Use Alt+Enter for corrections or to save words. Identifiers aren’t checked.

Brackets, quotes, indentation, and smart editing follow IDE settings. Generic `<T>` pairing supports recognized Slang types and declarations without guessing; comparisons and shifts stay unchanged.

Typing never triggers server formatting. Explicit formatting and reformat-on-paste use your chosen formatter.

---

### Rename

Use **Refactor → Rename**. The plugin uses `slangd`, with a reference-verified fallback for unsupported targets. Ordinary verified fallback renames apply directly as one undoable operation. Choose **Preview** to review changes; special cases such as generated-code references, read-only usages, or unloaded modules require a preview. Unsaved edits are supported. The fallback cannot check external/inactive code or name conflicts. Unverifiable targets are rejected; retry if files change.

---

### Unused symbols

Unreferenced declarations are marked automatically, excluding entry points, exports, and uncertain cases. Disable under **Settings → Tools → Slang → General → Check unused symbols**; server diagnostics stay enabled. Checks cover project Slang references only.

Supported declarations offer an undoable **Remove unused …** action after references are rechecked. Shared variable declarations and potentially side-effecting initializers are excluded from removal. Unused messages take precedence in hover tooltips while naming underlines and rename actions remain available.

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
