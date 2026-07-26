package com.orebit.mod.pathfinding.regionpathfinder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.worldmodel.hpa.CostPyramid;
import com.orebit.mod.worldmodel.hpa.FragmentBuilder;
import com.orebit.mod.worldmodel.hpa.RegionFragments;
import com.orebit.mod.worldmodel.hpa.RegionGrid;

import net.minecraft.core.BlockPos;

/**
 * Gate-level verification of the floorless water classification under <b>typed fragments</b>
 * (DESIGN-typed-fragments.md §5.5 amended, {@link FragmentBuilder#build}): {@link RegionFragments#KIND_AIR}
 * is now TRULY uniform — floorless, no solid, provably dry — so the no-place air gate's proof obligation
 * ("nothing swimmable here") holds by construction. A floorless leaf with ANY water is no longer uniform at
 * all: it takes the fragment path and becomes MIXED with typed fragments (the water fragment carries
 * {@code W}), so the swim route exists in the region graph through ordinary fragment crossings — no
 * kind-level compromise (the v5 any-water⇒KIND_WATER rule and the interim majority vote are both gone).
 *
 * <p>The consumer is {@link RegionPathfinder}'s no-place air gate (the relax loop's
 * {@code !canPlace && kind()==KIND_AIR && f != 2} drop): a no-place bot cannot pillar up a genuinely dry
 * floorless shaft, so lateral/upward entry into uniform AIR is physically impossible for it. Under the old
 * majority vote a surface leaf that was mostly air over a sliver of ocean got KIND_AIR and the gate refused
 * transit through genuinely swimmable cells: a coarse-graph FALSE DISCONNECTION (never converges). The typed
 * model fixes this structurally: such a leaf is MIXED, and MIXED fragments pass the v1 (kind-based) gates.
 * (Pass 2 moves the gates to per-fragment types — ¬S·¬W air fragments then gate like uniform AIR.)
 *
 * <p>Fixture idiom: {@link RegionFloodGuardTest} / {@code RegionScenarios} — real {@link FragmentBuilder}
 * floods over hand-authored masks on a {@link RegionGrid#headless headless} grid, everything sealed in
 * explicit solid so an unbuilt-optimistic leak can't fake a route.
 */
public class RegionWaterGateTest {

    private static final int G = 16;
    private static final int CELLS = G * G * G;
    private static final int MINY = 0;
    private static final int STONE = 8;

    private static int idx(int x, int y, int z) { return (y << 8) | (z << 4) | x; }

    /**
     * Sealed solid box rx −1..3, ry 0..2, rz −1..1 with three carved regions in a lateral row:
     * <pre>
     *   A = (0,1,0)  cavern floor — start (feet world (8,17,8))
     *   M = (1,1,0)  FLOORLESS    — all-passable, no floor; waterCount per test variant
     *   G = (2,1,0)  cavern floor — goal (feet world (40,17,8))
     * </pre>
     * The ONLY route A→G crosses M laterally; everything else is unmineable-for-a-no-break-bot solid.
     */
    private RegionGrid buildGrid(int middleWaterCount) {
        RegionGrid grid = RegionGrid.headless(MINY);
        for (int rx = -1; rx <= 3; rx++) {
            for (int ry = 0; ry <= 2; ry++) {
                for (int rz = -1; rz <= 1; rz++) {
                    seedSolid(grid, rx, ry, rz);
                }
            }
        }
        seedFloor(grid, 0, 1, 0);                       // A — start
        seedFloorless(grid, 1, 1, 0, middleWaterCount); // M — the gate's subject
        seedFloor(grid, 2, 1, 0);                       // G — goal
        return grid;
    }

