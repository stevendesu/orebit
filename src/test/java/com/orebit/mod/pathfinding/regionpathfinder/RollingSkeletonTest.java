package com.orebit.mod.pathfinding.regionpathfinder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.worldmodel.hpa.RegionGrid;

import net.minecraft.core.BlockPos;

/**
 * The <b>rolling skeleton</b> increment-A tests (DESIGN-rolling-skeleton.md §12.1, §13-A): the L1 slide
 * (committed-cursor-as-window-base, §3.1), the L0 suffix-splice extension (§4), the degraded fallback
 * (§7 increment-A interim — extension-null degrades IMMEDIATELY to today's exhausted-on-occupancy
 * machinery), and the INV-4 invariant with its {@code exhaustedFires}/{@code degradedSettles} counters (§9).
 *
 * <p>Fixture idioms: {@link RegionPathPlan#splice} is a pure array op tested on hand-built plans; the driven
 * scenarios use the {@link HierarchicalCascadeTest} headless-optimistic beeline (nothing seeded ⇒ the §6
 * optimistic AIR default everywhere). The fallback scenario forces the extension-null via the L0/L1
 * <b>blacklist asymmetry</b> — #5 crossing-memory rows seeded into the plan's L0 blacklists that the L1 tier
 * (per-level blacklists) does not read — the real-world source of tube-confined extension drains.
 */
public class RollingSkeletonTest {

    private static final int MINY = 0;
    /** The surface-band beeline height (see the HierarchicalCascadeTest §2 unbuilt-cost note). */
    private static final int Y = 70;

    // ===================================================================================================
    // §12.1 splice — pure array op: prefix preserved VERBATIM, dedup at the join, shift arithmetic,
    // reachedGoalRegion propagation.
    // ===================================================================================================

    /** A hand-built 6-step fragment-model plan along +X with distinctive per-step portal/frag/dig content. */
    private static RegionPathPlan sixStepPlan() {
        int[] rxs = {0, 1, 2, 3, 4, 5};
        int[] rys = {1, 1, 1, 1, 1, 1};
        int[] rzs = {0, 0, 0, 0, 0, 0};
        int[] frags = {0, 0, 1, 0, 2, 0};
        int[] px = new int[6], py = new int[6], pz = new int[6];
        px[0] = RegionPathPlan.NO_PORTAL; // start step: no incoming edge
        for (int i = 1; i < 6; i++) { px[i] = i * 16 + 3; py[i] = 17 + i; pz[i] = 5; }
        boolean[] digs = {false, false, true, false, false, false};
        return new RegionPathPlan(rxs, rys, rzs, frags, px, py, pz, digs, 6, MINY, 0, false);
    }

    /** A 3-step suffix whose step 0 IS the six-step plan's tail node (region (5,1,0), fragment 0). */
    private static RegionPathPlan tailSuffix(boolean reachesGoal) {
        int[] rxs = {5, 6, 7};
        int[] rys = {1, 1, 1};
        int[] rzs = {0, 0, 0};
        int[] frags = {0, 0, 0};
        int[] px = {RegionPathPlan.NO_PORTAL, 96 + 2, 112 + 9};
        int[] py = {RegionPathPlan.NO_PORTAL, 18, 19};
        int[] pz = {RegionPathPlan.NO_PORTAL, 4, 6};
        return new RegionPathPlan(rxs, rys, rzs, frags, px, py, pz, null, 3, MINY, 0, reachesGoal);
    }

