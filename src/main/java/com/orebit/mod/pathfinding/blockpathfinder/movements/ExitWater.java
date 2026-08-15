package com.orebit.mod.pathfinding.blockpathfinder.movements;

import com.orebit.mod.pathfinding.blockpathfinder.BotSteering;
import com.orebit.mod.pathfinding.blockpathfinder.CandidateSink;
import com.orebit.mod.pathfinding.blockpathfinder.MovePlan;
import com.orebit.mod.pathfinding.blockpathfinder.Movement;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.pathfinding.blockpathfinder.SteerControl;

/**
 * Climb out of water onto a bank one block up — the UPRIGHT counterpart to {@link Surface}'s prone crawl-out
 * (MOVEMENT-DESIGN.md §2, Tier 1). The bot floats at the top of a fluid column with a standable floor in a
 * cardinally adjacent column one cell higher, holds jump to rise the last fraction of a block, and steps
 * across onto it.
 *
 * <h2>Why this class exists (2026-08-15)</h2>
 * Nothing owned this shape. {@link Surface} is {@code MODE_PRONE} only <i>and</i> flat (it emits
 * {@code (nx, y, nz)}, the same floor Y), so an upright swimmer has no {@code Surface} edge at all.
 * {@link Ascend} used to cover it by accident — it never checked its source floor — until commit
 * {@code cf2b8ed} (2026-08-01) added rule R1:
 *
 * <pre>if (!ctx.solidFooting(x, y, z) &amp;&amp; !ctx.isClimbable(ctx.descriptorAt(x, y + 2, z))) return;</pre>
 *
 * R1 was written to stop a jump launching from a CLIMBABLE hang, and it was right to: a hanging entity cannot
 * jump (the 0.42 impulse fires only under {@code onGround}), so the emitted edge was unexecutable and the bot
 * burned ~12000 ticks at {@code (55,*,207)} ejecting off a vine, free-falling and re-grabbing. But
 * {@code !solidFooting} is broader than "hanging": it also excludes the third stance nobody enumerated,
 * <b>afloat</b>. Water was collateral damage, and from that commit onward a bot could enter a pool and never
 * leave it — bisected on the SwimCourse {@code cross} card, which passed at {@code 62f5eac} and fails at
 * {@code cf2b8ed} with {@code block tier cannot realize this crossing for these caps}.
 *
 * <p><b>Restoring the Ascend edge would have been wrong</b>, for the reason R1 exists. Water damps a jump
 * impulse exactly as a climbable does: a single tap does not breach, you have to HOLD the key and rise while
 * the feet stay in fluid. {@code Ascend.plan} drives a tap-jump, so admitting fluid stances there would emit
 * an edge its own executor cannot perform — the same unexecutable-edge bug, in a new medium. So this is a new
 * class with a hold-jump executor, per this project's own rule: <i>"adding a capability is adding a class,
 * never editing an existing one"</i> ({@link Movement}). R1 stays exactly as written.
 *
 * <h2>Frames</h2>
 * The node {@code (x,y,z)} is a FLOOR cell, so the bot's feet are {@code (x,y+1,z)} and this move requires
 * that feet cell to be fluid. The destination floor is {@code (nx,y+1,nz)} — one Y above the source floor —
 * so the landing feet cell is {@code (nx,y+2,nz)}. Worked against the SwimCourse {@code cross} geometry: the
 * topmost swim node is floor 159 / feet 160 (the pool fills to 160), and the platform's floor cell is 160
 * with feet 161.
 */
public final class ExitWater implements Movement {

    private static final int[][] CARDINALS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    /**
     * Ticks for one exit = the sustained rise plus the step across.
     *
     * <p>The rise is {@code 1 - SWIM_RIDE} blocks: the bot parks at {@link SteerControl#SWIM_RIDE} inside its
     * feet cell and the landing surface is that cell's top, so it must gain the remaining {@code 0.8} at the
     * in-fluid rise rate {@link Swim#UP_COST}. The lateral half is charged at {@link Swim#COST}, the surface
     * paddle rate, because the bot is still in the fluid while it crosses the lip. Both terms are the
     * physically-derived swim rulers rather than new numbers — the costs invariant.
     */
    public static final float COST =
            Swim.UP_COST * (1f - (float) SteerControl.SWIM_RIDE) + Swim.COST;

