pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/")
        maven("https://maven.architectury.dev")
        maven("https://maven.minecraftforge.net")
        maven("https://maven.neoforged.net/releases/")
        maven("https://maven.kikugie.dev/releases")
        maven("https://maven.kikugie.dev/snapshots")
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.6"
}

stonecutter {
    centralScript = "build.gradle.kts"
    kotlinController = true
    create(rootProject) {
        // Root `src/` is the loader-agnostic 'common' project; branches are loaders.
        // common is built for every version any loader targets.
        versions("1.21.4", "1.20.1")
        branch("fabric") { versions("1.21.4") }
        branch("neoforge") { versions("1.21.4") }
        branch("forge") { versions("1.20.1") }
    }
}

rootProject.name = "orebit"
