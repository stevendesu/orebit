package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

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
 * <b>Identity + semantics proof for the LAVA RISKY_EDIT SCATTER</b> (PERF-DESIGN-navgrid-build §C1,
 * re-scoped by the owner ruling of 2026-08-10 — see {@link NavFlags}'s lava-term section).
 *
 * <p>The chunk-column build SCATTERS RISKY_EDIT from each lava cell in
 * {@link NavSectionBuilder#computeDepth}, folded into the ascending floorGap sweep. The scatter's geometry
 * is a <b>1-cell dilation over the 6 orthogonal neighbours</b> of every lava cell — nothing else. It must
 * therefore equal an independent per-cell GATHER of the same dilation, so this test builds each fixture
 * twice from the SAME block states:
 * <ul>
 *   <li><b>SCATTER (production)</b> — {@link NavSectionBuilder#computeFlags} (whose {@code compute} owns
 *       only the gravity term) then {@link NavSectionBuilder#computeDepth} (the scatter fold).</li>
 *   <li><b>GATHER (reference)</b> — the <i>same</i> {@code computeFlags} base, then an oracle that walks
 *       the whole column in <b>column-Y space</b> ({@code colY = section*16 + row}) and OR-s RISKY_EDIT
 *       into every cell with a lava 6-neighbour, then a scatter-free copy of the depth sweeps.</li>
 * </ul>
 * Working the oracle in column-Y — rather than through the per-section descriptor scratch the production
 * code uses — makes it independent of the scratch's upward-only overscan, which is exactly the machinery
 * the vertical seams stress.
 *
 * <p>Sharing the {@code computeFlags} base isolates the single property under test — do the scattered
 * RISKY_EDIT cells equal the gathered ones? — from every other flag/nibble. The assertion compares both
 * the packed {@link TraversalGrid#raw()} shorts and the {@link TraversalGrid#depthRaw()} bytes of every
 * section.
 *
 * <p>Beyond the identity, three semantic tests pin the ruling itself: <b>water never scatters</b> (the old
 * flowing-fluid dilation is gone, not merely re-shaped), <b>lava scatters in all six directions and only
 * those</b> (no diagonals, no ±2 rows, centre excluded), and <b>both vertical section seams are crossed</b>
 * (row 0 lava marks the section below's row 15; row 15 lava marks the section above's row 0).
 */
class FluidScatterIdentityTest {

    private static boolean bootstrapped;

    // Assigned in boot() AFTER Bootstrap — NOT as static-final field initializers, which would run at
    // class-load (before @BeforeAll) and throw when this class is the first bootstrapped test to load.
    private static BlockState AIR;
    private static BlockState STONE;
    private static BlockState WATER;
    private static BlockState LAVA;

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
    }

    /** A classified column ready for the flags+depth passes: navtypes resident, flags 0, depth UNKNOWN. */
    private record Column(NavSection[] sections, boolean[] allAir) { }

    private record Fixture(String name, Supplier<Column> builder) { }

    // ---- Fixtures ----------------------------------------------------------------------------

    private static List<Fixture> fixtures() {
        List<Fixture> f = new ArrayList<>();
        f.add(new Fixture("RICH", FluidScatterIdentityTest::buildRich));
        f.add(new Fixture("AIR_BELOW_LAVA", FluidScatterIdentityTest::buildAirBelowLava));
        return f;
    }

    /**
     * A 4-section column (world y 0..63) with a stone floor and a spread of fluid shapes:
     * water in several arrangements (none of which may scatter anything), a lone lava cell, a lava column,
     * lava on BOTH sides of a section seam (the upward and downward seam crossings), lava at the top row of
     * the last content-bearing section (scattering into an ALL-AIR section above), and lava flush against a
     * lateral section face (whose off-section offset must be dropped identically by both pipelines).
     */
    private static Column buildRich() {
        PalettedContainer<BlockState>[] secs = newColumn(4);

        // Stone floor across s0's bottom row.
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) put(secs, x, 0, z, STONE);
        }
        // WATER — must contribute NOTHING. A shallow pond over the floor (every cell was "flowing" under the
        // old model, so under the old rules this whole patch lit up), a deep column (source + non-sources),
        // and a pair straddling the s0/s1 seam.
        for (int x = 3; x <= 6; x++) {
            for (int z = 3; z <= 6; z++) put(secs, x, 1, z, WATER);
        }
        put(secs, 10, 1, 10, WATER);
        put(secs, 10, 2, 10, WATER);
        put(secs, 10, 3, 10, WATER);
        put(secs, 14, 15, 7, WATER);
        put(secs, 14, 16, 7, WATER);

        // LAVA — a lone cell (all 6 neighbours in-section) and a 3-tall column.
        put(secs, 1, 5, 12, LAVA);
        put(secs, 5, 10, 5, LAVA);
        put(secs, 5, 11, 5, LAVA);
        put(secs, 5, 12, 5, LAVA);
        // Vertical section seam, DOWNWARD: lava at s1's row 0 (world y16) marks s0's row 15 (world y15).
        put(secs, 12, 16, 5, LAVA);
        // Vertical section seam, UPWARD: lava at s0's row 15 (world y15) marks s1's row 0 (world y16).
        put(secs, 2, 15, 9, LAVA);
        // Upward seam into an ALL-AIR section: lava at s1's row 15 (world y31) marks s2's row 0 (y32).
        put(secs, 8, 31, 8, LAVA);
        // Lateral section face: the x-1 offset leaves the section and must be dropped by BOTH pipelines.
        put(secs, 0, 5, 9, LAVA);

        return classify(secs, new boolean[] { false, false, true, true });
    }

    /**
     * A 4-section column whose bottom section is ALL AIR with lava directly above it: the s1 bottom-row lava
     * scatters DOWN into the all-air s0's top row, exercising the uniform-air {@code computeFlags} fast path
     * together with the cross-seam scatter.
     */
    private static Column buildAirBelowLava() {
        PalettedContainer<BlockState>[] secs = newColumn(4);
        // s0 (world y0..15): all air.  s1: lava at its bottom row.
        put(secs, 5, 16, 5, LAVA);
        put(secs, 5, 17, 5, LAVA);
        put(secs, 6, 16, 9, WATER); // water at the same seam: still nothing
        return classify(secs, new boolean[] { true, false, true, true });
    }

    // ---- The two build pipelines -------------------------------------------------------------

    /** SCATTER (production): computeFlags per section (lava-free compute) + computeDepth (scatter fold). */
    private static void buildScatter(Column col) {
        applyFlags(col);
        NavSectionBuilder.computeDepth(col.sections());
    }

    /** GATHER (reference): the SAME computeFlags base, then the independent column-Y lava gather, then the
     *  scatter-free depth sweeps. */
    private static void buildGather(Column col) {
        applyFlags(col);
        applyLavaGather(col.sections());
        legacyDepthNibbles(col.sections());
    }

    /** Pass 2 exactly as {@link com.orebit.mod.worldmodel.pathing.ChunkNavBuilder} drives it: each section's
     *  flags with the section above as vertical overscan (null when top or all-air). Shared base for both
     *  pipelines so only the lava RISKY_EDIT differs. */
    private static void applyFlags(Column col) {
        NavSection[] s = col.sections();
        boolean[] allAir = col.allAir();
        for (int i = 0; i < s.length; i++) {
            NavSection above = i + 1 < s.length ? s[i + 1] : null;
            NavSectionBuilder.computeFlags(s[i].getTraversalGrid(), allAir[i],
                    (above == null || allAir[i + 1]) ? null : above.getTraversalGrid());
        }
    }

    /**
     * The independent oracle: OR RISKY_EDIT into every cell of the column that has a LAVA cell among its 6
     * orthogonal neighbours, working directly in column-Y space. Lateral offsets that leave the 0..15
     * section footprint resolve to "no lava" — the same air-optimism the production scatter has at a chunk
     * face (closed separately by {@code EdgeFluidScatter}, which this fixture does not exercise).
     */
    private static void applyLavaGather(NavSection[] col) {
        for (int i = 0; i < col.length; i++) {
            if (col[i] == null) continue;
            TraversalGrid grid = col[i].getTraversalGrid();
            for (int ly = 0; ly < 16; ly++) {
                int colY = (i << 4) | ly;
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (anyLavaNeighbour(col, x, colY, z)) {
                            grid.orFlags(x, ly, z, NavFlags.RISKY_EDIT);
                        }
                    }
                }
            }
        }
    }

    /** The 6-offset structuring element, spelled out (no shared table — this is meant to be independent). */
    private static boolean anyLavaNeighbour(NavSection[] col, int x, int colY, int z) {
        return lavaAt(col, x - 1, colY, z) || lavaAt(col, x + 1, colY, z)
                || lavaAt(col, x, colY - 1, z) || lavaAt(col, x, colY + 1, z)
                || lavaAt(col, x, colY, z - 1) || lavaAt(col, x, colY, z + 1);
    }

    private static boolean lavaAt(NavSection[] col, int x, int colY, int z) {
        if (x < 0 || x > 15 || z < 0 || z > 15 || colY < 0 || (colY >> 4) >= col.length) return false;
        NavSection s = col[colY >> 4];
        if (s == null) return false;
        return NavBlock.isLava(NavBlock.descriptor((short) s.getTraversalGrid().navtype(x, colY & 15, z)));
    }

    /** A verbatim copy of the scatter-free {@code computeDepth} — the two single-direction nibble sweeps.
     *  The oracle's Pass 3, so the reference grid's depth bytes come from the same logic as production's
     *  (which the scatter leaves untouched). */
    private static void legacyDepthNibbles(NavSection[] sections) {
        int[] colA = new int[256];
        int[] colB = new int[256];
        // Ascending floorGap sweep.
        boolean seeded = false;
        for (int i = 0; i < sections.length; i++) {
            NavSection s = sections[i];
            if (s == null) {
                if (seeded) break;
                continue;
            }
            TraversalGrid grid = s.getTraversalGrid();
            short[] raw = grid.raw();
            byte[] depth = grid.depthRaw();
            for (int y = 0; y < 16; y++) {
                boolean seedRow = !seeded && y == 0;
                for (int c = 0; c < 256; c++) {
                    int idx = (y << 8) | c;
                    int gap = seedRow ? TraversalGrid.DEPTH_SAT
                            : (colB[c] != 0 ? 0 : Math.min(colA[c] + 1, TraversalGrid.DEPTH_SAT));
                    depth[idx] = (byte) ((depth[idx] & 0xF0) | gap);
                    long d = NavBlock.descriptor((short) (raw[idx] & TraversalGrid.NAVTYPE_MASK));
                    colA[c] = gap;
                    colB[c] = NavBlock.isStandable(d) ? 1 : 0;
                }
                if (seedRow) seeded = true;
            }
            seeded = true;
        }
        // Descending runUp sweep.
        Arrays.fill(colB, 0, 256, -1);
        Arrays.fill(colA, 0, 256, 0);
        for (int i = sections.length - 1; i >= 0; i--) {
            NavSection s = sections[i];
            if (s == null) {
                Arrays.fill(colB, 0, 256, -1);
                continue;
            }
            TraversalGrid grid = s.getTraversalGrid();
            short[] raw = grid.raw();
            byte[] depth = grid.depthRaw();
            for (int y = 15; y >= 0; y--) {
                for (int c = 0; c < 256; c++) {
                    int idx = (y << 8) | c;
                    int nav = raw[idx] & TraversalGrid.NAVTYPE_MASK;
                    int run = nav == colB[c] ? Math.min(colA[c] + 1, TraversalGrid.DEPTH_SAT) : 0;
                    depth[idx] = (byte) ((depth[idx] & 0x0F) | (run << 4));
                    colA[c] = run;
                    colB[c] = nav;
                }
            }
        }
    }

    // ---- The identity assertion --------------------------------------------------------------

    @Test
    void scatterBuildIsByteIdenticalToTheGather() {
        for (Fixture fx : fixtures()) {
            Column scatter = fx.builder().get();
            buildScatter(scatter);
            Column gather = fx.builder().get();
            buildGather(gather);

            NavSection[] a = scatter.sections();
            NavSection[] b = gather.sections();
            for (int i = 0; i < a.length; i++) {
                assertArrayEquals(b[i].getTraversalGrid().raw(), a[i].getTraversalGrid().raw(),
                        fx.name() + " section " + i + ": packed flags/navtype shorts diverged "
                                + "(scattered RISKY_EDIT != gathered RISKY_EDIT)");
                assertArrayEquals(b[i].getTraversalGrid().depthRaw(), a[i].getTraversalGrid().depthRaw(),
                        fx.name() + " section " + i + ": depth nibble bytes diverged");
            }
        }
    }

    // ---- The semantic assertions (the owner ruling itself) -----------------------------------

    /**
     * <b>Water is inert.</b> The term used to fire on any fluid whose cell-below was dry — which a pond over
     * a floor satisfies at every cell — so this scene used to light up a 6×6 patch of RISKY_EDIT. Nothing in
     * the scene has gravity, so a single RISKY bit anywhere is a regression.
     */
    @Test
    void waterNeverScatters() {
        PalettedContainer<BlockState>[] secs = newColumn(2);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) put(secs, x, 0, z, STONE);
        }
        for (int x = 3; x <= 6; x++) {                       // a pond, every cell "flowing" under the old rule
            for (int z = 3; z <= 6; z++) put(secs, x, 1, z, WATER);
        }
        put(secs, 10, 1, 10, WATER);                          // a 3-deep column
        put(secs, 10, 2, 10, WATER);
        put(secs, 10, 3, 10, WATER);
        put(secs, 12, 15, 12, WATER);                         // straddling the section seam
        put(secs, 12, 16, 12, WATER);
        NavSection[] col = build(secs, new boolean[] { false, false });

        for (int i = 0; i < col.length; i++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        assertFalse(NavFlags.risksEdit(col[i].getFlags(x, y, z)),
                                "water must not set RISKY_EDIT — section " + i + " (" + x + "," + y + "," + z + ")");
                    }
                }
            }
        }
    }

    /**
     * <b>Lava's dilation is exactly the 6 orthogonal neighbours</b> — not 4 horizontals, not two rows down,
     * not the diagonals, and not the lava cell itself. One lava cell in open air, well away from every face.
     */
    @Test
    void lavaScattersToTheSixOrthogonalNeighboursOnly() {
        PalettedContainer<BlockState>[] secs = newColumn(2);
        put(secs, 8, 8, 8, LAVA);
        NavSection[] col = build(secs, new boolean[] { false, true });

        assertTrue(risky(col, 7, 8, 8), "x-1");
        assertTrue(risky(col, 9, 8, 8), "x+1");
        assertTrue(risky(col, 8, 7, 8), "y-1");
        assertTrue(risky(col, 8, 9, 8), "y+1");
        assertTrue(risky(col, 8, 8, 7), "z-1");
        assertTrue(risky(col, 8, 8, 9), "z+1");

        assertFalse(risky(col, 8, 8, 8), "the lava cell itself is not marked (centre excluded)");
        assertFalse(risky(col, 7, 7, 8), "diagonals are not marked");
        assertFalse(risky(col, 8, 6, 8), "two rows down is not marked (the old y-2 row is gone)");
        assertFalse(risky(col, 6, 8, 8), "two cells out is not marked");
    }

    /**
     * <b>Both vertical section seams are crossed.</b> Lava at a section's bottom row marks the section
     * BELOW's row 15; lava at a section's top row marks the section ABOVE's row 0. The second direction is
     * the one the descriptor scratch cannot express (it overscans upward only), so it is scattered through
     * the real neighbour grid — see {@code NavSectionBuilder.scatterLavaRisky}.
     */
    @Test
    void lavaCrossesBothVerticalSectionSeams() {
        PalettedContainer<BlockState>[] secs = newColumn(3);
        put(secs, 4, 16, 4, LAVA);  // s1 row 0  -> DOWN into s0 row 15 (world y15)
        put(secs, 9, 15, 9, LAVA);  // s0 row 15 -> UP   into s1 row 0  (world y16)
        NavSection[] col = build(secs, new boolean[] { false, false, true });

        assertTrue(risky(col, 4, 15, 4), "row-0 lava must mark the section BELOW's row 15");
        assertTrue(risky(col, 9, 16, 9), "row-15 lava must mark the section ABOVE's row 0");
        // ...and nothing two rows away in either direction.
        assertFalse(risky(col, 4, 14, 4), "no second row below");
        assertFalse(risky(col, 9, 17, 9), "no second row above");
    }

    // ---- Fixture plumbing --------------------------------------------------------------------

    /** RISKY_EDIT at a WORLD-y cell of the column (minY 0). */
    private static boolean risky(NavSection[] col, int x, int wy, int z) {
        return NavFlags.risksEdit(col[wy >> 4].getFlags(x, wy & 15, z));
    }

    /** The production pipeline end to end: classify, overscan flags, column depth sweep (with the scatter). */
    private static NavSection[] build(PalettedContainer<BlockState>[] secs, boolean[] allAir) {
        Column col = classify(secs, allAir);
        buildScatter(col);
        return col.sections();
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

    /** Classify every section (Pass 1) into a fresh {@link NavSection}, returning navtype-resident grids
     *  (flags 0, depth UNKNOWN) plus the per-section all-air flags the flag pass consumes. */
    private static Column classify(PalettedContainer<BlockState>[] secs, boolean[] allAir) {
        NavSection[] out = new NavSection[secs.length];
        for (int i = 0; i < secs.length; i++) {
            NavSection nav = NavSection.create(BlockPos.ZERO);
            NavSectionBuilder.classifyNavtypes(secs[i], allAir[i], nav.getTraversalGrid(), null);
            out[i] = nav;
        }
        return new Column(out, allAir);
    }
}
