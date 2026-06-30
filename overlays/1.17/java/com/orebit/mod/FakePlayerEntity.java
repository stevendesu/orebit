package com.orebit.mod;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Version overlay: OLDEST baseline flavor (currently MC 1.17–1.18.2; the dir is named for the
 * lowest supported version — see the overlay re-baseline procedure).
 *
 * <p>{@code ServerPlayer} took a plain 3-arg constructor in this range: 1.17 moved the game-mode
 * creation inside {@code ServerPlayer} (≤1.16.5 needed a 4-arg {@code ServerPlayerGameMode}, the
 * future floor when support extends below 1.17), and the 4th arg only returns at 1.19
 * (ProfilePublicKey) before going away again at 1.19.3. The overlay eras compose
 * (build.gradle.kts): this is OVERRIDDEN by {@code overlays/1.19} (ProfilePublicKey 4-arg),
 * {@code overlays/1.19.3} (back to 3-arg), {@code overlays/1.20.2} (ClientInformation ctor, still
 * no-arg kill), and {@code overlays/1.21.2} (kill() takes a
 * {@link net.minecraft.server.level.ServerLevel}). Everything else about the fake player is
 * version-agnostic and stays in {@code src/main/java}.
 */
public class FakePlayerEntity extends ServerPlayer {

    public FakePlayerEntity(MinecraftServer server, ServerLevel world, GameProfile profile) {
        super(server, world, profile);

        this.setNoGravity(false);
    }

    @Override
    public void tick() {
        // Run the full vanilla ServerPlayer tick (i-frame countdown, container/advancement sync, attributes,
        // block-break progress, ...). A seam for shared fake-player tick logic; AllyBotEntity forges its inputs
        // around super.tick() + doTick(). The old baseTick()+aiStep() hand-roll predated the bot being a real
        // PlayerList member (placeNewPlayer) — running the full player tick is safe now.
        super.tick();
    }

    /** Removes this bot from the world. Pre-1.21.2 {@code kill()} takes no arguments. */
    public void removeFromWorld() {
        if (this.isAlive()) {
            this.kill();
            this.discard();
        }
    }

}
