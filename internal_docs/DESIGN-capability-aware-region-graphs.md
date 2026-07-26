# DESIGN — Capability-Aware Region Graphs (item #6): movement-verified fragments

Status: **DEFERRED 2026-07-25 (owner ruling). #6 is an OPTIMIZATION, not a correctness requirement —
search-time invalidation + re-search already cover correctness; #6 only avoids wasted search on the
known-impossible. Do NOT build eagerly. The v2 model below stands as the design of record; the OPEN
BLOCKER is the directional-asymmetry problem (§15). The ACTIVE next work is the NavGrid-build
performance pass (§15 close), which frees the compute budget more-accurate region building needs — NOT
this doc. (v2 rewrote the v1 draft, which got the intent wrong — a heuristic vertical gate.)**
Relationships: this is the **structural** cure for the merged-pocket that `DESIGN-virtual-start-fragment.md`
§2 treats *reactively* via approach-conditioned invalidation (they compose — §7); it is **performance-
centric** and entangled with a resurrected **movement-check performance pass** (§8, lever 2); it reshapes
the #4/#5 subsumption (§7) and the ROADMAP §2/§4 #6 entries (to be reconciled after review). Cites are the
`orebit-mc121-wt` worktree.

---

## §0. TL;DR

Today the L0 flood makes a fragment out of any **passable-adjacent** cells (`passable[c] &&
passable[neighbour]`, 6-connected incl. ±Y — `FragmentBuilder.java:204-209`). That over-connects: it
merges a shelf and the cliff-top above it (an open air column is passable-adjacent) even though a no-cap
bot cannot ascend the bare column (virtual-start §2). The fix the owner directed: **make the flood's
connectivity test the ACTUAL movement predicates** — no heap, no cost/heuristic, no edit tracking, none
of the search-time safety machinery, just the pure geometric "can a free move go between these cells?".
A fragment becomes **cells mutually reachable by the movement vocabulary**, not "connected air".

Two facts make this both correct and affordable:

1. **Membership = *locally-mutual* movement components; one-way moves = directed intra-leaf crossings**
   (§2/§3). Movement reachability is directed (`Fall` goes down, no free inverse), so *undirected*
   movement-connectivity would still merge shelf+cliff-top. Mutual reachability separates them — and it
   is cheap because every one-way free move is strictly **descending** (can't cycle), so mutual
   reachability decomposes into a flood over the two-way moves + directed crossings for the one-way ones.
   No SCC/Tarjan.
2. **The capability lattice falls out as a union-find overlay, not 4 floods** (§4, lever 1). The base
   (free-move) fragment set is capability-independent; `+break`/`+place` only *add* directed crossings
   (`MineDown`/dig, `Pillar`/place), and where an added crossing makes a previously one-way pair
   **mutual** it *unions* two base fragments. 4 cap-combo graphs = one base flood + 3 cheap union-find
   overlays. This is the owner's "start no-capa, merge when a new capability connects two fragment
   boundaries", and it reuses the existing coarse `PyramidMerger.combineFragments` union-find pattern.

The cost driver is that a movement predicate is far more work than a passability AND (Parkour takeoff
enumeration, headroom envelopes). The enabling **lever 2** (§5/§8) is a **movement-check performance
pass** — precompute the per-leaf cell masks the flood already builds (`passable/standable/water/footing/
headroom`) and rewrite the free predicates to consume those masks instead of re-reading descriptors,
killing the measured redundancy (start-floor read 3×/expansion, shared `y+3` head, etc.). Lever 2 helps
**search too** (the block A* pays the full 17-move predicate set per popped node — `BlockPathfinder.java:
897-901`), so it ships **first, on its own, JFR-driven** — which is exactly why #6 was originally slotted
*after* a performance pass.

---

## §1. Motivation — passable-adjacency over-connects; movement-verified membership fixes it

Fixture (shared with virtual-start): Gather Issues Repro, **no-capability bot** (`canPlace = canBreak =
false`), `(70,63,-68) → (77,72,-78)`. The goal sits atop a cliff; the only cap-legal route is the long
walk to a staircase on the far side. The region tier degenerates to a **one-hop skeleton** because the L0
flood merged the shelf and the cliff-top into one fragment — the flood ascends the open air column above
the shelf (`passable[c+G²]` at `FragmentBuilder.java:208`) with no notion that a no-place bot cannot
follow it. The region tier, seeing one node, dumps a region-scale problem on the block tier, which floods
the cliff face (virtual-start §1).

**The general defect:** the fragment graph over-approximates reachability by connecting on *passability*,
not *traversability*. Today that over-connection is corrected downstream — search-time cap gates prune
crossings, and invalidation (virtual-start §4) absorbs the merged-pocket residue. #6 moves the truth into
the graph: a fragment is a set of positions the bot can actually move among, so a genuinely unreachable
pocket is **structurally** a different fragment (or reachable only by a one-way crossing), and the region
tier can route around the obstacle with real nodes instead of flooding.

---

## §2. The model — locally-mutual movement components + directed crossings

**Node domain.** Flood over the block tier's *occupiable positions* (standable-with-headroom floor cells +
swimmable-water cells — the block-A* node space), not raw passable air. (Exact cell convention — floor
cell vs feet cell — is an implementation detail to align with the existing node↔fragment mapping; the
model is convention-agnostic.)

