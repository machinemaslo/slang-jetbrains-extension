package slanglsp.editor.rename

import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.util.concurrency.AppExecutorUtil
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.jetbrains.annotations.Nls
import slanglsp.SlangBundle
import slanglsp.analysis.ProjectTrust
import slanglsp.editor.navigation.cpp.ReflectionMetadataLookup
import slanglsp.editor.navigation.cpp.isRiderForWindows
import slanglsp.editor.navigation.cpp.notify
import slanglsp.editor.rename.ReflectionRenameRegistration.Entry
import slanglsp.editor.rename.ReflectionRenameRegistration.GenerationTask

/** A per-project queue: merge Rename edits and never run two generation tasks concurrently. */
@Service(Service.Level.PROJECT)
internal class ReflectionRenameRegeneration(private val project: Project, scope: CoroutineScope) {
    private data class Change(
        val path: Path,
        val file: VirtualFile,
        val document: Document,
        val stamp: Long,
    ) {
        fun isCurrent(): Boolean = file.isValid && localPath(file) == path && document.modificationStamp == stamp
    }

    private data class Request(
        val task: GenerationTask,
        val inputs: Set<Path>,
        val isCurrent: () -> Boolean = { true },
        val verifyEvaluation: ((String) -> Unit)? = null,
    )

