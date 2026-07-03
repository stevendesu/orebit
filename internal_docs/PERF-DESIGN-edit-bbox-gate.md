# PERF DESIGN REVIEW — per-pop edit-bbox disjointness gate

> **STATUS (2026-07-03): REFUTED — implemented as designed, measured FLAT, code DELETED.**
> Both variants (E1 full 3-component envelope, E2 owner's single padded box) were built behind
> default-OFF flags, verified byte-identical, and A/B'd overnight: FLOOD −0.23% / +0.12% — inside the
> ±2.1% run spread. A throwaway counter probe found the disjoint-pop fraction is **p = 0.000 in every
> scenario** (FLOOD: 10,001 pops, 9,559 edit-bearing, **0 disjoint**): the premise "a path's edits trail
> behind it" is false — the pillar-flood pop *stands on the block it just placed* (C1 hits at distance 0),
> and E2's sound box degenerates to a 4-compare horizontal-only test (Fall/Swim column scans are
> statically unbounded in Y, so no finite Y margin is sound). Full numbers + adoption addendum:
> `PERF-RESULTS-2026-07-03.md` §E1/E2. Gate code deleted per the pre-registered bar; kept from the work:
> the FLOOD JMH scenario (the suite's first warm edit-heavy shape). Consequence for future designs: any
> gate needs per-row/incremental chain bboxes or a recent-edits-only overlay, NOT a whole-chain bbox —
> and the widening doc's phase-2 "edits-bbox-gated neighbor prefilter" premise is dead as-designed.
> One salvage candidate (design-review first): re-hoist `editsRelevant = !pathEdits.isEmpty()` once per
> pop (the hoist alone, no bbox) — pinned OPEN showed the flagged code's per-pop boolean beat the
> per-read `isEmpty()` by ~3.6% on edit-free searches.
> **The body below is kept for the §2 movement-read envelope audit, which remains accurate and useful.**

**Original status: DESIGN ONLY — no code changed.** Perf item (a) from HANDOFF.md §Next-session-menu #6:
*"per-pop edit-bbox disjointness gate (kills the 49% kindAt tax — biggest lever, no data-model change)."*
Per the ratified process (CLAUDE.md "Performance model"): mechanism + invariants + expected win + risk,
for owner sign-off BEFORE implementation.

---

## 1. The tax today — exactly where and how much

### 1.1 The read path

Every path-edit-aware geometry read a movement makes during candidate generation goes through two
methods on `MovementContext` (`src/main/java/com/orebit/mod/pathfinding/blockpathfinder/MovementContext.java`):

- `descriptorAt(x,y,z)` — **lines 293–300**
- `descriptorOf(x,y,z,packed)` — **lines 328–335**

Both open with:

```java
if (!pathEdits.isEmpty()) {                    // field load + cmp   (PathEdits.java:80-82)
    int kind = pathEdits.kindAt(x, y, z);      // → PathEdits.java:118-122
    if (kind == PathEdits.PLACED) return PLACED_DESC;
    if (kind == PathEdits.BROKEN) return AIR_DESC;
}
return grid.descriptorAt(x, y, z);             // (or the navtype decode, for descriptorOf)
```

`PathEdits.kindAt(int,int,int)` (`PathEdits.java:118-122`) is itself already two-stage: a `size == 0`
check, then a **6-int-compare bounding-box reject** (added in the s2x round —
`docs/Optimizations/pathfinding_hot_path.md`, "we gave the diff a glance"), and only for in-box
coordinates the `BlockPos.asLong` pack + murmur finalizer + linear probe (`slotFor`, lines 160–169).

Every predicate wrapping a coordinate read funnels through these two methods: `passable/standable/
water/topYOf/isSlow/breakable/breakCost/placeable(int,int,int)` forms, `bodyTransitCost`'s two body
reads, and all of `EditScratch.requireAir/requireFloor/requireFootingOn` (`EditScratch.java:68,86,114,124`).

### 1.2 What the 49% is

`internal_docs/PERF-PROFILE-2026-07.md` §3, JFR `jdk.ExecutionSample` @1 ms, first-matching-frame
attribution over in-search stacks:

| Scenario | `PathEdits.kindAt` share | baseline |
|---|---|---|
| WARM FLOOD (10 001-pop edit-bearing flood, the S3 shape) | **49.0 %** | 952.6 ns/pop |
| TOWER (28 pops, macro pillar) | 28.7 % | 61.3 µs/op |
| UPOVER_WALL (41 pops) | 25.5 % | 93.7 µs/op |

So on the warm edit-heavy shape, **≈ 466 ns of every 952 ns pop** is spent inside `kindAt` — i.e. in
the *call + size check + 6-compare bbox reject*, ~100+ reads/pop × ~4–5 ns/read (profile §3 caller
evidence), **almost always returning NONE**. The murmur+probe stage barely runs (the per-read bbox
already rejects far reads); what's left is the sheer *volume* of per-read gate executions. In-game S3
(warm pillaring) runs 2 226 ns/node vs the 400–700 ns/node *edit-free* flood band — the delta is this
tax plus live-world chunk spread.

### 1.3 What already mitigates it (and what remains)

1. **`isEmpty()` fast path** (`PathEdits.java:80-82`, consulted at `MovementContext.java:294/329`):
   covers (a) searches where no accepted relaxation ever carried edits (`relaxer.anyEdits == false` —
   the rebuild is skipped wholesale, `BlockPathfinder.java:639-645`) and (b) pops whose **own chain**
   carries no edits even in an edit-bearing search (the rebuilt table is empty → `size == 0`). OPEN/
   SHORT-style beeline pops are here already. **The gate can win nothing for these pops.**
2. **Per-read bbox reject** (`PathEdits.java:120`): kills the hash for far reads, but still costs the
   call + 6 compares × ~100+ reads/pop. That residue **is** the 49 %.
3. **Adaptive `findEditInside`** (s42, `NavGridCuboidsView.java:290-325`): a *different* consumer —
   the cuboid edit-shrink scan — already bounded to box ∩ edit-bbox and adaptive volume-vs-list.
   Orthogonal to this design (see §7.1).

The remaining lever is exactly the profile's recommendation §7.3: the per-read test is **loop-invariant
within one expansion** — a pop either has edits near its read neighborhood or it doesn't — so hoist it
to **one test per pop**.

---

## 2. Mechanism

### 2.1 Where the gate lives

`BlockPathfinder.findPath` main loop, immediately after the per-pop diff rebuild
(`BlockPathfinder.java:639-645`):

```java
pathEdits.reset();
boolean editsRelevant = false;
if (relaxer.anyEdits) {
    for (int n = current; n != -1; n = nodes.parent[n]) pathEdits.add(nodes.edits[n]);
    if (!pathEdits.isEmpty()) {
        editsRelevant = envelopeIntersects(pathEdits, cx, cy, cz /*, per-search locals */);
    }
}
ctx.setEditsRelevant(editsRelevant);
```

`envelopeIntersects` compares the pop's **read envelope** (§3) against the edit bbox `PathEdits`
**already maintains** (`editMinX()..editMaxZ()`, updated incrementally in `markIfAbsent`,
`PathEdits.java:145-151`). No new bookkeeping in `PathEdits`; no data-model change anywhere — the
HANDOFF constraint holds.

### 2.2 How reads branch (the question of two paths vs flag vs swapped reference)

**A per-read flag, replacing the existing per-read branch of equal cost.** `MovementContext` gains one
`boolean editsRelevant` field; the two read methods change their opening test from
`!pathEdits.isEmpty()` to `editsRelevant`:

```java
public long descriptorAt(int x, int y, int z) {
    if (editsRelevant) {                       // field load + cmp — SAME cost as the isEmpty() it replaces
        int kind = pathEdits.kindAt(x, y, z);
        ...
    }
    return grid.descriptorAt(x, y, z);
}
```

Why this shape and not the others:

- **Swapped view/context reference (polymorphism):** rejected. Megamorphic dispatch on the single
  hottest read seam is exactly what the `polymorphism-off-hot-path` rule forbids; the whole
  `platform/`-adapter/static-one-liner architecture exists to keep these reads monomorphic and
  JIT-inlined.
- **Duplicated no-overlay methods** (`descriptorAtFast` twin family): rejected. Every movement call
  site would have to pick per pop (a branch anyway, now smeared over ~70 call sites), doubling icache
  footprint and doubling the soundness footgun surface.
- **The flag is not a new per-read branch** — it *replaces* one. Today's `!pathEdits.isEmpty()` is a
  field load + compare per read; the flag is a field load + compare per read. The per-read instruction
  count on the "edits relevant" path is unchanged; on the "disjoint" path it drops the `kindAt` call +
  size check + 6 bbox compares (~4–5 ns → ~0.3–0.5 ns per read). This respects the performance-model
  branch rule: the test is **loop-invariant within one expansion** (constant across the pop's ~100+
  reads → predicted after the first), flipping at most once per pop boundary — the NavFlags/prefilter
  "make the common case a single predictable test" pattern, not the reverted eager-prefetch's
  every-read data-dependent stencil check.

### 2.3 Flag lifecycle (cold paths stay byte-identical)

- **Init `false`** on construction. Pre-loop consumers — start-mode derivation
  (`BlockPathfinder.java:555-556`), `logRoot`'s passive candidate enumeration, `GoalForcedCost.probe`
  (which does no edit-aware reads at all — verified, §3.2) — all run with an **empty** `PathEdits`,
  where `kindAt` would return NONE anyway: identical answers either way.
- **Set per pop** as in §2.1.
- **Restored at loop exit**: `ctx.setEditsRelevant(!ctx.pathEdits().isEmpty())` before the failure
  diagnostics (`explainFailure` at `BlockPathfinder.java:948-953`, `dumpColumn` at 1021-1022), which
  read through the same seam against whatever the last pop's table holds — their (already
  semantically arbitrary) output stays byte-identical to today.
