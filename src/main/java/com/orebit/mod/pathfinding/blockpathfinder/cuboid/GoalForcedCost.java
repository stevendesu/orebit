package com.orebit.mod.pathfinding.blockpathfinder.cuboid;

import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.pathfinding.blockpathfinder.movements.MineDown;
import com.orebit.mod.pathfinding.blockpathfinder.movements.Pillar;
import com.orebit.mod.worldmodel.navblock.NavBlock;

/**
 * The admissible goal-cuboid heuristic correction (MACRO-IMPLEMENTATION.md §7, MACRO-MOVEMENTS §4) — the
 * principled, provably-admissible form of the rejected "multiply the vertical cost by 4" hack.
 *
 * <h2>Why this exists</h2>
 * Macro-Pillar collapses the <i>vertical</i> axis of the open-air-pillar flood: instead of expanding every
 * intermediate cell of a tall air column, the search takes one jump. But that alone leaves a residual
 * <i>horizontal</i> ground-flood — every floor cell near the goal still looks as cheap as the cell below
 * the goal, because the plain octile heuristic has no idea that reaching a goal floating in the air FORCES
 * you to build a pillar (a per-block place cost the octile never charges). This class supplies that missing
 * lower bound: a floor cell carries the FULL remaining build premium, so it stops looking as cheap as
 * climbing. <b>Macro-ops collapse the vertical axis; this collapses the orthogonal one. Partners.</b>
 *
 * <h2>The shape of the correction</h2>
 * It is the principled version of the old blanket multiplier, which was <i>inadmissible</i> (it
 * over-estimated terrain stairs, so it could refuse the optimal route — removed in session 23). The fix:
 * <ul>
 *   <li>Probe the goal's <b>6 faces</b> via {@link NavGridCuboidsView} once at search start.</li>
 *   <li>A wide flat <b>air</b> cuboid <i>below</i> the goal ⇒ you MUST build up to reach it ⇒ the forced
 *       per-block cost is a <b>place</b> step. A <b>solid</b> cuboid to the <i>side</i> ⇒ you MUST dig in ⇒
 *       the forced per-block cost is a <b>break</b> step.</li>
 *   <li><b>Bounded per axis, not a blanket multiplier:</b> only over the cuboid's {@code extent} is the
 *       premium charged ("I can prove at least {@code extent} of these blocks are expensive; I know nothing
 *       about the rest"); past the cuboid it reverts to base cost.</li>
 *   <li><b>Admissibility (mandatory):</b> take the face that <b>MINIMIZES</b> the premium — the cheapest
 *       entry. If ANY face offers a cheap approach (an adjacent standable cell), the result extent is
 *       {@code 0} and NO premium applies. Over-estimating would be inadmissible and could refuse the optimal
 *       route.</li>
 *   <li><b>Off-axis (up-and-over) goal:</b> the min-over-faces naturally credits only the CHEAPER single
 *       axis, never the sum of two (MACRO-MOVEMENTS §4: charging both full premiums double-counts the shared
 *       staircase work → over-estimate → inadmissible). This conservatively under-credits the diagonal —
 *       accepting some residual flood there — but it is provably admissible and degenerates to plain A* where
 *       it adds nothing. <b>When unsure, UNDER-credit.</b></li>
 * </ul>
 *
 * <h2>Wiring</h2>
 * {@link #probe} runs once in {@code BlockPathfinder.findPath} before the search loop, filling a {@link
 * Forced} held on the {@code Relaxer}; {@link #premium} is then added per node inside {@code Relaxer.h},
 * after the existing octile + 3-D tie-break term:
 * <pre>
 *   h = octile(...) + tieBreak(...) + GoalForcedCost.premium(forced, x,y,z, gx,gy,gz);
 * </pre>
 *
 * <h2>Per-step forced cost (the {@code forcedCost} in {@code perBlockPremium = forcedCost − 1})</h2>
 * The real movement constants are read here, never literals:
 * <ul>
 *   <li><b>Build face</b> (climb a pillar to the goal): one pillar step =
 *       {@code Pillar.COST + MovementContext.PLACE_COST} = {@code 1.0 + 3.0 = 4.0}, so
 *       {@code perBlockPremium = 3.0}.</li>
 *   <li><b>Dig face</b> (mine sideways/down into the goal): one break step =
 *       {@code MineDown.COST + MovementContext.breakCost(descriptor)} (a flat
 *       {@code MovementContext.BREAK_BASE_COST = 2.0} plus a per-hardness term), so
 *       {@code perBlockPremium = MineDown.COST + breakCost − 1}.</li>
 * </ul>
 * The "{@code − 1}" is the octile floor: the heuristic already credits one unit per block of straight-line
 * distance, so the EXTRA cost the octile under-counts is {@code (forcedCost − 1)} per forced block.
 *
 * @see NavGridCuboidsView
 * @see Cuboid
 * @see MacroJump
 */
