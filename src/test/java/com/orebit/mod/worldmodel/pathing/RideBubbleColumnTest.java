package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.orebit.mod.Debug;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.CandidateSink;
import com.orebit.mod.pathfinding.blockpathfinder.EditScratch;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.pathfinding.blockpathfinder.MovementRegistry;
import com.orebit.mod.pathfinding.blockpathfinder.RegionBound;
import com.orebit.mod.pathfinding.blockpathfinder.movements.RideBubbleColumn;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

/**
 * Headless proof of {@code RideBubbleColumn} — the UP bubble-column conveyor ride (owner-ratified UP-only
 * scope). The synthetic {@link NavGridView} test harness does NOT simulate bubble-column PHYSICS, so the
 * FOLLOWER (the ENTER/RIDE/SETTLE plan) is verified in-game (owner's domain), exactly like the other swim
 * followers; this suite pins the PATHFINDING side — the candidate geometry + height-scaled cost:
 *
 * <ol>
 *   <li><b>Candidate-level</b> (the precise arm): construct a {@link MovementContext} over a synthetic
 *       air+column volume, pin {@link MovementContext#MODE_STANDING}, and call {@code
 *       RideBubbleColumn.candidates(...)} directly with a capturing {@link CandidateSink}. Asserts the exact
 *       emitted exit node, the exact {@code RIDE_BASE + height·RIDE_PER_BLOCK} cost, the ride terminating at
 *       the column TOP (not mid-column), a surface vs mid-water termination each producing the right exit,
 *       that the exit is a NORMAL (non-bubble) cell, and that a DOWN column / a non-standing node produce
 *       nothing.</li>
 *   <li><b>findPath-level</b> (integration): a sheer-walled scene where the only route up is the column — the
 *       real search must select {@code RideBubbleColumn} and land on the top exit.</li>
 * </ol>
 *
 * <p>Lives in this package to reach {@link NavGridView}'s package-private synthetic constructor (the
 * {@link DiagonalSprintSwimTest}/{@link StatefulSwimTest} precedent).
 */
class RideBubbleColumnTest {

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

    // The START node: a standing bot on a stone pedestal at (2,4,8) → feet layer y=5. The column sits one
    // cardinal step away at (3,·,8); its bubble cells span the feet layers 5..10 (rides 5 blocks to top=10).
    private static final int SX = 2, SY = 4, SZ = 8;   // the STANDING start node the move expands
    private static final int CX = 3, CZ = 8;           // the column horizontal (a +X neighbour of the start)
    private static final int BASE = SY + 1;            // column base feet layer (=5)
    private static final int TOP = 10;                 // top bubble feet layer
    private static final float EXPECTED_COST =
            RideBubbleColumn.RIDE_BASE + (TOP - BASE) * RideBubbleColumn.RIDE_PER_BLOCK; // 4 + 5·1.428 ≈ 11.14

    // ==================================================================== candidate-level (precise)

    @Test
    void ridesToTopBankExitAtSurface() {
        // Surface-reaching column: air directly above the top bubble. A stone bank flush with the column top at
        // (4,10,8) with two clear body cells → the classic soul-sand elevator: ride up, step onto the shore.
        NavGridView grid = build(s -> {
            upColumn(s, CX, CZ, BASE, TOP);               // bubble cells 5..10; above (3,11,8) stays air
            s.set(4, TOP, 8, Blocks.STONE.defaultBlockState()); // bank floor, body (4,11,8)/(4,12,8) air
        });
        Capture cap = expand(grid, MovementContext.MODE_STANDING);

        assertEquals(1, cap.cells.size(), "exactly one exit (the bank flush with the column top)");
        assertTrue(cap.has(4, TOP, 8), "exit is the top bank node (4,10,8) — ride terminates at the TOP, not mid-column");
        assertEquals(EXPECTED_COST, cap.costOf(4, TOP, 8), 1e-3f,
                "cost is RIDE_BASE + height·RIDE_PER_BLOCK for a 5-block rise");
    }

