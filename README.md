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

### Language server
Use a current `slangd` installation. The client normalizes empty-object no-result
responses to JSON null before LSP4J reads them. Configure the server directory in
Settings → Tools → Slang → General, or make it available through vcpkg or PATH.

---

### Local vcpkg slangd
1. install the `shader-slang` package with vcpkg
2. enable "Use local vcpkg slangd package if found" in Settings → Tools → Slang (enabled by default)

---

### To Run Tests
The small test suite covers numeric lexing, settings persistence, executable
discovery, language isolation, and an optional real-server smoke test. Run it
with the included Gradle wrapper (requires JDK 21):

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

### Formatting
Install `clang-format`, then open Settings → Tools → Slang → Formatting.
Leave the executable field empty to search PATH, or enter its full path.
The settings include formatting on type, style (`file` by default), fallback
style, and whether on-type or selection formatting may change line breaks.
These options are sent to `slangd` when you apply settings.

New projects start with no predefined macros. Existing project macros remain
editable under General; examples are shown as help text rather than definitions.

### Navigation and usages
Go to Declaration opens built-in Slang definitions in read-only source tabs.
Their contents come from the running server's executable and are cached until
the server restarts. These tabs are not indexed as project files and do not
start additional language-server sessions.

Find Usages uses server references when available. Otherwise it uses the IDE's
word index, includes unsaved edits, and confirms each candidate with `slangd` to
exclude unrelated symbols with the same name. Results appear in the normal
Find Usages window as they are found, and the search can be canceled. Clicking
a declaration starts this search instead of scanning the project during hover.

### Code colors
Slang semantic tokens supply the symbol categories and exact source ranges.
In CLion, Slang colors inherit the active C/C++ color scheme; other IDEs use
Language Defaults. Types, functions, parameters, fields, constants, namespaces,
and macros can also be customized under Editor → Color Scheme → Slang.
Built-in types, directives, literals, comments, and punctuation have syntax
colors while the language server is starting or unavailable.

The server currently emits the same `function` token for declarations and calls,
with no modifiers. Both use the Function color. Keywords also need lexical
highlighting, as in the official VS Code grammar. Missing C/C++ color-key defaults
fall back to the standard IDE category instead of becoming unstyled. Variable
colors follow the scheme's local-variable category; a scheme may intentionally
use its normal text color there. Override it in the Slang color settings if desired.
