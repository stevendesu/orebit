package com.orebit.mod.neoforge;

import com.orebit.mod.OrebitCommon;

import net.neoforged.fml.common.Mod;

@Mod(OrebitCommon.MOD_ID)
public final class OrebitNeoForge {
    public OrebitNeoForge() {
        OrebitCommon.init(new NeoForgePlatformEvents());
    }
}
