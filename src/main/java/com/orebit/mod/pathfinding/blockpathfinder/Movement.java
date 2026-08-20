package com.orebit.mod.pathfinding.blockpathfinder;

/**
 * One kind of block-tier move the bot can make (walk, jump-up, drop, …) — the Strategy the block A*
 * expands a node with (MOVEMENT-DESIGN.md §1). Given a stand position (a <b>floor cell</b>, the block
 * the bot stands on), a movement reads the geometry of the cells it touches and emits every valid
 * destination floor cell reachable by <i>this</i> move, each with its tick cost.
 *
 * <p><b>Why movements, not block flags.</b> A move spans multiple cells — an ascend reads the source
 * head-clearance cell, the destination floor, and the destination body space; a (future) parkour gap
 * reads the takeoff, the air over the gap, and the landing. No single block can answer "is this move
 * valid," so the rule lives in the movement. Each movement is a stateless singleton in {@link
 * MovementRegistry}; adding a capability is adding a class, never editing an existing one (so the
 * search's correctness for moves already shipped can't regress when a new one lands).
 *
 * <h2>The two-resolution interplay</h2>
 * A movement gates on "is this cell's nav data loaded", then uses {@link MovementContext#descriptorAt}-
 * derived predicates ({@link MovementContext#standable}, {@link MovementContext#passable}) for the
 * <i>precise</i> per-cell checks. The coarse grid finds candidates; live geometry decides whether the move
 * actually works — which is what fixes the "head-in-block" class of bug precisely at the move level rather
 * than approximating it in the grid.
 *
 * <p><b>How that gate is actually spelled</b> (corrected 2026-08-11 — this said "{@link
 * MovementContext#built} (the cached 2-bit grid)", and there is no 2-bit grid: that was the deleted
 * {@code TraversalClass}. A {@link com.orebit.mod.worldmodel.pathing.TraversalGrid} cell is a packed
 * {@code short} = 6 flag bits + a 10-bit navtype, and {@code built} is a section-presence test, not a
 * per-cell value.) <b>No Tier-1 GROUND movement calls {@code built} any more</b> — they resolve the slot
 * once with {@link MovementContext#packedAt} and compare against {@link MovementContext#UNBUILT}, which
 * folds the loaded-gate, the flags and the navtype into ONE section resolve. Only the fluid family and
 * {@code RideBubbleColumn} still pay the older {@code built} + {@code descriptorAt} pair, at two resolves
 * per cell. Prefer {@code packedAt}/{@link MovementContext#descriptorOf} in new movements.
 */
public interface Movement {

    /**
     * Emit every destination floor cell reachable from floor cell {@code (x,y,z)} by this movement,
     * with its tick cost, into {@code out}. Implementations must be pure (no state) and must validate
     * each candidate against {@code ctx} so the cost and validity are identical at planning and
     * execution time.
     */
    void candidates(MovementContext ctx, int x, int y, int z, CandidateSink out);

    // ---- Execution (cold, tick-rate) — the follower drives these once per tick -----------------------
    // These three default methods make a movement own how the bot EXECUTES it, the way candidates() owns
    // how the search PLANS it (MOVEMENT-DESIGN.md §1). They run at tick rate (the follower), not on the A*
    // hot path, so virtual dispatch here is fine — the no-polymorphism rule is hot-path-only. The defaults
    // reproduce a plain ground walk, so a move with no special execution (Traverse/Diagonal/Descend/
    // MineDown) needs no override; only moves with special inputs (Ascend/Pillar jump, Fall homing, Swim
    // vertical control) override. All callbacks go through the MC-free {@link BotSteering} seam.

