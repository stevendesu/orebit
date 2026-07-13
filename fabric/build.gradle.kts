@file:Suppress("UnstableApiUsage")

plugins {
    id("dev.architectury.loom")
    id("architectury-plugin")
    id("com.github.johnrengelman.shadow")
}

val loader = prop("loom.platform")!!
val minecraft: String = stonecutter.current.version
val common: Project = requireNotNull(stonecutter.node.sibling("")?.project) {
    "No common project for $project"
}

version = "${mod.version}+$minecraft"
base {
    archivesName.set("${mod.id}-$loader")
}
architectury {
    platformSetupLoomIde()
    fabric()
}

val commonBundle: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val shadowBundle: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}
configurations {
    compileClasspath.get().extendsFrom(commonBundle)
    runtimeClasspath.get().extendsFrom(commonBundle)
    get("developmentFabric").extendsFrom(commonBundle)
}

repositories {
    maven("https://maven.parchmentmc.org")
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraft")
    // Parchment is optional — some versions (1.20.5, 1.21.2) have no stable release.
    val parchmentVer = common.prop("deps.parchment")
    mappings(loom.layered {
        officialMojangMappings()
        if (!parchmentVer.isNullOrBlank())
            parchment("org.parchmentmc.data:parchment-$minecraft:$parchmentVer@zip")
    })
    modImplementation("net.fabricmc:fabric-loader:${mod.dep("fabric_loader")}")
    // Full fabric-api bundle (umbrella mod) so it's present at runtime — Orebit's fabric.mod.json
    // depends on "fabric-api". (A 1.21.11 module-subset workaround was tried to dodge a Loom
    // source-namespace assertion on Loom 1.11, but it left the umbrella mod absent at dev-launch
    // → "fabric-api is missing". On Loom 1.13 the full bundle remaps cleanly.)
    // Exclude fabric-api's transitive fabric-loader. Old fabric-api (e.g. 0.46.x for 1.17) pins an
    // old loader (0.12.x) that Loom remaps onto the run classpath alongside our declared loader
    // (deps.fabric_loader), and Knot aborts with "duplicate fabric loader classes". We always
    // supply our own newer loader (backward-compatible with older fabric-api), so drop the
    // transitive one. Harmless on newer versions where it deduped anyway.
    modApi("net.fabricmc.fabric-api:fabric-api:${common.mod.dep("fabric_api")}") {
        exclude(group = "net.fabricmc", module = "fabric-loader")
    }
    // Architectury API is optional: the loader glue uses native Fabric events, not the
    // Architectury API (only the Architectury Loom plugin orchestrates the build). Versions
    // lacking an architectury build (e.g. transient 1.20.3) leave it blank.
    val architecturyVer = common.prop("deps.architectury_api")
    if (!architecturyVer.isNullOrBlank())
        modApi("dev.architectury:architectury-fabric:$architecturyVer")

    commonBundle(project(common.path, "namedElements")) { isTransitive = false }
    shadowBundle(project(common.path, "transformProductionFabric")) { isTransitive = false }
}

loom {
    runConfigs.all {
        isIdeConfigGenerated = true
        runDir = "../../../run"
    }
    runs {
        named("client") {
            // Pin the dev-client username. Without it vanilla invents "Player###" (random per
            // launch); in offline mode the owner UUID is derived from that name, and the bot's
            // UUID (and saved inventory) is derived from the owner UUID — so every runClient
            // used to orphan the previous session's bot data. A stable name = stable identities.
            // (Same pin as the 26 era's era-owned build.gradle.kts, s52 commit 7f9e527.)
            programArgs("--username", "Dev")
        }
    }
}

