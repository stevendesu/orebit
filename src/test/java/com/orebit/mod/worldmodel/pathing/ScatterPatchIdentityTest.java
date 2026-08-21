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
 * build scatter, itself proven equal to an independent gather by {@code ScatterIdentityTest}); the
 * patch path must land on exactly those bits.
 *
 * <p>Two independent subjects are compared to the same oracle:
 * <ul>
 *   <li><b>Sequential {@code patchCell}</b> — one edit at a time (the pre-batching inline hook shape).</li>
 *   <li><b>{@code enqueueIfChanges} + {@code drain}</b> — the production deferred-batch path (grouped by
 *       section, seam-ordered), which fans out to {@link NavSectionBuilder#patchCells}.</li>
 * </ul>
 *
 * <p>Since 2026-08-21 the same proof covers {@code RISKS_GRAVITY}'s <b>half B</b>, which is scatter-owned
 * on exactly the same terms (build scatter / patch gather / {@code EdgeScatter} for the chunk faces). Half A
 * ({@code hasGravity(above)}) is {@code compute}'s and needs no re-derivation.
 *
 * <p>The edit set deliberately exercises every transition the re-dilations must handle:
 * <ol>
 *   <li><b>ADD</b> lava beside floor cells (bit 4 must be SET where it was clear — and bit 0 must NOT be:
 *       bit 0 has been strictly gravity since the §4.1 migration);</li>
 *   <li><b>REMOVE</b> lava so its neighbours lose their only fluid neighbour (bit 4 must be CLEARED — the
 *       authoritative-not-additive case);</li>
 *   <li><b>GRAVITY OVERLAP</b> — remove lava beside a cell that ALSO has a gravity block above it: the two
 *       facts live on separate bits, so bit 4 clears with the lava while bit 0 (gravity) STAYS —
 *       the authoritative window must rewrite both correctly. Re-sited to the cell DIRECTLY under the sand
 *       when bit 0 became cell-centred (it used to sit two rows down, the old floor frame);</li>
 *   <li><b>WATER</b> add and remove — <b>no longer inert for bit 4</b> (any-fluid, 2026-08-17): each must
 *       move HAS_FLUID_NEIGHBOR exactly as lava does, while staying completely inert for bit 0 (water has
 *       never been, and must never become, a bit-0 term);</li>
 *   <li><b>SEAM DOWN</b> — add and remove fluid at a section's row 0, whose {@code y-1} neighbour is the
 *       section BELOW's row 15 (the pre-existing below-seam window) — lava and water each;</li>
 *   <li><b>SEAM UP</b> — add and remove fluid at a section's row 15, whose {@code y+1} neighbour is the
 *       section ABOVE's row 0 (the above-seam window the patch path grew for the fluid term's downward
 *       read; the REMOVE half is the one that would fail an OR-only fix) — again both fluids;</li>
 *   <li><b>GRAVITY ADD / REMOVE</b> — a sand appearing or vanishing moves bit 0 on the cell beneath it
 *       (half A) and, while it hangs unsupported, on its five other neighbours (half B). The REMOVE half is
 *       the authoritative-not-additive case for bit 0, which had never been exercised at all;</li>
 *   <li><b>SUPPORT REMOVAL</b> — break the stone UNDER a supported sand. The sand flips
 *       supported→unsupported, so half B appears on its four laterals AND on the cell ABOVE it: two rows
 *       above the edit. <b>This is the transition that requires the {@code ly+2} recompute window</b>;
 *       without it this edit is the one that diverges from the rebuild;</li>
 *   <li><b>SEAM UP, support removal</b> — the same flip with the support at s0 row 15 and the sand at s1
 *       row 0, so the newly-marked cell is s1 row <b>1</b>. <b>This is the exact case that forces the
 *       above-seam pass to arm at {@code ly >= 14}</b> (not {@code ly == 15}) and its window to reach
 *       {@code +2};</li>
 *   <li><b>SEAM DOWN, gravity</b> — add and remove a suspended sand at s1 row 0, whose below-neighbour at
 *       s0 row 15 must gain/lose bit 0 across the seam (half A through the overscan, half B through the
 *       scatter).</li>
 * </ol>
 * A second test asserts the transitions as named bits directly, so a regression names the failing
 * direction instead of surfacing as an opaque array mismatch.
 */
