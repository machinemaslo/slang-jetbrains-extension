# Formatting contracts

`shader/before.slang` and `shader/after.native.slang` specify native Slang-preset output.

Native golden expectations are handwritten. IDE acceptance tests use the registered formatting entry point
with slangd disabled for native formatting and enabled for external formatting.
External formatting must run clang-format through slangd.
Clang-format smoke tests use supported sources to verify executable/configuration wiring through slangd.
Upstream slangd formatting output is outside this project's test contract.

## Scenario layout

Each directory holds one scenario's inputs and expected outputs:

```text
shader/
  before.slang
  after.native.slang
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
The operators input also has explicit golden expectations under `operators/` for enabled/disabled spacing.
All `.slang` resource fixtures must be self-contained valid Slang, including golden output.
Source diagnostics are tested through the plugin's batch inspections, including malformed input and correction.
Expected assembly fragments are extracted from the valid source rather than stored as standalone `.slang` files.
Shader attributes provide Slang string-token coverage; `bitwise.slang` covers shift and bitwise operators.

Native configuration tests cover supported imported options and predictable handling of unsupported or
malformed configuration; the native engine does not implement every clang-format option.
Keep default-setting coverage and targeted setting interactions (spacing/operator boundaries,
wrapping/tabs, alignment, brace overrides). Exact output, token preservation, and idempotence
are complementary assertions.

`BundledClangFormatTest` owns installation-layout discovery, including issue #9's `windows-x64`
directory. The build workflow runs the full suite on Windows, Linux, and macOS; host-specific discovery
tests therefore exercise actual host filesystem behavior.

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
  --tests '*NativeFormattingTest' \
  --tests '*FormatterSettingsTest'
```

This is a tests-first specification. Failures in the new contracts remain enabled
until the production implementation supports the agreed behavior; do not mark them
ignored or replace their expected output with the current broken output.

Selection acceptance tests require neighboring declarations to remain unchanged.
External formatting uses JetBrains' standard save/undo lifecycle; formatting contracts
cover no-op behavior and synchronous undo/redo through the public IDE entry point.