    private val pending = linkedMapOf<Path, Change>()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            while (wake.receiveCatching().isSuccess) {
                delay(200.milliseconds)
                val changes = synchronized(pending) {
                    pending.toMap().also { pending.clear() }
                }
                if (changes.isEmpty() || project.isDisposed || !ProjectTrust.isTrusted(project)) {
                    continue
                }
                // Cancelling the progress task cancels this child, not the queue for future renames.
                launch {
                    try {
                        regenerate(changes)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (failure: Exception) {
                        log.debug("Could not load reflection rename tasks", failure)
                        report(SlangBundle.message("cpp.reflection.rename.invalid"))
                    }
                }.join()
            }
        }
    }

    /** Call with the final document revisions only after a successful refactoring. */
    fun renamed(documents: Iterable<Document>) {
        if (project.isDisposed || !ProjectTrust.isTrusted(project)) {
            return
        }
        val manager = FileDocumentManager.getInstance()
        val changes = documents.mapNotNull { document ->
            val file = manager.getFile(document)?.takeIf { it.isValid && it.isInLocalFileSystem } ?: return@mapNotNull null
            Change(localPath(file), file, document, document.modificationStamp)
        }
        if (changes.isNotEmpty()) {
            synchronized(pending) {
                for (change in changes) {
                    pending[change.path] = change
                }
            }
            wake.trySend(Unit)
        }
    }

    private suspend fun regenerate(changes: Map<Path, Change>) = withContext(Dispatchers.IO) {
        val workspace = Path.of(project.basePath ?: return@withContext).toAbsolutePath().normalize()
        val registered = ReflectionRenameRegistration.read(workspace)?.affected(changes.keys).orEmpty()
        val automatic = affectedProducers(workspace, changes.keys)
        val explicitByTarget = registered.entries.groupBy({ key(it.key) }, { it })
        val automaticKeys = automatic.mapTo(hashSetOf()) { key(it.task) }
        val requests = automatic.map { request ->
            val extra = explicitByTarget[key(request.task)].orEmpty()
            if (extra.isEmpty()) {
                request
            } else {
                // Preserve the explicit editable-input guards without running the same target twice.
                val task = request.task
                request.copy(
                    task = GenerationTask(task.entry, task.configuration, task.target, task.tool,
                        task.inputs + extra.flatMap { it.key.inputs }),
                    inputs = request.inputs + extra.flatMap { it.value },
                )
            }
        } + registered.mapNotNull { (task, inputs) ->
            Request(task, inputs).takeUnless { key(task) in automaticKeys }
        }
        if (requests.isEmpty()) {
            return@withContext
        }
        val realWorkspace = workspace.toRealPath()
        for ((task, affectedInputs, isCurrent, verifyEvaluation) in requests) {
            currentCoroutineContext().ensureActive()
            try {
                val changedInputs = affectedInputs.map(changes::getValue)
                if (changedInputs.any { !it.path.toRealPath().startsWith(realWorkspace) }) {
                    continue
                }
                if (!isCurrent()) {
                    continue
                }
                task.verifyBuild(realWorkspace)
                val executable = tool(task)
                val ready = withContext(Dispatchers.EDT) { saveChangedInputs(workspace, task, changedInputs) }
                if (!ready || project.isDisposed || !ProjectTrust.isTrusted(project) || !isCurrent()) {
                    continue
                }
                withBackgroundProgress(project, SlangBundle.message("cpp.reflection.rename.progress", task.target)) {
                    if (verifyEvaluation != null) {
                        val evaluation = runInterruptible(Dispatchers.IO) {
                            execute(executable, workspace, task.evaluationArguments(), timeoutMinutes = 1)
                        }
                        verifyEvaluation(evaluation)
                        if (project.isDisposed || !ProjectTrust.isTrusted(project) || !withContext(Dispatchers.IO) { isCurrent() } ||
                            !withContext(Dispatchers.EDT) { saveChangedInputs(workspace, task, changedInputs) }) {
                            return@withBackgroundProgress
                        }
                    }
                    runInterruptible(Dispatchers.IO) { execute(executable, workspace, task.arguments()) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                log.info("Reflection generation failed for ${task.target}", failure)
                report(SlangBundle.message("cpp.reflection.rename.failed", task.target))
            }
        }
    }

    private fun affectedProducers(workspace: Path, changed: Set<Path>): List<Request> {
        if (isRiderForWindows()) {
            val indicator = EmptyProgressIndicator()
            val lookup = project.service<ReflectionMetadataLookup>()
            return MsBuildReflectionProducer.plan(project, workspace, changed, lookup, indicator).map { found ->
                Request(found.task, found.affected, { found.isCurrent(project, workspace) }, found::verifyEvaluation)
            }
        }
        if (!isClion()) {
            return emptyList()
        }
        val directories = CMakeReflectionProducer.buildDirectories(project, workspace)
        return CMakeReflectionProducer.plan(
            workspace, directories, changed, project.service<ReflectionMetadataLookup>(), EmptyProgressIndicator(),
        ).map { found ->
            Request(found.task, found.affected, found.isCurrent)
        }
    }

    private data class TargetKey(val system: String, val path: Path, val configuration: String, val platform: String, val target: String)

    private fun key(task: GenerationTask): TargetKey {
        val entry = task.entry
        val path = when (entry) {
            is Entry.CMake -> entry.directory
            is Entry.MsBuild -> entry.project
        }
        val canonical = try {
            path.toRealPath()
        } catch (_: IOException) {
            path
        }
        return when (entry) {
            is Entry.CMake -> TargetKey("cmake", canonical, task.configuration, "", task.target)
            is Entry.MsBuild -> TargetKey("msbuild", canonical, task.configuration, entry.platform, task.target)
        }
    }

    private fun saveChangedInputs(workspace: Path, task: GenerationTask, changes: List<Change>): Boolean {
        if (project.isDisposed || !ProjectTrust.isTrusted(project) || changes.any { !it.isCurrent() }) {
            return false
        }
        val manager = FileDocumentManager.getInstance()
        val renamed = changes.mapTo(hashSetOf()) { it.document }
        val registration = workspace.resolve(ReflectionRenameRegistration.FILE_NAME)
        fun hasOtherUnsavedInputs(): Boolean = manager.unsavedDocuments.any { document ->
            if (document in renamed) {
                false
            } else {
                val file = manager.getFile(document)
                val path = file?.takeIf { it.isInLocalFileSystem }?.let(::localPath)
                path != null && (path == registration || path in task.inputs)
            }
        }

        // Don't save anything from outside the completed Rename, or run against unrelated dirty inputs.
        if (hasOtherUnsavedInputs()) {
            return false
        }
        for (change in changes) {
            if (!change.isCurrent()) {
                return false
            }
            if (manager.isDocumentUnsaved(change.document)) {
                manager.saveDocumentAsIs(change.document)
            }
            if (manager.isDocumentUnsaved(change.document)) {
                return false
            }
        }
        return !hasOtherUnsavedInputs()
    }

    private fun tool(task: GenerationTask): Path {
        val executable = task.tool ?: PathEnvironmentVariableUtil.findInPath(task.executableName)?.toPath()
        return executable?.takeIf { it.isAbsolute && Files.isRegularFile(it) && Files.isExecutable(it) }
            ?: throw IOException("Cannot find ${task.executableName}")
    }

    private fun execute(executable: Path, workspace: Path, arguments: List<String>, timeoutMinutes: Long = 10): String {
        val process = ProcessBuilder(listOf(executable.toString()) + arguments)
            .directory(workspace.toFile())
            .redirectErrorStream(true)
            .start()
        val output = BuildLog()
        val drain = AppExecutorUtil.getAppExecutorService().submit {
            try {
                process.inputStream.use { stream ->
                    val buffer = ByteArray(4096)
                    while (true) {
                        val size = stream.read(buffer)
                        if (size < 0) {
                            break
                        }
                        output.append(buffer, size)
                    }
                }
            } catch (_: IOException) {
                // The process may have been stopped on cancellation.
            }
        }
        try {
            process.outputStream.close()
            if (!process.waitFor(timeoutMinutes, TimeUnit.MINUTES)) {
                throw IOException("Reflection generation timed out")
            }
            try {
                drain.get(1, TimeUnit.SECONDS)
            } catch (_: TimeoutException) {
                // Do not wait indefinitely if another process inherited the output pipe.
            }
            if (process.exitValue() != 0) {
                throw IOException("Reflection generation exited with ${process.exitValue()}\n${output.text()}")
            }
            return output.text()
        } finally {
            if (process.isAlive) {
                val descendants = process.descendants().use { it.toList() }
                process.destroyForcibly()
                descendants.forEach { it.destroyForcibly() }
            }
            drain.cancel(true)
        }
    }

    private suspend fun report(@Nls message: String) {
        withContext(Dispatchers.EDT) {
            notify(project, message)
        }
    }

    private class BuildLog {
        private val bytes = ByteArray(8192)
        private var length = 0

        @Synchronized
        fun append(chunk: ByteArray, length: Int) {
            val keep = minOf(this.length, bytes.size - length)
            bytes.copyInto(bytes, 0, this.length - keep, this.length)
            chunk.copyInto(bytes, keep, 0, length)
            this.length = keep + length
        }

        @Synchronized
        fun text(): String = bytes.decodeToString(endIndex = length)
    }

    private companion object {
        val log = Logger.getInstance(ReflectionRenameRegeneration::class.java)

        fun localPath(file: VirtualFile): Path = file.toNioPath().toAbsolutePath().normalize()

        fun isClion(): Boolean = ApplicationInfo.getInstance().build.productCode == "CL"
    }
}
