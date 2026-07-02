package com.orebit.mod.pathfinding.blockpathfinder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.orebit.mod.worldmodel.navblock.NavBlock;
import com.orebit.mod.worldmodel.pathing.NavFlags;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Unit pins for the pass-through cost vocabulary: the {@link NavBlock} <b>transit</b> descriptor field
 * (bits 41–42: cobweb HEAVY, sweet berry bush / powder snow LIGHT), the new powder-snow <b>damaging</b>
 * registration (freezing), the {@link NavFlags#SLOW_TRANSIT} prefilter bit, and the caps-honest per-cell
 * pricing in {@link MovementContext#cellTransitCost}: the DAMAGE term is gated on
 * {@link BotCaps#takesDamage} (an invulnerable bot pays nothing), while the through-slow terms charge
 * every bot (physics slows an immune bot just the same). Pure descriptor/bit tests — no search, no grid
 * (the context is built grid-less; {@code cellTransitCost} reads only caps + the descriptor).
 */
class TransitCostVocabularyTest {

    private static boolean bootstrapped;

    @BeforeAll
    static void boot() {
        if (!bootstrapped) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            bootstrapped = true;
        }
    }

    private static final BotCaps MORTAL = new BotCaps(
            1, BotCaps.DEFAULT_SAFE_FALL, BotCaps.DEFAULT_MAX_FALL, true, false, false,
            BotCaps.UNBREAKABLE, BotCaps.DEFAULT_MAX_NODES, 1.0f);
    private static final BotCaps IMMUNE = new BotCaps(
            1, BotCaps.DEFAULT_SAFE_FALL, BotCaps.DEFAULT_MAX_FALL, false, false, false,
            BotCaps.UNBREAKABLE, BotCaps.DEFAULT_MAX_NODES, 1.0f);

    private static long desc(Block block) {
        return NavBlock.descriptorFor(block.defaultBlockState());
    }

    @Test
    void transitClassesAreClassifiedPerBlock() {
        assertEquals(NavBlock.TRANSIT_HEAVY, NavBlock.transitSlow(desc(Blocks.COBWEB)),
                "cobweb is the HEAVY through-slow (~0.05× speed)");
        assertEquals(NavBlock.TRANSIT_LIGHT, NavBlock.transitSlow(desc(Blocks.SWEET_BERRY_BUSH)),
                "sweet berry bush is a LIGHT through-slow (~0.75× speed)");
        assertEquals(NavBlock.TRANSIT_LIGHT, NavBlock.transitSlow(desc(Blocks.POWDER_SNOW)),
                "powder snow is a LIGHT through-slow (~0.75× speed)");
        assertEquals(NavBlock.TRANSIT_NONE, NavBlock.transitSlow(desc(Blocks.AIR)));
        assertEquals(NavBlock.TRANSIT_NONE, NavBlock.transitSlow(desc(Blocks.STONE)),
                "a solid wall is not a THROUGH-slow (it is priced by its break ticks)");
    }

    @Test
    void powderSnowIsDamagingAndPassableButNeverStandable() {
        long powderSnow = desc(Blocks.POWDER_SNOW);
        assertTrue(NavBlock.isDamaging(powderSnow), "powder snow freezes — the new damaging registration");
        assertTrue(NavBlock.isPassable(powderSnow), "powder snow has an empty collision shape (a body sinks in)");
        assertFalse(NavBlock.isStandable(powderSnow), "no standability change — it was never a floor");
    }

    @Test
    void cellTransitCostGatesDamageOnCapsButSlowsEveryBot() {
        MovementContext mortal = new MovementContext(null, MORTAL);
        MovementContext immune = new MovementContext(null, IMMUNE);

        // Fire: damaging + passable, no through-slow — the pure caps-gated damage case.
        long fire = desc(Blocks.FIRE);
        assertEquals(MovementContext.DAMAGE_TRANSIT_COST, mortal.cellTransitCost(fire), 1e-4f,
                "a mortal bot pays the damage surcharge per fire cell transited");
        assertEquals(0f, immune.cellTransitCost(fire), 1e-4f,
                "an invulnerable bot pays NOTHING for a damaging body cell (caps-honest, like Fall)");

        // Cobweb: heavy through-slow, not damaging — charged to EVERY bot.
        long cobweb = desc(Blocks.COBWEB);
        assertEquals(MovementContext.WEB_TRANSIT_COST, mortal.cellTransitCost(cobweb), 1e-3f);
        assertEquals(MovementContext.WEB_TRANSIT_COST, immune.cellTransitCost(cobweb), 1e-3f,
                "the web slow term is physics, not damage — immunity does not waive it");

        // Berry bush: LIGHT through-slow for everyone, plus the damage term only for a mortal bot.
        long bush = desc(Blocks.SWEET_BERRY_BUSH);
        assertEquals(MovementContext.DAMAGE_TRANSIT_COST + MovementContext.LIGHT_TRANSIT_COST,
                mortal.cellTransitCost(bush), 1e-3f);
        assertEquals(MovementContext.LIGHT_TRANSIT_COST, immune.cellTransitCost(bush), 1e-3f);

        // Cactus: damaging but SOLID — priced by its break ticks, never by the transit surcharge.
        long cactus = desc(Blocks.CACTUS);
        assertEquals(0f, mortal.cellTransitCost(cactus), 1e-4f,
                "a solid damaging block is not a pass-through cell — no transit charge");

        // Plain clear cells cost nothing for anyone.
        assertEquals(0f, mortal.cellTransitCost(desc(Blocks.AIR)), 1e-4f);
        assertEquals(0f, immune.cellTransitCost(desc(Blocks.AIR)), 1e-4f);
    }

    @Test
    void slowTransitPrefilterBitFiresForBodyCells() {
        long air = NavBlock.descriptor(NavBlock.AIR);
        long stone = desc(Blocks.STONE);

        // A section's 4096 descriptors in canonical (y<<8)|(z<<4)|x order: stone floor at (8,0,8), air body.
        long[] cells = new long[4096];
        Arrays.fill(cells, air);
        cells[(0 << 8) | (8 << 4) | 8] = stone;

        int clean = NavFlags.compute(cells, 8, 0, 8);
        assertFalse(NavFlags.slowTransit(clean), "clear body space — no SLOW_TRANSIT");
        assertFalse(NavFlags.clearableHazard(clean));

        cells[(1 << 8) | (8 << 4) | 8] = desc(Blocks.COBWEB); // web at the feet cell
        int webbed = NavFlags.compute(cells, 8, 0, 8);
        assertTrue(NavFlags.slowTransit(webbed), "a cobweb in the body space must set SLOW_TRANSIT");
        assertFalse(NavFlags.clearableHazard(webbed), "a web is slow, not damaging");

        cells[(1 << 8) | (8 << 4) | 8] = desc(Blocks.POWDER_SNOW); // powder snow at the feet cell
        int snowed = NavFlags.compute(cells, 8, 0, 8);
        assertTrue(NavFlags.slowTransit(snowed), "powder snow is a through-slow body cell");
        assertTrue(NavFlags.clearableHazard(snowed), "powder snow also damages (freezing) — hazard prefilter");

        cells[(1 << 8) | (8 << 4) | 8] = desc(Blocks.FIRE); // fire at the feet cell
        int burning = NavFlags.compute(cells, 8, 0, 8);
        assertTrue(NavFlags.clearableHazard(burning), "fire in the body space must set CLEARABLE_HAZARD");
        assertFalse(NavFlags.slowTransit(burning), "fire damages but does not slow");
    }
}
