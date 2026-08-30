# SERVO INVENTORY — SteerControl.java (audit 2026-08-19)

Generated from a full read of SteerControl.java (2,318 lines) + every src/main call site, during the
stepOffGate conviction (the long-flagship stalls: the rear-lip walk-out at (419,66,596) and the
blocked cross-pull at (260,83,452)). Line numbers (SC:) are as-of core @ 2026-08-19 and will drift.
Axis legend: **P** = position-regulated, **V** = velocity-regulated, **P→V** = position error →
capped velocity setpoint → thrust, **—** = not regulated.

## Shared geometry: `computeGeom` (SC:262–320, private)

All line-tracking servos route through this. Segment = SteerView start `(sx,sz)` → target `(tx,tz)`.
`along` = bot's projection clamped to `[0,len]`; foot point `f`; `cte = |bot−f|`. Pursuit point `q` =
foot point advanced `lookahead = LOOKAHEAD(1.5)/(1 + cteGain·cte)` along the line, clamped to the
segment end. `cteGain`: 0 for ground, `SWIM_CTE_GAIN=6.0` for swim. **laneGate** (4-arg overload):
when the caller passes `hazardCorner=true` and `cte > LANE_ADMIT(0.15)`, `lookahead=0` — pure
recentring at the perpendicular foot point. Degenerate segment (`len<EPS`): `q = target`, `cte` =
distance to column. So in every pursuit servo, cross-track is position-regulated toward the segment
line; along-track advance is whatever the caller layers on.

## A. Ground drive family

**`drive` (SC:2224–2269)** — the medium dispatcher; tag `release:blocked` when climbable+collided.
Callers: Movement.steer default, Traverse walk/stepup, Diagonal cross, Descend step (in water).
Order: climbable-collided release → `holdClimbableStance(translating=true)` → in-water
`uprightSwimServo`+`holdDepth(0)` → land `groundServo` (default) or `steerTowards` (legacy).

**`steerTowards` (SC:328–337)** tag `steer` — open-loop walk: face pursuit point, forward 1.0.
Along **—** (open loop), cross **P** (pursuit). Callers: Ascend, Parkour runup/takeoff, WalkOff,
legacy drive. No velocity term; convicted on ice (SC:1174–1179).

**`groundServo` (SC:1198–1294)** tags `servo:coast/hazard/thrust` — the land velocity servo,
yaw-only. Hazard mode when `groundOvershootHazard || (groundFlankHazard && crossTrack>0.08)`.
SAFE branch: desired vel = pursuit dir × `SERVO_GROUND_CRUISE(0.35)` (unreachable ceiling), corner
blend (`CORNER_BLEND_DIST 1.3 / MAX 0.55 / RACING_BIAS 0.5`) guarded by `blendLeavesLane`.
HAZARD branch: leg frame; `alongSpeed = min(0.35, max(0.11, 0.16·distToArrivePoint)) ×
max(0, 1−cte/0.40)`, arrive point = target − `TURN_ARRIVE_OFFSET(0.45)`; `crossSpeed =
min(0.13, 0.75·cte)`. Final: `err = dv − vel`; deadband `SERVO_DEADBAND(0.02)` → coast; else face
err, `forward = min(1, SERVO_GAIN(18)·|err|)`. Along = **V** toward ceiling (position only via the
hazard ramp); cross = **P→V**. Safe branch has NO arrival position term — full cruise through the
waypoint is by design (handoff = cursor advance).

## B. Arrive / recenter / station family

**`recenterOnTarget`/`recenterOn` (SC:386/398)** tags `recenter(:dead)` — radial **P**:
`d > COLUMN_DEADBAND(0.15)` → face, `forward=min(1,d)`; else forward 0 exactly (vine ruling).
Callers: Pillar, MineDown, RideBubbleColumn settle, StartSprintSwim submerge, Climb, Ascend build,
PhaseRunner airborne-hold, Parkour hot-entry (rear-lip stair frame), degenerate fallbacks.
**Documented conviction** (SC:432–440): pure P + deadband = standing overshoot; deadband zeroes
output exactly where a walk-off bot carries max momentum. Kept only where the climbable
involuntary-climb hazard requires zero-input.

**`arriveOnTarget` (SC:491–535)** tags `arrive(:dead)` — the velocity-aware arrival. Heading pinned
to the segment (braking = signed reverse forward, never a yaw flip). Projected miss `e = target −
(pos + coast·vel)` with `coast = grounded?1.20:10.11`; deadband 0.15 on the PROJECTION; decompose in
heading frame → signed `setForward` (along) + `setStrafe` (cross), vector-saturated. Along and cross
= **P on the projected stop point** (effectively PD). Overshoot-safe both directions. Callers: Fall
(steer/walkoff/fall), Descend step (replaced the convicted `descend:dead` law 2026-08-14). The
rejected `steppingOff` pre-brake variant is documented at SC:473–489 — do not reintroduce.

