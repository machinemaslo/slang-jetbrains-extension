### This is a refined fork of unofficial LSP for Slang to use with JetBrains IDEs.

---

### Example
![Example](./images/example.gif)

---

### Original JetBrains plugin distribution page
https://plugins.jetbrains.com/plugin/26239-slang-unofficial-

---

### To Build
1. install IntelliJ IDEA and JDK 21 or newer
2. fetch code with `git clone --recursive https://github.com/16-Bit-Dog/slang-intellj-extenson.git`
3. to use your installed IDEA, create `local.properties` with `localIdePath=/path/to/intellij-idea` and `compilerJavaHome=/path/to/intellij-idea/jbr` on separate lines. Otherwise, Gradle downloads IDEA 2024.1.4.
4. open the project in IDEA, select the Gradle wrapper and JDK 21 as the Gradle JVM, then run the `test` and `buildPlugin` tasks
5. install the plugin ZIP from `build/distributions/`

---

### If highlighting does not work, try the following:
(1) delete the Slang extension
(2) reinstall the Slang extension (latest version)

---

### Local vcpkg slangd
1. install the `shader-slang` package with vcpkg
2. enable "Use local vcpkg slangd package if found" in Settings → Tools → Slang (enabled by default)

---

### To Run Tests
The project contains native Java tests to verify Lexer and Syntax Highlighter correctness. To run the tests, use the included Gradle wrapper (requires JDK 21):

**On Windows:**
```cmd
./gradlew test
```

To also test import resolution through a real language server, set `SLANGD_TEST_EXECUTABLE`
to the absolute path of `slangd` before running the tests. This checks workspace discovery,
configured include paths, configuration replies, and cross-file go-to-definition over LSP.

**On macOS/Linux:**
```bash
./gradlew test
```
