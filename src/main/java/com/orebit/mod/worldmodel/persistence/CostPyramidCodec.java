package com.orebit.mod.worldmodel.persistence;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.orebit.mod.worldmodel.hpa.CostCodec;
import com.orebit.mod.worldmodel.hpa.CostPyramid;
import com.orebit.mod.worldmodel.hpa.PyramidMerger;
import com.orebit.mod.worldmodel.hpa.RegionAddress;
import com.orebit.mod.worldmodel.hpa.RegionFragments;
import com.orebit.mod.worldmodel.hpa.StraddleSet;

/**
 * On-disk (de)serializer for a dimension's {@link CostPyramid} fragment records — the cost half of the
 * world-model persistence arc (DESIGN-worldmodel-persistence.md §2, §7). <b>Stage-1 sharded format</b>: instead
 * of one whole-dimension {@code hpa.bin} carrying only the level-0 leaves, the cost pyramid is written as
 * Minecraft-{@code .mca}-style per-region SHARDS:
 * <ul>
 *   <li>{@code hpa.<X>.<Z>.bin} — cost levels <b>0..5</b> for the shard {@code (X = chunkX>>5, Z = chunkZ>>5)}.
 *       A shard is one level-5 region ({@link RegionAddress#sideOf}{@code (5) == 512} blocks == 32×32 chunks).
 *       Written by {@link #encodeShard}.</li>
 *   <li>{@code hpa.coarse.bin} — cost level <b>6</b> only ({@link RegionAddress#MAX_COARSE_LEVEL}), per-dimension.
 *       Written by {@link #encodeCoarse}.</li>
 * </ul>
 *
 * <h2>Why persist the coarse levels directly (the point of Stage 1)</h2>
 * The prior format persisted only level 0 and replayed {@link PyramidMerger#mergeUpFragments} on load to rebuild
 * the coarse pyramid. That re-merge is expensive on the tick thread (measured 34–880 ms/shard). Persisting
 * L0–L5 per shard and L6 per dimension makes reload a pure decode: {@link #decode} interns each row DIRECTLY at
 * its level and marks it built, with <b>no {@code mergeUp} replay</b>. A round-trip is lossless — persisted
 * coarse == recomputed coarse (proven by {@code RegionPersistenceRoundTripTest}).
 *
 * <h2>File format (both shard and coarse — a stack of per-level COLUMN-RUN sections, v3)</h2>
 * <pre>
 *   magic (int)                    \  header
 *   version (short)                /   OBHS = shard (L0-5), OBHC = coarse (L6); both distinct from the old OBHP blob
 *   --- body (uncompressed) ---
 *   levelCount (byte)                 number of level sections that follow (only non-empty levels are written)
 *   per level section:
 *     level (byte)                    the pyramid level these rows live at
 *     columnCount (int)               distinct (rx,rz) columns with built rows at this level in this file
 *     per column:
 *       rx (int) rz (int)             the column's region coords at this level
 *       runCount (unsigned short)     ry-runs in this column
 *       per run:
 *         ryStart (byte)              first ry of the run (0..31 from the dimension floor)
 *         ryLen (byte)                consecutive ry rows in the run (all share the byte-identical record below)
 *         recordLen (unsigned short)  bytes of the packed CostCodec bitstream that follow
 *         record (recordLen bytes)    CostCodec.packRegion(rf) output, shared by every ry in the run
 *   [shard files only] invalCount (int) = 0   reserved Stage-3 INVALIDATION section (empty stub)
 * </pre>
 * A run collapses rows that are BOTH consecutive in {@code ry} AND carry a byte-identical record — this is where
 * the redundant per-row coordinate headers (same rx/rz per column, sequential ry) are eliminated. On decode each
 * run is EXPANDED back to its individual {@code (rx, ry, rz, record)} rows and interned exactly as the flat format
 * did, so the round-trip is byte-identical at the row/record level (only the wire framing changed vs v2).
 * The reserved invalidation section is a forward-compatibility placeholder for Stage 3: it is the LAST thing in
 * a shard body, written as {@code int 0}, and {@link #decode} reads the count but ignores any entries — so a
 * future non-empty section's trailing bytes are simply not consumed and never break this reader.
 *
 * <h2>{@code gridSize} on decode (the per-level gotcha)</h2>
 * {@code gridSize} is not persisted (a build-time attribute). It is passed to {@link CostCodec#unpackRegion} per
 * level as {@link PyramidMerger#coarseG}{@code (level)} — {@code 16} at the leaf, {@code 4} at the coarse levels —
 * NOT the flat {@link RegionAddress#LEAF_SIZE} the old level-0-only codec hardcoded. (No query path reads
 * {@code RegionFragments.gridSize()} today, so this is belt-and-suspenders, but it keeps the record honest.)
 *
 * <h2>Cache semantics</h2>
 * Persisted data is a <b>cache</b>, never source of truth: a bad magic / version mismatch / truncation throws
 * {@link IOException}, and {@link RegionPersistence} treats the file as absent and lets the live world rebuild
 * it. {@link #decode} additionally honours "live world wins" — it never overwrites a row already
 * {@link CostPyramid#isBuilt built} this session.
 *
 * <p>Pure Java (plain streams + the MC-free {@link CostCodec}); no Minecraft API, so it is unit-testable with
 * no server. Cold path (server start/stop/periodic flush), so normal allocation is fine here. The body is written
 * UNCOMPRESSED — gzip inflate dominated shard-load cost (measured 62–71%) for only a ~2–3× on-disk saving.
 */