    /**
     * Whether the bot's feet block {@code (b.footX,footY,footZ)} has reached waypoint {@code (wx,wy,wz)} —
     * the follower's cursor-advance test. Default is an exact block match (waypoints and feet are both
     * blocks, so this is block-exact, no distance epsilon), gated on the bot being {@link
     * BotSteering#settled settled}: a waypoint is a STAND cell, so reaching it means being SUPPORTED there
     * — grounded, afloat, lava-suspended, or hanging on a climbable — never merely transiting it while
     * ballistic.
     *
     * <p><b>Why settled and not grounded.</b> The gate must admit every medium a step legitimately ends in:
     * {@link com.orebit.mod.pathfinding.blockpathfinder.movements.Fall Fall}'s landing may be buoyant water
     * (never grounded) or an arrested climbable hang, and the swim family arrives afloat. {@code settled}
     * is the same predicate the follower already uses to decide it may plan at all (BotNavigator's
     * {@code planAnchor}), so arrival and planning agree on what "not ballistic" means.
     *
     * <p><b>What it kills</b> — the airborne FLY-THROUGH match: a falling bot's feet block transits a
     * waypoint stand cell mid-arc, the cursor silently advances onto a step the bot never stood at, and the
     * settle anchor is poisoned to a cell it flew past. This was originally gated for {@link
     * #commitsAcrossArrival committed} moves only (the DiagonalParkour off-plan wedge), which left the
     * REVERSIBLE moves exposed: convicted 2026-08-01 on the flagship, where a {@code Fall} down column
     * (44,*,243) transited the stand cells of the two {@code Descend} steps below it, the cursor jumped
     * 5→7 ("advance SKIPPED 1 step(s)" — step 6's edits never ran), and the step-7 Descend was framed from
     * a cell the bot had only fallen past, ending in a permanent fail→HOLD at (44,149,244). The gate delays
     * a legitimate advance by zero ticks in every medium, because arrival IS support.
     *
     * <h2>Arrival means the NEXT move is teed up (owner ruling, 2026-08-15)</h2>
     * {@code reached} is not "my movement finished" — it is "the bot is positioned and aligned for what comes
     * next". So it takes {@code next}, the movement that will execute FROM this waypoint ({@code null} at the
     * end of the plan), and asks it whether this pose is an acceptable entry via {@link #entryReady}.
     *
     * <p>This exists because arrival tests kept omitting a dimension and the error surfaced one step later, on
     * whatever move DID check it. Every such omission has been in the vertical: five of the eight overrides
     * advanced the cursor without ever testing the feet cell's Y, and all five were fluid moves — precisely
     * the moves that can leave the bot at an unconstrained height. Asking the successor closes them as a class
     * instead of one patch at a time.
     *
     * <p>Byte-identical wherever the current move's own test already implies {@link #atWaypoint} (the default,
     * {@code Ascend}, {@code Climb}, {@code Pillar}, and — since the {@code SWIM_RIDE} change — {@code Swim},
     * {@code SprintSwim} and {@code EndSprintSwim}): the clause simply re-asks a question already answered.
     * It bites on the two that still omit Y, {@code StartSprintSwim} and {@code RideBubbleColumn}, and
     * only when a GROUND move follows — a fluid successor declares itself permissive because its own servo
     * establishes its entry. Since the delivery invariant (owner-ratified 2026-08-20) the default
     * {@code entryReady} additionally asks {@link #deliverable}, so a ground successor also refuses a
     * block-exact arrival whose one-tick velocity projection has already left the cell — see there.
     */
    default boolean reached(BotSteering b, int wx, int wy, int wz, Movement next) {
        return b.settled() && atWaypoint(b, wx, wy, wz) && teedUp(b, wx, wy, wz, next);
    }

    /**
     * The shared handoff clause: the successor accepts this pose as its entry. No successor (the plan's last
     * waypoint) means there is nothing to tee up, so the arrival stands on the current move's test alone.
     */
    static boolean teedUp(BotSteering b, int wx, int wy, int wz, Movement next) {
        return next == null || next.entryReady(b, wx, wy, wz);
    }

