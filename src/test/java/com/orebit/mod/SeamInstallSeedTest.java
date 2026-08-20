package com.orebit.mod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan;
import com.orebit.mod.pathfinding.blockpathfinder.Movement;
import com.orebit.mod.pathfinding.blockpathfinder.MovementRegistry;
import com.orebit.mod.pathfinding.blockpathfinder.StepEdits;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;

/**
 * The window-swap install seed ({@link BotNavigator#installSeed}, DESIGN-replan-handoff.md §5, R2/R3):
 * the cursor, step-0 frame anchor, and outgoing-phase disposition a freshly-installed plan starts the
 * follower from. Pins the fix for the 2026-08-19 run-5 forensic — a FAST_FORWARD seam adoption installed
 * a plan searched from a stale start one step behind the bot, and the old inline install (cursor 0,
 * anchor at the adoption-time bot floor, outgoing phase left running) re-ran the already-executed Pillar
 * step 0 from a +1-shifted frame, whose place phase filled the bot's own foot cell (the silent, permanent
 * entombment wedge — Pillar carries no failWhen). The seed must instead:
 *
 * <ul>
 *   <li>start the cursor PAST the matched body step on a FAST_FORWARD ({@code matchedIndex + 1} — the
 *       bot already stands on that step's floor, its prefix is history);</li>
 *   <li>anchor {@code planStartFloor} on the plan's TRUE search start ({@code PathPlan.blockPlanStart()}),
 *       never the adoption-time bot floor (for cursor {@code > 0} the from-floor comes from
 *       {@code path.waypoint(cursor - 1)} anyway, and for cursor 0 the R2/ADOPT geometry makes the two
 *       agree on a ground anchor — the anchor only DIFFERS where the old choice was wrong);</li>
 *   <li>drop the outgoing step's phase plan on EVERY install ({@code clearPhase}) — the plan it belonged
 *       to is gone, and left active it suppresses the reached-scan via {@code phaseOwnsCompletion}
 *       (see {@code SeamInstallPhaseSuppressionTest}).</li>
 * </ul>
 *
 * <p>Scene (all floor cells): seam/search start {@code S=(10,64,10)}; plan body floors
 * {@code k0=(9,64,10)} (step 0 = Pillar), {@code k1=(8,64,10)}, {@code k2=(8,64,11)}.
 */
class SeamInstallSeedTest {

    private static boolean bootstrapped;

