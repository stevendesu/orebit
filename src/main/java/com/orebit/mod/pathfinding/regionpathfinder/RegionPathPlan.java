package com.orebit.mod.pathfinding.regionpathfinder;

import com.orebit.mod.worldmodel.hpa.RegionAddress;

import net.minecraft.core.BlockPos;

/**
 * The coarse navigation skeleton produced by {@link RegionPathfinder}: an <b>immutable, ordered sequence
 * of level-0 region addresses</b> from the start region to the goal region
 * (PRD §6.3–6.5, §7.1; HPA-IMPLEMENTATION.md §8, "3g output").
 *
 * <h2>Ratified design — face-to-center, NOT portals</h2>
 * The region tier is a <b>fixed cubic-grid implicit octree</b> (PRD §6.3), NOT the superseded semantic
 * {@code Region}/{@code Portal} flood-fill model. A {@code RegionPathPlan} therefore carries no portals,
 * no region objects, and no per-step edge metadata — it is purely the list of <b>level-0 region cells</b>
 * (each a single 16³ {@link com.orebit.mod.worldmodel.pathing.NavSection NavSection}) the bot should walk
 * through, in travel order. Index {@code 0} is the start region; the last index is the goal region (or, in
 * the lazy-refinement scale-guard case, the end of the refined leading segment — HPA-IMPLEMENTATION.md §8).
 *
 * <p>"Which regions, in what order" is the entire contract. <b>How</b> to move within / between regions is
 * decided by the block tier ({@link com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder}); any
 * traversable arrival into the next region is acceptable (no entrances, PRD §6.5). The
 * {@link com.orebit.mod.pathfinding.PathPlan} sliding-window driver consumes this skeleton, picking a
 * windowed block target every few regions.
 *
 * <h2>Storage (house style — HPA-IMPLEMENTATION.md §14)</h2>
 * Three parallel {@code int[]} arrays of the skeleton's level-0 region coords ({@code rxs/rys/rzs}); no
 * per-step objects, no boxing. {@link #centerOf(int)} materializes a {@link BlockPos} on demand from
 * {@link RegionAddress#centerX}/{@link RegionAddress#centerY}/{@link RegionAddress#centerZ} (level 0). The
 * arrays are sized exactly at construction and never mutated — the plan is immutable once built.
 *
 * @see RegionPathfinder
 * @see com.orebit.mod.pathfinding.PathPlan
 */
public final class RegionPathPlan {

    /** Level-0 region coords of each skeleton step, in travel order (index 0 = start region). */
    private final int[] rxs;
    private final int[] rys;
    private final int[] rzs;

    /**
     * Per-step <b>fragment id</b> under the HPA* fragment model (HPA-FRAGMENTS.md §2, §S3): which 6-connected
     * occupiable component of {@code rxs/rys/rzs[i]} this step commits to (uniform/collapsed regions ⇒ the
     * single synthetic fragment {@code 0}). {@code null} only for a bare-coords plan built via the legacy
     * coords-only constructor (no per-step fragment/portal); every plan the region tier produces today is a
     * fragment-model plan.
     */
    private final int[] frags;

    /**
     * Per-step <b>portal cell</b> — the world-block boundary cell where this step is entered from the previous
     * one (the matched-footprint overlap center for a portal edge, the target fragment's interior rep for an
     * intra-region mine edge, or the region face center for a uniform transit). It is the reachable
     * occupiable target the {@link com.orebit.mod.pathfinding.PathPlan} sliding-window driver aims at (S4),
     * replacing the geometric {@link #centerOf} projection that landed on buried/mid-air cells (the two bugs
     * HPA-FRAGMENTS.md §6 fixes). {@link #NO_PORTAL} on the start step (index 0, no incoming edge) and on every
     * step of a bare-coords plan ({@code portalX == null}).
     */
    private final int[] portalX;
    private final int[] portalY;
    private final int[] portalZ;

    /**
     * Per-step <b>dig-through flag</b> (PERF-DESIGN-region-dig-through.md §5): {@code true} iff this step's
     * {@link #portalCell portal cell} is a <b>buried</b> crossing the block tier must mine to (a Fix-1
     * dig-through edge — solid sat between the previous fragment's air pocket and this face). The
     * {@link com.orebit.mod.pathfinding.PathPlan} window driver passes such a target through unfiltered (like
     * its goal target) instead of rejecting/snapping the buried cell. {@code null} for a bare-coords /
     * center-model plan and for the trivial same-fragment plan (no dig steps).
     */
    private final boolean[] digs;

