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
import com.orebit.mod.pathfinding.blockpathfinder.CandidateSink;
import com.orebit.mod.pathfinding.blockpathfinder.EditScratch;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.pathfinding.blockpathfinder.StepEdits;
import com.orebit.mod.pathfinding.blockpathfinder.movements.Ascend;
import com.orebit.mod.pathfinding.blockpathfinder.movements.Descend;
import com.orebit.mod.pathfinding.blockpathfinder.movements.Traverse;
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
 * REPRODUCTION + regression for extending the STATE-AGNOSTIC door-crossing model (already working for
 * {@link Traverse}) to {@link Ascend} and {@link Descend} — so a doorway a player built OFFSET FROM THE
 * FLOOR (coincident with a step up / down) is passable exactly like a flat one: walk-past a non-blocking
 * door FREE, open/close a blocking one, never smash it.
 *
 * <p><b>The gap this file proves (pre-fix).</b> Ascend/Descend called the door-BLIND {@link
 * MovementContext#requireBodyClear}/{@code requireAir}, so a door in the crossing body column read as a
 * plain obstacle: a walk-only+toggle bot got NO candidate (the closed door is not passable and it can't
 * break) — it could neither walk past a non-blocking offset door nor open a blocking one. Every case below
 * drives a <b>walk-only</b> (cannot break) + {@code doors.toggle} bot, so a free crossing PROVES the door
 * read passable and a toggled crossing PROVES a SET fold — a smash is impossible.
 *
 * <h2>Geometry</h2>
 * <ul>
 *   <li><b>Ascend (raised doorway):</b> west floor y=0, a step UP to an east floor y=1; the door stands ON
 *       the raised floor at column {@code DOOR_X}, occupying its two body cells {@code (DOOR_X, 2..3)}. The
 *       bot Ascends from {@code (DOOR_X-1, 0)} onto {@code (DOOR_X, 1)}, its landing body = the door column.
 *   <li><b>Descend (lowered doorway):</b> west floor y=1, a step DOWN to an east floor y=0; the door stands
 *       ON the low floor at {@code DOOR_X}, occupying {@code (DOOR_X, 1..2)}. The bot Descends from
 *       {@code (DOOR_X-1, 1)} onto {@code (DOOR_X, 0)}, its step-off / landing body = the door column.
 * </ul>
 *
 * <p>Node coords are FLOOR cells (the candidate destination Y is the floor block); the door's LOWER half
 * sits one above the floor it stands on. Lives in this package to reach {@link NavGridView}'s
 * package-private synthetic constructor (like {@link DoorNonBlockingPassageTest}).
 */
class DoorAscendDescendTest {

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
    private static final float FREE_ASCEND = Ascend.COST;                                       // a free step-through
    private static final float TOGGLE_ASCEND = Ascend.COST + MovementContext.DOOR_TOGGLE_COST;  // + one toggle
    private static final float FREE_DESCEND = Descend.COST;
    private static final float TOGGLE_DESCEND = Descend.COST + MovementContext.DOOR_TOGGLE_COST;

    // ================================================================================================
    // ASCEND — a RAISED doorway (door on the step the bot climbs onto)
    // ================================================================================================

    @Test
    void ascendThroughClosedNonBlockingDoorIsFree() {
        // Closed OAK door facing SOUTH → blocks NORTH (⊥ the E-bound climb). The bot Ascends STRAIGHT THROUGH
        // free — no toggle, no break. (Pre-fix: door-blind requireBodyClear tried to break the closed door →
        // a walk-only bot got NO candidate. This is the raised-doorway gap.)
        NavGridView grid = ascendDoorGrid(Blocks.OAK_DOOR, Direction.SOUTH, false, DoorHingeSide.LEFT);
        assertEquals(0, NavBlock.doorBlockedEdge(grid.descriptorAt(DOOR_X, 2, DOOR_Z)),
                "fixture sanity: closed facing SOUTH blocks NORTH (⊥ the climb axis)");

        Capture cap = new Capture(DOOR_X, 1, DOOR_Z);
        new Ascend().candidates(new MovementContext(grid, WALK_TOGGLE), DOOR_X - 1, 0, DOOR_Z, cap);

        assertTrue(cap.got, "a walk-only bot Ascends through a non-blocking offset door — it is a free passage");
        assertTrue(!cap.edits, "no edit: a non-blocking door is walked past, never toggled or broken");
        assertEquals(FREE_ASCEND, cap.cost, 1e-4f, "cost == a plain Ascend (no toggle, no break)");
    }

    @Test
    void ascendThroughClosedBlockingDoorTogglesOpen() {
        // Closed OAK door facing EAST → blocks WEST (the entry edge of the E-bound climb). doors.toggle folds a
        // single SET_OPEN. (Pre-fix: NO candidate — the walk-only bot could not open the raised doorway.)
        NavGridView grid = ascendDoorGrid(Blocks.OAK_DOOR, Direction.EAST, false, DoorHingeSide.LEFT);
        assertEquals(3, NavBlock.doorBlockedEdge(grid.descriptorAt(DOOR_X, 2, DOOR_Z)),
                "fixture sanity: closed facing EAST blocks WEST (the climb entry edge)");

        Capture cap = new Capture(DOOR_X, 1, DOOR_Z);
        new Ascend().candidates(new MovementContext(grid, WALK_TOGGLE), DOOR_X - 1, 0, DOOR_Z, cap);

        assertTrue(cap.got && cap.edits, "the walk-only bot Ascends by OPENING the blocking offset door");
        assertEquals(TOGGLE_ASCEND, cap.cost, 1e-4f, "cost == Ascend + ONE door toggle (≈6), never a smash");
    }

    @Test
    void ascendOutThroughBlockingFeetDoorTogglesClosed() {
        // EXIT side: the bot STANDS IN an open door whose panel blocks its EAST exit, then Ascends up-and-over.
        // Open OAK facing NORTH hinge RIGHT → blocks EAST; leaving EAST folds a SET_CLOSED (the exit gate,
        // shared with Traverse). setCurrentDoorEdge mirrors BlockPathfinder's per-pop call.
        NavGridView grid = ascendExitDoorGrid();
        MovementContext mc = new MovementContext(grid, WALK_TOGGLE);
        mc.setCurrentDoorEdge(DOOR_X - 1, 0, DOOR_Z); // feet door at (DOOR_X-1, 1..2)
        assertEquals(1, mc.currentDoorEdge(), "fixture sanity: the feet door blocks EAST (the exit edge)");

        Capture cap = new Capture(DOOR_X, 1, DOOR_Z);
        new Ascend().candidates(mc, DOOR_X - 1, 0, DOOR_Z, cap);

        assertTrue(cap.got && cap.edits, "the bot Ascends OUT of the doorway by toggling the panel clear");
        assertEquals(TOGGLE_ASCEND, cap.cost, 1e-4f, "cost == Ascend + ONE exit toggle");
    }

    @Test
    void plainAscendWithNoDoorIsUnchanged() {
        // Regression control: an ordinary Ascend (raised floor, NO door) is still a free jump-up candidate.
        NavGridView grid = ascendDoorGrid(null, null, false, null); // null block ⇒ no door placed (plain step)
        Capture cap = new Capture(DOOR_X, 1, DOOR_Z);
        new Ascend().candidates(new MovementContext(grid, WALK_TOGGLE), DOOR_X - 1, 0, DOOR_Z, cap);

        assertTrue(cap.got && !cap.edits, "a plain Ascend onto a clear step is still offered, edit-free");
        assertEquals(FREE_ASCEND, cap.cost, 1e-4f, "a no-door Ascend costs exactly a plain Ascend");
    }

    // ================================================================================================
    // DESCEND — a LOWERED doorway (door on the step the bot drops onto)
    // ================================================================================================

    @Test
    void descendThroughClosedNonBlockingDoorIsFree() {
        // Closed OAK door facing SOUTH → blocks NORTH (⊥ the E-bound descent). Free step-through.
        NavGridView grid = descendDoorGrid(Blocks.OAK_DOOR, Direction.SOUTH, false, DoorHingeSide.LEFT);
        assertEquals(0, NavBlock.doorBlockedEdge(grid.descriptorAt(DOOR_X, 1, DOOR_Z)),
                "fixture sanity: closed facing SOUTH blocks NORTH (⊥ the descent axis)");

        Capture cap = new Capture(DOOR_X, 0, DOOR_Z);
        new Descend().candidates(new MovementContext(grid, WALK_TOGGLE), DOOR_X - 1, 1, DOOR_Z, cap);

        assertTrue(cap.got, "a walk-only bot Descends through a non-blocking offset door — a free passage");
        assertTrue(!cap.edits, "no edit: a non-blocking door is walked past, never toggled or broken");
        assertEquals(FREE_DESCEND, cap.cost, 1e-4f, "cost == a plain Descend (no toggle, no break)");
    }

    @Test
    void descendThroughClosedBlockingDoorTogglesOpen() {
        // Closed OAK door facing EAST → blocks WEST (the descent entry edge). Folds a single SET_OPEN.
        NavGridView grid = descendDoorGrid(Blocks.OAK_DOOR, Direction.EAST, false, DoorHingeSide.LEFT);
        assertEquals(3, NavBlock.doorBlockedEdge(grid.descriptorAt(DOOR_X, 1, DOOR_Z)),
                "fixture sanity: closed facing EAST blocks WEST (the descent entry edge)");

        Capture cap = new Capture(DOOR_X, 0, DOOR_Z);
        new Descend().candidates(new MovementContext(grid, WALK_TOGGLE), DOOR_X - 1, 1, DOOR_Z, cap);

        assertTrue(cap.got && cap.edits, "the walk-only bot Descends by OPENING the blocking offset door");
        assertEquals(TOGGLE_DESCEND, cap.cost, 1e-4f, "cost == Descend + ONE door toggle (≈6), never a smash");
    }

    @Test
    void descendOutThroughBlockingFeetDoorTogglesClosed() {
        // EXIT side: the bot stands in an open door blocking its EAST exit, then steps down-and-over. Folds a
        // SET_CLOSED (the shared exit gate).
        NavGridView grid = descendExitDoorGrid();
        MovementContext mc = new MovementContext(grid, WALK_TOGGLE);
        mc.setCurrentDoorEdge(DOOR_X - 1, 1, DOOR_Z); // feet door at (DOOR_X-1, 2..3)
        assertEquals(1, mc.currentDoorEdge(), "fixture sanity: the feet door blocks EAST (the exit edge)");

        Capture cap = new Capture(DOOR_X, 0, DOOR_Z);
        new Descend().candidates(mc, DOOR_X - 1, 1, DOOR_Z, cap);

        assertTrue(cap.got && cap.edits, "the bot Descends OUT of the doorway by toggling the panel clear");
        assertEquals(TOGGLE_DESCEND, cap.cost, 1e-4f, "cost == Descend + ONE exit toggle");
    }

    @Test
    void plainDescendWithNoDoorIsUnchanged() {
        NavGridView grid = descendDoorGrid(null, null, false, null); // no door — a plain step down
        Capture cap = new Capture(DOOR_X, 0, DOOR_Z);
        new Descend().candidates(new MovementContext(grid, WALK_TOGGLE), DOOR_X - 1, 1, DOOR_Z, cap);

        assertTrue(cap.got && !cap.edits, "a plain Descend onto a clear step is still offered, edit-free");
        assertEquals(FREE_DESCEND, cap.cost, 1e-4f, "a no-door Descend costs exactly a plain Descend");
    }

    // ================================================================================================
    // Full search: the bot reaches the goal across an offset non-blocking doorway, mining NOTHING
    // ================================================================================================

    @Test
    void fullSearchClimbsThroughNonBlockingRaisedDoor() {
        NavGridView grid = ascendDoorGrid(Blocks.OAK_DOOR, Direction.SOUTH, false, DoorHingeSide.LEFT);
        // start on the west low floor (feet y=1), goal on the east raised floor (feet y=2).
        BlockPathPlan plan = BlockPathfinder.findPath(grid, new BlockPos(2, 0, DOOR_Z),
                new BlockPos(11, 1, DOOR_Z), WALK_TOGGLE);
        assertNotNull(plan, "a walk-only bot reaches the raised goal through a non-blocking offset door");
        assertTrue(contains(plan, DOOR_X, 2, DOOR_Z), "the path stands in the (raised) door column");
        assertEquals(0, totalBreaks(plan), "nothing is mined — the door is a free passage");
        assertEquals(0, totalDoorSets(plan), "no toggle — the panel is on a side wall");
    }

    @Test
    void fullSearchDropsThroughNonBlockingLoweredDoor() {
        NavGridView grid = descendDoorGrid(Blocks.OAK_DOOR, Direction.SOUTH, false, DoorHingeSide.LEFT);
        // start on the west high floor (feet y=2), goal on the east low floor (feet y=1).
        BlockPathPlan plan = BlockPathfinder.findPath(grid, new BlockPos(2, 1, DOOR_Z),
                new BlockPos(11, 0, DOOR_Z), WALK_TOGGLE);
        assertNotNull(plan, "a walk-only bot reaches the lowered goal through a non-blocking offset door");
        assertTrue(contains(plan, DOOR_X, 1, DOOR_Z), "the path stands in the (lowered) door column");
        assertEquals(0, totalBreaks(plan), "nothing is mined — the door is a free passage");
        assertEquals(0, totalDoorSets(plan), "no toggle — the panel is on a side wall");
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

    // ---- grid builders ------------------------------------------------------------------------------

    private static BlockState door(Block block, Direction facing, boolean open, DoorHingeSide hinge,
                                   DoubleBlockHalf half) {
        return block.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
                .setValue(BlockStateProperties.OPEN, open)
                .setValue(BlockStateProperties.DOOR_HINGE, hinge)
                .setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, half);
    }

    /**
     * A 1-wide EAST corridor stepping UP one block at column {@code DOOR_X}: floor y=0 for x&lt;DOOR_X, floor
     * y=1 for x&ge;DOOR_X, with (optionally) a door standing ON the raised floor at {@code (DOOR_X, 2..3)}.
     * A {@code null} block leaves the raised step clear (the no-door regression control).
     */
    private static NavGridView ascendDoorGrid(Block block, Direction facing, boolean open, DoorHingeSide hinge) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        PalettedContainer<BlockState> s = filledStone(air, stone);
        // West low floor y=0: body + takeoff head clearance clear (y=1,2,3).
        for (int x = 2; x < DOOR_X; x++) { s.set(x, 1, DOOR_Z, air); s.set(x, 2, DOOR_Z, air); s.set(x, 3, DOOR_Z, air); }
        // East raised floor y=1 (keep y=0,1 stone): body clear (y=2,3).
        for (int x = DOOR_X; x <= 12; x++) { s.set(x, 2, DOOR_Z, air); s.set(x, 3, DOOR_Z, air); }
        if (block != null) {
            s.set(DOOR_X, 2, DOOR_Z, door(block, facing, open, hinge, DoubleBlockHalf.LOWER));
            s.set(DOOR_X, 3, DOOR_Z, door(block, facing, open, hinge, DoubleBlockHalf.UPPER));
        }
        return single(s);
    }

    /**
     * A 1-wide EAST corridor stepping DOWN one block at column {@code DOOR_X}: floor y=1 for x&lt;DOOR_X, floor
     * y=0 for x&ge;DOOR_X, with (optionally) a door standing ON the low floor at {@code (DOOR_X, 1..2)}.
     */
    private static NavGridView descendDoorGrid(Block block, Direction facing, boolean open, DoorHingeSide hinge) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        PalettedContainer<BlockState> s = filledStone(air, stone);
        // West high floor y=1 (keep y=0,1 stone): body clear (y=2,3).
        for (int x = 2; x < DOOR_X; x++) { s.set(x, 2, DOOR_Z, air); s.set(x, 3, DOOR_Z, air); }
        // East low floor y=0: body + step-off head clearance clear (y=1,2,3).
        for (int x = DOOR_X; x <= 12; x++) { s.set(x, 1, DOOR_Z, air); s.set(x, 2, DOOR_Z, air); s.set(x, 3, DOOR_Z, air); }
        if (block != null) {
            s.set(DOOR_X, 1, DOOR_Z, door(block, facing, open, hinge, DoubleBlockHalf.LOWER));
            s.set(DOOR_X, 2, DOOR_Z, door(block, facing, open, hinge, DoubleBlockHalf.UPPER));
        }
        return single(s);
    }

    /**
     * Ascend EXIT fixture: the bot stands IN an open door at {@code (DOOR_X-1, 1..2)} whose panel blocks its
     * EAST exit, with a CLEAR raised step at {@code DOOR_X} (no entry door). Open OAK facing NORTH hinge
     * RIGHT blocks EAST.
     */
    private static NavGridView ascendExitDoorGrid() {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        PalettedContainer<BlockState> s = filledStone(air, stone);
        for (int x = 2; x < DOOR_X; x++) { s.set(x, 1, DOOR_Z, air); s.set(x, 2, DOOR_Z, air); s.set(x, 3, DOOR_Z, air); }
        for (int x = DOOR_X; x <= 12; x++) { s.set(x, 2, DOOR_Z, air); s.set(x, 3, DOOR_Z, air); }
        s.set(DOOR_X - 1, 1, DOOR_Z, door(Blocks.OAK_DOOR, Direction.NORTH, true, DoorHingeSide.RIGHT, DoubleBlockHalf.LOWER));
        s.set(DOOR_X - 1, 2, DOOR_Z, door(Blocks.OAK_DOOR, Direction.NORTH, true, DoorHingeSide.RIGHT, DoubleBlockHalf.UPPER));
        return single(s);
    }

    /**
     * Descend EXIT fixture: the bot stands IN an open door at {@code (DOOR_X-1, 2..3)} (on the high floor y=1)
     * whose panel blocks its EAST exit, with a CLEAR low step at {@code DOOR_X}.
     */
    private static NavGridView descendExitDoorGrid() {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        PalettedContainer<BlockState> s = filledStone(air, stone);
        for (int x = 2; x < DOOR_X; x++) { s.set(x, 2, DOOR_Z, air); s.set(x, 3, DOOR_Z, air); }
        for (int x = DOOR_X; x <= 12; x++) { s.set(x, 1, DOOR_Z, air); s.set(x, 2, DOOR_Z, air); s.set(x, 3, DOOR_Z, air); }
        s.set(DOOR_X - 1, 2, DOOR_Z, door(Blocks.OAK_DOOR, Direction.NORTH, true, DoorHingeSide.RIGHT, DoubleBlockHalf.LOWER));
        s.set(DOOR_X - 1, 3, DOOR_Z, door(Blocks.OAK_DOOR, Direction.NORTH, true, DoorHingeSide.RIGHT, DoubleBlockHalf.UPPER));
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
}
