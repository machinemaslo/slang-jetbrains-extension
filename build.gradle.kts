import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.tasks.VerifyPluginTask

version = "0.0.19"
group = "slang"

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.19.0"
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
            intellijIdeaCommunity("2024.2.6")
        }
        pluginVerifier("1.410")
        zipSigner()

        plugin("com.redhat.devtools.lsp4ij:${providers.gradleProperty("lsp4ijVersion").getOrElse("0.21.0")}")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
    // Use the IDE's Gson; do not bundle a separate copy across plugin classloaders.
    compileOnly("com.google.code.gson:gson:2.11.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testImplementation("junit:junit:4.13.2") // IntelliJ's test fixtures extend JUnit 3 classes.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.0")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks {
    withType<JavaCompile> {
        options.release.set(21)
        options.compilerArgs.add("-Xlint:deprecation")
    }

    test {
        useJUnitPlatform()
        systemProperty("idea.load.plugins.id", "slanglsp_r")
    }

    check {
        dependsOn(verifyPlugin)
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "242.26775.15"
            // Expand only after verifying the next platform branch and its dependencies.
            untilBuild = "262.*"
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
            // Comma-separated local installations are useful for offline checks.
            val localPaths = providers.gradleProperty("verificationIdePaths").orNull
            if (localPaths != null) {
                localPaths.split(',').forEach { local(file(it.trim())) }
            } else {
                create(IntelliJPlatformType.IntellijIdeaCommunity, "2024.2.6")
                create(IntelliJPlatformType.IntellijIdea, "2026.2.3")
                create(IntelliJPlatformType.CLion, "2026.2.2")
            }
        }
    }
}
