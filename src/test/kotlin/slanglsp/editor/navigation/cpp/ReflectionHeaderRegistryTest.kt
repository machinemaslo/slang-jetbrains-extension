package slanglsp.editor.navigation.cpp

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.runInEdtAndWait
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import slanglsp.editor.NativeServerSupport

internal class ReflectionHeaderRegistryTest {
    @Test
    fun installsTheBundledHelperAndFindsItWithAFreshCacheAfterMovingIt() {
        val fixture = NativeServerSupport.isolatedFixture("SlangReflectionHeader")
        runInEdtAndWait { fixture.setUp() }
        try {
            lateinit var root: VirtualFile
            lateinit var initialFolder: VirtualFile
            lateinit var newFolder: VirtualFile
            runInEdtAndWait {
                ModuleRootModificationUtil.addContentRoot(fixture.module, fixture.tempDirPath)
                initialFolder = fixture.addFileToProject("include/initial.txt", "").virtualFile.parent
                newFolder = fixture.addFileToProject("reflect/destination.txt", "").virtualFile.parent
                root = initialFolder.parent
            }
            val project = fixture.project
            val registry = ReflectionHeaderRegistry(project)
            assertEquals(ReflectionHeaderRegistry.Lookup.Missing, runReadActionBlocking { registry.find(root) })

            lateinit var header: VirtualFile
            runInEdtAndWait {
                WriteCommandAction.runWriteCommandAction(project) {
                    header = registry.create(root, initialFolder)
                }
            }
            val helper = header.contentsToByteArray().decodeToString()
            assertTrue(helper.startsWith("#ifndef JB_SLANGLSP_REFLECTION_META_HPP"))
            assertTrue(helper.contains("write_reflection_meta"))
            assertEquals(ReflectionHeaderRegistry.Lookup.Found(header), runReadActionBlocking { registry.find(root) })

            runInEdtAndWait {
                WriteCommandAction.runWriteCommandAction(project) {
                    header.move(this, newFolder)
                }
            }
            DumbService.getInstance(project).waitForSmartMode()

            assertEquals(newFolder, header.parent)
            assertEquals(ReflectionHeaderRegistry.Lookup.Found(header), runReadActionBlocking { registry.find(root) })
            val freshRegistry = ReflectionHeaderRegistry(project)
            assertEquals(ReflectionHeaderRegistry.Lookup.Found(header), runReadActionBlocking { freshRegistry.find(root) })
            assertEquals(helper, header.contentsToByteArray().decodeToString())
        } finally {
            runInEdtAndWait { fixture.tearDown() }
        }
    }

    @Test
    fun onlyAnExplicitUpgradeChangesAnEmptyExistingFile() {
        val fixture = NativeServerSupport.isolatedFixture("SlangReflectionHeaderUpgrade")
        runInEdtAndWait { fixture.setUp() }
        try {
            runInEdtAndWait {
                val project = fixture.project
                ModuleRootModificationUtil.addContentRoot(fixture.module, fixture.tempDirPath)
                val registry = ReflectionHeaderRegistry(project)
                val name = ReflectionHeaderRegistry.HEADER_NAME
                val empty = fixture.addFileToProject("a/$name", "").virtualFile
                val custom = fixture.addFileToProject("b/$name", "// project-owned helper\n").virtualFile
                val root = empty.parent.parent
                WriteCommandAction.runWriteCommandAction(project) {
                    assertEquals(empty, registry.create(root, empty.parent))
                    assertEquals(0L, empty.length)
                    assertTrue(registry.canUpgrade(root, empty))
                    registry.upgradeEmpty(root, empty)
                    assertTrue(empty.length > 0)

                    assertEquals(custom, registry.create(root, custom.parent))
                    assertFalse(registry.canUpgrade(root, custom))
                    assertEquals("// project-owned helper\n", custom.contentsToByteArray().decodeToString())
                }
            }
        } finally {
            runInEdtAndWait { fixture.tearDown() }
        }
    }

    @Test
    fun comparesVersionsAndOnlyReplacesTheConfirmedSavedRevision() {
        val fixture = NativeServerSupport.isolatedFixture("SlangReflectionHeaderVersion")
        runInEdtAndWait { fixture.setUp() }
        try {
            runInEdtAndWait {
                val project = fixture.project
                ModuleRootModificationUtil.addContentRoot(fixture.module, fixture.tempDirPath)
                val registry = ReflectionHeaderRegistry(project)
                val name = ReflectionHeaderRegistry.HEADER_NAME
                val old = "#define JB_SLANGLSP_REFLECTION_META_VERSION 1\n"
                val file = fixture.addFileToProject("include/$name", old).virtualFile
                val custom = fixture.addFileToProject("custom/$name", "// unversioned project helper\n").virtualFile
                val newer = fixture.addFileToProject("future/$name", old.replace(" 1", " 999999")).virtualFile
                val root = file.parent.parent

                assertNull(registry.outdated(root, custom))
                assertNull(registry.outdated(root, newer))
                val first = checkNotNull(registry.outdated(root, file))
                assertEquals(1, first.installedVersion)
                assertTrue(first.bundledVersion > first.installedVersion)

                val modified = old + "// custom edit\n"
                WriteCommandAction.runWriteCommandAction(project) {
                    file.setBinaryContent(modified.encodeToByteArray())
                    assertFalse(registry.replaceOutdated(root, first))
                }
                assertEquals(modified, file.contentsToByteArray().decodeToString())
                val confirmed = checkNotNull(registry.outdated(root, file))
                val documents = FileDocumentManager.getInstance()
                val document = checkNotNull(documents.getDocument(file))
                WriteCommandAction.runWriteCommandAction(project) {
                    document.insertString(document.textLength, "// unsaved\n")
                    assertFalse(registry.replaceOutdated(root, confirmed))
                }
                assertEquals(modified, file.contentsToByteArray().decodeToString())
                documents.reloadFromDisk(document)

                WriteCommandAction.runWriteCommandAction(project) {
                    assertTrue(registry.replaceOutdated(root, confirmed))
                }
                assertTrue(file.contentsToByteArray().decodeToString()
                    .contains("#define JB_SLANGLSP_REFLECTION_META_VERSION ${confirmed.bundledVersion}"))
                assertNull(registry.outdated(root, file))
            }
        } finally {
            runInEdtAndWait { fixture.tearDown() }
        }
    }
}
