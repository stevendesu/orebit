package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.orebit.mod.Debug;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.StepEdits;
import com.orebit.mod.worldmodel.navblock.NavBlock;

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
 * REPRODUCTION + regression for the completed STATE-AGNOSTIC door model on the ENTRY side (the owner-reported
 * quirk). A door cell is passable (air) in every direction EXCEPT across its single blocked edge, regardless of
 * open/closed state — so a bot must walk STRAIGHT THROUGH a door whose swung panel is NOT across the hallway axis
 * (its blocked edge is a side wall), folding NO edit, whether that door is open or closed.
 *
 * <p>All four cases run in a straight 1-wide EAST-bound hallway (z=6, x=2..12, door at x=7): the bot enters the
 * door cell across its WEST edge (ordinal 3) and leaves across its EAST edge (ordinal 1). "Blocking the hall axis"
 * therefore means the door's blocked edge is E(1) or W(3); "NOT blocking" (⊥ travel) means N(0) or S(2).
 *
 * <ul>
 *   <li><b>Closed, NOT blocking</b> (closed facing SOUTH → blocks NORTH): the owner's quirk — must walk through
 *       FREE (no toggle, no break). This FAILED before the {@code doorEntryClear} fix (the open-only gate rejected
 *       a non-blocking closed door, no toggle fired since it doesn't block the entry edge either → obstacle → no
 *       path for a walk-only bot).
 *   <li><b>Open, NOT blocking</b> (open facing EAST hinge LEFT → blocks NORTH): free walk-through (already worked).
 *   <li><b>Closed, blocking</b> (closed facing EAST → blocks WEST, the entry): toggle-open to pass (entry gate).
 *   <li><b>Open, blocking</b> (open facing NORTH hinge RIGHT → blocks EAST, the exit): toggle-closed to leave
 *       (exit gate — the state-agnostic exit fix).
 * </ul>
 *
 * <p>Every case drives a walk-only + doors.toggle bot (cannot break — so a free crossing PROVES the door read
 * passable, and a toggled crossing PROVES a SET fold, never a smash). Lives in this package to reach {@link
 * NavGridView}'s package-private synthetic constructor (like {@link DoorBackwardsTest}).
 */
class DoorNonBlockingPassageTest {

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

    private static BotCaps caps(boolean canBreak, boolean toggle) {
        return new BotCaps(1, BotCaps.DEFAULT_SAFE_FALL, BotCaps.DEFAULT_MAX_FALL, false,
                BotCaps.DEFAULT_COST_PER_HITPOINT, canBreak, false,
                100, false, BotCaps.DEFAULT_MAX_NODES, 1.0f, toggle);
    }

    /** Walk-only (cannot break) + doors.toggle ON — the whole suite's bot. */
    private static final BotCaps WALK_TOGGLE = caps(false, true);

    private static final int DOOR_X = 7, DOOR_Z = 6;

    // ---- (A) The reported quirk: a CLOSED door NOT blocking the hall axis is walked through FREE -------

    @Test
    void closedDoorNotBlockingHallIsWalkedThroughFree() {
        // Closed OAK door facing SOUTH → blocks NORTH (⊥ the E-W hallway). Neither the WEST entry nor the EAST
        // exit crosses the panel, so the bot walks straight through — NO toggle, NO break. (Pre-fix: the open-only
        // doorEntryClear rejected this, and doorSetClears didn't fire — the door blocks neither crossed edge — so a
        // walk-only bot got no candidate and NO PATH. This is the case that FAILED.)
        NavGridView grid = doorGrid(Blocks.OAK_DOOR, Direction.SOUTH, false, DoorHingeSide.LEFT);
        // Sanity: the door really blocks NORTH (ordinal 0), not the E(1)/W(3) hall axis.
        assertEquals(0, NavBlock.doorBlockedEdge(grid.descriptorAt(DOOR_X, 1, DOOR_Z)),
                "fixture sanity: closed facing SOUTH blocks NORTH (⊥ the hallway)");

        BlockPathPlan plan = BlockPathfinder.findPath(grid, new BlockPos(2, 0, DOOR_Z),
                new BlockPos(12, 0, DOOR_Z), WALK_TOGGLE);

        assertNotNull(plan, "a walk-only bot must reach the goal — a non-blocking closed door is a free passage");
        assertReachedEast(plan);
        assertTrue(contains(plan, DOOR_X, 1, DOOR_Z), "the path passes through the door column");
        assertTrue(contains(plan, DOOR_X + 1, 1, DOOR_Z), "the path crosses PAST the door (reaches the east side)");
        assertEquals(0, totalBreaks(plan), "nothing is mined — the closed door is not an obstacle here");
        assertEquals(0, totalDoorSets(plan), "NO toggle either — the panel is on a side wall, so passage is free");
        assertDoorPlanValid(grid, plan);
    }

