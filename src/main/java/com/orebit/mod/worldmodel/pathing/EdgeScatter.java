package com.orebit.mod.worldmodel.pathing;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongConsumer;

import com.orebit.mod.worldmodel.navblock.NavBlock;

import net.minecraft.core.BlockPos;

/**
 * <b>The DURABLE cross-chunk (lateral) edge fold for BOTH scatter-owned terms</b>
 * (PERF-DESIGN-navgrid-build §C1 step 3; DESIGN-fluid-flow-prediction.md §4). Steps 1/2 handle the
 * intra-chunk terms: the build SCATTER ({@link NavSectionBuilder#computeDepth}) and the patch re-dilation
 * ({@code NavSectionBuilder.recomputeWindow} via {@link NavFlags#hasFluidNeighborGather} /
 * {@link NavFlags#risksGravityNeighborGather}). Both are <b>lateral-air-optimistic</b> — a source cell on
 * a section's 4 side faces ({@code x==0/15}, {@code z==0/15}) does not scatter into the neighbour CHUNK,
 * and an edge cell does not pick the term up from just across the chunk boundary — because the scatter's
 * {@code x±1}/{@code z±1} reads cross a chunk at a side face and the neighbour may not be built at
 * the time. This class closes that gap in BOTH directions at BOTH sites, on the tick thread.
 *
 * <p><b>Two bits, one face walk</b> — deliberately one class rather than a parallel one: cheaper, and it
 * makes the two dilations impossible to drift apart. The per-source predicates ({@link #crossBitsAt}):
 * <ul>
 *   <li>{@code HAS_FLUID_NEIGHBOR} — the source cell is ANY fluid ({@link NavBlock#isFluid}, water and
 *       lava alike; widened from the lava-only RISKY_EDIT term this class was born folding —
 *       DESIGN-fluid-flow-prediction.md §4.1).</li>
 *   <li>{@code RISKS_GRAVITY} (half B — the cave-in dilation; NavFlags, "RISKS_GRAVITY: two halves, two
 *       owners") — the source cell is a gravity block that is UNSUPPORTED. That reads one row DEEPER than
 *       the fluid predicate (the source's own {@code colY-1}), which costs nothing here: {@link #descAt}
 *       already resolves any {@code colY} across section boundaries inside the column.</li>
 * </ul>
 *
 * <p><b>Only the 4 LATERAL offsets of the 6-neighbour dilations reach this class.</b> The vertical pair
 * ({@code y±1}) never leaves the chunk column — it is entirely {@link NavSectionBuilder}'s business (build
 * scatter through the real above/below grids, patch below-grid read + above-seam window).
 *
 * <h2>Why a plain monotone OR into a live neighbour grid is safe</h2>
 * Build and patch are strictly tick-thread single-writer; the planner pool workers only ever READ
 * ({@code PlanExecutor}: "N workers are N readers"). Both bits are adjacency prefilters,
 * OR-composed from independent contributions (the intra-chunk dilation and this cross-chunk fold — see
 * {@link NavFlags}), so folding the cross-chunk terms into a neighbour's already-built grid with
 * {@link TraversalGrid#orFlags} (navtype untouched, no volatile/epoch/copy) is exactly the pattern
 * {@code NavSectionBuilder.patchCells} already uses to write flags into a live BELOW section that planners
 * read.
 *
 * <h2>Column-Y frame</h2>
 * Both chunks of a shared face are the SAME level's stacked sections — identical section count and identical
 * world-Y bands — so this class works in a chunk-local <i>column-Y</i> frame ({@code colY = sectionIndex*16 +
 * localRow}, {@code 0} = bottom section row 0). Since the dilation is a plain 1-cell lateral step, a fluid
 * cell at {@code colY} marks the cell at the SAME {@code colY} across the face — no row offset at all (the
 * old flowing model's {@code colY-1}/{@code colY-2} rows are gone with it). Column access
 * ({@link #descAt}/{@link #orNeighbor}/{@link #crossBitsAt}) resolves the section by {@code colY >>> 4}, so
 * a face lane is walked as one continuous column regardless of where the section seams fall.
 *
 * <h2>Two hooks</h2>
 * <ul>
 *   <li><b>Build</b> ({@link #reconcileBuild}) — after a chunk's sections are built + stored, scatter each of
 *       its 4 lateral neighbours' face terms into it AND its own into each neighbour (pure additive OR,
 *       since at build the target cells' local flags are already final). A one-time first-build cost absorbed
 *       by the adaptive chunk-build budget; it only fires when a neighbour is already loaded. Bumps each
 *       neighbour actually modified.</li>
 *   <li><b>Patch</b> ({@link #collect} + {@link #reconcile}) — a live edit's authoritative window recompute
 *       ({@code recomputeWindow}) rewrites edge-cell flags from the LOCAL scratch only, dropping the cross-face
 *       terms. After the drain, this re-derives them over the <b>bounded footprint of each edited edge
 *       cell</b> (never the whole face plane): the local face cells the window cleared get their cross terms
 *       re-OR-ed (drain reset them to local-terms-only, so the OR is authoritative), and — only when the
 *       edit sits ON a face, so it could change a cross SOURCE — the neighbour's reading cells are re-derived
 *       authoritatively ({@code compute | local-gathers | cross-gather}). <b>TWO cells, not one</b>: the
 *       neighbour cell at the same {@code colY} reads the edited cell as its lateral {@code N}, while the one
 *       at {@code colY+1} reads it as {@code N}'s SUPPORT — the one structural addition the gravity term
 *       forces on this class (for fluid alone, only {@code colY} mattered). Corner cells (on two faces) check
 *       both neighbours via {@link #crossTerms}. Gated so an interior-only batch does zero work.</li>
 * </ul>
 * The two hooks agree because the build SCATTERs and the patch GATHERs express the same 6-neighbour dilations
 * (the {@code ScatterIdentityTest} identity, extended across the face) — proven by
 * {@code CrossChunkScatterTest}.
 */
