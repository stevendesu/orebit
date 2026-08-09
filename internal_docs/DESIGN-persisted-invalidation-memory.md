# DESIGN — Persisted Invalidation Memory (#5 increment C / sharding Stage 3)

Status: **SHIPPED (2026-07-22; restart oracle PASS)** — ratified from this draft, phases 1–3 all
implemented. Builds on: increment B (in-memory `RegionCrossingMemory`, core `02b908c`), sharding
Stages 1–2 (shipped + in-game verified; every `hpa.X.Z.bin` already carries a reserved empty inval
section), and the up-cliff arc (edge-realization blame + geometric partials, core `c49ae59` — which
already satisfies the old "trigger must include unfollowable partials" requirement by converting them to
honest BLOCKEDs).

## §0 AS-SHIPPED record (verify against code; supersedes the drafting details below where they differ)

- **Sig (Phase 1)** — `BotCaps.realizabilitySig(inv)`: effective-place bit 1 (`canPlace && !(consumesBlocks
  && placeableBlocks<=0)`), invuln bit 4, maxBreakHardness 5..12, jump 13..16, safeFall 17..29, maxFall
  30..42, bit 43 RESERVED `hasBreath` (never set/read), **SIX** 3-bit tool-tier fields at bits 44..61 (all
  real `NavBlock.Tool` categories, ordinals 1..6 — Q2's "~5" became 6; NONE excluded as inert; zeroed when
  `!canBreak` or null inv), **`mayFall` bit 62** (2026-08-08, for `/bot roam` — set = the bot may step off a
  ledge, i.e. strictly MORE capable; appended at a free bit rather than inserted because stored sigs are
  compared bit-for-bit). Dominance = per-field masked `>=` (`sigDominates`); the bool test is a single mask
  and is bit-position-agnostic, so bit 62 joins bits 0..4 in `SIG_BOOL_MASK` at no cost. Record-site skew fixed at
  the source: `HierarchicalRegionPlan` captures `capsSig` at construction from `PathPlan`'s per-replan
  (caps, inventory) pair — that construction-time value IS every search's sig. Null-inv plans never record
  (`recordToMemory=false`); they still seed (caps-only sig). Antichain compaction lives in
  `RegionCrossingMemory.record` (skip-dominated / replace-strictly-dominated / coexist-incomparable).
