# Orebit — Movement Vocabulary Design

> Working design for the block-tier movement layer (PRD §7.2 elaborated). Status: **Tier 1 built &
> runtime-verified (session 15); `/bot` commands built (session 16); break/place is next.** Updated
> session 16 (2026-06-23). **§1's "three decisions" are the canonical rules** for classifying any new
> capability (movement kind vs. modifier vs. separate system) — apply them before adding anything.
>
> *(Status update 2026-07-03: long since overtaken by events — break/place folding, the swim family,
> Climb, Parkour + DiagonalParkour (TIER1 = 14), timed mining (`BotMining`), phase-model execution
> (`MovePlan`/`PhaseRunner`), and the pass-through hazard / break-through cost model are all BUILT.
> The §1 classification rules remain canonical.)*

## 1. Framing decision — movement-centric, not block-centric

The earlier sketch was `ClimbableNavBlock`: "given a NavBlock, ask which movements it permits."
**Superseded.** A movement spans **multiple cells** — a parkour gap-jump reads the takeoff cell, the
air over the gap, the landing cell, *and* the head-clearance arc between them — so a single block
cannot answer "is this move valid." The rule belongs to the **movement**, not the block.

Clean separation of concerns:

- **`NavBlock` descriptor = the per-cell FACT table** (geometry + material effects). Read on demand
  via `NavGridView.descriptorAt(x,y,z)` (the [resolution-layers](../src/main/java/com/orebit/mod/worldmodel/pathing/NavGridView.java)
  seam — fine geometry, zero stored, always fresh).
- **`Movement` = a rule** that reads facts across the cells it touches and yields valid destination
  cells with a tick cost. Registered in a list the block A\* iterates per expanded node (Strategy
  pattern → new movement = new class, no edits to existing ones; matches Baritone's proven model).
- **`BotCaps` = a capability gate** (what THIS bot can do: break / place / jump height / swim / …).
  Movements filter candidates on it; costs parameterise on it (PRD §7.3, "bot ≠ player").

Contract sketch (refine when building):

```java
interface Movement {
    // ctx carries the NavGridView (classAt coarse + descriptorAt fine) and the BotCaps.
    // Emit every valid destination reachable by THIS movement from (x,y,z), with its tick cost
    // and any folded interactions (break/place/door-toggle).
    void candidates(MovementContext ctx, int x, int y, int z, CandidateSink out);
}
```

### The two-resolution interplay (why this is clean)

- `classAt` (cached 2-bit) → **cheap pruning**: skip BLOCKED cells early, get the broad route.
- `descriptorAt` (live geometry) → **precise validation**: each movement does the exact check
  (e.g. Ascend verifies real head clearance). The 2-bit grid is only a hint, so classifier
  approximations (the "2-high dirt wall reads as a step" bug, commit `7beda91`) are caught **precisely
  at the movement level** rather than approximated in the grid. The grid finds candidates; geometry
  decides moves.

### The three decisions (kind vs. modifier vs. separate system) — CANONICAL

When any new capability comes up (break a block, place a block, climb, swim, elytra, …), classify it
with three **independent** tests. Most apparent "should these merge?" questions dissolve once you see
they're asking different tests.

**Decision 1 — is it a block EDIT (break / place)? → it's a MODIFIER, never its own movement.**
Every movement computes, from the cells its own geometry touches, two sets: `mustBeAir` (body path +
clearance) and `mustBeSolid` (footing / support). For each `mustBeAir` cell that's solid → if
breakable & `canBreak`, add to the move's break-set and `cost += miningTicks`, else the move is
invalid. For each `mustBeSolid` cell that's air → if placeable & `canPlace`, add to the place-set and
`cost += placeCost`, else invalid. So **"BreakThrough" and "Bridge" are NOT movements** — they're
Traverse/Ascend/Fall with a non-empty edit-set. The move is still ONE A\* edge (A→B); the edits just
raise its cost, so A\* prefers going around unless digging/bridging is genuinely cheaper (that
emergent trade-off is the whole payoff). A single move may carry **more than one** edit (a 2-high wall
in front = break feet + head, two breaks in one Traverse) but only ever clears the cells **its own
geometry** needs — tunnelling through thickness is a **chain** of such moves, not one move mining far.
This is exactly Baritone's `positionsToBreak[]` + `positionToPlace` on each Movement. (Why folded, not
a separate path node: a placement doesn't change position, so a "place" node would be a zero-progress
self-loop that breaks position-keyed A\*; attributing the place to the move that *uses* the placed
block keeps the search clean.)

