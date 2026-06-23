// ---- 26.x ERA — Fabric-only, PURE Fabric Loom (NOT Architectury) ---------------------
// Architectury Loom has no MC 26.1+ support yet (architectury/architectury-loom#328:
// unobfuscated 26.x ships no mappings, which Architectury Loom still requires). Orebit's
// source is 100% Architectury-API-free — the loader seam is hand-written DI (OrebitFabric
// hands a FabricPlatformEvents to OrebitCommon.init) — so the Fabric jar builds on plain
// Fabric Loom with no Architectury at all. One module compiles common (src/) + the Fabric
// glue (fabric/src/) + the version overlays into a single jar. Forge/NeoForge for 26.x wait
// until #328 lands; the mc-1.21 era keeps Architectury + all three loaders.
//
// Model C refinement: when an era's LOADER TOOLCHAIN itself differs (Architectury vs Fabric
// Loom, multi-module vs single-module), the build SCRIPTS join the era-owned set alongside
// era.properties + the Gradle wrapper. Common SOURCE and overlays still merge cleanly from
// `core` (they are build-tool-agnostic).
//
// The Loom plugin VERSION is era-owned (era.properties / loom.version), injected below; the
// version matrix comes from era.properties (mc.versions.fabric). `core` carries neither, so
// it is not buildable (a missing era.properties fails fast with a clear message).

pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/")
        maven("https://maven.kikugie.dev/releases")
        maven("https://maven.kikugie.dev/snapshots")
    }
    // Fabric Loom version is era-owned (era.properties), injected here so the declaration in
    // stonecutter.gradle.kts needs no literal.
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
            if (requested.id.id == "net.fabricmc.fabric-loom") useVersion(loomVersion)
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
        // Fabric-only era: ONE project per version (no loader branches). The single build
        // compiles common + fabric glue + overlays into the Fabric jar. Versions are
        // era-owned (era.properties: mc.versions.fabric), sorted ascending.
        versions(*eraList("mc.versions.fabric").toTypedArray())
    }
}

rootProject.name = "orebit"
