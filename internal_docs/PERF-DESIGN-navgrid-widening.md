# PERF DESIGN — NavGrid cell widening (curated): floorGap depth nibble (CONDENSED)

> **STATUS (2026-07-03): ADOPTED (headlines 1+2) — unconditional in the tree as of the E3/E4 cleanup.**
> Built as prescribed: parallel `byte[4096]` per section (`TraversalGrid.depth`; the hot `short[]`
> untouched), chunk-column-exact (`NavSectionBuilder.computeDepth` pass 3), upward floorGap / downward
> runUp fixpoint patch propagation across ≤1 vertical seam. Measured (E3): FLOOD −5.1% / CLIFFS −4.3% /
> TOWER −3.4%, SHORT +1.2% pinned-real (accepted — the predicted y==minY worst case). `PatchStormBenchmark`
> gates at worst +2.7% vs no-maintenance. Deviations: (a) the high nibble went to the E4 runUp field
> (`PERF-DESIGN-runup-nibble.md`); (b) encoding = 0–13 exact / 14 proven-none / 15 UNKNOWN→legacy-scan;
> (c) all flags removed — maintenance + consumers unconditional. **Headline 3 (cardinal neighbor class)
> is DEAD** — it required the edit-bbox gate, refuted at p=0.000 (`PERF-RESULTS-2026-07-03.md` §E1/E2).
> Results: `PERF-RESULTS-2026-07-03.md` §E3 + ADDENDUM.

**Mechanism in one line:** a per-cell low nibble answers "distance to the first standable floor below"
so `Fall` stops paying 16-deep column scans; stored beside — never inside — the hot `short[]`.

**Code:** `worldmodel/pathing/TraversalGrid.java` (depth array), `NavSectionBuilder.java`
(`computeDepth`/`patchCell`), consumer `pathfinding/blockpathfinder/movements/Fall.java`.
**Guards:** `DepthNibbleTest`, `DepthIdentityTest`, `PatchStormBenchmark`, the CLIFFS JMH scenario.

**§ map (sections cited by code Javadocs):** §1 current-state audit (the scans the nibble replaces);
**§2 field semantics** — floorGap encoding 0–13 / 14 / 15-UNKNOWN (cited by `DepthNibbleTest`);
**§3 storage decision** — parallel `byte[4096]`, NOT `short[]`→`int[]` widening (widening taxes the
millions-of-reads extractor path to serve a ≤8-reads-per-pop field — the Hilbert mistake in cache form);
**§4 read-path mechanics** — `Fall`'s branch-shaped floorGap fast path; §5 byte-identity argument;
§6 write path — §6.1 column build, §6.2 `patchCell` upward propagation, **§6.3 the patch-storm concern +
the new bench** (`PatchStormBenchmark`), §6.4 memory math; §7 measurement plan — **§7.3 the Fall-heavy
guard scenario** (CLIFFS in `PathfinderBenchmark`); §8 phase-2 cardinal neighbor class (dead);
§9 alternatives; §10 risks.
