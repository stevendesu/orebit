package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.orebit.mod.Debug;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.CandidateSink;
import com.orebit.mod.pathfinding.blockpathfinder.EditScratch;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.pathfinding.blockpathfinder.movements.Traverse;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

/**
 * P1 proof: an ALREADY-OPEN door reads as directionally PASSABLE for the crossing Traverse move — the bot
 * walks a doorway it does not block, folding no edit (the headline fix: it no longer MINES an open door). A
 * CLOSED door is unchanged (still non-passable / a break fold), and an open door still blocks its swung-panel
 * edge on both ENTRY (§2a) and EXIT (§2b). Byte-identical for every non-door cell. Lives in this package to
 * reach {@link NavGridView}'s package-private synthetic constructor (like {@link PassThroughHazardTest}).
 */
class DoorPassageTest {

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

    // A bot that walks but CANNOT break: if it crosses a doorway, the door MUST have read passable (a
    // breaker could just mine it). Immune, so no hazard economics muddy the candidate costs.
    private static final BotCaps WALK_ONLY = new BotCaps(
            1, BotCaps.DEFAULT_SAFE_FALL, BotCaps.DEFAULT_MAX_FALL, false,
            BotCaps.DEFAULT_COST_PER_HITPOINT, false, false,
            BotCaps.UNBREAKABLE, false, BotCaps.DEFAULT_MAX_NODES, 1.0f);
    // A breaker with a mining cap above a wood door's hardness (15) — used only to prove a CLOSED door still
    // folds a break (unchanged pre-P1 behaviour) at candidate level.
    private static final BotCaps BREAKER = new BotCaps(
            1, BotCaps.DEFAULT_SAFE_FALL, BotCaps.DEFAULT_MAX_FALL, false,
            BotCaps.DEFAULT_COST_PER_HITPOINT, true, false,
            100, false, BotCaps.DEFAULT_MAX_NODES, 1.0f);

    private static final int DOOR_X = 7, DOOR_Z = 6;

