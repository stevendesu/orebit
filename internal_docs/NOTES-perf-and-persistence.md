# NOTES — region persistence format + durable perf verdicts

> **What this file is.** The surviving, VERIFIED core of the deleted `DESIGN-worldmodel-persistence.md`
> (whose §4–§6 were a superseded stub and whose §7/§10 still described a gzipped per-dimension blob that
> has not existed since 2026-07). Kept because it is (a) the citation anchor for ~24 code Javadoc
> references and (b) knowledge that is expensive to re-derive: the rejected-alternative analysis, the
> load-bearing invariants, and the measured rulings that must not be re-litigated.
>
> **Authority order.** Byte layout: the class Javadoc of
> `src/main/java/com/orebit/mod/worldmodel/persistence/CostPyramidCodec.java` (~L24–170) and
> `ResourcePyramidCodec.java` — **code wins over this file**. Runtime map:
> `internal_docs/SUBSYSTEMS.md` → "worldmodel/persistence". Invalidation rows:
> `internal_docs/DESIGN-persisted-invalidation-memory.md` §4/§4b.
>
> *Verified against `core` on 2026-08-07.*

---

## §1 Files and on-disk format

Per dimension, under `<world>/orebit/<sanitized-dim>/` (`RegionPersistence.ROOT_DIR = "orebit"`;
`sanitize` replaces anything outside `[a-zA-Z0-9.\-_]` with `_`, so `minecraft:overworld` →
`minecraft_overworld`). Plain `java.nio.file.Files` I/O — the `BotManager` pattern, **no NBT/SavedData**
(§6).

| file | magic | contents |
|---|---|---|
| `hpa.<X>.<Z>.bin` | `OBHS` | cost levels **0..5** for one L5 shard (`X = chunkX>>5`) + invalidation section |
| `hpa.coarse.bin` | `OBHC` | cost level **6 only** (`RegionAddress.MAX_COARSE_LEVEL`) + invalidation section |
| `res.<X>.<Z>.bin` | `OBRS` | resource levels **0..5** for one L5 shard |
| `res.coarse.bin` | `OBRC` | resource levels **6..21** (`RESOURCE_TOP_LEVEL`) |

Globs are `hpa.*.bin` / `res.*.bin`; the dot-middle-segment requirement means the legacy per-dimension
`hpa.bin` / `res.bin` blobs (and `*.bin.tmp`) never match — old blobs are **ignored, never deleted**.

**Versions (do not repeat the old "v7" claim).** `CostPyramidCodec.VERSION == 1` and
`INVAL_SIG_SCHEMA_VERSION == 1` — both were **reset to 1** on the 2026-07 `packLevelKey` repack
(`ry` narrowed 6→5 bits), collapsing the v2..v7 history rather than bumping to v8, because disk is a
cache. `ResourcePyramidCodec.VERSION == 2`. The *layout* is still the v7 layout; the version *history*
(v2 dropped gzip, v3 column-run body, v4 real invalidation section, v5 floorless-leaf semantics, v6
fragment-count sentinel, v7 typed fragments) is worth reading in the codec Javadoc.

**Bodies are RAW — there is no gzip anywhere** (ruling §7).

### Cost body (column-run) — `CostPyramidCodec.writeEncoded`
```
int   magic
short VERSION
byte  levelCount                 // non-empty levels only
per level:
  byte  level
  int   columnCount
  per column:
    int   rx
    int   rz
    short runCount               // read unsigned
    per run:
      byte  ryStart
      byte  ryLen                // consecutive ry sharing a byte-identical record
      short recordLen
      byte[recordLen]            // CostCodec.packRegion bitstream
per file (LAST, shard AND coarse):
  byte  INVAL_SIG_SCHEMA_VERSION (=1)
  byte  INVAL_GRAPH_CLASS_ID     (=0, "optimistic-v1")
  int   invalCount
  invalCount x { long fromStored; long toStored; long capsSig; }   // 24 B/row
```
A run continues while `ry` is consecutive AND the record bytes are `Arrays.equals`-identical; decode
expands runs back to individual rows, so the round trip is byte-identical at the row/record level.
`gridSize` is **not persisted** — the decoder derives it per level from `PyramidMerger.coarseG(level)`
(16 at the leaf, 4/2 coarse; the old level-0-only codec hardcoded `LEAF_SIZE = 16`, which is the gotcha).

