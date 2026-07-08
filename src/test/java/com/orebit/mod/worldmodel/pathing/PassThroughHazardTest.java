package com.orebit.mod.worldmodel.pathing;

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
import com.orebit.mod.pathfinding.blockpathfinder.RegionBound;
import com.orebit.mod.pathfinding.blockpathfinder.cuboid.Axes;
import com.orebit.mod.pathfinding.blockpathfinder.cuboid.NavGridCuboidsView;
import com.orebit.mod.pathfinding.blockpathfinder.movements.Traverse;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

/**
 * Headless proof of the caps-honest pass-through hazard surcharge ({@code MovementContext.bodyTransitCost}
 * — 1 HP × {@link BotCaps#costPerHitpoint} per damaging body cell, gated on {@link BotCaps#takesDamage}):
 * the SAME maze, two bots, two routes.
 *
 * <p><b>Single-bush regime.</b> A sealed stone section holds two carved routes from start to goal: a
 * straight 10-step corridor with ONE fire cell in the body path (cost {@code 10×FLAT + 100 ≈ 146} at the
 * default 100-tick costPerHitpoint), and a 14-step clear detour ({@code ≈ 65}). A <b>mortal</b> bot
 * ({@code takesDamage = true}) must pay the fire surcharge, so the detour is cheaper and it routes around;
 * an <b>invulnerable</b> bot ({@code takesDamage = false}) pays nothing and walks straight through the
 * fire — mirroring how the immune fall window already zeroes the Fall damage penalty.
 *
 * <p><b>Maze regime</b> ({@link #mortalBotTakesTheLongDetourAroundAHazardMaze}). Four fire cells on the
 * straight line vs a LONG (~40-extra-step) detour — the case the old hardcoded 40-tick surcharge got
 * WRONG: 4 cells bought only {@code 4×40/4.633 ≈ 34.5} blocks of detour, under this detour's ~40-step
 * extra cost, so the old planner rationally plowed the maze lethally. At {@code costPerHitpoint = 100}
 * the 4 cells buy ≈ 86 blocks, so the long detour must now win for a mortal bot (an immune bot still
 * plows — both directions stay asserted).
 *
 * <p>The classic searches run micro-only (no cuboid bound) with {@code greedyWeight = 1.0} so the returned
 * path is the true cost-optimal one (deterministic, no greedy wobble). The {@code ...UnderMacroCollapse}
 * variants re-run the seam maze WITH a cuboid bound — the LIVE follower's configuration ({@code
 * PathPlan.replanBlock} always supplies one, so in-game flat walks go through {@code Traverse.emitMacro},
 * a path the micro-only tests never exercised) — and {@link #macroTraverseCandidatePricesTheBushBodyCell}
 * pins the macro candidate's cost itself: the emitted flat-walk into a bush column must carry the 1-HP
 * {@code costPerHitpoint} surcharge. (On any walkable floor the macro jump length degenerates to 1 — the
 * escape-hedge's nearest-orthogonal-face distance is 0 because the uniform floor slab is 1-tall on Y, the
 * body space above being a different navtype — so macro-vs-micro must be candidate-for-candidate
 * equivalent; these tests hold that equivalence honest.) Lives in this package to reach
 * {@link NavGridView}'s package-private synthetic constructor.
 */
class PassThroughHazardTest {

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

    private static final BlockPos START = new BlockPos(2, 0, 6);
    private static final BlockPos GOAL = new BlockPos(12, 0, 6);
    // The fire cell sits at (7,1,6) — the body/feet cell of floor node (7,0,6). Plan waypoints are STAND
    // positions (floorCell.above(), see BlockPathfinder "Cells, not feet"), so the waypoint of that node
    // is exactly the fire cell's coordinates: (FIRE_X, 1, FIRE_Z).
    private static final int FIRE_X = 7, FIRE_Y = 1, FIRE_Z = 6;

