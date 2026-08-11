package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.orebit.mod.worldmodel.navblock.NavBlock;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

/**
 * <b>Correctness proof for the lava RISKY_EDIT re-dilation on the LIVE-EDIT (patch) path</b>
 * (PERF-DESIGN-navgrid-build §C1). The build path owns the lava RISKY_EDIT term as a SCATTER in
 * {@link NavSectionBuilder#computeDepth}; {@link NavFlags#compute} never produces it. So
 * {@link NavSectionBuilder#patchCell}/{@link NavSectionBuilder#patchCells} — which recompute flags via
 * {@code recomputeWindow → NavFlags.compute} — must re-derive it themselves, authoritatively, via the
 * {@link NavFlags#risksLavaEdit} gather plus the two section-seam reads the descriptor scratch cannot serve.
 *
 * <p>The proof shape: a live-edited grid must be <b>byte-for-byte identical to a full from-scratch rebuild
 * of the post-edit block states</b>. The full rebuild is the oracle (its RISKY_EDIT comes from the build
 * scatter, itself proven equal to an independent gather by {@code FluidScatterIdentityTest}); the patch path
 * must land on exactly those bits.
 *
 * <p>Two independent subjects are compared to the same oracle:
 * <ul>
 *   <li><b>Sequential {@code patchCell}</b> — one edit at a time (the pre-batching inline hook shape).</li>
 *   <li><b>{@code enqueueIfChanges} + {@code drain}</b> — the production deferred-batch path (grouped by
 *       section, seam-ordered), which fans out to {@link NavSectionBuilder#patchCells}.</li>
 * </ul>
 *
 * <p>The edit set deliberately exercises every transition the re-dilation must handle:
 * <ol>
 *   <li><b>ADD</b> lava beside floor cells (RISKY must be SET where it was clear);</li>
 *   <li><b>REMOVE</b> lava so its neighbours lose their only lava neighbour (RISKY must be CLEARED — the
 *       authoritative-not-additive case);</li>
 *   <li><b>GRAVITY OVERLAP</b> — remove lava beside a cell that ALSO has a gravity block above it: RISKY
 *       must STAY (proves the lava clear doesn't stomp the gravity term);</li>
 *   <li><b>WATER</b> add and remove — must be completely inert for RISKY_EDIT (the owner ruling: water no
 *       longer scatters at all);</li>
 *   <li><b>SEAM DOWN</b> — add and remove lava at a section's row 0, whose {@code y-1} neighbour is the
 *       section BELOW's row 15 (the pre-existing below-seam window);</li>
 *   <li><b>SEAM UP</b> — add and remove lava at a section's row 15, whose {@code y+1} neighbour is the
 *       section ABOVE's row 0. This direction is new with the 6-neighbour dilation: no flag ever read
 *       DOWNWARD across a section face before, so the patch path grew an above-seam window for it. The
 *       REMOVE half is the one that would fail an OR-only fix.</li>
 * </ol>
 * A second test asserts the seam bits directly, so a regression names the failing direction instead of
 * surfacing as an opaque array mismatch.
 */
class FluidPatchIdentityTest {

    private static boolean bootstrapped;

    private static BlockState AIR;
    private static BlockState STONE;
    private static BlockState WATER;
    private static BlockState LAVA;
    private static BlockState SAND;

