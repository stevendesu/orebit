package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.orebit.mod.Debug;
import com.orebit.mod.config.ProtectedBlocks;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.CandidateSink;
import com.orebit.mod.pathfinding.blockpathfinder.EditScratch;
import com.orebit.mod.pathfinding.blockpathfinder.MiningModel;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.pathfinding.blockpathfinder.MovementRegistry;
import com.orebit.mod.pathfinding.blockpathfinder.RegionBound;
import com.orebit.mod.pathfinding.blockpathfinder.StepEdits;
import com.orebit.mod.pathfinding.blockpathfinder.movements.Ascend;
import com.orebit.mod.pathfinding.blockpathfinder.movements.EndSprintSwim;
import com.orebit.mod.pathfinding.blockpathfinder.movements.Parkour;
import com.orebit.mod.pathfinding.blockpathfinder.movements.Surface;
import com.orebit.mod.worldmodel.navblock.NavBlock;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

/**
 * FENCE GATES — the §3 toggle-for-passage fold at the planner level (DESIGN-fence-gates.md §3/§7), the
 * {@link TrapdoorToggleTest} shapes recut for the degenerate openable (face-agnostic, no residual
 * geometry, no exit machinery):
 * <ul>
 *   <li><b>Closed-gate corridor</b> — a walled 2-tall corridor whose feet row holds one closed gate: the
 *       full search folds exactly ONE {@code SET_OPEN} on the gate cell and breaks/places nothing; a
 *       breaker PREFERS the toggle over its available break; {@code doors.toggle=false} makes the gate a
 *       wall to a walker.</li>
 *   <li><b>Already-open gate</b> — the same corridor crosses with ZERO edits of any kind, toggle flag on
 *       or off (an open gate is {@code Shapes.empty()} passable air — no capability consulted).</li>
 *   <li><b>Default-protected + toggle off</b> — the production default list protects
 *       {@code #minecraft:fence_gates}, so with the flag off a closed gate is a HARD WALL (no break
 *       fallback, no SET arm) even to a breaker; with the flag ON the toggle bypasses PROTECTED — the
 *       only way through a closed gate under default config (§3).</li>
 *   <li><b>Ascend-onto-gate refusal</b> — a step-up whose dest floor is a closed gate is never offered
 *       (not standable: topY 24 + NARROW_TOP; and rise(1, 24, 16) = 24 &gt; {@code JUMP_RISE} 20 walls it
 *       independently), walker and placer alike (the gate cell is occupied — no footing place).</li>
 *   <li><b>Parkour-over-gate refusal</b> — a flat gap whose intermediate column holds a closed gate ends
 *       the scan at the {@code overJumpable} net (topY 24 pokes into the feet path); the OPEN gate reads
 *       as an open gap column and the same arc lands.</li>
 *   <li><b>Water exit (§3 swim row)</b> — {@link Surface}'s bank-exit body predicate and
 *       {@link EndSprintSwim}'s pose-fit head predicate admit an OPEN gate cell and refuse the CLOSED
 *       one, with zero gate-specific code; the full water-to-land search crosses the open-gate bank free
 *       and never reaches a closed-gate bank.</li>
 * </ul>
 * Lives in this package for {@link NavGridView}'s package-private synthetic constructor (the
 * {@link DoorToggleTest} scene idiom).
 */
