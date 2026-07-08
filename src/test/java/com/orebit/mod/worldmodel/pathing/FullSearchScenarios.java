package com.orebit.mod.worldmodel.pathing;

import java.util.concurrent.ConcurrentHashMap;

import com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.pathfinding.blockpathfinder.RegionBound;
import com.orebit.mod.pathfinding.regionpathfinder.RegionCostField;
import com.orebit.mod.pathfinding.regionpathfinder.RegionMineModel;
import com.orebit.mod.pathfinding.regionpathfinder.RegionPathPlan;
import com.orebit.mod.pathfinding.regionpathfinder.RegionPathfinder;
import com.orebit.mod.pathfinding.regionpathfinder.RegionPlaceModel;
import com.orebit.mod.worldmodel.hpa.RegionAddress;
import com.orebit.mod.worldmodel.hpa.RegionGrid;
import com.orebit.mod.worldmodel.navblock.NavBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

/**
 * Headless synthetic <b>full-search</b> fixtures — the driver-level analog of {@code PathfinderBenchmark}'s
 * block-only synthetic {@code NavGridView} and {@code RegionScenarios}' record-only region grid. Where those
 * two exercise ONE tier over hand-authored data, this class stands up ONE shared
 * {@code ConcurrentHashMap<Long, NavSection[]>} of real {@link NavSection}s and feeds it to <b>both</b> tiers,
 * so the whole live-gameplay path — the goal dig-flood, the reverse-Dijkstra {@link RegionCostField}, the
 * start-fragment flood, the region skeleton, and the region-informed window block A* — runs end-to-end with no
 * {@code ServerLevel} (the enabler is {@link RegionGrid#headless(int, ConcurrentHashMap)}, which routes the
 * region tier's three section resolvers at the same synthetic sections the block tier reads).
 *
 * <p><b>Why this is needed:</b> the record-only {@code RegionScenarios} grid ({@link RegionGrid#headless(int)})
 * builds fragment RECORDS from masks and never makes a {@link NavSection}, so {@code RegionGrid.goalDigSeeds}
 * and {@code startFragmentByFlood} — which read resident sections per cell — early-out. A regression in the
 * live-gameplay region path is therefore invisible to the record-only bench. This fixture closes that gap.
 *
 * <h2>Scenarios</h2>
 * <ul>
 *   <li><b>GOAL_IN_WINDOW</b> — start and a <b>buried</b> goal ~2 regions apart (a solid stone hill fills the
 *       goal's chunk; the goal cell is embedded in breakable stone). The goal dig-flood + multi-source field
 *       seed + virtual-goal routing all fire, and the block window target IS the real goal — the "come into the
 *       cave pocket / dig to the ore" case.</li>
 *   <li><b>GOAL_NOT_IN_WINDOW</b> — an open floor corridor with the goal ~6 regions off (beyond a ~3-region
 *       window). The block window target is a skeleton <b>waypoint</b> portal cell (the {@code WINDOW_STEP}-th
 *       step), not the goal — the cascade / window-target walk. (An approximation of the full
 *       {@code PathPlan.windowTarget}, which the HANDOFF explicitly scopes to "the Nth waypoint, good enough for
 *       a perf bench"; the {@code PathPlan}-driver injection is a deferred follow-up.)</li>
 * </ul>
 *
 * <h2>House style</h2>
 * Static fixture factory producing a smart {@link Fixture} that runs its own end-to-end search. Terrain is
 * hand-authored as {@link NavSection}s exactly as {@code PathfinderBenchmark} does (shared flat columns + one
 * distinct hill column), classified through the real {@code NavSectionBuilder}; both tiers see the same map, so
 * the records they read are byte-identical to what a live chunk classify would produce.
 */
public final class FullSearchScenarios {

    private FullSearchScenarios() {}

    /** The named full-search fixtures. */
    public enum Scenario { GOAL_IN_WINDOW, GOAL_NOT_IN_WINDOW }

    /** Dimension floor for every fixture (world y 0 = the bottom of region ry 0). */
    static final int MINY = 0;

