package com.orebit.mod.pathfinding.regionpathfinder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.MiningModel;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.worldmodel.hpa.RegionAddress;
import com.orebit.mod.worldmodel.hpa.RegionCrossingMemory;
import com.orebit.mod.worldmodel.hpa.RegionGrid;
import com.orebit.mod.worldmodel.navblock.NavBlock;

import net.minecraft.core.BlockPos;

/**
 * Unit tests for the region-tier <b>nested-skeleton cascade</b> ({@link HierarchicalRegionPlan}, HPA-CASCADE.md
 * §13). Like {@link RegionPathfinderFragmentTest} these need <b>no Minecraft</b>: a {@link RegionGrid#headless
 * headless} grid with nothing seeded reads the §6 optimistic AIR default everywhere, so each per-level search
 * beelines toward its (clamped) sub-goal — exactly the long-range "travel then refine" path the cascade is for.
 * The cascade is exercised directly (its {@code build}/{@code onBotMoved}/{@code onBlocked}); the
 * {@link com.orebit.mod.pathfinding.PathPlan} block-window integration is covered in-game (it needs a live
 * level).
 *
 * <p>Coverage (HPA-CASCADE §13): stack consistency, selective re-plan (the amortization guarantee — crossing a
 * level-L cell window re-plans levels ≤ L only), collapse-on-approach (top level shrinks), and blocked-hop
 * escalation/repair. Distances are chosen so the cap-safe top level is 2 (an L0/L1/L2 stack):
 * {@code maxCheb(0)=8, maxCheb(1)=11, maxCheb(2)=16} cells, so a goal ~1000 blocks (≈63 L0 / 31 L1 / 15 L2
 * cells) away picks level 2.
 */
public class HierarchicalCascadeTest {

    private RegionGrid grid;
    private static final BotCaps CAPS = BotCaps.BREAK_PLACE;

    @BeforeEach
    void setUp() {
        grid = RegionGrid.headless(0); // minY = 0
    }

    /** A goal ~1000 blocks +X (cap-safe top level 2). */
    private static BlockPos farGoalX() {
        return new BlockPos(8 + 1000, 4, 8);
    }

    // ===================================================================================================
    // Build — the top-down descent yields a level-0 segment that progresses toward a far goal.
    // ===================================================================================================
    @Test
    void build_longRange_returnsProgressingL0Segment() {
        BlockPos bot = new BlockPos(8, 4, 8);
        HierarchicalRegionPlan h = HierarchicalRegionPlan.build(grid, 0, bot, farGoalX(), CAPS);

        assertTrue(h.topLevel() >= 1, "a ~1000-block goal must span more than the leaf level");
        assertEquals(2, h.topLevel(), "cap-safe top level for ~1000 blocks is 2");
        RegionPathPlan l0 = h.l0Skeleton();
        assertNotNull(l0, "the cascade must yield an L0 near segment, not null");
        assertTrue(l0.size() > 1, "the L0 segment is a real onward route, not a same-region stub");
        assertTrue(l0.isFragmentModel(), "the cascade plans the fragment model");
        assertEquals(0, l0.level(), "the bottom skeleton is level 0");
        assertEquals(0, l0.rx(0), "the L0 segment starts at the bot's region");
        assertTrue(l0.rx(l0.size() - 1) > l0.rx(0), "the L0 segment progresses toward the +X goal");
    }

    // ===================================================================================================
    // Stack consistency — every level 0..top has a level-tagged skeleton starting at the bot's cell.
    // ===================================================================================================
    @Test
    void build_stackConsistency() {
        BlockPos bot = new BlockPos(8, 4, 8);
        HierarchicalRegionPlan h = HierarchicalRegionPlan.build(grid, 0, bot, farGoalX(), CAPS);

        final int top = h.topLevel();
        for (int L = 0; L <= top; L++) {
            RegionPathPlan sk = h.skeletonAt(L);
            assertNotNull(sk, "level " + L + " must have a skeleton");
            assertEquals(L, sk.level(), "skeleton at level " + L + " must carry that level");
            assertTrue(sk.size() >= 1, "level " + L + " skeleton non-empty");
            // Each level's skeleton starts at the bot's cell AT THAT LEVEL.
            assertEquals(RegionAddress.regionX(bot.getX(), L), sk.rx(0), "level " + L + " starts at bot@L (x)");
            assertEquals(RegionAddress.regionZ(bot.getZ(), L), sk.rz(0), "level " + L + " starts at bot@L (z)");
        }
        assertNotNull(h.skeletonAt(top), "top level present");
    }

