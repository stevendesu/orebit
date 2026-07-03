# PERF DESIGN — E4: upward same-navtype run-length nibble (cuboid-extraction accelerator)

> **STATUS (2026-07-03): ADOPTED — unconditional (all flags removed in the E3/E4 cleanup).**
> The measurement kept it decisively: TOWER **−33.7%**, UPOVER_OPEN **−35.5%**, UPOVER_WALL **−30.4%**,
> MULTI **−32.3%** — ~75–80% of the extraction bill removed on cuboid-bearing shapes, confirming the
> owner's "cuboid startup tax" hypothesis (extraction was 38–45% per `PERF-PROFILE-2026-07.md` §3).
> Post-cleanup identity: `DepthIdentityTest` (repurposed to maintained-vs-erased-UNKNOWN) +
> `DepthNibbleTest` green; patch-storm worst +2.7%. Downward (MineDown-direction) acceleration remains
> future work as noted in §5. Results: `PERF-RESULTS-2026-07-03.md` §E4 + ADDENDUM.

**Original status:** designed + implemented in the same session as E3 (the floorGap nibble,
`PERF-DESIGN-navgrid-widening.md`) under the owner's standing ratification for tonight's E3/E4
measurement. Kill switches keep both OFF by default; keep/revert is a measurement decision.

**Owner's hypothesis:** "building cuboids is a hefty part of our startup tax" — per
PERF-PROFILE-2026-07 §3, `GoalForcedCost.probe` extraction is **38–45% of small edit-heavy
searches** (TOWER / UPOVER_WALL), and the per-node Pillar/MineDown macro extractions ride the same
`CuboidExtractor` scans.

## 1. What the extractor actually scans (the audit the prompt demanded)

`CuboidExtractor.extract` (`cuboid/CuboidExtractor.java`) audits as:

- **Uniformity key = the raw 10-bit NAVTYPE id** (`raw[idx] & NAVTYPE_MASK == nav`), NOT a
  kind/flag class. Air dedups to one navtype; same-state stone to one. So the nibble's equivalence
  class MUST be navtype equality — anything coarser over-claims and is unsound.
- **Stage 1** grows a 2-D slab in the plane orthogonal to the travel axis, one edge-row at a time
  (Chebyshev clearance order). For **Y-travel** the plane is (X,Z): no vertical spans — the nibble
  cannot help stage 1 there. For **X/Z-travel** the plane is (Y,Z)/(X,Y): edge rows along the Y
  orth axis are vertical column scans — the nibble applies.
- **Stage 2** extends along the travel axis, re-scanning the WHOLE slab per layer
  (`slabUniform`). For **Y-travel** (Pillar up / MineDown down — the TOWER shape) this is
  `layers × slabArea` reads: the single largest block of extraction work in air columns
  (TOWER: ~19×19 slab × ~32 layers ≈ 11.5k cell reads per probe extraction). For X/Z-travel each
  layer is a (Y,orth) slab whose Y spans are again vertical column scans.
- All cell reads funnel through ONE bulk primitive, `rectUniform`, which decomposes at 16-boundaries
  and scans each section's `short[]` in memory order (x innermost).

**Conclusion: the scan pattern CAN use an upward run-length** — vertical spans appear in exactly
two places (X/Z-travel Y-spans inside `rectUniform`; Y-travel stage-2 layer stepping in
`extract`), and both are the dominant volume in the profiled shapes.

## 2. Field semantics

**`runUp(x,y,z)`** — the number of consecutive cells strictly above `(x,y,z)` in its world column
holding the **same navtype id** as the cell itself, saturated:

| value | meaning |
|---|---|
| `0..13` | exact: cells `y+1 .. y+runUp` share this cell's navtype; cell `y+runUp+1` differs (or is unbuilt/absent) |
| `14` (`SAT`) | proven: at least 14 same-navtype cells above (chain past it by re-reading at `y+14`) |
| `15` (`UNKNOWN`) | no claim (maintenance off / single-section build) — readers legacy-scan |

Recurrence (descending sweep at chunk build): `runUp(y) = nav(y+1)==nav(y) ? min(runUp(y+1)+1, 14) : 0`,
seeded `0` at the top of the built column (unbuilt above = "differs", exactly the hard wall
`rectUniform` reports for a null section). UNKNOWN propagates through the recurrence (a same-navtype
cell under an UNKNOWN is UNKNOWN).

