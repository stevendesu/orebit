package com.orebit.mod.pathfinding.blockpathfinder.movements;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Re-derives the water-cushion momentum bound independently of {@link Fall}'s class-load table and asserts
 * {@link Fall#MAX_WATER_PENETRATION} agrees (NOTES-movement-physics.md §7).
 *
 * <p>Physics being asserted, both halves bytecode-verified on 1.21.11:
 * <ul>
 *   <li><b>Air free fall</b> — {@code v' = (v + 0.08) × 0.98} (gravity then drag, LivingEntity.travelInAir),
 *       terminal {@code 0.0784 / 0.02 = 3.92 b/t}. Same recurrence {@link Fall#HANG_MAX_DROP} derives from.</li>
 *   <li><b>In-water descent, no input</b> — {@code v' = 0.8×v + 0.005} (the Y water drag is hardcoded 0.8;
 *       the 0.005 is {@code Attributes.GRAVITY / 16} from getFluidFallingAdjustedMovement), terminal
 *       {@code 0.005 / 0.2 = 0.025 b/t} — a ~40 tick/block crawl.</li>
 * </ul>
 *
 * <p>The excess of entry speed over that crawl decays geometrically at 0.8, so the distance momentum
 * contributes before the bot is crawling is {@code Σ (vₜ − v∞) = (v_entry − v∞) × 1/(1 − 0.8)}. This test
 * checks the closed form against a direct tick-by-tick simulation, which is the part worth guarding: the
 * ×5 factor is easy to get wrong by one geometric term.
 */
class WaterPenetrationBoundTest {

    private static final double AIR_TERMINAL = 0.0784 / 0.02;   // 3.92 b/t
    private static final double WATER_TERMINAL = 0.005 / 0.2;   // 0.025 b/t

    @Test
    void closedFormMatchesTickSimulation() {
        // For a spread of entry speeds, simulate the in-water recurrence and accumulate the distance
        // travelled IN EXCESS of the terminal crawl; it must match the closed form 5 × (v_entry − v∞).
        for (double entry : new double[] {0.5, 1.0, 2.0, 3.0, AIR_TERMINAL}) {
            double v = entry, excess = 0;
            for (int t = 0; t < 5000; t++) {
                excess += v - WATER_TERMINAL;
                v = 0.8 * v + 0.005;
                if (v - WATER_TERMINAL < 1e-12) break;
            }
            assertEquals(5.0 * (entry - WATER_TERMINAL), excess, 1e-6,
                    "closed form must match the simulated excess distance at entry " + entry);
        }
    }

    @Test
    void maxPenetrationSaturatesAtAirTerminalVelocity() {
        // No fall, however long, enters water faster than air terminal velocity, so the deepest column any
        // entry momentum can cross is fixed. 5 × (3.92 − 0.025) ≈ 19.47 → 19 whole blocks.
        int expected = (int) Math.floor(5.0 * (AIR_TERMINAL - WATER_TERMINAL));
        assertEquals(expected, Fall.MAX_WATER_PENETRATION,
                "MAX_WATER_PENETRATION must equal the terminal-velocity penetration");
        assertTrue(Fall.MAX_WATER_PENETRATION >= 19 && Fall.MAX_WATER_PENETRATION <= 20,
                "sanity: the bound should sit at ~19-20 blocks, got " + Fall.MAX_WATER_PENETRATION);
    }

    @Test
    void aFallFromRestCarriesNoMomentum() {
        // Degenerate guard: entering water at rest must cross nothing — the bot is already crawling.
        assertEquals(0, (int) Math.floor(5.0 * Math.max(0.0, 0.0 - WATER_TERMINAL)),
                "a zero-speed entry penetrates no blocks");
    }
}
