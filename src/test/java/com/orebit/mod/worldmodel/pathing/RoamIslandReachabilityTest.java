package com.orebit.mod.worldmodel.pathing;

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

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

/**
 * Theodore's floating island, reproduced headlessly — the scene behind the {@code /bot roam} freeze report
 * (superflat world, a small island above it, roam targeting a ground cell ~50 blocks away).
 *
 * <p>This pins the FACT the roam target picker has to respect: with {@code mayFall} off, a walk-only bot on
 * an island has <b>no route at all</b> to the ground, so the block search exhausts its open set and returns
 * {@code null}. The same search with {@code mayFall} on finds the route immediately — the drop is the only
 * way off, which is exactly what roam refuses. Nothing about the island is a bug: the target is genuinely
 * unreachable, and the defect was that {@link com.orebit.mod.BotRoamer} committed to it anyway.
 *
 * <p>It also pins the SHAPE of the failure, which is what makes probe-before-commit affordable: the failing
 * search is TINY (the island's own cells and nothing else). A reachability probe on a boxed-in bot costs
 * almost nothing — it is the open-terrain case that would be expensive, and there the first probe succeeds.
 *
 * <p>And it pins the ESCAPE: a target ON the island is reachable with {@code mayFall} off, which is why
 * shrinking the leg length (rather than the roam radius) is the recovery that actually works — a bot on a
 * small island can still pace its island.
 */
class RoamIslandReachabilityTest {

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

    /** Walk-only, mortal, Fall available — the control bot. */
    private static final BotCaps FALLER = BotCaps.DEFAULT;
    /** The same bot under the roam restriction. */
    private static final BotCaps ROAMER = BotCaps.DEFAULT.withMayFall(false);

    // Superflat ground plane at y=0; an 8x8 stone island at y=10 (a 10-block drop: past safeFall 3, inside
    // maxFall 16, so a FALLING bot may legitimately take it — the control below proves it does).
    private static final int GROUND_Y = 0;
    private static final int ISLAND_Y = 10;
    private static final int ISLAND_X0 = 4, ISLAND_Z0 = 4, ISLAND_SIZE = 8;

    /** Bot's floor cell: the middle of the island. */
    private static final BlockPos ISLAND_FLOOR = new BlockPos(8, ISLAND_Y, 8);
    /** A ground cell ~50 blocks east — the kind of target roam's tournament picks. */
    private static final BlockPos GROUND_TARGET = new BlockPos(58, GROUND_Y, 8);
    /** Another cell on the island itself — the target a shrunk leg would pick. */
    private static final BlockPos ISLAND_TARGET = new BlockPos(11, ISLAND_Y, 10);
    /** The degenerate island: a single block, for the "parking is honest" counterpart. */
    private static final BlockPos PILLAR_FLOOR = new BlockPos(8, ISLAND_Y, 8);

    @Test
    void fallingBotReachesTheGroundByDropping() {
        // Control. If this fails the scene is wrong (island unreachable for everyone), and the refusal below
        // would prove nothing.
        BlockPathPlan plan = BlockPathfinder.findPath(world(), ISLAND_FLOOR, GROUND_TARGET, FALLER);
        assertNotNull(plan, "a bot allowed to fall gets off the island");
        assertFalse(BlockPathfinder.lastWasPartial(), "and it is a real route, not a budget partial");
    }

    @Test
    void roamingBotCannotReachTheGroundAtAll() {
        // THE REPORTED SCENE. No route exists — the search exhausts rather than running out of budget.
        BlockPathPlan plan = BlockPathfinder.findPath(world(), ISLAND_FLOOR, GROUND_TARGET, ROAMER);
        assertNull(plan, "with Fall refused there is no way down, so no path to the ground exists");
        assertFalse(BlockPathfinder.lastWasBudgetHit(),
                "the search EXHAUSTED its open set — it did not merely run out of nodes");
    }

    @Test
    void theFailingProbeIsCheap() {
        // Why probe-before-commit is affordable: a boxed-in bot's refusal costs a handful of expansions
        // (the island's own cells), not a flood. The expensive case — open terrain — succeeds on probe 1.
        BlockPathfinder.findPath(world(), ISLAND_FLOOR, GROUND_TARGET, ROAMER);
        int expansions = BlockPathfinder.lastExpansions();
        assertTrue(expansions > 1, "not start-dead — the bot can walk around its island (got " + expansions + ")");
        assertTrue(expansions < 2000,
                "a boxed-in refusal must stay cheap enough to probe per-tick (got " + expansions + ")");
    }

    @Test
    void aTargetOnTheIslandIsStillReachable() {
        // The escape hatch the leg-shrink exploits: roaming is still possible, just smaller. This is what
        // turns "frozen bot" into "bot pacing its island".
        BlockPathPlan plan = BlockPathfinder.findPath(world(), ISLAND_FLOOR, ISLAND_TARGET, ROAMER);
        assertNotNull(plan, "a no-fall bot can still walk around the island it is standing on");
        assertFalse(BlockPathfinder.lastWasPartial(), "and that route is proven, not a partial");
    }

