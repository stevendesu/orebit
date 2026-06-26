package com.orebit.mod.pathfinding.blockpathfinder.cuboid;

import com.orebit.mod.pathfinding.blockpathfinder.RegionBound;
import com.orebit.mod.worldmodel.pathing.NavGridView;
import com.orebit.mod.worldmodel.pathing.TraversalGrid;

/**
 * The directional maximal cuboid extractor — <b>THE CORE</b> of cuboid macro-collapse
 * (MACRO-IMPLEMENTATION.md §4; MACRO-MOVEMENTS.md §3a). Given a start cell and a travel axis, it fills a
 * pooled {@link Cuboid} with the maximal axis-aligned box that (1) contains the cell, (2) is uniform in the
 * start cell's navtype, (3) lies entirely inside the corridor {@link RegionBound}, and (4) — among all such
 * boxes — maximizes the orthogonal cross-section relative to {@code travelAxis} (equivalently: is shortest
 * along {@code travelAxis}). That objective is rule (a): it is the mechanism that selects the
 * short-along-travel / wide-orthogonal box, so the box's own travel extent caps a macro jump exactly at the
 * exit rather than sailing past it.
 *
 * <h2>Why this is not a 1-D walk (NON-NEGOTIABLE 1)</h2>
 * The tempting shortcut — "to collapse a pillar, just count air cells straight up and jump that far" — is
 * <b>wrong</b>: a 1-D line knows nothing about its sides, so it cannot certify that no cheaper exit (a
 * staircase stepping off at {@code Y+3}, say) hides beside the run. A jump is sound <i>only because</i> the
 * orthogonal cross-section is uniform for the WHOLE jump. The moment you "check the sides as you go" you are
 * computing the cuboid. This extractor therefore does it in two stages and never substitutes a column walk
 * for stage&nbsp;1:
 * <ol>
 *   <li><b>Stage 1 — the maximal orthogonal slab at the start cell</b> (the cross-section). In the 2-D plane
 *       perpendicular to {@code travelAxis}, grow the rectangle of same-navtype, in-corridor cells around the
 *       start cell, choosing the grow order that <em>maximizes the nearest-face clearance</em> (Chebyshev:
 *       maximize {@code min(distance to the 4 sides)}). That clearance is exactly what NON-NEGOTIABLE 2's
 *       escape-hedge divides by, so a smaller square with all faces 3 away beats a wide-but-thin rectangle
 *       with a face 1 away — the square permits a bigger escape-bounded jump.</li>
 *   <li><b>Stage 2 — extend along {@code travelAxis} while the whole slab stays uniform.</b> Step outward in
 *       both signs; at each new layer every cell of the slab cross-section must be the same navtype and
 *       in-corridor — stop the instant any cell breaks. The extent reached is the box's travel span. (This is
 *       where a staircase at {@code Y+3} halts a tall box: the wide slab fails at {@code Y+3}, so the box
 *       tops out at {@code Y+2}.)</li>
 * </ol>
 *
 * <h2>Uniformity key</h2>
 * Two cells are "same substrate" iff they share a navtype id:
 * {@code TraversalGrid.navtypeOf(grid.packedAt(x,y,z))}. {@link NavGridView#UNBUILT} ({@code -1}) and any
 * cell outside the corridor are treated as a <b>different</b> navtype — a hard wall the box can never cross.
 * Air dedups to one navtype, same-hardness stone to one; mixed hardness correctly fragments (desired).
 *
 * <h2>Conservative-only errors (MACRO-MOVEMENTS §3b)</h2>
 * Every approximation must <b>shrink</b> the box, never grow it. When a read is ambiguous or a grow is
 * uncertain, stop growing. An under-sized box yields a shorter jump and plain A* fills the gap (always safe);
 * an over-sized box yields a jump through a non-uniform cell — an <b>invalid</b> path (never acceptable).
 * When in doubt, shrink.
 *
 * <h2>Scope</h2>
 * Reads <b>committed</b> navtypes only (via {@code grid.packedAt}); the speculative {@code PathEdits} overlay
 * is applied later by {@link NavGridCuboidsView} (§5), never here. The box is <b>corridor-bounded</b> — it
 * may cross 16³ section boundaries, clipped to {@code bound}, which provides the finite cap (the orthogonal
 * plane and the travel run are both capped by the corridor, so the whole scan is tens–hundreds of reads).
 *
 * <p><b>Static-only, no per-call allocation</b> (HOT-PATH-NO-ALLOC): the result is written into the caller's
 * pooled {@code out}; the four scratch counters live on the stack as primitives. No objects, no boxing.
 */
