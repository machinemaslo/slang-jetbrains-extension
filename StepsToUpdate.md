1. Make the code changes and update `version` in `build.gradle.kts` and the release notes in `plugin.xml`.
2. If changing platform or LSP4IJ support, update the pinned dependencies, verifier IDE matrix, and README together. Do not widen `untilBuild` before verifying the new branch.
3. Run `./gradlew check` using JDK 21 and the default SDK. Run the optional real-server tests with `SLANGD_TEST_EXECUTABLE` when changing language-server behavior.
4. Inspect `build/reports/pluginVerifier/`, including the resolved dependencies and any experimental API notices.
5. Upload the verified ZIP from `build/distributions/`.
