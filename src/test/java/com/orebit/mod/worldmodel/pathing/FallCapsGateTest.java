package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
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
import com.orebit.mod.pathfinding.blockpathfinder.movements.Traverse;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

/**
 * Headless proof of the {@link BotCaps#mayFall} capability gate — the {@code /bot roam} "won't walk off a
 * cliff" guarantee ({@code com.orebit.mod.BotRoamer}).
 *
 * <p>The claim under test is <b>total</b>, not "usually": with {@code mayFall} off, {@link Fall} emits no
 * candidate for ANY drop, at any depth, through any of its acceptance branches — the ordinary hard landing,
 * the free landing inside {@code safeFallDistance}, and the uncapped soft landing (slime) that is otherwise
 * permitted from 30 blocks up. It is asserted branch-by-branch precisely because the fall window
 * ({@code safeFall}/{@code maxFall}) canNOT express this: those branches read through it, which is why the
 * restriction is its own caps axis rather than a squeezed window.
 *
 * <p>The counterpart matters just as much — a no-fall bot is a ROUTING restriction, not a grounded one. The
 * same scene shows {@link Traverse} still emitting normally with the gate off, so the bot that refuses the
 * ledge is otherwise the same bot.
 *
 * <p>Scene + section-building borrowed from {@link FallSoftLandingTest}; lives in this package to reach
 * {@link NavGridView}'s package-private synthetic constructor.
 */
class FallCapsGateTest {

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

    /** The mortal reference bot (safeFall 3, maxFall 16, 100 ticks/HP) — Fall available, as today. */
    private static final BotCaps FALLER = BotCaps.DEFAULT;
    /** The same bot with the roam restriction applied — the ONLY difference between the two columns below. */
    private static final BotCaps NO_FALLER = BotCaps.DEFAULT.withMayFall(false);

    // Step-off geometry (identical to FallSoftLandingTest): start floor at (2,40,8), drop column at (3,*,8).
    private static final int SX = 2, START_Y = 40, NX = 3, NZ = 8;

    // ---- the caps record itself -----------------------------------------------------------------------

    @Test
    void mayFallDefaultsOnForEveryLegacyConstructor() {
        // Every pre-mayFall call site (presets, benchmarks, tests, Config.toBotCaps) must be unchanged.
        assertTrue(BotCaps.DEFAULT.mayFall(), "the Tier 1 preset falls");
        assertTrue(BotCaps.BREAK_PLACE.mayFall(), "the break/place preset falls");
        assertTrue(new BotCaps(1, 3, 16, true, 100f, false, false, 255, false, 10000, 2f).mayFall(),
                "the 11-arg back-compat shape defaults mayFall on");
        assertTrue(new BotCaps(1, 3, 16, true, 100f, false, false, 255, false, 10000, 2f, true).mayFall(),
                "the 12-arg back-compat shape defaults mayFall on");
        assertTrue(new BotCaps(1, 3, 16, true, 100f, false, false, 255, false, 10000, 2f, true, 3).mayFall(),
                "the 13-arg (pre-mayFall canonical) shape defaults mayFall on");
    }

    @Test
    void withMayFallIsIdentityWhenUnchanged() {
        // The caps() cache in AllyBotEntity leans on this: a no-op derivation must not allocate, and the
        // roam derivation must change nothing else about the bot.
        assertSame(FALLER, FALLER.withMayFall(true), "no-op derivation returns this");
        assertSame(NO_FALLER, NO_FALLER.withMayFall(false), "no-op derivation returns this");
        assertNotSame(FALLER, NO_FALLER);
        assertEquals(FALLER, NO_FALLER.withMayFall(true), "the restriction is fully reversible");
        assertEquals(FALLER.safeFallDistance(), NO_FALLER.safeFallDistance(), "fall WINDOW untouched");
        assertEquals(FALLER.maxFallDistance(), NO_FALLER.maxFallDistance(), "fall WINDOW untouched");
        assertEquals(FALLER.canBreak(), NO_FALLER.canBreak(), "only the fall axis changes");
        assertEquals(FALLER.canPlace(), NO_FALLER.canPlace(), "only the fall axis changes");
    }

    // ---- the movement gate ----------------------------------------------------------------------------

    @Test
    void ordinaryDropOfferedWhenFallingAllowed() {
        // Control: the three depths below are all genuinely available to a falling bot, so the refusals in
        // the next test are the CAPS gate and not the scene failing to offer a candidate in the first place.
        assertFalse(Float.isNaN(fallCost(FALLER, Blocks.STONE, START_Y - 2)), "free 2-block drop offered");
        assertFalse(Float.isNaN(fallCost(FALLER, Blocks.STONE, START_Y - 5)), "hurtful 5-block drop offered");
        assertFalse(Float.isNaN(fallCost(FALLER, Blocks.SLIME_BLOCK, START_Y - 30)), "soft 30-block offered");
    }

    @Test
    void noDropOfferedAtAnyDepthWhenFallingRefused() {
        // The free branch (inside safeFallDistance — no damage term to price, so no window could ever
        // exclude it), the damage-priced branch, and the uncapped soft-landing branch, all silent.
        assertTrue(Float.isNaN(fallCost(NO_FALLER, Blocks.STONE, START_Y - 2)),
                "a FREE 2-block drop must still be refused — this is not a damage budget");
        assertTrue(Float.isNaN(fallCost(NO_FALLER, Blocks.STONE, START_Y - 3)),
                "a drop exactly at safeFallDistance must be refused");
        assertTrue(Float.isNaN(fallCost(NO_FALLER, Blocks.STONE, START_Y - 5)),
                "an ordinary hurtful drop must be refused");
        assertTrue(Float.isNaN(fallCost(NO_FALLER, Blocks.SLIME_BLOCK, START_Y - 30)),
                "the uncapped soft-landing branch must be refused too — a slime pad is still a cliff");
    }

