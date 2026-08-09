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

A few keys are exceptions. `pathing.async`, `pathing.maxThreads` (the background-planner
switch and its thread-pool size) and `hpa.lazyLoad` (the paged world-memory startup) only
take effect on a server restart. And `mining.protectedBlocks` is half-and-half: the reload makes the bot *refuse* to break
newly-protected blocks immediately, but the planner keeps routing from cached block data
until a restart (or until the affected chunks naturally rebuild) — see the key's entry
below.

## Options

The file is grouped into sections by what they control. Every value below is the default.

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
| `placement.consumesBlocks` | `false` | If `true`, every block the bot places is taken from **its own inventory** — so it can only build as far as it has blocks, and it will run out (the planner also adds a flat 10-tick premium per placement, so a bot spending real blocks scaffolds less readily). If `false`, it places an unlimited supply of a "conjured" block (below). |
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
mining.protectedBlocks  = #minecraft:logs, #minecraft:planks, ...   # a broad default set — see below
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
| `mining.protectedBlocks` | *(a broad "don't wreck the build" set — see below)* | A comma-separated list of block ids and `#`-prefixed block tags the bot must **never break** *to clear a path* — nor destroy by placing over — e.g. `minecraft:chest, #minecraft:beds`. Enforced both when planning (routes go *around* protected blocks) and again at the moment of a route break. Deliberate commands are exempt: `/bot gather wood` still fells protected logs, farming still harvests mature crops — protection governs pathing, not the job you gave the bot. Malformed entries warn and are skipped. Unlike every other key, this one does **not** default to empty: out of the box it protects a large set of player-placed and decorative blocks so a bot won't tear through someone's build (full list below). To let the bot break anything, set it explicitly empty (`mining.protectedBlocks=`). **Changing this list needs a server restart** to fully apply (the planner caches block classifications); the at-the-moment-of-breaking refusal applies immediately on reload. See [Breaking & Placing](world_edits.md#protected-blocks). |
| `mining.allowUnbreakable` | `false` | If `true`, the bot may "mine" vanilla-unbreakable blocks (bedrock, barriers, end portal frames — anything with negative destroy time) at the tool-derived cost set by `mining.unbreakableHardness` below: it stands and grinds that long, then the block breaks. Independent of `mining.maxHardness` (unbreakable is its own axis, not "very hard"); `mining.protectedBlocks` always wins. |
| `mining.unbreakableHardness` | `3200` | The pretend "hardness" of those unbreakable blocks (they have none in vanilla) when `allowUnbreakable` is on. It feeds the normal mining-time formula assuming a pickaxe, so a **better pickaxe digs faster** and bare hands are far slower. Same scale as real blocks (obsidian, the hardest, is ~250) but may go past 255 to make unbreakable mining a stronger deterrent. The default `3200` works out to ~2 minutes per block with a diamond pickaxe. |

#### The default protected-blocks set

To keep a bot from tearing through a player's build, `mining.protectedBlocks` ships with a broad default
covering the things people place and decorate with:

- **Structure & finish:** logs, planks, cobblestone variants, stairs, slabs, walls, fences, fence gates,
  glass (blocks + panes), carpets
- **Openings:** doors and trapdoors (see the door note below)
- **Utility & storage:** crafting table and the other work stations, furnaces, chests, barrels, beehives, …
- **Light & decoration:** torches, lanterns, campfires, glowstone / sea lanterns / shroomlight / froglights
  and other glowing blocks, candles, flower pots
- **Redstone:** wire, repeaters, comparators, pistons, observers, hoppers, buttons, pressure plates, rails, …
- **Plants:** saplings, flowers, crops, vines, and other cultivated/decorative plants (but **not** wild grass,
  ferns, or seagrass — the bot may still clear those)
- **Personal touches:** beds, signs, banners
- **Ladders**

**Doors are still used, not just avoided.** Protection forbids *breaking* a block, not operating it — so the
bot still **opens and closes** wooden/copper doors and trapdoors to pass through (a non-destructive action;
an already-open fence gate is walked through, but the bot does not yet operate closed gates). Iron doors and
iron trapdoors (which need redstone) can't be hand-operated, so a protected iron one is **routed around**
rather than smashed.

**Leaves are intentionally not protected** — the bot may cut through foliage. **Ores are not protected either**
— protecting a block only stops the bot breaking it *to make a path*; a block you explicitly send the bot to
mine (e.g. `/bot mine iron`) is a target, not path terrain, and is unaffected.

Everything here is just a starting point — edit the list freely, or set `mining.protectedBlocks=` (empty) to
let the bot break anything. Tags and ids that don't exist on your Minecraft version are ignored (a missing
tag silently, a missing id with a one-line startup warning), so the same default works across versions.

