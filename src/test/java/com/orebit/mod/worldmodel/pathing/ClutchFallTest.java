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
import com.orebit.mod.pathfinding.blockpathfinder.ClutchModel;
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
 * The planner half of the CLUTCH arc: a bot carrying water / powder snow / slime / hay may take a drop the
 * resident terrain would refuse, because it supplies the soft landing itself on the way down.
 *
 * <p>The property under test that is easiest to get wrong is the <b>landing geometry split</b>
 * ({@link ClutchModel#landsOnTop}). The clutch block always goes in the landing's feet cell, but where the bot
 * ENDS UP differs by kind, and the first cut of this branch got it wrong in both directions at once — it
 * emitted the pre-existing floor for every kind and folded a solid place into that node's own body cell,
 * making every clutch landing a self-blocking dead end.
 *
 * <ul>
 *   <li><b>Sink-through</b> (water, powder snow) — the bot rests on the PRE-EXISTING floor with the clutch in
 *       its feet cell, so the node and the drop are unchanged.</li>
 *   <li><b>Lands-on-top</b> (slime, hay) — the carried block BECOMES the floor, so the node is one cell higher
 *       and the drop is one block shorter.</li>
 * </ul>
 *
 * <p>Geometry mirrors {@link WaterCushionFallTest}: a mortal bot walks off a stone floor at {@code (2,40,8)}
 * into the {@code x=3} drop column. Lives in this package to reach {@link NavGridView}'s synthetic ctor.
 */
class ClutchFallTest {

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

    private static final int SX = 2, START_Y = 40, NX = 3, NZ = 8, FLOOR = 10;
    private static final int DEPTH = START_Y - FLOOR;               // 30 — well past maxFall 16
    // BREAK_PLACE, not DEFAULT: a clutch IS a placement, so it inherits caps.canPlace(), and BotCaps.DEFAULT
    // has canPlace=false. Identical safeFall/maxFall/costPerHitpoint, so the fall arithmetic is unchanged.
    private static final BotCaps MORTAL = BotCaps.BREAK_PLACE;

    // ---- The gate itself -------------------------------------------------------------------------------

    @Test
    void withoutAClutchTheDropStaysRefused() {
        // The control, and the regression guard for every bot that carries nothing: a 30-block drop onto bare
        // stone is past the mortal budget and must be refused exactly as it was before this arc.
        assertTrue(Float.isNaN(cost(0)), "a bare 30-block drop must still be refused when the mask is empty");
    }

    @Test
    void aCarriedClutchMakesTheSameDropSurvivable() {
        assertFalse(Float.isNaN(cost(ClutchModel.bit(ClutchModel.WATER))),
                "a carried water bucket must make the refused drop acceptable");
    }

    @Test
    void aBotThatMayNotPlaceCannotClutch() {
        // The clutch is a placement, so it inherits the placement capability gate — a bot forbidden from
        // editing the world must not be handed a drop it can only survive by editing the world.
        BotCaps noPlace = BotCaps.DEFAULT; // same fall window as BREAK_PLACE, but canPlace=false
        assertTrue(Float.isNaN(cost(ClutchModel.bit(ClutchModel.WATER), noPlace)),
                "canPlace=false must refuse the clutch even with a full bag");
    }

    // ---- The geometry split ----------------------------------------------------------------------------

    @Test
    void aSinkThroughClutchLandsOnThePreExistingFloor() {
        // Water is placed in the feet cell and the bot comes to rest on the stone that was already there, so
        // the node is the ordinary landing and the drop is its full 30 blocks.
        assertEquals(FLOOR, landingY(ClutchModel.bit(ClutchModel.WATER)),
                "a water clutch must land on the pre-existing floor, not a cell above it");
    }

    @Test
    void powderSnowIsSinkThroughDespiteBeingSolidDuringTheFall() {
        // The non-obvious one. PowderSnowBlock.getCollisionShape returns a solid 0.9-tall box only while
        // fallDistance > 2.5, and the landing is what resets fallDistance — so the shape empties on the very
        // next tick and the bot sinks to the floor beneath. Modelling it as lands-on-top would put the node a
        // block above where the bot actually ends up.
        assertEquals(FLOOR, landingY(ClutchModel.bit(ClutchModel.POWDER_SNOW)),
                "powder snow must be modelled as sink-through");
    }

    @Test
    void aLandsOnTopClutchBecomesTheFloorOneCellHigher() {
        // Hay is a real block the bot stands ON: the node moves up one and the drop is one block shorter.
        assertEquals(FLOOR + 1, landingY(ClutchModel.bit(ClutchModel.HAY)),
                "a hay clutch becomes the floor, so the node sits one cell above the terrain floor");
    }

    @Test
    void bouncingKindsAreNotOfferedYet() {
        // Slime and bed are excluded from the preference order, so best() can never return them however full
        // the bag is. Slime's exclusion is a FOLLOWER limit, not a physics one — it is the strongest absorber
        // in the table, but it BOUNCES, and Movement.reached advances the waypoint cursor on the first touch,
        // which would drop the reclaim and start the next move on a bot about to be launched skyward. Pinning
        // it here means re-enabling it has to come with a deliberate test change rather than silently.
        assertTrue(Float.isNaN(cost(ClutchModel.bit(ClutchModel.SLIME))),
                "slime must not be offered while the reclaim cannot survive its bounce");
        assertTrue(Float.isNaN(cost(ClutchModel.bit(ClutchModel.BED))),
                "bed must not be offered while the executor cannot place a two-cell multiblock");
    }

    @Test
    void reclaimIsTheExactComplementOfLandsOnTop() {
        // The invariant behind the 2026-08-08 hay stall. A lands-on-top clutch is folded as the node's FLOOR
        // and the node sits ON it, so every later step of the plan was searched standing on that block:
        // reclaiming it deletes the floor the bot is on, drops it a block, and the rest of the path is framed
        // off a cell it is not in. A sink-through clutch is the mirror image — it folds no geometry, so the
        // reclaim is what makes the plan true again. Any kind where these two disagree is a stall waiting to
        // happen, so pin the relationship rather than the individual values.
        for (int kind = 0; kind < ClutchModel.COUNT; kind++) {
            assertEquals(!ClutchModel.landsOnTop(kind), ClutchModel.reclaimable(kind),
                    "kind " + kind + ": reclaimable must be the complement of landsOnTop");
        }
    }

    // ---- Pricing ---------------------------------------------------------------------------------------

    @Test
    void aDamageFreeClutchIsCheaperThanADamagingOne() {
        // Water leaves zero residual damage from any height; hay leaves (depth-safeFall) * 0.2 HP. At 30
        // blocks that is ~5.2 HP of real cost, so the search must prefer the bucket on price even though
        // ClutchModel.best would spend the more expendable hay first when only feasibility is asked.
        float water = cost(ClutchModel.bit(ClutchModel.WATER));
        float hay = cost(ClutchModel.bit(ClutchModel.HAY));
        assertFalse(Float.isNaN(water) || Float.isNaN(hay), "both clutches must be offered");
        assertTrue(water < hay,
                "a damage-free clutch must price below a damaging one (water " + water + " vs hay " + hay + ")");
    }

    @Test
    void anAlreadySurvivableDropIsNotRepriced() {
        // The clutch is FEASIBILITY ONLY: it is consulted solely on a landing the softness gate has already
        // refused. A drop the old model accepted must cost the same float with an empty bag or a full one,
        // or every ordinary fall in the world silently changes price the moment the owner hands over a bucket.
        Map<Integer, Block> shallow = new HashMap<>();
        shallow.put(START_Y - 6, Blocks.STONE);   // a 6-block drop: past safeFall 3, inside maxFall 16
        float bare = cost(0, MORTAL, shallow);
        float carrying = cost(ClutchModel.bit(ClutchModel.WATER), MORTAL, shallow);
        assertFalse(Float.isNaN(bare), "a 6-block drop is accepted with no clutch");
        assertEquals(bare, carrying, 0f, "carrying a clutch must not reprice an already-survivable drop");
    }

    // ---- Harness ---------------------------------------------------------------------------------------

    private static float cost(int mask) { return cost(mask, MORTAL); }

    private static float cost(int mask, BotCaps caps) { return cost(mask, caps, deepDrop()); }

    private static float cost(int mask, BotCaps caps, Map<Integer, Block> scene) {
        return probe(mask, caps, scene)[1];
    }

    private static int landingY(int mask) {
        float y = probe(mask, MORTAL, deepDrop())[0];
        return Float.isNaN(y) ? Integer.MIN_VALUE : (int) y;
    }

    /** {@code {landingY, cost}} of the +X Fall candidate, both NaN when nothing is emitted. */
    private static float[] probe(int mask, BotCaps caps, Map<Integer, Block> scene) {
        MovementContext ctx = new MovementContext(grid(scene), caps);
        // consumesBlocks = false so the scalar throwaway budget never gates the placement; mining = null is
        // safe here because Fall folds no break and the placement gate never consults the mining snapshot.
        ctx.setInventory(new MovementContext.InventoryView(null, false, 0, 0f, 0f, 0f, mask));
        final float[] got = { Float.NaN, Float.NaN };
        new Fall().candidates(ctx, SX, START_Y, NZ, new CandidateSink() {
            @Override
            public void accept(int x, int y, int z, float c, EditScratch edits) {
                if (x == NX && z == NZ) { got[0] = y; got[1] = c; }
            }
        });
        return got;
    }

    /** Bare stone at {@code y=10} under a 30-block drop — refused without a clutch. */
    private static Map<Integer, Block> deepDrop() {
        Map<Integer, Block> scene = new HashMap<>();
        scene.put(FLOOR, Blocks.STONE);
        return scene;
    }

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
