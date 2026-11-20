---
name: code-style
description: Apply this repository's formatting, naming, and platform-specific testing rules when editing, refactoring, reviewing, or testing the Slang IntelliJ plugin or its build configuration.
---

# Repository code style

## Code formatting

Use readable line breaks and blank lines between logical blocks throughout every project source and configuration file, including existing code. Keep Java annotations and control-flow bodies on separate lines; do not cram methods or statements onto one line. End every text file with a newline. Preserve whitespace that is meaningful inside strings, fixtures, and file formats.

## Naming

Do not automatically prefix new files or classes with `Slang`; the `slanglsp` package already provides that context. Prefer descriptive responsibility-based names. Use the prefix when it meaningfully distinguishes a Slang-specific implementation of a platform concept (for example, `SlangLanguage` or `SlangFileType`). Keep Java filenames consistent with their public class names, and do not rename existing classes solely to remove the prefix.

## Platform-specific tests

Run platform-specific path, filesystem, and executable tests only on the matching operating system. Do not simulate Windows installation paths on Linux or macOS, or add production platform-selection overrides solely for such tests. The GitHub Actions workflow already runs tests on Windows, Linux, and macOS; rely on those matching-OS runners for coverage. A passing local Linux run does not verify Windows behavior.
