package com.orebit.mod;

import java.util.ArrayList;
import java.util.List;

/**
 * Per-<b>journey</b> search-health telemetry for the two-tier pathfinder — a pure-observation accumulator
 * that makes pathological floods, boundary thrash, and roundabout routes VISIBLE independently of whether
 * the bot reaches its goal. A <b>journey</b> runs from a goal being set until it is reached, abandoned
 * (give-up), or changed (a new goal region); {@link BotNavigator} owns one live instance and resets it at
 * each journey start, snapshotting the finished one for {@code /bot stats}.
 *
 * <p><b>Behaviour-neutral.</b> Nothing here feeds back into any search / plan / repair decision — every
 * method only counts. The signals it aggregates already exist per-search / per-plan
 * ({@code BlockPathfinder.lastExpansions()}/{@code lastWasPartial()}/{@code lastWasBudgetHit()},
 * {@code RegionPathfinder.lastWasFlood()}, {@code PathPlan.blockedGeneration}, the region blacklist size);
 * this class merely sums them over a journey so the headless autotest can assert on search health and a
 * live run can grep one line per journey.
 *
 * <p>Not thread-safe: mutated only on the server tick thread (the driver feeds it from
 * {@code onBotMoved}/{@code resultStatus} drains, all on-tick).
 */
public final class NavJourneyStats {

    // ---- per-search aggregates (block tier) ----------------------------------------------------------
    private int searchCount;
    private long totalExpansions;
    private int maxExpansions;
    private int partialCount;   // BlockPathfinder.lastWasPartial()
    private int capHitCount;    // BlockPathfinder.lastWasBudgetHit() — the node/time cap bound the search

    // ---- region tier + driver events -----------------------------------------------------------------
    private int floodCount;             // RegionPathfinder.lastWasFlood() trips observed this journey
    private int replanCount;            // full PathPlan (skeleton) rebuilds — BotNavigator.replan()
    private int repairCount;            // PathPlan.repairBlocked() invocations
    private int crossingsInvalidated;   // region blacklist add()s (delta-accumulated across plan rebuilds)
    private int boundaryRecrossings;    // re-entries into a region the bot already left (thrash signal)

    // ---- route geometry ------------------------------------------------------------------------------
    private double distanceTraveled;    // summed per-tick horizontal movement while pursuing the goal
    private double straightLineDistance; // horizontal |goal - start| captured at journey start

    private String outcome = "in-progress"; // reached / abandoned / superseded / in-progress

    // ---- boundary-recrossing state (journey-scoped; the region-key the skeleton commits over) --------
    private long[] leftRegions = new long[16]; // region keys the bot has entered-and-since-left (dedup)
    private int leftCount;
    private long currentRegionKey;
    private boolean haveRegion;

    /** Begin a fresh journey: zero every counter and seed the straight-line reference. */
    void reset(double straightLineDistance) {
        searchCount = 0;
        totalExpansions = 0L;
        maxExpansions = 0;
        partialCount = 0;
        capHitCount = 0;
        floodCount = 0;
        replanCount = 0;
        repairCount = 0;
        crossingsInvalidated = 0;
        boundaryRecrossings = 0;
        distanceTraveled = 0.0;
        this.straightLineDistance = straightLineDistance;
        outcome = "in-progress";
        leftCount = 0;
        haveRegion = false;
        currentRegionKey = 0L;
    }

    // ---- counter hooks (called from the driver / PathPlan) -------------------------------------------

    /** One completed block-A* search: its node count, whether it returned a best-effort PARTIAL, and
     *  whether the node/time cap bound it (a flood signal independent of the partial). */
    public void onBlockSearch(int expansions, boolean partial, boolean capHit) {
        searchCount++;
        totalExpansions += expansions;
        if (expansions > maxExpansions) maxExpansions = expansions;
        if (partial) partialCount++;
        if (capHit) capHitCount++;
    }

    /** The region tier tripped its cap-safe flood guard on this journey's last region search. */
    public void onRegionFlood() {
        floodCount++;
    }

    /** A full skeleton rebuild fired ({@code BotNavigator.replan}; includes the initial build). */
    void onReplan() {
        replanCount++;
    }

    /** A region-tier online repair was attempted ({@code PathPlan.repairBlocked}). */
    void onRepair() {
        repairCount++;
    }

    /** Add a batch of newly-blacklisted crossings (the driver feeds the positive delta of the region
     *  blacklist size, so a plan rebuild that resets the blacklist to 0 never subtracts). */
    void addCrossingsInvalidated(int delta) {
        if (delta > 0) crossingsInvalidated += delta;
    }

    /** Add this tick's horizontal travel toward the goal. */
    void addDistance(double horizontal) {
        distanceTraveled += horizontal;
    }

    /**
     * Observe the bot's current region key (level the skeleton commits over). The first observation seeds
     * the current region; a change means the bot left {@code currentRegionKey} for {@code key}. Entering a
     * key already recorded as "left" is a boundary re-crossing — the thrash signal.
     */
    void observeRegion(long key) {
        if (!haveRegion) {
            haveRegion = true;
            currentRegionKey = key;
            return;
        }
        if (key == currentRegionKey) return;
        if (leftContains(key)) boundaryRecrossings++;
        leftAdd(currentRegionKey);
        currentRegionKey = key;
    }

