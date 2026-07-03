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
import com.orebit.mod.pathfinding.blockpathfinder.EditSnapshot;
import com.orebit.mod.pathfinding.blockpathfinder.SpliceTestPlans;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;

/**
 * Headless integration proof of the splice baseline seed (DESIGN-background-pathfinding.md P0 /
 * DESIGN-portal-route-layer.md §4.3) at the {@link BlockPathfinder#findPath} level:
 *
 * <ul>
 *   <li><b>Null-baseline byte-identity</b>: the new baseline overload with {@code null} must return
 *       the exact plan of the historical overloads (same waypoints, same cost) — the "one compare per
 *       pop" guarantee, asserted on both an edit-free walk and an edit-bearing (place) search.</li>
 *   <li><b>Baseline BROKEN reads as air</b>: a walk-only bot (no break cap) crosses a solid plug ONLY
 *       when the baseline says an earlier plan will have mined it.</li>
 *   <li><b>Baseline PLACED reads as floor</b>: a walk-only bot crosses a 4-wide bottomless floor gap
 *       (too wide for flat parkour) ONLY when the baseline says an earlier plan will have bridged it.</li>
 * </ul>
 *
 * Fixture style mirrors {@code PassThroughHazardTest}: one sealed stone section, routes carved at
 * {@code y=1..2} over stone floors at {@code y=0}, start (2,0,6) → goal (12,0,6). Lives in this
 * package to reach {@link NavGridView}'s package-private synthetic constructor.
 */
class SpliceBaselineTest {

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

    private static final BlockPos START = new BlockPos(2, 0, 6);
    private static final BlockPos GOAL = new BlockPos(12, 0, 6);
    /** The solid plug column in the plugged corridor — its two body cells are the baseline's breaks. */
    private static final int PLUG_X = 7;
    /** The bottomless floor gap in the gap corridor — its floor cells are the baseline's places. */
    private static final int GAP_FROM_X = 6, GAP_TO_X = 9;

    /** Immortal walk-only: no break, no place — only the baseline can open a sealed route. */
    private static final BotCaps WALK_ONLY = new BotCaps(
            1, BotCaps.DEFAULT_SAFE_FALL, BotCaps.DEFAULT_MAX_FALL, false,
            BotCaps.DEFAULT_COST_PER_HITPOINT, false, false,
            BotCaps.UNBREAKABLE, false, BotCaps.DEFAULT_MAX_NODES, 1.0f);
    /** WALK_ONLY plus canPlace — bridges the gap itself (the edit-bearing identity fixture). */
    private static final BotCaps PLACER = new BotCaps(
            1, BotCaps.DEFAULT_SAFE_FALL, BotCaps.DEFAULT_MAX_FALL, false,
            BotCaps.DEFAULT_COST_PER_HITPOINT, false, true,
            BotCaps.UNBREAKABLE, false, BotCaps.DEFAULT_MAX_NODES, 1.0f);

    // ---- Null-baseline byte-identity ------------------------------------------------------------------

    @Test
    void nullBaselineIsByteIdenticalOnAnEditFreeWalk() {
        NavGridView grid = buildCorridor(false, false);
        BlockPathPlan legacy = BlockPathfinder.findPath(grid, START, GOAL, WALK_ONLY);
        BlockPathPlan seeded = BlockPathfinder.findPath(buildCorridor(false, false), START, GOAL,
                WALK_ONLY, null, null, null, BlockPathfinder.MODE_AUTO, null);

        assertPlansIdentical(legacy, seeded);
    }

    @Test
    void nullBaselineIsByteIdenticalOnAnEditBearingSearch() {
        // The placer bridges the bottomless gap itself → the plan carries PLACE edits, exercising the
        // per-pop rebuild path (relaxer.anyEdits true) with the baseline compare in place.
        BlockPathPlan legacy = BlockPathfinder.findPath(buildCorridor(false, true), START, GOAL, PLACER);
        BlockPathPlan seeded = BlockPathfinder.findPath(buildCorridor(false, true), START, GOAL,
                PLACER, null, null, null, BlockPathfinder.MODE_AUTO, null);

        assertPlansIdentical(legacy, seeded);
    }

    // ---- Baseline BROKEN reads as air -----------------------------------------------------------------

    @Test
    void walkOnlyBotCannotPassTheSolidPlug() {
        assertNull(BlockPathfinder.findPath(buildCorridor(true, false), START, GOAL, WALK_ONLY),
                "sealed corridor, no break cap, no baseline — there must be no path");
    }

    @Test
    void baselineBrokenOpensThePlug() {
        EditSnapshot baseline = snapshotBreaking(
                BlockPos.asLong(PLUG_X, 1, 6), BlockPos.asLong(PLUG_X, 2, 6));

        BlockPathPlan plan = BlockPathfinder.findPath(buildCorridor(true, false), START, GOAL,
                WALK_ONLY, null, null, null, BlockPathfinder.MODE_AUTO, baseline);

        assertNotNull(plan, "the baseline's BROKEN plug cells must read as air");
        assertReachedGoal(plan);
        assertTrue(contains(plan, PLUG_X, 1, 6), "the only route is straight through the plug column");
    }