class ScatterPatchIdentityTest {

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
     * {@code EdgeScatter} (not driven here) has nothing to contribute.
     */
    private static List<Edit> edits() {
        List<Edit> e = new ArrayList<>();
        e.add(new Edit(10, 1, 10, LAVA));   // ADD lava over the stone floor (bit 4 set, bit 0 untouched)
        e.add(new Edit(8, 1, 8, AIR));      // REMOVE lava (its neighbours lose their fluid bit)
        e.add(new Edit(5, 11, 4, AIR));     // GRAVITY OVERLAP: remove the lava; (4,11,4) keeps bit 0 (sand
                                            //   DIRECTLY above — half A), loses bit 4 (the fluid went away)
        e.add(new Edit(2, 5, 2, AIR));      // WATER remove — clears bit 4 around it, bit 0 untouched
        e.add(new Edit(12, 5, 12, WATER));  // WATER add — sets bit 4 around it, bit 0 untouched
        e.add(new Edit(9, 16, 9, LAVA));    // SEAM DOWN add  (s1 row 0  -> marks s0 row 15)
        e.add(new Edit(13, 16, 13, AIR));   // SEAM DOWN remove (s0 row 15 must LOSE its bit)
        e.add(new Edit(7, 15, 7, LAVA));    // SEAM UP add    (s0 row 15 -> marks s1 row 0)
        e.add(new Edit(11, 15, 11, AIR));   // SEAM UP remove (s1 row 0 must LOSE its bit)
        e.add(new Edit(3, 16, 3, WATER));   // WATER SEAM add (s1 row 0 -> marks s0 row 15 — water crosses too)
        e.add(new Edit(6, 15, 6, AIR));     // WATER SEAM remove (s1 row 0 must LOSE its water-fed bit)
        // ---- bit 0 (RISKS_GRAVITY) transitions — none of these existed before 2026-08-21 ----
        e.add(new Edit(4, 4, 12, SAND));    // GRAVITY ADD: (4,3,12) gains half A; laterals gain half B
        e.add(new Edit(12, 8, 4, AIR));     // GRAVITY REMOVE: (12,7,4) must LOSE bit 0 (authoritative)
        e.add(new Edit(2, 9, 12, AIR));     // SUPPORT REMOVAL: the sand at (2,10,12) goes unsupported, so
                                            //   (2,11,12) — TWO rows above the edit — gains half B
        e.add(new Edit(14, 15, 2, AIR));    // SEAM UP SUPPORT REMOVAL: support at s0 row 15, sand at s1
                                            //   row 0 -> (14,17,2), s1 row 1, gains half B
        e.add(new Edit(6, 16, 10, SAND));   // SEAM DOWN gravity add: (6,15,10) gains bit 0 across the seam
        e.add(new Edit(10, 16, 6, AIR));    // SEAM DOWN gravity remove: (10,15,6) must LOSE it
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
        // GRAVITY-OVERLAP case: sand (gravity) at y12 sits DIRECTLY above (4,11,4), which is what sets
        // bit 0 there (half A) — plus lava at (5,11,4), its x+1 neighbour, so bit 4 is ALSO set before the
        // edit. (Both cells moved up one row when bit 0 became cell-centred: under the old floor frame the
        // probe was (4,10,4), two rows under the sand, which is no longer a hazard at all.)
        put(secs, 4, 12, 4, SAND);
        put(secs, 5, 11, 4, LAVA);
        // SUPPORTED-OVERLAP case: stone at (9,11,9) holding sand at (9,12,9). The support carries bit 0
        // (half A); nothing around a SUPPORTED column carries half B. Untouched by any edit — it pins that
        // half B did not degenerate into "any cell near any gravity block".
        put(secs, 9, 11, 9, STONE);
        put(secs, 9, 12, 9, SAND);
        // GRAVITY REMOVE case: a suspended sand whose disappearance must CLEAR bit 0 below it.
        put(secs, 12, 8, 4, SAND);
        // SUPPORT REMOVAL case: stone holding a sand; breaking the stone flips the column to unsupported.
        put(secs, 2, 9, 12, STONE);
        put(secs, 2, 10, 12, SAND);
        // SEAM-UP SUPPORT REMOVAL case: the support is s0's row 15, the sand s1's row 0.
        put(secs, 14, 15, 2, STONE);
        put(secs, 14, 16, 2, SAND);
        // SEAM-DOWN gravity remove case: a suspended sand at s1 row 0, marking s0's (10,15,6) below it.
        put(secs, 10, 16, 6, SAND);
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
        assertTrue(risky(g, 4, 11, 4), "pre-edit: gravity DIRECTLY above sets bit 0 at (4,11,4) — half A");
        assertTrue(fluidNeighbor(g, 4, 11, 4), "pre-edit: the adjacent lava sets HAS_FLUID_NEIGHBOR at (4,11,4)");
        assertTrue(fluidNeighbor(g, 2, 6, 2), "pre-edit: water dilates the fluid bit");
        assertFalse(risky(g, 2, 6, 2), "pre-edit: water never touches bit 0");
        // The SUPPORTED overlap column — half A on the support, half B nowhere. Never edited; asserted
        // before and after so a regression that widened half B to supported columns is caught.
        assertTrue(risky(g, 9, 11, 9), "pre-edit: the support of a SUPPORTED sand carries bit 0 (half A)");
        assertFalse(risky(g, 8, 12, 9), "pre-edit: a SUPPORTED column marks no lateral neighbour (no half B)");

        ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        chunks.put(NavStore.key(0, 0), g);
        PendingPatches queue = new PendingPatches();
        for (Edit ed : edits()) {
            NavGridUpdater.enqueueIfChanges(queue, g[ed.wy() >> 4], ed.x(), ed.wy() & 15, ed.z(),
                    BlockPos.asLong(ed.x(), ed.wy(), ed.z()), NavBlock.navtypeFor(ed.state()));
        }
        NavGridUpdater.drain(queue, 0, chunks);

        // ADD / REMOVE, interior — the fluid fact moves on bit 4; bit 0 never fires (no gravity involved,
        // and adjacent lava sets bit 0 nowhere — RISKS_GRAVITY is gravity only, §4.1).
        assertTrue(fluidNeighbor(g, 10, 2, 10), "ADD: the cell above the new lava carries the fluid bit");
        assertTrue(fluidNeighbor(g, 9, 1, 10), "ADD: the lateral neighbour of the new lava carries the fluid bit");
        assertFalse(risky(g, 10, 2, 10), "ADD: adjacent lava never sets bit 0 (gravity only)");
        assertFalse(risky(g, 9, 1, 10), "ADD: adjacent lava never sets bit 0 laterally either");
        assertFalse(fluidNeighbor(g, 8, 2, 8), "REMOVE: the old lava's neighbour loses the fluid bit");
        assertFalse(fluidNeighbor(g, 7, 1, 8), "REMOVE: the old lava's lateral neighbour loses the fluid bit");

        // GRAVITY OVERLAP — separate bits: bit 4 clears with the lava, bit 0 (half A) survives.
        assertTrue(risky(g, 4, 11, 4), "gravity keeps bit 0 after the lava neighbour is removed");
        assertFalse(fluidNeighbor(g, 4, 11, 4), "...while HAS_FLUID_NEIGHBOR clears with the lava");

        // WATER moves bit 4 in both directions — and stays inert for bit 0 in both.
        assertTrue(fluidNeighbor(g, 12, 6, 12), "water ADD sets the fluid bit");
        assertFalse(risky(g, 12, 6, 12), "water ADD never sets bit 0");
        assertFalse(fluidNeighbor(g, 2, 6, 2), "water REMOVE clears the fluid bit");
        assertFalse(risky(g, 2, 6, 2), "water REMOVE leaves bit 0 clear");

        // Section seams, both directions, both transitions, both fluids.
        assertTrue(fluidNeighbor(g, 9, 15, 9), "SEAM DOWN add: new s1 row-0 lava marks s0 row 15");
        assertFalse(fluidNeighbor(g, 13, 15, 13), "SEAM DOWN remove: s0 row 15 loses the bit");
        assertTrue(fluidNeighbor(g, 7, 16, 7), "SEAM UP add: new s0 row-15 lava marks s1 row 0");
        assertFalse(fluidNeighbor(g, 11, 16, 11), "SEAM UP remove: s1 row 0 loses the bit");
        assertTrue(fluidNeighbor(g, 3, 15, 3), "WATER SEAM add: new s1 row-0 water marks s0 row 15");
        assertFalse(fluidNeighbor(g, 6, 16, 6), "WATER SEAM remove: s1 row 0 loses the water-fed bit");

        // ---- bit 0 (RISKS_GRAVITY) transitions ----
        assertTrue(risky(g, 4, 3, 12), "GRAVITY ADD: the cell under the new sand gains bit 0 (half A)");
        assertTrue(risky(g, 3, 4, 12), "GRAVITY ADD: a lateral of the (unsupported) new sand gains half B");
        assertFalse(risky(g, 4, 4, 12), "the gravity cell itself is never marked");
        assertFalse(risky(g, 12, 7, 4), "GRAVITY REMOVE: bit 0 CLEARS (authoritative, not additive)");
        assertFalse(risky(g, 11, 8, 4), "GRAVITY REMOVE: the lateral half B clears too");

        // SUPPORT REMOVAL — the flip that needs the ly+2 window: the edit is at y9, the newly-marked cell
        // at y11.
        assertTrue(risky(g, 2, 11, 12),
                "SUPPORT REMOVAL: the cell ABOVE the now-unsupported sand gains half B (two rows up)");
        assertTrue(risky(g, 1, 10, 12), "SUPPORT REMOVAL: its laterals gain half B too");

        // SEAM UP SUPPORT REMOVAL — the edit at s0 row 15 marks s1 row 1.
        assertTrue(risky(g, 14, 17, 2),
                "SEAM UP SUPPORT REMOVAL: s1 row 1 gains half B (arms the above-seam pass at ly >= 14)");

        // SEAM DOWN gravity, both directions.
        assertTrue(risky(g, 6, 15, 10), "SEAM DOWN gravity add: s0 row 15 gains bit 0 across the seam");
        assertFalse(risky(g, 10, 15, 6), "SEAM DOWN gravity remove: s0 row 15 loses it");

        // The untouched SUPPORTED column is still exactly half A.
        assertTrue(risky(g, 9, 11, 9), "post-edit: the SUPPORTED sand's support still carries half A");
        assertFalse(risky(g, 8, 12, 9), "post-edit: still no half B around a SUPPORTED column");
    }

    private static boolean risky(NavSection[] col, int x, int wy, int z) {
        return NavFlags.risksGravity(col[wy >> 4].getFlags(x, wy & 15, z));
    }

    private static boolean fluidNeighbor(NavSection[] col, int x, int wy, int z) {
        return NavFlags.hasFluidNeighbor(col[wy >> 4].getFlags(x, wy & 15, z));
    }

    private static void assertColumnEquals(String what, NavSection[] expected, NavSection[] actual) {
        for (int i = 0; i < expected.length; i++) {
            assertArrayEquals(expected[i].getTraversalGrid().raw(), actual[i].getTraversalGrid().raw(),
                    what + ": section " + i + " packed navtype/flag shorts diverged from the full rebuild "
                            + "(a scatter-owned re-dilation is wrong: HAS_FLUID_NEIGHBOR or RISKS_GRAVITY)");
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
