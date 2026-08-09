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
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

/**
 * TRAPDOORS Stage 2 — the §4 generalized residual clearance rules + the §5 face-aware entry folds
 * (DESIGN-trapdoors.md), pinned at candidate level (the {@link Capture}/{@code CandidateSink} idiom from
 * {@code DoorToggleTest}) and by full searches. The owner's brief, case by case: a 3/16 plate floor under a
 * hard 2-block ceiling WALKS (the head cell is not consulted at {@code t ≤ 3} — body top 1.9875 &lt; 2.0);
 * a closed-TOP hatch flush in the head cell WALKS UNDER (uniform top band 13 ≥ t−3, gap 1.8125 ≥ 1.8); the
 * 1.625 double-plate hallway and both head-bisection cases REFUSE <b>as intact geometry</b> (pinned on
 * IRON, where no toggle exists) — while their TOGGLEABLE flavors are rescued by the §5 TOGGLE-FOR-CLEARANCE
 * trigger (b) (owner-ratified, ruling §11.5): a perpendicular-facing closed plate folds exactly ONE {@code
 * SET_OPEN} (the opened panel lands on a side wall — the §4 guide-rail admit), and an ALONG-axis facing
 * (opened panel across travel) still refuses — no toggle chain may pretend otherwise. A slab floor under a
 * top-slab ceiling WALKS (band 8 ≥ 5 — the generalized, non-trapdoor beneficiary) while a full floor under
 * the same top slab still REFUSES (8 &lt; 13, gap 1.5); an OPEN panel parallel to travel is crossed FREE
 * with no edit; and crossing an open panel's blocked face folds exactly ONE {@code SET_CLOSED} at {@link
 * MovementContext#DOOR_TOGGLE_COST}. Walk-only bots throughout — a crossing PROVES the admit/fold (nothing
 * can be mined). Lives in this package for {@link NavGridView}'s package-private synthetic constructor
 * (the {@link DoorPassageTest} scene idiom).
 */
class TrapdoorClearanceTest {

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

    private static BotCaps caps(boolean canBreak, boolean toggle) {
        return new BotCaps(1, BotCaps.DEFAULT_SAFE_FALL, BotCaps.DEFAULT_MAX_FALL, false,
                BotCaps.DEFAULT_COST_PER_HITPOINT, canBreak, false,
                100, false, BotCaps.DEFAULT_MAX_NODES, 1.0f, toggle);
    }

    /** Walk-only + doors.toggle ON: a crossing proves a free admit or a SET fold — never a break. */
    private static final BotCaps WALK_TOGGLE = caps(false, true);
    /** Walk-only + doors.toggle OFF: the SET folds must vanish (the flag kill-switch regression). */
    private static final BotCaps WALK_OFF = caps(false, false);

    private static final int Z = 6, TD_X = 7;
    private static final float FLAT = Traverse.FLAT_COST;
    private static final float TOGGLE_WALK = Traverse.FLAT_COST + MovementContext.DOOR_TOGGLE_COST;

    // ================================================================================================
    // (a) A closed-BOTTOM plate floor in a "2-tall" hallway (plate top 3/16 → stone ceiling base 2.0 —
    //     a 1.8125 gap): the head cell is NOT consulted at t ≤ 3 — the plate hallway WALKS.
    // ================================================================================================

    @Test
    void plateFloorUnderTwoTallCeilingWalks() {
        NavGridView grid = plateHallway();
        MovementContext mc = new MovementContext(grid, WALK_TOGGLE);

        Capture cap = new Capture(TD_X, 0, Z);
        new Traverse().candidates(mc, TD_X - 1, 0, Z, cap);
        assertTrue(cap.got, "plate → plate flat walk under the 2-tall ceiling is ADMITTED (t=3 skips the head cell)");
        assertFalse(cap.edits, "the admit is FREE — no break, no SET");
        assertEquals(FLAT, cap.cost, 1e-4f, "an ordinary flat walk cost — nothing surcharged");

        BlockPathPlan plan = BlockPathfinder.findPath(grid, new BlockPos(2, 0, Z), new BlockPos(12, 0, Z),
                WALK_TOGGLE);
        assertNotNull(plan, "the walk-only bot walks the whole plate hallway");
        assertEndsNear(plan, 12);
        assertNoEditsAtAll(plan);
    }

