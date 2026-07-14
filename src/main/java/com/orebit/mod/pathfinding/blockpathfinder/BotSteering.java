package com.orebit.mod.pathfinding.blockpathfinder;

/**
 * The thin, MC-type-free seam a {@link Movement} steers the bot through during execution (MOVEMENT-DESIGN.md
 * §1, the cold counterpart to {@link Movement#candidates}). The follower runs a movement's
 * {@link Movement#steer}/{@link Movement#reached}/{@link Movement#editsReadyNow} hooks once per tick to turn
 * the planned waypoint into player inputs; those hooks call back through THIS interface to read the bot's
 * pose/velocity and set its movement inputs.
 *
 * <p><b>Why a seam, and why primitives only.</b> Steering is a <i>cold</i> (tick-rate) concern, so it can be
 * polymorphic for clean code (the no-polymorphism rule is hot-path-only — see the block A* loop, which is
 * already a per-node virtual dispatch over movements). But the {@code movements/} classes are deliberately
 * free of {@code net.minecraft} types so they stay headless-testable and version-portable. Exposing only
 * doubles/floats/ints/booleans here keeps that property: the entity layer ({@link
 * com.orebit.mod.AllyBotEntity}) implements this against its {@code ServerPlayer} ops, and no MC type
 * (e.g. {@code Vec3}, {@code BlockPos}) ever leaks into a movement.
 *
 * <p>Positions are entity-space doubles (block centres are {@code x+0.5}); {@link #footX}/{@link #footY}/
 * {@link #footZ} are the bot's feet <i>block</i> (its {@code blockPosition()}), used by the cursor-advance
 * {@link Movement#reached} test. Velocities wrap {@code getDeltaMovement}/{@code setDeltaMovement}; the
 * {@code set*Input} methods set the forward/vertical movement inputs ({@code zza}/{@code yya}); {@link #jump}
 * is a discrete ground jump ({@code jumpFromGround}).
 */
public interface BotSteering {

    double x();
    double y();
    double z();

    /**
     * The bot's current velocity ({@code getDeltaMovement}) components, entity-space blocks/tick — the
     * feedback signal for the velocity servo ({@link SteerControl#swimServo}). The input-based follower never
     * WRITES velocity (that is vanilla physics' job); these are read-only so a move can steer against the bot's
     * ACTUAL momentum (thrust to hold speed against drag, reverse-thrust to brake an overshoot) instead of
     * open-loop guessing from position alone.
     */
    double velX();
    double velY();
    double velZ();

    /** The bot's feet block X (its {@code blockPosition().getX()}) — for the {@link Movement#reached} test. */
    int footX();
    int footY();
    int footZ();

    /**
     * Whether the bot is standing on the ground. Named {@code grounded} (not {@code onGround}) deliberately:
     * MC {@code Entity} already has an {@code onGround()}/{@code isOnGround()} accessor whose name drifts
     * across versions and which the {@code EntityState} platform adapter wraps — a seam method named
     * {@code onGround()} would collide with (and recurse through) that adapter.
     */
    boolean grounded();

    /**
     * Whether the bot is in water (vanilla {@code isInWater}). The follower uses it for the cross-cutting
     * water rule: while submerged and below the planned depth, hold {@link #setJumping jump} so vanilla
     * buoyancy swims the bot up — the input-based way it rises, surfaces, and climbs out onto a bank.
     */
    boolean inWater();

    /** Whether the bot's body is in lava — the lava analog of {@link #inWater} ({@code Entity.isInLava}).
     *  Gates the {@link SteerControl#holdDepth} fluid autopilot for lava swimming (s52b hazard-media). */
    boolean inLava();

    /** Aim the body + head yaw along a horizontal delta (folds the {@code atan2} the follower used to repeat). */
    void faceHorizontally(double dx, double dz);

    /** Whether the bot is in the prone swimming pose (vanilla isSwimming() / Pose.SWIMMING) — the gate a
     *  dive-init / surface transition holds on until the pose has actually flipped. */
    boolean prone();

    /** Aim yaw AND pitch toward a 3-D direction (prone sprint-swim: vanilla steers vertical velocity toward the
     *  look-pitch via Player.travel). If the horizontal component is ~0, KEEP the current yaw (only set pitch) so
     *  a vertical dive/rise doesn't spin the head. */
    void faceTowards(double dx, double dy, double dz);

    /** Set the forward movement input ({@code zza}); {@code 1} = full ahead, {@code 0} = none. */
    void setForward(float zza);

    void setSprinting(boolean sprinting);

    /**
     * Hold/release the jump input. Vanilla {@code aiStep} turns a held jump into a ground jump on land and a
     * buoyant swim-UP in water (and a no-op in the air), so this single input is how every climb works in both
     * media — an Ascend/Pillar step, and the follower's water-rise.
     */
    void setJumping(boolean jumping);