**Invalidation row packing** (post-repack, `INVAL_*` constants): key mask = **55** bits;
`fromStored = fromKey | (level << 55)`; `toStored = toKey | (prov << 55)`, `INVAL_PROV_MASK = 0x3`;
fragment id at bits **49..54** (`INVAL_FRAG_SHIFT = 49`, mask `0x3F`). Rows are scoped **assign-to-FROM**
shard (`invalRowInScope`); `PROV_ESCALATION` rows are filtered on write. Decode is defensive:
sig-schema / graph-class mismatch drops **only** that section, `EOFException` is swallowed, and rows whose
TO fragment id `>= RegionFragments.MAX_FRAGMENTS` are dropped (legacy virtual-goal self-clean). Resource
files carry **no** trailing section.

### Resource body — `ResourcePyramidCodec.writeEncoded`
```
int   magic
short VERSION
byte  COLUMNS                    // = ResourceClasses.COLUMN_COUNT (23)
byte  levelCount
per level:
  byte level; int rowCount
  per row: int rx; byte ry; int rz; byte nz; nz x { byte col; byte log2 }
```
Sparse — only non-zero columns; decode reads the stored column count for alignment and skips
`col >= COLUMNS`. Still the **flat per-row** body (column-run was applied to cost only — §8).
`ResourcePyramidCodec`'s own Javadoc says "24 columns"; the constant is **23** (harmless comment drift).

---

## §2 What persists, what is recomputed

