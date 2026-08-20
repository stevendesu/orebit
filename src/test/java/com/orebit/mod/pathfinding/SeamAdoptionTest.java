package com.orebit.mod.pathfinding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.orebit.mod.pathfinding.AsyncWindowSearch.SeamVerdict;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.pathfinding.blockpathfinder.BotSteering;
import com.orebit.mod.pathfinding.blockpathfinder.Movement;
import com.orebit.mod.pathfinding.blockpathfinder.MovementRegistry;
import com.orebit.mod.pathfinding.blockpathfinder.StepEdits;

import net.minecraft.core.BlockPos;

/**
 * The §11 execution-edge adoption pump for a parked seam-SEEDED boundary result
 * ({@link AsyncWindowSearch#pollSeededParked}, DESIGN-replan-handoff.md §5 as amended by §11 —
 * <b>owner ruling 2026-08-20</b>: "the seam shouldn't be about the bot's LOCATION, but about where it
 * is in the plan execution ... plans should swap between moves"). These cases deliberately SUPERSEDE
 * the pre-§11 location-semantics pins (settled-floor startMatches / live-floor body membership): the
 * verdict is now the index trichotomy (before / at / beyond the seam) while a move is in flight, ruled
 * on the in-flight move's LANDING cell, DEFERRED to that move's completion — and the old geometric
 * tests survive only in the degenerate no-move-in-flight regime, where the settled live floor is still
 * the truth.
 *
 * <p>The geometric verdicts all live in this one mailbox seam — the driver ({@code PathPlan.pollPending})
 * only switches on them (arming a consummation for deferred verdicts) — so the pump is testable
 * headlessly over hand-built plans, a sync-mode mailbox ({@code executor == null}, whose walk-outrun
 * tolerance degenerates to the fixed Chebyshev-3 splice box), and an explicit execution position
 * (follower plan identity + cursor + move-in-flight + landing floor).
 *
 * <p>Geometry, all floor cells: seam at {@code (10,64,10)} = the OLD plan's step-3 floor; the parked
 * NEW plan runs on from it over floors {@code (9,64,10) → (8,64,10) → (8,64,11)} (search-native floors,
 * the frame {@code planBodyIndex} matches on); the OLD plan continues past the seam over
 * {@code (11,64,11) → (11,64,12)} (steps 4–5 — the route the seeded result supersedes).
 */
class SeamAdoptionTest {

    private static final BlockPos SEAM_FLOOR = new BlockPos(10, 64, 10);
    private static final BlockPos TARGET = new BlockPos(0, 64, 0);
    private static final int SEAM_INDEX = 3; // the seam's waypoint index on the OLD plan

    /** A synthetic all-Traverse plan over stand (feet) cells; the convenience ctor derives each floor as
     *  {@code waypoint.below()} (the full-block case), matching {@code PathPlanOnRouteTest}'s fixture. */
    private static BlockPathPlan planOf(BlockPos... stands) {
        Movement[] moves = new Movement[stands.length];
        Arrays.fill(moves, MovementRegistry.TRAVERSE);
        return new BlockPathPlan(Arrays.asList(stands), Arrays.asList(moves),
                Arrays.asList(new StepEdits[stands.length]), 0f);
    }

    /** The parked (new) plan: stands one above the floors (9,64,10) → (8,64,10) → (8,64,11). */
    private static final BlockPathPlan NEW_PLAN = planOf(new BlockPos(9, 65, 10),
            new BlockPos(8, 65, 10), new BlockPos(8, 65, 11));
    /** The executing (old) plan the seam was walked on: floor(3) IS the seam (10,64,10); steps 4–5 walk
     *  on past it — the in-flight-move landings the beyond-seam verdicts rule on. */
    private static final BlockPathPlan OLD_PLAN = planOf(new BlockPos(13, 65, 10),
            new BlockPos(12, 65, 10), new BlockPos(11, 65, 10), new BlockPos(10, 65, 10),
            new BlockPos(11, 65, 11), new BlockPos(11, 65, 12));

    /** A sync-mode mailbox with the seeded result parked at the seam, ready for the pump. */
    private static AsyncWindowSearch parked() {
        AsyncWindowSearch mailbox = new AsyncWindowSearch(null);
        mailbox.parkSeededResult(NEW_PLAN, false, 42, false, SEAM_FLOOR, TARGET, OLD_PLAN, SEAM_INDEX);
        return mailbox;
    }

