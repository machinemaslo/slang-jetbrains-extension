package slanglsp.mcp.skills

import com.intellij.mcpserver.skills.AgentSkillTarget
import com.intellij.mcpserver.skills.BundledSkill
import com.intellij.mcpserver.skills.BundledSkillsProvider

internal class AgentGuideProvider : BundledSkillsProvider {
    override fun registeredSkills(): List<BundledSkill> {
        val tools = setOf("slang_lookup", "slang_check_files", "slang_symbol")
        return listOf(
            BundledSkill(
                skillId = "slang-tools",
                tools = tools,
                targets = AgentSkillTarget.entries.toSet(),
                resourcePath = "bundledSkills/slang-tools",
                enabledRegistryKey = null,
                requiredTools = tools,
            ),
        )
    }
}
