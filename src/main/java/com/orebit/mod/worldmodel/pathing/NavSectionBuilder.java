package com.orebit.mod.worldmodel.pathing;

import java.lang.reflect.Field;

import net.minecraft.core.BlockPos;
import net.minecraft.core.IdMap;
import net.minecraft.util.BitStorage;
import net.minecraft.util.CrudeIncrementalIntIdentityHashBiMap;
import net.minecraft.world.level.Level;
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

    // NOTE (Phase 0 / Mojmap migration): the reflection below is migrated to
    // Mojang mapping names but otherwise unchanged. It is consumed by the JMH
    // reference benchmark (profile.BlockReadBenchmark) per PRD §8. Phase 1
    // reimplements build() as a correct read+classify with the uniform-section
    // bypass and replaces this reflection with an access-widener
    // (orebit.accesswidener) — at which point these helpers move/retire.
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
        } catch (NoSuchFieldException | ClassNotFoundException e) {
            throw new RuntimeException("Failed to initialize reflection fields", e);
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
     * Builds a new NavSection at the given origin (must be aligned to a 16x16x16 chunk section boundary).
     */
    public static NavSection build(Level world, LevelChunkSection chunkSection, BlockPos origin, LevelChunkSection nextSection) {
        NavSection section = NavSection.create(origin);

        // Placeholder STEP-1 read (replaced by the real read+classify in Phase 1).
        final int origX = origin.getX();
        final int origY = origin.getY();
        final int origZ = origin.getZ();
        for (int i = 0; i < 100; i++) {
            int x = i % 10;
            int y = 8;
            int z = i / 10;
            BlockPos pos = new BlockPos(origX + x, origY + y, origZ + z);
            sink = world.getBlockState(pos).isRedstoneConductor(world, pos);
        }

        /*
        // Phase 1 target: populate blocks array for quick access, then classify.
        for (int y = 0; y < NavSection.SIZE; y++) {
            for (int z = 0; z < NavSection.SIZE; z++) {
                for (int x = 0; x < NavSection.SIZE; x++) {
                    blocks[y + z * 16 + x * 16 * 16] = chunkSection.getBlockState(x, y, z);
                }
            }
        }

        for (int y = 0; y < NavSection.SIZE; y++) {
            for (int z = 0; z < NavSection.SIZE; z++) {
                for (int x = 0; x < NavSection.SIZE; x++) {
                    TraversalClass tc = TraversalAnalyzerMutable.classify(world, blocks, x, y, z);
                    section.setTraversalClass(x, y, z, tc);
                }
            }
        }
        */

        return section;
    }
}