    /**
     * A no-place bot's region search must route laterally THROUGH a floorless water-bearing leaf: even one
     * water cell in 4095 air makes M NOT truly uniform, so it takes the fragment path — MIXED with one
     * typed fragment carrying {@code W} — and the A→M→G crossing exists through ordinary fragment relax
     * (MIXED is never kind-gated). Under the original majority vote this exact leaf read uniform KIND_AIR
     * and the route was falsely disconnected.
     */
    @Test
    void noPlaceBot_routesThroughFloorlessWaterBearingCell() {
        RegionGrid grid = buildGrid(1); // 1 water cell among 4095 air — not truly uniform ⇒ fragment path
        RegionFragments m = grid.fragmentRecord(0, 1, 1, 0);
        assertEquals(RegionFragments.KIND_MIXED, m.kind(),
                "a floorless leaf with ANY water is NOT truly uniform ⇒ MIXED (typed fragments §5.5)");
        assertEquals(1, m.fragmentCount(), "one connected air+water component");
        assertTrue(m.typeW(0), "the fragment carries the water type bit");
        assertTrue(m.typeS(0), "the water cell has air-only headroom ⇒ surfaceable");

        RegionPathPlan plan = RegionPathfinder.plan(null, grid, feet(0, 1, 0), feet(2, 1, 0), BotCaps.DEFAULT);
        assertNotNull(plan, "the no-place bot must route laterally through the water-bearing floorless cell");
        assertTrue(plan.reachedGoalRegion(), "the route reaches the goal region, not a partial");
        assertEquals(2, plan.rx(plan.size() - 1), "the route ends in G = (2,1,0)");
    }

    /**
     * The gate itself stays intact: a TRULY-uniform dry floorless leaf (no solid, zero water ⇒ KIND_AIR)
     * still blocks lateral entry for a no-place bot — there is genuinely nothing to swim or pillar in it —
     * while a place-capable bot keeps its (dear) pillar crossing. The typed model sharpens AIR's proof
     * obligation, it does not weaken the gate.
     */
    @Test
    void noPlaceBot_stillGatedThroughPureAirCell() {
        RegionGrid grid = buildGrid(0);
        assertEquals(RegionFragments.KIND_AIR, grid.fragmentRecord(0, 1, 1, 0).kind(),
                "a truly-uniform dry floorless leaf classifies KIND_AIR");

        RegionPathPlan gated = RegionPathfinder.plan(null, grid, feet(0, 1, 0), feet(2, 1, 0), BotCaps.DEFAULT);
        assertNull(gated, "a no-place bot cannot cross a dry floorless shaft laterally — no route exists");

        RegionPathPlan capable = RegionPathfinder.plan(null, grid, feet(0, 1, 0), feet(2, 1, 0), BotCaps.BREAK_PLACE);
        assertNotNull(capable, "a place-capable bot still crosses (pillar/dig) — the gate is caps-scoped");
    }

    // ===================================================================================================
    // §3 pricing amendment (2026-07-23) — decomposed W pricing. The regression: transitCost priced ENTIRE
    // W legs (incl. |dy|) at SWIM_PER_BLOCK, deleting walkCost's vertical capability shaping for any
    // fragment containing ≥1 water cell — an ~8×-discounted phantom ascent through a wet cave (the cliff
    // repro). The amendment decomposes: horizontal at the swim discount; vertical capability-shaped
    // unless the fragment is provably fully submerged (¬S·W → SWIM_VERTICAL_PER_BLOCK).
    // ===================================================================================================

    private static final int SAFE_FALL = 3; // BotCaps.DEFAULT_SAFE_FALL — the no-place shaping window
    private static final float PF = RegionPathfinder.PILLAR_PER_BLOCK_FIELD;

