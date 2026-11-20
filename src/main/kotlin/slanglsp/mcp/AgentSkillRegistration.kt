package slanglsp.mcp

import com.intellij.ide.ApplicationInitializedListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Proxy
import java.net.URL

internal class RegisterAgentSkill : ApplicationInitializedListener {
    override suspend fun execute() {
        service<AgentSkillRegistration>().register()
    }
}

@Service(Service.Level.APP)
internal class AgentSkillRegistration : Disposable {
    private var registered = false

    @Synchronized
    fun register() {
        if (registered) {
            return
        }

        val point = ApplicationManager.getApplication().extensionArea
            .getExtensionPointIfRegistered<Any>(PROVIDER_EP) ?: return
        val api = point.pluginDescriptor.pluginClassLoader ?: return
        try {
            point.registerExtension(createProvider(api), this)
            registered = true
        } catch (failure: ReflectiveOperationException) {
            LOG.warn("Could not register the bundled Slang agent skill", failure)
        } catch (failure: LinkageError) {
            LOG.warn("Incompatible bundled agent skills API", failure)
        }
    }

    // This extension is available on newer IDEs, but absent from the minimum supported SDK.
    private fun createProvider(api: ClassLoader): Any {
        val provider = api.loadClass("com.intellij.mcpserver.skills.BundledSkillsProvider")
        val skillType = api.loadClass("com.intellij.mcpserver.skills.BundledSkill")
        val targetType = api.loadClass("com.intellij.mcpserver.skills.AgentSkillTarget")
        val skill = skillType.getConstructor(
            String::class.java, Set::class.java, Set::class.java, String::class.java, String::class.java, Set::class.java,
        ).newInstance(SKILL_ID, TOOLS, targetType.enumConstants.toSet(), RESOURCE_PATH, null, TOOLS)

        // The IDE resolves SKILL.md through the provider's classloader.
        val loader = object : ClassLoader(provider.classLoader) {
            override fun getResource(name: String): URL? {
                return if (name == RESOURCE_PATH || name.startsWith("$RESOURCE_PATH/")) {
                    AgentSkillRegistration::class.java.getResource("/$name")
                } else {
                    super.getResource(name)
                }
            }
        }
        return Proxy.newProxyInstance(loader, arrayOf(provider)) { instance, method, args ->
            when (method.name) {
                "registeredSkills" -> listOf(skill)
                "hashCode" -> System.identityHashCode(instance)
                "equals" -> instance === args?.singleOrNull()
                "toString" -> SKILL_ID
                else -> InvocationHandler.invokeDefault(instance, method, *(args ?: emptyArray()))
            }
        }
    }

    override fun dispose() = Unit

    companion object {
        internal const val PROVIDER_EP = "com.intellij.mcpServer.bundledSkillsProvider"
        private const val SKILL_ID = "slang-tools"
        internal const val RESOURCE_PATH = "bundledSkills/$SKILL_ID"
        private val TOOLS = setOf("slang_lookup", "slang_check_files", "slang_symbol")
        private val LOG = Logger.getInstance(AgentSkillRegistration::class.java)
    }
}