// ---- Headless autotest run config -----------------------------------------------------------------
// A dedicated-SERVER run that arms the common-src HeadlessAutotest hook (-Dorebit.autotest) in its own
// run dir (run/autotest), so the scenario's seed/world/config never collide with the interactive run/.
// Loom's server() preset appends the `nogui` program arg itself (bytecode-verified on Arch Loom
// 1.13.469) — the run opens no window. The shared runConfigs.all block above fires when this config is
// ADDED, then this create-closure runs, so the runDir set here wins over the shared "../../../run".
// Launch: ./gradlew :fabric:1.21.11:runAutotest   (after scripts/run-autotest.ps1 preps the run dir —
// server.properties pins the worldgen seed, eula.txt, config/orebit.properties pins the bot caps).
loom {
    runs {
        create("autotest") {
            server()
            configName = "Orebit Autotest ($minecraft)"
            runDir = "../../../run/autotest"
            vmArg("-Dorebit.autotest=true")
            // CLI overrides ride gradle -P properties into the JVM:
            //   ./gradlew :fabric:1.21.11:runAutotest "-Porebit.autotest.budgetTicks=48000"
            for (key in listOf("start", "goal", "budgetTicks", "startDelayTicks", "debug", "trace")) {
                val v = project.findProperty("orebit.autotest.$key")
                if (v != null) vmArg("-Dorebit.autotest.$key=$v")
            }
            // Ground drive-strategy selector rides through so the end-to-end goto autotest can A/B the ground
            // velocity servo on real terrain (Stage 2 broad verification):
            //   ./gradlew :fabric:1.21.11:runAutotest "-Porebit.ground.drive=servo"
            project.findProperty("orebit.ground.drive")?.let { vmArg("-Dorebit.ground.drive=$it") }
            isIdeConfigGenerated = false // CLI/script-driven; no IDE launch config needed
        }
        // Parkour-movement diagnostic: a superflat server that arms the common-src ParkourCourse hook
        // (-Dorebit.parkour) in its own run dir (run/parkour). Launch: ./gradlew :fabric:1.21.11:runParkour
        // (after scripts/run-parkour.ps1 preps the run dir with a FLAT server.properties + jump-isolating
        // orebit.properties). Mirrors the autotest config exactly.
        create("parkour") {
            server()
            configName = "Orebit Parkour ($minecraft)"
            runDir = "../../../run/parkour"
            vmArg("-Dorebit.parkour=true")
            for (key in listOf("debug")) {
                val v = project.findProperty("orebit.parkour.$key")
                if (v != null) vmArg("-Dorebit.parkour.$key=$v")
            }
            // Ground drive-strategy selector rides through so the parkour no-regression A/B can force it (parkour
            // bypasses drive(), so it must be identical either way): ./gradlew :fabric:1.21.11:runParkour "-Porebit.ground.drive=servo"
            project.findProperty("orebit.ground.drive")?.let { vmArg("-Dorebit.ground.drive=$it") }
            isIdeConfigGenerated = false
        }
        // Swimming-movement diagnostic: a superflat server that arms the common-src SwimCourse hook
        // (-Dorebit.swim) in its own run dir (run/swim). Launch: ./gradlew :fabric:1.21.11:runSwim
        // (after scripts/run-swim.ps1 preps the run dir with a FLAT server.properties + swim-isolating
        // orebit.properties). Mirrors the parkour config exactly.
        create("swim") {
            server()
            configName = "Orebit Swim ($minecraft)"
            runDir = "../../../run/swim"
            vmArg("-Dorebit.swim=true")
            for (key in listOf("debug", "bleed")) {
                val v = project.findProperty("orebit.swim.$key")
                if (v != null) vmArg("-Dorebit.swim.$key=$v")
            }
            // Ground drive-strategy selector rides through so the swim harness's ground-dominated Traverse
            // bank-approaches/exits can be A/B'd against the ground velocity servo (Stage 2 broad verification):
            //   ./gradlew :fabric:1.21.11:runSwim "-Porebit.ground.drive=servo"
            project.findProperty("orebit.ground.drive")?.let { vmArg("-Dorebit.ground.drive=$it") }
            isIdeConfigGenerated = false
        }
        // Ground-movement (blue-ice / lava) diagnostic: a superflat server that arms the common-src IceCourse
        // hook (-Dorebit.ice) in its own run dir (run/ice). Launch: ./gradlew :fabric:1.21.11:runIce
        // (after scripts/run-ice.ps1 preps the run dir with a FLAT server.properties + damage-on orebit.properties).
        // Mirrors the parkour/swim configs exactly.
        create("ice") {
            server()
            configName = "Orebit Ice ($minecraft)"
            runDir = "../../../run/ice"
            vmArg("-Dorebit.ice=true")
            for (key in listOf("debug")) {
                val v = project.findProperty("orebit.ice.$key")
                if (v != null) vmArg("-Dorebit.ice.$key=$v")
            }
            // Ground drive-strategy selector rides through for the ground velocity-servo A/B (Stage 2):
            //   ./gradlew :fabric:1.21.11:runIce "-Porebit.ground.drive=servo"
            project.findProperty("orebit.ground.drive")?.let { vmArg("-Dorebit.ground.drive=$it") }
            isIdeConfigGenerated = false
        }
        // Real-world REPLAY diagnostic: a server that arms the common-src WorldReplay hook (-Dorebit.replay)
        // in its own run dir (run/replay), which LOADS the owner's "Swims" world (copied in by
        // scripts/run-replay.ps1 — NOT a flat regen) and replays the reported-failing goto. Launch:
        // ./gradlew :fabric:1.21.11:runReplay (after scripts/run-replay.ps1 preps the run dir + copies the world).
        create("replay") {
            server()
            configName = "Orebit Replay ($minecraft)"
            runDir = "../../../run/replay"
            vmArg("-Dorebit.replay=true")
            for (key in listOf("debug")) {
                val v = project.findProperty("orebit.replay.$key")
                if (v != null) vmArg("-Dorebit.replay.$key=$v")
            }
            // Swim cruise-strategy selector rides through to the real-world maze replay too (the swim servo A/B):
            //   ./gradlew :fabric:1.21.11:runReplay "-Porebit.swim.bleed=servo"
            project.findProperty("orebit.swim.bleed")?.let { vmArg("-Dorebit.swim.bleed=$it") }
            isIdeConfigGenerated = false
        }
    }
}

