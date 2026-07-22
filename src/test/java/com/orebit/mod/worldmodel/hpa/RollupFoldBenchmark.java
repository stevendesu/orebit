package com.orebit.mod.worldmodel.hpa;

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

import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;

/**
 * Cost envelope for the Phase-2b record-time roll-up fold ({@link InvalidationRollup#foldFrom}) — the
 * owner's "a rare event can be safely recomputed, but benchmark to be safe" check. The fold runs ONLY on
 * the BLOCKED record path (a few per goal), so this pins the per-event cost, not a hot path.
 *
 * <p>PURE-ARRAY benchmark — no Minecraft, no {@code NavGridView}: hand-seeded {@link CostPyramid} +
 * {@link RegionCrossingMemory} fixtures (the {@code RollupFoldTest} shapes). The measured op re-runs the
 * complete fold — containment union-finds, constituent enumeration, memory dead-checks, and the (deduped)
 * ROLLED_UP re-record — steady-state, since {@code record}'s antichain skip makes repeat folds idempotent.
 *
 * <p>Scenarios:
 * <ul>
 *   <li><b>SPARSE</b> — the common case: few-fragment children, a 2-constituent kill-set, one level.</li>
 *   <li><b>DENSE</b> — the worst case: 12-fragment MIXED children on every slot (96 items per side's
 *       union-find, 4×12×12 = 576 constituent pairs, each linearly dead-checked against a 576-row
 *       level store).</li>
 *   <li><b>DEEP</b> — the recursion: a 63|64-boundary chain crossing folding L0→L6 (six containment
 *       derivations + records in one call).</li>
 * </ul>
 *
 * <p>Run (JDK 21, active 1.21.x node): {@code ./gradlew :1.21.11:jmh -Pbench=RollupFoldBenchmark}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 6, time = 1)
@Fork(2) // overridden to forks(0) by BenchmarkRunnerTest (runs embedded in the Knot JVM)
public class RollupFoldBenchmark {

    private static final int G = 16;
    private static final int DENSE_FRAGS = 12;

    @Param({"SPARSE", "DENSE", "DEEP"})
    private String scenario;

    private CostPyramid pyramid;
    private RegionCrossingMemory mem;
    private long triggerFrom;
    private long triggerTo;
    private long sig;

    @Setup(Level.Trial)
    public void setup() {
        sig = new BotCaps(1, 3, 16, true, 100.0f, false, false, 255, false, 10000, 2.0f).realizabilitySig();
        pyramid = new CostPyramid();
        mem = new RegionCrossingMemory();
        switch (scenario) {
            case "SPARSE" -> setupSparse();
            case "DENSE" -> setupDense();
            case "DEEP" -> setupDeep();
            default -> throw new IllegalArgumentException("unknown scenario: " + scenario);
        }
        // Sanity: the fixture's kill-set must actually complete (a 0 here is a broken fixture, not a result).
        int expected = scenario.equals("DEEP") ? RegionAddress.MAX_COARSE_LEVEL : 1;
        int got = InvalidationRollup.foldFrom(pyramid, mem, 0, triggerFrom, triggerTo, sig, BotCaps::sigDominates);
        if (got != expected) {
            throw new IllegalStateException(scenario + " fixture folds " + got + " levels, expected " + expected);
        }
    }

    @Benchmark
    public int fold() {
        return InvalidationRollup.foldFrom(pyramid, mem, 0, triggerFrom, triggerTo, sig, BotCaps::sigDominates);
    }

    // ---------------------------------------------------------------------------------------------------
    // Fixtures (the RollupFoldTest shapes, inlined — benchmarks and tests stay independent files)
    // ---------------------------------------------------------------------------------------------------

    /** L1 parents (0,0,0)|(1,0,0), two open child crossings, everything else SOLID; both proofs recorded. */
    private void setupSparse() {
        seedOpen(0, 1, 0, 0, (1 << 1) | (1 << 3));
        seedOpen(0, 1, 1, 0, (1 << 1) | (1 << 2));
        seedOpen(0, 2, 0, 0, (1 << 0) | (1 << 3));
        seedOpen(0, 2, 1, 0, (1 << 0) | (1 << 2));
        for (int[] c : new int[][] { { 0, 0, 0 }, { 0, 1, 0 }, { 0, 0, 1 }, { 0, 1, 1 }, { 1, 0, 1 },
                                     { 1, 1, 1 }, { 3, 0, 0 }, { 3, 1, 0 }, { 3, 0, 1 }, { 3, 1, 1 },
                                     { 2, 0, 1 }, { 2, 1, 1 } }) {
            seedSolid(0, c[0], c[1], c[2]);
        }
        mergeAllLeaves();
        record(0, key(1, 0, 0, 0), key(2, 0, 0, 0));
        record(0, key(1, 1, 0, 0), key(2, 1, 0, 0));
        triggerFrom = key(1, 1, 0, 0);
        triggerTo = key(2, 1, 0, 0);
    }

    /**
     * Every child of both L1 parents is a {@value #DENSE_FRAGS}-fragment MIXED region whose fragments all
     * touch all six faces full-face (so each side's union-find works a 96-item universe that collapses to
     * one parent fragment), and every one of the 4×{@value #DENSE_FRAGS}×{@value #DENSE_FRAGS} constituent
     * pairs is pre-recorded dead.
     */
    private void setupDense() {
        for (int x = 0; x <= 3; x++) {
            for (int y = 0; y <= 1; y++) {
                for (int z = 0; z <= 1; z++) {
                    seedDense(x, y, z);
                }
            }
        }
        mergeAllLeaves();
        for (int y = 0; y <= 1; y++) {
            for (int z = 0; z <= 1; z++) {
                for (int fa = 0; fa < DENSE_FRAGS; fa++) {
                    for (int fb = 0; fb < DENSE_FRAGS; fb++) {
                        record(0, key(1, y, z, fa), key(2, y, z, fb));
                    }
                }
            }
        }
        triggerFrom = key(1, 0, 0, 0);
        triggerTo = key(2, 0, 0, 0);
    }

    /** The 63|64-boundary chain: distinct parent pairs at every level, SOLID flush siblings, L0→L6 fold. */
    private void setupDeep() {
        seedOpen(0, 63, 0, 0, 1 << 1);
        seedOpen(0, 64, 0, 0, 1 << 0);
        int[][] sibs = { { 1, 0 }, { 0, 1 }, { 1, 1 } };
        for (int[] s : sibs) {
            seedSolid(0, 63, s[0], s[1]);
            seedSolid(0, 64, s[0], s[1]);
        }
        mergeAllLeaves();
        for (int L = 1; L <= 4; L++) {
            int xa = (1 << (6 - L)) - 1;
            int xb = 1 << (6 - L);
            for (int[] s : sibs) {
                seedSolid(L, xa, s[0], s[1]);
                seedSolid(L, xb, s[0], s[1]);
            }
        }
        seedSolid(5, 1, 0, 1);
        seedSolid(5, 2, 0, 1);
        record(0, key(63, 0, 0, 0), key(64, 0, 0, 0));
        triggerFrom = key(63, 0, 0, 0);
        triggerTo = key(64, 0, 0, 0);
    }

    // ---- seeding helpers ------------------------------------------------------------------------------

    private static long key(int rx, int ry, int rz, int frag) {
        return InvalidationRollup.fragmentKey(rx, ry, rz, frag);
    }

    private void record(int level, long from, long to) {
        mem.record(level, from, to, sig, RegionCrossingMemory.PROV_PROOF, BotCaps::sigDominates);
    }

    private void seedOpen(int level, int rx, int ry, int rz, int faceMaskBits) {
        int row = pyramid.rowFor(level, rx, ry, rz);
        RegionFragments rf = pyramid.ensureFragments(level, row);
        rf.reset(G);
        rf.setKind(RegionFragments.KIND_MIXED);
        rf.setPassFrac(8);
        rf.setAvgSolidHardness(4);
        int[] packed = new int[6];
        for (int f = 0; f < 6; f++) {
            packed[f] = ((faceMaskBits >> f) & 1) != 0
                    ? RegionFragments.packFootprint(0, 15, 0, 15) : RegionFragments.NO_FACE;
        }
        rf.setFragment(0, faceMaskBits, packed);
        rf.setFragmentCount(1);
        pyramid.setBuilt(level, row, true);
    }

    private void seedDense(int rx, int ry, int rz) {
        int row = pyramid.rowFor(0, rx, ry, rz);
        RegionFragments rf = pyramid.ensureFragments(0, row);
        rf.reset(G);
        rf.setKind(RegionFragments.KIND_MIXED);
        rf.setPassFrac(8);
        rf.setAvgSolidHardness(4);
        int[] packed = new int[6];
        for (int f = 0; f < 6; f++) {
            packed[f] = RegionFragments.packFootprint(0, 15, 0, 15);
        }
        for (int frag = 0; frag < DENSE_FRAGS; frag++) {
            rf.setFragment(frag, 0x3F, packed);
        }
        rf.setFragmentCount(DENSE_FRAGS);
        pyramid.setBuilt(0, row, true);
    }

    private void seedSolid(int level, int rx, int ry, int rz) {
        int row = pyramid.rowFor(level, rx, ry, rz);
        RegionFragments rf = pyramid.ensureFragments(level, row);
        rf.reset(G);
        rf.setKind(RegionFragments.KIND_SOLID);
        rf.setAvgSolidHardness(6);
        rf.setFragmentCount(0);
        pyramid.setBuilt(level, row, true);
    }

    private void mergeAllLeaves() {
        for (int r = 0; r < pyramid.rowCount(0); r++) {
            if (pyramid.isBuilt(0, r)) {
                PyramidMerger.mergeUpFragments(pyramid, pyramid.rowRX(0, r), pyramid.rowRY(0, r), pyramid.rowRZ(0, r));
            }
        }
    }
}
