package com.orebit.mod.pathfinding.blockpathfinder.cuboid;

import java.util.Arrays;

import com.orebit.mod.pathfinding.blockpathfinder.PathEdits;
import com.orebit.mod.pathfinding.blockpathfinder.RegionBound;
import com.orebit.mod.worldmodel.pathing.NavGridView;

import net.minecraft.core.BlockPos;

/**
 * The per-search cuboid query seam (MACRO-IMPLEMENTATION.md §5; MACRO-MOVEMENTS.md §5 item 4) — the cuboid
 * analog of {@link NavGridView}. <b>One instance per search</b>: it wraps the search's {@link NavGridView}
 * read seam plus the search's {@link PathEdits} speculative diff, owns a per-search base-cuboid cache, and
 * answers {@link #cuboidAt} with the cuboid for a (cell, travelAxis) <i>with</i> the path's speculative edits
 * folded in.
 *
 * <h2>Region memoization — extract each region ONCE, not each cell (the perf crux)</h2>
 * A {@link CuboidExtractor#extract} is O(box volume) — for a wide open corridor it scans thousands of cells.
 * The naive cache keyed by exact {@code (cell, axis)} re-ran that extract for <i>every</i> cell of a region
 * (they all have distinct keys), and a flood touches far more distinct cells than any fixed-size table holds,
 * so it saturated and re-extracted on essentially every call — the search froze (~300k ns/node). The fix:
 * cache the extracted <b>maximal boxes</b>, per axis, and answer a query by finding the box that
 * <b>contains</b> the cell. A uniform region is extracted ONCE (anchored at the first cell that probes it)
 * and every later cell inside it is an O(1) {@code contains()} hit. Same-navtype maximal boxes don't overlap
 * (each is maximal within the corridor), so the containing box is unambiguous; reusing a region's box for an
 * interior cell is conservative (the box is uniform and contains the cell — at worst a slightly shorter jump
 * than a box re-anchored at that cell, which is always safe).
 *
 * <p>Open terrain has a handful of regions, so the per-query scan is over a few boxes (with an MRU fast path
 * for the common "same region as last query" case). A search that somehow exceeds {@link #MAX_BOXES} distinct
 * regions degrades to an uncached extract for the overflow rather than growing unbounded — never a fault.
 *
 * <h2>The speculative-edit shrink (bounded to the edits, not the box)</h2>
 * {@link #cuboidAt} copies the cached committed base into the caller's {@code out}, then — if the path has
 * any edit <i>inside</i> the box — shrinks {@code out} to exclude it (an edit changed that cell's navtype, so
 * the box is no longer uniform there). Critically the scan is bounded to the <b>box ∩ {@link PathEdits}
 * bounding box</b>: a path's edits cluster tightly (a pillar is one column), so this is a handful of cells,
 * never the whole (possibly huge) box. Keep the check — it is the conservative-only correctness guard for the
 * speculative-edit case (without it a macro could jump through a cell the path just placed/broke) even though
 * it almost never fires (a goal-ward jump is ahead of the edits the path made behind it).
 *
 * <h2>Forward-only edit-shrink (Option D)</h2>
 * A macro jump always travels from the start cell in one {@code (travelAxis, sign)} direction. An edit the
 * path placed/broke <i>behind</i> the start cell along that direction is never traversed by the forward jump,
 * so it can never invalidate it. {@link #cuboidAt} therefore takes the travel {@code sign} and the shrink only
 * scans the <b>forward half</b> of the box ∩ edits intersection along the travel axis (from the start cell
 * forward), keeping the <b>full orthogonal range</b> — a lateral edit within the forward run still reduces
 * clearance and MUST be caught. This is conservative-only: it can merely <i>under</i>-shrink the unused
 * backward travel extent (which {@code MacroJump.extentToward(forward)} and {@code nearestOrthogonalFace} never
 * read), and never skips a forward or orthogonal edit. On an up-and-over (the path's pillar/bridge edits sit
 * below/behind the goal-ward node) this skips the bulk of the scanned intersection — it was ~57% of search CPU
 * before this clip.
 *
 * <p><b>Single-threaded per search.</b> Like {@link NavGridView}, the view is used single-threaded during one
 * pathfind, so the mutable caches and pooled {@link Cuboid}s need no synchronization.
 */
public final class NavGridCuboidsView {

    /** The committed nav read seam (no speculative edits) the base extractor reads. */
    private final NavGridView grid;

    /**
     * The SAME {@link PathEdits} instance {@link com.orebit.mod.pathfinding.blockpathfinder.MovementContext}
     * walks each expansion — its current contents are the speculative edits along the path that reached the
     * node now being expanded. {@link #cuboidAt} consults it to shrink the committed base.
     */
    private final PathEdits pathEdits;

