package com.orebit.mod.pathfinding.blockpathfinder.cuboid;

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
 * <h2>Two layers: a cached committed base, then a per-query speculative shrink</h2>
 * <ol>
 *   <li><b>Base cuboid (cached, committed state).</b> {@link #baseCuboid} returns the maximal cuboid for a
 *       (cell, axis) over COMMITTED navtypes (what {@code grid.packedAt} reports — no speculative edits),
 *       computed by {@link CuboidExtractor#extract} on a miss and cached for the rest of the search. The base
 *       is immutable to callers and is reused verbatim on a hit.</li>
 *   <li><b>Speculative shrink (per query, never cached).</b> {@link #cuboidAt} copies the cached base into the
 *       caller's pooled {@code out}, then applies the {@link PathEdits} overlay: any cell the current path has
 *       PLACED or BROKEN <i>inside</i> the box has had its navtype changed, so the box is no longer uniform
 *       there — the box is shrunk past the offending edit. The base stays cached and untouched.</li>
 * </ol>
 *
 * <h2>The base cache (open-addressed, primitive, no boxing)</h2>
 * Keyed by {@code mix(BlockPos.asLong(x,y,z), travelAxis)}, mirroring the open-addressed chunk cache in
 * {@link NavGridView}: a {@code long[]} key array and a {@link Cuboid}{@code []} value array (the values are
 * <b>pooled</b> {@code Cuboid} instances filled in place on a miss). No {@code Map<Long,..>}, no autoboxing of
 * the key, no per-query allocation (HOT-PATH-NO-ALLOC). The cache is per-search — like the view itself, it
 * starts empty and is discarded at search end. <b>Cross-search base persistence + {@code patchCell}
 * invalidation is a DEFERRED optimization</b> (MACRO-MOVEMENTS §5 "deferred"); the base cuboids recompute
 * cheaply, so v1 does not build it.
 *
 * <h2>Why keep the overlay check even though it almost never fires (do NOT optimize it out)</h2>
 * A greedy near-optimal path doesn't re-enter its own edited cells, and a goal-ward jump is <i>ahead</i> of
 * the edits made <i>behind</i> it (a pillar's support blocks are below the bot; the jump is the air above —
 * disjoint). So the overlay scan is a handful of point-in-box tests that almost always find nothing. It is
 * nonetheless the <b>conservative-only correctness guard</b> for the speculative-edit case (MACRO-MOVEMENTS
 * §3b): without it, a macro could jump through a cell the path itself just placed/broke — an invalid path.
 * Keep it.
 *
 * <p><b>Single-threaded per search.</b> Like {@link NavGridView}, the view is used single-threaded during one
 * pathfind, so the mutable cache and the pooled {@code Cuboid} values need no synchronization.
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

    // --- Per-search base-cuboid cache. Open-addressed, primitive-keyed, no boxing (mirror of the chunk cache
    //     in NavGridView). A power-of-two capacity sized well above the distinct (cell,axis) pairs a single
    //     bounded search touches; the view is per-pathfind, so it starts empty with no clearing. On the rare
    //     saturation (a search probing > CAP distinct pairs) we degrade to a direct extract rather than hang.
    //     Value slots are POOLED Cuboid instances: a slot's Cuboid is allocated once on first use of that slot
    //     and refilled in place by CuboidExtractor.extract — never per query. ---
    private static final int CAP = 1024;
    private static final int MASK = CAP - 1;

    private final long[] keys = new long[CAP];
    // A slot is COLD iff its value is null (the value array is the authoritative occupancy marker — like
    // NavGridView's ccVals[slot]==null). This avoids needing a key sentinel that could collide with a real
    // mixed key value (any long is a legal key after the axis XOR), so no key value is ever forbidden.
    private final Cuboid[] vals = new Cuboid[CAP]; // null slot = cold (no pooled Cuboid yet)

    public NavGridCuboidsView(NavGridView grid, PathEdits pathEdits, RegionBound bound) {
        this.grid = grid;
        this.pathEdits = pathEdits;
        this.bound = bound;
    }

    /**
     * The base cuboid for {@code (x,y,z, travelAxis)} over COMMITTED state, served from the per-search cache.
     * On a miss the slot's pooled {@link Cuboid} is filled by {@link CuboidExtractor#extract} (which sets it
     * invalid if the start cell is unbuilt or out of corridor) and cached. <b>The returned cuboid is the
     * cache's own instance — callers MUST NOT mutate it</b> (use {@link #cuboidAt}, which copies into a
     * caller-owned out-param before applying the speculative shrink).
     */
    private Cuboid baseCuboid(int x, int y, int z, int travelAxis) {
        long key = cacheKey(x, y, z, travelAxis);
        int slot = slotFor(key);
        for (int probes = 0; probes < CAP; probes++) {
            Cuboid box = vals[slot];
            if (box == null) { // cold slot — allocate the pooled Cuboid once, extract into it, and cache
                box = new Cuboid();
                vals[slot] = box;
                keys[slot] = key;
                CuboidExtractor.extract(grid, x, y, z, travelAxis, bound, box);
                return box;
            }
            if (keys[slot] == key) return box; // hit — the cached base
            slot = (slot + 1) & MASK;
        }
        // Cache saturated (a search probing > CAP distinct (cell,axis) pairs). Degrade to a direct extract into
        // the caller-visible scratch path: there is no free slot, so we cannot cache — fill the spare and return
        // it. (Production builds a fresh view per pathfind and the corridor bound keeps a windowed search to a
        // handful of chunks, so this should not fire on the live path.)
        Cuboid spare = saturationScratch;
        CuboidExtractor.extract(grid, x, y, z, travelAxis, bound, spare);
        return spare;
    }

    /** A single reusable Cuboid used only on the (should-never-fire) cache-saturation path of {@link #baseCuboid}. */
    private final Cuboid saturationScratch = new Cuboid();

    /**
     * The cuboid for {@code (x,y,z, travelAxis)} WITH the search's speculative edits folded in, written into
     * the caller-owned {@code out}. Copies the cached committed base into {@code out}, then — if the path has
     * any edit inside that box — shrinks {@code out} so it excludes the offending cell (an edit changes the
     * navtype, so the box is no longer uniform there). The cached base is left untouched.
     *
     * <p>The shrink trims the <b>nearest face past the offending edit</b> (the "shortest axis" cheap proxy,
     * MACRO-MOVEMENTS §3b): for each edited cell inside the box we measure, per axis, how few cells we'd have
     * to drop to push that face just short of the edit, and take the cheapest single such trim. Conservative
     * by construction — every trim shrinks the box, never grows it (MACRO-MOVEMENTS §3b). If a trim would
     * leave the start cell {@code (x,y,z)} outside the box (the edit IS the start cell, or sits between the
     * start cell and the only trimmable face), the cuboid collapses to invalid — the caller falls back to a
     * micro step there.
     *
     * @param x          start cell X (absolute world block coord)
     * @param y          start cell Y (absolute world block coord)
     * @param z          start cell Z (absolute world block coord)
     * @param travelAxis one of {@link Axes#AXIS_X}, {@link Axes#AXIS_Y}, {@link Axes#AXIS_Z}
     * @param out        the caller-owned pooled {@link Cuboid} to populate (copied base, then shrunk)
     */
    public void cuboidAt(int x, int y, int z, int travelAxis, Cuboid out) {
        Cuboid base = baseCuboid(x, y, z, travelAxis);
        if (base == null || !base.valid) {
            out.invalidate();
            return;
        }
        // Copy the cached base into the caller's out-param (so the base stays cached & untouched).
        out.set(base.minX, base.minY, base.minZ, base.maxX, base.maxY, base.maxZ, base.navtype);

        // Speculative-edit shrink. The check is cheap (PathEdits has its own bounding-box reject) and almost
        // never fires, but it is the conservative-only guard for the speculative-edit case — KEEP it (§5).
        if (pathEdits == null || pathEdits.isEmpty()) return;
        applyEditShrink(x, y, z, out);
    }

    /**
     * Trim {@code out} so it excludes every current-path PLACED/BROKEN cell inside it, repeatedly taking the
     * cheapest single-face trim (the "shortest axis" proxy). Each trim drops the cells from the box's nearest
     * face up to (and including) the offending edit on the cheapest axis; we then re-scan, since one trim may
     * leave another edit still inside. If a trim would exclude the start cell, the box is invalidated.
     */
    private void applyEditShrink(int sx, int sy, int sz, Cuboid out) {
        // Bounded loop: each pass either removes at least one edited cell from the box or terminates. The box is
        // corridor-small, so the number of distinct edits it can contain is tiny; we cap iterations at the box's
        // largest dimension as a hard safety so a pathological case can never spin.
        int safety = (out.maxX - out.minX) + (out.maxY - out.minY) + (out.maxZ - out.minZ) + 3;
        for (int pass = 0; pass < safety; pass++) {
            // Find the worst-case (cheapest-to-trim) edit currently inside the box. We trim ONE edit per pass —
            // the one whose nearest-face trim costs the fewest cells — and re-scan, because trimming may expose
            // or resolve others.
            long offending = findEditInside(out);
            if (offending == NO_EDIT) return; // box is clean — done

            int ex = BlockPos.getX(offending);
            int ey = BlockPos.getY(offending);
            int ez = BlockPos.getZ(offending);

            // Per axis, the cost (cells dropped) of trimming the LOW face up past the edit (max := edit-1) and
            // the HIGH face down past the edit (min := edit+1). The cheapest valid trim that KEEPS the start
            // cell wins. A trim is only valid on an axis if it leaves the start cell inside the box.
            int bestCost = Integer.MAX_VALUE;
            int bestAxis = -1;
            boolean bestHigh = false;

            // axis X
            { // trim high face down to ex-1: drops (maxX - (ex-1)) cells; valid iff sx <= ex-1
                int newMax = ex - 1;
                if (sx <= newMax && newMax >= out.minX) {
                    int cost = out.maxX - newMax;
                    if (cost < bestCost) { bestCost = cost; bestAxis = Axes.AXIS_X; bestHigh = true; }
                }
                int newMin = ex + 1; // trim low face up to ex+1: drops (ex+1 - minX); valid iff sx >= ex+1
                if (sx >= newMin && newMin <= out.maxX) {
                    int cost = newMin - out.minX;
                    if (cost < bestCost) { bestCost = cost; bestAxis = Axes.AXIS_X; bestHigh = false; }
                }
            }
            // axis Y
            {
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
            // axis Z
            {
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
                // No trim keeps the start cell (the edit IS the start cell, or it sits across the only trimmable
                // faces). The box can't be salvaged — invalidate so the caller falls back to a micro step.
                out.invalidate();
                return;
            }

            // Apply the cheapest trim.
            switch (bestAxis) {
                case Axes.AXIS_X:
                    if (bestHigh) out.maxX = ex - 1; else out.minX = ex + 1;
                    break;
                case Axes.AXIS_Y:
                    if (bestHigh) out.maxY = ey - 1; else out.minY = ey + 1;
                    break;
                default: // AXIS_Z
                    if (bestHigh) out.maxZ = ez - 1; else out.minZ = ez + 1;
                    break;
            }
        }
    }

    /** Sentinel for {@link #findEditInside}: no PLACED/BROKEN edit lies inside the box. */
    private static final long NO_EDIT = Long.MIN_VALUE;

    /**
     * Return the packed-{@code BlockPos.asLong} position of some current-path edit (PLACED or BROKEN) lying
     * inside the box, or {@link #NO_EDIT} if the box is clean. Scans the box cell-by-cell, querying
     * {@link PathEdits#kindAt(int, int, int)} — which itself rejects out-of-edit-box cells with six int
     * compares before any hash, so this is cheap (and almost always returns on the first miss because the box
     * rarely overlaps the edits at all). The box is corridor-small, so the worst-case scan is bounded.
     */
    private long findEditInside(Cuboid out) {
        for (int y = out.minY; y <= out.maxY; y++) {
            for (int z = out.minZ; z <= out.maxZ; z++) {
                for (int x = out.minX; x <= out.maxX; x++) {
                    if (pathEdits.kindAt(x, y, z) != PathEdits.NONE) {
                        return BlockPos.asLong(x, y, z);
                    }
                }
            }
        }
        return NO_EDIT;
    }

    // ------------------------------------------------------------------------------------------------
    // Cache key + slot. The key folds (cell, travelAxis) into one long, then a Murmur3 finalizer maps it to a
    // slot — the same primitive, no-boxing scheme as NavGridView's chunk cache.
    // ------------------------------------------------------------------------------------------------

    /**
     * Fold {@code (x,y,z)} and {@code travelAxis} into one cache key. {@link BlockPos#asLong} packs the cell
     * into the low ~38+26 bits; the travel axis (0..2) is mixed into a high bit region {@code asLong} doesn't
     * use, so distinct axes for the same cell never collide. (Mirrors MACRO-IMPLEMENTATION §5
     * "{@code key = mix(BlockPos.asLong(x,y,z), travelAxis)}".)
     */
    private static long cacheKey(int x, int y, int z, int travelAxis) {
        // BlockPos.asLong uses 26 bits X + 26 bits Z + 12 bits Y = 64 bits, so there is no spare bit. XOR the
        // axis (multiplied by a large odd constant to spread it) into the packed value: a perturbation of the
        // hashed key, not a packed field. The open-addressing slot compares this exact mixed value, so a hit is
        // only served when the stored key equals this one. Two different (cell,axis) pairs that mix to the same
        // long would alias — astronomically unlikely with the constant below, and even then only a cache
        // aliasing (a stale base served), never an out-of-bounds fault. (If that ever proves measurable, widen
        // to parallel keyCell[]+keyAxis[] arrays; not warranted for v1.)
        return BlockPos.asLong(x, y, z) ^ (travelAxis * 0x9E3779B97F4A7C15L);
    }

    /** Murmur3 64-bit finalizer → cache slot; spreads the structured keys so probe chains stay short. */
    private static int slotFor(long k) {
        k ^= k >>> 33;
        k *= 0xff51afd7ed558ccdL;
        k ^= k >>> 33;
        k *= 0xc4ceb9fe1a85ec53L;
        k ^= k >>> 33;
        return (int) k & MASK;
    }
}
