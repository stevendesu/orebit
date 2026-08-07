package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.orebit.mod.Debug;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.MovementRegistry;
import com.orebit.mod.pathfinding.blockpathfinder.RegionBound;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

/**
 * Headless proof of the {@code Climb} movement (ladders / vines): a sealed stone maze where a climbable
 * strip is the ONLY route ({@link BotCaps#DEFAULT} — no break, no place, jump 1), so a found plan proves
 * the grab-entry + up/down-climb candidates work end to end through the real classifier (ladder =
 * {@code SHAPE_OTHER} wall geometry + CLIMB bit; vine = empty shape + CLIMB bit). Negative: with the
 * strip removed the goal is unreachable. Lives in this package to reach {@link NavGridView}'s
 * package-private synthetic constructor. Not testable headless: the 0.2/−0.15 climb physics, the
 * jumping-flag climb trigger, and the Ascend top-out — those are the in-game verification pass.
 */
class ClimbTest {

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

    private static final RegionBound CORRIDOR = new RegionBound(0, 15, 0, 31, 0, 15);
    /** Start floor: the room beside the climb column. */
    private static final BlockPos START = new BlockPos(2, 0, 8);
    /**
     * Goal floor: the top of the wall next to the climb column. The search's ±1 xz / ±2 y arrival
     * tolerance means the upper climb nodes at x=3 satisfy it — reaching them at all REQUIRES climbing
     * (no break/place, and Ascend gains only 1 onto standable ground, of which the shaft has none).
     */
    private static final BlockPos GOAL = new BlockPos(4, 5, 8);
    /** Start floor for the overhead-strip (jump-grab) builds: the room under the strip. */
    private static final BlockPos START_UNDER_STRIP = new BlockPos(2, 0, 8);

    @Test
    void ladderIsTheOnlyWayUpTheWall() {
        NavGridView grid = buildWall(Blocks.LADDER.defaultBlockState());
        BlockPathPlan plan = BlockPathfinder.findPath(grid, START, GOAL, BotCaps.DEFAULT, CORRIDOR);

        assertNotNull(plan, "a walk-only bot should climb the ladder strip to the top of the wall");
        assertTrue(contains(plan, MovementRegistry.CLIMB), "the plan should contain Climb steps");
    }

    @Test
    void vineIsClimbableToo() {
        // Vines are the OTHER climbable shape: empty collision (passable) rather than a SHAPE_OTHER wall.
        NavGridView grid = buildWall(Blocks.VINE.defaultBlockState());
        BlockPathPlan plan = BlockPathfinder.findPath(grid, START, GOAL, BotCaps.DEFAULT, CORRIDOR);

        assertNotNull(plan, "a walk-only bot should climb the vine strip to the top of the wall");
        assertTrue(contains(plan, MovementRegistry.CLIMB), "the plan should contain Climb steps");
    }

    @Test
    void withoutTheLadderTheTopIsUnreachable() {
        NavGridView grid = buildWall(Blocks.AIR.defaultBlockState()); // same carve, no climbable strip
        BlockPathPlan plan = BlockPathfinder.findPath(grid, START, GOAL, BotCaps.DEFAULT, CORRIDOR);

        assertNull(plan, "with no ladder (and no break/place) the top of the wall must be unreachable");
    }

    @Test
    void climbsDownALadderShaftDeeperThanAnySurvivableFall() {
        // A 21-deep 1×1 shaft (deeper than BotCaps.DEFAULT's maxFallDistance=16) with a ladder strip:
        // the only way to the pit floor is climbing down. (Fall can't even step in: the ladder cells are
        // SHAPE_OTHER walls to its step-off clearance check — the grab entry is the sole way onto the
        // column, which is exactly the down-climb geometry being proven.)
        NavGridView grid = buildShaft();
        BlockPos start = new BlockPos(2, 21, 8);   // top platform beside the shaft
        BlockPos goal = new BlockPos(3, 0, 8);     // the pit floor at the shaft bottom
        BlockPathPlan plan = BlockPathfinder.findPath(grid, start, goal, BotCaps.DEFAULT, CORRIDOR);

        assertNotNull(plan, "the bot should climb down the ladder to the pit floor");
        assertTrue(contains(plan, MovementRegistry.CLIMB), "the descent should be Climb steps");
        BlockPos last = plan.waypoint(plan.size() - 1);
        assertTrue(last.getY() <= 3, "the plan should end near the pit floor; ended at " + last);
    }

