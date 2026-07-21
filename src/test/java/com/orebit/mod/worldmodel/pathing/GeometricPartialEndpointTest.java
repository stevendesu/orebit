package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

/**
 * Headless proof of the <b>geometric partial endpoint</b> (Fix B): on budget-hit the partial commits to the
 * explored row geometrically CLOSEST to the target by unweighted 3D octile — over ALL explored rows, popped
 * and relaxed-never-popped alike — not best-by-relaxer.h among popped nodes. The distinguishing shape is the
 * buried-target incident generalized past start children: one expensive forward candidate several steps from
 * the start (a fire-cell transit whose 1-HP × costPerHitpoint surcharge keeps its f behind a cheap open-room
 * flood for the whole budget) is relaxed toward the goal but never popped; the geometric rule commits to it,
 * where the popped-only rule inched along the flood. Also guards: determinism, exhausted-search null
 * (BLOCKED preserved), and the IRREVERSIBLE_GUARD truncation operating on the geometric endpoint.
 */
class GeometricPartialEndpointTest {

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

    /** Mortal walker with a steep damage price and a tight node budget: the room flood outlives the cap
     *  while the one fire-transit candidate waits un-popped on the heap (its f carries the 1000-tick HP
     *  surcharge — far above every room cell's). */
    private static final BotCaps HAZARD_BUDGET = new BotCaps(
            1, BotCaps.DEFAULT_SAFE_FALL, BotCaps.DEFAULT_MAX_FALL, true,
            1000f, false, false,
            BotCaps.UNBREAKABLE, false, 30, 2.0f);

    /** Same walker, generous cap: over the sealed room the search exhausts (walled in) instead of
     *  budget-hitting. */
    private static final BotCaps WALK = new BotCaps(
            1, BotCaps.DEFAULT_SAFE_FALL, BotCaps.DEFAULT_MAX_FALL, true,
            1000f, false, false,
            BotCaps.UNBREAKABLE, false, BotCaps.DEFAULT_MAX_NODES, 2.0f);

    /** No-place no-break faller with a 5-pop budget: crosses the cliff before the cap. */
    private static final BotCaps CLIFF_BUDGET = new BotCaps(
            1, BotCaps.DEFAULT_SAFE_FALL, BotCaps.DEFAULT_MAX_FALL, false,
            BotCaps.DEFAULT_COST_PER_HITPOINT, false, false,
            BotCaps.UNBREAKABLE, false, 5, 2.0f);

    private static final BlockPos ROOM_START = new BlockPos(2, 0, 6);
    private static final BlockPos ROOM_GOAL = new BlockPos(12, 0, 6);

    @Test
    void budgetHitCommitsToTheGeometricallyClosestExploredRow() {
        // The room floods cheaply (112 cells > the 30-pop cap); the one forward candidate (9,0,6) — relaxed
        // off (8,0,6) with the fire cell's 1000-tick transit surcharge — is geometrically 3 blocks from the
        // goal while every POPPED cell is ≥ 4. The old popped-only commit ended the partial at (8,0,6); the
        // geometric scan must end it at the relaxed-never-popped frontier (9,0,6), stand cell (9,1,6).
        BlockPathPlan plan = BlockPathfinder.findPath(room(true), ROOM_START, ROOM_GOAL, HAZARD_BUDGET,
                null, null, null, BlockPathfinder.MODE_AUTO, null, 0L);
        assertNotNull(plan, "budget-hit with real geometric progress must return a PARTIAL");
        assertTrue(BlockPathfinder.lastWasPartial());
        assertTrue(BlockPathfinder.lastWasBudgetHit());
        assertEquals(new BlockPos(9, 1, 6), plan.waypoint(plan.size() - 1),
                "the partial must terminate at the relaxed-never-popped frontier — the explored row "
                        + "geometrically closest to the target");
    }

    @Test
    void geometricSelectionIsDeterministic() {
        BlockPathPlan a = BlockPathfinder.findPath(room(true), ROOM_START, ROOM_GOAL, HAZARD_BUDGET,
                null, null, null, BlockPathfinder.MODE_AUTO, null, 0L);
        BlockPathPlan b = BlockPathfinder.findPath(room(true), ROOM_START, ROOM_GOAL, HAZARD_BUDGET,
                null, null, null, BlockPathfinder.MODE_AUTO, null, 0L);
        assertNotNull(a);
        assertNotNull(b);
        assertEquals(a.size(), b.size(), "two identical runs must produce identical partials");
        for (int i = 0; i < a.size(); i++) {
            assertEquals(a.waypoint(i), b.waypoint(i), "waypoint " + i + " must match across runs");
        }
    }