- **Other `MovementContext` instances** (`ProbeCommand.java:67`) never populate `pathEdits` → empty
  table → both flag values give identical answers; init-`false` is correct.

---

## 3. The envelope — derivation and soundness

### 3.1 Audited read extents (all edit-aware reads, relative to the expanded node)

Full audit of all 14 movements + `EditScratch` + the cuboid package (file:line evidence retained in
the audit notes; key extremes below). "Edit-aware" = goes through `descriptorAt`/`descriptorOf`
(directly or via a wrapping predicate / `EditScratch`). `packedAt`/`flagsAt`/`built` read the raw grid
and are out of scope.

| Movement | max \|dx\| | max \|dz\| | max +dy | max −dy | notes |
|---|---|---|---|---|---|
| Traverse micro (+step-assist/bridge) | 1 | 1 | +2 | −1 | |
| **Traverse MACRO** | **J along P** | **J along P** | +2 | −1 | per-step `requireFloor/requireAir` at k = 1..J (`Traverse.java:211-250`); J bounded — see §3.2 |
| Diagonal | 1 | 1 | +2 | 0 | corner columns (`Diagonal.java:64-70`) |
| Ascend | 1 | 1 | **jumpHeight+2** | 0 | caps-dependent (default jumpHeight 1 → +3) |
| Descend | 1 | 1 | +2 | −1 | |
| **Fall** | 1 | 1 | +2 | **−maxDrop** | column scan `fy ≥ y − maxDrop` (`Fall.java:89`); maxDrop = max(`caps.maxFallDistance`, safeFall) = **16** default, **4096** (`IMMUNE_FALL`) for an immune bot — caps-dependent, unboundable statically |
| Pillar micro | 0 | 0 | +3 | 0 | |
| **Pillar MACRO** | 0 | 0 | **J+2** | 0 | same column, k = 1..J + landing body (`Pillar.java:126-144`) |
| MineDown micro | 0 | 0 | 0 | −1 | |
| **MineDown MACRO** | 0 | 0 | 0 | **−J** | same column (`MineDown.java:98-111`) |
| **Parkour** | **5** | **5** | **+4** | −3 | landings out to gap+1 (falling gap ≤ 4 → \|dx\| = 5); clearance prism to y+4 (`Parkour.java:702`); drop scan −capsDrop (aggressive tier −3, `Parkour.java:444`) |
| DiagonalParkour | 3 | 3 | +3 | 0 | |
| **Swim** | 1 | 1 | **+column** | **−column** | `wf` scan up/down while `water()` (`Swim.java:86-94`) — terrain-bounded, statically UNBOUNDED |
| SprintSwim / StartSprintSwim / Surface | 1 | 1 | +2 | −1 | |
| **Climb** | 1 | 1 | +3 | −1 | |
| `EditScratch.requireFloor` → `placeable` | +1 lateral | +1 lateral | 0 | **−1 below** | neighbor-face probes (`MovementContext.java:694-696`) — add ±1 h / −1 v to whichever cell any `requireFloor` targets |

