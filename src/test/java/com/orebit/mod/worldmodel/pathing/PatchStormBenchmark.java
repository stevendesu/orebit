package com.orebit.mod.worldmodel.pathing;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
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
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

/**
 * Prices {@link NavSectionBuilder#patchCell} — the per-block-change nav-grid maintenance that runs on
 * EVERY server-side block change in a tracked chunk (crop growth, pistons, TNT, other players — not just
 * the bot's edits). This is the invalidation-cost gate for the floorGap/runUp depth nibbles
 * (docs/Optimizations/09_depth_nibbles.md): the nibble maintenance adds an upward floorGap sweep (≤15
 * cells, ≤1 seam into {@code above}) and a downward runUp sweep (≤15 cells, ≤1 seam into {@code below})
 * to each patch. Regression bar (from the adoption measurement): nibble maintenance cost ≤ +10% ns/patch
 * on every scenario (measured worst +1.8%), and the absolute ns/patch × a hostile storm rate
 * (~10k changes/s) comfortably sub-millisecond per tick (measured ~1.4–2.1 µs/patch).
 *
 * <p><b>Fixture:</b> one chunk column (6 sections, y 0..95) built the COLUMN way — {@code
 * classifyNavtypes} per section, {@code computeFlags} with vertical overscan, {@code computeDepth} — with
 * realistic strata: bedrock floor, ~40 rows of stone with two air cave pockets, a dirt/grass surface, air
 * above. (A {@code buildFlatChunks}-style single-section {@code classifyInto} fixture would leave the
 * depth nibbles UNKNOWN and bench nothing — the doc's named trap.)
 *
 * <p><b>Op:</b> one {@code patchCell(section, above, below, lx, ly, lz, state)} — exactly {@link
 * NavGridUpdater}'s call shape — over a pre-generated cycle of (cell, state) pairs (no per-op allocation),
 * states alternating stone/air so every patch actually toggles standability (the nibble's worst case).
 *
 * <p><b>Scenarios:</b> {@code SCATTER} (uniform-random cells — cache-miss upper bound), {@code DIG} (a
 * descending shaft dug then refilled — the bot's own applyEdits storm; clustered, seam-crossing every 16
 * cells), {@code TOGGLE} (one mid-stone cell alternating — fixed-overhead isolation, branch-predictor
 * best case), {@code SEAM} ({@code ly ∈ {0,1,2,13,14,15}} — maximum cross-section work: the below-seam
 * flags pass and the above/below depth passes together).
 *
 * <p>Run: {@code ./gradlew :<ver>:jmh -Pbench=PatchStormBenchmark}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 6, time = 2)
@Fork(2) // overridden to forks(0) by BenchmarkRunnerTest (must stay in the bootstrapped-MC JVM)
public class PatchStormBenchmark {

    @Param({"SCATTER", "DIG", "TOGGLE", "SEAM"})
    private String scenario;

    private static final int SECTIONS = 6;    // y 0..95
    private static final int SURFACE_TOP = 44; // grass at y=44; stone 1..40, dirt 41..43, bedrock 0

    private NavSection[] column;

    // The pre-generated op cycle (power-of-two length so the measured op masks, never divides).
    private int[] opSection;
    private int[] opLx;
    private int[] opLy;
    private int[] opLz;
    private BlockState[] opState;
    private int opMask;
    private int cursor;

    private static boolean bootstrapped = false;

    @Setup(Level.Trial)
    public void setup() {
        if (!bootstrapped) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            bootstrapped = true;
        }

        column = buildStrataColumn();
        buildOps();
    }

    /** The strata chunk column, built the column way (navtypes → overscan flags → depth sweep). */
    static NavSection[] buildStrataColumn() {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState bedrock = Blocks.BEDROCK.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState dirt = Blocks.DIRT.defaultBlockState();
        BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();

        NavSection[] sections = new NavSection[SECTIONS];
        boolean[] allAir = new boolean[SECTIONS];
        for (int i = 0; i < SECTIONS; i++) {
            PalettedContainer<BlockState> states = new PalettedContainer<>(
                air, Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
            boolean any = false;
            for (int ly = 0; ly < 16; ly++) {
                int y = i * 16 + ly;
                for (int x = 0; x < 16; x++) {
                    for (int z = 0; z < 16; z++) {
                        BlockState s;
                        if (y == 0) s = bedrock;
                        else if (y <= 40) s = inCave(x, y, z) ? air : stone;
                        else if (y <= 43) s = dirt;
                        else if (y == SURFACE_TOP) s = grass;
                        else s = air;
                        if (s != air) {
                            states.set(x, ly, z, s);
                            any = true;
                        }
                    }
                }
            }
            sections[i] = NavSection.create(BlockPos.ZERO);
            allAir[i] = NavSectionBuilder.classifyNavtypes(states, !any, sections[i].getTraversalGrid(), null);
        }
        for (int i = 0; i < SECTIONS; i++) {
            NavSection above = i + 1 < SECTIONS ? sections[i + 1] : null;
            NavSectionBuilder.computeFlags(sections[i].getTraversalGrid(), allAir[i],
                    (above == null || allAir[i + 1]) ? null : above.getTraversalGrid());
        }
        NavSectionBuilder.computeDepth(sections);
        return sections;
    }

    /** Two deterministic air cave pockets inside the stone body (one crossing the y=16..31 seam). */
    static boolean inCave(int x, int y, int z) {
        return (x >= 3 && x <= 8 && z >= 3 && z <= 8 && y >= 10 && y <= 14)
                || (x >= 9 && x <= 14 && z >= 2 && z <= 7 && y >= 24 && y <= 34);
    }

    private void buildOps() {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState stone = Blocks.STONE.defaultBlockState();

        List<int[]> cells = new ArrayList<>(); // {sectionIndex, lx, ly, lz}
        Random rng = new Random(42);
        switch (scenario) {
            case "SCATTER":
                for (int i = 0; i < 4096; i++) {
                    cells.add(new int[] { rng.nextInt(SECTIONS), rng.nextInt(16), rng.nextInt(16), rng.nextInt(16) });
                }
                break;
            case "DIG":
                // A shaft at (8,8) from the surface to just above bedrock, dug top-down then refilled —
                // repeated to fill the cycle. Alternating air/stone rides the pass parity below.
                for (int pass = 0; pass < 24; pass++) {
                    for (int y = SURFACE_TOP; y >= 1; y--) {
                        cells.add(new int[] { y >> 4, 8, y & 15, 8 });
                    }
                }
                break;
            case "TOGGLE":
                for (int i = 0; i < 1024; i++) {
                    cells.add(new int[] { 1, 8, 6, 8 }); // y=22, mid-stone
                }
                break;
            case "SEAM":
                int[] seamRows = { 0, 1, 2, 13, 14, 15 };
                for (int i = 0; i < 4096; i++) {
                    cells.add(new int[] { rng.nextInt(SECTIONS), rng.nextInt(16),
                            seamRows[rng.nextInt(seamRows.length)], rng.nextInt(16) });
                }
                break;
            default:
                throw new IllegalArgumentException("unknown scenario: " + scenario);
        }

        // Round the cycle down to a power of two so the measured op indexes with a mask.
        int n = Integer.highestOneBit(cells.size());
        opSection = new int[n];
        opLx = new int[n];
        opLy = new int[n];
        opLz = new int[n];
        opState = new BlockState[n];
        for (int i = 0; i < n; i++) {
            int[] c = cells.get(i);
            opSection[i] = c[0];
            opLx[i] = c[1];
            opLy[i] = c[2];
            opLz[i] = c[3];
            // Alternate so every patch toggles standability — the depth maintenance's worst case. DIG
            // alternates per PASS (dig the shaft out, then refill it), everything else per op.
            boolean toStone = "DIG".equals(scenario) ? ((i / 44) % 2 == 0) : (i % 2 == 0);
            opState[i] = toStone ? stone : air;
        }
        opMask = n - 1;
        cursor = 0;
    }

    @Benchmark
    public void patch() {
        int i = cursor++ & opMask;
        int si = opSection[i];
        NavSection above = si + 1 < SECTIONS ? column[si + 1] : null;
        NavSection below = si > 0 ? column[si - 1] : null;
        NavSectionBuilder.patchCell(column[si], above, below, opLx[i], opLy[i], opLz[i], opState[i]);
    }
}
