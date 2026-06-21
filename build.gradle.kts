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
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
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