public final class CostPyramidCodec {

    private CostPyramidCodec() {}

    /** Shard-file magic — ASCII "OBHS" (Orebit HPA Shard, cost levels 0..5). */
    static final int MAGIC_SHARD = ('O' << 24) | ('B' << 16) | ('H' << 8) | 'S';
    /** Coarse-file magic — ASCII "OBHC" (Orebit HPA Coarse, cost level 6). */
    static final int MAGIC_COARSE = ('O' << 24) | ('B' << 16) | ('H' << 8) | 'C';
    /**
     * Schema version; bump on any incompatible layout change (old files then read as absent). v2 dropped gzip;
     * v3 replaced the flat per-row body with a COLUMN-RUN body (rows grouped by {@code (rx,rz)} column, consecutive
     * byte-identical records at consecutive {@code ry} collapsed into runs) — recovers ~97% of gzip's on-disk
     * saving (~2× vs the flat raw body) at raw decode speed, by killing the repeated per-row coordinate headers.
     */
    static final short VERSION = 3;

    /** Lowest / highest cost level carried by a per-region shard file. */
    static final int SHARD_LO_LEVEL = 0;
    static final int SHARD_HI_LEVEL = RegionAddress.SHARD_LEVEL; // 5

    // ---------------------------------------------------------------------------------------------------
    // Encode
    // ---------------------------------------------------------------------------------------------------

    /**
     * Write shard {@code (shardX, shardZ)}'s cost levels 0..5 to {@code rawOut} (header + body uncompressed), plus
     * the empty reserved invalidation section. Only built rows whose level-relative shard equals
     * {@code (shardX, shardZ)} are written; interned-but-unbuilt rows and rows with no fragment record are
     * skipped. The stream is left open for the caller to close.
     */
    public static void encodeShard(CostPyramid p, int shardX, int shardZ, OutputStream rawOut) throws IOException {
        encode(p, MAGIC_SHARD, SHARD_LO_LEVEL, SHARD_HI_LEVEL, true, shardX, shardZ, true, rawOut);
    }

    /**
     * Write the per-dimension coarse cost file — level {@link RegionAddress#MAX_COARSE_LEVEL} only, all built
     * rows (no shard scoping, no invalidation section). The stream is left open for the caller to close.
     */
    public static void encodeCoarse(CostPyramid p, OutputStream rawOut) throws IOException {
        encode(p, MAGIC_COARSE, RegionAddress.MAX_COARSE_LEVEL, RegionAddress.MAX_COARSE_LEVEL,
                false, 0, 0, false, rawOut);
    }

    private static void encode(CostPyramid p, int magic, int loLevel, int hiLevel,
                               boolean shardScoped, int shardX, int shardZ, boolean reserveInval,
                               OutputStream rawOut) throws IOException {
        // Build the column groupings for every level first (cold path — normal allocation is fine), then hand
        // them to the shared serializer. Factored out of the write loop so the scan-once-bucket path
        // ({@link #encodeShardBucket}) emits BYTE-IDENTICAL bytes: it feeds the same List<Column>[] shape into
        // the same {@link #writeEncoded}.
        int nLevels = hiLevel - loLevel + 1;
        @SuppressWarnings("unchecked")
        List<Column>[] byLevel = new List[nLevels];
        for (int level = loLevel; level <= hiLevel; level++) {
            byLevel[level - loLevel] = collectColumns(p, level, shardScoped, shardX, shardZ);
        }
        writeEncoded(new DataOutputStream(rawOut), magic, loLevel, hiLevel, byLevel, reserveInval);
    }

