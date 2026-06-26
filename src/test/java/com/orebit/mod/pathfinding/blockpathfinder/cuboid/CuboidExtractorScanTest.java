package com.orebit.mod.pathfinding.blockpathfinder.cuboid;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.orebit.mod.pathfinding.blockpathfinder.RegionBound;
import com.orebit.mod.worldmodel.pathing.NavGridView;
import com.orebit.mod.worldmodel.pathing.NavSection;
import com.orebit.mod.worldmodel.pathing.NavSectionBuilder;
import com.orebit.mod.worldmodel.pathing.NavStore;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;

/**
 * Bug-guard for {@link CuboidExtractor#extract}'s section-aligned bulk scan ({@code rectUniform}).
 *
 * <p>The uniform-air test/bench fixtures cannot catch a missing inner row iteration: if the
 * section-stepping loop scans only the section-corner {@code (y,z)} row and advances by whole section,
 * every other {@code (y,z)} row in the section is SKIPPED — and on uniform air the skip is invisible
 * (the corner row is the same navtype as the skipped rows). This test deliberately places a single
 * NON-uniform cell (a stone block) <b>off the section-corner row</b> of the slab, so a skipped-row scan
 * would over-claim the box THROUGH that cell — the through-a-wall invalid jump.
 *
 * <p>Geometry: start cell {@code (8,8,8)} in air, travel axis Y (the pillar case). Stage 1 grows the X-Z
 * slab to fill the section; stage 2 extends up. A stone block sits at {@code (9,12,9)} — z-local 9, i.e.
 * NOT the section-corner z (z0 = 0 within the slab). The buggy loop scans only z=0 at Y=12, never sees the
 * stone, and grows the box past Y=12 (including the stone). The correct loop scans every (y,z) of the
 * section sub-box, hits the stone at Y=12, and stops the box at maxY=11. We assert the returned box
 * EXCLUDES the stone layer ({@code maxY < 12}). This test FAILS against the buggy loop, PASSES against the
 * fix. Lives in the {@code cuboid} package so it can read {@link Cuboid}'s package-private bounds directly.
 */
class CuboidExtractorScanTest {

    private static boolean bootstrapped;

    @BeforeAll
    static void boot() {
        if (!bootstrapped) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            bootstrapped = true;
        }
    }

    @Test
    void scanDoesNotSkipOffCornerRows() {
        final int stoneX = 9, stoneY = 12, stoneZ = 9;
        NavGridView grid = buildWorldWithStoneAt(stoneX, stoneY, stoneZ);

        // A corridor covering the whole section in X-Z (so the stage-1 slab fills z 0..15 — z0 = 0, the
        // section corner — putting the stone at z=9 squarely on a row the buggy loop skips), tall in Y.
        RegionBound corridor = new RegionBound(0, 15, 0, 40, 0, 15);

        Cuboid out = new Cuboid();
        CuboidExtractor.extract(grid, 8, 8, 8, Axes.AXIS_Y, corridor, out);

        assertTrue(out.isValid(), "the start cell is air in-corridor — extract should produce a box");

        // The stone at Y=12 must STOP the upward extent. A correct scan visits the off-corner stone and
        // caps the box at maxY=11; the skipped-row bug never sees it and over-claims through Y>=12.
        assertTrue(out.maxY < stoneY,
                "box must stop below the non-uniform cell at y=" + stoneY
                        + "; got maxY=" + out.maxY + " (the skipped-row bug would over-claim through it)");

        // Sanity: the box truly does not contain the stone cell (the through-a-wall failure mode).
        assertTrue(!out.contains(stoneX, stoneY, stoneZ),
                "box must not contain the non-uniform cell (" + stoneX + "," + stoneY + "," + stoneZ + ")");
    }

    /**
     * A single chunk (0,0) whose lowest section (y 0..15) is air everywhere EXCEPT one stone block at
     * {@code (sx,sy,sz)} (section-local coords, sy in 0..15); sections above are pure air. minY=0, no live
     * level (the synthetic {@code overSections} view).
     */
    private static NavGridView buildWorldWithStoneAt(int sx, int sy, int sz) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();

        PalettedContainer<BlockState> mixedStates = new PalettedContainer<>(
                Block.BLOCK_STATE_REGISTRY, air, PalettedContainer.Strategy.SECTION_STATES);
        mixedStates.set(sx, sy, sz, stone);
        NavSection mixed = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(mixedStates, false, mixed.getTraversalGrid());

        PalettedContainer<BlockState> airStates = new PalettedContainer<>(
                Block.BLOCK_STATE_REGISTRY, air, PalettedContainer.Strategy.SECTION_STATES);
        NavSection airSection = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(airStates, true, airSection.getTraversalGrid());

        NavSection[] column = { mixed, airSection, airSection, airSection }; // y 0..63
        ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        chunks.put(NavStore.key(0, 0), column);
        return NavGridView.overSections(0, chunks);
    }
}
