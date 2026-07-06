# PERF-DESIGN: region-tier dig-through connectivity + walk-across cost

**STATUS: RATIFIED (owner design-reviewed + locked 2026-07-05; implementation in progress via workflow).**
Supersedes the earlier "centroid" walk-cost framing — the chosen walk model is **entry→exit** (§4), and the
node identity gains an **entry-face** component to keep edge costs fixed (§2). We are NOT preserving current
behavior (it is known-bad); validation is a region-tier JMH perf-guard + expected-*improvement* tests (§7).

Per the CLAUDE.md performance protocol, this records mechanism + invariants + expected win + risk BEFORE
implementation. Nothing here is merged. Validation (paired JMH A/B) is gated on a new region-tier benchmark
that only runs on the **mc-1.21 era** (JMH is unavailable on the 26.x era).

---

## 1. Problem (grounded, with in-game evidence)

`/bot gather iron` routes the bot on a long winding cavern path (15 regions: down → over → up, ~30 blocks of
digging + bridging) instead of the obvious `dig down ~12 + over ~4` (≈20 blocks) straight to buried ore.

Evidence — `orebit-region-trace1.txt` (a `/bot rtrace` from region **(5,9,-1)** to buried-ore goal region
**(5,8,-2)**, which is `kind=SOLID`):

- **E2**: region **(5,9,-2)** is expanded at **g=1.0** — one cheap walk from the start, sitting **directly
  above** the goal. It emits only **5** candidates; the missing one is the **−Y face toward the goal**. So the
  cheapest possible route (walk one region over, dig straight down into the ore) is **not in the graph**.
- The goal is instead reached at **g=100.7** via a detour through (3,9,-1)…(5,8,-3).
- Nearly every lateral `walk` edge shows **cost=1.0**, so the winding air route is almost free.

Two root causes, both in `RegionPathfinder.planLevelFragments`.

### 1a. No dig-out through a face the fragment does not touch (the connectivity hole)

`RegionPathfinder.java:404`:
```java
for (int f = 0; f < 6; f++) {
    if (!uniformN && !rfN.touchesFace(fragA, f)) continue;   // ← skips the whole face
```
For a MIXED node, a face its fragment does not touch emits **zero edges of any kind** (the neighbour at
`:409-413` is never even resolved). (5,9,-2)'s air pocket does not reach that region's floor, so no dig-down
edge exists.

**Correction to earlier analysis:** the graph is NOT "fully connected for a digging bot." It DOES already
emit dig edges into uniform-SOLID neighbours (`uniformTransitCost` KIND_SOLID, `:734`) and MIXED
`mine-fallback`/`mine-solid` (`:466-484`) — **but only across faces the fragment touches.** The precise hole is
`:404`: a MIXED region cannot dig out through a face its air pocket does not reach.

### 1b. Walk edges price the boundary crossing, not the region traversal (the "1.0" bug)

The `walk` edge (`:456`) costs `walkCost(footprintCenterWorld(A,f) → footprintCenterWorld(B,oppF))`. Both
anchors sit **on the shared face plane, ~1 block apart**, so it charges the boundary crossing but **never the
cost of traversing the region to reach that boundary**. Moving within a 16-block region is neither instant nor
free; charging ~1 makes the whole open cavern near-free to wander, which is the amplifier behind the detour.

---

## 2. Node model (confirmed) + the entry-face augmentation (RATIFIED)

An HPA* node today is **`(region, fragment)`**, not `(region)` — each 6-connected occupiable air component of a
region is its own search node (`nodes.frag[current] = fragA`; key = `fragmentKey(rx,ry,rz,frag)` XORing the
fragment id into bits 50–55). A region contributes one node per fragment (usually 1; a handful in caves; a
synthetic fragment 0 for uniform/collapsed). This is why a **per-fragment** dig-through is correct: the dig
cost depends on how far the *source fragment* sits from the face.

**The rework augments the SEARCH node to `(region, fragment, entryFace)`** — the face (0–5, plus a `START`
sentinel) through which the node was entered. Rationale: the §4 entry→exit walk cost makes the cost of an edge
*out* of a fragment depend on *where you entered it*. If entry is not part of the node identity, that cost is
**path-dependent** (the stored `portalX/Y/Z` is overwritten by the min-`g` parent — `relaxFrag:536`), softly
violating A*'s fixed-edge-cost assumption. Putting `entryFace` in the key makes "fragment entered from the
north" and "…from the south" distinct states with fixed edge costs → clean, consistent A*. One footprint per
`(fragment, face)` means `entryFace` fully determines the entry-opening center, so face granularity (≤6× nodes,
usually far less) is sufficient — no need to key the full portal cell.

