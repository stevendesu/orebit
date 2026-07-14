# HPA* Jungle Treetop Flooding -> Pillar to World Height Pathology

## Setup

A pre-generated world ("Autotest Master") was loaded. Pre-generation was necessary because even when using
the same seed, some decorative features such as trees will generate differently based on non-deterministic
thread ordering.

Within this world the bot was initialized at position `(60,180,253)` - on top of the tallest jungle tree in
the forest. This was intended to provide an immediate puzzle for the bot ("how do I descend this tree?").

The bot was then asked to navigate to `(201,-28,90)` - a deep underground trial-chamber. Proper navigation
at this distance would exceed L0's "max chebyshev" of ~8, testing our HPA* cascade level selection. Thus a
path must first be created at the L1 level. Within that skeleton a partial goal is chosen to be routed at
the L0 level. Then within THAT skeleton routing is done at the block level.

The final goal would involve navigating a complex and twisting cave network, a few carefully selected locations
where digging is absolutely necessary, and avoiding several gaps and hazards.

The bot was bare-handed, which makes both mining and placing very expensive:

 * Mining is expensive because the cost is calculated in ticks required to break the block, and breaking a
   block barehanded incurs a 5x "not using the right tool to obtain drops" penalty
 * Placing is expensive because the cost is calculated in "ticks required to dig this block back out later",
   incurring the same barehanded penalty

The high cost of mining and placing should cause the bot to favor wiggling through the caves instead of
digging straight to the goal.

## Problem

Rather than make any progress towards the goal, the bot **pillars straight up to the world ceiling (y=319),
then bridges across the top of the world**, overshoots the goal in XZ, and oscillates. It never descends
toward the (deep, buried) goal.

## Related Docs

