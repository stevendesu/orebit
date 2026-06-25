package com.orebit.mod;

import com.mojang.authlib.GameProfile;
import com.orebit.mod.pathfinding.PathDebugRenderer;
import com.orebit.mod.pathfinding.PathPlan;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.MovementRegistry;
import com.orebit.mod.pathfinding.blockpathfinder.StepEdits;
import com.orebit.mod.platform.EntityState;
import com.orebit.mod.platform.Replaceable;
import com.orebit.mod.platform.WorldEdits;
import com.orebit.mod.platform.Worlds;
import com.orebit.mod.worldmodel.hpa.RegionGrid;
import com.orebit.mod.worldmodel.pathing.NavGridView;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
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
     * Vertical arrival tolerance (blocks). Paired with {@link #ARRIVE_DIST} so "arrived" means close in
     * 3D — without it the bot treats being directly under a target as arrival (matches the block A*'s
     * ±2 vertical goal tolerance, so the follower agrees with the planner about reaching the goal cell).
     */
    private static final double ARRIVE_Y = 2.5;
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

    /**
     * Bot capabilities the planner expands with. Break + place are enabled to prove the new modifiers
     * (mine through leaves, bridge a gap) — the motivating forest-leaves case. Once the config / tool /
     * inventory subsystems land this becomes per-bot, server-configurable.
     */
    private static final BotCaps CAPS = BotCaps.BREAK_PLACE;
    /** The throwaway block the bot places when bridging/footing (infinite supply until inventory exists). */
    private static final BlockState PLACE_BLOCK = Blocks.COBBLESTONE.defaultBlockState();

    /**
     * What the bot is currently trying to do, set by the {@code /bot} commands (defaults to
     * {@link Mode#FOLLOW} so a freshly spawned bot behaves as before — auto-follow the owner):
     * <ul>
     *   <li>{@link Mode#FOLLOW} — continuously path to the owner (the original behaviour).
     *   <li>{@link Mode#STAY} — hold position; don't path anywhere.
     *   <li>{@link Mode#COME} — path once to a fixed summon cell, then drop to {@link Mode#STAY}.
     * </ul>
     */
    public enum Mode { FOLLOW, STAY, COME }

    private Mode mode = Mode.FOLLOW;
    private BlockPos comeTarget;    // fixed summon cell (owner's feet block at /bot come time)

    /**
     * The two-tier driver (HPA-IMPLEMENTATION.md §9/§10): owns the coarse region skeleton and feeds the
     * follower one windowed {@link BlockPathPlan} at a time via {@link PathPlan#currentBlockPlan()}. Built
     * fresh per goal in {@link #replan}; ticked by {@link #driveToward} (which calls
     * {@link PathPlan#onBotMoved}). The existing {@link #steerAlongPath}/{@link #applyEdits} machinery is
     * unchanged — it walks whatever {@link #path} the driver currently exposes.
     */
    private PathPlan pathPlan;
    /**
     * Identity of the {@link BlockPathPlan} {@link #path} currently points at. When the driver advances its
     * sliding window it swaps in a NEW {@link BlockPathPlan} instance; we detect that by reference identity
     * and reset {@link #waypointIndex}/{@link #lastEditedIndex} so the follower restarts at the head of the
     * new window's path (HPA-IMPLEMENTATION.md §10).
     */
    private BlockPathPlan lastBlockPlanRef;

    private BlockPathPlan path;
    private int waypointIndex;
    private int replanCooldown;
    private int stuckTicks;         // consecutive ticks grinding in place; drives the stuck diagnostic
    private int lastEditedIndex = -1; // last step whose break/place edits were applied (apply once per step)
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

    // ---- Command-driven mode control (the /bot commands call these) --------------------------

    /** The bot's current behaviour mode. */
    public Mode mode() {
        return mode;
    }

    /** Switch behaviour mode (e.g. {@code /bot follow}, {@code /bot stay}); clears any active path. */
    public void setMode(Mode mode) {
        this.mode = mode;
        this.comeTarget = null;
        clearPlan();
    }

    /** {@code /bot come}: path once to {@code summonCell} (the caller's feet block), then hold there. */
    public void comeTo(BlockPos summonCell) {
        this.mode = Mode.COME;
        this.comeTarget = summonCell.immutable();
        clearPlan();
    }

    /**
     * Drop the active two-tier driver and its exposed block plan (HPA-IMPLEMENTATION.md §10): a mode change
     * or a STAY hold invalidates the current goal, so the {@link PathPlan} built for it must not be ticked
     * again. The next {@link #driveToward} sees a null {@link #pathPlan} and rebuilds for the new goal.
     */
    private void clearPlan() {
        this.pathPlan = null;
        this.path = null;
        this.lastBlockPlanRef = null;
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

        this.xxa = 0.0f;
        this.yya = this.isInWater() ? 1.0f : 0.0f;

        switch (mode) {
            case STAY -> holdPosition();
            case COME -> {
                // Summon to a fixed cell; once there, settle into STAY (distinct from FOLLOW, which
                // would keep chasing). comeTarget can't be null in COME, but guard defensively.
                if (comeTarget == null) { setMode(Mode.STAY); holdPosition(); break; }
                double tx = comeTarget.getX() + 0.5, ty = comeTarget.getY(), tz = comeTarget.getZ() + 0.5;
                if (driveToward(tx, ty, tz, comeTarget.below())) setMode(Mode.STAY); // arrived
            }
            default -> // FOLLOW
                driveToward(owner.getX(), owner.getY(), owner.getZ(), owner.blockPosition().below());
        }

        this.aiStep();
    }

    /** STAY: stop in place and face the owner. */
    private void holdPosition() {
        this.zza = 0.0f;
        clearPlan();
        lookAtPlayer(owner);
    }

    /**
     * Path toward {@code (tx,ty,tz)} (goal floor cell {@code goalFloor}), steering along the plan and
     * falling back to a straight-line steer off-grid. Returns {@code true} once within
     * {@link #ARRIVE_DIST} horizontally <i>and</i> {@link #ARRIVE_Y} vertically (the caller decides what
     * arrival means for its mode).
     */
    private boolean driveToward(double tx, double ty, double tz, BlockPos goalFloor) {
        double dx = tx - this.getX();
        double dy = ty - this.getY();
        double dz = tz - this.getZ();
        double distXZ = Math.sqrt(dx * dx + dz * dz);

        // Arrived only when close in 3D. Horizontal proximity alone would let the bot stop directly
        // BELOW the target (it walks under a sky platform / the top of a staircase and quits) — it must
        // also match the target's height, which is what makes it actually climb to reach you.
        if (distXZ <= ARRIVE_DIST && Math.abs(dy) <= ARRIVE_Y) {
            this.zza = 0.0f;
            clearPlan();
            lookAtPlayer(owner);
            return true;
        }

        // Replan when the goal moves to a new block, the driver is exhausted, or the safety interval elapses.
        // Holding a stable path while the goal stands still stops the debug particles from ghosting.
        // ("Exhausted" is now driver-level: the windowed block plan is spent AND the region driver reports it
        // is not COMPLETE — the sliding window owns advancing through intermediate windows, so we no longer
        // rebuild the whole two-tier plan just because one window's block path ran out.)
        boolean exhausted = (pathPlan == null)
                || (path == null && !pathPlan.isComplete())
                || (path != null && waypointIndex >= path.size() && !pathPlan.isComplete());
        if (exhausted || !goalFloor.equals(lastGoalCell) || --replanCooldown <= 0) {
            replan(goalFloor);
            lastGoalCell = goalFloor;
            replanCooldown = REPLAN_TICKS;
        } else if (pathPlan != null) {
            // Per-tick driver hook (HPA-IMPLEMENTATION.md §10): report the bot's current floor cell so the
            // sliding window can COMMIT into the next region (the wiggle rule) and replan that window's block
            // path. blockPosition().below() is the bot's floor cell (the same cell convention as replan's
            // startFloor). Then refresh `path` to whatever block plan the driver now exposes; when the driver
            // swapped in a NEW BlockPathPlan instance (window advanced / re-planned), restart the follower at
            // its head so steerAlongPath/applyEdits don't index a stale waypoint/edit cursor.
            pathPlan.onBotMoved(this.blockPosition().below());
            BlockPathPlan now = pathPlan.currentBlockPlan();
            if (now != lastBlockPlanRef) {
                this.path = now;
                this.lastBlockPlanRef = now;
                this.waypointIndex = 0;
                this.lastEditedIndex = -1;
            }
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
        return false;
    }

    /**
     * Plan a fresh <b>two-tier</b> path from the bot's floor cell to {@code goalFloor}
     * (HPA-IMPLEMENTATION.md §10). Builds a new {@link PathPlan} — which plans the coarse region skeleton
     * once ({@link com.orebit.mod.pathfinding.regionpathfinder.RegionPathfinder}) and computes the first
     * window's block path — and exposes that window's block plan through {@link #path} so the existing
     * follower keeps working unchanged. The driver then advances the sliding window per tick via
     * {@link PathPlan#onBotMoved} (see {@link #driveToward}); this whole-plan rebuild only fires on a new
     * goal / when the driver is fully exhausted.
     *
     * <p>The nav grid the block tier reads is kept live by the {@code LevelChunk.setBlockState} mixin
     * (BlockChangeEvents → NavGridUpdater.patchCell), so each window's block search sees current terrain —
     * including the bot's own break/place edits — without a per-replan rebuild. A chunk not yet built by
     * the on-load pipeline reads as unbuilt and is skipped, the same as any unloaded area.
     */
    private void replan(BlockPos goalFloor) {
        ServerLevel level = (ServerLevel) Worlds.of(this);
        BlockPos startFloor = this.blockPosition().below();

        this.pathPlan = new PathPlan(level, RegionGrid.of(level), startFloor, goalFloor, CAPS);
        this.path = pathPlan.currentBlockPlan();
        this.lastBlockPlanRef = this.path;
        this.waypointIndex = 0;
        this.lastEditedIndex = -1;

        boolean hasPath = path != null && path.size() > 0;
        if (DEBUG_PATH) {
            // Verbose per-plan trace while debugging: the full waypoint list shows exactly which cells
            // A* produced (e.g. whether a stacked staircase step is present or skipped).
            if (hasPath) {
                OrebitCommon.LOGGER.info("[Orebit] plan: {} wp cost={} start={} goal={} path={}",
                        path.size(), path.cost(), compact(startFloor), compact(goalFloor), waypointsString());
            } else {
                // FAIL-visible diagnostic (HPA-IMPLEMENTATION.md §10: pathological failures stay visible).
                // The two-tier driver exposes no block plan — report whether the start/goal cells even have
                // built nav data, the most common cause (cells outside the loaded/built radius). A throwaway
                // NavGridView is built only on this rare no-path branch, purely for the built() probes.
                NavGridView grid = new NavGridView(level);
                OrebitCommon.LOGGER.info("[Orebit] plan: NONE start={}(built={}) goal={}(built={})",
                        startFloor, grid.built(startFloor.getX(), startFloor.getY(), startFloor.getZ()),
                        goalFloor, grid.built(goalFloor.getX(), goalFloor.getY(), goalFloor.getZ()));
            }
        } else if (hasPath != loggedHasPath) {
            loggedHasPath = hasPath;
            if (hasPath) {
                OrebitCommon.LOGGER.info("[Orebit] bot path: {} waypoints (cost {})", path.size(), path.cost());
            } else {
                NavGridView grid = new NavGridView(level);
                OrebitCommon.LOGGER.info("[Orebit] bot path: none (startBuilt={}, goalBuilt={})",
                        grid.built(startFloor.getX(), startFloor.getY(), startFloor.getZ()),
                        grid.built(goalFloor.getX(), goalFloor.getY(), goalFloor.getZ()));
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

        // Apply this step's folded break/place edits once, the moment it becomes the current step: the
        // bot is standing at the previous waypoint and the cells to clear/fill are right in front, so the
        // route is made physically walkable before the bot tries to move into it. PILLAR is the exception:
        // its footing is placed in the bot's OWN feet cell, so it must wait until the bot has jumped clear
        // of that cell (airborne) — otherwise the block would be set inside the bot.
        boolean pillar = path.movement(waypointIndex) == MovementRegistry.PILLAR;
        StepEdits edits = path.edits(waypointIndex);
        if (edits != null && waypointIndex != lastEditedIndex
                && (!pillar || !EntityState.onGround(this))) {
            lastEditedIndex = waypointIndex;
            applyEdits(edits);
        }

        BlockPos wp = path.waypoint(waypointIndex);
        double ddx = (wp.getX() + 0.5) - this.getX();
        double ddz = (wp.getZ() + 0.5) - this.getZ();

        float yaw = (float) (Math.toDegrees(Math.atan2(-ddx, ddz)));
        this.setYRot(yaw);
        this.setYBodyRot(yaw);
        this.setYHeadRot(yaw);
        this.zza = 1.0f;

        // Jump only when the step the planner chose is an Ascend (a real jump-up onto a full block,
        // head-clearance already verified). A Traverse step-assist (slab/stair/snow lip) is auto-stepped
        // by vanilla movement and must NOT jump, or the bot launches over low steps. Stuck is the
        // backstop for physical hitches the planner can't see.
        // Jump on an Ascend (jump-up onto a full block) or a Pillar (jump-and-place straight up); both
        // need the bot off the ground. A Traverse step-assist must NOT jump (vanilla auto-steps it).
        boolean ascend = path.movement(waypointIndex) == MovementRegistry.ASCEND;
        Vec3 velocity = this.getDeltaMovement();
        boolean stuck = velocity.horizontalDistanceSqr() < STUCK_SPEED_SQR;
        if ((ascend || pillar || stuck) && EntityState.onGround(this)) {
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

    /**
     * Execute a step's folded break/place edits server-side, re-validated against the live world (cells
     * may have changed since planning). Breaks drop nothing (no inventory model yet); places use a
     * throwaway block. The nav grid isn't refreshed here — the bot performs exactly the edits the plan
     * assumed, so the route is now physically walkable, and the next replan rebuilds the spanned chunks
     * from the live world (so it plans over the bot's own changes).
     */
    private void applyEdits(StepEdits edits) {
        ServerLevel level = (ServerLevel) Worlds.of(this);
        for (int i = 0; i < edits.breakCount(); i++) {
            BlockPos p = edits.breakPos(i);
            if (!level.getBlockState(p).isAir()) WorldEdits.breakBlock(level, p);
        }
        for (int i = 0; i < edits.placeCount(); i++) {
            BlockPos p = edits.placePos(i);
            if (Replaceable.isReplaceable(level.getBlockState(p))) {
                WorldEdits.placeBlock(level, p, PLACE_BLOCK);
            }
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