    /** The skeleton step the GOAL_NOT_IN_WINDOW window target aims at (~the far edge of a 3-region window). */
    private static final int WINDOW_STEP = 3;

    /** {@code PathPlan.CUBOID_CAP_MARGIN} — the growth cap margin the live driver pads the start↔target box by. */
    private static final int CUBOID_CAP_MARGIN = 16;

    /**
     * A built full-search scenario: the shared section map + both-tier structures + the start/goal FLOOR cells
     * (the solid block stood on — {@code blockPosition().below()}, the convention the live {@code PathPlan}
     * hands both tiers). A smart object — {@link #search()} runs the exact three-stage pipeline the driver runs.
     */
    public static final class Fixture {
        /** Which scenario this is. */
        public final Scenario scenario;
        /** The hand-authored synthetic sections, shared by both tiers (block {@link NavGridView} + region grid). */
        public final ConcurrentHashMap<Long, NavSection[]> sections;
        /** The region tier over {@link #sections} (headless, no {@code ServerLevel}). */
        public final RegionGrid grid;
        /** The bot's start FLOOR cell (the solid block stood on; feet at {@code +1}). */
        public final BlockPos startFloor;
        /** The goal FLOOR cell — buried in solid stone for GOAL_IN_WINDOW. */
        public final BlockPos goalFloor;
        /** The bot capability the scenario is designed around ({@link BotCaps#BREAK_PLACE}). */
        public final BotCaps caps;
        /** Dimension floor. */
        public final int minY;
        /** The cost-field region bbox, start↔goal padded (as {@code PathPlan} builds it). */
        public final RegionPathfinder.RegionBox box;
        /** Tool-aware region dig model (default — no inventory). */
        public final RegionMineModel mine;
        /** Capability-aware region place model (default — no inventory). */
        public final RegionPlaceModel place;
        /** {@code true} ⇒ the block window target is the real goal (GOAL_IN_WINDOW); else a skeleton waypoint. */
        public final boolean targetIsGoal;

        Fixture(Scenario scenario, ConcurrentHashMap<Long, NavSection[]> sections, RegionGrid grid,
                BlockPos startFloor, BlockPos goalFloor, BotCaps caps, boolean targetIsGoal) {
            this.scenario = scenario;
            this.sections = sections;
            this.grid = grid;
            this.startFloor = startFloor;
            this.goalFloor = goalFloor;
            this.caps = caps;
            this.minY = MINY;
            this.targetIsGoal = targetIsGoal;
            int srx = RegionAddress.regionX(startFloor.getX(), 0);
            int sry = RegionAddress.regionY(startFloor.getY(), 0, MINY);
            int srz = RegionAddress.regionZ(startFloor.getZ(), 0);
            int grx = RegionAddress.regionX(goalFloor.getX(), 0);
            int gry = RegionAddress.regionY(goalFloor.getY(), 0, MINY);
            int grz = RegionAddress.regionZ(goalFloor.getZ(), 0);
            this.box = RegionPathfinder.RegionBox.around(srx, sry, srz, grx, gry, grz, 3);
            this.mine = RegionMineModel.DEFAULT;   // == RegionMineModel.from(null)
            this.place = RegionPlaceModel.DEFAULT; // == RegionPlaceModel.from(null)
        }

        /**
         * The whole live-gameplay path, assembled the way {@code PathPlan} assembles it internally (PathPlan is
         * {@code ServerLevel}-welded, so this drives the pieces, not the driver): (1) the reverse-Dijkstra
         * cost-to-goal field (with the goal dig-flood multi-source seed); (2) the region skeleton (with the
         * start-fragment flood + virtual goal); (3) the region-informed window block A* over a fresh
         * {@link NavGridView} (the per-search view construction the driver pays on every replan). Returns the
         * block plan (possibly a partial); the region grid's built leaves persist across calls exactly as the
         * per-dimension {@code RegionGrid.of(level)} does across replans.
         */
        public BlockPathPlan search() {
            RegionCostField field = RegionPathfinder.costToGoalField(grid, minY, goalFloor, startFloor,
                    caps.canBreak(), caps.canPlace(), caps.safeFallDistance(), mine, place, box);
            RegionPathPlan skeleton = RegionPathfinder.plan(null, grid, startFloor, goalFloor, caps, mine);
            BlockPos target = windowTarget(skeleton);
            RegionBound cuboidCap = cuboidCapBox(startFloor, target);
            NavGridView view = new NavGridView(minY, sections);
            return BlockPathfinder.findPath(view, startFloor, target, caps, null, cuboidCap, null,
                    MovementContext.MODE_STANDING, null, field);
        }

