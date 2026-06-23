package com.orebit.mod.worldmodel.pathing;

import java.lang.reflect.Field;
import java.util.Arrays;

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
     * descriptor, and classify every cell into the 2-bit {@link TraversalGrid}.
     *
     * <p>Uniform-air sections (the majority underground/skyward) are bypassed: every cell classifies
     * identically, so we classify one representative cell and fill — the big recompute win (PRD §6.2).
     * Out-of-section neighbour reads currently resolve to air (matching the prior analyzer's edge
     * behaviour); cross-section overscan is a later refinement.
     *
     * <p>The origin must be aligned to a 16×16×16 chunk-section boundary.
     */
    public static NavSection build(LevelChunkSection section, BlockPos origin) {
        NavSection navSection = NavSection.create(origin);
        classifyInto(section.getStates(), Sections.hasOnlyAir(section), navSection.getTraversalGrid());
        return navSection;
    }

    /**
     * Core classify pass over a section's state container. Split out from {@link #build} so it can be
     * exercised headless without constructing a {@link LevelChunkSection}.
     */
    public static void classifyInto(PalettedContainer<BlockState> states, boolean onlyAir, TraversalGrid grid) {
        final int[] slotScratch = SLOT_SCRATCH.get();
        final long[] descScratch = DESC_SCRATCH.get();

        if (onlyAir) {
            // Every cell is air over air: classify one representative cell and fill.
            Arrays.fill(descScratch, AIR_DESC);
            TraversalClass airClass = NavClassifier.classify(descScratch, 8, 8, 8);
            fill(grid, airClass);
            return;
        }

        // Map the (small) palette to descriptors once, then resolve every cell via its slot —
        // no per-cell state lookup.
        BlockState[] palette = SectionPalette.read(states, slotScratch);
        long[] slotToDesc = new long[palette.length];
        for (int s = 0; s < palette.length; s++) {
            slotToDesc[s] = NavBlock.descriptorFor(palette[s]);
        }
        for (int i = 0; i < 4096; i++) {
            descScratch[i] = slotToDesc[slotScratch[i]];
        }

        for (int y = 0; y < NavSection.SIZE; y++) {
            for (int z = 0; z < NavSection.SIZE; z++) {
                for (int x = 0; x < NavSection.SIZE; x++) {
                    grid.set(x, y, z, NavClassifier.classify(descScratch, x, y, z));
                }
            }
        }
    }

    private static void fill(TraversalGrid grid, TraversalClass tc) {
        for (int y = 0; y < NavSection.SIZE; y++) {
            for (int z = 0; z < NavSection.SIZE; z++) {
                for (int x = 0; x < NavSection.SIZE; x++) {
                    grid.set(x, y, z, tc);
                }
            }
        }
    }
}
