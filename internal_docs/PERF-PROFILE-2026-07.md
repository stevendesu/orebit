# PERF-PROFILE 2026-07 — block-A* allocation audit + CPU/alloc profile (pre-allocation decision)

**Question under test (owner):** should the search state be PRE-ALLOCATED at max size on server start
instead of lazily on first search? Motivated by three real in-game samples (26.2):

| # | In-game log line | Reading |
|---|---|---|
| S1 | `2 nodes in 16275.5 us (8137750 ns/node) +edits -> FOUND-2wp` | first search after boot |
| S2 | `8357 nodes in 47468.1 us (5680 ns/node) +edits` | first BIG search (lazy growth mid-search) |
| S3 | `7221 nodes in 16073.9 us (2226 ns/node) +edits` | warm, edit-heavy (pillaring); floods reach 400–700 ns/node |

**Verdict up front:** pre-allocation is cheap and worth doing (≈2.3 MB/thread, see §7) but it is NOT what
the three samples are showing. S1 is classloading + interpreted first execution (~16 ms, unfixable by
sizing arrays); S2 is ~61% JIT warm-up and only ~1–2% growth-reallocation; S3 is an *algorithmic* per-read
tax — **49% of warm edit-heavy search CPU is `PathEdits.kindAt`**, the speculative-edit diff check paid on
every `descriptorAt/Of` read once the search has any edit. The one boot-time change that actually attacks
S1/S2 is a **synthetic warm-up search at server start** (pre-JIT), not pre-sized arrays.

Environment: bench worktree `orebit-mc121-wt` (branch `mc-1.21`, 1.21.4 node, JDK 21, forks=0 JMH),
MAIN's full uncommitted src diff synced in (incl. the new `Climb`/`Parkour` movements — they add read
volume). Profilers: in-process JFR `jdk.ExecutionSample` @1 ms (`-Pprof=cpu`) and
`jdk.ObjectAllocationSample` (`-Pprof=jfr`); plus a throwaway reflection probe (deleted after the run)
that reports the ThreadLocal search-state's exact grown capacities, and a warmed 15-s big-flood loop with
its own JFR recording (the S3 shape no JMH scenario covers — TOWER now macro-collapses to ~28 pops).
Worktree verified byte-clean afterward (`git status --short` empty).

---

## 1. Static allocation audit — everything lazy/growable on the search path

All sizes assume compressed oops (default < 32 GB heap). "Measured @10001" = the reflection probe after a
budget-exhausted (`maxNodes=10000` ⇒ 10001 pops) edit-bearing flood to a far-unreachable goal (FLOOD2).

| Structure | Scope / lifetime | Initial | Growth policy | Measured @10001 pops | Bytes fully grown |
|---|---|---|---|---|---|
| `Nodes` node table (`key,x,y,z,g,f,parent,move,mode,edits` — 10 parallel arrays, 44 B/row) | ThreadLocal, reused | 512 rows (22.5 KB) | ×2 on fill (`growNodes` copies all 10) | rows=5676, cap=**8192** (4 grows) | 360 KB |
| `Nodes` open-addressed map (`mapKey` long + `mapRow` int, 12 B/slot) | part of `Nodes` | 1024 slots (12 KB) | ×2 at ¾ load, full rehash | size=5676, cap=**8192** (3 grows) | 96 KB |
| `Nodes` binary heap (`heap,heapF,heapG`, 12 B/entry) | part of `Nodes` | 512 (6 KB) | ×2 on push overflow | cap=**4096** (3 grows) | 48 KB |
| `EditPool` arena (slot array + lazily-`new`ed `StepEdits` ≈112 B each) | ThreadLocal, reused | 256 slots, 0 instances | slot array ×2; instances created first-touch, then reused | next=**14090**, cap=**16384** | ~1.72 MB |
| **ThreadLocal total** | | **~41 KB first touch** | | | **≈2.2 MB/thread** |
| `MovementContext` (+`EditScratch` long[6]/long[3], `PathEdits` 64-slot table+`editList`+bbox, `Cuboid` scratch) | **fresh per `findPath`** | ~1.5 KB | `PathEdits` ×2 at ¾ load (25 MB/112 s sampled — tiny) | — | garbage per search |
| `NavGridView` (`ccKeys` long[512] + `ccVals` NavSection[512][] chunk cache + cursor) | **fresh per replan (caller)** | **~6.3 KB, zeroed** | fixed cap 512, degrade past it | — | garbage per search |
| `NavGridCuboidsView` (per-axis `Cuboid[16→256]` memo, 3+1 pooled `Cuboid`s; fresh `Cuboid` per committed box) | **fresh per `findPath`** (when cuboidBound≠null) | ~0.3 KB | list ×2 up to MAX_BOXES=256/axis | — | garbage per search |
| `Relaxer`, `GoalForcedCost.Forced` | fresh per `findPath` | ~120 B | — | — | garbage per search |
| `reconstruct` output (`ArrayList`s, `Integer` boxing, `BlockPos`/waypoint, `StepEdits.copy`) | per returned plan | O(waypoints) | ArrayList doubling | — | garbage per plan |

