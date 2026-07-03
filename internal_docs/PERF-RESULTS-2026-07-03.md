# Perf experiment results — overnight run 2026-07-03

Five owner-ratified experiments, each: implement behind default-OFF flags → verify byte-identity →
paired interleaved A/B (3 full-suite JMH runs per arm, fresh JVM per run, suspicious deltas re-checked
with pinned `-Pscenario` fresh-JVM pairs). Baseline includes the uncommitted s43 #12/#13 feature diff.
All experiment code lives in the authoring (main/26.x) worktree, flags OFF; the mc-1.21 bench worktree
was restored clean afterward (bench-only build-script hooks preserved as
`internal_docs/bench-harness-mc121-buildscript.patch` — `-Parm` JMH passthrough + `:1.21.4:coldstart` task).

## Summary table (headline metric per experiment)

| # | Experiment | Flag(s) | Headline result | Guards (SHORT / MULTI / patch-storm) | Recommendation |
|---|---|---|---|---|---|
| E1 | Edit-bbox gate, 3-component envelope | `BlockPathfinder.EDIT_GATE=FULL` | **FLAT** — FLOOD −0.23% (inside ±2.1% run spread) | flat / flat / n.a. | **REVERT** (pre-registered bar) |
| E2 | Edit-bbox gate, single-box (owner variant) | `EDIT_GATE=SINGLE` | **FLAT** — FLOOD +0.12% | flat / flat / n.a. | **REVERT** |
| E3 | floorGap nibble (depth-below) + Fall consumer | `TraversalGrid.DEPTH_MAINTENANCE` + `Fall.FLOOR_GAP` | FLOOD **−5.1%**, CLIFFS **−4.3%**, TOWER **−3.4%** | **SHORT +1.2% (pinned, real)** / flat / ≤−3.0% | **KEEP w/ caveat** |
| E4 | runUp nibble → CuboidExtractor accel | `TraversalGrid.RUN_MAINTENANCE` + `CuboidExtractor.RUN_ACCEL` | TOWER **−33.7%**, UPOVER_OPEN **−35.5%**, UPOVER_WALL **−30.4%**, MULTI **−32.3%** | +0.9% (shared w/ E3) / **−32.3%** / ≤+1.8% | **KEEP** |
| E5 | NavWarmup boot warm-up | `pathing.warmup` (**default true — LIVE**) | First search **21.8 ms → 0.67 ms p50 (32×)**, p90 30.0 → 0.81 ms (37×); boot cost 475 ms median | steady-state suite flat by construction (verified) | **KEEP** |
| E5b | Nodes eager-size one-liner | — | SHORT **+4–7%** (pinned, real): `reset()` fills map over CAPACITY | — | **ALREADY REVERTED** |

## E1/E2 — why the gate is dead (the p=0 finding)

A throwaway counter probe measured the disjoint-pop fraction at **p = 0.000 in every scenario**
(FLOOD: 10,001 pops, 9,559 edit-bearing, 0 disjoint). The pillar-flood pop **stands on its own placed
block** — C1 hits at distance 0, and even the ±6 single box always contains the pop. "Edits trail
behind the path" is false for every shape the search produces; the 49% kindAt tax is real but paid
entirely by pops that are never envelope-disjoint. Consequences:
- Any future gate needs per-row/incremental chain bboxes or a recent-edits-only overlay, not a
  whole-chain bbox. The widening doc's phase-2 "edits-bbox-gated neighbor prefilter" premise is dead
  as-designed.
- E2 detail: the sound single box **degenerates to a 4-compare horizontal-only test** — Fall/Swim
  column scans are statically unbounded in Y (IMMUNE_FALL=4096 / terrain-bounded), so no finite Y
  margin is sound.
- **Kept from the work**: the FLOOD JMH scenario (the suite finally sees the warm S3 shape — note the
  old profile FLOOD shape now resolves in ~688 pops; FLOOD uses a goal above the built ceiling) and
  `EditGateIdentityTest` (mode-independent plan-identity harness). Gate code itself is deletable.

## E3 — floorGap (per-scenario, µs/op, mean of 3, OFF → E3)