    /**
     * The corridor every cuboid is clipped to (passed through to {@link CuboidExtractor#extract}). May be
     * {@code null} for the legacy unbounded search — in which case the extractor reports no cuboid (an
     * invalid {@code out}) and the caller falls back to micro moves (MACRO-IMPLEMENTATION §8).
     */
    private final RegionBound bound;

    // --- Per-axis lists of extracted maximal boxes (region memoization, see class doc). Only VALID boxes are
    //     stored, so the contains() hit-check needs no validity test. Lists grow on demand; lastHit is an MRU
    //     index probed first (consecutive queries cluster in one region). pending[axis] is the scratch the
    //     next extract writes into — promoted into the list iff it comes back valid, so an invalid extract
    //     (an unbuilt / out-of-corridor cell — cheap: the extractor's start-cell gate returns at once) costs
    //     no slot and no allocation. ---
    private static final int MAX_BOXES = 256;
    private final Cuboid[][] boxes = new Cuboid[3][];
    private final int[] boxCount = new int[3];
    private final int[] lastHit = new int[3];
    private final Cuboid[] pending = { new Cuboid(), new Cuboid(), new Cuboid() };
    private final Cuboid overflowScratch = new Cuboid();

    public NavGridCuboidsView(NavGridView grid, PathEdits pathEdits, RegionBound bound) {
        this.grid = grid;
        this.pathEdits = pathEdits;
        this.bound = bound;
    }

    /**
     * The base cuboid for {@code (x,y,z, travelAxis)} over COMMITTED state. If an already-extracted maximal
     * box of this axis contains the cell, that box is returned (O(1) MRU / short linear scan); otherwise a new
     * box is extracted, anchored here, and cached (unless it is invalid — an unbuilt / out-of-corridor cell —
     * or the region cache is full). <b>The returned cuboid is the cache's own instance — callers MUST NOT
     * mutate it</b> (use {@link #cuboidAt}, which copies into a caller-owned out-param before the shrink).
     */
    private Cuboid baseCuboid(int x, int y, int z, int travelAxis) {
        Cuboid[] list = boxes[travelAxis];
        int n = boxCount[travelAxis];
        if (n > 0) {
            int last = lastHit[travelAxis];               // MRU: same region as the previous query?
            Cuboid b = list[last];
            if (b.contains(x, y, z)) return b;
            for (int i = 0; i < n; i++) {
                b = list[i];
                if (b.contains(x, y, z)) { lastHit[travelAxis] = i; return b; }
            }
        }

        // Miss — extract anchored at this cell into the per-axis scratch.
        Cuboid box = pending[travelAxis];
        CuboidExtractor.extract(grid, x, y, z, travelAxis, bound, box);
        if (!box.valid) {
            return box; // unbuilt / out-of-corridor: cheap, not cached (the caller falls back to micro)
        }
        if (n < MAX_BOXES) { // commit the valid maximal box; allocate a fresh scratch for the next extract
            if (list == null) { list = new Cuboid[16]; boxes[travelAxis] = list; }
            else if (n == list.length) { list = Arrays.copyOf(list, list.length << 1); boxes[travelAxis] = list; }
            list[n] = box;
            boxCount[travelAxis] = n + 1;
            lastHit[travelAxis] = n;
            pending[travelAxis] = new Cuboid();
        } else {
            // Cache full (a search spanning >MAX_BOXES distinct regions — pathological). Re-extract into a
            // throwaway so we never grow unbounded; correctness holds, only the memoization lapses here.
            CuboidExtractor.extract(grid, x, y, z, travelAxis, bound, overflowScratch);
            return overflowScratch;
        }
        return box;
    }

    /**
     * The cuboid for {@code (x,y,z, travelAxis)} WITH the search's speculative edits folded in, written into
     * the caller-owned {@code out}. Copies the cached committed base into {@code out}, then — if the path has
     * any edit inside that box — shrinks {@code out} so it excludes the offending cell. The cached base is
     * left untouched.
     *
     * <p>The {@code (travelAxis, sign)} pair names the direction the caller's macro jump travels from this
     * start cell; it scopes the edit-shrink to the FORWARD half of the box along that axis (Option D — see the
     * class doc). An edit behind the start cell along travel is never traversed by the jump, so it is skipped;
     * forward and orthogonal edits are still caught in full.
     *
     * @param x          start cell X (absolute world block coord)
     * @param y          start cell Y (absolute world block coord)
     * @param z          start cell Z (absolute world block coord)
     * @param travelAxis one of {@link Axes#AXIS_X}, {@link Axes#AXIS_Y}, {@link Axes#AXIS_Z}
     * @param sign       the travel direction along {@code travelAxis} ({@code -1} or {@code +1}) — scopes the
     *                   edit-shrink to the forward half of the box (cells at/ahead of the start cell)
     * @param out        the caller-owned pooled {@link Cuboid} to populate (copied base, then shrunk)
     */
    public void cuboidAt(int x, int y, int z, int travelAxis, int sign, Cuboid out) {
        Cuboid base = baseCuboid(x, y, z, travelAxis);
        if (!base.valid) {
            out.invalidate();
            return;
        }
        out.set(base.minX, base.minY, base.minZ, base.maxX, base.maxY, base.maxZ, base.navtype);
        if (pathEdits == null || pathEdits.isEmpty()) {
            return;
        }
        applyEditShrink(x, y, z, travelAxis, sign, out);
    }

