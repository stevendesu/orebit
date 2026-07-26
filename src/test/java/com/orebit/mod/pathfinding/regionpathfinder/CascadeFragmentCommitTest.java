package com.orebit.mod.pathfinding.regionpathfinder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.worldmodel.hpa.CostPyramid;
import com.orebit.mod.worldmodel.hpa.FragmentBuilder;
import com.orebit.mod.worldmodel.hpa.RegionFragments;
import com.orebit.mod.worldmodel.hpa.RegionGrid;

import net.minecraft.core.BlockPos;

/**
 * The <b>fragment-gated committed-advance</b> tests ({@link HierarchicalRegionPlan#onBotMoved} — the s54
 * per-tick replan-livelock fix). Node identity under the fragment model is {@code (region, fragment)}, but the
 * level-0 committed-advance scan matched skeleton steps by REGION alone. A skeleton that legitimately
 * RE-ENTERS the bot's region at the window-far index through a DIFFERENT fragment (the two-pocket cliff
 * region: bot in the bottom pocket, route back in through the top pocket) then falsely committed the bot to
 * the far step, fired {@code exhausted}, and re-derived an IDENTICAL skeleton (same inputs) as a NEW object —
 * which the driver swapped in, resetting the block window and the follower's phase plan, every tick, forever.
 *
 * <p>Fixture idiom: hand-authored {@link FragmentBuilder} masks over a record-only headless grid (the
 * {@link RegionTubeEscalationTest} seed idiom) — a sealed 5-region RING whose only route leaves the bot's
 * region through its bottom pocket and re-enters through its top pocket exactly at the window-far index:
 *
 * <pre>
 *        E ← A(top) ← D
 *            A(bot)   ↑
 *              ↓      C
 *              B  →  ↗
 * </pre>
 *
 * A=(0,1,0) is two disconnected pockets (bottom frag 0, top frag 1); B=(1,1,0), C=(1,1,1), D=(0,1,1),
 * E=(-1,1,0) are open single-fragment boxes; everything else is sealed solid and the caps are no-break /
 * no-place, so the ONLY route is {@code [A:0, B, C, D, A:1, E(goal)]} — size 6, window-far index 4 = the A
 * re-entry via fragment 1, and far &lt; size-1 so the reached-goal-end guard does NOT mask exhaustion.
 *
 * <p>On this record-only grid the flood resolver cannot run (no backing sections), so the bot's fragment in
 * the multi-fragment region A resolves to {@code -1} (unresolvable) — which must behave exactly like a
 * fragment mismatch: NO advance, NO exhausted-rederive (the safe fallback; advancing is only an
 * optimization). Single-fragment regions resolve to their only kept id 0 and advance exactly as before.
 */
public class CascadeFragmentCommitTest {

    private static final int G = 16;
    private static final int CELLS = G * G * G;
    private static final int MINY = 0;
    private static final int STONE = 8;

    /** No-break, no-place caps: the seals are real walls and A's pockets cannot be mine-connected, so the
     *  ring is the only route. */
    private static final BotCaps CAPS = BotCaps.DEFAULT;

    private RegionGrid grid;

    private static int idx(int x, int y, int z) { return (y << 8) | (z << 4) | x; }

    @BeforeEach
    void setUp() {
        buildRingFixture();
    }

    // ===================================================================================================
    // F1 — the livelock shape: the window-far step revisits the bot's region via a DIFFERENT fragment.
    // onBotMoved from the (unmoved) bot must NOT advance committedIndex and must NOT fire the
    // exhausted-rederive: same committed cursor, same skeleton OBJECT, false return — tick after tick.
    // (OLD code: region-only far match → committedIndex=far → exhausted → identical-content re-derive as a
    // new object every tick — the s54 per-tick replan livelock.)
    // ===================================================================================================
    @Test
    void farStepDifferentFragment_noFalseCommit_noRederive() {
        BlockPos bot = feet(0, 1, 0);   // A, bottom pocket
        BlockPos goal = feet(-1, 1, 0); // E
        HierarchicalRegionPlan h = HierarchicalRegionPlan.build(grid, MINY, bot, goal, CAPS);
        RegionPathPlan l0 = assertLivelockShape(h);

        assertEquals(0, h.committedAt(0), "a fresh cascade starts uncommitted");
        for (int tick = 0; tick < 5; tick++) {
            boolean changed = h.onBotMoved(bot, false);
            assertFalse(changed, "tick " + tick + ": an unmoved bot must not trigger an L0 re-derive");
            assertSame(l0, h.skeletonAt(0),
                    "tick " + tick + ": the skeleton object must survive (no exhausted-rederive fired)");
            assertEquals(0, h.committedAt(0),
                    "tick " + tick + ": a region-only far match (different fragment) must NOT advance the commit");
        }
    }

