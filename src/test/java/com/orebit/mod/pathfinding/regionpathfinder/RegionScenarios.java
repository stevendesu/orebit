package com.orebit.mod.pathfinding.regionpathfinder;

import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.worldmodel.hpa.CostPyramid;
import com.orebit.mod.worldmodel.hpa.FragmentBuilder;
import com.orebit.mod.worldmodel.hpa.PyramidMerger;
import com.orebit.mod.worldmodel.hpa.RegionAddress;
import com.orebit.mod.worldmodel.hpa.RegionFragments;
import com.orebit.mod.worldmodel.hpa.RegionGrid;

import net.minecraft.core.BlockPos;

/**
 * Headless synthetic region-tier fixtures — the region-tier analog of {@code PathfinderBenchmark}'s synthetic
 * {@code NavGridView(minY, chunks)} seam (PERF-DESIGN-region-dig-through.md §7, §8 step 1). Constructs a
 * {@link RegionGrid} + a start/goal cell for each named scenario so {@link RegionPathfinder#plan}/
 * {@link RegionPathfinder#planWithin} can be driven <b>with no live {@code ServerLevel}</b> — the substrate
 * shared by the future region-tier JMH benchmark and the expected-improvement unit tests.
 *
 * <h2>Build strategy — real fragments, no Minecraft (option B)</h2>
 * Each leaf region's connectivity record is produced by the <b>real</b> {@link FragmentBuilder#build} flood-fill
 * over hand-authored {@code passable}/{@code standable} masks — the exact idiom {@code RegionPathfinderFragmentTest}
 * already uses, and the same pure core the production leaf path ({@link com.orebit.mod.worldmodel.hpa.FragmentLeafComputer})
 * hands its masks to. So a fixture region exercises the genuine occupiable-component flood, occupiability filter,
 * cap, and per-face footprint extraction — the records the region A* reads are byte-identical to what a live
 * chunk classify would produce for equivalent terrain, but the fixture needs no {@code Bootstrap}/registry.
 *
 * <p>This was chosen over (A) hand-authoring {@link RegionFragments} footprints via the package-private setters
 * (which bypasses the real flood-fill → less faithful) and over the strictly-more-faithful variant of building a
 * synthetic {@code NavSection} and running {@link com.orebit.mod.worldmodel.hpa.FragmentLeafComputer} over it:
 * that path adds only coverage of the MC <i>navtype read</i> (a block-tier concern) at the cost of pinning the
 * fixture to the bootstrapped-MC JVM, for no additional coverage of the region tier's own read path. <b>No new
 * production hook was added</b> — the fixture composes existing seams: {@link RegionGrid#headless},
 * {@link RegionGrid#pyramid}, {@link CostPyramid#rowFor}/{@link CostPyramid#ensureFragments}/
 * {@link CostPyramid#setBuilt}, {@link FragmentBuilder#build}, and {@link PyramidMerger#mergeLevelFragments}.
 *
 * <h2>Scenarios (PERF-DESIGN-region-dig-through.md §8.2)</h2>
 * <ul>
 *   <li><b>OPEN_CAVERN</b> — a lateral crossing of five mostly-air floor regions in a row. Exercises the §1b
 *       "walk edges cost ~1" shape (every lateral crossing is near-free).</li>
 *   <li><b>SEALED_DIG</b> — the {@code /bot gather} repro (§1): the goal region is a buried {@code SOLID} block
 *       directly below the start region, with solid between and a cheaper-looking air-shaft detour around it. The
 *       start's floor fragment does not reach its own −Y face, so pre-fix no dig-down edge into the goal exists
 *       (the §1a connectivity hole) and the A* must take the winding down→over route; the §3 dig-through fix lets
 *       it dig straight down.</li>
 *   <li><b>MULTI_FRAGMENT</b> — one region holding three vertically-separated tunnels (three fragments), sealed
 *       in rock; the route from the bottom tunnel to the top runs through intra-region mine edges.</li>
 *   <li><b>LONG_CASCADE</b> — a floor corridor long enough (Chebyshev &gt; {@link RegionPathfinder#maxChebAtLevel}
 *       at level 0) that {@link RegionPathfinder#chooseCapSafeLevel} forces a coarse pyramid level; the fixture
 *       rolls the pyramid up via {@link PyramidMerger#mergeLevelFragments} and {@link Fixture#plan} drives
 *       {@link RegionPathfinder#planWithin} at that level.</li>
 *   <li><b>ZERO_CAP</b> — a no-break/no-place bot ({@link BotCaps#DEFAULT}) whose direct route is walled by
 *       unmineable solid, forcing an all-walkable L-shaped ramp detour (no dig, no pillar).</li>
 * </ul>
 *
 * <h2>House style</h2>
 * Static fixture factory producing a smart {@link Fixture} (it knows how to run its own plan). Masks are the
 * canonical {@code (y<<8)|(z<<4)|x} section-local linear index (as {@link FragmentBuilder} requires); solid cells
 * are priced as stone (per-cell hardness 8) exactly as {@code RegionPathfinderFragmentTest} does.
 */