    // Walk-only caps (no break/place — the stone maze confines), admissible weight 1.0 for optimal paths;
    // the ONLY difference between the two bots is the takesDamage flag under test. Damage priced at the
    // config-default 100 ticks/HP (DEFAULT_COST_PER_HITPOINT) — the unified knob under test.
    private static final BotCaps MORTAL_WALK = new BotCaps(
            1, BotCaps.DEFAULT_SAFE_FALL, BotCaps.DEFAULT_MAX_FALL, true,
            BotCaps.DEFAULT_COST_PER_HITPOINT, false, false,
            BotCaps.UNBREAKABLE, false, BotCaps.DEFAULT_MAX_NODES, 1.0f);
    private static final BotCaps IMMUNE_WALK = new BotCaps(
            1, BotCaps.DEFAULT_SAFE_FALL, BotCaps.DEFAULT_MAX_FALL, false,
            BotCaps.DEFAULT_COST_PER_HITPOINT, false, false,
            BotCaps.UNBREAKABLE, false, BotCaps.DEFAULT_MAX_NODES, 1.0f);
    /**
     * MORTAL_WALK plus canBreak — the break-through-hazard bot. maxBreakHardness is pinned to 0 so ONLY
     * the hardness-0 hazards (fire / a bush) are within its mining cap: the stone maze still confines
     * (these headless searches run with no MiningModel table, where a stone break would otherwise price
     * at 0 and open free tunnels through every wall — a tie the test must not depend on).
     */
    private static final BotCaps MORTAL_BREAK = new BotCaps(
            1, BotCaps.DEFAULT_SAFE_FALL, BotCaps.DEFAULT_MAX_FALL, true,
            BotCaps.DEFAULT_COST_PER_HITPOINT, true, false,
            0, false, BotCaps.DEFAULT_MAX_NODES, 1.0f);

    @Test
    void mortalBotDetoursAroundTheFire() {
        BlockPathPlan plan = BlockPathfinder.findPath(buildMaze(), START, GOAL, MORTAL_WALK);

        assertNotNull(plan, "the maze has a clear detour — a mortal bot must still reach the goal");
        assertReachedGoal(plan);
        assertFalse(contains(plan, FIRE_X, FIRE_Y, FIRE_Z),
                "a mortal bot must not walk its body through the fire cell "
                        + "(100-tick 1-HP surcharge > 4-step detour)");
        assertTrue(anyAtZ(plan, 8), "the mortal bot should take the carved z=8 detour around the fire");
    }

    // ---- The MAZE regime: several hazard cells vs a LONG detour (the unified-knob case) --------------

    /** The four fire body cells on the maze's straight line (x, then the fixed body y=1 / z=6). */
    private static final int[] MAZE_FIRE_XS = {4, 6, 8, 10};

    @Test
    void mortalBotTakesTheLongDetourAroundAHazardMaze() {
        // 4 fire cells on the 10-step straight line vs a ~50-step detour (~40 extra steps ≈ 185 extra
        // ticks). Old economics (40/cell): 4 × 40 = 160 < 185 → plow through, lethally. Unified knob at
        // the default 100/HP: 4 × 100 = 400 > 185 → the long detour must win.
        BlockPathPlan plan = BlockPathfinder.findPath(buildHazardMaze(), START, GOAL, MORTAL_WALK);

        assertNotNull(plan, "the maze has a clear (if long) detour — a mortal bot must still reach the goal");
        assertReachedGoal(plan);
        for (int fx : MAZE_FIRE_XS) {
            assertFalse(contains(plan, fx, 1, 6),
                    "at costPerHitpoint=100 a mortal bot must not transit the fire cell at x=" + fx
                            + " (4 cells buy ~86 blocks of detour; this detour costs ~40)");
        }
        assertTrue(anyAtZ(plan, 14), "the mortal bot should take the long z=14 detour around the fire maze");
    }

