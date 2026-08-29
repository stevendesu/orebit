package com.orebit.mod.pathfinding.blockpathfinder.movements;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.CandidateSink;
import com.orebit.mod.pathfinding.blockpathfinder.EditScratch;
import com.orebit.mod.pathfinding.blockpathfinder.Movement;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.pathfinding.blockpathfinder.MovementRegistry;
import com.orebit.mod.worldmodel.pathing.NavGridView;
import com.orebit.mod.worldmodel.pathing.NavSection;
import com.orebit.mod.worldmodel.pathing.NavSectionBuilder;
import com.orebit.mod.worldmodel.pathing.NavStore;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

/**
 * Candidate-level pins for the dry 3-axis pair (DESIGN-diagonal-vertical-moves.md): the O3 ownership
 * window, the D2 three-row corner sweep, the D4 unowned small-rise band (pinned so closing it later is a
 * VISIBLE change), and {@link DiagonalDescend}'s mirror gates. Substrate = hand-placed blocks classified
 * through the real {@link NavSectionBuilder} behind {@link NavGridView#overSections} (the
 * WindowTargetingWaterYTest pattern) — no search, no ServerLevel.
 *
 * <p>Window-boundary pins use the rises real vanilla tops make reachable — full→full (16, admitted),
 * full→slab-up (8, refused low), slab→full-up (24, refused high) — rather than the exact 9/10/20/21
 * constants (no vanilla top hits those from a full or slab start); the comparison operators are the
 * SHARED {@code STEP_ASSIST_MAX_RISE}/{@code JUMP_RISE} constants cardinal {@link Ascend}'s own window
 * already exercises at its boundaries.
 */
class DiagonalVerticalCandidatesTest {

    private static final int MINY = 0;
    private static final int FY = 5; // the start FLOOR cell y

    private static boolean bootstrapped;

