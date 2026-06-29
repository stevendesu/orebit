package com.orebit.mod.pathfinding.blockpathfinder.movements;

import com.orebit.mod.pathfinding.blockpathfinder.BotSteering;
import com.orebit.mod.pathfinding.blockpathfinder.CandidateSink;
import com.orebit.mod.pathfinding.blockpathfinder.Movement;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;

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
 * destination is submerged (feet AND head in water for a horizontal step; water at the new feet for a
 * vertical one), which is exactly the 2-deep "initiable" state — the common deep-water case. The precise
 * rule is deferred to a stateful refinement:
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
        // Only sprint-swim from a water position: the bot's current feet cell must hold water. (A surface
        // node's feet are water too, so a dive-down from the surface is offered; a land node generates
        // nothing.) The precise 2-deep initiation rule is approximated here — see the class doc.
        if (!ctx.built(x, y + 1, z) || !ctx.water(x, y + 1, z)) return;

        // Horizontal, fully submerged: destination feet AND head in water. A destination with an air head is
        // a surface step (the slow Swim's), so the two moves partition the horizontal cases cleanly.
        for (int[] d : CARDINALS) {
            int nx = x + d[0];
            int nz = z + d[1];
            if (!ctx.built(nx, y + 1, nz)) continue;
            if (ctx.water(nx, y + 1, nz) && ctx.water(nx, y + 2, nz)) {
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

    @Override
    public void steer(BotSteering b, int wx, int wy, int wz) {
        Swim.steerSwim(b, wx, wy, wz, true); // submerged + sprinting → vanilla prone sprint-swim
    }
}
