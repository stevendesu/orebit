package com.orebit.mod.pathfinding.blockpathfinder.movements;

import com.orebit.mod.pathfinding.blockpathfinder.BotSteering;
import com.orebit.mod.pathfinding.blockpathfinder.CandidateSink;
import com.orebit.mod.pathfinding.blockpathfinder.Movement;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;

/**
 * Ordinary (non-sprint) swimming — Minecraft's <b>Swim</b> (MOVEMENT-DESIGN.md, Tier 1 water). The slow
 * "hold space and paddle" traversal: <b>2.2 blocks/s</b> at the surface (head in open air), dropping to
 * <b>1.97 blocks/s</b> fully submerged — both well under the 4.317 b/s walk. It is the move that
 * <b>un-walls water</b>: before it, {@link MovementContext#passable} rejected every water cell, so a river
 * was an impassable wall the bot bridged over (or, in the HPA* leaf-cost mini-search, a flooded/failed
 * search that inflated the region cost).
 *
 * <p><b>When it's chosen.</b> Normal swim is the slow fallback you take when you can't {@link SprintSwim}:
 * forced onto the surface, or shallow water where a sprint-swim was never initiated. Nothing physically
 * prevents slow swimming while submerged either — A* simply prefers the cheaper {@link SprintSwim} wherever
 * it is available, so this v1 emits only the <b>surface</b> case (feet in water, head in air). The submerged
 * normal-swim case (the 1.97 b/s fallback) is deferred to the stateful sprint-swim refinement below, where
 * it becomes the cost for water the bot is in but isn't sprint-swimming through.
 *
 * <h2>The floor-cell convention for a floating bot (decision C)</h2>
 * The search space is floor cells with the bot's feet at {@code floor.above()}. Water has no solid floor, so
 * a swim node's "floor" is simply the cell BELOW the feet — which is allowed to be water. A surface position
 * is the node {@code (x, wf-1, z)} whose feet cell {@code (x, wf, z)} hold water and whose head
 * {@code (x, wf+1, z)} is open air. This keeps {@code floor.above() == feet} universal, so {@code reconstruct}
 * and the follower geometry are unchanged — a swim node simply has a non-solid floor.
 *
 * <h2>Finding the surface (entry + crossing in one scan)</h2>
 * For each cardinal neighbour the move scans DOWN from the bot's current feet level for the highest cell
 * whose feet hold water and whose head is open air. That one scan covers both cases: flat-water crossing
 * finds the surface at the same level ({@code wf == feetY}); stepping in from a bank/ledge finds it a block
 * or two lower (the bot drops to the surface on entry — water cushions the small drop). Exiting onto land is
 * left to the ordinary walk moves ({@link Traverse}/{@link Ascend} from the water node onto an adjacent
 * standable cell), so this move only ever produces water destinations.
 */
public final class Swim implements Movement {

    /**
     * Surface-swim cost per block, in <b>ticks</b>: {@code 20 / 2.2 ≈ 9.09} (20 ticks/s ÷ the wiki's 2.2 b/s
     * surface-swim speed) — appreciably slower than the 4.633-tick walk. The submerged normal-swim rate
     * ({@code 20 / 1.97 ≈ 10.15}) is reserved for the stateful refinement (see class doc) and not emitted yet.
     */
    public static final float COST = 20f / 2.2f;

    /**
     * How far below the bot's current feet the move scans to locate the water surface — the maximum drop it
     * will take to enter water from a bank/ledge. Small and conservative (water cushions the landing, but a
     * big committed plunge belongs to a future water-Fall variant); a deeper entry just isn't offered here.
     */
    private static final int MAX_SINK = 4;

