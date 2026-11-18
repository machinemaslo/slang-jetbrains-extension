import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask
import org.gradle.api.artifacts.type.ArtifactTypeDefinition.ARTIFACT_TYPE_ATTRIBUTE

version = "0.0.24"
group = "slang"

plugins {
    id("java")
    idea
    kotlin("jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

idea {
    module {
        excludeDirs.add(file("vcpkg_installed"))
    }
}

repositories {
    maven("https://cache-redirector.jetbrains.com/intellij-dependencies")
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
        jetbrainsRuntime()
    }
}

dependencies {
    intellijPlatform {
        // Local IDEs are opt-in; release builds always use the pinned baseline.
        val localIdePath = providers.gradleProperty("localIdePath").orNull
        if (localIdePath != null) {
            local(localIdePath)
        } else {
            intellijIdeaCommunity("2025.2.6.4")
        }
        pluginVerifier("1.410")
        zipSigner()

        plugin("com.redhat.devtools.lsp4ij:${providers.gradleProperty("lsp4ijVersion").getOrElse("0.21.0")}")
        bundledModule("intellij.platform.vcs.impl") // Exercise the real pre-commit analysis service in tests.
        // Newer IDEs split the spellchecker out of the baseline platform compile classpath.
        if (localIdePath != null && providers.gradleProperty("mcpSmokeTest").map(String::toBoolean).getOrElse(false)) {
            bundledModule("intellij.spellchecker")
        }
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
    // Use the IDE's Gson; do not bundle a separate copy across plugin classloaders.
    compileOnly("com.google.code.gson:gson:2.11.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testImplementation("junit:junit:4.13.2") // IntelliJ's test fixtures extend JUnit 3 classes.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.0")
}

java {
    // Use the JDK running Gradle; --release below preserves Java 21 API/bytecode compatibility.
    toolchain.languageVersion = JavaLanguageVersion.of(JavaVersion.current().majorVersion.toInt())
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

val windowsHost = System.getProperty("os.name").startsWith("Windows")
val hostOs = when {
    windowsHost -> "windows"
    System.getProperty("os.name").startsWith("Mac") -> "osx"
    else -> "linux"
}
val hostArch: String = when (System.getProperty("os.arch").lowercase()) {
    "amd64", "x86_64" -> "x64"
    "aarch64", "arm64" -> "arm64"
    else -> System.getProperty("os.arch")
}
val testTriplet = "$hostArch-$hostOs"
val vcpkgName = if (windowsHost) "vcpkg.exe" else "vcpkg"
val vcpkgRoot = providers.environmentVariable("VCPKG_ROOT").getOrElse("")
val testInstalled = layout.projectDirectory.dir("vcpkg_installed")
val testSlangd = testInstalled.file("$testTriplet/tools/shader-slang/${if (windowsHost) "slangd.exe" else "slangd"}")
val testClangFormat = testInstalled.file("$testTriplet/tools/clang-format/${if (windowsHost) "clang-format.exe" else "clang-format"}")
val generateClangFormatPort = tasks.register<GenerateClangFormatPort>("generateClangFormatPort") {
    description = "Resolve a prebuilt clang-format matching LLVM at the project's vcpkg baseline."
    group = "verification"
    manifest.set(layout.projectDirectory.file("vcpkg.json"))
    hostPlatform.set(testTriplet)
    portDirectory.set(layout.buildDirectory.dir("generated/vcpkg-ports/clang-format"))
}
val installTestDependencies = tasks.register<Exec>("installTestDependencies") {
    val configuredRoot = vcpkgRoot
    dependsOn(generateClangFormatPort)
    description = "Install the pinned Slang and clang-format test dependencies using vcpkg."
    group = "verification"
    workingDir(layout.projectDirectory)
    commandLine(
        if (vcpkgRoot.isNotBlank()) File(vcpkgRoot, vcpkgName).absolutePath else vcpkgName,
        "install", "--triplet=$testTriplet", "--host-triplet=$testTriplet",
        "--x-install-root=${testInstalled.asFile.absolutePath}",
        "--overlay-ports=${generateClangFormatPort.get().portDirectory.get().asFile.absolutePath}"
    )
    // Let vcpkg check its package state on every run; unchanged installs are inexpensive.
    doFirst {
        require(configuredRoot.isNotBlank()) {
            "Tests require VCPKG_ROOT to point to a bootstrapped vcpkg checkout."
        }
    }
}

val home = providers.systemProperty("user.home").get()
val toolboxDataRoot = when (hostOs) {
    "windows" -> providers.environmentVariable("LOCALAPPDATA").getOrElse("$home/AppData/Local")
    "osx" -> "$home/Library/Application Support"
    else -> providers.environmentVariable("XDG_DATA_HOME").filter { it.isNotBlank() }.getOrElse("$home/.local/share")
}
val toolboxInventory = providers.fileContents(
    layout.projectDirectory.file("$toolboxDataRoot/JetBrains/Toolbox/state.json")
).asText.orElse("")

// Use the newer MCP API and descriptor for compilation and DevKit indexing, not at runtime.
val mcpSdkDependency = configurations.dependencyScope("mcpSdkDependency")
val mcpSdkArchive = configurations.resolvable("mcpSdkArchive") { extendsFrom(mcpSdkDependency.get()) }
dependencies {
    add("mcpSdkDependency", "com.jetbrains.intellij.idea:idea:2026.3@zip")
    registerTransform(ExtractMcpSdk::class) {
        from.attribute(ARTIFACT_TYPE_ATTRIBUTE, "zip")
        to.attribute(ARTIFACT_TYPE_ATTRIBUTE, "slang-mcp-sdk")
    }
}
val mcpSdkFiles = providers.gradleProperty("mcpSdkPath").orNull?.let { path ->
    val api = file(path)
    val descriptor = api.resolveSibling("mcpserver-plugin.jar")
    require(api.isFile && descriptor.isFile) {
        "mcpSdkPath must point to intellij.mcpserver.jar next to mcpserver-plugin.jar."
    }
    files(api, descriptor)
} ?: mcpSdkArchive.get().incoming.artifactView {
    attributes.attribute(ARTIFACT_TYPE_ATTRIBUTE, "slang-mcp-sdk")
}.files
dependencies {
    compileOnly(mcpSdkFiles)
}
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    // Do not generate calls to MCP interface defaults introduced after the minimum IDE build.
    compilerOptions.jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
}

fun registerPluginPlayground(
    taskName: String,
    ideName: String,
    suffix: String,
    vararg productCodes: String,
) {
    val installation = toolboxInventory.map { ToolboxInstallations.find(it, ideName, productCodes.toList()) }
    val playground = layout.buildDirectory.dir("playground$suffix")
    val preparePlayground = tasks.register<PreparePlayground>("preparePlayground$suffix") {
        group = "intellij platform"
        description = "Refresh the $ideName playground templates and connect installed native tools."
        templateDirectory.set(layout.projectDirectory.dir("dev/playground"))
        playgroundDirectory.set(playground)
        slangdCandidate.set(testSlangd.asFile.absolutePath)
        clangFormatCandidate.set(testClangFormat.asFile.absolutePath)
        searchPath.set(providers.environmentVariable("PATH").getOrElse(""))
        outputs.upToDateWhen { false }
    }

    val syncAppearance = tasks.register<SyncSandboxAppearance>("syncSandboxAppearance$suffix") {
        group = "intellij platform"
        description = "Copy the user's IDEA appearance settings into the $ideName sandbox."
        val home = providers.systemProperty("user.home").get()
        val configRoot = when (hostOs) {
            "windows" -> providers.environmentVariable("APPDATA").getOrElse("$home/AppData/Roaming")
            "osx" -> "$home/Library/Application Support"
            else -> providers.environmentVariable("XDG_CONFIG_HOME").getOrElse("$home/.config")
        }
        userConfigRoot.set("$configRoot/JetBrains")
    }

    intellijPlatformTesting.runIde.register(taskName) {
        localPath.set(layout.dir(installation))
        plugins {
            plugin("com.redhat.devtools.lsp4ij:${providers.gradleProperty("lsp4ijVersion").getOrElse("0.21.0")}")
            if (suffix == "CLion" && !providers.gradleProperty("playgroundPython").map(String::toBoolean).getOrElse(false)) {
                // Some Toolbox CLion builds ship a Python ML module with a broken classloader dependency.
                disablePlugin("PythonCore")
            }
        }
        task {
            description = "Launch Toolbox's installed $ideName with the Slang plugin and refreshed playground."
            dependsOn(preparePlayground, syncAppearance)
            args(playground.get().asFile.absolutePath)
        }
    }

    syncAppearance.configure {
        val sandbox = tasks.named<PrepareSandboxTask>("prepareSandbox_$taskName")
        dependsOn(sandbox)
        sandboxConfigDirectory.set(sandbox.flatMap { it.sandboxConfigDirectory })
    }
}

registerPluginPlayground("runPlugin", "IntelliJ IDEA", "", "IU", "IC")
registerPluginPlayground("runPluginCLion", "CLion", "CLion", "CL")

tasks {
    withType<JavaCompile> {
        options.release.set(21)
        options.compilerArgs.add("-Xlint:deprecation")
    }

    if (providers.gradleProperty("mcpSmokeTest").map(String::toBoolean).getOrElse(false)) {
        compileTestJava {
            // This smoke suite targets the newer MCP SDK, not the baseline test APIs.
            include("slanglsp/mcp/McpRegistrationTest.java")
        }
    }

    test {
        dependsOn(installTestDependencies)
        val mcpSmokeTest = providers.gradleProperty("mcpSmokeTest").map(String::toBoolean).getOrElse(false)
        useJUnitPlatform {
            if (mcpSmokeTest) {
                excludeEngines("junit-vintage")
            }
        }
        systemProperty("junit.jupiter.testclass.order.default", $$"org.junit.jupiter.api.ClassOrderer$ClassName")
        if (!mcpSmokeTest) {
            systemProperty("idea.load.plugins.id", "slanglsp_r")
        }
        systemProperty("slang.test.mcp", mcpSmokeTest)
        systemProperty("idea.log.config.properties.file",
            layout.projectDirectory.file("src/test/resources/test-log.properties").asFile.absolutePath)
        inputs.file("src/test/resources/test-log.properties").withPropertyName("testLoggingConfig")
        systemProperty("slang.test.executable", testSlangd.asFile.absolutePath)
        systemProperty("slang.test.clangFormat", testClangFormat.asFile.absolutePath)
        systemProperty("slang.test.vcpkgRoot", vcpkgRoot)
        systemProperty("slang.test.manifest", layout.projectDirectory.file("vcpkg.json").asFile.absolutePath)
        inputs.file(layout.projectDirectory.file("vcpkg.json")).withPropertyName("vcpkgManifest")
        inputs.file(testSlangd).withPropertyName("nativeSlangd")
        inputs.file(testClangFormat).withPropertyName("nativeClangFormat")
        testLogging {
            events("passed", "skipped", "failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showStandardStreams = true
        }
    }

    check {
        dependsOn(verifyPlugin)
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "252.28539.149"
            // Allow future IDE builds; verification still checks actual API compatibility.
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        failureLevel = listOf(
            VerifyPluginTask.FailureLevel.COMPATIBILITY_PROBLEMS,
            VerifyPluginTask.FailureLevel.INTERNAL_API_USAGES,
            VerifyPluginTask.FailureLevel.OVERRIDE_ONLY_API_USAGES,
            VerifyPluginTask.FailureLevel.NON_EXTENDABLE_API_USAGES,
            VerifyPluginTask.FailureLevel.DEPRECATED_API_USAGES,
            VerifyPluginTask.FailureLevel.MISSING_DEPENDENCIES,
            VerifyPluginTask.FailureLevel.INVALID_PLUGIN,
        )
        ides {
            val verificationIdePath = providers.gradleProperty("verificationIdePath").orNull
            if (verificationIdePath != null) {
                local(file(verificationIdePath))
            } else {
                latest {
                    types = listOf(IntelliJPlatformType.CLion)
                    channels = listOf(ProductRelease.Channel.RELEASE)
                }
            }
        }
    }
}
