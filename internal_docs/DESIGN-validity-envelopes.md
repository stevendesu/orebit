# Phase Execution, Validity Envelopes & the fail→log→HOLD Policy

**Status: SHIPPED (2026-07-23 movement-pathologies arc; uncommitted in the mc-1.21 worktree at
authoring).** The canonical doc for the follower's phase-execution vocabulary and the per-move
validity-envelope machinery that replaced the last of the "reached-fired-so-we're-fine" follower
assumptions. Cited from `MOVEMENT-DESIGN.md` and code Javadocs. Evidence trail: the
`PATHOLOGY-P1-diagonalparkour-wedge.md` / `PATHOLOGY-P1B-break-not-executed.md` /
`PATHOLOGY-P4-abandoned.md` reports (scratchpad, 2026-07-23) — the 27,400-tick silent
DiagonalParkour latch, the break-at-feet frame drift, and the ABANDONED flood.

**Where the code lives:**
- `src/main/java/com/orebit/mod/pathfinding/blockpathfinder/MovePlan.java` — the declarative plan
  (phases, needs, `resetWhen`, `failWhen`, door reqs)
- `.../blockpathfinder/PhaseRunner.java` — the per-tick runtime (`run`, `failed()`, `doneNow(bot)`)
- `.../blockpathfinder/Movement.java` — the grounded-gated default `reached`
- `.../blockpathfinder/movements/` — per-move `plan()` bodies carrying the envelopes:
  Parkour, DiagonalParkour, Ascend, Traverse, Descend
- `.../blockpathfinder/BlockPathPlan.java` + `BlockPathfinder.reconstruct` — the floor-frame carry
- `.../blockpathfinder/SteerControl.java` — gate-point steering (`steerViaGate`/`pastGate`)
- `src/main/java/com/orebit/mod/BotNavigator.java` — the FAILED→HOLD wiring, the ABANDONED
  tripwire, `floorOf` (live-position-only)
- `src/main/java/com/orebit/mod/AllyBotEntity.java` (`place`) + `BotMining` (break completion) —
  edit-attribution logging

---

## §1 The phase-execution vocabulary (current, complete)

A converted movement's `plan(fx,fy,fz, tx,ty,tz)` builds a `MovePlan` ONCE per step, in the step's
**search-native floor cells** (§6). The plan is a list of `Phase`s plus three plan-level guards:

- **Phase = `need` × `drive` × `advanceWhen` / `done`.** Needs are geometry requirements the runner
  establishes against the LIVE world each tick before the phase may drive:
  - `Need.AIR` — the cell must be clear; while `bot.solidAt(x,y,z)` (a **live collision-based**
    test on the real level, so it sees the bot's own just-made edits) the runner calls
    `bot.mine(...)` — timed, real-tool, ONE break per tick — and **holds** (recenters on the
    column, no drive).
  - `Need.FOOTING` — the cell must be solid; while `!solidAt` the runner calls `bot.place(...)`
    (instant) and holds one tick to re-validate.
  - `Need.OPEN` (doors, plan-level via `requireDoor`) — validated with `doorOpenAt`, NOT `solidAt`
    (an open door keeps a thin collision box); a door cell is never mined by an AIR need.
  Needs are re-checked every tick → a missed edit self-heals; the hold suppresses the drive (and
  any jump), so mine-then-move sequencing is structural, not scripted.
- **`resetWhen`** — the regression guard: physically back at the start after genuinely leaving it
  → cursor snaps to phase 0 (the legitimate balk-at-start retry). Checked FIRST each tick.
- **`failWhen`** — the **validity envelope** (§2). Checked AFTER `resetWhen` (the reset cell is
  always inside the envelope's allowed set, so both can't be true) and BEFORE doors/needs/drive —
  a plan whose frame reality has left must never mine, place, or press inputs. Firing sets the
  runner's `failed()` flag and drives nothing.
- **`PhaseRunner.doneNow(bot)`** — pure accessor: is the cursor on the TERMINAL phase and is its
  `done` true on the CURRENT bot state? Exists because `run()`'s return is necessarily one tick
  stale at the follower's advance boundary (§5).

Per-tick order in `PhaseRunner.run`: `resetWhen` → `failWhen` → door reqs → needs (hold if any
unmet) → `drive` → terminal `done` / `advanceWhen`.

## §2 The validity-envelope design rule

> **The allowed set is exactly the cells the move's execution can LEGITIMATELY occupy while
> settled. A bot that is SETTLED — grounded, or bodily in fluid — at a foot cell outside that set
> is off-plan: no phase's `advanceWhen`/`done` can ever fire from there, so re-attempting in place
> is a permanent latch by construction. Purely state-derived predicates over cells the plan already
> carries — NO timers, NO motion signatures, NO world reads in the predicate.**

The "settled" clause matters: airborne ticks are exempt (a jump arc is not displacement), and the
fluid arm (`inWater() || inLava()`) exists because a displaced GROUND-move executor that fell into
water is never "grounded" and would otherwise churn forever below the envelope's radar (the
longrun-5 under-wall water-pocket latch). Ground-only moves whose legitimate path can cross shallow
water (Traverse case A) discriminate by the LINE test, not the medium.