    /**
     * The <b>delivery invariant</b> (owner-ratified 2026-08-20): a grounded pose is a delivered entry for
     * cell {@code (wx,·,wz)} only if a ONE-TICK velocity projection still lands in that cell. Owner
     * philosophy — no envelope margins, invariants instead: "if the bot needs to be in a particular
     * position, the prior move should have delivered the bot to that position." This is that precondition,
     * asked once, at the handoff.
     *
     * <p><b>The conviction</b> (ReplanCourse run-2 forensic, 2026-08-20): a Traverse completed at
     * {@code z=516.007} with {@code velZ=-0.015} — block-exact and grounded, so the cell-only
     * {@code entryReady} accepted it and the cursor advanced — and vanilla drag carried the bot to
     * {@code z=515.992} on the very next tick, the FIRST tick the successor's {@code failWhen} ever
     * evaluated. The successor executed from outside the frame its whole plan was built on and the
     * validity envelope fail→HELD it one tick after a "clean" arrival.
     *
     * <p><b>Why exactly one tick of projection, not a coast horizon.</b> {@code entryReady} answers "will
     * the bot still be in this cell when the successor's {@code failWhen} is FIRST evaluated" — and that
     * is exactly one tick away: the advance tick builds the successor's plan and begins it, the next
     * steer tick runs it with {@code failWhen} first ({@code PhaseRunner}'s order), and physics moves the
     * bot in between — the run-2 forensic to the tick. A full GROUND_COAST horizon would refuse ~any
     * half-cell arrival (walk cruise 0.216 coasts ~0.26 blocks) — wrong: the full-coast question is
     * answered downstream by {@code stepOffGate}/{@code arrestCarryFrom}, which this invariant buys its
     * one tick ({@code carryUncontained} is checked AFTER {@code failWhen} in the runner). Same shape as
     * {@code SteerControl.settleIntoBand}'s {@code y + velY}: the stored post-drag deltas, no drag
     * constant, no margin constant.
     *
     * <p><b>The {@code grounded()} gate is load-bearing</b>: a floating bot ALWAYS carries velocity
     * (buoyant bob), and {@code Surface}/{@code DiagonalSprintSwim} are fluid moves with NO permissive
     * {@code entryReady} override — an ungated projection clause would newly bind them. Fluid/ballistic
     * media own their own entry (each swim servo drives to its line from wherever it starts), mirroring
     * {@link #atWaypoint}'s own medium exemption.
     */
    static boolean deliverable(BotSteering b, int wx, int wz) {
        return !b.grounded()                                   // fluid/ballistic: the medium owns entry
                || ((int) Math.floor(b.x() + b.velX()) == wx
                 && (int) Math.floor(b.z() + b.velZ()) == wz);
    }

    /**
     * Would this movement accept the bot's CURRENT pose as its starting stance, standing at feet cell
     * {@code (wx,wy,wz)}? Asked of the SUCCESSOR by {@link #reached}, never of the move itself.
     *
     * <p>The default is {@link #atWaypoint} <b>and {@link #deliverable}</b> (the delivery invariant,
     * owner-ratified 2026-08-20) — a ground move's whole plan is framed on that feet cell, so being
     * anywhere else is not an entry, and a pose one tick from LEAVING the cell is not an entry either.
     * Moves whose servo establishes its own entry override this to be permissive: the swim family (which
     * drives to depth regardless of where it starts, including from a dry lip) and
     * {@code RideBubbleColumn} (a conveyor — requiring a particular cell to board it would wedge the
     * very moves it exists for). Permissive was also exactly the pre-gate behaviour, since no entry test
     * existed at all before this, so those moves cannot regress.
     */
    default boolean entryReady(BotSteering b, int wx, int wy, int wz) {
        return atWaypoint(b, wx, wy, wz) && deliverable(b, wx, wz);
    }