    /**
     * Trim {@code out} so it excludes every current-path PLACED/BROKEN cell inside it, repeatedly taking the
     * cheapest single-face trim (the "shortest axis" proxy). Each pass scans only the box ∩ edits-bounding-box
     * intersection (the path's edits cluster tightly, so this is a handful of cells), trims one offending edit
     * past the nearest face, and re-scans (a trim may resolve or expose others). If a trim would exclude the
     * start cell, the box is invalidated (the caller falls back to a micro step).
     *
     * <p><b>Forward-only (Option D).</b> The scan is clipped along the travel axis to the FORWARD half — cells
     * at and ahead of the start cell in {@code (travelAxis, sign)} — while keeping the full orthogonal range. An
     * edit behind the start cell along travel is never traversed by the jump and is safely skipped; a forward
     * edit (which shrinks the box's forward extent) and a lateral edit within the forward run (which reduces the
     * orthogonal clearance) are still seen in full. This is conservative-only: it can only under-shrink the
     * unused backward extent.
     */
    private void applyEditShrink(int sx, int sy, int sz, int travelAxis, int sign, Cuboid out) {
        int safety = (out.maxX - out.minX) + (out.maxY - out.minY) + (out.maxZ - out.minZ) + 3;
        for (int pass = 0; pass < safety; pass++) {
            // Box ∩ edits-AABB: the only cells that can hold an edit. Recomputed each pass since `out` shrinks.
            int lx = Math.max(out.minX, pathEdits.editMinX());
            int hx = Math.min(out.maxX, pathEdits.editMaxX());
            int ly = Math.max(out.minY, pathEdits.editMinY());
            int hy = Math.min(out.maxY, pathEdits.editMaxY());
            int lz = Math.max(out.minZ, pathEdits.editMinZ());
            int hz = Math.min(out.maxZ, pathEdits.editMaxZ());

            // Option D: clip the TRAVEL axis to the forward half (cells at/ahead of the start cell along
            // (travelAxis, sign)); keep the FULL orthogonal range so lateral edits within the run are caught.
            // Conservative-only — only the unused backward extent is skipped, never a forward/orthogonal edit.
            switch (travelAxis) {
                case Axes.AXIS_X: if (sign > 0) lx = Math.max(lx, sx); else hx = Math.min(hx, sx); break;
                case Axes.AXIS_Y: if (sign > 0) ly = Math.max(ly, sy); else hy = Math.min(hy, sy); break;
                default:          if (sign > 0) lz = Math.max(lz, sz); else hz = Math.min(hz, sz); break;
            }

            if (lx > hx || ly > hy || lz > hz) return; // box and the (forward) edits don't overlap → nothing to shrink

            long offending = findEditInside(lx, hx, ly, hy, lz, hz);
            if (offending == NO_EDIT) return; // box is clean — done

            int ex = BlockPos.getX(offending);
            int ey = BlockPos.getY(offending);
            int ez = BlockPos.getZ(offending);

            // Cheapest valid trim (fewest cells dropped) that KEEPS the start cell inside the box.
            int bestCost = Integer.MAX_VALUE;
            int bestAxis = -1;
            boolean bestHigh = false;

            { // axis X
                int newMax = ex - 1;
                if (sx <= newMax && newMax >= out.minX) {
                    int cost = out.maxX - newMax;
                    if (cost < bestCost) { bestCost = cost; bestAxis = Axes.AXIS_X; bestHigh = true; }
                }
                int newMin = ex + 1;
                if (sx >= newMin && newMin <= out.maxX) {
                    int cost = newMin - out.minX;
                    if (cost < bestCost) { bestCost = cost; bestAxis = Axes.AXIS_X; bestHigh = false; }
                }
            }
            { // axis Y
                int newMax = ey - 1;
                if (sy <= newMax && newMax >= out.minY) {
                    int cost = out.maxY - newMax;
                    if (cost < bestCost) { bestCost = cost; bestAxis = Axes.AXIS_Y; bestHigh = true; }
                }
                int newMin = ey + 1;
                if (sy >= newMin && newMin <= out.maxY) {
                    int cost = newMin - out.minY;
                    if (cost < bestCost) { bestCost = cost; bestAxis = Axes.AXIS_Y; bestHigh = false; }
                }
            }
            { // axis Z
                int newMax = ez - 1;
                if (sz <= newMax && newMax >= out.minZ) {
                    int cost = out.maxZ - newMax;
                    if (cost < bestCost) { bestCost = cost; bestAxis = Axes.AXIS_Z; bestHigh = true; }
                }
                int newMin = ez + 1;
                if (sz >= newMin && newMin <= out.maxZ) {
                    int cost = newMin - out.minZ;
                    if (cost < bestCost) { bestCost = cost; bestAxis = Axes.AXIS_Z; bestHigh = false; }
                }
            }

            if (bestAxis == -1) {
                out.invalidate(); // no trim keeps the start cell — the box can't be salvaged
                return;
            }
            switch (bestAxis) {
                case Axes.AXIS_X: if (bestHigh) out.maxX = ex - 1; else out.minX = ex + 1; break;
                case Axes.AXIS_Y: if (bestHigh) out.maxY = ey - 1; else out.minY = ey + 1; break;
                default:          if (bestHigh) out.maxZ = ez - 1; else out.minZ = ez + 1; break;
            }
        }
    }

