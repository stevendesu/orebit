package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.orebit.mod.Debug;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.CandidateSink;
import com.orebit.mod.pathfinding.blockpathfinder.EditScratch;
import com.orebit.mod.pathfinding.blockpathfinder.Movement;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.pathfinding.blockpathfinder.MovementRegistry;
import com.orebit.mod.worldmodel.navblock.NavBlock;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

/**
 * The <b>curtain top-out</b> — climbing out of the top cell of a vine column onto the stance just above it,
 * and stepping laterally off it (owner physics, manual proof 2026-08-01).
 *
 * <h2>The rule that was modelled wrong</h2>
 * The climb precondition is on the cell the feet ARE IN, <b>never</b> on the cell they ENTER. While
 * {@code onClimbable}, jump drives {@code vy=+0.2} every tick the feet remain inside, so the bot rises out
 * of the column's top cell into whatever passable cell is above it. {@link
 * com.orebit.mod.pathfinding.blockpathfinder.movements.Climb Climb}'s exit-top branch additionally demanded
 * the climbable be {@code standable}, which admitted only a scaffold deck and silently deleted the only
 * route off a vine curtain once {@code Ascend} was (correctly) gated on {@code solidFooting} in
 * {@code c84c4b9}.
 *
 * <p><b>The cost of getting it wrong</b>, from the flagship at {@code (58,131,189)}: with no top-out, the
 * only non-descending option was a lateral exit ONE CELL LOW — place a floor at {@code (59,131,189)} and
 * mine the jungle leaves at {@code (59,132,189)} — when the free move was to top out one higher and walk
 * straight onto those same leaves, whose top face IS the top-out feet height.
 *
 * <h2>What the stance can and cannot do</h2>
 * Standing on a curtain top is real, held by sneak (feet inside) or jump (feet above) — so {@code Traverse}
 * off it onto a level block is legal. But the bot is NOT on a standable block, so the 0.42 jump impulse
 * never fires: {@code Ascend} / {@code Parkour} are impossible from there, which {@code solidFooting}
 * already enforces. Auto-step needs ground contact too, so a step-assist UP onto a slab is refused.
 *
 * <h2>The fixture (mirrors the flagship, z=8)</h2>
 * <pre>
 *        x=2            x=3
 *  y11   air            air
 *  y10   air            air            &larr; top-out feet cell / destination head
 *  y9    VINE (top)     STONE          &larr; destination FLOOR, top face y=10
 *  y8    vine           air
 *  y7    vine           air
 *  y6    vine           air
 *  y5    stone          stone
 * </pre>
 * Node under test: {@code (2,8,8)} — floor is vine, feet {@code (2,9,8)} is the curtain's TOP cell.
 */
class CurtainTopOutTest {

    private static final int Z = 8;
    /** The node whose feet sit in the column's top cell. */
    private static final int SRC_X = 2, SRC_Y = 8;
    /** The top-out node it should emit: floor = the curtain top cell, feet = the air above it. */
    private static final int TOP_X = 2, TOP_Y = 9;
    /** The ledge to step onto — a FULL block whose top face is the top-out feet height. */
    private static final int LEDGE_X = 3, LEDGE_Y = 9;

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

    // ---- the descriptor facts the relaxed gate now leans on -------------------------------------

    @Test
    void vineIsClimbableNonStandableAndNotNarrowTop() {
        long vine = NavBlock.descriptorFor(Blocks.VINE.defaultBlockState());
        assertTrue(NavBlock.isClimbable(vine), "a vine is a climb surface");
        assertFalse(NavBlock.isStandable(vine), "nothing to stand ON — the top-out stance is held, not stood");
        assertFalse(NavBlock.isNarrowTop(vine),
                "NARROW_TOP is derived from a NON-EMPTY collision shape; a vine has no collision at all, so "
                        + "the ladder exclusion survives dropping the standable term");
    }

