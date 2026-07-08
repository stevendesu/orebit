# s53 sync trace run — jungle-flood diagnosis (2026-07-07/08)

Source: `scripts/run-autotest.ps1 -Trace -BotDebug -BudgetTicks 3000` on `wip/s52-mc121`,
seed -6453171316297072906, start (-3,125,-28) → goal (201,-28,90), **pathing.async=false**
(sync, node cap 10001, PARTIAL_PATH on). 12 searches traced; PNGs are
`internal_docs/trace_analysis.py` 4-panel outputs.

## Verdict on the owner hypotheses

**(b) jungle-canopy lattice flood — CONFIRMED. (a) overshoot past the window target — REFUTED.**

- No search's bounding box spills meaningfully past its window target: trace 001's bbox tops
  out at x=15 vs goal x=16 (zero overshoot); 011/012 reach x=70 vs goal x=64 (≤6-block
  Chebyshev overspill, not a next-region flood).
- The expansions instead smear VERTICALLY across the canopy Y-levels around the corridor:
  001 explores y[103..134] (+10 above a start that needs −12), 011/012 explore y[98..127]
  (+14 above a start that needs −1). Ascend+Traverse account for 60–83% of expansions;
  on-goal-column nodes are 0% (001, 005) / 0.25% (011, 012).
- Region-field pruning contributes nothing here: every traced candidate carries `prem=0.0`
  (start and target both inside the field's box), so the search runs on bare octile over a
  dense 3D lattice of standable leaf surfaces.

## Files

| Trace | Search | Start → Goal | Expansions | Result | Notes |
|---|---|---|---|---|---|
| 001 | #1, tick 1 | (-3,124,-28) → (16,112,-25) | 5699 | FOUND-29wp (948 ms, trace I/O + first-search) | The jungle spawn search. bbox x[-12..15] y[103..134] z[-32..-17]; 64% of nodes BELOW start, 30% above; Traverse 46% + Ascend 37%; 0% on-goal-column; f-frontier 231→501. |
| 005 | #5, tick 131 | (-1,113,-29) → (42,112,-28) | 2923 | FOUND-42wp | First corridor window after descent. bbox y[111..124]; 65% of nodes ABOVE start for a −1 goal; Ascend 46% + Traverse 38%. |
| 011 | #11, tick 303 | (32,113,-30) → (64,112,-25) | 4334 | FOUND-30wp | DIG-kind window hop. bbox y[98..127]; 52% above / 42% below start for a −1 goal; MineDown 11% + Pillar 10% join the smear; 11 on-column nodes (0.25%). |
| 012 | #12, tick 332 | (38,113,-30) → (64,112,-25) | 4312 | FOUND-24wp | Same window, re-search after 6-block advance — near-identical shape to 011 (NOT a duplicate-start double-search; starts differ). Its plan's step 0 `Parkour d(4,1,0)` is the move whose undershoot ended the run. |

## Other findings from the run

- **No PARTIALs, no cap hits**: all 12 searches FOUND; max 5699 nodes vs the 10001 sync cap.
- **The async 9k/10.7k double-search from (-3,108,-28) did NOT recur** — under sync the
  route differs (search #1 fires at tick 1 from the treetop y=124; no search from y=108 ever
  happens) and no two traced searches share a start cell. The only same-start pair is search
  #1's tick-1 predecessor: a 0-node `no-start-ground` attempt (bot still airborne at spawn)
  → `drive: HOLD (window BLOCKED)` → the real 5699-node search next tick. Points to the
  double-search being an async-submit artifact (though sync never reproduced the y=108 start,
  so this is non-recurrence, not direct disproof).
- **E2E: FAIL (tick budget exhausted).** Bot advanced (-3,125,-28) → (42.5,102,-29.5)
  (~45 blocks) in ~350 ticks, then stalled for the remaining ~2650 ticks. Cause: plan step 0
  `Parkour d(4,1,0)` from (38,114) — a RISING 3-gap, the documented takeoff-undershoot
  pathology — undershot (`exec Parkour ... (air) feetY=114.42` → `(ground) feetY=102.00`),
  dropping the bot ~12 blocks into a pit below the gap. One diagnostic `STUCK` dump at
  23:58:57, then no replan/no recovery (by s52 design): `wp=0/24`, `navGaveUp=false`,
  Parkour phase target (42,115,-30) held to tick 3000. The async runs' "DiagonalParkour pit
  failure" recurs under sync **in kind** (parkour-family undershoot → pit → permanent stall),
  via rising Parkour rather than DiagonalParkour on this route.
