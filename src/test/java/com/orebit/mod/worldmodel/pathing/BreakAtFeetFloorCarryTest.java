package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.orebit.mod.Debug;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.BotSteering;
import com.orebit.mod.pathfinding.blockpathfinder.MovePlan;
import com.orebit.mod.pathfinding.blockpathfinder.MovementRegistry;
import com.orebit.mod.pathfinding.blockpathfinder.PhaseRunner;
import com.orebit.mod.pathfinding.blockpathfinder.RegionBound;
import com.orebit.mod.pathfinding.blockpathfinder.StepEdits;
import com.orebit.mod.pathfinding.blockpathfinder.SteerView;
import com.orebit.mod.pathfinding.blockpathfinder.movements.Ascend;
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
 * PATHOLOGY P1B — the follower-side floor-frame carry. The search models nodes as FLOOR cells and used to
 * throw them away in reconstruct, leaving the follower to re-invert each feet waypoint ({@code floorOf}).
 * That inversion assumes the feet cell is air or a bottom-partial; a <b>break-at-feet</b> step (the feet
 * cell solid until the step's own folded break runs) reads as a standable floor and drifts the whole
 * execution frame +1 — the block to be mined becomes the FOOTING and is never mined (the (87,63,-32)
 * Ascend jump-loop). The fix carries the search-native floor Y per waypoint ({@link BlockPathPlan#floorY}).
 *
 * <p>Headless proof, in three parts:
 * <ol>
 *   <li>a break-at-feet Ascend produced by the REAL search carries the true floor even though the live
 *       feet cell reads standable (the exact cell the old inversion drifted on);</li>
 *   <li>the {@link MovePlan} built from the carried frame mines exactly the step's folded break cells
 *       (mine-then-move via the existing {@link PhaseRunner} hold — zero new mechanism), while the drifted
 *       frame now trips {@link Ascend}'s contract tripwire instead of building a fiction;</li>
 *   <li>for every step whose feet cell is air or a bottom-partial (the overwhelming common case, incl. a
 *       slab floor) the carried floor is IDENTICAL to the old {@code floorOf}-style derivation — the fix
 *       changes nothing outside the pathological case.</li>
 * </ol>
 * Lives in this package to reach {@link NavGridView}'s package-private synthetic constructor (the
 * {@code ClimbTest} idiom); the runner part drives only the MC-free {@link BotSteering} seam (the
 * {@code PhaseRunnerDoorTest} idiom).
 */
class BreakAtFeetFloorCarryTest {

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

    private static final RegionBound CORRIDOR = new RegionBound(0, 15, 0, 31, 0, 15);
    private static final int Z = 8;

    private static long key(int x, int y, int z) { return BlockPos.asLong(x, y, z); }

    // ------------------------------------------------------------------------------------------------
    // Part 1+2 fixture: the specimen shape — a 1-up ledge whose riser feet/head cells are SOLID stone,
    // so the only sane route is a break-at-feet Ascend (brk=2 at the landing feet + head).
    // ------------------------------------------------------------------------------------------------

    /** Start floor: the approach corridor. */
    private static final BlockPos LEDGE_START = new BlockPos(1, 0, Z);
    /** Goal floor: on top of the ledge, far enough that arrival tolerance can't skip the Ascend. */
    private static final BlockPos LEDGE_GOAL = new BlockPos(5, 1, Z);

    /**
     * Sealed stone section, one carved lane at {@code z=8}: an approach at floor y0 ({@code x=1..2}, body
     * {@code y1..3} air) and a ledge top at floor y1 ({@code x=4..5}, body {@code y2..3} air). The riser
     * column {@code x=3} stays SOLID through {@code y0..} — floor {@code (3,1,8)} plus the landing feet
     * {@code (3,2,8)} and head {@code (3,3,8)} the Ascend must fold breaks for (the break-at-feet step).
     */
    private static NavGridView buildLedge() {
        BlockState air = Blocks.AIR.defaultBlockState();
        PalettedContainer<BlockState> s = solidSection();
        for (int y = 1; y <= 3; y++) {
            s.set(1, y, Z, air);
            s.set(2, y, Z, air); // (2,3,8) doubles as the Ascend takeoff head clearance
        }
        for (int y = 2; y <= 3; y++) {
            s.set(4, y, Z, air);
            s.set(5, y, Z, air);
        }
        return view(classify(s));
    }

    /** The plan's break-at-feet Ascend step index, or −1 (an Ascend carrying folded breaks). */
    private static int breakAscendStep(BlockPathPlan plan) {
        for (int i = 0; i < plan.size(); i++) {
            if (plan.movement(i) == MovementRegistry.ASCEND
                    && plan.edits(i) != null && plan.edits(i).breakCount() > 0) {
                return i;
            }
        }
        return -1;
    }

    @Test
    void carriedFloorSurvivesASolidFeetCell() {
        NavGridView grid = buildLedge();
        BlockPathPlan plan = BlockPathfinder.findPath(grid, LEDGE_START, LEDGE_GOAL, BotCaps.BREAK_PLACE, CORRIDOR);
        assertNotNull(plan, "the ledge must be pathable by a break-capable bot");

        int i = breakAscendStep(plan);
        assertTrue(i >= 0, "the plan should contain the break-at-feet Ascend onto the ledge");
        BlockPos wp = plan.waypoint(i);
        assertEquals(new BlockPos(3, 2, Z), wp, "the Ascend's feet waypoint is the ledge riser's feet cell");

        // The drift precondition, pinned: the live feet cell IS solid+standable (it's the block to be mined),
        // so the old floorOf-style inversion would have returned the feet cell itself — one above the truth.
        assertTrue(grid.built(3, 2, Z) && NavBlock.isStandable(grid.descriptorAt(3, 2, Z)),
                "the feet cell must read standable — the exact state the old inversion drifted on");
        assertEquals(wp.getY(), floorOfStyle(grid, wp), "the old derivation drifts +1 on this step (documented)");

        // The fix: the carried floor is the search's own node — one below the feet waypoint, drift-proof.
        assertEquals(1, plan.floorY(i), "the carried floor Y is the search-native floor (3,1,8)");
        assertEquals(new BlockPos(3, 1, Z), plan.floor(i));
    }

    @Test
    void phasePlanFromCarriedFrameMinesExactlyTheFoldedBreaks() {
        NavGridView grid = buildLedge();
        BlockPathPlan plan = BlockPathfinder.findPath(grid, LEDGE_START, LEDGE_GOAL, BotCaps.BREAK_PLACE, CORRIDOR);
        assertNotNull(plan);
        int i = breakAscendStep(plan);
        assertTrue(i >= 0);
        BlockPos wp = plan.waypoint(i);
        BlockPos from = (i == 0) ? LEDGE_START : plan.floor(i - 1); // the follower's carried from-floor

        // Build the step's MovePlan in the CARRIED frame — exactly what steerAlongPath now does.
        MovePlan mp = plan.movement(i).plan(from.getX(), from.getY(), from.getZ(),
                wp.getX(), plan.floorY(i), wp.getZ(),
                from.getY() + 1, wp.getY()); // full-block from-foot; wp.getY() is the topY-aware to-foot
        assertNotNull(mp, "Ascend is a converted move — it must produce a phase plan for its contract frame");

        // Drive the runner against a bot double seeded with the fixture's live riser cells.
        FakeBot bot = new FakeBot();
        bot.standAt(from.getX(), from.getY() + 1, from.getZ()); // on the from-stand — inside the envelope
        bot.solid.add(key(3, 0, Z));
        bot.solid.add(key(3, 1, Z)); // the true floor — already-solid footing, nothing to place
        bot.solid.add(key(3, 2, Z)); // folded break #1 (the feet cell)
        bot.solid.add(key(3, 3, Z)); // folded break #2 (the head cell)
        PhaseRunner runner = new PhaseRunner();
        runner.begin(mp);
        for (int t = 0; t < 4; t++) {
            runner.run(bot, new View());
        }

        assertFalse(runner.failed(), "the carried frame satisfies the Ascend contract — no envelope failure");
        Set<Long> folded = new HashSet<>();
        StepEdits e = plan.edits(i);
        for (int b = 0; b < e.breakCount(); b++) {
            folded.add(e.breakAt(b));
        }
        assertEquals(folded, new HashSet<>(bot.mineCalls),
                "the plan's AIR needs mine EXACTLY the step's folded break cells — not the +1-drifted pair");
        assertTrue(bot.placeCalls.isEmpty(), "the true floor is already solid — nothing is placed");
        assertEquals(1, runner.phase(), "with the breaks mined and the footing real, BUILD advances to CLIMB");
    }

    @Test
    void driftedFrameTripsTheAscendContractTripwire() {
        // The historical failure frame: floorOf drifted the to-floor +1, so Ascend.plan saw ty == fy + 2.
        // The tripwire reports it through the EXISTING validity-envelope FAILED path — detection, not
        // recovery: no phase runs, nothing is mined or placed from the fictional frame.
        MovePlan drifted = ((Ascend) MovementRegistry.ASCEND).plan(2, 0, Z, 3, 2, Z, 1, 3); // tripwire fires before using feet
        FakeBot bot = new FakeBot();
        bot.solid.add(key(3, 2, Z));
        PhaseRunner runner = new PhaseRunner();
        runner.begin(drifted);
        boolean done = runner.run(bot, new View());

        assertFalse(done);
        assertTrue(runner.failed(), "a frame violating ty == fy+1 must FAIL the step, not build a 2-block jump");
        assertTrue(bot.mineCalls.isEmpty(), "a failed plan must never mine from its fictional frame");
        assertTrue(bot.placeCalls.isEmpty(), "a failed plan must never place from its fictional frame");

        // And the contract-satisfying frame is untouched by the tripwire.
        MovePlan sane = ((Ascend) MovementRegistry.ASCEND).plan(2, 0, Z, 3, 1, Z, 1, 2); // full-block feet == floor+1
        bot.standAt(2, 1, Z); // on the from-stand — the position envelope must stay silent here
        runner.begin(sane);
        runner.run(bot, new View());
        assertFalse(runner.failed(), "ty == fy+1 must not trip the wire");
    }

    // ------------------------------------------------------------------------------------------------
    // Part 3 fixture: a sealed flat corridor with a bottom-slab step — every feet cell is air or a
    // standable partial, so the carried floor must be byte-identical to the old floorOf derivation.
    // ------------------------------------------------------------------------------------------------

    private static final BlockPos LANE_START = new BlockPos(1, 0, Z);
    private static final BlockPos LANE_GOAL = new BlockPos(10, 0, Z);

    /** One carved lane {@code x=1..10, y1..3} over a stone floor, with a bottom slab at {@code (5,1,8)}
     *  the 1-wide lane forces the path over (the partial-floor waypoint the equivalence must cover). */
    private static NavGridView buildSlabLane() {
        BlockState air = Blocks.AIR.defaultBlockState();
        PalettedContainer<BlockState> s = solidSection();
        for (int x = 1; x <= 10; x++) {
            for (int y = 1; y <= 3; y++) {
                s.set(x, y, Z, air);
            }
        }
        s.set(5, 1, Z, Blocks.STONE_SLAB.defaultBlockState()); // bottom slab riding the y0 floor
        return view(classify(s));
    }

    @Test
    void carriedFloorEqualsTheOldDerivationOnAirAndPartialFeet() {
        NavGridView grid = buildSlabLane();
        BlockPathPlan plan = BlockPathfinder.findPath(grid, LANE_START, LANE_GOAL, BotCaps.DEFAULT, CORRIDOR);
        assertNotNull(plan, "the slab lane must be walkable");

        boolean sawPartial = false;
        for (int i = 0; i < plan.size(); i++) {
            BlockPos wp = plan.waypoint(i);
            assertEquals(floorOfStyle(grid, wp), plan.floorY(i),
                    "step " + i + " (" + plan.movement(i).getClass().getSimpleName() + " -> " + wp
                            + "): the carried floor must equal the old floorOf derivation on non-solid feet");
            sawPartial |= plan.floorY(i) == wp.getY();
        }
        assertTrue(sawPartial, "the lane must cross the slab so the partial-floor case is actually covered");
    }

    /** The OLD follower derivation ({@code BotNavigator.floorOf}), reproduced verbatim for the equivalence
     *  oracle: the feet cell itself when it reads standable, else one below. */
    private static int floorOfStyle(NavGridView grid, BlockPos wp) {
        boolean standable = grid.built(wp.getX(), wp.getY(), wp.getZ())
                && NavBlock.isStandable(grid.descriptorAt(wp.getX(), wp.getY(), wp.getZ()));
        return standable ? wp.getY() : wp.getY() - 1;
    }

    // ------------------------------------------------------------------------------------------------
    // Shared harness (the ClimbTest grid idiom + the PhaseRunnerDoorTest bot idiom)
    // ------------------------------------------------------------------------------------------------

    private static PalettedContainer<BlockState> solidSection() {
        PalettedContainer<BlockState> s = new PalettedContainer<>(
                Blocks.AIR.defaultBlockState(), Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        BlockState stone = Blocks.STONE.defaultBlockState();
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    s.set(x, y, z, stone);
                }
            }
        }
        return s;
    }

    private static NavSection classify(PalettedContainer<BlockState> states) {
        NavSection section = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(states, false, section.getTraversalGrid());
        return section;
    }

    /** A one-chunk synthetic grid: the section at y 0..15, air above. */
    private static NavGridView view(NavSection s0) {
        PalettedContainer<BlockState> airStates = new PalettedContainer<>(
                Blocks.AIR.defaultBlockState(), Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        NavSection airSection = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(airStates, true, airSection.getTraversalGrid());

        NavSection[] column = { s0, airSection, airSection, airSection };
        ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        chunks.put(NavStore.key(0, 0), column);
        return new NavGridView(0, chunks);
    }

    /** A {@link BotSteering} double: settable solid state; {@code mine} records AND clears the cell (the
     *  break completes), {@code place} records (never expected in these fixtures). Grounded, at rest. */
    private static final class FakeBot implements BotSteering {
        final Set<Long> solid = new HashSet<>();
        final List<Long> mineCalls = new ArrayList<>();
        final List<Long> placeCalls = new ArrayList<>();

        @Override public boolean solidAt(int x, int y, int z) { return solid.contains(key(x, y, z)); }
        @Override public boolean airAt(int x, int y, int z) { return !solidAt(x, y, z); }
        @Override public boolean movementBlockedAt(int x, int y, int z, int dx, int dz) { return solidAt(x, y, z); } // fake: solid == full obstruction
        @Override public void mine(int x, int y, int z) {
            mineCalls.add(key(x, y, z));
            solid.remove(key(x, y, z)); // the timed break completes; the runner re-validates next tick
        }
        @Override public void place(int x, int y, int z) { placeCalls.add(key(x, y, z)); }
        @Override public boolean doorOpenAt(int x, int y, int z) { return false; }
        @Override public void setDoorOpen(int x, int y, int z, boolean open) { }

        // ---- pose/velocity/medium seam: grounded at rest (locomotion is not under test) ----
        @Override public double x() { return 0; }
        @Override public double y() { return 0; }
        @Override public double z() { return 0; }
        @Override public double velX() { return 0; }
        @Override public double velY() { return 0; }
        @Override public double velZ() { return 0; }
        // Positionable foot cell: the Ascend validity envelope (failWhen) is position-sensitive, so
        // fixtures must stand the double on the plan's from-stand (or wherever the case needs it).
        int fx = 0, fy = 0, fz = 0;
        void standAt(int x, int y, int z) { fx = x; fy = y; fz = z; }
        @Override public int footX() { return fx; }
        @Override public int footY() { return fy; }
        @Override public int footZ() { return fz; }
        @Override public boolean grounded() { return true; }
        @Override public boolean inWater() { return false; }
        @Override public boolean inLava() { return false; }
        @Override public void faceHorizontally(double dx, double dz) { }
        @Override public boolean prone() { return false; }
        @Override public void faceTowards(double dx, double dy, double dz) { }
        @Override public void setForward(float zza) { }
        @Override public void setSprinting(boolean s) { }
        @Override public void setJumping(boolean j) { }
        @Override public void setSneak(boolean s) { }
        @Override public void sinkInWater() { }
        @Override public boolean swimHazardAt(int x, int y, int z) { return false; }
        @Override public boolean bubbleUpAt(int x, int y, int z) { return false; }
        @Override public double slipperinessAt(int x, int y, int z) { return 0.6; }
        @Override public boolean gapFloorHazardAt(int x, int y, int z) { return false; }
    }

    /** A trivial non-degenerate segment so a HOLD's recenterOnTarget has a column to pull toward. */
    private static final class View implements SteerView {
        @Override public double sx() { return 2.5; }
        @Override public double sy() { return 1.0; }
        @Override public double sz() { return 8.5; }
        @Override public double tx() { return 3.5; }
        @Override public double ty() { return 2.0; }
        @Override public double tz() { return 8.5; }
        @Override public boolean hasNext() { return false; }
        @Override public double nx() { return 0; }
        @Override public double ny() { return 0; }
        @Override public double nz() { return 0; }
    }
}