final class EdgeScatter {

    private EdgeScatter() {}

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

    /** OR {@code bits} into {@code (x, colY, z)} of {@code col}; returns true iff any of them was newly
     *  set (a real modification — the neighbour-bump signal). No-op (returns false) below the world floor or
     *  above the built column. */
    private static boolean orNeighbor(NavSection[] col, int x, int colY, int z, int bits) {
        if (colY < 0) return false;
        int si = colY >> 4;
        if (si >= col.length) return false;
        NavSection s = col[si];
        if (s == null) return false;
        TraversalGrid g = s.getTraversalGrid();
        int cy = colY & 15;
        int was = g.flags(x, cy, z);
        g.orFlags(x, cy, z, bits);
        return (was & bits) != bits;
    }

    /**
     * The flag bits the SOURCE cell {@code (x, colY, z)} of {@code col} contributes ACROSS a lateral face —
     * the cross-chunk counterpart of the two intra-chunk scatter predicates, evaluated once per source cell:
     * <ul>
     *   <li>{@code HAS_FLUID_NEIGHBOR} iff the cell is any fluid ({@link NavBlock#isFluid}, unconditional —
     *       DESIGN-fluid-flow-prediction.md §4);</li>
     *   <li>{@code RISKS_GRAVITY} iff the cell is a gravity block with nothing solid beneath it (half B).
     *       The lateral offset has NO row change, so the cell it marks sits at the same {@code colY}.</li>
     * </ul>
     * {@code 0} for the overwhelmingly common ordinary cell.
     */
    private static int crossBitsAt(NavSection[] col, int x, int colY, int z) {
        long d = descAt(col, x, colY, z);
        int bits = NavBlock.isFluid(d) ? NavFlags.HAS_FLUID_NEIGHBOR : 0;
        if (NavBlock.hasGravity(d) && NavBlock.isPassable(descAt(col, x, colY - 1, z))) {
            bits |= NavFlags.RISKS_GRAVITY;
        }
        return bits;
    }

    // ==============================================================================================
    //  BUILD hook — pure cross-chunk scatter (additive OR into already-built neighbours)
    // ==============================================================================================

