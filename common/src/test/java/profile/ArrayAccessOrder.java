package profile;

import java.util.HashMap;
import java.util.Map;

/**
 * I start with a 20x20x20 array. This array represents a full
 * ChunkSection (16x16x16) plus the 2 neighboring blocks on all
 * sides.
 *
 * I pass a 3x3x3 sliding window over the ChunkSection. This is
 * akin to our access pattern for computing NavSections.
 *
 * The goal of this profile is to figure out if rearranging the
 * elements in the array yields meaningful improvement in
 * performance by optimizing for cache line hits.
 */
public class ArrayAccessOrder {

    // Encode grid directly in nested arrays
    private static final int[][][] baseline = new int[20][20][20];

    // Flatten nested arrays to avoid indirection during access at
    // the cost of computing the index via multiplication
    private static final int[] flat = new int[8000];

    // Morton encoding is spatially inefficient for arrays that are
    // not a power of two in each dimension. In our case the largest
    // index is [19][19][19]. The binary representation of 19 is
    // 0b10011, leading to a Morton encoding of:
    // 0b111000000111111 = 28,735
    private static final int[] morton = new int[28736];

    // Since our grid has side lengths
    private static final int[] tile = new int[8000];

    private static int flatEncode(int x, int y, int z) {
        return x + y * 20 + z * 20 * 20;
    }

    // Spread 5 bits: insert 2 zeros between each bit
    private static int splitBy3(int a) {
        int x = a & 0x1F; // Keep only the lowest 5 bits
        x = (x | (x << 8)) & 0x0000F00F;
        x = (x | (x << 4)) & 0x000C30C3;
        x = (x | (x << 2)) & 0x00249249;
        return x;
    }

    // Encode x, y, z into a 15-bit Morton code
    private static int mortonEncode(int x, int y, int z) {
        return splitBy3(x) | (splitBy3(y) << 1) | (splitBy3(z) << 2);
    }

    // Encode x, y, z into tile position
    private static int tileEncode(int x, int y, int z) {
        int xTile = x / 5;
        int yTile = y / 5;
        int zTile = z / 5;
        int xTileIdx = x - xTile * 5;
        int yTileIdx = y - yTile * 5;
        int zTileIdx = z - zTile * 5;

        int tileOffset = xTile + yTile * 20 + zTile * 20 * 20;
        int tileIdx = xTileIdx + yTileIdx * 5 + zTileIdx * 5 * 5;

        return tileOffset + tileIdx;
    }

    volatile static int sink;

    private static void readBlockAndNeighbors(int x, int y, int z) {
        for (int i = -2; i <= 2; i++) {
            for (int j = -2; j <= 2; j++) {
                for (int k = -2; k <= 2; k++) {
                    sink = baseline[x + i][y + j][z + k];
                }
            }
        }
    }

    private static void readBlockAndNeighborsFlat(int x, int y, int z) {
        for (int i = -2; i <= 2; i++) {
            for (int j = -2; j <= 2; j++) {
                for (int k = -2; k <= 2; k++) {
                    int idx = flatEncode(x + i, y + j, z + k);
                    sink = flat[idx];
                }
            }
        }
    }

    private static void readBlockAndNeighborsFlatInline(int x, int y, int z) {
        for (int i = -2; i <= 2; i++) {
            for (int j = -2; j <= 2; j++) {
                for (int k = -2; k <= 2; k++) {
                    int idx = x + i + (y + j) * 20 + (z + k) * 20 * 20;
                    sink = flat[idx];
                }
            }
        }
    }

    private static void readBlockAndNeighborsMorton(int x, int y, int z) {
        for (int i = -2; i <= 2; i++) {
            for (int j = -2; j <= 2; j++) {
                for (int k = -2; k <= 2; k++) {
                    int idx = mortonEncode(x + i, y + j, z + k);
                    sink = morton[idx];
                }
            }
        }
    }

    private static void readBlockAndNeighborsTile(int x, int y, int z) {
        for (int i = -2; i <= 2; i++) {
            for (int j = -2; j <= 2; j++) {
                for (int k = -2; k <= 2; k++) {
                    int idx = tileEncode(x + i, y + j, z + k);
                    sink = tile[idx];
                }
            }
        }
    }

