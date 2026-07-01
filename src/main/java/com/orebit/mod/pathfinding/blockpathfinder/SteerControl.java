package com.orebit.mod.pathfinding.blockpathfinder;

/**
 * The path-tracking controller: turns the planned trajectory ({@link SteerView}) into per-tick player
 * <b>inputs</b> (look direction + forward key) on the bot ({@link BotSteering}). This is the shared steering
 * math behind every movement's {@link Movement#steer} hook.
 *
 * <h2>Input-based, never velocity (the Baritone model)</h2>
 * The controller only ever sets <i>movement inputs</i> — the yaw to face and the forward key — and lets
 * vanilla physics produce the motion (walking, water drag, buoyancy, step-assist, slipperiness). It never
 * overwrites the bot's velocity. That is deliberately how a real player is driven: you press W and steer,
 * you don't teleport your momentum. The payoff is that medium-specific behaviour falls out of vanilla for
 * free — most importantly, <b>vertical movement in water is the JUMP input, not a tuned velocity</b>: holding
 * jump makes vanilla {@code aiStep} swim the bot up (buoyancy) in water and jump on land, one mechanism for
 * both. The follower owns that water rule (see {@code AllyBotEntity}); a movement just contributes its look +
 * forward (+ jump for a climb, + sprint for a sprint-swim).
 *
 * <h2>Tracking the line, not a point</h2>
 * Rather than aim at the next waypoint centre (which cuts corners and drifts wide with no correction), the
 * controller projects the bot onto the current segment and aims a look-ahead <b>pursuit point</b> a fixed
 * distance ahead <i>on the line</i> — so being off the line steers the bot back onto it. A vertical
 * (degenerate) segment collapses to "re-centre on the column", which is what a pillar/mine-down wants.
 *
 * <p>Cold (tick-rate) code, one bot's steer per call, so the per-call geometry is staged in a single reusable
 * {@link Geom} scratch — no garbage. All inputs/outputs are primitives via the two MC-free seams.
 */
public final class SteerControl {

    private SteerControl() {}

    /** How far ahead of the bot's projection, along the segment, the pursuit point is aimed (blocks). Larger
     *  = smoother but lazier cornering; smaller = tighter line-holding but twitchier. The one steering knob. */
    static final double LOOKAHEAD = 1.5;
    /** Lengths below this are treated as zero (degenerate segment / already on the point) — avoids /0. */
    static final double EPS = 1.0e-4;

    // ---- per-call geometry scratch (single bot per tick → one reusable instance) ---------------------

    private static final class Geom {
        double segLen;       // horizontal segment length
        double qx, qz;       // pursuit point (xz): the bot's projection advanced LOOKAHEAD toward the target
        double cte;          // horizontal cross-track distance (bot → nearest point on the segment line)
    }

    private static final Geom G = new Geom();

    /** Project the bot onto the current segment and compute the pursuit point + cross-track error into G. */
    private static void computeGeom(BotSteering b, SteerView p) {
        double ax = p.sx(), az = p.sz();
        double tx = p.tx(), tz = p.tz();
        double px = b.x(), pz = b.z();

        double segX = tx - ax, segZ = tz - az;
        double len = Math.sqrt(segX * segX + segZ * segZ);
        G.segLen = len;

        if (len < EPS) {
            // Vertical/degenerate segment: there is no line to track, so aim at (and measure from) the column.
            G.qx = tx; G.qz = tz;
            double dx = px - tx, dz = pz - tz;
            G.cte = Math.sqrt(dx * dx + dz * dz);
            return;
        }
        double ux = segX / len, uz = segZ / len;
        double along = (px - ax) * ux + (pz - az) * uz;
        if (along < 0.0) along = 0.0; else if (along > len) along = len;
        double fx = ax + ux * along, fz = az + uz * along;      // nearest point on the segment
        double q = Math.min(along + LOOKAHEAD, len);
        G.qx = ax + ux * q; G.qz = az + uz * q;                 // pursuit point ahead on the line
        double cx = px - fx, cz = pz - fz;
        G.cte = Math.sqrt(cx * cx + cz * cz);
    }

    /**
     * Walk the bot along the planned line: face the look-ahead pursuit point (which pulls the bot back onto
     * the line when it drifts) and hold the forward key. A vertical/degenerate segment has no line to follow,
     * so it re-centres on the target column instead (face it, forward eased to ~0 once centred). This is the
     * default for every ground move; jump/sprint/water-rise are added by the move and the follower on top.
     */
    public static void steerTowards(BotSteering b, SteerView p) {
        computeGeom(b, p);
        if (G.segLen < EPS) {
            recenterOnTarget(b, p);
            return;
        }
        b.faceHorizontally(G.qx - b.x(), G.qz - b.z());
        b.setForward(1.0f);
    }

    /**
     * Re-centre on the target column: face the target's x,z and apply forward input proportional to the
     * horizontal offset, so a bot dead-on the column doesn't shove itself off while a drifted bot walks back.
     * Used by the vertical-in-place moves (Pillar, MineDown) and by an airborne Fall homing onto its landing
     * column. Input-based, so the (weak) air control is honest rather than a teleported velocity.
     */
    public static void recenterOnTarget(BotSteering b, SteerView p) {
        double cx = p.tx() - b.x();
        double cz = p.tz() - b.z();
        double d = Math.sqrt(cx * cx + cz * cz);
        if (d > EPS) {
            b.faceHorizontally(cx, cz);
            b.setForward((float) Math.min(1.0, d)); // ~0 when already centred on the column
        } else {
            b.setForward(0.0f);
        }
    }

    /**
     * Swim along the planned line, HORIZONTALLY: face the look-ahead pursuit point and hold forward (the same
     * W-key + look a player uses). Vertical is NOT here — it's the follower's cross-cutting water rule, which
     * holds space to rise / "holds shift" to sink toward the planned depth (the input a player presses; see
     * {@code AllyBotEntity}). A pure vertical (degenerate) segment has no horizontal target, so it just stops
     * pushing and lets that rule drive the dive/climb straight down/up the column.
     */
    public static void swimTowards(BotSteering b, SteerView p) {
        computeGeom(b, p);
        if (G.segLen < EPS) {
            b.setForward(0.0f);
            return;
        }
        b.faceHorizontally(G.qx - b.x(), G.qz - b.z());
        b.setForward(1.0f);
    }

    /**
     * The generic locomotion actuator chosen by medium: on land, the input-based line-tracking walk
     * ({@link #steerTowards}); in water, the horizontal swim drive ({@link #swimTowards}) — so a ground move
     * still submerged on its way out of water is steered toward the exit while the follower's water rule lifts
     * it, instead of stalling.
     */
    public static void drive(BotSteering b, SteerView p) {
        if (b.inWater()) {
            swimTowards(b, p);
        } else {
            steerTowards(b, p);
        }
    }

    /**
     * Horizontal cross-track distance (blocks) of the bot from the current planned segment — how far off the
     * line it has slipped. The follower watches this to <i>detect</i> a genuine slip (knocked by a mob/current,
     * pushed) and trigger recovery.
     */
    public static double crossTrack(BotSteering b, SteerView p) {
        computeGeom(b, p);
        return G.cte;
    }
}
