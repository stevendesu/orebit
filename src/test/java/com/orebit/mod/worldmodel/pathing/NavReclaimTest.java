package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import net.minecraft.core.BlockPos;

/**
 * Pure-logic proof of the epoch-based retirement grace (DESIGN-background-pathfinding.md §4.1):
 * a retired batch is recycled only once {@code minActive} (the oldest in-flight search's start epoch)
 * has advanced past its retirement epoch — the guard that makes {@code NavStore} recycling safe under
 * background searches. Simulates the planner threads' side by passing explicit {@code minActive}
 * values; no threads needed (the algebra, not the scheduling, is what's under test — the scheduling
 * side is two volatile writes whose ordering PlanExecutor documents).
 */
class NavReclaimTest {

    @Test
    void retiredBatchWaitsOutAPredatingSearch() {
        int before = NavReclaim.pending();
        NavSection[] batch = { NavSection.create(BlockPos.ZERO) };
        long retireEpoch = NavReclaim.epoch();
        NavReclaim.retire(batch);
        assertEquals(before + 1, NavReclaim.pending());

        // A search started AT the retirement epoch is still running (minActive == retireEpoch):
        // the batch must be held — freeing it would yank sections that search may still hold.
        NavReclaim.tick(retireEpoch);
        assertEquals(before + 1, NavReclaim.pending(), "in-flight search at the retire epoch must block the free");

        // The search finished; every active search now post-dates the retirement → reclaimable.
        NavReclaim.tick(Long.MAX_VALUE);
        assertEquals(before, NavReclaim.pending(), "idle pool (minActive = MAX) must drain everything");
    }

    @Test
    void idleDrainsNextTickWhenAsyncOff() {
        // With async off, minActive is always MAX_VALUE — retirement degrades to a one-tick deferral.
        int before = NavReclaim.pending();
        NavReclaim.retire(new NavSection[] { NavSection.create(BlockPos.ZERO) });
        NavReclaim.tick(Long.MAX_VALUE);
        assertEquals(before, NavReclaim.pending());
    }

    @Test
    void fifoDrainStopsAtTheFirstHeldBatch() {
        int before = NavReclaim.pending();
        NavReclaim.retire(new NavSection[] { NavSection.create(BlockPos.ZERO) }); // epoch E1
        long e2 = NavReclaim.epoch();
        NavReclaim.tick(0); // bump only (minActive 0 holds everything)
        NavReclaim.retire(new NavSection[] { NavSection.create(BlockPos.ZERO) }); // epoch E2+1 > E1
        assertEquals(before + 2, NavReclaim.pending());

        // A search that started between the two retirements frees the first batch but not the second.
        NavReclaim.tick(e2 + 1);
        assertEquals(before + 1, NavReclaim.pending(), "only the batch the mid search post-dates may free");

        NavReclaim.tick(Long.MAX_VALUE);
        assertEquals(before, NavReclaim.pending());
        assertTrue(NavReclaim.epoch() > e2);
    }
}