**Node-count is a non-issue** (owner call): a level holds ≤16³ = 4096 regions and long range zooms to a coarser
level, so even a 6× blow-up is ~24k nodes — and the region tier has no flood pathology (unlike the block tier
there is no one-expensive-axis-vs-many-cheap structure: a fragmented region charges *walk* per crossing, a
uniform-AIR region charges bridging = pillar in every lateral direction, so going straight at the goal is almost
always cheapest). Worst realistic search stays far under the block tier's 40–60k floods.

**Consumers stay PHYSICAL `(region, fragment)` — do NOT thread `entryFace` into them.** The
`RegionEdgeBlacklist` keys a crossing by `fragmentNodeKey` (`PathPlan.blockedHop:1034-1037`) and the driver's
per-tick membership probe `fragmentOf` (`PathPlan:1484`) both map a *physical* bot position to a node. A
crossing's unrealizability is geometric — independent of how the FROM region was entered — so an
entry-augmented blacklist key would be over-specific (fail to suppress a dead crossing reached via a different
entry). And `entryFace` is a search-state attribute, not a world one, so it must not leak into position→node
resolution. The skeleton already stores the entry portal cell per hop (`reconstructFragments:601-603`), so any
consumer that needs the entry face can recover it — but by design none should. **Only the search frontier
carries `entryFace`.**

---

## 3. Fix 1 — the dig-through edge (the untouched-face hole)

**Connectivity principle (owner-ratified).** The Minecraft world is fully 6-connected: between any two adjacent
regions *some* move always exists, at a cost — walk/fall through an opening, **bridge/pillar** up into open air
(needs place), **dig** through solid (needs break). The region tier must therefore offer **every
capability-permitted move**, withholding an edge *only* where the bot's caps can't do it (no-place → no
bridge/pillar edge; no-break → no dig edge). It must **never statically declare a face "unreachable."** True
unreachability is discovered exclusively at the **block tier**: a partial path that *starts* with an
irreversible move → the crossing is blacklisted (`RegionEdgeBlacklist`) and the region A* re-plans without it.
(Better block-A* pruning ⇒ fewer partials ⇒ that repair path fires more rarely.)

**What already works — leave it alone.** The face loop already emits the right caps-gated edges for every face
`fragA` *touches*: walk/parkour across an overlapping opening (`:456`), `uniformTransitCost` for a uniform
neighbour — **pillar** cost rising / **fall** cost dropping into AIR (`:433`, caps-gated at `:423`/`:431`),
swim into WATER, mine into SOLID — and the MIXED `mine-fallback`/`mine-solid` for a `canBreak` bot with no
overlap (`:466-484`). The uniform-AIR bridge/pillar/fall edge is exactly the "no dig, but a place/fall cost"
move; the `:431` skip correctly removes it only for a genuinely `!canPlace` bot. **This change does not touch
any of that.**

**The one hole:** a face `fragA` does **not** reach (`:404` `!rfN.touchesFace(fragA,f)` → `continue`) — i.e.
there is SOLID between the fragment's air pocket and that face — emits *nothing* today. That is the missing
"dig through our own region's rock to the sealed face" move.

