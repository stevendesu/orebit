package com.orebit.mod;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Version overlay: MC 1.20.1 era (pre-1.20.2 login refactor).
 *
 * <p>{@code ServerPlayer} took a 3-arg constructor before <b>1.20.2</b> introduced
 * {@link net.minecraft.server.level.ClientInformation}. This is the baseline flavor; the
 * overlay eras compose (build.gradle.kts), so for 1.20.2+ this whole file is OVERRIDDEN by
 * {@code overlays/1.20.2} (ClientInformation ctor, still no-arg kill), and again at 1.21.2
 * by {@code overlays/1.21.2} (which switches kill() to take a
 * {@link net.minecraft.server.level.ServerLevel}). Everything else about the fake player
 * is version-agnostic and stays in {@code src/main/java}.
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