**Decision 2 — is it a distinct movement KIND? → split when the follower runs DIFFERENT
execution/validity/cost code, NOT based on the destination offset.** Sharing a destination is **free**:
the registry lets multiple movements emit the *same* neighbour cell, and A\* keeps the cheapest. So
sharing an offset is never a reason to merge. Examples:
- **Climb vs. Pillar** — both target the cell directly above, but Climb is cling-and-glide on an
  existing ladder/vine while Pillar is jump-and-self-place; different execution, validity, physics →
  **separate classes**, each emits `(0,+1)` when its preconditions hold. (Baritone merges these into
  `MovementPillar` via an internal `if climbable / else place` branch — precisely the
  conditional-in-a-class our Strategy registry avoids; splitting is where we improve on its structure.)
- **Crawl vs. Swim** — both use the prone 1-block hitbox, but that's a shared *precondition*, not a
  shared motion (Crawl is gravity-bound horizontal on a floor; Swim is buoyant 3D) → **separate**.
- **Merge only** when two candidates share execution and differ by at most a cost number (Traverse on
  stone vs. dirt = same walk, different mining-adjacent cost = same kind, cost tag).

**Decision 3 — does it need a separate PLANNING SYSTEM? → only when the motion is NOT
discrete-and-stoppable** (continuous-trajectory / momentum-bound). The test is **not "is it 3D."**
- **Swim is 3D but stays in the one A\***: you move to an adjacent water cell at ~constant speed and
  can hover/stop anywhere → discrete + stoppable. It's just A\* with a 3D neighbour set inside water
  volumes. In-framework; deferred only by priority.
- **Elytra needs a separate system**: a continuous parabola you can't stop, speed coupled to pitch,
  one decision spanning a long arc → the A\* state would need velocity + altitude, not just position.
- **Minecart** likewise rides a rail graph (its own search space). Of the whole vocabulary, **only
  elytra and vehicles** cross this line.

## 2. The movement vocabulary (tiered by when to build)

Classify every entry below with the three decisions above: most are **kinds** (decision 2); break/place
are **modifiers** woven into the kinds (decision 1); only Tier 4 is a separate **system** (decision 3).

### Tier 1 — ground (BUILD NEXT SESSION)
- **Traverse** — walk to a passable neighbour. Includes **step-assist**: a player auto-steps ~0.6
  blocks, so a target whose `topY ≤ ~10/16` (slab / stair / snow layer / partial) is walked onto
  **without a jump**. This is the visible "uses stairs naturally" behaviour — and it falls straight
  out of the existing `topY` field.
- **Ascend** — jump up 1. **Requires real head clearance**: read the cell above the bot's head via
  `descriptorAt` and confirm it's passable. This is the proper fix for the whole "head-in-block"
  class — validated by geometry, not the coarse grid.
- **Descend** — step down 1.
- **Diagonal** — diagonal walk with corner-clearance check (both shared orthogonal cells passable).
- **Fall** — drop > 1. Safe if `dist ≤ safeFall(caps)` OR the landing cell is soft (see `softLanding`)
  OR the bot is immortal (caps). Start with the safe-distance-only form; grow with `softLanding`.

### Tier 2 — climb / gap / water
- **ClimbUp / ClimbDown** — ladder / scaffolding / vine. Uses the existing `climbable` bit; vertical
  moves at ladder cost. (Scaffolding nuance: climb up, sneak-descend, walk-through bottom — handle in
  the movement.) **The user's explicit add: ascend AND descend a climbable, both directions.**
