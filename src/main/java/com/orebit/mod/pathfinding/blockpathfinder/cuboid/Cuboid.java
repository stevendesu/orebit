package com.orebit.mod.pathfinding.blockpathfinder.cuboid;

/**
 * One maximal axis-aligned box, plus the single navtype it is uniform in (MACRO-IMPLEMENTATION §3,
 * MACRO-MOVEMENTS §3a). This is the load-bearing geometric object behind cuboid collapse: a macro
 * jump is sound <i>only because</i> a {@code Cuboid} certifies that the whole orthogonal cross-section
 * is uniform over the travel run (NON-NEGOTIABLE 1) — skipping the intermediate cells is safe precisely
 * because no exit can hide inside a uniform box.
 *
 * <p><b>Mutable, pooled, reusable.</b> This is deliberately <i>not</i> immutable. One instance lives
 * per cache slot / per {@code MovementContext} scratch; {@link CuboidExtractor} and
 * {@link NavGridCuboidsView} fill it in place via {@link #set} and read it back. There is <b>no
 * per-query allocation</b> (HOT-PATH-NO-ALLOC) — never {@code new Cuboid()} in a search loop; obtain a
 * pooled instance and {@code set}/{@code invalidate} it.
 *
 * <p><b>Plain int fields, not a packed long (MACRO-IMPLEMENTATION §3).</b> The box is read several times
 * per jump (travel extent + orthogonal-face clearance + the PathEdits overlay scan); branch-free int
 * field reads JIT-inline. Packing would buy nothing — the box lives in a small per-search cache, never
 * persisted — and would cost shifts/masks on the hot path (consistent with FAVOR-CPU-OVER-RAM). All
 * coordinates are <b>absolute world block coords</b> and the min/max bounds are <b>inclusive</b>.
 */
public final class Cuboid {

    /** Inclusive minimum corner (absolute world block coords). */
    int minX, minY, minZ;
    /** Inclusive maximum corner (absolute world block coords). */
    int maxX, maxY, maxZ;

    /** The uniform navtype id this box is filled with (TraversalGrid navtype, 10-bit); {@code -1} = invalid/empty. */
    int navtype = -1;

    /**
     * {@code true} once {@link #set} has populated a real box; {@code false} means "no cuboid here"
     * (an unbuilt cell, or a start cell outside the corridor). Callers MUST check {@link #isValid()}
     * (or read {@code valid}) before trusting any geometry query.
     */
    boolean valid;

    /**
     * Populate this box and mark it valid. Bounds are inclusive; callers pass absolute world block coords.
     * {@code navtype} is the uniform TraversalGrid navtype id every cell in the box shares.
     */
    void set(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int navtype) {
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
        this.navtype = navtype;
        this.valid = true;
    }

    /** Mark this box as "no cuboid here" — geometry queries must not be trusted until the next {@link #set}. */
    void invalidate() {
        this.valid = false;
        this.navtype = -1;
    }

    /** {@code true} iff this box currently holds a real, populated cuboid. */
    public boolean isValid() {
        return valid;
    }

    /** The uniform navtype id this box is filled with; {@code -1} when {@link #isValid()} is false. */
    public int navtype() {
        return navtype;
    }

    /** {@code true} iff {@code (x,y,z)} lies inside this box (inclusive on every face). */
    boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX
            && y >= minY && y <= maxY
            && z >= minZ && z <= maxZ;
    }

    /**
     * Distance (in cells) from {@code (x,y,z)} to the box's far face in direction {@code (axis,sign)},
     * inclusive of the current cell as 0. This is the <b>HARD</b> travel-extent bound: a jump may never
     * exceed it, because past it the box (hence the uniformity guarantee) ends.
     *
     * <p>Examples: {@code axis=Y, sign=+1 → maxY - y}; {@code axis=X, sign=-1 → x - minX}.
     *
     * @param axis one of {@link Axes#AXIS_X}, {@link Axes#AXIS_Y}, {@link Axes#AXIS_Z}
     * @param sign {@code +1} or {@code -1}
     */
    int extentToward(int x, int y, int z, int axis, int sign) {
        switch (axis) {
            case Axes.AXIS_X:
                return sign > 0 ? (maxX - x) : (x - minX);
            case Axes.AXIS_Y:
                return sign > 0 ? (maxY - y) : (y - minY);
            case Axes.AXIS_Z:
                return sign > 0 ? (maxZ - z) : (z - minZ);
            default:
                return 0;
        }
    }

    /**
     * Minimum distance (in cells) from {@code (x,y,z)} to the NEAREST face orthogonal to {@code axis} —
     * i.e. how far the uniform cross-section clears sideways before the box ends. Constant along the
     * travel axis BY CONSTRUCTION (that uniformity is exactly why we built a cuboid), so it does not
     * depend on the cell's position along {@code axis}.
     *
     * <p>This feeds NON-NEGOTIABLE 2's escape-hedge: {@code escapeBound = ceil(this / moveCost)}. It is
     * the min, over the two orthogonal axes {@code a = orthA(axis)} and {@code b = orthB(axis)}, of the
     * clearance to each of the four orthogonal faces (low and high on both axes).
     *
     * @param axis the travel axis the cross-section is measured orthogonal to
     */
    int nearestOrthogonalFace(int x, int y, int z, int axis) {
        int a = Axes.orthA(axis);
        int b = Axes.orthB(axis);
        int clearA = Math.min(faceLow(a, x, y, z), faceHigh(a, x, y, z));
        int clearB = Math.min(faceLow(b, x, y, z), faceHigh(b, x, y, z));
        return Math.min(clearA, clearB);
    }

    /** Distance from {@code (x,y,z)} to the low (min) face on {@code axis}. */
    private int faceLow(int axis, int x, int y, int z) {
        switch (axis) {
            case Axes.AXIS_X: return x - minX;
            case Axes.AXIS_Y: return y - minY;
            case Axes.AXIS_Z: return z - minZ;
            default:          return 0;
        }
    }

    /** Distance from {@code (x,y,z)} to the high (max) face on {@code axis}. */
    private int faceHigh(int axis, int x, int y, int z) {
        switch (axis) {
            case Axes.AXIS_X: return maxX - x;
            case Axes.AXIS_Y: return maxY - y;
            case Axes.AXIS_Z: return maxZ - z;
            default:          return 0;
        }
    }
}
