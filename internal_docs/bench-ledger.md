# Bench ledger — measured results not (yet) carried by a docs/Optimizations chapter

> Purpose: the `docs/Optimizations/` series is the public measurement record; this ledger holds
> **sourced** perf numbers from recent arcs that have no chapter (or whose only record was a commit
> message / handoff note), plus the explicit MEASUREMENT-PENDING list. House rule applies: nothing
> appears here without a written source in the repo (commit message, handoff, code comment, design
> doc). No number in this file is from memory.
>
> Created 2026-07-23 (perf-doc audit session). Update in place; keep sources cited.

## Measured + adopted

### Region roll-up fold cost envelope (invalidation #5 increment C) — ADOPTED (envelope check passed)
- **Mechanism:** `InvalidationRollup.foldFrom` — the record-time L0→L6 containment fold on the
  BLOCKED record path (a few per goal; a rare-event path, not a hot path). Benchmarked to satisfy
  the owner's "a rare event can be safely recomputed, but benchmark to be safe" ruling.
- **Bench:** `RollupFoldBenchmark` (pure-array, no MC; scenarios SPARSE = common case, DENSE =
  12-fragment MIXED worst case, DEEP = L0→L6 boundary-chain recursion).
- **Measured:** **125 ns – 72 µs cold** across the scenario spread.
  Source: `HANDOFF.md`, "Persisted Invalidation Memory (#5 increment C) SHIPPED (2026-07-22)" block
  ("perf gate PASSED (… roll-up fold 125ns–72µs cold)"). Per-scenario breakdown was not written
  down — only the envelope. If per-scenario numbers are ever needed, re-run the bench.
  **⚠ 2026-08-07: that `HANDOFF.md` block is GONE** — HANDOFF.md is the rolling next-session pointer
  and has since been rewritten for the region-tier arc (2026-07-24). This ledger entry is now the
  **only surviving record** of the measurement anywhere in the repo (grepped: `internal_docs/`,
  `docs/`, all Javadoc — `InvalidationRollup.java` and `DESIGN-persisted-invalidation-memory.md`
  name `RollupFoldBenchmark` but carry no number). Do not delete this entry without re-running.
- **Verdict:** even the 72 µs worst case is per-BLOCKED-event, not per-node/per-tick — accepted.

### Persisted invalidation memory (#5 increment C) perf gate — ADOPTED (flat)
- **Measured:** region A/B **flat, ≤1.4% mixed-sign over 3 pairs**; Pathfinder **SHORT flat**.
  Source: same `HANDOFF.md` block (heads core `dc2182a` / mc-1.21 `1f14d23`).
- **Verdict:** the sig-tagged v5 shard rows + fold add no measurable cost to region planning or
  block-search setup.

### NavGrid edit batching (s54) — ADOPTED
- Full numbers + narrative: `docs/Optimizations/15_batching_the_repairs.md`; status header of
  `PERF-DESIGN-navgrid-edit-batching.md`. Primary source: commit `f57c65e` message
  (BATCH_PISTON −56.5%, BATCH_BLAST −67.5%, TOGGLE_PAIR −99.8%; PatchStorm/SHORT/MULTI gates flat).

### Per-pop `h` recompute cache (P2, s53) — REFUTED, reverted
- Verdict + do-not-retry rationale now in `NOTES-region-findings.md` §7 ("REFUTED, reverted"; no
  targeted scenario cleared the ≥3% bar; do not re-propose the exact variant; the `f−g` variant is
  rejected a priori as a behavior change). *(Was `PERF-AUDIT-region-field.md` §P2 — that file was
  consolidated away in the 2026-08-07 doc audit; the full paired A/B table did not survive the
  consolidation, only the verdict. Re-measure if a number is ever needed.)*

## MEASUREMENT-PENDING (no repo-written numbers — do not cite figures for these)

1. **Containment-anchor cold cost (region A/B)** — the s55 containment anchor
   (`RegionGrid.containedFragment` / `InvalidationRollup.containedParentFragment`, feet-cell flood
   seeding, faced-only centroid fallback) adds cold per-plan work. The owner named the region A/B
   bench **the remaining bank gate**: `HANDOFF.md` 2026-07-23 rulings block, item 3 — "Region A/B
   perf bench (anchor adds cold per-plan work) STILL PENDING". Status: **MEASUREMENT-PENDING**.
2. **Swim-up-blindness stack region cost** — the 2026-07-21 arc's perf gate result was never
   written into the repo (no number in any tracked doc). Either re-run the region A/B or accept it
   as covered by the #5-increment-C flat gate above. Status: **MEASUREMENT-PENDING** (repo-side).
3. **Depth-nibble maintenance worst-case discrepancy** — `docs/Optimizations/09_depth_nibbles.md`
   says patch-storm maintenance adds "**+2.7% at worst**"; `PatchStormBenchmark.java` (~line 36)
   and `NavSectionBuilder.java` both say "**measured worst +1.8%**". Both claim to be the adoption
   measurement. Resolve from the original s-log or re-measure before citing either.
   *(Still live and unresolved as of 2026-08-07: `docs/Optimizations/09_depth_nibbles.md:115` vs
   `NavSectionBuilder.java:636`.)*
4. **Field-build "~6 µs" Javadoc figure** (`PathPlan.regionFieldFor`) — flagged implausible for
   larger boxes by `NOTES-region-findings.md` §6 (the ×63 dense layout zeroing bill); a measured
   curve across box sizes is still owed (`RegionFieldBuildBenchmark` exists for it).
   Status: **MEASUREMENT-PENDING**.
5. **Region-field proposals P1 / P4 / P5** (baked centroids, ×63 layout shrink, de-boxed dig-flood
   BFS) — PROPOSED, unratified, unmeasured. Now in `NOTES-region-findings.md` §6.
6. **Async region-tier phases** — PROPOSED; the surviving §-tables carry measured *inputs*
   (region A* sub-10 µs/level, field build 0.1–1.4 ms — `NOTES-region-findings.md`) but the
   ~10–70 µs/replan savings are estimates, not results.
7. **`BatchEditBenchmark` REDSTONE shape** — deliberately absent (needs a live `ServerLevel` the
   Knot classloader can't provide); Phase-0 has correctness coverage (`NavGridEpochTest`) but no
   perf number. Fine to leave: its cost is structurally ≤ the old path.