**The free move set** (usable by a no-break/no-place bot — verified, movement forensic PART 1):
`Traverse`-flat, `Diagonal`, `Ascend`/`Descend` onto existing terrain, `Fall`, all swim
(`Swim`/`SprintSwim`/`StartSprintSwim`/`Surface`/`DiagonalSprintSwim`), `Climb`, `Parkour`,
`DiagonalParkour`, `WalkOff`, `RideBubbleColumn`. Break-gated: `MineDown`, the dig edit-arms on
Traverse/Ascend/Descend. Place-gated: `Pillar`, the place edit-arms. (Capability gating is two-layered —
hard `return` for Pillar/MineDown/jump; soft edit-arms for the ground moves that degrade to the free case
when caps are absent — `EditScratch.java:103`, `MovementContext.java:1232/1302`.)

**Connectivity rule.** For each candidate cell pair `(A,B)` the flood considers, run the free move
predicates **both directions**:

- **Both directions connect (locally mutual)** → `A` and `B` are in the **same fragment** (union).
- **Exactly one direction connects (one-way)** → record a **directed intra-leaf crossing** `A→B` between
  their (eventual) fragments — *not* a union.
- **Neither** → no edge.

Fragments are the **locally-mutual components**; one-way free moves (chiefly `Fall`, falling-`Parkour`,
`WalkOff`-down) become directed crossings, exactly like today's intra-region **mine edges** already
connect sibling fragments (`RegionPathfinder` intra-region mine edge, verified in the prior forensic) —
so the search model already has intra-region inter-fragment directed edges; #6 generalizes that set from
"break-only" to "the free asymmetric moves + the capability moves."

**Why this is the shelf/cliff-top fix.** `Fall` connects cliff-top → shelf; no free move connects shelf →
cliff-top (bare column, no Ascend/Pillar). One-way → a directed crossing, **not** a union → shelf and
cliff-top are **different fragments**. The region tier now sees "cliff-top→shelf yes, shelf→cliff-top no"
and will not route up the cliff. Correct, structurally, with no invalidation round-trip.

---

## §3. Why locally-mutual is correct (and why no Tarjan is needed)

**Undirected is wrong** (it merges shelf+cliff-top via the downward `Fall`). **Full SCC (Tarjan) is
unnecessary.** The bridge is this invariant, verified against the move set:

