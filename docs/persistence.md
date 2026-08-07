# Saved Data

Orebit remembers the world its bots explore. Not the fine, per-block detail near a bot — that's
cheap to recompute from the live world every time a chunk loads, so it's never saved — but the
**coarse map**: the shape of terrain far from any bot (for long-range routing) and where resources
were seen (for [`/bot report` and `/bot find`](gathering.md)). Both are tallied from every chunk that
loads, so the map covers ground your players have walked as well as ground a bot has. That coarse map
is written into your world folder so it survives a restart. On a server that stops when idle and restarts often,
this is what lets a bot still know the lay of land it visited hours ago without re-exploring it.

This page is for server admins: **what** Orebit writes, **where**, and what you can safely do with it.

## Where the files live

The saved map goes under a single `orebit/` folder inside your world save, one subfolder
per dimension:

```
<world>/
└── orebit/
    ├── minecraft_overworld/
    │   ├── hpa.coarse.bin      ← per-dimension summary (routing)
    │   ├── res.coarse.bin      ← per-dimension summary (resources)
    │   ├── hpa.3.-1.bin        ← one routing shard per 32×32-chunk area
    │   ├── hpa.4.-1.bin
    │   ├── res.3.-1.bin        ← one resource shard per 32×32-chunk area
    │   └── …
    ├── minecraft_the_nether/
    └── minecraft_the_end/
```

The map is **sharded** the same way Minecraft shards its own world data. A `.mca` region file covers a
32×32-chunk area; each `hpa.<X>.<Z>.bin` covers exactly that same area (the numbers are the region
coordinates — `chunkX >> 5`, `chunkZ >> 5`), so an area you've never been near has no shard file at
all. `hpa.*` files hold routing cost; `res.*` files hold the resource tallies behind the abundance
compass. The routing files also carry the bot's **learned dead ends** — region crossings it tried and
proved impossible for its capabilities — so a restarted bot doesn't have to walk back into a dead end
to rediscover it. The two `*.coarse.bin` files are a small per-dimension summary of the whole explored world at
the coarsest zoom — enough to plan a rough long-distance route the instant the server starts, before
any detailed shard has loaded.

That folder is the whole of the saved map, but it isn't quite everything Orebit writes. One tiny
sidecar sits beside it at `<world>/orebit-bots.properties` — a `<botUuid>=<owner name>` line per bot
this world has seen, so a respawn can re-adopt the world's existing bot when the owner's offline UUID
changes (a rename, or a dev relaunch) instead of deriving a fresh one. It is *not* part of the cache
described below: deleting it costs only that adoption fallback, but in the case it exists to cover
that means a bot starting over with an empty inventory. The bot's inventory itself isn't Orebit data
at all — it lives in vanilla player data under the bot's UUID, exactly like any player's.

## It's a cache — you can delete it

**These files are safe to delete.** They are a rebuildable cache, not authoritative save data: Orebit
treats a missing, empty, or corrupt file exactly the same as "never explored here," and rebuilds it
from the live world as chunks load. Deleting the whole `orebit/` folder just makes the bot re-learn
the terrain as it travels — it costs a little re-exploration, never a broken world. Nothing in your
actual Minecraft save depends on it.

This also means an Orebit version upgrade never needs a migration step: if the on-disk format changed,
the old files are simply ignored as a cache miss and rewritten in the new format as you play.

## When it's saved

- **On a clean shutdown** — the complete, authoritative save. Whenever the server stops normally, every
  explored dimension is fully written before it exits — all of its shards, not just the ones that
  changed since the last save.
- **Periodically, as crash insurance** — every `hpa.persistIntervalTicks` (default ≈ 5 minutes) the bot
  re-saves any dimension that changed since its last save, so a hard crash loses at most a few minutes of
  exploration. This background save is spread out over several ticks under a small time budget
  (`hpa.persistFlushBudgetMs`) rather than written all at once, so it never causes a visible server
  stall. Set `hpa.persistIntervalTicks = 0` to turn the periodic save off entirely (the clean-shutdown
  save still runs).

Both are covered under [World memory in the configuration reference](configuration.md#world-memory--surviving-a-restart).

## How much disk it uses

Not much — on the order of **a few percent of the size of your vanilla region files** for the same area.
A single 32×32-chunk shard is tens of kilobytes even for dense cave terrain, against multiple megabytes
for the vanilla `.mca` region covering the same ground. Only ground that has actually been *loaded*
gets a shard — the map is built from chunks as they load, whoever loaded them — so the footprint grows
with where you and your bots have been, not with world size.

## Keeping memory bounded (optional)

By default the entire saved map is loaded into RAM when the server starts. For most worlds that's fine.
On a very long-lived world with an enormous explored area, you can instead have Orebit keep only the
per-dimension summary resident and **stream the detailed shards in and out on demand**:

- `hpa.lazyLoad = true` loads only the two `*.coarse.bin` summaries at start (so long-range planning
  works immediately) and pages each shard back off disk on demand, the moment the planner reaches into
  the area it covers. Requires a server restart to change.
- `hpa.residentLeafCap` then caps how much detail stays in memory: once the resident count exceeds it,
  the coldest regions (those whose chunks are all currently unloaded) are written back to disk and
  reloaded only if a bot returns. The summary always stays resident, so the bot can still route across
  regions it has paged out.

This mode is newer and **off by default** while it proves out; turn it on if bot world-memory RAM is a
concern. See the [configuration reference](configuration.md#world-memory--surviving-a-restart) for the
exact knobs, and [Persisting the Map](Optimizations/13_persisting_the_map.md) for the engineering behind
the format.
