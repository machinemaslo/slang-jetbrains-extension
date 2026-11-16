# Slang naming defaults

The **Slang** code-style preset follows the upstream
[coding conventions](https://github.com/shader-slang/slang/blob/cdca151fb8578eb5a7c0c1c16bb1f595bc26c6d9/docs/design/coding-conventions.md#naming).
The document explicitly applies its recommendations to `.slang` files as well as
the compiler implementation. Examples were checked at that revision.

| Declaration | Default | Example / basis |
| --- | --- | --- |
| Structs and types | UpperCamelCase | `SurfaceGeometry`, `SimpleMaterial` |
| Interfaces | I + UpperCamelCase | `ITransformer`, `IMaterial`, `IBRDF` |
| Enums | UpperCamelCase | `Fruit` |
| Enum members | UpperCamelCase | `Orange`, `Apple`; Slang enums are scoped by default |
| Namespaces | UpperCamelCase | `Slang` in the namespace recommendation |
| Functions | lowerCamelCase | `computeMain`, `vertexMain` (entry points themselves are exempt) |
| Methods | lowerCamelCase | `transform`, `illuminate`, `prepare` |
| Fields and properties | lowerCamelCase | `diffuseColor`, `viewProjection` |
| Global variables / shader parameters | g + UpperCamelCase | `gViewParams`, `gMaterial` |
| Static constants | k + UpperCamelCase | documented `static const` convention, e.g. `kSampleCount` |
| Generic parameters | UpperCamelCase | `T`, `L`, `N` in `LightArray` / `LightPair` |
| Parameters | lowerCamelCase | `assembledVertex`, `coarseVertex` |
| Local variables | lowerCamelCase | `worldPosition`, `worldNormal`, `nDotL` |

Examples:

- [Shader objects](https://github.com/shader-slang/slang/blob/cdca151fb8578eb5a7c0c1c16bb1f595bc26c6d9/examples/shader-object/shader-object.slang)
- [Model viewer](https://github.com/shader-slang/slang/blob/cdca151fb8578eb5a7c0c1c16bb1f595bc26c6d9/examples/model-viewer/shaders.slang)
- [Scoped enums](https://github.com/shader-slang/slang/blob/cdca151fb8578eb5a7c0c1c16bb1f595bc26c6d9/tests/language-feature/enums/scoped-enum.slang)
- [Generic value parameters](https://github.com/shader-slang/slang/blob/cdca151fb8578eb5a7c0c1c16bb1f595bc26c6d9/tests/language-feature/generics/generic-default-value-param.slang)

Upstream examples are not perfectly uniform: hello-world uses unprefixed buffer
globals and mathematical examples use single uppercase local names. Where examples
differ, the preset follows the written recommendation. Optional private-member
prefixes can be configured in individual rules.

Acronyms retain their spelling, e.g. `nodeID`, `IBRDF`, `d3dThing`.
C++ unscoped-enum prefixes, header guards, and template terminology are not used.
Existing customized schemes remain editable; use the platform's **Set from → Slang**
to apply the upstream defaults to an existing scheme.