    // ===================================================================================================
    // Selective re-plan under the ROLLING SKELETON (DESIGN-rolling-skeleton.md §3, §13 increment A).
    // REWRITTEN from the static-head geometry this test previously pinned (per the
    // dont-weaken-model-for-outdated-test rule — the TEST moves, not the model, §11): the old assertions
    // ("L0 re-plans every 64 blocks; L1 re-plans at 128 blocks") pinned exactly the per-window re-derive
    // cadence rolling removes. Now: within the parent window, crossing L0 cells SLIDES the window (the
    // committed cursor IS the window base, §3.1) and EXTENDS the L0 skeleton by suffix-splice (§4) — it
    // never RE-DERIVES; the coarse levels re-plan only when genuinely consumed (`exhausted` retained
    // verbatim as the safety net — L1 at its TAIL under the slid window, L2 at its static head).
    // ===================================================================================================
    @Test
    void onBotMoved_selectiveReplan_fineBeforeCoarse() {
        // Beeline at SURFACE level (y≥63) — see the note on the §2 unbuilt cost prior: a flat surface-band
        // walk stays ON the skeleton, so this exercises the window math, not the routing choice.
        final int y = 70;
        HierarchicalRegionPlan h = HierarchicalRegionPlan.build(grid, 0, new BlockPos(8, y, 8),
                new BlockPos(8 + 1000, y, 8), CAPS);
        assertEquals(2, h.topLevel());

        RegionPathPlan l1Before = h.skeletonAt(1);
        RegionPathPlan l2Before = h.skeletonAt(2);

        // Phase 1 — walk +X through many L0 cells while every coarse window still contains the bot: rolling
        // slides/extends (verdict UNCHANGED or EXTENDED), never SWAPPED, and the coarse skeletons survive
        // untouched. (OLD pinned behavior: an L0 re-derive every 4 cells — the §1 waste class.)
        boolean sawExtended = false;
        for (int x = 24; x <= 248; x += 16) {
            HierarchicalRegionPlan.Verdict v = h.onBotMoved(new BlockPos(x, y, 8), false, 0);
            assertNotEquals(HierarchicalRegionPlan.Verdict.SWAPPED, v,
                    "x=" + x + ": in-window progress must slide/extend, never re-derive (rolling §3.1)");
            sawExtended |= v == HierarchicalRegionPlan.Verdict.EXTENDED;
        }
        assertTrue(sawExtended, "the clamping L0 window must have EXTENDED at least once (§3.2)");
        assertSame(l1Before, h.skeletonAt(1), "L1 must NOT re-plan while the bot is inside its (slid) window");
        assertSame(l2Before, h.skeletonAt(2), "L2 must NOT re-plan either");
        assertEquals(0, h.exhaustedFires(), "INV-4: the L0 safety net never fires on a healthy walk");
        assertEquals(0, h.degradedSettles(), "no extension may fail over open optimistic terrain");

        // Phase 2 — walk into the coarse windows' genuine consumption (L2's static window-far at ~256
        // blocks): the retained exhausted machinery re-plans the coarse suffix exactly as today (§3.1
        // "exhausted is retained verbatim"), and L1 is re-derived with it.
        boolean sawSwap = false;
        for (int x = 264; x <= 360; x += 16) {
            sawSwap |= h.onBotMoved(new BlockPos(x, y, 8), false, 0)
                    == HierarchicalRegionPlan.Verdict.SWAPPED;
        }
        assertTrue(sawSwap, "consuming the coarse windows must still re-derive (the safety net, §3.1)");
        assertNotSame(l1Before, h.skeletonAt(1), "the coarse re-derive replaces L1");
        assertEquals(0, h.exhaustedFires(),
                "the coarse-level exhaustion is not an L0 rolling failure — the L0 counter stays 0 (§9)");
    }

