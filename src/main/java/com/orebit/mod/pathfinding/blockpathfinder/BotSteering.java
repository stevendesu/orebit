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

    /**
     * Whether the bot's FEET block is a climbable — vanilla {@code LivingEntity.onClimbable()} (the
     * feet-cell {@code #climbable} test, incl. the open-trapdoor-above-same-facing-ladder special case).
     * This is the arrest state a hang landing completes in ({@code Fall}'s done predicate) and the
     * climb-steer branches key on (NOTES-movement-physics.md §5). Default {@code false} so the headless
     * test doubles need not implement it; the entity impl needs NO override — the inherited public
     * vanilla method satisfies the seam (a class method wins over an interface default).
     */
    default boolean onClimbable() { return false; }

    /**
     * Whether the bot is <b>settled</b> — supported by SOME medium rather than ballistic: standing, afloat,
     * suspended in lava, or hanging on a climbable. This is the movement-layer spelling of the follower's
     * own plan-anchor rule ({@code BotNavigator}'s {@code planAnchor}, owner ruling 2026-07-30: "never plan
     * while the bot is ballistic; a climbable hang or fluid suspension is a controlled anchor"), kept as one
     * predicate so the two can never drift apart.
     *
     * <p>A waypoint is a STAND cell, so "reached" means <i>supported there</i> — see {@link
     * Movement#reached}. The ballistic case is the one that must never match: a falling bot's feet block
     * transits cells it is merely passing THROUGH.
     *
     * <p>{@link #climbableBelow} (the curtain TOP-OUT stance) is deliberately <b>NOT</b> here, though it is
     * genuinely a held stance. Tried and reverted 2026-08-01: {@code climbableBelow} is true for any bot
     * whose feet cell merely sits above a climbable, which includes a bot in BALLISTIC FLIGHT over a vine.
     * Measured immediately — a Parkour arc at {@code (57,113,160)}, {@code botY=113.905}, airborne, fired
     * {@link Movement#reached} mid-arc ("ABANDONED ... reached fired before done"), advanced the waypoint
     * while still flying, and landed off-plan into a cascade of envelope failures. Widening it here also
     * widens the plan-anchor rule above, which would let the bot re-plan mid-flight — the exact thing that
     * rule exists to forbid. The top-out is accepted by {@link
     * com.orebit.mod.pathfinding.blockpathfinder.movements.Climb#reached} instead, scoped to the one
     * movement that emits such nodes.
     */
    default boolean settled() {
        return grounded() || inWater() || inLava() || hangingOnClimbable();
    }

    /**
     * Arrest threshold (blocks/tick) for {@link #hangingOnClimbable}, separating a HELD hang from a SLIDE.
     *
     * <p><b>Deliberately CONSERVATIVE, and the looser value is measured-and-refuted</b> (2026-08-02). Vanilla
     * clamps a climbable slide to {@code -0.15}/t, so {@code -0.12} looks like the natural separator — a held
     * hang was measured reading {@code -0.0784} (the stored {@code deltaMovement} runs one tick of gravity
     * ahead of the clamp). It is WRONG: the FIRST tick of a genuine fall reads {@code -0.0784} too (the
     * measured free-fall ramp is {@code -0.0784 → -0.1552 → -0.2254}), so at {@code -0.12} a bot that has just
     * started falling counts as settled, {@link Movement#reached} fires mid-fall again, and the flagship bot
     * fell back out of the vine column to {@code y=152} — the exact regression this predicate exists to stop.
     * Velocity alone CANNOT separate "held" from "one tick into a fall"; they are the same number.
     *
     * <p>So this stays tight. The cost is a held hang reading as un-settled, which delays a cursor advance
     * rather than corrupting one — the safe direction. A proper fix needs a signal velocity does not carry
     * (the arrest INPUT actually being held, or a Y-delta across ticks), not a different constant.
     */
    double CLIMBABLE_ARREST_VY = -0.05;

    /**
     * Whether the bot is genuinely <b>HELD</b> by a climbable, as opposed to merely being INSIDE one.
     *
     * <p>This distinction is the whole reason vine curtains wedged the follower (convicted 2026-08-02 on the
     * flagship). {@link #onClimbable} is true for every tick of a FALL through a vine column — vines have no
     * collision and only arrest you when the slide is suppressed — so folding it straight into
     * {@link #settled} made a falling bot report itself supported, which defeated
     * {@link Movement#reached}'s ballistic fly-through guard entirely. Measured cascade: three consecutive
     * {@code Descend} steps each fired {@code reached} mid-fall at {@code botY} {@code 171.922 → 170.922 →
     * 169.922} ({@code grounded=false} throughout), the cursor advanced each time
     * ("{@code ABANDONED … mid-phase 1/2 (reached fired before done)}"), and every step handed the next one an
     * airborne pose until the bot was three blocks below its plan and stuck in the vines at
     * {@code (61,169,253)} — where no stance input could recover it, because the step it was executing had
     * been framed from a cell it only fell past.
     *
     * <p>The discriminator is physical, not a timer: a held hang has {@code vy ≈ 0}, a slide has the vanilla
     * {@code -0.15}/t clamp. Reading velocity keeps this a pure per-tick state question, exactly like the
     * stance servo's other inputs.
     */
    default boolean hangingOnClimbable() {
        return onClimbable() && (sneakHeld() || velY() > CLIMBABLE_ARREST_VY);
    }

    /**
     * Whether the sneak (arrest) input is currently held — the CAUSAL half of {@link #hangingOnClimbable}.
     *
     * <p>Velocity alone cannot answer "am I held or falling", because the first tick of a fall and a settled
     * hang report the same {@code -0.0784} (see {@link #CLIMBABLE_ARREST_VY}). But sneak is not a symptom of
     * the arrest, it IS the arrest: on a climbable, vanilla's {@code isSuppressingSlidingDownLadder()} zeroes
     * the {@code -0.15}/t slide precisely when this input is held. Asking the input therefore separates the
     * two states the velocity number cannot, without loosening the conservative velocity threshold that keeps
     * a genuine fall from counting as settled. Default {@code false} for the headless test doubles.
     */
    default boolean sneakHeld() { return false; }

    /**
     * Whether the block directly UNDER the bot's feet is scaffolding — the one climbable whose top a held
     * SNEAK sinks through (scaffolding is exempt from the climbable sneak-hold), which is exactly how the
     * sink-in step descends a scaffold deck (NOTES-movement-physics.md §4/§5). A ladder plate must NOT
     * sneak there (the vanilla sneak edge-guard would pin the bot ON the plate), so the climb steer needs
     * this one-block discrimination. Live level read (the {@link #solidAt} pattern). Default {@code false}
     * for the test doubles.
     */
    default boolean scaffoldingBelow() { return false; }

    /**
     * Whether the cell directly BELOW the bot's feet is a climbable — the "topped out on a curtain" stance,
     * the complement of {@link #onClimbable} (feet INSIDE one). "Climbable" here is the SAME rule vanilla
     * applies to a feet cell: the {@code #climbable} tag OR the trapdoor-over-equal-facing-ladder special
     * case (DESIGN-trapdoor-ladder-climb.md §1/§5 — a bot topped out above an open mouth is held by it
     * exactly as above a curtain). Owner physics (manual proof, 2026-08-01):
     * this stance is not a stand at all. A vine has no collision, so the bot sinks into the cell, vanilla's
     * climb branch instantly re-lifts it at the surface, and it sinks again — a bounce across the boundary.
     * Holding JUMP is what makes it stable: it does not cancel the sink, it out-runs it by climbing at the
     * surface. Sneak also holds the stance, but sneak's ledge edge-guard would forbid stepping OFF the
     * curtain top, trapping the bot — so the top-out servo uses jump.
     *
     * <p>Distinct from the feet-inside case, where jump CLIMBS and only sneak holds position. See
     * {@link SteerControl#drive}.
     */
    default boolean climbableBelow() { return false; }

    /**
     * Whether a <b>standable surface</b> sits close enough BELOW the bot's feet to arrest a step-off — the
     * discriminator between "hanging on a curtain over nothing" and "clinging to a curtain that merely grows
     * over a floor". Owner ruling 2026-08-02: while moving laterally on a climbable, the sneak stance-hold is
     * for the FIRST case only (arrest our own fall via the climbable); with a floor underneath there is
     * nothing to arrest and the bot must simply walk off.
     *
     * <p><b>The mechanism this guards.</b> Sneak on a climbable does two things at once: it zeroes the
     * {@code −0.15}/t climbable slide (the wanted half, see {@link #setSneak}) and it arms vanilla's ledge
     * edge-guard ({@code Entity/Player.maybeBackOffFromEdge}), which silently DELETES horizontal motion that
     * would carry the bounding box past an unsupported lip (the unwanted half). Convicted 2026-08-02 in
     * jungle vines: the bot froze at {@code (60.289, 171.022, 255.500)} with {@code sneak=true} and
     * {@code hcol=false} on EVERY tick — no horizontal collision at all, so nothing was being pressed
     * against — while a non-zero {@code deltaMovement} was commanded every tick and produced ZERO
     * displacement. It needed {@code x >= 60.300} to clear the overhang and sat pinned {@code 0.011} short
     * of it, forever. One input, both symptoms.
     *
     * <p><b>"Standable" is {@link com.orebit.mod.worldmodel.navblock.NavBlock#isStandable}</b> — the same
     * precomputed floor bit the SEARCH plans its waypoints from (solid-topped, no fluid, and explicitly NOT
     * a narrow top: a ladder plate, a bamboo stalk, a dripstone tip is not a floor, owner ruling
     * 2026-08-01). Deliberately NOT {@link #solidAt}, whose "non-empty collision shape" is true for exactly
     * those non-floors — the class of block that ruling was written against.
     *
     * <p><b>The Y window is TWO cells</b> ({@code footY()-1} and {@code footY()-2}), not the single cell the
     * other {@code *Below()} probes use, and the depth is derived rather than tuned. A bot hanging on a
     * climbable sits LOW inside its feet cell (measured {@code 171.022} in cell {@code 171}), so the first
     * standable cell can be two down while its top FACE is barely a block below the feet: in the convicted
     * stall {@code (60,170,255)} was a second vine and the leaves that actually hold the bot were at
     * {@code (60,169,255)}, top face {@code y=170.0} — a {@code 1.02} block step-off. A standable at
     * {@code footY()-1} is a drop of at most {@code 1.0}; one at {@code footY()-2} at most {@code 2.0} —
     * both inside vanilla's {@code 3.0} damage-free fall, so everything this window sees is a step-off the
     * bot can take unaided. Anything DEEPER is a genuine hang over a drop, which is precisely what the sneak
     * arrest exists for, so the window stops there.
     *
     * <p>Live level read (the {@link #solidAt} pattern), so it reflects the bot's own just-made breaks and
     * places. Default {@code false} for the test doubles — the conservative answer, since it leaves the
     * pre-existing sneak stance-hold untouched for every double that does not opt in.
     */
    /**
     * Whether vanilla registered a HORIZONTAL COLLISION on the last movement attempt — i.e. the bot pressed
     * into something and was stopped by it.
     *
     * <p>This is the one fact that distinguishes "walking" from "grinding against a wall", and on a
     * climbable it is not cosmetic: vanilla turns {@code (horizontalCollision || jumping) && onClimbable}
     * into {@code vy = +0.2}, so a blocked press does not merely fail to advance — it converts the whole
     * input into ALTITUDE. Velocity cannot substitute for it: a bot pressing full-forward into a wall reads
     * {@code deltaMovement} horizontal ~0, which is indistinguishable from standing still.
     *
     * <p>Default {@code false} keeps every headless fake honest (no collisions in a synthetic world).
     */
    default boolean horizontalCollision() { return false; }

    default boolean standableBelow() { return false; }

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

    /**
     * Set the LATERAL movement input ({@code xxa}); {@code +1} = full strafe LEFT of the current facing,
     * {@code −1} = full right, {@code 0} = none. The sideways counterpart of {@link #setForward}, so a servo
     * can correct cross-track error <b>without yawing</b>.
     *
     * <p><b>Why it exists</b> (owner ruling 2026-08-06). Every movement steers by facing where it wants to go
     * and holding forward, so lateral error could only ever be corrected by turning — which spins the bot
     * whenever the target ends up behind it, and, worse, cannot be done at all by a servo that must hold a
     * fixed heading. Measured consequence: {@link SteerControl#arriveOnTarget} pinned its heading to the step
     * segment to stop the pirouette, and the uncorrected cross-axis velocity then integrated over a whole
     * 3-block fall into {@code +0.344} of drift — turning a {@code 0.001} X error into {@code 0.199} and
     * eating most of the along-axis win.
     *
     * <p><b>Sign convention</b> is vanilla's: {@code getInputVector} maps {@code (xxa, _, zza)} through the
     * yaw, so pure {@code zza} follows the look vector {@code (−sin θ, cos θ)} and pure {@code xxa} follows
     * {@code (cos θ, sin θ)} — the look vector rotated −90°, i.e. the mover's LEFT. For a unit heading
     * {@code h = (hx, hz)} the positive-strafe direction is therefore {@code (hz, −hx)}.
     *
     * <p><b>Thrust is shared, not added.</b> {@code getInputVector} normalizes the input when its length
     * exceeds 1, so {@code (forward 1, strafe 1)} is a unit diagonal, not √2 of thrust — a lateral correction
     * necessarily spends some of the forward budget. Callers should therefore scale the {@code (forward,
     * strafe)} pair as a VECTOR rather than clamping each component independently, or the commanded direction
     * is silently distorted at saturation.
     *
     * <p>Reset to {@code 0} each tick by the entity layer alongside {@link #setForward}/{@link #setJumping}
     * (see {@code AllyBotEntity.tick}), so a move must re-assert it; sneak scaling already applies to it. A
     * {@code default} no-op so the headless test doubles need not implement it — a double that ignores strafe
     * simply behaves as it did before.
     */
    default void setStrafe(float xxa) { }

    void setSprinting(boolean sprinting);

    /**
     * Hold/release the jump input. Vanilla {@code aiStep} turns a held jump into a ground jump on land and a
     * buoyant swim-UP in water (and a no-op in the air), so this single input is how every climb works in both
     * media — an Ascend/Pillar step, and the follower's water-rise.
     */
    void setJumping(boolean jumping);

    /**
     * Hold/release the sneak (crouch) input — vanilla {@code Entity.setShiftKeyDown}. On a climbable this is
     * the height-hold input: {@code Player.isSuppressingSlidingDownLadder()} (== {@code isShiftKeyDown()})
     * zeroes the {@code −0.15}/t slide in {@code LivingEntity.handleOnClimbable}, so a sneaking bot holds its
     * position on a vine/ladder while it eases sideways ({@link
     * com.orebit.mod.pathfinding.blockpathfinder.movements.Climb}'s lateral grab). Reset to {@code false}
     * each tick by the follower, like {@link #setJumping}/{@link #setSprinting}, so a move must re-assert it.
     */
    void setSneak(boolean sneaking);

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

    /**
     * Whether the block at {@code (x,y,z)} genuinely OBSTRUCTS the bot's body moving through the cell — a
     * geometry test (does the live collision shape intrude into the bot's centred body corridor above the
     * auto-step height), NOT the blunt "has any collision" of {@link #solidAt}. The {@code Need.AIR} reconcile
     * (PhaseRunner) mines a body cell only when THIS is true, so the bot walks through an already-open door /
     * open trapdoor (thin edge panel) and stands on a carpet / plate / slab in its feet cell (collision below
     * the step) instead of swinging at them, while a real full-block obstruction is still cleared. General by
     * construction — no per-block-type ("is it a door") special-casing. {@code (dx,dz)} is the step's horizontal
     * movement direction (signum or raw delta; {@code (0,0)} = a vertical move): the corridor spans the full cell
     * ALONG it and only the centred band ACROSS it, so a panel across the path (a closed door) is caught while
     * one along a side (an open door) is not. Reads the LIVE level like {@link #solidAt}. See the impl ({@code
     * AllyBotEntity.movementBlockedAt}) for the corridor geometry.
     */
    boolean movementBlockedAt(int x, int y, int z, int dx, int dz);

    /** Ask to mine the block at {@code (x,y,z)} this tick — routed to the timed {@link com.orebit.mod.BotMining}
     *  actuator (equip tool, face, swing, real destroy time, drops). Reactive: call it every tick the cell must
     *  go; stopping the calls aborts the break. */
    void mine(int x, int y, int z);

    /** Place a footing block at {@code (x,y,z)} — the bot's configured/conjured or carried block, server-side,
     *  the instant a {@code Need.FOOTING} is unmet and the cell is placeable. */
    void place(int x, int y, int z);

    /**
     * Place the {@link ClutchModel} block of {@code kind} into cell {@code (x,y,z)}, consuming the matching
     * CARRIED item — the mid-drop clutch action ({@code ClutchModel}'s class doc; {@link
     * com.orebit.mod.pathfinding.blockpathfinder.movements.Fall}'s deep-drop branch). Distinct from
     * {@link #place}, which places the bot's generic footing block and cannot be told WHAT to place: a clutch
     * is a specific block chosen by the planner, and two of the five kinds (water, powder snow) are not
     * {@code BlockItem}s at all, so they can never come out of the footing path's placeable-block budget.
     *
     * <p>Returns {@code false} — placing nothing and consuming nothing — when the bot is not carrying the
     * kind's item, when the cell will not accept the placement, or when the executor does not support the
     * kind (see the impl's {@link ClutchModel#BED} refusal). A {@code false} is cheap and side-effect-free, so
     * the caller may simply re-issue it on the next tick; reach is never the reason (interaction reach is 4.5
     * blocks and air terminal velocity is 3.92 b/t, so the landing cell is inside reach for at least one whole
     * tick of any drop — attempting the place every tick is provably sufficient).
     *
     * <p>Default {@code false} so the headless test doubles need not implement it — a double that ignores
     * clutches simply never gets one placed, which is the conservative direction.
     */
    default boolean placeClutch(int x, int y, int z, int kind) { return false; }

    /**
     * Remove the {@link ClutchModel} block of {@code kind} from cell {@code (x,y,z)} and hand the item back —
     * the reclaim half of {@link #placeClutch}, and the reason a clutch is priced as TIME rather than as a
     * consumed resource ({@link ClutchModel#reclaimable} is true for all five kinds).
     *
     * <p>Returns {@code false} — mutating nothing — when the cell no longer holds the expected block, or when
     * the bot has nowhere to put the returned item (a full inventory, or no empty bucket to scoop into). Both
     * refusals leave the world untouched, so nothing is ever destroyed to make room.
     *
     * <p>Default {@code false} for the headless test doubles, matching {@link #placeClutch}.
     */
    default boolean reclaimClutch(int x, int y, int z, int kind) { return false; }

    /**
     * OPEN ({@code open == true}) or CLOSE the hand-toggleable door at cell {@code (x,y,z)} server-side — the
     * "right-click the door" action a {@code Need.OPEN} establishes (DOORS P3), routed to {@link
     * com.orebit.mod.platform.WorldEdits#setDoorOpen}. Authoritative and instant (a direct vanilla {@code
     * setOpen}, not a simulated click), like {@link #place}; iron / non-door cells are no-ops (see WorldEdits).
     * The entity layer may cosmetically face the door but never swings. Reactive: the runner re-issues it while
     * the live door does not yet read the target state.
     */
    void setDoorOpen(int x, int y, int z, boolean open);

    /**
     * OPEN ({@code open == true}) or CLOSE the hand-toggleable trapdoor at cell {@code (x,y,z)} server-side —
     * the trapdoor twin of {@link #setDoorOpen} (DESIGN-trapdoors.md §7), routed to {@link
     * com.orebit.mod.platform.WorldEdits#setTrapdoorOpen}. Authoritative and instant (a direct property
     * write — {@code TrapDoorBlock} has no vanilla {@code setOpen} to call); iron / non-trapdoor cells are
     * no-ops (see WorldEdits). The entity layer may cosmetically face the trapdoor but never swings.
     * Reactive like the door verb: the runner re-issues it while the live trapdoor does not read the target
     * state ({@link #doorOpenAt}, which covers trapdoors too — shared {@code OPEN} property).
     *
     * <p>A {@code default} no-op — the {@link #placeClutch} pattern, NOT the abstract {@link #setDoorOpen}
     * shape — so the many existing headless test doubles need no edits: a double that ignores trapdoors
     * simply never satisfies a trapdoor req, which the runner surfaces as a visible hold (the conservative
     * direction). Doubles under trapdoor test override it.
     */
    default void setTrapdoorOpen(int x, int y, int z, boolean open) { }

    /**
     * OPEN ({@code open == true}) or CLOSE the hand-toggleable fence gate at cell {@code (x,y,z)}
     * server-side — the gate member of the {@link #setDoorOpen}/{@link #setTrapdoorOpen} verb family
     * (DESIGN-fence-gates.md §4), routed to {@link com.orebit.mod.platform.WorldEdits#setGateOpen}.
     * Authoritative and instant (a direct property write — {@code FenceGateBlock}, like {@code
     * TrapDoorBlock}, has no vanilla {@code setOpen} at any version); non-gate cells are no-ops (see
     * WorldEdits — and there is NO iron refusal, since no iron gate exists). The entity layer may
     * cosmetically face the gate but never swings. Reactive like the door verb: the runner re-issues it
     * while the live gate does not read the target state ({@link #doorOpenAt}, which covers gates too —
     * shared {@code OPEN} property).
     *
     * <p>A {@code default} no-op — the {@link #setTrapdoorOpen} pattern — so the many existing headless
     * test doubles need no edits: a double that ignores gates simply never satisfies a gate req, which
     * the runner surfaces as a visible hold (the conservative direction). Doubles under gate test
     * override it.
     */
    default void setGateOpen(int x, int y, int z, boolean open) { }

    /**
     * Whether the <b>openable</b> (door, trapdoor OR fence gate) at cell {@code (x,y,z)} is currently OPEN
     * — its live {@code OPEN} block-state property, which {@code DoorBlock}, {@code TrapDoorBlock} and
     * {@code FenceGateBlock} share ({@code BlockStateProperties.OPEN}) — the SET executor's gate and
     * verify-readback (DOORS P3; trapdoors DESIGN-trapdoors.md §7 and gates DESIGN-fence-gates.md §4 ride
     * the same read, so the name keeps its door heritage). It reads the OPEN <b>block-state property</b>,
     * NOT {@link #solidAt}: an open door keeps a thin (~3px) collision box (an open trapdoor keeps its
     * wall panel), so {@code solidAt} stays TRUE even when open — testing solidness would make a {@code
     * Need.OPEN} wrongly MINE the cell. Non-openable cells read {@code false}. Reads the live level, so it
     * reflects the bot's own just-issued {@link #setDoorOpen}/{@link #setTrapdoorOpen}/{@link
     * #setGateOpen}.
     */
    boolean doorOpenAt(int x, int y, int z);

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
     * Whether the block at {@code (x,y,z)} is an <b>UP bubble column</b> (a {@code BUBBLE_COLUMN} with
     * {@code DRAG_DOWN=false} — a soul-sand column whose conveyor pushes an entity upward). The
     * {@link com.orebit.mod.pathfinding.blockpathfinder.movements.RideBubbleColumn} follower uses it as a
     * pure STATE probe (no timers): the ENTER phase scans the bot's feet-level cardinal neighbours for the
     * up-column to swim into, and the RIDE phase tests the bot's own feet cell to know it is still being
     * carried (leaving the column — {@code false} at the feet — ends the ride). Reads the LIVE level (a cold,
     * tick-rate follower read, the {@link #swimHazardAt}/{@link #slipperinessAt} pattern; distinct from that
     * hazard probe, which fires for DOWN columns and lava too). DOWN (magma) columns read {@code false} — the
     * ride is UP-only.
     */
    boolean bubbleUpAt(int x, int y, int z);

    /**
     * The <b>fluid surface height</b> in cell {@code (x,y,z)}, in blocks above the cell's base — vanilla's
     * {@code FluidState.getHeight}, which reports {@code 1.0} for any cell whose neighbour ABOVE holds the
     * same fluid and the fluid's own partial height otherwise. Dry cells read {@code 0.0}.
     *
     * <p>Read by {@code Swim.reachedSwim} to bound the ride height a swim waypoint can actually be held at.
     * The swim reach model assumes a buoyant bot rises until it breaches, i.e. settles a full block above its
     * feet cell's base; a PARTIAL top cell makes that hydrostatically unreachable, exactly as a ceiling makes
     * it geometrically unreachable. Live read, the {@link #swimHazardAt}/{@link #slipperinessAt} pattern.
     *
     * <p>The default is {@code 1.0} — "assume a full column" — so every existing caller and test double is
     * byte-identical without implementing it, and the clamp it feeds can only ever be a no-op.
     */
    default double fluidTopAt(int x, int y, int z) { return 1.0; }

    /**
     * The collision top surface (SIXTEENTHS) of the block at {@code (x,y,z)} as seen from a body travelling
     * {@code (dx,dz)} — the live-world twin of {@code MovementContext.directionalTopY}, which it delegates to
     * so there is exactly one copy of the rule.
     *
     * <p>Only a BOTTOM stair makes this direction-dependent: its raised step occupies the half on its FACING
     * side, so crossing TOWARD the facing reads {@code 16} and crossing away reads {@code 8}. Everything else
     * reads its plain {@code topY}. {@code Parkour}'s run-up needs this live, because the tall half ends at
     * the cell CENTRE and its launch point is measured from there — see the run-up derivation in
     * {@code Parkour.plan}.
     *
     * <p>Default {@code 16} ("assume a full block") so every existing implementation and test double is
     * byte-identical without implementing it.
     */
    default int surfaceTopYToward(int x, int y, int z, int dx, int dz) { return 16; }

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
