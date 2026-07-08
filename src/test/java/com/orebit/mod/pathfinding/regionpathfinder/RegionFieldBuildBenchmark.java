package com.orebit.mod.pathfinding.regionpathfinder;

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
import org.openjdk.jmh.infra.Blackhole;

import com.orebit.mod.Debug;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.worldmodel.hpa.RegionAddress;
import com.orebit.mod.worldmodel.hpa.RegionGrid;
import com.orebit.mod.worldmodel.pathing.FullSearchScenarios;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;

/**
 * Times ONE region cost-field build — the {@code PathPlan.regionFieldFor} rebuild body, i.e.
 * {@link RegionPathfinder.RegionBox#around} + {@link RegionPathfinder#costToGoalField} — across field box sizes
 * <b>3³ → 10³ regions</b>, over the {@link FullSearchScenarios#fieldWorld() fully-resident flat world}. This is
 * the PERF-AUDIT-region-field.md §6 field-build microbench: the Javadoc's "~6 µs per build" claim
 * ({@code PathPlan.regionFieldFor}) predates the box-scaled field-array bill (§2 item 1 — a dense
 * {@code dimX·dimY·dimZ·63}-slot allocation + {@code Arrays.fill}, ~432 KB at 7³, ~1 MB at 10×8×10), so §6 asks
 * for a measured size curve to replace it — the before/after metric for the P4 layout-shrink proposal, and the
 * build-side cost input to P1 (baking centroids raises build cost, which must be weighed against this bill).
 *
 * <p><b>What one op is:</b> exactly the per-rebuild work {@code PathPlan.regionFieldFor} pays when the window
 * target moves — construct the {@link RegionPathfinder.RegionBox} and run the goal-rooted bounded Dijkstra
 * {@link RegionPathfinder#costToGoalField} (which itself allocates the {@link RegionCostField} arrays, runs the
 * goal dig-flood seed BFS, and floods the box). Per the audit's "include any per-build allocation the production
 * path pays" framing, the field-array allocation and the dig-flood's boxed BFS scratch are deliberately INSIDE
 * the measured op — they ARE the thing being measured. Not included (negligible, gate-side): the one
 * {@code BlockPos.equals} root gate and the six {@link RegionAddress} coordinate derivations (pure int math).
 *
 * <p><b>Params:</b>
 * <ul>
 *   <li>{@code boxSize} ∈ 3,5,7,10 — the box edge in regions. 7³ is the production MINIMUM (bot and target in
 *       the same region + the fixed ±3 pad of {@code regionFieldFor}); 10³ is the audit's large-window example
 *       (bot ~3 regions off diagonally + pad); 3³/5³ are sub-production anchors that separate the fixed
 *       per-build overhead (ThreadLocal reset, seeding) from the volume-scaled terms (array bill + flood).</li>
 *   <li>{@code scenario} ∈ SURFACE (the common case — a standable window-target floor cell; the dig-flood seeds
 *       from the feet pocket one cell up) / BURIED (the goal 3 blocks inside solid rock — the §2-item-2
 *       dig-flood BFS digs ~4 cells to the surface pocket). Pin with {@code -Pscenario=SURFACE}.</li>
 *   <li>{@code term} ∈ EXHAUST (the start-less overload — the Dijkstra floods the whole box, the pre-s53
 *       behaviour plus the floor bookkeeping) / FATSKEL (the s53 fat-skeleton early exit: a production-shaped
 *       bot floor cell {@code max(0, n−7)} regions off the goal diagonally — the {@code around(bot, target, 3)}
 *       anchoring — is passed as {@code startFloor}, so the build stops once the start settles and the chain's
 *       Chebyshev-1 neighbourhood is settled). The pair is the in-JVM A/B for the early-exit win; the
 *       cross-commit A/B (old code vs new EXHAUST) guards the bookkeeping overhead.</li>
 * </ul>
 *
 * <p><b>Allocation discipline</b> (CLAUDE.md perf model): the world fixture (sections + region grid) is built
 * ONCE per trial in {@link #setup}, and — like the per-dimension {@code RegionGrid.of(level)} across replans —
 * its lazily-built leaves persist across ops (the setup dry run pre-floods them), so the measured op is the
 * steady-state REBUILD cost over already-built terrain, exactly what the tick thread pays per window-target
 * move. The op's own allocations (field arrays, dig-flood scratch, the box object) are production's.
 *
 * <p>Run: {@code ./gradlew "Set active project to 1.21.11"} then
 * {@code ./gradlew :1.21.11:jmh -Pbench=RegionFieldBuildBenchmark} (JDK 21 on the active 1.21.x node; JMH runs
 * only on the mc-1.21 era). {@code -Pprof=gc} attaches the allocation-rate profiler (the §2-item-1 bill read);
 * {@code -Pscenario=BURIED} pins the goal kind.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 6, time = 2)
@Fork(2) // overridden to forks(0) by BenchmarkRunnerTest (must stay in the bootstrapped-MC JVM)
public class RegionFieldBuildBenchmark {

    @Param({"SURFACE", "BURIED"})
    private String scenario;

    @Param({"3", "5", "7", "10"})
    private String boxSize;

    @Param({"EXHAUST", "FATSKEL"})
    private String term;

    /** The shared flat world (sections + region grid), built once per trial; leaves persist across ops. */
    private FullSearchScenarios.FieldWorld world;
    /** The goal FLOOR cell for the selected scenario (surface floor-plane cell, or the buried solid cell). */
    private BlockPos goalFloor;
    /** The bot floor cell for the FATSKEL arm ({@code null} under EXHAUST): a surface cell {@code max(0, n−7)}
     *  regions off the goal column diagonally — the production {@code around(bot, target, 3)} anchoring. */
    private BlockPos startFloor;
    /** The goal's level-0 region coords — the box anchor (prebaked; production derives them per rebuild too). */
    private int grx, gry, grz;
    /** Box derivation: {@code around(g, g+off, pad)} has edge {@code off + 1 + 2·pad}; off 0 = odd, 1 = even. */
    private int pad, off;

    private static final BotCaps CAPS = BotCaps.BREAK_PLACE;

    private static boolean bootstrapped = false;

    @Setup(Level.Trial)
    public void setup() {
        if (!bootstrapped) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap(); // NavSection classify reads the block-state registry
            bootstrapped = true;
        }
        // Any per-search region-tier trace/console spew would dominate timing — silence it.
        RegionPathfinder.TRACE = false;
        Debug.ENABLED = false;

        world = FullSearchScenarios.fieldWorld();
        goalFloor = "BURIED".equals(scenario) ? world.buriedGoalFloor : world.surfaceGoalFloor;
        grx = RegionAddress.regionX(goalFloor.getX(), 0);
        gry = RegionAddress.regionY(goalFloor.getY(), 0, world.minY);
        grz = RegionAddress.regionZ(goalFloor.getZ(), 0);
        int n = Integer.parseInt(boxSize);
        pad = (n - 1) / 2;
        off = 1 - (n & 1);
        startFloor = "FATSKEL".equals(term) ? startFloorFor(n) : null;
        sanityDryRun(n);
    }

    /** The FATSKEL bot cell: a surface floor cell {@code max(0, n−7)} regions off the goal column on both
     *  horizontal axes — inside every measured box (production's {@code around(bot, target, 3)} has the bot 3
     *  regions inside the box edge; the sub-production 3³/5³ anchors put the bot in the goal region). */
    static BlockPos startFloorFor(int n) {
        int offRegions = Math.max(0, n - 7);
        int wx = 8 * 16 + 8 + offRegions * 16; // FIELD_CENTER + offRegions regions (surface plane at y=80)
        return new BlockPos(wx, 80, wx);
    }

    /** The measured production box: the goal's region, expanded to an {@code n}³ box (see {@link #pad}/{@link #off};
     *  for odd {@code n} this is exactly {@code regionFieldFor}'s bot==target-region box at pad {@code (n−1)/2}). */
    static RegionPathfinder.RegionBox boxFor(int grx, int gry, int grz, int n) {
        int pad = (n - 1) / 2;
        int off = 1 - (n & 1);
        return RegionPathfinder.RegionBox.around(grx, gry, grz, grx + off, gry + off, grz + off, pad);
    }

    /**
     * Setup-time (NOT measured) shape check + leaf pre-flood: the build must produce a field that actually
     * REACHED the surface probe cell one region off the goal column (a field of UNREACHED slots would mean the
     * flood never engaged and the timing would measure little more than the array fill), and the buried goal's
     * dig-flood must report ≥1 pocket (the §2-item-2 path this bench deliberately includes). The dry-run flood
     * also lazily builds every leaf the measured floods will touch, making subsequent ops steady-state.
     */
    private void sanityDryRun(int n) {
        RegionPathfinder.RegionBox box = boxFor(grx, gry, grz, n);
        RegionCostField field = RegionPathfinder.costToGoalField(world.grid, world.minY, goalFloor, startFloor,
                CAPS.canBreak(), CAPS.canPlace(), CAPS.safeFallDistance(),
                RegionMineModel.DEFAULT, RegionPlaceModel.DEFAULT, box);
        if (field == null) {
            throw new IllegalStateException(scenario + "/" + boxSize + " built a null field — fixture broken");
        }
        // Probe: the surface FEET cell one region +x of the goal column — inside even the 3³ box for both goal
        // kinds, on the surface fragment the flood must cross into from the goal pocket, and (FATSKEL) inside
        // the fat skeleton (goal-region-adjacent), so it must be genuinely SETTLED under either termination.
        float probe = field.rawCost(grx + 1, 5, grz, 0); // the surface region one region +x, fragment 0
        System.out.println("[RegionFieldBuildBenchmark] " + scenario + "/" + term + " box=" + boxSize
                + "^3 sanity: probeCost=" + probe + " settles=" + RegionPathfinder.lastFieldSettles()
                + " earlyExit=" + RegionPathfinder.lastFieldEarlyExit());
        if (probe >= RegionCostField.UNREACHED) {
            throw new IllegalStateException(scenario + "/" + boxSize
                    + " field never reached the adjacent surface region — flood not engaging, fixture broken");
        }
        if ("FATSKEL".equals(term) && !RegionPathfinder.lastFieldEarlyExit()) {
            throw new IllegalStateException(scenario + "/" + boxSize
                    + " FATSKEL build did not early-exit — the arm would measure an exhaustive build");
        }
        if ("BURIED".equals(scenario)) {
            int[] pockets = { 0 };
            world.grid.goalDigSeeds(goalFloor.getX(), goalFloor.getY(), goalFloor.getZ(), RegionGrid.MAX_GOAL_DIG_CELLS,
                    (rx, ry, rz, frag, digCells) -> pockets[0]++);
            if (pockets[0] == 0) {
                throw new IllegalStateException("BURIED goal dig-flood found no pockets — the buried-goal "
                        + "seed path this scenario exists to measure is not engaging");
            }
        }
    }

    @Benchmark
    public void build(Blackhole bh) {
        // One op == the regionFieldFor rebuild body: box construction + the goal-rooted bounded Dijkstra,
        // including the RegionCostField array allocation + fill and the goal dig-flood seed BFS. The box math
        // uses the prebaked pad/off ints (production's inputs are ints too — no param-string parse in the op).
        RegionPathfinder.RegionBox box = RegionPathfinder.RegionBox.around(
                grx, gry, grz, grx + off, gry + off, grz + off, pad);
        bh.consume(RegionPathfinder.costToGoalField(world.grid, world.minY, goalFloor, startFloor,
                CAPS.canBreak(), CAPS.canPlace(), CAPS.safeFallDistance(),
                RegionMineModel.DEFAULT, RegionPlaceModel.DEFAULT, box));
    }
}
