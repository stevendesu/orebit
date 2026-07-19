package com.orebit.mod.worldmodel.persistence;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.orebit.mod.worldmodel.hpa.RegionAddress;
import com.orebit.mod.worldmodel.hpa.StraddleSet;
import com.orebit.mod.worldmodel.resource.ResourceClasses;
import com.orebit.mod.worldmodel.resource.ResourcePyramid;

/**
 * On-disk (de)serializer for a dimension's {@link ResourcePyramid} tally rows — the resource half of the
 * world-model persistence arc (DESIGN-worldmodel-persistence.md §2, §7). The compass counterpart to
 * {@link CostPyramidCodec}, and, like it, <b>Stage-1 sharded</b>:
 * <ul>
 *   <li>{@code res.<X>.<Z>.bin} — resource levels <b>0..5</b> for the shard {@code (X = chunkX>>5, Z = chunkZ>>5)}
 *       (one level-5 region, 32×32 chunks). Written by {@link #encodeShard}.</li>
 *   <li>{@code res.coarse.bin} — resource levels <b>6..{@link ResourcePyramid#RESOURCE_TOP_LEVEL 21}</b>,
 *       per-dimension (the true-global tier {@code /bot find}/{@code /bot report} read). Written by
 *       {@link #encodeCoarse}.</li>
 * </ul>
 * The coarse levels are persisted directly so reload is a pure decode with <b>no {@code mergeUp} replay</b>
 * (the point of Stage 1 — see {@link CostPyramidCodec}).
 *
 * <h2>What a row holds</h2>
 * A row is a {@link ResourceClasses#COLUMN_COUNT}-wide {@code byte[]} of log₂ counts, but it is <b>sparse</b> — a
 * region gets a tally only where it holds ≥1 indexed block, and most columns are 0 — so each row is written as
 * {@code (col, log2val)} pairs over the non-zero columns only.
 *
 * <h2>File format (both shard and coarse — a stack of per-level sections)</h2>
 * <pre>
 *   magic (int)                    \
 *   version (short)                 |  header. OBRS = shard (L0-5), OBRC = coarse (L6-21).
 *   columnCount (byte)             /   the COLUMN_COUNT this was written with (forward-compat validation)
 *   --- body (uncompressed) ---
 *   levelCount (byte)                 number of level sections that follow (only non-empty levels are written)
 *   per level section:
 *     level (byte)
 *     rowCount (int)
 *     per row:
 *       rx (int) ry (byte) rz (int)
 *       nNonZero (byte)             number of non-zero columns that follow
 *       nNonZero * ( col (byte), log2val (byte) )
 * </pre>
 * The stored {@code columnCount} lets a decoder validate/zero-extend: {@link ResourceClasses} freezes column ids
 * by design, so a pair whose {@code col} is outside the running build's column range (a newer file on an older
 * build) is skipped rather than mis-applied.
 *
 * <h2>Cache semantics</h2>
 * Same as {@link CostPyramidCodec}: bad magic / version → {@link IOException} → treated as absent by
 * {@link RegionPersistence}; "live world wins" — a tally already built this session is not overwritten. Note the
 * persisted tally is a best-effort compass: block-change re-tally is not modelled (design §9), so a reloaded row
 * can be stale until its chunk reloads and {@code onChunkNavBuilt} overwrites it with truth.
 *
 * <p>Pure Java + the MC-free {@link ResourcePyramid}; unit-testable with no server. {@link ResourceClasses#COLUMN_COUNT}
 * is a compile-time constant, so referencing it does not force {@code ResourceClasses} class-init (which touches
 * the MC registry) — the codec and its test stay MC-free.
 */
public final class ResourcePyramidCodec {

    private ResourcePyramidCodec() {}

    /** Shard-file magic — ASCII "OBRS" (Orebit Resource Shard, levels 0..5). */
    static final int MAGIC_SHARD = ('O' << 24) | ('B' << 16) | ('R' << 8) | 'S';
    /** Coarse-file magic — ASCII "OBRC" (Orebit Resource Coarse, levels 6..21). */
    static final int MAGIC_COARSE = ('O' << 24) | ('B' << 16) | ('R' << 8) | 'C';
    /** Schema version; bump on any incompatible layout change. v2 dropped gzip. */
    static final short VERSION = 2;