    @Test
    void splice_preservesPrefixVerbatim_dedupsJoin_propagatesGoalFlag() {
        RegionPathPlan old = sixStepPlan();
        RegionPathPlan suffix = tailSuffix(true);

        final int drop = 2;
        RegionPathPlan out = RegionPathPlan.splice(old, drop, suffix);

        assertEquals((6 - drop) + (3 - 1), out.size(), "old[drop..] ++ suffix[1..] — suffix[0] deduped");
        assertEquals(0, out.level());
        assertTrue(out.isFragmentModel());
        assertTrue(out.reachedGoalRegion(), "the spliced plan carries the SUFFIX's goal flag (§4.2)");

        // Prefix preserved verbatim (INV-1): every accessor byte-compares old[drop+i] == out[i].
        for (int i = 0; i < 6 - drop; i++) {
            final int j = drop + i;
            assertEquals(old.rx(j), out.rx(i), "prefix rx @" + i);
            assertEquals(old.ry(j), out.ry(i), "prefix ry @" + i);
            assertEquals(old.rz(j), out.rz(i), "prefix rz @" + i);
            assertEquals(old.fragmentId(j), out.fragmentId(i), "prefix frag @" + i);
            assertEquals(old.isDig(j), out.isDig(i), "prefix dig @" + i);
            assertEquals(old.portalCell(j), out.portalCell(i), "prefix portal @" + i);
        }
        // Appended tail == suffix[1..], renumbered after the kept prefix.
        for (int i = 1; i < 3; i++) {
            final int k = (6 - drop) + (i - 1);
            assertEquals(suffix.rx(i), out.rx(k), "suffix rx @" + k);
            assertEquals(suffix.ry(i), out.ry(k), "suffix ry @" + k);
            assertEquals(suffix.rz(i), out.rz(k), "suffix rz @" + k);
            assertEquals(suffix.fragmentId(i), out.fragmentId(k), "suffix frag @" + k);
            assertEquals(suffix.portalCell(i), out.portalCell(k), "suffix portal @" + k);
        }
        // The join is deduped: the old tail appears exactly once (index 3), followed by suffix step 1.
        assertEquals(5, out.rx(3), "old tail kept at the join");
        assertEquals(6, out.rx(4), "suffix[1] follows the join — suffix[0] not duplicated");
    }

    @Test
    void splice_zeroDrop_keepsWholePrefix_nonFinalSuffixStaysNonFinal() {
        RegionPathPlan old = sixStepPlan();
        RegionPathPlan out = RegionPathPlan.splice(old, 0, tailSuffix(false));
        assertEquals(8, out.size());
        assertFalse(out.reachedGoalRegion());
        for (int i = 0; i < 6; i++) {
            assertEquals(old.rx(i), out.rx(i));
            assertEquals(old.fragmentId(i), out.fragmentId(i));
            assertEquals(old.portalCell(i), out.portalCell(i));
            assertEquals(old.isDig(i), out.isDig(i));
        }
    }

    // ===================================================================================================
    // §12.1 slide + extension — drive a bot along a healthy optimistic beeline: the committed-advance
    // slides the window (hand-down recedes ⇒ the L0 tail keeps advancing via EXTENDED splices), the
    // coarse skeletons survive untouched, INV-4 holds at every settle, and both §9 counters stay 0.
    // ===================================================================================================
    @Test
    void slide_extendsAheadOfCommit_neverRederives_inv4Holds() {
        RegionGrid grid = RegionGrid.headless(MINY);
        HierarchicalRegionPlan h = HierarchicalRegionPlan.build(grid, MINY, new BlockPos(8, Y, 8),
                new BlockPos(8 + 1000, Y, 8), BotCaps.BREAK_PLACE);
        assertEquals(2, h.topLevel());

        RegionPathPlan l1Before = h.skeletonAt(1);
        RegionPathPlan l0Start = h.l0Skeleton();
        assertNotNull(l0Start);
        final int startTailRx = l0Start.rx(l0Start.size() - 1);

        boolean sawExtended = false;
        for (int x = 24; x <= 248; x += 16) {
            HierarchicalRegionPlan.Verdict v =
                    h.onBotMoved(new BlockPos(x, Y, 8), false, h.committedAt(0));
            assertNotEquals(HierarchicalRegionPlan.Verdict.SWAPPED, v,
                    "x=" + x + ": healthy in-window progress must never re-derive (INV-1/§3.1)");
            sawExtended |= v == HierarchicalRegionPlan.Verdict.EXTENDED;
            // INV-4 (§9): non-final, non-degraded L0 never clamps at its tail — modulo the ratified
            // exemptions (parent clamped at ITS tail ⇒ increment B; surfaced via l0RollingExempt()).
            RegionPathPlan cur = h.l0Skeleton();
            assertTrue(h.committedAt(0) + HierarchicalRegionPlan.WINDOW_CELLS <= cur.size() - 1
                            || h.l0RollingExempt(),
                    "x=" + x + ": INV-4 violated — window-far clamped on a non-exempt L0 skeleton");
        }
        assertTrue(sawExtended, "the clamping window must extend by suffix-splice (§3.2/§4)");
        assertSame(l1Before, h.skeletonAt(1),
                "the slide consumes the existing commit signal — L1 is never re-planned in-window (§3.1)");
        assertTrue(h.l0Skeleton().rx(h.l0Skeleton().size() - 1) > startTailRx,
                "the L0 tail recedes ahead of the bot — the hand-down advanced per parent commit");
        assertEquals(0, h.exhaustedFires(), "§9: the safety net never fires on the healthy route");
        assertEquals(0, h.degradedSettles(), "§9: no degraded settles on the healthy route");
    }

