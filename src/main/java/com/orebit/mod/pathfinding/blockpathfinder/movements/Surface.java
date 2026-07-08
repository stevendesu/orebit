package com.orebit.mod.pathfinding.blockpathfinder.movements;

import com.orebit.mod.pathfinding.blockpathfinder.BotSteering;
import com.orebit.mod.pathfinding.blockpathfinder.CandidateSink;
import com.orebit.mod.pathfinding.blockpathfinder.Movement;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.pathfinding.blockpathfinder.SteerControl;
import com.orebit.mod.pathfinding.blockpathfinder.SteerView;

/**
 * Stand up out of a prone sprint-swim — the PRONE→STANDING transition, the counterpart to {@link
 * StartSprintSwim}. Two ways to leave the water and regain the upright (1.8-tall) pose:
 * <ul>
 *   <li><b>In place at the surface</b> — the current cell is a water surface (water feet, open-air head):
 *       come up and tread water upright. From there the ordinary STANDING moves take over.</li>
 *   <li><b>Onto a dry bank</b> — a cardinal neighbour that is a valid standing position (a standable floor
 *       with two cells of headroom): climb out of the water onto land. This is how the bot leaves through a
 *       submerged 1×1 hole onto the shore (the level-exit case; an up/down bank is reached by surfacing
 *       first, then an ordinary Ascend/Descend — left to those moves).</li>
 * </ul>
 * Emits {@link MovementContext#MODE_STANDING}. Small {@link #COST}, symmetric with {@link StartSprintSwim}.
 */
public final class Surface implements Movement {

    /** Ticks to rise / stand out of the prone pose — small, symmetric with {@link StartSprintSwim#COST}. */
    public static final float COST = 2f;

    private static final int[][] CARDINALS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    @Override
    public void candidates(MovementContext ctx, int x, int y, int z, CandidateSink out) {
        if (ctx.mode() != MovementContext.MODE_PRONE) return; // only a prone bot stands up

        // (a) Surface in place: feet water + head open air (passable, not water) → tread water upright here.
        if (ctx.built(x, y + 1, z) && ctx.water(x, y + 1, z)
                && ctx.built(x, y + 2, z) && ctx.passable(x, y + 2, z) && !ctx.water(x, y + 2, z)) {
            out.accept(x, y, z, COST, MovementContext.MODE_STANDING);
        }

        // (b) Climb out onto a dry bank: a cardinal neighbour with a standable floor and two clear body cells.
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
     * Swim toward the standable exit ({@link SteerControl#swimTowards}), rising toward the bank top via the
     * {@link SteerControl#holdDepth depth autopilot} (bias 0 — the target feet height IS the bank top, so
     * below it the autopilot holds jump and the bot pops up to step-assist out). Once out of the water the
     * autopilot no-ops, vanilla stands the bot up, and the same look + forward becomes a plain walk onto land.
     */
    @Override
    public void steer(BotSteering b, SteerView path) {
        SteerControl.swimTowards(b, path);
        SteerControl.holdDepth(b, path, 0.0);
    }
}