    /**
     * Whether the bot OCCUPIES waypoint cell {@code (wx,wy,wz)} — the position half of {@link #reached},
     * shared by every override so the partial-top rule can never drift between them. It says nothing about
     * whether the bot is SETTLED there; each caller adds its own medium test.
     *
     * <p>Block-exact by design: a waypoint is a FEET cell and the bot's feet are {@code blockPosition()}.
     *
     *
     * <p><b>DO NOT add a partial-top tolerance here.</b> It looks necessary and is not: {@code
     * BlockPathfinder.feetYOf} already builds every waypoint topY-aware —
     * {@code fy + (topY == 16 ? 1 : 0)} — so a slab / snow / carpet / pressure-plate waypoint ALREADY names
     * the lower cell the bot really stands in. Planner and follower have never disagreed. Tried and
     * reverted 2026-08-01: a second clause accepting {@code footY == wy - 1} on a solid floor cell instead
     * accepts the bot ONE CELL TOO LOW on every already-correct waypoint, and timed out the {@code stairup}
     * course card (bot parked at {@code finalY=151.50}, a stair half-height).
     *
     * <p>The dripstone livelock that motivated the attempt was never a partial-top case: {@code
     * pointed_dripstone} is FORCE-CLASSIFIED {@code SHAPE_FULL} in {@code NavBlock.fingerprint} (its
     * null-world collision query misleads), so {@code topY == 16}, {@code feetYOf} names the upper cell, and
     * vanilla seats the bot 5/16 lower. That is a classification quirk of the force-solid blocks, and it is
     * fixed where it belongs — narrow tops are no longer {@code STANDABLE}, so nothing routes onto one.
     */
    default boolean atWaypoint(BotSteering b, int wx, int wy, int wz) {
        return b.footX() == wx && b.footY() == wy && b.footZ() == wz
                // THE SETTLE BAND, not merely the cell (owner ruling 2026-08-05). A move's arrival pose is
                // the PRECONDITION of the next move: every plan is framed from "feet resting at wy", i.e.
                // [wy.00, wy.20], and its body-clearance cells are derived from that. Testing only the cell
                // let a move declare success anywhere in a 1.0-block span, handing the next move a frame up
                // to a full block off — at which point the bot's 1.8-tall box spans THREE cells instead of
                // two and collides with geometry the plan never checked.
                //
                // Convicted repeatedly on the canopy vine: Fall completed at botY=173.872 (cell 173, band
                // [173.00,173.20]); Descend then completed at 172.965 while hanging on the vine instead of
                // resting on the cobble at 172.0; the following Diagonal was framed for feet at 172 but
                // executed with its body reaching into cell 174, hit geometry nobody had cleared, and the
                // blocked press ratcheted it to the ceiling. Five separate patches at five arrival sites all
                // chased that one root cause.
                //
                // GROUNDED and FLUID are exempt, and must be: a grounded bot is physically resting on
                // whatever surface it found, so its y is correct by construction even on a partial floor
                // (bottom slab / snow / carpet seat the feet mid-cell, well above .20 — the topY-aware
                // feetYOf convention); and a floating bot bobs with buoyancy, which is why Swim overrides
                // reached with its own vertical tolerance. The band therefore binds exactly the case that
                // produced every failure above: a CLIMBABLE HANG, where nothing but the servo decides the
                // height the bot stops at.
                // The BAND only — deliberately NOT SteerControl.inRestingPose, which additionally requires
                // settled(). This method's contract is positional ("each caller adds its own medium test"),
                // and Climb depends on it: a CURTAIN TOP-OUT is a legitimate arrival that settled() does not
                // cover by design, so folding settled() in here broke it (StationKeepHoldTest's
                // toppedOutOnACurtainIsReachableByAClimbOnly, real geometry).
                //
                // Entry and arrival therefore differ, and coherently: a move may FINISH in a pose that is
                // positionally correct but not yet at rest, and the NEXT move's implicit settle gate simply
                // waits for it to come to rest before executing. The invariant that matters — no plan is
                // ever EXECUTED from outside its frame — is enforced once, at the gate.
                && (b.grounded() || b.inWater() || b.inLava()
                        || b.y() <= wy + SteerControl.SETTLE_BAND);
    }

    /**
     * Whether this step's folded break/place edits ({@link com.orebit.mod.pathfinding.blockpathfinder.StepEdits})
     * should be applied <i>this</i> tick. Default {@code true} (clear/fill the cells in front before moving
     * into them). Pillar overrides it to wait until the bot is airborne, because its footing is placed in the
     * bot's own feet cell — placing it while still standing there would set a block inside the bot.
     */
    default boolean editsReadyNow(BotSteering b) {
        return true;
    }

    /**
     * Drive the bot's per-tick movement <i>inputs</i> to track the planned {@link SteerView trajectory} — the
     * execution counterpart to {@link #candidates}. Default is the generic medium-aware locomotion ({@link
     * SteerControl#drive}): on land the line-tracking walk (face a look-ahead pursuit point + hold forward),
     * in water the horizontal swim drive plus the {@link SteerControl#holdDepth depth-hold} — so a ground
     * move still submerged on its way out of water keeps steering toward the exit AND rises toward it.
     * Overrides add a move's extra inputs (hold jump for a climb, the sprint flag for a sprint-swim,
     * re-centre for a vertical move); the water moves call {@code holdDepth} with their own pose bias. Every
     * input the bot presses is owned by SOME move's {@code steer} — there is no cross-cutting follower rule
     * and no follower-side recovery actuation (s52).
     */
    default void steer(BotSteering b, SteerView path) {
        SteerControl.drive(b, path);
    }