public final class CuboidExtractor {

    /** Not instantiable — the extractor is a single static entry point. */
    private CuboidExtractor() {}

    /**
     * Fill {@code out} with the directional maximal cuboid containing {@code (sx,sy,sz)} for {@code travelAxis}
     * (see the class doc for the full contract). Reads committed navtypes only; clips every grow to
     * {@code bound}. If the start cell is unbuilt ({@code packedAt == UNBUILT}) or outside {@code bound},
     * sets {@code out.invalidate()} and returns.
     *
     * @param grid       the per-search read seam over committed nav data
     * @param sx         start cell X (absolute world block coord)
     * @param sy         start cell Y (absolute world block coord)
     * @param sz         start cell Z (absolute world block coord)
     * @param travelAxis one of {@link Axes#AXIS_X}, {@link Axes#AXIS_Y}, {@link Axes#AXIS_Z}
     * @param bound      the corridor box every cell of the cuboid must lie inside
     * @param out        the pooled {@link Cuboid} to populate in place
     */
    public static void extract(NavGridView grid, int sx, int sy, int sz, int travelAxis,
                               RegionBound bound, Cuboid out) {

        // --- Start-cell gate. An unbuilt or out-of-corridor start cell has no cuboid (NON-NEGOTIABLE: a hard
        //     wall is not a navtype we can grow from). Report "no cuboid here" and bail. ---
        if (bound == null || !bound.allows(sx, sy, sz)) {
            out.invalidate();
            return;
        }
        int startPacked = grid.packedAt(sx, sy, sz);
        if (startPacked == NavGridView.UNBUILT) {
            out.invalidate();
            return;
        }
        final int nav = TraversalGrid.navtypeOf(startPacked);

        // The two axes perpendicular to the travel axis — the plane the cross-section slab lives in.
        final int a = Axes.orthA(travelAxis);
        final int b = Axes.orthB(travelAxis);

        // ============================================================================================
        // STAGE 1 — maximal orthogonal slab at the start layer, maximizing Chebyshev face clearance.
        //
        // The slab is a rectangle in the (a,b) plane at the start cell's travel-axis coordinate. We grow it
        // one full edge at a time, ALWAYS extending the currently-nearest (smallest-clearance) open face, so
        // the minimum of the four face clearances rises as fast as possible (Chebyshev objective, §4). A face
        // is "open" until a full edge-row of would-be-new cells fails uniformity / the corridor; then it is
        // frozen. We stop when all four faces are frozen. Every grow is one cell — clamping the box to the
        // last fully-uniform rectangle (conservative-only: an edge that partly fails freezes BEFORE claiming
        // any of it).
        //
        // The four faces are the low/high faces on axis `a` and on axis `b`. We track each face's current
        // outer coordinate as an offset from the start cell, and a "frozen" flag.
        // ============================================================================================

        // Clearances from the start cell to each of the four slab faces (cells). Start at 0 (the slab is the
        // single start cell) and grow outward.
        int aLo = 0, aHi = 0, bLo = 0, bHi = 0;
        boolean aLoOpen = true, aHiOpen = true, bLoOpen = true, bHiOpen = true;

        while (aLoOpen || aHiOpen || bLoOpen || bHiOpen) {
            // Pick the open face with the SMALLEST current clearance (Chebyshev: lift the binding constraint
            // first). Ties resolved by a fixed order — harmless, the final rectangle is the same maximal slab.
            int pick = -1;
            int best = Integer.MAX_VALUE;
            if (aLoOpen && aLo < best) { best = aLo; pick = 0; }
            if (aHiOpen && aHi < best) { best = aHi; pick = 1; }
            if (bLoOpen && bLo < best) { best = bLo; pick = 2; }
            if (bHiOpen && bHi < best) { best = bHi; pick = 3; }
            // pick is always set here (at least one face is open by the while-condition).

            // Try to extend the chosen face by one full edge-row. The new edge spans the CURRENT extent of the
            // OTHER (perpendicular-within-the-plane) axis; if any cell on it fails, freeze the face and claim
            // nothing new on it.
            switch (pick) {
                case 0: // grow low on axis `a`
                    if (edgeUniformA(grid, sx, sy, sz, travelAxis, a, b, nav, bound,
                                     -(aLo + 1), bLo, bHi)) {
                        aLo++;
                    } else {
                        aLoOpen = false;
                    }
                    break;
                case 1: // grow high on axis `a`
                    if (edgeUniformA(grid, sx, sy, sz, travelAxis, a, b, nav, bound,
                                     (aHi + 1), bLo, bHi)) {
                        aHi++;
                    } else {
                        aHiOpen = false;
                    }
                    break;
                case 2: // grow low on axis `b`
                    if (edgeUniformB(grid, sx, sy, sz, travelAxis, a, b, nav, bound,
                                     -(bLo + 1), aLo, aHi)) {
                        bLo++;
                    } else {
                        bLoOpen = false;
                    }
                    break;
                default: // case 3: grow high on axis `b`
                    if (edgeUniformB(grid, sx, sy, sz, travelAxis, a, b, nav, bound,
                                     (bHi + 1), aLo, aHi)) {
                        bHi++;
                    } else {
                        bHiOpen = false;
                    }
                    break;
            }
        }

        // ============================================================================================
        // STAGE 2 — extend along the travel axis while the WHOLE slab stays uniform & in-corridor.
        //
        // Step outward in both signs from the start layer. At each candidate layer, the entire (aLo..aHi,
        // bLo..bHi) slab cross-section must be the same navtype and inside `bound`; the instant one cell
        // breaks, that side stops (conservative: we never claim a partly-uniform layer).
        // ============================================================================================

        int tLo = 0, tHi = 0; // travel-axis extent below / above the start layer
        while (slabUniform(grid, sx, sy, sz, travelAxis, a, b, nav, bound, -(tLo + 1), aLo, aHi, bLo, bHi)) {
            tLo++;
        }
        while (slabUniform(grid, sx, sy, sz, travelAxis, a, b, nav, bound, (tHi + 1), aLo, aHi, bLo, bHi)) {
            tHi++;
        }

        // --- Assemble the absolute-world inclusive box from the per-axis extents. ---
        // The travel axis spans [start - tLo .. start + tHi]; axis `a` spans [start - aLo .. start + aHi];
        // axis `b` spans [start - bLo .. start + bHi]. Convert those per-axis (lo,hi) offset pairs into
        // (minX,minY,minZ,maxX,maxY,maxZ).
        int minX = sx, minY = sy, minZ = sz, maxX = sx, maxY = sy, maxZ = sz;
        minX -= loOnAxis(Axes.AXIS_X, travelAxis, a, b, tLo, aLo, bLo);
        maxX += hiOnAxis(Axes.AXIS_X, travelAxis, a, b, tHi, aHi, bHi);
        minY -= loOnAxis(Axes.AXIS_Y, travelAxis, a, b, tLo, aLo, bLo);
        maxY += hiOnAxis(Axes.AXIS_Y, travelAxis, a, b, tHi, aHi, bHi);
        minZ -= loOnAxis(Axes.AXIS_Z, travelAxis, a, b, tLo, aLo, bLo);
        maxZ += hiOnAxis(Axes.AXIS_Z, travelAxis, a, b, tHi, aHi, bHi);

        out.set(minX, minY, minZ, maxX, maxY, maxZ, nav);
    }