### Pathfinding — how the bot plans routes

```properties
pathing.syncSearchBudgetNodes = 10000
pathing.greedyWeight          = 2.0
pathing.costPerHitpoint       = 100.0
pathing.boxedInScanRadius     = 3
pathing.warmup                = true
pathing.warmupBudgetMs        = 1500
pathing.async                 = true
pathing.maxThreads            = 2
pathing.asyncSearchBudgetMs   = 250
pathing.chunkBuildBudgetMs    = 2.0
pathing.chunkBuildsPerTick    = 64
pathing.hpaFlushBudgetMs      = 1.0
pathing.regionShardLoadBudgetMs = 2.0
pathing.navReadyRadiusChunks  = 4
pathing.navReadyTimeoutTicks  = 150
```

| Key | Default | What it does |
| --- | --- | --- |
| `pathing.syncSearchBudgetNodes` | `10000` | How hard the bot searches before giving up on a single plan **when `pathing.async` is off** — the tick-thread search cap, counted in positions examined rather than milliseconds so a slow search can never freeze the server for its whole duration. Higher finds paths through more tangled terrain but costs more CPU per plan. With `pathing.async` on (the default), the time budget below is the effective limit instead and this cap is only a memory backstop. |
| `pathing.greedyWeight` | `2.0` | How directly the bot beelines toward its goal. `1.0` finds the shortest possible route but searches slowly; higher values head straight at the goal and plan much faster, at the cost of slightly longer routes. Must be `1.0` or greater. |
| `pathing.costPerHitpoint` | `100.0` | How many ticks of travel time the bot considers **one hitpoint of damage** to be worth (`>= 0`). This single number prices *all* damage in the planner: walking through fire, berry bushes, or powder snow, swimming through lava, and dropping farther than a safe fall — each expected hitpoint costs this many ticks. Lava is by far the most expensive: a mortal bot is charged 10 hitpoints for every lava cell it swims through (immersion plus the burn afterwards) on top of lava's own slowness, so at the default `100` a single lava cell prices at over a thousand ticks and the bot swims lava only when nothing else exists. The intuition: one hitpoint buys roughly `costPerHitpoint / 4.6` blocks of detour, so at the default `100` the bot will walk about 22 blocks out of its way to avoid each point of damage — enough to route around a whole thicket of bushes rather than push through it. Raise it for a more self-preserving bot (it will take long detours and gentle descents); lower it for a daredevil that trades health for time. Only matters when `survival.takesDamage` is `true` — an invulnerable bot ignores damage entirely. |
| `pathing.boxedInScanRadius` | `3` | How wide a box (in coarse "region" cells, `1`–`16`) the bot scans around a goal to decide *up front* whether that goal is sealed off — walled in by solid blocks with no way through. This proactive check keeps the bot from wandering toward an unreachable spot before discovering it can't get there. A larger radius catches bigger sealed-off pockets (a `3` box spans from ~16 blocks up to a few thousand across the coarser levels) but costs a little more work each time the bot picks a new goal — it's a one-off cost per goal, not a per-step one. Leave it at `3` unless the bot is failing to notice large sealed rooms. |
| `pathing.warmup` | `true` | Run a short synthetic pathfinder warm-up at server start, before any player can join, so the first *real* path isn't computed by a cold JIT compiler (a one-time ~22 ms tick stall otherwise; ~0.7 ms with the warm-up — [the measurements](Optimizations/10_background_pathfinding.md)). Costs roughly half a second of startup wall-clock and nothing afterwards. |
| `pathing.warmupBudgetMs` | `1500` | The hard wall-clock cap, in milliseconds, on that warm-up pass. It usually finishes early (it stops once search times plateau, typically ~400–500 ms); `0` disables the warm-up entirely. |
| `pathing.async` | `true` | Compute paths on background threads instead of the server tick thread. Searches stop costing tick time entirely; a plan arrives a tick or two after it's requested, and the bot keeps walking its current plan meanwhile (it also pre-computes the next stretch before finishing the current one, so long walks don't pause at plan boundaries). Set `false` for the synchronous behaviour: searches run on the tick thread under the node cap above. Requires a server restart to change. |
| `pathing.maxThreads` | `2` | How many background planner threads to run when `pathing.async` is on (clamped to your core count minus two). All bots share the pool — raise it on a server with many bots to keep their plans snappy, lower it to `1` on a constrained host. Trades bot responsiveness against server CPU headroom, like view-distance. Requires a restart to change. |
| `pathing.asyncSearchBudgetMs` | `250` | The wall-clock budget, in milliseconds, for one background path search — with `pathing.async` on, *time* replaces the node cap as the effective search limit (the node cap remains as a memory backstop). A search that runs out of budget returns its best partial path; the bot moves that way and replans, converging on far goals. Bigger budgets escape bigger dead-ends at the cost of slower worst-case planning — the server tick is never stalled either way. |
| `pathing.chunkBuildBudgetMs` | `2.0` | How many milliseconds each server tick the bot spends building the terrain-scan data (the "nav grid") for freshly loaded chunks. A single chunk column costs anywhere from ~1 ms (open surface) to ~5 ms (deep caves), so a fixed *count* per tick couldn't adapt — this time budget builds as many columns as safely fit and leaves the rest for the next tick. After a teleport or a fresh world-open (which can load hundreds of chunks at once) the bot's surroundings fill in over a few ticks instead of spiking the frame. Raise it on a strong machine for faster fill-in; lower it on a constrained host. |
| `pathing.chunkBuildsPerTick` | `64` | A safety cap on how many chunk columns can build in a single tick, on top of the time budget above — it stops a burst of very cheap (all-air) columns from running away even inside the budget. This is a backstop, not the main limit; leave it unless a strong machine is spending too many columns per tick. |
| `pathing.hpaFlushBudgetMs` | `1.0` | How many milliseconds each tick the bot spends refreshing its long-range routing map after the world changes (blocks mined, built, exploded). Usually a fraction of this is enough to catch up fully; a big edit (TNT, a fill command) is absorbed over a few ticks instead of one. The map-building analog of `pathing.chunkBuildBudgetMs`. |
| `pathing.regionShardLoadBudgetMs` | `2.0` | How many milliseconds each tick the bot spends paging its saved long-range map back in from disk, when `hpa.lazyLoad` is on. As the bot heads toward a distant region it explored in an earlier session, that region's map is streamed off disk a shard at a time; this budget bounds how many shards load per tick (always at least one, so it never stalls) so a fresh approach fills in smoothly instead of hitching. With `hpa.lazyLoad` off and no eviction cap set, nothing is ever paged in and this budget never bites — but it still applies to shards that `hpa.residentLeafCap` evicted and the bot later returns to. |
| `pathing.navReadyRadiusChunks` | `4` | How large an area around the bot (in chunks) must have its terrain scan finished before the bot will plan a path. Nav data builds over a few ticks after chunks load, and the planner treats not-yet-scanned ground as empty air — so planning too early can aim the bot at a hole that isn't really there. The bot waits until a `(2×4+1)²` = 9×9-chunk ring is scanned, which briefly shows as "waiting for terrain" right after joining or teleporting. `0` disables the wait (plan immediately). |
| `pathing.navReadyTimeoutTicks` | `150` | A give-up backstop for that wait: if the readiness ring still isn't built after this many ticks, the bot stops waiting and tells you it can't get terrain data, instead of hanging forever on a genuinely un-loadable spot (the world border, a permanently missing chunk). On a healthy server the ring builds in a tick or two and this never fires. `150` ≈ 7.5 seconds. |

