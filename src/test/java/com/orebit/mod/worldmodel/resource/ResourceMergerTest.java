package com.orebit.mod.worldmodel.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.orebit.mod.worldmodel.hpa.RegionAddress;

/**
 * Unit tests for the {@link ResourceMerger} roll-up (find-mine-resources design §5). Pure data-plane —
 * <b>no Minecraft</b>: a synthetic {@link ResourcePyramid} is hand-seeded at level 0, rolled up, and the
 * ancestors are read back. Pins:
 * <ul>
 *   <li>a parent column is the per-column {@link Log2Codec}-sum of its children, each column independent;</li>
 *   <li>the phase-3 0-identity fix — a column empty in every child stays <b>0</b> at the parent (no phantom
 *       counts);</li>
 *   <li>the walk climbs through the octree levels (≤5, 8 children) and the quadtree transition (levels ≥6, 4
 *       children) all the way to {@link ResourcePyramid#RESOURCE_TOP_LEVEL} — the resource layer is
 *       <b>true-global</b> (unlike the region A*'s {@link RegionAddress#MAX_COARSE_LEVEL} cap);</li>
 *   <li>the damping early-out leaves an unchanged parent untouched yet never drops a real change.</li>
 * </ul>
 *
 * <p>Column indices are hard-coded (diamond=7, iron=1, coal=0 per {@code ResourceClasses.bindColumn}) so this
 * test never forces {@code ResourceClasses} class-init (which touches the MC block registry).
 */
public class ResourceMergerTest {

    private static final int COAL = 0;
    private static final int IRON = 1;
    private static final int DIAMOND = 7;

    private static void setLeaf(ResourcePyramid p, int rx, int ry, int rz, int col, int rawCount) {
        int r = p.rowFor(0, rx, ry, rz);
        p.setLog2(0, r, col, Log2Codec.encode(rawCount));
        p.setBuilt(0, r, true);
    }

    @Test
    void octreeRollUpSumsPerColumnIndependently() {
        ResourcePyramid p = new ResourcePyramid();
        // Three level-0 leaves under the SAME level-1 octree parent (0,0,0):
        //   A(0,0,0) diamond=8, C(0,0,1) diamond=8  → parent diamond = merge(4,4) = 5
        //   B(1,0,0) iron=16                         → parent iron    = 5 (independent)
        setLeaf(p, 0, 0, 0, DIAMOND, 8);
        setLeaf(p, 0, 0, 1, DIAMOND, 8);
        setLeaf(p, 1, 0, 0, IRON, 16);

        ResourceMerger.mergeUpTallies(p, 0, 0, 0);
        ResourceMerger.mergeUpTallies(p, 0, 0, 1);
        ResourceMerger.mergeUpTallies(p, 1, 0, 0);

        int pr = p.rowIfPresent(1, 0, 0, 0);
        assertTrue(pr >= 0, "the level-1 parent must be interned by the roll-up");
        assertEquals(5, p.getLog2(1, pr, DIAMOND) & 0xFF, "diamond = log2-sum of the two diamond leaves");
        assertEquals(5, p.getLog2(1, pr, IRON) & 0xFF, "iron rolls up independently of diamond");
        assertEquals(0, p.getLog2(1, pr, COAL) & 0xFF, "coal empty in every child ⇒ stays 0 at the parent");
        for (int c = 0; c < ResourcePyramid.COLUMNS; c++) {
            if (c == DIAMOND || c == IRON) continue;
            assertEquals(0, p.getLog2(1, pr, c) & 0xFF, "no phantom count in empty column " + c);
        }
    }

    @Test
    void rollsUpThroughAllLevelsToTrueGlobal() {
        ResourcePyramid p = new ResourcePyramid();
        setLeaf(p, 0, 0, 0, DIAMOND, 8); // encode(8) = 4
        ResourceMerger.mergeUpTallies(p, 0, 0, 0);

        // A single populated leaf: each single-child ancestor carries the same count up, exercising the octree
        // (levels 1..5, childCount 8), the octree→quadtree transition (level 6, childCount 4) and — now that the
        // resource layer is true-global — every quadtree level above MAX_COARSE_LEVEL up to RESOURCE_TOP_LEVEL.
        for (int lvl = 1; lvl <= ResourcePyramid.RESOURCE_TOP_LEVEL; lvl++) {
            int r = p.rowIfPresent(lvl, 0, 0, 0);
            assertTrue(r >= 0, "ancestor at level " + lvl + " must be interned by the walk");
            assertEquals(4, p.getLog2(lvl, r, DIAMOND) & 0xFF, "diamond carries up unchanged at level " + lvl);
        }
        // The roll-up reaches the true-global top and stops there (RESOURCE_TOP_LEVEL == RegionAddress.MAX_LEVEL,
        // the top of the level table, so there is no level above it to intern).
        assertEquals(RegionAddress.MAX_LEVEL, ResourcePyramid.RESOURCE_TOP_LEVEL,
                "the resource layer rolls up to the addressing root (true-global)");
        assertTrue(p.rowCount(ResourcePyramid.RESOURCE_TOP_LEVEL) >= 1,
                "the true-global top level carries the tally");
    }

    @Test
    void earlyOutKeepsUnchangedButPropagatesRealChange() {
        ResourcePyramid p = new ResourcePyramid();
        setLeaf(p, 0, 0, 0, DIAMOND, 8); // encode 4
        setLeaf(p, 0, 0, 1, DIAMOND, 8); // encode 4
        ResourceMerger.mergeUpTallies(p, 0, 0, 0);
        ResourceMerger.mergeUpTallies(p, 0, 0, 1);

        int pr = p.rowIfPresent(1, 0, 0, 0);
        assertEquals(5, p.getLog2(1, pr, DIAMOND) & 0xFF, "merge(4,4) = 5");

        // Re-run from an unchanged leaf: the damping early-out must not corrupt the (correct) parent.
        ResourceMerger.mergeUpTallies(p, 0, 0, 0);
        assertEquals(5, p.getLog2(1, pr, DIAMOND) & 0xFF, "re-merging an unchanged leaf must leave the parent at 5");

        // A REAL change to leaf A must propagate past the early-out, all the way to the true-global top.
        int a = p.rowIfPresent(0, 0, 0, 0);
        p.setLog2(0, a, DIAMOND, Log2Codec.encode(4096)); // encode 13
        ResourceMerger.mergeUpTallies(p, 0, 0, 0);
        assertEquals(13, p.getLog2(1, pr, DIAMOND) & 0xFF, "the parent = merge(13, 4) = 13 after the change");

        int top = p.rowIfPresent(ResourcePyramid.RESOURCE_TOP_LEVEL, 0, 0, 0);
        assertTrue(top >= 0, "the true-global top ancestor exists");
        assertEquals(13, p.getLog2(ResourcePyramid.RESOURCE_TOP_LEVEL, top, DIAMOND) & 0xFF,
                "the change must reach RESOURCE_TOP_LEVEL (true-global)");
    }
}