Notes: rows < pops on a flood (5676 rows / 10001 pops — re-expansions of re-relaxed rows); the budget
bounds POPS, not rows, so `nodeHint=8192` is the honest high-water for `maxNodes=10000`. `EditPool.next`
(14090) counts accepted edit-bearing relaxations — its high-water, not distinct rows (re-relaxations
abandon slots by design).

**Growth-reallocation bill for S2 (8357 pops, same caps as FLOOD2):**
`growNodes`×4 copies (512+1024+2048+4096)×44 B ≈ 338 KB; heap ×3 ≈ 43 KB; `growMap`×3 allocs 172 KB and
re-hashes 5.4 k entries; EditPool slot copies ~130 KB; plus ~12–14 k first-touch `new StepEdits` ≈ 1.4–1.6 MB
allocation. **Total ≈ 2.2 MB of alloc/copy/zero ≈ ≲1 ms — ~1–2% of the 47.5 ms search.** Pre-allocation
removes this, and only this.

---

## 2. Measured baseline (JMH, this worktree state)

| Scenario | us/op (cpu run) | us/op (alloc run) | pops/op (probe) | ns/pop |
|---|---|---|---|---|
| TOWER (macro pillar-up) | 61.3 | 59.9 | 28 | ~2 140 |
| OPEN (edit-free walk) | 23.5 | 22.1 | — | — |
| UPOVER_OPEN | 124.6 | 117.7 | — | — |
| UPOVER_WALL | 93.7 | 89.3 | 41 | ~2 180 |
| SHORT (cold-start guard, fresh view/op) | 13.1 | 12.3 | 27 | ~455 |
| MULTI (4 searches/op) | 273.6 | 261.9 | — | — |
| **WARM FLOOD** (probe loop, 10001-pop edit-bearing flood, 15 s) | 9.53 **ms/search** | — | 10 001 | **952.6** |

Matches the prior session's post-adaptive-PathEdits baseline (`perf-round2/results-log.md`) — no drift.

---

## 3. CPU attribution (JFR ExecutionSample, in-search stacks)

### TOWER pinned (1119 samples) vs UPOVER_WALL pinned (1142) vs WARM FLOOD (1209)

| Category (first matching frame from stack top) | TOWER | UPOVER_WALL | WARM FLOOD |
|---|---|---|---|
| **`PathEdits.kindAt`** (edits-diff read on `descriptorAt/Of`) | 28.7% | 25.5% | **49.0%** |
| **Cuboid extraction** (`CuboidExtractor` + `sectionRawAt` under it) | **~45%** | **~38%** | 0% |
| `Nodes.intern` (map probe) | 7.5% | 3.2% | 10.8% |
| PathEdits **rebuild** (`add`/`markIfAbsent`/`reset`+`Arrays.fill`) | ~6% | ~4.5% | **~8%** |
| Heap push/pop | 1.0% | 8.0% | 5.5% |
| `NavGridCuboidsView` edit-shrink (`findEditInside`/`applyEditShrink`) | 1.8% | 6.9% | 0% |
| `findPath` loop self | 1.5% | 1.3% | 14.1% |
| Movements + EditScratch + MovementContext | ~4.3% | ~4.0% | ~5.2% |
| Grid reads outside extraction | ~2.5% | ~3.4% | 5.7% |
| Relaxer/octile | 0.4% | 1.8% | 3.1% |

**Caller evidence:**
- `NavGridView.sectionAt` (31%/29% self on the pinned runs) is ~93% called from **`sectionRawAt`**, i.e.
  it IS the cuboid extractor's bulk scan, not movement reads.
- The extraction chain on TOWER is **519/1119 samples = 46% via `GoalForcedCost.probe`** — the
  once-per-search goal-face probe re-extracting the corridor's air cuboid **every search** (the memo is
  per-search). UPOVER_WALL: 365/1142 (32%) via probe + 91 (8%) via mid-search `Pillar.candidates`.
- `kindAt`'s callers: `MovementContext.descriptorAt`/`descriptorOf` (plus `hasSolidCollision`/`standable`),
  fanned out of every movement's candidate checks — `Parkour`, `Swim`, `Ascend`, `Fall` are the big readers.
  This is the per-cell tax every read pays once `relaxer.anyEdits` is true: a call + `size` check + 6-compare
  bbox reject (~100+ reads/pop × ~4–5 ns).
