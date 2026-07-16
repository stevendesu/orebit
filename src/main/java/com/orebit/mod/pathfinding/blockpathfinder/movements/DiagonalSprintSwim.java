package com.orebit.mod.pathfinding.blockpathfinder.movements;

import com.orebit.mod.pathfinding.blockpathfinder.CandidateSink;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;

/**
 * Diagonal sprint-swim — prone {@link SprintSwim} to a diagonally-adjacent water cell, the water analogue of
 * how ground {@link Diagonal} relates to {@link Traverse} and {@link DiagonalParkour} to {@link Parkour}. It
 * turns underwater traversal from 6-connected (the {@link SprintSwim} faces: ±X, ±Y, ±Z) into fully
 * <b>26-connected</b>: a face-only swim grid must zig-zag (Manhattan) to cover diagonal distance, expanding
 * ~2× the nodes and producing staircased routes, whereas a diagonal swim step covers one cell in each of two
 * (edge) or three (corner) axes for a cost of {@code √2} / {@code √3} — matching the 3-D octile heuristic (see
 * {@link com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder#heuristic}).
 *
 * <h2>The 26 swim directions (this move owns 20 of them)</h2>
 * A cube cell has 26 neighbours: 6 faces + 12 edges + 8 corners. {@link SprintSwim} (the base) emits the 6
 * <b>faces</b> (±X, ±Y, ±Z). This move emits the 20 remaining <b>multi-axis</b> steps:
 * <ul>
 *   <li><b>4 same-Y horizontal diagonals</b> (edges, {@code ±X±Z}) — pass 1.</li>
 *   <li><b>8 vertical-diagonal edges</b> (a cardinal combined with {@code ±Y}: {@code ±X±Y}, {@code ±Z±Y}) —
 *       pass 2.</li>
 *   <li><b>8 corners</b> (a horizontal diagonal combined with {@code ±Y}: {@code ±X±Y±Z}) — pass 2.</li>
 * </ul>
 * All 20 follow with the identical direction-agnostic swim servo (see below), so — exactly as in pass 1 — this
 * move differs from its base ONLY in the PLANNING geometry ({@link #candidates}) and the per-step {@link #COST}
 * / {@link #CORNER_COST}. Overriding only {@code candidates()} keeps every swim move's execution byte-identical
 * and impossible to drift apart.
 *
 * <h2>Why subclass {@link SprintSwim}</h2>
 * The follower side of a sprint-swim is <b>direction-agnostic and composes in 3-D</b>: {@link SprintSwim#reached},
 * {@link SprintSwim#steer}, and {@link SprintSwim#plan} all servo generically toward the next planned node.
 * Horizontal (X/Z) is driven by {@code SteerControl.computeGeom}'s pursuit projection <i>in the X-Z plane</i>
 * (independent of the Y component), while vertical (Y) is driven by {@code SteerControl.holdDepth} (a bang-bang
 * jump/sink autopilot keyed ONLY on {@code p.ty()} vs the bot's {@code y}, independent of the horizontal) plus
 * the depth PITCH folded into {@code swimServo}/{@code swimPitched}. A combined vertical-diagonal or corner
 * target is therefore just the composition of two already-working independent controllers — the same pair
 * {@link SprintSwim}'s own up/down (pure-Y) and horizontal (pure-XZ) candidates already exercise — and
 * {@code Swim.reachedSwim} gates arrival on all three axes. That is exactly the reuse condition the code
 * convention picks a subclass for — unlike {@link DiagonalParkour}, which is standalone because {@link Parkour}'s
 * execution (a direction-specific takeoff trigger) is NOT reusable.
 *
 * <h2>Per-axis clearance (Minecraft's collision, never a swept diagonal)</h2>
 * Minecraft resolves collision <b>per axis</b>, in the order Y→X→Z — never a single swept diagonal — so a step
 * that changes N axes is legal ONLY if EVERY axis-orthogonal sub-cell the 0.6-wide/0.6-tall prone hitbox sweeps
 * is clear (here: swimmable water). Concretely, require water at the destination feet AND at the feet cell of
 * <b>every non-empty subset of the moving axes</b>:
 * <ul>
 *   <li><b>Edge</b> (2 moving axes {A,B}): the two single-axis intermediates {A}, {B}, plus the destination
 *       {A,B}. For a same-Y horizontal diagonal these are the two orthogonal corner feet — the exact pass-1 /
 *       {@link Diagonal} "both corners, never one open side" rule. For a vertical-diagonal (say {@code +X+Y})
 *       they are the horizontal-only feet {@code (x+1,y+1,z)} and the up-only feet {@code (x,y+2,z)} —
 *       precisely {@link SprintSwim}'s own cardinal-feet and up-feet checks, composed.</li>
 *   <li><b>Corner</b> (3 moving axes {X,Y,Z}): 3 single-axis + 3 pair-axis intermediates + the destination = 7
 *       feet cells. E.g. {@code +X+Y+Z} requires water at {@code (x+1,y+1,z)}, {@code (x,y+2,z)},
 *       {@code (x,y+1,z+1)} (singles), {@code (x+1,y+2,z)}, {@code (x+1,y+1,z+1)}, {@code (x,y+2,z+1)} (pairs),
 *       and {@code (x+1,y+2,z+1)} (destination). Any one non-water sub-cell clips the hitbox and the step is
 *       rejected — vanilla can neither squeeze diagonally through a solid edge/corner nor swim through it dry.</li>
 * </ul>
 * The subset enumeration ({@code candidates()} loop over subset masks 1..6) generalises pass-1's hand-written
 * "both corners" to any axis count with no per-offset special-casing.
 *
 * <p><b>No head clearance.</b> Sprint-swim keeps the prone 0.6-tall pose, so — exactly like {@link SprintSwim}'s
 * candidates — only the FEET-layer cell of each swept column matters; the cell above (2-deep / a 1-tall gap /
 * air) is never consulted. Every checked sub-cell above lies at a feet layer ({@code y+1} shifted by the
 * subset's Y delta), never a head layer.
 *
 * <h2>Sub-cells must be swimmable WATER (v1 design decision — owner may relax)</h2>
 * Each swept sub-cell is required to be swimmable {@link MovementContext#water water} — the SAME predicate
 * {@link SprintSwim} uses for its destinations — not merely {@code passable}. Rationale: this keeps the WHOLE
 * diagonal transit inside water so swim physics apply throughout; routing a corner of the arc through an air
 * pocket risks the bot dropping out of the prone swim pose mid-step (a breach degrades sprint-swim to the slow
 * {@link Swim}). This is a conservative v1 choice — a later increment may admit a passable (air / 1-tall gap)
 * sub-cell once pose retention across a dry corner is verified in-game, exactly as {@link SprintSwim}'s own
 * solid-head continuation rule was staged.
 *
 * <h2>Cost — √2 · {@link SprintSwim#COST} (edges) / √3 · {@link SprintSwim#COST} (corners)</h2>
 * Horizontal WASD is normalized, so swimming a diagonal is NOT faster than a cardinal step — the bot still
 * covers √2 (edge) or √3 (corner) blocks of water at the same 5.612 b/s. So an edge costs {@link SprintSwim#COST}
 * · √2 (≈ {@code 3.56 · 1.414 ≈ 5.04} ticks) and a corner {@link SprintSwim#COST} · √3 (≈ {@code 3.56 · 1.732 ≈
 * 6.17} ticks), both derived from the base constant so they re-scale automatically — the exact parallel of
 * {@link Diagonal#COST} = {@link Traverse#FLAT_COST} · √2. Like {@link SprintSwim} (and unlike ground
 * {@link Diagonal}), the base swim move prices a FLAT per-step cost with no transit/hazard/slow surcharges, so
 * this move mirrors that: a flat √2/√3-scaled cost, no per-corner surcharge. The 3-D octile heuristic uses the
 * same √2 edge and √3 corner terms, so a clean diagonal swim is heuristic-exact.
 */
