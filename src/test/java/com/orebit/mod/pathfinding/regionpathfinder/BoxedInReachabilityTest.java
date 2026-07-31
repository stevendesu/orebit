package com.orebit.mod.pathfinding.regionpathfinder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.orebit.mod.worldmodel.hpa.CostPyramid;
import com.orebit.mod.worldmodel.hpa.FragmentBuilder;
import com.orebit.mod.worldmodel.hpa.RegionFragments;
import com.orebit.mod.worldmodel.hpa.RegionGrid;

import net.minecraft.core.BlockPos;

/**
 * #4 Increment 1 — the boxed-in negative-reachability harvest (DESIGN-boxed-in-reachability §2/§3/§5,
 * FINDINGS-reverse-reachability §7). Guards the two load-bearing invariants of the harvest, on hand-authored
 * headless worlds (the {@link RegionFloodGuardTest} idiom — real {@link FragmentBuilder} flood over authored
 * masks):
 * <ol>
 *   <li><b>Byte-identical without a proof (INV BR-3).</b> A field built by the NORMAL per-search path
 *       ({@code harvest == false}) carries NO INFINITE set, so {@link RegionCostField#isBlocked} is
 *       {@code false} for every cell — even one inside a genuinely sealed, disconnected built pocket. The
 *       block-A* hard reject that consults it is therefore a no-op on every ordinary search.</li>
 *   <li><b>True under a closed flood + built + unreached (soundness, §2).</b> With the harvest flag on a
 *       CLOSED flood (drained to exhaustion within the box, no backstop, no out-of-box reject), a built but
 *       unreached region IS flagged INFINITE — while the reached goal region and out-of-box cells are not.</li>
 *   <li><b>Suppressed under an OPEN flood (the §2 Leg-2 soundness guard).</b> When the flood rejects any
 *       out-of-box target, it is not closed, so the harvest marks NOTHING — even a sealed, built, unreached
 *       in-box region stays optimistic (no false INFINITE, no false give-up).</li>
 * </ol>
 */
public class BoxedInReachabilityTest {

    private static final int G = 16;
    private static final int CELLS = G * G * G;
    private static final int MINY = 0;
    private static final int STONE = 8;

    private RegionGrid grid;

    private static int idx(int x, int y, int z) { return (y << 8) | (z << 4) | x; }

    @BeforeEach
    void setUp() {
        grid = RegionGrid.headless(MINY);
        RegionPathfinder.TRACE = false;
    }

    // ---- (1)/(2) a sealed, disconnected built pocket beside a sealed goal region ------------------------

    /** Author a world with a goal floor region at (0,1,0) and a DISCONNECTED floor pocket at (3,1,0), each
     *  fully walled by unmineable solid on all six faces (so a no-break flood stays local and CLOSES), with a
     *  solid wall region between them. */
    private void sealedGoalAndPocket() {
        seedFloor(0, 1, 0);   // goal region
        seedFloor(3, 1, 0);   // disconnected pocket (built, but walled off from the goal)
        // Seal the goal region's six faces.
        seedSolid(1, 1, 0); seedSolid(-1, 1, 0);
        seedSolid(0, 2, 0); seedSolid(0, 0, 0);
        seedSolid(0, 1, 1); seedSolid(0, 1, -1);
        // Seal the pocket's six faces (2,1,0 doubles as the wall toward the goal side).
        seedSolid(4, 1, 0); seedSolid(2, 1, 0);
        seedSolid(3, 2, 0); seedSolid(3, 0, 0);
        seedSolid(3, 1, 1); seedSolid(3, 1, -1);
    }

    /** The box enclosing the goal region and the pocket (pad 1). */
    private static RegionPathfinder.RegionBox boxGoalPocket() {
        return RegionPathfinder.RegionBox.around(0, 1, 0, 3, 1, 0, 1);
    }

    @Test
    void normalFieldCarriesNoProof_isBlockedFalseEverywhere() {
        sealedGoalAndPocket();
        BlockPos goal = feet(0, 1, 0);
        // NORMAL per-search build (harvest == false): no INFINITE set is harvested at all.
        RegionCostField field = RegionPathfinder.costToGoalField(grid, MINY, goal, feet(3, 1, 0),
                /*canBreak*/ false, /*canPlace*/ false, /*safeFall*/ 3,
                RegionMineModel.DEFAULT, RegionPlaceModel.DEFAULT, boxGoalPocket());
        // Even the genuinely sealed, disconnected pocket is NOT blocked — the prune is inert (byte-identical).
        assertFalse(field.isInfiniteRegion(3, 1, 0), "no harvest ⇒ the pocket must not be flagged INFINITE");
        assertFalse(field.isBlocked(56, 17, 8), "no harvest ⇒ isBlocked must be false inside the pocket");
        assertFalse(field.isBlocked(8, 17, 8), "no harvest ⇒ isBlocked must be false at the goal");
    }

