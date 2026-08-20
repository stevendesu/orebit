package com.orebit.mod.worldmodel.pathing;

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
 * Headless proof of Pillar's R1 enrollment ({@code MovementContext.canJumpFrom} — owner ruling
 * 2026-08-19, the flagship vine-curtain conviction at {@code (196,123,330)}): you CANNOT pillar-jump
 * from a climbable stance. Pillar was the ONLY jump-requiring move unenrolled in the takeoff rule —
 * its {@code floorSurface} gate is a HEIGHT test whose 16-sentinel makes a non-standable vine floor
 * read as a full cube, so a bot floating at a vine crest (or with its feet IN a vine) was offered a
 * jump-and-place the physics cannot launch (the jump input climbs — vanilla truncates the 0.42 impulse
 * to the 0.2 climb rate).
 *
 * <p>Deliberately NOT {@code solidFooting} (the Parkour/Ascend launch gate): that also refuses a WATER
 * floor, which would delete the flooded-shaft swim-pillar (held jump = swim-up) that the 16-sentinel
 * deliberately exempts. {@code canJumpFrom} refuses exactly (a) feet in a climbable and (b) a
 * no-collision climbable floor (the {@code Climb} §3.4 crest-float stance), and passes a scaffolding
 * DECK (standable — the climbable is below the feet, not in them) and a water floor. The positive
 * tests here pin those exemptions so the gate can never quietly widen into {@code solidFooting}.
 *
 * <p>Scene: a sealed stone section with a 1×1 vertical shaft at {@code (8, 6..14, 8)}. Start floor
 * cell {@code (8,5,8)}; goal floor cell {@code (8,10,8)} — the goal floor is carved AIR, so the goal is
 * standable ONLY by a Pillar chain that places it (feet 6 → place → feet 7 → place → feet 8 = floor
 * {@code (8,8,8)}). Walls are stone everywhere else, so no Ascend/Parkour/bridge alternative exists;
 * whether Pillar emits from the start stance decides reachability outright.
 *
 * <p>All tests use place-only caps ({@code canPlace} ON, {@code canBreak} OFF): Pillar needs the place
 * cap to emit at all, and breaking must stay off in the negative tests — with mining allowed the
 * search could legally fold a break of the vine and THEN pillar off the cleared stance (physically
 * correct!), turning the expected-null plans non-null.
 *
 * <p>Lives in this package to reach {@link NavGridView}'s package-private synthetic constructor.
 */
class PillarFromVineTest {

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
    /** The start floor cell (vine / stone / water / scaffolding) — the bot starts here, MODE_STANDING. */
    private static final BlockPos START = new BlockPos(8, 5, 8);
    /** Three pillar steps up the shaft — a floor cell that must be PLACED to be stood on. */
    private static final BlockPos GOAL = new BlockPos(8, 10, 8); // 4+ above the crest: outside the
    // default goal tolerance, so a climb to the vine top can never be tolerance-FOUND (the 2026-08-19
    // first draft put it at (8,8,8) and the crest node at feet (8,7,8) satisfied the +/-1 goal test).

    /**
     * Place-only caps: {@link BotCaps#DEFAULT} with {@code canPlace} on and {@code canBreak} kept OFF
     * (see the class Javadoc — a break-capable search defeats the negative tests by legally mining the
     * vine first). Same 11-component back-compat shape as the {@link BotCaps#BREAK_PLACE} preset.
     */
    private static final BotCaps PLACE_ONLY = new BotCaps(
            1, BotCaps.DEFAULT_SAFE_FALL, BotCaps.DEFAULT_MAX_FALL, true, BotCaps.DEFAULT_COST_PER_HITPOINT,
            /*canBreak=*/false, /*canPlace=*/true, BotCaps.UNBREAKABLE, false,
            BotCaps.DEFAULT_MAX_NODES, BotCaps.DEFAULT_GREEDY_WEIGHT);

    @Test
    void refusesToPillarFromAVineCrest() {
        // Start floor (8,5,8) = VINE with a vine column below (classification is per-cell; the column
        // just makes the stance a genuine mid-climb crest — floor vine, feet air, the §3.4 EXIT-TOP
        // float). canJumpFrom part (b): a no-collision climbable floor has no surface to push off.
        NavGridView grid = buildScene(Blocks.VINE.defaultBlockState(), Blocks.AIR.defaultBlockState(),
                /*vineColumnBelow=*/true);
        BlockPathPlan plan = BlockPathfinder.findPath(grid, START, GOAL, PLACE_ONLY, CORRIDOR);

        // The null plan ALSO guards the from-below hole: the search climbs down the vine column and
        // tries Pillar from every node (e.g. floor (8,4,8) with feet (8,5,8) = vine, and the stone
        // floor (8,0,8) at the column base with feet (8,1,8) = vine) — each is refused by the
        // feet-in-climbable half, or the whole chain up through the replaceable vine cells would plan.
        assertNull(plan, "no jump impulse exists at a vine crest — the shaft goal must be unreachable");
    }

