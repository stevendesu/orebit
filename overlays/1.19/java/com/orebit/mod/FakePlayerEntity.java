package com.orebit.mod;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Version overlay: MC 1.19–1.19.2 (chat-signing era, pre-1.19.3).
 *
 * <p>1.19 added chat signing, so {@code ServerPlayer} took a 4th
 * {@code @Nullable ProfilePublicKey} argument; <b>1.19.3</b> moved the chat session out of the
 * constructor and went back to 3 args. This overlay supplies the 4-arg flavor (a bot has no
 * chat key → {@code null}) for 1.19/1.19.1/1.19.2. It overrides the baseline 3-arg
 * flavor ({@code overlays/1.17}) and is itself overridden at {@code overlays/1.19.3} (back to
 * 3 args) and {@code overlays/1.20.2} (ClientInformation).
 */
public class FakePlayerEntity extends ServerPlayer {

    public FakePlayerEntity(MinecraftServer server, ServerLevel world, GameProfile profile) {
        super(server, world, profile, null);

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
