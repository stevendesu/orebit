# Configuring Orebit

Orebit reads its settings from a single plain-text file, **`orebit.properties`**, so a
server owner can decide exactly how capable — and how "fair" — their bots are.

## Where the file lives

The file is created automatically the first time Orebit runs, at:

```
config/orebit.properties
```

…inside your Minecraft directory — the same `config/` folder (next to `mods/`) that other
mods use:

| You're running… | The file is at… |
| --- | --- |
| A single-player world | `<your instance>/config/orebit.properties` |
| A dedicated server | `<server root>/config/orebit.properties` |

You don't have to create it yourself. On first launch Orebit writes the file with every
option set to its default and a comment explaining each one. Open it in any text editor to
make changes.

## Applying changes

After you edit the file you can apply it **without restarting** by running:

```
/bot config reload
```

The new settings take effect on each bot's next path. (The command echoes the key values
back so you can confirm they loaded.) The defaults are chosen so that, out of the box, a
freshly configured bot behaves exactly as it does with no config at all — change only what
you want to change.

A few keys are exceptions. `pathing.async` and `pathing.maxThreads` (the background-planner
switch and its thread-pool size) only take effect on a server restart. And
`mining.protectedBlocks` is half-and-half: the reload makes the bot *refuse* to break
newly-protected blocks immediately, but the planner keeps routing from cached block data
until a restart (or until the affected chunks naturally rebuild) — see the key's entry
below.

## Options

The file is grouped into four sections. Every value below is the default.

### Placement — can the bot build?

```properties
placement.canPlace          = true
placement.consumesBlocks    = false
placement.conjuredBlock     = minecraft:cobblestone
placement.removalCostWeight = 1.0
placement.placeBaseCost     = 6.0
```

| Key | Default | What it does |
| --- | --- | --- |
| `placement.canPlace` | `true` | Whether the bot may place blocks at all (to bridge gaps or pillar up). Set `false` for a bot that only ever walks and climbs existing terrain. |
| `placement.consumesBlocks` | `false` | If `true`, every block the bot places is taken from **its own inventory** — so it can only build as far as it has blocks, and it will run out. If `false`, it places an unlimited supply of a "conjured" block (below). |
| `placement.conjuredBlock` | `minecraft:cobblestone` | The block placed when `consumesBlocks` is `false`. Any block id works (e.g. `minecraft:dirt`). |
| `placement.removalCostWeight` | `1.0` | How strongly the bot avoids placing hard-to-remove blocks. Each placement is charged extra by the block's mine-out time × this weight, so with a mixed inventory it favors dirt/cobblestone over obsidian. `1.0` = full mine-out cost, `0` = disabled (placement cost ignores the block). |
| `placement.placeBaseCost` | `6.0` | Flat cost (in ticks) charged per block placed (`>= 0`). This is a **behavioral penalty**, not a physical place time — placing a block in-game is near-instant; the cost is the bot's "reluctance to place" (positioning/facing overhead plus a bias against needless scaffolding) that tilts it toward walking or digging around rather than building. **Lower** it for a more build-happy bot (it will pillar/bridge sooner); **raise** it to discourage placing. |

### Mining — can the bot dig?

```properties
mining.canMine          = true
mining.consumesTools    = false
mining.maxHardness      = 255
mining.ticksByHardness  = true
mining.ticksToMineFlat  = 0
mining.breakBaseCost    = 0.0
mining.protectedBlocks  =
mining.allowUnbreakable = false
mining.unbreakableHardness = 3200
```