This document consolidates + supersedes the earlier `DIAGNOSIS-region-pillar-to-sky.md` (whose "MIXED-region
bridging" fix was wrong) and `PROPOSAL-region-air-bridging-cost.md`.

DO NOT trust those docs over this one. This doc is canonical.

## Deterministic reproduction

Frozen master world (`scripts/autotest-world-master/world`, a pre-generated world copied fresh per run) +
**bare hands** + the **persisted HPA** the world carries. Headless: `run-autotest.ps1 -MasterWorld <w>
-Start 60,180,253 -Goal 201,-28,90 -Barehanded` → Y climbs 180→319. **Both ingredients are required:**

| Setup | Result |
|---|---|
| pickaxe + clean master | descends |
| pickaxe + persisted HPA | descends |
| bare hands + clean master | descends |
| **bare hands + persisted HPA** | **PILLARS to 319** |

Ruled OUT as the cause (all descend headless): placement/mining base cost, `pathing.async`, view/simulation
distance, teleport/unbuilt-nav timing.

## The general principle

**Flooding = "the real path has a required expensive move, and there's a huge cheap field."**

Here the required expensive move is the **bare-handed dig-down** to the deep goal; the huge cheap field is
**`unbuilt = free`** — the entire unexplored void beyond the loaded/persisted world.

## What the cascade actually does (verified)

`HierarchicalRegionPlan` is a stack of per-level skeletons `L = top … 0`. `chooseCapSafeLevel`
(`RegionPathfinder.java:324`) picks the top = the **lowest** level `L` where `cheb(start→goal, L) ≤
maxChebAtLevel(L)`. For this span that is **L1** → the cascade here is **L1 → L0** (two levels; L6+ is only for
million-block spans). The top (L1) plans toward the real goal; L0 plans toward the `WINDOW_CELLS=4`-th cell of
L1's skeleton.

**Per-level search bounds (the crux):** a per-level A* (`planLevelFragments`, `:467`) runs until goal /
heap-empty / `MAX_REGION_EXPANSIONS=20000`. The ONLY bounds are (a) the sub-goal clamped to `maxChebAtLevel`
regions (`:391`), and (b) an assumed **area cap** — `maxChebAtLevel = ½√(CAP_SAFE_NODES/verticalRegions(L))`
(`:320`), sized so the maxCheb box holds ≤ `CAP_SAFE_NODES=8192` cells.

**Hierarchy & bounds reference** (`RegionAddress`: `LEAF_BITS=4` ⇒ an L0 region is 16³ blocks; `sideOf(L) =
16<<L`; branching is 2× per axis — 8 children octree for L0–L4, 4 children quadtree for L5+ where `ry` pins to
0; `verticalRegions(L) = 32>>L`; `maxChebAtLevel(L) = ½√(8192/verticalRegions(L))`):

| Level | region side | children | verticalRegions | maxChebAtLevel | flood-detect threshold (cheb from start) |
|---|---|---|---|---|---|
| L0 | 16 blk | 8 | 32 | **8** | > 8 |
| L1 | 32 blk | 8 | 16 | **11** | > 11 |
| L2 | 64 blk | 8 | 8 | **16** | > 16 |
| L3 | 128 blk | 8 | 4 | 22 | > 22 |
| L4 | 256 blk | 8 | 2 | 32 | > 32 |
| L5 | 512 blk | 4 | 1 | 45 | > 45 |

(This scenario: L0 cheb = 13 > 8 ⇒ not cap-safe; L1 cheb = 6 ≤ 11 ⇒ top = **L1**. `8192` is a chosen node-count
budget, NOT geometry: `(2·maxCheb)² × verticalRegions ≈ 8192` — e.g. L0 `16² × 32 = 8192`.)

NOTE: The theory is that our choice of L will reduce the search space sufficiently that we never exceed 8192
nodes explored. This theory does not hold, because nothing confines our search to the higher-level skeleton.
If there exists a cheap area outside of our skeleton that we can flood, we will do so.

NOTE 2: Even if we *did* constrain within the higher-level skeleton, the highest level selected (in this
case L1) has no "higher"-level skeleton to defer to - so it runs unconstrained and can flood outside of the
approximated bounds. Our `chooseCapSafeLevel` returns a level that is ONLY allegedly "cap-safe" if the path
to the goal is direct. If the real path involves first moving away from the goal and then re-approaching, then
the actual search space can easily exceed the cube bounded by our chebyshev distance choice.

## The L1 flood — measured (`/bot rtrace` now runs the full cascade, `-Rtrace` headless seam)

Full-cascade region trace of the exact bare-handed + persisted-HPA scenario:

- **L1 expanded 20,000 nodes — the full backstop.** The "cap-safe ≤ 8192 by area" invariant is **VIOLATED**:
  because `unbuilt = free`, the reachable cheap area is effectively unbounded, so the maxCheb clamp does not
  contain the search.
- **16,511 / 20,000 L1 expansions (83%) were `[unbuilt]` regions**; candidate edges were **95,141 `unbuilt`
  transits** vs 9,951 air-pillar / 8,682 walk. L1 is flooding the **free unbuilt void**.
- **L1 explored region-Y −7 … 15** (goal ry ≈ 1, start ry ≈ 7): it floods the WHOLE vertical range, down AND
  up — it is **not up-biased**. It returns a best-f PARTIAL (`reachedGoal=false`) that happens to go
  up-and-over; the bot executes that partial by pillaring.
- L0 then expanded 7,495 nodes chasing L1's (up) sub-goal.
- The cascade **accepted the flooded partial and refined it at L0** — there is **no escalation on a flood**.
  Escalation today fires only on `onBlocked` (an unrealizable hop), never on budget-hit / partial.

## Three verified defects

**(1) Forward pass prices mine vs pillar with DIFFERENT cost models.**
- Dig: **tool-honest** — `RegionMineModel.from(inventory.mining())` (`PathPlan.java:393`), deliberate
  (PERF-DESIGN region §5). Bare-handed stone ≈ 215/region.
- Pillar/bridge: **flat behavioral** `PILLAR_PER_BLOCK = 6.0` (`RegionPathfinder.java:1420`, `:1331`),
  tool-blind, no mine-out. The caps-aware `RegionPlaceModel.pillarPerBlock()` (place-base + tool-scaled
  removal premium) is wired ONLY to the reverse cost-to-goal field.
- ⇒ For a bare-handed bot, digging is over-priced *relative to* pillaring — so it prefers to pillar/bridge.
  Decides *where* the partial points (up, away from the honest-expensive dig). ~9% of L1 expansions are the
  built-air `[air]` this touches.

**(2) `unbuilt = free`.**
- An unbuilt region (`fragmentRecord == null`) returns `0f` in ALL directions (`:1386-1388`, "assume the
  best"), floored to 1/region. Deliberately MORE optimistic than known-air (which costs a pillar to climb).
- The dominant flood medium (83% of the L1 flood). Decides *how far* the search floods (the whole void).
- NOTE: the `[unbuilt]` tag in the SKELETON dump is a **block-tier `NavStore` probe** (view-distance-bound,
  transient, never persisted) — NOT this region cost. There is **no persistence bug**: the region tier
  persists all-air regions and distinguishes explored-air (`KIND_AIR` record, pillar cost) from never-seen
  (`null`, free). The one real seam: a chunk the bot never *drained* (fast spectator flight outrunning the
  8-chunks/tick nav queue) is never persisted → reads free-unknown on reload.
- The reason unbuilt regions existed at all was the flood managed to reach *unexplored* regions beyond the
  loaded map. It was NOT a failure to persist explored regions.

**(3) No region-level flood guards; the top level can flood unconstrained.**
- The region tier has NONE of the block tier's flood-shaping (forced-cost premium, cuboid macros,
  irreversibility guard). Owner-noted constraints on porting them: **can't build cuboids** (no
  "contiguous same nav-type" concept at the region tier), **can't use a region-refined heuristic** (no higher
  level exists above the top), **can't bound the top-level search** (no known bounds; no proof the optimal
  path isn't a long roundabout).
