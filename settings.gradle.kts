pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/")
        maven("https://maven.architectury.dev")
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
        // Add more Minecraft versions here (e.g. "1.20.1", "1.21.6") to expand the matrix.
        versions("1.21.4")
        branch("fabric")
        branch("neoforge")
    }
}

rootProject.name = "orebit"
