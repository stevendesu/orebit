# Cuboid extraction — per-node cost: options status (CONDENSED)

Framing (owner, 2026-06-26): we need the WHOLE box (jump length depends on move type/cost/direction and
the other box dims; `GoalForcedCost` needs the full forced-column depth) — so the ask is "build the SAME
box FASTER", never "extract less".

**STATUS (2026-07-03, unchanged since):**
- **A — bulk section-local scan: DONE.** `NavGridView.sectionRawAt` memory-order scan (TOWER
  161 → 57 µs/op, ~2.8×, box byte-identical; guarded by `CuboidExtractorScanTest`). The remaining bill
  was then cut a further ~75–80% by the E4 runUp nibble (`PERF-DESIGN-runup-nibble.md`,
  `PERF-RESULTS-2026-07-03.md` §E4).
- **B — primary-axis-only extraction: OPEN/parked.** An early version shipped and was REVERTED — the
  node-count tradeoff (~20× up-and-over explosion risk). If revisited post-A/E4: macro the top-2 axes
  for balanced multi-axis goals, must preserve TOWER, gate on a node-count check.
- **C — SoA cuboid-cache layout: DEFERRED** (the only per-search cuboid alloc; small today, grows with
  region count).
- **§D — forward-only edit-shrink: DONE.** `NavGridCuboidsView.applyEditShrink` clips the shrink scan's
  travel-axis range to the forward half (was ~57% of UPOVER_WALL CPU — pillar-below / bridge-behind
  cells the forward jump never traverses); full orthogonal range kept. Guard: `ForwardShrinkTest`.

**Profiler context that set the priority:** cuboid build was ~82% of TOWER search CPU
(`CuboidExtractor.slabUniform` alone 70%); stage 2 dominates stage 1 ~30:1; per-cell cost is the lever,
not cell count. Caveat: the bench's shared air `NavSection` understates the cache-locality half — confirm
locality wins in-game.

Code: `src/main/java/com/orebit/mod/pathfinding/blockpathfinder/cuboid/CuboidExtractor.java`,
`NavGridCuboidsView.java`. Full profiler tables in git history pre-s52.
