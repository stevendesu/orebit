package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.orebit.mod.Debug;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.CandidateSink;
import com.orebit.mod.pathfinding.blockpathfinder.EditScratch;
import com.orebit.mod.pathfinding.blockpathfinder.movements.Diagonal;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

/**
 * The RATIFIED {@link Diagonal} rise gate (DESIGN-trapdoors.md §6, owner rulings §11.4 — a bug fix, not a
 * trapdoor feature): a same-block-level diagonal must fit the auto-step between the two floors' REAL tops,
 * {@code rise(0, destTopY, startTopY) ≤ STEP_ASSIST_MAX_RISE}, mirroring {@link
 * com.orebit.mod.pathfinding.blockpathfinder.movements.Traverse}'s flat gate. Before the fix a plate/carpet
 * → full-block diagonal (a 13–15/16 lip no auto-step clears) was emitted and physically unwalkable. Pinned:
 * the plate→full refusal, the slab→full (rise 8) admission unchanged, and the negative-lip step-down
 * admission unchanged. Lives in this package for {@link NavGridView}'s package-private constructor.
 */
class DiagonalRiseGateTest {

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

    private static final BotCaps WALK = new BotCaps(1, BotCaps.DEFAULT_SAFE_FALL, BotCaps.DEFAULT_MAX_FALL,
            false, BotCaps.DEFAULT_COST_PER_HITPOINT, false, false, 100, false, BotCaps.DEFAULT_MAX_NODES,
            1.0f, true);

    private static final int SX = 5, Z = 6; // start floor (SX,0,Z); diagonal dest (SX+1,0,Z+1)

    @Test
    void plateToFullDiagonalIsNowRefused() {
        // Closed-BOTTOM trapdoor start (top 3/16) → full stone diagonally: rise 13 > 9 — unwalkable, and
        // historically emitted anyway (the plate-flavored instance of the missing gate).
        NavGridView grid = diagGrid(plate(), Blocks.STONE.defaultBlockState());
        Capture cap = new Capture(SX + 1, 0, Z + 1);
        new Diagonal().candidates(new MovementContext(grid, WALK), SX, 0, Z, cap);
        assertFalse(cap.got, "a 13/16 plate→full diagonal lip is beyond the auto-step — refused");
    }

    @Test
    void slabToFullDiagonalStaysAdmitted() {
        // Bottom slab start (top 8) → full stone: rise 8 ≤ 9 — the ordinary auto-step diagonal, unchanged.
        NavGridView grid = diagGrid(Blocks.SMOOTH_STONE_SLAB.defaultBlockState()
                .setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM), Blocks.STONE.defaultBlockState());
        Capture cap = new Capture(SX + 1, 0, Z + 1);
        new Diagonal().candidates(new MovementContext(grid, WALK), SX, 0, Z, cap);
        assertTrue(cap.got, "a slab→full diagonal (rise 8) still walks");
        assertEquals(Diagonal.COST, cap.cost, 1e-4f, "an ordinary clean diagonal — no surcharge");
    }

    @Test
    void steppingDownOntoAPlateDiagonallyStaysAdmitted() {
        // Full start → plate dest: rise −13 (a step down) — negative lips always pass, unchanged.
        NavGridView grid = diagGrid(Blocks.STONE.defaultBlockState(), plate());
        Capture cap = new Capture(SX + 1, 0, Z + 1);
        new Diagonal().candidates(new MovementContext(grid, WALK), SX, 0, Z, cap);
        assertTrue(cap.got, "a diagonal step DOWN onto a plate floor still walks (negative lip)");
    }

    // ---- scene builder ------------------------------------------------------------------------------

    private static BlockState plate() {
        return Blocks.OAK_TRAPDOOR.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                .setValue(BlockStateProperties.OPEN, false)
                .setValue(BlockStateProperties.HALF, Half.BOTTOM)
                .setValue(BlockStateProperties.WATERLOGGED, false);
    }

    /**
     * An open 2-wide walkway: stone floor y=0 over rows z=Z..Z+1 (x=2..12), air body y=1..2 above the
     * whole patch (so both corner columns are clear); the start floor {@code (SX,0,Z)} and the diagonal
     * destination floor {@code (SX+1,0,Z+1)} swapped for the given states.
     */
    private static NavGridView diagGrid(BlockState startFloor, BlockState destFloor) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        PalettedContainer<BlockState> s = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        for (int x = 0; x < 16; x++)
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    s.set(x, y, z, stone);
        for (int cx = 2; cx <= 12; cx++) {
            for (int cz = Z; cz <= Z + 1; cz++) {
                s.set(cx, 1, cz, air);
                s.set(cx, 2, cz, air);
            }
        }
        s.set(SX, 0, Z, startFloor);
        s.set(SX + 1, 0, Z + 1, destFloor);
        NavSection section = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(s, false, section.getTraversalGrid());
        PalettedContainer<BlockState> airStates = new PalettedContainer<>(
                Blocks.AIR.defaultBlockState(), Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        NavSection airSection = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(airStates, true, airSection.getTraversalGrid());
        NavSection[] column = { section, airSection, airSection, airSection };
        ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        chunks.put(NavStore.key(0, 0), column);
        return new NavGridView(0, chunks);
    }

    /** Records whether a specific destination cell was emitted and its cost. */
    private static final class Capture implements CandidateSink {
        final int tx, ty, tz;
        boolean got;
        float cost;

        Capture(int tx, int ty, int tz) { this.tx = tx; this.ty = ty; this.tz = tz; }

        @Override
        public void accept(int x, int y, int z, float c, EditScratch e) {
            if (x == tx && y == ty && z == tz) {
                got = true;
                cost = c;
            }
        }
    }
}