- **Persistence (Phase 2)** — 24 B rows in the v4 invalidation section of every shard/coarse file
  (`CostPyramidCodec`; **file `VERSION` is 1, PINNED pre-release** — owner ruling 2026-08-09: zero wild
  installs, semantic changes append to the codec Javadoc history instead of bumping. Reset to 1 from 7 on
  the 2026-07 `packLevelKey` repack that narrowed `ry` 6→5 bits; disk is a cache; the *layout* is still v7's). Post-repack packing: the key mask is **55** bits, `fromKey` carries LEVEL
  in bits **55..63**, `toKey` carries provenance in **two** bits **55..56** (the draft said one spare
  bit), fragment id sits at bits **49..54**, `capsSig` 8 B. Full byte layout:
  `NOTES-perf-and-persistence.md` §1 and the `CostPyramidCodec` class Javadoc. Section header =
  `INVAL_SIG_SCHEMA_VERSION` (**1, PINNED pre-release** — same ruling; the codec Javadoc's history logs
  the semantic changes that would have bumped it: mayFall bit 62 [a v1-era section's rows carry that bit
  clear, reading as a strictly weaker prover — dropping and re-learning is the correct cache behaviour],
  sig bit 3 broadening to trapdoors then fence gates, and the trapdoor-ladder climb's planner-strength
  increase) + `INVAL_GRAPH_CLASS_ID` (0, "optimistic-v1"); mismatch drops
  the SECTION only. Assign-to-FROM sharding; L6 rows ride the coarse file. `PROV_ESCALATION` rows are SESSION-ONLY
  (filtered at every encode; `PROV_PROOF`/`PROV_ROLLED_UP` persist). Decode additionally DROPS any row
  whose TO fragment id ≥ `MAX_FRAGMENTS` (reserved 62 / `VIRTUAL_GOAL_FRAG` 63) — legacy V-row self-clean.
- **Roll-up (§4b)** — `InvalidationRollup.foldFrom` at record time from `onBlocked` (Q1/Q3 as ratified):
  constituents = the walk openings the A* would realize (`childFlushWithParentFace` + touchesFace +
  footprint overlap; unbuilt child = always-alive optimistic constituent — the frontier property; a
  zero-constituent all-sealed face never kills — no vacuous truth). Sig-sound (constituent counts only via
  `holdsProofDominating` at ≥ S0; ESCALATION rows never count and `blameTubeConfined` never triggers a
  fold). Containment re-derived by the merge's own partition-invariant union-find
  (`containedParentFragment`) with a fragmentCount cross-check bail. Bench: `RollupFoldBenchmark`.
- **Expiry** — `RegionCrossingMemory.evictLeafTouching` hooked off `HpaMaintenance`'s block-change
  rebuild-leaf path: evicts rows whose FROM or TO region (or containing coarse ancestor — reviving
  ROLLED_UP rows) touches the changed leaf; conservative over-eviction by design.
- **Journey scoping (post-draft, evidence-model memory)** — two blame classes stay per-plan and are NEVER
  recorded: start-region rows (FROM == the failing search's own start region — component-scoped proof, the
  ravine problem; the async driver threads the snapshotted `searchStartFloor` through `onBlocked`'s 4-arg
  form) and virtual-goal rows (TO == V; see HPA-CASCADE.md "The virtual goal fragment"). A cold one-line
  `#5 record-decision` log per BLOCKED result makes the record/skip decision attributable.
- **Oracle semantics (finding)** — WHICH search records a given blame varies run to run (async timing
  decides which window search hits the dead crossing first), so the restart oracle asserts
  **no-repeat-blames + convergence** (boot 2 skips straight to the detour with materially fewer
  invalidations), never specific rows or counts. `-KeepWorld` (Q4) is live on `run-autotest.ps1`.

## §1 Goal

Learned region-crossing invalidations survive server restarts, with per-row capability-dominance
seeding, so a bot that proved a cliff unclimbable yesterday does not re-pay the discovery walk today
— while never inheriting a theorem that wasn't proven for its current situation.

## §2 Layering decisions (RATIFIED in discussion, 2026-07-21/22)

- **Structural boolean capability axes → separate region graphs** (future arc; modified flood-fill
  connectedness, e.g. no-place/no-break variants). Deliberately symmetric/optimistic: an
  over-connected fragment is safe because the invalidation fallback (this system) provably
  converges — finds the goal or proves it impossible. Only pessimism (false disconnection) is fatal.
  NOT built now; this design must merely not block it.
- **Volatile / ordered / current capabilities → the invalidation signature** (tool tier, effective
  placement from inventory). Sparse, sig-labeled rows; dominance decides applicability per row.
- **Path-dependent survivability (lava transit, breath) → the search tier**, with matching sig bits
  added ONLY when the search actually models them (a sig bit for an unmodeled constraint is a false
  hypothesis on every recorded theorem). `takesDamage` + fall distances are already modeled and
  already in the sig; `hasBreath` is reserved-not-added.
- **Storage does not multiply with sig dimensions**: rows ∝ crossings actually proven dead; the sig
  is a 64-bit label, not a partition key. Per-crossing rows form a dominance antichain (typically 1).

## §3 Phase 1 — signature correctness (in-memory; prerequisite to persisting anything)

The sig must record the conditions the failing search actually proved under. Today it records
config `BotCaps` while the search consults a per-search `InventoryView` (verified: placement gated
on `consumesBlocks && placeableBlocks <= 0` at MovementContext.placeable; break cost priced at the
bot's real best tool tier via `MiningModel.Snapshot`). That mislabels theorems — dormant only
because `consumesBlocks` defaults false.

1. **`realizabilitySig(caps, inv)`** (new overload; old one delegates with null inv → unchanged for
   callers that have no inventory):
   - The existing `canPlace` bit becomes **effective-place**:
     `caps.canPlace() && !(inv.consumesBlocks() && inv.placeableBlocks() <= 0)`.
     Boolean is exactly faithful today because the search's own gate is existence, not count; if the
     search ever becomes count-aware, log₂ count buckets extend the ordered-field pattern (format
     versioned, see §5).
   - **Tool-tier ordered fields**: one 3-bit field per `MiningModel.Snapshot` category, in the free
     bits above the current layout (bits ~43+; current usage ends ~bit 42). Dominance uses the
     existing masked `a >= b` per-field rule. Zeroed when `!canBreak` (mirrors the existing
     maxBreakHardness zeroing).
   - `hasBreath`: RESERVE the bit position in the layout doc, do NOT set/read it until the search
     models submersion budgets.
2. **Record-site skew fix**: the recorded sig must come from the **failing search's** InventoryView,
   not `HierarchicalRegionPlan`'s construction-time snapshot. PathPlan holds the current
   `(caps, inventory)` per replan; thread the effective sig to the two record sites (`onBlocked`,
   `blacklistCurrentHop`) from there.
3. **Null-inventory rule**: searches with no InventoryView (headless, `/bot trace`, tests) never
   record to memory — their placement/mining assumptions are incoherent as a proof hypothesis
   (infinite blocks + bare-hand pricing).
4. **Seed site unchanged**: dominance test at plan construction, now against the bot's current
   effective sig (computed from the same per-replan inventory sample). A mid-goal inventory
   improvement takes effect at the next plan rebuild — consistent with plan lifecycle generally.
5. Optional (recommended): **antichain compaction** in `RegionCrossingMemory.record` — skip a new
   row whose sig is dominated by an existing row for the same crossing; replace when the new row
   strictly dominates. Bounds per-crossing rows at the set of incomparable proofs.

Tests: sig-layout unit tests (effective-place flip, tier ordering, dominance table), record-skew
test (inventory drifts between plan construction and failing search), null-inv no-record.

## §4 Phase 2 — persistence (the reserved inval section)

- **Record = 24 B**: `fromKey(8) + toKey(8) + capsSig(8)`. Level in fromKey's free high bits, provenance
  in toKey's (proof vs escalation vs rolled-up, see Q1/Q2). *(Drafting-era bit positions assumed a 56-bit
  key; the shipped post-repack numbers are in §0 — 55-bit mask, level 55..63, prov 55..56.)* Sharding:
  **assign-to-FROM** (matches the from-keyed read path); straddle eviction is boundary-aware per the
  ratified sharding design — **the on-disk straddle case was never verified**, see
  `NOTES-perf-and-persistence.md` §8.8.
- **Section header** (inside the existing reserved slot, before entryCount):
  `sigSchemaVersion (byte)` + `graphClassId (byte, = 0 "optimistic-v1")`. Cache semantics on either
  mismatch: drop the section, re-learn. This is the entire future migration story for new sig
  dimensions (breath, count buckets) and for capability-aware graphs (whose stores instantiate
  per-graph beside them — invalidation rows never transfer across graphs because fragment
  identities don't).
- **Write path**: piggyback the existing budgeted dirty-shard flush — recording/evicting a crossing
  marks its FROM shard dirty (`hpa.persistFlushBudgetMs` machinery, clear-after-write lifecycle).
  All tick-thread, like everything region-tier.
- **Load path**: shard load (eager or lazy) merges the section's rows into the per-dim
  `RegionCrossingMemory` (dedup / antichain). Rows are learned facts, not derivable state — no
  live-wins interplay.
- **Expiry (new for both memory and disk)**: hook the block-change seam (`HpaMaintenance`
  rebuildLeaf path): a block change in region R evicts rows whose FROM or TO is R (boundary-aware
  for straddles). Conservative over-eviction is fine (re-learn is cheap); stale-negative rows are
  the poison. Rolled-up ancestors of a revived crossing revive too (see §4b).

### §4b Invalidation roll-up (owner overturn 2026-07-19: store ALL levels, not L0-only)

An L(n+1) crossing (A→B) is dead iff ALL its constituent L(n) crossings are dead — the correctness
hole otherwise being a coarse skeleton routing through a pair of coarse regions whose only fine
crossing is dead. Mechanism: on recording an L(n) invalidation, enumerate the parent crossing's
constituent crossings (face-footprint data, already persisted L0–L5 + L6 coarse) and any-alive
fold; if none alive, record the parent row (provenance=rolled-up) and recurse. On expiry, revive
ancestors unconditionally (cheap, conservative). **MEASURE the enumerate+fold cost at record time**
(owner: "may be cheaper than feared — not a re-flood"); if it profiles hot, fall back to roll-up
evaluation at seed time instead of record time (same semantics, cost moved off the record path).

## §5 Phase 3 — validation

- Unit: round-trip (record → flush → load → seed) with sig dominance across the boundary; expiry
  round-trip; roll-up kill-set/revive; schema-version drop.
- **Restart oracle** (Stage 4 of the sharding plan): cliff repro two-boot run — boot 1 learns the
  invalidations and flushes; boot 2 (same world dir, NOT re-copied from master) must converge with
  materially fewer invalidations/searches (skip straight to the detour). Needs a small harness
  extension: a `-KeepWorld` (skip master re-copy) flag on `run-autotest.ps1`. Autotest stays
  `hpa.lazyLoad=false` per the determinism convention — orthogonal to this feature.
- Goal-shuffle regression (B's oracle) re-run to confirm no behavior change from Phase 1's sig
  narrowing (`consumesBlocks` is false there → sigs identical; the oracle guards exactly that).
- Perf gates: RegionPathfinderBenchmark (seed path), PatchStormBenchmark if the expiry hook touches
  grid-maintenance paths, and the roll-up measurement from §4b.

## §6 Rulings (owner, 2026-07-22)

- **Q1 — RATIFIED (persist roll-up; escalation rows per recommendation unless overturned)**:
  rolled-up coarse rows are PERSISTED, not derived on load — deriving on load is the same recompute
  cost the L0-L5 persist-coarse fork already rejected (loadDirect 15.3× on cave shards). Escalated
  `blacklistCurrentHop` rows persist provenance-tagged, keeping B's validated semantics.
- **Q2 — RATIFIED: all Snapshot tool categories** (~5 × 3-bit ordered fields).
- **Q3 — RESOLVED by Q1: record-time roll-up fold** (compute when learned, keep in memory, persist
  like any row; you can't persist rows you never computed, and seed-time is the vetoed on-load
  recompute). Measure the fold cost per protocol; it decides budgeting only, not placement.
- **Q4 — RATIFIED**: add `-KeepWorld` (skip master re-copy) to `run-autotest.ps1`.