    @Test
    void closedHarvest_flagsBuiltUnreachedPocketInfinite() {
        sealedGoalAndPocket();
        BlockPos goal = feet(0, 1, 0);
        // HARVEST build: full drain (no fat-skeleton early exit). The goal region is sealed, so a no-break flood
        // settles it alone and exhausts WITHOUT any out-of-box reject → a CLOSED flood.
        RegionCostField field = RegionPathfinder.costToGoalField(grid, MINY, goal, feet(3, 1, 0),
                /*canBreak*/ false, /*canPlace*/ false, /*safeFall*/ 3,
                RegionMineModel.DEFAULT, RegionPlaceModel.DEFAULT, boxGoalPocket(), /*harvest*/ true);

        assertTrue(RegionPathfinder.lastFieldClosedFlood(), "the sealed goal must yield a CLOSED flood");
        assertFalse(RegionPathfinder.lastFieldOutOfBoxRejected(), "a sealed goal never rejects out-of-box");

        // The disconnected built pocket is provably goal-disconnected ⇒ INFINITE ⇒ isBlocked.
        assertTrue(field.isInfiniteRegion(3, 1, 0), "closed flood ⇒ the built unreached pocket is INFINITE");
        assertTrue(field.isBlocked(56, 17, 8), "isBlocked must be true inside the proven-dead pocket");
        // The reached goal region stays reachable; out-of-box cells are never blocked.
        assertFalse(field.isInfiniteRegion(0, 1, 0), "the reached goal region is never INFINITE");
        assertFalse(field.isBlocked(8, 17, 8), "the goal cell is never blocked");
        assertFalse(field.isBlocked(168, 17, 8), "an out-of-box cell is never blocked");
    }

    // ---- (3) an OPEN flood (out-of-box reject) suppresses the harvest -----------------------------------

    @Test
    void openFlood_harvestsNothing() {
        // Goal (0,1,0) — (1,1,0) — (2,1,0): a straight floor corridor. The box stops at rx=1, so the flood
        // reaches (1,1,0) then tries to cross into the OUT-OF-BOX (2,1,0) → an out-of-box reject → NOT closed.
        seedFloor(0, 1, 0);
        seedFloor(1, 1, 0);
        seedFloor(2, 1, 0);              // out of the box below (rx = 2 > maxRx = 1)
        // A sealed, built, unreached probe region INSIDE the box: it WOULD be INFINITE under a closed flood.
        seedSolid(-1, 1, 0);
        // Corridor seals (top/bottom/sides) so the only out-of-box escape is the +X end.
        seedSolid(0, 0, 0); seedSolid(1, 0, 0);
        seedSolid(0, 2, 0); seedSolid(1, 2, 0);
        seedSolid(0, 1, 1); seedSolid(1, 1, 1);
        seedSolid(0, 1, -1); seedSolid(1, 1, -1);

        RegionPathfinder.RegionBox box = RegionPathfinder.RegionBox.around(0, 1, 0, 0, 1, 0, 1); // rx∈[-1,1]
        RegionCostField field = RegionPathfinder.costToGoalField(grid, MINY, feet(0, 1, 0), feet(0, 1, 0),
                /*canBreak*/ false, /*canPlace*/ false, /*safeFall*/ 3,
                RegionMineModel.DEFAULT, RegionPlaceModel.DEFAULT, box, /*harvest*/ true);

        assertTrue(RegionPathfinder.lastFieldOutOfBoxRejected(), "the corridor must reject an out-of-box target");
        assertFalse(RegionPathfinder.lastFieldClosedFlood(), "an out-of-box reject disqualifies the closed flood");
        // Harvest suppressed: even the sealed built-unreached probe region stays optimistic (no false INFINITE).
        assertFalse(field.isInfiniteRegion(-1, 1, 0), "open flood ⇒ NO harvest ⇒ no INFINITE anywhere");
        assertFalse(field.isBlocked(-8, 17, 8), "open flood ⇒ isBlocked must be false everywhere");
    }

    // ---- PROACTIVE rework: the FAR-TOMB (goal-rooted harvest closes despite unbuilt terrain between) -----
    //
    // These pin the decision PathPlan.maybeProactiveBoxedIn makes at region-plan-entry: run the harvest-mode
    // goal-rooted flood (box around(bot, JOURNEY GOAL, pad 3)) and read field.isBlocked(botFloor). The
    // key invariant is that the flood roots AT THE GOAL and closes inside a built tomb regardless of the
    // (optimistic) unbuilt terrain between the bot and the goal — so the born-boxed-in give-up fires without
    // the bot ever wandering (the reactive give-up would never fire while unbuilt reads as passable AIR).