Why this exists at all: the phase framework originally had **no failure vocabulary** — `run()`
returned only done/not-done, and every driver-side replan trigger is gated on
`currentFloor.equals(settledFloor)`. A committed move that mispredicted onto an off-plan cell was
the s52-documented "DEFERRED KNOWN RISK"; PATHOLOGY-P1 saw it in-game (27,400 ticks of total
diagnostic silence), and the envelope is the state-derived resolution that Javadoc anticipated.

## §3 The shipped per-move envelopes

All predicates are settled-gated per §2 unless noted; cells are the plan's own frame (from-floor
`(fx,fy,fz)`, to-floor `(tx,ty,tz)`; a stand/feet cell is floor+1).

| Move | Allowed settled foot cells | Notes |
|---|---|---|
| **Parkour** | takeoff stand `(fx,fy+1,fz)`; landing COLUMN band `(tx, min(fy,ty)+1 .. max(fy,ty)+1, tz)` | grounded-only gate (committed jump). The band admits a falling arc's descent touchdowns and degenerates to the exact landing stand for flat/rising. Cannot fire in normal execution: runup/takeoff are grounded ON the takeoff stand (`TAKEOFF_EDGE` 0.35 keeps the centre in-cell), airborne ticks aren't grounded, touchdown is on the column. |
| **DiagonalParkour** | exactly TWO cells: takeoff stand + landing stand | flat-only v1 → no descent column. Sound only because the takeoff is now **gate-triggered** (§8): the jump fires while the centre is still ≥ BODY_RADIUS inside the takeoff cell, so the grounded foot cell can no longer spill into the diagonal's ground-level spill cell (the fail→replan churn specimen at (169,81,-38); with the pre-gate `projRaw ≥ 0.566` trigger the runup overshot into a SOLID diagonal cell outside the set). |
| **Ascend** | FROM column feet band `(fx, fy+1 .. ty+1, fz)`; landing stand `(tx,ty+1,tz)` EXACT | fluid clause per §2 (water-leaving climbs legitimately swim the from-band). Settled in the target column BELOW the landing stand = fell short into a hole/fluid pocket = off-plan. Plus the **contract tripwire**: `plan()` handed a frame with `ty != fy+1` returns a phase-less plan with `failWhen(b -> true)` — detection of the P1B floor-drift fiction, never executes anything. |
| **Traverse case A** (flat run, incl. macro/bridge) | any foot cell ON the run line: height `fy+1`, cross-axis pinned, along-projection in `[0, n]` | the line test (not the medium) discriminates — running through shallow water is legitimate. Catches the fell-off-the-ledge latch (longrun-6, bot 3 below its Traverse, ground-looping forever). |
| **Traverse case B** (step-assist +1) | from stand `(fx,fy+1,fz)`; target column band `(tx, ty..ty+1, tz)` | the auto-step's rise crosses the lower foot cell for a tick — the band admits it. |
| **Descend** | from stand `(fx,fy+1,fz)`; dest COLUMN feet band `(tx, fy..fy+1, tz)` | `fy` = the destination stand (shallow-water landing is in-fluid there — inside); `fy+1` is the **LIP TRANSIT**: stepping off, the centre crosses into the dest column while the box is still grounded on the from-block's lip — a legitimate mid-step state, NOT displacement. Omitting it produced the longrun-8 first-hold false positive; the general lesson: enumerate the transit states of the move's own physics before declaring the set. |

