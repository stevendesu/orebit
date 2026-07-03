package com.orebit.mod.worldmodel.pathing;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.function.IntConsumer;

import com.orebit.mod.OrebitCommon;
import com.orebit.mod.platform.SectionPalette;
import com.orebit.mod.platform.Sections;
import com.orebit.mod.worldmodel.navblock.NavBlock;

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
        if (section == null) return classifyNavtypes(null, true, grid, portalCells);
        return classifyNavtypes(section.getStates(), Sections.hasOnlyAir(section), grid, portalCells);
    }

    /**
     * Headless form of {@link #classifyNavtypes(LevelChunkSection, TraversalGrid, IntConsumer)} over a
     * bare state container ({@code states} is not touched when {@code onlyAir}, so it may be null then).
     */
    public static boolean classifyNavtypes(PalettedContainer<BlockState> states, boolean onlyAir,
                                           TraversalGrid grid, IntConsumer portalCells) {
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
        boolean anyPortal = false;
        for (int s = 0; s < palette.length; s++) {
            short navtype = NavBlock.navtypeFor(palette[s]);
            slotToNavtype[s] = navtype & 0xFFFF;
            slotToDesc[s] = NavBlock.descriptor(navtype);
            anyPortal |= NavBlock.isPortal(slotToDesc[s]); // the one-bit-test-per-palette-entry gate
        }

        // Portal collection: gated on the palette actually containing one (portals are vanishingly rare),
        // so the per-cell pass never taxes the normal classify path.
        if (portalCells != null && anyPortal) {
            for (int i = 0; i < 4096; i++) {
                if (NavBlock.isPortal(slotToDesc[slotScratch[i]])) portalCells.accept(i);
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
        final long[] desc = DESC_SCRATCH.get();
        final TraversalGrid grid = section.getTraversalGrid();
        final short newNavtype = NavBlock.navtypeFor(newState);

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