    /** FAR-TOMB (positive): a goal fully walled by UNMINEABLE BUILT solid on ALL six perimeter regions, the
     *  bot's region BUILT but far outside, with the regions between left UNBUILT (optimistic). The goal-rooted
     *  no-break harvest settles the goal alone and EXHAUSTS without reaching the box edge → CLOSED → the bot's
     *  own region is built-but-unreached → INFINITE → isBlocked(bot) ⇒ boxedInProven ⇒ born-boxed-in give-up. */
    @Test
    void proactiveFarTomb_goalSealedBotOutside_isBlockedTrue() {
        seedFloor(0, 1, 0);                                 // the journey goal region
        seedSolid(1, 1, 0);  seedSolid(-1, 1, 0);           // seal ±X
        seedSolid(0, 2, 0);  seedSolid(0, 0, 0);            // seal ±Y
        seedSolid(0, 1, 1);  seedSolid(0, 1, -1);           // seal ±Z
        seedFloor(6, 1, 0);                                 // the bot's region — BUILT, far; rx 2..5 UNBUILT

        BlockPos goal = feet(0, 1, 0);
        BlockPos bot  = feet(6, 1, 0);
        // The exact box maybeProactiveBoxedIn builds: around(bot, goal, pad 3).
        RegionPathfinder.RegionBox box = RegionPathfinder.RegionBox.around(6, 1, 0, 0, 1, 0, 3);
        RegionCostField field = RegionPathfinder.costToGoalField(grid, MINY, goal, bot,
                /*canBreak*/ false, /*canPlace*/ false, /*safeFall*/ 3,
                RegionMineModel.DEFAULT, RegionPlaceModel.DEFAULT, box, /*harvest*/ true);

        assertTrue(RegionPathfinder.lastFieldClosedFlood(),
                "the sealed goal closes the flood inside the tomb despite the unbuilt gap");
        assertFalse(RegionPathfinder.lastFieldOutOfBoxRejected(),
                "a fully-sealed goal never reaches the box edge → no out-of-box reject");
        assertTrue(field.isBlocked(bot.getX(), bot.getY(), bot.getZ()),
                "far-tomb: the bot's own region is INFINITE ⇒ boxedInProven ⇒ born-boxed-in give-up");
        assertFalse(field.isBlocked(goal.getX(), goal.getY(), goal.getZ()),
                "the goal cell is reached, never blocked");
    }

    /** CONVERSE (no false give-up) — a BUILT floor corridor connects the bot to the goal: the goal-rooted
     *  harvest reaches the bot's region, so it is NOT INFINITE → NOT boxed-in → the plan proceeds. */
    @Test
    void proactiveReachable_connectedCorridor_notBoxedIn() {
        seedFloor(0, 1, 0); seedFloor(1, 1, 0); seedFloor(2, 1, 0); seedFloor(3, 1, 0);
        BlockPos goal = feet(0, 1, 0);
        BlockPos bot  = feet(3, 1, 0);
        RegionPathfinder.RegionBox box = RegionPathfinder.RegionBox.around(3, 1, 0, 0, 1, 0, 3);
        RegionCostField field = RegionPathfinder.costToGoalField(grid, MINY, goal, bot,
                false, false, 3, RegionMineModel.DEFAULT, RegionPlaceModel.DEFAULT, box, /*harvest*/ true);
        assertFalse(field.isBlocked(bot.getX(), bot.getY(), bot.getZ()),
                "a reachable goal ⇒ the bot's region is reached ⇒ never boxed-in ⇒ proceed");
    }