class GateToggleTest {

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
        // Bake the REAL mining-tick table (the TrapdoorToggleTest idiom): the breaker scenes assert the
        // toggle is PREFERRED over mining, which is only meaningful when a break costs its real ticks.
        MiningModel.buildTable(true, 0);
    }

    private static BotCaps caps(boolean canBreak, boolean canPlace, boolean toggle) {
        return new BotCaps(1, BotCaps.DEFAULT_SAFE_FALL, BotCaps.DEFAULT_MAX_FALL, false,
                BotCaps.DEFAULT_COST_PER_HITPOINT, canBreak, canPlace,
                100, false, BotCaps.DEFAULT_MAX_NODES, 1.0f, toggle);
    }

    /** Walk-only + doors.toggle ON: a crossing proves a free admit or a SET fold — never a break/place. */
    private static final BotCaps WALK_TOGGLE = caps(false, false, true);
    /** Walk-only + doors.toggle OFF: the flag kill-switch regression control. */
    private static final BotCaps WALK_OFF = caps(false, false, false);
    /** A breaker with the flag ON: proves the search PREFERS the cheap toggle over an available break. */
    private static final BotCaps BREAKER_TOGGLE = caps(true, false, true);
    /** A breaker with the flag OFF: under default protection the gate must refuse its break too. */
    private static final BotCaps BREAKER_OFF = caps(true, false, false);
    /** A placer with the flag ON: the Ascend build-a-step arm must not conjure footing in a gate cell. */
    private static final BotCaps PLACER_TOGGLE = caps(false, true, true);

    /** The gate column in the corridor scenes: gate at (GX, 1, Z), corridor along X at z = Z. */
    private static final int GX = 7, Z = 6;

    // ================================================================================================
    // (1) Closed-gate corridor: ONE SET_OPEN on the gate cell, nothing broken or placed.
    // ================================================================================================

    @Test
    void closedGateCorridorFoldsExactlyOneSetOpen() {
        NavGridView grid = corridor2Tall(Blocks.STONE.defaultBlockState(),
                gate(Blocks.OAK_FENCE_GATE, false));
        BlockPathPlan plan = BlockPathfinder.findPath(grid, new BlockPos(2, 0, Z),
                new BlockPos(12, 0, Z), WALK_TOGGLE);

        assertNotNull(plan, "a walk-only toggle bot crosses the closed gate — by opening it");
        BlockPos last = plan.waypoint(plan.size() - 1);
        assertTrue(Math.abs(last.getX() - 12) <= 1 && last.getZ() == Z, "ends at the goal; ended " + last);
        // Plan waypoints are FEET cells: crossing the corridor puts the feet IN the opened gate's own cell.
        assertTrue(contains(plan, GX, 1, Z), "the path walks its feet through the opened gate cell");
        assertGateSets(plan, 1, 0);
    }

    @Test
    void breakerPrefersTheToggleOverBreakingTheGate() {
        // Unprotected gate, real mining table: the break is AVAILABLE (wood hardness ≤ cap) and the
        // search must still fold the SET (toggle 6 ≪ mining ticks).
        NavGridView grid = corridor2Tall(Blocks.STONE.defaultBlockState(),
                gate(Blocks.OAK_FENCE_GATE, false));
        BlockPathPlan plan = BlockPathfinder.findPath(grid, new BlockPos(2, 0, Z),
                new BlockPos(12, 0, Z), BREAKER_TOGGLE);

        assertNotNull(plan, "the breaker+toggle bot crosses the closed gate");
        assertGateSets(plan, 1, 0);
    }

    @Test
    void toggleOffMakesTheClosedGateAWallToAWalker() {
        NavGridView grid = corridor2Tall(Blocks.STONE.defaultBlockState(),
                gate(Blocks.OAK_FENCE_GATE, false));
        BlockPathPlan plan = BlockPathfinder.findPath(grid, new BlockPos(2, 0, Z),
                new BlockPos(12, 0, Z), WALK_OFF);
        assertNeverCrosses(plan, GX, "flag OFF: a closed gate is a hard wall to a walk-only bot");
    }

    // ================================================================================================
    // (2) Already-open gate: passable air — crossed FREE, zero SETs, no capability consulted.
    // ================================================================================================

    @Test
    void openGateCorridorCrossesFreeWithZeroSets() {
        NavGridView grid = corridor2Tall(Blocks.STONE.defaultBlockState(),
                gate(Blocks.OAK_FENCE_GATE, true));

        BlockPathPlan plan = BlockPathfinder.findPath(grid, new BlockPos(2, 0, Z),
                new BlockPos(12, 0, Z), WALK_TOGGLE);
        assertNotNull(plan, "an open gate is air — the corridor is simply walked");
        BlockPos last = plan.waypoint(plan.size() - 1);
        assertTrue(Math.abs(last.getX() - 12) <= 1 && last.getZ() == Z, "ends at the goal; ended " + last);
        assertTrue(contains(plan, GX, 1, Z), "the path walks its feet through the open-gate cell");
        assertNoEditsAtAll(plan);

        // The open state needs NO capability: the toggle-off walker crosses identically.
        BlockPathPlan off = BlockPathfinder.findPath(grid, new BlockPos(2, 0, Z),
                new BlockPos(12, 0, Z), WALK_OFF);
        assertNotNull(off, "an open gate admits a toggle-off walker — passable air consults no cap");
        assertNoEditsAtAll(off);
    }

    // ================================================================================================
    // (3) Production default protection + doors.toggle=false: the closed gate is a HARD WALL — no break
    //     fallback (PROTECTED strips BREAKABLE), no SET arm (flag off). With the flag ON, the toggle
    //     BYPASSES protection — the only way through under default config (§3). The shell is bedrock so
    //     a cap-100 breaker cannot tunnel around: the gate's own break is the only break in question.
    // ================================================================================================

    @Test
    void defaultProtectedToggleOffGateIsAHardWall() {
        assertTrue(ProtectedBlocks.DEFAULT_SPEC.contains("#minecraft:fence_gates"),
                "the production default list protects fence gates by tag");
        // Headless Bootstrap binds no datapack tags (the ProtectedBlocksParseTest caveat), so the parsed
        // default's #fence_gates entry matches nothing here; on a live server that tag is exactly the
        // FenceGateBlock roster (all-wood, subclass-free — §1). The applied predicate is the parsed
        // production default with that one tag's live membership restored.
        ProtectedBlocks defaults = ProtectedBlocks.parse(ProtectedBlocks.DEFAULT_SPEC, msg -> {});
        BlockState closed = gate(Blocks.OAK_FENCE_GATE, false);
        short closedBefore = NavBlock.navtypeFor(closed);

        NavBlock.applyProtected(s -> defaults.matches(s) || s.getBlock() instanceof FenceGateBlock);
        try {
            NavGridView grid = corridor2Tall(Blocks.BEDROCK.defaultBlockState(), closed);

            BlockPathPlan brk = BlockPathfinder.findPath(grid, new BlockPos(2, 0, Z),
                    new BlockPos(12, 0, Z), BREAKER_OFF);
            assertNeverCrosses(brk, GX,
                    "flag OFF + default-protected: no SET arm and no break fallback — even a breaker is walled");
            assertNoSetsAnywhere(brk);

            BlockPathPlan walk = BlockPathfinder.findPath(grid, new BlockPos(2, 0, Z),
                    new BlockPos(12, 0, Z), WALK_OFF);
            assertNeverCrosses(walk, GX, "flag OFF + default-protected: walled to a walker too");

            // Flag ON: the SET fold bypasses PROTECTED (§3) — the corridor is crossed by ONE SET_OPEN
            // and the protected gate is never mined.
            BlockPathPlan toggled = BlockPathfinder.findPath(grid, new BlockPos(2, 0, Z),
                    new BlockPos(12, 0, Z), BREAKER_TOGGLE);
            assertNotNull(toggled, "the toggle is the only way through a default-protected closed gate");
            BlockPos last = toggled.waypoint(toggled.size() - 1);
            assertTrue(Math.abs(last.getX() - 12) <= 1 && last.getZ() == Z,
                    "ends at the goal; ended " + last);
            assertGateSets(toggled, 1, 0);
        } finally {
            NavBlock.applyProtected(s -> false);
        }
        assertEquals(closedBefore, NavBlock.navtypeFor(closed), "un-protect restores the base navtype");
    }

    // ================================================================================================
    // (4) Ascend-onto-gate refusal — candidate level: the +1 step's dest floor gates on standable
    //     (a closed gate is topY 24 + NARROW_TOP, never a floor) and the build arm cannot place into
    //     the occupied gate cell; only the gate block differs from the stone control.
    // ================================================================================================

    @Test
    void stepUpOntoAClosedGateIsNeverOffered() {
        NavGridView grid = corridor3With(GX + 1, 1, gate(Blocks.OAK_FENCE_GATE, false));

        Capture walk = new Capture(GX + 1, 1, Z);
        new Ascend().candidates(new MovementContext(grid, WALK_TOGGLE), GX, 0, Z, walk);
        assertFalse(walk.got, "a closed gate is never a step-up landing (topY 24, not standable)");

        Capture place = new Capture(GX + 1, 1, Z);
        new Ascend().candidates(new MovementContext(grid, PLACER_TOGGLE), GX, 0, Z, place);
        assertFalse(place.got, "the build-a-step arm cannot conjure footing in the occupied gate cell");

        NavGridView control = corridor3With(GX + 1, 1, Blocks.STONE.defaultBlockState());
        Capture stone = new Capture(GX + 1, 1, Z);
        new Ascend().candidates(new MovementContext(control, WALK_TOGGLE), GX, 0, Z, stone);
        assertTrue(stone.got, "the identical scene with a stone ledge emits the ordinary +1 jump");
    }

    // ================================================================================================
    // (5) Parkour-over-gate refusal — a closed gate in a gap column fails the overJumpable net
    //     (topY 24 > 16 pokes into the feet path) and ends the direction; the OPEN gate reads as an
    //     open gap column and the same flat 2-gap arc lands.
    // ================================================================================================

    private static final int TX = 4; // parkour takeoff floor cell (TX, 0, Z)

    @Test
    void parkourOverAClosedGateRefuses() {
        NavGridView grid = gapGrid(2, gate(Blocks.OAK_FENCE_GATE, false));
        Capture cap = new Capture(TX + 3, 0, Z);
        new Parkour().candidates(new MovementContext(grid, WALK_TOGGLE), TX, 0, Z, cap);
        assertFalse(cap.got, "a closed gate in the gap column blocks the arc (overJumpable: topY 24 > 16)");
    }

    @Test
    void parkourOverAnOpenGateLands() {
        NavGridView grid = gapGrid(2, gate(Blocks.OAK_FENCE_GATE, true));
        Capture cap = new Capture(TX + 3, 0, Z);
        new Parkour().candidates(new MovementContext(grid, WALK_TOGGLE), TX, 0, Z, cap);
        assertTrue(cap.got, "an open gate is an open gap column — the flat 2-gap arc lands beyond it");
    }

    // ================================================================================================
    // (6) Water exit — the §3 swim row: Surface's bank body cells and EndSprintSwim's head cell are
    //     plain passable tests, so an OPEN gate admits and a CLOSED gate refuses with zero gate code.
    //     The scene is StatefulSwimTest's maze with the bank's feet body cell holding the gate.
    // ================================================================================================

    private static final RegionBound POOL = new RegionBound(0, 15, 0, 15, 0, 15);
    private static final int WZ = 8;
    /** Bank goal two cells past the wall, as in {@code StatefulSwimTest} — reaching it requires standing. */
    private static final BlockPos BANK_GOAL = new BlockPos(8, 0, WZ);

    @Test
    void surfaceBankExitAdmitsOpenGateRefusesClosed() {
        MovementContext open = new MovementContext(swimMaze(gate(Blocks.OAK_FENCE_GATE, true)), WALK_TOGGLE);
        open.setMode(MovementContext.MODE_PRONE);
        Capture out = new Capture(GX, 0, WZ);
        new Surface().candidates(open, 6, 0, WZ, out);
        assertTrue(out.got, "the bank crawl-out admits an OPEN gate in the exit feet cell (passable air)");

        MovementContext shut = new MovementContext(swimMaze(gate(Blocks.OAK_FENCE_GATE, false)), WALK_TOGGLE);
        shut.setMode(MovementContext.MODE_PRONE);
        Capture blocked = new Capture(GX, 0, WZ);
        new Surface().candidates(shut, 6, 0, WZ, blocked);
        assertFalse(blocked.got, "a CLOSED gate in the exit feet cell refuses the crawl-out (no fold offered)");
    }

    @Test
    void endSprintSwimHeadCellAdmitsOpenGateRefusesClosed() {
        MovementContext open = new MovementContext(headGateScene(true), WALK_TOGGLE);
        open.setMode(MovementContext.MODE_PRONE);
        Capture up = new Capture(5, 0, WZ);
        new EndSprintSwim().candidates(open, 5, 0, WZ, up);
        assertTrue(up.got, "standing up under an OPEN gate head cell fits (passable, pose-fit gate)");

        MovementContext shut = new MovementContext(headGateScene(false), WALK_TOGGLE);
        shut.setMode(MovementContext.MODE_PRONE);
        Capture held = new Capture(5, 0, WZ);
        new EndSprintSwim().candidates(shut, 5, 0, WZ, held);
        assertFalse(held.got, "a CLOSED gate head cell fails the pose-fit gate — the bot stays prone");
    }

    @Test
    void waterToLandSearchCrossesTheOpenGateBankFree() {
        BlockPathPlan plan = BlockPathfinder.findPath(swimMaze(gate(Blocks.OAK_FENCE_GATE, true)),
                new BlockPos(2, 0, WZ), BANK_GOAL, WALK_TOGGLE, POOL);
        assertNotNull(plan, "the submerged bot threads the hole and Surfaces through the open-gate bank");
        assertTrue(containsMove(plan, MovementRegistry.SURFACE), "the bank exit is the Surface crawl-out");
        // The Surface waypoint is a FEET cell — the crawl-out stands its feet in the open-gate cell.
        assertTrue(contains(plan, GX, 1, WZ), "the bank exit puts the feet in the open-gate cell");
        assertNoEditsAtAll(plan);
    }

    @Test
    void waterToLandSearchNeverReachesTheClosedGateBank() {
        BlockPathPlan plan = BlockPathfinder.findPath(swimMaze(gate(Blocks.OAK_FENCE_GATE, false)),
                new BlockPos(2, 0, WZ), BANK_GOAL, WALK_TOGGLE, POOL);
        assertNeverCrosses(plan, GX,
                "no swim rung folds a gate SET from the water — the closed-gate bank is unreachable");
    }

    // ---- scene builders (the TrapdoorToggleTest / StatefulSwimTest single-section idiom) ------------

    private static BlockState gate(Block block, boolean open) {
        return block.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                .setValue(BlockStateProperties.OPEN, open)
                .setValue(BlockStateProperties.POWERED, false)
                .setValue(BlockStateProperties.IN_WALL, false);
    }

    /** A 1-wide corridor at z=Z with TWO body cells (air y=1..2 over a floor y=0, ceiling y=3), sealed in
     *  {@code shell}, with the gate state at the feet cell (GX, 1, Z). */
    private static NavGridView corridor2Tall(BlockState shell, BlockState gateState) {
        BlockState air = Blocks.AIR.defaultBlockState();
        PalettedContainer<BlockState> s = filled(shell);
        for (int cx = 2; cx <= 12; cx++) {
            s.set(cx, 1, Z, air);
            s.set(cx, 2, Z, air);
        }
        s.set(GX, 1, Z, gateState);
        return single(s);
    }

    /** A 1-wide stone corridor at z=Z with THREE body cells (air y=1..3), one cell overridden — the
     *  {@code TrapdoorToggleTest.corridor3With} shape (three-tall for the Ascend takeoff's y+3 headroom). */
    private static NavGridView corridor3With(int x, int y, BlockState state) {
        BlockState air = Blocks.AIR.defaultBlockState();
        PalettedContainer<BlockState> s = filled(Blocks.STONE.defaultBlockState());
        for (int cx = 2; cx <= 12; cx++) {
            s.set(cx, 1, Z, air);
            s.set(cx, 2, Z, air);
            s.set(cx, 3, Z, air);
        }
        s.set(x, y, Z, state);
        return single(s);
    }

    /** The parkour runway ({@code TrapdoorParkourTest.gapGrid}): stone takeoff at (TX,0,Z), {@code gap}
     *  bottomless gap columns, a stone landing beyond, air body y=1..3 — with the FIRST gap column's
     *  node-level cell (TX+1, 0, Z) holding the gate state. */
    private static NavGridView gapGrid(int gap, BlockState gateState) {
        BlockState air = Blocks.AIR.defaultBlockState();
        PalettedContainer<BlockState> s = filled(Blocks.STONE.defaultBlockState());
        for (int cx = 2; cx <= 12; cx++) {
            s.set(cx, 1, Z, air);
            s.set(cx, 2, Z, air);
            s.set(cx, 3, Z, air);
            s.set(cx, 4, Z, air); // the arc's apex head row (takeoff-feet+3; head-clearance fix 2026-08-17)
        }
        for (int cx = TX + 1; cx <= TX + gap; cx++) s.set(cx, 0, Z, air); // bottomless gap columns
        s.set(TX + 1, 0, Z, gateState);
        return single(s);
    }

    /**
     * {@code StatefulSwimTest.buildMaze(true)} with the bank's FEET BODY cell (7, 1, WZ) holding the gate:
     * a 2-deep initiation cell at x=2, a 1-deep channel x=3..5, the 1-tall hole at x=6 (water feet, stone
     * above), and the dry bank x=7..8 whose only entry body cell is the gate. Sealed stone otherwise.
     */
    private static NavGridView swimMaze(BlockState gateState) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState water = Blocks.WATER.defaultBlockState();
        PalettedContainer<BlockState> s = filled(Blocks.STONE.defaultBlockState());
        s.set(2, 1, WZ, water);
        s.set(2, 2, WZ, water);                       // 2-deep initiation cell (seeds PRONE)
        for (int x = 3; x <= 5; x++) {                // 1-deep channel run
            s.set(x, 1, WZ, water);
            s.set(x, 2, WZ, air);
        }
        s.set(6, 1, WZ, water);                       // the 1-TALL hole (stone above)
        s.set(GX, 1, WZ, gateState);                  // the bank's entry feet cell
        s.set(GX, 2, WZ, air);
        s.set(8, 1, WZ, air);
        s.set(8, 2, WZ, air);
        return single(s);
    }

    /** The EndSprintSwim pose-fit scene: 1-deep water feet at (5, 1, WZ) with the HEAD cell (5, 2, WZ)
     *  holding the gate state; sealed stone otherwise. */
    private static NavGridView headGateScene(boolean gateOpen) {
        PalettedContainer<BlockState> s = filled(Blocks.STONE.defaultBlockState());
        s.set(5, 1, WZ, Blocks.WATER.defaultBlockState());
        s.set(5, 2, WZ, gate(Blocks.OAK_FENCE_GATE, gateOpen));
        return single(s);
    }

    private static PalettedContainer<BlockState> filled(BlockState shell) {
        PalettedContainer<BlockState> s = new PalettedContainer<>(
                Blocks.AIR.defaultBlockState(), Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        for (int x = 0; x < 16; x++)
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    s.set(x, y, z, shell);
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

    private static boolean contains(BlockPathPlan plan, int x, int y, int z) {
        for (int i = 0; i < plan.size(); i++) {
            BlockPos wp = plan.waypoint(i);
            if (wp.getX() == x && wp.getY() == y && wp.getZ() == z) return true;
        }
        return false;
    }

    private static boolean containsMove(BlockPathPlan plan, Object move) {
        for (int i = 0; i < plan.size(); i++) {
            if (plan.movement(i) == move) return true;
        }
        return false;
    }

    /** Exactly {@code open} SET_OPENs and {@code closed} SET_CLOSEDs, every SET on the corridor gate cell
     *  (GX, 1, Z), and ZERO breaks/places anywhere in the plan. */
    private static void assertGateSets(BlockPathPlan plan, int open, int closed) {
        int setOpen = 0, setClosed = 0, breaks = 0, places = 0;
        for (int i = 0; i < plan.size(); i++) {
            StepEdits e = plan.edits(i);
            if (e == null) continue;
            breaks += e.breakCount();
            places += e.placeCount();
            for (int j = 0; j < e.doorSetCount(); j++) {
                assertEquals(BlockPos.asLong(GX, 1, Z), e.doorSetAt(j), "the only SET is on the gate cell");
                if (e.doorSetOpenAt(j)) setOpen++;
                else setClosed++;
            }
        }
        assertEquals(open, setOpen, "exactly " + open + " SET_OPEN — the gate is opened, not smashed");
        assertEquals(closed, setClosed, "no spurious SET_CLOSED");
        assertEquals(0, breaks, "nothing broken (the toggle outbids/replaces every break)");
        assertEquals(0, places, "nothing placed");
    }

    /** The plan crossed with ZERO edits of any kind — the free-admit proof. */
    private static void assertNoEditsAtAll(BlockPathPlan plan) {
        for (int i = 0; i < plan.size(); i++) {
            StepEdits e = plan.edits(i);
            if (e == null) continue;
            assertEquals(0, e.breakCount(), "no breaks folded");
            assertEquals(0, e.placeCount(), "no places folded");
            assertEquals(0, e.doorSetCount(), "no SETs folded");
        }
    }

    /** No SET edits anywhere in a (possibly partial, possibly null) plan. */
    private static void assertNoSetsAnywhere(BlockPathPlan plan) {
        if (plan == null) return;
        for (int i = 0; i < plan.size(); i++) {
            StepEdits e = plan.edits(i);
            if (e == null) continue;
            assertEquals(0, e.doorSetCount(), "flag OFF: no SET is folded anywhere");
        }
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
