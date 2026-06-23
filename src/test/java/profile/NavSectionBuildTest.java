package profile;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.orebit.mod.worldmodel.pathing.NavSectionBuilder;
import com.orebit.mod.worldmodel.pathing.TraversalClass;
import com.orebit.mod.worldmodel.pathing.TraversalGrid;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;

/**
 * Exercises the full Step-3 read+classify pass ({@link NavSectionBuilder#classifyInto}) end to end:
 * builds a real {@link PalettedContainer}, runs the palette read ({@code SectionPalette}) → NavBlock
 * descriptor map → {@code NavClassifier} pipeline, and checks the resulting {@link TraversalGrid}.
 * Also verifies the uniform-air bypass.
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

        // --- Uniform-air bypass: every cell is open air -> SLOW (no floor, nothing to bridge from).
        TraversalGrid airGrid = new TraversalGrid();
        NavSectionBuilder.classifyInto(newSection(), true, airGrid);
        assertEquals(TraversalClass.SLOW, airGrid.get(8, 8, 8), "open air bypass");
        assertEquals(TraversalClass.SLOW, airGrid.get(0, 0, 0), "open air bypass (corner)");
        assertEquals(TraversalClass.SLOW, airGrid.get(15, 15, 15), "open air bypass (far corner)");

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

        // Standing on the stone plane with clear headroom -> CLEAR.
        assertEquals(TraversalClass.CLEAR, g.get(8, 4, 8), "stone floor");
        // The air cell directly above the floor can bridge from the block below -> EASY.
        assertEquals(TraversalClass.EASY, g.get(8, 5, 8), "air above floor (bridgeable)");
        // The air cell directly beneath the floor has stone in its headroom -> BLOCKED.
        assertEquals(TraversalClass.BLOCKED, g.get(8, 3, 8), "air under floor (stone headroom)");
        // High open air with no support and nothing to attach to -> SLOW (fall).
        assertEquals(TraversalClass.SLOW, g.get(8, 10, 8), "open air high above");
    }
}