    /** CONVERSE (no false give-up) — the goal is sealed on FIVE built faces but its +X border is UNBUILT
     *  (optimistic). The flood escapes through the unbuilt border and rejects an out-of-box target → OPEN
     *  flood → NO harvest → nothing INFINITE → NOT boxed-in (exploration into unbuilt is legitimate). */
    @Test
    void proactiveUnbuiltBorder_openFlood_notBoxedIn() {
        seedFloor(0, 1, 0);
        seedSolid(-1, 1, 0);                                // seal −X
        seedSolid(0, 2, 0);  seedSolid(0, 0, 0);            // seal ±Y
        seedSolid(0, 1, 1);  seedSolid(0, 1, -1);           // seal ±Z
        // +X (1,1,0) deliberately LEFT UNBUILT → an optimistic escape hatch.
        seedFloor(6, 1, 0);                                 // a built region that WOULD be INFINITE under a closed flood

        BlockPos goal = feet(0, 1, 0);
        BlockPos bot  = feet(6, 1, 0);
        RegionPathfinder.RegionBox box = RegionPathfinder.RegionBox.around(6, 1, 0, 0, 1, 0, 3);
        RegionCostField field = RegionPathfinder.costToGoalField(grid, MINY, goal, bot,
                false, false, 3, RegionMineModel.DEFAULT, RegionPlaceModel.DEFAULT, box, /*harvest*/ true);

        assertTrue(RegionPathfinder.lastFieldOutOfBoxRejected(),
                "the unbuilt +X border lets the flood escape and reject an out-of-box target");
        assertFalse(RegionPathfinder.lastFieldClosedFlood(),
                "an unbuilt perimeter border ⇒ open flood ⇒ inconclusive (never a give-up)");
        assertFalse(field.isBlocked(bot.getX(), bot.getY(), bot.getZ()),
                "open flood ⇒ no harvest ⇒ NOT boxed-in ⇒ proceed optimistically");
    }

    // ---- #4 REWORK: isSealedWithin — the multi-level goal-box sealed probe (verdict = closedFlood) -------
    //
    // The multi-level scan (PathPlan.maybeProactiveBoxedIn) calls RegionPathfinder.isSealedWithin at each level
    // L6→L0 with a small goal-centered box; a CLOSED flood at any level ⇒ the goal is sealed (unreachable from
    // anywhere, bot position irrelevant). These pin the wrapper's verdict at L0 (byte-identical to the L0 flood)
    // and confirm the level-parameterized flood reads coarse fragments + the closedFlood verdict holds at L1.

    @Test
    void isSealedWithin_L0_sealedGoal_true() {
        sealedGoalAndPocket();
        assertTrue(RegionPathfinder.isSealedWithin(grid, MINY, feet(0, 1, 0), 0, 3,
                        /*canBreak*/ false, /*canPlace*/ false, /*safeFall*/ 3,
                        RegionMineModel.DEFAULT, RegionPlaceModel.DEFAULT),
                "a goal sealed on all six built faces ⇒ closed flood ⇒ sealed at L0");
    }

    @Test
    void isSealedWithin_L0_connectedCorridor_false() {
        seedFloor(0, 1, 0); seedFloor(1, 1, 0); seedFloor(2, 1, 0); seedFloor(3, 1, 0);
        assertFalse(RegionPathfinder.isSealedWithin(grid, MINY, feet(0, 1, 0), 0, 3,
                        false, false, 3, RegionMineModel.DEFAULT, RegionPlaceModel.DEFAULT),
                "an open corridor reaches unbuilt neighbours and leaves the box ⇒ not sealed");
    }

    @Test
    void isSealedWithin_L0_unbuiltBorder_false() {
        seedFloor(0, 1, 0);
        seedSolid(-1, 1, 0);
        seedSolid(0, 2, 0); seedSolid(0, 0, 0);
        seedSolid(0, 1, 1); seedSolid(0, 1, -1);
        // +X (1,1,0) left UNBUILT — an optimistic escape hatch (§6: never claim boxed-in over unbuilt terrain).
        assertFalse(RegionPathfinder.isSealedWithin(grid, MINY, feet(0, 1, 0), 0, 3,
                        false, false, 3, RegionMineModel.DEFAULT, RegionPlaceModel.DEFAULT),
                "a goal bordered by unbuilt terrain reads optimistically ⇒ escapes ⇒ not sealed");
    }

    @Test
    void isSealedWithin_coarseLevel_interiorPocket_sealedAtL0andL1() {
        // An air pocket fully interior to leaf (0,0,0) — its air touches no leaf face, so FragmentBuilder yields
        // one fragment with faceMask 0 (sealed within the leaf). Because it reaches no leaf face it reaches no
        // PARENT face either, so the L1 roll-up (isSealedWithin's ensureLevel builds the L1 node from these 8
        // children) keeps it sealed. Validates the level-parameterized flood at a COARSE level: the coarse
        // fragments are read and the closedFlood verdict holds. The 7 sibling leaves are solid (fully-built L1).
        seedInteriorPocket(0, 0, 0);
        seedSolid(1, 0, 0); seedSolid(0, 1, 0); seedSolid(1, 1, 0);
        seedSolid(0, 0, 1); seedSolid(1, 0, 1); seedSolid(0, 1, 1); seedSolid(1, 1, 1);
        BlockPos goal = pocketFeet(0, 0, 0);
        assertTrue(RegionPathfinder.isSealedWithin(grid, MINY, goal, 0, 3,
                        false, false, 3, RegionMineModel.DEFAULT, RegionPlaceModel.DEFAULT),
                "interior pocket ⇒ closed flood ⇒ sealed at L0");
        assertTrue(RegionPathfinder.isSealedWithin(grid, MINY, goal, 1, 3,
                        false, false, 3, RegionMineModel.DEFAULT, RegionPlaceModel.DEFAULT),
                "interior pocket rolls up sealed ⇒ sealed at the coarse L1 too");
    }