    // ================================================================================================
    // (b) A closed-TOP hatch flush in the HEAD cell over a full floor: uniform top band 13 ≥ 16−3 —
    //     the 1.8125 gap under it fits the 1.8 body. ADMITTED, free.
    // ================================================================================================

    @Test
    void closedTopPlateInHeadCellWalksUnder() {
        NavGridView grid = corridorWith(TD_X, 2, td(Blocks.OAK_TRAPDOOR, Direction.NORTH, false, Half.TOP));
        MovementContext mc = new MovementContext(grid, WALK_TOGGLE);

        Capture cap = new Capture(TD_X, 0, Z);
        new Traverse().candidates(mc, TD_X - 1, 0, Z, cap);
        assertTrue(cap.got, "walking under a flush closed-TOP hatch is ADMITTED (ceiling band 13 ≥ 13)");
        assertFalse(cap.edits, "no edit folded — the flush plate clears the body top");
        assertEquals(FLAT, cap.cost, 1e-4f);

        BlockPathPlan plan = BlockPathfinder.findPath(grid, new BlockPos(2, 0, Z), new BlockPos(12, 0, Z),
                WALK_TOGGLE);
        assertNotNull(plan, "full corridor crossing under the ceiling hatch");
        assertEndsNear(plan, 12);
        assertNoEditsAtAll(plan);
    }

    // ================================================================================================
    // (c) The 1.625 double-plate hallway (plate floor + closed-TOP plate in the LOWER body cell): the
    //     INTACT geometry bisects (§4) — pinned on IRON, where no toggle exists. The TOGGLEABLE flavor
    //     is the §5 trigger-(b) fold: perpendicular facing → ONE SET_OPEN admits (panel on the side
    //     wall, 1.8125 gap over the plate); ALONG-axis facing → still refused (panel across travel).
    // ================================================================================================

    @Test
    void doublePlateHallwayRefused() {
        NavGridView grid = plateHallwayWith(TD_X, 1, td(Blocks.IRON_TRAPDOOR, Direction.NORTH, false, Half.TOP));

        Capture cap = new Capture(TD_X, 0, Z);
        new Traverse().candidates(new MovementContext(grid, WALK_TOGGLE), TD_X - 1, 0, Z, cap);
        assertFalse(cap.got, "the 1.625 double-plate gap REFUSES a standing crossing (no crouch mode in v1)");

        BlockPathPlan plan = BlockPathfinder.findPath(grid, new BlockPos(2, 0, Z), new BlockPos(12, 0, Z),
                WALK_TOGGLE);
        assertNeverCrosses(plan, TD_X, "the 1.625 hallway is never crossed");
    }

    @Test
    void doublePlateHallwayToggleableOpensThrough() {
        // §5 trigger (b): oak, facing NORTH → the opened panel hugs the SOUTH wall, parallel to X travel.
        NavGridView grid = plateHallwayWith(TD_X, 1, td(Blocks.OAK_TRAPDOOR, Direction.NORTH, false, Half.TOP));
        MovementContext mc = new MovementContext(grid, WALK_TOGGLE);

        Capture cap = new Capture(TD_X, 0, Z);
        new Traverse().candidates(mc, TD_X - 1, 0, Z, cap);
        assertTrue(cap.got, "a toggleable bisecting plate folds SET_OPEN and admits (§5 trigger b)");
        assertTrue(cap.edits, "the crossing carries the SET_OPEN");
        assertEquals(TOGGLE_WALK, cap.cost, 1e-4f, "flat walk + exactly ONE toggle");

        BlockPathPlan plan = BlockPathfinder.findPath(grid, new BlockPos(2, 0, Z), new BlockPos(12, 0, Z),
                WALK_TOGGLE);
        assertNotNull(plan, "the walk-only bot opens the plate out of its way");
        assertEndsNear(plan, 12);
        assertExactlyOneSetOpenAt(plan, TD_X, 1);

        // Kill-switch: flag OFF → no fold, the plate is a wall again.
        Capture off = new Capture(TD_X, 0, Z);
        new Traverse().candidates(new MovementContext(grid, WALK_OFF), TD_X - 1, 0, Z, off);
        assertFalse(off.got, "doors.toggle OFF: trigger (b) never fires");
    }