    /** Sentinel in {@link #portalX} for a step with no portal cell (the start step, or a center-model plan). */
    public static final int NO_PORTAL = Integer.MIN_VALUE;

    /** The dimension floor, needed to recover world-Y centers from region {@code ry} (overworld −64). */
    private final int minY;

    /**
     * The region-pyramid <b>level</b> these coords are at (HPA-CASCADE.md §9, S6.1): {@code 0} = leaves (the
     * level the {@link com.orebit.mod.pathfinding.PathPlan} block-window driver consumes), {@code >0} = a coarse
     * skeleton in the nested cascade. {@link #centerOf} resolves the world center through {@link RegionAddress}
     * at this level; portal cells are already world coords. L0 plans (the only kind before the cascade) keep
     * {@code level == 0}, so their behaviour is unchanged.
     */
    private final int level;

    /** Whether the skeleton's last region is the actual goal region (vs a refined leading segment end). */
    private final boolean reachedGoalRegion;

    /**
     * Build the immutable skeleton from parallel coord arrays in travel order. The arrays are taken by
     * reference (the caller in {@link RegionPathfinder} hands over freshly-sized arrays it does not retain);
     * callers must not mutate them afterward.
     *
     * @param rxs   level-0 region X per step, index 0 = start
     * @param rys   level-0 region Y per step
     * @param rzs   level-0 region Z per step
     * @param size  number of valid leading entries (the arrays may be exactly this length)
     * @param minY  dimension floor (for {@link #centerOf})
     * @param reachedGoalRegion whether the last entry is the true goal region
     */
    public RegionPathPlan(int[] rxs, int[] rys, int[] rzs, int size, int minY, boolean reachedGoalRegion) {
        this(rxs, rys, rzs, size, minY, 0, reachedGoalRegion);
    }

    /**
     * As {@link #RegionPathPlan(int[], int[], int[], int, int, boolean)} with an explicit pyramid {@code level}
     * (HPA-CASCADE.md §9): a coarse center-model skeleton carries its level so {@link #centerOf} resolves the
     * right-sized world center. The pre-cascade level-0 ctor delegates here with {@code level == 0}.
     */
    public RegionPathPlan(int[] rxs, int[] rys, int[] rzs, int size, int minY, int level,
                          boolean reachedGoalRegion) {
        // Trim to the valid prefix so size()/indexing is exact and the plan is truly immutable.
        if (rxs.length != size) {
            int[] tx = new int[size];
            int[] ty = new int[size];
            int[] tz = new int[size];
            System.arraycopy(rxs, 0, tx, 0, size);
            System.arraycopy(rys, 0, ty, 0, size);
            System.arraycopy(rzs, 0, tz, 0, size);
            this.rxs = tx;
            this.rys = ty;
            this.rzs = tz;
        } else {
            this.rxs = rxs;
            this.rys = rys;
            this.rzs = rzs;
        }
        this.frags = null;          // center-model plan: one node per region, no fragments / portals
        this.portalX = null;
        this.portalY = null;
        this.portalZ = null;
        this.digs = null;
        this.minY = minY;
        this.level = level;
        this.reachedGoalRegion = reachedGoalRegion;
    }

    /**
     * Build the immutable <b>fragment-model</b> skeleton (HPA-FRAGMENTS.md §S3): the same level-0 region coords
     * plus, per step, the committed {@code fragmentId} and the {@code portalCell} it is entered through. All
     * seven arrays are parallel and trimmed to {@code size}; the caller hands over freshly-sized arrays it does
     * not retain. Portal coords of {@link #NO_PORTAL} mark a step with no incoming edge (the start step).
     */
    public RegionPathPlan(int[] rxs, int[] rys, int[] rzs, int[] frags,
                          int[] portalX, int[] portalY, int[] portalZ,
                          int size, int minY, boolean reachedGoalRegion) {
        this(rxs, rys, rzs, frags, portalX, portalY, portalZ, null, size, minY, 0, reachedGoalRegion);
    }