- **The known "O(depth) per-pop parent-chain rebuild" (BlockPathfinder ~634–640) is NOT the hot spot
  anymore**: rebuild+reset is ~8% of the warm flood (~75 ns/pop), already tamed by the edit-bbox +
  adaptive `findEditInside` work (see `perf-round2`). The per-READ `kindAt` gate it feeds is 6× bigger.

---

## 4. Allocation attribution (ObjectAllocationSample, full matrix, ~112 s, 29.7 GB sampled weight)

Steady state: **zero** `Nodes` growth, **zero** `EditPool` churn — the ThreadLocal reuse works. What's left
is all **per-search construction + per-plan output**:

| Share | Site | What |
|---|---|---|
| **56%** | `NavGridView.<init>` (long[] 15.3 GB + NavSection[][] 1.5 GB) | the 512-slot chunk cache, allocated+zeroed **per search** (~6.3 KB) |
| **~33%** | `BlockPathfinder.reconstruct` (Object[] 4.8 GB, BlockPos 1.4 GB, BlockPathPlan 1.3 GB, Integer 0.5 GB) + `StepEdits.copy` (1.6 GB) | per returned plan: ArrayList doubling, `Integer` row boxing, one `BlockPos`/waypoint, arena copy-out |
| ~4% | `PathEdits.<init>` (byte[] 0.74 GB + long[] 0.43 GB) | fresh `MovementContext` per search |
| ~3% | `NavGridCuboidsView` (`Cuboid` 0.65 GB + `Cuboid[]` 0.3 GB) | per-search cuboid memo + fresh pending per committed box |
| <1% | `EditScratch.<init>`, `PathEdits.grow` | noise |

(Rows attributed to `PathfinderBenchmark.setup` are trial fixture building, not search cost.)
Absolute rate is harmless for GC (~8 KB/search + plan output; a replan every ~2 s per bot ⇒ ~4 KB/s/bot);
the interest is latency (SHORT pays it as ~455 ns/pop floor) and that it is 100% poolable.

---

## 5. The three in-game samples, explained

**S1 — `2 nodes / 16.3 ms` (first search after boot).** First-touch of the entire pathfinder class graph:
classloading (BlockPathfinder + movements + cuboid package), interpreted/C1 execution, first
`GoalForcedCost.probe`, ThreadLocal init. The lazily-allocated scratch itself is **~41 KB** — allocating it
eagerly saves microseconds of the 16 ms. **JMH cannot see this case at all** (forks=0 reuses a warmed JVM;
warm-up iterations exist precisely to exclude it). Pre-allocation does not fix S1; a boot-time warm-up
search does (it forces classload + JIT on a synthetic grid before any player asks).

**S2 — `8357 nodes / 5680 ns/node` (first big search).** Decomposition: growth-reallocation + first-touch
`EditPool` fill ≈ 2.2 MB ≈ ≲1 ms ≈ **1–2%** (§1). The warm same-bot analog is S3's 2226 ns/node, so
(5680−2226) × 8357 ≈ **28.9 ms ≈ 61% of the search is warm-up** — C2 still compiling the hot loop, cold
i-cache/branch predictors — which pre-allocation cannot touch. Pre-sizing turns the growth hitches into a
smooth run and is worth its ~1 ms, but the headline number is JIT.

**S3 — `7221 nodes / 2226 ns/node` warm pillar (vs 400–700 ns/node floods).** The synthetic warm
edit-bearing flood runs at **952 ns/pop** with this attribution: `kindAt` 49%, loop self 14%, `intern` 11%,
rebuild+reset ~8%, heap ~5.5%, grid ~5.7%. The 400–700 band is the *edit-free* flood (`anyEdits=false`
skips the whole diff machinery — `PathEdits.isEmpty()` fast path); the moment the path carries one edit,
every one of the ~100+ descriptor reads per pop detours through `kindAt`, and per-pop `reset`
(`Arrays.fill` of the grown byte[] table) + chain re-`add` join in. In-game's extra over synthetic
(2226 vs 952) is live-world spread: many distinct chunks/sections (`lookupChunk` misses), varied navtypes,
plus the per-search probe. **Expected if the `kindAt` tax is gated per-pop (below): synthetic ~950 → ~550,
in-game warm pillar ~2226 → ~1300–1500 ns/node.** The parent-chain rebuild alone is only ~75 ns/pop —
fixing just it would not move S3 meaningfully.

---

## 6. What JMH cannot see (honesty box)

