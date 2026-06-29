package com.orebit.mod.worldmodel.hpa;

/**
 * Addressing math for the HPA* region tier (PRD §6.3–6.5; HPA-IMPLEMENTATION.md §2, "3a addressing").
 *
 * <p>The region tier is a <b>fixed cubic-grid implicit octree</b> (not the superseded semantic
 * Region/Portal model — PRD §6.3). A node is a tuple {@code (level, rx, ry, rz)} of region coordinates at
 * that level. Level 0 is a single 16³ {@link com.orebit.mod.worldmodel.pathing.NavSection NavSection}
 * (one leaf). Each ascending level doubles the cell side: cell side at {@code level} is
 * {@code 1 << (LEAF_BITS + level)} blocks. Levels {@code 0..OCTREE_TOP} subdivide vertically as well as
 * horizontally (octree, 8 children); above {@code OCTREE_TOP} the padded vertical extent
 * ({@link #PAD_HEIGHT} = 512 blocks) is a single slab and the tree degenerates to a quadtree (4 children,
 * {@code ry} pinned to 0). At {@code OCTREE_TOP == 5} the cell already spans the full padded height
 * ({@code 16 << 5 == 512 == PAD_HEIGHT}), so the octree→quadtree transition is seamless.
 *
 * <p>This class is <b>pure static math — no objects, no allocation, no MC API</b> (the world's live
 * {@code minY} is passed in by callers, sourced from {@link com.orebit.mod.platform.LevelBounds}). House
 * style: a node is never materialized as an object; the {@code CostPyramid} stores parallel
 * {@code int[] rx/ry/rz} and a per-level open-addressed {@code long}→row map keyed by {@link #packLevelKey}.
 *
 * <p><b>Vertical origin.</b> The vertical region index {@code ry} is measured from the dimension floor
 * {@code minY} (overworld −64). Callers pass the live {@code minY}; this class never reads world bounds.
 *
 * <p><b>Face order — canonical, used everywhere</b> (a {@code byte} 0..5):
 * <pre>
 *   0 = -X (WEST)   1 = +X (EAST)   2 = -Y (DOWN)   3 = +Y (UP)   4 = -Z (NORTH)   5 = +Z (SOUTH)
 * </pre>
 * {@link #opposite(int)} is {@code face ^ 1}; {@link #neighborRX(int,int)} / {@link #neighborRY(int,int)} /
 * {@link #neighborRZ(int,int)} step one region cell along a face.
 */
public final class RegionAddress {

    /** 16³ leaf = 2^4 blocks per side. */
    public static final int LEAF_BITS = 4;
    /** Leaf cell side in blocks (16). */
    public static final int LEAF_SIZE = 1 << LEAF_BITS; // 16

    /** Padded vertical extent = 512 = 2^9 (the quadtree slab height above the transition). */
    public static final int PAD_HEIGHT_BITS = 9;
    /** Padded vertical extent in blocks (512). */
    public static final int PAD_HEIGHT = 1 << PAD_HEIGHT_BITS; // 512

    /**
     * Levels {@code 0..OCTREE_TOP} are octree (cell side 16..512); at level {@code OCTREE_TOP} the cell
     * spans the full padded height, so {@code level >= OCTREE_TOP} is a single vertical cell (quadtree).
     */
    public static final int OCTREE_TOP = 5;

    /** Dimension root (~6 octree + ~17 quadtree levels); coords/levels are clamped here. */
    public static final int MAX_LEVEL = 22;

    /**
     * Highest level the <b>fragment pyramid</b> is rolled up to / the region A* will plan at (HPA-FRAGMENTS.md
     * §S5). There is deliberately <b>no single world-root node</b>: once a level's cap-safe search reaches any
     * practical goal, higher levels are dead weight (storage + merge work) — a level-{@value} quadtree cell is
     * {@code 16<<6 = 1024} blocks, and a cap-safe ~45-cell search there spans ~46k blocks; goals beyond that are
     * handled by clamping the top-level search goal toward them and re-planning on approach, never by a taller
     * pyramid. Caps {@code mergeUpFragments} and the planner's level selection.
     */
    public static final int MAX_COARSE_LEVEL = 6;

