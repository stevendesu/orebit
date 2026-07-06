package com.orebit.mod.pathfinding.regionpathfinder;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.worldmodel.hpa.CostPyramid;
import com.orebit.mod.worldmodel.hpa.FragmentBuilder;
import com.orebit.mod.worldmodel.hpa.RegionFragments;
import com.orebit.mod.worldmodel.hpa.RegionGrid;

import net.minecraft.core.BlockPos;

/**
 * <b>Expected-improvement</b> tests for the region-tier dig-through + walk-across rework
 * (PERF-DESIGN-region-dig-through.md §7). These encode what a <i>correct</i> region A* <b>should</b> produce,
 * NOT the current (known-bad) behavior — so <b>every test here is RED on current {@code core} and GREEN only
 * after the §3 (dig-through edge) + §4 (entry→exit walk cost) rework lands.</b> They are the RED/GREEN gate the
 * design's §7 calls for; they must never be re-baselined to "match" the current winding output (that would
 * enshrine the bug).
 *
 * <p>Like {@link RegionPathfinderFragmentTest} they need <b>no Minecraft world</b>: scenarios are built on a
 * {@link RegionGrid#headless headless} grid whose leaf {@link RegionFragments} records are produced by the real
 * {@link FragmentBuilder} flood over hand-authored passable/standable masks (the phase-1
 * {@link RegionScenarios} seam, reused directly for {@link #sealedDig_skeletonDigsStraightDown_noCavernDetour()}
 * and mirrored with local helpers for the two bespoke micro-geometries). An unbuilt neighbour reads as the §6
 * optimistic AIR default, so every scenario is <b>sealed with explicit built SOLID</b> so the intended edge is
 * the only cheap route.
 *
 * <h2>The three §7 ideas</h2>
 * <ol>
 *   <li><b>SEALED_DIG</b> — buried goal directly below: the skeleton digs straight DOWN into the goal region
 *       (few hops, a −Y dig edge) instead of the long cavern/air-shaft detour.</li>
 *   <li><b>Walk-across cost reflects opening geometry</b> — a region crossed corner-to-corner (entry and exit
 *       openings far apart) now costs ≫ a region crossed corner-clip (openings nearly coincide); asserted via
 *       the resulting <i>skeleton choice</i> between two otherwise-equal 2-hop routes (raw edge cost is not
 *       exposed on {@link RegionPathPlan}).</li>
 *   <li><b>Sealed-face connectivity</b> — a MIXED region with a face no fragment touches still offers a
 *       {@code canBreak} dig-through across it (the graph is fully 6-connected for a digging bot), and that
 *       edge is withheld from a no-break bot.</li>
 * </ol>
 */
public class RegionDigThroughImprovementTest {

    /** Leaf side in cells (one 16³ NavSection), matching {@link RegionScenarios}. */
    private static final int G = 16;
    private static final int CELLS = G * G * G;
    private static final int MINY = 0;
    private static final int STONE_HARDNESS = 8;

    /** Canonical section-local linear index (as {@link FragmentBuilder} requires). */
    private static int idx(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }

    private RegionGrid grid;

    @BeforeEach
    void setUp() {
        grid = RegionGrid.headless(MINY);
    }

    // ===================================================================================================
    // (1) SEALED_DIG — dig straight DOWN into the buried goal region, no cavern detour.
    // ===================================================================================================

    /**
     * RED now / GREEN after §3. The {@code /bot gather} repro (PERF-DESIGN §1): the goal is buried in region
     * {@code C=(0,1,0)} directly below the start region {@code A=(0,2,0)}, with a cheaper-looking air-shaft
     * detour one region over ({@code (1,2,0)}→{@code (1,1,0)}→{@code C}). A's floor fragment does not reach its
     * own −Y face, so pre-fix there is <b>no dig-down edge</b> from A into C (the §1a connectivity hole): the
     * A* is forced onto a winding detour that leaves the start column and mines/falls around through a
     * neighbour region (≥3 hops). The §3 dig-through fix adds A's straight −Y dig into the goal region, so the
     * correct skeleton is the 2-hop straight-down {@code A → C}.
     *
     * <p>Assertion (the correct behavior): the skeleton reaches the goal region in exactly 2 hops, straight
     * down the start column (rx/rz unchanged for every step). Fails today because the current skeleton detours
     * out of the start column with more hops (e.g. {@code (0,2,0)→(0,2,-1)→(0,1,-1)→(0,1,0)}).
     */
    @Test
    void sealedDig_skeletonDigsStraightDown_noCavernDetour() {
        RegionScenarios.Fixture fx = RegionScenarios.build(RegionScenarios.Scenario.SEALED_DIG);
        RegionPathPlan plan = fx.plan(); // BotCaps.BREAK_PLACE

        assertNotNull(plan, "sealed-dig must route (the world is fully connected for a digger)");
        int last = plan.size() - 1;
        // Goal region is C = (0,1,0), directly below the start region A = (0,2,0).
        assertTrue(plan.reachedGoalRegion(), "the skeleton must actually reach the buried goal region");
        assertTrue(plan.rx(last) == 0 && plan.ry(last) == 1 && plan.rz(last) == 0,
                "must end in the buried goal region (0,1,0); got " + route(plan));

        // The correct route is a straight 2-hop dig DOWN the start column — never the shaft detour (rx=1).
        assertTrue(plan.size() == 2,
                "correct route digs straight down A→C in 2 hops; got " + plan.size() + " hops: " + route(plan));
        for (int i = 0; i < plan.size(); i++) {
            assertTrue(plan.rx(i) == 0 && plan.rz(i) == 0,
                    "the dig-down route stays in the start column (rx=rz=0), never the shaft rx=1; got "
                            + route(plan));
        }
    }