- **Parkour** — horizontal gap-jump 1–4 blocks. Reads runway + the air over the gap + the landing +
  the arc clearance. Geometry decides whether the jump clears (the canonical "2-bit says the gap is
  passable; geometry says whether we make it" case).
- **Swim / SwimUp / SwimDown** — in water (`fluid == water`). 3D but **in-framework** (decision 3):
  discrete & stoppable, so it's the SAME cell-to-cell A\* with a 3D (6/26-connected) neighbour set
  inside water volumes, plus ground↔water transition edges; breath budget if `caps.drowns`. NOT a
  separate system — deferred only by priority (3D expansion is heavier; the region tier absorbs the
  search-volume growth).
- **Crawl** — horizontal move through a 1-tall gap (under a trapdoor / via sneak). Separate kind from
  Swim despite the shared prone 1-block hitbox — that's a shared *precondition*, not motion (decision 2).

### Tier 3 — special & interaction

**Break / place are MODIFIERS, not entries here** (decision 1): "BreakThrough" = Traverse/Ascend with a
non-empty break-set; "Bridge" = Traverse/Fall with a place-set (a missing floor it places). These are
the *same kinds* as Tier 1 with edits folded in — **no new classes**, just the break/place plumbing.
The genuinely new KINDS in this tier are the ones whose MOTION differs:
- **Pillar** — jump straight up in the *same column*, placing a block beneath at the apex
  (`caps.canPlace` + block budget). A distinct kind because the motion is vertical-in-place, not
  Ascend's diagonal-up step. It always carries a place, but that's intrinsic to the motion, not the
  reason it's separate (decision 2).
- **MineDown** — dig the block beneath and drop one (vertical-down-in-place; intrinsically a break).
  Pillar in reverse; distinct motion.
- **EnterPortal** — step into a portal block (needs the `portal` fact). The block tier only *enters*;
  cross-dimension routing is the region/HPA\* tier (PRD §6.5, "portals as local edges"). **User add.**
- **SlimeBounce** — use a `bouncy` block for ascent (advanced), or AVOID an unwanted bounce.
- **PowderSnow** — sink / slow / climb-out (`sinkable`); freeze hazard unless `caps` has leather boots.

### Tier 4 — separate planning SYSTEMS (decision 3: NOT discrete cell-to-cell)
The ONLY capabilities that leave the one cell-to-cell A\*; everything above stays in it.
- **Elytra flight** — continuous-trajectory glide (a parabola you can't stop, speed coupled to pitch,
  one decision spanning a long arc) → the A\* state would need velocity + altitude. A separate planner,
  not a `Movement`. Needs launch height + fireworks. Revisit much later.
- **Vehicles** — Minecart rides a `rail` graph (its own search space); Boat = a water highway;
  Horse/mount changes the graph. **Defer**; note the `rail` fact only if pursued.

## 3. NavBlock fact additions

**Principle: add a bit only when the movement that consumes it is being built** (no speculative
fields — keep NavBlock honest). Current layout uses 37 of 64 bits; **bits 37–63 are free (27 spare)**,
so there is ample room. Proposed, each tied to its consumer:

| Proposed fact | Width | Blocks | Consumed by | Tier |
|---|---|---|---|---|
| `softLanding` | 1 bit | water, hay, slime, bed, cobweb, honey, powder snow, vines, sweet-berry | Fall (bigger safe drops) | 1 |
| `bouncy` | 1 bit | slime, bed | SlimeBounce; Fall (avoid bounce) | 3 |
| `portal` | 2 bits | 0 none / 1 nether / 2 end-portal / 3 end-gateway | EnterPortal | 3 |
| `sinkable` | 1 bit | powder snow | PowderSnow | 3 (maybe) |
| `rail` | 1 bit | rails | Minecart | 4 (defer) |

**User's two questions answered:** yes to a `bouncy` bit (slime/bed trajectory) and yes to a
reduced-fall-damage bit (`softLanding`) — both real facts the planner needs, added when Fall /
SlimeBounce are built.

**Facts already present that movements reuse** (no new bits needed): `shape`/`topY` (step-assist,
stair, slab half-steps), ~~`faces`~~ (REMOVED — the sturdy-faces mask cost half the navtype table and
nothing read it; place-against is the `PLACEABLE_NEIGHBOR` NavFlags bit now), `climbable` (ladder/
vine/scaffold), `fluid` (swim/lava), `surface` slow/slippery (cost + ice handling), `gravity`
(cascade risk), `damaging` (hazard cost), `replaceable`+`faces` (bridge/pillar), `hardness`/`tool`/
`toolRequired` (break cost), `openable` (door/gate/trapdoor toggle folded into Traverse),
`waterloggable` (bucket-clutch fails).

## 4. Material effects — fact vs execution vs cost

Not every material interaction needs a NavBlock bit. Three buckets:

- **Fact (needs a bit)** — `softLanding`, `bouncy`, `portal`, `sinkable`. The *planner* needs them to
  choose or validate a move.
- **Execution dynamics (follower, no bit)** — **ice / slippery slide**: `surface == slippery` already
  flags it; the *follower* must steer with momentum (it may overshoot waypoints on ice — a follower
  refinement, not a planner fact). Slime bounce trajectory, if not modelled as a movement, is the same.
- **Cost-only (existing bits)** — slow surfaces (soul sand, honey, cobweb), water/lava penalties,
  slippery risk: handled by adjusting move cost from `surface`/`fluid`, no new structure.

## 5. BotCaps — the capability gate (PRD §7.3)

A small immutable record seeded from server/bot config; movements filter on it, costs parameterise on
it, and **consumables are tracked along the path** (not snapshotted once — PRD §7.3):

- `canBreak`, `canPlace` (+ throwaway-block budget)
- `jumpHeight` (default 1)
- `mortal` / health (damage as high cost vs ignored), `drowns` (breath budget), `toolDurability`
  tracking
- (Tier 4) `hasElytra`, `hasBoat`/mount

Start narrow: **walk + jump 1, no break/place, immortal, no drowning** — i.e. only Tier 1 movements
enabled — then widen as movements land.

## 6. Open questions / deferred decisions

- **Elytra**: separate planner, or skip entirely? (Lean: out of scope for the cell A\*.)
- **Vehicles**: worth the graph complexity for v1? (Defer.)
- **Fall damage**: single `softLanding` bit vs graded reduction? (Start single; grade later if costs
  need it — hay 80% vs water 100% etc.)
- **Ice overshoot**: follower momentum model — build when it visibly bites.
- **Powder snow**: freeze-hazard interplay with `caps` (leather boots) — defer with the movement.

## 7. Status & build order

> **Status update (2026-07).** Everything below through step 5 is BUILT, plus most of the "THEN" list.
> `MovementRegistry.TIER1` is now 13 movements: Traverse, Diagonal, Ascend, Descend, Fall, Pillar,
> MineDown, **Climb** (ladder/vine/scaffold, up+down), **Parkour** (1–4-gap, edit-free by rule), Swim,
> SprintSwim, StartSprintSwim, Surface (the stateful pose transitions — mode is in the node key, the §1
> "movement-state" answer). Break/place are folded edits carried by `StepEdits`/`PathEdits` exactly as
> step 4 planned; mining is timed vanilla (`BotMining`); portal entry is follower-level
> (`NetherPortalIndex` + portal-seek/ENTER), not an `EnterPortal` movement yet. Execution grew a layer
> this doc predates: multi-phase moves (Pillar, Parkour) declare a guard-based `MovePlan` consumed by
> `PhaseRunner`; the rest steer via the per-movement `steer` hook. Still open from below: Crawl,
> SlimeBounce, PowderSnow, `softLanding`/`bouncy` facts, Tier 4. The sections below are kept as the
> design rationale + the original build order.

**DONE + runtime-verified (session 15).** Tier 1: `Movement` / `MovementContext` / `CandidateSink` /
`BotCaps` / `MovementRegistry` + **Traverse** (step-assist) / **Ascend** (real head-clearance via
`descriptorAt`) / **Descend** / **Fall** (safe-distance). `BlockPathfinder` expands via the registry;
`BlockPathPlan` carries the chosen `Movement` per step; the follower jumps only on Ascend. `BotCaps`
already has `canBreak` / `canPlace` (default false). `/bot come|stay|follow|here` commands wired
(session 16). `classAt` = the cheap built/loaded prefilter; `descriptorAt` decides every move.

**NEXT — break / place (folded edits, decision 1) — the priority.** Motivating case: the bot couldn't
`come` through a forest because leaves (collide → not passable) walled every path; breaking one leaf
opened a route. Steps:
1. `BotCaps`: enable `canBreak` / `canPlace`; add a throwaway-block budget (consumables tracked along
   the path, PRD §7.3).
2. `MovementContext.breakable(cell, caps)` / `placeableAgainst(cell, caps)` predicates — reuse
   `hardness` / `tool` / `toolRequired` and `faces` / `replaceable`; **no new NavBlock facts**.
3. Teach the EXISTING Traverse/Ascend/Descend/Diagonal: a blocked `mustBeAir` cell → emit the move with
   that cell in a break-set + mining cost (if `canBreak`); a missing `mustBeSolid` floor → place-set +
   place cost (if `canPlace`). A move carries up to its own geometry's worth of edits (Traverse ≤ 2
   breaks); depth through a wall = a chain of moves.