    /**
     * S·W (surface water / wet cave with dry-or-surface cells): the vertical component keeps walkCost's
     * EXACT dy shaping — identical to the same leg through a dry S-only fragment — because W is an
     * existence bit and offers no proof the vertical extent is swimmable. The ~8× swim discount on the
     * ascent is dead.
     */
    @Test
    void surfaceWetFragment_verticalKeepsWalkShaping() {
        final int sw = RegionFragments.TYPE_S | RegionFragments.TYPE_W;
        final int dy = 12; // the regression shape: a big single-leg ascent, well past safeFall
        float wet = RegionPathfinder.transitCost(0, dy, 0, sw, false, SAFE_FALL, false, PF);
        float dry = RegionPathfinder.transitCost(0, dy, 0, RegionFragments.TYPE_S, false, SAFE_FALL, false, PF);
        assertEquals(dry, wet, 1e-4, "a pure-vertical S·W leg must price EXACTLY like the dry walk-shaped leg");
        assertTrue(wet >= dy * RegionPathfinder.PILLAR_PER_BLOCK,
                "the ascent is pillar-shaped (dear), incl. the no-place cliff penalty past safeFall");
        assertTrue(wet > 8f * dy * RegionPathfinder.SWIM_PER_BLOCK,
                "nowhere near the old flat swim discount — the phantom-ascent pricing is gone");
        // The descent side keeps walkCost's fall + cliff shaping too (symmetric reuse of dyCost).
        assertEquals(RegionPathfinder.transitCost(0, -dy, 0, RegionFragments.TYPE_S, false, SAFE_FALL, false, PF),
                RegionPathfinder.transitCost(0, -dy, 0, sw, false, SAFE_FALL, false, PF), 1e-4,
                "S·W descent = dry walk-shaped descent");
    }

    /**
     * ¬S·W (fully submerged — no surfaceable cell anywhere): vertical swim is provably honest and prices
     * at {@link RegionPathfinder#SWIM_VERTICAL_PER_BLOCK} ≈ 2.2 (vertical swim ~2 b/s vs walk 4.32),
     * direction-symmetric, reverse-invariant.
     */
    @Test
    void fullySubmergedFragment_verticalPricesAtSwimVerticalRate() {
        final int dy = 10;
        float up = RegionPathfinder.transitCost(0, dy, 0, RegionFragments.TYPE_W, false, SAFE_FALL, false, PF);
        assertEquals(dy * RegionPathfinder.SWIM_VERTICAL_PER_BLOCK, up, 1e-4,
                "¬S·W vertical prices at SWIM_VERTICAL_PER_BLOCK (≈2.2/block)");
        assertEquals(up, RegionPathfinder.transitCost(0, -dy, 0, RegionFragments.TYPE_W, false, SAFE_FALL, false, PF),
                1e-4, "swim is direction-symmetric (up = down; water absorbs falls)");
        assertEquals(up, RegionPathfinder.transitCost(0, dy, 0, RegionFragments.TYPE_W, false, SAFE_FALL, true, PF),
                1e-4, "reverse (cost-to-goal field) sense identical — no vertical swap for swum legs");
    }

    /** The horizontal W discount is unchanged, and the full decomposition is exact (horiz swim + shaped dy). */
    @Test
    void horizontalSwimDiscount_andExactDecomposition() {
        final int sw = RegionFragments.TYPE_S | RegionFragments.TYPE_W;
        float wetH = RegionPathfinder.transitCost(10, 0, 5, sw, false, SAFE_FALL, false, PF);
        float dryH = RegionPathfinder.transitCost(10, 0, 5, RegionFragments.TYPE_S, false, SAFE_FALL, false, PF);
        assertEquals(dryH * 0.77f, wetH, 1e-3, "lateral W legs keep the 0.77 sprint-swim discount");
        assertTrue(wetH < dryH, "swimming still BEATS walking laterally");
        // Decomposition: a mixed leg = swim-discounted horizontal + the S·W walk-shaped vertical, exactly.
        float mixed = RegionPathfinder.transitCost(10, 12, 5, sw, false, SAFE_FALL, false, PF);
        float dyOnly = RegionPathfinder.transitCost(0, 12, 0, RegionFragments.TYPE_S, false, SAFE_FALL, false, PF);
        assertEquals(wetH + dyOnly, mixed, 1e-3, "transitCost(S·W) = octile×SWIM + walk-shaped dy, exactly");
        // And the ¬S·W mixed leg swaps only the vertical term for the vertical swim rate.
        float mixedSub = RegionPathfinder.transitCost(10, 12, 5, RegionFragments.TYPE_W, false, SAFE_FALL, false, PF);
        assertEquals(wetH + 12 * RegionPathfinder.SWIM_VERTICAL_PER_BLOCK, mixedSub, 1e-3,
                "transitCost(¬S·W) = octile×SWIM + |dy|×SWIM_VERTICAL");
    }