    // ===================================================================================================
    // (2) Walk-across cost reflects opening geometry — corner-to-corner ≫ corner-clip, decided by route choice.
    // ===================================================================================================

    /**
     * RED now / GREEN after §4. Two otherwise-equal 2-hop routes connect a diagonal start/goal
     * ({@code S=(0,1,0)} → {@code T=(1,1,1)}):
     * <ul>
     *   <li><b>P via M1=(1,1,0)</b> — M1's entry ({@code −X} at low-Z) and exit ({@code +Z} at high-X) openings
     *       sit at <b>opposite corners</b>, so crossing it is a full <i>corner-to-corner</i> traverse
     *       (≈ a region diagonal ≈ 19 ticks once priced).</li>
     *   <li><b>Q via M2=(0,1,1)</b> — M2's entry ({@code −Z} at high-X) and exit ({@code +X} at low-Z) openings
     *       nearly <b>coincide at one corner</b>, so crossing it is a cheap <i>corner-clip</i> (≈ 2 ticks).</li>
     * </ul>
     * Both routes have the same hop count and (by construction) near-aligned boundary crossings, so the
     * <b>current</b> ~1-per-crossing model prices the within-region traversal at ~0 and picks P (the
     * corner-to-corner region) — it is blind to opening geometry (the §1b/§4 "1.0" bug). The §4 entry→exit
     * walk cost prices the traversal, so the corner-to-corner P (≈19) becomes far dearer than the corner-clip Q
     * (≈2) and the correct skeleton switches to Q.
     *
     * <p>Assertion (the correct behavior): the skeleton avoids the corner-to-corner region {@code M1=(1,1,0)}
     * and routes through the corner-clip {@code M2=(0,1,1)} instead. Fails today because the geometry-blind
     * model routes through M1.
     *
     * <p>Note: raw edge cost is not exposed on {@link RegionPathPlan}, so per §7 this asserts via the resulting
     * route choice. The after-fix margin is large (~19 vs ~2 traverse) so it is robust to the small
     * boundary-crossing differences that break the current near-tie.
     */
    @Test
    void walkAcross_prefersCornerClipOverCornerToCorner() {
        // Seal a solid box around the whole 2×2 region neighbourhood, then carve the four route regions.
        fillSolidBox(-1, 2, 0, 2, -1, 2);

        // S=(0,1,0): +X opening localized to z[0,3] (aligns M1's −X), +Z opening at x[12,14] (overlaps M2's −Z).
        seedMasks(0, 1, 0, union(window(0, 15, 0, 3), window(12, 14, 3, 15)));
        // M1=(1,1,0): CORNER-TO-CORNER — −X opening at z[0,3] (low-Z), +Z opening at x[12,15] (high-X).
        seedMasks(1, 1, 0, union(window(0, 12, 0, 3), window(12, 15, 0, 15)));
        // M2=(0,1,1): CORNER-CLIP — −Z opening at x[12,15] and +X opening at z[0,3] share the (x15,z0) corner.
        seedMasks(0, 1, 1, window(12, 15, 0, 3));
        // T=(1,1,1): −Z opening at x[12,15] (aligns M1's +Z), −X opening at z[1,3] (overlaps M2's +X z[0,3]).
        seedMasks(1, 1, 1, union(window(12, 15, 0, 15), window(0, 12, 1, 3)));

        BlockPos start = new BlockPos(2, 17, 2);    // in S=(0,1,0)
        BlockPos goal = new BlockPos(29, 17, 29);   // local (13,1,13) of T=(1,1,1)
        RegionPathPlan plan = RegionPathfinder.plan(null, grid, start, goal, BotCaps.BREAK_PLACE);

        assertNotNull(plan, "both routes are open; the search must find one — got null: " + start + "→" + goal);
        int last = plan.size() - 1;
        assertTrue(plan.rx(last) == 1 && plan.ry(last) == 1 && plan.rz(last) == 1,
                "must reach the goal region T=(1,1,1); got " + route(plan));

        // The corrected walk cost makes the corner-to-corner region M1=(1,1,0) expensive → route via the clip M2.
        assertTrue(!visits(plan, 1, 1, 0),
                "priced traversal must avoid the corner-to-corner region (1,1,0) and clip through (0,1,1); got "
                        + route(plan));
        assertTrue(visits(plan, 0, 1, 1),
                "the correct route clips through M2=(0,1,1); got " + route(plan));
    }

