package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * <b>Correctness proof for the DURABLE cross-chunk (lateral) fluid RISKY_EDIT fold</b> (#7 step 3,
 * PERF-DESIGN-navgrid-build §C1 — {@link EdgeFluidScatter}). Steps 1/2 (the intra-chunk build SCATTER +
 * patch re-dilation) are lateral-air-optimistic: a flowing source straddling a chunk boundary leaves the
 * boundary cells' RISKY_EDIT wrong. Step 3 reconciles the 4 lateral faces at BOTH the build and the patch
 * site. This test proves both halves against an INDEPENDENT cross-gather reference.
 *
 * <p>Two adjacent chunk columns are used throughout: chunk {@code (0,0)} (world x 0..15) and chunk
 * {@code (1,0)} (world x 16..31), sharing the {@code x=15 ↔ x=0} face.
 *
 * <ol>
 *   <li><b>Build-order invariance + reference.</b> A flowing source straddling the shared face; building
 *       (chunk0 then chunk1, each reconciling against whatever is already stored) must be byte-identical to
 *       (chunk1 then chunk0), AND equal to an intra-only build with the ground-truth cross term OR-ed in by
 *       an independent GATHER (the "single-big-column" authority, where the face would be interior and the
 *       intra scatter is exact).</li>
 *   <li><b>Patch durability.</b> After a converged build: (a) an ADD of a flowing source on one chunk's face
 *       cell makes the ABUTTING neighbour edge cell gain RISKY_EDIT; (b) a non-fluid patch on the neighbour's
 *       edge cell must NOT clear the still-valid cross contribution (the drain's authoritative window clears
 *       it; the reconcile re-derives it).</li>
 * </ol>
 */
class CrossChunkFluidScatterTest {

    private static boolean bootstrapped;