    /**
     * Uniform {@link RegionFragments#KIND_WATER} records are the truly-fully-submerged class by
     * construction (§5.5 narrowed fast-path), so their crossings align with ¬S·W: vertical faces at
     * {@link RegionPathfinder#SWIM_VERTICAL_PER_BLOCK}, lateral at {@link RegionPathfinder#SWIM_PER_BLOCK}.
     */
    @Test
    void uniformWaterRecord_verticalSwimRate_lateralSwimDiscount() {
        RegionGrid grid = RegionGrid.headless(MINY);
        seedFloorless(grid, 0, 1, 0, CELLS); // ALL cells water → uniform KIND_WATER by construction
        RegionFragments rf = grid.fragmentRecord(0, 0, 1, 0);
        assertEquals(RegionFragments.KIND_WATER, rf.kind(), "all-water floorless leaf is uniform KIND_WATER");
        float lateral = RegionPathfinder.uniformTransitCost(0, rf, 0, false, SAFE_FALL,
                RegionMineModel.DEFAULT, PF, false, 1, MINY);
        assertEquals(G * RegionPathfinder.SWIM_PER_BLOCK, lateral, 1e-3,
                "lateral uniform-water crossing keeps the sprint-swim discount");
        float up = RegionPathfinder.uniformTransitCost(0, rf, 3, false, SAFE_FALL,
                RegionMineModel.DEFAULT, PF, false, 1, MINY);
        float down = RegionPathfinder.uniformTransitCost(0, rf, 2, false, SAFE_FALL,
                RegionMineModel.DEFAULT, PF, false, 1, MINY);
        assertEquals(G * RegionPathfinder.SWIM_VERTICAL_PER_BLOCK, up, 1e-3,
                "vertical uniform-water crossing prices at the vertical swim rate");
        assertEquals(up, down, 1e-3, "swim is direction-symmetric");
    }

