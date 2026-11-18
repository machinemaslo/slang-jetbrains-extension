package slanglsp.mcp;

import com.intellij.mcpserver.McpToolset;

public abstract class CompatibleToolset implements McpToolset {
    // A marker interface in 2025.2; newer servers call this to decide whether to expose the tools.
    public final boolean isExperimental() {
        return false;
    }
}