4. **Plumb the edit-set through the plan (the real new work):** a stateless `Movement` singleton can't
   name the cells, so grow `CandidateSink` / `BlockPathPlan` to record per-edge break/place cells (the
   `BlockPathOperation` the `pathfinding/blockpathfinder` stubs anticipate). The follower mines/places
   them server-side (break likely needs a `platform/` shim for `destroyBlock`/drops drift) before
   completing the step, and re-validates at execution time.
5. Add **Pillar** + **MineDown** as new KINDS (distinct vertical-in-place motion). Cost from
   `hardness`/`tool` (Baritone-seeded mining ticks) + place cost; heuristic stays admissible (edits
   only raise edge cost). **Start narrow:** Traverse break-modifier on one soft block — that alone
   solves the forest-leaves case — then Bridge (place-floor) / Pillar.

**THEN — more KINDS (parallel, all in-framework, pure common Java):** Diagonal (Tier 1 leftover) →
ClimbUp/Down (both directions) → Parkour → Swim (3D-connected A\*). Each = a new `movements/<X>` class +
a `MovementRegistry` line; sharing a destination offset with an existing movement is free.

## 8. Nav-grid cell encoding — navtype + neighbour-property bits (RATIFIED, session 17)

The per-cell nav-grid entry (`TraversalGrid`) is being re-encoded from the legacy 4-value
`TraversalClass` (CLEAR/EASY/SLOW/BLOCKED) into **a navtype index plus a bitmask of precomputed
neighbour-derived properties**. The 4-class value is **dead** (grep: nothing compares it — `classAt` is
used only as `!= null`, a "section loaded" gate), so the bits are free to repurpose with zero consumer
migration.