**`stationKeep` (SC:653–700)** tags `hold(:dead)/hold(:floor/:depth/:sneak)` — PhaseRunner unmet-need
hold while settled/on-climbable. Horizontal: **`anchoredServo` → `actuate`** on the bot's OWN column
(never the target), same cap/gain as `restHold`. **NORMALIZED 2026-08-29**: it was the last hold still
on the legacy `recenterOn` P-law — `restHold` moved on 08-19 and this one was missed. `recenterOn`
faces its *position* error with no velocity term, no strafe and no signed forward, so rotation is its
only lever and cross-axis momentum makes it chase its own overshoot (observed: a ~200° facing sweep,
yaw 174→126, throttle 0.16→0.48 — exactly `fwd == distance-to-anchor`). Vertical: unchanged —
grounded → nothing; fluid → `holdDepthAt(standableBelow ? footY : footY+0.5)` (floor-settle beats
buoyancy); climbable → sneak; else `holdClimbableStance(false)`. Floor target one-sided by
construction (can only arrest descent).

**`settleOnOwnColumn` (SC:718)** — **ORPHANED** (no production callers).

**`settleIntoBand` (SC:802–810)** — PhaseRunner implicit settle gate (`!settled && fromFootY known &&
!inRestingPose`). `recenterOn`(own column); sneak if `y ≤ footY+SETTLE_BAND(0.20)` or `y+velY <
footY` (one-tick velocity feed-forward). Vertical **P bang-bang + feed-forward** — boundary-safe.

**`inRestingPose` (SC:758)** — predicate only: `(settled||climbableBelow) && (grounded || inWater ||
inLava || footY ≤ y ≤ footY+0.20)`.

## C. Gates & holds

**`stepOffGate` (SC:857–887)** tags `arrest(:hold)` — **the convicted servo**. Engaged via
`MovePlan.Phase.carryUncontained` (MovePlan.java:321–327): only while `arrestCarry && grounded &&
foot==carryFrom`; declared by Ascend climb, Climb climb, Descend step, Diagonal cross, Fall walkoff,
Traverse stepup/walk1, WalkOff walkoff. Containment: `predictedOffset = −crossErr + vCross/(1−f)`
(f = slipperiness×0.91), contained iff `|·| ≤ 0.5 − PARKOUR_CELL_MARGIN(0.3) = 0.2`. Arrest law:
`desiredCross = clamp(0.75·crossErr, ±0.13)`; **desired velocity vector = crossUnit·desiredCross —
the along component is identically 0, no position term**; `err = desired − vel`; face err,
`forward = min(1, 18·|err|)`. THE FAILURE: forward carry vAlong becomes along-error −vAlong →
full-key reverse (gain 18 saturates at 0.055 b/t; one ground tick imparts ~0.1 b/t) → at the rear
lip one tick walks the bot out of the from-cell, the gate self-disengages (foot leaves carryFrom),
the envelope fires. Frame mix: parkour landing-cell margin used as a ground-lane bound; crossErr
measured to the centreline through the TARGET centre while gating on standing in the FROM cell.

**`holdUntilOverTargetColumn` (SC:812–827)** — writes only sneak; engaged iff `onClimbable &&
!grounded && !standableBelow && |target−pos| > 0.15`. Callers: Descend clear/step, Diagonal cross,
Fall walkoff (latched into its advance gate).

**`holdClimbableStance` (SC:2097–2222)** — vertical **P bang-bang** on `[floorY, floorY+0.20]` with
one-tick velocity feed-forward on the descend edge (sneak tap); jump above/below band per rules;
grounded → no-op. Writes `lastStance` diagnostic, never `tag`.

**`holdDepth`/`holdDepthAt` (SC:2321/2340)** — vertical bang-bang, target `ty + SWIM_RIDE(0.2) −
bias`, hysteresis ±`WATER_RISE_DEADBAND(0.2)` (jump/sink) — but decided on the **PROJECTED RESTING
HEIGHT** `y + velY·(q/(1−q))`, not on `y`. Bang-bang is correct here (the actuators ARE discrete
±0.04 impulses, nothing to proportion); the projection supplies the velocity half of the choice.
Identical to the old law at rest (`velY == 0 ⇒ projected == y`), which preserves the settle /
station-keep band semantics and their pinned tests.