**Mechanism — emit inline, no bitmask.** The dig-through condition *is* exactly the `:404` untouched-face test,
so emit the edge right there instead of `continue`-ing:
```
if (!uniformN && !rfN.touchesFace(fragA, f)) {
    if (canBreak && !(level >= OCTREE_TOP && (f==2||f==3))) emitDigThrough(f);   // solid between fragA and face f
    continue;
}
```
Emit **directly** (compute the dig cost + `relaxFrag` into the neighbour's fragment 0 / face-centre) — do NOT
fall through to the walk path, which would read `packedA = footprint(fragA,f) = NO_FACE` and let
`footprintsOverlap` treat it as a full face (spurious walk edges). Because we emit-then-`continue`, there is no
double emission and **no coverage bitmask / post-loop pass is needed** (the earlier draft's bitmask was to also
"close" *touched-but-no-edge* faces like the `:431` air case — that was wrong: an open-air face has nothing to
dig and is already correctly caps-gated; forcing a dig edge there would offer an impossible move). Granularity
is **per-expansion (per fragment-node)** for free — A* expands one `(region, fragment[, entryFace])` node at a
time, so each fragment emits its own dig-through across a sealed face, priced by *its* source distance (two
fragments of one region that both fail to reach a face each dig their own way out — correct, the cost differs).

**Cost (distance-based).** Mirror the existing sibling `mineCost` but aim at the neighbour's face:
```
cost = manhattan( fragmentCentroidWorld(fragA) , footprintCenterWorld(N, oppF, NO_FACE) )
       × MINE_PER_BLOCK × hardnessFactor(rfN.avgSolidHardness())
```
Uses **our** region's hardness (the rock we tunnel through to reach the sealed face); the neighbour's own
material is charged by the neighbour's edges once we're in it. Distance-based, so a wall-hugging fragment pays
little and a far one pays more (owner's "close vs far"). Level-aware for free (all terms scale with `level` via
`sideOf`/`hScale`/`fragmentCentroidWorld`); pyramid roll-up preserves `avgSolidHardness` (`PyramidMerger:264`),
so it prices correctly at every cascade level.

**Trace check.** Stone → the (5,9,-2)→(5,8,-2) dig-down costs ≈ `~12 × 3 × 1 ≈ 36`; total `1.0 + 36 ≈ 37 ≪
100.7`. The dig route wins. Deepslate (nibble 8) ≈ 72 → still < 100.7. Genuinely hard rock legitimately loses
to wandering — ordinally right.

**Target side.** One edge into the neighbour's fragment 0 (or nearest touching `oppF`), never one-per-neighbour-
fragment. Crossing cell = `footprintCenterWorld(N, oppF, NO_FACE)` (buried when N is solid — handled in §5).

**Unbreakable rock.** `avgSolidHardness` folds in unbreakable cells (bedrock/obsidian sum 255 —
`FragmentLeafComputer:89`), so a bedrock-bearing region inflates toward "hard" but not "undiggable." Rather
than a coarse region-level unbreakable test, lean on the existing **`RegionEdgeBlacklist`** online repair
(`relaxFrag:524-528`): if the block tier cannot realize a dig, it blacklists that crossing and the region A*
reroutes. Block tier is the source of truth.

---

## 4. Fix 2 — walk-across cost (kill the 1.0)

**Why the walk edge is ~1 today (the model gap).** The `walk` edge measures
`footprintCenterWorld(A, f) → footprintCenterWorld(B, oppF)`. `footprintCenterWorld` places its point **on the
face plane** (`:803-808`, e.g. +X → `x = ox + sideH - 1`), so A's exit opening and B's entry opening sit **one
block apart across the shared boundary**, aligned (the overlap check guarantees it). So the edge charges the
**boundary crossing only** — never the traversal across the region to reach that boundary.

This is a **simplification of textbook HPA\***. Standard HPA* has two edge kinds: *intra-region* edges (walk
between two borders of the SAME region — where the traversal cost lives) and *inter-region* edges (the ~1 hop
across a shared boundary). Because an Orebit node is `(region, fragment)` — **one node per fragment, not one
per border** — there is nowhere to hang the intra-region traversal, so only the ~1 inter-region hop is charged.
Moving within a 16-block region is treated as free. That is the ~1 bug at its root; it is a real corner cut in
the model, not a tuning value.

**Three ways to re-introduce the traversal cost** (all keep the crossing CELL as the opening — the block tier
needs a real passable cell to aim at):

| Option | Cost expression | Accuracy | A\* cleanliness | Effort |
|---|---|---|---|---|
| **A. Centroid** (proposed default) | `walkCost(fragmentCentroidWorld(fragA) → fragmentCentroidWorld(fragB))` | coarse: ~one region pitch (~16) per hop regardless of entry/exit geometry | clean — edge cost is fixed (path-independent) | tiny, reuses `fragmentCentroidWorld`, no new field |
| **B. Entry→exit** (the faithful HPA* traversal) | `octile(entryOpening_A → exitOpening_A on face f) + ~1 boundary hop`, where `entryOpening_A = nodes.portalX/Y/Z[current]` (the cell we crossed INTO A through) | accurate: a corner-to-corner cross really costs ~48, a clipped corner ~2 | **mildly path-dependent** — the edge cost depends on the parent's entry opening, softly violating A*'s fixed-edge-cost assumption (the exact thing border-nodes exist to avoid). Bounded by region size; acceptable for an ordinal tier | tiny, reuses the already-stored portal cell, no new field |
| **C. Border nodes** (textbook HPA*) | true intra-region mini-search between per-border nodes | exact | clean | **large restructure** — many more nodes + intra-region pathfinds; not worth it for an ordinal tier |

**RATIFIED: B (entry→exit).** Centroid (A) is rejected — it is geometry-blind: two adjacent regions' centroids
are ~one region pitch apart *regardless* of where you enter/exit, so it prices every hop at ~16 and cannot tell
a corner-clip (~2) from a corner-to-corner cross (~30). Entry→exit prices the real traversal:
```
cost(A→B) = walk( entryOpening_A → exitOpening_A )   // cross the region — the missing term
          + walk( exitOpening_A → entryOpening_B )   // the ~1 boundary hop we already charge
```
where `entryOpening_A` = the node's stored entry-opening center (from `entryFace`, §2 — `nodes.portal[current]`
for a settled node), and `exitOpening_A`/`entryOpening_B` = `footprintCenterWorld` on the exit face / opposite
face (the existing `wa`/`wb`). The path-dependence that made B "less clean" in the earlier draft is **resolved
by the §2 entry-face node identity** — with `entryFace` in the key, `entryOpening_A` is fixed per node, so edge
costs are fixed and A* stays consistent. **Start node** (`entryFace = START`, no entry opening): hop-0
within-region cost uses the bot's actual start floor cell as the entry point (honester than any synthetic
opening). **C (border nodes) remains out of scope** — the entry-face augmentation gets ~all of C's fidelity at
a fraction of the restructure.