    /**
     * Narrow tops are not floors (owner ruling 2026-08-01). Vanilla would let you stand on a ladder's 3px
     * plate or a dripstone tip, but the servo, momentum conservation and ballistics all assume a full-width
     * block, so the model calls them what they are for our purposes: not a floor.
     */
    @Test
    void narrowTopsAreNotStandable() {
        long ladder = NavBlock.descriptorFor(Blocks.LADDER.defaultBlockState());
        assertTrue(NavBlock.isClimbable(ladder), "a ladder is still a climb surface");
        assertTrue(NavBlock.isNarrowTop(ladder), "the 3/16 plate is under the bot's 0.6 body width");
        assertFalse(NavBlock.isStandable(ladder), "...so it is not a floor");

        long tip = NavBlock.descriptorFor(Blocks.POINTED_DRIPSTONE.defaultBlockState());
        assertTrue(NavBlock.isNarrowTop(tip), "a dripstone tip is a narrow post");
        assertFalse(NavBlock.isStandable(tip),
                "THE FLAGSHIP WEDGE: the bot hopped forever on the tip at (148,29,7), resting at "
                        + "botY 29.688 == 29 + 11/16, while its Ascend expected to stand at feet 30.0");

        // The subtraction must not leak into the other derived bits.
        assertFalse(NavBlock.isPassable(tip), "still a wall for transit — passability is shape-derived");
        assertTrue(NavBlock.isBreakable(tip), "still mineable");
        assertTrue(NavBlock.hasCollision(tip), "still a face to build against");

        // A full-width partial top is untouched: soul sand (14/16) is a real floor.
        assertTrue(NavBlock.isStandable(NavBlock.descriptorFor(Blocks.SOUL_SAND.defaultBlockState())),
                "the rule is about NARROWNESS, not about partial height");
    }

    // ---- (1) Climb tops out of a vine curtain ---------------------------------------------------

    @Test
    void climbTopsOutOfAVineCurtain() {
        assertTrue(emits(MovementRegistry.CLIMB, curtain(Blocks.VINE.defaultBlockState()), SRC_X, SRC_Y, TOP_X, TOP_Y),
                "from feet in the column's TOP cell, the climb must rise into the passable cell above — the "
                        + "precondition is on the cell the feet are IN, not the one they enter");
    }

    @Test
    void scaffoldingTopOutIsUnchanged() {
        assertTrue(emits(MovementRegistry.CLIMB, curtain(Blocks.SCAFFOLDING.defaultBlockState()),
                        SRC_X, SRC_Y, TOP_X, TOP_Y),
                "the standable-deck top-out is the case that already worked and must keep working");
    }

    @Test
    void ladderTopsOutToo() {
        assertTrue(emits(MovementRegistry.CLIMB, curtain(Blocks.LADDER.defaultBlockState()),
                        SRC_X, SRC_Y, TOP_X, TOP_Y),
                "NARROW_TOP is a TAKEOFF/precision-LANDING restriction, never a standing one — a ladder's "
                        + "3/16 plate is an awkward stance, not an impossible one (owner ruling 2026-08-01)");
    }

    /**
     * The hazard {@code NARROW_TOP} was wrongly guarding in the exit-top branch: the alternating
     * ladder/air/ladder ascent. It is refused by the RIGHT test — a climbable floor cell is never solid
     * footing — so blocking the climb-out bought no safety at all.
     */
    @Test
    void noJumpOfAnyKindFromALadderTop() {
        NavGridView g = curtain(Blocks.LADDER.defaultBlockState());
        assertFalse(emits(MovementRegistry.ASCEND, g, TOP_X, TOP_Y),
                "solidFooting rejects a climbable floor cell — no 0.42 launch off a ladder plate");
        assertFalse(emits(MovementRegistry.PARKOUR, g, TOP_X, TOP_Y),
                "same gate for the gap jump");
        assertFalse(emits(MovementRegistry.CLIMB, g, TOP_X, TOP_Y, TOP_X, TOP_Y + 1),
                "and §3.3's jump-grab excludes it again with its own !isClimbable(floor) — this is the "
                        + "ladder/air/ladder alternation the FACING-blind descriptor must never plan");
    }

