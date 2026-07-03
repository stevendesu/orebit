package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.orebit.mod.Debug;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.MovementRegistry;
import com.orebit.mod.pathfinding.blockpathfinder.RegionBound;
import com.orebit.mod.pathfinding.blockpathfinder.movements.Parkour;
import com.orebit.mod.pathfinding.blockpathfinder.movements.Traverse;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;

/**
 * Headless proof of the {@code Parkour} (c,±1) OFFSET-jump fallback tier. One sealed stone section with
 * TWO carved column files: the ALIGNED file (z=8) holds the start platform ({@code x=1..4}, floor y=5)
 * and a bottomless chasm ({@code x=5..14}, air to y=0; below the grid is unbuilt so {@code Fall} never
 * lands) with NO aligned landing of any class inside the scan horizon; the OFFSET file (z=9) is void
 * ({@code x=4..8}) except a landing PILLAR whose floor (y=5) is the goal — reachable ONLY by the
 * knight's-move hop. The bot has {@link BotCaps#DEFAULT} (no break/place) throughout.
 *
 * <p><b>Positives.</b> A (2,+1) hop from the takeoff edge (4,5,8) to the pillar (6,5,9) plans as exactly
 * ONE Parkour waypoint at the stand position, with the exact displacement-interpolated cost
 * ({@code 2·FLAT_COST + }{@link Parkour#offsetCost}{@code (2)} — pins the √5 interpolation and the
 * zero-surcharge transit). The (3,+1) shape (pillar at (7,5,9)) likewise, at {@code offsetCost(3)}.
 *
 * <p><b>Negatives.</b> (1) FALLBACK-ONLY: adding an aligned landing island ((8,·,8) — the aligned scan
 * emits a flat 3-gap jump) must suppress the probe entirely, making the pillar UNREACHABLE (asserted
 * null, with an aligned-jump sanity plan first). (2) Supercover: a blocker at prism height in the
 * STRADDLED second column file ((5,7,9) — swept column (1,1) of the (2,+1) arc) rejects the hop.
 * (3) Envelope: the (3,±1) shape (displacement √10 ≈ 3.16) is legal at the default flat reach 4.0 but
 * must be re-gated when {@link Parkour#PARKOUR_MAX_GAP} = 2 shrinks the reach to 3.0. (4) The
 * open-gap FLAG: a stepping stone at (5,·,8) makes the takeoff's aligned scan meet a standable column-1
 * cell (NO open gap ⇒ no probe from (4,5,8)) even though a (3,+1) hop from there would verify — the
 * correct route walks onto the stone and (2,+1)-hops, which the exact-cost assertion pins (a flag-less
 * implementation would emit the CHEAPER (3,+1) from (4,5,8) and fail the cost check).
 *
 * <p>(5) The {@link Parkour#OFFSET_FALLBACK} kill switch (the flood A/B lever — see the cost-honesty
 * paragraph on {@code Parkour}) OFF must suppress the probe entirely, restoring the aligned-only scan.
 *
 * <p>Flags touched ({@link Parkour#PARKOUR_MAX_GAP}, {@link Parkour#OFFSET_FALLBACK}) are restored in
 * {@code finally}. Not testable headless: the skewed-arc kinematics and the normalized takeoff trigger
 * (the in-game pass).
 */
class ParkourOffsetTest {

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

    @Test
    void offsetLandingFoundWhenAlignedScanHasGapButNoLanding() {
        // Pillar at (6,5,9) = the (2,+1) shape from the takeoff edge (4,5,8); the aligned file is a
        // bottomless landing-less chasm, so the fallback arms and the knight hop is the only route.
        NavGridView grid = buildOffsetCourse(6, false, false, null);
        BlockPathPlan plan = BlockPathfinder.findPath(grid, START, new BlockPos(6, 5, 9),
                BotCaps.DEFAULT, CORRIDOR);

        assertNotNull(plan, "the (2,+1) offset hop should be found when the aligned scan has a gap "
                + "but no landing");
        assertEquals(1, count(plan, MovementRegistry.PARKOUR),
                "one offset hop should be exactly one Parkour waypoint");
        assertEquals(new BlockPos(6, 6, 9), waypointOf(plan, MovementRegistry.PARKOUR),
                "the hop's waypoint should be the stand position above the offset landing floor");
        // Exact cost pins the displacement-interpolated pricing: two walk steps (2->3->4) + the √5 shape.
        assertEquals(2 * Traverse.FLAT_COST + Parkour.offsetCost(2), plan.cost(), 1e-3,
                "the (2,+1) hop should cost the interpolated offset edge with zero transit surcharge");
    }

