package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.orebit.mod.Debug;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.CandidateSink;
import com.orebit.mod.pathfinding.blockpathfinder.EditScratch;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
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
 * Headless proof of the {@code Fall} WATER CUSHION — a standable landing whose FEET cell holds water takes
 * no fall damage from any height (NOTES-movement-physics.md §7), gated by whether entry momentum actually
 * carries the bot down through the water column.
 *
 * <p>The mechanism being pinned (1.21.11 bytecode): {@code LivingEntity.checkFallDamage} refreshes water
 * state — which calls {@code resetFallDistance()} off the bot's FINAL post-move AABB — strictly BEFORE
 * {@code Entity.checkFallDamage} runs {@code Block.fallOn}. So the reset is speed-independent and needs no
 * tunneling bound, unlike the vine arrest ({@link Fall#HANG_MAX_DROP}).
 *
 * <p>Geometry mirrors {@link FallSoftLandingTest}: a mortal bot ({@link BotCaps#DEFAULT} — safeFall 3,
 * maxFall 16, 100 ticks/HP) walks off a stone floor at {@code (2,40,8)} into the {@code x=3} drop column.
 * Lives in this package to reach {@link NavGridView}'s package-private synthetic constructor.
 */
class WaterCushionFallTest {

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

    private static final BotCaps MORTAL = BotCaps.DEFAULT;
    private static final int HP = (int) BotCaps.DEFAULT_COST_PER_HITPOINT; // 100
    private static final int SAFE = BotCaps.DEFAULT_SAFE_FALL;             // 3
    private static final int SX = 2, START_Y = 40, NX = 3, NZ = 8;

    /** The undamaged cost of a clean drop of {@code depth} blocks (no transit surcharges in these scenes). */
    private static float cleanCost(int depth) {
        return Fall.BASE_COST + depth * Fall.PER_BLOCK;
    }

    // ---- The headline: water on the ground cushions an arbitrarily deep drop ---------------------------

    @Test
    void oneBlockOfWaterOnStoneCushionsADeepDrop() {
        // 30-block drop onto stone with a single water block standing on it. Damage-free, however deep.
        float cost = fallCost(column(Blocks.STONE, 10, Blocks.WATER, 1));
        assertFalse(Float.isNaN(cost), "a 30-block drop into 1 block of water on stone must be permitted");
        assertEquals(cleanCost(30), cost, 1e-3, "a water-cushioned landing adds NO fall-damage cost");
    }

    @Test
    void theSameDropOntoDryStoneIsRejected() {
        // The control: remove the water and the identical drop is past the mortal budget.
        assertTrue(Float.isNaN(fallCost(column(Blocks.STONE, 10, Blocks.AIR, 0))),
                "a 30-block drop onto dry stone must still be rejected");
    }

    @Test
    void theCushionRemovesTheDamageCostOnAnAlreadySurvivableDrop() {
        // A 10-block drop is survivable either way — this pins that the cushion zeroes the damage TERM,
        // not merely the acceptance gate (the reason the check cannot live only in the reject branch).
        float wet = fallCost(column(Blocks.STONE, 30, Blocks.WATER, 1));
        float dry = fallCost(column(Blocks.STONE, 30, Blocks.AIR, 0));
        assertEquals(cleanCost(10), wet, 1e-3, "water must zero the damage term on a survivable drop");
        assertEquals(cleanCost(10) + (10 - SAFE) * HP, dry, 1e-2, "the dry control still pays full damage");
    }

    // ---- The suspended-water case falls out for free ---------------------------------------------------

    @Test
    void suspendedWaterDoesNotCushion() {
        // Water floating with AIR beneath it (the "water balanced on a sign" shape, minus the sign). The bot
        // would tunnel through at speed and hit the real floor 30 blocks down. No special handling exists
        // for this: the scan only ever accepts a STANDABLE landing, so it walks past the suspended water to
        // the stone at y=10, whose feet cell is air — and the drop is refused on the ordinary budget.
        Map<Integer, Block> scene = new HashMap<>();
        scene.put(10, Blocks.STONE);                       // the real floor, 30 below
        for (int y = 36; y <= 38; y++) scene.put(y, Blocks.WATER); // suspended water, air below it
        assertTrue(Float.isNaN(fallCost(scene)),
                "water suspended over air must not cushion a landing 30 blocks below it");

        // Non-vacuity: the refusal above must come from the DRY FEET, not from the suspended water blocking
        // the drop column. Same scene with one water block on the floor is permitted and damage-free.
        scene.put(11, Blocks.WATER);
        float cushioned = fallCost(scene);
        assertFalse(Float.isNaN(cushioned), "the suspended water must not block the drop column itself");
        assertEquals(cleanCost(30), cushioned, 1e-3, "water ON the floor cushions the very same drop");
    }

    // ---- The momentum gate -----------------------------------------------------------------------------

    @Test
    void waterDeeperThanAnyEntryMomentumCanCrossDoesNotCushion() {
        // 20 blocks of water — past the terminal-velocity penetration bound (~19), so no fall however long
        // carries the bot to the seabed. Refused rather than planning a node it would take ~1400 ticks of
        // terminal-crawl sinking to reach.
        float cost = fallCost(column(Blocks.STONE, 10, Blocks.WATER, 20));
        assertTrue(Float.isNaN(cost), "a 20-deep water column exceeds the momentum bound and must not cushion");
    }

    @Test
    void aShortDropIntoDeepWaterDoesNotCushion() {
        // The gate is about the WATER column vs the entry speed, not the total depth: this drop is SHALLOWER
        // than the cushioned 30-block case above, but only 8 blocks of free fall precede 12 blocks of water,
        // and that entry speed cannot carry the bot through them.
        float cost = fallCost(column(Blocks.STONE, 20, Blocks.WATER, 12));
        assertTrue(Float.isNaN(cost), "8 blocks of free fall cannot carry the bot through 12 blocks of water");
    }

    // ---- Only water qualifies --------------------------------------------------------------------------

    @Test
    void cobwebIsNotACushion() {
        // Cobweb resets fallDistance via makeStuckInBlock ← applyEffectsFromBlocks, which LivingEntity.aiStep
        // runs AFTER travel()→checkFallDamage. The reset lands a tick-phase too late to save a same-tick
        // landing, so it must NOT be treated as a cushion despite its fallSoftness = ZERO classification.
        assertTrue(Float.isNaN(fallCost(column(Blocks.STONE, 10, Blocks.COBWEB, 1))),
                "cobweb resets fall distance too late in the tick to cushion a landing");
    }

    // ---- Regression guard ------------------------------------------------------------------------------

    @Test
    void dropsInsideTheSafeWindowAreUntouched() {
        // depth 2 ≤ safeFall 3, so the softness gate never runs and the cushion check is never reached —
        // the cost must be byte-identical to the pre-cushion formula.
        float cost = fallCost(column(Blocks.STONE, 38, Blocks.WATER, 1));
        assertFalse(Float.isNaN(cost), "a 2-block drop must be permitted");
        assertEquals(cleanCost(2), cost, 1e-3, "a drop inside the safe window is unchanged by the cushion");
    }

    // ---- Scene builders --------------------------------------------------------------------------------

    /** A landing block at {@code landingY} with {@code cushionDepth} cells of {@code cushion} stacked on it. */
    private static Map<Integer, Block> column(Block landing, int landingY, Block cushion, int cushionDepth) {
        Map<Integer, Block> scene = new HashMap<>();
        scene.put(landingY, landing);
        for (int k = 1; k <= cushionDepth; k++) scene.put(landingY + k, cushion);
        return scene;
    }

    /** The cost of the +X Fall candidate landing in column (3,*,8), or {@code NaN} if none is emitted. */
    private static float fallCost(Map<Integer, Block> dropColumn) {
        MovementContext ctx = new MovementContext(grid(dropColumn), MORTAL);
        final float[] got = { Float.NaN };
        new Fall().candidates(ctx, SX, START_Y, NZ, new CandidateSink() {
            @Override
            public void accept(int x, int y, int z, float cost, EditScratch edits) {
                if (x == NX && z == NZ) got[0] = cost;
            }
        });
        return got[0];
    }

    /**
     * A tall built column (4 sections, y 0..63) of mostly air: a stone start floor at (2,40,8), plus the
     * caller's blocks placed in the +X drop column at (3,*,8). Built via {@code classifyInto}, so the depth
     * nibble stays UNKNOWN and the legacy/extended scan path is the one under test.
     */
    private static NavGridView grid(Map<Integer, Block> dropColumn) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        NavSection[] col = new NavSection[4];
        for (int i = 0; i < 4; i++) {
            PalettedContainer<BlockState> s = new PalettedContainer<>(
                    air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
            if ((START_Y >> 4) == i) s.set(SX, START_Y & 15, NZ, stone);
            for (Map.Entry<Integer, Block> e : dropColumn.entrySet()) {
                int y = e.getKey();
                if (e.getValue() != Blocks.AIR && (y >> 4) == i) {
                    s.set(NX, y & 15, NZ, e.getValue().defaultBlockState());
                }
            }
            col[i] = NavSection.create(BlockPos.ZERO);
            NavSectionBuilder.classifyInto(s, false, col[i].getTraversalGrid());
        }
        ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        chunks.put(NavStore.key(0, 0), col);
        return new NavGridView(0, chunks);
    }
}