    /** IN-EXECUTION poll: the OLD plan is walking with the move at {@code cursor} in flight, landing at
     *  {@code landing}; {@code actual} is the live floor (the walk-outrun sanity box's only input). */
    private static SeamVerdict poll(AsyncWindowSearch mailbox, BlockPos actual, BlockPos landing,
                                    int cursor, boolean fluidAnchor) {
        return mailbox.pollSeededParked(null, actual, landing, TARGET, BlockPathfinder.MODE_AUTO,
                fluidAnchor, OLD_PLAN, cursor, true);
    }

    /** DEGENERATE poll: no move in flight (planless / consumed / holding at a truncated terminal) —
     *  the verdict consummates immediately on the settled {@code actual} floor. */
    private static SeamVerdict pollSettled(AsyncWindowSearch mailbox, BotSteering bot, BlockPos actual,
                                           int cursor, boolean fluidAnchor) {
        return mailbox.pollSeededParked(bot, actual, null, TARGET, BlockPathfinder.MODE_AUTO,
                fluidAnchor, OLD_PLAN, cursor, false);
    }

    // ---- at-seam: the move ENDING at the seam is in flight — deferred ADOPT ---------------------------

    @Test
    void theSeamMoveInFlightDefersAdoptToItsCompletion() {
        // §11 (supersedes the pre-§11 settledAtTheSeamAdopts location pin, owner ruling 2026-08-20):
        // cursor == seamIndex means the move ENDING at the seam is in flight — the verdict is ADOPT, but
        // nothing installs now: the driver truncates the old plan at this move (verdictTerminal) and
        // consummates at its completion. The slot must stay parked until then.
        AsyncWindowSearch mailbox = parked();
        SeamVerdict v = poll(mailbox, new BlockPos(11, 64, 10), OLD_PLAN.floor(SEAM_INDEX),
                SEAM_INDEX, false);
        assertEquals(SeamVerdict.ADOPT, v);
        assertTrue(mailbox.verdictDeferred(), "an in-execution ADOPT defers to move completion");
        assertEquals(SEAM_INDEX, mailbox.verdictTerminal(),
                "the old plan truncates at the seam move — 'the plan ends when this movement ends'");
        assertTrue(mailbox.seededParked(), "no install mid-move: the slot holds until consummation");
    }

    @Test
    void consummationFillsTheResultFieldsAndClearsTheSlot() {
        // The deferred verdict's second half: the driver calls consummateSeeded at the terminal move's
        // completion — the result fields fill (with the seeded search's REAL telemetry) and the slot
        // clears, exactly the pre-§11 adopt fill, just at the execution edge.
        AsyncWindowSearch mailbox = parked();
        poll(mailbox, new BlockPos(11, 64, 10), OLD_PLAN.floor(SEAM_INDEX), SEAM_INDEX, false);
        mailbox.consummateSeeded(-1);
        assertSame(NEW_PLAN, mailbox.resultPlan(), "the adopted result installs through the result fields");
        assertEquals(SEAM_FLOOR, mailbox.resultStart(), "blockPlanStart becomes the seam");
        assertEquals(42, mailbox.resultExpansions(), "the seeded search's REAL telemetry is carried");
        assertEquals(-1, mailbox.resultMatchedIndex(), "an ADOPT consummation carries no body match");
        assertFalse(mailbox.seededParked(), "the slot clears on consummation");
    }

    // ---- degenerate: no move in flight — verdict IS consummation, on the settled floor ----------------

    @Test
    void holdingAtTheSeamConsummatesImmediately() {
        // Scenario B of §11 (the seam-pause pickup): the bot completed the truncated terminal and HOLDS
        // centered at the seam when the result lands — no move is in flight, so the verdict consummates
        // immediately on the settled floor (the one regime where the live floor is still the truth).
        AsyncWindowSearch mailbox = parked();
        SeamVerdict v = pollSettled(mailbox, null, SEAM_FLOOR, SEAM_INDEX + 1, false);
        assertEquals(SeamVerdict.ADOPT, v);
        assertFalse(mailbox.verdictDeferred(), "no move in flight — consummation is immediate");
        assertSame(NEW_PLAN, mailbox.resultPlan());
        assertFalse(mailbox.seededParked(), "the slot clears on the immediate consummation");
    }