public final class GoalForcedCost {

    private GoalForcedCost() {}

    /**
     * The cost of one pillar step the bot pays to build straight up toward a goal floating in the air:
     * the base upward move ({@link Pillar#COST}) plus the placed footing ({@link MovementContext#PLACE_COST}).
     * The build-face {@code forcedCost}.
     */
    private static final float PILLAR_STEP_COST = Pillar.COST + MovementContext.PLACE_COST;

    /**
     * Result of the once-per-search probe — a tiny mutable, reusable value object (no per-search allocation:
     * the search holds one instance on the {@code Relaxer} and {@link #probe} fills it in place,
     * HOT-PATH-NO-ALLOC). {@code extent == 0} ⇒ no correction (some goal face offers a cheap approach, so
     * the premium would be inadmissible).
     */
    public static final class Forced {

        /** The forced approach axis ({@link Axes#AXIS_X}, {@link Axes#AXIS_Y}, {@link Axes#AXIS_Z}). */
        public int axis;

        /**
         * The direction along {@link #axis} that travels FROM the open region TOWARD the goal ({@code -1} or
         * {@code +1}). E.g. a build face below the goal forces an upward approach, so {@code axis = AXIS_Y},
         * {@code sign = +1}: the goal sits at the high end and forced blocks lie just below it.
         */
        public int sign;

        /**
         * The number of forced (expensive) blocks proven to lie between the goal and the open region along
         * {@link #axis} — i.e. the extent of the goal-adjacent forced cuboid. {@code 0} ⇒ no correction.
         */
        public int extent;

        /**
         * The EXTRA cost per forced block that the octile heuristic under-counts: {@code (forcedCost − 1)}.
         * Always {@code >= 0} for a real forced face (a place or break step costs at least one unit).
         */
        public float perBlockPremium;

        /** Reset to "no correction" — the safe default the probe falls back to when no face is forced. */
        void clear() {
            this.axis = Axes.AXIS_Y;
            this.sign = 1;
            this.extent = 0;
            this.perBlockPremium = 0f;
        }
    }

