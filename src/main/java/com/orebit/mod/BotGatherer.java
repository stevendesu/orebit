package com.orebit.mod;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import com.orebit.mod.pathfinding.PathDebugRenderer;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.config.ConfigLoader;
import com.orebit.mod.platform.BlockShapes;
import com.orebit.mod.platform.BotInventory;
import com.orebit.mod.platform.EntityState;
import com.orebit.mod.platform.Worlds;
import com.orebit.mod.worldmodel.hpa.RegionGrid;
import com.orebit.mod.worldmodel.pathing.NavGridView;
import com.orebit.mod.worldmodel.resource.ResourceClasses;
import com.orebit.mod.worldmodel.resource.ResourceQuery;
import com.orebit.mod.worldmodel.resource.ResourceScan;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * The {@code /bot gather} machine, owned by {@link AllyBotEntity} (see {@link BotMining} for the component
 * pattern): the live-scan-primary find→mine→return resource loop. SCAN live-scans loaded sections around the
 * bot nearest-first (the PRIMARY ore source — robust to the coarse, load-populated, sometimes-mis-bucketed
 * resource pyramid); MINE approaches + timed-mines the queued cells; COLLECT chases the actual dropped item;
 * COMPASS walks toward a distant pyramid hint when nothing is loaded nearby, live-scanning en route so it
 * grabs ore it passes; RETURN walks back to the issue cell. Drives movement through
 * {@link BotNavigator#driveToward} and breaks through {@link BotMining}.
 */
final class BotGatherer {

    private final AllyBotEntity bot;

    /** Phases of a {@code /bot gather} run. Stepped one phase per tick by {@link #gatherLoopTick}. */
    private enum GatherPhase { SCAN, MINE, COLLECT, COMPASS, RETURN }

    /** Player mining reach (blocks, eye→block-centre) the bot must be within before it can break a
     *  {@code /bot gather} target cell — otherwise it paths closer first (see {@link #gatherMine}). */
    private static final double MINE_REACH = 4.5;

    private int gatherColumn = -1;            // indexed resource column being gathered (-1 = not gathering)
    private int gatherQuota;                  // target count of PICKED-UP items (owner-ratified, §10)
    private int gathered;                     // items accrued so far (counted on standing-mine ticks)
    private GatherPhase gatherPhase;          // current phase (null = inactive)
    private BlockPos gatherStartPos;          // where /bot gather was issued — the fixed RETURN target (§10)
    private int gatherLastInvTotal;           // inventory item total at the last standing-mine tick (Δ = drops)
    private int scanCursor;                   // index into SCAN_OFFSETS for the throttled nearest-first scan
    private int scanAnchorX, scanAnchorY, scanAnchorZ; // bot cell the current scan sweep is centred on
    private BlockPos compassTarget;           // centre of the pyramid-hinted region walked toward (COMPASS)
    private long compassKey;                  // that region's blacklist key
    /** Target ore cells found by the live scan, nearest-first; drained by MINE. */
    private final ArrayDeque<BlockPos> mineQueue = new ArrayDeque<>();
    /** Pyramid regions the COMPASS reached but found no live ore in — skipped (stale/phantom tally). */
    private final HashSet<Long> gatherBlacklist = new HashSet<>();
    /** Ore cells the driver could not reach this run — skipped by the scan so MINE can't loop on them. */
    private final HashSet<Long> unreachableCells = new HashSet<>();
    /** Candidates accumulated across a (possibly multi-tick) scan sweep, so exposed-vs-buried can be ranked. */
    private final List<BlockPos> scanFound = new ArrayList<>();
    /** MINE: the ore currently being pursued — chosen from {@link #mineQueue} by the two-A* route-cost compare
     *  ({@link #selectMineTarget}), not simply the nearest. Null → re-select on the next MINE tick. */
    private BlockPos mineTarget;
    /** COLLECT: the just-mined cell (now air) — kept to pop it from {@link #mineQueue} on finish. */
    private BlockPos collectCell;
    /** COLLECT: the actual dropped {@link ItemEntity} being chased (found by a one-shot scan around the
     *  mined cell). The drop's LIFECYCLE is the state machine — alive+airborne → wait, alive+grounded →
     *  path to its live position, removed → count-or-move-on. No timer (s52: COLLECT_TIMEOUT deleted). */
    private ItemEntity collectDrop;
    /** COLLECT: last observed stack size of {@link #collectDrop} — what {@link #gathered} accrues when the
     *  pickup lands (exact for the chased drop; junk walked over mid-chase never counts). */
    private int collectDropCount;
    /** MINE: the settled floor cell at the last opportunistic re-target challenge — one challenge per NEW
     *  settled cell (a route milestone), never per tick (s52: replaced the PROXIMITY_INTERVAL poll). */
    private BlockPos lastChallengeAnchor;
    /** Reused mutable cursor for the LOS raycast + exposed-face neighbour reads (no per-check allocation). */
    private final BlockPos.MutableBlockPos scratchPos = new BlockPos.MutableBlockPos();

    /** Drive-arrival tolerance while MINING/COLLECTING: deliberately TIGHTER than the ~1.0 closest a bot can get
     *  to a solid block's centre, so {@code driveToward} never short-circuits to "arrived" and stops the bot a
     *  block of stone short — it keeps tunnelling until the real stop condition (a clear {@link #firstOcclusion}
     *  line in MINE, the drop pickup in COLLECT) is met. Follow/come keep the looser
     *  {@link BotNavigator#ARRIVE_DIST}/{@link BotNavigator#ARRIVE_Y}. */
    private static final double MINE_ARRIVE_DIST = 0.6;
    private static final double MINE_ARRIVE_Y = 0.6;
    /** The 6 face neighbours ({dx,dy,dz}) — for the {@link #hasExposedFace} targeting check. */
    private static final int[][] FACE_OFFSETS =
            {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
    // Live-scan volume around the bot (in sections): horizontal ± chunks, plus a downward-biased vertical
    // band (ore sits below). Swept nearest-first, SCAN_SECTIONS_PER_TICK at a time, stopping at the first ore.
    private static final int SCAN_RADIUS_CHUNKS = 3;
    private static final int SCAN_DOWN_SECTIONS = 6;
    private static final int SCAN_UP_SECTIONS = 2;
    private static final int SCAN_SECTIONS_PER_TICK = 12;
    /** Section offsets {dChunkX, dSectionY, dChunkZ} in the scan volume, sorted nearest-first (built once). */
    private static final int[][] SCAN_OFFSETS;
    static {
        List<int[]> offs = new ArrayList<>();
        for (int dcx = -SCAN_RADIUS_CHUNKS; dcx <= SCAN_RADIUS_CHUNKS; dcx++) {
            for (int dcz = -SCAN_RADIUS_CHUNKS; dcz <= SCAN_RADIUS_CHUNKS; dcz++) {
                for (int dsy = -SCAN_DOWN_SECTIONS; dsy <= SCAN_UP_SECTIONS; dsy++) {
                    offs.add(new int[]{dcx, dsy, dcz});
                }
            }
        }
        offs.sort((a, b) -> Integer.compare(
                a[0] * a[0] + a[1] * a[1] + a[2] * a[2],
                b[0] * b[0] + b[1] * b[1] + b[2] * b[2]));
        SCAN_OFFSETS = offs.toArray(new int[0][]);
    }

    BotGatherer(AllyBotEntity bot) {
        this.bot = bot;
    }

    /**
     * {@code /bot gather <resource> [count]} entry point (the gather half — mode switch/plan reset live on
     * {@link AllyBotEntity#startGather}). Begin the loop for indexed resource {@code column}, targeting
     * {@code quota} PICKED-UP items (§10). Anchors the RETURN target at the bot's current cell and enters
     * {@link GatherPhase#SCAN} — the primary ore source is a LIVE nearest-first scan of the loaded sections
     * around the bot (the resource pyramid is coarse + load-populated + can mis-bucket, so it's used only as
     * the COMPASS heading when nothing is loaded nearby).
     */
    void startGather(int column, int quota) {
        this.gatherColumn = column;
        this.gatherQuota = Math.max(1, quota);
        this.gathered = 0;
        this.gatherStartPos = bot.blockPosition().immutable();
        this.compassTarget = null;
        this.mineQueue.clear();
        this.gatherBlacklist.clear();
        this.unreachableCells.clear();
        bot.navigator().clearNavGaveUp();
        beginScanSweep();
        this.gatherPhase = GatherPhase.SCAN;
    }

    /** One tick of the {@code /bot gather} state machine — dispatch to the current phase. */
    void gatherLoopTick() {
        if (gatherPhase == null) { bot.setMode(AllyBotEntity.Mode.STAY); return; } // defensive: lost phase → stop
        ServerLevel level = (ServerLevel) Worlds.of(bot);
        switch (gatherPhase) {
            case SCAN -> gatherScan(level);
            case MINE -> gatherMine(level);
            case COLLECT -> gatherCollect(level);
            case COMPASS -> gatherCompass(level);
            case RETURN -> gatherReturn(level);
        }
    }

    /** Re-anchor a fresh nearest-first live scan on the bot's current cell. */
    private void beginScanSweep() {
        BlockPos p = bot.blockPosition();
        scanAnchorX = p.getX();
        scanAnchorY = p.getY();
        scanAnchorZ = p.getZ();
        scanCursor = 0;
        scanFound.clear();
        mineTarget = null; // a fresh sweep re-picks the target
    }

    /**
     * SCAN: the primary ore source. Live-scan the loaded sections around the bot nearest-first (throttled,
     * {@link #advanceScan}); ore found → MINE. If the whole nearby volume is swept with nothing → fall back to
     * the pyramid COMPASS for a distant heading.
     */
    private void gatherScan(ServerLevel level) {
        bot.setForward(0.0f); // stand still while scanning
        bot.lookAtPlayer(bot.owner());
        if (advanceScan(level)) {
            gatherLastInvTotal = new BotInventory(bot).totalItemCount();
            bot.navigator().clearPlan();
            enterMine();
            return;
        }
        if (scanCursor >= SCAN_OFFSETS.length) { // nothing loaded nearby → ask the pyramid where to head
            beginCompass(level);
        }
    }

    /**
     * Advance the throttled nearest-first live scan by up to {@link #SCAN_SECTIONS_PER_TICK} sections from
     * {@link #scanCursor}, accumulating this run's exact target cells ({@link ResourceScan#exactCells} — a
     * {@code null} return skips an unloaded chunk) into {@link #scanFound}.
     *
     * <p><b>Prefers the EASY ore.</b> Rather than committing to the nearest ore (which may be buried behind
     * stone — a 9-block dig when a slightly-farther vein is exposed), it keeps sweeping nearest-first until it
     * finds an ore with an {@linkplain #hasExposedFace exposed face} OR the whole volume is swept, and only then
     * commits — ranking {@link #mineQueue} exposed-first, then by distance. So it walks to visible ore before it
     * digs, and only digs the nearest buried ore when nothing nearby is exposed. Returns {@code true} on commit;
     * {@code false} while it still wants to sweep more (or found nothing — then {@link #gatherScan} goes COMPASS).
     */
    private boolean advanceScan(ServerLevel level) {
        final int bcx = scanAnchorX >> 4;
        final int bcz = scanAnchorZ >> 4;
        final int bsy = (scanAnchorY - RegionGrid.of(level).minY()) >> 4;
        int scanned = 0;
        while (scanCursor < SCAN_OFFSETS.length && scanned < SCAN_SECTIONS_PER_TICK) {
            final int[] o = SCAN_OFFSETS[scanCursor++];
            scanned++;
            final int ry = bsy + o[1];
            if (ry < 0) continue; // below the world column
            final List<BlockPos> cells =
                    ResourceScan.exactCells(level, bcx + o[0], ry, bcz + o[2], gatherColumn);
            if (cells == null || cells.isEmpty()) continue;
            for (BlockPos cell : cells) {
                if (!unreachableCells.contains(cell.asLong())) scanFound.add(cell);
            }
        }
        if (scanFound.isEmpty()) return false; // nothing found yet — keep sweeping / (exhausted → COMPASS)
        // Keep sweeping until there is at least one EXPOSED option in the pool (or the volume is exhausted), so
        // {@link #selectMineTarget}'s two-A* has both an exposed and a buried candidate to weigh — otherwise we
        // could commit to digging the nearest buried ore before ever noticing a nearby vein we could walk to.
        boolean anyExposed = false;
        for (BlockPos cell : scanFound) {
            if (hasExposedFace(level, cell)) { anyExposed = true; break; }
        }
        if (!anyExposed && scanCursor < SCAN_OFFSETS.length) return false;
        mineQueue.clear();
        mineQueue.addAll(scanFound); // the candidate POOL — selectMineTarget picks the cheapest-route target
        scanFound.clear();
        return true;
    }

    /**
     * MINE: drain {@link #mineQueue} nearest-first. A cell out of reach is approached with the two-tier driver
     * (targeting the ore cell itself, so it tunnels right up to it — fixing the old "stop a few blocks short");
     * once within {@link #MINE_REACH} the bot stands, digs the sight line open if a block still occludes the
     * ore (the path goal tolerance ends plans adjacent, with no break edit for that last block), and
     * timed-mines the target via {@link BotMining} (real drops).
     * Quota counts PICKED-UP items: the inventory delta is accrued ONLY on standing-mine ticks (the sole fresh
     * drops there are the target's), so path-clearing on the approach never counts. An unreachable cell is
     * blacklisted + skipped. Queue drained → re-SCAN around the (now-moved) bot for the next nearest ore — this
     * is what makes it grab ore it walked past. Quota met → RETURN.
     */
    private void gatherMine(ServerLevel level) {
        // (s52: the region-crossing re-SCAN gate is gone. Re-selection is MILESTONE-driven — target mined /
        // unreachable / queue drained, all below — and "found something better en route" is the settle-driven
        // challenge below. The old gate re-keyed on a live positional bucket (regionOf, y>>4), which tripped
        // at every jump apex near a vertical region boundary and needed grounded()/mining guards to paper
        // over its own livelock — the same class of bug settledFloor exists to prevent in the driver.)
        //
        // TARGET-BECAME-AIR is the "break completed" milestone and MUST be consumed BEFORE re-selection:
        // the old order let selectMineTarget re-point mineTarget at the next ore first, which made the
        // beginCollect branch below provably unreachable — COLLECT was dead code, so no drop was ever
        // chased and the quota never advanced (the mined-60-ore-for-a-quota-of-3 bug). The breaks land in
        // mining.tick AFTER this state machine each tick, so the first observer of the air cell is HERE.
        if (mineTarget != null && level.getBlockState(mineTarget).isAir()) {
            beginCollect(mineTarget);
            return;
        }
        // (Re)choose the target when we have none, or the current one was proven unreachable.
        if (mineTarget == null || unreachableCells.contains(mineTarget.asLong())) {
            selectMineTarget(level);
        }
        if (mineTarget == null) { // pool worked out → re-scan around the (now-moved) bot for the next veins
            bot.navigator().clearPlan();
            beginScanSweep();
            gatherPhase = GatherPhase.SCAN;
            return;
        }
        // Opportunistic re-target, EVENT-driven (s52 — replaced the 15-tick poll + 14-block magic radius):
        // re-weigh the committed target only when the bot has SETTLED onto a NEW floor cell (a route
        // milestone — real movement, not wall clock; at most one challenge pair per completed waypoint) and
        // a pooled vein is strictly closer than the committed target (the computed prefilter inside
        // maybeChallengeTarget). Switch only if A* PROVES the challenger cheaper.
        final BlockPos settled = bot.navigator().settledFloor();
        if (!bot.mining().busy() && settled != null && !settled.equals(lastChallengeAnchor)) {
            lastChallengeAnchor = settled;
            maybeChallengeTarget(level);
        }
        final BlockPos cell = mineTarget;
        if (Debug.ENABLED) PathDebugRenderer.highlightCell(level, cell); // show the chosen ore (/bot debug)
        // Break ONLY when both within reach AND with line-of-sight — the LOS gate is what stops the bot mining
        // ore through 3 blocks of stone (raw reach alone did). Out of reach keeps driving (tunnelling) closer;
        // within reach but OCCLUDED digs the sight line open itself (see below) — the path tiers can't do it:
        // their ±1/±2 goal tolerance ends the plan adjacent to the vein with NO break edit for the last block,
        // so re-driving from here just walks the bot into the wall forever (the stand-at-the-wall bug).
        if (withinReach(cell)) {
            bot.setForward(0.0f);
            // (The break-completed → beginCollect handoff happens at the TOP of this method, before
            // re-selection — the ordering that made COLLECT reachable at all. s52.)
            final BlockPos occluder = firstOcclusion(level, cell);
            if (occluder == null) {
                // Correct-tool gate: iron & friends require a stone+ pickaxe for the block to DROP
                // anything — a bare-hand break silently yields nothing, so the quota can never advance
                // (the mined-forever-got-nothing failure). Refuse honestly instead of grinding.
                if (!new BotInventory(bot).hasCorrectTool(level.getBlockState(cell))) {
                    bot.chat("I can't harvest " + ResourceClasses.nameOfColumn(gatherColumn)
                            + " without the right tool.");
                    bot.setMode(AllyBotEntity.Mode.STAY);
                    return;
                }
                bot.mining().request(cell); // clear line → BotMining faces + times the break + drops
            } else {
                // Dig the eye→ore ray open, first blocking block per tick — each break strictly shortens the
                // occlusion, so this terminates with a clear line and falls into the branch above.
                gatherLastInvTotal = new BotInventory(bot).totalItemCount(); // occluder drops never count
                final BlockState s = level.getBlockState(occluder);
                if (ConfigLoader.config().mayBreak(s, s.getDestroySpeed(level, occluder))) {
                    bot.mining().request(occluder);
                } else { // protected/unbreakable in the sight line → not minable from here; drop the vein
                    unreachableCells.add(cell.asLong());
                    mineQueue.remove(cell);
                    mineTarget = null;
                    bot.navigator().clearPlan();
                }
            }
        } else {
            gatherLastInvTotal = new BotInventory(bot).totalItemCount(); // exclude approach pickups from Δ
            // Tight MINE arrival so it tunnels ALL the way to a line-of-sight cell, not 3 blocks short.
            boolean arrived = bot.navigator().driveToward(
                    cell.getX() + 0.5, cell.getY() + 0.5, cell.getZ() + 0.5, cell,
                    MINE_ARRIVE_DIST, MINE_ARRIVE_Y);
            if (!arrived && bot.navigator().navGaveUp()) {
                // can't reach it — blacklist + drop from the pool, re-select next tick
                unreachableCells.add(cell.asLong());
                mineQueue.remove(cell);
                mineTarget = null;
                bot.navigator().clearNavGaveUp();
                bot.navigator().clearPlan();
            }
        }
    }

    /**
     * Choose the next ore to mine (sets {@link #mineTarget}): compare the nearest EXPOSED candidate and the
     * nearest BURIED candidate by a REAL block-A* route cost (which prices the digging), and keep the cheaper —
     * so the bot walks to a visible vein rather than tunnelling past it, yet still digs a near buried vein when
     * that is genuinely cheaper. Because the cost is a real search, A* distinguishes "buried behind stone" (an
     * expensive dig) from "exposed in a cavern we can drop into" (a cheap fall) far better than any Y-heuristic.
     * Only TWO searches (owner-ratified — one-per-ore is untenable). {@code null} → the pool is worked out.
     */
    private void selectMineTarget(ServerLevel level) {
        mineQueue.removeIf(c -> level.getBlockState(c).isAir() || unreachableCells.contains(c.asLong()));
        if (mineQueue.isEmpty()) { mineTarget = null; return; }
        final double bx = bot.getX(), by = bot.getY(), bz = bot.getZ();
        BlockPos nearestExposed = null, nearestBuried = null;
        double eD = Double.MAX_VALUE, bD = Double.MAX_VALUE;
        for (BlockPos c : mineQueue) {
            final double d = distSq(c, bx, by, bz);
            if (hasExposedFace(level, c)) { if (d < eD) { eD = d; nearestExposed = c; } }
            else                          { if (d < bD) { bD = d; nearestBuried = c; } }
        }
        if (nearestExposed == null) { mineTarget = nearestBuried; return; }
        if (nearestBuried == null)  { mineTarget = nearestExposed; return; }
        // Two real A* route costs (dig priced in); cheaper wins, tie → exposed (walking beats tunnelling). A null
        // OR PARTIAL plan scores +inf: a budget-hit search returns a best-effort PARTIAL whose cost() is only
        // the TRUNCATED prefix — near zero — which let a flooding search to a buried vein "beat" a genuinely
        // FOUND route to an exposed one (the diagnosed wrong-commit). Unproven ≠ cheap.
        final BotCaps caps = bot.caps();
        final NavGridView grid = new NavGridView(level);
        final BlockPos start = bot.blockPosition().below();
        final BlockPathPlan pe = BlockPathfinder.findPath(grid, start, nearestExposed, caps);
        final boolean pePartial = BlockPathfinder.lastWasPartial();
        final BlockPathPlan pb = BlockPathfinder.findPath(grid, start, nearestBuried, caps);
        final boolean pbPartial = BlockPathfinder.lastWasPartial();
        final float ce = (pe != null && !pePartial) ? pe.cost() : Float.MAX_VALUE;
        final float cb = (pb != null && !pbPartial) ? pb.cost() : Float.MAX_VALUE;
        mineTarget = (ce <= cb) ? nearestExposed : nearestBuried;
        lastChallengeAnchor = bot.navigator().settledFloor(); // fresh target — challenge only after the NEXT settle milestone
    }

    /**
     * Opportunistic re-target: if a pooled vein is now <b>strictly closer than the committed target</b> (the
     * computed prefilter — no magic radius; the challenge exists for "walking right past a vein", and a
     * challenger farther than the target is what the up-front selection already weighed), run a real A* to it
     * AND to the currently-committed {@link #mineTarget} from HERE, and switch only if the newcomer is
     * <b>proven strictly cheaper</b> (sticky — we do not abandon the committed vein on a hunch). Fired by the
     * caller once per NEW settled floor cell (a route milestone), so the two searches are bounded by real
     * movement, never by wall clock (s52).
     */
    private void maybeChallengeTarget(ServerLevel level) {
        if (mineTarget == null) return;
        final double bx = bot.getX(), by = bot.getY(), bz = bot.getZ();
        BlockPos challenger = null;
        double best = distSq(mineTarget, bx, by, bz); // beat the committed target's distance or don't bother
        for (BlockPos c : mineQueue) {
            if (c.equals(mineTarget)) continue;
            if (level.getBlockState(c).isAir() || unreachableCells.contains(c.asLong())) continue;
            final double d = distSq(c, bx, by, bz);
            if (d < best) { best = d; challenger = c; } // the nearest newly-close vein is the best challenger
        }
        if (challenger == null) return; // nothing close enough to reconsider — keep going
        final BotCaps caps = bot.caps();
        final NavGridView grid = new NavGridView(level);
        final BlockPos start = bot.blockPosition().below();
        final BlockPathPlan pc = BlockPathfinder.findPath(grid, start, challenger, caps);
        if (pc == null || BlockPathfinder.lastWasPartial()) return; // unreachable/unPROVEN → keep committed
        final BlockPathPlan pt = BlockPathfinder.findPath(grid, start, mineTarget, caps);
        // A PARTIAL committed cost is the truncated prefix (near zero) — score it +inf so a genuinely FOUND
        // challenger can rescue a commit whose search floods (same honesty rule as selectMineTarget).
        final float committed = (pt != null && !BlockPathfinder.lastWasPartial()) ? pt.cost() : Float.MAX_VALUE;
        if (pc.cost() < committed) mineTarget = challenger; // proven cheaper → take it
    }

    /**
     * Enter COLLECT: the target block just broke. Find its ACTUAL {@link ItemEntity} with one scan around
     * the mined cell (drops spawn with a random offset/velocity and then fall/roll — the cell coordinate
     * alone chases a hole, not an item). No drop found = nothing ever dropped (someone else took it, or a
     * drop-less break slipped past the tool gate) → skip COLLECT entirely, no accrual.
     */
    private void beginCollect(BlockPos cell) {
        collectCell = cell;
        collectDrop = null;
        collectDropCount = 0;
        ItemEntity nearest = null;
        double bestD = Double.MAX_VALUE;
        final ServerLevel level = (ServerLevel) Worlds.of(bot);
        for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class,
                new AABB(cell).inflate(2.0), ItemEntity::isAlive)) {
            final double d = e.distanceToSqr(cell.getX() + 0.5, cell.getY() + 0.5, cell.getZ() + 0.5);
            if (d < bestD) { bestD = d; nearest = e; }
        }
        if (nearest == null) { // no drop exists — nothing to chase, nothing to count
            finishCollect();
            return;
        }
        collectDrop = nearest;
        collectDropCount = nearest.getItem().getCount();
        gatherLastInvTotal = new BotInventory(bot).totalItemCount(); // baseline before the drop lands in us
        bot.navigator().clearPlan();
        gatherPhase = GatherPhase.COLLECT;
    }

    /**
     * COLLECT: chase the tracked {@link #collectDrop} by its LIFECYCLE — pure state, no timer (s52:
     * COLLECT_TIMEOUT deleted per the no-arbitrary-timers rule):
     * <ul>
     *   <li><b>Removed + inventory rose</b> → picked up: accrue the drop's stack size (exact — junk items
     *       walked over mid-chase never count) and move on.</li>
     *   <li><b>Removed + no rise</b> → despawned/burned/someone else got it → move on, no accrual.</li>
     *   <li><b>Airborne</b> (still falling/bouncing) → stand and wait; falling ends by physics.</li>
     *   <li><b>Grounded/floating</b> → path to the item's LIVE position (it rolls, falls into shafts,
     *       drifts in water — the mined cell is history). Unreachable (nav gave up) → abandon.</li>
     * </ul>
     */
    private void gatherCollect(ServerLevel level) {
        final ItemEntity drop = collectDrop;
        if (drop == null) { // defensive: lost the ref → nothing to chase
            finishCollect();
            return;
        }
        if (drop.isRemoved()) {
            final int now = new BotInventory(bot).totalItemCount();
            if (now > gatherLastInvTotal) { // the pickup landed in us
                gathered += Math.max(1, collectDropCount);
                gatherLastInvTotal = now;
            }
            finishCollect();
            return;
        }
        collectDropCount = drop.getItem().getCount(); // track merges/splits while it lies there
        if (!EntityState.onGround(drop) && !drop.isInWater()) {
            bot.setForward(0.0f); // mid-air: it lands within ticks; chasing a parabola is pointless
            return;
        }
        if (Debug.ENABLED) PathDebugRenderer.highlightCell(level, drop.blockPosition());
        // Goal tolerance 0/0 (s52 stare-at-the-drop fix): the planner must land ON the item's cell, not
        // adjacent — the block tier's default ±1/±2 "reached" left plans ending a diagonal away, the driver
        // declared COMPLETE, and the item sat just outside the vanilla pickup box while the bot stared.
        bot.navigator().driveToward(drop.getX(), drop.getY(), drop.getZ(), drop.blockPosition().below(),
                MINE_ARRIVE_DIST, MINE_ARRIVE_Y, 0, 0);
        if (bot.navigator().navGaveUp()) { // provably unreachable (sealed pit, lava pocket) — abandon the drop honestly
            bot.navigator().clearNavGaveUp();
            bot.navigator().clearPlan();
            finishCollect();
        }
    }

    /** Leave COLLECT: drop the mined cell from the queue, check the quota, and resume MINE (or RETURN). */
    private void finishCollect() {
        if (collectCell != null) mineQueue.remove(collectCell); // done with it (it's air now anyway)
        collectCell = null;
        collectDrop = null;
        mineTarget = null; // pick the next ore via a fresh two-A* compare
        bot.navigator().clearPlan();
        if (gathered >= gatherQuota) {
            gatherPhase = GatherPhase.RETURN;
            bot.chat("got " + gathered + " " + ResourceClasses.nameOfColumn(gatherColumn) + " — heading back.");
            return;
        }
        enterMine();
    }

    /** Transition into MINE. Re-selection from here on is milestone-driven (target mined / unreachable /
     *  queue drained) plus the settle-driven challenge — no positional re-select gate (s52). */
    private void enterMine() {
        gatherPhase = GatherPhase.MINE;
    }

    /**
     * COMPASS: nothing loaded nearby, so ask the pyramid for the nearest non-blacklisted region holding the
     * resource and start walking toward it — while still live-scanning (so ore that loads en route diverts us
     * straight to MINE; we don't stride past visible ore). No pyramid hint → report + STAY.
     */
    private void beginCompass(ServerLevel level) {
        List<ResourceQuery.ResourceHit> hits =
                ResourceQuery.find(level, gatherColumn, bot.blockPosition(), 1, 8);
        for (ResourceQuery.ResourceHit h : hits) {
            final long key = regionKey(h.rx(), h.ry(), h.rz());
            if (gatherBlacklist.contains(key)) continue;
            compassTarget = h.center();
            compassKey = key;
            bot.navigator().clearNavGaveUp();
            bot.navigator().clearPlan();
            beginScanSweep();
            gatherPhase = GatherPhase.COMPASS;
            return;
        }
        bot.chat("I don't see any " + ResourceClasses.nameOfColumn(gatherColumn) + " nearby.");
        bot.setMode(AllyBotEntity.Mode.STAY);
    }

    /** COMPASS drive: head for the pyramid hint, live-scanning as we go (re-anchoring the scan as the bot
     *  travels). Ore found → MINE. Reached (or can't reach) the hint with the local scan still empty → the
     *  tally was stale; blacklist that region and get the next hint. */
    private void gatherCompass(ServerLevel level) {
        final BlockPos p = bot.blockPosition();
        if (Math.abs(p.getX() - scanAnchorX) + Math.abs(p.getY() - scanAnchorY)
                + Math.abs(p.getZ() - scanAnchorZ) >= 12) {
            beginScanSweep(); // re-anchor the scan on the bot as it travels
        }
        if (advanceScan(level)) {
            gatherLastInvTotal = new BotInventory(bot).totalItemCount();
            bot.navigator().clearPlan();
            enterMine();
            return;
        }
        final BlockPos c = compassTarget;
        boolean arrived = bot.navigator().driveToward(c.getX() + 0.5, c.getY(), c.getZ() + 0.5, c.below());
        if (arrived || bot.navigator().navGaveUp()) {
            gatherBlacklist.add(compassKey);
            bot.navigator().clearNavGaveUp();
            bot.navigator().clearPlan();
            beginCompass(level);
        }
    }

    /** RETURN: drive back to where {@code /bot gather} was issued, then STAY. */
    private void gatherReturn(ServerLevel level) {
        BlockPos s = gatherStartPos;
        boolean arrived = bot.navigator().driveToward(s.getX() + 0.5, s.getY(), s.getZ() + 0.5, s.below());
        if (arrived) {
            bot.chat("back with " + gathered + " " + ResourceClasses.nameOfColumn(gatherColumn) + ".");
            bot.setMode(AllyBotEntity.Mode.STAY);
        } else if (bot.navigator().navGaveUp()) {
            bot.chat("I got " + gathered + " " + ResourceClasses.nameOfColumn(gatherColumn)
                    + " but can't find my way back.");
            bot.setMode(AllyBotEntity.Mode.STAY);
        }
    }

    /** True when {@code cell}'s centre is within a player's mining reach ({@link #MINE_REACH}) of the bot's eyes. */
    private boolean withinReach(BlockPos cell) {
        double dx = cell.getX() + 0.5 - bot.getX();
        double dy = cell.getY() + 0.5 - bot.getEyeY();
        double dz = cell.getZ() + 0.5 - bot.getZ();
        return dx * dx + dy * dy + dz * dz <= MINE_REACH * MINE_REACH;
    }

    /**
     * The mining line-of-sight gate, in discriminating form: the FIRST full solid block interrupting the
     * straight line from the bot's eyes to {@code target}'s centre (the target itself is excluded), or
     * {@code null} when the line is clear. A non-null result is both the "don't break ore <i>through</i> a
     * wall" refusal — {@link #withinReach} alone (raw distance) let it mine 3 blocks of stone away — and the
     * next block {@link #gatherMine} must dig to OPEN that line (the within-reach-but-occluded case the path
     * goal tolerance leaves behind). A stepped sample (~4/block) is plenty at reach distance; the scan itself
     * is allocation-free (a single reused {@link BlockPos.MutableBlockPos}), with one immutable copy made only
     * on a hit; solidity uses the version-portable {@link BlockShapes#isSolidRender} seam, not a fragile
     * direct MC raytrace.
     */
    private BlockPos firstOcclusion(net.minecraft.world.level.Level level, BlockPos target) {
        final double ox = bot.getX(), oy = bot.getEyeY(), oz = bot.getZ();
        final double dx = target.getX() + 0.5 - ox, dy = target.getY() + 0.5 - oy, dz = target.getZ() + 0.5 - oz;
        final int steps = Math.max(1, (int) Math.ceil(Math.sqrt(dx * dx + dy * dy + dz * dz) * 4.0));
        for (int i = 1; i < steps; i++) {
            final double t = (double) i / steps;
            final int bx = (int) Math.floor(ox + dx * t);
            final int by = (int) Math.floor(oy + dy * t);
            final int bz = (int) Math.floor(oz + dz * t);
            if (bx == target.getX() && by == target.getY() && bz == target.getZ()) continue; // the target itself
            scratchPos.set(bx, by, bz);
            final BlockState s = level.getBlockState(scratchPos);
            if (!s.isAir() && BlockShapes.isSolidRender(s, level, scratchPos)) return scratchPos.immutable();
        }
        return null;
    }

    /**
     * A cheap, position-independent "easy target" test for ore SELECTION: true iff any of {@code cell}'s six
     * face neighbours is not a full solid block (air / water / a non-cube), i.e. the ore can be reached and
     * mined without tunnelling to it. Preferred over a per-bot line-of-sight for targeting because it does not
     * change as the bot moves (no flip-flop) and is 1-6 block reads with an early out.
     */
    private boolean hasExposedFace(net.minecraft.world.level.Level level, BlockPos cell) {
        for (int[] o : FACE_OFFSETS) {
            scratchPos.set(cell.getX() + o[0], cell.getY() + o[1], cell.getZ() + o[2]);
            final BlockState s = level.getBlockState(scratchPos);
            if (s.isAir() || !BlockShapes.isSolidRender(s, level, scratchPos)) return true;
        }
        return false;
    }

    private static double distSq(BlockPos p, double x, double y, double z) {
        double dx = p.getX() + 0.5 - x, dy = p.getY() + 0.5 - y, dz = p.getZ() + 0.5 - z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Pack a level-0 region's coords into a blacklist key (21/21/22 bits — ample for any world extent). */
    private static long regionKey(int rx, int ry, int rz) {
        return ((long) (rx & 0x1FFFFF) << 43) | ((long) (rz & 0x1FFFFF) << 22) | (ry & 0x3FFFFF);
    }
}
