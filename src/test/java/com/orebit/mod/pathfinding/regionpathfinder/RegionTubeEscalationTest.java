package com.orebit.mod.pathfinding.regionpathfinder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.MiningModel;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.worldmodel.hpa.CostPyramid;
import com.orebit.mod.worldmodel.hpa.FragmentBuilder;
import com.orebit.mod.worldmodel.hpa.RegionAddress;
import com.orebit.mod.worldmodel.hpa.RegionCrossingMemory;
import com.orebit.mod.worldmodel.hpa.RegionFragments;
import com.orebit.mod.worldmodel.hpa.RegionGrid;
import com.orebit.mod.worldmodel.navblock.NavBlock;

import net.minecraft.core.BlockPos;

/**
 * The <b>tube-drain escalation</b> tests (the cascade-correctness fix): a sub-top cascade level is PERMANENTLY
 * confined to a {@link RegionPathfinder.RegionTube} corridor of the level above, with the §3a flood guard
 * disabled inside it — so before this fix, a tube-confined {@code null} (the corridor simply cannot be refined
 * at this level) was indistinguishable from a genuine disconnection and all three {@code failed} sites
 * ({@code rebuild} / {@code onBotMoved} / {@code onBlocked}) FALSE-FAILED on it. Now a tubed {@code null}
 * raises {@link RegionPathfinder#lastWasTubeConfined()} plus a parent-cell touch mask, and
 * {@link HierarchicalRegionPlan} escalates ({@code rederiveWithTubeEscalation}): it blames the first
 * parent-window hop the failed refinement did NOT touch ({@code blameTubeConfined}, recorded as
 * {@link RegionCrossingMemory#PROV_ESCALATION} — session-only, never persisted) and re-plans the parent,
 * recursing upward. Honest give-up remains reachable ONLY via an untubed top-level drain, a flood at the
 * coarsest level, or the escalation backstop.
 *
 * <p>Fixture idiom: hand-authored {@link FragmentBuilder} masks over a headless grid — the
 * {@link RegionFloodGuardTest} wall geometry (a wide unmineable wall between a nearby start and goal, the only
 * real crossings at the wall ends) — plus the {@link HierarchicalCascadeTest} {@link
 * MovementContext.InventoryView} fixture so {@code recordToMemory} is ON and escalation blames reach the
 * dimension's {@link RegionCrossingMemory}.
 */
public class RegionTubeEscalationTest {

    private static final int G = 16;
    private static final int CELLS = G * G * G;
    private static final int MINY = 0;
    private static final int STONE = 8;

    /** Wall half-length in regions for the escalation fixtures (see {@link RegionFloodGuardTest#W} for the
     *  geometry): its ends (the only real crossings) sit at ±(W+1). Wide enough that the direct route floods
     *  the cap-safe level-0 search, forcing the cascade to coarser tops whose corridors then need tube-drain
     *  escalation to refine. */
    private static final int W = 30;

    /** No-break, no-place caps — the wall is impassable for real, so only the wall-end connectors route. */
    private static final BotCaps CAPS = BotCaps.DEFAULT;

    private RegionGrid grid;

    private static int idx(int x, int y, int z) { return (y << 8) | (z << 4) | x; }

    /** Bare-hand inventory view (the {@link HierarchicalCascadeTest} fixture): its only role here is being
     *  non-null, which turns {@code recordToMemory} ON so escalation blames reach the crossing memory. */
    private static MovementContext.InventoryView inv() {
        int[] tiers = new int[NavBlock.Tool.values().length];
        return new MovementContext.InventoryView(
                MiningModel.snapshot(tiers, 255, true), true, 0, 0f, 0f, 0f, 0);
    }

    /** Rows of {@code mem} at levels ≥ 1 with {@link RegionCrossingMemory#PROV_ESCALATION} provenance. */
    private static int escalationRowsAtCoarse(RegionCrossingMemory mem) {
        int n = 0;
        for (int L = 1; L <= RegionAddress.MAX_COARSE_LEVEL; L++) {
            for (int i = 0; i < mem.count(L); i++) {
                if (mem.provAt(L, i) == RegionCrossingMemory.PROV_ESCALATION) n++;
            }
        }
        return n;
    }

