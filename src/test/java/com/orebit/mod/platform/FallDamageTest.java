package com.orebit.mod.platform;

import org.junit.jupiter.api.Test;

import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.movements.Parkour;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins {@link FallDamage} — the vanilla fall-damage rule the planner prices drops against.
 *
 * <p><b>This test runs against whichever overlay flavour is composed for the ACTIVE MC version</b>
 * ({@code ceil} below 1.21.5, {@code floor} at and above), so everything asserted here is deliberately
 * a property BOTH flavours satisfy. The era-specific fractional behaviour — a 3.5-block fall costs 1 HP
 * pre-1.21.5 and nothing after — is documented on the two overlay copies and is not assertable from a
 * single test run without a version predicate the test source set does not have.
 *
 * <p>The load-bearing case is {@link #parkourApexReachesTheDamageTerm()}: before the apex correction the
 * falling-parkour damage term was unreachable ({@code dr ≤ FALL_DEPTH == 3} tested against
 * {@code safeFall == 3}), so every falling parkour priced as free while the bot took real damage.
 */
class FallDamageTest {

    /** Vanilla's damage-free window, and the planner's default {@code BotCaps.safeFallDistance}. */
    private static final float SAFE = BotCaps.DEFAULT_SAFE_FALL;

    /**
     * At WHOLE-block distances {@code ceil(d − safe)} and {@code floor(d + ε − safe)} are equal, which is
     * exactly why the 1.21.5 rounding split is invisible to {@code Fall}/{@code WalkOff} (they measure
     * drops in cells). If this ever fails, whole-cell drops have started pricing differently per era and
     * every fall-bearing scenario needs re-baselining.
     */
    @Test
    void wholeBlockDropsPriceIdenticallyOnEveryVersion() {
        assertEquals(0, FallDamage.damageFor(3f, SAFE), "3-block fall is free in every version");
        assertEquals(1, FallDamage.damageFor(4f, SAFE), "4-block fall is the classic half-heart");
        assertEquals(2, FallDamage.damageFor(5f, SAFE));
        assertEquals(13, FallDamage.damageFor(16f, SAFE), "the maxFallDistance rung");
    }

    /** Nothing at or under the safe window costs anything, on either flavour. */
    @Test
    void theSafeWindowIsFree() {
        for (float d = 0f; d <= 3f; d += 0.125f) {
            assertEquals(0, FallDamage.damageFor(d, SAFE), "fall of " + d + " must be free");
        }
    }

    /** Vanilla gates its {@code hurt} call on a positive result; the seam must clamp, never go negative. */
    @Test
    void neverNegative() {
        assertEquals(0, FallDamage.damageFor(0f, SAFE));
        assertEquals(0, FallDamage.damageFor(0.5f, 64f));
    }

    /** An invulnerable bot's window swallows any drop the planner can express. */
    @Test
    void immuneCapsTakeNoDamage() {
        assertEquals(0, FallDamage.damageFor(100f, BotCaps.IMMUNE_FALL));
        assertEquals(0, FallDamage.damageFor(4096f, BotCaps.IMMUNE_FALL));
    }

    /** Deeper can never be cheaper — the search relies on this to prefer the gentler of two drops. */
    @Test
    void monotonicInDistance() {
        int previous = 0;
        for (float d = 0f; d <= 40f; d += 0.0625f) {
            int hp = FallDamage.damageFor(d, SAFE);
            assertTrue(hp >= previous, "damage dropped from " + previous + " to " + hp + " at d=" + d);
            previous = hp;
        }
    }

    /**
     * THE REGRESSION GUARD. A falling parkour's real fall distance is its drop PLUS the jump apex, and at
     * the deepest row the falling class offers ({@code drop 3}) that has to land inside the damage term on
     * every version — 2 HP pre-1.21.5, 1 HP after. Pricing the bare drop instead put it at exactly the
     * safe window and the cost silently vanished.
     */
    @Test
    void parkourApexReachesTheDamageTerm() {
        assertTrue(FallDamage.damageFor(3 + Parkour.JUMP_APEX, SAFE) >= 1,
                "a drop-3 parkour really falls " + (3 + Parkour.JUMP_APEX) + " blocks and must cost HP");
    }

    /**
     * ...and the correction must not overshoot into the shallow rows: a drop-1 parkour falls ≈2.25 blocks,
     * comfortably inside the safe window, and a flat jump ≈1.25. Both stay free on both flavours, so the
     * fix cannot have made ordinary parkour expensive.
     */
    @Test
    void shallowAndFlatJumpsStayFree() {
        assertEquals(0, FallDamage.damageFor(Parkour.JUMP_APEX, SAFE), "a flat jump must stay free");
        assertEquals(0, FallDamage.damageFor(1 + Parkour.JUMP_APEX, SAFE), "drop-1 parkour must stay free");
    }
}