    // ---- (B) An OPEN door NOT blocking the hall axis is walked through FREE (already worked) ----------

    @Test
    void openDoorNotBlockingHallIsWalkedThroughFree() {
        // Open OAK door facing EAST hinge LEFT → blocks NORTH (⊥ travel). Free walk-through, no edit.
        NavGridView grid = doorGrid(Blocks.OAK_DOOR, Direction.EAST, true, DoorHingeSide.LEFT);
        assertEquals(0, NavBlock.doorBlockedEdge(grid.descriptorAt(DOOR_X, 1, DOOR_Z)),
                "fixture sanity: open facing EAST hinge LEFT blocks NORTH (⊥ the hallway)");

        BlockPathPlan plan = BlockPathfinder.findPath(grid, new BlockPos(2, 0, DOOR_Z),
                new BlockPos(12, 0, DOOR_Z), WALK_TOGGLE);

        assertNotNull(plan, "a walk-only bot reaches the goal through an open non-blocking door");
        assertReachedEast(plan);
        assertTrue(contains(plan, DOOR_X + 1, 1, DOOR_Z), "the path crosses past the open door");
        assertEquals(0, totalBreaks(plan), "an open door is never mined");
        assertEquals(0, totalDoorSets(plan), "an open door not blocking the walk is crossed with NO toggle");
        assertDoorPlanValid(grid, plan);
    }

    // ---- (C) A CLOSED door BLOCKING the hall axis is toggled open to pass (entry gate) ----------------

    @Test
    void closedDoorBlockingHallIsToggledOpen() {
        // Closed OAK door facing EAST → blocks WEST (the bot's entry edge). doors.toggle folds a SET_OPEN on entry.
        NavGridView grid = doorGrid(Blocks.OAK_DOOR, Direction.EAST, false, DoorHingeSide.LEFT);
        assertEquals(3, NavBlock.doorBlockedEdge(grid.descriptorAt(DOOR_X, 1, DOOR_Z)),
                "fixture sanity: closed facing EAST blocks WEST (the entry edge / hall axis)");

        BlockPathPlan plan = BlockPathfinder.findPath(grid, new BlockPos(2, 0, DOOR_Z),
                new BlockPos(12, 0, DOOR_Z), WALK_TOGGLE);

        assertNotNull(plan, "a walk-only+toggle bot opens the closed doorway and reaches the goal");
        assertReachedEast(plan);
        assertTrue(contains(plan, DOOR_X + 1, 1, DOOR_Z), "the path crosses past the (opened) door");
        assertEquals(0, totalBreaks(plan), "the door is OPENED, never smashed");
        assertTrue(totalDoorSets(plan) >= 1, "crossing a blocking closed door folds at least one door SET (toggle)");
        assertDoorPlanValid(grid, plan);
    }

    // ---- (D) An OPEN door BLOCKING the hall axis (backwards) is toggled closed to leave (exit gate) ---

    @Test
    void openDoorBlockingHallIsToggledClosed() {
        // Open OAK door facing NORTH hinge RIGHT → blocks EAST (the bot's EXIT edge). Entry across WEST is free
        // (panel not there); leaving EAST needs the exit gate to fold a SET_CLOSED (which frees the EAST edge).
        NavGridView grid = doorGrid(Blocks.OAK_DOOR, Direction.NORTH, true, DoorHingeSide.RIGHT);
        assertEquals(1, NavBlock.doorBlockedEdge(grid.descriptorAt(DOOR_X, 1, DOOR_Z)),
                "fixture sanity: open facing NORTH hinge RIGHT blocks EAST (the exit edge / hall axis)");

        BlockPathPlan plan = BlockPathfinder.findPath(grid, new BlockPos(2, 0, DOOR_Z),
                new BlockPos(12, 0, DOOR_Z), WALK_TOGGLE);

        assertNotNull(plan, "a walk-only+toggle bot passes an open backwards door by toggling it closed");
        assertReachedEast(plan);
        assertTrue(contains(plan, DOOR_X + 1, 1, DOOR_Z), "the path crosses past the door");
        assertEquals(0, totalBreaks(plan), "the door is toggled, never broken");
        assertTrue(totalDoorSets(plan) >= 1, "leaving through the blocked edge folds at least one door SET (toggle)");
        assertDoorPlanValid(grid, plan);
    }

    // ---- door-state validity oracle (mirrors DoorBackwardsTest) --------------------------------------

    private static void assertDoorPlanValid(NavGridView grid, BlockPathPlan plan) {
        String bad = doorPlanInvalidity(grid, plan);
        assertTrue(bad == null, "the plan walks through a blocked door panel: " + bad);
    }