    /**
     * Reconcile chunk {@code (cx,cz)}'s 4 lateral faces against whichever neighbours are currently built:
     * scatter each present neighbour's face TERMS into this chunk AND this chunk's into the neighbour.
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
     * For every CONTRIBUTING cell in {@code srcCol}'s face column ({@code x==srcFixed} for an X-face, else
     * {@code z==srcFixed}), OR its {@link #crossBitsAt} terms into {@code dstCol}'s opposite face at the SAME
     * column row — the lateral step of both 6-neighbour dilations, with no row offset (neither predicate has
     * one) and no carried state. Returns true iff any {@code dstCol} bit was newly set.
     */
    private static boolean scatterFace(NavSection[] srcCol, int srcFixed, NavSection[] dstCol, int dstFixed,
            boolean xAxis, int nSections) {
        boolean mod = false;
        for (int v = 0; v < 16; v++) {
            int sx = xAxis ? srcFixed : v, sz = xAxis ? v : srcFixed;
            int dx = xAxis ? dstFixed : v, dz = xAxis ? v : dstFixed;
            for (int si = 0; si < nSections; si++) {
                if (srcCol[si] == null) continue; // absent source section (a chunk hole): nothing to scatter
                for (int ly = 0; ly < 16; ly++) {
                    int colY = (si << 4) | ly;
                    int bits = crossBitsAt(srcCol, sx, colY, sz);
                    if (bits != 0) mod |= orNeighbor(dstCol, dx, colY, dz, bits);
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
     *       {@code recomputeWindow} rewrote this chunk's face cells in the box {@code (lx±1, colY-3..colY+2,
     *       lz±1)} to its LOCAL terms only, dropping their cross terms. Re-OR the (unchanged) neighbour cross
     *       terms over exactly that box — authoritative because each cell was just reset and cross is
     *       additive. {@link #crossTerms} checks every face a cell lies on, so corner cells keep both
     *       contributions. The {@code +2} row matches {@code recomputeWindow}'s new extent exactly (the
     *       gravity gather's two-deep read — see its doc); the drain's below-seam pass writes
     *       {@code colY-3..colY-1} and its above-seam pass {@code colY+1..colY+2}, both inside the box.</li>
     *   <li><b>Neighbour re-derive</b> (triggers only when the edit sits ON a face — {@code lx==0/15} or
     *       {@code lz==0/15} — so its column's cross terms changed): re-derive the neighbour's reading cells
     *       authoritatively ({@code compute | local-gathers | cross-gather}), covering both an ADD and a
     *       REMOVE of the cross source. <b>TWO cells</b>, {@code colY} and {@code colY+1} — see
     *       {@link #neighbourRederive}. The neighbour saw no edit, so a full re-derive is idempotent for its
     *       unchanged flags.</li>
     * </ul>
     */
    private static void reconcileEdit(ConcurrentHashMap<Long, NavSection[]> chunks, long pos, int minY,
            LongConsumer bump) {
        int x = BlockPos.getX(pos), y = BlockPos.getY(pos), z = BlockPos.getZ(pos);
        int cx = x >> 4, cz = z >> 4, lx = x & 15, lz = z & 15;
        int colY = y - minY;
        NavSection[] gCol = chunks.get(NavStore.key(cx, cz));
        if (gCol == null) return;

        // --- Local re-OR over the drain's cleared box (only face cells carry a cross term; crossTerms
        //     returns 0 for the interior ones, so the whole box is safe to walk). ---
        boolean gMod = false;
        for (int cy = colY - 3; cy <= colY + 2; cy++) {
            for (int dz = lz - 1; dz <= lz + 1; dz++) {
                if (dz < 0 || dz > 15) continue;
                for (int dx = lx - 1; dx <= lx + 1; dx++) {
                    if (dx < 0 || dx > 15) continue;
                    if (dx != 0 && dx != 15 && dz != 0 && dz != 15) continue; // interior cell: no cross term
                    int bits = crossTerms(chunks, cx, cz, dx, cy, dz);
                    if (bits != 0) gMod |= orNeighbor(gCol, dx, cy, dz, bits);
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
     * Authoritatively re-derive the cells of {@code (ncx,ncz)}'s opposite face column that READ the
     * just-edited neighbour source, on the {@code x==fixed}/{@code z==fixed} face plane ({@code lane} = the
     * preserved z/x — the lateral dilation is a plain 1-cell step with no row offset). Bumps the neighbour
     * iff anything changed.
     *
     * <p><b>TWO cells, {@code colY} and {@code colY+1}</b> — the one structural addition the gravity term
     * forces on this class. Verified mechanism: the neighbour's cell at {@code colY} reads the edited cell as
     * its lateral {@code N} (both terms); the neighbour's cell at {@code colY+1} reads the edited cell as
     * <i>its own</i> lateral {@code N}'s SUPPORT, because half B's predicate is
     * {@code hasGravity(N) && isPassable(below(N))}. So an edit that adds or removes the support under a
     * face-adjacent gravel flips the bit one row UP in the neighbour chunk. For the fluid term alone only
     * {@code colY} ever mattered; re-deriving {@code colY+1} is idempotent for it.
     */
    private static void neighbourRederive(ConcurrentHashMap<Long, NavSection[]> chunks, int ncx, int ncz,
            int fixed, int lane, int colY, boolean xAxis, LongConsumer bump) {
        NavSection[] nCol = chunks.get(NavStore.key(ncx, ncz));
        if (nCol == null) return;
        int nx = xAxis ? fixed : lane, nz = xAxis ? lane : fixed;
        boolean mod = rederiveOne(chunks, nCol, ncx, ncz, nx, nz, colY);
        mod |= rederiveOne(chunks, nCol, ncx, ncz, nx, nz, colY + 1);
        if (mod && bump != null) bump.accept(NavStore.key(ncx, ncz));
    }

    /** One cell of {@link #neighbourRederive}'s two-cell footprint: resolve its section, rebuild the scratch
     *  as that section sees it, and re-derive. False when the cell is off the built column. */
    private static boolean rederiveOne(ConcurrentHashMap<Long, NavSection[]> chunks, NavSection[] nCol,
            int ncx, int ncz, int nx, int nz, int colY) {
        if (colY < 0) return false;
        int si = colY >> 4;
        if (si >= nCol.length || nCol[si] == null) return false;
        long[] desc = DESC.get();
        fillScratch(desc, nCol[si].getTraversalGrid(), aboveGrid(nCol, si));
        return rederiveCell(chunks, ncx, ncz, nCol[si].getTraversalGrid(), belowGrid(nCol, si),
                desc, nx, colY & 15, nz, colY);
    }

    private static TraversalGrid aboveGrid(NavSection[] col, int si) {
        return (si + 1 < col.length && col[si + 1] != null) ? col[si + 1].getTraversalGrid() : null;
    }

    private static TraversalGrid belowGrid(NavSection[] col, int si) {
        return (si > 0 && col[si - 1] != null) ? col[si - 1].getTraversalGrid() : null;
    }

    /**
     * Re-derive one face cell's flags authoritatively — <b>the single place the whole composition is
     * written down</b>: {@code compute} (every non-scattered bit, plus {@link NavFlags#RISKS_GRAVITY}'s
     * half A), then the two LOCAL gathers (each with the downward read across a section seam that the
     * upward-only scratch cannot serve), then the CROSS-chunk terms. Returns true iff the stored flags
     * changed. Navtype stays resident.
     *
     * <p>Historical note, because the old wording here was load-bearing and became FALSE: this doc used to
     * claim "{@code compute} supplies the strictly-gravity RISKY_EDIT". Under the 2026-08-21 reframe
     * {@code compute} supplies only half A, so omitting the gravity gather here would silently CLEAR half B
     * on every face cell this method touches.
     */
    private static boolean rederiveCell(ConcurrentHashMap<Long, NavSection[]> chunks, int cx, int cz,
            TraversalGrid grid, TraversalGrid below, long[] desc, int x, int ly, int z, int colY) {
        int before = grid.flags(x, ly, z);
        int flags = NavFlags.compute(desc, x, ly, z);
        if (NavFlags.hasFluidNeighborGather(desc, x, ly, z)
                || (ly == 0 && below != null
                        && NavBlock.isFluid(NavBlock.descriptor((short) below.navtype(x, 15, z))))) {
            flags |= NavFlags.HAS_FLUID_NEIGHBOR;
        }
        if (NavFlags.risksGravityNeighborGather(desc, below, x, ly, z)) flags |= NavFlags.RISKS_GRAVITY;
        flags |= crossTerms(chunks, cx, cz, x, colY, z);
        grid.set(x, ly, z, grid.navtype(x, ly, z), flags);
        return flags != before;
    }

    /**
     * The flag bits a LATERAL-neighbour chunk contributes to the cell at {@code (x, colY, z)} — the OR of
     * {@link #crossBitsAt} over the source cell directly across, at the SAME column row, on whichever of the
     * (up to two, for a corner) chunk-boundary faces this cell touches. The cross-chunk counterpart of the 4
     * lateral offsets of {@link NavFlags#hasFluidNeighborGather} /
     * {@link NavFlags#risksGravityNeighborGather}.
     */
    private static int crossTerms(ConcurrentHashMap<Long, NavSection[]> chunks, int cx, int cz,
            int x, int colY, int z) {
        int bits = 0;
        if (x == 0) {
            NavSection[] w = chunks.get(NavStore.key(cx - 1, cz));
            if (w != null) bits |= crossBitsAt(w, 15, colY, z);
        }
        if (x == 15) {
            NavSection[] e = chunks.get(NavStore.key(cx + 1, cz));
            if (e != null) bits |= crossBitsAt(e, 0, colY, z);
        }
        if (z == 0) {
            NavSection[] n = chunks.get(NavStore.key(cx, cz - 1));
            if (n != null) bits |= crossBitsAt(n, x, colY, 15);
        }
        if (z == 15) {
            NavSection[] s = chunks.get(NavStore.key(cx, cz + 1));
            if (s != null) bits |= crossBitsAt(s, x, colY, 0);
        }
        return bits;
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
