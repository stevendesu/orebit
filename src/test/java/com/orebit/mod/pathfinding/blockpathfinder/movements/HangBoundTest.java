package com.orebit.mod.pathfinding.blockpathfinder.movements;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Re-derives the fall-arrest tunneling bound from the exact vanilla fall recurrence and asserts
 * {@link Fall#HANG_MAX_DROP} sits safely inside it (NOTES-movement-physics.md §3).
 *
 * <p>Physics being asserted: per airborne tick the stored vertical speed updates {@code v' = (v + 0.08)
 * × 0.98} (gravity then drag — LivingEntity.travelInAir) and the tick's displacement is {@code v'};
 * vanilla samples the feet cell once per tick (the climbable arrest clamp runs pre-move on the feet's
 * CURRENT cell), so a fall step under 1.0 b/t can never skip a 1-cell climbable, and the bound is the
 * last cumulative fall distance whose next step is still under 1.0. Longer-run relaxations (a 2-cell
 * run arrests to ≈40 blocks, a ≥4-run from any height — terminal velocity is 3.92) are deliberately
 * NOT shipped (owner ruling 2026-07-31: the deep-column sweeps they need cost more than the rare case
 * is worth), so only the 1-cell crossing is load-bearing here.
 */
class HangBoundTest {

    @Test
    void boundSitsSafelyUnderTheExactCrossing() {
        // The largest cumulative fall distance D such that ANY fall of D blocks crosses a plane below it
        // at under 1.0 b/t: cumulative distance through the LAST step whose successor is still < 1.0.
        double v = 0, dist = 0, maxDistUnder1 = -1;
        for (int t = 0; t < 1000; t++) {
            double next = (v + 0.08) * 0.98;
            if (next >= 1.0) { maxDistUnder1 = dist; break; } // distance BEFORE the first ≥ 1.0 step
            v = next;
            dist += v;
        }
        assertTrue(maxDistUnder1 > 0, "the recurrence must cross 1.0 b/t");
        // Half a block of margin absorbs float drift and fractional entry planes.
        assertTrue(Fall.HANG_MAX_DROP <= maxDistUnder1 - 0.5,
                "HANG_MAX_DROP " + Fall.HANG_MAX_DROP + " must sit ≥0.5 under the exact crossing "
                        + maxDistUnder1);
    }
}
