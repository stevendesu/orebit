package com.orebit.mod.worldmodel.pathing;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongConsumer;

import com.orebit.mod.worldmodel.navblock.NavBlock;

import net.minecraft.core.BlockPos;

/**
 * <b>Step 3 of the flowing-fluid RISKY_EDIT arc — the DURABLE cross-chunk (lateral) edge fold</b>
 * (PERF-DESIGN-navgrid-build §C1). Steps 1/2 handle the intra-chunk fluid RISKY_EDIT: the build SCATTER
 * ({@link NavSectionBuilder#computeDepth}) and the patch re-dilation ({@code NavSectionBuilder.recomputeWindow}
 * via {@link NavFlags#risksFluidFlow}). Both are <b>lateral-air-optimistic</b> — a flowing source on a
 * section's 4 side faces ({@code x==0/15}, {@code z==0/15}) does not scatter into the neighbour CHUNK, and an
 * edge cell does not pick up RISKY_EDIT from a flowing source just across the chunk boundary — because the
 * scatter's {@code x±1}/{@code z±1} reads cross a chunk at a side face and the neighbour may not be built at
 * the time. This class closes that gap in BOTH directions at BOTH sites, on the tick thread.
 *
 * <h2>Why a plain monotone OR into a live neighbour grid is safe</h2>
 * Build and patch are strictly tick-thread single-writer; the planner pool workers only ever READ
 * ({@code PlanExecutor}: "N workers are N readers"). RISKY_EDIT is a break/place hazard prefilter, OR-composed
 * from independent terms (gravity, intra-fluid, cross-fluid — see {@link NavFlags}), so folding the
 * cross-chunk term into a neighbour's already-built grid with {@link TraversalGrid#orFlags} (navtype
 * untouched, no volatile/epoch/copy) is exactly the pattern {@code NavSectionBuilder.patchCells} already uses
 * to write flags into a live BELOW section that planners read.
 *
 * <h2>Column-Y frame</h2>
 * Both chunks of a shared face are the SAME level's stacked sections — identical section count and identical
 * world-Y bands — so this class works in a chunk-local <i>column-Y</i> frame ({@code colY = sectionIndex*16 +
 * localRow}, {@code 0} = bottom section row 0). A flowing source at {@code colY} endangers the floor cells one
 * and two rows below it ({@code colY-1}, {@code colY-2}) in its horizontal neighbour — here the neighbour that
 * lives across the chunk face. Column access ({@link #descAt}/{@link #orRisky}/{@link #flowingAt}) resolves the
 * section by {@code colY >>> 4}, so the vertical section seam is crossed for free (a source at a section's
 * bottom row scatters DOWN into the section below, exactly as the intra-chunk
 * {@code NavSectionBuilder.markRiskyRow} does).
 *
 * <h2>Two hooks</h2>
 * <ul>
 *   <li><b>Build</b> ({@link #reconcileBuild}) — after a chunk's sections are built + stored, scatter each of
 *       its 4 lateral neighbours' flowing sources into it AND its own into each neighbour (pure additive OR,
 *       since at build the target cells' local flags are already final). A one-time first-build cost absorbed
 *       by the adaptive chunk-build budget; it only fires when a neighbour is already loaded. Bumps each
 *       neighbour actually modified.</li>
 *   <li><b>Patch</b> ({@link #collect} + {@link #reconcile}) — a live edit's authoritative window recompute
 *       ({@code recomputeWindow}) rewrites edge-cell flags from the LOCAL scratch only, dropping the cross-face
 *       fluid term. After the drain, this re-derives it over the <b>bounded footprint of each edited edge
 *       cell</b> (never the whole face plane): the local face cells the window cleared get their cross term
 *       re-OR-ed (drain reset them to {@code gravity|local}, so the OR is authoritative), and — only when the
 *       edit sits ON a face, so it could change a cross SOURCE — the neighbour's ≤3 reading cells are
 *       re-derived authoritatively ({@code compute | local-gather | cross-gather}). Corner cells (on two
 *       faces) check both neighbours via {@link #crossRisky}. Gated so an interior-only batch does zero work.</li>
 * </ul>
 * The two hooks agree because the build SCATTER and the patch GATHER express the same dilation of the flowing
 * set (the {@code FluidScatterIdentityTest} identity, extended across the face) — proven by
 * {@code CrossChunkFluidScatterTest}.
 */
