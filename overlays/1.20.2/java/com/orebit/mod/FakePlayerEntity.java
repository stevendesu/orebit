package com.orebit.mod;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Version overlay delta — introduced at MC <b>1.20.2</b>, overrides the 1.20.1 baseline.
 *
 * <p>1.20.2 added the {@link ClientInformation} parameter to the {@code ServerPlayer}
 * constructor (pre-1.20.2 used a 3-arg ctor — see {@code overlays/1.20.1}). We pass
 * {@link ClientInformation#createDefault()} — a server-side bot has no real client to
 * report view distance / chat options. {@code LivingEntity.kill()} is still no-arg in this
 * flavor.
 *
 * <p>The overlay eras compose (build.gradle.kts), so this file is the active
 * {@code FakePlayerEntity} for 1.20.2 through 1.21.1, then OVERRIDDEN by
 * {@code overlays/1.21.2} (which switches kill() to take a {@link ServerLevel}).
 */
public class FakePlayerEntity extends ServerPlayer {

    public FakePlayerEntity(MinecraftServer server, ServerLevel world, GameProfile profile) {
        super(server, world, profile, ClientInformation.createDefault());

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