    /**
     * As {@link #RegionPathPlan(int[], int[], int[], int[], int[], int[], int[], int, int, boolean)} with an
     * explicit pyramid {@code level} (HPA-CASCADE.md §9, S6.1) and the per-step {@code digs} tag
     * (PERF-DESIGN-region-dig-through.md §5): a coarse cascade skeleton carries its level so {@link #centerOf}
     * resolves the level-sized world center. {@code digs} may be {@code null} (no dig steps). The pre-cascade
     * level-0 ctor delegates here with {@code level == 0} and {@code digs == null}.
     */
    public RegionPathPlan(int[] rxs, int[] rys, int[] rzs, int[] frags,
                          int[] portalX, int[] portalY, int[] portalZ, boolean[] digs,
                          int size, int minY, int level, boolean reachedGoalRegion) {
        this.rxs = trim(rxs, size);
        this.rys = trim(rys, size);
        this.rzs = trim(rzs, size);
        this.frags = trim(frags, size);
        this.portalX = trim(portalX, size);
        this.portalY = trim(portalY, size);
        this.portalZ = trim(portalZ, size);
        this.digs = (digs == null) ? null : trim(digs, size);
        this.minY = minY;
        this.level = level;
        this.reachedGoalRegion = reachedGoalRegion;
    }

    /**
     * <b>Splice</b> an extension {@code suffix} onto {@code old} (DESIGN-rolling-skeleton.md §4.2, the rolling
     * skeleton's slide-and-extend primitive): produce a fresh immutable plan
     * {@code old[drop .. size-1] ++ suffix[1 ..]} — the consumed head is dropped, the tail is appended, and the
     * kept prefix is preserved <b>verbatim</b> (INV-1: a splice never re-derives any prefix step; the caller
     * shifts its index-valued cursors by {@code drop}). {@code suffix[0]} is by contract the same
     * {@code (region, fragment)} node as {@code old}'s tail (the suffix search starts AT the tail — §4.1), so it
     * is deduplicated at the join. The result carries {@code old}'s level and the <b>suffix's</b>
     * {@code reachedGoalRegion} flag (an extension that reaches the goal region makes the spliced plan final).
     *
     * <p>One cold array copy at crossing cadence (§10 — no ring/deque; the immutable-swap identity is what the
     * driver's change-detection relies on). The result is a fragment-model plan iff either input is (a
     * center-model step contributes fragment {@code 0} / {@link #NO_PORTAL}, via the same accessors the
     * readers use); two center-model inputs splice to a center-model plan.
     *
     * @param old    the current skeleton (its tail is the append point)
     * @param drop   how many consumed head steps to discard ({@code 0 ≤ drop ≤ old.size()-1}); this IS the
     *               index shift the caller applies to its cursors
     * @param suffix the extension ({@code suffix[0]} == {@code old}'s tail node; {@code size() ≥ 1})
     */
    public static RegionPathPlan splice(RegionPathPlan old, int drop, RegionPathPlan suffix) {
        if (old.level != suffix.level || old.minY != suffix.minY) {
            throw new IllegalArgumentException("splice across levels/dimensions: old L" + old.level
                    + "/minY " + old.minY + " vs suffix L" + suffix.level + "/minY " + suffix.minY);
        }
        if (drop < 0 || drop > old.size() - 1) {
            throw new IllegalArgumentException("splice drop " + drop + " out of range for size " + old.size());
        }
        // R40 (DESIGN-region-corner-crossing-v2.md §4.5.1): no index naming a skeleton POSITION may name a
        // corner-cut step — a chain node is not a place, and a boundary landing on one would surface three
        // layers away as a finer level aiming at open air. The producers are corner-free by construction
        // (reconstructFragments trims a partial-best corner tail; the commit cursor is fragment-gated; the
        // window target rides R32's NO_PORTAL) — so a corner boundary HERE is a caller bug, failed at the
        // boundary. (The splice RENUMBERS every step; no corner index is stable across one.)
        if (RegionPathfinder.isCornerCut(old.fragmentId(drop))) {
            throw new IllegalArgumentException("splice drop " + drop + " lands on a corner-cut step (R40)");
        }
        if (RegionPathfinder.isCornerCut(suffix.fragmentId(suffix.size() - 1))
                || RegionPathfinder.isCornerCut(old.fragmentId(old.size() - 1))) {
            throw new IllegalArgumentException("splice join/tail lands on a corner-cut step (R40)");
        }
        final int keep = old.size() - drop;             // old[drop .. size-1], preserved verbatim
        final int extra = Math.max(0, suffix.size() - 1); // suffix[1 ..] (suffix[0] == old tail, deduped)
        final int n = keep + extra;
        final int[] rx = new int[n];
        final int[] ry = new int[n];
        final int[] rz = new int[n];
        for (int i = 0; i < keep; i++) {
            rx[i] = old.rxs[drop + i];
            ry[i] = old.rys[drop + i];
            rz[i] = old.rzs[drop + i];
        }
        for (int i = 0; i < extra; i++) {
            rx[keep + i] = suffix.rxs[1 + i];
            ry[keep + i] = suffix.rys[1 + i];
            rz[keep + i] = suffix.rzs[1 + i];
        }
        if (!old.isFragmentModel() && !suffix.isFragmentModel()) {
            return new RegionPathPlan(rx, ry, rz, n, old.minY, old.level, suffix.reachedGoalRegion);
        }
        final int[] fr = new int[n];
        final int[] px = new int[n];
        final int[] py = new int[n];
        final int[] pz = new int[n];
        final boolean anyDigs = old.digs != null || suffix.digs != null;
        final boolean[] dg = anyDigs ? new boolean[n] : null;
        for (int i = 0; i < keep; i++) {
            final int j = drop + i;
            fr[i] = old.fragmentId(j);
            px[i] = old.portalX == null ? NO_PORTAL : old.portalX[j];
            py[i] = old.portalY == null ? NO_PORTAL : old.portalY[j];
            pz[i] = old.portalZ == null ? NO_PORTAL : old.portalZ[j];
            if (dg != null) dg[i] = old.isDig(j);
        }
        for (int i = 0; i < extra; i++) {
            final int j = 1 + i;
            final int k = keep + i;
            fr[k] = suffix.fragmentId(j);
            px[k] = suffix.portalX == null ? NO_PORTAL : suffix.portalX[j];
            py[k] = suffix.portalY == null ? NO_PORTAL : suffix.portalY[j];
            pz[k] = suffix.portalZ == null ? NO_PORTAL : suffix.portalZ[j];
            if (dg != null) dg[k] = suffix.isDig(j);
        }
        return new RegionPathPlan(rx, ry, rz, fr, px, py, pz, dg, n, old.minY, old.level,
                suffix.reachedGoalRegion);
    }

