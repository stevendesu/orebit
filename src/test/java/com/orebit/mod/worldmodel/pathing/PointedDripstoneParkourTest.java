package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.orebit.mod.worldmodel.navblock.NavBlock;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;

/**
 * The <b>pointed-dripstone parkour wedge</b> — the flagship's terminal stall, convicted 2026-08-02.
 *
 * <h2>The witnessed failure</h2>
 * The flagship bot travelled 331 blocks and then parked permanently on a validity-envelope hold:
 * <pre>
 * step FAILED (validity envelope) Parkour step 12 phase 2/4 bot=(110,48,158) — holding
 *   envelope: from-floor=(108,48,158) to-floor=(111,47,158) expectTakeoffFoot.y=49
 *             botFoot=(110,48,158) botY=48.000 grounded=true takeoffTopY=16 toFloorTopY=16
 * </pre>
 * A falling 3-gap parkour ({@code Δx=+3, Δy=-1, Δz=0}), both floors full cubes. The live world along
 * {@code z=158} (read out of the run's own world, so it includes the bot's placed takeoff block):
 * <pre>
 *   y=49:  108 air            109 air                110 air   111 air                &lt;- flight height: CLEAR
 *   y=48:  108 cobblestone*   109 pointed_dripstone  110 air   111 pointed_dripstone  &lt;- FEET level
 *   y=47:  108 dripstone_blk  109 dripstone_blk      110 dripstone_blk  111 dripstone_blk
 *   (* bot-placed conjured block — its own takeoff)
 * </pre>
 * The landing FLOOR {@code (111,47,158)} is a legitimate full cube, and the planner read it as one
 * ({@code toFloorTopY=16}). But the cell the bot's FEET must occupy on landing, {@code (111,48,158)}, holds a
 * <b>stalagmite</b>. The bot undershot by exactly one cell and grounded at {@code (110,48,158)} — inside the
 * gap, on the dripstone floor below — which is neither its takeoff stand nor its landing column, so the
 * envelope correctly fail→HELD.
 *
 * <h2>What this test pins</h2>
 * Whether {@link NavBlock} models pointed dripstone the way vanilla actually behaves. In vanilla a stalagmite
 * has a real collision shape — you stand on it, you cannot walk through it, and falling onto it hurts. If the
 * descriptor reports it {@link NavBlock#isPassable passable}, then every planner predicate that asks "is the
 * landing feet cell clear" answers yes for a cell the bot physically cannot enter, and a parkour aimed there
 * is unrealizable by construction — the planner would be routing an impossible jump rather than the servo
 * failing to land a possible one.
 *
 * <p>{@link MovementContext#landable} already refuses to LAND ON a dripstone tip
 * ({@link NavBlock#isNarrowTop}, owner ruling 2026-07-31). That guard is about the floor; this is about the
 * occupant of the feet cell above the floor, which is a different question and the one the flagship hit.
 */
public class PointedDripstoneParkourTest {

    /**
     * The descriptor-level facts. Deliberately assertion-light on purpose: each line states what vanilla does,
     * so a mismatch localises the model error precisely rather than just going red.
     */
    @Test
    void pointedDripstoneIsNotAnEmptyCell() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        long tip = NavBlock.descriptorFor(Blocks.POINTED_DRIPSTONE.defaultBlockState());

        assertFalse(NavBlock.isPassable(tip),
                "a stalagmite is NOT an empty cell — vanilla gives pointed dripstone a real collision shape "
                        + "(you stand on it, you cannot walk through it). If this reads passable, every "
                        + "'is the landing feet cell clear' predicate says yes for a cell the bot cannot "
                        + "enter, and Parkour will keep aiming at stalagmite-occupied cells — the measured "
                        + "flagship wedge at (111,48,158), which undershot to (110,48,158) and fail->HELD");

        assertTrue(NavBlock.isNarrowTop(tip),
                "…and it stays a NARROW top, so MovementContext.landable still refuses to land a jump ON a "
                        + "tip (owner ruling 2026-07-31). That guard is about the FLOOR; the passability of "
                        + "the feet cell above a floor is the separate question this test exists for");
    }

    /** The landing floor itself is unremarkable — the full dripstone_block cube the planner correctly read. */
    @Test
    void theDripstoneBlockFloorIsAnHonestFullCube() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        long floor = NavBlock.descriptorFor(Blocks.DRIPSTONE_BLOCK.defaultBlockState());

        assertTrue(NavBlock.isStandable(floor), "dripstone_block is a plain solid floor");
        assertFalse(NavBlock.isNarrowTop(floor), "…a full cube, not a narrow post — the planner's "
                + "toFloorTopY=16 reading of (111,47,158) was correct. The floor was never the problem");
    }
}