    /**
     * The number of distinct vertical region indices ({@code ry}) at {@code level}, from the padded dimension
     * extent ({@link #PAD_HEIGHT} = 512 blocks ⇒ 32 leaf sections): {@code max(1, 32 >> level)} —
     * {@code 32,16,8,4,2,1,1,…}, pinning to 1 at/above {@link #OCTREE_TOP} (the quadtree slab). Used by the
     * planner to size a cap-safe search box per level (a search's worst-case node count scales with the
     * horizontal area × this vertical depth). Conservative (the padded 32 ≥ any real dimension's section count),
     * so the cap-safety bound holds in every dimension.
     */
    public static int verticalRegions(int level) {
        int v = (PAD_HEIGHT >> LEAF_BITS) >> level; // (512/16) >> level = 32 >> level
        return v < 1 ? 1 : v;
    }

    private RegionAddress() {}

    // ---------------------------------------------------------------------------------------------------
    // World block → region coords at a level
    // ---------------------------------------------------------------------------------------------------

    /** Cell-side shift at {@code level}: cell side = {@code 1 << shift(level)} blocks. */
    public static int shift(int level) {
        return LEAF_BITS + level;
    }

    /** Cell side in blocks at {@code level} ({@code 1 << shift}). */
    public static int sideOf(int level) {
        return 1 << (LEAF_BITS + level);
    }

    /** World block X → region X at {@code level} (arithmetic shift, handles negatives). */
    public static int regionX(int wx, int level) {
        return wx >> (LEAF_BITS + level);
    }

    /** World block Z → region Z at {@code level}. */
    public static int regionZ(int wz, int level) {
        return wz >> (LEAF_BITS + level);
    }

    /**
     * World block Y → region Y at {@code level}, measured from the dimension floor {@code minY}.
     * At/above {@link #OCTREE_TOP} the vertical extent is one slab, so {@code ry} is pinned to 0.
     */
    public static int regionY(int wy, int level, int minY) {
        if (level >= OCTREE_TOP) return 0;
        return (wy - minY) >> (LEAF_BITS + level);
    }

    // ---------------------------------------------------------------------------------------------------
    // Parent / child
    // ---------------------------------------------------------------------------------------------------

    /** Parent region X (one level coarser). */
    public static int parentRX(int rx) {
        return rx >> 1;
    }

    /** Parent region Z (one level coarser). */
    public static int parentRZ(int rz) {
        return rz >> 1;
    }

    /**
     * Parent region Y when going from {@code level} to {@code level+1}. Halves {@code ry} only while the
     * <i>parent</i> stays within the octree ({@code level+1 <= OCTREE_TOP}); at/above the transition the
     * parent is a single vertical cell so {@code ry} stays 0.
     */
    public static int parentRY(int ry, int level) {
        if (level + 1 <= OCTREE_TOP) return ry >> 1;
        return 0;
    }

    /** Number of children of a node at {@code level}: 8 below the octree→quadtree transition, else 4. */
    public static int childCount(int level) {
        // Children live at level-1. They are octree (8) iff the child level is still within the octree,
        // i.e. (level-1) < OCTREE_TOP. At/above the transition the parent and children are single vertical
        // cells (quadtree, 4).
        return (level <= OCTREE_TOP) ? 8 : 4;
    }

    /**
     * The {@code i}-th child's region X at level {@code level-1}, where {@code i} is in {@code 0..childCount-1}.
     * Child index bit layout: bit0 = X, bit1 = Z, bit2 = Y (Y only meaningful in the octree).
     */
    public static int childRX(int rx, int i) {
        return (rx << 1) | (i & 1);
    }

    /** The {@code i}-th child's region Z at level {@code level-1}. */
    public static int childRZ(int rz, int i) {
        return (rz << 1) | ((i >> 1) & 1);
    }

    /**
     * The {@code i}-th child's region Y at level {@code level-1}. In the octree (child level
     * {@code level-1 < OCTREE_TOP}) the Y bit splits; otherwise (the child is a single vertical slab) Y is 0.
     */
    public static int childRY(int ry, int i, int level) {
        if (level - 1 < OCTREE_TOP) return (ry << 1) | ((i >> 2) & 1);
        return 0;
    }