    // ---- The climb/vine vocabulary arc (NOTES-movement-physics.md §4) ------------------------------

    /** Owner ruling 2026-07-31: no jump launches from climbable stances, and a same-side upper ladder
     *  makes the plate stance geometrically impossible (FACING isn't packed) — a gapped ladder column
     *  must NOT connect upward. */
    @Test
    void ladderGapUpIsRefused() {
        NavGridView grid = buildGappedWall(Blocks.LADDER.defaultBlockState());
        assertNull(BlockPathfinder.findPath(grid, START, GOAL, BotCaps.DEFAULT, CORRIDOR),
                "a ladder column with a one-cell air gap must not connect upward");
    }

    /** Physics: a hanging entity can never fire the 0.42 jump and the climb-out pop peaks at +0.154 —
     *  vine/air/vine cannot ascend (NOTES-movement-physics.md §3/§4). */
    @Test
    void vineGapUpIsRefused() {
        NavGridView grid = buildGappedWall(Blocks.VINE.defaultBlockState());
        assertNull(BlockPathfinder.findPath(grid, START, GOAL, BotCaps.DEFAULT, CORRIDOR),
                "a vine column with a one-cell air gap must not connect upward");
    }

    /** §3.3 jump-grab: a grounded 0.42 jump lifts the feet across ONE air cell into a climbable
     *  overhead — "jump to reach the bottom of a vine". */
    @Test
    void jumpGrabReachesAVineBottomOverhead() {
        NavGridView grid = buildOverheadStrip(2); // vine bottom one air cell above the feet
        BlockPos goal = new BlockPos(2, 4, 8);    // partway up the strip — only climbing reaches it
        BlockPathPlan plan = BlockPathfinder.findPath(grid, START_UNDER_STRIP, goal, BotCaps.DEFAULT, CORRIDOR);
        assertNotNull(plan, "the bot should jump-grab the vine bottom and climb");
        assertTrue(contains(plan, MovementRegistry.CLIMB), "the grab + ascent are Climb steps");
    }

    /** The jump apex is 1.25 blocks — feet+2 is never reachable, so a strip starting TWO air cells up
     *  must be unreachable. */
    @Test
    void jumpGrabCannotReachTwoCellsUp() {
        NavGridView grid = buildOverheadStrip(3); // vine bottom two air cells above the feet
        BlockPos goal = new BlockPos(2, 4, 8);
        assertNull(BlockPathfinder.findPath(grid, START_UNDER_STRIP, goal, BotCaps.DEFAULT, CORRIDOR),
                "a climbable two cells overhead is beyond the 1.25-block jump apex");
    }

    /** §3.1 fall arrest: a vine run in an otherwise LETHAL (20 > maxFall 16) drop column arrests the
     *  fall damage-free at the run's bottom cell, then the release-drop finishes the descent. */
    @Test
    void aVineArrestsAnOtherwiseLethalFall() {
        NavGridView grid = buildCliff(14, 15); // 2-cell vine run, 5 blocks below the step-off
        BlockPos start = new BlockPos(2, 20, 8);
        BlockPos goal = new BlockPos(3, 0, 8);
        BlockPathPlan plan = BlockPathfinder.findPath(grid, start, goal, BotCaps.DEFAULT, CORRIDOR);
        assertNotNull(plan, "the vine run should make the lethal cliff survivable");
        // Waypoints are FEET-frame cells (the uniform feet-cell-plus-one contract): the hang's feet sit
        // in the run's bottom vine cell (3,14,8).
        assertTrue(hasWaypoint(plan, 3, 14, 8),
                "the plan should pass through the HANG (feet in the run's bottom vine cell)");

        assertNull(BlockPathfinder.findPath(buildCliff(-1, -1), start, goal, BotCaps.DEFAULT, CORRIDOR),
                "without the vine the 20-deep drop exceeds maxFall and must be refused");
    }

    /** §3.1 tunneling bound: a SINGLE vine cell deeper than 7 blocks of free fall may be skipped
     *  between feet samples (entry ≥ 1 b/t) — arrest-vs-tunnel is nondeterministic, so the whole
     *  column is refused. */
    @Test
    void aDeepFallPastAShortVineIsRefused() {
        NavGridView grid = buildCliff(10, 10); // run of 1, 10 blocks below the step-off (> bound 7)
        assertNull(BlockPathfinder.findPath(grid, new BlockPos(2, 20, 8), new BlockPos(3, 0, 8),
                        BotCaps.DEFAULT, CORRIDOR),
                "a short vine past the run-1 bound must refuse the column (tunneling nondeterminism)");
    }