**Not yet enveloped** (legitimate-state analysis pending, same idiom): the swim family
(Swim/SprintSwim/StartSprintSwim/Surface/DiagonalSprintSwim), Pillar, WalkOff, Fall, Climb,
MineDown. Fall's default `reached` is also deliberately ungated (§5) — it is not committed and its
landing may be buoyant water.

## §4 The fail→log→HOLD policy (owner-ratified 2026-07-23)

When `phaseRunner.failed()` fires, `BotNavigator.steerAlongPath` does NOT replan. It logs **once
per failed step** (ungated `LOGGER.info` — rare and meaningful, the STUCK/logRepair idiom:
`"[Orebit] step FAILED (validity envelope) <move> step <i> phase <p>/<n> bot=<pos> — holding"`),
zeroes forward, and **HOLDs with the failed plan frozen for inspection**. Rationale: a deviation
from the plan is either external force (a future repair concern) or a MOVEMENT BUG, and while the
movement pathologies are being burned down a silent auto-replan would MASK exactly the bugs the
envelope exists to surface. The bot stalls visibly at the failure point; the log carries the repro.

**The documented future step** — once the failure classes are fixed — is flipping this branch back
to `clearPlan()`: the FAILED verdict then flows into the existing planless-adoption path
(`driveToward`'s `pathPlan == null` branch → `replan()` from the LIVE floor, re-anchoring
settledFloor/planStartFloor) — normal existing machinery, no new recovery. (`clearPlan()` is the
required form: clearing only the path dead-ends, because `PathPlan.pollWhenPlanless` early-outs
while a block plan is held.) An earlier iteration of the arc shipped exactly that auto-replan
wiring and it is proven (longrun 5→7 latch-elimination sequence); the HOLD is a deliberate
diagnostic posture, not a missing feature.

## §5 Grounded-gated `reached` + the doneNow ABANDONED tripwire

- **Cursor advance (Fix B).** The default `Movement.reached` is now
  `(!commitsAcrossArrival() || b.grounded()) && exact-feet-block match`. A committed move
  (Parkour/DiagonalParkour/WalkOff) therefore advances only from a real touchdown — the airborne
  fly-through match that adopted a step from mid-arc (and **poisoned `settledFloor`** with a stand
  cell the bot never occupied, freezing every settled-gated replan trigger forever) is dead. A
  landing is grounded by definition, so the legitimate advance is delayed zero ticks. Uncommitted
  moves keep the ungated exact match byte-identically; RideBubbleColumn has its own override.
- **ABANDONED (the P4 fix).** The `"ABANDONED <move> … (reached fired before done)"` VERBOSE line
  is a tripwire for a cursor that moved on while the outgoing step's plan was mid-flight. Its old
  comparison read `lastPhaseDone` — last tick's `run()` return — which is structurally one tick
  stale: any move whose terminal `done` and `reached` flip on the SAME physics state (every
  single-phase converted move — the sprint-swim flood, 258 lines/run — and grounded-gated parkour
  landings) logged on 100% of normal completions. The tripwire now also requires
  `!phaseRunner.doneNow(bot)` — a fresh, pure evaluation of the outgoing plan's terminal `done` on
  the CURRENT state. Same-state completions log nothing; ABANDONED is back to being the rare-event
  correctness tripwire it was written as (258 → 18 in the longrun A/B). The residual owed-Needs
  gate was deliberately skipped: doneNow alone kills the false-positive classes, and a needs gate
  would silence genuinely-abandoned zero-need plans.