**Why drop the 4-class.** It was meant as a low-fidelity fast pass for pruning — but (a) the HPA\*/region
tier is the real coarse/long-range pass, and (b) the packed nav grid (navtype → descriptor `long`, one
in-memory array) already navigates at section level with no measured cost. What the 4-class can't tell
us — and what a single navtype can't either — is **neighbour context**: headroom, fluid-flow risk,
gravity risk. Those are the multi-cell facts the movement layer would otherwise re-derive on every node
expansion. Precompute them once (at build / on block-update) and the movement layer reads ONE grid entry:
**low bits → the cell's own geometry** (navtype → descriptor), **high bits → its neighbour context**.

**Layout — keep `short[4096]` per section; bit-squeeze, do NOT widen.** Navtype index in the low bits
(~10 bits = 1024 navtypes; measured ~530, so ~2× headroom), neighbour facts in the high 6. **A small
footprint is itself a SPEED optimization** — cache residency + load time — so doubling the cell to `int`
would hurt the very thing the compact grid buys (this is *why* we keep memory small; it was never a
space goal). If the navtype count ever outgrows its bit budget, **compact the descriptor to collapse more
states into fewer navtypes** before even considering a wider cell: e.g. store `log2(hardness)` instead of
the full 8-bit hardness, merge `WATER`+`LAVA` into one `FLUID` bit (differentiate via `isDamaging`), or
drop `TOOL_REQUIRED` (assume tools are required). Compaction first; widening is a last resort needing
explicit sign-off.

**The facts** (each added only when its consumer movement is built — same discipline as NavBlock's spare
bits; `isDamaging` of the *floor* stays intrinsic in the navtype, no bit):

| Bit(s) | Fact (code name) | Meaning / what it gates | Consumer |
|---|---|---|---|
| 1 | `RISKY_EDIT` | don't BREAK/PLACE here — editing could release a neighbouring fluid into the body space **or** drop an overhead sand/gravel block onto the bot (walking through is fine). A BREAK/PLACE gate, **not** a movement check | fluid/gravity-aware break-modifier |
| 1 | `CLEARABLE_HAZARD` | a walkable-through hazard in the body space (fire) — adds cost, not blocked | hazard-cost movement |
| 2 | `HEADROOM` | 0 none / 1 crawl / 2 walk / 3 jump — vertical clearance above the floor | Crawl / Traverse / Ascend |
| 1 | `PLACEABLE_NEIGHBOR` | a solid face to bridge a placed block against | Traverse bridge / Pillar |
| 1 | *(reserved)* | bit 5 — free for a future neighbour-derived fact | — |