    @Override
    public void candidates(MovementContext ctx, int x, int y, int z, CandidateSink out) {
        if (ctx.mode() != MovementContext.MODE_STANDING) return;  // the upright exit; Surface owns the prone one

        // The bot must actually be afloat: its FEET cell (one above the node's floor cell) has to be fluid.
        final int feetY = y + 1;
        final int feetPacked = ctx.packedAt(x, feetY, z);
        if (feetPacked == MovementContext.UNBUILT) return;
        if (!Swim.fluid(ctx, ctx.descriptorOf(x, feetY, z, feetPacked))) return;

        // Rising to the landing height carries the body up one cell inside the SOURCE column first, so that
        // column's next two cells must be clear or the bot cannot get its head above the lip.
        if (!ctx.passable(x, y + 2, z) || !ctx.passable(x, y + 3, z)) return;

        for (int[] d : CARDINALS) {
            final int nx = x + d[0];
            final int nz = z + d[1];

            // The bank: a standable floor one cell UP from the node's floor.
            final int packed = ctx.packedAt(nx, y + 1, nz);
            if (packed == MovementContext.UNBUILT) continue;
            final long dstDesc = ctx.descriptorOf(nx, y + 1, nz, packed);
            if (!ctx.standable(dstDesc)) continue;

            // Landing body: feet + head above that floor.
            if (!ctx.passable(nx, y + 2, nz) || !ctx.passable(nx, y + 3, nz)) continue;

            out.accept(nx, y + 1, nz, COST + ctx.floorHazardCost(dstDesc));
        }
    }

    /**
     * Two phases, because the rise and the crossing need different inputs.
     *
     * <p><b>rise</b> — face the bank and HOLD jump, with no forward drive, until the feet reach the landing
     * surface. Holding is the whole point (see the class doc): in fluid a tap is damped away, and the bot
     * gains height only while the key is down and its feet are still wet. Forward is withheld so the bot
     * cannot press into the bank wall and convert the climb into a stall — the same shape as {@link Ascend}'s
     * climb-first-translate-after fix, for the same reason.
     *
     * <p><b>cross</b> — release and drive normally onto the bank. By this point the feet are at the landing
     * surface, so ordinary locomotion carries the bot over the lip.
     *
     * <p><b>Envelope.</b> The move is airborne-or-afloat by nature, so a verdict is only taken once the bot is
     * settled: settled anywhere outside the two columns it legitimately occupies (source and destination), or
     * below the source feet cell, means it has sunk out of the move's model and the plan's frame is fiction.
     * A bot that merely sinks back inside its own column is not a failure — {@code resetWhen} re-runs the rise.
     */
    @Override
    public MovePlan plan(int fx, int fy, int fz, int tx, int ty, int tz, int fromFootY, int toFootY) {
        if (ty != fy + 1) return null;                                  // strictly one cell up
        if (Math.abs(tx - fx) + Math.abs(tz - fz) != 1) return null;    // strictly one cardinal step

        final double landY = toFootY;              // world Y of the landing surface (feet cell's base)

        MovePlan plan = new MovePlan();
        plan.moveDir(tx - fx, tz - fz);
        // Sank back into the source column below the lip — re-run the rise from phase 0.
        plan.resetWhen(b -> b.footX() == fx && b.footZ() == fz && b.y() < landY - 0.5);
        plan.failWhen(b -> {
            if (!b.settled()) return false;                             // mid-rise is not a verdict
            if (b.footY() < fromFootY) return true;                     // sank out the bottom of the column
            final boolean inSource = b.footX() == fx && b.footZ() == fz;
            final boolean inDest = b.footX() == tx && b.footZ() == tz;
            return !(inSource || inDest);
        });
        plan.phase("rise")
                .drive((b, v) -> {
                    b.faceHorizontally(tx - fx, tz - fz);
                    b.setForward(0.0f);        // climb first; pressing the bank wall would only stall the rise
                    b.setJumping(true);        // HOLD, not tap — fluid damps an impulse
                })
                .advanceWhen(b -> b.y() >= landY);
        plan.phase("cross")
                .drive(SteerControl::drive)
                .done(b -> b.settled() && atWaypoint(b, tx, toFootY, tz));
        return plan;
    }

    /** Permissive entry: this move's servo establishes its own stance, so it accepts any pose (see
     *  {@link Movement#entryReady}). Also exactly the pre-2026-08-15 behaviour, where no entry test existed. */
    @Override
    public boolean entryReady(BotSteering b, int wx, int wy, int wz) {
        return true;
    }
}
