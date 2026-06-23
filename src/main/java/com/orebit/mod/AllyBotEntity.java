package com.orebit.mod;

import com.mojang.authlib.GameProfile;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.platform.EntityState;
import com.orebit.mod.platform.Worlds;
import com.orebit.mod.worldmodel.pathing.NavGridView;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
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

    private final Player owner;

    // ---- Follow / path-steering tuning -------------------------------------------------------
    /** Stop moving once this close to the owner (blocks, horizontal). */
    private static final double ARRIVE_DIST = 2.5;
    /** Replan to the owner's current cell at most this often (ticks). */
    private static final int REPLAN_TICKS = 10;
    /** Advance to the next waypoint once within this distance (blocks, horizontal). */
    private static final double WAYPOINT_REACH = 0.7;
    /** Below this squared horizontal speed while trying to move, treat the bot as stuck → jump. */
    private static final double STUCK_SPEED_SQR = 0.0016;

    private BlockPathPlan path;
    private int waypointIndex;
    private int replanCooldown;

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

        this.aiStep();
    }

    /** Plan a fresh block path from the bot's floor cell to the owner's floor cell. */
    private void replan() {
        ServerLevel level = (ServerLevel) Worlds.of(this);
        NavGridView grid = new NavGridView(level);
        BlockPos startFloor = this.blockPosition().below();
        BlockPos goalFloor = owner.blockPosition().below();
        this.path = BlockPathfinder.findPath(grid, startFloor, goalFloor);
        this.waypointIndex = 0;
    }

    /** Steer toward the current waypoint, advancing as we reach each and jumping over rises. */
    private void steerAlongPath() {
        BlockPos wp = path.waypoint(waypointIndex);
        double tx = wp.getX() + 0.5;
        double tz = wp.getZ() + 0.5;
        double ddx = tx - this.getX();
        double ddz = tz - this.getZ();

        if (Math.sqrt(ddx * ddx + ddz * ddz) < WAYPOINT_REACH) {
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
