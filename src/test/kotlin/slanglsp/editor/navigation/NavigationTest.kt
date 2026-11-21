package slanglsp.editor.navigation

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.ex.IdeDocumentHistory
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.usageView.UsageInfo
import com.intellij.util.Processor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import slanglsp.TestDependencies
import slanglsp.editor.NativeServerSupport
import slanglsp.editor.documentation.BuiltinHoverDocumentation
import slanglsp.editor.documentation.HoverDocumentationProvider
import slanglsp.editor.navigation.UsageSearch.candidateFiles
import slanglsp.editor.navigation.UsageSearch.process
import slanglsp.server.BuiltinFiles
import slanglsp.server.BuiltinFiles.BuiltinFile
import slanglsp.server.nativeclient.runningSlangClient

internal class NavigationTest {
    @Test
    @Timeout(90)
    @Throws(Exception::class)
    fun builtinNavigationAndIndexedUsagesIncludeUnsavedEditsButExcludeShadowedNames() {
        val executable = TestDependencies.slangd()
        val fixture = NativeServerSupport.isolatedFixture("SlangNavigationTest")
        runInEdtAndWait { fixture.setUp() }

        try {
            val code =
                """
                    public float sharedValue = 1;
                    float shadow() { float sharedValue = 2; return sharedValue; }
                    float useGlobal() { return sharedValue + sin(1.0); }
                    StructuredBuffer<float> values;
                    
                    """
                    .trimIndent()
            val files = arrayOfNulls<PsiFile>(2)
            runInEdtAndWait {
                NativeServerSupport.configure(fixture, executable) {
                    val main = fixture.addFileToProject("main.slang", code)
                    files[0] = main
                    files[1] = fixture.addFileToProject("edited.slang", "import main;\nfloat edited() { return 0; }\n")

                    for (i in 0..24) {
                        fixture.addFileToProject("unrelated$i.slang", "// sharedValue\nfloat other = 0;\n")
                    }
                    main.virtualFile.parent.path
                }
                FileDocumentManager.getInstance().saveAllDocuments()
                val document = PsiDocumentManager.getInstance(fixture.project).getDocument(files[1]!!)
                Assertions.assertNotNull(document)
                WriteCommandAction.runWriteCommandAction(fixture.project) {
                    val zero = document!!.text.indexOf("0;")
                    document.replaceString(zero, zero + 1, "sharedValue")
                    PsiDocumentManager.getInstance(fixture.project).commitDocument(document)
                }
            }
            lateinit var editor: Editor
            runInEdtAndWait {
                val editors = FileEditorManager.getInstance(fixture.project)
                editors.openFile(files[0]!!.virtualFile, true)
                PlatformTestUtil.waitWithEventsDispatching("slangd starts", { runningSlangClient(fixture.project) != null }, 15)
                editor = checkNotNull(editors.selectedTextEditor)
            }
            val firstHover = NativeServerSupport.awaitResult {
                runReadActionBlocking {
                    HoverDocumentationProvider().generateDoc(files[0]!!.findElementAt(code.indexOf("sharedValue")), null)
                }
            }
            Assertions.assertNotNull(firstHover, "Documentation must be available before any navigation request")
            Assertions.assertTrue(firstHover!!.contains("sharedValue"), firstHover)
            val scope = GlobalSearchScope.projectScope(fixture.project)
            val candidates = candidateFiles(fixture.project, scope, "sharedValue")
            Assertions.assertEquals(2, candidates.size, "Comment-only files must not become search candidates")
            Assertions.assertTrue(candidates.contains(files[1]!!.virtualFile), "Unsaved occurrence must be included")
            val declaration = runReadActionBlocking { files[0]!!.findElementAt(code.indexOf("sharedValue")) }
            val usages = ArrayList<UsageInfo?>()
            ProgressManager.getInstance()
                .runProcess(
                    {
                        Assertions.assertTrue(
                            process(
                                declaration,
                                scope,
                                Processor { usage: UsageInfo? ->
                                    usages.add(usage)
                                    true
                                }))
                    },
                    EmptyProgressIndicator())
            Assertions.assertEquals(2, usages.size, "Only the global use and the unsaved imported use should match")
            Assertions.assertTrue(usages.any { it?.file === files[1] })
            Assertions.assertTrue(
                usages.any { usage ->
                    usage != null &&
                        usage.file === files[0] &&
                        usage.navigationOffset == code.lastIndexOf("sharedValue")
                })

            // Exercise more than one request window and downstream early termination.
            val doc = runReadActionBlocking { PsiDocumentManager.getInstance(fixture.project).getDocument(files[0]!!) }
            val offsets = List(19) { code.lastIndexOf("sharedValue") }
            val verified = AtomicInteger()
            ProgressManager.getInstance()
                .runProcess(
                    {
                        Assertions.assertTrue(
                            UsageSearch.processDefinitions(files[0]!!, checkNotNull(doc), offsets, check = {}) {
                                _,
                                locations ->
                                Assertions.assertFalse(locations.isEmpty())
                                verified.incrementAndGet()
                                true
                            })
                    },
                    EmptyProgressIndicator())
            Assertions.assertEquals(19, verified.get())
            verified.set(0)
            ProgressManager.getInstance()
                .runProcess(
                    {
                        Assertions.assertFalse(
                            UsageSearch.processDefinitions(files[0]!!, checkNotNull(doc), offsets, check = {}) { _, _ ->
                                verified.incrementAndGet()
                                false
                            })
                    },
                    EmptyProgressIndicator())
            Assertions.assertEquals(1, verified.get())

            // A local with one reference should navigate directly, without a usage chooser.
            val localDeclaration = code.indexOf("sharedValue = 2")
            val localUsage = code.indexOf("return sharedValue") + "return ".length
            val navigated = CountDownLatch(1)
            val caretListener: CaretListener =
                object : CaretListener {
                    override fun caretPositionChanged(event: CaretEvent) {
                        if (editor.caretModel.offset == localUsage) {
                            Assertions.assertFalse(
                                JBPopupFactory.getInstance().isPopupActive,
                                "Single-usage navigation must not open a chooser")
                            navigated.countDown()
                        }
                    }
                }

            runInEdtAndWait {
                editor.caretModel.moveToOffset(localDeclaration)
                editor.caretModel.addCaretListener(caretListener)
                invokeGotoDeclaration(editor, files[0]!!)
            }

            try {
                Assertions.assertTrue(navigated.await(10, TimeUnit.SECONDS), "Single usage should be opened directly")
            } finally {
                runInEdtAndWait { editor.caretModel.removeCaretListener(caretListener) }
            }

            val builtin = runReadActionBlocking {
                val source = files[0]!!.findElementAt(code.indexOf("sin("))
                Assertions.assertNotNull(source)
                SymbolDeclarationHandler().definitions(source!!, code.indexOf("sin("))
            }
            Assertions.assertTrue(builtin.isNotEmpty(), "sin must navigate into the standard library")
            val file = builtin[0].containingFile.virtualFile
            Assertions.assertInstanceOf<BuiltinFile?>(BuiltinFile::class.java, file)
            Assertions.assertFalse(file!!.isWritable)
            val connectsBuiltinFile = checkNotNull(runningSlangClient(fixture.project)).descriptor.isSupportedFile(file)
            Assertions.assertFalse(connectsBuiltinFile)
            val service = fixture.project.getService(BuiltinFiles::class.java)
            Assertions.assertSame(file, service.resolve((file as BuiltinFile).uri().toString()))
            Assertions.assertTrue(
                runReadActionBlocking {
                    builtin[0].text.contains("sin")
                })

            // Quick Documentation still uses LSP after declaration navigation is deferred.
            val bufferDocs = runReadActionBlocking {
                HoverDocumentationProvider()
                    .generateDoc(files[0]!!.findElementAt(code.indexOf("StructuredBuffer")), null)
            }
            Assertions.assertNotNull(bufferDocs)
            Assertions.assertTrue(bufferDocs!!.contains("read-only structured buffer"), bufferDocs)
            Assertions.assertTrue(bufferDocs.contains("The element type of the buffer"), bufferDocs)
            val sineDocs = runReadActionBlocking {
                HoverDocumentationProvider().generateDoc(files[0]!!.findElementAt(code.indexOf("sin(")), null)
            }
            Assertions.assertNotNull(sineDocs)
            Assertions.assertTrue(sineDocs!!.contains("The angle in radians"), sineDocs)

            runReadActionBlocking {
                val offset = code.indexOf("sin(")
                val source = checkNotNull(files[0]!!.findElementAt(offset))
                val targets = SymbolDeclarationHandler().getGotoDeclarationTargets(source, offset, editor)
                val link = Assertions.assertInstanceOf(DeferredNavigationTarget::class.java, targets?.singleOrNull())
                Assertions.assertTrue(link.canNavigate())
                Assertions.assertEquals("sin", link.text, "The Ctrl-hover target must expose only the local token")
            }

            val documented =
                "```\nstruct StructuredBuffer<T>\n```\n\nServer-provided explanation.\n\nDefined in core(21016)\n"
            Assertions.assertEquals(documented, BuiltinHoverDocumentation.enrich(fixture.project, documented))

            runInEdtAndWait {
                editor.caretModel.moveToOffset(code.indexOf("sin("))
                val history = IdeDocumentHistory.getInstance(fixture.project)
                history.clearHistory()
                invokeGotoDeclaration(editor, files[0]!!)
                val editors = FileEditorManager.getInstance(fixture.project)
                PlatformTestUtil.waitWithEventsDispatching(
                    "Go to Declaration opens the resolved builtin rather than the link placeholder",
                    { editors.selectedFiles.contains(file) },
                    10,
                )
                Assertions.assertEquals(builtin[0].textOffset, editors.selectedTextEditor?.caretModel?.offset)
                Assertions.assertTrue(history.isBackAvailable, "Deferred navigation must preserve the IDE's Back action")
                history.back()
                Assertions.assertEquals(files[0]!!.virtualFile, editors.selectedFiles.firstOrNull())
                Assertions.assertEquals(code.indexOf("sin("), editors.selectedTextEditor?.caretModel?.offset)
            }
        } finally {
            try {
                NativeServerSupport.stop(fixture.project)
            } finally {
                runInEdtAndWait { fixture.tearDown() }
            }
        }
    }

    private fun invokeGotoDeclaration(editor: Editor, file: PsiFile) {
        val action = ActionManager.getInstance().getAction(IdeActions.ACTION_GOTO_DECLARATION)
        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, file.project)
            .add(CommonDataKeys.EDITOR, editor)
            .add(CommonDataKeys.PSI_FILE, file)
            .build()
        val event = AnActionEvent.createEvent(action, context, null, ActionPlaces.UNKNOWN, ActionUiKind.NONE, null)
        Assertions.assertFalse(ActionUtil.performAction(action, event).isIgnored, "Go to Declaration must execute")
    }
}
