package com.orebit.mod.worldmodel.hpa;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.MiningModel;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.worldmodel.navblock.NavBlock;

/**
 * Headless unit tests for the #5 invalidation-memory primitives: the pure {@link RegionCrossingMemory} store
 * (per-level directed crossings + sig tag, antichain compaction, accessors) and the {@link BotCaps}
 * realizability signature + dominance test that decides whether a remembered dead crossing binds a later bot
 * — including the Phase 1 EFFECTIVE sig ({@code realizabilitySig(inv)}: effective-place + per-category tool
 * tiers, DESIGN-persisted-invalidation-memory.md §3).
 *
 * <p>Pure POJO — no {@code Bootstrap}/{@code NavSection}, no live level ({@code MiningModel.snapshot} and the
 * sig math are plain array/bit work; {@code NavBlock.Tool} is a nested enum whose init does not trigger
 * {@code NavBlock}'s block interning). The end-to-end record/seed behaviour through a real cascade is pinned
 * in {@code HierarchicalCascadeTest}; this pins the mechanical pieces.
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

    /** An InventoryView with the given per-{@link NavBlock.Tool}-category tier ordinals + placement state. */
    private static MovementContext.InventoryView inv(int[] tiersByCategory, boolean consumesBlocks,
                                                     int placeableBlocks) {
        return new MovementContext.InventoryView(
                MiningModel.snapshot(tiersByCategory, 255, true), consumesBlocks, placeableBlocks, 0f, 0f, 0f,
                0);
    }

    /** Bare-hand tier array (every category at {@link MiningModel.Tier#BARE}). */
    private static int[] bareTiers() {
        return new int[NavBlock.Tool.values().length];
    }

    /** Bare hands except one category at the given {@link MiningModel.Tier} ordinal. */
    private static int[] tiersWith(NavBlock.Tool category, MiningModel.Tier tier) {
        int[] t = bareTiers();
        t[category.ordinal()] = tier.ordinal();
        return t;
    }

    // ===================================================================================================
    // Store — antichain compaction (record) + accessors.
    // ===================================================================================================

    @Test
    void recordDedupesExactTriple() {
        RegionCrossingMemory mem = new RegionCrossingMemory();
        long sig = walkOnly().realizabilitySig();
        mem.record(0, 111L, 222L, sig, RegionCrossingMemory.PROV_PROOF, BotCaps::sigDominates);
        mem.record(0, 111L, 222L, sig, RegionCrossingMemory.PROV_PROOF, BotCaps::sigDominates); // equal dominates ⇒ the skip rule dedups
        assertEquals(1, mem.count(0));
        assertEquals(1, mem.total());
        assertEquals(111L, mem.fromAt(0, 0));
        assertEquals(222L, mem.toAt(0, 0));
        assertEquals(sig, mem.sigAt(0, 0));
    }

    @Test
    void record_newRowDominatedByExisting_isSkipped() {
        RegionCrossingMemory mem = new RegionCrossingMemory();
        long strong = breakPlace().realizabilitySig();
        long weak = walkOnly().realizabilitySig();
        mem.record(0, 111L, 222L, strong, RegionCrossingMemory.PROV_PROOF, BotCaps::sigDominates);
        mem.record(0, 111L, 222L, weak, RegionCrossingMemory.PROV_PROOF, BotCaps::sigDominates); // covered: the strong row binds every bot weak would
        assertEquals(1, mem.count(0));
        assertEquals(strong, mem.sigAt(0, 0));
    }

    @Test
    void record_strictlyDominatingNewRow_replacesExisting() {
        RegionCrossingMemory mem = new RegionCrossingMemory();
        long weak = walkOnly().realizabilitySig();
        long strong = breakPlace().realizabilitySig();
        mem.record(0, 111L, 222L, weak, RegionCrossingMemory.PROV_PROOF, BotCaps::sigDominates);
        mem.record(0, 111L, 222L, strong, RegionCrossingMemory.PROV_PROOF, BotCaps::sigDominates); // strictly dominates → replace in place
        assertEquals(1, mem.count(0));
        assertEquals(strong, mem.sigAt(0, 0));
    }

    @Test
    void record_incomparableSigs_coexist() {
        // A: can break, low jump. B: cannot break, high jump. Neither dominates → two incomparable proofs.
        long a = new BotCaps(1, 3, 16, true, 100.0f, true, false, 255, false, 10000, 2.0f).realizabilitySig();
        long b = new BotCaps(5, 3, 16, true, 100.0f, false, false, 255, false, 10000, 2.0f).realizabilitySig();
        RegionCrossingMemory mem = new RegionCrossingMemory();
        mem.record(0, 111L, 222L, a, RegionCrossingMemory.PROV_PROOF, BotCaps::sigDominates);
        mem.record(0, 111L, 222L, b, RegionCrossingMemory.PROV_PROOF, BotCaps::sigDominates);
        assertEquals(2, mem.count(0));
    }

    @Test
    void record_dominatorCompactsEveryDominatedRow() {
        // Two incomparable rows, then a third dominating BOTH (break + high jump) → one row remains.
        long a = new BotCaps(1, 3, 16, true, 100.0f, true, false, 255, false, 10000, 2.0f).realizabilitySig();
        long b = new BotCaps(5, 3, 16, true, 100.0f, false, false, 255, false, 10000, 2.0f).realizabilitySig();
        long c = new BotCaps(5, 3, 16, true, 100.0f, true, false, 255, false, 10000, 2.0f).realizabilitySig();
        assertTrue(BotCaps.sigDominates(c, a) && BotCaps.sigDominates(c, b));
        RegionCrossingMemory mem = new RegionCrossingMemory();
        mem.record(0, 111L, 222L, a, RegionCrossingMemory.PROV_PROOF, BotCaps::sigDominates);
        mem.record(0, 111L, 222L, b, RegionCrossingMemory.PROV_PROOF, BotCaps::sigDominates);
        mem.record(0, 111L, 222L, c, RegionCrossingMemory.PROV_PROOF, BotCaps::sigDominates);
        assertEquals(1, mem.count(0));
        assertEquals(c, mem.sigAt(0, 0));
    }

    @Test
    void record_compactionIsPerCrossing_otherCrossingsUntouched() {
        RegionCrossingMemory mem = new RegionCrossingMemory();
        long weak = walkOnly().realizabilitySig();
        long strong = breakPlace().realizabilitySig();
        mem.record(0, 111L, 222L, weak, RegionCrossingMemory.PROV_PROOF, BotCaps::sigDominates);
        mem.record(0, 333L, 444L, weak, RegionCrossingMemory.PROV_PROOF, BotCaps::sigDominates);
        mem.record(0, 111L, 222L, strong, RegionCrossingMemory.PROV_PROOF, BotCaps::sigDominates); // replaces only its own crossing's row
        assertEquals(2, mem.count(0));
        boolean otherIntact = false;
        for (int i = 0; i < mem.count(0); i++) {
            if (mem.fromAt(0, i) == 333L && mem.toAt(0, i) == 444L && mem.sigAt(0, i) == weak) otherIntact = true;
        }
        assertTrue(otherIntact, "an unrelated crossing's row must survive another crossing's compaction");
    }

    @Test
    void perLevelStorageIsIndependent() {
        RegionCrossingMemory mem = new RegionCrossingMemory();
        long sig = walkOnly().realizabilitySig();
        mem.record(0, 1L, 2L, sig, RegionCrossingMemory.PROV_PROOF, BotCaps::sigDominates);
        mem.record(3, 5L, 6L, sig, RegionCrossingMemory.PROV_PROOF, BotCaps::sigDominates);
        mem.record(RegionAddress.MAX_COARSE_LEVEL, 7L, 8L, sig, RegionCrossingMemory.PROV_PROOF, BotCaps::sigDominates);
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
        for (int i = 0; i < 50; i++) mem.record(0, i, i + 1, sig, RegionCrossingMemory.PROV_PROOF, BotCaps::sigDominates);
        assertEquals(50, mem.count(0));
        assertEquals(49L, mem.fromAt(0, 49));
        assertEquals(50L, mem.toAt(0, 49));
    }

    // ===================================================================================================
    // Sig layout — caps-only axes (unchanged from increment B).
    // ===================================================================================================

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

    // ===================================================================================================
    // Sig layout — the Phase 1 EFFECTIVE sig (realizabilitySig(inv)): effective-place + tool tiers.
    // ===================================================================================================

    /** Bit 1 of the sig = effective canPlace (see the BotCaps layout note). */
    private static final long PLACE_BIT = 1L << 1;
    /** Bit 43 = RESERVED hasBreath — must never be set until the search models submersion budgets. */
    private static final long BREATH_BIT = 1L << 43;
    private static final int TIER_SHIFT = 44;
    private static final int TIER_BITS = 3;

    /** The sig's 3-bit tier field for a real tool category (ordinals 1..6, PICKAXE..SHEARS). */
    private static long tierField(long sig, NavBlock.Tool category) {
        return (sig >>> (TIER_SHIFT + (category.ordinal() - 1) * TIER_BITS)) & 0x7L;
    }

    @Test
    void sigTierFieldsCoverEveryRealToolCategory() {
        // The layout packs one 3-bit field per real category at bits 44..61 (NONE excluded — inert in the
        // mining formula). A new NavBlock.Tool category must extend the layout (+ the Phase 2 sigSchemaVersion).
        assertEquals(7, NavBlock.Tool.values().length,
                "sig layout assumes 6 real tool categories; extend the BotCaps tier fields for a new one");
    }

    @Test
    void effectivePlace_clearsOnlyWhenConsumingWithNoBlocks() {
        BotCaps caps = breakPlace(); // canPlace = true
        // Consuming placement + zero carried blocks: the search's own placement gate refuses every placement,
        // so the sig's place bit clears — the mislabeling increment B was dormant on (§3).
        assertEquals(0L, caps.realizabilitySig(inv(bareTiers(), true, 0)) & PLACE_BIT);
        // Carrying blocks, or non-consuming (conjured) placement: the raw capability stands.
        assertEquals(PLACE_BIT, caps.realizabilitySig(inv(bareTiers(), true, 5)) & PLACE_BIT);
        assertEquals(PLACE_BIT, caps.realizabilitySig(inv(bareTiers(), false, 0)) & PLACE_BIT);
        // Null inv (caps-only) keeps the raw bit; a can't-place bot never gets it.
        assertEquals(PLACE_BIT, caps.realizabilitySig() & PLACE_BIT);
        assertEquals(0L, walkOnly().realizabilitySig(inv(bareTiers(), false, 5)) & PLACE_BIT);
    }

    @Test
    void tierFields_packPerCategoryAtDocumentedPositions() {
        BotCaps caps = breakPlace();
        long pick = caps.realizabilitySig(inv(tiersWith(NavBlock.Tool.PICKAXE, MiningModel.Tier.IRON), false, 0));
        assertEquals(MiningModel.Tier.IRON.ordinal(), tierField(pick, NavBlock.Tool.PICKAXE));
        assertEquals(MiningModel.Tier.BARE.ordinal(), tierField(pick, NavBlock.Tool.AXE));
        long shears = caps.realizabilitySig(inv(tiersWith(NavBlock.Tool.SHEARS, MiningModel.Tier.GOLD), false, 0));
        assertEquals(MiningModel.Tier.GOLD.ordinal(), tierField(shears, NavBlock.Tool.SHEARS));
        // The two single-tool sigs differ only in their own category's field.
        assertEquals(0L, (pick ^ shears) & ~(0x7L << TIER_SHIFT)
                & ~(0x7L << (TIER_SHIFT + (NavBlock.Tool.SHEARS.ordinal() - 1) * TIER_BITS)));
    }

    @Test
    void tierFields_zeroedWhenCannotBreak() {
        // Mirrors the maxBreakHardness zeroing: a can't-break bot's carried tools can't fake capability —
        // its effective sig equals the caps-only sig exactly (tools change nothing it may do).
        BotCaps caps = walkOnly(); // canBreak = false
        long withTools = caps.realizabilitySig(inv(tiersWith(NavBlock.Tool.PICKAXE, MiningModel.Tier.DIAMOND), false, 5));
        assertEquals(caps.realizabilitySig(), withTools);
    }

    @Test
    void hasBreathBit_reservedAndNeverSet() {
        // Bit 43 is RESERVED for hasBreath (§2: no sig bit for an unmodeled constraint). Even a maxed-out
        // caps + inventory sig must leave it clear.
        BotCaps maxed = new BotCaps(15, 8191, 8191, false, 100.0f, true, true, 255, true, 10000, 2.0f, true);
        int[] allGold = bareTiers();
        for (NavBlock.Tool t : NavBlock.Tool.values()) {
            if (t != NavBlock.Tool.NONE) allGold[t.ordinal()] = MiningModel.Tier.GOLD.ordinal();
        }
        assertEquals(0L, maxed.realizabilitySig(inv(allGold, false, 64)) & BREATH_BIT);
        assertEquals(0L, maxed.realizabilitySig() & BREATH_BIT);
    }

    @Test
    void tierDominance_higherTierDominatesSameCategory() {
        BotCaps caps = breakPlace();
        long iron = caps.realizabilitySig(inv(tiersWith(NavBlock.Tool.PICKAXE, MiningModel.Tier.IRON), false, 0));
        long diamond = caps.realizabilitySig(inv(tiersWith(NavBlock.Tool.PICKAXE, MiningModel.Tier.DIAMOND), false, 0));
        assertTrue(BotCaps.sigDominates(diamond, iron));
        assertFalse(BotCaps.sigDominates(iron, diamond));
        assertTrue(BotCaps.sigDominates(iron, iron)); // equal tiers dominate each other
        // Any tooled sig dominates the bare-hand sig of the same caps (null-inv seed semantics).
        assertTrue(BotCaps.sigDominates(iron, caps.realizabilitySig()));
        assertFalse(BotCaps.sigDominates(caps.realizabilitySig(), iron));
    }

    @Test
    void tierDominance_crossCategoryIndependence() {
        // A: diamond pick, bare axe. B: bare pick, diamond axe. Incomparable — each is worse on one field.
        BotCaps caps = breakPlace();
        long a = caps.realizabilitySig(inv(tiersWith(NavBlock.Tool.PICKAXE, MiningModel.Tier.DIAMOND), false, 0));
        long b = caps.realizabilitySig(inv(tiersWith(NavBlock.Tool.AXE, MiningModel.Tier.DIAMOND), false, 0));
        assertFalse(BotCaps.sigDominates(a, b));
        assertFalse(BotCaps.sigDominates(b, a));
    }

    @Test
    void tierDominance_crossFieldWithNumericAxes() {
        // A: better pick, lower jump. B: worse pick, higher jump. Incomparable — a tier field must not leak
        // into (or be masked by) the caps-numeric fields below bit 43.
        long a = new BotCaps(1, 3, 16, true, 100.0f, true, false, 255, false, 10000, 2.0f)
                .realizabilitySig(inv(tiersWith(NavBlock.Tool.PICKAXE, MiningModel.Tier.DIAMOND), false, 0));
        long b = new BotCaps(5, 3, 16, true, 100.0f, true, false, 255, false, 10000, 2.0f)
                .realizabilitySig(inv(tiersWith(NavBlock.Tool.PICKAXE, MiningModel.Tier.WOOD), false, 0));
        assertFalse(BotCaps.sigDominates(a, b));
        assertFalse(BotCaps.sigDominates(b, a));
    }

    // ===================================================================================================
    // Regression guard — B's oracle-validated behaviour unchanged in the default config.
    // ===================================================================================================

    @Test
    void defaultConfig_effectiveSigMatchesCapsOnlySigOnOverlappingFields() {
        // The default config: consumesBlocks=false, full break+place caps. The effective sig must equal the
        // old caps-only sig on every pre-existing field (bits 0..42) — only the new tier fields may differ —
        // so increment B's goal-shuffle-oracle-validated dominance behaviour is unchanged at defaults (§5).
        BotCaps caps = breakPlace();
        long capsOnly = caps.realizabilitySig();
        long effective = caps.realizabilitySig(inv(tiersWith(NavBlock.Tool.PICKAXE, MiningModel.Tier.DIAMOND), false, 0));
        long overlap = (1L << 43) - 1; // bits 0..42 — the entire increment-B layout
        assertEquals(capsOnly & overlap, effective & overlap);
        // And with a bare-hand snapshot the two sigs are IDENTICAL (null inv ≡ bare hands, no consuming).
        assertEquals(capsOnly, caps.realizabilitySig(inv(bareTiers(), false, 0)));
    }
}
