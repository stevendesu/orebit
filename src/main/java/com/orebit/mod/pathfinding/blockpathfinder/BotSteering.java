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

    /** Aim the body + head yaw along a horizontal delta (folds the {@code atan2} the follower used to repeat). */
    void faceHorizontally(double dx, double dz);

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
}
