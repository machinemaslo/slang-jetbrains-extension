pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "Unofficial-LSP-For-Slang [Refined]"
if (!providers.gradleProperty("coreOnly").map(String::toBoolean).getOrElse(false)) {
    include(":mcp-skills")
}
