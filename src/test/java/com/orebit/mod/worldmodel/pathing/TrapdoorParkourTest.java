package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.orebit.mod.Debug;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.CandidateSink;
import com.orebit.mod.pathfinding.blockpathfinder.EditFixtures;
import com.orebit.mod.pathfinding.blockpathfinder.EditScratch;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.pathfinding.blockpathfinder.movements.Parkour;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

/**
 * Trapdoors × {@link Parkour} (DESIGN-trapdoors.md §6 — no Parkour source change; these pin the facts the
 * spec relies on):
 * <ul>
 *   <li><b>FROM a closed-bottom plate</b> — the takeoff row honours the REAL surface ({@code
 *       ParkourEnvelope.index(topY=3)}): a gap the full-block row admits (flat 3) is REFUSED off the 13/16-
 *       lower plate (the effective rise eats range), while a short gap still jumps.</li>
 *   <li><b>ONTO a plate</b> — the landing gate ({@code standable ∧ ¬narrow}) admits a closed-bottom
 *       trapdoor (judged as a full block — the §10-deferred conservative under-admission).</li>
 *   <li><b>Close-then-parkour</b> — an OPEN panel is no takeoff at all ({@code solidFooting} fails); after
 *       an arriving move's {@code SET_CLOSED} (the {@link EditFixtures} path-diff seam) the SAME cell reads
 *       the closed plate descriptor and the takeoff jumps from it.</li>
 * </ul>
 * Lives in this package for {@link NavGridView}'s package-private synthetic constructor.
 */
class TrapdoorParkourTest {

    private static boolean bootstrapped;

