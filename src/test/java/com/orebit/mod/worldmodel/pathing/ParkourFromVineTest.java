package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * Headless proof of the "solid footing at takeoff" parkour rule (issue 2): you CANNOT parkour-jump from a
 * vine / ladder / climbable. A climbable puts the player in a CLIMBING state, not a ground state — the
 * jump key just climbs UP faster and horizontal input merely EJECTS you; there is no {@code 0.42}
 * horizontal launch — so a parkour whose TAKEOFF cell is a climbable is physically impossible and the
 * finder must never plan it. (Real in-game pathology: a bot on a jungle vine tried to parkour onto a
 * nearby treetop.)
 *
 * <p>Mechanism: a mid-vine/-ladder node stays {@link MovementContext#MODE_STANDING} (Climb preserves the
 * mode), and {@code floorSurface} reports the {@code 16} full-block sentinel for a non-standable floor —
 * so the pre-fix takeoff gate (MODE_STANDING, jump≥1, not-honey, not-cobweb, head clearance) admitted a
 * climb node and offered it a jump. The fix adds one gate: {@code MovementContext.solidFooting} = {@code
 * standable && !climbable}. A VINE (empty shape) fails {@code standable}; a LADDER ({@code SHAPE_OTHER},
 * full-block collision top ⇒ {@code standable == true}) is caught only by {@code !climbable} — hence both
 * conjuncts.
 *
 * <p>Scene: a sealed stone section, corridor at {@code z=8}, floor {@code y=5}. Takeoff column at
 * {@code x=4}; a 1-wide BOTTOMLESS chasm at {@code x=5} (air to {@code y=0}, unbuilt below ⇒ no Fall
 * landing); an isolated landing ledge at {@code x=6} reachable ONLY by a flat 1-gap jump; goal at
 * {@code x=7}. The bot STARTS on the takeoff cell {@code (4,5,8)} (the search seeds the start at
 * MODE_STANDING regardless of the floor — exactly the on-the-vine pose).
 *
 * <ul>
 *   <li><b>Bug (vine / ladder takeoff):</b> the ledge is reachable ONLY via the illegal jump off the
 *       climbable — so pre-fix a plan exists whose PARKOUR takes off from {@code (4,5,8)}; post-fix that
 *       jump is refused and, with no other route, the goal is unreachable (plan {@code null} — the bot
 *       must instead climb down the vine to solid ground, which this walled scene does not provide).</li>
 *   <li><b>Positive guard (solid takeoff):</b> the SAME geometry with a plain STONE takeoff floor still
 *       plans exactly the parkour off {@code (4,5,8)} — the fix does not narrow legitimate ground jumps.
 *       </li>
 * </ul>
 * Lives in this package to reach {@link NavGridView}'s package-private synthetic constructor.
 */
class ParkourFromVineTest {

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

    private static final RegionBound CORRIDOR = new RegionBound(0, 15, 0, 15, 0, 15);
    /** The takeoff floor cell (vine / ladder / stone) — the bot starts here, MODE_STANDING. */
    private static final BlockPos TAKEOFF = new BlockPos(4, 5, 8);
    /** One traverse past the isolated landing ledge at (6,5,8). */
    private static final BlockPos GOAL = new BlockPos(7, 5, 8);

    @Test
    void refusesToParkourOffAVine() {
        NavGridView grid = buildScene(Blocks.VINE.defaultBlockState(), /*climbColumn=*/true);
        BlockPathPlan plan = BlockPathfinder.findPath(grid, TAKEOFF, GOAL, BotCaps.DEFAULT, CORRIDOR);

        // The ledge is reachable ONLY by the illegal vine jump; the fix removes it, so no route remains.
        assertNull(plan, "a jump cannot be launched from a vine (a climbing state) — the ledge is unreachable");
    }

    @Test
    void refusesToParkourOffALadder() {
        // Symmetry: a ladder is SHAPE_OTHER with a full-block collision top, so it reads standable — only
        // the !climbable half of solidFooting catches it. "Standing on top of a ladder" node (air feet).
        NavGridView grid = buildScene(Blocks.LADDER.defaultBlockState(), /*climbColumn=*/false);
        BlockPathPlan plan = BlockPathfinder.findPath(grid, TAKEOFF, GOAL, BotCaps.DEFAULT, CORRIDOR);

        assertNull(plan, "a jump cannot be launched from a ladder cell either — the ledge is unreachable");
    }

    @Test
    void stillParkoursOffSolidGround() {
        // Positive guard: identical geometry, a plain stone takeoff floor. The fix must NOT narrow this.
        NavGridView grid = buildScene(Blocks.STONE.defaultBlockState(), /*climbColumn=*/false);
        BlockPathPlan plan = BlockPathfinder.findPath(grid, TAKEOFF, GOAL, BotCaps.DEFAULT, CORRIDOR);

        assertNotNull(plan, "a solid-footed bot should still parkour the 1-wide gap to the ledge");
        int i = firstParkour(plan);
        assertTrue(i >= 0, "the crossing should be a PARKOUR jump");
        BlockPos takeoff = (i == 0) ? TAKEOFF : plan.waypoint(i - 1);
        assertEquals(TAKEOFF, takeoff, "the jump should take off from the solid floor cell (4,5,8)");
    }

    /** Index of the first PARKOUR step, or {@code -1}. */
    private static int firstParkour(BlockPathPlan plan) {
        for (int i = 0; i < plan.size(); i++) {
            if (plan.movement(i) == MovementRegistry.PARKOUR) return i;
        }
        return -1;
    }

    /**
     * One sealed stone section (chunk 0,0). Solid everywhere, then carved: a 1-wide bottomless chasm at
     * {@code x=5} ({@code y=0..9} air), an isolated landing ledge floor {@code (6,5,8)} + {@code (7,5,8)}
     * with head/apex clearance {@code x=6..7, y=6..9}, and the takeoff cell {@code (4,5,8)} set to
     * {@code takeoff}. When {@code climbColumn} the whole takeoff column {@code (4,1..8,8)} is filled with
     * the climbable (the bot is genuinely mid-column, feet IN the vine); otherwise only the floor cell is
     * set and {@code (4,6..9,8)} is carved to air (standing ON the cell, air feet/head). Everything else
     * stays solid stone, so the landing ledge is reachable ONLY by a flat 1-gap jump off {@code (4,5,8)}.
     */
    private static NavGridView buildScene(BlockState takeoff, boolean climbColumn) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();

        PalettedContainer<BlockState> s = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    s.set(x, y, z, stone);
                }
            }
        }

        final int z = 8;
        for (int y = 0; y <= 9; y++) s.set(5, y, z, air);          // bottomless chasm (the 1-wide gap)
        for (int x = 6; x <= 7; x++) {                              // isolated landing ledge + goal
            for (int y = 6; y <= 9; y++) s.set(x, y, z, air);       // head / apex clearance (floor y=5 stays stone)
        }

        if (climbColumn) {
            for (int y = 1; y <= 8; y++) s.set(4, y, z, takeoff);   // the whole climb column: feet IN the vine
        } else {
            s.set(4, 5, z, takeoff);                                // takeoff floor cell only...
            for (int y = 6; y <= 9; y++) s.set(4, y, z, air);       // ...standing ON it (air feet/head/apex)
        }

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
}
