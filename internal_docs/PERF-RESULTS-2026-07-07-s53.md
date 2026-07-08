# PERF RESULTS — 2026-07-07 (s53 benchmark session)

**Machine state:** quiet Windows 11 box, mains power, JDK 21.0.11 (Temurin), Gradle daemon warm.
All runs SERIAL, foreground, one scenario per JVM (pinned) unless noted. Branch `wip/s52-mc121`,
worktree `orebit-mc121-wt`, HEAD `006b98a`. Node = **1.21.11** (see caveat below). Bench harness:
`:1.21.11:jmh` (forks=0, 6 measurement iterations).

**Known measurement incident:** the JMH test-worker JVM sometimes lingers after a successful run
(holds `test-results/jmh/binary/output.bin`, fails the NEXT run's clean). Orphan workers were killed
and the suspicious scenarios (SHORT, MULTI, UPOVER_OPEN, UPOVER_WALL, CLIFFS) re-run clean; the
re-run values are the quoted numbers where two runs exist.

**Profiler-capture provenance:** `cpu.jfr` (21:46, over `FullSearchBenchmark`) and `alloc.jfr`
(21:58, over `RegionFieldBuildBenchmark`) predate commits `72506b1`/`006b98a` (a TRACE-guarded hook
added to `BlockPathfinder`; TRACE is off in benches, so scores are unaffected).

## The 1.21.4 → 1.21.11 node-change caveat (read before comparing)

All prior baselines (`PERF-RESULTS-2026-07-03.md`, s50 FullSearch) were measured on node **1.21.4**.
This session ran on **1.21.11** (harness retargeted in `581c7e1`). On 1.21.11 NavBlock interns
29,671 block states → 248 navtypes headless — a bigger state table and different navtype indices
than 1.21.4. A **uniform** shift across scenarios is plausibly the node change; only
**scenario-specific divergences beyond noise** are regression flags. Two further confounds:

1. **s52 behavior changes are in this build** (Parkour AGGRESSIVE deleted → one envelope;
   block-height canon in `MovementContext`; slab-aware Ascend/Parkour/Pillar/guard). These
   legitimately change candidate generation/expansion counts vs the 07-03 baselines.
2. **Pinned vs suite mode:** every run today is pinned (fresh JVM per scenario). The 07-03
   "post-cleanup full suite" numbers are shared-JVM suite runs; 07-03 pinned baselines exist only
   for OPEN (21.83–21.89), SHORT (12.91–12.95), CLIFFS (24.34–24.49).

Bottom line: **no same-node, same-code A/B exists in this data set.** Flags below mean "needs a
same-node A/B against pre-s52 before being believed as a regression", not "confirmed regression".

## 1. PathfinderBenchmark (µs/op, avgt, cnt=6, pinned)

| Scenario | Today (1.21.11) | ± | 07-03 baseline (1.21.4) | Δ | Call |
|---|---|---|---|---|---|
| TOWER | 53.23 | 0.54 | 49.98 (suite) | +6.5% | no clear regression (mode+node confound) |
| OPEN | 21.83 | 0.94 | 21.83–21.89 (pinned) | **flat** | **NO REGRESSION** (pinned-vs-pinned) |
| UPOVER_OPEN | 112.33 → rerun 128.02 | 21.5 / 10.2 | 105.20 (suite) | +7…+22% | noisy both runs; watch — s52 parkour/height-canon plausibly changed candidates |
| UPOVER_WALL | 91.98 → rerun 102.21 | 29.3 / 7.3 | 85.43 (suite) | +8…+20% | same as UPOVER_OPEN |
| SHORT | 15.89 (clean rerun) | 0.45 | 12.91–12.95 (pinned) | **+23%** | FLAG — but same-node smoke earlier today was 14.16±0.08, so much of it moves with the node; SHORT is setup-sensitive and the navtype table grew |
| MULTI | 310.99 → rerun 302.87 | 59.7 / 11.9 | 243.05 (suite) | **+25%** | FLAG (consistent across 2 runs) |
| FLOOD | 19,298 | 1,489 | 13,995 (suite) | **+38%** | FLAG magnitude-wise, but read-dominated and consistent with the uniform patch-storm shift (below) |
| CLIFFS | 29.50 → rerun 29.89 | 0.71 / 0.85 | 24.34–24.49 (pinned) | **+22%** | FLAG (consistent, tight error, pinned-vs-pinned) |
| BRIDGE | 41.59 | 1.54 | 31.68 (suite) | **+31%** | FLAG |
| SPIRAL | 192.08 | 3.42 | 173.21 (suite) | +11% | watch |
| SETUP (new) | **0.628** | 0.045 | — first formal number | — | per-search setup probe |
| SETUP_MACRO (new) | **2.410** | 0.056 | — first formal number | — | macro-context + goal-probe bill ≈ 1.78 µs over SETUP |

Pattern: OPEN is exactly flat pinned-vs-pinned while nearly everything else sits +10…+38%. That is
NOT a uniform shift, so it can't all be waved off as the node change — but the biggest movers
(FLOOD, BRIDGE, CLIFFS, MULTI) also can't be separated from the s52 movement-behavior delta with
this data. **Recommended next step:** one same-node A/B — `wip/s52-mc121` vs its merge-base on
1.21.11 — over CLIFFS/MULTI/BRIDGE/FLOOD pinned; that isolates s52-code from node effects in one run.

## 2. FullSearchBenchmark (µs/op, avgt, cnt=6, live-path two-tier search)

| Scenario | Today (1.21.11) | ± | s50 baseline (1.21.4) | Δ | Call |
|---|---|---|---|---|---|
| GOAL_IN_WINDOW | 5,683.6 | 41.9 | ≈5,648 | +0.6% | **NO REGRESSION** |
| GOAL_NOT_IN_WINDOW | 900.7 | 526.4 | ≈804 | +12% (±58% error) | inconclusive — error bar swallows the delta |

Note the contrast: the full live-path search is flat while block-tier micro scenarios moved.
Consistent with the profiler finding below — FullSearch time is ~91% region-field build, a
completely different code path from the block-tier A\* micro scenarios.

## 3. PatchStormBenchmark (ns/patch, avgt, cnt=6)

| Scenario | Today (1.21.11) | ± | 07-03 baseline (1.21.4) | Δ |
|---|---|---|---|---|
| SCATTER | 2,554 | 95 | 1,821 | +40% |
| DIG | 2,190 | 184 | 1,573 | +39% |
| TOGGLE | 1,986 | 146 | 1,455 | +36% |
| SEAM | 2,990 | 156 | 2,126 | +41% |

A **uniform +36…+41%** across all four patterns — the uniform-shift shape the node-change rule
expects (per-patch re-classification touches the larger 1.21.11 state/navtype tables), so no
per-scenario flag. The magnitude still deserves inclusion in the same-node A/B above; absolute
cost is now ~2.0–3.0 µs/patch.

## 4. RegionFieldBuildBenchmark (µs/op, avgt, cnt=6) — FIRST formal numbers

| boxSize | SURFACE | ± | BURIED | ± |
|---|---|---|---|---|
| 3 | 6,648.8 | 41.3 | 3,883.1 | 178.2 |
| 5 | 6,796.7 | 413.3 | 4,110.5 | 131.8 |
| 7 | 7,246.7 | 318.1 | 4,563.8 | 176.2 |
| 10 | 8,538.0 | 460.3 | 5,926.2 | 446.3 |

Field build is **milliseconds**, dominated by a large boxSize-independent floor (~6.6 ms SURFACE /
~3.9 ms BURIED at boxSize 3) — consistent with the profiler attribution below (the dig-flood +
field relaxation over the whole region neighborhood, not the goal box, is the bill).

## 5. RegionPathfinderBenchmark (µs/op, avgt, cnt=6)

| Scenario | Score | ± |
|---|---|---|
| OPEN_CAVERN | 0.948 | 0.030 |
| SEALED_DIG | 0.288 | 0.022 |
| MULTI_FRAGMENT | 0.525 | 0.010 |
| LONG_CASCADE | 7.439 | 0.223 |
| ZERO_CAP | 0.654 | 0.059 |

Region-tier planning itself is sub-10 µs everywhere — the region-side cost lives in the FIELD
BUILD (section 4), not the A\*.

## 6. Cold start + unit suite

- `:1.21.11:coldstart` (fresh JVM): first = **23.70 ms** (63 expansions), second = 2.06 ms,
  third = 1.75 ms. Matches the ~21.8–23 ms documented pre-warmup baseline (earlier capture today:
  22.94 / 1.18 / 0.70).
- `:1.21.11:test`: **203 tests, 0 failures, 4 skipped** — green.

## 7. Profiler attribution (the owner's ~2k ns/node question)

Source: `versions/1.21.11/build/cpu.jfr` — `jdk.ExecutionSample`, 2,160 samples total, 2,157 on the
`FullSearchBenchmark.search` worker thread. Captured over FullSearchBenchmark (both scenarios).

### 7a. Where FullSearch time actually goes (whole worker, n=2,157)

| Bucket | Samples | % |
|---|---|---|
| **Region cost-field BUILD** (stack passes `RegionCostField.<init>`/`costToGoalField`) | **1,959** | **90.8%** |
| Block A\* (`findPath` in stack) | 142 | 6.6% |
| Benchmark setup / misc | 56 | 2.6% |

**The region cost field build is the FullSearch bottleneck by an order of magnitude.**

### 7b. Inside the field build (n=1,959)

| Sub-path | Samples | % of field build |
|---|---|---|
| `fragmentContaining` (all invoked from `RegionGrid.startFragmentByFlood`, under the `goalDigSeeds` dig-flood BFS) | 913 | **46.6%** |
| dig-flood BFS proper (`goalDigSeeds`/`columnAt`, no fragmentContaining) | 62 | 3.2% |
| field relaxation itself (leaves: `relaxFrag` 212, `CostPyramid$Level.rowIfPresent` 190, `columnAt` 120, `costToGoalField` 92, `ensureLeaf` 75, heap pop/intern/push ~120, `footprintCenterWorld` 50, …) | 984 | 50.2% |

**Verdict on the interim "~82.7% of field-build time in `FragmentLeafComputer.fragmentContaining`"
claim: CORRECTED.** `fragmentContaining` is **46.6%** of field-build samples (913/1,959) — and by
leaf frame it is mostly **`FragmentBuilder.fragmentContaining` (740 = 37.8%)** with
`FragmentLeafComputer.fragmentContaining` at 173 = 8.8%. Every one of the 913 comes via
`RegionGrid.startFragmentByFlood` ← `goalDigSeeds` (the interim mechanism — re-scan + re-flood per
passable-cell touch — is confirmed; only the share and the class attribution were off). The other
half of the field build is the fragment-field relaxation sweep, so fixing `fragmentContaining`
alone caps out around −47% of field build.

### 7c. In-search attribution (block A\*, n=142 — thin, directional)

| Bucket | Samples | % of in-search |
|---|---|---|
| `PathEdits.kindAt` (edit-aware cell reads) | 46 | 32.4% |
| Region-field heuristic lookups (`RegionCostField.costAt` 11, `RegionFragments.footprint` 8, `fragmentCentroidWorld` 6, `fragmentOfLevel` 2, `footprintCenterWorld` 2) | 29 | 20.4% |
| Node table (`Nodes.intern` 14 / `pop` 3 / `push` 2) | 19 | 13.4% |
| `findPath` loop body | 10 | 7.0% |
| `EditScratch` (`requireFootingOn` 8, `requireFloor` 2) | 10 | 7.0% |
| Movement `candidates` (Traverse/Ascend/Parkour/Diagonal) | 7 | 4.9% |
| `Relaxer.relax` | 6 | 4.2% |
| `NavGridView` reads (`sectionAt`/`lookupChunk`) | 5 | 3.5% |
| Cuboid subsystem | 4 | 2.8% |
| Other (CandidateSink etc.) | 6 | 4.2% |

So within the block tier the region-field **heuristic consultation is ~20% of in-search time** —
real but second to `kindAt`. The ~2k ns/node the owner observed on live searches is NOT explained
by any single in-search hot spot; on the FullSearch shape it is the per-window **field build**
amortized over few nodes that dominates wall time.

## 8. Allocation attribution (evidence for the approved P5 de-boxing fix)

Source: `versions/1.21.11/build/alloc.jfr` — `jdk.ObjectAllocationSample` over
`RegionFieldBuildBenchmark`, 59,993 worker samples, 30.04 GB weighted.

| Weighted MB | % | Type | Site (leaf orebit frame) |
|---|---|---|---|
| 5,942 | 19.8% | `java.lang.reflect.Type[]` | `RegionGrid.columnAt` |
| 4,995 | 16.6% | `HashMap$TreeNode` | `RegionGrid.goalDigSeeds` |
| 4,971 | 16.5% | `java.lang.Integer` | `RegionGrid.goalDigSeeds` |
| 4,776 | 15.9% | `int[]` | `RegionCostField.<init>` (the field arrays — legitimate) |
| 2,718 | 9.0% | `java.lang.Long` | `RegionGrid.columnAt` |
| 1,669 | 5.6% | `int[]` | `RegionGrid.goalDigSeeds` |
| 1,252 | 4.2% | `HashMap$Node` | `RegionGrid.goalDigSeeds` |
| 1,011 | 3.4% | `Object[]` | `RegionGrid.goalDigSeeds` |
| 790 | 2.6% | `HashMap$Node[]` | `RegionGrid.goalDigSeeds` |
| 782 | 2.6% | `float[]` | `RegionCostField.<init>` (legitimate) |

**~78% of all field-build allocation is boxed-key map machinery on the dig-flood path.** The
standout: the 5.9 GB of `reflect.Type[]` is `ConcurrentHashMap.comparableClassFor` → 
`ParameterizedTypeImpl.getActualTypeArguments()` — `RegionGrid.columnAt`'s CHM lookups on
**treeified bins with boxed keys** pay a fresh reflective `Type[]` allocation per tree-bin compare
chain. Boxing (`Integer` 4.97 GB + `Long` 2.72 GB) plus `HashMap` node/tree churn in
`goalDigSeeds` is the rest. The P5 de-boxing fix (primitive-keyed structures for the dig-flood +
column lookup) attacks exactly the sites measured here; only the `int[]`/`float[]` at
`RegionCostField.<init>` (~18.5%) is the field's own storage.

## 9. Session inventory

- Harvested from disk (lost transcript): SETUP 0.628±0.045 (22:54 run, survived in
  `jmh-results.txt`). SETUP_MACRO was overwritten → re-run (2.410±0.056).
- Earlier-session numbers kept for the record: coldstart first/second/third = 22.94/1.18/0.70 ms;
  PathfinderBenchmark SHORT smoke 14.155±0.080 µs (both on this node, 1.21.11, earlier today).
- All scores in this doc from clean, serial, pinned foreground runs; orphan JMH workers killed
  between runs after the file-lock incident.
