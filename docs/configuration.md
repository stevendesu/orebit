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
mining.canMine         = true
mining.consumesTools   = false
mining.maxHardness     = 255
mining.ticksByHardness = true
mining.ticksToMineFlat = 0
```

| Key | Default | What it does |
| --- | --- | --- |
| `mining.canMine` | `true` | Whether the bot may break blocks to clear a path. Set `false` for a bot that always routes *around* obstacles instead of through them. |
| `mining.consumesTools` | `false` | If `true`, mining wears down the bot's real tools (they can break). If `false`, tools never take damage. |
| `mining.maxHardness` | `255` | The hardest block the bot is allowed to mine, on a `0`–`255` scale. `255` means "anything it can break." Lower it to keep a bot out of tough blocks — e.g. a small value lets it clear dirt and wood but not stone. |
| `mining.ticksByHardness` | `true` | If `true`, harder blocks take realistically longer to mine (and a better tool is faster) — so the bot prefers routes through softer material. If `false`, every block takes the same fixed time (below). |
| `mining.ticksToMineFlat` | `0` | The fixed time, in game ticks, to mine one block when `ticksByHardness` is `false`. `0` means instant. Ignored when `ticksByHardness` is `true`. |

### Pathfinding — how the bot plans routes

```properties
pathing.maxNodes     = 10000
pathing.greedyWeight = 2.0
```

| Key | Default | What it does |
| --- | --- | --- |
| `pathing.maxNodes` | `10000` | How hard the bot searches before giving up on a single plan. Higher finds paths through more tangled terrain but costs more CPU per plan. |
| `pathing.greedyWeight` | `2.0` | How directly the bot beelines toward its goal. `1.0` finds the shortest possible route but searches slowly; higher values head straight at the goal and plan much faster, at the cost of slightly longer routes. Must be `1.0` or greater. |

### Survival *(reserved)*

```properties
survival.takesDamage = false
survival.hunger      = false
survival.needsBreath = false
```

These describe whether the bot is mortal — taking damage, getting hungry, needing air. They
are read and validated today but **not yet wired into the bot's behavior**; they're reserved
for an upcoming release. You can set them now; they simply have no effect yet.

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

**A fast pathfinder for big open worlds** — plans quickly and ranges far, accepting slightly
less optimal routes:

```properties
pathing.maxNodes     = 20000
pathing.greedyWeight = 4.0
```
