package com.orebit.mod.pathfinding.regionpathfinder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;

import org.junit.jupiter.api.Test;

import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.worldmodel.hpa.RegionAddress;
import com.orebit.mod.worldmodel.hpa.RegionGrid;

import net.minecraft.core.BlockPos;

/**
 * FAR-GOAL CASCADE PROBE — a headless instrumentation harness (NOT a pass/fail unit test in spirit; the
 * assertions are loose sanity gates) that measures the {@link HierarchicalRegionPlan} region cascade's
 * <b>re-plan cadence</b> and <b>handed-down-waypoint behaviour</b> for a goal <i>hundreds of thousands of
 * blocks</i> away, over an <b>optimistic/unbuilt</b> synthetic {@link RegionGrid}, WITHOUT a physical bot
 * walk (millions of ticks — infeasible). It reuses the exact headless seam the cascade unit tests use
 * ({@link RegionGrid#headless} reads the §6 optimistic-AIR default everywhere — every region is
 * "walk/swim anywhere", the unexplored-terrain-read-optimistically model), and drives the cascade the way
 * the real {@link com.orebit.mod.pathfinding.PathPlan} block-window driver would: at each settle the bot
 * steps toward the cascade's CURRENT handed-down level-0 waypoint (so it FOLLOWS the plan; onRoute=true),
 * one leaf region per step, until it reaches the goal region.
 *
 * <h2>What it records (per run)</h2>
 * <ul>
 *   <li>Per pyramid level L: re-derives (real {@link HierarchicalRegionPlan.Verdict#SWAPPED} re-searches /
 *       skeleton-object changes) and, at L0, cheap {@link HierarchicalRegionPlan.Verdict#EXTENDED} splices —
 *       both bucketed per 10,000 blocks of +X travel.</li>
 *   <li>The effective HORIZON STEP per level: mean blocks of +X travel between that level's re-derives.</li>
 *   <li>The handed-down L0 waypoint (the world target the block-window driver aims at) each step, and its Y;
 *       the MAX deviation of that Y from the straight-line start Y (the pointless-vertical / overshoot
 *       detector). Over a UNIFORM grid the ideal lateral path is flat — but note the documented §2
 *       unbuilt-cost prior (RegionPathfinder line 170-182): BELOW y=63 the optimistic model discounts
 *       rising, so a start at y=60 will deliberately climb to the surface band before crossing. That is the
 *       model working as designed, not a bug — which is exactly why this probe runs a y=70 (surface-band)
 *       control alongside the y=60 case, to separate the intentional surface-rise from a genuine excursion.</li>
 *   <li>Total skeleton (re)derivations across all levels (a lower-bound proxy for {@code planWithin} calls —
 *       an escalation's intermediate overwritten skeletons are not separately observable) and wall time.</li>
 * </ul>
 *
 * <p>Read-only: constructs no production hook, mutates no production state; it composes the same public
 * seams the {@link HierarchicalCascadeTest} / {@link RollingSkeletonTest} suites already drive.
 */
public class FarGoalCascadeProbe {

    /** The far goal distance along +X (blocks). Hundreds of thousands, as the arc calls for. */
    private static final int FAR_X = 200_000;
    /** Dimension floor (region ry 0 spans world y 0..15) — matches every region fixture. */
    private static final int MINY = 0;
    /** One leaf region (16 blocks) of forward motion per settle — the natural rolling cadence (a larger step
     *  would teleport past the ~{@code WINDOW_CELLS}-cell L0 window and inflate forced re-derives). */
    private static final int STEP = RegionAddress.LEAF_SIZE;
    /** Hard loop cap (the beeline plus surface-rise is ≲ FAR_X/STEP × a small slope factor). */
    private static final int MAX_STEPS = 60_000;

    private static final BotCaps CAPS = BotCaps.BREAK_PLACE;

    @Test
    void probe_surfaceBand_y70_flatControl() {
        Result r = run(70);
        print(r);
        assertConverged(r);
    }

