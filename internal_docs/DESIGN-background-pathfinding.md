# DESIGN — Background-thread pathfinding + time cap + pre-plan/splice (CONDENSED — SHIPPED s44; full text in git history pre-s52)

**Status: fully implemented** behind `pathing.async` (default false = byte-identical sync). In-game
verified: complex-path tick time ~8–16 ms → ~3 ms; a 13.6k-node search FOUND past the old 10k cap.

**Where the code lives now:**
- `src/main/java/com/orebit/mod/pathfinding/async/` — `PlanExecutor` (fixed daemon pool,
  `pathing.maxThreads` def 2 clamp [1, cores−2], per-thread epoch stamps + drain counters),
  `PlanHandle` (volatile-done mailbox, `wasRejected` retry kind), `SearchRequest` (immutable snapshot;
  level is IDENTITY only — workers never touch live chunks)
- `worldmodel/pathing/NavReclaim.java` — epoch-deferred NavSection retirement
- `pathfinding/splice/SpliceSeam.java`, `blockpathfinder/EditSnapshot.java`, `PathEdits.addSnapshot`,
  `BlockPathfinder.findPath(..., baseline, budgetNanos)`
- `pathfinding/PathPlan.java` — submit/poll/seam-adopt; config: `pathing.async` / `maxThreads` /
  `searchBudgetMs` in `config/ConfigKeys.java`; pool start + reclaim drain in `OrebitCommon.init`

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
- §6 time-based cap — wall clock is the binding limit (`searchBudgetMs` def 40, checked every 256 pops);
  the node cap becomes the 262k `TIME_MODE_NODE_BACKSTOP` (memory-only).
- §7 pre-plan + splice — eager next-window plan from the predicted end cell at half-consumed, parked
  until seam-accept; baseline (`EditSnapshot`) seeded AFTER the cameFrom walk so path edits shadow it.
- §8 perf accounting. §9 phasing.
- §10 risks — incl. the shutdown drain-on-stop and the `LAST_EXPANSIONS`/`LAST_WAS_PARTIAL` statics race
  → ThreadLocal accessors `lastExpansions()`/`lastWasPartial()` (code cites this item as §10.6).
