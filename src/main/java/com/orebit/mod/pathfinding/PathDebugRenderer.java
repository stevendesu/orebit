package com.orebit.mod.pathfinding;

import com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan;
import com.orebit.mod.pathfinding.regionpathfinder.RegionPathPlan;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
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
 * <p>Purely diagnostic — gated by a caller-side flag ({@code com.orebit.mod.Debug.ENABLED}); it has
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

    // ---- region/HPA skeleton overlay (HANDOFF #1) ----------------------------------------------------
    //
    // The block path above is the LOCAL route (red dust); this draws the MACRO plan it refines: the region
    // skeleton sequence + the per-step PORTAL CELLS the sliding window aims at. Distinct VANILLA particle
    // TYPES (not custom dust colours) keep it version-portable — DustParticleOptions' colour ctor is the
    // version-fragile surface, SimpleParticleType is stable 1.17→26.2. Colour legend:
    //   • region centre column — green (committed) / orange (in window) / white (planned-ahead)
    //   • macro connector center→center — faint white, shows the region ORDER
    //   • portal cell — BLUE soul-flame; this is the reachable target each step is entered through. A portal
    //     drawn INSIDE rock is the §6 "buried target" bug made visible. The current window target (the far
    //     window step's portal — literally what windowTarget() returns) gets a TALL blue column to stand out.

    /** Spacing for the sparse macro connector between region centres (coarser than the block line). */
    private static final double MACRO_SPACING = 0.5;
    /** Height of a region-centre state column (blocks). */
    private static final double CENTER_COL_HEIGHT = 3.0;
    /** Height of a portal-cell marker column (blocks); the active window target uses the taller value. */
    private static final double PORTAL_COL_HEIGHT = 2.0;
    private static final double PORTAL_TARGET_COL_HEIGHT = 5.0;

    /**
     * Draw {@code plan}'s coarse region skeleton: a state-coloured column per skeleton region centre, faint
     * connectors in travel order, and a blue column at each step's portal cell (tall at the current window
     * target). No-op when the plan produced no skeleton (center-model plans still draw — their steps simply
     * carry no portal, so only the centre columns + connectors appear).
     */
    public static void renderSkeleton(ServerLevel level, PathPlan plan) {
        if (plan == null) return;
        RegionPathPlan sk = plan.skeletonPlan();
        if (sk == null || sk.isEmpty()) return;

        final int committed = plan.committedStepIndex();
        final int windowStart = plan.windowStartIndex();
        final int windowLast = plan.windowLastIndex();

        double prevX = 0, prevY = 0, prevZ = 0;
        boolean havePrev = false;
        for (int i = 0; i < sk.size(); i++) {
            BlockPos c = sk.centerOf(i);
            double cx = c.getX() + 0.5, cy = c.getY() + 0.5, cz = c.getZ() + 0.5;

            // Macro connector (region order), faint white.
            if (havePrev) {
                typedLine(level, ParticleTypes.END_ROD, prevX, prevY, prevZ, cx, cy, cz, MACRO_SPACING);
            }
            prevX = cx; prevY = cy; prevZ = cz; havePrev = true;

            // Region-centre state column: committed=green, in-window=orange, ahead=white.
            SimpleParticleType centerType = (i <= committed) ? ParticleTypes.HAPPY_VILLAGER
                    : (i >= windowStart && i <= windowLast) ? ParticleTypes.FLAME
                    : ParticleTypes.END_ROD;
            column(level, centerType, cx, cy, cz, CENTER_COL_HEIGHT);

            // Portal cell — the reachable boundary cell this step is entered through (the buried-target tell).
            if (sk.hasPortal(i)) {
                BlockPos p = sk.portalCell(i);
                double px = p.getX() + 0.5, py = p.getY() + 0.5, pz = p.getZ() + 0.5;
                double h = (i == windowLast) ? PORTAL_TARGET_COL_HEIGHT : PORTAL_COL_HEIGHT;
                column(level, ParticleTypes.SOUL_FIRE_FLAME, px, py, pz, h);
            }
        }
    }

    /** A vertical column of {@code type} particles rising {@code height} blocks from {@code (x,y,z)}. */
    private static void column(ServerLevel level, SimpleParticleType type,
                               double x, double y, double z, double height) {
        for (double h = 0.0; h <= height; h += DOT_SPACING) {
            level.sendParticles(type, x, y + h, z, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }

    /** A line of {@code type} particles from {@code (x0..)} to {@code (x1..)} at {@code spacing} blocks. */
    private static void typedLine(ServerLevel level, SimpleParticleType type, double x0, double y0, double z0,
                                  double x1, double y1, double z1, double spacing) {
        double dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int n = Math.max(1, (int) Math.ceil(dist / spacing));
        for (int s = 1; s <= n; s++) {
            double t = s / (double) n;
            level.sendParticles(type, x0 + dx * t, y0 + dy * t, z0 + dz * t, 1, 0.0, 0.0, 0.0, 0.0);
        }
    }
}