| Scenario | OFF | E3 Δ% | E3+E4 Δ% |
|---|---|---|---|
| TOWER | 75.30 | −3.4% | **−33.7%** |
| OPEN | 23.81 | −1.6% | −1.9% |
| UPOVER_OPEN | 162.5 | −0.8% | **−35.5%** |
| UPOVER_WALL | 119.6 | −0.1% | **−30.4%** |
| SHORT | 13.23 | +1.1% (pinned +1.2%) | +0.9% (pinned +2.1%) |
| MULTI | 356.6 | −1.2% | **−32.3%** |
| FLOOD | 14,770 | **−5.1%** | −5.2% |
| CLIFFS (new) | 25.86 | **−4.3%** | −3.5% |

- Storage: parallel `byte[4096]` per section (low nibble floorGap 0–13/14=proven-none/15=UNKNOWN→legacy,
  high nibble runUp), hot `short[]` untouched — per the widening doc's anti-int[] verdict.
  Chunk-column-exact (build pass 3 `NavSectionBuilder.computeDepth`); patchCell upward (floorGap) /
  downward (runUp) fixpoint recompute, ≤15 cells, ≤1 vertical seam.
- SHORT caveat mechanism: SHORT walks at y==minY where the legacy scan terminates on a nearly-free
  negative-section UNBUILT read, so the fg==0 nibble read (second section resolve) is at worst-case
  relative price. Identified fix if SHORT is weighed strictly: fold the floorGap read into the flags
  read's section resolve (one combined slot+depth read).
