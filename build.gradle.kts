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
    mappings(loom.layered {
        officialMojangMappings()
        parchment("org.parchmentmc.data:parchment-$minecraft:${mod.dep("parchment")}@zip")
    })
    modImplementation("net.fabricmc:fabric-loader:${mod.dep("fabric_loader")}")
    modImplementation("dev.architectury:architectury:${mod.dep("architectury_api")}")

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
    // MC < 1.20.5 runs on Java 17; 1.20.5+ on Java 21.
    val javaVersion = if (stonecutter.eval(minecraft, ">=1.20.5")) JavaVersion.VERSION_21 else JavaVersion.VERSION_17
    sourceCompatibility = javaVersion
    targetCompatibility = javaVersion
}

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
    testLogging { showStandardStreams = true }
    outputs.upToDateWhen { false }
}

tasks.build {
    group = "versioned"
    description = "Must run through 'chiseledBuild'"
}

// ---- Version overlays (PRD §9 portability) -------------------------------------
// Files that can't compile across every supported MC version don't live in the
// common `src/main/java`; they live in `overlays/<era>/java`, where <era> is the
// FIRST MC version that flavor applies to (forward-looking — you add a new era dir
// only when a newer version breaks something, never rename old ones).
//
// NB: overlays live in a TOP-LEVEL `overlays/` dir, OUTSIDE `src/`. Stonecutter
// copies the whole `src/` tree into its per-version chiseledSrc, so an overlay under
// `src/` would get every era swept in at once (duplicate class).
//
// For a given build we add the single highest era dir whose version is <= the active
// MC version; its files supersede older eras. Each era dir holds the complete set of
// version-divergent files for that era, so there's never a duplicate class with core.
run {
    fun mcKey(v: String): List<Int> = v.split('.', '-').mapNotNull(String::toIntOrNull)
    fun cmp(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val d = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (d != 0) return d
        }
        return 0
    }
    val overlaysDir = rootProject.file("overlays")
    val activeKey = mcKey(minecraft)
    val era = overlaysDir.listFiles()
        ?.filter { it.isDirectory && it.resolve("java").isDirectory && cmp(mcKey(it.name), activeKey) <= 0 }
        ?.maxWithOrNull { a, b -> cmp(mcKey(a.name), mcKey(b.name)) }
    if (era != null) {
        sourceSets["main"].java.srcDir(era.resolve("java"))
        logger.lifecycle("[orebit] MC $minecraft -> overlay era '${era.name}'")
    }
}