    @Test
    void mortalBreakerPunchesThroughTheHazardMazeAndFoldsTheBreaks() {
        // Break-through-hazard: a mortal bot that MAY break arbitrates punch-out vs transit vs detour by
        // price. Each fire cell transited intact costs 100 (1 HP × costPerHitpoint); punching it out costs
        // its real mining ticks (fire is hardness 0 — ~free) folded as a break edit, so the straight line
        // WITH four folded breaks (~10×4.633 + ~4) beats both the intact line (~446) and the long detour
        // (~235). The folded break is what makes the route honest: the follower's applyEdits clears the
        // fire before the bot walks the cell, so nothing is transited intact.
        BlockPathPlan plan = BlockPathfinder.findPath(buildHazardMaze(), START, GOAL, MORTAL_BREAK);

        assertNotNull(plan, "the straight corridor is open — the breaking bot must reach the goal");
        assertReachedGoal(plan);
        assertFalse(anyAtZ(plan, 14),
                "punching the fires out is far cheaper than the long detour — no z=14 leg");
        for (int fx : MAZE_FIRE_XS) {
            assertTrue(contains(plan, fx, 1, 6),
                    "the breaking bot takes the straight line through the (cleared) fire column at x=" + fx);
            assertTrue(hasBreakAt(plan, fx, 1, 6),
                    "the step onto the fire column at x=" + fx + " must FOLD A BREAK of the fire cell — "
                            + "walking it intact would owe the 100-tick hazard surcharge");
        }
    }

    /** Whether any step's folded edit-set breaks exactly cell {@code (x,y,z)}. */
    private static boolean hasBreakAt(BlockPathPlan plan, int x, int y, int z) {
        for (int i = 0; i < plan.size(); i++) {
            var edits = plan.edits(i);
            if (edits == null) continue;
            for (int b = 0; b < edits.breakCount(); b++) {
                BlockPos p = edits.breakPos(b);
                if (p.getX() == x && p.getY() == y && p.getZ() == z) return true;
            }
        }
        return false;
    }

    @Test
    void invulnerableBotPlowsStraightThroughTheHazardMaze() {
        // The immune direction stays asserted: no damage term at all, so the 10-step line wins outright.
        BlockPathPlan plan = BlockPathfinder.findPath(buildHazardMaze(), START, GOAL, IMMUNE_WALK);

        assertNotNull(plan, "the straight corridor is open — an immune bot must reach the goal");
        assertReachedGoal(plan);
        assertTrue(contains(plan, MAZE_FIRE_XS[0], 1, 6),
                "an invulnerable bot pays NO damage surcharge — it walks the straight line through the maze");
        assertFalse(anyAtZ(plan, 14), "the immune bot has no reason to take the long z=14 detour");
    }

    // ---- The SEAM regime: the hazard maze straddling a section boundary (the in-game death repro) -----

    /** Floor row of the seam maze: the TOP row of section 0; body cells live in section 1 (ly=0..1). */
    private static final int SEAM_FLOOR_Y = 15;
    private static final BlockPos SEAM_START = new BlockPos(2, SEAM_FLOOR_Y, 6);
    private static final BlockPos SEAM_GOAL = new BlockPos(12, SEAM_FLOOR_Y, 6);

    @Test
    void mortalBotDetoursAroundASeamHazardMaze() {
        // The exact in-game repro, headless: sweet-berry bushes at ly=0 of section 1, target floors at
        // y=15 = ly=15 of section 0. Before vertical overscan the floors' CLEARABLE_HAZARD/SLOW_TRANSIT
        // bits were computed against optimistic air (the bushes live in the section above), so
        // bodyTransitCost's zero-read fast path returned 0 and the bot plowed the maze lethally. With the
        // seam overscanned the economics match the flat maze: 4 bushes × ~100 ticks ≫ the ~185-tick
        // detour, so the mortal bot must route around.
        BlockPathPlan plan = BlockPathfinder.findPath(buildSeamHazardMaze(), SEAM_START, SEAM_GOAL, MORTAL_WALK);

        assertNotNull(plan, "the seam maze has a clear (if long) detour — a mortal bot must still reach the goal");
        assertReachedGoal(plan);
        for (int fx : MAZE_FIRE_XS) {
            assertFalse(contains(plan, fx, SEAM_FLOOR_Y + 1, 6),
                    "a mortal bot must not transit the seam-row bush at x=" + fx
                            + " (the hazard sits just across the section boundary — the bit must be honest there)");
        }
        assertTrue(anyAtZ(plan, 14), "the mortal bot should take the long z=14 detour around the seam bushes");
    }

