package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.orebit.mod.Debug;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.CandidateSink;
import com.orebit.mod.pathfinding.blockpathfinder.EditScratch;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.pathfinding.blockpathfinder.StepEdits;
import com.orebit.mod.pathfinding.blockpathfinder.movements.Traverse;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

/**
 * TRAPDOORS — the owner's vertically-sandwiched needle case (DESIGN-trapdoors.md §9-3b, ruling §11.5): a
 * 1-wide 2-tall hallway whose ONE column holds a closed-TOP trapdoor in the FEET cell and a closed-BOTTOM
 * trapdoor in the HEAD cell. This is the scene that ratified the §5 TOGGLE-FOR-CLEARANCE trigger (b) (a
 * bisecting closed plate is a CLEARANCE failure — its blocked face is UP/DOWN while travel is horizontal,
 * so only the clearance arm can offer the {@code SET_OPEN}), the §4 stacked-panel width soundness (two
 * opened panels on OPPOSITE walls leave a 10/16 = 0.625 needle slot &gt; the 0.6 body; same-wall panels a
 * 13/16 slot), and the grow-on-demand {@code EditScratch} doors buffer (a door exit toggle plus the
 * sandwich pair is 3 SETs / 4 folded cells in ONE candidate — must not overflow or drop). Cases:
 * (a) both closed, facings opposite + perpendicular → path with exactly TWO {@code SET_OPEN}s at 2×
 * {@link MovementContext#DOOR_TOGGLE_COST}; (b) both already open → free pass, zero edits; (c) adversarial
 * along-axis facing → the column REFUSES (the opened panel would lie across travel — no toggle chain may
 * pretend otherwise); (d) same-wall variant (equal facings, 13/16 slot) → admitted, two SETs; (e) door
 * exit toggle + the pair = three SETs / four cells in one step, all carried. Walk-only + toggle caps
 * throughout. Lives in this package for {@link NavGridView}'s package-private synthetic constructor.
 */
class TrapdoorSandwichTest {

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

    private static BotCaps caps(boolean toggle) {
        return new BotCaps(1, BotCaps.DEFAULT_SAFE_FALL, BotCaps.DEFAULT_MAX_FALL, false,
                BotCaps.DEFAULT_COST_PER_HITPOINT, false, false,
                100, false, BotCaps.DEFAULT_MAX_NODES, 1.0f, toggle);
    }

    private static final BotCaps WALK_TOGGLE = caps(true);

    private static final int TD_X = 7, Z = 6;
    private static final float FLAT = Traverse.FLAT_COST;
    private static final float TOGGLE = MovementContext.DOOR_TOGGLE_COST;

    // ================================================================================================
    // (a) Both closed, facings OPPOSITE and PERPENDICULAR to the corridor (feet TOP faces N → opens
    //     onto the S wall; head BOTTOM faces S → opens onto the N wall): TWO SET_OPENs in one step,
    //     cost 2× toggle; the 10/16 needle slot then admits the 0.6 body (§4 stacked-panel rule).
    // ================================================================================================

    @Test
    void bothClosedFoldsTwoSetOpens() {
        NavGridView grid = sandwich(Direction.NORTH, false, Direction.SOUTH, false);
        MovementContext mc = new MovementContext(grid, WALK_TOGGLE);

        Capture cap = new Capture(TD_X, 0, Z);
        new Traverse().candidates(mc, TD_X - 1, 0, Z, cap);
        assertTrue(cap.got, "the sandwich column is offered — both plates fold SET_OPENs (§5 trigger b)");
        assertTrue(cap.edits, "the crossing carries the two SETs");
        assertEquals(FLAT + 2 * TOGGLE, cap.cost, 1e-4f, "flat walk + exactly TWO toggles");

        BlockPathPlan plan = BlockPathfinder.findPath(grid, new BlockPos(2, 0, Z), new BlockPos(12, 0, Z),
                WALK_TOGGLE);
        assertNotNull(plan, "the walk-only bot threads the sandwich by opening both plates");
        assertEndsNear(plan, 12);
        assertSandwichSets(plan, 2, 0);
    }

    // ================================================================================================
    // (b) Both already OPEN (panels on opposite side walls — the live 10/16 needle slot): free pass,
    //     zero edits of any kind.
    // ================================================================================================

    @Test
    void bothOpenPassesFree() {
        NavGridView grid = sandwich(Direction.NORTH, true, Direction.SOUTH, true);
        MovementContext mc = new MovementContext(grid, WALK_TOGGLE);

        Capture cap = new Capture(TD_X, 0, Z);
        new Traverse().candidates(mc, TD_X - 1, 0, Z, cap);
        assertTrue(cap.got, "the 10/16 needle slot admits the 0.6 body (§4 stacked-panel soundness)");
        assertFalse(cap.edits, "a live needle slot is crossed FREE");
        assertEquals(FLAT, cap.cost, 1e-4f, "no toggle cost on open parallel panels");

        BlockPathPlan plan = BlockPathfinder.findPath(grid, new BlockPos(2, 0, Z), new BlockPos(12, 0, Z),
                WALK_TOGGLE);
        assertNotNull(plan, "the needle slot is walked end to end");
        assertEndsNear(plan, 12);
        assertSandwichSets(plan, 0, 0);
    }

