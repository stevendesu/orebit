# Commands

Orebit is driven by a single `/bot` command with a handful of subcommands. Each bot
belongs to the player who spawns it and answers only to its owner. The verbs are
deliberately small — most of what the bot *does* comes from the
[pathfinding](pathfinding.md) and [world-edit](world_edits.md) machinery reacting to a
simple goal, not from an elaborate command grammar.

## The everyday verbs

| Command | What it does |
|:--------|:-------------|
| `/bot spawn` | Summon your bot (or respawn a dead or missing one). Refuses while a live bot already exists. |
| `/bot follow` | Follow you around, keeping pace and pathing over whatever terrain you cross. |
| `/bot come` | Path to your current position once, then stop. |
| `/bot goto <x> <y> <z>` | Path to a specific block coordinate. |
| `/bot stay` | Hold position — stop following or pathing. |
| `/bot here` | Recall the bot to you when it's wandered off or gotten stuck out of sight. |
| `/bot mine` | Mine the block you're looking at (a manual, line-of-sight dig). |
| `/bot find <resource>` | Report the nearest known concentration of a resource — see [Finding & Gathering](gathering.md). |
| `/bot gather <resource> [count]` | Go get it: find, path, mine, and come back with the goods. |
| `/bot drop <what>` | Toss items on the ground for you to pick up. `<what>` tab-completes: `all`, `resources` (ores/ingots/gems/logs), `tools`, `trash` (everything that isn't a resource, tool, or armor), or a specific resource name (`iron`, `diamond`, `gold`, `wood`, …). Dropped items get a short pickup delay so the bot doesn't vacuum them back. |
| `/bot craft <item> [count]` | Craft from what the bot is carrying. `<item>` tab-completes to everything it knows how to make (the game's own recipe book); `[count]` is the number of *result* items you want — a single plank craft yields four toward it. Small (2×2) recipes happen on the spot; a big (3×3) recipe sends the bot to a nearby crafting table — or it places one of its own and reclaims it afterward. See [Crafting configuration](configuration.md#crafting--how-bot-craft-handles-recipes-that-need-a-crafting-table). |
| `/bot farm` | Tend the farm around where the bot stands: harvest every fully-grown crop, sweep up the drops, replant, seed bare farmland, and — carrying a hoe — till fresh ground by water. Like `follow`, it's a standing job: the bot reports its tally ("harvested 8, planted 9, tilled 2 — watching the farm"), then stays and watches the fields, harvesting crops as they ripen until you give it another order. Immature crops are never touched. See [Farming configuration](configuration.md#farming--how-bot-farm-tends-the-fields). |
| `/bot build <name> <x> <y> <z>` | Build a Litematica schematic anchored at the given corner. Drop `.litematic` files into `orebit-schematics/` in the server folder (created on first use) — `<name>` tab-completes from there. The bot builds bottom-up from its real inventory with the schematic's exact block states, clears what's in the way (honest timed digs — your protected-blocks list always wins), and finishes with a report of anything it couldn't do, including a shopping list of missing materials. See [Building configuration](configuration.md#building--how-bot-build-executes-schematics). |
| `/bot report` | Dump the bot's resource knowledge (the "compass") as a table: each resource it has mapped, with approximate counts at a few scales — `near`/`mid`/`far` (within ~64 / ~512 / ~8192 blocks of you, full depth) out to `global` (everything it has **ever explored, anywhere** — even resources it walked past thousands of blocks away). The near/mid/far windows are centered on you, so the numbers stay steady as you move a few blocks (no jump at the world origin). Big numbers show as `~2^n`. The whole census is [saved with the world](persistence.md), so it survives a server restart. See [Finding & Gathering](gathering.md). |
| `/bot config <…>` | Read or reload the bot's [configuration](configuration.md) without a server restart. |

The bot is a real server-side player, so `follow` / `come` / `goto` are not teleports —
the bot walks, jumps, swims, climbs, bridges, and (if you let it) digs its way there,
paying honest survival costs the whole way. If it can't reach a goal it says so rather
than cheating its way over.

## No verb for fighting

There is deliberately no `/bot fight`. Combat is a reflex, not a job: the moment a
hostile mob takes aim at the bot, it pauses whatever it was doing, draws the best weapon
it carries, and deals with the threat — then picks its task back up exactly where it left
off. It fights per-mob: creepers are knocked back out of fuse range before the hiss
finishes, skeletons are rushed down between arrows, and everything else gets timed,
full-strength swings. An invulnerable bot (the default) is never targeted by mobs in the
first place, so this mostly matters once you make the bot mortal — and
`combat.defend=false` turns it into a strict pacifist. See
[Combat configuration](configuration.md#combat--does-the-bot-defend-itself).

## Diagnostics

The interesting commands are the ones for *seeing why the bot did what it did*. Because
the bot's behaviour is entirely a product of the cost model — "every 'why did it swim
instead of taking the ladder?' is answered by the arithmetic" — the diagnostics expose
that arithmetic directly. These write to the server's run directory and are meant for
tuning and bug reports, not everyday play.

- **`/bot trace`** — stop the bot and run a single block-level search from where it
  stands to your position, logging *every* candidate position it considers and why each
  was accepted or rejected, to `orebit-trace.txt`. An offline analyzer renders the dump
  as a four-panel picture of the search's *shape* — where it flooded, where it threaded —
  which is how the open-air pillar-flood and the region-heuristic behaviour were both
  diagnosed.
- **`/bot rtrace`** — the same idea one tier up: trace the **region** search, logging each
  coarse edge (walk / fall / dig-through / mine) with its full cost breakdown, so you can
  see *why the skeleton takes the route it takes* — the "did it route the loop or the
  direct dig, and at what price" question.
- **`/bot probe <x> <y> <z>`** — dump exactly what the planner sees at one cell: its
  navtype, the decoded neighbour flags, the per-cell surcharge for passing through it
  versus the price to break it, and the capability gate in force. This is the
  stale-grid-versus-stale-flags-versus-caps discriminator when a bot behaves oddly at a
  specific spot.
- **`/bot stats`** — a read-only dump of the bot's search-health telemetry for the
  current journey and the last completed one: searches run, positions examined, partial
  paths, replans and route repairs, plus a route-efficiency figure (distance actually
  traveled versus the straight line). It's the live, mid-journey view of the same numbers
  the bot logs when a journey ends — the quick way to see a flood or a roundabout route
  developing without tailing the console.
- **`/bot debug on|off`** — toggle verbose per-tick planner logging (skeleton dumps,
  window swaps, block-plan summaries) for watching the two-tier driver work in real time.

If you're filing a bug about the bot taking a strange route, a `/bot trace` or `/bot
rtrace` dump is worth a thousand words — it's the difference between "the bot went the
wrong way" and "the bot priced the two-block dig at 1736 because of X."
