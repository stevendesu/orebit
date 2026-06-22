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
        // common is built for every version any loader targets. `chiseledCompileCommon`
        // compiles common against every node to pin which MC version introduced each
        // overlay divergence. 1.21.4 first = the default/"Reset active project" target.
        versions(
            "1.21.4",
            "1.20.1", "1.20.2", "1.20.3", "1.20.4", "1.20.5", "1.20.6",
            "1.21", "1.21.1", "1.21.2", "1.21.3",
            "1.21.5", "1.21.6", "1.21.7", "1.21.8", "1.21.9", "1.21.10", "1.21.11"
            // 26.1, 26.1.1, 26.1.2, 26.2 — TEMPORARILY OUT of the matrix: MC 26.x requires
            // a Java 25 toolchain (Loom rejects it on JDK 21). Version files are ready under
            // versions/26.*; re-add all four to every list below once JDK 25 is wired up.
        )
        // Loader coverage policy:
        //   Fabric   — EVERY version (fabric-api exists for all).
        //   NeoForge — every 1.21+ version with ANY release; uses the latest STABLE build,
        //              or the latest BETA when NeoForge never promoted a stable (the beta is
        //              then the de-facto/only NeoForge for that MC version, e.g. 1.21.2,
        //              1.21.6/.7/.9, 26.1/26.1.1/26.2). Floor stays 1.21 — older NeoForge has
        //              the legacy metadata + tick-event API churn we don't support.
        //   Forge    — every MC version that has a Forge release (Forge skipped 1.20.3,
        //              1.20.5, 1.21.2 — those stay Forge-less).
        // Loader branches are filled INCREMENTALLY. The new 1.21.5–1.21.11 versions exist
        // as common-only nodes above first (so `chiseledCompileCommon` validates the shared
        // source against each MC API without dragging in per-loader fabric-api/Loom config
        // issues); each version is then added to the branches below once its loaders build.
        // Target end-state per the policy comment above.
        branch("fabric") {
            versions(
                "1.21.4", "1.20.1", "1.20.2", "1.20.3", "1.20.4", "1.20.5",
                "1.20.6", "1.21", "1.21.1", "1.21.2", "1.21.3",
                "1.21.5", "1.21.6", "1.21.7", "1.21.8", "1.21.9", "1.21.10", "1.21.11"
                // 26.x pending: Java 25.
            )
        }
        branch("neoforge") {
            versions(
                "1.21.4", "1.21", "1.21.1", "1.21.2", "1.21.3",
                "1.21.5", "1.21.6", "1.21.7", "1.21.8", "1.21.9", "1.21.10", "1.21.11"
                // 26.x pending: Java 25.
            )
        }
        branch("forge") {
            versions(
                "1.20.1", "1.20.2", "1.20.4", "1.20.6", "1.21", "1.21.1", "1.21.3", "1.21.4",
                "1.21.5", "1.21.6", "1.21.7", "1.21.8", "1.21.9", "1.21.10", "1.21.11"
                // 1.21.6+ uses the EventBus-7 ForgePlatformEvents overlay (overlays-forge/1.21.6).
                // 26.x pending: Java 25.
            )
        }
    }
}

rootProject.name = "orebit"