    // ===================================================================================================
    // On-route tolerance (the cliff flip-flop fix, s52 form) — an off-window bot whose BLOCK PLAN vouches
    // for the excursion (onRoute=true: the fall-lineup clip) must NOT re-derive; an off-window bot NOT on
    // its plan is a genuine deviation and re-derives immediately, however small the excursion.
    // (Replaced the old BOUNDARY_CLIP_CHEB "within 1 region counts as on it" spatial guess.)
    // ===================================================================================================
    @Test
    void onBotMoved_offWindowButOnPlan_doesNotReplan() {
        HierarchicalRegionPlan h = HierarchicalRegionPlan.build(grid, 0, new BlockPos(8, 4, 8), farGoalX(), CAPS);
        RegionPathPlan l0Before = h.l0Skeleton();

        // One L0 region laterally off the +X skeleton (rz 0→1 at z=24), with the block plan vouching for it
        // (onRoute=true). Re-deriving here reset the commit cursor and flipped the target every settle (the
        // cliff-edge flip-flop); it must be tolerated: no re-derive, L0 skeleton unchanged.
        boolean changed = h.onBotMoved(new BlockPos(8, 4, 8 + 16), true);
        assertFalse(changed, "an off-window step the block plan vouches for must NOT re-derive the L0 segment");
        assertSame(l0Before, h.l0Skeleton(), "the L0 skeleton is unchanged by an on-plan excursion");
    }

    @Test
    void onBotMoved_offWindowNotOnPlan_replans() {
        HierarchicalRegionPlan h = HierarchicalRegionPlan.build(grid, 0, new BlockPos(8, 4, 8), farGoalX(), CAPS);
        RegionPathPlan l0Before = h.l0Skeleton();

        // The same one-region excursion with NO plan vouching for it (onRoute=false): a genuine off-route
        // deviation — re-derive promptly.
        boolean changed = h.onBotMoved(new BlockPos(8, 4, 8 + 16), false);
        assertTrue(changed, "an off-window step with no plan vouching for it must re-derive the L0 segment");
        assertNotSame(l0Before, h.l0Skeleton(), "the L0 skeleton is re-derived from the deviated cell");
        // A genuine deviation whose replan SUCCEEDS blames nothing: the tube-escalation path engages only on a
        // tube-confined failure, so a clean re-derive leaves the crossing memory untouched.
        assertEquals(0, grid.crossingMemory().total(), "a successful deviation replan records no blame");
    }

    @Test
    void onBotMoved_farDeviation_stillReplans() {
        HierarchicalRegionPlan h = HierarchicalRegionPlan.build(grid, 0, new BlockPos(8, 4, 8), farGoalX(), CAPS);
        RegionPathPlan l0Before = h.l0Skeleton();

        // Three L0 regions off the skeleton (z=56), not on any plan — a genuine off-route deviation.
        boolean changed = h.onBotMoved(new BlockPos(8, 4, 8 + 48));
        assertTrue(changed, "a genuine deviation must re-derive the L0 segment");
        assertNotSame(l0Before, h.l0Skeleton(), "the L0 skeleton is re-derived from the deviated cell");
    }

    // ===================================================================================================
    // Collapse on approach — as the bot nears the goal the cap-safe top level decreases and the stack pops.
    // ===================================================================================================
    @Test
    void onBotMoved_collapse_topLevelShrinksNearGoal() {
        BlockPos goal = farGoalX();
        HierarchicalRegionPlan h = HierarchicalRegionPlan.build(grid, 0, new BlockPos(8, 4, 8), goal, CAPS);
        assertEquals(2, h.topLevel(), "far goal starts at top level 2");

        // The bot is now right next to the goal (within maxCheb(0)=8 cells): the top exits (deviated off the far
        // coarse window), the top is recomputed, and the stack collapses to a single level-0 plan.
        h.onBotMoved(new BlockPos(goal.getX() - 8, 4, goal.getZ()));
        assertEquals(0, h.topLevel(), "near the goal the stack collapses to the direct level-0 plan");
        assertNotNull(h.l0Skeleton(), "the collapsed plan still reaches the goal");
    }

