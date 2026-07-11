package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.orebit.mod.Debug;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.MovementRegistry;
import com.orebit.mod.pathfinding.blockpathfinder.RegionBound;
import com.orebit.mod.pathfinding.blockpathfinder.movements.Parkour;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

/**
 * Headless proof of the {@code Parkour} RISING(+1) and FALLING(−1..) landing classes ({@code ParkourTest}
 * covers flat). One sealed stone section, a 1-wide corridor at {@code z=8}: a start platform (floor
 * {@code y=5}, {@code x=1..4}), a {@code g}-wide BOTTOMLESS chasm ({@code x=5..4+g}, air to {@code y=0};
 * below the grid is unbuilt so {@link com.orebit.mod.pathfinding.blockpathfinder.movements.Fall Fall}
 * never lands), and a landing platform at a parameterised floor height ({@code y=6} rising, {@code y=4/3}
 * falling, {@code y=5} flat). The bot has {@link BotCaps#DEFAULT} (no break/place) unless stated, so the
 * jump is the only route.
 *
 * <p><b>Positives.</b> Rising(+1) over a 2-gap with DEFAULT caps (no pillar available, no detour exists)
 * lands as exactly ONE Parkour waypoint at the STAND position {@code floor.above()}; a placing bot still
 * picks the 16.6-tick rising jump over a ~2-place bridge + step-up. Falling(−1) crosses a 3-gap AND the
 * owner-verified 4-gap; the deeper drops (−2/−3) are in the same DERIVED envelope ({@code ParkourEnvelope}).
 * The rising 3-gap is EXCLUDED — the derived envelope caps rising at 2 from a full-block takeoff, fixing
 * the old hardcoded RISE_MAX=3 that offered an unmakeable jump the bot fell short of. Fall damage is a
 * COST, not a
 * blocker: shrinking {@code safeFallDistance} on an otherwise-identical bot raises the SAME route's plan
 * cost by exactly {@code (drop − safeFall) ·} {@link BotCaps#costPerHitpoint} (the unified damage knob —
 * ≈1 HP per excess block × ticks-per-HP; both bots here carry the 100-tick default). A drop beyond
 * {@code maxFallDistance} is never emitted at all (plan is null, not merely expensive).
 *
 * <p><b>Envelope negatives.</b> A 5-gap is beyond every row of the table (never offered); a blocked
 * transit cell in a gap column
 * ({@code y+2} stone) kills the whole direction; the rising jump's EXTRA clearance row ({@code y+4} over
 * the takeoff column and over a gap column) is verified — blocking either kills the +1 jump; a standable
 * cell mid-gap ENDS the scan (never overfly a ledge): at the flat cap of 3, a floored
 * island in the middle of a 3-gap forces TWO 1-gap jumps instead of the cheaper single 3-gap jump the
 * scan must no longer see.
 *
 * <p>Flags touched ({@link Parkour#PARKOUR_MAX_GAP}) are restored in
 * {@code finally}. Not testable headless: jump kinematics, drop-control steering, missed-jump recovery
 * (the in-game pass).
 */
class ParkourLandingsTest {

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

    private static final RegionBound CORRIDOR = new RegionBound(0, 15, 0, 15, 0, 15);
    private static final BlockPos START = new BlockPos(2, 5, 8);

    // ---------------------------------------------------------------- rising (+1)

    @Test
    void risingJumpLandsOneLedgeUpWithDefaultCaps() {
        // 2-gap, landing floor y=6: the only route is the rising jump (DEFAULT caps cannot pillar and the
        // corridor offers no detour).
        NavGridView grid = buildCourse(2, 6, null);
        BlockPathPlan plan = BlockPathfinder.findPath(grid, START, new BlockPos(8, 6, 8),
                BotCaps.DEFAULT, CORRIDOR);

        assertNotNull(plan, "a no-place bot should rising-jump the 2-gap onto the +1 ledge");
        assertEquals(1, count(plan, MovementRegistry.PARKOUR),
                "one rising jump should be exactly one Parkour waypoint");
        // Waypoints are STAND positions: landing floor (7,6,8) -> feet at (7,7,8).
        assertEquals(new BlockPos(7, 7, 8), waypointOf(plan, MovementRegistry.PARKOUR),
                "the rising jump's waypoint should be the stand position above the +1 landing floor");
    }

