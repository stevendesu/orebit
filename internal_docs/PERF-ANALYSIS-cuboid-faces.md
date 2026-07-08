# PERF ANALYSIS — cuboid "faces": can we ignore top/bottom exits when a lateral macro dominates?

> **STATUS: PROPOSED (analysis — no change recommended without owner ratification; this is a BEHAVIOR
> change, not a mechanical optimization).**
> Bottom line up front: the hypothesis's underlying instinct — *don't pay vertical-cuboid costs when a
> lateral route dominates* — is **already implemented**, twice over: (1) Option B primary-axis gating
> (only the movement travelling the dominant start→goal axis extracts a cuboid at all;
> `BlockPathfinder.java:692-698`), and (2) `MacroJump`'s goal bound (a vertical macro not toward the
> goal degrades to a single micro step; `MacroJump.java:85-88`). There is **no per-face exit
> enumeration anywhere in the subsystem** to suppress — no code iterates face cells — so the imagined
> cost line-item mostly does not exist. What vertical cost remains on a lateral-dominant search is the
> once-per-search `GoalForcedCost.probe`, and its vertical build face `(Y,+1)` is the anti-flood
> premium itself — the one face the design deliberately refuses to drop
> (`GoalForcedCost.java:246-248`). Recommendation: **do not suppress faces**; if the owner wants a win
> in this area, pursue the already-open "persist goal-probe / base cuboids across replans" item
> (`PERF-PROFILE-2026-07.md:36`) instead. Also: `CUBOID-PERF-OPTIONS.md:12-14` still lists Option B as
> "OPEN/parked … REVERTED" — stale; the gate is live (commit `37e8033`, 2026-06-26, never reverted
> per `git log -S macroAxis`).

Owner hypothesis under analysis (s52): *"if a large macro lateral move is possible, it dominates
pillaring or mining down, so maybe we ignore top and bottom faces or something."*

Binding constraints respected throughout (settled — do not re-litigate; `MACRO-IMPLEMENTATION.md:3-8`
§0 and the `macro-movement-non-negotiables` memory):

1. **The macro move is a full cuboid, NOT a 1-D walk.** Jump validity is provable only because the
   whole orthogonal cross-section is uniform for the whole jump; any "cheap dominance probe" that
   walks one line is the rejected shortcut in a new hat.
2. **The escape-hedge MUST divide by the movement's per-step cost**
   (`escapeBound = ceil(orthFace / moveCost)`, `MacroJump.java:91-92`). Never drop the division.
3. (s43 addendum, same memory) Never sum forced-cost premiums across stacked cuboids.

And the byte-identical rule (CLAUDE.md, Performance model): anything that changes f-values or
candidate order is a **BEHAVIOR change** and gets treated as one. Every suppression variant below
changes the candidate set. None is a mechanical optimization.

---

## §1 Current face-emission inventory — what actually gets emitted, and where

**The premise "exit candidates on faces of a cuboid" does not match the architecture.** The subsystem
never enumerates a cuboid's faces and never emits per-face-cell exit candidates. Candidates come from
the *movements*, each emitting **at most ONE macro candidate along its own fixed travel direction**,
with the cuboid used only to *bound the jump length*:

| Movement | Travel (axis, sign) | Cuboid query | Gate | Emit |
|---|---|---|---|---|
| `Pillar` | (Y, +1) | `Pillar.java:126` | `MACRO_MOVES && cuboids != null && ctx.macroAxis() == AXIS_Y` (`Pillar.java:111`) | one candidate at `(x, y+J, z)` (`Pillar.java:175`) |
| `MineDown` | (Y, −1) | `MineDown.java:98` | same, `macroAxis() == AXIS_Y` (`MineDown.java:83`) | one candidate at `(x, y−J, z)` (`MineDown.java:121`) |
| `Traverse` | (X or Z, ±1), per cardinal | `Traverse.java:214` | same, `travelAxis == ctx.macroAxis()` (`Traverse.java:128-130`) | one candidate at jump distance per on-P cardinal (`Traverse.java:300`) |

Everything else about "exits" is implicit, and deliberately so:

- **Lateral exits from a vertical run are never emitted as face candidates.** They are recovered by
  the escape-hedge: `MacroJump.steps` caps the jump at
  `min(travelExtent, goalBound, ceil(nearestOrthogonalFace / moveCost))` (`MacroJump.java:75-95`,
  `Cuboid.nearestOrthogonalFace` at `Cuboid.java:112-118`), so the landing node sits close enough to
  any potential side exit that the ordinary micro/macro candidate set at *that* node finds it. The
  cuboid's side faces are consumed as **one scalar** (the min clearance), never iterated.
- **A vertical macro away from or level with the goal already degrades to a micro step.**
  `goalBound = max(0, sign·(goalCoord − cellCoord))`; `hard ≤ 1 → return 1` (`MacroJump.java:83-88`).
  So on a purely lateral goal, even if Pillar's macro path ran, it would emit the same 1-step
  candidate as the micro path.
- **Option B (primary axis P) is the big one: on a lateral-dominant search, vertical cuboid work is
  already OFF.** `P = argmax(|dx|,|dy|,|dz|)`, tie-break X > Z > Y
  (`BlockPathfinder.primaryAxis`, `BlockPathfinder.java:1059-1066`, wired at `:697-698`). When
  P = X or Z, `Pillar`/`MineDown` take their micro branch and **never call `cuboidAt` on Y at all**
  (`Pillar.java:109-119`, `MineDown.java:78-88`); when P = Y, `Traverse` takes its micro branch and
  extracts nothing on X/Z. Exactly one axis's cuboids are extracted per search by the movements.
- **The only place all six "faces" are enumerated is `GoalForcedCost.probe`** — once per search, at
  the *goal*, as a heuristic lower-bound probe, not candidate emission
  (`GoalForcedCost.java:239-247`). It already excludes the far face along the dominant start→goal
  axis (`:246-247`), with the vertical build face `(Y,+1)` **exempt from exclusion** because a goal
  floating over air forces pillar-up from either side (`:59-62`, `:191-195`). Relation to the
  hypothesis: the far-face exclusion is the one ratified "ignore a face" mechanism in the codebase,
  and it exists to make the premium *larger* (anti-flood), not to save probe cost — and it was
  accepted as *mildly inadmissible* only after explicit deliberation (`GoalForcedCost.java:52-58`).
  Its two vertical faces are load-bearing in opposite ways: `(Y,+1)`'s stand cell (the block under
  the goal) is the common standable short-circuit that kills the premium for on-ground goals
  (`:270-273`), and its air-cuboid branch IS the pillar premium (`:277-294`).