    @Test
    void doublePlateHallwayAlongAxisFacingStillRefused() {
        // Adversarial §5 face-vs-travel: facing EAST → the opened panel would hug the WEST wall — ACROSS
        // the corridor axis. The toggle refuses; a walk-only bot has no other arm — no crossing.
        NavGridView grid = plateHallwayWith(TD_X, 1, td(Blocks.OAK_TRAPDOOR, Direction.EAST, false, Half.TOP));

        Capture cap = new Capture(TD_X, 0, Z);
        new Traverse().candidates(new MovementContext(grid, WALK_TOGGLE), TD_X - 1, 0, Z, cap);
        assertFalse(cap.got, "an along-axis facing opens ACROSS travel — trigger (b) refuses (§5)");

        BlockPathPlan plan = BlockPathfinder.findPath(grid, new BlockPos(2, 0, Z), new BlockPos(12, 0, Z),
                WALK_TOGGLE);
        assertNeverCrosses(plan, TD_X, "no toggle chain crosses the along-axis plate");
    }

    // ================================================================================================
    // (d) A closed-TOP plate in the LOWER body cell over a FULL floor (0.8125 of leg room): intact
    //     geometry REFUSED (iron pin); toggleable + perpendicular → ONE SET_OPEN admits (§5 trigger b).
    // ================================================================================================

    @Test
    void closedTopPlateInLowerBodyCellRefused() {
        NavGridView grid = corridorWith(TD_X, 1, td(Blocks.IRON_TRAPDOOR, Direction.NORTH, false, Half.TOP));

        Capture cap = new Capture(TD_X, 0, Z);
        new Traverse().candidates(new MovementContext(grid, WALK_TOGGLE), TD_X - 1, 0, Z, cap);
        assertFalse(cap.got, "a closed-TOP plate at chest height bisects the lower body cell — REFUSED");

        BlockPathPlan plan = BlockPathfinder.findPath(grid, new BlockPos(2, 0, Z), new BlockPos(12, 0, Z),
                WALK_TOGGLE);
        assertNeverCrosses(plan, TD_X, "the chest-height plate column is never crossed");
    }

    @Test
    void closedTopPlateInLowerBodyCellToggleableOpensThrough() {
        NavGridView grid = corridorWith(TD_X, 1, td(Blocks.OAK_TRAPDOOR, Direction.NORTH, false, Half.TOP));
        MovementContext mc = new MovementContext(grid, WALK_TOGGLE);

        Capture cap = new Capture(TD_X, 0, Z);
        new Traverse().candidates(mc, TD_X - 1, 0, Z, cap);
        assertTrue(cap.got, "the toggleable chest-height plate folds SET_OPEN and admits (§5 trigger b)");
        assertEquals(TOGGLE_WALK, cap.cost, 1e-4f, "flat walk + exactly ONE toggle");

        BlockPathPlan plan = BlockPathfinder.findPath(grid, new BlockPos(2, 0, Z), new BlockPos(12, 0, Z),
                WALK_TOGGLE);
        assertNotNull(plan, "the corridor is crossed by opening the plate");
        assertEndsNear(plan, 12);
        assertExactlyOneSetOpenAt(plan, TD_X, 1);
    }

    // ================================================================================================
    // (e) A closed-BOTTOM plate in the HEAD cell (plate at 2.0..2.1875, body top 2.8): intact geometry
    //     REFUSED (iron pin) — ceilingMinY(closed-bottom) = 0 and its DOWN blocked face is never a
    //     horizontal crossing's, so only §5 trigger (b) can rescue it: toggleable + perpendicular →
    //     ONE SET_OPEN admits; along-axis → refused.
    // ================================================================================================

    @Test
    void closedBottomPlateInHeadCellRefused() {
        NavGridView grid = corridorWith(TD_X, 2, td(Blocks.IRON_TRAPDOOR, Direction.NORTH, false, Half.BOTTOM));

        Capture cap = new Capture(TD_X, 0, Z);
        new Traverse().candidates(new MovementContext(grid, WALK_TOGGLE), TD_X - 1, 0, Z, cap);
        assertFalse(cap.got, "a closed-BOTTOM plate across the head space bisects the body — REFUSED");

        BlockPathPlan plan = BlockPathfinder.findPath(grid, new BlockPos(2, 0, Z), new BlockPos(12, 0, Z),
                WALK_TOGGLE);
        assertNeverCrosses(plan, TD_X, "the head-bisection column is never crossed");
    }

