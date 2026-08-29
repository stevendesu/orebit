package com.orebit.mod.worldmodel.hpa;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.orebit.mod.worldmodel.persistence.CostPyramidCodec;

/**
 * ANALYSIS HARNESS (not a regression test — deliberately uncommitted): a per-level census of persisted
 * HPA* region records, answering "how many regions at each level are collapsed spongey cheese
 * ({@code kind==MIXED} with {@code fragmentCount==0}), and does it ever happen at L0/L1/L2?".
 *
 * <p>Decodes every v5 {@code hpa.*.bin} (OBHS shard, levels 0..5) + {@code hpa.coarse.bin} (OBHC, level 6)
 * in each dataset directory via the production {@link CostPyramidCodec#decode} into a fresh
 * {@link CostPyramid}, then tallies per level 0..{@link RegionAddress#MAX_COARSE_LEVEL}: total rows, kind
 * distribution, and for MIXED rows a fragmentCount histogram (min/median/p90/max, count==0 = COLLAPSED,
 * an at-cap count = {@link RegionFragments#MAX_FRAGMENTS} exactly, not collapsed). Non-v5 files (e.g. a
 * legacy {@code hpa.bin} OBHP blob) are reported as skipped.
 *
 * <p><b>Caveat pinned by {@link com.orebit.mod.worldmodel.hpa.CostCodec}'s schema comment:</b> the on-disk
 * form does NOT persist the build-time collapsed-vs-occupiability-stripped distinction — unpack re-flags
 * every count==0 MIXED record as collapsed. So this census's "MIXED count==0" population conflates
 * (a) over-cap collapse and (b) checkerboard/speckle "occupiability stripped every component" masses; the
 * files cannot tell them apart.
 *
 * <p>Gated by the {@code OREBIT_CENSUS_DIRS} environment variable (semicolon-separated dataset dirs) —
 * an env var rather than the {@code -D} gate the jmh/coldstart tasks use because the plain {@code test}
 * task forwards no system properties, and this harness must not touch the build script. The normal suite
 * skips it (variable absent). Report goes to stdout AND, when {@code OREBIT_CENSUS_OUT} is set, that file.
 *
 * <p>Run (active project 1.21.11, JDK 21):
 * <pre>
 *   $env:OREBIT_CENSUS_DIRS = "C:\...\dataset1;C:\...\dataset2"
 *   $env:OREBIT_CENSUS_OUT  = "C:\...\census.txt"
 *   .\gradlew.bat :1.21.11:test --tests "*RegionRecordCensus*"
 * </pre>
 */
public class RegionRecordCensusTest {

    private static final int MAGIC_SHARD  = ('O' << 24) | ('B' << 16) | ('H' << 8) | 'S';
    private static final int MAGIC_COARSE = ('O' << 24) | ('B' << 16) | ('H' << 8) | 'C';
    private static final int EXPECTED_VERSION = 5;

    /**
     * Opt-in ({@code OREBIT_CENSUS_ALLOW_V4=true}): ALSO decode v4 files, clearly labeled. Safe because the
     * {@link CostPyramidCodec#VERSION} javadoc pins v5 as "a SEMANTIC bump, not a layout change" (v5 only
     * reclassified floorless leaves any-water⇒WATER vs majority-voted AIR) — the byte layout is identical,
     * and the MIXED/fragmentCount population this census measures is untouched by that semantic change
     * (only the AIR-vs-WATER split of uniform floorless leaves drifts). The production decoder still
     * rejects v4 (cache semantics); this analysis-only relaxation re-implements nothing — it patches the
     * version short to 5 in the in-memory copy and feeds the production decoder.
     */
    private static final boolean ALLOW_V4 = "true".equals(System.getenv("OREBIT_CENSUS_ALLOW_V4"));

    @Test
    @EnabledIfEnvironmentVariable(named = "OREBIT_CENSUS_DIRS", matches = ".+")
    void census() throws IOException {
        String dirsVar = System.getenv("OREBIT_CENSUS_DIRS");
        StringWriter sw = new StringWriter();
        PrintWriter out = new PrintWriter(sw);

        for (String dirName : dirsVar.split(";")) {
            dirName = dirName.trim();
            if (dirName.isEmpty()) continue;
            censusOneDataset(new File(dirName), out);
        }

        out.flush();
        String report = sw.toString();
        System.out.println(report);
        String outPath = System.getenv("OREBIT_CENSUS_OUT");
        if (outPath != null && !outPath.isEmpty()) {
            Files.write(new File(outPath).toPath(), report.getBytes("UTF-8"));
        }
    }

