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
    forge()
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
    get("developmentForge").extendsFrom(commonBundle)
}

repositories {
    maven("https://maven.minecraftforge.net")
    maven("https://maven.parchmentmc.org")
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraft")
    // Parchment optional (no stable release for 1.20.5 / 1.21.2 — though Forge has no build
    // for those anyway).
    val parchmentVer = common.prop("deps.parchment")
    mappings(loom.layered {
        officialMojangMappings()
        if (!parchmentVer.isNullOrBlank())
            parchment("org.parchmentmc.data:parchment-$minecraft:$parchmentVer@zip")
    })
    "forge"("net.minecraftforge:forge:$minecraft-${common.mod.dep("forge_loader")}")
    // Architectury dropped legacy-Forge support after MC 1.20.4 (architectury-forge ends at
    // 11.1.17). We don't use the API anyway (native Forge events), so only pull it where it
    // exists; 1.20.6+ Forge builds without it via the Architectury Loom plugin alone.
    val architecturyVer = common.prop("deps.architectury_api")
    if (!architecturyVer.isNullOrBlank() && stonecutter.eval(minecraft, "<=1.20.4"))
        modApi("dev.architectury:architectury-forge:$architecturyVer")

    commonBundle(project(common.path, "namedElements")) { isTransitive = false }
    shadowBundle(project(common.path, "transformProductionForge")) { isTransitive = false }
}

loom {
    runConfigs.all {
        isIdeConfigGenerated = true
        runDir = "../../../run"
    }
}

java {
    withSourcesJar()
    // Per-version JDK toolchain: MC <1.20.5 builds AND RUNS on JDK 17; 1.20.5+ need JDK 21 (Java 21
    // bytecode). Old-Forge's modlauncher bundles an ASM (e.g. 9.1 on 1.17.1) that can't read Java
    // 21 class files, so its runClient must use JDK 17 — a per-version run-JDK concern, NOT an era
    // branch. Gradle selects the matching installed JDK (toolchain detection).
    toolchain {
        languageVersion = JavaLanguageVersion.of(if (stonecutter.eval(minecraft, ">=1.20.5")) 21 else 17)
    }
}

// Loom honors the java{} toolchain for COMPILE but launches runClient/runServer on the Gradle
// daemon's JDK, so old-Forge runs would still hit ASM "Unsupported class file major version 65"
// on JDK 21 (its modlauncher ASM can't read Java 21 classes). Loom's run task extends JavaExec,
// so pin the run JVM to the same per-version toolchain.
tasks.withType<JavaExec>().configureEach {
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion = JavaLanguageVersion.of(if (stonecutter.eval(minecraft, ">=1.20.5")) 21 else 17)
    })
}

tasks.jar {
    archiveClassifier = "dev"
}
tasks.shadowJar {
    configurations = listOf(shadowBundle)
    archiveClassifier = "dev-shadow"
    exclude("fabric.mod.json", "architectury.common.json")
}
tasks.remapJar {
    input = tasks.shadowJar.get().archiveFile
    archiveClassifier = null
    dependsOn(tasks.shadowJar)
}
tasks.processResources {
    properties(listOf("META-INF/mods.toml", "pack.mcmeta"),
        "id" to mod.id,
        "name" to mod.name,
        "version" to mod.version,
        "minecraft" to common.mod.prop("mc_dep_forgelike"),
        // FML's javafml language-loader version == the Forge MAJOR (37 on 1.17.1, 47 on 1.20.1, …);
        // mods.toml's loaderVersion + the forge dependency range must match this version, not a
        // hardcoded [47,). Derive it from deps.forge_loader.
        "loader_major" to common.mod.dep("forge_loader").substringBefore(".")
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

// ---- Version overlays (forge loader) -------------------------------------------
// Forge's event API is MC-version-divergent (the EventBus 7 migration at 1.21.6), so
// ForgePlatformEvents lives in TOP-LEVEL `overlays-forge/<era>/java` (NOT forge/src), composed
// per MC version by the same buildSrc helper the common module uses. Baseline era 1.20.1
// (classic MinecraftForge.EVENT_BUS); 1.21.6 era overrides it (per-event Event.BUS.addListener).
applyVersionOverlays(minecraft, rootProject.file("overlays-forge"))