    @BeforeAll
    static void boot() {
        if (!bootstrapped) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            bootstrapped = true;
        }
        AIR = Blocks.AIR.defaultBlockState();
        STONE = Blocks.STONE.defaultBlockState();
        WATER = Blocks.WATER.defaultBlockState();
        LAVA = Blocks.LAVA.defaultBlockState();
        SAND = Blocks.SAND.defaultBlockState();
    }

    private static final int SECTIONS = 4; // world y 0..63, minY 0

    /** One live block change: world coords + the new block state. */
    private record Edit(int x, int wy, int z, BlockState state) { }

    /**
     * The edited neighbourhood exercising every lava-RISKY transition (see the class doc). All cells sit in
     * chunk (0,0) with {@code 2 <= x,z <= 13}, so no lateral chunk face is involved and
     * {@code EdgeFluidScatter} (not driven here) has nothing to contribute.
     */
    private static List<Edit> edits() {
        List<Edit> e = new ArrayList<>();
        e.add(new Edit(10, 1, 10, LAVA));   // ADD lava over the stone floor
        e.add(new Edit(8, 1, 8, AIR));      // REMOVE lava (its neighbours lose their RISKY)
        e.add(new Edit(5, 10, 4, AIR));     // GRAVITY OVERLAP: remove the lava; sand above keeps (4,10,4) risky
        e.add(new Edit(2, 5, 2, AIR));      // WATER remove — must change nothing
        e.add(new Edit(12, 5, 12, WATER));  // WATER add — must change nothing
        e.add(new Edit(9, 16, 9, LAVA));    // SEAM DOWN add  (s1 row 0  -> marks s0 row 15)
        e.add(new Edit(13, 16, 13, AIR));   // SEAM DOWN remove (s0 row 15 must LOSE its bit)
        e.add(new Edit(7, 15, 7, LAVA));    // SEAM UP add    (s0 row 15 -> marks s1 row 0)
        e.add(new Edit(11, 15, 11, AIR));   // SEAM UP remove (s1 row 0 must LOSE its bit)
        return e;
    }

    // ---- Fixture: the pre-edit block states -------------------------------------------------------

    /** The source-of-truth block states (mutated to build the oracle; classified fresh for each subject). */
    private static PalettedContainer<BlockState>[] initialStates() {
        PalettedContainer<BlockState>[] secs = newColumn(SECTIONS);

        // Stone floor across the world-bottom row.
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) put(secs, x, 0, z, STONE);
        }
        // REMOVE case: lava over the floor (its 6 neighbours carry RISKY initially).
        put(secs, 8, 1, 8, LAVA);
        // WATER case: a water cell that will be removed (and must never have mattered).
        put(secs, 2, 5, 2, WATER);
        // GRAVITY-OVERLAP case: sand (gravity) at y12 above the cell (4,10,4) — that alone makes (4,10,4)
        // risky — plus lava at (5,10,4), its x+1 neighbour, so BOTH terms are set before the edit.
        put(secs, 4, 12, 4, SAND);
        put(secs, 5, 10, 4, LAVA);
        // SEAM DOWN remove case: lava at the s1 bottom row (y16), marking s0's (13,15,13) across the seam.
        put(secs, 13, 16, 13, LAVA);
        // SEAM UP remove case: lava at the s0 top row (y15), marking s1's (11,16,11) across the seam.
        put(secs, 11, 15, 11, LAVA);
        return secs;
    }

    // ---- The oracle + subjects --------------------------------------------------------------------

    @Test
    void patchPathIsByteIdenticalToRebuild() {
        List<Edit> edits = edits();

        // ORACLE — apply every edit to the block states, then FULL rebuild of the post-edit column.
        PalettedContainer<BlockState>[] oracleStates = initialStates();
        for (Edit ed : edits) put(oracleStates, ed.x(), ed.wy(), ed.z(), ed.state());
        NavSection[] oracle = fullBuild(oracleStates);

        // SUBJECT A — sequential patchCell on a live-maintained column built from the PRE-edit states.
        NavSection[] a = fullBuild(initialStates());
        for (Edit ed : edits) {
            int si = ed.wy() >> 4;
            NavSection above = si + 1 < SECTIONS ? a[si + 1] : null;
            NavSection below = si > 0 ? a[si - 1] : null;
            NavSectionBuilder.patchCell(a[si], above, below, ed.x(), ed.wy() & 15, ed.z(), ed.state());
        }
        assertColumnEquals("sequential patchCell", oracle, a);

        // SUBJECT B — the production enqueue + drain (batch) path on a fresh pre-edit column.
        NavSection[] b = fullBuild(initialStates());
        ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        chunks.put(NavStore.key(0, 0), b);
        PendingPatches queue = new PendingPatches();
        for (Edit ed : edits) {
            NavSection section = b[ed.wy() >> 4];
            NavGridUpdater.enqueueIfChanges(queue, section, ed.x(), ed.wy() & 15, ed.z(),
                    BlockPos.asLong(ed.x(), ed.wy(), ed.z()), NavBlock.navtypeFor(ed.state()));
        }
        NavGridUpdater.drain(queue, 0, chunks);
        assertColumnEquals("enqueue+drain batch", oracle, b);
    }

    /**
     * The same edits, asserted as NAMED bits rather than array equality — so a broken seam direction reports
     * which one. Run against the batch (production) subject; the byte-identity test above already pins the
     * sequential path to the same values.
     */
    @Test
    void seamAndWaterTransitionsLandOnTheRightBits() {
        NavSection[] g = fullBuild(initialStates());

        // Pre-edit: both seam bits are set by the BUILD scatter, in both directions.
        assertTrue(risky(g, 13, 15, 13), "pre-edit: s1 row-0 lava marks s0 row 15 (downward seam)");
        assertTrue(risky(g, 11, 16, 11), "pre-edit: s0 row-15 lava marks s1 row 0 (upward seam)");
        assertTrue(risky(g, 4, 10, 4), "pre-edit: gravity + lava both mark (4,10,4)");
        assertFalse(risky(g, 2, 6, 2), "pre-edit: water marks nothing");

        ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        chunks.put(NavStore.key(0, 0), g);
        PendingPatches queue = new PendingPatches();
        for (Edit ed : edits()) {
            NavGridUpdater.enqueueIfChanges(queue, g[ed.wy() >> 4], ed.x(), ed.wy() & 15, ed.z(),
                    BlockPos.asLong(ed.x(), ed.wy(), ed.z()), NavBlock.navtypeFor(ed.state()));
        }
        NavGridUpdater.drain(queue, 0, chunks);

        // ADD / REMOVE, interior.
        assertTrue(risky(g, 10, 2, 10), "ADD: the cell above the new lava is risky");
        assertTrue(risky(g, 9, 1, 10), "ADD: the lateral neighbour of the new lava is risky");
        assertFalse(risky(g, 8, 2, 8), "REMOVE: the old lava's neighbour is no longer risky");
        assertFalse(risky(g, 7, 1, 8), "REMOVE: the old lava's lateral neighbour is no longer risky");

        // GRAVITY OVERLAP — the lava term cleared, the gravity term survives.
        assertTrue(risky(g, 4, 10, 4), "gravity keeps the bit after its lava neighbour is removed");

        // WATER is inert in both directions.
        assertFalse(risky(g, 12, 6, 12), "water ADD sets nothing");
        assertFalse(risky(g, 2, 6, 2), "water REMOVE changes nothing");

        // Section seams, both directions, both transitions.
        assertTrue(risky(g, 9, 15, 9), "SEAM DOWN add: new s1 row-0 lava marks s0 row 15");
        assertFalse(risky(g, 13, 15, 13), "SEAM DOWN remove: s0 row 15 loses the bit");
        assertTrue(risky(g, 7, 16, 7), "SEAM UP add: new s0 row-15 lava marks s1 row 0");
        assertFalse(risky(g, 11, 16, 11), "SEAM UP remove: s1 row 0 loses the bit");
    }

    private static boolean risky(NavSection[] col, int x, int wy, int z) {
        return NavFlags.risksEdit(col[wy >> 4].getFlags(x, wy & 15, z));
    }

    private static void assertColumnEquals(String what, NavSection[] expected, NavSection[] actual) {
        for (int i = 0; i < expected.length; i++) {
            assertArrayEquals(expected[i].getTraversalGrid().raw(), actual[i].getTraversalGrid().raw(),
                    what + ": section " + i + " packed navtype/flag shorts diverged from the full rebuild "
                            + "(lava RISKY_EDIT re-dilation wrong)");
            assertArrayEquals(expected[i].getTraversalGrid().depthRaw(), actual[i].getTraversalGrid().depthRaw(),
                    what + ": section " + i + " depth nibble bytes diverged from the full rebuild");
        }
    }

    // ---- Plumbing ---------------------------------------------------------------------------------

    /** A full column build — the live pipeline: classify per section, overscan flags, column depth sweep. */
    private static NavSection[] fullBuild(PalettedContainer<BlockState>[] secs) {
        NavSection[] sections = new NavSection[secs.length];
        boolean[] allAir = new boolean[secs.length];
        for (int i = 0; i < secs.length; i++) {
            sections[i] = NavSection.create(BlockPos.ZERO);
            allAir[i] = NavSectionBuilder.classifyNavtypes(secs[i], false, sections[i].getTraversalGrid(), null);
        }
        for (int i = 0; i < secs.length; i++) {
            NavSection above = i + 1 < secs.length ? sections[i + 1] : null;
            NavSectionBuilder.computeFlags(sections[i].getTraversalGrid(), allAir[i],
                    (above == null || allAir[i + 1]) ? null : above.getTraversalGrid());
        }
        NavSectionBuilder.computeDepth(sections);
        return sections;
    }

    @SuppressWarnings("unchecked")
    private static PalettedContainer<BlockState>[] newColumn(int n) {
        PalettedContainer<BlockState>[] secs = new PalettedContainer[n];
        for (int i = 0; i < n; i++) {
            secs[i] = new PalettedContainer<>(AIR, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        }
        return secs;
    }

    /** Place a block at WORLD y in the column (section {@code wy>>4}, local row {@code wy&15}). */
    private static void put(PalettedContainer<BlockState>[] secs, int x, int wy, int z, BlockState st) {
        secs[wy >> 4].set(x, wy & 15, z, st);
    }
}
