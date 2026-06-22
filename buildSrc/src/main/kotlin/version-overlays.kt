import org.gradle.api.Project
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.kotlin.dsl.register
import java.io.File

// ---- Version overlays (PRD §9 portability) -------------------------------------
// Shared helper for the MC-version-overlay mechanism, used by the common module AND
// each loader module (which has its own event-API drift across MC versions).
//
// MC-version-divergent files don't live in a module's `src/main/java`; they live in a
// TOP-LEVEL `<overlaysDir>/<era>/java/<package>/…` (outside any `src/`, since Stonecutter
// copies whole `src/` trees into per-version chiseledSrc). `<era>` is the MC version that
// FIRST needs that flavor (the introducing version).
//
// Eras COMPOSE: every era whose version <= the active MC version is stacked in ascending
// order and a higher era's file OVERRIDES a lower era's file of the same path. Each era
// holds ONLY the files that changed at that boundary (no duplication). A `Sync` merges the
// applicable eras (last-wins) into one generated source dir — we can't add the era dirs as
// raw srcDirs because a class that changed at two boundaries would be a duplicate-class.

private fun mcKey(v: String): List<Int> = v.split('.', '-').mapNotNull(String::toIntOrNull)

private fun mcCmp(a: List<Int>, b: List<Int>): Int {
    for (i in 0 until maxOf(a.size, b.size)) {
        val d = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
        if (d != 0) return d
    }
    return 0
}

/**
 * Compose the version-overlay eras under [overlaysDir] (a TOP-LEVEL dir, outside `src/`)
 * for the active [minecraft] version, adding the merged result to the `main` source set.
 * No-op when [overlaysDir] is absent or has no applicable era (e.g. a loader with no drift).
 */
fun Project.applyVersionOverlays(minecraft: String, overlaysDir: File) {
    val activeKey = mcKey(minecraft)
    val eras = (overlaysDir.listFiles() ?: emptyArray())
        .filter { it.isDirectory && it.resolve("java").isDirectory && mcCmp(mcKey(it.name), activeKey) <= 0 }
        .sortedWith { a, b -> mcCmp(mcKey(a.name), mcKey(b.name)) } // ascending: later overrides earlier
    if (eras.isEmpty()) return
    val merge = tasks.register<Sync>("mergeOverlays") {
        description = "Compose version-overlay eras from ${overlaysDir.name} into one source dir."
        duplicatesStrategy = DuplicatesStrategy.INCLUDE // last `from` (highest era) wins per path
        eras.forEach { era -> from(era.resolve("java")) }
        into(layout.buildDirectory.dir("overlay-merged/java"))
    }
    extensions.getByType(SourceSetContainer::class.java)
        .getByName("main").java.srcDir(merge)
    logger.lifecycle("[orebit] MC $minecraft -> overlay eras [${eras.joinToString(", ") { it.name }}] (${overlaysDir.name})")
}
