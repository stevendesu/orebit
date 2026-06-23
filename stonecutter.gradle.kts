plugins {
    id("dev.kikugie.stonecutter")
    // 26.x era: PURE Fabric Loom (net.fabricmc.fabric-loom), the non-remapping plugin for
    // unobfuscated MC 26.x. NOT Architectury Loom (no 26.x support — see settings.gradle.kts
    // and architectury/architectury-loom#328). The version is era-owned (era.properties /
    // loom.version), injected by settings.gradle.kts, so no literal here.
    id("net.fabricmc.fabric-loom") apply false
}
stonecutter active "26.2" /* [SC] DO NOT EDIT */

// Builds every Fabric version into `build/libs/{mod.version}`
stonecutter registerChiseled tasks.register("chiseledBuild", stonecutter.chiseled) {
    group = "project"
    ofTask("buildAndCollect")
}

// Cheap probe: compile the mod against every version (pins which MC version introduced an
// API divergence so it can be addressed with an overlay). Run: `./gradlew chiseledCompile --continue`.
stonecutter registerChiseled tasks.register("chiseledCompile", stonecutter.chiseled) {
    group = "project"
    ofTask("compileJava")
}

// Convenience run tasks for the active version (runActiveClient / runActiveServer).
for (it in stonecutter.tree.nodes) {
    if (it.metadata != stonecutter.current) continue
    for (type in listOf("Client", "Server")) tasks.register("runActive$type") {
        group = "project"
        dependsOn("${it.hierarchy}run$type")
    }
}
