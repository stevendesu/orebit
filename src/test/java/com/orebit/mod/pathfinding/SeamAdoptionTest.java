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
 * The §5 four-case adoption state machine for a parked seam-SEEDED boundary result
 * ({@link AsyncWindowSearch#pollSeededParked}, DESIGN-replan-handoff.md §5, R2–R4) as a table over
 * (bot floor, seam floor, plan membership, past-the-seam) → {PARK, ADOPT, FAST_FORWARD, PANIC}.
 * The geometric verdicts all live in this one mailbox seam — the driver ({@code PathPlan.pollPending})
 * only switches on them — so the pump is testable headlessly over hand-built plans, a sync-mode
 * mailbox ({@code executor == null}, whose walk-outrun tolerance degenerates to the fixed
 * Chebyshev-3 splice box), and the driver-derived {@code pastSeam} flag passed explicitly.
 *
 * <p>Geometry, all floor cells: seam at {@code (10,64,10)}; the parked plan runs on from it over
 * floors {@code (9,64,10) → (8,64,10) → (8,64,11)} (search-native floors, the frame
 * {@code onPlanBody} matches on).
 */
class SeamAdoptionTest {

    private static final BlockPos SEAM_FLOOR = new BlockPos(10, 64, 10);
    private static final BlockPos TARGET = new BlockPos(0, 64, 0);
    private static final int SEAM_INDEX = 3; // the seam's index on the OLD plan (identity-carried only)

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
    /** The executing (old) plan the seam was walked on — identity-compared only, content irrelevant. */
    private static final BlockPathPlan OLD_PLAN = planOf(new BlockPos(11, 65, 10),
            new BlockPos(10, 65, 10));

    /** A sync-mode mailbox with the seeded result parked at the seam, ready for the pump. */
    private static AsyncWindowSearch parked() {
        AsyncWindowSearch mailbox = new AsyncWindowSearch(null);
        mailbox.parkSeededResult(NEW_PLAN, false, 42, false, SEAM_FLOOR, TARGET, OLD_PLAN, SEAM_INDEX);
        return mailbox;
    }

    private static SeamVerdict poll(AsyncWindowSearch mailbox, BlockPos botFloor, boolean fluidAnchor,
                                    boolean pastSeam) {
        return mailbox.pollSeededParked(null, botFloor, TARGET, BlockPathfinder.MODE_AUTO, fluidAnchor,
                pastSeam);
    }

    // ---- case 2: ADOPT — settled AT the seam ----------------------------------------------------------

    @Test
    void settledAtTheSeamAdopts() {
        AsyncWindowSearch mailbox = parked();
        assertEquals(SeamVerdict.ADOPT, poll(mailbox, SEAM_FLOOR, false, false));
        assertSame(NEW_PLAN, mailbox.resultPlan(), "the adopted result installs through the result fields");
        assertEquals(SEAM_FLOOR, mailbox.resultStart(), "blockPlanStart becomes the seam");
        assertEquals(42, mailbox.resultExpansions(), "the seeded search's REAL telemetry is carried");
        assertFalse(mailbox.seededParked(), "the slot clears on adoption");
    }

    @Test
    void theFluidBobToleranceAdmitsOneCellOfYAtTheSeam() {
        // fluidAnchor widens the start match to yTol +/-1 (the bob-quantized floor); a ground anchor
        // stays exact — one cell of Y off the seam is an approach (KEEP), not an arrival.
        AsyncWindowSearch dry = parked();
        assertEquals(SeamVerdict.KEEP, poll(dry, SEAM_FLOOR.above(), false, false));
        assertTrue(dry.seededParked(), "a ground anchor is exact — still parked");

        AsyncWindowSearch wet = parked();
        assertEquals(SeamVerdict.ADOPT, poll(wet, SEAM_FLOOR.above(), true, false));
    }

    @Test
    void theStepZeroEntryGateIsVacuouslyTrueForGroundMovesToday() {
        // §5: case-2 adoption consults movement(0).entryReady with the LIVE bot; every current ground
        // move's entryReady is the always-true default, so a real bot at the seam still ADOPTs — the
        // gate exists for future movements with a real (directional/velocity) entry test.
        AsyncWindowSearch mailbox = parked();
        SeamVerdict v = mailbox.pollSeededParked(new SeamBot(), SEAM_FLOOR, TARGET,
                BlockPathfinder.MODE_AUTO, false, false);
        assertEquals(SeamVerdict.ADOPT, v, "a Traverse step 0 never refuses entry");
    }

    // ---- case 1: PARK — not settled at the seam yet ---------------------------------------------------

    @Test
    void approachingInsideTheToleranceStaysParked() {
        // Two cells short of the seam along the old route, off the new plan, cursor not past the seam:
        // the result waits for a later boundary (R2 — park until the seam).
        AsyncWindowSearch mailbox = parked();
        assertEquals(SeamVerdict.KEEP, poll(mailbox, new BlockPos(12, 64, 10), false, false));
        assertTrue(mailbox.seededParked(), "case 1 keeps the result parked");
    }

    @Test
    void outsideTheToleranceStaysParkedEvenPastTheSeam() {
        // The walk-outrun Chebyshev bound is checked FIRST: a bot wholly away from the seam (Cheb 5 > 3
        // in the sync-degenerate box) parks regardless of the cursor — never a long-range PANIC.
        AsyncWindowSearch mailbox = parked();
        assertEquals(SeamVerdict.KEEP, poll(mailbox, new BlockPos(15, 64, 10), false, true));
        assertTrue(mailbox.seededParked());
    }

    // ---- case 3: FAST_FORWARD — on the plan body ------------------------------------------------------

    @Test
    void pastTheSeamButOnThePlanFastForwards() {
        // R3: the bot overshot the seam but its floor lies ON the new plan (step-1 floor (8,64,10)) —
        // install; the follower's reached-scan enters mid-plan ("advance SKIPPED").
        AsyncWindowSearch mailbox = parked();
        assertEquals(SeamVerdict.FAST_FORWARD, poll(mailbox, new BlockPos(8, 64, 10), false, true));
        assertSame(NEW_PLAN, mailbox.resultPlan());
        assertFalse(mailbox.seededParked(), "the slot clears on fast-forward too");
    }

    @Test
    void onThePlanBodyBeforeTheSeamStaysParked() {
        // R3's precondition is load-bearing (owner ruling 2026-08-18, the ReplanCourse reversal stall): a
        // seam-seeded plan legitimately DOUBLES BACK through the pre-seam cells the bot is still walking, so
        // a pre-seam floor ON the plan body must NOT install — a cursor-0 body entry mid-cell pins the
        // reached-scan (no waypoint's arrival plane is satisfied) and the follower stands in a yaw-flapping
        // limit cycle. Pre-seam + on-plan = case 1: stay parked, walk the old plan to the seam, ADOPT there.
        AsyncWindowSearch mailbox = parked();
        assertEquals(SeamVerdict.KEEP, poll(mailbox, new BlockPos(9, 64, 10), false, false));
        assertTrue(mailbox.seededParked(), "pre-seam on-plan keeps the result parked for the seam ADOPT");
    }

    // ---- case 4: PANIC — past the seam, off the plan --------------------------------------------------

    @Test
    void pastTheSeamAndOffThePlanPanics() {
        // R4: near the seam (inside the tolerance box), NOT the seam, NOT on the plan, cursor past the
        // seam — the result is dropped here; the driver drops the incumbent and resubmits from rest.
        AsyncWindowSearch mailbox = parked();
        assertEquals(SeamVerdict.PANIC, poll(mailbox, new BlockPos(10, 64, 12), false, true));
        assertFalse(mailbox.seededParked(), "PANIC drops the parked result");
        assertEquals(SeamVerdict.KEEP, poll(mailbox, new BlockPos(10, 64, 12), false, true),
                "an empty slot is a no-op KEEP — PANIC fires once");
    }

    @Test
    void theSamePoseBeforeTheSeamKeepsApproaching() {
        // The pastSeam flag is what separates R4 from R2: the identical off-plan pose with the cursor
        // still at/before the seam is an ordinary approach — keep the result parked.
        AsyncWindowSearch mailbox = parked();
        assertEquals(SeamVerdict.KEEP, poll(mailbox, new BlockPos(10, 64, 12), false, false));
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
        // body — standing there is startMatches' arm, and the install seed's cursor stays 0.
        assertEquals(-1, AsyncWindowSearch.planBodyIndex(NEW_PLAN, SEAM_FLOOR, 0),
                "the seam floor is the plan's implicit step -1, never on its body");
    }

    // ---- staleness ------------------------------------------------------------------------------------

    @Test
    void aMovedWindowTargetDropsTheStaleResult() {
        // The P4 rule carried over: the window moved on while the seeded search ran — the parked plan
        // answers a question nobody is asking anymore.
        AsyncWindowSearch mailbox = parked();
        assertEquals(SeamVerdict.KEEP, mailbox.pollSeededParked(null, SEAM_FLOOR, new BlockPos(5, 64, 5),
                BlockPathfinder.MODE_AUTO, false, false));
        assertFalse(mailbox.seededParked(), "a stale-target result is dropped, not adopted");
    }

    /** A minimal live-pose {@link BotSteering} standing at the seam (feet cell {@code (10,65,10)}) — just
     *  enough for the case-2 {@code entryReady} consultation ({@code CarryArrestGateTest}'s double,
     *  trimmed to the constant pose this test needs). */
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
