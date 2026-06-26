# Agency Layer — the next arc (phase boundary: navigation → agency)

> Ratified with Steve at the end of the partial-path session (2026-06-26). The **navigation foundation is
> done** (below); this is the keystone arc that makes the bot *act* in the world and unlocks the genuinely
> useful in-game commands. **Pick up here.** Ordering and the (1) sub-sequence are Steve's call; honor them.

## Status going in (what's already done — don't redo it)

- **Block-tier A\*** (`pathfinding/blockpathfinder/`): allocation-free hot path, struct-of-arrays search
  state, custom open-addressed maps, binary-heap open set; the `Movement` set behind `MovementRegistry.TIER1`
  (Traverse/Diagonal/Ascend/Descend/Fall/Pillar/MineDown); weighted 3D-octile heuristic (**W=2, greedy —
  already inadmissible by design**) + 3-D straight-line tie-break + the `GoalForcedCost` premium.
- **Cuboid macro-ops** (`.../cuboid/`): directional maximal-cuboid collapse so a uniform run becomes one
  jump. This session added **A** (bulk section-local extraction scan, ~2.6×), **B** (macro only the primary
  axis), **D** (forward-only edit-shrink, −28% on the up-and-over). See `internal_docs/CUBOID-PERF-OPTIONS.md`
  and `docs/Optimizations/cuboid_macro_movements.md`.
- **HPA\* two-tier driver** (`pathfinding/PathPlan.java` + `worldmodel/hpa/` + `worldmodel/region/`):
  region skeleton → windowed block search with a corridor `RegionBound`. `/bot come` uses it.
