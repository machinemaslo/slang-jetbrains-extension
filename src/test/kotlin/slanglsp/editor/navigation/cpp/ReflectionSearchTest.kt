package slanglsp.editor.navigation.cpp

import com.intellij.navigation.ChooseByNameContributor
import com.intellij.navigation.ChooseByNameContributorEx
import com.intellij.navigation.NavigationItem
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.WriteAction
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.fileTypes.ExactFileNameMatcher
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.impl.FakePsiElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.Processor
import com.intellij.util.indexing.FindSymbolParameters
import com.intellij.util.indexing.IdFilter
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.runInEdtAndWait
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import slanglsp.editor.NativeServerSupport

internal class ReflectionSearchTest {
    @Test
    fun findsProjectCppCodeThroughTheWordIndex() {
        val fixture = NativeServerSupport.isolatedFixture("ReflectionSearch")
        val fileName = "slang-reflection-test.cpp"
        val matcher = ExactFileNameMatcher(fileName)
        val fileTypes = FileTypeManager.getInstance()
        runInEdtAndWait {
            fixture.setUp()
            ModuleRootModificationUtil.addContentRoot(fixture.module, fixture.tempDirPath)
            // IDEA has no C++ file type; give the fixture an indexable type.
            WriteAction.run<RuntimeException> { fileTypes.associate(PlainTextFileType.INSTANCE, matcher) }
        }

        try {
            val source = """
                #include <slang.h>
                void check(slang::ProgramLayout* program) {
                    auto type = program->findTypeByName("Material");
                    auto layout = program->getTypeLayout(type);
                    assert(layout->getSize() == sizeof(render::MaterialData));
                }
            """.trimIndent()

            runInEdtAndWait { fixture.addFileToProject(fileName, source) }
            val match = ReflectionSearch.find(fixture.project, "Material").matches.single()

            assertEquals("render::MaterialData", match.type)
            assertEquals(fileName, match.location.file.name)
            assertEquals(source.indexOf("MaterialData"), match.location.offset)
            assertEquals(5, match.location.line)
        } finally {
            runInEdtAndWait {
                WriteAction.run<RuntimeException> { fileTypes.removeAssociation(PlainTextFileType.INSTANCE, matcher) }
                fixture.tearDown()
            }
        }
    }

    @Test
    fun findsAlignedGeneratedDefinitionAndAnUnrelatedNameMappedByLine(@TempDir root: Path) {
        val fixture = NativeServerSupport.isolatedFixture("GeneratedStructs")
        val matcher = ExactFileNameMatcher("shader_reflection.inc")
        runInEdtAndWait {
            fixture.setUp()
            ModuleRootModificationUtil.addContentRoot(fixture.module, fixture.tempDirPath)
            WriteAction.run<RuntimeException> { FileTypeManager.getInstance().associate(PlainTextFileType.INSTANCE, matcher) }
        }
        try {
            val shader = "public struct LightingParams { public float3 sun_direction; public float power; };"
            val path = root.resolve("lighting.slang")
            val emitted = """
                #line 1 "${path.toString().replace("\\", "/")}"
                struct UnrelatedName { int f1; int f2; };
            """.trimIndent()
            val include = Files.writeString(root.resolve("shader_reflection.inc"),
                "struct alignas(16) LightingParams { glm::vec3 sun_direction = {}; f32 power = {}; };")
            val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(include)!!
            runInEdtAndWait {
                fixture.addFileToProject("gen/shader_reflection.inc", emitted)
                fixture.addFileToProject("shader.cmm", "#include \"shader_reflection.inc\"\n")
                // Exercise the Go to Class bridge without depending on Nova classes in the minimum IDEA SDK.
                // The include is outside the project word index and is reachable only from this contributor.
                ChooseByNameContributor.CLASS_EP_NAME.point.registerExtension(object : ChooseByNameContributorEx {
                    override fun processNames(processor: Processor<in String>, scope: GlobalSearchScope, filter: IdFilter?) {
                        processor.process("LightingParams")
                    }

                    override fun processElementsWithName(name: String, processor: Processor<in NavigationItem>, parameters: FindSymbolParameters) {
                        if (name != "LightingParams") return
                        val file = PsiManager.getInstance(fixture.project).findFile(virtualFile) ?: return
                        processor.process(object : FakePsiElement() {
                            override fun getName() = "LightingParams"
                            override fun getParent() = file
                            override fun getContainingFile() = file
                        })
                    }
                }, fixture.testRootDisposable)
            }
            val indicator = EmptyProgressIndicator()
            val matches = ProgressManager.getInstance().runProcess<List<ReflectionSearch.Match>>({
                GeneratedStructLookup.find(fixture.project, path, shader, shader.indexOf("LightingParams"), indicator)
            }, indicator)
            assertEquals(
                setOf("LightingParams" to false, "UnrelatedName" to true),
                matches.map { it.type to it.typeOnly }.toSet(),
            )
            assertEquals(emitted.indexOf("UnrelatedName"), matches.single { it.typeOnly }.location.offset)
            assertEquals(virtualFile, matches.single { !it.typeOnly }.location.file)
        } finally {
            runInEdtAndWait {
                WriteAction.run<RuntimeException> { FileTypeManager.getInstance().removeAssociation(PlainTextFileType.INSTANCE, matcher) }
                fixture.tearDown()
            }
        }
    }

    @Test
    fun actionIsRegisteredAndRecognizesStructDeclarationsNotReferences() {
        val factory = IdeaTestFixtureFactory.getFixtureFactory()
        val fixture = factory.createCodeInsightFixture(factory.createLightFixtureBuilder("ReflectionAction").fixture)
        runInEdtAndWait { fixture.setUp() }

        try {
            val source = "struct /* heading */ Material {}; Material useMaterial;"
            runInEdtAndWait {
                val file = fixture.addFileToProject("material.slang", source)
                runReadActionBlocking {
                    assertEquals("Material", structNameAt(file, source.indexOf("Material")))
                    assertEquals("Material", structNameAt(file, 1))
                    assertNull(structNameAt(file, source.indexOf("Material", source.indexOf(';'))))
                }

                val actions = ActionManager.getInstance()
                assertInstanceOf(FindCppUsagesAction::class.java, actions.getAction("Slang.FindCppUsages"))
                assertEquals(
                    listOf("Slang.FindCppUsages"),
                    actions.getActionIdList("Slang.").filter { it.endsWith("CppUsages") },
                )
            }
        } finally {
            runInEdtAndWait { fixture.tearDown() }
        }
    }
}