| Key | Default | What it does |
| --- | --- | --- |
| `mining.canMine` | `true` | Whether the bot may break blocks to clear a path. Set `false` for a bot that always routes *around* obstacles instead of through them. |
| `mining.consumesTools` | `false` | If `true`, mining wears down the bot's real tools (they can break). If `false`, tools never take damage. |
| `mining.maxHardness` | `255` | The hardest block the bot is allowed to mine, on a `0`–`255` scale. `255` means "anything it can break." Lower it to keep a bot out of tough blocks — e.g. a small value lets it clear dirt and wood but not stone. |
| `mining.ticksByHardness` | `true` | If `true`, harder blocks take realistically longer to mine (and a better tool is faster) — so the bot prefers routes through softer material. If `false`, every block takes the same fixed time (below). |
| `mining.ticksToMineFlat` | `0` | The fixed time, in game ticks, to mine one block when `ticksByHardness` is `false`. `0` means instant. Ignored when `ticksByHardness` is `true`. |
| `mining.breakBaseCost` | `0.0` | A flat surcharge (in ticks) added to **every break the planner considers**, on top of the real mining time — the mining-side mirror of `placement.placeBaseCost`. It's a behavioral "reluctance to edit the world": raise it and the bot detours around obstacles (and wades through berry bushes) it would otherwise punch through; at `0` breaks are priced at mining time alone. |
| `mining.protectedBlocks` | *(empty)* | A comma-separated list of block ids and `#`-prefixed block tags the bot must **never break** — nor destroy by placing over — e.g. `minecraft:chest, #minecraft:beds`. Enforced both when planning (routes go *around* protected blocks) and again at the moment of breaking. Malformed entries warn and are skipped. **Changing this list needs a server restart** to fully apply (the planner caches block classifications); the at-the-moment-of-breaking refusal applies immediately on reload. See [Breaking & Placing](world_edits.md#protected-blocks). |
| `mining.allowUnbreakable` | `false` | If `true`, the bot may "mine" vanilla-unbreakable blocks (bedrock, barriers, end portal frames — anything with negative destroy time) at the tool-derived cost set by `mining.unbreakableHardness` below: it stands and grinds that long, then the block breaks. Independent of `mining.maxHardness` (unbreakable is its own axis, not "very hard"); `mining.protectedBlocks` always wins. |
| `mining.unbreakableHardness` | `3200` | The pretend "hardness" of those unbreakable blocks (they have none in vanilla) when `allowUnbreakable` is on. It feeds the normal mining-time formula assuming a pickaxe, so a **better pickaxe digs faster** and bare hands are far slower. Same scale as real blocks (obsidian, the hardest, is ~250) but may go past 255 to make unbreakable mining a stronger deterrent. The default `3200` works out to ~2 minutes per block with a diamond pickaxe. |

### Pathfinding — how the bot plans routes

```properties
pathing.syncSearchBudgetNodes = 10000
pathing.greedyWeight          = 2.0
pathing.costPerHitpoint       = 100.0
pathing.warmup                = true
pathing.warmupBudgetMs        = 1500
pathing.async                 = true
pathing.maxThreads            = 2
pathing.asyncSearchBudgetMs   = 250
```

| Key | Default | What it does |
| --- | --- | --- |
| `pathing.syncSearchBudgetNodes` | `10000` | How hard the bot searches before giving up on a single plan **when `pathing.async` is off** — the tick-thread search cap, counted in positions examined rather than milliseconds so a slow search can never freeze the server for its whole duration. Higher finds paths through more tangled terrain but costs more CPU per plan. With `pathing.async` on (the default), the time budget below is the effective limit instead and this cap is only a memory backstop. |
| `pathing.greedyWeight` | `2.0` | How directly the bot beelines toward its goal. `1.0` finds the shortest possible route but searches slowly; higher values head straight at the goal and plan much faster, at the cost of slightly longer routes. Must be `1.0` or greater. |
| `pathing.costPerHitpoint` | `100.0` | How many ticks of travel time the bot considers **one hitpoint of damage** to be worth (`>= 0`). This single number prices *all* damage in the planner: walking through fire, berry bushes, or powder snow, and dropping farther than a safe fall — each expected hitpoint costs this many ticks. The intuition: one hitpoint buys roughly `costPerHitpoint / 4.6` blocks of detour, so at the default `100` the bot will walk about 22 blocks out of its way to avoid each point of damage — enough to route around a whole thicket of bushes rather than push through it. Raise it for a more self-preserving bot (it will take long detours and gentle descents); lower it for a daredevil that trades health for time. Only matters when `survival.takesDamage` is `true` — an invulnerable bot ignores damage entirely. |
| `pathing.warmup` | `true` | Run a short synthetic pathfinder warm-up at server start, before any player can join, so the first *real* path isn't computed by a cold JIT compiler (a one-time ~22 ms tick stall otherwise; ~0.7 ms with the warm-up — [the measurements](Optimizations/depth_nibbles.md)). Costs roughly half a second of startup wall-clock and nothing afterwards. |
| `pathing.warmupBudgetMs` | `1500` | The hard wall-clock cap, in milliseconds, on that warm-up pass. It usually finishes early (it stops once search times plateau, typically ~400–500 ms); `0` disables the warm-up entirely. |
| `pathing.async` | `true` | Compute paths on background threads instead of the server tick thread. Searches stop costing tick time entirely; a plan arrives a tick or two after it's requested, and the bot keeps walking its current plan meanwhile (it also pre-computes the next stretch before finishing the current one, so long walks don't pause at plan boundaries). Set `false` for the synchronous behaviour: searches run on the tick thread under the node cap above. Requires a server restart to change. |
| `pathing.maxThreads` | `2` | How many background planner threads to run when `pathing.async` is on (clamped to your core count minus two). All bots share the pool — raise it on a server with many bots to keep their plans snappy, lower it to `1` on a constrained host. Trades bot responsiveness against server CPU headroom, like view-distance. Requires a restart to change. |
| `pathing.asyncSearchBudgetMs` | `250` | The wall-clock budget, in milliseconds, for one background path search — with `pathing.async` on, *time* replaces the node cap as the effective search limit (the node cap remains as a memory backstop). A search that runs out of budget returns its best partial path; the bot moves that way and replans, converging on far goals. Bigger budgets escape bigger dead-ends at the cost of slower worst-case planning — the server tick is never stalled either way. |

