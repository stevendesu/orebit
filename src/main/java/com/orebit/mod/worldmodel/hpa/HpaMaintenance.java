package com.orebit.mod.worldmodel.hpa;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;

import com.orebit.mod.OrebitCommon;
import com.orebit.mod.config.ConfigLoader;
import com.orebit.mod.platform.BlockChangeEvents;
import com.orebit.mod.platform.LevelBounds;
import com.orebit.mod.worldmodel.pathing.NavSection;
import com.orebit.mod.worldmodel.pathing.NavSectionBuilder;
import com.orebit.mod.worldmodel.pathing.NavStore;
import com.orebit.mod.worldmodel.persistence.RegionPersistence;
import com.orebit.mod.worldmodel.resource.Log2Codec;
import com.orebit.mod.worldmodel.resource.ResourceClasses;
import com.orebit.mod.worldmodel.resource.ResourceMerger;
import com.orebit.mod.worldmodel.resource.ResourcePyramid;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Incremental maintenance of the HPA* cost pyramid — keeps the region tier live as the world changes
 * (PRD §6.3–6.5, §10 Phase 3; HPA-IMPLEMENTATION.md §12 "3f incremental maintenance").
 *
 * <h2>Role</h2>
 * The level-0 leaf face costs ({@link LeafCostComputer}) and their roll-up ({@link PyramidMerger}) are a
 * snapshot of the terrain at compute time. A block change (player/bot mining or building, pistons, fluids,
 * worldgen, TNT, fill) can flip a leaf's six face→center costs, which must then propagate up the pyramid.
 * This class is the analog of {@link com.orebit.mod.worldmodel.pathing.NavGridUpdater NavGridUpdater} for
 * the region tier: it listens on the same {@link BlockChangeEvents} seam, but instead of patching one nav
 * cell it marks the <b>containing level-0 leaf dirty</b> and re-derives that leaf (and its ancestors) on a
 * debounced, budgeted pass.
 *
 * <h2>Why debounce (the key difference from {@code NavGridUpdater})</h2>
 * Re-running a leaf's connectivity flood ({@link FragmentLeafComputer#computeLeaf}) is far heavier than
 * a single {@code patchCell}. A bulk edit (TNT, a fill command, worldgen) can fire hundreds of changes into
 * the same leaf in one tick; recomputing per block would be wasteful and could spike the frame budget. So
 * {@link #onBlockChanged} does almost nothing — it adds the leaf's per-level packed key
 * ({@link RegionAddress#packLevelKey}) to a <b>per-level dirty set</b>, which naturally <i>dedups</i>: N
 * changes in one leaf collapse to one entry. The actual recompute happens in {@link #flush}, called once
 * per level per tick from the existing {@code onWorldTickEnd} cadence (the same cadence
 * {@link com.orebit.mod.worldmodel.pathing.ChunkNavLoader ChunkNavLoader} drains its build queue on), and is
 * itself budgeted by a per-tick wall-clock time budget ({@code pathing.hpaFlushBudgetMs}) with a
 * {@link #MAX_LEAVES_PER_TICK} count backstop, so a world-wide edit amortizes over a few ticks rather than
 * stalling one.
 *
 * <h2>Flow</h2>
 * <ol>
 *   <li><b>{@link #register()}</b> (once, at init, next to {@link com.orebit.mod.worldmodel.pathing.NavGridUpdater#register()})
 *       attaches {@link #onBlockChanged} to {@link BlockChangeEvents}.</li>
 *   <li><b>{@link #onBlockChanged}</b> — server-authority only (mirrors {@code NavGridUpdater}'s
 *       {@code instanceof ServerLevel} guard); ignores no-op interned-state changes; ignores dimensions
 *       with no live {@link RegionGrid} yet (nothing has planned there, so there is no pyramid to keep —
 *       it will build fresh on first plan). Otherwise computes the change's level-0 leaf address from the
 *       world position + the dimension floor and adds it to that level's dirty set.</li>
 *   <li><b>{@link #flush}</b> — drains up to {@link #MAX_LEAVES_PER_TICK} dirty leaves: for each,
 *       {@link FragmentLeafComputer#computeLeaf} re-floods its fragment record, then
 *       {@link PyramidMerger#mergeUpFragments} re-merges its ancestors (O(levels)). Leaves whose backing chunk
 *       has since unloaded are quietly
 *       dropped (the leaf computer no-ops them; the planner falls back to the §6 default). Remaining dirty
 *       leaves stay queued for the next tick.</li>
 * </ol>
 *
 * <h2>Thread safety (HPA-IMPLEMENTATION.md §12, §14)</h2>
 * {@code setBlockState} can run off the main tick thread during worldgen, so {@link #onBlockChanged} may be
 * invoked concurrently. The per-level dirty sets are {@link ConcurrentSkipListSet}s (concurrent, deduping,
 * <b>sorted</b>) and the level→set map is a {@link ConcurrentHashMap}, so marking a leaf dirty is lock-free
 * and thread-safe. The recompute side ({@link #flush}) runs on the tick thread (as {@link LeafCostComputer}
 * requires — it drives the single-threaded block tier and a non-thread-safe section pool); the concurrent
 * set lets producers (worldgen threads) keep enqueuing while the tick thread drains.
 *
 * <p><b>Deterministic drain (why sorted, not a plain concurrent hash-keyset).</b> {@link #flush} drains at
 * most {@link #MAX_LEAVES_PER_TICK} leaves per tick; over a large, still-settling backlog the SUBSET drained
 * by any given tick — and hence the region cost pyramid the region-A* skeleton reads — depended on the
 * hash-keyset's <i>iteration order</i>, which is not stable across JVM runs (leaves are enqueued in async
 * worldgen order, which itself varies). Two identical-seed runs could therefore have merged different leaf
 * subsets at the search tick → different skeleton → behavioral fork (proven: a165be0, 341-leaf symmetric
 * difference at tick 3657). The {@link ConcurrentSkipListSet} drains via {@link ConcurrentSkipListSet#pollFirst()
 * pollFirst()} in ascending packed-key order, so the drain SEQUENCE is a deterministic function of the set's
 * <i>contents</i>, independent of enqueue order. The packed leaf key ({@link RegionAddress#packLevelKey}) is a
 * non-negative {@code long} (22b rx | 22b rz | 6b ry), so natural {@code Long} ordering is a total, stable
 * order over all leaves. The marking path is
 * COLD (one boxed {@code Long} per block change, never a per-block hot loop), matching the project's
 * existing {@code ConcurrentHashMap}-based pipeline idiom ({@code NavStore}, {@code ChunkNavLoader}); the
 * <i>hot</i> region/block searches allocate nothing (HPA-IMPLEMENTATION.md §14) and live elsewhere.
 *
 * <h2>House style (HPA-IMPLEMENTATION.md §14)</h2>
 * Static-only utility, mirroring {@link com.orebit.mod.worldmodel.pathing.NavGridUpdater}'s shape. The
 * version-divergent vertical bound goes through the {@link LevelBounds} platform seam, never inlined.
 */
public final class HpaMaintenance {

    private HpaMaintenance() {}

    /**
     * COUNT BACKSTOP on dirty-leaf recomputes per level per tick — <b>no longer the primary gate</b> (that is
     * the wall-clock {@code pathing.hpaFlushBudgetMs} time budget {@link #flush} spends, mirroring
     * {@link com.orebit.mod.worldmodel.pathing.ChunkNavLoader}'s move to a time budget + count backstop). This
     * ceiling just keeps a burst of cheap leaves from draining unbounded within the time budget; the time
     * budget is what stops a run of expensive leaves. A world-wide edit fills in over a few ticks rather than
     * stalling one. Raised from 8 to 64 with the time-budget change (it is now a safety cap, not a per-tick
     * target — a leaf rebuild is ~0.15–1.8 ms, so the 1 ms default budget usually drains the small dirty set
     * fully well under this cap).
     */
    private static final int MAX_LEAVES_PER_TICK = 64;

    /**
     * Per-dimension dirty-leaf sets. Key = {@link RegionAddress#packLevelKey} of the changed level-0 leaf
     * ({@code rx, ry, rz}); the set dedups multiple changes in the same leaf within a debounce window. The
     * outer map and each inner set are concurrent so off-thread worldgen changes can mark leaves while the
     * tick thread drains. Created on first dirty mark for a level. The inner set is a
     * {@link ConcurrentSkipListSet} rather than a hash keyset so {@link #flush} drains in a stable, sorted,
     * insertion-order-independent sequence (see the "Deterministic drain" note above).
     */
    private static final Map<ServerLevel, ConcurrentSkipListSet<Long>> DIRTY = new ConcurrentHashMap<>();

    /**
     * Per-dimension dirty <b>resource</b>-leaf sets — the SEPARATE queue for the resource-pyramid re-tally
     * (find-mine-resources design §8.5). A leaf lands here only when {@link #onBlockChanged} sees a change that
     * adds or removes an INDEXED resource block (the {@link ResourceClasses#columnForBlock} gate), so a common
     * block change (dirt/stone/air — column −1) never enters this queue and pays no sweep. Same concurrent,
     * deduping, {@link ConcurrentSkipListSet sorted} structure as {@link #DIRTY} (deterministic ascending drain),
     * drained by {@link #flush} under the <b>same</b> per-tick wall-clock budget as the cost drain.
     */
    private static final Map<ServerLevel, ConcurrentSkipListSet<Long>> RESOURCE_DIRTY = new ConcurrentHashMap<>();

    /** Reusable per-thread raw-count + log₂-encoded scratch for the resource re-tally (tick thread; sized to the
     *  column count). Mirrors the {@code ResourceMerger} thread-local scratch idiom — no per-re-tally allocation. */
    private static final ThreadLocal<int[]> RETALLY_RAW =
            ThreadLocal.withInitial(() -> new int[ResourceClasses.COLUMN_COUNT]);
    private static final ThreadLocal<byte[]> RETALLY_ENC =
            ThreadLocal.withInitial(() -> new byte[ResourceClasses.COLUMN_COUNT]);

    // ---------------------------------------------------------------------------------------------------
    // Registration
    // ---------------------------------------------------------------------------------------------------

    /**
     * Register the pyramid-maintenance listener against the block-change seam (once, at init — call next to
     * {@link com.orebit.mod.worldmodel.pathing.NavGridUpdater#register()} in
     * {@link com.orebit.mod.OrebitCommon#init}). The debounced {@link #flush} side is driven separately from
     * the existing {@code onWorldTickEnd} cadence (the same place {@code ChunkNavLoader} drains its queue).
     *
     * <p>Until the {@code setBlockState} mixin overlay is wired this listener simply never fires (registering
     * it is harmless), exactly as for {@code NavGridUpdater}.
     */
    public static void register() {
        BlockChangeEvents.register(HpaMaintenance::onBlockChanged);
    }

    // ---------------------------------------------------------------------------------------------------
    // Eager on-load build — fill the pyramid as terrain is generated/loaded (the "build on first generation"
    // hook; HPA-IMPLEMENTATION.md §12, the travel-then-path fix)
    // ---------------------------------------------------------------------------------------------------

    /**
     * When {@code true}, region leaves are built as soon as a chunk's nav data is built
     * ({@link #onChunkNavBuilt}), not just lazily when a planner first touches them. This is what makes the
     * region tier work for the <b>travel-far-then-path</b> case: the {@link CostPyramid} lives per-level and
     * is <i>not</i> dropped on chunk unload (only on level unload), so building leaves while the chunk is
     * resident accumulates the cost of everything explored — and that data survives in RAM after the chunk
     * unloads. (Surviving a server restart additionally needs disk persistence — HPA-IMPLEMENTATION.md §11,
     * still deferred.) Toggle off to fall back to pure lazy/plan-driven builds.
     */
    public static boolean EAGER_BUILD = true;

    /**
     * Build the HPA* leaves for a chunk whose nav data was just built and stored in {@link NavStore} — called
     * from the {@link com.orebit.mod.worldmodel.pathing.ChunkNavLoader ChunkNavLoader} build step, on the tick
     * thread, bounded by that loader's own per-tick chunk budget. For each resident 16³ section in the
     * column it floods the leaf's fragment record ({@link FragmentLeafComputer}) and re-merges its
     * ancestors ({@link PyramidMerger#mergeUpFragments}). Ensures the dimension's {@link RegionGrid} exists, so the
     * pyramid starts filling as the world is explored even before the first plan.
     *
     * <p>Cost: bounded by {@code ChunkNavLoader.MAX_BUILDS_PER_TICK}. Most sections are uniform air/solid and
     * resolve via {@link LeafCostComputer}'s fast-paths (no mini-pathfind); only mixed (surface/cave) sections
     * pay the six bounded searches. (Future: skip enqueuing uniform-air sections, and batch the per-column
     * ancestor merge — both pure perf, noted in HPA-IMPLEMENTATION.md §12.)
     */
    public static void onChunkNavBuilt(ServerLevel level, int chunkX, int chunkZ) {
        if (!EAGER_BUILD) return;
        final NavSection[] column = NavStore.get(level, NavStore.key(chunkX, chunkZ));
        if (column == null) return;
        final RegionGrid grid = RegionGrid.of(level);
        final CostPyramid pyramid = grid.pyramid();
        final ResourcePyramid resources = grid.resourcePyramid();
        // A chunk's leaves (re)built ⇒ this dimension's SHARD (containing chunkX/chunkZ) has unflushed region-tier
        // changes. Mark it for the next periodic/stop persistence flush (NOTES-perf-and-persistence.md §5).
        // Cold: once per chunk build.
        RegionPersistence.markDirty(level, chunkX, chunkZ);
        for (int ry = 0; ry < column.length; ry++) {
            if (column[ry] == null) continue;
            buildLeafSafe(level, pyramid, chunkX, ry, chunkZ); // note the (rx, rz, ry) order inside
            // Resource tally (sparse — only sections that actually hold ≥1 indexed block get a non-null tally;
            // the pyramid interns no row for the resource-free common case). Same (rx=chunkX, ry=sectionIndex,
            // rz=chunkZ) coord convention as the cost pyramid. A NULL tally on rebuild means the section dropped
            // to no indexed resource — if a row was previously written for it (e.g. a since-mined-out vein), it
            // must be CLEARED, not skipped (design §8.5 zero-row fix); writeResourceTallySafe handles both.
            writeResourceTallySafe(resources, chunkX, ry, chunkZ, column[ry].resourceTally());
        }
    }

    /**
     * Write a section's level-0 resource tally into the {@link ResourcePyramid} and roll it up, <b>never
     * throwing</b> onto the tick thread (mirrors {@link #buildLeafSafe}). A <b>non-null</b> {@code tally} is the
     * log₂-encoded ({@link Log2Codec}) column vector for a resource-bearing section; a <b>null</b> {@code tally}
     * means the (re)built section holds no indexed resource, so a row previously written for it must be CLEARED
     * (the design §8.5 zero-row fix) — otherwise a since-mined-out vein would keep reporting on the compass. A
     * resource-free section that never had a row stays uninterned (the sparsity contract, design §3/§5).
     */
    private static void writeResourceTallySafe(ResourcePyramid resources, int rx, int ry, int rz, byte[] tally) {
        try {
            if (tally == null) { clearResourceRow(resources, rx, ry, rz); return; }
            int row = resources.rowFor(0, rx, ry, rz);
            resources.setRow(0, row, tally);
            resources.setBuilt(0, row, true);
            ResourceMerger.mergeUpTallies(resources, rx, ry, rz);
        } catch (Throwable t) {
            long n = ++resourceFailures;
            if (n == 1 || n % 256 == 0) {
                OrebitCommon.LOGGER.error("[Orebit] resource tally write failed at region ({},{},{}) [{} total] — "
                        + "row skipped (drill-down mis-reports there until next build)", rx, ry, rz, n, t);
            }
        }
    }

    /** Count of resource-tally-write failures swallowed by {@link #writeResourceTallySafe} (log throttle). */
    private static volatile long resourceFailures = 0;

    /**
     * Apply a <b>raw</b> per-column re-tally ({@link ResourceClasses#COLUMN_COUNT} counts) to the level-0
     * resource row {@code (rx,ry,rz)} and roll it up, <b>clearing</b> the row when the section now holds no
     * indexed resource. The re-tally driver for a block change (find-mine-resources design §8.5): the log₂
     * histogram store cannot subtract one block from a bucket (a bucket is a range), so a resource-relevant
     * change re-counts the whole section ({@link NavSectionBuilder#tallyResources}) and this re-encodes +
     * writes the row. All-zero counts ⇒ {@link #clearResourceRow} (a since-mined-out section drops its row and
     * its roll-up); a section that never had a row stays uninterned. Package-visible so the §8.5 zero-row
     * behaviour is directly unit-testable (MC-free). Returns whether a row was written or cleared.
     */
    static boolean applyResourceRetally(ResourcePyramid resources, int rx, int ry, int rz, int[] rawCounts) {
        final int cols = ResourceClasses.COLUMN_COUNT;
        boolean anyNonzero = false;
        for (int c = 0; c < cols; c++) if (rawCounts[c] != 0) { anyNonzero = true; break; }
        if (!anyNonzero) return clearResourceRow(resources, rx, ry, rz);

        final byte[] enc = RETALLY_ENC.get();
        for (int c = 0; c < cols; c++) enc[c] = Log2Codec.encode(rawCounts[c]);
        int row = resources.rowFor(0, rx, ry, rz);
        resources.setRow(0, row, enc);
        resources.setBuilt(0, row, true);
        ResourceMerger.mergeUpTallies(resources, rx, ry, rz);
        return true;
    }

    /**
     * Zero a previously-populated level-0 resource row and roll the drop up (the §8.5 clear path). No-op — and
     * <b>no interning</b> — when the row was never present (sparsity: an always-empty section earns no row). The
     * existing sparse write path could never clear a row (a null/all-zero tally was simply skipped), which is
     * exactly why a mined-out vein kept reporting; this is the missing inverse. Package-visible for the test.
     * Returns whether a row was cleared.
     */
    static boolean clearResourceRow(ResourcePyramid resources, int rx, int ry, int rz) {
        final int existing = resources.rowIfPresent(0, rx, ry, rz);
        if (existing < 0) return false; // never had a row ⇒ nothing to clear
        final byte[] zero = RETALLY_ENC.get();
        Arrays.fill(zero, 0, ResourceClasses.COLUMN_COUNT, (byte) 0);
        resources.setRow(0, existing, zero);
        resources.setBuilt(0, existing, false); // now an interned-but-empty placeholder
        ResourceMerger.mergeUpTallies(resources, rx, ry, rz);
        return true;
    }

    /**
     * Re-tally ONE dirty resource leaf from live geometry + write/clear its pyramid row, <b>never throwing</b>
     * onto the tick thread (mirrors {@link #writeResourceTallySafe}). Skips a leaf whose chunk is not
     * {@link NavStore}-resident (it re-tallies from scratch on its next chunk (re)build — mirrors
     * {@code ResourceScan.exactCells}' residency gate). Runs the resource-only section sweep
     * ({@link NavSectionBuilder#tallyResources} — a small fraction of a full classify) then applies it via
     * {@link #applyResourceRetally} (which clears the row for a since-emptied section).
     */
    private static void retallyResourceLeafSafe(ServerLevel level, ResourcePyramid resources, int rx, int ry, int rz) {
        try {
            if (NavStore.get(level, NavStore.key(rx, rz)) == null) return; // not nav-resident → skip
            final int[] raw = RETALLY_RAW.get();
            NavSectionBuilder.tallyResources(sectionAt(level, rx, ry, rz), raw);
            applyResourceRetally(resources, rx, ry, rz, raw);
        } catch (Throwable t) {
            long n = ++resourceFailures;
            if (n == 1 || n % 256 == 0) {
                OrebitCommon.LOGGER.error("[Orebit] resource re-tally failed at region ({},{},{}) [{} total] — "
                        + "row left stale (drill-down mis-reports there until next chunk rebuild)", rx, ry, rz, n, t);
            }
        }
    }

    /** The live {@link LevelChunkSection} at region {@code (rx,ry,rz)} (rx/rz = chunk coords, ry = section index
     *  from the floor), or {@code null} if that section index is out of the column. Only called for a
     *  nav-resident chunk (gated in {@link #retallyResourceLeafSafe}), so {@code getChunk} returns the loaded
     *  chunk without generating — the same accessor {@code ChunkNavLoader} uses on the tick thread. */
    private static LevelChunkSection sectionAt(ServerLevel level, int rx, int ry, int rz) {
        final ChunkAccess chunk = level.getChunk(rx, rz);
        final LevelChunkSection[] sections = chunk.getSections();
        return (ry >= 0 && ry < sections.length) ? sections[ry] : null;
    }

    /**
     * Recompute one leaf's faces + re-merge its ancestors, <b>never throwing</b> onto the caller (the server
     * tick thread). A leaf build runs the block tier over live geometry; an unforeseen edge there must not
     * crash the tick — it degrades to "this leaf stays unbuilt and the planner reads the §6 optimistic
     * default". Logs the first occurrence per session at error, then throttles to a periodic count so a
     * systemic bug can't spam the log.
     */
    private static void buildLeafSafe(ServerLevel level, CostPyramid pyramid, int rx, int ry, int rz) {
        try {
            // Rebuild the leaf's RegionFragments record. Going through RegionGrid.rebuildLeaf keeps the build in
            // ONE place; it force-recomputes (ignores the built flag) — the dirty-leaf / chunk-(re)built contract.
            RegionGrid.of(level).rebuildLeaf(rx, ry, rz);
            // Ancestor roll-up keeps the coarse fragment pyramid live as leaves (re)build (HPA-FRAGMENTS.md §6.5 /
            // §S5): mergeUpFragments recomputes each ancestor's RegionFragments from its children, walking
            // parent→root, O(levels), early-out when a level's output is unchanged.
            PyramidMerger.mergeUpFragments(pyramid, rx, ry, rz);
        } catch (Throwable t) {
            long n = ++buildFailures;
            if (n == 1 || n % 256 == 0) {
                OrebitCommon.LOGGER.error("[Orebit] HPA leaf build failed at region ({},{},{}) [{} total] — "
                        + "leaf left unbuilt (optimistic default applies)", rx, ry, rz, n, t);
            }
        }
    }

    /** Count of leaf-build failures swallowed by {@link #buildLeafSafe} (diagnostics + log throttle). */
    private static volatile long buildFailures = 0;

    /**
     * R30 — the corner-refutation corrective (DESIGN-region-corner-crossing-v2.md §4.10): a corner
     * crossing {@code A → D} between diagonal leaves was durably refuted, so the coarse merges that corner
     * justified must be RE-RUN — the shared ancestor's fragment splits, and the split propagates upward
     * (above the first split level the two masses are same-slot items, which the union never joins).
     * The re-merge renumbers coarse fragment ids, so every L1+ invalidation row touching either endpoint
     * is evicted first ({@link RegionCrossingMemory#evictCoarseTouching} — L0 rows, including the gating
     * refutation itself, survive), with each eviction re-dirtying its FROM shard exactly as the
     * block-change flush does. {@code PyramidMerger.remergeSharedAncestors} walks WITHOUT the
     * unchanged-signature early-out ({@code mergeUpFrom} would stop below the fused level, since the
     * unshared ancestors between here and there really are unchanged). Tick-thread only, cold (one call
     * per durable corner refutation).
     */
    public static void onCornerRefuted(RegionGrid grid, int aRx, int aRy, int aRz,
                                       int dRx, int dRy, int dRz) {
        // A HEADLESS grid (RegionGrid.headless — level deliberately null; the cascade test seam) has no
        // persistence to mark: RegionPersistence's dirty maps NPE on a null level key, and the sink fires
        // OUTSIDE the try below. Guard every mark (review 2026-08-29, dim5 F2/dim3 F2) — the structural
        // half (evict + re-merge) still runs, which is exactly what the refutation-lifecycle tests drive.
        final ServerLevel level = grid.level();
        final CostPyramid pyramid = grid.pyramid();
        final RegionCrossingMemory mem = grid.crossingMemory();
        final RegionCrossingMemory.EvictSink evictSink = level == null ? null : (lvl, fromKey) -> {
            if (lvl >= RegionAddress.MAX_COARSE_LEVEL) {
                RegionPersistence.markCoarseDirty(level);
            } else {
                RegionPersistence.markShardDirty(level,
                        RegionAddress.shardOf(RegionAddress.unpackRX(fromKey), lvl),
                        RegionAddress.shardOf(RegionAddress.unpackRZ(fromKey), lvl));
            }
        };
        if (mem.total() > 0) {
            mem.evictCoarseTouching(aRx, aRy, aRz, evictSink);
            mem.evictCoarseTouching(dRx, dRy, dRz, evictSink);
        }
        try {
            PyramidMerger.remergeSharedAncestors(pyramid, aRx, aRy, aRz, dRx, dRy, dRz);
            // The re-merged ancestors' records changed on disk terms too: the shard files carry levels
            // 0..SHARD_LEVEL for their columns and the coarse file carries L6, so re-dirty both endpoints'
            // shards + the coarse file (over-marking is a cheap re-flush; under-marking resurrects the
            // fused records on reload).
            if (level != null) {
                RegionPersistence.markDirty(level, aRx, aRz);
                RegionPersistence.markDirty(level, dRx, dRz);
                RegionPersistence.markCoarseDirty(level);
            }
        } catch (Throwable t) {
            // Its OWN counter — inflating buildFailures would muddy the leaf-build throttle denominator
            // and misattribute the failure class (review dim5 F3).
            long n = ++cornerRemergeFailures;
            if (n == 1 || n % 256 == 0) {
                OrebitCommon.LOGGER.error("[Orebit] corner-refutation re-merge failed for ({},{},{})→({},{},{}) "
                        + "[{} corner-remerge failures total] — coarse records may stay fused until the next "
                        + "leaf rebuild", aRx, aRy, aRz, dRx, dRy, dRz, n, t);
            }
        }
    }

    /** Count of corner-refutation re-merge failures swallowed by {@link #onCornerRefuted} (distinct from
     *  {@link #buildFailures} — a different failure class with its own log throttle). */
    private static volatile long cornerRemergeFailures = 0;

    // ---------------------------------------------------------------------------------------------------
    // The block-change listener — mark dirty (debounced; thread-safe; cheap)
    // ---------------------------------------------------------------------------------------------------

    private static void onBlockChanged(Level level, BlockPos pos, BlockState oldState, BlockState newState) {
        if (!(level instanceof ServerLevel server)) return; // server authority only (mirror NavGridUpdater)
        if (oldState == newState) return;                    // interned states: reference-equal == no change

        // No pyramid for this dimension yet → nothing to keep live; it builds fresh on first plan. We must
        // NOT call RegionGrid.of() here (that would create a pyramid for a dimension nobody has planned in,
        // off the worldgen thread). Probe the cache without creating.
        RegionGrid grid = RegionGrid.peek(server);
        if (grid == null) return;

        // Level-0 leaf address of the changed block: rx/rz = world>>4 (chunk coords); ry from the floor.
        final int minY = LevelBounds.minY(server);
        final int sectionIndex = (pos.getY() - minY) >> 4;
        if (sectionIndex < 0) return;                        // below the dimension floor (out of bounds)
        final int rx = pos.getX() >> 4;
        final int rz = pos.getZ() >> 4;

        final long leafKey = RegionAddress.packLevelKey(rx, sectionIndex, rz);
        dirtyFor(server).add(leafKey);

        // Resource re-tally gate (find-mine-resources design §8.5). The resource tally only changes when the
        // block change ADDS or REMOVES an indexed resource block; columnForBlock is O(1) (a HashMap lookup) and
        // dirt/stone/air all map to column −1, so the overwhelming majority of block changes skip the resource
        // work entirely (the cost dirtying above is unaffected). A resource-relevant change marks the SEPARATE
        // resource-dirty set, drained (row re-tallied / cleared) by flush under the shared per-tick budget.
        final int oldCol = ResourceClasses.columnForBlock(oldState.getBlock());
        final int newCol = ResourceClasses.columnForBlock(newState.getBlock());
        if (oldCol >= 0 || newCol >= 0) resourceDirtyFor(server).add(leafKey);
    }

    /** The dimension's cost-leaf dirty set, created on first touch. */
    private static ConcurrentSkipListSet<Long> dirtyFor(ServerLevel level) {
        return DIRTY.computeIfAbsent(level, l -> new ConcurrentSkipListSet<>());
    }

    /** The dimension's resource-leaf dirty set, created on first touch. */
    private static ConcurrentSkipListSet<Long> resourceDirtyFor(ServerLevel level) {
        return RESOURCE_DIRTY.computeIfAbsent(level, l -> new ConcurrentSkipListSet<>());
    }

    // ---------------------------------------------------------------------------------------------------
    // The debounced flush — recompute dirty leaves + re-merge ancestors (budgeted, tick thread)
    // ---------------------------------------------------------------------------------------------------

    /**
     * Drain dirty leaves for {@code level} until the per-tick {@code pathing.hpaFlushBudgetMs} wall-clock
     * budget is spent (or the {@link #MAX_LEAVES_PER_TICK} count backstop is hit), re-flooding each leaf's
     * fragment record ({@link FragmentLeafComputer#computeLeaf}) and re-merging its ancestors
     * ({@link PyramidMerger#mergeUpFragments}). Call once per level per tick from the existing
     * {@code onWorldTickEnd} cadence (wired in {@link com.orebit.mod.OrebitCommon#init}, alongside
     * {@code ChunkNavLoader}'s drain). No-op if nothing is dirty in this dimension. Runs on the tick thread; the
     * concurrent
     * dirty set lets off-thread worldgen keep marking leaves while this drains.
     *
     * <p>The debounce is the dirty set itself: a leaf hit N times since the last flush is recomputed once.
     * Leaves still dirty after the per-tick budget remain queued for the next tick.
     *
     * @param level the dimension to flush
     */
    public static void flush(ServerLevel level) {
        final ConcurrentSkipListSet<Long> dirty = DIRTY.get(level);
        final ConcurrentSkipListSet<Long> resDirty = RESOURCE_DIRTY.get(level);
        final boolean haveCost = dirty != null && !dirty.isEmpty();
        final boolean haveRes = resDirty != null && !resDirty.isEmpty();
        if (!haveCost && !haveRes) return;

        final RegionGrid grid = RegionGrid.peek(level);
        if (grid == null) {
            // The dimension's grid was dropped (level unload) — discard both dirty backlogs.
            if (dirty != null) dirty.clear();
            if (resDirty != null) resDirty.clear();
            return;
        }
        final CostPyramid pyramid = grid.pyramid();

        // Time-budgeted drain: recompute leaves until the per-tick wall-clock budget is spent, capped by the
        // MAX_LEAVES_PER_TICK count backstop. Elapsed is checked before each leaf (so worst-case overshoot is
        // one leaf). Mirrors ChunkNavLoader's time budget + count backstop — a leaf rebuild is ~0.15–1.8 ms so
        // the 1 ms default usually drains the (small) dirty set fully, while a bulk edit amortizes over ticks.
        // The resource re-tally drain below SHARES this one budget (cost leaves first), so the two together can
        // never exceed the per-tick budget (plus at most one in-flight unit of overshoot each).
        final long budgetNanos = (long) (ConfigLoader.config().hpaFlushBudgetMs() * 1_000_000.0);
        final long start = System.nanoTime();

        int processed = 0;
        // pollFirst() atomically claims the least-keyed dirty leaf (set is concurrent — a re-mark after this
        // re-enqueues it). Ascending packed-key order makes the drain SEQUENCE a deterministic function of the
        // set's contents, independent of enqueue/iteration order (see the "Deterministic drain" class note).
        if (haveCost) {
            // #5 invalidation expiry (DESIGN-persisted-invalidation-memory.md §4): a rebuilt leaf's nav content
            // changed, so every remembered dead crossing touching it (FROM or TO, at every level whose cell
            // contains it) is no longer a proven theorem — stale NEGATIVES are the poison, re-learning is cheap.
            // Each evicted row's FROM shard is re-marked dirty so the deletion persists (rows are FROM-sharded;
            // a TO-side match may live in a NEIGHBOURING shard's file, which the chunk-based markDirty of the
            // rebuilt leaf can't name — re-flushing that FROM shard regenerates its whole invalidation section
            // from memory, so no cross-shard file scan is ever needed). Level-6 rows ride the coarse file. The
            // sink is hoisted out of the drain loop (one small allocation per flush pass — the cold block-change
            // path, and only when something is actually dirty).
            final RegionCrossingMemory mem = grid.crossingMemory();
            final RegionCrossingMemory.EvictSink evictSink = (lvl, fromKey) -> {
                if (lvl >= RegionAddress.MAX_COARSE_LEVEL) {
                    RegionPersistence.markCoarseDirty(level);
                } else {
                    RegionPersistence.markShardDirty(level,
                            RegionAddress.shardOf(RegionAddress.unpackRX(fromKey), lvl),
                            RegionAddress.shardOf(RegionAddress.unpackRZ(fromKey), lvl));
                }
            };
            Long boxed;
            while (processed < MAX_LEAVES_PER_TICK && System.nanoTime() - start < budgetNanos
                    && (boxed = dirty.pollFirst()) != null) {
                final long key = boxed;

                final int rx = RegionAddress.unpackRX(key);
                final int ry = RegionAddress.unpackRY(key);
                final int rz = RegionAddress.unpackRZ(key);

                // Recompute the leaf's six faces + re-merge ancestors (crash-safe). If its chunk/section
                // unloaded since the mark, computeLeaf no-ops (leaves the node unbuilt) — the planner then reads
                // the §6 default.
                buildLeafSafe(level, pyramid, rx, ry, rz);
                // This leaf (rx,rz = chunk coords) + its coarse ancestors changed ⇒ mark its shard dirty (§5.2).
                RegionPersistence.markDirty(level, rx, rz);
                // Expire the crossing rows this rebuild invalidates (cheap linear scan; empty-store no-op).
                if (mem.total() > 0) {
                    mem.evictLeafTouching(rx, ry, rz, evictSink);
                }

                processed++;
            }
        }

        // Resource re-tally drain (find-mine-resources design §8.5) — same deterministic ascending drain, same
        // count backstop, and the SAME shared wall-clock budget as the cost drain above (whatever time it left).
        // A resource re-tally is far cheaper than a cost-leaf rebuild (a resource-only section sweep, no
        // mini-pathfind), and resource-relevant changes are rare, so this set is usually tiny and drains fully.
        int resProcessed = 0;
        if (haveRes) {
            final ResourcePyramid resources = grid.resourcePyramid();
            Long boxed;
            while (resProcessed < MAX_LEAVES_PER_TICK && System.nanoTime() - start < budgetNanos
                    && (boxed = resDirty.pollFirst()) != null) {
                final long key = boxed;
                final int rxR = RegionAddress.unpackRX(key), rzR = RegionAddress.unpackRZ(key);
                retallyResourceLeafSafe(level, resources, rxR, RegionAddress.unpackRY(key), rzR);
                // The re-tallied leaf's shard + coarse tallies changed ⇒ mark its shard dirty (§5.2).
                RegionPersistence.markDirty(level, rxR, rzR);
                resProcessed++;
            }
        }
    }

    /**
     * Drop a dimension's dirty backlogs (cost + resource) on level unload — call alongside
     * {@link RegionGrid#drop}. Idempotent.
     */
    public static void drop(ServerLevel level) {
        DIRTY.remove(level);
        RESOURCE_DIRTY.remove(level);
    }

    /** Drop every dimension's dirty backlogs (cost + resource) on server stop — call alongside
     *  {@link RegionGrid#clear}. */
    public static void clear() {
        DIRTY.clear();
        RESOURCE_DIRTY.clear();
    }
}
