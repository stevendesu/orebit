package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.orebit.mod.worldmodel.navblock.NavBlock;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

/**
 * The Phase-0 epoch test (PERF-DESIGN-navgrid-edit-batching.md §4.1/§6): a state change whose interned
 * navtype equals the cell's resident navtype is grid-invisible — {@code NavGridUpdater.onBlockChanged}
 * must NOT patch and must NOT bump the edit epoch — while a real navtype change patches and bumps
 * exactly as before. {@code onBlockChanged} itself is welded to a live {@code ServerLevel} (the
 * {@code NavStore}/epoch/portal-index keys — cannot be stood up under the Knot test classloader, the
 * same split {@code NetherPortalIndexTest} documents), so this drives the extracted decision seam
 * {@link NavGridUpdater#changesGrid} — the exact predicate that gates BOTH the epoch bump and the
 * {@code patchCell} call — plus the claims the skip rests on:
 * <ul>
 *   <li><b>Premise</b>: redstone-churn state flavors (wire power, lever/repeater powered) intern to ONE
 *       navtype, so the early-out actually fires on the churn it was built for.</li>
 *   <li><b>Skip is unobservable</b>: a same-navtype {@code patchCell} on a fully-maintained column
 *       recomputes byte-identical flags AND depth nibbles — skipping it changes nothing any search
 *       could see, which is what licenses skipping the epoch bump (the {@code EDIT_EPOCH} contract:
 *       unchanged epoch ⇒ re-search byte-identical).</li>
 *   <li><b>Real changes still patch</b>: a navtype-changing state trips the seam and the patch lands.</li>
 * </ul>
 * Residual gap (in-game-verified): the three-line wiring in {@code onBlockChanged} — seam-false returns
 * BEFORE the bump and the patch — is straight-line code with no branches beyond the seam itself.
 */
class NavGridEpochTest {

    private static boolean bootstrapped;