- The area cap (8192) is the only "guard", and doesn't actually guard against any expansion during search. It
  is ONLY used to compute which level we should begin our cascade. Free unbuilt regions defeat it (L1 hit
  20000). The top level is never constrained by a coarser skeleton, so when its heuristic is misled it floods
  to the backstop and returns a garbage partial the cascade silently accepts.

## Planned Resolution

Separate fixes for each of the verified defects:

### 1. Forward pass prices mine vs pillar with DIFFERENT cost models

There are two competing goals.

The first is that we want the region tier to be stable and logical, regardless of the bot's inventory. If
there is a nearby cave entrance, the bot should walk to it rather than dig straight through the ground to
enter the cave through the ceiling. Just because a path is slightly faster doesn't mean the path "makes sense".

The second is that we want the bot to move in a "near-optimal" fashion towards the goal - bridging or mining
when necessary instead of taking a 1,000-block detour that no player in their right mind would take. Again,
this falls under the "makes sense" category.

So we simultaneously both want to NOT mine through walls and want to mine through walls. A dilemma.

To resolve this, I propose we pre-compute the cost to mine with a WOODEN PICKAXE at server start time, then
use this cost consistently during forward-pass region pathfinding. This is a safe "bare minimum" we can
assume most bots will be equipped with, and does not over-index on mining-over-walking-around when the
bot has upgraded gear. It also provides a fairly high (but not infinite) cost to placing blocks, which will
discourage unnecessary pillaring or bridging.

This should, of course, factor in bot capabilities. If the bot is not permitted to mine or place, then we
should not offer paths that are KNOWN to require mining or placing. This mostly affects SOLID or AIR regions,
as "MIXED" regions we do not have enough information to know if mining or placing is REQUIRED.

This "hard-code wooden pickaxe, use that cost consistently" rule should only be applied to the FORWARD pass.
The reverse pass (Dijkstra) is not used to determine the region-level skeleton or the general shape of the
route, but is instead used to gather accurate data to improve the block-level A*. Therefore the backwards
pass should use the proper costs using the bot's available inventory tools.

---

NOTE: The forward tool-aware dig was a *deliberate* decision (PERF-DESIGN region §5, PathPlan.java:392).

We are intentionally reverting this decision.

The decision was orginally made in order to ENCOURAGE digging when a pickaxe was available (the bot
used to always walk around, even if it had a diamond pickaxe). The new design leans in the opposite
direction: A bare-handed bot may generate a path skeleton that assumes it can mine through stone.

The current belief is that this will be mitigated by two other decisions, and one current bug/limitation
we have chosen to defer addressing:

 1. We generate a full path at the block level, which IS tool-aware and will look for a path around
    such walls
 2. Wooden pickaxes are weak enough we will still favor nearly-free walks through fragments over
    trying to mine with the slowest tool
 3. While our region-to-region dig-through costs and fragment-transit costs are relatively accurate,
    our within-region fragment-to-fragment dig-through cost is known to massively over-estimate. Two
    fragments in a region may be separated by a single block thick wall, but we assume a 16-block
    thick wall every time. This discourages most digging already during region-level pathfinding

If in practice we find barehanded bots are getting stuck trying to punch through stone very often
then we will re-evaluate this decision.

### 2. `unbuilt = free`

The original reason we decided "unbuilt" should have a "free" cost was a the idea that a bot may be pathing
from a known location to a known location THROUGH an unknown location. For example:

 * You create a nether portal at (0,0,0)
 * You travel to (1000,0,1000) in the nether
 * You create a portal back to the overworld, appearing at (8000,0,8000)
 * You ask the bot to come to you from (0,0,0) to (8000,0,8000)