### World memory — surviving a restart

The bot remembers the coarse shape of terrain it has explored (for long-range routing) and where it saw
resources (for `/bot report`). That memory is saved into the world folder so it survives a server restart —
important for a server that stops when idle and restarts often. It's saved automatically on a clean shutdown;
the knobs below control the background safety-save in between and, optionally, how much of that memory is
kept in RAM at once. The saved files and everything a server admin can safely do with them are covered on the
[Saved Data](persistence.md) page.

```properties
hpa.persistIntervalTicks = 6000
hpa.persistFlushBudgetMs = 2.0
hpa.lazyLoad             = false
hpa.residentLeafCap      = 0
```

| Key | Default | What it does |
| --- | --- | --- |
| `hpa.persistIntervalTicks` | `6000` | How often (in server ticks, 20 = 1 second) to re-save that memory in the background as crash insurance, and only for worlds that changed since the last save. The real save happens on a clean server stop no matter what this is set to — this is just a safety net for a hard crash. `6000` ≈ 5 minutes. Set `0` to turn the periodic safety-save off (the shutdown save still runs). The data lives in `<world>/orebit/<dimension>/` and is treated as a cache — if a file is ever corrupted it's simply ignored and rebuilt as you explore. |
| `hpa.persistFlushBudgetMs` | `2.0` | How many milliseconds each tick that background safety-save is allowed to spend writing. When the interval above fires on a world that's been heavily explored, there can be a lot of changed data to write; rather than write it all in one tick (which caused a brief ~2-second server stall), the save trickles out under this budget across as many ticks as it takes. It's crash insurance, so spreading it out is safe — the clean-shutdown save is always complete. Leave it unless you want the periodic save to finish faster (raise) or stay even quieter (lower). |
| `hpa.lazyLoad` | `false` | Where the saved world-memory lives at runtime. `false` (default) loads all of it into RAM at server start — simple, and fine for most worlds. `true` loads only the top-level summary at start and streams each region's detail back off disk as the bot approaches it (see `pathing.regionShardLoadBudgetMs`), which keeps memory bounded on a world with a huge explored area — pair it with `hpa.residentLeafCap` to actually cap RAM. **Requires a server restart to change.** This mode is newer and off by default while it proves out; turn it on if bot world-memory RAM is a concern on a long-lived world. |
| `hpa.residentLeafCap` | `0` | The RAM cap for world memory, as a maximum number of detailed region tiles kept in memory at once. `0` (default) means unbounded — nothing is ever evicted. A positive value tells the bot to page the coldest regions (those whose chunks are all currently unloaded) back to disk once the resident count exceeds the cap, reloading them on demand if the bot returns. The top-level summary always stays resident, so long-range planning still works over evicted regions. Independent of `hpa.lazyLoad` — eviction runs on whatever is resident either way — but the two pair naturally: lazy loading bounds what is ever *loaded*, the cap bounds what *stays* loaded. |

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

