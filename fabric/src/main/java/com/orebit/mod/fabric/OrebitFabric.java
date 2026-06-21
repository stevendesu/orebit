package com.orebit.mod.fabric;

import com.orebit.mod.OrebitCommon;

import net.fabricmc.api.ModInitializer;

public final class OrebitFabric implements ModInitializer {
    @Override
    public void onInitialize() {
        OrebitCommon.init(new FabricPlatformEvents());
    }
}