    // ---- GOAL-SEED regression (owner ruling 2026-07-31): containment/faced-only, never a centroid pocket --

    /**
     * The vine-jungle drifted-world false positive (2026-07-31): ONE region holding BOTH an open surface
     * fragment (a corridor whose only face openings sit on the -X face) AND a faceless interior pocket
     * whose default centroid — the REGION CENTER — out-attracts the corridor's face-averaged centroid for
     * a mid-region goal (pocket Manhattan ≈10 vs corridor ≈12 from the goal cell). The old
     * nearest-centroid goal seed anchored the seal flood in the pocket: a faceless no-break seed emits
     * ZERO edges, the flood closes after one expansion, and the prover reported "walled off" on trivially
     * walkable terrain. The {@code anchorFragment} seed (containment-first; faced-only centroid fallback —
     * the same resolution the start anchor got for the t=35 cliff drain) resolves the corridor fragment
     * the goal actually stands in; the flood then escapes -X through the neighbour floor into unbuilt
     * terrain and out of the box ⇒ NOT sealed.
     */
    @Test
    void isSealedWithin_goalSeedResolvesContainingFragment_notCentroidPocket() {
        boolean[] passable = new boolean[CELLS];
        boolean[] standable = new boolean[CELLS];
        for (int x = 0; x <= 14; x++) {            // corridor: floor y0 + air y1..3 at z=8; x15 solid ⇒ -X-only faces
            standable[idx(x, 0, 8)] = true;
            for (int y = 1; y <= 3; y++) passable[idx(x, y, 8)] = true;
        }
        for (int x = 4; x <= 11; x++) {            // faceless interior pocket above the corridor: floor y4, air y5..10
            for (int z = 4; z <= 11; z++) {
                standable[idx(x, 4, z)] = true;
                for (int y = 5; y <= 10; y++) passable[idx(x, y, z)] = true;
            }
        }
        seed(0, 1, 0, passable, standable, STONE);
        seedFloor(-1, 1, 0);                       // the corridor's -X escape into open (then unbuilt) terrain
        BlockPos goal = new BlockPos(11, MINY + 16 + 1, 8); // feet ON the corridor floor, mid-region
        assertFalse(RegionPathfinder.isSealedWithin(grid, MINY, goal, 0, 3,
                        /*canBreak*/ false, /*canPlace*/ false, /*safeFall*/ 3,
                        RegionMineModel.DEFAULT, RegionPlaceModel.DEFAULT),
                "the goal's CONTAINING corridor fragment must seed the flood (containment/faced-only beats "
                        + "the faceless pocket's region-center centroid) ⇒ escapes ⇒ not sealed");
    }

    // ---- seed helpers (real FragmentBuilder flood; the RegionFloodGuardTest idiom) ----------------------

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

    /** An air pocket fully INTERIOR to a leaf: standable floor at local y=4, passable air y5..10, all within
     *  x,z ∈ [4,11]. The pocket touches no leaf face, so FragmentBuilder yields one fragment with faceMask 0
     *  (sealed within the leaf) — and, reaching no leaf face, it stays sealed when rolled up to any parent. */
    private void seedInteriorPocket(int rx, int ry, int rz) {
        boolean[] passable = new boolean[CELLS];
        boolean[] standable = new boolean[CELLS];
        for (int x = 4; x <= 11; x++) {
            for (int z = 4; z <= 11; z++) {
                standable[idx(x, 4, z)] = true;
                for (int y = 5; y <= 10; y++) passable[idx(x, y, z)] = true;
            }
        }
        seed(rx, ry, rz, passable, standable, STONE);
    }

    /** Feet cell inside the pocket seeded by {@link #seedInteriorPocket} (local 8,5,8 — on the y=4 floor). */
    private static BlockPos pocketFeet(int rx, int ry, int rz) {
        return new BlockPos((rx << 4) + 8, MINY + (ry << 4) + 5, (rz << 4) + 8);
    }

    private static BlockPos feet(int rx, int ry, int rz) {
        return new BlockPos((rx << 4) + 8, MINY + (ry << 4) + 1, (rz << 4) + 8);
    }
}