    // ================================================================================================
    // (c) ADVERSARIAL: the feet plate faces ALONG the corridor axis (EAST) — its opened panel would lie
    //     ACROSS travel, so trigger (b) refuses and no toggle chain works: the column REFUSES.
    // ================================================================================================

    @Test
    void alongAxisFacingRefusesTheColumn() {
        NavGridView grid = sandwich(Direction.EAST, false, Direction.SOUTH, false);

        Capture cap = new Capture(TD_X, 0, Z);
        new Traverse().candidates(new MovementContext(grid, WALK_TOGGLE), TD_X - 1, 0, Z, cap);
        assertFalse(cap.got, "an along-axis facing opens ACROSS travel — the candidate refuses (§5)");

        BlockPathPlan plan = BlockPathfinder.findPath(grid, new BlockPos(2, 0, Z), new BlockPos(12, 0, Z),
                WALK_TOGGLE);
        assertNeverCrosses(plan, TD_X, "no toggle chain crosses the adversarial sandwich");
    }

    // ================================================================================================
    // (d) SAME-WALL variant: both facings NORTH → both opened panels on the SOUTH wall (a 13/16 slot,
    //     wider than the needle): admitted with the same two SET_OPENs.
    // ================================================================================================

    @Test
    void sameWallVariantAdmitted() {
        NavGridView grid = sandwich(Direction.NORTH, false, Direction.NORTH, false);
        MovementContext mc = new MovementContext(grid, WALK_TOGGLE);

        Capture cap = new Capture(TD_X, 0, Z);
        new Traverse().candidates(mc, TD_X - 1, 0, Z, cap);
        assertTrue(cap.got, "same-wall panels leave a 13/16 slot — admitted");
        assertEquals(FLAT + 2 * TOGGLE, cap.cost, 1e-4f, "still exactly TWO toggles");

        BlockPathPlan plan = BlockPathfinder.findPath(grid, new BlockPos(2, 0, Z), new BlockPos(12, 0, Z),
                WALK_TOGGLE);
        assertNotNull(plan, "the same-wall sandwich is crossed");
        assertEndsNear(plan, 12);
        assertSandwichSets(plan, 2, 0);
    }

    // ================================================================================================
    // (e) THREE SETs in one step (the EditScratch grow case): the bot stands in a CLOSED door whose
    //     panel blocks the eastward exit (facing WEST → blocked edge EAST), with the sandwich in the
    //     next column. One candidate folds the door exit toggle (2 half-cells, ONE cost) + the two
    //     trapdoor SET_OPENs = 4 folded cells / 3 charged toggles — the doors[] buffer (historically
    //     sized 2) must grow, and every SET must be carried into the plan.
    // ================================================================================================

    @Test
    void doorExitPlusSandwichCarriesThreeSetsInOneStep() {
        NavGridView grid = sandwichWithDoor();
        BlockPathPlan plan = BlockPathfinder.findPath(grid, new BlockPos(2, 0, Z), new BlockPos(12, 0, Z),
                WALK_TOGGLE);
        assertNotNull(plan, "door + sandwich corridor is crossed");
        assertEndsNear(plan, 12);

        // The step that crosses into the sandwich column carries all FOUR cells (2 door halves + 2
        // trapdoors), all SET_OPEN. Find it and pin the whole edit-set.
        boolean found = false;
        for (int i = 0; i < plan.size(); i++) {
            StepEdits e = plan.edits(i);
            if (e == null || e.doorSetCount() != 4) continue;
            found = true;
            int doorHalves = 0, trapdoors = 0;
            for (int j = 0; j < e.doorSetCount(); j++) {
                assertTrue(e.doorSetOpenAt(j), "every SET in the 3-toggle step is a SET_OPEN");
                long cell = e.doorSetAt(j);
                if (cell == BlockPos.asLong(TD_X - 1, 1, Z) || cell == BlockPos.asLong(TD_X - 1, 2, Z)) {
                    doorHalves++;
                } else if (cell == BlockPos.asLong(TD_X, 1, Z) || cell == BlockPos.asLong(TD_X, 2, Z)) {
                    trapdoors++;
                }
            }
            assertEquals(2, doorHalves, "both door half-cells carried");
            assertEquals(2, trapdoors, "both sandwich trapdoors carried");
        }
        assertTrue(found, "one step carries all four SET cells (the EditScratch doors[] grow case)");
    }

