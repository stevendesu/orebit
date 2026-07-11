package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.orebit.mod.Debug;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.CandidateSink;
import com.orebit.mod.pathfinding.blockpathfinder.EditScratch;
import com.orebit.mod.pathfinding.blockpathfinder.Movement;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.pathfinding.blockpathfinder.MovementRegistry;
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
 * Honey block reduces jump power ({@code Block.getJumpFactor() == 0.5}, apex ~0.384 blocks — clears
 * nothing), and it is the ONLY vanilla block with a jump factor != 1.0. This proves the {@code
 * REDUCED_JUMP} descriptor bit + the takeoff-floor gate on the jump movements:
 * <ol>
 *   <li>{@link NavBlock} classifies honey with {@code reducesJump == true} and a normal block (stone)
 *       {@code false};</li>
 *   <li>{@link MovementContext#reducesJump} reads a honey FLOOR cell as reduced-jump (and stone / unbuilt
 *       as not);</li>
 *   <li>the jump-takeoff movements ({@link MovementRegistry#ASCEND Ascend}, {@link MovementRegistry#PARKOUR
 *       Parkour}) emit ZERO candidates from a honey source floor, where an identical STONE source floor
 *       (the control) emits a jump — so the gate, not the geometry, is what suppresses the jump.</li>
 * </ol>
 * The walking moves are deliberately NOT gated (walking off honey is fine) — not asserted here, but the
 * control cases confirm the geometry is otherwise jumpable.
 */
class ReducedJumpTest {

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

    // ---- (1) NavBlock classification -------------------------------------------------------------

    @Test
    void honeyClassifiesReducedJumpAndStoneDoesNot() {
        long honey = NavBlock.descriptorFor(Blocks.HONEY_BLOCK.defaultBlockState());
        long stone = NavBlock.descriptorFor(Blocks.STONE.defaultBlockState());

        assertTrue(NavBlock.reducesJump(honey), "honey block reduces jump (getJumpFactor 0.5)");
        assertFalse(NavBlock.reducesJump(stone), "stone does not reduce jump");
        // Honey must still be a real, standable floor (the gate suppresses the JUMP, not the footing).
        assertTrue(NavBlock.isStandable(honey), "honey is still a standable floor");
    }

    // ---- (2) MovementContext.reducesJump reads the takeoff floor cell ----------------------------

    @Test
    void movementContextReadsHoneyFloorAsReducedJump() {
        // A single stone section with a honey block at (2,5,8): the floor cell a jump move reads.
        NavGridView grid = singleSectionWith(new BlockPos(2, 5, 8), Blocks.HONEY_BLOCK.defaultBlockState());
        MovementContext ctx = new MovementContext(grid, BotCaps.DEFAULT);

        assertTrue(ctx.reducesJump(2, 5, 8), "the honey floor cell reads as reduced-jump");
        assertFalse(ctx.reducesJump(3, 5, 8), "an adjacent stone floor cell does not");
        assertFalse(ctx.reducesJump(200, 5, 8), "an unbuilt cell (reads as air) is never gated");
    }

    // ---- (3) The jump moves emit nothing from a honey source floor -------------------------------

    @Test
    void ascendRefusesToTakeOffFromHoney() {
        // A raised stone ledge one block up and one east of the source — a normal Ascend step.
        assertTrue(countCandidates(MovementRegistry.ASCEND, ascendCourse(Blocks.STONE.defaultBlockState())) >= 1,
                "control: Ascend must emit a jump from a STONE source floor onto the raised ledge");
        assertEquals(0, countCandidates(MovementRegistry.ASCEND, ascendCourse(Blocks.HONEY_BLOCK.defaultBlockState())),
                "Ascend must emit NO jump from a honey source floor (reduced-jump gate)");
    }

    @Test
    void parkourRefusesToTakeOffFromHoney() {
        // A one-wide bottomless gap with a stone landing platform beyond it — a normal g=1 gap jump.
        assertTrue(countCandidates(MovementRegistry.PARKOUR, parkourCourse(Blocks.STONE.defaultBlockState())) >= 1,
                "control: Parkour must emit a gap jump from a STONE source floor");
        assertEquals(0, countCandidates(MovementRegistry.PARKOUR, parkourCourse(Blocks.HONEY_BLOCK.defaultBlockState())),
                "Parkour must emit NO gap jump from a honey source floor (reduced-jump gate)");
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /** Count the candidates {@code move} emits when expanding the source floor node (2,5,8). */
    private static int countCandidates(Movement move, NavGridView grid) {
        MovementContext ctx = new MovementContext(grid, BotCaps.DEFAULT);
        int[] n = { 0 };
        move.candidates(ctx, 2, 5, 8, new CandidateSink() {
            @Override
            public void accept(int x, int y, int z, float cost, EditScratch edits) {
                n[0]++;
            }
        });
        return n[0];
    }

    /**
     * Ascend course: a solid stone section with the source floor at (2,5,8) and a raised stone ledge at
     * (3,6,8) one block up and one east; the source body ((2,6..8,8)) and the ledge body ((3,7..8,8)) are
     * carved clear so a full jump onto the ledge is valid. The source floor block is {@code sourceFloor}.
     */
    private static NavGridView ascendCourse(BlockState sourceFloor) {
        PalettedContainer<BlockState> s = solidStone();
        final int z = 8;
        // Source body + takeoff head clearance (feet y+1, head y+2, y+3 above head).
        for (int y = 6; y <= 8; y++) s.set(2, y, z, Blocks.AIR.defaultBlockState());
        // Ledge landing body (the ledge floor at (3,6,8) is already stone from the fill).
        for (int y = 7; y <= 8; y++) s.set(3, y, z, Blocks.AIR.defaultBlockState());
        s.set(2, 5, z, sourceFloor);
        return wrap(s);
    }

    /**
     * Parkour course: a solid stone section with a 1-wide corridor at z=8 (bodies + jump headroom carved
     * y=6..8 for x=1..5), a bottomless air gap at x=3 (air y=0..5, below the grid unbuilt so Fall never
     * lands), the source floor at (2,5,8), and a stone landing platform at (4,5,8). The source floor block
     * is {@code sourceFloor}. Mirrors {@code ParkourTest.buildCourse(g=1)}.
     */
    private static NavGridView parkourCourse(BlockState sourceFloor) {
        PalettedContainer<BlockState> s = solidStone();
        final int z = 8;
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int x = 1; x <= 5; x++) {          // corridor: body + jump headroom over the platforms
            for (int y = 6; y <= 8; y++) s.set(x, y, z, air);
        }
        for (int y = 0; y <= 5; y++) s.set(3, y, z, air); // the chasm: air to the section floor (bottomless)
        s.set(2, 5, z, sourceFloor);
        return wrap(s);
    }

    /** A single stone section with one cell overridden — for the MovementContext.reducesJump read. */
    private static NavGridView singleSectionWith(BlockPos pos, BlockState state) {
        PalettedContainer<BlockState> s = solidStone();
        s.set(pos.getX(), pos.getY(), pos.getZ(), state);
        return wrap(s);
    }

    private static PalettedContainer<BlockState> solidStone() {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        PalettedContainer<BlockState> s = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    s.set(x, y, z, stone);
                }
            }
        }
        return s;
    }

    /** Wrap one classified 16³ section (plus 3 air sections above) into a NavGridView at chunk (0,0). */
    private static NavGridView wrap(PalettedContainer<BlockState> s) {
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
}