    @Test
    void probe_underground_y60_asSpecified() {
        Result r = run(60);
        print(r);
        assertConverged(r);
    }

    /** The vertical guard bound: the handed-down L0 waypoint Y must stay within this many blocks of the
     *  straight start→goal line for the WHOLE journey. The only legitimate excursion is the documented §2
     *  unbuilt-cost surface-rise (a y=60 start climbs to the ~y63-64 band before cruising, ≈3-4 blocks); a
     *  runaway/oscillating vertical would blow well past this. Matches the report's "BOUNDED & FLAT" threshold. */
    private static final int MAX_WAYPOINT_Y_DEV = 8;

    /**
     * The regression guards this harness enforces (not just prints): the journey converges at the coarsest
     * level; the coarsest tier's re-plan cadence is BOUNDED — the actual amortization guarantee under test —
     * re-planning about once per {@code WINDOW_CELLS} of its own 1024-block cells, not per step (we assert the
     * coarse bound, not the fine-tier churn, which the y=60 underground surface-rise legitimately inflates);
     * and the handed-down L0 waypoint Y stays within {@link #MAX_WAYPOINT_Y_DEV} of the straight line (no
     * pointless-vertical / overshoot). All three gates are distance-relative, so they hold whatever {@link
     * #FAR_X} is set to.
     */
    private static void assertConverged(Result r) {
        assertTrue(r.reached, "y" + r.lineY + ": the bot must reach the goal region over the optimistic grid");
        assertEquals(RegionAddress.MAX_COARSE_LEVEL, r.startTopLevel,
                "a far (" + FAR_X + "-block) goal must open the stack at the coarsest level");
        final int topRd = r.reDerives[RegionAddress.MAX_COARSE_LEVEL];
        assertTrue(topRd > 0 && FAR_X / topRd >= 2_000,
                "coarsest-level cadence must be bounded (~1 re-plan per WINDOW_CELLS 1024-blk cells); got 1 per "
                        + (FAR_X / Math.max(1, topRd)) + " blocks");
        assertTrue(r.maxWaypointYDev <= MAX_WAYPOINT_Y_DEV,
                "y" + r.lineY + ": handed-down waypoint Y must stay within " + MAX_WAYPOINT_Y_DEV
                        + " blocks of the straight line (surface-rise prior only); got max |dev|="
                        + r.maxWaypointYDev);
    }

    // ===================================================================================================
    // The driven journey
    // ===================================================================================================

    private static final int LVLS = RegionAddress.MAX_COARSE_LEVEL + 1;
    private static final int BUCKET = 10_000;

    private static final class Result {
        final int lineY;
        int steps;
        int startTopLevel;
        boolean reached;
        long wallNanos;
        int totalReDerives;
        int totalExtensions;
        // Per level: re-derive count, and per-10k-block bucket counts.
        final int[] reDerives = new int[LVLS];
        final int[] extensions = new int[LVLS]; // only L0 populated
        final int[][] reDerivesByBucket; // [level][bucket]
        int maxWaypointYDev;              // max |waypointY - lineY| over the journey
        int cruiseY = Integer.MIN_VALUE;  // the waypoint Y at mid-journey (x ~= FAR_X/2) — the cruising altitude
        final int buckets;

        Result(int lineY, int buckets) {
            this.lineY = lineY;
            this.buckets = buckets;
            this.reDerivesByBucket = new int[LVLS][buckets];
        }
    }