    @Test
    void ridesToTopFloatOutWhenColumnTerminatesMidWater() {
        // Mid-water termination: plain (non-bubble) water directly above the top bubble. A lateral neighbour
        // whose feet cell is swimmable water → float up off the last bubble and swim out sideways at topY+1.
        NavGridView grid = build(s -> {
            upColumn(s, CX, CZ, BASE, TOP);
            s.set(CX, TOP + 1, CZ, Blocks.WATER.defaultBlockState());  // more water above → mid-water termination
            s.set(CX, TOP + 2, CZ, Blocks.WATER.defaultBlockState());
            s.set(4, TOP, 8, Blocks.STONE.defaultBlockState());        // an underwater ledge below the exit feet
            s.set(4, TOP + 1, 8, Blocks.WATER.defaultBlockState());    // the float-out feet cell (swimmable)
        });
        Capture cap = expand(grid, MovementContext.MODE_STANDING);

        assertTrue(cap.has(4, TOP, 8), "float-out exit node (4,10,8) at feet layer topY+1");
        assertEquals(EXPECTED_COST, cap.costOf(4, TOP, 8), 1e-3f, "same height-scaled cost");
        // The exit is a NORMAL navigable cell: its feet cell is non-bubble swimmable water, never a bubble cell.
        MovementContext ctx = new MovementContext(grid, BotCaps.DEFAULT);
        assertTrue(ctx.water(4, TOP + 1, 8), "exit feet is swimmable (non-bubble) water");
        assertFalse(ctx.bubbleUp(4, TOP + 1, 8), "exit cell must not be a bubble cell");
    }

    @Test
    void surfaceFloatOutIntoSurroundingPoolAtTopLevel() {
        // Surface-reaching column surrounded by surface water (no bank): a same-Y lateral swim-out into the pool
        // at feet layer topY (one below the bank level) — node (ex, topY-1, ez).
        NavGridView grid = build(s -> {
            upColumn(s, CX, CZ, BASE, TOP);                            // above (3,11,8) air → surface reached
            s.set(4, TOP, 8, Blocks.WATER.defaultBlockState());        // surrounding surface water at the top level
            // (4,11,8) left air → surface (feet water, head air)
        });
        Capture cap = expand(grid, MovementContext.MODE_STANDING);

        assertTrue(cap.has(4, TOP - 1, 8),
                "surface float-out exits at the top water level: node (4,9,8) (feet layer topY)");
        assertEquals(EXPECTED_COST, cap.costOf(4, TOP - 1, 8), 1e-3f, "same height-scaled cost");
    }

    @Test
    void aTallerColumnRidesAllTheWayToItsTop() {
        // Termination at the TOP is by the CONTIGUOUS bubble scan, independent of height: an 8-block column
        // (base..13) exits at node (4,13,8), and the cost tracks the taller rise.
        int top = 13;
        NavGridView grid = build(s -> {
            upColumn(s, CX, CZ, BASE, top);
            s.set(4, top, 8, Blocks.STONE.defaultBlockState());
        });
        Capture cap = expand(grid, MovementContext.MODE_STANDING);

        assertTrue(cap.has(4, top, 8), "an 8-block column rides to its own top (4,13,8)");
        assertEquals(RideBubbleColumn.RIDE_BASE + (top - BASE) * RideBubbleColumn.RIDE_PER_BLOCK,
                cap.costOf(4, top, 8), 1e-3f, "cost scales with the taller rise");
    }

    @Test
    void aDownColumnProducesNoRide() {
        // UP-only: a DOWN column (magma, DRAG_DOWN=true) is not enterable by this move even with a valid bank.
        NavGridView grid = build(s -> {
            downColumn(s, CX, CZ, BASE, TOP);
            s.set(4, TOP, 8, Blocks.STONE.defaultBlockState());
        });
        Capture cap = expand(grid, MovementContext.MODE_STANDING);

        assertEquals(0, cap.cells.size(), "a DOWN column yields no ride candidate (UP-only scope)");
    }

    @Test
    void emitsNothingFromAProneNode() {
        // Self-gate on MODE_STANDING: a prone/submerged node offers no ride (v1 UP-only entry is standing/tread).
        NavGridView grid = build(s -> {
            upColumn(s, CX, CZ, BASE, TOP);
            s.set(4, TOP, 8, Blocks.STONE.defaultBlockState());
        });
        Capture cap = expand(grid, MovementContext.MODE_PRONE);

        assertEquals(0, cap.cells.size(), "RideBubbleColumn must emit nothing from a PRONE node");
    }

    @Test
    void bankExitIsANormalStandableNonBubbleCell() {
        NavGridView grid = build(s -> {
            upColumn(s, CX, CZ, BASE, TOP);
            s.set(4, TOP, 8, Blocks.STONE.defaultBlockState());
        });
        MovementContext ctx = new MovementContext(grid, BotCaps.DEFAULT);
        assertTrue(ctx.standable(4, TOP, 8), "the exit floor is a normal standable cell");
        assertFalse(ctx.bubbleUp(4, TOP, 8), "the exit floor is not a bubble cell");
    }

    // ==================================================================== findPath-level (integration)

    private static final RegionBound CORRIDOR = new RegionBound(0, 15, 0, 15, 0, 15);

