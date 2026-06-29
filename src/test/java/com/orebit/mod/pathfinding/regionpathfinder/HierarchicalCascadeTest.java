package com.orebit.mod.pathfinding.regionpathfinder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.worldmodel.hpa.RegionAddress;
import com.orebit.mod.worldmodel.hpa.RegionGrid;

import net.minecraft.core.BlockPos;

/**
 * Unit tests for the region-tier <b>nested-skeleton cascade</b> ({@link HierarchicalRegionPlan}, HPA-CASCADE.md
 * §13). Like {@link RegionPathfinderFragmentTest} these need <b>no Minecraft</b>: a {@link RegionGrid#headless
 * headless} grid with nothing seeded reads the §6 optimistic AIR default everywhere, so each per-level search
 * beelines toward its (clamped) sub-goal — exactly the long-range "travel then refine" path the cascade is for.
 * The cascade is exercised directly (its {@code build}/{@code onBotMoved}/{@code onBlocked}); the
 * {@link com.orebit.mod.pathfinding.PathPlan} block-window integration is covered in-game (it needs a live
 * level).
 *
 * <p>Coverage (HPA-CASCADE §13): stack consistency, selective re-plan (the amortization guarantee — crossing a
 * level-L cell window re-plans levels ≤ L only), collapse-on-approach (top level shrinks), and blocked-hop
 * escalation/repair. Distances are chosen so the cap-safe top level is 2 (an L0/L1/L2 stack):
 * {@code maxCheb(0)=8, maxCheb(1)=11, maxCheb(2)=16} cells, so a goal ~1000 blocks (≈63 L0 / 31 L1 / 15 L2
 * cells) away picks level 2.
 */
public class HierarchicalCascadeTest {

    private RegionGrid grid;
    private static final BotCaps CAPS = BotCaps.BREAK_PLACE;

    @BeforeEach
    void enableCascade() {
        RegionGrid.HPA_FRAGMENTS = true;       // the cascade is built on the fragment model
        RegionGrid.HIERARCHICAL_CASCADE = true;
        grid = RegionGrid.headless(0);          // minY = 0
    }

    @AfterEach
    void resetFlags() {
        RegionGrid.HIERARCHICAL_CASCADE = false; // do not leak to other tests (flag default OFF)
        RegionGrid.HPA_FRAGMENTS = false;
    }

    /** A goal ~1000 blocks +X (cap-safe top level 2). */
    private static BlockPos farGoalX() {
        return new BlockPos(8 + 1000, 4, 8);
    }

    // ===================================================================================================
    // Build — the top-down descent yields a level-0 segment that progresses toward a far goal.
    // ===================================================================================================
    @Test
    void build_longRange_returnsProgressingL0Segment() {
        BlockPos bot = new BlockPos(8, 4, 8);
        HierarchicalRegionPlan h = HierarchicalRegionPlan.build(grid, 0, bot, farGoalX(), CAPS);

        assertTrue(h.topLevel() >= 1, "a ~1000-block goal must span more than the leaf level");
        assertEquals(2, h.topLevel(), "cap-safe top level for ~1000 blocks is 2");
        RegionPathPlan l0 = h.l0Skeleton();
        assertNotNull(l0, "the cascade must yield an L0 near segment, not null");
        assertTrue(l0.size() > 1, "the L0 segment is a real onward route, not a same-region stub");
        assertTrue(l0.isFragmentModel(), "the cascade plans the fragment model");
        assertEquals(0, l0.level(), "the bottom skeleton is level 0");
        assertEquals(0, l0.rx(0), "the L0 segment starts at the bot's region");
        assertTrue(l0.rx(l0.size() - 1) > l0.rx(0), "the L0 segment progresses toward the +X goal");
    }

    // ===================================================================================================
    // Stack consistency — every level 0..top has a level-tagged skeleton starting at the bot's cell.
    // ===================================================================================================
    @Test
    void build_stackConsistency() {
        BlockPos bot = new BlockPos(8, 4, 8);
        HierarchicalRegionPlan h = HierarchicalRegionPlan.build(grid, 0, bot, farGoalX(), CAPS);

        final int top = h.topLevel();
        for (int L = 0; L <= top; L++) {
            RegionPathPlan sk = h.skeletonAt(L);
            assertNotNull(sk, "level " + L + " must have a skeleton");
            assertEquals(L, sk.level(), "skeleton at level " + L + " must carry that level");
            assertTrue(sk.size() >= 1, "level " + L + " skeleton non-empty");
            // Each level's skeleton starts at the bot's cell AT THAT LEVEL.
            assertEquals(RegionAddress.regionX(bot.getX(), L), sk.rx(0), "level " + L + " starts at bot@L (x)");
            assertEquals(RegionAddress.regionZ(bot.getZ(), L), sk.rz(0), "level " + L + " starts at bot@L (z)");
        }
        assertNotNull(h.skeletonAt(top), "top level present");
    }

