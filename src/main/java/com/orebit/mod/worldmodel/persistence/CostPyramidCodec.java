package com.orebit.mod.worldmodel.persistence;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.orebit.mod.worldmodel.hpa.CostCodec;
import com.orebit.mod.worldmodel.hpa.CostPyramid;
import com.orebit.mod.worldmodel.hpa.RegionAddress;
import com.orebit.mod.worldmodel.hpa.RegionFragments;

/**
 * On-disk (de)serializer for a dimension's {@link CostPyramid} <b>level-0 leaves</b> — the cost half of the
 * world-model persistence arc (DESIGN-worldmodel-persistence.md §2, §7). One file per dimension
 * ({@code <world>/orebit/<dim>/hpa.bin}), written on a graceful stop / periodic flush by
 * {@link RegionPersistence} and read back at server start.
 *
 * <h2>What is persisted</h2>
 * <b>Only level-0 rows</b> — the expensive leaf flood. Every coarse pyramid level is a pure function of the
 * leaves ({@link com.orebit.mod.worldmodel.hpa.PyramidMerger#mergeUpFragments}), so {@link RegionPersistence}
 * re-merges them on load rather than persisting them (§2.1). Each leaf's fragment record rides the
 * <b>already-existing</b> {@link CostCodec#packRegion}/{@link CostCodec#unpackRegion} sub-byte bitstream — the
 * single biggest de-risking fact of this task: the wire format for a region is done and unit-tested
 * ({@code CostCodecTest}).
 *
 * <h2>File format</h2>
 * <pre>
 *   magic (int, "OBHP")            \  header — UNCOMPRESSED so a reader can validate before inflating
 *   version (short)               /
 *   --- gzip(body) ---
 *   rowCount (int)                    number of persisted level-0 leaves
 *   per leaf:
 *     rx (int) ry (byte) rz (int)     level-0 region coords (ry is 0..31 from the dimension floor)
 *     recordLen (unsigned short)      bytes of the packed CostCodec bitstream that follow
 *     record (recordLen bytes)        CostCodec.packRegion(rf) output
 * </pre>
 * {@code gridSize} (16 at the leaf) is not persisted — it is passed to {@link CostCodec#unpackRegion} on load
 * ({@link RegionAddress#LEAF_SIZE}). The body is gzip'd (the many uniform 6-bit rows compress hard); the header
 * stays raw so a bad magic/version is caught without inflating.
 *
 * <h2>Cache semantics</h2>
 * Persisted data is a <b>cache</b>, never source of truth: a bad magic / version mismatch / truncation throws
 * {@link IOException}, and {@link RegionPersistence} treats the file as absent and lets the live world rebuild
 * it. {@link #decode} additionally honours "live world wins" — it never overwrites a leaf already
 * {@link CostPyramid#isBuilt built} this session.
 *
 * <p>Pure Java (streams + gzip + the MC-free {@link CostCodec}); no Minecraft API, so it is unit-testable with
 * no server (see {@code RegionPersistenceRoundTripTest}). Cold path (server start/stop/periodic flush), so
 * normal allocation is fine here.
 */
public final class CostPyramidCodec {

    private CostPyramidCodec() {}

    /** File magic — ASCII "OBHP" (Orebit HPA Pyramid). */
    static final int MAGIC = ('O' << 24) | ('B' << 16) | ('H' << 8) | 'P';
    /** Schema version; bump on any incompatible layout change (old files then read as absent). */
    static final short VERSION = 1;

    /**
     * Write every <b>built</b> level-0 leaf of {@code p} to {@code rawOut} (header raw, body gzip'd). Rows that
     * are interned-but-unbuilt or have no fragment record are skipped (nothing to persist). The stream is left
     * open for the caller to close.
     */
    public static void encode(CostPyramid p, OutputStream rawOut) throws IOException {
        DataOutputStream header = new DataOutputStream(rawOut);
        header.writeInt(MAGIC);
        header.writeShort(VERSION);
        header.flush();

        GZIPOutputStream gz = new GZIPOutputStream(rawOut);
        DataOutputStream out = new DataOutputStream(gz);

        final int rows = p.rowCount(0);
        int persist = 0;
        for (int r = 0; r < rows; r++) {
            if (p.isBuilt(0, r) && p.fragmentRecord(0, r) != null) persist++;
        }
        out.writeInt(persist);

        for (int r = 0; r < rows; r++) {
            if (!p.isBuilt(0, r)) continue;
            RegionFragments rf = p.fragmentRecord(0, r);
            if (rf == null) continue;
            int bits = CostCodec.regionBitLength(rf);
            int nbytes = (bits + 7) >> 3;
            byte[] buf = new byte[nbytes];
            CostCodec.packRegion(rf, buf, 0);

            out.writeInt(p.rowRX(0, r));
            out.writeByte(p.rowRY(0, r));
            out.writeInt(p.rowRZ(0, r));
            out.writeShort(nbytes);
            out.write(buf);
        }
        out.flush();
        gz.finish();
    }

    /**
     * Read leaves written by {@link #encode} from {@code rawIn} into {@code dest}, interning each level-0 row and
     * marking it built. Coarse levels are NOT rebuilt here — {@link RegionPersistence} replays
     * {@code mergeUpFragments} after decode. Honours "live world wins": a leaf already built in {@code dest} this
     * session is left untouched (the bytes are still consumed to keep the stream aligned). Throws
     * {@link IOException} on a bad header / truncation (the caller treats the file as absent).
     */
    public static void decode(InputStream rawIn, CostPyramid dest) throws IOException {
        DataInputStream header = new DataInputStream(rawIn);
        int magic = header.readInt();
        int version = header.readUnsignedShort();
        if (magic != MAGIC) {
            throw new IOException("bad cost-pyramid magic 0x" + Integer.toHexString(magic));
        }
        if (version != VERSION) {
            throw new IOException("unsupported cost-pyramid version " + version + " (expected " + VERSION + ")");
        }

        DataInputStream in = new DataInputStream(new GZIPInputStream(rawIn));
        int count = in.readInt();
        if (count < 0) throw new IOException("negative cost row count " + count);

        for (int i = 0; i < count; i++) {
            int rx = in.readInt();
            int ry = in.readUnsignedByte();
            int rz = in.readInt();
            int nbytes = in.readUnsignedShort();
            byte[] buf = new byte[nbytes];
            in.readFully(buf);

            int existing = dest.rowIfPresent(0, rx, ry, rz);
            if (existing != -1 && dest.isBuilt(0, existing)) continue; // live world wins (§6)

            int row = dest.rowFor(0, rx, ry, rz);
            RegionFragments out = dest.ensureFragments(0, row);
            CostCodec.unpackRegion(buf, 0, RegionAddress.LEAF_SIZE, out);
            dest.setBuilt(0, row, true);
        }
    }
}
