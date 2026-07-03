# Orebit configuration — `config/orebit.properties`

> Phase 1 (Agency Layer) owner-facing config. This is the canonical reference for every key the server owner
> can set. The subsystem lives in `config/` (`Config`, `ConfigKeys`, `ConfigValidator`, `ConfigLoader`) and is
> read by the pathfinder (via `BotCaps`), the follower (conjured block + future survival flags), and the
> mining-tick model (`MiningModel`). **All defaults reproduce today's stock follower behaviour exactly**, so
> generating the file changes nothing until the owner edits it.

## File location

```
<server run dir>/config/orebit.properties
```

The run/config directory is resolved through the `ConfigDir` platform seam (it reads the dir off the
`MinecraftServer` the loader hands us), so the same code works on the multi-loader mc-1.21 era and the
pure-Fabric 26.x era, and on both dedicated servers and integrated single-player worlds (each resolves to its
own run dir). A dedicated server's file is at `<server>/config/orebit.properties`; an integrated world resolves
to the client run dir's `config/`.

## Format

Plain `java.util.Properties` (JDK built-in — **zero new dependencies**):

- `key=value`, one per line.
- `#` starts a comment line.
- Flat, dotted, namespaced keys (`survival.*`, `placement.*`, `mining.*`, `pathing.*`) — the namespace is
  purely lexical (a dotted prefix), `Properties` has no real sections.

On first run (file missing) Orebit **writes a fully-commented default** documenting every key, then uses
`Config.DEFAULT`. On a read/parse error it logs a warning and falls back to defaults — it never crashes server
start. Out-of-range or unparseable individual values are clamped/defaulted with a one-line warning (see
**Validation** below), never fatal.

## Keys

### `survival.*` — does the bot have a body that can be hurt / starve / drown?

| Key | Type | Default | Effect | Example |
|---|---|---|---|---|
| `survival.takesDamage` | boolean | `false` | Bot takes damage at all (fall, mobs, fire, …). `false` = invulnerable. | `survival.takesDamage=true` |
| `survival.hunger` | boolean | `false` | Bot has a hunger bar that depletes. | `survival.hunger=true` |
| `survival.needsBreath` | boolean | `false` | Bot needs air underwater (can drown). | `survival.needsBreath=true` |

> Note (updated): the survival flags are now **wired at runtime** — the bot runs the full vanilla player
> tick in SURVIVAL, with `takesDamage` driving both invulnerability flags, `needsBreath` gating
> `decreaseAirSupply`, and `hunger` gating `causeFoodExhaustion`. `takesDamage` also rides into
> `BotCaps.takesDamage` so the planner prices damage (the fall window + the pass-through hazard
> surcharge are caps-honest: an immune bot pays neither).

### `placement.*` — may the bot place blocks, and what does it place?

| Key | Type | Default | Effect | Example |
|---|---|---|---|---|
| `placement.canPlace` | boolean | `true` | Bot may place blocks (bridge a gap, pillar up). → `BotCaps.canPlace`; a `false` here makes the A* never emit place-moves. | `placement.canPlace=false` |
| `placement.consumesBlocks` | boolean | `false` | Placing draws a real block from the bot's inventory. `false` = infinite conjured supply (today's behaviour). When `true`: placement feasibility becomes a path constraint (the bot needs enough carried blocks) **and** a place charges an inventory premium tick cost. | `placement.consumesBlocks=true` |
| `placement.conjuredBlock` | block id (`namespace:path`) | `minecraft:cobblestone` | The throwaway block the bot conjures/places when `consumesBlocks=false`. Must resolve to a real block on the running version or it's rejected back to cobblestone. | `placement.conjuredBlock=minecraft:dirt` |
| `placement.removalCostWeight` | float `>= 0.0` | `1.0` | Removal premium: every placement is charged extra by the placed block's mine-out time (ticks, `MiningModel.fastestTicks`) × this weight — "the cost of potentially mining it out later" — so a mixed inventory favors dirt/cobblestone over obsidian. The premium is precomputed once per pathfind in `BotInventory.feasibility` over the representative placed block (the softest carried placeable when `consumesBlocks`, else the conjured block) and read as a scalar field in `MovementContext.placeCost` (hot-path-safe). `1.0` = full mine-out cost, `0` = disabled. | `placement.removalCostWeight=2.0` |
| `placement.placeBaseCost` | float `>= 0.0` | `6.0` | Flat per-placement base cost (ticks) — a **behavioral "reluctance to place" penalty, NOT a physical place time** (placing is ~1 tick in-game). `6` ≈ the place interaction plus a few ticks of positioning/facing overhead beyond the bare move, plus a bias against needless scaffolding, so A* prefers walking/digging over building when a comparable route exists. Threaded onto the per-search `InventoryView` (same path as the removal premium) and read as a scalar in `MovementContext.placeCost`; headless/trace/test searches (no snapshot) fall back to the static `MovementContext.PLACE_BASE_COST` default, which equals `6.0`. **Coupling:** `GoalForcedCost`'s anti-flood pillar premium derives from the static default (`PILLAR_STEP_COST = Pillar.COST + PLACE_BASE_COST ≈ 4.633 + 6 ≈ 10.6`), so lowering the default lowers the premium → the open-air-pillar search explores more (an intended, build-happier trade — measure when changing). A per-bot `placeBaseCost` changes actual path g-cost only, never the heuristic probe (no bot context). Lower → more build-happy; raise → discourage placing. | `placement.placeBaseCost=12.0` |

