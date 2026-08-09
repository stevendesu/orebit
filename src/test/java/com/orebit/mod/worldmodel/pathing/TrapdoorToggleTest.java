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
import com.orebit.mod.pathfinding.blockpathfinder.MiningModel;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.pathfinding.blockpathfinder.StepEdits;
import com.orebit.mod.pathfinding.blockpathfinder.movements.Ascend;
import com.orebit.mod.pathfinding.blockpathfinder.movements.MineDown;
import com.orebit.mod.pathfinding.blockpathfinder.movements.Pillar;
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
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

/**
 * TRAPDOORS Stage 3 — the §5/§6 toggle folds on the movement family (DESIGN-trapdoors.md), the door-suite
 * shapes cloned for hatches:
 * <ul>
 *   <li><b>Hallway OPEN-to-progress</b> — a closed hatch flush in the only route DOWN: the full search
 *       folds exactly ONE {@code SET_OPEN} (MineDown's §5 hatch-drop toggle arm) and mines NOTHING; the
 *       IRON flavor gets no toggle (crossed by mining — or, for a walk-only bot, not at all); {@code
 *       doors.toggle=false} folds no SET.</li>
 *   <li><b>Hallway CLOSE-to-progress</b> — an open BOTTOM panel across the corridor's feet cell: the flat
 *       walk self-refuses on the post-toggle plate (the §5 within-candidate re-check) and the crossing is
 *       the dy=+1 step-assist onto the closed PLATE NODE via {@code requireFloorOrToggle} — one {@code
 *       SET_CLOSED}, nothing broken.</li>
 *   <li><b>Flush-hatch pocket</b> — a closed-bottom hatch sunk in a full floor: entered by the plain flat
 *       step-down, exited ONLY by {@link Ascend}'s same-level jump arm (rise 13 ∈ (9,20]); no edits at
 *       all.</li>
 *   <li><b>MineDown hatch-drop / Pillar-up-through-hatch</b> — candidate-level: the vertical family's
 *       {@code requireAirVertical} folds the SET_OPEN at {@link MovementContext#DOOR_TOGGLE_COST} instead
 *       of the break.</li>
 *   <li><b>SET-resolution overlay</b> — a downstream node reads the TOGGLED geometry through the path
 *       diff (the {@link EditFixtures} door-set seam): after an earlier SET_CLOSED the open panel reads as
 *       a standable 3/16 plate and a later step stands on it free.</li>
 * </ul>
 * Lives in this package for {@link NavGridView}'s package-private synthetic constructor (the
 * {@link DoorToggleTest} scene idiom).
 */
