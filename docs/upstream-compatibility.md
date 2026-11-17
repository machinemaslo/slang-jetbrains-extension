# Upstream compatibility

The plugin uses stock LSP4IJ 0.21.0 and JetBrains formatting services. The following
adapters address defects reproduced with LSP4IJ 0.21.0 and Slang 2026.22.

| Adapter | Reason | Removal criterion |
| --- | --- | --- |
| `DocumentSynchronization` | LSP4IJ captures `didOpen` text before attaching its document listener. Edits made during startup can be lost. Refresh the initial snapshot and rebase queued changes onto transmitted text, with explicit ranges required by slangd. | Startup and queued-change regression tests pass without the adapter against the supported dependency baseline. |
| `ResponseInputStream` | slangd can return `{}` for an absent definition, which is not a valid definition result. Normalize only responses whose request method is `textDocument/definition`. | The native protocol test passes without normalization, including absent definitions. |
| `FormattingConfiguration` | slangd checks `_clang_format` instead of `_clang-format` and discards clang-format's exit status and stderr. Resolve configuration explicitly and validate it with `--dump-config`. Formatting itself still runs through slangd. | Upstream discovers both standard config names and reports invalid configurations; formatting-contract tests pass without preflight. |
| Selection filtering in `ClangFormattingService` | slangd passes a zero-based start line to clang-format's one-based `--lines`, potentially editing before the selection. | Range-formatting contract passes without filtering. |

Protocol adapters are installed through LSP4IJ's launcher-builder extension point.
Semantic highlighting and document links remain provided by LSP4IJ.
External formatting accepts the platform's normal save/undo lifecycle.

Formatting sessions serialize slangd's server-wide options and expose the active
options through `ServerClient.findSettings` as well as configuration notifications.
This prevents a delayed startup `workspace/configuration` reply from restoring the
default style while a formatting request is pending. A repeated cold-start
`_clang-format` contract reproduces that ordering race without changing its expected output.

References:

- [LSP4IJ developer guide](https://github.com/redhat-developer/lsp4ij/blob/0.21.0/docs/DeveloperGuide.md)
- [LSP4IJ document synchronization](https://github.com/redhat-developer/lsp4ij/blob/0.21.0/src/main/java/com/redhat/devtools/lsp4ij/DocumentContentSynchronizer.java)
- [Slang formatting implementation](https://github.com/shader-slang/slang/blob/v2026.22/source/slang/slang-language-server-auto-format.cpp)