    // ---- scene builders (the TrapdoorClearanceTest single-section idiom) ----------------------------

    private static BlockState td(Block block, Direction facing, boolean open, Half half) {
        return block.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
                .setValue(BlockStateProperties.OPEN, open)
                .setValue(BlockStateProperties.HALF, half)
                .setValue(BlockStateProperties.WATERLOGGED, false);
    }

    /** The 2-tall corridor (stone floor y=0, air body y=1..2, stone ceiling y=3, x=2..12) with the
     *  sandwich column at TD_X: a trapdoor with {@code feetFacing} (TOP half) in the feet cell and one
     *  with {@code headFacing} (BOTTOM half) in the head cell, each open per its flag. */
    private static NavGridView sandwich(Direction feetFacing, boolean feetOpen,
                                        Direction headFacing, boolean headOpen) {
        BlockState air = Blocks.AIR.defaultBlockState();
        PalettedContainer<BlockState> s = filledStone(air);
        for (int cx = 2; cx <= 12; cx++) { s.set(cx, 1, Z, air); s.set(cx, 2, Z, air); }
        s.set(TD_X, 1, Z, td(Blocks.OAK_TRAPDOOR, feetFacing, feetOpen, Half.TOP));
        s.set(TD_X, 2, Z, td(Blocks.OAK_TRAPDOOR, headFacing, headOpen, Half.BOTTOM));
        return single(s);
    }

    /** The (a) sandwich plus a CLOSED oak door filling the column just west of it (facing WEST → its
     *  panel blocks the EAST exit edge), so the step into the sandwich owes the door exit toggle too. */
    private static NavGridView sandwichWithDoor() {
        BlockState air = Blocks.AIR.defaultBlockState();
        PalettedContainer<BlockState> s = filledStone(air);
        for (int cx = 2; cx <= 12; cx++) { s.set(cx, 1, Z, air); s.set(cx, 2, Z, air); }
        s.set(TD_X, 1, Z, td(Blocks.OAK_TRAPDOOR, Direction.NORTH, false, Half.TOP));
        s.set(TD_X, 2, Z, td(Blocks.OAK_TRAPDOOR, Direction.SOUTH, false, Half.BOTTOM));
        BlockState doorBase = Blocks.OAK_DOOR.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST)
                .setValue(BlockStateProperties.OPEN, false);
        s.set(TD_X - 1, 1, Z, doorBase.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.LOWER));
        s.set(TD_X - 1, 2, Z, doorBase.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER));
        return single(s);
    }

    private static PalettedContainer<BlockState> filledStone(BlockState air) {
        BlockState stone = Blocks.STONE.defaultBlockState();
        PalettedContainer<BlockState> s = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        for (int x = 0; x < 16; x++)
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    s.set(x, y, z, stone);
        return s;
    }

    private static NavGridView single(PalettedContainer<BlockState> s) {
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

    // ---- assertions ---------------------------------------------------------------------------------

    private static void assertEndsNear(BlockPathPlan plan, int goalX) {
        BlockPos last = plan.waypoint(plan.size() - 1);
        assertTrue(Math.abs(last.getX() - goalX) <= 1 && last.getZ() == Z,
                "the plan ends at the goal; ended " + last);
    }

    /** The whole plan folds exactly {@code setOpens} SET_OPENs (all on the sandwich column) and
     *  {@code setCloseds} SET_CLOSEDs, and breaks nothing. */
    private static void assertSandwichSets(BlockPathPlan plan, int setOpens, int setCloseds) {
        int open = 0, closed = 0, breaks = 0;
        for (int i = 0; i < plan.size(); i++) {
            StepEdits e = plan.edits(i);
            if (e == null) continue;
            breaks += e.breakCount();
            for (int j = 0; j < e.doorSetCount(); j++) {
                long cell = e.doorSetAt(j);
                assertTrue(cell == BlockPos.asLong(TD_X, 1, Z) || cell == BlockPos.asLong(TD_X, 2, Z),
                        "every SET is on the sandwich column");
                if (e.doorSetOpenAt(j)) open++;
                else closed++;
            }
        }
        assertEquals(setOpens, open, "SET_OPEN count");
        assertEquals(setCloseds, closed, "SET_CLOSED count");
        assertEquals(0, breaks, "a walk-only bot breaks nothing");
    }

    /** No waypoint ever reaches the barrier column {@code barX} or beyond (partial plans allowed). */
    private static void assertNeverCrosses(BlockPathPlan plan, int barX, String msg) {
        if (plan == null) return;
        for (int i = 0; i < plan.size(); i++) {
            assertTrue(plan.waypoint(i).getX() < barX, msg + "; reached " + plan.waypoint(i));
        }
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
