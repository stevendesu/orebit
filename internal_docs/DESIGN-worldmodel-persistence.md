<!-- Provenance: Gap B scoping pass, 2026-07-09. Read-only research agent. Pending owner review of the
     §12 decisions before implementation. To be committed to internal_docs/ on `core` after the Gap A merge. -->

# DESIGN — Gap B: World-Model Persistence (HPA-IMPLEMENTATION.md §11)

**Status: DESIGN / SCOPING. Not yet implemented.** Persisting the per-`ServerLevel` region tier (`CostPyramid` fragment records + `ResourcePyramid` resource tallies) to disk so the bot's memory of explored terrain survives a level-unload / server restart. This is the single deferred remainder of the HPA arc (§11) and the `§8.1` deferral of the find-mine-resources arc.

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

## 4. Storage layout & keying

**Per-dimension, region-file-sharded — mirroring vanilla's own `region/r.X.Z.mca` sharding.**

```
<world>/orebit/
  <dim-namespace>/<dim-path>/           e.g.  minecraft/overworld, minecraft/the_nether
    hpa/   r.<sx>.<sz>.obhpa            cost-pyramid leaf shard  (32×32 chunk-columns)
    res/   r.<sx>.<sz>.obres            resource-pyramid leaf shard
```

- A **shard** covers a 32×32 block of chunk-columns (`sx = chunkX >> 5`, `sz = chunkZ >> 5`) — the same 512×512 footprint as a vanilla `.mca`, so shards align with terrain the player actually generated and bound write amplification.
- Within a shard, a leaf row is `(chunkX & 31, chunkZ & 31, ry)` + its packed payload. The shard header carries a small index (which of the 1024 columns are present + each column's byte offset), so a **single column** (`ChunkNavLoader` granularity) can be located without scanning.
- **Why not one blob per dimension**: a single-file-per-dimension design has O(explored-world) load cost and rewrites the whole file on every flush (write amplification). Sharding gives partial/lazy load (§5) and dirty-shard-only flush.
- **Why not per-chunk files**: a level-3+ coarse node spans 64 chunks (PRD §6.3) — but since we persist **only level 0** and re-merge, per-chunk *would* work; region-file sharding is chosen purely to avoid tens of thousands of tiny files (the same reason Minecraft shards). Keep cost and resource in separate files (PRD §6.3: the two SoA layers "compress and granularize independently"; they are written by different producers and read by different consumers).

**File format (both):** `magic(4) | version(2) | flags(2) | columnIndex | payload`. `columnIndex` = for each present `(cx,cz)`: the packed key + offset + row-count. Payload = per column, the `ry`-ordered rows. gzip the payload region (see §7).

---

## 5. Lifecycle — when to load, when to flush

### 5.1 The lifecycle gap that must be closed first

**Critical finding:** `RegionGrid.drop`, `RegionGrid.clear`, `HpaMaintenance.drop`, `HpaMaintenance.clear`, and `NavStore.clear` are **currently dead code — never called in production.** `OrebitCommon.init` wires `onServerStarted`, `onChunkLoad`, `onChunkUnload`, `onWorldTickEnd`, `onPlayerJoin/Disconnect`, `onRegisterCommands` — but **no server-stopping / level-unload / world-save hook**. `PlatformEvents` has no such method.

So step zero of implementation is **adding a lifecycle seam** to `PlatformEvents`:

- `onServerStopping(Consumer<MinecraftServer>)` — Fabric `ServerLifecycleEvents.SERVER_STOPPING`; Forge/NeoForge `ServerStoppingEvent`. The flush-everything barrier.
- Optionally `onServerSave(Consumer<MinecraftServer>)` — Fabric has no first-class "world saved" event pre-modern; use a periodic autosave cadence instead (below). Forge/NeoForge have `LevelEvent.Save`. To stay portable, prefer a **self-driven periodic flush** off the existing `onWorldTickEnd` cadence rather than depending on a save event that differs per loader.

Both new methods should be `default {}` no-ops (like `onChunkUnload`/`onRegisterCommands` already are), so eras/loaders not yet wired still compile.

### 5.2 Flush triggers (write path)

1. **On server stopping** (`onServerStopping`) — the authoritative full flush: for every level in `RegionGrid.BY_LEVEL`, write all dirty shards. This is the must-have.
2. **Periodic autosave** — every N ticks (config `hpa.persistIntervalTicks`, default e.g. 6000 = 5 min, matching vanilla autosave feel) drain a bounded number of dirty shards per tick off the `onWorldTickEnd` cadence (same budget discipline as `HpaMaintenance.MAX_LEAVES_PER_TICK` / `ChunkNavLoader.MAX_BUILDS_PER_TICK`). Protects against crash loss.
3. **On level unload / `RegionGrid.drop`** — flush that dimension's dirty shards, then drop. (Wire `RegionGrid.drop`/`clear` into the new lifecycle hooks — they exist but are unused.)

**Dirty tracking.** Add a per-dimension dirty-shard set (a `ConcurrentHashMap.newKeySet()` of packed shard keys), marked whenever a level-0 row is written/rebuilt (`RegionGrid.rebuildLeaf`, `writeResourceTallySafe`). This is the exact idiom `HpaMaintenance.DIRTY` already uses for dirty leaves — reuse the pattern. Flush walks dirty shards, re-packs their present columns, writes, clears the dirty mark. Marking is cold (one add per chunk build / block-change flush), off the hot search path.

### 5.3 Load triggers (read path) — **lazy per-shard, on first touch**

**Recommend lazy, not eager.** Eager (load the whole dimension at `onServerStarted`) reintroduces the O(explored-world) cost we sharded to avoid, and most of it is never touched in a session.

Load a shard the first time anything addresses a level-0 leaf inside it that isn't resident:

- Add a per-dimension "loaded-shard" set to `RegionGrid`. Before a level-0 `rowIfPresent`/`ensureLeaf` miss falls through to the optimistic default, `RegionGrid` checks: is this leaf's shard loaded? If not, load it (decode rows via `CostCodec.unpackRegion` / the resource decoder, intern them with `pyramid.rowFor` + `ensureFragments`/`setRow` + `setBuilt(true)`), then **replay `mergeUp*` for each loaded leaf** to rebuild coarse ancestors, mark the shard loaded, and re-answer.
- A shard with no file on disk is marked loaded-empty (negative cache) so we don't stat the filesystem on every miss.

**Interaction with the live rebuild.** When a chunk physically loads, `onChunkNavBuilt` rebuilds its leaf from the live `NavSection` and overwrites whatever was loaded from disk — the live world is always authoritative. Persistence only fills the gap for **not-currently-loaded** terrain. So the load path must not clobber a leaf that's already `built` from a live section this session (guard on `isBuilt`, prefer live).

---

## 6. How this changes the two read paths

Both consumers currently treat "unmapped region" as a default. Persistence makes them consult disk first, transparently, via the `RegionGrid` chokepoint — **no consumer code changes**, the lazy-load happens inside `RegionGrid`'s accessors.

**(a) Region A* default-on-miss (`RegionGrid.kind/avgHardness/passFrac/fragments/faceFootprint/fragmentRecord`).** Today an interned-but-`!built` or never-touched node returns the optimistic uniform-AIR / softest / fully-open default (`RegionGrid.java` lines 583–643; `HPA-FRAGMENTS.md` §6). With persistence, the miss branch first triggers a lazy shard load (§5.3); if the leaf was persisted, the planner now reads the **remembered** fragments instead of AIR. Admissibility is preserved: remembered data is a *refinement* of the optimistic default, and the live nav grid still overrides on approach. The optimistic default remains the final fallback for genuinely never-explored terrain.

- **Concurrency caveat (important):** these accessors run on **planner threads** (async pathing, `PlanExecutor`), which "must never touch vanilla chunks" (CLAUDE.md; `NavGridView.background`). Disk I/O on a planner thread is worse than a chunk read. Therefore **lazy load must not do disk I/O on the planner thread.** Two options — owner decision (§10): (i) planner-thread misses stay optimistic (no load); shard load is triggered only from the **tick thread** (e.g. `PathPlan` seeds a load request for the region window before dispatching the async search, mirroring how `AsyncWindowSearch` snapshots inputs); or (ii) an async I/O worker loads shards and publishes them, planner reads only already-resident rows. Option (i) is simpler and fits the existing "tick thread prepares, planner reads immutable snapshot" discipline.

**(b) `ResourceQuery` / `/bot report` (`ResourceQuery.find`).** Today the ascend/descend reads `rowIfPresent`; an unmapped region contributes nothing and the query reports "none known nearby" (`ResourceQuery.java` lines 98–105). `/bot report` and `/bot gather`'s FIND phase both run on the **tick thread** (design §8.4), so lazy shard load is safe to do inline here. Before the ascend, `RegionGrid` ensures the shards covering the query's `MAX_COARSE_LEVEL`-ancestor footprint are loaded; the drill-down then sees remembered tallies and reports resources in explored-but-unloaded terrain. This is the headline user-visible win: `/bot report` after a restart reflects everything the bot ever saw.

---

## 7. Serialization format & schema / versioning

- **Cost payload:** reuse `CostCodec.packRegion`/`regionBitLength`/`unpackRegion` verbatim (already the "cold disk path", explicitly designed for persistence — see `CostCodec` §"The packer is a cold disk path"). Per column: `ry`-count, then for each row `ry`(1B) + the bitstream. Pack the column's rows into one `byte[]` sized by summing `regionBitLength`.
- **Resource payload:** new, trivial. Per row: `ry`(1B) + either the fixed 24-byte vector, or a sparse `nNonZero(1B) + (col,val) pairs` form (better, since typical rows have 1–4 non-zero columns). Decode with `Log2Codec` untouched.
- **Compression:** gzip the payload region (`java.util.zip.GZIPOutputStream`). The bit-packed data is already dense; gzip mainly squeezes the many uniform 6-bit cost rows and zero-heavy resource vectors. Keep the header uncompressed for index seeks.
- **Schema versioning:** `version` field in the header. On a version mismatch we can't decode, **discard the shard** (treat as absent) rather than guess — the data losslessly rebuilds from live chunk loads (it's a cache, not source-of-truth). Log once.
- **Forward-compat / class-id stability:** `ResourceClasses` registration order is **frozen by design** ("future persisted resource data stays compatible", `ResourceClasses.java` header) — so column indices are stable across versions. If `COLUMN_COUNT` ever grows, old shards (fewer columns) zero-extend; new shards on an old build (unknown columns) must be tolerated or version-bumped. Store `COLUMN_COUNT` in the resource header so a decoder can validate/zero-extend.
- **Corruption handling:** every read is defensive (`BotManager`'s model) — bad magic/length/IO → treat shard as absent, log throttled, continue. A corrupt shard costs only re-navving on chunk load, never a crash.

---

## 8. Portability plan

- **New `platform/PlatformEvents` methods:** `onServerStopping` (+ optional `onServerSave`), `default {}`. Impls: `FabricPlatformEvents` (+ the Forge/NeoForge bridges under the mc-1.21 era). Fabric: `ServerLifecycleEvents.SERVER_STOPPING`. This is authored on `core` (the interface + common wiring) with each loader's impl in its module — the standard seam split.
- **Dimension → path helper:** verify `ServerLevel.dimension().location()` and `ResourceLocation` namespace/path accessors across 1.17 → 26.x (javap, like the `BotTeleport`/`LevelResource` verifications). If stable, keep in common; if it drifts, add `Worlds.dimensionKey(ServerLevel)` to the existing `Worlds` overlay (it already has 1.17 + 1.20 flavors). **Filesystem-sanitize** the path (`:` → `/`, strip illegal chars) for Windows (the dev platform).
- **Everything else is Java-stable:** `Files`, streams, gzip, our own byte format, `CostCodec`/`Log2Codec` (pure math, no MC imports). No `SavedData`/`CompoundTag`/NBT drift surface. This is the whole reason for the plain-file recommendation.
- **`getWorldPath` + `LevelResource.ROOT`:** already proven stable by `BotManager`.
- **Version-drift risk is near-zero** by construction — the only MC-API touch points are the two lifecycle events (cold, one line each per loader) and the dimension-id read (one seam if needed).

---

## 9. Concurrency & consistency

- **Writes** originate on the **tick thread** (`onChunkNavBuilt`, block-change `flush`, periodic/stop flush). The flush reads the pyramid SoA arrays; since those are also written on the tick thread, run flush **on the tick thread** (or a snapshot taken on it) to avoid tearing. The dirty-shard set is a concurrent set (worldgen threads can mark via block changes, exactly as `HpaMaintenance.DIRTY` already handles).
- **Reads (lazy load)** must respect the **epoch discipline** (`NavReclaim`/`PlanExecutor.minActiveStamp`, CLAUDE.md async model): do not intern new pyramid rows from a planner thread while a search reads them. Resolution: **do all lazy loads + `mergeUp*` on the tick thread** (§6a option i). Loading interns rows and mutates the SoA maps (`growMap`/`growRows` reallocate arrays) — that MUST NOT race a planner read. Since `CostPyramid`/`ResourcePyramid` are append-only and grow by reallocation, a concurrent planner read during a grow would see a stale array reference at best, an inconsistent one at worst. **Load on the tick thread only.** This is the single most important consistency constraint.
- **Flush vs. concurrent write:** the periodic flush and `onChunkNavBuilt` both run on the tick thread → naturally serialized. Server-stopping flush runs after the tick loop halts → no concurrent writer.
- **Resource-tally staleness (the `onBlockChanged` TODO).** `HpaMaintenance.onBlockChanged` updates the **cost** pyramid on block change but **not** the resource pyramid (design §8.5 — resources are chunk-load-driven only). Persistence *bakes in* whatever the resource tally was at flush time. So a persisted resource shard can be **stale** relative to blocks mined/placed since that section last loaded. This is acceptable and consistent with the existing contract (`/bot gather` tolerates drift via its on-arrival local section scan; `ResourceQuery` is "approximate by design"). **State explicitly** that persisted resource tallies are a best-effort compass, refreshed to truth whenever the chunk reloads (which overwrites via `onChunkNavBuilt`). Do not attempt to make persistence more accurate than the live model — that's a separate arc (§8.5 incremental re-tally).

---

## 10. Disk budget estimate

Baseline (PRD §6.6): **2,557 B/chunk** compressed vanilla block data on the dev world. Target: added data < 10–15%.

**Cost pyramid (dense — every loaded section gets a row):** per chunk column ≈ 24 sections. Estimate ~2–3 MIXED (~10–18 B each packed) + ~21 uniform (6 bits ≈ 1 B each) ≈ **~45–75 B/chunk** payload, plus ~a few bytes of per-column index, pre-gzip. Post-gzip (uniform rows compress hard) → **~30–50 B/chunk ≈ ~1.5–2%** of baseline. Matches PRD's "~2%".

**Resource pyramid (sparse — only sections with indexed blocks):** most chunk columns have a handful of resource-bearing sections (ore layers, surface logs). Sparse rows ~5–15 B each, a few per column → **~20–60 B/chunk** pre-gzip, less after → **~1–2.5%**, in line with PRD's "~4–5%" upper bound (PRD's estimate is conservative).

