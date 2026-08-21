# PERF-DESIGN — NavGrid build: cutting the neighbor-aware-bit cost

Status: **DRAFT 2026-07-25 — candidates pending (a) a JFR on-CPU confirmation of the hot spot and (b)
owner design-review before any implementation (CLAUDE.md perf process: design-review → paired A/B →
keep only on a ≥3% win, no scenario regressing).** Grounded in the 2026-07-25 NavGrid-build forensic.
Relationship: this is the **performance pass** #6 (capability-aware region graphs) was slotted after —
faster chunk-gen frees the tick budget more-complex/accurate region building would spend
(`DESIGN-capability-aware-region-graphs.md` §15). Companion: `PERF-DESIGN-navgrid-edit-batching.md`
(the per-EDIT `patchCell` path; this doc is the per-BUILD `classifyInto`/`computeFlags`/`computeDepth`
path).

---

## §0. The goal and the target

The tick thread builds each novel chunk's NavGrid, bounded by `pathing.chunkBuildBudgetMs` (default 2.0
ms/tick) + `chunkBuildsPerTick` (default 64) — a slow build directly stalls the server tick
(`ChunkNavLoader.java:72-100,130-154`). The owner's worst-case adversarial chunk-gen (~38 ms, an
uncommitted prior analysis of NavGrid + leaf regions + merge + resource) was **dominated by the NavGrid
build**, and within it by the **neighbor-aware bits**. Committed in-repo: whole-column build ~1 ms
surface / ~5 ms cave (`ChunkBuildBenchmark`-derived, `ChunkNavLoader.java:40,77`); no per-pass µs
committed → the JFR (§3) establishes it.

**The build is three passes over 24 sections/column** (forensic PART 1):

