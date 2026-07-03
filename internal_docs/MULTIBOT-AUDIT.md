# MULTI-BOT READINESS AUDIT (V2 vision groundwork)

> **STATUS (2026-07-03): findings record, no work planned.** Context: V1 = one LLM-driven bot per
> player (mine/fight/build). V2 vision (owner, 2026-07-03, chat — first written record): replace
> vanilla villagers with "smart villagers" that mine their surroundings and build houses, and/or a
> Dwarf-Fortress-style colony sim assigning jobs to many bots. `/bot spawn` (added same day) is a
> respawn command today but is the natural seam for spawning ADDITIONAL bots later.
> If the background-pathfinding arc (`DESIGN-background-pathfinding.md`) lands first, re-check the
> "sequential on the tick thread" assumptions below against the planner-thread model.

## Verdict

**The engine is multi-bot-clean; the product shell is not.** Pathfinding, follower/steering,
mining, inventory, and the whole world model are per-instance or level-keyed. What breaks is
identity plumbing (one-bot-per-owner map, owner-keyed commands) and the global config — including
one genuinely awkward interaction: per-owner `mining.protectedBlocks` is baked into the GLOBAL
NavBlock descriptor table.

## Already safe (verified per-instance / level-keyed / bot-agnostic)

- `AllyBotEntity` state (owner ref, plan, follower counters, portal state), `BotMining`,
  `FakePlayerEntity`/`FakeNetworkHandler`, `BotPositioning` — all per-instance.
- `PathPlan`/`HierarchicalRegionPlan`/`RegionEdgeBlacklist` — per-PathPlan instances.
- `BlockPathfinder`/`RegionPathfinder` ThreadLocal scratch — shared across bots but searches are
  sequential on the tick thread, so reuse is safe (re-audit if searches move off-thread).
- World model: `NavStore`, `ChunkNavLoader`, `NavGridUpdater`, `HpaMaintenance`, `RegionGrid`,
  `NetherPortalIndex` — all `ServerLevel`-keyed, no bot identity anywhere.
- `BlockPathfinder.LAST_EXPANSIONS`/`LAST_WAS_PARTIAL` statics — write-per-search on one thread;
  fine today (already flagged for the async arc regardless).
- Bot GameProfile UUID is `UUID.randomUUID()` (BotManager.java:27) — no collision risk for extra
  bots; only the NAME (`{owner}_bot`) would collide.

## Breaks with >1 bot (ranked)

1. **`BotManager.botsByOwner`** (BotManager.java:16) — `Map<UUID owner, AllyBotEntity>`; a second
   spawn for the same owner silently overwrites the map entry, orphaning the first bot (it keeps
   ticking in the world but is unreachable and — worse — `onPlayerDisconnect` →
   `removeBotFor` removes only the mapped one, leaving a ghost PlayerList member).
   V2 shape: owner → (botId → bot), plus remove-ALL-on-disconnect.
2. **Command targeting** (OrebitCommands.act, OrebitCommands.java:59) — every subcommand resolves
   `BotManager.botFor(player)`: exactly one bot per owner, no selector. V2 needs a selector
   argument (or named bots) with a "sole bot" default for V1 ergonomics; `/bot list` becomes
   necessary alongside.
3. **Global config** (ConfigLoader.java:54/86) — one static `Config`/`BotCaps` for the whole
   server. Two owners with different survival/mining/pathing settings can't coexist; last reload
   wins. V2: per-owner config scope (the `Config` record itself is immutable and already threads
   cleanly — the scoping is a loader/lookup change).
4. **`mining.protectedBlocks` is baked GLOBAL** (NavBlock.applyProtected, NavBlock.java:427) — the
   PROTECTED descriptor bit splits navtypes in the one shared NavBlock table, and the nav grids
   built from it are shared per level. This is *architecturally* server-global: per-owner protected
   lists would need either per-caps PROTECTED interpretation at read time (hot-path cost — needs
   its own design) or acceptance that protection is a server policy, not an owner preference.
   **Decide the semantics before any V2 work; "server policy" is the cheap and probably right
   answer.**
5. **`Debug.ENABLED` / TRACE flags** — global toggles; one owner's `/bot debug on` chats every
   bot's diagnostics. Cosmetic, fix late.
6. **Bot naming** — `{owner}_bot` collides for additional bots; needs an index/role suffix.

## Death/respawn lifecycle (as of `/bot spawn`, 2026-07-03)

Nothing respawns a dead bot automatically (vanilla respawn is client-driven; the bot has no
client). Death leaves the corpse entity + the stale `botsByOwner` entry. Recovery paths, both the
same mechanics: disconnect+rejoin, or `/bot spawn` (refuses while alive; otherwise
`removeBotFor` — `PlayerList.remove` works on dead members, the routine disconnect-on-death-screen
path — then `spawnBotFor` fresh; inventory lost per vanilla drops). A death *listener* (auto-notify
or auto-respawn policy) is V2 colony-sim material.