class TrapdoorToggleTest {

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
        // Bake the REAL mining-tick table (the ColdStartHarnessTest server-started idiom): the breaker
        // scenes below assert the toggle is PREFERRED over mining, which is only meaningful when a break
        // costs its real ticks (an unbaked table prices breaks near zero and the search just tunnels).
        MiningModel.buildTable(true, 0);
    }

    private static BotCaps caps(boolean canBreak, boolean canPlace, boolean toggle) {
        return new BotCaps(1, BotCaps.DEFAULT_SAFE_FALL, BotCaps.DEFAULT_MAX_FALL, false,
                BotCaps.DEFAULT_COST_PER_HITPOINT, canBreak, canPlace,
                100, false, BotCaps.DEFAULT_MAX_NODES, 1.0f, toggle);
    }

    /** Walk-only + doors.toggle ON: a crossing proves a free admit or a SET fold — never a break/place. */
    private static final BotCaps WALK_TOGGLE = caps(false, false, true);
    /** Walk-only + doors.toggle OFF: the flag kill-switch regression control. */
    private static final BotCaps WALK_OFF = caps(false, false, false);
    /** A breaker with the flag ON: proves the search PREFERS the cheap toggle over an available break
     *  (also the only bot that can run MineDown at all — its {@code canBreak} top gate stands, hatch or
     *  not; see the MineDown class doc). */
    private static final BotCaps BREAKER_TOGGLE = caps(true, false, true);
    /** A breaker with the flag OFF: the crossing must fall back to the break path, no SETs anywhere. */
    private static final BotCaps BREAKER_OFF = caps(true, false, false);
    /** A placer (Pillar needs {@code canPlace}) with the flag ON / OFF. */
    private static final BotCaps PLACER_TOGGLE = caps(false, true, true);
    private static final BotCaps PLACER_OFF = caps(false, true, false);

    private static final int TD_X = 7, Z = 6;
    private static final float TOGGLE_WALK = Traverse.FLAT_COST + MovementContext.DOOR_TOGGLE_COST;

    // ================================================================================================
    // (1) Hallway OPEN-to-progress: a two-storey corridor whose ONLY link is a 1×1 hole covered by a
    //     closed-BOTTOM hatch. The route stands on the hatch and MineDowns through it — the §5 toggle
    //     arm folds exactly ONE SET_OPEN, nothing is mined.
    // ================================================================================================

    @Test
    void hallwayOpenToProgressFoldsOneSetOpen() {
        NavGridView grid = hatchDropGrid(Blocks.OAK_TRAPDOOR);
        BlockPathPlan plan = BlockPathfinder.findPath(grid, new BlockPos(2, 1, Z),
                new BlockPos(TD_X, 0, 12), BREAKER_TOGGLE);

        assertNotNull(plan, "the breaker+toggle bot reaches the lower tunnel through the hatch");
        BlockPos last = plan.waypoint(plan.size() - 1);
        assertTrue(last.getX() == TD_X && Math.abs(last.getZ() - 12) <= 1,
                "the plan ends at the tunnel goal; ended " + last);
        assertTrue(contains(plan, TD_X, 1, Z), "the path stands on the hatch column");

        int setOpen = 0, setClosed = 0, breaks = 0;
        for (int i = 0; i < plan.size(); i++) {
            StepEdits e = plan.edits(i);
            if (e == null) continue;
            breaks += e.breakCount();
            for (int j = 0; j < e.doorSetCount(); j++) {
                assertEquals(BlockPos.asLong(TD_X, 1, Z), e.doorSetAt(j), "the only SET is on the hatch cell");
                if (e.doorSetOpenAt(j)) setOpen++;
                else setClosed++;
            }
        }
        assertEquals(1, setOpen, "exactly ONE SET_OPEN is folded — the hatch is opened, not smashed");
        assertEquals(0, setClosed, "no spurious SET_CLOSED");
        assertEquals(0, breaks, "the toggle is PREFERRED over every available break (toggle 6 ≪ mining)");
    }

    @Test
    void ironHatchGetsNoToggle() {
        // Iron: no SET is ever offered. A breaker still gets through — by BREAKING (the P1-equivalent
        // fall-through) — and a walk-only bot is walled out entirely (routed around / refused).
        NavGridView grid = hatchDropGrid(Blocks.IRON_TRAPDOOR);

        BlockPathPlan brk = BlockPathfinder.findPath(grid, new BlockPos(2, 1, Z),
                new BlockPos(TD_X, 0, 12), BREAKER_TOGGLE);
        assertNotNull(brk, "a breaker crosses the iron hatch scene — by mining");
        int breaks = 0, sets = 0;
        for (int i = 0; i < brk.size(); i++) {
            StepEdits e = brk.edits(i);
            if (e == null) continue;
            breaks += e.breakCount();
            sets += e.doorSetCount();
        }
        assertEquals(0, sets, "an iron hatch is not hand-toggleable — no SET anywhere in the plan");
        assertTrue(breaks >= 1, "the iron crossing is a BREAK (P1 fall-through)");

        BlockPathPlan walk = BlockPathfinder.findPath(grid, new BlockPos(2, 1, Z),
                new BlockPos(TD_X, 0, 12), WALK_TOGGLE);
        assertNeverReachesTunnel(walk, "a walk-only bot cannot pass an iron hatch, flag or not");
    }

    @Test
    void flagOffHatchFoldsNoSet() {
        // doors.toggle OFF: the SET arm vanishes — a breaker falls back to mining (no SETs anywhere),
        // byte-identical to the pre-trapdoor model.
        NavGridView grid = hatchDropGrid(Blocks.OAK_TRAPDOOR);
        BlockPathPlan plan = BlockPathfinder.findPath(grid, new BlockPos(2, 1, Z),
                new BlockPos(TD_X, 0, 12), BREAKER_OFF);
        assertNotNull(plan, "a breaker still crosses with the flag off — by mining");
        int breaks = 0, sets = 0;
        for (int i = 0; i < plan.size(); i++) {
            StepEdits e = plan.edits(i);
            if (e == null) continue;
            breaks += e.breakCount();
            sets += e.doorSetCount();
        }
        assertEquals(0, sets, "flag OFF: no SET is folded anywhere");
        assertTrue(breaks >= 1, "flag OFF: the crossing is a break");
    }

    // ================================================================================================
    // (2) Hallway CLOSE-to-progress: an open BOTTOM panel across the corridor's feet cell — the flat
    //     arm self-refuses on the post-toggle plate; the crossing is the dy=+1 step-assist onto the
    //     PLATE NODE (requireFloorOrToggle), one SET_CLOSED.
    // ================================================================================================

    @Test
    void hallwayCloseToProgressStandsThePlateNode() {
        // Open OAK panel, BOTTOM half, facing EAST → the panel hugs the WEST wall strip: exactly the
        // entry face of eastward travel, so the cell cannot be crossed free.
        NavGridView grid = corridor3With(TD_X, 1, td(Blocks.OAK_TRAPDOOR, Direction.EAST, true, Half.BOTTOM));
        MovementContext mc = new MovementContext(grid, WALK_TOGGLE);

        // Candidate level: the emission is the dy=+1 step onto the CLOSED plate (7,1,Z), at one toggle.
        Capture plate = new Capture(TD_X, 1, Z);
        new Traverse().candidates(mc, TD_X - 1, 0, Z, plate);
        assertTrue(plate.got, "the crossing is offered as the step-assist onto the closing plate");
        assertTrue(plate.edits, "it folds an edit — the SET_CLOSED");
        assertEquals(TOGGLE_WALK, plate.cost, 1e-4f, "flat walk + exactly ONE toggle");

        // The flat dy=0 candidate must self-refuse on the post-toggle geometry (the plate bisects the
        // transit cell) — §5's within-candidate re-check.
        Capture flat = new Capture(TD_X, 0, Z);
        new Traverse().candidates(mc, TD_X - 1, 0, Z, flat);
        assertFalse(flat.got, "the flat crossing self-refuses: closing the panel creates a plate in the feet cell");

        // Full search: the walk-only bot crosses by closing the panel and standing the plate node.
        BlockPathPlan plan = BlockPathfinder.findPath(grid, new BlockPos(2, 0, Z), new BlockPos(12, 0, Z),
                WALK_TOGGLE);
        assertNotNull(plan, "the corridor is crossed");
        BlockPos last = plan.waypoint(plan.size() - 1);
        assertTrue(Math.abs(last.getX() - 12) <= 1 && last.getZ() == Z, "ends at the goal; ended " + last);
        // The plate node IS on the path, at the REAL plate feet: the reconstruction resolves the floor
        // descriptor through the plan's OWN SET_CLOSED (BlockPathfinder.feetYOf + resolveDoorSet), so the
        // waypoint reads the toggled 3/16 plate (feet in the floor's own cell, y=1) instead of drifting to
        // the fy+1 fallback the raw grid read produced (the executor-facing seam gap, now closed).
        assertTrue(contains(plan, TD_X, 1, Z), "the path stands the plate node at the toggled plate's own feet cell");
        int setClosed = 0, setOpen = 0, breaks = 0;
        for (int i = 0; i < plan.size(); i++) {
            StepEdits e = plan.edits(i);
            if (e == null) continue;
            breaks += e.breakCount();
            for (int j = 0; j < e.doorSetCount(); j++) {
                assertEquals(BlockPos.asLong(TD_X, 1, Z), e.doorSetAt(j), "the only SET is on the panel cell");
                if (e.doorSetOpenAt(j)) setOpen++;
                else setClosed++;
            }
        }
        assertEquals(1, setClosed, "exactly ONE SET_CLOSED");
        assertEquals(0, setOpen, "no spurious SET_OPEN");
        assertEquals(0, breaks, "nothing broken");

        // Kill-switch: with the flag OFF the panel is a wall (no fold, no crossing).
        BlockPathPlan off = BlockPathfinder.findPath(grid, new BlockPos(2, 0, Z), new BlockPos(12, 0, Z),
                WALK_OFF);
        assertNeverCrosses(off, TD_X, "flag OFF: the blocking panel is a wall to a walker");
    }

    // ================================================================================================
    // (3) Flush-hatch pocket: enter by the plain step-down, exit ONLY via Ascend's same-level jump arm.
    // ================================================================================================

    @Test
    void flushHatchPocketEnteredByStepDownExitedBySameLevelJump() {
        NavGridView grid = pocketGrid();
        MovementContext mc = new MovementContext(grid, WALK_TOGGLE);

        // Candidate level: from the pocket, Traverse CANNOT exit (rise 13 > 9) — the jump arm owns it.
        Capture walkOut = new Capture(TD_X + 1, 0, Z);
        new Traverse().candidates(mc, TD_X, 0, Z, walkOut);
        assertFalse(walkOut.got, "a 13/16 lip is beyond the auto-step — Traverse never exits the pocket");

        Capture jumpOut = new Capture(TD_X + 1, 0, Z);
        new Ascend().candidates(mc, TD_X, 0, Z, jumpOut);
        assertTrue(jumpOut.got, "Ascend's same-level jump arm exits the flush-hatch pocket");
        assertFalse(jumpOut.edits, "the exit is edit-free (a plain jump)");
        assertEquals(Ascend.COST, jumpOut.cost, 1e-4f, "priced like the +1 jump");

        // Full search: the pocket is on the only route; entered (step-down) and exited (jump), no edits.
        BlockPathPlan plan = BlockPathfinder.findPath(grid, new BlockPos(2, 0, Z), new BlockPos(12, 0, Z),
                WALK_TOGGLE);
        assertNotNull(plan, "the corridor with the sunken hatch is walked end to end");
        BlockPos last = plan.waypoint(plan.size() - 1);
        assertTrue(Math.abs(last.getX() - 12) <= 1 && last.getZ() == Z, "ends at the goal; ended " + last);
        assertTrue(contains(plan, TD_X, 0, Z), "the path stands IN the pocket (feet = the hatch's own cell)");
        assertNoEditsAtAll(plan);
    }

    // ================================================================================================
    // (4) MineDown hatch-drop — candidate level: SET_OPEN instead of the break, at the toggle cost.
    // ================================================================================================

    @Test
    void mineDownFoldsSetOpenOnAClosedHatch() {
        NavGridView grid = hatchStandGrid(Blocks.OAK_TRAPDOOR);

        Capture drop = new Capture(TD_X, 0, Z);
        new MineDown().candidates(new MovementContext(grid, BREAKER_TOGGLE), TD_X, 1, Z, drop);
        assertTrue(drop.got, "standing on a closed toggleable hatch, MineDown drops through it");
        assertTrue(drop.edits, "the drop folds an edit — the SET_OPEN");
        assertEquals(MineDown.COST + MovementContext.DOOR_TOGGLE_COST, drop.cost, 1e-4f,
                "the hatch is OPENED at DOOR_TOGGLE_COST, never priced as a break");

        // Flag OFF: the toggle arm vanishes — the breaker falls through to the P1 break at mining cost.
        Capture broke = new Capture(TD_X, 0, Z);
        new MineDown().candidates(new MovementContext(grid, BREAKER_OFF), TD_X, 1, Z, broke);
        assertTrue(broke.got, "flag OFF: a breaker still mines through its own floor hatch");
        assertTrue(broke.cost > MineDown.COST + MovementContext.DOOR_TOGGLE_COST + 1f,
                "flag OFF: the crossing is the BREAK price, not the toggle price");

        // A walk-only bot never runs MineDown at all (the canBreak top gate — see the class doc).
        Capture walk = new Capture(TD_X, 0, Z);
        new MineDown().candidates(new MovementContext(grid, WALK_TOGGLE), TD_X, 1, Z, walk);
        assertFalse(walk.got, "MineDown's canBreak gate stands: a walk-only bot emits nothing, hatch or not");
    }

    // ================================================================================================
    // (5) Pillar-up-through-hatch — candidate level: the overhead y+3 hatch is swung open (SET_OPEN),
    //     not mined; the shaft stays clear for the 0.6 body (§4).
    // ================================================================================================

    @Test
    void pillarTogglesTheOverheadHatchOpen() {
        NavGridView grid = pillarShaftGrid();

        Capture up = new Capture(TD_X, 1, Z);
        new Pillar().candidates(new MovementContext(grid, PLACER_TOGGLE), TD_X, 0, Z, up);
        assertTrue(up.got, "a placer+toggle bot pillars up through its own closed hatch");
        assertTrue(up.edits, "the step folds edits — the footing place AND the hatch SET_OPEN");
        assertEquals(Pillar.COST + MovementContext.PLACE_BASE_COST + MovementContext.DOOR_TOGGLE_COST,
                up.cost, 1e-4f, "cost = pillar + one place + ONE toggle (the hatch is opened, not broken)");

        // Flag OFF: no toggle, and a non-breaking placer cannot clear the overhead cell — refused.
        Capture off = new Capture(TD_X, 1, Z);
        new Pillar().candidates(new MovementContext(grid, PLACER_OFF), TD_X, 0, Z, off);
        assertFalse(off.got, "flag OFF: the overhead hatch is a ceiling to a non-breaking placer");
    }

    // ================================================================================================
    // (6) SET-resolution overlay: after an earlier fold, downstream reads see the TOGGLED geometry.
    // ================================================================================================

    @Test
    void downstreamNodesSeeToggledGeometry() {
        // Grid truth: an OPEN bottom panel at (TD_X, 1, Z). An earlier step's SET_CLOSED rides the path
        // diff; every later read must see the CLOSED plate — standable, topY 3.
        NavGridView grid = corridor3With(TD_X, 1, td(Blocks.OAK_TRAPDOOR, Direction.EAST, true, Half.BOTTOM));
        MovementContext mc = new MovementContext(grid, WALK_TOGGLE);
        long cell = BlockPos.asLong(TD_X, 1, Z);

        // Sanity: with no edits the grid reads the OPEN panel (not standable).
        long open = mc.descriptorAt(TD_X, 1, Z);
        assertTrue(NavBlock.isTrapdoor(open) && NavBlock.trapdoorOpen(open), "grid truth: an open trapdoor");
        assertFalse(NavBlock.isStandable(open), "an open panel is not a floor");

        mc.pathEdits().reset();
        mc.pathEdits().add(EditFixtures.doorSetStep(false, cell)); // the earlier step's SET_CLOSED
        long closed = mc.descriptorAt(TD_X, 1, Z);
        assertTrue(NavBlock.isTrapdoor(closed) && !NavBlock.trapdoorOpen(closed),
                "the SET_CLOSED resolves the trapdoor CLOSED via withOpenableOpen");
        assertTrue(NavBlock.isStandable(closed), "the closed BOTTOM half is a standable plate");
        assertEquals(3, NavBlock.topY(closed), "…with the re-derived plate top (3/16)");

        // A downstream Traverse stands on it FREE — no new fold, the ordinary standable step-assist.
        Capture cap = new Capture(TD_X, 1, Z);
        new Traverse().candidates(mc, TD_X - 1, 0, Z, cap);
        assertTrue(cap.got, "a later step stands on the closed plate the earlier fold created");
        assertFalse(cap.edits, "…for free: the geometry is already toggled in the diff");
        assertEquals(Traverse.FLAT_COST, cap.cost, 1e-4f, "an ordinary step-assist, nothing re-toggled");
    }

    // ================================================================================================
    // (7) FAR-WALL panel EXIT checks (§4 per-cell blocked-face on the EXIT side): an open panel hugging
    //     the exit-side wall of a corridor cell is entered FREE (its blocked face is not the entry face)
    //     but must not be exited THROUGH unchecked. Head cell, TOP half → the exit folds ONE SET_CLOSED
    //     (flush overhead hatch — sound above the standing body); head cell, BOTTOM half → EXIT_BLOCKED
    //     (closing would plate through the bot's own head — route around / no path); feet cell, TOP half
    //     → EXIT_BLOCKED (closing would plate through the bot's own waist).
    // ================================================================================================

    @Test
    void farWallHeadPanelTopHalfExitClosesIt() {
        // Open TOP-half panel in the HEAD cell at (TD_X,2,Z), facing WEST → panel hugs the EAST wall:
        // entered free from the west, but the eastward EXIT crosses it — the exit context folds SET_CLOSED.
        NavGridView grid = corridor3With(TD_X, 2, td(Blocks.OAK_TRAPDOOR, Direction.WEST, true, Half.TOP));
        BlockPathPlan plan = BlockPathfinder.findPath(grid, new BlockPos(2, 0, Z), new BlockPos(12, 0, Z),
                WALK_TOGGLE);
        assertNotNull(plan, "the head panel on the far wall is closed overhead and the corridor crossed");
        BlockPos last = plan.waypoint(plan.size() - 1);
        assertTrue(Math.abs(last.getX() - 12) <= 1 && last.getZ() == Z, "ends at the goal; ended " + last);
        int setClosed = 0, setOpen = 0, breaks = 0;
        for (int i = 0; i < plan.size(); i++) {
            StepEdits e = plan.edits(i);
            if (e == null) continue;
            breaks += e.breakCount();
            for (int j = 0; j < e.doorSetCount(); j++) {
                assertEquals(BlockPos.asLong(TD_X, 2, Z), e.doorSetAt(j), "the only SET is on the head panel");
                if (e.doorSetOpenAt(j)) setOpen++;
                else setClosed++;
            }
        }
        assertEquals(1, setClosed, "the exit folds exactly ONE SET_CLOSED (flush overhead hatch)");
        assertEquals(0, setOpen, "no spurious SET_OPEN");
        assertEquals(0, breaks, "nothing broken");
    }

    @Test
    void farWallHeadPanelBottomHalfExitBlocked() {
        // Same geometry, BOTTOM half: closing it would materialize a plate through the standing bot's own
        // head (2.0..2.1875 vs body top 2.8) — the exit is BLOCKED and a walk-only bot never crosses.
        NavGridView grid = corridor3With(TD_X, 2, td(Blocks.OAK_TRAPDOOR, Direction.WEST, true, Half.BOTTOM));
        BlockPathPlan plan = BlockPathfinder.findPath(grid, new BlockPos(2, 0, Z), new BlockPos(12, 0, Z),
                WALK_TOGGLE);
        assertNeverCrosses(plan, TD_X + 1, "a BOTTOM-half far-wall head panel cannot be closed into the bot");
    }

    @Test
    void farWallFeetPanelTopHalfExitBlocked() {
        // Open TOP-half panel in the FEET cell, panel on the east (exit) wall, in a 2-TALL corridor (the
        // 2-tall ceiling also forbids the close-and-jump-OVER alternative a 3-tall corridor would admit):
        // walking through the panel's face would need a SET_CLOSED that plates through the bot's own waist
        // (1.8125..2.0) — EXIT_BLOCKED; a walk-only bot never crosses.
        NavGridView grid = corridorWith2Tall(TD_X, 1, td(Blocks.OAK_TRAPDOOR, Direction.WEST, true, Half.TOP));
        BlockPathPlan plan = BlockPathfinder.findPath(grid, new BlockPos(2, 0, Z), new BlockPos(12, 0, Z),
                WALK_TOGGLE);
        assertNeverCrosses(plan, TD_X + 1, "a TOP-half feet panel cannot be closed into the bot's own waist");
    }

    // ---- scene builders (the DoorToggleTest / TrapdoorClearanceTest single-section idiom) -----------

    private static BlockState td(Block block, Direction facing, boolean open, Half half) {
        return block.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
                .setValue(BlockStateProperties.OPEN, open)
                .setValue(BlockStateProperties.HALF, half)
                .setValue(BlockStateProperties.WATERLOGGED, false);
    }

    /**
     * The OPEN-to-progress two-storey scene: an UPPER corridor along X at floor y=1 (body y=2..3,
     * x=2..TD_X), whose floor cell at TD_X is a closed-BOTTOM hatch (facing SOUTH → its opened panel hugs
     * the NORTH wall, clear of the southbound exit) over a stone landing at (TD_X,0,Z); a LOWER tunnel
     * along Z at floor y=0 (body y=1..2, z=Z+1..12) reachable ONLY through the hatch.
     */
    private static NavGridView hatchDropGrid(Block trapdoor) {
        BlockState air = Blocks.AIR.defaultBlockState();
        PalettedContainer<BlockState> s = filledStone(air);
        for (int x = 2; x <= TD_X; x++) { s.set(x, 2, Z, air); s.set(x, 3, Z, air); } // upper body
        s.set(TD_X, 1, Z, td(trapdoor, Direction.SOUTH, false, Half.BOTTOM));          // the hatch floor
        for (int z = Z + 1; z <= 12; z++) { s.set(TD_X, 1, z, air); s.set(TD_X, 2, z, air); } // lower tunnel
        return single(s);
    }

    /** A 1-wide corridor at z=Z with TWO body cells (air y=1..2, stone ceiling y=3), one cell overridden —
     *  the TrapdoorClearanceTest corridor shape, for scenes where the 2-tall ceiling must forbid going
     *  OVER an obstacle (the feet far-wall panel exit pin). */
    private static NavGridView corridorWith2Tall(int x, int y, BlockState state) {
        BlockState air = Blocks.AIR.defaultBlockState();
        PalettedContainer<BlockState> s = filledStone(air);
        for (int cx = 2; cx <= 12; cx++) {
            s.set(cx, 1, Z, air);
            s.set(cx, 2, Z, air);
        }
        s.set(x, y, Z, state);
        return single(s);
    }

    /** A 1-wide corridor at z=Z with THREE body cells (air y=1..3 over a stone floor y=0, ceiling y=4),
     *  one cell overridden to {@code state}. Three-tall so the §5 plate-node crossing can descend past
     *  the plate (the step-off head clearance needs the third body row). */
    private static NavGridView corridor3With(int x, int y, BlockState state) {
        BlockState air = Blocks.AIR.defaultBlockState();
        PalettedContainer<BlockState> s = filledStone(air);
        for (int cx = 2; cx <= 12; cx++) {
            s.set(cx, 1, Z, air);
            s.set(cx, 2, Z, air);
            s.set(cx, 3, Z, air);
        }
        s.set(x, y, Z, state);
        return single(s);
    }

    /** The flush-hatch pocket: the 3-body corridor with the FLOOR cell at TD_X swapped for a
     *  closed-BOTTOM hatch (top 3/16 — a sunken pocket ringed by full blocks). */
    private static NavGridView pocketGrid() {
        BlockState air = Blocks.AIR.defaultBlockState();
        PalettedContainer<BlockState> s = filledStone(air);
        for (int cx = 2; cx <= 12; cx++) {
            s.set(cx, 1, Z, air);
            s.set(cx, 2, Z, air);
            s.set(cx, 3, Z, air);
        }
        s.set(TD_X, 0, Z, td(Blocks.OAK_TRAPDOOR, Direction.NORTH, false, Half.BOTTOM));
        return single(s);
    }

    /** Stand-on-a-hatch mini scene for the MineDown candidate: hatch floor at (TD_X,1,Z) over a stone
     *  landing (TD_X,0,Z), body air above. */
    private static NavGridView hatchStandGrid(Block trapdoor) {
        BlockState air = Blocks.AIR.defaultBlockState();
        PalettedContainer<BlockState> s = filledStone(air);
        s.set(TD_X, 1, Z, td(trapdoor, Direction.SOUTH, false, Half.BOTTOM));
        s.set(TD_X, 2, Z, air);
        s.set(TD_X, 3, Z, air);
        return single(s);
    }

    /** A 1×1 pillar shaft: bot floor (TD_X,0,Z) stone, body air y=1..2, a closed-BOTTOM hatch at y=3
     *  (the overhead cell of the first pillar step), air above it. */
    private static NavGridView pillarShaftGrid() {
        BlockState air = Blocks.AIR.defaultBlockState();
        PalettedContainer<BlockState> s = filledStone(air);
        s.set(TD_X, 1, Z, air);
        s.set(TD_X, 2, Z, air);
        s.set(TD_X, 3, Z, td(Blocks.OAK_TRAPDOOR, Direction.SOUTH, false, Half.BOTTOM));
        s.set(TD_X, 4, Z, air);
        return single(s);
    }

    private static PalettedContainer<BlockState> filledStone(BlockState air) {
        BlockState stone = Blocks.STONE.defaultBlockState();
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

    // ---- assertions ---------------------------------------------------------------------------------

    private static boolean contains(BlockPathPlan plan, int x, int y, int z) {
        for (int i = 0; i < plan.size(); i++) {
            BlockPos wp = plan.waypoint(i);
            if (wp.getX() == x && wp.getY() == y && wp.getZ() == z) return true;
        }
        return false;
    }

    /** The plan crossed with ZERO edits of any kind — the free-admit proof. */
    private static void assertNoEditsAtAll(BlockPathPlan plan) {
        for (int i = 0; i < plan.size(); i++) {
            StepEdits e = plan.edits(i);
            if (e == null) continue;
            assertEquals(0, e.breakCount(), "no breaks folded");
            assertEquals(0, e.placeCount(), "no places folded");
            assertEquals(0, e.doorSetCount(), "no SETs folded");
        }
    }

    /** No waypoint ever reaches the barrier column {@code barX} or beyond (partial plans allowed). */
    private static void assertNeverCrosses(BlockPathPlan plan, int barX, String msg) {
        if (plan == null) return;
        for (int i = 0; i < plan.size(); i++) {
            assertTrue(plan.waypoint(i).getX() < barX, msg + "; reached " + plan.waypoint(i));
        }
    }

    /** No waypoint ever enters the lower tunnel (z past the hatch row at the low level). */
    private static void assertNeverReachesTunnel(BlockPathPlan plan, String msg) {
        if (plan == null) return;
        for (int i = 0; i < plan.size(); i++) {
            BlockPos wp = plan.waypoint(i);
            assertFalse(wp.getZ() > Z && wp.getY() <= 2, msg + "; reached " + wp);
        }
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
