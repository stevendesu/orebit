package com.orebit.mod;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Version overlay: MC 1.19.4–1.20.1 (post chat-signing-ctor cleanup, pre-ClientInformation).
 *
 * <p><b>1.19.4</b> moved the chat session out of the {@code ServerPlayer} constructor, dropping
 * the {@code ProfilePublicKey} arg that 1.19–1.19.3 required ({@code overlays/1.19}) — so the
 * ctor is 3-arg again here, matching the {@code overlays/1.17} baseline. (We can't simply let
 * the baseline serve 1.19.4+, because the {@code overlays/1.19} override sits between them and
 * would otherwise win.) Overridden at {@code overlays/1.20.2} (ClientInformation 4-arg).
 */
public class FakePlayerEntity extends ServerPlayer {

    public FakePlayerEntity(MinecraftServer server, ServerLevel world, GameProfile profile) {
        super(server, world, profile);

        this.setNoGravity(false);
    }

    @Override
    public void tick() {
        super.baseTick();
        this.setNoGravity(false);
        this.aiStep();
    }

    /** Removes this bot from the world. Pre-1.21.2 {@code kill()} takes no arguments. */
    public void removeFromWorld() {
        if (this.isAlive()) {
            this.kill();
            this.discard();
        }
    }

}