    @Test
    void extension_shiftsCursor_prefixUntouched_shiftedIndexAddressesSameStep() {
        RegionGrid grid = RegionGrid.headless(MINY);
        HierarchicalRegionPlan h = HierarchicalRegionPlan.build(grid, MINY, new BlockPos(8, Y, 8),
                new BlockPos(8 + 1000, Y, 8), BotCaps.BREAK_PLACE);

        boolean found = false;
        for (int x = 24; x <= 248 && !found; x += 16) {
            RegionPathPlan pre = h.l0Skeleton();
            final int cPre = h.committedAt(0);
            HierarchicalRegionPlan.Verdict v = h.onBotMoved(new BlockPos(x, Y, 8), false, cPre);
            if (v != HierarchicalRegionPlan.Verdict.EXTENDED) {
                continue;
            }
            found = true;
            final int shift = h.lastExtensionShift();
            RegionPathPlan post = h.l0Skeleton();
            assertTrue(shift >= 0 && shift <= cPre,
                    "the head-drop is bounded by the driver windowStart bound (§4.2/D3)");
            assertTrue(post.size() > pre.size() - shift, "the tail was appended (a real extension)");
            // Prefix untouched (INV-1): byte-compare the kept prefix through every accessor.
            for (int i = shift; i < pre.size(); i++) {
                final int k = i - shift;
                assertEquals(pre.rx(i), post.rx(k), "prefix rx @" + k);
                assertEquals(pre.ry(i), post.ry(k), "prefix ry @" + k);
                assertEquals(pre.rz(i), post.rz(k), "prefix rz @" + k);
                assertEquals(pre.fragmentId(i), post.fragmentId(k), "prefix frag @" + k);
                assertEquals(pre.isDig(i), post.isDig(k), "prefix dig @" + k);
                assertEquals(pre.portalCell(i), post.portalCell(k), "prefix portal @" + k);
            }
            // Cursors shift, never reset (§4.2/§4.4 — INV-3's checkable form): the shifted committed index
            // addresses the SAME skeleton step the unshifted one did.
            final int cPost = h.committedAt(0);
            assertTrue(cPost >= 0, "a shifted cursor never goes negative");
            assertEquals(pre.rx(cPost + shift), post.rx(cPost), "shifted cursor addresses the same step (rx)");
            assertEquals(pre.ry(cPost + shift), post.ry(cPost), "shifted cursor addresses the same step (ry)");
            assertEquals(pre.rz(cPost + shift), post.rz(cPost), "shifted cursor addresses the same step (rz)");
        }
        assertTrue(found, "the drive must produce at least one EXTENDED splice");
    }