final class EdgeFluidScatter {

    private EdgeFluidScatter() {}

    private static final long AIR_DESC = NavBlock.descriptor(NavBlock.AIR);

    // Per-thread descriptor scratch for the patch-path neighbour re-derive (overscan-carrying, the layout
    // NavFlags.compute expects). Server-thread only, but ThreadLocal for the same "safe to run on a worker
    // later" posture as the classify kernels.
    private static final ThreadLocal<long[]> DESC = ThreadLocal.withInitial(() -> new long[NavFlags.SCRATCH_SIZE]);

    // ---- Column access (column-Y frame; null / OOB section resolves to air, matching the intra-chunk
    //      scatter's air-optimism at the world floor / a chunk hole) --------------------------------------

    /** The packed descriptor at column position {@code (x, colY, z)} of {@code col}, or air when the section
     *  is absent / out of range. */
    private static long descAt(NavSection[] col, int x, int colY, int z) {
        if (colY < 0) return AIR_DESC;
        int si = colY >> 4;
        if (si >= col.length) return AIR_DESC;
        NavSection s = col[si];
        if (s == null) return AIR_DESC;
        return NavBlock.descriptor((short) s.getTraversalGrid().navtype(x, colY & 15, z));
    }

    /** OR RISKY_EDIT into {@code (x, colY, z)} of {@code col}; returns true iff the bit was newly set (a real
     *  modification — the neighbour-bump signal). No-op (returns false) below the world floor or above the
     *  built column. */
    private static boolean orRisky(NavSection[] col, int x, int colY, int z) {
        if (colY < 0) return false;
        int si = colY >> 4;
        if (si >= col.length) return false;
        NavSection s = col[si];
        if (s == null) return false;
        TraversalGrid g = s.getTraversalGrid();
        int cy = colY & 15;
        boolean was = (g.flags(x, cy, z) & NavFlags.RISKY_EDIT) != 0;
        g.orFlags(x, cy, z, NavFlags.RISKY_EDIT);
        return !was;
    }

    /** Whether {@code col} holds a FLOWING fluid source at {@code (x, colY, z)} — a fluid whose own cell-below
     *  is dry (the exact {@code NavBlock.fluid(d)!=0 && fluidBelow==0} test the intra-chunk scatter uses). */
    private static boolean flowingAt(NavSection[] col, int x, int colY, int z) {
        if (NavBlock.fluid(descAt(col, x, colY, z)) == 0) return false;
        return NavBlock.fluid(descAt(col, x, colY - 1, z)) == 0;
    }

    // ==============================================================================================
    //  BUILD hook — pure cross-chunk scatter (additive OR into already-built neighbours)
    // ==============================================================================================

    /**
     * Reconcile chunk {@code (cx,cz)}'s 4 lateral faces against whichever neighbours are currently built:
     * scatter each present neighbour's flowing sources into this chunk AND this chunk's into the neighbour.
     * {@code bump} is invoked with the {@link NavStore#key packed key} of every NEIGHBOUR whose grid this
     * actually changed (this chunk's own bump is the caller's — a fresh build already bumps it). Called on the
     * tick thread right after {@code NavStore.put} for the chunk.
     */
    static void reconcileBuild(ConcurrentHashMap<Long, NavSection[]> chunks, int cx, int cz, LongConsumer bump) {
        if (chunks == null) return;
        NavSection[] gCol = chunks.get(NavStore.key(cx, cz));
        if (gCol == null) return;
        reconcileBuildDir(chunks, gCol, cx + 1, cz, 15, 0, true, bump);  // EAST  (G x=15 ↔ E x=0)
        reconcileBuildDir(chunks, gCol, cx - 1, cz, 0, 15, true, bump);  // WEST  (G x=0  ↔ W x=15)
        reconcileBuildDir(chunks, gCol, cx, cz + 1, 15, 0, false, bump); // SOUTH (G z=15 ↔ S z=0)
        reconcileBuildDir(chunks, gCol, cx, cz - 1, 0, 15, false, bump); // NORTH (G z=0  ↔ N z=15)
    }

