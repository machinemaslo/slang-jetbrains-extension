---
name: slang-tools
description: "Use the Slang IDE tools for .slang and .slangh shaders: look up syntax or intrinsics, check edited shaders, and resolve declarations or references."
allowed-tools: execute_tool slang_lookup slang_check_files slang_symbol
---

Call the Slang tools directly if exposed. Otherwise pass one of these command strings to `execute_tool`:

```text
slang_lookup --query "saturate"
slang_check_files --files '["path/to/shader.slang"]'
slang_symbol --file path/to/shader.slang --line 12 --column 8 --references true
```

Use paths relative to the IDE project and 1-based line and UTF-16 column numbers. Check only relevant files. Omit `--references true` if you only need the declaration. Do not call `help` or `tools/list` through the router.
