// ---- Era toolchain values (Model C — internal_docs/BUILD-STRATEGY.md) ----------------
// All version-portable build LOGIC lives in these scripts on `core`; the per-era toolchain
// VALUES (Loom version + the version matrix) live in the era-owned `era.properties`, which
// is intentionally ABSENT on `core` (so `core` is not buildable). Reading it fails fast
// with a clear message when missing.
//
// `pluginManagement {}` is compiled as an isolated preamble (it runs before the main script
// body), so it cannot see the `eraProps`/`eraList` helpers declared below — it reads the
// file inline. `settingsDir` works in both places because it's a Settings API member.

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
    // Architectury Loom version is era-owned (era.properties), injected here so the
    // declaration in stonecutter.gradle.kts (and the loader modules) needs no literal.
    val loomVersion = java.util.Properties().apply {
        val f = settingsDir.resolve("era.properties")
        if (!f.exists()) error(
            "era.properties not found — this branch is not buildable.\n" +
            "  This is expected on the toolless `core` branch (Model C): it holds only\n" +
            "  version-portable logic and carries no toolchain values. Check out an era\n" +
            "  branch (e.g. `main`) to build. See internal_docs/BUILD-STRATEGY.md."
        )
        f.inputStream().use { load(it) }
    }.getProperty("loom.version") ?: error("era.properties is missing required key 'loom.version'")
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "dev.architectury.loom") useVersion(loomVersion)
        }
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.6"
}

// Matrix values (era-owned). Same fail-fast contract as above.
val eraProps = java.util.Properties().apply {
    settingsDir.resolve("era.properties").inputStream().use { load(it) }
}
fun eraList(key: String): List<String> =
    (eraProps.getProperty(key) ?: error("era.properties is missing required key '$key'"))
        .split(",").map { it.trim() }.filter { it.isNotEmpty() }

stonecutter {
    centralScript = "build.gradle.kts"
    kotlinController = true
    create(rootProject) {
        // Root `src/` is the loader-agnostic 'common' project; branches are loaders.
        // common is built for every version any loader targets. `chiseledCompileCommon`
        // compiles common against every node to pin which MC version introduced each
        // overlay divergence.
        //
        // The version VALUES are era-owned and live in era.properties (mc.versions.*),
        // sorted ascending — no version is special. The LOGIC and the loader-coverage
        // policy below are version-portable and stay on `core`.
        //
        // Loader coverage policy:
        //   Fabric   — EVERY version (fabric-api exists for all).
        //   NeoForge — every 1.21+ version with ANY release; uses the latest STABLE build,
        //              or the latest BETA when NeoForge never promoted a stable (the beta is
        //              then the de-facto/only NeoForge for that MC version, e.g. 1.21.2,
        //              1.21.6/.7/.9, 26.1/26.1.1/26.2). Floor stays 1.21 — older NeoForge has
        //              the legacy metadata + tick-event API churn we don't support.
        //   Forge    — every MC version that has a Forge release (Forge skipped 1.20.3,
        //              1.20.5, 1.21.2 — those stay Forge-less).
        //   1.21.6+ Forge uses the EventBus-7 ForgePlatformEvents overlay (overlays-forge/1.21.6).
        //
        // 26.x (Java 25) is a SEPARATE era branch — its version files are staged under
        // versions/26.* and added to that era's era.properties, not here.
        versions(*eraList("mc.versions.common").toTypedArray())
        branch("fabric") { versions(*eraList("mc.versions.fabric").toTypedArray()) }
        branch("neoforge") { versions(*eraList("mc.versions.neoforge").toTypedArray()) }
        branch("forge") { versions(*eraList("mc.versions.forge").toTypedArray()) }
    }
}

rootProject.name = "orebit"