## §6 The floor-frame carry (PATHOLOGY P1B)

**Rule: the follower never re-derives a step's floor from its waypoint; it uses the floor the
search already knew.**

- `BlockPathfinder.reconstruct` fills a per-plan `int[] floorYs` (SoA, parallel to the waypoints —
  X/Z ride the waypoint, only Y can differ) from the node coords it already has, in both the
  single-waypoint and macro re-expansion branches. Search-side structs untouched; reconstruct is
  post-search/cold → expansion order and returned paths byte-identical.
- `BlockPathPlan` carries `floorY(int)` (alloc-free) / `floor(int)` (BlockPos convenience, cold
  sites). Seam-adoption/splice adopt whole `BlockPathPlan` objects, so floors ride through the
  async/splice path with zero seam changes.
- `BotNavigator.steerAlongPath` builds every step's `MovePlan` frame from CARRIED floors
  (`toFloor = (wp.x, path.floorY(i), wp.z)`; from-floor likewise for steps ≥ 1; step 0 keeps
  `planStartFloor`/live-floor). The settle anchor on step completion and the preplan seed use
  `path.floor(...)` too.
- **`floorOf` is restricted to LIVE-position inversion** (settle/adoption anchors, damaging-floor
  probe): a real standing bot's feet cell is never solid, so inverting real feet is exact there.

The pathology this kills: `floorOf(waypoint)` returned the feet cell ITSELF whenever that cell was
"standable" — which a **break-at-feet step's** feet cell is, being solid until the plan's own AIR
needs mine it. The whole frame drifted +1: the block that should be MINED became the FOOTING
(never mined by anything), and CLIMB commanded a physically impossible 2-block jump forever
(the (87,63,-32) shore-wall jump-loop, 15k ticks, 1324 regressions). The Ascend `ty == fy+1`
contract tripwire (§3) is the belt-and-braces detector should any caller ever hand a drifted frame
again. Regression oracle: `BreakAtFeetFloorCarryTest` (carried-floor vs old-derivation equivalence
on air/partial feet; the drifted frame trips the tripwire; the carried frame mines exactly the
folded breaks).

## §7 Edit attribution logging (P7 instrumentation)

Both real-world-edit funnels log one ungated line per ACTUAL edit (bounded by world edits, never
per-tick):

- `AllyBotEntity.place` (after a successful `WorldEdits.placeBlock`, consume and conjure branches):
  `[Orebit] place executed at (x,y,z) for step <move> -> (segTo)`.
- `BotMining` break completion (after `destroyBlock`):
  `[Orebit] break executed at (x,y,z) for step <move> -> (segTo)`.

This is the discriminator for unattributed placements (the silent 4-tall cobble pillar class): a
planned pillar to an air window target vs the PhaseRunner FOOTING reconcile vs Ascend's build
drive now each leave a named trail.

## §8 Gate-point steering (SteerControl) — the corner-geometry primitive

Ratified insight: a diagonal hug past a blocked corner and a diagonal-parkour takeoff approach are
the SAME geometry — pass NEAR a corner point. Straight line-tracking either presses into the
corner (the hug freeze) or spills laterally off the takeoff cell (the churn specimen). Hitbox
overhang matters only while grounded; support matters only at the jump tick → aim via a GATE point
and act at the gate crossing.

- **`SteerControl.steerViaGate(b, sx, sz, tx, tz, gx, gz)`** — two-leg point pursuit: aim the GATE
  while short of it, the real TARGET once past. Velocity-servo actuation (desired =
  unit(aim−pos)·`SERVO_GROUND_CRUISE`, thrust ∝ error) so off-line momentum is bled AND cross-axis
  POSITION is corrected (the hole the pure velocity-alignment `parkourRunupAlign` left).
  **Deliberately NO centerline recentering on either leg** — the recentering is exactly what
  refuted the P5 hug (§9). Both aims are pass-through; a stopping consumer hands off to
  `recenterOnTarget` itself. Alloc-free, cold path.
