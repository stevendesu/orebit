package com.orebit.mod.worldmodel.pathing;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.function.IntConsumer;

import com.orebit.mod.OrebitCommon;
import com.orebit.mod.platform.SectionPalette;
import com.orebit.mod.platform.Sections;
import com.orebit.mod.worldmodel.navblock.NavBlock;
import com.orebit.mod.worldmodel.resource.ResourceClasses;

import net.minecraft.core.IdMap;
import net.minecraft.util.BitStorage;
import net.minecraft.util.CrudeIncrementalIntIdentityHashBiMap;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.GlobalPalette;
import net.minecraft.world.level.chunk.HashMapPalette;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.LinearPalette;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;

public final class NavSectionBuilder {
    private NavSectionBuilder() {}

    // 16x16x16 array extends 2 extra blocks in all directions into neighboring
    // chunks. This lets us answer questions about neighbors and headroom.
    public static BlockState[] blocks = new BlockState[4096];

    volatile static boolean sink;

    // Per-THREAD scratch (slot-per-cell + resolved descriptors). The classify/flags kernels read/write
    // only these plus the grids they're handed, so they are safe to run on a worker pool — we
    // deliberately do NOT couple them to single-threaded execution. Reusing per thread also avoids
    // ~64 KB of garbage per section. (The remaining single-thread touchpoint is NavSectionPool; make it
    // concurrent/per-thread when section building is moved off the tick thread.) The descriptor scratch
    // carries NavFlags.OVERSCAN_ROWS extra rows above the section (y = 16..18, indices 4096..4863) filled
    // from the section ABOVE, so top-row flag computation sees real blocks — see NavFlags "Boundary
    // handling".
    private static final ThreadLocal<int[]> SLOT_SCRATCH = ThreadLocal.withInitial(() -> new int[4096]);
    private static final ThreadLocal<long[]> DESC_SCRATCH =
            ThreadLocal.withInitial(() -> new long[NavFlags.SCRATCH_SIZE]);
    // Per-column state carried across sections by the depth sweeps (computeDepth): 256 columns, index
    // (z<<4)|x. A holds the value below/above (floorGap / runUp), B the paired fact (standability of the
    // cell below / navtype of the cell above). Same per-thread pattern as the scratches above.
    private static final ThreadLocal<int[]> DEPTH_COL_A = ThreadLocal.withInitial(() -> new int[256]);
    private static final ThreadLocal<int[]> DEPTH_COL_B = ThreadLocal.withInitial(() -> new int[256]);
    private static final long AIR_DESC = NavBlock.descriptor(NavBlock.AIR);

    // LEGACY reflection helpers, consumed ONLY by the JMH reference benchmark
    // (profile.BlockReadBenchmark, PRD §8). The RUNTIME read+classify path uses the
    // self-degrading platform/SectionPalette instead, so these are no longer on the live
    // pipeline. Kept until the benchmark is ported. The static init below must NOT throw —
    // that would break NavSectionBuilder class-load (and the whole pipeline) on versions whose
    // PalettedContainer internals differ (e.g. pre-1.18, before the Data record).
    private static Field dataField;
    private static Field storageField;
    private static Field paletteField;
    private static Field linearValuesField;
    private static Field hashMapValuesField;
    private static Field globalRegistryField;

    static {
        try {
            // Field inside PalettedContainer
            dataField = PalettedContainer.class.getDeclaredField("data");
            dataField.setAccessible(true);

            // Inner record class: PalettedContainer$Data(configuration, storage, palette)
            Class<?> dataClass = Class.forName("net.minecraft.world.level.chunk.PalettedContainer$Data");

            storageField = dataClass.getDeclaredField("storage");
            storageField.setAccessible(true);

            paletteField = dataClass.getDeclaredField("palette");
            paletteField.setAccessible(true);

            linearValuesField = LinearPalette.class.getDeclaredField("values");
            linearValuesField.setAccessible(true);

            hashMapValuesField = HashMapPalette.class.getDeclaredField("values");
            hashMapValuesField.setAccessible(true);

            globalRegistryField = GlobalPalette.class.getDeclaredField("registry");
            globalRegistryField.setAccessible(true);
        } catch (Throwable e) {
            // Benchmark-only helpers; don't fail class-load. The runtime path uses SectionPalette.
            OrebitCommon.LOGGER.debug("[Orebit] NavSectionBuilder legacy reflection unavailable: {}", e.toString());
        }

        // The grid packs the navtype into 10 bits (TraversalGrid.NAVTYPE_CAPACITY). If NavBlock ever
        // interns more navtypes than that (heavily-modded servers), the high index bits would collide
        // with the flag bits and cells would mis-resolve. Vanilla measures ~590, well under 1024; warn
        // loudly rather than corrupt silently. The fix is to compact the descriptor to shed navtypes
        // (MOVEMENT-DESIGN §8), NOT to widen the cell. (AIR_DESC above already forced NavBlock
        // class-init, so the count is final here.)
        if (NavBlock.navtypeCount() > TraversalGrid.NAVTYPE_CAPACITY) {
            OrebitCommon.LOGGER.error("[Orebit] navtype count {} exceeds the {}-navtype nav-grid budget — "
                    + "grid cells will truncate and mis-resolve; compact the NavBlock descriptor (MOVEMENT-DESIGN §8).",
                    NavBlock.navtypeCount(), TraversalGrid.NAVTYPE_CAPACITY);
        }
    }

