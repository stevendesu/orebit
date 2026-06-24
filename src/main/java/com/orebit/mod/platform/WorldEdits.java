package com.orebit.mod.platform;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The thin server-side world-mutation seam the path follower uses to execute a step's folded
 * break/place edits ({@link com.orebit.mod.pathfinding.blockpathfinder.StepEdits}). It isolates the two
 * MC-API calls that a fake player would otherwise make directly, so any future signature drift across
 * the 1.17 → 26.x range is fixed here (an overlay flavor) rather than in the common follower.
 *
 * <p>These calls have been stable across the whole supported range, so this lives in the core baseline
 * with no overlay override today; it exists as a named seam so the follower never touches {@code Level}
 * mutation directly. (The replaceable / breakable <i>predicates</i> the planner uses live on {@link
 * com.orebit.mod.worldmodel.navblock.NavBlock} via the {@link Replaceable} shim; this is only the act of
 * mutating.)
 */
public final class WorldEdits {
    private WorldEdits() {}

    /**
     * Break the block at {@code pos} server-side, <b>without</b> dropping items — the bot has no
     * inventory model yet, so drops would just litter the world. Mirrors a creative-mode break.
     */
    public static void breakBlock(ServerLevel level, BlockPos pos) {
        level.destroyBlock(pos, false);
    }

    /** Place {@code state} at {@code pos}, applying the normal neighbour updates. */
    public static void placeBlock(ServerLevel level, BlockPos pos, BlockState state) {
        level.setBlockAndUpdate(pos, state);
    }
}