**Combined ≈ ~3–5% of save, comfortably under the 10–15% target and consistent with the PRD's ~6–8%.** For a large explored world (say 500k chunks generated), that's on the order of tens of MB — trivial next to the vanilla region files. Because we persist only leaves and gzip, we sit at the low end of the PRD band. Empirical measurement (PRD §11) can follow once both writers land.

---

## 11. Phased implementation plan (smallest shippable slice first)

**Phase 0 — lifecycle seam (prerequisite, no persistence yet).** Add `onServerStopping` (default no-op) to `PlatformEvents`; implement in each loader module; wire `RegionGrid.drop`/`clear` + `HpaMaintenance.drop`/`clear` + `NavStore.clear` into it (closing the existing dead-code gap). Verifiable independently (grids actually drop on stop now). Low risk.

**Phase 1 — write-then-read the COST pyramid, one dimension, eager-load.** Ship the format + shard writer (using existing `CostCodec`) + a full-dimension eager loader at `onServerStarted`/first-`RegionGrid.of`. Flush only on `onServerStopping`. This is the minimal end-to-end proof: explore, stop, restart, confirm the region A* reads remembered fragments (via `/bot goto` into unloaded terrain, or a `/bot regiontrace`). Eager-load first because it sidesteps the planner-thread concurrency question entirely.

