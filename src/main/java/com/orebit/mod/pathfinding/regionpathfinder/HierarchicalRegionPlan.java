package com.orebit.mod.pathfinding.regionpathfinder;

import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.worldmodel.hpa.RegionAddress;
import com.orebit.mod.worldmodel.hpa.RegionGrid;

import net.minecraft.core.BlockPos;

/**
 * The HPA* region-tier <b>stateful nested-skeleton cascade</b> (HPA-CASCADE.md, the "S6" arc) — a stack of
 * per-level region skeletons, one per pyramid level {@code L = topLevel … 0}, each navigating within the window
 * handed down from the level above. It is the source of the level-0 skeleton the
 * {@link com.orebit.mod.pathfinding.PathPlan} block-window driver consumes. (It replaced an earlier two-tier
 * shortcut — one coarse level + one L0 near refine — now deleted.)
 *
 * <h2>Why a cascade (HPA-CASCADE.md §1)</h2>
 * The two-tier branch plans one coarse level + one L0 near refine, re-searched from scratch every replan. That
 * leaves (1) <b>no intermediate routing</b> between the L0 horizon (~128 blk) and the coarse cell (1024+ blk) —
 * a medium obstacle is walked-into-then-rerouted — and (2) a <b>redundant full re-search</b> each replan. The
 * cascade fixes both: a stack of skeletons re-planned <b>only at the level whose window the bot exited</b>
 * (coarse levels rarely, the leaf often), so a medium obstacle is pre-routed at the level whose cells are its
 * scale, and the macro route is not re-derived when it barely changed. This is the L0 block-window slider
 * recursed up the region pyramid — and is what lets a path reach out to millions of blocks: the top level
 * <b>slides</b> toward the goal and <b>collapses</b> as the bot nears it, so the pyramid is never made taller
 * than {@link RegionAddress#MAX_COARSE_LEVEL} (§7).
 *
 * <h2>The model (§2)</h2>
 * Each {@link LevelPlan} holds its level's skeleton (region coords AT that level, see {@link RegionPathPlan#level})
 * plus {@code committedIndex} — how far along the skeleton the bot has committed (hysteresis). The top is planned
 * toward the goal (clamped cap-safe); each lower level is planned from the bot toward the <b>window sub-goal</b>
 * handed down from the level above — the far cell ({@link #WINDOW_CELLS} ahead) of that level's skeleton. The
 * bottom (L0) skeleton is what {@link com.orebit.mod.pathfinding.PathPlan} drives. Every per-level search is tiny
 * and cap-safe by construction (it spans only ~{@code WINDOW_CELLS} toward its hand-down; only the top can be
 * {@code maxCheb}-sized — the already-proven cap-safe bound, §8).
 *
 * <h2>House style (HPA-CASCADE.md §10, §14)</h2>
 * The {@code LevelPlan[]} is a fixed array (≤ {@link RegionAddress#MAX_COARSE_LEVEL}+1), reused across goals. The
 * per-tick {@link #onBotMoved} fast path (still inside every window) does only {@code region(bot,L)} integer math
 * per level (≤7) and allocates nothing; a re-plan runs at most a small suffix of windowed searches (reusing
 * {@link RegionPathfinder}'s {@code ThreadLocal} search state — the cascade is sequential) and allocates only the
 * replaced immutable {@link RegionPathPlan} arrays + a couple of sub-goal {@link BlockPos}.
 */
public final class HierarchicalRegionPlan {

    /**
     * Cells of macro route each level commits before handing the finer level a sub-goal (HPA-CASCADE.md §15.1):
     * the hand-down is the {@code WINDOW_CELLS}-th cell of the level's skeleton, and the level re-plans when the
     * bot commits that far. Bigger ⇒ longer commits, fewer re-plans, looser intermediate routing.
     */
    public static final int WINDOW_CELLS = 4;

    // ---- immutable navigation context ----------------------------------------------------------------
    private final RegionGrid grid;
    private final int minY;
    private final BlockPos goal;
    private final BotCaps caps;
    /** The bot's tool-aware region dig-cost model (PERF-DESIGN region §5), built once from its inventory. */
    private final RegionMineModel mine;

    // ---- the stack (indices 0..topLevel valid) -------------------------------------------------------
    private final LevelPlan[] levels = new LevelPlan[RegionAddress.MAX_COARSE_LEVEL + 1];
    /** Per-level forbidden crossings (online repair, §6); index = level. Level-relative keys never collide. */
    private final RegionEdgeBlacklist[] blacklists = new RegionEdgeBlacklist[RegionAddress.MAX_COARSE_LEVEL + 1];
    private int topLevel;
    /** {@code true} once no route exists (a level returned {@code null} with escalation exhausted) — FAILED. */
    private boolean failed;

