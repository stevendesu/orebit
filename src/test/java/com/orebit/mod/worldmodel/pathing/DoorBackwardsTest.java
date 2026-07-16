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
 * REPRODUCTION + regression for the STATE-AGNOSTIC door toggle. A door that is ALREADY OPEN and whose swung
 * panel blocks the edge the bot must cross has to be CLOSED (toggled) by the search — a "backwards"/zombie-proof
 * door left open, blocking a doorway, BEFORE any bot action. The toggle machinery must be symmetric: whether the
 * feet door reads open or closed, if it blocks the edge being crossed the search folds a toggle to the OPPOSITE
 * state at DOOR_TOGGLE_COST (which clears that edge). Both the ENTRY gate ({@code doorSetClears}) and the EXIT
 * gate ({@code setCurrentDoorEdge}/{@code foldExitDoorToggle}) must fire for ANY door blocking the crossed edge.
 *
 * <p>The prior asymmetry: the exit gate registered a blocked edge only for an OPEN feet door and only ever folded
 * a SET_CLOSED. A corner where the ENTRY toggle CLOSES an initially-open door leaves the bot standing in a
 * now-CLOSED door whose panel blocks the exit edge; the exit gate did not fire (feet reads closed) and the search
 * emitted a step that walks straight through the closed panel — a physically INVALID plan. The {@link
 * #assertDoorPlanValid} oracle replays the plan's door edits and fails on exactly that crossing.
 *
 * <p>Drives a walk-only + doors.toggle bot (cannot break — so any crossing PROVES a toggle fold, never a smash).
 * Lives in this package to reach {@link NavGridView}'s package-private synthetic constructor (like {@link
 * DoorToggleTest}).
 */
class DoorBackwardsTest {

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

    private static final BotCaps WALK_TOGGLE = caps(false, true);

    private static final int DOOR_X = 7, DOOR_Z = 6;

    // ================================================================================================
    // (1) The tight repro: the 1-wide 90° corner with an already-OPEN door facing WEST hinge LEFT. Open,
    //     it blocks SOUTH — the bot's entry edge — so entry toggles it CLOSED; closed (facing W) it then
    //     blocks EAST, the exit edge. The exit MUST re-toggle (SET_OPEN) or the plan walks through a shut
    //     door. This is the case the old always-SET_CLOSED / open-only exit gate got WRONG.
    // ================================================================================================

    @Test
    void openBackwardsCornerDoorFoldsAStateAgnosticExitToggle() {
        NavGridView grid = cornerGrid(Direction.WEST, true, DoorHingeSide.LEFT); // open blocks S (entry edge)
        BlockPathPlan plan = BlockPathfinder.findPath(grid, new BlockPos(DOOR_X, 0, 8),
                new BlockPos(9, 0, DOOR_Z), WALK_TOGGLE);
        assertNotNull(plan, "the walk-only+toggle bot rounds the corner past an already-open backwards door");
        BlockPos last = plan.waypoint(plan.size() - 1);
        assertTrue(Math.abs(last.getX() - 9) <= 1 && last.getZ() == DOOR_Z, "ends at the east goal; ended " + last);
        assertEquals(0, totalBreaks(plan), "the corner is rounded by toggling, never by breaking the door");
        assertDoorPlanValid(grid, plan); // FAILS on the old code: wp exits east through a closed panel, no toggle
    }

    // ================================================================================================
    // (2) Straight east corridor: an already-OPEN oak door in EVERY orientation must yield a VALID path to a
    //     walk-only+toggle bot (no breaks, no crossing through a blocked panel).
    // ================================================================================================

    @Test
    void openDoorEveryOrientationYieldsAValidEastboundPath() {
        StringBuilder fails = new StringBuilder();
        for (Direction facing : new Direction[] { Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST }) {
            for (DoorHingeSide hinge : new DoorHingeSide[] { DoorHingeSide.LEFT, DoorHingeSide.RIGHT }) {
                NavGridView grid = doorGrid(Blocks.OAK_DOOR, facing, true, hinge);
                BlockPathPlan plan = BlockPathfinder.findPath(grid, new BlockPos(2, 0, DOOR_Z),
                        new BlockPos(12, 0, DOOR_Z), WALK_TOGGLE);
                String tag = "open " + facing + " " + hinge;
                if (plan == null) { fails.append("\n  ").append(tag).append(": NO PATH"); continue; }
                BlockPos last = plan.waypoint(plan.size() - 1);
                boolean reached = Math.abs(last.getX() - 12) <= 1 && last.getZ() == DOOR_Z;
                boolean crossed = contains(plan, DOOR_X + 1, 1, DOOR_Z);
                int breaks = totalBreaks(plan);
                String invalid = doorPlanInvalidity(grid, plan);
                if (!reached || !crossed || breaks != 0 || invalid != null) {
                    fails.append("\n  ").append(tag)
                            .append(": reached=").append(reached)
                            .append(" crossedPastDoor=").append(crossed)
                            .append(" breaks=").append(breaks)
                            .append(invalid == null ? "" : " INVALID:" + invalid);
                }
            }
        }
        assertTrue(fails.length() == 0,
                "every already-open door orientation must yield a valid, break-free eastbound path:" + fails);
    }

