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
import com.orebit.mod.pathfinding.blockpathfinder.MovementRegistry;
import com.orebit.mod.pathfinding.blockpathfinder.RegionBound;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

/**
 * Headless proof of the ISSUE-3 <b>jump-over-a-standable-obstacle</b> parkour rule and its landing
 * floor-hazard pricing. Before the fix the aligned parkour scan ENDED a direction at the first
 * {@code standable} node-level cell, so a standable obstacle (magma / campfire / snow layer / slab /
 * soul sand / honey) was never treated as a jump-over gap column — the bot walked onto the hazard (magma,
 * damage) or dead-ended entirely (snow into a +1 ledge). The fix arms a jump-over TRIGGER at the standable
 * branch — {@code damaging (caps-gated) || topY < 12 || isSlow} — so a triggering obstacle is overflown to
 * a landing BEYOND it while the walk-onto candidate still stands, and A* picks the cheaper. A plain full
 * non-damaging non-slow block (topY 16) triggers nothing and stays byte-identical (the walker steps/walks).
 *
 * <p>Scene convention (shared with {@link com.orebit.mod.worldmodel.pathing HazardJumpDiagnosisTest} /
 * {@code ParkourOverFluidTest}): a sealed stone section with a 1-wide corridor at {@code z=8}, floor at
 * {@code y=0}, bodies {@code y=1..} carved to air. Takeoff {@code x=2}; the gap/obstacle cells follow.
 * Lives in this package to reach {@link NavGridView}'s package-private synthetic constructor.
 */
class ParkourHazardJumpTest {

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

    private static BlockState snow2() {
        return Blocks.SNOW.defaultBlockState().setValue(BlockStateProperties.LAYERS, 2);
    }

    // ---- (1) snow gap + raised (+1) landing — the priority NO-PATH challenge --------------------------

    @Test
    void snowGap_risingParkourOverSnowOntoPlusOneLedge() {
        // takeoff (2,0) stone; sunk snow (3,0); raised landing block at (4,0)+(4,1) (top y=2, a +1 rising
        // landing); goal platform at (5,0)+(5,1). The ONLY route is a rising parkour OVER the snow.
        BlockPathPlan plan = search(snowPitRaisedLanding(), 2, 0, 8, 5, 1, 8);

        assertNotNull(plan, "the bot must rising-parkour over the sunk snow onto the +1 ledge (was NO PATH)");
        assertTrue(contains(plan, MovementRegistry.PARKOUR),
                "crossing the snow pit onto the raised block should be a PARKOUR jump");
        assertReaches(plan, 5, 1, 8);
    }

    // ---- (2) magma gap — jump OVER, never walk ONTO ---------------------------------------------------

    @Test
    void magmaGap_parkoursOver_neverWalksOntoTheMagma() {
        BlockPathPlan plan = search(flatGap(Blocks.MAGMA_BLOCK.defaultBlockState()), 2, 0, 8, 5, 0, 8);

        assertNotNull(plan, "a route to the goal must exist");
        assertTrue(contains(plan, MovementRegistry.PARKOUR),
                "the magma gap cell should be JUMPED, not walked (damaging trigger)");
        assertReaches(plan, 5, 0, 8);
        assertFalse(visitsFloor(plan, 3, 0, 8), "the plan must NOT stand on the magma cell (3,0,8)");
    }

    // ---- (3) campfire gap — damaging standable full-ish block ----------------------------------------

    @Test
    void campfireGap_parkoursOver() {
        BlockPathPlan plan = search(flatGap(Blocks.CAMPFIRE.defaultBlockState()), 2, 0, 8, 5, 0, 8);

        assertNotNull(plan, "a route to the goal must exist");
        assertTrue(contains(plan, MovementRegistry.PARKOUR),
                "the campfire gap cell should be JUMPED (damaging trigger)");
        assertReaches(plan, 5, 0, 8);
        assertFalse(visitsFloor(plan, 3, 0, 8), "the plan must NOT stand on the campfire cell (3,0,8)");
    }

    // ---- (4) slow FLOOR gaps — worth jumping over the walk-speed multiplier ---------------------------

