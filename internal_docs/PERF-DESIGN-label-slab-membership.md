# PERF-DESIGN: label-slab EXACT fragment membership in `RegionCostField` slot resolution

> **STATUS: ADOPTED (s54, 2026-07-09 — measured, KEPT).** Owner pre-approved pursuing this
> (RATIFIED-DIRECTION, HANDOFF menu item 1), gated on measurement; the §8 paired interleaved
> A/B cleared the bar: **HONEYCOMB −12.3%** (targeted; expansions 254 → 101 from honest
> guidance), GOAL scenarios −0.9/−1.7%, every guard flat. Design-review-first satisfied by
> the owner's standing ratification of the direction; the semantic delta below is the part that
> would otherwise need a fresh sign-off, so it is called out explicitly.

**Scope:** `RegionCostField.costAt` slot resolution (the per-A*-node field read, 2–3× per block
expansion — PERF-AUDIT-region-field.md §3), its build-side bake, `FragmentBuilder.build` label
emission, `RegionFragments` label storage. The forward region A*, `BlockPathfinder`, and the
null-field (baseline octile) path are untouched.

## §1 Problem (PERF-AUDIT §3 items 2, 4, 5)

`costAt` resolves "which fragment of this region encloses the queried cell" by
**nearest-centroid guesswork** on every read: 2 `ThreadLocal.get()`s + a `fragmentRecord` hash
probe + (for a k-fragment region) up to 6k packed-footprint decodes + 3k long divisions — all
recomputing values immutable per built region — and, whenever the guess lands on a slot the
goal-rooted Dijkstra never settled, a **63-slot linear fallback scan**. The s54 HONEYCOMB
coverage audit measured the bill: 100% of its 589 per-search `costAt` reads run the 4-fragment
centroid loop and 13.2% additionally pay the 63-scan. The guess is also simply **wrong** near
fragment-centroid boundaries (the whole reason the fallback exists): a tunnel cell near a
region's ±X end reads a sealed side pocket's slot — or its UNREACHED slot, then the scan's
"cheapest reached" — instead of the tunnel fragment the bot is actually in.

## §2 Mechanism

Exact per-cell membership already exists in the codebase: the `186669c` dig-flood machinery
labels every cell of a region with its kept fragment id in one flood
(`FragmentBuilder.labelAll` / `FragmentLeafComputer.labelFragments`), unit-pinned
cell-for-cell equal to `fragmentContaining`. Three-part change:

