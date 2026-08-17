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
 * <b>Correctness proof for the HAS_FLUID_NEIGHBOR re-dilation on the LIVE-EDIT (patch) path</b>
 * (PERF-DESIGN-navgrid-build §C1; DESIGN-fluid-flow-prediction.md §4, §9's "Flag" bullets). The build path
 * owns the any-fluid HAS_FLUID_NEIGHBOR term as a SCATTER in {@link NavSectionBuilder#computeDepth};
 * {@link NavFlags#compute} never produces it. So
 * {@link NavSectionBuilder#patchCell}/{@link NavSectionBuilder#patchCells} — which recompute flags via
 * {@code recomputeWindow → NavFlags.compute} — must re-derive it themselves, authoritatively, via the
 * {@link NavFlags#hasFluidNeighborGather} gather plus the two section-seam reads the descriptor scratch
 * cannot serve.
 *
 * <p>The proof shape: a live-edited grid must be <b>byte-for-byte identical to a full from-scratch rebuild
 * of the post-edit block states</b>. The full rebuild is the oracle (its HAS_FLUID_NEIGHBOR comes from the
 * build scatter, itself proven equal to an independent gather by {@code FluidScatterIdentityTest}); the
 * patch path must land on exactly those bits.
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
 *   <li><b>ADD</b> lava beside floor cells (bit 4 must be SET where it was clear — and bit 0 must NOT be:
 *       RISKY_EDIT is strictly gravity since the §4.1 migration);</li>
 *   <li><b>REMOVE</b> lava so its neighbours lose their only fluid neighbour (bit 4 must be CLEARED — the
 *       authoritative-not-additive case);</li>
 *   <li><b>GRAVITY OVERLAP</b> — remove lava beside a cell that ALSO has a gravity block above it: the two
 *       facts now live on separate bits, so bit 4 clears with the lava while bit 0 (gravity) STAYS —
 *       the authoritative window must rewrite both correctly;</li>
 *   <li><b>WATER</b> add and remove — <b>no longer inert for bit 4</b> (any-fluid, 2026-08-17): each must
 *       move HAS_FLUID_NEIGHBOR exactly as lava does, while staying completely inert for bit 0 (water has
 *       never been, and must never become, a RISKY_EDIT term);</li>
 *   <li><b>SEAM DOWN</b> — add and remove fluid at a section's row 0, whose {@code y-1} neighbour is the
 *       section BELOW's row 15 (the pre-existing below-seam window) — lava and water each;</li>
 *   <li><b>SEAM UP</b> — add and remove fluid at a section's row 15, whose {@code y+1} neighbour is the
 *       section ABOVE's row 0 (the above-seam window the patch path grew for the fluid term's downward
 *       read; the REMOVE half is the one that would fail an OR-only fix) — again both fluids.</li>
 * </ol>
 * A second test asserts the transitions as named bits directly, so a regression names the failing
 * direction instead of surfacing as an opaque array mismatch.
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
     * The edited neighbourhood exercising every HAS_FLUID_NEIGHBOR transition (see the class doc). All
     * cells sit in chunk (0,0) with {@code 2 <= x,z <= 13}, so no lateral chunk face is involved and
     * {@code EdgeFluidScatter} (not driven here) has nothing to contribute.
     */
    private static List<Edit> edits() {
        List<Edit> e = new ArrayList<>();
        e.add(new Edit(10, 1, 10, LAVA));   // ADD lava over the stone floor (bit 4 set, bit 0 untouched)
        e.add(new Edit(8, 1, 8, AIR));      // REMOVE lava (its neighbours lose their fluid bit)
        e.add(new Edit(5, 10, 4, AIR));     // GRAVITY OVERLAP: remove the lava; (4,10,4) keeps bit 0 (sand
                                            //   above), loses bit 4 (the fluid went away)
        e.add(new Edit(2, 5, 2, AIR));      // WATER remove — clears bit 4 around it, bit 0 untouched
        e.add(new Edit(12, 5, 12, WATER));  // WATER add — sets bit 4 around it, bit 0 untouched
        e.add(new Edit(9, 16, 9, LAVA));    // SEAM DOWN add  (s1 row 0  -> marks s0 row 15)
        e.add(new Edit(13, 16, 13, AIR));   // SEAM DOWN remove (s0 row 15 must LOSE its bit)
        e.add(new Edit(7, 15, 7, LAVA));    // SEAM UP add    (s0 row 15 -> marks s1 row 0)
        e.add(new Edit(11, 15, 11, AIR));   // SEAM UP remove (s1 row 0 must LOSE its bit)
        e.add(new Edit(3, 16, 3, WATER));   // WATER SEAM add (s1 row 0 -> marks s0 row 15 — water crosses too)
        e.add(new Edit(6, 15, 6, AIR));     // WATER SEAM remove (s1 row 0 must LOSE its water-fed bit)
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
        // REMOVE case: lava over the floor (its 6 neighbours carry the fluid bit initially).
        put(secs, 8, 1, 8, LAVA);
        // WATER case: a water cell that will be removed (its neighbours carry bit 4 — and never bit 0).
        put(secs, 2, 5, 2, WATER);
        // GRAVITY-OVERLAP case: sand (gravity) at y12 above the cell (4,10,4) — that alone sets bit 0
        // there — plus lava at (5,10,4), its x+1 neighbour, so bit 4 is ALSO set before the edit.
        put(secs, 4, 12, 4, SAND);
        put(secs, 5, 10, 4, LAVA);
        // SEAM DOWN remove case: lava at the s1 bottom row (y16), marking s0's (13,15,13) across the seam.
        put(secs, 13, 16, 13, LAVA);
        // SEAM UP remove case: lava at the s0 top row (y15), marking s1's (11,16,11) across the seam.
        put(secs, 11, 15, 11, LAVA);
        // WATER SEAM remove case: water at the s0 top row (y15), marking s1's (6,16,6) across the seam.
        put(secs, 6, 15, 6, WATER);
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

        // Pre-edit: the BUILD scatter set the fluid bit in both seam directions, for both fluids — and the
        // gravity/fluid facts sit on their separate bits at the overlap cell.
        assertTrue(fluidNeighbor(g, 13, 15, 13), "pre-edit: s1 row-0 lava marks s0 row 15 (downward seam)");
        assertTrue(fluidNeighbor(g, 11, 16, 11), "pre-edit: s0 row-15 lava marks s1 row 0 (upward seam)");
        assertTrue(fluidNeighbor(g, 6, 16, 6), "pre-edit: s0 row-15 water marks s1 row 0 (upward water seam)");
        assertTrue(risky(g, 4, 10, 4), "pre-edit: gravity (sand above) sets RISKY_EDIT at (4,10,4)");
        assertTrue(fluidNeighbor(g, 4, 10, 4), "pre-edit: the adjacent lava sets HAS_FLUID_NEIGHBOR at (4,10,4)");
        assertTrue(fluidNeighbor(g, 2, 6, 2), "pre-edit: water dilates the fluid bit");
        assertFalse(risky(g, 2, 6, 2), "pre-edit: water never touches RISKY_EDIT");

        ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        chunks.put(NavStore.key(0, 0), g);
        PendingPatches queue = new PendingPatches();
        for (Edit ed : edits()) {
            NavGridUpdater.enqueueIfChanges(queue, g[ed.wy() >> 4], ed.x(), ed.wy() & 15, ed.z(),
                    BlockPos.asLong(ed.x(), ed.wy(), ed.z()), NavBlock.navtypeFor(ed.state()));
        }
        NavGridUpdater.drain(queue, 0, chunks);

        // ADD / REMOVE, interior — the fluid fact moves on bit 4; bit 0 never fires (no gravity involved,
        // and adjacent lava no longer sets RISKY_EDIT anywhere — strictly gravity, §4.1).
        assertTrue(fluidNeighbor(g, 10, 2, 10), "ADD: the cell above the new lava carries the fluid bit");
        assertTrue(fluidNeighbor(g, 9, 1, 10), "ADD: the lateral neighbour of the new lava carries the fluid bit");
        assertFalse(risky(g, 10, 2, 10), "ADD: adjacent lava never sets RISKY_EDIT (strictly gravity)");
        assertFalse(risky(g, 9, 1, 10), "ADD: adjacent lava never sets RISKY_EDIT laterally either");
        assertFalse(fluidNeighbor(g, 8, 2, 8), "REMOVE: the old lava's neighbour loses the fluid bit");
        assertFalse(fluidNeighbor(g, 7, 1, 8), "REMOVE: the old lava's lateral neighbour loses the fluid bit");

        // GRAVITY OVERLAP — separate bits now: bit 4 clears with the lava, bit 0 (gravity) survives.
        assertTrue(risky(g, 4, 10, 4), "gravity keeps RISKY_EDIT after the lava neighbour is removed");
        assertFalse(fluidNeighbor(g, 4, 10, 4), "...while HAS_FLUID_NEIGHBOR clears with the lava");

        // WATER moves bit 4 in both directions — and stays inert for bit 0 in both.
        assertTrue(fluidNeighbor(g, 12, 6, 12), "water ADD sets the fluid bit");
        assertFalse(risky(g, 12, 6, 12), "water ADD never sets RISKY_EDIT");
        assertFalse(fluidNeighbor(g, 2, 6, 2), "water REMOVE clears the fluid bit");
        assertFalse(risky(g, 2, 6, 2), "water REMOVE leaves RISKY_EDIT clear");

        // Section seams, both directions, both transitions, both fluids.
        assertTrue(fluidNeighbor(g, 9, 15, 9), "SEAM DOWN add: new s1 row-0 lava marks s0 row 15");
        assertFalse(fluidNeighbor(g, 13, 15, 13), "SEAM DOWN remove: s0 row 15 loses the bit");
        assertTrue(fluidNeighbor(g, 7, 16, 7), "SEAM UP add: new s0 row-15 lava marks s1 row 0");
        assertFalse(fluidNeighbor(g, 11, 16, 11), "SEAM UP remove: s1 row 0 loses the bit");
        assertTrue(fluidNeighbor(g, 3, 15, 3), "WATER SEAM add: new s1 row-0 water marks s0 row 15");
        assertFalse(fluidNeighbor(g, 6, 16, 6), "WATER SEAM remove: s1 row 0 loses the water-fed bit");
    }

    private static boolean risky(NavSection[] col, int x, int wy, int z) {
        return NavFlags.risksEdit(col[wy >> 4].getFlags(x, wy & 15, z));
    }

    private static boolean fluidNeighbor(NavSection[] col, int x, int wy, int z) {
        return NavFlags.hasFluidNeighbor(col[wy >> 4].getFlags(x, wy & 15, z));
    }

    private static void assertColumnEquals(String what, NavSection[] expected, NavSection[] actual) {
        for (int i = 0; i < expected.length; i++) {
            assertArrayEquals(expected[i].getTraversalGrid().raw(), actual[i].getTraversalGrid().raw(),
                    what + ": section " + i + " packed navtype/flag shorts diverged from the full rebuild "
                            + "(HAS_FLUID_NEIGHBOR re-dilation wrong)");
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