    /** Print every PROV_ESCALATION row of {@code mem} with its decoded from/to cells (diagnostic). */
    private static void dumpEscalationRows(String tag, RegionCrossingMemory mem) {
        for (int L = 1; L <= RegionAddress.MAX_COARSE_LEVEL; L++) {
            for (int i = 0; i < mem.count(L); i++) {
                if (mem.provAt(L, i) != RegionCrossingMemory.PROV_ESCALATION) continue;
                long f = mem.fromAt(L, i), t = mem.toAt(L, i);
                System.out.println("[TUBE] " + tag + " ESC L" + L
                        + " from=" + RegionAddress.unpackRX(f) + "," + RegionAddress.unpackRY(f) + ","
                        + RegionAddress.unpackRZ(f)
                        + " to=" + RegionAddress.unpackRX(t) + "," + RegionAddress.unpackRY(t) + ","
                        + RegionAddress.unpackRZ(t));
            }
        }
    }

    // ===================================================================================================
    // T1 — tubed-drain escalation reaches across the wall. OLD BEHAVIOR (pinned here as the bug): on this
    // exact fixture, rebuild()'s descent flooded L0, escalated the top, and the finer levels' TUBED
    // refinements of the coarse corridor drained (the coarse abstraction routes optimistically where the
    // fine level cannot follow); the tubed null was read as a genuine no-route (lastWasFlood()==false) and
    // the cascade FALSE-FAILED — isFailed()==true, l0Skeleton()==null — despite the real walkable route
    // around the wall ends. NEW: the tubed drain escalates with realized blame until a refinable corridor
    // is found; the plan routes and the blames are visible as coarse-level PROV_ESCALATION rows.
    // ===================================================================================================
    @Test
    void tubedDrain_escalatesInsteadOfFalseFailing() {
        buildWallFixture(W, false);
        BlockPos start = feet(0, 1, 0);
        BlockPos goal = feet(0, 1, 2);

        HierarchicalRegionPlan h = HierarchicalRegionPlan.build(grid, MINY, start, goal, CAPS,
                RegionMineModel.DEFAULT, inv());
        dump("T1", h);

        assertFalse(h.isFailed(), "a tubed drain must escalate, not false-fail (the OLD code failed here)");
        assertNotNull(h.l0Skeleton(), "the escalated cascade must yield a level-0 skeleton");
        assertTrue(escalationRowsAtCoarse(grid.crossingMemory()) >= 1,
                "escalation must have blamed >=1 coarse (level>=1) crossing as PROV_ESCALATION");
    }