    // ===================================================================================================
    // F2 — legitimate same-fragment advance is preserved: walking the ring commits each single-fragment
    // step exactly as the region-only scan always did (their only kept fragment id matches trivially).
    // ===================================================================================================
    @Test
    void sameFragmentAdvance_stillWorks() {
        BlockPos bot = feet(0, 1, 0);
        HierarchicalRegionPlan h = HierarchicalRegionPlan.build(grid, MINY, bot, feet(-1, 1, 0), CAPS);
        RegionPathPlan l0 = assertLivelockShape(h);

        assertFalse(h.onBotMoved(feet(1, 1, 0), false), "stepping to B stays inside the window");
        assertEquals(1, h.committedAt(0), "B (single fragment) must commit — the advance optimization survives");
        assertFalse(h.onBotMoved(feet(1, 1, 1), false), "stepping to C stays inside the window");
        assertEquals(2, h.committedAt(0), "C must commit");
        assertFalse(h.onBotMoved(feet(0, 1, 1), false), "stepping to D stays inside the window");
        assertEquals(3, h.committedAt(0), "D must commit");
        assertSame(l0, h.skeletonAt(0), "in-window ring progress never re-derives the L0 skeleton");

        // Commit hysteresis survives the nearest-first scan: a transient dip BACK to B (index 1, behind the
        // commit cursor) with the block plan vouching for it (onRoute=true) neither retreats committedIndex
        // nor re-derives.
        assertFalse(h.onBotMoved(feet(1, 1, 0), true), "an on-plan dip back must not re-derive");
        assertEquals(3, h.committedAt(0), "a transient dip never retreats the commit cursor");
        assertSame(l0, h.skeletonAt(0));
    }

    // ===================================================================================================
    // F3 — the unresolvable-fragment fallback is safe AND non-sticky: moving WITHIN the multi-fragment
    // region (fragment unresolvable on a record-only grid → -1) never advances; the very next step into a
    // resolvable single-fragment region commits normally.
    // ===================================================================================================
    @Test
    void unresolvableFragment_noAdvance_thenRecoversInNextRegion() {
        BlockPos bot = feet(0, 1, 0);
        HierarchicalRegionPlan h = HierarchicalRegionPlan.build(grid, MINY, bot, feet(-1, 1, 0), CAPS);
        RegionPathPlan l0 = assertLivelockShape(h);

        // A short shuffle within A's bottom pocket: region matches steps 0 AND far — with the fragment
        // unresolvable, NEITHER may advance the commit (refusing to guess between real sibling fragments).
        assertFalse(h.onBotMoved(new BlockPos(10, 17, 9), false));
        assertEquals(0, h.committedAt(0), "an unresolvable fragment must never advance the commit");
        assertSame(l0, h.skeletonAt(0));

        // Recovery: the fallback is per-position, not sticky — entering B resolves and commits.
        assertFalse(h.onBotMoved(feet(1, 1, 0), false));
        assertEquals(1, h.committedAt(0), "the next resolvable region must commit normally");
        assertSame(l0, h.skeletonAt(0));
    }

    // ---- fixture ---------------------------------------------------------------------------------------

    /** Asserts (and returns) the built plan carries the exact livelock precondition this suite targets:
     *  a fragment-model L0 skeleton whose window-far step revisits the START region via a DIFFERENT
     *  fragment, with the far step NOT the terminal goal step (else reachedEnd masks exhaustion). */
    private static RegionPathPlan assertLivelockShape(HierarchicalRegionPlan h) {
        assertFalse(h.isFailed(), "the ring fixture must route");
        assertEquals(0, h.topLevel(), "a ~2-region span is cap-safe at level 0");
        RegionPathPlan l0 = h.l0Skeleton();
        assertNotNull(l0);
        assertTrue(l0.isFragmentModel(), "the cascade plans the fragment model");
        final int far = Math.min(HierarchicalRegionPlan.WINDOW_CELLS, l0.size() - 1);
        assertTrue(far < l0.size() - 1,
                "the far step must not be the terminal goal step (reachedEnd would mask exhaustion)");
        assertEquals(0, l0.rx(0)); assertEquals(1, l0.ry(0)); assertEquals(0, l0.rz(0));
        assertEquals(0, l0.rx(far), "the window-far step must revisit the start region (x)");
        assertEquals(1, l0.ry(far), "the window-far step must revisit the start region (y)");
        assertEquals(0, l0.rz(far), "the window-far step must revisit the start region (z)");
        assertNotEquals(l0.fragmentId(0), l0.fragmentId(far),
                "the revisit must enter through a DIFFERENT fragment — the livelock shape");
        return l0;
    }

