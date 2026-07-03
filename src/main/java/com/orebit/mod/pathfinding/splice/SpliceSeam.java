package com.orebit.mod.pathfinding.splice;

import com.orebit.mod.pathfinding.blockpathfinder.EditSnapshot;

import net.minecraft.core.BlockPos;

/**
 * The splice primitive's contract object (owner-ratified first-class primitive — memory
 * {@code path-splice-primitive}; spec'd in DESIGN-portal-route-layer.md §4 and consumed first by
 * DESIGN-background-pathfinding.md §7): a guarded handoff between two independently-computed plans at
 * a settled boundary. "Splicing" is NOT a data-structure merge of two paths — it is three steps,
 * <b>seed → accept → adopt</b>:
 *
 * <ol>
 *   <li><b>Seed</b>: the later plan's search runs with {@link #baseline()} — the earlier plan's
 *       not-yet-applied edits — threaded into {@code BlockPathfinder.findPath}, so it prices the world
 *       as it WILL be at the boundary, not as it is at plan time (eager mode; lazy mode seeds
 *       {@link EditSnapshot#EMPTY} because everything is already applied and grid-patched).</li>
 *   <li><b>Accept</b>: at the boundary, {@link #accepts} decides whether the precomputed plan may be
 *       adopted from where the bot ACTUALLY stands — the later plan's start was a prediction. Rejection
 *       discards the precomputed plan wholesale (replan from the actual floor, i.e. today's path);
 *       never repair a rejected plan.</li>
 *   <li><b>Adopt</b>: the follower's existing window-swap mechanics, only ever at a settled boundary
 *       (the boundary-gated invariant: plans never switch from a cell the bot is transiently passing
 *       through). Adoption itself lives with the follower/PathPlan, not here.</li>
 * </ol>
 *
 * <p>Two consumers, one contract: the background planner (splice window K to precomputed window K+1;
 * predicted start = the current plan's final waypoint floor) and the portal route layer (splice leg N
 * to leg N+1; predicted start = the predicted portal exit — which may widen the tolerance to the
 * vanilla exit spread). Cold code, MC-free except {@link BlockPos}.
 */
public final class SpliceSeam {

    /**
     * Default acceptance radius (Chebyshev, blocks) — matches {@code PathPlan.REPLAN_NEAR_TARGET}: the
     * "close enough that the window machinery self-corrects" radius, deliberately smaller than a region
     * so a parallel-tunnel arrival is rejected rather than walked from the wrong corridor.
     */
    public static final int DEFAULT_TOLERANCE_CHEB = 3;

    private final BlockPos predictedStartFloor;
    private final int predictedStartMode;
    private final EditSnapshot baseline;
    private final int toleranceCheb;

    /** A seam with the {@link #DEFAULT_TOLERANCE_CHEB default} acceptance radius. */
    public SpliceSeam(BlockPos predictedStartFloor, int predictedStartMode, EditSnapshot baseline) {
        this(predictedStartFloor, predictedStartMode, baseline, DEFAULT_TOLERANCE_CHEB);
    }

    /**
     * @param predictedStartFloor the floor cell the later plan believes it starts from
     * @param predictedStartMode  the {@code MovementContext} mode seed for the later search
     *                            (STANDING/PRONE, or {@code BlockPathfinder.MODE_AUTO})
     * @param baseline            the edits the later search must see ({@link EditSnapshot#EMPTY} in
     *                            lazy mode; never {@code null})
     * @param toleranceCheb       acceptance radius in blocks (Chebyshev over all three axes)
     */
    public SpliceSeam(BlockPos predictedStartFloor, int predictedStartMode, EditSnapshot baseline,
                      int toleranceCheb) {
        this.predictedStartFloor = predictedStartFloor;
        this.predictedStartMode = predictedStartMode;
        this.baseline = baseline == null ? EditSnapshot.EMPTY : baseline;
        this.toleranceCheb = toleranceCheb;
    }

    /**
     * The acceptance predicate: may the precomputed later plan be adopted from where the bot actually
     * is? Chebyshev distance over all three axes ≤ the tolerance — within it, the adopted plan's own
     * window machinery self-corrects the small start error; beyond it the prediction was wrong (bot
     * drifted, wrong portal exit) and the plan must be discarded.
     */
    public boolean accepts(BlockPos actualFloor) {
        int dx = Math.abs(actualFloor.getX() - predictedStartFloor.getX());
        int dy = Math.abs(actualFloor.getY() - predictedStartFloor.getY());
        int dz = Math.abs(actualFloor.getZ() - predictedStartFloor.getZ());
        return Math.max(dx, Math.max(dy, dz)) <= toleranceCheb;
    }

    /** The floor cell the later plan was computed from. */
    public BlockPos predictedStartFloor() {
        return predictedStartFloor;
    }

    /** The mode seed the later search ran with. */
    public int predictedStartMode() {
        return predictedStartMode;
    }

    /** The baseline the later search was seeded with (never {@code null}; possibly EMPTY). */
    public EditSnapshot baseline() {
        return baseline;
    }

    /** The acceptance radius (Chebyshev, blocks). */
    public int toleranceCheb() {
        return toleranceCheb;
    }
}
