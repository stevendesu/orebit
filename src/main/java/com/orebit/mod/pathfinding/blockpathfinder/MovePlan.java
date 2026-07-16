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
         * The (hand-toggleable) door at the cell must reach a target OPEN state (DOORS P3) — the runner
         * {@link BotSteering#setDoorOpen}s it (instant, place-like) while the live door
         * ({@link BotSteering#doorOpenAt}, <b>not</b> {@code solidAt} — an open door still has collision) does
         * not read that state. The target ({@code open}) rides on the {@link Req}. Carried at plan level (not
         * inside a phase) because a door-open is a precondition of the WHOLE crossing, established before any
         * phase drives — see {@link #requireDoor} and {@link PhaseRunner}.
         */
        OPEN
    }

    private final List<Phase> phases = new ArrayList<>(4);
    private Predicate<BotSteering> resetWhen = b -> false;
    /** Plan-level door {@link Need#OPEN} reqs (DOORS P3); almost always empty (a door crossing is rare). */
    private final List<Req> doorReqs = new ArrayList<>(0);

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
     * Require the door at cell {@code (x,y,z)} reach {@code open} before the crossing drives (DOORS P3) — a
     * plan-level {@link Need#OPEN}. Injected by {@link com.orebit.mod.BotNavigator} from the step's folded
     * door-set ({@link StepEdits}) after the movement builds its geometry plan, since a door-open is a
     * live-world fact a movement's cell-geometry {@code plan(...)} cannot derive on its own. The runner opens
     * all door reqs (via {@link BotSteering#setDoorOpen}) as a pre-pass each tick and re-validates them with
     * {@link BotSteering#doorOpenAt}; a cell governed by a door req is never mined by a {@link Need#AIR} on the
     * same cell ({@link #isDoorCell}). Returns {@code this} for fluent use.
     */
    public MovePlan requireDoor(int x, int y, int z, boolean open) {
        doorReqs.add(new Req(Need.OPEN, x, y, z, open));
        return this;
    }

    // ---- consumed by PhaseRunner ---------------------------------------------------------------------
    int size() { return phases.size(); }
    Phase phaseAt(int i) { return phases.get(i); }
    boolean regressed(BotSteering bot) { return resetWhen.test(bot); }
    List<Req> doorReqs() { return doorReqs; }

    /** Whether cell {@code (x,y,z)} is governed by a door {@link Need#OPEN} (so a {@code Need.AIR} must NOT mine
     *  it — the door is opened/closed by hand, never smashed). Linear over the tiny door list (usually empty). */
    boolean isDoorCell(int x, int y, int z) {
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

        private Phase(String name) { this.name = name; }

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
    }

    /** One geometry requirement: a {@link Need} at a world cell. {@code open} is the target door state, used
     *  only by {@link Need#OPEN} (true for AIR/FOOTING, where it is inert). */
    static final class Req {
        final Need kind;
        final int x, y, z;
        final boolean open;
        Req(Need kind, int x, int y, int z) { this(kind, x, y, z, true); }
        Req(Need kind, int x, int y, int z, boolean open) {
            this.kind = kind; this.x = x; this.y = y; this.z = z; this.open = open;
        }
    }
}
