package com.orebit.mod.worldmodel.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the {@link ResourceQuery} best-first drill-down (find-mine-resources design §6). Pure
 * data-plane — <b>no Minecraft world</b>: they drive the headless
 * {@link ResourceQuery#find(ResourcePyramid, int, int, int, int, int, int, int) find(pyramid, minY, column,
 * ax, ay, az, minCount, maxResults)} overload against a hand-built {@link ResourcePyramid} rolled up with
 * {@link ResourceMerger}. Raw column indices are used (never {@link ResourceClasses}) so the test does not
 * force the block-registry class-init — mirroring the MC-free phase 3/4 tests.
 *
 * <p><b>Fixture geometry ({@code minY = 0}).</b> Two level-0 regions {@code A=(0,0,0)} and {@code B=(1,0,0)}
 * are siblings under the level-1 parent {@code (0,0,0)}. The anchor sits in a third, resource-free sibling
 * {@code C=(0,0,1)} at world {@code (8,8,20)} — so it never short-circuits on its own leaf and the query must
 * ascend to the parent and descend. A's center {@code (8,8,8)} is 12 blocks from the anchor; B's center
 * {@code (24,8,8)} is farther — so A is the nearest hit.
 */
public class ResourceQueryTest {

    private static final int COL = 7;         // an arbitrary indexed column ("diamond" slot; used as a raw id)
    private static final int EMPTY_COL = 5;   // a column never seeded — stays 0 everywhere
    private static final int MIN_Y = 0;

    // Anchor inside the resource-free sibling C=(0,0,1): world (8,8,20).
    private static final int AX = 8, AY = 8, AZ = 20;

    /** Seed a level-0 region's column to {@code count} and roll it up to its ancestors. */
    private static void seed(ResourcePyramid p, int rx, int ry, int rz, int col, int count) {
        int row = p.rowFor(0, rx, ry, rz);
        p.setLog2(0, row, col, Log2Codec.encode(count));
        p.setBuilt(0, row, true);
        ResourceMerger.mergeUpTallies(p, rx, ry, rz);
    }

    @Test
    void nearestOfTwoEqualCountRegionsIsFirst() {
        ResourcePyramid p = new ResourcePyramid();
        seed(p, 0, 0, 0, COL, 4); // A
        seed(p, 1, 0, 0, COL, 4); // B (equal count, farther)

        List<ResourceQuery.ResourceHit> hits =
                ResourceQuery.find(p, MIN_Y, COL, AX, AY, AZ, /*minCount*/ 3, /*maxResults*/ 5);

        assertEquals(2, hits.size(), "both qualifying regions returned");
        assertEquals(0, hits.get(0).rx(), "A (nearer) is first");
        assertEquals(1, hits.get(1).rx(), "B (farther) is second");
        assertEquals(4, hits.get(0).approxCount(), "decoded approx count of A");
        // Center of the nearest hit is A's center block (8,8,8).
        assertEquals(8, hits.get(0).center().getX());
        assertEquals(8, hits.get(0).center().getY());
        assertEquals(8, hits.get(0).center().getZ());
    }

    @Test
    void regionBelowMinCountIsExcluded() {
        ResourcePyramid p = new ResourcePyramid();
        seed(p, 0, 0, 0, COL, 4); // A: qualifies at minCount 3
        seed(p, 1, 0, 0, COL, 1); // B: only 1 -> pruned by minCount 3

        List<ResourceQuery.ResourceHit> hits =
                ResourceQuery.find(p, MIN_Y, COL, AX, AY, AZ, /*minCount*/ 3, /*maxResults*/ 5);

        assertEquals(1, hits.size(), "only the region meeting minCount is returned");
        assertEquals(0, hits.get(0).rx(), "and it is A");
    }

    @Test
    void maxResultsCapsTheList() {
        ResourcePyramid p = new ResourcePyramid();
        seed(p, 0, 0, 0, COL, 4); // A
        seed(p, 1, 0, 0, COL, 4); // B
        seed(p, 1, 1, 0, COL, 4); // D — a third sibling under parent (0,0,0)

        List<ResourceQuery.ResourceHit> hits =
                ResourceQuery.find(p, MIN_Y, COL, AX, AY, AZ, /*minCount*/ 3, /*maxResults*/ 2);

        assertEquals(2, hits.size(), "maxResults caps the returned list at 2 of the 3 candidates");
    }