    @Test
    void risingJumpBeatsPillarBridgingForAPlacingBot() {
        NavGridView grid = buildCourse(2, 6, null);
        BlockPathPlan plan = BlockPathfinder.findPath(grid, START, new BlockPos(8, 6, 8),
                BotCaps.BREAK_PLACE, CORRIDOR);

        assertNotNull(plan, "a placing bot should certainly cross onto the +1 ledge");
        assertTrue(count(plan, MovementRegistry.PARKOUR) >= 1,
                "the 16.6-tick rising jump should beat a 2-place bridge + step-up");
    }

    @Test
    void risingThreeGapIsNotOffered() {
        // 3-gap, landing floor y=6 (a rising +1 landing): the DERIVED envelope caps rising at 2 from a
        // full-block takeoff (ParkourEnvelope BASE row: rise 2). The old hardcoded RISE_MAX=3 OVER-offered
        // this jump — the bot attempted it and fell SHORT in live runs. With rising-3 correctly excluded
        // and no other route across the bottomless chasm (DEFAULT caps, no place), the search finds none.
        NavGridView grid = buildCourse(3, 6, null);
        BlockPos goal = new BlockPos(9, 6, 8);
        BlockPathPlan plan = BlockPathfinder.findPath(grid, START, goal, BotCaps.DEFAULT, CORRIDOR);
        assertNull(plan, "the rising 3-gap must NOT be offered (derived envelope caps rising at 2)");
    }

    @Test
    void missingRisingHeadroomKillsTheJump() {
        // The +1 arc needs the y+4 row (y=9) clear over takeoff, gap and landing columns. Block it over
        // the gap column first, then over the takeoff column — either alone must kill the jump.
        NavGridView overGap = buildCourse(1, 6, new BlockPos(5, 9, 8));
        assertNull(BlockPathfinder.findPath(overGap, START, new BlockPos(7, 6, 8),
                        BotCaps.DEFAULT, CORRIDOR),
                "a block at y+4 over the gap column must kill the rising jump (no other route exists)");

        NavGridView overTakeoff = buildCourse(1, 6, new BlockPos(4, 9, 8));
        assertNull(BlockPathfinder.findPath(overTakeoff, START, new BlockPos(7, 6, 8),
                        BotCaps.DEFAULT, CORRIDOR),
                "a block at y+4 over the takeoff column must kill the rising jump");
    }

    @Test
    void blockedTransitColumnKillsTheJump() {
        // Stone at y+2 in the gap column: the shared transit prism is blocked, ending the direction before
        // any landing class can be found (and y+2 is not a standable-ledge false-positive like y+1 is).
        NavGridView grid = buildCourse(1, 6, new BlockPos(5, 7, 8));
        BlockPathPlan plan = BlockPathfinder.findPath(grid, START, new BlockPos(7, 6, 8),
                BotCaps.DEFAULT, CORRIDOR);

        assertNull(plan, "a blocked transit cell in the gap column must end the whole direction");
    }

    // ---------------------------------------------------------------- falling (−1 ..)

    @Test
    void fallingJumpDropsOneAcrossAThreeWideGap() {
        // 3-gap, landing floor y=4: no flat/rising landing exists (the far platform is a block DOWN), so
        // this exercises the falling row (default envelope: drop 1, gaps 1–4). The goal IS the landing
        // floor: with the falling 4-gap now in the default envelope, a farther goal would legitimately be
        // reached by one 4-gap jump overflying this landing column (its node-level cell is open air).
        NavGridView grid = buildCourse(3, 4, null);
        BlockPathPlan plan = BlockPathfinder.findPath(grid, START, new BlockPos(8, 4, 8),
                BotCaps.DEFAULT, CORRIDOR);

        assertNotNull(plan, "a falling(−1) jump should cross the 3-wide gap with default flags");
        assertEquals(1, count(plan, MovementRegistry.PARKOUR),
                "one falling jump should be exactly one Parkour waypoint");
        // Stand position: landing floor (8,4,8) -> feet at (8,5,8).
        assertEquals(new BlockPos(8, 5, 8), waypointOf(plan, MovementRegistry.PARKOUR),
                "the falling jump's waypoint should be the stand position above the −1 landing floor");
    }