    // ===================================================================================================
    // §12.1 fallback (§7 increment-A interim / INV-5): the RegionTubeEscalationTest WALL geometry — the
    // coarse tiers (rolled up from mixed children) route optimistically ACROSS an unmineable wall the L0
    // level cannot refine, so when the sliding L1 hand-down reaches the wall crossing, the tube-confined
    // L0 extension search returns null ⇒ the level degrades IMMEDIATELY to today's behavior: the window
    // clamps at the tail, the skeleton object is untouched, and the retained exhausted-on-occupancy
    // machinery (+ the existing tube-escalation repair) owns recovery once the bot arrives. No new failure
    // state: the journey continues (re-derive around the wall's real connector), it does not FAIL.
    // ===================================================================================================
    @Test
    void extensionNull_degradesImmediately_todaysMachineryRecovers() {
        // Forcing substrate: the L0/L1 BLACKLIST ASYMMETRY — the real-world source of extension nulls.
        // Both tiers read the same terrain (over BUILT children the coarse roll-up is honest, and over
        // UNBUILT cells both tiers are equally optimistic — verified empirically: solid-wall fixtures never
        // null the extension, the coarse route honestly detours). What ONLY the L0 search honors is its
        // per-level blacklist — here seeded from the dimension's #5 crossing memory (dead crossings proven
        // under an earlier goal survive the plan boundary): every 6-face entry into the four L0 regions
        // forming L1 cell (6,2,0)'s -X entry face is a remembered PROV_PROOF dead crossing, so when the
        // sliding L1 window hands that cell's portal down, the tube-confined L0 suffix search drains and
        // returns a tube-null — the §7 fallback under test.
        RegionGrid grid = RegionGrid.headless(MINY);
        final long sig = BotCaps.BREAK_PLACE.realizabilitySig();
        final int[][] targets = { {12, 4, 0}, {12, 5, 0}, {12, 4, 1}, {12, 5, 1} };
        final int[][] faces = { {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1} };
        for (int[] t : targets) {
            for (int[] f : faces) {
                grid.crossingMemory().record(0,
                        RegionPathfinder.fragmentNodeKey(t[0] + f[0], t[1] + f[1], t[2] + f[2], 0),
                        RegionPathfinder.fragmentNodeKey(t[0], t[1], t[2], 0),
                        sig, com.orebit.mod.worldmodel.hpa.RegionCrossingMemory.PROV_PROOF,
                        BotCaps::sigDominates);
            }
        }

        HierarchicalRegionPlan h = HierarchicalRegionPlan.build(grid, MINY, new BlockPos(8, Y, 8),
                new BlockPos(8 + 1000, Y, 8), BotCaps.BREAK_PLACE);
        assertFalse(h.isFailed(), "the build must route (the blacklisted face is beyond the first window)");
        assertEquals(2, h.topLevel());

        boolean sawDegraded = false;
        boolean sawSwappedAfterDegrade = false;
        for (int x = 24; x <= 296 && !sawSwappedAfterDegrade; x += 16) {
            RegionPathPlan pre = h.l0Skeleton();
            HierarchicalRegionPlan.Verdict v = h.onBotMoved(new BlockPos(x, Y, 8), false, 0);
            if (v == HierarchicalRegionPlan.Verdict.UNCHANGED && h.l0Degraded()) {
                // The degraded interim (§7): extension failed and NOTHING else changed — skeleton, cursors,
                // window all keep their values; the bot walks on toward the existing tail.
                sawDegraded = true;
                assertSame(pre, h.l0Skeleton(),
                        "x=" + x + ": the degraded interim keeps the skeleton object untouched (§7)");
            }
            if (sawDegraded && v == HierarchicalRegionPlan.Verdict.SWAPPED) {
                sawSwappedAfterDegrade = true; // today's machinery took over (exhausted-on-occupancy re-derive)
            }
        }
        assertTrue(sawDegraded,
                "the tube-null extension must degrade immediately (D8 increment-A interim — no escalation)");
        assertTrue(h.degradedSettles() >= 1, "§9: degraded settles are counted");
        assertTrue(sawSwappedAfterDegrade,
                "occupying the clamped tail must fire the retained exhausted machinery (INV-5 recovery)");
        // (No exhaustedFires assertion here: the L0 tail is the parent's window-far portal, so occupying it
        // often exhausts the PARENT on the same settle, and the coarsest-exit rule attributes the re-derive
        // there — verified in this run. exhaustedFires counts only L0-as-the-exit (§9); its healthy-route
        // ==0 guarantee is pinned by the slide/INV-4 tests above.)
        assertFalse(h.isFailed(),
                "INV-5: degraded mode is today's machinery, not a new failure state — escalation reroutes");
        assertNotNull(h.l0Skeleton());
    }
}
