package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.orebit.mod.pathfinding.blockpathfinder.MovementRegistry;
import com.orebit.mod.pathfinding.blockpathfinder.RegionBound;
import com.orebit.mod.pathfinding.blockpathfinder.movements.Fall;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

/**
 * The owner's live water-tower scene (report 2026-08-07), at 1:1 scale: a 1×1 water column built from stacked
 * {@code .S. / SWS / .P.} modules — planks on three faces, signs on the fourth so the column is enterable —
 * standing on solid ground, with one wall extended into a 60-block perch.
 *
 * <p>Before the wet-endpoint change this scene was <b>wholly unpathable</b>: {@code Fall} emitted nothing at
 * all, because the entry momentum from the drop could not carry the bot through 16 blocks of water to the
 * seabed and the candidate was refused outright. With no fall available, A* routed the bot down a pillared
 * spiral staircase beside the tower. The refusal rested on a false premise — that the remaining descent was a
 * ~1400-tick passive crawl — when {@link com.orebit.mod.pathfinding.blockpathfinder.movements.Swim} has an
 * active sink rung all along.
 *
 * <p>The failure was in the ENTRY, not the exit: a shallower column (≤ the momentum window) pathed end-to-end
 * through the very same sign face, which is why {@link #aShallowColumnWasAlwaysPathable} is kept as the
 * control. Lives in this package to reach {@link NavGridView}'s package-private synthetic constructor.
 */
class WaterTowerFallTest {

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

    private static final int WX = 8, WZ = 8;   // the 1x1 water column
    private static final int PX = 7, PZ = 8;   // the wall extended into the perch; the bot stands on top
    private static final int GROUND = 10;      // seabed / world floor
    private static final int TOP = 86;         // the bot's node floor => a 76-block drop
    private static final int SECTIONS = 7;     // y 0..111
    private static final RegionBound BOUND = new RegionBound(0, 15, 0, SECTIONS * 16 - 1, 0, 15);

    /**
     * The headline. 16 blocks of water under a 76-block drop: the free fall above the column is 60 blocks,
     * which enters at 2.34 b/t and buys 11 blocks of penetration — 5 short of the seabed. The fall therefore
     * ends FLOATING with the feet 11 cells below the surface (y=26), so the node keys on y=14.
     */
    @Test
    void aFallIntoADeepColumnEndsFloatingInsteadOfBeingRefused() {
        int surface = GROUND + 16;
        int expected = surface - 11 - 1;   // node cell = one below the resting feet
        assertEquals(expected, fallLandingY(16),
                "the fall must end where momentum actually leaves the bot, not be refused for missing the floor");
    }

    /** And the whole route now exists: fall in, swim down the column, walk out through the sign face. */
    @Test
    void theBotCanFallInAndSwimOutOfTheTower() {
        BlockPathPlan plan = path(16);
        assertNotNull(plan, "the tower must be pathable — this returned null before the wet-endpoint change"
                + dump(plan));
        assertTrue(contains(plan, MovementRegistry.FALL), "the descent is a Fall" + dump(plan));
        assertTrue(contains(plan, MovementRegistry.SWIM),
                "the fall lands mid-column, so Swim owns the rest of the descent" + dump(plan));
    }

    /**
     * The control that localises the old bug to the ENTRY. A column inside the momentum window pathed fine
     * before and after — same tower, same 1-wide sign face, same seabed exit — so nothing about the exit
     * geometry or the region tier was ever implicated.
     */
    @Test
    void aShallowColumnWasAlwaysPathable() {
        assertEquals(GROUND, fallLandingY(8), "8 blocks of water is crossed by momentum — a seabed landing");
        assertNotNull(path(8), "the shallow tower was pathable before the change and must stay so");
    }

    // ---- scene ------------------------------------------------------------------------------------------

    /** The Y of the Fall candidate into the water column from the perch, or {@link Integer#MIN_VALUE}. */
    private static int fallLandingY(int waterDepth) {
        MovementContext ctx = new MovementContext(tower(waterDepth), BotCaps.DEFAULT);
        final int[] got = { Integer.MIN_VALUE };
        new Fall().candidates(ctx, PX, TOP, PZ, new CandidateSink() {
            @Override
            public void accept(int x, int y, int z, float cost, EditScratch edits) {
                if (x == WX && z == WZ) got[0] = y;
            }
        });
        return got[0];
    }

    /** Tower top → open ground outside the tower. {@link BotCaps#DEFAULT} cannot place, so there is no
     *  staircase alternative: a null plan means the water route genuinely does not exist. */
    private static BlockPathPlan path(int waterDepth) {
        return BlockPathfinder.findPath(tower(waterDepth),
                new BlockPos(PX, TOP, PZ), new BlockPos(12, GROUND, WZ), BotCaps.DEFAULT, BOUND);
    }

    /**
     * Stone ground at {@code y=GROUND}, a 1×1 water column {@code waterDepth} tall on it at {@code (WX,WZ)},
     * walled by planks on three faces and signs on the fourth (the module's passable face), with the
     * {@code (PX,PZ)} wall extended to {@code TOP}. Everything outside the tower is open air over the ground.
     */
    private static NavGridView tower(int waterDepth) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState planks = Blocks.OAK_PLANKS.defaultBlockState();
        BlockState water = Blocks.WATER.defaultBlockState();
        BlockState sign = Blocks.OAK_SIGN.defaultBlockState();

        BlockState[][][] w = new BlockState[16][SECTIONS * 16][16];
        for (int x = 0; x < 16; x++)
            for (int y = 0; y < SECTIONS * 16; y++)
                for (int z = 0; z < 16; z++)
                    w[x][y][z] = (y <= GROUND) ? stone : air;

        for (int y = GROUND + 1; y <= GROUND + waterDepth; y++) {
            w[WX][y][WZ] = water;         // the column
            w[WX][y][WZ - 1] = planks;    // .S.
            w[WX + 1][y][WZ] = planks;    // ..S
            w[WX][y][WZ + 1] = sign;      // .P. — the passable face that holds the water and lets the bot out
        }
        for (int y = GROUND + 1; y <= TOP; y++) w[PX][y][PZ] = planks; // S.. extended into the perch

        NavSection[] column = new NavSection[SECTIONS];
        for (int s = 0; s < SECTIONS; s++) {
            PalettedContainer<BlockState> pc = new PalettedContainer<>(
                    air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
            for (int x = 0; x < 16; x++)
                for (int y = 0; y < 16; y++)
                    for (int z = 0; z < 16; z++)
                        pc.set(x, y, z, w[x][s * 16 + y][z]);
            column[s] = NavSection.create(BlockPos.ZERO);
            NavSectionBuilder.classifyInto(pc, false, column[s].getTraversalGrid());
        }
        ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        chunks.put(NavStore.key(0, 0), column);
        return new NavGridView(0, chunks);
    }

    private static boolean contains(BlockPathPlan plan, Object move) {
        if (plan == null) return false;
        for (int i = 0; i < plan.size(); i++) if (plan.movement(i) == move) return true;
        return false;
    }

    private static String dump(BlockPathPlan plan) {
        if (plan == null) return "\n  (no plan)";
        StringBuilder sb = new StringBuilder("\n  plan:");
        for (int i = 0; i < plan.size(); i++) {
            sb.append("\n    ").append(i).append(' ').append(plan.waypoint(i)).append(" via ")
              .append(plan.movement(i) == null ? "-" : plan.movement(i).getClass().getSimpleName());
        }
        return sb.toString();
    }
}