    // ===================================================================================================
    // The regression's END-TO-END shape: a no-place, no-break bot offered (a) a wet-cave ascent — an S·W
    // fragment entered low and exited ~9 blocks higher — versus (b) a longer walkable detour that climbs
    // the same 9 blocks gradually (≤ safeFall per transit). Under the broken pricing the wet ascent read
    // ~8× cheaper than ANY dry leg and the skeleton dove into the cave; under the amendment its |dy| is
    // pillar-shaped (+ cliff penalty), so the gradual dry route must win.
    // ===================================================================================================
    @Test
    void noPlaceBot_prefersGradualDryDetour_overWetCaveAscent() {
        RegionGrid grid = RegionGrid.headless(MINY);
        // Seal the whole arena in built solid rock (carved regions overwrite below): rx -1..3, ry 0..2,
        // rz -1..2 — no unbuilt-optimistic leak can fake a cheaper third route.
        for (int rx = -1; rx <= 3; rx++)
            for (int ry = 0; ry <= 2; ry++)
                for (int rz = -1; rz <= 2; rz++) seedSolid(grid, rx, ry, rz);

        // --- Region A (0,1,0): start. L-shaped 2-tall corridors at feet y=1: along X (to the wet cave
        // east) and along Z (to the detour south). Touches +X and +Z at y1..2.
        {
            boolean[] p = new boolean[CELLS], s = new boolean[CELLS];
            corridorX(p, s, 0, G - 1, 1);
            corridorZ(p, s, 0, G - 1, 1);
            seed(grid, 0, 1, 0, p, s, null);
        }
        // --- Region W (1,1,0): the WET CAVE — one S·W fragment entered at -X low (y1..2, a 1-deep pool
        // under the shaft) whose only exit east is a ledge at +X high (y10..11): the ascent leg dy≈+9.
        {
            boolean[] p = new boolean[CELLS], s = new boolean[CELLS];
            boolean[] w = new boolean[CELLS];
            for (int x = 0; x <= 7; x++)
                for (int z = 7; z <= 9; z++) {
                    p[idx(x, 1, z)] = true; w[idx(x, 1, z)] = true; // the pool (water, air above → S·W)
                    p[idx(x, 2, z)] = true;
                }
            for (int x = 7; x <= 8; x++)                            // the shaft up
                for (int z = 7; z <= 9; z++)
                    for (int y = 1; y <= 11; y++) p[idx(x, y, z)] = true;
            for (int x = 7; x <= G - 1; x++)                        // the high exit ledge
                for (int z = 7; z <= 9; z++) {
                    s[idx(x, 9, z)] = true;
                    p[idx(x, 10, z)] = true; p[idx(x, 11, z)] = true;
                }
            seed(grid, 1, 1, 0, p, s, w);
            RegionFragments rf = grid.fragmentRecord(0, 1, 1, 0);
            assertEquals(1, rf.fragmentCount(), "the wet cave floods to ONE component");
            assertTrue(rf.typeW(0) && rf.typeS(0), "…typed S·W (pool surface + dry ledge — NOT fully submerged)");
        }
        // --- Region GR (2,1,0): goal. L-shaped corridors at feet y=10: along X (west face aligns with
        // the wet cave's high ledge) and along Z (south face aligns with the detour's last leg).
        {
            boolean[] p = new boolean[CELLS], s = new boolean[CELLS];
            corridorX(p, s, 0, G - 1, 10);
            corridorZ(p, s, 0, G - 1, 10);
            seed(grid, 2, 1, 0, p, s, null);
        }
        // --- The dry detour row (rz=1): three ramp regions climbing feet 1 → 4 → 7 → 10, each transit's
        // dy ≤ safeFall(3) so the climb is pillar-priced but never cliff-penalized (the gradual route).
        {
            boolean[] p = new boolean[CELLS], s = new boolean[CELLS]; // D1 (0,1,1): enter -Z low, ramp to +X y4
            corridorZ(p, s, 0, 8, 1);
            rampX(p, s, 7, G - 1, 1, 4);
            seed(grid, 0, 1, 1, p, s, null);
        }
        {
            boolean[] p = new boolean[CELLS], s = new boolean[CELLS]; // D2 (1,1,1): ramp feet 4 → 7 across X
            rampX(p, s, 0, G - 1, 4, 7);
            seed(grid, 1, 1, 1, p, s, null);
        }
        {
            boolean[] p = new boolean[CELLS], s = new boolean[CELLS]; // D3 (2,1,1): ramp to feet 10, corridor -Z (north to GR)
            rampX(p, s, 0, 8, 7, 10);
            corridorZ(p, s, 0, 9, 10);
            seed(grid, 2, 1, 1, p, s, null);
        }

        BlockPos start = new BlockPos(8, MINY + 16 + 1, 8);    // region A, feet y=1 local
        BlockPos goal = new BlockPos(40, MINY + 16 + 10, 8);   // region GR, feet y=10 local
        RegionPathPlan plan = RegionPathfinder.plan(null, grid, start, goal, BotCaps.DEFAULT);
        assertNotNull(plan, "a route exists (at worst the wet ascent — the gate passes it; pricing decides)");
        assertTrue(plan.reachedGoalRegion());
        boolean throughWetCave = false, throughDetour = false;
        for (int i = 0; i < plan.size(); i++) {
            if (plan.rx(i) == 1 && plan.ry(i) == 1 && plan.rz(i) == 0) throughWetCave = true;
            if (plan.rz(i) == 1) throughDetour = true;
        }
        assertFalse(throughWetCave,
                "the skeleton must NOT dive into the wet-cave ascent — its |dy| is pillar-shaped + cliff-"
                        + "penalized under the §3 pricing amendment, dearer than the gradual dry climb");
        assertTrue(throughDetour, "…and must take the gradual walkable detour (rz=1 row)");
    }

