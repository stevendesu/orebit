package com.orebit.mod.pathfinding.blockpathfinder.movements;

import com.orebit.mod.pathfinding.blockpathfinder.BotSteering;
import com.orebit.mod.pathfinding.blockpathfinder.CandidateSink;
import com.orebit.mod.pathfinding.blockpathfinder.MovePlan;
import com.orebit.mod.pathfinding.blockpathfinder.Movement;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.pathfinding.blockpathfinder.SteerControl;
import com.orebit.mod.pathfinding.blockpathfinder.SteerView;

/**
 * Leave the prone sprint-swim — the {@link MovementContext#MODE_PRONE PRONE}&rarr;{@link
 * MovementContext#MODE_STANDING STANDING} transition <b>in place</b>, and the exact mirror of
 * {@link StartSprintSwim} case (1) (DESIGN-submerged-upright-swim.md §5). It does not move the bot: it flips
 * the search node's mode at the same {@code (x,y,z)}, which lands on a distinct row because mode is part of the
 * node key — so it is a real search edge, not a zero-progress self-loop.
 *
 * <p>It completes the cycle the whole "fluid is a medium" design turns on: <b>sprint-swim laterally &rarr;
 * stand up underwater &rarr; rise or sink upright ({@link Swim}) &rarr; go prone again ({@link StartSprintSwim})
 * &rarr; resume sprint-swimming</b>. Before it existed, the only way out of the prone pose was
 * {@link Surface}, which demanded an <i>open-air</i> head — so a submerged bot could never stand up while still
 * submerged, and the vertical axis was stranded on {@link SprintSwim}'s (unreal) verticals.
 *
 * <h2>The headroom gate is a POSE-FIT test, not a policy (§5.1)</h2>
 * Dropping sprint clears vanilla's {@code isSwimming()} flag, so {@code getDesiredPose()} returns
 * {@code STANDING} — but {@code Player.updatePlayerPose} then FIT-TESTS it and, in a 1-tall gap, falls back
 * through {@code CROUCHING} (1.5) to {@code Pose.SWIMMING} (0.6) (bytecode-verified against 1.21.11). The bot
 * would keep the prone hitbox and this move's {@code done} would never fire. So the edge is emitted only where
 * the upright pose actually fits: <b>two non-solid body cells</b> above the floor. That is {@link Surface}
 * case (a)'s old geometry minus its {@code !water} clause — which is precisely the clause that made the old
 * move a <i>surfacing</i> move rather than a <i>standing-up</i> one.
 *
 * <p>Where the pose does NOT fit — prone in a 1-tall submerged tunnel — the bot cannot stand up where it is at
 * all, and its only exit is {@link Surface}'s remaining rung: crawl out sideways onto a standable neighbour and
 * stand up as it emerges.
 *
 * <h2>Water only</h2>
 * A PRONE node exists only in water ({@code Pose.SWIMMING} is unreachable in lava — see {@link SprintSwim}),
 * so this move needs no fluid dispatch: there is nothing to end in lava.
 */
public final class EndSprintSwim implements Movement {

    /** Ticks to leave the prone pose and come upright — symmetric with {@link StartSprintSwim#COST}, and small
     *  enough that the search is not discouraged from using the pose transitions the design depends on. */
    public static final float COST = 2f;

    @Override
    public void candidates(MovementContext ctx, int x, int y, int z, CandidateSink out) {
        if (ctx.mode() != MovementContext.MODE_PRONE) return;           // only a prone bot stands up
        if (!ctx.built(x, y + 1, z) || !ctx.water(x, y + 1, z)) return; // a PRONE node is wet by construction

        // The pose-fit gate: the head cell must be non-solid (water — still submerged, the case that makes
        // this move exist — or open air, the old Surface case (a)). A solid head means the upright pose does
        // not fit and vanilla would silently keep the bot prone.
        if (!ctx.built(x, y + 2, z)) return;
        long head = ctx.descriptorAt(x, y + 2, z);
        if (!ctx.water(head) && !ctx.passable(head)) return;

        out.accept(x, y, z, COST, MovementContext.MODE_STANDING);
    }

    /**
     * Stop sprinting and stop pressing forward — that is the entire move. Vanilla drops {@code Pose.SWIMMING}
     * on the next tick that sees {@code !(isSprinting() && isInWater())}, and the pose-fit gate above
     * guarantees the upright box has somewhere to grow into. {@link SteerControl#holdDepth} at bias 0 pins the
     * new, taller pose at the planned feet height rather than letting it drift up from the
     * {@link SteerControl#SUBMERGE_BIAS} depth the prone pose was riding.
     */
    @Override
    public void steer(BotSteering b, SteerView path) {
        b.setSprinting(false);
        b.setForward(0f);
        SteerControl.holdDepth(b, path, 0.0);
    }

    @Override
    public MovePlan plan(int fx, int fy, int fz, int tx, int ty, int tz, int fromFootY, int toFootY) {
        // Swim floors are non-standable water → feetYOf returns floorY+1; fromFootY/toFootY == fy+1/ty+1.
        MovePlan plan = new MovePlan();
        plan.phase("standup")
                .drive((b, v) -> {
                    b.setSprinting(false);      // KEEP: the whole mechanism — vanilla drops the pose from this
                    b.setForward(0f);           // KEEP: a held forward impulse is what RETAINS the prone pose
                    SteerControl.holdDepth(b, v, 0.0);
                })
                .done(b -> !b.prone());
        return plan;
    }

    /**
     * Arrived once the bot is upright at the waypoint. The Y term matters even though the move is in place:
     * the pose transition RAISES the ride height (prone holds {@link SteerControl#SUBMERGE_BIAS} below the
     * planned feet, upright holds the planned feet), so "upright at this column" is true a good fraction of a
     * block before the bot has actually settled at the depth the next move will be framed from. Reuses
     * {@link Swim}'s buoyancy band rather than inventing a second tolerance — the same reasoning that
     * convicted {@link Surface}'s missing Y term on the flagship waterfall.
     */
    @Override
    public boolean reached(BotSteering b, int wx, int wy, int wz) {
        return !b.prone() && Swim.reachedSwim(b, wx, wy, wz);
    }
}
