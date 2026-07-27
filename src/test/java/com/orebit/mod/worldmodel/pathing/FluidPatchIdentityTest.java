package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.orebit.mod.worldmodel.navblock.NavBlock;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

/**
 * <b>Correctness proof for the flowing-fluid RISKY_EDIT re-dilation on the LIVE-EDIT (patch) path</b>
 * (PERF-DESIGN-navgrid-build §C1, step 2 of the fluid-scatter arc). The build path moved the flowing-fluid
 * RISKY_EDIT term out of {@link NavFlags#compute} into {@link NavSectionBuilder#computeDepth}'s SCATTER;
 * that left {@link NavSectionBuilder#patchCell}/{@link NavSectionBuilder#patchCells} — which recompute
 * flags via {@code recomputeWindow → NavFlags.compute} — no longer setting the fluid term after a live
 * block change. The patch path now re-derives it authoritatively over the recompute window (the retained
 * {@link NavFlags#risksFluidFlow} reference gather).
 *
 * <p>This proves the fix the way {@code FluidScatterIdentityTest} proves the build fold: a
 * live-edited grid must be <b>byte-for-byte identical to a full from-scratch rebuild of the post-edit
 * block states</b>. The full rebuild is the oracle (its RISKY_EDIT comes from the build scatter, itself
 * proven bit-identical to the historical gather); the patch path must land on exactly those bits.
 *
 * <p>Two independent subjects are compared to the same oracle:
 * <ul>
 *   <li><b>Sequential {@code patchCell}</b> — one edit at a time (the pre-batching inline hook shape).</li>
 *   <li><b>{@code enqueueIfChanges} + {@code drain}</b> — the production deferred-batch path (grouped by
 *       section, below-seam-ordered), which fans out to {@link NavSectionBuilder#patchCells}.</li>
 * </ul>
 *
 * <p>The edit set deliberately exercises every fluid-RISKY transition the re-dilation must handle:
 * <ol>
 *   <li><b>ADD</b> a flowing source next to floor cells (RISKY must be SET where it was clear);</li>
 *   <li><b>REMOVE</b> a flowing source so its neighbours LOSE their only flowing neighbour (RISKY must be
 *       CLEARED — the authoritative-not-additive case);</li>
 *   <li><b>TOGGLE-BELOW</b> — change the block below a fluid so the fluid flips flowing↔non-flowing,
 *       moving the endangered rows;</li>
 *   <li><b>GRAVITY OVERLAP</b> — remove a flowing source from a cell that ALSO has a gravity block above
 *       it: RISKY must STAY (gravity still sets it — proves the fluid clear doesn't stomp the gravity
 *       term);</li>
 *   <li><b>SEAM</b> edits on both sides of the s0/s1 face (a cross-seam-down scatter and a within-seam
 *       flowing flip) — the below-seam recompute path.</li>
 * </ol>
 */
class FluidPatchIdentityTest {

    private static boolean bootstrapped;

    private static BlockState AIR;
    private static BlockState STONE;
    private static BlockState WATER;
    private static BlockState SAND;

