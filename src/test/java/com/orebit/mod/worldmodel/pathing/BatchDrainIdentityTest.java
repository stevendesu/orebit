package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.orebit.mod.worldmodel.navblock.NavBlock;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;

/**
 * The mandatory correctness guards for the deferred block-edit drain
 * (PERF-DESIGN-navgrid-edit-batching.md §6 — all three, plus the direct-seam duplicate case the
 * {@code BatchEditBenchmark} shapes exercise):
 * <ul>
 *   <li><b>Identity</b> (the {@code DepthIdentityTest} pattern): randomized change sequences —
 *       including same-cell toggles, same-column stacks (dig-then-refill across section seams), seam
 *       rows, and multi-chunk scatter — applied (a) sequentially via {@code patchCell} (today's inline
 *       pipeline, gated by {@code changesGrid} exactly as the old hook was) and (b) via
 *       {@code enqueueIfChanges} + {@code drain} must leave the final navtype {@code short[]} grids AND
 *       depth {@code byte[]}s byte-equal. Both nibblesets and every flag are pure functions of the
 *       final navtype field on the union of recompute windows, so any divergence is a bug, not a
 *       tuning question.</li>
 *   <li><b>Seam duplicates</b>: {@link NavSectionBuilder#patchCells} keeps the "N changes arriving in
 *       sequence" contract for callers that hand it a raw event stream (the benchmark does — the
 *       enqueue queue dedups before the drain ever calls it), so a duplicate-bearing batch must equal
 *       the sequential loop too.</li>
 *   <li><b>Barrier</b>: after a drain the queue is empty ({@code pendingCount == 0}), reads see the
 *       final state, and a re-drain is a no-op. (The live {@code flush(level)} wiring around this seam
 *       needs a {@code ServerLevel} — not standable under the Knot test classloader, the same split
 *       {@code NavGridEpochTest} documents — and is three straight-line lines: the static-int gate,
 *       the map lookup, and the delegation to the {@code drain} seam tested here.)</li>
 *   <li><b>Epoch</b>: Phase-0/effective-navtype skips don't bump (return {@code false} and leave the
 *       queue untouched); real changes bump ({@code true} exactly when the queue changed), so a
 *       debounce read behind any barrier can never observe queued-but-unbumped state.</li>
 *   <li><b>Unload drop</b>: entries whose chunk left the store between enqueue and drain are dropped,
 *       never patched onto a stale section.</li>
 * </ul>
 * Fixture: the {@link PatchStormBenchmark#buildStrataColumn() strata chunk column} (built the column
 * way, so the depth nibbles are live).
 */
class BatchDrainIdentityTest {

    private static boolean bootstrapped;