- **Persisted:** cost levels **0..5** per shard + level **6** per dimension; resource levels 0..5 per
  shard + 6..21 per dimension. Coarse levels are persisted **directly** — reload is a pure decode that
  interns each row AT its level with **no `mergeUp` replay** (§7's 15.3× ruling).
- **Never persisted:** the fine 16³ nav grid (`NavStore`/`NavSection`/`TraversalGrid` — recomputed on
  chunk load, PRD §6.2), `RegionFragments.labels()` (build-time scratch, re-derived by
  `FragmentLeafComputer.labelFragments`), and `built` flags (implied — a persisted row is built).
- **Files are a CACHE, never source of truth.** Bad magic / version mismatch / IO error → treated as
  absent → rebuilt from live. **Migration policy: there is none** — bump the version, old files
  cache-miss. No migrator, ever.
- **Live wins.** A decode never clobbers a row already built this session, checked **per level
  independently** (skip a row iff THAT level is already built). The straddle cells this skips are
  repaired by §3.
- **Resource tallies are best-effort.** `HpaMaintenance.onBlockChanged` updates the cost pyramid but not
  the resource pyramid, so a persisted resource shard can be stale relative to blocks mined since that
  section last loaded. Refreshed to truth whenever the chunk reloads (`onChunkNavBuilt` overwrites).
  Do not try to make persistence more accurate than the live model.

---

## §3 Reconciliation (the anchor code cites as "§2b")

`decode(in, dest, StraddleSet)` records every **L>=1** row skipped by the live-wins rule (L0 skips are
never recorded — live always wins at L0). `RegionReconciler` then recomputes those cells ascending
L1→L5 (children read via `rowIfPresent`, so it unions live + freshly-interned-persisted) and propagates
L5→top. The per-cell primitives are `PyramidMerger.combineFragments` and
`ResourceMerger.recomputeParent`, both of which take a `childLevel` for exactly this caller. An empty
straddle set (fully-unloaded shard) costs zero.

---

## §4 Bounded region RAM (the anchor code cites as "bounded region RAM")

- **Startup.** `hpa.lazyLoad=false` (**the shipped default**) → `RegionPersistence.loadAll`, eager, every
  shard. `true` → `loadCoarseOnly`: decode only the 2 coarse files and build the persisted-shard index
  from a **directory listing** (filenames only, no bodies). Wired in `OrebitCommon` at `SERVER_STARTED`.
- **Lazy load.** hpa-side consumers only REQUEST — `RegionShardResidency.enqueueLoad`, so the dependency
  arrow stays persistence→hpa and there is **no I/O on the alloc-free read path**. Triggers:
  `HpaMaintenance.onChunkNavBuilt` (ensure the containing shard resident before `mergeUp`) and
  `RegionGrid.ensureLeaf/ensureLevel` on an unbuilt+persisted-non-resident miss.
  `RegionShardLoader.drain(level)` is a tick phase budgeted by `pathing.regionShardLoadBudgetMs`
  (default 2.0) with a >=1/tick backstop; loads are **atomic per shard**, which is only safe because raw
  bodies decode in ~11–34 ms (§7).
- **The clobber-guard (load-bearing invariant).** A live re-merge must not overwrite a `built` coarse cell
  using a child that is absent because it is *persisted-but-non-resident* rather than genuinely
  unexplored. `PyramidMerger.combineFragments` and `ResourceMerger.recomputeParent` early-return
  (DEFER + enqueue load) when any child is absent-or-interned-but-unbuilt AND its shard is
  persisted-non-resident. Byte-identical no-op when residency is empty (eager mode).
- **Eviction** (`RegionEvictor.sweep`, opt-in, inert while `hpa.residentLeafCap == 0`, **the shipped
  default**): shard-granular. Gate = **every backing chunk column unloaded in `NavStore`** — a state
  gate, no timers (an unloaded chunk fires no block change, so nothing is lost) — then coldest by
  `RegionShardResidency.lastTouched`. Flush-if-dirty first, capped `MAX_FLUSHES_PER_SWEEP = 4`.
  `freeShard` nulls the `RegionFragments` and clears `built` on cost AND resource levels 0..5, but the
  **row stays interned** (SoA is append-only; an unbuilt-but-interned row reads as the optimistic-AIR
  default the planner already handles). **L6 coarse rows are never evicted**, and
  `flushShard(writeCoarse=false)` means eviction never rewrites the coarse files.
- **Non-determinism (accepted).** Lazy+evict WIDENED a pre-existing, ratified non-determinism rather than
  adding a new class: a non-resident coarse cell reads the optimistic-AIR default (admissible, never
  refuses a real route), so an explored cell can read real data in one run and optimistic-AIR in another.
  Worst case is a different but valid corridor; the near window always refines against the live
  `NavGridView` before commit. **HARD CONVENTION: the headless autotest runs `hpa.lazyLoad=false`** to
  preserve `-MasterWorld` frozen-world determinism.

---

## §5 Lifecycle, dirty tracking, concurrency (the anchor code cites as "§5.2")

- **Load** at `SERVER_STARTED`; **authoritative flush** at `SERVER_STOPPING` (the
  `PlatformEvents.onServerStopping` seam, default no-op, wired in every loader impl); **periodic
  crash-insurance flush** on `onWorldTickEnd`.
- **Dirty tracking:** `DIRTY_SHARDS: Map<ServerLevel, Set<Long>>` + `COARSE_DIRTY: Set<ServerLevel>`,
  marked at the three tick-thread `HpaMaintenance` sites (this is the mark that `HpaMaintenance`'s
  block-change path performs). The periodic pass is interval-TRIGGERED (`hpa.persistIntervalTicks`,
  default 6000; `0` disables) then **resuming and wall-clock budgeted**
  (`hpa.persistFlushBudgetMs`, default 2.0, >=1 shard/tick backstop) with a **clear-after-write**
  lifecycle — a shard's dirty flag clears only on a successful write, `COARSE_DIRTY` only once both
  coarse files wrote. Coarse is written LAST. This budgeting is the fix for the measured ~1.9 s periodic
  flush spike.
- **Atomic-ish write:** `writeAtomic` = `BufferedOutputStream(…, 1<<16)` → temp file → `Files.move` with
  `ATOMIC_MOVE|REPLACE_EXISTING` plus a non-atomic fallback. No fsync (crash insurance only). Per-shard
  try/catch — never throws on the tick thread.
- **All I/O on the tick thread.** This is the single most important consistency constraint: loading
  interns rows and grows the SoA maps by reallocation, which must never race a reader. The region tier is
  tick-confined (planner workers read only an immutable `RegionCostField` snapshot), so load / flush /
  evict / reconcile are plain tick phases — **no `NavReclaim`-style epoch machinery is needed for the
  region grid.**
- **`ShardRowIndex`** (`worldmodel/hpa/`) — per-dim, per-L5-shard, per-level row-index lists appended in
  `CostPyramid.rowFor` / `ResourcePyramid.rowFor` (one hook covers every intern path incl. disk decode).
  Purely additive; lets a flush gather one shard's rows with **no dimension scan** (`bucketShard`).
  `bucketShards` (full scan) survives as the byte-identity test oracle.

---

## §6 Why plain files, NOT vanilla `SavedData` (the rejected alternative — do not revisit)

`SavedData` looks native but its API has drifted hard across the 1.17.1 → 26.x matrix, and every drift
point would need a `platform/` overlay:

- **Constructor / dirty model:** pre-1.20 `SavedData(String name)` with `setDirty()`; 1.20 dropped the
  name-in-constructor and reworked the factory.
- **`save(CompoundTag)`** gained a `HolderLookup.Provider` parameter in 1.20.5, and the whole
  `CompoundTag` surface moved toward `ValueInput`/`ValueOutput`/`TagValueOutput` in the 1.21.6+ era — the
  same churn `BotSpawn`'s overlay already fights for player data.
- **`DimensionDataStorage.computeIfAbsent`** changed signature with the `SavedData.Factory<T>` rework.
- **NBT buys nothing here:** the payload is a bit-packed `CostCodec` bitstream either way, so `SavedData`
  would only buy vanilla's flush-on-save plumbing at the cost of 3–4 overlay flavors.
- **Unobf 26.x** is Fabric-only precisely because the toolchain can't handle that churn; `Files` + our own
  bytes are Java-stable.

The only vanilla API this depends on is `server.getWorldPath(LevelResource.ROOT)` (mojmap-stable
1.17 → 26.x, proven by `BotManager`) plus `level.dimension().location()` for the per-dimension
sub-directory.

---

## §7 Durable measured rulings — do NOT re-litigate

- **Persist L0–L5 directly, NOT L0-only + `mergeUp` replay.** `PersistenceLoadBenchmark` A/B per 32×32
  shard: SURFACE 43.0 → 37.1 ms (1.16×), **CAVE 1355 → 88.7 ms (15.3×)**. `mergeUp` does not re-flood, but
  `combineFragments` is O(children·fragments²) union-find and CAVE speckle hits it on every cell → a ~27-tick
  freeze. Cost: +8–14% file size.
- **Drop gzip everywhere** (both codecs, shard + coarse). Inflate was **62–71% of shard-load cost**
  (SURFACE 40→11 ms, CAVE 88→34 ms raw). Measured disk penalty on the frozen jungle world: cost 2.15×,
  resource 2.79×, blended **2.36×** — accepted. (A synthetic bench's 6–22× ratio was an LZ77 artifact of
  interning one identical column at 1024 coords; discarded.) The zero-runtime-dep constraint rules out LZ4.
  Consequence: raw CAVE 34 ms < a 50 ms tick → **atomic shard load is tick-safe**, no incremental interning.
- **Column-run cost codec adopted (v3):** 2.08× vs raw = ~97% of gzip's space at raw decode speed
  (0.87–1.02× raw). The compressible bulk was **per-row coordinate headers** (rx/ry/rz/len = 63.4% of the
  raw body), NOT air runs — hence per-(rx,rz) grouping + ry-runs. Chose column-run over palette+RLE (2%
  better, more complex).
- **Object pooling is a DEAD lever for load latency — REFUTED, measured.** A `RegionFragments` pool
  eliminated 82–90% of allocation (51→5 MB/op SURFACE, 57→10 MB/op CAVE) for **zero wall-clock change**
  (marginally negative from `Supplier.get` indirection). Young-gen alloc is free (bump-pointer TLAB); the
  floor is DECODE (`readBits` sub-byte unpack + open-addressed interning). Caveat: latency-only verdict —
  but SLOWTICK measured `gc=0` on the real in-game spike, so do not pursue pooling for tick spikes either
  without new `gc=` evidence.
- **Invalidations roll up; they are NOT L0-only** (owner overturn 2026-07-19). If a single L0 crossing is
  the only link between two L1 regions and it dies, those L1 regions are disconnected — coarse planning
  would still route through them and discover it only on approach. See `InvalidationRollup`.
- **Colocation = assign each crossing to the shard owning its FROM node** — matches the from-keyed read
  path (the blacklist is consulted when expanding A, whose shard is resident) → zero cross-shard read on
  the hot path.
- **Disk budget:** the pre-measurement estimate in the deleted doc (~3–5% of save, gzipped) is obsolete.
  Use the measured raw penalty above against the PRD §6.6 baseline of 2,557 B/chunk.

### Diagnostics that found these
- `[Orebit][PERSIST] wrote {N} shards in {X}ms (shardEnc={Y}ms coarse={Z}ms) {M} dirty remaining` —
  `RegionPersistence.drainDimension`, gated on `Debug.VERBOSE`, elapsed > 5 ms.
- `[Orebit][SLOWTICK] tick=Xms ourOps=Yms gc=Zms other=Wms | navBuild= mergeUp= hpaFlush= shardLoad=
  evict= persist= botTick=` — `src/main/java/com/orebit/mod/SlowTickMonitor.java`, threshold 100 ms,
  gated on `/bot debug verbose on`. This is what attributed an in-game multi-second stall to `persist`
  (not GC, not vanilla). Use it first for any future tick-spike hunt.
- Benchmarks (`src/test`, mc-1.21 era only): `PersistenceLoadBenchmark` (direct-vs-recompute load A/B),
  `ChunkBuildBenchmark` (`shardLeaf`/`shardRollup`), `RollupFoldBenchmark`.
- Tests: `RegionPersistenceRoundTripTest`, `ClobberGuardTest`, `ReconcileTest`, `LazyLoadOracleTest`,
  `EvictionTest`, `RegionInvalPersistenceTest`. They drive the **CODECS**, not `RegionPersistence`
  (which imports `ServerLevel`/`MinecraftServer` → can't class-load headless), so the file-I/O / glob /
  atomic-move / dirty-lifecycle glue is **in-game-only verifiable**.

---

## §8 Open / deferred (future work)

1. **Flip the shipped defaults.** `hpa.lazyLoad` is still `false` and `hpa.residentLeafCap` still `0` —
   the bounded-RAM stage ships OFF. In-game verification was done once (3000-block goto into unexplored
   world and back, `lazyLoad=true` + `cap=3000` — SUCCESS) but the defaults were never flipped. Decide:
   flip, or keep opt-in permanently and document why.
2. **Resource-shard column-run codec** — deferred; resource is still `VERSION=2` flat per-row. Measure the
   resource body's structure (sparse `(col, log2)` pairs, 2.79× under gzip) before applying; it may not pay.
3. **Byte-align the cost record format** — the second lever on the residual decode floor (11 ms SURFACE /
   34 ms CAVE per shard, i.e. `readBits` sub-byte unpack + interning, after gzip removal). Noted,
   deliberately not built.
4. **Coarse-cadence decoupling.** `COARSE_DIRTY` is set on every `markDirty`, so both coarse files are
   re-encoded on every flush pass. Post-fix measurements showed `coarse=0 ms` in the `[Orebit][PERSIST]`
   log, so it has never been the bottleneck — a future call informed by that log.
5. **`RegionEvictor.freeShard` still does a full per-level `rowCount` scan** rather than using
   `ShardRowIndex` (~L211–230). Cold path, low value, but it is the one place the index wasn't adopted.
6. **First-persist-write JIT outlier** — the first periodic write measured 202 ms, then 19 ms, then a
   steady 13–17 ms/shard. Most-likely cause (offered, never confirmed): cold JIT on the ENCODE path
   (`loadCoarseOnly` only decodes; `NavWarmup` only warms the pathfinder) + first-file I/O. Optional fix:
   a synthetic encode warm at `SERVER_STARTED`, and split `shardEnc` into encode-vs-`Files.move`.
   **LOW PRIORITY** — one-time, on the crash-insurance path.
7. **Coarse pyramid RAM still grows unbounded** (~14% of pyramid RAM vs ~86% for L0 leaves; per-leaf
   ≈1.6 KB uniform / ≈5.8 KB MIXED, dominated by `RegionFragments.footprint = int[378]` paid even by
   uniform leaves, plus a lazy `byte[4096]` label slab on MIXED). Coarse paging was accepted as
   deferred-to-v2. The fixed-array shrink is the other untried lever.
8. **Cross-shard straddle eviction of invalidation rows — UNVERIFIED.** Rows are assign-to-FROM, so a
   block change at B reopening a passage must evict a row stored in A's shard file.
   `HpaMaintenance` does call `RegionCrossingMemory.evictLeafTouching` on the block-change rebuild-leaf
   path (in-memory store), but whether the **on-disk** straddle case is covered was never confirmed.
   Check before trusting it.

---

## §9 Cross-references for other measured verdicts

This file covers persistence only. The other live measurement records:

- `internal_docs/bench-ledger.md` — sourced numbers with no `docs/Optimizations` chapter, plus the
  explicit MEASUREMENT-PENDING list.
- `internal_docs/PERF-DESIGN-navgrid-build.md` §4 — the NavGrid-build JFR (CAVE 3060 µs/column,
  `NavFlags.risksFluidFlow` 65.9%; SURFACE 838 µs, 53.1%), the shipped C1 fluid SCATTER
  (**continuous carry, no seam reset**), and the deferred **C4 off-thread build** (the largest remaining
  tick-budget lever).
- `internal_docs/PERF-DESIGN-navgrid-edit-batching.md` status header — the s54 batching A/B
  (BATCH_PISTON −56.5%, BATCH_BLAST −67.5%, TOGGLE_PAIR −99.8%, gates flat) and the two shipped
  deviations that must not be "fixed" (the §4.3 ordered-P3 depth phase is UNSOUND; the §6 REDSTONE bench
  shape is not buildable headless).
- `internal_docs/PERF-AUDIT-region-field.md` — the region cost-field audit and its refuted P2.
- `docs/Optimizations/` — the public, per-chapter measurement series.
