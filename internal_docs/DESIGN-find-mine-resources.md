# DESIGN — Find → Mine Resources (the "ask for iron, come back with iron" milestone)

> **STATUS (2026-07-03): DESIGN — owner review before implementation.** Drafted after an audit that
> found the resource layer ~40% built (a working log₂ histogram + 64-class registry) but 0% wired and
> bolted to the deleted semantic `Region` tree. This doc specifies the rehost onto the fixed-grid octree,
> the tally-on-classify hook, the drill-down query, and the `/bot gather` task loop. See the
> `resource-layer-design` memory and PRD §6.4/§6.6.

## 0. The bar

One usable capability: **`/bot gather diamond 5`** → the bot consults a resource octree for where the
resource is, paths there via the existing two-tier nav, mines it in survival (real drops, picked up into
its real inventory), repeats until the quota is met, then returns to the owner. "Ask for iron, come back
with iron." Everything below is the minimum coherent machinery for that, reusing the nav data plane.

Non-goals for this milestone (deferred, §8): cross-restart resource *memory* (persistence), long-range
prospecting into unloaded chunks, the "find structures / player bases" query, farms/food gathering.

---

## 1. What we salvage, what dies

**Investigated live (file:line in the audit):** `worldmodel/region/RegionBlockIndex` (REAL — 64 classes,
version-agnostic `BlockLookup.byId`) and `RegionMetadata` (REAL — per-region log₂ histogram:
`encodeLog2(n) = floor(log₂(n+1)−1)`, `mergeLogCounts` rolls up correctly). Both are referenced ONLY by
the semantic `Region` flood-fill tree, which PRD §6.3 superseded with the fixed cubic-grid octree. Nothing
live calls either. `NavSection.candidateRegions[]` (+`addRegion`/`getCandidateRegions`) has zero callers.

**DELETE (vestigial semantic model):** `Region`, `CompositeRegion`, `LeafRegion`, `RegionPool`,
`RegionBuilder` (stub), `RegionBoundingBox`, `Portal`, `PortalShape`; the `candidateRegions` plumbing in
`NavSection`; the `region.Portal` javadoc mention in `NetherPortalIndex` (cosmetic).

**SALVAGE:** the **class registry** (rehomed as `worldmodel/resource/ResourceClasses` — drop the "Region"
name; regions-as-semantic are gone) and the **log₂ codec** (`encodeLog2` / `mergeLogCounts` → a small
`Log2Codec`, or inlined into the pyramid). The heap-object-per-region `RegionMetadata` shape does **not**
survive — it violates the SoA/no-alloc pattern `CostPyramid` uses (see [[hot-path-no-alloc]]); its *math*
lives on inside the pyramid columns.

New package `worldmodel/resource/`: `ResourceClasses`, `ResourcePyramid`, `ResourceQuery`. It depends on
`worldmodel/hpa/` (`RegionAddress`, `RegionGrid`) — the resource pyramid is a **parallel** SoA layer on the
same grid, per the owner "separate SoA pyramids" decision.

---

## 2. Class model — 64 stable IDs, ~23 indexed columns

Two distinct concepts, deliberately split:

- **Registry (all 64):** every tracked block → a stable class id 0..63 (the existing `RegionBlockIndex`
  numbering, kept for lookup and future persistence compatibility — its version-stable ids are the whole
  reason it resolves by registry string not `Blocks.X`).
- **Indexed set (~23):** the subset that actually gets a **pyramid column**. `ResourceClasses` maps
  `classId → column 0..N-1` (or −1 = not indexed). The tally, storage, and query operate on **columns**.

**Why a subset:** the log₂ octree only adds information for blocks whose location is *non-obvious and
non-uniform*. A ubiquitous block (stone, deepslate below y=0) **saturates** — `encodeLog2` maxes to the
same value in every region, so drill-down can't discriminate — and costs a column at every level. Storage
is linear in indexed columns, so ~23 vs 64 is ≈2.8× less. Non-indexed blocks are served by a **directed
scan on demand** (dig down for stone; walk to a biome for wood).

**Milestone-1 indexed set (owner-ratified 2026-07-03) — 23 columns:**

- **Ores / valuables (12):** coal, iron, copper, gold (`gold_ore`+`nether_gold_ore`), redstone, lapis,
  emerald, diamond, nether_quartz, ancient_debris, amethyst, obsidian.
