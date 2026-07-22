package com.orebit.mod.pathfinding.regionpathfinder;

import com.orebit.mod.Debug;
import com.orebit.mod.OrebitCommon;
import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.worldmodel.hpa.InvalidationRollup;
import com.orebit.mod.worldmodel.hpa.RegionAddress;
import com.orebit.mod.worldmodel.hpa.RegionCrossingMemory;
import com.orebit.mod.worldmodel.hpa.RegionFragments;
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
 * per-tick {@link #onBotMoved} fast path (still inside every window) does {@code region(bot,L)} integer math
 * per level (≤7) plus at most ONE alloc-free level-0 fragment resolution ({@link #botFragmentL0}, lazily, only
 * when a level-0 region match exists) and allocates nothing; a re-plan runs at most a small suffix of windowed searches (reusing
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

    /** §3b tube half-width: every sub-top level plans PERMANENTLY confined to within this many parent cells of
     *  the level-above skeleton ({@link RegionPathfinder.RegionTube}). A tube-confined {@code null} is handled
     *  by {@link #rederiveWithTubeEscalation} (blame a parent-window hop, re-plan the parent) — it is never a
     *  no-route by itself. */
    private static final int TUBE_MARGIN = 2;

    /**
     * Hard backstop on per-event tube-escalation iterations (pathological terrain): each iteration forbids one
     * NEW parent crossing and re-runs ≤ {@code topLevel} cap-safe windowed searches, so the cap bounds one
     * event's tick work. Realistic corridors converge in 1–3 blames; past the cap we stop repairing THIS
     * event — the accumulated blacklists live on in the plan and in {@link #crossingMemory}, so a later event
     * resumes from the learned state instead of rediscovering it.
     */
    private static final int MAX_TUBE_ESCALATIONS = 32;

    // ---- immutable navigation context ----------------------------------------------------------------
    private final RegionGrid grid;
    private final int minY;
    private final BlockPos goal;
    private final BotCaps caps;
    /** The bot's tool-aware region dig-cost model (PERF-DESIGN region §5), built once from its inventory. */
    private final RegionMineModel mine;
    /**
     * The dimension's long-lived crossing-invalidation memory (#5). This plan SEEDS its blacklists from it at
     * construction (so dead crossings proven under an earlier goal survive the plan boundary) and RECORDS every
     * newly-proven dead crossing back into it. Shared per-dimension via {@link RegionGrid#crossingMemory()}.
     */
    private final RegionCrossingMemory crossingMemory;
    /**
     * This plan's EFFECTIVE realizability signature ({@link BotCaps#realizabilitySig(MovementContext.InventoryView)}
     * over the ctor's caps + inventory snapshot) — the dominance tag stored with each crossing this plan
     * invalidates AND the seed-time dominance probe. Because {@code PathPlan} builds a fresh cascade per plan
     * from its per-replan {@code (caps, inventory)} pair, this construction-time value IS the sig of every
     * search this plan runs — the record-site skew (a caps-only tag mislabeling what an inventory-gated
     * search actually proved) is fixed at the source (DESIGN-persisted-invalidation-memory.md §3.2).
     */
    private final long capsSig;
    /**
     * Whether this plan may RECORD into {@link #crossingMemory} — false when built with no {@link
     * MovementContext.InventoryView} (headless, {@code /bot trace}, tests): a null-inv search's
     * placement/mining assumptions (infinite blocks + bare-hand pricing) are incoherent as a proof
     * hypothesis, so its failures must not become world knowledge (§3.3). Seeding still happens either way
     * (the null-inv sig is the caps-only semantics — config caps, BARE tiers — preserving the historical
     * headless seed behaviour).
     */
    private final boolean recordToMemory;

    // ---- the stack (indices 0..topLevel valid) -------------------------------------------------------
    private final LevelPlan[] levels = new LevelPlan[RegionAddress.MAX_COARSE_LEVEL + 1];
    /** Per-level forbidden crossings (online repair, §6); index = level. Level-relative keys never collide. */
    private final RegionEdgeBlacklist[] blacklists = new RegionEdgeBlacklist[RegionAddress.MAX_COARSE_LEVEL + 1];
    private int topLevel;
    /** {@code true} once no route exists — FAILED. Set ONLY by (a) an UNTUBED top-level heap drain (the honest
     *  disconnection signal), (b) a flood still present at {@link RegionAddress#MAX_COARSE_LEVEL}, or (c) the
     *  {@link #MAX_TUBE_ESCALATIONS} backstop. A tubed {@code null} never sets it directly — it escalates
     *  ({@link #rederiveWithTubeEscalation}). */
    private boolean failed;
    /** The LEVEL of the terminal drained {@code planWithin} of the most recent failed re-derive (the search
     *  whose {@code null} the escalation could not repair) — {@code -1} until a re-derive fails. Captured in
     *  {@link #rederiveWithTubeEscalation} purely for the FAIL post-mortem ({@link #logFailPostMortem});
     *  never a control input. */
    private int lastFailedLevel = -1;

    private HierarchicalRegionPlan(RegionGrid grid, int minY, BlockPos goal, BotCaps caps, RegionMineModel mine,
                                   MovementContext.InventoryView inventory) {
        this.grid = grid;
        this.minY = minY;
        this.goal = goal;
        this.caps = caps;
        this.mine = mine;
        this.crossingMemory = grid.crossingMemory();
        this.capsSig = caps.realizabilitySig(inventory);
        this.recordToMemory = inventory != null;
        for (int i = 0; i < levels.length; i++) {
            levels[i] = new LevelPlan();
            blacklists[i] = new RegionEdgeBlacklist();
        }
        // #5: seed this navigation's per-level blacklists from the dimension's long-lived crossing memory, so
        // dead crossings proven under an earlier goal are avoided from the FIRST skeleton instead of being
        // re-discovered from scratch. Only crossings whose FAILING sig dominates this bot's current EFFECTIVE
        // sig bind it (a stronger bot — or the same bot after picking up blocks/tools — ignores a weaker
        // negative); for the common single-bot steady-state the sigs are identical, so every remembered
        // crossing applies. A mid-goal inventory improvement takes effect at the next plan rebuild, consistent
        // with plan lifecycle generally (§3.4). Empty memory (fresh dimension / tests / benchmarks) seeds
        // nothing → byte-identical to before this increment.
        int seeded = 0;
        for (int L = 0; L < blacklists.length; L++) {
            final int n = crossingMemory.count(L);
            for (int i = 0; i < n; i++) {
                if (BotCaps.sigDominates(crossingMemory.sigAt(L, i), capsSig)) {
                    blacklists[L].add(crossingMemory.fromAt(L, i), crossingMemory.toAt(L, i));
                    seeded++;
                    // Per-row seed dump (diagnostic 1) — Debug-gated like the other plan logs (the count
                    // summary below is always emitted; the rows are the firehose).
                    if (Debug.ENABLED) {
                        OrebitCommon.LOGGER.info("[Orebit] seeded L{} {}->{} sig={} prov={}",
                                L, RegionPathfinder.describeFragKey(crossingMemory.fromAt(L, i)),
                                RegionPathfinder.describeFragKey(crossingMemory.toAt(L, i)),
                                Long.toHexString(crossingMemory.sigAt(L, i)),
                                provChar(crossingMemory.provAt(L, i)));
                    }
                }
            }
        }
        if (seeded > 0) {
            // Always-on one-liner: how much learned world knowledge this plan starts from (crossing-memory
            // seeding was previously invisible — a plan silently avoiding remembered dead crossings read
            // exactly like a plan that never knew them).
            OrebitCommon.LOGGER.info(
                    "[Orebit] region crossing-memory seeded {} remembered crossings into this plan's blacklists"
                            + " (sig={})",
                    seeded, Long.toHexString(capsSig));
        }
    }

    /** Provenance letter for the seed dump: {@code P} = block-tier proof, {@code E} = tube-escalation
     *  inference, {@code R} = Phase-2b roll-up derivation ({@code ?} defensively). */
    private static char provChar(int prov) {
        switch (prov) {
            case RegionCrossingMemory.PROV_PROOF:      return 'P';
            case RegionCrossingMemory.PROV_ESCALATION: return 'E';
            case RegionCrossingMemory.PROV_ROLLED_UP:  return 'R';
            default:                                   return '?';
        }
    }

    /**
     * Build a fresh cascade from {@code botFloor} toward {@code goal} (HPA-CASCADE.md §3): choose the cap-safe
     * top level, then descend top→0 planning each level toward the sub-goal handed down from the level above. A
     * tube-confined {@code null} at a sub-top level escalates ({@link #rederiveWithTubeEscalation}); only an
     * UNTUBED top-level {@code null} with escalation exhausted (or a flood at the coarsest level / the
     * escalation backstop) is a genuine no-route ⇒ the returned plan is {@link #isFailed() FAILED}
     * ({@link #l0Skeleton()} {@code null}).
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
        return build(grid, minY, botFloor, goal, caps, mine, null);
    }

    /**
     * As above, additionally carrying the live bot's per-pathfind {@link MovementContext.InventoryView} —
     * the SAME snapshot the plan's windowed block searches run under — so the #5 crossing-memory sig is the
     * EFFECTIVE realizability signature ({@link BotCaps#realizabilitySig(MovementContext.InventoryView)}:
     * effective place + tool tiers) of what those searches actually prove
     * (DESIGN-persisted-invalidation-memory.md §3). {@code null} (every headless / test caller) = caps-only
     * sig for SEEDING dominance — byte-identical to the historical sig — and NO recording into the
     * dimension's {@link RegionCrossingMemory} (§3.3: a null-inv search's placement/mining assumptions are
     * incoherent as a proof hypothesis).
     */
    public static HierarchicalRegionPlan build(RegionGrid grid, int minY, BlockPos botFloor, BlockPos goal,
                                               BotCaps caps, RegionMineModel mine,
                                               MovementContext.InventoryView inventory) {
        HierarchicalRegionPlan h = new HierarchicalRegionPlan(grid, minY, goal, caps, mine, inventory);
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

    /** Total blacklisted crossings across all levels — a search-health telemetry read ({@code /bot stats}'s
     *  {@code crossingsInvalidated}); grows as {@link #repairBlocked} blacklists dead hops. Pure observation. */
    public int totalBlacklisted() {
        int n = 0;
        for (RegionEdgeBlacklist b : blacklists) n += b.size();
        return n;
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
            // Advance committedIndex to the NEAREST not-yet-committed window cell the bot OCCUPIES (forward-
            // only over [committedIndex..far], so a transient dip back never retreats it — region-tier commit
            // hysteresis), and note whether the bot is anywhere in the committed window at all.
            //
            // NEAREST-FIRST, not furthest-first (the s54 per-tick replan livelock, both halves): commit is
            // PROGRESS, and progress accrues INCREMENTALLY as the bot walks the skeleton — each new step it
            // enters is matched and committed in turn. A skeleton may legitimately contain the SAME matching
            // identity at two indices: at L0 the same (region, fragment) under two ENTRY kinds (the region A*
            // node key is (fragment, entry) — an interior start plus a portal re-entry, the cliff-detour
            // shape), at any level the same region via two fragments (the two-pocket cliff region), and at
            // coarse levels any region revisit (the scan there is region-only). Matching the FURTHEST copy
            // teleports the commit across a loop the bot never walked: a fresh skeleton whose window-far step
            // revisits the start identity instantly reads committed>=far, fires `exhausted`, and re-derives an
            // IDENTICAL skeleton (same inputs) as a new object — which the driver swaps in every tick, forever.
            // Nearest-first is byte-identical for a unique match (the overwhelmingly common case), and real
            // progress is never lost: by the time the bot genuinely reaches a revisit copy, committedIndex has
            // already advanced past the earlier copy step-by-step, so the far copy is the nearest match then.
            //
            // At LEVEL 0 with a fragment-model skeleton the match is additionally FRAGMENT-GATED: node
            // identity there is (region, fragment), and a region-only match is not occupancy (bot in the
            // bottom pocket, step through the top pocket). A step advances only when its fragmentId equals the
            // bot's resolved fragment ({@link #botFragmentL0}; -1 = unresolvable never matches, and a
            // {@link RegionPathfinder#VIRTUAL_GOAL_FRAG} step can never match a real bot fragment — a virtual
            // step has no occupiable identity to stand in). `inWindow` stays REGION-scale (the deviation test
            // is spatial; that semantic is unchanged).
            //
            // COARSE levels (L >= 1) stay region-only deliberately: resolving which COARSE fragment a bot
            // inside a multi-fragment coarse cell occupies needs the cost-field labels (there is no backing
            // section to flood at coarse scale — see RegionGrid.startFragmentByFlood's level-0-only contract);
            // a per-approach coarse identity is an open item documented in HANDOFF. Nearest-first alone
            // already removes the coarse false-far-commit.
            final boolean fragGate = L == 0 && sk.isFragmentModel();
            int botFrag = Integer.MIN_VALUE; // lazily resolved on the first level-0 region match
            boolean inWindow = false;
            for (int i = lp.committedIndex; i <= far; i++) {
                if (sk.rx(i) == brx && sk.ry(i) == bry && sk.rz(i) == brz) {
                    inWindow = true;
                    if (fragGate) {
                        if (botFrag == Integer.MIN_VALUE) {
                            botFrag = botFragmentL0(brx, bry, brz, botFloor);
                        }
                        if (sk.fragmentId(i) != botFrag) {
                            continue; // region-only match — not the bot's node; keep scanning later steps
                        }
                    }
                    if (i > lp.committedIndex) lp.committedIndex = i;
                    break;
                }
            }
            // Exit this level when EITHER the bot committed to its far cell (window exhausted — needs sliding,
            // unless that cell is the true goal end) OR the bot is genuinely OFF the route: outside the window
            // AND not on its active block plan (onRoute — the plan vouches for intentional off-window steps
            // like a cliff-edge fall-lineup; see the method javadoc). Re-plan the suffix at/below the coarsest
            // exited level.
            //
            // RESIDUAL RE-DERIVE-PROGRESS HAZARD: when `exhausted` (or `deviated`) fires from a state whose
            // planning inputs are UNCHANGED, rederiveWithTubeEscalation returns an identical-content skeleton
            // as a NEW object; the driver then swaps it in, resets its block window, and the follower rebuilds
            // its phase plan from scratch — a full progress reset. Any trigger that can fire again from the
            // identical state therefore livelocks at one re-derive per tick. The fragment-gated advance above
            // removes the known false trigger; the invariant to preserve is that exhausted/deviated imply real
            // bot progress or displacement, never a matcher artifact. (Do NOT paper over a recurrence with a
            // "skip swap if content identical" mask — that hides the broken trigger instead of fixing it.)
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
        // Re-plan the suffix exited..0 (levels above are untouched — their windows still contain the bot). A
        // tube-confined failure below the top escalates with realized blame (a genuine deviation that still
        // replans cleanly blames nothing).
        final RegionPathPlan beforeL0 = levels[0].skeleton;
        if (!rederiveWithTubeEscalation(exited, botFloor)) {
            // §3a: a FLOOD (not a genuine no-route) means the search area outgrew this top — widen the lens with
            // a full flood-aware rebuild (which escalates topLevel) before reporting FAILED.
            if (RegionPathfinder.lastWasFlood()) {
                rebuild(botFloor);
                return failed || levels[0].skeleton != beforeL0;
            }
            failed = true;
            logFailPostMortem(botFloor);
            return true; // l0Skeleton() now null → the driver reports FAILED
        }
        return levels[0].skeleton != beforeL0;
    }

    /**
     * Online repair (HPA-CASCADE.md §6): the block tier proved the level-0 crossing {@code l0FromKey →
     * l0ToKey} unrealizable for these caps. Blacklist it and re-plan L0 within the current L1 window; if L0
     * still can't route (a tube-confined {@code null}), escalate via {@link #rederiveWithTubeEscalation} —
     * blame the first parent-window hop the failed refinement did not touch and re-plan the parent, recursing
     * upward until a level finds a way around. Returns {@code true} iff a route remains ({@link #l0Skeleton()}
     * non-null); {@code false} ⇒ escalation exhausted (honest give-up). The keys come from
     * {@link RegionPathfinder#fragmentNodeKey}.
     *
     * <p>This 3-arg form is the sync/test convention: {@code botFloor} IS the failing search's start floor
     * (a synchronous search runs from the bot's live cell). The live driver's async path uses the 4-arg
     * form with the snapshotted search start ({@code PathPlan.blockedStartFloor}).
     */
    public boolean onBlocked(long l0FromKey, long l0ToKey, BlockPos botFloor) {
        return onBlocked(l0FromKey, l0ToKey, botFloor, botFloor);
    }

    /**
     * As {@link #onBlocked(long, long, BlockPos)}, additionally carrying the failing search's START floor
     * cell ({@code searchStartFloor} — the sync {@code findPath} from-floor / the async
     * {@code SearchRequest} start, snapshotted by {@code PathPlan.resultStatus}; may differ from the live
     * {@code botFloor}, which stays the re-plan origin). It scopes the #5 record decision — see the
     * start-region rule in the body.
     */
    public boolean onBlocked(long l0FromKey, long l0ToKey, BlockPos botFloor, BlockPos searchStartFloor) {
        if (failed) {
            return false;
        }
        blacklists[0].add(l0FromKey, l0ToKey);
        // #5: remember the dead crossing under the EFFECTIVE sig of the searches that proved it (the plan's
        // per-replan caps+inventory, captured at construction — see the capsSig field note). Null-inv plans
        // never record (§3.3); their per-plan blacklist above still applies within this navigation.
        //
        // START-REGION JOURNEY SCOPING (component-scoped proof): a blame whose FROM region is the failing
        // search's OWN start region is only proven for the caps-connected component the bot stands in — the
        // optimistic fragment can span pockets the caps disconnect (the ravine problem: a bot on the ravine
        // floor proves "this component can't exit toward X", which says nothing about the same fragment's
        // rim component). Such rows stay journey-scoped: the per-plan blacklists[0].add above (already
        // per-goal) applies, but they must NOT become world knowledge — no crossingMemory.record, and no
        // roll-up fold (an unrecorded row can't complete a parent kill-set). Deeper hops (FROM != start
        // region) were reached by actually crossing INTO that region, so their proof is component-anchored
        // by the route itself and records as before. blameTubeConfined's PROV_ESCALATION parent rows are
        // already session-only (persistence-filtered) — see its javadoc.
        final boolean startScoped = searchStartFloor != null
                && RegionAddress.unpackRX(l0FromKey) == RegionAddress.regionX(searchStartFloor.getX(), 0)
                && RegionAddress.unpackRY(l0FromKey) == RegionAddress.regionY(searchStartFloor.getY(), 0, minY)
                && RegionAddress.unpackRZ(l0FromKey) == RegionAddress.regionZ(searchStartFloor.getZ(), 0);
        // VIRTUAL-GOAL JOURNEY SCOPING: a blame whose TO node is the virtual goal V (fragment bits 50..55 of
        // the key == RegionPathfinder.VIRTUAL_GOAL_FRAG — the same read describeFragKey uses) is likewise
        // journey-scoped, for two principled reasons. (1) No realized-evidence backing: PathPlan.blameHop
        // treats an (approach → V) hop as unrealized BY DEFINITION on a BLOCKED result — reaching V IS
        // reaching the goal cell, which would have been a FOUND — so it is the ONLY blame class condemned
        // without the realized-crossing evidence every other hop is judged against, and it never meets the
        // proof standard that justifies a durable row. (2) No goal-cell identity in the key: V's TO key is
        // (goalRegion, VIRTUAL_GOAL_FRAG) — it does not encode which goal CELL the plan was aiming at, so two
        // different goals in the same region SHARE the key, and a persisted V-row proven against goal A would
        // structurally poison goal B's approaches forever. The per-plan blacklists[0].add above (already
        // per-goal by construction) is the correct scope: this navigation still reroutes to another approach.
        // Legacy files written before this rule may still carry V-rows; CostPyramidCodec.decode filters them.
        final boolean virtualGoalHop = RegionPathfinder.isVirtualGoal((int) ((l0ToKey >>> 50) & 0x3F));
        if (recordToMemory && !startScoped && !virtualGoalHop) {
            crossingMemory.record(0, l0FromKey, l0ToKey, capsSig,
                    RegionCrossingMemory.PROV_PROOF, BotCaps::sigDominates);
            // Phase-2b roll-up fold (§4b): this proof may have completed its PARENT crossing's kill-set —
            // if every constituent child crossing is now proven dead at a sig dominating this plan's, record
            // a PROV_ROLLED_UP row at the parent level and recurse upward. Cold (a few per goal), tick-
            // confined, read-only on the pyramid. Deliberately NOT invoked from blameTubeConfined:
            // PROV_ESCALATION rows are inference, not proof — they neither trigger nor count in a fold.
            InvalidationRollup.foldFrom(grid.pyramid(), crossingMemory, 0, l0FromKey, l0ToKey, capsSig,
                    BotCaps::sigDominates);
        }
        // Re-plan L0 within the current L1 window; a tube-confined failure (the obstacle is bigger than the
        // corridor at some level) escalates with REALIZED blame — the first parent-window hop the failed
        // refinement did not touch — recursing upward until a level reroutes. When the tubed failure sits AT
        // the committed hop (the cliff case), realized blame degenerates to the old committed-hop blame, so
        // convergence is preserved.
        if (rederiveWithTubeEscalation(0, botFloor)) {
            return true;
        }
        // §3a: escalation is exhausted (an untubed top drain, a blame bail, or the backstop). If the last
        // re-plan FLOODED (an area problem, not a proven dead hop), widen the lens with a flood-aware rebuild
        // (escalates topLevel) before the honest give-up.
        if (RegionPathfinder.lastWasFlood()) {
            rebuild(botFloor);
            return !failed;
        }
        failed = true;
        logFailPostMortem(botFloor);
        return false;
    }

    // ---------------------------------------------------------------------------------------------------
    // Building / re-planning the stack
    // ---------------------------------------------------------------------------------------------------

    /**
     * Full top-down descent from {@code botFloor} (HPA-CASCADE.md §3); sets {@link #failed} on a no-route.
     *
     * <p><b>§3a flood escalation (FINDINGS-region-pillar-flood.md §3).</b> The cap-safe top level is only
     * "cap-safe" for a DIRECT route; a real route that must detour away-then-back (a wide obstacle, or the old
     * free-void) can still blow the search area past {@link RegionPathfinder#maxChebAtLevel} at that level. When a
     * per-level search aborts on the flood guard ({@link RegionPathfinder#lastWasFlood()}), we <b>widen the
     * lens</b> — restart one level coarser (blacklisting NOTHING; the flood is an area problem, not a bad hop) —
     * up to {@link RegionAddress#MAX_COARSE_LEVEL}. Iterative +1: the guard trips EARLY (first out-of-box pop, not
     * the expansion backstop), so a failed attempt is cheap, and each coarser level both halves the span and
     * enlarges the cap-safe radius, so it converges in a couple of steps at most. A flood still present at the
     * coarsest level, or a genuine heap-drain no-route, is an honest FAIL. A TUBED {@code null} never reaches
     * this decision — it is intercepted by the §3b escalation inside {@link #rederiveWithTubeEscalation}; only
     * the untubed top produces the honest-fail drain (the flood guard is active there, so its {@code null} is a
     * real drain or a flood, never a corridor artifact).
     */
    private void rebuild(BlockPos botFloor) {
        this.failed = false;
        int top = capSafeTop(botFloor);
        while (true) {
            this.topLevel = top;
            if (rederiveWithTubeEscalation(top, botFloor)) {
                return; // routed
            }
            if (RegionPathfinder.lastWasFlood() && top < RegionAddress.MAX_COARSE_LEVEL) {
                top++;   // §3a widen the lens (restart coarser, blacklist nothing)
                continue;
            }
            this.failed = true; // untubed top drain, flooded even at the coarsest, or the escalation backstop — honest FAIL
            logFailPostMortem(botFloor);
            return;
        }
    }

    /**
     * FAIL post-mortem (diagnostic 3): every path that sets {@link #failed} logs a cold re-derivation of the
     * terminal drained search's START-node candidate edges — the level it failed at
     * ({@link #lastFailedLevel}), the {@code (region, fragment)} its start anchored to (the same
     * nearest-centroid/flood resolution {@code RegionPathfinder.startFragment} gave the search), and each
     * candidate edge's disposition under the relax loop's own predicates (blacklisted / caps-gated /
     * no-footprint-overlap / solid-neighbor / tube-rejected / OK-but-unexpanded) — see
     * {@link RegionPathfinder#describeStartEdges}. Rare + meaningful ⇒ deliberately UNGATED (the
     * {@code logRepair} idiom); never throws into the failure path.
     */
    private void logFailPostMortem(BlockPos botFloor) {
        try {
            final int L = (lastFailedLevel >= 0 && lastFailedLevel <= RegionAddress.MAX_COARSE_LEVEL)
                    ? lastFailedLevel : topLevel;
            // The terminal search's corridor: every sub-top level runs tubed to the level-above skeleton
            // (rederiveSuffix); the top runs untubed. Reconstructed identically for the re-derivation.
            final RegionPathfinder.RegionTube tube =
                    (L < topLevel && levels[L + 1].skeleton != null)
                            ? new RegionPathfinder.RegionTube(levels[L + 1].skeleton, L + 1, L, TUBE_MARGIN)
                            : null;
            OrebitCommon.LOGGER.info("[Orebit] region FAIL post-mortem L{} {}", L,
                    RegionPathfinder.describeStartEdges(L, grid, minY, botFloor,
                            caps.canBreak(), caps.canPlace(), blacklists[L], tube));
        } catch (Throwable ignored) {
            // diagnostics must never break the failure path
        }
    }

    /**
     * Re-plan levels {@code fromLevel … 0} from the bot toward the chained window sub-goals (HPA-CASCADE.md §5):
     * the top of the suffix aims at the level-above's current window-far cell (or the real goal at the top
     * level); each lower level aims at the cell just re-planned above it. Levels above {@code fromLevel} are
     * untouched. Returns {@code -1} on success; on a {@code null} at some level, that failing LEVEL (without
     * partial mutation beyond the levels it replaced) — the caller escalates.
     * {@link RegionPathfinder#lastWasFlood()} / {@link RegionPathfinder#lastWasTubeConfined()} describe the
     * LAST {@code planWithin} call, which is exactly the failing one (the descent stops there).
     */
    private int rederiveSuffix(int fromLevel, BlockPos botFloor) {
        BlockPos subGoal = (fromLevel >= topLevel) ? goal : handDown(levels[fromLevel + 1]);
        for (int L = fromLevel; L >= 0; L--) {
            RegionPathfinder.RegionTube tube = (L < topLevel)
                    ? new RegionPathfinder.RegionTube(levels[L + 1].skeleton, L + 1, L, TUBE_MARGIN)
                    : null;
            RegionPathPlan skel = RegionPathfinder.planWithin(L, grid, minY, botFloor, subGoal, goal, caps,
                    blacklists[L], mine, tube);
            if (skel == null) {
                return L;
            }
            levels[L].skeleton = skel;
            levels[L].committedIndex = 0;
            subGoal = handDown(levels[L]);
        }
        return -1;
    }

    /**
     * Re-plan the suffix {@code fromLevel…0}, ESCALATING a tube-confined failure instead of mistaking it for a
     * no-route: when a level's refinement returns {@code null} inside its §3b corridor
     * ({@link RegionPathfinder#lastWasTubeConfined()}), the corridor — not the world — is at fault, so blame
     * the first parent-window hop the failed search did not touch ({@link #blameTubeConfined}) and re-derive
     * from the PARENT level, recursing upward as needed. Returns {@code true} iff a level-0 route was
     * produced; {@code false} only on an UNTUBED failure (the top-level drain/flood — the honest no-route
     * signal, with {@link RegionPathfinder#lastWasFlood()} describing that last search), a blame bail (no new
     * fact to add), or the {@link #MAX_TUBE_ESCALATIONS} backstop. A tubed {@code null} therefore NEVER
     * reaches the callers' {@code failed} decision. Termination: every accepted iteration adds a crossing the
     * parent's just-planned skeleton contained (so not previously blacklisted) — the blacklist strictly grows
     * over a finite edge universe, and the cap bounds one event's tick work.
     */
    private boolean rederiveWithTubeEscalation(int fromLevel, BlockPos botFloor) {
        int from = fromLevel;
        for (int blames = 0; ; ) {
            final int failedLevel = rederiveSuffix(from, botFloor);
            if (failedLevel < 0) {
                return true;
            }
            lastFailedLevel = failedLevel; // post-mortem anchor: the terminal drained search's level
            if (!RegionPathfinder.lastWasTubeConfined() || ++blames > MAX_TUBE_ESCALATIONS) {
                return false; // untubed (top) drain/flood — the honest signal — or the backstop
            }
            if (!blameTubeConfined(failedLevel)) {
                return false; // degenerate window / already-blacklisted pick — bail, don't loop unchanged
            }
            from = failedLevel + 1; // ≤ topLevel: a tube exists only when failedLevel < topLevel
        }
    }

    /**
     * Blame a tube-confined failure of {@code childLevel}'s refinement on a PARENT-window crossing — the
     * region-tier twin of {@code PathPlan.blameHop}: the failed child search realized corridor-internal routes
     * to every parent cell it TOUCHED ({@link RegionPathfinder#lastTubeTouchedMask()}), so the first
     * parent-window hop into an UNTOUCHED cell is the crossing it could not realize. When every window cell
     * was touched, fall back to the hop INTO the window-far cell (mirrors {@code blameHop}'s endpoint
     * fallback — guarantees the blame lands on a live window hop). Touch-membership is a sufficient
     * realization signal inside a tube (see {@code RegionPathfinder.collectTubeTouchedMask}); its residual
     * imprecision only DEFERS blame (each accepted blame adds a NEW edge, so the escalation converges), which
     * is why these rows are {@link RegionCrossingMemory#PROV_ESCALATION} — kept off disk by
     * {@code CostPyramidCodec}'s persistence filter — never block-tier {@code PROV_PROOF}. Returns
     * {@code false} on a degenerate one-cell window (no onward hop to blame) or an already-blacklisted pick
     * (no NEW fact — the caller bails instead of re-planning unchanged).
     */
    private boolean blameTubeConfined(int childLevel) {
        final int parent = childLevel + 1;
        final RegionPathPlan sk = levels[parent].skeleton;
        final int far = Math.min(WINDOW_CELLS, sk.size() - 1);
        if (far == 0) {
            return false; // degenerate one-cell window — nothing to blame
        }
        final long touched = RegionPathfinder.lastTubeTouchedMask();
        int to = far; // all-touched fallback: the hop INTO the window-far cell
        for (int i = levels[parent].committedIndex + 1; i <= far; i++) {
            if ((touched & (1L << i)) == 0) {
                to = i;
                break;
            }
        }
        final int from = to - 1;
        final long fromKey = RegionPathfinder.fragmentNodeKey(sk.rx(from), sk.ry(from), sk.rz(from), sk.fragmentId(from));
        final long toKey = RegionPathfinder.fragmentNodeKey(sk.rx(to), sk.ry(to), sk.rz(to), sk.fragmentId(to));
        if (blacklists[parent].contains(fromKey, toKey)) {
            return false; // defensive: no NEW fact to add — bail rather than loop unchanged
        }
        blacklists[parent].add(fromKey, toKey);
        if (recordToMemory) { // §3.3 — same rule as the level-0 record site in onBlocked
            // (Cross-reference: onBlocked's start-region journey scoping does NOT apply here and needs no
            // twin — these PROV_ESCALATION rows are inference, already SESSION-ONLY by construction:
            // CostPyramidCodec's persistence filter keeps them off disk, and they never trigger or count in
            // a roll-up fold. The component-scoped-proof concern is a PROV_PROOF/persistence concern.)
            crossingMemory.record(parent, fromKey, toKey, capsSig,
                    RegionCrossingMemory.PROV_ESCALATION, BotCaps::sigDominates);
        }
        return true;
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

    /**
     * Resolve which level-0 fragment of region {@code (brx,bry,brz)} the bot at {@code floor} occupies — the
     * commit-advance identity for the {@link #onBotMoved} fragment gate. Returns a kept fragment id
     * ({@code 0..62}), or {@code -1} when membership cannot be resolved unambiguously (then NO skeleton step
     * matches — declining to advance is the safe fallback: advancing is only an optimization, and the window
     * slide is also driven by block-plan progress at the driver).
     *
     * <ul>
     *   <li><b>Unbuilt / uniform / collapsed / single-fragment</b> record: the node has exactly ONE fragment
     *       identity — the synthetic (or only kept) fragment {@code 0}, the same id the planner interns for
     *       skeleton steps through such nodes. A region match there is unambiguous by construction, preserving
     *       the pre-fix advance byte-for-byte (this is every node of a record-only headless grid, and the
     *       common built-terrain case).</li>
     *   <li><b>Multi-fragment</b> record: EXACT containment only, via the planner's own flood-from-bot signal
     *       ({@link RegionGrid#startFragmentByFlood}). The flood needs a PASSABLE seed and {@code floor} is
     *       normally the solid support cell, so it seeds at the bot's FEET cell ({@code floor.above()}) when
     *       that cell is still inside the same level-0 region (a floor at local y=15 has its feet in the region
     *       above, whose fragment ids belong to a different node — never usable here), falling back to the
     *       floor cell itself (a swimming bot's floor is passable water). An inconclusive flood returns
     *       {@code -1}: between REAL sibling fragments we refuse to guess (the nearest-centroid guess is
     *       exactly the mis-assignment {@code RegionPathfinder.startFragment}'s javadoc documents), because a
     *       wrong guess re-arms the false far-commit this gate exists to remove.</li>
     * </ul>
     *
     * <p>Alloc-free (the flood runs over {@link com.orebit.mod.worldmodel.hpa.FragmentLeafComputer}'s
     * thread-local masks); called at most once per {@link #onBotMoved} (lazily, only when a level-0 region
     * match exists), on the tick thread — the {@code PathPlan.botFragmentAt} per-tick idiom.
     */
    private int botFragmentL0(int brx, int bry, int brz, BlockPos floor) {
        grid.ensureLeaf(brx, bry, brz);
        final RegionFragments rf = grid.fragmentRecord(0, brx, bry, brz);
        if (rf == null || rf.isUniform() || rf.fragmentCount() <= 1) {
            return 0; // one fragment identity (synthetic or the only kept one) — unambiguous
        }
        final int fx = floor.getX(), fy = floor.getY(), fz = floor.getZ();
        final boolean feetInRegion = ((fy - minY) & 15) != 15;
        int f = grid.startFragmentByFlood(brx, bry, brz, fx, feetInRegion ? fy + 1 : fy, fz);
        if (f < 0 && feetInRegion) {
            f = grid.startFragmentByFlood(brx, bry, brz, fx, fy, fz); // swimming: the floor cell is passable
        }
        return f; // exact containment, or -1 = unresolved (no advance)
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
