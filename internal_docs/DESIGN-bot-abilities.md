# DESIGN — Bot Abilities Arc (Crafting / Farming / Fighting / Building)

**Status: all four abilities SHIPPED and adopted on every branch (2026-07-29).** This file is KEPT, not
as an open arc, but as the **live architecture contract for ability components** — §2 (the component
template, the fighting-interrupt pattern, and the multi-bot / work-tree readiness rules) governs every
ability written from here on, and §1's work-tree planner ("build a house" → gather wood → craft pickaxe
→ …) is still a FUTURE arc that composes these four. Per-feature mechanism detail (the vanilla
constants, the litematic bit format, the version-churn boundaries) is duplicated in the code Javadocs
that cite this doc by §-anchor; read those first — this is the map, not the territory.

## §1 Scope, order, and why this order

Four abilities, implemented strictly in this order:

1. **Crafting** — `/bot craft <item> [count]`: craft any standard (shaped/shapeless) recipe from
   the bot's real inventory; 2x2 recipes anywhere, 3x3 recipes within reach of a crafting table,
   optionally placing (and reclaiming) a temporary table.
2. **Farming** — `/bot farm`: till near water, plant, harvest mature crops, replant.
3. **Fighting** — self-defense against hostile mobs with per-mob-type strategies. Not a mode
   (§2.3).
4. **Building** — `/bot build <schematic> <x y z>`: execute a Litematica schematic from inventory.

The order is the owner's requested order, independently confirmed by prior art (Voyager's
curriculum, MineDojo's tech-tree task family, Mindcraft's skill set all treat crafting as the
dependency spine: farming needs a hoe, fighting needs weapons, survival building needs sourced
blocks). It also builds shared machinery incrementally: crafting introduces per-item inventory
verbs; farming introduces the use-item-on-block seam; fighting introduces entity combat + the
interrupt layer; building composes placement + inventory + navigation at scale. The **work-tree
planner** ("build a house" → gather wood → craft pickaxe → …) is a LATER arc that composes these
four; nothing here may hardcode assumptions that block it (§2.4).

## §2 Component architecture (ratified)

### §2.1 The component template

Each ability is a component following the **BotGatherer template** exactly (the live, proven
task-machine shape — see BotGatherer.java):

- package-private `final class Bot<X>` in `com.orebit.mod` (root package), constructed once in the
  `AllyBotEntity` constructor, stored in a final field. Long-lived; never per-command.
- a private phase enum, `null` = inactive; `start<X>(…)` is the ONLY full-state reset; a per-tick
  `<x>LoopTick()` that is a pure switch-dispatch to one private method per phase.
- every transition is **state/event-based** — no tick-counter timers, no thresholds-as-recovery
  (the No-Arbitrary-Timers rule). Counters may exist only as give-up backstops with derived bounds.
- every terminal path = one `bot.chat(...)` line + `bot.setMode(Mode.STAY)`.
- ALL movement through `bot.navigator().driveToward(...)` + the `navGaveUp()`/`clearNavGaveUp()`/
  `clearPlan()` protocol. Components never touch paths, movements, or steering directly.
- ALL breaking through `bot.mining().request(pos[, cond])`. Mining actuates AFTER mode dispatch in
  the tick, so completion milestones ("target became air") are observed at the TOP of the
  component's next tick and MUST be consumed before re-selection (the gatherMine ordering rule).
- read-only observation seams (package-private pure getters + AllyBotEntity delegates) for the
  headless harness, mirroring `gatherPhaseName()`/`gatheredCount()`.

Wiring per ability: +1 `Mode` constant, +1 final field/ctor line, +1 tick-switch case, +1
`start<X>()` mode-entry method (the standard reset ritual: set mode, clear stale mode data,
`navigator.clearPlan()`, `portalFollower.resetPortalSeek()`, then component init), +1 command
class, +1 line in `OrebitCommands.COMMANDS`.

### §2.2 Relationship to the Phase-7 stub layer

