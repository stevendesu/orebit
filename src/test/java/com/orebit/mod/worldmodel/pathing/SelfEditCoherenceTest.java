package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.orebit.mod.Debug;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.RegionBound;
import com.orebit.mod.pathfinding.blockpathfinder.StepEdits;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

/**
 * SELF-EDIT COHERENCE (the flagship scaffold-treadmill find, 2026-07-30): a plan must never walk its body
 * through a cell an earlier step of the SAME plan placed, without folding an honest break of that cell.
 * The live repro: a support-chained bridge-down ladder (each plank placed against the previous one —
 * {@code placeable}'s edit-aware neighbour support) whose {@code Descend d(0,-1,±1)} zigzag re-entered
 * the just-vacated column THROUGH the plank placed two steps earlier, with {@code brk=0} — because the
 * transit clearance was proven by the resident HEADROOM flag prefilter ({@code headroomProves}), which
 * reads the grid and cannot see path edits. Over a void the grid says "all clear", so the per-cell
 * edit-aware reads were skipped and the path's own plank was invisible. (BREAK edits cannot leak this
 * way: flags read them as solid, the prefilter FAILS, and the per-cell fallback is edit-aware — the
 * blind spot only ever wrong-ADMITS placements.)
 *
 * <p>The fixture forces the shape: a cliff lip over a void with the only route a place-supported
 * bridge-down (the landing is too far for any parkour and there is no fall floor). The test asserts the
 * COHERENCE PROPERTY over whatever plan A* returns — walking the steps in order, no step's feet/head
 * cell may coincide with a still-intact cell placed by an earlier step (a step's own folded breaks are
 * applied before its check: breaking your old plank while descending through it is the honest form).
 * Lives in this package to reach {@link NavGridView}'s package-private synthetic constructor.
 */
class SelfEditCoherenceTest {

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
    /** Start floor: the cliff-lip platform. */
    private static final BlockPos START = new BlockPos(2, 8, 8);
    /** Goal floor: the landing platform — 5 below and beyond every jump/fall reach, so the only route is
     *  a place-supported bridge-down. */
    private static final BlockPos GOAL = new BlockPos(11, 3, 8);

    @Test
    void bridgeDownNeverWalksThroughItsOwnIntactPlanks() {
        BlockPathPlan plan = BlockPathfinder.findPath(buildCliff(), START, GOAL, BotCaps.BREAK_PLACE, CORRIDOR);
        assertNotNull(plan, "a break+place bot must be able to bridge-and-descend to the landing");

        // Walk the plan in step order with the set of placed-and-still-intact cells. A step's own breaks
        // land before its occupancy check (descending THROUGH your old plank by breaking it is the honest,
        // priced form); its places land after (a step never occupies the cell it is placing this step —
        // the footing is below the feet).
        Set<Long> placedIntact = new HashSet<>();
        for (int i = 0; i < plan.size(); i++) {
            StepEdits se = plan.edits(i);
            if (se != null) {
                for (int b = 0; b < se.breakCount(); b++) placedIntact.remove(se.breakAt(b));
            }
            BlockPos wp = plan.waypoint(i);
            long feet = wp.asLong();
            long head = BlockPos.asLong(wp.getX(), wp.getY() + 1, wp.getZ());
            assertTrue(!placedIntact.contains(feet),
                    "step " + i + " (" + plan.movement(i).getClass().getSimpleName() + ") puts its FEET at "
                            + wp + " — a cell an earlier step placed and no step has broken");
            assertTrue(!placedIntact.contains(head),
                    "step " + i + " (" + plan.movement(i).getClass().getSimpleName() + ") puts its HEAD at "
                            + wp.above() + " — a cell an earlier step placed and no step has broken");
            if (se != null) {
                for (int p = 0; p < se.placeCount(); p++) placedIntact.add(se.placeAt(p));
            }
        }
    }

    /**
     * One section, mostly VOID: a cliff-lip platform (floors {@code (0..4, 8, 7..9)}, lip at x=4) and a
     * landing platform (floors {@code (9..15, 3, 7..9)}). The 4-gap to the landing needs a −5 drop —
     * outside every parkour row (falling drops reach −3) — and the void has no fall floor, so the only
     * route is bridging out and descending on placed, support-chained footing.
     */
    private static NavGridView buildCliff() {
        PalettedContainer<BlockState> s = new PalettedContainer<>(
                Blocks.AIR.defaultBlockState(), Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        BlockState stone = Blocks.STONE.defaultBlockState();
        for (int x = 0; x <= 4; x++) {
            for (int z = 7; z <= 9; z++) s.set(x, 8, z, stone);   // the lip platform floors
        }
        for (int x = 9; x <= 15; x++) {
            for (int z = 7; z <= 9; z++) s.set(x, 3, z, stone);   // the landing platform floors
        }
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
