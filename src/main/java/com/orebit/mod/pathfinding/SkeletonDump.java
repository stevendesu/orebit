package com.orebit.mod.pathfinding;

import com.orebit.mod.OrebitCommon;
import com.orebit.mod.pathfinding.blockpathfinder.StepEdits;
import com.orebit.mod.pathfinding.regionpathfinder.RegionPathPlan;
import com.orebit.mod.worldmodel.hpa.RegionAddress;
import com.orebit.mod.worldmodel.hpa.RegionFragments;
import com.orebit.mod.worldmodel.navblock.NavBlock;
import com.orebit.mod.worldmodel.pathing.NavGridView;

import net.minecraft.core.BlockPos;

/**
 * Diagnostics formatting for {@link PathPlan} — the skeleton dump ({@link PathPlan#describeSkeleton}) and
 * the block-plan shape log, extracted verbatim from the driver. Pure formatting over the plan's current
 * state (read via same-package field access; nothing here mutates the plan), cold path only: one dump per
 * replan / trace, under {@code Debug.ENABLED} or an explicit {@code /bot trace}.
 */
final class SkeletonDump {

    private SkeletonDump() {
    }

    /**
     * The bot's own <b>commit key</b> — {@code (rx,ry,rz:frag)} — in the same notation the skeleton steps are
     * printed in, so a slide line can be read as a straight key comparison instead of a coordinate conversion
     * done by hand. {@code frag} is the caller's already-resolved {@code botFragmentAt} value (a
     * nearest-CENTROID membership signal, deliberately not re-derived here).
     */
    static String botKey(int minY, BlockPos floor, int frag) {
        return "(" + RegionAddress.regionX(floor.getX(), 0)
                + "," + RegionAddress.regionY(floor.getY(), 0, minY)
                + "," + RegionAddress.regionZ(floor.getZ(), 0)
                + ":" + frag + ")";
    }