    @Test
    void offsetsAreNotProbedWhenAnAlignedLandingEmits() {
        // Same course plus an aligned 3-gap landing island at (8,·,8): the aligned scan now EMITS, so
        // the fallback must not arm and the pillar (reachable only by the hop) becomes unreachable.
        NavGridView grid = buildOffsetCourse(6, true, false, null);

        BlockPathPlan aligned = BlockPathfinder.findPath(grid, START, new BlockPos(8, 5, 8),
                BotCaps.DEFAULT, CORRIDOR);
        assertNotNull(aligned, "sanity: the aligned flat 3-gap landing must itself be planable");
        assertEquals(1, count(aligned, MovementRegistry.PARKOUR),
                "sanity: the aligned route is a single Parkour jump");

        assertNull(BlockPathfinder.findPath(grid, START, new BlockPos(6, 5, 9),
                        BotCaps.DEFAULT, CORRIDOR),
                "offsets must NOT be probed for a direction whose aligned scan emitted a landing");
    }

    @Test
    void blockerInTheStraddledColumnFileRejectsTheHop() {
        // The (2,+1) arc straddles two column files; stone at (5,7,9) — prism height (y+2) in the
        // SECOND file's swept column (1,1) — must reject the hop via the supercover verification.
        NavGridView grid = buildOffsetCourse(6, false, false, new BlockPos(5, 7, 9));
        assertNull(BlockPathfinder.findPath(grid, START, new BlockPos(6, 5, 9),
                        BotCaps.DEFAULT, CORRIDOR),
                "a blocker in the straddled second column file must reject the offset hop");
    }

    @Test
    void threeOneShapeHonorsTheFlatReachEnvelope() {
        // Pillar at (7,5,9) = the (3,+1) shape, displacement √10 ≈ 3.16: inside the default flat reach
        // 4.0 (flat cap 3), so found — then re-gated when the knob shrinks the reach to 3.0.
        NavGridView grid = buildOffsetCourse(7, false, false, null);
        BlockPos goal = new BlockPos(7, 5, 9);

        BlockPathPlan plan = BlockPathfinder.findPath(grid, START, goal, BotCaps.DEFAULT, CORRIDOR);
        assertNotNull(plan, "the (3,+1) hop is inside the default flat reach (√10 <= 4.0)");
        assertEquals(1, count(plan, MovementRegistry.PARKOUR), "a single Parkour waypoint");
        assertEquals(new BlockPos(7, 6, 9), waypointOf(plan, MovementRegistry.PARKOUR),
                "stand position above the (3,+1) landing floor");
        assertEquals(2 * Traverse.FLAT_COST + Parkour.offsetCost(3), plan.cost(), 1e-3,
                "the (3,+1) hop should cost the interpolated offset edge");

        int saved = Parkour.PARKOUR_MAX_GAP;
        Parkour.PARKOUR_MAX_GAP = 2;
        try {
            assertNull(BlockPathfinder.findPath(grid, START, goal, BotCaps.DEFAULT, CORRIDOR),
                    "flat cap 2 shrinks the reach to 3.0 < √10 — the (3,±1) shape must be gated");
        } finally {
            Parkour.PARKOUR_MAX_GAP = saved;
        }
    }

    @Test
    void fallbackRequiresAnOpenAlignedGap() {
        // Stepping stone at (5,·,8): the takeoff (4,5,8)'s aligned scan meets a standable cell at
        // column 1 — no OPEN gap column — so its fallback must not arm even though a (3,+1) hop from
        // there to the pillar (7,5,9) would fully verify (the stone floor passes the corner rule, every
        // swept prism is clear). The correct route walks one further onto the stone and (2,+1)-hops
        // (3 walk steps + offsetCost(2) ≈ 30.24); a flag-less implementation would instead emit the
        // cheaper (3,+1) from (4,5,8) (2 walk steps + offsetCost(3) ≈ 28.39) — the exact-cost assertion
        // is the discriminator (waypoint and count are identical either way).
        NavGridView grid = buildOffsetCourse(7, false, true, null);
        BlockPathPlan plan = BlockPathfinder.findPath(grid, START, new BlockPos(7, 5, 9),
                BotCaps.DEFAULT, CORRIDOR);

        assertNotNull(plan, "the (2,+1) hop from the stepping stone should reach the pillar");
        assertEquals(1, count(plan, MovementRegistry.PARKOUR), "a single Parkour waypoint");
        assertEquals(new BlockPos(7, 6, 9), waypointOf(plan, MovementRegistry.PARKOUR),
                "stand position above the pillar floor");
        assertEquals(3 * Traverse.FLAT_COST + Parkour.offsetCost(2), plan.cost(), 1e-3,
                "no gap at column 1 must mean no probe from (4,5,8): the plan walks onto the stone "
                        + "and takes the (2,+1) shape, never the (3,+1) from the platform edge");
    }