    @BeforeAll
    static void boot() {
        if (!bootstrapped) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            bootstrapped = true;
        }
    }

    // ---- substrate ------------------------------------------------------------------------------------

    private static PalettedContainer<BlockState> emptyStates() {
        return new PalettedContainer<>(Blocks.AIR.defaultBlockState(),
                Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
    }

    /** One chunk (0,0): the given (x, y, z, state) placements over air, classified for real. */
    private static NavGridView world(Object[][] placements) {
        PalettedContainer<BlockState> s0 = emptyStates();
        for (Object[] p : placements) {
            s0.set((Integer) p[0], (Integer) p[1], (Integer) p[2], (BlockState) p[3]);
        }
        NavSection sec = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(s0, false, sec.getTraversalGrid());
        NavSection air = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(emptyStates(), true, air.getTraversalGrid());
        ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        chunks.put(NavStore.key(0, 0), new NavSection[]{sec, air, air, air});
        return NavGridView.overSections(MINY, chunks);
    }

    // Lazy — Blocks statics must not run before @BeforeAll's Bootstrap (the TransitCostVocabularyTest
    // convention; an eager field here is an ExceptionInInitializerError at class load).
    private static BlockState stone() { return Blocks.STONE.defaultBlockState(); }
    private static BlockState slab() { return Blocks.SMOOTH_STONE_SLAB.defaultBlockState(); }

    /** Start floor at (8,FY,8) plus the given extras. */
    private static NavGridView withStart(Object[]... extras) {
        List<Object[]> all = new ArrayList<>();
        all.add(new Object[]{8, FY, 8, stone()});
        for (Object[] e : extras) all.add(e);
        return world(all.toArray(new Object[0][]));
    }

    private record Cand(int x, int y, int z, float cost, boolean hasEdits) {}

    private static List<Cand> run(Movement m, NavGridView grid, int x, int y, int z) {
        MovementContext ctx = new MovementContext(grid, BotCaps.DEFAULT);
        List<Cand> out = new ArrayList<>();
        m.candidates(ctx, x, y, z, new CandidateSink() {
            @Override
            public void accept(int cx, int cy, int cz, float cost, EditScratch edits) {
                out.add(new Cand(cx, cy, cz, cost, edits != null));
            }
        });
        return out;
    }

    private static boolean lands(List<Cand> cands, int x, int y, int z) {
        return cands.stream().anyMatch(c -> c.x == x && c.y == y && c.z == z);
    }

    // ---- DiagonalAscend -------------------------------------------------------------------------------

    @Test
    void emitsTheCleanDiagonalJumpUp_editFree() {
        NavGridView g = withStart(new Object[]{9, FY + 1, 9, stone()});
        List<Cand> c = run(MovementRegistry.DIAGONAL_ASCEND, g, 8, FY, 8);
        assertTrue(lands(c, 9, FY + 1, 9), "full→full one up diagonally (rise 16 ∈ (9,20]) is the move");
        Cand hit = c.stream().filter(x -> x.y == FY + 1).findFirst().orElseThrow();
        assertEquals(DiagonalAscend.COST, hit.cost, 1e-4, "clean geometry prices at the bare √2 base");
        assertEquals(false, hit.hasEdits, "O2: no folded edits, ever");
    }

    @Test
    void windowRefusesTheSmallRise_theD4UnownedBand() {
        // full → bottom-slab one up: rise(1, 8, 16) = 8 ≤ STEP_ASSIST_MAX_RISE — DiagonalAscend refuses
        // (O3's lower bound), and NOTHING ELSE owns the dy=+1 diagonal small-rise either (D4): pin the
        // hole across the whole registry so closing it later is a visible change.
        NavGridView g = withStart(new Object[]{9, FY + 1, 9, slab()});
        for (Movement m : MovementRegistry.TIER1) {
            assertTrue(!lands(run(m, g, 8, FY, 8), 9, FY + 1, 9),
                    "D4: the dy=+1 diagonal small-rise band is UNOWNED (offender: " + m.getClass()
                            .getSimpleName() + ")");
        }
    }

    @Test
    void windowRefusesPastOneJumpsGain() {
        // slab start → full one up: rise(1, 16, 8) = 24 > JUMP_RISE — taller than one jump gains.
        NavGridView g = world(new Object[][]{{8, FY, 8, slab()}, {9, FY + 1, 9, stone()}});
        assertTrue(!lands(run(MovementRegistry.DIAGONAL_ASCEND, g, 8, FY, 8), 9, FY + 1, 9),
                "O3's upper bound: a 24/16 surface-to-surface rise is refused");
    }

    @Test
    void windowAdmitsThePartialToPartial() {
        // slab → slab one up: rise(1, 8, 8) = 16 ∈ (9,20] — admitted from a partial start too.
        NavGridView g = world(new Object[][]{{8, FY, 8, slab()}, {9, FY + 1, 9, slab()}});
        assertTrue(lands(run(MovementRegistry.DIAGONAL_ASCEND, g, 8, FY, 8), 9, FY + 1, 9));
    }

    @Test
    void cornerSweepIsThreeRows_theD2Cap() {
        // A solid corner cell at y+3 is inside the jump arc's sweep — refused; at y+4 it is above the
        // D2 cap (Ascend's truncated-apex bar) — admitted. Both corner columns tested symmetrically.
        NavGridView blockedA = withStart(new Object[]{9, FY + 1, 9, stone()}, new Object[]{9, FY + 3, 8, stone()});
        assertTrue(!lands(run(MovementRegistry.DIAGONAL_ASCEND, blockedA, 8, FY, 8), 9, FY + 1, 9),
                "a solid corner cell at y+3 (mid-arc body) refuses the jump");
        NavGridView blockedB = withStart(new Object[]{9, FY + 1, 9, stone()}, new Object[]{8, FY + 3, 9, stone()});
        assertTrue(!lands(run(MovementRegistry.DIAGONAL_ASCEND, blockedB, 8, FY, 8), 9, FY + 1, 9),
                "…on the OTHER corner column too");
        NavGridView above = withStart(new Object[]{9, FY + 1, 9, stone()}, new Object[]{9, FY + 4, 8, stone()});
        assertTrue(lands(run(MovementRegistry.DIAGONAL_ASCEND, above, 8, FY, 8), 9, FY + 1, 9),
                "a ceiling at y+4 is above the D2 cap (the truncated-apex argument) — admitted");
    }

    @Test
    void baseCornerCellRefuses_theDiagonalRule() {
        NavGridView g = withStart(new Object[]{9, FY + 1, 9, stone()}, new Object[]{9, FY + 1, 8, stone()});
        assertTrue(!lands(run(MovementRegistry.DIAGONAL_ASCEND, g, 8, FY, 8), 9, FY + 1, 9),
                "the flat corner rule carries over: a solid y+1 corner cell never squeezes the body");
    }

    // ---- DiagonalDescend ------------------------------------------------------------------------------

    @Test
    void emitsTheCleanDiagonalStepDown_editFree() {
        NavGridView g = withStart(new Object[]{9, FY - 1, 9, stone()});
        List<Cand> c = run(MovementRegistry.DIAGONAL_DESCEND, g, 8, FY, 8);
        assertTrue(lands(c, 9, FY - 1, 9), "the diagonal one-block step-down is the move");
        Cand hit = c.stream().filter(x -> x.y == FY - 1).findFirst().orElseThrow();
        assertEquals(DiagonalDescend.COST, hit.cost, 1e-4);
        assertEquals(false, hit.hasEdits, "O2: no folded edits — no requireFloor place arm either");
    }

    @Test
    void descendRefusesWithoutAFloor() {
        NavGridView g = withStart();
        assertTrue(run(MovementRegistry.DIAGONAL_DESCEND, g, 8, FY, 8).isEmpty(),
                "no destination floor and no place arm (O2) — nothing emitted");
    }

    @Test
    void descendCornerAndTransitGates() {
        NavGridView corner = withStart(new Object[]{9, FY - 1, 9, stone()}, new Object[]{9, FY + 1, 8, stone()});
        assertTrue(!lands(run(MovementRegistry.DIAGONAL_DESCEND, corner, 8, FY, 8), 9, FY - 1, 9),
                "the flat corner sweep (y+1..y+2, D3) refuses a solid corner cell");
        NavGridView transit = withStart(new Object[]{9, FY - 1, 9, stone()}, new Object[]{9, FY + 2, 9, stone()});
        assertTrue(!lands(run(MovementRegistry.DIAGONAL_DESCEND, transit, 8, FY, 8), 9, FY - 1, 9),
                "the step-off head cell (y+2 over the dest column) is a strict refusal, not a break");
    }
}