    @Test
    void settledOnTheBodyWithNoMoveInFlightFastForwardsImmediately() {
        // Degenerate past-seam body membership (the consumed-incumbent / post-hold shape): settled on
        // the new plan's step-1 floor — FAST_FORWARD immediately, matched index carried for the install
        // seed (DESIGN-replan-handoff.md §5/R3).
        AsyncWindowSearch mailbox = parked();
        SeamVerdict v = pollSettled(mailbox, null, new BlockPos(8, 64, 10), SEAM_INDEX + 1, false);
        assertEquals(SeamVerdict.FAST_FORWARD, v);
        assertFalse(mailbox.verdictDeferred());
        assertEquals(1, mailbox.resultMatchedIndex(), "the body hit rides out to the install seed");
        assertFalse(mailbox.seededParked());
    }

    @Test
    void theStepZeroEntryGateAdmitsACentredDeliveredBotAtConsummation() {
        // §11 re-site (owner ruling 2026-08-20): entryReady moved from VERDICT time to CONSUMMATION
        // time. The immediate (degenerate) ADOPT is a consummation, so it consults the gate with the
        // live bot — and since the delivery invariant (owner-ratified 2026-08-20) the gate is NO
        // LONGER vacuous for ground moves: the default entryReady is atWaypoint AND deliverable (the
        // one-tick velocity projection stays in the cell — MarginalArrivalTest). This SeamBot is the
        // pose consummation produces by construction (centred, zero velocity, grounded at the seam),
        // so it passes both clauses and the ADOPT goes through; a bot one tick from leaving the cell
        // would be ENTRY_REFUSED and re-consulted next boundary instead. A DEFERRED verdict consults
        // nothing — the driver re-asks at completion.
        AsyncWindowSearch mailbox = parked();
        SeamVerdict v = pollSettled(mailbox, new SeamBot(), SEAM_FLOOR, SEAM_INDEX + 1, false);
        assertEquals(SeamVerdict.ADOPT, v,
                "a centred, zero-velocity bot at the seam is a delivered Traverse entry — the gate must"
                        + " admit it");
    }

    // ---- before-seam: park, keep executing — regardless of geometry -----------------------------------

    @Test
    void beforeTheSeamParksRegardlessOfGeometry() {
        // §11: cursor < seamIndex is a flat PARK — the pre-§11 approachingInside case, and it now
        // structurally subsumes the 2026-08-18 reversal ruling (a seam-seeded plan legitimately DOUBLES
        // BACK through pre-seam cells the bot is still walking): even a landing ON the new plan's body
        // must not install before the seam. Walk the old plan to the seam; ADOPT consummates there.
        AsyncWindowSearch onOld = parked();
        assertEquals(SeamVerdict.KEEP, poll(onOld, new BlockPos(12, 64, 10),
                OLD_PLAN.floor(1), 1, false));
        assertTrue(onOld.seededParked(), "before-seam keeps the result parked");

        AsyncWindowSearch onBody = parked();
        assertEquals(SeamVerdict.KEEP, poll(onBody, new BlockPos(9, 64, 10),
                new BlockPos(9, 64, 10), 2, false), "even landing ON the body: pre-seam never installs");
        assertTrue(onBody.seededParked(), "pre-seam on-plan keeps the result parked for the seam ADOPT");
    }

    @Test
    void theSamePoseBeforeTheSeamKeepsApproaching() {
        // The trichotomy is what separates PANIC from an ordinary approach: the identical off-plan
        // landing with the cursor still before the seam is a plain park.
        AsyncWindowSearch mailbox = parked();
        assertEquals(SeamVerdict.KEEP, poll(mailbox, new BlockPos(10, 64, 12),
                new BlockPos(10, 64, 12), 2, false));
        assertTrue(mailbox.seededParked());
    }

    // ---- beyond: the in-flight move's LANDING cell decides --------------------------------------------

