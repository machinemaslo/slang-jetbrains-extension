package slanglsp.editor.navigation.cpp

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.usages.PsiElementUsageTarget
import com.intellij.usages.Usage
import com.intellij.usages.UsageTarget
import com.intellij.usages.UsageView
import com.intellij.usages.impl.UsageViewElementsListener
import com.intellij.usages.impl.UsageViewImpl
import com.intellij.usages.rules.UsageInFile
import java.util.WeakHashMap

/** Bind exclusions to the next matching usage view, never to global Find Usages preferences. */
@Service(Service.Level.PROJECT)
internal class ReflectedUsageExclusions {
    private data class Pending(
        val id: Long,
        val declaration: ReflectionSearch.Location,
        val name: String,
        val files: Set<VirtualFile>,
        val expires: Long,
    )
    private var serial = 0L
    private val pending = mutableListOf<Pending>()
    private val views = WeakHashMap<UsageView, Set<VirtualFile>>()

    @Synchronized
    fun prepare(declaration: ReflectionSearch.Location, name: String, files: Set<VirtualFile>): Long {
        pending.removeIf { it.expires < System.nanoTime() }
        if (pending.size >= 128) pending.removeFirst()
        val id = ++serial
        pending.add(Pending(id, declaration, name, files + declaration.file, System.nanoTime() + 30_000_000_000L))
        return id
    }

    @Synchronized
    fun cancel(id: Long) {
        pending.removeIf { it.id == id }
    }

    @Synchronized
    fun skip(view: UsageView, targets: Array<UsageTarget>, file: VirtualFile): Boolean {
        val exclusions = views[view] ?: run {
            pending.removeIf { it.expires < System.nanoTime() }
            val match = pending.lastOrNull { request ->
                targets.any { target ->
                    val element = (target as? PsiElementUsageTarget)?.element
                    if (element != null) {
                        element.containingFile?.virtualFile == request.declaration.file && element.textOffset == request.declaration.offset
                    } else {
                        // Nova's frontend targets intentionally return an empty getFiles().
                        target.name?.let { Regex("""[A-Za-z_][A-Za-z_0-9]*""").findAll(it).any { word -> word.value == request.name } } == true
                    }
                }
            } ?: return false
            pending.remove(match)
            views[view] = match.files
            match.files
        }
        return file in exclusions
    }
}

/** Called before appending results, for both PSI usages and Nova's UsageInFile frontend results. */
class ReflectedUsageViewListener : UsageViewElementsListener {
    override fun skipUsage(view: UsageView, usage: Usage): Boolean {
        val impl = view as? UsageViewImpl ?: return false
        val file = (usage as? UsageInFile)?.file ?: return false
        return impl.project.service<ReflectedUsageExclusions>().skip(view, impl.targets, file)
    }
}