**Phase 2 — RESOURCE pyramid persistence.** Add the (trivial) resource row serializer + `res/` shards, same lifecycle. Verify `/bot report` after restart shows remembered resources. This is independently valuable and low-risk (tick-thread-only consumers).

**Phase 3 — sharded lazy load + periodic flush + dirty tracking.** Replace eager load with per-shard lazy load (tick-thread-gated per §6a option i), add the dirty-shard set and the budgeted periodic `onWorldTickEnd` flush. This is the scalability + crash-safety upgrade. Most of the concurrency care lands here.

**Phase 4 — hardening.** Schema-version rejection, corruption tolerance, gzip, filesystem sanitization, disk-budget measurement (PRD §11), config keys (`hpa.persist`, `hpa.persistIntervalTicks`). Tests: round-trip `CostCodec` (exists) + a new pyramid-file round-trip test (headless, MC-free — the pyramids and codecs already have MC-free test seams: `HpaMilestoneTest`, `ResourcePyramidTest`, `ResourceQuery.find` headless core).

Ship Phase 0+1 together as the first PR; each later phase is independently mergeable.

---

## 12. Open questions / owner decisions

1. **Leaves-only vs whole-pyramid on disk.** This design persists **level 0 + re-merges** (smaller, matches §7 principle). Confirm the owner didn't intend literal whole-pyramid persistence per PRD §6.3 wording (§2.1).
2. **Lazy-load thread model** (§6a / §9). Confirm option (i) "tick-thread loads only; planner-thread misses stay optimistic until the tick thread has loaded the region." This is the recommended, lowest-risk choice and preserves the epoch discipline.
3. **Eager vs lazy for v1.** Recommend eager in Phase 1 (simplicity) → lazy in Phase 3 (scale). Confirm acceptable.
4. **Dimension-path portability** (§8). Approve adding a `Worlds.dimensionKey` seam if `dimension().location()` proves to drift; otherwise keep in common. Needs a javap pass across the era boundaries (like the `BotTeleport`/`LevelResource` verifications).
5. **Flush cadence** (`hpa.persistIntervalTicks` default). 5 min? Tie to vanilla autosave?
6. **Is persisted data source-of-truth or cache?** This design treats it as a **cache** (discard on version mismatch/corruption, live world always wins). Confirm — it's what makes corruption handling trivial.
7. **`RegionGrid` keyed by `ServerLevel` identity.** On reload the `ServerLevel` object is new, so the `BY_LEVEL` map naturally starts empty — good. But confirm nothing else relies on grid identity surviving restart.

---

### Critical files for implementation

- `worldmodel/hpa/RegionGrid.java` — the per-dimension chokepoint; add lazy-load-on-miss + dirty/loaded-shard tracking; wire `drop`/`clear` into the new lifecycle.
- `worldmodel/hpa/CostCodec.java` — the fragment serializer already exists (`packRegion`/`unpackRegion`/`regionBitLength`); the cost shard reader/writer builds directly on it.
- `worldmodel/hpa/HpaMaintenance.java` — the write-path producers; hook dirty-shard marking here and add the flush-drain to the tick cadence.
- `platform/PlatformEvents.java` (+ `FabricPlatformEvents` and the Forge/NeoForge bridges) — add the `onServerStopping`/save lifecycle seam that does not yet exist.
- `BotManager.java` — the copy-this precedent for portable world-save file I/O (`getWorldPath(LevelResource.ROOT)`, defensive read/write); the new writers + any dimension-path seam should follow its shape and javap-verified path-stability notes.

Supporting: `ResourcePyramid.java` / `Log2Codec.java` / `ResourceClasses.java`, `PyramidMerger.java` / `ResourceMerger.java` (`mergeUp*` replayed on load), `RegionAddress.java`, `ResourceQuery.java`, `OrebitCommon.java`.
