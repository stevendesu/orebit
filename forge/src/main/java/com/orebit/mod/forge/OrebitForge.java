package com.orebit.mod.forge;

import com.orebit.mod.OrebitCommon;

import net.minecraftforge.fml.common.Mod;

@Mod(OrebitCommon.MOD_ID)
public final class OrebitForge {
    public OrebitForge() {
        OrebitCommon.init(new ForgePlatformEvents());
    }
}
