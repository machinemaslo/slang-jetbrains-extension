package slanglsp.editor.navigation.cpp

import com.intellij.ide.DataManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.util.Key
import com.intellij.psi.PsiElement
import com.intellij.ui.awt.RelativePoint
import com.intellij.usages.UsageView
import com.intellij.util.ui.JBUI
import java.awt.Point
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JButton
import javax.swing.SwingUtilities
import javax.swing.Timer
import slanglsp.SlangBundle
import slanglsp.editor.navigation.cpp.ReflectionMetadataLookup.Result.Unavailable

/** Adds a companion to the stock Show Usages popup without modifying its toolbar. */
internal object CppViewButton {
    private val pending = Key.create<Timer>("slang.cppView.pending")

    fun watch(editor: Editor, target: PsiElement, anchor: RelativePoint) {
        editor.getUserData(pending)?.stop()
        editor.putUserData(pending, null)
        if (!supportsCppReflection() || !editor.contentComponent.isShowing) {
            return
        }

        val file = target.containingFile ?: return
        val project = file.project
        val offset = target.textOffset
        if (file.virtualFile?.isInLocalFileSystem != true || structDeclarationAt(file, offset) == null) {
            return
        }

        // JBPopup.show uses the normalized component, not the original editor component.
        val owner = anchor.component
        val factory = JBPopupFactory.getInstance()
        val existing = factory.getChildPopups(owner).toSet()
        val stamp = editor.document.modificationStamp
        val caretOffset = editor.caretModel.offset
        val deadline = System.nanoTime() + 4_000_000_000L

        // The stock popup is created asynchronously and may not show at all for a lone usage.
        val watcher = Timer(50) { event ->
            val timer = event.source as Timer
            fun stop() {
                timer.stop()
                editor.putUserData(pending, null)
            }

            if (editor.isDisposed || project.isDisposed || !file.isValid || !editor.contentComponent.isShowing ||
                editor.caretModel.offset != caretOffset ||
                editor.document.modificationStamp != stamp || System.nanoTime() >= deadline) {
                stop()
                return@Timer
            }

            val stock = factory.getChildPopups(owner).firstOrNull { popup ->
                popup !in existing && popup.isVisibleUsagePopup()
            } ?: return@Timer

            stop()
            show(stock) { button ->
                if (editor.isDisposed || editor.document.modificationStamp != stamp) {
                    notify(project, Unavailable.STALE.message())
                    return@show
                }

                val position = stock.locationOnScreen
                button.isEnabled = false
                ReflectionHeaderSetup.checkVersion(project) { replaced ->
                    if (replaced || editor.isDisposed || !editor.contentComponent.isShowing) {
                        button.isEnabled = true
                        return@checkVersion
                    }
                    if (editor.document.modificationStamp != stamp) {
                        button.isEnabled = true
                        notify(project, Unavailable.STALE.message())
                        return@checkVersion
                    }

                    var openingSetup = false
                    val started = CppUsageSearch.start(
                        file, editor, offset,
                        unavailable = { reason ->
                            if (stock.isVisible && (reason == Unavailable.MISSING_PACK || reason == Unavailable.NO_ACTIVE_BUILD)) {
                                openingSetup = true
                                ReflectionHeaderSetup.open(project, button, reason.message())
                            } else {
                                notify(project, reason.message())
                            }
                        },
                        showUsages = { location, current ->
                            CppQuickUsages.replace(editor, stock, position, location, current)
                        },
                        finished = {
                            if (!openingSetup) {
                                button.isEnabled = true
                            }
                        },
                    )
                    if (!started) {
                        button.isEnabled = true
                    }
                }
            }
        }

        editor.putUserData(pending, watcher)
        watcher.start()
    }

    private fun show(stock: JBPopup, activate: (JButton) -> Unit) {
        val button = JButton(SlangBundle.message("cpp.usages.quick.view"))
        button.isFocusable = false
        button.addActionListener { activate(button) }
        val companion = JBPopupFactory.getInstance().createComponentPopupBuilder(button, null)
            .setRequestFocus(false)
            .setFocusable(false)
            .setCancelOnClickOutside(false)
            .setCancelOnOtherWindowOpen(false)
            .setCancelOnWindowDeactivation(false)
            .setBelongsToGlobalPopupStack(false)
            .createPopup()

        val content = stock.content
        fun position(): RelativePoint {
            val size = button.preferredSize
            val gap = JBUI.scale(6)
            return RelativePoint(content, Point((content.width - size.width).coerceAtLeast(0), -size.height - gap))
        }

        val window = SwingUtilities.getWindowAncestor(content)
        val follow = object : ComponentAdapter() {
            override fun componentMoved(event: ComponentEvent) = reposition()

            override fun componentResized(event: ComponentEvent) = reposition()

            private fun reposition() {
                if (content.isShowing && companion.isVisible) {
                    companion.setLocation(position().screenPoint)
                }
            }
        }

        stock.addListener(object : JBPopupListener {
            override fun onClosed(event: LightweightWindowEvent) {
                window?.removeComponentListener(follow)
                content.removeComponentListener(follow)
                if (!companion.isDisposed) {
                    companion.cancel()
                }
            }
        })
        window?.addComponentListener(follow)
        content.addComponentListener(follow)
        companion.show(position())
    }
}

internal fun JBPopup.isVisibleUsagePopup(): Boolean =
    isVisible && content.isShowing &&
        DataManager.getInstance().getDataContext(content).getData(UsageView.USAGE_VIEW_SETTINGS_KEY) != null