    /** Returns a description of the first invalid door crossing, or {@code null} if the whole plan is valid. */
    private static String doorPlanInvalidity(NavGridView grid, BlockPathPlan plan) {
        for (int i = 1; i < plan.size(); i++) {
            BlockPos from = plan.waypoint(i - 1);
            BlockPos to = plan.waypoint(i);
            int dx = to.getX() - from.getX();
            int dz = to.getZ() - from.getZ();
            if (to.getY() != from.getY() || Math.abs(dx) + Math.abs(dz) != 1) continue; // cardinal same-Y steps
            int departEdge = ordinalOf(dx, dz);
            int entryEdge = ordinalOf(-dx, -dz);
            String s = checkDoorCell(grid, plan, i, from.getX(), from.getY(), from.getZ(), departEdge, "depart");
            if (s != null) return s;
            String t = checkDoorCell(grid, plan, i, to.getX(), to.getY(), to.getZ(), entryEdge, "enter");
            if (t != null) return t;
        }
        return null;
    }

    private static String checkDoorCell(NavGridView grid, BlockPathPlan plan, int stepUpTo,
                                        int x, int y, int z, int crossedEdge, String kind) {
        long gridD = grid.descriptorAt(x, y, z);
        if (!NavBlock.isDoor(gridD)) return null;
        boolean open = NavBlock.doorOpen(gridD);
        long cell = BlockPos.asLong(x, y, z);
        for (int i = 0; i <= stepUpTo; i++) {
            StepEdits e = plan.edits(i);
            if (e == null) continue;
            for (int j = 0; j < e.doorSetCount(); j++) {
                if (e.doorSetAt(j) == cell) open = e.doorSetOpenAt(j); // latest-wins in plan order
            }
        }
        int blocked = NavBlock.doorBlockedEdge(NavBlock.withDoorOpen(gridD, open));
        if (blocked == crossedEdge) {
            return "step " + stepUpTo + " (" + kind + ") at door (" + x + "," + y + "," + z
                    + ") open=" + open + " blocks edge " + blocked + " == crossed edge " + crossedEdge;
        }
        return null;
    }

    /** (dx,dz) → cardinal ordinal (0=N 1=E 2=S 3=W), mirroring MovementContext.ordinalOf. */
    private static int ordinalOf(int dx, int dz) {
        if (dz < 0) return 0;
        if (dx > 0) return 1;
        if (dz > 0) return 2;
        return 3;
    }

    // ---- helpers ------------------------------------------------------------------------------------

    private static void assertReachedEast(BlockPathPlan plan) {
        BlockPos last = plan.waypoint(plan.size() - 1);
        assertTrue(Math.abs(last.getX() - 12) <= 1 && last.getZ() == DOOR_Z,
                "the plan should end at the east goal; ended at " + last);
    }

    private static int totalBreaks(BlockPathPlan plan) {
        int breaks = 0;
        for (int i = 0; i < plan.size(); i++) {
            StepEdits e = plan.edits(i);
            if (e != null) breaks += e.breakCount();
        }
        return breaks;
    }

    private static int totalDoorSets(BlockPathPlan plan) {
        int sets = 0;
        for (int i = 0; i < plan.size(); i++) {
            StepEdits e = plan.edits(i);
            if (e != null) sets += e.doorSetCount();
        }
        return sets;
    }

    private static boolean contains(BlockPathPlan plan, int x, int y, int z) {
        for (int i = 0; i < plan.size(); i++) {
            BlockPos wp = plan.waypoint(i);
            if (wp.getX() == x && wp.getY() == y && wp.getZ() == z) return true;
        }
        return false;
    }

    // ---- grid builders (a straight 1-wide east hallway with one door) -------------------------------

    private static BlockState door(Block block, Direction facing, boolean open, DoorHingeSide hinge,
                                   DoubleBlockHalf half) {
        return block.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
                .setValue(BlockStateProperties.OPEN, open)
                .setValue(BlockStateProperties.DOOR_HINGE, hinge)
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, half);
    }

    private static NavGridView doorGrid(Block block, Direction facing, boolean open, DoorHingeSide hinge) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        PalettedContainer<BlockState> s = filledStone(air, stone);
        for (int x = 2; x <= 12; x++) { s.set(x, 1, DOOR_Z, air); s.set(x, 2, DOOR_Z, air); }
        s.set(DOOR_X, 1, DOOR_Z, door(block, facing, open, hinge, DoubleBlockHalf.LOWER));
        s.set(DOOR_X, 2, DOOR_Z, door(block, facing, open, hinge, DoubleBlockHalf.UPPER));
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
}