    @BeforeAll
    static void boot() {
        if (!bootstrapped) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            bootstrapped = true;
        }
    }

    private static final int SECTIONS = 6; // strata column: y 0..95, minY 0

    private static short[] palette() {
        return new short[] {
                NavBlock.navtypeFor(Blocks.STONE.defaultBlockState()),
                NavBlock.navtypeFor(Blocks.AIR.defaultBlockState()),
                NavBlock.navtypeFor(Blocks.DIRT.defaultBlockState()),
                NavBlock.navtypeFor(Blocks.GLASS.defaultBlockState()),
                NavBlock.navtypeFor(Blocks.WATER.defaultBlockState()),
                NavBlock.navtypeFor(Blocks.LADDER.defaultBlockState()),
        };
    }

    /** One fired change: chunkX (0 or 1, chunkZ always 0), world y, local x/z, new navtype. */
    private record Event(int chunkX, int lx, int y, int lz, short nav) { }

    /**
     * The randomized event stream: multi-chunk scatter, same-cell toggles, a same-column
     * dig-then-refill stack crossing the section 1|2 seam, and seam-row clusters.
     */
    private static List<Event> randomEvents(long seed) {
        final short[] pal = palette();
        final short air = pal[1];
        final short stone = pal[0];
        final Random rng = new Random(seed);
        final List<Event> events = new ArrayList<>();

        // (a) scatter — anywhere in either chunk, any palette navtype.
        for (int i = 0; i < 150; i++) {
            events.add(new Event(rng.nextInt(2), rng.nextInt(16), 1 + rng.nextInt(94), rng.nextInt(16),
                    pal[rng.nextInt(pal.length)]));
        }
        // (b) same-cell toggles — 5 cells fired 6 times each, alternating air/stone (net no-op).
        for (int t = 0; t < 5; t++) {
            final int cx = rng.nextInt(2), lx = rng.nextInt(16), y = 1 + rng.nextInt(94), lz = rng.nextInt(16);
            for (int k = 0; k < 6; k++) {
                events.add(new Event(cx, lx, y, lz, (k & 1) == 0 ? air : stone));
            }
        }
        // (c) same-column stack — dig a shaft top-down across the 31|32 seam, then refill bottom-up.
        final int slx = rng.nextInt(16), slz = rng.nextInt(16), scx = rng.nextInt(2);
        for (int y = 40; y >= 20; y--) events.add(new Event(scx, slx, y, slz, air));
        for (int y = 20; y <= 40; y++) events.add(new Event(scx, slx, y, slz, stone));
        // (d) seam rows — ly ∈ {0,1,2,13,14,15}, the below-seam / cross-seam depth paths.
        final int[] seamRows = { 0, 1, 2, 13, 14, 15 };
        for (int i = 0; i < 60; i++) {
            final int sec = rng.nextInt(SECTIONS);
            final int y = (sec << 4) + seamRows[rng.nextInt(seamRows.length)];
            if (y == 0) continue; // keep off the bedrock floor row for variety parity with (a)
            events.add(new Event(rng.nextInt(2), rng.nextInt(16), y, rng.nextInt(16),
                    pal[rng.nextInt(pal.length)]));
        }
        return events;
    }

    /** Apply one event the way the pre-batching hook did: gate on {@code changesGrid}, patch inline. */
    private static void applySequential(NavSection[] column, Event e) {
        final int si = e.y() >> 4;
        final NavSection section = column[si];
        if (!NavGridUpdater.changesGrid(section, e.lx(), e.y() & 15, e.lz(), e.nav())) return;
        final NavSection above = si + 1 < column.length ? column[si + 1] : null;
        final NavSection below = si > 0 ? column[si - 1] : null;
        NavSectionBuilder.patchCell(section, above, below, e.lx(), e.y() & 15, e.lz(), e.nav());
    }

    private static short[][] rawSnapshot(NavSection[]... columns) {
        final List<short[]> out = new ArrayList<>();
        for (NavSection[] column : columns) {
            for (NavSection s : column) out.add(s.getTraversalGrid().raw().clone());
        }
        return out.toArray(new short[0][]);
    }

    private static byte[][] depthSnapshot(NavSection[]... columns) {
        final List<byte[]> out = new ArrayList<>();
        for (NavSection[] column : columns) {
            for (NavSection s : column) out.add(s.getTraversalGrid().depthRaw().clone());
        }
        return out.toArray(new byte[0][]);
    }

    private static void assertColumnsEqual(String what, NavSection[][] expected, NavSection[][] actual) {
        for (int c = 0; c < expected.length; c++) {
            for (int s = 0; s < expected[c].length; s++) {
                assertArrayEquals(expected[c][s].getTraversalGrid().raw(),
                        actual[c][s].getTraversalGrid().raw(),
                        what + ": chunk " + c + " section " + s + " navtype/flag grids diverged");
                assertArrayEquals(expected[c][s].getTraversalGrid().depthRaw(),
                        actual[c][s].getTraversalGrid().depthRaw(),
                        what + ": chunk " + c + " section " + s + " depth nibbles diverged");
            }
        }
    }

    // ---- §6 identity guard: enqueue+drain ≡ sequential patchCell ------------------------------

    @Test
    void enqueueDrainMatchesSequential() {
        for (long seed : new long[] { 1, 2, 3, 42, 1337 }) {
            final List<Event> events = randomEvents(seed);

            // A — today's inline pipeline, event by event.
            final NavSection[][] a = { PatchStormBenchmark.buildStrataColumn(),
                    PatchStormBenchmark.buildStrataColumn() };
            for (Event e : events) applySequential(a[e.chunkX()], e);

            // B — enqueue every event (the effective-navtype no-op + last-wins dedup), then one drain.
            final NavSection[][] b = { PatchStormBenchmark.buildStrataColumn(),
                    PatchStormBenchmark.buildStrataColumn() };
            final ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
            chunks.put(NavStore.key(0, 0), b[0]);
            chunks.put(NavStore.key(1, 0), b[1]);
            final PendingPatches queue = new PendingPatches();
            for (Event e : events) {
                final NavSection section = b[e.chunkX()][e.y() >> 4];
                NavGridUpdater.enqueueIfChanges(queue, section, e.lx(), e.y() & 15, e.lz(),
                        BlockPos.asLong((e.chunkX() << 4) | e.lx(), e.y(), e.lz()), e.nav());
            }
            NavGridUpdater.drain(queue, 0, chunks);

            assertEquals(0, queue.count(), "seed " + seed + ": drain must leave the queue empty");
            assertColumnsEqual("seed " + seed, a, b);
        }
    }

    // ---- the seam's own contract: a duplicate-bearing batch ≡ the sequential loop -------------

    @Test
    void patchCellsWithDuplicatesMatchesSequential() {
        final short[] pal = palette();
        final short air = pal[1], stone = pal[0], glass = pal[3];

        // A raw event stream in sections 1 and 2 (fire order): a toggled row (net no-op), a re-fired
        // cell whose LAST value must win, and seam-row cells on both sides of the 1|2 face.
        final int[][] events = { // {sectionIndex, packedCell, navtype}
                // section 1: toggle a 4-cell row at ly 6 out and back (duplicates, net zero)
                { 1, pack(2, 6, 12), air }, { 1, pack(3, 6, 12), air },
                { 1, pack(4, 6, 12), air }, { 1, pack(5, 6, 12), air },
                { 1, pack(2, 6, 12), stone }, { 1, pack(3, 6, 12), stone },
                { 1, pack(4, 6, 12), stone }, { 1, pack(5, 6, 12), stone },
                // section 1: same cell fired three times — glass must win
                { 1, pack(8, 14, 8), air }, { 1, pack(8, 14, 8), stone }, { 1, pack(8, 14, 8), glass },
                // section 1 top rows + section 2 bottom rows (the below-seam pass both ways)
                { 1, pack(5, 15, 5), air }, { 1, pack(6, 15, 5), air },
                { 2, pack(5, 0, 5), air }, { 2, pack(5, 1, 5), air }, { 2, pack(6, 2, 5), glass },
        };

        // A — the sequential loop in fire order, grouped ascending by section exactly like the old
        // seam body / the benchmark's sub-batch shape.
        final NavSection[] a = PatchStormBenchmark.buildStrataColumn();
        for (int si = 1; si <= 2; si++) {
            for (int[] e : events) {
                if (e[0] != si) continue;
                final int p = e[1];
                NavSectionBuilder.patchCell(a[si], a[si + 1], a[si - 1],
                        p & 15, p >>> 8, (p >>> 4) & 15, (short) e[2]);
            }
        }

        // B — one phased patchCells call per section, ascending (the drain shape).
        final NavSection[] b = PatchStormBenchmark.buildStrataColumn();
        for (int si = 1; si <= 2; si++) {
            final short[] cells = new short[events.length];
            final short[] navs = new short[events.length];
            int m = 0;
            for (int[] e : events) {
                if (e[0] != si) continue;
                cells[m] = (short) e[1];
                navs[m] = (short) e[2];
                m++;
            }
            NavSectionBuilder.patchCells(b[si], b[si + 1], b[si - 1], cells, navs, m);
        }

        assertColumnsEqual("duplicate batch", new NavSection[][] { a }, new NavSection[][] { b });
    }

    private static int pack(int lx, int ly, int lz) {
        return (ly << 8) | (lz << 4) | lx;
    }

    // ---- §6 barrier guard: drain empties the queue and reads see final state ------------------

    @Test
    void drainLeavesQueueEmptyAndReadsFinal() {
        final short[] pal = palette();
        final short air = pal[1], glass = pal[3];

        final NavSection[] column = PatchStormBenchmark.buildStrataColumn();
        final ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        chunks.put(NavStore.key(0, 0), column);
        final PendingPatches queue = new PendingPatches();

        final int[][] cells = { { 4, 20, 4 }, { 5, 20, 4 }, { 8, 33, 8 } }; // {x, y, z}
        for (int[] c : cells) {
            final NavSection section = column[c[1] >> 4];
            assertTrue(NavGridUpdater.enqueueIfChanges(queue, section, c[0], c[1] & 15, c[2],
                    BlockPos.asLong(c[0], c[1], c[2]), air));
        }
        // Re-fire one cell: dedup keeps the count, the LAST navtype must be what the drain applies.
        assertTrue(NavGridUpdater.enqueueIfChanges(queue, column[1], 4, 4, 4,
                BlockPos.asLong(4, 20, 4), glass));
        assertEquals(3, queue.count(), "re-fired cell must dedup, not append");

        // Deferred: the resident grid is untouched until the drain (the barrier is what publishes it).
        assertNotEquals(air & 0xFFFF, column[1].getTraversalGrid().navtype(5, 4, 4),
                "enqueue must not patch the grid");

        NavGridUpdater.drain(queue, 0, chunks);

        assertEquals(0, queue.count(), "a drained queue is empty (pendingCount == 0)");
        assertEquals(glass & 0xFFFF, column[1].getTraversalGrid().navtype(4, 4, 4),
                "reads behind the barrier see the LAST enqueued state");
        assertEquals(air & 0xFFFF, column[1].getTraversalGrid().navtype(5, 4, 4));
        assertEquals(air & 0xFFFF, column[2].getTraversalGrid().navtype(8, 1, 8));

        // A second drain of the (now clean) queue is a no-op — byte-identical grids.
        final short[][] raw = rawSnapshot(column);
        final byte[][] depth = depthSnapshot(column);
        NavGridUpdater.drain(queue, 0, chunks);
        assertArrayEquals(raw, rawSnapshot(column));
        assertArrayEquals(depth, depthSnapshot(column));
    }

    // ---- §6 epoch guard: bump iff the queue changed --------------------------------------------

    @Test
    void bumpDecisionTracksQueueVisibility() {
        final short[] pal = palette();
        final short stone = pal[0], air = pal[1];

        final NavSection[] column = PatchStormBenchmark.buildStrataColumn();
        final NavSection s1 = column[1];
        final long key = BlockPos.asLong(8, 22, 8); // mid-stone, section 1 / ly 6
        final PendingPatches queue = new PendingPatches();

        // Phase-0 skip on a clean cell: same navtype as resident → no queue entry, no bump.
        assertFalse(NavGridUpdater.enqueueIfChanges(queue, s1, 8, 6, 8, key, stone),
                "same-navtype change on a clean cell must not bump");
        assertEquals(0, queue.count());

        // Real change → queued + bumped.
        assertTrue(NavGridUpdater.enqueueIfChanges(queue, s1, 8, 6, 8, key, air));
        assertEquals(1, queue.count());
        assertEquals(air & 0xFFFF, queue.get(key));

        // Re-fire with the SAME pending navtype: effective no-op → no bump, queue untouched.
        assertFalse(NavGridUpdater.enqueueIfChanges(queue, s1, 8, 6, 8, key, air),
                "pending-matching change must not bump");
        assertEquals(1, queue.count());

        // Toggle back to the resident navtype: pending differs → bumps (twice per extend+retract,
        // identical to the pre-batching pipeline) and the pending value returns to resident.
        assertTrue(NavGridUpdater.enqueueIfChanges(queue, s1, 8, 6, 8, key, stone),
                "a toggle-back is a pending-value change and must bump, like today");
        assertEquals(1, queue.count());
        assertEquals(stone & 0xFFFF, queue.get(key));

        // The drained toggle is a net no-op — the grid stays byte-identical.
        final ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        chunks.put(NavStore.key(0, 0), column);
        final short[][] raw = rawSnapshot(column);
        final byte[][] depth = depthSnapshot(column);
        NavGridUpdater.drain(queue, 0, chunks);
        assertEquals(0, queue.count());
        assertArrayEquals(raw, rawSnapshot(column));
        assertArrayEquals(depth, depthSnapshot(column));

        // Structural half of "a barrier read never observes queued-but-unbumped state": a false return
        // NEVER changes the queue (count or pending value), so every queued change was announced by a
        // true return — i.e. an epoch bump — at the instant it was queued, before any barrier could run.
        final Random rng = new Random(7);
        final short[] shortPal = { stone, air, pal[2] };
        for (int i = 0; i < 500; i++) {
            final int lx = rng.nextInt(16), ly = rng.nextInt(16), lz = rng.nextInt(16);
            final long k = BlockPos.asLong(lx, 16 + ly, lz);
            final short nav = shortPal[rng.nextInt(shortPal.length)];
            final int countBefore = queue.count();
            final int pendingBefore = queue.get(k);
            final boolean bumped = NavGridUpdater.enqueueIfChanges(queue, s1, lx, ly, lz, k, nav);
            if (!bumped) {
                assertEquals(countBefore, queue.count(), "a skipped change must leave the queue size");
                assertEquals(pendingBefore, queue.get(k), "a skipped change must leave the pending value");
            } else {
                assertEquals(nav & 0xFFFF, queue.get(k), "a bumped change must be the queued value");
            }
        }
    }

    // ---- unload drop: entries whose chunk left the store are dropped, not mis-patched ---------

    @Test
    void unloadedChunkEntriesAreDropped() {
        final short air = palette()[1];

        final NavSection[] loaded = PatchStormBenchmark.buildStrataColumn();
        final NavSection[] unloaded = PatchStormBenchmark.buildStrataColumn();
        final ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        chunks.put(NavStore.key(0, 0), loaded); // chunk (1,0) is NOT in the store at drain time

        final PendingPatches queue = new PendingPatches();
        assertTrue(NavGridUpdater.enqueueIfChanges(queue, loaded[1], 8, 6, 8,
                BlockPos.asLong(8, 22, 8), air));
        assertTrue(NavGridUpdater.enqueueIfChanges(queue, unloaded[1], 8, 6, 8,
                BlockPos.asLong(24, 22, 8), air));

        final short[][] rawUnloaded = rawSnapshot(unloaded);
        NavGridUpdater.drain(queue, 0, chunks);

        assertEquals(0, queue.count(), "dropped entries still leave the drain with an empty queue");
        assertEquals(air & 0xFFFF, loaded[1].getTraversalGrid().navtype(8, 6, 8),
                "the loaded chunk's entry must still be patched");
        assertArrayEquals(rawUnloaded, rawSnapshot(unloaded),
                "an unloaded chunk's sections must never be touched by the drain");
    }
}
