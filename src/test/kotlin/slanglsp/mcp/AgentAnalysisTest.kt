package slanglsp.mcp

import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.runInEdtAndWait
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import slanglsp.TestDependencies
import slanglsp.editor.NativeServerSupport
import slanglsp.mcp.AgentAnalysis.checkFiles
import slanglsp.mcp.AgentAnalysis.symbol
import slanglsp.replaceAllText
import slanglsp.settings.project.ProjectSettings

internal class AgentAnalysisTest {
    @Test
    @Throws(Exception::class)
    fun compilerToolUsesUnsavedEditorTextAndRejectsEscapingPaths(@TempDir temporary: Path) {
        val root = temporary.toRealPath()
        val factory = IdeaTestFixtureFactory.getFixtureFactory()
        val fixture = factory.createFixtureBuilder("SlangAgentAnalysis", root, false).getFixture()
        runInEdtAndWait { fixture.setUp() }
        try {
            val project = fixture.project
            val source = Path.of(checkNotNull(project.basePath)).resolve("shader.slang")
            val saved = "int shade() { return 1; }\n"
            Files.writeString(source, saved)
            val state = ProjectSettings.getInstance(project).getState()
            state.explicitSlangdLocation = Path.of(TestDependencies.slangd()).parent.toString()
            state.enableSearchingSubDirectoriesOfWorkspace = false
            ProjectSettings.getInstance(project).loadState(state)
            Assertions.assertThrows<IllegalArgumentException?>(IllegalArgumentException::class.java) {
                checkFiles(project, mutableListOf("../shader.slang"))
            }
            Assertions.assertEquals("shader.slang: clean", checkFiles(project, mutableListOf("shader.slang")))

            val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(source)
            Assertions.assertNotNull(file)
            val document = runReadActionBlocking { FileDocumentManager.getInstance().getDocument(file!!) }
            Assertions.assertNotNull(document)
            runInEdtAndWait {
                WriteAction.run<RuntimeException?> {
                    document!!.replaceAllText("int shade() { return missingName; }\n")
                }
            }
            val diagnostics = checkFiles(project, mutableListOf("shader.slang", "shader.slang"))
            Assertions.assertTrue(
                diagnostics.contains("shader.slang:1:") && diagnostics.contains("missingName"), diagnostics)
            Assertions.assertEquals(saved, Files.readString(source))
        } finally {
            runInEdtAndWait { fixture.tearDown() }
        }
    }

    @Test
    @Throws(Exception::class)
    fun symbolToolUsesSlangdToResolveTheCorrectOverload(@TempDir temporary: Path) {
        val factory = IdeaTestFixtureFactory.getFixtureFactory()
        val fixture = factory.createFixtureBuilder("SlangAgentSymbols", temporary.toRealPath(), false).getFixture()
        runInEdtAndWait { fixture.setUp() }
        try {
            val project = fixture.project
            val basePath = checkNotNull(project.basePath)
            val root = Path.of(basePath)
            runInEdtAndWait {
                val module =
                    WriteAction.compute<Module, RuntimeException?> {
                        ModuleManager.getInstance(project).newModule(root.resolve("shaders.iml"), "EMPTY_MODULE")
                    }
                ModuleRootModificationUtil.addContentRoot(module, basePath)
            }
            val state = ProjectSettings.getInstance(project).getState()
            state.explicitSlangdLocation = Path.of(TestDependencies.slangd()).parent.toString()
            ProjectSettings.getInstance(project).loadState(state)
            NativeServerSupport.enable(project)
            Files.writeString(
                root.resolve("shading.slang"),
                """
                    module shading;
                    public float shade(float x) { return x; }
                    public int shade(int x) { return x; }
                    
                    """
                    .trimIndent())
            Files.writeString(
                root.resolve("shader.slang"),
                """
                    import shading;
                    float test() { return shade(1.0); }
                    
                    """
                    .trimIndent())
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root.resolve("shading.slang"))
            val result = symbol(project, "shader.slang", 2, 23, references = true)
            Assertions.assertTrue(
                result.contains("Definition: shading.slang:2:") && result.contains("float shade"), result)
            Assertions.assertFalse(result.contains("Definition: shading.slang:3:"), result)
            Assertions.assertTrue(result.contains("Reference: shader.slang:2:23"), result)
        } finally {
            try {
                NativeServerSupport.stop(fixture.project)
            } finally {
                runInEdtAndWait { fixture.tearDown() }
            }
        }
    }
}