    /** Indexed column count (24) — a compile-time constant, so this reference stays MC-free. */
    private static final int COLUMNS = ResourceClasses.COLUMN_COUNT;

    /** Lowest / highest resource level carried by a per-region shard file. */
    static final int SHARD_LO_LEVEL = 0;
    static final int SHARD_HI_LEVEL = RegionAddress.SHARD_LEVEL; // 5
    /** Lowest / highest resource level carried by the per-dimension coarse file. */
    static final int COARSE_LO_LEVEL = RegionAddress.MAX_COARSE_LEVEL;          // 6
    static final int COARSE_HI_LEVEL = ResourcePyramid.RESOURCE_TOP_LEVEL;      // 21

    // ---------------------------------------------------------------------------------------------------
    // Encode
    // ---------------------------------------------------------------------------------------------------

    /**
     * Write shard {@code (shardX, shardZ)}'s resource levels 0..5 to {@code rawOut} (header + body uncompressed),
     * each row as its non-zero {@code (col, log2val)} pairs. Only built rows whose level-relative shard equals
     * {@code (shardX, shardZ)} are written. The stream is left open for the caller to close.
     */
    public static void encodeShard(ResourcePyramid p, int shardX, int shardZ, OutputStream rawOut) throws IOException {
        encode(p, MAGIC_SHARD, SHARD_LO_LEVEL, SHARD_HI_LEVEL, true, shardX, shardZ, rawOut);
    }

    /**
     * Write the per-dimension coarse resource file — levels 6..21, all built rows (no shard scoping). The stream
     * is left open for the caller to close.
     */
    public static void encodeCoarse(ResourcePyramid p, OutputStream rawOut) throws IOException {
        encode(p, MAGIC_COARSE, COARSE_LO_LEVEL, COARSE_HI_LEVEL, false, 0, 0, rawOut);
    }

    private static void encode(ResourcePyramid p, int magic, int loLevel, int hiLevel,
                               boolean shardScoped, int shardX, int shardZ, OutputStream rawOut) throws IOException {
        // Gather each level's matching rows first (cold path — normal allocation is fine), then hand them to the
        // shared serializer. Factored out of the write loop so the scan-once-bucket path ({@link #encodeShardBucket})
        // emits BYTE-IDENTICAL bytes: it feeds the same List<Row>[] shape into the same {@link #writeEncoded}.
        int nLevels = hiLevel - loLevel + 1;
        @SuppressWarnings("unchecked")
        List<Row>[] byLevel = new List[nLevels];
        final byte[] vec = new byte[COLUMNS];
        for (int level = loLevel; level <= hiLevel; level++) {
            List<Row> list = new ArrayList<>();
            int rows = p.rowCount(level);
            for (int r = 0; r < rows; r++) {
                if (!matches(p, level, r, shardScoped, shardX, shardZ)) continue;
                p.readRow(level, r, vec);
                list.add(new Row(p.rowRX(level, r), p.rowRY(level, r), p.rowRZ(level, r), Arrays.copyOf(vec, COLUMNS)));
            }
            byLevel[level - loLevel] = list;
        }
        writeEncoded(new DataOutputStream(rawOut), magic, loLevel, byLevel);
    }

    /**
     * The single header + per-level-rows serializer, shared by {@link #encode} (which gathers each level's
     * matching rows per shard) and {@link #encodeShardBucket} (which gathers them for every shard in ONE
     * dimension pass). {@code byLevel} is indexed by {@code level - loLevel}; only non-empty levels are written.
     * Identical row lists in ⇒ identical bytes out — what makes the scan-once-bucket flush byte-for-byte the
     * same on disk as the per-shard {@link #encodeShard}.
     */
    private static void writeEncoded(DataOutputStream out, int magic, int loLevel, List<Row>[] byLevel) throws IOException {
        out.writeInt(magic);
        out.writeShort(VERSION);
        out.writeByte(COLUMNS);

        int levelCount = 0;
        for (List<Row> l : byLevel) {
            if (!l.isEmpty()) levelCount++;
        }
        out.writeByte(levelCount);

        for (int i = 0; i < byLevel.length; i++) {
            List<Row> list = byLevel[i];
            if (list.isEmpty()) continue;
            out.writeByte(loLevel + i);
            out.writeInt(list.size());
            for (Row row : list) {
                int nz = 0;
                for (int c = 0; c < COLUMNS; c++) {
                    if (row.vec[c] != 0) nz++;
                }
                out.writeInt(row.rx);
                out.writeByte(row.ry);
                out.writeInt(row.rz);
                out.writeByte(nz);
                for (int c = 0; c < COLUMNS; c++) {
                    if (row.vec[c] != 0) {
                        out.writeByte(c);
                        out.writeByte(row.vec[c]);
                    }
                }
            }
        }
        out.flush();
    }

