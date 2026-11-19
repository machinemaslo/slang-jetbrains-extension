package slanglsp.editor.navigation

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.usageView.UsageInfo
import com.intellij.util.Processor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.eclipse.lsp4j.MarkupContent
import org.eclipse.lsp4j.MarkupKind
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import slanglsp.TestDependencies
import slanglsp.analysis.ReadActions.compute
import slanglsp.editor.NativeServerSupport
import slanglsp.editor.documentation.HoverDocumentationProvider
import slanglsp.editor.navigation.UsageSearch.candidateFiles
import slanglsp.editor.navigation.UsageSearch.process
import slanglsp.server.BatchConnection.wrapper
import slanglsp.server.BuiltinFiles
import slanglsp.server.BuiltinFiles.BuiltinFile
import slanglsp.server.ServerFactory

// Integration checks exercise LSP4IJ's experimental extension points.
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
            // Wait for process initialization, not a document connection. Hover must still open the file itself.
            Assertions.assertNotNull(wrapper(fixture.project).initializedServer.get(15, TimeUnit.SECONDS))
            val firstHover = compute {
                HoverDocumentationProvider().generateDoc(files[0]!!.findElementAt(code.indexOf("sharedValue")), null)
            }
            Assertions.assertNotNull(firstHover, "Documentation must connect the file before any navigation request")
            Assertions.assertTrue(firstHover!!.contains("sharedValue"), firstHover)
            val scope = GlobalSearchScope.projectScope(fixture.project)
            val candidates = candidateFiles(fixture.project, scope, "sharedValue")
            Assertions.assertEquals(2, candidates.size, "Comment-only files must not become search candidates")
            Assertions.assertTrue(candidates.contains(files[1]!!.virtualFile), "Unsaved occurrence must be included")
            val declaration = compute { files[0]!!.findElementAt(code.indexOf("sharedValue")) }
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
            val doc = compute { PsiDocumentManager.getInstance(fixture.project).getDocument(files[0]!!) }
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
                        if (fixture.editor.caretModel.offset == localUsage) {
                            Assertions.assertFalse(
                                JBPopupFactory.getInstance().isPopupActive,
                                "Single-usage navigation must not open a chooser")
                            navigated.countDown()
                        }
                    }
                }

            runInEdtAndWait {
                fixture.configureFromExistingVirtualFile(files[0]!!.virtualFile)
                fixture.editor.caretModel.moveToOffset(localDeclaration)
                fixture.editor.caretModel.addCaretListener(caretListener)
                run {
                    val local = files[0]!!.findElementAt(localDeclaration)
                    Assertions.assertNotNull(local)
                    UsageNavigationTarget(local!!, fixture.editor).navigate(true)
                }
            }

            try {
                Assertions.assertTrue(navigated.await(10, TimeUnit.SECONDS), "Single usage should be opened directly")
            } finally {
                runInEdtAndWait { fixture.editor.caretModel.removeCaretListener(caretListener) }
            }

            val builtin = compute {
                val source = files[0]!!.findElementAt(code.indexOf("sin("))
                Assertions.assertNotNull(source)
                SymbolDeclarationHandler().definitions(source!!, code.indexOf("sin("))
            }
            Assertions.assertTrue(builtin.isNotEmpty(), "sin must navigate into the standard library")
            val file = builtin[0].containingFile.virtualFile
            Assertions.assertInstanceOf<BuiltinFile?>(BuiltinFile::class.java, file)
            Assertions.assertFalse(file!!.isWritable)
            // Check the actual client registration rather than constructing the feature in isolation.
            @Suppress("UnstableApiUsage")
            val connectsBuiltinFile = ServerFactory().createClientFeatures().isEnabled(file)
            Assertions.assertFalse(connectsBuiltinFile)
            val service = fixture.project.getService(BuiltinFiles::class.java)
            Assertions.assertSame(file, service.resolve((file as BuiltinFile).uri().toString()))
            Assertions.assertTrue(
                compute {
                    builtin[0].text.contains("sin")
                })

            // Exercise the same rendering hook used by both LSP hover and Ctrl-hover.
            val bufferDocs = compute {
                HoverDocumentationProvider()
                    .generateDoc(files[0]!!.findElementAt(code.indexOf("StructuredBuffer")), null)
            }
            Assertions.assertNotNull(bufferDocs)
            Assertions.assertTrue(bufferDocs!!.contains("read-only structured buffer"), bufferDocs)
            Assertions.assertTrue(bufferDocs.contains("The element type of the buffer"), bufferDocs)
            val sineDocs = compute {
                HoverDocumentationProvider().generateDoc(files[0]!!.findElementAt(code.indexOf("sin(")), null)
            }
            Assertions.assertNotNull(sineDocs)
            Assertions.assertTrue(sineDocs!!.contains("The angle in radians"), sineDocs)

            val documented =
                MarkupContent(
                    MarkupKind.MARKDOWN,
                    "```\nstruct StructuredBuffer<T>\n```\n\nServer-provided explanation.\n\nDefined in core(21016)\n")
            @Suppress("UnstableApiUsage") // Deliberately exercise the registered LSP4IJ hover extension.
            val suppliedDocs = compute {
                ServerFactory().createClientFeatures().hoverFeature.getContent(documented, files[0]!!)
            }
            Assertions.assertNotNull(suppliedDocs)
            Assertions.assertTrue(suppliedDocs!!.contains("Server-provided explanation."))
            Assertions.assertFalse(
                suppliedDocs.contains("read-only structured buffer"), "Do not duplicate newer server documentation")
        } finally {
            try {
                NativeServerSupport.stop(fixture.project)
            } finally {
                runInEdtAndWait { fixture.tearDown() }
            }
        }
    }
}