    @Test
    void closedBottomPlateInHeadCellToggleableOpensThrough() {
        NavGridView grid = corridorWith(TD_X, 2, td(Blocks.OAK_TRAPDOOR, Direction.NORTH, false, Half.BOTTOM));
        MovementContext mc = new MovementContext(grid, WALK_TOGGLE);

        Capture cap = new Capture(TD_X, 0, Z);
        new Traverse().candidates(mc, TD_X - 1, 0, Z, cap);
        assertTrue(cap.got, "the toggleable head plate folds SET_OPEN and admits (§5 trigger b)");
        assertEquals(TOGGLE_WALK, cap.cost, 1e-4f, "flat walk + exactly ONE toggle");

        BlockPathPlan plan = BlockPathfinder.findPath(grid, new BlockPos(2, 0, Z), new BlockPos(12, 0, Z),
                WALK_TOGGLE);
        assertNotNull(plan, "the corridor is crossed by opening the head plate");
        assertEndsNear(plan, 12);
        assertExactlyOneSetOpenAt(plan, TD_X, 2);
    }

    // ================================================================================================
    // (f) GENERALIZED rule: a bottom-slab floor (t=8) under a TOP-SLAB ceiling (band 8 ≥ 8−3): the
    //     2.0-block gap WALKS — no trapdoor involved, the same §4 top-band arithmetic.
    // ================================================================================================

    @Test
    void slabFloorUnderTopSlabCeilingWalks() {
        NavGridView grid = slabHallway();
        MovementContext mc = new MovementContext(grid, WALK_TOGGLE);

        Capture cap = new Capture(TD_X, 0, Z);
        new Traverse().candidates(mc, TD_X - 1, 0, Z, cap);
        assertTrue(cap.got, "slab floor under a top-slab ceiling is ADMITTED (band 8 ≥ 5 — 2.0 ≥ 1.8 real gap)");
        assertFalse(cap.edits, "free admit — no break folded");

        BlockPathPlan plan = BlockPathfinder.findPath(grid, new BlockPos(2, 0, Z), new BlockPos(12, 0, Z),
                WALK_TOGGLE);
        assertNotNull(plan, "the walk-only bot walks the slab-under-top-slab hallway");
        assertEndsNear(plan, 12);
        assertNoEditsAtAll(plan);
    }

    // ================================================================================================
    // (g) A FULL floor under the same top-slab ceiling (1.5 gap): REFUSED — band 8 < 16−3.
    // ================================================================================================

    @Test
    void fullFloorUnderTopSlabCeilingRefused() {
        NavGridView grid = corridorWith(TD_X, 2,
                Blocks.SMOOTH_STONE_SLAB.defaultBlockState().setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP));

        Capture cap = new Capture(TD_X, 0, Z);
        new Traverse().candidates(new MovementContext(grid, WALK_TOGGLE), TD_X - 1, 0, Z, cap);
        assertFalse(cap.got, "a top slab over a full floor leaves 1.5 blocks — REFUSED (8 < 13)");

