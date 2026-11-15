1. Make the code changes and update `version` in `build.gradle.kts` and the release notes in `plugin.xml`.
2. If changing platform or LSP4IJ support, update the pinned dependencies, verifier IDE matrix, and README together. Do not widen `untilBuild` before verifying the new branch.
3. Run `./gradlew check` using JDK 21 and the default SDK, with the `VCPKG_ROOT` environment variable pointing to a bootstrapped vcpkg checkout. Tests automatically install the pinned Slang and clang-format dependencies and include real-server verification. For tests alone, IDEA's shared **Run Tests** configuration shows results and logs in the Run tool window.
4. Inspect `build/reports/pluginVerifier/`, including the resolved dependencies and any experimental API notices.
5. Upload the verified ZIP from `build/distributions/`.
