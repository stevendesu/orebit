package profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.orebit.mod.worldmodel.navblock.NavBlock;
import com.orebit.mod.worldmodel.pathing.NavFlags;
import com.orebit.mod.worldmodel.pathing.NavSection;
import com.orebit.mod.worldmodel.pathing.NavSectionBuilder;
import com.orebit.mod.worldmodel.pathing.TraversalGrid;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

/**
 * Exercises {@link NavFlags} over synthetic descriptor grids (no world). Builds a 16³ {@code long[]}
 * scratch in the canonical {@code (y<<8)|(z<<4)|x} order, places known blocks around an interior floor
 * cell, and asserts the resulting neighbour-property bitmask (headroom level, edit-hazard, walk-through
 * hazard). Replaces the old {@code NavClassifierTest} (the 4-value class is gone).
 *
 * <p>Also pins the OWNERSHIP split of the two per-bit facts {@link NavFlags#compute} deliberately does not
 * gather (DESIGN-fluid-flow-prediction.md §4/§4.1): {@code HAS_FLUID_NEIGHBOR} (bit 4) is SCATTER-owned —
 * {@code compute()} never writes it, for water or lava — and {@code RISKY_EDIT} (bit 0) is STRICTLY
 * gravity — adjacent fluid sets it nowhere, not in {@code compute()} and not through the full build
 * pipeline either (the built-fixture test below).
 */
public class NavFlagsTest {

    private static long AIR;

    private static long[] freshGrid() {
        long[] g = new long[4096];
        Arrays.fill(g, AIR);
        return g;
    }

    private static void put(long[] g, int x, int y, int z, BlockState state) {
        g[(y << 8) | (z << 4) | x] = NavBlock.descriptorFor(state);
    }

    private static int flags(long[] g) {
        return NavFlags.compute(g, 8, 8, 8); // interior floor cell, away from edges
    }

    private static BlockState state(net.minecraft.world.level.block.Block b) {
        return b.defaultBlockState();
    }

    @Test
    void computesNeighbourFacts() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        AIR = NavBlock.descriptor(NavBlock.AIR);

        // --- HEADROOM levels (clearance above the floor) ----------------------------------------
        // Stone floor, all air above -> JUMP (3 clear cells), no other flags.
        long[] g = freshGrid();
        put(g, 8, 8, 8, state(Blocks.STONE));
        int f = flags(g);
        assertEquals(NavFlags.HEADROOM_JUMP, NavFlags.headroom(f), "stone floor, open above");
        assertFalse(NavFlags.risksEdit(f), "no edit hazard over open stone");
        assertFalse(NavFlags.clearableHazard(f), "no walk-through hazard");

        // Solid block directly above the floor -> NONE (can't even crawl).
        g = freshGrid();
        put(g, 8, 8, 8, state(Blocks.STONE));
        put(g, 8, 9, 8, state(Blocks.STONE));
        assertEquals(NavFlags.HEADROOM_NONE, NavFlags.headroom(flags(g)), "blocked at feet");

        // Clear at feet, solid at head -> CRAWL (1-tall gap).
        g = freshGrid();
        put(g, 8, 8, 8, state(Blocks.STONE));
        put(g, 8, 10, 8, state(Blocks.STONE));
        assertEquals(NavFlags.HEADROOM_CRAWL, NavFlags.headroom(flags(g)), "1-tall gap");

        // Clear feet + head, solid one higher -> WALK (2-tall, no room to jump).
        g = freshGrid();
        put(g, 8, 8, 8, state(Blocks.STONE));
        put(g, 8, 11, 8, state(Blocks.STONE));
        assertEquals(NavFlags.HEADROOM_WALK, NavFlags.headroom(flags(g)), "2-tall gap");

        // --- RISKY_EDIT (don't break/place here) -------------------------------------------------
        // A gravity block overhead -> editing the body space could drop it.
        g = freshGrid();
        put(g, 8, 8, 8, state(Blocks.STONE));
        put(g, 8, 10, 8, state(Blocks.SAND));
        assertTrue(NavFlags.risksEdit(flags(g)), "sand overhead = risky edit");