**Scope split (both A and B).** Only the *horizontal* term becomes honest here. The *vertical* (Δy) stays
opening/centroid-based (mid-air) — that is the deferred standable-anchor refinement (§6), not this change.

**Perf note (B).** Entry→exit is cheap: `entryOpening_A` is already stored on the node (no recompute), and the
added work is `footprintCenterWorld(fragA, exitFace)` (which `wa` already computes at `:418`) + one extra
`octile`/`walkCost` on the entry→exit leg. So per-edge cost rises by ~one `walkCost` call vs today. Hoist
nothing centroid-related (centroid rejected). The JMH bench (§7) must still confirm the setup-bound short-search
scenarios don't regress — and that the ≤6× node-identity split doesn't inflate expansions on the dig-heavy /
zero-cap scenarios beyond the perf budget.

---

## 5. Consumer handling (block tier realizes the dig)

A dig-through crossing cell is **buried in solid**. Today `PathPlan.windowTarget` would reject/relocate it:
`isUsableTarget` returns false for solid (`:1285`), and `snapInFootprint(requireStandable=true)` either returns
null or snaps to a floor on the **near** side of the wall (`:1327-1334`), defeating the dig. The block A*
*itself* can dig to a buried target (reaches within the `isGoal` ±1/±2 tolerance, prices break-through under
`canBreak` — `BlockPathfinder.java:713,938`); the gate is purely in `windowTarget`.

- **Observed bug (dig into the GOAL region):** handled with **no consumer change** — `windowTarget`'s GOAL
  branch (`:1167-1174`) already passes a buried `goalFloor` through unfiltered. A short skeleton
  `(5,9,-1)→(5,9,-2)→(5,8,-2)` digs down correctly with Fix 1 alone.
- **Intermediate dig-through (through a non-goal region):** needs a dig-tag passthrough — tag dig edges on the
  skeleton (a per-step flag threaded `relaxFrag → Nodes → reconstructFragments → RegionPathPlan →
  windowTarget`) and have `windowTarget` return a tagged dig crossing directly, like the GOAL branch. Include
  it, but it is only *exercised* once we have an intermediate-dig repro (deferred verification).

---

## 6. Deferred (not in this change)

- **Honest Δy / standable crossing anchor.** No new stored field required (correction to earlier claim): project
  the per-face footprint bbox one block into the from/to region and run the **existing** block-side standable
  scan (`snapInFootprint` already projects+scans). The deferred work is the honest-Δy *cost* term plus the
  known-hard "mid-air on the underside of a block (only reachable by falling through to the floor past it)"
  case. Chase with repros later.

---

## 7. Validation & risk