- Classloading + interpreter/C1 phases (S1, most of S2) — forks=0 + warm-up iterations exclude them by design.
- Live-`ServerLevel` effects: real chunk spread (synthetic fixture shares 2 section instances), the
  `descriptorAt` live-read fallback, mid-tick cache pollution from the rest of the server.
- GC pauses under real server heap pressure; the bench heap is quiet.
- The bench's TOWER/UPOVER are now ~28–41 pops (macro collapse works) — small-search shapes; the warm
  big-flood loop was added precisely because no JMH scenario reproduces S3's 7 k-node searches.

---

## 7. Recommendations (ordered by expected impact)

1. **Boot-time warm-up search (fixes S1, most of S2 — the actual observed pain).** At server start (or
   first level load), run ~100–150 synthetic searches on a hand-built section map (the
   `PathfinderBenchmark.buildFlatChunks` pattern — no live level needed via `NavGridView.overSections`):
   a SHORT walk, a TOWER-with-corridor, and a far-goal flood. ~1–2 s of one-time background CPU; forces
   classload + C2 for the whole search path AND touches/grows the ThreadLocal scratch to high-water as a
   side effect (making item 2 nearly free). Caveat: it must run on the thread that will search (ThreadLocal)
   — today the tick thread; budget it across a few ticks or accept a one-time startup cost.
2. **Eager-size the ThreadLocal scratch to the measured high-water: `new Nodes(8192, 8192)` + prefill
   `EditPool` to 16384 slots ≈ 2.3 MB/thread.** Kills all mid-search growth (the S2 ~1 ms + the first-flood
   1.6 MB `StepEdits` burst). Favor-CPU-over-RAM says just do it — but book it as smoothing, not as the S2
   fix. If `maxNodes` config is raised, scale: rows ≈ 0.6×maxNodes → nodeHint = next pow2 ≥ 0.8×maxNodes;
   map = same; heap = nodeHint/2. **Threading:** ThreadLocal ⇒ this is per SEARCH THREAD, not per bot — one
   copy today (tick thread), one more per future background-pathfinding thread (2.3 MB each, fine); if
   searches ever fan out per-bot across a pool, cap the pool size.
3. **Gate the `kindAt` read tax per-pop (biggest WARM lever — 49% of S3-shape CPU).** After the per-pop
   rebuild, compute once whether the node's read neighborhood (± movement reach, ~5 blocks) intersects the
   edit bbox; if disjoint (the overwhelmingly common case — a path's edits trail behind it), set a flag so
   `descriptorAt/Of` skip straight to the grid. One AABB test per pop replaces ~100+ per-read gates.
   Expected: warm flood ~950 → ~550 ns/pop; S3 ~2226 → ~1300–1500 ns/node.
4. **Persist the goal probe / base cuboids across replans (biggest SMALL-search lever).** The
   `GoalForcedCost.probe` chain re-extracts the same goal-face cuboids every search: **46% of TOWER, 32% of
   UPOVER_WALL** (~28 µs/search on both). Same grid + same goal ⇒ same committed bases; cache keyed on
   (goal, corridor) with invalidation via the `NavGridUpdater` patch seam. The MULTI scenario exists as the
   regression guard for exactly this change.
5. **Pool the per-search construction** (`NavGridView` chunk-cache arrays, `MovementContext`+`PathEdits`,
   `NavGridCuboidsView`): 56%+ of steady-state allocation (~8 KB/search) becomes a reset of pooled arrays
   (a 4 KB `ccVals` null-fill beats alloc+zero of 6.3 KB). Also de-boxes `reconstruct` (`ArrayList<Integer>`
   rows → int[] scratch) while there. GC hygiene + a slice of the SHORT-search 455 ns/pop floor; do it
   opportunistically, not as a dedicated arc.

**Direct answer to "pre-allocate everything at max on server start?"** — Yes to items 1+2 (warm-up run
that also grows the scratch; ~2.3 MB/thread, trivially within budget), but sizing alone buys only ~1–2% of
S2 and none of S1/S3. The measured big rocks are the per-search goal-probe re-extraction (small searches),
the per-read `kindAt` gate (big edit-heavy searches), and JIT warm-up (the boot samples).

---

*Method files: bench = `PathfinderBenchmark` (+`-Pprof=cpu|jfr`, pinned `-Pscenario=` runs); grown-capacity
numbers from a throwaway reflection probe + 15-s warmed flood loop with in-test JFR (both deleted; bench
worktree restored byte-clean). Raw dumps in the session scratchpad (`cpu-tower.txt`, `cpu-upoverwall.txt`,
`flood-warm.txt`, `alloc-all.txt`, `sizeprobe.txt`).*
