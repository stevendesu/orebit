package com.orebit.mod.platform;

import com.orebit.mod.FakeClientConnection;
import com.orebit.mod.OrebitCommon;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.storage.TagValueInput;

import java.util.Optional;

/**
 * Version-selected bot spawn (MC 1.21.9+, incl. 26.x): {@code placeNewPlayer} STOPPED loading the
 * player's saved .dat in 1.21.9 (javap-verified: the 1.21.8 method body calls
 * {@code load(ServerPlayer, ProblemReporter)}; the 1.21.9/1.21.10/1.21.11/26.2 bodies contain no
 * load call at all). Vanilla moved the load into the configuration-phase login flow —
 * {@code PrepareSpawnTask$Ready.spawn}: {@code playerList.loadPlayerData(nameAndId)} →
 * {@code player.load(TagValueInput.create(reporter, registryAccess, tag))} → {@code snapTo} →
 * {@code placeNewPlayer} — which a fake player never runs. Without replaying it here the bot
 * spawns FRESH and its first save overwrites the good data (the lost-inventory-on-relaunch bug).
 *
 * <p>This flavor replays exactly that vanilla sequence: load the saved tag (if any), apply it via
 * {@code Entity.load(ValueInput)}, then placeNewPlayer. All signatures are identical across
 * 1.21.9 → 26.2 (javap-verified on the mojmap/unobf jars), so one flavor covers both eras.
 * Vanilla's post-place {@code loadAndSpawnEnderPearls}/{@code loadAndSpawnParentVehicle} are
 * deliberately skipped — the bot is re-snapped beside its owner right after place, so respawning
 * a logout-time vehicle or in-flight pearls would only strand entities at the stale position.
 *
 * <p>The applied tag restores the bot's logout position/rotation along with its inventory;
 * {@link com.orebit.mod.BotManager} re-snaps the bot beside its owner after place (same handling
 * as the ≤1.21.8 in-placeNewPlayer load). Earlier eras: {@code overlays/1.20.5} (load inside
 * placeNewPlayer; 2-arg createInitial), {@code overlays/1.20.2}, {@code overlays/1.17}.
 */
public final class BotSpawn {
    private BotSpawn() {}

    public static void place(MinecraftServer server, ServerPlayer bot) {
        Optional<CompoundTag> tag = server.getPlayerList().loadPlayerData(bot.nameAndId());
        if (tag.isPresent()) {
            try (ProblemReporter.ScopedCollector reporter =
                     new ProblemReporter.ScopedCollector(bot.problemPath(), OrebitCommon.LOGGER)) {
                bot.load(TagValueInput.create(reporter, server.registryAccess(), tag.get()));
            }
        }
        server.getPlayerList().placeNewPlayer(
            new FakeClientConnection(),
            bot,
            CommonListenerCookie.createInitial(bot.getGameProfile(), false)
        );
    }
}