    // ------------------------------------------------------------------------------------------------
    // Edge / slab uniformity probes (CUBOID-PERF-OPTIONS §A — "bulk, section-local, memory-order scan").
    //
    // Each probe is a rectangle in the (travelAxis, a, b) OFFSET basis relative to the start cell. Rather
    // than test one cell at a time (the old per-cell `cellOk` → `packedAt` → re-resolve-the-section path,
    // ~82% of search CPU), a probe maps its offset-rectangle to an absolute WORLD-space inclusive rectangle
    // ONCE via the axis-component plumbing, then hands it to the bulk primitive `rectUniform`, which clips
    // the corridor into integer loop bounds, decomposes the rectangle at every 16-boundary into
    // section-aligned sub-rectangles, resolves each section's backing `short[]` exactly once through the
    // per-search chunk cache, and scans it in backing-array memory order (x innermost, +1 per cell). The
    // RESULTING BOX IS BYTE-IDENTICAL to the per-cell version (same navtype key, same UNBUILT/out-of-corridor
    // hard-wall semantics) — only the reads change. A probe returns false the moment any cell fails, so a
    // caller that gets false claims nothing.
    // ------------------------------------------------------------------------------------------------

    /**
     * Is the whole edge-row on axis {@code a} at offset {@code aOff} (spanning {@code b} from {@code -bLo} to
     * {@code +bHi}, at the start travel layer) uniform & in-corridor? Used to grow a low/high face on axis
     * {@code a} during stage 1.
     */
    private static boolean edgeUniformA(NavGridView grid, int sx, int sy, int sz,
                                        int travelAxis, int a, int b, int nav, RegionBound bound,
                                        int aOff, int bLo, int bHi) {
        // tOff fixed at 0 (start layer); a fixed at aOff; b spans [-bLo, +bHi].
        return rectUniform(grid, sx, sy, sz, travelAxis, a, b, nav, bound,
                           0, 0, aOff, aOff, -bLo, bHi);
    }

