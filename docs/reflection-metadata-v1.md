# Slang-to-C++ reflection metadata, version 1

This is the contract for `jb-slanglsp-reflection-meta.hpp` and the shared IDE reader.
The [bundled writer header](../src/main/resources/reflection/jb-slanglsp-reflection-meta.hpp) contains
the public C++17 API, a quick start, and integration notes for CMake and MSBuild. Install it through
**C++ View → Add a header**, include it in the generator, and call `write_reflection_meta()` after
finalizing the ordinary outputs. The writer does not require `#line` directives, generator replay,
or symbol-name guesses.

## Locating a pack

The build integration and IDE adapter must agree on the workspace root, the metadata build directory,
and the exact build identity below. The workspace root is the project root chosen by the integration,
not the directory containing the shader.

| Identity component | CMake | MSBuild |
| --- | --- | --- |
| `system` | `cmake` | `msbuild` |
| `target` | Canonical CMake target name | Workspace-relative `.vcxproj` path |
| `configuration` | Actual `$<CONFIG>` / build type, not the IDE profile label | Evaluated **project** configuration, not the solution label |
| `platform` | Empty string; separate toolchains need distinct build directories | Evaluated **project** platform, for example `x64` |
| Metadata build directory | Configuration generation directory | Absolute `$(IntDir)` resolved against `$(MSBuildProjectDirectory)` |

The producer writes one indexed binary pack per build identity:

```text
<build directory>/.jb-slanglsp/reflection/v1/<variant-key>.pack
```

The variant key is lowercase hex SHA-256 of the UTF-8 bytes of
`system + LF + target + LF + configuration + LF + platform`, with no BOM or extra terminator.
A CMake identity ends immediately after the separator before the empty platform field.
The shader key is the **raw 32-byte SHA-256** of the exact normalized workspace-relative shader path
in UTF-8. An empty CMake build type is representable; the reader must not try a different
configuration if the selected pack is missing.

## Binary layout

All integers are unsigned and little-endian. A `text` is `u32` byte length followed by exactly that
many UTF-8 bytes, with no terminator. A `digest` is 32 raw SHA-256 bytes in standard digest order.
Fields are consecutive, without padding or alignment. Fields appear in this order:

```text
pack:
    magic              8 bytes: 4a 42 53 4c 52 50 4b 00 ("JBSLRPK\0")
    version            u32 = 1
    shader_count       u32
    system            text
    target            text
    configuration     text
    platform          text
    index             shader_count × index_entry
    records           shader_count × shader_record (in index order)

index_entry (48 bytes):
    shader_key         digest
    record_offset      u64 (absolute from the first byte of the pack)
    record_length      u64 (bytes)

shader_record:
    source_path        text
    source_hash        digest
    input_count        u32
    inputs             input_count × file_snapshot
    binding_count      u32
    bindings           binding_count × binding

file_snapshot:
    root               u8: 0 = project, 1 = build
    path               text
    hash               digest

binding:
    slang              declaration
    destination_count  u32 (at least 1)
    destinations       destination_count × (file_snapshot, declaration)

declaration:
    start              u64 (byte offset in source or destination file)
    name               text (end offset is start + the encoded name byte length)
```

- Index keys are strictly increasing in unsigned byte order, with no duplicates. Records are contiguous:
  the first starts immediately after the index, each begins where the previous one ends, and the last ends
  at EOF. A zero-entry pack ends immediately after the identity fields. There are no extension fields in v1.
- All paths use `/` and preserve spelling, including case. They are nonempty, root-relative paths with no
  drive prefix, empty segments, `.` or `..`; resolving them, including symbolic links, must not escape the
  named root. `source_path` is workspace-relative. The identity fields and paths may not contain ASCII control
  bytes (including DEL). No machine-specific absolute paths are serialized.
- `inputs` lists additional files that affect this mapping, such as imports, generator inputs, or a
  build-produced receipt for relevant flags and tool versions. List any root/path pair at most once per
  record. Do not rely on an unverifiable aggregate hash. Declare the corresponding dependencies and
  regenerate the pack when they change.
- Digests cover the final on-disk bytes. Source and C++ text must be UTF-8. Ranges are zero-based,
  end-exclusive **byte offsets** into those same bytes, not character offsets or logical `#line` positions.
  A UTF-8 BOM and CRLF, if present, count toward the byte offsets. The reader translates to IDE document offsets.
  `name` is the exact unqualified identifier at that range.
- Bindings are ordered by Slang start offset, must not overlap, and must have distinct C++ destinations
  within each binding. Destination and input order otherwise has no lookup significance.