| Pass | What | Class | Reads/output cell |
|---|---|---|---|
| 1 classify | navtype interning (palette decode + `navtypeFor` map) | LOCAL + fixed palette cost | own cell; palette-sized decode/section |
| 2 flags | `NavFlags.compute` — headroom/hazard/slow/**RISKS_GRAVITY half A** | **NEIGHBOR-AWARE (heavy)** | **~27 worst** |
| 3 depth | `floorGap` (↓ sweep) + `runUp` (↑ sweep) nibbles | NEIGHBOR-AWARE (cheap) | 1 each |

**CONFIRMED (JFR on-CPU, `ChunkBuildBenchmark.navgrid`, 2026-07-25):** the cost is **Pass 2 flag
compute**, and within it a **single method, `NavFlags.risksFluidFlow`**:

| | build/column | `NavFlags.compute` | **`risksFluidFlow`** | `computeDepth` | classify+palette |
|---|---|---|---|---|---|
| CAVE (worst) | 3060 µs | 70.9% | **65.9%** | 21.1% | 7.8% |
| SURFACE | 838 µs | 59.8% | **53.1%** | 32.3% | 7.9% |

`risksFluidFlow` runs ~32 `at()` reads/cell (4 neighbours × 2 rows × the two calls at y+1,y+2) with **no
early-out**; CAVE/SURFACE contain zero fluid yet it scans every cell and returns false. `hasPlaceableNeighbor`
does **not** register (early-outs on the first solid neighbour) — so `PLACEABLE` is a non-issue and any
bit-plane work on it (old C2) is dropped. The depth sweeps are the secondary cost (21–32%).

> **ADDENDUM 2026-08-11 — `PLACEABLE_NEIGHBOR` has no reader.** The forensic's "non-issue" verdict stands
> and is now doubly true: audit of `src/main` found the bit's only consumer is the diagnostic
> `/bot probe` (`ProbeCommand`). `MovementContext.placeable` never reads it — it runs its own
> 6-neighbour `descriptorAt` fan-out, on a *different* predicate (not vanilla-REPLACEABLE, vs the bit's
> not-passable-AND-fluid-free). So the cheapest optimization available on this bit is deleting its
> computation, not accelerating it. The bit is UNDER REVIEW, not removed — do not assume either way
> without the owner's call.

---

## §1. Candidate optimizations (ranked; each states bit-exactness, value, risk, effort)

**All must preserve byte-identical grids** (same packed short + depth bytes) — the build feeds the
pathfinder; any change to a flag/nibble is a behavior change. "Bit-identical" below means provably the
same output, not an approximation.

### C1 — Fluid SCATTER, folded into the depth sweep (FLAGSHIP; owner 2026-07-25; bit-identical)

> **SEMANTIC SUPERSESSION — owner ruling 2026-08-21: bit 0 became CELL-CENTRED and SPLIT-OWNERSHIP, and
> this scatter now carries TWO terms behind ONE union mask test.** `RISKY_EDIT` was renamed
> **`RISKS_GRAVITY`** and reframed to *"breaking or placing at THIS CELL drops a gravity block"*
> (DESIGN-fluid-flow-prediction.md §4.3 has the two ratified rules). The consequences for the machinery
> described below:
> - **Ownership is split.** `NavFlags.compute` writes **half A** — `hasGravity(a1)`, a pure upward column
>   read that the existing overscan already serves. `computeDepth`'s ascending sweep scatters **half B**
>   (`scatterGravityNeighbor`, from every UNSUPPORTED gravity cell) on exactly the fluid term's geometry:
>   the shared `NavFlags.SIX` table, the vertical section seam crossed in BOTH directions through the real
>   below/above grids, the lateral chunk face dropped for `EdgeScatter`.
> - **One dispatch, not two.** The sweep tests `d & NavBlock.SCATTER_MASK` (low FLUID bit ∪ GRAVITY bit)
>   once per cell; the ordinary cell pays exactly the one predictable not-taken branch the fluid term alone
>   used to cost, and the two predicates discriminate on the cold side.
> - **The patch window widened `ly+1 → ly+2`.** The gravity gather reads one row DEEPER than the fluid one
>   (its `-y` neighbour's own support), so inverting, an edit can flip half B two rows ABOVE itself — the
>   supported→unsupported transition. `recomputeWindow`'s box is now `(lx±1, ly-3..ly+2, lz±1)`, and the
>   above-seam pass arms at `ly >= 14` (not `ly == 15`), reaching the above section's rows 0–1.
> - **`EdgeFluidScatter` is now `EdgeScatter`** — one class, one face walk, both bits (`crossBitsAt`
>   returns the OR). Its patch-side `neighbourRederive` re-derives **two** cells (`colY` and `colY+1`), the
>   one structural addition the gravity term forces: the `+1` cell reads the edited cell as its own lateral
>   neighbour's SUPPORT.
> - **Perf:** re-measure with `PatchStormBenchmark` (SCATTER/DIG/TOGGLE/**SEAM**) — the widened window and
>   the second gather are squarely in SEAM's blast radius, the same place the fluid above-seam pass cost
>   +3.5%.

> **SEMANTIC SUPERSESSION — owner ruling 2026-08-10: the term is now LAVA-ONLY, UNCONDITIONAL, and
> 6-DIRECTIONAL.** Everything below about *which cells* RISKY_EDIT's fluid term covers is HISTORY. The
> mechanism (a scatter folded into `computeDepth`'s ascending sweep, a patch-path gather, a cross-chunk
> lateral fold) is unchanged and still the shipped design; only the predicate and the offsets changed:
> - **Predicate:** `NavBlock.isLava(d)` — one mask test, no cell-below read, no "flowing" concept. **Water
>   no longer contributes at all.** The flowing test (`fluid(F) && !fluid(F_below)`) was shown by bytecode
>   analysis of vanilla `FlowingFluid.spread` to be close to ANTI-correlated with real spreading: a fluid
>   cell that *can* drain downward does not spread sideways, while impoundment (every cell with fluid
>   below) never matched yet always floods on a break. Modelling vanilla properly needs source-vs-flowing
>   and fluid-level bits `NavBlock`'s 2-bit fluid field does not store, so the term was re-scoped to a
>   blunt lava keep-away zone instead.
> - **Geometry:** a 1-cell dilation over the **6 orthogonal neighbours** (`x±1`, `y±1`, `z±1`), centre
>   excluded — replacing "4 horizontal neighbours at rows `fy-1`/`fy-2`".
> - **Consequences for the machinery:** the `DEPTH_COL_FLUID` per-column carry is DELETED (the predicate is
>   stateless); the vertical section seam is now crossed in **both** directions, so the build scatter takes
>   the above grid as well as the below one, and the patch path grew (a) an explicit below-grid read for its
>   row-0 cells and (b) an **above-seam window** for `ly == 15` edits — the lava term is the first flag fact
>   that reads DOWNWARD across a section face, which the upward-only descriptor overscan cannot serve; the
>   cross-chunk lateral fold (`EdgeScatter`, then named `EdgeFluidScatter`) loses its row offset and its
>   flowing carry (same `colY`).
> - **Perf note:** the non-lava common case is now ONE mask test per cell with no scratch read at all, so
>   the build sweep is cheaper than the flowing variant measured below. The patch path is slightly more
>   expensive: `ly == 15` edits now pay one extra `fillScratch` + a one-row window (`PatchStormBenchmark`
>   re-measure pending).
>
> The authoritative prose lives in `NavFlags`'s class Javadoc — the fluid term under
> **"HAS_FLUID_NEIGHBOR: ANY fluid, unconditional, 6-directional"**, and, since the 2026-08-21
> reframe, bit 0's own term under **"RISKS_GRAVITY: two halves, two owners"** (half A is
> `NavFlags.compute`'s upward read, half B is this same scatter apparatus with a second term).
> The rest of this section is retained as the record of how the scatter mechanism was derived and
> why the seam carry was continuous.

The owner's reformulation, superseding the earlier per-section palette-guard (which had only
section-level fidelity, still paid the 3-row overscan, and would have to persist `hasFluid`/`hasGravity`
never used at search). Replace the per-cell **gather** (each cell scans neighbours for fluid) with a
**scatter** (each fluid source marks the cells it endangers), folded into a sweep we already run.

**Bit-exact derivation from the then-current `NavFlags.compute` (line numbers long since stale — the
method has been rewritten twice since; read it, do not trust a citation).** RISKY_EDIT's fluid term was
`risksFluidFlow(y+1) || risksFluidFlow(y+2)`, and `risksFluidFlow(row)` = "a horizontal neighbour at
`row` is **flowing**", where **flowing(F) ≡ `fluid(F)≠0 && fluid(F_below)==0`** (`:202-203`). So:
- **Detect flowing** in the bottom-up sweep carrying a per-column `fluidBelow` bit (`DEPTH_COL` pattern):
  `flowing = fluid(F) && !fluidBelow[col]; fluidBelow[col] = fluid(F)`. The `fluid()` bit is extracted
  from the descriptor `floorGap` already decodes → ~1 bit-test/cell.
- **Scatter (dilate):** each flowing F sets a bit on its **4 horizontal neighbours at the same row** in an
  `adjFlow` scratch (allocated once, thread-local — the owner's "small scratchpad").
- **Finalize:** `RISKY_fluid(C) = adjFlow[C @ y+1] | adjFlow[C @ y+2]` (the two rows `risksFluidFlow` is
  read at, `:156-157`). Equivalently, F scatters to its 4 horizontal neighbours at rows `fy-1`/`fy-2`.

**Bit-identical:** `adjFlow` is exactly the per-row horizontal dilation of the flowing set that
`risksFluidFlow` gathers. **CORRECTION (verified in impl 2026-07-27 — this doc's earlier "reset at seam"
claim was WRONG).** The gather is NOT air-optimistic downward for the fluid scan: `risksFluidFlow`'s lowest
read is the floor cell's own row `y+1`, whose below-read `y` is always in-scratch or covered by the 3-row
*above* overscan of the section below — never the below-section OOB air. So the per-section gather already
saw the REAL cell below every flowing source, across seams. Therefore the **CONTINUOUS carry (NO seam
reset)** is the bit-identical choice; a seam reset would *over-set* RISKY and DIVERGE (proven —
`FluidScatterIdentityTest` fixture D fails with reset, passes with continuous carry). The "bit-identical vs
seam-correct" split for the intra-chunk *vertical* seam is thus MOOT — they are the same. The only
remaining optimism is the CROSS-CHUNK *lateral* edge (a NavGrid whose lateral neighbour grid isn't in
scratch), which stays air-optimistic and is fixed by the cross-grid **scan-neighbour-face + scatter**
follow-on (step 3, gathers nothing at runtime), NOT by any vertical-seam reset. **Ship continuous-carry.**

**Why it wins (measured target):** in fluid-free terrain there are **zero** flowing cells → zero scatter →
the ~32-read `risksFluidFlow` collapses to **1 bit-test + 2 scratch reads/cell**, and a per-column
"any-flowing" flag falls out of the sweep for free (skip the two `adjFlow` reads when the column had no
fluid) — subsuming the section-guard at cell precision, no persistence, no overscan penalty. Gravity's
RISKY term stays local (bit-tests + one `unsupported` read; the JFR shows it isn't hot; optional symmetric
vertical scatter — owner's call). **Expected: removes ~65% (CAVE) / ~53% (SURFACE) of build (~3× the
worst case); the new secondary is `computeDepth` — a natural follow-on (possibly the same fused sweep).**
**Effort:** moderate (fold detection into `computeDepth`'s ascending sweep, add the `adjFlow` scratch +
dilation, drop `risksFluidFlow` from `computeFlags`, teach `patchCell` to re-dilate an edited fluid
neighbourhood). **Risk:** low-moderate — the risk is bit-exactness (the seam caveat) + the `patchCell`
maintenance, both covered by the grid-diff + `PatchStormBenchmark` gates (§3).

### C2 — Bit-plane precompute for the lateral neighbour scans (high value, HIGH risk; deferred behind C1)

**Mechanism.** For sections C1 *can't* skip (fluid/gravity actually present — and for
`PLACEABLE_NEIGHBOR`, which C1 barely guards since most sections have solids; but see the §0 addendum —
that bit currently has no reader on the search path, so its half of this item is chasing dead work), build per-section bit-planes
(`fluid`, `solid`) once (4096 bits = 64 `long`s) and compute "has-lateral-fluid-neighbour" /
"has-placeable-neighbour" for whole 16-cell rows via **bitwise shift+OR** (±x = intra-lane shift, ±z =
lane shift, ±y = plane shift) instead of per-cell 4–6 scalar reads.

**Value:** converts O(cells × neighbours) scalar reads into O(cells/16 × ops). **Risk: HIGH** — the
CLAUDE.md perf model's standing warning: Hilbert indexing was 2–3× SLOWER, the prefetch stencil regressed
+7–26%; "clever" bit-tricks backfire when the per-op math costs more than the reads it saves. The plane
*build* is itself a pass; net win only if the scans truly dominate the residual (fluid-bearing / dense-cave)
sections. Seam handling (`y+1/y+2` cross-section) is fiddly. **Effort: high. Verdict:** only if the JFR
shows the lateral scans still dominate *after* C1, and only under the full paired-A/B protocol; do not
build speculatively.

### C3 — Fuse vertical traversals / cut descriptor re-derivation (LOW value; the cheap part)

The three vertical walks (flag `y..y+3` reads, `floorGap` ↓ sweep, `runUp` ↑ sweep) are independent, and
`floorGap` re-derives the descriptor `fillScratch` already built (to read one `isStandable` bit). Fusing
them / precomputing a packed `isStandable` plane saves descriptor re-derivations. **But the forensic shows
depth is the CHEAP pass (1 read/cell) and a descriptor re-derivation is one L1 array index** — so this
targets the cheap part and the expected win is small, against real churn to carefully-tuned, bit-exact
sweep code (the depth-maintenance 15-cell-cap soundness is per-cell-interleaved for a reason,
`NavSectionBuilder.java:604-613`). **Verdict: low priority** — revisit only if the JFR surprises us and
depth/`fillScratch` are hot.

### C4 — (STRATEGIC) Off-thread the NavGrid build (biggest lever; moderate-high risk)

The forensic notes the classify kernel is **already written thread-safe** for a future off-thread build
(per-thread scratch, `NavSectionBuilder.java:36-48`; `ChunkNavLoader.java:31`). Moving the whole NavGrid
build (classify+flags+depth) onto a worker and handing the finished `NavSection` back to the tick thread
for region building would remove the **entire** NavGrid cost from the tick budget — not a fraction.
Because the owner's goal is *tick budget for region building*, this is the largest lever: it frees all of
NavGrid's tick time regardless of how fast the passes are. **Risk:** moderate-high — threading + the
`PalettedContainer` reflection (the most version-fragile code) + ordering the region build after its
NavGrid dependency; the region tier must stay tick-confined (memory safety), so only the NavGrid build
moves, the fragment flood does not. **Verdict:** raise as a strategic direction; it *composes* with C1
(a faster off-thread build is still cheaper). Likely its own arc after the C1 quick win.

---

## §2. Recommended sequence (JFR-confirmed)

1. **C1 (the fluid scatter) first** — bit-identical (**CONTINUOUS-carry variant, NO seam reset**), targets
   the measured 53–66%. *(This line said "seam-reset variant" until 2026-08-07, contradicting §C1's own
   2026-07-27 correction eighty lines above it. The reset variant DIVERGES —* `FluidScatterIdentityTest`
   *fixture D fails with reset and passes with continuous carry. Shipped as continuous carry.)*
   Gate: `ChunkBuildBenchmark` navgrid CAVE/SURFACE paired A/B + **bit-exact grid diff** (build a column
   both ways, assert identical packed shorts + depth bytes every scenario) + `PatchStormBenchmark`
   (patchCell fluid re-dilation) + full suite green.
2. **Re-profile after C1.** `computeDepth` becomes the leading cost (21–32% → now dominant). Evaluate a
   depth-sweep tightening or fusing the fluid scatter + floorGap + runUp into one column pass (C3, now
   promoted from low-priority since it's the new secondary — but the depth sweeps are already lean, so
   measure before assuming a win).
3. **C4 (off-thread the whole build)** as a separate strategic arc if the tick-budget goal wants the
   entire NavGrid cost off the tick (composes with C1).
4. ~~C2 (bit-plane placeable)~~ **DROPPED** — the JFR shows `hasPlaceableNeighbor` early-outs and never
   registers as hot.

---

## §3. Measurement plan

- **Benchmark:** `ChunkBuildBenchmark` `navgrid` stage (`ChunkBuildBenchmark.java:376`, the exact 3-pass
  `buildAllSections`), terrain `@Param` UNIFORM_AIR/UNIFORM_SOLID/SURFACE/**CAVE (worst)**. `-Pprof=cpu`
  for the on-CPU JFR (`build/cpu.jfr`, `jfr print --events jdk.ExecutionSample`, filtered to build
  frames). This isolates the NavGrid build (stage A) from region build (stage B `hpaLeaf`).
- **Protocol (CLAUDE.md):** paired interleaved A/B; keep only on a ≥3% targeted win with no scenario
  regressing beyond noise; `forks=0` bimodality caveat → confirm any single-scenario delta with pinned
  `-Pscenario=<X>` fresh-JVM pairs. Bit-exact grid diff is a hard gate (not just tests).
- **Also watch the per-EDIT path:** C1's presence bits must be recomputed on `patchCell`
  (`PatchStormBenchmark` SCATTER/DIG/TOGGLE/SEAM must not regress — a section that gains/loses its last
  fluid block flips the skip guard).

---

## §4. Filled from the JFR (2026-07-25)

- **Hot-method ranking:** `NavFlags.risksFluidFlow` is THE hot leaf (65.9% CAVE / 53.1% SURFACE of the
  whole build); `computeDepth` secondary (21–32%); classify+palette minor (~8%); `hasPlaceableNeighbor`
  negligible (early-outs). → C1 (fluid scatter) is the correct target; placeable work dropped.
- **Absolute µs/column:** CAVE 3060 µs, SURFACE 838 µs (24-section column). C1 should buy back ~½–⅔ of
  CAVE.
- **Palette decode (~8%)** is not worth its own candidate now (dominated by the fluid scan); revisit after
  C1 if the ~8% becomes a meaningful fraction of the reduced total.
- **Artifacts:** JFRs in the session scratchpad (`navgrid-CAVE.jfr`, `navgrid-SURFACE.jfr`). Bench isolation
  gotcha: use `-Pbench="ChunkBuildBenchmark.navgrid"` (regex over `Class.method`) — the `shard*` benches
  otherwise swamp the `forks=0` sample.
