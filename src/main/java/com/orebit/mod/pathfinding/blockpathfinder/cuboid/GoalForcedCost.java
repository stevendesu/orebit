package com.orebit.mod.pathfinding.blockpathfinder.cuboid;

import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.MiningModel;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.pathfinding.blockpathfinder.movements.MineDown;
import com.orebit.mod.pathfinding.blockpathfinder.movements.Pillar;
import com.orebit.mod.pathfinding.blockpathfinder.movements.Traverse;
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
 *   <li><b>Far-face exclusion (the one deliberate carve-out from strict min-over-faces):</b> the goal face
 *       whose stand cell lies on the FAR side of the goal along the dominant start→goal approach axis is
 *       EXCLUDED from the probe — from both the standable short-circuit and the min-premium candidate set.
 *       Rationale: the bot cannot approach from that face without first paying to pass the goal, so letting
 *       a cheap (or standable — premium-zeroing) far face into the min systematically UNDER-states the real
 *       forced approach cost. The canonical failure: a goal one block under a ledge, approached from below —
 *       the standable cell ABOVE the goal short-circuits the premium to 0 and the ground flood returns. The
 *       exclusion can over-charge the rare route that genuinely loops around behind the goal (a strict
 *       lower bound over ALL approaches would keep the face), so it is mildly inadmissible in that corner —
 *       accepted deliberately: the search already runs {@code greedyWeight ≥ 1} (non-admissible by design),
 *       and under-valuing forced dig/pillar work at the goal is the flood this class exists to kill. When
 *       start == goal on the dominant axis (all deltas zero), no face is excluded. <b>The vertical build
 *       face {@code (Y,+1)} is EXEMPT from the exclusion:</b> a goal floating over air forces a
 *       pillar-up-from-below regardless of which side the start is on — a bot approaching from above falls
 *       PAST the unsupported goal to the ground and must pillar back up — so excluding it (goal
 *       predominantly BELOW the start) would zero the very premium this class supplies and re-open the
 *       ground flood under the goal. Its standable short-circuit is likewise kept: a standable cell
 *       directly below the goal means standing on it occupies the goal, a genuinely cheap approach from
 *       either side.</li>
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
 * <h2>Per-step forced cost (the {@code forcedCost} in {@code perBlockPremium = forcedCost − octileFloor})</h2>
 * The real movement constants are read here in TICKS (PRD §10 Phase 1d), never literals:
 * <ul>
 *   <li><b>Build face</b> (climb a pillar to the goal): one pillar step =
 *       {@code Pillar.COST + placeBaseCost + placeRemovalPremium} (the move plus the bot's per-search
 *       build-face place cost — base plus the placed block's mine-out premium — supplied as the {@code
 *       pillarPlaceCost} argument from {@link MovementContext#pillarPlaceCost()}), so
 *       {@code perBlockPremium = Pillar.COST + pillarPlaceCost − octileFloor}. <b>Admissibility:</b> the
 *       place cost INCLUDES the removal premium but NOT the inventory premium, so it is a true lower bound on
 *       the real per-block place cost the bot pays when pillaring — the follower places the SOFTEST block it
 *       carries (the block the removal premium is measured from), and running out of it only makes the real
 *       cost higher, so under-crediting the inventory term keeps the heuristic admissible. With no
 *       {@code InventoryView} (headless / trace / tests) {@code pillarPlaceCost()} falls back to the static
 *       {@code MovementContext.PLACE_BASE_COST}, so those searches are unchanged; inventory-off uses the
 *       conjured block and the bound is exact.)</li>
 *   <li><b>Dig face</b> (mine sideways/down into the goal): one break step =
 *       {@code MineDown.COST + MiningModel.fastestTicks(descriptor) + digBreakBase} — the move plus the
 *       mining time of the <i>best possible</i> tool (the probe has no bot inventory, and the premium must
 *       be a LOWER bound, so it charges the cheapest dig any tool could manage) plus the search's configured
 *       flat per-break surcharge ({@code mining.breakBaseCost}, from
 *       {@link MovementContext#breakBaseCost()}; {@code 0} with no {@code InventoryView}). Admissible: every
 *       real folded break costs {@code ticksFor ≥ fastestTicks} plus the SAME base — the mirror of the
 *       build face carrying the configured place base. So {@code perBlockPremium = MineDown.COST +
 *       fastestTicks + digBreakBase − octileFloor}.</li>
 * </ul>
 * The "{@code octileFloor}" (= {@link Traverse#FLAT_COST}, one straight-block heuristic credit, now in ticks)
 * is subtracted because the octile already credits that much per block of straight-line distance, so the EXTRA
 * cost it under-counts is {@code (forcedCost − octileFloor)} per forced block.
 *
 * @see NavGridCuboidsView
 * @see Cuboid
 * @see MacroJump
 */
public final class GoalForcedCost {

    private GoalForcedCost() {}

    /**
     * The per-block heuristic credit the octile already gives a straight-line block (= {@link
     * Traverse#FLAT_COST}, one walk-tick/block) — subtracted from a forced step's full cost so the premium is
     * exactly the EXTRA the octile under-counts. In tick units (PRD §10 Phase 1d); was a literal {@code 1}
     * when costs were dimensionless.
     */
    private static final float OCTILE_FLOOR = Traverse.FLAT_COST;

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
     *       {@code forcedCost = Pillar.COST + pillarPlaceCost} (= {@code Pillar.COST + placeBaseCost +
     *       placeRemovalPremium}, the per-search build-face place cost passed in by the caller).</li>
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
     * <p><b>Far-face exclusion:</b> the face whose stand cell lies on the far side of the goal along the
     * dominant start→goal axis (argmax of {@code |goal − start|} per axis, tie-break X &gt; Z &gt; Y — the
     * same deterministic order as {@code BlockPathfinder.primaryAxis}) is skipped entirely: it is the one
     * face the bot can only reach AFTER passing the goal, so admitting it — into the standable short-circuit
     * OR the min — understates the premium (see the class doc). Zero delta on the dominant axis (start ==
     * goal on every axis) excludes nothing. The vertical build face {@code (Y,+1)} is never excluded, even
     * when it is the far face (goal predominantly below the start): a floating goal forces the pillar-up
     * from either side — from above the bot falls past the unsupported goal and must climb back — so
     * dropping it would null the anti-flood premium (see the class doc). Computed here, once per search —
     * cost is irrelevant off the per-node hot path.
     *
     * @param cuboids         the per-search cuboid query seam (cache + PathEdits overlay)
     * @param sx              search-start X (absolute world block coord) — fixes the dominant approach axis
     *                        for the far-face exclusion; never read per node
     * @param sy              search-start Y
     * @param sz              search-start Z
     * @param gx              goal X (absolute world block coord)
     * @param gy              goal Y
     * @param gz              goal Z
     * @param caps            the bot's capabilities — gate which forced approaches are even possible
     * @param pillarPlaceCost the per-search build-face place cost (base + removal premium, no inventory
     *                        premium — an admissible lower bound) from {@link MovementContext#pillarPlaceCost()};
     *                        falls back to the static {@link MovementContext#PLACE_BASE_COST} when the search
     *                        has no {@code InventoryView} (headless / trace / tests)
     * @param digBreakBase    the per-search flat per-break surcharge ({@code mining.breakBaseCost}) added to
     *                        the dig-face break step, from {@link MovementContext#breakBaseCost()}; {@code 0}
     *                        when the search has no {@code InventoryView}, leaving those searches unchanged
     * @param out             filled in place with the cheapest forced approach (or "no correction")
     */
    public static void probe(NavGridCuboidsView cuboids, int sx, int sy, int sz, int gx, int gy, int gz,
            BotCaps caps, float pillarPlaceCost, float digBreakBase, Forced out) {
        out.clear();
        if (cuboids == null) {
            return; // no macro view → no correction (legacy / unbounded search)
        }

        // Far-face exclusion: the dominant start→goal axis (argmax |delta|, tie-break X > Z > Y — same
        // deterministic order as BlockPathfinder.primaryAxis) and the SIGNED delta along it. The excluded
        // face is the one travelling AGAINST the approach (sign == -signum(domDelta)): its stand cell sits at
        // goal + signum(domDelta)·unit, past the goal from the start's side. domDelta == 0 ⇒ all deltas are
        // 0 (argmax) ⇒ no exclusion. Once per search — not the per-node hot path.
        int domAxis = Axes.AXIS_X;
        int domDelta = gx - sx;
        if (Math.abs(gz - sz) > Math.abs(domDelta)) { domAxis = Axes.AXIS_Z; domDelta = gz - sz; }
        if (Math.abs(gy - sy) > Math.abs(domDelta)) { domAxis = Axes.AXIS_Y; domDelta = gy - sy; }
        final int farSign = -Integer.signum(domDelta); // 0 = exclude nothing

        // The six axis-aligned approach directions, as (axis, sign). For each, the goal is entered by
        // travelling (axis, sign): the adjacent cell sits one step BACK along that direction, i.e. at
        // goal - unit(axis,sign), and the forced run lies between it and the goal.
        boolean haveBest = false;
        float bestPremium = Float.MAX_VALUE;

        for (int axis = Axes.AXIS_X; axis <= Axes.AXIS_Z; axis++) {
            for (int sign = -1; sign <= 1; sign += 2) {
                // Far face: only approachable after passing the goal — excluded from the standable
                // short-circuit AND the min-premium candidates (see the method/class doc). The vertical
                // BUILD face (Y,+1) is exempt: a floating goal forces a pillar-up-from-below no matter
                // which side the start is on (from above the bot falls PAST the unsupported goal), so
                // excluding it when the goal lies below the start would null the anti-flood premium.
                if (farSign != 0 && axis == domAxis && sign == farSign
                        && !(axis == Axes.AXIS_Y && sign > 0)) {
                    continue;
                }
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
                //     forcedCost = one pillar step = Pillar.COST + pillarPlaceCost (base + removal premium).
                if (axis == Axes.AXIS_Y && sign > 0 && caps.canPlace() && NavBlock.isPassable(desc)) {
                    // The forced air column extends AWAY from the goal (downward, the depth the bot must
                    // pillar UP through) — measure from the face cell in the -sign direction, NOT toward the
                    // goal (which would only reach the short air gap above it).
                    int extent = box.extentToward(ax, ay, az, axis, -sign); // forced air depth below the goal
                    if (extent > 0) {
                        float premium = (Pillar.COST + pillarPlaceCost) - OCTILE_FLOOR;
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
                //     A vanilla-unbreakable substrate (BREAKABLE bit off, hardness sentinel 255) becomes a
                //     dig face too when the bot opted into mining.allowUnbreakable — priced at the fixed
                //     stand-in, the same lower bound the real folded break pays. An owner-PROTECTED
                //     substrate is never a dig face (its BREAKABLE bit is off and the grind arm excludes
                //     it), matching the unbreakable-wall skip below: conservative, no invented premium.
                boolean digGeom = NavBlock.isBreakable(desc);
                boolean grindGeom = !digGeom && caps.allowUnbreakable()
                        && NavBlock.hasCollision(desc)
                        && NavBlock.hardness(desc) == MovementContext.UNBREAKABLE_HARDNESS
                        && !NavBlock.isProtected(desc);
                if (caps.canBreak() && (digGeom || grindGeom)) {
                    // The forced solid extends AWAY from the goal (the depth the bot must dig through to
                    // reach it from this side) — measure from the face cell in the -sign direction.
                    int extent = box.extentToward(ax, ay, az, axis, -sign); // forced rock depth out from the goal
                    if (extent > 0) {
                        // Real mining time of the BEST possible tool (admissible lower bound — the probe has no
                        // bot inventory, and over-estimating would refuse the optimal route) plus the search's
                        // flat per-break surcharge (every real folded break pays the same base, so the bound
                        // holds). An unbreakable-grind substrate uses the tool-derived stand-in's FASTEST-tier
                        // time instead (the tables hold only the UNMINEABLE sentinel for it) — the fastest
                        // pickaxe's cost, so the bound stays admissible whatever the bot actually carries.
                        // Resident-table scan, off the per-node hot path (probe runs once per search).
                        float mineTicks = digGeom ? MiningModel.fastestTicks(desc)
                                : MiningModel.unbreakableFastestTicks();
                        float breakStep = MineDown.COST + mineTicks + digBreakBase;
                        float premium = breakStep - OCTILE_FLOOR;
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