- An empty binding list explicitly reports that the indexed shader has no mappings. A missing key means the
  pack has no record for that shader; a missing pack means metadata is unavailable for the selected build.
  None of these results permits guessing from another target or an older path.

## Publication

Exactly one writer owns each pack. Each call supplies the complete set of records for that variant:
omitted shaders are removed, and a call with no shaders writes a valid empty pack. If multiple rules
contribute, collect their records before calling the writer; do not have independent processes update the
same pack. Publication rewrites the full pack in one atomic replacement from a temporary file in the same
directory, after all named outputs are final. A failed write leaves any existing pack in place.

Declare the pack as a build output or byproduct. Reserve the `.jb-slanglsp` subtree for generated
metadata and keep it out of version control. The earlier draft per-shader JSON sidecars are not part of
this contract. The installed helper carries `JB_SLANGLSP_REFLECTION_META_VERSION`, which is independent
of the pack format version. C++ View and Find C++ Usages offer **Replace with newer version** for older
helpers they recognize, including exact copies of the old unversioned drafts. Replacement requires
confirmation and does not overwrite unsaved edits. Rebuild after updating the helper.

## Lookup and invalidation

Open the selected variant's pack and verify the magic, version, identity and index. Validate index entries
without decoding the records. Binary search the fixed-width keys and read only the matching record. Verify
that its source path matches the requested shader and the index key. A validated index can be cached so
subsequent lookups can seek directly to records. Before allocating or seeking, bound counts, lengths and
offsets against their containing section, and reject overflow, truncation, and unsupported versions.

Validate the source, destination and input snapshots before using a mapping. Reject stale bytes, unsaved
changes in the relevant editor documents, malformed ranges, paths that escape their roots, and mismatched
identifiers. Do not return a subset of a binding whose destinations do not all validate.

Cache the index, parsed records, and validation against known file revisions, not just the shader name or
the IDE profile label. Revalidate when files are moved, replaced, or edited. A source move changes the shader
key; a build move changes the directory used to resolve the same relative paths. Rebuild or reconfigure as
needed rather than searching old locations. If the active variant changes while a lookup is running, discard
its result before handing it to native navigation. A lookup must not start a build or run a subprocess.

Both IDE actions distinguish **mapped**, **not mapped**, **missing**, **stale**, and **invalid or conflicting**.
CLion supplies the selected CMake build identity. On Windows, Rider uses an explicit MSBuild association.
The reader bounds its work: 32 MiB of index, 16 MiB per shader record, 64 MiB per source or C++ snapshot,
and 512 MiB of snapshot bytes per lookup. Paths are checked again when using cached validation.

### Rider / MSBuild association

The solution configuration and solution platform labels are not part of the pack identity. Without a supported Rider API
for the active C++ project tuple, the IDE reads the root `.sln` files' `ActiveCfg` assignments. If
there is more than one possible project tuple, it asks which to use. Unbuilt tuples stay in the list;
the chooser never selects a variant because it built most recently or is the only one with a pack.
Repeated solution labels for the same project tuple do not require a choice.

After generation, publish one UTF-8 association at
`<workspace>/.jb-slanglsp/reflection/msbuild/v1/<solution-key>/<variant-key>.assoc`, where
`solution-key` is lowercase hex SHA-256 of the root `.sln` filename. To expose the producer for
Rename, publish:

```text
JB-SLANGLSP-MSBUILD-2
<lowercase hex SHA-256 of the solution bytes>
<lowercase hex SHA-256 of the vcxproj bytes>
<normalized absolute evaluated IntDir>
<generation-only target name>
<normalized absolute path to the MSBuild.exe used by the producer>
```

End each line with LF. Publish atomically, with one writer per tuple. The read-only
`JB-SLANGLSP-MSBUILD-1` form ends after `IntDir` and still supports usage lookup. If the same
solution and project revisions produce different directories or producers for the same tuple,
mark the directory `CONFLICT` instead of switching to the latest build. Hash any additional build
inputs affecting provenance in the pack. An old association whose solution or project changed is stale.

## Automatic regeneration after Rename

After Slang Rename in a trusted project, the plugin checks the indexed records of **already-built**
variants for the renamed shader. It runs a dedicated producer only for a matching pack, regardless
of the active IDE configuration. Unbuilt and unrelated variants are skipped. Usage lookup still
reads only the chosen variant and never runs a build.

In CLion/CMake projects, the plugin considers loaded profiles and known build directories from
profiles, presets, and conventional workspace paths. It does not recursively scan the project.
The CMake File API must identify one generation-only utility dependency of the C++ target that
owns the exact pack. Declare the pack as an `OUTPUT` of the generating command, and list it under
both `DEPENDS` and `SOURCES` on the utility target when the output uses generator expressions
(`SOURCES` exposes the evaluated path in the File API, including for multi-config generators).
If the model cannot identify a unique producer, the pack stays stale rather than running the host
or default target.