    // ===================================================================================================
    // Escalation / repair — a blocked L0 hop is blacklisted and the cascade reroutes (online repair, §6).
    // ===================================================================================================
    @Test
    void onBlocked_reroutesAroundDeadHop() {
        BlockPos bot = new BlockPos(8, 4, 8);
        HierarchicalRegionPlan h = HierarchicalRegionPlan.build(grid, 0, bot, farGoalX(), CAPS);
        RegionPathPlan l0 = h.l0Skeleton();
        assertTrue(l0.size() > 1);

        // The block tier "proves" the first L0 hop unrealizable: blacklist (step0 → step1) and repair.
        long fromKey = RegionPathfinder.fragmentNodeKey(l0.rx(0), l0.ry(0), l0.rz(0), l0.fragmentId(0));
        long toKey = RegionPathfinder.fragmentNodeKey(l0.rx(1), l0.ry(1), l0.rz(1), l0.fragmentId(1));

        boolean ok = h.onBlocked(fromKey, toKey, bot);
        assertTrue(ok, "over open terrain the cascade must reroute around one dead hop, not give up");
        RegionPathPlan after = h.l0Skeleton();
        assertNotNull(after);
        assertTrue(after.size() > 1, "the rerouted L0 segment is still a real onward route");
        // The repaired route must not take the forbidden first hop again.
        boolean sameHop = after.rx(1) == l0.rx(1) && after.ry(1) == l0.ry(1) && after.rz(1) == l0.rz(1)
                && after.fragmentId(1) == l0.fragmentId(1);
        assertTrue(!sameHop, "the repaired route avoids the blacklisted first hop");
    }

    // ===================================================================================================
    // #5 crossing-memory record sites (DESIGN-persisted-invalidation-memory.md §3): the recorded sig is the
    // EFFECTIVE sig of the searches the plan runs (its per-replan inventory snapshot), and a null-inventory
    // plan (headless / trace / tests) never records at all.
    // ===================================================================================================

    /** The first L0 hop of the plan's skeleton, as the {@code (fromKey, toKey)} pair onBlocked takes. */
    private static long[] firstHopKeys(HierarchicalRegionPlan h) {
        return hopKeys(h, 0);
    }

    /** L0 hop {@code i → i+1} of the plan's skeleton, as the {@code (fromKey, toKey)} pair onBlocked takes. */
    private static long[] hopKeys(HierarchicalRegionPlan h, int i) {
        RegionPathPlan l0 = h.l0Skeleton();
        assertTrue(l0.size() > i + 1);
        return new long[] {
                RegionPathfinder.fragmentNodeKey(l0.rx(i), l0.ry(i), l0.rz(i), l0.fragmentId(i)),
                RegionPathfinder.fragmentNodeKey(l0.rx(i + 1), l0.ry(i + 1), l0.rz(i + 1), l0.fragmentId(i + 1)) };
    }

    /** The FIX-2 inventory: skews the effective sig vs the raw caps (consuming placement with zero carried
     *  blocks clears the effective-place bit; an iron pickaxe sets the tier field), so recording is
     *  observable AND {@code recordToMemory} is true. */
    private static MovementContext.InventoryView sigSkewInventory() {
        int[] tiers = new int[NavBlock.Tool.values().length];
        tiers[NavBlock.Tool.PICKAXE.ordinal()] = MiningModel.Tier.IRON.ordinal();
        return new MovementContext.InventoryView(
                MiningModel.snapshot(tiers, 255, true), true, 0, 0f, 0f, 0f);
    }

    @Test
    void onBlocked_recordsEffectiveSigOfSearchInventory_notCapsOnly() {
        // The record-skew fix: the remembered sig must be what the plan's searches actually proved under,
        // not the caps-only tag. Blame a DEEPER hop (1 → 2, FROM != the bot's start region) so the row is
        // eligible for recording under the start-region journey scoping (a start-region blame is
        // component-scoped and deliberately never recorded — see the dedicated test below).
        MovementContext.InventoryView inv = sigSkewInventory();

        BlockPos bot = new BlockPos(8, 4, 8);
        HierarchicalRegionPlan h = HierarchicalRegionPlan.build(grid, 0, bot, farGoalX(), CAPS,
                RegionMineModel.DEFAULT, inv);
        long[] hop = hopKeys(h, 1);
        assertTrue(h.onBlocked(hop[0], hop[1], bot));

        assertEquals(1, grid.crossingMemory().total(),
                "one BLOCKED on a deeper hop (FROM != start region) = one remembered crossing, as before");
        long recorded = grid.crossingMemory().sigAt(0, 0);
        assertEquals(CAPS.realizabilitySig(inv), recorded, "the recorded sig is the failing search's effective sig");
        assertTrue(recorded != CAPS.realizabilitySig(),
                "this inventory must actually skew the sig, or the test proves nothing");
    }