    @Test
    void noTopOutUnderACeiling() {
        PalettedContainer<BlockState> s = curtainStates(Blocks.VINE.defaultBlockState());
        s.set(TOP_X, TOP_Y + 1, Z, Blocks.STONE.defaultBlockState()); // seal the cell the feet would enter
        assertFalse(emits(MovementRegistry.CLIMB, wrap(s), SRC_X, SRC_Y, TOP_X, TOP_Y),
                "there must be somewhere to rise INTO — a sealed cell above is not a top-out");
    }

    // ---- (2) an ordinary Traverse steps off the curtain top ------------------------------------

    @Test
    void traverseStepsOffTheCurtainTopOntoALevelLedge() {
        assertTrue(emits(MovementRegistry.TRAVERSE, curtain(Blocks.VINE.defaultBlockState()),
                        TOP_X, TOP_Y, LEDGE_X, LEDGE_Y),
                "from the top-out node, a plain flat walk reaches the adjacent full block whose top face IS "
                        + "the feet height — no new movement, no jump, no edits");
    }

    // ---- (3) what the stance must NOT be able to do ---------------------------------------------

    @Test
    void noJumpTakeoffFromACurtainTop() {
        assertFalse(emits(MovementRegistry.ASCEND, curtain(Blocks.VINE.defaultBlockState()), TOP_X, TOP_Y),
                "the 0.42 impulse fires only under onGround, and a curtain top is not a standable block");
        assertFalse(emits(MovementRegistry.PARKOUR, curtain(Blocks.VINE.defaultBlockState()), TOP_X, TOP_Y),
                "same gate — solidFooting already refuses every jump takeoff from a held stance");
    }