    private boolean leftContains(long key) {
        for (int i = 0; i < leftCount; i++) if (leftRegions[i] == key) return true;
        return false;
    }

    private void leftAdd(long key) {
        if (leftContains(key)) return;
        if (leftCount == leftRegions.length) {
            leftRegions = java.util.Arrays.copyOf(leftRegions, leftCount * 2);
        }
        leftRegions[leftCount++] = key;
    }

    void setOutcome(String outcome) {
        this.outcome = outcome;
    }

    // ---- reads ---------------------------------------------------------------------------------------

    int searchCount() { return searchCount; }
    long totalExpansions() { return totalExpansions; }
    int maxExpansions() { return maxExpansions; }
    int partialCount() { return partialCount; }
    int capHitCount() { return capHitCount; }
    int floodCount() { return floodCount; }
    int replanCount() { return replanCount; }
    int repairCount() { return repairCount; }
    int crossingsInvalidated() { return crossingsInvalidated; }
    int boundaryRecrossings() { return boundaryRecrossings; }
    double distanceTraveled() { return distanceTraveled; }
    double straightLineDistance() { return straightLineDistance; }
    String outcome() { return outcome; }

    /** Route efficiency = distance actually traveled / straight-line distance (≥1; a big number = a
     *  roundabout route). Guarded against a zero straight-line (goal == start). */
    double routeEfficiency() {
        return distanceTraveled / Math.max(1.0, straightLineDistance);
    }

    /** Whether this journey recorded any pathfinding work (used to suppress empty-journey log/report noise). */
    boolean hasActivity() {
        return searchCount > 0 || distanceTraveled > 1.0;
    }

    /** Copy the aggregate fields into {@code dst} — the completed-journey snapshot {@code /bot stats}
     *  shows as "last completed" (the boundary-recross scratch state is not copied; only its count). */
    void copyInto(NavJourneyStats dst) {
        dst.searchCount = searchCount;
        dst.totalExpansions = totalExpansions;
        dst.maxExpansions = maxExpansions;
        dst.partialCount = partialCount;
        dst.capHitCount = capHitCount;
        dst.floodCount = floodCount;
        dst.replanCount = replanCount;
        dst.repairCount = repairCount;
        dst.crossingsInvalidated = crossingsInvalidated;
        dst.boundaryRecrossings = boundaryRecrossings;
        dst.distanceTraveled = distanceTraveled;
        dst.straightLineDistance = straightLineDistance;
        dst.outcome = outcome;
        dst.haveRegion = false;
        dst.leftCount = 0;
    }

    /** One concise, greppable INFO line for a journey end (prefix {@code [Orebit][NAVSTATS]}). */
    String logLine() {
        return String.format(java.util.Locale.ROOT,
                "[Orebit][NAVSTATS] outcome=%s searches=%d expansions=%d/%d partial=%d capHit=%d flood=%d "
                        + "replans=%d repairs=%d crossInval=%d recross=%d dist=%.1f straight=%.1f routeEff=%.2f",
                outcome, searchCount, totalExpansions, maxExpansions, partialCount, capHitCount, floodCount,
                replanCount, repairCount, crossingsInvalidated, boundaryRecrossings,
                distanceTraveled, straightLineDistance, routeEfficiency());
    }

    /** A readable multi-line table for {@code /bot stats} ({@code label} names the journey). */
    List<String> table(String label) {
        List<String> out = new ArrayList<>();
        out.add("Nav journey stats (" + label + "):");
        out.add(String.format(java.util.Locale.ROOT, "  outcome           : %s", outcome));
        out.add(String.format(java.util.Locale.ROOT, "  searches          : %d", searchCount));
        out.add(String.format(java.util.Locale.ROOT, "  expansions total  : %d", totalExpansions));
        out.add(String.format(java.util.Locale.ROOT, "  expansions max    : %d", maxExpansions));
        out.add(String.format(java.util.Locale.ROOT, "  partial paths     : %d", partialCount));
        out.add(String.format(java.util.Locale.ROOT, "  cap hits          : %d", capHitCount));
        out.add(String.format(java.util.Locale.ROOT, "  region floods     : %d", floodCount));
        out.add(String.format(java.util.Locale.ROOT, "  skeleton replans  : %d", replanCount));
        out.add(String.format(java.util.Locale.ROOT, "  hop repairs       : %d", repairCount));
        out.add(String.format(java.util.Locale.ROOT, "  crossings invalid : %d", crossingsInvalidated));
        out.add(String.format(java.util.Locale.ROOT, "  boundary recross  : %d", boundaryRecrossings));
        out.add(String.format(java.util.Locale.ROOT, "  distance traveled : %.1f", distanceTraveled));
        out.add(String.format(java.util.Locale.ROOT, "  straight-line     : %.1f", straightLineDistance));
        out.add(String.format(java.util.Locale.ROOT, "  route efficiency  : %.2f", routeEfficiency()));
        return out;
    }
}
