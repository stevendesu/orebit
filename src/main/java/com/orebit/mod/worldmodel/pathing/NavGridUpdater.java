package com.orebit.mod.worldmodel.pathing;

import com.orebit.mod.platform.BlockChangeEvents;
import com.orebit.mod.platform.LevelBounds;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Keeps the nav grid live by patching it on every block change — the block-update hook that retires the
 * follower's per-replan {@code refreshNavData} rebuild. Registers a {@link BlockChangeEvents.Listener};
 * for a server-side change inside a built section it updates that cell's navtype and recomputes the small
 * within-section neighbourhood via {@link NavSectionBuilder#patchCell} (no palette scan). Changes in
 * chunks we don't track yet are ignored — they build fresh on load.
 *
 * <p>The trigger is the {@code setBlockState} mixin firing {@link BlockChangeEvents#fire}; until that
 * overlay is wired this listener simply never runs (registering it is harmless).
 */
public final class NavGridUpdater {
    private NavGridUpdater() {}

    /** Register the nav-grid patcher against the block-change seam (once, at init). */
    public static void register() {
        BlockChangeEvents.register(NavGridUpdater::onBlockChanged);
    }

    private static void onBlockChanged(Level level, BlockPos pos, BlockState oldState, BlockState newState) {
        if (!(level instanceof ServerLevel server)) return; // server authority only
        if (oldState == newState) return;                    // interned states: reference-equal == no change

        int sectionIndex = (pos.getY() - LevelBounds.minY(server)) >> 4;
        if (sectionIndex < 0) return;
        NavSection[] sections = NavStore.get(server, NavStore.key(pos.getX() >> 4, pos.getZ() >> 4));
        if (sections == null || sectionIndex >= sections.length) return; // chunk not tracked
        NavSection section = sections[sectionIndex];
        if (section == null) return;

        NavSectionBuilder.patchCell(section, pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15, newState);
    }
}
