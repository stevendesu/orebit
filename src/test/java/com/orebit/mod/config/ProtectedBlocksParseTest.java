package com.orebit.mod.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;

/**
 * Unit pins for the {@code mining.protectedBlocks} / {@code mining.allowUnbreakable} config surface:
 * {@link ProtectedBlocks#parse} (ids, {@code #}-tags, per-entry warn-and-skip), the {@link
 * ConfigValidator} wiring, and the execution-side {@link Config#mayBreak} policy gate (protected always
 * wins; vanilla-unbreakable needs the opt-in).
 *
 * <p><b>Tag caveat:</b> headless {@code Bootstrap.bootStrap()} registers blocks but does NOT bind
 * datapack tags, so a {@code #tag} entry can only be asserted to PARSE cleanly and match nothing here —
 * positive tag membership is exercised on a live server (tags are bound by the time
 * {@code ConfigLoader.install} applies the list at server-started).
 */
class ProtectedBlocksParseTest {

    private static boolean bootstrapped;

    @BeforeAll
    static void boot() {
        if (!bootstrapped) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            bootstrapped = true;
        }
    }

    @Test
    void emptyAndBlankParseToEmpty() {
        List<String> warnings = new ArrayList<>();
        assertTrue(ProtectedBlocks.parse(null, warnings::add).isEmpty());
        assertTrue(ProtectedBlocks.parse("", warnings::add).isEmpty());
        assertTrue(ProtectedBlocks.parse("  , , ", warnings::add).isEmpty());
        assertTrue(warnings.isEmpty(), "nothing to warn about: " + warnings);
        assertFalse(ProtectedBlocks.EMPTY.matches(Blocks.STONE.defaultBlockState()));
    }

    @Test
    void exactBlockIdsMatchExactly() {
        List<String> warnings = new ArrayList<>();
        ProtectedBlocks p = ProtectedBlocks.parse(
                "minecraft:chest, minecraft:diamond_ore", warnings::add);

        assertTrue(warnings.isEmpty(), "well-formed list must not warn: " + warnings);
        assertFalse(p.isEmpty());
        assertTrue(p.matches(Blocks.CHEST.defaultBlockState()));
        assertTrue(p.matches(Blocks.DIAMOND_ORE.defaultBlockState()));
        assertFalse(p.matches(Blocks.STONE.defaultBlockState()),
                "ids are exact — stone is not on the list");
        assertFalse(p.matches(Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState()),
                "ids are exact, not fuzzy — deepslate diamond ore is a different block");
        assertEquals("minecraft:chest,minecraft:diamond_ore", p.spec());
    }

    @Test
    void malformedEntriesWarnAndAreSkippedIndividually() {
        List<String> warnings = new ArrayList<>();
        ProtectedBlocks p = ProtectedBlocks.parse(
                "minecraft:chest, minecraft:definitely_not_a_block, #not a valid id!, minecraft:tnt",
                warnings::add);

        assertEquals(2, warnings.size(), "one warning per bad entry: " + warnings);
        assertTrue(p.matches(Blocks.CHEST.defaultBlockState()), "good entries before a bad one survive");
        assertTrue(p.matches(Blocks.TNT.defaultBlockState()), "good entries after a bad one survive");
        assertFalse(p.spec().contains("definitely_not_a_block"), "skipped entries leave the spec");
    }

    @Test
    void tagEntriesParseCleanlyEvenThoughHeadlessTagsAreUnbound() {
        List<String> warnings = new ArrayList<>();
        ProtectedBlocks p = ProtectedBlocks.parse("#minecraft:beds, minecraft:chest", warnings::add);

        assertTrue(warnings.isEmpty(), "a well-formed #tag id parses without warning: " + warnings);
        assertTrue(p.matches(Blocks.CHEST.defaultBlockState()));
        // Headless: tags unbound, so the tag entry matches nothing (and must not throw).
        assertFalse(p.matches(Blocks.STONE.defaultBlockState()));
    }

    @Test
    void validatorThreadsBothKeysIntoConfig() {
        Properties props = new Properties();
        props.setProperty(ConfigKeys.MINING_PROTECTED_BLOCKS, "minecraft:chest");
        props.setProperty(ConfigKeys.MINING_ALLOW_UNBREAKABLE, "true");

        List<String> warnings = new ArrayList<>();
        Config c = new ConfigValidator(warnings::add).validate(props);

        assertTrue(warnings.isEmpty(), "valid values must not warn: " + warnings);
        assertTrue(c.protectedBlocks().matches(Blocks.CHEST.defaultBlockState()));
        assertTrue(c.allowUnbreakable());
        assertTrue(c.toBotCaps().allowUnbreakable(), "allowUnbreakable rides into BotCaps");
        // Defaults stay byte-identical: absent keys → nothing protected, opt-in off.
        Config d = new ConfigValidator(warnings::add).validate(new Properties());
        assertTrue(d.protectedBlocks().isEmpty());
        assertFalse(d.allowUnbreakable());
        assertFalse(d.toBotCaps().allowUnbreakable());
    }

    @Test
    void mayBreakIsTheExecutionSidePolicyGate() {
        Properties props = new Properties();
        props.setProperty(ConfigKeys.MINING_PROTECTED_BLOCKS, "minecraft:chest, minecraft:bedrock");
        Config noOptIn = new ConfigValidator(msg -> {}).validate(props);
        props.setProperty(ConfigKeys.MINING_ALLOW_UNBREAKABLE, "true");
        Config optIn = new ConfigValidator(msg -> {}).validate(props);

        float breakableTime = 2.5f;    // any ordinary block (chest ≈ 2.5)
        float unbreakableTime = -1.0f; // vanilla-unbreakable sentinel (bedrock)

        // Protected always refuses — breakable or not, opted in or not.
        assertFalse(noOptIn.mayBreak(Blocks.CHEST.defaultBlockState(), breakableTime));
        assertFalse(optIn.mayBreak(Blocks.CHEST.defaultBlockState(), breakableTime));
        assertFalse(optIn.mayBreak(Blocks.BEDROCK.defaultBlockState(), unbreakableTime),
                "mining.protectedBlocks OVERRIDES mining.allowUnbreakable");

        // Vanilla-unbreakable needs the opt-in.
        assertFalse(noOptIn.mayBreak(Blocks.BARRIER.defaultBlockState(), unbreakableTime));
        assertTrue(optIn.mayBreak(Blocks.BARRIER.defaultBlockState(), unbreakableTime),
                "an unprotected unbreakable block is breakable once opted in");

        // Ordinary unprotected blocks are always allowed (the planner prices them; this gate is policy).
        assertTrue(noOptIn.mayBreak(Blocks.STONE.defaultBlockState(), 1.5f));

        // The all-defaults config refuses only the unbreakable class.
        assertTrue(Config.DEFAULT.mayBreak(Blocks.STONE.defaultBlockState(), 1.5f));
        assertFalse(Config.DEFAULT.mayBreak(Blocks.BEDROCK.defaultBlockState(), unbreakableTime));
    }
}