    private static void censusOneDataset(File dir, PrintWriter out) {
        out.println("================================================================================");
        out.println("DATASET: " + dir.getAbsolutePath());
        out.println("================================================================================");
        if (!dir.isDirectory()) {
            out.println("  (missing directory — skipped)");
            return;
        }

        File[] files = dir.listFiles((d, name) -> name.startsWith("hpa") && name.endsWith(".bin"));
        if (files == null || files.length == 0) {
            out.println("  (no hpa*.bin files — skipped)");
            return;
        }
        Arrays.sort(files);

        CostPyramid pyramid = new CostPyramid();
        int decoded = 0;
        for (File f : files) {
            try {
                byte[] bytes = Files.readAllBytes(f.toPath());
                if (bytes.length < 6) {
                    out.println("  SKIP " + f.getName() + " (too short: " + bytes.length + " bytes)");
                    continue;
                }
                int magic = ((bytes[0] & 0xFF) << 24) | ((bytes[1] & 0xFF) << 16)
                          | ((bytes[2] & 0xFF) << 8) | (bytes[3] & 0xFF);
                int version = ((bytes[4] & 0xFF) << 8) | (bytes[5] & 0xFF);
                boolean v4Relaxed = false;
                if ((magic != MAGIC_SHARD && magic != MAGIC_COARSE) || version != EXPECTED_VERSION) {
                    boolean layoutCompatibleV4 = ALLOW_V4 && version == 4
                            && (magic == MAGIC_SHARD || magic == MAGIC_COARSE);
                    if (!layoutCompatibleV4) {
                        out.println("  SKIP " + f.getName() + " (magic=" + magicAscii(magic)
                                + " version=" + version + " — not a v5 OBHS/OBHC file)");
                        continue;
                    }
                    // v4 → v5 is layout-identical (see ALLOW_V4 doc); patch the version short in our copy.
                    bytes[4] = 0;
                    bytes[5] = EXPECTED_VERSION;
                    v4Relaxed = true;
                }
                CostPyramidCodec.decode(new ByteArrayInputStream(bytes), pyramid);
                decoded++;
                out.println("  OK   " + f.getName() + " (" + bytes.length + " bytes, "
                        + (magic == MAGIC_SHARD ? "shard L0-5" : "coarse L6")
                        + (v4Relaxed ? ", v4 DECODED UNDER LAYOUT-COMPAT RELAXATION" : "") + ")");
            } catch (IOException e) {
                out.println("  FAIL " + f.getName() + " (" + e.getMessage() + ")");
            }
        }
        out.println("  decoded " + decoded + "/" + files.length + " files");
        out.println();

        out.println(String.format("  %-3s %7s | %7s %7s %7s %7s | %s",
                "lvl", "total", "MIXED", "SOLID", "AIR", "WATER", "MIXED fragmentCount stats"));
        for (int level = 0; level <= RegionAddress.MAX_COARSE_LEVEL; level++) {
            int rows = pyramid.rowCount(level);
            int total = 0;
            int[] kinds = new int[4];
            List<Integer> mixedCounts = new ArrayList<>();
            int collapsed = 0;  // MIXED, fragmentCount == 0 (the persisted collapsed/uniform-mass marker)
            int atCap = 0;      // MIXED, fragmentCount == MAX_FRAGMENTS (61) — at the cap, NOT collapsed
            for (int r = 0; r < rows; r++) {
                RegionFragments rf = pyramid.fragmentRecord(level, r);
                if (rf == null || !pyramid.isBuilt(level, r)) continue;
                total++;
                kinds[rf.kind() & 3]++;
                if (rf.kind() == RegionFragments.KIND_MIXED) {
                    int fc = rf.fragmentCount();
                    mixedCounts.add(fc);
                    if (fc == 0) collapsed++;
                    if (fc == RegionFragments.MAX_FRAGMENTS) atCap++;
                }
            }
            String stats;
            if (mixedCounts.isEmpty()) {
                stats = "(no MIXED rows)";
            } else {
                Collections.sort(mixedCounts);
                int n = mixedCounts.size();
                int min = mixedCounts.get(0);
                int med = mixedCounts.get(n / 2);
                int p90 = mixedCounts.get(Math.min(n - 1, (int) Math.ceil(n * 0.9) - 1 < 0 ? 0 : (int) Math.ceil(n * 0.9) - 1));
                int max = mixedCounts.get(n - 1);
                stats = String.format("min=%d med=%d p90=%d max=%d | COLLAPSED(fc==0)=%d atCap(fc==63)=%d",
                        min, med, p90, max, collapsed, atCap);
            }
            out.println(String.format("  L%-2d %7d | %7d %7d %7d %7d | %s",
                    level, total, kinds[RegionFragments.KIND_MIXED], kinds[RegionFragments.KIND_SOLID],
                    kinds[RegionFragments.KIND_AIR], kinds[RegionFragments.KIND_WATER], stats));
        }

        // Compact fragmentCount histogram per level for the MIXED rows (bucketed), for shape context.
        out.println();
        out.println("  MIXED fragmentCount buckets per level (0 | 1 | 2-4 | 5-9 | 10-19 | 20-39 | 40-62 | 63):");
        for (int level = 0; level <= RegionAddress.MAX_COARSE_LEVEL; level++) {
            int rows = pyramid.rowCount(level);
            int[] buckets = new int[8];
            boolean any = false;
            for (int r = 0; r < rows; r++) {
                RegionFragments rf = pyramid.fragmentRecord(level, r);
                if (rf == null || !pyramid.isBuilt(level, r) || rf.kind() != RegionFragments.KIND_MIXED) continue;
                any = true;
                int fc = rf.fragmentCount();
                int b = fc == 0 ? 0 : fc == 1 ? 1 : fc <= 4 ? 2 : fc <= 9 ? 3 : fc <= 19 ? 4
                        : fc <= 39 ? 5 : fc <= 62 ? 6 : 7;
                buckets[b]++;
            }
            if (any) {
                out.println(String.format("  L%-2d  %6d | %6d | %6d | %6d | %6d | %6d | %6d | %6d",
                        level, buckets[0], buckets[1], buckets[2], buckets[3],
                        buckets[4], buckets[5], buckets[6], buckets[7]));
            }
        }
        out.println();
    }

    private static String magicAscii(int magic) {
        StringBuilder sb = new StringBuilder(4);
        for (int shift = 24; shift >= 0; shift -= 8) {
            int c = (magic >> shift) & 0xFF;
            sb.append(c >= 32 && c < 127 ? (char) c : '?');
        }
        return "0x" + Integer.toHexString(magic) + " (\"" + sb + "\")";
    }
}
