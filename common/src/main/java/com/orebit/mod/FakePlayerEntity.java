package com.orebit.mod;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

public class FakePlayerEntity extends ServerPlayer {

    public FakePlayerEntity(MinecraftServer server, ServerLevel world, GameProfile profile, ClientInformation options) {
        super(server, world, profile, options);
        this.connection = new FakeNetworkHandler(server, this);

        this.setNoGravity(false);
    }

    @Override
    public void tick() {
        super.baseTick();
        this.setNoGravity(false);
        this.aiStep();
    }

}