    @Test
    void refusesToPillarWithFeetInAVine() {
        // Start floor (8,5,8) = STONE, feet cell (8,6,8) = a single VINE. Vine is REPLACEABLE, so
        // Pillar's openForPlace gate passes and the stone floor passes floorSurface (16) — ONLY
        // canJumpFrom's feet-in-climbable half refuses. The discriminating case: pre-fix this scene
        // planned a pillar chain straight up through the vine.
        NavGridView grid = buildScene(Blocks.STONE.defaultBlockState(), Blocks.VINE.defaultBlockState(),
                /*vineColumnBelow=*/false);
        BlockPathPlan plan = BlockPathfinder.findPath(grid, START, GOAL, PLACE_ONLY, CORRIDOR);

        assertNull(plan, "feet in a vine: the jump input climbs, it cannot launch — no pillar, no route");
    }

    @Test
    void stillPillarsFromStone() {
        // Positive guard: plain stone floor, air feet. The gate must not narrow the ordinary pillar.
        NavGridView grid = buildScene(Blocks.STONE.defaultBlockState(), Blocks.AIR.defaultBlockState(),
                /*vineColumnBelow=*/false);
        BlockPathPlan plan = BlockPathfinder.findPath(grid, START, GOAL, PLACE_ONLY, CORRIDOR);

        assertNotNull(plan, "a stone-footed bot should still pillar up the shaft");
        assertTrue(firstPillar(plan) >= 0, "the ascent should be a PILLAR chain");
    }

    @Test
    void stillPillarsFromAFloodedFloor() {
        // Positive guard: WATER floor cell (a one-cell flooded shaft bottom, air feet above). Water is
        // not climbable, so canJumpFrom passes; floorSurface's 16-sentinel admits the non-standable
        // floor exactly as before. This is the flooded-shaft swim-pillar (held jump = swim-up) that
        // solidFooting would have wrongly deleted — the reason canJumpFrom exists as its own predicate.
        NavGridView grid = buildScene(Blocks.WATER.defaultBlockState(), Blocks.AIR.defaultBlockState(),
                /*vineColumnBelow=*/false);
        BlockPathPlan plan = BlockPathfinder.findPath(grid, START, GOAL, PLACE_ONLY, CORRIDOR);

        assertNotNull(plan, "a water-floored bot swim-pillars: held jump lifts, the placed footing lands it");
        assertTrue(firstPillar(plan) >= 0, "the ascent should be a PILLAR chain");
    }

    @Test
    void stillPillarsFromAScaffoldDeck() {
        // Positive guard: standing ON a scaffolding deck (SHAPE_FULL, STANDABLE, CLIMB — the climbable
        // is below the feet, not in them). canJumpFrom's floor half requires climbable AND
        // not-standable to refuse, so the deck passes — a real deck-top jump exists in vanilla.
        NavGridView grid = buildScene(Blocks.SCAFFOLDING.defaultBlockState(), Blocks.AIR.defaultBlockState(),
                /*vineColumnBelow=*/false);
        BlockPathPlan plan = BlockPathfinder.findPath(grid, START, GOAL, PLACE_ONLY, CORRIDOR);

        assertNotNull(plan, "a scaffolding DECK is a standable jump surface — the pillar must survive");
        assertTrue(firstPillar(plan) >= 0, "the ascent should be a PILLAR chain");
    }

    /** Index of the first PILLAR step, or {@code -1}. */
    private static int firstPillar(BlockPathPlan plan) {
        for (int i = 0; i < plan.size(); i++) {
            if (plan.movement(i) == MovementRegistry.PILLAR) return i;
        }
        return -1;
    }

    /**
     * One sealed stone section (chunk 0,0). Solid everywhere, then carved: a 1×1 vertical shaft at
     * {@code (8, 6..14, 8)} (air), the start floor cell {@code (8,5,8)} set to {@code floorCell}, and
     * the feet cell {@code (8,6,8)} set to {@code feetCell} (air or a single vine). When
     * {@code vineColumnBelow}, {@code (8, 1..4, 8)} is filled with vine so the start stance is a
     * genuine mid-climb crest. Everything else stays stone, so the goal floor {@code (8,10,8)} — carved
     * air — is standable only via a Pillar chain that places it.
     */
    private static NavGridView buildScene(BlockState floorCell, BlockState feetCell, boolean vineColumnBelow) {
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

        for (int y = 6; y <= 14; y++) s.set(8, y, 8, air);  // the 1×1 shaft (apex headroom to y+3 for the last pillar)
        s.set(8, 5, 8, floorCell);                          // the start floor stance under test
        s.set(8, 6, 8, feetCell);                           // air, or the single feet-in-vine cell
        if (vineColumnBelow) {
            for (int y = 1; y <= 4; y++) s.set(8, y, 8, Blocks.VINE.defaultBlockState());
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
