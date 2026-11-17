# Formatting contracts

`shader/before.slang`, `shader/after.native.slang`, and `shader/after.slangd.slang` specify the expected Slang-preset output for both engines.
The semantic colon follows each engine's convention: native `id : SEMANTIC`, slangd's C#-based formatting `id: SEMANTIC`.

These are hand-written golden expectations. Tests must use the registered IDE formatting entry point to select an
engine. Protocol tests additionally exercise real slangd with the existing pinned clang-format executable.

## Scenario layout

Each directory holds one scenario's inputs and expected outputs:

```text
shader/
  before.slang
  after.native.slang
  after.slangd.slang
selection/
  before.slang
  after.slang
configuration/
  external/.clang-format
  explicit/custom-format.yaml
```

The same small source may be reused deliberately across tests, for example
`simple-function/before.slang` with different style/configuration expectations.
`token-preservation/` contains the independent inputs for the token-preservation
matrix; those tests still check tokens and idempotence, not generated golden output.

Use `FormattingFixtures.text("scenario/file.slang")` to read exact UTF-8 text.
Do not trim, dedent, normalize line endings, or reformat these files.
Git pins the fixture tree to LF so Windows checkout settings do not change test input.
Tests specifically exercising a missing final newline use
`FormattingFixtures.withoutFinalNewline(...)`; this removes exactly one final line
ending and no other whitespace. Fixture files themselves retain their final newline.

## Running tests

Run without installing native tools when dependencies are already present:

```bash
./gradlew test -x installTestDependencies --offline \
  --tests '*BundledClangFormatTest' \
  --tests '*FormattingContractTest' \
  --tests '*NativeFormattingConfigurationTest' \
  --tests '*FormattingProtocolTest' \
  --tests '*FormatterSettingsTest' \
  --tests '*FormattingUndoTest'
```

This is a tests-first specification. Failures in the new contracts remain enabled
until the production implementation supports the agreed behavior; do not mark them
ignored or replace their expected output with the current broken output.

The standalone protocol tests establish what the upstream backend supports; they
do not substitute for IDE acceptance tests. Selection acceptance tests are stricter
than the backend: neighboring declarations must not be reformatted even when the
underlying formatter expands its range.