    @Test
    void exhaustedSearchStillReturnsNull() {
        // Same room sealed shut (no corridor), generous cap: the search EXHAUSTS the walled-in room. The
        // geometric endpoint runs only under budgetHit, so the exhausted FAIL (→ BLOCKED upstream) is
        // preserved.
        assertNull(BlockPathfinder.findPath(room(false), ROOM_START, ROOM_GOAL, WALK,
                null, null, null, BlockPathfinder.MODE_AUTO, null, 0L));
        assertFalse(BlockPathfinder.lastWasBudgetHit());
    }

    @Test
    void irreversibleGuardTruncatesTheGeometricEndpoint() {
        // The geometric endpoint (7,6,6) sits past a 4-block drop a no-place bot can't climb back; the
        // guard must truncate the commit to the lip (6,10,6) — stand cell (6,11,6) — never past the edge.
        BlockPathPlan plan = BlockPathfinder.findPath(cliff(), new BlockPos(2, 10, 6), new BlockPos(12, 6, 6),
                CLIFF_BUDGET, null, null, null, BlockPathfinder.MODE_AUTO, null, 0L);
        assertNotNull(plan, "the truncated partial still makes real progress along the ledge");
        assertTrue(BlockPathfinder.lastWasPartial());
        assertEquals(new BlockPos(6, 11, 6), plan.waypoint(plan.size() - 1),
                "the guard must truncate the geometric endpoint to the last cell before the drop");
        for (int i = 0; i < plan.size(); i++) {
            assertTrue(plan.waypoint(i).getX() <= 6, "no waypoint may cross the irreversible drop");
        }
    }

    /**
     * One sealed stone chunk with an open room {@code x=1..8, z=1..14} carved 2-tall at {@code y=1..2}.
     * With {@code fireCorridor}, the corridor continues {@code x=9..12} at {@code z=6} but its first body
     * cell {@code (9,1,6)} is FIRE — the only progress past {@code x=8} is a hazard transit a mortal
     * walker prices at 1 HP × costPerHitpoint (relaxed, never popped under the tight cap). Without it the
     * room is walled shut and the goal {@code (12,0,6)} unreachable.
     */
    private static NavGridView room(boolean fireCorridor) {
        PalettedContainer<BlockState> s = filledStone();
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int x = 1; x <= 8; x++) {
            for (int z = 1; z <= 14; z++) {
                s.set(x, 1, z, air);
                s.set(x, 2, z, air);
            }
        }
        if (fireCorridor) {
            for (int x = 9; x <= 12; x++) {
                s.set(x, 1, 6, air);
                s.set(x, 2, 6, air);
            }
            s.set(9, 1, 6, Blocks.FIRE.defaultBlockState());
        }
        return oneChunk(s);
    }

    /**
     * A high ledge ({@code x=2..6}, floor {@code y=10}) over a lower corridor ({@code x=7..12}, floor
     * {@code y=6}) at {@code z=6} — the only way forward is a 4-block drop (irreversible for a no-place bot).
     */
    private static NavGridView cliff() {
        PalettedContainer<BlockState> s = filledStone();
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int x = 2; x <= 6; x++) {
            for (int y = 11; y <= 15; y++) {
                s.set(x, y, 6, air);
            }
        }
        for (int x = 7; x <= 12; x++) {
            for (int y = 7; y <= 15; y++) {
                s.set(x, y, 6, air);
            }
        }
        return oneChunk(s);
    }

    private static PalettedContainer<BlockState> filledStone() {
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
        return s;
    }

    private static NavGridView oneChunk(PalettedContainer<BlockState> base) {
        BlockState air = Blocks.AIR.defaultBlockState();
        NavSection section = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(base, false, section.getTraversalGrid());

        PalettedContainer<BlockState> airStates = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        NavSection airSection = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(airStates, true, airSection.getTraversalGrid());

        ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        chunks.put(NavStore.key(0, 0), new NavSection[] { section, airSection, airSection, airSection });
        return new NavGridView(0, chunks);
    }
}
