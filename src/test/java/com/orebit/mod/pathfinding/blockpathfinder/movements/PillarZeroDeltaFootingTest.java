package com.orebit.mod.pathfinding.blockpathfinder.movements;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.orebit.mod.pathfinding.blockpathfinder.BotSteering;
import com.orebit.mod.pathfinding.blockpathfinder.MovePlan;
import com.orebit.mod.pathfinding.blockpathfinder.PhaseRunner;
import com.orebit.mod.pathfinding.blockpathfinder.SteerView;

import net.minecraft.core.BlockPos;

/**
 * Pillar's frame sensitivity + validity envelope (DESIGN-replan-handoff.md §5/R3; the 2026-08-19 run-5
 * forensic). The run-5 wedge: a FAST_FORWARD seam adoption re-ran an already-executed Pillar step with a
 * frame shifted one cell toward the bot — and because Pillar's place phase targets {@code (fx, fy+1, fz)}
 * PURELY from that frame, the mis-framed step drove a {@code place} into the cell the bot's own feet
 * occupy: a silent, permanent entombment (Pillar carried no {@code failWhen}, so nothing ever reported
 * the step dead). Drives the real {@link Pillar#plan} through a real {@link PhaseRunner} against a
 * scripted recording bot (the {@code ClimbPlanConversionTest} pattern):
 *
 * <ul>
 *   <li>C1 — REMOVED 2026-08-23, unreachable by construction. It drove the place phase from a
 *       FALLEN-BACK pose (feet back in the footing cell) by scripting the advance tick and the
 *       place tick separately, which only worked while {@link PhaseRunner} advanced at the BOTTOM
 *       of its tick: the new phase's needs ran a tick later, by which time the injected pose had
 *       decayed. The runner now advances and establishes the new phase on ONE tick, so the place
 *       phase's first needs pass always happens at the pose that fired the gate — {@code y >= fy+2},
 *       feet ABOVE the footing cell. The entombing pose needs {@code y} in {@code [fy+1, fy+2)};
 *       the two are disjoint, so no scripted arc can reach C1's assertion any more. The guard it
 *       covered is NOT gone and is still exercised: C1b pins that a correctly-framed apex place is
 *       not refused, and C3 pins the grounded half (the actual run-5 pose). The airborne refusal
 *       still re-tests every tick if a footing never takes;</li>
 *   <li>C1b — the guard is EXACTLY the entombment geometry: a correctly-framed apex place (same column,
 *       feet one cell ABOVE the footing — {@code footY == r.y+1}, the pose Pillar's jump gate
 *       guarantees) is NOT refused;</li>
 *   <li>C2 — framed from the TRUE search start, the place lands in the plan's own column, never the
 *       bot's;</li>
 *   <li>C3 — the validity envelope: a bot GROUNDED at a foot-Y outside the step's own two feet levels
 *       (the entombed pose — grounded one above the landing foot) must FAIL the step, not latch
 *       (the grounded half of the same defense).</li>
 * </ul>
 */
class PillarZeroDeltaFootingTest {

    /** Wrong-frame column (the bot's): floor B=(9,64,10). True search start: S=(10,64,10). */
    private static final int BX = 9, SX = 10, Y = 64, Z = 10;
    private static final int FROM_FOOT_Y = 65, TO_FOOT_Y = 66;

    /** A scripted recording {@link BotSteering}: mutable pose, records {@code place} cells. */
    private static final class FakeBot implements BotSteering {
        final List<Long> placeCalls = new ArrayList<>();
        double x = BX + 0.5, y = 65.9, z = Z + 0.5; // airborne inside its own column, feet block 65
        boolean grounded;

        @Override public double x() { return x; }
        @Override public double y() { return y; }
        @Override public double z() { return z; }
        @Override public double velX() { return 0; }
        @Override public double velY() { return 0; }
        @Override public double velZ() { return 0; }
        @Override public int footX() { return (int) Math.floor(x); }
        @Override public int footY() { return (int) Math.floor(y); }
        @Override public int footZ() { return (int) Math.floor(z); }
        @Override public boolean grounded() { return grounded; }
        @Override public boolean inWater() { return false; }
        @Override public boolean inLava() { return false; }
        @Override public boolean prone() { return false; }
        @Override public void faceHorizontally(double dx, double dz) { }
        @Override public void faceTowards(double dx, double dy, double dz) { }
        @Override public void setForward(float zza) { }
        @Override public void setSprinting(boolean s) { }
        @Override public void setJumping(boolean j) { }
        @Override public void setSneak(boolean s) { }
        @Override public void sinkInWater() { }
        @Override public boolean solidAt(int x, int y, int z) { return false; } // footing never yet placed
        @Override public boolean airAt(int x, int y, int z) { return true; }
        @Override public boolean movementBlockedAt(int x, int y, int z, int dx, int dz) { return false; }
        @Override public void mine(int x, int y, int z) { }
        @Override public void place(int x, int y, int z) { placeCalls.add(BlockPos.asLong(x, y, z)); }
        @Override public void setDoorOpen(int x, int y, int z, boolean open) { }
        @Override public boolean doorOpenAt(int x, int y, int z) { return true; }
        @Override public boolean swimHazardAt(int x, int y, int z) { return false; }
        @Override public boolean bubbleUpAt(int x, int y, int z) { return false; }
        @Override public double slipperinessAt(int x, int y, int z) { return 0.6; }
        @Override public boolean gapFloorHazardAt(int x, int y, int z) { return false; }

