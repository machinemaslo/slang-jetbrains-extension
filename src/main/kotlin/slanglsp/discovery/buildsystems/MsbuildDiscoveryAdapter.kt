package slanglsp.discovery.buildsystems

import java.nio.file.Path
import slanglsp.discovery.DiscoveryContext

/** Reads literal MSBuild properties and imports without evaluating conditions or property functions. */
internal class MsbuildDiscoveryAdapter : DiscoveryAdapter {
    override fun discover(context: DiscoveryContext) {
        val projects = findProjects(context).ifEmpty { setOf(context.root.resolve("Directory.Build.props")) }
        for (project in projects.take(32)) {
            findInstallations(context, project.parent, readProjectProperties(context, project))
        }
    }

    private fun findProjects(context: DiscoveryContext): Set<Path> = buildSet {
        addAll(context.children(context.root, "*.vcxproj"))
        addAll(context.children(context.root, "*.csproj"))

        for (solution in context.children(context.root, "*.slnx")) {
            val xml = context.xml(solution) ?: continue
            val projectPaths = xml.getElementsByTagName("Project").elements().map { it.getAttribute("Path") }
            for (path in projectPaths.takeWhile { size < 32 }) {
                context.resolve(solution.parent, path)?.let(::add)
            }
        }

        for (solution in context.children(context.root, "*.sln")) {
            for (match in solutionProject.findAll(context.read(solution)).takeWhile { size < 32 }) {
                context.resolve(solution.parent, match.groupValues[1])?.let(::add)
            }
        }
    }

    private fun readProjectProperties(context: DiscoveryContext, project: Path): Map<String, String?> {
        val directory = project.parent
        val properties =
            mutableMapOf<String, String?>().apply {
                putAll(context.environment)
                put("MSBuildProjectDirectory", directory.toString())
                put("SolutionDir", "${context.root}/")
            }
        val reader = PropertiesReader(context, properties)

        // Use the nearest props before the project and the nearest targets after it.
        nearestBuildFile(context, directory, "Directory.Build.props")?.let(reader::read)
        reader.read(project)
        nearestBuildFile(context, directory, "Directory.Build.targets")?.let(reader::read)
        return properties
    }

    private fun nearestBuildFile(context: DiscoveryContext, directory: Path, name: String): Path? =
        generateSequence(directory) { it.parent }
            .take(8)
            .map { it.resolve(name) }
            .firstOrNull { context.read(it).isNotBlank() }

    private fun findInstallations(context: DiscoveryContext, directory: Path, properties: Map<String, String?>) {
        fun expanded(key: String) = DiscoveryContext.expand(properties[key], properties)

        val host = properties["VcpkgHostTriplet"]
        context.resolve(directory, expanded("VcpkgInstalledDir"))?.let { installed ->
            // MSBuild manifest mode includes the target triplet in this path.
            val target = properties["VcpkgTriplet"]
            val root =
                if (target != null && installed.fileName?.toString() == target) {
                    installed.parent
                } else {
                    installed
                }
            context.installed(root, ".", host)
        }
        context.installed(directory, expanded("VcpkgManifestInstalledBaseDir"), host)
        context.resolve(directory, expanded("VcpkgManifestRoot"))?.let { manifest ->
            context.installed(manifest, "vcpkg_installed", host)
        }
        context.installed(directory, "vcpkg_installed", host)
        context.vcpkgRoot(directory, expanded("VcpkgRoot"))
    }

    private class PropertiesReader(
        private val context: DiscoveryContext,
        private val properties: MutableMap<String, String?>,
    ) {
        private val visited = mutableSetOf<Path>()

        fun read(path: Path) {
            val file = path.toAbsolutePath().normalize()
            if (visited.size >= 24 || !visited.add(file)) {
                return
            }
            val document = context.xml(file) ?: return
            val previousDirectory = properties.put(FILE_DIRECTORY_KEY, "${file.parent}/")
            try {
                for (element in document.documentElement.childElements()) {
                    if (element.getAttribute("Condition").isNotBlank()) {
                        continue
                    }
                    when (element.tagName) {
                        "PropertyGroup" -> {
                            for (property in element.childElements()) {
                                if (property.getAttribute("Condition").isBlank()) {
                                    properties[property.tagName] = expand(property.textContent.trim { it <= ' ' })
                                }
                            }
                        }
                        "Import" -> readImport(file.parent, element.getAttribute("Project"))
                    }
                }
            } finally {
                if (previousDirectory == null) {
                    properties.remove(FILE_DIRECTORY_KEY)
                } else {
                    properties[FILE_DIRECTORY_KEY] = previousDirectory
                }
            }
        }

        private fun readImport(directory: Path, expression: String) {
            val imported = context.resolve(directory, expand(expression)) ?: return
            val normalized = imported.toString().replace('\\', '/')
            if (normalized.endsWith(VCPKG_PROPS_SUFFIX)) {
                properties["VcpkgRoot"] = normalized.removeSuffix(VCPKG_PROPS_SUFFIX)
            } else {
                read(imported)
            }
        }

        private fun expand(value: String): String? = DiscoveryContext.expand(value, properties)
    }

    private companion object {
        const val FILE_DIRECTORY_KEY = "MSBuildThisFileDirectory"
        const val VCPKG_PROPS_SUFFIX = "/scripts/buildsystems/msbuild/vcpkg.props"
        val solutionProject = Regex("(?m)^Project\\([^\\r\\n]+?=\\s*\"[^\"]*\",\\s*\"([^\"]+\\.(?:vcxproj|csproj))\"")
    }
}
