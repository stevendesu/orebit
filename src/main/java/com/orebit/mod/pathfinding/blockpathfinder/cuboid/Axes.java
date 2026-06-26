package com.orebit.mod.pathfinding.blockpathfinder.cuboid;

/**
 * Axis / direction vocabulary for the cuboid macro-movement subsystem.
 *
 * <p>This is the smallest, lowest layer of the cuboid machinery (build order phase A,
 * {@code MACRO-IMPLEMENTATION.md} §11) — pure integer constants plus a handful of branch-free
 * static helpers. There is <strong>no state, no objects, and no enums</strong>: on the block-tier
 * A* hot path the direction of a movement is carried as a packed {@code (axis, sign)} pair of
 * {@code int}s, and these helpers are written so the JIT can inline them down to a couple of
 * compares (HOT-PATH-NO-ALLOC). An {@code enum Axis} would force a heap object / ordinal lookup on
 * a path that runs millions of times per search; ints are deliberate house style here.
 *
 * <h2>Direction encoding</h2>
 * A movement's travel direction is an <em>axis</em> ({@link #AXIS_X}, {@link #AXIS_Y},
 * {@link #AXIS_Z}) paired with a <em>sign</em> ({@code -1} or {@code +1}). Together they name one of
 * the six axis-aligned unit directions. The step-vector helpers ({@link #stepX}, {@link #stepY},
 * {@link #stepZ}) turn an {@code (axis, sign)} pair into the three components of that unit vector;
 * the orthogonal-axis helpers ({@link #orthA}, {@link #orthB}) name the two axes perpendicular to a
 * given travel axis, which the cuboid uses to measure its orthogonal cross-section (the clearance
 * that the escape-hedge bound divides by — see {@code MacroJump}, NON-NEGOTIABLE 2).
 *
 * <h2>Diagonal movements (Phase E2)</h2>
 * The diagonal macros ({@code Diagonal}, {@code Ascend}) do not travel along a single axis; they
 * carry a full integer step vector {@code (dx, dy, dz)}. The {@code Cuboid} / {@code MacroJump} API
 * is therefore designed to also accept an explicit step vector so axis-aligned and diagonal worlds
 * share one code path ({@code MACRO-IMPLEMENTATION.md} §8.4). These {@code (axis, sign)} helpers are
 * the convenience form for the three axis-aligned macros; they intentionally do not encode any
 * "axis-aligned only" assumption that would block the diagonal overloads.
 *
 * @see com.orebit.mod.pathfinding.blockpathfinder.cuboid.Cuboid
 * @see com.orebit.mod.pathfinding.blockpathfinder.cuboid.MacroJump
 */
public final class Axes {

    /** Not instantiable — static-only vocabulary. */
    private Axes() {}

    /** The X axis (east/west). */
    public static final int AXIS_X = 0;
    /** The Y axis (up/down). */
    public static final int AXIS_Y = 1;
    /** The Z axis (north/south). */
    public static final int AXIS_Z = 2;

    // ----------------------------------------------------------------------------------------------
    // Step vector for an axis-aligned direction (axis, sign).
    // For the named axis the component is `sign` (-1 or +1); for the other two it is 0.
    // ----------------------------------------------------------------------------------------------

    /**
     * The X component of the unit step vector for direction {@code (axis, sign)}:
     * {@code sign} when {@code axis == AXIS_X}, else {@code 0}.
     *
     * @param axis one of {@link #AXIS_X}, {@link #AXIS_Y}, {@link #AXIS_Z}
     * @param sign {@code -1} or {@code +1}
     * @return the X component of the step (one of {@code -1}, {@code 0}, {@code +1})
     */
    public static int stepX(int axis, int sign) { return axis == AXIS_X ? sign : 0; }

    /**
     * The Y component of the unit step vector for direction {@code (axis, sign)}:
     * {@code sign} when {@code axis == AXIS_Y}, else {@code 0}.
     *
     * @param axis one of {@link #AXIS_X}, {@link #AXIS_Y}, {@link #AXIS_Z}
     * @param sign {@code -1} or {@code +1}
     * @return the Y component of the step (one of {@code -1}, {@code 0}, {@code +1})
     */
    public static int stepY(int axis, int sign) { return axis == AXIS_Y ? sign : 0; }

    /**
     * The Z component of the unit step vector for direction {@code (axis, sign)}:
     * {@code sign} when {@code axis == AXIS_Z}, else {@code 0}.
     *
     * @param axis one of {@link #AXIS_X}, {@link #AXIS_Y}, {@link #AXIS_Z}
     * @param sign {@code -1} or {@code +1}
     * @return the Z component of the step (one of {@code -1}, {@code 0}, {@code +1})
     */
    public static int stepZ(int axis, int sign) { return axis == AXIS_Z ? sign : 0; }

    // ----------------------------------------------------------------------------------------------
    // The two axes orthogonal to `axis` — the plane in which the cuboid measures its cross-section.
    // For travel axis X the orthogonal pair is (Y, Z); for Y it is (X, Z); for Z it is (X, Y).
    // ----------------------------------------------------------------------------------------------

    /**
     * The first axis orthogonal to {@code axis}.
     *
     * <p>Returns {@link #AXIS_Y} when {@code axis == AXIS_X}, else {@link #AXIS_X}. Paired with
     * {@link #orthB(int)} this names the two-axis plane in which a {@code Cuboid}'s uniform
     * cross-section (and its nearest-orthogonal-face clearance) is measured.
     *
     * @param axis the travel axis, one of {@link #AXIS_X}, {@link #AXIS_Y}, {@link #AXIS_Z}
     * @return the first orthogonal axis
     */
    public static int orthA(int axis) { return axis == AXIS_X ? AXIS_Y : AXIS_X; }

    /**
     * The second axis orthogonal to {@code axis}.
     *
     * <p>Returns {@link #AXIS_Y} when {@code axis == AXIS_Z}, else {@link #AXIS_Z}. Together with
     * {@link #orthA(int)} this yields the two distinct axes perpendicular to {@code axis}: for X it
     * is {@code (Y, Z)}, for Y {@code (X, Z)}, for Z {@code (X, Y)}.
     *
     * @param axis the travel axis, one of {@link #AXIS_X}, {@link #AXIS_Y}, {@link #AXIS_Z}
     * @return the second orthogonal axis
     */
    public static int orthB(int axis) { return axis == AXIS_Z ? AXIS_Y : AXIS_Z; }
}