> **Every one-way free move is strictly descending.** `Fall`, falling-`Parkour`, `WalkOff`-down all lose
> height and have no free inverse; every *ascending* free move (`Ascend`, `Climb`-up, rising-`Parkour`)
> either has a checkable local inverse (so it's handled by the locally-mutual union) or is itself one-way
> (recorded as a directed crossing). A cycle of strictly-descending moves is impossible, so global mutual
> reachability cannot arise except through locally-mutual steps already unioned.

Therefore: flood-union on locally-mutual moves + directed crossings for one-way moves **exactly captures
reachability** without computing SCCs. **Soundness (find-path-iff-exists):** every free move is
represented as *either* a union *or* a directed crossing — **no reachable transition is ever dropped**, so
no false disconnection is possible. The only residual error is *over*-fragmentation (a mutually-reachable
pair split into two fragments joined by two directed crossings, e.g. `Fall` down + rising-`Parkour` up on
different cells) — which is still fully routable via the crossings and merely costs a few more fragments
(§9). Error stays on the safe (over-connected / finer) side, never the fatal (under-connected) side. This
is the same soundness posture as today, at finer resolution.

---

## §4. Lever 1 — the capability lattice as a union-find overlay (not 4 floods)

The base fragment set uses only the **free** moves, so it is **capability-independent** — one flood serves
all four `place×break` corners. Capabilities add *crossings*, and where an added crossing makes a
previously one-way pair **mutual**, they *union* base fragments:

- **`+place`** adds `Pillar` (ascend a bare column) and the place edit-arms (build a step/bridge). Where a
  base one-way crossing `A→B` (e.g. `Fall` cliff-top→shelf) gains its inverse from a place move
  (`Pillar` shelf→cliff-top), the pair becomes mutual → **union `A,B`** in the `+place` graph. Plus new
  place-only crossings.
- **`+break`** adds `MineDown` and the dig edit-arms. Same rule: a break move supplying the missing
  direction unions; break-only new connections become crossings.
- **`+break+place`** = the union of both overlays over the base.

Mechanically this is a **union-find over base fragments**, processed **only at fragment boundaries** —
run *only* the capability-specific predicates (`Pillar`/`MineDown`/edit-arms) where they can fire (at
floors, walls, one-way-crossing endpoints), never a full re-flood. Cost = one base flood + three cheap
boundary passes, **not 4× the flood**. This is the owner's lever 1 verbatim ("start with the no-capa
graph… can the boundaries of two fragments be connected with a new capability? if so, merge them"), and it
reuses the existing coarse union-find pattern `PyramidMerger.combineFragments` (which already unions child
fragments on a shared-face condition — `PyramidMerger.java:28-72`), applied at the leaf with a
capability-move predicate instead of footprint overlap.

**Boundary data gap (verified, must be closed).** Today fragments store only per-face **bbox footprints**
(`RegionFragments.faceMask`/`packFootprint`), not per-cell boundary geometry — too coarse to ask "does a
`Pillar`/`MineDown` bridge X and Y here?". The base flood must therefore **retain the one-way-crossing
endpoint cells** (and the fragment boundary cells the capability predicates need) as it runs — a small
per-fragment cell list or a boundary bitmask, computed once, consumed by the three overlays. This is new
state the base flood emits; scope it in §12.

---

## §5. Lever 2 — mask-backed movement predicates (the performance pass)

A movement predicate today calls `descriptorAt(x,y,z)` (section resolve + descriptor-table lookup) per
cell, and different movements **re-read the same cells** (movement forensic PART 2): the start-floor
descriptor is read independently by Traverse/Ascend/Descend/Parkour (**3+ reads/expansion of one cell**);
the `y+3` takeoff head is read by Ascend/Parkour/Pillar; Traverse's dest body pair == Descend transit ==
Diagonal corner column. `MovementContext` caches **none** of this across movements — only the path-edit
diff and the door edge are hoisted (`BlockPathfinder.java:874-896`).

**The reuse.** The flood *already* computes per-leaf `passable[]/standable[]/water[]` + a per-cell footing
and air-headroom test (`FragmentLeafComputer.computeLeaf:82-104`, `FragmentBuilder.java:181-193`).
Precompute a compact per-cell **cell profile** — `{standable, passable, water, headroomRun (passable
cells above), footing}` — once per leaf, and rewrite the free predicates to consume these **arrays**
(index + compare) instead of `descriptorAt`. Each predicate collapses to a few array reads; the "N
headroom cells passable" tests become one `headroomRun ≥ N` compare (generalizing the existing
`NavFlags.headroomProves` prefilter — `MovementContext.java:803`). The owner's "precompute the 4 adjacent
3-tall cuboids' standability/passability and pass to the movements" is exactly this at per-node
granularity.