    /**
     * The skeleton steps in {@code [lo, hi]} as {@code S<i>(rx,ry,rz:frag)}, for the forward-slide logs.
     *
     * <p><b>Why the whole span and not just the endpoints.</b> A multi-step slide has two very different
     * causes that the endpoints alone cannot separate: the bot really crossed several regions, or the
     * intervening steps SHARE the committed step's region and differ only by fragment (an intra-region MINE
     * edge). {@link PathPlan#forwardIndexOf} returns the FIRST exact {@code (region, fragment)} match, so in
     * the second case one fragment re-resolution advances the cursor several steps without the bot leaving
     * its region — and that path bypasses the wiggle guard entirely ({@code sameRegionDig}). Printing the
     * span makes the two cases distinguishable at a glance.
     */
    static String stepSpan(PathPlan plan, int lo, int hi) {
        final RegionPathPlan sk = plan.skeleton;
        if (sk == null) return "none";
        final int a = Math.max(0, Math.min(lo, hi));
        final int b = Math.min(sk.size() - 1, Math.max(lo, hi));
        final StringBuilder sb = new StringBuilder();
        for (int i = a; i <= b; i++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append('S').append(i).append('(').append(sk.rx(i)).append(',').append(sk.ry(i))
                    .append(',').append(sk.rz(i)).append(':').append(sk.fragmentId(i)).append(')');
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    /** The implementation behind {@link PathPlan#describeSkeleton} — see that method's Javadoc. */
    static String describeSkeleton(PathPlan plan) {
        final RegionPathPlan skeleton = plan.skeleton;
        if (skeleton == null || skeleton.isEmpty()) {
            return "skeleton: NONE (no coarse route — no built ground at start, or region A* FAILed)";
        }
        final NavGridView grid = new NavGridView(plan.level);
        final StringBuilder sb = new StringBuilder();
        sb.append("skeleton ").append(skeleton.size()).append(" steps  fragmentModel=")
                .append(skeleton.isFragmentModel()).append("  committed=").append(plan.committedIndex)
                .append("  window=[").append(plan.windowStart).append("..").append(plan.windowLast())
                .append("]  goalRegion=(").append(plan.goalRX).append(',').append(plan.goalRY).append(',')
                .append(plan.goalRZ)
                .append(")  reachedGoal=").append(skeleton.reachedGoalRegion());
        // Cascade stack (HPA-CASCADE.md): when the nested-skeleton cascade drives this plan, dump every coarse
        // level above L0 — its skeleton, the committed cursor (*), and each cell's portal/center probe — so the
        // L1/L2 macro route + which regions are built/standable/water is legible (the L0 detail follows below).
        if (plan.hier != null) {
            sb.append("\n  CASCADE top=L").append(plan.hier.topLevel())
                    .append(plan.hier.isFailed() ? " FAILED" : "");
            for (int L = plan.hier.topLevel(); L >= 1; L--) {
                final RegionPathPlan sk = plan.hier.skeletonAt(L);
                if (sk == null) {
                    sb.append("\n  L").append(L).append(": (none)");
                    continue;
                }
                sb.append("\n  L").append(L).append(' ').append(sk.size()).append(" steps committed=")
                        .append(plan.hier.committedAt(L)).append(" reachedGoal=").append(sk.reachedGoalRegion());
                for (int i = 0; i < sk.size(); i++) {
                    sb.append("\n    L").append(L).append('.').append(i)
                            .append(i == plan.hier.committedAt(L) ? "*" : " ")
                            .append(" region=(").append(sk.rx(i)).append(',').append(sk.ry(i)).append(',')
                            .append(sk.rz(i)).append(") frag=").append(sk.fragmentId(i));
                    if (sk.hasPortal(i)) {
                        final BlockPos p = sk.portalCell(i);
                        sb.append(" portal=").append(compactPos(p)).append(probe(grid, p));
                    }
                    final BlockPos cc = sk.centerOf(i);
                    sb.append(" center=").append(compactPos(cc)).append(probe(grid, cc));
                }
            }
            sb.append("\n  L0 (driven):");
        }
        final int last = plan.windowLast();
        // The step windowTarget() actually aims at: the farthest window portal that's non-buried, else (all
        // buried) the farthest portal (it gets snapped). Mark THAT, not just windowLast — the two differ when
        // the far portal is buried, which is exactly when this dump matters.
        int targetStep = -1;
        for (int i = last; i > plan.windowStart; i--) {
            if (skeleton.hasPortal(i) && WindowTargeting.notBuried(grid, skeleton.portalCell(i))) {
                targetStep = i;
                break;
            }
        }
        if (targetStep == -1) {
            for (int i = last; i > plan.windowStart; i--) {
                if (skeleton.hasPortal(i)) { targetStep = i; break; }
            }
        }
        for (int i = 0; i < skeleton.size(); i++) {
            final int rx = skeleton.rx(i), ry = skeleton.ry(i), rz = skeleton.rz(i);
            final String tag = (i == targetStep) ? "*TARGET"
                    : (i == last) ? "far    "
                    : (i >= plan.windowStart && i <= last) ? "win    " : "       ";
            // Force a build attempt so kind reflects what ensureLeaf produces NOW (the planner already did this
            // during the search; re-doing it is idempotent). navSection = is the underlying NavStore section
            // even present? If navSection=built but kind=AIR, ensureLeaf failed to classify a present section
            // (a region/NavStore bug); if navSection=unbuilt, the chunk nav simply isn't loaded there.
            final BlockPos c = skeleton.centerOf(i);
            plan.regionGrid.ensureLeaf(rx, ry, rz);
            final boolean navSection = grid.built(c.getX(), c.getY(), c.getZ());
            sb.append("\n  S").append(i).append(' ').append(tag)
                    .append(" region=(").append(rx).append(',').append(ry).append(',').append(rz).append(')')
                    .append(" frag=").append(skeleton.fragmentId(i))
                    .append(" kind=").append(kindName(plan.regionGrid.kind(0, rx, ry, rz)))
                    .append(" navSection=").append(navSection ? "built" : "UNBUILT");
            if (skeleton.hasPortal(i)) {
                final BlockPos p = skeleton.portalCell(i);
                sb.append("  portal=").append(compactPos(p)).append(probe(grid, p));
            } else {
                sb.append("  portal=none");
            }
            sb.append("  center=").append(compactPos(c)).append(probe(grid, c));
        }
        return sb.toString();
    }

    /**
     * One-line summary of how much of this plan rides on terrain the nav layer has <b>never seen</b>, or
     * {@code null} when every step of every level is built (the overwhelmingly common case, so the caller
     * logs nothing at all).
     *
     * <p><b>Why this is worth its own log line</b> (owner request 2026-08-12). An UNBUILT region has no
     * measured cost, so the region tier prices it optimistically and its portals get placed at the surface
     * preference (≈y 63) rather than wherever the real cave is. A plan that crosses a RUN of them therefore
     * reads as "climb to daylight and walk", which is correct if the terrain really is open and badly wrong
     * if it is not — and it is invisible in the block tier's logs, because each individual window search
     * succeeds. Measured on the headless flagship the same day: L1 steps 8..16 were all {@code [unbuilt]}
     * with portals pinned at y=64/63, and the bot duly climbed from y≈56 back to y≈104 chasing them, while
     * the manual run over identical terrain had ZERO unbuilt steps and stayed in the caves. The difference
     * was chunk residency, not pathing — which is exactly the class of problem this line exists to expose.
     *
     * <p>Counts a step as unbuilt if EITHER its portal cell or its centre cell has no nav data, matching
     * {@link #probe}'s {@code [unbuilt]} test, and reports the first offending index per level so the run
     * can be correlated against the full dump.
     */
    static String unbuiltSummary(PathPlan plan) {
        final RegionPathPlan skeleton = plan.skeleton;
        if (skeleton == null || skeleton.isEmpty()) {
            return null;
        }
        final NavGridView grid = new NavGridView(plan.level);
        final StringBuilder sb = new StringBuilder();
        int total = 0;
        if (plan.hier != null) {
            for (int L = plan.hier.topLevel(); L >= 1; L--) {
                final RegionPathPlan sk = plan.hier.skeletonAt(L);
                if (sk == null) {
                    continue;
                }
                int n = 0;
                int first = -1;
                for (int i = 0; i < sk.size(); i++) {
                    if (stepUnbuilt(grid, sk, i)) {
                        n++;
                        if (first < 0) {
                            first = i;
                        }
                    }
                }
                if (n > 0) {
                    total += n;
                    sb.append("  L").append(L).append('=').append(n).append('/').append(sk.size())
                            .append(" from L").append(L).append('.').append(first);
                }
            }
        }
        int n0 = 0;
        int first0 = -1;
        for (int i = 0; i < skeleton.size(); i++) {
            if (stepUnbuilt(grid, skeleton, i)) {
                n0++;
                if (first0 < 0) {
                    first0 = i;
                }
            }
        }
        if (n0 > 0) {
            total += n0;
            sb.append("  L0=").append(n0).append('/').append(skeleton.size()).append(" from S").append(first0);
        }
        return total == 0 ? null : "unbuilt steps in plan —" + sb;
    }

    /** A step rides unbuilt nav data when its portal OR its centre has none — {@link #probe}'s test. */
    private static boolean stepUnbuilt(NavGridView grid, RegionPathPlan sk, int i) {
        if (sk.hasPortal(i)) {
            final BlockPos p = sk.portalCell(i);
            if (!grid.built(p.getX(), p.getY(), p.getZ())) {
                return true;
            }
        }
        final BlockPos c = sk.centerOf(i);
        return !grid.built(c.getX(), c.getY(), c.getZ());
    }

    /**
     * Occupiability annotation for a <b>floor</b> cell {@code p} (the convention for skeleton targets — the
     * solid block the bot stands ON). {@code [stand]} = a real floor with ≥2 passable cells above; {@code
     * [buried]} = a standable block sealed by rock above (the §6 buried-target tell — what looked like
     * {@code [stand]} before this headroom check); {@code [air-no-floor]} = passable but not a floor;
     * {@code [SOLID]} = solid non-floor; {@code [unbuilt]} = no nav data.
     */
    private static String probe(NavGridView grid, BlockPos p) {
        final int x = p.getX(), y = p.getY(), z = p.getZ();
        if (!grid.built(x, y, z)) {
            return "[unbuilt]";
        }
        final long d = grid.descriptorAt(x, y, z);
        if (NavBlock.isStandable(d)) {
            // Standable bit = "solid top you could stand on", but it ignores headroom: a buried rock block has
            // it set. Require 2 passable cells above (feet + head) for a genuinely occupiable floor.
            final boolean feet = NavBlock.isPassable(grid.descriptorAt(x, y + 1, z));
            final boolean head = NavBlock.isPassable(grid.descriptorAt(x, y + 2, z));
            return (feet && head) ? "[stand]" : "[buried]";
        }
        // Swimmable water is an occupiable target — distinguish it from dry mid-air so a [water] portal is
        // legible as REACHABLE in the dump (vs an [air-no-floor] cell that needs a climb intent to target). A
        // waterlogged solid (water fluid but a collision shape) is NOT swimmable → it falls through to [SOLID].
        if (NavBlock.isSwimmableWater(d)) {
            return "[water]";
        }
        if (NavBlock.isLava(d)) {
            return "[LAVA]";
        }
        return NavBlock.isPassable(d) ? "[air-no-floor]" : "[SOLID]";
    }

    private static String compactPos(BlockPos p) {
        return "(" + p.getX() + "," + p.getY() + "," + p.getZ() + ")";
    }

    private static String kindName(int kind) {
        switch (kind) {
            case RegionFragments.KIND_SOLID: return "SOLID";
            case RegionFragments.KIND_AIR:   return "AIR";
            case RegionFragments.KIND_WATER: return "WATER";
            default:                         return "MIXED";
        }
    }

    /**
     * Dump the returned block plan's SHAPE — the first ~10 steps as {@code move d(dx,dy,dz) -> floor [brk/plc]},
     * plus the total cost and whether it's a best-effort PARTIAL. The per-step delta makes a pathological route
     * legible at a glance: a plan that goes DOWN then back UP (a MineDown that undoes a Pillar) shows as
     * {@code d(0,-1,0)} followed by {@code d(0,+1,0)} — the signature we're hunting. Cold (one dump per replan,
     * under {@code Debug.ENABLED}); reads the plan in place, no allocation beyond the string.
     */
    static void logBlockPlan(PathPlan plan) {
        final StringBuilder sb = new StringBuilder();
        sb.append("[Orebit] block plan ").append(plan.blockPlan.size()).append("wp cost=")
                .append(String.format("%.1f", plan.blockPlan.cost()))
                .append(plan.lastPlanPartial ? " PARTIAL" : " FULL")
                .append(" from ").append(compactPos(plan.botFloor)).append(':');
        BlockPos prev = plan.botFloor;
        final int lim = Math.min(plan.blockPlan.size(), 10);
        for (int i = 0; i < lim; i++) {
            final BlockPos floor = plan.blockPlan.floor(i); // the search-native floor cell (carried, exact on partials)
            final int dx = floor.getX() - prev.getX();
            final int dy = floor.getY() - prev.getY();
            final int dz = floor.getZ() - prev.getZ();
            sb.append("\n  ").append(i).append(' ')
                    .append(plan.blockPlan.movement(i).getClass().getSimpleName())
                    .append(" d(").append(dx).append(',').append(dy).append(',').append(dz).append(") ->")
                    .append(compactPos(floor));
            final StepEdits e = plan.blockPlan.edits(i);
            if (e != null && (e.breakCount() > 0 || e.placeCount() > 0)) {
                sb.append(" [brk=").append(e.breakCount()).append(" plc=").append(e.placeCount()).append(']');
            }
            prev = floor;
        }
        if (plan.blockPlan.size() > lim) {
            sb.append("\n  ... +").append(plan.blockPlan.size() - lim).append(" more");
        }
        OrebitCommon.LOGGER.info(sb.toString());
    }
}
