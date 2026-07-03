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

    /**
     * The vertical-overscan column build (the two-pass form {@code ChunkNavBuilder} runs): classify BOTH
     * sections' navtypes first, then compute each section's flags with the section above's grid in hand.
     * Floor cells in a section's top rows must see hazards/solids sitting in the bottom rows of the
     * section above — the exact geometry the old within-section build left stale-CLEAR (the sweet-berry
     * seam-maze the bot died in).
     */
    @Test
    void verticalOverscanAtBuildTime() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();

        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState bush = Blocks.SWEET_BERRY_BUSH.defaultBlockState();

        // Section k-1 (below): isolated floor cells in its top rows.
        PalettedContainer<BlockState> below = newSection();
        below.getAndSet(8, 15, 8, stone);   // floor A — top row; body y+1 is the above section's ly=0
        below.getAndSet(10, 14, 10, stone); // floor B — ly=14; body y+2 is the above section's ly=0
        below.getAndSet(4, 15, 4, stone);   // floor C — headroom probe
        below.getAndSet(2, 15, 2, stone);   // floor D — control, nothing above

        // Section k (above): the seam-row occupants.
        PalettedContainer<BlockState> above = newSection();
        above.getAndSet(8, 0, 8, bush);   // hazard over floor A
        above.getAndSet(10, 0, 10, bush); // hazard over floor B (two cells up from the floor)
        above.getAndSet(4, 0, 4, stone);  // solid over floor C

        TraversalGrid belowGrid = new TraversalGrid();
        TraversalGrid aboveGrid = new TraversalGrid();
        boolean belowAir = NavSectionBuilder.classifyNavtypes(below, false, belowGrid, null);
        boolean aboveAir = NavSectionBuilder.classifyNavtypes(above, false, aboveGrid, null);
        NavSectionBuilder.computeFlags(aboveGrid, aboveAir, null);              // nothing above the top
        NavSectionBuilder.computeFlags(belowGrid, belowAir, aboveGrid);         // seam overscan under test

        // (1) hazard across the seam sets the prefilter bits on the below section's floor cells.
        int fA = belowGrid.flags(8, 15, 8);
        assertTrue(NavFlags.clearableHazard(fA), "bush at above ly=0 = hazard on the ly=15 floor below");
        assertTrue(NavFlags.slowTransit(fA), "bush at above ly=0 = slow transit on the ly=15 floor below");
        int fB = belowGrid.flags(10, 14, 10);
        assertTrue(NavFlags.clearableHazard(fB), "bush two cells over the seam still lands in the body space (ly=14 floor)");

        // (3) HEADROOM at the seam is honest: a solid at above ly=0 blocks the below floor at the feet.
        assertEquals(NavFlags.HEADROOM_NONE, NavFlags.headroom(belowGrid.flags(4, 15, 4)),
                "solid across the seam = no headroom on the top-row floor");

        // Control: an untouched top-row floor keeps clean, full-headroom flags.
        int fD = belowGrid.flags(2, 15, 2);
        assertEquals(NavFlags.HEADROOM_JUMP, NavFlags.headroom(fD), "clear seam column keeps full headroom");
        assertFalse(NavFlags.clearableHazard(fD), "clear seam column carries no hazard bit");

        // A UNIFORM-AIR section below real blocks: the interior keeps the one-cell fill bypass, but the
        // overscan-affected top rows are computed individually against the section above.
        TraversalGrid airBelowGrid = new TraversalGrid();
        boolean airBelowAir = NavSectionBuilder.classifyNavtypes(null, true, airBelowGrid, null);
        NavSectionBuilder.computeFlags(airBelowGrid, airBelowAir, aboveGrid);
        assertEquals(NavFlags.HEADROOM_NONE, NavFlags.headroom(airBelowGrid.flags(4, 15, 4)),
                "air section's top row still reads the solid across the seam");
        assertTrue(NavFlags.clearableHazard(airBelowGrid.flags(8, 15, 8)),
                "air section's top row still reads the hazard across the seam");
        assertEquals(NavFlags.HEADROOM_JUMP, NavFlags.headroom(airBelowGrid.flags(4, 8, 4)),
                "air section interior keeps the uniform fill");
    }
}
