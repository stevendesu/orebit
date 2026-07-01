package com.orebit.mod.pathfinding.blockpathfinder;

/**
 * The follower-side runtime that executes a {@link MovePlan} — the reactive counterpart to the declarative
 * plan a {@link Movement} builds. It owns the one piece of per-execution state a stateless movement singleton
 * can't: the phase <b>cursor</b> ("what we're trying to do"). Everything else is re-derived from the live world
 * each tick, so the move self-heals instead of latching a stale "done."
 *
 * <p>Per tick ({@link #run}):
 * <ol>
 *   <li><b>Reality override.</b> If the plan's {@link MovePlan#resetWhen regression} guard fires, snap the
 *       cursor back to the first phase — the move physically fell back and must re-attempt.</li>
 *   <li><b>Establish geometry.</b> Re-check the current phase's {@link MovePlan.Need needs} against the LIVE
 *       world and act on any unmet one: {@code AIR} still solid &rarr; {@link BotSteering#mine} it (timed, one
 *       at a time); {@code FOOTING} still missing &rarr; {@link BotSteering#place} it (instant). While anything
 *       is unmet the bot <b>holds</b> on the column rather than driving — "stop and fix, like a player." A
 *       break or place missed for any reason is simply re-issued next tick (the self-heal).</li>
 *   <li><b>Drive + advance.</b> Once the geometry holds, run the phase's {@code drive} inputs; advance the
 *       cursor when its {@code advanceWhen} guard fires, or report the move complete when the last phase's
 *       {@code done} guard fires.</li>
 * </ol>
 *
 * <p>MC-free (drives the bot only through the {@link BotSteering}/{@link SteerView} seams), so it — and every
 * movement's plan — stays headless-testable, exactly like the rest of the {@code steer} path.
 */
public final class PhaseRunner {

    private MovePlan plan;
    private int cursor;

    /** Begin executing {@code plan} from its first phase (called when a new step's plan is built). */
    public void begin(MovePlan plan) {
        this.plan = plan;
        this.cursor = 0;
    }

    /** Whether a plan is currently loaded (the follower runs {@link #run} only then; else it uses {@code steer}). */
    public boolean active() {
        return plan != null;
    }

    /** Drop the current plan (step finished / window swapped) so the next step rebuilds from scratch. */
    public void clear() {
        this.plan = null;
        this.cursor = 0;
    }

    /**
     * Advance the plan one tick against the live world. Returns {@code true} when the move is complete (the last
     * phase's {@code done} guard fired), so the follower can advance its waypoint cursor.
     */
    public boolean run(BotSteering bot, SteerView view) {
        if (plan == null) {
            return false;
        }
        if (cursor > 0 && plan.regressed(bot)) {
            cursor = 0; // reality override: the move fell back to its start — re-attempt from phase 0
        }

        MovePlan.Phase phase = plan.phaseAt(cursor);

        // Establish this phase's geometry FIRST. Mining is timed and one-cell-at-a-time, so the first unmet AIR
        // need claims the tick and we hold; placements are instant, so all missing footings resolve now. While
        // anything is unmet, hold on the target column instead of driving the phase (stop and fix the geometry).
        boolean holding = false;
        for (MovePlan.Req r : phase.needs()) {
            if (r.kind == MovePlan.Need.AIR) {
                if (bot.solidAt(r.x, r.y, r.z)) {
                    bot.mine(r.x, r.y, r.z);
                    holding = true;
                    break; // one timed break per tick
                }
            } else { // FOOTING
                if (!bot.solidAt(r.x, r.y, r.z)) {
                    bot.place(r.x, r.y, r.z);
                    holding = true; // re-validate next tick (place is instant)
                }
            }
        }
        if (holding) {
            SteerControl.recenterOnTarget(bot, view);
            return false;
        }

        // Geometry holds — drive the phase, then advance or finish.
        phase.drive(bot, view);
        if (cursor == plan.size() - 1) {
            return phase.isDone(bot);
        }
        if (phase.shouldAdvance(bot)) {
            cursor++;
        }
        return false;
    }
}
