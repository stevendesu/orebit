# PERF-PROFILE 2026-07 — block-A* CPU/allocation audit (pre-experiment, condensed)

> **STATUS: motivating audit; all §7 recommendations have since been tried.** This was the pre-experiment
> JFR CPU/allocation profile that motivated the E1–E5 experiments. Every recommendation it made has been
> executed or refuted — **see `PERF-RESULTS-2026-07-03.md` for outcomes** (E1/E2 edit-bbox gate refuted,
> E3 floorGap kept-w/-caveat, E4 runUp cuboid accel adopted −30..36%, E5 NavWarmup adopted, E5b eager-size
> refuted). The full 231-line profile was collapsed to this stub; the headline numbers other docs cite are
> preserved below.

Motivated by three in-game samples (26.2): S1 `2 nodes / 16.3 ms` (first search after boot), S2
`8357 nodes / 5680 ns/node` (first BIG search, lazy growth mid-search), S3 `7221 nodes / 2226 ns/node`
(warm, edit-heavy pillaring; edit-free floods reach 400–700 ns/node). Method: bench worktree
`orebit-mc121-wt` (1.21.4 node, JDK 21, forks=0 JMH) with MAIN's uncommitted src synced; in-process JFR
`jdk.ExecutionSample` (`-Pprof=cpu`) + `jdk.ObjectAllocationSample` (`-Pprof=jfr`) + a throwaway
reflection probe for grown ThreadLocal capacities + a warmed 15-s big-flood loop for the S3 shape.

## Headline numbers (cited by the design docs)

- **`PathEdits.kindAt` (the per-read speculative-edit diff gate) is ~49% of warm edit-heavy search CPU**
  (WARM FLOOD; 28.7% TOWER / 25.5% UPOVER_WALL) — paid on every `descriptorAt/Of` read once the search
  carries any edit. This is the largest WARM lever.
- **Cuboid extraction is 38–45% of small-search CPU** (`CuboidExtractor` + `sectionRawAt` under it; ~45%
  TOWER, ~38% UPOVER_WALL) — driven ~46% (TOWER) / 32% (UPOVER_WALL) via the once-per-search
  `GoalForcedCost.probe` re-extracting the same goal-face cuboids every search (the memo is per-search).
- **S1's 16 ms is JIT, not allocation** — classloading + interpreted/C1 first execution of the pathfinder
  class graph. The lazily-allocated scratch is only ~41 KB. S2 is ~61% JIT warm-up
  ((5680−2226)×8357 ≈ 28.9 ms) and only ~1–2% growth-reallocation. JMH cannot see either case (forks=0
  reuses a warmed JVM). The fix is a boot-time synthetic warm-up search, not pre-sized arrays.
- **Pre-allocation (eager-size the ~2.3 MB/thread ThreadLocal scratch) is smoothing only** — it removes
  the ≲1 ms / ~1–2% S2 growth bill and none of S1/S3. Steady-state allocation is dominated by per-search
  construction (56% `NavGridView.<init>` chunk-cache arrays) + per-plan `reconstruct` output (~33%), all
  poolable; absolute rate is harmless for GC, the interest is the SHORT ~455 ns/pop latency floor.

(The §7 recommendation ledger is the STATUS block above — E1/E2 edit-bbox gate refuted+deleted, E3
floorGap kept-w/-caveat, E4 runUp cuboid accel adopted, E5 NavWarmup adopted, E5b eager-size refuted.
Two §7 items are still open: persist the goal-probe / base cuboids across replans, and pool the
per-search construction — see `PERF-RESULTS-2026-07-03.md`.)

*Raw dumps (`cpu-tower.txt`, `cpu-upoverwall.txt`, `flood-warm.txt`, `alloc-all.txt`, `sizeprobe.txt`)
were in the session scratchpad; bench worktree restored byte-clean afterward.*