    /** §3.2 hang chains: vine curtain → release-drop across a 6-cell gap (within the flat ≤7 arrest
     *  bound) → the next run → dismount at the pit. Vine/air/vine descends any TOTAL depth as long as
     *  each individual gap stays within the bound. */
    @Test
    void vineCurtainChainDescendsTheGap() {
        NavGridView grid = buildVineChain();
        BlockPos start = new BlockPos(2, 21, 8);
        BlockPos goal = new BlockPos(3, 1, 8);
        BlockPathPlan plan = BlockPathfinder.findPath(grid, start, goal, BotCaps.DEFAULT, CORRIDOR);
        assertNotNull(plan, "the curtain chain should descend hang-to-hang to the pit");
        BlockPos last = plan.waypoint(plan.size() - 1);
        assertTrue(last.getY() <= 3, "the plan should end at the pit; ended at " + last);
    }

    /** §3.5 sink-in: standing ATOP a ladder column (the old dead-end trap), the bot enters the column
     *  below and climbs down — the in-column edge from plate-top now exists. */
    @Test
    void sinkInDescendsALadderColumnFromItsTop() {
        NavGridView grid = buildColumn(Blocks.LADDER.defaultBlockState());
        BlockPos start = new BlockPos(3, 20, 8);   // standing ON the ladder column's top plate
        BlockPos goal = new BlockPos(3, 0, 8);     // the pit floor inside the column
        BlockPathPlan plan = BlockPathfinder.findPath(grid, start, goal, BotCaps.DEFAULT, CORRIDOR);
        assertNotNull(plan, "the atop-plate stance must connect down into its own column");
        // Waypoints are FEET-frame: the sink-in's first hop puts the feet INSIDE the top ladder cell.
        BlockPos first = plan.waypoint(0);
        assertTrue(first.getX() == 3 && first.getY() == 20 && first.getZ() == 8,
                "step 0 must be the sink-in (feet into the column's top cell); was " + first);
        assertTrue(contains(plan, MovementRegistry.CLIMB), "the descent is Climb steps");
    }

    /** §3.4/§3.5 scaffolding: the column interior climbs both ways — sink in through the deck going
     *  down, exit-top onto the deck going up (the full-faced standable climbable top; ladder plates are
     *  NARROW_TOP-excluded). */
    @Test
    void scaffoldColumnConnectsBothWays() {
        NavGridView grid = buildColumn(Blocks.SCAFFOLDING.defaultBlockState());
        BlockPos top = new BlockPos(3, 20, 8);
        BlockPos pit = new BlockPos(3, 0, 8);
        assertNotNull(BlockPathfinder.findPath(grid, top, pit, BotCaps.DEFAULT, CORRIDOR),
                "deck → pit: sink-in + interior climb-down");
        assertNotNull(BlockPathfinder.findPath(grid, pit, top, BotCaps.DEFAULT, CORRIDOR),
                "pit → deck: interior climb-up + exit-top");
    }

    /** §3.6: the lateral grab's hold is a SNEAK and scaffolding is sneak-exempt (the bot would sink
     *  while crossing) — a scaffold wall reachable only sideways is refused. V1-conservative: interior
     *  walk-in entry is also shape-blocked (scaffolding classifies SHAPE_FULL), documented §5. */
    @Test
    void scaffoldLateralGrabIsRefused() {
        NavGridView grid = buildWall(Blocks.SCAFFOLDING.defaultBlockState());
        assertNull(BlockPathfinder.findPath(grid, START, GOAL, BotCaps.DEFAULT, CORRIDOR),
                "a sideways-only scaffold column must be refused (the sneak hold cannot work)");
    }

    private static boolean contains(BlockPathPlan plan, Object move) {
        for (int i = 0; i < plan.size(); i++) {
            if (plan.movement(i) == move) return true;
        }
        return false;
    }

    private static boolean hasWaypoint(BlockPathPlan plan, int x, int y, int z) {
        for (int i = 0; i < plan.size(); i++) {
            BlockPos w = plan.waypoint(i);
            if (w.getX() == x && w.getY() == y && w.getZ() == z) return true;
        }
        return false;
    }

