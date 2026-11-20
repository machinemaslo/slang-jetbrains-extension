package slanglsp.editor

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.lang.annotation.AnnotationBuilder
import com.intellij.lang.annotation.AnnotationHolder
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.openapi.util.TextRange
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import slanglsp.editor.AnnotationRecorder.RecordedAnnotation

/** Records the public annotation-builder protocol without IDE-internal annotation implementations. */
internal class AnnotationRecorder : ArrayList<RecordedAnnotation>() {
    internal class Fix(val quickFix: IntentionAction) {
        var textRange: TextRange? = null
    }

    internal class RecordedAnnotation(val message: String?) {
        var range: TextRange? = null
        var tooltip: String? = message
        var severity: HighlightSeverity? = null
        val quickFixes = ArrayList<Fix>()

        val startOffset: Int
            get() = range!!.startOffset

        val endOffset: Int
            get() = range!!.endOffset
    }

    fun holder(): AnnotationHolder {
        return proxy(AnnotationHolder::class.java) { _, method, params ->
            val args = params.orEmpty()
            when (method.name) {
                "newAnnotation",
                "newSilentAnnotation" -> {
                    val annotation = RecordedAnnotation(if (args.size > 1) args[1] as String? else null)
                    annotation.severity = args[0] as HighlightSeverity?
                    builder(annotation)
                }

                else -> throw java.lang.AssertionError("Unexpected annotation-holder call: $method")
            }
        }
    }

    private fun builder(annotation: RecordedAnnotation): AnnotationBuilder {
        return proxy(AnnotationBuilder::class.java) { builder, method, params ->
            val args = params.orEmpty()
            when (method.name) {
                "range" -> {
                    annotation.range = args[0] as TextRange
                    builder
                }

                "highlightType",
                "textAttributes" -> builder
                "tooltip" -> {
                    annotation.tooltip = args[0] as String?
                    builder
                }

                "newFix" -> {
                    val fix = Fix(args[0] as IntentionAction)
                    proxy(AnnotationBuilder.FixBuilder::class.java) { fixObject, fixMethod, fixArgs ->
                        when (fixMethod.name) {
                            "range" -> {
                                fix.textRange = fixArgs[0] as TextRange
                                fixObject
                            }

                            "registerFix" -> {
                                annotation.quickFixes.add(fix)
                                builder
                            }

                            else -> throw java.lang.AssertionError("Unexpected fix-builder call: $fixMethod")
                        }
                    }
                }

                "create" -> {
                    add(annotation)
                    null
                }

                else -> throw java.lang.AssertionError("Unexpected annotation-builder call: $method")
            }
        }
    }

    companion object {
        private fun <T : Any> proxy(type: Class<T>, handler: InvocationHandler): T {
            return type.cast(Proxy.newProxyInstance(type.classLoader, arrayOf<Class<*>>(type), handler))
        }
    }
}