In trusted Windows Rider/MSBuild projects, the build-published association can enable automatic
regeneration without a separate registration file. The project must expose a generation-only
target named `jb_slanglsp_reflection_*` and set the evaluated `JbSlangLspReflectionTarget` property
to that target's name. Use the [fixture publisher](../dev/playground-msbuild/PublishAssociation.ps1)
as an example. The plugin checks published associations for the root solutions' mapped project
tuples. It skips stale or conflicting associations, including a pack claimed by different solution
contexts. It uses MSBuild 17.8 or later to verify the project configuration, platform, `IntDir`, and target
without running any targets. Only then does it invoke the dedicated target directly on the
`.vcxproj`, never on the solution. The target may build the generator but must not build the host.
It must be runnable with the project tuple, standard solution path properties, and the IDE's
environment; the plugin does not replay arbitrary flags from a prior build.

For build rules that do not expose this information, project authors can optionally provide
`.jb-slanglsp-rename.json` at the workspace root. This is project configuration, not generated metadata:

```json
{
  "version": 1,
  "tasks": [
    {
      "system": "cmake",
      "buildDirectory": "out/debug",
      "configuration": "Debug",
      "generationTarget": "jb_slanglsp_reflection_lighting",
      "inputs": ["shaders/lighting.slang", "tools/lighting_types.hpp"]
    },
    {
      "system": "msbuild",
      "project": "host/Graphics.vcxproj",
      "configuration": "Checked",
      "platform": "x64",
      "generationTarget": "jb_slanglsp_reflection_lighting",
      "inputs": ["shaders/lighting.slang", "tools/lighting_types.hpp"]
    }
  ]
}
```

Each entry names one exact variant and one **generation-only** target. The target name must start
with `jb_slanglsp_reflection_`. Create a dedicated wrapper if needed; it must not depend on a host
target or the default build. Declare the inputs that affect the target, using workspace-relative
paths. For large projects, group inputs by independent generation target. The target's own build
dependencies determine what needs to be regenerated within that group.

CMake needs an existing out-of-source build directory (workspace-relative or absolute); the plugin
checks its configured build type before running it. MSBuild runs on Windows and addresses the
`.vcxproj` directly with the evaluated *project* configuration and platform, not a solution build.
If the build tool isn't on `PATH`, use an optional absolute `"tool"` path to `cmake` or `MSBuild.exe`.

After a successful Slang Rename or a native C++ rename of a registered editable input, the plugin
automatically queues only the tasks that consume the changed files. It merges pending changes and
runs the selected targets one at a time. It saves only changed input documents whose revisions
still match the rename, and skips a task if other known inputs have unsaved edits. There is no
full-build fallback and neither lookup action starts generation.

For generated C++ names, register and rename the **editable C++ definition the generator reads**.
Renaming the generated header directly does not update the generator's input; the plugin won't
guess at or rewrite that input. The project generator remains the only writer of the pack.

## Contract tests

On Linux, use an installed CMake, Ninja, C++ compiler and the repository's provisioned `slangc`:

```sh
SLANG_REFLECTION_CONTRACT_TESTS=1 ./gradlew --offline -PcoreOnly=true \
    :test -x installTestDependencies --tests '*ReflectionMetadataContractTest'
```

The test-only CMake fixture builds the same Slang shader and C++ target in Debug and Release, producing
different C++ type names. Its pack emitter does not use the bundled header. A legacy decoy catches accidental
fallback. These tests remain opt-in because they require a native CMake build.
Rider and MSBuild integration tests will run on Windows only; this suite does not start Rider.

## Lookup benchmark

Before removing the legacy lookup, the direct reader was measured on the Linux CMake contract fixture
with 200 samples per case. Cold means fresh plugin lookup caches with the OS page cache and IDE VFS
already warm. The timings exclude the C++ IDE's usage search:

| Lookup | p50 | p95 |
| --- | ---: | ---: |
| Cold hit | 242 µs | 876 µs |
| Warm hit | 61 µs | 76 µs |
| Missing pack | 15 µs | 19 µs |

Rerun with `SLANG_REFLECTION_CONTRACT_TESTS=1 SLANG_REFLECTION_BENCHMARK=1` and
`--tests '*ReflectionMetadataContractTest.benchmarkDirectLookup'`. This is a small fixture, not a
cold-disk or large-project throughput measurement.