The idea was that the area around the bot might be known, the area around the goal might be known, but the
area in between might be unknown. So our pathfinding should draw a straight line from "the edge of the known
world" to "the edge of the OTHER known world", zipping across the gap without bothering to explore (taking
the straightest path, per A*'s towards-goal heuristic)

The **problem** with this idea was exactly our flooding definition, from above:

**Flooding = "the real path has a required expensive move, and there's a huge cheap field."**

An "expensive" move must only be expensive *relative to the cheap field*. When unloaded regions are free in
all six directions, they're cheaper than **every other move**, by definition. This means while our A* will
rapidly spread out across the unloaded gap, the moment it hits the loaded region on the goal-side the
"enter the known world" movement will cost more than "spread out orthogonally to scan the unloaded world
vertically".

So even our *intended* reason for making this cost free is unfounded - before getting into the pathology of
a pathfinding search that hits the edge of the known world moving **away** from the goal.

The challenge is in determining what the actual cost should be.

It's **NOT** accurate to say "treat unbuilt regions as AIR", because this implies a very cheap "fall" action,
which isn't right for solid regions.

It's **NOT** accurate to say "treat unbuild regions as SOLID", because this implies a very expensive "mine
down" action, which isn't right for air regions.

It's also **NOT** accurate to treat them as "6-axis walkable" because this ignores the higher cost of moving
up or down in most mediums, and can still lead to favoring unbuilt regions over known fragment paths (just
not as strongly as before).

But there's something we can take advantage of:

The only unbuilt regions are regions that EITHER:

 1. Were never loaded chunks
 2. Were loaded briefly, but were unloaded before they could be drained (the player did not spend time in them)

In either case, unbuilt regions are regions whose only contents are world generation contents. This means we
can build heuristics based on the world generation properties. There's basically three different levels of
complexity we can build here, based on measured cost. We should write benchmarks to figure out the cost of each
one of these solutions:

**Option A. Consistent heuristic**

No matter whether we're underground, on the ground, or in the air -- unloaded chunks won't have player-build
stair cases or elevators. So going upwards will either involve mining upwards, pillaring upwards, building a
staircase, or searching for a natural hill or mountain (which may require complex winding to find a series
of 1-block high ascension points). We can therefore assume that "going up will cost more than average".

Similarly, no matter the terrain, going down is almost always "easy":
 * In AIR, moving down is the cheapest movement (Fall)
 * In SOLID, moving down costs half as much as moving laterally (MineDown breaks 1 block, digging requires
   breaking two - one for head, one for feet)
 * In MIXED, there are often easy locations to stack multiple vertical descents:

Unlike ascending (which requires finding a series of 1-block high points), you can fall at a 2-block, 3-block,
or 4-block point (more for immortal bots). This makes it easier to locate descent locations than ascent
locations.

From the above, we can approximate the cost of travel as something like:

UP -> double cost
DOWN -> half cost
LATERAL -> base cost (assume walking for admissibility, or 16 units of cost to cross an L0 region)

**Option B. Height-based Heuristic**

We can start with the above, but augment it slightly with known properties of the world. In Minecraft, "sea
level" is Y=63. The vast majority of terrain generation (with the exception of Jagged Peaks biomes) never
extends above Y=128.

This means that below Y=63 there are no "AIR" regions, and above Y=128 there are no "SOLID" regions. In fact,
above Y=128 **almost all regions are AIR**, and we can generally assume Jagged Peaks to be rare enough to
ignore their existence for pathfinding in unexplored terrain.

Further, below Y=63 even though regions may be MIXED (not SOLID) we can be almost certain that lateral
movement is more expensive than it is on land because we need to wiggle through caves (or occasionally
mine through a wall).

This produces 3 distinct tiers of costs:

Y > 128:
UP -> double cost
DOWN -> quarter cost
LATERAL -> double cost

63 < Y < 128:
UP -> double cost
DOWN -> half cost
LATERAL -> base cost

Y < 63:
UP -> double cost
DOWN -> base cost
LATERAL -> double cost

**Option C. Partial World Generation**

Minecraft chunk generation is done in multiple phases. ONE of these phases is computing the height map of
the terrain. Running JUST THIS ONE PHASE (ignoring cave generation, decorations, and other features) we
may be able to tighten the "Y=63 to Y=128" band more accurately, saying "this particular chunk generates
land at Y=87" or something.

