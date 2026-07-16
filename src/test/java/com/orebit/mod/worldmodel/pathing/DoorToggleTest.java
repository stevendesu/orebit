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
import com.orebit.mod.pathfinding.blockpathfinder.EditFixtures;
import com.orebit.mod.pathfinding.blockpathfinder.EditScratch;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.pathfinding.blockpathfinder.StepEdits;
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
 * DOORS P2 (planner side): with {@code doors.toggle} ON ({@link BotCaps#mayToggleDoors}) the search folds a cheap
 * absolute-SET door edit — {@code SET_OPEN} to enter a closed door, {@code SET_CLOSED} for the hallway-corner
 * exit double-toggle — and PREFERS it over smashing the door. Iron doors have no SET option and fall through to
 * the P1 break/route. With the flag OFF the planner is byte-identical to Phase A (closed wooden door mined /
 * routed). Lives in this package to reach {@link NavGridView}'s package-private synthetic constructor (like
 * {@link DoorPassageTest}), and uses the public {@link EditFixtures} door-set seam to drive {@code descriptorAt}.
 */
class DoorToggleTest {

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

    // A walk-only bot (cannot break) with doors.toggle ON — if it crosses a CLOSED door, the ONLY mechanism is a
    // SET_OPEN toggle (a breaker could have mined it), so a crossing PROVES the toggle fold. Immune (no hazard
    // economics). / The same walk-only bot with the flag OFF is the Phase-A regression control. / A breaker with
    // the flag ON proves the search PREFERS the cheap toggle over an available break.
    private static final BotCaps WALK_TOGGLE   = caps(false, true);
    private static final BotCaps WALK_OFF       = caps(false, false);
    private static final BotCaps BREAKER_TOGGLE = caps(true, true);
    private static final BotCaps BREAKER_OFF    = caps(true, false);

    private static final int DOOR_X = 7, DOOR_Z = 6;
    private static final float TOGGLE_WALK = Traverse.FLAT_COST + MovementContext.DOOR_TOGGLE_COST;

    // ================================================================================================
    // (1) Candidate level: a CLOSED wooden door blocking the entry edge folds a SET_OPEN (toggle), not a break
    // ================================================================================================

    @Test
    void closedWoodDoorFoldsToggleForAWalkOnlyBot() {
        // Closed OAK door facing EAST → blocks WEST (the entry edge of a bot walking EAST). WALK_TOGGLE can't
        // break, so a candidate at all means the door was OPENED via a SET fold at one toggle cost.
        NavGridView grid = doorGrid(Blocks.OAK_DOOR, Direction.EAST, false, DoorHingeSide.LEFT);
        MovementContext mc = new MovementContext(grid, WALK_TOGGLE);

        Capture cap = new Capture(DOOR_X, 0, DOOR_Z);
        new Traverse().candidates(mc, DOOR_X - 1, 0, DOOR_Z, cap);

        assertTrue(cap.got, "a walk-only+toggle bot IS offered the step into the closed doorway — it opens the door");
        assertTrue(cap.edits, "the crossing folds an edit (the door SET_OPEN), not a free walk");
        assertEquals(TOGGLE_WALK, cap.cost, 1e-4f,
                "cost == flat walk + ONE door-toggle (≈6) — far below breaking both wood halves");
    }

    @Test
    void breakerPrefersToggleOverSmashing() {
        // A bot that CAN break AND toggle: the cheap SET_OPEN (≈6) beats mining two wood halves, so the emitted
        // candidate carries the toggle cost, not the break cost.
        NavGridView grid = doorGrid(Blocks.OAK_DOOR, Direction.EAST, false, DoorHingeSide.LEFT);
        MovementContext mc = new MovementContext(grid, BREAKER_TOGGLE);

        Capture cap = new Capture(DOOR_X, 0, DOOR_Z);
        new Traverse().candidates(mc, DOOR_X - 1, 0, DOOR_Z, cap);

        assertTrue(cap.got && cap.edits, "the breaker crosses the closed door");
        assertEquals(TOGGLE_WALK, cap.cost, 1e-4f,
                "even a breaker OPENS the door (one toggle cost), never smashes it — toggle ≪ break");
    }

    // ================================================================================================
    // (2) A CLOSED IRON door has NO toggle option — unchanged from P1 (break for a breaker, wall for a walker)
    // ================================================================================================

    @Test
    void closedIronDoorIsNotToggleable() {
        NavGridView grid = doorGrid(Blocks.IRON_DOOR, Direction.EAST, false, DoorHingeSide.LEFT);

        // Walk-only + toggle: an iron door is redstone-only, so no SET is offered → impassable (unchanged).
        Capture walk = new Capture(DOOR_X, 0, DOOR_Z);
        new Traverse().candidates(new MovementContext(grid, WALK_TOGGLE), DOOR_X - 1, 0, DOOR_Z, walk);
        assertFalse(walk.got, "an iron door is not hand-toggleable — a walk-only bot cannot pass it, flag or not");

        // Breaker + toggle: falls through to the P1 break fold (mines it), NOT a toggle.
        Capture brk = new Capture(DOOR_X, 0, DOOR_Z);
        new Traverse().candidates(new MovementContext(grid, BREAKER_TOGGLE), DOOR_X - 1, 0, DOOR_Z, brk);
        assertTrue(brk.got && brk.edits, "a breaker still passes the iron door — by mining it (P1 break fold)");
        assertTrue(Math.abs(brk.cost - TOGGLE_WALK) > 1e-3f,
                "the iron-door crossing is a BREAK, not a toggle — its cost is not the toggle cost");
    }

    // ================================================================================================
    // (3) Flag OFF ⇒ byte-identical to Phase A: a closed wooden door is a wall to a walker (mined by a breaker)
    // ================================================================================================

    @Test
    void flagOffLeavesClosedWoodDoorUnchanged() {
        NavGridView grid = doorGrid(Blocks.OAK_DOOR, Direction.EAST, false, DoorHingeSide.LEFT);

        Capture walk = new Capture(DOOR_X, 0, DOOR_Z);
        new Traverse().candidates(new MovementContext(grid, WALK_OFF), DOOR_X - 1, 0, DOOR_Z, walk);
        assertFalse(walk.got, "flag OFF: a closed wooden door is unchanged from Phase A — a wall to a non-breaker");

        Capture brk = new Capture(DOOR_X, 0, DOOR_Z);
        new Traverse().candidates(new MovementContext(grid, BREAKER_OFF), DOOR_X - 1, 0, DOOR_Z, brk);
        assertTrue(brk.got && brk.edits, "flag OFF: a breaker still MINES the closed door (P1 break fold) — no toggle");
        assertTrue(Math.abs(brk.cost - TOGGLE_WALK) > 1e-3f, "flag OFF: the crossing is a break, never a toggle");
    }

    // ================================================================================================
    // (4) Full search: a walk-only+toggle bot reaches the goal through a closed wooden door, folding a SET_OPEN
    //     and mining NOTHING
    // ================================================================================================

    @Test
    void walkOnlyBotOpensClosedDoorToReachGoal() {
        NavGridView grid = doorGrid(Blocks.OAK_DOOR, Direction.EAST, false, DoorHingeSide.LEFT);
        BlockPathPlan plan = BlockPathfinder.findPath(grid, new BlockPos(2, 0, DOOR_Z),
                new BlockPos(12, 0, DOOR_Z), WALK_TOGGLE);

        assertNotNull(plan, "a walk-only+toggle bot reaches the goal — it OPENS the 1-wide closed doorway");
        BlockPos last = plan.waypoint(plan.size() - 1);
        assertTrue(Math.abs(last.getX() - 12) <= 1 && last.getZ() == DOOR_Z, "the plan ends at the goal; ended " + last);
        assertTrue(contains(plan, DOOR_X, 1, DOOR_Z), "the path passes through the door column");

        int setOpen = 0, breaks = 0;
        for (int i = 0; i < plan.size(); i++) {
            StepEdits e = plan.edits(i);
            if (e == null) continue;
            breaks += e.breakCount();
            for (int j = 0; j < e.doorSetCount(); j++) {
                if (e.doorSetOpenAt(j) && isDoorColumn(e.doorSetAt(j))) setOpen++;
            }
        }
        assertTrue(setOpen > 0, "the path OPENS the door (a SET_OPEN on the door column), not mines it");
        assertEquals(0, breaks, "a walk-only bot mines nothing — the closed door is OPENED, never broken");
    }

    @Test
    void flagOffClosedWoodDoorHasNoWalkOnlyPath() {
        // The Phase-A regression: without doors.toggle, a walk-only bot cannot pass a closed wooden door.
        NavGridView grid = doorGrid(Blocks.OAK_DOOR, Direction.EAST, false, DoorHingeSide.LEFT);
        BlockPathPlan plan = BlockPathfinder.findPath(grid, new BlockPos(2, 0, DOOR_Z),
                new BlockPos(12, 0, DOOR_Z), WALK_OFF);
        boolean crossed = plan != null && contains(plan, DOOR_X + 1, 1, DOOR_Z);
        assertFalse(crossed, "flag OFF: a closed wooden door blocks a non-breaker — the plan never crosses it");
    }

    // ================================================================================================
    // (5) The hallway 1-wide 90° corner: the path folds SET_OPEN (enter) THEN SET_CLOSED (exit) on the door cell
    // ================================================================================================

    @Test
    void corridorCornerFoldsOpenThenCloseDoubleToggle() {
        // L-corridor: bot starts SOUTH of the door D, walks NORTH into D, then turns EAST to the goal. D is a
        // CLOSED oak door facing NORTH, hinge RIGHT → closed blocks SOUTH (the entry), open blocks EAST (the
        // exit). So entering folds SET_OPEN and the east turn folds SET_CLOSED — net CLOSED behind the bot.
        NavGridView grid = cornerGrid();
        BlockPathPlan plan = BlockPathfinder.findPath(grid, new BlockPos(DOOR_X, 0, 8),
                new BlockPos(9, 0, DOOR_Z), WALK_TOGGLE);

        assertNotNull(plan, "the walk-only+toggle bot rounds the corner by opening then closing the door");
        BlockPos last = plan.waypoint(plan.size() - 1);
        assertTrue(Math.abs(last.getX() - 9) <= 1 && last.getZ() == DOOR_Z, "ends at the east goal; ended " + last);
        assertTrue(contains(plan, DOOR_X, 1, DOOR_Z), "the bot rests IN the doorway between the two toggles");

        boolean sawOpen = false, sawClose = false;
        int breaks = 0;
        for (int i = 0; i < plan.size(); i++) {
            StepEdits e = plan.edits(i);
            if (e == null) continue;
            breaks += e.breakCount();
            for (int j = 0; j < e.doorSetCount(); j++) {
                if (!isDoorColumn(e.doorSetAt(j))) continue;
                if (e.doorSetOpenAt(j)) sawOpen = true;
                else sawClose = true;
            }
        }
        assertTrue(sawOpen, "the ENTER edge folds a SET_OPEN on the corner door");
        assertTrue(sawClose, "the EXIT (east turn) folds a SET_CLOSED on the SAME corner door — the double-toggle");
        assertEquals(0, breaks, "the corner is rounded by toggling, never by breaking the door");
    }

    // ================================================================================================
    // (6) SET edits fold through latest-wins: SET_OPEN then SET_CLOSED on one cell resolves CLOSED in descriptorAt
    // ================================================================================================

    @Test
    void setEditsResolveLatestWinsInDescriptorAt() {
        NavGridView grid = doorGrid(Blocks.OAK_DOOR, Direction.EAST, false, DoorHingeSide.LEFT); // grid door: CLOSED
        MovementContext mc = new MovementContext(grid, WALK_TOGGLE);
        long doorCell = BlockPos.asLong(DOOR_X, 1, DOOR_Z);

        // Sanity: with no edits the grid door reads CLOSED.
        assertFalse(NavBlock.doorOpen(mc.descriptorAt(DOOR_X, 1, DOOR_Z)), "grid door reads closed with no edits");

        // A lone SET_OPEN reads the door OPEN (resolved against the door's OWN facing/hinge, not a constant).
        var pe = mc.pathEdits();
        pe.reset();
        pe.add(EditFixtures.doorSetStep(true, doorCell));
        long opened = mc.descriptorAt(DOOR_X, 1, DOOR_Z);
        assertTrue(NavBlock.doorOpen(opened), "SET_OPEN resolves the door OPEN in descriptorAt");
        assertEquals(NavBlock.doorBlockedEdge(NavBlock.withDoorOpen(grid.descriptorAt(DOOR_X, 1, DOOR_Z), true)),
                NavBlock.doorBlockedEdge(opened), "the opened door's blocked edge follows its own facing/hinge");

        // Latest-wins: fold the exit SET_CLOSED (closest to the node) THEN the enter SET_OPEN (farther) — the
        // reversed cameFrom walk PathEdits.add uses. First-seen-wins ⇒ SET_CLOSED wins ⇒ net CLOSED.
        pe.reset();
        pe.add(EditFixtures.doorSetStep(false, doorCell)); // exit, closest to node
        pe.add(EditFixtures.doorSetStep(true, doorCell));  // enter, farther
        assertFalse(NavBlock.doorOpen(mc.descriptorAt(DOOR_X, 1, DOOR_Z)),
                "SET_OPEN then SET_CLOSED on one cell resolves CLOSED (latest-wins), the hallway-corner net state");

        // The reverse order resolves OPEN — proves it is genuinely latest-wins, not a fixed precedence.
        pe.reset();
        pe.add(EditFixtures.doorSetStep(true, doorCell));
        pe.add(EditFixtures.doorSetStep(false, doorCell));
        assertTrue(NavBlock.doorOpen(mc.descriptorAt(DOOR_X, 1, DOOR_Z)),
                "SET_CLOSED then SET_OPEN resolves OPEN (latest-wins the other way)");
    }

    // ---- grid builders ------------------------------------------------------------------------------

    private static boolean isDoorColumn(long cell) {
        return BlockPos.getX(cell) == DOOR_X && BlockPos.getZ(cell) == DOOR_Z;
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

    /** Straight 1-wide corridor z=6, x=2..12 (air body over stone floor y=0), with a door at column DOOR_X. */
    private static NavGridView doorGrid(Block block, Direction facing, boolean open, DoorHingeSide hinge) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        PalettedContainer<BlockState> s = filledStone(air, stone);
        for (int x = 2; x <= 12; x++) { s.set(x, 1, DOOR_Z, air); s.set(x, 2, DOOR_Z, air); }
        s.set(DOOR_X, 1, DOOR_Z, door(block, facing, open, hinge, DoubleBlockHalf.LOWER));
        s.set(DOOR_X, 2, DOOR_Z, door(block, facing, open, hinge, DoubleBlockHalf.UPPER));
        return single(s);
    }

    /**
     * The 1-wide 90° corner (DESIGN-04 §4): a vertical corridor x=DOOR_X running z=8→6 into the corner door cell
     * D=(DOOR_X,DOOR_Z), then a horizontal corridor z=DOOR_Z running x=7→9 to the goal. Everything else is stone,
     * so the ONLY route bends through D. D is a CLOSED oak door facing NORTH, hinge RIGHT (closed blocks SOUTH,
     * open blocks EAST).
     */
    private static NavGridView cornerGrid() {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        PalettedContainer<BlockState> s = filledStone(air, stone);
        for (int z = DOOR_Z; z <= 8; z++) { s.set(DOOR_X, 1, z, air); s.set(DOOR_X, 2, z, air); } // vertical leg
        for (int x = DOOR_X; x <= 9; x++) { s.set(x, 1, DOOR_Z, air); s.set(x, 2, DOOR_Z, air); } // horizontal leg
        s.set(DOOR_X, 1, DOOR_Z, door(Blocks.OAK_DOOR, Direction.NORTH, false, DoorHingeSide.RIGHT, DoubleBlockHalf.LOWER));
        s.set(DOOR_X, 2, DOOR_Z, door(Blocks.OAK_DOOR, Direction.NORTH, false, DoorHingeSide.RIGHT, DoubleBlockHalf.UPPER));
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