    /**
     * The single header + column-run serializer, shared by {@link #encode} (which gathers each level's columns
     * per shard via {@link #collectColumns}) and {@link #encodeShardBucket} (which gathers them for every shard
     * in ONE dimension pass). {@code byLevel} is indexed by {@code level - loLevel}; only non-empty levels are
     * written. Identical column lists in ⇒ identical bytes out, which is what makes the scan-once-bucket flush
     * byte-for-byte the same on disk as the per-shard {@link #encodeShard}.
     */
    private static void writeEncoded(DataOutputStream out, int magic, int loLevel, int hiLevel,
                                     List<Column>[] byLevel, boolean reserveInval) throws IOException {
        out.writeInt(magic);
        out.writeShort(VERSION);

        int levelCount = 0;
        for (List<Column> cols : byLevel) {
            if (!cols.isEmpty()) levelCount++;
        }
        out.writeByte(levelCount);

        for (int level = loLevel; level <= hiLevel; level++) {
            List<Column> cols = byLevel[level - loLevel];
            if (cols.isEmpty()) continue;
            out.writeByte(level);
            out.writeInt(cols.size());
            for (Column c : cols) {
                c.sortByRy();
                out.writeInt(c.rx);
                out.writeInt(c.rz);

                // Split the ry-sorted column into runs: consecutive ry AND byte-identical record collapse.
                int size = c.size();
                List<Integer> runFirst = new ArrayList<>();
                int i = 0;
                while (i < size) {
                    int j = i + 1;
                    while (j < size
                            && c.ryAt(j) == c.ryAt(j - 1) + 1
                            && Arrays.equals(c.recAt(j), c.recAt(i))) {
                        j++;
                    }
                    runFirst.add(i);
                    i = j;
                }
                out.writeShort(runFirst.size());
                for (int f = 0; f < runFirst.size(); f++) {
                    int s = runFirst.get(f);
                    int e = (f + 1 < runFirst.size()) ? runFirst.get(f + 1) : size;
                    byte[] rec = c.recAt(s);
                    out.writeByte(c.ryAt(s));   // ryStart
                    out.writeByte(e - s);       // ryLen (column ry-range is 0..31, so a run fits a byte)
                    out.writeShort(rec.length); // recordLen
                    out.write(rec);
                }
            }
        }

        // Reserved Stage-3 invalidation section (shard files only) — empty for now, forward-compatible.
        if (reserveInval) out.writeInt(0);

        out.flush();
    }

    // ---------------------------------------------------------------------------------------------------
    // Scan-once bucket — group every shard's rows in ONE dimension pass (kills the periodic flush's O(N*M))
    // ---------------------------------------------------------------------------------------------------

    /**
     * Bucket EVERY persistable built cost row (levels 0..5) by its level-5 shard key in ONE pass over the
     * dimension, instead of re-scanning the whole dimension once per shard (the old {@link #encodeShard} path
     * runs {@link #collectColumns} per level PER shard ⇒ O(dirtyShards × rows)). The periodic flush calls this
     * once per draining tick and writes each dirty shard from its bucket via {@link #encodeShardBucket} — the
     * emitted bytes are byte-identical to {@link #encodeShard} for the same shard (same {@link Column} grouping,
     * same insertion→iteration order, so the same column-run framing). The map key is the packed L5 shard key,
     * IDENTICAL to {@code RegionPersistence.shardKey(shardX, shardZ)}; only shards with ≥1 persistable row appear
     * (mirroring {@code RegionPersistence.enumerateCostShards}). Cold path — normal allocation is fine.
     */
    public static Map<Long, ShardColumns> bucketShards(CostPyramid p) {
        Map<Long, ShardColumns> shards = new HashMap<>();
        for (int level = SHARD_LO_LEVEL; level <= SHARD_HI_LEVEL; level++) {
            int rows = p.rowCount(level);
            for (int r = 0; r < rows; r++) {
                if (!p.isBuilt(level, r) || p.fragmentRecord(level, r) == null) continue;
                int rx = p.rowRX(level, r);
                int ry = p.rowRY(level, r);
                int rz = p.rowRZ(level, r);
                RegionFragments rf = p.fragmentRecord(level, r);
                int nbytes = (CostCodec.regionBitLength(rf) + 7) >> 3;
                byte[] buf = new byte[nbytes];
                CostCodec.packRegion(rf, buf, 0);

                long key = shardKeyOf(rx, rz, level);
                shards.computeIfAbsent(key, k -> new ShardColumns())
                      .add(level - SHARD_LO_LEVEL, rx, rz, ry, buf);
            }
        }
        return shards;
    }