    /**
     * The sealed 5-region ring (see the class javadoc): A=(0,1,0) two disconnected pockets — bottom
     * (x1..15, z0..14, floor y0, air y1..6; open EAST to B only) and top (x0..14, z0..15, floor y8, air
     * y9..14; open +Z to D and WEST to E, sealed EAST) — B/C/D/E open boxes, all outer neighbours solid.
     */
    private void buildRingFixture() {
        grid = RegionGrid.headless(MINY);
        seedTwoPocketA();
        seedOpen(1, 1, 0);   // B
        seedOpen(1, 1, 1);   // C
        seedOpen(0, 1, 1);   // D
        seedOpen(-1, 1, 0);  // E
        // Horizontal seals around the ring.
        seedSolid(0, 1, -1); seedSolid(1, 1, -1); seedSolid(-1, 1, -1);
        seedSolid(2, 1, 0); seedSolid(2, 1, 1);
        seedSolid(1, 1, 2); seedSolid(0, 1, 2);
        seedSolid(-1, 1, 1); seedSolid(-2, 1, 0);
        // Vertical caps below/above every ring region.
        int[][] ring = { {0, 0}, {1, 0}, {1, 1}, {0, 1}, {-1, 0} };
        for (int[] r : ring) {
            seedSolid(r[0], 0, r[1]);
            seedSolid(r[0], 2, r[1]);
        }
    }

    /** Region A: two disconnected occupiable pockets — kept ids 0 (bottom, floods first) and 1 (top). */
    private void seedTwoPocketA() {
        boolean[] passable = new boolean[CELLS];
        boolean[] standable = new boolean[CELLS];
        for (int x = 1; x < 16; x++) {          // bottom pocket: open EAST (x15), sealed WEST/+Z at its band
            for (int z = 0; z < 15; z++) {
                standable[idx(x, 0, z)] = true;
                for (int y = 1; y <= 6; y++) passable[idx(x, y, z)] = true;
            }
        }
        for (int x = 0; x < 15; x++) {          // top pocket: open WEST (x0) and +Z (z15), sealed EAST
            for (int z = 0; z < 16; z++) {
                standable[idx(x, 8, z)] = true;
                for (int y = 9; y <= 14; y++) passable[idx(x, y, z)] = true;
            }
        }
        seed(0, 1, 0, passable, standable);
    }

    // ---- seed helpers (real FragmentBuilder flood — the RegionTubeEscalationTest idiom) ---------------

    private void seed(int rx, int ry, int rz, boolean[] passable, boolean[] standable) {
        int passCount = 0, standCount = 0, solidCount = 0;
        long hardnessSumSolid = 0;
        for (int i = 0; i < CELLS; i++) {
            if (passable[i]) passCount++;
            else { solidCount++; hardnessSumSolid += STONE; }
            if (standable[i]) standCount++;
        }
        CostPyramid pyr = grid.pyramid();
        int row = pyr.rowFor(0, rx, ry, rz);
        RegionFragments rf = pyr.ensureFragments(0, row);
        FragmentBuilder.build(passable, standable, null, G, passCount, standCount, 0, hardnessSumSolid, solidCount, rf);
        pyr.setBuilt(0, row, true);
    }

    private void seedSolid(int rx, int ry, int rz) {
        seed(rx, ry, rz, new boolean[CELLS], new boolean[CELLS]);
    }

    /** Full open box: standable slab at local y=0, passable air y1..14 — one kept fragment (id 0). */
    private void seedOpen(int rx, int ry, int rz) {
        boolean[] passable = new boolean[CELLS];
        boolean[] standable = new boolean[CELLS];
        for (int x = 0; x < G; x++) {
            for (int z = 0; z < G; z++) {
                standable[idx(x, 0, z)] = true;
                for (int y = 1; y <= 14; y++) passable[idx(x, y, z)] = true;
            }
        }
        seed(rx, ry, rz, passable, standable);
    }

    private static BlockPos feet(int rx, int ry, int rz) {
        return new BlockPos((rx << 4) + 8, MINY + (ry << 4) + 1, (rz << 4) + 8);
    }
}