        // RISKY_EDIT is STRICTLY gravity (DESIGN-fluid-flow-prediction.md §4.1) and HAS_FLUID_NEIGHBOR
        // (bit 4) is SCATTER-owned (§4) — computed by NavSectionBuilder.computeDepth's scatter during the
        // section build, never here (the upward-only scratch cannot see below/lateral, and a compute-side
        // write would break the scatter's write-into-above safety argument). So compute() over a bare grid
        // writes NEITHER bit for an adjacent fluid: lava orthogonally beside the computed cell leaves both
        // bit 0 (the old keep-away term migrated to pricing, §4.2) and bit 4 CLEAR. The scatter itself is
        // proven at the build/patch level by FluidScatterIdentityTest / FluidPatchIdentityTest /
        // CrossChunkFluidScatterTest; the built-fixture test below pins the split through the full pipeline.
        g = freshGrid();
        put(g, 8, 8, 8, state(Blocks.STONE));
        put(g, 7, 8, 8, state(Blocks.LAVA)); // an orthogonal neighbour of the computed cell
        f = flags(g);
        assertFalse(NavFlags.risksEdit(f), "adjacent lava never sets RISKY_EDIT — bit 0 is strictly gravity");
        assertFalse(NavFlags.hasFluidNeighbor(f), "compute() never writes HAS_FLUID_NEIGHBOR — scatter-owned");

        // ...and WATER likewise: no bit 0 (water has never been a RISKY_EDIT term) and no compute-side
        // bit 4. Asserted here as well as at the build level so a re-introduction of a fluid gather in
        // compute() is caught immediately.
        g = freshGrid();
        put(g, 8, 8, 8, state(Blocks.STONE));
        put(g, 7, 8, 8, state(Blocks.WATER));
        put(g, 8, 9, 8, state(Blocks.WATER));
        f = flags(g);
        assertFalse(NavFlags.risksEdit(f), "water is never a RISKY_EDIT hazard");
        assertFalse(NavFlags.hasFluidNeighbor(f), "compute() never writes HAS_FLUID_NEIGHBOR for water either");