    // ---- Baseline PLACED reads as floor ---------------------------------------------------------------

    @Test
    void walkOnlyBotCannotCrossTheGap() {
        assertNull(BlockPathfinder.findPath(buildCorridor(false, true), START, GOAL, WALK_ONLY),
                "4-wide bottomless gap, no place cap, no baseline — there must be no path");
    }

    @Test
    void baselinePlacedBridgesTheGap() {
        long[] bridge = new long[GAP_TO_X - GAP_FROM_X + 1];
        for (int x = GAP_FROM_X; x <= GAP_TO_X; x++) bridge[x - GAP_FROM_X] = BlockPos.asLong(x, 0, 6);
        EditSnapshot baseline = snapshotPlacing(bridge);

        BlockPathPlan plan = BlockPathfinder.findPath(buildCorridor(false, true), START, GOAL,
                WALK_ONLY, null, null, null, BlockPathfinder.MODE_AUTO, baseline);

        assertNotNull(plan, "the baseline's PLACED gap cells must read as standable floor");
        assertReachedGoal(plan);
        assertTrue(contains(plan, PLUG_X, 1, 6), "the route walks the bridged column");
    }

    // ---- Snapshot construction helpers (via the public folding API) ------------------------------------

    private static EditSnapshot snapshotBreaking(long... cells) {
        return SpliceTestPlans.snapshotOf(cells, new long[0]);
    }

    private static EditSnapshot snapshotPlacing(long... cells) {
        return SpliceTestPlans.snapshotOf(new long[0], cells);
    }

    // ---- Assertions ------------------------------------------------------------------------------------

    private static void assertPlansIdentical(BlockPathPlan a, BlockPathPlan b) {
        assertNotNull(a);
        assertNotNull(b);
        assertEquals(a.size(), b.size(), "same waypoint count");
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.waypoint(i), b.waypoint(i), "waypoint " + i);
        }
        assertEquals(a.cost(), b.cost(), 0f, "same total cost");
    }

    /** Last waypoint within the search's goal tolerance (±1 horizontal, ±2 vertical of the goal floor). */
    private static void assertReachedGoal(BlockPathPlan plan) {
        BlockPos last = plan.waypoint(plan.size() - 1);
        assertTrue(Math.abs(last.getX() - GOAL.getX()) <= 1
                        && Math.abs(last.getZ() - GOAL.getZ()) <= 1
                        && Math.abs(last.getY() - GOAL.getY()) <= 2,
                "last waypoint " + last + " must be within goal tolerance of " + GOAL);
    }

    private static boolean contains(BlockPathPlan plan, int x, int y, int z) {
        for (int i = 0; i < plan.size(); i++) {
            BlockPos wp = plan.waypoint(i);
            if (wp.getX() == x && wp.getY() == y && wp.getZ() == z) return true;
        }
        return false;
    }

    // ---- Fixture ----------------------------------------------------------------------------------------

    /**
     * One sealed stone section (chunk 0,0): the single corridor {@code z=6, x=2..12} carved 2-tall
     * (air at y=1..2 over stone floors at y=0, stone ceiling at y=3 — no jump headroom). Options:
     * {@code plugged} leaves the {@code x=7} column SOLID (the wall a walk-only bot can't pass);
     * {@code gapped} removes the floor at {@code x=6..9} (a 4-wide gap — wider than flat parkour's
     * 3 — over nothing: y=0 is the section's bottom row and below it is out of vertical range, so the
     * gap cells have no standable landing). No detour exists — everything else is solid rock, and
     * outside the built chunk is UNBUILT (the search can't leave).
     */
    private static NavGridView buildCorridor(boolean plugged, boolean gapped) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();

        PalettedContainer<BlockState> s = new PalettedContainer<>(
                Block.BLOCK_STATE_REGISTRY, air, PalettedContainer.Strategy.SECTION_STATES);
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    s.set(x, y, z, stone);
                }
            }
        }
        for (int x = 2; x <= 12; x++) {
            if (plugged && x == PLUG_X) continue; // the solid plug column
            s.set(x, 1, 6, air);
            s.set(x, 2, 6, air);
        }
        if (gapped) {
            for (int x = GAP_FROM_X; x <= GAP_TO_X; x++) s.set(x, 0, 6, air); // bottomless floor gap
        }

        NavSection section = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(s, false, section.getTraversalGrid());

        PalettedContainer<BlockState> airStates = new PalettedContainer<>(
                Block.BLOCK_STATE_REGISTRY, air, PalettedContainer.Strategy.SECTION_STATES);
        NavSection airSection = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(airStates, true, airSection.getTraversalGrid());

        NavSection[] column = { section, airSection, airSection, airSection };
        ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        chunks.put(NavStore.key(0, 0), column);
        return new NavGridView(0, chunks);
    }
}
