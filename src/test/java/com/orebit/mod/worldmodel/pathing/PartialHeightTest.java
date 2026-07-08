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
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
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
 * Headless proof of the <b>block-height canon</b> (heights in sixteenths; one jump gains
 * {@link MovementContext#JUMP_RISE} = 20, step assist clears {@link MovementContext#STEP_ASSIST_MAX_RISE}
 * = 9) applied start-side as well as destination-side. Partial-height floors ARE expressible in this
 * harness — the courses classify real {@link BlockState}s ({@code STONE_SLAB} bottom = top 8/16,
 * {@code REPEATER} = top 2/16) through {@code NavSectionBuilder.classifyInto}, exactly like the
 * ParkourLandingsTest fixture — so every rule below is exercised on real navtype descriptors.
 *
 * <p><b>Rules under test (derivations at the constants / gates):</b>
 * <ul>
 *   <li><b>Ascend start deficit</b> — rise = {@code 16 + destTopY − startTopY}: slab → full one up is
 *       24 &gt; 20 (impossible); slab → slab one up is 16 (an ordinary jump); full → slab one up is
 *       8 ≤ 9 (Traverse's step-assist, NOT an Ascend).</li>
 *   <li><b>Traverse same-level lip</b> — {@code destTopY − startTopY} ≤ 9 to walk: a 2/16 repeater
 *       plate onto a full block is a 14/16 rise no auto-step clears (and — the documented KNOWN GAP —
 *       no movement emits the same-block-level JUMP that lip physically allows, so the route is null,
 *       not rerouted).</li>
 *   <li><b>Pillar full-start gate</b> — the placed cube's top is {@code 32 − startTopY} above the start
 *       surface; from a slab {@code 8 + 20 = 28 < 32} — no pillar off a slab.</li>
 *   <li><b>Parkour rising start deficit</b> — the +1 landing obeys the same 24 &gt; 20 rejection from a
 *       slab takeoff.</li>
 * </ul>
 *
 * <p><b>Not testable headless (documented gaps):</b> the topY-aware irreversibility guard
 * ({@code BlockPathfinder.lastReversibleRow} — drop onto a slab reads 1.5 blocks deep) needs a
 * budget-exhausted PARTIAL search to reach the guard walk, which these single-section courses can't
 * force deterministically; verified by inspection + in-game. Jump kinematics remain the in-game pass.
 */
class PartialHeightTest {

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
    private static final BlockPos START = new BlockPos(2, 5, 8);

    private static BlockState stone() {
        return Blocks.STONE.defaultBlockState();
    }

    /** Bottom stone slab — collision top 8/16 (SHAPE_SLAB_BOTTOM), standable. */
    private static BlockState slab() {
        return Blocks.STONE_SLAB.defaultBlockState();
    }

    /** Repeater — 2/16-tall full-area plate (SHAPE_PARTIAL_LOW), standable; the "very low partial". */
    private static BlockState lowPlate() {
        return Blocks.REPEATER.defaultBlockState();
    }

    // ---------------------------------------------------------------- Ascend (rule 1)

    @Test
    void slabStartCannotAscendAFullBlockStep() {
        // Control: full start → full step one up is rise 16+16−16 = 16 ≤ 20 — the everyday Ascend.
        assertNotNull(BlockPathfinder.findPath(buildStep(stone(), stone()), START,
                        new BlockPos(6, 6, 8), BotCaps.DEFAULT, CORRIDOR),
                "sanity: a full-block start ascends a full step");
        // Slab start → full step one up is rise 16+16−8 = 24 > JUMP_RISE 20: you cannot ascend 1.5
        // blocks, and no other move covers it (step-assist needs ≤ 9; no place caps) — route is null.
        assertNull(BlockPathfinder.findPath(buildStep(slab(), stone()), START,
                        new BlockPos(6, 6, 8), BotCaps.DEFAULT, CORRIDOR),
                "a slab start must not ascend onto a full block one level up (rise 24 > 20)");
    }

    @Test
    void slabStartAscendsASlabStep() {
        // Slab → slab one up: rise 16+8−8 = 16 ≤ 20 and > 9, so it is exactly one jump — an Ascend,
        // not a step-assist.
        BlockPathPlan plan = BlockPathfinder.findPath(buildStep(slab(), slab()), START,
                new BlockPos(6, 6, 8), BotCaps.DEFAULT, CORRIDOR);
        assertNotNull(plan, "slab → slab one up is an ordinary 16/16 jump");
        assertTrue(count(plan, MovementRegistry.ASCEND) >= 1,
                "the 16/16 rise is above the 9/16 auto-step budget, so the step must be an Ascend");
    }

    @Test
    void fullStartStepAssistsOntoASlab() {
        // Full → slab one up: rise 16+8−16 = 8 ≤ STEP_ASSIST_MAX_RISE 9 — Traverse's auto-step owns it;
        // Ascend must NOT claim it (the partition the two movements share).
        BlockPathPlan plan = BlockPathfinder.findPath(buildStep(stone(), slab()), START,
                new BlockPos(6, 6, 8), BotCaps.DEFAULT, CORRIDOR);
        assertNotNull(plan, "an 8/16 lip one level up is auto-stepped");
        assertEquals(0, count(plan, MovementRegistry.ASCEND),
                "an auto-steppable lip belongs to Traverse's step-assist, never an Ascend jump");
        assertTrue(count(plan, MovementRegistry.TRAVERSE) >= 1,
                "the step onto the slab is a Traverse (step-assist) waypoint");
    }

    // ---------------------------------------------------------------- Traverse same-level lip (rule 2)

    @Test
    void lowPartialStartRejectsAFlatLipAboveNineSixteenths() {
        // Control: slab start onto full-block floors at the SAME level is an 8/16 lip — walkable.
        assertNotNull(BlockPathfinder.findPath(buildFlat(slab()), START,
                        new BlockPos(6, 5, 8), BotCaps.DEFAULT, CORRIDOR),
                "sanity: an 8/16 same-level lip (slab → full) auto-steps");
        // Repeater start (top 2/16) onto full-block floors: lip 14/16 > 9 — not auto-steppable. A
        // 10..20/16 same-level lip IS physically jumpable (JUMP_RISE), but no movement emits a
        // same-block-level jump today (the documented known gap), so the route must be null rather
        // than silently walked.
        assertNull(BlockPathfinder.findPath(buildFlat(lowPlate()), START,
                        new BlockPos(6, 5, 8), BotCaps.DEFAULT, CORRIDOR),
                "a 14/16 same-level lip must not be walked flat (and no move offers the jump — known gap)");
    }

    // ---------------------------------------------------------------- Pillar full-start gate (rule 3)

    @Test
    void pillarRequiresAFullHeightStartFloor() {
        BotCaps placeOnly = new BotCaps(1, BotCaps.DEFAULT_SAFE_FALL, BotCaps.DEFAULT_MAX_FALL, true,
                BotCaps.DEFAULT_COST_PER_HITPOINT, false, true, BotCaps.UNBREAKABLE, false,
                BotCaps.DEFAULT_MAX_NODES, BotCaps.DEFAULT_GREEDY_WEIGHT);
        // Control: a full stone floor at the bottom of the 1×1 shaft pillars up to the goal.
        BlockPathPlan plan = BlockPathfinder.findPath(buildShaft(stone()), START,
                new BlockPos(2, 8, 8), placeOnly, CORRIDOR);
        assertNotNull(plan, "sanity: a place-capable bot pillars a 1×1 shaft from a full floor");
        assertTrue(count(plan, MovementRegistry.PILLAR) >= 1,
                "the shaft's only route is the pillar");
        // From a slab the jump apex is 8 + 20 = 28/16 above the block base — the feet can never clear
        // the 32/16 top of the cube being placed into their own cell: no pillar, and the shaft offers
        // nothing else, so the route is null.
        assertNull(BlockPathfinder.findPath(buildShaft(slab()), START,
                        new BlockPos(2, 8, 8), placeOnly, CORRIDOR),
                "a slab start must not pillar (apex 28 < placed-cube top 32)");
    }

    // ---------------------------------------------------------------- Parkour rising takeoff (rule 4)

    @Test
    void slabTakeoffCannotRiseJumpOntoAFullLedge() {
        // Control: full-block takeoff platform, 2-gap, +1 full-block landing — the owner-verified rising
        // jump (ParkourLandingsTest's positive, rebuilt here with a parameterised platform floor).
        assertNotNull(BlockPathfinder.findPath(buildJumpCourse(stone(), 2), START,
                        new BlockPos(8, 6, 8), BotCaps.DEFAULT, CORRIDOR),
                "sanity: the rising 2-gap lands from a full-block takeoff");
        // Slab takeoff platform: the +1 full-block landing needs rise 16+16−8 = 24 > 20 — the arc apex
        // (start surface + 20/16) never reaches the landing top, so the rising row must not emit and no
        // other route exists (no place caps, bottomless chasm).
        assertNull(BlockPathfinder.findPath(buildJumpCourse(slab(), 2), START,
                        new BlockPos(8, 6, 8), BotCaps.DEFAULT, CORRIDOR),
                "a slab takeoff must not rising-jump onto a full-block +1 ledge (rise 24 > 20)");
    }

    // ---------------------------------------------------------------- helpers

    private static int count(BlockPathPlan plan, Object move) {
        int n = 0;
        for (int i = 0; i < plan.size(); i++) {
            if (plan.movement(i) == move) n++;
        }
        return n;
    }

    /**
     * Course A — the one-block STEP: sealed stone section, 1-wide corridor at {@code z=8}. Start ledge
     * {@code x=1..2} with its floor CELL {@code y=5} holding {@code startFloor} (air {@code y=6..12});
     * raised platform {@code x=3..14} with its floor cell {@code y=6} holding {@code raisedFloor}
     * (air {@code y=7..12}, stone below). The only route is the step up at {@code x=3}.
     */
    private static NavGridView buildStep(BlockState startFloor, BlockState raisedFloor) {
        PalettedContainer<BlockState> s = solidStone();
        final int z = 8;
        for (int x = 1; x <= 2; x++) {
            s.set(x, 5, z, startFloor);
            for (int y = 6; y <= 12; y++) {
                s.set(x, y, z, Blocks.AIR.defaultBlockState());
            }
        }
        for (int x = 3; x <= 14; x++) {
            s.set(x, 6, z, raisedFloor);
            for (int y = 7; y <= 12; y++) {
                s.set(x, y, z, Blocks.AIR.defaultBlockState());
            }
        }
        return toGrid(s);
    }

    /**
     * Course B — the FLAT lip: same corridor, every floor cell at {@code y=5}; the start cells
     * {@code x=1..2} hold {@code startFloor}, the rest full stone. The only route is the same-level
     * walk across the lip at {@code x=3}.
     */
    private static NavGridView buildFlat(BlockState startFloor) {
        PalettedContainer<BlockState> s = solidStone();
        final int z = 8;
        for (int x = 1; x <= 14; x++) {
            if (x <= 2) s.set(x, 5, z, startFloor);
            for (int y = 6; y <= 12; y++) {
                s.set(x, y, z, Blocks.AIR.defaultBlockState());
            }
        }
        return toGrid(s);
    }

    /**
     * Course C — the 1×1 SHAFT: air only at {@code (2, 6..12, 8)}, floor cell {@code (2,5,8)} holds
     * {@code startFloor}. A place-capable bot's only way up is Pillar.
     */
    private static NavGridView buildShaft(BlockState startFloor) {
        PalettedContainer<BlockState> s = solidStone();
        s.set(2, 5, 8, startFloor);
        for (int y = 6; y <= 12; y++) {
            s.set(2, y, 8, Blocks.AIR.defaultBlockState());
        }
        return toGrid(s);
    }

    /**
     * Course D — the RISING-jump course (the ParkourLandingsTest layout with a parameterised takeoff
     * platform floor): takeoff platform {@code x=1..4} floor cells {@code y=5} holding
     * {@code platformFloor} (air {@code y=6..10}), a {@code g}-wide bottomless chasm
     * ({@code x=5..4+g}, air {@code y=0..10}), landing platform {@code x=5+g..14} full stone with floor
     * {@code y=6} (air {@code y=7..10}).
     */
    private static NavGridView buildJumpCourse(BlockState platformFloor, int g) {
        PalettedContainer<BlockState> s = solidStone();
        final int z = 8;
        for (int x = 1; x <= 4; x++) {
            s.set(x, 5, z, platformFloor);
            for (int y = 6; y <= 10; y++) {
                s.set(x, y, z, Blocks.AIR.defaultBlockState());
            }
        }
        for (int x = 5; x <= 4 + g; x++) {
            for (int y = 0; y <= 10; y++) {
                s.set(x, y, z, Blocks.AIR.defaultBlockState());
            }
        }
        for (int x = 5 + g; x <= 14; x++) {
            for (int y = 7; y <= 10; y++) {
                s.set(x, y, z, Blocks.AIR.defaultBlockState());
            }
        }
        return toGrid(s);
    }

    private static PalettedContainer<BlockState> solidStone() {
        PalettedContainer<BlockState> s = new PalettedContainer<>(
                Blocks.AIR.defaultBlockState(), Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        BlockState stone = Blocks.STONE.defaultBlockState();
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    s.set(x, y, z, stone);
                }
            }
        }
        return s;
    }

    /** Classify the section and wrap it in a single-chunk grid (air sections above — the fixture idiom). */
    private static NavGridView toGrid(PalettedContainer<BlockState> states) {
        NavSection section = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(states, false, section.getTraversalGrid());

        PalettedContainer<BlockState> airStates = new PalettedContainer<>(
                Blocks.AIR.defaultBlockState(), Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        NavSection airSection = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(airStates, true, airSection.getTraversalGrid());

        NavSection[] column = { section, airSection, airSection, airSection };
        ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        chunks.put(NavStore.key(0, 0), column);
        return new NavGridView(0, chunks);
    }
}