    @Test
    void searchRidesTheColumnWhenItIsTheOnlyRouteUp() {
        // A sheer scene: a stone pedestal start, the up-column, and a bank flush with its top — nothing else to
        // stand on, so the ONLY way to the raised goal is riding the column. The real two-tier search must pick
        // RideBubbleColumn and land on the top exit.
        NavGridView grid = build(s -> {
            upColumn(s, CX, CZ, BASE, TOP);
            s.set(4, TOP, 8, Blocks.STONE.defaultBlockState());
        });
        BlockPos start = new BlockPos(SX, SY, SZ);        // standing pedestal (feet air → MODE_STANDING seed)
        BlockPos goal = new BlockPos(4, TOP, 8);          // the top bank exit
        BlockPathPlan plan = BlockPathfinder.findPath(grid, start, goal, BotCaps.DEFAULT, CORRIDOR);

        assertNotNull(plan, "the bot should reach the raised goal by riding the up-column");
        assertTrue(contains(plan, MovementRegistry.RIDE_BUBBLE_COLUMN),
                "the sheer column is the only way up — the search must ride it");
        // Waypoints are FEET positions (floorCell.above()), so the top bank exit node (4,10,8) lands the bot's
        // feet at (4,11,8) — standing on the bank flush with the column top.
        assertEquals(new BlockPos(4, TOP + 1, 8), plan.waypoint(plan.size() - 1),
                "the ride lands the bot's feet on the top bank exit (feet = floor.above())");
    }

    // ---------------------------------------------------------------- helpers

    /** Expand {@code RideBubbleColumn} at the start node in {@code mode}, capturing its candidates. */
    private static Capture expand(NavGridView grid, int mode) {
        MovementContext ctx = new MovementContext(grid, BotCaps.DEFAULT);
        ctx.setMode(mode);
        Capture cap = new Capture();
        MovementRegistry.RIDE_BUBBLE_COLUMN.candidates(ctx, SX, SY, SZ, cap);
        return cap;
    }

    /** A stack of UP bubble-column cells (soul-sand column, {@code DRAG_DOWN=false}) at (x,·,z), feet layers
     *  {@code loY..hiY} inclusive. */
    private static void upColumn(PalettedContainer<BlockState> s, int x, int z, int loY, int hiY) {
        BlockState up = Blocks.BUBBLE_COLUMN.defaultBlockState()
                .setValue(BubbleColumnBlock.DRAG_DOWN, false);
        for (int y = loY; y <= hiY; y++) s.set(x, y, z, up);
    }

    /** A stack of DOWN bubble-column cells (magma column, {@code DRAG_DOWN=true}) at (x,·,z), {@code loY..hiY}. */
    private static void downColumn(PalettedContainer<BlockState> s, int x, int z, int loY, int hiY) {
        BlockState down = Blocks.BUBBLE_COLUMN.defaultBlockState()
                .setValue(BubbleColumnBlock.DRAG_DOWN, true);
        for (int y = loY; y <= hiY; y++) s.set(x, y, z, down);
    }

    /**
     * One classified section (chunk 0,0), AIR everywhere except a stone pedestal under the start node — so the
     * start is a valid STANDING floor — plus whatever the {@code build} tweak places (the column + exit). Air
     * background keeps the exit-clearance checks clean (no incidental stone blocking body cells).
     */
    private static NavGridView build(Consumer<PalettedContainer<BlockState>> features) {
        BlockState air = Blocks.AIR.defaultBlockState();

        PalettedContainer<BlockState> s = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        s.set(SX, SY, SZ, Blocks.STONE.defaultBlockState());  // the start pedestal (feet + head above are air)
        features.accept(s);

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

    private static boolean contains(BlockPathPlan plan, Object move) {
        for (int i = 0; i < plan.size(); i++) if (plan.movement(i) == move) return true;
        return false;
    }

    /** A capturing {@link CandidateSink}: records every accepted (cell, cost) so a test can assert the set. */
    private static final class Capture implements CandidateSink {
        final List<int[]> cells = new ArrayList<>();
        final List<Float> costs = new ArrayList<>();

        @Override
        public void accept(int x, int y, int z, float cost, EditScratch edits) {
            cells.add(new int[] { x, y, z });
            costs.add(cost);
        }

        boolean has(int x, int y, int z) {
            for (int[] c : cells) if (c[0] == x && c[1] == y && c[2] == z) return true;
            return false;
        }

        float costOf(int x, int y, int z) {
            for (int i = 0; i < cells.size(); i++) {
                int[] c = cells.get(i);
                if (c[0] == x && c[1] == y && c[2] == z) return costs.get(i);
            }
            return Float.NaN;
        }
    }
}