    @Test
    void soulSandGap_parkoursOverTheSlowFloor() {
        // Two soul-sand cells in the gap floor (3,0)+(4,0); landing (5,0); a walk drags across both at the
        // 0.4 speed factor (≈2.5× per step), clearly dearer than one g=2 jump over them.
        BlockPathPlan plan = search(twoCellGap(soulSand(), soulSand()), 2, 0, 8, 5, 0, 8);

        assertNotNull(plan, "a route to the goal must exist");
        assertTrue(contains(plan, MovementRegistry.PARKOUR),
                "the slow soul-sand span should be JUMPED (isSlow trigger) — the drag exceeds the jump");
        assertReaches(plan, 5, 0, 8);
    }

    @Test
    void honeyGap_parkoursOverTheSlowFloor() {
        // Honey is a FULL block (topY 16) — only the isSlow term catches it (getSpeedFactor 0.4). VERIFIED
        // in NavBlock: honey classifies SURFACE_SLOW, so ctx.isSlow(fd) fires and the jump-over is offered.
        BlockPathPlan plan = search(twoCellGap(honey(), honey()), 2, 0, 8, 5, 0, 8);

        assertNotNull(plan, "a route to the goal must exist");
        assertTrue(contains(plan, MovementRegistry.PARKOUR),
                "the slow honey span should be JUMPED (isSlow trigger) — the drag exceeds the jump");
        assertReaches(plan, 5, 0, 8);
    }

    // ---- (5) regression: fluids still jump; a plain full block never does -----------------------------

    @Test
    void lavaGap_stillParkours() {
        BlockPathPlan plan = search(flatGap(Blocks.LAVA.defaultBlockState()), 2, 0, 8, 5, 0, 8);
        assertNotNull(plan, "the prior fix: a 1-wide lava gap is jumped");
        assertTrue(contains(plan, MovementRegistry.PARKOUR), "lava gap must still PARKOUR");
        assertReaches(plan, 5, 0, 8);
    }

    @Test
    void fireGap_stillParkours() {
        BlockPathPlan plan = search(flatGap(Blocks.FIRE.defaultBlockState()), 2, 0, 8, 5, 0, 8);
        assertNotNull(plan, "the prior fix: a 1-wide fire gap is jumped");
        assertTrue(contains(plan, MovementRegistry.PARKOUR), "fire gap must still PARKOUR");
        assertReaches(plan, 5, 0, 8);
    }

    @Test
    void plainFullBlockStraightaway_producesNoParkour() {
        // A full stone block in the "gap" cell is continuous ground (topY 16, non-damaging, non-slow) — no
        // trigger — so the bot WALKS across and NO parkour is emitted (byte-identical to v1 terrain).
        BlockPathPlan plan = search(flatGap(Blocks.STONE.defaultBlockState()), 2, 0, 8, 5, 0, 8);
        assertNotNull(plan, "continuous ground is walkable");
        assertFalse(contains(plan, MovementRegistry.PARKOUR),
                "a full block is a STEP, not a gap — the bot should Traverse, never PARKOUR");
        assertReaches(plan, 5, 0, 8);
    }

    // ---- (6) landing floor-hazard pricing: the hazard landing carries +costPerHitpoint ---------------

    @Test
    void landingOnADamagingFloorCarriesTheHazardCost() {
        // ONE forced parkour g=1 over an air gap onto the goal cell (4,0,8) — magma vs stone floor. The
        // plans are identical but for the landing floor, so cost(magma) − cost(stone) is exactly the
        // caps.costPerHitpoint the fix now charges for landing ON a damaging floor.
        BlockPathPlan onMagma = search(landingPad(Blocks.MAGMA_BLOCK.defaultBlockState()), 2, 0, 8, 4, 0, 8);
        BlockPathPlan onStone = search(landingPad(Blocks.STONE.defaultBlockState()), 2, 0, 8, 4, 0, 8);

        assertNotNull(onMagma, "the bot must parkour the 1-wide gap onto the magma pad");
        assertNotNull(onStone, "the bot must parkour the 1-wide gap onto the stone pad");
        assertTrue(contains(onMagma, MovementRegistry.PARKOUR) && contains(onStone, MovementRegistry.PARKOUR),
                "both crossings are the same g=1 parkour");
        assertTrue(visitsFloor(onMagma, 4, 0, 8) && visitsFloor(onStone, 4, 0, 8),
                "both plans must actually land on the pad (4,0,8), the walled-off only route");
        assertEquals(BotCaps.DEFAULT.costPerHitpoint(), onMagma.cost() - onStone.cost(), 0.01f,
                "landing on magma must cost exactly one costPerHitpoint more than landing on stone");
    }

