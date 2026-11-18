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

Keep test fixtures small and valid Slang, including expected output, test source errors through batch inspections.

Cover default settings, supported options, key setting combinations, and invalid configurations. The native engine supports only some clang-format options.
Check exact output, unchanged tokens, and that formatting twice gives the same result.

`BundledClangFormatTest` checks bundled tool locations, CI runs all tests on Windows, Linux, and macOS.

Read fixtures with `FormattingFixtures.text("scenario/file.slang")`. Preserve all whitespace and line endings. Fixtures use LF and end with a newline. For tests that need no final newline, use `FormattingFixtures.withoutFinalNewline(...)`.
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

This is a tests-first specification, failures in the new contracts remain enabled
until the production implementation supports the agreed behavior, do not mark them
ignored or replace their expected output with the current broken output.