    private Result run(int lineY) {
        final int buckets = (FAR_X / BUCKET) + 2;
        final Result res = new Result(lineY, buckets);

        final BlockPos start = new BlockPos(0, lineY, 0);
        final BlockPos goal = new BlockPos(FAR_X, lineY, 0);
        final RegionGrid grid = RegionGrid.headless(MINY);

        final long t0 = System.nanoTime();
        final HierarchicalRegionPlan h = HierarchicalRegionPlan.build(grid, MINY, start, goal, CAPS);
        res.startTopLevel = h.topLevel();
        assertNotNull(h.l0Skeleton(), "the far-goal build must produce an L0 segment, not FAIL");

        // Track per-level skeleton identity to attribute re-derives to the right level.
        final RegionPathPlan[] prev = new RegionPathPlan[LVLS];
        for (int L = 0; L <= h.topLevel(); L++) prev[L] = h.skeletonAt(L);

        int px = start.getX(), py = start.getY(), pz = start.getZ();
        int noProgress = 0;

        int step = 0;
        for (; step < MAX_STEPS; step++) {
            final int bucket = Math.min(res.buckets - 1, Math.max(0, px) / BUCKET);

            // 1) Settle: advance the cascade at the bot's current cell (following the plan → onRoute=true).
            final HierarchicalRegionPlan.Verdict v =
                    h.onBotMoved(new BlockPos(px, py, pz), true, h.committedAt(0));

            // 2) Attribute skeleton-object changes per level.
            final int top = h.topLevel();
            for (int L = 0; L < LVLS; L++) {
                final RegionPathPlan cur = (L <= top) ? h.skeletonAt(L) : null;
                if (cur != null && prev[L] != null && cur != prev[L]) {
                    if (L == 0 && v == HierarchicalRegionPlan.Verdict.EXTENDED) {
                        res.extensions[0]++;
                        res.totalExtensions++;
                    } else {
                        res.reDerives[L]++;
                        res.reDerivesByBucket[L][bucket]++;
                        res.totalReDerives++;
                    }
                }
                prev[L] = cur;
            }

            // 3) Read the handed-down L0 waypoint (the world target the block driver aims at) + its Y.
            final BlockPos wp = l0Waypoint(h, goal);
            if (wp != null) {
                final int dev = Math.abs(wp.getY() - lineY);
                if (dev > res.maxWaypointYDev) res.maxWaypointYDev = dev;
                // Cruise altitude: the waypoint Y sampled once around the journey midpoint, clear of both the
                // initial surface-rise and the final goal-Y switchback.
                if (res.cruiseY == Integer.MIN_VALUE && px >= FAR_X / 2) {
                    res.cruiseY = wp.getY();
                }
            }

            // 4) Reached? (goal region occupied, or L0 final and within a region of the goal cell.)
            if (Math.abs(px - goal.getX()) <= RegionAddress.LEAF_SIZE
                    && Math.abs(pz - goal.getZ()) <= RegionAddress.LEAF_SIZE
                    && Math.abs(py - goal.getY()) <= RegionAddress.LEAF_SIZE) {
                res.reached = true;
                break;
            }

            // 5) Step toward the waypoint by STEP blocks (following the plan). Fall back to a direct +X nudge
            //    if the waypoint stops giving forward progress (degenerate — keeps the probe from stalling).
            final int oldX = px;
            BlockPos tgt = (wp != null) ? wp : goal;
            long dx = tgt.getX() - px, dy = tgt.getY() - py, dz = tgt.getZ() - pz;
            double dist = Math.sqrt((double) dx * dx + (double) dy * dy + (double) dz * dz);
            if (dist < 1e-6 || noProgress >= 8) {
                // On the waypoint (or wedged): nudge straight toward the goal so commit/extension advances.
                px += Math.min(STEP, Math.max(1, goal.getX() - px));
                noProgress = 0;
            } else {
                double s = Math.min(STEP, dist) / dist;
                px += (int) Math.round(dx * s);
                py += (int) Math.round(dy * s);
                pz += (int) Math.round(dz * s);
            }
            if (px <= oldX) {
                noProgress++;
            } else {
                noProgress = 0;
            }
        }
        res.wallNanos = System.nanoTime() - t0;
        res.steps = step;
        return res;
    }

