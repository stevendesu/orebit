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
        //
        // The intermediate versions (1.20.2 .. 1.21.3) are present as COMMON nodes
        // for the version walk-back (pin which MC version introduced each overlay
        // divergence) via `chiseledCompileCommon`. They have no loader branch yet —
        // the real shippable loader targets are Fabric/NeoForge 1.21.4 + Forge 1.20.1.
        // 1.21.4 first = the default/"Reset active project" target (primary dev version).
        versions(
            "1.21.4",
            "1.20.1", "1.20.2", "1.20.3", "1.20.4", "1.20.5", "1.20.6",
            "1.21", "1.21.1", "1.21.2", "1.21.3"
        )
        // Fabric: every version. NeoForge: 1.20.2+ (its earliest; betas for the transient
        // 1.20.3/1.20.5/1.21.2). Forge: every version that has a Forge release (Forge
        // skipped 1.20.3/1.20.5/1.21.2). Goal: any version+loader pair is runnable.
        branch("fabric") {
            versions(
                "1.21.4", "1.20.1", "1.20.2", "1.20.3", "1.20.4", "1.20.5",
                "1.20.6", "1.21", "1.21.1", "1.21.2", "1.21.3"
            )
        }
        // NeoForge floor = 1.21 (its real adoption range). Below that, NeoForge's old
        // metadata format + tick-event API add complexity for versions almost nobody runs.
        // 1.21.2 is a transient (Fabric-only) release, so it's excluded here too.
        branch("neoforge") { versions("1.21.4", "1.21", "1.21.1", "1.21.3") }
        branch("forge") {
            // Every MC version that has a Forge release (Forge skipped 1.20.3/1.20.5/1.21.2).
            versions("1.20.1", "1.20.2", "1.20.4", "1.20.6", "1.21", "1.21.1", "1.21.3", "1.21.4")
        }
    }
}

rootProject.name = "orebit"
