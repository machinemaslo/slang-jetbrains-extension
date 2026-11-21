# Rider and MSBuild reflection fixture (Windows)

This fixture requires Visual Studio's MSBuild with the **Desktop development with C++** workload (v143,
Windows SDK, x64) and the Slang 2026.22+ SDK. On GitHub Actions the Windows job of **Build and verify**
runs the native solution fixture after provisioning the pinned SDK.

The solution choices deliberately do not match the evaluated C++ project identities:

| Solution configuration | Project configuration | Resolved `IntDir` (under `host`) | C++ declaration |
| --- | --- | --- | --- |
| `Editor\|Mixed Platforms` | `Checked\|x64` | `artifacts/intermediate/Checked/x64` | `CheckedMaterial` |
| `Shipping\|Mixed Platforms` | `Optimized\|x64` | `artifacts/intermediate/Optimized/x64` | `OptimizedMaterial` |

On Windows, select **Run Plugin (Rider)** or use `.\gradlew.bat runPluginRider`. This uses your installed
Toolbox Rider and opens `build/playgroundRider/Reflection.sln` with the development plugin. It does not
build the solution. MSBuild uses the repository's `vcpkg_installed/x64-windows` SDK if present.
For another SDK, set `SlangSdk` in the environment before launching to the root containing the SDK's
`include`, `lib`, and `bin` (or vcpkg's `tools/shader-slang`). Make `slangd.exe` available on `PATH`
if it isn't provisioned in the repository.

Build both solution configurations in Rider. Look up `PlaygroundMaterial` in `reflection.slang`
with **Find C++ Usages** and **C++ View**. When asked, pick the displayed solution-to-project mapping.
Confirm each native search lists usages of its corresponding type, and that the quick popup replaces
the stock popup without selecting a C++ tab until a result is chosen. Delete one generated pack and
repeat: the selected variant must report missing instead of falling back to the other build.

Normal builds also publish the generation-only target. In a trusted project, rename the Slang struct
and wait for **Regenerating Reflection Metadata** to finish. Both previously built variants refresh
automatically, regardless of Rider's active configuration. An unbuilt variant is skipped. No separate
rename registration is needed.

The hosted GitHub test checks both mappings and runs the dedicated target for each built tuple after
renaming the shader. It checks that both packs refresh and neither host executable changes.
It does not exercise the live native handoff, which needs an interactive Windows Rider session.