    @Test
    void invulnerableBotPlowsStraightThroughTheSeamHazardMaze() {
        // The immune direction stays asserted at the seam too: no damage term, and the bushes' light
        // through-slow surcharge (~1.5 ticks/cell) is far below the ~185-tick detour.
        BlockPathPlan plan = BlockPathfinder.findPath(buildSeamHazardMaze(), SEAM_START, SEAM_GOAL, IMMUNE_WALK);

        assertNotNull(plan, "the straight corridor is open — an immune bot must reach the goal");
        assertReachedGoal(plan);
        assertTrue(contains(plan, MAZE_FIRE_XS[0], SEAM_FLOOR_Y + 1, 6),
                "an invulnerable bot pays NO damage surcharge — it walks the straight line through the seam bushes");
        assertFalse(anyAtZ(plan, 14), "the immune bot has no reason to take the long z=14 detour");
    }

    // ---- The MACRO regime: the seam maze with the LIVE search shape (cuboid bound → emitMacro path) ----

    /** The cuboid growth cap covering the whole two-chunk seam course — the analog of the live
     *  {@code PathPlan.cuboidCapBox}, whose presence is what routes flat walks through
     *  {@code Traverse.emitMacro} instead of the micro emit. */
    private static final RegionBound SEAM_MAZE_BOUND = new RegionBound(0, 31, 0, 63, 0, 15);

    @Test
    void mortalBotDetoursAroundTheSeamHazardMazeUnderMacroCollapse() {
        // Same course + economics as the micro seam test, but searched the way the LIVE follower searches
        // (cuboid bound present, macro collapse active). Since flat-walk macro jumps clamp to J=1 on any
        // walkable floor (see the class doc), this must reproduce the micro result exactly — a divergence
        // here means the macro emit lost (or double-charged) a per-cell surcharge the micro emit prices.
        assertTrue(BlockPathfinder.MACRO_MOVES, "macro collapse should be on by default for this variant");
        BlockPathPlan plan = BlockPathfinder.findPath(buildSeamHazardMaze(), SEAM_START, SEAM_GOAL,
                MORTAL_WALK, null, SEAM_MAZE_BOUND, null);

        assertNotNull(plan, "the seam maze has a clear (if long) detour — a mortal bot must still reach the goal");
        assertReachedGoal(plan);
        for (int fx : MAZE_FIRE_XS) {
            assertFalse(contains(plan, fx, SEAM_FLOOR_Y + 1, 6),
                    "a mortal bot must not transit the seam-row bush at x=" + fx
                            + " under macro collapse (the emitMacro per-cell loop must price every body cell)");
        }
        assertTrue(anyAtZ(plan, 14), "the mortal bot should take the long z=14 detour under macro collapse");
    }

    @Test
    void invulnerableBotPlowsTheSeamHazardMazeUnderMacroCollapse() {
        // Macro-parity for the immune direction too: no damage term, straight line wins.
        BlockPathPlan plan = BlockPathfinder.findPath(buildSeamHazardMaze(), SEAM_START, SEAM_GOAL,
                IMMUNE_WALK, null, SEAM_MAZE_BOUND, null);

        assertNotNull(plan, "the straight corridor is open — an immune bot must reach the goal");
        assertReachedGoal(plan);
        assertTrue(contains(plan, MAZE_FIRE_XS[0], SEAM_FLOOR_Y + 1, 6),
                "an invulnerable bot pays NO damage surcharge — it walks the straight line under macro collapse");
        assertFalse(anyAtZ(plan, 14), "the immune bot has no reason to take the long z=14 detour");
    }