    /**
     * Gather ONE shard's cost levels 0..5 into a {@link ShardColumns} via the pyramid's incremental per-shard row
     * index ({@link CostPyramid#shardRowCount}/{@link CostPyramid#shardRowAt}), <b>without scanning the dimension</b>
     * — the periodic-flush replacement for {@link #bucketShards} (which buckets EVERY shard in one {@code O(rows)}
     * dimension pass, re-run each draining tick). Returns {@code null} when the shard holds no persistable (built +
     * fragment-record) row (mirroring {@code RegionPersistence.enumerateCostShards}: such a shard writes no file).
     *
     * <p><b>Byte-identical to {@link #encodeShard}{@code (p, sx, sz, out)} for the same shard.</b> The index lists a
     * shard's rows in ascending intern order per level, which is exactly the order {@link #collectColumns} /
     * {@link #bucketShards} encounter that shard's rows in a full scan; the same rows are fed in the same order into
     * the same {@link ShardColumns} column grouping, and the write-time built-filter here matches theirs — so the
     * emitted bytes match. Cold path — normal allocation is fine.
     */
    public static ShardColumns bucketShard(CostPyramid p, long shardKey) {
        ShardColumns bucket = null;
        for (int level = SHARD_LO_LEVEL; level <= SHARD_HI_LEVEL; level++) {
            final int n = p.shardRowCount(shardKey, level);
            for (int i = 0; i < n; i++) {
                final int r = p.shardRowAt(shardKey, level, i);
                if (!p.isBuilt(level, r) || p.fragmentRecord(level, r) == null) continue;
                final int rx = p.rowRX(level, r);
                final int ry = p.rowRY(level, r);
                final int rz = p.rowRZ(level, r);
                final RegionFragments rf = p.fragmentRecord(level, r);
                final int nbytes = (CostCodec.regionBitLength(rf) + 7) >> 3;
                final byte[] buf = new byte[nbytes];
                CostCodec.packRegion(rf, buf, 0);
                if (bucket == null) bucket = new ShardColumns();
                bucket.add(level - SHARD_LO_LEVEL, rx, rz, ry, buf);
            }
        }
        return bucket;
    }

    /**
     * Write ONE shard's cost levels 0..5 from a {@link #bucketShards}/{@link #bucketShard} bucket — byte-identical to
     * {@link #encodeShard} for the same shard. The stream is left open for the caller to close.
     */
    public static void encodeShardBucket(ShardColumns bucket, OutputStream rawOut) throws IOException {
        writeEncoded(new DataOutputStream(rawOut), MAGIC_SHARD, SHARD_LO_LEVEL, SHARD_HI_LEVEL,
                bucket.toLists(), true);
    }

    /** Packed L5 shard key of a row at {@code level} — MUST match {@code RegionPersistence.shardKey}. */
    private static long shardKeyOf(int rx, int rz, int level) {
        long sx = RegionAddress.shardOf(rx, level);
        long sz = RegionAddress.shardOf(rz, level);
        return (sx << 32) | (sz & 0xFFFFFFFFL);
    }

    /**
     * One shard's per-level {@link Column} groupings — gathered by {@link #bucketShards}, consumed by
     * {@link #encodeShardBucket}. Opaque handle. Rows are added in row-scan order, so each per-level column map
     * ends up in the SAME insertion→iteration order {@link #collectColumns} produces for that shard, and the
     * encoded bytes therefore match the per-shard {@link #encodeShard} exactly.
     */
    public static final class ShardColumns {
        @SuppressWarnings("unchecked")
        private final Map<Long, Column>[] byLevel = new Map[SHARD_HI_LEVEL - SHARD_LO_LEVEL + 1];

