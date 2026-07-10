# DESIGN — Gap B: World-Model Persistence (HPA-IMPLEMENTATION.md §11)

**Status: IMPLEMENTED (core; eager-load + stop-flush + periodic dirty flush).** Persisting the per-`ServerLevel` region tier (`CostPyramid` fragment records + `ResourcePyramid` resource tallies) to disk so the bot's memory of explored terrain survives a level-unload / server restart. This closes the single deferred remainder of the HPA arc (§11) and the `§8.1` deferral of the find-mine-resources arc.

> **Implementation note (deviates from §4/§5 of the scoping below — a deliberate, owner-locked simplification).**
> The shipped design uses **per-dimension plain blob files, NOT region sharding** (§4), and **eager load at
> `SERVER_STARTED` + flush at `SERVER_STOPPING` + a budgeted periodic dirty flush**, NOT lazy per-shard load
> (§5). The clean-shutdown flush is the primary trigger (the target auto-stops when idle). Sharding / lazy load
> stay an explicit FUTURE optimization; the friend-server data set is small.
>
> Shipped surface:
> - `worldmodel/persistence/RegionPersistence` — the orchestrator (load-all / flush-all / periodic tick / dirty
>   set / path resolution), mirroring `BotManager`'s portable world-save file I/O.
> - `worldmodel/persistence/CostPyramidCodec` + `ResourcePyramidCodec` — headless-testable `OutputStream`/
>   `InputStream` codecs (magic + version header, gzip body; cost reuses `CostCodec.packRegion`/`unpackRegion`,
>   resource writes sparse `(col, log2)` pairs). Only level-0 leaves are persisted; `mergeUp*` is replayed on load.
> - `platform/PlatformEvents.onServerStopping` (default no-op) wired in every loader impl (Fabric
>   `SERVER_STOPPING`; Forge/NeoForge `ServerStoppingEvent`) — §5.1's missing lifecycle seam, now closed.
> - `hpa.persistIntervalTicks` config key (default 6000; `0` disables the periodic flush).
> - Headless round-trip test: `RegionPersistenceRoundTripTest`.
>
> Concurrency (§9) is honoured by construction: all load/flush runs on the tick thread (or after it halts), so
> nothing races a planner search or a pyramid array grow. Files are a cache (§5/§7): bad magic / version / IO →
> treated as absent; live-built leaves are never clobbered by a decode.

---

## 1. Problem & goal

Today both per-dimension pyramids live in RAM only:

- `RegionGrid` (`worldmodel/hpa/RegionGrid.java`) is interned per `ServerLevel` in a static `ConcurrentHashMap BY_LEVEL` and owns one `CostPyramid` + one `ResourcePyramid`.
- Rows are written on chunk-nav-build (`HpaMaintenance.onChunkNavBuilt` → `buildLeafSafe`/`writeResourceTallySafe`), and — for the cost pyramid — kept live by block changes (`HpaMaintenance.onBlockChanged` via the `LevelChunk.setBlockState` mixin, which **is now wired**: `overlays/1.17/.../mixin/LevelChunkMixin.java`, `overlays/1.21.5/...`).
- Data survives **chunk** unload (the pyramid is per-level, not per-chunk — this is the "travel-far-then-path" fix, `HpaMaintenance.EAGER_BUILD`), but is **lost on level unload / restart**: `RegionGrid.drop`/`clear` just `BY_LEVEL.remove`/`clear`.

**Consequence.** After a restart, a bot planning or `/bot report`-ing into terrain it explored last session sees nothing until those chunks are physically re-loaded and re-navved. The two read paths silently degrade to their optimistic defaults (region A* reads uniform-AIR; `ResourceQuery` reads "none known"). The point of the coarse tier — planning and prospecting over terrain that is **not currently loaded** — is defeated across sessions.

