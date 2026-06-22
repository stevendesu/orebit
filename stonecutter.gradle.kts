plugins {
    id("dev.kikugie.stonecutter")
    // Loom 1.13.469 — the version that builds the WHOLE matrix on Gradle 8.12.1:
    //  • ≥1.13.3 so it consumes fabric-api for MC 1.21.11 (remapped with Fabric Loom 1.13.3;
    //    Loom rejects artifacts built by a NEWER Loom),
    //  • new enough to provide NeoForge 1.21.10+ (which dropped the old data/server.lzma patch
    //    format that pre-1.13 Loom can't unpack), and
    //  • still has working legacy-Forge support (Loom 1.14 broke Forge remap for MC ≥1.20.6 and
    //    Forge compile for ≥1.21.6; Loom 1.17 also needs a newer Gradle than our 8.12.1).
    // The single sweet spot across Fabric + NeoForge + legacy Forge.
    id("dev.architectury.loom") version "1.13.469" apply false
    id("architectury-plugin") version "3.4-SNAPSHOT" apply false
    id("com.github.johnrengelman.shadow") version "8.1.1" apply false
}
stonecutter active "1.21.4" /* [SC] DO NOT EDIT */

// Builds every version into `build/libs/{mod.version}/{loader}`
stonecutter registerChiseled tasks.register("chiseledBuild", stonecutter.chiseled) {
    group = "project"
    ofTask("buildAndCollect")
}

// Version walk-back probe: compile ONLY the common (loader-agnostic) source for
// every version, with its selected overlay era. The common source is pure
// Minecraft (no architectury/fabric-api imports), so this needs only the MC jar +
// mappings — dodging per-version dependency gaps (missing parchment/architectury).
// A compile error pins exactly which MC version introduced a divergence.
// Run: `./gradlew chiseledCompileCommon --continue` and read the per-version errors.
stonecutter registerChiseled tasks.register("chiseledCompileCommon", stonecutter.chiseled) {
    group = "project"
    versions { branch, _ -> branch.isEmpty() }
    ofTask("compileJava")
}

// Builds loader-specific versions into `build/libs/{mod.version}/{loader}`
for (it in stonecutter.tree.branches) {
    if (it.id.isEmpty()) continue
    val loader = it.id.upperCaseFirst()
    stonecutter registerChiseled tasks.register("chiseledBuild$loader", stonecutter.chiseled) {
        group = "project"
        versions { branch, _ -> branch == it.id }
        ofTask("buildAndCollect")
    }
}

// Runs active versions for each loader (runActiveClientFabric, runActiveServerNeoforge, ...)
for (it in stonecutter.tree.nodes) {
    if (it.metadata != stonecutter.current || it.branch.id.isEmpty()) continue
    val types = listOf("Client", "Server")
    val loader = it.branch.id.upperCaseFirst()
    for (type in types) tasks.register("runActive$type$loader") {
        group = "project"
        dependsOn("${it.hierarchy}run$type")
    }
}