    // ===================================================================================================
    // (3) Sealed-face connectivity — a canBreak dig-through edge exists across a face no fragment touches;
    //     a no-break bot does NOT get it.
    // ===================================================================================================

    /**
     * RED now / GREEN after §3. A MIXED start region {@code A=(0,1,0)} (a full cavern floor) is sealed in solid
     * rock on all six sides; the goal is in region {@code C=(0,0,0)} directly below, across A's <b>−Y face —
     * which no fragment of A touches</b> (the floor slab is standable, not passable). Both A's −Y and C's +Y
     * are sealed, so the ONLY short connection between them is a dig-through across that untouched face.
     *
     * <p><b>canBreak</b>: pre-fix there is no dig-through edge across A's −Y (the §1a hole), so the digger is
     * forced into a long mine-<i>around</i> through the surrounding rock (≥3 solid mine hops, entering
     * neighbour columns). The §3 fix emits the straight −Y dig-through, so the correct skeleton is the 2-hop
     * straight-down {@code A → C}. Assertion: reaches C in 2 hops, straight down (rx/rz unchanged). Fails today
     * (the current route mines around, >2 hops leaving the start column).
     *
     * <p><b>no-break</b>: the dig-through (and every surrounding mine) is break-gated, and A touches none of its
     * six faces with a walkable opening into a non-solid neighbour, so a no-break bot has <i>no</i> edge out of
     * A at all → the search FAILs. Asserted as {@code null}; this invariant holds both before and after the fix
     * (it demonstrates the new edge is correctly withheld from a no-break bot), so it is the control, not the
     * RED signal.
     */
    @Test
    void sealedFace_digThroughIsCanBreakGated() {
        // Solid shell around A=(0,1,0) and the buried goal C=(0,0,0).
        fillSolidBox(-1, 1, -1, 2, -1, 1);
        seedFloorFull(0, 1, 0);   // A — start region (MIXED floor; does NOT touch its −Y face)
        seedFloorFull(0, 0, 0);   // C — buried goal region, one region straight below A

        // Sanity: A is MIXED and its single fragment does not touch −Y (face 2) — the sealed face under test.
        RegionFragments rfA = grid.fragmentRecord(0, 0, 1, 0);
        assertTrue(rfA.kind() == RegionFragments.KIND_MIXED, "A must be a MIXED floor region");
        assertTrue(!rfA.touchesFace(0, 2), "A's fragment must NOT touch its −Y face (the sealed face under test)");

        BlockPos start = new BlockPos(8, 17, 8);   // in A=(0,1,0)
        BlockPos goal = new BlockPos(8, 1, 8);     // in C=(0,0,0), straight below

        // canBreak: the dig-through edge exists → correct route digs straight DOWN in 2 hops (no mine-around).
        RegionPathPlan dig = RegionPathfinder.plan(null, grid, start, goal, BotCaps.BREAK_PLACE);
        assertNotNull(dig, "a digger must reach the buried goal (fully 6-connected for break)");
        assertTrue(dig.reachedGoalRegion() && dig.rx(dig.size() - 1) == 0 && dig.ry(dig.size() - 1) == 0
                        && dig.rz(dig.size() - 1) == 0,
                "must reach the buried goal region C=(0,0,0); got " + route(dig));
        assertTrue(dig.size() == 2,
                "correct route is the straight −Y dig A→C (2 hops), not a mine-around; got " + dig.size()
                        + " hops: " + route(dig));
        for (int i = 0; i < dig.size(); i++) {
            assertTrue(dig.rx(i) == 0 && dig.rz(i) == 0,
                    "the dig-through route stays in the start column (rx=rz=0); got " + route(dig));
        }

        // no-break: the dig-through is break-gated and A has no walkable opening out → the search must FAIL.
        RegionPathPlan noBreak = RegionPathfinder.plan(null, grid, start, goal, BotCaps.DEFAULT);
        assertNull(noBreak, "a no-break bot must NOT get the dig-through edge across the sealed face → FAIL");
    }