    @Test
    void theFloorSweepFindsFootingOnThisIsland() {
        // BotRoamer's LAST batch is a deterministic 8-way compass sweep at LEG_FLOOR (3 blocks) — the batch
        // whose total refusal parks the bot. Replayed here against the reported scene it must NOT be a total
        // refusal: the bot is mid-island, so several of the eight headings land on the island and probe
        // reachable. If this ever goes to zero, roam parks a bot that could still have paced its island.
        // (Mirrors BotRoamer.sampleCandidates' floorSweep geometry; LEG_FLOOR/CANDIDATES are 3/8 there.)
        final int legFloor = 3, headings = 8;
        NavGridView world = world();
        int reachable = 0;
        for (int i = 0; i < headings; i++) {
            double heading = i * ((Math.PI * 2.0) / headings);
            int cx = ISLAND_FLOOR.getX() + (int) Math.round(Math.cos(heading) * legFloor);
            int cz = ISLAND_FLOOR.getZ() + (int) Math.round(Math.sin(heading) * legFloor);
            BlockPos target = new BlockPos(cx, ISLAND_Y, cz);
            if (BlockPathfinder.findPath(world, ISLAND_FLOOR, target, ROAMER) != null
                    && !BlockPathfinder.lastWasPartial()) {
                reachable++;
            }
        }
        assertTrue(reachable > 0,
                "the floor sweep must find somewhere to go on an 8x8 island (got " + reachable + "/8)");
    }

    @Test
    void aOneBlockPillarHasNowhereToGoAndShouldPark() {
        // The counterpart: on a 1x1 pillar every floor-sweep heading is off the pillar, so the batch really is
        // a total refusal and parking is the honest verdict rather than a premature give-up.
        final int legFloor = 3, headings = 8;
        NavGridView world = pillarWorld();
        for (int i = 0; i < headings; i++) {
            double heading = i * ((Math.PI * 2.0) / headings);
            int cx = PILLAR_FLOOR.getX() + (int) Math.round(Math.cos(heading) * legFloor);
            int cz = PILLAR_FLOOR.getZ() + (int) Math.round(Math.sin(heading) * legFloor);
            assertNull(BlockPathfinder.findPath(world, PILLAR_FLOOR, new BlockPos(cx, GROUND_Y, cz), ROAMER),
                    "heading " + i + " must be unreachable from a 1x1 pillar");
        }
    }

    // ---- scene ----------------------------------------------------------------------------------------

    /**
     * Chunks (0..4, 0) — a stone ground plane at y=0 across all of them, plus an 8x8 stone platform at
     * y={@value #ISLAND_Y} in chunk 0 with nothing but air connecting the two.
     */
    private static NavGridView world() {
        ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        for (int cx = 0; cx <= 4; cx++) {
            chunks.put(NavStore.key(cx, 0), cx == 0 ? islandColumn() : groundColumn());
        }
        return new NavGridView(0, chunks);
    }

    /** Two sections (y 0..31): a full stone floor plane at y=0, air above. */
    private static NavSection[] groundColumn() {
        return column(null);
    }

    /** The ground column plus the 8x8 island slab at {@link #ISLAND_Y}. */
    private static NavSection[] islandColumn() {
        return column(states -> {
            for (int x = ISLAND_X0; x < ISLAND_X0 + ISLAND_SIZE; x++) {
                for (int z = ISLAND_Z0; z < ISLAND_Z0 + ISLAND_SIZE; z++) {
                    states.set(x, ISLAND_Y & 15, z, Blocks.STONE.defaultBlockState());
                }
            }
        });
    }

    /** The same world with the island reduced to the single block under {@link #PILLAR_FLOOR}. */
    private static NavGridView pillarWorld() {
        ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        NavSection[] pillar = column(states ->
                states.set(PILLAR_FLOOR.getX(), ISLAND_Y & 15, PILLAR_FLOOR.getZ(),
                        Blocks.STONE.defaultBlockState()));
        for (int cx = 0; cx <= 4; cx++) {
            chunks.put(NavStore.key(cx, 0), cx == 0 ? pillar : groundColumn());
        }
        return new NavGridView(0, chunks);
    }

    /** Build a 2-section column with the ground plane, letting {@code extra} paint section 0 as well. */
    private static NavSection[] column(SectionPainter extra) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        NavSection[] col = new NavSection[2];
        for (int i = 0; i < 2; i++) {
            PalettedContainer<BlockState> s = new PalettedContainer<>(
                    air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
            if (i == 0) {
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        s.set(x, GROUND_Y, z, stone);
                    }
                }
                if (extra != null) extra.paint(s);
            }
            col[i] = NavSection.create(BlockPos.ZERO);
            NavSectionBuilder.classifyInto(s, false, col[i].getTraversalGrid());
        }
        NavSectionBuilder.computeDepth(col); // the floorGap nibbles Fall's fast path reads
        return col;
    }

    @FunctionalInterface
    private interface SectionPainter {
        void paint(PalettedContainer<BlockState> section);
    }
}
