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
     * <b>The swim ride height inside the waypoint's own feet cell</b> (owner ruling 2026-08-15). A swim
     * waypoint is a FEET cell like every other waypoint, and this says where in that cell the depth autopilot
     * parks the bot: {@code wy + 0.2} — the top of the ground SETTLE BAND.
     *
     * <p><b>Why 0.2 and not the cell centre.</b> The band is {@code [wy, wy+0.2]} because the bot is 1.8 tall:
     * at {@code +0.20} its head still occupies exactly the headroom cells the planner assumed, and anything
     * higher risks fouling a ceiling the search never checked — aiming at the centre would wedge a bot in a
     * tight cave. With {@link #WATER_RISE_DEADBAND} also 0.2 the bang-bang controller then oscillates over
     * {@code [wy+0.0, wy+0.4]}, entirely inside the feet cell, so the ride can never round to a neighbour.
     *
     * <p><b>Three other places already computed this exact number.</b> {@code Swim.reachedSwim}'s ceiling
     * clamp evaluated {@code (wy+2) - 1.8 == wy+0.2}; the ground settle band's top is {@code wy+0.2}; and
     * {@link #SUBMERGE_BIAS} was 0.8, which pulled the old {@code wy+1.0} prone ride to {@code wy+0.2} as
     * well. Adopting it universally turns the formerly-special ceiling case into the DEFAULT, which is why
     * both of {@code reachedSwim}'s clamps could be deleted outright rather than reworked.
     *
     * <p>The consequence that matters most: a swim rung now rests in the SAME band a ground move is framed
     * from, so a swim&rarr;ground handoff no longer has a block of disagreement to bridge.
     */
    public static final double SWIM_RIDE = 0.2;

    /**
     * The depth set-point for a swim waypoint — the {@link #SWIM_RIDE ride height} inside the waypoint's feet
     * cell, less the pose {@code bias}. Kept as ONE expression so the four pitch servos and {@link #holdDepth}
     * cannot drift apart; they all used to spell it {@code p.ty() - bias}, back when {@code ty()} meant the
     * feet cell's ceiling.
     */
    static double swimDepthTarget(SteerView p, double bias) {
        return p.ty() + SWIM_RIDE - bias;
    }

    /**
     * Extra sink (blocks) for a PRONE-pose move — <b>now identity, and deliberately kept as the seam</b>.
     *
     * <p>It was 0.8, and its entire job was to drag the old {@code wy + 1.0} ride down to a depth that kept
     * the ~0.6-tall prone hitbox wet: {@code (wy+1.0) - 0.8 == wy + 0.2}. {@link #SWIM_RIDE} now puts BOTH
     * poses at {@code wy + 0.2} directly, so the correction has nothing left to correct — prone rides at
     * byte-identical height to before, and only the UPRIGHT swim actually moves. At {@code wy+0.2} a 0.6-tall
     * prone hitbox tops out at {@code wy+0.8}, still comfortably inside its own (wet) cell, so vanilla keeps
     * {@code Pose.SWIMMING} exactly as the old bias arranged.
     *
     * <p>Left in place rather than deleted because the per-pose bias is a real seam and removing it would
     * churn every swim move's {@code steer} plus {@code reachedSwim}'s overload for no behavioural gain.
     */
    public static final double SUBMERGE_BIAS = 0.0;
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
        tag("steer");
        b.faceHorizontally(G.qx - b.x(), G.qz - b.z());
        b.setForward(1.0f);
    }

    /**
     * The column-aligned input DEADBAND (owner ruling 2026-07-31, the Descend vine-bounce fix): once the
     * bot's centre is within this radius of the target column's centre, terminal column-targeting drives
     * output EXACTLY zero forward instead of an eased small push. Physics: the 0.6-wide bot in a 1.0
     * cell keeps its whole box inside the cell while its centre is within 0.2 of the cell centre — no
     * box contact with any NEIGHBOURING block means {@code horizontalCollision} is impossible, and
     * vanilla's involuntary climb ({@code (horizontalCollision || jumping) && onClimbable → vy=+0.2})
     * can never fire — so a bot settling beside a trunk-hugging vine descends on the −0.15 clamp instead
     * of ratcheting up and bouncing ("no horizontal movement → no wall-pressing → no automatic climb
     * ascent"). 0.15 leaves margin under the 0.2 geometric bound. Deliberately NOT applied to the
     * general locomotion drives (a deadband there would stutter every cell crossing) — only to
     * {@link #recenterOnTarget} and terminal step phases whose target IS the column (Descend's STEP).
     */
    public static final double COLUMN_DEADBAND = 0.15;

    /**
     * Dead-zone (blocks) on the signed Δy that classifies a step's VERTICAL INTENT into the three cases the
     * stance servo drives — rise / hold / descend ({@link #holdClimbableStance}). Below it the step is treated
     * as "hold this height", so sub-block settling jitter cannot flap the stance between climbing and falling.
     * Kept at the {@code 0.05} the old single {@code descending} flag used, so the hold/descend boundary is
     * bit-identical to before; the change is that RISING is now its own case instead of being folded in with
     * holding (which pressed sneak at a bot that was trying to climb — the measured 2026-08-02 Climb wedge).
     */
    private static final double RISE_EPS = 0.05;

    /**
     * Height of the <b>settled band</b> above a cell's floor (owner ruling, 2026-08-03): a bot is "settled on
     * the floor of cell X" anywhere in {@code [X.00, X.20]} inclusive, not at {@code X.00} exactly.
     *
     * <p><b>Derivation:</b> the bot is 1.8 blocks tall, so at {@code X.20} its head still tops out inside the
     * same headroom cells the planner assumed when it emitted the step ({@code X.20 + 1.8 = X+2.00}). Any pose
     * in that interval is therefore a legitimate resting height that satisfies the plan's geometry.
     *
     * <p>Why a band and not a point: a descent moves up to {@code ~0.45} blocks/tick, so a point test is a
     * knife-edge nothing can land on. Measured 2026-08-03 — {@code 173.875 -> 173.425 -> 172.975} stepped
     * clean over a 0.1-wide window, never satisfied the hold, and left the vine before the servo noticed.
     */
    public static final double SETTLE_BAND = 0.20;

    /**
     * Re-centre on the target column: face the target's x,z and apply forward input proportional to the
     * horizontal offset, so a bot dead-on the column doesn't shove itself off while a drifted bot walks back.
     * Used by the vertical-in-place moves (Pillar, MineDown) and by an airborne Fall homing onto its landing
     * column. Input-based, so the (weak) air control is honest rather than a teleported velocity.
     * Within {@link #COLUMN_DEADBAND} the output is EXACTLY zero (not eased-toward-zero) — the
     * vine-bounce fix's load-bearing detail.
     */
    public static void recenterOnTarget(BotSteering b, SteerView p) {
        recenterOn(b, p.tx(), p.tz());
    }

    /**
     * Shared column servo: proportional walk toward {@code (cx0,cz0)}, exact zero inside
     * {@link #COLUMN_DEADBAND}.
     *
     * @return {@code true} once the bot is INSIDE the deadband, i.e. centred on {@code (cx0,cz0)} — the
     *         signal {@link com.orebit.mod.pathfinding.blockpathfinder.movements.Parkour}'s run-up uses to
     *         end a re-centre and start accelerating. Callers that only want the input may ignore it.
     */
    public static boolean recenterOn(BotSteering b, double cx0, double cz0) {
        double cx = cx0 - b.x();
        double cz = cz0 - b.z();
        double d = Math.sqrt(cx * cx + cz * cz);
        if (d > COLUMN_DEADBAND) {
            tag("recenter");
            b.faceHorizontally(cx, cz);
            b.setForward((float) Math.min(1.0, d));
            return false;
        }
        tag("recenter:dead");
        b.setForward(0.0f); // aligned — exact zero input; see COLUMN_DEADBAND
        return true;
    }

    /**
     * <b>Coast distance</b> (blocks per block/tick of speed) an un-thrusted body still travels before drag
     * stops it, {@code q/(1−q)} for the medium's horizontal drag {@code q} — the closed form that makes
     * {@link #arriveOnTarget} velocity-aware without a tuned gain.
     *
     * <p><b>Airborne</b> ({@code q = 0.91}): {@code 10.11 × v}. <b>Grounded</b> ({@code q = 0.6 × 0.91 =
     * 0.546}): {@code 1.20 × v}. That ~8× step at the lip is the whole reason a walk-off overshoots: a
     * {@code 0.105} b/t exit speed is a harmless {@code 0.13}-block coast while the feet are still supported
     * and becomes a {@code 1.06}-block coast the instant they are not.
     */
    private static final double AIR_COAST = 0.91 / (1.0 - 0.91);
    private static final double GROUND_COAST = 0.546 / (1.0 - 0.546);

    /**
     * <b>Arrive</b> on the target column: aim the bot's <i>predicted stopping point</i> at the target rather
     * than its current position, holding the step's heading and braking with REVERSE input. The velocity-aware
     * counterpart of {@link #recenterOnTarget}, used by an airborne {@link
     * com.orebit.mod.pathfinding.blockpathfinder.movements.Fall} homing onto its landing column.
     *
     * <p><b>Why position-only steering cannot land centred</b> (measured 2026-08-06, the flagship parkour
     * failure). {@link #recenterOn} drives {@code forward = min(1, distanceToTarget)} with no velocity term,
     * so against carried momentum it is a pure P-controller and always settles with standing overshoot. Worse,
     * its {@link #COLUMN_DEADBAND} zeroes the output for the first {@code 0.15} of error — which is exactly
     * where the bot is when it goes airborne still carrying its walk-off speed. Convicted on a Fall into
     * {@code (82,115,218)}: the bot left the lip {@code 0.063} past the target centre at {@code −0.105} b/t,
     * the servo commanded {@code 0.00} while the momentum was largest, then ramped {@code 0.23 → 0.35 → 0.42}
     * as the error grew — reproducing {@code min(1, d)} to the digit — and settled {@code 0.45} past centre.
     * The Parkour that followed took off from a standstill at the far cell edge and fell {@code 0.31} short.
     *
     * <p><b>The fix is the projection, not a gain.</b> With no input a body travels a further
     * {@code coast × v} (see {@link #AIR_COAST}), so {@code stop = position + coast × velocity} is where it
     * WILL end up. Servoing on {@code target − stop} makes the controller brake the moment the projection
     * overshoots — from the first airborne tick, while there is still airtime to spend — and self-corrects in
     * both directions: undershooting projects short and drives forward, overshooting projects long and drives
     * back. No timers, no tuned damping constant, no per-case branches.
     *
     * <p><b>Heading is held; braking is REVERSE input</b> (owner ruling 2026-08-06). The old servo faced
     * whatever direction reduced the error, so an overshoot span it {@code 180°} — visually a bot pirouetting
     * off every ledge. Instead the facing is pinned to the SEGMENT heading (previous waypoint → target, i.e.
     * the direction the step actually travels) and the along-heading component of the error becomes a SIGNED
     * forward input, so {@link BotSteering#setForward} goes negative to brake. Vanilla scales {@code zza}
     * symmetrically, so reverse thrust has the same authority as forward ({@code 0.0255} b/t² airborne while
     * sprinting) — the bot simply moon-walks the last few centimetres instead of turning around.
     *
     * <p><b>Cross-axis error is corrected by STRAFE, not by yawing</b> ({@link BotSteering#setStrafe}). The
     * first cut of this method dropped cross correction entirely, on the reasoning that a cardinal step's
     * lateral drift is negligible ({@code |velX| ≈ 0.0003} per tick on the convicted fall). Re-measured
     * 2026-08-06 and REFUTED: it is negligible per tick and decisive per MOVE, because nothing bleeds it — a
     * pinned heading gives the lateral axis no thrust at all, so {@code 0.026} b/t of carry decayed only by air
     * drag across a 3-block drop and integrated into {@code +0.344} blocks of displacement. The X miss went
     * from {@code 0.001} to {@code 0.199} and swallowed most of the along-axis win. So the projected miss is
     * resolved in the heading frame and BOTH channels are driven; the facing still never turns.
     *
     * <p><b>Climbables keep the old servo.</b> {@link #COLUMN_DEADBAND} exists because horizontal input near a
     * vine trips vanilla's involuntary climb ({@code (horizontalCollision || jumping) && onClimbable → vy =
     * +0.2}); that hazard is specific to climbables, so a bot on one falls through to {@link #recenterOn} and
     * the 2026-07-31 vine-bounce ruling is preserved untouched. Everywhere else the deadband still applies, but
     * to the PROJECTED point — a bot with no velocity projects onto itself, so a settled bot inside
     * {@code 0.15} still commands exactly zero.
     */
    /**
     * <b>REJECTED: pre-braking the walk-off</b> (built and reverted 2026-08-06 — do not reintroduce). A
     * {@code steppingOff} flag forced {@link #AIR_COAST} while the bot was still grounded, reasoning that a bot
     * striding off a lip is about to inherit the {@code 10.11 × v} coast and should brake while it still has
     * ground authority ({@code 0.127} b/t², ~5× the airborne {@code 0.0255}).
     *
     * <p>It centred the landing and was still wrong. Measured on a 2-block Fall: <b>20 of 26 ticks</b> went to
     * creeping at 0.02–0.10 b/t (the servo chattering {@code arrive:off} against {@code arrive:off:dead} as the
     * projection crossed the deadband), and the airborne phase then read {@code arrive:dead} for every tick of
     * the drop — no momentum was left to brake. The centring was bought entirely with ground time, and the
     * air-brake it existed to assist never ran once.
     *
     * <p>The reasoning was wrong in one specific place: while grounded the bot CAN still stop within
     * {@code 1.20 × v}, so there is no overshoot to prevent yet. The overshoot becomes real only at the lip —
     * and at the lip the bot goes airborne and this method switches to {@link #AIR_COAST} by itself, on that
     * same tick. Braking after the transition is both sufficient and free (owner ruling: "it can absolutely
     * air-brake a fall — we don't need to crawl over the edge").
     */
    public static void arriveOnTarget(BotSteering b, SteerView p) {
        if (b.onClimbable()) {          // vine-bounce ruling owns this case — unchanged servo
            recenterOn(b, p.tx(), p.tz());
            return;
        }
        // Heading: the step's own segment (previous waypoint → target). Stable for the whole move, so the
        // facing never flips; degenerate (vertical) segments fall back to the target bearing.
        double hx = p.tx() - p.sx();
        double hz = p.tz() - p.sz();
        double hlen = Math.sqrt(hx * hx + hz * hz);
        if (hlen < EPS) {
            recenterOn(b, p.tx(), p.tz());
            return;
        }
        hx /= hlen;
        hz /= hlen;

        // Where the bot ENDS UP if it stops thrusting now, and how far that misses the target column.
        double coast = b.grounded() ? GROUND_COAST : AIR_COAST;
        double ex = p.tx() - (b.x() + coast * b.velX());
        double ez = p.tz() - (b.z() + coast * b.velZ());

        b.faceHorizontally(hx, hz);
        if (Math.sqrt(ex * ex + ez * ez) <= COLUMN_DEADBAND) {
            tag("arrive:dead");
            b.setForward(0.0f);         // projected arrival is on the column — no input; see COLUMN_DEADBAND
            b.setStrafe(0.0f);
            return;
        }
        tag("arrive");
        // Decompose the projected miss into the heading frame and drive BOTH channels, so cross-track error is
        // corrected without yawing (BotSteering.setStrafe): along = forward/brake, cross = strafe. Positive
        // strafe is the mover's LEFT, which for unit heading (hx,hz) is the direction (hz,−hx).
        double along = ex * hx + ez * hz;   // signed: negative == projected to overshoot == brake
        double cross = ex * hz - ez * hx;   // signed: positive == target lies to the LEFT of the heading
        // Saturate as a VECTOR, never per-component: vanilla normalizes an over-unit input anyway, and clamping
        // the two independently would tilt the commanded direction exactly when the correction matters most.
        double mag = Math.sqrt(along * along + cross * cross);
        if (mag > 1.0) {
            along /= mag;
            cross /= mag;
        }
        b.setForward((float) along);
        b.setStrafe((float) cross);
    }

    /**
     * <b>Station-keep</b>: hold the stance the bot is already in WITHOUT travelling — the input set for
     * {@link PhaseRunner}'s "stop and fix the geometry" hold (mine an obstruction / place a footing before
     * the phase drives). Re-centres on the bot's OWN column rather than the step's target, and applies
     * {@link #holdClimbableStance}.
     *
     * <p><b>Why the own column and not the target</b> (convicted 2026-08-01 on the flagship wedge at
     * {@code (58,133,189)}). The runner's hold is documented as "the bot holds on the column rather than
     * driving", but it called {@link #recenterOnTarget} — and a LATERAL move's target is the NEXT column, so
     * the hold walked the bot at full forward straight into the very block it was mining. On flat ground
     * that is a harmless wall-press. With the bot's feet inside a curtain it is fatal: vanilla's involuntary
     * climb ({@code (horizontalCollision || jumping) && onClimbable → vy=+0.2}) ratchets the bot up
     * 0.2/tick — a full block every 5 ticks — while no block is mined in fewer than that. The press
     * therefore ALWAYS lifts the bot out of its own stance before the obstruction clears; it tops out above
     * the curtain and grounds one cell above the frame its plan was built from, a permanent fail→HOLD.
     * Witnessed exactly: a Climb to feet 132 up the vine at {@code (58,129..132,189)}, then a {@code
     * Traverse +x} needing the jungle leaves at {@code (59,132,189)} mined — the bot ratcheted 132.007 →
     * 133.026 and settled ON the leaves it was supposed to break. Station-keeping removes the press, so the
     * ratchet cannot fire (the same physics {@link #COLUMN_DEADBAND} already exploits for the in-place case)
     * and the mine completes from a stable stance.
     *
     * <p>The facing is not lost by dropping the target-ward push: {@link BotSteering#mine} aims at the cell
     * it breaks (BotMining's look-at-centre), so the hold never needs to face its own obstruction.
     *
     * <h2>"Stop" is medium-relative — zero inputs only STOP a bot the ground is already holding</h2>
     * Ground is the one medium where standing still is free: release everything and friction keeps the bot
     * exactly where it is. In every other medium the bot is being CARRIED somewhere while it holds, so the
     * inputs that mean "stay put" are different inputs — and emitting none is not a hold at all, it is a
     * slow drift out of the frame the plan was built from. Two are real:
     * <ul>
     *   <li><b>Fluid</b> — buoyancy/gravity never balance for a headless bot (vanilla's swim-down is a
     *       CLIENT tick it does not run), so a no-input bot sinks. Convicted 2026-08-10 on a submerged wall
     *       break: 41 hold ticks at a constant {@code dm.y=-0.025} took botY {@code 39.992 -> 39.018} —
     *       0.974 blocks — the foot cell left the {@code Traverse}'s admitted band, {@link
     *       MovePlan#failWhen} fired, and {@code BotMining}'s reactive progress (a break continues only
     *       while the mover keeps asking for the SAME cell) reset with the block unbroken. The station-keep
     *       is {@link #holdDepthAt} — the same depth autopilot every swim move and {@link #drive}'s
     *       in-water branch already press — aimed at the bot's own feet cell, the vertical twin of this
     *       method's own-column re-centre. Deadband {@code ±}{@link #WATER_RISE_DEADBAND} keeps the whole
     *       hold inside one cell, so the foot cell is unreachable from either side however long the break
     *       takes ({@code failWhen} is purely POSITIONAL — no tick budget — so an arbitrarily slow break
     *       is fine once the bot is actually still). <b>WHICH height inside the cell is chosen by
     *       {@link BotSteering#standableBelow}</b> — see the floor rule below.</li>
     *   <li><b>Climbable</b> — sneak, as before: vanilla's {@code handleOnClimbable} zeroes the
     *       {@code -0.15}/t slide for a sneaking Player.</li>
     * </ul>
     *
     * <p><b>Fluid outranks climbable, and that is a vanilla fact, not a preference</b> (javap-verified on
     * 1.21.11 named bytecode, 2026-08-10). {@code LivingEntity.travel} dispatches
     * {@code shouldTravelInFluid(...) ? travelInFluid : isFallFlying() ? travelFallFlying : travelInAir},
     * and {@code handleOnClimbable} has exactly ONE caller —
     * {@code handleRelativeFrictionAndCalculateMovement}, itself called from exactly one site, inside
     * {@code travelInAir}. So a bot on a LADDER IN WATER runs the fluid branch and the climbable clamp
     * never executes: sneak there is not a weaker hold, it is no hold at all. (What DOES survive into the
     * fluid branch is the involuntary climb — {@code travelInWater} still turns
     * {@code horizontalCollision && onClimbable} into {@code vy=0.2} — which the own-column re-centre
     * already starves of input.)
     *
     * <p><b>Scaffolding is exempt from the sneak-hold</b> (same verification pass; NOTES-movement-physics
     * §3). Vanilla's hold reads
     * {@code y<0 && !getInBlockState().is(Blocks.SCAFFOLDING) && isSuppressingSlidingDownLadder() && this
     * instanceof Player} — scaffolding is excluded by name — and {@code ScaffoldingBlock.getCollisionShape}
     * additionally returns {@code Shapes.empty()} for a DESCENDING context, so a sneak on a scaffold deck
     * deletes the very surface holding the bot up. On scaffolding sneak is a DESCEND input; the hold
     * therefore presses nothing and lets the bot rest on the next deck plate (which makes it
     * {@code grounded}, the medium that needs no input at all). {@link BotSteering#scaffoldingBelow} is the
     * existing seam read for this — the same one {@code Climb}'s sink-in step uses.
     *
     * <h2>In fluid, a FLOOR beats buoyancy — settle on it (owner ruling, 2026-08-15)</h2>
     * The fluid hold above answers "what holds me up in water", and buoyancy was treated as the only
     * available answer. It is not: when a standable block sits under the feet cell, <b>resting on it is
     * also a hold</b> — a strictly better one, because it costs no input, cannot drift, and makes the bot
     * {@code onGround}. That last part is worth a factor of five in mining rate and nothing else in the
     * system can buy it back: vanilla's {@code Player.getDestroySpeed} divides by 5 for {@code !onGround}
     * and multiplies by the submerged-mining attribute (0.2 without Aqua Affinity) for an eye in water,
     * and {@code BotMining} accumulates {@code BlockState.getDestroyProgress}, so both land on the bot.
     * Floating to mine therefore pays <b>25×</b> where standing pays 5× — and the 5× is unavoidable
     * (the eye stays wet either way) while the other 5× is pure self-harm.
     *
     * <p><b>Measured</b> on the vd=16 flagship, 2026-08-15: a {@code MineDown} shaft at {@code (195,·,68)}
     * held {@code hold:depth} for <b>5,121 ticks — 43% of the entire 12,000-tick budget</b> — bobbing
     * between {@code botY -13.67} and {@code -13.72} with {@code grounded=false}, while a full deepslate
     * block sat at {@code (195,-15,68)} whose top face was 0.30 below the feet. One block per ~1,200
     * ticks; the run failed on budget with 14 blocks still to descend.
     *
     * <p><b>The change is the depth TARGET, not a new branch</b>: the same autopilot is aimed at the cell
     * FLOOR ({@code footY}) instead of its centre ({@code footY + 0.5}). Above the deadband it presses
     * sink, so the bot descends the last third of a block under its own power; the block stops it; and
     * from the next tick the {@code grounded} short-circuit at the top of the chain takes over and presses
     * nothing at all — the medium that needs no input, which is the whole point of the ordering.
     *
     * <p>That target is <b>one-sided by construction</b>, which is what makes it safe to press: {@code
     * footY()} is derived from the bot's own position, so {@code y() >= footY()} always holds and {@link
     * #holdDepthAt}'s rise branch is unreachable. The floor hold can only SINK — it can never swim the bot
     * back off the block it just settled on and fight the grounded short-circuit. The corollary is its
     * honest limit: nothing in the servo arrests a descent, so if the floor is a lie the bot keeps sinking
     * a cell at a time. Deliberate — {@code standableBelow} is a live world read, and for {@code MineDown}
     * the floor breaking underfoot IS the movement.
     *
     * <p><b>Why {@link BotSteering#standableBelow} is the right probe.</b> It is the existing "is there a
     * floor under my feet" answer, read live off the level through {@link
     * com.orebit.mod.worldmodel.navblock.NavBlock#isStandable} — the SEARCH's own floor bit — across the
     * columns the bounding box actually overlaps. It is the same seam {@code holdClimbableStance} already
     * consults for the same underlying question. Its {@code false} default leaves every headless double,
     * and therefore every existing test, byte-identical.
     *
     * <p><b>The partial-floor case is a broken state, not a case to handle.</b> A bottom slab under the
     * feet cell would seat the bot at {@code footY-0.5}, i.e. in the cell BELOW — which would move the
     * foot cell and could trip a {@code failWhen}. That geometry cannot arise from a healthy plan: the
     * planner's {@code fromFootY} is topY-aware, so a slab floor frames the step at the SLAB's cell, not
     * one above it. Reaching the hold with a partial floor a full block under the feet therefore already
     * means the grid disagrees with the world, and the fix for that is grid freshness, not a servo
     * special case. Note also that a bot which is ALREADY grounded on such a slab gets zero vertical hold
     * today (the {@code grounded} short-circuit), so this makes no new state reachable — it only lets the
     * bot arrive at one the code already treats as normal.
     */
    public static void stationKeep(BotSteering b, SteerView p) {
        recenterOn(b, Math.floor(b.x()) + 0.5, Math.floor(b.z()) + 0.5);
        tag("hold");   // after recenterOn, so the log names the CALLER's intent, not the shared column servo
        // A HOLD IS A HOLD — including vertically (2026-08-05, the third (55,173,256) miss). This is the
        // runner's stop-and-fix path: the bot is mining or placing and must not move AT ALL until the
        // geometry is established. Delegating to holdClimbableStance broke that on a hang, because its
        // descend branch consults the MOVE's vertical intent — and every Descend's intent is "go down", so
        // the servo dutifully released the stance while the runner was still placing the block. Measured:
        // sneak held at botY=173.043 for one tick, released the next while the cobble was still being
        // placed, feet out of the vine cell at 172.965, free-fall to 170.5. The move's intent is simply not
        // relevant while the runner has stopped it; only holding is.
        //
        // Deliberately NOT applied to drive()'s stance call, where the descend intent IS the point.
        //
        // WHAT HOLDS ME, in the medium I am in RIGHT NOW — a stateless per-tick question, re-asked every
        // tick, with no timer and no memory of how long the hold has run (see the class doc above for the
        // verification behind the ordering). Grounded skips the whole chain: a floor is already holding the
        // bot, and zero inputs is the correct — and byte-identical-to-before — answer there.
        if (!b.grounded()) {
            if (b.inWater() || b.inLava()) {
                // Fluid FIRST: vanilla's fluid travel branch never runs the climbable clamp, so on a
                // ladder in water sneak does nothing and only the depth autopilot can hold the bot.
                //
                // A FLOOR BEATS BUOYANCY (see the class doc's floor rule). Both targets are inside MY OWN
                // cell — never the step's target — so the foot cell is safe either way; the choice is only
                // WHERE in the cell. With something to stand on, aim at the cell floor: the sink press
                // closes the last fraction of a block, the block stops it, and the grounded short-circuit
                // above then holds the bot for free — worth 5x the mining rate over floating. With
                // nothing to stand on, buoyancy is the only hold there is, so keep the cell centre, which
                // is the height furthest from either cell boundary.
                boolean floor = b.standableBelow();
                tag(floor ? "hold:floor" : "hold:depth");
                holdDepthAt(b, floor ? b.footY() : b.footY() + 0.5);
                return;
            }
            if (b.onClimbable()) {
                // Scaffolding is sneak-exempt in vanilla (and sneak deletes its deck shape outright), so
                // pressing sneak there DESCENDS. Nothing holds a bot inside scaffolding: press nothing and
                // let it settle onto the next plate, which grounds it.
                if (!b.scaffoldingBelow()) {
                    tag("hold:sneak");
                    b.setSneak(true);
                }
                return;
            }
        }
        holdClimbableStance(b, p);
    }

    /**
     * {@link #stationKeep} MINUS the vertical hold — hold the bot's own column while letting it SINK. The
     * settle gate a step opens with (Descend's SETTLE phase): the bot is above its own start block and must
     * come to rest ON it before the step commits, so the one thing this must NOT do is hold height.
     *
     * <p><b>Why not stationKeep</b> (the distinction is the whole point). stationKeep calls {@link
     * #holdClimbableStance} with {@code translating == false}, which on a climbable presses SNEAK to pin the
     * bot where it is — correct while mining (the bot must not slide off the frame its plan was built from),
     * and exactly wrong here, where descending onto the start block is the goal. A settle gate that sneaked
     * would hang forever at the height it was trying to leave.
     *
     * <p>The column re-centre is kept: it costs nothing when the bot is already centred (exact zero inside
     * {@link #COLUMN_DEADBAND}), bleeds lateral drift while the bot sinks, and — unlike a target-ward push —
     * aims INWARD, so it can never press the box into a neighbouring block and trip the involuntary-climb
     * ratchet that this gate exists to starve of input.
     */
    public static void settleOnOwnColumn(BotSteering b) {
        recenterOn(b, Math.floor(b.x()) + 0.5, Math.floor(b.z()) + 0.5);
    }

    /**
     * On a climbable, HOLD HEIGHT until the bot is over its target column — the lateral half of a step that
     * begins from a hang. Returns {@code true} when the hold was applied.
     *
     * <p><b>Why this is the MOVE's job and not {@link #holdClimbableStance}'s</b> (2026-08-04). A descending
     * bot on a climbable faces two physically opposite situations that the servo cannot tell apart, because
     * the difference is the move's INTENT, not the geometry:
     * <ul>
     *   <li>A multi-block drop ({@code Fall}'s §3.2 release-drop, or any descent whose plan already accounts
     *       for the fall) must LET GO. Arresting it strands the bot on the vine it was told to leave.</li>
     *   <li>A one-block {@code Descend} into an ADJACENT column must NOT let go yet. Releasing while still
     *       over the old column drops the bot down THAT column — and on a curtain the supporting cell is
     *       often a single block, so it exits the climbable entirely and free-falls.</li>
     * </ul>
     * Measured on the second at {@code (55,173,256)}: the bot released at {@code botY=173.043} while still
     * at {@code x=55.5}, its feet left cell 173 at {@code 172.965}, and it fell two blocks into the gap its
     * own placed cobble had just walled off — unable to jump back out.
     *
     * <p>The gate is the same {@link #COLUMN_DEADBAND} the step-off drive uses, so "close enough to stop
     * pressing" and "close enough to let go" are one threshold rather than two that can disagree. Sneak is
     * safe to hold here: vanilla's ledge edge-guard needs {@code onGround}, and a bot hanging on a climbable
     * is airborne, so the guard cannot eat the lateral motion this is easing across.
     */
    /**
     * Whether the bot is in a RESTING POSE for feet cell {@code footY} — the single definition of "standing
     * where a plan assumes you are" (owner ruling 2026-08-05). Shared by {@link
     * com.orebit.mod.pathfinding.blockpathfinder.Movement#atWaypoint} (may a move FINISH here?) and by
     * {@link PhaseRunner}'s implicit settle gate (may a move START here?), because those must be the same
     * question: every move's arrival pose is the next move's precondition, so a pose good enough to end on
     * is exactly a pose good enough to begin from. Two definitions would silently disagree at the seam.
     *
     * <p>Grounded and fluid are exempt for physical reasons, not convenience: a grounded bot rests on
     * whatever surface it found (a bottom slab / snow / carpet seats the feet mid-cell, and {@code feetYOf}
     * is topY-aware, so its y is correct by construction), and a floating bot bobs with buoyancy. The band
     * therefore binds exactly the CLIMBABLE HANG, where nothing but the servo decides the stopping height.
     */
    public static boolean inRestingPose(BotSteering b, int footY) {
        // SETTLED is the first conjunct, not an afterthought: position says where the bot IS, never that
        // anything is HOLDING it there. Measured at (55,173,256) — the bot sat at botY=173.122, inside the
        // [173.00, 173.20] band, while in free fall at dm.y=-0.0784; a position-only test opened the gate
        // and it fell straight through. settled() is exactly "supported in this medium", and on a climbable
        // that means HELD (sneak, or velocity above the arrest threshold), not merely intersecting.
        // climbableBelow() is admitted alongside settled(), mirroring Climb.reached's
        // `(settled() || climbableBelow())` — and for the same reason. A bot TOPPED OUT on a curtain is
        // supported by it, but is not grounded, not in fluid, and not onClimbable() (the climbable is UNDER
        // the feet, not in them), so settled() cannot see it. Without this arm the gate blocks every move
        // that begins from a top-out and settleIntoBand cannot rescue it — it returns immediately off a
        // climbable — so the bot would hang forever at a pose it had legitimately reached.
        //
        // Safe here even though folding climbableBelow into settled() ITSELF was tried and reverted (it
        // fires for a Parkour arc merely passing over a vine, which then advanced its waypoint mid-flight).
        // This gate only PERMITS a plan to execute; it never advances one. The band test still applies, so
        // the worst case is a ballistic bot at exactly the right height starting its next move one tick
        // early — whereas reached/done, which do advance, keep their own stricter guards.
        if (!b.settled() && !b.climbableBelow()) {
            return false;
        }
        return b.grounded() || b.inWater() || b.inLava()
                || (b.y() >= footY && b.y() <= footY + SETTLE_BAND);
    }

    /**
     * Drive the bot INTO the resting band for feet cell {@code footY} — the implicit settle every move opens
     * with (owner ruling 2026-08-05: "every move is built under the assumption the bot is standing in the
     * band at the start; if we're NOT there, we need to settle into that position").
     *
     * <p>Three cases, all state-based (no timers):
     * <ul>
     *   <li><b>Above the band</b> — press nothing vertical and let gravity, or the {@code -0.15} climbable
     *       clamp, bring the bot down. Holding here would pin it at the height it needs to leave.</li>
     *   <li><b>About to overshoot</b> — on a climbable, tap sneak the tick before the feet would drop below
     *       {@code footY}. A descent moves up to ~0.45 b/t, so reacting only once inside a 0.20 band is a
     *       knife-edge nothing can land on; anticipating is what makes the band reachable at all.</li>
     *   <li><b>In the band</b> — on a climbable, hold sneak, or the slide simply continues through it.</li>
     * </ul>
     *
     * <p>Off a climbable there is no input that arrests a fall, so this only re-centres and waits — which is
     * correct: a bot falling through open air is either going to land (and be grounded, hence resting) or is
     * off-plan, and that is the validity envelope's verdict to make, not this servo's.
     */
    public static void settleIntoBand(BotSteering b, SteerView p, int footY) {
        recenterOn(b, Math.floor(b.x()) + 0.5, Math.floor(b.z()) + 0.5); // hold the column, never press on
        if (!b.onClimbable() || b.y() < footY) {
            return;                                    // nothing to hold with, or already below — let it be
        }
        if (b.y() <= footY + SETTLE_BAND || b.y() + b.velY() < footY) {
            b.setSneak(true);                          // in the band, or one tick from falling out of it
        }
    }

    public static boolean holdUntilOverTargetColumn(BotSteering b, SteerView p) {
        if (!b.onClimbable()) return false;
        // SUPPORT UNDERNEATH ⇒ no hold (the already-ratified lateral rule, re-derived here 2026-08-05).
        // This hold exists to stop the bot sliding down the WRONG column while it is still off-target. A bot
        // standing on a block is not sliding anywhere, so the hold buys nothing — and sneak arms vanilla's
        // maybeBackOffFromEdge, which deletes precisely the horizontal motion a step-off needs to leave the
        // ledge. Measured at (58,171,254): grounded on a block whose cell ALSO holds a vine, so onClimbable
        // was true; sneak engaged the moment the bot entered that cell and its dm.x decayed 0.078 -> 0.036
        // as the edge guard ate the walk-off. A vine sharing a cell with solid footing is common on a trunk,
        // so testing onClimbable alone is not enough — the question is whether anything is holding the bot up.
        if (b.grounded() || b.standableBelow()) return false;
        double ox = p.tx() - b.x(), oz = p.tz() - b.z();
        if (Math.sqrt(ox * ox + oz * oz) <= COLUMN_DEADBAND) return false; // over it — let go, gravity drops
        b.setSneak(true);
        return true;
    }

    /**
     * The STEP-OFF <b>velocity-alignment gate</b> (owner-ratified 2026-07-30) — the chained-step
     * momentum-corner-slip fix, the {@link
     * com.orebit.mod.pathfinding.blockpathfinder.movements.DiagonalParkour} takeoff-gate concept
     * generalized to grounded step-offs. A short (one-cell) step-off entered with CROSS-axis momentum from
     * the previous step (a −z Descend chaining into a +x Descend) drifts the bot across the lane boundary
     * during the 2–3 ticks ground friction needs to bleed the carry — it grounds on the diagonally
     * adjacent cell, a REAL off-plan settle the validity envelope rightly fail→HOLDs (the flagship-cliff
     * (68,149,245)-vs-column-(68,*,246) freeze). The gate makes the commit conditional on a PREDICTION,
     * not a timer: with zero further input, a grounded bot's future cross drift is the geometric sum of
     * its velocity under the per-tick ground retention {@code f = slipperiness × 0.91} (displacement uses
     * {@code v_t} before the multiply — the {@link #predictAlongTouchdown} recurrence — so the sum is
     * {@code v/(1−f)}: ×2.2 on stone at slip 0.6, ×9+ on ice). The step-off may drive only when
     * {@code |botCrossOffset + vCross/(1−f)| ≤ 0.5 − }{@link #PARKOUR_CELL_MARGIN} — the bot's centre,
     * after coasting out its carry, stays inside the one-wide landing lane with the player half-width to
     * spare. While that fails, this method WRITES the arrest inputs for the tick — the pure cross servo
     * ({@link #SERVO_CROSS_GAIN}/{@link #SERVO_CROSS_CAP} toward the lane centreline, desired along-speed
     * ZERO, the {@link #parkourAirborne} actuation) — and returns {@code true}: bleed the carry and pull
     * the centreline FIRST, commit after. On ice the horizon is honestly long, so the bot all but stops
     * before stepping off — the physically right caution, at worst a visible pause on the lip (never a
     * slide off it). Conservative by construction: the arrest beats pure friction, and post-commit the
     * normal drive's cross-gain only shrinks the carry further, so the prediction is an upper bound.
     * Callers gate on {@code b.grounded()} and on still standing on the FROM column — once the step-off
     * is under way (foot moved / airborne) the gate must not re-engage.
     *
     * @return {@code true} = held at the gate (arrest inputs written for this tick);
     *         {@code false} = aligned/contained (nothing written — the caller drives the step).
     */
    public static boolean stepOffGate(BotSteering b, SteerView p) {
        double dx = p.tx() - p.sx(), dz = p.tz() - p.sz();
        double len = Math.sqrt(dx * dx + dz * dz);
        if (len < EPS) return false;                        // degenerate segment — nothing to align to
        final double ux = dx / len, uz = dz / len;
        final double crossUx = -uz, crossUz = ux;
        // Lane centreline through the target centre; +crossErr = the centreline is +cross of the bot.
        final double crossErr = (p.tx() * crossUx + p.tz() * crossUz) - (b.x() * crossUx + b.z() * crossUz);
        final double vCross = b.velX() * crossUx + b.velZ() * crossUz;
        // Zero-input drift horizon on the CURRENT support block (the drift happens on the from-side floor;
        // vanilla reads the block under the feet — for a bottom-partial stand this reads one below it, a
        // negligible mismatch). f→1 (modded super-slip) degrades to always-gated: conservative, never a slip.
        final double f = b.slipperinessAt(b.footX(), b.footY() - 1, b.footZ()) * PARKOUR_H_DRAG;
        final double predictedOffset = -crossErr + vCross / (1.0 - f);
        if (Math.abs(predictedOffset) <= 0.5 - PARKOUR_CELL_MARGIN) return false; // contained — commit
        // Arrest: the pure cross servo — desired along-speed 0, desired cross velocity toward the centreline.
        final double desiredCross = Math.max(-SERVO_CROSS_CAP, Math.min(SERVO_CROSS_CAP, SERVO_CROSS_GAIN * crossErr));
        final double errx = crossUx * desiredCross - b.velX();
        final double errz = crossUz * desiredCross - b.velZ();
        final double emag = Math.sqrt(errx * errx + errz * errz);
        if (emag < EPS) {
            tag("arrest:hold");
            b.faceHorizontally(ux, uz);
            b.setForward(0.0f);
        } else {
            tag("arrest");
            b.faceHorizontally(errx, errz);
            b.setForward((float) Math.min(1.0, SERVO_GAIN * emag));
        }
        return true;
    }

    /**
     * Swim along the planned line, HORIZONTALLY: face the look-ahead pursuit point and hold forward (the same
     * W-key + look a player uses). Vertical is the caller's {@link #holdDepth} (each swim move calls it with
     * its own bias — the moves own their vertical control). A pure vertical (degenerate) segment has no line
     * to track, so it re-centres on the target column ({@link #recenterOnTarget}, as {@link #steerTowards}
     * does) while {@code holdDepth} drives the dive/climb: carried momentum can drift the bot off the column,
     * and the exact-cell swim reach ({@code Swim.reachedSwim}'s footX/footZ match) can never fire off-column.
     */
    public static void swimTowards(BotSteering b, SteerView p) {
        computeGeom(b, p, SWIM_CTE_GAIN);
        if (G.segLen < EPS) {
            recenterOnTarget(b, p);
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
     * segments that plain faceHorizontally suffers. A pure vertical (degenerate) segment has no line to pursue,
     * so it station-keeps over the target column ({@link #swimPitchedCentered}'s proportional pull at the same
     * bias): carried momentum can drift the bot off the column, and the exact-cell swim reach
     * ({@code Swim.reachedSwim}'s footX/footZ match) can never fire off-column. Centred, that reduces to the
     * pure depth pitch with no horizontal push.
     */
    public static void swimPitched(BotSteering b, SteerView p, double bias) {
        computeGeom(b, p, SWIM_CTE_GAIN);
        if (G.segLen < EPS) {
            swimPitchedCentered(b, p, bias);   // pure vertical: hold the column; pitch + holdDepth own the climb
        } else {
            double dy = swimDepthTarget(p, bias) - b.y();
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
        double dy = swimDepthTarget(p, bias) - b.y();       // depth pitch (same as swimPitched)
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
        double dy = swimDepthTarget(p, bias) - b.y();       // depth pitch (same as swimPitched)
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
     * A degenerate (vertical) segment station-keeps over the target column ({@link #recenterOnTarget}'s
     * proportional pull, pitched) with the same client-legal forward floor as the cruise: sprint momentum can
     * drift the bot off the column, and the exact-cell swim reach ({@code Swim.reachedSwim}'s footX/footZ
     * match) can never fire off-column. Centred, that reduces to a pure depth pitch.
     */
    public static void swimServo(BotSteering b, SteerView p, double bias) {
        computeGeom(b, p, SWIM_CTE_GAIN);
        double dy = swimDepthTarget(p, bias) - b.y();               // depth pitch target (same as swimPitched)
        if (G.segLen < EPS) {                              // pure vertical: hold the column while diving/rising
            double ox = p.tx() - b.x(), oz = p.tz() - b.z();
            double od = Math.sqrt(ox * ox + oz * oz);
            double vfwd;
            if (od > EPS) {
                b.faceTowards(ox, dy, oz);                 // yaw toward the column centre + depth pitch
                vfwd = Math.min(1.0, od);                  // proportional: eases to 0 once centred (recenterOnTarget)
            } else {
                b.faceTowards(0.0, dy, 0.0);               // centred: pure depth pitch
                vfwd = 0.0;
            }
            // Same client-legal floor as the cruise below: W never released while prone + in water + airborne.
            if (b.prone() && b.inWater() && !b.grounded()) vfwd = Math.max(vfwd, SERVO_FORWARD_MIN);
            b.setForward((float) vfwd);
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
                    if (bl > EPS && !blendLeavesLane(b, p, bx / bl, bz / bl)) {
                        dirx = bx / bl; dirz = bz / bl;
                    }
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
                        if (bl > EPS && !blendLeavesLane(b, p, bx / bl, bz / bl)) {
                            dirx = bx / bl; dirz = bz / bl;
                        }
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
            tag("servo:coast");
            b.faceHorizontally(dirx, dirz);                // at speed: hold heading, coast
            b.setForward(0.0f);
        } else {
            tag(hazardCorner ? "servo:hazard" : "servo:thrust");
            b.faceHorizontally(errx, errz);                // face the velocity error (forward thrust or reverse brake)
            b.setForward((float) Math.min(1.0, SERVO_GAIN * emag));
        }
    }

    /**
     * UPRIGHT-SWIM <b>velocity SERVO</b> horizontal drive (YAW-ONLY) — the fluid counterpart of
     * {@link #groundServo}, and the control half of the "fluid is a medium" design
     * . Drives the tall {@link
     * com.orebit.mod.pathfinding.blockpathfinder.movements.Swim Swim} / {@link
     * com.orebit.mod.pathfinding.blockpathfinder.movements.Surface Surface} pose and {@link #drive}'s in-water
     * branch. Vertical is NOT its business — {@link #holdDepth} owns the jump/sink.
     *
     * <h2>Why the controller it replaces could not work</h2>
     * Upright swim used to steer with {@link #swimTowards}, which faces a look-ahead point and holds forward,
     * and whose degenerate (vertical) branch is {@link #recenterOnTarget} — a pure POSITION P-controller that
     * commands EXACTLY ZERO inside {@link #COLUMN_DEADBAND}. A P-controller settles at a standing offset under
     * any constant disturbance, and inside its dead-band it does not even try. On the flagship waterfall
     * (2026-08-06) that is precisely what the log shows: {@code str=0.00} on every water tick while +Z velocity
     * held at ~0.054 b/t across eight consecutive ticks — vanilla's ~0.014/t flow push against 0.8 drag settles
     * at 0.07 b/t, the same order — so something external was moving the bot and nothing in the loop could see
     * it. {@code arriveOnTarget}'s javadoc convicts the same controller for parkour, for the same reason: "no
     * velocity term … always settles with standing overshoot."
     *
     * <h2>Why not {@link #swimServo}</h2>
     * Owner physics: a PRONE sprint-swimmer travels along its LOOK vector (look down + hold forward = descend),
     * so {@code swimServo} folds the depth pitch into its facing. An UPRIGHT swimmer does not — pitch is
     * horizontally inert, and it "is more like grounded movement". Hence the {@code groundServo} mould, with no
     * depth pitch and no {@link #SERVO_FORWARD_MIN} floor (that floor exists solely to retain the prone pose,
     * and holding W every tick to keep a pose is exactly what injected the lateral drift that made
     * {@code SprintSwim}'s verticals unusable).
     *
     * <h2>The two branches</h2>
     * <ul>
     *   <li><b>Along a segment</b> — desired velocity is the pursuit direction at an intentionally UNREACHABLE
     *       {@link #SERVO_CRUISE} ceiling, so the forward key saturates and the bot swims flat out while the
     *       error's CROSS-track component still tilts the heading against a current. This is the direct answer
     *       to "if we push forward but we somehow have lateral velocity, adjust yaw to counteract it".</li>
     *   <li><b>Degenerate (a pure vertical segment — the waterfall column)</b> — the case that actually
     *       mattered, and the one the old code handled worst. Desired velocity is a proportional pull toward
     *       the column centre, capped at {@link #UPRIGHT_SWIM_SPEED} (never ask the medium for more than it can
     *       deliver, or the error never leaves saturation and the loop cannot converge). Centred, the desired
     *       velocity is ZERO — so any residual momentum, from a current or from carried drift, becomes the
     *       whole error and is answered with reverse thrust. "Hold this column" becomes an active
     *       station-keep instead of a dead-band no-op.</li>
     * </ul>
     */
    public static void uprightSwimServo(BotSteering b, SteerView p) {
        computeGeom(b, p, SWIM_CTE_GAIN);

        double dvx, dvz, dirx, dirz;
        if (G.segLen < EPS) {
            // Pure vertical: station-keep over the target column. Desired velocity closes the horizontal
            // offset, capped at what an upright swimmer can actually swim; dead centre it is zero, which turns
            // the servo into a brake on whatever the water is doing to the bot.
            double ox = p.tx() - b.x(), oz = p.tz() - b.z();
            double od = Math.sqrt(ox * ox + oz * oz);
            if (od > EPS) {
                double sp = Math.min(UPRIGHT_SWIM_SPEED, UPRIGHT_STATION_GAIN * od);
                dvx = (ox / od) * sp; dvz = (oz / od) * sp;
                dirx = ox / od; dirz = oz / od;
            } else {
                dvx = 0.0; dvz = 0.0;
                dirx = 0.0; dirz = 0.0;
            }
        } else {
            dirx = G.qx - b.x(); dirz = G.qz - b.z();       // pursuit (along-track + cross-track return)
            double dl = Math.sqrt(dirx * dirx + dirz * dirz);
            if (dl < EPS) { b.setForward(0.0f); return; }
            dirx /= dl; dirz /= dl;
            dvx = dirx * SERVO_CRUISE; dvz = dirz * SERVO_CRUISE;
        }

        // Velocity error = desired - current (horizontal). Face ALONG the error, thrust proportional to its
        // magnitude: under-speed or pushed off-line → thrust that corrects BOTH; overshoot → the error points
        // up-track, the yaw flips, and the forward key becomes a brake. No velocity is ever written.
        double errx = dvx - b.velX();
        double errz = dvz - b.velZ();
        double emag = Math.sqrt(errx * errx + errz * errz);
        if (emag < SERVO_DEADBAND) {
            tag("uswim:coast");
            if (dirx != 0.0 || dirz != 0.0) b.faceHorizontally(dirx, dirz);
            b.setForward(0.0f);
        } else {
            tag("uswim:thrust");
            b.faceHorizontally(errx, errz);
            b.setForward((float) Math.min(1.0, SERVO_GAIN * emag));
        }
    }

    /**
     * Upright swim speed ceiling (blocks/tick) used as the station-keep cap: the wiki's 2.2 b/s surface paddle
     * is {@code 2.2/20 = 0.11}. Unlike the cruise ceilings this one is deliberately ACHIEVABLE — a station-keep
     * loop whose target the medium cannot reach would sit permanently saturated and never settle.
     */
    static final double UPRIGHT_SWIM_SPEED = 0.11;

    /**
     * Station-keep proportional gain (blocks/tick of desired closing speed per block of horizontal offset).
     * At 1.0 the servo asks to close the whole offset in one tick and is immediately clamped by
     * {@link #UPRIGHT_SWIM_SPEED}, so it means "return at full swim speed until nearly centred, then ease" —
     * the ease is what stops the return from becoming the next overshoot.
     */
    static final double UPRIGHT_STATION_GAIN = 1.0;

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
     * forward + sprint to touchdown" airborne drive (NOTES-movement-physics.md §1; the ice-overshoot / short-flat
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

    /** The vanilla ground-jump initial vertical velocity ({@code vy₀ = 0.42}) — the impulse the takeoff phase's
     *  {@link BotSteering#setJumping} produces, used by {@link #parkourLaunchShort} to predict the arc a jump we
     *  are ABOUT to fire would fly (the bot is still grounded at {@code vy ≈ −0.078} when we ask). */
    static final double PARKOUR_JUMP_VY = 0.42;

    /**
     * The parkour RUNUP <b>velocity-alignment</b> drive — the launch-axis pre-jump servo that replaces the
     * open-loop {@link #steerTowards} in a {@link
     * com.orebit.mod.pathfinding.blockpathfinder.movements.DiagonalParkour} runup. A diagonal jump launches a
     * ballistic arc, and the arc is only a clean 45° (the geometry the envelope + airborne servo assume) if the
     * bot's horizontal VELOCITY is on the jump axis at take-off. A cardinal (+X) run onto a diagonal (NE) takeoff
     * cell arrives with velocity that is 45° OFF the jump line — a large component PERPENDICULAR to the axis — so
     * an open-loop "face the pursuit point + full forward" launches a skewed arc that clips the near face of the
     * landing and drops into the gap. This closes the loop on the bot's momentum exactly like {@link #groundServo}
     * /{@link #swimServo}: the desired velocity is {@code (ux,uz)·}{@link #SERVO_GROUND_CRUISE} — pure along-axis,
     * ZERO cross-axis — so the velocity error {@code desired − current} points to BOTH advance along the axis AND
     * cancel (reverse-thrust) any perpendicular component. Facing that error and holding forward bleeds the
     * cross-axis velocity to ~0 while the bot approaches the takeoff edge, so by the time the jump fires the launch
     * is on-axis ({@code vx≈vz} for a 45° diagonal). No velocity is ever written — input only (look + forward),
     * the Baritone model. The cruise ceiling is unreachable (an over-terminal target, the {@code groundServo}
     * precedent), so on a safe straight the forward key saturates and this drives the runup exactly as hard as the
     * open-loop walk — it only ever STEERS the momentum onto the axis, never slows a bot already on it.
     */
    public static void parkourRunupAlign(BotSteering b, double ux, double uz) {
        double dvx = ux * SERVO_GROUND_CRUISE;               // desired velocity: pure along-axis, zero cross-axis
        double dvz = uz * SERVO_GROUND_CRUISE;
        double errx = dvx - b.velX();                        // error → advance along-axis AND null the cross-axis
        double errz = dvz - b.velZ();
        double emag = Math.sqrt(errx * errx + errz * errz);
        if (emag < SERVO_DEADBAND) {
            b.faceHorizontally(ux, uz);                      // on-axis at speed: hold heading, coast
            b.setForward(0.0f);
        } else {
            b.faceHorizontally(errx, errz);                  // face the error: forward thrust or cross-axis bleed
            b.setForward((float) Math.min(1.0, SERVO_GAIN * emag));
        }
    }

    /**
     * The take-off <b>launch-momentum sufficiency</b> test (called on the grounded jump tick by {@link
     * com.orebit.mod.pathfinding.blockpathfinder.movements.DiagonalParkour}): does a NON-sprint jump fired from
     * the bot's CURRENT along-axis momentum reach the landing cell, or will it drop short into the gap? Reuses the
     * ballistic {@link #predictAlongTouchdown} predictor — from the current along-axis position/velocity, with the
     * jump impulse {@link #PARKOUR_JUMP_VY} we are about to apply and the airborne servo's own forward-accel
     * policy ({@code dir=+1}, walk accel — the best a non-sprint arc achieves) — and compares the predicted
     * along-axis touchdown against the landing cell's NEAR edge ({@code C − 0.5 + }{@link #PARKOUR_CELL_MARGIN},
     * the same half-width floor the airborne servo never predicts below). Short ⇒ the caller injects a sprint for
     * the take-off (the extra jump-boost + accel the arc needs); this is the ONLY momentum lever left once the
     * launch is on-axis, since a slow (e.g. soul-sand) takeoff floor throttles the run-up speed to a plateau that
     * a plain jump can't fling across the gap. General — no floor/coordinate special-casing; it simply asks the
     * physics "does this launch reach?". The airborne servo brakes any resulting overshoot (it aims the landing
     * cell centre and reverse-thrusts), so injecting when NOT strictly needed is self-correcting, never a fall.
     */
    public static boolean parkourLaunchShort(BotSteering b, double ux, double uz, int tx, int ty, int tz) {
        double s = b.x() * ux + b.z() * uz;                  // current along-axis position
        double v = b.velX() * ux + b.velZ() * uz;            // current along-axis velocity (the run-up plateau)
        double landY = ty + 1.0;                             // FLAT diagonal: feet return to the takeoff level
        double pred = predictAlongTouchdown(s, v, b.y(), PARKOUR_JUMP_VY, landY, +1, PARKOUR_A_WALK);
        double c = (tx + 0.5) * ux + (tz + 0.5) * uz;        // landing centre, along-axis
        double nearEdge = c - 0.5 + PARKOUR_CELL_MARGIN;      // the near-edge floor a safe touchdown must clear
        return pred < nearEdge;
    }

    // ---- gate-point steering (the corner-gate primitive: DiagonalParkour runup + the P5 hug re-landing) ----

    /**
     * Along-track dead-band (blocks) for the {@link #pastGate} crossing test: the drive hands off from the
     * gate to the real target once the bot's along-track projection is within this of the gate's — a touch
     * EARLY, the safe side (the gate's own setback margin covers the residual), and single-sided so the
     * stateless test can't chatter aim points when wall-contact pushback jitters the projection right at the
     * exact crossing. The {@link #WATER_RISE_DEADBAND}/{@link #SERVO_DEADBAND} bang-bang idiom, applied to a
     * positional handoff.
     */
    static final double GATE_PASS_DEADBAND = 0.05;

    /**
     * <b>Gate-point steering</b> — drive toward an intermediate GATE point {@code (gx,gz)} until the bot's
     * along-track progress passes the gate's own projection ({@link #pastGate}), then toward the real target.
     * The primitive for the "pass NEAR a corner" family: a diagonal step past a blocked corner and a
     * diagonal-parkour takeoff approach are the same geometry problem — straight line-tracking either presses
     * the hitbox into the blocked corner (the P5 hug freeze: the ground servo's cross-track return toward the
     * start→target centerline exactly cancels the wall-slide the hug needs) or spills the grounded foot cell
     * laterally off the takeoff block (the DiagonalParkour envelope churn). The point-mass insight: the
     * hitbox overhang only matters while GROUNDED, and support only matters at the jump/step tick — so aim
     * via ONE point, the corner offset by the body radius (0.3) plus a small margin toward the side the
     * consumer owns, and the whole corner interaction disappears.
     *
     * <p><b>What it deliberately does NOT do:</b> while short of the gate there is <i>no cross-track return
     * toward the {@code start→target} line</i> — no {@code computeGeom} pursuit, no centerline term. That
     * recentering is exactly what refuted the search-side hug (the servo and the wall reached a fixed point
     * at the blocked corner); the gate leg is a pure point pursuit of {@code (gx,gz)}. Past the gate the aim
     * is the target POINT (not the line either — a line-return would pull the bot back toward the corner it
     * just cleared).
     *
     * <p><b>Actuation</b> is the standard velocity-servo idiom ({@link #groundServo}/{@link
     * #parkourRunupAlign}): desired velocity = unit(aim − bot) × {@link #SERVO_GROUND_CRUISE} (an unreachable
     * ceiling on normal friction, so forward saturates like the open-loop walk), face the velocity ERROR and
     * thrust proportional — so momentum off the gate line is BLED (reverse-thrust component), which is what
     * keeps a laterally arriving runup from spilling past its corner. Both aims are PASS-THROUGH (constant
     * cruise, never eased by distance): the bot must carry speed through the gate and through the target — a
     * consumer that wants to STOP at the target hands off to {@link #recenterOnTarget} once its reach fires.
     *
     * <p><b>Consumers.</b> {@code DiagonalParkour}'s runup passes the takeoff-corner gate (the cell's exit
     * corner pulled {@code 0.3+margin} back INTO the takeoff cell along the diagonal). The intended P5 hug
     * consumer (a one-open-side walking {@link com.orebit.mod.pathfinding.blockpathfinder.movements.Diagonal}
     * — the re-landing of the reverted search-side hug) passes the shared cell corner offset {@code 0.3+margin}
     * toward its OPEN side (§3.2's gate point, e.g. corner {@code (1,1)} → gate {@code (0.7,1.3)} for a
     * blocked {@code (x+dx,z)} column): short of the gate the bot walks INTO the open column instead of
     * pressing the corner, past it the aim swings to the destination centre. Cold (tick-rate), primitives
     * only, zero allocation.
     */
    public static void steerViaGate(BotSteering b, SteerView p, double gx, double gz) {
        steerViaGate(b, p.sx(), p.sz(), p.tx(), p.tz(), gx, gz);
    }

    /**
     * Explicit-coordinate core of {@link #steerViaGate(BotSteering, SteerView, double, double)} for consumers
     * whose gate frame is their own plan geometry rather than the follower's live segment view (the
     * {@code DiagonalParkour} runup anchors on its known takeoff/landing cells so a mid-path adoption can't
     * skew the projection axis). {@code (sx,sz)} → {@code (tx,tz)} is the along-track axis the crossing is
     * measured on; {@code (gx,gz)} the gate.
     */
    public static void steerViaGate(BotSteering b, double sx, double sz, double tx, double tz,
                                    double gx, double gz) {
        double aimX, aimZ;
        if (pastGate(b, sx, sz, tx, tz, gx, gz)) {
            aimX = tx; aimZ = tz;       // past the gate: the real target owns the aim (point, never the line)
        } else {
            aimX = gx; aimZ = gz;       // short of the gate: pure gate pursuit — NO centerline return
        }
        double dirx = aimX - b.x(), dirz = aimZ - b.z();
        double dl = Math.sqrt(dirx * dirx + dirz * dirz);
        if (dl < EPS) {                 // dead-on the aim point: nothing to press this tick
            b.setForward(0.0f);
            return;
        }
        dirx /= dl; dirz /= dl;
        // Velocity-servo actuation (the groundServo/parkourRunupAlign idiom): under-speed → forward thrust
        // along the aim; momentum OFF the aim line → the error's reverse component bleeds it.
        double errx = dirx * SERVO_GROUND_CRUISE - b.velX();
        double errz = dirz * SERVO_GROUND_CRUISE - b.velZ();
        double emag = Math.sqrt(errx * errx + errz * errz);
        if (emag < SERVO_DEADBAND) {
            b.faceHorizontally(dirx, dirz);                // at speed on the aim line: hold heading, coast
            b.setForward(0.0f);
        } else {
            b.faceHorizontally(errx, errz);                // face the error (forward thrust or momentum bleed)
            b.setForward((float) Math.min(1.0, SERVO_GAIN * emag));
        }
    }

    /**
     * Whether the bot is PAST the gate: its along-track projection on the {@code (sx,sz)→(tx,tz)} axis has
     * reached the gate's own projection minus {@link #GATE_PASS_DEADBAND} (the early-side slack — see the
     * constant). Cross-track position is deliberately not consulted: the gate governs the handoff point
     * ALONG the travel, and the aim/servo governs the lateral. Also the {@code DiagonalParkour} takeoff
     * trigger ("jump early — while the centre is still safely on the takeoff block"): grounded + pastGate
     * replaces the old drive-to-the-edge overshoot trigger whose late fire spilled the foot cell laterally.
     * A degenerate axis ({@code len < }{@link #EPS}) has no gate geometry and reads as past (the target owns
     * the aim).
     */
    public static boolean pastGate(BotSteering b, double sx, double sz, double tx, double tz,
                                   double gx, double gz) {
        double segX = tx - sx, segZ = tz - sz;
        double len = Math.sqrt(segX * segX + segZ * segZ);
        if (len < EPS) return true;
        double ux = segX / len, uz = segZ / len;
        double alongBot = (b.x() - sx) * ux + (b.z() - sz) * uz;
        double alongGate = (gx - sx) * ux + (gz - sz) * uz;
        return alongBot >= alongGate - GATE_PASS_DEADBAND;
    }

    /** The current segment's horizontal travel frame into scratch {@code F}; false if degenerate (a dive/rise). */
    private static boolean travelFrame(SteerView p) {
        double cdx = p.tx() - p.sx(), cdz = p.tz() - p.sz();
        double cl = Math.sqrt(cdx*cdx + cdz*cdz);
        if (cl < EPS) return false;
        F.ux = cdx / cl; F.uz = cdz / cl;
        F.cx = (int) Math.floor(p.tx());
        F.cz = (int) Math.floor(p.tz());
        // The waypoint's FEET cell. Value-preserving across the 2026-08-15 frame change: this read
        // `floor(p.ty()) - 1` when ty() was the feet cell's ceiling, which evaluated to the same cell index
        // this now yields directly. (NOTE the old comment called it the "floor cell"; the arithmetic said
        // otherwise then and says otherwise now. Left as-is deliberately — correcting the CELL would be a
        // behaviour change, not a frame change, and belongs in its own commit with its own evidence.)
        F.cy = (int) Math.floor(p.ty());
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
        holdDepthAt(b, swimDepthTarget(p, bias));
    }

    /**
     * {@link #holdDepth} against an ABSOLUTE {@code depth} instead of the step's planned one — the same
     * bang-bang autopilot, with the target supplied by the caller.
     *
     * <p>Exists because the two callers want the depth from opposite places. A MOVE is travelling, so its
     * target is the segment's planned feet height ({@code p.ty() - bias}) and {@link #holdDepth} is the
     * right spelling. The runner's {@link #stationKeep} hold is NOT travelling: consulting the step's
     * planned height there would drive the bot toward the cell it has been stopped from entering — the
     * vertical twin of the {@code (58,133,189)} wedge that made the hold re-centre on its OWN column rather
     * than the target, and the same reason {@code stationKeep} refuses to delegate its vertical to
     * {@link #holdClimbableStance} (whose descend branch reads the MOVE's intent). So the hold passes its
     * own cell instead.
     *
     * <p>No-op out of fluid, so a caller need not pre-test the medium — the guard IS the medium test.
     */
    public static void holdDepthAt(BotSteering b, double depth) {
        if (!b.inWater() && !b.inLava()) { // the autopilot works in ANY fluid (lava swims like slow water)
            return;
        }
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
    /**
     * CLIMBABLE-STANCE HOLD (owner physics, manual proof 2026-08-01). A curtain is not a floor, so a move
     * executing at one needs the vertical input that HOLDS its stance, or the bot sinks and the move can
     * never complete. Two stances, two different inputs:
     *
     * <ul>
     *   <li>feet ABOVE a climbable (topped out) &rarr; hold JUMP. It does not cancel the sink; it out-runs
     *       it by instantly re-climbing at the surface. Sneak would hold too, but sneak's ledge edge-guard
     *       forbids stepping OFF the curtain top and would trap the bot on the cell it came from.</li>
     *   <li>feet INSIDE a climbable (lateral cling) <b>with nothing standable underneath</b> &rarr; hold
     *       SNEAK. Here jump CLIMBS rather than holds, so sneak is the only stance-hold. Sneak does NOT
     *       block a simultaneous climb and does not change climb speed, so it is safe to hold whenever we
     *       are not deliberately descending — <i>provided</i> there is genuinely nothing below to stand on
     *       (see the {@link BotSteering#standableBelow} gate below).</li>
     * </ul>
     *
     * <p>Released when the segment actually wants to go DOWN (the move is a descent through/off the
     * curtain), the one case where sinking is the intent. Convicted by the 2026-08-01 flagship stall at
     * {@code (129,~115.5,132)}: a Descend atop a curtain bounced 115&harr;116 for ~12000 ticks, sinking in
     * and being re-lifted, invisible to every envelope because it is never settled.
     *
     * <p>Note the ratchet asymmetry: the wall-press climb can only fire with feet INSIDE the cell, so the
     * topped-out hold cannot lift the bot away from its own lane.
     *
     * <p>BOTH stances require {@code !grounded()}: the hold applies only to a bot actually SUSPENDED on the
     * curtain, never to one standing on a real block that merely happens to have vines in its feet or under
     * it. Ground-level vine is everywhere in jungle, and holding sneak there is actively harmful — sneak's
     * ledge edge-guard forbids stepping off a lip, trapping the bot on the block it was leaving (owner's
     * warning; measured: flagship best regressed 58.43 &rarr; 212.55 without this guard).
     *
     * <p><b>The feet-INSIDE sneak additionally requires {@link BotSteering#standableBelow} to be FALSE</b>
     * (owner ruling 2026-08-02). Sneak is not one effect but two: it zeroes the {@code −0.15}/t climbable
     * slide (the stance-hold we want) AND it arms vanilla's ledge edge-guard ({@code
     * Entity/Player.maybeBackOffFromEdge}), which deletes horizontal motion that would carry the box past
     * an unsupported lip. So the question the gate must ask is not "am I in a climbable and not
     * descending" but WHAT IS UNDERNEATH:
     * <ul>
     *   <li>no standable underneath &rarr; the fall is ours to arrest, and the climbable is the only thing
     *       that can arrest it &rarr; SNEAK (unchanged behaviour).</li>
     *   <li>a standable underneath &rarr; there is nothing to arrest; a step-off simply lands. Sneaking
     *       here buys no height we need and costs us the ability to move &rarr; do NOT sneak, just walk
     *       off.</li>
     * </ul>
     *
     * <p>Convicted 2026-08-02, jungle-vine descent. Per-tick at the stall: {@code botY=171.172 sneak=false
     * hcol=false dm=(0.0747,-0.2254,0.0120)} (free climbable slide), then {@code botY=171.022 sneak=TRUE
     * hcol=false} and thereafter {@code x=60.289 sneak=true hcol=false dm=(0.0568,-0.0784,0.0000)} —
     * FROZEN. {@code hcol=false} on every tick means there was no horizontal collision anywhere: nothing
     * was being pressed against, yet a non-zero {@code deltaMovement} was commanded every tick and yielded
     * ZERO displacement. That signature is the sneak edge-guard, not a wall. The bot sat at
     * {@code (60.289, 171.022, 255.500)}, {@code 0.011} short of the {@code x >= 60.300} it needed to stop
     * overhanging {@code x=59} and reach its target column centre {@code 60.5}. It entered the state
     * because it was {@code 0.022} above its target feet height {@code 171.00} — INSIDE the {@code 0.05}
     * {@code descending} margin — so {@code descending} read false and sneak engaged, while the leaves at
     * {@code (60,169,255)} were holding a perfectly good floor {@code 1.02} blocks under its feet.
     *
     * <p><b>Why this cannot reintroduce the 58.43 &rarr; 212.55 regression.</b> That regression is the
     * OPPOSITE error — sneaking where sneak is harmful — and the fix moves strictly in the safe direction:
     * it only ever REMOVES a sneak press, never adds one. The {@code !grounded()} guard above is untouched
     * and still does its job. Ground-level jungle vine, the case that produced those numbers, is vine
     * growing over solid ground: it now has a standable below and so is refused sneak by a SECOND
     * independent test as well, which can only reinforce the guard. A genuine hang — a bot suspended on a
     * curtain with nothing but air under it — has no standable below, reads {@code false}, and keeps
     * sneaking exactly as before. The {@code climbableBelow} top-out branch is untouched, and the branches
     * stay mutually exclusive: a bot with its feet INSIDE a climbable must never fall through to the JUMP
     * branch (in the convicted geometry {@code climbableBelow} was also true — the cell below was a second
     * vine — and jumping there would climb the bot back UP its curtain).
     *
     * <p>Applied by BOTH the locomotion {@link #drive} and the runner's {@link #stationKeep} hold — a stance
     * the bot must keep while WALKING it must equally keep while standing still to mine or place, or the
     * "stop and fix the geometry" hold quietly slides it off the frame its plan was built from.
     */
    public static void holdClimbableStance(BotSteering b, SteerView p) {
        holdClimbableStance(b, p, false);
    }

    /**
     * As {@link #holdClimbableStance(BotSteering, SteerView)}, with the <b>translating</b> discriminator.
     *
     * <p><b>Why the CALLER must supply it (owner ruling 2026-08-02, scope correction).</b> The ruling reads
     * "<i>moving laterally</i>, on a climbable, with a standable underneath ⇒ don't sneak, just walk off".
     * That qualifier is load-bearing, and this method cannot see it: it is called from BOTH the locomotion
     * {@link #drive} — where the bot IS being translated and sneak's ledge edge-guard is exactly what pins it
     * (measured 2026-08-02 at {@code x=60.289}: {@code hcol=false}, non-zero deltaMovement, ZERO displacement,
     * forever) — and the runner's {@link #stationKeep} hold, where the bot is deliberately NOT translating
     * (it re-centres on its OWN column, and {@link #recenterOn} emits exact zero forward inside
     * {@link #COLUMN_DEADBAND}).
     *
     * <p>On the station-keeping path the edge-guard costs nothing — any residual recentre is INWARD, toward
     * support, which the guard permits — while the slide-suppression is the whole value. Relaxing there would
     * resurrect the already-convicted {@code (58,133,189)} bug from the opposite direction: a bot hanging in a
     * curtain above jungle leaves would drop the hold for the entire mine (≥5 ticks at −0.15/t ≈ a full block)
     * and ground one cell BELOW the frame its plan was built from — precisely what the closing paragraph of
     * {@link #holdClimbableStance(BotSteering, SteerView)} exists to forbid. So {@code false} is the default
     * and the relaxation is opt-in, applied only where the bot is genuinely being driven.
     */
    /**
     * Diagnostic ONLY: the stance servo's decision on its last call — {@code intent}, the live {@code err},
     * and the branch taken. Written every call, read by the follower's {@code exec} log so a wedge shows WHY
     * the servo chose what it chose instead of leaving it to be inferred. Never read by logic.
     */
    public static volatile String lastStance = "-";

    /**
     * Diagnostic ONLY: WHICH servo branch actually wrote this tick's movement inputs. Written by every drive
     * entry point, read by the follower's {@code exec} log. Never read by logic.
     *
     * <p><b>Why it exists</b> (2026-08-06). {@code fwd} and {@code yaw} alone cannot identify the author of an
     * input, and the branches mean completely different things by the same numbers: {@link #groundServo} faces
     * the VELOCITY ERROR (so its yaw is a thrust direction, not a heading), {@link #arriveOnTarget} faces the
     * SEGMENT, {@link #stepOffGate}'s arrest faces the cross-axis correction, and a movement's own deadband
     * writes {@code 0} while facing nothing at all. Two mechanism claims were derived from that ambiguity and
     * both were wrong — the log said "the follower steered down the old segment" when the real answer was
     * "the velocity servo was thrusting to kill a diagonal carry". One token removes the whole class of error.
     */
    public static volatile String lastDrive = "-";

    /** Record the drive branch for {@link #lastDrive}; diagnostic-only, so it can be called freely. Public so a
     *  movement that writes inputs directly (e.g. {@code Descend}'s column deadband) can name itself too. */
    public static void tag(String branch) { lastDrive = "#" + (++driveCalls) + " " + branch; }

    /** Diagnostic ONLY: call counter for {@link #lastDrive}, mirroring {@link #stanceCalls}. A drive tag is a
     *  STATIC last-writer-wins slot, so a tick on which NO servo ran reprints the previous tick's branch
     *  verbatim — indistinguishable, without a counter, from that branch having genuinely run again. A
     *  repeated {@code #N} across two consecutive {@code exec} lines proves the second line's {@code src},
     *  {@code yaw} and heading are STALE, not a fresh decision. (2026-08-12: an apparent 180-degree facing
     *  inversion on a replan tick was read off exactly such a duplicated line.) */
    private static int driveCalls;

    /** Diagnostic ONLY: call counter, so a log line can prove {@link #lastStance} is THIS tick's decision and
     *  not a stale one left by an earlier call (the difference between "the branch didn't press" and "the
     *  branch never ran"). */
    private static int stanceCalls;

    public static void holdClimbableStance(BotSteering b, SteerView p, boolean translating) {
        if (b.grounded()) { lastStance = "grounded"; return; } // a real floor holds the stance; no input needed
        // The step's OWN vertical intent (-1 / 0 / +1), taken from the segment's start->target feet heights —
        // NOT from the bot's live position error. Those differ, and the difference matters: a bot sagging
        // below its target INSIDE a lateral cling (the vine sag) has a position error but its step is still a
        // Δy==0 hold, and must sneak to arrest the sag rather than jump and ratchet up the curtain. Reading
        // the error instead misclassified exactly that case (ClimbSteerTest.lateralClingHoldsSneak). The
        // environment tests below stay live and per-tick; it is only the INTENT that is fixed by the plan.
        final double intent = p.ty() - p.sy();   // which way the STEP wants to go: -1 / 0 / +1
        // The live error, IN THE BOT'S FRAME. Since 2026-08-15 SteerView's y IS the bot's frame — the base of
        // the feet cell — so this is now a plain read. It used to be the "feet-cell-plus-one" frame and had to
        // subtract the block straight back off; getting that wrong was measured 2026-08-02 as `err=0.95` on a
        // bot sitting exactly on its target (ty=170.0 for feet cell 169, botY=169.055), which read as "still a
        // block short" forever and never let the stance arrest. That whole hazard is gone with the frame.
        // (`intent` above is a segment DELTA and was frame-free either way.)
        final double floorY = p.ty();            // the target FEET CELL's floor, in the bot's own frame
        final double err = floorY - b.y();
        // SETTLED IS A BAND, NOT A POINT (owner ruling, 2026-08-03). "Settled on the floor of a cell" is
        // [X.00, X.20] inclusive: the bot is 1.8 tall, so at X.20 its head still occupies exactly the headroom
        // cells the planner assumed, and anything lower is a legitimate resting pose.
        //
        // Treating it as a POINT (|err| < 0.05) was a knife-edge no descent could hit. Measured 2026-08-03: a
        // Fall descending at 0.45 blocks/tick stepped 173.875 -> 173.425 -> 172.975 straight OVER the 0.1-wide
        // window, never once satisfied the hold, and by the time the error went positive it had left the vine
        // (climbable=false) and had nothing to arrest against — it rode down to 170.5, ~2.5 blocks past its
        // landing. The bot was IN its target cell at 173.425 and the servo still called it "descending",
        // because it measured against the cell FLOOR rather than asking whether the feet were in the cell.
        final boolean aboveBand = b.y() > floorY + SETTLE_BAND;
        final boolean belowFloor = b.y() < floorY;
        // THE BAND DECIDES, NOT THE STEP'S INTENT (owner ruling 2026-08-12, the (61,169,253) lateral-Climb
        // wedge). The three inputs a climbable affords — jump RISES, sneak HOLDS, nothing FALLS — are chosen
        // by comparing where the feet ARE against where they should BE, targeting the settled band
        // [floorY, floorY + SETTLE_BAND]: below the floor is potentially fatal (out the bottom of the column
        // with nothing left to grab), above the band is simply not arrived yet.
        //
        // The gate this replaces read the STEP's vertical intent ({@code intent > RISE_EPS && belowFloor},
        // {@code intent < -RISE_EPS && aboveBand}), which meant a LATERAL step — intent == 0, the ledge→vine
        // transfer and the ladder-plate crossing — could never take either corrective branch: dy collapsed to
        // 0.0 for EVERY height error, so the HOLD branch below pressed sneak unconditionally and the bot
        // arrested wherever it happened to be. Measured on the flagship 2026-08-12, Climb
        // (61,168,254)->(61,168,253): the jump-grab entry launched a real 0.42 arc off a placed cobble,
        // crossed into the target column 0.078 below its apex, and the vine caught the feet at botY=170.122 —
        // foot cell (61,170,253), a FULL CELL above the target (61,169,253). From there the servo read
        // int=0.00, err=-1.12, dy=0.00 and pressed sneak for 253+ consecutive ticks: the hold actively
        // suppressed the -0.15/t slide that was the only thing which could have corrected the error, `done`
        // (footY == 169) could never fire, and the move wedged permanently. hcol was false on every tick —
        // this was NOT the horizontalCollision ratchet the same failure shape usually implies.
        //
        // Dropping the gate restores the rule exactly as the owner stated it on 2026-08-02 ("if we're on a
        // climbable and our Y is below the Y we want, hold jump; if it's above, hold nothing; if it's AT the
        // Y we want, hold sneak") — that rule never mentioned the step's direction, and direction is
        // precisely what a lateral step does not have. `intent` now survives only in the diagnostic below.
        //
        // The vine-sag case the old gate was written for is UNAFFECTED, and its fixture PROVES that rather
        // than merely tolerating it: ClimbSteerTest.lateralClingHoldsSneak puts a Δy==0 bot at botY=177.2
        // against ty=178.0, i.e. floorY=177.0 — belowFloor false, aboveBand false (177.2 > 177.20 is false).
        // It sits INSIDE the band, so the band rule holds sneak there for the same reason the gate did. The
        // gate was never what made that test pass; it merely also suppressed every case the band would catch.
        //
        // The two non-lateral corners this newly reaches are both corrections TOWARD the band, in the
        // direction the ruling already argues for: a RISE that overshot above the band now releases instead
        // of holding high, and a DESCENT that sank below the floor now climbs back instead of arresting below
        // its own target (the "potentially deadly" side). Inside the band every intent still holds, unchanged,
        // and the RISE_EPS deadband still swallows a sub-0.05 error so no step chatters on arrival.
        final double dy = belowFloor ? err : aboveBand ? err : 0.0;
        lastStance = String.format("#%d int=%.2f err=%.2f dy=%.2f clb=%b stb=%b tr=%b grd=%b",
                ++stanceCalls, intent, err, dy, b.onClimbable(), b.standableBelow(), translating, b.grounded());
        if (dy > RISE_EPS) {
            // +1 — RISE. In a climbable, jump is the climb input; out of one it is the jump. Same press either
            // way, so no medium test is needed. This is the case a single `descending` flag used to swallow:
            // it lumped "rising" in with "holding height" and pressed SNEAK, which pins the bot at its current
            // height forever AND arms the ledge edge-guard that kills the lateral half of the step. Measured
            // 2026-08-02 on the flagship: a Climb to (61,169,252) with ty=170.00 against botY=169.055 — Δy of
            // +0.945, unambiguously a rise — sat frozen at z=253.700 with sneak held, dm commanded every tick
            // and zero displacement, until the tick budget ran out.
            b.setJumping(true);
        } else if (dy < -RISE_EPS) {
            // -1 — DESCEND. Hold NOTHING and let the drop run… UNLESS this tick would carry the feet BELOW
            // the target cell's floor. Then tap sneak to arrest NOW (owner ruling, 2026-08-03: "tap sneak for
            // one tick when we enter the block to slow our fall, then monitor momentum and aim for X.20 or
            // less"). Reacting only once the band is entered is too late at speed — a 0.45 b/t descent
            // crosses the whole 0.20 band inside a single tick — and overshooting is not a cosmetic error:
            // dropping to 172.999 puts the feet BELOW the vine cell with nothing left to grab, which is how
            // the measured run ended up 2.5 blocks low.
            //
            // Anticipation only helps where an input can actually arrest, i.e. on a climbable; in free air
            // sneak does nothing and the drop is the plan's own business. One tap is enough: the clamp takes
            // the slide to 0, and releasing next tick resumes at the -0.15 climbable rate, which eases the
            // bot down into the band a sixth of a block at a time instead of flying past it.
            // A "nothing beneath us ⇒ hold" term was tried here on 2026-08-04 and REVERTED the same day.
            // It is wrong as a general rule, and CarryArrestGateTest.aDeliberateDescentReleasesBothHolds
            // already said so: that fixture is a bot inside a curtain 1.0 off its target column with a
            // 4-block drop ahead, and a deliberate descent must RELEASE there, void beneath or not. Fall's
            // §3.2 release-drop is the same shape — letting go IS the plan, and arresting it would strand
            // the bot on the vine it was told to leave. Holding height until the bot is over its target
            // column is a MOVE's concern (see Descend's CLEAR/STEP), not this servo's: only the move knows
            // whether letting go lands it where it meant to go.
            if (b.onClimbable() && b.y() + b.velY() < floorY) {
                b.setSneak(true);
            }
            return;
        } else if (b.onClimbable()) {
            // 0 — HOLD HEIGHT with the feet INSIDE a climbable. Jump would climb and gravity would slide, so
            // sneak is the only input that holds — but sneak also arms vanilla's ledge edge-guard, which
            // deletes horizontal motion that would carry the box off its supporting column. So press it only
            // when there is genuinely nothing below to catch us.
            //
            // Re-read EVERY tick, which is the whole point: standableBelow() asks about the columns the bot's
            // bounding box currently overlaps, so as the box crosses a lip the answer flips by itself. A step
            // that starts overhanging a floor gets NO sneak (it is free to cross) and, the moment the box
            // clears into the unsupported column, standableBelow() goes false and the hold engages before the
            // bot can fall. Half the movement unsneaked and half sneaked, with no state and no timers.
            //
            // `translating == false` is the runner's stationKeep hold — the bot is deliberately NOT moving
            // (it re-centres on its OWN column at exact zero forward inside COLUMN_DEADBAND). No lip is being
            // crossed, so the edge-guard costs nothing and the hold is pure gain; relaxing there would slide
            // the bot off the frame its plan was built from during a mine (>=5 ticks at -0.15/t ~ a full
            // block) — the already-convicted (58,133,189) failure, re-entered from the other side.
            if (!translating || !b.standableBelow()) b.setSneak(true);
        } else if (b.climbableBelow()) {
            // 0 — HOLD HEIGHT with the feet ABOVE a curtain (the top-out). Nothing to stand on, so the bot
            // sinks in and vanilla re-lifts it; jump out-runs the sink by re-climbing at the surface. Sneak
            // would hold it too, but its edge-guard would forbid ever stepping OFF the curtain top.
            b.setJumping(true);
        }
    }

    public static void drive(BotSteering b, SteerView p) {
        // BLOCKED PRESS ON A CLIMBABLE — release (owner rule, finally implemented 2026-08-05 after it was
        // the answer at three separate sites). Vanilla turns (horizontalCollision || jumping) && onClimbable
        // into vy = +0.2, so a press that cannot move the bot HORIZONTALLY is converted entirely into
        // ALTITUDE. Continuing to hold it is not merely useless, it actively climbs the bot out of the frame
        // its plan was built from, and the higher it goes the more geometry it fouls — the (57,172,255)
        // ratchet to a leaf ceiling, and the (58,170,253) limit cycle where the bot was ALREADY in its
        // target column and only needed to drop.
        //
        // Releasing is the whole fix: with no input, the climbable's own physics take over (the -0.15 slide,
        // or a rest if something supports it), the bot returns to its band, and the settle gate re-admits it.
        // "How long" needs no timer — the condition is re-read every tick, so the press resumes the moment it
        // can actually produce displacement.
        //
        // Gated on onClimbable() so ordinary walking into a wall is untouched: there a blocked press is
        // harmless, and releasing it would break every move that leans on collision to slide along geometry.
        // If the obstruction is real and permanent the move now STALLS visibly instead of climbing — which is
        // the correct outcome under the no-recovery rule, and legible in a way the ratchet never was.
        // climbableBelow() is included, not just onClimbable() (narrowed-then-widened 2026-08-05). A bot
        // oscillating across a cell boundary is IN the climbable only on the ticks it dips below it —
        // measured at (58,170,253), roughly one tick in four — and on the other three an onClimbable-only
        // gate let the full drive press straight back into the wall, which is what re-armed the collision
        // that the next dip converted to +0.2. Releasing only on the dip tick cannot break a cycle whose
        // energy is supplied by the ticks in between. The question is not "am I in a climbable right now"
        // but "is the involuntary climb available in this column", and a vine one cell down answers yes:
        // the bot is one tick of gravity away from being in it.
        if ((b.onClimbable() || b.climbableBelow()) && b.horizontalCollision()) {
            tag("release:blocked");
            holdClimbableStance(b, p, true);
            b.setForward(0.0f);
            return;
        }
        holdClimbableStance(b, p, true);   // the locomotion path: the bot IS translating
        if (b.inWater()) {
            // A GROUND move still in water (leaving onto a bank, clipping a stream, knocked into a pool
            // mid-segment) is an UPRIGHT body in fluid, so it gets the upright velocity servo — same reason
            // Swim/Surface do: the position-only swimTowards it used to call cannot see a current at all
            // (§6). holdDepth still lifts/sinks it toward the planned cell.
            uprightSwimServo(b, p);
            holdDepth(b, p, 0.0);
        } else if ("servo".equals(GROUND_DRIVE)) {
            groundServo(b, p);            // input-only velocity servo (holds a 1-wide low-friction lane); A/B-gated
        } else {
            steerTowards(b, p);           // legacy open-loop walk (default)
        }
    }

    /**
     * LANE CONTAINMENT for the corner blend (owner ruling 2026-08-01). The blend may round toward the next
     * leg only while doing so keeps the bot inside the CURRENT step's lane; once the bot is at the lane
     * bound the blend is dropped for any tick whose cross component points further OUT.
     *
     * <p><b>Why.</b> The blend rotates up to {@link #CORNER_BLEND_MAX} of the heading toward the next leg and
     * adds a {@link #CORNER_RACING_BIAS} OUTWARD push, starting {@link #CORNER_BLEND_DIST} = 1.3 blocks out —
     * i.e. more than a full cell before arrival. Every converted movement's {@code failWhen} envelope admits
     * only that step's own columns, so on any corner where the next leg turns, the steer deliberately drives
     * the bot out of the lane and the envelope fail&rarr;HOLDs it for obeying the steer. Three instances in
     * one 2026-08-01 flagship pair — {@code (58,113,160)}, {@code (62,135,189)}, {@code (143,113,13)} — and
     * the instrumented capture at the last shows it plainly: the bot starts the step AT REST and centred
     * ({@code vel=(0,0)}, {@code z=14.419} on a constant-z step) and the cross velocity is MANUFACTURED,
     * growing monotonically with {@code w} as {@code dCorner} shrinks (−0.009 → −0.019 → −0.037 → −0.052)
     * until {@code z} crosses the cell boundary. Not momentum, not terrain: the steering law and the
     * validity envelope simply disagreed about where the bot is allowed to be.
     *
     * <p>Positional, not predictive — no horizon and no new constant. It reuses the same lane half-width the
     * step-off gate uses ({@code 0.5 −} {@link #PARKOUR_CELL_MARGIN} {@code = 0.2}), so the bot may still
     * round a corner up to the lane bound and only then holds the line. Where there IS room the blend
     * survives untouched, which is what keeps its original purpose (an orthogonal run-up into a parkour,
     * rounding wide to keep the hitbox off the inside flank column — the clip IS the ejection) alive.
     *
     * @return {@code true} when the blended heading would push the bot further outside its lane.
     */
    private static boolean blendLeavesLane(BotSteering b, SteerView p, double bx, double bz) {
        double segx = p.tx() - p.sx(), segz = p.tz() - p.sz();
        double sl = Math.sqrt(segx * segx + segz * segz);
        if (sl < EPS) return false;                     // degenerate (vertical) segment — no lane to leave
        segx /= sl; segz /= sl;
        // Signed offset of the bot from the segment LINE, and the blend's cross component in the same sense
        // (right-hand perpendicular of the segment is (segz, -segx)).
        double cte = (b.x() - p.sx()) * segz - (b.z() - p.sz()) * segx;
        if (Math.abs(cte) <= 0.5 - PARKOUR_CELL_MARGIN) return false;   // still inside the lane — blend freely
        double bcross = bx * segz - bz * segx;
        return cte > 0 ? bcross > 0 : bcross < 0;       // at the bound and still heading out
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
