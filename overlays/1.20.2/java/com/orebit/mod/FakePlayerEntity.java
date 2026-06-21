package com.orebit.mod;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Version overlay: MC 1.20.2+ era (post login refactor).
 *
 * <p>1.20.2 added the {@link ClientInformation} parameter to the {@code ServerPlayer}
 * constructor. We hand it a default {@link ClientInformation#createDefault()} — a
 * server-side bot has no real client to report view distance / chat options — so the
 * caller never has to name the version-specific type. The pre-1.20.2 flavor lives in
 * {@code overlays/1.20.1/java}.
 */
public class FakePlayerEntity extends ServerPlayer {

    public FakePlayerEntity(MinecraftServer server, ServerLevel world, GameProfile profile) {
        super(server, world, profile, ClientInformation.createDefault());
        this.connection = new FakeNetworkHandler(server, this);

        this.setNoGravity(false);
    }

    @Override
    public void tick() {
        super.baseTick();
        this.setNoGravity(false);
        this.aiStep();
    }

    /**
     * Removes this bot from the world. As of 1.21 {@code kill()} requires the
     * {@link ServerLevel}. (The exact boundary — 1.21.0 — will get its own overlay era
     * when the version walk-back pins it; this era currently targets 1.21.4.)
     */
    public void removeFromWorld() {
        if (this.isAlive()) {
            this.kill((ServerLevel) this.level());
            this.discard();
        }
    }

}
