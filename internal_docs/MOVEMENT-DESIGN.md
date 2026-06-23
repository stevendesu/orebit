# Orebit — Movement Vocabulary Design

> Working design for the block-tier movement layer (PRD §7.2 elaborated). Written end of
> session 14 (2026-06-23), to be built next session. Status: **design, not yet implemented.**

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

## 2. The movement vocabulary (tiered by when to build)

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
- **Swim / SwimUp / SwimDown** — in water (`fluid == water`); 3D movement; breath budget if
  `caps.drowns`.
- **Crawl** — 1-tall gap (under a trapdoor / via sneak).

### Tier 3 — special & interaction
- **EnterPortal** — step into a portal block (needs the `portal` fact). The block tier only *enters*;
  the cross-dimension routing is the region/HPA\* tier (PRD §6.5, "portals as local edges"). **User add.**
- **Pillar** — jump and place a block beneath to gain height (`caps.canPlace` + throwaway-block budget).
- **Bridge** — place a block across a gap (a Traverse/Parkour variant carrying a placement).
- **BreakThrough** — Traverse/Ascend that breaks a soft block in the way (`caps.canBreak`; cost =
  mining, per PRD §7.3). Folds breaking into the move rather than a separate op.
- **SlimeBounce** — use a `bouncy` block for ascent (advanced), or AVOID an unwanted bounce.
- **PowderSnow** — sink / slow / climb-out (`sinkable`); freeze hazard unless `caps` has leather boots.

### Tier 4 — uncertain / heavy (think now, don't commit)
- **Elytra flight** — a *continuous-trajectory 3D glide*, fundamentally unlike cell-to-cell A\*; needs
  launch height + fireworks. **Recommendation: out of scope for the block pathfinder** — if pursued,
  it's a separate planning mode, not a `Movement`. Revisit much later.
- **Vehicles** — Boat (water highway), Minecart (needs a `rail` fact + the rail network as its own
  graph), Horse/mount. Each substantially changes the movement graph. **Defer**; note the `rail` fact
  if ever pursued.

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
stair, slab half-steps), `faces` (place-against, parkour landing sturdiness), `climbable` (ladder/
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

## 7. Build order for next session (Tier 1)

1. `Movement` interface + `MovementContext` (wraps `NavGridView` + `BotCaps`) + `CandidateSink` +
   a static registry list.
2. Convert `BlockPathfinder` A\* to expand a node by iterating the registry (replacing the hardcoded
   4-cardinal `standableFloor`). Keep `classAt` CLEAR-as-prefilter; use `descriptorAt` for the precise
   per-movement checks.
3. Implement **Traverse** (with step-assist), **Ascend** (with real head-clearance via `descriptorAt`),
   **Descend**, **Fall** (safe-distance only). `Diagonal` if time.
4. `BlockPathPlan` starts carrying the chosen `Movement` per step (toward the operation list the
   `pathfinding/blockpathfinder` stubs spec), so the follower can ask the movement how to execute it.
5. Verify in-game (the existing `DEBUG_PATH` viz + STUCK dump still apply).