1. **Emit labels during the leaf build itself** (`FragmentBuilder.build`, `G == 16` only): the
   build's flood already visits every passable cell and its per-component queue holds exactly
   the component's cells, so stamping the kept id is one extra pass over the queue (the
   `labelAll` stamping idiom) + one 4 KB `-1` prefill — **no second flood**. The slab is stored
   on the leaf's `RegionFragments` record (lazily allocated `byte[4096]`, reused across
   rebuilds) and committed only when **≥2 fragments survive and the region did not collapse**
   (single-fragment membership is trivial; collapsed regions answer −1 everywhere, matching
   `fragmentContaining`'s contract). Marginal leaf-build cost ≈ 1 µs on ~13 µs, maintenance
   cadence only.
2. **Bake per-region slabs into the field at build time** (`RegionCostField.bakeSlabs`, called
   by `costToGoalField` right after `setFloor`, on the build thread): for every box region with
   **≥2 reached fragment slots**, copy the leaf record's label slab (`byte[4096]` clone,
   ~0.2 µs) into a field-owned `slabs[regionIndex]`. Regions with ≤1 reached slot need no slab:
   whatever fragment the old code resolved, the returned value collapsed to the single reached
   slot (directly or via the fallback scan) or to the frontier floor — so a per-region
   precomputed **`cheapSlot[]`** (argmin over reached slots, maintained incrementally in
   `record()`, old scan's lower-index tie-break preserved) reproduces those answers exactly.
   The ≥2-reached set is tracked by a per-region `reachedFrags[]` counter, also maintained in
   `record()` (O(1) per settle).
3. **Rewrite `costAt` slot resolution** to: bounds check → `slabs[ri]` null test → (slab
   present: one byte read + one reached test, miss ⇒ `cheapSlot[ri]`) / (no slab:
   `cheapSlot[ri]`) → existing gradient math. Deleted from the read path: both ThreadLocals
   (removed from the class), the `fragmentRecord` hash probe, every footprint decode, every
   division, and the 63-slot fallback scan. `RegionCostField` drops its `grid` field entirely.

### Probe data that shaped §2 (temp instrumentation, removed)

Per-scenario field-box census at 098e66c (regions with ≥2 reached fragment slots — the
slab-bake set — plus one label-flood timing):

| Scenario | box regions | ≥1 reached | **≥2 reached** | max | one flood costs |
|---|---|---|---|---|---|
| GOAL_IN_WINDOW | 441 | 441 | **0** | 1 | ~27.3 µs (4-frag start region) |
| GOAL_NOT_IN_WINDOW | 637 | 616 | **0** | 1 | ~10.7 µs |
| HONEYCOMB | 637 | 616 | **7** (the belt) | 3 | ~11.6 µs |

The flood timing is why the bake **copies build-time labels instead of re-flooding**: 7 fresh
label floods ≈ 81 µs would have REGRESSED the 833 µs HONEYCOMB op by ~10%, swamping the read
win. 7 slab copies ≈ 1.5 µs do not.

## §3 Semantic delta (the point, but a behavior change — stated, not snuck in)

Exact membership CHANGES `costAt` values wherever nearest-centroid used to mis-assign between
two REACHED slots, or used to fall back. Enumerated:

- **Regions with ≤1 reached slot (the overwhelming majority — 100% of the box regions in both
  pre-existing FullSearch scenarios): values byte-identical** to the old code (see §2 item 2's
  argument; float-tie ordering of the old scan is preserved by the tie-break).
- **≥2 reached slots + slab present:** a cell inside a reached fragment now reads **its own
  fragment's slot** (old: the nearest-centroid one — HONEYCOMB's +X-end tunnel cells read the
  sealed +X pocket's slot, whose cost is the *optimistic-air outside route*, understating the
  real corridor cost). A cell labeled −1 (solid / non-occupiable component) or inside an
  unreached fragment reads `cheapSlot` (old: centroid pick, then the same cheapest-reached
  fallback if unreached — so −1/unreached cells can differ only when the centroid pick was
  reached-but-not-cheapest). Guidance-only surface; the block tier `max()`es it against the
  octile, and honest (higher) values strengthen goal-ward pull.
- **≥2 reached slots, no slab** (record-only headless grids; a leaf whose record was
  hand-seeded rather than built; legacy records from before this change until their next
  rebuild): degrades to `cheapSlot` — the old fallback semantics — instead of nearest-centroid.
  Rare, guidance-only, self-heals on leaf rebuild.
- **Test pins:** no `FullSearchHeadlessTest` pins exist (it asserts plan-not-null only); no
  other unit test asserts `costAt` on a multi-reached region. No pin updates were needed. The
  FullSearch bench dry-run guards (FIND-not-partial) still hold — verified before benching.

## §4 Thread-safety (the audit's latent cross-thread read — surface SHRINKS, not grows)

- **Where the slab data lives:** per-leaf labels live on the `RegionFragments` record (the
  EXISTING shared-mutable pyramid surface, written only by the single-threaded tick/maintenance
  leaf build); per-field slabs are **field-owned defensive copies** made at build time.
- **Mutated after build?** The field's `slabs`/`cheapSlot`/`reachedFrags` are written only
  during `costToGoalField` (build thread — always the server/tick thread per PERF-AUDIT §1) and
  are frozen before the field is published (same publication path as the existing
  `cost[]`/`exit*[]` arrays: cached reference in `PathPlan`, snapshotted into `SearchRequest`).
  A later leaf rebuild mutates the RECORD's labels, never a published field's copy.
