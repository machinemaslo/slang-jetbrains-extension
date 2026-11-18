package slanglsp.mcp;

import com.intellij.mcpserver.McpToolset;
import com.intellij.openapi.application.ApplicationInfo;

public abstract class CompatibleToolset implements McpToolset {
    // A marker interface in 2025.2; newer servers call this to decide whether to expose the tools.
    public final boolean isExperimental() {
        return false;
    }

    // No @Override: the method isn't declared on the 2025.2 SDK's interface.
    public final boolean alwaysIncluded() {
        return ApplicationInfo.getInstance().getBuild().getBaselineVersion() >= 262;
    }
}
