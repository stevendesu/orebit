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
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.pathfinding.blockpathfinder.movements.Ascend;
import com.orebit.mod.pathfinding.blockpathfinder.movements.Traverse;

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
 * The RATIFIED same-level jump arm (DESIGN-trapdoors.md §6, owner rulings §11.4): {@link Ascend} now emits
 * a {@code dy = 0} jump whenever the lip between the two floors' real tops sits in
 * {@code (STEP_ASSIST_MAX_RISE, JUMP_RISE]} — the 13/16 plate→full and 15/16 carpet→full pockets that were
 * historically one-way. Pinned here: both admissions (at the +1-jump price, edit-free), the low-ceiling
 * jump-clearance refusals (both columns, whole-cell conservative §4 v1), and the partition — a lip within
 * the auto-step budget ({@code ≤ 9}) is REFUSED by the arm and still owned by {@link Traverse}'s flat walk
 * (no double emission). Walk-only caps throughout, so nothing can be folded to cheat a gate. Lives in this
 * package for {@link NavGridView}'s package-private synthetic constructor.
 */
class AscendSameLevelJumpTest {

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

    /** Walk-only, doors.toggle on (irrelevant here — nothing toggleable in these scenes). */
    private static final BotCaps WALK = new BotCaps(1, BotCaps.DEFAULT_SAFE_FALL, BotCaps.DEFAULT_MAX_FALL,
            false, BotCaps.DEFAULT_COST_PER_HITPOINT, false, false, 100, false, BotCaps.DEFAULT_MAX_NODES,
            1.0f, true);

    private static final int SX = 5, Z = 6; // takeoff floor cell (SX, 0, Z); dest (SX+1, 0, Z)

    @Test
    void plateToFullThirteenSixteenthsIsJumped() {
        // Closed-BOTTOM trapdoor floor (top 3/16) → full stone at the same level: rise(0,16,3) = 13 ∈ (9,20].
        NavGridView grid = lipGrid(plate(), null, null);
        Capture cap = new Capture(SX + 1, 0, Z);
        new Ascend().candidates(new MovementContext(grid, WALK), SX, 0, Z, cap);
        assertTrue(cap.got, "the 13/16 plate→full lip is jumped at the same block level");
        assertFalse(cap.edits, "edit-free — a plain jump");
        assertEquals(Ascend.COST, cap.cost, 1e-4f, "priced like the +1 jump arm");
    }

    @Test
    void carpetToFullFifteenSixteenthsIsJumped() {
        // Carpet floor (top 1/16) → full stone: rise(0,16,1) = 15 ∈ (9,20].
        NavGridView grid = lipGrid(Blocks.WHITE_CARPET.defaultBlockState(), null, null);
        Capture cap = new Capture(SX + 1, 0, Z);
        new Ascend().candidates(new MovementContext(grid, WALK), SX, 0, Z, cap);
        assertTrue(cap.got, "the 15/16 carpet→full lip is jumped — the documented carpet-lip pocket exits");
        assertFalse(cap.edits, "edit-free");
        assertEquals(Ascend.COST, cap.cost, 1e-4f, "priced like the +1 jump");
    }

    @Test
    void refusedUnderALowCeilingOverTheDestColumn() {
        // Stone at (dest, y+3): the jump clearance over the DEST column fails — whole-cell conservative.
        NavGridView grid = lipGrid(plate(), null, Blocks.STONE.defaultBlockState());
        Capture cap = new Capture(SX + 1, 0, Z);
        new Ascend().candidates(new MovementContext(grid, WALK), SX, 0, Z, cap);
        assertFalse(cap.got, "no jump under a hard ceiling over the landing column (jump clearance)");
    }

    @Test
    void refusedUnderALowCeilingOverTheTakeoffColumn() {
        // Stone at (takeoff, y+3): the takeoff head-clearance fails — the existing HEADROOM_JUMP pattern.
        NavGridView grid = lipGrid(plate(), Blocks.STONE.defaultBlockState(), null);
        Capture cap = new Capture(SX + 1, 0, Z);
        new Ascend().candidates(new MovementContext(grid, WALK), SX, 0, Z, cap);
        assertFalse(cap.got, "no jump under a hard ceiling over the takeoff column");
    }

    @Test
    void stepAssistTerritoryStaysTraversesNoDoubleEmission() {
        // Bottom-slab floor (top 8) → full stone: rise(0,16,8) = 8 ≤ 9 — auto-step territory. The jump arm
        // must refuse it and Traverse's flat walk must still own it (exactly one movement emits the cell).
        NavGridView grid = lipGrid(Blocks.SMOOTH_STONE_SLAB.defaultBlockState()
                .setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM), null, null);
        MovementContext mc = new MovementContext(grid, WALK);

        Capture jump = new Capture(SX + 1, 0, Z);
        new Ascend().candidates(mc, SX, 0, Z, jump);
        assertFalse(jump.got, "a lip within the auto-step budget is NOT the jump arm's (rise 8 ≤ 9)");

        Capture walk = new Capture(SX + 1, 0, Z);
        new Traverse().candidates(mc, SX, 0, Z, walk);
        assertTrue(walk.got, "Traverse's flat walk still owns the slab→full auto-step");
        assertEquals(Traverse.FLAT_COST, walk.cost, 1e-4f, "an ordinary flat walk");
    }

    @Test
    void plateToFullIsNotAlsoEmittedByTraverse() {
        // The partition's other half: the 13/16 lip is beyond the auto-step, so the flat walk refuses it —
        // the jump arm is the SOLE emitter (no double emission in either band).
        NavGridView grid = lipGrid(plate(), null, null);
        Capture walk = new Capture(SX + 1, 0, Z);
        new Traverse().candidates(new MovementContext(grid, WALK), SX, 0, Z, walk);
        assertFalse(walk.got, "a 13/16 lip is beyond the auto-step — Traverse never emits it");
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
     * A 1-wide corridor at z=Z (stone floor y=0, air body y=1..3, ceiling y=4) with the takeoff floor
     * {@code (SX,0,Z)} swapped for {@code takeoffFloor}; optional stone plugs at the two columns' y+3
     * jump-clearance cells ({@code srcCeiling} → {@code (SX,3,Z)}, {@code dstCeiling} → {@code (SX+1,3,Z)}).
     */
    private static NavGridView lipGrid(BlockState takeoffFloor, BlockState srcCeiling, BlockState dstCeiling) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        PalettedContainer<BlockState> s = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        for (int x = 0; x < 16; x++)
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    s.set(x, y, z, stone);
        for (int cx = 2; cx <= 12; cx++) {
            s.set(cx, 1, Z, air);
            s.set(cx, 2, Z, air);
            s.set(cx, 3, Z, air);
        }
        s.set(SX, 0, Z, takeoffFloor);
        if (srcCeiling != null) s.set(SX, 3, Z, srcCeiling);
        if (dstCeiling != null) s.set(SX + 1, 3, Z, dstCeiling);
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

    /** Records whether a specific destination cell was emitted, its cost, and whether it folded any edit. */
    private static final class Capture implements CandidateSink {
        final int tx, ty, tz;
        boolean got;
        float cost;
        boolean edits;

        Capture(int tx, int ty, int tz) { this.tx = tx; this.ty = ty; this.tz = tz; }

        @Override
        public void accept(int x, int y, int z, float c, EditScratch e) {
            if (x == tx && y == ty && z == tz) {
                got = true;
                cost = c;
                edits = e != null && e.hasEdits();
            }
        }
    }
}
