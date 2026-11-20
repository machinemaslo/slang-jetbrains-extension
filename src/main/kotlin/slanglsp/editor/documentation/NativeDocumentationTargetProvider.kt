package slanglsp.editor.documentation

import com.intellij.model.Pointer
import com.intellij.openapi.util.TextRange
import com.intellij.platform.backend.documentation.DocumentationResult
import com.intellij.platform.backend.documentation.DocumentationTarget
import com.intellij.platform.backend.documentation.DocumentationTargetProvider
import com.intellij.platform.backend.presentation.TargetPresentation
import com.intellij.psi.PsiFile
import java.util.function.Supplier
import slanglsp.language.SlangLanguage

internal class NativeDocumentationTargetProvider : DocumentationTargetProvider {
    override fun documentationTargets(psiFile: PsiFile, offset: Int): List<DocumentationTarget> {
        if (psiFile.language !== SlangLanguage.INSTANCE) {
            return emptyList()
        }

        val leaf = psiFile.findElementAt(offset) ?: return emptyList()
        if (leaf.text.isBlank() || psiFile.virtualFile?.isInLocalFileSystem != true) {
            return emptyList()
        }

        return listOf(Target(psiFile, offset, leaf.text))
    }

    private class Target(private val file: PsiFile, private val offset: Int, private val label: String) :
        DocumentationTarget {
        override fun createPointer(): Pointer<out DocumentationTarget> {
            return Pointer.fileRangePointer(file, TextRange(offset, offset)) { restored, range ->
                Target(restored, range.startOffset, label)
            }
        }

        override fun computePresentation(): TargetPresentation {
            return TargetPresentation.builder(label).presentation()
        }

        override fun computeDocumentation(): DocumentationResult {
            return DocumentationResult.asyncDocumentation(Supplier {
                NativeHoverDocumentation.html(file, offset)?.let(DocumentationResult::documentation)
            })
        }
    }
}
