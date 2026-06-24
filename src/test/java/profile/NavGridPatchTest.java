package profile;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
