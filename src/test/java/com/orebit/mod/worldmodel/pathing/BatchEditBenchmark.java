package com.orebit.mod.worldmodel.pathing;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;

import com.orebit.mod.worldmodel.navblock.NavBlock;

/**
 * Prices the BATCH drain seam {@link NavSectionBuilder#patchCells} — the entrypoint the deferred
 * block-edit queue drains through (PERF-DESIGN-navgrid-edit-batching.md §6). The seam's CURRENT body is
 * the trivial sequential loop over {@code patchCell}, so this benchmark's numbers at this commit ARE the
 * sequential baseline ("A"); the Phase-2 phased drain replaces the seam's internals, and its A/B reruns
 * this same benchmark — algorithm against algorithm, never benchmark against benchmark.
 * {@link PatchStormBenchmark} stays the single-change regression gate; this class measures multi-cell
 * ticks. Metric: JMH reports ns per OP; one op = one whole drained tick (see per-shape cell counts).
 *
 * <p><b>Fixture:</b> the {@link PatchStormBenchmark#buildStrataColumn() strata chunk column} (built the
 * column way, so the depth nibbles are live — the same trap note applies).
 *
 * <p><b>Shapes</b> (all cells verified stone in the fixture, clear of both cave pockets; no per-op
 * allocation — batch arrays are pre-generated and ops alternate direction by cursor parity so every
 * patch actually changes its cell):
 * <ul>
 *   <li>{@code BATCH_PISTON} — 26 clustered same-section cells (a 13×2 pushed row at y 24..25), ONE
 *       batch per op, alternating all-air / all-stone (extend / retract on successive ticks). The §2
 *       double-work shape: 26 scratch fills today where Phase 2 pays ~1. 26 changed cells/op.</li>
 *   <li>{@code BATCH_BLAST} — a 57-cell sphere (r²≤5 around (5,32,11)) spanning the section 1|2 seam:
 *       ly∈{14,15} above it, ly∈{0,1,2} below it, so every op exercises the below-seam double-scratch
 *       pass. Drained as TWO per-section sub-batches (ascending), alternating blast-out / refill — the
 *       TNT shape. 57 changed cells/op.</li>
 *   <li>{@code TOGGLE_PAIR} — a 16-cell row changed TWICE per op (all→air then all→back-to-stone: a
 *       one-tick piston pulse). Net grid change zero, so every op does identical work with no parity
 *       machinery; the sequential baseline pays all 32 patches, Phase-1 dedup collapses the pair — the
 *       defer+dedup headline. 32 patch events/op, 0 net changed cells.</li>
 * </ul>
 * The design doc's fourth shape (REDSTONE, the Phase-0 navtype no-op gate) is deliberately absent: the
 * gate lives in {@code NavGridUpdater.onBlockChanged}, which needs a live {@code ServerLevel} that
 * cannot be stood up under the Knot test classloader — {@link NavGridEpochTest} covers Phase 0's
 * correctness through the package-private {@code changesGrid} seam instead.
 *
 * <p>Run: {@code ./gradlew :<ver>:jmh -Pbench=BatchEditBenchmark}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 6, time = 2)
@Fork(2) // overridden to forks(0) by BenchmarkRunnerTest (must stay in the bootstrapped-MC JVM)
public class BatchEditBenchmark {

    @Param({"BATCH_PISTON", "BATCH_BLAST", "TOGGLE_PAIR"})
    private String scenario;

    private NavSection[] column;

    // One op = one drained tick = subBatch 0..subBatchCount-1 in order, each ONE patchCells call.
    // Ops alternate direction by cursor parity (navtypes[parity][...]); TOGGLE_PAIR is parity-invariant.
    private int subBatchCount;
    private int[] batchSection;        // [subBatch] section index in the column
    private short[][] batchCells;      // [subBatch][i] packed (ly<<8)|(lz<<4)|lx — patchCells' order
    private short[][][] batchNavtypes; // [parity][subBatch][i]
    private int cursor;

    private static boolean bootstrapped = false;

    @Setup(Level.Trial)
    public void setup() {
        if (!bootstrapped) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            bootstrapped = true;
        }

        column = PatchStormBenchmark.buildStrataColumn();
        buildBatches();
    }

    private void buildBatches() {
        final short air = NavBlock.navtypeFor(Blocks.AIR.defaultBlockState());
        final short stone = NavBlock.navtypeFor(Blocks.STONE.defaultBlockState());

        // Collect (sectionIndex, packedCell, parity0Navtype, parity1Navtype) tuples per shape, in fire
        // order, then group into per-section sub-batches preserving that order.
        List<int[]> events = new ArrayList<>(); // {sectionIndex, packedCell, nav0, nav1}
        switch (scenario) {
            case "BATCH_PISTON":
                // A 13-long, 2-high pushed row: x 2..14, y {24,25}, z 10 — 26 cells, all section 1.
                for (int y = 24; y <= 25; y++) {
                    for (int x = 2; x <= 14; x++) {
                        events.add(new int[] { y >> 4, pack(x, y & 15, 10), air, stone });
                    }
                }
                break;
            case "BATCH_BLAST":
                // Lattice sphere dx²+dy²+dz² ≤ 5 around (5,32,11): 57 cells, y 30..34 across the
                // section 1|2 seam (ly 14,15 | 0,1,2).
                for (int y = 30; y <= 34; y++) {
                    for (int x = 3; x <= 7; x++) {
                        for (int z = 9; z <= 13; z++) {
                            int d2 = (x - 5) * (x - 5) + (y - 32) * (y - 32) + (z - 11) * (z - 11);
                            if (d2 <= 5) events.add(new int[] { y >> 4, pack(x, y & 15, z), air, stone });
                        }
                    }
                }
                break;
            case "TOGGLE_PAIR":
                // A 8×2 row at x 2..9, y {22,23}, z 12 — 16 cells, section 1. Each op fires the extend
                // (→air) for every cell, then the retract (→stone): 32 events, net zero, parity-invariant.
                for (int phase = 0; phase < 2; phase++) {
                    short to = phase == 0 ? air : stone;
                    for (int y = 22; y <= 23; y++) {
                        for (int x = 2; x <= 9; x++) {
                            events.add(new int[] { y >> 4, pack(x, y & 15, 12), to, to });
                        }
                    }
                }
                break;
            default:
                throw new IllegalArgumentException("unknown scenario: " + scenario);
        }

        // Group by section, ascending, preserving fire order within each — the drain shape (§4.3).
        List<Integer> sections = new ArrayList<>();
        for (int[] e : events) {
            if (!sections.contains(e[0])) sections.add(e[0]);
        }
        sections.sort(null);
        subBatchCount = sections.size();
        batchSection = new int[subBatchCount];
        batchCells = new short[subBatchCount][];
        batchNavtypes = new short[2][subBatchCount][];
        for (int b = 0; b < subBatchCount; b++) {
            int si = sections.get(b);
            batchSection[b] = si;
            int n = 0;
            for (int[] e : events) if (e[0] == si) n++;
            batchCells[b] = new short[n];
            batchNavtypes[0][b] = new short[n];
            batchNavtypes[1][b] = new short[n];
            int i = 0;
            for (int[] e : events) {
                if (e[0] != si) continue;
                batchCells[b][i] = (short) e[1];
                batchNavtypes[0][b][i] = (short) e[2];
                batchNavtypes[1][b][i] = (short) e[3];
                i++;
            }
        }
        cursor = 0;
    }

    private static int pack(int lx, int ly, int lz) {
        return (ly << 8) | (lz << 4) | lx;
    }

    @Benchmark
    public void drain() {
        final int parity = cursor++ & 1;
        for (int b = 0; b < subBatchCount; b++) {
            final int si = batchSection[b];
            final NavSection above = si + 1 < column.length ? column[si + 1] : null;
            final NavSection below = si > 0 ? column[si - 1] : null;
            NavSectionBuilder.patchCells(column[si], above, below,
                    batchCells[b], batchNavtypes[parity][b], batchCells[b].length);
        }
    }
}
