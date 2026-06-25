plugins {
    id("dev.architectury.loom")
    id("architectury-plugin")
}

val minecraft = stonecutter.current.version

version = "${mod.version}+$minecraft"
base {
    archivesName.set("${mod.id}-common")
}

architectury.common(stonecutter.tree.branches.mapNotNull {
    if (stonecutter.current.project !in it) null
    else it.project.prop("loom.platform")
})

repositories {
    maven("https://maven.parchmentmc.org")
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraft")
    // Official Mojang mappings + Parchment param names (shared by all loaders).
    // Parchment is OPTIONAL: some MC versions (e.g. 1.20.5, 1.21.2) never got a
    // stable Parchment release. When `deps.parchment` is blank we fall back to
    // official Mojang mappings only (param names are uglier; compile is unaffected).
    val parchmentVer = prop("deps.parchment")
    mappings(loom.layered {
        officialMojangMappings()
        if (!parchmentVer.isNullOrBlank())
            parchment("org.parchmentmc.data:parchment-$minecraft:$parchmentVer@zip")
    })
    modImplementation("net.fabricmc:fabric-loader:${mod.dep("fabric_loader")}")
    // Architectury is OPTIONAL too: the common source imports ZERO architectury
    // classes (the only loader seam, PlatformEvents, is pure-MC), so the
    // common node compiles without it. Versions lacking a stable architectury
    // build (e.g. 1.20.3) leave `deps.architectury_api` blank — used for the
    // version walk-back, which only needs common to compile, not a loader jar.
    val architecturyVer = prop("deps.architectury_api")
    if (!architecturyVer.isNullOrBlank())
        modImplementation("dev.architectury:architectury:$architecturyVer")

    // ---- JMH micro-benchmark + headless-MC test harness ----
    // Loader-agnostic (benchmarks NavSectionBuilder / block reads). Bootstraps MC
    // headlessly via fabric-loader-junit. Run with `./gradlew :<commonNode>:jmh`.
    testImplementation("org.openjdk.jmh:jmh-core:1.37")
    testAnnotationProcessor("org.openjdk.jmh:jmh-generator-annprocess:1.37")
    testImplementation("org.openjdk.jmh:jmh-generator-annprocess:1.37")
    testImplementation("net.fabricmc:fabric-loader-junit:${mod.dep("fabric_loader")}")
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

java {
    withSourcesJar()
    // Per-version JDK toolchain: MC <1.20.5 builds AND RUNS on JDK 17; 1.20.5+ need JDK 21 (Java 21
    // bytecode); 26+ need JDK 25 (the 26.x era, on its own era branch). This also fixes old-loader
    // runtime: old-Forge's modlauncher bundles an ASM that can't read Java 21 class files, so its
    // runClient must use JDK 17 — a per-version run-JDK concern, NOT an era branch. Gradle selects
    // the matching installed JDK (toolchain detection).
    toolchain {
        languageVersion = JavaLanguageVersion.of(if (stonecutter.eval(minecraft, ">=26")) 25 else if (stonecutter.eval(minecraft, ">=1.20.5")) 21 else 17)
    }
}

// The block-change hook is the project's one mixin; it lives in the common module (composed from
// overlays/<era>/…/mixin/) so all loaders share it. NOTE: Architectury Loom 1.13 remaps mixins
// REFMAP-LESS — the Architectury transformer rewrites the @Inject target descriptors to each loader's
// runtime names in-place at the transformProduction<Loader> step. So there is deliberately NO
// `loom { mixin { … } }` block and NO "refmap" field in orebit.mixins.json: the legacy mixin AP is
// off by default in this Loom, and a stray refmap reference only triggers "could not be read" /
// "please remove it" warnings at dev launch. (The 26.x era runs UNOBFUSCATED, so it needs no remap
// at all.) If a future Loom re-requires the AP, set `loom.mixin.useLegacyMixinAp = true` and restore
// a defaultRefmapName + the JSON "refmap" field together.

tasks.named<Test>("test") {
    useJUnitPlatform()
}

// JMH benchmarks inside Fabric's Knot classloader (forks=0). Gated by -Djmh=true so
// a normal `test` skips them. Invoke via `./gradlew :<commonNode>:jmh [-Pbench=...]`.
tasks.register<Test>("jmh") {
    group = "verification"
    description = "Run JMH benchmarks (inside the Knot classloader, forks=0)."
    useJUnitPlatform()
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter { includeTestsMatching("profile.BenchmarkRunnerTest") }
    systemProperty("jmh", "true")
    if (project.hasProperty("bench")) systemProperty("bench", project.property("bench")!!)
    if (project.hasProperty("prof")) systemProperty("prof", project.property("prof")!!)
    if (project.hasProperty("scenario")) systemProperty("scenario", project.property("scenario")!!)
    testLogging { showStandardStreams = true }
    outputs.upToDateWhen { false }
}

tasks.build {
    group = "versioned"
    description = "Must run through 'chiseledBuild'"
}

// ---- Version overlays (PRD §9 portability) -------------------------------------
// MC-version-divergent files live in the TOP-LEVEL `overlays/<era>/java` (outside src/),
// composed by the active MC version. The full design lives in the shared buildSrc helper
// `applyVersionOverlays` (buildSrc/.../version-overlays.kt); each loader module composes
// its own overlay dir the same way.
applyVersionOverlays(minecraft, rootProject.file("overlays"))