- E3's FLOOD ceiling is structural: edit-bearing pops' AABBs overlap the scan columns, so the
  edits-disjoint column check correctly declines the nibble (consistent with E1's p=0).
- Patch-storm (ns/patch, median of 3): SCATTER 1789→1754/1822, DIG 1536→1507/1564, TOGGLE 1419→1377/1416,
  SEAM 2097→2062/2123 (OFF→E3/E3+E4). **Gate PASS** (worst +1.8% vs ≤+10% bar). First-ever baseline
  patch cost: ~1.4–2.1 µs/patch.

## E4 — runUp cuboid acceleration (the headline)

CuboidExtractor's uniformity key is the **raw 10-bit navtype** (not a kind class), so the high nibble
is an upward same-navtype run (saturate 14, seeded 0 at built-column top = exactly `rectUniform`'s
null-section wall). Two consumers: `rectUniform` column mode (bottom-row read + run-chain per (z,x)
column) and Y-travel stage-2 `runSkipUp` (slab pass of run-chains computes the legacy loop's exact
`tHi`). Downward (MineDown direction) stays legacy — future work, needs bits the byte doesn't have.
Result: **~75–80% of the extraction bill removed** on cuboid-bearing scenarios = −30…−36% total search
time (extraction was 38–45% per the JFR profile — the owner's "cuboid startup tax" hypothesis
confirmed). Design doc: `internal_docs/PERF-DESIGN-runup-nibble.md`.

Identity: `DepthIdentityTest` (all flag combos × 6 scenarios, bit-exact costs/waypoints/edits equal) +
`DepthNibbleTest` (brute-force vs nibble incl. seam patches + 600-patch storm). Suite 137/0.
**Contract**: consumers only over grids built AND continuously patched with matching maintenance —
flipping read flags ON requires the write flags ON for live grids (defaults must flip together).

## E5 — NavWarmup (fresh-JVM cold-start harness, 10 runs/arm interleaved, 0 discarded)

| Arm | first p50 | first p90 | first range | 2nd med | 3rd med | warm-up cost |
|---|---|---|---|---|---|---|
| OFF | 21.81 ms | 30.00 ms | 15.8–30.1 ms | 1.26 ms | 0.85 ms | — |
| ON | **0.67 ms** | **0.81 ms** | 0.32–1.01 ms | 0.20 ms | 0.12 ms | 475 ms (372–527) |

- Measured first search = CLIFFS (a shape/caps the warm-up never literally ran — honest vs profile
  pollution). Freshness proven: control always in the 15–30 ms cold band, worker uptime ~0.95 s.
- `NavWarmup` (~250 lines, cold): 5 pooled sections/81-chunk fixture via `TraversalGrid.set` +
  `computeFlags`, public `NavGridView.overSections`; per round 2 budget-floods + 6 wall + 6 water +
  50 production-shape SHORT; plateau-detected (median 6 rounds / 384 searches / 475 ms; never hit the
  1500 ms cap). Hook: third `onServerStarted` handler (after ConfigLoader, MiningModel), synchronous
  (NavSectionPool unsync'd; ThreadLocal scratch must grow on the tick thread). Config:
  `pathing.warmup` (**default true**) + `pathing.warmupBudgetMs` (1500).
- **Nodes(8192,8192) one-liner falsified + reverted**: `Nodes.reset()` does `Arrays.fill(mapRow,-1)`
  over CAPACITY → every flood-free search pays +28 KB of fill → pinned SHORT +4–7%. Any future eager
  sizing needs lazy clearing / epoch-stamped slots — a design pass, not a one-liner.
- Remaining pre-ship check (doc §7): in-game `runClient` first-search + world-open UX delta —
  the headless harness can't see chunk-cache cold misses. `pathing.warmup=true` is LIVE for the next
  in-game session.

## Decisions for the owner

1. **E1/E2**: delete the gate code (keep FLOOD scenario + EditGateIdentityTest)? Both flat, p=0.
2. **E3**: keep — accept SHORT +1.2%, or require the fold-into-flags-read shaving first?
3. **E4**: keep — flip `RUN_MAINTENANCE`+`RUN_ACCEL` default ON (with E3's write flag if E3 kept;
   maintenance+consumer defaults must flip together)?
4. **E5**: `pathing.warmup` default true is live; confirm after the in-game smoke.
5. Commit dance ordering: these experiments sit atop the uncommitted #12/#13 feature diff — decide
   whether to verify+commit features first, then flip+commit ratified experiments, or fold together.

Raw JMH/coldstart logs: session scratchpad `bench/` (run{1..9} logs, pinned re-checks, coldstart_*.txt,
e5_* logs). Bench agents: interleaved ×3 per arm throughout; forks=0 JIT-pollution rule honored via
pinned fresh-JVM re-checks on every suspicious delta.

---

## ADDENDUM (2026-07-03, adoption + cleanup executed)

Owner ratified: **E1/E2 gate code DELETED** (FLOOD/BRIDGE/SPIRAL/CLIFFS scenarios + PatchStormBenchmark
kept; EditGateIdentityTest deleted with the modes it tested), **E3+E4 made UNCONDITIONAL** (all four
flags removed; UNKNOWN(15)→legacy fallback kept as the single-section correctness regime;
DepthIdentityTest repurposed to maintained-vs-erased-to-UNKNOWN identity — green), **NavWarmup now
sweeps `computeDepth`** (depth parity with live grids; wall column got its own slot-1 air section so
instance-shared cells keep identical depth), **E5 stays config-gated** (`pathing.warmup`).

### Post-cleanup full suite (µs/op, mean of 3) and the two comparisons

| Scenario | post-cleanup | vs OFF baseline (a) | vs E3E4 arm (b) |
|---|---|---|---|
| TOWER | 49.98 | **−33.6%** | +0.1% |
| OPEN | 24.11 | +1.3% | +3.2% |
| UPOVER_OPEN | 105.20 | **−35.3%** | +0.4% |
| UPOVER_WALL | 85.43 | **−28.6%** | +2.6% |
| SHORT | 13.61 | +2.9% | +1.9% |
| MULTI | 243.05 | **−31.8%** | +0.7% |
| FLOOD | 13,995 | **−5.2%** | −0.1% |
| CLIFFS | 25.49 | −1.4% | +2.2% |
| BRIDGE | 31.68 | (no baseline — raw logs purged) | — |
| SPIRAL | 173.21 | (no baseline) | — |

Comparison (b) crosses a JVM/day boundary; pinned fresh-JVM interleaved pairs (×3/arm, same day)
resolve the offsets:

- **SHORT pinned**: cleanup 12.95 vs flagged-E3E4 12.84 (**+0.9% — noise**) vs flagged-OFF 12.65
  (**+2.4%** — the accepted E3 nibble cost; last night's pinned was +2.1%). Pinned flagged-OFF today
  (12.65) reproduces last night's pinned OFF (12.6) almost exactly — pinned cross-day drift ≈ 0; the
  full-suite (b) offsets are forks=0 same-JVM ordering artifacts.
- **Flag-cost verdict (owner's hypothesis)**: removing the mutable static flags + gate branches is
  **FLAT** — under forks=0 C2 speculates a never-changing static boolean to a constant, so the dead
  flags cost nothing measurable. The ~1.2 ms in-game SHORT overhead is NOT flag overhead.
- **OPEN pinned**: cleanup 21.83 vs flagged-E3E4 21.06 (**+3.6%, consistent 3/3 rounds — real**).
  Mechanism: the deleted gate's per-pop `editsRelevant` boolean was incidentally a CHEAPER per-read
  test than the restored pre-experiment `!pathEdits.isEmpty()` (one context-field bool load vs a
  PathEdits deref + size compare) on **edit-free** searches; on edit-bearing searches `kindAt`
  dominates (FLOOD/TOWER/MULTI flat). Also explains the CLIFFS/UPOVER_WALL (b) offsets. **Follow-up
  design candidate** (owner review first, per protocol): re-hoist `editsRelevant = !pathEdits.isEmpty()`
  once per pop — the hoist alone, no envelope/bbox code — for ~3% on edit-free searches.

### Patch storm post-cleanup (ns/patch, unconditional maintenance)

SCATTER 1821 / DIG 1573 / TOGGLE 1455 / SEAM 2126 — matches the E3E4 arm (1822/1564/1416/2123);
worst vs the old no-maintenance baseline +2.7% (TOGGLE), inside the ≤+10% bar.

Raw logs: session scratchpad `post_run{1..3}.log`, `pin2_short_*`, `pin_open_*`, `post_patchstorm.log`.

---

## ADDENDUM 2 (2026-07-03, follow-up hoist measured — FALSIFIED, reverted)

The ADDENDUM's follow-up candidate (re-hoist `editsRelevant = !pathEdits.isEmpty()` once per pop — the
hoist alone, no envelope/bbox) was owner-ratified, implemented (~10 lines: a package-private
`MovementContext.editsRelevant` boolean, default TRUE for out-of-loop consumers, set per pop after the
edit-chain rebuild; `descriptorAt`/`descriptorOf` test it instead of `!pathEdits.isEmpty()`), verified
byte-identical (full-signature dump — expansions/status/cost-bits/waypoints/moves/edits — over
CLIFFS/FLOOD/TOWER/UPOVER_OPEN/UPOVER_WALL/SHORT, hoist vs no-hoist: identical; suite 137/0), and
benched with pinned fresh-JVM interleaved pairs. **The +3.6% OPEN finding did NOT reproduce — the hoist
is a small consistent REGRESSION on edit-free searches. REVERTED per protocol.**

| Pinned scenario (µs/op, mean) | no-hoist | hoist | Δ | pairs |
|---|---|---|---|---|
| OPEN ×3 pairs | 21.89 (21.81/22.04/21.81) | 22.07 (21.92/22.27/22.00) | **+0.8%** | hoist slower 3/3 |
| SHORT ×3 pairs | 12.91 (12.88/12.95/12.91) | 13.00 (12.95/13.01/13.04) | **+0.7%** | hoist slower 3/3 |
| CLIFFS ×2 pairs | 24.34 (24.66/24.01) | 24.49 (24.87/24.10) | **+0.6%** | hoist slower 2/2 |

No-hoist baselines reproduce this doc's clean pinned numbers to <0.5% (OPEN 21.89 vs 21.83, SHORT 12.91
vs 12.95), validating the measurement window. Conclusion: the flagged-arm's +3.6% OPEN advantage was NOT
the per-pop boolean itself — most plausibly JIT code-layout/inlining differences from the (now deleted)
gate machinery surrounding the same sites. The per-read `!pathEdits.isEmpty()` (field load + int compare,
perfectly predicted) is already effectively free; replacing it with a context-field boolean bought nothing
and cost a hair. **Do not re-attempt the bare hoist; any future revival needs a new mechanism hypothesis.**

Measurement notes: (a) runs taken while a `runClient` session was live-but-paused measured clean (tight
iteration spreads, baselines matching this doc) — the full-suite pair that caught ACTIVE gameplay was
discarded (its no-hoist tail inflated: CLIFFS 27.7 / BRIDGE 38.4 / SPIRAL 212±32); (b) post-session
re-runs were unusable because the machine had been switched to BATTERY power (uniform ~2.5× inflation,
huge variance, unaffected by P-core affinity pinning) — **do not bench this laptop on battery.**
