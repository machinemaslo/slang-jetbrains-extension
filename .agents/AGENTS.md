# Code formatting

Use readable line breaks and blank lines between logical blocks throughout every project source and configuration file, including existing code. Keep Java annotations and control-flow bodies on separate lines; do not cram methods or statements onto one line. End every text file with a newline. Preserve whitespace that is meaningful inside strings, fixtures, and file formats.

# Naming

Do not automatically prefix new files or classes with `Slang`; the `slanglsp` package already provides that context. Prefer descriptive responsibility-based names. Use the prefix when it meaningfully distinguishes a Slang-specific implementation of a platform concept (for example, `SlangLanguage` or `SlangFileType`). Keep Java filenames consistent with their public class names, and do not rename existing classes solely to remove the prefix.
