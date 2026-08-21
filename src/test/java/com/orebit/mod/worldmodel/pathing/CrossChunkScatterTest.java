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
 * <b>Correctness proof for the DURABLE cross-chunk (lateral) fold of BOTH scatter-owned terms</b>
 * (PERF-DESIGN-navgrid-build §C1 — {@link EdgeScatter}; DESIGN-fluid-flow-prediction.md §4). Steps
 * 1/2 (the intra-chunk build SCATTER + patch re-dilation) are lateral-air-optimistic: a source straddling a
 * chunk boundary leaves the boundary cells' HAS_FLUID_NEIGHBOR — or, since 2026-08-21,
 * {@code RISKS_GRAVITY}'s half B — wrong. Step 3 reconciles the 4 lateral faces at BOTH the build and the
 * patch site. This test proves both halves against an INDEPENDENT cross-gather reference. The fluid
 * predicate is ANY fluid ({@code NavBlock.isFluid}) since the 2026-08-17 widening — <b>water folds across
 * faces exactly as lava does</b>, and NEITHER FLUID ever lands on bit 0 (RISKS_GRAVITY is gravity only,
 * §4.1). The gravity predicate is a gravity block that is UNSUPPORTED; a SUPPORTED one contributes
 * nothing across a face.
 *
 * <p>The dilation is a plain 1-cell step in each of the 6 orthogonal directions, so the LATERAL half this
 * class owns has <b>no row offset</b>: fluid at column row {@code colY} marks the cell at the SAME
 * {@code colY} directly across the face. (The old flowing-fluid model marked {@code colY-1}/{@code colY-2};
 * those rows are gone with it, and the assertions below pin that they stay clear.)
 *
 * <p>Two adjacent chunk columns are used throughout: chunk {@code (0,0)} (world x 0..15) and chunk
 * {@code (1,0)} (world x 16..31), sharing the {@code x=15 ↔ x=0} face.
 *
 * <ol>
 *   <li><b>Build-order invariance + reference.</b> Fluid straddling the shared face; building (chunk0 then
 *       chunk1, each reconciling against whatever is already stored) must be byte-identical to (chunk1 then
 *       chunk0), AND equal to an intra-only build with the ground-truth cross term OR-ed in by an
 *       independent GATHER (the "single-big-column" authority, where the face would be interior and the
 *       intra scatter is exact).</li>
 *   <li><b>Patch durability.</b> After a converged build: (a) an ADD of lava OR water on one chunk's face
 *       cell makes the ABUTTING neighbour edge cell gain HAS_FLUID_NEIGHBOR (and never bit 0);
 *       (b) a non-fluid patch on the neighbour's edge cell must NOT clear the still-valid cross
 *       contribution (the drain's authoritative window clears it; the reconcile re-derives it).</li>
 *   <li><b>The gravity term's TWO-CELL neighbour footprint.</b> An edit on a face is read by the neighbour
 *       column at {@code colY} (as a lateral {@code N}) <i>and</i> at {@code colY+1} (as that cell's
 *       lateral {@code N}'s SUPPORT). Removing the support under a face-adjacent gravel exercises the
 *       second one alone. This is the one structural difference between the two terms' cross folds — for
 *       fluid alone, only {@code colY} ever mattered.</li>
 * </ol>
 */
class CrossChunkScatterTest {

    private static boolean bootstrapped;

    private static BlockState AIR;
    private static BlockState STONE;
    private static BlockState WATER;
    private static BlockState LAVA;
    private static BlockState GLASS;
    private static BlockState SAND;
    private static BlockState GRAVEL;

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
        SAND = Blocks.SAND.defaultBlockState();
        GRAVEL = Blocks.GRAVEL.defaultBlockState();
    }

    private static final int SECTIONS = 2; // world y 0..31, minY 0

    // ---- Test 1: build-order invariance + independent cross-gather reference ----------------------

    @Test
    void buildOrderInvarianceAndReference() {
        // Order A: chunk0 built+reconciled first (no neighbour yet), then chunk1 (reconciles the pair).
        ConcurrentHashMap<Long, NavSection[]> a = new ConcurrentHashMap<>();
        NavSection[] a0 = fullBuild(scene0());
        a.put(NavStore.key(0, 0), a0);
        EdgeScatter.reconcileBuild(a, 0, 0, k -> { });
        NavSection[] a1 = fullBuild(scene1());
        a.put(NavStore.key(1, 0), a1);
        EdgeScatter.reconcileBuild(a, 1, 0, k -> { });

        // Order B: chunk1 first, then chunk0.
        ConcurrentHashMap<Long, NavSection[]> b = new ConcurrentHashMap<>();
        NavSection[] b1 = fullBuild(scene1());
        b.put(NavStore.key(1, 0), b1);
        EdgeScatter.reconcileBuild(b, 1, 0, k -> { });
        NavSection[] b0 = fullBuild(scene0());
        b.put(NavStore.key(0, 0), b0);
        EdgeScatter.reconcileBuild(b, 0, 0, k -> { });

        assertColumnsEqual("build-order invariance chunk0", a0, b0);
        assertColumnsEqual("build-order invariance chunk1", a1, b1);

        // Reference: an intra-only build with the cross term folded in by an independent GATHER.
        NavSection[] r0 = fullBuild(scene0());
        NavSection[] r1 = fullBuild(scene1());
        applyCrossReference(r0, 15, r1, 0); // chunk0 x=15 face pulls from chunk1 x=0
        applyCrossReference(r1, 0, r0, 15); // chunk1 x=0  face pulls from chunk0 x=15
        assertColumnsEqual("cross-gather reference chunk0", r0, a0);
        assertColumnsEqual("cross-gather reference chunk1", r1, a1);

        // ...and the named bits the fold exists for. Water folds exactly as lava does (any-fluid), and
        // NEITHER FLUID lands on bit 0 — RISKS_GRAVITY is gravity only. (The scene DOES carry gravity
        // shapes now, asserted separately below; these two cells are fluid-only.)
        assertTrue(fluidNeighbor(a1, 0, 1, 8), "chunk0's face lava must mark chunk1's abutting cell at the SAME row");
        assertFalse(fluidNeighbor(a1, 0, 0, 8), "no row-below contribution (the old colY-1 row is gone)");
        assertTrue(fluidNeighbor(a0, 15, 5, 2), "chunk1's face lava must mark chunk0's abutting cell");
        assertTrue(fluidNeighbor(a1, 0, 7, 3), "chunk0's face WATER must fold across the boundary too (any-fluid)");
        assertTrue(fluidNeighbor(a0, 15, 9, 11), "chunk1's face WATER must fold across the boundary too");
        assertFalse(risky(a1, 0, 1, 8), "cross-face lava never sets bit 0 (RISKS_GRAVITY is gravity only)");
        assertFalse(risky(a1, 0, 7, 3), "cross-face water never sets bit 0");

        // The GRAVITY half B folds across the face on exactly the same geometry — and only for an
        // UNSUPPORTED source.
        assertTrue(risky(a1, 0, 11, 6), "chunk0's face SUSPENDED gravel must mark chunk1's abutting cell");
        assertTrue(risky(a0, 15, 14, 9), "chunk1's face suspended sand must mark chunk0's abutting cell");
        assertFalse(risky(a1, 0, 13, 13), "a SUPPORTED column contributes NOTHING across the face");
        assertFalse(risky(a1, 0, 10, 6), "no row-below contribution for gravity either");
        assertFalse(risky(a1, 1, 11, 6), "and it does not reach two cells in");
    }

    /** Chunk0 states: stone floor + face lava, an interior lava (must stay a purely-intra scatter) and a
     *  face WATER cell (folds across the boundary like any fluid). */
    private static PalettedContainer<BlockState>[] scene0() {
        PalettedContainer<BlockState>[] s = floor();
        put(s, 15, 1, 8, LAVA);  // east-face lava -> marks chunk1 (0,1,8)
        put(s, 5, 1, 5, LAVA);   // interior lava (purely intra)
        put(s, 15, 7, 3, WATER); // east-face water -> marks chunk1 (0,7,3) — any-fluid
        // GRAVITY, half B: a SUSPENDED gravel column on the east face -> marks chunk1 (0,11,6).
        put(s, 15, 11, 6, GRAVEL);
        // ...and a SUPPORTED sand on the same face, which must contribute NOTHING across the boundary.
        put(s, 15, 12, 13, STONE);
        put(s, 15, 13, 13, SAND);
        return s;
    }

    /** Chunk1 states: stone floor + a face lava at a different row and a face water. */
    private static PalettedContainer<BlockState>[] scene1() {
        PalettedContainer<BlockState>[] s = floor();
        put(s, 0, 5, 2, LAVA);    // west-face lava -> marks chunk0 (15,5,2)
        put(s, 0, 9, 11, WATER);  // west-face water -> marks chunk0 (15,9,11) — any-fluid
        put(s, 0, 14, 9, SAND);   // suspended sand on the west face -> marks chunk0 (15,14,9)
        return s;
    }

    /** For each face cell of {@code g} (the plane {@code x==gFixed}), OR the terms {@code n}'s opposite face
     *  ({@code x==nFixed}) contributes at the SAME column row — the independent cross-chunk GATHER oracle
     *  (the counterpart of the production SCATTER), covering BOTH scatter-owned bits. */
    private static void applyCrossReference(NavSection[] g, int gFixed, NavSection[] n, int nFixed) {
        int height = g.length << 4;
        for (int z = 0; z < 16; z++) {
            for (int colY = 0; colY < height; colY++) {
                if (refFluid(n, nFixed, colY, z)) orRef(g, gFixed, colY, z, NavFlags.HAS_FLUID_NEIGHBOR);
                if (refUnsupportedGravity(n, nFixed, colY, z)) {
                    orRef(g, gFixed, colY, z, NavFlags.RISKS_GRAVITY);
                }
            }
        }
    }

    private static boolean refFluid(NavSection[] col, int x, int colY, int z) {
        return NavBlock.isFluid(descAtRef(col, x, colY, z));
    }

    /** The gravity half-B source predicate, spelled out independently of the production code. */
    private static boolean refUnsupportedGravity(NavSection[] col, int x, int colY, int z) {
        return NavBlock.hasGravity(descAtRef(col, x, colY, z))
                && NavBlock.isPassable(descAtRef(col, x, colY - 1, z));
    }

    private static long descAtRef(NavSection[] col, int x, int colY, int z) {
        if (colY < 0 || (colY >> 4) >= col.length) return NavBlock.descriptor(NavBlock.AIR);
        NavSection s = col[colY >> 4];
        if (s == null) return NavBlock.descriptor(NavBlock.AIR);
        return NavBlock.descriptor((short) s.getTraversalGrid().navtype(x, colY & 15, z));
    }

    private static void orRef(NavSection[] col, int x, int colY, int z, int bits) {
        if (colY < 0 || (colY >> 4) >= col.length) return;
        NavSection s = col[colY >> 4];
        if (s == null) return;
        s.getTraversalGrid().orFlags(x, colY & 15, z, bits);
    }

    // ---- Test 2: patch durability -----------------------------------------------------------------

    @Test
    void patchAddOnFaceGivesNeighbourFluidBit() {
        // Converged build: neither chunk has fluid at the shared face.
        NavSection[] c0 = fullBuild(floor());
        NavSection[] c1 = fullBuild(floor());
        ConcurrentHashMap<Long, NavSection[]> chunks = pair(c0, c1);
        EdgeScatter.reconcileBuild(chunks, 0, 0, k -> { });
        EdgeScatter.reconcileBuild(chunks, 1, 0, k -> { });

        // The abutting neighbour edge cell starts clear.
        assertFalse(fluidNeighbor(c1, 0, 5, 8), "pre-edit: neighbour edge cell must have no cross fluid bit");

        // ADD lava on chunk0's east face via the production flush path.
        flushEdit(chunks, 0, c0[0], 15, 5, 8, LAVA); // chunk0, world (15,5,8), section 0

        assertTrue(fluidNeighbor(c1, 0, 5, 8), "ADD on the face must give the abutting neighbour edge cell the fluid bit");
        assertFalse(fluidNeighbor(c1, 0, 4, 8), "the dilation is a 1-cell lateral step: no colY-1 contribution");
        assertFalse(fluidNeighbor(c1, 0, 6, 8), "no colY+1 contribution either");
        assertFalse(fluidNeighbor(c1, 1, 5, 8), "and it does not reach two cells in");
        assertFalse(risky(c1, 0, 5, 8), "cross-face lava never sets bit 0 (gravity only)");

        // ADD water on the face: folds identically (any-fluid) — and still never touches bit 0.
        flushEdit(chunks, 0, c0[0], 15, 9, 3, WATER);
        assertTrue(fluidNeighbor(c1, 0, 9, 3), "water on the face must fold across the boundary (any-fluid)");
        assertFalse(risky(c1, 0, 9, 3), "cross-face water never sets bit 0");
    }

    /**
     * <b>The gravity cross fold's TWO-CELL neighbour footprint</b> (owner ruling 2026-08-21). Half B at a
     * cell {@code C} is {@code ∃N ∈ SIX(C): hasGravity(N) && isPassable(below(N))}, so an edit {@code E} on
     * a chunk face is read by exactly two cells of the neighbour column:
     * <ul>
     *   <li>{@code C = (across, colY(E))} — {@code E} is <i>itself</i> {@code C}'s lateral {@code N};</li>
     *   <li>{@code C = (across, colY(E) + 1)} — {@code E} is {@code below(N)} for {@code C}'s lateral
     *       {@code N}, the cell one row above {@code E}. <b>This is the cell a one-cell
     *       {@code neighbourRederive} misses</b>, and the fluid term never needed it.</li>
     * </ul>
     * The scene makes the {@code +1} cell the only one that changes: chunk0 holds a SUPPORTED gravel at
     * face row 7 over a support at row 6. Breaking the support at row 6 flips the gravel (row 7) to
     * unsupported, so chunk1's cell at row <b>7</b> — {@code colY(E)+1} — must gain half B, while row 6
     * (which now sees plain air across the face) must not.
     */
    @Test
    void supportRemovalOnFaceMarksTheColYPlusOneNeighbourCell() {
        PalettedContainer<BlockState>[] s0 = floor();
        put(s0, 15, 6, 4, STONE);   // the support
        put(s0, 15, 7, 4, GRAVEL);  // supported => contributes nothing across the face yet
        NavSection[] c0 = fullBuild(s0);
        NavSection[] c1 = fullBuild(floor());
        ConcurrentHashMap<Long, NavSection[]> chunks = pair(c0, c1);
        EdgeScatter.reconcileBuild(chunks, 0, 0, k -> { });
        EdgeScatter.reconcileBuild(chunks, 1, 0, k -> { });
        assertFalse(risky(c1, 0, 7, 4), "pre-edit: a SUPPORTED face column marks nothing across the face");
        assertFalse(risky(c1, 0, 6, 4), "pre-edit: nor the cell beside its support");

        // Break the support (chunk0, world (15,6,4)) through the production flush sequence.
        flushEdit(chunks, 0, c0[0], 15, 6, 4, AIR);

        assertTrue(risky(c1, 0, 7, 4),
                "the now-unsupported gravel must mark the neighbour cell at colY(edit)+1 — the cell a "
                        + "one-cell neighbourRederive would miss");
        assertFalse(risky(c1, 0, 6, 4),
                "the cell at colY(edit) itself sees only air across the face now");
        assertFalse(risky(c1, 0, 8, 4), "and nothing two rows up");
    }

    @Test
    void patchOnNeighbourEdgeKeepsCrossContribution() {
        // chunk0 has face lava; the converged build sets chunk1's abutting edge cell's fluid bit.
        PalettedContainer<BlockState>[] s0 = floor();
        put(s0, 15, 5, 8, LAVA);
        NavSection[] c0 = fullBuild(s0);
        NavSection[] c1 = fullBuild(floor());
        ConcurrentHashMap<Long, NavSection[]> chunks = pair(c0, c1);
        EdgeScatter.reconcileBuild(chunks, 0, 0, k -> { });
        EdgeScatter.reconcileBuild(chunks, 1, 0, k -> { });
        assertTrue(fluidNeighbor(c1, 0, 5, 8), "build must give chunk1's edge cell chunk0's cross fluid bit");

        // A NON-fluid patch on that very edge cell: the drain's authoritative window clears the cross term;
        // the reconcile must re-derive it (chunk0's lava is still there), so the fluid bit SURVIVES.
        flushEdit(chunks, 1, c1[0], 0, 5, 8, GLASS); // chunk1, world (16,5,8) -> local x=0

        assertTrue(fluidNeighbor(c1, 0, 5, 8), "chunk0's still-valid cross contribution must survive chunk1's edit");
    }

    /** Run one live edit (in chunk {@code chunkX}, chunkZ 0) through the exact production flush sequence:
     *  enqueue, collect touched faces, drain, reconcile lateral faces. minY 0. */
    private static void flushEdit(ConcurrentHashMap<Long, NavSection[]> chunks, int chunkX, NavSection section,
            int lx, int ly, int lz, BlockState state) {
        int worldX = (chunkX << 4) | lx;
        PendingPatches queue = new PendingPatches();
        NavGridUpdater.enqueueIfChanges(queue, section, lx, ly, lz,
                BlockPos.asLong(worldX, ly, lz), NavBlock.navtypeFor(state));
        EdgeScatter.collect(queue, 0);
        NavGridUpdater.drain(queue, 0, chunks);
        EdgeScatter.reconcile(chunks, 0, k -> { });
    }

    // ---- Plumbing ---------------------------------------------------------------------------------

    private static boolean risky(NavSection[] col, int x, int y, int z) {
        return NavFlags.risksGravity(col[y >> 4].getFlags(x, y & 15, z));
    }

    private static boolean fluidNeighbor(NavSection[] col, int x, int y, int z) {
        return NavFlags.hasFluidNeighbor(col[y >> 4].getFlags(x, y & 15, z));
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