    @Test
    void beyondTheSeamWithTheLandingOnThePlanDefersFastForward() {
        // §11 (supersedes the pre-§11 live-floor pastSeamOnPlanFastForwards pin): the move in flight at
        // cursor 4 LANDS on the new plan's step-1 floor — FAST_FORWARD, deferred to that move's
        // completion, truncated AT that move, matched index carried for the install seed.
        AsyncWindowSearch mailbox = parked();
        SeamVerdict v = poll(mailbox, new BlockPos(11, 64, 10), new BlockPos(8, 64, 10), 4, false);
        assertEquals(SeamVerdict.FAST_FORWARD, v);
        assertTrue(mailbox.verdictDeferred());
        assertEquals(4, mailbox.verdictTerminal(), "truncate at the in-flight move — finish it first");
        assertEquals(1, mailbox.verdictMatched(), "the LANDING's body hit, not the live floor's");
        assertTrue(mailbox.seededParked(), "the slot holds until the consummation");
        mailbox.consummateSeeded(mailbox.verdictMatched());
        assertEquals(1, mailbox.resultMatchedIndex());
        assertFalse(mailbox.seededParked());
    }

    @Test
    void theFluidToleranceAdmitsOneCellOfYOnTheLandingMembership() {
        // The fluid yTol survives §11 on the landing→body membership (the bob-quantized floor): a
        // landing one Y above the body floor is off-plan dry (PANIC) but a body hit in fluid.
        AsyncWindowSearch dry = parked();
        assertEquals(SeamVerdict.PANIC, poll(dry, new BlockPos(11, 64, 10),
                new BlockPos(8, 65, 10), 4, false), "dry: exact-Y — one cell off the body is off-plan");

        AsyncWindowSearch wet = parked();
        assertEquals(SeamVerdict.FAST_FORWARD, poll(wet, new BlockPos(11, 64, 10),
                new BlockPos(8, 65, 10), 4, true), "fluid: yTol ±1 admits the bob-quantized landing");
        assertEquals(1, wet.verdictMatched());
    }

    @Test
    void beyondTheSeamWithTheLandingOffThePlanPanics() {
        // §11 (supersedes the pre-§11 pastSeamOffPlanPanics location pin): the in-flight move's landing
        // lies on NEITHER the seam nor the body — the result is useless (dropped NOW, the surviving
        // assertion), but the PLAN drop defers: the verdict carries the truncation terminal so the
        // driver finishes the committed move centered, THEN drops and relaunches rest-gated.
        AsyncWindowSearch mailbox = parked();
        SeamVerdict v = poll(mailbox, new BlockPos(11, 64, 11), new BlockPos(10, 64, 12), 4, false);
        assertEquals(SeamVerdict.PANIC, v);
        assertTrue(mailbox.verdictDeferred(), "the plan drop waits for the committed move to finish");
        assertEquals(4, mailbox.verdictTerminal());
        assertFalse(mailbox.seededParked(), "PANIC drops the parked result at verdict time");
        assertEquals(SeamVerdict.KEEP, poll(mailbox, new BlockPos(11, 64, 11),
                new BlockPos(10, 64, 12), 4, false), "an empty slot is a no-op KEEP — PANIC fires once");
    }

    @Test
    void outsideTheToleranceStaysParkedEvenPastTheSeam() {
        // The walk-outrun Chebyshev box survives §11 as a SANITY bound on the live floor: a bot wholly
        // away from the seam (Cheb 5 > 3 in the sync-degenerate box) parks regardless of the cursor —
        // never a long-range PANIC.
        AsyncWindowSearch mailbox = parked();
        assertEquals(SeamVerdict.KEEP, poll(mailbox, new BlockPos(15, 64, 10),
                new BlockPos(10, 64, 12), 4, false));
        assertTrue(mailbox.seededParked());
    }

    // ---- planBodyIndex — the R3 body-match INDEX the install seed consumes ----------------------------
    // (DESIGN-replan-handoff.md §5/R3; the 2026-08-19 run-5 forensic: a FAST_FORWARD install must know
    // WHICH step the bot already stands on, or the follower re-runs the executed prefix.)