    public static void main(String[] args) {

        Map<String, Map<String, Long>> timespans = new HashMap<>();
        timespans.put("baseline", new HashMap<>());
        timespans.put("flat", new HashMap<>());
        timespans.put("flatinline", new HashMap<>());
        timespans.put("flatinlinerev", new HashMap<>());
        timespans.put("morton", new HashMap<>());
        timespans.put("tile", new HashMap<>());

        long start;

        /*
            Populate arrays
         */
        // Baseline
        start = System.nanoTime();
        for (int x = 0; x < 20; x++) {
            for (int y = 0; y < 20; y++) {
                for (int z = 0; z < 20; z++) {
                    baseline[x][y][z] = x + y * 20 + z * 20 * 20;
                }
            }
        }
        timespans.get("baseline").put("populate", System.nanoTime() - start);

        // Flattened
        start = System.nanoTime();
        for (int x = 0; x < 20; x++) {
            for (int y = 0; y < 20; y++) {
                for (int z = 0; z < 20; z++) {
                    int idx = flatEncode(x, y, z);
                    flat[idx] = x + y * 20 + z * 20 * 20;
                }
            }
        }
        timespans.get("flat").put("populate", System.nanoTime() - start);

        // Flattened (Inline)
        start = System.nanoTime();
        for (int x = 0; x < 20; x++) {
            for (int y = 0; y < 20; y++) {
                for (int z = 0; z < 20; z++) {
                    int idx = x + y * 20 + z * 20 * 20;
                    flat[idx] = x + y * 20 + z * 20 * 20;
                }
            }
        }
        timespans.get("flatinline").put("populate", System.nanoTime() - start);

        // Flattened (Inline, Reverse)
        start = System.nanoTime();
        for (int z = 0; z < 20; z++) {
            for (int y = 0; y < 20; y++) {
                for (int x = 0; x < 20; x++) {
                    int idx = x + y * 20 + z * 20 * 20;
                    flat[idx] = x + y * 20 + z * 20 * 20;
                }
            }
        }
        timespans.get("flatinlinerev").put("populate", System.nanoTime() - start);

        // Morton
        start = System.nanoTime();
        for (int x = 0; x < 20; x++) {
            for (int y = 0; y < 20; y++) {
                for (int z = 0; z < 20; z++) {
                    int idx = mortonEncode(x, y, z);
                    morton[idx] = x + y * 20 + z * 20 * 20;
                }
            }
        }
        timespans.get("morton").put("populate", System.nanoTime() - start);

        // Tile
        start = System.nanoTime();
        for (int x = 0; x < 20; x++) {
            for (int y = 0; y < 20; y++) {
                for (int z = 0; z < 20; z++) {
                    int idx = tileEncode(x, y, z);
                    tile[idx] = x + y * 20 + z * 20 * 20;
                }
            }
        }
        timespans.get("tile").put("populate", System.nanoTime() - start);

        /*
            Read arrays 100 times and average performance
         */
        start = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            for (int x = 2; x < 18; x++) {
                for (int y = 2; y < 18; y++) {
                    for (int z = 2; z < 18; z++) {
                        readBlockAndNeighbors(x, y, z);
                    }
                }
            }
        }
        timespans.get("baseline").put("read", (System.nanoTime() - start) / 100);