    /**
     * One sealed stone section: a start room at {@code (2, 1..3, 8)}, a climb strip at {@code (3, 1..5, 8)}
     * ({@code climbable} — ladder, vine, or air for the negative), open cells above the strip at
     * {@code (3, 6..7, 8)} (the top climb node's head + the Ascend takeoff clearance), and a cleared body
     * over the wall top {@code (4, 6..7, 8)} (the wall cell {@code (4,5,8)} itself stays stone — the goal
     * floor). Everything else is solid, so the strip is the only route up.
     */
    private static NavGridView buildWall(BlockState climbable) {
        BlockState air = Blocks.AIR.defaultBlockState();
        PalettedContainer<BlockState> s = solidSection();

        final int z = 8;
        for (int y = 1; y <= 3; y++) s.set(2, y, z, air);   // start room (floor (2,0,8))
        for (int y = 1; y <= 5; y++) s.set(3, y, z, climbable); // the climb strip
        s.set(3, 6, z, air);                                 // top climb node's head cell
        s.set(3, 7, z, air);                                 // Ascend takeoff clearance (source y+3)
        s.set(4, 6, z, air);                                 // body over the wall top (goal floor (4,5,8))
        s.set(4, 7, z, air);

        return view(classify(s), null);
    }

    /**
     * Two stacked stone sections (y 0..31): a top platform room at {@code (2, 22..23, 8)} (floor
     * {@code (2,21,8)}), a 1×1 shaft at {@code x=3} with a ladder strip {@code (3, 1..22, 8)} and an open
     * head cell {@code (3,23,8)} for the grab entry; the pit floor {@code (3,0,8)} stays stone.
     */
    private static NavGridView buildShaft() {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState ladder = Blocks.LADDER.defaultBlockState();
        PalettedContainer<BlockState> s0 = solidSection();  // y 0..15
        PalettedContainer<BlockState> s1 = solidSection();  // y 16..31

        final int z = 8;
        s1.set(2, 22 - 16, z, air);                          // start room body (floor (2,21,8))
        s1.set(2, 23 - 16, z, air);
        for (int y = 1; y <= 15; y++) s0.set(3, y, z, ladder);
        for (int y = 16; y <= 22; y++) s1.set(3, y - 16, z, ladder);
        s1.set(3, 23 - 16, z, air);                          // head over the ladder top (grab entry)

        return view(classify(s0), classify(s1));
    }

    /** {@link #buildWall} with a ONE-cell air gap mid-strip: {@code (3,1..2,8)} climbable, {@code (3,3,8)}
     *  air, {@code (3,4..5,8)} climbable — the ladder/air/ladder and vine/air/vine ascent shapes. */
    private static NavGridView buildGappedWall(BlockState climbable) {
        BlockState air = Blocks.AIR.defaultBlockState();
        PalettedContainer<BlockState> s = solidSection();

        final int z = 8;
        for (int y = 1; y <= 3; y++) s.set(2, y, z, air);       // start room (floor (2,0,8))
        for (int y = 1; y <= 2; y++) s.set(3, y, z, climbable); // lower strip
        s.set(3, 3, z, air);                                     // the gap
        for (int y = 4; y <= 5; y++) s.set(3, y, z, climbable); // upper strip
        s.set(3, 6, z, air);
        s.set(3, 7, z, air);
        s.set(4, 6, z, air);                                     // body over the wall top (goal (4,5,8))
        s.set(4, 7, z, air);

        return view(classify(s), null);
    }

    /**
     * A vine strip directly OVER the start room's own column: room air at {@code (2,1,8)} (and up to the
     * strip), the strip {@code (2, stripLo..5, 8)}, head cells {@code (2,6..7,8)} air. {@code stripLo==2}
     * puts the vine bottom one air cell above the feet (jump-grab reachable); {@code stripLo==3} puts it
     * two up (beyond the 1.25 apex — must refuse).
     */
    private static NavGridView buildOverheadStrip(int stripLo) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState vine = Blocks.VINE.defaultBlockState();
        PalettedContainer<BlockState> s = solidSection();

        final int z = 8;
        for (int y = 1; y < stripLo; y++) s.set(2, y, z, air);  // the air gap under the strip
        for (int y = stripLo; y <= 5; y++) s.set(2, y, z, vine);
        s.set(2, 6, z, air);
        s.set(2, 7, z, air);

