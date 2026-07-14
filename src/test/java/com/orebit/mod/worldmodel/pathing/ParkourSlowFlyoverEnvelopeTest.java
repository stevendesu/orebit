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
 * Headless proof of the SLOW-FIRST-FLYOVER envelope-selection fix ({@link
 * com.orebit.mod.pathfinding.blockpathfinder.movements.Parkour}/{@code DiagonalParkour}).
 *
 * <p>Before the fix the reduced ("slow", gsf-0.4) {@link
 * com.orebit.mod.pathfinding.blockpathfinder.movements.ParkourEnvelope} row was selected ONLY from the
 * takeoff cell. So a NORMAL takeoff whose FIRST flown-over block is slowing (honey / soul sand at gap
 * column 1) was offered under the FULL envelope — but the tick-1 speedFactor-0.4 slash makes the max-reach
 * tiers physically unmakeable (measured: the Phase-4 takeoff-timing sweep). The fix ALSO selects the reduced
 * row, per direction, when the FIRST flyover cell is slow — so the planner no longer OFFERS the over-reduced-
 * envelope tiers. Only the first flyover matters (the bot is still low there; deeper slow blocks clear above
 * the read zone) and the table itself is unchanged — only its SELECTION.
 *
 * <p>Reduced caps (surface 16 / gsf-0.4): flat 2 (&lt; the full 3). So a gap-3 flat is REFUSED over a slow
 * first flyover, but still OFFERED over a normal (void) gap. Scene convention mirrors {@code
 * ParkourHazardJumpTest}: a sealed stone section, 1-wide corridor at {@code z=8}, floor {@code y=0}; takeoff
 * {@code x=2}, gap cells {@code 3..5}, landing {@code x=6}. Lives in this package to reach {@link
 * NavGridView}'s package-private synthetic constructor.
 */
class ParkourSlowFlyoverEnvelopeTest {

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

    // ---- the control: a normal (void) gap-3 flat is STILL offered ------------------------------------

    @Test
    void gap3Flat_overNormalVoidGap_isOffered() {
        // takeoff (2,0); void (3,0),(4,0),(5,0); landing (6,0)=goal. Full envelope (flat cap 3) -> the g=3
        // flat is offered and it is the only route, so the plan must reach the goal via a PARKOUR jump.
        BlockPathPlan plan = search(gap3Flat(Blocks.AIR.defaultBlockState()), 2, 0, 8, 6, 0, 8);
        assertNotNull(plan, "a gap-3 flat over a normal void gap must be OFFERED");
        assertTrue(reachesGoal(plan, 6, 0, 8),
                "the normal gap-3 flat must reach the goal (it is the only route)");
        assertTrue(contains(plan, MovementRegistry.PARKOUR), "the gap-3 crossing is a PARKOUR jump");
    }

    // ---- the fix: a SLOW first flyover selects the reduced envelope -> gap-3 flat REFUSED --------------

    @Test
    void gap3Flat_overHoneyFirstFlyover_isRefused() {
        // Same geometry, but the FIRST flyover cell (3,0) is HONEY (slow, speedFactor 0.4). The reduced row
        // (flat cap 2 < 3) must be selected from the flyover, so the g=3 flat is NOT offered — the goal is
        // unreachable (a walk onto the honey dead-ends; parkour from honey is jump-suppressed).
        BlockPathPlan plan = search(gap3Flat(Blocks.HONEY_BLOCK.defaultBlockState()), 2, 0, 8, 6, 0, 8);
        assertFalse(reachesGoal(plan, 6, 0, 8),
                "a gap-3 flat over a SLOW (honey) first flyover must be REFUSED (reduced-envelope flat cap 2<3)");
    }

    @Test
    void gap3Flat_overSoulSandFirstFlyover_isRefused() {
        // Soul sand is the other slow floor (speedFactor 0.4). Same reduced-envelope refusal as honey.
        BlockPathPlan plan = search(gap3Flat(Blocks.SOUL_SAND.defaultBlockState()), 2, 0, 8, 6, 0, 8);
        assertFalse(reachesGoal(plan, 6, 0, 8),
                "a gap-3 flat over a SLOW (soul-sand) first flyover must be REFUSED (reduced-envelope flat cap 2<3)");
    }

    // ---- scene builder -------------------------------------------------------------------------------

    /**
     * takeoff (2,0)=stone; FIRST gap cell (3,0)={@code firstCell}; void (4,0),(5,0); landing (6,0)=stone
     * (the goal). Bodies {@code y=1..4} carved to air over the whole corridor, so the arc has clearance and
     * the ONLY route to the goal is a gap-3 flat parkour. A slow {@code firstCell} (honey/soul) is the
     * reduced-envelope trigger; {@code AIR} is the normal control.
     */
    private static NavGridView gap3Flat(BlockState firstCell) {
        BlockState air = Blocks.AIR.defaultBlockState();
        PalettedContainer<BlockState> s = solidBlock();
        carveCorridor(s, 1, 6, 4);
        s.set(3, 0, 8, firstCell);
        s.set(4, 0, 8, air);
        s.set(5, 0, 8, air);
        return grid(s);
    }

    private static void carveCorridor(PalettedContainer<BlockState> s, int xLo, int xHi, int yHi) {
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int x = xLo; x <= xHi; x++)
            for (int y = 1; y <= yHi; y++)
                s.set(x, y, 8, air);
    }

    // ---- infra (mirrors ParkourHazardJumpTest) -------------------------------------------------------

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

    /** Whether the plan's last waypoint reaches the goal (within the ±1 goal tolerance). Robust to a
     *  best-effort PARTIAL: a refused route ends at the takeoff / a dead-end honey cell, far from the goal. */
    private static boolean reachesGoal(BlockPathPlan plan, int gx, int gy, int gz) {
        if (plan == null || plan.size() == 0) return false;
        BlockPos last = plan.waypoint(plan.size() - 1);
        return Math.abs(last.getX() - gx) <= 1 && Math.abs(last.getZ() - gz) <= 1
                && Math.abs(last.getY() - gy) <= 1;
    }
}