- **Relation to the existing exposure:** the old read path escaped into the mutable
  `CostPyramid` on **every worker-thread read** (`costAt → fragmentOf → fragmentRecord →
  rowIfPresent`). The new read path touches **only field-owned arrays** — the latent
  cross-thread read is REMOVED from `costAt` entirely (build-time reads of the record happen on
  the same thread that mutates it). No new mutable-shared reads are added. (Full field
  self-containment for the async-region-tier Phase 0 is thereby already delivered for the READ
  path; Phase 0's remaining scope is the build/publication side.)

## §5 Hot-path budget & house rules

- `costAt` runs 2–3× per block-A* expansion: the new resolution is 2 array reads + 1–2
  predictable branches (slab null test dominated by the no-slab common case; the byte read only
  in multi-fragment regions), zero allocation, zero ThreadLocal, zero hash probes, zero
  division. Strictly branch-lighter than what it replaces.
- No per-read allocation anywhere; per-BUILD allocation adds 2 small per-region arrays
  (~5 B/region vs the existing 1,260 B/region ×63 slot arrays) + one 4 KB slab copy per
  multi-reached region (replan-cadence, isolation-required — pooling would violate the
  write-once snapshot the in-flight workers rely on).
- Null-field searches (`PathfinderBenchmark` SHORT/MULTI) never construct or read a field —
  the change cannot touch them (guard must read flat).

## §6 Expected win / measurement (the keep decision)

- **Targeted:** FullSearchBenchmark HONEYCOMB — deletes the 100%-covered per-read centroid
  loop (~589 reads/op) and all 78 fallback scans; estimate 4–10% plus any expansion-count
  improvement from honest guidance. GOAL_IN/NOT_IN_WINDOW should improve slightly (each read
  still drops 2 ThreadLocal gets + a hash probe) with byte-identical values.
- **Guards (flat):** PathfinderBenchmark SHORT + MULTI (field null), RegionPathfinderBenchmark
  (forward A* untouched), RegionFieldBuildBenchmark 16-combo sweep (build side: ctor + record()
  bookkeeping + bake loop; expected ≤ ~2%, watch it).
- Keep-bar per the standing protocol: ≥3% on the targeted field-guided scenarios, nothing
  regressing beyond noise, full `:1.21.11:test` green.

## §7 Risk

- **Medium-low.** The read rewrite is small and self-contained; the value delta is confined to
  multi-reached regions (measured: zero such regions in both pre-existing scenarios).
- Label/record skew: a section edited between leaf build and field build yields labels
  consistent with the RECORD (whose footprints/costs the Dijkstra priced) rather than the live
  section — the preferable side of the skew, and no worse than the old centroid math which read
  the same record.
- `FragmentBuilder.build` gains ~1 µs on multi-fragment leaf builds (maintenance cadence);
  single-fragment leaves pay only the 4 KB prefill (~0.1 µs). Memory: +4 KB per ever-multi-
  fragment built leaf (favor-CPU-over-RAM: negligible against the nav grid).
- Reverting is one `git revert` (all changes in one commit).

## §8 Measured (s54, 2026-07-09) — **KEPT**

Paired interleaved A/B (A = `098e66c`, B = `acd6fa1`), sweeps A1,B1,A2,B2 each running
FullSearch → Pathfinder SHORT (pinned) → Pathfinder MULTI (pinned) → RegionPathfinder →
RegionFieldBuild. JMH avgt cnt 6, µs/op, ± = 99.9% CI, forks=0 harness, fresh test-worker JVM
per invocation, JDK 21, active node 1.21.11.

### FullSearchBenchmark (targeted)

| Scenario | A1 | B1 | A2 | B2 | A mean | B mean | Δ B vs A |
|---|---|---|---|---|---|---|---|
| GOAL_IN_WINDOW | 937.7 ±4.5 | 932.7 ±8.2 | 929.9 ±7.5 | 917.5 ±6.1 | 933.80 | 925.09 | −0.9% |
| GOAL_NOT_IN_WINDOW | 796.6 ±13.9 | 772.8 ±9.1 | 772.2 ±14.6 | 769.2 ±22.5 | 784.41 | 771.02 | −1.7% |
| **HONEYCOMB** | 826.8 ±7.8 | 721.2 ±4.8 | 813.5 ±6.6 | 717.4 ±13.4 | 820.19 | 719.30 | **−12.3%** |

HONEYCOMB's dry-run sanity: expansions 254 (A) → **101** (B), plan 39 steps and FIND on both
sides — the win is part read-path (no centroid loop / fallback scan), part BEHAVIOR (exact
membership stops +X-end tunnel cells reading the sealed pocket's understated optimistic-air
cost, so the field pulls harder). GOAL_IN/NOT_IN expansions identical (15/245) — the §3
byte-identity class held exactly.

### Guards (all flat)

| Bench / scenario | A runs | B runs | A mean | B mean | Δ |
|---|---|---|---|---|---|
| Pathfinder SHORT | 13.850, 13.403 | 13.631, 13.320 | 13.627 | 13.476 | −1.1% (session drift, monotone across all 4 runs) |
| Pathfinder MULTI | 245.5, 243.8, **246.0, 245.5** | 248.9, 249.2, **246.5, 246.1** | 245.20 | 247.66 | +1.0%* |
| Region OPEN_CAVERN | 0.800, 0.800 | 0.803, 0.799 | 0.800 | 0.801 | +0.1% |
| Region SEALED_DIG | 0.240, 0.240 | 0.242, 0.240 | 0.240 | 0.241 | +0.4% |
| Region MULTI_FRAGMENT | 0.442, 0.437 | 0.441, 0.435 | 0.440 | 0.438 | −0.3% |
| Region LONG_CASCADE | 5.925, 5.921 | 5.999, 5.917 | 5.923 | 5.958 | +0.6% |
| Region ZERO_CAP | 0.507, 0.505 | 0.483, 0.512 | 0.506 | 0.498 | −1.7% (known-jittery) |

\* MULTI's first two B runs read +1.8%; MULTI executes ZERO changed code (block tier only,
field null), so per the pinned-rerun rule two extra interleaved pairs were run (the bold
values): +0.2% and +0.2% — the delta did not reproduce; verdict FLAT. (Cross-session context:
the PRE-baseline A itself read 249.9/248.3 — identical code wobbles ±1–2% between sessions.)