    // ---- scene builders ------------------------------------------------------------------------------

    private static BlockState soulSand() {
        return Blocks.SOUL_SAND.defaultBlockState();
    }

    private static BlockState honey() {
        return Blocks.HONEY_BLOCK.defaultBlockState();
    }

    /** 1-wide corridor: takeoff (2,0), gap OBSTACLE (3,0)=obstacle, landing (4,0), goal (5,0). */
    private static NavGridView flatGap(BlockState obstacle) {
        PalettedContainer<BlockState> s = solidBlock();
        carveCorridor(s, 1, 6, 4);
        s.set(3, 0, 8, obstacle);
        return grid(s);
    }

    /** 1-wide corridor with a TWO-cell obstacle span (3,0)+(4,0); landing (5,0), goal (5,0). */
    private static NavGridView twoCellGap(BlockState a, BlockState b) {
        PalettedContainer<BlockState> s = solidBlock();
        carveCorridor(s, 1, 6, 4);
        s.set(3, 0, 8, a);
        s.set(4, 0, 8, b);
        return grid(s);
    }

    /**
     * takeoff (2,0), a true AIR gap (3,0)=air, and a single landing PAD (4,0)=pad which is the goal. The
     * corridor is carved ONLY x=1..4, so x≥5 stays a solid wall (body blocked) — there is NO landing beyond
     * the pad, so the bot cannot fly OVER it (a g=2 to (5,0) is walled off) and MUST land on the pad. That
     * isolates the landing floor-hazard term: magma vs stone differ only by floorHazardCost.
     */
    private static NavGridView landingPad(BlockState pad) {
        PalettedContainer<BlockState> s = solidBlock();
        carveCorridor(s, 1, 4, 4);
        s.set(3, 0, 8, Blocks.AIR.defaultBlockState()); // real gap — the only crossing is a parkour
        s.set(4, 0, 8, pad);
        return grid(s);
    }

    /** snow pit + raised (+1) landing: takeoff (2,0); snow (3,0); solid (4,0)+(4,1) & (5,0)+(5,1). */
    private static NavGridView snowPitRaisedLanding() {
        BlockState stone = Blocks.STONE.defaultBlockState();
        PalettedContainer<BlockState> s = solidBlock();
        carveCorridor(s, 1, 6, 6);
        s.set(2, 0, 8, stone);
        s.set(3, 0, 8, snow2());
        s.set(4, 0, 8, stone);
        s.set(4, 1, 8, stone);
        s.set(5, 0, 8, stone);
        s.set(5, 1, 8, stone);
        return grid(s);
    }

    private static void carveCorridor(PalettedContainer<BlockState> s, int xLo, int xHi, int yHi) {
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int x = xLo; x <= xHi; x++)
            for (int y = 1; y <= yHi; y++)
                s.set(x, y, 8, air);
    }

    // ---- infra (mirrors HazardJumpDiagnosisTest) -----------------------------------------------------

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

    private static boolean contains(BlockPathPlan plan, Object move) {
        for (int i = 0; i < plan.size(); i++)
            if (plan.movement(i) == move) return true;
        return false;
    }

    /** Whether the plan ever STANDS on floor cell {@code (fx,fy,fz)} — waypoints store FEET (floor y+1). */
    private static boolean visitsFloor(BlockPathPlan plan, int fx, int fy, int fz) {
        for (int i = 0; i < plan.size(); i++) {
            BlockPos w = plan.waypoint(i);
            if (w.getX() == fx && w.getY() == fy + 1 && w.getZ() == fz) return true;
        }
        return false;
    }

    private static void assertReaches(BlockPathPlan plan, int gx, int gy, int gz) {
        BlockPos last = plan.waypoint(plan.size() - 1);
        assertTrue(Math.abs(last.getX() - gx) <= 1 && Math.abs(last.getZ() - gz) <= 1
                        && Math.abs(last.getY() - gy) <= 1,
                "the plan should end at the goal (" + gx + "," + gy + "," + gz + "); ended at " + last);
    }
}