    /** One persistable resource row: its region coords + a snapshot of its {@link #COLUMNS}-wide log₂ vector. */
    private static final class Row {
        final int rx, ry, rz;
        final byte[] vec;
        Row(int rx, int ry, int rz, byte[] vec) { this.rx = rx; this.ry = ry; this.rz = rz; this.vec = vec; }
    }

    // ---------------------------------------------------------------------------------------------------
    // Scan-once bucket — group every shard's rows in ONE dimension pass (kills the periodic flush's O(N*M))
    // ---------------------------------------------------------------------------------------------------

    /**
     * Bucket EVERY persistable built resource row (levels 0..5) by its level-5 shard key in ONE pass over the
     * dimension, instead of re-scanning the whole dimension once per shard (the old {@link #encodeShard} path).
     * The periodic flush calls this once per draining tick and writes each dirty shard from its bucket via
     * {@link #encodeShardBucket} — byte-identical to {@link #encodeShard} for the same shard (rows in the same
     * row-scan order). The map key is the packed L5 shard key, IDENTICAL to
     * {@code RegionPersistence.shardKey(shardX, shardZ)}; only shards with ≥1 built row appear. Cold path.
     */
    public static Map<Long, ShardRows> bucketShards(ResourcePyramid p) {
        Map<Long, ShardRows> shards = new HashMap<>();
        final byte[] vec = new byte[COLUMNS];
        for (int level = SHARD_LO_LEVEL; level <= SHARD_HI_LEVEL; level++) {
            int rows = p.rowCount(level);
            for (int r = 0; r < rows; r++) {
                if (!p.isBuilt(level, r)) continue;
                int rx = p.rowRX(level, r);
                int ry = p.rowRY(level, r);
                int rz = p.rowRZ(level, r);
                p.readRow(level, r, vec);
                long key = shardKeyOf(rx, rz, level);
                shards.computeIfAbsent(key, k -> new ShardRows())
                      .add(level - SHARD_LO_LEVEL, new Row(rx, ry, rz, Arrays.copyOf(vec, COLUMNS)));
            }
        }
        return shards;
    }

    /**
     * Write ONE shard's resource levels 0..5 from a {@link #bucketShards} bucket — byte-identical to
     * {@link #encodeShard} for the same shard. The stream is left open for the caller to close.
     */
    public static void encodeShardBucket(ShardRows bucket, OutputStream rawOut) throws IOException {
        writeEncoded(new DataOutputStream(rawOut), MAGIC_SHARD, SHARD_LO_LEVEL, bucket.toLists());
    }

    /** Packed L5 shard key of a row at {@code level} — MUST match {@code RegionPersistence.shardKey}. */
    private static long shardKeyOf(int rx, int rz, int level) {
        long sx = RegionAddress.shardOf(rx, level);
        long sz = RegionAddress.shardOf(rz, level);
        return (sx << 32) | (sz & 0xFFFFFFFFL);
    }

    /**
     * One shard's per-level rows — gathered by {@link #bucketShards}, consumed by {@link #encodeShardBucket}.
     * Opaque handle. Rows are appended in row-scan order (the same order {@link #encode} writes them), so the
     * encoded bytes match the per-shard {@link #encodeShard} exactly.
     */
    public static final class ShardRows {
        @SuppressWarnings("unchecked")
        private final List<Row>[] byLevel = new List[SHARD_HI_LEVEL - SHARD_LO_LEVEL + 1];