public final class RegionScenarios {

    private RegionScenarios() {}

    /** The named region-tier fixtures (PERF-DESIGN-region-dig-through.md §8.2). */
    public enum Scenario { OPEN_CAVERN, SEALED_DIG, MULTI_FRAGMENT, LONG_CASCADE, ZERO_CAP }

    /** Leaf side in cells (16³ = one {@link com.orebit.mod.worldmodel.pathing.NavSection}). */
    private static final int G = RegionAddress.LEAF_SIZE;
    private static final int CELLS = G * G * G;

    /** Dimension floor for every fixture: region {@code ry} 0 spans world y 0..15 (as in the region tests). */
    private static final int MINY = 0;

    /** Per-cell quantized hardness charged to a solid (non-passable) cell — stone (≈ round(1.5×5)). */
    private static final int STONE_HARDNESS = 8;

    /** Canonical section-local linear index for local coords 0..15 ({@link FragmentBuilder}'s {@code G==16} form). */
    private static int idx(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }

    /**
     * A built scenario: the seeded grid + the start/goal <b>feet</b> cells + the bot capability the scenario is
     * designed around. A smart object — it runs its own plan through the appropriate entry (direct level-0 for
     * most scenarios; {@link RegionPathfinder#planWithin} at {@link #planLevel} for the cascade scenario).
     */
    public static final class Fixture {
        /** Which scenario this is. */
        public final Scenario scenario;
        /** The hand-seeded headless region grid (no {@code ServerLevel}). */
        public final RegionGrid grid;
        /** The bot's start floor/feet cell (world coords). */
        public final BlockPos start;
        /** The goal floor/feet cell (world coords) — buried in solid for SEALED_DIG. */
        public final BlockPos goal;
        /** The capability the scenario targets (e.g. {@link BotCaps#DEFAULT} for ZERO_CAP). Overridable in {@link #plan(BotCaps)}. */
        public final BotCaps caps;
        /** The dimension floor the grid was built at (for {@link RegionPathfinder#planWithin}). */
        public final int minY;
        /**
         * The pyramid level {@link #plan()} searches at: {@code 0} = the direct level-0 {@link RegionPathfinder#plan};
         * {@code >0} = a coarse {@link RegionPathfinder#planWithin} at that level (LONG_CASCADE only).
         */
        public final int planLevel;

        Fixture(Scenario scenario, RegionGrid grid, BlockPos start, BlockPos goal, BotCaps caps, int planLevel) {
            this.scenario = scenario;
            this.grid = grid;
            this.start = start;
            this.goal = goal;
            this.caps = caps;
            this.minY = MINY;
            this.planLevel = planLevel;
        }

        /** Run the scenario's plan with its own {@link #caps}. */
        public RegionPathPlan plan() {
            return plan(caps);
        }

        /** Run the scenario's plan with an overriding {@code botCaps} (e.g. to A/B break vs no-break). */
        public RegionPathPlan plan(BotCaps botCaps) {
            if (planLevel <= 0) {
                return RegionPathfinder.plan(null, grid, start, goal, botCaps);
            }
            // Coarse cascade entry: the sub-goal and real goal are both the true goal (no cascade above this).
            return RegionPathfinder.planWithin(planLevel, grid, minY, start, goal, goal, botCaps, null);
        }