        return view(classify(s), null);
    }

    /**
     * A 20-deep sealed cliff: start platform floor {@code (2,20,8)} (body {@code (2,21..22,8)}), the drop
     * column {@code (3,1..22,8)} air except a vine run at {@code (3, vineLo..vineHi, 8)}, pit floor
     * {@code (3,0,8)}. {@code vineLo < 0} builds the bare (lethal) cliff.
     */
    private static NavGridView buildCliff(int vineLo, int vineHi) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState vine = Blocks.VINE.defaultBlockState();
        PalettedContainer<BlockState> s0 = solidSection();  // y 0..15
        PalettedContainer<BlockState> s1 = solidSection();  // y 16..31

        final int z = 8;
        s1.set(2, 21 - 16, z, air);                          // start platform body (floor (2,20,8))
        s1.set(2, 22 - 16, z, air);
        for (int y = 1; y <= 22; y++) {
            BlockState b = (y >= vineLo && y <= vineHi) ? vine : air;
            if (y < 16) s0.set(3, y, z, b); else s1.set(3, y - 16, z, b);
        }

        return view(classify(s0), classify(s1));
    }

    /**
     * The hang-chain shaft: start room floor {@code (2,21,8)}, a vine curtain {@code (3,16..22,8)}
     * (grabbable from the room), a 6-cell air gap {@code (3,10..15,8)} (release-drop of 6 ≤ the flat
     * arrest bound 7), a lower vine run {@code (3,2..9,8)}, pit floor {@code (3,1,8)}.
     */
    private static NavGridView buildVineChain() {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState vine = Blocks.VINE.defaultBlockState();
        PalettedContainer<BlockState> s0 = solidSection();
        PalettedContainer<BlockState> s1 = solidSection();

        final int z = 8;
        s1.set(2, 22 - 16, z, air);                          // start room body (floor (2,21,8))
        s1.set(2, 23 - 16, z, air);
        for (int y = 16; y <= 22; y++) s1.set(3, y - 16, z, vine); // the curtain
        s1.set(3, 23 - 16, z, air);                          // head over the curtain top
        for (int y = 10; y <= 15; y++) s0.set(3, y, z, air); // the 6-cell gap
        for (int y = 2; y <= 9; y++) s0.set(3, y, z, vine);  // the lower run
        // (3,1,8) stays stone — the pit floor.

        return view(classify(s0), classify(s1));
    }

    /**
     * A free-standing 20-tall climbable column in a sealed shaft: {@code (3,1..20,8)} the climbable,
     * {@code (3,21..23,8)} air above its top (the atop stance's body + clearance), pit floor
     * {@code (3,0,8)}. Start atop at {@code (3,20,8)} (standing ON the plate/deck) or in the pit at
     * {@code (3,0,8)} (feet inside the column's bottom cell).
     */
    private static NavGridView buildColumn(BlockState climbable) {
        BlockState air = Blocks.AIR.defaultBlockState();
        PalettedContainer<BlockState> s0 = solidSection();
        PalettedContainer<BlockState> s1 = solidSection();

        final int z = 8;
        for (int y = 1; y <= 15; y++) s0.set(3, y, z, climbable);
        for (int y = 16; y <= 20; y++) s1.set(3, y - 16, z, climbable);
        for (int y = 21; y <= 23; y++) s1.set(3, y - 16, z, air);

        return view(classify(s0), classify(s1));
    }

    private static PalettedContainer<BlockState> solidSection() {
        PalettedContainer<BlockState> s = new PalettedContainer<>(
                Blocks.AIR.defaultBlockState(), Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        BlockState stone = Blocks.STONE.defaultBlockState();
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    s.set(x, y, z, stone);
                }
            }
        }
        return s;
    }

    private static NavSection classify(PalettedContainer<BlockState> states) {
        NavSection section = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(states, false, section.getTraversalGrid());
        return section;
    }

    /** A one-chunk synthetic grid: section0 at y 0..15, an optional section1 at y 16..31, air above. */
    private static NavGridView view(NavSection s0, NavSection s1) {
        PalettedContainer<BlockState> airStates = new PalettedContainer<>(
                Blocks.AIR.defaultBlockState(), Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        NavSection airSection = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(airStates, true, airSection.getTraversalGrid());

        NavSection[] column = { s0, s1 != null ? s1 : airSection, airSection, airSection };
        ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        chunks.put(NavStore.key(0, 0), column);
        return new NavGridView(0, chunks);
    }
}
