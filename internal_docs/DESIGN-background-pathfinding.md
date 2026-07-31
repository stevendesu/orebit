# DESIGN — Background-thread pathfinding + time cap + pre-plan/splice (CONDENSED — SHIPPED s44; full text in git history pre-s52)

**Status: fully implemented** behind `pathing.async` — **default TRUE** since the config bake-in
(`Config.DEFAULT`: async on, 2 planner threads, 250 ms budget); `pathing.async=false` restores the
byte-identical sync mode, whose node cap is `pathing.syncSearchBudgetNodes` (def 10k). In-game
verified: complex-path tick time ~8–16 ms → ~3 ms; a 13.6k-node search FOUND past the old 10k cap.

**Where the code lives now:**
- `src/main/java/com/orebit/mod/pathfinding/async/` — `PlanExecutor` (fixed daemon pool,
  `pathing.maxThreads` def 2 clamp [1, cores−2], per-thread epoch stamps + drain counters),
  `PlanHandle` (volatile-done mailbox, `wasRejected` retry kind), `SearchRequest` (immutable snapshot;
  level is IDENTITY only — workers never touch live chunks)
- `worldmodel/pathing/NavReclaim.java` — epoch-deferred NavSection retirement
- `pathfinding/splice/SpliceSeam.java`, `blockpathfinder/EditSnapshot.java`, `PathEdits.addSnapshot`,
  `BlockPathfinder.findPath(..., baseline, budgetNanos)`
- `pathfinding/PathPlan.java` — submit/poll/seam-adopt; config: `pathing.async` /
  `pathing.maxThreads` / `pathing.asyncSearchBudgetMs` (the async wall-clock cap; the old
  `searchBudgetMs` name is dead) + `pathing.syncSearchBudgetNodes` (sync-mode node cap) in
  `config/ConfigKeys.java`; pool start + reclaim drain in `OrebitCommon.init`; `/bot config reload`
  drains the planner pool before rebaking shared tables (§4.4)

**§ map (sections cited by code Javadocs):**
- §1 problem & scope. §2 what exists (the enablers).
- §3 threading model; §3.1 fixed planner pool, latest-wins handoff (`PlanHandle.cancel` advisory);
  §3.2 the request/result protocol (immutable `SearchRequest` snapshot).
- §4 memory safety: **§4.1** NavSection use-after-recycle — THE hazard; fix = retirement grace
  (`NavReclaim`, drained per level-tick against `PlanExecutor.minActiveStamp()`); **§4.2** no
  live-level fallback on planner threads (`NavGridView.background` reads AIR out-of-built);
  §4.3 in-place patches vs a concurrent reader — document-and-accept; **§4.4** cold rebakes drain the
  planner before mutating shared tables (`ConfigLoader` reload); §4.5 region tier stays tick-confined;
  **§4.6** warm-up amendment — `NavWarmup` stays on the tick thread, JIT warmth is JVM-global.
- §5 async `PathPlan` — the one seam that changes (submit at the settled boundary; `pollWhenPlanless`
  tick-rate first-plan adoption).
  **Mid-motion amendment (owner ruling 2026-07-30):** never plan while the bot is in motion. The driver
  gates every plan-LAUNCH/adopt decision point (fresh replan, the settled-boundary bundle, the planless
  poll, the repair step) on PLAN-ANCHOR stability — `grounded() || isInWater() || inLava() ||
  onClimbable()`, i.e. any CONTROLLED medium — because floor-cell equality alone passed while airborne
  (the mid-air Fall adoption incident). Only BALLISTIC states (jump/fall arcs) defer, to the touchdown a
  few ticks away. The anchor predicate is deliberately WIDER than the arrival test's "safe to drop all
  inputs" pair (`grounded||isInWater`): a climbable hang and lava suspension are valid anchors (review
  findings — a gate on the narrow pair slid a hanging bot to the ladder base in a climb/slide livelock,
  and made a lava-borne bot sink to the pool floor before planning its escape), but neither is a place to
  drop inputs. Pure window slides are untouched (they never create a new `BlockPathPlan` reference). And
  adoption gains a POST-PLAN RECONCILE: a seam-accepted result is adopted only when the bot's floor is
  the searched start or ON the result plan (`AsyncWindowSearch.onStartOrPlan`; the follower's
  reached-scan then enters mid-plan — the existing "advance SKIPPED" mechanism); otherwise RETRY from the
  actual floor (`drainPending`), and a parked P4 precompute that fails the test stays parked for the
  approach case but is DROPPED by `refreshWindow` (a consumed/terrain-impacted plan's parked result
  already failed this tick's adoption — keeping it would veto every resubmit: the parked-wedge review
  finding). Seam tolerance (Chebyshev 3) is unchanged — the membership test is strictly tighter, never
  wider.
- §6 time-based cap — wall clock is the binding limit (`pathing.asyncSearchBudgetMs`, **def 250**,
  checked every 256 pops); the node cap becomes the 262k `TIME_MODE_NODE_BACKSTOP` (memory-only).
- §7 pre-plan + splice — eager next-window plan from the predicted end cell at half-consumed, parked
  until seam-accept; baseline (`EditSnapshot`) seeded AFTER the cameFrom walk so path edits shadow it.
  **Follower-seam note (2026-07-23):** `BlockPathPlan` now carries per-step search-native floors
  (`floorYs`, filled by `reconstruct`); seam-adoption/splice adopt whole `BlockPathPlan` objects, so
  the carry rides the async path with zero seam changes, and the preplan seed uses the carried
  `path.floor(last)` (not a `floorOf(waypoint)` re-derivation) — `DESIGN-validity-envelopes.md` §6.
  A validity-envelope FAILED step currently HOLDs (no auto-replan, no async submit) by owner policy
  — same doc §4.
- §8 perf accounting. §9 phasing.
- §10 risks — incl. the shutdown drain-on-stop and the `LAST_EXPANSIONS`/`LAST_WAS_PARTIAL` statics race
  → ThreadLocal accessors `lastExpansions()`/`lastWasPartial()` (code cites this item as §10.6).
