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
| `/bot config <…>` | Read or reload the bot's [configuration](configuration.md) without a server restart. |

The bot is a real server-side player, so `follow` / `come` / `goto` are not teleports —
the bot walks, jumps, swims, climbs, bridges, and (if you let it) digs its way there,
paying honest survival costs the whole way. If it can't reach a goal it says so rather
than cheating its way over.

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
- **`/bot debug on|off`** — toggle verbose per-tick planner logging (skeleton dumps,
  window swaps, block-plan summaries) for watching the two-tier driver work in real time.

If you're filing a bug about the bot taking a strange route, a `/bot trace` or `/bot
rtrace` dump is worth a thousand words — it's the difference between "the bot went the
wrong way" and "the bot priced the two-block dig at 1736 because of X."
