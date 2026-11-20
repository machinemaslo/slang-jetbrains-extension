package slanglsp.analysis

import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.NlsContexts.ProgressTitle
import com.intellij.platform.ide.progress.withBackgroundProgress
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Runs blocking editor analysis with cancellable progress and delivers the result on the EDT. */
@Service(Service.Level.PROJECT)
internal class EditorAnalysisTasks(
    private val project: Project,
    private val scope: CoroutineScope,
) {
    private val serialQueue = Mutex()

    fun <T> launch(
        @ProgressTitle
        title: String,
        onFailure: ((Exception) -> Unit)? = null,
        serial: Boolean = false,
        compute: () -> T,
        onSuccess: (T) -> Unit,
    ): Job {
        if (!serial) {
            return scope.launch {
                analyzeAndApply(title, onFailure, compute, onSuccess)
            }
        }

        // Enter the queue in submission order and hold the lock through the EDT callback.
        return scope.launch(start = CoroutineStart.UNDISPATCHED) {
            serialQueue.withLock {
                analyzeAndApply(title, onFailure, compute, onSuccess)
            }
        }
    }

    private suspend fun <T> analyzeAndApply(
        @ProgressTitle
        title: String,
        onFailure: ((Exception) -> Unit)?,
        compute: () -> T,
        onSuccess: (T) -> Unit,
    ) {
        val result =
            try {
                withBackgroundProgress(project, title) {
                    // Analysis can block on LSP futures. Keep it off the EDT and interrupt it on cancellation.
                    runInterruptible(Dispatchers.IO, compute)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val report = onFailure ?: throw failure

                onEdt {
                    report(failure)
                }
                return
            }

        onEdt {
            onSuccess(result)
        }
    }

    private suspend fun onEdt(action: () -> Unit) {
        withContext(Dispatchers.EDT) {
            if (!project.isDisposed) {
                action()
            }
        }
    }
}
