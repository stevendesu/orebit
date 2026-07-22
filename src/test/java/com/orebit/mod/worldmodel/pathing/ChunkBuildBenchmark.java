package com.orebit.mod.worldmodel.pathing;

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

import com.orebit.mod.worldmodel.hpa.CostPyramid;
import com.orebit.mod.worldmodel.hpa.FragmentLeafComputer;
import com.orebit.mod.worldmodel.hpa.PyramidMerger;
import com.orebit.mod.worldmodel.hpa.RegionAddress;
import com.orebit.mod.worldmodel.hpa.RegionFragments;
import com.orebit.mod.worldmodel.resource.Log2Codec;
import com.orebit.mod.worldmodel.resource.ResourceClasses;
import com.orebit.mod.worldmodel.resource.ResourceMerger;
import com.orebit.mod.worldmodel.resource.ResourcePyramid;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.Strategy;

/**
 * <b>Per-chunk-column cost of building the pathfinding data structures</b> — the measured input to the
 * "how many chunks/tick can the nav-build drain afford?" decision (the drain rate is currently a hardcoded,
 * <i>unmeasured</i> {@code ChunkNavLoader.MAX_BUILDS_PER_TICK} of 8). One drained chunk column runs, on the
 * tick thread, the whole two-tier build for its ~24 sections; this benchmark reproduces that build headlessly
 * (no live {@link net.minecraft.server.level.ServerLevel ServerLevel}) over synthetic
 * {@link PalettedContainer}s and times each stage independently, plus the two composite shapes that bound the
 * real cost (an isolated column vs. a contiguous streaming region).
 *
 * <h2>The three structures a column build produces</h2>
 * <ol>
 *   <li><b>(A) NavGrid</b> — {@link NavSectionBuilder#classifyNavtypes classify} + {@link
 *       NavSectionBuilder#computeFlags computeFlags} + {@link NavSectionBuilder#computeDepth computeDepth} per
 *       16³ section (the exact 3-pass column build {@code ChunkNavBuilder.buildAllSections} runs). The
 *       indexed-resource TALLY rides for free in the classify pass's {@code int[]} out-arg, so its compute is
 *       folded into stage A (matching production: {@code ChunkNavBuilder} computes the tally in pass 1).</li>
 *   <li><b>(B) HPA fragment leaf + rollup</b> — {@link FragmentLeafComputer#computeLeaf} per section (a
 *       bounded 6-connected connectivity flood — the heavy, terrain-shape-dependent cost), then {@link
 *       PyramidMerger#mergeUpFragments} parent→root with damping.</li>
 *   <li><b>(C) Resource compass</b> — {@link ResourcePyramid#setRow} + {@link ResourceMerger#mergeUpTallies}
 *       parent→root (to level {@value ResourcePyramid#RESOURCE_TOP_LEVEL}) with damping. The cheapest.</li>
 * </ol>
 * B's and C's rollups are incremental with early-out DAMPING (a parent walk stops the instant a level's output
 * is unchanged), so across many contiguous columns sharing coarse ancestors the amortized rollup is sub-linear
 * — the {@code regionBuild} shape exercises exactly this.
 *
 * <h2>Terrain axis ({@code @Param terrain})</h2>
 * A full ~24-section column of synthetic {@code PalettedContainer}s:
 * <ul>
 *   <li><b>UNIFORM_AIR</b> — all air (a sky column). Every section hits the classify all-air fast path and the
 *       fragment leaf's uniform-AIR fast path.</li>
 *   <li><b>UNIFORM_SOLID</b> — all stone (deep underground). Full classify, uniform-SOLID leaf fast path, no
 *       resources.</li>
 *   <li><b>SURFACE</b> — the common case: stone below (with a little ore speckle), one mixed dirt/grass surface
 *       section, air above. A realistic mix of all-solid / one-mixed / all-air sections.</li>
 *   <li><b>CAVE</b> — stone speckled with air pockets + ore (mirrors {@code ConnectivityBenchmark}'s SPECKLE):
 *       complex 6-connected connectivity that drives the fragment flood into many components / the cap
 *       collapse. The worst terrain for stage B.</li>
 * </ul>
 *
 * <h2>Stages ({@code @Benchmark} methods)</h2>
 * <ul>
 *   <li>{@code navgrid} — A for a full column (classify+flags+depth+resource-tally-compute), steady-state
 *       (re-classifies into reused scratch grids each op; production reuses pooled sections likewise).</li>
 *   <li>{@code hpaLeaf} — B leaf only: {@code computeLeaf} for every section, into a persistent pyramid whose
 *       {@link RegionFragments} records are reused across rebuilds (production reuses them too — the leaf
 *       <i>rebuild</i> cost, no first-alloc).</li>
 *   <li>{@code hpaLeafRollup} — B complete: leaf + {@code mergeUpFragments} per section, into a FRESH pyramid
 *       each op (Level.Invocation) so the parent→root walk actually does work rather than damping to a no-op
 *       against an already-built pyramid. <b>Rollup-alone ≈ {@code hpaLeafRollup} − {@code hpaLeaf}.</b></li>
 *   <li>{@code resource} — C: {@code setRow} + {@code mergeUpTallies} per resource-bearing section, into a
 *       fresh {@link ResourcePyramid} each op (same damping reason). The tally bytes are precomputed in
 *       setup (their COMPUTE is stage A).</li>
 *   <li>{@code fullColumn} — A+B+C for ONE isolated column into fresh pyramids: the per-drain-unit cost when
 *       chunks load scattered (worst-case rollup — no ancestor sharing with any other column).</li>
 *   <li>{@code regionBuild} — A+B+C for a contiguous {@value #REGION}×{@value #REGION} block of columns into
 *       fresh pyramids: the rollup damping amortizes across shared ancestors (a player exploring a contiguous
 *       area). Reported for the whole region; amortized per-column = op_ns / {@value #REGION_COLUMNS}.</li>
 * </ul>
 *
 * <p><b>Fresh-pyramid stages use {@code @Setup(Level.Invocation)}</b> to allocate empty {@link CostPyramid}/
 * {@link ResourcePyramid} shells (two cheap {@code new}s — no leaf pre-building), so the per-op state is
 * damping-honest and memory stays bounded (each op's pyramid is GC'd). Level.Invocation's per-invocation
 * timing overhead is negligible here: every method is ≥ tens of µs/op (fullColumn/regionBuild are 100s of
 * µs → tens of ms), far above the ~sub-µs floor where Level.Invocation distorts.
 *
 * <p>Run (JDK 21, active 1.21.11 node; JMH runs only on the mc-1.21 era):
 * {@code ./gradlew "Set active project to 1.21.11"} then
 * {@code ./gradlew :1.21.11:jmh -Pbench=ChunkBuildBenchmark}. Pin one terrain with {@code -Pscenario=CAVE}
 * (the runner maps {@code -Pscenario} → the {@code @Param} named {@code scenario}). {@code -Pprof=gc} attaches
 * the allocation-rate profiler.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2) // overridden to forks(0) by BenchmarkRunnerTest (must stay in the bootstrapped-MC JVM)
public class ChunkBuildBenchmark {

    /** Dimension floor (overworld −64) — 24 sections of 16 blocks = the 384-tall column. */
    private static final int MIN_Y = -64;
    private static final int SECTIONS = 24;
    private static final int COLS = ResourceClasses.COLUMN_COUNT; // 23 resource columns

    /** Contiguous region footprint for the amortized shape (8×8 = 64 chunk columns = 128 blocks/side). */
    private static final int REGION = 8;
    private static final int REGION_COLUMNS = REGION * REGION;

    /** Base region coords for the isolated stages (arbitrary; fresh pyramids make them non-interfering). */
    private static final int ISO_RX = 1000, ISO_RZ = 1000;
    /** Base region coords for the contiguous region shape. */
    private static final int REG_RX = 4000, REG_RZ = 4000;

    /** ~35% air speckle for CAVE — below the 3D 6-connected percolation threshold (~0.31 connects), so the
     *  flood fragments into many occupiable pockets / the cap collapse rather than one big component. */
    private static final int CAVE_AIR_PERMIL = 350;
    /** ~1.5% ore speckle (any indexed ore) sprinkled into stone for SURFACE/CAVE. */
    private static final int ORE_PERMIL = 15;

    @Param({"UNIFORM_AIR", "UNIFORM_SOLID", "SURFACE", "CAVE"})
    private String scenario;

    // ---- Trial-scope fixtures (built once per @Param) ----
    /** The terrain: one PalettedContainer per section (null slot ⇒ all-air section). */
    private PalettedContainer<BlockState>[] terrain;
    /** Per-section all-air flag (the classify fast-path input; == Sections.hasOnlyAir in production). */
    private boolean[] onlyAir;
    /** Read-only classified sections (input for the leaf/rollup stages). */
    private NavSection[] classified;
    /** Per-section log₂ resource tally (null ⇒ resource-free section) — precomputed for the resource stage. */
    private byte[][] tallyBytes;

    /** Reused scratch sections + tally buffer for the navgrid / fullColumn / regionBuild classify passes. */
    private NavSection[] navScratch;
    private int[] resScratch;

    /** Persistent pyramid for the steady-state leaf-rebuild stage (records reused across ops). */
    private CostPyramid steadyPyramid;

    // ---- Level.Invocation-scope fresh pyramids (fresh per op for the rollup-bearing stages) ----
    private CostPyramid freshCost;
    private ResourcePyramid freshRes;

    private static boolean bootstrapped = false;

    private static PalettedContainer<BlockState> newContainer() {
        return new PalettedContainer<>(
                Blocks.AIR.defaultBlockState(), Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
    }

    @SuppressWarnings("unchecked")
    @Setup(Level.Trial)
    public void setup() {
        if (!bootstrapped) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap(); // classify + fragment leaf read the block-state registry
            bootstrapped = true;
        }

        terrain = new PalettedContainer[SECTIONS];
        onlyAir = new boolean[SECTIONS];
        buildTerrain();

        // Build the read-only classified column AND capture its per-section resource tallies in one pass.
        resScratch = new int[COLS];
        classified = new NavSection[SECTIONS];
        tallyBytes = new byte[SECTIONS][];
        for (int ry = 0; ry < SECTIONS; ry++) {
            classified[ry] = NavSection.create(new BlockPos(ISO_RX << 4, MIN_Y + ry * 16, ISO_RZ << 4));
        }
        classifyColumn(classified, tallyBytes);

        navScratch = new NavSection[SECTIONS];
        for (int ry = 0; ry < SECTIONS; ry++) {
            navScratch[ry] = NavSection.create(new BlockPos(0, MIN_Y + ry * 16, 0));
        }

        // Warm the persistent leaf pyramid so hpaLeaf reuses records (steady rebuild, no per-op record alloc).
        steadyPyramid = new CostPyramid();
        for (int ry = 0; ry < SECTIONS; ry++) {
            int r = steadyPyramid.rowFor(0, ISO_RX, ry, ISO_RZ);
            FragmentLeafComputer.computeLeaf(classified[ry], steadyPyramid.ensureFragments(0, r));
            steadyPyramid.setBuilt(0, r, true);
        }

        sanityDryRun();
    }

    /** Fresh empty pyramids for the damping-honest rollup stages — two cheap shells, GC'd after each op. */
    @Setup(Level.Invocation)
    public void freshState() {
        freshCost = new CostPyramid();
        freshRes = new ResourcePyramid();
    }

    // ===================================================================================================
    // Terrain construction
    // ===================================================================================================

    private void buildTerrain() {
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState dirt = Blocks.DIRT.defaultBlockState();
        BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
        // A small rotation of indexed ores so several resource columns get exercised.
        BlockState[] ores = {
            Blocks.COAL_ORE.defaultBlockState(), Blocks.IRON_ORE.defaultBlockState(),
            Blocks.COPPER_ORE.defaultBlockState(), Blocks.GOLD_ORE.defaultBlockState(),
            Blocks.REDSTONE_ORE.defaultBlockState(), Blocks.DIAMOND_ORE.defaultBlockState(),
        };

        switch (scenario) {
            case "UNIFORM_AIR":
                for (int ry = 0; ry < SECTIONS; ry++) { terrain[ry] = null; onlyAir[ry] = true; }
                break;

            case "UNIFORM_SOLID":
                for (int ry = 0; ry < SECTIONS; ry++) {
                    PalettedContainer<BlockState> c = newContainer();
                    fill(c, stone);
                    terrain[ry] = c; onlyAir[ry] = false;
                }
                break;

            case "SURFACE": {
                // ry 0..7 stone (ore-speckled), ry 8 the dirt/grass surface band, ry 9..23 air.
                long lcg = 0x1234_5678_9ABC_DEF1L;
                for (int ry = 0; ry <= 7; ry++) {
                    PalettedContainer<BlockState> c = newContainer();
                    for (int y = 0; y < 16; y++)
                        for (int z = 0; z < 16; z++)
                            for (int x = 0; x < 16; x++) {
                                lcg = adv(lcg);
                                c.getAndSet(x, y, z, permil(lcg, ORE_PERMIL) ? ores[oreIdx(lcg, ores.length)] : stone);
                            }
                    terrain[ry] = c; onlyAir[ry] = false;
                }
                PalettedContainer<BlockState> surf = newContainer();
                for (int z = 0; z < 16; z++)
                    for (int x = 0; x < 16; x++) {
                        for (int y = 0; y <= 7; y++) surf.getAndSet(x, y, z, stone); // stone base
                        for (int y = 8; y <= 10; y++) surf.getAndSet(x, y, z, dirt); // dirt
                        surf.getAndSet(x, 11, z, grass);                             // grass top; y 12..15 air
                    }
                terrain[8] = surf; onlyAir[8] = false;
                for (int ry = 9; ry < SECTIONS; ry++) { terrain[ry] = null; onlyAir[ry] = true; }
                break;
            }

            case "CAVE": {
                // Stone speckled with air pockets (complex connectivity) + sparse ore, every section.
                long lcg = 0xCAFE_BABE_DEAD_BEEFL;
                for (int ry = 0; ry < SECTIONS; ry++) {
                    PalettedContainer<BlockState> c = newContainer();
                    for (int y = 0; y < 16; y++)
                        for (int z = 0; z < 16; z++)
                            for (int x = 0; x < 16; x++) {
                                lcg = adv(lcg);
                                if (permil(lcg, CAVE_AIR_PERMIL)) continue;          // air pocket (leave AIR)
                                lcg = adv(lcg);
                                c.getAndSet(x, y, z, permil(lcg, ORE_PERMIL) ? ores[oreIdx(lcg, ores.length)] : stone);
                            }
                    terrain[ry] = c; onlyAir[ry] = false;
                }
                break;
            }
            default:
                throw new IllegalArgumentException("unknown terrain: " + scenario);
        }
    }

    private static void fill(PalettedContainer<BlockState> c, BlockState s) {
        for (int y = 0; y < 16; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++)
                    c.getAndSet(x, y, z, s);
    }

    /** A 64-bit LCG step (SplitMix-style constants) — deterministic, reproducible fixtures. */
    private static long adv(long s) {
        return s * 6364136223846793005L + 1442695040888963407L;
    }

    /** True with probability {@code permil}/1000 from the LCG's high bits. */
    private static boolean permil(long s, int permil) {
        return Math.floorMod(s >>> 40, 1000) < permil;
    }

    private static int oreIdx(long s, int n) {
        return (int) Math.floorMod(s >>> 20, n);
    }

    // ===================================================================================================
    // The shared build primitives (headless equivalents of ChunkNavBuilder.buildAllSections)
    // ===================================================================================================

    /** Stage A for a whole column into {@code sections}, writing each section's tally into {@code tallyOut}
     *  (nullable). Reproduces {@code ChunkNavBuilder}'s 3-pass build exactly. Returns nothing timed itself. */
    private void classifyColumn(NavSection[] sections, byte[][] tallyOut) {
        // Pass 1 — navtypes + resource tally.
        for (int ry = 0; ry < SECTIONS; ry++) {
            java.util.Arrays.fill(resScratch, 0);
            NavSectionBuilder.classifyNavtypes(terrain[ry], onlyAir[ry], sections[ry].getTraversalGrid(), null,
                    resScratch);
            byte[] tally = encodeTally(resScratch);
            sections[ry].setResourceTally(tally);
            if (tallyOut != null) tallyOut[ry] = tally;
        }
        // Pass 2 — flags, each section reading the one above as vertical overscan.
        for (int ry = 0; ry < SECTIONS; ry++) {
            NavSection above = ry + 1 < SECTIONS ? sections[ry + 1] : null;
            NavSectionBuilder.computeFlags(sections[ry].getTraversalGrid(), onlyAir[ry],
                    (above == null || onlyAir[ry + 1]) ? null : above.getTraversalGrid());
        }
        // Pass 3 — depth nibbles.
        NavSectionBuilder.computeDepth(sections);
    }

    /** Log₂-encode raw per-column counts into a tally byte[], or null if the section held no indexed block
     *  (the sparsity contract — mirrors {@code ChunkNavBuilder.attachResourceTally}). */
    private static byte[] encodeTally(int[] scratch) {
        byte[] tally = null;
        for (int c = 0; c < COLS; c++) {
            if (scratch[c] != 0) {
                if (tally == null) tally = new byte[COLS];
                tally[c] = Log2Codec.encode(scratch[c]);
            }
        }
        return tally;
    }

    /** Stage B (leaf + rollup) for one column at region (rx, rz) into a cost pyramid. */
    private void buildHpaColumn(CostPyramid p, NavSection[] sections, int rx, int rz) {
        for (int ry = 0; ry < SECTIONS; ry++) {
            int r = p.rowFor(0, rx, ry, rz);
            FragmentLeafComputer.computeLeaf(sections[ry], p.ensureFragments(0, r));
            p.setBuilt(0, r, true);
            PyramidMerger.mergeUpFragments(p, rx, ry, rz);
        }
    }

    /** Stage C (resource setRow + rollup) for one column at region (rx, rz) into a resource pyramid. */
    private void buildResourceColumn(ResourcePyramid p, byte[][] tallies, int rx, int rz) {
        for (int ry = 0; ry < SECTIONS; ry++) {
            byte[] tally = tallies[ry];
            if (tally == null) continue; // sparse: no row for a resource-free section
            int r = p.rowFor(0, rx, ry, rz);
            p.setRow(0, r, tally);
            p.setBuilt(0, r, true);
            ResourceMerger.mergeUpTallies(p, rx, ry, rz);
        }
    }

    // ===================================================================================================
    // Benchmarks
    // ===================================================================================================

    /** (A) NavGrid: classify + flags + depth + resource-tally compute for a full column. */
    @Benchmark
    public void navgrid(Blackhole bh) {
        classifyColumn(navScratch, null);
        bh.consume(navScratch);
    }

    /** (B leaf) fragment leaf flood for every section — steady rebuild into reused records. */
    @Benchmark
    public void hpaLeaf(Blackhole bh) {
        for (int ry = 0; ry < SECTIONS; ry++) {
            int r = steadyPyramid.rowFor(0, ISO_RX, ry, ISO_RZ);
            RegionFragments rf = steadyPyramid.ensureFragments(0, r);
            FragmentLeafComputer.computeLeaf(classified[ry], rf);
            bh.consume(rf.fragmentCount());
        }
    }

    /** (B leaf + rollup) into a fresh pyramid — rollup-alone ≈ this − hpaLeaf. */
    @Benchmark
    public void hpaLeafRollup(Blackhole bh) {
        buildHpaColumn(freshCost, classified, ISO_RX, ISO_RZ);
        bh.consume(freshCost);
    }

    /** (C) resource setRow + mergeUpTallies into a fresh resource pyramid. */
    @Benchmark
    public void resource(Blackhole bh) {
        buildResourceColumn(freshRes, tallyBytes, ISO_RX, ISO_RZ);
        bh.consume(freshRes);
    }

    /** A+B+C for ONE isolated column (the per-drain-unit cost, worst-case rollup — no ancestor sharing). */
    @Benchmark
    public void fullColumn(Blackhole bh) {
        classifyColumn(navScratch, tallyBytes);         // A (recompute tallies into the reused buffer)
        buildHpaColumn(freshCost, navScratch, ISO_RX, ISO_RZ);   // B
        buildResourceColumn(freshRes, tallyBytes, ISO_RX, ISO_RZ); // C
        bh.consume(freshCost);
        bh.consume(freshRes);
    }

    /** Contiguous 32×32-chunk shard = one L5 region (1024 columns). */
    private static final int SHARD = 32;
    private static final int SHARD_COLUMNS = SHARD * SHARD;
    private static final int SHARD_RX = 6000, SHARD_RZ = 6000;

    /** (B leaf only) over a contiguous 32×32 shard into a fresh pyramid — the shard-scale leaf baseline. */
    @Benchmark
    public void shardLeaf(Blackhole bh) {
        for (int cx = 0; cx < SHARD; cx++) {
            for (int cz = 0; cz < SHARD; cz++) {
                for (int ry = 0; ry < SECTIONS; ry++) {
                    int r = freshCost.rowFor(0, SHARD_RX + cx, ry, SHARD_RZ + cz);
                    FragmentLeafComputer.computeLeaf(classified[ry], freshCost.ensureFragments(0, r));
                    freshCost.setBuilt(0, r, true);
                }
            }
        }
        bh.consume(freshCost);
    }

    /** (B leaf + rollup) over a contiguous 32×32 shard into a fresh pyramid — the L0-only DISK-RELOAD cost.
     *  Shard rollup-alone (rebuild L1..L6 for the whole shard) ≈ shardRollup − shardLeaf. */
    @Benchmark
    public void shardRollup(Blackhole bh) {
        for (int cx = 0; cx < SHARD; cx++) {
            for (int cz = 0; cz < SHARD; cz++) {
                buildHpaColumn(freshCost, classified, SHARD_RX + cx, SHARD_RZ + cz);
            }
        }
        bh.consume(freshCost);
    }

    /** A+B+C over a contiguous REGION×REGION block of columns (rollup damping amortizes). Report / n columns. */
    @Benchmark
    public void regionBuild(Blackhole bh) {
        for (int cx = 0; cx < REGION; cx++) {
            for (int cz = 0; cz < REGION; cz++) {
                classifyColumn(navScratch, tallyBytes);
                buildHpaColumn(freshCost, navScratch, REG_RX + cx, REG_RZ + cz);
                buildResourceColumn(freshRes, tallyBytes, REG_RX + cx, REG_RZ + cz);
            }
        }
        bh.consume(freshCost);
        bh.consume(freshRes);
    }

    // ===================================================================================================
    // Untimed fixture-shape assertions (a benchmark measuring the wrong fixture is worthless)
    // ===================================================================================================

    private void sanityDryRun() {
        int built = 0;
        for (int ry = 0; ry < SECTIONS; ry++) if (classified[ry] != null) built++;
        if (built != SECTIONS) {
            throw new IllegalStateException(scenario + ": expected " + SECTIONS + " sections, got " + built);
        }

        // Per-section fill classes (non-air cell count) — used to assert the intended terrain shape.
        int[] nonAir = new int[SECTIONS];
        for (int ry = 0; ry < SECTIONS; ry++) nonAir[ry] = countNonAir(ry);

        switch (scenario) {
            case "UNIFORM_AIR": {
                for (int ry = 0; ry < SECTIONS; ry++) {
                    if (nonAir[ry] != 0) throw new IllegalStateException("UNIFORM_AIR: section " + ry + " not all air");
                }
                RegionFragments rf = leafOf(0);
                if (rf.kind() != RegionFragments.KIND_AIR) {
                    throw new IllegalStateException("UNIFORM_AIR: leaf kind " + rf.kind() + " != KIND_AIR");
                }
                break;
            }
            case "UNIFORM_SOLID": {
                for (int ry = 0; ry < SECTIONS; ry++) {
                    if (nonAir[ry] != 4096) throw new IllegalStateException("UNIFORM_SOLID: section " + ry + " not full");
                }
                RegionFragments rf = leafOf(SECTIONS / 2);
                if (rf.kind() != RegionFragments.KIND_SOLID) {
                    throw new IllegalStateException("UNIFORM_SOLID: leaf kind " + rf.kind() + " != KIND_SOLID");
                }
                break;
            }
            case "SURFACE": {
                boolean anyFull = false, anyEmpty = false, anyMixed = false;
                for (int ry = 0; ry < SECTIONS; ry++) {
                    if (nonAir[ry] == 4096) anyFull = true;
                    else if (nonAir[ry] == 0) anyEmpty = true;
                    else anyMixed = true;
                }
                if (!(anyFull && anyEmpty && anyMixed)) {
                    throw new IllegalStateException("SURFACE: not a mixed column (full=" + anyFull
                            + " empty=" + anyEmpty + " mixed=" + anyMixed + ")");
                }
                if (!anyTally()) throw new IllegalStateException("SURFACE: no resource tally (ore speckle missing)");
                break;
            }
            case "CAVE": {
                // Every section is a stone/air speckle → mixed fills (neither all-air nor all-solid).
                for (int ry = 0; ry < SECTIONS; ry++) {
                    if (nonAir[ry] == 0 || nonAir[ry] == 4096) {
                        throw new IllegalStateException("CAVE: section " + ry + " degenerate fill=" + nonAir[ry]);
                    }
                }
                // The leaf flood must produce a non-degenerate connectivity result: multiple fragments or the
                // over-cap collapse (both exercise the full flood + occupiability + cap this bench is timing).
                RegionFragments rf = leafOf(SECTIONS / 2);
                if (rf.kind() != RegionFragments.KIND_MIXED
                        || !(rf.fragmentCount() > 1 || rf.isCollapsed())) {
                    throw new IllegalStateException("CAVE: leaf degenerate (kind=" + rf.kind()
                            + " fragments=" + rf.fragmentCount() + " collapsed=" + rf.isCollapsed()
                            + ") — tune CAVE_AIR_PERMIL");
                }
                if (!anyTally()) throw new IllegalStateException("CAVE: no resource tally (ore speckle missing)");
                break;
            }
            default:
                throw new IllegalArgumentException("unknown terrain: " + scenario);
        }
        System.out.println("[ChunkBuildBenchmark] " + scenario + " fixture OK: sections=" + SECTIONS
                + " midLeaf=" + describeLeaf(leafOf(SECTIONS / 2)) + " tallyRows=" + tallyRows());
    }

    private int countNonAir(int ry) {
        if (terrain[ry] == null) return 0;
        BlockState air = Blocks.AIR.defaultBlockState();
        int n = 0;
        for (int y = 0; y < 16; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++)
                    if (terrain[ry].get(x, y, z) != air) n++;
        return n;
    }

    private RegionFragments leafOf(int ry) {
        RegionFragments rf = new RegionFragmentsHolder().rf;
        FragmentLeafComputer.computeLeaf(classified[ry], rf);
        return rf;
    }

    /** RegionFragments has a package-private ctor; borrow one via a persistent pyramid row (public API). */
    private final class RegionFragmentsHolder {
        final RegionFragments rf;
        RegionFragmentsHolder() {
            int r = steadyPyramid.rowFor(0, ISO_RX - 7, 0, ISO_RZ - 7); // a scratch row, away from the leaf rows
            rf = steadyPyramid.ensureFragments(0, r);
        }
    }

    private boolean anyTally() {
        for (int ry = 0; ry < SECTIONS; ry++) if (tallyBytes[ry] != null) return true;
        return false;
    }

    private int tallyRows() {
        int n = 0;
        for (int ry = 0; ry < SECTIONS; ry++) if (tallyBytes[ry] != null) n++;
        return n;
    }

    private static String describeLeaf(RegionFragments rf) {
        return "kind=" + rf.kind() + " frags=" + rf.fragmentCount() + " collapsed=" + rf.isCollapsed();
    }
}
