package slanglsp.mcp

import com.intellij.mcpserver.McpExpectedError
import com.intellij.mcpserver.McpToolset
import com.intellij.mcpserver.annotations.McpDescription
import com.intellij.mcpserver.annotations.McpTool
import com.intellij.mcpserver.project
import java.io.IOException
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runInterruptible

/** Loaded only when JetBrains' MCP Server plugin is available. */
class SlangToolset : McpToolset {
    // These tools are stable; the platform default marks them experimental.
    //noinspection SameReturnValue
    override fun isExperimental(): Boolean = false

    // Opt into the platform's always-included tool filter.
    //noinspection SameReturnValue
    override fun alwaysIncluded(): Boolean = true

    @McpTool(name = "slang_lookup")
    @McpDescription(
        "Look up Slang shader syntax, language features, intrinsics, and examples in official documentation. " +
            "Returns up to three short cited results, following relevant links from Slang's LLM index. " +
            "Supply the installed Slang version when known to prefer matching release sources.",
    )
    suspend fun lookup(
        @McpDescription("Slang topic or identifier, such as generic value parameters or saturate.") query: String,
        @McpDescription("Installed Slang SDK version, such as 2026.22, if known.") sdkVersion: String? = null,
    ): String = perform { OfficialSlangDocs.lookup(query, sdkVersion) }

    @McpTool(name = "slang_check_files")
    @McpDescription(
        "Check edited Slang shaders with the project's configured Slang compiler, include paths, and macros. " +
            "Checks current editor contents without saving or generating shader binaries. " +
            "Returns compact diagnostics with 1-based line and column numbers; incomplete checks are flagged.",
    )
    suspend fun checkFiles(
        @McpDescription("One to sixteen project-relative Slang file paths.") files: List<String>,
    ): String {
        val project = currentCoroutineContext().project
        return perform { AgentAnalysis.checkFiles(project, files) }
    }

    @McpTool(name = "slang_symbol")
    @McpDescription(
        "Resolve a Slang symbol through the language server. Use for imports and overloaded names instead of guessing from text matches. " +
            "Returns definition locations and short declaration excerpts, plus project references when requested.",
    )
    suspend fun symbol(
        @McpDescription("Project-relative Slang file path.") file: String,
        @McpDescription("1-based source line.") line: Int,
        @McpDescription("1-based UTF-16 column pointing at the identifier.") column: Int,
        @McpDescription("Also return up to 20 project references (default: false).") references: Boolean = false,
    ): String {
        val project = currentCoroutineContext().project
        return perform { AgentAnalysis.symbol(project, file, line, column, references) }
    }

    private suspend fun perform(action: () -> String): String {
        try {
            return runInterruptible(Dispatchers.IO) { action() }
        } catch (failure: IllegalArgumentException) {
            throw McpExpectedError(failure.message ?: "Invalid Slang tool input.")
        } catch (failure: IOException) {
            throw McpExpectedError(failure.message ?: "Could not access the requested Slang source.")
        } catch (failure: CompletionException) {
            if (failure.cause is TimeoutException) {
                throw McpExpectedError("Slang symbol lookup timed out.")
            }
            throw failure
        }
    }
}
