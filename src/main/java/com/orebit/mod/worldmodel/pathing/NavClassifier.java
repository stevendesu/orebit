package com.orebit.mod.worldmodel.pathing;

import com.orebit.mod.worldmodel.navblock.NavBlock;

/**
 * Classifies a section cell into a coarse 2-bit {@link TraversalClass}, reading only packed
 * {@link NavBlock} descriptors (the {@code long[]} scratch) — no world access, no hardcoded
 * {@code Blocks.X}. Replaces the drifted {@code TraversalAnalyzer} / {@code TraversalAnalyzerMutable}
 * pair (one used a {@code block.toString().contains("CONCRETE_POWDER")} hack and a short-circuiting
 * fluid loop; the other a {@code Set<Block>}). All facts now come from the descriptor bit-fields.
 *
 * <h2>Cell convention</h2>
 * The class at grid cell {@code (x,y,z)} describes <b>standing on the block at that cell</b>: the
 * cell itself is the floor; {@code (x,y+1,z)} and {@code (x,y+2,z)} are the body space. This matches
 * the prior analyzer so nothing downstream changes meaning.
 *
 * <h2>Index order (the reconciliation)</h2>
 * The scratch uses the canonical {@link TraversalGrid} / Minecraft section order
 * {@code (y<<8)|(z<<4)|x} (x fastest). The old mutable analyzer read its scratch as
 * {@code y + z*16 + x*256} (y fastest); this class fixes that so the classifier and the grid it
 * writes into agree on layout.
 *
 * <h2>Coarse, not final</h2>
 * The 2-bit grid is a fast pruning hint. Fine movement decisions (stair half-steps, bridging,
 * swim, hazard cost under a configurable health setting) are made later in the movement/cost layer,
 * which reads the descriptors directly. So fluids and hazards here are treated conservatively
 * (BLOCKED) rather than modelled as traversable; that is intentional for the baseline grid.
 */
public final class NavClassifier {

    private NavClassifier() {}

    /** destroyTime ≥ 1.0 in quantized units (round(destroyTime*5)): the "non-trivial to break" cutoff. */
    private static final int HEADROOM_HARDNESS = 5;
    /** collision top ≥ 0.5 in 1/16ths: the "can stand on it" cutoff. */
    private static final int STANDABLE_TOP_Y = 8;

    private static final long AIR_DESC = NavBlock.descriptor(NavBlock.AIR);

    // Horizontal neighbours (x,z) for fluid-flow risk.
    private static final int[][] HORIZONTAL = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
    // All six neighbours (x,y,z) for "is there a face to place against".
    private static final int[][] SIX = {{-1, 0, 0}, {1, 0, 0}, {0, -1, 0}, {0, 1, 0}, {0, 0, -1}, {0, 0, 1}};