        BlockPathPlan plan = BlockPathfinder.findPath(grid, new BlockPos(2, 0, Z), new BlockPos(12, 0, Z),
                WALK_TOGGLE);
        assertNeverCrosses(plan, TD_X, "the top-slab column is never crossed on a full floor");
    }

    // ================================================================================================
    // (h) An OPEN panel PARALLEL to travel (facing N → panel hugs the S wall, blocked face S; travel E
    //     crosses W): the cell is body-passable — crossed FREE, no edit folded, and the mid-panel node's
    //     exit east is EXIT_CLEAR.
    // ================================================================================================

    @Test
    void openPanelParallelToTravelCrossesFree() {
        NavGridView grid = corridorWith(TD_X, 1, td(Blocks.OAK_TRAPDOOR, Direction.NORTH, true, Half.BOTTOM));
        MovementContext mc = new MovementContext(grid, WALK_TOGGLE);

        Capture cap = new Capture(TD_X, 0, Z);
        new Traverse().candidates(mc, TD_X - 1, 0, Z, cap);
        assertTrue(cap.got, "an open panel parallel to travel is body-passable — the crossing is ADMITTED");
        assertFalse(cap.edits, "crossed FREE — no SET, no break");
        assertEquals(FLAT, cap.cost, 1e-4f, "no toggle cost on a parallel panel");

        BlockPathPlan plan = BlockPathfinder.findPath(grid, new BlockPos(2, 0, Z), new BlockPos(12, 0, Z),
                WALK_TOGGLE);
        assertNotNull(plan, "the corridor with the parallel panel is walked end to end");
        assertEndsNear(plan, 12);
        assertNoEditsAtAll(plan); // entry free AND the mid-panel node's east exit is EXIT_CLEAR
    }

    // ================================================================================================
    // (i) Crossing an open panel's BLOCKED face (facing E → panel hugs the W wall, exactly the entry
    //     edge of eastward travel; TOP half, in the head cell): folds exactly ONE SET_CLOSED at
    //     DOOR_TOGGLE_COST — the closed-TOP result is then admitted by the ceiling band. Flag off ⇒
    //     no fold, no crossing.
    // ================================================================================================

    @Test
    void crossingOpenPanelBlockedFaceFoldsOneSetClosed() {
        NavGridView grid = corridorWith(TD_X, 2, td(Blocks.OAK_TRAPDOOR, Direction.EAST, true, Half.TOP));
        MovementContext mc = new MovementContext(grid, WALK_TOGGLE);

        Capture cap = new Capture(TD_X, 0, Z);
        new Traverse().candidates(mc, TD_X - 1, 0, Z, cap);
        assertTrue(cap.got, "crossing the panel's blocked face is offered — via the SET fold");
        assertTrue(cap.edits, "the crossing folds an edit (the trapdoor SET_CLOSED)");
        assertEquals(TOGGLE_WALK, cap.cost, 1e-4f,
                "cost == flat walk + exactly ONE toggle — the single-cell trapdoor never dedups as a door half");

        BlockPathPlan plan = BlockPathfinder.findPath(grid, new BlockPos(2, 0, Z), new BlockPos(12, 0, Z),
                WALK_TOGGLE);
        assertNotNull(plan, "the walk-only bot closes the overhead panel to pass");
        assertEndsNear(plan, 12);
        int setClosed = 0, setOpen = 0, breaks = 0;
        for (int i = 0; i < plan.size(); i++) {
            StepEdits e = plan.edits(i);
            if (e == null) continue;
            breaks += e.breakCount();
            for (int j = 0; j < e.doorSetCount(); j++) {
                assertEquals(BlockPos.asLong(TD_X, 2, Z), e.doorSetAt(j), "the only SET is on the panel cell");
                if (e.doorSetOpenAt(j)) setOpen++;
                else setClosed++;
            }
        }
        assertEquals(1, setClosed, "exactly ONE SET_CLOSED is folded on the panel cell");
        assertEquals(0, setOpen, "no spurious SET_OPEN");
        assertEquals(0, breaks, "a walk-only bot breaks nothing");

        // doors.toggle OFF: the fold vanishes and the panel is a wall (byte-identity kill-switch).
        Capture off = new Capture(TD_X, 0, Z);
        new Traverse().candidates(new MovementContext(grid, WALK_OFF), TD_X - 1, 0, Z, off);
        assertFalse(off.got, "flag OFF: no SET is offered — the blocked panel face is a wall to a walker");
    }

    // ---- scene builders (the DoorToggleTest single-section idiom) -----------------------------------

    private static BlockState td(Block block, Direction facing, boolean open, Half half) {
        return block.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, facing)
                .setValue(BlockStateProperties.OPEN, open)
                .setValue(BlockStateProperties.HALF, half)
                .setValue(BlockStateProperties.WATERLOGGED, false);
    }

    /** Stone-filled section with a 1-wide corridor at z=Z, x=2..12: stone floor y=0, air body y=1..2
     *  (stone ceiling y=3), one cell {@code (x,y,Z)} overridden to {@code state}. */
    private static NavGridView corridorWith(int x, int y, BlockState state) {
        BlockState air = Blocks.AIR.defaultBlockState();
        PalettedContainer<BlockState> s = filledStone(air);
        for (int cx = 2; cx <= 12; cx++) { s.set(cx, 1, Z, air); s.set(cx, 2, Z, air); }
        s.set(x, y, Z, state);
        return single(s);
    }

    /** The plate hallway: closed-BOTTOM trapdoor floor row y=0 (x=2..12), air body ONLY at y=1 — the
     *  stone at y=2 is a hard ceiling 1.8125 above the plates. Unwalkable for a t=16 floor; the §4
     *  exact-fit rule is what admits it. */
    private static NavGridView plateHallway() {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState plate = td(Blocks.OAK_TRAPDOOR, Direction.NORTH, false, Half.BOTTOM);
        PalettedContainer<BlockState> s = filledStone(air);
        for (int cx = 2; cx <= 12; cx++) { s.set(cx, 0, Z, plate); s.set(cx, 1, Z, air); }
        return single(s);
    }

    /** {@link #plateHallway()} with one extra cell overridden (the double-plate case). */
    private static NavGridView plateHallwayWith(int x, int y, BlockState state) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState plate = td(Blocks.OAK_TRAPDOOR, Direction.NORTH, false, Half.BOTTOM);
        PalettedContainer<BlockState> s = filledStone(air);
        for (int cx = 2; cx <= 12; cx++) { s.set(cx, 0, Z, plate); s.set(cx, 1, Z, air); }
        s.set(x, y, Z, state);
        return single(s);
    }

    /** Bottom-slab floor row y=0, air y=1, TOP-slab ceiling row y=2 — the generalized §4 case (f). */
    private static NavGridView slabHallway() {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState bottom = Blocks.SMOOTH_STONE_SLAB.defaultBlockState()
                .setValue(BlockStateProperties.SLAB_TYPE, SlabType.BOTTOM);
        BlockState top = Blocks.SMOOTH_STONE_SLAB.defaultBlockState()
                .setValue(BlockStateProperties.SLAB_TYPE, SlabType.TOP);
        PalettedContainer<BlockState> s = filledStone(air);
        for (int cx = 2; cx <= 12; cx++) {
            s.set(cx, 0, Z, bottom);
            s.set(cx, 1, Z, air);
            s.set(cx, 2, Z, top);
        }
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

    /** The plan crossed the corridor with ZERO edits of any kind — the free-admit proof. */
    private static void assertNoEditsAtAll(BlockPathPlan plan) {
        for (int i = 0; i < plan.size(); i++) {
            StepEdits e = plan.edits(i);
            if (e == null) continue;
            assertEquals(0, e.breakCount(), "no breaks folded");
            assertEquals(0, e.placeCount(), "no places folded");
            assertEquals(0, e.doorSetCount(), "no SETs folded");
        }
    }

    /** The plan folds exactly ONE {@code SET_OPEN}, on cell {@code (x, y, Z)}, and nothing else — the §5
     *  trigger-(b) proof (one toggle, no breaks, no spurious SET_CLOSED). */
    private static void assertExactlyOneSetOpenAt(BlockPathPlan plan, int x, int y) {
        int setOpen = 0, setClosed = 0, breaks = 0;
        for (int i = 0; i < plan.size(); i++) {
            StepEdits e = plan.edits(i);
            if (e == null) continue;
            breaks += e.breakCount();
            for (int j = 0; j < e.doorSetCount(); j++) {
                assertEquals(BlockPos.asLong(x, y, Z), e.doorSetAt(j), "the only SET is on the plate cell");
                if (e.doorSetOpenAt(j)) setOpen++;
                else setClosed++;
            }
        }
        assertEquals(1, setOpen, "exactly ONE SET_OPEN folded (§5 trigger b)");
        assertEquals(0, setClosed, "no spurious SET_CLOSED");
        assertEquals(0, breaks, "a walk-only bot breaks nothing");
    }

    /** No waypoint ever reaches the barrier column {@code barX} or beyond — standing AT it would BE the
     *  refused occupancy (partial plans allowed — PARTIAL_PATH). */
    private static void assertNeverCrosses(BlockPathPlan plan, int barX, String msg) {
        if (plan == null) return; // no route at all — the strongest refusal
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