        void add(int relLevel, int rx, int rz, int ry, byte[] rec) {
            Map<Long, Column> m = byLevel[relLevel];
            if (m == null) { m = new HashMap<>(); byLevel[relLevel] = m; }
            long colKey = (((long) rx) << 32) ^ (rz & 0xffffffffL); // matches collectColumns' column key
            Column col = m.get(colKey);
            if (col == null) { col = new Column(rx, rz); m.put(colKey, col); }
            col.add(ry, rec);
        }

        @SuppressWarnings("unchecked")
        List<Column>[] toLists() {
            List<Column>[] out = new List[byLevel.length];
            for (int i = 0; i < byLevel.length; i++) {
                out[i] = (byLevel[i] == null) ? Collections.<Column>emptyList()
                                              : new ArrayList<>(byLevel[i].values());
            }
            return out;
        }
    }

    /** Whether row {@code r} at {@code level} is a persistable built row matching the (optional) shard scope. */
    private static boolean matches(CostPyramid p, int level, int r, boolean shardScoped, int shardX, int shardZ) {
        if (!p.isBuilt(level, r) || p.fragmentRecord(level, r) == null) return false;
        if (!shardScoped) return true;
        return RegionAddress.shardOf(p.rowRX(level, r), level) == shardX
                && RegionAddress.shardOf(p.rowRZ(level, r), level) == shardZ;
    }

    /**
     * Group every persistable built row at {@code level} (matching the optional shard scope) into {@link Column}s
     * keyed by {@code (rx,rz)}, packing each row's {@link CostCodec} record eagerly. Column order is arbitrary
     * (decode expands + interns regardless of order); within a column rows are ry-sorted at write time.
     */
    private static List<Column> collectColumns(CostPyramid p, int level,
                                               boolean shardScoped, int shardX, int shardZ) {
        Map<Long, Column> byColumn = new HashMap<>();
        int rows = p.rowCount(level);
        for (int r = 0; r < rows; r++) {
            if (!matches(p, level, r, shardScoped, shardX, shardZ)) continue;
            int rx = p.rowRX(level, r);
            int ry = p.rowRY(level, r);
            int rz = p.rowRZ(level, r);
            RegionFragments rf = p.fragmentRecord(level, r);
            int bits = CostCodec.regionBitLength(rf);
            int nbytes = (bits + 7) >> 3;
            byte[] buf = new byte[nbytes];
            CostCodec.packRegion(rf, buf, 0);

            long key = (((long) rx) << 32) ^ (rz & 0xffffffffL);
            Column col = byColumn.get(key);
            if (col == null) {
                col = new Column(rx, rz);
                byColumn.put(key, col);
            }
            col.add(ry, buf);
        }
        return new ArrayList<>(byColumn.values());
    }

    /** One {@code (rx,rz)} column's built rows: parallel {@code ry} + packed-record arrays, ry-sortable. */
    private static final class Column {
        final int rx, rz;
        private int[] rys = new int[8];
        private byte[][] recs = new byte[8][];
        private int size = 0;

        Column(int rx, int rz) { this.rx = rx; this.rz = rz; }

        void add(int ry, byte[] rec) {
            if (size == rys.length) {
                rys = Arrays.copyOf(rys, size << 1);
                recs = Arrays.copyOf(recs, size << 1);
            }
            rys[size] = ry;
            recs[size] = rec;
            size++;
        }

        int size() { return size; }
        int ryAt(int i) { return rys[i]; }
        byte[] recAt(int i) { return recs[i]; }