**Goal.** Persist the level-0 leaf data of both pyramids to the world save, portably across the MC 1.17.1 → 26.2 × Fabric/Forge/NeoForge matrix, reload it on demand, and keep the added disk footprint under the PRD §6.6 target (< 10–15% of save; estimated ~6–8%). Non-goals: persisting the fine 16³ nav grid (deliberately recomputed — PRD §6.2, decision #2), and persisting coarse pyramid levels (recomputable — see §2).

---

## 2. What to persist — and what NOT to

### 2.1 Persist: level-0 leaves only; recompute the rollup

Both pyramids are a stack of per-level SoA tables (`CostPyramid.Level` / `ResourcePyramid.Level`), levels 0..`MAX_LEVEL`(22), rolled up to `MAX_COARSE_LEVEL`(6). **Every coarse level is a pure function of level 0**, reconstructable by replaying the existing merge drivers:

- `PyramidMerger.mergeUpFragments(pyramid, rx, ry, rz)` — walks a leaf's ancestors to the root, `combineFragments` per level, sets each parent's `built` flag.
- `ResourceMerger.mergeUpTallies(resources, rx, ry, rz)` — same shape, `Log2Codec.merge` roll-up.

So **persist only level-0 rows** and replay `mergeUp*` per loaded leaf to rebuild every coarse ancestor. This matches the PRD §7 principle ("recompute cheap data; persist only expensive/global data") and shrinks the on-disk set to the leaves — the coarse levels are cheap arithmetic, the leaf flood is the expensive part.

> Note: PRD §6.3/§6.6 says "the pyramid is persisted (you can't rebuild a super-region's rollup from unloaded leaves)". That statement is about not rebuilding from the *live world* (leaves are unloaded). It does **not** preclude persisting the leaves themselves and re-merging on load — which is what this design does, and is strictly smaller on disk. Flag for the owner if they intended whole-pyramid persistence.

### 2.2 What each level-0 row holds, and its size

**CostPyramid leaf (`RegionFragments`, `CostCodec` bitstream — the on-disk form already exists):**

The serializer is **already written and tested**: `CostCodec.packRegion(rf, buf, bitPos)` / `unpackRegion(buf, bitPos, gridSize, out)` / `regionBitLength(rf)`. This is the single biggest de-risking fact of this whole task — the fragment wire format is done. Layout (sub-byte bitstream, MSB-first):

- Uniform region (SOLID/AIR/WATER): `kind`(2) + `avgSolidHardness`(4) = **6 bits**.
- MIXED: 6 + `passFrac`(4) + `fragmentCount`(6) + per fragment `faceMask`(6) + 16 bits per set face. A typical surface leaf (1–3 fragments, ~2–4 faces each) ≈ **60–140 bits ≈ 8–18 bytes**.
- `gridSize` (16 at the leaf) is **not** persisted — passed to `unpackRegion` at load. The collapsed-vs-stripped distinction is also not persisted (both reload as `count==0`).

Every loaded 16³ section gets a cost row (dense, unlike resources), but most are uniform (1 byte). A whole overworld chunk column (24 sections) is typically ~2–3 mixed + ~21 uniform ≈ **~60–90 bits of payload/section on average**.

**ResourcePyramid leaf (`Log2Codec` column vector):**

A row is a flat `byte[COLUMNS]` where `COLUMNS = ResourceClasses.COLUMN_COUNT = 24` (one log₂ byte per indexed resource column). **No serializer exists yet** — but it is trivial (a fixed 24-byte vector, or sparse `(col, value)` pairs since most columns are 0). **Sparsity is the key**: `ResourcePyramid` interns a row **only** for sections holding ≥1 indexed block (`HpaMaintenance.writeResourceTallySafe` is called only for a non-null `NavSection.resourceTally()`), so the vast majority of sections cost **zero** disk.

> Doc drift to fix in passing: `ResourcePyramid`'s class javadoc says "23-byte column vector" but `COLUMN_COUNT` is **24**. The wire format must key off the constant, not the prose.

**Row addressing.** A row's identity is `(level, rx, ry, rz)`; the RAM key is `RegionAddress.packLevelKey(rx,ry,rz)` (22b rx | 22b rz | 6b ry). On disk we don't need the packed RAM key — the shard file's own coordinate (see §4) supplies rx/rz, leaving only `ry` (0..31, one byte) per row.

### 2.3 Do NOT persist

- The **fine 16³ nav grid** (`NavStore`/`NavSection`/`TraversalGrid`) — recomputed on chunk load (PRD §6.2). Confirmed: `NavStore` javadoc "recomputed on chunk load and dropped on unload — never persisted." State this boundary explicitly in the file header.
- **Coarse pyramid levels 1..6** — recomputed via `mergeUp*` on load (§2.1).
- The `RegionFragments.labels()` slab (leaf-local flood labels) — a build-time scratch, re-derived on demand by `FragmentLeafComputer.labelFragments`.
- `built` flags — implied (a persisted row is built by definition).

---

## 3. Mechanism decision — plain-file seam, NOT vanilla `SavedData`

**Recommendation: adopt the `BotManager` plain-file-in-the-world-save pattern. Do NOT use vanilla `SavedData`/`DimensionDataStorage`.**

### 3.1 The precedent (already portable, already in the tree)

`BotManager` persists `orebit-bots.properties` into the world save with **zero vanilla-serialization API**:

- Path anchor: `server.getWorldPath(LevelResource.ROOT)` — `BotManager` documents `LevelResource.ROOT` as **mojmap-stable 1.17 → 26.x** (and `PLAYER_DATA_DIR` tracked the `playerdata` → `players/data` rename itself, javap-verified). That anchor is loader-agnostic (read from the `MinecraftServer` every loader hands us via `onServerStarted`).
- I/O: plain `java.nio.file.Files` + streams, defensive (missing/corrupt file → empty).

This is exactly the mod's stated philosophy: Architectury-**API**-free, hand-written seams, full control, zero runtime deps (CLAUDE.md build section; `platform/` overlay model).

### 3.2 Why not `SavedData`

`SavedData` looks native but its API has drifted hard across the matrix, and every drift point would need a `platform/` overlay:

- **Constructor / dirty model**: pre-1.20 `SavedData(String name)` with `setDirty()`; 1.20 dropped the name-in-constructor and reworked the factory.
- **`save(CompoundTag)`**: gained a `HolderLookup.Provider` parameter in 1.20.5 (`save(CompoundTag, HolderLookup.Provider)`), and the whole `CompoundTag` surface moved toward `ValueInput`/`ValueOutput` / `TagValueOutput` in the 1.21.6+ era — the same churn `BotSpawn`'s 1.21.9 overlay already fights for player-data.
- **`DimensionDataStorage.computeIfAbsent`**: signature changed with the factory rework (`SavedData.Factory<T>` wrapper added ~1.20).
- **NBT itself**: we'd be encoding a bit-packed blob into a `CompoundTag`/`ByteArrayTag` anyway, so `SavedData` buys us *nothing* on the serialization side — it only buys vanilla's flush-on-save plumbing, at the cost of 3–4 overlay flavors for the API drift.
- **Unobf 26.x**: the mod is Fabric-only there precisely because the toolchain can't handle the churn; hand-written file I/O sidesteps the whole question (`Files` + our own bytes are Java-stable).

The `CostCodec` bitstream is **already** our own format. Wrapping it in `SavedData` would be strictly more version-fragile for zero benefit. **Plain files win decisively here.**

### 3.3 The one vanilla API we depend on

`server.getWorldPath(LevelResource)` + per-dimension sub-pathing. `BotManager` proves `getWorldPath` + `LevelResource.ROOT` are stable. We additionally need a **per-dimension** sub-directory (§4), which needs the dimension id from a `ServerLevel`. `level.dimension().location()` (a `ResourceKey<Level>` → `ResourceLocation`) is a stable surface, but `ResourceLocation.toString()`/path components should be javap-confirmed across the range and, if it drifts, hidden behind a tiny new `platform/` helper (e.g. `Worlds.dimensionPath(ServerLevel)` returning a filesystem-safe `namespace/path` string). **Owner decision / verify item.**

---

## 4–6. Storage layout, lifecycle, read paths — SUPERSEDED by the shipped design

The scoping originally specified region **sharding** (§4), **lazy per-shard load** (§5.3), and the matching
read-path plumbing (§6). The SHIPPED design deviated (owner-locked): **per-dimension plain blob files**,
**eager load at `SERVER_STARTED`**, flush at `SERVER_STOPPING`, plus a budgeted periodic dirty flush — see
the Implementation note at the top for the shipped surface. §5.1's missing lifecycle seam is closed by
`PlatformEvents.onServerStopping`. Sharding / lazy load remain an explicit FUTURE optimization (the
friend-server data set is small).

---

## 7. Serialization format & schema / versioning

- **Cost payload:** reuse `CostCodec.packRegion`/`regionBitLength`/`unpackRegion` verbatim (already the "cold disk path", explicitly designed for persistence — see `CostCodec` §"The packer is a cold disk path"). Per column: `ry`-count, then for each row `ry`(1B) + the bitstream. Pack the column's rows into one `byte[]` sized by summing `regionBitLength`.
- **Resource payload:** new, trivial. Per row: `ry`(1B) + either the fixed 24-byte vector, or a sparse `nNonZero(1B) + (col,val) pairs` form (better, since typical rows have 1–4 non-zero columns). Decode with `Log2Codec` untouched.
- **Compression:** gzip the payload region (`java.util.zip.GZIPOutputStream`). The bit-packed data is already dense; gzip mainly squeezes the many uniform 6-bit cost rows and zero-heavy resource vectors. Keep the header uncompressed for index seeks.
- **Schema versioning:** `version` field in the header. On a version mismatch we can't decode, **discard the shard** (treat as absent) rather than guess — the data losslessly rebuilds from live chunk loads (it's a cache, not source-of-truth). Log once.
- **Forward-compat / class-id stability:** `ResourceClasses` registration order is **frozen by design** ("future persisted resource data stays compatible", `ResourceClasses.java` header) — so column indices are stable across versions. If `COLUMN_COUNT` ever grows, old shards (fewer columns) zero-extend; new shards on an old build (unknown columns) must be tolerated or version-bumped. Store `COLUMN_COUNT` in the resource header so a decoder can validate/zero-extend.
- **Corruption handling:** every read is defensive (`BotManager`'s model) — bad magic/length/IO → treat shard as absent, log throttled, continue. A corrupt shard costs only re-navving on chunk load, never a crash.

---

## 9. Concurrency & consistency

- **Writes** originate on the **tick thread** (`onChunkNavBuilt`, block-change `flush`, periodic/stop flush). The flush reads the pyramid SoA arrays; since those are also written on the tick thread, run flush **on the tick thread** (or a snapshot taken on it) to avoid tearing. The dirty-shard set is a concurrent set (worldgen threads can mark via block changes, exactly as `HpaMaintenance.DIRTY` already handles).
- **Reads (lazy load)** must respect the **epoch discipline** (`NavReclaim`/`PlanExecutor.minActiveStamp`, CLAUDE.md async model): do not intern new pyramid rows from a planner thread while a search reads them. Resolution: **do all lazy loads + `mergeUp*` on the tick thread** (load on the tick thread only). Loading interns rows and mutates the SoA maps (`growMap`/`growRows` reallocate arrays) — that MUST NOT race a planner read. Since `CostPyramid`/`ResourcePyramid` are append-only and grow by reallocation, a concurrent planner read during a grow would see a stale array reference at best, an inconsistent one at worst. **Load on the tick thread only.** This is the single most important consistency constraint.
- **Flush vs. concurrent write:** the periodic flush and `onChunkNavBuilt` both run on the tick thread → naturally serialized. Server-stopping flush runs after the tick loop halts → no concurrent writer.
- **Resource-tally staleness (the `onBlockChanged` TODO).** `HpaMaintenance.onBlockChanged` updates the **cost** pyramid on block change but **not** the resource pyramid (design §8.5 — resources are chunk-load-driven only). Persistence *bakes in* whatever the resource tally was at flush time. So a persisted resource shard can be **stale** relative to blocks mined/placed since that section last loaded. This is acceptable and consistent with the existing contract (`/bot gather` tolerates drift via its on-arrival local section scan; `ResourceQuery` is "approximate by design"). **State explicitly** that persisted resource tallies are a best-effort compass, refreshed to truth whenever the chunk reloads (which overwrites via `onChunkNavBuilt`). Do not attempt to make persistence more accurate than the live model — that's a separate arc (§8.5 incremental re-tally).

---

## 10. Disk budget estimate

Baseline (PRD §6.6): **2,557 B/chunk** compressed vanilla block data on the dev world. Target: added data < 10–15%.

**Cost pyramid (dense — every loaded section gets a row):** per chunk column ≈ 24 sections. Estimate ~2–3 MIXED (~10–18 B each packed) + ~21 uniform (6 bits ≈ 1 B each) ≈ **~45–75 B/chunk** payload, plus ~a few bytes of per-column index, pre-gzip. Post-gzip (uniform rows compress hard) → **~30–50 B/chunk ≈ ~1.5–2%** of baseline. Matches PRD's "~2%".

**Resource pyramid (sparse — only sections with indexed blocks):** most chunk columns have a handful of resource-bearing sections (ore layers, surface logs). Sparse rows ~5–15 B each, a few per column → **~20–60 B/chunk** pre-gzip, less after → **~1–2.5%**, in line with PRD's "~4–5%" upper bound (PRD's estimate is conservative).

**Combined ≈ ~3–5% of save, comfortably under the 10–15% target and consistent with the PRD's ~6–8%.** For a large explored world (say 500k chunks generated), that's on the order of tens of MB — trivial next to the vanilla region files. Because we persist only leaves and gzip, we sit at the low end of the PRD band. Empirical measurement (PRD §11) can follow once both writers land.