**Two granularities, because search has edits:**
- **Flood (static leaf):** the per-leaf mask is fully static → the clean, total win.
- **Search (edits + multi-leaf):** the block A* mutates cells via `PathEdits`, so masks can't be fully
  static. The search win is the **per-node envelope precompute** (compute the neighbour `{standable,
  feet-passable, head-passable}` tuple once per popped node, share across that node's 17 move candidates)
  — edit-aware, recomputed per node, folding the redundancy the forensic found. What the tuple *cannot*
  serve (Parkour's multi-cell gap, Fall's deep column, Pillar/MineDown verticals, the `y+3` takeoff)
  stays on the current read path. Partial but real, and it is the JFR-measured hot set (§8).

---

## §6. Performance — what is known, what must be measured

**Known cost facts (region-gen forensic PART 2):** current passable flood ~13 µs/leaf (Javadoc claim, not
a benched result); per-column full build surface ~1 ms / cave ~5 ms (`ChunkBuildBenchmark`); the leaf
build runs **on the tick thread**, eager on chunk-nav-build (`EAGER_BUILD`) + lazy on `ensureLeaf`;
results are **deserialized on reload, not re-flooded** — the flood is paid once per novel chunk + per
edit. **The ~38 ms figure (owner, 2026-07-25):** a prior (uncommitted) analysis of the *full* worst-case
adversarial-chunk generation — NavGrid build + leaf regions + leaf merge + resource compass — where
**most of the time was the NavGrid build, and most of *that* was the neighbor-aware bits (depth nibbles,
safe-break)**. So the dominant chunk-gen cost is *upstream* of the region flood; speeding the NavGrid
neighbor-bit computation is the lever that frees budget for the more-complex (movement-verified,
directed) region building #6 wants — see §15 close.

**The cost change #6 introduces:** the connectivity test goes from "6 passability ANDs per passable cell"
to "run the free move set per occupiable cell (both directions)". The node domain *shrinks* (standable+
water cells < all passable cells) but the per-pair work *grows* (a move predicate » an AND). Net is
**unknown and must be measured** — no number asserted (perf model rule).

**The measurement plan (benchmarks exist — extend, don't invent):**
- `ConnectivityBenchmark` (`worldmodel/hpa/ConnectivityBenchmark.java`) already isolates a 16³ leaf and
  compares flood variants (OPEN/HALF/SPECKLE/CHECKER). Add a **movement-flood variant** (mask-backed free
  moves) beside `fragmentBuild`; paired interleaved A/B is the gate for the base flood + lever 2.
- `ChunkBuildBenchmark` (`hpaLeaf`/`regionBuild`, FLAT/SURFACE/CAVE) is the ms/chunk region-gen harness —
  the "is #6 within the tick budget" gate; this is where the ~38 ms is re-established/refuted.
- **JFR on `PathfinderBenchmark`** (search side, `-Pprof=jfr,cpu`) drives lever 2: confirm the redundant
  descriptor reads dominate before implementing the per-node envelope precompute, and prove the speedup
  with byte-identical search results (the perf model's hard bar).

---

## §7. Subsumption of #4/#5 and composition with virtual-start (reconciles the v1 error)

**vs #4/#5.** With movement-verified membership, a no-cap bot's cliff is **structurally** two fragments
(§2) → the region A* routes around it (or heap-exhausts to a true BLOCKED) **without flooding**, so #4's
negative-reachability harvest and #5's block-prune fire **less often**. But #6 does **not** eliminate them:
they still own (a) **unbuilt-terrain optimism** (a crossing into an unloaded neighbour is priced
optimistically; no movement flood can pre-disprove ungenerated terrain), and (b) **collapsed mass**
(`>MAX_FRAGMENTS` leaves have no per-fragment structure — §9). #6 is the structural amortization *beneath*
#4/#5, not a replacement; **do not gate #4/#5 on #6** (they ship first, need no graph rebuild).

**vs virtual-start (#2/#3).** virtual-start explicitly does **not** split the merged fragment; it
self-corrects the merge at runtime via approach-conditioned invalidation (virtual-start §2/§4). #6 makes
the split **structural** (the merge never forms). They **compose**: #6 *reduces the invalidation load*
virtual-start carries on the merged-pocket class, while virtual-start still supplies the blameable first
crossing, the flap damping, and the honest-BLOCKED semantics #6 doesn't touch. Because virtual-start
already makes the merge *correct*, **#6's split is a performance/accuracy optimization, not a correctness
fix** — which is why its sequencing is gated on a measurement (§8), not urgent.

*(The v1 draft's D0 — "inter-fragment caps are already gated at search, so #6 is just annotate + a
vertical heuristic split" — was the wrong frame: it is true that caps gate crossings at search, but the
point of #6 is that fragment **membership** is passability-based and must become movement-based. That is a
flood change, not a crossing annotation.)*

---

## §8. The performance-pass sequencing (the entanglement, and the recommendation)

Lever 2 **is** the movement-check performance pass #6 was originally slotted after. Recommendation, in
order:

1. **PERF PASS FIRST, on search, as its own arc.** JFR `PathfinderBenchmark` → confirm the redundant
   per-node descriptor reads are a real hot fraction → implement the per-node envelope precompute + the
   mask-backed free predicates behind `MovementContext` → gate on **byte-identical search results** + a
   measured expansion-time win (paired A/B). This is independently valuable (faster search regardless of
   #6) and **de-risks #6** (proves the mask-backed predicates are correct and fast before flooding with
   them). It also gives us the movement-predicate cost model #6's flood budget needs.
2. **THEN #6 base flood** (movement-verified fragments + directed crossings, §2/§3) using the now-fast
   predicates; gate on `ConnectivityBenchmark`/`ChunkBuildBenchmark` within the tick budget + the
   soundness battery (§12).
3. **THEN lever-1 overlays** (the capability union-find, §4).

This matches the owner's original ordering and keeps each step measurable. **Owner call (R1):** confirm
perf-pass-first, vs co-designing #6 with lever 2 in one arc. (Leaning: perf-pass-first — the search win is
real on its own and it de-risks the flood cost.)

---

## §9. MAX_FRAGMENTS pressure

Movement-verified membership is **finer** than passable-adjacency (a bare column splits off; a fall-pocket
becomes its own fragment) → more fragments per leaf → more chance of tripping `MAX_FRAGMENTS = 62`
(`RegionFragments.java:105`) and collapsing to a uniform mass. The typed-fragments §8 census shows L0-L2
(the near-field the block tier leans on) safe-to-marginal with headroom (max 25/72). Mitigation: collapse
is a *cap-blind uniform-mass* fallback that degrades to today's over-connected behavior — **safe**
(over-connection, invalidation-absorbed). If #6 raises collapse rates materially at coarse levels, that is
a measured tuning input (raise the cap, or accept coarse collapse where the census allows), not a
correctness risk. Measure the fragment-count delta on the census terrain as part of §12.

---

## §10. Persistence

The movement-flood's output is the same *shape* as today (fragments + crossings), so it **deserializes on
reload, not re-floods** (region-gen forensic PART 4) — the added cost is paid once per novel chunk + per
edit, never on restart. Two format questions: (a) the base fragment set + the **directed intra-leaf
crossings** need a codec slot (today crossings are derived at search from footprints; #6's one-way free
crossings are new persistent data); (b) the **capability overlays** (union-find merges + capability
crossings) — persist per-corner, or recompute the three overlays on load from the base + retained boundary
data? **Leaning: persist the base fragments + one-way crossings; recompute the capability overlays lazily
on first bot-of-that-cap demand** (RAM-cached, tick-thread — favor-cpu-over-ram), so disk carries one set,
not four. Codec bump; the format is a rebuildable CACHE (bad/missing → reflood).

---

## §11. Invariants

- **find-path-IFF-exists / prove-nonexistence — TOUCHED, and upheld by §3:** every free move is a union or
  a directed crossing; no reachable transition is dropped → no false disconnection. The *only* way #6
  could under-connect is a **buggy movement predicate** that misses a real move — mitigated because the
  flood uses the **same predicates the block A* uses**, so any such bug is already a search bug (consistent
  failure, not a new #6-only hazard). This is the load-bearing invariant.
- **See massive obstacles from afar:** improved — the cliff is a structural region obstacle, not an
  invisible intra-fragment merge.
- **Principled/generic (no per-WORLD-feature special-casing):** honored — the connectivity test *is* the
  generic movement vocabulary; no "if lava/if cliff" hack. The cap axes are capability axes, not world
  features.
- **No baked wrong capability assumptions:** strengthened — #6 stops baking the full-cap assumption into a
  shared graph; the base is the no-capa truth, capabilities add.
- **Region tier stays tick-confined:** hard constraint — the movement flood, like every flood, runs on the
  tick/maintenance thread; no planner-pool worker triggers it; lever-1 overlays and any lazy no-cap
  caching are tick-thread-owned.
- **Benchmark anything hot:** §6/§8 — nothing lands without paired A/B (`ConnectivityBenchmark`/
  `ChunkBuildBenchmark`) + JFR (lever 2 on search) + byte-identical search results.
- **No arbitrary timers:** honored.

---

## §12. Increments + test plan

- **Increment P (the perf pass, ships first — §8.1).** Per-node envelope precompute + mask-backed free
  predicates behind `MovementContext`. Gate: **byte-identical search results** for every scenario +
  a JFR-proven expansion-time win (paired A/B on `PathfinderBenchmark`); full suite green. Independently
  shippable; #6 depends on it.
- **Increment 1 (base movement-flood — §2/§3).** Replace the leaf connectivity test with the free
  move-predicate mutual/one-way rule; emit directed intra-leaf crossings; retain the boundary/crossing
  cell data (§4 gap). Gate: `ConnectivityBenchmark` movement-flood variant + `ChunkBuildBenchmark` within
  tick budget (paired A/B); the **soundness battery**:
  - **staircase fixture** (no-cap bot ascends a real block staircase) → still one connected region / FINDS
    (proves mutual-union didn't over-split a real ascent).
  - **bare-shaft fixture** (goal atop a sheer column, no lateral escape) → shelf and top are separate
    fragments with only a one-way `Fall` crossing → the no-cap search true-BLOCKs *structurally* (proves
    the split fires where it should) — and the cliff fixture PASSES at the default 10k budget by routing to
    the staircase with **fewer invalidation cycles** than virtual-start alone (measure the delta).
  - **place-capable regression** → `+place` unions shelf+top (Pillar inverse) → the place bot's route is
    unchanged (byte-identical for `canPlace`).
- **Increment 2 (lever-1 capability overlays — §4).** The union-find overlays for `+break`/`+place`/both;
  the boundary-predicate passes; the persistence choice (§10). Gate: all four cap-combos correct on the
  soundness battery; MAX_FRAGMENTS/collapse census delta measured (§9); restart oracle green (base +
  crossings deserialize; overlays recompute).

---

## §13. Decisions log

- **D1.** Fragment = **locally-mutual** movement component; one-way free moves = **directed intra-leaf
  crossings** (generalizing today's intra-region mine edges). Correct (separates shelf/cliff-top),
  sound (§3, no dropped transition), and cheap (no Tarjan — one-way moves are strictly descending).
- **D2.** The base fragment set is **capability-independent** (free moves only); the 4 cap-combo graphs are
  **union-find overlays** (+break/+place add crossings; union where an added crossing makes a one-way pair
  mutual). One base flood + 3 cheap boundary passes, **not 4 floods** (lever 1). Reuses
  `PyramidMerger.combineFragments`'s union-find pattern.
- **D3.** Lever 2 (mask-backed predicates + per-node envelope precompute) is a **performance pass that
  ships first, on search, JFR-driven** — independently valuable and de-risks #6's flood cost. The flood
  gets the static-mask total win; search gets the edit-aware per-node win (§5).
- **D4.** Node domain = the block tier's occupiable positions (standable+water), not raw passable air —
  fragments match the block-A* node space (exact cell convention aligned at implementation).
- **D5.** Persist base fragments + one-way crossings; **recompute the capability overlays lazily**
  (RAM-cached, tick-thread) — one set on disk, not four (§10).
- **D6.** Finer fragments raise MAX_FRAGMENTS pressure; collapse degrades to today's safe over-connected
  mass — a measured tuning input, not a correctness risk (§9).
- **D7 (supersedes v1 D0).** The v1 "caps already gated at search → annotate + heuristic vertical split"
  frame was wrong: fragment **membership** is passability-based and must become movement-based — a flood
  change, and the heuristic footing gate is rejected.

## §14. For ratification (genuinely owner-level)

- **R1 — perf-pass-first sequencing** (§8): confirm Increment P ships and lands before #6, vs co-design.
  (Leaning: perf-pass-first.)
- **R2 — locally-mutual membership + one-way crossings** (§2/§3): confirm this reading of "internally
  mutually reachable" (vs undirected-merge, which mis-merges fall-pockets, or full SCC, which is
  unnecessary). This is the core model decision.
- **R3 — node domain / cell convention** (§2, D4): occupiable-position flood — confirm, and the exact
  floor↔fragment cell mapping to align with existing code.
- **R4 — persistence** (§10, D5): persist base+crossings, recompute cap overlays — confirm vs persist all
  four corners.
- **R5 — the ~38 ms is full chunk-gen, dominated by the NavGrid build's neighbor-aware bits** (owner,
  §6). The immediate perf work is that NavGrid build, not this doc.

---

## §15. The directional-asymmetry problem (the deferral blocker) — owner 2026-07-25

For a **no-capa bot**, `Fall` is a real one-way move. Cliff-top → bottom is valid (descend); bottom → top
is impossible (no ascent without place/break). The naive binary is a false choice:

- **Connect** (merge top+bottom into one fragment) → **lies about the ability to ASCEND** (a bottom→top
  search thinks the top is reachable, floods, fails).
- **Disconnect** (two fragments, no edge) → **lies about the ability to DESCEND** (a top→bottom search
  thinks the bottom is unreachable, misses the valid `Fall`).

Neither is right because the relationship is **directional**. The honest representation is the **directed
one-way crossing** (§2/§3): top and bottom are separate fragments with a crossing `top→bottom` only. Then
a top→bottom search follows it (descent works) and a bottom→top search finds no reverse crossing (ascent
correctly unreachable). **Neither lie.** §15 records that this IS the crux, not a flood detail.

**Tractable:** every one-way free move is strictly **descending** (Fall, falling-parkour, walk-off-down),
so the one-way crossings form a **strictly-descending DAG** — no directed cycles. The region graph is
`undirected mutual-fragments + a descending-DAG of directed crossings`.

**A real project (the deferral reason):** the region tier must become **directed-edge-aware end to end** —
the forward region A* already is, but the **goal-rooted reverse Dijkstra** (`costToGoalField`) must follow
crossings **backward** (in-edge traversal), and the **coarse roll-up** (`PyramidMerger` union-find) must
**not** merge across a one-way edge (or a parent fragment re-asserts the mutual reachability the split
removed). For a **capable** bot the asymmetry mostly dissolves (the +place/+break overlay unions top+bottom
— build/mine a staircase, §4), so this is a **no-capa-specific structural cost**.

**Owner ruling — DEFER #6.** Search-time invalidation + re-search already deliver correctness (a no-capa
bot at a cliff bottom searches, floods, invalidates, and honestly gives up without #6). #6 is purely an
optimization (don't waste search on the known-impossible), not worth building the directed region tier
until the directional model is fully settled AND the compute budget exists.

**The active next work: the NavGrid-build performance pass.** Distinct from #6's lever 2 (movement-predicate
reuse) and *upstream* of it: the ~38 ms adversarial chunk-gen is dominated by the NavGrid build, and most of
that by the **neighbor-aware bits (depth nibbles, safe-break)** (§6). Speeding that up is a measurable win on
its own AND frees the budget that more-accurate (movement-verified, directed) region building would spend.
See the forensic on the NavGrid-build hot path (2026-07-25).