    private static void reconcileBuildDir(ConcurrentHashMap<Long, NavSection[]> chunks, NavSection[] gCol,
            int ncx, int ncz, int gFixed, int nFixed, boolean xAxis, LongConsumer bump) {
        NavSection[] nCol = chunks.get(NavStore.key(ncx, ncz));
        if (nCol == null) return;
        int nSections = Math.min(gCol.length, nCol.length);
        // Apply both directions; only the NEIGHBOUR needs an explicit bump (the caller bumps this chunk).
        scatterFace(nCol, nFixed, gCol, gFixed, xAxis, nSections);              // N sources -> G edge
        boolean nMod = scatterFace(gCol, gFixed, nCol, nFixed, xAxis, nSections); // G sources -> N edge
        if (nMod) bump.accept(NavStore.key(ncx, ncz));
    }

    /**
     * For every flowing source in {@code srcCol}'s face column ({@code x==srcFixed} for an X-face, else
     * {@code z==srcFixed}), OR RISKY_EDIT into {@code dstCol}'s opposite face at column-rows {@code colY-1}
     * and {@code colY-2}. Ascending sweep per lane with a continuous {@code belowFluid} carry — the same
     * flowing detection as {@link NavSectionBuilder#computeDepth}'s fold. Returns true iff any {@code dstCol}
     * bit was newly set.
     */
    private static boolean scatterFace(NavSection[] srcCol, int srcFixed, NavSection[] dstCol, int dstFixed,
            boolean xAxis, int nSections) {
        boolean mod = false;
        for (int v = 0; v < 16; v++) {
            int sx = xAxis ? srcFixed : v, sz = xAxis ? v : srcFixed;
            int dx = xAxis ? dstFixed : v, dz = xAxis ? v : dstFixed;
            boolean belowFluid = false; // colY = -1 is air (nothing below the world bottom is fluid)
            for (int si = 0; si < nSections; si++) {
                NavSection s = srcCol[si];
                if (s == null) {
                    belowFluid = false; // absent source section (a chunk hole): carry resets to dry
                    continue;
                }
                TraversalGrid g = s.getTraversalGrid();
                for (int ly = 0; ly < 16; ly++) {
                    int colY = (si << 4) | ly;
                    boolean fluidHere = NavBlock.fluid(NavBlock.descriptor((short) g.navtype(sx, ly, sz))) != 0;
                    boolean flowing = fluidHere && !belowFluid;
                    belowFluid = fluidHere;
                    if (flowing) {
                        mod |= orRisky(dstCol, dx, colY - 1, dz);
                        mod |= orRisky(dstCol, dx, colY - 2, dz);
                    }
                }
            }
        }
        return mod;
    }

    // ==============================================================================================
    //  PATCH hook — snapshot the edited face cells before the drain, reconcile bounded footprints after
    // ==============================================================================================

    // Tick-thread scratch: the world positions of drained cells sitting on / within window-reach of a lateral
    // face, captured pre-drain (the drain clears the queue). Reused across flushes (server-thread confined).
    private static long[] editScratch = new long[64];
    private static int editCount;

    /**
     * Snapshot every pending cell that sits on — or whose {@code recomputeWindow} footprint reaches — a lateral
     * face, BEFORE the drain clears the queue. The gate ({@code lx<=1 || lx>=14 || lz<=1 || lz>=14}) is exactly
     * "the authoritative window writes an edge cell", so an interior-only batch snapshots nothing and
     * {@link #reconcile} is a no-op.
     */
    static void collect(PendingPatches queue, int minY) {
        editCount = 0;
        int n = queue.count();
        for (int i = 0; i < n; i++) {
            long pos = queue.keyAt(i);
            int lx = BlockPos.getX(pos) & 15, lz = BlockPos.getZ(pos) & 15;
            if (lx > 1 && lx < 14 && lz > 1 && lz < 14) continue; // interior — the no-edge-touch gate
            if (editCount == editScratch.length) editScratch = Arrays.copyOf(editScratch, editCount << 1);
            editScratch[editCount++] = pos;
        }
    }

    /**
     * Reconcile the bounded lateral-edge footprint of each snapshotted edit (call AFTER the drain). Clears the
     * snapshot on exit. {@code bump} is invoked with the packed key of every chunk actually modified.
     */
    static void reconcile(ConcurrentHashMap<Long, NavSection[]> chunks, int minY, LongConsumer bump) {
        if (chunks != null) {
            for (int i = 0; i < editCount; i++) {
                reconcileEdit(chunks, editScratch[i], minY, bump);
            }
        }
        editCount = 0;
    }