    /** Sentinel for {@link #findEditInside}: no PLACED/BROKEN edit lies inside the scanned sub-box. */
    private static final long NO_EDIT = Long.MIN_VALUE;

    /**
     * The packed-{@code BlockPos.asLong} position of some current-path edit (PLACED or BROKEN) inside the
     * inclusive sub-box {@code [lx..hx] × [ly..hy] × [lz..hz]} (already the box ∩ edits intersection), or
     * {@link #NO_EDIT}.
     *
     * <p><b>Adaptive: scans whichever side is smaller — the volume or the edit list.</b> Both strategies
     * return the SAME edit, so the choice is pure mechanics:
     * <ul>
     *   <li><b>Volume scan</b> ({@code y}-outer / {@code z}-middle / {@code x}-inner, first hit wins): one
     *       {@link PathEdits#kindAt(int, int, int)} hash probe per cell. Cheap when the intersection is a
     *       few cells (a pillar column clipped forward — the TOWER shape).</li>
     *   <li><b>Edit-list walk</b> ({@link PathEdits#editAt}, dense, no hashing): O(editCount) regardless of
     *       the intersection's size. Cheap when the intersection is large (a wide dig fan — the
     *       UPOVER_WALL shape, where the per-pass volume sweep dominated the whole search's CPU).</li>
     * </ul>
     * The volume scan's first hit in {@code (y,z,x)} order IS the in-box edit lexicographically minimal by
     * {@code (y,z,x)}, and the list walk selects exactly that minimum — so the two branches are
     * byte-identical in result (the shrink's greedy trim sequence depends on WHICH edit is returned), and
     * the {@code volume <= editCount} pick is a pure cost model, never a behavior switch.
     */
    private long findEditInside(int lx, int hx, int ly, int hy, int lz, int hz) {
        int n = pathEdits.editCount();
        long volume = (long) (hx - lx + 1) * (hy - ly + 1) * (hz - lz + 1);
        if (volume <= n) {
            // Small intersection: the classic first-hit sweep (identical to the pre-list implementation).
            for (int y = ly; y <= hy; y++) {
                for (int z = lz; z <= hz; z++) {
                    for (int x = lx; x <= hx; x++) {
                        if (pathEdits.kindAt(x, y, z) != PathEdits.NONE) {
                            return BlockPos.asLong(x, y, z);
                        }
                    }
                }
            }
            return NO_EDIT;
        }
        // Large intersection: walk the dense edit list, keeping the (y,z,x)-lexicographic minimum in-box hit.
        // "Have a best yet" is a separate flag, NOT `best == NO_EDIT`: Long.MIN_VALUE is itself a legal
        // packed BlockPos (x=-33554432, y=0, z=0), so the sentinel comparison could mistake a real best
        // for "unset" and let a later edit clobber the true minimum.
        boolean have = false;
        long best = NO_EDIT;
        int by = 0, bz = 0, bx = 0;
        for (int i = 0; i < n; i++) {
            long p = pathEdits.editAt(i);
            int x = BlockPos.getX(p), y = BlockPos.getY(p), z = BlockPos.getZ(p);
            if (x < lx || x > hx || y < ly || y > hy || z < lz || z > hz) continue;
            if (!have
                    || y < by
                    || (y == by && (z < bz || (z == bz && x < bx)))) {
                have = true;
                best = p; by = y; bz = z; bx = x;
            }
        }
        return best;
    }
}