- **Builder palette (11):** diorite, granite, andesite, calcite, tuff, dripstone, sandstone
  (`sandstone`+`red_sandstone`), basalt, terracotta (all colors), glowstone, vines. — the block-variant
  blobs a survival builder runs short of ("go find more andesite" for a mansion is a real drill-down;
  these are biome/blob-clustered so the histogram discriminates well).
- **Registry-only (stable id, NO column — dig-down / directed scan):** stone, **deepslate** (ubiquitous
  below y=0 → saturates; nobody runs short of it), log/wood (surface, clustered — "mine the forest I'm
  standing in" needs no index; walk to a biome otherwise), all farms, all utility, all "hints".

The registry-only classes keep their ids so a later arc (food gathering; the "find structures/player
bases" query over chests/spawners/redstone/rails) adds columns with **zero renumbering** — persistence
stays compatible.

---

## 3. `ResourcePyramid` — the data structure

A parallel SoA store mirroring `CostPyramid` exactly (open-addressed `long`→row map, murmur3 finalizer,
grow-at-¾, per-level `Level` inner class, `long` keys — no boxing). It is keyed by the **same**
`RegionAddress` grid: leaf = 16 blocks, octree to level 5 then quadtree, `packLevelKey` (22 rx / 22 rz /
6 ry), `childCount` 8 or 4. One NavSection (16³) = one level-0 region → 1:1 tally row.

**Per-row payload:** the log₂ count of each indexed column. A count ≤ 4096 (cells in a section) needs
`log₂ ≤ 12`, which fits a **nibble** (0–15), but for milestone-1 simplicity store a **byte per column**,
SoA: `byte[COLUMNS][rowCap]` (or a flat `byte[rowCap*COLUMNS]`). ~23 B/row. (Nibble-packing to ~12 B/row
is a later option; favor-CPU-over-RAM says don't bother until RAM profiles hot — see [[favor-cpu-over-ram]].)

**Sparsity is the win:** a resource row is interned **only** when a section actually holds ≥1 indexed
block. Air/dirt/stone sections never create a row → zero storage. This is what keeps the layer inside the
PRD §6.6 ~4–5%-of-save budget. `ResourcePyramid.rowIfPresent` returns −1 for the empty common case with no
allocation.

**API (mirrors `CostPyramid`):** `rowFor(level,rx,ry,rz)` (intern), `rowIfPresent(...)`,
`isBuilt/setBuilt`, `rowRX/RY/RZ`, `count(level,column,row)` (decoded), `setCount(level,column,row,log2)`.
`RegionGrid.of(level)` gains a `resourcePyramid()` accessor alongside `pyramid()`.

---

## 4. Tally-on-classify — piggyback the single chunk scan (NO second scan)

**Owner constraint:** the build already pays once for palette decode + encoded-index walk
(`NavSectionBuilder.classifyNavtypes`, see `docs/Optimizations/block_reading.md`). The tally rides *that*
pass; no re-read, no second reflection into `PalettedContainer`, no BitStorage re-walk.

The hook copies the **existing `anyPortal` gate pattern verbatim** (NavSectionBuilder.java:214–227):

```java
// palette-decode loop (already runs, ~palette.length iterations) — add two lines:
int col = ResourceClasses.columnFor(palette[s]);   // 0..22, or -1
slotToColumn[s] = col;
anyResource |= (col >= 0);                          // one compare per palette entry, like anyPortal
...
// gated pass — mirrors the portal collection at :223-227; runs ONLY when anyResource:
if (tallyOut != null && anyResource) {
    for (int i = 0; i < 4096; i++) {
        int c = slotToColumn[slotScratch[i]];      // sequential int[] read (L1/L2), gated increment
        if (c >= 0) tallyOut[c]++;
    }
}
```

The hot 4096-cell **navtype-resolution loop (:229–235) is untouched.** Common path (no indexed block in
palette) pays exactly one compare per palette entry — same as portals today. Resource-bearing sections pay
one extra sequential 4096 pass of a read + gated increment — a fraction of the original palette+BitStorage
decode. `tallyOut` is a reused per-thread `int[COLUMNS]` (zeroed by the caller), no allocation.

**Carrying the tally to the pyramid:** `ChunkNavBuilder.buildAllSections` passes the reused `tallyOut` per
section; if any nonzero, it log₂-encodes into a small nullable `byte[]` stored on the `NavSection`
(`section.resourceTally` — nullable, so resource-free sections cost nothing). Only allocated once per
resource-bearing section build, off the hot loop.

---

## 5. Level-0 write + roll-up — reuse the nav maintenance cycle

Everything reuses `HpaMaintenance`'s existing hooks and its **single dirty-leaf set + per-tick budget** —
one machine drives both pyramids:

- **On chunk load** (`ChunkNavLoader.onWorldTickEnd:69` → `HpaMaintenance.onChunkNavBuilt`): the per-section
  loop already visits each level-0 region. Extend it to also write `section.resourceTally` into
  `resourcePyramid.rowFor(0, rx, sectionY, rz)`, then `mergeUpTallies(...)`.
- **Roll-up** (`mergeUpTallies`, a mirror of `PyramidMerger.mergeUpFragments`): O(levels) walk parent→root,
  each parent column = **log₂-sum of its 8/4 children** (`mergeLogCounts` is associative), early-out on
  unchanged signature (line-for-line the fragment damping). Single-threaded on the tick thread; children
  are read, parent written.
- **On block change** (`HpaMaintenance.onBlockChanged:200`): the level-0 leaf is already marked dirty by
  packed key. The same dirty entry re-tallies the resource row when `flush` drains it — a section re-tally
  on block change is a local re-scan of one section (or, cheaper, an incremental ±1 on the changed block's
  column; incremental is a later optimization). `flush` (:226, `MAX_LEAVES_PER_TICK=8`) rebuilds both
  pyramids for each drained leaf.

No new event wiring, no new thread, no new budget — the resource layer is a passenger on the nav
maintenance cycle.

---

## 6. Drill-down query — best-first over the octree

`ResourceQuery.find(level, column, filters)` → an ordered list of candidate level-0 regions (then a local
scan yields exact block positions, §7).

**Model:** a best-first search over the pyramid, ranking regions by a score that folds the two filters:

- **Quantity** ("lots of diamonds"): the decoded log₂ count at a region; a `minCount` threshold prunes,
  and higher counts score better.
- **Proximity** ("near me" / "near point P" / "in cave X"): Chebyshev/euclid distance from the region
  center to the anchor point P; nearer scores better.

```
find(column, P, minCount, maxResults):
  start at the coarsest built level whose region over P has count >= minCount
  (ascend from P's leaf until a level has enough, per PRD §6.4);
  push that region into a best-first PQ ordered by score(count, dist-to-P);
  pop:
    if level == 0 -> emit as a candidate (has the resource; exact cells found on arrival)
    else -> push each child with count > 0 (and passing the filter), scored
  until maxResults candidates or PQ empty.
```

Level-0 is a 16³ region: the query proves the resource is *in* that section, not *where*. Exact positions
come from a **single-section local scan on arrival** (§7) — cheap, once, off any hot path. Scratch (PQ,
visited) is per-query pooled, no per-node allocation, same idiom as the block A*.

**Concurrency:** the pyramid is written on the tick thread (chunk load, flush) and, for milestone 1, the
query also runs on the tick thread (the gather loop ticks) — single-threaded, safe. If the query later
moves to a planner thread it needs the same epoch/reclamation care as async nav (`NavReclaim`); flagged in
§8, not built now.

---

## 7. `/bot gather` — the GATHER mode state machine

The agency/`tasks` layer is stubs (CLAUDE.md); the loop is a hand-coded state machine on `AllyBotEntity`,
reusing the reactive executors. New command via the `BotCommand` strategy (one line in
`OrebitCommands.COMMANDS`): `/bot gather <resource> [count]` → `bot.startGather(column, quota)`.

**New `Mode.GATHER`** (added to the `FOLLOW/STAY/COME` enum) with a phase field and a `tick()` case
`gatherLoopTick()`:

| Phase | Action (reusing existing APIs) | Advance when |
|---|---|---|
| **FIND** | `ResourceQuery.find(column, botPos, …)` → target region; if none known, report + STAY (milestone 1 searches loaded/known regions only). | got a target → PATH |
| **PATH** | `driveToward(targetCenter, floor)` each tick; watch `pathPlan.status()`. | `driveToward` returns true → SCAN/MINE; `FAILED` → blacklist region, back to FIND |
| **SCAN** | on arrival, scan the target section for exact block positions of `column` (direct `getBlockState` over 4096 cells — one section, not a hot path); queue them nearest-first. | queued → MINE |
| **MINE** | `mining.request(cell)` **every tick** (reactive — BotMining has no done-event); poll `level.getBlockState(cell).isAir()`; on air, check inventory count of the resource item (real ServerPlayer inventory, auto-pickup — see [[agency-inventory-is-real]]). | quota met → RETURN; section exhausted → FIND |
| **RETURN** | `driveToward(gatherStartPos or owner)`. | arrived → DONE |
| **DONE** | `setMode(STAY)`, chat success. | — |

**Quota is measured in picked-up items**, not blocks mined (ore drops the item; a fortune/deepslate
variant may drop more/less) — poll `BotInventory` count of the target item after each break. This dovetails
with the inventory-persistence feature (next arc).

Awkwardness to accept (from the execution-plane audit): no arrival/mining callbacks → the loop polls every
tick; modes are exclusive → GATHER is standalone (can't layer on FOLLOW); `clearPlan()` on mode switch
resets path state cleanly.

---

## 8. Deferred / risks (named, not silently dropped)

1. **Cross-restart resource memory (persistence).** The pyramid rebuilds from live chunk loads (like nav;
   `HpaPersistence` is itself still a stub). Without persistence, "the diamonds I saw in the cave
   yesterday" do **not** survive a restart — a use case the owner named. This is the natural **milestone-2**
   companion (PRD §6.6 budgets ~4–5% of save); design it with the nav-cost persistence, sharing the codec.
2. **Unloaded-chunk prospecting.** The query only sees built (loaded / previously-loaded) regions.
   "Find diamonds" won't reach unexplored chunks. Milestone 1: search known regions; if none, report or
   walk-and-scan. Long-range prospecting (force-load ahead? does the FakePlayer already?) is a later arc.
3. **Level-0 granularity.** The pyramid localizes to a 16³ section; exact cells need the on-arrival scan.
   Fine, but means the bot must reach the region before it knows the precise block — acceptable.
4. **Query thread.** On the tick thread for now (safe). Moving it off-tick later requires the async-nav
   epoch discipline.
5. **Incremental vs full re-tally on block change.** Milestone 1 re-scans the changed section (simple);
   an incremental ±1 on the changed block's column is a clean later optimization.

---

## 9. Build phases (the workflow — ordered, each independently testable)

1. **Demolition.** Delete the semantic region tree + `candidateRegions` plumbing; compile-green all 28 +
   26.2. (Pure subtraction; no behavior change — nothing referenced it.)
2. **`ResourceClasses`.** Rehome the registry; add the `column` mapping for the 23-set; salvage `Log2Codec`.
   Unit-test the codec (encode/merge round-trips) — reuse the existing `RegionMetadata` math.
3. **`ResourcePyramid`.** Clone `CostPyramid`'s SoA; byte-per-column rows; `mergeUpTallies`. Unit-test
   intern/grow + a hand-built roll-up (children → parent log₂-sum).
4. **Tally-on-classify.** The gated pass in `classifyNavtypes` + `NavSection.resourceTally` + the
   `onChunkNavBuilt`/`flush` writes. Verify no regression on the nav JMH `SHORT`/`MULTI` guards (the tally
   is gated, but confirm the palette-loop additions don't perturb classify) and a probe that a known ore
   section produces the expected level-0 counts.
5. **`ResourceQuery`.** Best-first drill-down + filters; unit-test on a synthetic pyramid (nearest / most /
   threshold). Add `/bot probe`-style `/bot find <resource>` to eyeball candidates in-game.
6. **GATHER mode + `/bot gather`.** The state machine; in-game verify `/bot gather iron 5` end-to-end on
   26.2 (find → path → mine → inventory quota → return). This is the milestone bar.

Phases 1–3 are pure data-plane and fully unit-testable off the game. 4 touches the (perf-sensitive) build
path — design-review + the JMH guard per the CLAUDE.md performance protocol. 6 is the in-game payoff.

---

## 10. Open questions for owner

- **Deepslate:** confirm excluded (saturates) or index it anyway (one column)?
- **Quota semantics:** "gather 5" = 5 *items in inventory* (my assumption) or 5 *blocks mined*? (Matters
  with fortune / variant drops.)
- **RETURN target:** back to where the bot started the gather, or to the owner's current position?
- **"None found" behavior:** stop and report, or walk-and-scan outward until something turns up (pulls in
  the unloaded-chunk question early)?
- **Persistence now or milestone 2?** The "remember yesterday's cave" use case needs it; everything else in
  this milestone works on live-loaded data.
