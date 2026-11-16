- [Slang documentation overview](https://docs.shader-slang.org/en/latest/):
  SlangPy, WGSL, slangc.
- [Automatic differentiation](https://docs.shader-slang.org/en/latest/external/slang/docs/user-guide/07-autodiff.html):
  backprop.
- [Basic convenience features](https://docs.shader-slang.org/en/latest/external/slang/docs/user-guide/03-convenience-features.html):
  bindless, cbuffer, numthreads, spirv.
- [Slang language server](https://github.com/shader-slang/slang/tree/v2026.22/tools/slangd):
  slangd.
- [SPIR-V target specifics](https://docs.shader-slang.org/en/latest/external/slang/docs/user-guide/a2-01-spirv-target-specific.html):
  StructuredBuffer, RWStructuredBuffer, ByteAddressBuffer, RWByteAddressBuffer,
  ConstantBuffer, std430, std140.
- Basic convenience features (linked above): Texture2D and SamplerState.

JetBrains splits camel-case and numeric suffixes. The dictionary includes
lowercase full type names for prose that does not preserve camel case, and
`std` for std140/std430 and `texture` for Texture2D. Integration tests also
exercise their original source spellings in comments.
