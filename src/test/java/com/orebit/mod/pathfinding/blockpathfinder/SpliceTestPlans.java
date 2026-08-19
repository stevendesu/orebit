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

    /** An {@link EditSnapshot} carrying exactly the given (dry) BROKEN + PLACED cells (packed asLong). */
    public static EditSnapshot snapshotOf(long[] breaks, long[] places) {
        StepEdits se = new StepEdits();
        byte[] kinds = new byte[breaks.length];
        Arrays.fill(kinds, (byte) PathEdits.BROKEN); // dry breaks — the kind is stored verbatim now
        se.load(breaks, kinds, breaks.length, places, places.length,
                new long[0], new boolean[0], 0, ClutchModel.NONE, 0L);
        BlockPathPlan plan = new BlockPathPlan(
                Arrays.asList(new BlockPos[] { BlockPos.ZERO }),
                Arrays.asList(new Movement[] { null }),
                Arrays.asList(new StepEdits[] { se }), 0f);
        return EditSnapshot.fromRemainingSteps(plan, 0);
    }

    /** A bare no-op {@link StepEdits} — for tests that only need "this step CARRIES folded edits" as a
     *  fact (the §10 U4 edit-carrying-step rule, {@code PrefixIntegrityTest}); reaches the
     *  package-private ctor + {@code load} seam like the fixtures above. */
    public static StepEdits emptyStepEdits() {
        StepEdits se = new StepEdits();
        se.load(new long[0], new byte[0], 0, new long[0], 0,
                new long[0], new boolean[0], 0, ClutchModel.NONE, 0L);
        return se;
    }

    /** A {@link StepEdits} carrying exactly one dry BREAK at {@code cell} (packed asLong) — the §10 U4
     *  mine-cell rule's fixture ({@code PrefixIntegrityTest}). */
    public static StepEdits breakStepEdits(long cell) {
        StepEdits se = new StepEdits();
        se.load(new long[] { cell }, new byte[] { (byte) PathEdits.BROKEN }, 1, new long[0], 0,
                new long[0], new boolean[0], 0, ClutchModel.NONE, 0L);
        return se;
    }
}
