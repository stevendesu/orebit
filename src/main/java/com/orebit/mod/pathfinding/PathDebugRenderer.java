package com.orebit.mod.pathfinding;

import com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.server.level.ServerLevel;

/**
 * Debug-only: draws a bot's planned {@link BlockPathPlan} as server-spawned particles, visible to
 * vanilla clients (no client mod needed). {@code END_ROD} traces the path line from the bot through
 * each waypoint; {@code FLAME} marks the waypoint the follower is currently steering to. All spawned
 * via {@link ServerLevel#sendParticles} on the server thread, so this is loader-agnostic.
 *
 * <p>Purely diagnostic — gated by a caller-side flag (e.g. {@code AllyBotEntity.DEBUG_PATH}); it has
 * no effect on planning or movement.
 */
public final class PathDebugRenderer {

    private PathDebugRenderer() {}

    /** Interpolated points drawn between consecutive waypoints (a denser line is easier to read). */
    private static final int LINE_SAMPLES = 4;

    /**
     * Draw {@code path} as particles, starting the line at {@code (startX,startY,startZ)} (the bot's
     * feet). {@code activeIndex} is the waypoint the follower is steering to (highlighted FLAME).
     */
    public static void render(ServerLevel level, BlockPathPlan path, int activeIndex,
                              double startX, double startY, double startZ) {
        if (path == null || path.isEmpty()) return;

        double px = startX, py = startY, pz = startZ;
        for (int i = 0; i < path.size(); i++) {
            BlockPos wp = path.waypoint(i);
            double cx = wp.getX() + 0.5, cy = wp.getY() + 0.1, cz = wp.getZ() + 0.5;
            for (int s = 1; s <= LINE_SAMPLES; s++) {
                double t = s / (double) LINE_SAMPLES;
                spawn(level, ParticleTypes.END_ROD, px + (cx - px) * t, py + (cy - py) * t, pz + (cz - pz) * t);
            }
            spawn(level, i == activeIndex ? ParticleTypes.FLAME : ParticleTypes.END_ROD, cx, cy + 0.2, cz);
            px = cx; py = cy; pz = cz;
        }
    }

    private static void spawn(ServerLevel level, SimpleParticleType type, double x, double y, double z) {
        level.sendParticles(type, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
    }
}
