import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import java.util.Properties

fun getProjectVersion():String = "0.0.17"
project.version = getProjectVersion()
group = "slang"

plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

val localBuildProperties = Properties().apply {
    val propertiesFile = rootProject.file("local.properties")
    if (propertiesFile.isFile) propertiesFile.inputStream().use { load(it) }
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
        val localIdePath = providers.gradleProperty("localIdePath").orNull
            ?: localBuildProperties.getProperty("localIdePath")
        if (localIdePath != null) {
            local(localIdePath)
        } else {
            intellijIdeaCommunity("2024.1.4")
        }
        pluginVerifier()
        zipSigner()

        plugin("com.redhat.devtools.lsp4ij:${providers.gradleProperty("lsp4ijVersion").getOrElse("0.13.0")}")
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
    // Use the IDE's Gson; do not bundle a separate copy across plugin classloaders.
    compileOnly("com.google.code.gson:gson:2.11.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    testImplementation("junit:junit:4.13.2") // IntelliJ's test fixtures extend JUnit 3 classes.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.0")
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
        options.release.set(17)
        localBuildProperties.getProperty("compilerJavaHome")?.let {
            options.isFork = true
            options.forkOptions.javaHome = file(it)
        }
    }

    buildPlugin

    test {
        useJUnitPlatform()
        systemProperty("idea.load.plugins.id", "slanglsp")
    }

    runIde
    /*
    signPlugin {
        certificateChain.set(System.getenv("SLANG_LSP_CERTIFICATE_CHAIN"))
        privateKey.set(System.getenv("SLANG_LSP_PRIVATE_KEY"))
        password.set(System.getenv("SLANG_LSP_PRIVATE_KEY_PASSWORD"))
    }

    publishPlugin {
        token.set(System.getenv("SLANG_LSP_PUBLISH_TOKEN"))
    }
    */
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "241.0"
            untilBuild = provider { null }
        }
    }
    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdeaCommunity, "2024.1.4")
            create(IntelliJPlatformType.CLion, "2024.1.4")
        }
    }
}