    /**
     * The §4.6 corner-run COLLAPSE (DESIGN-region-corner-crossing-v2.md, R17), as an instance primitive so
     * every blame producer shares ONE walk: hop {@code hop} is the edge {@code step[hop] → step[hop+1]};
     * when either endpoint is a corner-cut chain step, walk the FROM endpoint BACKWARD and the TO endpoint
     * FORWARD past the run and fill {@code out[0]/out[1]} with the physical
     * {@link RegionPathfinder#fragmentNodeKey}s of the REAL endpoints — the invalidation must say
     * {@code (A, fragA) → (D, fragD)}, never a virtual intermediate (a chain node carries no stable
     * identity across {@code (A, D)} pairs, and a row naming one would kill every corner through it).
     * Key CONSTRUCTION, not blame selection: the FROM walk deliberately ignores any window bound, and R40
     * guarantees both walks terminate on a real step (a skeleton never begins or ends on a corner-cut
     * step). Consumers: {@code PathPlan.blockedHop} (the block-tier blame) and
     * {@code HierarchicalRegionPlan.blameTubeConfined} (the escalation blame — a §4.5.1-census member the
     * design's table missed).
     */
    public void collapsedHopKeys(int hop, long[] out) {
        int from = hop;
        while (from > 0 && RegionPathfinder.isCornerCut(fragmentId(from))) from--;
        int to = hop + 1;
        final int lastStep = size() - 1;
        while (to < lastStep && RegionPathfinder.isCornerCut(fragmentId(to))) to++;
        out[0] = RegionPathfinder.fragmentNodeKey(rx(from), ry(from), rz(from), fragmentId(from));
        out[1] = RegionPathfinder.fragmentNodeKey(rx(to), ry(to), rz(to), fragmentId(to));
    }

