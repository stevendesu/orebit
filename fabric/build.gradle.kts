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
}

java {
    withSourcesJar()
    // MC < 1.20.5 runs on Java 17; 1.20.5+ on Java 21.
    val javaVersion = if (stonecutter.eval(minecraft, ">=1.20.5")) JavaVersion.VERSION_21 else JavaVersion.VERSION_17
    sourceCompatibility = javaVersion
    targetCompatibility = javaVersion
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
