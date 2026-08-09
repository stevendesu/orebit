package com.orebit.mod.platform;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

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

    /**
     * OPEN or CLOSE the (hand-toggleable) trapdoor at {@code pos} server-side, authoritatively — the trapdoor
     * twin of {@link #setDoorOpen} (DESIGN-trapdoors.md §7). Unlike {@code DoorBlock}, <b>{@code TrapDoorBlock}
     * has no {@code setOpen} convenience at any version</b> (member sweeps 1.17.1 → 26.2), so this is a
     * hand-rolled authoritative write in the house style (no interaction stack): force the {@code OPEN}
     * property to the target and {@code setBlock} it. Single cell — a trapdoor has no two-half sync.
     *
     * <p><b>Two guards, mirroring the door verb.</b> A non-trapdoor {@code pos} (stale grid) is a no-op via
     * the {@code instanceof} test; an <b>iron</b> trapdoor is refused ({@code Blocks.IRON_TRAPDOOR} is the
     * complete vanilla hand-toggle exception set at every version — copper trapdoors ARE hand-openable). A
     * trapdoor already at the target state is a no-op too, so re-issuing the verb never spams block updates.
     *
     * <p><b>Deliberately setBlock-only — the vanilla sound/game-event side effects are DROPPED</b>, per the
     * ratified drift rule (§7: javap-pin the side-effect surface or ship without it). Both candidates drift:
     * <ul>
     *   <li><b>Sound</b>: the classic wood-trapdoor level events (ids 1007 open / 1013 close) are DEAD on
     *       modern clients — the client handler's switch has no case for them at 1.21.11 or 26.2, and 26.2's
     *       {@code LevelEvent} class dropped the constants outright (javap-verified; vanilla switched trapdoors
     *       to {@code BlockSetType} sounds at 1.19.4). The 4-arg {@code levelEvent} overload also churns
     *       {@code Player}&rarr;{@code Entity} at 1.21.5, and the modern {@code playSound}/{@code BlockSetType}
     *       path does not exist &le;1.19.3 (and {@code getType()} is protected). No single sound surface spans
     *       the range.</li>
     *   <li><b>Game event</b>: {@code GameEvent.BLOCK_OPEN}'s field type churns {@code GameEvent} &rarr;
     *       {@code Holder.Reference<GameEvent>} mid-range (javap 1.17.1 vs 1.21.1) — source-incompatible for
     *       common code.</li>
     * </ul>
     * So the toggle is silent and sculk-invisible in v1 — accepted; an overlay flavor can restore parity later.
     * The waterlogged fluid re-tick vanilla schedules is likewise skipped (the tick-scheduler API churned at
     * 1.18.2); flag {@code 3} (neighbour updates + client sync, per the ratified §7 form) lets neighbours react.
     *
     * <p><b>Version stability of what IS used</b> (all javap-verified 1.17.1 → 26.2): {@code instanceof
     * TrapDoorBlock} (class stable, copper subclasses extend it), {@code Blocks.IRON_TRAPDOOR},
     * {@code BlockStateProperties.OPEN} ({@code BooleanProperty}), {@code StateHolder.getValue/setValue}, and
     * {@code Level.setBlock(BlockPos, BlockState, int)}. Core baseline, no overlay flavor. ({@code actor} is
     * unused today — carried for seam symmetry with {@link #setDoorOpen}, and it is what a future sound/
     * game-event parity overlay would attribute the toggle to.)
     */
    public static void setTrapdoorOpen(ServerLevel level, BlockPos pos, Entity actor, boolean open) {
        BlockState state = level.getBlockState(pos);
        Block block = state.getBlock();
        if (block instanceof TrapDoorBlock && block != Blocks.IRON_TRAPDOOR) {
            if (state.getValue(BlockStateProperties.OPEN) == open) {
                return; // already at target — no redundant block update
            }
            level.setBlock(pos, state.setValue(BlockStateProperties.OPEN, open), 3);
        }
    }

    /**
     * OPEN or CLOSE the fence gate at {@code pos} server-side, authoritatively — the gate member of the
     * {@link #setDoorOpen}/{@link #setTrapdoorOpen} verb family (DESIGN-fence-gates.md §4). Like {@code
     * TrapDoorBlock} — and unlike {@code DoorBlock} — <b>{@code FenceGateBlock} has no {@code setOpen}
     * convenience at any version</b> (member sweeps 1.17.1 → 26.2), so this is the same hand-rolled
     * authoritative write in the house style (no interaction stack): force the {@code OPEN} property to the
     * target and {@code setBlock} it. Single cell — a gate has no two-half sync.
     *
     * <p><b>One guard, not two.</b> A non-gate {@code pos} (stale grid) is a no-op via the {@code
     * instanceof} test; there is <b>no iron refusal</b> — no iron/redstone-only fence gate exists at any
     * version (the vanilla roster is all-wood, all hand-openable), so the family's iron backstop has no gate
     * case. A gate already at the target state is a no-op too, so re-issuing the verb never spams block
     * updates.
     *
     * <p><b>{@code FACING} is written AS-READ</b> (DESIGN-fence-gates.md §1). Vanilla's use handler
     * re-faces a gate toward its opener on close→open, but that flip is always 180° ({@code FACING.axis} is
     * invariant for the block's lifetime) and open collision is {@code Shapes.empty()} regardless of facing
     * — the re-face is visual only, so a property-preserving write is behaviorally exact at every version.
     *
     * <p><b>Deliberately setBlock-only — the vanilla sound/game-event side effects are DROPPED</b>, the
     * same ratified drift ruling as {@link #setTrapdoorOpen} (the gate sound surface churned three times
     * across the range: levelEvent ids → {@code SoundEvents} fields → {@code WoodType}; no single surface
     * spans it, and the game-event holder type churns mid-range). Silent and sculk-invisible in v1 —
     * accepted; an overlay flavor can restore parity later. Flag {@code 3} (neighbour updates + client
     * sync, the ratified form) lets neighbours react.
     *
     * <p><b>Version stability of what IS used</b> (all javap-verified 1.17.1 → 26.2): {@code instanceof
     * FenceGateBlock}, {@code BlockStateProperties.OPEN}, {@code StateHolder.getValue/setValue}, and
     * {@code Level.setBlock(BlockPos, BlockState, int)}. Core baseline, no overlay flavor. ({@code actor}
     * is unused today — carried for seam symmetry with {@link #setDoorOpen}, the future sound/game-event
     * parity attribution.)
     */
    public static void setGateOpen(ServerLevel level, BlockPos pos, Entity actor, boolean open) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof FenceGateBlock) {
            if (state.getValue(BlockStateProperties.OPEN) == open) {
                return; // already at target — no redundant block update
            }
            level.setBlock(pos, state.setValue(BlockStateProperties.OPEN, open), 3);
        }
    }
}
