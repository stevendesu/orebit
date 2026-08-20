package com.orebit.mod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan;
import com.orebit.mod.pathfinding.blockpathfinder.Movement;
import com.orebit.mod.pathfinding.blockpathfinder.MovementRegistry;
import com.orebit.mod.pathfinding.blockpathfinder.StepEdits;
import com.orebit.mod.worldmodel.pathing.NavGridView;
import com.orebit.mod.worldmodel.pathing.NavSection;
import com.orebit.mod.worldmodel.pathing.NavSectionBuilder;
import com.orebit.mod.worldmodel.pathing.NavStore;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

/**
 * The horizon-seam selection rule ({@link BotNavigator#horizonSeamWalk},
 * DESIGN-replan-handoff.md §3, R1; §10 U2): the waypoint a mid-motion re-search is seeded FROM is
 * the smallest {@code k ≥ cursor} whose prefix of per-step costs ({@link BlockPathPlan#stepCost})
 * reaches the search budget in ticks — subject to the commits-risk early stop (the walk never
 * crosses a parkour-family step or a deeper-than-safe Fall; the seam is that step's takeoff stand
 * cell), the §10 U2 broken-step clamp (never AT or PAST a move-invalidated step — the seam is
 * clamped to the last safe waypoint, evaluated against a live {@link NavGridView}), the terminus
 * cap, and the sync degenerate case (budget 0 → the current cursor). The plan-shape sections pass
 * a {@code null} grid (no clamp, no MC bootstrap semantics needed beyond class-loading); the U2
 * clamp section builds a real classified grid (the {@code PrefixIntegrityTest} fixture pattern).
 * The instance memo's epoch invalidation ({@code seamWalkEpoch} — an edit-epoch advance must
 * re-walk, since the clamp reads the grid) is NOT headlessly testable: it lives on the
 * bot-owning instance wrapper; the ReplanCourse prefixseal tile proves it live.
 */
class HorizonSeamWalkTest {

    private static boolean bootstrapped;

