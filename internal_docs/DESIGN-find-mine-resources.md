# DESIGN — Find → Mine Resources (CONDENSED — SHIPPED s45+; full text in git history pre-s52)

**Status: built end-to-end.** The resource data plane (classify-tally → pyramid → roll-up → query) and
the `/bot gather` loop are live; s51/s52 hardened gather (eye→ore ray dig via
`AllyBotEntity.firstOcclusion`, ItemEntity-lifecycle COLLECT — no timers). The vestigial semantic
`worldmodel/region/` tree was deleted as §1 prescribed.

**Where the code lives now:**
- `src/main/java/com/orebit/mod/worldmodel/resource/` — `ResourceClasses` (64 stable class IDs, ~23
  indexed columns), `Log2Codec` (per-column log₂ histogram byte), `ResourcePyramid` (per-dimension SoA
  octree on the `RegionAddress` grid, owned by `RegionGrid`; rows interned only for sections holding ≥1
  indexed block), `ResourceScan` (tally-on-classify + on-arrival exact-cell scan), `ResourceMerger`
  (roll-up), `ResourceQuery` (best-first drill-down)
- Producers/consumers: `ChunkNavBuilder` tallies into `NavSection.resourceTally`;
  `HpaMaintenance.onChunkNavBuilt` rolls up; `commands/FindCommand.java` + `commands/GatherCommand.java`;
  `AllyBotEntity` GATHER mode (`gatherScan`/`gatherMine`/`gatherCollect`/`gatherCompass`)

**§ map (sections cited by code Javadocs):**
- §0 the bar ("ask for iron, come back with iron"). §1 salvage/delete decisions (semantic region tree
  dies; log₂ math salvaged). §2 class model — 64 stable IDs, ~23 indexed columns.
- §3 `ResourcePyramid`. §4 tally-on-classify — piggyback the single chunk scan, NO second scan
  (anyPortal-style gate). §5 level-0 write + roll-up via the nav maintenance cycle.
- §6 drill-down query. §7 the `/bot gather` state machine (FIND→PATH→SCAN→MINE→COLLECT→RETURN as
  designed; live enum is `GatherPhase {SCAN, MINE, COLLECT, COMPASS, RETURN}`).
- §8 deferred/risks: §8.1 cross-restart persistence (pyramid rebuilds from chunk loads); §8.2
  unloaded-chunk prospecting; §8.3 level-0 granularity is the 16³ section — exact cells need the
  on-arrival scan; §8.4 query runs on the tick thread; **§8.5 incremental vs full re-tally on block
  change — the resource pyramid stays chunk-load-driven; per-block patches keep only the COST pyramid
  live** (cited by `HpaMaintenance`).
- §9 build phases (all done). §10 owner-ratified decisions (2026-07-03), incl. quota = items in
  inventory, not blocks mined.
