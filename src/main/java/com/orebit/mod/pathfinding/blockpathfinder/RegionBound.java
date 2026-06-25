package com.orebit.mod.pathfinding.blockpathfinder;

/**
 * A world-space axis-aligned box that confines a {@link BlockPathfinder} search to a <b>corridor</b>
 * (HPA-IMPLEMENTATION.md §9 "corridor bound"; PRD §7.1). This is the spatial bound that makes the
 * sliding-window block tier actually <i>windowed</i> — without it, a window's block-A* runs over the whole
 * loaded grid and a heuristic-underestimating goal (the canonical "pillar straight up in open air") floods
 * horizontally to the expansion cap exactly as a flat search does, because a nearer target does not fix an
 * under-estimate (PRD §7.4; the tower is a heuristic problem, not a distance problem).
 *
 * <h2>What it bounds</h2>
 * The region tier produces a skeleton of regions to pass through; the driver
 * ({@link com.orebit.mod.pathfinding.PathPlan}) turns the current window's skeleton regions into this box
 * (their union, expanded by a one-region margin), and the block-A* rejects any candidate cell outside it.
 * A candidate outside the box is simply never relaxed — the search can explore freely <i>inside</i> the
 * corridor (so it still finds the real, optimal micro-path over live geometry, including a beneficial
 * one-region dip into a neighbour the coarse face-to-center cost couldn't see) but can never wander
 * arbitrarily far off the skeleton (so the pillar's horizontal flood is capped and the search is forced
 * to ascend). The one-region margin is the knob that keeps the beneficial dip while forbidding 2+-region
 * wandering.
 *
 * <h2>Why a single box per window (not the exact region union)</h2>
 * A window is only ~3 skeleton regions (~48 blocks); a single AABB enclosing them plus the margin is the
 * cheapest possible per-candidate test (six int compares, no allocation, no boxing — house style,
 * HPA-IMPLEMENTATION.md §14) and is at worst slightly permissive where a short skeleton bends (harmless —
 * it only admits a few extra in-window regions, the search stays bounded). For the straight pillar the box
 * is exactly the 3×3-region column. The bound is rebuilt per replan as the window slides forward.
 *
 * <h2>Pure world coordinates (layering)</h2>
 * Deliberately holds only block-space {@code min/max} — it imports nothing from {@code worldmodel.hpa}, so
 * the block tier stays independent of the region tier. {@link com.orebit.mod.pathfinding.PathPlan} (which
 * spans both) maps skeleton regions to this box.
 */
public final class RegionBound {

    private final int minX, maxX, minY, maxY, minZ, maxZ;

    /** A box spanning the inclusive block range {@code [min..max]} on each axis. */
    public RegionBound(int minX, int maxX, int minY, int maxY, int minZ, int maxZ) {
        this.minX = minX;
        this.maxX = maxX;
        this.minY = minY;
        this.maxY = maxY;
        this.minZ = minZ;
        this.maxZ = maxZ;
    }

    /** Whether world cell {@code (x,y,z)} is inside the corridor (six int compares; no allocation). */
    public boolean allows(int x, int y, int z) {
        return x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    /**
     * A copy widened by {@code blocks} on every horizontal side and {@code vblocks} vertically — the
     * "widen-on-failure" retry the driver uses when a bounded search comes back empty (a too-tight corridor),
     * before giving up to a BLOCKED status.
     */
    public RegionBound widened(int blocks, int vblocks) {
        return new RegionBound(minX - blocks, maxX + blocks, minY - vblocks, maxY + vblocks,
                minZ - blocks, maxZ + blocks);
    }
}
