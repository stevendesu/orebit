package com.orebit.mod.pathfinding.blockpathfinder;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * A movement's <b>execution plan</b> — the declarative, ordered list of {@link Phase phases} a {@link Movement}
 * goes through to carry the bot from one floor cell to the next, the way a keyboard player would (jump, then
 * place, then land). Built ONCE when a step begins (the from/to cells are known) by {@link Movement#plan}; the
 * follower's {@link PhaseRunner} then consumes it each tick. Movements stay stateless singletons that only
 * <i>describe</i> the plan; the per-execution cursor lives in the runner.
 *
 * <h2>Why phases, and why guard-based (not a remembered FSM)</h2>
 * The alternative — a per-tick waterfall of {@code if}s inside {@code steer} — is hard to read for a multi-step
 * move (pillar's jump/place/land, parkour's take-off/place-landing/land, wall-clutch's fall/clutch/land). A
 * named phase list reads like the physical action. But a phase machine that merely <i>remembers</i> "we're in
 * LAND now" and only advances forward re-introduces the "I thought I was done" desync the reactive follower
 * exists to kill: if a cell we believed cleared is still solid, a forward-only machine won't go back. So the
 * runner is <b>guard-based and self-healing</b>: each phase's {@link Phase#need needs} are re-checked against
 * the LIVE world every tick and re-established (mine/place) if unmet, and a {@link #resetWhen} guard sends the
 * cursor back when reality shows the move regressed (e.g. a pillar that fell back to the start). The cursor is
 * "what we're trying to do"; live geometry is "what's actually true."
 *
 * <h2>The requirement vocabulary (break + place from one declaration)</h2>
 * A {@link Phase#need need} states a target geometry on a cell, and the runner establishes it with the matching
 * action: {@link Need#AIR} &rarr; mine it if solid; {@link Need#FOOTING} &rarr; place a block if it's missing.
 * That single vocabulary unifies breaking and placing. The kinematic timing a cell-requirement can't express
 * (place only once airborne, clutch at a fall-distance threshold) rides the {@link Phase#advanceWhen}/{@link
 * Phase#done} guard predicates over the bot's live pose — declarative sugar for the common case, a plain
 * predicate escape hatch for the rest.
 */
public final class MovePlan {

    /** The kind of geometry a {@link Phase#need} demands, and hence the action that establishes it. */
    public enum Need {
        /** The cell must be clear — the runner mines it (timed, real tool) while it is solid. */
        AIR,
        /** The cell must be solid footing — the runner places a block there while it is missing. */
        FOOTING,
        /**
         * The (hand-toggleable) openable — door OR trapdoor — at the cell must reach a target OPEN state
         * (DOORS P3; trapdoors DESIGN-trapdoors.md §7) — the runner {@link BotSteering#setDoorOpen}s /
         * {@link BotSteering#setTrapdoorOpen}s it (instant, place-like; the {@link Req} carries which kind)
         * while the live openable ({@link BotSteering#doorOpenAt}, <b>not</b> {@code solidAt} — an open door
         * still has collision, an open trapdoor its wall panel) does not read that state. The target
         * ({@code open}) rides on the {@link Req}. Carried at plan level (not inside a phase) because an
         * openable-state is a precondition of the WHOLE crossing, established before any phase drives — see
         * {@link #requireDoor}/{@link #requireTrapdoor} and {@link PhaseRunner}.
         */
        OPEN
    }

    private final List<Phase> phases = new ArrayList<>(4);
    private Predicate<BotSteering> resetWhen = b -> false;
    private Predicate<BotSteering> failWhen = b -> false;
    /** The step's horizontal movement direction (signum), for the runner's direction-aware body-obstruction test
     *  ({@link BotSteering#movementBlockedAt}). {@code (0,0)} (the default) = a vertical/undirected move. */
    private int moveDx, moveDz;
    /** Plan-level openable {@link Need#OPEN} reqs — doors AND trapdoors (DOORS P3; DESIGN-trapdoors.md §7);
     *  almost always empty (an openable crossing is rare). */
    private final List<Req> doorReqs = new ArrayList<>(0);
    /**
     * The plan-level CLUTCH: the {@link ClutchModel} kind to place into the landing cell mid-drop, and the
     * cell it goes in. {@link ClutchModel#NONE} on every plan but a clutched deep {@link
     * com.orebit.mod.pathfinding.blockpathfinder.movements.Fall}, so this is four dead {@code int}s on the
     * common step — deliberately scalars rather than a {@link Req} list, because a clutch is one-per-step by
     * construction ({@link StepEdits} carries exactly one kind + one cell) and the flat fields mirror the
     * other plan-level scalar {@link #moveDir} rather than paying a list for a value that can never repeat.
     */
    private int clutchKind = ClutchModel.NONE;
    private int clutchX, clutchY, clutchZ;

    /** Append a phase; returns it for fluent configuration ({@code .need(...).drive(...).advanceWhen(...)}). */
    public Phase phase(String name) {
        Phase p = new Phase(name);
        phases.add(p);
        return p;
    }

    /**
     * Set the regression guard: when it tests true the runner snaps the cursor back to the first phase, so a
     * move that physically fell back to its start (a pillar whose footing never took, a parkour that came up
     * short) re-attempts from the beginning instead of latching a later phase it never truly reached.
     */
    public MovePlan resetWhen(Predicate<BotSteering> guard) {
        this.resetWhen = guard;
        return this;
    }

    /**
     * Set the <b>validity envelope</b>: when it tests true the runner reports the whole move FAILED
     * ({@link PhaseRunner#failed}) instead of driving — the live state is provably outside the move's
     * model (for a committed jump: grounded on a cell that is neither the takeoff stand nor the planned
     * landing column), so no phase's {@code advanceWhen}/{@code done} can ever fire from here and
     * re-attempting in place is a permanent latch. Distinct from {@link #resetWhen}, which the runner
     * checks FIRST and which keeps owning the legitimate balk-at-the-start retry: reset says "physically
     * back at the start — re-attempt from phase 0", fail says "somewhere this plan has no answer for —
     * the follower must drop the plan and replan from where the bot really is." Purely state-derived (a
     * predicate over the live pose and cells the plan already carries — no timers, no motion signatures);
     * the default never fires, so a plan that declares no envelope is byte-identical.
     */
    public MovePlan failWhen(Predicate<BotSteering> guard) {
        this.failWhen = guard;
        return this;
    }

    /**
     * Record the step's horizontal movement direction (any non-zero-per-axis form — signum or raw delta), so the
     * runner's {@code Need.AIR} reconcile can ask {@link BotSteering#movementBlockedAt} whether a body cell is
     * obstructed ALONG THE ROUTE (a closed door across the path blocks; an open door along a side does not).
     * Set by {@link com.orebit.mod.BotNavigator} from the step's from/to cells after the movement builds its
     * plan (the movement's cell geometry doesn't otherwise need it). Default {@code (0,0)} = vertical/undirected.
     */
    public MovePlan moveDir(int dx, int dz) {
        this.moveDx = dx;
        this.moveDz = dz;
        return this;
    }

    /**
     * Require the door at cell {@code (x,y,z)} reach {@code open} before the crossing drives (DOORS P3) — a
     * plan-level {@link Need#OPEN}. Injected by {@link com.orebit.mod.BotNavigator} from the step's folded
     * door-set ({@link StepEdits}) after the movement builds its geometry plan, since a door-open is a
     * live-world fact a movement's cell-geometry {@code plan(...)} cannot derive on its own. The runner opens
     * all door reqs (via {@link BotSteering#setDoorOpen}) as a pre-pass each tick and re-validates them with
     * {@link BotSteering#doorOpenAt}; a cell governed by a door req is never mined by a {@link Need#AIR} on the
     * same cell ({@link #isOpenableCell}). Returns {@code this} for fluent use.
     */
    public MovePlan requireDoor(int x, int y, int z, boolean open) {
        doorReqs.add(new Req(Need.OPEN, x, y, z, open, false));
        return this;
    }

    /**
     * Require the <b>trapdoor</b> at cell {@code (x,y,z)} reach {@code open} before the crossing drives
     * (DESIGN-trapdoors.md §7) — the trapdoor twin of {@link #requireDoor}, riding the same plan-level
     * {@link Need#OPEN} list (re-validated per tick via the shared {@link BotSteering#doorOpenAt} read, never
     * mined — {@link #isOpenableCell}). The kind flag is what the runner dispatches the executor verb on
     * ({@link BotSteering#setTrapdoorOpen} vs {@link BotSteering#setDoorOpen}); it is resolved ONCE at
     * injection by {@link com.orebit.mod.BotNavigator} from the live block at the folded SET's cell — the
     * kind of a cell never flips out from under a step (an external toggle changes OPEN, not the block).
     * Returns {@code this} for fluent use.
     */
    public MovePlan requireTrapdoor(int x, int y, int z, boolean open) {
        doorReqs.add(new Req(Need.OPEN, x, y, z, open, true));
        return this;
    }

    /**
     * Record that this step lands on a <b>clutch</b> — the {@link ClutchModel} block of {@code kind} placed
     * into cell {@code (x,y,z)} mid-drop to survive a fall {@link
     * com.orebit.mod.pathfinding.blockpathfinder.movements.Fall} would otherwise refuse (ClutchModel's class
     * doc). Injected by {@link com.orebit.mod.BotNavigator} off the step's {@link StepEdits}
     * ({@link StepEdits#clutchKind()}/{@link StepEdits#clutchCell()}) after the movement builds its geometry
     * plan, <b>exactly as {@link #requireDoor} is</b>, and for the same reason: the choice is a live-world
     * fact a movement's cell-geometry {@code plan(...)} cannot derive from floor coords. The planner did not
     * merely decide THAT the drop is survivable — it picked WHICH carried block makes it so, from a
     * preference order gated on the bot's {@code clutchMask} and the depth ({@link ClutchModel#best}), and
     * priced that kind's residual damage into {@code g}. Re-deriving it downstream against the LIVE inventory
     * could pick a different kind than the one the search paid for, so the decision travels rather than being
     * recomputed.
     *
     * <p><b>Carrying the cell is not redundant with the step's place-set.</b> The two landing geometries
     * differ (ClutchModel §Landing): a LANDS-ON-TOP kind (slime, hay) folds a {@code PathEdits.PLACED} at the
     * landing floor, but a SINK-THROUGH kind (water, powder snow) folds <b>no geometry edit at all</b> — a
     * place at the bot's own feet cell would make the node read its body space as solid and dead-end itself.
     * For those kinds this cell is the ONLY record of where the block goes.
     *
     * <p><b>This plan only CARRIES the requirement; the runner does nothing with it.</b> A clutch is not a
     * {@link Need} — it is not established before a phase drives (it is placed DURING the drop, at a
     * fall-distance the geometry vocabulary cannot express) and it is not reconciled against the live world
     * by the runner. {@code Fall} reads it back off its own plan inside the phase closures it built, and
     * drives {@link BotSteering#placeClutch}/{@link BotSteering#reclaimClutch} itself. Returns {@code this}
     * for fluent use.
     */
    public MovePlan requireClutch(int x, int y, int z, int kind) {
        this.clutchX = x;
        this.clutchY = y;
        this.clutchZ = z;
        this.clutchKind = kind;
        return this;
    }

    // ---- the clutch, read back by the MOVEMENT (not the runner) --------------------------------------
    //
    // PUBLIC, unlike the package-private accessors below, because the reader is Fall — and the movements
    // live in the sibling package com.orebit.mod.pathfinding.blockpathfinder.movements, so package-private
    // would be invisible to the one class that needs them. Same split the builder API already has:
    // phase()/need()/requireDoor() are public because movements call them across that boundary, while
    // needs()/doorReqs() stay package-private because only PhaseRunner reads them.

    /** The {@link ClutchModel} kind this step clutches with, or {@link ClutchModel#NONE} — which is every
     *  plan but a clutched deep {@code Fall}. Callers MUST test against {@code NONE} before trusting the
     *  cell, whose value is meaningless (0,0,0) otherwise. */
    public int clutchKind() { return clutchKind; }
    /** X of the cell the clutch block goes in; meaningful only when {@link #clutchKind()} != {@code NONE}. */
    public int clutchX() { return clutchX; }
    /** Y of the cell the clutch block goes in — the landing FEET cell for a sink-through kind, the landing
     *  FLOOR cell for a lands-on-top one ({@link ClutchModel#landsOnTop}). */
    public int clutchY() { return clutchY; }
    /** Z of the cell the clutch block goes in; meaningful only when {@link #clutchKind()} != {@code NONE}. */
    public int clutchZ() { return clutchZ; }

    // ---- consumed by PhaseRunner ---------------------------------------------------------------------
    int size() { return phases.size(); }
    Phase phaseAt(int i) { return phases.get(i); }
    boolean regressed(BotSteering bot) { return resetWhen.test(bot); }
    boolean failed(BotSteering bot) { return failWhen.test(bot); }
    int moveDx() { return moveDx; }
    int moveDz() { return moveDz; }
    List<Req> doorReqs() { return doorReqs; }

    /** Whether cell {@code (x,y,z)} is governed by an openable {@link Need#OPEN} — door or trapdoor — (so a
     *  {@code Need.AIR} must NOT mine it: a SET-governed cell is opened/closed by hand, never smashed).
     *  Linear over the tiny openable list (usually empty). */
    boolean isOpenableCell(int x, int y, int z) {
        for (int i = 0; i < doorReqs.size(); i++) {
            Req r = doorReqs.get(i);
            if (r.x == x && r.y == y && r.z == z) return true;
        }
        return false;
    }

    /**
     * One step of a {@link MovePlan}: the geometry it must establish first, how to drive the bot's inputs once
     * that geometry holds, and the guard that says this phase is finished. Fluent builder; every field has a
     * sensible default (no needs, generic medium-aware drive, advance immediately, never "done"), so a phase
     * only states what it changes.
     */
    public static final class Phase {
        final String name;
        private final List<Req> needs = new ArrayList<>(2);
        private BiConsumer<BotSteering, SteerView> drive = SteerControl::drive;
        private Predicate<BotSteering> advance = b -> true;
        private Predicate<BotSteering> done = b -> false;
        private boolean arrestCarry;
        private int carryFromX, carryFromZ;

        private Phase(String name) { this.name = name; }

        /**
         * Gate this phase's drive on <b>cross-axis velocity alignment</b> while the bot is still grounded on
         * the from-column {@code (fx,fz)} — the declarative form of the owner-ratified step-off gate
         * ({@link SteerControl#stepOffGate}), generalized off {@link
         * com.orebit.mod.pathfinding.blockpathfinder.movements.Descend} to every grounded move that commits
         * into a NEW column (owner ruling 2026-08-01).
         *
         * <p><b>Why any move, not just a step-off.</b> A step entered with momentum ROUGHLY PERPENDICULAR to
         * its own line — the chained 90° turn: a −z move handing off to a +x one — drifts across the one-wide
         * lane during the 2–3 ticks ground friction needs to bleed the carry, and grounds on the diagonally
         * adjacent cell. That is a real off-plan settle, which the validity envelope rightly fail→HOLDs, so
         * the bot freezes on a move it never had the alignment to make. Both witnessed specimens are this
         * shape: the flagship-cliff {@code (68,149,245)}-vs-column-{@code (68,*,246)} Descend freeze, and the
         * 2026-07-31 post-replan {@code Ascend −X} entered carrying an abandoned Parkour's sprint carry −Z
         * (the runup had parked the centre ~0.15 from the lip by design, {@code TAKEOFF_EDGE}).
         *
         * <p>Cost is zero on the common case: a step continuing in the SAME direction has ~no cross velocity,
         * so the prediction is contained on the first tick and the phase drives unchanged. Only a genuine
         * turn-under-carry pays, and it pays in the 1–3 ticks the arrest needs — never in a permanent hold.
         * The runner checks this AFTER the phase's needs (geometry prep proceeds while the carry bleeds) and
         * BEFORE the drive, and it self-limits: once the bot is airborne or its foot has left the from
         * column, the gate can never re-engage.
         */
        public Phase arrestCarryFrom(int fx, int fz) {
            this.arrestCarry = true;
            this.carryFromX = fx;
            this.carryFromZ = fz;
            return this;
        }

        /** Require {@code kind} geometry at cell {@code (x,y,z)}; the runner mines (AIR) or places (FOOTING)
         *  to establish it before this phase drives. */
        public Phase need(Need kind, int x, int y, int z) {
            needs.add(new Req(kind, x, y, z));
            return this;
        }

        /** How to drive the bot's inputs once this phase's needs are met (default: medium-aware locomotion). */
        public Phase drive(BiConsumer<BotSteering, SteerView> d) {
            this.drive = d;
            return this;
        }

        /** Advance to the NEXT phase once this tests true (a non-terminal phase). */
        public Phase advanceWhen(Predicate<BotSteering> guard) {
            this.advance = guard;
            return this;
        }

        /** The whole move is complete once this tests true (used on the LAST phase). */
        public Phase done(Predicate<BotSteering> guard) {
            this.done = guard;
            return this;
        }

        // ---- consumed by PhaseRunner -----------------------------------------------------------------
        List<Req> needs() { return needs; }
        void drive(BotSteering bot, SteerView view) { drive.accept(bot, view); }
        boolean shouldAdvance(BotSteering bot) { return advance.test(bot); }
        boolean isDone(BotSteering bot) { return done.test(bot); }

        /** Whether the cross-axis carry is still uncontained for this phase — {@code true} means the arrest
         *  inputs have been WRITTEN for this tick and the caller must not drive. Inert unless the phase
         *  declared {@link #arrestCarryFrom}; never fires once airborne or off the from column. */
        boolean carryUncontained(BotSteering bot, SteerView view) {
            return arrestCarry
                    && bot.grounded()
                    && bot.footX() == carryFromX && bot.footZ() == carryFromZ
                    && SteerControl.stepOffGate(bot, view);
        }
    }

    /** One geometry requirement: a {@link Need} at a world cell. {@code open} is the target openable state and
     *  {@code trapdoor} the openable KIND (trapdoor vs door — the runner's executor-verb dispatch); both are
     *  used only by {@link Need#OPEN} ({@code open} true / {@code trapdoor} false for AIR/FOOTING, where they
     *  are inert). */
    static final class Req {
        final Need kind;
        final int x, y, z;
        final boolean open;
        final boolean trapdoor;
        Req(Need kind, int x, int y, int z) { this(kind, x, y, z, true, false); }
        Req(Need kind, int x, int y, int z, boolean open, boolean trapdoor) {
            this.kind = kind; this.x = x; this.y = y; this.z = z; this.open = open; this.trapdoor = trapdoor;
        }
    }
}
