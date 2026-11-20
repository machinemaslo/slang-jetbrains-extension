plugins {
    kotlin("jvm")
    id("org.jetbrains.intellij.platform.module")
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Only this optional module needs the newer SDK; the main plugin still targets IDEA 2026.2.5.
        val skillsIdePath = providers.gradleProperty("skillsIdePath").orNull
        if (skillsIdePath != null) {
            local(skillsIdePath)
        } else {
            clion("2026.3.0.1")
        }
        bundledModule("intellij.mcpserver.skills")
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(JavaVersion.current().majorVersion.toInt()))
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(25)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_25)
    compilerOptions.apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4)
}

tasks.named<org.jetbrains.intellij.platform.gradle.tasks.ComposedJarTask>("composedJar") {
    archiveFileName.set("slanglsp.mcp.skills.jar")
}

intellijPlatform {
    instrumentCode = false
}