    public static BitStorage getStorageViaReflection(PalettedContainer<BlockState> container) {
        try {
            Object data = dataField.get(container);
            return (BitStorage) storageField.get(data);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed to access storage", e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Palette<BlockState> getPaletteViaReflection(PalettedContainer<BlockState> container) {
        try {
            Object data = dataField.get(container);
            return (Palette<BlockState>) paletteField.get(data);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed to access palette", e);
        }
    }

    public static Object[] getArrayFieldViaReflection(LinearPalette<BlockState> palette) {
        try {
            return (Object[]) linearValuesField.get(palette);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed to access LinearPalette.values", e);
        }
    }

    @SuppressWarnings("unchecked")
    public static CrudeIncrementalIntIdentityHashBiMap<BlockState> getBiMapFieldViaReflection(HashMapPalette<BlockState> palette) {
        try {
            return (CrudeIncrementalIntIdentityHashBiMap<BlockState>) hashMapValuesField.get(palette);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed to access HashMapPalette.values", e);
        }
    }

    @SuppressWarnings("unchecked")
    public static IdMap<BlockState> getIdListFieldViaReflection(GlobalPalette<BlockState> palette) {
        try {
            return (IdMap<BlockState>) globalRegistryField.get(palette);
        } catch (IllegalAccessException e) {
            throw new RuntimeException("Failed to access GlobalPalette.registry", e);
        }
    }

    /**
     * Single-section classify (navtypes + flags), with NO vertical overscan — flag reads above the
     * section resolve to air. This is the headless/test entry (no {@link LevelChunkSection}, no chunk
     * column); the live chunk build ({@link ChunkNavBuilder}) instead runs the two-pass column form —
     * {@link #classifyNavtypes} for every section, then {@link #computeFlags} with the section above's
     * grid in hand — so the top ~{@link NavFlags#OVERSCAN_ROWS} floor rows get honest hazard/slow/headroom
     * bits (see NavFlags "Boundary handling").
     */
    public static void classifyInto(PalettedContainer<BlockState> states, boolean onlyAir, TraversalGrid grid) {
        classifyInto(states, onlyAir, grid, null);
    }

    /**
     * As {@link #classifyInto(PalettedContainer, boolean, TraversalGrid)}, additionally reporting the
     * section-local cell index ({@code (y<<8)|(z<<4)|x}) of every nether-portal cell to {@code portalCells}
     * (nullable).
     */
    public static void classifyInto(PalettedContainer<BlockState> states, boolean onlyAir, TraversalGrid grid,
                                    IntConsumer portalCells) {
        boolean allAir = classifyNavtypes(states, onlyAir, grid, portalCells);
        computeFlags(grid, allAir, null);
    }

    /**
     * <b>Pass 1 of the column build</b> — resolve the section's block states to per-cell navtypes (flags
     * left zero; {@link #computeFlags} fills them once the whole column's navtypes exist, so each
     * section's flags can read the section above). Also reports nether-portal cells to {@code portalCells}
     * (nullable), the full-section discovery feed for {@link NetherPortalIndex}. <b>Fast-path
     * constraint:</b> when the palette holds no portal (the overwhelming case), the extra cost is exactly
     * one bit-test per palette entry — the 4096-cell collection pass runs ONLY when a portal entry is
     * actually present. Air-only sections skip everything.
     *
     * <p>A null {@code section} is all air: pre-1.18, an empty section is NULL in the chunk's section
     * array; 1.18+ always allocates a real (empty) LevelChunkSection, never null. Classifying it as
     * all-air rather than dereferencing it avoids the 1.17.1 NPE the moment a chunk with an empty section
     * ticked. Guard lives in core — harmless on 1.18+ where it never triggers.
     *
     * @return true if the section classified as uniform air — the hint {@link #computeFlags} uses to keep
     *         the big uniform-air recompute bypass (PRD §6.2).
     */
    public static boolean classifyNavtypes(LevelChunkSection section, TraversalGrid grid, IntConsumer portalCells) {
        return classifyNavtypes(section, grid, portalCells, null);
    }

    /**
     * As {@link #classifyNavtypes(LevelChunkSection, TraversalGrid, IntConsumer)}, additionally tallying
     * indexed-resource block counts into {@code resourceTallyOut} (nullable — see the core overload
     * {@link #classifyNavtypes(PalettedContainer, boolean, TraversalGrid, IntConsumer, int[])}).
     */
    public static boolean classifyNavtypes(LevelChunkSection section, TraversalGrid grid, IntConsumer portalCells,
                                           int[] resourceTallyOut) {
        if (section == null) return classifyNavtypes(null, true, grid, portalCells, resourceTallyOut);
        return classifyNavtypes(section.getStates(), Sections.hasOnlyAir(section), grid, portalCells, resourceTallyOut);
    }

    /**
     * Headless form of {@link #classifyNavtypes(LevelChunkSection, TraversalGrid, IntConsumer)} over a
     * bare state container ({@code states} is not touched when {@code onlyAir}, so it may be null then).
     */
    public static boolean classifyNavtypes(PalettedContainer<BlockState> states, boolean onlyAir,
                                           TraversalGrid grid, IntConsumer portalCells) {
        return classifyNavtypes(states, onlyAir, grid, portalCells, null);
    }

    /**
     * Core classify + optional resource tally. When {@code resourceTallyOut != null} (length
     * {@link ResourceClasses#COLUMN_COUNT}, caller-owned + pre-zeroed), the palette-decode loop also maps
     * each palette entry to its indexed resource column and, gated exactly like the portal collection,
     * runs one extra sequential 4096-cell pass to accumulate per-column raw counts. Resource-free sections
     * (the common case) pay only one {@code columnForBlock} lookup + compare per palette entry — never the
     * per-cell pass. A {@code null} tally (headless/benchmark callers) skips resources entirely, so those
     * paths are byte-identical to before. The hot 4096-cell navtype-resolution loop is untouched.
     */
    public static boolean classifyNavtypes(PalettedContainer<BlockState> states, boolean onlyAir,
                                           TraversalGrid grid, IntConsumer portalCells, int[] resourceTallyOut) {
        if (onlyAir) {
            fillRows(grid, NavBlock.AIR & 0xFFFF, 0, 0, NavSection.SIZE - 1);
            return true;
        }

        final int[] slotScratch = SLOT_SCRATCH.get();

        // Map the (small) palette to navtypes + descriptors once, then resolve every cell via its slot —
        // no per-cell state lookup. Only the navtype is stored; computeFlags re-derives descriptors from
        // the resident navtypes (one descriptor-table read per cell — the table is L1-resident).
        BlockState[] palette = SectionPalette.read(states, slotScratch);
        int[] slotToNavtype = new int[palette.length];
        long[] slotToDesc = new long[palette.length];
        // Per-call slot→resource-column map, allocated only when a tally was requested (same per-build
        // allocation as slotToNavtype/slotToDesc — this is the cold chunk-build path, not the search hot path).
        int[] slotToColumn = resourceTallyOut != null ? new int[palette.length] : null;
        boolean anyPortal = false;
        boolean anyResource = false;
        for (int s = 0; s < palette.length; s++) {
            short navtype = NavBlock.navtypeFor(palette[s]);
            slotToNavtype[s] = navtype & 0xFFFF;
            slotToDesc[s] = NavBlock.descriptor(navtype);
            anyPortal |= NavBlock.isPortal(slotToDesc[s]); // the one-bit-test-per-palette-entry gate
            if (slotToColumn != null) {
                int col = ResourceClasses.columnForBlock(palette[s].getBlock());
                slotToColumn[s] = col;
                anyResource |= (col >= 0);                 // one compare per palette entry, like anyPortal
            }
        }

        // Portal collection: gated on the palette actually containing one (portals are vanishingly rare),
        // so the per-cell pass never taxes the normal classify path.
        if (portalCells != null && anyPortal) {
            for (int i = 0; i < 4096; i++) {
                if (NavBlock.isPortal(slotToDesc[slotScratch[i]])) portalCells.accept(i);
            }
        }

        // Resource tally: gated on the palette actually containing an indexed block (mirrors the portal
        // gate). One sequential int[] read + gated increment per cell; feeds ChunkNavBuilder's per-section
        // log₂-encoded tally. The hot navtype-resolution loop below stays untouched.
        if (resourceTallyOut != null && anyResource) {
            for (int i = 0; i < 4096; i++) {
                int c = slotToColumn[slotScratch[i]];
                if (c >= 0) resourceTallyOut[c]++;
            }
        }

        for (int y = 0; y < NavSection.SIZE; y++) {
            for (int z = 0; z < NavSection.SIZE; z++) {
                for (int x = 0; x < NavSection.SIZE; x++) {
                    grid.set(x, y, z, slotToNavtype[slotScratch[(y << 8) | (z << 4) | x]], 0);
                }
            }
        }
        return false;
    }

    /**
     * <b>Pass 2 of the column build</b> — compute every cell's neighbour-property flags from the resident
     * navtypes, with the section ABOVE's cells as vertical overscan ({@code above} nullable: world-top,
     * unbuilt, or uniform-air above all mean "air above", which is exact for the first two and lets the
     * caller skip handing over an all-air grid for the third). Each cell keeps its pass-1 navtype.
     *
     * <p><b>Uniform-air bypass preserved</b> (PRD §6.2): when {@code selfAllAir} and nothing is above,
     * one representative cell prices the whole grid. When {@code selfAllAir} but the section above has
     * content, only the top {@link NavFlags#OVERSCAN_ROWS} rows (the overscan-affected ones, {@code y >=
     * 13}) are computed individually — the interior stays a uniform fill.
     */
    public static void computeFlags(TraversalGrid grid, boolean selfAllAir, TraversalGrid above) {
        final long[] desc = DESC_SCRATCH.get();
        final int firstOverscanRow = NavSection.SIZE - NavFlags.OVERSCAN_ROWS; // 13: reads y+3 >= 16

        if (selfAllAir) {
            Arrays.fill(desc, 0, 4096, AIR_DESC);
            fillOverscan(desc, above);
            int airFlags = NavFlags.compute(desc, 8, 8, 8);
            if (above == null) {
                fillRows(grid, NavBlock.AIR & 0xFFFF, airFlags, 0, NavSection.SIZE - 1);
                return;
            }
            fillRows(grid, NavBlock.AIR & 0xFFFF, airFlags, 0, firstOverscanRow - 1);
            computeRows(grid, desc, firstOverscanRow, NavSection.SIZE - 1);
            return;
        }

        fillScratch(desc, grid, above);
        computeRows(grid, desc, 0, NavSection.SIZE - 1);
    }

    /** Recompute flags for rows {@code yFrom..yTo} from the scratch; navtypes stay resident. */
    private static void computeRows(TraversalGrid grid, long[] desc, int yFrom, int yTo) {
        for (int y = yFrom; y <= yTo; y++) {
            for (int z = 0; z < NavSection.SIZE; z++) {
                for (int x = 0; x < NavSection.SIZE; x++) {
                    grid.set(x, y, z, grid.navtype(x, y, z), NavFlags.compute(desc, x, y, z));
                }
            }
        }
    }

    /** Fill rows {@code yFrom..yTo} with one navtype + flags (the uniform-air fast paths). */
    private static void fillRows(TraversalGrid grid, int navtype, int flags, int yFrom, int yTo) {
        for (int y = yFrom; y <= yTo; y++) {
            for (int z = 0; z < NavSection.SIZE; z++) {
                for (int x = 0; x < NavSection.SIZE; x++) {
                    grid.set(x, y, z, navtype, flags);
                }
            }
        }
    }

    /**
     * Rebuild the descriptor scratch from a grid's resident navtypes (indices 0..4095) plus the vertical
     * overscan rows (4096..) from the section above's bottom {@link NavFlags#OVERSCAN_ROWS} rows —
     * air when {@code above} is null. ≈4.8k descriptor-table reads, no allocation.
     */
    private static void fillScratch(long[] desc, TraversalGrid grid, TraversalGrid above) {
        final short[] raw = grid.raw();
        for (int i = 0; i < 4096; i++) {
            desc[i] = NavBlock.descriptor((short) (raw[i] & TraversalGrid.NAVTYPE_MASK));
        }
        fillOverscan(desc, above);
    }

    /** Fill only the overscan rows of the scratch (see {@link #fillScratch}). */
    private static void fillOverscan(long[] desc, TraversalGrid above) {
        if (above == null) {
            Arrays.fill(desc, 4096, NavFlags.SCRATCH_SIZE, AIR_DESC);
            return;
        }
        final short[] raw = above.raw(); // above's rows 0..2 are exactly indices 0..767
        for (int i = 0; i < NavFlags.OVERSCAN_ROWS * 256; i++) {
            desc[4096 + i] = NavBlock.descriptor((short) (raw[i] & TraversalGrid.NAVTYPE_MASK));
        }
    }

    /**
     * <b>Pass 3 of the column build (the floorGap/runUp depth nibbles — PERF-DESIGN-navgrid-widening.md
     * §2/§3, PERF-DESIGN-runup-nibble.md):</b> fill the parallel depth byte of every cell in the chunk
     * column. Two single-direction sweeps, each one navtype read + one table/compare + one nibble store
     * per cell, carrying per-column state across sections:
     * <ul>
     *   <li><b>floorGap</b> (ascending) — the §2 recurrence
     *       {@code gap(y) = standable(y−1) ? 0 : min(gap(y−1)+1, 14)}, seeded {@link TraversalGrid#DEPTH_SAT}
     *       at the world-bottom cell (nothing below minY is standable; the reader's legacy tail scan below
     *       minY reads UNBUILT and breaks, reproducing the pre-nibble behaviour exactly).</li>
     *   <li><b>runUp</b> (descending) —
     *       {@code run(y) = nav(y+1)==nav(y) ? min(run(y+1)+1, 14) : 0}, seeded 0 at the top of the built
     *       column (unbuilt above = "differs", the same hard wall the extractor's legacy scan reports).</li>
     * </ul>
     * A {@code null} section slot (a chunk shorter than the level column) ends the ascending sweep — every
     * cell above it keeps {@link TraversalGrid#DEPTH_UNKNOWN} (conservative: readers legacy-scan) — and
     * resets the descending sweep's state (cells below it read "absent above"). Single-section producers
     * ({@code classifyInto}, headless tests) never call this, so their grids stay all-UNKNOWN — correctness
     * by fallback, mirroring the s42 "single-section producers remain air-optimistic" pattern.
     */
    public static void computeDepth(NavSection[] sections) {
        final int[] colA = DEPTH_COL_A.get();
        final int[] colB = DEPTH_COL_B.get();

        {
            // Ascending floorGap sweep. colA = gap of the cell below; colB = standability of the cell below
            // (1/0). The bottom row of the bottom-most built section is the DEPTH_SAT seed.
            boolean seeded = false;
            for (int i = 0; i < sections.length; i++) {
                NavSection s = sections[i];
                if (s == null) {
                    if (seeded) break; // hole above built data: everything above keeps UNKNOWN (reset() fill)
                    continue;          // leading null slots: the first real section below is still the seed
                }
                TraversalGrid grid = s.getTraversalGrid();
                final short[] raw = grid.raw();
                final byte[] depth = grid.depthRaw();
                for (int y = 0; y < 16; y++) {
                    boolean seedRow = !seeded && y == 0;
                    for (int c = 0; c < 256; c++) {
                        int idx = (y << 8) | c;
                        int gap = seedRow ? TraversalGrid.DEPTH_SAT
                                : (colB[c] != 0 ? 0 : Math.min(colA[c] + 1, TraversalGrid.DEPTH_SAT));
                        depth[idx] = (byte) ((depth[idx] & 0xF0) | gap);
                        long d = NavBlock.descriptor((short) (raw[idx] & TraversalGrid.NAVTYPE_MASK));
                        colA[c] = gap;
                        colB[c] = NavBlock.isStandable(d) ? 1 : 0;
                    }
                    if (seedRow) seeded = true;
                }
                seeded = true;
            }
        }

        {
            // Descending runUp sweep. colA = run of the cell above; colB = navtype of the cell above
            // (-1 sentinel = absent/unbuilt above, which never equals a real navtype).
            java.util.Arrays.fill(colB, 0, 256, -1);
            java.util.Arrays.fill(colA, 0, 256, 0);
            for (int i = sections.length - 1; i >= 0; i--) {
                NavSection s = sections[i];
                if (s == null) {
                    java.util.Arrays.fill(colB, 0, 256, -1); // a hole: cells below it see "absent above"
                    continue;
                }
                TraversalGrid grid = s.getTraversalGrid();
                final short[] raw = grid.raw();
                final byte[] depth = grid.depthRaw();
                for (int y = 15; y >= 0; y--) {
                    for (int c = 0; c < 256; c++) {
                        int idx = (y << 8) | c;
                        int nav = raw[idx] & TraversalGrid.NAVTYPE_MASK;
                        int run = nav == colB[c] ? Math.min(colA[c] + 1, TraversalGrid.DEPTH_SAT) : 0;
                        depth[idx] = (byte) ((depth[idx] & 0x0F) | (run << 4));
                        colA[c] = run;
                        colB[c] = nav;
                    }
                }
            }
        }
    }

    /**
     * {@link #patchCell(NavSection, NavSection, NavSection, int, int, int, BlockState) patchCell} without
     * vertical neighbours — the headless/test convenience when no chunk column exists. Overscan reads
     * resolve to air and no below-seam propagation happens; the live block-update hook
     * ({@link NavGridUpdater}) must pass the column neighbours.
     */
    public static void patchCell(NavSection section, int lx, int ly, int lz, BlockState newState) {
        patchCell(section, null, null, lx, ly, lz, newState);
    }

    /**
     * Incrementally update one cell after a live block change (the block-update hook): set the cell's
     * navtype to {@code newState}'s, then recompute the neighbour-property flags of that cell plus the
     * small neighbourhood whose flags read it. Far cheaper than a rebuild: no palette scan, an O(1)
     * navtype write, and a small neighbourhood recompute reusing {@link NavFlags}.
     *
     * <p><b>Vertical seam (the overscan contract, both directions of the READ):</b>
     * <ul>
     *   <li><b>Upward reads</b> — the recompute window's top rows ({@code ly >= 12} changes reach cells
     *       whose {@code y+3} crosses the face) read the section ABOVE via the scratch's overscan rows,
     *       filled from {@code above} (nullable: world-top/unbuilt → air, exactly as at build time).</li>
     *   <li><b>Downward propagation</b> — a change in this section's bottom rows ({@code ly <}
     *       {@link NavFlags#OVERSCAN_ROWS}) is read by the top floor cells of the section BELOW through
     *       <i>its</i> upward overscan, so their hazard/slow/headroom bits are recomputed here too (the
     *       inverse window, rows {@code 13+ly..15} — minimal, mirroring the read footprint). {@code below}
     *       nullable: world-bottom/unbuilt → skip; an unbuilt section gets honest bits when it builds.</li>
     * </ul>
     * Lateral neighbours keep the air default (lateral overscan is the deferred follow-up — see NavFlags),
     * so a change never touches another CHUNK's data; the vertical neighbours are same-chunk by
     * construction ({@link NavStore}'s per-chunk column).
     *
     * <p>The descriptor scratch is reconstructed from resident navtypes (≈4.8k cheap array reads — well
     * below the old whole-chunk {@code refreshNavData}); the below pass refills it once more. It can be
     * windowed to the affected neighbourhood later if it ever profiles hot. {@code (lx,ly,lz)} are
     * section-local 0..15.
     */
    public static void patchCell(NavSection section, NavSection above, NavSection below,
                                 int lx, int ly, int lz, BlockState newState) {
        patchCell(section, above, below, lx, ly, lz, NavBlock.navtypeFor(newState));
    }

    /**
     * {@link #patchCell(NavSection, NavSection, NavSection, int, int, int, BlockState) patchCell} taking
     * the already-interned navtype — the state param's only use was the {@link NavBlock#navtypeFor}
     * lookup, which {@link NavGridUpdater} already pays for its navtype no-op early-out and passes down
     * (PERF-DESIGN-navgrid-edit-batching.md §4.1: the lookup moves, it doesn't add).
     */
    public static void patchCell(NavSection section, NavSection above, NavSection below,
                                 int lx, int ly, int lz, short newNavtype) {
        final long[] desc = DESC_SCRATCH.get();
        final TraversalGrid grid = section.getTraversalGrid();

        // Write the new navtype first (with its stale flags — the window recompute below fixes them), so
        // BOTH scratch rebuilds — this section's, and the below section's, which reads this grid as ITS
        // overscan — see the new block.
        grid.set(lx, ly, lz, newNavtype & 0xFFFF, grid.flags(lx, ly, lz));

        fillScratch(desc, grid, above == null ? null : above.getTraversalGrid());

        // Recompute the changed cell's flags + the cells whose flags depend on it. NavFlags.compute()
        // reads the headroom column up to y+3 and the x±1 / z±1 placeable/fluid/gravity neighbourhood,
        // so the inverse affected set is x±1 / y-3..y+1 / z±1 (clamped to this section; the part below
        // the face is the below-section pass, and cells above the face never read downward — upward-only
        // overscan means an ly=15 change can't change the above section's stored flags).
        recomputeWindow(grid, desc, lx, ly, lz);

        // Below-seam propagation: rebuild the scratch AS THE BELOW SECTION SEES IT (its navtypes + this
        // just-patched grid as overscan) and run the same inverse window on a virtual change at y = 16+ly.
        if (ly < NavFlags.OVERSCAN_ROWS && below != null) {
            final TraversalGrid belowGrid = below.getTraversalGrid();
            fillScratch(desc, belowGrid, grid);
            recomputeWindow(belowGrid, desc, lx, ly + NavSection.SIZE, lz);
        }

        // ---- Depth-nibble maintenance (floorGap/runUp). A navtype change can flip this cell's
        //      standability (dirtying the floorGap of ≤15 cells ABOVE it, crossing ≤1 seam into `above` —
        //      the mirror of the flags pass's write into `below`) and its navtype equality with its
        //      vertical neighbours (dirtying its own runUp plus ≤15 cells BELOW, ≤1 seam into `below`).
        //      Both sweeps early-out at the first fixpoint (recomputed == stored); saturation at
        //      DEPTH_SAT guarantees that within the 15-cell cap on a maintained column. On a never-swept
        //      (single-section) grid the sweeps write UNKNOWN or exact values only — never a stale claim.
        //      Measured cost: worst scenario +1.8% ns/patch (PatchStormBenchmark, ~1.4–2.1 µs/patch). ----
        patchFloorGap(grid, above == null ? null : above.getTraversalGrid(), lx, ly, lz);
        patchRunUp(grid, above == null ? null : above.getTraversalGrid(),
                below == null ? null : below.getTraversalGrid(), lx, ly, lz);
    }

    // Per-thread batch scratch for patchCells: a 4096-bit changed-cell set (one bit per section-local
    // cell) + a last-wins final-navtype slot per cell. Cleared per call (64 longs); the navtype slots
    // need no clearing (the bitmap gates every read).
    private static final ThreadLocal<long[]> BATCH_BITS = ThreadLocal.withInitial(() -> new long[64]);
    private static final ThreadLocal<short[]> BATCH_NAV = ThreadLocal.withInitial(() -> new short[4096]);

    /**
     * Patch a BATCH of cell changes in ONE section — the drain-side seam of the deferred block-edit
     * queue (PERF-DESIGN-navgrid-edit-batching.md §4.2/§4.3; the drain groups pending cells by section
     * and hands each group here with its column neighbours). This is the <b>Phase-2 phased drain</b> —
     * the §2 scratch amortization: where the sequential loop paid one full {@link #fillScratch}
     * (≈4.8k descriptor reads) per changed cell, the batch pays ONE per section (plus one for the
     * below-seam pass when any bottom-row cell changed), because flags are a pure function of the FINAL
     * navtype field and can be recomputed after all navtype writes land.
     * <ul>
     *   <li><b>Dedup</b>: events fold last-wins into a per-cell final navtype (the same fold the enqueue
     *       queue already does — repeated here so the seam keeps the "N changes arriving in sequence"
     *       contract for any caller), and cells whose final navtype equals the resident one are dropped
     *       (a net no-op recomputes byte-identical values — the {@code NavGridEpochTest} claim).</li>
     *   <li><b>P1</b>: per remaining cell (any order — each iteration starts from a depth-consistent
     *       grid and leaves one, the single-cell inheritance argument): write its navtype (with stale
     *       flags — the {@code patchCell} idiom) and immediately run its depth repairs
     *       ({@link #patchFloorGap}/{@link #patchRunUp}). Depth maintenance deliberately stays
     *       interleaved per cell rather than phased: §4.3's "ordered P3 after all navtype writes" is
     *       UNSOUND under the repairs' 15-cell cap — with two same-column changes both resident, the
     *       first cell's propagation carries both changes' influence but truncates at the cap on a
     *       non-fixpoint frontier, and the second cell's own repair then early-outs on the
     *       freshly-written prefix, never reaching the stale tail (caught by
     *       {@code BatchDrainIdentityTest}; cap+saturation is only sound for a SINGLE un-repaired
     *       change, which the sequential regime guarantees). Depth was never the amortization target —
     *       the doc's §2 shows the fixpoints don't double-work laterally — so this costs nothing the
     *       batch was buying.</li>
     *   <li><b>P2</b>: ONE {@code fillScratch} over the final field + one {@link #recomputeWindow} per
     *       changed cell, then — when any {@code ly <} {@link NavFlags#OVERSCAN_ROWS} cell changed —
     *       ONE below-seam scratch fill (the below grid with this just-patched grid as its overscan) +
     *       the inverse window per low cell, exactly the single-cell seam contract amortized per
     *       section-pair.</li>
     * </ul>
     * Final grid state is byte-identical to the sequential loop (depth: P1 IS the sequential regime,
     * one cell at a time from a consistent grid; flags = f(final navtypes) on the union of windows
     * either way, and flags read only navtypes, never depth — the {@code BatchDrainIdentityTest}
     * guard); intermediate states differ, made unobservable by the §4.4 flush barriers.
     *
     * <p>{@code packedCells[i]} is the section-local cell {@code (ly << 8) | (lz << 4) | lx} (the
     * {@link TraversalGrid} linear-index order); {@code navtypes[i]} its already-interned new navtype;
     * {@code count} the live prefix of both arrays (drain buffers are reused across drains, never
     * resized per call — no allocation on this path).
     */
    public static void patchCells(NavSection section, NavSection above, NavSection below,
                                  short[] packedCells, short[] navtypes, int count) {
        final TraversalGrid grid = section.getTraversalGrid();
        final TraversalGrid aboveGrid = above == null ? null : above.getTraversalGrid();
        final TraversalGrid belowGrid = below == null ? null : below.getTraversalGrid();
        final long[] bits = BATCH_BITS.get();
        final short[] finalNav = BATCH_NAV.get();
        Arrays.fill(bits, 0L);

        // Last-wins fold: later events for the same cell overwrite earlier ones (the navtype is a pure
        // function of the block state, so this IS "the deferred patch sees the final state").
        for (int i = 0; i < count; i++) {
            final int p = packedCells[i] & 0xFFF;
            finalNav[p] = navtypes[i];
            bits[p >>> 6] |= 1L << (p & 63);
        }

        // P1 + net-no-op filter: per changed cell — dropping cells whose final navtype already sits in
        // the grid (a one-tick toggle: nothing any window or depth sweep reads has changed) — write the
        // final navtype (stale flags; P2 fixes them) and repair the depth nibbles IMMEDIATELY, so every
        // repair runs against a grid with exactly ONE un-repaired change (the sequential single-cell
        // regime the 15-cell cap + saturation argument is sound for — see the method doc).
        int changed = 0;
        boolean anyLow = false;
        for (int w = 0; w < 64; w++) {
            long b = bits[w];
            while (b != 0) {
                final int p = (w << 6) | Long.numberOfTrailingZeros(b);
                b &= b - 1;
                final int lx = p & 15, ly = p >>> 8, lz = (p >>> 4) & 15;
                final int nav = finalNav[p] & 0xFFFF;
                if (nav == grid.navtype(lx, ly, lz)) {
                    bits[w] &= ~(1L << (p & 63));
                    continue;
                }
                grid.set(lx, ly, lz, nav, grid.flags(lx, ly, lz));
                patchFloorGap(grid, aboveGrid, lx, ly, lz);
                patchRunUp(grid, aboveGrid, belowGrid, lx, ly, lz);
                changed++;
                anyLow |= ly < NavFlags.OVERSCAN_ROWS;
            }
        }
        if (changed == 0) return;

        // P2: one scratch fill over the FINAL field, then the inverse-footprint window per changed cell
        // (overlapping windows recompute a shared cell twice — idempotent, flags are a pure function of
        // the scratch). Then the amortized below-seam pass: one refill AS THE BELOW SECTION SEES IT and
        // the inverse window per bottom-row cell (rows 13+ly..15 of the below grid — the exact footprint
        // whose flags read this section through their upward overscan).
        final long[] desc = DESC_SCRATCH.get();
        fillScratch(desc, grid, aboveGrid);
        for (int w = 0; w < 64; w++) {
            long b = bits[w];
            while (b != 0) {
                final int p = (w << 6) | Long.numberOfTrailingZeros(b);
                b &= b - 1;
                recomputeWindow(grid, desc, p & 15, p >>> 8, (p >>> 4) & 15);
            }
        }
        if (anyLow && belowGrid != null) {
            fillScratch(desc, belowGrid, grid);
            for (int w = 0; w < (NavFlags.OVERSCAN_ROWS * 256) >> 6; w++) { // words 0..11 = ly 0..2
                long b = bits[w];
                while (b != 0) {
                    final int p = (w << 6) | Long.numberOfTrailingZeros(b);
                    b &= b - 1;
                    recomputeWindow(belowGrid, desc, p & 15, (p >>> 8) + NavSection.SIZE, (p >>> 4) & 15);
                }
            }
        }
    }

    /** Standability of a grid cell from its resident navtype (the exact predicate the floorGap encodes). */
    private static boolean standableAt(TraversalGrid g, int x, int y, int z) {
        return NavBlock.isStandable(NavBlock.descriptor((short) g.navtype(x, y, z)));
    }

    /**
     * Upward floorGap recompute after a navtype change at {@code (lx,ly,lz)} (E3 invalidation): re-derive
     * the cells above from the §2 recurrence, seeded by the changed cell's OWN (unchanged — it depends only
     * on cells below) stored gap and its NEW standability, stopping at the first fixpoint. {@code aboveGrid}
     * nullable (world-top/unbuilt: nothing stored there to fix).
     */
    private static void patchFloorGap(TraversalGrid grid, TraversalGrid aboveGrid, int lx, int ly, int lz) {
        boolean prevStand = standableAt(grid, lx, ly, lz);
        int prevGap = grid.floorGap(lx, ly, lz);
        for (int step = 1; step <= 15; step++) {
            int wy = ly + step;
            TraversalGrid g = wy < NavSection.SIZE ? grid : aboveGrid;
            if (g == null) return;
            int cy = wy & 15;
            int gNew = prevStand ? 0
                    : (prevGap == TraversalGrid.DEPTH_UNKNOWN ? TraversalGrid.DEPTH_UNKNOWN
                        : Math.min(prevGap + 1, TraversalGrid.DEPTH_SAT));
            if (g.floorGap(lx, cy, lz) == gNew) return; // fixpoint — everything above already consistent
            g.setFloorGap(lx, cy, lz, gNew);
            prevStand = standableAt(g, lx, cy, lz);
            prevGap = gNew;
        }
    }

    /**
     * runUp recompute after a navtype change at {@code (lx,ly,lz)} (E4 invalidation): the changed cell's
     * own run is re-derived from the cell above it (≤1 seam read into {@code aboveGrid}), then the change
     * propagates DOWNWARD (the inverse footprint of an upward-counting field) with fixpoint early-out,
     * ≤15 cells, ≤1 seam into {@code belowGrid}. Both neighbours nullable (world edge / unbuilt).
     */
    private static void patchRunUp(TraversalGrid grid, TraversalGrid aboveGrid, TraversalGrid belowGrid,
                                   int lx, int ly, int lz) {
        int navHere = grid.navtype(lx, ly, lz);
        int aboveNav = -1, aboveRun = 0; // -1 = absent above (never equals a real navtype)
        if (ly < NavSection.SIZE - 1) {
            aboveNav = grid.navtype(lx, ly + 1, lz);
            aboveRun = grid.runUp(lx, ly + 1, lz);
        } else if (aboveGrid != null) {
            aboveNav = aboveGrid.navtype(lx, 0, lz);
            aboveRun = aboveGrid.runUp(lx, 0, lz);
        }
        int prevRun = navHere == aboveNav
                ? (aboveRun == TraversalGrid.DEPTH_UNKNOWN ? TraversalGrid.DEPTH_UNKNOWN
                    : Math.min(aboveRun + 1, TraversalGrid.DEPTH_SAT))
                : 0;
        grid.setRunUp(lx, ly, lz, prevRun);
        int prevNav = navHere;
        for (int step = 1; step <= 15; step++) {
            int wy = ly - step;
            TraversalGrid g = wy >= 0 ? grid : belowGrid;
            if (g == null) return;
            int cy = wy & 15;
            int nav = g.navtype(lx, cy, lz);
            int rNew = nav == prevNav
                    ? (prevRun == TraversalGrid.DEPTH_UNKNOWN ? TraversalGrid.DEPTH_UNKNOWN
                        : Math.min(prevRun + 1, TraversalGrid.DEPTH_SAT))
                    : 0;
            if (g.runUp(lx, cy, lz) == rNew) return; // fixpoint — everything below already consistent
            g.setRunUp(lx, cy, lz, rNew);
            prevNav = nav;
            prevRun = rNew;
        }
    }

    /** The inverse-footprint flags recompute around a changed cell (which may sit in the overscan rows —
     *  the window then clamps to this grid's top rows). Navtypes stay resident. */
    private static void recomputeWindow(TraversalGrid grid, long[] desc, int lx, int ly, int lz) {
        for (int y = clampCell(ly - 3); y <= clampCell(ly + 1); y++) {
            for (int z = clampCell(lz - 1); z <= clampCell(lz + 1); z++) {
                for (int x = clampCell(lx - 1); x <= clampCell(lx + 1); x++) {
                    grid.set(x, y, z, grid.navtype(x, y, z), NavFlags.compute(desc, x, y, z));
                }
            }
        }
    }

    private static int clampCell(int v) {
        return v < 0 ? 0 : (v >= NavSection.SIZE ? NavSection.SIZE - 1 : v);
    }
}