    // ---------------------------------------------------------------------------------------------------
    // Node center in world coords
    // ---------------------------------------------------------------------------------------------------

    /** World-X center of node {@code (level, rx, ...)}. */
    public static int centerX(int level, int rx) {
        int shift = LEAF_BITS + level;
        int side = 1 << shift;
        return (rx << shift) + side / 2;
    }

    /** World-Z center of node {@code (level, ..., rz)}. */
    public static int centerZ(int level, int rz) {
        int shift = LEAF_BITS + level;
        int side = 1 << shift;
        return (rz << shift) + side / 2;
    }

    /**
     * World-Y center of node {@code (level, ry)}. At/above {@link #OCTREE_TOP} the cell is the padded slab,
     * so the center is {@code minY + PAD_HEIGHT/2}; below the transition it is the {@code ry}-indexed cell's
     * center measured from {@code minY}.
     */
    public static int centerY(int level, int ry, int minY) {
        int shift = LEAF_BITS + level;
        int side = 1 << shift;
        return (level >= OCTREE_TOP) ? (minY + PAD_HEIGHT / 2) : (minY + (ry << shift) + side / 2);
    }

    // ---------------------------------------------------------------------------------------------------
    // Per-level packed key (the CostPyramid map key)
    // ---------------------------------------------------------------------------------------------------

    /**
     * Pack region coords into one {@code long} key for the per-level open-addressed map. Layout:
     * <pre>22 bits rx | 22 bits rz | 6 bits ry</pre>
     * Each level has its own map, so keys are only ever compared for <b>equality</b> — masking without
     * sign-extension is fine: at level 0 {@code |rx| ≤ 30_000_000>>4 ≈ 1.875M} fits in 22 signed bits and
     * {@code ry ≤ 32} fits in 6 bits, so two distinct coords never collide within a level.
     */
    public static long packLevelKey(int rx, int ry, int rz) {
        return ((rx & 0x3FFFFFL) << 28) | ((rz & 0x3FFFFFL) << 6) | (ry & 0x3FL);
    }

    /** Recover {@code rx} from a packed key, sign-extending the 22-bit field. */
    public static int unpackRX(long key) {
        return signExtend((int) ((key >>> 28) & 0x3FFFFFL), 22);
    }

    /** Recover {@code rz} from a packed key, sign-extending the 22-bit field. */
    public static int unpackRZ(long key) {
        return signExtend((int) ((key >>> 6) & 0x3FFFFFL), 22);
    }

    /** Recover {@code ry} from a packed key, sign-extending the 6-bit field. */
    public static int unpackRY(long key) {
        return signExtend((int) (key & 0x3FL), 6);
    }

    /** Sign-extend the low {@code bits} of {@code v} to a full {@code int}. */
    private static int signExtend(int v, int bits) {
        int shift = 32 - bits;
        return (v << shift) >> shift;
    }

    // ---------------------------------------------------------------------------------------------------
    // Faces
    // ---------------------------------------------------------------------------------------------------

    /** The opposite face of {@code face} ({@code face ^ 1}): -X↔+X, -Y↔+Y, -Z↔+Z. */
    public static int opposite(int face) {
        return face ^ 1;
    }

    /** Region X of the neighbor across {@code face} (delta only on the ±X faces 0/1). */
    public static int neighborRX(int rx, int face) {
        if (face == 0) return rx - 1;
        if (face == 1) return rx + 1;
        return rx;
    }

    /** Region Y of the neighbor across {@code face} (delta only on the ±Y faces 2/3). */
    public static int neighborRY(int ry, int face) {
        if (face == 2) return ry - 1;
        if (face == 3) return ry + 1;
        return ry;
    }

    /** Region Z of the neighbor across {@code face} (delta only on the ±Z faces 4/5). */
    public static int neighborRZ(int rz, int face) {
        if (face == 4) return rz - 1;
        if (face == 5) return rz + 1;
        return rz;
    }
}
