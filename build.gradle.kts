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

// Pre-clean for the bench tasks below: kill a LINGERING test-worker JVM left by a PREVIOUS run.
// Both bench harnesses bootstrap Minecraft inside the Knot classloader in the test worker; the
// bootstrapped MC spawns non-daemon threads (background executors/timers) that can keep the worker
// JVM alive after a SUCCESSFUL run. The orphan holds open handles under
// build/test-results/<task>/binary (output.bin), so the NEXT run's result-dir clean fails with
// "Unable to delete". Identification is deliberately narrow, so unrelated JVMs are never touched:
// a java process whose command line has BOTH the Gradle test-worker main class
// (worker.org.gradle.process.internal.worker.GradleWorkerMain) AND this task's gate system
// property (-Djmh=true / -Dcoldstart=true) — Gradle puts Test-task systemProperty values on the
// worker's command line. At doFirst time the current run's worker does not exist yet, so any match
// is an orphan. Windows-only (the file-lock symptom is Windows-specific); a silent no-op when
// nothing lingers.
fun killLingeringBenchWorker(gateProp: String) {
    if (!System.getProperty("os.name").orEmpty().lowercase().contains("win")) return
    val script =
        "Get-CimInstance Win32_Process -Filter 'Name=''java.exe''' | " +
        "Where-Object { \$_.CommandLine -like '*worker.org.gradle.process.internal.worker.GradleWorkerMain*' " +
        "-and \$_.CommandLine -like '*-D$gateProp=true*' } | " +
        "ForEach-Object { Write-Output ('[orebit] pre-clean: killing lingering bench worker PID ' + \$_.ProcessId); " +
        "Stop-Process -Id \$_.ProcessId -Force; " +
        "Wait-Process -Id \$_.ProcessId -Timeout 10 -ErrorAction SilentlyContinue }"
    try {
        val proc = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script)
                .redirectErrorStream(true).start()
        proc.inputStream.bufferedReader().forEachLine { line -> if (line.isNotBlank()) println(line) }
        proc.waitFor()
    } catch (e: Exception) {
        logger.warn("[orebit] lingering-worker pre-clean failed (continuing): ${e.message}")
    }
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
    if (project.hasProperty("arm")) systemProperty("arm", project.property("arm")!!)
    testLogging { showStandardStreams = true }
    outputs.upToDateWhen { false }
    doFirst { killLingeringBenchWorker("jmh") }
}

// E5 cold-start harness: times the JIT-COLD first pathfinder search in a FRESH test-worker JVM —
// the number JMH's warmed forks=0 JVM cannot see. ONE measured run per Gradle invocation; forkEvery 1
// + never-up-to-date guarantee a new worker each time (workers don't persist across builds anyway).
// Arm: -Pwarmarm=true|false → -Dorebit.bench.warmup (run NavWarmup before the timed search, or not).
// Gated by -Dcoldstart=true so a normal `test` skips it.
tasks.register<Test>("coldstart") {
    group = "verification"
    description = "Fresh-JVM cold-start pathfinder timing (E5 warm-up experiment)."
    useJUnitPlatform()
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    filter { includeTestsMatching("com.orebit.mod.worldmodel.pathing.ColdStartHarnessTest") }
    systemProperty("coldstart", "true")
    if (project.hasProperty("warmarm")) systemProperty("orebit.bench.warmup", project.property("warmarm")!!)
    setForkEvery(1)
    maxParallelForks = 1
    testLogging { showStandardStreams = true }
    outputs.upToDateWhen { false }
    // Same MC-in-the-worker linger vulnerability as `jmh` (forkEvery 1 makes the worker EXIT-eligible
    // after its one test class, but non-daemon MC threads can still pin the process).
    doFirst { killLingeringBenchWorker("coldstart") }
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