    @BeforeAll
    static void boot() {
        if (!bootstrapped) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            bootstrapped = true;
        }
    }

    private static final int SAFE_FALL = 3; // vanilla-mortal safe-fall window for the Fall-depth cases

    /** A plan over explicit stand cells + per-step costs (floors derived {@code stand.below()}, the
     *  full-block case; total = the sum, as reconstruct's telescoping g-deltas produce). */
    private static BlockPathPlan plan(Movement[] moves, float[] costs, BlockPos... stands) {
        int[] floorYs = new int[stands.length];
        float total = 0f;
        for (int i = 0; i < stands.length; i++) {
            floorYs[i] = stands[i].getY() - 1;
            total += costs[i];
        }
        return new BlockPathPlan(Arrays.asList(stands), Arrays.asList(moves),
                Arrays.asList(new StepEdits[stands.length]), floorYs, costs, total);
    }

    /** {@code n} straight-line Traverse stands along +x at stand height 65. */
    private static BlockPos[] line(int n) {
        BlockPos[] stands = new BlockPos[n];
        for (int i = 0; i < n; i++) {
            stands[i] = new BlockPos(i + 1, 65, 0);
        }
        return stands;
    }

    private static Movement[] traverses(int n) {
        Movement[] moves = new Movement[n];
        Arrays.fill(moves, MovementRegistry.TRAVERSE);
        return moves;
    }

    private static float[] costs(int n, float each) {
        float[] c = new float[n];
        Arrays.fill(c, each);
        return c;
    }

    // ---- prefix-sum selection -------------------------------------------------------------------------

    @Test
    void prefixSumPicksTheFirstWaypointWhoseCumulativeCostReachesTheBudget() {
        // Six Traverses at 4 ticks each. Budget 10: prefix sums from cursor 0 run 4, 8, 12 — the first
        // k whose prefix REACHES the budget is k=2.
        BlockPathPlan p = plan(traverses(6), costs(6, 4f), line(6));
        assertEquals(2, BotNavigator.horizonSeamWalk(p, 0, 10L, SAFE_FALL, null));
        // The candidate window starts at the CURSOR (the current move's destination is the floor):
        // from cursor 2 with budget 5 the prefix runs 4, 8 → k=3.
        assertEquals(3, BotNavigator.horizonSeamWalk(p, 2, 5L, SAFE_FALL, null));
    }

    @Test
    void aPrefixThatMeetsTheBudgetExactlyStopsThere() {
        // ">= budgetTicks", not ">": 5+5 meets a budget of 10 at k=1 — the walk must not overshoot to k=2.
        BlockPathPlan p = plan(traverses(4), costs(4, 5f), line(4));
        assertEquals(1, BotNavigator.horizonSeamWalk(p, 0, 10L, SAFE_FALL, null));
    }

    // ---- commits-risk early stop ----------------------------------------------------------------------

    @Test
    void aCommittingStepInsideTheHorizonStopsTheWalkAtTheWaypointBefore() {
        // T T PARKOUR T, 1 tick each, budget far beyond the plan: the walk must stop at k=1 — the
        // parkour's takeoff stand cell — never on or past the committed arc (§3: this is what lets the
        // CAUTION hold rescope to the seam rather than grow).
        Movement[] moves = { MovementRegistry.TRAVERSE, MovementRegistry.TRAVERSE,
                MovementRegistry.PARKOUR, MovementRegistry.TRAVERSE };
        BlockPathPlan p = plan(moves, costs(4, 1f), line(4));
        assertEquals(1, BotNavigator.horizonSeamWalk(p, 0, 100L, SAFE_FALL, null));
        // Cursor already AT the takeoff stand: the very first probe sees the committing step and holds.
        assertEquals(1, BotNavigator.horizonSeamWalk(p, 1, 100L, SAFE_FALL, null));
    }

    @Test
    void theCursorsOwnCommittingStepIsTheFloorNotAStop() {
        // The current move's destination is the floor — the bot is already committed to it (§3). A
        // PARKOUR at the cursor itself must not wedge the walk: only steps AHEAD are probed.
        Movement[] moves = { MovementRegistry.TRAVERSE, MovementRegistry.PARKOUR,
                MovementRegistry.TRAVERSE, MovementRegistry.TRAVERSE };
        BlockPathPlan p = plan(moves, costs(4, 1f), line(4));
        assertEquals(3, BotNavigator.horizonSeamWalk(p, 1, 100L, SAFE_FALL, null));
    }

    @Test
    void aFallDeeperThanTheSafeWindowStopsTheWalkAShallowFallDoesNot() {
        // T FALL T with the Fall dropping 5 stand cells (65 → 60): deeper than SAFE_FALL=3, so the
        // walk holds at k=0, the drop's takeoff stand. The same shape under a 6-block window walks
        // through to the terminus — Fall depth rides the caps, not the move class.
        Movement[] moves = { MovementRegistry.TRAVERSE, MovementRegistry.FALL, MovementRegistry.TRAVERSE };
        BlockPos[] stands = { new BlockPos(1, 65, 0), new BlockPos(2, 60, 0), new BlockPos(3, 60, 0) };
        BlockPathPlan p = plan(moves, costs(3, 1f), stands);
        assertEquals(0, BotNavigator.horizonSeamWalk(p, 0, 100L, 3, null));
        assertEquals(2, BotNavigator.horizonSeamWalk(p, 0, 100L, 6, null));
    }

    @Test
    void theRiskPredicateIgnoresAFirstStepFallAndUncommittedMoves() {
        // The i>0 guard: a FALL at step 0 has no takeoff waypoint to measure a drop from, so it never
        // reads as committing — and an ordinary Traverse never does at any depth.
        Movement[] moves = { MovementRegistry.FALL, MovementRegistry.TRAVERSE };
        BlockPos[] stands = { new BlockPos(1, 60, 0), new BlockPos(2, 60, 0) };
        BlockPathPlan p = plan(moves, costs(2, 1f), stands);
        assertFalse(BotNavigator.stepCommitsRisk(p, 0, 0), "a step-0 Fall has no takeoff to drop from");
        assertFalse(BotNavigator.stepCommitsRisk(p, 1, 0), "a Traverse never commits across arrival");
        assertTrue(BotNavigator.stepCommitsRisk(
                plan(new Movement[] { MovementRegistry.WALK_OFF }, costs(1, 1f), line(1)), 0, 99),
                "the committed family reads by move NATURE, independent of depth");
    }

    // ---- §10 U2 broken-step clamp ---------------------------------------------------------------------
    // Fixture (the PrefixIntegrityTest pattern): one chunk (0,0), minY 0, a stone floor at y=0, air
    // above; `mutate` stamps the scenario's blocks before classification. Stands sit at y=1 so the real
    // classifier produces the descriptors the U4 predicate reads.

    private static final int MINY = 0;

    private static NavGridView grid(Consumer<PalettedContainer<BlockState>> mutate) {
        PalettedContainer<BlockState> s0 = emptyStates();
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                s0.set(x, 0, z, Blocks.STONE.defaultBlockState());
            }
        }
        if (mutate != null) {
            mutate.accept(s0);
        }
        ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        chunks.put(NavStore.key(0, 0),
                new NavSection[] { classify(s0, false), airSection(), airSection(), airSection() });
        return NavGridView.overSections(MINY, chunks);
    }

    private static PalettedContainer<BlockState> emptyStates() {
        return new PalettedContainer<>(Blocks.AIR.defaultBlockState(),
                Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
    }

    private static NavSection classify(PalettedContainer<BlockState> states, boolean allAir) {
        NavSection section = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(states, allAir, section.getTraversalGrid());
        return section;
    }

    private static NavSection airSection() {
        return classify(emptyStates(), true);
    }

    /** {@code n} straight-line Traverse stands along +x at stand height 1 (on the fixture's y=0 floor). */
    private static BlockPos[] lowLine(int n) {
        BlockPos[] stands = new BlockPos[n];
        for (int i = 0; i < n; i++) {
            stands[i] = new BlockPos(i + 1, 1, 0);
        }
        return stands;
    }

    @Test
    void aMoveInvalidatedStepInsideTheHorizonClampsTheWalkToTheWaypointBefore() {
        // Six Traverses on the stone floor, budget far beyond the plan (unclamped the walk reaches the
        // terminus k=5). A wall stamped into step 3's feet cell (4,1,0) move-invalidates it (U4 ground
        // arm), so the U2 clamp stops the walk at k=2 — the last safe waypoint, never AT or PAST the
        // broken step.
        BlockPathPlan p = plan(traverses(6), costs(6, 1f), lowLine(6));
        assertEquals(5, BotNavigator.horizonSeamWalk(p, 0, 1000L, SAFE_FALL, grid(null)),
                "an untouched grid clamps nothing — the terminus cap is the only stop");
        NavGridView g = grid(s -> s.set(4, 1, 0, Blocks.STONE.defaultBlockState()));
        assertEquals(2, BotNavigator.horizonSeamWalk(p, 0, 1000L, SAFE_FALL, g));
    }

    @Test
    void aSealAtCursorPlusOneClampsTheSeamToTheCursorItself() {
        // The sync course-failure shape (DESIGN-replan-handoff.md §10): the NEXT step's cells sealed
        // while the bot walks the current move. The seam must be the current move's destination — the
        // cursor — in ANY budget regime; seeding from or past the sealed cell is exactly the bug the
        // unified design closes.
        BlockPathPlan p = plan(traverses(4), costs(4, 1f), lowLine(4));
        NavGridView g = grid(s -> s.set(3, 1, 0, Blocks.STONE.defaultBlockState())); // step 2's feet
        assertEquals(1, BotNavigator.horizonSeamWalk(p, 1, 100L, SAFE_FALL, g));
    }

    @Test
    void aBrokenStepAtTheCursorDoesNotClampTheWalk() {
        // The cursor itself is the walk's floor (the bot is already committed to it — a broken CURRENT
        // step is the §10 U5 emergency, owned elsewhere): only steps AHEAD are probed, so the walk
        // proceeds past its own broken floor to the terminus.
        BlockPathPlan p = plan(traverses(3), costs(3, 1f), lowLine(3));
        NavGridView g = grid(s -> s.set(2, 1, 0, Blocks.STONE.defaultBlockState())); // step 1's feet
        assertEquals(2, BotNavigator.horizonSeamWalk(p, 1, 100L, SAFE_FALL, g));
    }

    // ---- sync degenerate case -------------------------------------------------------------------------

    @Test
    void budgetZeroReturnsTheCurrentCursor() {
        // Sync mode: budgetNanos 0 → budgetTicks 0 → k == cursor, one rule for both modes (§3). Even a
        // committing step immediately ahead changes nothing — the walk never leaves the cursor.
        Movement[] moves = { MovementRegistry.TRAVERSE, MovementRegistry.TRAVERSE,
                MovementRegistry.TRAVERSE, MovementRegistry.TRAVERSE, MovementRegistry.PARKOUR };
        BlockPathPlan p = plan(moves, costs(5, 4f), line(5));
        assertEquals(0, BotNavigator.horizonSeamWalk(p, 0, 0L, SAFE_FALL, null));
        assertEquals(3, BotNavigator.horizonSeamWalk(p, 3, 0L, SAFE_FALL, null));
    }

    // ---- terminus cap ---------------------------------------------------------------------------------

    @Test
    void theWalkNeverPassesTheTerminus() {
        // A budget worth far more than the whole plan caps at size()-1 (degenerating toward P4's
        // existing path.floor(last) seed).
        BlockPathPlan p = plan(traverses(4), costs(4, 1f), line(4));
        assertEquals(3, BotNavigator.horizonSeamWalk(p, 0, 1000L, SAFE_FALL, null));
    }

    // ---- degenerate plans -----------------------------------------------------------------------------

    @Test
    void oneStepPlansSeamAtTheirOnlyWaypoint() {
        BlockPathPlan p = plan(traverses(1), costs(1, 4f), line(1));
        assertEquals(0, BotNavigator.horizonSeamWalk(p, 0, 0L, SAFE_FALL, null), "sync: the cursor itself");
        assertEquals(0, BotNavigator.horizonSeamWalk(p, 0, 1000L, SAFE_FALL, null), "terminus cap at step 0");
    }

    @Test
    void emptyConsumedOrAbsentPlansHaveNoSeam() {
        assertEquals(-1, BotNavigator.horizonSeamWalk(null, 0, 100L, SAFE_FALL, null), "no plan");
        BlockPathPlan empty = plan(traverses(0), costs(0, 0f), new BlockPos[0]);
        assertEquals(-1, BotNavigator.horizonSeamWalk(empty, 0, 100L, SAFE_FALL, null), "empty plan");
        BlockPathPlan p = plan(traverses(2), costs(2, 1f), line(2));
        assertEquals(-1, BotNavigator.horizonSeamWalk(p, 2, 100L, SAFE_FALL, null),
                "a consumed plan (cursor past the last step) has no walkable incumbent");
    }

    // ---- §11 terminal view (DESIGN-replan-handoff.md §11, owner ruling 2026-08-20) --------------------

    @Test
    void theWalkNeverPassesTheTruncatedTerminal() {
        // A seam-truncated plan ends at planTerminalIndex for this follower — the walk caps there
        // exactly as it caps at the real terminus (a seam past the truncation would seed a re-search
        // from a cell the bot will never settle on).
        BlockPathPlan p = plan(traverses(6), costs(6, 1f), line(6));
        assertEquals(2, BotNavigator.horizonSeamWalk(p, 0, 1000L, SAFE_FALL, null, 2),
                "terminal 2 caps the walk at step 2");
        assertEquals(5, BotNavigator.horizonSeamWalk(p, 0, 1000L, SAFE_FALL, null, Integer.MAX_VALUE),
                "MAX_VALUE = untruncated, byte-identical to the 5-arg overload");
    }

    @Test
    void aCursorPastTheTruncatedTerminalHasNoWalkableIncumbent() {
        // Holding at a truncated terminal (cursor advanced past it) is the consumed shape under the
        // terminal view: no seam — every launch site seeds from the settled floor, as before.
        BlockPathPlan p = plan(traverses(6), costs(6, 1f), line(6));
        assertEquals(-1, BotNavigator.horizonSeamWalk(p, 3, 100L, SAFE_FALL, null, 2));
    }
}
