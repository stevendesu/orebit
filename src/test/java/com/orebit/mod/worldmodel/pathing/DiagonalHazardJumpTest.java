package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * Headless proof of the ISSUE-3c <b>diagonal</b> jump-over-a-hazard rule (FLAT diagonals only). Before the
 * fix {@code DiagonalParkour.scanDirection} admitted a gap cell only when strictly {@code passable} (air) and
 * ended the direction at the first {@code standable} cell, so a diagonal that crossed a fluid/hazard was
 * never offered — the bot pathed INTO the lava (common in the nether). The fix brings cardinal
 * {@code Parkour}'s two admissions to the diagonal: a non-standable gap cell is overflown when
 * {@code overJumpable} (fluids/short décor, not fences), and a standable obstacle worth flying over
 * ({@code damaging || topY < 12 || isSlow}) is treated as an overflyable column while its own body prism
 * {@code y+1..y+3} — and every swept corner column's — is proven strictly {@code passable} by
 * {@code verifyArc}/{@code cornerColumnCost}, so a 2-tall hazard column still rejects the jump.
 *
 * <p>The motivating scene is the owner's nether corner-cut: a 3×3 filled with lava minus two opposite solid
 * corners ({@code SLL / LLL / LLS}), start on one solid corner, goal on the other, cleared by a single
 * {@code √2} (1-gap) diagonal jump — centre gap cell AND all four swept corner columns lava.
 *
 * <p>Convention (shared with {@code DiagonalParkourTest} / {@code ParkourHazardJumpTest}): a sealed stone
 * section, floors at {@code y=5}, bodies {@code y=6..9} carved to air ONLY over cells the bot may occupy or
 * the arc sweeps — surrounding stone keeps a solid body, so it is untraversable and every non-diagonal
 * detour is sealed. Diagonal is {@code +x+z}: takeoff floor {@code (5,5,5)}, gap cells {@code (5+t,5,5+t)},
 * landing {@code (6+g,5,6+g)}; the swept corners of transition {@code t→t+1} are {@code (6+t,5,5+t)} and
 * {@code (5+t,5,6+t)}. Lives in this package to reach {@code NavGridView}'s package-private ctor.
 */
class DiagonalHazardJumpTest {

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

    private static final RegionBound BOUND = new RegionBound(0, 15, 0, 15, 0, 15);

    // ---- (1) the SLL / LLL / LLS nether corner-cut: a √2 jump over lava, corners lava too --------------

    @Test
    void lavaCornerCut_diagonalParkoursCornerToCorner() {
        // Centre gap cell (6,6) lava AND all four swept corners lava; only the two solid corners (5,5)/(7,7)
        // are standable. The ONLY route is one diagonal jump — walking any cardinal/diagonal step lands in
        // lava (non-standable) and the surrounding bodies are sealed.
        NavGridView grid = diagCourse(1, Blocks.LAVA.defaultBlockState(),
                Blocks.LAVA.defaultBlockState(), null);
        BlockPathPlan plan = search(grid, 5, 5, 5, 7, 5, 7);

        assertNotNull(plan, "the bot must diagonal-jump corner-to-corner over the lava (was: paths into lava)");
        assertEquals(1, count(plan, MovementRegistry.DIAGONAL_PARKOUR),
                "the corner-cut is exactly one DiagonalParkour waypoint");
        assertReaches(plan, 7, 6, 7);
        assertFalse(visitsFloor(plan, 6, 5, 6), "the plan must never stand on the lava centre cell (6,5,6)");
    }

    // ---- (2) magma: a standable DAMAGING obstacle, jumped rather than walked-over ---------------------

    @Test
    void magmaCentre_diagonalParkoursOver_neverWalksOntoTheMagma() {
        // Centre (6,6) MAGMA (standable, damaging) with lava corners. Walking diagonally onto the magma
        // costs floorHazardCost (100) — far dearer than one jump — so A* flies over via the damaging trigger.
        NavGridView grid = diagCourse(1, Blocks.MAGMA_BLOCK.defaultBlockState(),
                Blocks.LAVA.defaultBlockState(), null);
        BlockPathPlan plan = search(grid, 5, 5, 5, 7, 5, 7);

        assertNotNull(plan, "a route to the opposite corner must exist");
        assertTrue(contains(plan, MovementRegistry.DIAGONAL_PARKOUR),
                "the magma centre should be JUMPED (damaging trigger), not walked");
        assertReaches(plan, 7, 6, 7);
        assertFalse(visitsFloor(plan, 6, 5, 6), "the plan must NOT stand on the magma centre (6,5,6)");
    }