        start = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            for (int x = 2; x < 18; x++) {
                for (int y = 2; y < 18; y++) {
                    for (int z = 2; z < 18; z++) {
                        readBlockAndNeighborsFlat(x, y, z);
                    }
                }
            }
        }
        timespans.get("flat").put("read", (System.nanoTime() - start) / 100);

        start = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            for (int x = 2; x < 18; x++) {
                for (int y = 2; y < 18; y++) {
                    for (int z = 2; z < 18; z++) {
                        readBlockAndNeighborsFlatInline(x, y, z);
                    }
                }
            }
        }
        timespans.get("flatinline").put("read", (System.nanoTime() - start) / 100);

        start = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            for (int z = 2; z < 18; z++) {
                for (int y = 2; y < 18; y++) {
                    for (int x = 2; x < 18; x++) {
                        readBlockAndNeighborsFlatInline(x, y, z);
                    }
                }
            }
        }
        timespans.get("flatinlinerev").put("read", (System.nanoTime() - start) / 100);

        start = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            for (int x = 2; x < 18; x++) {
                for (int y = 2; y < 18; y++) {
                    for (int z = 2; z < 18; z++) {
                        readBlockAndNeighborsMorton(x, y, z);
                    }
                }
            }
        }
        timespans.get("morton").put("read", (System.nanoTime() - start) / 100);

        start = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            for (int x = 2; x < 18; x++) {
                for (int y = 2; y < 18; y++) {
                    for (int z = 2; z < 18; z++) {
                        readBlockAndNeighborsTile(x, y, z);
                    }
                }
            }
        }
        timespans.get("tile").put("read", (System.nanoTime() - start) / 100);

        long baselineTime, flatTime, flatInlineTime, mortonTime, tileTime;

        System.out.println("---------------------------------------");
        System.out.println("Baseline: ");
        System.out.println("Populated in: " + timespans.get("baseline").get("populate") + " ns");
        System.out.println("Read in: " + timespans.get("baseline").get("read") + " ns");
        System.out.println("---------------------------------------");
        System.out.println("Flat: ");
        System.out.println("Populated in: " + timespans.get("flat").get("populate") + " ns");
        System.out.println("Read in: " + timespans.get("flat").get("read") + " ns");
        baselineTime = timespans.get("baseline").get("populate");
        flatTime = timespans.get("flat").get("populate");
        System.out.printf("Write eff.: %.2fx\n", (double) baselineTime / flatTime);
        baselineTime = timespans.get("baseline").get("read");
        flatTime = timespans.get("flat").get("read");
        System.out.printf("Read eff.: %.2fx\n", (double) baselineTime / flatTime);
        baselineTime = timespans.get("baseline").get("populate") + timespans.get("baseline").get("read");
        flatTime = timespans.get("flat").get("populate") + timespans.get("flat").get("read");
        System.out.printf("Total eff.: %.2fx\n", (double) baselineTime / flatTime);
        System.out.println("---------------------------------------");
        System.out.println("Flat Inline: ");
        System.out.println("Populated in: " + timespans.get("flatinline").get("populate") + " ns");
        System.out.println("Read in: " + timespans.get("flatinline").get("read") + " ns");
        baselineTime = timespans.get("baseline").get("populate");
        flatInlineTime = timespans.get("flatinline").get("populate");
        System.out.printf("Write eff.: %.2fx\n", (double) baselineTime / flatInlineTime);
        baselineTime = timespans.get("baseline").get("read");
        flatInlineTime = timespans.get("flatinline").get("read");
        System.out.printf("Read eff.: %.2fx\n", (double) baselineTime / flatInlineTime);
        baselineTime = timespans.get("baseline").get("populate") + timespans.get("baseline").get("read");
        flatInlineTime = timespans.get("flatinline").get("populate") + timespans.get("flatinline").get("read");
        System.out.printf("Total eff.: %.2fx\n", (double) baselineTime / flatInlineTime);
        System.out.println("---------------------------------------");
        System.out.println("Flat Inline Reverse: ");
        System.out.println("Populated in: " + timespans.get("flatinlinerev").get("populate") + " ns");
        System.out.println("Read in: " + timespans.get("flatinlinerev").get("read") + " ns");
        baselineTime = timespans.get("baseline").get("populate");
        flatInlineTime = timespans.get("flatinlinerev").get("populate");
        System.out.printf("Write eff.: %.2fx\n", (double) baselineTime / flatInlineTime);
        baselineTime = timespans.get("baseline").get("read");
        flatInlineTime = timespans.get("flatinlinerev").get("read");
        System.out.printf("Read eff.: %.2fx\n", (double) baselineTime / flatInlineTime);
        baselineTime = timespans.get("baseline").get("populate") + timespans.get("baseline").get("read");
        flatInlineTime = timespans.get("flatinlinerev").get("populate") + timespans.get("flatinlinerev").get("read");
        System.out.printf("Total eff.: %.2fx\n", (double) baselineTime / flatInlineTime);
        System.out.println("---------------------------------------");
        System.out.println("Morton: ");
        System.out.println("Populated in: " + timespans.get("morton").get("populate") + " ns");
        System.out.println("Read in: " + timespans.get("morton").get("read") + " ns");
        baselineTime = timespans.get("baseline").get("populate");
        mortonTime = timespans.get("morton").get("populate");
        System.out.printf("Write eff.: %.2fx\n", (double) baselineTime / mortonTime);
        baselineTime = timespans.get("baseline").get("read");
        mortonTime = timespans.get("morton").get("read");
        System.out.printf("Read eff.: %.2fx\n", (double) baselineTime / mortonTime);
        baselineTime = timespans.get("baseline").get("populate") + timespans.get("baseline").get("read");
        mortonTime = timespans.get("morton").get("populate") + timespans.get("morton").get("read");
        System.out.printf("Total eff.: %.2fx\n", (double) baselineTime / mortonTime);
        System.out.println("---------------------------------------");
        System.out.println("Tile: ");
        System.out.println("Populated in: " + timespans.get("tile").get("populate") + " ns");
        System.out.println("Read in: " + timespans.get("tile").get("read") + " ns");
        baselineTime = timespans.get("baseline").get("populate");
        tileTime = timespans.get("tile").get("populate");
        System.out.printf("Write eff.: %.2fx\n", (double) baselineTime / tileTime);
        baselineTime = timespans.get("baseline").get("read");
        tileTime = timespans.get("tile").get("read");
        System.out.printf("Read eff.: %.2fx\n", (double) baselineTime / tileTime);
        baselineTime = timespans.get("baseline").get("populate") + timespans.get("baseline").get("read");
        tileTime = timespans.get("tile").get("populate") + timespans.get("tile").get("read");
        System.out.printf("Total eff.: %.2fx\n", (double) baselineTime / tileTime);
        System.out.println("---------------------------------------");
    }

}
