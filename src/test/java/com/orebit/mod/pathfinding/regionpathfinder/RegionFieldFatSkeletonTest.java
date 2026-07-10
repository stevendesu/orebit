package com.orebit.mod.pathfinding.regionpathfinder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.orebit.mod.Debug;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.worldmodel.hpa.RegionAddress;
import com.orebit.mod.worldmodel.hpa.RegionFragments;
import com.orebit.mod.worldmodel.pathing.FullSearchScenarios;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;

/**
 * Pins the s53 owner-ratified <b>fat-skeleton early exit + frontier floor</b> semantics of
 * {@link RegionPathfinder#costToGoalField} / {@link RegionCostField} by comparing, for every
 * (goal kind × box size) of the {@link FullSearchScenarios#fieldWorld() field-bench world}, an EXHAUSTIVE build
 * (the start-less overload) against a FAT-SKELETON build (the bot floor cell passed):
 * <ol>
 *   <li><b>Floor invariant</b> — every slot the fat build left unsettled has an exhaustive value ≥ the fat
 *       build's frontier floor AND ≥ its {@code cheb × MIN_CROSS} distance bound (both are provable lower
 *       bounds — Dijkstra's nondecreasing settle order, and the per-crossing relax floor; see
 *       {@link RegionCostField}). Every slot the fat build DID settle is byte-identical to the exhaustive value
 *       (the early-exit run is an exact prefix of the exhaustive run).</li>
 *   <li><b>Fat-skeleton coverage</b> — every region on and within Chebyshev 1 of the reconstructed optimal
 *       goal→start chain has identical slot values in both builds (the termination rule waits for exactly
 *       these regions' reached work).</li>
 *   <li><b>Out-of-box reads</b> — {@code costAt} outside the box returns {@code max(floor, cheb × MIN_CROSS)},
 *       never {@code UNREACHED} (the owner's latent-pathology fix: a detour that leaves the box keeps field
 *       guidance).</li>
 * </ol>
 * Also prints the per-fixture settle savings (the early exit's whole point) as the recorded evidence for the
 * docs/Optimizations/12_field_build.md measurement section.
 */
class RegionFieldFatSkeletonTest {

    private static final int[] BOX_SIZES = { 3, 5, 7, 10 };
    private static final float EPS = 1e-3f;
    private static final int MAX_FRAGMENTS = RegionFragments.MAX_FRAGMENTS;

    private static boolean bootstrapped;

