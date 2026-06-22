package com.orebit.mod;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Version overlay delta — introduced at MC <b>1.21.2</b>, the newest flavor of this file.
 *
 * <p>This file folds in BOTH walk-back-pinned divergences that touch {@code FakePlayerEntity}:
 * <ul>
 *   <li><b>1.20.2</b> added the {@link ClientInformation} parameter to the
 *       {@code ServerPlayer} constructor. We hand it a default
 *       {@link ClientInformation#createDefault()} — a server-side bot has no real
 *       client to report view distance / chat options.</li>
 *   <li><b>1.21.2</b> changed {@code LivingEntity.kill()} to require a
 *       {@link ServerLevel} (see {@link #removeFromWorld()}).</li>
 * </ul>
 * (Overlay override is whole-file, so this 1.21.2 flavor must restate the 1.20.2 ctor
 * change too.) The overlay eras compose (build.gradle.kts); this file overrides
 * {@code overlays/1.20.2} (ClientInformation ctor + no-arg kill, active 1.20.2–1.21.1)
 * for 1.21.2 and up. The pre-1.20.2 baseline is {@code overlays/1.20.1}.
 */
public class FakePlayerEntity extends ServerPlayer {

    public FakePlayerEntity(MinecraftServer server, ServerLevel world, GameProfile profile) {
        super(server, world, profile, ClientInformation.createDefault());

        this.setNoGravity(false);
    }

    @Override
    public void tick() {
        super.baseTick();
        this.setNoGravity(false);
        this.aiStep();
    }

    /**
     * Removes this bot from the world. As of <b>1.21.2</b> {@code kill()} requires the
     * {@link ServerLevel} (walk-back-pinned boundary). Eras ≤ 1.20.5 use the no-arg
     * {@code kill()} in {@code overlays/1.20.x}.
     */
    public void removeFromWorld() {
        if (this.isAlive()) {
            this.kill((ServerLevel) this.level());
            this.discard();
        }
    }

}