    @Test
    void emptyWhenColumnAbsent() {
        ResourcePyramid p = new ResourcePyramid();
        seed(p, 0, 0, 0, COL, 4);
        seed(p, 1, 0, 0, COL, 4);

        // A column that was never seeded reads 0 at every ancestor -> nothing meets minCount 1 -> empty.
        List<ResourceQuery.ResourceHit> absent =
                ResourceQuery.find(p, MIN_Y, EMPTY_COL, AX, AY, AZ, /*minCount*/ 1, /*maxResults*/ 5);
        assertTrue(absent.isEmpty(), "a column with no data returns no hits");

        // Present column but an unreachable threshold -> also empty (no ancestor holds that many).
        List<ResourceQuery.ResourceHit> tooMany =
                ResourceQuery.find(p, MIN_Y, COL, AX, AY, AZ, /*minCount*/ 1_000_000, /*maxResults*/ 5);
        assertTrue(tooMany.isEmpty(), "a minCount above any aggregate returns no hits");
    }

    @Test
    void findCrossesTheWorldOrigin() {
        ResourcePyramid p = new ResourcePyramid();
        // The resource sits in region (-1,*,-1) — x,z in [-16,0). The anchor at world (8,8,8) is in region
        // (0,*,0), one region across the origin. Regions 0 and -1 share NO common ancestor (0 is a grid
        // boundary at every level), so the old single-ancestor ascend could never reach it. The 3x3
        // neighbourhood ascend covers rx/rz in {-1,0,1}, so it does.
        seed(p, -1, 0, -1, COL, 4);

        List<ResourceQuery.ResourceHit> hits =
                ResourceQuery.find(p, MIN_Y, COL, /*ax*/ 8, /*ay*/ 8, /*az*/ 8, /*minCount*/ 1, /*maxResults*/ 5);

        assertEquals(1, hits.size(), "the resource just across the world origin is found");
        assertEquals(-1, hits.get(0).rx(), "the hit is region (-1,*,-1)");
        assertEquals(-1, hits.get(0).rz());
    }

    // ---- windowLog2: the player-centered box sum the /bot report compass shows ------------------------

    @Test
    void windowSumsBoxAndIsStableAcrossOrigin() {
        ResourcePyramid p = new ResourcePyramid();
        seed(p, 0, 0, 0, COL, 4);    // a region in the +,+ quadrant, touching the origin
        seed(p, -1, 0, -1, COL, 4);  // the diagonally-opposite region across the origin

        // A ~64-block box at level 1 centered just PLUS of the origin and just MINUS of it both cover BOTH
        // regions, so the windowed count is identical — the origin no longer splits the reading. (The old
        // single-cell read landed (1,1) in region (0,*,0) and (-1,-1) in region (-1,*,-1): different answers.)
        byte plus  = ResourceQuery.windowLog2(p, 1, 1, /*level*/ 1, /*radius*/ 64, COL);
        byte minus = ResourceQuery.windowLog2(p, -1, -1, /*level*/ 1, /*radius*/ 64, COL);
        assertEquals(plus, minus, "windows a few blocks either side of the origin agree (stable)");
        assertEquals(8, Log2Codec.decode(plus), "both regions (4+4) folded into the ~8 bucket");
    }

    @Test
    void windowCountsFullVerticalColumn() {
        ResourcePyramid p = new ResourcePyramid();
        seed(p, 0, 5, 0, COL, 4); // a vein deep in the column (vertical index 5), not at the surface

        // windowLog2 folds EVERY vertical region index, so a deep vein counts regardless of the player's Y
        // (the method takes no Y at all — "how much is near me" means the whole column).
        byte w = ResourceQuery.windowLog2(p, 0, 0, /*level*/ 1, /*radius*/ 64, COL);
        assertEquals(4, Log2Codec.decode(w), "the deep vein is summed (full-depth window)");
    }
}