> ⚠️ **The pre-2026-08-29 entry here read "No velocity term — fine because fluid vertical rates
> (~0.04 b/t) ≪ the 0.4 band." That premise was FALSE and cost a flagship wedge.** 0.04 is the
> per-tick *impulse*, not the rate it produces: under the 0.8 vertical drag it integrates to a
> terminal `0.04·0.8/0.2 = 0.16 b/t`, and `Swim`'s own cost model had said so all along ("sink one
> cell: `1/0.185 ≈ 5.41` t/block"). At the measured 0.148 b/t the ±0.2 band is crossed in under
> three ticks, so the hysteresis bought nothing. **Never justify a missing velocity term by
> comparing an impulse to a distance** — integrate it first.

## D. Swim family

Vertical for all = caller's `holdDepth`; pitch servos aim the look at `swimDepthTarget` so pitch and
autopilot cooperate. SprintSwim drive selected by `-Dorebit.swim.bleed`, default **`servo`** →
`swimServo` is the live prone drive.

**Pose truth (2026-08-29):** `swimArrive` takes the caller's `declaredProne` but steers on
`declaredProne || b.prone()`. The caller constant states what the MOVE believes; the two disagree
for the length of any pose transition, and `StartSprintSwim` *is* that transition. Both pose-gated
behaviours are load-bearing: **pitch** (`faceTowards` is the only pitch write on the path, and in
the prone pose vanilla steers the whole vertical axis off the look, ≤0.085/t vs jump's 0.04/t) and
**client legality** (the `SERVO_FORWARD_MIN` floor — without it the bot holds sprint at `fwd=0.00`
airborne in water, which `LocalPlayer.shouldStopSwimSprinting` makes impossible for a real player).

**`swimTowards` (SC:897)** — face pursuit (cteGain 6), forward 1. Open-loop along.
**`swimPitched` (SC:921)** — `faceTowards(pursuit, depth)`, forward 1. Open-loop along.
**`swimPitchedCentered` (SC:942)** — aim waypoint CENTRE, `forward = min(1,d)` — **no deadband, no
velocity term**: the swim-frame sibling of the convicted `recenterOn` P-law.
**`swimPitchedBraked` (SC:977)** — near-face arrive point (−0.45), `throttle = min(d, 0.28)`;
drag-assisted braking, no reverse thrust.
**`swimPitchedDirectional` (SC:1013)** — dispatcher: crawl (braked) on
`overshootHazard || (flankHazard && crossTrack>0.08)`, else `swimPitched`.
**`swimServo` (SC:1059–1168)** — untagged (exec `src` is stale for its ticks!). hazardCorner gates
`laneGate` + suppresses corner blend (the mazeportal inside-pull conviction) + speed ramp
(`min(0.35, max(0.11, 0.16·distToNearFaceArrivePoint))`); safe = pursuit × `SERVO_CRUISE(0.35)`
(unreachable); err-servo, deadband 0.02, gain 18, `SERVO_FORWARD_MIN(0.08)` floor while prone.
Degenerate branch = P-only column pull (`min(1,od)`) — no velocity term there.
**`uprightSwimServo` (SC:1338–1379)** tags `uswim:coast/thrust` — segment branch = pursuit ×
cruise; **degenerate branch = the correctly-built zero-velocity law**: desired vel = `unit(offset) ×
min(0.11, 1.0·od)` — the zero-velocity setpoint is DERIVED from the position error, so at centre it
actively brakes external push (an anchored station-keep, not a dead-band no-op). The ceiling is
deliberately achievable so the loop converges.

## E. Parkour family

**`parkourAirborne` (SC:1479–1565)** — along = **predictive P**: `predictAlongTouchdown` (verified
recurrence `s+=v; v=(v+0.98·a·dir)·0.91; y+=vy; vy=(vy−0.08)·0.98`, cap 30t) vs desired point
(centre / centre+`CARRY_AHEAD 0.2` colinear-chain / `cnSafe = C−0.5+margin` pure-arrival-on-ice),
clamped ≤ far margin; **hard invariant: never brake if the full-reverse prediction lands short of
`cnSafe`** — the only servo with an explicit boundary floor on its braking axis. Cross = P→V
(0.75·crossErr cap 0.13). Callers: Parkour/DiagonalParkour airborne+land, WalkOff cross.
**`parkourRunupAlign` (SC:1612–1625)** — desired vel = axis × cruise, **cross setpoint 0 with NO
cross position term** — structurally the stepOffGate flaw on the cross axis; live only on the 1-tick
DiagonalParkour takeoff (the runup was migrated to `steerViaGate` for exactly this reason,
DiagonalParkour.java:489–492).
**`parkourLaunchShort` (SC:1642)** — predicate: predicts a non-sprint jump touchdown; short ⇒ caller
injects sprint; over-injection self-corrects via the airborne servo.
**`steerViaGate`/`pastGate` (SC:1700/1750)** — aim the gate until `along ≥ gate − 0.05` (one-sided),
then the target point; desired vel = dir × cruise; DELIBERATE omissions: no cross line-return, no
arrival ease — pass-through by contract; a STOP consumer must hand off to `recenterOnTarget`.

## F. Hazard helpers

`travelFrame` (SC:1762) — frame caveat: `F.cy = floor(p.ty())` is the FEET cell, knowingly kept
(SC:1769–1774). `overshootHazard` (2 cells ahead, `swimHazardAt` y−1..y+1); `flankHazard` (±1 at
waypoint, armed only with crossTrack > `FLANK_DRIFT(0.08)`); ground variants: LAVA always, VOID
(single lane-floor solidity read) only when `!pathDropsAhead`; `pathDropsAhead` = next leg descends
+ colinear (`STRAIGHT_DOT 0.9`) — the discriminator that stopped hazard-braking on planned
Descends. `blendLeavesLane` — signed cte vs half-width `0.2`, drops outward blends.

## Closing table

| Servo | Along | Cross | Vertical | Boundary-safe |
|---|---|---|---|---|
| steerTowards | — (fwd 1.0) | P | — | **NO** (convicted, ice) |
| groundServo safe | V (cruise; no arrival term) | P→V | — | pass-through by design; chatter-only risk |
| groundServo hazard | V×P-ramp | P→V | — | yes-ish (speed ≤0.11 at corner) |
| recenterOn(Target) | P radial, deadband 0.15 | (radial) | — | **NO** (convicted SC:432–440) |
| arriveOnTarget | P projected (PD) | P projected → strafe | — | **YES** |
| stationKeep | P own-col | P own-col | P bang-bang / sneak | YES |
| settleIntoBand | P own-col | P own-col | P band + vel FF | YES |
| holdUntilOverTargetColumn | — | — | sneak hold | n/a |
| **stepOffGate arrest** | **V-only, setpoint 0, NO position anchor** | P→V | — | **NO — the convicted class** |
| swimTowards / swimPitched | — (fwd 1.0) | P | pitch-aim | NO (open loop) |
| swimPitchedCentered | P min(1,d), no deadband/vel | (radial) | pitch-aim | **NO** |
| swimPitchedBraked | P throttle min(d,0.28) | (radial) | pitch-aim | drag-assisted |
| swimServo | V (cruise/ramp) | P→V + laneGate | pitch + holdDepth | YES at hazard; degenerate branch P-only → NO |
| uprightSwimServo segment | V cruise | P→V | — | pass-through by design |
| uprightSwimServo degenerate | **P→V, vel-0 setpoint DERIVED from position** | (same) | — | **YES — the correct vel-0 pattern** |
| parkourAirborne | **P predictive + cnSafe hard floor** | P→V | ballistic | **YES — the correct braking pattern** |
| parkourRunupAlign | V cruise | **V-only, setpoint 0, NO position anchor** | — | **NO** (same class; 1-tick exposure) |
| steerViaGate | V cruise pass-through | P point pursuit | — | pass-through by contract |
| holdDepth(At) | — | — | P bang-bang ±0.2 | YES for fluid rates |
| holdClimbableStance | — | — | P band + vel FF | YES |

## Consolidated bug-class flags

1. **Velocity-only axis, no position anchor:** `stepOffGate` along + `parkourRunupAlign` cross. The
   file already contains BOTH correct patterns for the same intent: `uprightSwimServo`'s degenerate
   branch (zero-velocity setpoint derived from position error) and `parkourAirborne` (predictive
   position servo with the `cnSafe` hard boundary floor on the braking axis).
2. **One-tick proportional overshoot:** `SERVO_GAIN=18` saturates the key at `|err| ≥ 0.055` b/t vs
   ~0.1 b/t imparted per ground thrust tick — every err-servo can cross its setpoint in one tick;
   only position-anchored setpoints self-correct. The pure-P laws (`recenterOn`,
   `swimPitchedCentered`, RideBubbleColumn's local `holdColumn`) carry the documented
   standing-overshoot defect.
3. **Mixed frames:** stepOffGate borrows `PARKOUR_CELL_MARGIN` for a ground-lane bound and measures
   crossErr to the TARGET centreline while gating on the FROM cell; `travelFrame.cy` is a
   knowingly-kept suspect; `arriveOnTarget` switches frames on degenerate segments; Parkour's stair
   recentre aims a rear-lip frame.
4. **Untagged servos** (exec `src` stale for their ticks): swim pitch family, swimServo,
   parkourAirborne, parkourRunupAlign, steerViaGate, settleIntoBand, holdUntilOverTargetColumn,
   holdDepth.
5. **Orphans:** `settleOnOwnColumn`, `swimTowardsDirectional`.
