package slanglsp.projectview

import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.ProjectViewNodeDecorator
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValue
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import com.intellij.ui.SimpleTextAttributes
import slanglsp.language.EntryPointStages
import slanglsp.language.SlangFileType

/** Adds a display-only suffix to Slang file nodes in the Project tool window. */
// Inherit the platform's unused, deprecated default overload without emitting a Kotlin bridge.
@JvmDefaultWithoutCompatibility
class EntryPointStageDecorator : ProjectViewNodeDecorator {
    override fun decorate(node: ProjectViewNode<*>, data: PresentationData) {
        val file = node.value as? PsiFile ?: return
        if (!file.isValid || file.fileType !== SlangFileType) {
            return
        }

        // Project View computes node presentations in its background tree model. Tie the cache
        // to this PSI file so the platform's next update after an edit sees the new stages.
        val stages = CachedValuesManager.getCachedValue(file, STAGES) {
            CachedValueProvider.Result.create(EntryPointStages.collect(file.viewProvider.contents), file)
        }
        if (stages.isEmpty()) {
            return
        }

        if (data.coloredText.isEmpty()) {
            data.addText(data.presentableText ?: file.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
        }
        data.addText(stages.joinToString(", ", prefix = " [", postfix = "]"), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
    }

}

val STAGES = Key.create<CachedValue<List<String>>>("slang.projectView.entryPointStages")