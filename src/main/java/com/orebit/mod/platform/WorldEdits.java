package com.orebit.mod.platform;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
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

    /**
     * OPEN or CLOSE the (hand-toggleable) door at {@code pos} server-side, authoritatively — the "right-click
     * the door" world edit the DOORS follower executes in place of smashing it (DOORS P3). Faithful to how a
     * player operates a door <b>without simulating a right-click</b>: {@link DoorBlock#setOpen} is the vanilla
     * entry point the interaction path itself calls, so a direct call is the same authoritative mutation as
     * {@link #breakBlock}/{@link #placeBlock} are for mining/placing. It also does the two-halves sync (pass
     * EITHER half's {@code pos}), the open/close sound, and the block game-event, so calling it on one half
     * swings the whole door.
     *
     * <p><b>Two guards, both belt-and-suspenders.</b> A non-door {@code pos} (stale grid, block changed since
     * planning) is a no-op — the {@code instanceof DoorBlock} test skips it. An <b>iron</b> door is refused
     * even though {@code setOpen} is not itself gated and WOULD swing it: a player cannot hand-operate an iron
     * door (redstone only), so opening one would be non-faithful. The P2 planner already never folds a door-set
     * for iron (it lacks {@link com.orebit.mod.worldmodel.navblock.NavBlock#doorToggleable}), so this refusal is
     * a parity backstop for the same reason the executor re-checks {@code mayBreak} before a mine.
     *
     * <p><b>Version stability.</b> {@code DoorBlock.setOpen(Entity, Level, BlockState, BlockPos, boolean)} is
     * byte-identical across the whole supported range (1.17.1 → 26.2), so — like the break/place calls above —
     * this stays in the core baseline with no overlay flavor.
     */
    public static void setDoorOpen(ServerLevel level, BlockPos pos, Entity actor, boolean open) {
        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();
        if (block instanceof DoorBlock door && block != Blocks.IRON_DOOR) {
            door.setOpen(actor, level, state, pos, open);
        }
    }
}