    // ===================================================================================================
    // Selective re-plan — moving within L1's window re-plans only L0; exhausting L1's window re-plans L1.
    // (HPA-CASCADE §5: re-plan frequency halves per level up — the amortization guarantee.)
    // ===================================================================================================
    @Test
    void onBotMoved_selectiveReplan_fineBeforeCoarse() {
        HierarchicalRegionPlan h = HierarchicalRegionPlan.build(grid, 0, new BlockPos(8, 4, 8), farGoalX(), CAPS);
        assertEquals(2, h.topLevel());

        RegionPathPlan l1Before = h.skeletonAt(1);
        RegionPathPlan l2Before = h.skeletonAt(2);

        // Walk +X within L1's window (< 128 blocks = 4 L1 cells): L0 exhausts its 4-cell window (every 64 blocks)
        // and re-plans, but L1/L2 do not. Step in 16-block (one L0 cell) increments so commit advances cleanly.
        boolean anyL0Replan = false;
        for (int x = 24; x <= 72; x += 16) { // up to +64 blocks from start (x=8)
            boolean changed = h.onBotMoved(new BlockPos(x, 4, 8));
            anyL0Replan |= changed;
        }
        assertTrue(anyL0Replan, "crossing L0 cells must re-plan the L0 segment");
        assertSame(l1Before, h.skeletonAt(1), "L1 must NOT re-plan while the bot is still inside its window");
        assertSame(l2Before, h.skeletonAt(2), "L2 must NOT re-plan either");

        // Now walk far enough to exhaust L1's window (≥128 blocks from start) → L1 re-plans (and L0 with it).
        for (int x = 88; x <= 200; x += 16) {
            h.onBotMoved(new BlockPos(x, 4, 8));
        }
        assertNotSame(l1Before, h.skeletonAt(1), "exhausting L1's window must re-plan L1");
    }

    // ===================================================================================================
    // Collapse on approach — as the bot nears the goal the cap-safe top level decreases and the stack pops.
    // ===================================================================================================
    @Test
    void onBotMoved_collapse_topLevelShrinksNearGoal() {
        BlockPos goal = farGoalX();
        HierarchicalRegionPlan h = HierarchicalRegionPlan.build(grid, 0, new BlockPos(8, 4, 8), goal, CAPS);
        assertEquals(2, h.topLevel(), "far goal starts at top level 2");

        // The bot is now right next to the goal (within maxCheb(0)=8 cells): the top exits (deviated off the far
        // coarse window), the top is recomputed, and the stack collapses to a single level-0 plan.
        h.onBotMoved(new BlockPos(goal.getX() - 8, 4, goal.getZ()));
        assertEquals(0, h.topLevel(), "near the goal the stack collapses to the direct level-0 plan");
        assertNotNull(h.l0Skeleton(), "the collapsed plan still reaches the goal");
    }

    // ===================================================================================================
    // Escalation / repair — a blocked L0 hop is blacklisted and the cascade reroutes (online repair, §6).
    // ===================================================================================================
    @Test
    void onBlocked_reroutesAroundDeadHop() {
        BlockPos bot = new BlockPos(8, 4, 8);
        HierarchicalRegionPlan h = HierarchicalRegionPlan.build(grid, 0, bot, farGoalX(), CAPS);
        RegionPathPlan l0 = h.l0Skeleton();
        assertTrue(l0.size() > 1);

        // The block tier "proves" the first L0 hop unrealizable: blacklist (step0 → step1) and repair.
        long fromKey = RegionPathfinder.fragmentNodeKey(l0.rx(0), l0.ry(0), l0.rz(0), l0.fragmentId(0));
        long toKey = RegionPathfinder.fragmentNodeKey(l0.rx(1), l0.ry(1), l0.rz(1), l0.fragmentId(1));

        boolean ok = h.onBlocked(fromKey, toKey, bot);
        assertTrue(ok, "over open terrain the cascade must reroute around one dead hop, not give up");
        RegionPathPlan after = h.l0Skeleton();
        assertNotNull(after);
        assertTrue(after.size() > 1, "the rerouted L0 segment is still a real onward route");
        // The repaired route must not take the forbidden first hop again.
        boolean sameHop = after.rx(1) == l0.rx(1) && after.ry(1) == l0.ry(1) && after.rz(1) == l0.rz(1)
                && after.fragmentId(1) == l0.fragmentId(1);
        assertTrue(!sameHop, "the repaired route avoids the blacklisted first hop");
    }
}