- **Partial-path return is ON** (`BlockPathfinder.PARTIAL_PATH = true`): a budget-exhausted search that made
  real progress returns its closest-approach node so the bot walks + replans (Baritone best-so-far model);
  a genuinely walled-in (heap-exhausted) search still returns `null` so real failures stay visible. This is
  the structural net for heuristic terrain blind spots (the floating-log premium cap — diagnosed, reproduced;
  it can't be patched away per-terrain, so we net it). **Verified in-game**: the bot now ascends the
  pathological floating-oak-log pillar, ~2 hitches, node counts dropping (~600 → ~30) as it climbs out of the
  log's shadow.
- **`/bot trace`** now runs the real HPA* first-window block search (corridor + cuboids + premium active),
  not a raw cornerless one. Headless benches: `PathfinderBenchmark` TOWER / OPEN / UPOVER_OPEN / UPOVER_WALL.

**The key fact for this arc:** path costs are currently **arbitrary magic numbers** (`MovementContext`:
`PLACE_COST`, `BREAK_BASE_COST`, `BREAK_PER_HARDNESS`; each `Movement.COST`). Making them *real* (ticks) is
the end of this arc, and it retroactively improves the heuristic, macro-ops, and the premium.

## The arc, in order — each rung is shippable + testable on its own

### (1a) Capability config  ← START HERE
- **Seam already exists:** `BotCaps` (`pathfinding/blockpathfinder/BotCaps.java`) — `canPlace`/`canBreak`/
  `jumpHeight`/`safeFallDistance`, today hardcoded as `BREAK_PLACE` / `DEFAULT`. The pathfinder + the future
  dig-out escalation all read it.
- **Build the config subsystem** (the stubbed `config/` + `settings/` packages) → per-server / per-bot
  tunables → `BotCaps` (and a richer capability object as needed). Server-owner knobs Steve listed:
  - survival: bot has health/takes damage? hunger? breath?
  - placement: bot places blocks at all? consumes inventory to place? if not, what block does it conjure?
  - mining: bot mines at all? consumes tools? if not, max hardness it can mine (today: insta-mines anything
    `< 255`)?
- **Why first:** every downstream rung (move generation, tick costs, the dig-out escalation) reads
  capabilities. Config → caps is the foundation, and a no-mine / no-place bot must be a first-class config.

### (1b) Inventory
- Model what the bot carries (blocks, tools, items).
- **Gates two path-relevant things:** (i) placement feasibility — if config says "consumes inventory to
  place," pillaring 200 up needs 200 blocks, so block count becomes a **path constraint**; (ii) mining
  yields — drops flow to inventory (later feeds the resource arc).
- **⚠️ Design crux — pathfinding becomes STATEFUL** along a path (the "consumables-along-path" problem;
  see the `pathfinding-design` memory). `PathEdits` already tracks per-path placed/broken cells; a finite
  block/tool budget is the resource analog. Baritone tracks a throwaway-block count. Decide how the search
  accounts for a finite, depleting block/tool budget without wrecking the alloc-free hot path. This is the
  hardest design decision in the arc — settle it before building.

### (1c) Tool use (+ block placement from inventory)
- Tools → mining speed, and whether a block is mineable at all (gates `BotCaps.canBreak` by hardness/tool).
- Placement-from-inventory: consume a block item to place (the concrete mechanism (1b) reasons about).
- Prerequisite for real break-cost ticks.

### (1d) Tick costs (Baritone-style — the payoff)
- Replace arbitrary cost units with **real ticks**:
  - move costs → ticks to perform the move.
  - **break cost** → real mining time = f(block hardness, tool, enchants) — needs (1c).
  - **place cost** → tick-to-place **+** the inventory cost of the consumed block (Steve: "premium for cost
    of placed block").
- **Touch points:** `MovementContext` cost constants, each `Movement.COST`, `GoalForcedCost` (its premium is
  derived from `Pillar.COST + PLACE_COST` and `MineDown.COST + breakCost` — re-derive from ticks),
  `MovementRegistry`. `NavBlock.hardness(desc)` already exposes per-state hardness for the break term.
- **Keep the hot path cheap:** precompute tick costs per (block-state × tool), not per A\* node.
- **Payoff:** a physically-meaningful heuristic; the premium and macro-ops inherit real costs for free.

## Dependency chain
`config → BotCaps → (inventory + tools) → tick costs → physically-derived path costs`

## Triage early (adjacent, NOT part of arc (1), but flagged)
- **HPA\* far-goal / unloaded-chunk "path=NONE flood"** (Steve teleported 0,0,0 → 1000,0,1000; logs spammed
  NONE). You fundamentally can't route through unloaded terrain, so this is likely a **missing exploration
  mode** (head toward the goal through loaded terrain, re-plan as chunks stream in) + graceful degradation,
  not a one-line fix. **Underpins the partial-path safety story** (HPA*'s global vision is what keeps partials
  from the Baritone cave-trap; if it's blind past the loaded radius, far-goal partials are greedy). Cheap to
  diagnose — do it like the pillar diagnosis.
- **Time-based expansion cap** instead of the 10k-node cap (more robust as per-node cost shifts).

## After arc (1)
- **Resource/goal arc (the "useful helper" payoff, depends on (1)):** `RegionMetadata` → resource counts →
  search-by-resource-location → commands like `mine diamonds` / `cut wood` (+ an inventory-drop mechanism).
  This is what makes Orebit a thing people actually use in-game.
- **Pathfinding completeness:** more move types (each is a `Movement` — DiagonalAscend/Parkour/Swim/Crawl);
  portal traversal (nether/end — cross-dimension skeleton stitching); background-threading the search (kills
  the ~11 ms tick hitches; needs the search made thread-safe first).
- **UX polish:** a debug-view enable/disable command; split debug particles (HPA*-center vs A*-exact path).
- **Deferred pathfinding-quality (opportunistic, composes with partial-path):** stronger forced-cost premium,
  dominance/symmetry pruning, the high-weight "dig out of the trap" escalation (reads `BotCaps` — lands after
  (1a/1c)), the SoA cuboid cache (`CUBOID-PERF-OPTIONS.md` option C).
- **Far horizon:** the LLM intent pipeline (`integration/`) — chat → intent → goal → the navigation + task
  machinery. Only sensible once the bot is a capable, configurable agent.