> **Implemented (session 19).** Work items 1 & 2 below are done: `TraversalGrid` is now
> `[6-bit flags | 10-bit navtype]`, computed by `NavFlags` (replacing the dead `NavClassifier`/
> `TraversalClass`) at build/air-bypass and recomputed by `NavSectionBuilder.patchCell` on every block
> change. **Design refinement:** the design's separate `risksFluidFlow` + `risksGravityFall` were
> **merged into one `RISKY_EDIT` bit** — both mean "don't edit here," and the precise flood/cascade check
> lives in the fine layer (`descriptorAt`) at break time (work item 3 / deferred-#3), so one prefilter bit
> suffices and frees bit 5. Items 3 (conservative fluid OOB) & 4 (movements read the bits) stay deferred —
> their consumers (the fluid-aware break modifier; the movement-layer migration) aren't built yet, so the
> bits are stored air-optimistically at boundaries and the movements still read `descriptorAt`.

**Semantics shift:** fluid/gravity risk no longer means "can't go here" (old 4-class BLOCKED) — it means
"**don't BREAK or PLACE here**," because only an *edit* triggers the block update that releases the
flow/cascade. So they're break-modifier gates read in one bit instead of an 8-cell neighbour scan.

**Boundary handling — always within-section, NO overscan.** Each section's neighbour bits are computed
from its own cells plus a per-fact out-of-section default, so a section's bits **never depend on another
section** — a block update recomputes bits within one section only, never reaching into a neighbouring nav
grid (which would mean extra data fetching + cache thrash on *every* update). The bitmask is therefore
**exact in a section's interior and only ever suspect within one cell of a section face.** Two cases:

- **Edit-hazard gates** (`risksFluidFlow`, `risksGravityFall`) — conservative OOB default (assume the
  hazard *could* be there), so the bot won't break/place at a boundary it can't see past. `risksFluidFlow`
  reads horizontal neighbours → treat horizontally-out-of-bounds cells as **alternating air/water by
  y-parity** (the check runs for both body cells y+1/y+2, one odd / one even, and ORs, so a boundary cell
  always trips → flagged). Harmless, since these gate only break/place, never walking. Worst case: the bot
  won't edit *exactly on* a chunk/section face; a rare *valuable* boundary edit gets a **lazy one-time**
  real-neighbour lookup in A\* — never eager overscan.

- **Movement / capability facts** (`headroomHeight`, `hasPlaceableNeighbor`) — keep the **air-optimistic**
  default (a section top / chunk edge is usually open). The hazard here is the *opposite* of the gates: the
  bit can **over-claim** — e.g. treating a vertically-out-of-bounds cell as air says "has headroom" when
  there's actually **unbreakable bedrock one block up in the next section**, so the move looks walkable
  when it isn't. **Do NOT fix this with a pessimistic default** (assuming bedrock would deny walking on
  perfectly clear blocks at every section top). Instead **use the bit as a PREFILTER:**
  - bit says **no** headroom → reject the move immediately (the common, cheap case);
  - bit says **yes** headroom **and the cell is within one block of a section face** → **lazily verify**
    the real body cell(s) via `descriptorAt` (cross-section-correct) before committing the move.

  This verify fires **more often** than the edit-hazard one — it's plain walking near a chunk/section
  border, not a rare valuable edit — but it's a cheap, bounded read and only at faces; everywhere else the
  bit is trusted with no read. (This is *why* `headroomHeight` is a movement-layer prefilter, not a hard
  truth: the move's geometry is still confirmed by `descriptorAt` whenever the bit's accuracy is in doubt.)

### Work items (ratified — build per consumer, not speculatively)
1. **[DONE §19] Replace the 4-value `TraversalClass`** with the navtype + neighbour-property bitmask
   (**`short[4096]`, bit-squeezed**: 10-bit navtype + 6-bit flags); `built()` becomes section-presence,
   not `classAt != null`.