    @Test
    void onBlocked_startRegionHop_blacklistedButNotRecorded() {
        // Start-region journey scoping: a blame whose FROM region is the failing search's OWN start region
        // is proven only for the caps-connected component the bot stands in (the ravine problem — the
        // optimistic fragment can span pockets the caps disconnect). It must still repair THIS plan
        // (blacklists[0].add → the reroute avoids the hop) but must NOT become world knowledge (no
        // crossingMemory.record, no roll-up fold, no cross-plan seeding). Uses a recording-capable plan
        // (real InventoryView) so the skip is the scoping rule, not the null-inv §3.3 rule.
        BlockPos bot = new BlockPos(8, 4, 8);
        HierarchicalRegionPlan h = HierarchicalRegionPlan.build(grid, 0, bot, farGoalX(), CAPS,
                RegionMineModel.DEFAULT, sigSkewInventory());
        RegionPathPlan l0 = h.l0Skeleton();
        long[] hop = firstHopKeys(h); // hop 0 → 1: FROM is the bot's (= the sync search start's) region

        assertTrue(h.onBlocked(hop[0], hop[1], bot), "the journey-scoped repair still reroutes");
        RegionPathPlan after = h.l0Skeleton();
        assertNotNull(after);
        boolean sameHop = after.size() > 1 && after.rx(1) == l0.rx(1) && after.ry(1) == l0.ry(1)
                && after.rz(1) == l0.rz(1) && after.fragmentId(1) == l0.fragmentId(1);
        assertFalse(sameHop, "the per-plan blacklist still applies — the reroute avoids the blamed hop");
        assertEquals(0, grid.crossingMemory().total(),
                "a start-region blame is component-scoped — never recorded to the crossing memory");
    }

    @Test
    void onBlocked_nullInventory_neverRecordsToMemory() {
        // A caps-only plan (headless / trace / tests — no InventoryView): its placement/mining assumptions
        // (infinite blocks + bare-hand pricing) are incoherent as a proof hypothesis, so its BLOCKEDs repair
        // the plan's own blacklists but never become world knowledge.
        BlockPos bot = new BlockPos(8, 4, 8);
        HierarchicalRegionPlan h = HierarchicalRegionPlan.build(grid, 0, bot, farGoalX(), CAPS);
        long[] hop = firstHopKeys(h);
        assertTrue(h.onBlocked(hop[0], hop[1], bot), "the repair itself still reroutes as before");
        assertEquals(0, grid.crossingMemory().total(), "a null-inv plan must not record to the crossing memory");
    }

    @Test
    void build_seedsFromMemoryByEffectiveSigDominance() {
        // A crossing recorded under the caps-only sig (BARE tiers, raw place bit) binds a later plan whose
        // effective sig carries MORE capability? No — dominance runs recorder ≥ me, so it binds only bots the
        // recorder dominates: the bare-hand seed applies, the diamond-pick bot ignores it.
        BlockPos bot = new BlockPos(8, 4, 8);
        HierarchicalRegionPlan probe = HierarchicalRegionPlan.build(grid, 0, bot, farGoalX(), CAPS);
        long[] hop = firstHopKeys(probe);
        grid.crossingMemory().record(0, hop[0], hop[1], CAPS.realizabilitySig(), RegionCrossingMemory.PROV_PROOF, BotCaps::sigDominates);

        // Same-sig bot (null inv → the identical caps-only sig): the seeded blacklist applies and the fresh
        // plan's first hop differs from the remembered dead one.
        HierarchicalRegionPlan seeded = HierarchicalRegionPlan.build(grid, 0, bot, farGoalX(), CAPS);
        long[] seededHop = firstHopKeys(seeded);
        assertTrue(seededHop[0] != hop[0] || seededHop[1] != hop[1],
                "an equal-sig plan must be seeded away from the remembered dead crossing");

        // A strictly more capable bot (diamond pick in an otherwise identical setup) is NOT dominated by the
        // bare-hand recorder and rightly ignores the negative: its first hop is the direct one again.
        int[] tiers = new int[NavBlock.Tool.values().length];
        tiers[NavBlock.Tool.PICKAXE.ordinal()] = MiningModel.Tier.DIAMOND.ordinal();
        MovementContext.InventoryView diamond = new MovementContext.InventoryView(
                MiningModel.snapshot(tiers, 255, true), false, 0, 0f, 0f, 0f);
        HierarchicalRegionPlan stronger = HierarchicalRegionPlan.build(grid, 0, bot, farGoalX(), CAPS,
                RegionMineModel.DEFAULT, diamond);
        long[] strongerHop = firstHopKeys(stronger);
        assertEquals(hop[0], strongerHop[0]);
        assertEquals(hop[1], strongerHop[1]);
    }
}
