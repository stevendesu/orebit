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
 * Headless proof of the {@link com.orebit.mod.pathfinding.blockpathfinder.movements.WalkOff} movement — the
 * no-jump advance-2 / descend-1 crossing that restores honey-crossing after the slow-flyover removal.
 *
 * <p>The owner honey/soul geometry (travel &minus;X): stand on a solid block, Traverse ONTO the slow block
 * (honey / soul sand) at node level, then cross a 1-wide void gap and land ONE block lower. Reproduced here
 * travelling +X in a sealed stone section (the {@code ParkourSlowFlyoverEnvelopeTest} convention):
 * <pre>
 *   floor:  (2,1)=stone start   (3,1)=SLOW block   (4,*)=void gap   (5,0)=stone landing (goal)
 * </pre>
 *
 * <p>Pins the two behaviours the owner requires:
 * <ul>
 *   <li><b>Honey</b> — a jump FROM honey is refused ({@code reducesJump}) and honey is no longer overflown,
 *       so WalkOff is the SOLE crosser: the plan reaches the goal via {@code WALK_OFF}.</li>
 *   <li><b>Soul sand</b> — soul is slow but NOT {@code reducesJump}, so a jump FROM it is legal; WalkOff's
 *       jump-refused emission gate keeps it SILENT there and Parkour owns the crossing (jump-from-soul). The
 *       plan reaches the goal via {@code PARKOUR}, NOT {@code WALK_OFF} — the anti-hijack guard.</li>
 * </ul>
 * Lives in this package to reach {@link NavGridView}'s package-private synthetic constructor.
 */
class WalkOffTest {

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

    // ---- honey: WalkOff is the SOLE crosser (jump refused, no flyover) --------------------------------

    @Test
    void honey_walkOff_reachesGoal() {
        // Floor cells (the pathfinder's convention): start (2,1) stone → Traverse onto (3,1) HONEY → WalkOff
        // over the void (4,1)/(4,0) → land (5,0), one lower. A jump FROM honey is refused (reducesJump) and
        // honey isn't overflown, so WalkOff is the only route.
        BlockPathPlan plan = search(slowCrossing(Blocks.HONEY_BLOCK.defaultBlockState()), 2, 1, 8, 5, 0, 8);
        assertNotNull(plan, "the honey WalkOff crossing must produce a plan");
        assertTrue(reachesGoal(plan, 5, 0, 8),
                "the bot must cross the honey via WalkOff and reach the landing one block lower");
        assertTrue(contains(plan, MovementRegistry.WALK_OFF),
                "the honey crossing is a WALK_OFF (jump-from-honey refused; honey not overflown)");
    }

    // ---- soul sand: Parkour owns it — WalkOff must NOT hijack a legal jump ----------------------------

    @Test
    void soulSand_jumpsFromSand_notWalkOff() {
        // Identical geometry with SOUL sand. Soul is NOT reducesJump, so a jump FROM it is legal and Parkour
        // (reduced-envelope gap-1 fall-1) owns the crossing. WalkOff's jump-refused gate keeps it silent — the
        // owner.soulflyover "must still cross via jump-from-sand" guard.
        BlockPathPlan plan = search(slowCrossing(Blocks.SOUL_SAND.defaultBlockState()), 2, 1, 8, 5, 0, 8);
        assertNotNull(plan, "the soul-sand crossing must produce a plan");
        assertTrue(reachesGoal(plan, 5, 0, 8), "the soul-sand crossing must reach the goal");
        assertTrue(contains(plan, MovementRegistry.PARKOUR),
                "soul sand must cross via a PARKOUR jump FROM the sand");
        assertFalse(contains(plan, MovementRegistry.WALK_OFF),
                "WalkOff must NOT hijack a legal jump-from-soul-sand (jump-refused gate)");
    }

    // ---- scene builder -------------------------------------------------------------------------------

    /**
     * (2,1)=stone start; (3,1)={@code slow} (honey/soul), a standable slow block at start level; (4,1),(4,0)
     * void gap; (5,0)=stone landing, ONE block lower (the goal at stand pos (5,1)). Bodies carved to air over
     * the whole arc. Everything below (4,*) is also void so the gap has no floor.
     */
    private static NavGridView slowCrossing(BlockState slow) {
        BlockState air = Blocks.AIR.defaultBlockState();
        PalettedContainer<BlockState> s = solidBlock();
        // Start + slow block sit at y=1; landing at y=0. Bodies above the whole run carved clear (y=2..5).
        for (int x = 2; x <= 5; x++)
            for (int y = 2; y <= 5; y++)
                s.set(x, y, 8, air);
        // The slow block at start level.
        s.set(3, 1, 8, slow);
        // The 1-wide void gap column (4,*) — clear at start level AND one below (the descend-through cell).
        s.set(4, 1, 8, air);
        s.set(4, 0, 8, air);
        // The landing feet cell (5,1) must be clear — one below the start level (the y=2..5 sweep above didn't
        // reach it); its floor (5,0) stays stone.
        s.set(5, 1, 8, air);
        return grid(s);
    }

    // ---- infra (mirrors ParkourSlowFlyoverEnvelopeTest) ----------------------------------------------

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
        if (plan == null) return false;
        for (int i = 0; i < plan.size(); i++)
            if (plan.movement(i) == move) return true;
        return false;
    }

    /** Whether the plan's last waypoint reaches the goal (within the ±1 goal tolerance). */
    private static boolean reachesGoal(BlockPathPlan plan, int gx, int gy, int gz) {
        if (plan == null || plan.size() == 0) return false;
        BlockPos last = plan.waypoint(plan.size() - 1);
        return Math.abs(last.getX() - gx) <= 1 && Math.abs(last.getZ() - gz) <= 1
                && Math.abs(last.getY() - gy) <= 1;
    }
}