    /**
     * Reconstruct the cascade's handed-down level-0 waypoint — the world target the block-window driver aims
     * at, byte-identical to {@code HierarchicalRegionPlan.handDown(0, levels[0])} but via the public surface:
     * the window-far cell ({@code min(committed + WINDOW_CELLS, size-1)}) of the L0 skeleton, its portal cell
     * (fallback centre), or the real goal once the L0 segment reaches the goal region at its tail.
     */
    private static BlockPos l0Waypoint(HierarchicalRegionPlan h, BlockPos goal) {
        final RegionPathPlan l0 = h.l0Skeleton();
        if (l0 == null || l0.isEmpty()) return null;
        final int far = Math.min(h.committedAt(0) + HierarchicalRegionPlan.WINDOW_CELLS, l0.size() - 1);
        if (l0.reachedGoalRegion() && far == l0.size() - 1) return goal;
        final BlockPos portal = l0.portalCell(far);
        return portal != null ? portal : l0.centerOf(far);
    }

    // ===================================================================================================
    // Reporting
    // ===================================================================================================

    private void print(Result r) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n================ FAR-GOAL CASCADE PROBE  (start y=").append(r.lineY)
          .append(", goal +X ").append(FAR_X).append(", caps=BREAK_PLACE, optimistic unbuilt grid) ")
          .append("================\n");
        sb.append(String.format(Locale.ROOT,
                "journey: reached=%s  steps=%d  wall=%.1f ms  startTopLevel=L%d%n",
                r.reached, r.steps, r.wallNanos / 1e6, r.startTopLevel));
        sb.append(String.format(Locale.ROOT,
                "totals:  re-derives(all levels)=%d   L0 extensions(splices)=%d   (re-derives = proxy for real "
                        + "region searches)%n", r.totalReDerives, r.totalExtensions));

        // Per-level summary.
        sb.append("\n level | re-derives | re-derives/10k blk | horizon step (blk/re-derive) | notes\n");
        sb.append(" ------+------------+--------------------+------------------------------+-------\n");
        final int travelled = Math.max(1, FAR_X);
        for (int L = 0; L <= RegionAddress.MAX_COARSE_LEVEL; L++) {
            final int rd = r.reDerives[L];
            final double per10k = rd * (double) BUCKET / travelled;
            final String horizon = rd > 0
                    ? String.format(Locale.ROOT, "%,.0f", (double) travelled / rd)
                    : "(none)";
            final String note = L == 0
                    ? String.format(Locale.ROOT, "+%d cheap splices", r.extensions[0])
                    : (L == RegionAddress.MAX_COARSE_LEVEL ? "coarsest (slides toward goal)" : "");
            sb.append(String.format(Locale.ROOT, "  L%d   | %10d | %18.3f | %28s | %s%n",
                    L, rd, per10k, horizon, note));
        }

        // Handed-down waypoint Y behaviour.
        sb.append(String.format(Locale.ROOT,
                "%nhanded-down waypoint Y: line Y=%d, cruise Y (x~=%d) = %d, MAX |dev|=%d blocks%n",
                r.lineY, FAR_X / 2, r.cruiseY, r.maxWaypointYDev));
        if (r.maxWaypointYDev <= 8) {
            sb.append("  => BOUNDED & FLAT: the handed-down target settles at a single surface-band cruise "
                    + "altitude (~y63-64,\n     the §2 unbuilt-cost band floor) and HOLDS it for the whole "
                    + "journey — no runaway / oscillating\n     vertical. The small offset from the start Y is "
                    + "the documented surface-travel prior, not overshoot.\n");
        } else {
            sb.append("  => Y EXCURSION exceeds the surface-band offset — investigate (compare the two runs).\n");
        }

        // Top-level re-plan cadence check against the constants' prediction.
        final int topL = RegionAddress.MAX_COARSE_LEVEL;
        final int topRd = r.reDerives[topL];
        sb.append(String.format(Locale.ROOT,
                "%ncoarse-cadence check: L%d re-derived %d times over %d blocks => 1 per ~%s blocks "
                        + "(WINDOW_CELLS=%d, leaf=%d).%n",
                topL, topRd, FAR_X, topRd > 0 ? String.format(Locale.ROOT, "%,d", FAR_X / topRd) : "inf",
                HierarchicalRegionPlan.WINDOW_CELLS, RegionAddress.LEAF_SIZE));
        sb.append("=================================================================================="
                + "==============\n");
        System.out.println(sb);
    }
}
