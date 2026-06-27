package com.orebit.mod.worldmodel.hpa;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Persistence codec for the HPA* region tier's {@link CostPyramid}
 * (PRD §6.6 "disk budget"; HPA-IMPLEMENTATION.md §11, "3e persistence").
 *
 * <p><b>Milestone-first sequencing (note §0/§11).</b> The "earns its keep" milestone (PRD §10 Phase 3) is
 * reachable WITHOUT persistence — an in-memory pyramid over loaded terrain already turns a FAILing long
 * goal into a success. Persistence only extends reach to unloaded / multi-million-block paths. So this
 * file ships the <b>pure-Java codec</b> now (encode/decode a {@link CostPyramid} to/from a compact
 * {@code byte[]}) and <b>defers the {@code net.minecraft} {@code SavedData} wiring</b> — that surface is
 * version-fragile ({@code SavedData.save(CompoundTag)} ⇄ {@code save(CompoundTag, HolderLookup.Provider)}
 * drifts across the 1.17→26.x range, and {@code ServerLevel.getDataStorage()} likewise), so per §11 it
 * goes behind the {@code platform/} seam / an overlay AFTER the milestone. This class deliberately imports
 * <b>no MC API</b> and stays compiling against {@code byte[]}/streams only.
 *
 * <h2>On-disk form (palette-free; the nibbles ARE the compression — §11)</h2>
 * The pyramid is the per-dimension cost store; we serialize, per level, the <b>sparse</b> set of interned
 * rows. A node that was never interned is simply absent; a node that is interned-but-{@code !built}
 * round-trips with its default INF faces and {@code built=false}. Layout (big-endian via
 * {@link DataOutputStream}):
 * <pre>
 *   magic   : 4 bytes  = {@value #MAGIC} ("OHPA")
 *   version : 1 byte   = {@value #FORMAT_VERSION}
 *   nLevels : varint   = number of NON-EMPTY levels that follow
 *   per level:
 *     level   : varint   (0..MAX_LEVEL)
 *     nRows   : varint   = interned row count at this level
 *     per row (the §11 record, now DIRECTIONAL — "(rx,ry,rz, 12 nibbles packed into 6 bytes, built bit)"):
 *       rx    : varint (zig-zag)   region X (may be negative)
 *       ry    : varint (zig-zag)   region Y
 *       rz    : varint (zig-zag)   region Z
 *       enter : 3 bytes            6 ENTER nibbles, face[2k] = high nibble of byte k, face[2k+1] = low
 *       exit  : 3 bytes            6 EXIT  nibbles, same packing
 *       flags : 1 byte             bit0 = built
 * </pre>
 *
 * <p><b>Compactness.</b> Coords are zig-zag varints (small near-origin regions cost 1–2 bytes each); the
 * twelve face buckets (6 ENTER + 6 EXIT — {@link CostPyramid#ENTER}) are exactly 6 bytes (the
 * {@link CostCodec} nibble form); the built bit is one flags byte → ~13–16 bytes/node typical. This is the form the deferred {@code SavedData} will hand to NBT as a
 * single {@code byte[]} blob (NOT a per-node compound — that would blow the disk budget). Target ~2% of
 * save (PRD §6.6); measured by the disk-budget check (PRD §11) once both this and the resource octree
 * exist.
 *
 * <p><b>House style (note §14).</b> The codec is the cold path (level load / autosave), not a hot loop, so
 * it favours clarity; it allocates only the obvious stream buffers. It reads/writes through
 * {@link CostPyramid}'s public API ({@link CostPyramid#rowCount}, {@link CostPyramid#rowRX} etc.,
 * {@link CostPyramid#rowFor}, {@link CostPyramid#faceBucket}/{@link CostPyramid#setFaceBucket},
 * {@link CostPyramid#isBuilt}/{@link CostPyramid#setBuilt}) — it never reaches into the SoA arrays. The
 * nav grid is NEVER persisted (recomputed on load); only the rolled-up region costs are.
 */
public final class HpaPersistence {

    /** Format magic — ASCII "OHPA" (Orebit HPA), as a big-endian int. */
    public static final int MAGIC = 0x4F485041; // 'O' 'H' 'P' 'A'

    /** On-disk format version; bump on any layout change so an old blob is detected and discarded. */
    public static final byte FORMAT_VERSION = 2; // v2: 12 directional nibbles/node (was 6 symmetric)

    /** Six face buckets in ONE direction pack into this many bytes on disk (two nibbles per byte). */
    private static final int FACE_BYTES = 3;

    /** {@code flags} byte bit: the node's faces were actually computed (vs an interned default). */
    private static final int FLAG_BUILT = 0x01;

    private HpaPersistence() {}

    // ===================================================================================================
    // Encode
    // ===================================================================================================

    /**
     * Encode an entire {@link CostPyramid} to the compact on-disk byte form (see the class doc for the
     * layout). Only non-empty levels and only interned rows are written; absent nodes round-trip as absent.
     *
     * @param pyramid the per-dimension cost store to serialize
     * @return a fresh {@code byte[]} blob (the form the deferred {@code SavedData} hands to NBT)
     */
    public static byte[] encode(CostPyramid pyramid) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(4096);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);
            out.writeByte(FORMAT_VERSION);

            // Count non-empty levels first (the keyspace is sparse; most levels may be untouched).
            int nLevels = 0;
            for (int level = 0; level <= RegionAddress.MAX_LEVEL; level++) {
                if (pyramid.rowCount(level) > 0) nLevels++;
            }
            writeVarInt(out, nLevels);

            for (int level = 0; level <= RegionAddress.MAX_LEVEL; level++) {
                int rows = pyramid.rowCount(level);
                if (rows == 0) continue;
                writeVarInt(out, level);
                writeVarInt(out, rows);
                for (int row = 0; row < rows; row++) {
                    writeZigZag(out, pyramid.rowRX(level, row));
                    writeZigZag(out, pyramid.rowRY(level, row));
                    writeZigZag(out, pyramid.rowRZ(level, row));
                    // 12 nibbles → 6 bytes: ENTER faces 0..5 (3 bytes), then EXIT faces 0..5 (3 bytes).
                    for (int dir = 0; dir < 2; dir++) {
                        for (int b = 0; b < FACE_BYTES; b++) {
                            int hi = pyramid.faceBucket(level, row, b * 2, dir) & 0x0F;
                            int lo = pyramid.faceBucket(level, row, b * 2 + 1, dir) & 0x0F;
                            out.writeByte((hi << 4) | lo);
                        }
                    }
                    out.writeByte(pyramid.isBuilt(level, row) ? FLAG_BUILT : 0);
                }
            }
            out.flush();
        } catch (IOException e) {
            // ByteArrayOutputStream never throws; wrap defensively to keep a checked-exception-free API.
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    // ===================================================================================================
    // Decode
    // ===================================================================================================

    /**
     * Decode a blob produced by {@link #encode} into a fresh {@link CostPyramid}. Convenience overload of
     * {@link #decodeInto(byte[], CostPyramid)} that allocates the pyramid.
     *
     * @param blob the bytes (e.g. read from the NBT field by the deferred {@code SavedData})
     * @return a new {@code CostPyramid} seeded with the persisted rows, or an empty pyramid if {@code blob}
     *     is null/empty/malformed/wrong-version (treated as "no saved data" — the tier recomputes lazily)
     */
    public static CostPyramid decode(byte[] blob) {
        CostPyramid pyramid = new CostPyramid();
        decodeInto(blob, pyramid);
        return pyramid;
    }

    /**
     * Decode a blob into an existing {@link CostPyramid}, interning each persisted row and restoring its six
     * face buckets + built bit. Unknown/empty/malformed/version-mismatched input is treated as "no saved
     * data" (a no-op) — the region tier then rebuilds lazily from the live nav grid, so a stale or partial
     * save can never corrupt planning.
     *
     * @param blob    the persisted bytes (may be null/empty)
     * @param pyramid the store to seed (rows are interned via {@link CostPyramid#rowFor})
     * @return the number of rows restored (0 if the blob was absent/unusable)
     */
    public static int decodeInto(byte[] blob, CostPyramid pyramid) {
        if (blob == null || blob.length < 5) return 0;
        int restored = 0;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(blob))) {
            if (in.readInt() != MAGIC) return 0;
            if (in.readByte() != FORMAT_VERSION) return 0;

            int nLevels = readVarInt(in);
            for (int li = 0; li < nLevels; li++) {
                int level = readVarInt(in);
                int rows = readVarInt(in);
                for (int r = 0; r < rows; r++) {
                    int rx = readZigZag(in);
                    int ry = readZigZag(in);
                    int rz = readZigZag(in);
                    // 6 face bytes: ENTER (e0,e1,e2) then EXIT (x0,x1,x2). Read all (keep the stream aligned)
                    // before the level-skip check below.
                    int e0 = in.readUnsignedByte();
                    int e1 = in.readUnsignedByte();
                    int e2 = in.readUnsignedByte();
                    int x0 = in.readUnsignedByte();
                    int x1 = in.readUnsignedByte();
                    int x2 = in.readUnsignedByte();
                    int flags = in.readUnsignedByte();

                    // Skip rows for a level the addressing model no longer admits (defensive against a
                    // future MAX_LEVEL change); never crash a load on stale data.
                    if (level < 0 || level > RegionAddress.MAX_LEVEL) continue;

                    int row = pyramid.rowFor(level, rx, ry, rz);
                    pyramid.setFaceBucket(level, row, 0, CostPyramid.ENTER, (e0 >> 4) & 0x0F);
                    pyramid.setFaceBucket(level, row, 1, CostPyramid.ENTER, e0 & 0x0F);
                    pyramid.setFaceBucket(level, row, 2, CostPyramid.ENTER, (e1 >> 4) & 0x0F);
                    pyramid.setFaceBucket(level, row, 3, CostPyramid.ENTER, e1 & 0x0F);
                    pyramid.setFaceBucket(level, row, 4, CostPyramid.ENTER, (e2 >> 4) & 0x0F);
                    pyramid.setFaceBucket(level, row, 5, CostPyramid.ENTER, e2 & 0x0F);
                    pyramid.setFaceBucket(level, row, 0, CostPyramid.EXIT, (x0 >> 4) & 0x0F);
                    pyramid.setFaceBucket(level, row, 1, CostPyramid.EXIT, x0 & 0x0F);
                    pyramid.setFaceBucket(level, row, 2, CostPyramid.EXIT, (x1 >> 4) & 0x0F);
                    pyramid.setFaceBucket(level, row, 3, CostPyramid.EXIT, x1 & 0x0F);
                    pyramid.setFaceBucket(level, row, 4, CostPyramid.EXIT, (x2 >> 4) & 0x0F);
                    pyramid.setFaceBucket(level, row, 5, CostPyramid.EXIT, x2 & 0x0F);
                    pyramid.setBuilt(level, row, (flags & FLAG_BUILT) != 0);
                    restored++;
                }
            }
        } catch (IOException e) {
            // Truncated/corrupt blob: keep whatever was restored before the fault; the tier recomputes the
            // rest. Never propagate — a bad save must not break level load.
            return restored;
        }
        return restored;
    }

    // ===================================================================================================
    // Varint helpers (pure-Java; no Netty/MC ByteBuf dependency so this stays version-free)
    // ===================================================================================================

    /** Write an unsigned LEB128 varint (non-negative; used for counts/levels). */
    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        // Callers only pass non-negative counts; treat as unsigned 32-bit.
        int v = value;
        while ((v & 0xFFFFFF80) != 0) {
            out.writeByte((v & 0x7F) | 0x80);
            v >>>= 7;
        }
        out.writeByte(v & 0x7F);
    }

    /** Read an unsigned LEB128 varint written by {@link #writeVarInt}. */
    private static int readVarInt(DataInputStream in) throws IOException {
        int result = 0;
        int shift = 0;
        while (true) {
            int b = in.readUnsignedByte();
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) return result;
            shift += 7;
            if (shift >= 35) throw new IOException("varint too long");
        }
    }

    /** Write a signed int as a zig-zag varint (small magnitudes — incl. negatives — stay 1–2 bytes). */
    private static void writeZigZag(DataOutputStream out, int value) throws IOException {
        writeVarInt(out, (value << 1) ^ (value >> 31));
    }

    /** Read a zig-zag varint written by {@link #writeZigZag}. */
    private static int readZigZag(DataInputStream in) throws IOException {
        int raw = readVarInt(in);
        return (raw >>> 1) ^ -(raw & 1);
    }

    // ===================================================================================================
    // SavedData wiring — DEFERRED (note §0/§11: milestone-first; version-coupled signature)
    // ===================================================================================================

    // TODO wire SavedData after the milestone (version-coupled signature — see note section 11).
    //
    // The thin per-dimension store binds the codec above to MC's save system:
    //
    //   * a `net.minecraft.world.level.saveddata.SavedData` subclass named "orebit_hpa" that holds a
    //     CostPyramid; its load(CompoundTag) calls HpaPersistence.decode(tag.getByteArray("pyramid"))
    //     and its save(CompoundTag[, HolderLookup.Provider]) calls tag.putByteArray("pyramid",
    //     HpaPersistence.encode(pyramid)) and returns the tag; setDirty() on every leaf/merge change.
    //   * obtained per level via ServerLevel.getDataStorage().computeIfAbsent(factory, "orebit_hpa").
    //
    // Both `SavedData.save(...)` (the HolderLookup.Provider arg appeared mid-range) and `getDataStorage()`
    // drift across the 1.17 -> 26.x range, so per note §11 the SavedData subclass + getDataStorage call go
    // behind the platform/ seam (or an overlay), keeping THIS codec (pure byte[] logic) in core. Wire the
    // load/save hooks in OrebitCommon next to NavGridUpdater.register() (note §10/§12). Deferred until the
    // in-memory milestone (note §0) lands so failures stay visible and nothing version-fragile is imported
    // before it earns its keep.
}
