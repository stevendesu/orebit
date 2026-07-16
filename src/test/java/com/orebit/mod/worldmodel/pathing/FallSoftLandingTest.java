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
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.pathfinding.blockpathfinder.movements.Fall;
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
 * Headless proof of the {@link NavBlock#fallSoftness} landing field and the {@code Fall} movement's
 * softness-scaled height/cost math (safe-surface fall landing, #3).
 *
 * <p><b>Classification</b> — the curated {@link NavBlock#fallSoftness} map rounds each block up to its
 * conservative fall-damage class: slime → {@code ZERO}, hay/honey → {@code FIFTH}, bed → {@code HALF},
 * stone → {@code NONE}; the fall-distance-reset media (water / powder snow / berry / cobweb) → {@code ZERO}.
 *
 * <p><b>Height + cost</b> — a mortal bot ({@link BotCaps#DEFAULT}: safeFall 3, maxFall 16, 100 ticks/HP)
 * is driven off a ledge at {@code y=40} toward a 1-wide drop column ({@code x=3}) that ends in a chosen
 * landing block at a chosen depth. Asserted directly off {@code Fall.candidates}' emitted candidate:
 * <ul>
 *   <li>a 30-block drop onto SLIME (class 11, uncapped) is permitted at near-zero added cost;</li>
 *   <li>the same 30-block drop onto STONE (class 00) past maxFall is rejected;</li>
 *   <li>a 25-block drop onto HAY / BED (proportionally taller, not uncapped) is permitted with a
 *       softness-scaled damage cost, and a drop past their own scaled budget is rejected;</li>
 *   <li>an ordinary within-maxFall fall onto STONE is byte-identical to the pre-softness formula.</li>
 * </ul>
 * Lives in this package to reach {@link NavGridView}'s package-private synthetic constructor.
 */
class FallSoftLandingTest {

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

    // The mortal reference bot: safeFall 3, maxFall 16, takesDamage, 100 ticks/HP (BotCaps.DEFAULT).
    private static final BotCaps MORTAL = BotCaps.DEFAULT;
    private static final int HP = (int) BotCaps.DEFAULT_COST_PER_HITPOINT; // 100
    private static final int SAFE = BotCaps.DEFAULT_SAFE_FALL;              // 3

    // Step-off geometry: start floor at (2,40,8); the drop column is the +X neighbour (3,*,8).
    private static final int SX = 2, START_Y = 40, NX = 3, NZ = 8;

    // ---- Classification -------------------------------------------------------------------------------

    @Test
    void classification() {
        assertEquals(NavBlock.FALLSOFT_ZERO, fs(Blocks.SLIME_BLOCK), "slime → zero (11)");
        assertEquals(NavBlock.FALLSOFT_FIFTH, fs(Blocks.HAY_BLOCK), "hay → fifth (10)");
        assertEquals(NavBlock.FALLSOFT_FIFTH, fs(Blocks.HONEY_BLOCK), "honey → fifth (10)");
        assertEquals(NavBlock.FALLSOFT_HALF, fs(Blocks.WHITE_BED), "bed → half (01)");
        assertEquals(NavBlock.FALLSOFT_NONE, fs(Blocks.STONE), "stone → none (00)");
        // The non-standable fall-distance-reset media are classified ZERO (deferred as landings, but
        // the descriptor must be correct for the future v1.1 Fall→swim landing).
        assertEquals(NavBlock.FALLSOFT_ZERO, fs(Blocks.WATER), "water → zero");
        assertEquals(NavBlock.FALLSOFT_ZERO, fs(Blocks.POWDER_SNOW), "powder snow → zero");
        assertEquals(NavBlock.FALLSOFT_ZERO, fs(Blocks.SWEET_BERRY_BUSH), "berry bush → zero");
        assertEquals(NavBlock.FALLSOFT_ZERO, fs(Blocks.COBWEB), "cobweb → zero");
    }

    private static int fs(Block block) {
        return NavBlock.fallSoftness(NavBlock.descriptorFor(block.defaultBlockState()));
    }

    // ---- Fall height + cost math ----------------------------------------------------------------------

    @Test
    void slimeDeepFallPermittedAtNearZeroCost() {
        // Depth 30 (≫ maxFall 16) onto slime (class 11, m = 0 ⇒ uncapped, no damage cost).
        float cost = fallCostOnto(Blocks.SLIME_BLOCK, START_Y - 30);
        assertFalse(Float.isNaN(cost), "a 30-block drop onto slime must be permitted (uncapped)");
        assertEquals(Fall.BASE_COST + 30 * Fall.PER_BLOCK, cost, 1e-3,
                "a slime landing adds NO fall-damage cost, however deep");
    }

    @Test
    void stoneDeepFallRejected() {
        // The SAME 30-block drop onto stone (class 00, m = 1) exceeds maxFall → no candidate.
        assertTrue(Float.isNaN(fallCostOnto(Blocks.STONE, START_Y - 30)),
                "a 30-block drop onto stone past maxFall must be rejected");
    }

    @Test
    void hayTallerFallPermittedWithScaledCost() {
        // Depth 25 onto hay (class 10, m = 0.2): (25-3)*0.2 = 4.4 ≤ 13 → permitted; cost scaled ×0.2.
        float cost = fallCostOnto(Blocks.HAY_BLOCK, START_Y - 25);
        assertFalse(Float.isNaN(cost), "a 25-block drop onto hay must be permitted");
        assertEquals(Fall.BASE_COST + 25 * Fall.PER_BLOCK + (25 - SAFE) * HP * 0.2f, cost, 1e-2,
                "hay scales the excess-fall damage cost by 0.2");
        // Not uncapped: (70-3)*0.2 = 13.4 > 13 → a 70-block drop onto hay is rejected.
        assertTrue(Float.isNaN(fallCostOnto(Blocks.HAY_BLOCK, START_Y - 70)),
                "hay widens the fall budget but does not remove the cap");
    }

    @Test
    void bedTallerFallPermittedWithScaledCost() {
        // Depth 25 onto bed (class 01, m = 0.5): (25-3)*0.5 = 11 ≤ 13 → permitted; cost scaled ×0.5.
        float cost = fallCostOnto(Blocks.WHITE_BED, START_Y - 25);
        assertFalse(Float.isNaN(cost), "a 25-block drop onto a bed must be permitted");
        assertEquals(Fall.BASE_COST + 25 * Fall.PER_BLOCK + (25 - SAFE) * HP * 0.5f, cost, 1e-2,
                "a bed scales the excess-fall damage cost by 0.5");
        // Not uncapped: (30-3)*0.5 = 13.5 > 13 → a 30-block drop onto a bed is rejected.
        assertTrue(Float.isNaN(fallCostOnto(Blocks.WHITE_BED, START_Y - 30)),
                "a bed widens the fall budget but does not remove the cap");
    }

    @Test
    void normalHardFallUnchanged() {
        // The regression guard: a within-maxFall drop onto stone (m = 1) must price EXACTLY the
        // pre-softness formula — base + per-block + (depth-safe)*costPerHitpoint, no softness term.
        float cost = fallCostOnto(Blocks.STONE, START_Y - 5);
        assertFalse(Float.isNaN(cost), "an ordinary 5-block drop onto stone must be permitted");
        assertEquals(Fall.BASE_COST + 5 * Fall.PER_BLOCK + (5 - SAFE) * HP, cost, 1e-3,
                "an ordinary hard fall must be byte-identical to the pre-softness cost");
    }

    // ---- Scene builder --------------------------------------------------------------------------------

    /** The cost of the +X Fall candidate landing in column (3,*,8), or {@code NaN} if none is emitted. */
    private static float fallCostOnto(Block landing, int landingY) {
        NavGridView g = column(landing.defaultBlockState(), landingY);
        MovementContext ctx = new MovementContext(g, MORTAL);
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
     * A tall built column (4 sections, y 0..63) of mostly air: a stone start floor at (2,40,8) and the
     * chosen {@code landing} block in the +X drop column at (3,{@code landingY},8). Built per-section via
     * {@code classifyInto} (depth nibble UNKNOWN → the legacy/extended scan path, exercising the deep soft
     * scan directly).
     */
    private static NavGridView column(BlockState landing, int landingY) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        NavSection[] col = new NavSection[4];
        for (int i = 0; i < 4; i++) {
            PalettedContainer<BlockState> s = new PalettedContainer<>(
                    air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
            if ((START_Y >> 4) == i) s.set(SX, START_Y & 15, NZ, stone);
            if ((landingY >> 4) == i) s.set(NX, landingY & 15, NZ, landing);
            col[i] = NavSection.create(BlockPos.ZERO);
            NavSectionBuilder.classifyInto(s, false, col[i].getTraversalGrid());
        }
        ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        chunks.put(NavStore.key(0, 0), col);
        return new NavGridView(0, chunks);
    }
}
