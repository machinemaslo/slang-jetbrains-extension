package slanglsp.editor.navigation.cpp

import com.intellij.find.actions.ShowUsagesAction
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.psi.PsiDocumentManager
import java.awt.Point
import javax.swing.JLayeredPane
import javax.swing.SwingUtilities
import javax.swing.Timer
import slanglsp.SlangBundle
import slanglsp.editor.navigation.cpp.ReflectionMetadataLookup.Result.Unavailable

internal object CppQuickUsages {
    fun replace(
        sourceEditor: Editor,
        slangPopup: JBPopup,
        anchorAtClick: Point,
        location: CppLocation,
        current: () -> Boolean,
    ) {
        val project = sourceEditor.project ?: return
        if (project.isDisposed || sourceEditor.isDisposed || !sourceEditor.contentComponent.isShowing || !current()) {
            return
        }

        val (file, stamp, offset) = location
        val document = FileDocumentManager.getInstance().getDocument(file)
        if (!file.isValid || document == null || document.modificationStamp != stamp) {
            notify(project, Unavailable.STALE.message())
            return
        }

        val root = SwingUtilities.getRootPane(sourceEditor.contentComponent)
        // A native mapping chooser can close the Slang popup before the C++ target is chosen.
        val anchor = if (slangPopup.isVisible) slangPopup.locationOnScreen else anchorAtClick
        val actions = ActionManager.getInstance()
        val showUsages = actions.getAction(ShowUsagesAction.ID)
        if (root == null || showUsages == null) {
            notify(project, SlangBundle.message("cpp.usages.handoff.failed"))
            return
        }

        val popupFactory = JBPopupFactory.getInstance()
        val existing = popupFactory.getChildPopups(root).toSet()
        val factory = EditorFactory.getInstance()
        val editor = factory.createEditor(document, project, file, false, EditorKind.MAIN_EDITOR)
        val parent = root.layeredPane
        val position = Point(anchor)
        SwingUtilities.convertPointFromScreen(position, parent)
        val component = editor.component

        // CLion needs a real C++ editor context, but that editor need not be an open tab.
        parent.setLayer(component, JLayeredPane.FRAME_CONTENT_LAYER - 1)
        parent.add(component)
        component.setBounds(position.x, position.y, 1, 1)
        component.doLayout()
        editor.caretModel.moveToOffset(offset)

        var released = false
        fun release() {
            if (released) {
                return
            }
            released = true
            parent.remove(component)
            // Use an IDE EDT callback to release the editor even when Swing's timer closes the popup.
            ApplicationManager.getApplication().invokeLater({
                if (!editor.isDisposed) {
                    factory.releaseEditor(editor)
                }
            }, ModalityState.any())
        }

        val deadline = System.nanoTime() + 8_000_000_000L
        val watcher = Timer(25, null)
        watcher.addActionListener {
            if (project.isDisposed || sourceEditor.isDisposed || !root.isShowing || System.nanoTime() >= deadline) {
                watcher.stop()
                release()
                if (!sourceEditor.isDisposed) {
                    notify(project, SlangBundle.message("cpp.usages.handoff.failed"))
                }
                return@addActionListener
            }

            val cppPopup = popupFactory.getChildPopups(root).firstOrNull { popup ->
                popup !in existing && popup.isVisibleUsagePopup()
            } ?: return@addActionListener

            watcher.stop()
            cppPopup.setLocation(Point(anchor))
            cppPopup.addListener(object : JBPopupListener {
                override fun onClosed(event: LightweightWindowEvent) {
                    release()
                }
            })
        }

        if (!slangPopup.isDisposed) {
            slangPopup.cancel()
        }
        watcher.start()
        val context = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.EDITOR, editor)
            .add(CommonDataKeys.CARET, editor.caretModel.primaryCaret)
            .add(CommonDataKeys.VIRTUAL_FILE, file)
            .add(CommonDataKeys.PSI_FILE, PsiDocumentManager.getInstance(project).getPsiFile(document))
            .add(PlatformCoreDataKeys.CONTEXT_COMPONENT, editor.contentComponent)
            .build()
        val event = AnActionEvent.createEvent(showUsages, context, null, ActionPlaces.EDITOR_POPUP, ActionUiKind.NONE, null)
        if (ActionUtil.performAction(showUsages, event).isIgnored) {
            watcher.stop()
            release()
            notify(project, SlangBundle.message("cpp.usages.handoff.failed"))
        }
    }
}