The stub `tasks/`/`ai/` architecture (TaskExecutor, AIStateMachine, StateStack) is a FUTURE
formalization (PRD Phase 7) that will sit ABOVE these components and eventually retire the
imperative mode dispatch. These components are the ability/actuator stratum that layer is designed
to drive (its "CraftingState / mining, walking, crafting, building" InterruptibleStates). We do
not build any of the stub layer now; we keep the components adoptable by it later: explicit
start/tick/terminal lifecycle, resumable internal state, and NO goal-arbitration inside a
component (arbitration is the future AIPrioritySystem's job; today it is the mode switch + §2.3).

### §2.3 Fighting is an interrupt, not a mode

Self-defense must work during every mode, so `BotFighter` is NOT a `Mode`. It follows the
`BotPortalFollower.followThroughPortal()` precedent: a pre-dispatch check in `tick()` that, when
engaged, CONSUMES the tick (returns true → the mode switch is skipped that tick). Because
components keep their state while un-ticked (the existing, ratified behavior — gatherer state
survives mode switches), this yields preempt-and-resume for free: combat ends → the interrupted
mode's machine resumes exactly where it was. This is the live analogue of the stub StateStack's
push/pop contract without building the stack. Mindcraft's ordered-interrupt "modes" layer is the
proven prior art for this shape.

### §2.4 Multi-bot and work-tree readiness

Commands stay single-bot through `OrebitCommands.act` (owner-keyed `BotManager.botFor`); ALL
per-task state lives on the component (never static). Components expose their terminal outcome
(succeeded / failed+reason) via observation seams so the future work-tree planner can sequence
them; `start<X>()` entry methods are the seams it will call.

## §3 Crafting (ratified design)

### §3.1 Vanilla recipes are the execution-layer truth

The stub `requirements/CraftingRecipe` spec ("human-authored logical recipes, not parsed from
game data") governs the future PLANNING layer (work trees). This arc is the EXECUTION layer:
"craft item X now from ingredients already in inventory" — and the owner's requirement is *any*
item, which only the server's own `RecipeManager` can provide. So: execution reads vanilla
recipes; the later planner may still be hand-authored per its spec. Only **shaped and shapeless
crafting recipes** are supported; special/dynamic recipes (fireworks, dyeing, map ops) are
excluded by design (they have no static ingredient list; vanilla's own machinery marks them
NOT_PLACEABLE).

### §3.2 The version seam: platform/CraftingOps

Anchor: `MinecraftServer#getRecipeManager()` — javap-verified byte-stable 1.17.1 → 26.2 (never
`Level#getRecipeManager`, which became `recipeAccess()` at 1.21.2). One overlay adapter
`platform/CraftingOps` with flavors at the verified churn boundaries — expected: **1.17**
baseline (1.18.x identical), **1.19.4** (`assemble(+RegistryAccess)`), **1.20**
(`TransientCraftingContainer`), **1.20.2** (`RecipeHolder`), **1.20.5** (`HolderLookup.Provider`),
**1.21** (`CraftingInput`), **1.21.2** (recipe rework: enumeration via `getRecipes()`,
`CraftingRecipe#getRemainingItems`), **26** (`assemble(T)` one-arg). Exact dirs pinned empirically
by `chiseledCompileCommon`. Headless-craft precedent: vanilla's Crafter block and Carpet's
autoCraftingTable both query `getRecipeFor` + `assemble` against a synthetic grid with no player,
no menu, no recipe book — we do the same. No menu/window simulation, ever.

Adapter surface (signatures identical across flavors; core-facing types are version-stable):

- `List<KnownRecipe> listCrafting(MinecraftServer server)` — walks the crafting recipes,
  keeps shaped+shapeless, builds `KnownRecipe`s (opaque vanilla handle inside).
- `boolean matches(ServerLevel level, Object handle, List<ItemStack> grid, int w, int h)`
- `ItemStack assemble(ServerLevel level, Object handle, List<ItemStack> grid, int w, int h)`
- `List<ItemStack> remainders(ServerLevel level, Object handle, List<ItemStack> grid, int w, int h)`

### §3.3 Core model: crafting/ package

- **`KnownRecipe`** — smart object: recipe id string, result item id + count, shaped w×h grid of
  `IngredientSlot`s (row-major, empties null) or shapeless list, `fits2x2()` (shaped w≤2∧h≤2;
  shapeless ≤4 ingredients), opaque vanilla handle. Owns `planFrom(Inventory) → CraftAssignment`
  (deterministic greedy slot assignment, §3.4). Named to avoid colliding with the stub
  `requirements/CraftingRecipe` (avoid-repeated-names principle).
- **`IngredientSlot`** — wraps the vanilla `Ingredient` as a `Predicate<ItemStack>`.
- **`RecipeIndex`** — static, baked at SERVER_STARTED (the `MiningModel.buildTable` pattern;
  recipes are datapack-loaded, so they do NOT exist at Bootstrap/static-init) and re-baked on
  `/bot config reload` after the planner drain. Result-item → `List<KnownRecipe>` map + the
  friendly-name suggestion list for the command (item id path, `word()`-safe).
- **`CraftAssignment`** — the planned mapping grid-cell → inventory slot; knows how to execute:
  verify `matches`, `assemble`, decrement the chosen stacks, insert result + remainders into the
  real inventory (overflow → drop at feet).

### §3.4 Deterministic selection rules

Recipe choice for `/bot craft <item>`: among recipes producing the item — (1) feasible from
current inventory beats infeasible; (2) among feasible, `fits2x2()` beats table-requiring (skips
the table trip); (3) lexicographic recipe id as the final tiebreak. Ingredient assignment: scan
inventory slots in index order, first matching stack with remaining budget feeds the grid cell
(count-aware; one stack may feed several cells). No RNG anywhere.

### §3.5 BotCrafter machine

`Mode.CRAFT`; phases `PLAN → (SEEK_TABLE → [PLACE_TABLE]) → CRAFT → [RECLAIM] → done(STAY)`.

- **PLAN**: resolve recipes via `RecipeIndex`; count = requested RESULT items (gather-quota
  semantics, crafts `ceil(count/resultCount)` ops). Infeasible → chat the missing ingredients
  (per-ingredient first-missing summary) + STAY. Feasible 2x2 → CRAFT. Feasible 3x3-only →
  SEEK_TABLE. If the chosen recipe needs a table, none is reachable, and none is carried, but
  `crafting_table` is itself 2x2-craftable from inventory → prepend that craft (one level deep,
  the owner's "conditionally place-then-break a crafting table" note).
- **SEEK_TABLE**: locate the nearest crafting table within `crafting.tableSearchRadius` (resource
  layer if `crafting_table` is a locatable column, else a bounded nearby-section scan);
  `driveToward` until the table is within reach (4.5 eye-to-center, the mining reach). Found none
  + carrying/crafted one + `crafting.placeTable` → PLACE_TABLE (adjacent placeable cell via the
  existing place machinery). Unreachable → `navGaveUp` protocol → chat + STAY.
- **CRAFT**: one craft operation per tick (swing + inventory mutation — vanilla crafting is
  instantaneous; one-op-per-tick is a cadence, not a timer). Each op re-plans the assignment
  against the LIVE inventory (milestone = the inventory mutation itself). Quota met or
  ingredients exhausted → chat result → RECLAIM or STAY.
- **RECLAIM** (only when the bot placed the table this run and `crafting.reclaimTable`): break
  the exact placed pos back into inventory. The bot-placed table is exempted from the
  `mining.protectedBlocks` refusal for THAT position only, verified still a crafting table
  (§10-D3).

### §3.6 Config + command

`crafting.*` namespace: `crafting.placeTable` (bool, default true), `crafting.reclaimTable`
(bool, default true), `crafting.tableSearchRadius` (blocks, default 16, clamp 0..64 — Mindcraft's
proven radius). Command `/bot craft <item> [count]` — GatherCommand's exact Brigadier shape
(`StringArgumentType.word()` + `SharedSuggestionProvider.suggest` over `RecipeIndex` names; the
registration seam carries no `CommandBuildContext`, so vanilla `ItemArgument` is structurally
unavailable).

### §3.7 New inventory verbs

`BotInventory` gains per-Item verbs (missing today — current API is Block/predicate-typed):
`countOf(Item)`, `removeOne(slot)`, `give(ItemStack)` (vanilla `Inventory#add`, overflow → drop).
Version-stable (lives in `src/`, like the rest of BotInventory).

## §4 Farming (summary; detail ratified at feature start)

- `platform/ItemUse` overlay seam: `ItemStack#useOn(UseOnContext)` with an UP-face
  `BlockHitResult` — the exact server-guarded path `ServerPlayerGameMode.useItemOn` runs; handles
  tilling (HoeItem TILLABLES: grass/dirt/dirt path → farmland, needs air above), planting
  (ItemNameBlockItem → BlockItem.place, checks farmland + light≥8), durability, sounds, game
  events for ALL crop types generically. Ctor drift (UseOnContext/InteractionResult) is why it's
  an overlay.
- `BotFarmer` (`Mode.FARM`): harvest mature crops (age-property table: wheat/carrot/potato 7,
  beetroot 3, nether wart 3, cocoa 2), replant from drops, till+plant new farmland near water.
  Hydration = vanilla's exact rule: any water fluid state in the 9×9×2 box (Chebyshev r=4, same Y
  and Y+1). Per-crop facts as Strategy classes (abstract-classes-over-enums).
- Trample rule: farmland converts on `fallDistance > 0.5` landings — the farmer must never jump
  on farmland (walking is always safe).
- Hazard fix that lands with this feature: `consumeOnePlaceable`'s softest-first selection would
  treat carried seeds (hardness-0 BlockItems) as prime bridging material — crop/seed items are
  excluded from placeable selection.

## §5 Fighting (summary; detail ratified at feature start)

- `BotFighter`, pre-dispatch interrupt (§2.3). Target model: hostiles targeting the bot
  (`Mob#getTarget() == bot` — the bot is a first-class mob target when `survival.takesDamage=true`;
  `abilities.invulnerable` exempts it from `canBeSeenAsEnemy`, so under the invulnerable default
  mobs won't aggro and combat is quiescent) plus hostiles within an engage radius.
- Attack machinery: inherited `Player#attack(Entity)` + `swing(MAIN_HAND, true)` — the method is
  fully self-contained server-side (charge/crit/sweep/knockback/enchants) but has NO reach or
  cooldown gate, so the bot self-enforces ≤3.0-block reach and a cadence derived from the held
  weapon's ATTACK_SPEED attribute (sword 1.6/s → 20/1.6 = 12.5 → attack on tick 13; derived, not
  tuned). Weapon selection by attack damage (new BotInventory ranking — today's ranking is
  destroy-speed and would equip a pickaxe).
- Per-mob strategies as one-class-per-archetype behind a common abstract type (Strategy pattern;
  no mob-type enum switch). Seeds, all source-verified: **Creeper** — never path within 3.0
  (swell trigger distSq<9), engage with sprint+full-charge knockback hits at ~2.5-2.9, disengage
  past 7 or break LOS after each hit, and remember the fuse DECAYS (1/tick) rather than resetting.
  **Skeleton** — strafe perpendicular (arrows lead nothing), close during the 40-tick reload
  windows, 3s of broken LOS cancels its draw. **Melee default** (zombie/spider/drowned) — face,
  kite, full-charge hits.

## §6 Building (summary; detail ratified at feature start)

- `.litematic` parser in core: gzip NBT; per region min-corner = per-axis
  `min(pos, pos + (size>=0 ? size-1 : size+1))`, dims = abs(size); palette list of
  {Name, Properties}; `bits = max(2, ceil(log2(paletteSize)))`, entries LSB-first and SPANNING
  long boundaries (LitematicaBitArray semantics — vanilla SimpleBitStorage pads and would
  mis-decode); `index = x + z*dimX + y*dimX*dimZ`. Palette → BlockState behind a platform seam.
  Sponge `.schem` v2 as a cheap fallback if it stays cheap.
- `BotBuilder` (`Mode.BUILD`): diff-schematic-vs-world reactive loop (idempotent/resumable),
  bottom-up support-aware ordering, direct exact-state placement
  (`setBlock(pos, state, UPDATE_CLIENTS|UPDATE_KNOWN_SHAPE)` — the litematica-paste precedent)
  while charging survival costs (consume the matching BlockItem from the real inventory, reach
  gating, swing). This deletes Baritone's entire orientation-failure class (its builder must
  simulate player-rotation-dependent `getStateForPlacement`). Diff ignores self-healing
  connection properties (stair SHAPE, fence/pane/wall sides — they converge via `updateShape`);
  multi-block structures deduped to their root cell (door lower half, bed foot). Missing
  materials → chat report (feeds the future work-tree planner); permanently unplaceable cells →
  explicit terminal state, never a retry timer.
- Schematic files from `<world>/orebit/schematics/` (ConfigDir pattern); `/bot build <name>
  <x y z>`, name tab-completed from the directory.

## §7 Config namespaces

One namespace per ability (`crafting.*` §3.6, `farming.*`, `combat.*`, `building.*`), each
following the 5-touchpoint recipe (ConfigKeys constants, positional Config record fields +
DEFAULT, ConfigValidator positional parse, ConfigLoader.writeDefault section, CONFIG.md +
docs/configuration.md). Executor-read knobs need no bake/drain; anything feeding a shared
planner-side table re-bakes in `ConfigLoader.install()` AFTER `PlanExecutor.drainIdle`.

## §8 Testing strategy

- **Pure-JVM unit tests on core** (`src/test`): ingredient-assignment planning (synthetic
  predicates), per-crop tables, litematic bit-array/parser round-trips (fixture files),
  combat-cadence math. Bootstrap-tier (`Bootstrap.bootStrap()`) where real
  Items/Blocks/BlockStates are needed. NOTE: vanilla RECIPES are datapack-loaded per-server, NOT
  available from Bootstrap — recipe-dependent logic is tested against synthetic `KnownRecipe`s in
  unit tests and against the real RecipeManager only in the headless autotest.
- **Headless end-to-end** (mc-1.21 era): new `HeadlessAutotest` modes per ability — CRAFT (inject
  ingredients via the existing `setItem` seam → `startCraft` → `countInInventory` assertion; place
  a table programmatically for the 3x3 leg), FARM (programmatic superflat plot: pre-grown crop
  states for determinism — growth is random-tick), FIGHT (programmatic mob spawns + difficulty
  pin; bot death is already a first-class FAIL), BUILD (superflat + block-state region assert).
  HeadlessAutotest modes are ERA-OWNED (the branch-scoping lesson) — authored on the mc-1.21
  branch, not core.
- **Determinism pins** as today: `pathing.async=false` in harness config, programmatic terrain or
  frozen master worlds, never seed-regen.

## §9 Branch mechanics for this arc

*(Removed 2026-08-07 — this section described a TEMPORARY mc-1.21 worktree conflict and a throwaway
`mc121-abilities-verify` side branch, both resolved when the arc was adopted on every branch on
2026-07-29. The standing rule is just the CLAUDE.md model: common code + overlays + unit tests on
**core**, `git merge core` into each era branch; era-owned harness additions on the era branch.)*

## §10 Owner decision points (flagged, implemented with stated defaults)

- **D1** Vanilla RecipeManager as execution truth (§3.1) — the stub's hand-authored-recipes rule
  is interpreted as planning-layer-only. Default: implemented as designed.
- **D2** Fighting as pre-dispatch interrupt, not a Mode (§2.3). Default: implemented as designed.
- **D3** ~~Reclaim waiver~~ SUPERSEDED by the owner's 2026-07-29 ruling: `mining.protectedBlocks`
  is a PATHING policy — it gates the planner and the route executors (`applyEdits`/`place`, the
  gather occluder dig, builder clears), never the deliberate hands (`BotMining`). Gather targets,
  harvests, reclaims, and `/bot mine` proceed regardless of the protected list (protecting logs
  must not refuse `/bot gather wood`); the hands' only refusal is vanilla-unbreakables without
  `mining.allowUnbreakable`. The waiver machinery this decision originally introduced was removed.
- **D4** Building places EXACT palette states directly (survival costs charged) instead of
  simulating survival placement (§6). A strict survival-placement mode is possible later.
- **D5** Special/dynamic recipes excluded from crafting (§3.1).
