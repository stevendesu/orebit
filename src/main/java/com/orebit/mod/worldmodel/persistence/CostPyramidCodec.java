package com.orebit.mod.worldmodel.persistence;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.orebit.mod.worldmodel.hpa.CostCodec;
import com.orebit.mod.worldmodel.hpa.CostPyramid;
import com.orebit.mod.worldmodel.hpa.PyramidMerger;
import com.orebit.mod.worldmodel.hpa.RegionAddress;
import com.orebit.mod.worldmodel.hpa.RegionFragments;

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
 * <h2>File format (both shard and coarse — a stack of per-level sections)</h2>
 * <pre>
 *   magic (int)                    \  header — UNCOMPRESSED so a reader validates before inflating
 *   version (short)                /   OBHS = shard (L0-5), OBHC = coarse (L6); both distinct from the old OBHP blob
 *   --- gzip(body) ---
 *   levelCount (byte)                 number of level sections that follow (only non-empty levels are written)
 *   per level section:
 *     level (byte)                    the pyramid level these rows live at
 *     rowCount (int)                  built rows at this level in this file
 *     per row:
 *       rx (int) ry (byte) rz (int)   region coords at this level (ry is 0..31 from the dimension floor)
 *       recordLen (unsigned short)    bytes of the packed CostCodec bitstream that follow
 *       record (recordLen bytes)      CostCodec.packRegion(rf) output
 *   [shard files only] invalCount (int) = 0   reserved Stage-3 INVALIDATION section (empty stub)
 * </pre>
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
 * <p>Pure Java (streams + gzip + the MC-free {@link CostCodec}); no Minecraft API, so it is unit-testable with
 * no server. Cold path (server start/stop/periodic flush), so normal allocation is fine here.
 */
public final class CostPyramidCodec {

    private CostPyramidCodec() {}

    /** Shard-file magic — ASCII "OBHS" (Orebit HPA Shard, cost levels 0..5). */
    static final int MAGIC_SHARD = ('O' << 24) | ('B' << 16) | ('H' << 8) | 'S';
    /** Coarse-file magic — ASCII "OBHC" (Orebit HPA Coarse, cost level 6). */
    static final int MAGIC_COARSE = ('O' << 24) | ('B' << 16) | ('H' << 8) | 'C';
    /** Schema version; bump on any incompatible layout change (old files then read as absent). */
    static final short VERSION = 1;

    /** Lowest / highest cost level carried by a per-region shard file. */
    static final int SHARD_LO_LEVEL = 0;
    static final int SHARD_HI_LEVEL = RegionAddress.SHARD_LEVEL; // 5

    // ---------------------------------------------------------------------------------------------------
    // Encode
    // ---------------------------------------------------------------------------------------------------

    /**
     * Write shard {@code (shardX, shardZ)}'s cost levels 0..5 to {@code rawOut} (header raw, body gzip'd), plus
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
        DataOutputStream header = new DataOutputStream(rawOut);
        header.writeInt(magic);
        header.writeShort(VERSION);
        header.flush();

        GZIPOutputStream gz = new GZIPOutputStream(rawOut);
        DataOutputStream out = new DataOutputStream(gz);

        int levelCount = 0;
        for (int level = loLevel; level <= hiLevel; level++) {
            if (countRows(p, level, shardScoped, shardX, shardZ) > 0) levelCount++;
        }
        out.writeByte(levelCount);

        for (int level = loLevel; level <= hiLevel; level++) {
            int n = countRows(p, level, shardScoped, shardX, shardZ);
            if (n == 0) continue;
            out.writeByte(level);
            out.writeInt(n);
            int rows = p.rowCount(level);
            for (int r = 0; r < rows; r++) {
                if (!matches(p, level, r, shardScoped, shardX, shardZ)) continue;
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

        // Reserved Stage-3 invalidation section (shard files only) — empty for now, forward-compatible.
        if (reserveInval) out.writeInt(0);

        out.flush();
        gz.finish();
    }

    /** Whether row {@code r} at {@code level} is a persistable built row matching the (optional) shard scope. */
    private static boolean matches(CostPyramid p, int level, int r, boolean shardScoped, int shardX, int shardZ) {
        if (!p.isBuilt(level, r) || p.fragmentRecord(level, r) == null) return false;
        if (!shardScoped) return true;
        return RegionAddress.shardOf(p.rowRX(level, r), level) == shardX
                && RegionAddress.shardOf(p.rowRZ(level, r), level) == shardZ;
    }

    private static int countRows(CostPyramid p, int level, boolean shardScoped, int shardX, int shardZ) {
        int rows = p.rowCount(level);
        int n = 0;
        for (int r = 0; r < rows; r++) {
            if (matches(p, level, r, shardScoped, shardX, shardZ)) n++;
        }
        return n;
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
        DataInputStream header = new DataInputStream(rawIn);
        int magic = header.readInt();
        int version = header.readUnsignedShort();
        if (magic != MAGIC_SHARD && magic != MAGIC_COARSE) {
            throw new IOException("bad cost-pyramid magic 0x" + Integer.toHexString(magic));
        }
        if (version != VERSION) {
            throw new IOException("unsupported cost-pyramid version " + version + " (expected " + VERSION + ")");
        }

        DataInputStream in = new DataInputStream(new GZIPInputStream(rawIn));
        int levelCount = in.readUnsignedByte();
        for (int ls = 0; ls < levelCount; ls++) {
            int level = in.readUnsignedByte();
            int count = in.readInt();
            if (count < 0) throw new IOException("negative cost row count " + count);
            // gridSize per level: 16 at the leaf, 4 at the coarse levels (NOT the flat LEAF_SIZE the old codec used).
            final int gridSize = PyramidMerger.coarseG(level);
            for (int i = 0; i < count; i++) {
                int rx = in.readInt();
                int ry = in.readUnsignedByte();
                int rz = in.readInt();
                int nbytes = in.readUnsignedShort();
                byte[] buf = new byte[nbytes];
                in.readFully(buf);

                int existing = dest.rowIfPresent(level, rx, ry, rz);
                if (existing != -1 && dest.isBuilt(level, existing)) continue; // live world wins (§6)

                int row = dest.rowFor(level, rx, ry, rz);
                RegionFragments out = dest.ensureFragments(level, row);
                CostCodec.unpackRegion(buf, 0, gridSize, out);
                dest.setBuilt(level, row, true);
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