### Survival — is the bot mortal?

```properties
survival.takesDamage = false
survival.hunger      = false
survival.needsBreath = false
```

These decide whether the bot has a body that can be hurt. The bot runs the full vanilla
player simulation, so when these are on, the mechanics are the real ones — not approximations.

| Key | Default | What it does |
| --- | --- | --- |
| `survival.takesDamage` | `false` | If `true`, the bot takes damage like a player — lava, fire, falls, cactus, mobs. This also changes how it *plans*: a mortal bot pays a steep path cost to walk through fire or a berry bush and treats big drops as expensive, so it routes around hazards an invulnerable bot would stroll through. How steep is one knob: `pathing.costPerHitpoint` (see Pathfinding above). `false` = invulnerable (hazards still cost it time, never health). |
| `survival.hunger` | `false` | If `true`, the bot's food bar drains from activity like a player's. If `false`, it never gets hungry (and can always sprint). Note the bot doesn't yet feed itself — a hungry bot is your problem to keep fed. |
| `survival.needsBreath` | `false` | If `true`, the bot's air depletes underwater and it can drown. If `false`, it can swim submerged indefinitely. |

## Example configurations

**A pacifist guide** — never digs, never builds, just finds its way through what's already
there:

```properties
placement.canPlace = false
mining.canMine     = false
```

**A survival-honest helper** — mines and builds from its real inventory, with tool wear and
realistic mining times, so it actually consumes resources like a player:

```properties
placement.consumesBlocks = true
mining.consumesTools     = true
mining.ticksByHardness   = true
```

With `consumesBlocks` on, the bot also prefers to spend its *cheapest-to-dig-out* blocks
first — `placement.removalCostWeight` (default `1.0`) charges each placement by the placed
block's mine-out time, so a bot carrying both dirt and obsidian bridges with the dirt. Raise
it to disfavor hard blocks even more strongly; set it to `0` to ignore the block entirely.

How eagerly the bot builds at all is `placement.placeBaseCost` (default `6.0`) — a flat
behavioral penalty per placement (not a physical place time; placing is near-instant). At the
default the bot will pillar or bridge a short distance rather than take a long detour. Lower it
for a more build-happy bot; raise it to make the bot strongly prefer walking and digging over
building scaffolding.

**A fast pathfinder for big open worlds** — beelines hard and gets more time to escape big
dead-ends, accepting slightly less optimal routes:

```properties
pathing.greedyWeight        = 4.0
pathing.asyncSearchBudgetMs = 500
```
