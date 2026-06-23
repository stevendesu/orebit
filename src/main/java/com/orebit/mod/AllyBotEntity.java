package com.orebit.mod;

import com.mojang.authlib.GameProfile;
import com.orebit.mod.pathfinding.PathDebugRenderer;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.platform.EntityState;
import com.orebit.mod.platform.Worlds;
import com.orebit.mod.worldmodel.pathing.ChunkNavBuilder;
import com.orebit.mod.worldmodel.pathing.NavGridView;
import com.orebit.mod.worldmodel.pathing.NavStore;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.phys.Vec3;

/**
 * The ally bot: a faked {@link net.minecraft.server.level.ServerPlayer} that follows its owner.
 *
 * <p><b>This is the first consumer of the nav grid (PRD Phase 4 / pathfinding milestone).</b> When
 * the owner is far enough away, the bot plans a block-level path to them with {@link BlockPathfinder}
 * over a {@link NavGridView} and steers along the waypoints, jumping over single-block rises — so it
 * routes around obstacles instead of walking straight into them. Where nav data isn't built yet (the
 * owner just outside the bot's loaded radius), it falls back to the old straight-line steer so it
 * never freezes.
 */
public class AllyBotEntity extends FakePlayerEntity {

    /**
     * Debug toggle: draw the bot's planned path as particles (END_ROD line, FLAME = current target)
     * and log each plan (size, first/last waypoint, goal). Non-final so it can be flipped at runtime
     * (e.g. via a debugger or future command); default on while the pathfinder is being validated.
     */
    public static boolean DEBUG_PATH = true;

    private final Player owner;

    // ---- Follow / path-steering tuning -------------------------------------------------------
    /** Stop moving once this close to the owner (blocks, horizontal). */
    private static final double ARRIVE_DIST = 2.5;
    /** Replan to the owner's current cell at most this often (ticks). */
    private static final int REPLAN_TICKS = 10;
    /** Advance to the next waypoint once within this distance (blocks, horizontal). */
    private static final double WAYPOINT_REACH = 0.7;
    /**
     * Don't advance off a waypoint until the bot has climbed to (within this of, or above) its level.
     * Without this, the horizontal-only reach would advance the target to the <i>next</i> step while
     * the bot is still on the current one — so on a stacked staircase it would skip the middle step
     * and try to jump two blocks at once (the pit-escape bug). One-sided so a descent (bot above the
     * next waypoint) still advances and drops.
     */
    private static final double WAYPOINT_VERTICAL_REACH = 0.5;
    /** Below this squared horizontal speed while trying to move, treat the bot as stuck → jump. */
    private static final double STUCK_SPEED_SQR = 0.0016;

    /** Pad the rebuilt area by one chunk on each side so a path that bulges around an obstacle has data. */
    private static final int REFRESH_PAD_CHUNKS = 1;
    /** Cap the rebuilt span per axis (chunks) so a far-away owner can't spike the rebuild cost. */
    private static final int REFRESH_MAX_SPAN_CHUNKS = 2;

    private BlockPathPlan path;
    private int waypointIndex;
    private int replanCooldown;
    private boolean loggedHasPath; // dedupe the path/no-path diagnostic so it logs only on change

    public AllyBotEntity(MinecraftServer server, ServerLevel world, GameProfile profile, Player owner) {
        super(server, world, profile);
        this.owner = owner;
    }

    public void lookAtPlayer(Player player) {
        double dx = player.getX() - this.getX();
        double dy = (player.getEyeY()) - this.getEyeY();
        double dz = player.getZ() - this.getZ();

        double distXZ = Math.sqrt(dx * dx + dz * dz);

        float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        float pitch = (float) (Math.toDegrees(-Math.atan2(dy, distXZ)));

        this.setYHeadRot(yaw);    // where the head turns
        this.setYRot(yaw);        // body rotation
        this.setYBodyRot(yaw);    // optional for full facing
        this.setXRot(pitch);      // up/down looking
    }

    @Override
    public void tick() {
        // Drive the player tick directly: baseTick() + a single aiStep() with our inputs set first.
        // (The previous super.tick() already ran aiStep(), so steering followed by a second aiStep()
        // double-stepped physics each tick — see HANDOFF. baseTick/aiStep are stable across versions.)
        this.baseTick();
        this.setNoGravity(false);

        if (owner == this) {
            this.aiStep();
            return;
        }

        double dx = owner.getX() - this.getX();
        double dz = owner.getZ() - this.getZ();
        double distXZ = Math.sqrt(dx * dx + dz * dz);

        this.xxa = 0.0f;
        this.yya = this.isInWater() ? 1.0f : 0.0f;

        if (distXZ <= ARRIVE_DIST) {
            // Arrived: stop and face the owner.
            this.zza = 0.0f;
            this.path = null;
            lookAtPlayer(owner);
            this.aiStep();
            return;
        }

        if (--replanCooldown <= 0 || path == null || waypointIndex >= path.size()) {
            replan();
            replanCooldown = REPLAN_TICKS;
        }

        if (path != null && waypointIndex < path.size()) {
            steerAlongPath();
        } else {
            steerStraight(dx, dz); // no nav data here — fall back to straight-line follow
        }

        if (DEBUG_PATH && path != null) {
            PathDebugRenderer.render((ServerLevel) Worlds.of(this), path, waypointIndex,
                    this.getX(), this.getY(), this.getZ());
        }

        this.aiStep();
    }