- **`SteerControl.pastGate(...)`** — along-track projection of bot vs gate on the s→t axis, with
  the single-sided early deadband `GATE_PASS_DEADBAND = 0.05` (stateless — cannot chatter on wall
  pushback; fires early-side, the support-safe direction). Cross-track deliberately unconsulted.
- **DiagonalParkour consumer:** constants `BODY_RADIUS = 0.3`, `GATE_MARGIN = 0.05`,
  `GATE_SETBACK = BODY_RADIUS + GATE_MARGIN`; the gate = the takeoff exit corner pulled
  GATE_SETBACK back INTO the takeoff cell along the diagonal (≈ 0.357 along-line past centre).
  The runup drives `steerViaGate` anchored on the PLAN's own takeoff/landing cells (mid-path
  adoption can't skew the axis); the takeoff trigger is `grounded && pastGate(...)` — the jump
  fires with the centre ≥ body radius inside the cell, so a grounded spill into the fail set is
  impossible in normal execution and a mis-trigger surfaces as a clean envelope FAIL. The old
  `TAKEOFF_EDGE_ALONG`/`TAKEOFF_EDGE_RAW` constants are DELETED (ParkourEnvelope keeps its own
  private 0.40 derivation copy — see `DESIGN-parkour-envelope.md` status). The hazard-predictive
  early-takeoff branch is preserved verbatim. Known slack: the jump now launches ~0.05–0.09
  along-line earlier than the envelope derivation's 0.40 assumption — covered by the sprint
  predictor + airborne servo; max-gap diagonal tiers deserve one in-game look.

## §9 The one-side-hug Diagonal (P5) — search shipped-then-REVERTED, refutation on record

The search-side relaxation (emit a HUG diagonal at ≈ 8.648 ticks when exactly one corner column is
clear; both-blocked stays refused — vanilla physics; both-clear byte-identical at 6.552) was
implemented, tested (8 candidate-level tests) and **REVERTED after in-game refutation**: the first
real hug diagonal froze the bot at the blocked corner for 15k ticks. Mechanism: the ground servo's
straight-line tracking corrects the bot back toward the takeoff→target line, exactly canceling the
wall-slide's cross-axis progress — the hug REQUIRES leaving that line, so servo and vanilla
collision reach a fixed point at the corner. **The "no follower change needed, vanilla sliding
produces the hug" assumption is refuted.** The patch is preserved (`scratchpad/P5.patch`:
Diagonal.java + DiagonalHugTest + docs); `Diagonal.java` in-tree is back to both-corners-required.

Re-landing plan (P5 follow-up, not yet done): re-apply the search-side emission AND give Diagonal a
`steer` override calling `steerViaGate` with the open-side gate (corner + 0.3 body radius + margin
toward the OPEN side — the §8 primitive's Javadoc documents this intended consumer), plus a
follower validity envelope so a frozen hug FAILs instead of churning.

## §10 Swim steering — current state (for cross-reference)

The swim execution stack is unchanged by this arc and lives in `SteerControl`: vertical control is
the **`holdDepth` depth autopilot**, called by each swim move's own `steer` with its pose bias
(`SUBMERGE_BIAS = 0.8` — how far below the planned depth a PRONE move rides); pitch-based drives
(`swimPitched*`) pass the SAME bias so pitch and holdDepth target one depth. Pure-vertical
(degenerate, segLen≈0) segments **station-keep** over the target column
(`recenterOnTarget`/`swimPitchedCentered`'s proportional pull) instead of thrusting — the banked
vertical-swim fix (core 306e871). Reaches are bias-aware and pose-gated (`reachedSwim`). None of
the swim moves declares a validity envelope yet (§3).
