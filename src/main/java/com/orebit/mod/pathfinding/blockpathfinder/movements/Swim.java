package com.orebit.mod.pathfinding.blockpathfinder.movements;

import com.orebit.mod.pathfinding.blockpathfinder.BotSteering;
import com.orebit.mod.pathfinding.blockpathfinder.CandidateSink;
import com.orebit.mod.pathfinding.blockpathfinder.Movement;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.pathfinding.blockpathfinder.SteerControl;
import com.orebit.mod.pathfinding.blockpathfinder.SteerView;

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

    @Override
    public void candidates(MovementContext ctx, int x, int y, int z, CandidateSink out) {
        if (ctx.mode() != MovementContext.MODE_STANDING) return; // upright surface paddle — STANDING pose

        // Domination prune: wherever a sprint-swim can be INITIATED from this cell (the StartSprintSwim gate),
        // the prone StartSprintSwim->SprintSwim branch strictly dominates the slow surface Swim — it is cheaper
        // per block (3.56 vs 9.09) and reaches everywhere Swim can (SprintSwim there + Surface up). Offering Swim
        // there only lets the greedy/inadmissible search race down the slow branch (Swim lowers h at once, while
        // the in-place StartSprintSwim doesn't) and, once committed, never reconsider — the "arrived, floating at
        // the surface, moved away before it sank" case that plans slow Swim instead of diving. Suppressing Swim
        // forces the dive. It stays available where initiation is impossible: entering from a dry bank (feet not
        // yet wet) and strictly-1-deep water (solid floor, open-air head) — mirrors StartSprintSwim.candidates.
        if (ctx.built(x, y + 1, z) && ctx.water(x, y + 1, z)) {                    // feet in water
            boolean twoDeep = ctx.built(x, y + 2, z) && ctx.water(x, y + 2, z);    // head water → prone in place
            boolean deepBelow = ctx.built(x, y, z) && ctx.water(x, y, z);          // water below → dive to prone
            if (twoDeep || deepBelow) return;
        }

        // LAVA RISE (s52b hazard-media): a bot SUBMERGED in lava (feet + head both lava) has no other
        // planner rung — there is no prone pose in lava, so SprintSwim's submerged mobility doesn't
        // exist here — yet vanilla swims up in lava exactly like water (hold jump). One cell up per
        // step, priced as a lava cell (slow factor + immersion damage), so A* can climb a lava column
        // to the surface, paddle across, and exit via the ordinary walk moves — and will only ever
        // choose to when nothing cheaper exists. Water keeps its existing division of labor
        // (submerged rise = the sprint-swim family) — this rung is lava-only by design.
        if (ctx.built(x, y + 1, z) && ctx.lava(ctx.descriptorAt(x, y + 1, z))
                && ctx.built(x, y + 2, z) && ctx.lava(ctx.descriptorAt(x, y + 2, z))) {
            out.accept(x, y + 1, z, ctx.lavaSwimCellCost(COST));
        }

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
                if (ctx.water(fd) || ctx.lava(fd)) {
                    // Fluid feet found. A SURFACE position needs open air at the head; a submerged-water
                    // head is SprintSwim's job (and a submerged-lava column is reached only via the lava
                    // rise above). A LAVA cell prices the hard-coded lava adjustments — slow factor +
                    // immersion damage (s52b hazard-media) — so A* enters lava only when forced.
                    if (!ctx.passable(nx, wf + 1, nz)) break;
                    out.accept(nx, wf - 1, nz, ctx.lava(fd) ? ctx.lavaSwimCellCost(COST) : COST);
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

    /**
     * Surface swim: look at the planned cell and hold forward ({@link SteerControl#swimTowards}), holding the
     * planned depth with the {@link SteerControl#holdDepth depth autopilot} (bias 0 — the tall standing pose
     * rides at the planned feet height). Not sprinting; the prone 1×1 pose is {@link SprintSwim}. This move
     * owns its whole control set — rising, diving, and holding depth (s52; no follower water rule exists).
     */
    @Override
    public void steer(BotSteering b, SteerView path) {
        SteerControl.swimTowards(b, path);
        SteerControl.holdDepth(b, path, 0.0);
    }

    /**
     * Vertical reach tolerance for the swim cursor (blocks of continuous Y). Kept under 1 so a vertical dive's
     * stacked waypoints (1 block apart) can't both be "reached" at once — that was the {@code ±1} block
     * tolerance's "drop two cells at a time" leapfrog — while still wide enough to absorb the buoyancy bob.
     */
    static final double REACHED_Y = 0.6;

    /**
     * Swim cursor-advance test (shared with {@link SprintSwim}): horizontal cell match plus the bot's
     * <i>continuous</i> feet Y within {@link #REACHED_Y} of the held swim depth (feet on top of the floor cell,
     * i.e. world {@code wy + 1}). Using continuous Y rather than the old ±1 <i>block</i> tolerance keeps a
     * dive advancing one waypoint at a time instead of leapfrogging the column.
     */
    static boolean reachedSwim(BotSteering b, int wx, int wy, int wz) {
        return b.footX() == wx && b.footZ() == wz && Math.abs(b.y() - (wy + 1.0)) < REACHED_Y;
    }
}
