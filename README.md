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
Install `clang-format`, then open Settings → Tools → Slang → Formatting. Leave the executable field empty to search PATH, or enter its full path.
New projects start with no predefined macros. Existing project macros remain editable under General; examples are shown as help text rather than definitions.

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
The small test suite covers numeric lexing, settings persistence, executable discovery, language isolation, and an optional real-server smoke test. Run it with the included Gradle wrapper (requires JDK 21):

**On Windows:**
```cmd
./gradlew test
```

To also test import resolution through a real language server, set `SLANGD_TEST_EXECUTABLE`
to the absolute path of `slangd` before running the tests. This checks configured
include paths, configuration replies, cross-file go-to-definition, hover, and
semantic tokens over LSP.

**On macOS/Linux:**
```bash
./gradlew test
```

### vcpkg discovery project bundle

Run the self-contained project fixtures for CMake, MSBuild, Xmake, and Meson:

```bash
./gradlew test --tests '*VcpkgProjectBundleTest' --rerun
```

The bundle generates temporary project files and vcpkg installation trees, then calls the plugin's production discovery code. It checks custom paths with spaces, all three conventional install directories in priority order, host-versus-target selection, missing servers, and Windows executable naming. Fixture executables are placeholders and are never run. These tests need no build tools or network access beyond the normal Gradle dependencies.

To also install the real `shader-slang` package and configure CMake:

```bash
VCPKG_TEST_ROOT=/absolute/path/to/vcpkg ./gradlew test --tests '*VcpkgProjectBundleTest' --rerun
```

In PowerShell, set `$env:VCPKG_TEST_ROOT = 'C:\path\to\vcpkg'` before running the Gradle command.

The opt-in test needs a bootstrapped vcpkg checkout, CMake, Ninja, and the native toolchain required by the port. It pins baseline `e6f9e70a29a3e80a1fc510d8503304315447112f`, installs into a temporary custom directory, configures CMake, and asks every adapter to locate the real installed `slangd`. MSBuild, Xmake, and Meson use generated metadata fixtures; their build tools are not invoked. Windows executable naming on a non-Windows machine is likewise a fixture test, not a Windows build.

Temporary projects are cleaned up by JUnit. vcpkg's normal download/build/binary caches may persist in its checkout and user cache. Results are in `build/reports/tests/test/index.html`; without `VCPKG_TEST_ROOT`, only the real-install test is skipped.