        // --- CLEARABLE_HAZARD (walk-through, cost not block) -------------------------------------
        // Fire in the body space over a solid floor.
        g = freshGrid();
        put(g, 8, 8, 8, state(Blocks.STONE));
        put(g, 8, 9, 8, state(Blocks.FIRE));
        f = flags(g);
        assertTrue(NavFlags.clearableHazard(f), "fire in headroom = clearable hazard");
        assertEquals(NavFlags.HEADROOM_JUMP, NavFlags.headroom(f), "fire is passable, so headroom stays");
    }

    /**
     * The built-pipeline pins for the ownership split (DESIGN-fluid-flow-prediction.md §4/§4.1): a full
     * section build (classify → {@code computeFlags} → {@code computeDepth}, the scatter's home) over a
     * lone fluid cell must
     * <ul>
     *   <li>set {@code HAS_FLUID_NEIGHBOR} on the fluid's orthogonal neighbours — water and lava alike
     *       (the any-fluid scatter), centre excluded; and</li>
     *   <li>leave {@code RISKY_EDIT} clear EVERYWHERE — adjacent lava no longer sets bit 0 <b>at all</b>
     *       (this strengthens the compute-level pin above from "not via compute" to "not anywhere": the
     *       lava keep-away term is deleted, not relocated — a predicted lava flood is PRICED via the
     *       funnel's BROKEN_LAVA verdict, §4.2).</li>
     * </ul>
     * The 6-dilation's exact geometry, seam crossings and patch-path identity are
     * {@code FluidScatterIdentityTest}/{@code FluidPatchIdentityTest}'s; this pins the bit OWNERSHIP at the
     * {@link NavFlags} API level.
     */
    @Test
    void builtGridScattersFluidNeighborAndKeepsRiskyEditGravityOnly() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        AIR = NavBlock.descriptor(NavBlock.AIR);

        for (BlockState fluid : new BlockState[] { state(Blocks.LAVA), state(Blocks.WATER) }) {
            String name = fluid.getBlock().toString();
            TraversalGrid grid = builtLoneCellColumn(fluid)[0].getTraversalGrid();

            // The scatter marks the orthogonal neighbours (spot-checks; full geometry in the identity test)...
            assertTrue(NavFlags.hasFluidNeighbor(grid.flags(7, 8, 8)), name + ": lateral neighbour carries bit 4");
            assertTrue(NavFlags.hasFluidNeighbor(grid.flags(8, 9, 8)), name + ": above neighbour carries bit 4");
            assertTrue(NavFlags.hasFluidNeighbor(grid.flags(8, 7, 8)), name + ": below neighbour carries bit 4");
            // ...never the fluid cell itself (centre excluded).
            assertFalse(NavFlags.hasFluidNeighbor(grid.flags(8, 8, 8)), name + ": centre excluded");

            // RISKY_EDIT clear everywhere: nothing in the scene has gravity, and fluid adjacency is no
            // longer a bit-0 term anywhere in the pipeline.
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        assertFalse(NavFlags.risksEdit(grid.flags(x, y, z)),
                                name + ": RISKY_EDIT is strictly gravity — (" + x + "," + y + "," + z + ")");
                    }
                }
            }
        }
    }

    /** One classified+flagged+depth-swept section (the scatter's home) with {@code state} at (8,8,8). */
    private static NavSection[] builtLoneCellColumn(BlockState state) {
        PalettedContainer<BlockState> states = new PalettedContainer<>(
                Blocks.AIR.defaultBlockState(), Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        states.set(8, 8, 8, state);
        NavSection section = NavSection.create(BlockPos.ZERO);
        boolean allAir = NavSectionBuilder.classifyNavtypes(states, false, section.getTraversalGrid(), null);
        NavSectionBuilder.computeFlags(section.getTraversalGrid(), allAir, null);
        NavSection[] col = { section };
        NavSectionBuilder.computeDepth(col);
        return col;
    }

    /**
     * The vertical-overscan scratch contract: a {@link NavFlags#SCRATCH_SIZE}-length scratch carries
     * {@link NavFlags#OVERSCAN_ROWS} rows of the section ABOVE at {@code y = 16..18} (the same
     * {@code (y<<8)|(z<<4)|x} formula), so TOP-row floor cells see real blocks across the seam; a bare
     * 4096 scratch keeps the legacy air-optimistic behaviour.
     */
    @Test
    void overscanRowsInformTopRowFlags() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        AIR = NavBlock.descriptor(NavBlock.AIR);

        // A berry bush just across the seam (the above section's ly=0) is the body cell of the top-row
        // floor -> hazard + slow bits set, headroom kept (the bush is passable).
        long[] g = freshOverscanGrid();
        put(g, 8, 15, 8, state(Blocks.STONE));
        put(g, 8, 16, 8, state(Blocks.SWEET_BERRY_BUSH));
        int f = NavFlags.compute(g, 8, 15, 8);
        assertTrue(NavFlags.clearableHazard(f), "berry bush across the seam = clearable hazard");
        assertTrue(NavFlags.slowTransit(f), "berry bush across the seam = slow transit");
        assertEquals(NavFlags.HEADROOM_JUMP, NavFlags.headroom(f), "bush is passable, headroom stays");

        // A solid across the seam blocks the top-row floor's headroom outright.
        g = freshOverscanGrid();
        put(g, 8, 15, 8, state(Blocks.STONE));
        put(g, 8, 16, 8, state(Blocks.STONE));
        assertEquals(NavFlags.HEADROOM_NONE, NavFlags.headroom(NavFlags.compute(g, 8, 15, 8)),
                "solid across the seam = blocked at feet");

        // Two rows over the seam still lands in the deepest overscan read (y+3 = 18).
        g = freshOverscanGrid();
        put(g, 8, 15, 8, state(Blocks.STONE));
        put(g, 8, 18, 8, state(Blocks.STONE));
        assertEquals(NavFlags.HEADROOM_WALK, NavFlags.headroom(NavFlags.compute(g, 8, 15, 8)),
                "solid at the top overscan row caps headroom at WALK");

        // A bare 4096 scratch (no overscan) resolves above-section reads to air — the compat path for
        // callers without column context.
        long[] legacy = freshGrid();
        put(legacy, 8, 15, 8, state(Blocks.STONE));
        int lf = NavFlags.compute(legacy, 8, 15, 8);
        assertEquals(NavFlags.HEADROOM_JUMP, NavFlags.headroom(lf), "bare scratch stays air-optimistic above");
        assertFalse(NavFlags.clearableHazard(lf), "bare scratch sees no hazard above the section");
    }

    private static long[] freshOverscanGrid() {
        long[] g = new long[NavFlags.SCRATCH_SIZE];
        Arrays.fill(g, AIR);
        return g;
    }
}
