package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Unit tests for the fluid-folded break's TWO-PHASE expectation machinery — the per-cell
 * {@code {air, fluid}} expectation set of DESIGN-fluid-flow-prediction.md §8.3/§11.4 that stops a
 * plan's own predicted flood from reading as a foreign world change. The slot machinery around it
 * ({@code expectFloodedBreak}/{@code consumeExpected}/{@code onBlockChanged}) is welded to a live
 * {@code ServerLevel} — it cannot be stood up under the Knot test classloader, the same split
 * {@link NavGridEpochTest} documents for {@code changesGrid} — so this drives the extracted,
 * level-free match seams against a plain residual map and real interned {@code BlockState}s:
 * {@link NavGridUpdater#observeFloodedBreak} (the fluid-armed slot's match-and-deposit rule),
 * {@link NavGridUpdater#observePendingFlood} (the residual's state-based release rule), and
 * {@link NavGridUpdater#isPredictedFluid} (the arrival match both rules share).
 *
 * <p><b>The two-phase story under test</b> (§1.4/§8.3, as corrected against shipped code): the
 * break's own AIR phase was ALREADY forgiven pre-arc — the executor arms the direction-matched
 * one-shot for every break, fluid-folded or not. What this arc adds is the second half: the fluid's
 * lagged arrival (≥5 ticks for water, ≥30 for overworld lava, §1.4) was previously a
 * guaranteed-foreign "cascading change" costing one invalidation + re-search per flooded break the
 * plan itself prescribed; a fluid-kind arm now deposits a pending-flood residual on its AIR phase,
 * and the arrival consumes it as NOT-foreign. Everything else stays foreign, and every entry is
 * released by STATE only — consumed by the matching arrival, superseded by any other change at its
 * cell — never by a tick countdown ({@code no-arbitrary-timers}).
 *
 * <p><b>What is deliberately NOT re-tested here.</b> The PLAIN arm's direction rule
 * ({@code expectAir == newState.isAir()}) is unextracted straight-line code inside
 * {@code consumeExpected}, unchanged by this arc and unreachable without a {@code ServerLevel}. Its
 * two load-bearing guarantees ARE pinned at this seam: (a) a plain arm never routes through
 * {@link NavGridUpdater#observeFloodedBreak}, so it deposits NO residual and an unpredicted flood at
 * a dry-broken cell still classifies foreign (the surviving cascade rule —
 * {@link #dryBreaksDepositNothingSoAnUnpredictedFloodStaysForeign}); and (b) the widened arm keeps
 * the mismatch-is-foreign shape — a fluid arm observing neither air nor its predicted fluid is
 * foreign, exactly as a plain arm's direction mismatch is
 * ({@link #aForeignStateOnAFluidArmStaysForeignAndScrubsTheResidual}).
 */
class FluidExpectationTest {

    private static boolean bootstrapped;

    @BeforeAll
    static void boot() {
        if (!bootstrapped) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            bootstrapped = true;
        }
    }

    /** The broken cell all single-cell scenarios use (packed {@code BlockPos.asLong}). */
    private static final long CELL = BlockPos.asLong(100, 64, -20);

    // States are built inside methods, never in static initializers — the class loads before boot().
    private static BlockState air() { return Blocks.AIR.defaultBlockState(); }

    private static BlockState waterSource() { return Blocks.WATER.defaultBlockState(); }

    /** What lateral spread actually delivers (§1.2): the FLOWING state, a distinct interned state. */
    private static BlockState waterFlowing() {
        return Blocks.WATER.defaultBlockState().setValue(BlockStateProperties.LEVEL, 2);
    }

    private static BlockState lavaSource() { return Blocks.LAVA.defaultBlockState(); }

    private static BlockState gravel() { return Blocks.GRAVEL.defaultBlockState(); }

    // ---- (1)/(2) the two-phase edit: AIR deposits the residual, the arrival consumes it -------------

    @Test
    void waterArmAirPhaseDepositsAResidualTheArrivalThenConsumes() {
        HashMap<Long, Byte> floods = new HashMap<>();

        // Phase 1 — the mined break lands as AIR: forgiven, and the predicted flood is deposited.
        assertTrue(NavGridUpdater.observeFloodedBreak(NavGridUpdater.FLOOD_WATER, air(), floods, CELL),
                "the AIR phase of a fluid-folded break must be forgiven");
        assertEquals(Byte.valueOf(NavGridUpdater.FLOOD_WATER), floods.get(CELL),
                "the AIR phase must deposit the pending-flood residual");

        // Phase 2 — the lagged arrival (≥5 ticks later, §1.4): consumes the residual, NOT foreign.
        assertTrue(NavGridUpdater.observePendingFlood(floods, CELL, waterSource()),
                "the predicted water arriving must consume the residual as not-foreign");
        assertTrue(floods.isEmpty(), "the arrival must release the entry (state-based, one-shot)");

        // The residual is identity, not a count: a SECOND water change at the cell is foreign again.
        assertFalse(NavGridUpdater.observePendingFlood(floods, CELL, waterSource()),
                "a spent residual must not forgive a later change at the same cell");
    }

    @Test
    void theArrivalMatchesTheFlowingStateVanillaActuallyDelivers() {
        // Lateral spread carries getFlowing(...), not the source (§1.2) — the arrival match must
        // accept the FLOWING state or every real lateral flood would still read foreign.
        assertNotEquals(waterSource(), waterFlowing(),
                "premise: source and flowing water are distinct interned states");
        HashMap<Long, Byte> floods = new HashMap<>();
        assertTrue(NavGridUpdater.observeFloodedBreak(NavGridUpdater.FLOOD_WATER, air(), floods, CELL));
        assertTrue(NavGridUpdater.observePendingFlood(floods, CELL, waterFlowing()),
                "flowing water IS the predicted flood (open-fluid match, not state identity)");
        assertTrue(floods.isEmpty());
    }

    @Test
    void lavaArmAirPhaseDepositsAResidualTheArrivalThenConsumes() {
        // The lava analog (§4.2/§6): same rule, its own kind — a BROKEN_LAVA fold predicts LAVA,
        // and lava's 30-tick overworld lag is exactly why the residual outlives the one-shot slot.
        HashMap<Long, Byte> floods = new HashMap<>();

        assertTrue(NavGridUpdater.observeFloodedBreak(NavGridUpdater.FLOOD_LAVA, air(), floods, CELL),
                "the AIR phase of a lava-folded break must be forgiven");
        assertEquals(Byte.valueOf(NavGridUpdater.FLOOD_LAVA), floods.get(CELL),
                "the AIR phase must deposit the LAVA residual");

        assertTrue(NavGridUpdater.observePendingFlood(floods, CELL, lavaSource()),
                "the predicted lava arriving must consume the residual as not-foreign");
        assertTrue(floods.isEmpty());
    }

    // ---- (3) supersede-by-state: any OTHER change at the pending cell is foreign and releases -------

    @Test
    void aDifferentBlockAtThePendingCellIsForeignAndClearsTheEntry() {
        HashMap<Long, Byte> floods = new HashMap<>();
        assertTrue(NavGridUpdater.observeFloodedBreak(NavGridUpdater.FLOOD_WATER, air(), floods, CELL));

        // Gravel lands in the opened cell before the water does: the world genuinely diverged from
        // what the plan folded — foreign, and the stale prediction no longer describes the cell.
        assertFalse(NavGridUpdater.observePendingFlood(floods, CELL, gravel()),
                "a non-fluid block at the pending cell must classify foreign");
        assertTrue(floods.isEmpty(), "the mismatching change must supersede (remove) the entry");

        // The prediction is gone with the entry: water arriving AFTER the supersede is plain foreign
        // (correct — it floods a gravel-history cell the plan never modelled in that shape).
        assertFalse(NavGridUpdater.observePendingFlood(floods, CELL, waterSource()));
    }

    @Test
    void theWrongFluidAtThePendingCellIsForeignToo() {
        // The residual predicts ONE fluid kind, not "any fluid": lava arriving where the plan folded
        // BROKEN_WATER is the world diverging (different damage, different affordances — §6).
        HashMap<Long, Byte> floods = new HashMap<>();
        assertTrue(NavGridUpdater.observeFloodedBreak(NavGridUpdater.FLOOD_WATER, air(), floods, CELL));
        assertFalse(NavGridUpdater.observePendingFlood(floods, CELL, lavaSource()),
                "lava at a water-pending cell must classify foreign");
        assertTrue(floods.isEmpty(), "…and supersede the entry");
    }

    // ---- (4) the plain arm: dry breaks deposit nothing, so the cascade rule survives ----------------

    @Test
    void dryBreaksDepositNothingSoAnUnpredictedFloodStaysForeign() {
        // A plain BROKEN fold arms the classic single-phase expectation (FLOOD_NONE), which never
        // routes through observeFloodedBreak — so the residual table stays empty and the flood that
        // the plan did NOT predict reads exactly as before this arc: a foreign cascading change that
        // invalidates and re-plans. This is the load-bearing negative — the §8.3 carve-out forgives
        // ONLY plan-predicted floods, not flooding in general.
        HashMap<Long, Byte> floods = new HashMap<>();
        assertFalse(NavGridUpdater.observePendingFlood(floods, CELL, waterSource()),
                "water arriving at a dry-broken cell must classify foreign (no residual was deposited)");
        assertFalse(NavGridUpdater.observePendingFlood(floods, CELL, lavaSource()),
                "…and likewise lava");
    }

    // ---- (5) the waterlogged break: immediate fluid, forgiven outright, no residual -----------------

    @Test
    void immediateFluidOnAFluidArmIsForgivenWithNoResidual() {
        // Breaking a WATERLOGGED block never has an air phase: vanilla's destroyBlock leaves the
        // fluid's legacy block in place of air, which the old direction-only match (expectAir vs
        // isAir) read as a false foreign change from the bot's OWN prescribed break. The widened arm
        // consumes it outright — and deposits nothing, because the predicted end state already holds.
        HashMap<Long, Byte> floods = new HashMap<>();
        assertTrue(NavGridUpdater.observeFloodedBreak(NavGridUpdater.FLOOD_WATER, waterSource(), floods, CELL),
                "immediate water on a water arm (the waterlogged break) must be forgiven");
        assertTrue(floods.isEmpty(), "no residual — there is no second phase left to expect");

        // Symmetric for lava (no vanilla lavalogging, but the seam must not special-case water).
        assertTrue(NavGridUpdater.observeFloodedBreak(NavGridUpdater.FLOOD_LAVA, lavaSource(), floods, CELL));
        assertTrue(floods.isEmpty());
    }

    // ---- (6) mismatch on the arm itself stays foreign (the pre-existing direction rule, widened) ----

    @Test
    void aForeignStateOnAFluidArmStaysForeignAndScrubsTheResidual() {
        // The widened arm expands the expectation to a SET, {air, fluid} — it must not weaken the
        // pre-existing rule that a pos-matched observation of the WRONG state is foreign (the vine
        // growing / gravel falling into the very cell we were about to mine).
        HashMap<Long, Byte> floods = new HashMap<>();
        assertFalse(NavGridUpdater.observeFloodedBreak(NavGridUpdater.FLOOD_WATER, gravel(), floods, CELL),
                "a solid observed on a water arm must classify foreign");
        assertTrue(floods.isEmpty(), "a foreign observation must not deposit a residual");

        assertFalse(NavGridUpdater.observeFloodedBreak(NavGridUpdater.FLOOD_WATER, lavaSource(), floods, CELL),
                "the WRONG fluid observed on a water arm must classify foreign");

        // A stale residual at the cell (an earlier fluid-folded break there) is superseded by the
        // foreign observation — the cell's state moved past the old prediction.
        floods.put(CELL, NavGridUpdater.FLOOD_WATER);
        assertFalse(NavGridUpdater.observeFloodedBreak(NavGridUpdater.FLOOD_WATER, gravel(), floods, CELL));
        assertTrue(floods.isEmpty(), "the foreign observation must scrub the stale residual");
    }

    // ---- the arrival match itself: OPEN fluid, not the bare fluid field -----------------------------

    @Test
    void theArrivalMatchRequiresTheOpenFluid() {
        // isPredictedFluid uses the descriptor's OPEN-fluid tests (isSwimmableWater/isSwimmableLava:
        // fluid present AND passable), so a waterlogged SOLID appearing at the cell — someone places
        // a waterlogged stair there — is a foreign change, not the predicted flood.
        assertTrue(NavGridUpdater.isPredictedFluid(NavGridUpdater.FLOOD_WATER, waterSource()));
        assertTrue(NavGridUpdater.isPredictedFluid(NavGridUpdater.FLOOD_WATER, waterFlowing()));
        assertTrue(NavGridUpdater.isPredictedFluid(NavGridUpdater.FLOOD_LAVA, lavaSource()));

        assertFalse(NavGridUpdater.isPredictedFluid(NavGridUpdater.FLOOD_WATER, air()));
        assertFalse(NavGridUpdater.isPredictedFluid(NavGridUpdater.FLOOD_WATER, lavaSource()));
        assertFalse(NavGridUpdater.isPredictedFluid(NavGridUpdater.FLOOD_LAVA, waterSource()));
        assertFalse(NavGridUpdater.isPredictedFluid(NavGridUpdater.FLOOD_WATER,
                        Blocks.OAK_STAIRS.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true)),
                "a waterlogged SOLID is not the predicted open-water flood");
    }

    // ---- the documented safety backstop: the cap refuses deposits, never forgives -------------------

    @Test
    void theCapRefusesNewDepositsAndErrsTowardInvalidation() {
        // PENDING_FLOOD_CAP is a hard backstop on the one leak the state-based release cannot close
        // (a predicted fluid that never arrives at a cell nothing else ever touches). At the cap a
        // NEW cell's deposit is refused — its later arrival then reads foreign, which is exactly the
        // pre-arc behavior (one wasted-but-correct re-search) — but the break's own AIR phase is
        // still forgiven (it IS the plan executing), and an EXISTING cell still re-arms in place.
        HashMap<Long, Byte> floods = new HashMap<>();
        for (int i = 0; i < NavGridUpdater.PENDING_FLOOD_CAP; i++) {
            floods.put(BlockPos.asLong(i, 0, 0), NavGridUpdater.FLOOD_WATER);
        }

        assertTrue(NavGridUpdater.observeFloodedBreak(NavGridUpdater.FLOOD_WATER, air(), floods, CELL),
                "at the cap the AIR phase itself must still be forgiven");
        assertEquals(NavGridUpdater.PENDING_FLOOD_CAP, floods.size(), "…but the deposit must be refused");
        assertFalse(NavGridUpdater.observePendingFlood(floods, CELL, waterSource()),
                "the un-deposited arrival must err toward invalidation, never toward forgiving");

        final long existing = BlockPos.asLong(0, 0, 0);
        assertTrue(NavGridUpdater.observeFloodedBreak(NavGridUpdater.FLOOD_LAVA, air(), floods, existing),
                "an EXISTING cell's re-arm overwrites in place (no growth, no refusal)");
        assertEquals(Byte.valueOf(NavGridUpdater.FLOOD_LAVA), floods.get(existing));
        assertEquals(NavGridUpdater.PENDING_FLOOD_CAP, floods.size());
    }
}
