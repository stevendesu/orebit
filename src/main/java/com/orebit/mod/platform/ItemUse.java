package com.orebit.mod.platform;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Version-STABLE "use the held item on a block" primitive (DESIGN-bot-abilities.md §4) — the
 * exact server-guarded path {@code ServerPlayerGameMode.useItemOn} runs:
 * {@code ItemStack#useOn(UseOnContext)}, which dispatches to the item's own use logic (hoe
 * tilling with its air-above gate + durability, {@code BlockItem}/seed placement with its
 * {@code canSurvive} checks, sounds, game events) — nothing re-implemented. Lives in {@code src}
 * (not overlays) because every touched signature is javap-pinned byte-stable 1.17.1 → 26.2:
 * {@code UseOnContext(Player, InteractionHand, BlockHitResult)}, the 4-arg
 * {@code BlockHitResult} ctor, and {@code ItemStack#useOn}.
 *
 * <p>Callers do NOT branch on the drifting {@code InteractionResult} — the ratified state-based
 * pattern is: equip the item, use it, then VERIFY by re-reading the block (did farmland appear?
 * did the crop get planted?). The result type churned at 1.21.2; the world state never lies.
 */
public final class ItemUse {

    private ItemUse() {}

    /**
     * Use the bot's main-hand item on the TOP face of {@code target} (the till/plant gesture —
     * a player right-clicking the top of a dirt or farmland block). The hit point is the centre
     * of the top face; {@code inside=false} as a real targeted click reports.
     */
    public static void useOnTop(ServerPlayer bot, BlockPos target) {
        final Vec3 hit = new Vec3(target.getX() + 0.5, target.getY() + 1.0, target.getZ() + 0.5);
        final BlockHitResult bhr = new BlockHitResult(hit, Direction.UP, target, false);
        bot.getItemInHand(InteractionHand.MAIN_HAND)
                .useOn(new UseOnContext(bot, InteractionHand.MAIN_HAND, bhr));
    }
}