        /** Insertion sort by ry (columns are tiny — ry range 0..31). Keeps ry + rec arrays in lockstep. */
        void sortByRy() {
            for (int i = 1; i < size; i++) {
                int ry = rys[i];
                byte[] rec = recs[i];
                int j = i - 1;
                while (j >= 0 && rys[j] > ry) {
                    rys[j + 1] = rys[j];
                    recs[j + 1] = recs[j];
                    j--;
                }
                rys[j + 1] = ry;
                recs[j + 1] = rec;
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------
    // Decode
    // ---------------------------------------------------------------------------------------------------

    /**
     * Read rows written by {@link #encodeShard} / {@link #encodeCoarse} from {@code rawIn} into {@code dest},
     * interning each row DIRECTLY at its stored level and marking it built — <b>no {@code mergeUp} replay</b>
     * (the coarse levels are persisted, so they load as-is). Honours "live world wins": a row already built in
     * {@code dest} this session is left untouched (its bytes are still consumed to keep the stream aligned).
     * Accepts either the shard or coarse magic. Throws {@link IOException} on a bad header / truncation (the
     * caller treats the file as absent).
     */
    public static void decode(InputStream rawIn, CostPyramid dest) throws IOException {
        decode(rawIn, dest, null);
    }

    /**
     * As {@link #decode(InputStream, CostPyramid)}, additionally reporting the <b>straddle set</b> (Stage-2
     * §2b reconciliation): whenever a <i>coarse</i> row (level {@code >= 1}) is SKIPPED by the live-world-wins
     * rule, its cell is recorded into {@code straddle} (when non-null) so a later {@code RegionReconciler} pass can
     * re-derive it now that this shard's persisted children are interned beside the live-partial coarse value.
     * Level-0 skips are NOT recorded (a live leaf always wins and has no children to reconcile). Passing
     * {@code null} is exactly the base {@link #decode(InputStream, CostPyramid)} — byte-for-byte the same interning
     * behaviour, the collector is a pure observation.
     */
    public static void decode(InputStream rawIn, CostPyramid dest, StraddleSet straddle) throws IOException {
        DataInputStream in = new DataInputStream(rawIn);
        int magic = in.readInt();
        int version = in.readUnsignedShort();
        if (magic != MAGIC_SHARD && magic != MAGIC_COARSE) {
            throw new IOException("bad cost-pyramid magic 0x" + Integer.toHexString(magic));
        }
        if (version != VERSION) {
            throw new IOException("unsupported cost-pyramid version " + version + " (expected " + VERSION + ")");
        }

        int levelCount = in.readUnsignedByte();
        for (int ls = 0; ls < levelCount; ls++) {
            int level = in.readUnsignedByte();
            int columnCount = in.readInt();
            if (columnCount < 0) throw new IOException("negative cost column count " + columnCount);
            // gridSize per level: 16 at the leaf, 4 at the coarse levels (NOT the flat LEAF_SIZE the old codec used).
            final int gridSize = PyramidMerger.coarseG(level);
            for (int cc = 0; cc < columnCount; cc++) {
                int rx = in.readInt();
                int rz = in.readInt();
                int runCount = in.readUnsignedShort();
                for (int ru = 0; ru < runCount; ru++) {
                    int ryStart = in.readUnsignedByte();
                    int ryLen = in.readUnsignedByte();
                    int nbytes = in.readUnsignedShort();
                    byte[] buf = new byte[nbytes];
                    in.readFully(buf);

                    // Expand the run back to individual rows and intern each exactly as the flat format did.
                    for (int k = 0; k < ryLen; k++) {
                        int ry = ryStart + k;

                        int existing = dest.rowIfPresent(level, rx, ry, rz);
                        if (existing != -1 && dest.isBuilt(level, existing)) {
                            // Live world wins (§6). A skipped COARSE row is a straddle to reconcile (§2b): its
                            // live-partial value stays, but this shard's persisted children are now interned beside
                            // it. Leaf skips need no reconcile (no children), so only record level >= 1.
                            if (straddle != null && level >= 1) straddle.add(level, rx, ry, rz);
                            continue;
                        }

                        int row = dest.rowFor(level, rx, ry, rz);
                        RegionFragments out = dest.ensureFragments(level, row);
                        CostCodec.unpackRegion(buf, 0, gridSize, out);
                        dest.setBuilt(level, row, true);
                    }
                }
            }
        }

        // Reserved Stage-3 invalidation section (shard files only, and always empty in Stage 1): read the entry
        // count if present and ignore any entries. Read defensively — a coarse file has no such section, and a
        // truncated/older shard simply has no trailing int; either way the cost rows above are already interned.
        if (magic == MAGIC_SHARD) {
            try {
                in.readInt(); // invalCount — Stage-1 always 0; entries (none yet) intentionally not consumed
            } catch (EOFException ignore) {
                // no invalidation section present — fine, nothing more to read
            }
        }
    }
}
