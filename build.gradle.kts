import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.3.21"
}

group = "net.axogc.paper"
version = "0.1.0"

// Build variant toggle: `-PtargetJava=21` produces a Paper 1.21.x / Java 21 jar.
// Default (no flag) keeps the Paper 26.1.2 / Java 25 build used by /srv/paper.
val targetJava: String = (findProperty("targetJava") as String?) ?: "25"
val isJava21Build = targetJava == "21"
val jdkVersion = if (isJava21Build) 21 else 25
val paperApiCoord = if (isJava21Build)
    "io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT"
else
    "io.papermc.paper:paper-api:26.1.2.build.63-stable"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Plain Paper API — no paperweight needed since we never touch NMS.
    compileOnly(paperApiCoord)
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(jdkVersion))
}

kotlin {
    jvmToolchain(jdkVersion)
    compilerOptions {
        jvmTarget.set(if (isJava21Build) JvmTarget.JVM_21 else JvmTarget.JVM_25)
    }
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(jdkVersion)
    }
    processResources {
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filesMatching("plugin.yml") { expand(props) }
    }
    jar {
        val classifier = if (isJava21Build) "-java21" else ""
        archiveFileName.set("paper-platform-${project.version}${classifier}.jar")
        // Embed kotlin-stdlib (Paper no longer bundles it). compileOnly deps are excluded.
        from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
            exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/9/module-info.class")
        }
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
}