    private HierarchicalRegionPlan(RegionGrid grid, int minY, BlockPos goal, BotCaps caps, RegionMineModel mine) {
        this.grid = grid;
        this.minY = minY;
        this.goal = goal;
        this.caps = caps;
        this.mine = mine;
        for (int i = 0; i < levels.length; i++) {
            levels[i] = new LevelPlan();
            blacklists[i] = new RegionEdgeBlacklist();
        }
    }

    /**
     * Build a fresh cascade from {@code botFloor} toward {@code goal} (HPA-CASCADE.md §3): choose the cap-safe
     * top level, then descend top→0 planning each level toward the sub-goal handed down from the level above. On
     * a fresh goal the per-level blacklists are empty, so a {@code null} at any level is a genuine no-route ⇒ the
     * returned plan is {@link #isFailed() FAILED} ({@link #l0Skeleton()} {@code null}).
     */
    public static HierarchicalRegionPlan build(RegionGrid grid, int minY, BlockPos botFloor, BlockPos goal,
                                               BotCaps caps) {
        return build(grid, minY, botFloor, goal, caps, RegionMineModel.DEFAULT);
    }

    /**
     * As {@link #build(RegionGrid, int, BlockPos, BlockPos, BotCaps)}, with the bot's tool-aware region dig-cost
     * model ({@link RegionMineModel}, §5) threaded to every per-level {@link RegionPathfinder#planWithin}. The
     * live driver ({@link com.orebit.mod.pathfinding.PathPlan}) passes a model built from the bot's inventory;
     * the no-model overload uses the stone-tier {@link RegionMineModel#DEFAULT} (headless / tests).
     */
    public static HierarchicalRegionPlan build(RegionGrid grid, int minY, BlockPos botFloor, BlockPos goal,
                                               BotCaps caps, RegionMineModel mine) {
        HierarchicalRegionPlan h = new HierarchicalRegionPlan(grid, minY, goal, caps, mine);
        h.rebuild(botFloor);
        return h;
    }

    // ---------------------------------------------------------------------------------------------------
    // Public surface (consumed by PathPlan)
    // ---------------------------------------------------------------------------------------------------

    /** The level-0 region skeleton the block-window driver consumes ({@code null} when FAILED). */
    public RegionPathPlan l0Skeleton() {
        return failed ? null : levels[0].skeleton;
    }

    /** {@code true} when no route exists for these caps (the driver then reports FAILED, as today). */
    public boolean isFailed() {
        return failed;
    }

    /** The coarsest level the stack currently spans (collapses toward 0 as the bot nears the goal). */
    public int topLevel() {
        return topLevel;
    }

    /**
     * The current skeleton at {@code level} (or {@code null} if above {@link #topLevel} / unbuilt) — exposed for
     * the headless cascade tests (selective re-plan / stack consistency) and the debug stack dump
     * ({@link com.orebit.mod.pathfinding.PathPlan#describeSkeleton}).
     */
    public RegionPathPlan skeletonAt(int level) {
        return (level < 0 || level > topLevel) ? null : levels[level].skeleton;
    }

    /** The committed cursor at {@code level} (how far along that level's skeleton the bot has committed) — debug. */
    public int committedAt(int level) {
        return (level < 0 || level > topLevel) ? -1 : levels[level].committedIndex;
    }

    /** As {@link #onBotMoved(BlockPos, boolean)} with {@code onRoute=false} — for callers (tests, headless)
     *  that have no block plan to vouch for an off-window excursion. */
    public boolean onBotMoved(BlockPos botFloor) {
        return onBotMoved(botFloor, false);
    }

