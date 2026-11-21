# Slang and C++ reflection playground

1. Launch `./gradlew runPluginCLion`. In CLion, load the CMake project if prompted, then enable the
   **Reflection Playground** and **Reflection Playground Release** Ninja profiles.
2. Build `reflection_playground` and `reflection_preview` in both profiles. The generator uses the installed
   Slang SDK (2026.22 or newer) to compile SPIR-V and get the type layout and declaration location in the same
   session. It publishes the pack through `jb-slanglsp-reflection-meta.hpp`; the generated header has no `#line`.
   Set `slang_DIR` if CMake cannot find the SDK next to `slangc`.
3. Open `reflection.slang` and Ctrl-click **`PlaygroundMaterial`** on its declaration (Cmd-click on macOS).
   Click **C++ View** above **Show Usages**. The C++ popup should occupy the same position, with
   `reflection.slang` still selected until you choose a result.
4. With the Debug profile selected, the C++ type is `MaterialConstants` for `reflection_playground` and
   `PreviewConstants` for `reflection_preview`. Switch to the Release profile and look for the same types with
   the `Release` suffix. Each variant's `main.cpp` and `material.cpp` include `bindings.hpp` and name its type
   directly. Right-click the Slang declaration and run **Find C++ Usages** to check the full Find
   view. Test an unbuilt profile separately: it must report missing metadata, not use another profile's pack.
5. Rename the Slang struct with **Refactor → Rename**. The plugin saves the renamed shader and regenerates
   its packs in every target and profile you already built. Wait for **Regenerating Reflection Metadata**
   to finish, then switch profiles to check C++ View. An unbuilt profile stays unbuilt. This requires a
   trusted project but needs no separate registration and does not rebuild the C++ apps.

The sample does not require a GPU. Gradle does not install CLion or the Slang SDK for this launcher.