    @BeforeAll
    static void boot() {
        if (!bootstrapped) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            bootstrapped = true;
        }
        RegionPathfinder.TRACE = false;
        Debug.ENABLED = false;
    }

    /** One exhaustive-vs-fat build pair over the field world, plus the diagnostics the assertions need. */
    private static final class Pair {
        final String label;
        final RegionPathfinder.RegionBox box;
        final int grx, gry, grz;
        final RegionCostField exhaustive;
        final RegionCostField fat;
        final int exhaustiveSettles, fatSettles;
        final boolean fatEarlyExit;

        Pair(FullSearchScenarios.FieldWorld w, BlockPos goal, String goalKind, int n) {
            this.label = goalKind + "/box" + n;
            this.grx = RegionAddress.regionX(goal.getX(), 0);
            this.gry = RegionAddress.regionY(goal.getY(), 0, w.minY);
            this.grz = RegionAddress.regionZ(goal.getZ(), 0);
            this.box = RegionFieldBuildBenchmark.boxFor(grx, gry, grz, n);
            BotCaps caps = BotCaps.BREAK_PLACE;
            this.exhaustive = RegionPathfinder.costToGoalField(w.grid, w.minY, goal,
                    caps.canBreak(), caps.canPlace(), caps.safeFallDistance(),
                    RegionMineModel.DEFAULT, RegionPlaceModel.DEFAULT, box);
            this.exhaustiveSettles = RegionPathfinder.lastFieldSettles();
            assertFalse(RegionPathfinder.lastFieldEarlyExit(), label + ": start-less build must exhaust");
            this.fat = RegionPathfinder.costToGoalField(w.grid, w.minY, goal,
                    RegionFieldBuildBenchmark.startFloorFor(n),
                    caps.canBreak(), caps.canPlace(), caps.safeFallDistance(),
                    RegionMineModel.DEFAULT, RegionPlaceModel.DEFAULT, box);
            this.fatSettles = RegionPathfinder.lastFieldSettles();
            this.fatEarlyExit = RegionPathfinder.lastFieldEarlyExit();
        }

        int chebToGoal(int rx, int ry, int rz) {
            return Math.max(Math.abs(rx - grx), Math.max(Math.abs(ry - gry), Math.abs(rz - grz)));
        }
    }

    private static java.util.List<Pair> buildAll() {
        FullSearchScenarios.FieldWorld w = FullSearchScenarios.fieldWorld();
        java.util.List<Pair> pairs = new java.util.ArrayList<>();
        for (int n : BOX_SIZES) {
            pairs.add(new Pair(w, w.surfaceGoalFloor, "SURFACE", n));
            pairs.add(new Pair(w, w.buriedGoalFloor, "BURIED", n));
        }
        return pairs;
    }

    /** (1) Floor invariant + settled-prefix identity, over every slot of every fixture. */
    @Test
    void floorIsALowerBoundOnEveryUnsettledSlot() {
        for (Pair p : buildAll()) {
            assertTrue(p.fatEarlyExit, p.label + ": fat-skeleton build should early-exit on this fixture");
            float floor = p.fat.floor();
            assertTrue(floor > 0f, p.label + ": frontier floor should be a positive settled max");
            int checkedUnsettled = 0, checkedSettled = 0;
            for (int ry = p.box.minRy; ry <= p.box.maxRy; ry++) {
                for (int rz = p.box.minRz; rz <= p.box.maxRz; rz++) {
                    for (int rx = p.box.minRx; rx <= p.box.maxRx; rx++) {
                        for (int f = 0; f < MAX_FRAGMENTS; f++) {
                            float eV = p.exhaustive.rawCost(rx, ry, rz, f);
                            float fV = p.fat.rawCost(rx, ry, rz, f);
                            if (fV < RegionCostField.UNREACHED) {
                                // Prefix property: anything the early-exit run settled carries its exhaustive value.
                                assertEquals(eV, fV, EPS, p.label + ": settled slot (" + rx + "," + ry + "," + rz
                                        + ")#" + f + " differs from the exhaustive build");
                                checkedSettled++;
                            } else if (eV < RegionCostField.UNREACHED) {
                                // Unsettled in the fat build: its true value must respect BOTH lower bounds.
                                assertTrue(eV >= floor - EPS, p.label + ": exhaustive value " + eV + " of unsettled slot ("
                                        + rx + "," + ry + "," + rz + ")#" + f + " undercuts the frontier floor " + floor);
                                float distBound = p.chebToGoal(rx, ry, rz) * RegionCostField.MIN_CROSS;
                                assertTrue(eV >= distBound - EPS, p.label + ": exhaustive value " + eV
                                        + " of unsettled slot (" + rx + "," + ry + "," + rz + ")#" + f
                                        + " undercuts the cheb×MIN_CROSS bound " + distBound);
                                checkedUnsettled++;
                            }
                        }
                    }
                }
            }
            System.out.println("[FatSkeleton] " + p.label + ": settles exhaustive=" + p.exhaustiveSettles
                    + " fat=" + p.fatSettles + " (saved " + (p.exhaustiveSettles - p.fatSettles)
                    + ", " + String.format("%.0f%%", 100.0 * (p.exhaustiveSettles - p.fatSettles)
                            / Math.max(1, p.exhaustiveSettles))
                    + ")  floor=" + p.fat.floor()
                    + "  slots settled=" + checkedSettled + " floored=" + checkedUnsettled);
            assertTrue(checkedSettled > 0, p.label + ": fat build settled nothing — fixture broken");
        }
    }

    /** (2) Fat-skeleton coverage: chain regions and their Chebyshev-1 neighbours are identical in both builds. */
    @Test
    void fatSkeletonRegionsMatchExhaustiveBuild() {
        for (Pair p : buildAll()) {
            assertNull(p.exhaustive.chainRegions, p.label + ": exhaustive build must record no chain");
            int[] chain = p.fat.chainRegions;
            assertNotNull(chain, p.label + ": fat build must record the goal→start chain");
            assertTrue(chain.length >= 3, p.label + ": chain must contain at least the start region");
            for (int c = 0; c < chain.length; c += 3) {
                int crx = chain[c], cry = chain[c + 1], crz = chain[c + 2];
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            int rx = crx + dx, ry = cry + dy, rz = crz + dz;
                            if (!p.box.contains(rx, ry, rz)) continue;
                            for (int f = 0; f < MAX_FRAGMENTS; f++) {
                                assertEquals(p.exhaustive.rawCost(rx, ry, rz, f), p.fat.rawCost(rx, ry, rz, f), EPS,
                                        p.label + ": fat-skeleton slot (" + rx + "," + ry + "," + rz + ")#" + f
                                        + " (chain step (" + crx + "," + cry + "," + crz
                                        + ") ±1) differs from the exhaustive build");
                            }
                        }
                    }
                }
            }
        }
    }

    /** (3) Out-of-box and unsettled-region reads return {@code max(floor, cheb × MIN_CROSS)}, never UNREACHED. */
    @Test
    void flooredReadsCarryTheGoalAnchoredBound() {
        for (Pair p : buildAll()) {
            // Far out-of-box cell: 40 regions +x of the box edge (region coords are world>>4 at level 0).
            int rx = p.box.maxRx + 40, ry = p.gry, rz = p.grz;
            float rc = p.fat.costAt((rx << 4) + 8, (ry << 4) + 8, (rz << 4) + 8);
            assertTrue(rc < RegionCostField.UNREACHED, p.label + ": out-of-box costAt must not be UNREACHED");
            float expected = Math.max(p.fat.floor(), p.chebToGoal(rx, ry, rz) * RegionCostField.MIN_CROSS);
            assertEquals(expected, rc, EPS, p.label + ": out-of-box costAt must be max(floor, cheb×MIN_CROSS)");
            // Far out-of-box, the distance bound dominates any frontier floor — the goal-anchored gradient.
            assertTrue(rc >= 40 * RegionCostField.MIN_CROSS - EPS,
                    p.label + ": the cheb×MIN_CROSS gradient must dominate far out of the box");

            // An in-box region the fat build never settled ANY slot of (exists once the early exit engages on
            // the larger boxes): costAt must read the same floored bound, not UNREACHED.
            outer:
            for (int iry = p.box.minRy; iry <= p.box.maxRy; iry++) {
                for (int irz = p.box.minRz; irz <= p.box.maxRz; irz++) {
                    for (int irx = p.box.minRx; irx <= p.box.maxRx; irx++) {
                        boolean anySettled = false;
                        for (int f = 0; f < MAX_FRAGMENTS && !anySettled; f++) {
                            anySettled = p.fat.rawCost(irx, iry, irz, f) < RegionCostField.UNREACHED;
                        }
                        if (!anySettled) {
                            float inRc = p.fat.costAt((irx << 4) + 8, (iry << 4) + 8, (irz << 4) + 8);
                            float inExpected = Math.max(p.fat.floor(),
                                    p.chebToGoal(irx, iry, irz) * RegionCostField.MIN_CROSS);
                            assertEquals(inExpected, inRc, EPS, p.label + ": unsettled in-box region ("
                                    + irx + "," + iry + "," + irz + ") costAt must be max(floor, cheb×MIN_CROSS)");
                            break outer;
                        }
                    }
                }
            }
        }
    }
}