    @Test
    void macroTraverseCandidatePricesTheBushBodyCell() {
        // Pin the emitMacro-emitted candidate's COST directly (not just the route): expanding the corridor
        // node one step west of the first bush, the +X flat-walk candidate onto floor (4,15,6) — whose feet
        // cell (4,16,6) is the bush — must be emitted via the macro path (cuboids present, travel axis ==
        // macro axis X) and must carry the 1-HP × costPerHitpoint pass-through surcharge for the bush cell.
        assertTrue(BlockPathfinder.MACRO_MOVES, "macro collapse should be on by default for this variant");
        NavGridView grid = buildSeamHazardMaze();
        MovementContext mc = new MovementContext(grid, MORTAL_WALK);
        NavGridCuboidsView cuboids = new NavGridCuboidsView(grid, mc.pathEdits(), SEAM_MAZE_BOUND);
        mc.setMacro(cuboids, SEAM_GOAL.getX(), SEAM_GOAL.getY(), SEAM_GOAL.getZ(), Axes.AXIS_X);

        final float[] got = { Float.NaN };
        new Traverse().candidates(mc, 3, SEAM_FLOOR_Y, 6, new CandidateSink() {
            @Override
            public void accept(int x, int y, int z, float cost, EditScratch edits) {
                if (x == 4 && y == SEAM_FLOOR_Y && z == 6) got[0] = cost;
            }
        });

        assertFalse(Float.isNaN(got[0]),
                "the +X macro flat-walk candidate onto the bush column (4," + SEAM_FLOOR_Y + ",6) must be emitted");
        assertTrue(got[0] >= MORTAL_WALK.costPerHitpoint(),
                "the macro candidate must include the bush's 1-HP pass-through surcharge ("
                        + MORTAL_WALK.costPerHitpoint() + " ticks); got " + got[0]);
    }

    @Test
    void invulnerableBotTakesTheShortRouteThroughTheFire() {
        BlockPathPlan plan = BlockPathfinder.findPath(buildMaze(), START, GOAL, IMMUNE_WALK);

        assertNotNull(plan, "the straight corridor is open — an immune bot must reach the goal");
        assertReachedGoal(plan);
        assertTrue(contains(plan, FIRE_X, FIRE_Y, FIRE_Z),
                "an invulnerable bot pays NO damage surcharge — the straight route through the fire is cheapest");
        assertFalse(anyAtZ(plan, 8), "the immune bot has no reason to take the longer z=8 detour");
    }

    /** The plan genuinely ended at the goal (within the search's ±1 horizontal arrival tolerance) —
     *  guards the waypoint-shape assertions against a spurious partial/short plan. */
    private static void assertReachedGoal(BlockPathPlan plan) {
        BlockPos last = plan.waypoint(plan.size() - 1);
        assertTrue(Math.abs(last.getX() - GOAL.getX()) <= 1 && Math.abs(last.getZ() - GOAL.getZ()) <= 1,
                "the plan should end at the goal; ended at " + last);
    }

    private static boolean contains(BlockPathPlan plan, int x, int y, int z) {
        for (int i = 0; i < plan.size(); i++) {
            BlockPos wp = plan.waypoint(i);
            if (wp.getX() == x && wp.getY() == y && wp.getZ() == z) return true;
        }
        return false;
    }

    private static boolean anyAtZ(BlockPathPlan plan, int z) {
        for (int i = 0; i < plan.size(); i++) {
            if (plan.waypoint(i).getZ() == z) return true;
        }
        return false;
    }

