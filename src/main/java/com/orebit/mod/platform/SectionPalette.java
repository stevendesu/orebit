package com.orebit.mod.platform;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import net.minecraft.util.BitStorage;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.Palette;
import net.minecraft.world.level.chunk.PalettedContainer;

/**
 * Reads a chunk section's block states into a per-cell palette-slot array — the one place that
 * touches {@link PalettedContainer} internals.
 *
 * <p><b>Why a seam:</b> the per-block hot path must not go through the World API. The fast path
 * grabs the section's {@code BitStorage} + {@code Palette} <i>once per section</i> (a couple of
 * reflective field reads), then iterates them through public API ({@link BitStorage#get(int)},
 * {@link Palette#valueFor(int)} / {@link Palette#getSize()}). Reflection is therefore per-section,
 * not per-block (≈ a handful of reads out of 4096), so it costs no more than an access-widener would
 * here — see the NavBlock/section design notes. Swapping in an access-widener later only changes how
 * these two objects are obtained, not the loop.
 *
 * <p><b>Self-degrading:</b> the fast path reflects {@code PalettedContainer.data} and the
 * {@code Data} record's {@code storage}/{@code palette}. On versions whose internals differ (e.g.
 * pre-1.18, before the {@code Data} record), reflection fails once at class-load (or per call) and
 * we fall back to the public {@link PalettedContainer#get(int, int, int)} accessor — correct
 * everywhere, just slower, and only on those versions. No per-version overlay required.
 */
public final class SectionPalette {

    private SectionPalette() {}

    private static final boolean REFLECTIVE;
    private static Field dataField;
    private static Field storageField;
    private static Field paletteField;

    static {
        boolean ok = false;
        try {
            dataField = PalettedContainer.class.getDeclaredField("data");
            dataField.setAccessible(true);
            Class<?> dataClass = Class.forName("net.minecraft.world.level.chunk.PalettedContainer$Data");
            storageField = dataClass.getDeclaredField("storage");
            storageField.setAccessible(true);
            paletteField = dataClass.getDeclaredField("palette");
            paletteField.setAccessible(true);
            ok = true;
        } catch (Throwable t) {
            ok = false; // pre-Data-record layout (or a future change) — use the public fallback.
        }
        REFLECTIVE = ok;
    }

    /**
     * Read a section's block states. Fills {@code slotsOut} (length 4096, canonical
     * {@code (y<<8)|(z<<4)|x} order) with a palette slot per cell, and returns the palette
     * (slot → {@link BlockState}). The caller maps the (small) palette to navtypes once, then
     * resolves every cell through {@code slotsOut} — no per-cell state lookup.
     */
    @SuppressWarnings("unchecked")
    public static BlockState[] read(PalettedContainer<BlockState> container, int[] slotsOut) {
        if (REFLECTIVE) {
            try {
                Object data = dataField.get(container);
                BitStorage storage = (BitStorage) storageField.get(data);
                Palette<BlockState> palette = (Palette<BlockState>) paletteField.get(data);
                int size = palette.getSize();
                BlockState[] states = new BlockState[size];
                for (int i = 0; i < size; i++) states[i] = palette.valueFor(i);
                for (int i = 0; i < 4096; i++) slotsOut[i] = storage.get(i);
                return states;
            } catch (Throwable ignored) {
                // Fall through to the public path on any reflective mismatch.
            }
        }
        return readPublic(container, slotsOut);
    }

    private static BlockState[] readPublic(PalettedContainer<BlockState> container, int[] slotsOut) {
        Map<BlockState, Integer> slotOf = new HashMap<>();
        ArrayList<BlockState> states = new ArrayList<>();
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState s = container.get(x, y, z);
                    Integer slot = slotOf.get(s);
                    if (slot == null) {
                        slot = states.size();
                        slotOf.put(s, slot);
                        states.add(s);
                    }
                    slotsOut[(y << 8) | (z << 4) | x] = slot;
                }
            }
        }
        return states.toArray(new BlockState[0]);
    }
}