### Doors — how the bot handles doors

```properties
doors.toggle = true
```

| Key | Default | What it does |
| --- | --- | --- |
| `doors.toggle` | `true` | Whether the bot may open and close hand-operable doors **and trapdoors** (wooden, copper) by right-clicking them, rather than smashing through or routing around — it opens a closed door before crossing (closing it again behind itself on a hallway corner), opens a hatch to drop through, and closes an open hatch to walk across it. Set `false` as a kill-switch: an already-open door or trapdoor is walked through, a closed one is mined (or routed around when protected). Iron doors and iron trapdoors are never hand-operable regardless of this setting (they need redstone). |

### Crafting — how `/bot craft` handles recipes that need a crafting table

```properties
crafting.placeTable        = true
crafting.reclaimTable      = true
crafting.tableSearchRadius = 16
```

`/bot craft <item> [count]` crafts from the bot's real inventory: small (2x2) recipes anywhere,
big (3x3) recipes only within arm's reach of a crafting table. These knobs govern what the bot
does when a big recipe needs a table and there isn't one around.

| Key | Default | What it does |
| --- | --- | --- |
| `crafting.placeTable` | `true` | When no crafting table is nearby, the bot may set down a temporary one from its inventory — and if it only carries planks, it crafts the table first (that's a small recipe). Set `false` to make it refuse instead. |
| `crafting.reclaimTable` | `true` | After using a temporary table it placed, the bot breaks it and takes it back. Set `false` to leave placed tables standing (the bot tells you where). |
| `crafting.tableSearchRadius` | `16` | How far (blocks, up to 48) the bot looks for an existing crafting table before considering placing one. `0` skips the search entirely. Only loaded chunks are searched. |

### Farming — how `/bot farm` tends the fields

```properties
farming.workRadius = 16
farming.till       = true
```

`/bot farm` puts the bot into a persistent farming state — like `follow`, it keeps going
until you give it another command. It harvests every fully-grown crop nearby, picks up the
drops, replants what it harvested, plants any bare farmland it has seeds for, and — with a hoe
in its inventory — can till fresh ground next to water and plant that too. When a work round
finishes it reports the tally ("harvested 8, planted 9, tilled 2 — watching the farm") and then
stands watch, checking back every few seconds so crops that ripen later get harvested and
replanted too. Aim it at ground with no farm on it at all and it says "nothing to farm here"
instead.

| Key | Default | What it does |
| --- | --- | --- |
| `farming.workRadius` | `16` | How far (blocks, 4–48) around the command spot the bot looks for work. |
| `farming.till` | `true` | Whether the bot may create NEW farmland (hoe + seeds + water nearby required) — i.e. expand your farm, not just tend it. Set `false` to keep the bot strictly to existing farmland. Tilling only happens where the ground would actually stay hydrated (the same water rule the game uses). |

The bot knows wheat, carrots, potatoes, and beetroots so far. Harvesting only ever touches
FULLY-GROWN crops — immature plants are always left alone (that's the farmer's own rule).
`mining.protectedBlocks` protects your farm from the PATHFINDER (the bot won't chew through
crops en route), while deliberate commands like farming and `/bot gather` still do their job.

### Combat — does the bot defend itself?

```properties
combat.defend     = true
combat.scanRadius = 16
```

Whenever a mob decides the bot is its target, the bot pauses whatever it was doing, fights back,
and then picks its task right back up where it left off. It fights smart per mob: creepers get
knocked back out of fuse range with sprint hits (never letting them finish their hiss),
skeletons get rushed down between arrows, and everything else gets timed, full-strength sword
swings (it picks the best weapon it carries — swords over axes, better tiers first).

| Key | Default | What it does |
| --- | --- | --- |
| `combat.defend` | `true` | Fight back when targeted. Note: with the default invulnerable bot (`survival.takesDamage=false`) mobs never even target it, so this only matters for mortal bots. Set `false` for a strict pacifist. |
| `combat.scanRadius` | `16` | How far (blocks, 8–32) the bot checks each tick for mobs that are targeting it. Mobs hunting it from farther away are engaged as they close in. |

### Building — how `/bot build` executes schematics

```properties
building.clearMismatches = true
```

Drop Litematica `.litematic` files into `<server dir>/orebit-schematics/` (the folder is created
the first time you use the command), then `/bot build <name> <x y z>` — the name tab-completes
from the folder, and the coordinates anchor the schematic's corner. The bot builds bottom-up from
its real inventory, block by block with the exact states from the schematic, and finishes with an
honest report: what it placed, what it cleared, and anything it could NOT do — unreachable spots,
protected blocks it refused to break, and a shopping list of missing materials.

| Key | Default | What it does |
| --- | --- | --- |
| `building.clearMismatches` | `true` | Whether the build may break blocks that are in the way (a real timed break with drops — and your `mining.protectedBlocks` list still always wins). Set `false` to make builds strictly additive. |

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