**Storage:** the HIGH nibble of E3's parallel `byte[4096]` (`TraversalGrid.depth`); low nibble =
floorGap. Same cache/locality argument as E3 §3 — the hot `short[]` stays untouched; the extractor's
column check reads ONE byte row in parallel with the bottom `short[]` row (both sequential).

## 3. Read paths (both conservative-fallback, byte-identical results)

1. **`rectUniform` column mode** (X/Z-travel Y-spans; also X/Z stage-1 edge rows along Y): for a
   section sub-box with y-span ≥ 2 (3+ rows), per (z,x) column: verify the BOTTOM cell `== nav`
   (one `short` read), then chain the run nibble to cover the span (one byte read per ≤14 cells;
   saturation re-anchors at `y+14`, which the SAT value has proven `== nav`). `UNKNOWN` ⇒ that
   column falls back to the legacy per-cell scan. Exact-run shortfall (`r < remaining`, `r < 14`)
   ⇒ the cell past the run provably differs ⇒ `false` — identical to the legacy compare failing
   there. Row mode is kept for spans < 3 (locality; nothing to save).
2. **Y-travel stage-2 upward skip** (`extract`): after stage 1 certifies the start-layer slab
   `== nav`, chain each slab column's run upward (capped at the corridor's `maxY`); the minimum
   over columns IS the number of fully-uniform in-corridor layers above — set `tHi` to it in one
   slab-area pass of byte reads instead of `tHi` slab-area short scans, then let the legacy loop
   confirm the stop layer (its next probe fails on the corridor clip or the run-exhausted cell —
   one layer's cost, same as legacy's own failing probe). Any `UNKNOWN` column ⇒ skip nothing,
   full legacy loop. Downward (`tLo`) stays legacy — the field is upward-only (MineDown's descending
   extraction would need a downward run; out of nibble budget, noted as future work).

Byte-identity argument: the run nibble is a memoization of navtype-equality over the same resident
`short[]` the legacy compare reads; the extractor reads COMMITTED state only (`PathEdits` is applied
later by `NavGridCuboidsView`), so no edit gate is needed at all. Fresh values ⇒ identical box;
`UNKNOWN` ⇒ legacy scan ⇒ identical box; the returned `Cuboid` (not the read pattern) is the whole
observable surface. Verified by the identity harness (OFF vs E3+E4 across all scenarios) plus a
brute-force unit test of run values incl. seams and patches.

## 4. Write path

- **Build:** a descending pass folded into E3's column sweep (`NavSectionBuilder.computeDepth`),
  gated by its own flag `TraversalGrid.RUN_MAINTENANCE`. One navtype compare + byte store per cell.
- **Patch (`patchCell`):** recompute `runUp` at the changed cell (reads the cell above, ≤1 seam up
  for the read only), then propagate DOWNWARD with fixpoint early-out, ≤15 cells, crossing ≤1 seam
  into `below` — the exact mirror of E3's upward floorGap propagation, riding the same s42 vertical
  machinery (`patchCell` already receives `above`/`below`). Saturation at 14 guarantees fixpoint
  within 15 steps on maintained columns; the loop is hard-capped there.
- Priced by the same `PatchStormBenchmark` arm split (OFF / E3 / E3+E4) so E4's marginal
  maintenance cost is visible separately.

## 5. Expected effect / where it must show

Per-search cuboid extraction is per-search work ⇒ the win should appear in the small edit-heavy
scenarios (TOWER, UPOVER_WALL, UPOVER_OPEN) and in SHORT/MULTI only via their probe extractions
(SHORT's probe is over flat ground/air — small boxes). TOWER's probe alone drops ~11.5k short reads
→ ~1.5k byte/short reads by the §1 arithmetic. OPEN/FLOOD have no corridor ⇒ no cuboids ⇒ expected
exactly neutral. Keep bar: the standing ≥3% targeted win, no scenario regressing beyond noise,
patch-storm gate green.

## 6. Flags

| flag | side | default |
|---|---|---|
| `TraversalGrid.DEPTH_MAINTENANCE` | write floorGap (E3) | off |
| `TraversalGrid.RUN_MAINTENANCE` | write runUp (E4) | off |
| `Fall.FLOOR_GAP` | read floorGap (E3 consumer) | off |
| `CuboidExtractor.RUN_ACCEL` | read runUp (E4 consumer) | off |

All-off = byte-identical to today (arrays exist but hold UNKNOWN and are never read). Contract: a
consumer flag must only be enabled on grids built AND continuously patched with the matching
maintenance flag on (flipping maintenance off mid-run leaves stale exact values a later consumer
would wrongly trust — flags are set once at startup / per benchmark JVM).