    @Test
    void offsetFallbackFlagOffRestoresTheAlignedOnlyScan() {
        // The runtime kill switch / flood A/B lever (Parkour's cost-honesty paragraph): OFF must
        // suppress the probe entirely — the pillar, reachable only by the (2,+1) hop, becomes
        // unreachable, bit-for-bit the aligned-only scan.
        NavGridView grid = buildOffsetCourse(6, false, false, null);
        boolean saved = Parkour.OFFSET_FALLBACK;
        Parkour.OFFSET_FALLBACK = false;
        try {
            assertNull(BlockPathfinder.findPath(grid, START, new BlockPos(6, 5, 9),
                            BotCaps.DEFAULT, CORRIDOR),
                    "OFFSET_FALLBACK = false must suppress the (c,±1) probe entirely");
        } finally {
            Parkour.OFFSET_FALLBACK = saved;
        }
    }

    // ---------------------------------------------------------------- helpers

    private static int count(BlockPathPlan plan, Object move) {
        int n = 0;
        for (int i = 0; i < plan.size(); i++) {
            if (plan.movement(i) == move) n++;
        }
        return n;
    }

    /** The stand-position waypoint of the first step using {@code move} (null if none). */
    private static BlockPos waypointOf(BlockPathPlan plan, Object move) {
        for (int i = 0; i < plan.size(); i++) {
            if (plan.movement(i) == move) return plan.waypoint(i);
        }
        return null;
    }

    /**
     * The two-file offset course (sealed stone section, standard harness):
     * <ul>
     *   <li><b>z=8, the ALIGNED file</b>: start platform {@code x=1..4} (floor y=5, air y=6..10), then a
     *       bottomless chasm {@code x=5..14} (air y=0..10) — no aligned landing of any class;</li>
     *   <li><b>z=9, the OFFSET file</b>: air y=0..10 for {@code x=4..8} (the swept columns' floor cells
     *       and prisms), with the landing PILLAR (stone y=0..5, floor y=5) at {@code (pillarX, 9)};</li>
     *   <li>{@code alignedLanding} refills {@code (8, 0..5, 8)} — a flat aligned 3-gap landing island
     *       that must SUPPRESS the fallback;</li>
     *   <li>{@code steppingStone} refills {@code (5, 0..5, 8)} — extends the platform one column so the
     *       takeoff's aligned scan sees a standable column-1 cell (no open gap ⇒ no probe there);</li>
     *   <li>{@code extraStone} (nullable) re-fills one cell — the supercover blocker knob.</li>
     * </ul>
     */
    private static NavGridView buildOffsetCourse(int pillarX, boolean alignedLanding,
            boolean steppingStone, BlockPos extraStone) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();

        PalettedContainer<BlockState> s = new PalettedContainer<>(
                Block.BLOCK_STATE_REGISTRY, air, PalettedContainer.Strategy.SECTION_STATES);
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    s.set(x, y, z, stone);
                }
            }
        }

        for (int x = 1; x <= 4; x++) {              // aligned file: start platform, headroom to y=10
            for (int y = 6; y <= 10; y++) {
                s.set(x, y, 8, air);
            }
        }
        for (int x = 5; x <= 14; x++) {             // aligned file: the bottomless chasm
            for (int y = 0; y <= 10; y++) {
                s.set(x, y, 8, air);
            }
        }
        for (int x = 4; x <= 8; x++) {              // offset file: void over the swept columns
            for (int y = 0; y <= 10; y++) {
                s.set(x, y, 9, air);
            }
        }
        for (int y = 0; y <= 5; y++) {              // the landing pillar (floor y=5)
            s.set(pillarX, y, 9, stone);
        }
        if (alignedLanding) {
            for (int y = 0; y <= 5; y++) {
                s.set(8, y, 8, stone);
            }
        }
        if (steppingStone) {
            for (int y = 0; y <= 5; y++) {
                s.set(5, y, 8, stone);
            }
        }
        if (extraStone != null) {
            s.set(extraStone.getX(), extraStone.getY(), extraStone.getZ(), stone);
        }

        NavSection section = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(s, false, section.getTraversalGrid());

        PalettedContainer<BlockState> airStates = new PalettedContainer<>(
                Block.BLOCK_STATE_REGISTRY, air, PalettedContainer.Strategy.SECTION_STATES);
        NavSection airSection = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(airStates, true, airSection.getTraversalGrid());

        NavSection[] column = { section, airSection, airSection, airSection };
        ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        chunks.put(NavStore.key(0, 0), column);
        return new NavGridView(0, chunks);
    }
}
