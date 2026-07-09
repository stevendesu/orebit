package com.orebit.mod.worldmodel.hpa;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.orebit.mod.OrebitCommon;
import com.orebit.mod.platform.BlockChangeEvents;
import com.orebit.mod.platform.LevelBounds;
import com.orebit.mod.worldmodel.pathing.NavSection;
import com.orebit.mod.worldmodel.pathing.NavStore;
import com.orebit.mod.worldmodel.persistence.RegionPersistence;
import com.orebit.mod.worldmodel.resource.ResourceMerger;
import com.orebit.mod.worldmodel.resource.ResourcePyramid;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

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
 * itself budgeted to at most {@link #MAX_LEAVES_PER_TICK} leaves so a world-wide edit amortizes over a few
 * ticks rather than stalling one.
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
 * invoked concurrently. The per-level dirty sets are {@link ConcurrentHashMap#newKeySet() concurrent sets}
 * and the level→set map is a {@link ConcurrentHashMap}, so marking a leaf dirty is lock-free and
 * thread-safe. The recompute side ({@link #flush}) runs on the tick thread (as {@link LeafCostComputer}
 * requires — it drives the single-threaded block tier and a non-thread-safe section pool); the concurrent
 * set lets producers (worldgen threads) keep enqueuing while the tick thread drains. The marking path is
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
     * Cap on dirty-leaf recomputes per level per tick, to keep the (heavier) leaf re-pathfind off the frame
     * budget — mirrors {@link com.orebit.mod.worldmodel.pathing.ChunkNavLoader}'s {@code MAX_BUILDS_PER_TICK}.
     * A world-wide edit fills in over a few ticks rather than stalling one.
     */
    private static final int MAX_LEAVES_PER_TICK = 8;

    /**
     * Per-dimension dirty-leaf sets. Key = {@link RegionAddress#packLevelKey} of the changed level-0 leaf
     * ({@code rx, ry, rz}); the set dedups multiple changes in the same leaf within a debounce window. The
     * outer map and each inner set are concurrent so off-thread worldgen changes can mark leaves while the
     * tick thread drains. Created on first dirty mark for a level.
     */
    private static final Map<ServerLevel, Set<Long>> DIRTY = new ConcurrentHashMap<>();

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
        // A chunk's leaves (re)built ⇒ this dimension has unflushed region-tier changes. Mark it for the next
        // periodic/stop persistence flush (DESIGN-worldmodel-persistence.md §5.2). Cold: once per chunk build.
        RegionPersistence.markDirty(level);
        for (int ry = 0; ry < column.length; ry++) {
            if (column[ry] == null) continue;
            buildLeafSafe(level, pyramid, chunkX, ry, chunkZ); // note the (rx, rz, ry) order inside
            // Resource tally (sparse — only sections that actually held ≥1 indexed block have a tally; the
            // pyramid interns no row for the null/empty common case). Same (rx=chunkX, ry=sectionIndex,
            // rz=chunkZ) coord convention as the cost pyramid.
            final byte[] tally = column[ry].resourceTally();
            if (tally != null) writeResourceTallySafe(resources, chunkX, ry, chunkZ, tally);
        }
    }

    /**
     * Write a section's level-0 resource tally into the {@link ResourcePyramid} and roll it up, <b>never
     * throwing</b> onto the tick thread (mirrors {@link #buildLeafSafe}). Only called for a non-null tally,
     * so no row is ever interned for a resource-free section (the sparsity contract, design §3/§5).
     */
    private static void writeResourceTallySafe(ResourcePyramid resources, int rx, int ry, int rz, byte[] tally) {
        try {
            int row = resources.rowFor(0, rx, ry, rz);
            resources.setRow(0, row, tally);
            resources.setBuilt(0, row, true);
            ResourceMerger.mergeUpTallies(resources, rx, ry, rz);
        } catch (Throwable t) {
            long n = ++resourceFailures;
            if (n == 1 || n % 256 == 0) {
                OrebitCommon.LOGGER.error("[Orebit] resource tally write failed at region ({},{},{}) [{} total] — "
                        + "row skipped (drill-down under-reports there until next build)", rx, ry, rz, n, t);
            }
        }
    }

    /** Count of resource-tally-write failures swallowed by {@link #writeResourceTallySafe} (log throttle). */
    private static volatile long resourceFailures = 0;

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

    // ---------------------------------------------------------------------------------------------------
    // The block-change listener — mark dirty (debounced; thread-safe; cheap)
    // ---------------------------------------------------------------------------------------------------

    private static void onBlockChanged(Level level, BlockPos pos, BlockState oldState, BlockState newState) {
        if (!(level instanceof ServerLevel server)) return; // server authority only (mirror NavGridUpdater)
        if (oldState == newState) return;                    // interned states: reference-equal == no change

        // TODO(milestone-1): resource pyramid is load-populated only; block-change re-tally deferred
        //   (see DESIGN-find-mine-resources.md §8.5). This hook keeps only the COST pyramid live; the
        //   gather loop tolerates mid-session drift via its on-arrival local section scan.

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

        dirtyFor(server).add(RegionAddress.packLevelKey(rx, sectionIndex, rz));
    }

    /** The dimension's dirty set, created on first touch. */
    private static Set<Long> dirtyFor(ServerLevel level) {
        return DIRTY.computeIfAbsent(level, l -> ConcurrentHashMap.newKeySet());
    }

    // ---------------------------------------------------------------------------------------------------
    // The debounced flush — recompute dirty leaves + re-merge ancestors (budgeted, tick thread)
    // ---------------------------------------------------------------------------------------------------

    /**
     * Drain up to {@link #MAX_LEAVES_PER_TICK} dirty leaves for {@code level}, re-flooding each leaf's fragment
     * record ({@link FragmentLeafComputer#computeLeaf}) and re-merging its ancestors
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
        final Set<Long> dirty = DIRTY.get(level);
        if (dirty == null || dirty.isEmpty()) return;

        final RegionGrid grid = RegionGrid.peek(level);
        if (grid == null) {
            // The dimension's grid was dropped (level unload) — discard its dirty backlog.
            dirty.clear();
            return;
        }
        final CostPyramid pyramid = grid.pyramid();

        int processed = 0;
        final java.util.Iterator<Long> it = dirty.iterator();
        while (processed < MAX_LEAVES_PER_TICK && it.hasNext()) {
            final long key = it.next();
            it.remove(); // claim this leaf (set is concurrent — a re-mark after this re-enqueues it)

            final int rx = RegionAddress.unpackRX(key);
            final int ry = RegionAddress.unpackRY(key);
            final int rz = RegionAddress.unpackRZ(key);

            // Recompute the leaf's six faces + re-merge ancestors (crash-safe). If its chunk/section
            // unloaded since the mark, computeLeaf no-ops (leaves the node unbuilt) — the planner then reads
            // the §6 default.
            buildLeafSafe(level, pyramid, rx, ry, rz);

            processed++;
        }
        // Block-change-driven leaf recomputes also dirty the dimension for persistence (§5.2).
        if (processed > 0) RegionPersistence.markDirty(level);
    }

    /**
     * Drop a dimension's dirty backlog (on level unload — call alongside {@link RegionGrid#drop}). Idempotent.
     */
    public static void drop(ServerLevel level) {
        DIRTY.remove(level);
    }

    /** Drop every dimension's dirty backlog (on server stop — call alongside {@link RegionGrid#clear}). */
    public static void clear() {
        DIRTY.clear();
    }
}
