package com.orebit.mod.worldmodel.hpa;

import java.util.concurrent.ConcurrentHashMap;

import com.orebit.mod.OrebitCommon;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.platform.LevelBounds;
import com.orebit.mod.worldmodel.navblock.NavBlock;
import com.orebit.mod.worldmodel.pathing.NavGridView;
import com.orebit.mod.worldmodel.pathing.NavSection;
import com.orebit.mod.worldmodel.pathing.NavStore;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Computes a level-0 leaf node's six face→center traversal costs for the HPA* region tier
 * (PRD §6.3–6.5, §7.1; HPA-IMPLEMENTATION.md §5 "3b leaf cost", §6 "3d defaults").
 *
 * <h2>What a leaf cost is</h2>
 * A level-0 region == one 16³ {@link NavSection}. PRD §6.5 stores, per node, the <b>half-traversal</b>
 * cost from each of the node's six faces to the node center — never an edge. The boundary between two
 * neighbouring leaves is the implicit sum of the two facing halves (face {@code f} of one + face
 * {@code opposite(f)} of the other), so we store the half and the region A* (HPA-IMPLEMENTATION.md §8)
 * sums them at query time. Face cost is the cost <i>from</i> that face <i>to</i> center.
 *
 * <p>Face order is the canonical {@link RegionAddress} order (a {@code byte} 0..5):
 * <pre>0 = -X  1 = +X  2 = -Y  3 = +Y  4 = -Z  5 = +Z</pre>
 *
 * <h2>Algorithm (HPA-IMPLEMENTATION.md §5, pseudocode followed verbatim)</h2>
 * <ol>
 *   <li><b>Resolve the section</b> from {@link NavStore} (the chunk column, then the {@code ry'}-indexed
 *       16³ section). If the chunk/section isn't built, return without marking the node built — the
 *       planner then reads the optimistic admissible default (§6, {@code RegionGrid}).</li>
 *   <li><b>Occupancy scan</b> from the resident nav grid (no live block reads): for each of the 4096
 *       local cells, read its packed navtype → {@link NavBlock} descriptor and tally
 *       {@link NavBlock#isStandable standable} / {@link NavBlock#isPassable passable}. {@code passFrac}
 *       = passable / 4096.</li>
 *   <li><b>Uniform fast-paths</b> (no mini-pathfind): if NO cell is standable, the leaf is either
 *       (nearly) all air → all six faces = {@code quantize(AIR_TRANSIT_TICKS)} (cheap step/fall-through),
 *       or (nearly) all solid → all six faces = {@code quantize(SOLID_MINE_TICKS)} (high but finite — a
 *       leaf is never impassable; INF is reserved for void/out-of-world).</li>
 *   <li><b>Representative FLOOR cells.</b> A FLOOR cell is the block you stand ON; the stand position is
 *       {@code floorCell.above()}. The <i>center rep</i> is the standable floor cell nearest local
 *       (8, *, 8); each <i>face rep</i> is the standable floor cell in the 2-cell band nearest that face,
 *       closest to the face's center. If there is no center rep at all, fall back to the
 *       solidity-interpolated default.</li>
 *   <li><b>Bounded mini-pathfind per face.</b> Build a ONE-SECTION {@link NavGridView} via
 *       {@link NavGridView#overSections} whose only built cells are this section's — the search walls
 *       itself in, so it stays {@code ≪ MAX_EXPANSIONS} (4096 cells) with no change to
 *       {@link BlockPathfinder}. For each face that has a rep, {@link BlockPathfinder#findPath} from the
 *       face rep to the center rep with {@link BotCaps#BREAK_PLACE}; cost = the plan's
 *       {@link BlockPathPlan#cost()}, or a mine-ish fallback if no plan. Faces with no standable cell are
 *       entered by mining/falling: a solidity-scaled mine cost. The block tier's per-search DEBUG/timing
 *       logging is silenced around the (many) leaf searches and restored in {@code finally}.</li>
 * </ol>
 *
 * <h2>House style (HPA-IMPLEMENTATION.md §14)</h2>
 * Static-only utility, mirroring the {@code final}-static-helper idiom of the block tier. The per-call
 * occupancy buffers are flat primitive scratch reused across faces (no per-cell allocation); the
 * one-column section map is a single tiny {@link ConcurrentHashMap} reusing the REAL pooled
 * {@link NavSection} object (never copied). Version-divergent MC bounds go through the
 * {@link LevelBounds} platform seam, never inlined.
 */
public final class LeafCostComputer {

    private LeafCostComputer() {}

    /** Leaf side in blocks (16) — one {@link NavSection} per side. */
    private static final int LEAF = RegionAddress.LEAF_SIZE; // 16
    /** Cells per leaf (16³ = 4096). */
    private static final int CELLS = LEAF * LEAF * LEAF;

    /**
     * Tick stand-in for mining one block of solid material — matches the block tier's break-deterrent
     * scale (HPA-IMPLEMENTATION.md §5). Not a tuned magic number for the final inventory subsystem; a
     * placeholder until physically-derived mining-time costs land.
     */
    public static final float MINE_PER_BLOCK = 3.0f;
    /** Cheap cost to transit an all-air leaf (a step/fall-through across one leaf side). */
    public static final float AIR_TRANSIT_TICKS = LEAF; // 16
    /** Cost to mine straight across a fully-solid leaf (one full side of blocks). Finite, never INF. */
    public static final float SOLID_MINE_TICKS = LEAF * MINE_PER_BLOCK; // 48

    /**
     * Expensive cost for any direction through an all-air leaf that is NOT a free fall — pillaring up (the
     * {@code +Y} EXIT / {@code -Y} ENTER) or crossing floorless air horizontally. ~{@code LEAF} blocks of
     * placement at the block tier's place base cost (~6 ticks/block). This is what makes the region A* treat
     * an air column as a one-way DOWN chute (cheap to fall through, dear to climb/bridge) instead of a cheap
     * up-and-over highway — the fix for the "walk away to ascend into the sky" routing. Tunable.
     */
    public static final float AIR_CLIMB_TICKS = LEAF * 6f; // ~PLACE_BASE_COST per placed block

    /**
     * Symmetric cost to swim across a fully-flooded (all-water) leaf side. Swimming is reversible (up and
     * down both cost effort), so unlike air this is the same in both directions. Seeded at ~the sprint-swim
     * rate (5.612 b/s → ~3.56 ticks/block; a full-water column is ≥2-deep, so the bot sprint-swims it).
     * Tunable.
     */
    public static final float WATER_TRANSIT_TICKS = LEAF * 3.6f; // ~sprint-swim ticks/block × LEAF

    /**
     * Quantized hardness of stone (≈ {@code round(1.5 × 5)}), the reference point at which
     * {@link #solidTunnelTicks} equals {@link #SOLID_MINE_TICKS}. Softer blocks (dirt) tunnel cheaper,
     * harder (obsidian) much dearer.
     */
    private static final float STONE_HARDNESS_REF = 8f;

    /**
     * Occupancy fraction below which a leaf with no standable cell is treated as "all solid" rather than
     * "all air". A leaf with no floor is air iff it is overwhelmingly passable.
     */
    private static final float AIR_PASS_THRESHOLD = 0.5f;

    /**
     * The capability profile the leaf mini-pathfinds run with. <b>Walk-only</b> ({@link BotCaps#DEFAULT}), not
     * break+place. Two reasons, one principled and one measured:
     * <ul>
     *   <li><b>PRD §7.3:</b> the persisted HPA* layer is a TERRAIN BASELINE computed with default capability —
     *       the block tier adjusts for the live bot's real break/place on approach. (This supersedes
     *       HPA-IMPLEMENTATION §5, which specified {@code BREAK_PLACE}; the PRD wins on intent.)</li>
     *   <li><b>Cost:</b> with break+place every cell is reachable by mining/bridging, so the face→center search
     *       weighed walk-vs-mine-vs-bridge everywhere and explored ~700 nodes/leaf on average (max ~3000) —
     *       measured as the dominant first-load tick-stall cost. Walk-only (plus the swim moves, which self-gate
     *       on water, not caps) confines the search to actually-walkable terrain, drops the per-node edit
     *       folding, and yields a more discriminating gradient (open=cheap, barriers→solidity fallback).</li>
     * </ul>
     */
    private static final BotCaps CAPS = BotCaps.DEFAULT;

    // ---- Instrumentation (TEMPORARY — first-load tick-stall diagnosis, PRD §10.B Phase 2 #1) -----------
    // Measures what per-leaf cost actually costs during world-gen: how many leaves are computed, how they
    // split across the uniform-air / uniform-solid / mixed-with-searches / centerless-fallback paths, and
    // for the mixed path the per-face search count, fail (null-plan → fallback) ratio, and expansion totals
    // (read off BlockPathfinder.LAST_EXPANSIONS). A summary is logged every INSTRUMENT_LOG_EVERY computed
    // leaves and reset. Flip INSTRUMENT off (or delete this block) once diagnosed.
    public static boolean INSTRUMENT = true;
    private static final int INSTRUMENT_LOG_EVERY = 256;
    private static int instLeaves, instAir, instWater, instSolid, instMixed, instFallback;
    private static int instSearches, instFails, instMaxExp;
    private static long instExpansions, instNanos;

    private static void instFinish(long t0) {
        if (!INSTRUMENT) return;
        instLeaves++;
        instNanos += System.nanoTime() - t0;
        if (instLeaves % INSTRUMENT_LOG_EVERY == 0) instDump();
    }

    /** Log + reset the accumulated leaf-cost instrumentation (also callable externally, e.g. on shutdown). */
    public static synchronized void instDump() {
        if (instLeaves == 0) return;
        OrebitCommon.LOGGER.info(
                "[Orebit] LEAFCOST {} leaves (air {} / water {} / solid {} / mixed {} / fallback {}) | "
                        + "searches {} (fails {}, {}%) | exp total {} avg {} max {} | {} us/leaf avg",
                instLeaves, instAir, instWater, instSolid, instMixed, instFallback,
                instSearches, instFails, instSearches > 0 ? (100 * instFails / instSearches) : 0,
                instExpansions, instSearches > 0 ? (instExpansions / instSearches) : 0, instMaxExp,
                String.format("%.1f", instNanos / 1000.0 / instLeaves));
        instLeaves = instAir = instWater = instSolid = instMixed = instFallback = 0;
        instSearches = instFails = instMaxExp = 0;
        instExpansions = instNanos = 0;
    }

    // Reusable per-call scratch. A leaf computation is single-threaded (driven by the region planner /
    // maintenance pass on the tick thread); these are wiped at the top of computeLeaf. Flat 1-D buffers
    // indexed by idx(lx,ly,lz) so the occupancy scan and the rep search allocate nothing per cell.
    private static final ThreadLocal<boolean[]> STANDABLE = ThreadLocal.withInitial(() -> new boolean[CELLS]);
    private static final ThreadLocal<boolean[]> PASSABLE  = ThreadLocal.withInitial(() -> new boolean[CELLS]);

    /** Flat cell index for local coords (0..15 each). */
    private static int idx(int lx, int ly, int lz) {
        return (ly << 8) | (lz << 4) | lx;
    }

    /**
     * Compute and store the six face→center buckets (plus {@code built=true}) for the level-0 leaf at
     * region coords {@code (rx, ryLevel0, rz)} in {@code level}'s dimension. If the backing chunk/section
     * isn't currently built, the node is left unbuilt (the planner reads the §6 default instead).
     *
     * @param level     the dimension whose nav grid / bounds back this leaf
     * @param rx        level-0 region X (== chunk-local 16-block column X; world {@code wx >> 4})
     * @param rz        level-0 region Z
     * @param ryLevel0  level-0 vertical region index, measured from the dimension floor
     *                  ({@code (worldY - minY) >> 4})
     * @param pyramid   the per-dimension SoA store to write into
     */
    public static void computeLeaf(ServerLevel level, int rx, int rz, int ryLevel0, CostPyramid pyramid) {
        final int minY = LevelBounds.minY(level);

        // 0) Resolve the section: chunk column from NavStore, then the ry'-indexed 16³ section. NavStore's
        // section array is indexed bottom-up by (worldY - minY)>>4 — exactly ryLevel0 (the level-0 vertical
        // region index uses the same origin and shift). rx/rz ARE the chunk X/Z at level 0 (cell side 16).
        final long chunkKey = NavStore.key(rx, rz);
        final NavSection[] column = NavStore.get(level, chunkKey);
        if (column == null || ryLevel0 < 0 || ryLevel0 >= column.length) {
            return; // not built — planner uses the §6 default; leave the node unbuilt
        }
        final NavSection section = column[ryLevel0];
        if (section == null) {
            return;
        }

        final long instT0 = INSTRUMENT ? System.nanoTime() : 0L; // time only an actually-computed leaf

        // The section's world origin (min corner). Level-0 region coords map straight to it.
        final int ox = rx << 4;            // = rx * LEAF
        final int oz = rz << 4;
        final int oy = minY + (ryLevel0 << 4);

        final int row = pyramid.rowFor(0, rx, ryLevel0, rz);

        // 1) Occupancy scan from the resident grid (no live reads). descriptor = NavBlock table lookup of
        // the cell's resident navtype — the same read NavGridView.descriptorAt does for a built cell.
        final boolean[] standable = STANDABLE.get();
        final boolean[] passable  = PASSABLE.get();
        int standCount = 0;
        int passCount = 0;
        int waterCount = 0;     // passable cells that hold water (to tell an all-WATER column from all-AIR)
        long hardnessSum = 0;   // Σ quantized block hardness (to scale the all-SOLID tunnel cost)
        for (int ly = 0; ly < LEAF; ly++) {
            for (int lz = 0; lz < LEAF; lz++) {
                for (int lx = 0; lx < LEAF; lx++) {
                    long desc = NavBlock.descriptor((short) section.getNavtype(lx, ly, lz));
                    boolean st = NavBlock.isStandable(desc);
                    boolean pa = NavBlock.isPassable(desc);
                    int i = idx(lx, ly, lz);
                    standable[i] = st;
                    passable[i] = pa;
                    if (st) standCount++;
                    if (pa) passCount++;
                    if (NavBlock.fluid(desc) == 1) waterCount++; // FLUID_WATER (geometrically passable cells)
                    hardnessSum += NavBlock.hardness(desc);
                }
            }
        }
        final float passFrac = (float) passCount / CELLS;

        // 2) Uniform fast-paths — skip the per-face mini-pathfind when the leaf is homogeneous.

        // (a) Fully SOLID: not one passable cell, so there is no floor to walk and no air to search — the
        // only route across is to tunnel straight through, ~LEAF blocks of mining scaled by the section's
        // average hardness (dirt cheap, obsidian a near-wall). Symmetric (digging is the same either way) and
        // never INF (HPA §5: everything is mineable). This catches the common underground stone/ore section,
        // which the standCount test below CANNOT — every solid block reads as a "standable" top, so a stone
        // leaf has standCount == 4096 and would otherwise fall into six guaranteed-to-fail walk searches.
        if (passCount == 0) {
            fillAllFaces(pyramid, row, CostCodec.quantize(solidTunnelTicks(hardnessSum)));
            pyramid.setBuilt(0, row, true);
            if (INSTRUMENT) { instSolid++; instFinish(instT0); }
            return;
        }

        // (b) No standable floor but passable throughout → an AIR or WATER column. Water swims (symmetric —
        // sprint-swim up and down both cost effort), so it must NOT use the directional air chute (which would
        // wrongly penalize swimming up). Air is the one-way down chute (fall cheap, pillar/horizontal dear).
        if (standCount == 0) {
            boolean water = waterCount * 2 >= passCount; // mostly water → swim, else air
            if (water) {
                fillAllFaces(pyramid, row, CostCodec.quantize(WATER_TRANSIT_TICKS)); // symmetric swim
            } else {
                fillAirFaces(pyramid, row);                                          // directional air chute
            }
            pyramid.setBuilt(0, row, true);
            if (INSTRUMENT) { if (water) instWater++; else instAir++; instFinish(instT0); }
            return;
        }

        // 3) Representative FLOOR cells. FLOOR cell = the block stood ON; stand pos = floorCell.above().
        // Center rep: standable floor minimizing Manhattan dist to local (8, *, 8), lowest |ly-8| on ties.
        int centerRep = -1;
        int centerBest = Integer.MAX_VALUE;
        // Face reps: best standable floor in the 2-cell band nearest each face, closest to the face center.
        final int[] faceRep = new int[6];
        final int[] faceBest = new int[6];
        for (int f = 0; f < 6; f++) { faceRep[f] = -1; faceBest[f] = Integer.MAX_VALUE; }

        for (int ly = 0; ly < LEAF; ly++) {
            for (int lz = 0; lz < LEAF; lz++) {
                for (int lx = 0; lx < LEAF; lx++) {
                    int i = idx(lx, ly, lz);
                    if (!standable[i]) continue;

                    // --- center rep ---
                    int dCenter = Math.abs(lx - 8) + Math.abs(ly - 8) + Math.abs(lz - 8);
                    if (dCenter < centerBest
                            || (dCenter == centerBest && centerRep != -1
                                && Math.abs(ly - 8) < Math.abs(localY(centerRep) - 8))) {
                        centerBest = dCenter;
                        centerRep = i;
                    }

                    // --- face reps (2-cell band nearest each face) ---
                    // -X (face 0): lx in {0,1}; closeness to (·, 8, 8). +X (face 1): lx in {14,15}.
                    if (lx <= 1) considerFace(faceRep, faceBest, 0, i, distFaceX(ly, lz));
                    if (lx >= LEAF - 2) considerFace(faceRep, faceBest, 1, i, distFaceX(ly, lz));
                    // -Y (face 2): ly in {0,1}; closeness to (8, ·, 8). +Y (face 3): ly in {14,15}.
                    if (ly <= 1) considerFace(faceRep, faceBest, 2, i, distFaceY(lx, lz));
                    if (ly >= LEAF - 2) considerFace(faceRep, faceBest, 3, i, distFaceY(lx, lz));
                    // -Z (face 4): lz in {0,1}; closeness to (8, 8, ·). +Z (face 5): lz in {14,15}.
                    if (lz <= 1) considerFace(faceRep, faceBest, 4, i, distFaceZ(lx, ly));
                    if (lz >= LEAF - 2) considerFace(faceRep, faceBest, 5, i, distFaceZ(lx, ly));
                }
            }
        }

        if (centerRep == -1) {
            // No center rep despite some standable cells (shouldn't normally happen, but be safe): fall to
            // the mixed-solidity default — interpolate by how open the leaf is.
            int bucket = CostCodec.quantize(mixedDefaultTicks(passFrac));
            fillAllFaces(pyramid, row, bucket);
            pyramid.setBuilt(0, row, true);
            if (INSTRUMENT) { instFallback++; instFinish(instT0); }
            return;
        }

        // 4) Bounded mini-pathfind per face over a ONE-SECTION view. Reuse the REAL section object in a
        // 1-column map; the column has only this one vertical slot, so the search walls itself into 16³.
        final ConcurrentHashMap<Long, NavSection[]> oneCol = new ConcurrentHashMap<>(2);
        final NavSection[] columnView = new NavSection[ryLevel0 + 1];
        columnView[ryLevel0] = section; // lower slots null → outside-section probes report unbuilt
        oneCol.put(chunkKey, columnView);
        final NavGridView boundedGrid = NavGridView.overSections(minY, oneCol);

        // Stand position for the center rep (floorCell.above()), in world coords.
        final BlockPos centerStand = standWorld(ox, oy, oz, centerRep);

        final boolean saveDbg = BlockPathfinder.DEBUG;
        final boolean saveTim = BlockPathfinder.LOG_TIMING;
        BlockPathfinder.DEBUG = false;
        BlockPathfinder.LOG_TIMING = false;
        try {
            for (int f = 0; f < 6; f++) {
                float ticks;
                if (faceRep[f] != -1) {
                    BlockPos faceStand = standWorld(ox, oy, oz, faceRep[f]);
                    BlockPathPlan plan = BlockPathfinder.findPath(boundedGrid, faceStand, centerStand, CAPS);
                    if (INSTRUMENT) {
                        instSearches++;
                        int exp = BlockPathfinder.LAST_EXPANSIONS;
                        instExpansions += exp;
                        if (exp > instMaxExp) instMaxExp = exp;
                        if (plan == null) instFails++;
                    }
                    // Fallback when the bounded search finds no plan: a mine-ish estimate scaled by solidity.
                    ticks = (plan != null) ? plan.cost()
                            : (SOLID_MINE_TICKS * (1f - passFrac) + AIR_TRANSIT_TICKS);
                } else {
                    // No standable cell on that face → enter by mining/falling (solidity-scaled).
                    ticks = SOLID_MINE_TICKS * (1f - passFrac) + AIR_TRANSIT_TICKS;
                }
                // Walk is reversible, so enter and exit cost the same — set both directions together.
                pyramid.setFaceBoth(0, row, f, CostCodec.quantize(ticks));
            }
        } finally {
            BlockPathfinder.DEBUG = saveDbg;
            BlockPathfinder.LOG_TIMING = saveTim;
        }
        pyramid.setBuilt(0, row, true);
        if (INSTRUMENT) { instMixed++; instFinish(instT0); }
    }

    // ---------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------

    /** A mixed (partly-solid) leaf's per-face default: lerp between air-cheap and solid-expensive. */
    private static float mixedDefaultTicks(float passFrac) {
        return AIR_TRANSIT_TICKS * passFrac + SOLID_MINE_TICKS * (1f - passFrac);
    }

    /**
     * Tunnel-straight-through cost (ticks) for a fully-solid leaf: ~{@code LEAF} blocks of mining scaled by
     * the section's AVERAGE block hardness, so an obsidian wall costs far more than a dirt bank. Quantized
     * hardness is {@code round(destroyTime × 5)}; at stone ({@link #STONE_HARDNESS_REF}) this equals
     * {@link #SOLID_MINE_TICKS}. High-but-finite for bedrock-grade hardness (the {@link CostCodec} bucket
     * lattice caps it below INF — never impassable, HPA §5).
     */
    private static float solidTunnelTicks(long hardnessSum) {
        float avgHardness = (float) hardnessSum / CELLS;
        return LEAF * MINE_PER_BLOCK * (avgHardness / STONE_HARDNESS_REF);
    }

    /** Set all six faces of a row to one bucket, BOTH directions (the symmetric uniform/solid/default case). */
    private static void fillAllFaces(CostPyramid pyramid, int row, int bucket) {
        for (int f = 0; f < 6; f++) pyramid.setFaceBoth(0, row, f, bucket);
    }

    /**
     * Fill an all-air leaf's faces <b>directionally</b>: an air column is a one-way DOWN chute. The only cheap
     * motions are falling IN through the top ({@code +Y} ENTER) and falling OUT through the bottom ({@code -Y}
     * EXIT); every other direction — pillaring up ({@code +Y} EXIT / {@code -Y} ENTER) or crossing floorless
     * air horizontally — needs placed blocks and is set to {@link #AIR_CLIMB_TICKS}. This asymmetry is what a
     * single per-face scalar could not express, and is the fix for the region A* flying up-and-over through
     * cheap air. Face order: {@code 2 = -Y}, {@code 3 = +Y} (see {@link RegionAddress}).
     */
    private static void fillAirFaces(CostPyramid pyramid, int row) {
        int cheap = CostCodec.quantize(AIR_TRANSIT_TICKS);
        int dear = CostCodec.quantize(AIR_CLIMB_TICKS);
        for (int f = 0; f < 6; f++) pyramid.setFaceBoth(0, row, f, dear); // default every direction: dear
        pyramid.setFaceBucket(0, row, 3, CostPyramid.ENTER, cheap); // +Y enter: fall in through the top
        pyramid.setFaceBucket(0, row, 2, CostPyramid.EXIT, cheap);  // -Y exit:  fall out through the bottom
    }

    /** Keep the closest standable floor cell for a face. */
    private static void considerFace(int[] faceRep, int[] faceBest, int f, int i, int dist) {
        if (dist < faceBest[f]) {
            faceBest[f] = dist;
            faceRep[f] = i;
        }
    }

    // Face-center proximity metrics. On a ±X face the center point is (·, 8, 8) → measure (ly,lz) gap; the
    // x-depth within the 2-cell band is ignored (any cell in the band is "on" the face). Symmetric for ±Y/±Z.
    private static int distFaceX(int ly, int lz) { return Math.abs(ly - 8) + Math.abs(lz - 8); }
    private static int distFaceY(int lx, int lz) { return Math.abs(lx - 8) + Math.abs(lz - 8); }
    private static int distFaceZ(int lx, int ly) { return Math.abs(lx - 8) + Math.abs(ly - 8); }

    /** Local Y of a flat cell index. */
    private static int localY(int i) { return (i >> 8) & 15; }
    /** Local Z of a flat cell index. */
    private static int localZ(int i) { return (i >> 4) & 15; }
    /** Local X of a flat cell index. */
    private static int localX(int i) { return i & 15; }

    /**
     * World stand position for a flat FLOOR cell index: the section origin plus the local floor coords,
     * raised one block ({@code floorCell.above()}) — the block the bot's feet occupy when standing on it.
     */
    private static BlockPos standWorld(int ox, int oy, int oz, int cellIdx) {
        return new BlockPos(ox + localX(cellIdx), oy + localY(cellIdx) + 1, oz + localZ(cellIdx));
    }
}