    private static BlockState AIR;
    private static BlockState STONE;
    private static BlockState WATER;
    private static BlockState LAVA;
    private static BlockState GLASS;

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
        LAVA = Blocks.LAVA.defaultBlockState();
        GLASS = Blocks.GLASS.defaultBlockState();
    }

    private static final int SECTIONS = 2; // world y 0..31, minY 0

    // ---- Test 1: build-order invariance + independent cross-gather reference ----------------------

    @Test
    void buildOrderInvarianceAndReference() {
        // Order A: chunk0 built+reconciled first (no neighbour yet), then chunk1 (reconciles the pair).
        ConcurrentHashMap<Long, NavSection[]> a = new ConcurrentHashMap<>();
        NavSection[] a0 = fullBuild(scene0());
        a.put(NavStore.key(0, 0), a0);
        EdgeFluidScatter.reconcileBuild(a, 0, 0, k -> { });
        NavSection[] a1 = fullBuild(scene1());
        a.put(NavStore.key(1, 0), a1);
        EdgeFluidScatter.reconcileBuild(a, 1, 0, k -> { });

        // Order B: chunk1 first, then chunk0.
        ConcurrentHashMap<Long, NavSection[]> b = new ConcurrentHashMap<>();
        NavSection[] b1 = fullBuild(scene1());
        b.put(NavStore.key(1, 0), b1);
        EdgeFluidScatter.reconcileBuild(b, 1, 0, k -> { });
        NavSection[] b0 = fullBuild(scene0());
        b.put(NavStore.key(0, 0), b0);
        EdgeFluidScatter.reconcileBuild(b, 0, 0, k -> { });

        assertColumnsEqual("build-order invariance chunk0", a0, b0);
        assertColumnsEqual("build-order invariance chunk1", a1, b1);

        // Reference: an intra-only build with the cross term folded in by an independent GATHER.
        NavSection[] r0 = fullBuild(scene0());
        NavSection[] r1 = fullBuild(scene1());
        applyCrossReference(r0, 15, r1, 0); // chunk0 x=15 face pulls from chunk1 x=0
        applyCrossReference(r1, 0, r0, 15); // chunk1 x=0  face pulls from chunk0 x=15
        assertColumnsEqual("cross-gather reference chunk0", r0, a0);
        assertColumnsEqual("cross-gather reference chunk1", r1, a1);
    }

    /** Chunk0 states: stone floor + a face-straddling flowing source and an interior one (interior must be
     *  untouched by the cross fold). */
    private static PalettedContainer<BlockState>[] scene0() {
        PalettedContainer<BlockState>[] s = floor();
        put(s, 15, 1, 8, WATER); // face source (flowing over stone) -> scatters into chunk1 (0,·,8)
        put(s, 5, 1, 5, WATER);  // interior source (must stay a purely-intra scatter)
        return s;
    }

    /** Chunk1 states: stone floor + several face sources at assorted rows/materials. */
    private static PalettedContainer<BlockState>[] scene1() {
        PalettedContainer<BlockState>[] s = floor();
        put(s, 0, 1, 8, WATER);  // face source opposite chunk0's
        put(s, 0, 3, 10, WATER); // face source over AIR (flowing at a higher row) -> chunk0 (15,{2,1},10)
        put(s, 0, 1, 2, LAVA);   // a lava face source
        return s;
    }

    /** For each face floor cell of {@code g} (the plane {@code x==gFixed}), OR RISKY_EDIT when {@code n}'s
     *  opposite face ({@code x==nFixed}) holds a FLOWING source one or two rows above — the independent
     *  cross-chunk GATHER oracle (the counterpart of the production SCATTER). */
    private static void applyCrossReference(NavSection[] g, int gFixed, NavSection[] n, int nFixed) {
        int height = g.length << 4;
        for (int z = 0; z < 16; z++) {
            for (int colY = 0; colY < height; colY++) {
                if (refFlowing(n, nFixed, colY + 1, z) || refFlowing(n, nFixed, colY + 2, z)) {
                    orRiskyRef(g, gFixed, colY, z);
                }
            }
        }
    }

    private static boolean refFlowing(NavSection[] col, int x, int colY, int z) {
        if (colY < 0 || (colY >> 4) >= col.length) return false;
        long here = descAtRef(col, x, colY, z);
        if (NavBlock.fluid(here) == 0) return false;
        return NavBlock.fluid(descAtRef(col, x, colY - 1, z)) == 0;
    }

    private static long descAtRef(NavSection[] col, int x, int colY, int z) {
        if (colY < 0 || (colY >> 4) >= col.length) return NavBlock.descriptor(NavBlock.AIR);
        NavSection s = col[colY >> 4];
        if (s == null) return NavBlock.descriptor(NavBlock.AIR);
        return NavBlock.descriptor((short) s.getTraversalGrid().navtype(x, colY & 15, z));
    }

    private static void orRiskyRef(NavSection[] col, int x, int colY, int z) {
        if (colY < 0 || (colY >> 4) >= col.length) return;
        NavSection s = col[colY >> 4];
        if (s == null) return;
        s.getTraversalGrid().orFlags(x, colY & 15, z, NavFlags.RISKY_EDIT);
    }

    // ---- Test 2: patch durability -----------------------------------------------------------------

    @Test
    void patchAddOnFaceGivesNeighbourRisky() {
        // Converged build: neither chunk has fluid at the shared face.
        NavSection[] c0 = fullBuild(floor());
        NavSection[] c1 = fullBuild(floor());
        ConcurrentHashMap<Long, NavSection[]> chunks = pair(c0, c1);
        EdgeFluidScatter.reconcileBuild(chunks, 0, 0, k -> { });
        EdgeFluidScatter.reconcileBuild(chunks, 1, 0, k -> { });

        // The abutting neighbour edge cell starts clear.
        assertFalse(risky(c1, 0, 4, 8), "pre-edit: neighbour edge cell must have no cross RISKY");

        // ADD a flowing water source on chunk0's east face (over air) via the production flush path.
        flushEdit(chunks, 0, c0[0], 15, 5, 8, WATER); // chunk0, world (15,5,8), section 0

        assertTrue(risky(c1, 0, 4, 8), "ADD on the face must give the abutting neighbour edge cell RISKY");
        assertTrue(risky(c1, 0, 3, 8), "the source at colY=5 endangers colY-1 and colY-2 across the face");
    }

    @Test
    void patchOnNeighbourEdgeKeepsCrossContribution() {
        // chunk0 has a flowing water source on its east face; converged build sets chunk1's edge RISKY.
        PalettedContainer<BlockState>[] s0 = floor();
        put(s0, 15, 5, 8, WATER); // flowing over air (15,4,8)
        NavSection[] c0 = fullBuild(s0);
        NavSection[] c1 = fullBuild(floor());
        ConcurrentHashMap<Long, NavSection[]> chunks = pair(c0, c1);
        EdgeFluidScatter.reconcileBuild(chunks, 0, 0, k -> { });
        EdgeFluidScatter.reconcileBuild(chunks, 1, 0, k -> { });
        assertTrue(risky(c1, 0, 4, 8), "build must give chunk1's edge cell A's cross RISKY");

        // A NON-fluid patch on that very edge cell: the drain's authoritative window clears the cross term;
        // the reconcile must re-derive it (chunk0's source still flows), so RISKY SURVIVES.
        flushEdit(chunks, 1, c1[0], 0, 4, 8, GLASS); // chunk1, world (16,4,8) -> local x=0

        assertTrue(risky(c1, 0, 4, 8), "A's still-valid cross contribution must survive B's non-fluid edit");
    }

    /** Run one live edit (in chunk {@code chunkX}, chunkZ 0) through the exact production flush sequence:
     *  enqueue, collect touched faces, drain, reconcile lateral faces. minY 0. */
    private static void flushEdit(ConcurrentHashMap<Long, NavSection[]> chunks, int chunkX, NavSection section,
            int lx, int ly, int lz, BlockState state) {
        int worldX = (chunkX << 4) | lx;
        PendingPatches queue = new PendingPatches();
        NavGridUpdater.enqueueIfChanges(queue, section, lx, ly, lz,
                BlockPos.asLong(worldX, ly, lz), NavBlock.navtypeFor(state));
        EdgeFluidScatter.collect(queue, 0);
        NavGridUpdater.drain(queue, 0, chunks);
        EdgeFluidScatter.reconcile(chunks, 0, k -> { });
    }

    // ---- Plumbing ---------------------------------------------------------------------------------

    private static boolean risky(NavSection[] col, int x, int y, int z) {
        return NavFlags.risksEdit(col[y >> 4].getFlags(x, y & 15, z));
    }

    private static ConcurrentHashMap<Long, NavSection[]> pair(NavSection[] c0, NavSection[] c1) {
        ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        chunks.put(NavStore.key(0, 0), c0);
        chunks.put(NavStore.key(1, 0), c1);
        return chunks;
    }

    private static void assertColumnsEqual(String what, NavSection[] expected, NavSection[] actual) {
        for (int i = 0; i < expected.length; i++) {
            assertArrayEquals(expected[i].getTraversalGrid().raw(), actual[i].getTraversalGrid().raw(),
                    what + ": section " + i + " packed navtype/flag shorts diverged");
            assertArrayEquals(expected[i].getTraversalGrid().depthRaw(), actual[i].getTraversalGrid().depthRaw(),
                    what + ": section " + i + " depth nibble bytes diverged");
        }
    }

    /** A full column build — classify per section, overscan flags, column depth sweep (the intra fold). */
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

    /** A fresh column with a stone floor across the world-bottom row. */
    private static PalettedContainer<BlockState>[] floor() {
        PalettedContainer<BlockState>[] secs = newColumn(SECTIONS);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) put(secs, x, 0, z, STONE);
        }
        return secs;
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
