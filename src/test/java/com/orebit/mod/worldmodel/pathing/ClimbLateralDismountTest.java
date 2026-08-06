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
 * The <b>lateral dismount</b> (Climb §3.7) — easing sideways OUT of a climbable onto the ledge beside it,
 * and the vocabulary that surrounds it. Owner geometry, 2026-08-05.
 *
 * <h2>The gap</h2>
 * Every sideways move refuses a hang at its own gate: {@code Traverse}/{@code Descend} want a standable
 * source floor, and {@code Ascend}/{@code Parkour}/{@code WalkOff} want {@code solidFooting}, which a
 * climbable feet cell fails by R1. The grab loop only ever emitted into cells that were <i>themselves</i>
 * climbable. So a bot on a vine could climb the column or drop off its bottom — it could not step onto the
 * ledge beside it, and A* paid for a place-a-block-and-walk detour instead of one sneak sideways.
 *
 * <h2>The fixture (owner's curtain, {@code z=8}, vine column x=2, target column x=3)</h2>
 * <pre>
 *          x=2   x=3
 *   y=9     V     L      &larr; ceiling of the 2-tall gap
 *   y=8     V     A      &larr; target HEAD
 *   y=7     V     A      &larr; target FEET
 *   y=6     A     L      &larr; target FLOOR  (node (3,6))
 *   y=5     L     L
 * </pre>
 * Two stances start here, and the point of the arc is that BOTH reach the gap cheaply:
 * <ul>
 *   <li><b>Hanging</b> at feet {@code (2,7)} — node {@code (2,6)}, floor air. One §3.7 dismount east.
 *   <li><b>Standing</b> on the leaf at {@code (2,5)} — feet {@code (2,6)} air, head {@code (2,7)} vine.
 *       {@code Ascend} is legal here (the vine is in the HEAD cell, and {@code solidFooting} tests the FEET
 *       cell), and §3.3's jump-grab additionally offers the two-waypoint route into the vine and out.
 * </ul>
 *
 * <h2>Vine at the bottom — the variant</h2>
 * Filling {@code (2,6)} with vine moves the climbable INTO the standing bot's feet cell, which is the
 * stance R1 exists to refuse: vanilla truncates that jump to the {@code 0.2} climb. {@code Ascend} must
 * disappear, and the route becomes climb-up-then-dismount.
 */
class ClimbLateralDismountTest {

    private static final int Z = 8;
    private static final int VINE_X = 2, LEDGE_X = 3;
    /** The hang: feet in {@code (2,7)}, floor {@code (2,6)} is air. */
    private static final int HANG_Y = 6;
    /** The destination: floor {@code (3,6)} is leaf, feet {@code (3,7)} and head {@code (3,8)} are air. */
    private static final int DEST_Y = 6;
    /** The standing stance one below the hang: on the leaf at {@code (2,5)}. */
    private static final int STAND_Y = 5;

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

    /** The substrate the whole fixture rests on — asserted so a classifier change cannot silently void it. */
    @Test
    void leavesAreAFloorAndVinesAreNot() {
        long leaf = NavBlock.descriptorFor(Blocks.JUNGLE_LEAVES.defaultBlockState());
        assertTrue(NavBlock.isStandable(leaf), "a jungle leaf is a full cube — the ledge the bot lands on");
        long vine = NavBlock.descriptorFor(Blocks.VINE.defaultBlockState());
        assertTrue(NavBlock.isClimbable(vine), "the column");
        assertFalse(NavBlock.isStandable(vine), "...and nothing to stand on, so a vine node is a true HANG");
    }

    // ---- §3.7: the gap itself --------------------------------------------------------------------

    @Test
    void aHangDismountsSidewaysOntoTheLedge() {
        assertTrue(emits(MovementRegistry.CLIMB, curtain(), VINE_X, HANG_Y, LEDGE_X, DEST_Y),
                "THE GAP: from a hang, one sneak sideways reaches the standable ledge at the same feet "
                        + "height. Before §3.7 no movement emitted this edge at all, so A* priced a "
                        + "place-a-block-and-walk detour instead");
    }

    /**
     * What a hang can and cannot already do — the measurement that reframed this arc (2026-08-05).
     *
     * <p>The JUMP moves refuse a hang, as designed. {@code Traverse} does NOT: it never gates on its source
     * floor, so it emits the identical hang&nbsp;&rarr;&nbsp;ledge step at {@code FLAT_COST} and dominates
     * §3.7's cling. So the lateral exit was never a MISSING edge — it was a MIS-PRICED one. Pinned here so
     * the day Traverse is gated to a standable source floor, this test states what changed and why.
     */
    @Test
    void aHangsJumpExitsAreRefusedButTraverseIsNot() {
        NavGridView g = curtain();
        assertFalse(emits(MovementRegistry.ASCEND, g, VINE_X, HANG_Y),
                "R1: no jump launches from a climbable stance");
        assertFalse(emits(MovementRegistry.PARKOUR, g, VINE_X, HANG_Y),
                "same gate");
        assertTrue(emits(MovementRegistry.TRAVERSE, g, VINE_X, HANG_Y, LEDGE_X, DEST_Y),
                "CURRENT BEHAVIOUR, and the open question: a flat WALK is emitted straight out of a hang. "
                        + "Vanilla clamps on-climbable horizontal motion to 0.15 b/t and sinks the bot at "
                        + "0.15/t without a sneak, so this step is neither walk-speed nor height-holding — "
                        + "it is the sneak §3.7 prices at 15.44, being sold for 4.633");
    }

    // ---- the stance one lower: Ascend is NOT blocked when the vine is in the HEAD cell ------------

    @Test
    void standingUnderAHangingVineStillAscends() {
        NavGridView g = curtain();
        assertTrue(new MovementContext(g, BotCaps.DEFAULT).solidFooting(VINE_X, STAND_Y, Z),
                "the bot stands on a leaf and its FEET cell is air — the vine is in its HEAD cell, which is "
                        + "neither physical jump-inhibitor, so solidFooting passes");
        assertTrue(emits(MovementRegistry.ASCEND, g, VINE_X, STAND_Y, LEDGE_X, DEST_Y),
                "so a single Ascend into the gap is emitted; R1 never fired on this geometry");
    }

    @Test
    void andJumpGrabOffersTheTwoWaypointRouteToo() {
        assertTrue(emits(MovementRegistry.CLIMB, curtain(), VINE_X, STAND_Y, VINE_X, HANG_Y),
                "§3.3 already models 'jump into the vine': from full-faced footing a grounded jump lifts the "
                        + "feet across ONE air cell into the climbable overhead. Paired with §3.7 that is the "
                        + "jump-in-then-step-out route, without touching Ascend");
    }

    // ---- vine at the bottom: R1 fires, and climb-then-dismount takes over -------------------------

    @Test
    void vineInTheFeetCellRefusesAscendAndRoutesThroughTheColumn() {
        NavGridView g = wrap(curtainWithVineAtBottom());
        assertFalse(new MovementContext(g, BotCaps.DEFAULT).solidFooting(VINE_X, STAND_Y, Z),
                "now the climbable IS the feet cell: vanilla truncates the 0.42 impulse to the 0.2 climb");
        assertFalse(emits(MovementRegistry.ASCEND, g, VINE_X, STAND_Y, LEDGE_X, DEST_Y),
                "so R1 correctly deletes the one-move Ascend");
        assertFalse(emits(MovementRegistry.CLIMB, g, VINE_X, STAND_Y, VINE_X, HANG_Y + 1),
                "and §3.3's jump-grab is unreachable from a climbable stance — no jump of any kind");

        // ...leaving exactly the two-move route.
        assertTrue(emits(MovementRegistry.CLIMB, g, VINE_X, STAND_Y, VINE_X, HANG_Y),
                "climb up one: the feet move from the vine at (2,6) into the vine at (2,7)");
        assertTrue(emits(MovementRegistry.CLIMB, g, VINE_X, HANG_Y, LEDGE_X, DEST_Y),
                "then dismount east onto the ledge — climb-then-lateral, as expected");
    }

    // ---- the guards that must survive -------------------------------------------------------------

    /**
     * The cling flood the standability guard exists to stop. A vine hanging over walkable ground must not
     * offer a lateral cling when a plain walk already reaches the same cell — §3.7 deliberately did NOT
     * touch this arm (it only added the non-climbable destination), so the suppression is unchanged.
     */
    @Test
    void clingingIntoAVineOverWalkableGroundIsStillRefused() {
        PalettedContainer<BlockState> s = curtainStates();
        // Give the LEDGE column a vine in its feet cell, directly over its standable leaf floor.
        s.set(LEDGE_X, DEST_Y + 1, Z, Blocks.VINE.defaultBlockState());
        assertFalse(emits(MovementRegistry.CLIMB, wrap(s), VINE_X, HANG_Y, LEDGE_X, DEST_Y),
                "the destination feet cell is climbable AND has standable footing under it — the walk owns "
                        + "it. Re-opening this is the measured flood, and §3.7 must not");
    }

    /** A grounded bot beside the column has a walk available, so it gets no cling and no dismount. */
    @Test
    void aGroundedStanceGetsNoLateralClimb() {
        PalettedContainer<BlockState> s = curtainStates();
        s.set(VINE_X, HANG_Y, Z, Blocks.JUNGLE_LEAVES.defaultBlockState()); // give the hang a real floor
        assertFalse(emits(MovementRegistry.CLIMB, wrap(s), VINE_X, HANG_Y, LEDGE_X, DEST_Y),
                "with a standable floor under it the bot is standing, not hanging — Traverse owns the step, "
                        + "and duplicating it here would double-count the arbitration");
    }

    /**
     * Ground one cell LOWER is a drop, not a cling. Emitting it would land the bot at a node whose floor is
     * air, which is Fall's job — the guard's {@code belowStandable} arm deliberately has no §3.7 mirror.
     */
    @Test
    void aLedgeOneCellLowerIsNotADismount() {
        PalettedContainer<BlockState> s = curtainStates();
        s.set(LEDGE_X, DEST_Y, Z, Blocks.AIR.defaultBlockState()); // floor drops one; (3,5) leaf remains
        assertFalse(emits(MovementRegistry.CLIMB, wrap(s), VINE_X, HANG_Y, LEDGE_X, DEST_Y),
                "nothing catches the feet at THIS height, so the sideways ease would be a fall");
    }

    @Test
    void aSealedDestinationIsNotADismount() {
        PalettedContainer<BlockState> s = curtainStates();
        s.set(LEDGE_X, DEST_Y + 2, Z, Blocks.STONE.defaultBlockState()); // plug the head cell
        assertFalse(emits(MovementRegistry.CLIMB, wrap(s), VINE_X, HANG_Y, LEDGE_X, DEST_Y),
                "the bot's body has to fit where it lands");
    }

    // ---- helpers ---------------------------------------------------------------------------------

    private static boolean emits(Movement move, NavGridView grid, int sx, int sy, int tx, int ty) {
        MovementContext ctx = new MovementContext(grid, BotCaps.DEFAULT);
        boolean[] hit = { false };
        move.candidates(ctx, sx, sy, Z, new CandidateSink() {
            @Override
            public void accept(int x, int y, int z, float cost, EditScratch edits) {
                if (x == tx && y == ty && z == Z) hit[0] = true;
            }
        });
        return hit[0];
    }

    private static boolean emits(Movement move, NavGridView grid, int sx, int sy) {
        MovementContext ctx = new MovementContext(grid, BotCaps.DEFAULT);
        int[] n = { 0 };
        move.candidates(ctx, sx, sy, Z, new CandidateSink() {
            @Override
            public void accept(int x, int y, int z, float cost, EditScratch edits) {
                n[0]++;
            }
        });
        return n[0] > 0;
    }

    private static NavGridView curtain() {
        return wrap(curtainStates());
    }

    /** The owner's VL/VA/VA/AL/LL curtain, hollowed out of solid stone at {@code z=8}. */
    private static PalettedContainer<BlockState> curtainStates() {
        PalettedContainer<BlockState> s = solidStone();
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState leaf = Blocks.JUNGLE_LEAVES.defaultBlockState();
        BlockState vine = Blocks.VINE.defaultBlockState();
        // Hollow a hall so nothing outside the two columns can supply footing or block a head.
        for (int x = 1; x <= 4; x++) {
            for (int y = 5; y <= 12; y++) s.set(x, y, Z, air);
        }
        s.set(VINE_X, 5, Z, leaf);                     // LL floor, left
        s.set(LEDGE_X, 5, Z, leaf);                    // LL floor, right
        s.set(LEDGE_X, 6, Z, leaf);                    // AL  -> the destination FLOOR
        for (int y = 7; y <= 9; y++) s.set(VINE_X, y, Z, vine);  // the column
        s.set(LEDGE_X, 9, Z, leaf);                    // VL  -> the gap's ceiling
        return s;
    }

    /** The variant with one more vine at the bottom, putting a climbable in the standing bot's FEET cell. */
    private static PalettedContainer<BlockState> curtainWithVineAtBottom() {
        PalettedContainer<BlockState> s = curtainStates();
        s.set(VINE_X, 6, Z, Blocks.VINE.defaultBlockState());
        return s;
    }

    private static PalettedContainer<BlockState> solidStone() {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        PalettedContainer<BlockState> s = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) s.set(x, y, z, stone);
            }
        }
        return s;
    }

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
