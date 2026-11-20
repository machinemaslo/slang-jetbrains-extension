package slanglsp.server.nativeclient

import com.intellij.codeInsight.hint.ShowParameterInfoHandler
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.lang.LanguageStructureViewBuilder
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.lang.parameterInfo.CreateParameterInfoContext
import com.intellij.lang.parameterInfo.ParameterInfoHandler
import com.intellij.lang.parameterInfo.ParameterInfoUIContext
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.runReadActionBlocking
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.openapi.util.Disposer
import com.intellij.platform.lsp.api.LspClientManager
import com.intellij.platform.lsp.api.customization.LspSemanticTokensSupport
import com.intellij.platform.lsp.api.getClients
import com.intellij.platform.lsp.api.stopClients
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.search.LocalSearchScope
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.usageView.UsageInfo
import com.intellij.util.Processor
import java.awt.Color
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import org.eclipse.lsp4j.SemanticTokensParams
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Isolated
import slanglsp.TestDependencies
import slanglsp.analysis.DocumentSymbolRequests
import slanglsp.editor.AnnotationRecorder
import slanglsp.editor.NativeServerSupport
import slanglsp.editor.documentation.NativeHoverDocumentation
import slanglsp.editor.inspections.unused.UnusedSymbolsAnnotator
import slanglsp.editor.navigation.SymbolDeclarationHandler
import slanglsp.editor.navigation.UsageSearch
import slanglsp.editor.rename.RenamePlan
import slanglsp.editor.rename.VerifiedRenameHandler
import slanglsp.highlighting.SyntaxColors
import slanglsp.settings.project.ProjectSettings

