package com.orebit.mod.building;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A parsed Litematica {@code .litematic} schematic — MC-free (pure NBT + bit math, unit-testable
 * without any registry), byte-level-faithful to the litematica source (DESIGN-bot-abilities.md §6;
 * every rule below source-verified against LitematicaSchematic/LitematicaBitArray and
 * cross-checked against Baritone's independent parser):
 * <ul>
 *   <li>Per-region {@code Position}/{@code Size} are {x,y,z} INT COMPOUNDS; Size can be NEGATIVE
 *       per axis (it preserves the selection corners). Min corner per axis =
 *       {@code min(pos, pos + (size>=0 ? size-1 : size+1))}; dimensions = abs(size).</li>
 *   <li>{@code BlockStatePalette} = a LIST of {Name, Properties{string:string}} compounds;
 *       index 0 is air by construction on write, but the list is trusted as-is on read.</li>
 *   <li>{@code BlockStates} = a packed long[]: bits/entry =
 *       {@code max(2, ceil(log2(paletteSize)))}, entries LSB-first and SPANNING long boundaries
 *       (tight packing — UNLIKE post-1.16 vanilla chunk storage, which pads; vanilla
 *       {@code SimpleBitStorage} would MIS-DECODE these).</li>
 *   <li>Cell index = {@code x + z*sizeX + y*sizeX*sizeZ} over the abs-size region, relative to
 *       the region's min corner.</li>
 * </ul>
 * v1 scope: BLOCKS ONLY (TileEntities/Entities/PendingTicks are parsed past, not applied — the
 * Baritone-parser precedent); {@code Version < 5} files (pre-1.13.2 palettes needing datafix
 * conversion) are REJECTED rather than mis-built.
 */
public class Schematic {

    /** One palette entry: a block id + its property strings (resolved to a BlockState later,
     *  behind the version seam — this class stays MC-free). */
    public record PaletteEntry(String blockId, Map<String, String> properties) {}

    /** One sub-region: named box of packed block-state indices over its own palette. */
    public static class Region {
        private final String name;
        private final int sizeX, sizeY, sizeZ;
        /** This region's min corner relative to the schematic origin. */
        private final int minX, minY, minZ;
        private final List<PaletteEntry> palette;
        private final long[] packed;
        private final int bits;
        private final long maxEntry;

        Region(String name, int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ,
               List<PaletteEntry> palette, long[] packed) {
            this.name = name;
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.sizeX = sizeX;
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.palette = palette;
            this.bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(Math.max(1, palette.size() - 1)));
            this.maxEntry = (1L << bits) - 1;
            this.packed = packed;
        }

        public String name() {
            return name;
        }

        public int sizeX() {
            return sizeX;
        }

        public int sizeY() {
            return sizeY;
        }

        public int sizeZ() {
            return sizeZ;
        }

        public int minX() {
            return minX;
        }

        public int minY() {
            return minY;
        }

        public int minZ() {
            return minZ;
        }

        public List<PaletteEntry> palette() {
            return palette;
        }

        /** Bits per packed entry (exposed for the round-trip tests). */
        public int bitsPerEntry() {
            return bits;
        }

        /**
         * The palette index at region-relative {@code (x,y,z)} — the LitematicaBitArray read:
         * LSB-first, entries SPAN long boundaries (the split-read merge below is the load-bearing
         * difference from vanilla's padded storage).
         */
        public int paletteIndexAt(int x, int y, int z) {
            final long index = (long) y * sizeX * sizeZ + (long) z * sizeX + x;
            final long bitPos = index * bits;
            final int startWord = (int) (bitPos >> 6);
            final int endWord = (int) ((bitPos + bits - 1) >> 6);
            final int startOffset = (int) (bitPos & 63);
            if (startWord >= packed.length) return 0;
            if (startWord == endWord) {
                return (int) ((packed[startWord] >>> startOffset) & maxEntry);
            }
            if (endWord >= packed.length) return 0;
            final long lo = packed[startWord] >>> startOffset;
            final long hi = packed[endWord] << (64 - startOffset);
            return (int) ((lo | hi) & maxEntry);
        }

        /** The palette entry at region-relative {@code (x,y,z)} (index 0 is air by convention). */
        public PaletteEntry entryAt(int x, int y, int z) {
            final int i = paletteIndexAt(x, y, z);
            return i >= 0 && i < palette.size() ? palette.get(i) : palette.get(0);
        }
    }

    private final String name;
    private final List<Region> regions;

    private Schematic(String name, List<Region> regions) {
        this.name = name;
        this.regions = regions;
    }

    public String name() {
        return name;
    }

    public List<Region> regions() {
        return regions;
    }

    /** Total non-air cells across all regions (the build-size report). */
    public int totalBlocks() {
        int n = 0;
        for (Region r : regions) {
            for (int y = 0; y < r.sizeY(); y++) {
                for (int z = 0; z < r.sizeZ(); z++) {
                    for (int x = 0; x < r.sizeX(); x++) {
                        if (r.paletteIndexAt(x, y, z) != 0
                                && !"minecraft:air".equals(r.entryAt(x, y, z).blockId())) n++;
                    }
                }
            }
        }
        return n;
    }

    /** Load and parse a {@code .litematic} file. Throws {@link IOException} with an owner-readable
     *  message on any structural problem (the command surfaces it as chat). */
    public static Schematic load(Path file) throws IOException {
        final Map<String, Object> root = NbtReader.readCompressed(file);
        final int version = NbtReader.intValue(root, "Version", -1);
        if (version < 0) throw new IOException("not a .litematic (no Version tag)");
        if (version < 5) {
            throw new IOException("schematic Version " + version + " predates 1.13.2 palettes — re-save it with a current Litematica");
        }
        final Map<String, Object> meta = NbtReader.compound(root, "Metadata");
        final String name = NbtReader.stringValue(meta, "Name", file.getFileName().toString());
        final Map<String, Object> regionsTag = NbtReader.compound(root, "Regions");
        if (regionsTag == null || regionsTag.isEmpty()) throw new IOException("no Regions");
        final List<Region> regions = new ArrayList<>();
        for (Map.Entry<String, Object> e : regionsTag.entrySet()) {
            if (!(e.getValue() instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            final Map<String, Object> tag = (Map<String, Object>) e.getValue();
            regions.add(parseRegion(e.getKey(), tag));
        }
        return new Schematic(name, regions);
    }

    private static Region parseRegion(String name, Map<String, Object> tag) throws IOException {
        final Map<String, Object> pos = NbtReader.compound(tag, "Position");
        final Map<String, Object> size = NbtReader.compound(tag, "Size");
        if (pos == null || size == null) throw new IOException("region '" + name + "' lacks Position/Size");
        final int px = NbtReader.intValue(pos, "x", 0);
        final int py = NbtReader.intValue(pos, "y", 0);
        final int pz = NbtReader.intValue(pos, "z", 0);
        final int sx = NbtReader.intValue(size, "x", 0);
        final int sy = NbtReader.intValue(size, "y", 0);
        final int sz = NbtReader.intValue(size, "z", 0);
        if (sx == 0 || sy == 0 || sz == 0) throw new IOException("region '" + name + "' has a zero-size axis");
        // Negative-size semantics: the second corner per axis is pos + (size>=0 ? size-1 : size+1).
        final int minX = Math.min(px, px + (sx >= 0 ? sx - 1 : sx + 1));
        final int minY = Math.min(py, py + (sy >= 0 ? sy - 1 : sy + 1));
        final int minZ = Math.min(pz, pz + (sz >= 0 ? sz - 1 : sz + 1));
        final List<PaletteEntry> palette = new ArrayList<>();
        for (Object o : NbtReader.list(tag, "BlockStatePalette")) {
            if (!(o instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            final Map<String, Object> entry = (Map<String, Object>) o;
            final String id = NbtReader.stringValue(entry, "Name", "minecraft:air");
            final Map<String, String> props = new LinkedHashMap<>();
            final Map<String, Object> propsTag = NbtReader.compound(entry, "Properties");
            if (propsTag != null) {
                for (Map.Entry<String, Object> p : propsTag.entrySet()) {
                    if (p.getValue() instanceof String s) props.put(p.getKey(), s);
                }
            }
            palette.add(new PaletteEntry(id, props));
        }
        if (palette.isEmpty()) throw new IOException("region '" + name + "' has an empty palette");
        return new Region(name, minX, minY, minZ, Math.abs(sx), Math.abs(sy), Math.abs(sz),
                palette, NbtReader.longArray(tag, "BlockStates"));
    }
}
