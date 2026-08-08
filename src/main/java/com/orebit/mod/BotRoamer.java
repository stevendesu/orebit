package com.orebit.mod;

import java.util.Random;

import com.orebit.mod.pathfinding.blockpathfinder.BlockPathPlan;
import com.orebit.mod.pathfinding.blockpathfinder.BlockPathfinder;
import com.orebit.mod.platform.Worlds;
import com.orebit.mod.worldmodel.navblock.NavBlock;
import com.orebit.mod.worldmodel.pathing.NavGridUpdater;
import com.orebit.mod.worldmodel.pathing.NavGridView;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * The {@code /bot roam} machine, owned by {@link AllyBotEntity} (the component pattern of {@link BotMining} /
 * {@link BotGatherer}): an open-ended wander that keeps picking a fresh destination and walking to it, biased
 * to carry the bot AWAY from where roaming started.
 *
 * <h2>Target selection — a best-of-N tournament, then a REACHABILITY PROBE</h2>
 * Each leg samples {@link #CANDIDATES} cells in independent random compass directions at an independent random
 * leg length around the bot's <b>current</b> cell, ranks them by distance from the roam origin (that is the
 * whole "prefer targets farther from the starting position" rule), and then <b>proves</b> the best one
 * reachable with a real block-A* before committing to it. Ranking as a tournament over a local neighbourhood
 * rather than as a biased radial draw around the origin is deliberate: a draw around the origin picks
 * north-400 then south-380 and marches the bot back and forth ACROSS its own start, whereas ranking a local
 * neighbourhood by origin-distance produces an outward random walk.
 *
 * <h2>Why the probe exists (the floating-island freeze, 2026-08-08)</h2>
 * A bot on a small floating island in a superflat world has, with {@link
 * com.orebit.mod.pathfinding.blockpathfinder.BotCaps#mayFall} off, <b>no route to anything off the island</b> —
 * every cell the tournament can see is on the ground below, and the only way down is the drop roam refuses.
 * The picker used to commit to such a cell regardless, and the two-tier driver then spent its whole repair
 * budget discovering, one blacklisted region crossing at a time, what a single block search proves instantly:
 * the goal is unreachable. On a superflat world that cycle is slow enough (a region-tier flood per iteration)
 * to look exactly like a frozen bot. The fix is not recovery machinery around a bad choice — it is to stop
 * making the bad choice. {@code RoamIslandReachabilityTest} pins both halves of what makes this affordable:
 * the refusal costs a handful of expansions (a boxed-in bot's open set is tiny), while the expensive
 * open-terrain case succeeds on the first probe.
 *
 * <p>Probes are spent <b>one per tick</b>, so a roam tick never costs more than the one block search the bot
 * would have paid for a replan anyway — no batch of searches ever lands on a single tick.
 *
 * <h2>When nothing is reachable: shrink the LEG, not the radius</h2>
 * If no sampled candidate proves reachable, the sampling range halves ({@link #MAX_SHRINK} times, down to
 * {@link #LEG_FLOOR} blocks) and a fresh batch is drawn. This is what rescues the island bot: at a 3–8 block
 * leg the candidates land on the island itself, which IS reachable, so it paces its island instead of
 * freezing. Shrinking the roam RADIUS — the bound from the origin — would not have helped at all, because the
 * problem was never how far the target was from the origin; it was that the target was on the other side of a
 * drop. Each completed leg decays the shrink by one level, so the leg length self-tunes to whatever the
 * terrain actually supports and re-expands as soon as the terrain allows.
 *
 * <p>The LAST batch is not a random draw but a deterministic 8-way compass sweep at {@link #LEG_FLOOR}, so
 * "nothing is reachable" is a verdict about the bot's immediate ring rather than eight unlucky dice — see
 * {@link #sampleCandidates}. Refuse all of those and the bot is genuinely stuck for its capabilities: it says
 * so, once, and PARKS, instead of burning a search per tick re-proving it. Parking is released by STATE, not
 * by a clock — the level's nav-edit epoch advancing (someone bridged off the island) or the bot ending up
 * somewhere else. There is deliberately no retry timer anywhere in this class.
 *
 * <h2>No falling</h2>
 * Roam is the one mode that plans with {@code BotCaps.mayFall} OFF ({@code AllyBotEntity.caps()} derives it).
 * The block tier emits no {@code Fall} candidate at all, so no route it returns steps off a ledge — the bot
 * will not walk off a cliff or the rim of a floating island, at ANY drop depth. Every other movement is
 * untouched: it still jumps gaps (Parkour), swims, climbs, bridges, and steps down one block.
 *
 * <h2>State, not timers</h2>
 * A committed leg ends on one of exactly two OBSERVED conditions — the driver reports arrival, or the region
 * tier reports it exhausted its options ({@link BotNavigator#navGaveUp}). No attempt clock, no give-up
 * countdown, no recovery branch.
 */
final class BotRoamer {

    private final AllyBotEntity bot;

    /** Shortest leg (blocks) at full range. Comfortably past the driver's arrival tolerance, so an unshrunk
     *  leg is always real travel. */
    private static final int LEG_MIN = 24;

    /** Longest leg (blocks) at full range. Kept inside the chunk radius the bot itself keeps loaded, so
     *  {@link #resolveFloor} answers from built nav data rather than failing at the frontier. */
    private static final int LEG_MAX = 64;

    /** Shortest leg the shrink will ever draw. Below this "roaming" stops meaning anything — the bot would be
     *  shuffling within its own arrival tolerance. */
    private static final int LEG_FLOOR = 3;

    /** Halvings the leg range may take before the bot declares itself stuck. Three gets {@link #LEG_MIN}/
     *  {@link #LEG_MAX} from 24/64 down to 3/8 — small enough to find footing on an 8x8 island. */
    private static final int MAX_SHRINK = 3;

    /** Candidate headings sampled per leg. The best-ranked is probed first, so this is also the worst-case
     *  number of ticks a fruitless leg costs. 8 keeps a visible outward pull while leaving the path
     *  meandering enough to look like exploring. */
    private static final int CANDIDATES = 8;

    /** How far up/down from the bot's own feet level {@link #resolveFloor} looks for a destination column's
     *  floor. Wide enough for ordinary terrain relief over a full-range leg. */
    private static final int VERTICAL_SEARCH = 24;

    /** Sort-key offset that ranks every out-of-radius candidate below every in-radius one (see
     *  {@link #sampleCandidates}). Larger than any squared distance the radius bound admits. */
    private static final double OUT_OF_BOUNDS_KEY = Double.MAX_VALUE / 4.0;

    /** Where {@code /bot roam} was issued — the anchor every candidate is ranked against. Null = not roaming. */
    private BlockPos roamOrigin;

    /** Max blocks (horizontal) from {@link #roamOrigin} the bot may roam to (the command's {@code radius}
     *  argument; the default and accepted range live on {@code commands.RoamCommand}). */
    private int roamRadius;

    /** The current leg's destination FLOOR cell — set only once a probe has PROVEN it reachable. */
    private BlockPos roamTarget;

    /** Current leg-range halvings, 0..{@link #MAX_SHRINK}. Raised when a whole batch proves unreachable,
     *  decayed by one on each completed leg. */
    private int shrink;

    /** The current batch, ranked best-first, and how far through it {@link #advanceTargetSearch} has probed.
     *  {@code probeIndex >= candidateCount} means "draw a fresh batch". */
    private final BlockPos[] candidates = new BlockPos[CANDIDATES];
    private final double[] candidateKeys = new double[CANDIDATES];
    private int candidateCount;
    private int probeIndex;

    /** Set once the shrink is exhausted and nothing is reachable: stop spending a search per tick until the
     *  world or the bot's position actually changes. */
    private boolean parked;
    /** The {@link NavGridUpdater#editEpoch} and bot cell at park time — the two state changes that release it. */
    private int parkedEpoch;
    private BlockPos parkedCell;

    /** Per-bot heading/length source. Plain {@link Random} rather than the entity's RNG accessor: {@code
     *  Entity.getRandom()}'s return type moved from {@code java.util.Random} to {@code RandomSource} inside
     *  the supported MC range, and a wander's heading is not worth an overlay seam. */
    private final Random random = new Random();

    BotRoamer(AllyBotEntity bot) {
        this.bot = bot;
    }

    /**
     * {@code /bot roam [radius]} — anchor the wander at the bot's current cell and start looking for a first
     * leg. Nothing is chosen here: the first candidate batch is drawn and probed on the next tick, from the
     * same nav data and through the same probe every later leg uses.
     */
    void startRoam(int radius) {
        this.roamOrigin = bot.blockPosition().immutable();
        this.roamRadius = radius;
        this.roamTarget = null;
        this.shrink = 0;
        this.candidateCount = 0;
        this.probeIndex = 0;
        this.parked = false;
        this.parkedCell = null;
    }

    /**
     * One roam tick: look for a leg if we have none, otherwise walk the one we have — and start looking again
     * the moment the driver says this leg is finished. A leg ends on arrival, or on {@link
     * BotNavigator#navGaveUp} (the route was proven reachable when we committed, so a give-up here means the
     * terrain changed underneath us — treated as a failed leg, which feeds the shrink).
     */
    void roamLoopTick() {
        if (roamOrigin == null) return; // defensive: mode dispatch only reaches here while roaming

        final ServerLevel level = (ServerLevel) Worlds.of(bot);

        if (roamTarget == null) {
            if (!advanceTargetSearch(level)) {
                bot.navigator().clearPlan(); // still looking (or parked) — stand still, don't hold a stale plan
                return;
            }
        }

        final BlockPos t = roamTarget;
        final boolean arrived = bot.navigator().driveToward(
                t.getX() + 0.5, t.getY() + 1, t.getZ() + 0.5, t);
        if (arrived) {
            endLeg();
            if (shrink > 0) shrink--; // a completed leg earns back one level of range (see the class doc)
        } else if (bot.navigator().navGaveUp()) {
            // We PROVED this reachable before committing, so a give-up means the world moved. Count it as a
            // failed leg so a genuinely closing-in area still converges on a shorter leg.
            bot.navigator().clearNavGaveUp();
            endLeg();
            if (shrink < MAX_SHRINK) shrink++;
        }
    }

    /** Finish the current leg: drop the target and the stale batch so the next tick draws fresh. */
    private void endLeg() {
        bot.navigator().clearNavGaveUp();
        bot.navigator().clearPlan();
        roamTarget = null;
        candidateCount = 0;
        probeIndex = 0;
    }

    /**
     * Spend at most ONE reachability probe and report whether a target is now committed. Draws a fresh
     * candidate batch when the current one is used up; raises the shrink (or parks) when a whole batch proves
     * unreachable. Returns {@code false} for "still looking" — the caller simply stands still and this runs
     * again next tick.
     */
    private boolean advanceTargetSearch(ServerLevel level) {
        if (parked) {
            if (!unparked(level)) return false;
            parked = false;
            shrink = 0; // whatever changed deserves a fresh look at the full range
        }

        if (probeIndex >= candidateCount && !sampleCandidates(level)) {
            // Not one sampled column even resolved to standable ground in built nav data (a bot boxed into a
            // tiny space, or terrain still streaming in). Same remedy as an unreachable batch: look closer.
            return shrinkOrPark(level);
        }

        final BlockPos candidate = candidates[probeIndex++];
        if (probeReachable(level, candidate)) {
            roamTarget = candidate;
            bot.chat("roaming to " + AllyBotEntity.compact(candidate) + " ("
                    + (int) Math.sqrt(horizontalDistSqFromOrigin(candidate)) + "m from where I started).");
            return true;
        }
        if (probeIndex >= candidateCount) {
            return shrinkOrPark(level); // whole batch refused — the terrain here is tighter than we sampled
        }
        return false; // try the next-best candidate on the next tick
    }

    /**
     * A whole batch was unreachable: halve the leg range and try again next tick, or — if the range is
     * already at the floor — admit the bot is stuck for its capabilities, say so once, and park.
     * Always returns {@code false} (no target was committed).
     */
    private boolean shrinkOrPark(ServerLevel level) {
        candidateCount = 0;
        probeIndex = 0;
        if (shrink < MAX_SHRINK) {
            shrink++;
            return false;
        }
        park(level);
        return false;
    }

    /**
     * Stop probing and tell the owner. This is the honest end state of a bot that cannot reach anything: on a
     * small floating island with no way down that it is willing to take, there is nothing to retry until the
     * world changes, and re-proving it every tick would just burn a search forever.
     */
    private void park(ServerLevel level) {
        parked = true;
        parkedEpoch = NavGridUpdater.editEpoch(level);
        parkedCell = bot.blockPosition().immutable();
        bot.chat("I can't reach anywhere from here without dropping down, so I'm staying put. "
                + "Give me a way across and I'll carry on exploring.");
        OrebitCommon.LOGGER.info("[Orebit] roam PARKED at {} — no reachable target at any leg length "
                + "(mayFall=false); waiting on a nav-edit or a position change.",
                AllyBotEntity.compact(parkedCell));
    }

    /** Whether the park should be released: the level's nav data changed (someone built a way out) or the bot
     *  is no longer where it parked (teleported, pushed, carried). Both are STATE, not elapsed time. */
    private boolean unparked(ServerLevel level) {
        return NavGridUpdater.editEpoch(level) != parkedEpoch
                || !bot.blockPosition().equals(parkedCell);
    }

    /**
     * Draw a fresh candidate batch into {@link #candidates}, ranked best-first, and report whether any
     * survived. A candidate survives if its column resolves to a standable floor with headroom in ALREADY-BUILT
     * nav data; ranking is by horizontal distance from {@link #roamOrigin}, farthest first, with every
     * out-of-radius candidate ranked below every in-radius one and ordered nearest-to-origin first (so a bot
     * sitting on the rim is turned back inward rather than left with nothing to pick).
     *
     * <p>Vertical distance is deliberately excluded from the ranking: "farther out" for an explorer means
     * farther ACROSS the world, and including {@code dy} would let a bot that walked down a ravine score a
     * cell under its own feet as a great outward move.
     */
    private boolean sampleCandidates(ServerLevel level) {
        // The BACKGROUND view: descriptor probes outside built nav data report AIR instead of falling back to
        // a live getBlockState. That matters here specifically — a candidate column is chosen at random and may
        // sit past the loaded radius, and a live read there would synchronously load (and possibly generate) a
        // chunk from inside the tick just to reject a heading.
        final NavGridView grid = NavGridView.background(level);
        final BlockPos here = bot.blockPosition();
        final double radiusSq = (double) roamRadius * roamRadius;
        final int legMin = Math.max(LEG_FLOOR, LEG_MIN >> shrink);
        final int legMax = Math.max(legMin + 1, LEG_MAX >> shrink);

        // At the FLOOR the batch stops being a lottery and becomes a verdict. Parking says "this bot cannot
        // reach anything", and that must not rest on eight unlucky dice: an 8x8 island samples on-island only
        // ~1 heading in 5 at a 3–8 block leg, so a random floor batch would strand a bot that was fine. So the
        // last batch is a deterministic 8-way compass sweep at exactly LEG_FLOOR — complete coverage of the
        // bot's immediate ring. Every one of those refused genuinely means there is nowhere to step to, and
        // re-drawing would return the same answer, which is what makes parking on a single batch honest.
        final boolean floorSweep = shrink >= MAX_SHRINK;

        candidateCount = 0;
        probeIndex = 0;
        for (int i = 0; i < CANDIDATES; i++) {
            final double heading = floorSweep
                    ? i * ((Math.PI * 2.0) / CANDIDATES)
                    : random.nextDouble() * (Math.PI * 2.0);
            final double leg = floorSweep
                    ? LEG_FLOOR
                    : legMin + random.nextDouble() * (legMax - legMin);
            final int cx = here.getX() + (int) Math.round(Math.cos(heading) * leg);
            final int cz = here.getZ() + (int) Math.round(Math.sin(heading) * leg);

            final BlockPos floor = resolveFloor(grid, cx, here.getY(), cz);
            if (floor == null) continue; // that column has no standable landing in built data — try another

            final double d = horizontalDistSqFromOrigin(floor);
            // Ascending sort key: in-radius ranks by -d (farthest from origin first); out-of-radius is offset
            // beyond every in-radius key and ranks by +d (least-far-out first — the way back).
            insert(floor, d <= radiusSq ? -d : OUT_OF_BOUNDS_KEY + d);
        }
        return candidateCount > 0;
    }

    /** Insertion-sort {@code pos} into the ranked batch by ascending {@code key} (≤ 8 entries, cold path —
     *  no comparator, no boxing, no allocation). */
    private void insert(BlockPos pos, double key) {
        int i = candidateCount++;
        while (i > 0 && candidateKeys[i - 1] > key) {
            candidateKeys[i] = candidateKeys[i - 1];
            candidates[i] = candidates[i - 1];
            i--;
        }
        candidateKeys[i] = key;
        candidates[i] = pos;
    }

    private double horizontalDistSqFromOrigin(BlockPos p) {
        final double dx = p.getX() - roamOrigin.getX();
        final double dz = p.getZ() - roamOrigin.getZ();
        return dx * dx + dz * dz;
    }

    /**
     * Whether a route to {@code floorTarget} actually EXISTS for this bot right now — one block-A* under the
     * bot's live caps (which, while roaming, is the Fall-free gate, so the probe answers the question the real
     * plan will be asked). A {@code null} plan is no route; a PARTIAL is a budget-bounded guess and is
     * deliberately NOT accepted as proof — the same "unproven ≠ cheap" rule {@link BotGatherer} applies when
     * it compares two ore routes.
     *
     * <p>Only the block tier is consulted, which is exactly right for a leg of at most {@link #LEG_MAX} blocks:
     * it reads the built nav grid the bot is standing in, and it is the tier that actually knows about the
     * missing {@code Fall}. The region tier is capability-optimistic about drops, so asking IT would reproduce
     * the very false "yes, reachable" that made the bot commit to the ground in the first place.
     */
    private boolean probeReachable(ServerLevel level, BlockPos floorTarget) {
        final NavGridView grid = new NavGridView(level);
        final BlockPos start = bot.blockPosition().below();
        final BlockPathPlan plan = BlockPathfinder.findPath(grid, start, floorTarget, bot.caps());
        return plan != null && !BlockPathfinder.lastWasPartial();
    }

    /**
     * The destination cell for column {@code (x, z)}: the standable floor NEAREST {@code aroundY} that has a
     * bot-sized opening above it, or {@code null} if the column holds none within {@link #VERTICAL_SEARCH} (or
     * isn't built). Searched outward from {@code aroundY} — alternating down, up, down, up — so a surface bot
     * gets the surface and a bot in a cave gets the cave floor, without either needing to know which it is.
     *
     * <p>The opening test is {@link NavBlock#isPassable}, i.e. genuinely empty, not merely enterable. That
     * makes a submerged cell an invalid ROAM DESTINATION (the bot doesn't pick "the bottom of the lake" as a
     * place to go stand) while leaving the route to a chosen destination completely free to swim — this
     * function gates targets, never movements.
     */
    private static BlockPos resolveFloor(NavGridView grid, int x, int aroundY, int z) {
        for (int step = 0; step <= VERTICAL_SEARCH; step++) {
            // step 0 probes aroundY once; every later step probes below then above, nearest-first.
            for (int sign = -1; sign <= 1; sign += 2) {
                if (step == 0 && sign > 0) continue; // don't probe aroundY twice
                final int y = aroundY + sign * step;
                if (standableWithHeadroom(grid, x, y, z)) {
                    return new BlockPos(x, y - 1, z); // the FLOOR cell under those feet — the planner's goal
                }
            }
        }
        return null;
    }

    /** Whether a bot could stand with its feet at {@code (x,y,z)}: a standable floor below, and the feet +
     *  head cells clear. Every probe is gated on {@link NavGridView#built} first, so an unbuilt column is
     *  rejected as "unknown" rather than being read through the view's AIR default as "open sky". */
    private static boolean standableWithHeadroom(NavGridView grid, int x, int y, int z) {
        if (!grid.built(x, y - 1, z) || !grid.built(x, y, z) || !grid.built(x, y + 1, z)) return false;
        return NavBlock.isStandable(grid.descriptorAt(x, y - 1, z))
                && NavBlock.isPassable(grid.descriptorAt(x, y, z))
                && NavBlock.isPassable(grid.descriptorAt(x, y + 1, z));
    }
}
