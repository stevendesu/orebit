package com.orebit.mod.worldmodel.pathing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.orebit.mod.Debug;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.ReadCensus;
import com.orebit.mod.pathfinding.blockpathfinder.RegionBound;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

/**
 * Self-verification for {@link ReadCensus} — a counter that silently records nothing is worse than no
 * counter at all, and the whole read-reduction arc is about to be prioritised off its numbers.
 *
 * <p>Two arms, selected by whether the instrument is armed, so BOTH the shipped configuration and the
 * measurement configuration are covered by the same suite:
 *
 * <ul>
 *   <li><b>Disabled (the normal suite):</b> a real search records nothing. This is the guard that the
 *       {@code static final} gate genuinely erases the hooks rather than merely skipping their bodies —
 *       if someone converts {@link ReadCensus#ENABLED} to a mutable {@code static boolean} for
 *       convenience, this arm keeps passing but the perf claim behind it quietly dies, so the assertion is
 *       paired with the javadoc contract rather than standing alone.
 *   <li><b>Armed ({@code -Dorebit.readcensus=true}):</b> a real search records pops, records strictly more
 *       read calls than distinct cells (i.e. the repeat tax is being observed at all), and renders a
 *       complete report to disk. The report path is otherwise cold run-once code that nothing else
 *       exercises, and it is caught-and-swallowed at runtime by design, so without this it could fail
 *       silently in the field.
 * </ul>
 *
 * <p>Lives in this package for {@link NavGridView}'s package-private synthetic constructor.
 */
class ReadCensusTest {

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

    private static final RegionBound CORRIDOR = new RegionBound(0, 15, 0, 31, 0, 15);
    private static final BlockPos START = new BlockPos(2, 0, 2);
    private static final BlockPos GOAL = new BlockPos(13, 0, 13);

    @Test
    void inertWhenDisabled() {
        Assumptions.assumeFalse(ReadCensus.ENABLED, "armed run — see countsAndReportsWhenArmed");
        ReadCensus.reset();
        assertNotNull(runSearch(), "the fixture itself must produce a path");
        assertEquals(0L, ReadCensus.pops(),
                "census must record nothing when the static final gate is off");
    }

    @Test
    void countsAndReportsWhenArmed(@TempDir java.io.File tmp) throws Exception {
        Assumptions.assumeTrue(ReadCensus.ENABLED,
                "not armed — run with -Dorebit.readcensus=true to exercise the census");
        ReadCensus.reset();
        assertNotNull(runSearch(), "the fixture itself must produce a path");

        long pops = ReadCensus.pops();
        assertTrue(pops > 0, "an open-floor search must record pops, got " + pops);

        java.io.File out = new java.io.File(tmp, "orebit-read-census.txt");
        String summary = ReadCensus.dump(out);
        assertTrue(!summary.contains("FAILED"), "dump reported a failure: " + summary);
        assertTrue(out.isFile() && out.length() > 0, "report file not written");

        String report = new String(java.nio.file.Files.readAllBytes(out.toPath()),
                java.nio.charset.StandardCharsets.UTF_8);

        // Every section the design decisions will be read off must actually render.
        for (String section : new String[] {
                "## Repeat tax", "## Reads per pop", "## Distinct cells per pop", "## By accessor",
                "## Path-edit layer", "## By movement", "## Prefetch-envelope scoring",
                "## Per-offset reads" }) {
            assertTrue(report.contains(section), "report is missing section: " + section);
        }

        // The instrument's core claim: reads are attributed to offsets around the popped cell, and the same
        // cell is read more than once per pop. If amplification came out at exactly 1.00x on an open-floor
        // search the offset bookkeeping would be broken (every read landing in its own bucket).
        assertTrue(report.contains("amplification"), "amplification line missing");
        assertTrue(reportedAmplification(report) > 1.0,
                "expected repeat reads on an open-floor search; report says " + reportedAmplification(report));

        // The popped cell itself is the most-contended offset in the inventory; it must appear at 0,0,0.
        assertTrue(report.contains("     0    0    0 "), "per-offset table is missing the popped cell");
    }

    private static double reportedAmplification(String report) {
        for (String line : report.split("\n")) {
            if (line.startsWith("amplification")) {
                int c = line.indexOf(':');
                String v = line.substring(c + 1).trim();
                int x = v.indexOf('x');
                return Double.parseDouble(x < 0 ? v : v.substring(0, x));
            }
        }
        return -1;
    }

    /** A flat stone floor with three air rows above it — an open-ground search that pops a lot of nodes. */
    private static BlockPathPlan runSearch() {
        return BlockPathfinder.findPath(openFloor(), START, GOAL, BotCaps.DEFAULT, CORRIDOR);
    }

    private static NavGridView openFloor() {
        PalettedContainer<BlockState> floor = new PalettedContainer<>(
                Blocks.STONE.defaultBlockState(), Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        BlockState air = Blocks.AIR.defaultBlockState();
        for (int y = 1; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) floor.set(x, y, z, air);
            }
        }
        NavSection ground = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(floor, false, ground.getTraversalGrid());

        PalettedContainer<BlockState> allAir = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
        NavSection sky = NavSection.create(BlockPos.ZERO);
        NavSectionBuilder.classifyInto(allAir, true, sky.getTraversalGrid());

        ConcurrentHashMap<Long, NavSection[]> chunks = new ConcurrentHashMap<>();
        chunks.put(NavStore.key(0, 0), new NavSection[] { ground, sky });
        return new NavGridView(0, chunks);
    }
}