    private static final int[][] CARDINALS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /**
     * Upward velocity (blocks/tick) the swim steer drives when the planned cell is ABOVE the bot. Deliberately
     * strong + fixed (not the proportional descent rate): a proportional climb decays to ~nothing near the
     * target and the water-surface buoyancy equilibrium swallows a small velocity outright (measured stall:
     * dy=0.16 → vy=0.047 → the bot floated pinned at the waterline, never clearing the last cell). 0.30
     * reliably breaks the surface float and clears a one-block lip; descent stays gentle. Shared with {@link
     * SprintSwim}.
     */
    static final double CLIMB_VY = 0.30;

    @Override
    public void candidates(MovementContext ctx, int x, int y, int z, CandidateSink out) {
        int feetY = y + 1;
        for (int[] d : CARDINALS) {
            int nx = x + d[0];
            int nz = z + d[1];

            // Highest surface cell in this neighbour column: scan from the bot's feet level downward. Air
            // above the surface keeps the scan going; the first water cell is the surface; hitting a solid
            // cell first means there is no surface reachable straight down this column.
            for (int wf = feetY; wf >= feetY - MAX_SINK; wf--) {
                if (!ctx.built(nx, wf, nz)) break;          // unknown column — don't path into it
                long fd = ctx.descriptorAt(nx, wf, nz);
                if (ctx.water(fd)) {
                    // Water feet found. A SURFACE position needs open air at the head; a water head means the
                    // column is submerged here — that's SprintSwim's job, not a normal-swim destination.
                    if (!ctx.passable(nx, wf + 1, nz)) break;
                    out.accept(nx, wf - 1, nz, COST);
                    break;
                }
                if (!ctx.passable(fd)) break;               // hit solid before any water — no surface this way
                // else open air above the surface: keep scanning down.
            }
        }
    }

    @Override
    public boolean reached(BotSteering b, int wx, int wy, int wz) {
        return reachedSwim(b, wx, wy, wz);
    }

    @Override
    public void steer(BotSteering b, int wx, int wy, int wz) {
        steerSwim(b, wx, wy, wz, false);
    }

    /**
     * Swim cursor-advance test (shared with {@link SprintSwim}): a floating bot's Y bobs with buoyancy, so an
     * exact 3-D block match can stall the cursor — match horizontally with a ±1 vertical tolerance instead.
     */
    static boolean reachedSwim(BotSteering b, int wx, int wy, int wz) {
        return b.footX() == wx && b.footZ() == wz && Math.abs(b.footY() - wy) <= 1;
    }

    /**
     * Swim steering, shared by normal {@link Swim} ({@code sprint=false}) and {@link SprintSwim}
     * ({@code sprint=true}). Vertical control is by DIRECT velocity, not the {@code yya} input: in water the
     * movement input is weak (it's normalized and scaled by the small water speed, so a {@code yya=-1} yields
     * only ~-0.02/tick), and near-neutral buoyancy cancels that, so the bot just hovers. Driving
     * {@code deltaMovement.y} straight toward the target depth (clamped to a swim rate) makes it reliably
     * sink/rise to track the planned cell while {@code zza}+yaw propel it horizontally. Authority is
     * ASYMMETRIC: a STRONG fixed climb ({@link #CLIMB_VY}) is needed to break the surface float and clear a
     * lip, while the descent is gently proportional; a dead-band holds depth without jitter.
     */
    static void steerSwim(BotSteering b, int wx, int wy, int wz, boolean sprint) {
        b.faceHorizontally((wx + 0.5) - b.x(), (wz + 0.5) - b.z());
        b.setForward(1.0f);
        b.setSprinting(sprint); // submerged + sprinting → vanilla prone sprint-swim (5.612 b/s)
        b.setJumping(false);
        b.setVerticalInput(0.0f);
        double dyTarget = wy - b.y();
        double vy;
        if (dyTarget > 0.05) {
            vy = CLIMB_VY;                              // climb hard (surface break / clear the lip)
        } else if (dyTarget < -0.05) {
            vy = Math.max(-0.20, dyTarget * 0.3);       // descend gently (proportional)
        } else {
            vy = 0.0;                                   // at depth — hold
        }
        b.setVelocity(b.velX(), vy, b.velZ());
    }
}