    @Test
    void fallDamagePastTheSafeWindowIsChargedAsCost() {
        // Same course, two bots differing ONLY in safeFallDistance: 3 (drop 1 is free) vs 0 (drop 1 costs
        // one block ≈ 1 HP of damage). The route is forced, so the plan costs differ by exactly
        // costPerHitpoint (both bots carry the 100-tick default).
        NavGridView grid = buildCourse(3, 4, null);
        BlockPathPlan free = BlockPathfinder.findPath(grid, START, new BlockPos(9, 4, 8),
                BotCaps.DEFAULT, CORRIDOR);
        BlockPathPlan hurt = BlockPathfinder.findPath(grid, START, new BlockPos(9, 4, 8),
                capsWithFallWindow(0, BotCaps.DEFAULT_MAX_FALL), CORRIDOR);

        assertNotNull(free, "the damage-free bot should jump");
        assertNotNull(hurt, "damage is a cost, not a blocker — the fragile bot still jumps");
        assertEquals(BotCaps.DEFAULT_COST_PER_HITPOINT, hurt.cost() - free.cost(), 1e-3,
                "one block past the safe window should surcharge exactly 1 HP × costPerHitpoint");
    }

    @Test
    void fallingFourGapIsOpenByDefault() {
        // 4-gap, drop 1 — the owner-verified maximum, in the DEFAULT envelope after the flip (was
        // aggressive-gated).
        NavGridView grid = buildCourse(4, 4, null);
        BlockPathPlan plan = BlockPathfinder.findPath(grid, START, new BlockPos(10, 4, 8),
                BotCaps.DEFAULT, CORRIDOR);
        assertNotNull(plan, "the owner-verified falling 4-gap should be offered with default flags");
        assertEquals(1, count(plan, MovementRegistry.PARKOUR),
                "the 4-gap falling jump should still be a single Parkour waypoint");
        assertEquals(new BlockPos(9, 5, 8), waypointOf(plan, MovementRegistry.PARKOUR),
                "stand position above the −1 landing floor at the far side of the 4-gap");
    }

    @Test
    void fallingFiveGapIsNeverOffered() {
        // A 5-gap exceeds every row of the (one, unconditional) envelope table, so the scan horizon
        // itself ends before the far platform and no route exists.
        NavGridView grid = buildCourse(5, 4, null);
        BlockPos goal = new BlockPos(11, 4, 8);
        assertNull(BlockPathfinder.findPath(grid, START, goal, BotCaps.DEFAULT, CORRIDOR),
                "a falling 5-gap must never be offered");
    }

    @Test
    void deeperFallingJumpIsOfferedAndChargesDamage() {
        // 2-gap, landing floor y=3 (drop 2): in the one unconditional envelope (s52). Shrinking the
        // safe window to 0 surcharges 2 blocks of damage on the same forced route.
        NavGridView grid = buildCourse(2, 3, null);
        BlockPos goal = new BlockPos(8, 3, 8);
        BlockPathPlan free = BlockPathfinder.findPath(grid, START, goal, BotCaps.DEFAULT, CORRIDOR);
        assertNotNull(free, "the −2 falling jump should be offered (one envelope, always on)");
        assertEquals(1, count(free, MovementRegistry.PARKOUR),
                "one −2 falling jump should be exactly one Parkour waypoint");
        // Any landing on the low platform stands at y=4 (floor y=3 + 1) — the exact column is the
        // search's choice between equal-drop landings, so pin only the stand height.
        assertEquals(4, waypointOf(free, MovementRegistry.PARKOUR).getY(),
                "the −2 jump's waypoint should STAND one above the y=3 landing floor");

        BlockPathPlan hurt = BlockPathfinder.findPath(grid, START, goal,
                capsWithFallWindow(0, BotCaps.DEFAULT_MAX_FALL), CORRIDOR);
        assertNotNull(hurt, "damage is a cost, not a blocker");
        assertEquals(2 * BotCaps.DEFAULT_COST_PER_HITPOINT, hurt.cost() - free.cost(), 1e-3,
                "two blocks past the safe window should surcharge exactly 2 HP × costPerHitpoint");
    }

    @Test
    void fallingJumpIsNeverEmittedBeyondMaxFallDistance() {
        // Same −2 course (the envelope TABLE offers the drop unconditionally): a bot whose
        // maxFallDistance is 1 must not get the candidate at all — rejection, not a damage surcharge.
        NavGridView grid = buildCourse(2, 3, null);
        BlockPos goal = new BlockPos(8, 3, 8);
        assertNotNull(BlockPathfinder.findPath(grid, START, goal, BotCaps.DEFAULT, CORRIDOR),
                "sanity: the −2 jump is open for a bot whose caps allow the drop");
        assertNull(BlockPathfinder.findPath(grid, START, goal, capsWithFallWindow(1, 1), CORRIDOR),
                "a drop beyond maxFallDistance must never be emitted, however the table reads");
    }

