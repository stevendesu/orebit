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

    /**
     * A/B + revert switch for {@link #drive}'s LAND branch (the chokepoint the ground moves Traverse/Descend/
     * Diagonal steer through): {@code "servo"} (default) = the input-only velocity {@link #groundServo} (hazard-
     * aware target-velocity with reverse-thrust braking — holds a 1-wide blue-ice lane); {@code "legacy"} = the
     * old open-loop {@link #steerTowards} (full-forward look-ahead, overshoots on ice). Mirrors SprintSwim's
     * {@code orebit.swim.bleed} servo A/B switch. Set {@code -Dorebit.ground.drive=legacy} to revert. Momentum-
     * critical moves (parkour arc, Ascend-climb, Fall-walkoff) call {@code steerTowards} DIRECTLY (bypassing
     * {@code drive}) and are UNAFFECTED by this. Promoted to default after the walk-off-void hazard was made
     * off-path/overshoot-directional (a planned Descent no longer mistaken for a void to avoid) and broad
     * re-verification: HeadlessAutotest descends off the start ledge + progresses at parity with legacy, ice
     * iceturn PASS, swim harness 17/17, parkour unregressed (43/53, identical planner-refusals to legacy). */
    private static final String GROUND_DRIVE = System.getProperty("orebit.ground.drive", "servo");

    // ---- velocity-servo cruise (swimServo) constants -------------------------------------------------
    /**
     * Desired-speed CEILING (blocks/tick) for the velocity servo on a safe straight. Set ABOVE the sprint-swim
     * terminal (~0.26 b/t measured on a straight) so the velocity error {@code desired - current} stays large
     * enough that the proportional forward key SATURATES to full ahead on every safe straight — i.e. the servo
     * cruises a straight exactly as hard as the open-loop drive, and the clamp only ever bites inside the
     * hazard-corner speed ramp (where {@code min(cruise, ramp*dist)} takes the ramp term). A pure-P servo can't
     * hold a speed equal to its own ceiling (steady-state error), so the ceiling is deliberately unreachable. */
    static final double SERVO_CRUISE = 0.35;
    /** Servo forward-key gain: {@code forward = clamp(SERVO_GAIN * |velocityError|, 0, 1)}. Large enough that a
     *  ~0.05 b/t error already saturates, so acceleration (under-speed) and braking (overshoot → reverse thrust)
     *  are both crisp; the hazard speed ramp — not this gain — sets the arrival speed. */
    static final double SERVO_GAIN = 18.0;
    /** Servo dead-band (b/t): below this velocity error the servo coasts (forward 0) and merely holds heading —
     *  bang-bang hysteresis so a bot at its desired velocity doesn't chatter the forward key on/off. */
    static final double SERVO_DEADBAND = 0.02;
    /** Hazard speed-ramp rate (b/t of desired speed per block of distance to the hazard corner): the desired
     *  speed is {@code min(SERVO_CRUISE, SERVO_HAZARD_RAMP * distanceToCorner)}, so the bot decelerates smoothly
     *  to ~0 as it reaches a hazardous turn instead of a cliff-stop. Only applied when the corner's overshoot is
     *  a hazard (reusing {@link #overshootHazard}/{@link #flankHazard}); a harmless turn keeps full cruise. */
    static final double SERVO_HAZARD_RAMP = 0.16;
    /**
     * Hazard-corner creep FLOOR (b/t): the velocity-servo counterpart of {@link #TURN_CRAWL_THROTTLE}. The
     * hazard speed ramp is clamped to never target BELOW this, so at a run of consecutive hazard waypoints (a
     * bubble-walled maze channel) the bot holds a steady crawl through the corners instead of dead-stopping at
     * each one and paying a slow re-acceleration from standstill (the swimturn stall). Small enough that the
     * crawl still can't overshoot a 1-wide lane into the flank column, large enough to keep the swim cursor
     * advancing — the same "creep, never stall" balance {@link #TURN_CRAWL_THROTTLE} strikes as a throttle cap,
     * but expressed as a target SPEED the servo actively holds (reverse-thrust included) rather than a cap. */
    static final double SERVO_TURN_FLOOR = 0.11;
    /**
     * Corner-blend onset distance (blocks): within this range of the turn waypoint the servo's desired-velocity
     * DIRECTION starts rotating from the current segment toward the NEXT one (the {@link SteerView} look-ahead),
     * so the bot carries diagonal velocity through the corner (efficiency: no stop-and-go; client-portability:
     * some forward is always held, keeping the prone-sprint pose client-legal). Beyond this the drive is pure
     * current-segment pursuit (full-speed straight). */
    static final double CORNER_BLEND_DIST = 1.3;
    /** Max corner-blend weight toward the next leg (the {@code w} in {@code (1-w)*current + w*next}). */
    static final double CORNER_BLEND_MAX = 0.55;
    /**
     * OUTSIDE racing-line bias: the corner blend also pushes the desired direction toward the OUTSIDE of the
     * turn (the side opposite the turn), scaled by the same proximity weight, so the bot rounds the corner on a
     * WIDER radius and keeps its 0.6-wide hitbox off the INSIDE flank/corner column (the clip that ejects a
     * prone swimmer — the actual correctness concern, not going slow). Pure next-leg blending alone cuts to the
     * inside; this outward term is what makes the diagonal safe near a bubble/lava flank. */
    static final double CORNER_RACING_BIAS = 0.5;
    /**
     * Client-legal FORWARD-INPUT floor: the servo never fully releases the forward key (W) while the bot is
     * prone-sprint-swimming and airborne (in water, not on ground). The vanilla CLIENT keeps the prone
     * sprint-swim pose only while {@code hasForwardImpulse || onGround || shift}, so a future CLIENT-controlled
     * bot must hold W to stay prone — this floor keeps the servo's input pattern portable to that case. It costs
     * nothing on the server (there is no server-side deadband) and does NOT compromise braking: the servo brakes
     * by REVERSE-THRUST (facing against its motion — the velocity error points up-track on an overshoot — with W
     * still held), so W stays pressed the whole time and a true throttle cut is never needed. Tiny, so the
     * residual forward trickle at a dead-stop corner is negligible. */
    static final double SERVO_FORWARD_MIN = 0.08;
    /**
     * GROUND velocity-servo desired-speed CEILING (blocks/tick) — the land counterpart of {@link #SERVO_CRUISE}.
     * Set ABOVE the land-sprint terminal (~0.28 b/t) AND the plain-walk terminal (~0.216 b/t) so on ordinary
     * friction the velocity error {@code desired - current} never goes negative and the servo saturates forward
     * exactly like the open-loop walk — i.e. the servo NEVER slows the bot below its natural land speed, so it is
     * a pure no-op on normal ground. On low-friction blue ice the natural coast blows PAST this ceiling, so there
     * the servo bites: it reverse-thrusts to CAP the runaway ice momentum at the ceiling (safe straight) and the
     * hazard ramp brings it down further into a corner — the whole point of the ground servo (hold a 1-wide ice
     * lane instead of sliding off). No depth pitch (YAW-ONLY): land has no vertical swim control. */
    static final double SERVO_GROUND_CRUISE = 0.35;
    /**
     * GROUND hazard-corner CROSS-TRACK return gain + cap (blocks/tick per block of cross-track). At a slippery
     * hazard corner the two 1-wide legs' centerlines are offset 0.5 block in the perpendicular axis, so the bot
     * enters the new leg with ~0.5 block of cross-track error. On near-frictionless ice a diagonal aim at the
     * pivot gives almost NO centering thrust once the along speed is ramped low (the desired cross-velocity ≈ the
     * bot's current), so the bot advances along the leg and clips the inside flank before it re-centres. These
     * drive a DEDICATED cross-track return term — {@code min(CAP, GAIN*cte)} toward the centerline, INDEPENDENT of
     * the (low) along speed — the ice lane-hold lever. Capped so a recovered bot doesn't fling past centre into
     * the FAR flank (the reverse-brake mops up the residual). */
    static final double SERVO_CROSS_GAIN = 0.75;
    static final double SERVO_CROSS_CAP  = 0.13;
    /**
     * GROUND hazard-corner ALONG-track HALT scale: the cross-track error (blocks) at which the along-track advance
     * is throttled to its floor factor, so a badly off-centre bot RE-CENTRES before advancing down the new leg
     * (rather than sliding along it into the inside flank). {@code alongFactor = max(HALT_FLOOR, 1 - cte/CTE_HALT)}
     * — full along when centred, ~floor when a full corner-offset off. The bot never dead-stops (it is still
     * sliding cross-track toward centre — legal input, not a stall). */
    static final double SERVO_CTE_HALT   = 0.40;
    static final double SERVO_ALONG_HALT_FLOOR = 0.0;

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

    /**
     * Prone sprint-swim <b>velocity SERVO</b> horizontal drive — the input-only, velocity-feedback alternative
     * to the position-based {@link #swimPitchedDirectional}. Instead of easing the forward key by DISTANCE to a
     * waypoint (open-loop), it closes the loop on the bot's actual momentum: it computes a horizontal velocity
     * ERROR {@code desired - current}, FACES along that error, and presses the forward key in proportion to its
     * magnitude — so vanilla water drag is fought with forward thrust to HOLD speed, and an overshoot is killed
     * with REVERSE thrust (the error points up-track → the yaw flips 180° → the W key becomes a brake). No
     * velocity is ever written; only look + forward, exactly as a player steers.
     *
     * <ul>
     *   <li><b>Desired direction</b> = the swim pursuit vector {@code (G.q - bot)} from {@link #computeGeom}
     *       with the swim cross-track gain — along-track advance PLUS the cross-track return toward the lane
     *       centerline, so the servo holds the 1-wide bubble lane the same way the cruise does.</li>
     *   <li><b>Desired direction</b> also ROUNDS the corner: near a turn it blends toward the next leg with an
     *       OUTSIDE racing-line bias ({@link #CORNER_BLEND_MAX}/{@link #CORNER_RACING_BIAS}) so the bot carries
     *       diagonal velocity through the corner on a WIDE radius — efficiency (no stop-and-go) and keeping the
     *       0.6-wide hitbox off the inside flank column (the clip = the ejection).</li>
     *   <li><b>Desired speed</b> = a HAZARD-AWARE profile: {@link #SERVO_CRUISE} on a safe straight (unreachable
     *       ceiling → forward saturates → full cruise), ramped DOWN as the bot nears a hazardous turn
     *       ({@code min(cruise, max(SERVO_TURN_FLOOR, SERVO_HAZARD_RAMP * dist))}) so it can't coast through
     *       into the flank hazard, but the ramp is clamped to a creep FLOOR ({@link #SERVO_TURN_FLOOR}) so a run
     *       of consecutive hazard corners holds a steady crawl rather than dead-stopping and re-accelerating at
     *       each one (the swimturn stall). Same {@link #overshootHazard}/{@link #flankHazard} probes as the
     *       directional cruise.</li>
     *   <li><b>Vertical</b> is unchanged from {@link #swimPitched}: the look PITCH aims at the depth target
     *       {@code p.ty() - bias}, and the CALLER adds {@link #holdDepth} for the jump/sink. The servo owns only
     *       horizontal momentum.</li>
     * </ul>
     * A degenerate (vertical) segment collapses to a pure depth pitch with no horizontal push, like
     * {@link #swimPitched}.
     */
    public static void swimServo(BotSteering b, SteerView p, double bias) {
        computeGeom(b, p, SWIM_CTE_GAIN);
        double dy = (p.ty() - bias) - b.y();               // depth pitch target (same as swimPitched)
        if (G.segLen < EPS) {                              // pure vertical: no horizontal servo, just dive/rise
            b.faceTowards(0.0, dy, 0.0);
            b.setForward(0.0f);
            return;
        }
        // Desired travel DIRECTION: the pursuit vector (along-track + cross-track return toward the centerline).
        double dirx = G.qx - b.x(), dirz = G.qz - b.z();
        double dl = Math.sqrt(dirx * dirx + dirz * dirz);
        if (dl < EPS) { b.faceTowards(0.0, dy, 0.0); b.setForward(0.0f); return; }
        dirx /= dl; dirz /= dl;

        // Smooth DIAGONAL corner: as the bot nears the turn waypoint, rotate the desired direction from this
        // segment toward the NEXT one, with an OUTSIDE racing-line bias so it rounds WIDE and keeps the hitbox
        // off the inside flank column (the clip = the ejection). Weight grows with proximity to the corner.
        if (p.hasNext()) {
            double ndx = p.nx() - p.tx(), ndz = p.nz() - p.tz();
            double nl = Math.sqrt(ndx * ndx + ndz * ndz);
            if (nl > EPS) {                                // next leg horizontal (a vertical dive doesn't blend)
                ndx /= nl; ndz /= nl;
                double ccx = p.tx() - b.x(), ccz = p.tz() - b.z();
                double dCorner = Math.sqrt(ccx * ccx + ccz * ccz);
                double w = (CORNER_BLEND_DIST - dCorner) / CORNER_BLEND_DIST;
                if (w > CORNER_BLEND_MAX) w = CORNER_BLEND_MAX;
                if (w > 0.0) {
                    // Outward normal = the side OPPOSITE the turn. cross = dir × next (y-component): >0 left turn
                    // (outside is right), <0 right turn (outside is left). Right-hand perp of dir is (dz,-dx).
                    double cross = dirx * ndz - dirz * ndx;
                    double sgn = cross > 0 ? 1.0 : (cross < 0 ? -1.0 : 0.0);
                    double outx = sgn * dirz, outz = -sgn * dirx;   // unit outward normal
                    double bx = (1.0 - w) * dirx + w * ndx + CORNER_RACING_BIAS * w * outx;
                    double bz = (1.0 - w) * dirz + w * ndz + CORNER_RACING_BIAS * w * outz;
                    double bl = Math.sqrt(bx * bx + bz * bz);
                    if (bl > EPS) { dirx = bx / bl; dirz = bz / bl; }
                }
            }
        }

        // Desired SPEED: full cruise on a safe straight; ramp DOWN approaching a HAZARD corner, but clamp the
        // ramp to a creep FLOOR so a maze channel of consecutive corners holds a crawl instead of stalling at
        // each. The tight centerline pursuit above is the correctness lever (don't clip the column); the speed
        // ramp just prevents an overshoot-through, and the floor keeps the corner from a full re-accel stall.
        double cruise = SERVO_CRUISE;
        boolean hazardCorner = overshootHazard(b, p) || (flankHazard(b, p) && crossTrack(b, p) > FLANK_DRIFT);
        if (hazardCorner) {
            double segx = p.tx() - p.sx(), segz = p.tz() - p.sz();
            double sl = Math.sqrt(segx * segx + segz * segz);
            double aimx = p.tx(), aimz = p.tz();
            if (sl > EPS) {                                // near-face arrive point (as swimPitchedBraked aims)
                aimx -= (segx / sl) * TURN_ARRIVE_OFFSET;
                aimz -= (segz / sl) * TURN_ARRIVE_OFFSET;
            }
            double dcx = aimx - b.x(), dcz = aimz - b.z();
            // Hazard speed-ramp, clamped to the creep FLOOR: at a run of consecutive hazard corners (a maze
            // channel) the target never drops below SERVO_TURN_FLOOR, so the bot holds a steady crawl through
            // the turns instead of dead-stopping and paying a slow re-accel from standstill (the swimturn stall).
            double ramp = Math.max(SERVO_TURN_FLOOR, SERVO_HAZARD_RAMP * Math.sqrt(dcx * dcx + dcz * dcz));
            cruise = Math.min(SERVO_CRUISE, ramp);
        }

        // Velocity error = desired - current (horizontal). Face ALONG the error, thrust proportional to |error|:
        // under-speed → forward thrust; overshoot → error points up-track → yaw flips → reverse-thrust brake.
        double errx = dirx * cruise - b.velX();
        double errz = dirz * cruise - b.velZ();
        double emag = Math.sqrt(errx * errx + errz * errz);
        double fwd;
        if (emag < SERVO_DEADBAND) {
            b.faceTowards(dirx, dy, dirz);                 // at speed: hold heading + depth pitch, coast
            fwd = 0.0;
        } else {
            b.faceTowards(errx / emag, dy, errz / emag);   // unit error dir → stable depth-pitch reference
            fwd = Math.min(1.0, SERVO_GAIN * emag);
        }
        // Client-legal forward-input floor: never release W while prone + in water + airborne (a client keeps
        // the prone pose only with hasForwardImpulse held). Braking is by facing (reverse-thrust) above, so W
        // stays held — this floor just guarantees it's never exactly 0. Grounded/out-of-water: no floor.
        if (b.prone() && b.inWater() && !b.grounded()) fwd = Math.max(fwd, SERVO_FORWARD_MIN);
        b.setForward((float) fwd);
    }

    /**
     * GROUND <b>velocity SERVO</b> horizontal drive (YAW-ONLY) — the land counterpart of {@link #swimServo}, the
     * input-only velocity-feedback alternative to the open-loop {@link #steerTowards} the ground moves
     * (Traverse/Descend/Diagonal) drive through {@link #drive}. Where {@code steerTowards} just faces the
     * look-ahead pursuit point and holds full forward — which on low-friction blue ice lets the carried momentum
     * coast the bot off a 1-wide path at a corner into the flanking lava/void — this closes the loop on the bot's
     * ACTUAL momentum: it computes a horizontal velocity ERROR {@code desired - current}, FACES along that error,
     * and presses forward in proportion to its magnitude, so ice friction is fought with forward thrust to HOLD a
     * capped speed and an overshoot is killed with REVERSE thrust (the error points up-track → the yaw flips 180°
     * → the W key becomes a brake — essential on ice, where merely releasing forward coasts forever). No velocity
     * is ever written; only look + forward, exactly as a player steers. NO depth pitch (land is 2-D).
     *
     * <ul>
     *   <li><b>Desired direction</b> = the pursuit vector {@code (G.q - bot)} from {@link #computeGeom} (along-track
     *       advance + cross-track return toward the centerline), blended near a turn toward the NEXT leg with an
     *       OUTSIDE racing-line bias ({@link #CORNER_BLEND_MAX}/{@link #CORNER_RACING_BIAS}) so the bot rounds the
     *       corner wide and keeps its hitbox off the inside flank — identical geometry to {@link #swimServo}.</li>
     *   <li><b>Desired speed</b> = {@link #SERVO_GROUND_CRUISE} on a safe straight (an unreachable ceiling on normal
     *       ground → the servo is a no-op there; on ice it caps the runaway coast), ramped DOWN toward a hazardous
     *       turn ({@code min(cruise, max(SERVO_TURN_FLOOR, SERVO_HAZARD_RAMP*dist))}) so the bot can't coast through
     *       into a flank hazard, clamped to a creep FLOOR so a run of corners holds a crawl rather than dead-stopping.
     *       The ground hazard is LAVA <i>or</i> a would-fall VOID ({@link #groundOvershootHazard}/
     *       {@link #groundFlankHazard} — the overshoot cell has no standable floor: the bot would walk off the
     *       1-wide ice into the pit).</li>
     * </ul>
     * A degenerate (vertical/in-place) segment collapses to {@link #recenterOnTarget}, exactly like
     * {@link #steerTowards}.
     */
    public static void groundServo(BotSteering b, SteerView p) {
        computeGeom(b, p);                                 // ground: fixed look-ahead (gain 0), like steerTowards
        if (G.segLen < EPS) {
            recenterOnTarget(b, p);                        // no line to track → re-centre on the column
            return;
        }
        double dirx = G.qx - b.x(), dirz = G.qz - b.z();   // pursuit direction (along-track + cross-track return)
        double dl = Math.sqrt(dirx * dirx + dirz * dirz);
        if (dl < EPS) { recenterOnTarget(b, p); return; }
        dirx /= dl; dirz /= dl;

        // Hazard-corner check FIRST — it selects the CORNERING LINE. The ground hazard is LAVA or a would-fall
        // VOID; near it a wide racing line is fatal on near-frictionless ICE, where the momentum a blend injects
        // toward the next leg PERSISTS (water drag bled it for swimServo; blue ice at slip 0.98 does not), sliding
        // the 0.6 hitbox off the inside flank before the bot re-centres — the inside-corner cut.
        boolean hazardCorner = groundOvershootHazard(b, p)
                || (groundFlankHazard(b, p) && crossTrack(b, p) > FLANK_DRIFT);

        // Desired VELOCITY (dvx,dvz): the servo tracks this against the bot's actual momentum below.
        double cruise = SERVO_GROUND_CRUISE;
        double dvx, dvz;
        if (hazardCorner) {
            // TIGHT ice line, in the LEG FRAME. Decompose desired velocity into ALONG-track (throttled low into the
            // corner AND further throttled while off-centre, so the bot re-centres before advancing) + a DEDICATED
            // CROSS-track return toward the centerline whose authority is INDEPENDENT of the low along speed. This
            // is the ice lane-hold: a plain low-cruise diagonal aim gives near-zero centering thrust, so the bot
            // slides along the new leg into the inside flank before re-centring (the retained-momentum inside cut).
            double segx = p.tx() - p.sx(), segz = p.tz() - p.sz();
            double sl = Math.sqrt(segx * segx + segz * segz);
            double ux = segx / sl, uz = segz / sl;                       // leg unit (sl>EPS: G.segLen>=EPS above)
            double along = (b.x() - p.sx()) * ux + (b.z() - p.sz()) * uz;
            if (along < 0.0) along = 0.0; else if (along > sl) along = sl;
            double fx = p.sx() + ux * along, fz = p.sz() + uz * along;   // nearest centerline point
            double crx = fx - b.x(), crz = fz - b.z();                   // toward the centerline
            double cte = Math.sqrt(crx * crx + crz * crz);

            // ALONG speed: hazard ramp toward the near-face arrive point, floor-clamped, THEN scaled down by the
            // cross-track error so a badly off-centre bot barely advances until it is back on the centerline.
            double aimx = p.tx() - ux * TURN_ARRIVE_OFFSET, aimz = p.tz() - uz * TURN_ARRIVE_OFFSET;
            double dcx = aimx - b.x(), dcz = aimz - b.z();
            double alongSpeed = Math.min(SERVO_GROUND_CRUISE,
                    Math.max(SERVO_TURN_FLOOR, SERVO_HAZARD_RAMP * Math.sqrt(dcx * dcx + dcz * dcz)));
            double alongFactor = Math.max(SERVO_ALONG_HALT_FLOOR, 1.0 - cte / SERVO_CTE_HALT);
            alongSpeed *= alongFactor;

            // CROSS speed: strong return to the centerline, capped (the reverse-brake mops up any overshoot).
            double crossSpeed = cte > EPS ? Math.min(SERVO_CROSS_CAP, SERVO_CROSS_GAIN * cte) : 0.0;
            double cdirx = cte > EPS ? crx / cte : 0.0, cdirz = cte > EPS ? crz / cte : 0.0;

            dvx = ux * alongSpeed + cdirx * crossSpeed;
            dvz = uz * alongSpeed + cdirz * crossSpeed;
            double dvl = Math.sqrt(dvx * dvx + dvz * dvz);
            if (dvl > EPS) { dirx = dvx / dvl; dirz = dvz / dvl; }       // heading for the coast/deadband branch
        } else {
            if (p.hasNext()) {
                // Safe corner: rotate the desired direction toward the next leg near the turn, with an OUTSIDE
                // racing-line bias so the bot rounds WIDE for efficiency (harmless where no flank hazard).
                double ndx = p.nx() - p.tx(), ndz = p.nz() - p.tz();
                double nl = Math.sqrt(ndx * ndx + ndz * ndz);
                if (nl > EPS) {
                    ndx /= nl; ndz /= nl;
                    double ccx = p.tx() - b.x(), ccz = p.tz() - b.z();
                    double dCorner = Math.sqrt(ccx * ccx + ccz * ccz);
                    double w = (CORNER_BLEND_DIST - dCorner) / CORNER_BLEND_DIST;
                    if (w > CORNER_BLEND_MAX) w = CORNER_BLEND_MAX;
                    if (w > 0.0) {
                        double cross = dirx * ndz - dirz * ndx;
                        double sgn = cross > 0 ? 1.0 : (cross < 0 ? -1.0 : 0.0);
                        double outx = sgn * dirz, outz = -sgn * dirx;   // unit outward normal
                        double bx = (1.0 - w) * dirx + w * ndx + CORNER_RACING_BIAS * w * outx;
                        double bz = (1.0 - w) * dirz + w * ndz + CORNER_RACING_BIAS * w * outz;
                        double bl = Math.sqrt(bx * bx + bz * bz);
                        if (bl > EPS) { dirx = bx / bl; dirz = bz / bl; }
                    }
                }
            }
            dvx = dirx * cruise;                                         // safe: full-cruise pursuit heading
            dvz = dirz * cruise;
        }

        // Velocity error = desired - current (horizontal). Face ALONG the error, thrust proportional to |error|:
        // under-speed → forward thrust; overshoot → error points up-track → yaw flips → reverse-thrust brake.
        double errx = dvx - b.velX();
        double errz = dvz - b.velZ();
        double emag = Math.sqrt(errx * errx + errz * errz);
        if (emag < SERVO_DEADBAND) {
            b.faceHorizontally(dirx, dirz);                // at speed: hold heading, coast
            b.setForward(0.0f);
        } else {
            b.faceHorizontally(errx, errz);                // face the velocity error (forward thrust or reverse brake)
            b.setForward((float) Math.min(1.0, SERVO_GAIN * emag));
        }
    }

    // ---- parkour predictive-airborne servo constants (see parkourAirborne) ---------------------------
    /** Vanilla sprint horizontal ground-accel (the {@code a} in the airborne recurrence); walk is {@link
     *  #PARKOUR_A_WALK}. Both feed the arc predictor and match the follower's held sprint state. */
    static final double PARKOUR_A_SPRINT = 0.026;
    static final double PARKOUR_A_WALK = 0.02;
    /** Airborne horizontal drag / vertical drag / gravity — the verified 1.21.11 constants (spec §physics):
     *  {@code v←(v+0.98·a·dir)·0.91}, {@code vy←(vy−0.08)·0.98}, displacement uses {@code v_t}. */
    static final double PARKOUR_H_DRAG = 0.91;
    static final double PARKOUR_V_DRAG = 0.98;
    static final double PARKOUR_GRAVITY = 0.08;
    static final double PARKOUR_INPUT = 0.98; // the 0.98 multiplying the accel input in the recurrence
    /** Predictor loop cap (ticks) — the longest shipped parkour arc is ~18 t (a −4 fall); 30 is slack. LATENT
     *  cap: no shipped arc exceeds it, so it never truncates a real prediction; were a deeper-fall arc ever
     *  offered, hitting the cap returns an early (shorter) along-position that only biases the servo toward
     *  braking — and the bot still physically lands by gravity, so the cap can never cause an under-shoot. */
    static final int PARKOUR_PREDICT_MAXT = 30;
    /** Player half-width margin (blocks): "touchdown is on the cell" means the predicted along-axis landing
     *  sits within {@code [Cn+MARGIN, Cf−MARGIN]} of the 1-wide landing cell (Cn/Cf = near/far edge). The
     *  near-edge form {@code C−0.5+MARGIN} is the HARD floor the air-brake may never predict below (never
     *  brake the bot short into the gap/void). */
    static final double PARKOUR_CELL_MARGIN = 0.3;
    /** Predicted-touchdown dead-band (blocks): within this of the desired along-axis point the servo neither
     *  accelerates nor brakes (holds current along momentum) — hysteresis so it doesn't chatter thrust. */
    static final double PARKOUR_PREDICT_DEAD = 0.15;
    /** Along-axis desired-speed CEILING when the servo needs to ACCELERATE (predicted short): set above the
     *  sprint terminal so the forward key saturates, exactly like {@link #SERVO_GROUND_CRUISE}. */
    static final double PARKOUR_CRUISE = 0.35;
    /** Landing-block friction at/above which the surface is treated as ICE (can't brake post-touchdown), so
     *  the servo aims the cell CENTER/near-edge and brakes to arrive slow rather than carrying momentum to the
     *  far edge. Public so the FALLING airborne handoff (Parkour) can gate the ice-only servo path on it. */
    public static final double PARKOUR_ICE_SLIP = 0.98;
    /** How far past the cell CENTER (blocks, toward the far edge) a colinear continuation (a chain jump onto
     *  non-ice) aims its predicted touchdown — carries momentum for the next leg while staying within the cell
     *  ({@code < 0.5−MARGIN}). Zero on ice / at a turn / on arrival (aim dead-center). */
    static final double PARKOUR_CARRY_AHEAD = 0.2;
    /** Tighter near-edge margin (blocks) for the FALLING-onto-ICE aggressive path ({@link
     *  #parkourAirborne} 9-arg): smaller than the standard {@link #PARKOUR_CELL_MARGIN} half-width so the servo
     *  lands the bot nearer the near edge (more cell runway) and brakes earlier — the extra bite a 4-gap fall
     *  needs to arrest on a 1-wide frictionless cell. Kept a safe distance in from the edge; the full-reverse
     *  invariant still guarantees touchdown never falls short of it into the gap. */
    static final double PARKOUR_ICE_FALL_MARGIN = 0.15;

    /**
     * The parkour <b>predictive-airborne servo</b> — the closed-loop replacement for the open-loop "hold full
     * forward + sprint to touchdown" airborne drive (DESIGN-parkour-envelope; the ice-overshoot / short-flat
     * pathologies). Called every airborne (and, for flat/rising, land) tick by {@link
     * com.orebit.mod.pathfinding.blockpathfinder.movements.Parkour#plan}/{@link
     * com.orebit.mod.pathfinding.blockpathfinder.movements.DiagonalParkour#plan} with the jump-axis unit
     * {@code (ux,uz)} and the landing floor cell {@code (tx,ty,tz)}. It steers so the bot's PREDICTED along-axis
     * touchdown hits a chosen point in the landing cell, air-braking an overshoot with reverse-thrust and
     * accelerating a shortfall — input-only (look + forward), never a velocity write, exactly like the ground
     * and swim servos.
     *
     * <h2>Per tick</h2>
     * <ol>
     *   <li><b>Arc predictor</b> ({@link #predictAlongTouchdown}, allocation-free ≤{@link #PARKOUR_PREDICT_MAXT}-tick
     *       loop): integrate the verified 1.21.11 recurrence forward from the bot's current along-axis position
     *       {@code s} / along-axis velocity {@code v} / height {@code y} / {@code vy} under a policy (dir
     *       0/+1/−1) until the feet descend to the landing surface {@code ty+1}, returning the predicted
     *       along-axis touchdown {@code P}.</li>
     *   <li><b>Desired point</b> {@code D} (along-axis): the landing-cell CENTER {@code C} by default, shifted
     *       toward the far edge by {@link #PARKOUR_CARRY_AHEAD} for a COLINEAR non-ice continuation (a chain —
     *       carry momentum for the next leg), and pulled back to the NEAR edge ({@code C−0.5+}{@link
     *       #PARKOUR_CELL_MARGIN}) for a PURE ARRIVAL on ICE (no next waypoint — the STOP case), where friction
     *       won't arrest the touchdown speed so the servo must brake hardest and land furthest back to keep the
     *       bot on the 1-wide cell. Read from {@link SteerView#hasNext}/{@code nx}/{@code nz} + the landing
     *       block's {@link BotSteering#slipperinessAt slipperiness}.</li>
     *   <li><b>Control law</b>: predicted {@code P} short of {@code D} → accelerate ({@link #PARKOUR_CRUISE}
     *       forward); {@code P} past {@code D} → reverse-thrust brake, but ONLY if the predictor UNDER FULL
     *       REVERSE still lands at/beyond the near-edge floor {@code C−0.5+}{@link #PARKOUR_CELL_MARGIN} — the
     *       HARD INVARIANT that the brake never drops touchdown short into the gap/void. When braking would
     *       undershoot, the servo COASTS (preserves reach) and brakes a later tick, once the shrinking airtime
     *       makes a full-reverse touchdown land safely: this is what guarantees "brake as late as is safe,
     *       never into the gap." Sprint stays ON the whole arc (the caller holds it) — {@code a=0.026} works in
     *       reverse, so W is always pressed and the yaw alone flips forward↔brake.</li>
     *   <li><b>Cross-axis</b> centering toward the landing column centerline ({@link #SERVO_CROSS_GAIN}/
     *       {@link #SERVO_CROSS_CAP}, the ground-servo lever) folds into the desired velocity so a 1-wide
     *       landing lane is held.</li>
     * </ol>
     * The desired point is ALWAYS inside the landing cell (a real, arc-verified landing), so reverse-thrust
     * never aims the bot over a gap/lava column — the hazard-awareness the spec asks for falls out of "aim only
     * inside the landing cell" plus the near-edge invariant. Cold (tick-rate), small doubles only.
     */
    public static void parkourAirborne(BotSteering b, SteerView p, double ux, double uz,
                                       int tx, int ty, int tz, boolean sprint) {
        parkourAirborne(b, p, ux, uz, tx, ty, tz, sprint, false);
    }

    /**
     * As {@link #parkourAirborne(BotSteering, SteerView, double, double, int, int, int, boolean)} with an
     * {@code iceFallAggressive} lever for the FALLING-onto-ICE case (Phase 3): a falling jump's reach momentum
     * can't be fully bled inside a 1-wide zero-runout ice cell (the reach-vs-brake conflict), so on ICE it uses
     * a TIGHTER near-edge margin ({@link #PARKOUR_ICE_FALL_MARGIN}) — the invariant floor drops, so the servo
     * both starts reverse-braking EARLIER (more speed shed) and lands the bot FURTHER back on the cell (more
     * runway to arrest the residual slide). Still safe: the invariant is a FULL-REVERSE prediction, so actual
     * touchdown is guaranteed at/beyond the (tighter) near-edge floor — never into the gap. Flat/rising and
     * non-ice pass {@code false} (the standard 0.3 margin), so their behaviour is unchanged.
     */
    public static void parkourAirborne(BotSteering b, SteerView p, double ux, double uz,
                                       int tx, int ty, int tz, boolean sprint, boolean iceFallAggressive) {
        final double accel = sprint ? PARKOUR_A_SPRINT : PARKOUR_A_WALK;
        final double landY = ty + 1.0;                       // feet rest on the landing floor's top face
        // Along-axis frame: s = along position, v = along velocity; cross-axis = 90 deg left of the jump axis.
        final double s = b.x() * ux + b.z() * uz;
        final double v = b.velX() * ux + b.velZ() * uz;
        final double crossUx = -uz, crossUz = ux;
        final double C = (tx + 0.5) * ux + (tz + 0.5) * uz;              // landing centre, along-axis
        boolean ice = b.slipperinessAt(tx, ty, tz) >= PARKOUR_ICE_SLIP;
        // Near-edge margin: the tighter falling-ice value when armed + on ice (max runway / earliest brake),
        // else the standard player-half-width margin.
        final double margin = (iceFallAggressive && ice) ? PARKOUR_ICE_FALL_MARGIN : PARKOUR_CELL_MARGIN;
        final double cnSafe = C - 0.5 + margin;                        // hard near-edge floor (never predict below)

        // Desired along-axis point: carry momentum toward the far edge for a colinear non-ice chain, else centre.
        boolean colinear = false;
        if (p.hasNext()) {
            double ndx = p.nx() - p.tx(), ndz = p.nz() - p.tz();
            double nl = Math.sqrt(ndx * ndx + ndz * ndz);
            if (nl > EPS) colinear = (ndx * ux + ndz * uz) / nl >= STRAIGHT_DOT;
        }
        double d;
        if (colinear && !ice) {
            d = C + PARKOUR_CARRY_AHEAD;         // stone chain/sheet: carry momentum toward the next leg
        } else if (ice && !p.hasNext()) {
            // PURE ARRIVAL on a 1-wide ice cell (the STOP case, no next waypoint): friction won't stop the
            // bot post-touchdown, so aim the NEAR edge — the servo brakes hardest/earliest (slowest safe
            // landing) AND lands as far back as the invariant allows, giving the full cell width to arrest
            // the residual slide. (A faster centre-aimed landing slides off the far edge — the g3 case.)
            d = cnSafe;
        } else {
            d = C;                               // stone arrival/turn, ICE turn (has-next redirect bleeds it),
                                                 // or ICE colinear chain (keep momentum for the next leg)
        }
        if (d > C + 0.5 - margin) d = C + 0.5 - margin; // keep the aim inside the cell

        // Predict the neutral-coast touchdown, then choose the along-axis desired velocity.
        double pNeutral = predictAlongTouchdown(s, v, b.y(), b.velY(), landY, 0, accel);
        double desiredAlong;
        if (pNeutral < d - PARKOUR_PREDICT_DEAD) {
            desiredAlong = PARKOUR_CRUISE;                    // predicted short → accelerate forward
        } else if (pNeutral > d + PARKOUR_PREDICT_DEAD) {
            double pReverse = predictAlongTouchdown(s, v, b.y(), b.velY(), landY, -1, accel);
            desiredAlong = (pReverse >= cnSafe) ? 0.0        // safe to brake to a stop-target (reverse-thrust)
                                                : v;          // braking would undershoot into the gap → coast
        } else {
            desiredAlong = v;                                 // on target → hold current along momentum
        }

        // Cross-track return toward the landing centerline (independent of the along servo — the ice lane-hold).
        double botCross = b.x() * crossUx + b.z() * crossUz;
        double centerCross = (tx + 0.5) * crossUx + (tz + 0.5) * crossUz;
        double crossErr = centerCross - botCross;
        double desiredCross = Math.max(-SERVO_CROSS_CAP, Math.min(SERVO_CROSS_CAP, SERVO_CROSS_GAIN * crossErr));

        // Desired velocity → velocity error → face along it, thrust proportional (reverse when the error is
        // up-track). Same servo actuation as swimServo/groundServo, no depth pitch (parkour is a ballistic arc).
        double dvx = ux * desiredAlong + crossUx * desiredCross;
        double dvz = uz * desiredAlong + crossUz * desiredCross;
        double errx = dvx - b.velX();
        double errz = dvz - b.velZ();
        double emag = Math.sqrt(errx * errx + errz * errz);
        if (emag < EPS) {
            b.faceHorizontally(ux, uz);
            b.setForward(0.0f);
        } else {
            b.faceHorizontally(errx, errz);
            b.setForward((float) Math.min(1.0, SERVO_GAIN * emag));
        }
        b.setSprinting(sprint);
    }

    /**
     * The parkour arc predictor: integrate the verified 1.21.11 ballistic recurrence forward from
     * {@code (s,v,y,vy)} under a fixed horizontal policy {@code dir} ({@code +1} face-forward, {@code −1}
     * face-reverse/air-brake, {@code 0} neutral coast) until the feet descend to the landing surface
     * {@code landY}, and return the predicted along-axis touchdown position. Allocation-free, ≤{@link
     * #PARKOUR_PREDICT_MAXT} iterations. Recurrence (spec §physics): displacement into a tick uses {@code v_t}
     * (before the drag multiply), {@code v←(v+0.98·a·dir)·0.91}, {@code y←y+vy}, {@code vy←(vy−0.08)·0.98}.
     * The termination waits for a DESCENDING crossing ({@code vy<0}) so the rising half of the arc (feet still
     * at/above {@code landY} just after take-off) doesn't false-trigger.
     */
    static double predictAlongTouchdown(double s, double v, double y, double vy,
                                        double landY, int dir, double accel) {
        for (int i = 0; i < PARKOUR_PREDICT_MAXT; i++) {
            if (y <= landY && vy < 0.0) break;               // descended to the landing surface
            s += v;                                          // move happens BEFORE the drag multiply (uses v_t)
            v = (v + PARKOUR_INPUT * accel * dir) * PARKOUR_H_DRAG;
            y += vy;
            vy = (vy - PARKOUR_GRAVITY) * PARKOUR_V_DRAG;
        }
        return s;
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
     * GROUND overshoot hazard: whether barrelling PAST the turn waypoint in the current travel direction hits a
     * hazard within {@link #HAZARD_LOOKAHEAD} cells — the corner-overshoot slide off a 1-wide path into the
     * flank. The land counterpart of {@link #overshootHazard}, with one descent-aware distinction between its two
     * hazard kinds:
     * <ul>
     *   <li><b>LAVA</b> ({@link #groundLavaColumn}) is ALWAYS a hazard — a lava pit ahead must brake the bot
     *       whether the path turns or dives (this is what keeps iceturn safe).</li>
     *   <li><b>VOID</b> ({@link #groundVoidColumn}) is a hazard ONLY when the path is NOT itself descending
     *       straight ahead ({@link #pathDropsAhead}). A multi-block DESCENT the planner chose (a Descend/Fall
     *       run) legitimately has no floor at the waypoint's y-level for the cells the path drops through — that
     *       is the path's OWN trajectory, not an off-lane walk-off. Treating it as a void hazard braked the servo
     *       to a halt on the ledge and it never stepped off (the froze-on-descent bug). So the void probe fires
     *       only for an off-path overshoot into a drop the path does NOT take.</li>
     * </ul>
     */
    private static boolean groundOvershootHazard(BotSteering b, SteerView p) {
        if (!travelFrame(p)) return false;
        boolean plannedDrop = pathDropsAhead(p);       // path descends straight ahead → the void ahead is planned
        for (int k = 1; k <= HAZARD_LOOKAHEAD; k++) {
            int hx = F.cx + (int) Math.round(F.ux * k);
            int hz = F.cz + (int) Math.round(F.uz * k);
            if (groundLavaColumn(b, hx, F.cy, hz)) return true;                 // lava: always a hazard
            if (!plannedDrop && groundVoidColumn(b, hx, F.cy, hz)) return true; // void: only if NOT a planned descent
        }
        return false;
    }

    /**
     * GROUND flank hazard: whether either cell one step perpendicular to travel (the lane flanks at the waypoint)
     * is a hazard — the 1-wide ice lane the bot must not drift off. Land counterpart of {@link #flankHazard}. As
     * with {@link #groundOvershootHazard}, LAVA to the side is always a hazard, but a VOID to the side is NOT a
     * hazard while the path is descending straight ahead ({@link #pathDropsAhead}) — a planned open-air descent
     * has void all around by nature and must not be braked (the bot is deliberately dropping through it).
     */
    private static boolean groundFlankHazard(BotSteering b, SteerView p) {
        if (!travelFrame(p)) return false;
        int fx = (int) Math.round(-F.uz), fz = (int) Math.round(F.ux);   // rotate travel dir 90 deg
        if (groundLavaColumn(b, F.cx + fx, F.cy, F.cz + fz) || groundLavaColumn(b, F.cx - fx, F.cy, F.cz - fz)) {
            return true;                                                  // lava flank: always a hazard
        }
        if (pathDropsAhead(p)) return false;                             // planned descent: surrounding void is expected
        return groundVoidColumn(b, F.cx + fx, F.cy, F.cz + fz) || groundVoidColumn(b, F.cx - fx, F.cy, F.cz - fz);
    }

    /**
     * Whether the planned path DESCENDS straight ahead past the current waypoint — the next leg drops to a lower
     * waypoint while continuing in the current travel direction. When true, the void an overshoot/flank probe
     * finds around the waypoint is the path's OWN planned descent (a Descend/Fall run the search chose), not an
     * off-lane walk-off, so the void must NOT brake the bot. This is the off-path/overshoot-directional
     * discriminator the lava probe doesn't need (lava ahead is always a hazard; an intended drop ahead is not):
     * it distinguishes "the path goes down here" from "the void is off to the overshoot side." Requires
     * {@link #travelFrame} to have populated {@code F} (uses the current travel direction {@code F.ux/F.uz}).
     */
    private static boolean pathDropsAhead(SteerView p) {
        if (!p.hasNext()) return false;                 // nothing planned beyond the waypoint → a real walk-off
        if (p.ny() >= p.ty() - EPS) return false;       // next waypoint not below the current → no descent ahead
        double ndx = p.nx() - p.tx(), ndz = p.nz() - p.tz();
        double nl = Math.sqrt(ndx * ndx + ndz * ndz);
        if (nl < EPS) return false;                     // next is a pure straight-DOWN drop AT the waypoint column
                                                        //   (a Fall) — that is not "ahead"; overshooting forward
                                                        //   past it IS an off-lane walk-off, so keep the void guard.
        double dot = (F.ux * ndx + F.uz * ndz) / nl;    // next leg aligned with the current travel direction?
        return dot >= STRAIGHT_DOT;                     // descends roughly straight ahead → planned (not a turn into a pit)
    }

    /** LAVA anywhere in the short ground body column at {@code (x, y..y+1, z)} plus the floor cell below (reusing
     *  {@link BotSteering#swimHazardAt}, which already covers lava / damaging fluid). {@code y} is the waypoint
     *  FLOOR cell, feet at {@code y+1}. Always a hazard — see {@link #groundOvershootHazard}. */
    private static boolean groundLavaColumn(BotSteering b, int x, int y, int z) {
        return b.swimHazardAt(x, y, z) || b.swimHazardAt(x, y + 1, z) || b.swimHazardAt(x, y - 1, z);
    }

    /**
     * A would-fall DROP-OFF at the overshoot cell {@code (x, y, z)} — a one-block DROP-HEIGHT check, not a
     * downward scan. {@code y} is the overshoot cell's FEET/body level (air when a bot stands there, as
     * {@link #groundLavaColumn}'s {@code y+1} body probe implies); the lane FLOOR is {@code y-1}. If that floor
     * cell is standable the bot walks on level ground (drop 0); if it is absent the next possible floor is a
     * full block lower at best, a drop of {@code >= 16/16} that exceeds the bot's step-assist
     * ({@link MovementContext#STEP_ASSIST_MAX_RISE} = 9/16 ~ 0.56) — the bot would walk off the 1-wide path and
     * can't step back up. So a single "is the lane floor here?" read is the whole test: it flags a
     * recoverable-lip drop AND SUBSUMES a bottomless void (an infinite drop is just the limiting case of a drop
     * past step-assist). Gated by {@link #pathDropsAhead} at the call sites so a PLANNED descent (the path's own
     * drop) is not mistaken for an off-lane walk-off. */
    private static boolean groundVoidColumn(BotSteering b, int x, int y, int z) {
        return !b.solidAt(x, y - 1, z);   // lane floor (one below feet) absent -> drop > step-assist (subsumes void)
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
        } else if ("servo".equals(GROUND_DRIVE)) {
            groundServo(b, p);            // input-only velocity servo (holds a 1-wide low-friction lane); A/B-gated
        } else {
            steerTowards(b, p);           // legacy open-loop walk (default)
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
