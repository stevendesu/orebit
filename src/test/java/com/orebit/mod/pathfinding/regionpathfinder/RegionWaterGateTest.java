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
     * existence bit and offers no proof the vertical extent is swum rather than walked. The point of the §3
     * amendment survives the 2026-08-02 stairs-optimism ruling: what a W bit must NOT do is buy a discount
     * off the dry price. It buys nothing.
     */
    @Test
    void surfaceWetFragment_verticalKeepsWalkShaping() {
        final int sw = RegionFragments.TYPE_S | RegionFragments.TYPE_W;
        final int dy = 12; // the regression shape: a big single-leg ascent
        float wet = RegionPathfinder.transitCost(0, dy, 0, sw, false, SAFE_FALL, false, PF);
        float dry = RegionPathfinder.transitCost(0, dy, 0, RegionFragments.TYPE_S, false, SAFE_FALL, false, PF);
        assertEquals(dry, wet, 1e-4, "a pure-vertical S·W leg must price EXACTLY like the dry walk-shaped leg");
        // Stairs-optimism (2026-08-02): a MIXED ascent assumes a pre-existing staircase, so it is walk-rate
        // per block of rise — no pillar rate (that is now KIND_AIR-only) and no cliff term.
        assertEquals(dy * RegionPathfinder.WALK_PER_BLOCK, wet, 1e-4,
                "the ascent is stairs-shaped: dy × WALK, exactly");
        // The descent side reuses dyCost identically.
        assertEquals(RegionPathfinder.transitCost(0, -dy, 0, RegionFragments.TYPE_S, false, SAFE_FALL, false, PF),
                RegionPathfinder.transitCost(0, -dy, 0, sw, false, SAFE_FALL, false, PF), 1e-4,
                "S·W descent = dry walk-shaped descent");
    }

    /**
     * ¬S·W (fully submerged — no surfaceable cell anywhere): the vertical leg is provably swum, and prices at
     * the ONE {@link RegionPathfinder#SWIM_PER_BLOCK} sprint-swim rate — because
     * {@link com.orebit.mod.pathfinding.blockpathfinder.movements.SprintSwim} emits its up/down candidates at
     * exactly its lateral cost. Direction-symmetric, reverse-invariant.
     */
    @Test
    void fullySubmergedFragment_verticalPricesAtSwimRate() {
        final int dy = 10;
        float up = RegionPathfinder.transitCost(0, dy, 0, RegionFragments.TYPE_W, false, SAFE_FALL, false, PF);
        assertEquals(dy * RegionPathfinder.SWIM_PER_BLOCK, up, 1e-4,
                "¬S·W vertical prices at the sprint-swim rate (0.77/block), not a paddle rate");
        assertEquals(up, RegionPathfinder.transitCost(0, -dy, 0, RegionFragments.TYPE_W, false, SAFE_FALL, false, PF),
                1e-4, "swim is direction-symmetric (up = down; water absorbs falls)");
        assertEquals(up, RegionPathfinder.transitCost(0, dy, 0, RegionFragments.TYPE_W, false, SAFE_FALL, true, PF),
                1e-4, "reverse (cost-to-goal field) sense identical — no vertical swap for swum legs");
    }

    /** The horizontal W discount is unchanged, and a mixed leg combines its axes by max (not a sum). */
    @Test
    void horizontalSwimDiscount_andMaxCombination() {
        final int sw = RegionFragments.TYPE_S | RegionFragments.TYPE_W;
        float wetH = RegionPathfinder.transitCost(10, 0, 5, sw, false, SAFE_FALL, false, PF);
        float dryH = RegionPathfinder.transitCost(10, 0, 5, RegionFragments.TYPE_S, false, SAFE_FALL, false, PF);
        assertEquals(dryH * 0.77f, wetH, 1e-3, "lateral W legs keep the 0.77 sprint-swim discount");
        assertTrue(wetH < dryH, "swimming still BEATS walking laterally");
        // A block of rise rides along free with a block of horizontal (Ascend.COST == Traverse.FLAT_COST), so
        // the two axes combine by max — the old additive form billed a staircase twice.
        float mixed = RegionPathfinder.transitCost(10, 12, 5, sw, false, SAFE_FALL, false, PF);
        float dyOnly = RegionPathfinder.transitCost(0, 12, 0, RegionFragments.TYPE_S, false, SAFE_FALL, false, PF);
        assertEquals(Math.max(wetH, dyOnly), mixed, 1e-3, "transitCost(S·W) = max(octile×SWIM, walk-shaped dy)");
        assertTrue(mixed < wetH + dyOnly, "…strictly cheaper than the old additive form");
        // A provably-submerged ¬S·W leg is NOT a max: water is a normalized sphere of motion, so all three
        // axes combine by the 3-D octile — no axis rides along free the way a walk's vertical does.
        float mixedSub = RegionPathfinder.transitCost(10, 12, 5, RegionFragments.TYPE_W, false, SAFE_FALL, false, PF);
        final float octile3 = (12 - 10) + 1.4142135f * (10 - 5) + 1.7320508f * 5; // sorted 5,10,12
        assertEquals(octile3 * RegionPathfinder.SWIM_PER_BLOCK, mixedSub, 1e-3,
                "transitCost(¬S·W) = octile3(dx,dy,dz) × SWIM");
        assertTrue(mixedSub > Math.max(wetH, 12 * RegionPathfinder.SWIM_PER_BLOCK),
                "…and is dearer than a max would be — swimming buys no free axis");
    }

    /**
     * Uniform {@link RegionFragments#KIND_WATER} records are the truly-fully-submerged class by
     * construction (§5.5 narrowed fast-path), so their crossings align with ¬S·W: vertical faces and lateral
     * faces alike at the one {@link RegionPathfinder#SWIM_PER_BLOCK} rate.
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
        assertEquals(G * RegionPathfinder.SWIM_PER_BLOCK, up, 1e-3,
                "vertical uniform-water crossing prices at the same sprint-swim rate as lateral");
        assertEquals(up, down, 1e-3, "swim is direction-symmetric");
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
