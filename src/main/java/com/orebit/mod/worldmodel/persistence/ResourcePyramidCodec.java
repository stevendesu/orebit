package com.orebit.mod.worldmodel.persistence;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import com.orebit.mod.worldmodel.resource.ResourceClasses;
import com.orebit.mod.worldmodel.resource.ResourcePyramid;

/**
 * On-disk (de)serializer for a dimension's {@link ResourcePyramid} <b>level-0 tallies</b> — the resource half
 * of the world-model persistence arc (DESIGN-worldmodel-persistence.md §2, §7). One file per dimension
 * ({@code <world>/orebit/<dim>/res.bin}), the compass counterpart to {@link CostPyramidCodec}'s {@code hpa.bin}:
 * it lets {@code /bot report} surface resources the bot saw in now-unloaded terrain after a restart.
 *
 * <h2>What is persisted</h2>
 * Only level-0 tally rows; the coarse roll-up is replayed on load via
 * {@link com.orebit.mod.worldmodel.resource.ResourceMerger#mergeUpTallies}. A row is a
 * {@link ResourceClasses#COLUMN_COUNT}-wide {@code byte[]} of log₂ counts, but it is <b>sparse</b> — a section
 * gets a tally only when it holds ≥1 indexed block, and most of its 24 columns are 0 — so each row is written as
 * {@code (col, log2val)} pairs over the non-zero columns only.
 *
 * <h2>File format</h2>
 * <pre>
 *   magic (int, "OBRP")            \
 *   version (short)                 |  header — UNCOMPRESSED
 *   columnCount (byte)             /   the COLUMN_COUNT this was written with (forward-compat validation)
 *   --- gzip(body) ---
 *   rowCount (int)
 *   per row:
 *     rx (int) ry (byte) rz (int)
 *     nNonZero (byte)               number of non-zero columns that follow
 *     nNonZero * ( col (byte), log2val (byte) )
 * </pre>
 * The stored {@code columnCount} lets a decoder validate/zero-extend: {@link ResourceClasses} freezes column
 * ids by design, so a pair whose {@code col} is outside the running build's column range (a newer file on an
 * older build) is skipped rather than mis-applied.
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

    /** File magic — ASCII "OBRP" (Orebit Resource Pyramid). */
    static final int MAGIC = ('O' << 24) | ('B' << 16) | ('R' << 8) | 'P';
    /** Schema version; bump on any incompatible layout change. */
    static final short VERSION = 1;

    /** Indexed column count (24) — a compile-time constant, so this reference stays MC-free. */
    private static final int COLUMNS = ResourceClasses.COLUMN_COUNT;

    /**
     * Write every <b>built</b> level-0 tally row of {@code p} to {@code rawOut} (header raw, body gzip'd), each
     * as its non-zero {@code (col, log2val)} pairs. The stream is left open for the caller to close.
     */
    public static void encode(ResourcePyramid p, OutputStream rawOut) throws IOException {
        DataOutputStream header = new DataOutputStream(rawOut);
        header.writeInt(MAGIC);
        header.writeShort(VERSION);
        header.writeByte(COLUMNS);
        header.flush();

        GZIPOutputStream gz = new GZIPOutputStream(rawOut);
        DataOutputStream out = new DataOutputStream(gz);

        final int rows = p.rowCount(0);
        int persist = 0;
        for (int r = 0; r < rows; r++) {
            if (p.isBuilt(0, r)) persist++;
        }
        out.writeInt(persist);

        final byte[] vec = new byte[COLUMNS];
        for (int r = 0; r < rows; r++) {
            if (!p.isBuilt(0, r)) continue;
            p.readRow(0, r, vec);
            int nz = 0;
            for (int c = 0; c < COLUMNS; c++) {
                if (vec[c] != 0) nz++;
            }
            out.writeInt(p.rowRX(0, r));
            out.writeByte(p.rowRY(0, r));
            out.writeInt(p.rowRZ(0, r));
            out.writeByte(nz);
            for (int c = 0; c < COLUMNS; c++) {
                if (vec[c] != 0) {
                    out.writeByte(c);
                    out.writeByte(vec[c]);
                }
            }
        }
        out.flush();
        gz.finish();
    }

    /**
     * Read tally rows written by {@link #encode} from {@code rawIn} into {@code dest}, interning each level-0 row
     * and marking it built. Coarse levels are NOT rolled up here — {@link RegionPersistence} replays
     * {@code mergeUpTallies} after decode. Honours "live world wins" (a tally already built this session is left
     * untouched) and forward-compat (a column id ≥ the running build's {@link #COLUMNS} is skipped). Throws
     * {@link IOException} on a bad header / truncation.
     */
    public static void decode(InputStream rawIn, ResourcePyramid dest) throws IOException {
        DataInputStream header = new DataInputStream(rawIn);
        int magic = header.readInt();
        int version = header.readUnsignedShort();
        header.readUnsignedByte(); // stored columnCount — read for stream alignment (per-pair col is self-guarding)
        if (magic != MAGIC) {
            throw new IOException("bad resource-pyramid magic 0x" + Integer.toHexString(magic));
        }
        if (version != VERSION) {
            throw new IOException("unsupported resource-pyramid version " + version + " (expected " + VERSION + ")");
        }

        DataInputStream in = new DataInputStream(new GZIPInputStream(rawIn));
        int count = in.readInt();
        if (count < 0) throw new IOException("negative resource row count " + count);

        for (int i = 0; i < count; i++) {
            int rx = in.readInt();
            int ry = in.readUnsignedByte();
            int rz = in.readInt();
            int nz = in.readUnsignedByte();

            int existing = dest.rowIfPresent(0, rx, ry, rz);
            boolean skip = existing != -1 && dest.isBuilt(0, existing); // live world wins (§6)
            int row = skip ? -1 : dest.rowFor(0, rx, ry, rz);

            for (int j = 0; j < nz; j++) {
                int col = in.readUnsignedByte();
                byte val = in.readByte();
                if (!skip && col < COLUMNS) {
                    dest.setLog2(0, row, col, val);
                }
            }
            if (!skip) dest.setBuilt(0, row, true);
        }
    }
}