public final class DiagonalSprintSwim extends SprintSwim {

    /**
     * Diagonal sprint-swim EDGE cost per step (ticks) = {@link SprintSwim#COST} · √2 — one cell in each of two
     * axes covers √2 blocks of water at the same normalized swim speed (≈ {@code 3.56 · 1.414 ≈ 5.04}). Covers
     * the same-Y horizontal diagonals AND the vertical-diagonals (both are 2-axis edges). Derived from the base
     * rate so it tracks any future re-measure of {@link SprintSwim#COST}; matches the {@link Diagonal#COST} =
     * {@link Traverse#FLAT_COST} · √2 parallel and the octile √2 edge term.
     */
    public static final float COST = SprintSwim.COST * 1.41421356f;

    /**
     * Diagonal sprint-swim CORNER cost per step (ticks) = {@link SprintSwim#COST} · √3 — one cell in each of the
     * three axes covers √3 blocks of water at the same normalized swim speed (≈ {@code 3.56 · 1.732 ≈ 6.17}).
     * Derived from the base rate like {@link #COST}; matches the 3-D octile √3 corner term.
     */
    public static final float CORNER_COST = SprintSwim.COST * 1.73205081f;

    /**
     * The 20 multi-axis sprint-swim offsets, each {@code {dx, dy, dz, movingMask}} — the 4 same-Y horizontal
     * diagonals, the 8 vertical-diagonal edges, and the 8 corners. {@code movingMask} is the bit set of axes
     * this offset changes (bit 0 = X, bit 1 = Y, bit 2 = Z), precomputed so the clearance loop needs no runtime
     * axis test. The 6 faces are {@link SprintSwim}'s job (registered as a separate movement), so they are
     * absent here.
     */
    private static final int[][] MOVES = {
            // 4 same-Y horizontal diagonals (edges, X+Z; movingMask = 1|4 = 5) — √2
            {  1, 0,  1, 5 }, {  1, 0, -1, 5 }, { -1, 0,  1, 5 }, { -1, 0, -1, 5 },
            // 8 vertical-diagonal edges: a cardinal + ±Y — √2
            {  1,  1, 0, 3 }, {  1, -1, 0, 3 }, { -1,  1, 0, 3 }, { -1, -1, 0, 3 },   // ±X + ±Y (X|Y = 3)
            {  0,  1,  1, 6 }, {  0, -1,  1, 6 }, {  0,  1, -1, 6 }, {  0, -1, -1, 6 }, // ±Z + ±Y (Z|Y = 6)
            // 8 corners: a horizontal diagonal + ±Y (X|Y|Z = 7) — √3
            {  1,  1,  1, 7 }, {  1, -1,  1, 7 }, {  1,  1, -1, 7 }, {  1, -1, -1, 7 },
            { -1,  1,  1, 7 }, { -1, -1,  1, 7 }, { -1,  1, -1, 7 }, { -1, -1, -1, 7 },
    };

