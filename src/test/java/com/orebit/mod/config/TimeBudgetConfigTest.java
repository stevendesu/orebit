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

/**
 * Unit pins for the time-based drain-budget config surface: the two new wall-clock budget keys
 * ({@code pathing.chunkBuildBudgetMs}, {@code pathing.hpaFlushBudgetMs}) and the repurposed count backstop
 * ({@code pathing.chunkBuildsPerTick}, default raised 8 → 64). Asserts defaults, clean parse, and the
 * clamp-and-warn behaviour of the new {@code floatClamped} helper (positive float, 25 ms ceiling).
 *
 * <p>Bootstraps like {@link ProtectedBlocksParseTest} because {@link Config#DEFAULT} references
 * {@code Blocks.COBBLESTONE}, whose class-init needs the vanilla registries bound.
 */
class TimeBudgetConfigTest {

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
    void defaultsAreTheNewTimeBudgetValues() {
        assertEquals(2.0f, Config.DEFAULT.chunkBuildBudgetMs(), "primary chunk-build budget default 2.0 ms");
        assertEquals(1.0f, Config.DEFAULT.hpaFlushBudgetMs(), "HPA flush budget default 1.0 ms");
        assertEquals(64, Config.DEFAULT.chunkBuildsPerTick(), "count backstop default raised to 64");
    }

    @Test
    void absentKeysTakeTheDefaults() {
        List<String> warnings = new ArrayList<>();
        Properties props = protectedBlocksOff();
        Config c = new ConfigValidator(warnings::add).validate(props);
        assertTrue(warnings.isEmpty(), "absent time-budget keys must not warn: " + warnings);
        assertEquals(2.0f, c.chunkBuildBudgetMs());
        assertEquals(1.0f, c.hpaFlushBudgetMs());
        assertEquals(64, c.chunkBuildsPerTick());
    }

    @Test
    void validValuesParseCleanly() {
        Properties props = protectedBlocksOff();
        props.setProperty(ConfigKeys.PATHING_CHUNK_BUILD_BUDGET_MS, "3.5");
        props.setProperty(ConfigKeys.PATHING_HPA_FLUSH_BUDGET_MS, "0.5");
        props.setProperty(ConfigKeys.PATHING_CHUNK_BUILDS_PER_TICK, "128");

        List<String> warnings = new ArrayList<>();
        Config c = new ConfigValidator(warnings::add).validate(props);

        assertTrue(warnings.isEmpty(), "valid values must not warn: " + warnings);
        assertEquals(3.5f, c.chunkBuildBudgetMs());
        assertEquals(0.5f, c.hpaFlushBudgetMs());
        assertEquals(128, c.chunkBuildsPerTick());
    }

    @Test
    void budgetsClampToPositiveAndCeiling() {
        // zero / negative → clamp up to the 0.1 ms floor; above 1000 → clamp down to the 1 s ceiling.
        Properties props = protectedBlocksOff();
        props.setProperty(ConfigKeys.PATHING_CHUNK_BUILD_BUDGET_MS, "0");
        props.setProperty(ConfigKeys.PATHING_HPA_FLUSH_BUDGET_MS, "5000");

        List<String> warnings = new ArrayList<>();
        Config c = new ConfigValidator(warnings::add).validate(props);

        assertEquals(0.1f, c.chunkBuildBudgetMs(), "0 ms clamps up to the positive floor");
        assertEquals(1000.0f, c.hpaFlushBudgetMs(), "5000 ms clamps down to the 1 s ceiling");
        assertEquals(2, warnings.size(), "one clamp warning per out-of-range key: " + warnings);
    }

    @Test
    void highBudgetForAutotestDeterminismIsAcceptedNotClamped() {
        // The autotest pins high, never-binding budgets (100 ms) so the deterministic count backstop governs.
        // 100 ms must pass through un-clamped (it is well under the 1000 ms ceiling).
        Properties props = protectedBlocksOff();
        props.setProperty(ConfigKeys.PATHING_CHUNK_BUILD_BUDGET_MS, "100");
        props.setProperty(ConfigKeys.PATHING_HPA_FLUSH_BUDGET_MS, "100");
        props.setProperty(ConfigKeys.PATHING_CHUNK_BUILDS_PER_TICK, "8");

        List<String> warnings = new ArrayList<>();
        Config c = new ConfigValidator(warnings::add).validate(props);

        assertTrue(warnings.isEmpty(), "the autotest determinism pin must not warn/clamp: " + warnings);
        assertEquals(100.0f, c.chunkBuildBudgetMs());
        assertEquals(100.0f, c.hpaFlushBudgetMs());
        assertEquals(8, c.chunkBuildsPerTick());
    }

    @Test
    void nonNumberBudgetFallsBackToDefaultWithWarning() {
        Properties props = protectedBlocksOff();
        props.setProperty(ConfigKeys.PATHING_CHUNK_BUILD_BUDGET_MS, "fast");

        List<String> warnings = new ArrayList<>();
        Config c = new ConfigValidator(warnings::add).validate(props);

        assertEquals(2.0f, c.chunkBuildBudgetMs(), "a non-number falls back to the default");
        assertFalse(warnings.isEmpty(), "a non-number value warns");
    }

    /**
     * A {@link Properties} with {@code mining.protectedBlocks} pinned EMPTY, so these time-budget tests are
     * isolated from the broad built-in default protection set an absent key would otherwise parse (which can
     * legitimately warn about blocks absent on older MC versions — irrelevant to the keys under test here).
     */
    private static Properties protectedBlocksOff() {
        Properties props = new Properties();
        props.setProperty(ConfigKeys.MINING_PROTECTED_BLOCKS, "");
        return props;
    }
}
