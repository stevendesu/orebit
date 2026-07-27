package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

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
 * <b>Bit-identity proof for the flowing-fluid RISKY_EDIT SCATTER</b> (PERF-DESIGN-navgrid-build §C1). The
 * chunk-column build used to GATHER RISKY_EDIT's fluid term per cell ({@link NavFlags#risksFluidFlow} at
 * {@code y+1}/{@code y+2} inside {@link NavFlags#compute}); it now SCATTERS it from each flowing source in
 * {@link NavSectionBuilder#computeDepth}, folded into the ascending floorGap sweep. Both express the same
 * dilation of the flowing-fluid set, so the produced grids must be <b>byte-for-byte identical</b>.
 *
 * <p>Each fixture is a multi-section chunk column classified two ways from the SAME block states:
 * <ul>
 *   <li><b>SCATTER (production)</b> — {@link NavSectionBuilder#computeFlags} (whose {@code compute} no
 *       longer gathers fluid) then {@link NavSectionBuilder#computeDepth} (the scatter fold).</li>
 *   <li><b>GATHER (reference)</b> — the <i>same</i> {@code computeFlags} base, then the retained
 *       {@code risksFluidFlow} gather OR-ed in per cell over the identical vertical-overscan scratch
 *       {@code computeFlags} builds, then a scatter-free copy of the depth sweeps.</li>
 * </ul>
 * Sharing the {@code computeFlags} base isolates the single property under test — do the scattered
 * RISKY_EDIT cells equal the gathered ones? — from every other flag/nibble. The assertion compares both
 * the packed {@link TraversalGrid#raw()} shorts and the {@link TraversalGrid#depthRaw()} bytes of every
 * section.
 *
 * <p>The fixtures deliberately exercise: a flowing source next to floor cells (RISKY set), a
 * fluid-over-fluid non-source (RISKY not set), a flowing source at a section's BOTTOM row that must
 * scatter DOWN across the seam (matching the old gather's read of the section-above overscan), a
 * fluid-over-fluid pair straddling a section seam (the reset-vs-continuous-carry discriminator: the
 * continuous carry sees the real cell below across the seam, exactly as the gather did — so NO seam reset
 * is bit-identical), lava sources, and a flowing source scattering into an all-air section below it
 * (the uniform-air {@code computeFlags} fast path).
 */
class FluidScatterIdentityTest {

    private static boolean bootstrapped;

    // Assigned in boot() AFTER Bootstrap — NOT as static-final field initializers, which would run at
    // class-load (before @BeforeAll) and throw when this class is the first bootstrapped test to load.
    private static BlockState AIR;
    private static BlockState STONE;
    private static BlockState WATER;
    private static BlockState LAVA;
    private static long AIR_DESC;

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
        AIR_DESC = NavBlock.descriptor(NavBlock.AIR);
    }

    /** A classified column ready for the flags+depth passes: navtypes resident, flags 0, depth UNKNOWN. */
    private record Column(NavSection[] sections, boolean[] allAir) { }

    private record Fixture(String name, Supplier<Column> builder) { }

    // ---- Fixtures ----------------------------------------------------------------------------

    private static List<Fixture> fixtures() {
        List<Fixture> f = new ArrayList<>();
        f.add(new Fixture("RICH", FluidScatterIdentityTest::buildRich));
        f.add(new Fixture("AIR_BELOW_FLUID", FluidScatterIdentityTest::buildAirBelowFluid));
        return f;
    }

    /**
     * A 4-section column (world y 0..63) with a stone floor and a spread of fluid shapes:
     * a shallow water pond (flowing over the floor), a deep water column (source at the base,
     * non-sources above it), lava sources, a flowing source straddling the s0/s1 seam from below,
     * and a fluid-over-fluid pair straddling the s0/s1 seam (the carry-vs-reset discriminator).
     */
    private static Column buildRich() {
        PalettedContainer<BlockState>[] secs = newColumn(4);

        // Stone floor across s0's bottom row.
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) put(secs, x, 0, z, STONE);
        }
        // A: shallow water pond one above the floor — every cell water-over-stone ⇒ flowing; the floor
        //    cells around/under it must pick up RISKY_EDIT.
        for (int x = 3; x <= 6; x++) {
            for (int z = 3; z <= 6; z++) put(secs, x, 1, z, WATER);
        }
        // B: deep water column — y1 flowing (over stone), y2/y3 fluid-over-fluid NON-sources.
        put(secs, 10, 1, 10, WATER);
        put(secs, 10, 2, 10, WATER);
        put(secs, 10, 3, 10, WATER);
        // E: lava — a lone source and a 3-deep column (source at base, non-sources above).
        put(secs, 1, 1, 12, LAVA);
        put(secs, 1, 1, 13, LAVA);
        put(secs, 1, 2, 13, LAVA);
        put(secs, 1, 3, 13, LAVA);
        // C: flowing source AT a section's bottom row (world y16 = s1 row 0) over air (world y15 = s0 top).
        //    Must scatter DOWN across the seam into s0 rows 15 and 14.
        put(secs, 12, 16, 5, WATER);
        put(secs, 12, 17, 5, WATER); // non-source above it (fluid-over-fluid)
        // D: fluid-over-fluid straddling the seam — water at world y15 (s0 top) AND world y16 (s1 bottom),
        //    with air below the pair. The y15 cell is a flowing source (over air y14); the y16 cell is NOT
        //    (real fluid below it, across the seam). A seam RESET would wrongly flag y16 flowing — this is
        //    the discriminator that a CONTINUOUS carry (no reset) is the bit-identical choice.
        put(secs, 14, 15, 7, WATER);
        put(secs, 14, 16, 7, WATER);

        return classify(secs, new boolean[] { false, false, true, true });
    }

    /**
     * A 4-section column whose bottom section is ALL AIR with a fluid section directly above it: the s1
     * bottom-row source scatters DOWN into the all-air s0's top rows, exercising the uniform-air
     * {@code computeFlags} fast path together with the cross-seam scatter.
     */
    private static Column buildAirBelowFluid() {
        PalettedContainer<BlockState>[] secs = newColumn(4);
        // s0 (world y0..15): all air.  s1: a flowing water source at its bottom rows.
        put(secs, 5, 16, 5, WATER);          // world y16 = s1 row 0, over air (s0 top) ⇒ flowing
        put(secs, 5, 17, 5, WATER);          // non-source above it
        put(secs, 6, 16, 9, LAVA);           // a second flowing source (lava) at the seam
        return classify(secs, new boolean[] { true, false, true, true });
    }

    // ---- The two build pipelines -------------------------------------------------------------

    /** SCATTER (production): computeFlags per section (fluid-free compute) + computeDepth (scatter fold). */
    private static void buildScatter(Column col) {
        applyFlags(col);
        NavSectionBuilder.computeDepth(col.sections());
    }

    /** GATHER (reference): the SAME computeFlags base, then the retained per-cell fluid gather OR-ed in,
     *  then the scatter-free depth sweeps. This reconstructs the exact pre-C1 grid. */
    private static void buildGather(Column col) {
        applyFlags(col);
        applyFluidGather(col);
        legacyDepthNibbles(col.sections());
    }

    /** Pass 2 exactly as {@link com.orebit.mod.worldmodel.pathing.ChunkNavBuilder} drives it: each section's
     *  flags with the section above as vertical overscan (null when top or all-air). Shared base for both
     *  pipelines so only the fluid RISKY_EDIT differs. */
    private static void applyFlags(Column col) {
        NavSection[] s = col.sections();
        boolean[] allAir = col.allAir();
        for (int i = 0; i < s.length; i++) {
            NavSection above = i + 1 < s.length ? s[i + 1] : null;
            NavSectionBuilder.computeFlags(s[i].getTraversalGrid(), allAir[i],
                    (above == null || allAir[i + 1]) ? null : above.getTraversalGrid());
        }
    }

    /** The retained-gather reference: OR RISKY_EDIT into each floor cell for which the pre-C1
     *  {@code risksFluidFlow(y+1) || risksFluidFlow(y+2)} holds, over the identical overscan scratch
     *  {@code computeFlags} used. */
    private static void applyFluidGather(Column col) {
        NavSection[] s = col.sections();
        boolean[] allAir = col.allAir();
        long[] desc = new long[NavFlags.SCRATCH_SIZE];
        for (int i = 0; i < s.length; i++) {
            TraversalGrid grid = s[i].getTraversalGrid();
            NavSection above = i + 1 < s.length ? s[i + 1] : null;
            TraversalGrid aboveGrid = (above == null || allAir[i + 1]) ? null : above.getTraversalGrid();
            fillScratch(desc, grid, aboveGrid);
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (NavFlags.risksFluidFlow(desc, x, y + 1, z)
                                || NavFlags.risksFluidFlow(desc, x, y + 2, z)) {
                            grid.orFlags(x, y, z, NavFlags.RISKY_EDIT);
                        }
                    }
                }
            }
        }
    }

    /** Replica of {@link NavSectionBuilder}'s fillScratch/fillOverscan: navtypes → descriptors + the
     *  section-above's bottom {@link NavFlags#OVERSCAN_ROWS} rows as overscan (air when {@code above} null). */
    private static void fillScratch(long[] desc, TraversalGrid grid, TraversalGrid above) {
        short[] raw = grid.raw();
        for (int i = 0; i < 4096; i++) {
            desc[i] = NavBlock.descriptor((short) (raw[i] & TraversalGrid.NAVTYPE_MASK));
        }
        if (above == null) {
            Arrays.fill(desc, 4096, NavFlags.SCRATCH_SIZE, AIR_DESC);
        } else {
            short[] araw = above.raw();
            for (int i = 0; i < NavFlags.OVERSCAN_ROWS * 256; i++) {
                desc[4096 + i] = NavBlock.descriptor((short) (araw[i] & TraversalGrid.NAVTYPE_MASK));
            }
        }
    }

    /** A verbatim copy of the PRE-scatter {@code computeDepth} — the two single-direction nibble sweeps,
     *  with NO fluid scatter. The oracle's Pass 3, so the reference grid's depth bytes come from the same
     *  logic as production's (which the scatter left untouched). */
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

    // ---- The assertion -----------------------------------------------------------------------

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

    // ---- Fixture plumbing --------------------------------------------------------------------

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
