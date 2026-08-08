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

    // ---- Momentum decides WHERE the fall ends, not whether it is allowed --------------------------------

    @Test
    void waterTooDeepToCrossEndsTheFallFloating() {
        // 20 blocks of water on the floor at y=10, entered after a 10-block free fall. Momentum cannot carry
        // the bot to the seabed, so the fall ENDS IN THE COLUMN rather than being refused (the pre-2026-08-07
        // behaviour, which is what made a deep pool unpathable). At a 10-block drop the entry speed is
        // ~1.13 b/t ⇒ 5 blocks of penetration, so the feet come to rest 5 cells below the surface at y=26
        // and the node keys on the cell beneath them.
        int surface = 10 + 20;                       // topmost water cell
        int landing = surface - 5 - 1;               // node cell = one below the resting feet
        assertEquals(landing, fallLandingY(column(Blocks.STONE, 10, Blocks.WATER, 20)),
                "a fall that runs out of momentum must land floating, not be refused");
        assertEquals(cleanCost(START_Y - landing), fallCost(column(Blocks.STONE, 10, Blocks.WATER, 20)), 1e-3,
                "a wet endpoint is damage-free and priced as the SHORTER fall that actually happens");
    }

    @Test
    void aShortDropIntoDeepWaterStopsNearTheSurface() {
        // The stop depth tracks entry speed, not total depth: only 8 blocks of free fall precede 12 blocks of
        // water, and at ~1.03 b/t that buys 4 blocks of penetration — so the bot rests near the top of the
        // column, far above the seabed at y=20.
        int landing = (20 + 12) - 4 - 1;
        assertEquals(landing, fallLandingY(column(Blocks.STONE, 20, Blocks.WATER, 12)),
                "8 blocks of free fall carries the bot 4 blocks into the water, not through 12");
    }

    @Test
    void theSeabedAndFloatingBranchesAgreeAtTheBoundary() {
        // w == p + 1 is the changeover, where the floating cell computes to fy EXACTLY — so the two branches
        // emit the same node at the same price and there is no discontinuity to tune. Here a 30-block drop
        // with 7 blocks of water gives airDrop 23 ⇒ p = 7, so w = 7 takes the seabed branch and w = 8 takes
        // the floating one; both must resolve to y=10.
        assertEquals(10, fallLandingY(column(Blocks.STONE, 10, Blocks.WATER, 7)),
                "the last fully-crossed column lands on the seabed (w <= p)");
        assertEquals(10, fallLandingY(column(Blocks.STONE, 10, Blocks.WATER, 8)),
                "one block past the window resolves, via the floating branch, to the same seabed cell");
        assertEquals(fallCost(column(Blocks.STONE, 10, Blocks.WATER, 7)),
                fallCost(column(Blocks.STONE, 10, Blocks.WATER, 8)), 1e-3,
                "and the two branches price that shared node identically");
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
        return fall(dropColumn)[1];
    }

    /** The Y of the +X Fall candidate's landing CELL, or {@link Integer#MIN_VALUE} if none is emitted. */
    private static int fallLandingY(Map<Integer, Block> dropColumn) {
        float y = fall(dropColumn)[0];
        return Float.isNaN(y) ? Integer.MIN_VALUE : (int) y;
    }

    /** {@code {landingY, cost}} of the +X Fall candidate in column (3,*,8); both NaN when nothing is emitted. */
    private static float[] fall(Map<Integer, Block> dropColumn) {
        MovementContext ctx = new MovementContext(grid(dropColumn), MORTAL);
        final float[] got = { Float.NaN, Float.NaN };
        new Fall().candidates(ctx, SX, START_Y, NZ, new CandidateSink() {
            @Override
            public void accept(int x, int y, int z, float cost, EditScratch edits) {
                if (x == NX && z == NZ) { got[0] = y; got[1] = cost; }
            }
        });
        return got;
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
