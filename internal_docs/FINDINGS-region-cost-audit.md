# FINDINGS — region-tier cost & targeting audit (2026-08-02)

Four convicted issues, with evidence, from a four-way code audit plus a live `/bot rtrace` on the
flagship route `(60,180,253) → (201,-28,90)`. **Nothing here is fixed yet.** Written to be picked up
in a fresh context.

Trace: `orebit-mc121-wt/run/orebit-region-trace.txt` (125 MB / 1.33 M lines — QUERY it, never read it
whole). Header: `caps=[safeFallDistance=3, maxFallDistance=16, takesDamage=true, canBreak/canPlace=true,
maxNodes=40000, greedyWeight=2.0]`, `CASCADE top=L2`, skeleton 12 steps.

---

## 0. The governing principle (owner, 2026-08-02)

> **Under-estimating is admissible. Over-estimating is not.**

A region cost that is too LOW is optimism the block tier corrects on contact (the existing "§6 online
optimism" contract that already covers unbuilt regions). A region cost that is too HIGH silently
deletes a route that was never explored — unrecoverable, and invisible in any log.

The standing three-way tension: **admissible** (find optimal routes) vs **conservative** (don't burn
budget on routes that may not realize) vs **performant** (bounded time, avoid floods/partials).

### Already ruled OUT — do not "fix" these

- **`dy` treated exactly like `dx`/`dz` in the heuristic is CORRECT.** It is a 3D world; walking up
  stairs is as easy as walking flat. Special-casing Y previously caused a large bug class.
  `SimpleRegionHeuristic.estimate` is fine as written.
- **A vertical break being cheaper than a horizontal one is CORRECT** — it is literally half the
  blocks (`digCost` charges `2·horizSpan + vertSpan`). The expectation is that caves beat digging;
  issue #1 is why they currently don't.
- **Unbuilt-region optimism is fine** — it only affects distant regions, not local skeletons.
- **No arbitrary timers.** Replans are EVENT-driven (fragment transition, fell-off-path, goal moved).
  The 40-tick timer is a DEBOUNCE on world-edit detection — "have there been edits that might have
  altered my route" — NOT a replan trigger. Do not propose timer-driven replanning.

---

## 1. `UNSAFE_VERTICAL_PENALTY` is applied to every dry fragment, not just AIR

**Owner ruling: it should apply only to `KIND_AIR` regions.** Falling through pure air is genuinely
dangerous for a takes-damage bot; walking down a slope is not.

`RegionPathfinder.transitCost` consults the `TYPE_S` bit ONLY inside the water branch; every dry
fragment falls through to `walkCost` → `dyCost` → the cliff penalty:

```java
if ((typeBits & TYPE_W) != 0) {
    if ((typeBits & TYPE_S) == 0) return horiz + |dy| * SWIM_VERTICAL_PER_BLOCK;
    return horiz + dyCost(...);          // S·W
}
return walkCost(dx, dy, dz, ...);        // ALL dry → dyCost → +cliff
```

`dyCost` (`RegionPathfinder.java:1980-1987`) charges `(drop − safeFall) × UNSAFE_VERTICAL_PENALTY`
(16/blk) on the DOWN branch, with an explicit comment that it is "Not gated on canPlace".

**The code contradicts its own javadoc**, which states the intended split: `¬S·¬W` gets "the fall/cliff
terms", and "**S-only = plain walk (1.0, unchanged)**". `TYPE_S` = *surfaceable*: "∃ a component cell
with footing … and air-only headroom" (`RegionFragments.java:122`). A provably-walkable fragment is
charged the identical cliff penalty as open air.

### Measured, from the trace

```
cave descent   traverse[horiz=16.24 + down=19.0 (19×FALL 1.0/blk) +cliff=256.0]   = 291.2 ticks
intra-region   mine-sibling: walk=16.0 (16 blk) + dig=83.48 ((2·16+0) blk × 2.61) =  99.5 ticks
```

`(19−3)×16 = 256` — the penalty is **88% of the edge** and **8.3× the honest cost (35.2)**. Digging
beats caving by ~3×. Remove the penalty and the same edge is 35.2 vs 99.5 — the cave wins by 2.8×,
i.e. **this single change flips the preference.** Scales exactly as the formula predicts:

| drop | real | `+cliff` | charged |
|---|---|---|---|
| 19 | 35.2 | 256 | 291.2 |
| 27 | 41.0 | 384 | 425.0 |
| 31 | 48.0 | 448 | 496.0 |

30 548 cliff-penalised edges across 31 093 expansions — roughly one per expansion.

### Note on the prior regression

The water branch's javadoc records that discounting on the `W` existence bit "manufactured ~8×-cheap
phantom ascents through wet caves (**the cliff-repro regression**)". `S` is likewise an EXISTENCE bit —
one standable cell, not proof of a continuous ramp. **Owner ruling: acceptable anyway**, because
under-estimating is admissible and the block tier corrects. Supporting asymmetry: the W regression was
about **ascents** (often genuinely unrealizable → re-attempt churn); this is a **descent**, and a drop
is nearly always realizable — worst case a `Fall` with damage cost, not a dead end. Optimism degrades
to "costlier than estimated" rather than "impossible".

---

## 2. L2 flooded at the cap — should it have escalated to L3?

`RegionPathfinder.java:67` `MAX_REGION_EXPANSIONS = 20000`. The trace shows **exactly** that at L2:

```
L2  20000 expansions      <- the cap, i.e. a flood
L1  10569
L0    524
```

Owner's expectation: *"flooding at L2 is a signal that this is actually an L3 problem."*

Current behaviour does NOT do that. Escalation machinery exists but is scoped to **tube-confined
nulls** (`HierarchicalRegionPlan.rederiveWithTubeEscalation`), and the class javadoc (`:226-228`) says
"only an UNTUBED top-level null with escalation exhausted (**or a flood at the coarsest level** / the
escalation backstop) is a genuine no-route". The trace header says `CASCADE top=L2`, so L2 *was* the
coarsest level here — a flood there is classified as no-route rather than as a reason to raise the top.

