package slanglsp.editor.navigation.cpp

import com.intellij.find.actions.ShowUsagesAction
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.UiDataProvider
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.EditorKind
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
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
    private val log = Logger.getInstance(CppQuickUsages::class.java)

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
        val factory = EditorFactory.getInstance()
        val editor = factory.createEditor(document, project, file, false, EditorKind.MAIN_EDITOR)
        val parent = root.layeredPane
        val position = Point(anchor)
        SwingUtilities.convertPointFromScreen(position, parent)
        val component = UiDataProvider.wrapComponent(editor.component) { sink ->
            sink[CommonDataKeys.PROJECT] = project
            sink[CommonDataKeys.EDITOR] = editor
            sink[CommonDataKeys.HOST_EDITOR] = editor
            sink[CommonDataKeys.EDITOR_EVEN_IF_INACTIVE] = editor
            sink[CommonDataKeys.CARET] = editor.caretModel.primaryCaret
            sink[CommonDataKeys.VIRTUAL_FILE] = file
            sink[CommonDataKeys.VIRTUAL_FILE_ARRAY] = arrayOf(file)
            sink.lazy(CommonDataKeys.PSI_FILE) {
                if (project.isDisposed || !file.isValid) null else PsiDocumentManager.getInstance(project).getPsiFile(document)
            }
        }

        // CLion needs a real C++ editor context, but that editor need not be an open tab.
        parent.setLayer(component, JLayeredPane.FRAME_CONTENT_LAYER - 1)
        parent.add(component)
        component.setBounds(position.x, position.y, 1, 1)
        component.doLayout()

        fun popups() = popupFactory.getChildPopups(root) + popupFactory.getChildPopups(editor.contentComponent)
        val existing = popups().toSet()

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

        // Let CLion attach its native editor context before dispatching through the normal action update path.
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) {
                release()
                return@invokeLater
            }
            FileEditorManager.getInstance(project).runWhenLoaded(editor) {
                if (project.isDisposed || sourceEditor.isDisposed || !component.isShowing || !current()) {
                    release()
                    return@runWhenLoaded
                }
                if (document.modificationStamp != stamp) {
                    release()
                    notify(project, Unavailable.STALE.message())
                    return@runWhenLoaded
                }

                val deadline = System.nanoTime() + 8_000_000_000L
                val watcher = Timer(25, null)
                fun failed(reason: String) {
                    watcher.stop()
                    release()
                    log.info("Native C++ Show Usages: $reason for ${file.path}:$offset")
                    notify(project, SlangBundle.message("cpp.usages.handoff.failed"))
                }
                watcher.addActionListener {
                    if (project.isDisposed || sourceEditor.isDisposed || !root.isShowing) {
                        watcher.stop()
                        release()
                        return@addActionListener
                    }
                    if (System.nanoTime() >= deadline) {
                        failed("no usages popup appeared")
                        return@addActionListener
                    }
                    val cppPopup = popups().firstOrNull { it !in existing && it.isVisibleUsagePopup() } ?: return@addActionListener
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
                editor.caretModel.moveToOffset(offset)
                watcher.start()
                actions.tryToExecute(showUsages, null, editor.contentComponent, ActionPlaces.EDITOR_POPUP, true)
                    .doWhenRejected(Runnable { failed("IDE rejected the action") })
            }
        }, ModalityState.nonModal())
    }
}
