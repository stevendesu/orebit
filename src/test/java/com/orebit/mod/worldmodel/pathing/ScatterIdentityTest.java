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
 * <b>Identity + semantics proof for the HAS_FLUID_NEIGHBOR SCATTER</b> (PERF-DESIGN-navgrid-build §C1;
 * DESIGN-fluid-flow-prediction.md §4, §9's "Flag" bullets — see {@link NavFlags}'s HAS_FLUID_NEIGHBOR
 * section. The bit was born 2026-08-10 as RISKY_EDIT's lava-only keep-away term; the 2026-08-17 migration
 * widened the predicate to ANY fluid — water and lava alike — and moved it to bit 4, leaving bit 0
 * strictly gravity; the 2026-08-21 reframe then renamed bit 0 to RISKS_GRAVITY and gave it a scatter of
 * its own — see below).
 *
 * <p>The chunk-column build SCATTERS <b>two</b> terms in {@link NavSectionBuilder#computeDepth}, folded
 * into the ascending floorGap sweep behind one union mask test: HAS_FLUID_NEIGHBOR from each fluid cell,
 * and {@code RISKS_GRAVITY}'s <b>half B</b> from each UNSUPPORTED gravity cell (owner ruling 2026-08-21 —
 * see {@link NavFlags}'s "RISKS_GRAVITY: two halves, two owners"). Each scatter's geometry is a <b>1-cell
 * dilation over the 6 orthogonal neighbours</b> of its source cell — nothing else. Both must therefore
 * equal an independent per-cell GATHER of the same dilation, so this test builds each fixture twice from
 * the SAME block states:
 * <ul>
 *   <li><b>SCATTER (production)</b> — {@link NavSectionBuilder#computeFlags} (whose {@code compute} owns
 *       bit 0's half A and never writes bit 4 or half B) then
 *       {@link NavSectionBuilder#computeDepth} (the scatter fold).</li>
 *   <li><b>GATHER (reference)</b> — the <i>same</i> {@code computeFlags} base, then oracles that walk
 *       the whole column in <b>column-Y space</b> ({@code colY = section*16 + row}) and OR each term into
 *       every cell whose 6-neighbourhood supplies it, then a scatter-free copy of the depth sweeps.</li>
 * </ul>
 *
 * <p><b>The gravity oracle is load-bearing, not decorative:</b> {@code legacyDepthNibbles} is a
 * scatter-FREE copy of {@code computeDepth}, so without {@code applyGravityGather} the reference pipeline
 * would silently lose half B the moment any fixture gained a gravity block — and the identity assertion
 * would break for the wrong reason.
 * Working the oracle in column-Y — rather than through the per-section descriptor scratch the production
 * code uses — makes it independent of the scratch's upward-only overscan, which is exactly the machinery
 * the vertical seams stress.
 *
 * <p>Sharing the {@code computeFlags} base isolates the single property under test — do the scattered
 * HAS_FLUID_NEIGHBOR cells equal the gathered ones? — from every other flag/nibble. The assertion compares
 * both the packed {@link TraversalGrid#raw()} shorts and the {@link TraversalGrid#depthRaw()} bytes of
 * every section, so the identity covers the NEW bit and everything around it at once.
 *
 * <p>Beyond the identity, the semantic tests pin the model itself: <b>water never sets bit 0</b>
 * (RISKS_GRAVITY is gravity only — the migration moved the fluid fact, it did not resurrect a water term
 * on bit 0), <b>water and lava each dilate bit 4 in all six directions and only those</b> (no diagonals,
 * no ±2 rows, centre excluded — and none of it touches bit 0), and <b>both vertical section seams are
 * crossed by both fluids</b> (row 0 fluid marks the section below's row 15; row 15 fluid marks the
 * section above's row 0).
 */
class ScatterIdentityTest {

    private static boolean bootstrapped;

    // Assigned in boot() AFTER Bootstrap — NOT as static-final field initializers, which would run at
    // class-load (before @BeforeAll) and throw when this class is the first bootstrapped test to load.
    private static BlockState AIR;
    private static BlockState STONE;
    private static BlockState WATER;
    private static BlockState LAVA;
    private static BlockState SAND;
    private static BlockState GRAVEL;

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
        GRAVEL = Blocks.GRAVEL.defaultBlockState();
    }

    /** A classified column ready for the flags+depth passes: navtypes resident, flags 0, depth UNKNOWN. */
    private record Column(NavSection[] sections, boolean[] allAir) { }

    private record Fixture(String name, Supplier<Column> builder) { }

    // ---- Fixtures ----------------------------------------------------------------------------

    private static List<Fixture> fixtures() {
        List<Fixture> f = new ArrayList<>();
        f.add(new Fixture("RICH", ScatterIdentityTest::buildRich));
        f.add(new Fixture("AIR_BELOW_LAVA", ScatterIdentityTest::buildAirBelowLava));
        return f;
    }

    /**
     * A 4-section column (world y 0..63) with a stone floor and a spread of fluid shapes — under the
     * any-fluid model BOTH fluids contribute to the dilation, so every shape here now exercises the
     * scatter: a water pond / column / seam-straddling pair, a lone lava cell, a lava column, lava on BOTH
     * sides of a section seam (the upward and downward seam crossings), lava at the top row of the last
     * content-bearing section (scattering into an ALL-AIR section above), and water + lava each flush
     * against a lateral section face (whose off-section offset must be dropped identically by both
     * pipelines).
     */
    private static Column buildRich() {
        PalettedContainer<BlockState>[] secs = newColumn(4);

        // Stone floor across s0's bottom row.
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) put(secs, x, 0, z, STONE);
        }
        // WATER — a live contributor since the any-fluid widening (2026-08-17): a shallow pond over the
        // floor, a deep column (source + non-sources), and a pair straddling the s0/s1 seam (both water
        // seam crossings).
        for (int x = 3; x <= 6; x++) {
            for (int z = 3; z <= 6; z++) put(secs, x, 1, z, WATER);
        }
        put(secs, 10, 1, 10, WATER);
        put(secs, 10, 2, 10, WATER);
        put(secs, 10, 3, 10, WATER);
        put(secs, 14, 15, 7, WATER);
        put(secs, 14, 16, 7, WATER);
        // Lateral section face, water: the x+1 offset leaves the section and must be dropped identically.
        put(secs, 15, 3, 6, WATER);

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
        // Lateral section face, lava: the x-1 offset leaves the section and must be dropped by BOTH pipelines.
        put(secs, 0, 5, 9, LAVA);

        // GRAVITY (RISKS_GRAVITY half B — the second scatter, 2026-08-21). The same four shape classes the
        // fluid cells cover, so the identity stresses the gravity dilation exactly as hard:
        //   * a SUPPORTED column (stone under sand): contributes NO half B at all — the discriminator that
        //     keeps the dilation from degenerating into "near any gravity block";
        put(secs, 7, 4, 2, STONE);
        put(secs, 7, 5, 2, SAND);
        //   * an UNSUPPORTED (suspended) column in open air: dilates in all six directions;
        put(secs, 11, 7, 13, SAND);
        put(secs, 11, 8, 13, GRAVEL);
        //   * seam-straddling pairs in BOTH directions — a suspended sand at s1's row 0 (marks s0's row 15
        //     downward) and one at s0's row 15 (marks s1's row 0 upward);
        put(secs, 4, 16, 12, SAND);
        put(secs, 6, 15, 14, GRAVEL);
        //   * lateral-face cells whose off-section offset must be dropped identically by both pipelines.
        put(secs, 0, 9, 4, SAND);
        put(secs, 15, 9, 11, GRAVEL);

        return classify(secs, new boolean[] { false, false, true, true });
    }

    /**
     * A 4-section column whose bottom section is ALL AIR with fluid directly above it: the s1 bottom-row
     * fluid scatters DOWN into the all-air s0's top row, exercising the uniform-air {@code computeFlags}
     * fast path together with the cross-seam scatter — for lava AND (since the any-fluid widening) water.
     */
    private static Column buildAirBelowLava() {
        PalettedContainer<BlockState>[] secs = newColumn(4);
        // s0 (world y0..15): all air.  s1: lava at its bottom row.
        put(secs, 5, 16, 5, LAVA);
        put(secs, 5, 17, 5, LAVA);
        put(secs, 6, 16, 9, WATER); // water at the same seam: folds down identically (any-fluid)
        return classify(secs, new boolean[] { true, false, true, true });
    }

    // ---- The two build pipelines -------------------------------------------------------------

    /** SCATTER (production): computeFlags per section (bit-4-free compute) + computeDepth (scatter fold). */
    private static void buildScatter(Column col) {
        applyFlags(col);
        NavSectionBuilder.computeDepth(col.sections());
    }

    /** GATHER (reference): the SAME computeFlags base, then the independent column-Y any-fluid gather, then
     *  the scatter-free depth sweeps. */
    private static void buildGather(Column col) {
        applyFlags(col);
        applyFluidGather(col.sections());
        applyGravityGather(col.sections());
        legacyDepthNibbles(col.sections());
    }

    /** Pass 2 exactly as {@link com.orebit.mod.worldmodel.pathing.ChunkNavBuilder} drives it: each section's
     *  flags with the section above as vertical overscan (null when top or all-air). Shared base for both
     *  pipelines so only the HAS_FLUID_NEIGHBOR term differs. */
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
     * The independent oracle: OR HAS_FLUID_NEIGHBOR into every cell of the column that has ANY fluid cell
     * (water or lava — {@code NavBlock.isFluid}) among its 6 orthogonal neighbours, working directly in
     * column-Y space. Lateral offsets that leave the 0..15 section footprint resolve to "no fluid" — the
     * same air-optimism the production scatter has at a chunk face (closed separately by
     * {@code EdgeScatter}, which this fixture does not exercise).
     */
    private static void applyFluidGather(NavSection[] col) {
        for (int i = 0; i < col.length; i++) {
            if (col[i] == null) continue;
            TraversalGrid grid = col[i].getTraversalGrid();
            for (int ly = 0; ly < 16; ly++) {
                int colY = (i << 4) | ly;
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (anyFluidNeighbour(col, x, colY, z)) {
                            grid.orFlags(x, ly, z, NavFlags.HAS_FLUID_NEIGHBOR);
                        }
                    }
                }
            }
        }
    }

    /**
     * The gravity oracle: OR {@code RISKS_GRAVITY} into every cell of the column with an UNSUPPORTED gravity
     * cell among its 6 orthogonal neighbours — half B, in column-Y space, deliberately NOT sharing
     * {@code NavFlags.SIX} (the same independence discipline as the fluid oracle above). Half A is
     * {@code compute}'s and already sits in the shared {@code applyFlags} base, so this adds only the
     * scattered half. Off-section lateral offsets resolve to "no gravity" — the production scatter's
     * chunk-face air-optimism, closed separately by {@code EdgeScatter}.
     */
    private static void applyGravityGather(NavSection[] col) {
        for (int i = 0; i < col.length; i++) {
            if (col[i] == null) continue;
            TraversalGrid grid = col[i].getTraversalGrid();
            for (int ly = 0; ly < 16; ly++) {
                int colY = (i << 4) | ly;
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (anyUnsupportedGravityNeighbour(col, x, colY, z)) {
                            grid.orFlags(x, ly, z, NavFlags.RISKS_GRAVITY);
                        }
                    }
                }
            }
        }
    }

    /** The 6-offset structuring element for half B, spelled out (independent of the production table). */
    private static boolean anyUnsupportedGravityNeighbour(NavSection[] col, int x, int colY, int z) {
        return unsupportedGravityAt(col, x - 1, colY, z) || unsupportedGravityAt(col, x + 1, colY, z)
                || unsupportedGravityAt(col, x, colY - 1, z) || unsupportedGravityAt(col, x, colY + 1, z)
                || unsupportedGravityAt(col, x, colY, z - 1) || unsupportedGravityAt(col, x, colY, z + 1);
    }

    /** A gravity block at {@code (x,colY,z)} with nothing solid directly beneath it (air below the built
     *  column reads passable — the production scatter's own air-optimism at the world floor). */
    private static boolean unsupportedGravityAt(NavSection[] col, int x, int colY, int z) {
        return NavBlock.hasGravity(descOf(col, x, colY, z))
                && NavBlock.isPassable(descOf(col, x, colY - 1, z));
    }

    /** The packed descriptor at a column-Y cell, or AIR when out of the column / off the section laterally. */
    private static long descOf(NavSection[] col, int x, int colY, int z) {
        if (x < 0 || x > 15 || z < 0 || z > 15 || colY < 0 || (colY >> 4) >= col.length) {
            return NavBlock.descriptor(NavBlock.AIR);
        }
        NavSection s = col[colY >> 4];
        if (s == null) return NavBlock.descriptor(NavBlock.AIR);
        return NavBlock.descriptor((short) s.getTraversalGrid().navtype(x, colY & 15, z));
    }

    /** The 6-offset structuring element, spelled out (no shared table — this is meant to be independent). */
    private static boolean anyFluidNeighbour(NavSection[] col, int x, int colY, int z) {
        return fluidAt(col, x - 1, colY, z) || fluidAt(col, x + 1, colY, z)
                || fluidAt(col, x, colY - 1, z) || fluidAt(col, x, colY + 1, z)
                || fluidAt(col, x, colY, z - 1) || fluidAt(col, x, colY, z + 1);
    }

    private static boolean fluidAt(NavSection[] col, int x, int colY, int z) {
        if (x < 0 || x > 15 || z < 0 || z > 15 || colY < 0 || (colY >> 4) >= col.length) return false;
        NavSection s = col[colY >> 4];
        if (s == null) return false;
        return NavBlock.isFluid(NavBlock.descriptor((short) s.getTraversalGrid().navtype(x, colY & 15, z)));
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
                                + "(a scattered term != its gather: HAS_FLUID_NEIGHBOR or RISKS_GRAVITY)");
                assertArrayEquals(b[i].getTraversalGrid().depthRaw(), a[i].getTraversalGrid().depthRaw(),
                        fx.name() + " section " + i + ": depth nibble bytes diverged");
            }
        }
    }

    // ---- The semantic assertions (the model itself) ------------------------------------------

    /**
     * <b>Water never touches bit 0 — and now dilates HAS_FLUID_NEIGHBOR.</b> Bit 0 is RISKS_GRAVITY, gravity
     * only (DESIGN-fluid-flow-prediction.md §4.1): nothing in the scene has gravity, so a single bit-0
     * anywhere is a regression — the any-fluid widening moved the fluid fact to bit 4, it did not
     * resurrect a water term on bit 0. Bit 4, meanwhile, IS set around every water shape (the pond, the
     * column, and across the section seam) — water is no longer inert for the fluid bit.
     */
    @Test
    void waterSetsFluidNeighborButNeverBitZero() {
        PalettedContainer<BlockState>[] secs = newColumn(2);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) put(secs, x, 0, z, STONE);
        }
        for (int x = 3; x <= 6; x++) {                       // a pond over the floor
            for (int z = 3; z <= 6; z++) put(secs, x, 1, z, WATER);
        }
        put(secs, 10, 1, 10, WATER);                          // a 3-deep column
        put(secs, 10, 2, 10, WATER);
        put(secs, 10, 3, 10, WATER);
        put(secs, 12, 15, 12, WATER);                         // straddling the section seam
        put(secs, 12, 16, 12, WATER);
        NavSection[] col = build(secs, new boolean[] { false, false });

        // Bit 0: water is NEVER a bit-0 contributor, anywhere in the grid (RISKS_GRAVITY is gravity only).
        for (int i = 0; i < col.length; i++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        assertFalse(NavFlags.risksGravity(col[i].getFlags(x, y, z)),
                                "water must not set bit 0 — section " + i + " (" + x + "," + y + "," + z + ")");
                    }
                }
            }
        }

        // Bit 4: the same shapes DO dilate HAS_FLUID_NEIGHBOR (any-fluid, 2026-08-17).
        assertTrue(fluidNeighbor(col, 2, 1, 3), "pond edge marks its lateral neighbour");
        assertTrue(fluidNeighbor(col, 3, 2, 3), "pond marks the cell above");
        assertTrue(fluidNeighbor(col, 10, 4, 10), "column top marks the cell above");
        assertTrue(fluidNeighbor(col, 11, 2, 10), "column marks its lateral neighbour");
        assertTrue(fluidNeighbor(col, 12, 14, 12), "seam pair marks below");
        assertTrue(fluidNeighbor(col, 12, 17, 12), "seam pair marks above");
        assertFalse(fluidNeighbor(col, 1, 1, 3), "two cells out from the pond stays clear");
        assertFalse(fluidNeighbor(col, 11, 4, 10), "diagonals stay clear");
        assertFalse(fluidNeighbor(col, 8, 8, 8), "far from any fluid stays clear");
    }

    /**
     * <b>Lava's dilation is exactly the 6 orthogonal neighbours</b> — not 4 horizontals, not two rows down,
     * not the diagonals, and not the lava cell itself. One lava cell in open air, well away from every
     * face. And <b>none of it lands on bit 0</b>: adjacent lava no longer sets RISKS_GRAVITY anywhere — the
     * old keep-away term migrated to the priced HAS_FLUID_NEIGHBOR model
     * (DESIGN-fluid-flow-prediction.md §4.1/§4.2).
     */
    @Test
    void lavaScattersToTheSixOrthogonalNeighboursOnly() {
        PalettedContainer<BlockState>[] secs = newColumn(2);
        put(secs, 8, 8, 8, LAVA);
        NavSection[] col = build(secs, new boolean[] { false, true });

        assertTrue(fluidNeighbor(col, 7, 8, 8), "x-1");
        assertTrue(fluidNeighbor(col, 9, 8, 8), "x+1");
        assertTrue(fluidNeighbor(col, 8, 7, 8), "y-1");
        assertTrue(fluidNeighbor(col, 8, 9, 8), "y+1");
        assertTrue(fluidNeighbor(col, 8, 8, 7), "z-1");
        assertTrue(fluidNeighbor(col, 8, 8, 9), "z+1");

        assertFalse(fluidNeighbor(col, 8, 8, 8), "the lava cell itself is not marked (centre excluded)");
        assertFalse(fluidNeighbor(col, 7, 7, 8), "diagonals are not marked");
        assertFalse(fluidNeighbor(col, 8, 6, 8), "two rows down is not marked (the old y-2 row is gone)");
        assertFalse(fluidNeighbor(col, 6, 8, 8), "two cells out is not marked");

        // Bit 0 stays clear at the lava's neighbours AND the lava cell — strictly gravity, no fluid term.
        assertFalse(risky(col, 7, 8, 8), "adjacent lava never sets bit 0 (gravity only)");
        assertFalse(risky(col, 8, 9, 8), "adjacent lava never sets bit 0 above either");
        assertFalse(risky(col, 8, 8, 8), "the lava cell itself carries no bit 0");
    }

    /**
     * <b>Water's dilation is identical</b> — the widened predicate is {@code NavBlock.isFluid}, so a lone
     * water cell marks exactly the same six neighbours a lava cell does, and nothing else.
     */
    @Test
    void waterScattersToTheSixOrthogonalNeighboursOnly() {
        PalettedContainer<BlockState>[] secs = newColumn(2);
        put(secs, 8, 8, 8, WATER);
        NavSection[] col = build(secs, new boolean[] { false, true });

        assertTrue(fluidNeighbor(col, 7, 8, 8), "x-1");
        assertTrue(fluidNeighbor(col, 9, 8, 8), "x+1");
        assertTrue(fluidNeighbor(col, 8, 7, 8), "y-1");
        assertTrue(fluidNeighbor(col, 8, 9, 8), "y+1");
        assertTrue(fluidNeighbor(col, 8, 8, 7), "z-1");
        assertTrue(fluidNeighbor(col, 8, 8, 9), "z+1");

        assertFalse(fluidNeighbor(col, 8, 8, 8), "the water cell itself is not marked (centre excluded)");
        assertFalse(fluidNeighbor(col, 7, 7, 8), "diagonals are not marked");
        assertFalse(fluidNeighbor(col, 6, 8, 8), "two cells out is not marked");
    }

    /**
     * <b>Both vertical section seams are crossed, by both fluids.</b> Fluid at a section's bottom row marks
     * the section BELOW's row 15; fluid at a section's top row marks the section ABOVE's row 0. The second
     * direction is the one the descriptor scratch cannot express (it overscans upward only), so it is
     * scattered through the real neighbour grid — see {@code NavSectionBuilder.scatterFluidNeighbor}.
     */
    @Test
    void fluidCrossesBothVerticalSectionSeams() {
        PalettedContainer<BlockState>[] secs = newColumn(3);
        put(secs, 4, 16, 4, LAVA);    // s1 row 0  -> DOWN into s0 row 15 (world y15)
        put(secs, 9, 15, 9, LAVA);    // s0 row 15 -> UP   into s1 row 0  (world y16)
        put(secs, 12, 16, 12, WATER); // water crosses DOWN identically
        put(secs, 3, 15, 3, WATER);   // water crosses UP identically
        NavSection[] col = build(secs, new boolean[] { false, false, true });

        assertTrue(fluidNeighbor(col, 4, 15, 4), "row-0 lava must mark the section BELOW's row 15");
        assertTrue(fluidNeighbor(col, 9, 16, 9), "row-15 lava must mark the section ABOVE's row 0");
        assertTrue(fluidNeighbor(col, 12, 15, 12), "row-0 water must mark the section BELOW's row 15");
        assertTrue(fluidNeighbor(col, 3, 16, 3), "row-15 water must mark the section ABOVE's row 0");
        // ...and nothing two rows away in either direction.
        assertFalse(fluidNeighbor(col, 4, 14, 4), "no second row below (lava)");
        assertFalse(fluidNeighbor(col, 9, 17, 9), "no second row above (lava)");
        assertFalse(fluidNeighbor(col, 12, 14, 12), "no second row below (water)");
        assertFalse(fluidNeighbor(col, 3, 17, 3), "no second row above (water)");
    }

    // ---- Fixture plumbing --------------------------------------------------------------------

    /** RISKS_GRAVITY at a WORLD-y cell of the column (minY 0). */
    private static boolean risky(NavSection[] col, int x, int wy, int z) {
        return NavFlags.risksGravity(col[wy >> 4].getFlags(x, wy & 15, z));
    }

    /** HAS_FLUID_NEIGHBOR at a WORLD-y cell of the column (minY 0). */
    private static boolean fluidNeighbor(NavSection[] col, int x, int wy, int z) {
        return NavFlags.hasFluidNeighbor(col[wy >> 4].getFlags(x, wy & 15, z));
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
