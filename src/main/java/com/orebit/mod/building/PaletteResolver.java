package com.orebit.mod.building;

import java.util.Map;

import com.orebit.mod.platform.BlockLookup;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Resolves a parsed {@link Schematic.PaletteEntry} to its EXACT vanilla {@link BlockState}
 * (DESIGN-bot-abilities.md §6): block id through the {@link BlockLookup} seam, then every stored
 * property applied BY NAME through the generic state API — {@code Property#getName}/
 * {@code getValue(String)}/{@code StateHolder#setValue}, all javap-pinned byte-stable
 * 1.17.1 → 26.2 (this is the same no-per-version-class trick the crop age read uses). An unknown
 * block id (newer-version or modded palette entry) resolves to {@code null} — the builder skips
 * and REPORTS those cells rather than substituting; an unknown property/value is ignored
 * individually (the rest of the state still applies).
 */
public final class PaletteResolver {

    private PaletteResolver() {}

    /** The exact state for {@code entry}, or {@code null} when its block id is unknown here. */
    public static BlockState resolve(Schematic.PaletteEntry entry) {
        final Block block = BlockLookup.byId(entry.blockId());
        if (block == null) return null;
        BlockState state = block.defaultBlockState();
        for (Map.Entry<String, String> e : entry.properties().entrySet()) {
            for (Property<?> p : state.getProperties()) {
                if (p.getName().equals(e.getKey())) {
                    state = apply(state, p, e.getValue());
                    break;
                }
            }
        }
        return state;
    }

    private static <T extends Comparable<T>> BlockState apply(BlockState s, Property<T> p, String raw) {
        return p.getValue(raw).map(v -> s.setValue(p, v)).orElse(s);
    }
}