### `mining.*` — may the bot mine, what can it mine, how long does it take?

| Key | Type | Default | Effect | Example |
|---|---|---|---|---|
| `mining.canMine` | boolean | `true` | Bot may mine (break) blocks in its way. → `BotCaps.canBreak`; `false` makes the A* never emit break-moves. | `mining.canMine=false` |
| `mining.consumesTools` | boolean | `false` | Mining wears down (damages, eventually consumes) the bot's real tools. `false` = no wear (today). | `mining.consumesTools=true` |
| `mining.maxHardness` | int `0..255` | `255` | Hardest block the bot may mine (quantized `NavBlock` hardness). `255` = mine anything breakable (today's insta-mine cap); lower values let a weak bot mine only soft blocks. → `BotCaps.maxBreakHardness`, gates `MovementContext.breakable()`. | `mining.maxHardness=3` |
| `mining.ticksByHardness` | boolean | `true` | Mining time scales with block hardness/tool (the physically-derived tick model) vs. a flat per-block time. | `mining.ticksByHardness=false` |
| `mining.ticksToMineFlat` | int `>=0` | `0` | Ticks to mine one block when `ticksByHardness=false`. Ignored when `ticksByHardness=true`. `0` = insta-mine (matching today's flat behaviour). | `mining.ticksToMineFlat=20` |
| `mining.breakBaseCost` | float `>=0` | `0.0` | Flat surcharge (ticks) added to **every break the planner folds**, on top of the real mining time — the mining-side mirror of `placement.placeBaseCost`: a behavioral "reluctance to edit the world" penalty. Raise it to discourage gratuitous digging and hazard punch-throughs (breaking through a berry bush/cobweb instead of wading). Rides the per-pathfind inventory snapshot (`MovementContext.InventoryView.breakBaseCost` → `breakCost()`), never `BotCaps`. | `mining.breakBaseCost=12` |
| `mining.protectedBlocks` | comma list of block ids + `#`tags | *(empty)* | Blocks the bot must **NEVER break — nor clear/replace with a placement** (filling a cell destroys its occupant, so the `OPEN_PLACE` bit and both live place paths refuse protected occupants too). Entries are exact block ids (`minecraft:chest`) or block tags (`#minecraft:beds`); malformed/unknown entries warn and are skipped individually. Enforced **both sides** (the planner/executor parity rule): planner-side the list is folded into the `NavBlock` classification fingerprint at config install (`NavBlock.applyProtected` sets the PROTECTED descriptor bit, splitting matching states into their own navtypes — the derived BREAKABLE bit excludes them, so `MovementContext.breakable`/`breakableThrough`/MineDown/the `GoalForcedCost` dig face all refuse in one bit test and routes are planned *around* protected blocks); execution-side every live break (`AllyBotEntity.applyEdits`/`place`, `BotMining`) re-checks the LIVE state via `Config.mayBreak` — the immediate hard backstop that also covers stale grids. **Changing this list requires a server restart** (or waiting for chunks to rebuild) for the *planner* to fully see it: protected-ness is baked into cached nav-grid navtypes at classification time; `/bot config reload` re-derives the table + warns, and the execution-side refusal applies immediately. A `#tag` that doesn't exist on the server parses fine and matches nothing. | `mining.protectedBlocks=minecraft:chest, #minecraft:beds, minecraft:diamond_ore` |
| `mining.allowUnbreakable` | boolean | `false` | Bot may mine **vanilla-unbreakable** blocks (negative destroy time — bedrock, barriers, end portal frame/gateway, command blocks, …; detected by the hardness sign at classification time, never a hardcoded block list) at the fixed stand-in cost `MiningModel.UNBREAKABLE_STANDIN_TICKS` (2400 ticks = 2 min/block — vanilla defines no mining time for these, so the price is policy: ~10× the hardest legit dig with the best tool, break-even ≈ 518 walk-blocks of detour). Both sides honor it: planner (`BotCaps.allowUnbreakable` → `MovementContext.breakable`/`breakableThrough`/`breakCost`, `GoalForcedCost` grind face) and executor (`BotMining` grinds at the same tick rate, then forces the edit past the survival path's own refusal; `Config.mayBreak` gates the legacy `applyEdits` path). **Its own axis**: deliberately *not* subject to `mining.maxHardness` (the 255 sentinel doesn't order against real hardness). `mining.protectedBlocks` **always overrides**. Hot-reloadable (no descriptor bit — rides caps). | `mining.allowUnbreakable=true` |

### `pathing.*` — the A\* search knobs (carried on `BotCaps` into `BlockPathfinder`)

| Key | Type | Default | Effect | Example |
|---|---|---|---|---|
| `pathing.maxNodes` | int `> 0` | `10000` | A\* node-expansion ceiling per search. → `BotCaps.maxNodes`. Higher = can route farther at a slower worst case; lower = bails sooner (partial-path return then walks + replans). | `pathing.maxNodes=20000` |
| `pathing.greedyWeight` | float `>= 1.0` | `2.0` | Heuristic greediness weight (multiplies the tick-unit octile). `1.0` = admissible/optimal/slow; higher beelines (far fewer nodes, paths no longer guaranteed optimal). → `BotCaps.greedyWeight`. | `pathing.greedyWeight=3.0` |
| `pathing.costPerHitpoint` | float `>= 0.0` | `100.0` | **The ONE damage-pricing knob**: ticks the planner considers 1 HP of damage to be worth. → `BotCaps.costPerHitpoint`. Every damage-as-cost term is priced in it: the pass-through hazard surcharge (`MovementContext.cellTransitCost` — 1 HP per damaging body cell transited: fire, berry bush, powder snow) and the fall-damage penalty (`Fall`/`Parkour` — 1 HP per block dropped past `safeFallDistance`). **Break-even intuition:** 1 HP buys `costPerHitpoint / 4.633` walk-blocks of detour (≈ 21.6 at the default), so 4 hazard cells justify an ~86-block detour. The pre-unification hardcodes (40/hazard-cell, 10/excess-fall-block) bought only ~9 / ~2 blocks — a bush MAZE was rationally plowed through lethally because cumulative death was never priced. Raise for a more self-preserving bot; only meaningful with `survival.takesDamage=true` (an immune bot's damage terms are zero). Ratified successor (not built): a cumulative health-aware damage *budget* per path. | `pathing.costPerHitpoint=500` |
| `pathing.warmup` | boolean | `true` | Run a short synthetic pathfinder warm-up at server start (`NavWarmup`, `internal_docs/PERF-DESIGN-warmup-searches.md`): ~250-500 searches over a private in-memory fixture, SYNCHRONOUS in the `SERVER_STARTED` hook (before any player can join), so the bot's FIRST real search doesn't run JIT-cold (a one-time ~16-30 ms tick stall otherwise; measured ~0.7 ms with warm-up, E5 harness). Costs ~0.4-0.6 s of startup wall-clock only — boot-only, zero effect on any search after boot. NOT read into `BotCaps`; read once by `OrebitCommon`'s hook. | `pathing.warmup=false` |
| `pathing.warmupBudgetMs` | int `>= 0` | `1500` | Hard wall-clock cap (ms) on that warm-up pass; it usually stops earlier (plateau detection on the short-search mean, min 4 / max 8 rounds — typically ~380-530 ms). `0` disables the warm-up entirely. | `pathing.warmupBudgetMs=3000` |

## Mapping to `BotCaps`

`Config.toBotCaps()` folds the placement / mining / pathing knobs into the capability gate the block-tier A\*
threads per search:

- `canBreak = mining.canMine`, `maxBreakHardness = mining.maxHardness`,
  `allowUnbreakable = mining.allowUnbreakable`
- `canPlace = placement.canPlace`
- `maxNodes = pathing.maxNodes`, `greedyWeight = pathing.greedyWeight`
- `costPerHitpoint = pathing.costPerHitpoint` — the unified ticks-per-HP damage price every
  damage-as-cost term reads (hazard transit + fall damage)
- `jumpHeight = 1` (fixed; not yet an owner knob)
- `takesDamage = survival.takesDamage`, which also derives the fall window: a mortal bot gets the
  default safe/max fall distances, an immune bot gets unlimited (`IMMUNE_FALL` — every drop free).

`consumesBlocks`/`consumesTools`, the tick model, and the `hunger`/`needsBreath` flags are **not** in
`BotCaps`: they drive the follower body and the mining-tick model (`MiningModel`), not move generation, so
their consumers read `Config`/`ConfigLoader` directly. The exception is `survival.takesDamage`, which DOES
ride into `BotCaps.takesDamage` — the planner prices damage as cost (fall window, pass-through hazard
surcharge), so mortality is a move-generation fact too.

## How changes are picked up (restart vs. hot-reload)

- **At server start:** `OrebitCommon.init`'s `onServerStarted` hook calls `ConfigLoader.load(server)` once —
  parse + validate into a `Config`, derive a `BotCaps`, then build the `MiningModel` tick tables. Both the
  `Config` and `BotCaps` are cached in statics (`ConfigLoader.config()` / `ConfigLoader.botCaps()`).
- **Hot-reload (no restart):** run **`/bot config reload`** in-game. It re-reads the same file, re-installs the
  cached `Config` + `BotCaps`, and re-bakes the `MiningModel` tick tables (so a changed `ticksByHardness` /
  `ticksToMineFlat` model takes effect immediately). The command confirms with the new `maxNodes`,
  `greedyWeight`, `canMine`, `canPlace`.
- **Exception — `mining.protectedBlocks` needs a restart to fully apply:** the list is baked into the
  `NavBlock` classification fingerprint (the PROTECTED descriptor bit) and nav-grid sections cache the
  navtypes they were classified with, so grid data built before a list change keeps the old fingerprints
  until its chunks rebuild. A `/bot config reload` re-derives the table (fresh classifications are
  correct), logs a staleness warning, and the **execution-side refusal applies immediately** — so a
  reload is safe, just not planner-complete until restart/rebuild. `mining.allowUnbreakable` has no such
  caveat (it rides `BotCaps`, not the fingerprint — fully hot-reloadable).
- **When it takes effect on the bot:** the follower reads the live `ConfigLoader` cache **per replan**
  (`caps()` → `ConfigLoader.botCaps()`, `placeBlock()` → `ConfigLoader.config().conjuredBlockState()`), so a
  reload applies on the bot's **next plan** — no per-tick or per-A\*-node cost (the parse is paid once; the hot
  loop reads search-start locals).

## Validation (clamp-and-warn, never crash)

`ConfigValidator` parses each key into the typed `Config`, emitting a one-line warning for anything it has to
fix, and never failing the load:

- `pathing.maxNodes` clamped to `>= 1`.
- `pathing.greedyWeight` clamped to `>= 1.0`.
- `pathing.costPerHitpoint` clamped to `>= 0.0` (the `weightNonNeg` helper).
- `pathing.warmupBudgetMs` clamped to `0..60000`.
- `mining.maxHardness` clamped to `0..255`.
- `mining.ticksToMineFlat` clamped to `>= 0`.
- `placement.removalCostWeight`, `placement.placeBaseCost`, and `mining.breakBaseCost` clamped to `>= 0.0`
  (the `weightNonNeg` helper).
- `mining.protectedBlocks` parsed per entry (`ProtectedBlocks.parse`): each malformed id / tag warns and is
  skipped individually — the remaining entries still apply.
- Booleans default to their `Config.DEFAULT` value on anything that isn't exactly `true`/`false`.
- `placement.conjuredBlock` falls back to `minecraft:cobblestone` if it doesn't resolve to a real block on the
  running version.
- A missing key uses its default; a missing file writes the commented default and uses `Config.DEFAULT`.

## Example: a weak survival bot

```properties
# A fragile bot that can be killed, mines only soft blocks slowly with real tools,
# bridges from its own inventory, beelines harder to save CPU, and treats its
# hitpoints as very expensive (detours far around hazards and big drops).
survival.takesDamage=true
survival.hunger=true
placement.consumesBlocks=true
placement.conjuredBlock=minecraft:dirt
mining.consumesTools=true
mining.maxHardness=3
mining.ticksByHardness=true
pathing.greedyWeight=3.0
pathing.costPerHitpoint=500
```
