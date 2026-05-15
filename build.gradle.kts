plugins {
    kotlin("jvm") version "2.0.21"
    id("io.papermc.paperweight.userdev") version "1.7.7"
    id("com.gradleup.shadow") version "8.3.5"
}

group = "com.axogc.paper"
version = "0.1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    paperweight.paperDevBundle("1.21.4-R0.1-SNAPSHOT")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

kotlin {
    jvmToolchain(21)
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
        options.release.set(21)
    }
    processResources {
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filesMatching("paper-plugin.yml") { expand(props) }
    }
    shadowJar {
        archiveClassifier.set("")
        archiveFileName.set("paper-platform-${project.version}.jar")
        // kotlin stdlib must travel with the jar — Paper no longer bundles it
        minimize {
            exclude(dependency("org.jetbrains.kotlin:.*"))
        }
    }
    assemble {
        dependsOn(reobfJar)
    }
    build {
        dependsOn(shadowJar)
    }
}
