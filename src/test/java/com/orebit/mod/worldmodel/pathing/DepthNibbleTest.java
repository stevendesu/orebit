package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;

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
 * Correctness harness for the E3/E4 depth nibbles (PERF-DESIGN-navgrid-widening.md §2/§6,
 * PERF-DESIGN-runup-nibble.md §2/§4): every cell's stored {@code floorGap} / {@code runUp} must equal a
 * brute-force scan over the column's resident navtypes — after the column build, after targeted
 * {@code patchCell} mutations (placing/removing floors mid-column, at both vertical-seam rows), and after
 * a random patch storm. The fixture deliberately includes a FENCE floor (collision but NOT standable — the
 * floorGap predicate is standability, not collision), water, a floor straddling a section seam, and an
 * air span longer than the 14-cell window (the saturation case).
 */
class DepthNibbleTest {

    private static boolean bootstrapped;

    @BeforeAll
    static void boot() {
        if (!bootstrapped) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            bootstrapped = true;
        }
    }

    private static final int SECTIONS = 4; // y 0..63

    /**
     * Build the fixture column (column-form: navtypes → overscan flags → depth sweep). Strata (uniform across x,z except the named feature columns):
     * bedrock y0, stone y1..8, air y9..30 (a >14 window — saturation), stone floor y31 (straddling reads
     * across the y=31/32 seam), air above; feature column (5,5): an oak FENCE at y=20 (collision,
     * non-standable) and water at y=40; feature column (10,3): stone at y=15 and y=16 (a floor pair
     * exactly on the seam).
     */
    private NavSection[] buildColumn() {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState bedrock = Blocks.BEDROCK.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState fence = Blocks.OAK_FENCE.defaultBlockState();
        BlockState water = Blocks.WATER.defaultBlockState();

        NavSection[] sections = new NavSection[SECTIONS];
        boolean[] allAir = new boolean[SECTIONS];
        for (int i = 0; i < SECTIONS; i++) {
            PalettedContainer<BlockState> states = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
            boolean any = false;
            for (int ly = 0; ly < 16; ly++) {
                int y = i * 16 + ly;
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        BlockState s = air;
                        if (y == 0) s = bedrock;
                        else if (y >= 1 && y <= 8) s = stone;
                        else if (y == 31) s = stone;
                        if (x == 5 && z == 5) {
                            if (y == 20) s = fence;
                            else if (y == 40) s = water;
                        }
                        if (x == 10 && z == 3 && (y == 15 || y == 16)) s = stone;
                        if (s != air) {
                            states.set(x, ly, z, s);
                            any = true;
                        }
                    }
                }
            }
            sections[i] = NavSection.create(BlockPos.ZERO);
            allAir[i] = NavSectionBuilder.classifyNavtypes(states, !any, sections[i].getTraversalGrid(), null);
        }
        for (int i = 0; i < SECTIONS; i++) {
            NavSection above = i + 1 < SECTIONS ? sections[i + 1] : null;
            NavSectionBuilder.computeFlags(sections[i].getTraversalGrid(), allAir[i],
                    (above == null || allAir[i + 1]) ? null : above.getTraversalGrid());
        }
        NavSectionBuilder.computeDepth(sections);
        return sections;
    }

    /** {@link #buildColumn()} WITHOUT the depth sweep — the single-section-producer regime (all UNKNOWN). */
    private NavSection[] buildColumnNoDepth() {
        NavSection[] sections = buildColumn();
        for (NavSection s : sections) {
            java.util.Arrays.fill(s.getTraversalGrid().depthRaw(), TraversalGrid.DEPTH_UNKNOWN_BYTE);
        }
        return sections;
    }

    // ---- Brute-force references (the §2 definitions, straight off the resident navtypes) ------------

    private static boolean standable(NavSection[] col, int x, int y, int z) {
        return NavBlock.isStandable(NavBlock.descriptor(
                (short) col[y >> 4].getTraversalGrid().navtype(x, y & 15, z)));
    }

    private static int navtype(NavSection[] col, int x, int y, int z) {
        return col[y >> 4].getTraversalGrid().navtype(x, y & 15, z);
    }

    private static int bruteFloorGap(NavSection[] col, int x, int y, int z) {
        int gap = 0;
        for (int wy = y - 1; wy >= 0; wy--) {
            if (gap >= TraversalGrid.DEPTH_SAT) return TraversalGrid.DEPTH_SAT;
            if (standable(col, x, wy, z)) return gap;
            gap++;
        }
        return Math.min(gap + TraversalGrid.DEPTH_SAT, TraversalGrid.DEPTH_SAT); // nothing below minY is standable
    }

    private static int bruteRunUp(NavSection[] col, int x, int y, int z) {
        int nav = navtype(col, x, y, z);
        int run = 0;
        int top = SECTIONS * 16 - 1;
        for (int wy = y + 1; wy <= top && run < TraversalGrid.DEPTH_SAT; wy++) {
            if (navtype(col, x, wy, z) != nav) break;
            run++;
        }
        return run;
    }

    private void assertColumnExact(NavSection[] col, String when) {
        for (int y = 0; y < SECTIONS * 16; y++) {
            TraversalGrid g = col[y >> 4].getTraversalGrid();
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    assertEquals(bruteFloorGap(col, x, y, z), g.floorGap(x, y & 15, z),
                            when + ": floorGap(" + x + "," + y + "," + z + ")");
                    assertEquals(bruteRunUp(col, x, y, z), g.runUp(x, y & 15, z),
                            when + ": runUp(" + x + "," + y + "," + z + ")");
                }
            }
        }
    }

    private void patch(NavSection[] col, int x, int y, int z, BlockState state) {
        int si = y >> 4;
        NavSection above = si + 1 < SECTIONS ? col[si + 1] : null;
        NavSection below = si > 0 ? col[si - 1] : null;
        NavSectionBuilder.patchCell(col[si], above, below, x, y & 15, z, state);
    }

    @Test
    void buildMatchesBruteForce() {
        assertColumnExact(buildColumn(), "after build");
    }

    @Test
    void unsweptGridStaysUnknownOrExactUnderPatches() {
        // The single-section-producer regime (classifyInto fixtures, no computeDepth): every cell starts
        // UNKNOWN, and patchCell's maintenance must never fabricate a stale claim there — after a patch,
        // each touched cell holds either UNKNOWN (no claim) or the brute-force-exact value.
        NavSection[] col = buildColumnNoDepth();
        for (int y = 0; y < SECTIONS * 16; y += 7) {
            TraversalGrid g = col[y >> 4].getTraversalGrid();
            assertEquals(TraversalGrid.DEPTH_UNKNOWN, g.floorGap(3, y & 15, 3));
            assertEquals(TraversalGrid.DEPTH_UNKNOWN, g.runUp(3, y & 15, 3));
        }
        patch(col, 4, 20, 4, Blocks.STONE.defaultBlockState());
        patch(col, 4, 20, 4, Blocks.AIR.defaultBlockState());
        for (int y = 0; y < SECTIONS * 16; y++) {
            TraversalGrid g = col[y >> 4].getTraversalGrid();
            int fg = g.floorGap(4, y & 15, 4);
            int ru = g.runUp(4, y & 15, 4);
            if (fg != TraversalGrid.DEPTH_UNKNOWN) {
                assertEquals(bruteFloorGap(col, 4, y, 4), fg, "patched-unswept floorGap y=" + y);
            }
            if (ru != TraversalGrid.DEPTH_UNKNOWN) {
                assertEquals(bruteRunUp(col, 4, y, 4), ru, "patched-unswept runUp y=" + y);
            }
        }
    }

    @Test
    void patchesStayExactIncludingSeams() {
        NavSection[] col = buildColumn();
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();

        // Place a floor mid-air (dirties the gaps above it and the runs below it), then remove it.
        patch(col, 4, 20, 4, stone);
        assertColumnExact(col, "after mid-air place");
        patch(col, 4, 20, 4, air);
        assertColumnExact(col, "after mid-air remove");

        // Seam rows: a change at ly=15 propagates its floorGap upward into the section above; one at ly=0
        // propagates its runUp downward into the section below.
        patch(col, 7, 15, 7, stone);
        assertColumnExact(col, "after ly=15 place");
        patch(col, 7, 16, 7, stone);
        assertColumnExact(col, "after ly=0 place");
        patch(col, 7, 15, 7, air);
        assertColumnExact(col, "after ly=15 remove");
        patch(col, 7, 16, 7, air);
        assertColumnExact(col, "after ly=0 remove");

        // Remove an existing floor (the y=31 plane at one column) — landings shift a whole window down.
        patch(col, 2, 31, 2, air);
        assertColumnExact(col, "after floor remove");

        // World-bottom and world-top edges.
        patch(col, 9, 1, 9, air);
        assertColumnExact(col, "after y=1 remove");
        patch(col, 9, 63, 9, stone);
        assertColumnExact(col, "after top place");
    }

    @Test
    void randomPatchStormStaysExact() {
        NavSection[] col = buildColumn();
        BlockState[] states = {
                Blocks.AIR.defaultBlockState(),
                Blocks.STONE.defaultBlockState(),
                Blocks.OAK_FENCE.defaultBlockState(),
                Blocks.WATER.defaultBlockState(),
        };
        Random rng = new Random(1234);
        for (int i = 0; i < 600; i++) {
            patch(col, rng.nextInt(16), rng.nextInt(SECTIONS * 16), rng.nextInt(16),
                    states[rng.nextInt(states.length)]);
        }
        assertColumnExact(col, "after 600-patch storm");
    }
}
