package com.orebit.mod.pathfinding.blockpathfinder.cuboid;

/**
 * The macro-movement jump length — the single home of the two cost arithmetic that every macro shares.
 *
 * <p>Given a movement traveling in some axis-aligned direction {@code (axis, sign)} from a cell
 * {@code (x,y,z)}, its already-resolved {@link Cuboid} (the maximal uniform box containing the cell,
 * produced by {@code NavGridCuboidsView.cuboidAt}), the movement's true per-step cost, and the search
 * goal, this returns the number of steps the movement may collapse into a single jump.
 *
 * <p>It is deliberately the ONE place the bound is written, so that no individual movement can re-derive
 * it incorrectly. Every macro-aware movement ({@code Pillar}, {@code MineDown}, {@code Traverse}, later
 * {@code Diagonal}/{@code Ascend}) calls {@link #steps} with its own real per-step cost.
 *
 * <h2>The bound (three caps, min of all three)</h2>
 * <pre>
 *   jump = max(1, min(travelExtent, goalBound, escapeBound))
 * </pre>
 * <ul>
 *   <li><b>{@code travelExtent}</b> — distance to the box's far face along travel
 *       ({@code cuboid.extentToward}). <b>HARD</b>: you cannot jump past the cuboid's edge, beyond which
 *       uniformity (and thus validity) is no longer proven.</li>
 *   <li><b>{@code goalBound}</b> — forward distance to the goal's coordinate on this axis. <b>HARD</b>:
 *       never overshoot the goal. If the goal is not ahead on this axis, this is 0 and forces
 *       {@code jump = 1} (a plain micro step) — correct: do not, e.g., pillar away from the goal.</li>
 *   <li><b>{@code escapeBound}</b> — {@code ceil(nearestOrthogonalFaceDistance / moveCost)}. This is the
 *       escape-hedge of <b>NON-NEGOTIABLE 2</b> (see {@code MACRO-IMPLEMENTATION.md} §0): the JPS
 *       uniform-cost requirement recovered for our non-uniform costs. The {@code / moveCost} is the entire
 *       point — it makes cheap movements (walk, {@code moveCost≈1}) get large jumps and expensive ones
 *       (pillar/mine) get small ones, so an expensive movement cannot over-jump past a cheaper exit lurking
 *       just beyond the cuboid's orthogonal face. This term is a bound on SUB-OPTIMALITY, not validity, so
 *       rounding it UP ({@code ceil}) is acceptable (≤ ~1 block of avoidable detour).</li>
 * </ul>
 *
 * <p><b>Do not "simplify" the escape bound by dropping the division.</b> That was litigated for hours and
 * rejected; removing {@code / moveCost} reintroduces the over-jump bug.
 *
 * @see Cuboid
 * @see Axes
 */
public final class MacroJump {
    private MacroJump() {}

    /**
     * The number of steps to jump ({@code >= 1}) for a movement traveling {@code (axis, sign)} from
     * {@code (x,y,z)}, given its already-resolved {@code cuboid} (from
     * {@code NavGridCuboidsView.cuboidAt}), the movement's true per-step {@code moveCost} (base move +
     * folded edit cost, e.g. a pillar step = {@code Pillar.COST + MovementContext.PLACE_COST}), and the
     * goal.
     *
     * <p>{@code jump = max(1, min(travelExtent, goalBound, escapeBound))} where:
     * <ul>
     *   <li>{@code travelExtent = cuboid.extentToward(x,y,z,axis,sign)} — HARD (box edge / validity);</li>
     *   <li>{@code goalBound = max(0, sign*(goalCoord - cellCoord))} — HARD (don't overshoot the goal); if
     *       the goal is not ahead on this axis this is 0 and forces {@code jump = 1};</li>
     *   <li>{@code escapeBound = ceil(cuboid.nearestOrthogonalFace(x,y,z,axis) / moveCost)} —
     *       NON-NEGOTIABLE 2, soft ({@code ceil} OK, but never drop the {@code / moveCost}).</li>
     * </ul>
     *
     * <p>Returns {@code 1} (the micro fallback) when the cuboid is invalid or any HARD bound is
     * {@code <= 1} — the caller then emits the plain single micro step.
     *
     * @param cuboid   the resolved uniform box containing {@code (x,y,z)}; {@code null} or invalid → micro
     * @param x        start cell X (absolute world block coord)
     * @param y        start cell Y
     * @param z        start cell Z
     * @param axis     the travel axis ({@link Axes#AXIS_X}, {@link Axes#AXIS_Y}, {@link Axes#AXIS_Z})
     * @param sign     the travel direction along {@code axis} ({@code -1} or {@code +1})
     * @param moveCost the movement's REAL per-step cost (base + folded edit) — never a literal
     * @param goalX    goal X (absolute world block coord)
     * @param goalY    goal Y
     * @param goalZ    goal Z
     * @return the jump length in steps ({@code >= 1})
     */
    public static int steps(Cuboid cuboid, int x, int y, int z, int axis, int sign,
                            float moveCost, int goalX, int goalY, int goalZ) {
        if (cuboid == null || !cuboid.valid) return 1;

        // HARD bound 1: the box edge. You cannot jump past the cuboid — uniformity is unproven beyond it.
        int travelExtent = cuboid.extentToward(x, y, z, axis, sign);

        // HARD bound 2: don't overshoot the goal. If the goal is not ahead on this axis, goalBound == 0.
        int goalCoord = (axis == Axes.AXIS_X ? goalX : axis == Axes.AXIS_Y ? goalY : goalZ);
        int cellCoord = (axis == Axes.AXIS_X ? x     : axis == Axes.AXIS_Y ? y     : z);
        int goalBound = Math.max(0, sign * (goalCoord - cellCoord));

        int hard = Math.min(travelExtent, goalBound);
        if (hard <= 1) return 1;

        // SOFT bound: the escape-hedge. NON-NEGOTIABLE 2 — the / moveCost is the whole point; never drop it.
        int orth = cuboid.nearestOrthogonalFace(x, y, z, axis);
        int escapeBound = Math.max(1, (int) Math.ceil(orth / moveCost));

        return Math.max(1, Math.min(hard, escapeBound));
    }
}
