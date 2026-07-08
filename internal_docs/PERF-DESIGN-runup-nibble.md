# PERF DESIGN — E4: upward same-navtype run-length nibble (CONDENSED)

> **STATUS (2026-07-03): ADOPTED — unconditional (all flags removed in the E3/E4 cleanup).**
> Kept decisively: TOWER **−33.7%**, UPOVER_OPEN **−35.5%**, UPOVER_WALL **−30.4%**, MULTI **−32.3%** —
> ~75–80% of the cuboid-extraction bill removed on cuboid-bearing shapes, confirming the owner's
> "cuboid startup tax" hypothesis (extraction was 38–45% of small-search CPU per
> `PERF-PROFILE-2026-07.md` §3). Patch-storm worst +2.7%. Identity guards: `DepthIdentityTest` +
> `DepthNibbleTest`. Results: `PERF-RESULTS-2026-07-03.md` §E4 + ADDENDUM.

**Mechanism:** the depth byte's HIGH nibble stores the upward same-NAVTYPE run length from each cell, so
`CuboidExtractor`'s vertical column scans (stage-1 edge rows for X/Z travel, stage-2 slab re-scans —
~12k cell reads per TOWER probe) skip whole runs instead of reading cell-by-cell. Equivalence class MUST
be raw navtype equality (anything coarser over-claims); read paths fall back conservatively →
byte-identical boxes.

**Code:** `worldmodel/pathing/TraversalGrid.java` (high nibble), `NavSectionBuilder.java` (build/patch),
consumer `pathfinding/blockpathfinder/cuboid/CuboidExtractor.java` (rectUniform/slabUniform run-chains).

**§ map (cited by code):** §1 extractor scan audit (stage 2 dominates 30:1); **§2 field semantics** —
runUp = consecutive same-navtype cells above (cited by `TraversalGrid`/`CuboidExtractor`); §3 read paths
(conservative fallback, byte-identical); §4 write path (`computeDepth` pass 3 + `patchCell` fixpoint);
**§5 expected effect — downward (MineDown-direction) acceleration remains FUTURE WORK**; §6 flags
(removed).
