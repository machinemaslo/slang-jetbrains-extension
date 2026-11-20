package slanglsp.editor.navigation.cpp

import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiFile
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.usageView.UsageInfo
import com.intellij.usages.UsageInfo2UsageAdapter
import com.intellij.usages.UsageTarget
import com.intellij.usages.UsageView
import com.intellij.usages.UsageViewManager
import com.intellij.usages.UsageViewPresentation
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import slanglsp.editor.NativeServerSupport

internal class ReflectedUsageExclusionsTest {
    @Test
    fun skipsGeneratedCopiesInNativeStyleUsageViewsWithoutChangingOtherSearches() {
        val fixture = NativeServerSupport.isolatedFixture("ReflectedUsageExclusions")
        runInEdtAndWait { fixture.setUp() }
        val views = mutableListOf<UsageView>()
        try {
            lateinit var generated: PsiFile
            lateinit var inactive: PsiFile
            lateinit var application: PsiFile
            lateinit var view: UsageView
            lateinit var ordinary: UsageView
            runInEdtAndWait {
                generated = fixture.addFileToProject("generated/debug/reflection.slang", "struct RenderPageCacheGpu {};")
                inactive = fixture.addFileToProject("generated/release/reflection.slang", "struct RenderPageCacheGpu {};")
                application = fixture.addFileToProject("application.slang", "RenderPageCacheGpu value;")
                val manager = UsageViewManager.getInstance(fixture.project)
                // Nova's UsageTargets supply names but intentionally return no files or PSI elements.
                view = manager.createUsageView(arrayOf(target("RenderPageCacheGpu")), emptyArray(), UsageViewPresentation(), null)
                ordinary = manager.createUsageView(arrayOf(target("SomethingElse")), emptyArray(), UsageViewPresentation(), null)
                views.addAll(listOf(view, ordinary))
            }
            val generatedUsage = runReadActionBlocking { UsageInfo2UsageAdapter(UsageInfo(generated, 7, 25)) }
            val inactiveUsage = runReadActionBlocking { UsageInfo2UsageAdapter(UsageInfo(inactive, 7, 25)) }
            val realUsage = runReadActionBlocking { UsageInfo2UsageAdapter(UsageInfo(application, 0, 18)) }
            val exclusions = fixture.project.service<ReflectedUsageExclusions>()
            exclusions.prepare(location(generated), "RenderPageCacheGpu", setOf(generated.virtualFile, inactive.virtualFile))
            runReadActionBlocking {
                ordinary.appendUsage(generatedUsage)
                view.appendUsage(generatedUsage)
                view.appendUsage(inactiveUsage)
                view.appendUsage(realUsage)
                assertEquals(setOf(realUsage), view.usages)
                assertEquals(setOf(generatedUsage), ordinary.usages)
            }
        } finally {
            runInEdtAndWait {
                views.forEach { Disposer.dispose(it) }
                fixture.tearDown()
            }
        }
    }

    @Test
    fun invalidatesAConfirmedMappingWhenSwitchingProfiles() {
        val fixture = NativeServerSupport.isolatedFixture("ReflectedProfileCache")
        runInEdtAndWait { fixture.setUp() }
        try {
            lateinit var shader: PsiFile
            lateinit var generated: PsiFile
            runInEdtAndWait {
                shader = fixture.addFileToProject("shader.slang", "struct Params {};")
                generated = fixture.addFileToProject("generated.slang", "struct Params {};")
            }
            val root = java.nio.file.Path.of(fixture.tempDirPath)
            val debug = ActiveCppConfiguration("Debug", root, emptySet(), emptySet())
            val release = debug.copy(name = "Release", buildDirectory = root.resolve("other"))
            val cache = fixture.project.service<ReflectionMappingCache>()
            runReadActionBlocking {
                val stamp = FileDocumentManager.getInstance().getDocument(shader.virtualFile)!!.modificationStamp
                cache.put(shader.virtualFile, 7, stamp, location(generated), debug, setOf(generated.virtualFile))
                assertTrue(cache.getValid(shader.virtualFile, 7, stamp, debug)!!.generatedFiles.contains(generated.virtualFile))
                assertNull(cache.getValid(shader.virtualFile, 7, stamp, release))
            }
        } finally {
            runInEdtAndWait { fixture.tearDown() }
        }
    }

    private fun location(file: PsiFile): ReflectionSearch.Location = runReadActionBlocking {
        val document = FileDocumentManager.getInstance().getDocument(file.virtualFile)!!
        ReflectionSearch.Location(file.virtualFile, document.modificationStamp, 7, 1)
    }

    private fun target(name: String): UsageTarget = object : UsageTarget {
        override fun getName(): String = name
        override fun getPresentation() = null
        override fun isValid(): Boolean = true
        override fun getFiles(): Array<VirtualFile> = emptyArray()
        override fun update() {}
        override fun findUsages() {}
        override fun navigate(requestFocus: Boolean) {}
        override fun canNavigate(): Boolean = false
        override fun canNavigateToSource(): Boolean = false
    }
}