    @Test
    void planBodyIndexReturnsTheMatchedStepForBodyFloors() {
        assertEquals(0, AsyncWindowSearch.planBodyIndex(NEW_PLAN, new BlockPos(9, 64, 10), 0),
                "step-0's search-native floor (9,64,10) matches at index 0");
        assertEquals(1, AsyncWindowSearch.planBodyIndex(NEW_PLAN, new BlockPos(8, 64, 10), 0),
                "step-1's search-native floor (8,64,10) matches at index 1");
    }

    @Test
    void planBodyIndexRefusesTheSeamFloor() {
        // Reconstruct is START-EXCLUSIVE: the seam (the plan's search start) is NOT a waypoint of the
        // body — a landing there is the ADOPT arm, and the install seed's cursor stays 0.
        assertEquals(-1, AsyncWindowSearch.planBodyIndex(NEW_PLAN, SEAM_FLOOR, 0),
                "the seam floor is the plan's implicit step -1, never on its body");
    }

    // ---- staleness ------------------------------------------------------------------------------------

    @Test
    void aMovedWindowTargetDropsTheStaleResult() {
        // The P4 rule carried over: the window moved on while the seeded search ran — the parked plan
        // answers a question nobody is asking anymore.
        AsyncWindowSearch mailbox = parked();
        assertEquals(SeamVerdict.KEEP, mailbox.pollSeededParked(null, SEAM_FLOOR, null,
                new BlockPos(5, 64, 5), BlockPathfinder.MODE_AUTO, false, OLD_PLAN, SEAM_INDEX, true));
        assertFalse(mailbox.seededParked(), "a stale-target result is dropped, not adopted");
    }

    @Test
    void aReplacedFollowerPlanDropsTheStaleResult() {
        // §11: the seam index is meaningful only against the plan the walk chose it on. A follower
        // walking a DIFFERENT plan (an install replaced the incumbent while the seeded search ran) makes
        // the trichotomy — and the result's premise — meaningless: drop, never a location-lucky install.
        AsyncWindowSearch mailbox = parked();
        assertEquals(SeamVerdict.KEEP, mailbox.pollSeededParked(null, SEAM_FLOOR, null,
                TARGET, BlockPathfinder.MODE_AUTO, false, NEW_PLAN /* not the walked plan */, 0, true));
        assertFalse(mailbox.seededParked(), "a dead-premise result is dropped");
    }

    /** A minimal live-pose {@link BotSteering} standing at the seam (feet cell {@code (10,65,10)}) — just
     *  enough for the consummation-time {@code entryReady} consultation ({@code CarryArrestGateTest}'s
     *  double, trimmed to the constant pose this test needs). */
    private static final class SeamBot implements BotSteering {
        @Override public double x() { return 10.5; }
        @Override public double y() { return 65.0; }
        @Override public double z() { return 10.5; }
        @Override public double velX() { return 0; }
        @Override public double velY() { return 0; }
        @Override public double velZ() { return 0; }
        @Override public int footX() { return 10; }
        @Override public int footY() { return 65; }
        @Override public int footZ() { return 10; }
        @Override public boolean grounded() { return true; }
        @Override public boolean inWater() { return false; }
        @Override public boolean inLava() { return false; }
        @Override public boolean prone() { return false; }
        @Override public void faceHorizontally(double dx, double dz) { }
        @Override public void faceTowards(double dx, double dy, double dz) { }
        @Override public void setForward(float zza) { }
        @Override public void setSprinting(boolean sprinting) { }
        @Override public void setJumping(boolean jumping) { }
        @Override public void setSneak(boolean sneaking) { }
        @Override public void sinkInWater() { }
        @Override public boolean solidAt(int x, int y, int z) { return true; }
        @Override public boolean airAt(int x, int y, int z) { return true; }
        @Override public boolean movementBlockedAt(int x, int y, int z, int dx, int dz) { return false; }
        @Override public void mine(int x, int y, int z) { }
        @Override public void place(int x, int y, int z) { }
        @Override public void setDoorOpen(int x, int y, int z, boolean open) { }
        @Override public boolean doorOpenAt(int x, int y, int z) { return true; }
        @Override public boolean swimHazardAt(int x, int y, int z) { return false; }
        @Override public boolean bubbleUpAt(int x, int y, int z) { return false; }
        @Override public double slipperinessAt(int x, int y, int z) { return 0.6; } // stone
        @Override public boolean gapFloorHazardAt(int x, int y, int z) { return false; }
    }
}