    /** 2-tall corridor along X at {@code z} 7..9: standable floor at {@code feetY-1}, passable feetY..feetY+1. */
    private static void corridorX(boolean[] p, boolean[] s, int x0, int x1, int feetY) {
        for (int x = x0; x <= x1; x++)
            for (int z = 7; z <= 9; z++) {
                s[idx(x, feetY - 1, z)] = true;
                p[idx(x, feetY, z)] = true; p[idx(x, feetY + 1, z)] = true;
            }
    }

    /** 2-tall corridor along Z at {@code x} 7..9 (the north-south limb of the L / detour connectors). */
    private static void corridorZ(boolean[] p, boolean[] s, int z0, int z1, int feetY) {
        for (int z = z0; z <= z1; z++)
            for (int x = 7; x <= 9; x++) {
                s[idx(x, feetY - 1, z)] = true;
                p[idx(x, feetY, z)] = true; p[idx(x, feetY + 1, z)] = true;
            }
    }

    /** 2-tall walkable ramp along X at {@code z} 7..9, feet rising linearly {@code feet0 → feet1} (≤1/step). */
    private static void rampX(boolean[] p, boolean[] s, int x0, int x1, int feet0, int feet1) {
        for (int x = x0; x <= x1; x++) {
            int feet = feet0 + Math.round((feet1 - feet0) * (float) (x - x0) / (x1 - x0));
            for (int z = 7; z <= 9; z++) {
                s[idx(x, feet - 1, z)] = true;
                p[idx(x, feet, z)] = true; p[idx(x, feet + 1, z)] = true;
            }
        }
    }

    // ---- seed helpers (real FragmentBuilder flood — the RegionFloodGuardTest idiom) -----------------------

    private static void seed(RegionGrid grid, int rx, int ry, int rz,
                             boolean[] passable, boolean[] standable, boolean[] water) {
        int passCount = 0, standCount = 0, waterCount = 0, solidCount = 0;
        long hardnessSumSolid = 0;
        for (int i = 0; i < CELLS; i++) {
            if (passable[i]) {
                passCount++;
                if (water != null && water[i]) waterCount++;
            } else { solidCount++; hardnessSumSolid += STONE; }
            if (standable[i]) standCount++;
        }
        CostPyramid pyr = grid.pyramid();
        int row = pyr.rowFor(0, rx, ry, rz);
        RegionFragments rf = pyr.ensureFragments(0, row);
        FragmentBuilder.build(passable, standable, water, G, passCount, standCount, waterCount,
                hardnessSumSolid, solidCount, rf);
        pyr.setBuilt(0, row, true);
    }

    private static void seedSolid(RegionGrid grid, int rx, int ry, int rz) {
        seed(grid, rx, ry, rz, new boolean[CELLS], new boolean[CELLS], null);
    }

    /** Full cavern floor: standable slab at local y=0, passable air y1..14 (reaches the four side faces). */
    private static void seedFloor(RegionGrid grid, int rx, int ry, int rz) {
        boolean[] passable = new boolean[CELLS];
        boolean[] standable = new boolean[CELLS];
        for (int x = 0; x < G; x++) {
            for (int z = 0; z < G; z++) {
                standable[idx(x, 0, z)] = true;
                for (int y = 1; y <= 14; y++) passable[idx(x, y, z)] = true;
            }
        }
        seed(grid, rx, ry, rz, passable, standable, null);
    }

    /** Floorless: all-passable, none standable; the first {@code waterCount} cells hold water. */
    private static void seedFloorless(RegionGrid grid, int rx, int ry, int rz, int waterCount) {
        boolean[] passable = new boolean[CELLS];
        java.util.Arrays.fill(passable, true);
        boolean[] water = null;
        if (waterCount > 0) {
            water = new boolean[CELLS];
            for (int i = 0; i < waterCount; i++) water[i] = true; // low cells first (y=0 upward)
        }
        seed(grid, rx, ry, rz, passable, new boolean[CELLS], water);
    }

    private static BlockPos feet(int rx, int ry, int rz) {
        return new BlockPos((rx << 4) + 8, MINY + (ry << 4) + 1, (rz << 4) + 8);
    }
}
