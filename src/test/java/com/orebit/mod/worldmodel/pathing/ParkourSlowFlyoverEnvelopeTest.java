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
 * Headless proof of the SLOW-FLYOVER REMOVAL ({@link
 * com.orebit.mod.pathfinding.blockpathfinder.movements.Parkour}/{@code DiagonalParkour}).
 *
 * <p><b>Owner decision (2026-07-14):</b> a Parkour scan no longer flies OVER a <i>slow</i> block (honey /
 * soul sand). The old jump-over trigger's {@code || ctx.isSlow(fd)} term was REMOVED: the honey
 * wall-slide ({@code HoneyBlock.doSlideMovement}) steals ~88% of horizontal momentum on a fast descent
 * beside honey and drops the bot into the void, and special-casing that is exactly the bandaid class the
 * model avoids. A slow block in the gap-line is now a NON-overflyable obstacle — soul sand / honey is a
 * full-block-standable cell, so the directional scan simply terminates on it (the same "never overfly a
 * ledge" v1 rule that a plain stone block already followed). The reduced-envelope <i>first-flyover</i>
 * selection that this test previously locked (added in {@code dfd5ab3}) is likewise removed as dead code —
 * with no slow flyovers there is no first flyover to reduce for. The takeoff-slow envelope bucket
 * (jump-FROM soul sand) is untouched.
 *
 * <p>Consequences this test pins:
 * <ul>
 *   <li><b>Normal void gap</b> — a full-reach flat parkour over an OPEN (void) gap is unchanged (control).</li>
 *   <li><b>Honey first-flyover</b> — REFUSED: the scan dead-ends on the honey (not overflown), and a jump
 *       FROM honey is refused by the {@code reducesJump} gate, so the goal beyond is unreachable. (A
 *       crossing move — WalkOff — will cover the honey case separately; not exercised here.)</li>
 *   <li><b>Soul-sand first-flyover, gap beyond exceeds the reduced jump-FROM cap</b> — REFUSED for the same
 *       "not overflown" reason (the flyover-from-the-near-edge is gone).</li>
 *   <li><b>Soul-sand first-flyover, gap beyond WITHIN the reduced jump-FROM cap</b> — RE-ROUTES: the planner
 *       walks ONTO the soul sand and jumps FROM it (reduced-envelope Parkour) and still reaches the goal.
 *       This is the behavioral difference from honey (soul sand does not suppress the jump).</li>
 * </ul>
 *
 * <p>Scene convention mirrors {@code ParkourHazardJumpTest}: a sealed stone section, 1-wide corridor at
 * {@code z=8}, floor {@code y=0}. Lives in this package to reach {@link NavGridView}'s package-private
 * synthetic constructor.
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

    // ---- slow removal: a slow first flyover is NOT overflown -> the flyover route is not offered --------

    @Test
    void gap3Flat_overHoneyFirstFlyover_isRefused() {
        // Same geometry, but the FIRST flyover cell (3,0) is HONEY (slow). A slow block is no longer
        // overflown, so the +x scan dead-ends on the honey; a jump FROM honey is refused (reducesJump). The
        // goal beyond is unreachable — the flyover route is simply not offered.
        BlockPathPlan plan = search(gap3Flat(Blocks.HONEY_BLOCK.defaultBlockState()), 2, 0, 8, 6, 0, 8);
        assertFalse(reachesGoal(plan, 6, 0, 8),
                "a gap-3 flat over a HONEY first flyover must be REFUSED (slow no longer overflown; "
                        + "jump-from-honey suppressed)");
    }

    @Test
    void gap3Flat_overSoulSandFirstFlyover_isRefused() {
        // Soul sand is the other slow floor. The near-edge flyover is gone, and here the jump FROM the soul
        // sand to the goal would be a gap-2 (soul at (3,0), void (4,0),(5,0), landing (6,0)) — beyond the
        // reduced jump-FROM cap — so this scene stays REFUSED. (The in-envelope re-route is the next test.)
        BlockPathPlan plan = search(gap3Flat(Blocks.SOUL_SAND.defaultBlockState()), 2, 0, 8, 6, 0, 8);
        assertFalse(reachesGoal(plan, 6, 0, 8),
                "a gap-3 goal over a SOUL-SAND first flyover must be REFUSED (slow no longer overflown; "
                        + "the jump-from-soul-sand gap-2 exceeds the reduced cap)");
    }

    // ---- the behavioral difference: soul sand RE-ROUTES via a jump FROM it when in-envelope ------------

    @Test
    void gap1Flat_fromSoulSand_reachesGoal() {
        // takeoff (2,0)=stone; soul (3,0); void (4,0); landing (5,0)=goal. The +x scan from (2,0) dead-ends
        // on the soul sand (not overflown), so the ONLY route is to walk ONTO the soul sand and jump FROM it:
        // a gap-1 (single void column (4,0)) Parkour under the reduced (gsf-0.4) envelope. Soul sand does NOT
        // suppress the jump (unlike honey), so this re-routes and reaches the goal — the exact honey/soul
        // distinction the owner.soulflyover harness trial exercises.
        BlockPathPlan plan = search(gap1FromSoulSand(), 2, 0, 8, 5, 0, 8);
        assertNotNull(plan, "the jump-from-soul-sand re-route must produce a plan");
        assertTrue(reachesGoal(plan, 5, 0, 8),
                "soul sand must re-route to a jump FROM it (reduced-envelope gap-1) and reach the goal");
        assertTrue(contains(plan, MovementRegistry.PARKOUR),
                "the gap-1 crossing FROM the soul sand is a PARKOUR jump");
    }

    // ---- scene builders ------------------------------------------------------------------------------

    /**
     * takeoff (2,0)=stone; FIRST gap cell (3,0)={@code firstCell}; void (4,0),(5,0); landing (6,0)=stone
     * (the goal). Bodies {@code y=1..4} carved to air over the whole corridor, so the arc has clearance and
     * the ONLY flyover route to the goal is a gap-3 flat parkour from (2,0). A slow {@code firstCell}
     * (honey/soul) is no longer overflown; {@code AIR} is the normal control.
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

    /**
     * takeoff (2,0)=stone; soul sand (3,0); void (4,0); landing (5,0)=stone (the goal). Bodies {@code y=1..4}
     * carved to air. The +x scan from the takeoff dead-ends on the soul sand, so the only route is to walk
     * onto it and jump FROM it over the single void column (4,0) — a reduced-envelope gap-1.
     */
    private static NavGridView gap1FromSoulSand() {
        BlockState air = Blocks.AIR.defaultBlockState();
        PalettedContainer<BlockState> s = solidBlock();
        carveCorridor(s, 1, 5, 4);
        s.set(3, 0, 8, Blocks.SOUL_SAND.defaultBlockState());
        s.set(4, 0, 8, air);
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
