# HANDOFF (s45, 2026-07-03) — resume pointer

> Tracked copy of the next-session pointer (root `HANDOFF.md` is gitignored, so this lives here to survive
> a push — resume from GitHub on any machine). Weekend handoff: repo hygiene + the find→mine-resources arc,
> **phases 1–5 of 6 done, committed on `core`, merged to both eras, and PUSHED.**

## Where to pick up: Phase 6 — `/bot gather`

The resource **find** pipeline is fully built and in-game-testable; the **gather** loop is the one
remaining piece and the milestone payoff. Full spec: `internal_docs/DESIGN-find-mine-resources.md` §7.

**FIRST, before writing phase 6 — in-game sanity check the data plane (phases 4–5):**
- Launch a world (mc-1.21 era, JDK 21: `Set active project to 1.21.4` → `:1.21.4:runClient`; or 26.x on
  `main`, JDK 25: `:26.2:runClient`), spawn the bot, stand near known ore, run **`/bot find diamond`**
  (also `iron`, `andesite`). Confirm the reported region coords + approx counts match reality. This
  validates classify-tally → pyramid → roll-up → query end to end. If counts look wrong, debug that
  before building gather on top.

**Then implement Phase 6** (design §7): a new `Mode.GATHER` on `AllyBotEntity` (enum is currently
`FOLLOW/STAY/COME`) driving a hand-coded phase machine — the agency/`tasks` layer is stubs, so it's
hand-wired, reusing the existing reactive executors:

| Phase | Uses | Advance when |
|---|---|---|
| FIND | `ResourceQuery.find(level, column, bot.blockPosition(), minCount, maxResults)` | got a target → PATH; none → chat + STAY |
| PATH | `driveToward(center, floor)` (returns true on arrival; poll `pathPlan.status()` for BLOCKED/FAILED) | arrived → SCAN; FAILED → blacklist region, back to FIND |
| SCAN | on arrival, scan the target 16³ section (`getBlockState`) for exact `column` block positions, nearest-first | queued → MINE |
| MINE | `mining.request(cell)` EVERY tick (BotMining is reactive, no done-event); poll `getBlockState(cell).isAir()`; on air, poll inventory | quota met → RETURN; section exhausted → FIND |
| RETURN | `driveToward(gatherStartPos)` — the cell where `/bot gather` was issued | arrived → DONE (→ STAY) |

- New `GatherCommand` (`/bot gather <resource> [count]`) via the `BotCommand` strategy — one line in
  `OrebitCommands.COMMANDS`; parse resource via `ResourceClasses.columnForName`; call a new
  `bot.startGather(column, quota)`.
- **Quota = items in inventory** (owner-ratified) — poll `BotInventory` count of the resource item after
  each break (ore drops the item; auto-pickup is real — see the `agency-inventory-is-real` memory), NOT
  blocks-mined.
- Execution seams are all mapped: `AllyBotEntity` mode enum + `tick()` case, `driveToward` arrival
  polling, `mining.request()/tick()`, `BotCommand`/`OrebitCommands` registration.

## What shipped this session (all pushed)

Doc hygiene: `internal_docs` triaged (deleted the refuted edit-bbox-gate doc; condensed HPA-IMPLEMENTATION /
warmup / profile / cuboid-options; drift fixes incl. the **portal-route bit-44 conflict** flagged with a
verified fix — relocate `PORTAL_KIND` to bits 45–46). Design ratified in
`internal_docs/DESIGN-find-mine-resources.md`.

Find→mine phases 1–5 (`worldmodel/resource/` is the new package):
1. Deleted the vestigial semantic `Region` tree + dead `NavSection.candidateRegions`.
2. `ResourceClasses` (64-id registry + 23 indexed columns; **ore classes include deepslate/nether variants**
   so underground ore is visible) + `Log2Codec` (log₂ histogram math; 0 is the additive identity).
3. Sparse `ResourcePyramid` (SoA clone of `CostPyramid`, 23 log₂ bytes/row) + `ResourceMerger` roll-up;
   `RegionGrid.resourcePyramid()`.
4. Tally rides `classifyNavtypes` behind an `anyResource` gate (hot 4096-loop untouched) → `NavSection`
   → written to level-0 rows + rolled up at chunk load.
5. `ResourceQuery` (ascend-to-ancestor, then nearest-first best-first descent, `minCount` filter) +
   `/bot find <resource> [minCount]` diagnostic command.

Unit-tested throughout (Log2Codec / ResourceClasses / ResourcePyramid / ResourceMerger / ResourceTally /
ResourceQuery, all green on mc-1.21); both eras compile (mc-1.21 all versions + 26.x).

## Known limitations / deferred (milestone-1 scope — NOT bugs)

- **Block-change re-tally deferred** (design §8.5): the resource pyramid is **load-populated only**. Mid-
  session mining/building doesn't update it until the chunk reloads. The gather loop tolerates this via its
  on-arrival SCAN. TODO marker is in `HpaMaintenance.onBlockChanged`.
- **`minCount` prunes at every level** (design §6): a resource spread thin (each child section below
  `minCount`) yields no hit even if the ancestor aggregate qualifies. Fine for the phase-6 default
  (`minCount=1` = "find any"); reconsider for large thresholds — may want threshold only at the
  ancestor-qualify step, ranking (not pruning) on descent.
- **Persistence = milestone 2** (§8.1): resource memory doesn't survive restart (needs the disk layer,
  shared with nav's still-stubbed `HpaPersistence`).
- **Unloaded-chunk prospecting = later arc** (§8.2): `/bot find` sees only loaded/known regions, bounded to
  the anchor's `MAX_COARSE_LEVEL` region (~1024 blocks/axis). "None found" = stop + report.

## Also queued (owner's 3-feature "usable bot" milestone)

After gather: **persist inventory across restart** (SavedData/NBT keyed by owner UUID) and **drop /
deposit-to-chest** (drop = trivial; chest = go-to-container + transfer). Both smaller than find→mine.

## Build reminders

- **mc-1.21 era needs JDK 21** (default JAVA_HOME is 25 → set it, or `chiseledCompileCommon` fails).
  **26.x era needs JDK 25.**
- **`core` is NOT buildable** (no `era.properties`). Author common logic on `core`, then `git merge core`
  into `mc-1.21` / `main` to build/run. Merges are conflict-free (core never touches era-owned files).
- `stonecutter.gradle.kts` (the `active "…"` marker) is era-owned build churn — don't commit its flips.

## Commit trail (this session, on `core` → merged to both eras)

`daec75f` doc triage · `8c903fa` design doc · `b8c0716` design decisions · `b98db9f` phase 1 ·
`d86b05a` phase 2 · `2afcfad` phase 3 · `4d9072a` phase 4 · `37b6afb` phase 5 · (+ this handoff).
