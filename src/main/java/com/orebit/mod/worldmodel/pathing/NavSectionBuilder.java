package com.orebit.mod.worldmodel.pathing;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.function.IntConsumer;

import com.orebit.mod.OrebitCommon;
import com.orebit.mod.platform.SectionPalette;
import com.orebit.mod.platform.Sections;
import com.orebit.mod.worldmodel.navblock.NavBlock;

import net.minecraft.core.BlockPos;
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

    // Per-THREAD scratch (slot-per-cell + resolved descriptors). classifyInto reads/writes only
    // these plus the grid it's handed, so the classify kernel is safe to run on a worker pool — we
    // deliberately do NOT couple it to single-threaded execution. Reusing per thread also avoids
    // ~64 KB of garbage per section. (The remaining single-thread touchpoint is NavSectionPool in
    // build(); make it concurrent/per-thread when section building is moved off the tick thread.)
    private static final ThreadLocal<int[]> SLOT_SCRATCH = ThreadLocal.withInitial(() -> new int[4096]);
    private static final ThreadLocal<long[]> DESC_SCRATCH = ThreadLocal.withInitial(() -> new long[4096]);
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
     * Build a NavSection for one chunk section: read its block states, map each to a NavBlock
     * navtype + descriptor, and pack every cell's navtype and neighbour-property flags into the
     * {@link TraversalGrid}.
     *
     * <p>Uniform-air sections (the majority underground/skyward) are bypassed: every cell classifies
     * identically, so we classify one representative cell and fill — the big recompute win (PRD §6.2).
     * Out-of-section neighbour reads currently resolve to air (matching the prior analyzer's edge
     * behaviour); cross-section overscan is a later refinement.
     *
     * <p>The origin must be aligned to a 16×16×16 chunk-section boundary.
     */
    public static NavSection build(LevelChunkSection section, BlockPos origin) {
        return build(section, origin, null);
    }

    /**
     * As {@link #build(LevelChunkSection, BlockPos)}, additionally reporting the section-local cell index
     * ({@code (y<<8)|(z<<4)|x}) of every nether-portal cell to {@code portalCells} (nullable) — the
     * full-section discovery feed for {@link NetherPortalIndex}. A null section is all air, so no portals.
     */
    public static NavSection build(LevelChunkSection section, BlockPos origin, IntConsumer portalCells) {
        NavSection navSection = NavSection.create(origin);
        if (section == null) {
            // Pre-1.18, an empty section is NULL in the chunk's section array; 1.18+ always allocates a
            // real (empty) LevelChunkSection, never null. A null section is entirely air, so classify it
            // as all-air rather than dereferencing it (this NPE'd on 1.17.1 the moment a chunk with an
            // empty section ticked). Guard lives in core — harmless on 1.18+ where it never triggers.
            classifyAir(navSection.getTraversalGrid());
        } else {
            classifyInto(section.getStates(), Sections.hasOnlyAir(section),
                    navSection.getTraversalGrid(), portalCells);
        }
        return navSection;
    }

    /**
     * Core classify pass over a section's state container. Split out from {@link #build} so it can be
     * exercised headless without constructing a {@link LevelChunkSection}.
     */
    public static void classifyInto(PalettedContainer<BlockState> states, boolean onlyAir, TraversalGrid grid) {
        classifyInto(states, onlyAir, grid, null);
    }

    /**
     * As {@link #classifyInto(PalettedContainer, boolean, TraversalGrid)}, additionally reporting the
     * section-local cell index ({@code (y<<8)|(z<<4)|x}) of every nether-portal cell to {@code portalCells}
     * (nullable). <b>Fast-path constraint:</b> when the palette holds no portal (the overwhelming case),
     * the extra cost is exactly one bit-test per palette entry — the 4096-cell collection pass runs ONLY
     * when a portal entry is actually present. Air-only sections skip everything.
     */
    public static void classifyInto(PalettedContainer<BlockState> states, boolean onlyAir, TraversalGrid grid,
                                    IntConsumer portalCells) {
        final int[] slotScratch = SLOT_SCRATCH.get();
        final long[] descScratch = DESC_SCRATCH.get();

        if (onlyAir) {
            classifyAir(grid);
            return;
        }

        // Map the (small) palette to navtypes + descriptors once, then resolve every cell via its slot —
        // no per-cell state lookup. We store the navtype per cell (the fine geometry index) alongside the
        // neighbour-property flags; the descriptor scratch is only needed transiently to compute them.
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
        for (int i = 0; i < 4096; i++) {
            descScratch[i] = slotToDesc[slotScratch[i]];
        }

        // Portal collection: gated on the palette actually containing one (portals are vanishingly rare),
        // so the per-cell pass never taxes the normal classify path.
        if (portalCells != null && anyPortal) {
            for (int i = 0; i < 4096; i++) {
                if (NavBlock.isPortal(descScratch[i])) portalCells.accept(i);
            }
        }

        for (int y = 0; y < NavSection.SIZE; y++) {
            for (int z = 0; z < NavSection.SIZE; z++) {
                for (int x = 0; x < NavSection.SIZE; x++) {
                    int navtype = slotToNavtype[slotScratch[(y << 8) | (z << 4) | x]];
                    grid.set(x, y, z, navtype, NavFlags.compute(descScratch, x, y, z));
                }
            }
        }
    }

    /**
     * Compute flags for a grid that is entirely air — the shared path for uniform-air sections (the
     * underground/skyward bypass) and pre-1.18 null sections. One representative cell is computed over an
     * all-air descriptor scratch (every cell is identical), then the whole grid is filled with air's
     * navtype + those flags.
     */
    private static void classifyAir(TraversalGrid grid) {
        final long[] descScratch = DESC_SCRATCH.get();
        Arrays.fill(descScratch, AIR_DESC);
        int airFlags = NavFlags.compute(descScratch, 8, 8, 8);
        fill(grid, NavBlock.AIR & 0xFFFF, airFlags);
    }

    private static void fill(TraversalGrid grid, int navtype, int flags) {
        for (int y = 0; y < NavSection.SIZE; y++) {
            for (int z = 0; z < NavSection.SIZE; z++) {
                for (int x = 0; x < NavSection.SIZE; x++) {
                    grid.set(x, y, z, navtype, flags);
                }
            }
        }
    }

    /**
     * Incrementally update one cell after a live block change (the block-update hook): set the cell's
     * navtype to {@code newState}'s, then recompute the neighbour-property flags of that cell plus the
     * small <b>within-section</b> neighbourhood whose flags read it. Out-of-section neighbours keep the
     * air default (no overscan), so a change never touches another section's data — the update is
     * self-contained. Far cheaper than a rebuild: no palette scan, an O(1) navtype write, and a small
     * neighbourhood recompute reusing {@link NavFlags}.
     *
     * <p>The descriptor scratch is reconstructed from the section's own resident navtypes (≈4096 cheap
     * array reads — well below the old whole-chunk {@code refreshNavData}); it can be windowed to the
     * affected neighbourhood later if it ever profiles hot. {@code (lx,ly,lz)} are section-local 0..15.
     */
    public static void patchCell(NavSection section, int lx, int ly, int lz, BlockState newState) {
        final long[] desc = DESC_SCRATCH.get();
        final TraversalGrid grid = section.getTraversalGrid();
        final short newNavtype = NavBlock.navtypeFor(newState);

        // Rebuild this section's descriptors from its resident navtypes, then patch the changed cell so
        // the reclassify below sees the new block.
        for (int y = 0; y < NavSection.SIZE; y++) {
            for (int z = 0; z < NavSection.SIZE; z++) {
                for (int x = 0; x < NavSection.SIZE; x++) {
                    desc[(y << 8) | (z << 4) | x] = NavBlock.descriptor((short) grid.navtype(x, y, z));
                }
            }
        }
        desc[(ly << 8) | (lz << 4) | lx] = NavBlock.descriptor(newNavtype);

        // Recompute the changed cell's flags + the cells whose flags depend on it. NavFlags.compute()
        // reads the headroom column up to y+3 and the x±1 / z±1 placeable/fluid/gravity neighbourhood,
        // so the inverse affected set is x±1 / y-3..y+1 / z±1. Each cell keeps its own navtype (only the
        // changed cell's navtype is new).
        for (int y = clampCell(ly - 3); y <= clampCell(ly + 1); y++) {
            for (int z = clampCell(lz - 1); z <= clampCell(lz + 1); z++) {
                for (int x = clampCell(lx - 1); x <= clampCell(lx + 1); x++) {
                    int navtype = (x == lx && y == ly && z == lz) ? (newNavtype & 0xFFFF)
                            : grid.navtype(x, y, z);
                    grid.set(x, y, z, navtype, NavFlags.compute(desc, x, y, z));
                }
            }
        }
    }

    private static int clampCell(int v) {
        return v < 0 ? 0 : (v >= NavSection.SIZE ? NavSection.SIZE - 1 : v);
    }
}