    /**
     * Reconcile one edited edge cell's bounded footprint. Two disjoint, cheap pieces:
     * <ul>
     *   <li><b>Local re-OR</b> (triggers whenever a face is within window-reach): the drain's
     *       {@code recomputeWindow} rewrote this chunk's face cells in the box {@code (lx±1, colY-3..colY+1,
     *       lz±1)} to {@code gravity|local}, dropping their cross term. Re-OR the (unchanged) neighbour cross
     *       term over exactly that box — authoritative because each cell was just reset and cross is additive.
     *       {@link #crossRisky} checks every face a cell lies on, so corner cells keep both contributions.</li>
     *   <li><b>Neighbour re-derive</b> (triggers only when the edit sits ON a face — {@code lx==0/15} or
     *       {@code lz==0/15} — so its column's fluid changed): the neighbour's ≤3 reading cells ({@code colY-2
     *       ..colY}, same lane) are re-derived authoritatively ({@code compute | local-gather | cross-gather}),
     *       covering both an ADD and a REMOVE of the cross source. The neighbour saw no edit, so a full
     *       re-derive is idempotent for its unchanged non-fluid flags.</li>
     * </ul>
     */
    private static void reconcileEdit(ConcurrentHashMap<Long, NavSection[]> chunks, long pos, int minY,
            LongConsumer bump) {
        int x = BlockPos.getX(pos), y = BlockPos.getY(pos), z = BlockPos.getZ(pos);
        int cx = x >> 4, cz = z >> 4, lx = x & 15, lz = z & 15;
        int colY = y - minY;
        NavSection[] gCol = chunks.get(NavStore.key(cx, cz));
        if (gCol == null) return;

        // --- Local re-OR over the drain's cleared box (only face cells carry a cross term; crossRisky
        //     returns false for the interior ones, so the whole box is safe to walk). ---
        boolean gMod = false;
        for (int cy = colY - 3; cy <= colY + 1; cy++) {
            for (int dz = lz - 1; dz <= lz + 1; dz++) {
                if (dz < 0 || dz > 15) continue;
                for (int dx = lx - 1; dx <= lx + 1; dx++) {
                    if (dx < 0 || dx > 15) continue;
                    if (dx != 0 && dx != 15 && dz != 0 && dz != 15) continue; // interior cell: no cross term
                    if (crossRisky(chunks, cx, cz, dx, cy, dz)) gMod |= orRisky(gCol, dx, cy, dz);
                }
            }
        }
        if (gMod && bump != null) bump.accept(NavStore.key(cx, cz));

        // --- Neighbour re-derive: only when the edited cell is ON a face (its column's fluid changed). ---
        if (lx == 0)  neighbourRederive(chunks, cx - 1, cz, 15, lz, colY, true, bump);
        if (lx == 15) neighbourRederive(chunks, cx + 1, cz, 0, lz, colY, true, bump);
        if (lz == 0)  neighbourRederive(chunks, cx, cz - 1, lx, 15, colY, false, bump);
        if (lz == 15) neighbourRederive(chunks, cx, cz + 1, lx, 0, colY, false, bump);
    }

    /**
     * Authoritatively re-derive the ≤3 cells of {@code (ncx,ncz)}'s opposite face column that read the just-
     * edited neighbour source ({@code colY-2..colY}, on the {@code x==fixed}/{@code z==fixed} face plane,
     * {@code lane} = the preserved z/x). Fills the (up to two) touched sections' scratches once each. Bumps the
     * neighbour iff a cell changed.
     */
    private static void neighbourRederive(ConcurrentHashMap<Long, NavSection[]> chunks, int ncx, int ncz,
            int fixed, int lane, int colY, boolean xAxis, LongConsumer bump) {
        NavSection[] nCol = chunks.get(NavStore.key(ncx, ncz));
        if (nCol == null) return;
        long[] desc = DESC.get();
        int filledSi = -1;
        boolean mod = false;
        for (int cy = colY - 2; cy <= colY; cy++) {
            if (cy < 0) continue;
            int si = cy >> 4;
            if (si >= nCol.length || nCol[si] == null) continue;
            if (si != filledSi) {
                fillScratch(desc, nCol[si].getTraversalGrid(), aboveGrid(nCol, si));
                filledSi = si;
            }
            int nx = xAxis ? fixed : lane, nz = xAxis ? lane : fixed;
            mod |= rederiveCell(chunks, ncx, ncz, nCol[si].getTraversalGrid(), desc, nx, cy & 15, nz, cy);
        }
        if (mod && bump != null) bump.accept(NavStore.key(ncx, ncz));
    }

