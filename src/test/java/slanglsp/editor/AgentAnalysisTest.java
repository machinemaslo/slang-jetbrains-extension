package slanglsp.editor;

import com.intellij.openapi.application.WriteAction;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.roots.ModuleRootModificationUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.testFramework.EdtTestUtil;
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import slanglsp.TestDependencies;
import slanglsp.settings.ProjectSettings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

class AgentAnalysisTest {
    @Test
    void compilerToolUsesUnsavedEditorTextAndRejectsEscapingPaths(@TempDir Path temporary) throws Exception {
        Path root = temporary.toRealPath();
        var factory = IdeaTestFixtureFactory.getFixtureFactory();
        var fixture = factory.createFixtureBuilder("SlangAgentAnalysis", root, false).getFixture();
        EdtTestUtil.runInEdtAndWait(fixture::setUp);
        try {
            var project = fixture.getProject();
            Path source = Path.of(Objects.requireNonNull(project.getBasePath())).resolve("shader.slang");
            String saved = "int shade() { return 1; }\n";
            Files.writeString(source, saved);
            var state = ProjectSettings.getInstance(project).getState();
            state.explicitSlangdLocation = Path.of(TestDependencies.slangd()).getParent().toString();
            state.enableSearchingSubDirectoriesOfWorkspace = false;
            ProjectSettings.getInstance(project).loadState(state);
            assertThrows(IllegalArgumentException.class, () -> AgentAnalysis.checkFiles(project, List.of("../shader.slang")));
            assertEquals("shader.slang: clean", AgentAnalysis.checkFiles(project, List.of("shader.slang")));

            var file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(source);
            assertNotNull(file);
            var document = ReadActions.compute(() -> FileDocumentManager.getInstance().getDocument(file));
            assertNotNull(document);
            EdtTestUtil.runInEdtAndWait(() ->
                    WriteAction.run(() -> document.setText("int shade() { return missingName; }\n")));
            String diagnostics = AgentAnalysis.checkFiles(project, List.of("shader.slang", "shader.slang"));
            assertTrue(diagnostics.contains("shader.slang:1:") && diagnostics.contains("missingName"), diagnostics);
            assertEquals(saved, Files.readString(source));
        } finally {
            EdtTestUtil.runInEdtAndWait(fixture::tearDown);
        }
    }

    @Test
    void symbolToolUsesSlangdToResolveTheCorrectOverload(@TempDir Path temporary) throws Exception {
        var factory = IdeaTestFixtureFactory.getFixtureFactory();
        var fixture = factory.createFixtureBuilder("SlangAgentSymbols", temporary.toRealPath(), false).getFixture();
        EdtTestUtil.runInEdtAndWait(fixture::setUp);
        try {
            var project = fixture.getProject();
            String basePath = Objects.requireNonNull(project.getBasePath());
            Path root = Path.of(basePath);
            EdtTestUtil.runInEdtAndWait(() -> {
                var module = WriteAction.compute(() -> ModuleManager.getInstance(project)
                        .newModule(root.resolve("shaders.iml"), "EMPTY_MODULE"));
                ModuleRootModificationUtil.addContentRoot(module, basePath);
            });
            var state = ProjectSettings.getInstance(project).getState();
            state.explicitSlangdLocation = Path.of(TestDependencies.slangd()).getParent().toString();
            ProjectSettings.getInstance(project).loadState(state);
            NativeServerSupport.enable(project);
            Files.writeString(root.resolve("shading.slang"), """
                    module shading;
                    public float shade(float x) { return x; }
                    public int shade(int x) { return x; }
                    """);
            Files.writeString(root.resolve("shader.slang"), """
                    import shading;
                    float test() { return shade(1.0); }
                    """);
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root.resolve("shading.slang"));
            String result = AgentAnalysis.symbol(project, "shader.slang", 2, 23, true);
            assertTrue(result.contains("Definition: shading.slang:2:") && result.contains("float shade"), result);
            assertFalse(result.contains("Definition: shading.slang:3:"), result);
            assertTrue(result.contains("Reference: shader.slang:2:23"), result);
        } finally {
            try {
                NativeServerSupport.stop(fixture.getProject());
            } finally {
                EdtTestUtil.runInEdtAndWait(fixture::tearDown);
            }
        }
    }
}