        /** The block-A* target: the real goal in-window, else the {@code WINDOW_STEP}-th skeleton waypoint's
         *  stored portal cell, projected down to a real standable FLOOR cell. The portal feet cell sits on the
         *  field's goalward path (the block search, guided by the goal-rooted region field, walks through it), so
         *  targeting it — rather than a region-center the field routes around — is what actually lets {@code isGoal}
         *  fire (an approximation of {@code PathPlan.windowTarget} + {@code projectToStandableFloor}). */
        BlockPos windowTarget(RegionPathPlan skeleton) {
            if (targetIsGoal || skeleton == null || skeleton.isEmpty()) {
                return goalFloor;
            }
            int i = Math.min(WINDOW_STEP, skeleton.size() - 1);
            BlockPos portalFeet = skeleton.portalCell(i);
            BlockPos feet = portalFeet != null ? portalFeet : skeleton.centerOf(i);
            return projectToFloor(feet);
        }

        /** Scan straight down from {@code feet} to the first SOLID cell (the floor the bot stands on) in the
         *  authored sections; {@code feet.below()} if the column has no solid (shouldn't happen in-corridor). */
        private BlockPos projectToFloor(BlockPos feet) {
            NavSection[] col = sections.get(NavStore.key(feet.getX() >> 4, feet.getZ() >> 4));
            if (col != null) {
                for (int y = feet.getY(); y >= minY; y--) {
                    int ry = (y - minY) >> 4;
                    if (ry < 0 || ry >= col.length || col[ry] == null) continue;
                    int nav = col[ry].getNavtype(feet.getX() & 15, (y - minY) & 15, feet.getZ() & 15);
                    if (!NavBlock.isPassable(NavBlock.descriptor((short) nav))) {
                        return new BlockPos(feet.getX(), y, feet.getZ());
                    }
                }
            }
            return feet.below();
        }
    }

    /** {@code PathPlan.cuboidCapBox} — the macro-cuboid growth cap: the start↔target box padded by the margin. */
    private static RegionBound cuboidCapBox(BlockPos a, BlockPos b) {
        int m = CUBOID_CAP_MARGIN;
        return new RegionBound(
                Math.min(a.getX(), b.getX()) - m, Math.max(a.getX(), b.getX()) + m,
                Math.min(a.getY(), b.getY()) - m, Math.max(a.getY(), b.getY()) + m,
                Math.min(a.getZ(), b.getZ()) - m, Math.max(a.getZ(), b.getZ()) + m);
    }

    // ===================================================================================================
    // Scenario layouts
    // ===================================================================================================

    /** Build the named fixture. */
    public static Fixture build(Scenario scenario) {
        switch (scenario) {
            case GOAL_IN_WINDOW:     return goalInWindow();
            case GOAL_NOT_IN_WINDOW: return goalNotInWindow();
            default: throw new IllegalArgumentException("unknown scenario: " + scenario);
        }
    }