- **No region-tier JMH benchmark exists** (`PathfinderBenchmark` is block-only; `ConnectivityBenchmark` is the
  flood-fill primitive). **Prerequisite:** a new region-tier bench driving `RegionPathfinder.plan`/`planWithin`
  over representative scenarios — open-cavern, walled/sealed (dig-heavy), multi-fragment cave, long-range
  cascade — ideally via a synthetic `RegionGrid`/pre-populated `RegionFragments` seam (analogous to
  `PathfinderBenchmark`'s synthetic `NavGridView(minY, chunks)` ctor) so it runs headless. **JMH runs only on
  the mc-1.21 era**, so authoring is on `core`, running is at-home on `mc-1.21`.
- **We are NOT preserving behavior — current behavior is known-bad.** So do NOT re-baseline existing skeleton
  assertions to "match the new output" (that just enshrines whatever we produce). Instead author
  **expected-improvement** tests that encode what a correct search *should* do — RED on current `core`, GREEN
  after the rework: e.g. (a) buried-ore directly below → skeleton digs straight down (few hops, dig edge into
  the goal region) rather than the long cavern detour; (b) entry→exit walk cost reflects opening geometry (a
  corner-to-corner cross costs ≫ a corner-clip, and ≫ the old ~1); (c) a MIXED region sealed on a face still
  emits a `canBreak` dig-through across it (full 6-connectivity). Existing region tests
  (`RegionPathfinderFragmentTest`, `HierarchicalCascadeTest`, `PyramidMergerTest`, `FragmentBuilderTest`,
  `HpaMilestoneTest`) that assert *fragment/pyramid data* (not skeleton cost/order) should still pass untouched;
  any that assert the old winding skeleton is a bug-enshrining test → delete or convert to an improvement test,
  intentionally and noted.
- **Fan-out.** Fix 1 adds ≤6 dig edges per MIXED node (≈ doubling candidate emissions), but dig edges are
  expensive → mostly heap-resident, rarely popped; the added cost is relax/heap-push, not extra expansions.
  Bounded further by the cap-safe level selection and the fully-connected geometry making most searches
  trivial. Allocation-free (reuses `wa/wb/wc` scratch + `relaxFrag`). Must still be measured.
- **Heuristic.** Region A* is `f = g + h·hScale` at weight 1.0 (no greedy multiplier — that is block-tier-only),
  `h` already mildly inadmissible/ordinal (COST_PER_REGION=16). Expensive dig edges never make `h` over-
  estimate; the higher walk costs move `g` closer to `h`'s scale (fewer floods). Admissibility not weakened.
- **Kept only on** a targeted win (correct skeletons on the repro scenarios) with no JMH scenario regressing
  beyond noise and tests green; reverted without sentiment otherwise.

---

## 8. Implementation order (authored on `core`; JMH measured on `mc-1.21`)

Code (common logic + tests + bench) is authored on **`core`**; the region-tier JMH bench only *runs* on the
**mc-1.21 era** (JMH unavailable on 26.x), so the measurement step is a `git merge core → mc-1.21` +
`Set active project to 1.21.4` + `:1.21.4:jmh` dance (owner-driven, per the bench-traps in memory).

1. **Synthetic region seam** — a headless way to construct a `RegionGrid`/`RegionFragments` fixture from a
   compact scenario spec (hand-built fragment records via the package-private `RegionFragments` setters),
   analogous to the block tier's `NavGridView(minY, chunks)` ctor. Shared by the bench and the tests.
2. **Region-tier JMH benchmark** driving `RegionPathfinder.plan`/`planWithin` over representative scenarios —
   open-cavern, walled/sealed (dig-heavy), multi-fragment cave, long-range cascade, and a zero-cap (no
   break/place) variant. Capture a **baseline on current `core`** before any cost change.
3. **Expected-improvement unit tests** (§7) — RED now, GREEN after.
4. **Fix 2 + node identity** — entry→exit walk cost (§4) + augment the search node to `(region, fragment,
   entryFace)` (§2), consumers stay physical. `START` sentinel + start-floor hop-0 cost.
5. **Fix 1** — per-fragment dig-through via the coverage bitmask (§3), `canBreak`-gated.
6. **Consumer dig-tag passthrough** (§5) for intermediate buried-cell targets.
7. **Re-measure** JMH (new vs the step-2 baseline — the perf guard, not an A/B of a preserved behavior) + the
   expected-improvement tests green + in-game `/bot rtrace` before/after on the gather repro.