    // ===================================================================================================
    // Seed helpers — real FragmentBuilder flood over synthetic masks (the RegionPathfinderFragmentTest idiom).
    // ===================================================================================================

    /** Seed a level-0 leaf's fragment record from passable/standable masks via the real {@link FragmentBuilder}. */
    private void seed(int rx, int ry, int rz, boolean[] passable, boolean[] standable) {
        int passCount = 0, standCount = 0, solidCount = 0;
        long hardnessSumSolid = 0;
        for (int i = 0; i < CELLS; i++) {
            if (passable[i]) passCount++;
            else { solidCount++; hardnessSumSolid += STONE_HARDNESS; }
            if (standable[i]) standCount++;
        }
        CostPyramid pyr = grid.pyramid();
        int row = pyr.rowFor(0, rx, ry, rz);
        RegionFragments rf = pyr.ensureFragments(0, row);
        FragmentBuilder.build(passable, standable, G, passCount, standCount, 0, hardnessSumSolid, solidCount, rf);
        pyr.setBuilt(0, row, true);
    }

    /** A uniform {@code KIND_SOLID} region (all-false masks) — a wall of known rock. */
    private void seedSolid(int rx, int ry, int rz) {
        seed(rx, ry, rz, new boolean[CELLS], new boolean[CELLS]);
    }

    /** A full cavern floor: standable slab at local y=0, passable air y 1..14 across the whole footprint. */
    private void seedFloorFull(int rx, int ry, int rz) {
        boolean[] passable = new boolean[CELLS];
        boolean[] standable = new boolean[CELLS];
        for (int x = 0; x < G; x++) {
            for (int z = 0; z < G; z++) {
                standable[idx(x, 0, z)] = true;
                for (int y = 1; y <= 14; y++) passable[idx(x, y, z)] = true;
            }
        }
        seed(rx, ry, rz, passable, standable);
    }

    /** Fill an inclusive region box with uniform SOLID (the enclosing rock a cave is carved out of). */
    private void fillSolidBox(int rxLo, int rxHi, int ryLo, int ryHi, int rzLo, int rzHi) {
        for (int rx = rxLo; rx <= rxHi; rx++)
            for (int ry = ryLo; ry <= ryHi; ry++)
                for (int rz = rzLo; rz <= rzHi; rz++)
                    seedSolid(rx, ry, rz);
    }

    /**
     * Seed a leaf from a boolean floor mask over the (x,z) plane: wherever {@code floorXZ[x*G+z]} is set, a
     * standable slab at local y=0 with 2-tall passable headroom (y=1,2). Faces are exactly the columns the mask
     * reaches, so a localized floor patch yields a localized per-face footprint (the corner-opening idiom).
     */
    private void seedMasks(int rx, int ry, int rz, boolean[] floorXZ) {
        boolean[] passable = new boolean[CELLS];
        boolean[] standable = new boolean[CELLS];
        for (int x = 0; x < G; x++) {
            for (int z = 0; z < G; z++) {
                if (!floorXZ[x * G + z]) continue;
                standable[idx(x, 0, z)] = true;
                passable[idx(x, 1, z)] = true;
                passable[idx(x, 2, z)] = true;
            }
        }
        seed(rx, ry, rz, passable, standable);
    }

    /** A rectangular (x,z) floor patch mask (inclusive bounds). */
    private static boolean[] window(int xLo, int xHi, int zLo, int zHi) {
        boolean[] m = new boolean[G * G];
        for (int x = xLo; x <= xHi; x++)
            for (int z = zLo; z <= zHi; z++)
                m[x * G + z] = true;
        return m;
    }

    /** Union of two (x,z) floor masks. */
    private static boolean[] union(boolean[] a, boolean[] b) {
        boolean[] m = new boolean[G * G];
        for (int i = 0; i < m.length; i++) m[i] = a[i] || b[i];
        return m;
    }

    // ===================================================================================================
    // Small assertion helpers.
    // ===================================================================================================

    private static boolean visits(RegionPathPlan plan, int rx, int ry, int rz) {
        for (int i = 0; i < plan.size(); i++)
            if (plan.rx(i) == rx && plan.ry(i) == ry && plan.rz(i) == rz) return true;
        return false;
    }

    private static String route(RegionPathPlan plan) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < plan.size(); i++) {
            if (i > 0) sb.append(" -> ");
            sb.append('(').append(plan.rx(i)).append(',').append(plan.ry(i)).append(',').append(plan.rz(i))
                    .append(")f").append(plan.fragmentId(i));
        }
        return sb.toString();
    }
}
