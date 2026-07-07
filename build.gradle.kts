plugins {
    // PURE Fabric Loom (non-remapping) for unobfuscated MC 26.x — NOT Architectury Loom.
    // See settings.gradle.kts for the full rationale (architectury-loom#328).
    id("net.fabricmc.fabric-loom")
}

val minecraft = stonecutter.current.version

version = "${mod.version}+$minecraft"
base {
    archivesName.set(mod.id)
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/")
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraft")
    // MC >=26 ships UNOBFUSCATED: Mojang publishes no mappings artifact and Loom does NOT
    // remap, so there is no mappings() call and dependencies use the PLAIN configurations
    // (implementation/api, not modImplementation; jar, not remapJar). Fabric 26.1 porting guide.
    implementation("net.fabricmc:fabric-loader:${mod.dep("fabric_loader")}")
    implementation("net.fabricmc.fabric-api:fabric-api:${mod.dep("fabric_api")}")
}

loom {
    runs {
        named("client") {
            // Pin the dev-client username. Without it vanilla invents "Player###" (random per
            // launch); in offline mode the owner UUID is derived from that name, and the bot's
            // UUID (and saved inventory) is derived from the owner UUID — so every runClient
            // used to orphan the previous session's bot data. A stable name = stable identities.
            // (Era-owned run config: the mc-1.21 branch needs the same pin in its own scripts.)
            programArgs("--username", "Dev")
        }
    }
}

// Single Fabric module: fold the loader glue (fabric/src) in next to the common source (src/).
// OrebitFabric (the fabric.mod.json `main` entrypoint) wires FabricPlatformEvents into
// OrebitCommon.init — plain DI, no Architectury bundling. Version-divergent files come from
// the composed overlays (applyVersionOverlays, below).
sourceSets {
    named("main") {
        java.srcDir(rootProject.file("fabric/src/main/java"))
        resources.srcDir(rootProject.file("fabric/src/main/resources"))
    }
    // The JMH micro-benchmark + headless-MC test harness (src/test) is DEFERRED for the 26.x
    // era: it bootstraps MC via fabric-loader-junit, which is unverified on unobfuscated 26.x,
    // and profiling runs on the mc-1.21 era. Empty the test source set so `build` stays green.
    named("test") {
        java.setSrcDirs(emptyList<String>())
        resources.setSrcDirs(emptyList<String>())
    }
}

java {
    withSourcesJar()
    // 26.x runs on JDK 25 (the per-version toolchain logic kept consistent with mc-1.21).
    toolchain {
        languageVersion = JavaLanguageVersion.of(if (stonecutter.eval(minecraft, ">=26")) 25 else if (stonecutter.eval(minecraft, ">=1.20.5")) 21 else 17)
    }
}

tasks.processResources {
    properties(listOf("fabric.mod.json"),
        "id" to mod.id,
        "name" to mod.name,
        "version" to mod.version,
        "minecraft" to mod.prop("mc_dep_fabric")
    )
}

tasks.build {
    group = "versioned"
    description = "Must run through 'chiseledBuild'"
}

// Collect the (un-remapped) mod jar into build/libs/<modver>.
tasks.register<Copy>("buildAndCollect") {
    group = "versioned"
    from(tasks.named("jar"))
    into(rootProject.layout.buildDirectory.dir("libs/${mod.version}"))
    dependsOn("build")
}

// ---- Version overlays (PRD §9 portability) -------------------------------------
// MC-version-divergent files live in TOP-LEVEL `overlays/<era>/java` (outside src/), composed
// by the active MC version. 26.x is far past the 1.21.4 baseline, so expect a new overlays/26
// era for Mojang's continued rename pass (ResourceLocation->Identifier, etc.).
applyVersionOverlays(minecraft, rootProject.file("overlays"))
// Fabric loader overlays (command-api v1/v2 split): the single 26.x module compiles fabric/src too,
// so it composes the Fabric loader overlay dir like the mc-1.21 era's fabric subproject does. 26.x is
// past the 1.19 boundary, so the v2 FabricCommandRegistrar flavor wins — keeping FabricPlatformEvents
// identical across eras (no era-divergence, conflict-free merges).
applyVersionOverlays(minecraft, rootProject.file("overlays-fabric"))