@Isolated
internal class SlangNativeLspTest {
    @Test
    fun supportsNativeEditorFeaturesIncludingVerifiedRenameAndUnusedWarnings() {
        val fixture = NativeServerSupport.isolatedFixture("SlangNativeLsp")
        val executable = TestDependencies.slangd()
        lateinit var file: PsiFile
        lateinit var consumer: PsiFile
        val consumerSource = "import material;\nfloat imported(Material m) { return evaluate(m) + importedOnly(); }\n"
        val source = """
            public struct Material { float roughness; };

            public float evaluate(Material material) { return material.roughness; }

            public float importedOnly() { return 1; }

            float shade(Material material) { float unusedLocal = 0; return evaluate(material) + sin(1.0); }
        """.trimIndent()

        try {
            runInEdtAndWait {
                fixture.setUp()
                TrustedProjects.setProjectTrusted(fixture.project, true)
                ModuleRootModificationUtil.addContentRoot(fixture.module, fixture.tempDirPath)

                val settings = ProjectSettings.getInstance(fixture.project)
                val state = settings.getState()
                state.explicitSlangdLocation = Path.of(executable).parent.toString()
                state.useLocalVcpkgSlangd = false
                settings.loadState(state)

                file = fixture.addFileToProject("material.slang", source)
                consumer = fixture.addFileToProject("consumer.slang", consumerSource)
                val editors = FileEditorManager.getInstance(fixture.project)
                editors.openFile(file.virtualFile, true)
                assertTrue(editors.isFileOpen(file.virtualFile))
            }

            fun requestSymbols() = runReadActionBlocking {
                val document = checkNotNull(PsiDocumentManager.getInstance(fixture.project).getDocument(file))
                DocumentSymbolRequests.get(file, document, document.modificationStamp, batch = false)
            }

            // The client can report Running before it has sent didOpen for the editor.
            val started = TimeSource.Monotonic.markNow()
            var symbols = requestSymbols().get(12, TimeUnit.SECONDS)
            while (symbols.isNullOrEmpty() && started.elapsedNow() < 10.seconds) {
                Thread.sleep(50)
                symbols = requestSymbols().get(12, TimeUnit.SECONDS)
            }

            val clients = LspClientManager.getInstance(fixture.project).getClients<SlangLspProvider>()
            assertEquals(
                listOf("Material", "evaluate", "importedOnly", "shade"),
                symbols?.map { it.name },
                "Native client states: ${clients.map { it.state }}",
            )
            assertEquals(listOf("roughness"), symbols?.first()?.children?.map { it.name })
            assertStructure(file, listOf("Material", "evaluate", "importedOnly", "shade"), "roughness")

            val editors = FileEditorManager.getInstance(fixture.project)
            val editor = runReadActionBlocking { checkNotNull(editors.selectedTextEditor) }
            val argumentOffset = source.lastIndexOf("evaluate(material)") + "evaluate(".length
            assertParameterInfo(file, editor, argumentOffset)

            val client = checkNotNull(runningSlangClient(fixture.project))
            runInEdtAndWait {
                val cpp = fixture.addFileToProject("other.cpp", "int main() { return 0; }")
                assertFalse(client.descriptor.isSupportedFile(cpp.virtualFile))
            }
            val colors = client.descriptor.lspCustomization.semanticTokensCustomizer as LspSemanticTokensSupport
            assertTrue(runReadActionBlocking { colors.shouldAskServerForSemanticTokens(file) })
            assertEquals(SyntaxColors.FIELD, colors.getTextAttributesKey("property", emptyList()))
            val params = SemanticTokensParams(client.getDocumentIdentifier(file.virtualFile))
            val tokens = client.sendRequestSync { it.textDocumentService.semanticTokensFull(params) }
            assertNotNull(tokens)
            assertTrue(tokens!!.data.isNotEmpty())

            val typeHover = NativeHoverDocumentation.html(file, source.lastIndexOf("Material"))
            assertTrue(typeHover?.contains("Material") == true, typeHover)
            val callOffset = source.lastIndexOf("evaluate")
            val callHover = NativeHoverDocumentation.html(file, callOffset)
            assertTrue(callHover?.contains("evaluate") == true, callHover)
            val builtinHover = NativeHoverDocumentation.html(file, source.indexOf("sin("))
            assertTrue(builtinHover?.contains("The angle in radians") == true, builtinHover)

            val call = runReadActionBlocking { checkNotNull(file.findElementAt(callOffset)) }
            val declarationOffset = source.indexOf("evaluate")
            val targets = SymbolDeclarationHandler().definitions(call, callOffset)
            assertEquals(listOf(declarationOffset), targets.map { it.textOffset })

            val declaration = runReadActionBlocking { file.findElementAt(declarationOffset) }
            val usages = mutableListOf<UsageInfo?>()
            assertTrue(UsageSearch.process(declaration, LocalSearchScope(file), Processor {
                usages.add(it)
                true
            }))
            assertEquals(listOf(callOffset), usages.map { it?.navigationOffset })

            val annotator = UnusedSymbolsAnnotator()
            val input = checkNotNull(runReadActionBlocking { annotator.collectForEditor(file) })
            val result = checkNotNull(annotator.doAnnotate(input))
            val unused = result.unused.map { it.name }
            assertTrue("shade" in unused, unused.toString())
            assertTrue("unusedLocal" in unused, unused.toString())
            assertTrue("evaluate" !in unused, unused.toString())
            assertTrue("importedOnly" !in unused, "A possible use in a closed file must suppress the warning")
            runReadActionBlocking {
                val annotations = AnnotationRecorder()
                annotator.apply(file, result, annotations.holder())
                val warning = annotations.single { it.startOffset == source.indexOf("unusedLocal") }
                assertEquals(HighlightSeverity.WEAK_WARNING, warning.severity)
            }

            val savedPlan = RenamePlan.collect(call, "evaluateSurface")
            assertEquals(3, savedPlan.usages().size, "Rename must find the reference in the unopened file")
            runInEdtAndWait {
                val editor = checkNotNull(FileEditorManager.getInstance(fixture.project).selectedTextEditor)
                editor.caretModel.moveToOffset(callOffset)
                val context = SimpleDataContext.builder()
                    .add(CommonDataKeys.PROJECT, fixture.project)
                    .add(CommonDataKeys.EDITOR, editor)
                    .add(CommonDataKeys.PSI_FILE, file)
                    .build()
                assertTrue(VerifiedRenameHandler().isAvailableOnDataContext(context))
            }

            val editedConsumer = "// Unsaved edit\n$consumerSource"
            runInEdtAndWait {
                val documents = PsiDocumentManager.getInstance(fixture.project)
                val document = checkNotNull(documents.getDocument(consumer))
                WriteCommandAction.runWriteCommandAction(fixture.project) {
                    document.setText(editedConsumer)
                }
                documents.commitDocument(document)
                assertTrue(FileDocumentManager.getInstance().isDocumentUnsaved(document))
            }

            val plan = RenamePlan.collect(call, "evaluateSurface")
            assertEquals(3, plan.usages().size)
            runInEdtAndWait {
                plan.apply()
                assertEquals(editedConsumer.replace("evaluate", "evaluateSurface"), consumer.text)
                assertFalse(FileEditorManager.getInstance(fixture.project).isFileOpen(consumer.virtualFile))
            }

            runInEdtAndWait {
                val documents = PsiDocumentManager.getInstance(fixture.project)
                val document = checkNotNull(documents.getDocument(file))

                WriteCommandAction.runWriteCommandAction(fixture.project) {
                    document.setText("struct Updated { float value; };\n")
                }

                documents.commitDocument(document)
            }

            assertEquals(listOf("Updated"), requestSymbols().get(12, TimeUnit.SECONDS)?.map { it.name })
            assertStructure(file, listOf("Updated"), "value")
        } finally {
            runInEdtAndWait {
                LspClientManager.getInstance(fixture.project).stopClients<SlangLspProvider>()
                fixture.tearDown()
            }
        }
    }

