package com.orebit.mod.pathfinding;

import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.regionpathfinder.RegionPathPlan;
import com.orebit.mod.pathfinding.regionpathfinder.RegionPathfinder;
import com.orebit.mod.worldmodel.hpa.RegionAddress;
import com.orebit.mod.worldmodel.hpa.RegionFragments;
import com.orebit.mod.worldmodel.hpa.RegionGrid;
import com.orebit.mod.worldmodel.navblock.NavBlock;
import com.orebit.mod.worldmodel.pathing.NavGridView;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Window-target selection for {@link PathPlan} (HPA-IMPLEMENTATION.md §9; HPA-FRAGMENTS.md §6/§S4) — the
 * "where should the current window's block search aim" policy, extracted verbatim from the driver so the
 * sliding-window control flow and the target-selection geometry live apart.
 *
 * <p><b>Ownership</b>: this class owns NO mutable plan state. The constructor captures the plan-immutable
 * context (level, region grid, caps, goal); each {@link #target} call receives the CURRENT skeleton +
 * window bounds and returns a small immutable {@link Result} (target cell + skeleton step + how it was
 * chosen). {@link PathPlan#replanBlock} reads the result and remains the sole writer of
 * {@code windowTargetPos}/{@code windowTargetStep}/{@code windowTargetKind}. Replan cadence only — the
 * one {@link Result} allocation per call matches the existing per-replan allocations (fresh
 * {@link NavGridView}, {@code BlockPos}es); nothing here runs on a per-tick path.
 */
final class WindowTargeting {

    private final ServerLevel level;
    private final RegionGrid regionGrid;
    private final int minY;
    private final BotCaps caps;
    private final BlockPos goalFloor;
    /** The goal's level-0 region coords (so "goal in window" can be tested by index). */
    private final int goalRX, goalRY, goalRZ;

    WindowTargeting(ServerLevel level, RegionGrid regionGrid, int minY, BotCaps caps,
                    BlockPos goalFloor, int goalRX, int goalRY, int goalRZ) {
        this.level = level;
        this.regionGrid = regionGrid;
        this.minY = minY;
        this.caps = caps;
        this.goalFloor = goalFloor;
        this.goalRX = goalRX;
        this.goalRY = goalRY;
        this.goalRZ = goalRZ;
    }

    /**
     * One window-target choice: the block-A* target cell, the skeleton step it corresponds to, and
     * {@link PathPlan.TargetKind how it was chosen}. Tiny immutable holder, allocated once per
     * {@link #target} call (replan cadence — cold).
     */
    static final class Result {
        final BlockPos pos;
        final int step;
        final PathPlan.TargetKind kind;

        Result(BlockPos pos, int step, PathPlan.TargetKind kind) {
            this.pos = pos;
            this.step = step;
            this.kind = kind;
        }
    }

    /**
     * The current window's block target (HPA-IMPLEMENTATION.md §9; HPA-FRAGMENTS.md §6/§S4). If the goal's
     * level-0 region is within the window (goal in window), the real {@code goalFloor} (never projected). Else,
     * for a <b>fragment-model</b> skeleton, the <b>farthest window step that yields a usable target cell</b>,
     * walking far→near. A portal is only the bbox-center of a fragment's face footprint, so the centroid can
     * land in solid rock (the A→B bounce) <i>or</i> in mid-air with no floor (an {@code air-no-floor} portal —
     * the descent/ascent flood: the block tier can't stand at a point it only falls through, so it floods the
     * open cave). A centroid is used directly when {@link #isUsableTarget usable}, or when it is BURIED in
     * {@link NavBlock#isBreakable breakable} solid and the bot can break (raw {@link PathPlan.TargetKind#DIG} —
     * the block A* digs to it, preserving the far step's routing information rather than degrading to a near
     * snap; unbreakable/protected solids keep the snap — no search can ever mine to those); otherwise we
     * {@link #snapInFootprint snap} to a real standable cell within the portal's footprint bbox. Whether a
     * mid-air cell is acceptable is {@link #airTargetOk caps + direction}-aware (a place-capable bot climbing
     * upward may target air; everyone else needs standable ground). For a <b>center-model</b> skeleton (coarse
     * branch) or when no window step yields a cell, the far region's center projected to a standable floor.
     */
    Result target(RegionPathPlan skeleton, int windowStart, int windowLast) {
        final int last = windowLast;
        // Goal in window? The goal region is the skeleton's tail iff reachedGoalRegion; treat "goal region
        // index ≤ last" by checking whether any window region equals the goal region.
        if (skeleton.reachedGoalRegion()) {
            // Fragment-aware guard (FRAGMENT_AWARE_GOAL_WINDOW): the goal is the skeleton TAIL's (region,fragment).
            // A region-only match is a false positive when the window contains the goal region at a DIFFERENT
            // fragment (goal = a separate pocket of the start's own region, reached via a loop) — it targets the
            // goal directly and unconfines the search into a flood. Requiring the fragment match falls through to
            // the near-window portal. Default off keeps that flood reproducible for pruning research.
            final boolean fragAware = PathPlan.FRAGMENT_AWARE_GOAL_WINDOW && skeleton.isFragmentModel();
            final int goalFrag = fragAware ? skeleton.fragmentId(skeleton.size() - 1) : -1;
            for (int i = windowStart; i <= last; i++) {
                if (skeleton.rx(i) == goalRX && skeleton.ry(i) == goalRY && skeleton.rz(i) == goalRZ
                        && (!fragAware || skeleton.fragmentId(i) == goalFrag)) {
                    return new Result(goalFloor, i, PathPlan.TargetKind.GOAL);
                }
            }
        }
        // Fragment model: aim at the FARTHEST window portal that is occupiable. A portal is only the stored
        // bbox CENTROID (lossy) — when it lands in rock we don't give up, because the real opening is still
        // recoverable from the nav grid: snapToOccupiable scans the step's region for the nearest real cell.
        // So we prefer, far→near, per step: (1) a region-committed dig → raw; (2) a usable centroid → raw;
        // (3) a centroid BURIED in breakable solid, bot can break → raw (dig to it — see below); else (4) record the
        // step's snapped real cell and continue nearer. Only if NO window step yields a raw target does the
        // farthest snap win, and only when even that fails do we fall back to the center projection. The far
        // target carries the skeleton's ROUTING information — snapping destroys it, so raw wins over snap even
        // when the raw cell is buried.
        if (skeleton.isFragmentModel()) {
            final NavGridView grid = new NavGridView(level);
            BlockPos snappedFallback = null; // a snapped cell from the farthest unusable portal, if any
            int snappedStep = last;
            for (int i = last; i > windowStart; i--) {
                if (!skeleton.hasPortal(i)) {
                    continue;
                }
                final BlockPos p = skeleton.portalCell(i);
                // DIG-THROUGH crossing (PERF-DESIGN-region-dig-through.md §5): the portal cell is buried in solid
                // — the region tier committed to mining through this face. Pass it straight through (like the
                // GOAL branch), NOT rejecting/snapping it: the block A* digs to a buried target within its
                // isGoal tolerance and prices break-through under canBreak. Skipping isUsableTarget/snap here is
                // exactly what lets an INTERMEDIATE dig-through be realized instead of relocated to the near
                // wall face (defeating the dig). TargetKind.DIG covers BOTH this region-committed case and the
                // lossy-centroid buried case below.
                if (skeleton.isDig(i)) {
                    return new Result(p, i, PathPlan.TargetKind.DIG);
                }
                final boolean airOK = airTargetOk(skeleton, i);
                if (isUsableTarget(grid, p, airOK)) {
                    return new Result(p, i, PathPlan.TargetKind.PORTAL); // the stored centroid is itself a usable target — best case
                }
                // BURIED centroid + break-capable bot (2026-07-06 incident): the centroid landed in solid rock
                // without the region tier committing a dig (the lossy bbox center just missed the opening — or
                // the whole face footprint is rock). Snapping here can DESTROY the far step's routing value:
                // when every far footprint is unsnappable (a buried stretch), the surviving snappedFallback is
                // the NEAREST crossing's face cell — sometimes 1 block from the bot, which produced a FOUND
                // 0-waypoint empty plan and starved the driver. A break-capable bot doesn't need the snap: treat
                // the buried centroid exactly like the isDig branch — raw DIG target, reached within the block
                // A*'s goal tolerance under break pricing. The re-rooted region field handles a buried root too
                // (costToGoalField's goalDigSeeds seeds the reachable pockets around a solid root, and when the
                // flood can't engage it falls back to nearest-centroid seeding — it never fails on solid). The
                // cell is guaranteed BUILT here (isUsableTarget short-circuits unbuilt cells to usable), so
                // !passable really means solid rock, not an unloaded read. A no-break bot keeps the snap
                // fallback: a raw buried target would be unreachable for it. isBreakable gates the same way
                // (review finding): the derived BREAKABLE bit excludes vanilla-unbreakable (bedrock — nether
                // roof/floor centroids) and owner-PROTECTED solids, cells the block A* can NEVER generate
                // break edits for — aiming raw at one just floods the budget into a wall face, then falsely
                // BLOCKED-blacklists a realizable hop. Those centroids keep the snap (the pre-incident path
                // to the real opening); it also skips fluids (lava centroid ≠ rock to dig).
                final long dCentroid = grid.descriptorAt(p.getX(), p.getY(), p.getZ());
                if (caps.canBreak() && !NavBlock.isPassable(dCentroid) && NavBlock.isBreakable(dCentroid)) {
                    return new Result(p, i, PathPlan.TargetKind.DIG);
                }
                // Not usable (buried in rock, or a mid-air cell we can't / shouldn't stand at): recover a real
                // cell by scanning ONLY this portal's footprint bbox (§S4) — clamped to the fragment opening,
                // so it's fast and never snaps into a different fragment. Require a STANDABLE cell unless an
                // airborne target is acceptable here (airOK ⇒ place-capable + climbing on, §thought-3).
                if (snappedFallback == null) {
                    BlockPos snapped = snapInFootprint(grid, skeleton, i, p, !airOK);
                    if (snapped != null) {
                        snappedFallback = snapped;
                        snappedStep = i;
                    }
                }
            }
            if (snappedFallback != null) {
                return new Result(snappedFallback, snappedStep, PathPlan.TargetKind.SNAPPED);
            }
            // FREE-FALL EXTENSION (HPA-FRAGMENTS.md §S4): the whole window held no standable cell — typically an
            // all-air column (step off a ledge and drop through empty regions to the ground far below). There is
            // no useful place to stop mid-fall, but the bot can just FALL the whole way, so we extend the horizon
            // DOWN the committed skeleton past the window to the first standable cell — the landing — and aim
            // there. The block tier's Fall reaches it in (essentially) one move AND enforces the bot's own
            // maxFallDistance, so a drop too deep to survive simply fails here (→ repair) rather than being
            // wrongly committed. Gated on STRICTLY descending: you can only cheaply fall to a far target going
            // down, so we stop the instant the skeleton stops dropping (never aim the search at a far up/lateral
            // cell). Iterative, bounded by the skeleton length and the first floor found.
            for (int i = last + 1; i < skeleton.size() && skeleton.ry(i) < skeleton.ry(i - 1); i++) {
                if (!skeleton.hasPortal(i)) {
                    continue;
                }
                final BlockPos p = skeleton.portalCell(i);
                if (isUsableTarget(grid, p, false)) { // standable / unbuilt (no air target mid-fall)
                    return new Result(p, i, PathPlan.TargetKind.EXTENDED);
                }
                final BlockPos snapped = snapInFootprint(grid, skeleton, i, p, true); // require the standable landing floor
                if (snapped != null) {
                    return new Result(snapped, i, PathPlan.TargetKind.EXTENDED);
                }
            }
        }
        BlockPos center = skeleton.centerOf(last);
        BlockPos floor = projectToStandableFloor(center);
        return new Result((floor != null) ? floor : center, last, PathPlan.TargetKind.CENTER);
    }

    /**
     * Whether an AIRBORNE (non-standable) window target is acceptable at skeleton step {@code i}: only when the
     * bot can <b>place</b> (so it can pillar up to / hold an air cell) AND a climb is coming — either the next
     * crossing rises ({@code ry(i+1) > ry(i)}, immediate climb) OR the one after it does ({@code ry(i+2) > ry(i)},
     * the 45° staircase: lateral now, up next), so the airborne height feeds that climb (§thought-3). A no-place
     * bot can't reach an air cell at all (and never has an upward-air hop — the region tier excludes those), and a
     * purely lateral/downward stretch gets no benefit from being airborne, so both prefer a standable target.
     *
     * <p>No separate "not descending next" guard is needed: regions are 6-connected, so {@code ry} changes by at
     * most ±1 per step, which means {@code ry(i+2) > ry(i)} can only hold when {@code ry(i+1) >= ry(i)} (you can't
     * climb two regions of height in one move) — so a {@code ry(i+1) < ry(i)} (descend-next) step makes <i>both</i>
     * disjuncts false. Keeping the {@code ry(i+1)} disjunct (rather than {@code ry(i+2)} alone) preserves the
     * up-then-back-down "transit layer" case (rise to swap to a disjoint lower fragment). Lookahead reads the
     * committed skeleton beyond the window; the bounds checks make it false near the skeleton's tail.
     */
    private boolean airTargetOk(RegionPathPlan skeleton, int i) {
        if (!caps.canPlace()) {
            return false;
        }
        final int n = skeleton.size();
        final int ryi = skeleton.ry(i);
        return (i + 1 < n && skeleton.ry(i + 1) > ryi)   // immediate climb
                || (i + 2 < n && skeleton.ry(i + 2) > ryi); // staircase: lateral now, climb next
    }

    /**
     * Whether {@code p} is directly usable as a block-A* target. A real <b>standable</b> floor (with headroom)
     * always is; a <b>water</b> cell always is (the bot can swim there — no floor or capability needed); a bare
     * <b>passable</b> (air) cell is usable only when {@code airOK} (we intend to climb up to it); an
     * <b>unbuilt</b> cell counts as usable (optimistic frontier — the block tier resolves real geometry on
     * approach). Buried-in-rock and (when {@code !airOK}) dry mid-air cells are NOT usable → the caller snaps.
     */
    private static boolean isUsableTarget(NavGridView grid, BlockPos p, boolean airOK) {
        final int x = p.getX(), y = p.getY(), z = p.getZ();
        if (!grid.built(x, y, z)) {
            return true;
        }
        final long desc = grid.descriptorAt(x, y, z);
        if (NavBlock.isStandable(desc) && NavBlock.isPassable(grid.descriptorAt(x, y + 1, z))) {
            return true;
        }
        // A swimmable WATER cell is occupiable — every bot can swim (no place/break/climb needed), so an
        // underwater opening (passable but not standable, i.e. "air-no-floor" under the surface) is a perfectly
        // good target, NOT a mid-air cell to reject. Without this the window target snapped down to the seafloor
        // (or flooded the center fallback), making the block tier dive/flood instead of swimming to the portal.
        // SWIMMABLE water specifically (full water + no collision), not merely "water present" — a waterlogged
        // solid (water fluid but a collision shape, e.g. a waterlogged fence) is an obstacle you can't float
        // through, so it must NOT count as a target.
        if (NavBlock.isSwimmableWater(desc)) {
            return true;
        }
        return airOK && NavBlock.isPassable(desc);
    }

    /**
     * Recover a real target cell for a portal whose centroid isn't usable (HPA-FRAGMENTS.md §S4). Scans <b>only
     * the portal's footprint bbox</b> — the two in-face axes clamped to the stored footprint (so we never snap
     * into a different fragment, and it's far fewer cells than the whole 16³ region), while the perpendicular
     * axis scans the full region height so an air opening's standable floor BELOW it is found. Returns the cell
     * nearest the centroid that is a standable floor with headroom; when {@code !requireStandable} (an airborne
     * target is allowed here) it falls back to the nearest passable cell. {@code null} when the bbox holds no
     * such cell (the caller then tries a nearer step, else the center projection). Cold path, one bbox scan per
     * unusable target on replan.
     */
    private BlockPos snapInFootprint(NavGridView grid, RegionPathPlan skeleton, int step, BlockPos near,
                                     boolean requireStandable) {
        final int s = RegionAddress.LEAF_SIZE;
        final int x0 = skeleton.rx(step) << RegionAddress.LEAF_BITS;
        final int y0 = minY + (skeleton.ry(step) << RegionAddress.LEAF_BITS);
        final int z0 = skeleton.rz(step) << RegionAddress.LEAF_BITS;
        // The footprint of this step's entrance fragment, on the face it's entered from.
        final int face = entranceFace(skeleton, step);
        final RegionFragments rf = regionGrid.fragmentRecord(0, skeleton.rx(step), skeleton.ry(step),
                skeleton.rz(step));
        // The virtual goal node has no real fragment record — snap within the whole region (NO_FACE) rather than
        // indexing rf.footprint with the sentinel id. (In practice the goal-in-window branch fires before a virtual
        // tail is portal-snapped, but guard defensively against an AIOOBE.)
        final int packed = (rf != null && !rf.isUniform()
                && !RegionPathfinder.isVirtualGoal(skeleton.fragmentId(step)))
                ? rf.footprint(skeleton.fragmentId(step), face) : RegionFragments.NO_FACE;
        int uMin = 0, uMax = s - 1, vMin = 0, vMax = s - 1;
        if (packed != RegionFragments.NO_FACE) {
            uMin = RegionFragments.footprintMinU(packed); uMax = RegionFragments.footprintMaxU(packed);
            vMin = RegionFragments.footprintMinV(packed); vMax = RegionFragments.footprintMaxV(packed);
        }
        // Map the (U,V) in-face axes to world ranges per face; the perpendicular axis scans the full region.
        // In-face axes (RegionFragments): ±X → (Y,Z); ±Y → (X,Z); ±Z → (X,Y).
        int xMin = x0, xMax = x0 + s - 1, yMin = y0, yMax = y0 + s - 1, zMin = z0, zMax = z0 + s - 1;
        switch (face >> 1) {
            case 0: yMin = y0 + uMin; yMax = y0 + uMax; zMin = z0 + vMin; zMax = z0 + vMax; break; // ±X (perp X)
            case 1: xMin = x0 + uMin; xMax = x0 + uMax; zMin = z0 + vMin; zMax = z0 + vMax; break; // ±Y (perp Y)
            default: xMin = x0 + uMin; xMax = x0 + uMax; yMin = y0 + vMin; yMax = y0 + vMax; break; // ±Z (perp Z)
        }
        final int nx = near.getX(), ny = near.getY(), nz = near.getZ();
        int bx = 0, by = 0, bz = 0;
        long bestStand = Long.MAX_VALUE;
        BlockPos bestPass = null;
        long bestPassD = Long.MAX_VALUE;
        for (int y = yMin; y <= yMax; y++) {
            for (int z = zMin; z <= zMax; z++) {
                for (int x = xMin; x <= xMax; x++) {
                    if (!grid.built(x, y, z)) {
                        continue;
                    }
                    final long d = sq(x - nx) + sq(y - ny) + sq(z - nz);
                    final long desc = grid.descriptorAt(x, y, z);
                    if (NavBlock.isStandable(desc) && NavBlock.isPassable(grid.descriptorAt(x, y + 1, z))) {
                        if (d < bestStand) {
                            bestStand = d; bx = x; by = y; bz = z;
                        }
                    } else if (!requireStandable && NavBlock.isPassable(desc) && d < bestPassD) {
                        bestPassD = d; bestPass = new BlockPos(x, y, z);
                    }
                }
            }
        }
        if (bestStand != Long.MAX_VALUE) {
            return new BlockPos(bx, by, bz);
        }
        return requireStandable ? null : bestPass;
    }

    /**
     * The face of skeleton step {@code i}'s region that faces the previous step {@code i-1} — the side the bot
     * ENTERS this region from, where its portal footprint lives. Face encoding (matching {@link RegionAddress}):
     * {@code 0=−X, 1=+X, 2=−Y, 3=+Y, 4=−Z, 5=+Z}. (Adjacent skeleton regions differ by ±1 on exactly one axis.)
     */
    private static int entranceFace(RegionPathPlan skeleton, int i) {
        final int dx = skeleton.rx(i) - skeleton.rx(i - 1);
        final int dy = skeleton.ry(i) - skeleton.ry(i - 1);
        final int dz = skeleton.rz(i) - skeleton.rz(i - 1);
        if (dx > 0) return 0;
        if (dx < 0) return 1;
        if (dy > 0) return 2;
        if (dy < 0) return 3;
        if (dz > 0) return 4;
        return 5;
    }

    private static long sq(int v) {
        return (long) v * v;
    }

    /**
     * Whether {@code p} is a usable block-A* target — i.e. NOT buried in solid rock. A fragment portal cell is
     * the bbox center of a face footprint and can land in solid when the opening is non-convex (§9); aiming the
     * block tier at a sealed cell is the A→B bounce. Usable = built and either passable itself (an air /
     * fall-through cell) or a standable floor with a passable cell just above (room to stand). An <b>unbuilt</b>
     * cell counts as usable (optimistic — the block tier resolves real geometry on approach via its live read).
     *
     * <p>Package-private (not part of {@link #target}'s own flow): {@link SkeletonDump#describeSkeleton} uses it
     * to annotate which step the target selection would aim at.
     */
    static boolean notBuried(NavGridView grid, BlockPos p) {
        final int x = p.getX(), y = p.getY(), z = p.getZ();
        if (!grid.built(x, y, z)) {
            return true;
        }
        if (NavBlock.isPassable(grid.descriptorAt(x, y, z))) {
            return true;
        }
        return NavBlock.isStandable(grid.descriptorAt(x, y, z))
                && NavBlock.isPassable(grid.descriptorAt(x, y + 1, z));
    }

    /**
     * Project a region center down to a standable floor cell within that center's level-0 vertical region
     * (HPA-IMPLEMENTATION.md §9). Scans the 16-tall region column straddling {@code center.y}, nearest-first,
     * for a built cell whose descriptor is {@link NavBlock#isStandable standable}. Returns {@code null} if the
     * column has no standable floor (the caller then uses the raw center). Reads through a fresh
     * {@link NavGridView}; allocation here is bounded (one view, one scan) and happens only on replan.
     */
    private BlockPos projectToStandableFloor(BlockPos center) {
        final NavGridView grid = new NavGridView(level);
        final int cx = center.getX();
        final int cz = center.getZ();
        // The level-0 region this center belongs to, and that region's vertical block span [y0, y0+16).
        final int ry = RegionAddress.regionY(center.getY(), 0, minY);
        final int y0 = minY + (ry << RegionAddress.LEAF_BITS);
        final int y1 = y0 + RegionAddress.LEAF_SIZE;
        // Search outward from the center y so we land on the closest standable floor.
        final int cy = center.getY();
        for (int d = 0; d < RegionAddress.LEAF_SIZE; d++) {
            int yDown = cy - d;
            if (yDown >= y0 && yDown < y1 && grid.built(cx, yDown, cz)
                    && NavBlock.isStandable(grid.descriptorAt(cx, yDown, cz))) {
                return new BlockPos(cx, yDown, cz);
            }
            int yUp = cy + d;
            if (d != 0 && yUp >= y0 && yUp < y1 && grid.built(cx, yUp, cz)
                    && NavBlock.isStandable(grid.descriptorAt(cx, yUp, cz))) {
                return new BlockPos(cx, yUp, cz);
            }
        }
        return null;
    }
}