    /** Per-offset cost parallel to {@link #MOVES}: {@link #COST} (√2) for the 12 edges, {@link #CORNER_COST}
     *  (√3) for the 8 corners. */
    private static final float[] MOVE_COST = {
            COST, COST, COST, COST,
            COST, COST, COST, COST,
            COST, COST, COST, COST,
            CORNER_COST, CORNER_COST, CORNER_COST, CORNER_COST,
            CORNER_COST, CORNER_COST, CORNER_COST, CORNER_COST,
    };

    /**
     * Emit the 20 multi-axis sprint-swim edges (4 horizontal diagonals + 8 vertical-diagonal edges + 8 corners).
     * Self-gates on {@link MovementContext#MODE_PRONE} and a feet-water source exactly like {@link SprintSwim}
     * (a PRONE node is in water by construction; the feet read is defensive). For each offset the destination
     * feet is fast-rejected first, then every proper non-empty subset of the moving axes has its feet cell tested
     * for swimmable water — the per-axis clearance rule (see the class doc): an edge checks its 2 single-axis
     * intermediates, a corner its 3 single-axis + 3 pair-axis intermediates. Any non-water swept cell clips the
     * prone hitbox (Minecraft never sweeps a diagonal) and the step is dropped. Flat √2 (edge) / √3 (corner)
     * cost, no surcharge. Zero heap allocation: static offset/cost tables, no {@code new} in the loops.
     */
    @Override
    public void candidates(MovementContext ctx, int x, int y, int z, CandidateSink out) {
        if (ctx.mode() != MovementContext.MODE_PRONE) return;
        int fy = y + 1; // feet layer of the source node
        // A PRONE node is in water by construction; guard the feet-water read defensively (SprintSwim's gate).
        if (!ctx.built(x, fy, z) || !ctx.water(x, fy, z)) return;

        for (int i = 0; i < MOVES.length; i++) {
            int[] m = MOVES[i];
            int dx = m[0], dy = m[1], dz = m[2], moving = m[3];

            // Destination feet (the full moving set): swimmable water. Fast-reject before the swept-cell loop.
            int nfy = fy + dy;
            if (!ctx.built(x + dx, nfy, z + dz) || !ctx.water(x + dx, nfy, z + dz)) continue;

            // Per-axis clearance: every PROPER non-empty subset of the moving axes must be swimmable water too,
            // or the 0.6-wide prone hitbox clips a solid edge/corner (Minecraft resolves collision per-axis,
            // never a swept diagonal). Water (not merely passable) keeps the whole transit submerged — the v1
            // sub-cell-water decision. Masks 1..6 enumerate the subsets; skip the full set (destination, done
            // above) and any mask that selects a non-moving axis.
            boolean clear = true;
            for (int s = 1; s < 7; s++) {
                if (s == moving || (s & ~moving) != 0) continue;
                int cx = x + ((s & 1) != 0 ? dx : 0);
                int cy = fy + ((s & 2) != 0 ? dy : 0);
                int cz = z + ((s & 4) != 0 ? dz : 0);
                if (!ctx.built(cx, cy, cz) || !ctx.water(cx, cy, cz)) { clear = false; break; }
            }
            if (clear) out.accept(x + dx, y + dy, z + dz, MOVE_COST[i]);
        }
    }
}