2. **[DONE §19] Intercept `setBlockState`** → update the changed cell's navtype AND recompute the
   neighbour-property bits for the affected (within-section) neighbourhood — the block-update hook (the
   mixin was wired §18; `patchCell` now recomputes flags, not the vestigial class); retires the per-replan
   `refreshNavData`.
3. **[deferred] Conservative out-of-section default** — alternating air/water for the fluid-flow
   horizontal-OOB read, always (no overscan). Lands with the fluid-aware break modifier that reads
   `RISKY_EDIT`; until then the bit is stored air-optimistically at boundaries (the precise check is the
   fine layer's anyway).
4. **[DONE §19 cont.] Rewrite the movement classes** to read the neighbour-property bits. Traverse /
   Ascend / Descend / Fall now check body clearance through the `HEADROOM` bit
   (`MovementContext.headroomProves(flags,y,need)` / `requireBodyClear`) — one grid read instead of
   per-cell `descriptorAt` probes — and `RISKY_EDIT` now **gates** break/place (`EditScratch.reset(allowEdits)`:
   a move won't fold an edit in a fluid/gravity-risky cell). The proof is exact via two facts: the OOB bias
   is one-directional (air-optimistic → a sub-`need` reading is a trustworthy reject) and the trust
   threshold is per level (`(y&15)+need<=15`, so WALK holds to `<=13`, only JUMP tightens to `<=12`). Still
   open: `PLACEABLE_NEIGHBOR` isn't consumed yet (the bridge/pillar place still scans neighbours), and the
   `RISKY_EDIT` *precise* flood/cascade check (item 3) + conservative fluid OOB are still deferred to the
   fluid-aware break modifier.

**Coverage gap — staircases DONE incl. open air (§19 cont.):** Ascend and Descend fold a place (like
Traverse's bridge). A lone footing one-up-and-over floats in open air (no face to attach to), so Ascend now
places a **support** beneath it against the floor the bot stands on (`EditScratch.requireFootingOn`, the
**two-block step**) — building a diagonal **staircase up** off a ledge / out of a pit / to a hovering owner,
not just up against terrain. Descend places a **step down** a sheer drop it can't safely Fall. With the
break folds (dig up into a hill / down through the transit) the bot reaches any cell by some combination of
the four Tier-1 kinds. Gated by `RISKY_EDIT` + `canPlace` (a non-placing bot is unchanged). **Why Pillar is
still wanted (not a reachability gap now, a trade-off):** the staircase is 2 placements/step and moves
up-AND-over; a straight **Pillar** is 1/block but one-way (a death-trap you can't descend). A* should weigh
them as a natural cost fall-out — so Pillar/MineDown stay queued as separate kinds, not blockers. **Also
open:** `PLACEABLE_NEIGHBOR` isn't consumed yet (the place still scans neighbours); and a **downward
staircase DIG** (mine a descending stair when sealed in / boxed) is failing — next investigation (logs in
hand). `BlockPathfinder.DEBUG` dumps the dead-end's per-movement candidates to localise these.

### The block-change hook — mixin, via the overlay pattern (approved, full-now)

There's no loader API for "any block changed," so we accept a **mixin** — but contained the same way as
every other version-fragile surface: behind a seam, with the version-specific code in `overlays/`.
- **Common seam:** a small interface/registry the mod calls *consistently* to register a block-change
  callback (e.g. `onBlockChanged(level, pos, oldState, newState)`), plus a common dispatcher the mixin
  invokes. The nav-grid patcher registers against it in `OrebitCommon.init`.
- **The mixin lives in `overlays/<era>/…/mixin/`** (baseline `overlays/1.17`): an `@Inject` into
  `LevelChunk.setBlockState` that forwards to the common dispatcher. Plus the loader mixin-config JSON
  (Fabric `fabric.mod.json` / Forge `mods.toml`) — this is the project's **first mixin**, so it adds the
  mixin-config scaffolding too.
- **Per-version discipline:** a mixin *compiles* regardless of whether its target signature is right (it
  resolves at load time), so the compile gate can't catch drift. **Walk the versions forward and check the
  Minecraft source for `setBlockState`'s signature at each step**, adding an overlay flavor only where it
  changed. Runtime-verify on the versions we can run.
- **Why full-now (not call-site-only):** the bot is designed to operate *alongside* a player who is
  constantly mining/building its terrain, so it must see *all* edits, not just its own — a strong
  foundation laid early avoids retrofitting later.
