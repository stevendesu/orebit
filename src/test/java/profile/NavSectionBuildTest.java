package profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.orebit.mod.worldmodel.navblock.NavBlock;
import com.orebit.mod.worldmodel.pathing.NavFlags;
import com.orebit.mod.worldmodel.pathing.NavSectionBuilder;
import com.orebit.mod.worldmodel.pathing.TraversalGrid;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;

/**
 * Exercises the full read+classify pass ({@link NavSectionBuilder#classifyInto}) end to end: builds a
 * real {@link PalettedContainer}, runs the palette read ({@code SectionPalette}) → NavBlock descriptor
 * map → {@link NavFlags} pipeline, and checks the resulting {@link TraversalGrid} (navtype + neighbour
 * flags). Also verifies the uniform-air bypass.
 */
public class NavSectionBuildTest {

    private static PalettedContainer<BlockState> newSection() {
        return new PalettedContainer<>(
                Block.BLOCK_STATE_REGISTRY,
                Blocks.AIR.defaultBlockState(),
                PalettedContainer.Strategy.SECTION_STATES);
    }

    @Test
    void uniformAirBypassAndRealClassify() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        // --- Uniform-air bypass: every cell is open air -> full (JUMP) headroom, nothing to bridge from.
        TraversalGrid airGrid = new TraversalGrid();
        NavSectionBuilder.classifyInto(newSection(), true, airGrid);
        assertEquals(NavFlags.HEADROOM_JUMP, NavFlags.headroom(airGrid.flags(8, 8, 8)), "open air bypass");
        assertEquals(NavFlags.HEADROOM_JUMP, NavFlags.headroom(airGrid.flags(0, 0, 0)), "open air bypass (corner)");
        assertEquals(NavFlags.HEADROOM_JUMP, NavFlags.headroom(airGrid.flags(15, 15, 15)), "open air bypass (far corner)");
        assertEquals(NavBlock.AIR & 0xFFFF, airGrid.navtype(8, 8, 8), "air bypass navtype");

        // --- Real read+classify path: a stone floor plane at y=4, air above.
        PalettedContainer<BlockState> c = newSection();
        BlockState stone = Blocks.STONE.defaultBlockState();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                c.getAndSet(x, 4, z, stone);
            }
        }

        TraversalGrid g = new TraversalGrid();
        NavSectionBuilder.classifyInto(c, false, g);

        // Standing on the stone plane with clear headroom -> JUMP, and the cell carries stone's navtype.
        assertEquals(NavFlags.HEADROOM_JUMP, NavFlags.headroom(g.flags(8, 4, 8)), "stone floor headroom");
        assertEquals(NavBlock.navtypeFor(stone) & 0xFFFF, g.navtype(8, 4, 8), "stone floor navtype");
        // The air cell directly above the floor can bridge from the block below.
        assertTrue(NavFlags.placeableNeighbor(g.flags(8, 5, 8)), "air above floor is bridgeable");
        // The air cell directly beneath the floor has stone in its headroom -> NONE.
        assertEquals(NavFlags.HEADROOM_NONE, NavFlags.headroom(g.flags(8, 3, 8)), "air under floor (stone headroom)");
        // High open air with no support and nothing to attach to -> full headroom, nothing to bridge.
        assertEquals(NavFlags.HEADROOM_JUMP, NavFlags.headroom(g.flags(8, 10, 8)), "open air high above");
        assertFalse(NavFlags.placeableNeighbor(g.flags(8, 10, 8)), "open air high above has no face");
    }
}
