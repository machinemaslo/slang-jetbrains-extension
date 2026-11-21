import java.nio.file.Files
import java.nio.file.Path
import org.gradle.testfixtures.ProjectBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PreparePlaygroundTest {
    @get:Rule val directory = TemporaryFolder()

    @Test
    fun removesOnlyTheTemplateModuleAndPreservesClionWorkspace() {
        val templates = directory.newFolder("idea-template").toPath()
        write(templates, ".idea/modules.xml", "IDEA module list")
        write(templates, ".idea/playground.iml", "IDEA module")
        write(templates, ".idea/workspace.xml", "IDEA workspace")
        write(templates, "main.slang", "sample")
        val cpp = directory.newFolder("cpp-template").toPath()
        write(cpp, ".idea/misc.xml", "CMake workspace")
        val playground = directory.newFolder("staging").toPath()
        write(playground, ".idea/modules.xml", "IDEA module list")
        write(playground, ".idea/playground.iml", "IDEA module")
        write(playground, ".idea/workspace.xml", "CLion user's selected profile")
        val task = prepareTask(templates, cpp, playground)

        task.prepare()
        assertFalse(Files.exists(playground.resolve(".idea/modules.xml")))
        assertFalse(Files.exists(playground.resolve(".idea/playground.iml")))
        assertEquals("CLion user's selected profile", Files.readString(playground.resolve(".idea/workspace.xml")))
        assertTrue(Files.isRegularFile(playground.resolve("main.slang")))
        assertTrue(Files.isRegularFile(playground.resolve(".idea/misc.xml")))

        write(playground, ".idea/modules.xml", "CLion module list")
        write(playground, ".idea/playground.iml", "CLion module")
        task.prepare()
        assertEquals("CLion module list", Files.readString(playground.resolve(".idea/modules.xml")))
        assertEquals("CLion module", Files.readString(playground.resolve(".idea/playground.iml")))
    }

    private fun prepareTask(templates: Path, cpp: Path, playground: Path): PreparePlayground {
        val project = ProjectBuilder.builder().withProjectDir(directory.newFolder("project")).build()
        val windows = System.getProperty("os.name").startsWith("Windows")
        val executable = Path.of(System.getProperty("java.home"), "bin", if (windows) "java.exe" else "java")
        return project.tasks.register("prepare", PreparePlayground::class.java).get().apply {
            templateDirectory.set(templates.toFile())
            cppTemplateDirectory.set(cpp.toFile())
            playgroundDirectory.set(playground.toFile())
            slangdCandidate.set(executable.toString())
            clangFormatCandidate.set(executable.toString())
            searchPath.set("")
        }
    }

    private fun write(root: Path, relative: String, text: String) {
        val file = root.resolve(relative)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }
}