        void add(int relLevel, Row row) {
            List<Row> l = byLevel[relLevel];
            if (l == null) { l = new ArrayList<>(); byLevel[relLevel] = l; }
            l.add(row);
        }

        @SuppressWarnings("unchecked")
        List<Row>[] toLists() {
            List<Row>[] out = new List[byLevel.length];
            for (int i = 0; i < byLevel.length; i++) {
                out[i] = (byLevel[i] == null) ? Collections.<Row>emptyList() : byLevel[i];
            }
            return out;
        }
    }

    /** Whether row {@code r} at {@code level} is a persistable built row matching the (optional) shard scope. */
    private static boolean matches(ResourcePyramid p, int level, int r, boolean shardScoped, int shardX, int shardZ) {
        if (!p.isBuilt(level, r)) return false;
        if (!shardScoped) return true;
        return RegionAddress.shardOf(p.rowRX(level, r), level) == shardX
                && RegionAddress.shardOf(p.rowRZ(level, r), level) == shardZ;
    }

    // ---------------------------------------------------------------------------------------------------
    // Decode
    // ---------------------------------------------------------------------------------------------------

    /**
     * Read tally rows written by {@link #encodeShard} / {@link #encodeCoarse} from {@code rawIn} into
     * {@code dest}, interning each row DIRECTLY at its stored level and marking it built — <b>no {@code mergeUp}
     * replay</b>. Honours "live world wins" (a tally already built this session is left untouched) and
     * forward-compat (a column id ≥ the running build's {@link #COLUMNS} is skipped). Accepts either the shard or
     * coarse magic. Throws {@link IOException} on a bad header / truncation.
     */
    public static void decode(InputStream rawIn, ResourcePyramid dest) throws IOException {
        decode(rawIn, dest, null);
    }

    /**
     * As {@link #decode(InputStream, ResourcePyramid)}, additionally reporting the <b>straddle set</b> (Stage-2
     * §2b reconciliation): a <i>coarse</i> tally row (level {@code >= 1}) SKIPPED by live-world-wins is recorded
     * into {@code straddle} (when non-null) so a later {@code RegionReconciler} can re-sum it now that this shard's
     * persisted child tallies are interned. Level-0 skips are NOT recorded. Passing {@code null} is exactly the
     * base decode — the collector is a pure observation, interning is unchanged.
     */
    public static void decode(InputStream rawIn, ResourcePyramid dest, StraddleSet straddle) throws IOException {
        DataInputStream in = new DataInputStream(rawIn);
        int magic = in.readInt();
        int version = in.readUnsignedShort();
        in.readUnsignedByte(); // stored columnCount — read for stream alignment (per-pair col is self-guarding)
        if (magic != MAGIC_SHARD && magic != MAGIC_COARSE) {
            throw new IOException("bad resource-pyramid magic 0x" + Integer.toHexString(magic));
        }
        if (version != VERSION) {
            throw new IOException("unsupported resource-pyramid version " + version + " (expected " + VERSION + ")");
        }

        int levelCount = in.readUnsignedByte();
        for (int ls = 0; ls < levelCount; ls++) {
            int level = in.readUnsignedByte();
            int count = in.readInt();
            if (count < 0) throw new IOException("negative resource row count " + count);
            for (int i = 0; i < count; i++) {
                int rx = in.readInt();
                int ry = in.readUnsignedByte();
                int rz = in.readInt();
                int nz = in.readUnsignedByte();

                int existing = dest.rowIfPresent(level, rx, ry, rz);
                boolean skip = existing != -1 && dest.isBuilt(level, existing); // live world wins (§6)
                // A skipped COARSE tally is a straddle to reconcile (§2b); leaf skips need none (no children).
                if (skip && straddle != null && level >= 1) straddle.add(level, rx, ry, rz);
                int row = skip ? -1 : dest.rowFor(level, rx, ry, rz);

                for (int j = 0; j < nz; j++) {
                    int col = in.readUnsignedByte();
                    byte val = in.readByte();
                    if (!skip && col < COLUMNS) {
                        dest.setLog2(level, row, col, val);
                    }
                }
                if (!skip) dest.setBuilt(level, row, true);
            }
        }
    }
}