This would produce very similar costs to Option B, except instead of choosing one of the three "unbuilt
region cost tiers" based on hard-coded 63 and 128 constants, we choose is based on the actual terrain -
giving us greater accuracy for the real world.

---

One of the three options above can be used to give non-free / reasonable costs to traversing unbuilt
regions, which should drastically reduce flooding since we now pay a non-zero movement cost, hopefully
comparable to what we pay to enter the actual region towards the goal.

A quick note on forward-vs-reverse:

The `unbuilt = free` fix does not dictate whether this should only apply to the forward A* pass or if
these costs should be applied to the reverse Dijkstra cost. This was left out because, in theory, the
reverse Dijkstra should be unable to encounter an unbuilt region. Reverse Dijkstra is only run to get
the cost for the block tier A*, the block tier A* is only used to compute paths local to the bot, and
the bot loads chunks. This means that there should never be unbuilt regions in the block tier A*.

That said, it is my believe that should we ever encounter such a sitaution, we SHOULD apply one of
option A/B/C to the reverse pass (increasing the predicted cost). This is known to be inadmissible, as
we may OVER-estimate the cost to the goal and end up choosing a sub-optimal path. However:

 1. We are already inadmissisble due to using greedy A*
 2. The only paths this would affect are paths that MUST travel through an unbuilt region, and per the
    "bot paths should make sense" goal, it doesn't make sense to choose to path through the unexplored
    world when you can instead path through known terrain
 3. Lower heuristic values from the region tier increase the risk of flooding at the block tier, which
    can lead to partial paths, which are potentially deadly for navigation

In short: a slightly sub-optimal path that always reaches the goal is better than a POTENTIALLY better
path that might fail to reach the goal.

### 3. No region-level flood guards

There's really two solutions that I think need to be implemented here, to address two different levels in
which flooding can occur.

**Top-level Flooding:**

If at any point we attempt to expand a node that is more than `maxChebAtLevel` regions away from our start
position, this means that our "cap-safe ≤ 8192 by area" invariant is **VIOLATED**

When this happens, we should NOT continue expanding - but should restart our search with a higher starting
level.

Even with the mine and pillar prices consistent and with unbuilt no longer being free, this kind of
flooding is still possible in BUILT regions for certain (very rare) contrived maps. Consider a map with a
200-block tall obsidian cuboid that spans the area from X=-1000 to X=+1000 and Z=-1000 to Z=+1000. Then
image a bot placed at (0,201,0) (above the obsidian) trying to pathfind to (0,-1,0) (below the obsidian).

Such a bot is only 13 regions away vertically -- an L0 or L1 task based on chebyshev distance.

However our pathfinder will rapidly see that traversing laterally to X=+1000 and THEN descending is much
cheaper than trying to mine straight down through an obsidian blockade.

This search will expand a 62² = 3,844 L1 region square on the surface before reaching the edges of the
obsidian blockade. When you factor in the VERTICAL movements it will consider, as well (pillaring near
the start, creating a cone-shaped flood) we'll likely blow our the 20k node cap without ever reaching
the goal.

It's for this reason that we need to stop the moment we realize we've started to flood. The moment we try
to expand >11 regions out from the starting location we should stop the search and restart it from a
coarser representation of the world.