    /**
     * GOAL_IN_WINDOW — a flat stone floor (y=0) with a solid stone hill filling chunk (2,0) from y 1..6. The bot
     * starts on the flat ground in chunk 0 and the goal is buried at world (36,3,8) — inside the hill, ~2 regions
     * east — so the goal cell reads SOLID and the dig-flood + virtual goal fire; the block window target is the
     * real (buried) goal. Chunks −1..3 × −1..1 are flat; chunk (2,0) is the hill.
     */
    private static Fixture goalInWindow() {
        ConcurrentHashMap<Long, NavSection[]> sections = new ConcurrentHashMap<>();
        NavSection[] flat = flatColumn();
        for (int cx = -1; cx <= 3; cx++) {
            for (int cz = -1; cz <= 1; cz++) {
                sections.put(NavStore.key(cx, cz), flat);
            }
        }
        sections.put(NavStore.key(2, 0), hillColumn()); // solid y1..6 across the whole chunk

        RegionGrid grid = RegionGrid.headless(MINY, sections);
        BlockPos start = new BlockPos(8, 0, 8);   // stone floor at y=0, chunk 0
        BlockPos goal = new BlockPos(36, 3, 8);   // buried in the hill (chunk 2, y 1..6 solid)
        return new Fixture(Scenario.GOAL_IN_WINDOW, sections, grid, start, goal, BotCaps.BREAK_PLACE, true);
    }

    /**
     * GOAL_NOT_IN_WINDOW — a <b>confined</b> stone tunnel (solid rock with a 4-wide × 3-tall passage carved along
     * +X at z 6..9, y 1..3, floor y=0) running chunks 0..6 with the goal ~6 regions east at world (104,0,8). The
     * region skeleton spans the corridor and the block window target is the {@code WINDOW_STEP}-th waypoint portal
     * (well short of the goal). Confinement matters: the region field is region-granular and DOMINATES the block
     * heuristic when the target (a near waypoint) is much closer than the goal-root, so an OPEN corridor lets the
     * block search flood laterally to the node cap; a walled tunnel bounds it, exactly as real cave terrain (and
     * {@code RegionScenarios}' solid-walled corridors) does — the window-target walk we actually want to measure.
     */
    private static Fixture goalNotInWindow() {
        ConcurrentHashMap<Long, NavSection[]> sections = new ConcurrentHashMap<>();
        NavSection[] tunnel = tunnelColumn();
        for (int cx = 0; cx <= 6; cx++) {
            sections.put(NavStore.key(cx, 0), tunnel);
        }
        RegionGrid grid = RegionGrid.headless(MINY, sections);
        BlockPos start = new BlockPos(8, 0, 8);    // chunk 0, in the carved passage
        BlockPos goal = new BlockPos(104, 0, 8);   // chunk 6 (region x6) — beyond a ~3-region window
        // A no-break WALKER (not BREAK_PLACE): the headless MiningModel prices breaks at ~0, so a break-capable
        // bot would dig straight through the tunnel walls (the CLIFFS/BRIDGE caps gotcha) and flood the whole
        // solid volume to the node cap. This scenario is the window-target WALK, so a pure walker is both correct
        // and what keeps the tunnel confining.
        return new Fixture(Scenario.GOAL_NOT_IN_WINDOW, sections, grid, start, goal, BotCaps.DEFAULT, false);
    }

    // ===================================================================================================
    // NavSection authoring — shared flat columns + one hill column (mirrors PathfinderBenchmark's idiom)
    // ===================================================================================================