**Doc/code discrepancy found while auditing:** `CUBOID-PERF-OPTIONS.md:12-14` ("STATUS 2026-07-03,
unchanged since") records Option B as *"OPEN/parked. An early version shipped and was REVERTED —
the node-count tradeoff (~20× up-and-over explosion risk)"*. The code says otherwise: the gate is
present in all three movements and `git log -S macroAxis` shows only the introducing commit
`37e8033` (2026-06-26) plus an unrelated touch (`21f68d3`) — no revert. The feared up-and-over
explosion did not materialize because UPOVER's geometry has dy=30 > dx=dz=15, so **P = Y and the
Y-macro (the one UPOVER needs) is the one kept** (`PathfinderBenchmark.java:47-52`). The condensed
status doc should be corrected. (Similarly `MACRO-IMPLEMENTATION.md:28` says the `MACRO_MOVES` flag
was removed; it is live at `BlockPathfinder.java:186`.)

## §2 Cost share of the "vertical faces" — the geometry, and why it doesn't map to a live cost

**The face-area intuition, made explicit.** For a W×H×D box the face areas are:

```
top + bottom  = 2·W·D
4 side faces  = 2·H·(W+D)
vertical share = W·D / (W·D + H·(W+D))
```

| Shape | W×H×D | top+bottom | sides | vertical share |
|---|---|---|---|---|
| Flat-world slab (OPEN-like) | 32×4×32 | 2048 | 512 | **80%** |
| Wide room | 16×6×16 | 512 | 384 | 57% |
| TOWER air column (corridor-clipped) | 7×30×7 | 98 | 1680 | 6% |
| 1×30×1 shaft (NON-NEG 1's example) | 1×30×1 | 2 | 240 | <1% |

So *if* face cells were iterated, wide-flat cuboids would indeed be top/bottom-dominated ~4:1 and
shafts side-dominated ~100:1. **But no loop in the subsystem iterates face cells.** Where the real
money goes:

- **Extraction scales with VOLUME, not face area.** Stage 1 grows the orthogonal slab one edge-row
  at a time — total reads ≈ slab area A⊥ plus one failing row per frozen face
  (`CuboidExtractor.java:128-176`); stage 2 re-scans the whole A⊥ slab once per layer over travel
  extent T — ≈ A⊥·T = the box volume (`:196-201`). Measured: stage 2 dominates stage 1 ~30:1, and
  cuboid build was ~82% of TOWER search CPU pre-optimization (`CUBOID-PERF-OPTIONS.md:21-22`).
  The E4 runUp nibble then removed ~75-80% of that bill on the vertical-scan components
  (`PERF-DESIGN-runup-nibble.md:3-8`; `CuboidExtractor.java:66-72`, `runSkipUp` at `:409-424`,
  column mode at `:342-353`). Note the irony for the hypothesis: the *accelerated* scans are
  precisely the vertical ones — Y-column reads are the cheap direction now.
- **Extraction is per-REGION, not per-node or per-face.** `NavGridCuboidsView` memoizes maximal
  boxes per axis and answers interior cells by `contains()` (`NavGridCuboidsView.java:97-136`,
  `MAX_BOXES = 256` at `:83`). A uniform region is extracted once per search per axis — and Option B
  means the movements populate exactly ONE axis's cache.
- **Emission cost is O(J) per emitted candidate, one column/row, not a face**: the k = 1..J edit-fold
  loops (`Pillar.java:143-150`, `MineDown.java:106-117`, `Traverse.java:247-276`). J is capped by
  the escape-hedge, which for the *expensive* vertical moves is small by construction
  (NON-NEGOTIABLE 2): a pillar step ≈ `4.633 + ~6 ≈ 10.6` ticks (`Pillar.java:71-78`), so even a
  clearance-7 corridor column gives `escapeBound = ceil(7/10.6) = 1` — vertical macro folds are
  typically a handful of iterations. Cheap lateral walks (4.633/step) get the long jumps, i.e. the
  lateral moves are already the ones doing the big collapses.

**So what fraction of cost is attributable to vertical (top/bottom) faces? Effectively zero as
"faces," and — on lateral-dominant searches — near-zero as vertical anything:**

- P = X/Z search (OPEN, CLIFFS, SHORT): movements extract no Y-cuboid, emit no Y-macro. Vertical
  candidates are the plain 1-step micro Pillar/MineDown emits, whose cost is a few grid reads each —
  and which the open heap then never pops when a lateral route is cheaper (A* already "ignores"
  dominated candidates for the price of a heap push).
- The residual vertical-cuboid cost on ANY search is `GoalForcedCost.probe`'s face extractions at
  the goal (up to 5 after far-face exclusion, spanning all 3 axes): once per search, but part of the
  per-search setup bill the SHORT guard exists for. Measured: **cuboid extraction is 38-45% of
  small-search CPU, of which ~46% (TOWER) / 32% (UPOVER_WALL) is the probe re-extracting the same
  goal-face cuboids every replan** (`PERF-PROFILE-2026-07.md:22-24`). This is the only real number
  the hypothesis could chase — and the fix on file is *persistence across replans*
  (`PERF-PROFILE-2026-07.md:36`), not face suppression: the probe's vertical faces are exactly the
  ones that can't be dropped (§1, §3b).

## §3 Would suppressing top/bottom exits change search RESULTS? YES — concretely

Any suppression changes the candidate set → g/f values, expansion order, and returned paths change.
Under the byte-identical rule this is a behavior change requiring owner design review regardless of
its benchmark delta. Where it is also *wrong*:

**(a) Pits, pockets, quarries — the bottom face is the route.** "A large lateral macro exists" is
TRUE almost everywhere on any flat or open map — flat worlds are one giant lateral cuboid. A
dominance trigger keyed to lateral-macro size therefore fires precisely on the terrain where
MineDown-into-a-cave / descend-into-a-quarry goals live. Suppressing (Y,−1) there makes a goal at
the bottom of a shaft unreachable except by an expensive spiral of lateral+Descend moves — or not at
all if the pocket has no walkable entry. The gather-adjacent-but-occluded bug (s51) was exactly a
"plan ends adjacent, nobody breaks the last block" failure; face suppression would manufacture a
whole family of those.

**(b) Buried/elevated goals straight up or down — TOWER is the counter-example, by name.** TOWER is
start (8,0,8) → goal (8,30,8) over flat ground (`PathfinderBenchmark.java:41-44`, `:126-129`): huge
lateral cuboids available in every direction, and the ONLY route is up through the "top face."
Macro-Pillar collapse is what shrank TOWER from a ~10k-node budget-burn flood to ~28 pops
(`PathfinderBenchmark.java:42-43`, `:74` history note). "Lateral macro exists → suppress vertical"
inverts that fix and re-opens the open-air pillar-cone flood the whole subsystem was built to kill.
SPIRAL likewise (goal at top, P = Y, `PathfinderBenchmark.java:217-218`).

**(c) The escape-hedge's justification is voided (NON-NEGOTIABLE 2).** The hedge's argument
(`MacroJump.java:26-36`) is: *after* a jump, the cheapest alternative exit may lie just past the
orthogonal face in unscanned terrain, so bound the jump such that walking to that hypothetical
cost-1 exit stays competitive. That bound on sub-optimality holds only if the search, at the landing
node, can actually *take* the alternatives — including the vertical ones. Suppress vertical
candidates at landing nodes and the hedge is hedging toward moves that no longer exist: the
`/moveCost` arithmetic is untouched (the non-negotiable is formally respected) but its guarantee is
not — the sub-optimality bound silently becomes unbounded in the suppressed direction. Worse, a
"does a big lateral macro exist?" trigger is itself a cuboid-shaped question; answering it cheaply
with a 1-D run-length probe is NON-NEGOTIABLE 1's rejected shortcut verbatim, and answering it
honestly costs an extraction — i.e. the trigger costs what it purports to save.

**(d) Primary-axis-P interaction — the proposal fights the mechanism that already won.** UPOVER has
dx = dz = 15, dy = 30 → **P = Y: the vertical macro IS the main move**
(`PathfinderBenchmark.java:47-52` — Pillar macro-collapses on-P while Traverse micro-walks off-P).
A lateral-dominance suppression rule would either (i) key off lateral cuboid size and suppress the
on-P vertical macro UPOVER needs, or (ii) key off P itself — in which case it is Option B, which is
already shipped. And when P = X/Z, the only vertical emissions left are the 1-step micros;
suppressing *those* breaks completeness outright (a wall between bot and goal becomes impassable —
UPOVER_WALL's climb, `PathfinderBenchmark.java:53-57`).

## §4 Safer variants — assessment and recommendation

All variants below are behavior changes under the byte-identical rule (changed candidate sets ⇒
changed f-values/expansion order). Assessed against the two non-negotiables and against what already
exists:

- **V1 — conditional suppression: vertical exits off when goal outside the cuboid's XZ footprint AND
  lateral strictly dominates.** Substantially subsumed: goal-not-above ⇒ `goalBound = 0` ⇒ the
  vertical macro is already a 1-step micro (`MacroJump.java:85-88`), and on P = X/Z there's no
  vertical extraction to save (Option B). Remaining savings ≈ the cost of a heap push per never-popped
  micro candidate; remaining risk = every failure in §3a/§3d(ii). A "provable dominance" condition
  strong enough to be safe must reason about unscanned terrain past the box — which is exactly what
  the escape-hedge already does, conservatively, without deleting candidates. **Not recommended.**
- **V2 — lazy/deferred vertical emission (emit only if lateral candidates exhaust).** A* correctness
  requires a node's candidates at expansion time; "exhausted" is a global property discovered only at
  termination, so this needs re-open/re-expand machinery (a second search phase), is
  order-dependent, and duplicates what the open heap already does for free: an expensive vertical
  candidate that never becomes competitive is pushed once and never popped — its expansion work is
  *already lazy*. Suppression can only save the push, at the price of completeness plumbing.
  **Not recommended.**
- **V3 — cost-ordering, no suppression (status quo).** Lateral dominance is already expressed where
  it belongs: in the costs. Walk 4.633/step vs pillar ~10.6 and mine-down `4.633 + breakCost`
  (`Pillar.java:71-78`, `MineDown.java:56-62`) means a viable lateral route wins on f without any
  rule. Byte-identical trivially, both non-negotiables intact. **This is the recommendation for the
  candidate set: change nothing.**
- **V4 — the real lever: persist goal-probe / base cuboids across replans.** Attacks the measured
  bill (§2: probe re-extraction is ~⅓-½ of the 38-45% small-search extraction share) without
  touching candidate sets or either non-negotiable. Already an open §7 item
  (`PERF-PROFILE-2026-07.md:36`), and the MULTI scenario was built to referee exactly this change
  (`PathfinderBenchmark.java:66-72`). Needs an invalidation story (grid patch / config rebake /
  PathEdits emptiness — note `GoalForcedCost.java:262-263` relies on the probe running with empty
  edits). Per-search results must stay byte-identical to a fresh extract over identical committed
  state; the s42 chunk-cache revert is the cautionary tale that even this must survive paired A/B on
  SHORT/MULTI. **If the owner wants work scheduled from this analysis, it should be this — as its own
  design doc under the design-review-first rule.**

**Recommendation: do not suppress top/bottom faces or vertical exits.** The hypothesis correctly
smells a cost (the cuboid/probe setup tax) but attributes it to a mechanism (per-face exit emission)
that doesn't exist; the suppressions available all trade correctness in the scenarios the subsystem
was built for (TOWER, UPOVER, shaft descent) for savings the existing Option B + goalBound + heap
laziness already capture. Fix the stale `CUBOID-PERF-OPTIONS.md` Option B status line; pursue V4 if
the setup tax is worth another arc.

## §5 Measurement plan (only if a variant is ratified anyway)

Per the non-negotiable process: owner design review first; then paired interleaved A/B on the full
JMH suite; keep only ≥3% targeted win with no scenario regressing beyond noise; suspicious
single-scenario deltas re-confirmed with pinned `-Pscenario=<X>` fresh-JVM pairs.

- **Vertical-exit-dependent — must not change RESULTS (path found, cost, pops):** TOWER,
  UPOVER_OPEN, UPOVER_WALL, SPIRAL (all P = Y). For any suppression variant, add a result-identity
  harness run first: capture returned waypoints + `lastExpansions()` per scenario before/after — a
  single changed pop count on these is the §3 behavior change materializing, and disqualifies the
  variant as a "perf" change regardless of speed.
- **Lateral-dominant — where the win must show, if any:** OPEN, CLIFFS (P = X). Expected honest
  outcome for V1/V2: ~0 (the work they'd skip isn't being done — §2).
- **Setup-tax guards — where V4's win must show and V1/V2's trigger cost would bite:** SHORT, SETUP,
  and MULTI (MULTI is the designated referee for cross-search cuboid persistence,
  `PathfinderBenchmark.java:66-72`; a persistence bug shows as a wrong-cost anomaly vs SHORT +
  UPOVER_OPEN alone).
- **FLOOD** for the warm edit-heavy shape (any change touching `NavGridCuboidsView`/`PathEdits`
  interplay); `PatchStormBenchmark` only if grid maintenance is touched (V4's invalidation hooks
  would touch it).
- In-game: `/bot trace` repros on a TOWER-like hover goal and a shaft-descent goal — the trace's
  on-column/off-column split is the flood detector if a premium or macro was accidentally weakened.

*Analysis sources: `cuboid/` package at `src/main/java/com/orebit/mod/pathfinding/blockpathfinder/cuboid/`
(all six files read in full), `movements/Pillar|MineDown|Traverse.java`, `BlockPathfinder.java`
(§ MACRO_MOVES/primaryAxis/probe wiring), `MACRO-IMPLEMENTATION.md`, `CUBOID-PERF-OPTIONS.md`,
`PERF-PROFILE-2026-07.md`, `PERF-DESIGN-runup-nibble.md`, `docs/Optimizations/cuboid_macro_movements.md`,
`PathfinderBenchmark.java` scenario geometry, and the `macro-movement-non-negotiables` memory.*