NOTE (widen the lens, don't blacklist the hop): this escalation is fundamentally different from the existing
`onBlocked` escalation. `onBlocked` fires because a specific crossing was proven *unrealizable* — so it
blacklists that hop and re-plans around it. A flood escalation fires because the *search area* got too big, not
because any hop is bad — so it must **restart at a coarser level and blacklist NOTHING**. The trigger cell is a
perfectly valid region we simply reached while flooding; forbidding it would be wrong. Keep the two paths
separate: a flood "widens the lens," it does not condemn a hop.

The remaining question (which I will allow Claude to answer) is how high up we should go. Do we go up one
level at a time and risk flooding again, or do we jump two or three levels at a time, or do we jump striaght
to level 6?

**Lower-level Flooding:**

Even if we have routed a perfect skeleton at the L1 or L2 level, there's currently no guarantee that the
L0 pathfinding will stay within this skeleton. Though it's very unlikely, at the L0 level we may expand
into irrelevant regions far from our skeleton window.

To prevent this, we should create an envelope around our region skeleton and ensure the L0 path never
strays from the envelope. Attempting to expand a node outside of this envelope should be forbidden.

We used to have a rule like this for our block-level A*, but it was subsumed by our region-refined
heuristic (which makes it impractically expensive to expand outside of our skeleton). We have no such
"region-refined" heuristic at the region-tier A*, so we regress to the envelope.

NOTE: adding a 1-2 region margin around the skeleton to assist with finding a cheaper path is acceptable,
but it's important to realize that there is NO situation where excluding regions outside the skeleton
will lead to a bare FAIL. This is because the Minecraft world is **fully 6-connected**. You can always
build or mine to traverse between two adjacent regions. The only difference is cost. The only way we can
get a "FAIL" from the region-tier A* is if we reach the 20k node cap - which can only happen if we're
flooding. By preventing a flood, we prevent bare FAIL.


## Reproduction & tooling built this session (all uncommitted, mc121 worktree, owner holding)

- Frozen-world harness: `run-autotest.ps1 -MasterWorld` (copy-from-pristine-master, edit-safe), `-Barehanded`,
  `-ProbeOnly` (start-cell worldgen dump), `-Rtrace` (full-cascade region trace). Harness seams in
  `HeadlessAutotest.java` + `fabric/build.gradle.kts`.
- `/bot rtrace` now runs the **full cascade** (was a direct-L0 search), tool-aware, level-tagged
  `E <seq> L<level>` (`AllyBotEntity.regionTraceTo`).
- `internal_docs/region_trace_analysis.py` (region-trace analyzer, mirrors the block-tier `trace_analysis.py`).
- `internal_docs/DIAGNOSIS-worldgen-nondeterminism.md` (the separate worldgen-non-determinism finding + the
  frozen-world rationale).

## Verification

**Sequencing — benchmark the unbuilt cost, then land all three fixes as one unit.**

1. **First, benchmark unbuilt-cost Options A/B/C** (§2) in isolation and pick the option by *measured* per-region
   cost, not by intuition. The three differ only in how much work they do per unbuilt-region query (A: a
   direction switch; B: a direction switch + a Y-band compare; C: a Y-band derived from a one-phase heightmap
   generation). The cost of C in particular is unknown and could be prohibitive on the search hot path — measure
   before committing. The chosen option is an input to the combined change below.

2. **Implement #1, #2 (with the selected option), and #3 as a SINGLE unit — do not gate them on per-fix JMH.**
   The three interact and build on one another, so a per-fix regression reading is misleading: e.g. #2 *raises*
   the cost to evaluate a region while #1/#3 *reduce the total number of regions evaluated* (no more flood).
   Judging #2 alone would show a regression that the whole set erases. Benchmark the **combined** change against
   the pre-change baseline, as one A/B.

3. **Expect and accept a SLIGHT region-tier JMH regression (~5–10%)** after all three land. We are adding real
   per-node/per-search logic — cap-safe violation detection, unbuilt cost computation, forward mine+place cost
   (wooden-pickaxe model), and skeleton-envelope bounds checks. A modest region-tier slowdown in exchange for
   correctness (no flood, no garbage partial) is the intended trade. (Block-tier benches should be unaffected.)

**Hard gates — these MUST hold, independent of the perf numbers:**

- **The bare-handed bot + persisted HPA must NOT pillar** in the Autotest Master. Concretely, the deterministic
  repro (`run-autotest.ps1 -MasterWorld <w> -Start 60,180,253 -Goal 201,-28,90 -Barehanded`) must **descend
  toward the goal** — Y must not climb to the ceiling. Cross-check with `-Rtrace`: the top-level search must
  reach the goal region (or a coarser level that does), NOT return a high-altitude flood partial.
- **Every previously-passing unit test must continue to pass** — the ~30 headless region/field/cascade tests
  and the full `:1.21.11:test` suite, unchanged.

**New test — prove #3's value on the obsidian case.** Add a headless test for the contrived built-obstacle
flood (§3): a bot above a wide, thick unmineable (or bare-handed-prohibitive) slab, goal directly below it, so
the honest-cost search *still* wants to flood laterally around the slab even after #1+#2. Run it **before and
after** #3 and compare **behavior** (before: floods to the node cap / returns a wandering partial; after:
detects the cap-safe violation, escalates, and returns a sane route or a clean give-up) **and performance**
(expansions, wall-time). This test is also the vehicle to settle the **escalation-depth tunable** (iterative +1
vs a bigger jump — see the Top-level Flooding note): measure both on the obsidian case and keep whichever is
faster on the worst realistic obstacle; it's rare enough that either is acceptable, so decide empirically
rather than by argument.