    // ===================================================================================================
    // T2 — realized blame picks the hop the failed refinement could NOT realize, not (only) the naive
    // committed hop. The bot starts several regions west of the goal's wall crossing, so the parent windows
    // contain realizable hops the tubed searches DO touch; blame must land beyond them. The strong form —
    // at least one blamed TO-cell lies across the wall (its parent-level rz differs from the FROM's) — shows
    // the blame walked to the actual barrier.
    // ===================================================================================================
    @Test
    void realizedBlame_landsOnUnrealizedHop_notJustCommitted() {
        buildWallFixture(W, false);
        // Start well west of the wall's midpoint (2026-08-02): the cost-model corrections stopped the L1 search
        // FLOODING on the old -12 start, so rebuild() no longer escalated its top from 1 to 2 and the cascade
        // collapsed to a single coarse level — leaving realized blame nothing to reach BEYOND and degenerating
        // it to the committed hop. Not eliminating a flood to keep a test honest: widening the span makes
        // chooseCapSafeLevel pick the deeper top OUTRIGHT (it is purely geometric), so the multi-level shape
        // this test exists to exercise is restored WITHOUT depending on a flood to produce it. Strictly more
        // rigorous — the assertions below are unchanged. -28 stays inside the seeded corridor (rx ∈ [-31,31]).
        BlockPos start = feet(-28, 1, 0);
        BlockPos goal = feet(0, 1, 2);

        HierarchicalRegionPlan h = HierarchicalRegionPlan.build(grid, MINY, start, goal, CAPS,
                RegionMineModel.DEFAULT, inv());
        dump("T2 build", h);
        assertFalse(h.isFailed(), "the build itself must route (escalating as needed)");
        RegionPathPlan l0 = h.l0Skeleton();
        assertNotNull(l0);
        assertTrue(l0.size() > 1, "a real onward L0 segment to block");

        // Capture the naive committed-hop pairs at every coarse level BEFORE the trigger — the hops the OLD
        // blacklistCurrentHop would have blamed.
        final long[][] committedHops = new long[h.topLevel() + 1][];
        for (int L = 1; L <= h.topLevel(); L++) {
            RegionPathPlan sk = h.skeletonAt(L);
            int c = h.committedAt(L);
            if (sk != null && c + 1 < sk.size()) {
                committedHops[L] = new long[] {
                        RegionPathfinder.fragmentNodeKey(sk.rx(c), sk.ry(c), sk.rz(c), sk.fragmentId(c)),
                        RegionPathfinder.fragmentNodeKey(sk.rx(c + 1), sk.ry(c + 1), sk.rz(c + 1),
                                sk.fragmentId(c + 1)) };
            }
        }

        RegionCrossingMemory mem = grid.crossingMemory();
        final int coarseRowsBefore = escalationRowsAtCoarse(mem);

        // The block tier "proves" the first L0 hop dead — the escalation trigger.
        long fromKey = RegionPathfinder.fragmentNodeKey(l0.rx(0), l0.ry(0), l0.rz(0), l0.fragmentId(0));
        long toKey = RegionPathfinder.fragmentNodeKey(l0.rx(1), l0.ry(1), l0.rz(1), l0.fragmentId(1));
        h.onBlocked(fromKey, toKey, feet(-12, 1, 0));
        dump("T2 after onBlocked", h);
        dumpEscalationRows("T2", mem);

        assertTrue(escalationRowsAtCoarse(mem) > coarseRowsBefore,
                "the blocked repair must have escalated with >=1 new coarse blame");

        // Realized blame: at least one new escalation row is NOT the pre-trigger committed hop of its level.
        // (Not EVERY row: when a tubed failure sits AT the committed hop — the cliff case — realized blame
        // legitimately degenerates to the committed-hop pick; the point is that blame can now reach BEYOND it,
        // which the old blacklistCurrentHop never could.)
        boolean sawNonCommitted = false;
        boolean sawCrossRow = false;
        for (int L = 1; L <= RegionAddress.MAX_COARSE_LEVEL; L++) {
            for (int i = 0; i < mem.count(L); i++) {
                if (mem.provAt(L, i) != RegionCrossingMemory.PROV_ESCALATION) continue;
                long f = mem.fromAt(L, i), t = mem.toAt(L, i);
                long[] committed = L < committedHops.length ? committedHops[L] : null;
                if (committed == null || f != committed[0] || t != committed[1]) {
                    sawNonCommitted = true;
                }
                if (RegionAddress.unpackRZ(f) != RegionAddress.unpackRZ(t)) {
                    sawCrossRow = true; // a blamed crossing OUT of the corridor row — not an along-route hop
                }
            }
        }
        assertTrue(sawNonCommitted,
                "realized blame must reach beyond the naive committed hop (the OLD blacklistCurrentHop pick)");
        assertTrue(sawCrossRow,
                "at least one blamed crossing leaves the corridor row (rz differs) — blame walked to the "
                        + "unrealizable crossing itself, not just along the committed corridor");
    }

    // ===================================================================================================
    // T3 — REMOVED 2026-08-13 (owner ruling), and deliberately not replaced.
    //
    // It pinned onBotMoved-entry parity with T2: walking the bot at the wall used to make some suffix
    // re-derive's tubed L0 refinement DRAIN, and escalation had to blame rather than false-FAIL. The drain
    // was an artifact of the L0 search FLOODING the unbuilt void around the wall until it exhausted itself.
    // The unbuilt-SHELL bound (RegionPathfinder.MAX_UNBUILT_SHELL_DEPTH) removed the flood: L0 now finds the
    // same shell path L1 found and returns a skeleton, so there is no drain and nothing to blame. Measured
    // here: L1 size=40 reachedGoal=true, L0 size=29, coarseEscRows=0.
    //
    // The fixture cannot be rescued by LENGTHENING the wall — the shell route around it stays valid at any
    // length, merely longer, and the fixture seeds ~10 real 4096-cell FragmentBuilder floods per rx, so a
    // wall long enough to matter costs minutes. Only geometry with NO unbuilt cell within the shell depth
    // would drain L0, and no such geometry lets L1 succeed where L0 fails — which is the whole point: the
    // escalation was compensating for a limitation of the search that no longer exists.
    //
    // Escalation itself is still pinned, on this same fixture, by tubedDrain_escalatesInsteadOfFalseFailing
    // (the onBlocked entry) and realizedBlame_landsOnUnrealizedHop_notJustCommitted. Do NOT restore this
    // test by weakening the shell bound.
    // ===================================================================================================