    @BeforeAll
    static void boot() {
        if (!bootstrapped) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            bootstrapped = true;
        }
    }

    private static final BlockPos S = new BlockPos(10, 64, 10);   // the seam — the plan's search start
    private static final BlockPos K0 = new BlockPos(9, 64, 10);   // step-0 floor (step 0 = Pillar)
    private static final BlockPos K1 = new BlockPos(8, 64, 10);   // step-1 floor

    /** The installed plan over stand (feet) cells one above the body floors; step 0 is the Pillar the
     *  run-5 wedge re-ran. The convenience ctor derives each floor as {@code waypoint.below()}. */
    private static BlockPathPlan plan() {
        BlockPos[] stands = { K0.above(), K1.above(), new BlockPos(8, 65, 11) };
        Movement[] moves = { MovementRegistry.PILLAR, MovementRegistry.TRAVERSE,
                MovementRegistry.TRAVERSE };
        return new BlockPathPlan(Arrays.asList(stands), Arrays.asList(moves),
                Arrays.asList(new StepEdits[stands.length]), 0f);
    }

    @Test
    void aFastForwardMatchStartsTheCursorPastTheMatchedStep() {
        // A1: the bot stands on step 0's floor (k0) — that step already EXECUTED; the cursor enters at 1.
        BotNavigator.InstallSeed seed = BotNavigator.installSeed(plan(), S, K0, 0, false);
        assertEquals(1, seed.cursor(),
                "matched=0 means step 0 is history — cursor 1, never re-run the executed Pillar");
    }

    @Test
    void aFastForwardMatchAnchorsOnThePlansTrueSearchStart() {
        // A2: the frame anchor is the plan's implicit step -1 (S), NOT the bot floor (k0) — the old
        // botFloor anchor is exactly the +1-shifted Pillar frame of the run-5 wedge.
        BotNavigator.InstallSeed seed = BotNavigator.installSeed(plan(), S, K0, 0, false);
        assertEquals(S, seed.anchorFloor(),
                "planStartFloor must be the searched-from cell, not the adoption-time bot floor");
    }

    @Test
    void anAdoptAtTheSeamSeedsCursorZeroAnchoredAtTheSeam() {
        // A3: the R2/ADOPT geometry — bot AT the seam, no body match. Cursor 0; anchor S (== the live
        // floor here, so the anchor choice is behavior-identical for ADOPT and only FIXES fast-forward).
        BotNavigator.InstallSeed seed = BotNavigator.installSeed(plan(), S, S, -1, false);
        assertEquals(0, seed.cursor(), "at the seam the plan runs from its step 0");
        assertEquals(S, seed.anchorFloor(), "at the seam the search start IS the bot floor");
    }

    @Test
    void aDeeperMatchSkipsTheWholeExecutedPrefix() {
        // A4: standing on step 1's floor skips steps 0 AND 1 — cursor matchedIndex + 1 = 2.
        BotNavigator.InstallSeed seed = BotNavigator.installSeed(plan(), S, K1, 1, false);
        assertEquals(2, seed.cursor(), "matched=1 enters at step 2 — the executed prefix never re-runs");
    }

    @Test
    void anOffPlanBotFloorSeedsCursorZero() {
        // A5: no body match (the RESULT/RETRY-recovered installs) — cursor 0, the plan runs whole.
        BotNavigator.InstallSeed seed =
                BotNavigator.installSeed(plan(), S, new BlockPos(12, 64, 12), -1, false);
        assertEquals(0, seed.cursor(), "no match — nothing of the plan is history");
    }

    @Test
    void everyInstallDropsTheOutgoingPhase() {
        // A7: clearPhase on EVERY arm — the outgoing step's phase plan belongs to the REPLACED plan, and
        // left active it suppresses the reached-scan (phaseOwnsCompletion) under the new plan's cursor.
        assertTrue(BotNavigator.installSeed(plan(), S, K0, 0, false).clearPhase(),
                "fast-forward install must clear the outgoing phase");
        assertTrue(BotNavigator.installSeed(plan(), S, S, -1, false).clearPhase(),
                "adopt-at-seam install must clear the outgoing phase");
        assertTrue(BotNavigator.installSeed(plan(), S, new BlockPos(12, 64, 12), -1, false).clearPhase(),
                "off-plan install must clear the outgoing phase");
        assertTrue(BotNavigator.installSeed(null, S, S, -1, false).clearPhase(),
                "even a BLOCKED (null-plan) install clears — no plan may own a dead step");
    }

    @Test
    void aPanicConsummationNullInstallAnchorsAtTheSettledFloor() {
        // A8 (§11, owner ruling 2026-08-20): the PANIC consummation finishes the committed move
        // centered, THEN drops — the null install that follows seeds cursor 0 anchored at the bot's
        // settled floor (nothing to frame; the rest-gated planless machinery owns the relaunch). The
        // botFloor argument here is the terminal move's LANDING — under §11 the install boundary is the
        // execution edge, so the settled floor and the landing are the same cell by construction.
        BotNavigator.InstallSeed seed = BotNavigator.installSeed(null, S, K1, -1, false);
        assertEquals(0, seed.cursor(), "a null install has no plan to enter");
        assertEquals(K1, seed.anchorFloor(), "anchored at the settled (landing) floor");
        assertTrue(seed.clearPhase(), "the dropped plan's phase dies with it");
    }
}