### RegionFieldBuildBenchmark (build-side guard — 16 combos, µs/op, A/B means over 2 runs each)

| box | scenario | term | A mean | B mean | Δ |
|---|---|---|---|---|---|
| 3 | SURFACE | EXHAUST | 87.25 | 87.39 | +0.2% |
| 3 | SURFACE | FATSKEL | 90.94 | 90.80 | −0.2% |
| 3 | BURIED | EXHAUST | 49.50 | 49.30 | −0.4% |
| 3 | BURIED | FATSKEL | 53.23 | 52.61 | −1.2% |
| 5 | SURFACE | EXHAUST | 182.39 | 177.28 | −2.8%† |
| 5 | SURFACE | FATSKEL | 177.57 | 173.43 | −2.3%† |
| 5 | BURIED | EXHAUST | 142.32 | 136.37 | −4.2%† |
| 5 | BURIED | FATSKEL | 114.71 | 112.13 | −2.3%† |
| 7 | SURFACE | EXHAUST | 479.20 | 474.40 | −1.0% |
| 7 | SURFACE | FATSKEL | 389.80 | 387.59 | −0.6% |
| 7 | BURIED | EXHAUST | 444.67 | 437.18 | −1.7% |
| 7 | BURIED | FATSKEL | 302.31 | 300.17 | −0.7% |
| 10 | SURFACE | EXHAUST | 1550.79 | 1547.30 | −0.2% |
| 10 | SURFACE | FATSKEL | 1263.54 | 1262.36 | −0.1% |
| 10 | BURIED | EXHAUST | 1534.13 | 1518.25 | −1.0% |
| 10 | BURIED | FATSKEL | 1050.39 | 1047.78 | −0.2% |

† The A1 sweep ran hot on the box-5 combos (first RegionFieldBuild sweep of the session, e.g.
5/BUR/EXH A1 = 147.4 vs PRE-baseline 136.3 and A2 = 137.3) — the honest read is FLAT, not a B
win. No combo regressed: the feared bake/bookkeeping cost is invisible on this
single-fragment world, as designed.

**Verdict against the decision rule:** HONEYCOMB −12.3% ≫ the 3% bar on the targeted
field-guided scenario; both other FullSearch scenarios flat-to-better; every guard flat.
**KEPT** (commit `acd6fa1`). Tests 223/0 green, no pre-existing pin moved.
