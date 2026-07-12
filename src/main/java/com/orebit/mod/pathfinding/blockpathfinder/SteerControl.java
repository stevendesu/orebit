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
 * both. The depth-hold autopilot ({@link #holdDepth}) is called by each move's own {@code steer} — the moves
 * OWN their vertical control (s52; the old cross-cutting follower water rule is gone). The one exception to
 * "inputs only" is the sink half: vanilla's -0.04 down-swim lives in the CLIENT tick a headless bot never
 * runs, so {@link BotSteering#sinkInWater} replicates it.
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
    /**
     * Swim-local cross-track gain: the swim pursuit look-ahead is shrunk as the bot's cross-track error grows
     * ({@code lookahead = LOOKAHEAD / (1 + SWIM_CTE_GAIN * cte)}). At {@code cte=0} the swim drive is the same
     * lazy {@link #LOOKAHEAD} pursuit as the ground walk; as the bot slips off its lane the aim point collapses
     * back toward the nearest point ON the line, so the correction turns HARD toward the centerline instead of
     * converging gently. This is the tight 1-wide-lane hold the bubble-column channels need (a graze of a flank
     * up-column ejects the bot). Ground steering keeps gain 0 (fixed look-ahead) — this is swim-only. */
    static final double SWIM_CTE_GAIN = 6.0;
    /** How many cells past a turn waypoint the hazard-aware corner brake probes along the CURRENT travel
     *  direction to decide whether an overshoot would carry the bot into a hazard (bubble column / lava). */
    static final int HAZARD_LOOKAHEAD = 2;
    /** Cross-track drift (blocks) past which a bot on a hazard-FLANKED lane is judged to be drifting toward the
     *  flank column and is crawled to bleed the perpendicular momentum + recentre. Below it (a centred bot on a
     *  bubble-flanked straight) the lane runs at full speed — so only the actual corner-departure drift is bled. */
    static final double FLANK_DRIFT = 0.08;
    /** Lengths below this are treated as zero (degenerate segment / already on the point) — avoids /0. */
    static final double EPS = 1.0e-4;
    /** cos of the max off-heading angle treated as "in line" (~25 degrees) — above it a corner is a real turn. */
    static final double STRAIGHT_DOT = 0.9;
    /** Dead-band (blocks) around the planned depth inside which {@link #holdDepth} presses neither rise nor
     *  sink — bang-bang controller hysteresis so a bot at its target depth doesn't chatter jump on/off. */
    static final double WATER_RISE_DEADBAND = 0.2;
    /**
     * How far (blocks) below the planned depth a PRONE-pose move rides ({@link #holdDepth}'s {@code bias} for
     * the sprint-swim moves). The prone hitbox is only ~0.6 tall, so at a surface-level planned depth the
     * {@link #WATER_RISE_DEADBAND} up-slack would float the whole hitbox clear of the water and vanilla would
     * drop {@code Pose.SWIMMING} (its continuation rule needs {@code isInWater()}), degrading the fast
     * sprint-swim to the slow surface swim. Sinking the ride ~0.5 keeps the hitbox wet while staying under
     * {@code Swim.REACHED_Y} (0.6) so the swim cursor still advances. Standing water moves pass bias 0.
     */
    public static final double SUBMERGE_BIAS = 0.8;
    /**
     * Corner-brake throttle-off distance (blocks): inside this range of the turn waypoint centre the
     * {@link #swimPitchedCentered} corner brake cuts the forward key to a COAST so the bot bleeds its cruise
     * momentum and arrives centred instead of overshooting the lane into the far wall. Sized to the ~half-block
     * the swim cursor-advance releases the brake EARLY at (a swim waypoint is reached when the FOOT block enters
     * the cell, ~0.5 block short of the cell centre) — so the coast covers exactly the released gap and the bot
     * doesn't barrel into the corner at half throttle with a full head of momentum (the bubble-lane ejection). */
    static final double TURN_BRAKE_STOP = 0.1;
    /** Distance (blocks) over which the corner brake ramps the forward key from full down to the coast as the bot
     *  nears the turn centre — larger = gentler/earlier deceleration into the corner. */
    static final double TURN_BRAKE_RAMP = 2.0;
    /**
     * How far (blocks) the corner brake pulls its aim point BACK toward the incoming waypoint from the turn
     * cell centre. A swim waypoint is "reached" when the bot's FOOT block enters the cell — at its NEAR face,
     * ~0.5 block short of the centre — so a brake that decelerates toward the far centre still hands off (cursor
     * advances) with the bot half a cell short and a full head of momentum, which then coasts THROUGH the lane
     * into the far wall. Braking toward the near face instead zeroes the momentum right where the cursor
     * releases, so the bot arrives centred on the lane. Kept under 0.5 so the aim still lies inside the turn cell
     * (the bot must cross the near face to advance the cursor — a full 0.5 offset would stall it at the face). */
    static final double TURN_ARRIVE_OFFSET = 0.45;
    /** Corner reverse-brake zone (blocks): within this range of the arrive point the corner brake stops merely
     *  coasting and actively REVERSE-thrusts (faces up-track, away from the arrive point) to kill the incoming
     *  cruise momentum, so the bot enters the turn cell slow enough to hold the lane instead of coasting through
     *  into the flanking wall. */
    /** Crawl throttle cap at a HAZARD corner: the forward key is capped this low so a fast bot DECELERATES into
     *  the corner (drag beats the reduced thrust) yet a slow bot keeps CREEPING across the cell face — a steady
     *  near-crawl (owner: "velocity ≈ 0") that neither overshoots the lane into the flank hazard nor stalls the
     *  cursor (a true dead-stop leaves nothing to advance the bot). Only ever applied when the corner's overshoot
     *  is a hazard, so a harmless turn is never slowed. */
    static final double TURN_CRAWL_THROTTLE = 0.28;

    // ---- per-call geometry scratch (single bot per tick → one reusable instance) ---------------------

    private static final class Geom {
        double segLen;       // horizontal segment length
        double qx, qz;       // pursuit point (xz): the bot's projection advanced LOOKAHEAD toward the target
        double cte;          // horizontal cross-track distance (bot → nearest point on the segment line)
    }

    private static final Geom G = new Geom();

    /** Per-call travel-frame scratch for the hazard probes (single bot per tick → one reusable instance). */
    private static final class Frame {
        double ux, uz;   // unit travel direction (horizontal)
        int cx, cy, cz;  // current waypoint cell
    }

    private static final Frame F = new Frame();

    /** Project the bot onto the current segment and compute the pursuit point + cross-track error into G, with a
     *  FIXED {@link #LOOKAHEAD} (ground walk — gain 0). */
    private static void computeGeom(BotSteering b, SteerView p) {
        computeGeom(b, p, 0.0);
    }

    /**
     * Project the bot onto the current segment and compute the pursuit point + cross-track error into G. The
     * pursuit look-ahead is {@code LOOKAHEAD / (1 + cteGain * cte)} — with {@code cteGain == 0} this is the plain
     * fixed-look-ahead pursuit (ground), and with {@code cteGain > 0} (the swim drives, {@link #SWIM_CTE_GAIN})
     * the look-ahead collapses toward the on-line point as cross-track grows, tightening the lane hold.
     */
    private static void computeGeom(BotSteering b, SteerView p, double cteGain) {
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
        double cx = px - fx, cz = pz - fz;
        double cte = Math.sqrt(cx * cx + cz * cz);
        G.cte = cte;
        double lookahead = LOOKAHEAD / (1.0 + cteGain * cte);   // swim: shrink as cross-track grows
        double q = Math.min(along + lookahead, len);
        G.qx = ax + ux * q; G.qz = az + uz * q;                 // pursuit point ahead on the line
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
     * W-key + look a player uses). Vertical is the caller's {@link #holdDepth} (each swim move calls it with
     * its own bias — the moves own their vertical control). A pure vertical (degenerate) segment has no
     * horizontal target, so it just stops pushing and lets {@code holdDepth} drive the dive/climb straight
     * down/up the column.
     */
    public static void swimTowards(BotSteering b, SteerView p) {
        computeGeom(b, p, SWIM_CTE_GAIN);
        if (G.segLen < EPS) {
            b.setForward(0.0f);
            return;
        }
        b.faceHorizontally(G.qx - b.x(), G.qz - b.z());
        b.setForward(1.0f);
    }

    /**
     * Prone sprint-swim LOOK + forward: face the 3-D pursuit point (horizontal look-ahead on the line, pitch
     * toward the planned depth {@code p.ty() - bias}) and hold forward. NO jump/sink here — the CALLER adds
     * holdDepth for submersion/depth. Pass the SAME {@code bias} holdDepth uses so pitch and holdDepth target
     * the identical depth and cooperate on a descent/cruise (an over-high pitch fights holdDepth's descent,
     * bobbing the bot ~bias/2 above the target — the sink/lipdown stall); pass {@code bias=0} for the brief
     * initiation move (StartSprintSwim), where aiming pitch at {@code p.ty()} keeps a surface crossing near the
     * top so it rises and crosses instead of digging into the floor. Pitch fixes the yaw-spin on steep/vertical
     * segments that plain faceHorizontally suffers.
     */
    public static void swimPitched(BotSteering b, SteerView p, double bias) {
        computeGeom(b, p, SWIM_CTE_GAIN);
        double dy = (p.ty() - bias) - b.y();
        if (G.segLen < EPS) {
            b.faceTowards(0.0, dy, 0.0);
            b.setForward(0.0f);   // pure vertical: no horizontal push (moveRelative is yaw-only); pitch descends
        } else {
            b.faceTowards(G.qx - b.x(), dy, G.qz - b.z());
            b.setForward(1.0f);
        }
    }

    /**
     * Prone sprint-swim CENTERED drive: like {@link #swimPitched} it faces a 3-D target with a depth pitch,
     * but it aims at the waypoint CENTRE (not a LOOKAHEAD pursuit point) and eases the forward key in
     * proportion to the horizontal distance to that centre — mirroring {@link #recenterOnTarget}'s proportional
     * pull. As the bot nears the waypoint centre, forward decays to 0; on an overshoot the yaw re-faces the
     * centre and forward pushes BACK, so the cruise DECELERATES into a corner instead of coasting full-forward
     * past it (the swimturn drift). The depth pitch is unchanged from {@link #swimPitched}: it faces
     * {@code p.ty() - bias} so it cooperates with the caller's {@link #holdDepth} at the same depth. A vertical
     * (degenerate) segment collapses to a pure depth pitch with no horizontal push.
     */
    public static void swimPitchedCentered(BotSteering b, SteerView p, double bias) {
        double cx = p.tx() - b.x();               // toward the waypoint CENTER (not a look-ahead)
        double cz = p.tz() - b.z();
        double dy = (p.ty() - bias) - b.y();       // depth pitch (same as swimPitched)
        double d  = Math.sqrt(cx * cx + cz * cz);
        if (d < EPS) {
            b.faceTowards(0.0, dy, 0.0);
            b.setForward(0.0f);
        } else {
            b.faceTowards(cx, dy, cz);             // pitch for depth + yaw toward center
            b.setForward((float) Math.min(1.0, d)); // proportional: eases to 0 at center, re-faces + pushes back on overshoot
        }
    }

    /**
     * Prone sprint-swim AGGRESSIVE horizontal-turn brake — the wall-adjacent corner bleed. Where
     * {@link #swimPitchedCentered} is the gentle proportional brake safe for a dive/rise transition, this is the
     * hard decelerator reserved for a genuine HORIZONTAL turn (both the incoming and outgoing segments are
     * horizontal — see {@link #swimPitchedDirectional}), where the bot must shed its full cruise momentum to
     * hold a 1-wide lane through the corner or its 0.6 hitbox grazes the flank column and the bubble-up ejects
     * it. Two mechanisms stacked:
     * <ul>
     *   <li><b>Near-face aim.</b> A swim waypoint is "reached" when the FOOT block enters the cell (its near
     *       face, ~0.5 short of centre), so the cursor releases the brake half a cell early. Aiming at the near
     *       face ({@link #TURN_ARRIVE_OFFSET} back toward the incoming waypoint) puts the deceleration where the
     *       cursor actually hands off, so the bot arrives centred instead of coasting through into the wall.</li>
     *   <li><b>Reverse-brake zone.</b> Inside {@link #TURN_REVERSE_ZONE} of the arrive point the bot faces
     *       up-track (AWAY from the arrive point) and pushes ({@link #TURN_REVERSE_MAX}-bounded), actively
     *       killing the incoming momentum rather than merely coasting — the coast+drag alone can't stop a cruise
     *       in a single cell. Bounded so residual momentum still carries it across the face (no stall).</li>
     * </ul>
     * A 90-degree horizontal turn continues AROUND (momentum carries the bot through the corner), so it tolerates
     * this hard brake without stalling — unlike a dive/rise, which {@link #swimPitchedDirectional} keeps on the
     * gentle {@link #swimPitchedCentered}.
     */
    public static void swimPitchedBraked(BotSteering b, SteerView p, double bias) {
        double segx = p.tx() - p.sx(), segz = p.tz() - p.sz();
        double sl = Math.sqrt(segx * segx + segz * segz);
        double aimx = p.tx(), aimz = p.tz();
        if (sl > EPS) {
            aimx -= (segx / sl) * TURN_ARRIVE_OFFSET;  // near-face arrive point (pulled back toward incoming wp)
            aimz -= (segz / sl) * TURN_ARRIVE_OFFSET;
        }
        double cx = aimx - b.x();                  // vector bot → arrive point
        double cz = aimz - b.z();
        double dy = (p.ty() - bias) - b.y();       // depth pitch (same as swimPitched)
        double d  = Math.sqrt(cx * cx + cz * cz);
        if (d < EPS) {
            b.faceTowards(0.0, dy, 0.0);
            b.setForward(0.0f);
            return;
        }
        // Aim at the near-face arrive point — corrects BOTH the cross-track (drift toward the flank hazard) and,
        // on an overshoot, faces the bot back toward centre (never away, which would drive it into the hazard).
        b.faceTowards(cx, dy, cz);
        // Crawl throttle: capped low so a fast bot decelerates into the corner while a slow bot still creeps
        // across the cell face; eases to 0 right at the arrive point.
        double throttle = d < TURN_CRAWL_THROTTLE ? d : TURN_CRAWL_THROTTLE;
        b.setForward((float) throttle);
    }

    /**
     * Prone sprint-swim DIRECTIONAL drive — the owner's <b>hazard-aware</b> cruise (how a human threads the
     * maze). A straight run (next segment in-line) is full-throttle pursuit ({@link #swimPitched}). At a TURN,
     * the drive brakes to a crawl ({@link #swimPitchedBraked}) <b>only if overshooting the corner would carry
     * the bot into a hazard</b> ({@link #overshootHazard}: a bubble column / lava within a cell or two straight
     * ahead) — the bubble-up ejection the maze punishes. When the overshoot is harmless (a solid wall stops the
     * bot for free, or it is open safe water), the corner is taken at FULL speed, exactly like a straight — so
     * the drive no longer stutters every harmless turn (which slowed the harness without helping the maze). The
     * last segment (no look-ahead) is a plain pursuit. Same depth {@code bias} so pitch cooperates with holdDepth.
     */
    public static void swimPitchedDirectional(BotSteering b, SteerView p, double bias) {
        // Crawl approaching DANGER: (a) a hazard lies within HAZARD_LOOKAHEAD cells straight AHEAD in the travel
        // direction — a wall/column the bot is barrelling toward (whether the path turns here or the lane simply
        // ends at it), so it must arrive slow enough to turn without overshooting into it; or (b) a lane FLANK is
        // a hazard AND the bot has already drifted off-centre toward it (cross-track beyond FLANK_DRIFT), the
        // corner-departure case where perpendicular momentum carries it into the flank column. A CENTRED bot on
        // an open straight (no hazard ahead, none flanking it that it's drifting into) runs at FULL speed.
        boolean crawl = overshootHazard(b, p)
                || (flankHazard(b, p) && crossTrack(b, p) > FLANK_DRIFT);
        if (crawl) swimPitchedBraked(b, p, bias);                    // approaching danger -> crawl centred
        else swimPitched(b, p, bias);                                // safe -> full speed
    }

    /** The current segment's horizontal travel frame into scratch {@code F}; false if degenerate (a dive/rise). */
    private static boolean travelFrame(SteerView p) {
        double cdx = p.tx() - p.sx(), cdz = p.tz() - p.sz();
        double cl = Math.sqrt(cdx*cdx + cdz*cdz);
        if (cl < EPS) return false;
        F.ux = cdx / cl; F.uz = cdz / cl;
        F.cx = (int) Math.floor(p.tx());
        F.cz = (int) Math.floor(p.tz());
        F.cy = (int) Math.floor(p.ty()) - 1;   // waypoint floor cell (feet cell is water above)
        return true;
    }

    /** Whether barrelling PAST the turn waypoint in the current travel direction hits a hazard within
     *  {@link #HAZARD_LOOKAHEAD} cells (the corner-overshoot ejection). */
    private static boolean overshootHazard(BotSteering b, SteerView p) {
        if (!travelFrame(p)) return false;
        for (int k = 1; k <= HAZARD_LOOKAHEAD; k++) {
            if (hazardColumn(b, F.cx + (int) Math.round(F.ux * k), F.cy, F.cz + (int) Math.round(F.uz * k))) {
                return true;
            }
        }
        return false;
    }

    /** Whether either cell one step perpendicular to travel (the lane flanks at the waypoint) is a hazard —
     *  the bubble-walled 1-wide lane the bot must not drift into. */
    private static boolean flankHazard(BotSteering b, SteerView p) {
        if (!travelFrame(p)) return false;
        int fx = (int) Math.round(-F.uz), fz = (int) Math.round(F.ux);   // rotate travel dir 90 deg
        return hazardColumn(b, F.cx + fx, F.cy, F.cz + fz) || hazardColumn(b, F.cx - fx, F.cy, F.cz - fz);
    }

    /** A hazard anywhere in the short swim-body column at {@code (x, y +/- 1, z)} (a bubble column spans water). */
    private static boolean hazardColumn(BotSteering b, int x, int y, int z) {
        return b.swimHazardAt(x, y, z) || b.swimHazardAt(x, y + 1, z) || b.swimHazardAt(x, y - 1, z);
    }

    /**
     * Upright surface-swim DIRECTIONAL drive (YAW-ONLY): the yaw-only counterpart of
     * {@link #swimPitchedDirectional} for the tall standing {@link com.orebit.mod.pathfinding.blockpathfinder.movements.Swim}
     * pose, which owns its depth separately via {@link #holdDepth} (no depth pitch here). On a STRAIGHT run
     * (the next segment continues nearly in line with the current one) it drives full-throttle look-ahead
     * pursuit ({@link #swimTowards}) so a long straight never stutters from center-braking every waypoint;
     * only when the path actually TURNS — or, critically, DIVES (a degenerate/vertical next segment) — at the
     * current waypoint does it center-brake ({@link #recenterOnTarget}) to bleed momentum so the bot arrives
     * CENTERED on the initiation cell instead of overshooting into an adjacent hazard (the bubble-up ejection:
     * the prone hitbox's leading edge clipped the up-column before the dive began). The last segment (no
     * look-ahead) is a plain pursuit.
     */
    public static void swimTowardsDirectional(BotSteering b, SteerView p) {
        if (!p.hasNext()) { swimTowards(b, p); return; }             // last segment → normal pursuit
        double cdx = p.tx() - p.sx(), cdz = p.tz() - p.sz();         // current segment dir
        double cl = Math.sqrt(cdx*cdx + cdz*cdz);
        if (cl < EPS) { swimTowards(b, p); return; }                 // degenerate current → normal pursuit
        double ndx = p.nx() - p.tx(), ndz = p.nz() - p.tz();         // next segment dir
        double nl = Math.sqrt(ndx*ndx + ndz*ndz);
        if (nl < EPS) { recenterOnTarget(b, p); return; }            // next is a vertical dive → brake to center
        double dot = (cdx*ndx + cdz*ndz) / (cl * nl);
        if (dot >= STRAIGHT_DOT) swimTowards(b, p);                  // next in line → full speed (no stutter)
        else recenterOnTarget(b, p);                                 // turn → brake to center
    }

    /**
     * The water-column depth autopilot: press the inputs a player would to bring the bot's feet to the
     * planned depth ({@code path.ty() - bias}). Below it (past the {@link #WATER_RISE_DEADBAND dead-band}) →
     * hold jump (vanilla {@code jumpInLiquid} rises +0.04/t); above it → {@link BotSteering#sinkInWater}
     * (the client-only -0.04 down-swim a headless bot must replicate). No-op out of water — a move that
     * just exited onto a bank must not hop. This is how the bot dives to a submerged hole, holds depth,
     * surfaces, and climbs out: called by each water-capable move's {@code steer} (the four swim moves with
     * their pose's bias, and {@link #drive}'s in-water branch for ground moves crossing/exiting water).
     * (s52: relocated from the follower's cross-cutting water rule — movements own their controls.)
     */
    public static void holdDepth(BotSteering b, SteerView p, double bias) {
        if (!b.inWater() && !b.inLava()) { // the autopilot works in ANY fluid (lava swims like slow water)
            return;
        }
        final double depth = p.ty() - bias;
        if (b.y() < depth - WATER_RISE_DEADBAND) {
            b.setJumping(true);
        } else if (b.y() > depth + WATER_RISE_DEADBAND) {
            b.sinkInWater();
        }
    }

    /**
     * The generic locomotion actuator chosen by medium: on land, the input-based line-tracking walk
     * ({@link #steerTowards}); in water, the horizontal swim drive ({@link #swimTowards}) plus the
     * {@link #holdDepth depth-hold} at the planned feet height (bias 0) — so a ground move still submerged
     * (leaving water onto a bank, clipping a stream, knocked into a pool mid-segment) is steered toward the
     * exit AND lifted/sunk toward its planned cell instead of stalling at buoyancy equilibrium.
     */
    public static void drive(BotSteering b, SteerView p) {
        if (b.inWater()) {
            swimTowards(b, p);
            holdDepth(b, p, 0.0);
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