    // ===================================================================================================
    // T5 — honest give-up is PRESERVED. (a) a sealed pocket start with the goal in another pocket, close
    // enough that the top level is 0: the ONLY search is untubed, its heap drain is a genuine no-route —
    // FAILED. (b) the wall fixture with the wall-end connectors ALSO sealed (genuinely no route at any
    // level): escalation exhausts and the give-up path (untubed top drain / coarsest flood / backstop)
    // still reports FAILED with a null skeleton.
    // ===================================================================================================
    @Test
    void honestFail_sealedPocket_topLevelZero_untubedDrainFails() {
        grid = RegionGrid.headless(MINY);
        // Two floor pockets 2 regions apart, each sealed on all six sides; nothing connects them.
        seedFloor(0, 1, 0);
        seedFloor(0, 1, 2);
        sealAround(0, 1, 0);
        sealAround(0, 1, 2);

        HierarchicalRegionPlan h = HierarchicalRegionPlan.build(grid, MINY, feet(0, 1, 0), feet(0, 1, 2), CAPS,
                RegionMineModel.DEFAULT, inv());
        dump("T5a", h);

        assertEquals(0, h.topLevel(), "a 2-region span is cap-safe at level 0 — the search is UNTUBED");
        assertTrue(h.isFailed(), "an untubed heap drain is a genuine no-route — honest FAILED");
        assertNull(h.l0Skeleton());
    }

    /**
     * With the connectors sealed there is genuinely no route — but the BUILD does not (and must not) know
     * that yet: the coarse levels legitimately route through the UNBUILT space beyond the seeded fixture (§6
     * far-route optimism, ratified — an optimistic far skeleton whose first window refines is a valid plan;
     * the truth is discovered on approach). The honest give-up therefore arrives through the production
     * mechanism: as the block tier keeps proving the near hops dead ({@code onBlocked}), blacklists strictly
     * grow, tube-drains escalate, and once even the untubed TOP search drains out of the start cell the plan
     * reports FAILED. This test drives that loop to exhaustion and asserts the give-up is (a) reached, (b)
     * reached in bounded events (escalation converges — no infinite re-propose), preserving the plan-§4
     * failed=true reachability: untubed top drain / coarsest flood / escalation backstop only.
     */
    @Test
    void honestFail_walledWithSealedConnectors_giveUpUnderRepeatedProofs() {
        buildWallFixture(W, true); // connectors sealed too — genuinely no route for a no-break bot
        BlockPos start = feet(0, 1, 0);
        BlockPos goal = feet(0, 1, 2);

        HierarchicalRegionPlan h = HierarchicalRegionPlan.build(grid, MINY, start, goal, CAPS,
                RegionMineModel.DEFAULT, inv());
        dump("T5b build", h);
        assertFalse(h.isFailed(), "build-time optimism about far unbuilt terrain is ratified — no instant FAIL");

        // The block tier proves every offered near hop dead, event by event (each L0 skeleton's first hop is
        // over built terrain the no-break bot genuinely cannot cross beyond — the fixture is sealed).
        int events = 0;
        while (!h.isFailed() && events < 64) {
            RegionPathPlan l0 = h.l0Skeleton();
            assertNotNull(l0, "a non-failed plan always exposes an L0 skeleton");
            if (l0.size() < 2) {
                break; // a same-cell stub — nothing left to prove dead
            }
            long fromKey = RegionPathfinder.fragmentNodeKey(l0.rx(0), l0.ry(0), l0.rz(0), l0.fragmentId(0));
            long toKey = RegionPathfinder.fragmentNodeKey(l0.rx(1), l0.ry(1), l0.rz(1), l0.fragmentId(1));
            h.onBlocked(fromKey, toKey, start);
            events++;
        }
        dump("T5b after " + events + " blocked proofs", h);

        assertTrue(h.isFailed(), "the sealed fixture must reach the honest give-up under repeated proofs");
        assertNull(h.l0Skeleton(), "a FAILED plan exposes no level-0 skeleton");
        assertTrue(events < 64, "give-up must converge well within the event bound (blacklists strictly grow)");
    }

    // ---- fixture: the RegionFloodGuardTest wall geometry, parameterized -------------------------------

