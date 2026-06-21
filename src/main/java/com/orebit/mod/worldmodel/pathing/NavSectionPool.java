package com.orebit.mod.worldmodel.pathing;

import net.minecraft.core.BlockPos;

import java.util.ArrayDeque;

public final class NavSectionPool {
    private static final int POOL_SIZE = 256; // tweak based on performance testing

    private static final ArrayDeque<NavSection> POOL = new ArrayDeque<>(POOL_SIZE);

    private NavSectionPool() {}

    public static NavSection get(BlockPos origin) {
        NavSection section = POOL.isEmpty() ? new NavSection() : POOL.pop();
        section.reset(origin);
        return section;
    }

    public static void recycle(NavSection section) {
        POOL.push(section);
    }
}