    // ---------------------------------------------------------------- scan termination

    @Test
    void standableMidGapCellEndsTheScan() {
        // A floored island in the middle of a 3-gap (flat course), at the flat cap of 3 (the default
        // since the envelope flip — pinned explicitly so the test outlives future default changes): the
        // single 3-gap jump (21.6 ticks) would be cheaper than two 1-gap jumps (31.2), so if the scan
        // wrongly overflew the standable island the plan would hold ONE Parkour waypoint. The ratified
        // never-overfly-a-ledge rule ends the scan at the island, forcing exactly two.
        NavGridView grid = buildCourse(3, 5, new BlockPos(6, 5, 8));
        int saved = Parkour.PARKOUR_MAX_GAP;
        Parkour.PARKOUR_MAX_GAP = 3;
        try {
            BlockPathPlan plan = BlockPathfinder.findPath(grid, START, new BlockPos(9, 5, 8),
                    BotCaps.DEFAULT, CORRIDOR);
            assertNotNull(plan, "the island splits the 3-gap into two jumpable 1-gaps");
            assertEquals(2, count(plan, MovementRegistry.PARKOUR),
                    "a standable mid-gap cell must END the scan: two 1-gap jumps, never one 3-gap overflight");
        } finally {
            Parkour.PARKOUR_MAX_GAP = saved;
        }
    }

    // ---------------------------------------------------------------- helpers

    /** DEFAULT caps with a custom fall window (everything else identical, incl. search params). */
    private static BotCaps capsWithFallWindow(int safeFall, int maxFall) {
        return new BotCaps(1, safeFall, maxFall, true, BotCaps.DEFAULT_COST_PER_HITPOINT, false, false,
                BotCaps.UNBREAKABLE, false, BotCaps.DEFAULT_MAX_NODES, BotCaps.DEFAULT_GREEDY_WEIGHT);
    }

    private static int count(BlockPathPlan plan, Object move) {
        int n = 0;
        for (int i = 0; i < plan.size(); i++) {
            if (plan.movement(i) == move) n++;
        }
        return n;
    }

    /** The stand-position waypoint of the first step using {@code move} (null if none). */
    private static BlockPos waypointOf(BlockPathPlan plan, Object move) {
        for (int i = 0; i < plan.size(); i++) {
            if (plan.movement(i) == move) return plan.waypoint(i);
        }
        return null;
    }

    /**
     * One sealed stone section, corridor at {@code z=8}: start platform {@code x=1..4} (floor y=5, air
     * y=6..10 — the rising arc needs the y+4=9 row), bottomless chasm {@code x=5..4+g} (air y=0..10;
     * below the grid is unbuilt so Fall never lands), landing platform {@code x=5+g..14} with its floor
     * at {@code landingFloorY} (air above it to y=10). {@code extraStone} (nullable) re-fills one cell
     * with stone — the ceiling/blocker/island knob.
     */
    private static NavGridView buildCourse(int g, int landingFloorY, BlockPos extraStone) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();

        PalettedContainer<BlockState> s = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    s.set(x, y, z, stone);
                }
            }
        }

        final int z = 8;
        for (int x = 1; x <= 4; x++) {              // start platform: floor y=5, headroom to y=10
            for (int y = 6; y <= 10; y++) {
                s.set(x, y, z, air);
            }
        }
        for (int x = 5; x <= 4 + g; x++) {          // the chasm: bottomless
            for (int y = 0; y <= 10; y++) {
                s.set(x, y, z, air);
            }
        }
        for (int x = 5 + g; x <= 14; x++) {         // landing platform at the parameterised height
            for (int y = landingFloorY + 1; y <= 10; y++) {
                s.set(x, y, z, air);
            }
        }
        if (extraStone != null) {
            s.set(extraStone.getX(), extraStone.getY(), extraStone.getZ(), stone);
        }

        NavSection section = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(s, false, section.getTraversalGrid());

        PalettedContainer<BlockState> airStates = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        NavSection airSection = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(airStates, true, airSection.getTraversalGrid());

        NavSection[] column = { section, airSection, airSection, airSection };
        ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        chunks.put(NavStore.key(0, 0), column);
        return new NavGridView(0, chunks);
    }
}
