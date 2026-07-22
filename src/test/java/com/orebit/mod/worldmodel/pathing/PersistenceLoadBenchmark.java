package com.orebit.mod.worldmodel.pathing;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
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

import com.orebit.mod.worldmodel.hpa.CostCodec;
import com.orebit.mod.worldmodel.hpa.CostPyramid;
import com.orebit.mod.worldmodel.hpa.FragmentLeafComputer;
import com.orebit.mod.worldmodel.hpa.PyramidMerger;
import com.orebit.mod.worldmodel.hpa.RegionAddress;
import com.orebit.mod.worldmodel.hpa.RegionFragments;
import com.orebit.mod.worldmodel.persistence.CostPyramidCodec;
import com.orebit.mod.worldmodel.persistence.ResourcePyramidCodec;
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
 * <b>Stage-1 region-persistence LOAD-PATH payoff</b> — the A/B that sizes the win of persisting the coarse
 * pyramid levels directly vs. recomputing them on load. One <i>shard</i> is a level-5 region (32×32 chunks =
 * 1024 columns × 24 sections); this bench builds a fully-explored shard pyramid (cost + resource), serializes it
 * two ways in {@link #setup} (untimed), and times the two RELOAD paths a server-start / lazy-page load runs on the
 * tick thread:
 * <ul>
 *   <li>{@link #loadRecompute} — the OLD path: decode ONLY the level-0 leaves ({@code L0}-only bytes), then replay
 *       {@link PyramidMerger#mergeUpFragments} (cost) + {@link ResourceMerger#mergeUpTallies} (resource) to rebuild
 *       levels 1..6 / 1..21. Decode + full roll-up.</li>
 *   <li>{@link #loadDirect} — the NEW Stage-1 path: decode the {@code L0-5} shard bytes ({@link
 *       CostPyramidCodec#encodeShard}) into a fresh pyramid with <b>no {@code mergeUp}</b>. Pure decode.</li>
 * </ul>
 * The {@code recompute − direct} delta is the tick-thread cost Stage-1 removes; {@code loadDirect}'s absolute
 * ms/op is what a lazy-load tick budget ({@code pathing.regionShardLoadBudgetMs}) must accommodate per shard.
 *
 * <p>The shard-construction reuses {@link ChunkBuildBenchmark}'s pattern: one 24-section column is classified once
 * and interned at all 1024 region coordinates of the shard (the terrain SHAPE per section is what drives the
 * codec/merge cost; the leaf record is identical across columns, exactly as {@code shardLeaf}/{@code shardRollup}
 * do). Byte arrays are held in memory — the physical disk read is OS-page-cached and negligible; this bench
 * isolates the CPU cost of decode ± {@code mergeUp}, which is the tick-thread concern.
 *
 * <p>Run (JDK 21, active 1.21.11 node): {@code ./gradlew "Set active project to 1.21.11"} then
 * {@code ./gradlew :1.21.11:jmh -Pbench=PersistenceLoadBenchmark -Pscenario=SURFACE,CAVE}. Output is ms/op = the
 * per-shard load cost.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2) // overridden to forks(0) by BenchmarkRunnerTest (must stay in the bootstrapped-MC JVM)
public class PersistenceLoadBenchmark {

    private static final int MIN_Y = -64;
    private static final int SECTIONS = 24;
    private static final int COLS = ResourceClasses.COLUMN_COUNT;

    /** One persistence shard = one level-5 region = 32×32 chunk columns. */
    private static final int SHARD = 32;

    /** Base region (== chunk, at level 0) coords, shard-aligned so shardOf(coord,0) == 0 for the whole block. */
    private static final int BASE_RX = 0, BASE_RZ = 0;

    private static final int CAVE_AIR_PERMIL = 350;
    private static final int ORE_PERMIL = 15;

    @Param({"UNIFORM_AIR", "UNIFORM_SOLID", "SURFACE", "CAVE"})
    private String scenario;

    // ---- Terrain / classify fixtures (built once per @Param) ----
    private PalettedContainer<BlockState>[] terrain;
    private boolean[] onlyAir;
    private NavSection[] classified;
    private byte[][] tallyBytes;
    private int[] resScratch;

    // ---- The two serialized forms of the SAME fully-explored shard (built in setup, NOT timed) ----
    /** Cost + resource shard bytes carrying ONLY level 0 (the pre-Stage-1 on-disk format). */
    private byte[] costL0Bytes, resL0Bytes;
    /** Cost + resource shard bytes carrying levels 0..5 (the Stage-1 {@code encodeShard} format). */
    private byte[] costFullBytes, resFullBytes;
    /**
     * The SAME L0-5 shard body as {@link #costFullBytes}/{@link #resFullBytes} — identical header + per-level
     * sections + {@code CostCodec} bitstream / resource-pair records — but written to a PLAIN stream (no gzip
     * layer). The {@code loadDirect − loadDirectNoGzip} time delta isolates the gzip-inflate cost; the
     * {@code costFullBytes.length − costFullNoGzipBytes.length} size delta isolates gzip's on-disk saving. Built
     * benchmark-locally by {@link #encodeCostNoGzip}/{@link #encodeResNoGzip} (a byte-for-byte replica of the
     * production {@code encode} body minus the {@code GZIPOutputStream} wrap).
     */
    private byte[] costFullNoGzipBytes, resFullNoGzipBytes;

    private static boolean bootstrapped = false;

    private static PalettedContainer<BlockState> newContainer() {
        return new PalettedContainer<>(
                Blocks.AIR.defaultBlockState(), Strategy.createForBlockStates(Block.BLOCK_STATE_REGISTRY));
    }

    @SuppressWarnings("unchecked")
    @Setup(Level.Trial)
    public void setup() throws IOException {
        if (!bootstrapped) {
            SharedConstants.tryDetectVersion();
            Bootstrap.bootStrap();
            bootstrapped = true;
        }

        terrain = new PalettedContainer[SECTIONS];
        onlyAir = new boolean[SECTIONS];
        buildTerrain();

        // Classify ONE column and capture its per-section resource tallies (the shard interns this everywhere).
        resScratch = new int[COLS];
        classified = new NavSection[SECTIONS];
        tallyBytes = new byte[SECTIONS][];
        for (int ry = 0; ry < SECTIONS; ry++) {
            classified[ry] = NavSection.create(new BlockPos(BASE_RX << 4, MIN_Y + ry * 16, BASE_RZ << 4));
        }
        classifyColumn();

        // Build the fully-explored shard pyramids: leaf + full mergeUp (cost → L6, resource → L21).
        CostPyramid fullCost = new CostPyramid();
        ResourcePyramid fullRes = new ResourcePyramid();
        // ...and an L0-only pyramid (leaf, NO mergeUp) — encodeShard on it emits only the level-0 section.
        CostPyramid l0Cost = new CostPyramid();
        ResourcePyramid l0Res = new ResourcePyramid();

        for (int cx = 0; cx < SHARD; cx++) {
            for (int cz = 0; cz < SHARD; cz++) {
                final int rx = BASE_RX + cx, rz = BASE_RZ + cz;
                for (int ry = 0; ry < SECTIONS; ry++) {
                    // full cost leaf
                    int rf = fullCost.rowFor(0, rx, ry, rz);
                    FragmentLeafComputer.computeLeaf(classified[ry], fullCost.ensureFragments(0, rf));
                    fullCost.setBuilt(0, rf, true);
                    // l0-only cost leaf
                    int rl = l0Cost.rowFor(0, rx, ry, rz);
                    FragmentLeafComputer.computeLeaf(classified[ry], l0Cost.ensureFragments(0, rl));
                    l0Cost.setBuilt(0, rl, true);
                    // resource rows (sparse: only resource-bearing sections)
                    byte[] tally = tallyBytes[ry];
                    if (tally != null) {
                        int rrf = fullRes.rowFor(0, rx, ry, rz);
                        fullRes.setRow(0, rrf, tally); fullRes.setBuilt(0, rrf, true);
                        int rrl = l0Res.rowFor(0, rx, ry, rz);
                        l0Res.setRow(0, rrl, tally); l0Res.setBuilt(0, rrl, true);
                    }
                }
                // roll the full pyramid up per leaf (production's chunk-load path; damping makes it sub-linear).
                for (int ry = 0; ry < SECTIONS; ry++) {
                    PyramidMerger.mergeUpFragments(fullCost, rx, ry, rz);
                    if (tallyBytes[ry] != null) ResourceMerger.mergeUpTallies(fullRes, rx, ry, rz);
                }
            }
        }

        // Serialize both forms of shard (0,0). Header raw + gzip body — exactly what hits disk.
        costL0Bytes = encodeShardBytes(true, l0Cost, null);
        resL0Bytes  = encodeShardBytes(true, null, l0Res);
        costFullBytes = encodeShardBytes(true, fullCost, null);
        resFullBytes  = encodeShardBytes(true, null, fullRes);

        // Same L0-5 body, NO gzip layer — for the space + gzip-inflate-time A/B.
        costFullNoGzipBytes = encodeCostNoGzip(fullCost);
        resFullNoGzipBytes  = encodeResNoGzip(fullRes);

        System.out.printf(
            "[PersistenceLoadBenchmark] %s shard OK: costL0=%d B costL0-5=%d B  resL0=%d B resL0-5=%d B%n",
            scenario, costL0Bytes.length, costFullBytes.length, resL0Bytes.length, resFullBytes.length);
        // SPACE A/B: gzipped L0-5 shard vs the identical body written uncompressed.
        System.out.printf(
            "[PersistenceLoadBenchmark] %s SPACE: cost gzip=%d B nogzip=%d B (ratio %.3f, +%d B)  "
            + "res gzip=%d B nogzip=%d B (ratio %.3f, +%d B)  total gzip=%d B nogzip=%d B (ratio %.3f, +%d B)%n",
            scenario,
            costFullBytes.length, costFullNoGzipBytes.length,
            (double) costFullBytes.length / costFullNoGzipBytes.length,
            costFullNoGzipBytes.length - costFullBytes.length,
            resFullBytes.length, resFullNoGzipBytes.length,
            (double) resFullBytes.length / resFullNoGzipBytes.length,
            resFullNoGzipBytes.length - resFullBytes.length,
            costFullBytes.length + resFullBytes.length,
            costFullNoGzipBytes.length + resFullNoGzipBytes.length,
            (double) (costFullBytes.length + resFullBytes.length)
                    / (costFullNoGzipBytes.length + resFullNoGzipBytes.length),
            (costFullNoGzipBytes.length + resFullNoGzipBytes.length)
                    - (costFullBytes.length + resFullBytes.length));
    }

    private static byte[] encodeShardBytes(boolean shard, CostPyramid cp, ResourcePyramid rp) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (cp != null) CostPyramidCodec.encodeShard(cp, 0, 0, bos);
        else ResourcePyramidCodec.encodeShard(rp, 0, 0, bos);
        return bos.toByteArray();
    }

    // ===================================================================================================
    // No-gzip shard (de)serialization — a byte-for-byte replica of CostPyramidCodec/ResourcePyramidCodec's
    // encodeShard/decode BODY, but the header AND body are written to a plain stream (the gzip wrap is the
    // ONLY thing removed). Kept benchmark-local so no production seam is needed; the record/bit format is
    // untouched, so the readBits bit-unpack + interning work in decodeCostNoGzip/decodeResNoGzip is identical
    // to loadDirect's — the ONLY delta vs loadDirect is skipping GZIPInputStream inflate / GZIPOutputStream.
    // Shard scope is (0,0) — matching encodeShardBytes(...,0,0,...) — so shardOf() == 0 selects every built row.
    // ===================================================================================================

    private static final int COST_MAGIC_SHARD = ('O' << 24) | ('B' << 16) | ('H' << 8) | 'S';
    private static final int RES_MAGIC_SHARD  = ('O' << 24) | ('B' << 16) | ('R' << 8) | 'S';
    private static final short SHARD_VERSION = 1;
    private static final int SHARD_LO = 0;
    private static final int SHARD_HI = RegionAddress.SHARD_LEVEL; // 5

    private static boolean costMatches(CostPyramid p, int level, int r) {
        if (!p.isBuilt(level, r) || p.fragmentRecord(level, r) == null) return false;
        return RegionAddress.shardOf(p.rowRX(level, r), level) == 0
                && RegionAddress.shardOf(p.rowRZ(level, r), level) == 0;
    }

    private static int costCountRows(CostPyramid p, int level) {
        int rows = p.rowCount(level), n = 0;
        for (int r = 0; r < rows; r++) if (costMatches(p, level, r)) n++;
        return n;
    }

    /** Encode cost levels 0..5 for shard (0,0) exactly as {@code CostPyramidCodec.encodeShard} — but NO gzip. */
    private static byte[] encodeCostNoGzip(CostPyramid p) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeInt(COST_MAGIC_SHARD);
        out.writeShort(SHARD_VERSION);
        int levelCount = 0;
        for (int level = SHARD_LO; level <= SHARD_HI; level++) if (costCountRows(p, level) > 0) levelCount++;
        out.writeByte(levelCount);
        for (int level = SHARD_LO; level <= SHARD_HI; level++) {
            int n = costCountRows(p, level);
            if (n == 0) continue;
            out.writeByte(level);
            out.writeInt(n);
            int rows = p.rowCount(level);
            for (int r = 0; r < rows; r++) {
                if (!costMatches(p, level, r)) continue;
                RegionFragments rf = p.fragmentRecord(level, r);
                int bits = CostCodec.regionBitLength(rf);
                int nbytes = (bits + 7) >> 3;
                byte[] buf = new byte[nbytes];
                CostCodec.packRegion(rf, buf, 0);
                out.writeInt(p.rowRX(level, r));
                out.writeByte(p.rowRY(level, r));
                out.writeInt(p.rowRZ(level, r));
                out.writeShort(nbytes);
                out.write(buf);
            }
        }
        out.writeInt(0); // reserved Stage-3 invalidation section (empty), as encodeShard writes
        out.flush();
        return bos.toByteArray();
    }

    /** Decode {@link #encodeCostNoGzip} output — identical interning to {@code CostPyramidCodec.decode}, no inflate. */
    private static void decodeCostNoGzip(byte[] bytes, CostPyramid dest) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        in.readInt();            // magic
        in.readUnsignedShort();  // version
        int levelCount = in.readUnsignedByte();
        for (int ls = 0; ls < levelCount; ls++) {
            int level = in.readUnsignedByte();
            int count = in.readInt();
            final int gridSize = PyramidMerger.coarseG(level);
            for (int i = 0; i < count; i++) {
                int rx = in.readInt();
                int ry = in.readUnsignedByte();
                int rz = in.readInt();
                int nbytes = in.readUnsignedShort();
                byte[] buf = new byte[nbytes];
                in.readFully(buf);
                int existing = dest.rowIfPresent(level, rx, ry, rz);
                if (existing != -1 && dest.isBuilt(level, existing)) continue;
                int row = dest.rowFor(level, rx, ry, rz);
                RegionFragments out = dest.ensureFragments(level, row);
                CostCodec.unpackRegion(buf, 0, gridSize, out);
                dest.setBuilt(level, row, true);
            }
        }
        in.readInt(); // invalCount (0)
    }

    private static boolean resMatches(ResourcePyramid p, int level, int r) {
        if (!p.isBuilt(level, r)) return false;
        return RegionAddress.shardOf(p.rowRX(level, r), level) == 0
                && RegionAddress.shardOf(p.rowRZ(level, r), level) == 0;
    }

    private static int resCountRows(ResourcePyramid p, int level) {
        int rows = p.rowCount(level), n = 0;
        for (int r = 0; r < rows; r++) if (resMatches(p, level, r)) n++;
        return n;
    }

    /** Encode resource levels 0..5 for shard (0,0) exactly as {@code ResourcePyramidCodec.encodeShard} — NO gzip. */
    private static byte[] encodeResNoGzip(ResourcePyramid p) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeInt(RES_MAGIC_SHARD);
        out.writeShort(SHARD_VERSION);
        out.writeByte(COLS); // stored columnCount
        int levelCount = 0;
        for (int level = SHARD_LO; level <= SHARD_HI; level++) if (resCountRows(p, level) > 0) levelCount++;
        out.writeByte(levelCount);
        final byte[] vec = new byte[COLS];
        for (int level = SHARD_LO; level <= SHARD_HI; level++) {
            int n = resCountRows(p, level);
            if (n == 0) continue;
            out.writeByte(level);
            out.writeInt(n);
            int rows = p.rowCount(level);
            for (int r = 0; r < rows; r++) {
                if (!resMatches(p, level, r)) continue;
                p.readRow(level, r, vec);
                int nz = 0;
                for (int c = 0; c < COLS; c++) if (vec[c] != 0) nz++;
                out.writeInt(p.rowRX(level, r));
                out.writeByte(p.rowRY(level, r));
                out.writeInt(p.rowRZ(level, r));
                out.writeByte(nz);
                for (int c = 0; c < COLS; c++) {
                    if (vec[c] != 0) {
                        out.writeByte(c);
                        out.writeByte(vec[c]);
                    }
                }
            }
        }
        out.flush();
        return bos.toByteArray();
    }

    /** Decode {@link #encodeResNoGzip} output — identical interning to {@code ResourcePyramidCodec.decode}, no inflate. */
    private static void decodeResNoGzip(byte[] bytes, ResourcePyramid dest) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        in.readInt();            // magic
        in.readUnsignedShort();  // version
        in.readUnsignedByte();   // stored columnCount
        int levelCount = in.readUnsignedByte();
        for (int ls = 0; ls < levelCount; ls++) {
            int level = in.readUnsignedByte();
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                int rx = in.readInt();
                int ry = in.readUnsignedByte();
                int rz = in.readInt();
                int nz = in.readUnsignedByte();
                int existing = dest.rowIfPresent(level, rx, ry, rz);
                boolean skip = existing != -1 && dest.isBuilt(level, existing);
                int row = skip ? -1 : dest.rowFor(level, rx, ry, rz);
                for (int j = 0; j < nz; j++) {
                    int col = in.readUnsignedByte();
                    byte val = in.readByte();
                    if (!skip && col < COLS) dest.setLog2(level, row, col, val);
                }
                if (!skip) dest.setBuilt(level, row, true);
            }
        }
    }

    // ===================================================================================================
    // Benchmarks
    // ===================================================================================================

    /** OLD reload: decode level-0 leaves, then replay mergeUp to rebuild the coarse pyramid (cost + resource). */
    @Benchmark
    public void loadRecompute(Blackhole bh) throws IOException {
        CostPyramid cp = new CostPyramid();
        CostPyramidCodec.decode(new ByteArrayInputStream(costL0Bytes), cp);
        int crows = cp.rowCount(0);
        for (int r = 0; r < crows; r++) {
            if (cp.isBuilt(0, r)) {
                PyramidMerger.mergeUpFragments(cp, cp.rowRX(0, r), cp.rowRY(0, r), cp.rowRZ(0, r));
            }
        }

        ResourcePyramid rp = new ResourcePyramid();
        ResourcePyramidCodec.decode(new ByteArrayInputStream(resL0Bytes), rp);
        int rrows = rp.rowCount(0);
        for (int r = 0; r < rrows; r++) {
            if (rp.isBuilt(0, r)) {
                ResourceMerger.mergeUpTallies(rp, rp.rowRX(0, r), rp.rowRY(0, r), rp.rowRZ(0, r));
            }
        }

        bh.consume(cp);
        bh.consume(rp);
    }

    /** NEW Stage-1 reload: decode levels 0..5 directly — no mergeUp (cost + resource). */
    @Benchmark
    public void loadDirect(Blackhole bh) throws IOException {
        CostPyramid cp = new CostPyramid();
        CostPyramidCodec.decode(new ByteArrayInputStream(costFullBytes), cp);

        ResourcePyramid rp = new ResourcePyramid();
        ResourcePyramidCodec.decode(new ByteArrayInputStream(resFullBytes), rp);

        bh.consume(cp);
        bh.consume(rp);
    }

    /**
     * IDENTICAL to {@link #loadDirect} except the L0-5 shard body is read from a PLAIN stream instead of through
     * a {@link java.util.zip.GZIPInputStream} — same {@code CostCodec.readBits} bit-unpack, same open-addressed
     * interning, same allocation. The {@code loadDirect − loadDirectNoGzip} delta is therefore the pure
     * gzip-inflate fraction of shard-load cost; {@code loadDirectNoGzip}'s own ms/op is the residual
     * bit-unpack + interning floor with gzip removed.
     */
    @Benchmark
    public void loadDirectNoGzip(Blackhole bh) throws IOException {
        CostPyramid cp = new CostPyramid();
        decodeCostNoGzip(costFullNoGzipBytes, cp);

        ResourcePyramid rp = new ResourcePyramid();
        decodeResNoGzip(resFullNoGzipBytes, rp);

        bh.consume(cp);
        bh.consume(rp);
    }

    // ===================================================================================================
    // Terrain construction + classify (mirrors ChunkBuildBenchmark)
    // ===================================================================================================

    private void buildTerrain() {
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState dirt = Blocks.DIRT.defaultBlockState();
        BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
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
                        for (int y = 0; y <= 7; y++) surf.getAndSet(x, y, z, stone);
                        for (int y = 8; y <= 10; y++) surf.getAndSet(x, y, z, dirt);
                        surf.getAndSet(x, 11, z, grass);
                    }
                terrain[8] = surf; onlyAir[8] = false;
                for (int ry = 9; ry < SECTIONS; ry++) { terrain[ry] = null; onlyAir[ry] = true; }
                break;
            }

            case "CAVE": {
                long lcg = 0xCAFE_BABE_DEAD_BEEFL;
                for (int ry = 0; ry < SECTIONS; ry++) {
                    PalettedContainer<BlockState> c = newContainer();
                    for (int y = 0; y < 16; y++)
                        for (int z = 0; z < 16; z++)
                            for (int x = 0; x < 16; x++) {
                                lcg = adv(lcg);
                                if (permil(lcg, CAVE_AIR_PERMIL)) continue;
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

    private void classifyColumn() {
        for (int ry = 0; ry < SECTIONS; ry++) {
            java.util.Arrays.fill(resScratch, 0);
            NavSectionBuilder.classifyNavtypes(terrain[ry], onlyAir[ry], classified[ry].getTraversalGrid(), null,
                    resScratch);
            byte[] tally = encodeTally(resScratch);
            classified[ry].setResourceTally(tally);
            tallyBytes[ry] = tally;
        }
        for (int ry = 0; ry < SECTIONS; ry++) {
            NavSection above = ry + 1 < SECTIONS ? classified[ry + 1] : null;
            NavSectionBuilder.computeFlags(classified[ry].getTraversalGrid(), onlyAir[ry],
                    (above == null || onlyAir[ry + 1]) ? null : above.getTraversalGrid());
        }
        NavSectionBuilder.computeDepth(classified);
    }

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

    private static void fill(PalettedContainer<BlockState> c, BlockState s) {
        for (int y = 0; y < 16; y++)
            for (int z = 0; z < 16; z++)
                for (int x = 0; x < 16; x++)
                    c.getAndSet(x, y, z, s);
    }

    private static long adv(long s) {
        return s * 6364136223846793005L + 1442695040888963407L;
    }

    private static boolean permil(long s, int permil) {
        return Math.floorMod(s >>> 40, 1000) < permil;
    }

    private static int oreIdx(long s, int n) {
        return (int) Math.floorMod(s >>> 20, n);
    }
}