    /**
     * Per-tick hook (HPA-CASCADE.md §5): given the bot's current floor cell, advance each level's commit and
     * re-plan only the suffix at/below the <b>coarsest level whose window the bot exhausted</b>. Returns
     * {@code true} iff the level-0 skeleton changed (so the driver should swap it in + reset its block window);
     * {@code false} means the bot is still inside every level's window — the driver just slides its block window.
     *
     * <p>{@code onRoute} = the caller (who owns the active block plan) vouches that the bot is currently ON
     * that plan — standing at/near one of its waypoints. A block path may legitimately step through an
     * ADJACENT, non-skeleton region while executing inside the window (a cliff-edge fall-lineup); the plan
     * itself is the authority on whether that excursion is intentional. An off-window bot that is on its plan
     * is following a route the block tier already vouched for, so no re-derive fires (re-deriving there reset
     * the commit cursor and flip-flopped the target every settle — the old cliff bug). An off-window bot NOT
     * on its plan is a genuine deviation and re-derives immediately. (s52: this replaced the old
     * {@code BOUNDARY_CLIP_CHEB} spatial guess — "within 1 region counts as on-route" — with asking the plan.)
     */
    public boolean onBotMoved(BlockPos botFloor, boolean onRoute) {
        if (failed) {
            return false;
        }
        final int bx = botFloor.getX(), by = botFloor.getY(), bz = botFloor.getZ();
        int exited = -1; // coarsest exited level (top-down scan ⇒ first match is the coarsest)
        for (int L = topLevel; L >= 0; L--) {
            final LevelPlan lp = levels[L];
            final RegionPathPlan sk = lp.skeleton;
            final int far = Math.min(WINDOW_CELLS, sk.size() - 1);
            final int brx = RegionAddress.regionX(bx, L);
            final int bry = RegionAddress.regionY(by, L, minY);
            final int brz = RegionAddress.regionZ(bz, L);
            // Advance committedIndex forward to the furthest committed-window cell whose level-L region is the
            // bot's (forward-only, so a transient dip back never retreats it — region-tier commit hysteresis),
            // and note whether the bot is anywhere in the committed window at all.
            boolean inWindow = false;
            for (int i = far; i >= lp.committedIndex; i--) {
                if (sk.rx(i) == brx && sk.ry(i) == bry && sk.rz(i) == brz) {
                    if (i > lp.committedIndex) lp.committedIndex = i;
                    inWindow = true;
                    break;
                }
            }
            // Exit this level when EITHER the bot committed to its far cell (window exhausted — needs sliding,
            // unless that cell is the true goal end) OR the bot is genuinely OFF the route: outside the window
            // AND not on its active block plan (onRoute — the plan vouches for intentional off-window steps
            // like a cliff-edge fall-lineup; see the method javadoc). Re-plan the suffix at/below the coarsest
            // exited level.
            final boolean reachedEnd = sk.reachedGoalRegion() && far == sk.size() - 1;
            final boolean exhausted = !reachedEnd && lp.committedIndex >= far;
            final boolean deviated = !inWindow && !onRoute;
            if (exited == -1 && (exhausted || deviated)) {
                exited = L;
            }
        }
        if (exited == -1) {
            return false; // still within every window — PathPlan slides its block window over the unchanged L0
        }

        // The top exited → recompute the cap-safe top level (collapse as the goal nears / slide for a far goal,
        // §7) and, if it changed, rebuild the whole stack from here.
        if (exited == topLevel) {
            final int newTop = capSafeTop(botFloor);
            if (newTop != topLevel) {
                final RegionPathPlan beforeL0 = levels[0].skeleton;
                rebuild(botFloor);
                return failed || levels[0].skeleton != beforeL0;
            }
        }
        // Re-plan the suffix exited..0 (levels above are untouched — their windows still contain the bot).
        final RegionPathPlan beforeL0 = levels[0].skeleton;
        if (!rederiveFromOrCoarser(exited, botFloor)) {
            failed = true;
            return true; // l0Skeleton() now null → the driver reports FAILED
        }
        return levels[0].skeleton != beforeL0;
    }

    /**
     * Online repair (HPA-CASCADE.md §6): the block tier proved the level-0 crossing {@code l0FromKey →
     * l0ToKey} unrealizable for these caps. Blacklist it and re-plan L0 within the current L1 window; if L0 still
     * can't route, blacklist the corresponding coarser hop and escalate up the hierarchy, re-planning each level
     * until one finds a way around. Returns {@code true} iff a route remains ({@link #l0Skeleton()} non-null);
     * {@code false} ⇒ every level is exhausted (honest give-up). The keys come from
     * {@link RegionPathfinder#fragmentNodeKey}.
     */
    public boolean onBlocked(long l0FromKey, long l0ToKey, BlockPos botFloor) {
        if (failed) {
            return false;
        }
        blacklists[0].add(l0FromKey, l0ToKey);
        if (rederiveSuffix(0, botFloor)) {
            return true;
        }
        // L0 couldn't route around the dead hop → the obstacle is bigger than one L0 cell. Escalate: forbid the
        // bot's current hop at each coarser level in turn and re-plan from there down, until a level reroutes.
        for (int L = 1; L <= topLevel; L++) {
            blacklistCurrentHop(L);
            if (rederiveSuffix(L, botFloor)) {
                return true;
            }
        }
        failed = true;
        return false;
    }

    // ---------------------------------------------------------------------------------------------------
    // Building / re-planning the stack
    // ---------------------------------------------------------------------------------------------------