java {
    withSourcesJar()
    // Per-version JDK toolchain: MC <1.20.5 builds AND RUNS on JDK 17; 1.20.5+ need JDK 21 (Java 21
    // bytecode). This also fixes old-loader runtime: old-Forge's modlauncher bundles an ASM that
    // can't read Java 21 class files, so its runClient must use JDK 17 — a per-version run-JDK
    // concern, NOT an era branch. Gradle selects the matching installed JDK (toolchain detection).
    toolchain {
        languageVersion = JavaLanguageVersion.of(if (stonecutter.eval(minecraft, ">=26")) 25 else if (stonecutter.eval(minecraft, ">=1.20.5")) 21 else 17)
    }
}

tasks.shadowJar {
    configurations = listOf(shadowBundle)
    archiveClassifier = "dev-shadow"
}
tasks.remapJar {
    input = tasks.shadowJar.get().archiveFile
    archiveClassifier = null
    dependsOn(tasks.shadowJar)
}
tasks.jar {
    archiveClassifier = "dev"
}
tasks.processResources {
    properties(listOf("fabric.mod.json"),
        "id" to mod.id,
        "name" to mod.name,
        "version" to mod.version,
        "minecraft" to common.mod.prop("mc_dep_fabric")
    )
}
tasks.build {
    group = "versioned"
    description = "Must run through 'chiseledBuild'"
}
tasks.register<Copy>("buildAndCollect") {
    group = "versioned"
    from(tasks.remapJar.get().archiveFile, tasks.remapSourcesJar.get().archiveFile)
    into(rootProject.layout.buildDirectory.file("libs/${mod.version}/$loader"))
    dependsOn("build")
}

// ---- Version overlays (fabric loader) ------------------------------------------
// Fabric's command API made a breaking change at MC 1.19 (fabric-command-api v1's
// (dispatcher, dedicated) -> v2's (dispatcher, registryAccess, environment), a different package),
// so the tiny FabricCommandRegistrar primitive lives in TOP-LEVEL `overlays-fabric/<era>/java`
// (NOT fabric/src), composed per MC version by the same buildSrc helper the common + forge modules
// use. Baseline era 1.17 (v1); 1.19 era overrides it (v2). FabricPlatformEvents (stable) stays in
// fabric/src and just delegates to the merged registrar. (The 26.x era builds Fabric-only from its
// own scripts and wires this inline, so it doesn't compose this dir.)
applyVersionOverlays(minecraft, rootProject.file("overlays-fabric"))