    // ---- (3) slow floors: a 2-gap of soul-sand / honey, jumped when the drag exceeds the jump ----------

    @Test
    void soulSandSpan_diagonalParkoursOverTheSlowFloor() {
        // Two soul-sand gap cells (6,6)+(7,7), landing (8,8); lava corners. Walking drags across both at the
        // 0.4 speed factor (2.5× per diagonal step) — dearer than one g=2 diagonal jump over them.
        NavGridView grid = diagCourse(2, Blocks.SOUL_SAND.defaultBlockState(),
                Blocks.LAVA.defaultBlockState(), null);
        BlockPathPlan plan = search(grid, 5, 5, 5, 8, 5, 8);

        assertNotNull(plan, "a route to the goal must exist");
        assertTrue(contains(plan, MovementRegistry.DIAGONAL_PARKOUR),
                "the slow soul-sand span should be JUMPED (isSlow trigger) — the drag exceeds the jump");
        assertReaches(plan, 8, 6, 8);
        assertFalse(visitsFloor(plan, 6, 5, 6) || visitsFloor(plan, 7, 5, 7),
                "the plan must NOT walk the soul-sand gap cells");
    }

    @Test
    void honeySpan_diagonalParkoursOverTheSlowFloor() {
        // Honey is a FULL block (topY 16) — only the isSlow term catches it (getSpeedFactor 0.4).
        NavGridView grid = diagCourse(2, Blocks.HONEY_BLOCK.defaultBlockState(),
                Blocks.LAVA.defaultBlockState(), null);
        BlockPathPlan plan = search(grid, 5, 5, 5, 8, 5, 8);

        assertNotNull(plan, "a route to the goal must exist");
        assertTrue(contains(plan, MovementRegistry.DIAGONAL_PARKOUR),
                "the slow honey span should be JUMPED (isSlow trigger) — the drag exceeds the jump");
        assertReaches(plan, 8, 6, 8);
    }

    // ---- (4) SAFETY NEGATIVE: a 2-tall hazard column above the obstacle must reject the jump -----------

    @Test
    void twoTallLavaColumn_rejectsTheDiagonalJump() {
        // Identical to the passing lava corner-cut, but the centre gap cell also has LAVA at y+1 (a 2-tall
        // lava column). verifyArc proves the obstacle's body prism y+1..y+3 strictly passable, so the y+1
        // lava rejects — the bot would otherwise fly through a 2-tall lava column. No other route → NO PATH.
        NavGridView grid = diagCourse(1, Blocks.LAVA.defaultBlockState(),
                Blocks.LAVA.defaultBlockState(), Blocks.LAVA.defaultBlockState());
        BlockPathPlan plan = search(grid, 5, 5, 5, 7, 5, 7);

        assertNull(plan, "a 2-tall lava column above the obstacle must block the diagonal jump (prism blocked)");
    }

    // ---- (5) NEGATIVE: an ordinary walkable diagonal must NOT produce unnecessary parkour ---------------

    @Test
    void plainWalkableDiagonal_producesNoDiagonalParkour() {
        // Centre (6,6) plain STONE and stone corners — continuous walkable ground (topY 16, non-damaging,
        // non-slow). No trigger → the scan terminates at the standable cell, byte-identical to v1: the bot
        // Diagonal-walks across and NO DiagonalParkour is emitted.
        NavGridView grid = diagCourse(1, Blocks.STONE.defaultBlockState(),
                Blocks.STONE.defaultBlockState(), null);
        BlockPathPlan plan = search(grid, 5, 5, 5, 7, 5, 7);

        assertNotNull(plan, "continuous walkable ground has a route");
        assertFalse(contains(plan, MovementRegistry.DIAGONAL_PARKOUR),
                "a plain full-block diagonal is a WALK — the bot should Diagonal, never DiagonalParkour");
        assertReaches(plan, 7, 6, 7);
    }