    /**
     * Classify one cell. {@code desc} is a section's 4096 packed descriptors in canonical order;
     * out-of-section reads resolve to air (the same edge behaviour as the prior analyzer — a Step-3
     * refinement will overscan into adjacent sections).
     */
    public static TraversalClass classify(long[] desc, int x, int y, int z) {
        long ground = at(desc, x, y, z);
        long a1 = at(desc, x, y + 1, z);
        long a2 = at(desc, x, y + 2, z);

        // No headroom and the blocks in the way are non-trivial to break.
        // (Unbreakable blocks pack hardness 255, so a bedrock ceiling is correctly BLOCKED here —
        // the old code summed defaultDestroyTime, where bedrock's -1 sentinel could slip through.)
        if (NavBlock.hardness(a1) + NavBlock.hardness(a2) >= HEADROOM_HARDNESS) return TraversalClass.BLOCKED;

        // Disturbing a gravity block (above us, or one we'd undercut) risks a cascade onto the path.
        if (NavBlock.hasGravity(a2)) return TraversalClass.BLOCKED;
        if (NavBlock.hasGravity(ground) && unsupported(desc, x, y, z)) return TraversalClass.BLOCKED;
        if (NavBlock.hasGravity(a1) && unsupported(desc, x, y + 1, z)) return TraversalClass.BLOCKED;

        // Breaking near an unsupported fluid would let it flow into the body space.
        if (risksFluidFlow(desc, x, y + 1, z) || risksFluidFlow(desc, x, y + 2, z)) return TraversalClass.BLOCKED;

        // A damaging block as the floor (lava/fire/magma/cactus/campfire) — avoid.
        if (NavBlock.isDamaging(ground)) return TraversalClass.BLOCKED;

        // A clearable hazard in the body space (e.g. fire) — passable but costly.
        if (NavBlock.isDamaging(a1) || NavBlock.isDamaging(a2)) return TraversalClass.SLOW;

        // The body space (feet a1 / head a2) holds a SOLID block — soft, since a hard one already
        // returned BLOCKED via the headroom-hardness test. You cannot stand here without mining it, so
        // it is EASY (breakable), never CLEAR — matching TraversalClass's own contract ("CLEAR = solid
        // ground with 2+ AIR above"; "EASY = breakable blocks"). This is the "2-high soft wall reads as
        // a step" bug: ground solid + a1 solid + a2 air slipped past the hardness sum (dirt 3 + air 0 <
        // 5) and fell through to CLEAR, so a non-breaking follower kept bouncing with its head in the
        // block. A CLEAR-only pathfinder now excludes it; a future breaking-capable bot can use EASY.
        if (!NavBlock.isPassable(a1) || !NavBlock.isPassable(a2)) return TraversalClass.EASY;

        // No floor: traversable only by bridging (a replaceable cell with a face to place against).
        if (isEmptyAir(ground)) {
            if (NavBlock.isReplaceable(ground) && hasPlaceableNeighbor(desc, x, y, z)) return TraversalClass.EASY;
            return TraversalClass.SLOW; // open fall
        }

        // Slow/slippery surface (ice, slime, soul sand, honey).
        if (NavBlock.surface(ground) != 0) return TraversalClass.SLOW;

        // Solid base with clear headroom — the ideal case.
        if (NavBlock.topY(ground) >= STANDABLE_TOP_Y) return TraversalClass.CLEAR;

        // Everything else (fluids as floor, sub-half partials we can't stand on, …).
        return TraversalClass.BLOCKED;
    }

    private static long at(long[] desc, int x, int y, int z) {
        if (x < 0 || y < 0 || z < 0 || x >= 16 || y >= 16 || z >= 16) return AIR_DESC;
        return desc[(y << 8) | (z << 4) | x];
    }

    /** Nothing solid directly below — a gravity block here/above would fall. */
    private static boolean unsupported(long[] desc, int x, int y, int z) {
        return NavBlock.isPassable(at(desc, x, y - 1, z));
    }

    /** Truly empty space (no collision and no fluid) — distinct from a fluid cell. */
    private static boolean isEmptyAir(long d) {
        return NavBlock.isPassable(d) && NavBlock.fluid(d) == 0;
    }

    /** A horizontal neighbour holds a fluid that is not already draining straight down. */
    private static boolean risksFluidFlow(long[] desc, int x, int y, int z) {
        for (int[] o : HORIZONTAL) {
            int nx = x + o[0], nz = z + o[1];
            if (NavBlock.fluid(at(desc, nx, y, nz)) != 0
                    && NavBlock.fluid(at(desc, nx, y - 1, nz)) == 0) {
                return true;
            }
        }
        return false;
    }

    /** Any of the six neighbours offers a solid (non-fluid) face to place a bridging block against. */
    private static boolean hasPlaceableNeighbor(long[] desc, int x, int y, int z) {
        for (int[] o : SIX) {
            long n = at(desc, x + o[0], y + o[1], z + o[2]);
            if (!NavBlock.isPassable(n) && NavBlock.fluid(n) == 0) return true;
        }
        return false;
    }
}
