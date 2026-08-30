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
     * Swim pursuit cross-track gain: the look-ahead shrinks as cross-track error grows
     * ({@code lookahead = LOOKAHEAD / (1 + SWIM_CTE_GAIN * cte)}). At {@code cte = 0} the swim drive is the
     * same fixed-look-ahead pursuit ground steering uses; off the line the aim swings toward the
     * PERPENDICULAR foot point, so the recovery is a hard return rather than a lazy diagonal.
     *
     * <p><b>Kept through the 2026-08-26 rewrite, and the reason matters.</b> That pass deleted the swim HAZARD
     * probes (overshootHazard / flankHazard / hazardColumn) because they asked what was in a CELL — a question
     * the follower has no business re-litigating after the planner priced it. This is a different kind of
     * constant: it is about the bot's relationship to its own PLANNED LINE, which is the one thing the
     * follower unambiguously does own. The first cut of the rewrite dropped it along with the probes, on the
     * theory that a position-anchored law cannot cut a corner. That was wrong, and measurably so: anchoring on
     * the next WAYPOINT CENTRE while still short of the current one IS a corner cut, because the desired
     * velocity points diagonally across the lane. SwimCourse `mazeportal` convicted it in one run —
     * "teleported out of the course dimension (clipped a portal wall)" — the exact 0.026-block graze the lane
     * geometry was introduced to prevent. The endpoint is where the bot is GOING; the line is where it must
     * STAY, and a law that tracks only the former has no opinion about the latter.
     */
    static final double SWIM_CTE_GAIN = 6.0;

    /** Vanilla's ground input SPEED for a sprinting player: {@code 0.1 × 1.3}. See {@link #actuate} for why
     *  the sprint value is assumed rather than the walk one — under-command converges, over-command
     *  oscillates. */
    static final double GROUND_INPUT_SPEED = 0.1 * 1.3;
    /** The numerator of vanilla's friction-compensated ground accel, {@code speed × (0.216/friction³)}. */
    static final double VANILLA_ACCEL_NUMERATOR = 0.21600002;
    /** Vanilla scales the movement input by this before applying it ({@code moveRelative}'s 0.98). */
    static final double VANILLA_INPUT_SCALE = 0.98;

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
     * A/B + revert switch for {@link #drive}'s LAND branch (the chokepoint the ground moves Traverse/Descend/
     * Diagonal steer through): {@code "servo"} (default) = the input-only velocity {@code groundServo} (deleted 2026-08-24) (hazard-
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
    /** Servo forward-key gain: {@code forward = clamp(SERVO_GAIN * |velocityError|, 0, 1)}. Large enough that a
     *  ~0.05 b/t error already saturates, so acceleration (under-speed) and braking (overshoot → reverse thrust)
     *  are both crisp; the hazard speed ramp — not this gain — sets the arrival speed. */
    static final double SERVO_GAIN = 18.0;
    /** Servo dead-band (b/t): below this velocity error the servo coasts (forward 0) and merely holds heading —
     *  bang-bang hysteresis so a bot at its desired velocity doesn't chatter the forward key on/off. */
    static final double SERVO_DEADBAND = 0.02;
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
    /*
     * RETIRED 2026-08-20 with groundServo's hazard branch (owner ruling — Phase 2 of the servo normalization,
     * the (340,69,481) creep-wedge conviction): SERVO_CTE_HALT (0.40) and SERVO_ALONG_HALT_FLOOR (0.0), the
     * along-track halt scale that throttled advance by cross-track error inside the retired speed schedule.
     * They had no other reader. The ground hazard corner is now an ARRIVE on the near-face anchor, whose
     * easing is derived from the medium's drag (ARRIVE_GAIN_GROUND) rather than scheduled — see drive().
     */

    // ---- per-call geometry scratch (single bot per tick → one reusable instance) ---------------------

    private static final class Geom {
        double segLen;       // horizontal segment length
        double qx, qz;       // pursuit point (xz): the bot's projection advanced LOOKAHEAD toward the target
        double cte;          // horizontal cross-track distance (bot → nearest point on the segment line)
    }

    private static final Geom G = new Geom();

    /** Per-call travel-frame scratch for the hazard probes (single bot per tick → one reusable instance). */
    private static final class Frame {
        double ux, uz;   // unit travel direction (horizontal, EUCLIDEAN — the rotate/dot frame)
        double wx, wz;   // the same direction CHEBYSHEV-normalised: one cell per k along the dominant axis
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
        double lookahead = LOOKAHEAD / (1.0 + cteGain * cte);
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
        return recenterOn(b, cx0, cz0, COLUMN_DEADBAND);
    }

    /**
     * {@link #recenterOn} with an EXPLICIT tolerance, for callers whose requirement is tighter than
     * {@link #COLUMN_DEADBAND}.
     *
     * <p>{@code COLUMN_DEADBAND} is a general "close enough to the column" figure, and inside it this servo
     * writes {@code setForward(0.0)} — it stops driving and reports success. That makes the deadband a hard
     * FLOOR on how precisely any caller can be positioned: a caller needing better cannot get it by testing
     * more strictly, because no input is being written any more, so a tighter test just spins forever.
     *
     * <p>Parkour's run-up is such a caller. Its requirement is {@code ParkourEnvelope.maxLaunchOffset}
     * (~0.026 on stone), about a sixth of the deadband, because the run-up is quantised in ticks and losing
     * the last one costs 5% of launch speed. Passing the requirement in as the tolerance is what lets the
     * servo keep driving until it is actually met — see the (456,0,512) flagship wedge.
     */
    /**
     * Scratch for {@link #recenterClearOf}'s span read. Tick-thread only — every servo in this class runs
     * inside the entity tick — so a single shared array is safe and keeps the per-tick read allocation-free.
     */
    private static final double[] SPAN = new double[4];

    /**
     * {@link #recenterOnTarget} for a bot that must stand clear of the COLLISION in the cell it is targeting
     * — the sink-in re-centre (owner-ratified 2026-08-27, the ShaftCourse {@code control-plain-topdown}
     * stall).
     *
     * <p>Aims at the centre of {@link BotSteering#clearSpan}'s free span rather than the cell centre, with a
     * tolerance DERIVED from that span rather than the general {@link #COLUMN_DEADBAND}: the servo stops when
     * the whole body is inside the air, and not before. Both numbers come out of the same geometry, so
     * neither is tuned:
     * <ul>
     *   <li><b>anchor</b> = the span's midpoint. For an EAST-facing ladder (a 3/16 plate at
     *       {@code x ∈ [cell, cell+0.1875]}) that is {@code cell + 0.59375}, not {@code cell + 0.5}.</li>
     *   <li><b>tolerance</b> = {@code halfSpan − BODY_RADIUS}, i.e. exactly "the whole box fits in the free
     *       span". For that ladder: {@code 0.40625 − 0.3 = 0.10625}.</li>
     * </ul>
     *
     * <p><b>Why the tolerance cannot simply stay {@link #COLUMN_DEADBAND}.</b> 0.15 is WIDER than the 0.10625
     * the geometry allows, so a bot approaching the plate from its own side would stop with the box still
     * over the shelf — the original stall, merely relocated. And it is why the fix is an anchor rather than a
     * tighter deadband: this servo writes {@code setForward(0)} inside its deadband, so tightening alone
     * leaves the bot crawling toward a target that was never clear in the first place.
     *
     * <p><b>Inert wherever there is no partial collision</b>, which is nearly everywhere: an empty or
     * full-footprint shape hands back the whole cell, giving anchor = cell centre and tolerance
     * {@code 0.5 − 0.3 = 0.2}, capped by {@code COLUMN_DEADBAND} to 0.15 — the exact call
     * {@link #recenterOnTarget} makes today. The vine family and scaffolding are therefore byte-identical.
     *
     * @param fx,fy,fz the FLOOR cell whose collision the body must clear — for a sink-in, the step's target
     *                 cell (the climbable being entered), which is the cell the bot is currently standing on
     *                 top of.
     */
    public static boolean recenterClearOf(BotSteering b, int fx, int fy, int fz) {
        b.clearSpan(fx, fy, fz, SPAN);
        final double ax = 0.5 * (SPAN[0] + SPAN[1]);
        final double az = 0.5 * (SPAN[2] + SPAN[3]);
        final double tol = Math.min(COLUMN_DEADBAND,
                Math.min(0.5 * (SPAN[1] - SPAN[0]), 0.5 * (SPAN[3] - SPAN[2])) - BotSteering.BODY_RADIUS);
        return recenterOn(b, ax, az, tol);
    }

    public static boolean recenterOn(BotSteering b, double cx0, double cz0, double deadband) {
        double cx = cx0 - b.x();
        double cz = cz0 - b.z();
        double d = Math.sqrt(cx * cx + cz * cz);
        if (d > deadband) {
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
        // NORMALIZED 2026-08-29 (owner ruling). This was `recenterOn` + tag("hold") — the legacy
        // position-only P-law, and the LAST hold still on it: restHold was moved to anchoredServo on
        // 08-19 and this one was missed. recenterOn faces its POSITION error and presses forward, with
        // no velocity term, no strafe and no signed forward, so it has exactly one lever — rotation.
        // Arriving with cross-axis momentum it therefore chases its own overshoot rather than arresting
        // it. Observed directly on a 2026-08-29 hold: a smooth ~200-degree facing sweep (yaw 174 -> 126)
        // with throttle ramping 0.16 -> 0.48, both exactly as the law predicts, since it faces its
        // position error and fwd == distance-to-anchor by construction. (That hold did NOT by itself wedge
        // the bot — the wedge in that run had a separate, since-reverted cause — but the sweep is genuine
        // recenterOn behaviour and is what this normalization removes.)
        //
        // anchoredServo runs the §2.1 cascade (anchor -> desired velocity -> velocity error) and actuates
        // through §2.2, which is the law this hold actually wants: it keeps a SEMANTIC facing (the head
        // points where the bot is going) and corrects with BOTH key channels — a signed forward, so a
        // brake is a backpedal tap rather than a 180-degree spin, plus strafe for the cross axis. It only
        // yaws onto the correction when the keys genuinely cannot deliver it (|u| > 1). Same anchor as
        // before (the bot's OWN column centre, never the step's target — the (58,133,189) rule), and the
        // same cap/gain restHold proved on the ground, so this is the sibling hold's law applied here.
        final double ax = Math.floor(b.x()) + 0.5, az = Math.floor(b.z()) + 0.5;
        anchoredServo(b, "hold", "hold:dead", ax, az,
                SERVO_CROSS_CAP, SERVO_CROSS_GAIN, ax - b.x(), az - b.z());
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
        if (!b.onClimbable()) {
            return;                                    // nothing to hold with — the envelope's verdict, not ours
        }
        if (b.y() < footY) {
            // BELOW THE BAND ON A CLIMBABLE ⇒ CLIMB BACK INTO IT (owner ruling 2026-08-21: "hold jump if the
            // feet cell is climbable and you're below your expected Y"). This used to fall under the same
            // early-out as "no climbable" — "already below, let it be" — which DEADLOCKED against
            // PhaseRunner's implicit-settle gate: that gate refuses to run the phase drive until
            // inRestingPose(fromFootY) holds, and settleIntoBand is the only thing that can establish it.
            // Releasing here left the bot with NO input at all, so vanilla's -0.15/t climbable slide carried
            // it FURTHER from the band every tick and the pose could never be reached.
            //
            // Measured on the mid-climb tile: a freshly adopted Traverse framed at fromFootY=152 met the bot
            // at y=151.988 — short by 0.012 — and the exec log shows exactly this: `src=recenter:dead
            // jump=false sneak=false fwd=0.00`, then botY 151.988 -> 151.838 -> ... -> 151.088 in exact
            // -0.150 steps (the unsuppressed clamp) until the validity envelope fired. Jump is the correct
            // input and the only one that closes the gap: on a climbable vanilla drives vy = +0.2 while it
            // is held, so the bot re-enters the band within a tick or two and the gate opens.
            b.setJumping(true);
            return;
        }
        if (b.y() <= footY + SETTLE_BAND || b.y() + b.velY() < footY) {
            b.setSneak(true);                          // in the band, or one tick from falling out of it
        }
    }

    public static boolean holdUntilOverTargetColumn(BotSteering b, SteerView p) {
        // §4: unconditional stamp, before any early-out (DESIGN-servo-normalization.md — "a method that
        // writes any steering input writes a tag in the same call"). This is the ONLY drive-path call on
        // Descend's CLEAR hand-off tick, and untagged it left that tick reading the PREVIOUS servo's src
        // in the exec log (the stale-tag trap — the (57,172,255) conviction's counter froze on exactly
        // that tick). The dead variant is honest for every early-out (the drive ran and chose to write
        // nothing); the sneak path re-stamps the active name. Callers that follow this with a real drive
        // (Descend's STEP, Diagonal, Fall's walkoff) overwrite it — last stamp wins, the stationKeep
        // after-recenterOn precedent.
        tag("hold:overcol:dead");
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
        tag("hold:overcol");
        b.setSneak(true);
        return true;
    }

    /**
     * The PLANLESS/HELD CLING (owner-ratified 2026-08-19, the vine-hang wedge). A bot whose feet are inside
     * a climbable slides out at the vanilla −0.15/t clamp on every tick nobody presses an input — and the
     * follower's planless states (WAIT / HOLD / envelope fail→hold / U5 drop / nav-unready / plan-consumed)
     * press only setForward(0), so a replan in flight walks the bot out the bottom of the very column its
     * next plan will be framed from. Sneak IS the arrest: vanilla's isSuppressingSlidingDownLadder zeroes
     * the slide precisely while it is held (BotSteering.sneakHeld). Purely VERTICAL: the climbable
     * zero-horizontal-input ruling (2026-07-31 vine-bounce, DESIGN-servo-normalization.md §2.2) overrides
     * everything on a climbable, so this writes no thrust — the caller's own setForward(0) is the whole
     * horizontal story.
     *
     * @return true when the cling was engaged; false = nothing to hold.
     */
    /**
     * The CLIMBABLE-TOP DIP RECOVERY (owner-ratified 2026-08-21) — the follower half of Traverse's
     * walk-the-top-of-a-climbable node ({@code MovementContext#climbableFloorAt}).
     *
     * <p>A climbable has no collision, so a bot walking its top is not standing on anything: it sinks,
     * and only once its FEET enter the climbable does vanilla grant the grab that jump can act on
     * ({@code onClimbable()} is a feet-block test — {@code climbableBelow()} does NOT enable climbing, which
     * is exactly why "hold jump whenever a climbable is below" would be inert). So the rule is stated on
     * the dip itself: feet in the climbable AND below the step's target height -> hold jump, which lifts at
     * {@code +0.2/t} until the bot clears the cell again. That is the same dip-and-recover a human does by
     * holding space, and it is what the {@code CLIMBABLE_TOP_COST} 1.3× surcharge prices.
     *
     * <p><b>No curtain guard is needed here</b>, and deliberately so rather than by omission: a Traverse is
     * only ever emitted onto a climbable floor whose FEET cell is non-climbable, so by construction this
     * cannot fire inside a curtain (Climb's sneak-speed lateral cling owns those). Scaffolding likewise
     * cannot reach this path — it is STANDABLE, so {@code climbableFloorAt} rejects it and the ordinary
     * flat walk applies.
     *
     * <p>Call it AFTER the phase drive: {@link #drive} writes forward/yaw and must not clobber the jump.
     *
     * @return true when the recovery jump was engaged.
     */
    public static boolean climbableDipRecover(BotSteering b, SteerView p) {
        tagAlso(":dip:dead");                  // §4: unconditional, before any early-out — but APPENDED, not
                                               // clobbering: this runs after drive and writes only jump
        if (!b.onClimbable()) return false;    // no grab yet — jump is inert until the feet are IN it
        if (b.y() >= p.ty()) return false;     // at or above the step's height — never ratchet upward
        tagAlso(":dip");
        b.setJumping(true);
        return true;
    }

    public static boolean clingHold(BotSteering b) {
        tag("hold:cling:dead");                       // §4: unconditional, before any early-out
        if (!b.onClimbable()) return false;           // feet not IN a climbable — nothing arrests here
        // GROUNDED ONLY — a floor SOMEWHERE BELOW is not a hold (owner ruling 2026-08-21). This guard used
        // to bail on standableBelow() as well, reading "something already holds us"; over a vine that merely
        // GROWS above a floor that is false — the bot is a block up, resting on nothing, and refusing the
        // cling let it ride all the way down. Convicted on the flagship at (56,170,257): a SEAM-PAUSE held
        // 10 ticks at the top-out (vine 170, floor 169, feet 171); clingHold refused on the airborne half
        // (feet not yet IN the vine) and then refused AGAIN on the dip half because the stone at 169 read
        // standable — so nothing arrested across the whole pause, the bot sank the full 1.114 blocks to
        // botY=170.000, and the adopted plan's step 0 (framed at feet 171) failed its envelope on tick one.
        //
        // Clinging 0.1 of a block above a floor costs nothing: the bot simply holds the vine until it has a
        // plan, then moves under that plan. The 2026-08-02 standableBelow ruling this drops is untouched
        // where it belongs — it governs MOVING LATERALLY on a climbable (walk off, don't cling), and the
        // stance servo still reads it. This is a station-keeping HOLD, which is the opposite case.
        if (b.grounded()) return false;               // a real floor holds us; sneak would only arm
                                                      // vanilla's maybeBackOffFromEdge
        if (b.scaffoldingBelow()) return false;       // scaffolding is sneak-EXEMPT: sneak DESCENDS through it
        tag("hold:cling");
        b.setSneak(true);
        return true;
    }

    // ---- the unified servo core (DESIGN-servo-normalization.md §2, ratified 2026-08-19) --------------

    /**
     * Face-the-error escape-hatch threshold (b/t of velocity error) for the unified core's SEMANTIC yaw
     * (DESIGN-servo-normalization.md §2.2): below it the head keeps facing the step's target and the hands
     * (signed forward + strafe) express the correction; at/above it the servo yaws onto the error vector.
     * DERIVED, not tuned (§7 Q1, owner 2026-08-19): {@code 1/}{@link #SERVO_GAIN} is the saturation point —
     * the error at which the proportional key first maxes out — so the yaw escape engages exactly where a
     * correction needs sprint-class thrust, which vanilla only grants FORWARD (sprint requires forward
     * input); below it the strafe/backpedal channels can deliver the whole correction without spinning the
     * head. No new literal by ruling; promote to a hysteresis pair only on observed chatter AT the threshold.
     */
    static final double FACE_ERR_THRESHOLD = 1.0 / SERVO_GAIN;

    /**
     * Vanilla's per-tick horizontal velocity-retention multiplier applied ALONGSIDE the block's friction:
     * {@code LivingEntity.travel} forms its drag as {@code blockFriction x 0.91}. Named here because
     * {@link #groundArriveGain} derives the ARRIVE gain from it per tick; {@link #AIR_COAST} and
     * {@link #GROUND_COAST} still spell their own copies inline (they are pinned single-surface constants
     * and left byte-identical on purpose).
     */
    static final double VANILLA_HORIZONTAL_DRAG = 0.91;

    /**
     * The STONE ARRIVE gain, {@code 1/}{@link #GROUND_COAST} = 0.831 — kept as a PINNED constant for
     * {@link #parkourRunupAlign} ALONE after the 2026-08-25 ruling made the arrive gain per-tick and
     * friction-derived ({@link #groundArriveGain}).
     *
     * <p>Why the launch composition does NOT take the derived gain. {@code parkourRunupAlign}'s along axis
     * is not braking to a stop — it is a full-cruise advance toward a landing centre that always sits at
     * least a whole jump (~1.4 blocks) ahead, and its documented invariant is that the cap SATURATES on
     * every live tick, so the gain is inert by construction (crossover {@code 0.35/0.831 = 0.42} blocks).
     * The derived gain would break exactly that: on ice it is 0.121, moving the crossover out to 2.89
     * blocks — which puts a 1.4-block landing INSIDE the easing region and roughly HALVES the commanded
     * launch speed. That is the takeoff-velocity failure the 2026-08-25 parkour launch-speed arc closed;
     * an ice launch is a ballistic problem the envelope already prices, not an arrival to be eased.
     */
    static final double ARRIVE_GAIN_GROUND = 1.0 / GROUND_COAST;

    /**
     * The <b>unified servo core</b> — one position→velocity→thrust cascade, per tick, stateless,
     * allocation-free (DESIGN-servo-normalization.md §2.1, owner-ratified 2026-08-19). The law:
     *
     * <pre>
     *   desired_vel = unit(anchor − pos) · min(cap, gainP · |anchor − pos|)
     *   err         = desired_vel − vel              (horizontal, blocks/tick)
     *   thrust      = min(1, SERVO_GAIN · |err|)     (|err| &lt; SERVO_DEADBAND → coast)
     * </pre>
     *
     * The modes differ ONLY by the anchor and the {@code cap}/{@code gainP} pair the caller passes —
     * <b>HOLD</b> is a small ACHIEVABLE cap ({@link #SERVO_CROSS_CAP}/{@link #SERVO_CROSS_GAIN} on ground:
     * {@link #stepOffGate}'s proven pull), <b>ARRIVE</b> an easing pull at the physics-derived
     * {@link #ARRIVE_GAIN_GROUND}, <b>PASS-THROUGH</b> (no caller yet — Phase 2+) a moving pursuit anchor
     * at the unreachable cruise ceiling, where the cap saturates and {@code gainP} never engages. The
     * zero-velocity setpoint is DERIVED from the position error — {@link #uprightSwimServo}'s proven
     * degenerate branch promoted to the norm — so at the anchor the servo actively brakes external push,
     * and past it {@code unit(anchor − pos)} reverses and overshoot is answered with position-anchored
     * reverse thrust. A position-anchored law cannot walk away from its anchor the way the retired
     * velocity-only laws could (§1 class 1: a zero-velocity SETPOINT with no position term fights the
     * velocity it sees this tick and is indifferent to where the bot has already been pushed — the
     * (419,66,596) rear-lip walk-out).
     *
     * <h2>Actuation (§2.2 — {@link #arriveOnTarget}'s owner-ratified frame generalized to the norm)</h2>
     * Yaw is SEMANTIC: face {@code (faceX,faceZ)} — the step's target / travel direction — never the error
     * vector for a small correction (the 2026-08-06 no-pirouettes rulings: "heading is held, braking is
     * REVERSE input"; "cross-axis error is corrected by STRAFE, not by yawing"). The error is expressed in
     * the facing frame: {@code setForward = err·heading}, SIGNED — backpedal is legal, the moon-walk brake —
     * and {@code setStrafe} the cross component, saturated as a VECTOR (the pair scaled so its magnitude is
     * the thrust), never per-component. One escape hatch: at {@code |err| ≥ }{@link #FACE_ERR_THRESHOLD}
     * the proportional key is saturated — the correction needs sprint-class thrust, which exists only
     * forward — so the servo yaws onto the error, where the decomposition reduces exactly to the legacy
     * face-the-error actuation ({@code along = |err|}, {@code cross = 0}, full key). The coexistence
     * carve-outs that ride on specific FAMILIES (the climbable zero-horizontal-input ruling, the prone
     * {@link #SERVO_FORWARD_MIN} floor, swim pitch ownership) attach at their families' migration phases —
     * the two Phase-1 callers are grounded-on-land by their own gates.
     *
     * <h2>Tag (§4)</h2>
     * Stamped on EVERY tick this core runs, before any input write or early-out — an untagged servo's ticks
     * read the PREVIOUS servo's {@code src} in the exec log (the stale-tag trap, inventory bug-class 4), and
     * the core kills that structurally rather than by auditing callers. {@code tagDead} is the caller's
     * {@code :dead} quiescent variant; both are caller-supplied literals so the stamp allocates nothing
     * beyond {@link #tag}'s own existing formatting.
     */
    static void anchoredServo(BotSteering b, String tagName, String tagDead,
                              double ax, double az, double cap, double gainP,
                              double faceX, double faceZ) {
        // The cascade: anchor → desired velocity (capped proportional pull) → velocity error. Pure math up
        // to the tag stamp — no input write and no return precedes it.
        double dx = ax - b.x(), dz = az - b.z();
        double dist = Math.sqrt(dx * dx + dz * dz);
        double dvx = 0.0, dvz = 0.0;
        if (dist >= EPS) {
            double sp = Math.min(cap, gainP * dist);
            dvx = (dx / dist) * sp;
            dvz = (dz / dist) * sp;
        }
        actuate(b, tagName, tagDead, dvx - b.velX(), dvz - b.velZ(), faceX, faceZ);
    }

    /**
     * The unified core's <b>err→thrust→actuation tail</b> (§2.2) — factored out of {@link #anchoredServo}
     * so the {@link #parkourRunupAlign} composition (which forms its desired velocity PER AXIS rather than
     * from a single anchor) can share it verbatim: one copy of the actuation math, ever. Takes the caller's
     * already-formed velocity ERROR ({@code desired − actual}, horizontal, blocks/tick) and expresses it
     * as inputs — the §4 tag stamp (unconditional, before any input write or early-out), SEMANTIC yaw at
     * {@code (faceX,faceZ)} with the yaw-onto-the-error escape at {@link #FACE_ERR_THRESHOLD}, the signed
     * forward + strafe decomposition in the facing frame, and vector saturation. See {@link #anchoredServo}
     * for the rationale of each piece; behavior through the anchored path is byte-identical to the
     * pre-factoring inline tail.
     */
    /** The tick's ground drag {@code q = friction·0.91}, clamped — see {@link #actuate}'s derivation. */
    private static double dragQ(BotSteering b) {
        double fr = b.slipperinessAt(b.footX(), b.footY() - 1, b.footZ());
        fr = Math.max(EPS, Math.min(fr, 1.0 - EPS));
        return Math.max(EPS, Math.min(fr * VANILLA_HORIZONTAL_DRAG, 1.0 - EPS));
    }

    /** The tick's input scale {@code k = 1/(A·q)} — the other half of {@link #actuate}'s exact solve.
     *  Split out (with {@link #dragQ}) so the §2.2 physics has ONE copy that both the actuation and the
     *  {@link #holdWithinKeyBudget} authority test read. */
    private static double inputK(BotSteering b, double q) {
        double fr = b.slipperinessAt(b.footX(), b.footY() - 1, b.footZ());
        fr = Math.max(EPS, Math.min(fr, 1.0 - EPS));
        double accel = GROUND_INPUT_SPEED * (VANILLA_ACCEL_NUMERATOR / (fr * fr * fr)) * VANILLA_INPUT_SCALE;
        return 1.0 / (accel * q);
    }

    /**
     * <b>Can the station-keep hold reach its desired velocity within the key budget this tick?</b> — i.e.
     * is the exact input {@code |u| ≤ 1}, so forward/strafe (including a backpedal) can deliver the whole
     * correction without saturating?
     *
     * <p>This is the gate the {@code PhaseRunner} holds on before aiming at and mining a block (owner
     * ruling 2026-08-29). Mining while still carrying momentum the servo cannot cancel is unstable in both
     * directions: the break's own reach and progress are evaluated from a moving body, and the aim
     * competes with the drive for the yaw. Waiting for authority makes "stop, then turn, then break" true
     * by construction rather than by hope.
     *
     * <p>Deliberately {@code |u| ≤ 1} — the SAME quantity {@link #actuate} uses to decide whether it must
     * yaw onto the correction — and not a hand-set speed threshold. It is the exact statement of "the keys
     * can do this", it scales itself to the block underfoot (ice has a quarter of stone's authority), and
     * it cannot drift out of agreement with the servo because it is the servo's own number.
     */
    public static boolean holdWithinKeyBudget(BotSteering b) {
        final double ax = Math.floor(b.x()) + 0.5, az = Math.floor(b.z()) + 0.5;
        final double dx = ax - b.x(), dz = az - b.z();
        final double dist = Math.sqrt(dx * dx + dz * dz);
        double dvx = 0.0, dvz = 0.0;
        if (dist >= EPS) {
            double sp = Math.min(SERVO_CROSS_CAP, SERVO_CROSS_GAIN * dist);
            dvx = (dx / dist) * sp;
            dvz = (dz / dist) * sp;
        }
        final double q = dragQ(b);
        final double k = inputK(b, q);
        final double ux = (dvx - b.velX() + b.velX() * (1.0 - q)) * k;
        final double uz = (dvz - b.velZ() + b.velZ() * (1.0 - q)) * k;
        return Math.sqrt(ux * ux + uz * uz) <= 1.0;
    }

    private static void actuate(BotSteering b, String tagName, String tagDead,
                                double errx, double errz, double faceX, double faceZ) {
        double emag = Math.sqrt(errx * errx + errz * errz);

        boolean dead = emag < SERVO_DEADBAND;
        tag(dead ? tagDead : tagName);                 // §4: unconditional, before any early-out

        // ---- THE INPUT IS SOLVED, NOT APPROXIMATED (owner ruling 2026-08-26) ----------------------------
        //
        // This used to be `scale = min(1, SERVO_GAIN * emag) / emag` — a fixed linear gain applied to a
        // velocity error. It was wrong in FRAME, and the error was systematic rather than incidental.
        //
        // Vanilla's ground tick is  v(t+1) = (v(t) + A·u) · q,  with friction applied at the END. So
        // velX()/velZ() — and therefore `err` — are POST-drag quantities, while the input `u` acts PRE-drag.
        // Solving that recurrence for the input that lands exactly on the desired velocity `d`:
        //
        //     u = (d − v·q) / (A·q)          and since err = d − v,   u = (err + v·(1−q)) / (A·q)
        //
        // No linear gain can express it, because of the `v·(1−q)` term. SERVO_GAIN = 18 was roughly
        // 1/(A·q) = 18.7, which is what you get if you assume the error is already pre-drag — so the servo
        // over-commanded by the whole `v·(1−q)` term, and `min(1, …)` quietly clipped the excess to full
        // throttle. Measured on the convicted (278,113,352) state: exact input 0.844, commanded 1.095,
        // clipped to 1.000, which reversed the velocity almost symmetrically into a permanent 2-cycle.
        //
        // BOTH CONSTANTS COME FROM THE BLOCK UNDER THE FEET, so ice, slime and soul sand are handled by the
        // same expression rather than by cases: q = friction·0.91 is the drag, and vanilla's ground input
        // accel is speed·(0.216/friction³)·0.98 — which is why ice is hard to steer (friction 0.98 gives
        // A = 0.030 against stone's 0.130, a quarter of the authority).
        //
        // SPRINT is assumed. BotSteering has no sprint getter, and the direction of the error matters: using
        // the SPRINT accel when the bot is walking UNDER-commands by 23% (achieved = 0.77·required), which
        // converges geometrically over a couple of ticks. Using the walk value while sprinting would
        // OVER-command — the exact failure this change exists to remove. Under-command is self-correcting;
        // over-command oscillates. If a sprint seam is ever added, this becomes exact.
        double q = dragQ(b);
        double k = inputK(b, q);
        double ux = (errx + b.velX() * (1.0 - q)) * k;   // the input vector, in key units
        double uz = (errz + b.velZ() * (1.0 - q)) * k;
        double umag = Math.sqrt(ux * ux + uz * uz);

        // §2.2 SEMANTIC yaw: face the step's target; yaw onto the correction only when the keys cannot
        // deliver it. That condition is now EXACT — |u| > 1 means "more than full input is required" —
        // where it used to be the hand-set FACE_ERR_THRESHOLD standing in for the same idea.
        double hx, hz;
        if (!dead && umag > 1.0) {
            hx = ux / umag; hz = uz / umag;            // saturated: put every key along the need
        } else {
            double fl = Math.sqrt(faceX * faceX + faceZ * faceZ);
            if (fl >= EPS) {
                hx = faceX / fl; hz = faceZ / fl;      // semantic: the head points where the bot is GOING
            } else if (umag >= EPS) {
                hx = ux / umag; hz = uz / umag;        // no semantic heading given — the need is all there is
            } else {
                b.setForward(0.0f);                    // nothing to face, nothing to correct — explicit
                b.setStrafe(0.0f);                     // zeros, never a stale key (the zza invariant)
                return;
            }
        }
        b.faceHorizontally(hx, hz);
        if (dead) {
            b.setForward(0.0f);                        // quiescent: hold the heading, coast — explicit zeros
            b.setStrafe(0.0f);
            return;
        }
        // Decompose the INPUT in the facing frame and drive BOTH channels (BotSteering's sign convention:
        // positive strafe = the mover's LEFT, (hz,−hx) for unit heading), saturated as a VECTOR. Inside
        // saturation the pair is now the exact solution, so a correction that fits within the keys lands on
        // its target in ONE tick instead of asymptotically.
        double along = ux * hx + uz * hz;              // signed: negative = backpedal (the moon-walk brake)
        double cross = ux * hz - uz * hx;              // signed: positive = the need points LEFT of the facing
        double clamp = umag > 1.0 ? 1.0 / umag : 1.0;
        b.setForward((float) (along * clamp));
        b.setStrafe((float) (cross * clamp));
    }

    /**
     * The ARRIVAL-SETTLE hold (owner-ratified 2026-08-19, DESIGN-servo-normalization.md §2.6): on plan
     * completion the bot drives to rest on the CENTRE of its final plan cell — "if you tell the bot to
     * stand in a specific location, you want it to stand EXACTLY there" (e.g. keeping a mob spawner
     * active from exact afk coordinates). The {@link #anchoredServo unified core} in HOLD mode at the
     * proven ground pull ({@link #SERVO_CROSS_CAP}/{@link #SERVO_CROSS_GAIN}), anchored at the completed
     * plan's final waypoint centre {@code (ax,az)}. Facing is SEMANTIC per §2.2 — the anchor direction
     * while off-centre; once the bot rests ON the anchor both the facing and the error vanish and the
     * dead branch's final else writes explicit zero inputs without touching yaw, so the last approach
     * heading is simply held. The deadband quiescence IS the rest state: an external push re-raises the
     * error and the servo actively re-centres — an anchored station-keep, never a dead-band no-op.
     * Callers gate on the GROUNDED land medium (water/climbable arrivals keep their pre-settle behavior
     * — this round's ratified scope) and on holding a captured anchor at all: a planless arrival or a
     * command-{@code /bot stay} hold never reaches this method.
     */
    public static void restHold(BotSteering b, double ax, double az) {
        anchoredServo(b, "hold:rest", "hold:rest:dead", ax, az,
                SERVO_CROSS_CAP, SERVO_CROSS_GAIN, ax - b.x(), az - b.z());
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
     * spare. While that fails, this method WRITES the hold inputs for the tick — {@link #anchoredServo
     * the unified core} in HOLD mode, anchored at the TAKEOFF cell centre with the proven
     * {@link #SERVO_CROSS_CAP}/{@link #SERVO_CROSS_GAIN} pull (DESIGN-servo-normalization.md §3) — and
     * returns {@code true}: bleed the carry and re-centre on the takeoff stand FIRST, commit after. The
     * RETIRED response (desired along-speed identically ZERO with no position term, crossErr measured to
     * the TARGET centreline) was the audit's class-1/class-3 conviction: forward carry became along-error
     * {@code −vAlong} → a full-key reverse (gain 18 saturates at 0.055 b/t against ~0.1 b/t imparted per
     * ground tick), and standing near the rear lip of the from-cell ONE reverse tick walked the bot OUT of
     * it — the gate self-disengaged (foot left carryFrom) and the validity envelope fired (the
     * (419,66,596) rear-lip walk-out). Anchored at the takeoff centre that is geometrically impossible:
     * the desired velocity always points back INTO the from-cell with magnitude ≤ 0.13. On ice the horizon
     * is honestly long, so the bot all but stops before stepping off — the physically right caution, at
     * worst a visible pause on the lip (never a slide off it). Conservative by construction: the hold
     * beats pure friction, and post-commit the normal drive's cross-gain only shrinks the carry further,
     * so the prediction is an upper bound. Callers gate on {@code b.grounded()} and on still standing on
     * the FROM column — once the step-off is under way (foot moved / airborne) the gate must not
     * re-engage; that same caller gate is what makes the bot's own foot cell the takeoff cell below.
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
        // RESPONSE (re-anchored 2026-08-19, DESIGN-servo-normalization.md §3): HOLD at the TAKEOFF cell
        // centre — the class-1 (no along position term) and class-3 (crossErr to the TARGET centreline
        // while gating on the FROM cell) fixes in one anchor; see the class doc for the (419,66,596)
        // conviction. The caller (MovePlan.Phase.carryUncontained) admits this tick only while
        // foot == carryFrom, so the bot's own foot cell IS the takeoff cell by construction. Yaw is
        // semantic — face the step's travel direction (§2.2) — and the desired velocity is a bounded pull
        // (≤ SERVO_CROSS_CAP) that always points back INTO the from-cell.
        anchoredServo(b, "hold:takeoff", "hold:takeoff:dead",
                b.footX() + 0.5, b.footZ() + 0.5,
                SERVO_CROSS_CAP, SERVO_CROSS_GAIN, ux, uz);
        return true;
    }

    /**
     * Whether the CURRENT step's {@link #stepOffGate} is ARMED for the tick being driven — set by
     * {@code PhaseRunner} around the phase drive ({@link MovePlan.Phase#carryGateArmed}), consumed by
     * {@link #drive}'s land branch, and cleared by the runner the moment the drive returns, so no other
     * caller of {@link #drive} can ever read it stale. Plain static per-tick scratch, the {@link #G}
     * pattern: one bot per tick on the server thread, zero allocation, no cross-tick state.
     *
     * <p><b>Why the drive must know</b> (owner-ratified 2026-08-19, DESIGN-servo-normalization.md §2.5):
     * the gate polices the CURRENT step's target centreline while the normal ground drive steers for
     * {@code groundServo} (deleted 2026-08-24)'s corner racing line — two controllers, two lane definitions. At (259,78,448)
     * (a +z Traverse chaining into a −x turn) that dispute was a bit-identical TWO-TICK limit cycle for
     * 46k ticks: the safe-corner blend pushed the bot to the ±0.2 lane boundary ({@code servo:thrust}),
     * the gate's containment predicate read the resulting crossErr + carry as uncontained and its HOLD
     * restored exactly the pre-drive state ({@code hold:takeoff}), forever — both saturated, no geometry
     * involved. While the gate is armed the drive therefore anchors on the SAME lane the gate polices —
     * {@link #arriveOnStep} — the one-gate principle (refusal, centring and drive share one lane
     * definition) extended to the drive.
     */
    static boolean stepGateArmed;

    /**
     * §11 CENTERED-TERMINAL drive flag (DESIGN-replan-handoff.md §11, owner ruling 2026-08-20): the
     * follower is executing the TERMINAL move of a seam-truncated plan — "the plan will now END when
     * the current movement ends" — so the generic land drive must ARRIVE on the step's target centre
     * ({@link #arriveOnStep}: the {@link #SERVO_GROUND_CRUISE}-capped easing, hazard near-face branch
     * and all) instead of cruising the pursuit line, ending the move AT the cell centre at low speed —
     * the settled, centered stand the seam consummation installs the new plan from. Set and cleared by
     * the follower tightly AROUND the step execution (the {@link #stepGateArmed} discipline, one flag
     * write each side — no other SteerControl caller can read it stale); {@link #stepGateArmed} keeps
     * precedence (its lane law is phase-armed and stricter). Moves that write their own servos
     * (steerTowards-direct committed arcs, the swim family's medium branches) never consult it.
     *
     * <p>Also armed by the follower's DELIVERY-TAIL convergence (the delivery invariant's second half,
     * owner-ratified 2026-08-20): when a step's own completion is satisfied but {@code reached}
     * withholds SOLELY on {@link com.orebit.mod.pathfinding.blockpathfinder.Movement#deliverable}, the
     * same ARRIVE brakes the tail onto the cell centre so the one-tick projection re-enters the cell
     * and the handoff completes — pursuit alone never eases at its target and would carry the bot
     * through. Same flag, same set/clear discipline, same precedence.
     */
    public static boolean terminalArrive;

    /**
     * The ONE grounded drive law: the {@link #anchoredServo unified core} in <b>ARRIVE</b> at the current
     * step's target CENTRE, {@link #SERVO_GROUND_CRUISE} capped over the per-tick friction-derived
     * {@link #groundArriveGain}, with semantic yaw down the step's travel direction. Every grounded tick
     * routes here — gate-armed, terminal, or ordinary ({@link #drive}) — so no two branches can hold
     * different opinions about the same tick.
     *
     * <p><b>Gate-armed</b> (owner-ratified 2026-08-19): while {@link #stepGateArmed} the anchor replaces the
     * pursuit point, so the drive and the gate police ONE lane. The gate predicate is CROSS-axis only
     * ({@code crossErr + vCross/(1−f)}), and with the drive pulling toward the same centreline the gate's
     * HOLD centres on, the cross error converges and stays converged while the along-axis ARRIVE advances
     * the step until the foot leaves the from column. The look-ahead corner-cut this forgoes on gate-armed
     * ticks is an accepted efficiency cost (owner, 2026-08-19).
     *
     * <p><b>The HAZARD near-face anchor is GONE</b> (owner ruling 2026-08-25). This method used to take a
     * {@code hazard} bit and, while it fired, pull the anchor back by {@code STEP_ARRIVE_OFFSET} (0.19
     * blocks) from the target centre. That bit was the sole output of ~90 lines of cell-contents probing,
     * and it was answering the wrong question with the wrong shape of answer — see the tombstone further
     * down this file for the full conviction. Two things it got wrong are worth restating here, because
     * they are what this method now gets right:
     *
     * <ul>
     *   <li>The corrections it made were sized by a BODY-GEOMETRY constant ({@code 0.5 − BODY_RADIUS}),
     *       not by any braking quantity. Stopping distance is {@code coast × v} — 0.42 blocks on stone at
     *       cruise, 2.88 on ice — so one fixed 0.19 was simultaneously unnecessary on stone and ~15× too
     *       small on ice. {@link #groundArriveGain} makes the ease continuous in the surface instead.</li>
     *   <li>Its 0.19 anchor was un-landable on a step DOWN onto a partial-height floor. At 0.31 into the
     *       destination cell the 0.6-wide body clears the source block by 0.01 — an order of magnitude
     *       under the servo's own hunting amplitude — so every other tick pushed the body back into the
     *       source column, and vanilla resolves Y BEFORE X and then hands a grounded entity a step-assist
     *       re-mount on a horizontal block ({@code Entity.collide}), which discards the descent entirely.
     *       The bot held y to the exact millimetre for 600 ticks. Anchoring on the target centre leaves
     *       the whole body 0.2 clear of the source face, so the descent simply happens.</li>
     * </ul>
     */
    /**
     * The grounded ARRIVE, exposed for movements whose own phase drive is an AIRBORNE servo and which
     * therefore need somewhere to hand over at touchdown (owner ruling 2026-08-25).
     *
     * <p>The case is {@code Parkour}'s {@code land} phase. {@link #parkourAirborne} keeps driving after the
     * feet are down, and there it is not the position controller it is in the air: its position term is the
     * TOUCHDOWN PREDICTOR, and once the bot is standing on the landing with {@code v == 0} the predictor is
     * being asked "where will I land if I coast?" while already there. It returns the current position, both
     * surviving branches collapse to {@code desiredAlong in {0, v}} — the same number when {@code v == 0} —
     * and the servo is satisfied wherever the bot happens to have stopped.
     *
     * <p>Measured on IceParkourCourse {@code ice.chain.g3}: touchdown x=50.586 (0.086 from the cell centre,
     * a good landing), braked slide to 50.886, servo pulls back to 50.737 — and then holds there for 434
     * ticks with {@code fwd=0.00}. It had reversed to correct the overshoot and then braked its own
     * correction to a halt, because a zero-VELOCITY setpoint is achieved at any position. That is exactly
     * the class-1 defect {@link #anchoredServo} was written to end; {@code parkourAirborne} survived the
     * conversion because it is classified as an AIR servo, and nobody asked what it does after touchdown.
     *
     * <p>So the fix is the conversion it missed rather than a new mechanism: airborne ticks keep the
     * ballistic servo (velocity is genuinely the only lever mid-arc), grounded ticks get the same
     * position-anchored ARRIVE that owns every other grounded tick in the codebase.
     */
    public static void arriveGrounded(BotSteering b, SteerView p) {
        arriveOnStep(b, p);
    }

    private static void arriveOnStep(BotSteering b, SteerView p) {
        anchoredServo(b, "arrive:step", "arrive:step:dead",
                p.tx(), p.tz(),
                SERVO_GROUND_CRUISE, groundArriveGain(b, p),
                p.tx() - p.sx(), p.tz() - p.sz());
    }

    /**
     * The ARRIVE-mode GROUND position gain for THIS tick - {@code (1-q)/q} for the drag {@code q} of the
     * surface the bot will actually brake against. Replaces the former {@code ARRIVE_GAIN_GROUND} constant
     * and, with it, the entire deleted ground-hazard family (see the tombstone further down this file).
     *
     * <p><b>Why a function and not a constant.</b> {@link #anchoredServo}'s cascade already IS the correct
     * braking law: with {@code gainP = 1/coast}, {@code desired_vel(d)} is exactly the speed from which a
     * pure-drag coast stops ON the anchor, so the servo eases in over precisely the distance the physics
     * needs. That was documented from the start ("future media derive their gain the same way, never from
     * tuning sessions") - but the constant hard-coded STONE: {@code GROUND_COAST = 0.546/(1-0.546)} with
     * {@code 0.546 = 0.6 x 0.91}. Vanilla drag is {@code q = blockFriction x 0.91}
     * ({@code LivingEntity.travel}), so the law was right and the number was right for exactly one surface:
     *
     * <pre>
     *   stone/dirt  f=0.600  q=0.546  coast=1.20 b   gainP=0.831   &lt;- what every arrive used
     *   slime       f=0.800  q=0.728  coast=2.68 b   gainP=0.374
     *   ice/packed  f=0.980  q=0.892  coast=8.24 b   gainP=0.121
     *   blue ice    f=0.989  q=0.900  coast=9.00 b   gainP=0.111
     * </pre>
     *
     * On ice the servo believed it could stop in 1.20 blocks when it needed 8.24 - it commanded a desired
     * speed 6.9x too high at every distance. THAT is the ice-corner overshoot the hazard family was bolted
     * on to paper over, and 0.19 blocks of anchor offset against a 2.88-block stopping distance at cruise
     * never had a chance of covering it. Derive the gain and the patch has nothing left to do.
     *
     * <p><b>The SLIPPERIER of the two surfaces</b> (owner ruling 2026-08-25). Vanilla drags on the block
     * below the feet, so the CURRENT surface governs this tick's real physics - but a bot walking stone
     * toward an ice cell must begin easing BEFORE it steps on, not one tick after, and stepping onto ice is
     * precisely the case that hurts. So the gain takes whichever of {current floor, destination floor} has
     * the higher {@code getFriction()}. NOTE the inversion in vanilla's naming: {@code getFriction()} is a
     * velocity-RETENTION factor, so HIGHER means MORE slippery (stone 0.6, ice 0.98) and the conservative
     * pick is {@code max}, not {@code min}. Being early is free - an over-long ease on grippy ground just
     * arrives gently - while being late is a walk-off.
     *
     * <p>Both reads use the feet-minus-one cell, matching
     * {@code Entity.getBlockPosBelowThatAffectsMyMovement} (which resolves to {@code floor(y - 0.5)}) for a
     * bot standing on a full block - and every slippery block in the game IS a full block. Deliberately NOT
     * a floor-geometry derivation: this reads a VALUE with a sane default, never a floor's EXISTENCE, so a
     * partial-height or absent floor yields the ordinary 0.6 rather than a bogus verdict. That is the
     * specific failure mode being retired.
     *
     * <p>{@code q} is clamped to the open interval {@code (0,1)} - a well-formedness guard, not a tuning
     * knob: drag must lose energy for a coast to converge at all. Vanilla friction never approaches either
     * bound (max is blue ice's 0.989 -&gt; q = 0.900); the clamp exists so a modded block cannot produce a
     * negative or infinite gain.
     */
    static double groundArriveGain(BotSteering b, SteerView p) {
        double fHere = b.slipperinessAt(b.footX(), b.footY() - 1, b.footZ());
        double fThere = b.slipperinessAt((int) Math.floor(p.tx()),
                                         (int) Math.floor(p.ty()) - 1,
                                         (int) Math.floor(p.tz()));
        double q = Math.max(fHere, fThere) * VANILLA_HORIZONTAL_DRAG;
        q = Math.max(EPS, Math.min(q, 1.0 - EPS));
        return (1.0 - q) / q;
    }

    // =================================================================================================
    // THE SWIM DRIVE (rewritten 2026-08-26, owner ruling) — the fluid half of the servo normalization the
    // ground family completed on 2026-08-24/25. Seven hand-tuned drives (swimTowards, swimPitched,
    // swimPitchedCentered, swimPitchedBraked, swimPitchedDirectional, swimServo, uprightSwimServo) plus a
    // five-probe hazard family collapse here into ONE position-anchored law with two actuators.
    //
    // WHY ALL SEVEN FAILED THE SAME WAY. Every one wrote setForward and nothing else, and on a segment every
    // one reduced to "hold full forward, face something" — because SERVO_CRUISE was 0.35 b/t against a
    // sprint-swim TERMINAL of 0.18, so the velocity error never left saturation. The two that were nominally
    // velocity servos faced the velocity ERROR, which is the correct actuation for water; but with an
    // unreachable set-point the error is dominated by its dir*cruise term, so the facing collapsed back onto
    // the raw pursuit direction and the velocity feedback had no authority. AN UNREACHABLE CAP DOES NOT
    // MERELY SATURATE THE THROTTLE — IT ROTATES THE FACING AWAY FROM THE CORRECTION, and in water the facing
    // IS the entire control signal. Measured on the flagship's wp16 (a pure +X segment, bot 0.349 short in Z
    // carrying +0.161 b/t of Z): desired = dir*0.35 = (0.284, +0.204) against vel (0.032, +0.161) gives
    // err (+0.252, +0.044) — the servo read an already-committed overshoot as UNDER-speed and asked for more
    // +Z. At the achievable 0.18 the same tick gives err (+0.114, -0.056): it brakes.
    //
    // WHY THE 21 GREEN SWIM CARDS NEVER CAUGHT IT. The maze family (swimmaze / mazeportal / mazelava) walls
    // its lanes with bubble columns, END_PORTAL blocks and a lava blanket — all swimHazardAt — so every corner
    // there took the HAZARD RAMP branch, min(0.35, max(0.11, 0.16*dist)), which falls below the 0.18 terminal
    // inside the last ~1.1 blocks. IN THE MAZE THE CAP WAS REACHABLE EXACTLY WHERE IT MATTERED and the servo
    // genuinely braked; open water never armed the ramp. The servo had only ever been validated in the one
    // regime where its set-point was achievable. The flagship at (359,37,426) was the first open water it had
    // ever driven: it handed EndSprintSwim 0.0516 b/t of un-nulled cross velocity which, on water's 4.0-block
    // coast, carried the bot 0.206 blocks — a whole cell past the waypoint — into a deadlock.
    //
    // WHAT REPLACES IT: anchoredServo's cascade with water's own drag, over the existing line geometry.
    // DIRECTION still comes from the cte-shrunken pursuit point (the cross-track return — deleting it was a
    // false start, see SWIM_CTE_GAIN and `mazeportal`); SPEED becomes min(cap, gain*dist) with cap the
    // ACHIEVABLE terminal and gain = 1/coast, so the ease begins exactly where a full-speed coast would just
    // reach the anchor. No unreachable constant is needed, and the corner brake generalizes to EVERY waypoint,
    // hazard or not — which is what makes the overshootHazard/flankHazard/hazardColumn/racing-line apparatus
    // deletable rather than ported. The line drawn is CONTENTS vs GEOMETRY: probes that ask what is in a
    // neighbouring cell are gone; the bot's relationship to its own planned line stays.
    //
    // WHAT DELIBERATELY DOES *NOT* CARRY OVER FROM THE GROUND CORE (each vanilla-verified — see the constants):
    //   * NO STRAFE. actuate() decomposes the error into forward + strafe because on land the facing is
    //     semantically load-bearing (the no-pirouettes ruling). In water the facing is ALSO the vertical
    //     control, and "look where you are going and hold W" is how the medium is actually flown (owner
    //     ruling 2026-08-26). Strafe DOES work in water — getInputVector rotates the full input vector by
    //     YAW only, with no pose gate anywhere in Player.travel or LivingEntity.travel* — but horizontal
    //     thrust is a disc of radius WATER_ACCEL that facing + forward already covers completely. Strafe only
    //     buys a direction without moving the head, which is exactly the thing water does not want.
    //   * PITCH IS AN ACTUATOR, and ONLY while PRONE. Player.travel steers vel.y toward lookAngle.y at
    //     rate e, gated on isSwimming() — the prone pose. Upright swimming never enters that block, so pitch
    //     is vertically INERT there and holdDepth's jump/sink is the only lever. Prone gets BOTH, off one
    //     shared set-point (see swimArrive's vertical section for why the jump is load-bearing there and not
    //     a rival controller — SwimCourse `rise` is the proof).
    //   * PITCH IS A VELOCITY COMMAND, not an acceleration one — it sets the vertical set-point directly, so
    //     it inverts in closed form (proneLookYFor) with no gain, no integrator and nothing to tune.
    // =================================================================================================

    /** Vanilla horizontal velocity RETENTION per tick in water while SPRINTING — {@code LivingEntity
     *  .travelInWater}: {@code float f = this.isSprinting() ? 0.9F : this.getWaterSlowDown();}.
     *
     *  <p>Note this is numerically IDENTICAL to blue ice ({@code 0.989 * 0.91 = 0.900}). A sprint-swimming
     *  bot is driving on the slipperiest surface in the game — 9.00 blocks of coast per b/t of velocity,
     *  against stone's 1.20 — which is the whole reason a hand-set arrival constant was never going to hold
     *  here, and the reason the ground family's derived-gain argument transfers rather than being re-tuned. */
    static final double WATER_DRAG_SPRINT = 0.9;
    /** ...and while NOT sprinting — {@code LivingEntity.getWaterSlowDown()} returns {@code 0.8F} (coast 4.0). */
    static final double WATER_DRAG_UPRIGHT = 0.8;
    /** Vanilla VERTICAL velocity retention in water — the {@code 0.8F} in {@code vec32.multiply(f, 0.8F, f)}.
     *  Pose-INDEPENDENT, unlike the horizontal pair above. */
    static final double WATER_DRAG_VERTICAL = 0.8;
    /** Vanilla horizontal ACCELERATION per tick at full input in water — the {@code float g = 0.02F} handed to
     *  {@code moveRelative}. The bot carries no depth strider, so {@code Attributes.WATER_MOVEMENT_EFFICIENCY}
     *  is 0 and the {@code h > 0} branch (which would raise BOTH f and g) never runs. */
    static final double WATER_ACCEL = 0.02;

    /** Prone vertical steering rate toward the look pitch — the {@code e} in {@code Player.travel}, shallow
     *  branch ({@code d >= -0.2}: any rise, and gentle descents). */
    static final double PRONE_PITCH_RATE_SHALLOW = 0.06;
    /** ...and the STEEP-DIVE branch ({@code d < -0.2}), which vanilla runs faster. */
    static final double PRONE_PITCH_RATE_STEEP = 0.085;
    /** The branch point between the two rates above ({@code double e = d < -0.2 ? 0.085 : 0.06;}). */
    static final double PRONE_PITCH_STEEP_AT = -0.2;
    /**
     * Ceiling on {@code |lookAngle.y|} the prone drive will command — {@code sin(80 degrees)}.
     *
     * <p>Owner observation (2026-08-26), from in-game play: you can never sprint-swim straight up or straight
     * down, you always slip sideways. Two mechanisms are consistent with that and BOTH are satisfied by this
     * clamp, so it does not matter which is the true one: (a) the effective travel angle is bounded anyway,
     * because pitch ADDS vertical without SUBTRACTING horizontal — moveRelative is yaw-only, so a bot holding
     * forward keeps its full 0.18 b/t horizontal at any pitch, capping the climb at atan(0.194/0.18) ~ 47
     * degrees; (b) the look itself is bounded near vertical. Clamping costs nothing either way and buys a real
     * structural guarantee: it keeps the horizontal component of the synthesized look vector away from zero,
     * where {@link BotSteering#faceTowards} deliberately KEEPS the current yaw and the drive would silently
     * lose its steering. The planner never emits a pure-vertical sprint-swim (SprintSwim.candidates dropped
     * its verticals 2026-08-07; the upright Swim rungs own that axis), so this is a guard, not a limit.
     */
    static final double PRONE_LOOK_Y_MAX = 0.9848;

    /** Water's horizontal retention for the pose in question — the {@code q} every derived quantity below
     *  is a function of. Prone moves sprint; upright ones deliberately do not (sprinting would re-enter the
     *  prone pose — see {@code Entity.updateSwimming}, which keys the pose on {@code isSprinting()} ALONE). */
    static double swimDrag(boolean sprinting) {
        return sprinting ? WATER_DRAG_SPRINT : WATER_DRAG_UPRIGHT;
    }

    /**
     * TERMINAL horizontal swim speed (b/t) at full forward input: {@code v = a*q/(1-q)}, the fixed point of
     * vanilla's {@code v <- (v + a)*q}. Sprint 0.180, upright 0.080.
     *
     * <p>This is the servo's speed CAP, and it must be ACHIEVABLE — the single defect this rewrite exists to
     * fix. The retired {@code SERVO_CRUISE = 0.35} was documented as "deliberately unreachable" so the
     * forward key would saturate; the cost of that was paid in the FACING, which is the one thing that
     * actually steers in water. A reachable cap costs a few percent of straight-line speed (the servo hovers
     * just under terminal instead of pinned at it) and buys a velocity term with authority on every axis.
     */
    static double swimTerminal(boolean sprinting) {
        double q = swimDrag(sprinting);
        return WATER_ACCEL * q / (1.0 - q);
    }

    /**
     * The swim ARRIVE gain: b/t of desired closing speed per block of position error, {@code (1-q)/q} — the
     * reciprocal of the coast distance {@code q/(1-q)}. Sprint 0.1111 (coast 9.0), upright 0.2500 (coast 4.0).
     * Identical in form to {@link #groundArriveGain}, which reads {@code blockFriction * 0.91} for its
     * {@code q}; water's {@code q} is simply not a property of any block.
     *
     * <p>With this gain {@code min(cap, gain*dist)} is exactly the speed a pure-drag coast stops ON the
     * anchor, so the servo asks for full speed until {@code cap/gain} blocks out and eases from there.
     */
    static double swimArriveGain(boolean sprinting) {
        double q = swimDrag(sprinting);
        return (1.0 - q) / q;
    }

    /** Steady-state vertical speed per unit of {@code lookAngle.y} at steering rate {@code e}: the fixed point
     *  of {@code vy <- 0.8*(vy + (d - vy)*e)}, i.e. {@code 0.8*e/((1-0.8) + 0.8*e)}. Shallow 0.1935, steep
     *  0.2537 — the slopes {@link #proneLookYFor} inverts. */
    private static double proneVySlope(double rate) {
        return WATER_DRAG_VERTICAL * rate / ((1.0 - WATER_DRAG_VERTICAL) + WATER_DRAG_VERTICAL * rate);
    }

    /** The vertical speed a prone swimmer settles at while holding {@code lookAngle.y == d}. */
    static double proneVerticalTerminal(double d) {
        return proneVySlope(d < PRONE_PITCH_STEEP_AT ? PRONE_PITCH_RATE_STEEP : PRONE_PITCH_RATE_SHALLOW) * d;
    }

    /**
     * The inverse of {@link #proneVerticalTerminal}: the {@code lookAngle.y} to hold for a desired vertical
     * speed. Closed form because pitch commands a vertical VELOCITY directly rather than an acceleration.
     *
     * <p>The relation is piecewise-linear with a small DISCONTINUITY at the {@code d = -0.2} rate change
     * (the shallow branch reaches -0.0387 there, the steep branch starts at -0.0507). A desired speed inside
     * that window is unachievable at any pitch; it resolves to the shallowest steep pitch, which errs toward
     * a slightly faster descent than asked — bounded by 0.012 b/t and self-correcting on the next tick.
     */
    static double proneLookYFor(double desiredVy) {
        double d = desiredVy / proneVySlope(PRONE_PITCH_RATE_SHALLOW);
        if (d < PRONE_PITCH_STEEP_AT) {
            d = desiredVy / proneVySlope(PRONE_PITCH_RATE_STEEP);
            if (d > PRONE_PITCH_STEEP_AT) d = PRONE_PITCH_STEEP_AT;
        }
        return Math.max(-PRONE_LOOK_Y_MAX, Math.min(PRONE_LOOK_Y_MAX, d));
    }

    /**
     * THE ONE SWIM DRIVE. Anchors on the waypoint, forms a desired velocity from the position error at the
     * medium's own drag, and expresses the velocity ERROR as a look direction plus forward — the water
     * actuation ("face it, hold W"), with pitch carrying the vertical set-point when prone.
     *
     * <h2>Horizontal</h2>
     * {@code desired = unit(anchor - pos) * min(cap, gain * dist)}, then {@code err = desired - vel}, then
     * {@code yaw <- err}, {@code forward <- min(1, SERVO_GAIN*|err|)}. Past the anchor
     * {@code unit(anchor - pos)} reverses and the overshoot is answered with reverse thrust, exactly as
     * {@link #anchoredServo} does on land — a position-anchored law cannot walk away from its anchor the way
     * the retired velocity-only swim laws could.
     *
     * <h2>Carry-through</h2>
     * A bare arrive would come to REST on every intermediate waypoint, turning a long swim into stop-and-go.
     * So when the next leg continues STRAIGHT AHEAD ({@link #STRAIGHT_DOT}), the arrive ramp bottoms out at
     * the cruise rather than at zero and the bot carries full speed through. Any other continuation — a real
     * turn, a vertical next leg, the end of the plan — gets the honest arrive and comes to rest ON the
     * waypoint. That IS the corner brake, and it needs no probe of what lies outside the lane: squaring a
     * corner is what a 1-wide lane means, not a reaction to what happens to be standing in the next column.
     * See the body for the direction-blending variant that was tried and refuted.
     *
     * <h2>Vertical</h2>
     * PRONE holds a pitch whose steady-state vertical speed closes the depth error at the vertical drag's own
     * gain; UPRIGHT delegates to {@link #holdDepthAt}, because pitch is vertically inert outside the prone
     * pose ({@code Player.travel}'s steering block is gated on {@code isSwimming()}). The one place they still
     * meet: vanilla only applies the prone RISE when {@code d <= 0 || jumping || <fluid above the head>}, so a
     * prone bot rising into the surface layer — where there is no fluid above its head — needs jump held or
     * the pitch does nothing. That is a mechanism, not a servo, and it is the only jump this drive presses.
     */
    private static void swimArrive(BotSteering b, SteerView p, double bias, boolean declaredProne,
                                   String tagName, String tagDead) {
        // THE POSE IS THE BOT'S FACT, NOT THE CALLER'S ASSUMPTION (2026-08-29; the (337,59,414) wedge).
        // `declaredProne` is a per-callsite CONSTANT -- true from swimServo, false from uprightSwimServo --
        // so it states what the MOVE believes, and the two disagree for as long as a pose transition takes.
        // StartSprintSwim is *definitionally* that disagreement: it is the STANDING->PRONE transition, and
        // vanilla flips the pose out from under it the moment isSprinting() && isUnderWater() (updateSwimming),
        // while its steer keeps calling uprightSwimServo. Everything below that reads the pose then reads it
        // WRONG for the rest of the move, and two of those are load-bearing:
        //
        //   * PITCH. faceTowards -- the only pitch write on this path -- is inside `if (prone)`. Declared
        //     upright, nothing commands pitch, and Player.travel's look-steering (live in the prone pose, up
        //     to 0.085/t) steers the whole vertical axis off whatever stale value was left behind. Measured:
        //     pitch ~-90 => terminal -0.1444 b/t WITH jump held; the bot sank to the seabed under its own
        //     rise command and no swim move has a failWhen to end it.
        //   * CLIENT LEGALITY. The SERVO_FORWARD_MIN floor below is likewise inside `if (prone)`. Skipping it
        //     let the bot hold sprint at fwd=0.00 while airborne in water -- a combination NO real player can
        //     produce, because LocalPlayer.shouldStopSwimSprinting drops sprint on exactly
        //     `!hasForwardImpulse() && !onGround() && !shift`. That illegal state is what held the pose for
        //     3,648 ticks of pressing nothing.
        //
        // OR rather than replace, so the prone callers are bit-identical (they already pass true, and a
        // declared-prone move mid-transition must keep its prone law rather than flip laws for a tick). Only
        // the upright-declared path changes, and only when the bot is PHYSICALLY prone -- where the prone
        // terminal/gain are also simply the correct physics for the hitbox it actually has. Safe on the depth
        // target because SUBMERGE_BIAS is identity, so both callers aim at the same wy+SWIM_RIDE.
        boolean prone = declaredProne || b.prone();
        double cap  = swimTerminal(prone);
        double gain = swimArriveGain(prone);

        // ---- horizontal desired velocity ------------------------------------------------------------------
        //
        // DIRECTION comes from the LINE, SPEED from the ARRIVE. That split is the whole design:
        //   * the pursuit point (computeGeom, cte-shrunken) carries the cross-track RETURN, so the bot tracks
        //     the planned segment rather than merely heading for its far end. Aiming straight at the endpoint
        //     is a corner cut by construction, and in a 1-wide lane with a lethal wall a corner cut is the
        //     whole failure mode (`mazeportal`, see SWIM_CTE_GAIN).
        //   * the magnitude is min(cap, gain * distanceToAnchor) — the achievable terminal, eased so a
        //     pure-drag coast stops ON the waypoint. This is the half the retired servo got wrong.
        // A degenerate (vertical) segment needs no branch: computeGeom aims q at the column and reports the
        // horizontal offset as cte, so the same two lines become a station-keep.
        computeGeom(b, p, SWIM_CTE_GAIN);
        double ax = p.tx(), az = p.tz();
        double dist = Math.hypot(ax - b.x(), az - b.z());

        // CARRY-THROUGH is a property of the SPEED, never of the direction (owner ruling 2026-08-26, after
        // measuring the alternative). A bare arrive comes to REST on every intermediate waypoint, which turns
        // a long swim into stop-and-go; the fix is to let the arrive ramp bottom out at the CRUISE instead of
        // at zero when the plan continues straight ahead.
        //
        // THE VERSION THAT DID NOT WORK, recorded so it is not re-invented: blending the desired DIRECTION
        // toward the next leg over the coast distance (cap/gain = 1.62 blocks). It reads well — "arrive
        // already travelling along the next leg" — and it makes the corner brake fall out for free, but the
        // length is wrong by construction. The coast distance is how far the bot needs to BRAKE; it has
        // nothing to do with how far ahead it may begin to ROTATE. In a 1-wide lane a 1.62-block rounded turn
        // puts the box in the neighbouring column long before the corner. SwimCourse `mazeportal` — end
        // portal, where any box intersection is an instant teleport — caught it; `swimmaze` and `mazelava`
        // tolerated the same graze and stayed green, which is exactly why a card with no margin earns its keep.
        //
        // So: STRAIGHT continuation (STRAIGHT_DOT, the existing ~25-degree in-line test) keeps full cruise
        // through the waypoint. Anything else — a real turn, a vertical next leg, or the end of the plan —
        // gets the honest arrive and comes to rest ON the waypoint before the next leg starts. Squaring the
        // corner is not a hazard measure; it is what a 1-wide lane means, and it costs only the re-accel the
        // arrive ramp was always going to pay.
        double exit = 0.0;
        if (p.hasNext()) {
            double segX = ax - p.sx(), segZ = az - p.sz();
            double segLen = Math.hypot(segX, segZ);
            double nx = p.nx() - ax, nz = p.nz() - az;
            double nl = Math.hypot(nx, nz);
            if (segLen > EPS && nl > EPS
                    && (segX * nx + segZ * nz) / (segLen * nl) >= STRAIGHT_DOT) {
                exit = cap;
            }
        }

        double dirx = G.qx - b.x(), dirz = G.qz - b.z();
        double dl = Math.hypot(dirx, dirz);
        double dvx = 0.0, dvz = 0.0;
        if (dl >= EPS) {
            double sp = Math.min(cap, gain * dist + exit);
            dvx = (dirx / dl) * sp;
            dvz = (dirz / dl) * sp;
        }

        // ---- the velocity error, and the TAG, BEFORE any input write -------------------------------------
        // Ordering is the §4 tag invariant, not style: the vertical block below writes jump/sink, and an
        // untagged tick reads the PREVIOUS servo's `src` in the exec log. Everything the error needs is
        // already computed, so it costs nothing to establish it first.
        double errx = dvx - b.velX();
        double errz = dvz - b.velZ();
        double emag = Math.sqrt(errx * errx + errz * errz);
        boolean dead = emag < SERVO_DEADBAND;
        tag(dead ? tagDead : tagName);

        // ---- vertical: ONE set-point, and for the prone pose TWO actuators ------------------------------
        //
        // holdDepthAt is the jump/sink impulse and runs for BOTH poses. Upright it is the whole story, because
        // Player.travel's pitch-steering block is gated on isSwimming() and so is inert out of the prone pose.
        //
        // PRONE ADDS PITCH ON TOP, and the two are complementary rather than rival — they are driven from the
        // same `depth`, so their signs agree by construction. MEASURED (2026-08-26): dropping holdDepthAt from
        // the prone path on the theory that "pitch owns prone vertical" regressed SwimCourse `rise` from PASS
        // to a timeout at finalY 156.20 of a 160 goal. The card routes a prone DiagonalSprintSwim zig-zag —
        // +1Y per +1X, a 45-degree climb — and pitch ALONE cannot hold that: moveRelative is yaw-only, so a
        // bot holding forward keeps its full 0.18 b/t horizontal at any pitch, capping the climb at
        // atan(0.1906/0.18) = 46.6 degrees BEFORE the in-water gravity term. 45 against 46.6 is not a margin.
        // Vanilla's jumpInLiquid +0.04/t is what turns it into one.
        //
        // There is a second, sharper reason the jump belongs here. Vanilla applies the prone pitch-steering
        // only when `d <= 0 || jumping || <fluid above the head>` — so on a RISE that breaks the surface, jump
        // is not merely extra lift, it is what LICENSES the pitch term at all. Suppressing it would have made
        // the pitch inert in exactly the case it was introduced to serve.
        double depth = swimDepthTarget(p, bias);
        holdDepthAt(b, depth);
        double look = 0.0;
        if (prone) {
            // FEED-FORWARD + FEEDBACK, and the feed-forward is the load-bearing half.
            //
            // The error term alone (vgain * depthError) is a P-controller, and a climb is a RAMP input: a
            // P-controller tracking a ramp settles at a STANDING OFFSET and never catches up. Measured on the
            // SwimCourse `rise` card, whose route is a prone DiagonalSprintSwim zig-zag at +1 Y per +1 XZ —
            // a 45-degree climb. With feedback only, a mid-segment depth error of ~0.5 commands
            // 0.25*0.5 = 0.125 b/t of rise against 0.18 b/t of horizontal: 34.8 degrees, ten degrees shallower
            // than the plan. The bot sinks below its own line until the accumulated error is big enough to
            // command the pitch it needed from the start. That is the vertical twin of the class-1 defect the
            // whole servo normalization exists to remove.
            //
            // So the set-point leads with what the SEGMENT'S OWN GEOMETRY demands — its slope times the speed
            // actually being carried — and uses the error only to correct deviation from it. On a level
            // segment the slope is 0 and this reduces exactly to the feedback-only law.
            //
            // Feasibility, for the record, because it was mis-stated once: pitch ADDS vertical without
            // SUBTRACTING horizontal (moveRelative is yaw-only), so at FULL forward the climb ceiling is
            // atan(0.1906/0.18) = 46.6 degrees — 45 fits, with ~6% to spare — and easing the forward key
            // raises it steeply (0.8 of the key gives 52.9 degrees). There was never a planner-envelope
            // problem here; there was a controller that did not ask for the climb it had been given.
            // THE FEED-FORWARD IS DRIVEN BY THE COMMANDED HORIZONTAL SPEED, NOT THE CARRIED ONE — the two
            // axes must ramp together. Driving it from the measured velocity regressed SwimCourse `gap2x1`
            // (ejected, finalY 161.50 against a 161.0 surface): that card dips under a wall and climbs back
            // to the surface layer, and while the horizontal arrive was easing toward the waypoint the
            // vertical kept demanding the full slope rate. Same dy, less dx, so the EFFECTIVE climb angle
            // steepened past what the plan asked and the bot flew up through the surface, breached, and lost
            // the prone pose. Slaving the feed-forward to `sp` makes it decay in lockstep, so the bot levels
            // off exactly as it arrives instead of climbing through the waypoint.
            double vgain = (1.0 - WATER_DRAG_VERTICAL) / WATER_DRAG_VERTICAL;
            double runXZ = Math.hypot(p.tx() - p.sx(), p.tz() - p.sz());
            double slope = runXZ > EPS ? (p.ty() - p.sy()) / runXZ : 0.0;
            // ...and specifically the ARRIVE component of it, never the carry-through. The colinearity test
            // that keeps `exit` alive is HORIZONTAL, so a rising leg followed by a level one counts as
            // "straight ahead" and holds full cruise into the waypoint — correct for the horizontal axis,
            // fatal for the vertical if the feed-forward rides it, because THIS leg's slope keeps being
            // demanded through a waypoint where the next leg is flat. Measured on `gap2x1` (block plan wp3
            // DiagonalSprintSwim d(1,+1,0) into wp4 SprintSwim d(1,0,0)): the bot arrived at botY 160.725
            // for a 160.2 set-point and kept going, breaching at 161.29 and losing the prone pose.
            //
            // Riding the arrive term instead makes the feed-forward RETIRE as the waypoint is reached — it is
            // the rate needed to get to THIS waypoint's height, and it has no business outliving it. The next
            // segment's slope takes over the moment the cursor advances.
            double arriveXZ = Math.min(cap, gain * dist);
            look = proneLookYFor(slope * arriveXZ + vgain * (depth - b.y()));
        }

        // ---- actuation: face the velocity error, hold forward --------------------------------------------
        double hx = 0.0, hz = 0.0;
        if (!dead) {
            hx = errx / emag; hz = errz / emag;              // the correction IS the heading (water actuation)
        } else {
            double vl = Math.sqrt(dvx * dvx + dvz * dvz);    // at speed: hold the travel heading and coast
            if (vl > EPS) { hx = dvx / vl; hz = dvz / vl; }
        }
        double fwd = dead ? 0.0 : Math.min(1.0, SERVO_GAIN * emag);

        if (prone) {
            // Synthesize a look vector whose NORMALIZED y is exactly the commanded set-point: scaling the
            // horizontal part by sqrt(1-look^2) makes |(h*c, look)| == 1, so faceTowards' own normalization
            // reproduces `look` rather than some pitch that merely points at a target.
            double c = Math.sqrt(Math.max(0.0, 1.0 - look * look));
            // Guard the fully-degenerate aim: station-keeping dead-centre at the exact planned depth leaves
            // nothing to face at all, and faceTowards(0,0,0) has no defined heading. Hold the current look.
            if (hx != 0.0 || hz != 0.0 || look != 0.0) {
                b.faceTowards(hx * c, look, hz * c);
            }
            // Client-legality only: a headless bot's prone pose is held by isSprinting() ALONE
            // (Entity.updateSwimming), so this floor is NOT load-bearing here — it exists so a future
            // CLIENT-driven bot, whose LocalPlayer keeps the pose on hasForwardImpulse, behaves identically.
            if (b.prone() && b.inWater() && !b.grounded()) fwd = Math.max(fwd, SERVO_FORWARD_MIN);
        } else if (hx != 0.0 || hz != 0.0) {
            b.faceHorizontally(hx, hz);
        }
        b.setForward((float) fwd);
        b.setStrafe(0.0f);                                   // the zza invariant: never leave a stale key
    }

    /**
     * PRONE sprint-swim drive — {@link #swimArrive} in the prone pose, where pitch owns the vertical axis.
     * Drives {@link com.orebit.mod.pathfinding.blockpathfinder.movements.SprintSwim SprintSwim} and its
     * diagonal subclass. The caller supplies the pose {@code bias}; it must NOT also call
     * {@link #holdDepth} — jump/sink and pitch are two vertical controllers and they fight.
     */
    public static void swimServo(BotSteering b, SteerView p, double bias) {
        swimArrive(b, p, bias, true, "swim:prone", "swim:prone:dead");
    }

    /**
     * UPRIGHT swim drive — {@link #swimArrive} out of the prone pose, where the vertical axis belongs to
     * {@link #holdDepthAt} (which this calls itself). Drives the tall {@link
     * com.orebit.mod.pathfinding.blockpathfinder.movements.Swim Swim} / {@link
     * com.orebit.mod.pathfinding.blockpathfinder.movements.Surface Surface} poses, the {@code EndSprintSwim}
     * stand-up, and {@link #drive}'s in-water branch.
     */
    public static void uprightSwimServo(BotSteering b, SteerView p) {
        swimArrive(b, p, 0.0, false, "swim:up", "swim:up:dead");
    }

    // groundServo was DELETED here on 2026-08-24 (owner ruling). It was the last non-normalized ground
    // servo: an input-only velocity controller that faced the raw velocity error with no position term,
    // so near a segment end -- where the scheduled speed drops under the residual velocity -- its facing
    // flipped 180 degrees per tick at saturated throttle and it parked the bot short of the target in a
    // stable limit cycle. Phase 2 (2026-08-20) retired its hazard branch onto arriveOnStep; Phase 3 does
    // the same for the pursuit branch, so ONE position-anchored law now owns every grounded drive tick.
    // The behaviour it was kept for -- holding a 1-wide low-friction lane -- is guarded by IceParkourCourse.

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
        // Feet rest on the landing floor's REAL top face, read live in sixteenths — NOT a blanket +1.0
        // (owner ruling 2026-08-24; the slabflat2.walkin conviction). A full block is 16/16 so this is
        // byte-identical there, and surfaceTopYToward defaults to 16, so every test double is unchanged.
        //
        // The blanket +1.0 ended the predicted arc HALF A BLOCK EARLY on a slab (top 8/16), and the servo
        // then did exactly the wrong thing for exactly the right reason. Measured on slabflat2.walkin: aim
        // d = 11.7, predicted touchdown ~11.6 (the arc cut at y=151.0, where the bot was still at x=11.605),
        // so the servo read "predicted SHORT" and accelerated for eight ticks. Reality ended the arc at
        // y=150.5, carrying it to x=11.996 -- 0.296 past its own aim, with only three ticks left to brake --
        // and the 0.17 it still carried coasted it across the cell boundary, where the validity envelope
        // correctly failed a jump that had physically succeeded.
        //
        // Direction is the travel axis, mirroring Parkour's lowHalfStair call: only a BOTTOM stair reads
        // direction-dependently, and the landing is approached along (ux,uz).
        final double landTop = b.surfaceTopYToward(tx, ty, tz,
                (int) Math.signum(ux), (int) Math.signum(uz)) / 16.0;
        final double landY = ty + landTop;
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

        // ---- THE ALONG-AXIS OBJECTIVE (owner ruling 2026-08-25) ------------------------------------
        //
        // Two predictions, both from the verified ballistic recurrence: where the feet touch down if the bot
        // COASTS from here, and where they touch down if it brakes as hard as it can from here.
        //
        // WHY THERE ARE TWO OBJECTIVES, not one. The old law had a single one -- put the touchdown at `d` --
        // and it treated any touchdown within PARKOUR_PREDICT_DEAD of `d` as finished ("on target -> hold
        // current momentum"). But a jump has TWO degrees of freedom inside the reach budget: WHERE you land
        // and HOW FAST you are going when you get there. Constraining only the first leaves the second to
        // fall out of whatever the arc happened to do, and the second is the one that decides where the bot
        // ENDS UP -- because touchdown speed times the surface's coast IS the slide.
        //
        // Measured on IceParkourCourse ice.chain.g3: per-tick horizontal displacement over the final jump ran
        // 0.403 0.356 0.335 0.326 0.321 0.318 0.314 0.312 | 0.284 0.258 0.235 0.214. Air drag alone is 0.91,
        // so ratios ABOVE it are forward thrust and ratios AT it are free coast: the servo pushed FORWARD for
        // seven ticks, then coasted at exactly 0.910/0.908/0.911/0.910. It applied no reverse braking at all,
        // touched down at 0.194 b/t, and on ice that is a 1.6-block slide it then had to fight on the ground.
        //
        // THE NEW OBJECTIVE, when the plan is not carrying through: land as SLOWLY as the reach budget
        // allows. That is a bang-bang optimal-control problem and its solution is "coast at full speed, then
        // brake as LATE as possible" -- so the switching test is simply "would braking from here still reach
        // the landing?", i.e. pReverse >= cnSafe. Before that point braking would undershoot into the gap;
        // after it, every tick spent not braking is touchdown speed the ground servo has to absorb.
        //
        // Note what this does NOT need: pReverse was already being computed and already compared against
        // cnSafe -- but only inside the overshoot branch, as a SAFETY CHECK on a decision made for other
        // reasons. Promoting it from a veto to the objective is the whole change. PARKOUR_PREDICT_DEAD
        // survives only on the carry-through path; nothing new is tuned.
        //
        // AND WHAT IT MUST NOT DO: brake when the plan wants the momentum. `colinear` (already computed
        // above, from p.hasNext() and the next leg's direction) is exactly that question -- a chain whose
        // next leg continues straight ahead is asking to arrive fast, and `d` is already displaced forward by
        // PARKOUR_CARRY_AHEAD to suit. Minimising touchdown speed there would fight the plan, so the
        // carry-through case keeps the position-only law verbatim.
        double pNeutral = predictAlongTouchdown(s, v, b.y(), b.velY(), landY, 0, accel);
        double pReverse = predictAlongTouchdown(s, v, b.y(), b.velY(), landY, -1, accel);
        double desiredAlong;
        if (colinear) {
            // CARRY THROUGH: the next leg continues straight ahead, so momentum is wanted. Position-only
            // law, unchanged -- aim the touchdown at `d` and hold whatever speed that takes.
            if (pNeutral < d - PARKOUR_PREDICT_DEAD) {
                desiredAlong = PARKOUR_CRUISE;                // predicted short → accelerate forward
            } else if (pNeutral > d + PARKOUR_PREDICT_DEAD) {
                desiredAlong = (pReverse >= cnSafe) ? 0.0     // safe to brake to a stop-target
                                                    : v;      // braking would undershoot → coast
            } else {
                desiredAlong = v;                             // on target → hold current momentum
            }
        } else if (pReverse >= cnSafe) {
            // ARRIVE SLOW: braking from here still reaches the landing, so brake. Held every tick from the
            // switching point on, which is what makes this "brake late and hard" rather than "brake gently
            // for the whole arc" -- the latter bleeds the speed that buys the reach in the first place.
            desiredAlong = 0.0;
        } else if (pNeutral < d - PARKOUR_PREDICT_DEAD) {
            desiredAlong = PARKOUR_CRUISE;                    // still short of the landing → drive forward
        } else {
            desiredAlong = v;                                 // cannot afford to brake yet → coast
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
     * The parkour TAKEOFF <b>launch-alignment</b> drive — the <b>ballistic-runup composition</b>
     * (DESIGN-servo-normalization.md §2.4, ratified 2026-08-19): two instances of the unified cascade in
     * the jump-axis frame, one per axis, sharing {@link #actuate the core's actuation tail}. A diagonal
     * jump launches a ballistic arc, and the arc is only a clean 45° (the geometry the envelope + airborne
     * servo assume) if the bot's horizontal VELOCITY is on the jump axis at take-off — so the takeoff tick
     * must hold full launch momentum ALONG the axis while converging position AND velocity onto it ACROSS.
     *
     * <ul>
     *   <li><b>Along axis</b> ({@code (ux,uz)}): ARRIVE at the LANDING CENTRE's along-coordinate —
     *       {@code desired_along = signum(dAlong) · min(SERVO_GROUND_CRUISE, ARRIVE_GAIN_GROUND·|dAlong|)}.
     *       The landing centre sits a full jump ahead of any takeoff stand (≥ 1.4 blocks even on the
     *       shortest offered jump), far outside the cap/gainP easing crossover ({@code 0.35/0.831 ≈ 0.42}
     *       blocks), so on every live tick the cap saturates and the drive is a full-cruise advance toward
     *       the landing.</li>
     *   <li><b>Cross axis</b>: HOLD on the jump-axis centreline — the line through the landing centre
     *       with direction {@code (ux,uz)} — with {@link #parkourAirborne}'s cross law verbatim:
     *       {@code desired_cross = clamp(SERVO_CROSS_GAIN · crossErr, ±SERVO_CROSS_CAP)}, a bounded pull
     *       back onto the axis. Why per-axis and not one isotropic ARRIVE at the landing: an isotropic
     *       ARRIVE converges the cross error only LINEARLY over the remaining distance — the intercept
     *       happens AT the anchor — so a long jump (gap-4) would launch while still displaced off-axis,
     *       violating the planner's prism assumption (hitbox inside the 1-wide corridor). The per-axis
     *       HOLD makes the cross convergence rate independent of jump length. The lane half-width the
     *       prism assumes, ±0.2, is DERIVED, not new: {@code 0.5 − }{@link #PARKOUR_CELL_MARGIN}
     *       (0.3 = the player half-width).</li>
     * </ul>
     *
     * <p><b>Why the anchor is the LANDING centre and not the takeoff gate</b> (the backwards-phantom-hop
     * conviction, ParkourCourse 2026-08-19). The first re-anchoring of this drive put an ARRIVE at the
     * GATE point — the point whose along-line crossing TRIGGERS the takeoff — so on the jump tick the
     * bot stood AT its own anchor moving 0.21–0.27 b/t: desired velocity ≈ 0, error = −vel (a full
     * backward error, over {@link #FACE_ERR_THRESHOLD}), and the servo yawed the bot around and thrust
     * BACKWARD on the very tick {@code setJumping(true)} fired — a backwards phantom hop that consumed
     * the one-shot jump arm, after which the re-approach rolled off the lip with no jump (9–10
     * ParkourCourse trials, the "fell" signature). The takeoff tick IS this drive's whole live exposure
     * (the runup migrated to {@link #steerViaGate}), so an anchor that eases "only inside the final
     * fraction of a block" was easing EXACTLY where it lived. With the landing centre as the along anchor
     * that geometry cannot recur: the anchor is never underfoot, the error points down-axis, and the
     * launch is at full cruise.
     *
     * <p>The RETIRED pre-normalization law was the audit's class-1 conviction on the CROSS axis: desired
     * velocity {@code (ux,uz)·cruise} — a zero cross SETPOINT with no cross position term, structurally
     * {@code stepOffGate}'s retired along-axis flaw rotated 90°: it bled cross-axis SPEED but was
     * indifferent to where the bot had already been pushed. Yaw is semantic — face the jump axis (§2.2)
     * — no velocity is ever written, input only (look + forward/strafe), the Baritone model.
     *
     * @param landingX the LANDING cell's centre (world x) — the along anchor + the centreline's point
     * @param landingZ the LANDING cell's centre (world z)
     */
    public static void parkourRunupAlign(BotSteering b, double ux, double uz,
                                         double landingX, double landingZ) {
        // Jump-axis frame (parkourAirborne's, verbatim): along = ·(ux,uz); cross unit = (−uz, ux), 90°
        // LEFT of the axis — so +crossErr below means the centreline lies to the bot's LEFT.
        final double crossUx = -uz, crossUz = ux;
        // ALONG — ARRIVE at the landing centre's along-coordinate: saturates to full cruise over the whole
        // takeoff stand (the easing region can never contain it — see the class doc).
        double dAlong = (landingX - b.x()) * ux + (landingZ - b.z()) * uz;
        double desiredAlong = Math.signum(dAlong)
                * Math.min(SERVO_GROUND_CRUISE, ARRIVE_GAIN_GROUND * Math.abs(dAlong));
        // CROSS — HOLD on the jump-axis centreline through the landing centre (parkourAirborne's cross
        // law): a bounded pull back onto the axis, convergence rate independent of jump length.
        double crossErr = (landingX * crossUx + landingZ * crossUz) - (b.x() * crossUx + b.z() * crossUz);
        double desiredCross = Math.max(-SERVO_CROSS_CAP, Math.min(SERVO_CROSS_CAP, SERVO_CROSS_GAIN * crossErr));
        // Compose in the world frame, then the unified core's shared tail (err → deadband → semantic yaw
        // at the jump axis → signed forward/strafe → vector saturation; tag stamped unconditionally first).
        double dvx = ux * desiredAlong + crossUx * desiredCross;
        double dvz = uz * desiredAlong + crossUz * desiredCross;
        actuate(b, "arrive:runup", "arrive:runup:dead", dvx - b.velX(), dvz - b.velZ(), ux, uz);
    }

    /**
     * CROSS-AXIS CANCEL — the grounded pre-run-up servo (owner ruling 2026-08-26). Drives the CROSS axis of
     * the jump frame to zero, in both position and velocity, and asks for zero ALONG velocity so the whole
     * input budget goes into the correction rather than competing with a run-up that has not started yet.
     *
     * <h2>Why a separate phase and not just the run-up's cross term</h2>
     * {@link #parkourRunupAlign} already carries a cross term, but it runs SIMULTANEOUSLY with an along-axis
     * ARRIVE that saturates to full cruise over the whole takeoff stand. {@link #actuate} then saturates the
     * pair as a VECTOR, so the cross correction only ever gets whatever authority the along term leaves it —
     * and the run-up is over in two ticks, which is not enough to cancel a real cross carry.
     *
     * <p>Measured on the 2026-08-26 long flagship, {@code Parkour} step 10. Its predecessor was
     * {@code Ascend (77,145,261) -> (77,146,262)} — a +Z move feeding an +X jump — so the bot entered the
     * takeoff cell CENTRED ({@code offCentre=(0.022,-0.011)}) but carrying {@code vel=(0.002, 0.130)}, i.e.
     * 0.130 b/t of pure cross. Two run-up ticks took that to 0.0335 and left the bot 0.181 off-centre in Z at
     * launch. The cost was not paid on the ground: {@code parkourAirborne} then spent the first FOUR airborne
     * ticks yawed up to 48 degrees off the jump axis correcting cross-track ({@code headX} 0.667, 0.783,
     * 0.911, 0.991), so a third of the air acceleration bought lateral correction instead of reach. The jump
     * arrived 0.069 blocks under the landing surface, struck the side of the landing block and fell.
     *
     * <p><b>Launch SPEED was never the differentiator</b> — {@code falld2g4.rest} makes the identical jump
     * from rest at 0.1076 against the flagship's 0.1079 and has always passed. What it does not carry is
     * cross momentum. Straighten the launch and the same speed clears the gap; that is the whole finding, and
     * it is why no amount of takeoff-edge or run-up tuning addressed it.
     *
     * <h2>Self-gating</h2>
     * The phase this drives advances the moment cross position and cross velocity are both settled, and the
     * runner advances BEFORE it drives — so a bot entering along its own jump axis (out of another Parkour, a
     * Diagonal, a straight Traverse) satisfies the test on the first check, never runs this servo, and keeps
     * its carry intact. It costs a tick only when there is genuinely something to cancel.
     */
    public static void parkourCrossCancel(BotSteering b, double ux, double uz,
                                          double takeoffCx, double takeoffCz) {
        final double crossUx = -uz, crossUz = ux;          // parkourAirborne's frame, verbatim
        double crossErr = (takeoffCx * crossUx + takeoffCz * crossUz)
                - (b.x() * crossUx + b.z() * crossUz);
        double desiredCross = Math.max(-SERVO_CROSS_CAP,
                Math.min(SERVO_CROSS_CAP, SERVO_CROSS_GAIN * crossErr));
        // ALONG desired is ZERO: the run-up owns that axis and has not begun. Asking for zero here also
        // brakes an along carry -- which is exactly why the gate below must let an aligned hot entry skip
        // this phase entirely rather than have it stand the bot up.
        double dvx = crossUx * desiredCross;
        double dvz = crossUz * desiredCross;
        actuate(b, "parkour:align", "parkour:align:dead", dvx - b.velX(), dvz - b.velZ(), ux, uz);
    }

    /**
     * Is the jump frame's CROSS axis settled enough to commit a launch — position within
     * {@link #COLUMN_DEADBAND} of the takeoff centreline and cross velocity inside the servo's own
     * {@link #SERVO_DEADBAND}? Both bounds are existing derived quantities, not new tuning: the first is the
     * tolerance parkour re-centring already treats as "on the column", the second is the threshold at which
     * every servo in this file already calls a velocity error settled.
     *
     * <p>The flagship launched 0.181 off-centre carrying 0.0335 of cross — outside both.
     */
    public static boolean parkourCrossSettled(BotSteering b, double ux, double uz,
                                              double takeoffCx, double takeoffCz) {
        final double crossUx = -uz, crossUz = ux;
        double crossErr = (takeoffCx * crossUx + takeoffCz * crossUz)
                - (b.x() * crossUx + b.z() * crossUz);
        double crossVel = b.velX() * crossUx + b.velZ() * crossUz;
        // WHERE THE BOT WILL END UP, not where it is: the current cross offset PLUS the distance the
        // remaining cross velocity will still carry it, against the lane tolerance. Taken as absolute values
        // so the two never cancel — conservative in the direction of aligning, which is the safe one.
        //
        // NOT an instantaneous velocity test. That was the first cut (|crossVel| < SERVO_DEADBAND) and it
        // WEDGED the long flagship at (278,113,352): the bot sat 0.035 off the centreline — comfortably
        // inside COLUMN_DEADBAND — while its cross velocity chattered at ±0.0346, above the 0.02 band. The
        // servo's own error then landed just past FACE_ERR_THRESHOLD, so actuate() took the yaw-onto-error
        // escape and commanded FULL throttle, which moves cross velocity ~0.069 in a tick and overshot a
        // 0.035 error symmetrically. A clean 2-cycle, forever:
        //
        //   x=278.465 vel=-0.0346 yaw=-90 fwd=1.00
        //   x=278.528 vel=+0.0346 yaw=+90 fwd=1.00
        //
        // The lesson is general: NEVER GATE A PHASE TRANSITION ON A QUANTITY THAT CHATTERS BELOW THE
        // ACTUATOR'S RESOLUTION. One tick of the smallest useful input changes cross velocity by more than
        // SERVO_DEADBAND, so that test can never be satisfied on a surface the bot is actively driving. The
        // other servos survive the same chatter only because they are position-anchored and never gate on it.
        //
        // Displacement is the honest quantity, and it separates the two cases cleanly:
        //   flagship entry   0.011 + 0.130 * 1.202 = 0.167  > 0.15  -> align (correct: it launched 0.181 off)
        //   the wedge        0.035 + 0.0346 * 1.202 = 0.077 < 0.15  -> settle (correct: harmless)
        double q = Math.max(EPS, Math.min(b.slipperinessAt(b.footX(), b.footY() - 1, b.footZ())
                * VANILLA_HORIZONTAL_DRAG, 1.0 - EPS));
        double coast = q / (1.0 - q);
        return Math.abs(crossErr) + Math.abs(crossVel) * coast < COLUMN_DEADBAND;
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
     * <p><b>Actuation</b> is the standard velocity-servo idiom ({@code groundServo} (deleted 2026-08-24)/{@link
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
     *
     * <p><b>Frame.</b> {@code (sx,sz)} &rarr; {@code (tx,tz)} is the along-track axis the crossing is measured
     * on; {@code (gx,gz)} the gate. Consumers anchor it on their OWN plan geometry ({@code DiagonalParkour}'s
     * known takeoff/landing cells) so a mid-path adoption cannot skew the projection axis.
     *
     * <p><b>The {@link SteerView} convenience overload was deleted 2026-08-20</b> (owner ruling, the Tier A
     * free-delete pass): it only forwarded {@code p.sx(),p.sz(),p.tx(),p.tz()} into this core and had zero
     * production callers — precisely because the live segment view is the axis a consumer should NOT silently
     * inherit. A caller that genuinely wants the segment frame passes those four accessors explicitly, which
     * keeps the choice visible at the call site.
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
    // ================================================================================================
    // The GROUND HAZARD FAMILY WAS DELETED HERE on 2026-08-25 (owner ruling). Removed together:
    // groundHazardCorner, groundOvershootHazard, groundFlankHazard, groundFlankVoid,
    // plannedDescentCell, groundLavaColumn, groundVoidColumn, plus STEP_ARRIVE_OFFSET /
    // STEP_ARRIVE_MARGIN / ARRIVE_GAIN_GROUND. ~90 lines and three constants, whose ENTIRE effect on
    // behaviour was one bit that moved arriveOnStep's anchor 0.19 blocks back along the travel axis.
    //
    // WHY IT WAS THE WRONG SHAPE. The question a corner brake has to answer is "from how far out must I
    // start slowing so I come to rest ON the anchor rather than past it?" That is a function of SPEED and
    // the SURFACE'S DRAG -- it is not a question about the contents of any cell. This family asked
    // "is there something bad within HAZARD_LOOKAHEAD cells past my waypoint?" and used the answer as a
    // proxy, which failed three independent ways:
    //
    //   1. BINARY where the quantity is CONTINUOUS. The correction was STEP_ARRIVE_OFFSET, i.e.
    //      0.5 - BODY_RADIUS - margin -- a BODY-GEOMETRY constant with no relationship to braking at all.
    //      A bot at the 0.35 b/t cruise cap needs 0.42 blocks of run-out on stone and 2.88 on ice; it got
    //      0.19 on both. On ice that is ~15x too small, which is why it never actually solved the ice
    //      corner it was introduced for.
    //   2. CONTENTS-BASED, so it fired on cells the PLAN DELIBERATELY CHOSE. The planner already priced
    //      that lava / that drop / that gap and routed through it anyway; the follower's job is to execute
    //      the plan, not to re-litigate its cost model. plannedDescentCell was a one-cell patch over this,
    //      and it could only ever exempt ONE cell (SteerView is one waypoint deep) and only for a DESCENT.
    //   3. It re-derived geometry the NavGrid already models, WRONGLY. groundVoidColumn read
    //      !solidAt(x, F.cy - 1, z) with F.cy the waypoint's FEET cell -- baking in "the floor is always
    //      one below the feet". A partial-height floor block breaks that: standing on a 3/16 trapdoor
    //      plate puts the feet cell INSIDE the floor block's own cell, so the probe read a cell BELOW the
    //      corridor floor, found air, and flagged the entire lane ahead as void. That is what hung the
    //      trapdoor course's `pocket` and `closeparkour` cards for 600 ticks apiece.
    //
    // THE REPLACEMENT is groundArriveGain: derive the ARRIVE gain from the friction the bot will actually
    // brake against. One friction read, no lookahead, no cell contents, no flank probes, no crossTrack, no
    // exemptions -- and no false positives are POSSIBLE, because it never inspects what is in a cell.
    // Fail-safe by construction too: where !solidAt() turned "no block" into HAZARD, getFriction() turns
    // "no block" into 0.6, the ordinary value.
    //
    // THE SWIM HAZARD FAMILY FOLLOWED IT on 2026-08-26, once that evidence existed. Removed together:
    // overshootHazard / flankHazard / hazardColumn / travelFrame / blendLeavesLane, plus HAZARD_LOOKAHEAD /
    // FLANK_DRIFT / LANE_ADMIT / SWIM_CTE_GAIN / TURN_CRAWL_THROTTLE / TURN_ARRIVE_OFFSET / SERVO_CRUISE /
    // SERVO_HAZARD_RAMP / SERVO_TURN_FLOOR / CORNER_BLEND_DIST / CORNER_BLEND_MAX / CORNER_RACING_BIAS and
    // computeGeom's lane-gate overload.
    //
    // The note above was right that water's uniform drag does not, by itself, license the deletion — the
    // swim family had a second job the ground one did not: its hazard ramp was the ONLY thing that ever made
    // the servo's speed cap reachable (min(0.35, max(0.11, 0.16*dist)) drops below the 0.18 sprint terminal
    // inside the last ~1.1 blocks). That is why the bubble-walled maze cards were green while open water
    // wedged: the ramp was accidentally load-bearing, doing corner-braking duty for a controller whose
    // nominal set-point it could never reach. The rewrite makes the cap achievable everywhere and derives
    // the corner brake from the PLAN's own next leg, so both jobs are covered without asking what is in any
    // cell. See the swim-drive header for the full post-mortem.
    // ================================================================================================

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
        // DECIDE ON THE PROJECTED RESTING HEIGHT, NEVER THE RAW ONE (2026-08-29; the (337,59,414)
        // StartSprintSwim wedge). This was a pure POSITION bang-bang, and SERVO-INVENTORY justified the
        // missing velocity term with "fluid vertical rates (~0.04 b/t) << the 0.4 band". That premise is
        // FALSE and the arithmetic was always available to refute it: 0.04 is the per-tick IMPULSE, not the
        // rate it produces. Under the 0.8 vertical drag it integrates to a terminal 0.04*0.8/0.2 = 0.16 b/t
        // -- and Swim's own cost model has said so all along ("sink one cell: 1/0.185 = 5.41 t/block").
        //
        // At 0.148 b/t (measured) the +-0.2 band is crossed in under three ticks, so the hysteresis bought
        // nothing and the law had no term that could see the momentum it had itself built. Convicted
        // tick-exactly: the bot entered the column at y=62.922 ABOVE a 61.2 set-point, so this method
        // commanded sinkInWater for nine straight ticks (log: jump=false, 62.922 -> 61.529, the exact
        // y > depth+0.2 boundary), coasted the two ticks the band was wide (61.306, 61.128), and only then
        // pressed jump -- by which point it carried 0.148 b/t downward into a 2-block ride to the seabed,
        // where the move's arrival test could never be met and no swim move has a failWhen to end it.
        //
        // The fix is the CLAUDE.md rule for gates applied to the decision variable: ask WHERE THIS COAST
        // ENDS, not where the bot is. A pure-drag coast from velY travels a further velY*q/(1-q) blocks, so
        // `projected` is the height the bot arrives at with no further input -- smooth in the state, and
        // identical to the old law at rest (velY == 0 => projected == y), which is why the settle/station-
        // keep semantics and their pinned band tests are preserved exactly. Moving it makes the controller
        // BRAKE: descending at 0.148 it now presses jump from y ~ 61.5 instead of 61.0, arresting inside the
        // waypoint's own cell instead of overshooting two blocks past it.
        //
        // Still a bang-bang, deliberately: the actuators ARE discrete +-0.04 impulses, so there is no
        // continuous thrust to proportion. The cascade's job here is choosing WHICH impulse, and the
        // projection is what supplies the velocity half of that choice.
        double projected = b.y() + b.velY() * (WATER_DRAG_VERTICAL / (1.0 - WATER_DRAG_VERTICAL));
        if (projected < depth - WATER_RISE_DEADBAND) {
            b.setJumping(true);
        } else if (projected > depth + WATER_RISE_DEADBAND) {
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
     *
     * <p><b>Still live after 2026-08-27</b>, though it is no longer the only term: the HOLD branch now
     * releases unconditionally when {@link BotSteering#seatedFloorBelow} says the arrest is inert (the feet
     * are already over their own floor). This flag continues to decide the case it was written for — a
     * lateral crossing whose catch is up to two cells down.
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
     * input, and the branches mean completely different things by the same numbers: {@code groundServo} (deleted 2026-08-24) faces
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

    /** Append a SECONDARY branch to {@link #lastDrive} instead of replacing it — for helpers that run AFTER
     *  the phase drive and write only ONE input, so the log names the servo that actually steered PLUS the
     *  arm that ran beside it. {@link #climbableDipRecover} writes only jump ({@link #drive} owns
     *  forward/yaw), and its §4 unconditional entry tag was overwriting the drive branch every tick: the
     *  2026-08-24 slowstep investigation read {@code src=hold:dip:dead} on every line and could not tell
     *  which ground servo had steered, which had to be inferred from the yaw instead. Deliberately does NOT
     *  bump {@code driveCalls} — the primary tag's counter stays the staleness oracle. */
    public static void tagAlso(String branch) { lastDrive = lastDrive + "+" + branch; }

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
            //
            // SEATED ON ITS OWN FLOOR — the one release that needs no `translating` (owner ruling 2026-08-27,
            // the ShaftCourse topdown wedge). Reaching this branch means dy == 0, i.e. the feet are already
            // inside the settle band [floorY, floorY + SETTLE_BAND] of the step's OWN target. If a standable
            // sits at exactly footY()-1, then letting go seats the bot at floorY — the base of the cell it is
            // already in — so the arrest is buying nothing at all and costs the edge-guard plus, downstream,
            // the `grounded()` that the navigator's arrival test waits for. Releasing here is not a
            // relaxation of the hold's purpose; it is the hold having no purpose left.
            //
            // seatedFloorBelow(), NOT standableBelow(): the latter spans TWO cells by design, so it is also
            // true for a curtain hang whose catch is a cell BELOW the waypoint — releasing on that answer is
            // exactly the jungle-canopy regression climbLateralTransferKeepsItsHoldOverACanopy pins. The
            // single-cell probe is the only one that means "where I already am".
            //
            // Strictly ADDITIVE: it can only ever remove a sneak press, in the one state where the press is
            // provably inert. Every other combination keeps the byte-identical behaviour above.
            //
            // <p>What it fixes: the last rung of a ladder descent. The bot stops 0.086 above the shaft floor
            // — inside the band, stone directly below — and the old gate pressed sneak forever, pinning it
            // off the floor and starving the arrival test (ShaftCourse topdown-open / topdown-closed, both
            // 600t timeouts).
            //
            // Re-read EVERY tick, as before: these probes ask about the columns the box currently overlaps,
            // so the answer flips by itself as the box crosses a lip — no state, no timers.
            if (!b.seatedFloorBelow() && (!translating || !b.standableBelow())) b.setSneak(true);
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
            // Swim/Surface do: the position-only drive it used to call cannot see a current at all (§6).
            // The servo lifts/sinks it toward the planned cell itself (holdDepthAt, bias 0).
            uprightSwimServo(b, p);
        } else if (stepGateArmed) {
            // GATE-ARMED step — still grounded on the from column of a step whose step-off gate is armed
            // (PhaseRunner plumbs the bit around the phase drive): anchor on the CURRENT step's target
            // centre instead of the pursuit point, so the gate and the drive police ONE lane (the
            // (259,78,448) two-tick thrust/hold limit cycle — see stepGateArmed). The climbable and water
            // branches above keep precedence: the gate's lane law is a LAND rule, and the climbable/swim
            // rulings override everything. Applies to BOTH ground-drive flavors — the lane dispute is not
            // part of the servo/legacy A/B.
            arriveOnStep(b, p);
        } else if (terminalArrive) {
            // §11 CENTERED TERMINAL (owner ruling 2026-08-20) — the terminal move of a seam-truncated
            // plan: ARRIVE on the step's target centre so the move ends settled AT the cell the new
            // plan's step 0 will be framed from (see terminalArrive). Same law as the gate-armed branch;
            // climbable/water precedence identical.
            arriveOnStep(b, p);
        } else if ("servo".equals(GROUND_DRIVE)) {
            // HAZARD → ARRIVE, SAFE → PURSUIT (owner-ratified 2026-08-20, Phase 2 of the servo normalization —
            // the (340,69,481) creep-wedge conviction). The verdict is computed ONCE here and handed to the
            // consumer, so the routing decision and the anchor choice can never be two different answers about
            // one tick. groundServo used to own both modes and its hazard mode was a SPEED SCHEDULE with no
            // position term: on the conviction tick it commanded a half-throttle thrust 123° away from what the
            // position-anchored law wanted, on a bot that was already ON its line. Retiring that branch and
            // routing to arriveOnStep — the same ARRIVE the gate-armed and terminal branches above use, near-face
            // anchor and all — makes ONE law responsible for every hazard-corner tick regardless of which branch
            // brought the bot here.
            // PHASE 3 (owner ruling 2026-08-24): the SAFE branch joins the hazard branch on arriveOnStep,
            // and groundServo is GONE. Phase 2 retired only its hazard mode; the pursuit mode it kept was
            // the same defect wearing different clothes -- it faced the RAW VELOCITY ERROR,
            // faceHorizontally(dv - vel), with a saturated throttle and no position term at all.
            //
            // Convicted on the slowstep cards: walking onto a partial-height slow block the scheduled speed
            // near the segment end falls BELOW the residual velocity, so (dv - vel) flips sign every tick;
            // the bot spun 180 degrees per tick at fwd=1.00 and sat in a +-0.0346 limit cycle at x~1.95,
            // 0.45 short of a target it was driving at full throttle. It never descended the 1/16 onto the
            // block, so the step could never complete.
            //
            // That shortfall is NOT specific to slow blocks -- it is how this servo always arrived. A
            // full-height destination hides it, because there toFootY == ty+1 is already satisfied the
            // instant the bot crosses the cell boundary, so the step completes while the servo is still
            // parked short. The partial top merely made the standing error observable by demanding a real
            // descent. arriveOnStep anchors on the step's target CENTRE (p.tx(), p.tz()) via anchoredServo,
            // whose error is unit(anchor - pos): a facing that cannot flip on residual velocity, and an
            // anchor far enough into the cell that the 0.6-wide box clears the previous block.
            arriveOnStep(b, p);
        } else {
            steerTowards(b, p);           // legacy open-loop walk — the -Dorebit.ground.drive=legacy A/B leg,
                                          // deliberately untouched by the mode switch (it never had a hazard mode)
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