    /** Full top-down descent from {@code botFloor} (HPA-CASCADE.md §3); sets {@link #failed} on a no-route. */
    private void rebuild(BlockPos botFloor) {
        this.topLevel = capSafeTop(botFloor);
        this.failed = false;
        if (!rederiveSuffix(topLevel, botFloor)) {
            this.failed = true;
        }
    }

    /**
     * Re-plan levels {@code fromLevel … 0} from the bot toward the chained window sub-goals (HPA-CASCADE.md §5):
     * the top of the suffix aims at the level-above's current window-far cell (or the real goal at the top
     * level); each lower level aims at the cell just re-planned above it. Levels above {@code fromLevel} are
     * untouched. Returns {@code false} (without partial mutation beyond the levels it replaced) if any level has
     * no route — the caller escalates.
     */
    private boolean rederiveSuffix(int fromLevel, BlockPos botFloor) {
        BlockPos subGoal = (fromLevel >= topLevel) ? goal : handDown(levels[fromLevel + 1]);
        for (int L = fromLevel; L >= 0; L--) {
            RegionPathPlan skel = RegionPathfinder.planWithin(L, grid, minY, botFloor, subGoal, goal, caps,
                    blacklists[L], mine);
            if (skel == null) {
                return false;
            }
            levels[L].skeleton = skel;
            levels[L].committedIndex = 0;
            subGoal = handDown(levels[L]);
        }
        return true;
    }

    /**
     * Re-plan from {@code fromLevel} down; if that suffix has no route, retry from successively coarser levels
     * (more routing freedom) up to {@link #topLevel}. Used by {@link #onBotMoved} (no proven-bad hop to
     * blacklist — just widen the search). Returns whether a level-0 route was produced.
     */
    private boolean rederiveFromOrCoarser(int fromLevel, BlockPos botFloor) {
        for (int L = fromLevel; L <= topLevel; L++) {
            if (rederiveSuffix(L, botFloor)) {
                return true;
            }
        }
        return false;
    }

    /** Forbid the bot's current committed hop at {@code level} ({@code cell[committed] → cell[committed+1]}). */
    private void blacklistCurrentHop(int level) {
        final RegionPathPlan sk = levels[level].skeleton;
        final int from = levels[level].committedIndex;
        final int to = from + 1;
        if (to >= sk.size()) {
            return; // no onward hop at this level to blame
        }
        blacklists[level].add(
                RegionPathfinder.fragmentNodeKey(sk.rx(from), sk.ry(from), sk.rz(from), sk.fragmentId(from)),
                RegionPathfinder.fragmentNodeKey(sk.rx(to), sk.ry(to), sk.rz(to), sk.fragmentId(to)));
    }

    /**
     * The world sub-goal a level hands its finer neighbour: the portal cell (fallback: the center) of its
     * window-far cell — the {@link #WINDOW_CELLS}-th skeleton cell, clamped to the skeleton tail. When this level
     * already reaches the true goal region at that far cell, hand down the <b>real goal</b> instead of the coarse
     * cell center, so the finer levels aim precisely at the goal as the stack collapses (§7).
     */
    private BlockPos handDown(LevelPlan lp) {
        final RegionPathPlan sk = lp.skeleton;
        final int far = Math.min(WINDOW_CELLS, sk.size() - 1);
        if (sk.reachedGoalRegion() && far == sk.size() - 1) {
            return goal;
        }
        BlockPos portal = sk.portalCell(far);
        return portal != null ? portal : sk.centerOf(far);
    }

    /** The cap-safe top level for the bot→goal span (HPA-CASCADE.md §7 collapse/slide; {@link RegionAddress#MAX_COARSE_LEVEL} ceiling). */
    private int capSafeTop(BlockPos botFloor) {
        final int srx = RegionAddress.regionX(botFloor.getX(), 0);
        final int sry = RegionAddress.regionY(botFloor.getY(), 0, minY);
        final int srz = RegionAddress.regionZ(botFloor.getZ(), 0);
        final int grx = RegionAddress.regionX(goal.getX(), 0);
        final int gry = RegionAddress.regionY(goal.getY(), 0, minY);
        final int grz = RegionAddress.regionZ(goal.getZ(), 0);
        return RegionPathfinder.chooseCapSafeLevel(srx, sry, srz, grx, gry, grz);
    }

    /** One level of the cascade: its skeleton (region coords at {@link RegionPathPlan#level}) + commit cursor. */
    private static final class LevelPlan {
        RegionPathPlan skeleton;
        int committedIndex;
    }
}
