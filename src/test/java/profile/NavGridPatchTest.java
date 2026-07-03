package profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.orebit.mod.worldmodel.navblock.NavBlock;
import com.orebit.mod.worldmodel.pathing.NavFlags;
import com.orebit.mod.worldmodel.pathing.NavSection;
import com.orebit.mod.worldmodel.pathing.NavSectionBuilder;
import com.orebit.mod.worldmodel.pathing.TraversalGrid;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;

/**
 * Exercises the incremental block-update hook ({@link NavSectionBuilder#patchCell}): build a section,
 * change one cell, and verify the cell's navtype updates AND the within-section neighbourhood whose
 * neighbour-property flags depend on it is recomputed — while far cells are untouched.
 */
public class NavGridPatchTest {

    private static PalettedContainer<BlockState> newSection() {
        return new PalettedContainer<>(
                Block.BLOCK_STATE_REGISTRY,
                Blocks.AIR.defaultBlockState(),
                PalettedContainer.Strategy.SECTION_STATES);
    }

    @Test
    void patchUpdatesNavtypeAndNeighbourhood() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        // A stone floor plane at y=4, air above (same fixture as NavSectionBuildTest).
        PalettedContainer<BlockState> c = newSection();
        BlockState stone = Blocks.STONE.defaultBlockState();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                c.getAndSet(x, 4, z, stone);
            }
        }

        NavSection section = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(c, false, section.getTraversalGrid());
        TraversalGrid g = section.getTraversalGrid();

        // Baseline: standing on the floor has full (JUMP) headroom; so does the air cell above it.
        assertEquals(NavFlags.HEADROOM_JUMP, NavFlags.headroom(g.flags(8, 4, 8)), "floor headroom before");
        assertEquals(NavFlags.HEADROOM_JUMP, NavFlags.headroom(g.flags(8, 5, 8)), "air above floor before");

        // Place stone into the air cell directly above the floor.
        NavSectionBuilder.patchCell(section, 8, 5, 8, stone);

        // The changed cell now carries stone's navtype and still has clear headroom above it.
        assertEquals(NavBlock.navtypeFor(stone) & 0xFFFF, g.navtype(8, 5, 8), "changed cell navtype");
        assertEquals(NavFlags.HEADROOM_JUMP, NavFlags.headroom(g.flags(8, 5, 8)), "changed cell headroom");
        // Its downstairs neighbour now has stone directly in its headroom -> NONE (the recompute reached it).
        assertEquals(NavFlags.HEADROOM_NONE, NavFlags.headroom(g.flags(8, 4, 8)), "floor now headroom-blocked");
        // A floor cell far from the change is untouched.
        assertEquals(NavFlags.HEADROOM_JUMP, NavFlags.headroom(g.flags(2, 4, 2)), "far floor untouched");
    }

    /**
     * Cross-seam patching (the vertical-overscan contract on the incremental path): a block change in a
     * section's BOTTOM rows is a flags change for the top floor cells of the section BELOW (they read it
     * through their upward overscan), and a recompute in the TOP rows must read the section ABOVE rather
     * than wipe seam-derived bits back to air-optimism.
     */
    @Test
    void patchPropagatesAcrossTheVerticalSeam() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState bush = Blocks.SWEET_BERRY_BUSH.defaultBlockState();
        BlockState air = Blocks.AIR.defaultBlockState();

        // Column: lower section with a stone floor plane in its TOP row (y=15), upper section all air.
        PalettedContainer<BlockState> c = newSection();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                c.getAndSet(x, 15, z, stone);
            }
        }
        NavSection lower = NavSection.create(BlockPos.ZERO);
        NavSection upper = NavSection.create(new BlockPos(0, 16, 0));
        boolean lowerAir = NavSectionBuilder.classifyNavtypes(c, false, lower.getTraversalGrid(), null);
        boolean upperAir = NavSectionBuilder.classifyNavtypes(null, true, upper.getTraversalGrid(), null);
        NavSectionBuilder.computeFlags(upper.getTraversalGrid(), upperAir, null);
        NavSectionBuilder.computeFlags(lower.getTraversalGrid(), lowerAir,
                upperAir ? null : upper.getTraversalGrid()); // air above = null, as ChunkNavBuilder passes it
        TraversalGrid lg = lower.getTraversalGrid();

        assertEquals(NavFlags.HEADROOM_JUMP, NavFlags.headroom(lg.flags(8, 15, 8)), "seam floor open before");
        assertFalse(NavFlags.clearableHazard(lg.flags(8, 15, 8)), "seam floor clean before");

        // Place a berry bush just across the seam (upper ly=0): the patch must reach DOWN into the
        // lower section's top-row flags.
        NavSectionBuilder.patchCell(upper, null, lower, 8, 0, 8, bush);
        assertEquals(NavBlock.navtypeFor(bush) & 0xFFFF, upper.getTraversalGrid().navtype(8, 0, 8),
                "changed cell navtype (upper section)");
        assertTrue(NavFlags.clearableHazard(lg.flags(8, 15, 8)), "below floor now sees the bush hazard");
        assertTrue(NavFlags.slowTransit(lg.flags(8, 15, 8)), "below floor now sees the bush slow-transit");
        assertEquals(NavFlags.HEADROOM_JUMP, NavFlags.headroom(lg.flags(8, 15, 8)), "bush is passable — headroom kept");
        assertFalse(NavFlags.clearableHazard(lg.flags(2, 15, 2)), "far seam floor untouched");

        // A recompute in the lower section's TOP rows (a neighbouring block change) must KEEP the
        // seam-derived hazard bit — its window reads the upper section through the overscan.
        NavSectionBuilder.patchCell(lower, upper, null, 7, 15, 8, stone);
        assertTrue(NavFlags.clearableHazard(lg.flags(8, 15, 8)),
                "own-section recompute near the seam keeps reading the section above");

        // Remove the bush -> the below-section bits clear again.
        NavSectionBuilder.patchCell(upper, null, lower, 8, 0, 8, air);
        assertFalse(NavFlags.clearableHazard(lg.flags(8, 15, 8)), "hazard cleared after removal");
        assertFalse(NavFlags.slowTransit(lg.flags(8, 15, 8)), "slow-transit cleared after removal");

        // Solid across the seam limits the below floor's headroom; removal restores it.
        NavSectionBuilder.patchCell(upper, null, lower, 8, 0, 8, stone);
        assertEquals(NavFlags.HEADROOM_NONE, NavFlags.headroom(lg.flags(8, 15, 8)), "solid across the seam");
        NavSectionBuilder.patchCell(upper, null, lower, 8, 0, 8, air);
        assertEquals(NavFlags.HEADROOM_JUMP, NavFlags.headroom(lg.flags(8, 15, 8)), "headroom restored");
    }
}
