package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.orebit.mod.Debug;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.CandidateSink;
import com.orebit.mod.pathfinding.blockpathfinder.EditScratch;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.pathfinding.blockpathfinder.MovementRegistry;
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
 * Pins {@code StartSprintSwim}'s <b>eye-submersion gate</b> — the in-place prone transition may only be
 * emitted where vanilla would actually grant the pose.
 *
 * <p><b>The conviction (2026-08-20, the noodle-cave wedge).</b> A 1-wide cave stream ran four blocks
 * horizontally before cascading down a step, so the cell above the drop held a shallow FLOWING block rather
 * than a full one. The planner's test was "feet cell holds water AND head cell holds water", which that
 * geometry satisfies — but vanilla starts a swim only while {@code Entity.isUnderWater()}, i.e. with the
 * fluid SURFACE above {@code getEyeY()}, and a flowing block's surface is {@code amount/9} of its cell. The
 * bot was handed a pose the physics refused and sat in the transition indefinitely.
 *
 * <p><b>The arithmetic</b> (constants source-verified — see
 * {@link MovementContext#eyesSubmergedWithHeadIn}): a standing bot's eye is at {@code feetY + 1.62}, inside
 * the head cell, so the fluid must clear {@code 0.62} of that cell — {@code amount >= 6}
 * ({@code 6/9 = 0.667}), with {@code amount 5} ({@code 0.556}) falling short. The one escape is vanilla's
 * {@code hasSameAbove} rule: the same fluid directly above forces the surface to a full {@code 1.0}
 * regardless of this cell's own amount.
 *
 * <p>Note that CONTINUING a swim needs only {@code isInWater()}, which is why the gate belongs on the START
 * move alone, and why a bot can legitimately hold a swim through shallows it could never have begun in.
 *
 * <p>Blockstate {@code LEVEL} maps to amount as {@code amount = 8 - LEVEL} for flowing water (so LEVEL 2 =
 * amount 6, LEVEL 3 = amount 5); LEVEL 0 is the source at amount 8. Lives in this package to reach
 * {@link NavGridView}'s package-private synthetic constructor, per the {@link DiagonalSprintSwimTest} idiom.
 */
class SprintSwimEyeSubmersionTest {

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

    // The node under test is a FLOOR cell: feet at NY+1, head at NY+2, and the hasSameAbove probe at NY+3.
    private static final int NX = 8, NY = 4, NZ = 8;
    private static final int FEET = NY + 1, HEAD = NY + 2, ABOVE = NY + 3;

    // ---- the regression -------------------------------------------------------------------------------

    @Test
    void aShallowFlowInTheHeadCellIsRefused_theNoodleCaveWedge() {
        // amount 5 = 0.556 of the cell, and the eye needs 0.62. This is the exact wedge shape: the cell
        // unambiguously "holds water", and the pose is unreachable anyway.
        Capture cap = expand(waterColumn(flowing(3), Blocks.AIR.defaultBlockState()));
        assertTrue(cap.cells.isEmpty(),
                "amount 5 leaves the eyes dry (5/9 = 0.556 < 0.62) — vanilla will not grant the prone pose, "
                        + "so the planner must not offer it. Emitted: " + cap.cells.size());
    }

    @Test
    void amountSixIsTheBoundaryAndIsEmitted() {
        // 6/9 = 0.667 > 0.62. One amount step above the refusal, so the threshold is pinned from both sides
        // rather than merely pinning "shallow is refused".
        Capture cap = expand(waterColumn(flowing(2), Blocks.AIR.defaultBlockState()));
        assertEquals(1, cap.cells.size(), "amount 6 clears the standing eye and must still be offered");
        assertTrue(cap.has(NX, NY, NZ), "the in-place prone transition stays on the node's own floor cell");
    }

    // ---- the hasSameAbove escape ----------------------------------------------------------------------

    @Test
    void aMinimalFlowWithWaterAboveIsEmitted_hasSameAboveForcesFullHeight() {
        // amount 1 — the shallowest flow there is — but with water directly above, FlowingFluid.getHeight
        // returns a flat 1.0 for this cell. Refusing here would wrongly strand a bot inside a water column.
        Capture cap = expand(waterColumn(flowing(7), Blocks.WATER.defaultBlockState()));
        assertEquals(1, cap.cells.size(),
                "same fluid above forces this cell's surface to 1.0 — its own amount is irrelevant there");
    }

    @Test
    void aMinimalFlowWithAirAboveIsRefused() {
        // The control for the case above: identical head cell, nothing above it.
        Capture cap = expand(waterColumn(flowing(7), Blocks.AIR.defaultBlockState()));
        assertTrue(cap.cells.isEmpty(), "amount 1 under open air is 0.111 of a cell — nowhere near the eyes");
    }

    // ---- the ordinary case must survive ---------------------------------------------------------------

    @Test
    void aFullSourceHeadCellIsStillEmitted() {
        // The everyday deep-water start. A source is amount 8 (0.889), so it clears the eye on its own and
        // needs no help from the cell above — this guards the gate against over-refusing into a wedge of its
        // own making.
        Capture cap = expand(waterColumn(Blocks.WATER.defaultBlockState(), Blocks.AIR.defaultBlockState()));
        assertEquals(1, cap.cells.size(), "a full source head cell has always been legal and must remain so");
    }

    // ---- the descriptor field the gate reads ----------------------------------------------------------

    @Test
    void theLevelFieldRoundTripsTheAmountsThisGateDependsOn() {
        // If the encoding drifts, the gate silently changes meaning; pin the exact mapping it consumes.
        assertEquals(8, NavBlock.fluidLevel(NavBlock.descriptorFor(Blocks.WATER.defaultBlockState())));
        assertEquals(6, NavBlock.fluidLevel(NavBlock.descriptorFor(flowing(2))));
        assertEquals(5, NavBlock.fluidLevel(NavBlock.descriptorFor(flowing(3))));
        assertEquals(1, NavBlock.fluidLevel(NavBlock.descriptorFor(flowing(7))));
        assertEquals(6, MovementContext.EYE_SUBMERGE_MIN_AMOUNT,
                "the threshold is derived from the 1.62 eye height and amount/9 fluid height — not a tunable");
    }

    // ---- helpers --------------------------------------------------------------------------------------

    private static BlockState flowing(int level) {
        return Blocks.WATER.defaultBlockState().setValue(BlockStateProperties.LEVEL, level);
    }

    /** Expand {@code START_SPRINT_SWIM} at the node from a STANDING pose, capturing what it offers. */
    private static Capture expand(NavGridView grid) {
        MovementContext ctx = new MovementContext(grid, BotCaps.DEFAULT);
        ctx.setMode(MovementContext.MODE_STANDING);
        Capture cap = new Capture();
        MovementRegistry.START_SPRINT_SWIM.candidates(ctx, NX, NY, NZ, cap);
        return cap;
    }

    /**
     * A stone floor at {@code NY} with a full water source at the FEET cell, {@code head} at the head cell and
     * {@code above} one higher. Only the head cell varies across the tests: the feet are always unambiguously
     * wet, so every refusal below is attributable to the eye test and nothing else.
     */
    private static NavGridView waterColumn(BlockState head, BlockState above) {
        return build(s -> {
            s.set(NX, NY, NZ, Blocks.STONE.defaultBlockState());
            s.set(NX, FEET, NZ, Blocks.WATER.defaultBlockState());
            s.set(NX, HEAD, NZ, head);
            s.set(NX, ABOVE, NZ, above);
        });
    }

    private static NavGridView build(Consumer<PalettedContainer<BlockState>> fill) {
        BlockState air = Blocks.AIR.defaultBlockState();
        PalettedContainer<BlockState> s = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        fill.accept(s);

        NavSection section = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(s, false, section.getTraversalGrid());

        PalettedContainer<BlockState> airStates = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        NavSection airSection = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(airStates, true, airSection.getTraversalGrid());

        NavSection[] column = { section, airSection, airSection, airSection };
        ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        chunks.put(NavStore.key(0, 0), column);
        return new NavGridView(0, chunks);
    }

    private static final class Capture implements CandidateSink {
        final List<int[]> cells = new ArrayList<>();

        @Override
        public void accept(int x, int y, int z, float cost, EditScratch edits) {
            cells.add(new int[] { x, y, z });
        }

        boolean has(int x, int y, int z) {
            for (int[] c : cells) if (c[0] == x && c[1] == y && c[2] == z) return true;
            return false;
        }
    }
}