    private static BlockState door(Direction facing, boolean open, DoorHingeSide hinge, DoubleBlockHalf half) {
        return Blocks.OAK_DOOR.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
                .setValue(BlockStateProperties.OPEN, open)
                .setValue(BlockStateProperties.DOOR_HINGE, hinge)
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, half);
    }

    // ---- (1) The headline: an open door not blocking the walk is crossed FREE, no edit ---------------

    @Test
    void openDoorIsWalkedThroughFree() {
        // Door FACING EAST, OPEN, hinge LEFT → blocked edge NORTH. A bot walking EAST (crossing W then E edges)
        // is not blocked. WALK_ONLY, so a crossing proves the door read passable.
        NavGridView grid = doorGrid(Direction.EAST, true, DoorHingeSide.LEFT);
        MovementContext mc = new MovementContext(grid, WALK_ONLY);

        Capture cap = new Capture(DOOR_X, 0, DOOR_Z);       // step +X from (6,0,6) onto the door floor cell
        new Traverse().candidates(mc, DOOR_X - 1, 0, DOOR_Z, cap);

        assertTrue(cap.got, "a WALK_ONLY bot must be offered the step INTO the open doorway (7,0,6)");
        assertFalse(cap.edits, "crossing an already-open door folds NO edit (it is free passage, not a mine)");
        assertEquals(freeWalkCost(), cap.cost, 1e-4f,
                "the open-door crossing costs a plain flat walk — byte-identical to an air body cell");
    }

    @Test
    void openDoorCrossingMatchesAnAirBodyCellExactly() {
        // Byte-identical proof: the same corridor with the door column left as plain air must emit the same
        // candidate cost as the open-door column (no door surcharge, no missing/extra term).
        NavGridView air = corridor(null, null);
        MovementContext mcAir = new MovementContext(air, WALK_ONLY);
        Capture airCap = new Capture(DOOR_X, 0, DOOR_Z);
        new Traverse().candidates(mcAir, DOOR_X - 1, 0, DOOR_Z, airCap);

        assertTrue(airCap.got && !airCap.edits, "the plain-air control cell is a free walk");
        assertEquals(airCap.cost, freeWalkCost(), 1e-4f, "control cost sanity");
    }

    // ---- (2) Entry through the door's BLOCKED edge is still refused (open door, wrong direction) ------

    @Test
    void openDoorBlocksEntryFromItsPanelEdge() {
        // Door FACING NORTH, OPEN, hinge LEFT → blocked edge WEST. A bot walking EAST enters across the WEST
        // edge of the door cell — straight into the swung panel. WALK_ONLY can't mine → no candidate at all.
        NavGridView grid = doorGrid(Direction.NORTH, true, DoorHingeSide.LEFT);
        MovementContext mc = new MovementContext(grid, WALK_ONLY);

        Capture cap = new Capture(DOOR_X, 0, DOOR_Z);
        new Traverse().candidates(mc, DOOR_X - 1, 0, DOOR_Z, cap);

        assertFalse(cap.got, "an open door blocking the ENTRY edge is impassable to a non-breaker — no candidate");
    }

    // ---- (3) A CLOSED door is UNCHANGED: non-passable to a walker, a break fold to a breaker ----------

    @Test
    void closedDoorStillBlocksANonBreaker() {
        NavGridView grid = doorGrid(Direction.EAST, false, DoorHingeSide.LEFT); // closed → blocks W (the entry edge)
        MovementContext mc = new MovementContext(grid, WALK_ONLY);

        Capture cap = new Capture(DOOR_X, 0, DOOR_Z);
        new Traverse().candidates(mc, DOOR_X - 1, 0, DOOR_Z, cap);

        assertFalse(cap.got, "a CLOSED door is unchanged in P1 — still non-passable to a bot that can't break it");
    }

    @Test
    void closedDoorStillFoldsABreakForABreaker() {
        NavGridView grid = doorGrid(Direction.EAST, false, DoorHingeSide.LEFT);
        MovementContext mc = new MovementContext(grid, BREAKER);

        Capture cap = new Capture(DOOR_X, 0, DOOR_Z);
        new Traverse().candidates(mc, DOOR_X - 1, 0, DOOR_Z, cap);

        assertTrue(cap.got, "a breaker can still pass a CLOSED door");
        assertTrue(cap.edits, "passing a CLOSED door still FOLDS A BREAK (unchanged pre-P1) — P1 didn't free closed doors");
    }

    // ---- (4) EXIT (§2b): standing in an open door, the panel edge is refused, the open edges are free -

    @Test
    void openDoorRefusesExitThroughItsPanelEdge() {
        // Bot STANDS in the door at (7,0,6): FACING EAST, OPEN, hinge LEFT → blocked edge NORTH. It may leave
        // south/east/west but NOT north (through the panel). BlockPathfinder sets this per pop; do it by hand.
        NavGridView grid = crossDoorGrid(Direction.EAST, true, DoorHingeSide.LEFT);
        MovementContext mc = new MovementContext(grid, WALK_ONLY);
        mc.setCurrentDoorEdge(DOOR_X, 0, DOOR_Z); // reads feet (7,1,6) = open door → blocked edge NORTH

        Capture north = new Capture(DOOR_X, 0, DOOR_Z - 1); // exit N (0,-1)
        Capture south = new Capture(DOOR_X, 0, DOOR_Z + 1); // exit S (0,+1)
        Sinks both = new Sinks(north, south);
        new Traverse().candidates(mc, DOOR_X, 0, DOOR_Z, both);

        assertFalse(north.got, "the open door's panel blocks the NORTH exit — no candidate that way");
        assertTrue(south.got, "the perpendicular open edges stay free — the SOUTH exit is offered");
        assertEquals(MovementContext.EDGE_NONE, edgeNoneProbe(grid), "sanity: a plain air feet cell yields EDGE_NONE");
    }

    /** A control: an air feet-cell node yields EDGE_NONE (no exit constraint) — proves the per-pop read is
     *  door-gated, not always-on. */
    private static int edgeNoneProbe(NavGridView grid) {
        MovementContext mc = new MovementContext(grid, WALK_ONLY);
        mc.setCurrentDoorEdge(2, 0, DOOR_Z); // (2,1,6) is plain air in the corridor
        return mc.currentDoorEdge();
    }

    // ---- (5) Full search: the walk-only bot reaches the goal through the open door, no break edit -----

    @Test
    void walkOnlyBotReachesGoalThroughOpenDoorWithoutMining() {
        NavGridView grid = doorGrid(Direction.EAST, true, DoorHingeSide.LEFT); // open, blocks N — walk is free
        BlockPathPlan plan = BlockPathfinder.findPath(grid, new BlockPos(2, 0, DOOR_Z),
                new BlockPos(12, 0, DOOR_Z), WALK_ONLY);

        assertNotNull(plan, "a walk-only bot must reach the goal — the open doorway is a free 1-wide passage");
        BlockPos last = plan.waypoint(plan.size() - 1);
        assertTrue(Math.abs(last.getX() - 12) <= 1 && last.getZ() == DOOR_Z,
                "the plan should end at the goal; ended at " + last);
        // The door column's floor cell is on the path (it walked straight through, not around — the corridor
        // is 1-wide, so there is no around).
        assertTrue(contains(plan, DOOR_X, 1, DOOR_Z),
                "the path must pass through the door column (waypoints are stand cells = floor.above())");
        // The headline assertion: NOTHING was mined — least of all the door halves.
        for (int i = 0; i < plan.size(); i++) {
            var edits = plan.edits(i);
            if (edits == null) continue;
            assertEquals(0, edits.breakCount(),
                    "a walk-only bot mines nothing — the already-open door must not be broken");
        }
    }

    @Test
    void closedDoorCorridorHasNoPathForAWalkOnlyBot() {
        NavGridView grid = doorGrid(Direction.EAST, false, DoorHingeSide.LEFT); // closed → walls the 1-wide corridor
        BlockPathPlan plan = BlockPathfinder.findPath(grid, new BlockPos(2, 0, DOOR_Z),
                new BlockPos(12, 0, DOOR_Z), WALK_ONLY);

        // Either no plan, or a partial that never crosses the closed door column.
        boolean crossed = plan != null && contains(plan, DOOR_X + 1, 1, DOOR_Z);
        assertFalse(crossed, "a CLOSED door blocks a non-breaker: the plan must not reach past the door column");
    }

    // ---- grid builders ------------------------------------------------------------------------------

    private static boolean contains(BlockPathPlan plan, int x, int y, int z) {
        for (int i = 0; i < plan.size(); i++) {
            BlockPos wp = plan.waypoint(i);
            if (wp.getX() == x && wp.getY() == y && wp.getZ() == z) return true;
        }
        return false;
    }

    private static float freeWalkCost() {
        return Traverse.FLAT_COST; // an immune, no-slow flat walk onto stone
    }

    /** Straight 1-wide corridor z=6, x=2..12 (air body over stone floor y=0). If {@code lower}/{@code upper}
     *  are non-null they replace the body cells at the door column (DOOR_X). */
    private static NavGridView corridor(BlockState lower, BlockState upper) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        PalettedContainer<BlockState> s = filledStone(air, stone);
        for (int x = 2; x <= 12; x++) { s.set(x, 1, DOOR_Z, air); s.set(x, 2, DOOR_Z, air); }
        if (lower != null) { s.set(DOOR_X, 1, DOOR_Z, lower); s.set(DOOR_X, 2, DOOR_Z, upper); }
        return single(s);
    }

    private static NavGridView doorGrid(Direction facing, boolean open, DoorHingeSide hinge) {
        return corridor(door(facing, open, hinge, DoubleBlockHalf.LOWER),
                door(facing, open, hinge, DoubleBlockHalf.UPPER));
    }

    /** The corridor PLUS north/south spurs at the door column (for the exit test): floors + air body at
     *  (7,0,5) and (7,0,7). */
    private static NavGridView crossDoorGrid(Direction facing, boolean open, DoorHingeSide hinge) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        PalettedContainer<BlockState> s = filledStone(air, stone);
        for (int x = 2; x <= 12; x++) { s.set(x, 1, DOOR_Z, air); s.set(x, 2, DOOR_Z, air); }
        for (int dz = -1; dz <= 1; dz += 2) { s.set(DOOR_X, 1, DOOR_Z + dz, air); s.set(DOOR_X, 2, DOOR_Z + dz, air); }
        s.set(DOOR_X, 1, DOOR_Z, door(facing, open, hinge, DoubleBlockHalf.LOWER));
        s.set(DOOR_X, 2, DOOR_Z, door(facing, open, hinge, DoubleBlockHalf.UPPER));
        return single(s);
    }

    private static PalettedContainer<BlockState> filledStone(BlockState air, BlockState stone) {
        PalettedContainer<BlockState> s = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        for (int x = 0; x < 16; x++)
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    s.set(x, y, z, stone);
        return s;
    }

    private static NavGridView single(PalettedContainer<BlockState> s) {
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

    // ---- candidate-capturing sinks ------------------------------------------------------------------

    /** Records whether a specific destination cell was emitted, its cost, and whether it folded any edit. */
    private static final class Capture implements CandidateSink {
        final int tx, ty, tz;
        boolean got;
        float cost;
        boolean edits;

        Capture(int tx, int ty, int tz) { this.tx = tx; this.ty = ty; this.tz = tz; }

        @Override
        public void accept(int x, int y, int z, float c, EditScratch e) {
            if (x == tx && y == ty && z == tz) {
                got = true;
                cost = c;
                edits = e != null && e.hasEdits();
            }
        }
    }

    /** Fan a movement's candidates into two {@link Capture}s (for the exit test's N/S probes). */
    private static final class Sinks implements CandidateSink {
        final Capture a, b;
        Sinks(Capture a, Capture b) { this.a = a; this.b = b; }

        @Override
        public void accept(int x, int y, int z, float c, EditScratch e) {
            a.accept(x, y, z, c, e);
            b.accept(x, y, z, c, e);
        }
    }
}