    @Test
    void groundMovementStillWorksWhenFallingRefused() {
        // A no-fall bot is a ROUTING restriction, not a grounded one: give it a floor to walk onto and it
        // walks. (If this ever fails, the gate has leaked out of Fall into the shared standing prologue.)
        NavGridView g = flatPair();
        assertFalse(Float.isNaN(traverseCost(g, FALLER)), "control: a faller walks the flat step");
        assertFalse(Float.isNaN(traverseCost(g, NO_FALLER)), "a no-fall bot still walks the flat step");
        assertEquals(traverseCost(g, FALLER), traverseCost(g, NO_FALLER), 1e-6,
                "and pays exactly the same price for it");
    }

    // ---- the realizability signature ------------------------------------------------------------------

    @Test
    void fallingBotDominatesNoFallBotButNotViceVersa() {
        // A crossing a ROAMING bot proved dead must not bind an ordinary bot — the ordinary bot may well
        // have a route the roamer refused (that is the entire point of the restriction). Dominance is what
        // enforces that, so mayFall has to be a signature axis (BotCaps bit 62).
        long faller = FALLER.realizabilitySig();
        long noFaller = NO_FALLER.realizabilitySig();
        assertTrue(faller != noFaller, "the restriction must be visible in the sig");
        assertTrue(BotCaps.sigDominates(faller, noFaller), "a falling bot is at least as capable");
        assertFalse(BotCaps.sigDominates(noFaller, faller), "a no-fall bot is NOT as capable");
        assertTrue(BotCaps.sigDominates(noFaller, noFaller), "equal caps dominate each other");
    }

    // ---- scene builders -------------------------------------------------------------------------------

    /** The cost of the +X {@link Fall} candidate landing in column (3,*,8), or {@code NaN} if none emitted. */
    private static float fallCost(BotCaps caps, Block landing, int landingY) {
        MovementContext ctx = new MovementContext(dropColumn(landing.defaultBlockState(), landingY), caps);
        final float[] got = { Float.NaN };
        new Fall().candidates(ctx, SX, START_Y, NZ, sink(got, NX, START_Y_ANY, NZ));
        return got[0];
    }

    /** The cost of the +X {@link Traverse} candidate onto the level neighbour, or {@code NaN} if none. */
    private static float traverseCost(NavGridView g, BotCaps caps) {
        final float[] got = { Float.NaN };
        new Traverse().candidates(new MovementContext(g, caps), SX, START_Y, NZ,
                sink(got, NX, START_Y, NZ));
        return got[0];
    }

    /** Sentinel for "any landing height" — the fall sink matches on the column, not the depth. */
    private static final int START_Y_ANY = Integer.MIN_VALUE;

    /** Records the cost of the first candidate emitted into column {@code (wantX, wantZ)} (and, unless
     *  {@link #START_Y_ANY}, at exactly {@code wantY}). */
    private static CandidateSink sink(float[] got, int wantX, int wantY, int wantZ) {
        return new CandidateSink() {
            @Override
            public void accept(int x, int y, int z, float cost, EditScratch edits) {
                if (x == wantX && z == wantZ && (wantY == START_Y_ANY || y == wantY)) got[0] = cost;
            }
        };
    }

    /**
     * A tall built column (4 sections, y 0..63) of mostly air: a stone start floor at (2,40,8) and the
     * chosen {@code landing} block in the +X drop column at (3,{@code landingY},8) — i.e. a ledge with a
     * drop beside it (FallSoftLandingTest's scene).
     */
    private static NavGridView dropColumn(BlockState landing, int landingY) {
        return sections((sec, i) -> {
            if ((START_Y >> 4) == i) sec.set(SX, START_Y & 15, NZ, Blocks.STONE.defaultBlockState());
            if ((landingY >> 4) == i) sec.set(NX, landingY & 15, NZ, landing);
        });
    }

    /** Two stone floor cells side by side at {@code START_Y} — a plain flat step, no ledge anywhere. */
    private static NavGridView flatPair() {
        return sections((sec, i) -> {
            if ((START_Y >> 4) != i) return;
            sec.set(SX, START_Y & 15, NZ, Blocks.STONE.defaultBlockState());
            sec.set(NX, START_Y & 15, NZ, Blocks.STONE.defaultBlockState());
        });
    }

    /** Build the 4-section synthetic chunk, letting {@code paint} place blocks into each section. */
    private static NavGridView sections(SectionPainter paint) {
        BlockState air = Blocks.AIR.defaultBlockState();
        NavSection[] col = new NavSection[4];
        for (int i = 0; i < 4; i++) {
            PalettedContainer<BlockState> s = new PalettedContainer<>(
                    air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
            paint.paint(s, i);
            col[i] = NavSection.create(BlockPos.ZERO);
            NavSectionBuilder.classifyInto(s, false, col[i].getTraversalGrid());
        }
        ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        chunks.put(NavStore.key(0, 0), col);
        return new NavGridView(0, chunks);
    }

    @FunctionalInterface
    private interface SectionPainter {
        void paint(PalettedContainer<BlockState> section, int sectionIndex);
    }
}
