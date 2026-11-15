# Slang plugin playground

Open `main.slang`, `lighting.slang`, and `preview.slang`.

- **Navigation/references:** Go to Declaration, Find Usages, and Rename on
  `shadeSurface`. Both callers should participate.
- **Unused symbols:** inspect `unusedLocal` and `unusedHelper`.
- **Spelling:** use Alt+Enter on `mispellled` in a comment and a string.
  Try spelling corrections and Save to dictionary. In the IDE spelling settings,
  enable asking which dictionary to save to, then try project and application.
- **Formatting:** reformat `badlyFormatted`, then undo. Change
  Editor → Code Style → Slang and try the presets.
  Choose Slang formatter or Clang-Format under Editor → Code Style → Slang →
  General. Clang-Format can use the IDE's bundled tool, PATH, or a custom executable.
- **Editing:** try completion, hover, bracket/quote pairing, Enter, and Backspace.

The project enables Typo inspection. Optional native formatting checks use the
IDE's General → Incorrect formatting inspection.
Use Reformat Code for layout changes. Wait for indexing
and language-server initialization before trying navigation.

This is an editable copy under `build/playground`. Relaunching preserves edits
and settings. To reset, close the sandbox IDE, delete `build/playground`, and
run **Run Plugin** again.
