package com.orebit.mod.pathfinding.async;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The realized-crossings leg of the {@link PlanHandle} mailbox (Fix A): a failed search's realized set rides
 * {@link PlanHandle#complete} to the tick thread; an executor-rejected handle (which never ran a search)
 * carries none.
 */
class PlanHandleRealizedTest {

    @Test
    void completePublishesTheRealizedCrossings() {
        PlanHandle handle = new PlanHandle();
        long[] realized = {1L, 2L, 3L, 4L};
        handle.complete(null, false, 7, true, realized);
        assertTrue(handle.isDone());
        assertNull(handle.plan());
        assertArrayEquals(realized, handle.realizedCrossings(),
                "the failed search's realized set must be readable after isDone()");
    }

    @Test
    void completeRejectedLeavesRealizedNull() {
        PlanHandle handle = new PlanHandle();
        handle.completeRejected();
        assertTrue(handle.isDone());
        assertTrue(handle.wasRejected());
        assertNull(handle.realizedCrossings(),
                "a rejected handle never ran a search — no realized data, never a blame input");
        assertFalse(handle.wasBudgetHit());
    }
}
