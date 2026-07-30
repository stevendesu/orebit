package com.orebit.mod.building;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pure-JVM round trip for {@link NbtReader}/{@link Schematic} (DESIGN-bot-abilities.md §6): the
 * test WRITES a byte-faithful {@code .litematic} (NBT wire format + the LitematicaBitArray
 * LSB-first tight packing) and reads it back — no MC, no fixtures on disk. Load-bearing cases:
 * an entry that SPANS a long boundary (the exact place vanilla's padded storage would
 * mis-decode), the negative-Size min-corner rule, and the version gate.
 */
class SchematicParseTest {

    // ---- a minimal NBT writer over the same Map/List model (test-only) ----------------------

    private static void writeNbt(DataOutputStream out, Map<String, Object> root) throws IOException {
        out.writeByte(10);
        out.writeUTF("");
        writeCompoundBody(out, root);
    }

    private static void writeCompoundBody(DataOutputStream out, Map<String, Object> tag) throws IOException {
        for (Map.Entry<String, Object> e : tag.entrySet()) {
            final Object v = e.getValue();
            out.writeByte(typeOf(v));
            out.writeUTF(e.getKey());
            writePayload(out, v);
        }
        out.writeByte(0);
    }

    @SuppressWarnings("unchecked")
    private static void writePayload(DataOutputStream out, Object v) throws IOException {
        if (v instanceof Integer i) out.writeInt(i);
        else if (v instanceof Long l) out.writeLong(l);
        else if (v instanceof String s) out.writeUTF(s);
        else if (v instanceof long[] a) {
            out.writeInt(a.length);
            for (long x : a) out.writeLong(x);
        } else if (v instanceof Map<?, ?> m) writeCompoundBody(out, (Map<String, Object>) m);
        else if (v instanceof List<?> list) {
            final byte elem = list.isEmpty() ? 0 : typeOf(list.get(0));
            out.writeByte(elem);
            out.writeInt(list.size());
            for (Object o : list) writePayload(out, o);
        } else throw new IOException("unsupported test payload " + v.getClass());
    }

    private static byte typeOf(Object v) {
        if (v instanceof Integer) return 3;
        if (v instanceof Long) return 4;
        if (v instanceof String) return 8;
        if (v instanceof long[]) return 12;
        if (v instanceof List) return 9;
        if (v instanceof Map) return 10;
        throw new IllegalArgumentException(v.getClass().toString());
    }

    /** The litematica packing: LSB-first, entries span long boundaries (tight, no padding). */
    private static long[] pack(int[] indices, int bits) {
        final long[] out = new long[(int) Math.ceil(indices.length * bits / 64.0)];
        for (int i = 0; i < indices.length; i++) {
            final long bitPos = (long) i * bits;
            final int word = (int) (bitPos >> 6);
            final int off = (int) (bitPos & 63);
            out[word] |= (long) indices[i] << off;
            if (off + bits > 64) {
                out[word + 1] |= (long) indices[i] >>> (64 - off);
            }
        }
        return out;
    }

    private static Map<String, Object> xyz(int x, int y, int z) {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("x", x);
        m.put("y", y);
        m.put("z", z);
        return m;
    }

    private static Map<String, Object> paletteEntry(String id, Map<String, Object> props) {
        final Map<String, Object> m = new LinkedHashMap<>();
        m.put("Name", id);
        if (props != null) m.put("Properties", props);
        return m;
    }

    private static Path writeLitematic(Map<String, Object> root) throws IOException {
        final Path file = Files.createTempFile("orebit-schem-test", ".litematic");
        try (OutputStream fs = Files.newOutputStream(file);
             GZIPOutputStream gz = new GZIPOutputStream(fs);
             DataOutputStream out = new DataOutputStream(gz)) {
            writeNbt(out, root);
        }
        return file;
    }

    // ---- the cases ---------------------------------------------------------------------------

    @Test
    void roundTripWithSpanningEntries() throws IOException {
        // Palette of 5 → bits = 3; a 22×1×1 row puts entry 21 at bits 63..65 — SPANNING the
        // long boundary. Pattern: index i holds palette (i % 5).
        final int cells = 22;
        final int[] indices = new int[cells];
        for (int i = 0; i < cells; i++) indices[i] = i % 5;

        final Map<String, Object> region = new LinkedHashMap<>();
        region.put("Position", xyz(0, 0, 0));
        region.put("Size", xyz(cells, 1, 1));
        region.put("BlockStatePalette", List.of(
                paletteEntry("minecraft:air", null),
                paletteEntry("minecraft:stone", null),
                paletteEntry("minecraft:dirt", null),
                paletteEntry("minecraft:cobblestone", null),
                paletteEntry("minecraft:oak_planks", null)));
        region.put("BlockStates", pack(indices, 3));

        final Map<String, Object> root = new LinkedHashMap<>();
        root.put("Version", 6);
        root.put("Metadata", Map.of("Name", "span-test"));
        root.put("Regions", Map.of("main", region));

        final Schematic s = Schematic.load(writeLitematic(root));
        assertEquals("span-test", s.name());
        assertEquals(1, s.regions().size());
        final Schematic.Region r = s.regions().get(0);
        assertEquals(3, r.bitsPerEntry());
        for (int i = 0; i < cells; i++) {
            assertEquals(i % 5, r.paletteIndexAt(i, 0, 0), "cell " + i);
        }
        // 22 cells, every 5th (0,5,10,15,20) is air → 22 - 5 = 17 non-air.
        assertEquals(17, s.totalBlocks());
    }

    @Test
    void negativeSizeMinCornerAndProperties() throws IOException {
        // Size y = -2 from Position y=0 → second corner y = 0 + (-2+1) = -1 → minY = -1, dims 1x2x1.
        final int[] indices = {1, 2}; // bottom cell = stone, top = the stairs
        final Map<String, Object> stairsProps = new LinkedHashMap<>();
        stairsProps.put("facing", "north");
        stairsProps.put("half", "top");
        final Map<String, Object> region = new LinkedHashMap<>();
        region.put("Position", xyz(4, 0, 7));
        region.put("Size", xyz(1, -2, 1));
        region.put("BlockStatePalette", List.of(
                paletteEntry("minecraft:air", null),
                paletteEntry("minecraft:stone", null),
                paletteEntry("minecraft:oak_stairs", stairsProps)));
        region.put("BlockStates", pack(indices, 2));

        final Map<String, Object> root = new LinkedHashMap<>();
        root.put("Version", 6);
        root.put("Regions", Map.of("r", region));

        final Schematic.Region r = Schematic.load(writeLitematic(root)).regions().get(0);
        assertEquals(4, r.minX());
        assertEquals(-1, r.minY());
        assertEquals(7, r.minZ());
        assertEquals(2, r.sizeY());
        assertEquals("minecraft:stone", r.entryAt(0, 0, 0).blockId());
        assertEquals("minecraft:oak_stairs", r.entryAt(0, 1, 0).blockId());
        assertEquals("north", r.entryAt(0, 1, 0).properties().get("facing"));
        assertEquals("top", r.entryAt(0, 1, 0).properties().get("half"));
    }

    @Test
    void oldVersionsAreRejected() throws IOException {
        final Map<String, Object> root = new LinkedHashMap<>();
        root.put("Version", 4);
        root.put("Regions", Map.of());
        final Path file = writeLitematic(root);
        assertThrows(IOException.class, () -> Schematic.load(file));
    }
}