    /**
     * Probe the goal's six faces and fill {@code out} with the cheapest forced approach (or "no correction").
     *
     * <p>For each of the six axis-aligned unit directions {@code (axis, sign)} the algorithm looks at the
     * cell adjacent to the goal on that face (the cell the bot would stand in to enter the goal from that
     * side) and asks the cuboid view what uniform box that cell lives in, measured orthogonal to the
     * approach axis:
     * <ul>
     *   <li>If the adjacent cell is <b>standable</b> (a solid-topped, non-fluid, non-damaging floor the bot
     *       can simply step onto), this face offers a <b>cheap approach</b> → the goal is reachable for free
     *       from at least one side → {@code out.extent = 0}, NO premium. (Admissibility: a single cheap face
     *       kills the whole correction.)</li>
     *   <li>If the adjacent cell's cuboid is <b>air</b> and the approach axis is vertical from below (the bot
     *       must PILLAR up through that air to the goal), this is a <b>build face</b>:
     *       {@code forcedCost = Pillar.COST + PLACE_COST}.</li>
     *   <li>If the adjacent cell's cuboid is <b>solid breakable</b> rock (the bot must DIG through it to
     *       reach the goal), this is a <b>dig face</b>: {@code forcedCost = MineDown.COST + breakCost}.</li>
     *   <li>Otherwise (unbuilt, fluid, unbreakable wall, or an ambiguous read) the face contributes no
     *       proven-forced approach and is skipped — conservative, never invented cost.</li>
     * </ul>
     * Each candidate face yields {@code (extent, perBlockPremium)}; {@code out} keeps the one MINIMIZING
     * {@code perBlockPremium} (the cheapest forced entry). The first cheap (standable) face short-circuits to
     * {@code extent = 0}.
     *
     * <p><b>Off-axis goal:</b> because the result is the min over faces, a goal forced on more than one axis
     * (build up AND dig over) naturally credits only the CHEAPER single axis — never the sum (MACRO-MOVEMENTS
     * §4 conservative rule). When in doubt, this under-credits.
     *
     * @param cuboids the per-search cuboid query seam (cache + PathEdits overlay)
     * @param gx      goal X (absolute world block coord)
     * @param gy      goal Y
     * @param gz      goal Z
     * @param caps    the bot's capabilities — gate which forced approaches are even possible
     * @param out     filled in place with the cheapest forced approach (or "no correction")
     */
    public static void probe(NavGridCuboidsView cuboids, int gx, int gy, int gz, BotCaps caps, Forced out) {
        out.clear();
        if (cuboids == null) {
            return; // no macro view → no correction (legacy / unbounded search)
        }

        // The six axis-aligned approach directions, as (axis, sign). For each, the goal is entered by
        // travelling (axis, sign): the adjacent cell sits one step BACK along that direction, i.e. at
        // goal - unit(axis,sign), and the forced run lies between it and the goal.
        boolean haveBest = false;
        float bestPremium = Float.MAX_VALUE;

        for (int axis = Axes.AXIS_X; axis <= Axes.AXIS_Z; axis++) {
            for (int sign = -1; sign <= 1; sign += 2) {
                int dx = Axes.stepX(axis, sign);
                int dy = Axes.stepY(axis, sign);
                int dz = Axes.stepZ(axis, sign);
                // The cell the bot stands in to enter the goal from this face (one step back along travel):
                int ax = gx - dx, ay = gy - dy, az = gz - dz;

                // Resolve the uniform box that adjacent cell lives in, measured orthogonal to the approach
                // axis (a goal-ward jump along this face travels `axis`).
                Cuboid box = SCRATCH.get();
                // Pass this face's approach sign; the probe runs once at search START with pathEdits empty, so
                // applyEditShrink early-returns and the sign is a no-op here — passed correct for completeness.
                cuboids.cuboidAt(ax, ay, az, axis, sign, box);
                if (!box.isValid()) {
                    continue; // unbuilt / out-of-corridor — no proven forced approach on this face
                }

                long desc = NavBlock.descriptor((short) box.navtype());

                // (1) Cheap face: the bot can simply stand here and step onto the goal — NO premium at all.
                // A single cheap face is admissibility-decisive: it kills the whole correction.
                if (NavBlock.isStandable(desc)) {
                    out.clear();
                    return;
                }

                // (2) Build face: a vertical-from-below approach through an AIR column forces a pillar.
                //     forcedCost = one pillar step = Pillar.COST + PLACE_COST.
                if (axis == Axes.AXIS_Y && sign > 0 && caps.canPlace() && NavBlock.isPassable(desc)) {
                    // The forced air column extends AWAY from the goal (downward, the depth the bot must
                    // pillar UP through) — measure from the face cell in the -sign direction, NOT toward the
                    // goal (which would only reach the short air gap above it).
                    int extent = box.extentToward(ax, ay, az, axis, -sign); // forced air depth below the goal
                    if (extent > 0) {
                        float premium = PILLAR_STEP_COST - 1f;
                        if (!haveBest || premium < bestPremium) {
                            bestPremium = premium;
                            haveBest = true;
                            out.axis = axis;
                            out.sign = sign;
                            out.extent = extent;
                            out.perBlockPremium = premium;
                        }
                    }
                    continue;
                }

                // (3) Dig face: a solid, breakable cuboid the bot must mine through to reach the goal.
                //     forcedCost = one break step = MineDown.COST + breakCost(of this substrate).
                if (caps.canBreak() && NavBlock.isBreakable(desc)) {
                    // The forced solid extends AWAY from the goal (the depth the bot must dig through to
                    // reach it from this side) — measure from the face cell in the -sign direction.
                    int extent = box.extentToward(ax, ay, az, axis, -sign); // forced rock depth out from the goal
                    if (extent > 0) {
                        float breakStep = MineDown.COST
                                + MovementContext.BREAK_BASE_COST
                                + NavBlock.hardness(desc) * MovementContext.BREAK_PER_HARDNESS;
                        float premium = breakStep - 1f;
                        if (premium > 0f && (!haveBest || premium < bestPremium)) {
                            bestPremium = premium;
                            haveBest = true;
                            out.axis = axis;
                            out.sign = sign;
                            out.extent = extent;
                            out.perBlockPremium = premium;
                        }
                    }
                }
                // else: unbreakable wall / fluid / ambiguous — no proven-forced approach, skip (conservative).
            }
        }
        // If nothing was forced, `out` is still the cleared "no correction" state.
    }

