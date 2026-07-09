package com.orebit.mod.worldmodel.pathing;

import java.util.concurrent.ConcurrentHashMap;

import com.orebit.mod.platform.BlockChangeEvents;
import com.orebit.mod.platform.LevelBounds;
import com.orebit.mod.worldmodel.navblock.NavBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Keeps the nav grid live as the world changes — the block-update hook that retires the follower's
 * per-replan {@code refreshNavData} rebuild. Registers a {@link BlockChangeEvents.Listener}; for a
 * server-side change inside a built section it records the cell's new navtype in the level's
 * {@link PendingPatches} queue (defer + last-state-wins dedup —
 * PERF-DESIGN-navgrid-edit-batching.md §4.2) and the whole set is drained through
 * {@link NavSectionBuilder#patchCells} at the next {@link #flush} barrier. Changes in chunks we don't
 * track yet are ignored — they build fresh on load.
 *
 * <p><b>Flush barriers (§4.4 — read-your-writes for every server-thread reader):</b> block changes
 * originate in MULTIPLE phases of {@code ServerLevel.tick} (scheduled/random ticks before entities,
 * TNT/mob griefing DURING entity ticking interleaved with bot ticks, piston block-events elsewhere),
 * so no fixed drain point can both precede and follow all same-tick producers. Instead every
 * server-thread read path drains on entry: the live {@code NavGridView(ServerLevel)} ctor (every sync
 * block search), the start of {@code AllyBotEntity}'s tick (region-tier reads that bypass
 * {@code NavGridView} — the lazy leaf-cost mini-pathfinds), and a world-tick-end catch-all registered
 * BEFORE {@code HpaMaintenance::flush} (its leaf recomputes read patched grids; also guarantees the
 * queue is empty across tick boundaries — invariant §4.6-2). A clean flush costs one static int test.
 * Async planner workers get no weaker guarantee than before: the drain issues the identical
 * {@code grid.set} writes on the same (server) thread, just later within the tick.
 *
 * <p>The trigger is the {@code setBlockState} mixin firing {@link BlockChangeEvents#fire}; until that
 * overlay is wired this listener simply never runs (registering it is harmless).
 */
public final class NavGridUpdater {
    private NavGridUpdater() {}

    /**
     * Per-level count of TRACKED-grid block edits (bumped once per grid-visible enqueued change). This
     * is the cheap "did the world change at all?" signal the follower's terrain-recheck debounce gates
     * on: an unchanged epoch means no built nav cell changed since the plan's last window search, so
     * the periodic re-search would be byte-identical and is skipped entirely (a stationary bot in a
     * quiet world never re-searches). The bump happens at ENQUEUE time (§4.5's recommended variant),
     * keeping today's semantics verbatim — "the world may have changed", immediately; the one consumer
     * reads it inside the bot's tick, which sits BEHIND the bot-tick-start flush barrier, so an
     * advanced epoch is never observed while its change still sits queued. Tick-thread confined (the
     * mixin fires on the server thread; the driver reads on the server thread) — no synchronization.
     * Known coarseness, documented: the epoch is level-global (an edit anywhere re-arms every bot's
     * recheck — one wasted-but-correct search), and it includes the bot's OWN plan edits (excluding
     * those needs per-edit attribution; a plan's own assumed edits are already modelled by PathEdits,
     * so those re-searches return equivalent routes).
     */
    private static final java.util.WeakHashMap<ServerLevel, int[]> EDIT_EPOCH = new java.util.WeakHashMap<>();

    /**
     * The per-level deferred-patch queues (§4.2) — the same {@code WeakHashMap} idiom and tick-thread
     * confinement as {@link #EDIT_EPOCH} (off-thread worldgen fires hit the untracked-chunk early-out
     * before ever touching a queue).
     */
    private static final java.util.WeakHashMap<ServerLevel, PendingPatches> PENDING = new java.util.WeakHashMap<>();

    /**
     * Total dirty cells across every level — the one-test clean gate {@link #flush} pays when nothing
     * is pending (the common case for every barrier crossing). Exact: incremented per first insert of
     * a key, decremented by the drained count. Server-thread confined like everything else here.
     */
    private static int pendingGlobal;

    /** The current edit epoch for {@code level} (0 until its first tracked edit). Server thread only. */
    public static int editEpoch(ServerLevel level) {
        final int[] c = EDIT_EPOCH.get(level);
        return c == null ? 0 : c[0];
    }

    /**
     * Advance the epoch for a NON-block-change grid mutation — chunk nav sections built or dropped
     * ({@code ChunkNavLoader}). A newly BUILT area is exactly as plan-relevant as an edited one: without
     * this, a bot whose first search ran before its chunks built (seconds after world open) had no signal
     * to re-search until some block changed (the s52b cold-open false START-DEAD). Server thread only.
     */
    public static void bumpEpoch(ServerLevel level) {
        EDIT_EPOCH.computeIfAbsent(level, l -> new int[1])[0]++;
    }

    /** Register the nav-grid patcher against the block-change seam (once, at init). */
    public static void register() {
        BlockChangeEvents.register(NavGridUpdater::onBlockChanged);
    }

    private static void onBlockChanged(Level level, BlockPos pos, BlockState oldState, BlockState newState) {
        if (!(level instanceof ServerLevel server)) return; // server authority only
        if (oldState == newState) return;                    // interned states: reference-equal == no change

        int sectionIndex = (pos.getY() - LevelBounds.minY(server)) >> 4;
        if (sectionIndex < 0) return;
        NavSection[] sections = NavStore.get(server, NavStore.key(pos.getX() >> 4, pos.getZ() >> 4));
        if (sections == null || sectionIndex >= sections.length) return; // chunk not tracked
        NavSection section = sections[sectionIndex];
        if (section == null) return;

        final int lx = pos.getX() & 15, ly = pos.getY() & 15, lz = pos.getZ() & 15;
        final short newNavtype = NavBlock.navtypeFor(newState); // interned ONCE here, stored in the queue

        // EFFECTIVE-NAVTYPE NO-OP EARLY-OUT (the Phase-0 filter generalized over the queue, §4.2 step 2):
        // a change whose navtype equals what the grid WILL hold once drained — the pending value if the
        // cell is dirty, else the resident navtype — changes nothing any plan can see: this cell's
        // descriptor is identical, so every neighbour's flag/depth window (which reads only navtypes) is
        // identical too. An extend-then-retract piston pair in one tick collapses to a pending value equal
        // to the resident one, which the drain skips outright. Skipping the patch is pure saved work;
        // skipping the epoch bump is what keeps the follower's terrain-recheck debounce MEANINGFUL —
        // without it a single redstone clock anywhere in the level re-arms every bot's periodic re-search
        // forever (PERF-DESIGN-navgrid-edit-batching.md phase 0).
        final PendingPatches queue = PENDING.computeIfAbsent(server, l -> new PendingPatches());
        if (!enqueueIfChanges(queue, section, lx, ly, lz, pos.asLong(), newNavtype)) {
            return;
        }

        // A grid-visible change was queued — the world visibly changed for every plan over this level.
        // Enqueue-time bump (§4.5): the epoch may only ever run AHEAD of the drained grid, never behind
        // it, so a debounce read behind any barrier can never observe queued-but-unbumped state.
        EDIT_EPOCH.computeIfAbsent(server, l -> new int[1])[0]++;

        // Nether-portal index maintenance (NetherPortalIndex incremental feed), from the EVENT params:
        // under deferral the resident grid can be stale-by-one-pending-write, but the event's old/new
        // states are order-exact (§4.6-6), and the navtype is a pure function of the state — identical to
        // the old grid read while the grid was patched inline. Two descriptor bit-tests per block change —
        // the index mutates only when a portal actually toggles (vanishingly rare), and this path is
        // per-block-change, never per-A*-node.
        boolean wasPortal = NavBlock.isPortal(NavBlock.descriptorFor(oldState));
        boolean nowPortal = NavBlock.isPortal(NavBlock.descriptor(newNavtype));
        if (wasPortal != nowPortal) {
            if (nowPortal) NetherPortalIndex.add(server, pos.getX(), pos.getY(), pos.getZ());
            else NetherPortalIndex.removeCell(server, pos.getX(), pos.getY(), pos.getZ());
        }
    }

    /**
     * The enqueue decision + insert (package-private: the headless identity/epoch tests drive this seam
     * directly — {@code onBlockChanged} itself needs a live {@code ServerLevel}, which cannot be stood
     * up under the Knot test classloader). Returns {@code false} when the change is invisible to the
     * nav grid — {@code newNavtype} equals the cell's EFFECTIVE navtype (pending value if dirty, else
     * resident) — in which case nothing is queued and the caller must not bump the epoch; {@code true}
     * means the change was queued and the epoch must bump. The {@code true}-iff-queue-changed coupling
     * is what invariant §4.6-5 (epoch never under-reports) rests on.
     */
    static boolean enqueueIfChanges(PendingPatches queue, NavSection section, int lx, int ly, int lz,
                                    long posKey, short newNavtype) {
        final int pending = queue.get(posKey);
        if (pending >= 0 ? newNavtype == (short) pending
                         : !changesGrid(section, lx, ly, lz, newNavtype)) {
            return false;
        }
        if (queue.put(posKey, newNavtype)) pendingGlobal++;
        return true;
    }

    /**
     * Flush barrier (§4.4): drain {@code level}'s pending queue so the caller's subsequent nav-grid
     * reads observe every block change fired before this point (read-your-writes, invariant §4.6-1 —
     * equivalent to the old inline patch). A no-op costing one static int test when nothing is pending
     * anywhere (the common case), plus one map lookup + count test when another level owns the pending
     * cells. Server thread only — the same confinement as the queue itself.
     */
    public static void flush(ServerLevel level) {
        if (pendingGlobal == 0) return;
        final PendingPatches queue = PENDING.get(level);
        final int n = queue == null ? 0 : queue.count();
        if (n == 0) return;
        pendingGlobal -= n;
        drain(queue, LevelBounds.minY(level), NavStore.chunksOf(level));
    }

    // Drain sort-key layout (one long per pending cell, grouped by section when sorted):
    // [chunkX+BIAS:22][chunkZ+BIAS:22][sectionIndex:8][packedCell:12]. Only ADJACENCY of equal
    // (chunk, section) prefixes and ascending section order within a chunk matter — the signed sort's
    // cross-chunk order is irrelevant — and the cell's world position is fully reconstructible from the
    // key, so the pending navtype is re-fetched with one queue probe per cell.
    private static final int CHUNK_BIAS = 1 << 21;

    /**
     * The drain (§4.3's outer loop; package-private, headless-testable — the level-free core
     * {@link #flush} delegates to, parameterized exactly like {@code NavGridView}'s synthetic seam):
     * sort the pending cells so same-section cells are adjacent (sections ascending within a chunk, so
     * a below-seam batch always runs after the section under it was itself patched-or-skipped), resolve
     * each section group FRESH from the store — a chunk unloaded since enqueue drops its entries, never
     * a stale {@code NavSection} ref (§4.6-4) — and hand each group to
     * {@link NavSectionBuilder#patchCells} (the Phase-2 phased per-section patch). Ends with
     * {@link PendingPatches#clear}, so the queue is empty behind every barrier. Allocation-free: the
     * sort/group buffers are the queue's own reusable scratch.
     */
    static void drain(PendingPatches queue, int minY, ConcurrentHashMap<Long, NavSection[]> chunks) {
        final int n = queue.count();
        final long[] sorted = queue.sortScratch(n);
        for (int i = 0; i < n; i++) {
            final long posKey = queue.keyAt(i);
            final int x = BlockPos.getX(posKey), y = BlockPos.getY(posKey), z = BlockPos.getZ(posKey);
            sorted[i] = ((long) ((x >> 4) + CHUNK_BIAS) << 42)
                    | ((long) ((z >> 4) + CHUNK_BIAS) << 20)
                    | ((long) ((y - minY) >> 4) << 12)
                    | ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
        }
        java.util.Arrays.sort(sorted, 0, n);

        final short[] cells = queue.cellScratch(n);
        final short[] navs = queue.navScratch(n);
        int i = 0;
        while (i < n) {
            final long groupPrefix = sorted[i] >>> 12;
            final int chunkX = ((int) (sorted[i] >>> 42) & 0x3FFFFF) - CHUNK_BIAS;
            final int chunkZ = ((int) (sorted[i] >>> 20) & 0x3FFFFF) - CHUNK_BIAS;
            final int sectionIndex = (int) (sorted[i] >>> 12) & 0xFF;
            int m = 0;
            do {
                final int cell = (int) sorted[i] & 0xFFF;
                // Reconstruct the world position to re-fetch this cell's pending navtype.
                final int cx = (chunkX << 4) | (cell & 15);
                final int cy = minY + (sectionIndex << 4) + (cell >>> 8);
                final int cz = (chunkZ << 4) | ((cell >>> 4) & 15);
                cells[m] = (short) cell;
                navs[m] = (short) queue.get(BlockPos.asLong(cx, cy, cz));
                m++;
                i++;
            } while (i < n && (sorted[i] >>> 12) == groupPrefix);

            final NavSection[] sections = chunks == null ? null : chunks.get(NavStore.key(chunkX, chunkZ));
            if (sections == null || sectionIndex >= sections.length) continue; // unloaded since enqueue: drop
            final NavSection section = sections[sectionIndex];
            if (section == null) continue;
            final NavSection above = sectionIndex + 1 < sections.length ? sections[sectionIndex + 1] : null;
            final NavSection below = sectionIndex > 0 ? sections[sectionIndex - 1] : null;
            NavSectionBuilder.patchCells(section, above, below, cells, navs, m);
        }
        queue.clear();
    }

    /**
     * The grid-visibility decision for a CLEAN cell (package-private: the headless epoch test drives
     * this seam directly). {@code false} means the change is invisible to the nav grid: equal navtype ⇒
     * equal descriptor ⇒ identical inputs to every neighbour's flag/depth window ⇒ the patch would
     * recompute byte-identical values, so skipping BOTH the patch and the epoch bump exactly satisfies
     * the epoch contract above (unchanged epoch ⇒ re-search byte-identical). A DIRTY cell's decision
     * compares against its pending value instead — see {@link #enqueueIfChanges}.
     */
    static boolean changesGrid(NavSection section, int lx, int ly, int lz, short newNavtype) {
        return newNavtype != (short) section.getTraversalGrid().navtype(lx, ly, lz);
    }
}
