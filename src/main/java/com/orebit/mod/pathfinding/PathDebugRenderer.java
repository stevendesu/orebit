package com.orebit.mod.pathfinding;

import com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;

/**
 * Debug-only: draws a bot's planned {@link BlockPathPlan} as a thin static line of single-colour
 * particles — like the F3+G chunk-border grid — visible to vanilla clients (no client mod needed).
 *
 * <p>Uses {@link DustParticleOptions#REDSTONE} (small, solid red, effectively no drift or size
 * animation) rather than the drifting/flickering emissive particles, so the line reads as a steady
 * route. The waypoint the follower is currently steering to is marked with a short vertical tick of
 * the same colour (distinguished by shape, not a second flickery particle). Spawned via
 * {@link ServerLevel#sendParticles} on the server thread, so it is loader-agnostic.
 *
 * <p>Purely diagnostic — gated by a caller-side flag (e.g. {@code AllyBotEntity.DEBUG_PATH}); it has
 * no effect on planning or movement.
 */
public final class PathDebugRenderer {

    private PathDebugRenderer() {}

    /** Spacing between dots along the line (blocks) — small enough to read as a continuous line. */
    private static final double DOT_SPACING = 0.25;
    /** Height of the vertical tick marking the active waypoint (blocks). */
    private static final double TICK_HEIGHT = 1.0;

    /**
     * Draw {@code path} as a line starting at {@code (startX,startY,startZ)} (the bot's feet) and
     * running through every waypoint. {@code activeIndex} is the waypoint the follower is steering to.
     */
    public static void render(ServerLevel level, BlockPathPlan path, int activeIndex,
                              double startX, double startY, double startZ) {
        if (path == null || path.isEmpty()) return;

        double px = startX, py = startY, pz = startZ;
        for (int i = 0; i < path.size(); i++) {
            BlockPos wp = path.waypoint(i);
            double cx = wp.getX() + 0.5, cy = wp.getY() + 0.1, cz = wp.getZ() + 0.5;
            line(level, px, py, pz, cx, cy, cz);
            if (i == activeIndex) {
                for (double h = DOT_SPACING; h <= TICK_HEIGHT; h += DOT_SPACING) dot(level, cx, cy + h, cz);
            }
            px = cx; py = cy; pz = cz;
        }
    }

    private static void line(ServerLevel level, double x0, double y0, double z0,
                             double x1, double y1, double z1) {
        double dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int n = Math.max(1, (int) Math.ceil(dist / DOT_SPACING));
        for (int s = 1; s <= n; s++) {
            double t = s / (double) n;
            dot(level, x0 + dx * t, y0 + dy * t, z0 + dz * t);
        }
    }

    private static void dot(ServerLevel level, double x, double y, double z) {
        level.sendParticles(DustParticleOptions.REDSTONE, x, y, z, 1, 0.0, 0.0, 0.0, 0.0);
    }
}