        /** Always run the direct level-0 {@link RegionPathfinder#plan}, regardless of {@link #planLevel}. */
        public RegionPathPlan planDirect(BotCaps botCaps) {
            return RegionPathfinder.plan(null, grid, start, goal, botCaps);
        }
    }

    /** Build the named fixture. */
    public static Fixture build(Scenario scenario) {
        switch (scenario) {
            case OPEN_CAVERN:   return openCavern();
            case SEALED_DIG:    return sealedDig();
            case MULTI_FRAGMENT:return multiFragment();
            case LONG_CASCADE:  return longCascade();
            case ZERO_CAP:      return zeroCap();
            default: throw new IllegalArgumentException("unknown scenario: " + scenario);
        }
    }

    // ===================================================================================================
    // Scenario layouts
    // ===================================================================================================

    /**
     * OPEN_CAVERN — five mostly-air floor regions in a row along +X (rx 0..4, ry 1, rz 0), walled in Z with
     * solid so it is a clean cavern corridor. The bot walks straight across; every crossing is a cheap walk edge
     * (the §1b shape). Start in region 0, goal in region 4.
     */
    private static Fixture openCavern() {
        RegionGrid grid = RegionGrid.headless(MINY);
        for (int rx = 0; rx <= 4; rx++) {
            seedCavernFloor(grid, rx, 1, 0);
            seedSolid(grid, rx, 1, -1);   // Z walls keep the corridor from leaking into free unbuilt AIR
            seedSolid(grid, rx, 1, 1);
        }
        BlockPos start = feet(0, 1, 0);   // world (8, 17, 8)
        BlockPos goal = feet(4, 1, 0);    // world (72, 17, 8)
        return new Fixture(Scenario.OPEN_CAVERN, grid, start, goal, BotCaps.BREAK_PLACE, 0);
    }

    /**
     * SEALED_DIG — the {@code /bot gather} repro (PERF-DESIGN-region-dig-through.md §1). A solid rock volume
     * (rx −1..2, ry −1..3, rz −1..1, all {@code SOLID}) with three carved regions:
     * <pre>
     *   A = (0,2,0)  FLOOR   — the start region (bot stands here; feet world (8,33,8))
     *   C = (0,1,0)  SOLID   — the buried-ore GOAL region, directly BELOW A (goal world (8,24,8))
     *   shaft (1,2,0) AIR and (1,1,0) AIR — a descent chute one region over
     * </pre>
     * A's floor fragment does not reach A's −Y face, so pre-fix there is <b>no dig-down edge from A into C</b>
     * (the §1a connectivity hole); the only route to C is the detour A →(walk/pillar)→ shaft (1,2,0) →(fall)→
     * (1,1,0) →(mine −X)→ C. The §3 dig-through fix adds A's straight −Y dig into the goal region.
     */
    private static Fixture sealedDig() {
        RegionGrid grid = RegionGrid.headless(MINY);
        fillSolidBox(grid, -1, 2, -1, 3, -1, 1);   // the enclosing rock
        seedCavernFloor(grid, 0, 2, 0);            // A — start region
        // C = (0,1,0) stays SOLID (the buried goal), already filled by the box.
        seedAir(grid, 1, 2, 0);                    // descent shaft, upper
        seedAir(grid, 1, 1, 0);                    // descent shaft, lower (mines −X straight into C)
        BlockPos start = feet(0, 2, 0);            // world (8, 33, 8)
        BlockPos goal = new BlockPos(8, 24, 8);    // buried in C = (0,1,0)  (ry1 → world y16..31)
        return new Fixture(Scenario.SEALED_DIG, grid, start, goal, BotCaps.BREAK_PLACE, 0);
    }

