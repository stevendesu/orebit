package com.orebit.mod.pathfinding;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan;
import com.orebit.mod.pathfinding.blockpathfinder.Movement;
import com.orebit.mod.pathfinding.blockpathfinder.StepEdits;

import net.minecraft.core.BlockPos;

/**
 * The {@code onRoute} membership rule ({@link PathPlan#onBlockPlan}) — the input the region cascade's
 * deviation test reads ({@code deviated = !inWindow && !onRoute}).
 *
 * <p>Regression specimen: the <b>post-replan Ascend hold</b> (owner-witnessed 2026-07-31 23:36, headless
 * 2026-08-01 07:02). The bot stood on floor {@code (46,146,215)}; a break at {@code (46,147,215)} made the
 * plan terrain-impacted, so {@code refreshWindow} installed a fresh 16-waypoint plan whose <b>first move is
 * a {@code Parkour d(0,-3,-5)}</b>. On the very next tick the cascade asked "is the bot on its block plan?"
 * — and because {@code BlockPathfinder}'s reconstruct is START-EXCLUSIVE ("the start cell itself is not a
 * waypoint — the bot is already there"), that plan's nearest waypoint was the jump's LANDING, five cells
 * away. No match ⇒ {@code onRoute=false} ⇒ {@code deviated} ⇒ the whole L0 skeleton was discarded and
 * re-derived, swapping the route ~90° to an {@code Ascend −X}. The bot had not moved one block.
 *
 * <p>That is precisely the "matcher artifact" {@code HierarchicalRegionPlan}'s exhausted/deviated invariant
 * forbids ("exhausted/deviated imply real bot progress or displacement, never a matcher artifact"). The
 * fix is the plan's own start cell as its implicit step −1; these pin it, and pin that a genuinely
 * displaced bot still reads as deviated.
 */
class PathPlanOnRouteTest {

    /** A synthetic plan over the given stand (feet) cells; the convenience ctor derives each floor as
     *  {@code waypoint.below()}, matching the full-block production case. */
    private static BlockPathPlan planOf(BlockPos... stands) {
        List<BlockPos> wps = Arrays.asList(stands);
        return new BlockPathPlan(wps,
                Arrays.asList(new Movement[stands.length]),
                Arrays.asList(new StepEdits[stands.length]),
                0f);
    }

    /** The witnessed frame: planned FROM floor (46,146,215); step 0 is a Parkour whose landing floor is
     *  (46,143,210) — stand cell one above. Later steps only get farther away. */
    private static final BlockPos PLAN_START = new BlockPos(46, 146, 215);

    private static BlockPathPlan parkourFirstPlan() {
        return planOf(new BlockPos(46, 144, 210),  // Parkour d(0,-3,-5) landing stand
                new BlockPos(46, 143, 209),        // Descend
                new BlockPos(46, 143, 208));       // Traverse
    }

    @Test
    void botAtTheStartOfAParkourFirstPlanReadsAsOnRoute() {
        // The exact freeze condition: the bot is standing on the cell the plan was searched FROM.
        assertTrue(PathPlan.onBlockPlan(parkourFirstPlan(), PLAN_START, PLAN_START),
                "a bot standing where the plan was planned from is maximally on-route");
    }

    @Test
    void withoutTheStartTermTheSameStateReadsAsDeviated() {
        // Pins WHY the bug existed: waypoint-only membership cannot see the bot at a start-exclusive
        // plan's start when the first move is a jump. This is the pre-fix behaviour.
        assertFalse(PathPlan.onBlockPlan(parkourFirstPlan(), null, PLAN_START),
                "waypoint-only membership misses the start cell — the matcher artifact behind the hold");
    }

    @Test
    void theStartTermCarriesTheSameOneCellToleranceAsAWaypoint() {
        BlockPathPlan plan = parkourFirstPlan();
        // Within the ±1 box of the start (the bot drifts a cell while the plan is installed).
        assertTrue(PathPlan.onBlockPlan(plan, PLAN_START, new BlockPos(46, 146, 214)),
                "one cell off the start is inside the same +/-1 box a waypoint gets");
        assertTrue(PathPlan.onBlockPlan(plan, PLAN_START, new BlockPos(45, 147, 216)),
                "the box is a cube, not an axis test");
        // Two cells out on an axis is outside it.
        assertFalse(PathPlan.onBlockPlan(plan, PLAN_START, new BlockPos(46, 146, 213)),
                "two cells off the start is not vouched for by the start term");
    }

    @Test
    void aGenuinelyDisplacedBotStillReadsAsDeviated() {
        // The invariant the fix must NOT weaken: real displacement still trips the cascade.
        assertFalse(PathPlan.onBlockPlan(parkourFirstPlan(), PLAN_START, new BlockPos(30, 120, 180)),
                "a bot nowhere near the start or any waypoint is genuinely off-route");
    }

    @Test
    void aTraverseFirstPlanWasNeverAffected() {
        // Why the bug is rare and jump-specific: a 1-cell first move puts waypoint 0 inside the +/-1 box
        // of the start, so waypoint-only membership already vouched. Behaviour here is unchanged.
        BlockPathPlan walk = planOf(new BlockPos(46, 147, 214), new BlockPos(46, 147, 213));
        assertTrue(PathPlan.onBlockPlan(walk, null, PLAN_START),
                "a Traverse-first plan vouched for its own start even before the fix");
        assertTrue(PathPlan.onBlockPlan(walk, PLAN_START, PLAN_START),
                "and still does with the start term");
    }

    @Test
    void waypointMembershipIsUnchangedMidRoute() {
        // Once the bot is actually walking the plan, the waypoint term does the work exactly as before.
        BlockPathPlan plan = parkourFirstPlan();
        assertTrue(PathPlan.onBlockPlan(plan, PLAN_START, new BlockPos(46, 143, 210)),
                "standing on the Parkour's landing floor is on-route via the waypoint term");
        assertTrue(PathPlan.onBlockPlan(plan, PLAN_START, new BlockPos(46, 142, 208)),
                "the last step's floor, one below, is inside its +/-1 box");
    }

    @Test
    void anEmptyOrAbsentPlanIsNeverOnRoute() {
        assertFalse(PathPlan.onBlockPlan(null, PLAN_START, PLAN_START), "no plan vouches for nothing");
        assertFalse(PathPlan.onBlockPlan(planOf(), PLAN_START, PLAN_START),
                "an empty plan vouches for nothing — even at its own start");
    }
}