    /**
     * A single walkable plane at ry=1 over rx∈[-(w+1),w+1], rz∈[0,2], with a solid unmineable WALL along rz=1
     * covering rx∈[-w,w]. The direct start→goal crossing (rz0→rz2) is blocked; the ONLY real crossings are the
     * floor connectors at the wall ends, rx=±(w+1) — sealed too when {@code sealConnectors} (the genuinely
     * disconnected T5b form). Everything else (ry0/ry2 caps, rz=-1/3 sides, rx=±(w+2) ends) is sealed solid.
     */
    private void buildWallFixture(int w, boolean sealConnectors) {
        grid = RegionGrid.headless(MINY);
        for (int rx = -(w + 1); rx <= w + 1; rx++) {
            seedFloor(rx, 1, 0);                        // start corridor (rz=0)
            seedFloor(rx, 1, 2);                        // goal corridor (rz=2)
            if ((rx < -w || rx > w) && !sealConnectors) {
                seedFloor(rx, 1, 1);                    // wall-end connectors
            } else {
                seedSolid(rx, 1, 1);                    // the unmineable wall (or a sealed connector)
            }
            seedSolid(rx, 0, 0); seedSolid(rx, 2, 0);   // caps below/above the walk plane
            seedSolid(rx, 0, 1); seedSolid(rx, 2, 1);
            seedSolid(rx, 0, 2); seedSolid(rx, 2, 2);
            seedSolid(rx, 1, -1); seedSolid(rx, 1, 3);  // Z-side seals
        }
        seedSolid(-(w + 2), 1, 0); seedSolid(w + 2, 1, 0); // X-end seals (start/goal corridors)
        seedSolid(-(w + 2), 1, 2); seedSolid(w + 2, 1, 2);
    }

    /** Seal all six faces of region {@code (rx,ry,rz)} solid (the T5a pocket form). */
    private void sealAround(int rx, int ry, int rz) {
        seedSolid(rx - 1, ry, rz); seedSolid(rx + 1, ry, rz);
        seedSolid(rx, ry - 1, rz); seedSolid(rx, ry + 1, rz);
        seedSolid(rx, ry, rz - 1); seedSolid(rx, ry, rz + 1);
    }

    /** Cascade state dump — the RegionFloodGuardTest diagnostic idiom. */
    private void dump(String tag, HierarchicalRegionPlan h) {
        System.out.println("[TUBE] " + tag + ": topLevel=" + h.topLevel() + " failed=" + h.isFailed()
                + " blacklisted=" + h.totalBlacklisted()
                + " memTotal=" + grid.crossingMemory().total()
                + " coarseEscRows=" + escalationRowsAtCoarse(grid.crossingMemory()));
        for (int L = h.topLevel(); L >= 0; L--) {
            RegionPathPlan sk = h.skeletonAt(L);
            System.out.println("[TUBE]   L" + L + " " + (sk == null ? "null" : "size=" + sk.size()
                    + " reachedGoal=" + sk.reachedGoalRegion()));
        }
    }

    // ---- seed helpers (real FragmentBuilder flood — the RegionFloodGuardTest idiom) -------------------

    private void seed(int rx, int ry, int rz, boolean[] passable, boolean[] standable, int cellHardness) {
        int passCount = 0, standCount = 0, solidCount = 0;
        long hardnessSumSolid = 0;
        for (int i = 0; i < CELLS; i++) {
            if (passable[i]) passCount++;
            else { solidCount++; hardnessSumSolid += cellHardness; }
            if (standable[i]) standCount++;
        }
        CostPyramid pyr = grid.pyramid();
        int row = pyr.rowFor(0, rx, ry, rz);
        RegionFragments rf = pyr.ensureFragments(0, row);
        FragmentBuilder.build(passable, standable, null, G, passCount, standCount, 0, hardnessSumSolid, solidCount, rf);
        pyr.setBuilt(0, row, true);
    }

    private void seedSolid(int rx, int ry, int rz) {
        seed(rx, ry, rz, new boolean[CELLS], new boolean[CELLS], STONE);
    }

    /** Full cavern floor: standable slab at local y=0, passable air y1..14; connects to horizontal neighbours. */
    private void seedFloor(int rx, int ry, int rz) {
        boolean[] passable = new boolean[CELLS];
        boolean[] standable = new boolean[CELLS];
        for (int x = 0; x < G; x++) {
            for (int z = 0; z < G; z++) {
                standable[idx(x, 0, z)] = true;
                for (int y = 1; y <= 14; y++) passable[idx(x, y, z)] = true;
            }
        }
        seed(rx, ry, rz, passable, standable, STONE);
    }

    private static BlockPos feet(int rx, int ry, int rz) {
        return new BlockPos((rx << 4) + 8, MINY + (ry << 4) + 1, (rz << 4) + 8);
    }
}