    /**
     * The per-node heuristic premium added to {@code h}: the forced (expensive) cost still BETWEEN this node
     * and the goal along the forced axis that the octile heuristic under-counts.
     *
     * <p>{@code remainingForced} = the number of forced blocks still ahead of this node along
     * {@link Forced#axis}, clamped to {@code [0, extent]} (the cell may already be inside the forced run, or
     * past/before it). {@code premium = remainingForced × perBlockPremium}. This is a true lower bound on
     * unavoidable cost — provably admissible — so a floor cell far below a floating goal carries the FULL
     * remaining build premium and stops looking as cheap as a cell that has already climbed.
     *
     * <p>Returns {@code 0} when there is no correction ({@code f.extent == 0}).
     *
     * @param f  the once-per-search probe result
     * @param x  node X (absolute world block coord)
     * @param y  node Y
     * @param z  node Z
     * @param gx goal X
     * @param gy goal Y
     * @param gz goal Z
     * @return the admissible extra heuristic cost for this node ({@code >= 0})
     */
    public static float premium(Forced f, int x, int y, int z, int gx, int gy, int gz) {
        if (f.extent == 0) {
            return 0f;
        }

        // Distance from this node to the goal along the forced axis (signed toward the goal). The forced run
        // is the last `extent` blocks before the goal on this axis; how many of them are still ahead of the
        // node is min(distanceToGoal, extent), floored at 0 (a node already past the forced run owes nothing).
        int nodeCoord, goalCoord;
        switch (f.axis) {
            case Axes.AXIS_X: nodeCoord = x; goalCoord = gx; break;
            case Axes.AXIS_Y: nodeCoord = y; goalCoord = gy; break;
            default:          nodeCoord = z; goalCoord = gz; break;
        }

        // Forward distance toward the goal along the forced direction; if the node is on the far side of the
        // goal (or level with it) there is no forced run ahead of it.
        int distToGoal = f.sign * (goalCoord - nodeCoord);
        if (distToGoal <= 0) {
            return 0f;
        }

        int remaining = Math.min(distToGoal, f.extent);
        return remaining * f.perBlockPremium;
    }

    /**
     * A per-thread reusable {@link Cuboid} scratch for {@link #probe}'s six face reads — the probe runs ONCE
     * per search (not on the per-node hot path), but reusing one box across the six faces still avoids six
     * allocations per search and keeps the no-alloc discipline uniform. Thread-local because a server may run
     * several pathfinds concurrently on different threads (the background-search arc), and a {@code Cuboid} is
     * mutable; a {@code static} singleton would race. (If the integrator can guarantee single-threaded probe
     * calls, a plain {@code static} {@code Cuboid} is cheaper — see openQuestions.)
     */
    private static final ThreadLocal<Cuboid> SCRATCH = ThreadLocal.withInitial(Cuboid::new);
}