    /**
     * One sealed stone section (chunk 0,0) with two carved routes (air at y=1..2 over stone floors at y=0):
     * the straight corridor {@code z=6, x=2..12} with FIRE at {@code (7,1,6)}, and a clear detour leaving it
     * at {@code x=5} (via {@code z=7} to {@code z=8}), running {@code x=5..9} along {@code z=8}, and
     * rejoining at {@code x=9}. Straight = 10 steps + one fire body cell; detour = 14 steps, hazard-free.
     */
    private static NavGridView buildMaze() {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState fire = Blocks.FIRE.defaultBlockState();

        PalettedContainer<BlockState> s = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    s.set(x, y, z, stone); // solid rock everywhere; the routes are carved out below
                }
            }
        }

        for (int x = 2; x <= 12; x++) carve(s, air, x, 6);   // straight corridor
        s.set(FIRE_X, 1, FIRE_Z, fire);                      // the hazard: fire in the body path
        carve(s, air, 5, 7);                                 // detour out at x=5 ...
        for (int x = 5; x <= 9; x++) carve(s, air, x, 8);    // ... along z=8 ...
        carve(s, air, 9, 7);                                 // ... and back in at x=9

        NavSection section = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(s, false, section.getTraversalGrid());

        PalettedContainer<BlockState> airStates = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        NavSection airSection = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(airStates, true, airSection.getTraversalGrid());

        NavSection[] column = { section, airSection, airSection, airSection }; // y 0..63 (only y 0..15 used)
        ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        chunks.put(NavStore.key(0, 0), column);
        return new NavGridView(0, chunks);
    }

    /** Carve a 2-tall walking gap (air at y=1..2) over the stone floor at column {@code (x, z)}. */
    private static void carve(PalettedContainer<BlockState> s, BlockState air, int x, int z) {
        s.set(x, 1, z, air);
        s.set(x, 2, z, air);
    }

    /**
     * The MAZE course — two sealed stone chunks side by side (x 0..31, z 0..15), routes carved at y=1..2
     * over stone floors at y=0:
     * <ul>
     *   <li><b>straight line</b>: {@code z=6, x=2..12} (10 steps) with FIRE body cells at
     *       {@code x = 4, 6, 8, 10} — a 1-wide corridor, so no within-corridor dodge exists;</li>
     *   <li><b>long detour</b>: out at {@code (2, z=7..14)}, along {@code z=14, x=2..24}, back via
     *       {@code (24, z=10..13)}, along {@code z=10, x=12..24}, rejoining at {@code (12, z=7..9)} —
     *       ≈ 50 steps total, ≈ 40 more than the line (≈ 185 extra ticks, between the old 4×40=160
     *       plow-through threshold and the new 4×100=400 detour allowance).</li>
     * </ul>
     * Everything else is solid stone; outside the two built chunks is UNBUILT (the search can't leave).
     */
    private static NavGridView buildHazardMaze() {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState fire = Blocks.FIRE.defaultBlockState();

        PalettedContainer<BlockState> west = filledStone(air, stone); // chunk (0,0): x 0..15
        PalettedContainer<BlockState> east = filledStone(air, stone); // chunk (1,0): x 16..31

        for (int x = 2; x <= 12; x++) carveAt(west, east, air, x, 6);   // the straight line
        for (int fx : MAZE_FIRE_XS) west.set(fx, 1, 6, fire);           // 4 fires (all in x<16)
        for (int z = 7; z <= 14; z++) carveAt(west, east, air, 2, z);   // detour: out at x=2
        for (int x = 2; x <= 24; x++) carveAt(west, east, air, x, 14);  // ... the long z=14 leg
        for (int z = 10; z <= 13; z++) carveAt(west, east, air, 24, z); // ... back down at x=24
        for (int x = 12; x <= 23; x++) carveAt(west, east, air, x, 10); // ... the z=10 return leg
        for (int z = 7; z <= 9; z++) carveAt(west, east, air, 12, z);   // ... rejoin at the goal column

        NavSection westSection = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(west, false, westSection.getTraversalGrid());
        NavSection eastSection = NavSection.create(new BlockPos(16, 0, 0));
        NavSectionBuilder.classifyInto(east, false, eastSection.getTraversalGrid());

        PalettedContainer<BlockState> airStates = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        NavSection airSection = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(airStates, true, airSection.getTraversalGrid());

        ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        chunks.put(NavStore.key(0, 0), new NavSection[] { westSection, airSection, airSection, airSection });
        chunks.put(NavStore.key(1, 0), new NavSection[] { eastSection, airSection, airSection, airSection });
        return new NavGridView(0, chunks);
    }

    /**
     * The maze-regime course rebuilt to STRADDLE a section boundary — identical route geometry and
     * economics to {@link #buildHazardMaze}, but the floors are the TOP row of section 0 ({@code y=15})
     * and every body cell (the carved corridors, and the sweet-berry bushes at {@code y=16}) lives in
     * section 1 ({@code ly=0..1}). Built the way {@code ChunkNavBuilder} builds a live column: navtypes
     * for both sections first, then flags with the section above's grid as vertical overscan — the
     * mechanism under test (without it, every floor's hazard prefilter bit is stale-CLEAR).
     */
    private static NavGridView buildSeamHazardMaze() {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState bush = Blocks.SWEET_BERRY_BUSH.defaultBlockState();

        // Section 0 (floors): solid rock — every top-row cell is a potential floor.
        PalettedContainer<BlockState> westFloor = filledStone(air, stone);
        PalettedContainer<BlockState> eastFloor = filledStone(air, stone);
        // Section 1 (body space): solid rock with the SAME corridor layout carved at ly=0..1.
        PalettedContainer<BlockState> westBody = filledStone(air, stone);
        PalettedContainer<BlockState> eastBody = filledStone(air, stone);

        for (int x = 2; x <= 12; x++) carveSeamAt(westBody, eastBody, air, x, 6);   // the straight line
        for (int fx : MAZE_FIRE_XS) westBody.set(fx, 0, 6, bush);                   // 4 bushes AT THE SEAM ROW
        for (int z = 7; z <= 14; z++) carveSeamAt(westBody, eastBody, air, 2, z);   // detour: out at x=2
        for (int x = 2; x <= 24; x++) carveSeamAt(westBody, eastBody, air, x, 14);  // ... the long z=14 leg
        for (int z = 10; z <= 13; z++) carveSeamAt(westBody, eastBody, air, 24, z); // ... back down at x=24
        for (int x = 12; x <= 23; x++) carveSeamAt(westBody, eastBody, air, x, 10); // ... the z=10 return leg
        for (int z = 7; z <= 9; z++) carveSeamAt(westBody, eastBody, air, 12, z);   // ... rejoin at the goal column

        NavSection westFloorS = NavSection.create(BlockPos.ZERO);
        NavSection westBodyS = NavSection.create(new BlockPos(0, 16, 0));
        NavSection eastFloorS = NavSection.create(new BlockPos(16, 0, 0));
        NavSection eastBodyS = NavSection.create(new BlockPos(16, 16, 0));

        // The two-pass column build (ChunkNavBuilder's structure): all navtypes, then flags bottom-up
        // with the above section's grid in hand.
        NavSectionBuilder.classifyNavtypes(westFloor, false, westFloorS.getTraversalGrid(), null);
        NavSectionBuilder.classifyNavtypes(westBody, false, westBodyS.getTraversalGrid(), null);
        NavSectionBuilder.classifyNavtypes(eastFloor, false, eastFloorS.getTraversalGrid(), null);
        NavSectionBuilder.classifyNavtypes(eastBody, false, eastBodyS.getTraversalGrid(), null);
        NavSectionBuilder.computeFlags(westFloorS.getTraversalGrid(), false, westBodyS.getTraversalGrid());
        NavSectionBuilder.computeFlags(westBodyS.getTraversalGrid(), false, null); // air above section 1
        NavSectionBuilder.computeFlags(eastFloorS.getTraversalGrid(), false, eastBodyS.getTraversalGrid());
        NavSectionBuilder.computeFlags(eastBodyS.getTraversalGrid(), false, null);

        PalettedContainer<BlockState> airStates = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        NavSection airSection = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(airStates, true, airSection.getTraversalGrid());

        ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        chunks.put(NavStore.key(0, 0), new NavSection[] { westFloorS, westBodyS, airSection, airSection });
        chunks.put(NavStore.key(1, 0), new NavSection[] { eastFloorS, eastBodyS, airSection, airSection });
        return new NavGridView(0, chunks);
    }

    /** Carve the 2-tall walking gap of the seam maze — {@code ly=0..1} of the BODY section — routed to
     *  the owning chunk container by world {@code x} (0..31 across the pair). */
    private static void carveSeamAt(PalettedContainer<BlockState> west, PalettedContainer<BlockState> east,
            BlockState air, int x, int z) {
        PalettedContainer<BlockState> s = x < 16 ? west : east;
        s.set(x & 15, 0, z, air);
        s.set(x & 15, 1, z, air);
    }

    /** A section container pre-filled with solid stone (the maze is carved out of it). */
    private static PalettedContainer<BlockState> filledStone(BlockState air, BlockState stone) {
        PalettedContainer<BlockState> s = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    s.set(x, y, z, stone);
                }
            }
        }
        return s;
    }

    /** {@link #carve} routed to the owning chunk container by world {@code x} (0..31 across the pair). */
    private static void carveAt(PalettedContainer<BlockState> west, PalettedContainer<BlockState> east,
            BlockState air, int x, int z) {
        carve(x < 16 ? west : east, air, x & 15, z);
    }
}
