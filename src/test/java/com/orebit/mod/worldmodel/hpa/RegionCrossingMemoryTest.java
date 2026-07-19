package com.orebit.mod.worldmodel.hpa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;

/**
 * Headless unit tests for the #5 invalidation-memory primitives: the pure {@link RegionCrossingMemory} store
 * (per-level directed crossings + caps tag, dedup, accessors) and the {@link BotCaps} realizability signature
 * + dominance test that decides whether a remembered dead crossing binds a later bot.
 *
 * <p>Pure POJO — no {@code Bootstrap}/{@code NavSection}, no live level. The end-to-end seed-into-a-plan
 * behaviour (a fresh {@code HierarchicalRegionPlan} avoiding a remembered crossing) is exercised by the
 * in-game gather oracle, not here; this pins the mechanical pieces.
 */
class RegionCrossingMemoryTest {

    // A mortal walk-only bot: no break, no place, no door-toggle, takes damage, vanilla fall window.
    private static BotCaps walkOnly() {
        return new BotCaps(1, 3, 16, true, 100.0f, false, false, 255, false, 10000, 2.0f);
    }

    // Same but with break + place — strictly more capable on the break/place axes.
    private static BotCaps breakPlace() {
        return new BotCaps(1, 3, 16, true, 100.0f, true, true, 255, false, 10000, 2.0f);
    }

    // A damage-immune bot: no fall limit, no damage — strictly more capable across hazards/falls.
    private static BotCaps immune() {
        return new BotCaps(1, 4096, 4096, false, 100.0f, false, false, 255, false, 10000, 2.0f);
    }

    @Test
    void recordDedupesExactTriple() {
        RegionCrossingMemory mem = new RegionCrossingMemory();
        long sig = walkOnly().realizabilitySig();
        mem.record(0, 111L, 222L, sig);
        mem.record(0, 111L, 222L, sig); // exact duplicate → no-op
        assertEquals(1, mem.count(0));
        assertEquals(1, mem.total());
        assertEquals(111L, mem.fromAt(0, 0));
        assertEquals(222L, mem.toAt(0, 0));
        assertEquals(sig, mem.sigAt(0, 0));
    }

    @Test
    void differentCapsSameCrossingKeptSeparately() {
        RegionCrossingMemory mem = new RegionCrossingMemory();
        mem.record(0, 111L, 222L, walkOnly().realizabilitySig());
        mem.record(0, 111L, 222L, breakPlace().realizabilitySig()); // different failing caps = distinct fact
        assertEquals(2, mem.count(0));
    }

    @Test
    void perLevelStorageIsIndependent() {
        RegionCrossingMemory mem = new RegionCrossingMemory();
        long sig = walkOnly().realizabilitySig();
        mem.record(0, 1L, 2L, sig);
        mem.record(3, 5L, 6L, sig);
        mem.record(RegionAddress.MAX_COARSE_LEVEL, 7L, 8L, sig);
        assertEquals(1, mem.count(0));
        assertEquals(0, mem.count(1));
        assertEquals(1, mem.count(3));
        assertEquals(1, mem.count(RegionAddress.MAX_COARSE_LEVEL));
        assertEquals(3, mem.total());
    }

    @Test
    void growthPastInitialCapacity() {
        RegionCrossingMemory mem = new RegionCrossingMemory();
        long sig = walkOnly().realizabilitySig();
        for (int i = 0; i < 50; i++) mem.record(0, i, i + 1, sig);
        assertEquals(50, mem.count(0));
        assertEquals(49L, mem.fromAt(0, 49));
        assertEquals(50L, mem.toAt(0, 49));
    }

    @Test
    void distinctCapsProduceDistinctSignatures() {
        assertNotEquals(walkOnly().realizabilitySig(), breakPlace().realizabilitySig());
        assertNotEquals(walkOnly().realizabilitySig(), immune().realizabilitySig());
    }

    @Test
    void equalCapsDominateEachOther() {
        long a = walkOnly().realizabilitySig();
        // an invalidation recorded by an identical bot binds this bot
        assertTrue(BotCaps.sigDominates(a, a));
    }

    @Test
    void strongerCapsDominateWeaker_notViceVersa() {
        long weak = walkOnly().realizabilitySig();
        long strong = breakPlace().realizabilitySig();
        // strong ≥ weak on every axis → a weak-bot failure binds... no: the RECORDER must dominate the bot.
        // sigDominates(recorder, me): a break+place bot's failure (strong) binds a walk-only bot (weak).
        assertTrue(BotCaps.sigDominates(strong, weak));
        // a walk-only bot's failure must NOT bind a stronger break+place bot (it may succeed).
        assertFalse(BotCaps.sigDominates(weak, strong));
    }

    @Test
    void immuneDominatesMortal_notViceVersa() {
        long mortal = walkOnly().realizabilitySig();
        long immune = immune().realizabilitySig();
        assertTrue(BotCaps.sigDominates(immune, mortal));
        assertFalse(BotCaps.sigDominates(mortal, immune));
    }

    @Test
    void incomparableCapsDominateNeitherWay() {
        // A: can break, but low jump (1). B: cannot break, but high jump (5). Neither ≥ the other on all axes.
        long a = new BotCaps(1, 3, 16, true, 100.0f, true, false, 255, false, 10000, 2.0f).realizabilitySig();
        long b = new BotCaps(5, 3, 16, true, 100.0f, false, false, 255, false, 10000, 2.0f).realizabilitySig();
        assertFalse(BotCaps.sigDominates(a, b)); // a worse on jump
        assertFalse(BotCaps.sigDominates(b, a)); // b worse on canBreak
    }
}