    /** A fresh empty section-state container (default AIR). */
    private static PalettedContainer<BlockState> newStates() {
        return new PalettedContainer<>(
                Blocks.AIR.defaultBlockState(), Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
    }

    /** Classify one 16³ section's states into a fresh {@link NavSection}. */
    private static NavSection section(PalettedContainer<BlockState> states, boolean onlyAir) {
        NavSection s = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(states, onlyAir, s.getTraversalGrid());
        return s;
    }

    /** An all-air 16³ section (the {@code onlyAir} fast path). */
    private static NavSection airSection() {
        return section(newStates(), true);
    }

    /**
     * The shared flat column (y 0..63): stone floor plane at world y=0, air above. Distinct air instances per
     * upper slot so the column-form depth sweep writes each once (no cross-slot instance sharing). Read-only, so
     * one instance backs every flat chunk key.
     */
    private static NavSection[] flatColumn() {
        BlockState stone = Blocks.STONE.defaultBlockState();
        PalettedContainer<BlockState> ground = newStates();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                ground.set(x, 0, z, stone);
            }
        }
        NavSection[] col = { section(ground, false), airSection(), airSection(), airSection() };
        NavSectionBuilder.computeDepth(col);
        return col;
    }

    /**
     * The GOAL_NOT_IN_WINDOW tunnel column: section 0 is solid stone EXCEPT a carved passage at local z 6..9,
     * y 1..3 (floor y=0 solid, ceiling y≥4 solid, z-walls solid), so the block search is confined to the passage;
     * sections 1..3 air (sealed off below by the solid ceiling). Shared across every corridor chunk.
     */
    private static NavSection[] tunnelColumn() {
        BlockState stone = Blocks.STONE.defaultBlockState();
        PalettedContainer<BlockState> low = newStates(); // section 0 = world y 0..15
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 0; y < 16; y++) {
                    boolean carve = z >= 6 && z <= 9 && y >= 1 && y <= 3; // the 4-wide × 3-tall passage
                    if (!carve) {
                        low.set(x, y, z, stone);
                    }
                }
            }
        }
        NavSection[] col = { section(low, false), airSection(), airSection(), airSection() };
        NavSectionBuilder.computeDepth(col);
        return col;
    }

    // ===================================================================================================
    // Field-build bench world (RegionFieldBuildBenchmark) — a flat world big enough for a 10³-region box
    // ===================================================================================================

    /** Field-bench world: authored chunk extent (inclusive), sections per column, solid/floor layout. */
    private static final int FIELD_CHUNK_MIN = 3;
    private static final int FIELD_CHUNK_MAX = 14;
    private static final int FIELD_SECTIONS = 11;      // world y 0..175 (region ry 0..10)
    private static final int FIELD_SOLID_TOP = 4;      // sections 0..4 fully solid stone
    private static final int FIELD_FLOOR_SECTION = 5;  // stone plane at its bottom layer (y=80), air above
    private static final int FIELD_CENTER = 8 * 16 + 8; // world x/z of the goal column (chunk (8,8))

    /**
     * The {@code RegionFieldBuildBenchmark} world (PERF-AUDIT-region-field.md §6): a flat surface world whose
     * columns are {@value #FIELD_SECTIONS} sections tall — sections 0..{@value #FIELD_SOLID_TOP} fully solid
     * stone (the underground), section {@value #FIELD_FLOOR_SECTION} a stone floor plane at world y=80 with air
     * above (the surface), sections 6..10 open air — authored over chunks
     * {@value #FIELD_CHUNK_MIN}..{@value #FIELD_CHUNK_MAX}² so that a field box of up to <b>10³ regions</b>
     * centered on either goal region is entirely RESIDENT. Residency matters: an unresident region inside the
     * box would fall back to the optimistic-AIR unbuilt default ({@code RegionGrid.rebuildLeaf}'s no-op path),
     * and the reverse Dijkstra would flood fake air instead of real terrain — production fields are built
     * around a loaded bot, so every box region is resident there too.
     *
     * <p>Two goal FLOOR cells (the solid block stood on — the same convention {@code PathPlan} hands
     * {@code costToGoalField}):
     * <ul>
     *   <li><b>surface</b> — the floor-plane cell (136,80,136), region (8,5,8): the common window-target case
     *       (the target floor cell is solid, its feet pocket one cell up, so the goal dig-flood seeds
     *       trivially).</li>
     *   <li><b>buried</b> — (136,77,136), embedded in the solid underground (region (8,4,8), 3 blocks below
     *       the floor plane): the buried-goal case, whose dig-flood BFS (§2 item 2, the boxed-Integer path)
     *       must dig ~4 cells up to the surface pocket.</li>
     * </ul>
     * One shared read-only column instance backs every chunk key (the {@link #flatColumn()} idiom).
     */
    public static FieldWorld fieldWorld() {
        ConcurrentHashMap<Long, NavSection[]> sections = new ConcurrentHashMap<>();
        NavSection[] col = fieldColumn();
        for (int cx = FIELD_CHUNK_MIN; cx <= FIELD_CHUNK_MAX; cx++) {
            for (int cz = FIELD_CHUNK_MIN; cz <= FIELD_CHUNK_MAX; cz++) {
                sections.put(NavStore.key(cx, cz), col);
            }
        }
        RegionGrid grid = RegionGrid.headless(MINY, sections);
        return new FieldWorld(sections, grid, MINY,
                new BlockPos(FIELD_CENTER, FIELD_FLOOR_SECTION * 16, FIELD_CENTER),      // surface floor (y=80)
                new BlockPos(FIELD_CENTER, FIELD_FLOOR_SECTION * 16 - 3, FIELD_CENTER),  // buried (y=77, solid)
                FIELD_CHUNK_MIN, FIELD_CHUNK_MAX, FIELD_SECTIONS);
    }

    /** The built field-bench world: the shared section map, the region grid over it, and the two goal FLOOR
     *  cells (see {@link #fieldWorld()}). {@code chunkMin/chunkMax/sectionCount} describe the authored extent
     *  so callers can assert a candidate {@code RegionBox} is fully resident. */
    public static final class FieldWorld {
        public final ConcurrentHashMap<Long, NavSection[]> sections;
        public final RegionGrid grid;
        public final int minY;
        public final BlockPos surfaceGoalFloor;
        public final BlockPos buriedGoalFloor;
        public final int chunkMin, chunkMax;
        public final int sectionCount;

        FieldWorld(ConcurrentHashMap<Long, NavSection[]> sections, RegionGrid grid, int minY,
                   BlockPos surfaceGoalFloor, BlockPos buriedGoalFloor,
                   int chunkMin, int chunkMax, int sectionCount) {
            this.sections = sections;
            this.grid = grid;
            this.minY = minY;
            this.surfaceGoalFloor = surfaceGoalFloor;
            this.buriedGoalFloor = buriedGoalFloor;
            this.chunkMin = chunkMin;
            this.chunkMax = chunkMax;
            this.sectionCount = sectionCount;
        }
    }

    /**
     * The field-bench column (see {@link #fieldWorld()}): sections 0..{@value #FIELD_SOLID_TOP} fully solid
     * stone, section {@value #FIELD_FLOOR_SECTION} a floor plane (stone at local y 0, air above), the rest air.
     * Distinct section instances per slot (the depth sweep writes each once); the whole column is read-only and
     * shared across every chunk key.
     */
    private static NavSection[] fieldColumn() {
        BlockState stone = Blocks.STONE.defaultBlockState();
        NavSection[] col = new NavSection[FIELD_SECTIONS];
        for (int i = 0; i <= FIELD_SOLID_TOP; i++) {
            PalettedContainer<BlockState> solid = newStates();
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        solid.set(x, y, z, stone);
                    }
                }
            }
            col[i] = section(solid, false);
        }
        PalettedContainer<BlockState> floor = newStates();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                floor.set(x, 0, z, stone);
            }
        }
        col[FIELD_FLOOR_SECTION] = section(floor, false);
        for (int i = FIELD_FLOOR_SECTION + 1; i < FIELD_SECTIONS; i++) {
            col[i] = airSection();
        }
        NavSectionBuilder.computeDepth(col);
        return col;
    }

    /**
     * The GOAL_IN_WINDOW hill column (chunk (2,0)): stone floor at y=0 PLUS a solid stone fill of the whole
     * chunk footprint from y=1 to y=6 (the buried-goal rock), air y 7..63. The bot digs into its west face to
     * reach the buried goal at (36,3,8).
     */
    private static NavSection[] hillColumn() {
        BlockState stone = Blocks.STONE.defaultBlockState();
        PalettedContainer<BlockState> low = newStates(); // section 0 = world y 0..15
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                low.set(x, 0, z, stone);              // ground plane
                for (int y = 1; y <= 6; y++) {
                    low.set(x, y, z, stone);          // the hill (buried-goal rock)
                }
            }
        }
        NavSection[] col = { section(low, false), airSection(), airSection(), airSection() };
        NavSectionBuilder.computeDepth(col);
        return col;
    }
}