    @BeforeAll
    static void boot() {
        if (!bootstrapped) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            bootstrapped = true;
        }
        BlockPathfinder.LOG_TIMING = false;
        Debug.ENABLED = false;
    }

    private static final BotCaps WALK = new BotCaps(1, BotCaps.DEFAULT_SAFE_FALL, BotCaps.DEFAULT_MAX_FALL,
            false, BotCaps.DEFAULT_COST_PER_HITPOINT, false, false, 100, false, BotCaps.DEFAULT_MAX_NODES,
            1.0f, true);

    private static final int TX = 4, Z = 6; // takeoff floor cell (TX, 0, Z)

    @Test
    void fullBlockTakeoffJumpsTheThreeGap() {
        // Control: the documented flat envelope from a FULL takeoff is gaps 1–3 — the 3-gap jump lands.
        NavGridView grid = gapGrid(Blocks.STONE.defaultBlockState(), 3, Blocks.STONE.defaultBlockState());
        Capture cap = new Capture(TX + 4, 0, Z);
        new Parkour().candidates(new MovementContext(grid, WALK), TX, 0, Z, cap);
        assertTrue(cap.got, "a flat 3-gap sprint jump from a full block lands (the documented envelope)");
    }

    @Test
    void plateTakeoffRefusesTheThreeGap() {
        // The same 3-gap from a closed-bottom PLATE takeoff (top 3/16): the envelope row is derived for
        // the real 13/16-lower surface — effectively a rising jump — and the 3-gap is out of reach. This
        // is the "takeoff row honours topY=3" pin: only the takeoff block differs from the control above.
        NavGridView grid = gapGrid(plate(), 3, Blocks.STONE.defaultBlockState());
        Capture cap = new Capture(TX + 4, 0, Z);
        new Parkour().candidates(new MovementContext(grid, WALK), TX, 0, Z, cap);
        assertFalse(cap.got, "the plate takeoff's derived row refuses the 3-gap the full-block row admits");
    }

    @Test
    void plateTakeoffStillJumpsAShortGap() {
        // The plate row is narrowed, not zeroed: a 1-gap jump off the plate still lands.
        NavGridView grid = gapGrid(plate(), 1, Blocks.STONE.defaultBlockState());
        Capture cap = new Capture(TX + 2, 0, Z);
        new Parkour().candidates(new MovementContext(grid, WALK), TX, 0, Z, cap);
        assertTrue(cap.got, "a 1-gap jump from the plate takeoff is admitted (row honours, not forbids)");
    }

    @Test
    void plateLandingIsAdmitted() {
        // ONTO a plate: the landing gate is standable ∧ ¬narrow — a closed-bottom trapdoor passes (judged
        // as a full-block landing; conservative, §10-deferred landing-row awareness).
        NavGridView grid = gapGrid(Blocks.STONE.defaultBlockState(), 1, plate());
        Capture cap = new Capture(TX + 2, 0, Z);
        new Parkour().candidates(new MovementContext(grid, WALK), TX, 0, Z, cap);
        assertTrue(cap.got, "a closed-bottom plate is a legal parkour landing");
    }

    @Test
    void closeThenParkourReadsTheClosedDescriptorAtTakeoff() {
        // Grid truth: the takeoff cell holds an OPEN panel — not standable, so solidFooting refuses and
        // NO jump exists. After an earlier step's SET_CLOSED rides the path diff, the identical cell reads
        // the closed PLATE descriptor and the takeoff jumps from it (downstream nodes see toggled geometry).
        NavGridView grid = gapGrid(td(Blocks.OAK_TRAPDOOR, Direction.EAST, true, Half.BOTTOM), 1,
                Blocks.STONE.defaultBlockState());
        MovementContext mc = new MovementContext(grid, WALK);

        Capture before = new Capture(TX + 2, 0, Z);
        new Parkour().candidates(mc, TX, 0, Z, before);
        assertFalse(before.got, "an OPEN panel is no takeoff — solidFooting refuses the non-standable cell");

        mc.pathEdits().reset();
        mc.pathEdits().add(EditFixtures.doorSetStep(false, BlockPos.asLong(TX, 0, Z)));
        Capture after = new Capture(TX + 2, 0, Z);
        new Parkour().candidates(mc, TX, 0, Z, after);
        assertTrue(after.got, "after the arriving fold's SET_CLOSED the takeoff reads the plate and jumps");
    }

    // ---- scene builder ------------------------------------------------------------------------------

    private static BlockState plate() {
        return td(Blocks.OAK_TRAPDOOR, Direction.NORTH, false, Half.BOTTOM);
    }

    private static BlockState td(Block block, Direction facing, boolean open, Half half) {
        return block.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
                .setValue(BlockStateProperties.OPEN, open)
                .setValue(BlockStateProperties.HALF, half)
                .setValue(BlockStateProperties.WATERLOGGED, false);
    }

    /**
     * The parkour runway: a 1-wide strip at z=Z with air body y=1..3 (arc prisms clear, stone ceiling
     * y=4). Floor row y=0: {@code takeoff} at TX, {@code gap} bottomless AIR columns (TX+1..TX+gap), the
     * {@code landing} at TX+gap+1, stone elsewhere (which correctly TERMINATES each direction's scan at
     * the first standable column past the strip).
     */
    private static NavGridView gapGrid(BlockState takeoff, int gap, BlockState landing) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        PalettedContainer<BlockState> s = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        for (int x = 0; x < 16; x++)
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    s.set(x, y, z, stone);
        for (int cx = 2; cx <= 12; cx++) {
            s.set(cx, 1, Z, air);
            s.set(cx, 2, Z, air);
            s.set(cx, 3, Z, air);
        }
        s.set(TX, 0, Z, takeoff);
        for (int cx = TX + 1; cx <= TX + gap; cx++) s.set(cx, 0, Z, air); // bottomless gap columns
        s.set(TX + gap + 1, 0, Z, landing);
        NavSection section = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(s, false, section.getTraversalGrid());
        PalettedContainer<BlockState> airStates = new PalettedContainer<>(
                Blocks.AIR.defaultBlockState(), Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        NavSection airSection = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(airStates, true, airSection.getTraversalGrid());
        NavSection[] column = { section, airSection, airSection, airSection };
        ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        chunks.put(NavStore.key(0, 0), column);
        return new NavGridView(0, chunks);
    }

    /** Records whether a specific destination cell was emitted. */
    private static final class Capture implements CandidateSink {
        final int tx, ty, tz;
        boolean got;

        Capture(int tx, int ty, int tz) { this.tx = tx; this.ty = ty; this.tz = tz; }

        @Override
        public void accept(int x, int y, int z, float c, EditScratch e) {
            if (x == tx && y == ty && z == tz) got = true;
        }
    }
}