Three structural facts fall out:

1. **Every deep/unbounded vertical scan is horizontally within ±1 of the node** (Fall's 4 cardinal
   columns, Swim's column, Climb's column, Pillar/MineDown macro's own column).
2. **Every wide horizontal read is vertically shallow** (Parkour: −3..+4 at up to ±5, +1 probe margins).
3. **The only reads that escape both** are the horizontal-macro per-step folds (Traverse macro), and
   those are provably goal-ward-bounded (§3.2).

### 3.2 The macro bound and the cuboid non-issue

- `MacroJump.steps` (`MacroJump.java:75-95`): `jump = max(1, min(travelExtent, goalBound, escapeBound))`
  with `goalBound = max(0, sign·(goalCoord − cellCoord))` and `hard ≤ 1 → return 1`. **J > 1 exists
  only in the goal-ward sign along the travel axis, and reads reach at most the goal's coordinate on
  that axis.** Non-goal-ward cardinals get J = 1 → reads within the small box.
- Option B (`MovementContext.macroAxis()`): only the movement whose travel axis equals the per-search
  primary axis **P** extracts cuboids / emits macros. P = Y ⇒ the only far folds are Pillar/MineDown's
  **own column** (covered by fact 1). P = X or Z ⇒ only Traverse's goal-ward ray needs covering.
- **Cuboid machinery needs no envelope coverage**: `CuboidExtractor`/`GoalForcedCost.probe`/`MacroJump`
  contain zero edit-aware reads (verified — they read the raw grid via `sectionRawAt`); speculative
  edits enter cuboids only through `NavGridCuboidsView.applyEditShrink`/`findEditInside`, which reads
  `PathEdits` **directly** (not via the gated `MovementContext` seam) and **must stay ungated** — a
  cuboid box legitimately ranges far beyond any movement envelope (§7.1).

### 3.3 The envelope: a 3-component union

Disjointness test per pop = edit bbox intersects **none** of (short-circuit on first hit, cheapest
first):

| | X | Z | Y | covers |
|---|---|---|---|---|
| **C1** — deep-column | cx ± 1 | cz ± 1 | **all** (test omits Y) | Fall/Swim/Climb columns, Pillar/MineDown micro+macro, all ±1 moves |
| **C2** — wide-shallow | cx ± **RH** | cz ± RH | cy − **RD** .. cy + **RU** | Parkour, DiagonalParkour, Ascend, Diagonal, everything ≤ RH |
| **C3** — macro ray (only when `cuboids != null` **and** P horizontal) | node → **goal plane on P, goal-ward only**; ±1 on the orthogonal horizontal axis | | cy − 2 .. cy + 3 | Traverse macro per-step folds (incl. their `placeable` probes) |

Per-search locals (computed once in `findPath`, hoisted like `maxNodes`):

- **RH = 6** — Parkour max landing reach 5 + 1 `placeable` lateral probe.
- **RU = max(4, jumpHeight + 2) + 1** — Parkour +4 vs Ascend's caps-dependent reach; +1 margin.
- **RD = 5** — Parkour aggressive drop 3 + 1 below-probe + 1 margin. (Deep drops are C1's job.)

Cost of the test: C1 = 4 compares; C2 = 6; C3 (when applicable) ≈ 6–8 — worst case ~16–18 int
compares **per edit-bearing pop only**, executed right after a rebuild that already walked the parent
chain. Call it ≲ 5–10 ns/pop against a 466 ns/pop target. Nothing is added to edit-free pops or
edit-free searches (the gate sits inside the existing `anyEdits` branch).

Deliberate slack (each costs disjointness-fraction, never correctness): C1 ignores Y entirely (an
edit 30 blocks below within ±1 horizontally forces the slow path even for a pop that can't Fall
there); C3 uses the goal plane instead of the (unknowable-before-extraction) box extent; margins +1.
Sound-by-construction beats tight-by-analysis here — the failure mode of "too tight" is a silent
behavior change, the failure mode of "too loose" is a measurable few % of forgone win.

### 3.4 Soundness ⇒ byte-identical results

Claim: with the envelope of §3.3, gating changes **no read's answer**, hence no candidate, cost,
f-value, heap operation, expansion order, or returned path (incl. `LAST_EXPANSIONS`, TRACE output,
partial/irreversible truncation).

1. Every edit-aware read issued during the expansion of node (cx,cy,cz) targets a coordinate inside
   C1 ∪ C2 ∪ C3 (§3.1–3.2 audit).
2. The gate sets `editsRelevant = false` only when editBBox ∩ (C1 ∪ C2 ∪ C3) = ∅.
3. Therefore every such read's coordinate lies outside the edit bbox, so today's code path would have
   returned NONE via the per-read bbox reject (`PathEdits.java:120`) — skipping the consult returns
   the identical descriptor.
4. **The bbox is frozen for the duration of one expansion**: `PathEdits` is written only by
   `reset()`+`add()` at the top of the pop (`BlockPathfinder.java:639-645`). `EditScratch`
   accumulates candidate edits in its own buffers; `Relaxer.relax` stores `StepEdits` on node rows
   (affecting only *future* pops' rebuilds); no movement or cuboid code writes `PathEdits`
   mid-expansion. So a gate decision made after the rebuild is valid for every read of that expansion.

Anything that would violate (1) — a new movement, a longer Parkour tier, `jumpHeight`-style caps
growth — is the risk register's #1 item; §8 and §9 put a mechanical tripwire under it.

---

## 4. What the gate does NOT touch

- **The per-pop rebuild itself** (reset + chain re-`add`, ~8 % of warm flood ≈ 75 ns/pop): must stay,
  because `NavGridCuboidsView.cuboidAt` consults the table for edit-shrink over boxes that can be far
  larger than the movement envelope, at any pop. Skipping the rebuild on disjoint pops would need a
  per-row incremental chain-bbox — a data-model change, out of scope (rejected-alternatives §10).
- **`NavGridCuboidsView` / `findEditInside`**: keeps reading `PathEdits` directly, ungated.
- **`PathEdits` internals**: unchanged — `kindAt`'s own size/bbox guards remain (they're still needed
  when `editsRelevant` is true, and by `findEditInside`'s volume branch).

---

## 5. Expected win — the math, honestly

Population accounting for the warm-flood shape (952 ns/pop, kindAt 49 % ≈ 466 ns/pop):

- Let **q** = fraction of pops whose rebuilt chain carries ≥ 1 edit (only these pay kindAt today;
  ~empty-chain pops are already fast via `isEmpty`). The 49 % attribution implies q is high on FLOOD2.
- Let **p** = fraction of *those* whose edit bbox is envelope-disjoint. Saving ≈ p × (466 − ε) ns/pop,
  ε = flag-test residue ≈ 0 (it replaces the isEmpty test) + gate cost ≲ 10 ns/pop.

**p is the honest unknown.** The structural argument for high p — "a path's edits trail behind it" —
is true for walk-after-dig chains but **false for exactly the pops that mint the edits**: a Pillar
tip's floor IS a `PLACED` cell (C1 hit at distance 0); a fresh dig's pop stands in its own `BROKEN`
cells. In a pillar-cone flood, every partial-pillar tip is edit-adjacent; the flood's *fan-out* pops
(walked ≥ RH sideways / along from their last edit) are the winners. So:

| p | warm flood ns/pop | Δ |
|---|---|---|
| 0.9 (profile §7.3's implicit assumption) | ~530 | **−44 %** |
| 0.7 | ~636 | −33 % |
| 0.5 | ~730 | **−23 %** |

**Prediction: warm flood −25…−45 %; in-game S3 2 226 → ~1 400–1 700 ns/node** (the profile's own
estimate was 1 300–1 500; the wider band prices the p-uncertainty). Even the pessimistic end clears
the ≥ 3 % keep threshold by an order of magnitude *on the target shape*.

Per suite scenario:

| Scenario | kindAt today | expectation |
|---|---|---|
| WARM FLOOD (probe loop — **the target metric**) | 49 % | −25…−45 % |
| TOWER | 28.7 % | ~flat: 28 pops, mostly on-column macro pops (C1 hit) + probe-extraction-dominated (45 %); do not gate the keep decision on it |
| UPOVER_WALL | 25.5 % | 0…small win: 41 pops weaving near their own wall edits; treat as guard |
| OPEN / SHORT / MULTI | ~0 (isEmpty path) | **0 ± noise — pure guards.** SHORT's trap is per-search *setup*; this design adds none (no new per-search allocation, no init loop — two int locals) |

Honest cap: this is a **warm, edit-heavy** lever only. It does nothing for S1 (JIT-cold first search),
S2 (warm-up), or the small-search probe-extraction cost — those are items (b) and (c) in the HANDOFF
perf order.

---

## 6. Fit with the performance-model case studies

- **Hilbert lesson (per-access math):** the gate *removes* ~10 instructions from ~100+ accesses/pop
  and *adds* ~16 compares once per pop. Net ALU strictly down on disjoint pops, unchanged otherwise.
- **Chunk-cache lesson (per-search setup):** zero per-search setup added — no allocation, no arrays,
  no zeroing; two extra locals in `findPath`'s prologue.
- **Prefetch lesson (per-read branches):** no new per-read branch — the flag replaces `isEmpty()`
  one-for-one; it is pop-invariant, so its predictor state is stable across each expansion.
- **Byte-identical rule:** §3.4 argues identity; §9 *verifies* it mechanically rather than trusting
  the argument.

---

## 7. Interactions

1. **s42 adaptive `findEditInside` (−40 % UPOVER_WALL): composes, neither redundant.** That change
   optimizes the cuboid *shrink scan* (box ∩ edit-bbox, volume-vs-list adaptive); this one optimizes
   the *movement read seam*. They share only the `PathEdits` bbox accessors. The gate never fires for
   `cuboidAt` (§4), so the adaptive scan keeps its exact inputs and outputs.
2. **HANDOFF item (c) — persist base cuboids across replans:** independent; (c) attacks
   `GoalForcedCost.probe` extraction (38–45 % of small searches), which does no edit-aware reads.
3. **HANDOFF item (d) — NavGrid 16→32 widening:** the HANDOFF already describes the neighbor-class
   prefilter as "**edits-bbox-gated**" — this gate is the intended substrate: a widened per-cell
   prefilter is only trustworthy when the pop is edit-disjoint (edits invalidate resident neighbor
   bits). Landing (a) first is the right order.
4. **Item #12 (break-through-hazard):** adds break-folds of *body* cells already inside C1/C2 — no
   envelope growth. Must still be checked against the tripwire test when it lands.
5. **Warm-up searches (b):** unaffected; A/B for (a) must be measured on the warmed states anyway.

---

## 8. Risk register

| # | Risk | Severity | Mitigation |
|---|---|---|---|
| 1 | **Envelope unsoundness** — a missed edit-aware read site today (Parkour is 914 lines), or silent future drift (a longer parkour tier, a new movement, a config'd `jumpHeight`/fall growing past a constant) → wrong descriptors on gated pops → *silently different paths* | HIGH (the one real correctness risk) | (i) RU caps-derived, RD/RH constants documented AT the movement constants they derive from; (ii) **paranoia cross-check mode** (test/Debug-gated): on gated-off pops, still run `kindAt` per read and assert NONE — run over the full unit suite + all bench scenarios before first measurement; (iii) identity harness (§9.2); (iv) contract note on `Movement.candidates` javadoc: reads beyond the envelope require extending it |
| 2 | Per-pop gate cost regresses edit-bearing small searches | LOW | ≲ 10 ns/pop inside the existing `anyEdits` branch; TOWER/UPOVER_WALL are the sentinels; SHORT/OPEN structurally untouched |
| 3 | Flag branch mispredicts when pops alternate near/far | LOW | ≤ 1 mispredict per pop boundary (~0.1 % of pop cost); within-pop invariant |
| 4 | forks=0 JIT/code-layout perturbation — `descriptorAt/Of` are inlined into every movement; changing their first test can shift inlining decisions suite-wide, minting phantom single-scenario deltas | MEDIUM (measurement, not product) | pinned `-Pscenario=<X>` fresh-JVM interleaved pairs for any suspicious delta (the phantom-OPEN protocol); foreground runs only |
| 5 | Diagnostic drift (`explainFailure`/`dumpColumn`/probe/trace read through the seam) | LOW | flag restore at loop exit (§2.3); TRACE compared byte-for-byte in §9.2 |
| 6 | The target metric isn't in the JMH suite (warm flood was a throwaway probe loop) | MEDIUM | promote/re-create it for this arc (§9.1) — without it the suite would show "~flat everywhere" and the keep decision would be blind |

---

## 9. Measurement & acceptance plan

### 9.1 Benchmarks (bench worktree `orebit-mc121-wt`, 1.21.4 node, JDK 21, foreground only)

1. Re-create the profile's **warm-flood probe** (FLOOD2 shape: edit-bearing, budget-exhausted
   10 001-pop flood over the flat fixture, 15-s loop) — the **target metric**. Preferably promoted to
   a `FLOOD` `@Param` scenario so the suite covers the S3 shape permanently (owner call; it lengthens
   the suite by ~2 min).
2. **Paired interleaved A/B** (gate ON/OFF via a temporary `BlockPathfinder.EDIT_GATE` static, deleted
   after ratification, per the HPA-flag precedent) over the full suite — TOWER, OPEN, UPOVER_OPEN,
   UPOVER_WALL, SHORT, MULTI + FLOOD — plus pinned fresh-JVM re-checks for any suspicious
   single-scenario delta.
3. **Keep rule:** FLOOD ≥ 3 % (expected ≫), no other scenario regressing beyond noise, tests green.
   Revert without sentiment if FLOOD's win doesn't reproduce (that would mean p ≈ 0 — worth knowing).

### 9.2 Byte-identity verification (before any timing is believed)

- Unit suite green with the paranoia cross-check mode ON (risk #1).
- For each bench scenario + FLOOD: assert gate-ON vs gate-OFF produce identical
  `LAST_EXPANSIONS`, identical waypoint lists, identical per-step edits.
- One `/bot trace` (or synthetic TRACE) on an edit-heavy search: `E`/`C` line streams diff-clean.

### 9.3 In-game (26.2)

- Warm pillar / dig scenario at the user's live config (`costPerHitpoint=5000`, `placeBaseCost=12`):
  `LOG_TIMING` ns/node distribution before/after — expect S3-shape 2 226 → ~1 400–1 700.
- Berry-maze and staircase-build sanity replays (edit-vocabulary behaviors unchanged).

---

## 10. Alternatives considered and rejected

1. **Per-row incremental chain-edit bbox** (6 ints or packed form per node row, maintained at relax
   time): O(1) gate *and* could skip the ~8 % rebuild for disjoint pops. Rejected *for this arc*:
   +24–40 B/row is a data-model change (HANDOFF explicitly scopes (a) as "no data-model change"), it
   fattens `growNodes`/relax, and the rebuild-skip additionally requires gating `cuboidAt` against
   far boxes. Recorded as a future composition, possibly with (d).
2. **Swapped context/view reference** and **duplicated read methods** — §2.2.
3. **Tighter per-movement envelopes** (gate recomputed per movement inside the loop): 13 tests/pop
   instead of 1, and pushes soundness knowledge into every movement — the union envelope is one
   place, one test.
4. **Sorting/spatially indexing the edit list**: the per-read bbox reject already made in-box hashing
   rare; the residual cost is the gate *executions*, which only hoisting removes.

---

## 11. Implementation sketch (for scoping only — NOT implemented)

- `MovementContext`: `+ boolean editsRelevant` (init false), `+ setEditsRelevant(boolean)`; change
  the first test in `descriptorAt` (L294) and `descriptorOf` (L329). ~6 lines.
- `BlockPathfinder`: per-search locals (RH/RU/RD, P, goal-ward sign material already in scope as
  `gx/gy/gz`/`macroAxis`); `envelopeIntersects(...)` private static (~15 lines); gate call in the
  rebuild block; flag restore before `explainFailure`. ~25 lines.
- Temporary: `EDIT_GATE` A/B static + paranoia cross-check (Debug/test-gated). Deleted/retired after
  ratification.
- No changes to `PathEdits`, movements, `EditScratch`, or any cuboid class. No allocation anywhere.

**Estimated effort:** ~half a session including the identity harness and the paired A/B protocol;
the FLOOD scenario promotion is the main new test surface.