    /**
     * MULTI_FRAGMENT — one region (0,0,0) holding three vertically-separated 2-tall tunnels (feet y 2 / 7 / 12),
     * so it floods to three fragments, sealed in solid rock on all six sides. Start in the bottom tunnel, goal in
     * the top; the route runs through intra-region mine edges (a dig between pockets), never leaving the region.
     */
    private static Fixture multiFragment() {
        RegionGrid grid = RegionGrid.headless(MINY);
        boolean[] passable = new boolean[CELLS];
        boolean[] standable = new boolean[CELLS];
        carveTunnelX(passable, standable, 2);
        carveTunnelX(passable, standable, 7);
        carveTunnelX(passable, standable, 12);
        seed(grid, 0, 0, 0, passable, standable);
        // Seal in known rock on all six sides (an unbuilt neighbour reads as FREE optimistic AIR — a cave must
        // be walled with explicit solid so the mine edges between pockets are the chosen route).
        seedSolid(grid, 1, 0, 0);  seedSolid(grid, -1, 0, 0);
        seedSolid(grid, 0, 0, 1);  seedSolid(grid, 0, 0, -1);
        seedSolid(grid, 0, 1, 0);  seedSolid(grid, 0, -1, 0);
        BlockPos start = new BlockPos(8, 2, 8);    // bottom tunnel
        BlockPos goal = new BlockPos(8, 12, 8);    // top tunnel
        return new Fixture(Scenario.MULTI_FRAGMENT, grid, start, goal, BotCaps.BREAK_PLACE, 0);
    }

    /**
     * LONG_CASCADE — a floor corridor of thirteen regions along +X (rx 0..12, ry 1, rz 0), Z-walled with solid,
     * long enough that {@link RegionPathfinder#chooseCapSafeLevel} picks a <b>coarse</b> level (level-0 Chebyshev
     * 12 &gt; {@link RegionPathfinder#maxChebAtLevel}(0) = 8). The pyramid is rolled up bottom-up via
     * {@link PyramidMerger#mergeLevelFragments} so the coarse nodes are real merges of the leaves, and
     * {@link Fixture#plan} drives {@link RegionPathfinder#planWithin} at the cap-safe level.
     */
    private static Fixture longCascade() {
        RegionGrid grid = RegionGrid.headless(MINY);
        for (int rx = 0; rx <= 12; rx++) {
            seedCavernFloor(grid, rx, 1, 0);
            seedSolid(grid, rx, 1, -1);
            seedSolid(grid, rx, 1, 1);
        }
        rollUpPyramid(grid);
        BlockPos start = feet(0, 1, 0);            // world (8, 17, 8)
        BlockPos goal = new BlockPos(200, 17, 8);  // rx 12 (world x192..207), 12 regions away → coarse level
        int level = RegionPathfinder.chooseCapSafeLevel(
                RegionAddress.regionX(start.getX(), 0), RegionAddress.regionY(start.getY(), 0, MINY),
                RegionAddress.regionZ(start.getZ(), 0),
                RegionAddress.regionX(goal.getX(), 0), RegionAddress.regionY(goal.getY(), 0, MINY),
                RegionAddress.regionZ(goal.getZ(), 0));
        return new Fixture(Scenario.LONG_CASCADE, grid, start, goal, BotCaps.BREAK_PLACE, level);
    }

    /**
     * ZERO_CAP — a no-break/no-place bot ({@link BotCaps#DEFAULT}) whose direct crossing is walled by unmineable
     * solid, forcing an all-walkable L-shaped ramp. A solid box (rx −1..3, ry 0..2, rz −1..2) with five carved
     * floor regions:
     * <pre>
     *   A=(0,1,0) → (0,1,1) → (1,1,1) → (2,1,1) → G=(2,1,0)
     * </pre>
     * The direct region (1,1,0) stays solid (the bot cannot mine it), so the walk-only route is the L. Start in
     * A, goal in G.
     */
    private static Fixture zeroCap() {
        RegionGrid grid = RegionGrid.headless(MINY);
        fillSolidBox(grid, -1, 3, 0, 2, -1, 2);
        seedCavernFloor(grid, 0, 1, 0);   // A — start
        seedCavernFloor(grid, 0, 1, 1);
        seedCavernFloor(grid, 1, 1, 1);
        seedCavernFloor(grid, 2, 1, 1);
        seedCavernFloor(grid, 2, 1, 0);   // G — goal   ((1,1,0) between A and G stays SOLID)
        BlockPos start = feet(0, 1, 0);   // world (8, 17, 8)
        BlockPos goal = feet(2, 1, 0);    // world (40, 17, 8)
        return new Fixture(Scenario.ZERO_CAP, grid, start, goal, BotCaps.DEFAULT, 0);
    }

    // ===================================================================================================
    // Seed helpers — real FragmentBuilder flood over synthetic masks (no Minecraft)
    // ===================================================================================================