    private fun assertStructure(file: PsiFile, declarations: List<String>, member: String) {
        runReadActionBlocking {
            val builder = LanguageStructureViewBuilder.INSTANCE.getStructureViewBuilder(file)
            check(builder is TreeBasedStructureViewBuilder) { "No Structure view for ${file.name}" }
            val model = builder.createStructureViewModel(null)
            try {
                val children = model.root.children
                assertEquals(declarations, children.map { it.presentation.presentableText })
                assertEquals(listOf(member), children.first().children.map { it.presentation.presentableText })
            } finally {
                Disposer.dispose(model)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun assertParameterInfo(file: PsiFile, editor: Editor, offset: Int) {
        var items = emptyArray<Any>()
        var hintShown = false
        val context = testContext<CreateParameterInfoContext> { _, method, args ->
            when (method.name) {
                "getFile" -> file
                "getProject" -> file.project
                "getEditor" -> editor
                "getOffset" -> offset
                "setItemsToShow" -> {
                    items = args!![0] as Array<Any>
                    null
                }
                "showHint" -> {
                    hintShown = true
                    null
                }
                else -> error("Unexpected parameter-info call: ${method.name}")
            }
        }

        runReadActionBlocking {
            val (handler, owner) = ShowParameterInfoHandler.getHandlers(file.project, file.language)
                .mapNotNull { candidate ->
                    val handler = candidate as ParameterInfoHandler<PsiElement, Any>
                    handler.findElementForParameterInfo(context)?.let { handler to it }
                }.single()
            handler.showParameterInfo(owner, context)
            assertTrue(hintShown)

            var highlighted: String? = null
            val presentation = testContext<ParameterInfoUIContext> { _, method, args ->
                when (method.name) {
                    "getCurrentParameterIndex" -> 0
                    "getDefaultParameterColor" -> Color.BLACK
                    "setupUIComponentPresentation" -> {
                        val label = args!![0] as String
                        assertTrue(label.contains("evaluate"), label)
                        highlighted = label.substring(args[1] as Int, args[2] as Int)
                        label
                    }
                    else -> error("Unexpected signature presentation call: ${method.name}")
                }
            }
            handler.updateUI(items.single(), presentation)
            assertTrue(highlighted?.contains("material") == true, highlighted)
        }
    }

    private inline fun <reified T> testContext(handler: InvocationHandler): T {
        val type = T::class.java
        return type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf(type), handler))
    }
}