**Open questions for the fix:**
- What chose `top=L2` for a 208-block descent? (The audit cited a `chooseCapSafeLevel`; confirm it and
  its inputs.) `RegionAddress.OCTREE_TOP` also matters — above it there is no vertical dimension at all
  (`RegionAddress.java:126`, `RegionPathfinder.java:1543` skips faces 2/3), so raising the top has a
  correctness ceiling.
- Should a flood at the top level *raise* the top and re-derive, rather than accept the flooded result?
- Is 20000 the right cap per level, or should it scale with level?

---

## 3. Intra-region dig cost is over-priced (and axis-destroying)

```java
private static void mineSpans(int ax, int ay, int az, int bx, int by, int bz, int level, int[] out) {
    int hs = Math.abs(ax - bx) + Math.abs(az - bz);
    int vs = Math.abs(ay - by);
    final int floor = RegionAddress.sideOf(level) / 2;      // 16 at L1, 8 at L0
    if (hs + vs < floor) { hs = floor; vs = 0; }            // rounds UP and converts vertical -> horizontal
```

Two separate faults:

1. **The floor over-estimates** — half a region for fragments that may be 2 blocks apart. This violates
   §0 (over-estimating is inadmissible), which is why the owner calls it "always a bug".
2. **`vs = 0` destroys the axis** — a purely VERTICAL separation is re-charged as horizontal, and
   `digCost` charges `2h + v`, so the cheap axis is converted into the expensive one. A 4-block drop
   between stacked fragments (`4 + 4m ≈ 22`) is charged as `8 + 16m ≈ 82`.

Visible in the trace as three different fragment pairs with byte-identical costs — the floor firing,
erasing the geometry that would distinguish them:

```
mine-sibling: walk=16.0 (16 blk) + dig=83.478264 ((2·16+0) blk × mine=2.6086957/blk) = 99.478264
mine-sibling: walk=16.0 (16 blk) + dig=83.478264 ((2·16+0) blk × mine=2.6086957/blk) = 99.478264
mine-sibling: walk=16.0 (16 blk) + dig=83.478264 ((2·16+0) blk × mine=2.6086957/blk) = 99.478264
```

### Why it is hard, and the sketch

We do **not** store full fragment geometry — only the **bounding boxes of the face intersections** (6
faces per fragment) plus `passFrac` and `avgSolidHardness`. Everything else must be approximated.
`fragmentCentroidWorld` falls back to the REGION CENTRE for a fragment touching no face, which is the
degenerate case the floor was presumably guarding.

Direction worth exploring (unratified):
- Two fragments in the same region are **distinct passable components**, so they are separated by ≥1
  solid cell — a 2-tall opening ⇒ an admissible LOWER bound of ~2 blocks, not half a region.
- Use **minimum separation between the two fragments' face-footprint bboxes** rather than centroid
  Manhattan: strictly closer to truth, and it PRESERVES which axis the separation is on.
- Keep a floor only for the genuinely-degenerate no-face fragment, and make it a lower bound.

---

## 4. Window targets are mostly `[air-no-floor]` / `[buried]`

Of the 15 L2 skeleton steps in the trace, only **two** portals are `[stand]`:

```
L2.1  frag=1 portal=(12,128,204)[buried]        L2.4  frag=0 portal=(64,128,220)[stand]
L2.2  frag=8 portal=(8,127,204)[buried]         L2.11 frag=13 portal=(164,-1,84)[stand]
L2.5  frag=0 portal=(92,128,191)[air-no-floor]
L2.6  frag=0 portal=(128,128,156)[air-no-floor]  ... etc
```

Fragments are passable components with real footing somewhere, so their boundary crossings SHOULD yield
standable cells far more often than 2-in-15.

This is the upstream cause of the `WindowTargeting` fallback cascade (see §5): an unusable portal forces
`snapInFootprint`, and a window where nothing snaps falls back to the raw region **CENTER**. Confirmed
instance: target `(72,152,200)` is exactly `RegionAddress.centerX/Y/Z(0,4,13,12)` — the CENTER fallback,
which sent a bot standing on the jungle floor at `y=144` climbing to `y=152` to chase a target that the
next skeleton immediately re-aimed 24 blocks down.

**Where to look:** `portalCell(i)` is `footprintCenterWorld(...)` — the **centroid of the entrance-face
footprint bbox**, floor-anchored (`RegionPathfinder.java:2188-2197`). A bbox centroid is not a member of
the set it bounds: for an L-shaped or annular face intersection the centroid can easily land in rock or
mid-air. That is a strong candidate for the root cause and should be checked first.

---

## 5. Context: the window-slide issue (deferred, separate arc)

Deferred by the owner, documented so it isn't re-discovered. The bot walks the WHOLE ~37-block distance
to a 4-region-out window target instead of re-targeting after ~1 region.

The boundary gate EXISTS (`PathPlan.java:652` `curRegion > committedIndex` genuinely is "the bot's
region changed") but is conjoined with two conditions that are **both deliberate and correct**:

- `committed(curRegion)` — the block plan must vouch. Owner: contrived paths bounce between two
  fragments repeatedly and must settle on the LAST crossing; and the block tier sees obstacles the
  region tier cannot, sometimes requiring a dip into neighbouring regions. **The block plan's knowledge
  is always trusted over the region tier's.**
- `!lastPlanPartial` — owner's two candidate reasons: (a) **anti-flapping** (partials pointing in
  opposite directions ping-pong the bot across a boundary forever — reproducible with a wide wall
  between bot and goal where walking around is possible); (b) **invalidation blame** — following a
  partial to its terminus and THEN getting stuck is concrete proof the region crossing is
  unrealizable; abandoning it after a few blocks yields no evidence and may fail to invalidate.

So this is **not** a regression, and neither conjunct should simply be removed. The remaining question
is how the window should advance without breaking either guarantee.

Also relevant: `onRoute` (`PathPlan.java:835-850`) matches within ±1 of ANY waypoint, so
`deviated = !inWindow && !onRoute` is false for an entire plan-following walk — the region tier returns
`UNCHANGED` and never rolls the window.

---

## 6. Also found, not yet raised

- **The "prefer a known-good route over a partial" policy does not exist in code.** It was built and
  reverted byte-identical on 2026-07-25 (`DESIGN-anti-flap.md:340`). Both plan-install sites overwrite
  unconditionally. The live behaviour is the opposite: `PathPlan.java:664` FOLLOW-TO-TERMINUS makes
  partials *stickier* than full plans.
- **Partial endpoints are chosen by straight-line octile only** (`BlockPathfinder.java:933`), so a deep
  descent always scores as "progress" even as its g-cost climbs (measured 695 → 1170 across successive
  replans).
- **Async would not have rescued the flagship partials**: 250 ms at the measured 6.5 µs/node ≈ 38 k
  nodes, slightly FEWER than the 40 k sync cap. The 262 k `TIME_MODE_NODE_BACKSTOP` never binds.

---

## 7. How to query the trace

```bash
grep -c '+cliff='            run/orebit-region-trace.txt    # cliff-penalised edges
grep -m5 '+cliff='           run/orebit-region-trace.txt    # the breakdown, incl. the honest cost
grep -m5 'dig='              run/orebit-region-trace.txt    # mine-sibling / dig-through pricing
grep -o '^E [0-9]* L[0-9]'   run/orebit-region-trace.txt | grep -o 'L[0-9]' | sort | uniq -c
head -40                     run/orebit-region-trace.txt    # caps + full cascade skeleton with portal tags
```

Portal tags in the skeleton dump: `[stand]` / `[air-no-floor]` / `[buried]` — the fastest read on §4.

Raw geometry from the frozen master: `internal_docs/tools/peek.py` (Anvil reader) and `slices.py`
(per-X slice renderer). Both take a world DIRECTORY and a box.
