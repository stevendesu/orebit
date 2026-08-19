package com.orebit.mod.pathfinding.blockpathfinder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * {@link BotSteering#atRest} (DESIGN-replan-handoff.md §10 U5) — the medium-aware launch-anchor gate, kept by U6 for the planless pickup and PANIC —
 * over a pose/velocity-settable double (the {@code CarryArrestGateTest} pattern). Pins the three medium
 * classes and the drag-derived ground epsilon's BEHAVIOR (an input-zeroed walker passes within ~4 ticks
 * of decay; an ice slide keeps failing while genuinely moving), not the constant's exact value.
 */
class AtRestTest {

    /** Minimal pose double: only the state {@code atRest} reads is settable; everything else is inert. */
    private static final class RestBot implements BotSteering {
        double vx, vz;
        boolean grounded, water, lava, climbable;

        RestBot vel(double vx, double vz) { this.vx = vx; this.vz = vz; return this; }
        RestBot standing() { this.grounded = true; return this; }

        @Override public double x() { return 0; }
        @Override public double y() { return 0; }
        @Override public double z() { return 0; }
        @Override public double velX() { return vx; }
        @Override public double velY() { return 0; }
        @Override public double velZ() { return vz; }
        @Override public int footX() { return 0; }
        @Override public int footY() { return 0; }
        @Override public int footZ() { return 0; }
        @Override public boolean grounded() { return grounded; }
        @Override public boolean inWater() { return water; }
        @Override public boolean inLava() { return lava; }
        @Override public boolean onClimbable() { return climbable; }
        @Override public boolean prone() { return false; }
        @Override public void faceHorizontally(double dx, double dz) { }
        @Override public void faceTowards(double dx, double dy, double dz) { }
        @Override public void setForward(float zza) { }
        @Override public void setSprinting(boolean sprinting) { }
        @Override public void setJumping(boolean jumping) { }
        @Override public void setSneak(boolean sneaking) { }
        @Override public void sinkInWater() { }
        @Override public boolean solidAt(int x, int y, int z) { return false; }
        @Override public boolean airAt(int x, int y, int z) { return true; }
        @Override public boolean movementBlockedAt(int x, int y, int z, int dx, int dz) { return false; }
        @Override public void mine(int x, int y, int z) { }
        @Override public void place(int x, int y, int z) { }
        @Override public void setDoorOpen(int x, int y, int z, boolean open) { }
        @Override public boolean doorOpenAt(int x, int y, int z) { return false; }
        @Override public boolean swimHazardAt(int x, int y, int z) { return false; }
        @Override public boolean bubbleUpAt(int x, int y, int z) { return false; }
        @Override public double slipperinessAt(int x, int y, int z) { return 0.6; }
        @Override public boolean gapFloorHazardAt(int x, int y, int z) { return false; }
    }

    // ---- ground: grounded AND slow --------------------------------------------------------------------

    @Test
    void aGroundedWalkingBotIsNotAtRest() {
        // Walk terminal speed 0.216 b/t (NOTES-movement-physics.md §1) — carry very much alive.
        assertFalse(new RestBot().standing().vel(0.216, 0).atRest());
    }

    @Test
    void aGroundedSlowBotIsAtRest() {
        assertTrue(new RestBot().standing().vel(0.01, 0.01).atRest(),
                "hypot ≈ 0.014 < the 0.02 rest epsilon");
    }

    @Test
    void theEpsilonPassesAWalkerAfterFourTicksOfGroundDrag() {
        // The derivation the constant's Javadoc records, pinned as behavior: v = 0.216·0.546ⁿ.
        RestBot bot = new RestBot().standing();
        assertFalse(bot.vel(0.216 * Math.pow(0.546, 3), 0).atRest(), "3 ticks after input-zero: 0.035");
        assertTrue(bot.vel(0.216 * Math.pow(0.546, 4), 0).atRest(), "4 ticks after input-zero: 0.019");
    }

    @Test
    void anIceSlideKeepsFailingUntilGenuinelySlow() {
        // Ice is just ground to this gate — no special case, the speed itself is the discriminator.
        // Blue-ice decay ×0.9/t: 22 ticks after input-zero a walk carry still reads 0.021 → not at rest.
        RestBot bot = new RestBot().standing();
        assertFalse(bot.vel(0.09, 0).atRest(), "mid-slide");
        assertFalse(bot.vel(0.216 * Math.pow(0.9, 22), 0).atRest(), "22 ticks of blue-ice decay: 0.021");
        assertTrue(bot.vel(0.216 * Math.pow(0.9, 23), 0).atRest(), "23 ticks: 0.019 — genuinely slow");
    }

    @Test
    void theSpeedTestIsPlanarNotPerAxis() {
        // 0.015 on each axis is 0.021 in the plane — above the epsilon even though each axis is below.
        assertFalse(new RestBot().standing().vel(0.015, 0.015).atRest());
    }

    // ---- fluid / climbable media: today's plan-anchor semantics, unchanged ----------------------------

    @Test
    void aBobbingSwimmerIsAtRestRegardlessOfSpeed() {
        // Water is exempt (§10 U5): fluid drag arrests carry, and FLOWING water pushes the bot forever —
        // a speed test there would deadlock the planner. The buoyancy bob must never defer a launch.
        RestBot bot = new RestBot().vel(0.2, 0.1);
        bot.water = true;
        assertTrue(bot.atRest());
    }

    @Test
    void aLavaBorneBotIsAtRestImmediately() {
        // Existing owner ruling: a lava-borne bot plans its escape NOW, not after settling.
        RestBot bot = new RestBot().vel(0.1, 0);
        bot.lava = true;
        assertTrue(bot.atRest());
    }

    @Test
    void aClimbableHangIsAtRest() {
        RestBot bot = new RestBot().vel(0, 0);
        bot.climbable = true;
        assertTrue(bot.atRest(), "a hang has always serviced handoffs — deferring slides the bot down");
    }

    // ---- ballistic ------------------------------------------------------------------------------------

    @Test
    void aBallisticBotIsNeverAtRest() {
        assertFalse(new RestBot().vel(0, 0).atRest(),
                "airborne with zero horizontal speed is still not an anchor");
    }
}