    // ================================================================================================
    // (3) Mid-crossing: the search STARTS standing in the open door whose panel blocks the exit edge.
    // ================================================================================================

    @Test
    void searchStartingInOpenDoorBlockingExitClosesItToLeave() {
        // Open oak door facing NORTH, hinge RIGHT → open blocks EAST. The search starts in the doorway and must
        // leave east — the panel is across the exit. The exit gate must fold a toggle (SET_CLOSED) to free it.
        NavGridView grid = crossDoorGrid(Direction.NORTH, true, DoorHingeSide.RIGHT);
        BlockPathPlan plan = BlockPathfinder.findPath(grid, new BlockPos(DOOR_X, 0, DOOR_Z),
                new BlockPos(11, 0, DOOR_Z), WALK_TOGGLE);
        assertNotNull(plan, "a bot standing IN an open door whose panel blocks its exit toggles it and walks out");
        BlockPos last = plan.waypoint(plan.size() - 1);
        assertTrue(last.getX() >= 10 && last.getZ() == DOOR_Z, "ends at the east goal; ended " + last);
        assertEquals(0, totalBreaks(plan), "no breaks — the door is toggled to free the exit");
        assertDoorPlanValid(grid, plan);
    }

    // ---- door-state validity oracle -----------------------------------------------------------------

    /**
     * Assert the plan never crosses a door edge the door blocks in its state at the moment of the crossing.
     * Replays the plan's door SET edits (latest-wins, in plan order) to reconstruct each door cell's state as
     * each cardinal step is taken, then checks BOTH the source door (the cell the bot leaves) against the
     * departure edge and the destination door (the cell it enters) against the entry edge. Door SETs folded on
     * step {@code i} take effect for step {@code i}'s crossing (an entry SET_OPEN opens the door before the bot
     * walks in; an exit toggle clears the door before the bot departs) — matching the executor's per-phase
     * {@code requireDoor} gate.
     */
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
            if (to.getY() != from.getY() || Math.abs(dx) + Math.abs(dz) != 1) continue; // only cardinal, same-Y steps
            int departEdge = ordinalOf(dx, dz);
            int entryEdge = ordinalOf(-dx, -dz);
            // Source door: the cell the bot leaves — its state after edits folded up to and including step i.
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

    private static int totalBreaks(BlockPathPlan plan) {
        int breaks = 0;
        for (int i = 0; i < plan.size(); i++) {
            StepEdits e = plan.edits(i);
            if (e != null) breaks += e.breakCount();
        }
        return breaks;
    }

    private static boolean contains(BlockPathPlan plan, int x, int y, int z) {
        for (int i = 0; i < plan.size(); i++) {
            BlockPos wp = plan.waypoint(i);
            if (wp.getX() == x && wp.getY() == y && wp.getZ() == z) return true;
        }
        return false;
    }

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

    /** The straight corridor PLUS north/south spurs at the door column, so a bot can START in the door. */
    private static NavGridView crossDoorGrid(Direction facing, boolean open, DoorHingeSide hinge) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        PalettedContainer<BlockState> s = filledStone(air, stone);
        for (int x = 2; x <= 12; x++) { s.set(x, 1, DOOR_Z, air); s.set(x, 2, DOOR_Z, air); }
        for (int dz = -1; dz <= 1; dz += 2) { s.set(DOOR_X, 1, DOOR_Z + dz, air); s.set(DOOR_X, 2, DOOR_Z + dz, air); }
        s.set(DOOR_X, 1, DOOR_Z, door(Blocks.OAK_DOOR, facing, open, hinge, DoubleBlockHalf.LOWER));
        s.set(DOOR_X, 2, DOOR_Z, door(Blocks.OAK_DOOR, facing, open, hinge, DoubleBlockHalf.UPPER));
        return single(s);
    }

    private static NavGridView cornerGrid(Direction facing, boolean open, DoorHingeSide hinge) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        PalettedContainer<BlockState> s = filledStone(air, stone);
        for (int z = DOOR_Z; z <= 8; z++) { s.set(DOOR_X, 1, z, air); s.set(DOOR_X, 2, z, air); } // vertical leg
        for (int x = DOOR_X; x <= 9; x++) { s.set(x, 1, DOOR_Z, air); s.set(x, 2, DOOR_Z, air); } // horizontal leg
        s.set(DOOR_X, 1, DOOR_Z, door(Blocks.OAK_DOOR, facing, open, hinge, DoubleBlockHalf.LOWER));
        s.set(DOOR_X, 2, DOOR_Z, door(Blocks.OAK_DOOR, facing, open, hinge, DoubleBlockHalf.UPPER));
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