    private static TraversalGrid aboveGrid(NavSection[] col, int si) {
        return (si + 1 < col.length && col[si + 1] != null) ? col[si + 1].getTraversalGrid() : null;
    }

    /**
     * Re-derive one face cell's flags authoritatively: the local {@code recomputeWindow} body ({@code compute}
     * + the retained local fluid gather) then OR the cross-chunk fluid term. Returns true iff the stored flags
     * changed. Navtype stays resident.
     */
    private static boolean rederiveCell(ConcurrentHashMap<Long, NavSection[]> chunks, int cx, int cz,
            TraversalGrid grid, long[] desc, int x, int ly, int z, int colY) {
        int before = grid.flags(x, ly, z);
        int flags = NavFlags.compute(desc, x, ly, z);
        if (NavFlags.risksFluidFlow(desc, x, ly + 1, z) || NavFlags.risksFluidFlow(desc, x, ly + 2, z)) {
            flags |= NavFlags.RISKY_EDIT;
        }
        if (crossRisky(chunks, cx, cz, x, colY, z)) flags |= NavFlags.RISKY_EDIT;
        grid.set(x, ly, z, grid.navtype(x, ly, z), flags);
        return flags != before;
    }

    /**
     * Whether a flowing source in a LATERAL-neighbour chunk endangers the floor cell at {@code (x, colY, z)} —
     * a neighbour flowing source one or two rows above it, on whichever of the (up to two, for a corner)
     * chunk-boundary faces this cell touches. The cross-chunk counterpart of {@link NavFlags#risksFluidFlow}'s
     * {@code y+1}/{@code y+2} gather.
     */
    private static boolean crossRisky(ConcurrentHashMap<Long, NavSection[]> chunks, int cx, int cz,
            int x, int colY, int z) {
        if (x == 0) {
            NavSection[] w = chunks.get(NavStore.key(cx - 1, cz));
            if (w != null && (flowingAt(w, 15, colY + 1, z) || flowingAt(w, 15, colY + 2, z))) return true;
        }
        if (x == 15) {
            NavSection[] e = chunks.get(NavStore.key(cx + 1, cz));
            if (e != null && (flowingAt(e, 0, colY + 1, z) || flowingAt(e, 0, colY + 2, z))) return true;
        }
        if (z == 0) {
            NavSection[] n = chunks.get(NavStore.key(cx, cz - 1));
            if (n != null && (flowingAt(n, x, colY + 1, 15) || flowingAt(n, x, colY + 2, 15))) return true;
        }
        if (z == 15) {
            NavSection[] s = chunks.get(NavStore.key(cx, cz + 1));
            if (s != null && (flowingAt(s, x, colY + 1, 0) || flowingAt(s, x, colY + 2, 0))) return true;
        }
        return false;
    }

    /**
     * Rebuild an overscan-carrying descriptor scratch from a grid's resident navtypes plus the section above's
     * bottom {@link NavFlags#OVERSCAN_ROWS} rows (air when {@code above} is null) — the exact layout
     * {@code NavSectionBuilder.fillScratch} / {@link NavFlags#compute} expect. Replicated here (like the
     * identity tests') so the patch reconcile needs no new surface on {@code NavSectionBuilder}.
     */
    private static void fillScratch(long[] desc, TraversalGrid grid, TraversalGrid above) {
        short[] raw = grid.raw();
        for (int i = 0; i < 4096; i++) {
            desc[i] = NavBlock.descriptor((short) (raw[i] & TraversalGrid.NAVTYPE_MASK));
        }
        if (above == null) {
            Arrays.fill(desc, 4096, NavFlags.SCRATCH_SIZE, AIR_DESC);
        } else {
            short[] araw = above.raw();
            for (int i = 0; i < NavFlags.OVERSCAN_ROWS * 256; i++) {
                desc[4096 + i] = NavBlock.descriptor((short) (araw[i] & TraversalGrid.NAVTYPE_MASK));
            }
        }
    }
}