    /**
     * Sink in water — the descend half of the water vertical control, the effect of <b>holding shift</b>.
     * Vanilla applies the rise half ({@code jumpInLiquid}, {@code +0.04}) from the shared {@code LivingEntity}
     * tick (so {@link #setJumping} works), but the sink half ({@code LocalPlayer.goDownInWater}, {@code -0.04})
     * lives in the CLIENT tick a headless bot doesn't run — so the bot replicates it: subtract the same
     * {@code 0.04} from its vertical velocity. Called per tick by the follower while above the planned depth.
     */
    void sinkInWater();

    // ---- Live-world geometry + block actions (the reconcile seam a MovePlan drives through) -----------
    // A phase plan re-checks the LIVE world each tick and establishes its required geometry, so these read the
    // current level (not the nav grid) and act as a player does. Primitives only, so movements + the runner
    // stay MC-free and headless-testable; the entity layer backs them with real reads / the mining + placing
    // actuators.

    /** Whether the world cell {@code (x,y,z)} is currently a solid (movement-blocking) block — the live test a
     *  {@code Need.AIR} requirement clears (mine while true) and a {@code Need.FOOTING} requirement wants (place
     *  while false). Reads the live level, so it reflects the bot's own just-made breaks/places. */
    boolean solidAt(int x, int y, int z);

    /** Whether the world cell {@code (x,y,z)} is currently passable (air / non-blocking) — the complement of
     *  {@link #solidAt} exposed for guards/plans that read positively. */
    boolean airAt(int x, int y, int z);

    /** Ask to mine the block at {@code (x,y,z)} this tick — routed to the timed {@link com.orebit.mod.BotMining}
     *  actuator (equip tool, face, swing, real destroy time, drops). Reactive: call it every tick the cell must
     *  go; stopping the calls aborts the break. */
    void mine(int x, int y, int z);

    /** Place a footing block at {@code (x,y,z)} — the bot's configured/conjured or carried block, server-side,
     *  the instant a {@code Need.FOOTING} is unmet and the cell is placeable. */
    void place(int x, int y, int z);

    /**
     * Whether cell {@code (x,y,z)} is a swim <b>overshoot hazard</b> — a cell the follower must not let its
     * momentum carry the prone bot into while cornering: a <b>bubble column</b> (whose vertical drag breaches
     * a prone swimmer out of the water and ejects it) or <b>lava</b>/damaging fluid. Reads the LIVE level (the
     * follower runs cold, at tick rate, with a large per-decision budget — unlike the pathfinder — so a handful
     * of live block reads around the path each corner is affordable). This is the seam the hazard-aware corner
     * brake ({@link SteerControl}) uses to decide whether a given turn's overshoot is DANGEROUS (brake to a
     * crawl) or harmless (take the corner at full speed).
     */
    boolean swimHazardAt(int x, int y, int z);

    /**
     * The vanilla surface FRICTION (slipperiness) of the block at {@code (x,y,z)} — {@code 0.6} for ordinary
     * ground, {@code 0.98} for ice / packed ice / frosted ice, {@code 0.989} for blue ice, {@code 0.8} for
     * slime. This is the same per-block factor vanilla reads from the block a mover stands ON to size its
     * horizontal drag, so a HIGH value ({@code >= 0.98}) means the bot cannot brake its carried momentum once
     * it touches down. The parkour predictive-airborne servo ({@link SteerControl#parkourAirborne}) reads the
     * LANDING block's slipperiness to decide how early/hard to air-brake and where in the cell to aim (center
     * + arrive-slow on ice; a moderate landing speed is fine on stone). Reads the LIVE level — a cold,
     * tick-rate follower read, the {@link #swimHazardAt} pattern. (Direct {@code Block.getFriction()} call;
     * range-stable on the mojmap ≥1.17 API — see the portability note on the impl.)
     */
    double slipperinessAt(int x, int y, int z);

    /**
     * Whether the block at {@code (x,y,z)} is a TAKEOFF hazard the bot must not be grounded over — either
     * DAMAGING on contact (magma block / lava, which {@code hurt}s an entity standing on it) or
     * JUMP-SUPPRESSING (honey, whose {@code jumpFactor < 1} weakens a jump launched from over it). The
     * parkour takeoff-timing fix ({@code Parkour}/{@code DiagonalParkour} runup) probes the FIRST gap-floor
     * cell just past the takeoff lip with this and, when it is hazardous, fires the jump EARLY so the bot's
     * center never crosses onto that block on a grounded tick (magma damage is center-based + requires
     * {@code onGround}; honey's {@code getBlockJumpFactor} reads the block under the center at launch). Reads
     * the LIVE level — a cold, tick-rate follower read, the {@link #swimHazardAt}/{@link #slipperinessAt}
     * pattern. Deliberately EXCLUDES slow-but-safe floors (soul sand): those neither damage nor suppress the
     * jump, so an early takeoff over them would only cost reach for nothing.
     */
    boolean gapFloorHazardAt(int x, int y, int z);
}
