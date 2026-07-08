package com.orebit.mod.pathfinding.blockpathfinder.movements;

import com.orebit.mod.pathfinding.blockpathfinder.BotSteering;
import com.orebit.mod.pathfinding.blockpathfinder.CandidateSink;
import com.orebit.mod.pathfinding.blockpathfinder.Movement;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.pathfinding.blockpathfinder.SteerControl;
import com.orebit.mod.pathfinding.blockpathfinder.SteerView;

/**
 * Fast prone swimming — Minecraft's <b>Sprint Swim</b> (MOVEMENT-DESIGN.md, Tier 1 water). At
 * <b>5.612 blocks/s</b> it is the quickest water traversal there is — faster than the 4.317 b/s walk, and
 * the same speed as a land sprint (sprint and sprint-swim share the 5.612 b/s figure). The 3-D underwater
 * workhorse: it can cross, rise, or sink through flooded space, and A* prefers it over the slow {@link Swim}
 * wherever it is available.
 *
 * <h2>Initiation vs. continuation (a stateful nuance — v1 approximates)</h2>
 * In vanilla you must be in <b>2-deep</b> water (head submerged) to <i>initiate</i> a sprint swim, but once
 * moving you may <i>continue</i> sprint-swimming through <b>1-deep</b> water. That is a movement-state rule,
 * exactly like Crawl (a requirement to start that then unlocks otherwise-illegal moves). The block A* nodes
 * carry no such mode bit yet, so <b>v1 approximates</b>: a sprint-swim edge is offered wherever the
 * destination is submerged (water at the new feet for a vertical step; for a horizontal step, water feet plus
 * a head that is water OR — only when the bot is already submerged here — a solid block, so it threads a
 * <b>1-tall underwater gap</b> prone, the {@code 1×1}-hole-in-a-wall case). An open-air head stays the upright
 * surface {@link Swim}. Requiring the source to be submerged for the solid-head gap is the geometric stand-in
 * for "already prone-swimming" — the continuation-in-1-deep state. The precise rule is deferred to a stateful
 * refinement:
 * <ul>
 *   <li><b>Reserved NavGrid bit.</b> The TraversalGrid short has a spare flag bit; baking "this water cell
 *       has water above it" (i.e. 2-deep / sprint-initiable) into it at chunk-build time turns the
 *       feet+head probe into a single resident-bit read. (If more bits are ever needed, the navtype index is
 *       only ~250/1024 used, so it can shrink from 10 to 9 bits to free one.)</li>
 *   <li><b>Node sprint-state.</b> Tracking "already sprint-swimming" in the search node would let the
 *       continuation-in-1-deep case (and the slow submerged {@link Swim} fallback for non-initiated shallow)
 *       be modelled exactly. Heavier (it widens the search space), so it waits until the approximation proves
 *       insufficient.</li>
 * </ul>
 *
 * <h2>Hunger (future)</h2>
 * Sprint-swim drains hunger just like a land sprint. Bots currently ignore hunger (PRD §7.3 — sprint always
 * available), so this move is free today, consistent with that model. When a hunger subsystem lands, the
 * sprint-swim (and land-sprint) availability/cost gains a hunger gate.
 *
 * <h2>Floor-cell convention (decision C — see {@link Swim})</h2>
 * A swim node's "floor" is the cell below the feet, allowed to be water; the bot's feet are
 * {@code floor.above()}. A submerged position is a node {@code (x,y,z)} whose feet {@code (x,y+1,z)} hold
 * water. Swim-up and swim-down are plain node steps with <b>no block placement</b> — so the "expensive
 * vertical" problem that drives the open-air-pillar pathologies simply does not exist inside water.
 *
 * <h2>Entry</h2>
 * Sprint-swim edges are emitted only from a position whose feet are already in water (the {@code water(feet)}
 * gate). Entering water from land is {@link Swim}'s job; diving from the surface is the down-case here (a
 * surface node's feet — the top water cell — satisfy the gate). A pure land node generates nothing.
 */
public final class SprintSwim implements Movement {

    /**
     * Sprint-swim cost per block, in <b>ticks</b>: {@code 20 / 5.612 ≈ 3.56} (20 ticks/s ÷ the wiki's
     * 5.612 b/s sprint-swim speed) — faster than the 4.633-tick walk, the cheapest water move. Applies to
     * horizontal and vertical alike in v1 (a single measured rate until per-axis water speeds warrant more).
     */
    public static final float COST = 20f / 5.612f;

    private static final int[][] CARDINALS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    @Override
    public void candidates(MovementContext ctx, int x, int y, int z, CandidateSink out) {
        // Sprint-swim only continues in the PRONE mode — entered via StartSprintSwim in 2-deep water. Because
        // the search carries that mode in the node key, "already prone" is now an exact fact, not a geometric
        // guess: the bot RETAINS the 0.6-tall hitbox through 1-deep water and 1-tall gaps (the move-state rule
        // the old `submerged` hack only half-modelled).
        if (ctx.mode() != MovementContext.MODE_PRONE) return;
        // A PRONE node is in water by construction; guard the feet-water read defensively.
        if (!ctx.built(x, y + 1, z) || !ctx.water(x, y + 1, z)) return;

        // Horizontal: a prone step just needs water at the DESTINATION FEET. The cell above may be water
        // (2-deep), solid (a 1-tall underwater gap — a 1×1 hole in a wall, threaded prone), or air (skimming
        // the surface prone) — all fit the 0.6-tall hitbox, so there is no head-clearance requirement.
        for (int[] d : CARDINALS) {
            int nx = x + d[0];
            int nz = z + d[1];
            if (ctx.built(nx, y + 1, nz) && ctx.water(nx, y + 1, nz)) {
                out.accept(nx, y, nz, COST);
            }
        }

        // Up: rise one. The new feet cell (x,y+2,z) must hold water; the new head (x,y+3,z) may be water
        // (still submerged) or air (surfacing) — both are valid arrivals, so only the new feet is gated.
        if (ctx.built(x, y + 2, z) && ctx.water(x, y + 2, z)) {
            out.accept(x, y + 1, z, COST);
        }

        // Down: sink one. The new feet cell (x,y,z) must hold water.
        if (ctx.built(x, y, z) && ctx.water(x, y, z)) {
            out.accept(x, y - 1, z, COST);
        }
    }

    @Override
    public boolean reached(BotSteering b, int wx, int wy, int wz) {
        return Swim.reachedSwim(b, wx, wy, wz);
    }

    /**
     * Look at the planned cell and swim forward ({@link SteerControl#swimTowards}), sprinting — so vanilla
     * adopts the prone sprint-swim (fast, and the 0.6-tall pose that threads a 1×1 hole) — while the
     * {@link SteerControl#holdDepth depth autopilot} rides {@link SteerControl#SUBMERGE_BIAS} below the
     * planned depth: the short prone hitbox pinned under the surface never breaches, so vanilla keeps the
     * pose (a breach drops it and degrades to the slow {@link Swim}).
     */
    @Override
    public void steer(BotSteering b, SteerView path) {
        SteerControl.swimTowards(b, path);
        b.setSprinting(true);
        SteerControl.holdDepth(b, path, SteerControl.SUBMERGE_BIAS);
    }
}
