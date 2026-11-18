import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.models.ProductRelease
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

version = "0.0.23"
group = "slang"

plugins {
    id("java")
    idea
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
            intellijIdeaCommunity("2025.1")
        }
        pluginVerifier("1.410")
        zipSigner()

        plugin("com.redhat.devtools.lsp4ij:${providers.gradleProperty("lsp4ijVersion").getOrElse("0.21.0")}")
        bundledModule("intellij.platform.vcs.impl") // Exercise the real pre-commit analysis service in tests.
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

    test {
        dependsOn(installTestDependencies)
        useJUnitPlatform()
        systemProperty("junit.jupiter.testclass.order.default", $$"org.junit.jupiter.api.ClassOrderer$ClassName")
        systemProperty("idea.load.plugins.id", "slanglsp_r")
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
            sinceBuild = "251"
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