    /**
     * Seed a level-0 leaf region's {@link RegionFragments} from raw masks via the real {@link FragmentBuilder},
     * computing the tallies it needs (solid cells priced as stone). Overwrites the row if already seeded.
     */
    private static void seed(RegionGrid grid, int rx, int ry, int rz, boolean[] passable, boolean[] standable) {
        int passCount = 0, standCount = 0, solidCount = 0;
        long hardnessSumSolid = 0;
        for (int i = 0; i < CELLS; i++) {
            if (passable[i]) {
                passCount++;
            } else {
                solidCount++;
                hardnessSumSolid += STONE_HARDNESS;
            }
            if (standable[i]) standCount++;
        }
        CostPyramid pyr = grid.pyramid();
        int row = pyr.rowFor(0, rx, ry, rz);
        RegionFragments rf = pyr.ensureFragments(0, row);
        FragmentBuilder.build(passable, standable, G,
                passCount, standCount, 0, hardnessSumSolid, solidCount, rf);
        pyr.setBuilt(0, row, true);
    }

    /** A uniform {@code KIND_SOLID} region (all-false masks) — a wall of known rock. */
    private static void seedSolid(RegionGrid grid, int rx, int ry, int rz) {
        seed(grid, rx, ry, rz, new boolean[CELLS], new boolean[CELLS]);
    }

    /** A floorless uniform {@code KIND_AIR} region (all passable, none standable) — a fall/pillar chute. */
    private static void seedAir(RegionGrid grid, int rx, int ry, int rz) {
        boolean[] passable = new boolean[CELLS];
        java.util.Arrays.fill(passable, true);
        seed(grid, rx, ry, rz, passable, new boolean[CELLS]);
    }

    /**
     * A mostly-air cavern region with a full floor: standable slab at local y=0, passable air y 1..14 across the
     * whole footprint. One occupiable fragment reaching the four horizontal faces (feet band Y 1..14) but neither
     * ±Y face — so it walks to its horizontal neighbours but has no dig-down/up edge of its own (the §1a hole).
     */
    private static void seedCavernFloor(RegionGrid grid, int rx, int ry, int rz) {
        boolean[] passable = new boolean[CELLS];
        boolean[] standable = new boolean[CELLS];
        for (int x = 0; x < G; x++) {
            for (int z = 0; z < G; z++) {
                standable[idx(x, 0, z)] = true;
                for (int y = 1; y <= 14; y++) {
                    passable[idx(x, y, z)] = true;
                }
            }
        }
        seed(grid, rx, ry, rz, passable, standable);
    }

    /** A 2-tall tunnel spanning all X and Z at feet {@code feetY} (touches ±X and ±Z) — one occupiable pocket. */
    private static void carveTunnelX(boolean[] passable, boolean[] standable, int feetY) {
        for (int x = 0; x < G; x++) {
            for (int z = 0; z < G; z++) {
                standable[idx(x, feetY - 1, z)] = true;
                passable[idx(x, feetY, z)] = true;
                passable[idx(x, feetY + 1, z)] = true;
            }
        }
    }

    /** Fill an inclusive region box with uniform {@code SOLID} — the enclosing rock a cave is carved out of. */
    private static void fillSolidBox(RegionGrid grid, int rxLo, int rxHi, int ryLo, int ryHi, int rzLo, int rzHi) {
        for (int rx = rxLo; rx <= rxHi; rx++) {
            for (int ry = ryLo; ry <= ryHi; ry++) {
                for (int rz = rzLo; rz <= rzHi; rz++) {
                    seedSolid(grid, rx, ry, rz);
                }
            }
        }
    }

    /** Roll the fragment pyramid up bottom-up (the load-time/test path) so coarse-level nodes are real merges. */
    private static void rollUpPyramid(RegionGrid grid) {
        CostPyramid pyr = grid.pyramid();
        for (int level = 0; level < RegionAddress.MAX_COARSE_LEVEL; level++) {
            PyramidMerger.mergeLevelFragments(pyr, level);
        }
    }

    /** The feet cell at the centre of a floor region's occupiable band (local (8,1,8) → world). */
    private static BlockPos feet(int rx, int ry, int rz) {
        return new BlockPos((rx << 4) + 8, MINY + (ry << 4) + 1, (rz << 4) + 8);
    }
}
