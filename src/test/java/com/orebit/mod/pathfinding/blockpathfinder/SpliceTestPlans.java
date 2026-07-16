package com.orebit.mod.pathfinding.blockpathfinder;

import java.util.Arrays;

import net.minecraft.core.BlockPos;

/**
 * Test-only builder for {@link EditSnapshot}s, going through the PUBLIC folding API
 * ({@link EditSnapshot#fromRemainingSteps}) so tests exercise the real path rather than a synthetic
 * back door. Lives in the production package to reach {@link StepEdits}'s package-private ctor +
 * {@code load} seam — the same arrangement as {@link EditFixtures}. Used by the splice integration
 * test in {@code worldmodel.pathing} (which lives THERE for {@code NavGridView}'s synthetic ctor).
 */
public final class SpliceTestPlans {

    private SpliceTestPlans() {}

    /** An {@link EditSnapshot} carrying exactly the given BROKEN + PLACED cells (packed asLong). */
    public static EditSnapshot snapshotOf(long[] breaks, long[] places) {
        StepEdits se = new StepEdits();
        se.load(breaks, breaks.length, places, places.length, new long[0], new boolean[0], 0);
        BlockPathPlan plan = new BlockPathPlan(
                Arrays.asList(new BlockPos[] { BlockPos.ZERO }),
                Arrays.asList(new Movement[] { null }),
                Arrays.asList(new StepEdits[] { se }), 0f);
        return EditSnapshot.fromRemainingSteps(plan, 0);
    }
}