    /**
     * Build this move's declarative execution {@link MovePlan} for the step from floor cell {@code (fx,fy,fz)}
     * to {@code (tx,ty,tz)} — the reconcile-based counterpart to {@link #steer}, where the move states the
     * geometry it needs (break/place) and the phase ordering, and the follower's {@link PhaseRunner} establishes
     * that geometry against the LIVE world each tick (so a missed break/place self-heals). Built once when the
     * step begins.
     *
     * <p>Default {@code null} — the move has no plan and the follower drives it the old way ({@link #steer} plus
     * the follower's one-shot edit application). Moves are converted to the phase model one at a time (Pillar
     * first); an unconverted move is untouched.
     *
     * <p><b>Floor vs. foot Y (partial-block height awareness).</b> {@code (fx,fy,fz)}/{@code (tx,ty,tz)} are the
     * search-native <i>floor</i> cells (the block the bot stands ON — where support/footing edits and reach
     * geometry are anchored). {@code fromFootY}/{@code toFootY} are the bot's <i>feet block</i> Y when standing
     * on each floor — {@code floorY + 1} for a full-topped floor (full block / TOP slab), but the floor cell
     * ITSELF ({@code floorY}) for a BOTTOM-partial floor (bottom slab / snow / carpet / pressure plate /
     * repeater / amethyst, whose collision top is mid-cell so the feet occupy the floor's own cell). This is the
     * same topY-aware value the search reconstruct carries as the waypoint Y ({@code BlockPathfinder.feetYOf}),
     * supplied here so a plan's stand GUARDS ({@code failWhen}/{@code done}/{@code resetWhen} — they compare the
     * bot's live {@code footY()}) and its BODY-clearance {@code Need.AIR} cells (feet/head) sit at the real feet,
     * not a blanket {@code floorY+1}. Support/footing {@code Need.FOOTING} cells stay FLOOR-relative. For a full
     * block {@code fromFootY == fy+1} and {@code toFootY == ty+1}, so a plan that uses these is byte-identical to
     * the old {@code fy+1}/{@code ty+1} on ordinary (full-block) terrain — only partial floors shift.
     */
    default MovePlan plan(int fx, int fy, int fz, int tx, int ty, int tz, int fromFootY, int toFootY) {
        return null;
    }

    /**
     * Whether this move is an IRREVERSIBLE cross-arrival commitment — once begun (runup / takeoff / step-off) it
     * must run to its landing before the bot may declare "arrived" and drop all inputs. A jump ({@link
     * com.orebit.mod.pathfinding.blockpathfinder.movements.Parkour Parkour} / {@link
     * com.orebit.mod.pathfinding.blockpathfinder.movements.DiagonalParkour DiagonalParkour}) and the no-jump
     * {@link com.orebit.mod.pathfinding.blockpathfinder.movements.WalkOff WalkOff} crossing are committed: the
     * bot leaves the ground and cannot stop mid-arc, so a goal whose landing cell is within the arrival radius
     * must NOT preempt the move (the ice-STOP parkour undershoot; the WalkOff close-goal step-off).
     *
     * <p>Default {@code false} — an ordinary grounded, reversible move (Traverse / Diagonal / Ascend / Descend /
     * Pillar / …) can be stopped at any cell, so arrival may fire the moment the bot is in range and grounded.
     * The follower reads this in its arrival-preempt gate ({@code BotNavigator.midCommittedMove}); a move-agnostic
     * property, so it replaces the old {@code instanceof Parkour || instanceof DiagonalParkour} test and adding a
     * new committed move is one override on that move, never a follower edit. This is a move-NATURE flag (like the
     * cost model or {@link #plan}), not per-tick state — evaluate it as a constant.
     */
    default boolean commitsAcrossArrival() {
        return false;
    }
}