    /** Trim {@code a} to exactly {@code size} (returns it unchanged when already that length). */
    private static int[] trim(int[] a, int size) {
        if (a.length == size) {
            return a;
        }
        int[] t = new int[size];
        System.arraycopy(a, 0, t, 0, size);
        return t;
    }

    /** Trim {@code a} to exactly {@code size} (returns it unchanged when already that length). */
    private static boolean[] trim(boolean[] a, int size) {
        if (a.length == size) {
            return a;
        }
        boolean[] t = new boolean[size];
        System.arraycopy(a, 0, t, 0, size);
        return t;
    }

    /** Number of skeleton regions (0 for an empty/failed plan). */
    public int size() {
        return rxs.length;
    }

    /** {@code true} iff this plan has no regions. */
    public boolean isEmpty() {
        return rxs.length == 0;
    }

    /** Whether the final skeleton region is the true goal region (vs the end of a refined leading segment). */
    public boolean reachedGoalRegion() {
        return reachedGoalRegion;
    }

    /** The region-pyramid level these coords are at ({@code 0} = leaves; {@code >0} = a coarse cascade skeleton). */
    public int level() {
        return level;
    }

    /** Level-0 region X of skeleton step {@code i}. */
    public int rx(int i) {
        return rxs[i];
    }

    /** Level-0 region Y of skeleton step {@code i}. */
    public int ry(int i) {
        return rys[i];
    }

    /** Level-0 region Z of skeleton step {@code i}. */
    public int rz(int i) {
        return rzs[i];
    }

    /** {@code true} iff this is a fragment-model plan (carries per-step {@code fragmentId} + {@code portalCell}). */
    public boolean isFragmentModel() {
        return frags != null;
    }

    /**
     * The committed fragment id of skeleton step {@code i} (HPA-FRAGMENTS.md §2): which 6-connected occupiable
     * component of region {@code i} the path passes through. Always {@code 0} for a center-model plan (one node
     * per region) and for uniform/collapsed regions (a single synthetic fragment).
     */
    public int fragmentId(int i) {
        return frags == null ? 0 : frags[i];
    }

    /** Whether step {@code i} has a portal cell (false on the start step and on every center-model step). */
    public boolean hasPortal(int i) {
        return portalX != null && portalX[i] != NO_PORTAL;
    }

    /**
     * Whether step {@code i}'s {@link #portalCell portal cell} is a <b>buried dig-through crossing</b>
     * (PERF-DESIGN-region-dig-through.md §5) — the block tier must mine to reach it, so the
     * {@link com.orebit.mod.pathfinding.PathPlan} window driver targets it directly rather than
     * rejecting/snapping the solid cell. Always {@code false} for a center-model / bare-coords plan.
     */
    public boolean isDig(int i) {
        return digs != null && digs[i];
    }

    /**
     * The world-block <b>portal cell</b> step {@code i} is entered through (HPA-FRAGMENTS.md §6) — a reachable
     * occupiable boundary cell, the fragment-model replacement for the geometric {@link #centerOf} projection.
     * {@code null} when {@link #hasPortal(int)} is false (the start step / a center-model plan); the driver
     * falls back to {@link #centerOf} there.
     */
    public BlockPos portalCell(int i) {
        return hasPortal(i) ? new BlockPos(portalX[i], portalY[i], portalZ[i]) : null;
    }

    /**
     * The world-block center of skeleton region {@code i} at this plan's {@link #level()}. Materializes a
     * {@link BlockPos} from {@link RegionAddress}'s level-aware center math; the driver projects this to a
     * standable floor cell to use it as a windowed block target (HPA-IMPLEMENTATION.md §9). For a level-0 plan
     * (the only kind the block-window driver consumes) this is the leaf center, exactly as before.
     */
    public BlockPos centerOf(int i) {
        int cx = RegionAddress.centerX(level, rxs[i]);
        int cy = RegionAddress.centerY(level, rys[i], minY);
        int cz = RegionAddress.centerZ(level, rzs[i]);
        return new BlockPos(cx, cy, cz);
    }
}