    /**
     * Is the whole edge-row on axis {@code b} at offset {@code bOff} (spanning {@code a} from {@code -aLo} to
     * {@code +aHi}, at the start travel layer) uniform & in-corridor? Used to grow a low/high face on axis
     * {@code b} during stage 1.
     */
    private static boolean edgeUniformB(NavGridView grid, int sx, int sy, int sz,
                                        int travelAxis, int a, int b, int nav, RegionBound bound,
                                        int bOff, int aLo, int aHi) {
        // tOff fixed at 0 (start layer); a spans [-aLo, +aHi]; b fixed at bOff.
        return rectUniform(grid, sx, sy, sz, travelAxis, a, b, nav, bound,
                           0, 0, -aLo, aHi, bOff, bOff);
    }

    /**
     * Is the entire slab cross-section ({@code a} in {@code [-aLo,+aHi]} × {@code b} in {@code [-bLo,+bHi]})
     * at travel-axis offset {@code tOff} uniform & in-corridor? Used to extend the box along the travel axis
     * during stage 2 — every cell of the slab must hold or the layer is rejected.
     */
    private static boolean slabUniform(NavGridView grid, int sx, int sy, int sz,
                                       int travelAxis, int a, int b, int nav, RegionBound bound,
                                       int tOff, int aLo, int aHi, int bLo, int bHi) {
        // tOff fixed; a spans [-aLo, +aHi]; b spans [-bLo, +bHi].
        return rectUniform(grid, sx, sy, sz, travelAxis, a, b, nav, bound,
                           tOff, tOff, -aLo, aHi, -bLo, bHi);
    }

