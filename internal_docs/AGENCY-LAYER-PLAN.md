# Agency Layer — the (1a)–(1d) arc (CONDENSED — arc COMPLETE; full text in git history pre-s52)

**Status: SHIPPED (s29+).** Code Javadocs cite this doc by rung name ("Capability config", "Tool use",
"Tick costs") alongside PRD §10 Phase 1a–1d.

**Rung map (what each rung was, where it landed):**
- **(1a) Capability config** → `src/main/java/com/orebit/mod/config/` — `Config` (immutable record),
  `ConfigLoader` (`config/orebit.properties`, commented defaults on first run), `ConfigValidator`
  (clamp-and-warn), `ConfigKeys`, `Config.toBotCaps()`; surfaced via `/bot config`.
- **(1b) Inventory** → the bot's REAL vanilla `ServerPlayer` inventory (auto-pickup works; see the
  `agency-inventory-is-real` memory); read through `platform/BotInventory.java`.
- **(1c) Tool use + placement from inventory** → `pathfinding/blockpathfinder/MiningModel.java`
  (tool selection, per-navtype mining-tick table + per-search `Snapshot`), `BotMining.java` (timed
  survival breaking executor), `platform/WorldEdits.java` (placement).
- **(1d) Tick costs** — the payoff: all movement/break/place costs priced in real game ticks
  (`physically-derived-costs` memory); `MiningModel` baked at boot in `OrebitCommon.init`.

**Remaining from the doc's "after arc (1)" list:** the ai/tasks layer (GoalDispatcher, TaskExecutor…)
is still Javadoc-only stubs; `/bot gather` is hand-wired on `AllyBotEntity` instead.
