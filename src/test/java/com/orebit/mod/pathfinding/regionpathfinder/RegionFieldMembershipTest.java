package com.orebit.mod.pathfinding.regionpathfinder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.orebit.mod.Debug;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.worldmodel.hpa.RegionFragments;
import com.orebit.mod.worldmodel.pathing.FullSearchScenarios;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

/**
 * Pins {@link RegionCostField}'s label-slab EXACT slot resolution (label-slab membership) over
 * the HONEYCOMB full-search fixture — the scenario built (s54) precisely because its sealed side pockets
 * steal the old nearest-centroid membership probe. Assertions:
 * <ul>
 *   <li>a mid-belt tunnel cell whose nearest CENTROID is the (reached) +X-face pocket resolves to the
 *       TUNNEL fragment's slot — exact membership, the old code's mis-assignment specimen;</li>
 *   <li>a cell inside an UNREACHED sealed pocket, and a solid (no-fragment) cell, resolve to the region's
 *       cheapest reached slot — the old fallback-scan semantics, now precomputed;</li>
 *   <li>a single-reached-fragment region (GOAL_NOT_IN_WINDOW's tunnel) resolves to its only reached slot —
 *       the ≤1-reached byte-identity class of the design note (§3).</li>
 * </ul>
 */
class RegionFieldMembershipTest {

    private static boolean bootstrapped;

    @BeforeAll
    static void boot() {
        if (!bootstrapped) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            bootstrapped = true;
        }
        BlockPathfinder.LOG_TIMING = false;
        Debug.ENABLED = false;
    }

    private static RegionCostField fieldFor(FullSearchScenarios.Fixture f) {
        return RegionPathfinder.costToGoalField(f.grid, f.minY, f.goalFloor, f.startFloor,
                f.caps.canBreak(), f.caps.canPlace(), f.caps.safeFallDistance(), f.mine, f.place, f.box);
    }

    /** The cheapest settled cost over region {@code (rx,ry,rz)}'s fragment slots ({@code UNREACHED} if none). */
    private static float cheapestRaw(RegionCostField field, int rx, int ry, int rz) {
        float best = RegionCostField.UNREACHED;
        for (int frag = 0; frag < RegionFragments.MAX_FRAGMENTS; frag++) {
            float c = field.rawCost(rx, ry, rz, frag);
            if (c < best) best = c;
        }
        return best;
    }

    @Test
    void honeycomb_exactMembershipBeatsCentroidSteal() {
        FullSearchScenarios.Fixture f = FullSearchScenarios.build(FullSearchScenarios.Scenario.HONEYCOMB);
        RegionCostField field = fieldFor(f);

        // Mid-belt region (3,0,0): fragments (flood-seed order) #0 = +X-face pocket (x13..15, z0..2),
        // #1 = the through-tunnel (z6..9), #2 = interior pocket, #3 = -X-face pocket. Preconditions that
        // make the steal specimen meaningful: BOTH the pocket #0 and the tunnel #1 slots are reached (#0
        // through the unauthored optimistic-air -Z neighbour), the sealed #2/#3 are not.
        assertTrue(field.rawCost(3, 0, 0, 0) < RegionCostField.UNREACHED, "+X pocket slot reached (via air belt)");
        assertTrue(field.rawCost(3, 0, 0, 1) < RegionCostField.UNREACHED, "tunnel slot reached");
        assertTrue(field.rawCost(3, 0, 0, 2) >= RegionCostField.UNREACHED, "interior pocket sealed ⇒ unreached");
        assertTrue(field.rawCost(3, 0, 0, 3) >= RegionCostField.UNREACHED, "-X pocket sealed mid-belt ⇒ unreached");

        // The steal specimen: tunnel cell world (63,1,6) = local (15,1,6) — Manhattan 7 to pocket #0's
        // centroid (≈62,1,0) vs 9 to the tunnel's (≈55,1,7), so the old nearest-centroid probe resolved the
        // REACHED pocket slot #0. Exact membership must resolve the tunnel fragment #1.
        int slot = field.resolvedSlotAt(63, 1, 6);
        assertTrue(slot >= 0, "in-box reached region must resolve a slot");
        assertEquals(1, slot % RegionFragments.MAX_FRAGMENTS,
                "a tunnel cell resolves to the tunnel fragment, not the centroid-nearer pocket");

        // An UNREACHED-fragment cell (interior pocket, label #2) falls back to the cheapest reached slot.
        int pocketSlot = field.resolvedSlotAt(48 + 7, 1, 12);
        assertTrue(pocketSlot >= 0, "fallback must still resolve");
        assertEquals(cheapestRaw(field, 3, 0, 0), field.rawCost(3, 0, 0, pocketSlot % RegionFragments.MAX_FRAGMENTS),
                "an unreached-fragment cell reads the region's cheapest reached slot");

        // A solid cell (in no kept fragment, label -1) reads the same cheapest reached slot.
        int solidSlot = field.resolvedSlotAt(48 + 4, 1, 4);
        assertEquals(pocketSlot, solidSlot, "a no-fragment cell reads the cheapest reached slot");
    }

    @Test
    void singleReachedRegion_resolvesItsOnlySlot() {
        FullSearchScenarios.Fixture f = FullSearchScenarios.build(FullSearchScenarios.Scenario.GOAL_NOT_IN_WINDOW);
        RegionCostField field = fieldFor(f);

        // Mid-corridor region (3,0,0) of the plain tunnel world: exactly one fragment ⇒ exactly one
        // reachable slot. Every in-region query must resolve to it (the §3 byte-identity class).
        int reached = -1, count = 0;
        for (int frag = 0; frag < RegionFragments.MAX_FRAGMENTS; frag++) {
            if (field.rawCost(3, 0, 0, frag) < RegionCostField.UNREACHED) { reached = frag; count++; }
        }
        assertEquals(1, count, "the plain tunnel region has exactly one reached fragment slot");
        int slot = field.resolvedSlotAt(48 + 8, 2, 8); // a tunnel cell
        assertEquals(reached, slot % RegionFragments.MAX_FRAGMENTS, "resolves the only reached slot");
        int slotSolid = field.resolvedSlotAt(48 + 8, 1, 2); // a solid wall cell in the same region
        assertEquals(reached, slotSolid % RegionFragments.MAX_FRAGMENTS,
                "a solid cell in a single-reached region resolves the same slot");
    }
}