    /**
     * <b>The bulk uniformity primitive (CUBOID-PERF-OPTIONS §A).</b> Given an inclusive rectangle in the
     * {@code (travelAxis, a, b)} offset basis — travel offsets {@code [tLoR..tHiR]}, axis-{@code a} offsets
     * {@code [aLoR..aHiR]}, axis-{@code b} offsets {@code [bLoR..bHiR]}, all relative to the start cell —
     * answer whether <i>every</i> cell of the mapped world rectangle is in-corridor, built, and of navtype
     * {@code nav}. Returns false on the first mismatch / hard wall (UNBUILT section or clipped-away cell),
     * so the box only ever grows over a fully-uniform rectangle (conservative-only, NON-NEGOTIABLE 1).
     *
     * <h3>How it scans</h3>
     * <ol>
     *   <li>Map the offset rectangle to an absolute world inclusive box {@code [x0..x1]×[y0..y1]×[z0..z1]}
     *       via the axis-component plumbing (each offset axis is a distinct world axis — a permutation).</li>
     *   <li>Clip that world box against the corridor {@code bound} <b>once</b>, into integer loop bounds. If
     *       the clip dropped any cell (the clipped span is narrower than the requested span on any axis),
     *       a cell was out-of-corridor → <b>not uniform</b> (return false) — exactly the old per-cell
     *       {@code !bound.allows} hard wall, but tested once per axis instead of once per cell.</li>
     *   <li>Walk the (now fully in-corridor) box split at every 16-boundary into section-aligned
     *       sub-boxes; for each section resolve its {@code short[]} once via
     *       {@link NavGridView#sectionRawAt} (a {@code null} section is a hard wall → return false), then
     *       scan <i>every</i> cell of that sub-box in backing-array memory order — {@code y} outer,
     *       {@code z} middle, {@code x} innermost ({@code +1} per cell, contiguous) — comparing
     *       {@code (raw[idx] & NAVTYPE_MASK) == nav}. (The section-stepping outer loops carry inner
     *       per-cell loops over the section's Y and Z rows, so no row of the sub-box is skipped.)</li>
     * </ol>
     * Stack primitives only; no allocation, no boxing (the section is resolved through the view's cached
     * {@code lookupChunk}, so a chunk key is boxed at most once per distinct chunk per search).
     */
    private static boolean rectUniform(NavGridView grid, int sx, int sy, int sz,
                                       int travelAxis, int a, int b, int nav, RegionBound bound,
                                       int tLoR, int tHiR, int aLoR, int aHiR, int bLoR, int bHiR) {
        // --- 1. Map the (travelAxis, a, b) offset rectangle to an absolute world inclusive box. Each world
        //        axis takes its range from whichever offset axis is mapped to it; the offset lo/hi are already
        //        ordered (lo <= hi) by every caller, so comp(lo) <= comp(hi) per world axis. ---
        int x0 = sx + comp(Axes.AXIS_X, travelAxis, a, b, tLoR, aLoR, bLoR);
        int x1 = sx + comp(Axes.AXIS_X, travelAxis, a, b, tHiR, aHiR, bHiR);
        int y0 = sy + comp(Axes.AXIS_Y, travelAxis, a, b, tLoR, aLoR, bLoR);
        int y1 = sy + comp(Axes.AXIS_Y, travelAxis, a, b, tHiR, aHiR, bHiR);
        int z0 = sz + comp(Axes.AXIS_Z, travelAxis, a, b, tLoR, aLoR, bLoR);
        int z1 = sz + comp(Axes.AXIS_Z, travelAxis, a, b, tHiR, aHiR, bHiR);

        // --- 2. Clip against the corridor ONCE. If clipping shrank ANY axis span, at least one requested cell
        //        was out-of-corridor → a hard wall → not uniform. (Mirrors the old per-cell `!bound.allows`.)
        int cx0 = Math.max(x0, bound.minX()), cx1 = Math.min(x1, bound.maxX());
        int cy0 = Math.max(y0, bound.minY()), cy1 = Math.min(y1, bound.maxY());
        int cz0 = Math.max(z0, bound.minZ()), cz1 = Math.min(z1, bound.maxZ());
        if (cx0 != x0 || cx1 != x1 || cy0 != y0 || cy1 != y1 || cz0 != z0 || cz1 != z1) {
            return false;
        }

        // --- 3. Section-aligned bulk scan. The outer Y/Z/X loops STEP BY SECTION (split at every 16-boundary
        //        so a section's backing `short[]` is resolved exactly once); the inner (yy,zz,xl) loops scan
        //        EVERY cell of that section's sub-box. Within a section the local linear index is
        //        ((y&15)<<8)|((z&15)<<4)|(x&15); x is contiguous innermost (+1 per cell) for locality. ---
        for (int y = y0; y <= y1; ) {
            int ySecEnd = Math.min(y1, (y & ~15) + 15); // last world-Y still in this 16-section on Y
            for (int z = z0; z <= z1; ) {
                int zSecEnd = Math.min(z1, (z & ~15) + 15);
                for (int x = x0; x <= x1; ) {
                    int xSecEnd = Math.min(x1, (x & ~15) + 15);
                    // One section covers the WHOLE sub-box [x..xSecEnd] × [z..zSecEnd] × [y..ySecEnd] (all in
                    // one 16³ box). Resolve its backing array once, then scan every (yy,zz) row of the sub-box.
                    short[] raw = grid.sectionRawAt(x, y, z);
                    if (raw == null) return false; // unbuilt section — hard wall (== old packedAt == UNBUILT)
                    int xl0 = x & 15;
                    int xl1 = xSecEnd & 15;
                    for (int yy = y; yy <= ySecEnd; yy++) {
                        for (int zz = z; zz <= zSecEnd; zz++) {
                            int rowBase = ((yy & 15) << 8) | ((zz & 15) << 4); // (x-local 0) on this zz,yy row
                            for (int xl = xl0; xl <= xl1; xl++) {
                                if ((raw[rowBase | xl] & TraversalGrid.NAVTYPE_MASK) != nav) return false;
                            }
                        }
                    }
                    x = xSecEnd + 1;
                }
                z = zSecEnd + 1;
            }
            y = ySecEnd + 1;
        }
        return true;
    }

