package com.orebit.mod.worldmodel.navblock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;

/**
 * Classification pins for GROUND-COVER blocks (PATHOLOGY P7 — the "random cobble" hunt, owner request
 * 2026-07-23): the executor's {@code Need.FOOTING} places whenever live {@code solidAt} is false, so any
 * cell the SEARCH treats as a floor but the EXECUTOR's solidity test rejects becomes an UNPLANNED
 * placement. Leaf litter (1/16-height ground cover), short grass, and leaves are the suspects from the
 * owner's live runs (lone cobble on forest floor; cobble staircases onto canopy). This test pins what
 * the navtype table actually says, so the FOOTING-mismatch hypothesis is judged on evidence:
 * <ul>
 *   <li>PASSABLE ground cover (no collision — litter/grass) must NOT be standable: the floor under it is
 *       the dirt below, {@code solidAt(dirt)} is true, FOOTING no-ops — litter is then EXONERATED.</li>
 *   <li>If litter IS standable (or leaves' standable/solidAt disagree), the mismatch is CONFIRMED as a
 *       placement source and the fix is aligning the executor's footing test with the search's floor
 *       model.</li>
 * </ul>
 * Diagnostic pins, deliberately verbose in failure messages — print the packed descriptor on mismatch.
 */
class GroundCoverClassificationTest {

    @BeforeAll
    static void boot() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static long desc(net.minecraft.world.level.block.state.BlockState state) {
        return NavBlock.descriptor(NavBlock.navtypeFor(state));
    }

    @Test
    void leafLitterIsPassableNotStandable() {
        long d = desc(Blocks.LEAF_LITTER.defaultBlockState());
        assertTrue(NavBlock.isPassable(d),
                "leaf litter must be passable (ground cover) — descriptor=" + Long.toHexString(d));
        assertFalse(NavBlock.isStandable(d),
                "leaf litter must NOT be a floor: if standable, every run cell over litter makes the "
                        + "executor's FOOTING solidAt test place an unplanned cobble — descriptor="
                        + Long.toHexString(d));
    }

    @Test
    void shortGrassIsPassableNotStandable() {
        long d = desc(Blocks.SHORT_GRASS.defaultBlockState());
        assertTrue(NavBlock.isPassable(d), "short grass passable — descriptor=" + Long.toHexString(d));
        assertFalse(NavBlock.isStandable(d),
                "short grass must not be a floor — descriptor=" + Long.toHexString(d));
    }

    @Test
    void leavesAreStandableAndNotPassable() {
        long d = desc(Blocks.OAK_LEAVES.defaultBlockState());
        assertFalse(NavBlock.isPassable(d), "leaves are collidable — descriptor=" + Long.toHexString(d));
        assertTrue(NavBlock.isStandable(d),
                "leaves ARE a floor to the search (canopy walking) — descriptor=" + Long.toHexString(d));
    }
}