        boolean placed(int x, int y, int z) { return placeCalls.contains(BlockPos.asLong(x, y, z)); }
    }

    /** A trivial segment over the frame's column. */
    private static final class Up implements SteerView {
        final double cx, cz;
        Up(int fx, int fz) { this.cx = fx + 0.5; this.cz = fz + 0.5; }
        @Override public double sx() { return cx; }
        @Override public double sy() { return FROM_FOOT_Y; }
        @Override public double sz() { return cz; }
        @Override public double tx() { return cx; }
        @Override public double ty() { return TO_FOOT_Y; }
        @Override public double tz() { return cz; }
        @Override public boolean hasNext() { return false; }
        @Override public double nx() { return 0; }
        @Override public double ny() { return 0; }
        @Override public double nz() { return 0; }
    }

    /**
     * Drive a Pillar framed from floor {@code (fx, Y, fz)} through the runner over a scripted jump arc:
     * airborne rise past the frame's {@code fy+2} advance gate (jump → place), then a final place-phase
     * tick at {@code placeTickY}. NOTE (2026-08-23): since the runner advances and establishes the new
     * phase on ONE tick, the place already happens on the apex tick below; {@code placeTickY} now only
     * controls the pose of the trailing tick, and both surviving callers pass a pose that changes nothing.
     * Returns the runner so callers can assert its hold state.
     */
    private static PhaseRunner driveJumpArc(FakeBot bot, int fx, int fz, double placeTickY) {
        MovePlan plan = new Pillar().plan(fx, Y, fz, fx, Y + 1, fz, FROM_FOOT_Y, TO_FOOT_Y);
        PhaseRunner runner = new PhaseRunner();
        runner.begin(plan); // gate unarmed: the fixture injects mid-flight poses, the frame is under test
        // SETTLE (added 2026-08-23): a pillar brakes and centres onto its own column before rising, so the
        // arc must START from a grounded, at-rest pose ON THE PLAN'S COLUMN. That tick is satisfied
        // ARTIFICIALLY here — the same reason the implicit settle gate above is left unarmed: this fixture
        // injects mid-flight poses because the FRAME is what is under test, and the FakeBot's setForward
        // is a no-op so a real re-centre could never converge. C2 in particular parks the bot in a
        // DIFFERENT column from the frame on purpose, which SETTLE would otherwise never clear. The pose
        // is restored immediately after, so every assertion still sees the pose its case intends.
        double keepX = bot.x, keepZ = bot.z, keepY = bot.y;
        bot.x = fx + 0.5;
        bot.z = fz + 0.5;
        bot.y = FROM_FOOT_Y;
        bot.grounded = true;
        runner.run(bot, new Up(fx, fz));     // settle -> jump (at rest, on the plan's column)
        bot.x = keepX;
        bot.z = keepZ;
        bot.y = keepY;
        bot.grounded = false;
        runner.run(bot, new Up(fx, fz));     // jump phase: y=65.9 < fy+2 — drives, no advance
        bot.y = 66.3;                        // apex — feet cleared the footing cell
        runner.run(bot, new Up(fx, fz));     // advance gate fires: jump -> place
        bot.y = placeTickY;                  // the pose the place phase executes from
        runner.run(bot, new Up(fx, fz));     // place phase establishes Need.FOOTING at (fx, 65, fz)
        return runner;
    }

    @Test
    void aCorrectlyFramedApexPlaceBeneathTheFeetIsNotRefused() {
        // C1b: the guard is exactly the entombment geometry, nothing broader. Same column, but the
        // place-phase tick runs at the APEX pose Pillar's jump gate guarantees (y=66.3 — feet block 66,
        // one ABOVE the footing cell 65): footY == r.y+1, the cell is beneath the feet, the place fires.
        FakeBot bot = new FakeBot();
        bot.x = SX + 0.5; // on the frame's column — the real, correctly-framed pillar geometry
        driveJumpArc(bot, SX, Z, 66.3);
        assertTrue(bot.placed(SX, 65, Z),
                "the legitimate apex place beneath the feet must NOT be refused by the guard");
    }

    @Test
    void aCorrectlyFramedPillarPlacesInItsOwnColumnNotTheBots() {
        // C2: framed from the TRUE search start S, the footing goes to S's column — the bot's cell is
        // never touched. Same bot pose, same script; only the frame differs.
        FakeBot bot = new FakeBot();
        driveJumpArc(bot, SX, Z, 65.4);
        assertTrue(bot.placed(SX, 65, Z), "the correctly-framed place fills the plan's own column");
        assertFalse(bot.placed(BX, 65, Z),
                "a correct frame never places into the bot's occupied cell");
    }

    @Test
    void groundedOutsideTheStepsFeetLevelsFailsTheEnvelope() {
        // C3: the entombed pose — GROUNDED one above the landing foot (standing on the block the wedge
        // placed inside its own cell). Outside {fromFootY, toFootY} the plan's frame is fiction; the
        // step must report FAILED so the follower replans, never latch silently.
        FakeBot bot = new FakeBot();
        MovePlan plan = new Pillar().plan(SX, Y, Z, SX, Y + 1, Z, FROM_FOOT_Y, TO_FOOT_Y);
        PhaseRunner runner = new PhaseRunner();
        runner.begin(plan);
        bot.x = SX + 0.5;
        bot.y = TO_FOOT_Y + 1; // feet block 67 — one above the landing foot
        bot.grounded = true;
        runner.run(bot, new Up(SX, Z));
        assertTrue(runner.failed(),
                "grounded outside the step's own feet levels must fail the step — the silent-wedge fix");
    }
}
