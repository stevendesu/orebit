package com.orebit.mod.pathfinding.blockpathfinder.movements;

import com.orebit.mod.pathfinding.blockpathfinder.BotSteering;
import com.orebit.mod.pathfinding.blockpathfinder.CandidateSink;
import com.orebit.mod.pathfinding.blockpathfinder.MovePlan;
import com.orebit.mod.pathfinding.blockpathfinder.Movement;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.pathfinding.blockpathfinder.SteerControl;
import com.orebit.mod.pathfinding.blockpathfinder.SteerView;

/**
 * Crawl out of the water onto a bank — the {@link MovementContext#MODE_PRONE PRONE}&rarr;{@link
 * MovementContext#MODE_STANDING STANDING} transition for a bot that <b>cannot stand up where it is</b>
 * (DESIGN-submerged-upright-swim.md §7). It moves the bot one cardinal cell onto a standable floor and comes
 * upright as it emerges.
 *
 * <h2>The geometry is easy to misread: it is the SOURCE that lacks headroom, not the bank</h2>
 * This move never inspects {@code (x, y+2, z)}. It covers a bot prone in a <b>1-tall submerged tunnel</b> whose
 * exit opens onto a standable floor at the same level with two clear cells above it: the bot crawls out and
 * stands up in the act of emerging, because standing up where it is, is physically impossible — vanilla's
 * {@code Player.updatePlayerPose} fit-tests STANDING, then CROUCHING, and falls back to {@code Pose.SWIMMING}
 * in a 1-tall gap (see {@link EndSprintSwim} §5.1). {@code StatefulSwimTest}'s 1×1 hole in a wall is exactly
 * this shape, and it is the reason the move survives at all.
 *
 * <p>It is the ONLY rung for "prone in fluid &rarr; dry standable neighbour": {@link SprintSwim} cannot go
 * there (it needs fluid at the destination feet), and {@link EndSprintSwim} cannot fire (no headroom to stand
 * up in). It is also a genuinely cheaper FUSED edge than {@code EndSprintSwim + Traverse} would be even where
 * both are legal — the same "fusion is what makes the edge findable" principle that keeps
 * {@link StartSprintSwim} case (2).
 *
 * <h2>What used to be here</h2>
 * A second rung surfaced the bot IN PLACE at a water surface (water feet, open-AIR head). That is now
 * {@link EndSprintSwim}, whose gate is the same geometry minus the {@code !water} clause — and dropping that
 * clause is the whole point: requiring an air head is what made a submerged bot unable to stand up, which in
 * turn stranded the vertical axis on {@link SprintSwim}'s unreal verticals.
 */
public final class Surface implements Movement {

    /** Ticks to crawl out and come upright — small, symmetric with {@link StartSprintSwim#COST}. */
    public static final float COST = 2f;

    private static final int[][] CARDINALS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    @Override
    public void candidates(MovementContext ctx, int x, int y, int z, CandidateSink out) {
        if (ctx.mode() != MovementContext.MODE_PRONE) return; // only a prone bot crawls out

        // Climb out onto a dry bank: a cardinal neighbour with a standable floor and two clear body cells (the
        // bot ends UPRIGHT there, so the destination — unlike the source — must fit the tall pose).
        for (int[] d : CARDINALS) {
            int nx = x + d[0];
            int nz = z + d[1];
            if (!ctx.built(nx, y, nz)) continue;
            long nd = ctx.descriptorAt(nx, y, nz);
            if (ctx.standable(nd)
                    && ctx.passable(nx, y + 1, nz) && ctx.passable(nx, y + 2, nz)) {
                // Bank floor read once: a damaging bank (magma shore — standable since s52b) charges the
                // flat floor-contact HP in the one damage currency; free for an immune bot.
                out.accept(nx, y, nz, COST + ctx.floorHazardCost(nd), MovementContext.MODE_STANDING);
            }
        }
    }

    /**
     * Swim toward the standable exit, rising toward the bank top via the {@link SteerControl#holdDepth depth
     * autopilot} (bias 0 — the target feet height IS the bank top, so below it the autopilot holds jump and the
     * bot pops up to step-assist out). Once out of the water the autopilot no-ops, vanilla stands the bot up,
     * and the same heading + forward becomes a plain walk onto land.
     */
    @Override
    public void steer(BotSteering b, SteerView path) {
        SteerControl.uprightSwimServo(b, path);
        SteerControl.holdDepth(b, path, 0.0);
    }

    @Override
    public MovePlan plan(int fx, int fy, int fz, int tx, int ty, int tz, int fromFootY, int toFootY) {
        // Swim floors are non-standable water → feetYOf returns floorY+1; fromFootY/toFootY == fy+1/ty+1.
        MovePlan plan = new MovePlan();
        plan.phase("surface")
                .drive((b, v) -> {
                    b.setSprinting(false);
                    SteerControl.uprightSwimServo(b, v);
                    SteerControl.holdDepth(b, v, 0.0);
                })
                .done(b -> !b.prone() && b.footX() == tx && b.footZ() == tz);
        return plan;
    }

    // NO reached() OVERRIDE — and DO NOT REINTRODUCE A LOOSE ONE.
    //
    // This move once declared `reached` as `!prone() && footX == wx && footZ == wz`: no Y term, no settle gate,
    // the only move besides StartSprintSwim to omit both. That produced the flagship waterfall failure directly
    // (2026-08-06). A fluid column is a stack of same-XZ waypoints, and BotNavigator.steerAlongPath scans
    // END->START taking the FURTHEST match, so entering the shaft at the BOTTOM matched the Surface node at the
    // TOP: "advance SKIPPED 10 step(s): 11->22" at (154,-13,104); step 22 was then framed from
    // expectTakeoffFoot.y=-7 — six blocks above the bot — failed its validity envelope, and the bot held
    // position while the current carried it back out of the column. That is the airborne FLY-THROUGH class the
    // default Movement.reached gained its settled() gate for on 2026-08-01; this move simply never got it.
    //
    // Now that the in-place surfacing rung has moved to EndSprintSwim, this move has exactly ONE arrival —
    // standing on dry land — so inheriting the default (settled + exact cell) is both correct and sufficient.
    // atWaypoint's settle band already exempts a grounded bot, so a bank exit is judged exactly like any other
    // walk arrival. The medium-aware split the waterfall forced on it is no longer needed.
}