    // ---- scene builder -------------------------------------------------------------------------------

    /**
     * A sealed diagonal course (class Javadoc): takeoff floor {@code (5,5,5)} stone, {@code g} gap cells
     * {@code (5+t,5,5+t), t=1..g} floored with {@code gapFloor}, landing {@code (6+g,5,6+g)} stone; every
     * swept corner column of a transition floored with {@code cornerFloor}. Bodies {@code y=6..9} are carved
     * to air over the takeoff, gap cells, landing, and all corners; everything else stays solid stone (its
     * body blocks the bot, sealing all detours). {@code aboveGap != null} additionally sets that block at
     * {@code y=6} over every gap cell — the 2-tall-hazard-column safety negative.
     */
    private static NavGridView diagCourse(int g, BlockState gapFloor, BlockState cornerFloor,
            BlockState aboveGap) {
        PalettedContainer<BlockState> s = solidBlock();
        // Diagonal travel cells (takeoff .. landing) — floors stay stone except the gap cells below.
        for (int t = 0; t <= g + 1; t++) {
            carveBody(s, 5 + t, 5 + t);
        }
        // Gap cell floors (and the optional 2-tall hazard column above them).
        for (int t = 1; t <= g; t++) {
            s.set(5 + t, 5, 5 + t, gapFloor);
            if (aboveGap != null) s.set(5 + t, 6, 5 + t, aboveGap);
        }
        // Swept corner columns of every transition t -> t+1.
        for (int t = 0; t <= g; t++) {
            setCorner(s, 6 + t, 5 + t, cornerFloor); // (x+dx*(t+1), z+dz*t)
            setCorner(s, 5 + t, 6 + t, cornerFloor); // (x+dx*t,     z+dz*(t+1))
        }
        return grid(s);
    }

    private static void setCorner(PalettedContainer<BlockState> s, int x, int z, BlockState floor) {
        s.set(x, 5, z, floor);
        carveBody(s, x, z);
    }

    private static void carveBody(PalettedContainer<BlockState> s, int x, int z) {
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int y = 6; y <= 9; y++) s.set(x, y, z, air);
    }

    // ---- infra (mirrors DiagonalParkourTest / ParkourHazardJumpTest) ---------------------------------

    private static PalettedContainer<BlockState> solidBlock() {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        PalettedContainer<BlockState> s = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        for (int x = 0; x < 16; x++)
            for (int y = 0; y < 16; y++)
                for (int z = 0; z < 16; z++)
                    s.set(x, y, z, stone);
        return s;
    }

    private static NavGridView grid(PalettedContainer<BlockState> s) {
        BlockState air = Blocks.AIR.defaultBlockState();
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

    private static BlockPathPlan search(NavGridView g, int sx, int sy, int sz, int gx, int gy, int gz) {
        return BlockPathfinder.findPath(g, new BlockPos(sx, sy, sz), new BlockPos(gx, gy, gz),
                BotCaps.DEFAULT, BOUND);
    }

    private static int count(BlockPathPlan plan, Object move) {
        int n = 0;
        for (int i = 0; i < plan.size(); i++)
            if (plan.movement(i) == move) n++;
        return n;
    }

    private static boolean contains(BlockPathPlan plan, Object move) {
        return count(plan, move) > 0;
    }

    /** Whether the plan ever STANDS on floor cell {@code (fx,fy,fz)} — waypoints store FEET (floor y+1). */
    private static boolean visitsFloor(BlockPathPlan plan, int fx, int fy, int fz) {
        for (int i = 0; i < plan.size(); i++) {
            BlockPos w = plan.waypoint(i);
            if (w.getX() == fx && w.getY() == fy + 1 && w.getZ() == fz) return true;
        }
        return false;
    }

    private static void assertReaches(BlockPathPlan plan, int fx, int fy, int fz) {
        BlockPos last = plan.waypoint(plan.size() - 1);
        assertTrue(Math.abs(last.getX() - fx) <= 1 && Math.abs(last.getZ() - fz) <= 1
                        && Math.abs(last.getY() - fy) <= 1,
                "the plan should end at the goal-feet (" + fx + "," + fy + "," + fz + "); ended at " + last);
    }
}