    @BeforeAll
    static void boot() {
        if (!bootstrapped) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            bootstrapped = true;
        }
    }

    private static final int SECTIONS = 2; // y 0..31

    private static final int WIRE_X = 5, WIRE_Y = 8, WIRE_Z = 5; // wire on the stone floor, section 0

    /**
     * Build the fixture column exactly as the live pipeline does (navtypes → overscan flags → depth
     * sweep): stone y0..7, a redstone wire (power 0) at the feature cell on the floor, air above.
     */
    private static NavSection[] buildColumn() {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState wire = Blocks.REDSTONE_WIRE.defaultBlockState();

        NavSection[] sections = new NavSection[SECTIONS];
        boolean[] allAir = new boolean[SECTIONS];
        for (int i = 0; i < SECTIONS; i++) {
            PalettedContainer<BlockState> states = new PalettedContainer<>(
                    air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
            boolean any = false;
            for (int ly = 0; ly < 16; ly++) {
                int y = i * 16 + ly;
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        BlockState s = air;
                        if (y <= 7) s = stone;
                        else if (x == WIRE_X && y == WIRE_Y && z == WIRE_Z) s = wire;
                        if (s != air) {
                            states.set(x, ly, z, s);
                            any = true;
                        }
                    }
                }
            }
            sections[i] = NavSection.create(BlockPos.ZERO);
            allAir[i] = NavSectionBuilder.classifyNavtypes(states, !any, sections[i].getTraversalGrid(), null);
        }
        for (int i = 0; i < SECTIONS; i++) {
            NavSection above = i + 1 < SECTIONS ? sections[i + 1] : null;
            NavSectionBuilder.computeFlags(sections[i].getTraversalGrid(), allAir[i],
                    (above == null || allAir[i + 1]) ? null : above.getTraversalGrid());
        }
        NavSectionBuilder.computeDepth(sections);
        return sections;
    }

    // ---- the premise: redstone churn is navtype-invariant --------------------------------------

    @Test
    void redstoneChurnKeepsTheNavtype() {
        BlockState wire0 = Blocks.REDSTONE_WIRE.defaultBlockState();
        BlockState wire15 = wire0.setValue(BlockStateProperties.POWER, 15);
        assertNotEquals(wire0, wire15, "distinct interned states (the change really fires the hook)");
        assertEquals(NavBlock.navtypeFor(wire0), NavBlock.navtypeFor(wire15),
                "wire power flips must intern to one navtype");

        BlockState lever = Blocks.LEVER.defaultBlockState();
        assertEquals(NavBlock.navtypeFor(lever),
                NavBlock.navtypeFor(lever.setValue(BlockStateProperties.POWERED, true)),
                "lever powered flips must intern to one navtype");

        BlockState repeater = Blocks.REPEATER.defaultBlockState();
        assertEquals(NavBlock.navtypeFor(repeater),
                NavBlock.navtypeFor(repeater.setValue(BlockStateProperties.POWERED, true)),
                "repeater powered flips must intern to one navtype");
    }

    // ---- the decision seam: what bumps + patches, what returns early --------------------------

    @Test
    void navtypeInvariantChangeIsGridInvisible() {
        NavSection[] col = buildColumn();
        NavSection s0 = col[0];

        // Resident cell is wire@power0; the incoming state is wire@power15 — same navtype, no-op.
        BlockState wire15 = Blocks.REDSTONE_WIRE.defaultBlockState().setValue(BlockStateProperties.POWER, 15);
        assertFalse(NavGridUpdater.changesGrid(s0, WIRE_X, WIRE_Y, WIRE_Z, NavBlock.navtypeFor(wire15)),
                "same-navtype change must be grid-invisible (no patch, no epoch bump)");

        // A real navtype change at the same cell must trip the seam (bump + patch, exactly as before).
        short stoneNav = NavBlock.navtypeFor(Blocks.STONE.defaultBlockState());
        assertNotEquals(NavBlock.navtypeFor(wire15), stoneNav, "premise: stone is a different navtype");
        assertTrue(NavGridUpdater.changesGrid(s0, WIRE_X, WIRE_Y, WIRE_Z, stoneNav),
                "navtype-changing change must patch and bump");

        // And on a plain floor cell: stone→stone-flavor no-op vs stone→air real change.
        short airNav = NavBlock.navtypeFor(Blocks.AIR.defaultBlockState());
        assertFalse(NavGridUpdater.changesGrid(s0, 3, 4, 3, stoneNav));
        assertTrue(NavGridUpdater.changesGrid(s0, 3, 4, 3, airNav));
    }

    // ---- the correctness claim licensing the skip: a same-navtype patch is byte-identical -----

    @Test
    void sameNavtypeRepatchIsByteIdentical() {
        NavSection[] col = buildColumn();

        short[][] rawBefore = new short[SECTIONS][];
        byte[][] depthBefore = new byte[SECTIONS][];
        for (int i = 0; i < SECTIONS; i++) {
            rawBefore[i] = col[i].getTraversalGrid().raw().clone();
            depthBefore[i] = col[i].getTraversalGrid().depthRaw().clone();
        }

        // Force the patch the early-out skips: wire@power15 onto the resident wire@power0 cell.
        BlockState wire15 = Blocks.REDSTONE_WIRE.defaultBlockState().setValue(BlockStateProperties.POWER, 15);
        NavSectionBuilder.patchCell(col[0], col[1], null, WIRE_X, WIRE_Y, WIRE_Z, wire15);

        for (int i = 0; i < SECTIONS; i++) {
            assertArrayEquals(rawBefore[i], col[i].getTraversalGrid().raw(),
                    "section " + i + ": a same-navtype patch must recompute byte-identical flags/navtypes");
            assertArrayEquals(depthBefore[i], col[i].getTraversalGrid().depthRaw(),
                    "section " + i + ": a same-navtype patch must recompute byte-identical depth nibbles");
        }

        // Sanity: a REAL change through the same call path is visible (the seam and the patch agree).
        NavSectionBuilder.patchCell(col[0], col[1], null, WIRE_X, WIRE_Y, WIRE_Z,
                Blocks.STONE.defaultBlockState());
        assertEquals(NavBlock.navtypeFor(Blocks.STONE.defaultBlockState()) & 0xFFFF,
                col[0].getTraversalGrid().navtype(WIRE_X, WIRE_Y, WIRE_Z));
    }
}