    @BeforeAll
    static void boot() {
        if (!bootstrapped) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            bootstrapped = true;
        }
        AIR = Blocks.AIR.defaultBlockState();
        STONE = Blocks.STONE.defaultBlockState();
        WATER = Blocks.WATER.defaultBlockState();
        SAND = Blocks.SAND.defaultBlockState();
    }

    private static final int SECTIONS = 4; // world y 0..63, minY 0

    /** One live block change: world coords + the new block state. */
    private record Edit(int x, int wy, int z, BlockState state) { }

    /**
     * The edited neighbourhood exercising every fluid-RISKY transition (see the class doc). All cells sit
     * in chunk (0,0), x/z ≤ 14, so lateral faces stay air-optimistic identically in patch and rebuild.
     */
    private static List<Edit> edits() {
        List<Edit> e = new ArrayList<>();
        e.add(new Edit(10, 1, 10, WATER)); // ADD a flowing source over the stone floor
        e.add(new Edit(8, 1, 8, AIR));     // REMOVE a flowing source (neighbours lose their RISKY)
        e.add(new Edit(2, 4, 2, WATER));   // TOGGLE-BELOW: y5 water goes non-flowing, y4 becomes flowing
        e.add(new Edit(5, 11, 4, AIR));    // GRAVITY OVERLAP: remove the fluid; sand above keeps RISKY set
        e.add(new Edit(7, 15, 7, WATER));  // SEAM (s0 top): y16 goes non-flowing, y15 becomes flowing
        e.add(new Edit(9, 16, 9, WATER));  // SEAM (s1 bottom): new flowing source scatters DOWN into s0
        return e;
    }

    // ---- Fixture: the pre-edit block states -------------------------------------------------------

    /** The source-of-truth block states (mutated to build the oracle; classified fresh for each subject). */
    private static PalettedContainer<BlockState>[] initialStates() {
        PalettedContainer<BlockState>[] secs = newColumn(SECTIONS);

        // Stone floor across the world-bottom row.
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) put(secs, x, 0, z, STONE);
        }
        // REMOVE case: a flowing water source over the floor (its neighbours carry RISKY initially).
        put(secs, 8, 1, 8, WATER);
        // TOGGLE-BELOW case: water at y5 over a stone plug at y4 — flowing at y5.
        put(secs, 2, 4, 2, STONE);
        put(secs, 2, 5, 2, WATER);
        // GRAVITY-OVERLAP case: sand (gravity) at y12 above the cell (4,10,4); a flowing water neighbour at
        // (5,11,4) over air ⇒ (4,10,4) gets BOTH the gravity and the fluid RISKY term initially.
        put(secs, 4, 12, 4, SAND);
        put(secs, 5, 11, 4, WATER);
        // SEAM case: a flowing water source at the s1 bottom row (y16) over air (y15) — endangers s0's top
        // rows across the seam.
        put(secs, 7, 16, 7, WATER);
        return secs;
    }

    // ---- The oracle + subjects --------------------------------------------------------------------

    @Test
    void patchPathIsByteIdenticalToRebuild() {
        List<Edit> edits = edits();

        // ORACLE — apply every edit to the block states, then FULL rebuild of the post-edit column.
        PalettedContainer<BlockState>[] oracleStates = initialStates();
        for (Edit ed : edits) put(oracleStates, ed.x(), ed.wy(), ed.z(), ed.state());
        NavSection[] oracle = fullBuild(oracleStates);

        // SUBJECT A — sequential patchCell on a live-maintained column built from the PRE-edit states.
        NavSection[] a = fullBuild(initialStates());
        for (Edit ed : edits) {
            int si = ed.wy() >> 4;
            NavSection above = si + 1 < SECTIONS ? a[si + 1] : null;
            NavSection below = si > 0 ? a[si - 1] : null;
            NavSectionBuilder.patchCell(a[si], above, below, ed.x(), ed.wy() & 15, ed.z(), ed.state());
        }
        assertColumnEquals("sequential patchCell", oracle, a);

        // SUBJECT B — the production enqueue + drain (batch) path on a fresh pre-edit column.
        NavSection[] b = fullBuild(initialStates());
        ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        chunks.put(NavStore.key(0, 0), b);
        PendingPatches queue = new PendingPatches();
        for (Edit ed : edits) {
            NavSection section = b[ed.wy() >> 4];
            NavGridUpdater.enqueueIfChanges(queue, section, ed.x(), ed.wy() & 15, ed.z(),
                    BlockPos.asLong(ed.x(), ed.wy(), ed.z()), NavBlock.navtypeFor(ed.state()));
        }
        NavGridUpdater.drain(queue, 0, chunks);
        assertColumnEquals("enqueue+drain batch", oracle, b);
    }

    private static void assertColumnEquals(String what, NavSection[] expected, NavSection[] actual) {
        for (int i = 0; i < expected.length; i++) {
            assertArrayEquals(expected[i].getTraversalGrid().raw(), actual[i].getTraversalGrid().raw(),
                    what + ": section " + i + " packed navtype/flag shorts diverged from the full rebuild "
                            + "(fluid RISKY_EDIT re-dilation wrong)");
            assertArrayEquals(expected[i].getTraversalGrid().depthRaw(), actual[i].getTraversalGrid().depthRaw(),
                    what + ": section " + i + " depth nibble bytes diverged from the full rebuild");
        }
    }

    // ---- Plumbing ---------------------------------------------------------------------------------

    /** A full column build — the live pipeline: classify per section, overscan flags, column depth sweep. */
    private static NavSection[] fullBuild(PalettedContainer<BlockState>[] secs) {
        NavSection[] sections = new NavSection[secs.length];
        boolean[] allAir = new boolean[secs.length];
        for (int i = 0; i < secs.length; i++) {
            sections[i] = NavSection.create(BlockPos.ZERO);
            allAir[i] = NavSectionBuilder.classifyNavtypes(secs[i], false, sections[i].getTraversalGrid(), null);
        }
        for (int i = 0; i < secs.length; i++) {
            NavSection above = i + 1 < secs.length ? sections[i + 1] : null;
            NavSectionBuilder.computeFlags(sections[i].getTraversalGrid(), allAir[i],
                    (above == null || allAir[i + 1]) ? null : above.getTraversalGrid());
        }
        NavSectionBuilder.computeDepth(sections);
        return sections;
    }

    @SuppressWarnings("unchecked")
    private static PalettedContainer<BlockState>[] newColumn(int n) {
        PalettedContainer<BlockState>[] secs = new PalettedContainer[n];
        for (int i = 0; i < n; i++) {
            secs[i] = new PalettedContainer<>(AIR, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        }
        return secs;
    }

    /** Place a block at WORLD y in the column (section {@code wy>>4}, local row {@code wy&15}). */
    private static void put(PalettedContainer<BlockState>[] secs, int x, int wy, int z, BlockState st) {
        secs[wy >> 4].set(x, wy & 15, z, st);
    }
}
