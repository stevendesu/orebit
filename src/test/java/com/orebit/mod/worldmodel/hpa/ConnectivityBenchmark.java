package com.orebit.mod.worldmodel.hpa;

import java.util.Arrays;
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

/**
 * Settles the open design question for the HPA* region fragment model (the portal/connected-component
 * proposal): compute the 6-connected components of a 16³ region's passable cells via <b>flood fill (BFS)</b>
 * vs <b>union-find</b>. Both are run on the SAME synthetic grids, allocation-free past warmup (reusable
 * {@code @State} scratch, reset inside the measured op the way production resets per region), so the only
 * difference measured is the algorithm.
 *
 * <p>This is a PURE-ARRAY benchmark — no Minecraft, no {@code NavGridView}. A region is modelled as a
 * {@code boolean[4096]} of "passable" cells with the project's section-local linear index
 * {@code i = (y<<8) | (z<<4) | x} (see {@code TraversalGrid}), so neighbour stepping is the same ±1/±16/±256
 * index arithmetic the real grid uses. Both algorithms return the component count, which {@link #setup}
 * cross-checks once so a divergence fails the run rather than silently skewing a number.
 *
 * <p>Scenarios span the axes that drive CC cost — passable density and component count/shape:
 * <ul>
 *   <li><b>OPEN</b> — all passable, ONE big component. Best case; the BFS does one full-grid sweep, the
 *       union-find one full pass of unions that all collapse to a single root.</li>
 *   <li><b>HALF</b> — a solid wall at {@code x=8} → TWO big components. The two-blob baseline.</li>
 *   <li><b>SPECKLE</b> — deterministic ~50%-passable noise (an LCG) → many irregular small/medium
 *       components. The realistic "fragmented terrain" case (gravel/ore speckle, broken cave walls).</li>
 *   <li><b>CHECKER</b> — {@code (x+y+z)%2==0} passable: every passable cell is 6-isolated, so 2048 singleton
 *       components. The pathological worst case for component COUNT (the checkerboard stress).</li>
 * </ul>
 *
 * <p>Run (JDK 21, active 1.21.x node):
 * {@code ./gradlew :1.21.4:jmh -Pbench=ConnectivityBenchmark}. Pin one with {@code -Pscenario=CHECKER}.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 6, time = 1)
@Fork(2) // overridden to forks(0) by BenchmarkRunnerTest (runs embedded in the Knot JVM)
public class ConnectivityBenchmark {

    private static final int SIZE = 16;
    private static final int CELLS = SIZE * SIZE * SIZE; // 4096

    @Param({"OPEN", "HALF", "SPECKLE", "CHECKER"})
    private String scenario;

    /** The region's passable mask, section-local linear index {@code (y<<8)|(z<<4)|x}. */
    private boolean[] passable;

    // ---- Reusable scratch (no per-invocation allocation; reset inside the measured op) ----
    private final int[] floodLabel = new int[CELLS];
    private final int[] floodQueue = new int[CELLS];
    private final int[] ufParent = new int[CELLS];
    private final int[] ufSize = new int[CELLS];

    @Setup(Level.Trial)
    public void setup() {
        passable = buildGrid(scenario);
        // Cross-check the two algorithms agree on this grid — a divergence is a bug, not a benchmark result.
        int a = floodComponents();
        int b = unionComponents();
        if (a != b) {
            throw new IllegalStateException(
                    "CC mismatch on " + scenario + ": flood=" + a + " union=" + b);
        }
    }

    private static boolean[] buildGrid(String scenario) {
        boolean[] p = new boolean[CELLS];
        switch (scenario) {
            case "OPEN":
                Arrays.fill(p, true);
                break;
            case "HALF":
                for (int i = 0; i < CELLS; i++) {
                    p[i] = (i & 15) != 8; // solid wall plane x==8 → two halves
                }
                break;
            case "SPECKLE": {
                // Deterministic ~50% noise (a fixed-seed LCG, so the fixture is reproducible).
                long s = 0x9E3779B97F4A7C15L;
                for (int i = 0; i < CELLS; i++) {
                    s = s * 6364136223846793005L + 1442695040888963407L;
                    p[i] = (s >>> 63) != 0; // top bit → ~50%
                }
                break;
            }
            case "CHECKER":
                for (int i = 0; i < CELLS; i++) {
                    int x = i & 15, z = (i >> 4) & 15, y = (i >> 8) & 15;
                    p[i] = ((x + y + z) & 1) == 0; // 6-isolated singletons
                }
                break;
            default:
                throw new IllegalArgumentException("unknown scenario: " + scenario);
        }
        return p;
    }

    @Benchmark
    public int floodFill() {
        return floodComponents();
    }

    @Benchmark
    public int unionFind() {
        return unionComponents();
    }

    // ----------------------------------------------------------------------------------------------------
    // Flood fill (BFS): sweep all cells; from each unvisited passable cell, BFS its whole component. Each
    // cell is enqueued once → O(n) total (NOT a re-run per face cell). Reset cost (fill label = -1) is part
    // of the measured op, matching a per-region recompute.
    // ----------------------------------------------------------------------------------------------------
    private int floodComponents() {
        final int[] label = floodLabel;
        final int[] queue = floodQueue;
        final boolean[] pass = passable;
        Arrays.fill(label, -1);
        int comp = 0;
        for (int seed = 0; seed < CELLS; seed++) {
            if (!pass[seed] || label[seed] != -1) continue;
            int head = 0, tail = 0;
            queue[tail++] = seed;
            label[seed] = comp;
            while (head < tail) {
                int c = queue[head++];
                int x = c & 15, z = (c >> 4) & 15, y = (c >> 8) & 15;
                if (x > 0)        { int n = c - 1;   if (pass[n] && label[n] == -1) { label[n] = comp; queue[tail++] = n; } }
                if (x < 15)       { int n = c + 1;   if (pass[n] && label[n] == -1) { label[n] = comp; queue[tail++] = n; } }
                if (z > 0)        { int n = c - 16;  if (pass[n] && label[n] == -1) { label[n] = comp; queue[tail++] = n; } }
                if (z < 15)       { int n = c + 16;  if (pass[n] && label[n] == -1) { label[n] = comp; queue[tail++] = n; } }
                if (y > 0)        { int n = c - 256; if (pass[n] && label[n] == -1) { label[n] = comp; queue[tail++] = n; } }
                if (y < 15)       { int n = c + 256; if (pass[n] && label[n] == -1) { label[n] = comp; queue[tail++] = n; } }
            }
            comp++;
        }
        return comp;
    }

    // ----------------------------------------------------------------------------------------------------
    // Union-find: one sequential pass unioning each passable cell with its three BACKWARD passable neighbours
    // (-x,-y,-z) — every 6-edge is covered exactly once. Path-halving find + union-by-size. Reset (parent =
    // identity) is part of the measured op. A final pass counts roots. This is also the primitive the pyramid
    // MERGE needs (union child fragments across shared internal faces), so the leaf reuses it.
    // ----------------------------------------------------------------------------------------------------
    private int unionComponents() {
        final int[] parent = ufParent;
        final int[] size = ufSize;
        final boolean[] pass = passable;
        for (int i = 0; i < CELLS; i++) { parent[i] = i; size[i] = 1; }
        for (int i = 0; i < CELLS; i++) {
            if (!pass[i]) continue;
            int x = i & 15, z = (i >> 4) & 15, y = (i >> 8) & 15;
            if (x > 0 && pass[i - 1])   union(parent, size, i, i - 1);
            if (z > 0 && pass[i - 16])  union(parent, size, i, i - 16);
            if (y > 0 && pass[i - 256]) union(parent, size, i, i - 256);
        }
        int comp = 0;
        for (int i = 0; i < CELLS; i++) {
            if (pass[i] && find(parent, i) == i) comp++;
        }
        return comp;
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]]; // path halving
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int[] size, int a, int b) {
        int ra = find(parent, a), rb = find(parent, b);
        if (ra == rb) return;
        if (size[ra] < size[rb]) { int t = ra; ra = rb; rb = t; } // union by size
        parent[rb] = ra;
        size[ra] += size[rb];
    }
}