    /** Plan a fresh block path from the bot's floor cell to the owner's floor cell. */
    private void replan() {
        ServerLevel level = (ServerLevel) Worlds.of(this);
        BlockPos startFloor = this.blockPosition().below();
        BlockPos goalFloor = owner.blockPosition().below();

        refreshNavData(level, startFloor, goalFloor);

        NavGridView grid = new NavGridView(level);
        this.path = BlockPathfinder.findPath(grid, startFloor, goalFloor);
        this.waypointIndex = 0;

        boolean hasPath = path != null && path.size() > 0;
        if (DEBUG_PATH) {
            // Verbose per-plan trace while debugging: first waypoint reveals which way A* actually heads.
            if (hasPath) {
                OrebitCommon.LOGGER.info("[Orebit] plan: {} wp cost={} start={} first={} last={} goal={}",
                        path.size(), path.cost(), startFloor, path.waypoint(0),
                        path.waypoint(path.size() - 1), goalFloor);
            } else {
                OrebitCommon.LOGGER.info("[Orebit] plan: NONE start={}({}) goal={}({})",
                        startFloor, grid.classAt(startFloor.getX(), startFloor.getY(), startFloor.getZ()),
                        goalFloor, grid.classAt(goalFloor.getX(), goalFloor.getY(), goalFloor.getZ()));
            }
        } else if (hasPath != loggedHasPath) {
            loggedHasPath = hasPath;
            if (hasPath) {
                OrebitCommon.LOGGER.info("[Orebit] bot path: {} waypoints (cost {})", path.size(), path.cost());
            } else {
                OrebitCommon.LOGGER.info("[Orebit] bot path: none (startClass={}, goalClass={})",
                        grid.classAt(startFloor.getX(), startFloor.getY(), startFloor.getZ()),
                        grid.classAt(goalFloor.getX(), goalFloor.getY(), goalFloor.getZ()));
            }
        }
    }

    /**
     * Demo-time freshness shim. The nav grid is otherwise built only on chunk <i>load</i> (PRD §6.2),
     * so runtime block edits — a player digging a pit or placing a staircase — are not reflected and
     * the planner would route over stale terrain. Until a proper block-update invalidation hook
     * exists (loader-divergent — see HANDOFF), rebuild the handful of chunks this path spans straight
     * from the live world each replan, so the demo always plans over current blocks. (This also
     * self-heals if the background chunk-load pipeline isn't running on a given era.)
     */
    private void refreshNavData(ServerLevel level, BlockPos a, BlockPos b) {
        int cx0 = (Math.min(a.getX(), b.getX()) >> 4) - REFRESH_PAD_CHUNKS;
        int cx1 = (Math.max(a.getX(), b.getX()) >> 4) + REFRESH_PAD_CHUNKS;
        int cz0 = (Math.min(a.getZ(), b.getZ()) >> 4) - REFRESH_PAD_CHUNKS;
        int cz1 = (Math.max(a.getZ(), b.getZ()) >> 4) + REFRESH_PAD_CHUNKS;
        if (cx1 - cx0 > REFRESH_MAX_SPAN_CHUNKS) cx1 = cx0 + REFRESH_MAX_SPAN_CHUNKS;
        if (cz1 - cz0 > REFRESH_MAX_SPAN_CHUNKS) cz1 = cz0 + REFRESH_MAX_SPAN_CHUNKS;
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                ChunkAccess chunk = level.getChunk(cx, cz);
                NavStore.put(level, NavStore.key(cx, cz), ChunkNavBuilder.buildAllSections(level, chunk));
            }
        }
    }

    /** Steer toward the current waypoint, advancing as we reach each and jumping over rises. */
    private void steerAlongPath() {
        BlockPos wp = path.waypoint(waypointIndex);
        double tx = wp.getX() + 0.5;
        double tz = wp.getZ() + 0.5;
        double ddx = tx - this.getX();
        double ddz = tz - this.getZ();

        if (reachedWaypoint(wp, ddx, ddz)) {
            if (++waypointIndex >= path.size()) {
                this.zza = 0.0f;
                return;
            }
            wp = path.waypoint(waypointIndex);
            tx = wp.getX() + 0.5;
            tz = wp.getZ() + 0.5;
            ddx = tx - this.getX();
            ddz = tz - this.getZ();
        }

        float yaw = (float) (Math.toDegrees(Math.atan2(-ddx, ddz)));
        this.setYRot(yaw);
        this.setYBodyRot(yaw);
        this.setYHeadRot(yaw);
        this.zza = 1.0f;

        boolean stepUp = wp.getY() > this.getY() + 0.4;
        Vec3 velocity = this.getDeltaMovement();
        boolean stuck = velocity.horizontalDistanceSqr() < STUCK_SPEED_SQR;
        if ((stepUp || stuck) && EntityState.onGround(this)) {
            this.jumpFromGround();
        }
    }

    /** A waypoint is reached only when the bot is close horizontally <b>and</b> has climbed to its level. */
    private boolean reachedWaypoint(BlockPos wp, double ddx, double ddz) {
        return Math.sqrt(ddx * ddx + ddz * ddz) < WAYPOINT_REACH
                && this.getY() >= wp.getY() - WAYPOINT_VERTICAL_REACH;
    }

    /** Original straight-at-the-owner steer, used when nav data for the route isn't built yet. */
    private void steerStraight(double dx, double dz) {
        float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        this.setYRot(yaw);
        this.setYBodyRot(yaw);
        this.setYHeadRot(yaw);
        this.zza = 1.0f;

        Vec3 velocity = this.getDeltaMovement();
        boolean stuck = velocity.horizontalDistanceSqr() < STUCK_SPEED_SQR;
        if (stuck && EntityState.onGround(this)) {
            this.jumpFromGround();
        }
    }
}
