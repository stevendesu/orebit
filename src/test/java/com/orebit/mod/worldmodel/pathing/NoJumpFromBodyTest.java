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
 * A cobweb the bot's BODY stands INSIDE at takeoff throttles the jump: vanilla's {@code WebBlock.entityInside}
 * calls {@code makeStuckInBlock(state, new Vec3(0.25, 0.05, 0.25))}, and {@code Entity.move} multiplies the
 * WHOLE move vector — the Y component too — by that {@code stuckSpeedMultiplier}, so a jump's {@code 0.42}
 * take-off velocity becomes {@code 0.42 × 0.05 = 0.021} (apex ~0.021 blocks — un-jumpable). Cobweb is the only
 * vanilla block whose stuck Y-multiplier is below the ~0.5 jump-kill threshold (sweet berry bush Y {@code
 * 0.75} → apex ~0.76; powder snow Y {@code 1.5} → boosts), and it is exactly the block the descriptor already
 * classes {@link NavBlock#TRANSIT_HEAVY HEAVY} (berry bush / powder snow are {@link NavBlock#TRANSIT_LIGHT
 * LIGHT}) — so the existing HEAVY/LIGHT split is the jump-kill discriminator and no new descriptor bit is
 * needed. This proves the body-cell gate on the jump-takeoff movements:
 * <ol>
 *   <li>{@link NavBlock} classes cobweb {@code transitSlow == HEAVY}, berry bush {@code LIGHT}, stone
 *       {@code NONE};</li>
 *   <li>{@link MovementContext#noJumpFromBody} reads a cobweb feet cell as jump-killing (and a clear / berry
 *       bush / unbuilt body as not);</li>
 *   <li>the jump-takeoff movements ({@link MovementRegistry#ASCEND Ascend}, {@link MovementRegistry#PARKOUR
 *       Parkour}) emit ZERO candidates when the takeoff feet cell is cobweb, where an identical CLEAR feet
 *       cell (the control) emits a jump — so the gate, not the geometry, suppresses the jump;</li>
 *   <li>{@link MovementRegistry#TRAVERSE Traverse} STILL emits from a cobweb feet cell — walking / step-assist
 *       through cobweb is deliberately not gated.</li>
 * </ol>
 */
class NoJumpFromBodyTest {

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
    void cobwebIsHeavyBerryBushIsLightStoneIsNone() {
        long cobweb = NavBlock.descriptorFor(Blocks.COBWEB.defaultBlockState());
        long berry = NavBlock.descriptorFor(Blocks.SWEET_BERRY_BUSH.defaultBlockState());
        long stone = NavBlock.descriptorFor(Blocks.STONE.defaultBlockState());

        assertEquals(NavBlock.TRANSIT_HEAVY, NavBlock.transitSlow(cobweb), "cobweb is HEAVY through-slow");
        assertEquals(NavBlock.TRANSIT_LIGHT, NavBlock.transitSlow(berry), "sweet berry bush is LIGHT");
        assertEquals(NavBlock.TRANSIT_NONE, NavBlock.transitSlow(stone), "stone is not through-slow");
        // Cobweb is a body cell you pass THROUGH — passable, not a standable floor.
        assertTrue(NavBlock.isPassable(cobweb), "cobweb is passable (the body occupies it)");
    }

    // ---- (2) MovementContext.noJumpFromBody reads the takeoff feet cell --------------------------

    @Test
    void noJumpFromBodyDetectsCobwebFeetCellOnly() {
        // Feet cell (2,6,8) is the body cell just above the floor (2,5,8).
        assertTrue(noJumpFromBody(Blocks.COBWEB.defaultBlockState()),
                "a cobweb feet cell kills the take-off jump");
        assertFalse(noJumpFromBody(Blocks.AIR.defaultBlockState()),
                "a clear (air) feet cell does not");
        assertFalse(noJumpFromBody(Blocks.SWEET_BERRY_BUSH.defaultBlockState()),
                "a LIGHT through-slow feet cell (berry bush, Y 0.75) leaves a jumpable apex — not gated");
        // An unbuilt cell (reads flags 0) is never gated.
        MovementContext ctx = new MovementContext(bodyCourse(Blocks.AIR.defaultBlockState()), BotCaps.DEFAULT);
        assertFalse(ctx.noJumpFromBody(200, 5, 8), "an unbuilt floor is never gated");
    }

    /** noJumpFromBody(2,5,8) with the given block filling the feet cell (2,6,8). */
    private static boolean noJumpFromBody(BlockState feet) {
        MovementContext ctx = new MovementContext(bodyCourse(feet), BotCaps.DEFAULT);
        return ctx.noJumpFromBody(2, 5, 8);
    }

    // ---- (3) The jump moves refuse from a cobweb feet cell; the control jumps --------------------

    @Test
    void ascendRefusesToTakeOffFromCobwebBody() {
        assertTrue(countCandidates(MovementRegistry.ASCEND, ascendCourse(Blocks.AIR.defaultBlockState())) >= 1,
                "control: Ascend must emit a jump when the feet cell is CLEAR");
        assertEquals(0, countCandidates(MovementRegistry.ASCEND, ascendCourse(Blocks.COBWEB.defaultBlockState())),
                "Ascend must emit NO jump when the takeoff feet cell is cobweb (stuck-multiplier gate)");
    }

    @Test
    void parkourRefusesToTakeOffFromCobwebBody() {
        assertTrue(countCandidates(MovementRegistry.PARKOUR, parkourCourse(Blocks.AIR.defaultBlockState())) >= 1,
                "control: Parkour must emit a gap jump when the feet cell is CLEAR");
        assertEquals(0, countCandidates(MovementRegistry.PARKOUR, parkourCourse(Blocks.COBWEB.defaultBlockState())),
                "Parkour must emit NO gap jump when the takeoff feet cell is cobweb (stuck-multiplier gate)");
    }

    // ---- (4) Traverse (walking / step-assist) is NOT gated ---------------------------------------

    @Test
    void traverseStillWalksThroughACobwebBody() {
        assertTrue(countCandidates(MovementRegistry.TRAVERSE, traverseCourse(Blocks.COBWEB.defaultBlockState())) >= 1,
                "Traverse must still emit — walking / step-assist through cobweb is allowed");
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
     * A solid stone section: source floor STONE at (2,5,8), the feet cell (2,6,8) set to {@code feet}, and the
     * rest of the takeoff body ((2,7,8),(2,8,8)) carved air (a cobweb feet cell is walk-clear, so HEADROOM
     * stays JUMP either way). No landing geometry — for the {@link MovementContext#noJumpFromBody} read only.
     */
    private static NavGridView bodyCourse(BlockState feet) {
        PalettedContainer<BlockState> s = solidStone();
        final int z = 8;
        for (int y = 7; y <= 8; y++) s.set(2, y, z, Blocks.AIR.defaultBlockState());
        s.set(2, 6, z, feet);
        // floor (2,5,8) stays stone from the fill.
        return wrap(s);
    }

    /**
     * Ascend course: source floor STONE at (2,5,8) with a raised stone ledge at (3,6,8) one block up and one
     * east; the feet cell (2,6,8) is {@code feet}, the rest of the source body ((2,7,8),(2,8,8)) and the ledge
     * body ((3,7,8),(3,8,8)) carved clear so a full jump onto the ledge is geometrically valid.
     */
    private static NavGridView ascendCourse(BlockState feet) {
        PalettedContainer<BlockState> s = solidStone();
        final int z = 8;
        for (int y = 7; y <= 8; y++) s.set(2, y, z, Blocks.AIR.defaultBlockState());
        s.set(2, 6, z, feet);
        for (int y = 7; y <= 8; y++) s.set(3, y, z, Blocks.AIR.defaultBlockState());
        return wrap(s);
    }

    /**
     * Parkour course: a 1-wide corridor at z=8 (bodies + jump headroom carved y=7..8 for x=1..5, y=6 for the
     * platforms except the source feet), a bottomless air gap at x=3, source floor STONE at (2,5,8) with the
     * feet cell (2,6,8) set to {@code feet}, and a stone landing platform at (4,5,8). Mirrors {@code
     * ParkourTest.buildCourse(g=1)} with the takeoff feet cell parametrised.
     */
    private static NavGridView parkourCourse(BlockState feet) {
        PalettedContainer<BlockState> s = solidStone();
        final int z = 8;
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int x = 1; x <= 5; x++) {          // corridor: body + jump headroom over the platforms
            for (int y = 6; y <= 8; y++) s.set(x, y, z, air);
        }
        for (int y = 0; y <= 5; y++) s.set(3, y, z, air); // the chasm: air to the section floor (bottomless)
        s.set(2, 6, z, feet);                             // the takeoff feet cell
        // floor (2,5,8) and landing (4,5,8) stay stone from the fill.
        return wrap(s);
    }

    /**
     * Traverse course: flat stone floor with the source (2,5,8) and an adjacent floor (3,5,8), bodies carved
     * (y=7..8 at both columns), the source feet cell (2,6,8) set to {@code feet} and the neighbour feet
     * (3,6,8) carved air — a plain one-block walk, so Traverse must emit even with a cobweb feet cell.
     */
    private static NavGridView traverseCourse(BlockState feet) {
        PalettedContainer<BlockState> s = solidStone();
        final int z = 8;
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int y = 7; y <= 8; y++) { s.set(2, y, z, air); s.set(3, y, z, air); }
        s.set(3, 6, z, air);   // neighbour feet clear
        s.set(2, 6, z, feet);  // source feet cell
        // floors (2,5,8) and (3,5,8) stay stone from the fill.
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
