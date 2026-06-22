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
    neoForge()
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
    get("developmentNeoForge").extendsFrom(commonBundle)
}

repositories {
    maven("https://maven.neoforged.net/releases/")
    maven("https://maven.parchmentmc.org")
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraft")
    // Parchment optional (no stable release for 1.20.5 / 1.21.2).
    val parchmentVer = common.prop("deps.parchment")
    mappings(loom.layered {
        officialMojangMappings()
        if (!parchmentVer.isNullOrBlank())
            parchment("org.parchmentmc.data:parchment-$minecraft:$parchmentVer@zip")
    })
    "neoForge"("net.neoforged:neoforge:${common.mod.dep("neoforge_loader")}")
    // Architectury API optional — loader glue uses native NeoForge events, not the API.
    val architecturyVer = common.prop("deps.architectury_api")
    if (!architecturyVer.isNullOrBlank())
        modApi("dev.architectury:architectury-neoforge:$architecturyVer")

    commonBundle(project(common.path, "namedElements")) { isTransitive = false }
    shadowBundle(project(common.path, "transformProductionNeoForge")) { isTransitive = false }
}

loom {
    runConfigs.all {
        isIdeConfigGenerated = true
        runDir = "../../../run"
    }
}

java {
    withSourcesJar()
    // Per-version JDK toolchain: NeoForge floor is 1.21 so all nodes resolve to JDK 21 (Java 21
    // bytecode); the conditional is kept uniform with the other modules. Gradle selects the
    // matching installed JDK (toolchain detection).
    toolchain {
        languageVersion = JavaLanguageVersion.of(if (stonecutter.eval(minecraft, ">=1.20.5")) 21 else 17)
    }
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
    // NeoForge floor is 1.21, which uses the modern metadata (neoforge.mods.toml +
    // type="required"). Every mod is also a resource pack, so pack.mcmeta is required.
    properties(listOf("META-INF/neoforge.mods.toml", "pack.mcmeta"),
        "id" to mod.id,
        "name" to mod.name,
        "version" to mod.version,
        "minecraft" to common.mod.prop("mc_dep_forgelike")
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