    @Test
    void noStepAssistUpOffACurtain() {
        // A slab one cell up beside the curtain top: rise = 16 + 8 - 16 = 8/16, inside the auto-step budget
        // on paper — but auto-step needs ground contact, which a held stance never has.
        PalettedContainer<BlockState> s = curtainStates(Blocks.VINE.defaultBlockState());
        s.set(LEDGE_X, LEDGE_Y + 1, Z, Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
        assertFalse(emits(MovementRegistry.TRAVERSE, wrap(s), TOP_X, TOP_Y, LEDGE_X, LEDGE_Y + 1),
                "step assist does not engage from a curtain — you may only walk onto a block whose top "
                        + "equals your feet height");
    }

    /**
     * The ladder's 3/16 plate DOES support a step — but only toward the face the ladder is mounted on;
     * the other way walks straight off the ledge. FACING isn't packed in the descriptor, so the direction
     * that works is unknowable to the planner and the whole case is refused rather than guessed.
     */
    @Test
    void noStepAssistOffALadderTop() {
        PalettedContainer<BlockState> s = curtainStates(Blocks.LADDER.defaultBlockState());
        s.set(LEDGE_X, LEDGE_Y + 1, Z, Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
        assertFalse(emits(MovementRegistry.TRAVERSE, wrap(s), TOP_X, TOP_Y, LEDGE_X, LEDGE_Y + 1),
                "a NARROW_TOP stance auto-steps only in the mounted direction — unknowable, so refused");
    }

    // ---- what a SCAFFOLD deck may do that a vine and a ladder may not ----------------------------

    /**
     * Standing on a scaffold deck is standing on solid ground: the climbable is BELOW the feet, not IN
     * them, so neither physical jump-inhibitor applies (owner ruling 2026-08-01). {@code solidFooting}
     * used to refuse it purely because the floor block happened to be climbable.
     */
    @Test
    void scaffoldDeckIsARealJumpTakeoff() {
        NavGridView g = curtain(Blocks.SCAFFOLDING.defaultBlockState());
        MovementContext ctx = new MovementContext(g, BotCaps.DEFAULT);
        assertTrue(ctx.solidFooting(TOP_X, TOP_Y, Z),
                "deck = solid ground under the feet + a non-climbable feet cell = a legal 0.42 launch");
        assertFalse(ctx.solidFooting(SRC_X, SRC_Y, Z),
                "control: one cell lower the FEET are inside the scaffolding, which truncates the jump");
    }

    @Test
    void vineAndLadderAreNotJumpTakeoffs() {
        assertFalse(new MovementContext(curtain(Blocks.VINE.defaultBlockState()), BotCaps.DEFAULT)
                        .solidFooting(TOP_X, TOP_Y, Z),
                "a vine top has no support at all — refused by standable");
        assertFalse(new MovementContext(curtain(Blocks.LADDER.defaultBlockState()), BotCaps.DEFAULT)
                        .solidFooting(TOP_X, TOP_Y, Z),
                "a ladder plate IS solid, but NARROW_TOP refuses it as a takeoff — the term that actually "
                        + "means 'no alternating ladder/air/ladder ascent'");
    }

    @Test
    void stepAssistFromRealGroundStillWorks() {
        // The control for the gate above: identical slab, but the bot stands on STONE.
        PalettedContainer<BlockState> s = curtainStates(Blocks.VINE.defaultBlockState());
        s.set(SRC_X, 5, Z, Blocks.STONE.defaultBlockState());
        for (int y = 6; y <= 8; y++) s.set(SRC_X, y, Z, Blocks.AIR.defaultBlockState()); // clear the curtain
        s.set(LEDGE_X, 6, Z, Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
        for (int y = 7; y <= 9; y++) s.set(LEDGE_X, y, Z, Blocks.AIR.defaultBlockState());
        assertTrue(emits(MovementRegistry.TRAVERSE, wrap(s), SRC_X, 5, LEDGE_X, 6),
                "the curtain gate must not touch ordinary auto-step off solid ground");
    }

    // ---- helpers ---------------------------------------------------------------------------------

    /** Whether {@code move} expanding node {@code (sx,sy,Z)} emits a candidate at {@code (tx,ty,Z)}. */
    private static boolean emits(Movement move, NavGridView grid, int sx, int sy, int tx, int ty) {
        MovementContext ctx = new MovementContext(grid, BotCaps.DEFAULT);
        boolean[] hit = { false };
        move.candidates(ctx, sx, sy, Z, new CandidateSink() {
            @Override
            public void accept(int x, int y, int z, float cost, EditScratch edits) {
                if (x == tx && y == ty && z == Z) hit[0] = true;
            }
        });
        return hit[0];
    }

    /** Whether {@code move} emits ANY candidate from node {@code (sx,sy,Z)}. */
    private static boolean emits(Movement move, NavGridView grid, int sx, int sy) {
        MovementContext ctx = new MovementContext(grid, BotCaps.DEFAULT);
        int[] n = { 0 };
        move.candidates(ctx, sx, sy, Z, new CandidateSink() {
            @Override
            public void accept(int x, int y, int z, float cost, EditScratch edits) {
                n[0]++;
            }
        });
        return n[0] > 0;
    }

    private static NavGridView curtain(BlockState climbable) {
        return wrap(curtainStates(climbable));
    }

    /**
     * The fixture: a 4-cell {@code climbable} column at x=2 (y=6..9) rising off a stone floor at y=5, open
     * air above it, and a full stone ledge at {@code (3,9,8)} whose top face is the top-out feet height.
     */
    private static PalettedContainer<BlockState> curtainStates(BlockState climbable) {
        PalettedContainer<BlockState> s = solidStone();
        BlockState air = Blocks.AIR.defaultBlockState();
        // Carve a hall around the column: x=1..4, y=6..12 all air, floor stays stone at y=5.
        for (int x = 1; x <= 4; x++) {
            for (int y = 6; y <= 12; y++) s.set(x, y, Z, air);
        }
        for (int y = 6; y <= 9; y++) s.set(SRC_X, y, Z, climbable); // the column, top cell at y=9
        s.set(LEDGE_X, LEDGE_Y, Z, Blocks.STONE.defaultBlockState()); // the ledge, top face at y=10
        return s;
    }

    private static PalettedContainer<BlockState> solidStone() {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        PalettedContainer<BlockState> s = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) s.set(x, y, z, stone);
            }
        }
        return s;
    }

    /** Wrap one classified 16³ section (plus 3 air sections above) into a NavGridView at chunk (0,0). */
    private static NavGridView wrap(PalettedContainer<BlockState> s) {
        NavSection section = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(s, false, section.getTraversalGrid());

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
