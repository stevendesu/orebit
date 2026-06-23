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
    /**
     * Safety re-plan interval (ticks). We normally replan only when the owner steps to a new block
     * (see {@link #lastGoalCell}); this is the backstop so a static owner still picks up terrain edits
     * (the demo refresh) within a second or two. Kept long so a stationary bot replans rarely — which
     * also keeps the debug path stable so its particles don't ghost.
     */
    private static final int REPLAN_TICKS = 40;
    /** Below this squared horizontal speed while trying to move, treat the bot as stuck → jump. */
    private static final double STUCK_SPEED_SQR = 0.0016;
    /** Consecutive stuck ticks before the diagnostic dumps the surrounding blocks (≈1s). */
    private static final int STUCK_DUMP_TICKS = 20;

    /** Pad the rebuilt area by one chunk on each side so a path that bulges around an obstacle has data. */
    private static final int REFRESH_PAD_CHUNKS = 1;
    /** Cap the rebuilt span per axis (chunks) so a far-away owner can't spike the rebuild cost. */
    private static final int REFRESH_MAX_SPAN_CHUNKS = 2;

    private BlockPathPlan path;
    private int waypointIndex;
    private int replanCooldown;
    private int stuckTicks;         // consecutive ticks grinding in place; drives the stuck diagnostic
    private BlockPos lastGoalCell;  // owner floor cell the current path was planned to; replan when it moves
    private boolean loggedHasPath;  // dedupe the path/no-path diagnostic so it logs only on change

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

        // Replan when the goal moves to a new block, the path is spent, or the safety interval elapses.
        // Holding a stable path while the owner stands still stops the debug particles from ghosting.
        BlockPos goalFloor = owner.blockPosition().below();
        if (path == null || waypointIndex >= path.size()
                || !goalFloor.equals(lastGoalCell) || --replanCooldown <= 0) {
            replan(goalFloor);
            lastGoalCell = goalFloor;
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
    private void replan(BlockPos goalFloor) {
        ServerLevel level = (ServerLevel) Worlds.of(this);
        BlockPos startFloor = this.blockPosition().below();

        refreshNavData(level, startFloor, goalFloor);

        NavGridView grid = new NavGridView(level);
        this.path = BlockPathfinder.findPath(grid, startFloor, goalFloor);
        this.waypointIndex = 0;

        boolean hasPath = path != null && path.size() > 0;
        if (DEBUG_PATH) {
            // Verbose per-plan trace while debugging: the full waypoint list shows exactly which cells
            // A* produced (e.g. whether a stacked staircase step is present or skipped).
            if (hasPath) {
                OrebitCommon.LOGGER.info("[Orebit] plan: {} wp cost={} start={} goal={} path={}",
                        path.size(), path.cost(), compact(startFloor), compact(goalFloor), waypointsString());
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

    /** Steer toward the current waypoint, advancing by block occupancy and jumping over rises. */
    private void steerAlongPath() {
        // Advance to the furthest waypoint whose block the bot's feet currently occupy. Waypoints ARE
        // blocks and so are the bot's feet ({@link #blockPosition()}), so this is block-exact — no
        // distance epsilon. Because the comparison includes Y, the feet block can only equal the next
        // step once the bot has actually climbed onto it, so a stacked staircase can't be skipped;
        // scanning from the end also absorbs any overshoot.
        BlockPos foot = this.blockPosition();
        for (int j = path.size() - 1; j >= waypointIndex; j--) {
            if (foot.equals(path.waypoint(j))) {
                waypointIndex = j + 1;
                break;
            }
        }
        if (waypointIndex >= path.size()) {
            this.zza = 0.0f;
            return;
        }

        BlockPos wp = path.waypoint(waypointIndex);
        double ddx = (wp.getX() + 0.5) - this.getX();
        double ddz = (wp.getZ() + 0.5) - this.getZ();

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

        // Stuck-on-a-step diagnostic: when the bot grinds in place against the current target for a
        // while, dump the ACTUAL block solidity (live world, not the coarse grid) for the bot's column
        // and the target's column — this reveals jump head-clearance, which the floor-centric 2-bit
        // grid can't represent (a CLEAR cell guarantees only 2 air blocks above the floor; a jump-up
        // needs ~3). Logged once per stuck episode.
        if (stuck && EntityState.onGround(this)) {
            if (++stuckTicks == STUCK_DUMP_TICKS && DEBUG_PATH) dumpStuck(wp);
        } else {
            stuckTicks = 0;
        }
    }

    // ---- Debug log formatting ----------------------------------------------------------------

    private void dumpStuck(BlockPos wp) {
        ServerLevel level = (ServerLevel) Worlds.of(this);
        BlockPos foot = this.blockPosition();
        OrebitCommon.LOGGER.info("[Orebit] STUCK pos=({},{},{}) foot={} target={} | "
                        + "botCol[floor,feet,head,+1,+2]={} targetCol[floor,feet,head,+1,+2]={} (S=solid .=air)",
                String.format("%.2f", getX()), String.format("%.2f", getY()), String.format("%.2f", getZ()),
                compact(foot), compact(wp), column(level, foot), column(level, wp));
    }

    /** Solidity of 5 cells from the floor (one below the feet block) up through head+2, as S/. */
    private static String column(ServerLevel level, BlockPos feetBlock) {
        StringBuilder sb = new StringBuilder();
        for (int dy = -1; dy <= 3; dy++) {
            sb.append(level.getBlockState(feetBlock.above(dy)).isAir() ? '.' : 'S');
        }
        return sb.toString();
    }

    private static String compact(BlockPos p) {
        return "(" + p.getX() + "," + p.getY() + "," + p.getZ() + ")";
    }

    private String waypointsString() {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < path.size(); i++) {
            if (i > 0) sb.append(' ');
            sb.append(compact(path.waypoint(i)));
        }
        return sb.append(']').toString();
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
