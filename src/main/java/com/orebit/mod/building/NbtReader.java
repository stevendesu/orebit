package com.orebit.mod.building;

import java.io.DataInput;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * A dependency-free reader for the NBT binary format (DESIGN-bot-abilities.md §6) — the
 * {@code .litematic}/{@code .schem} container. Deliberately NOT the vanilla {@code NbtIo}:
 * schematic parsing must be identical across MC 1.17.1 → 26.x, and vanilla's tag API drifted
 * (getter signatures, Optional-returning accessors) while the WIRE FORMAT never has. ~150 lines
 * of plain Java beats ten overlay flavors.
 *
 * <p>Model: a compound is a {@code LinkedHashMap<String,Object>} (insertion order preserved),
 * a list is an {@code ArrayList<Object>}, primitives are boxed ({@code Byte/Short/Integer/Long/
 * Float/Double/String}), arrays are {@code byte[]/int[]/long[]}. Consumers navigate with the
 * typed helpers ({@link #compound}, {@link #list}, {@link #intValue}, {@link #longArray}) that
 * fail soft ({@code null}/default) — a malformed or minimal file degrades to "field absent",
 * never a crash.
 */
public final class NbtReader {

    private NbtReader() {}

    /** Read a GZIP-compressed NBT file (the .litematic/.schem envelope) into its root compound. */
    public static Map<String, Object> readCompressed(Path file) throws IOException {
        try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
            return readRoot(new DataInputStream(in));
        }
    }

    /** Read an UNCOMPRESSED NBT stream's root compound (tests / already-inflated buffers). */
    public static Map<String, Object> readRoot(DataInput in) throws IOException {
        final byte rootType = in.readByte();
        if (rootType != 10) {
            throw new IOException("not an NBT compound root (tag " + rootType + ")");
        }
        readString(in); // the root's name (usually empty) — irrelevant
        return readCompound(in);
    }

    private static Map<String, Object> readCompound(DataInput in) throws IOException {
        final Map<String, Object> out = new LinkedHashMap<>();
        while (true) {
            final byte type = in.readByte();
            if (type == 0) return out; // TAG_End
            final String name = readString(in);
            out.put(name, readPayload(in, type));
        }
    }

    private static Object readPayload(DataInput in, byte type) throws IOException {
        switch (type) {
            case 1: return in.readByte();
            case 2: return in.readShort();
            case 3: return in.readInt();
            case 4: return in.readLong();
            case 5: return in.readFloat();
            case 6: return in.readDouble();
            case 7: { // byte array
                final byte[] a = new byte[in.readInt()];
                in.readFully(a);
                return a;
            }
            case 8: return readString(in);
            case 9: { // list
                final byte elemType = in.readByte();
                final int n = in.readInt();
                final List<Object> list = new ArrayList<>(Math.max(0, n));
                for (int i = 0; i < n; i++) {
                    list.add(readPayload(in, elemType));
                }
                return list;
            }
            case 10: return readCompound(in);
            case 11: { // int array
                final int[] a = new int[in.readInt()];
                for (int i = 0; i < a.length; i++) a[i] = in.readInt();
                return a;
            }
            case 12: { // long array
                final long[] a = new long[in.readInt()];
                for (int i = 0; i < a.length; i++) a[i] = in.readLong();
                return a;
            }
            default:
                throw new IOException("unknown NBT tag type " + type);
        }
    }

    private static String readString(DataInput in) throws IOException {
        return in.readUTF(); // NBT strings are modified-UTF-8 with a u16 length — exactly readUTF
    }

    // ---- soft typed navigation ------------------------------------------------------------------

    /** The child compound {@code key}, or {@code null}. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> compound(Map<String, Object> parent, String key) {
        final Object v = parent == null ? null : parent.get(key);
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    /** The child list {@code key}, or an empty list. */
    @SuppressWarnings("unchecked")
    public static List<Object> list(Map<String, Object> parent, String key) {
        final Object v = parent == null ? null : parent.get(key);
        return v instanceof List ? (List<Object>) v : List.of();
    }

    /** The numeric child {@code key} as an int, or {@code def}. */
    public static int intValue(Map<String, Object> parent, String key, int def) {
        final Object v = parent == null ? null : parent.get(key);
        return v instanceof Number n ? n.intValue() : def;
    }

    /** The string child {@code key}, or {@code def}. */
    public static String stringValue(Map<String, Object> parent, String key, String def) {
        final Object v = parent == null ? null : parent.get(key);
        return v instanceof String s ? s : def;
    }

    /** The long-array child {@code key}, or an empty array. */
    public static long[] longArray(Map<String, Object> parent, String key) {
        final Object v = parent == null ? null : parent.get(key);
        return v instanceof long[] a ? a : new long[0];
    }
}