    // ------------------------------------------------------------------------------------------------
    // Axis-component plumbing. An offset triple (tOff along travelAxis, aOff along a, bOff along b) is mapped
    // to its per-world-axis component. Branch-free compares, JIT-inlined. This is the one place that knows
    // how (travelAxis, a, b) map onto world (X,Y,Z) — every probe routes through it, so a single correct
    // mapping covers the whole extractor (and, by taking explicit axes, leaves the door open for the diagonal
    // step-vector overload of §8.4 without baking axis-aligned-only assumptions into the geometry).
    // ------------------------------------------------------------------------------------------------

    /** The component along world axis {@code worldAxis} of the offset triple (in the (travelAxis,a,b) basis). */
    private static int comp(int worldAxis, int travelAxis, int a, int b, int tOff, int aOff, int bOff) {
        if (worldAxis == travelAxis) return tOff;
        if (worldAxis == a) return aOff;
        if (worldAxis == b) return bOff;
        return 0;
    }

    /** The low (negative-direction) extent on world axis {@code worldAxis} from the per-basis lo extents. */
    private static int loOnAxis(int worldAxis, int travelAxis, int a, int b, int tLo, int aLo, int bLo) {
        if (worldAxis == travelAxis) return tLo;
        if (worldAxis == a) return aLo;
        if (worldAxis == b) return bLo;
        return 0;
    }

    /** The high (positive-direction) extent on world axis {@code worldAxis} from the per-basis hi extents. */
    private static int hiOnAxis(int worldAxis, int travelAxis, int a, int b, int tHi, int aHi, int bHi) {
        if (worldAxis == travelAxis) return tHi;
        if (worldAxis == a) return aHi;
        if (worldAxis == b) return bHi;
        return 0;
    }
}
