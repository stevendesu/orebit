# DESIGN — Servo normalization (one control law, three modes)

**Status: RATIFIED 2026-08-19 (owner) — Phase 1 in implementation.** §7 answers: Q1 =
`FACE_ERR_THRESHOLD` is the saturation point `1/gainV ≈ 0.055` b/t (derive from `SERVO_GAIN`,
no new literal; promote to a hysteresis pair only on observed chatter). Q2 = Phase 1 stays at
exactly two servos; the arrest() hooks land afterward as their own reviewed change, as a CALLER
of the Phase-1 core. Q3 = the derived-gainP rule `gainP = (1−q)/q` per medium IS ratified as the
ARRIVE default — future media get gains from NOTES-movement-physics, never tuning sessions.
2026-08-19. Direction ratified by the owner 2026-08-19; this doc's job is to make it precise and
migratable. Companions: `SERVO-INVENTORY.md` (the complete audited state — every servo, control
law, constants, the closing table and bug-class flags; all SC: line numbers below are from that
audit, core @ 2026-08-19, and will drift), `DESIGN-arrest-servo.md` (the braking study — its
stopping-distance table and hazard gates fold in here), `DESIGN-replan-handoff.md` §10 (the U1–U6
machinery whose stand-still states the arrest servo serves).

## §1 Why — the consolidated bug classes and the two convictions

The inventory's closing table shows 20+ steering laws with **five distinct control-law shapes**
for the same three intents (go somewhere, arrive somewhere, stay somewhere), and its consolidated
flags reduce every open follower defect to four structural classes:

1. **Velocity-only axis, no position anchor** — `stepOffGate`'s along axis (SC:857–887) and
   `parkourRunupAlign`'s cross axis (SC:1612–1625). A zero-velocity *setpoint* with no position
   term cannot distinguish "drifting" from "displaced": it fights the velocity it sees this tick
   and is indifferent to where the bot has already been pushed. The file already contains BOTH
   correct patterns for the same intent — `uprightSwimServo`'s degenerate branch (zero-velocity
   setpoint DERIVED from the position error, SC:1338–1379) and `parkourAirborne`'s predictive
   position servo with the `cnSafe` hard boundary floor (SC:1479–1565).
2. **One-tick proportional overshoot** — `SERVO_GAIN = 18` saturates the key at
   `|err| ≥ 0.055` b/t against ~0.1 b/t imparted per ground thrust tick, so every err-servo can
   cross its setpoint in one tick; only position-anchored setpoints self-correct. The pure-P laws
   (`recenterOn` SC:386–440, `swimPitchedCentered` SC:942, RideBubbleColumn's local `holdColumn`)
   carry the documented standing-overshoot conviction (SC:432–440).
3. **Mixed frames** — stepOffGate borrows `PARKOUR_CELL_MARGIN` as a ground-lane bound and
   measures `crossErr` to the TARGET centreline while gating on standing in the FROM cell.
4. **Untagged servos** — the swim pitch family, `swimServo`, `parkourAirborne`,
   `parkourRunupAlign`, `steerViaGate`, `settleIntoBand`, `holdUntilOverTargetColumn`, and
   `holdDepth` never stamp `tag()`, so the exec log's `src` is STALE for every tick they own (§4).

**The motivating incidents** — the two long-flagship freezes that triggered the audit, both
convicting the same servo through classes 1–3:

- **The rear-lip walk-out at (419,66,596).** stepOffGate engaged with forward carry; the arrest
  law's along setpoint is identically 0 with no position term, so `vAlong` became along-error
  `−vAlong` → a full-key REVERSE (gain 18 saturates at 0.055 b/t; one ground tick imparts
  ~0.1 b/t). Standing near the rear lip of the from-cell, one reverse tick walked the bot OUT of
  it; the gate self-disengaged (foot left `carryFrom`, MovePlan.java:321–327), and the validity
  envelope fired — `step FAILED` → HOLD. An anchored law cannot do this: at the takeoff centre
  the desired velocity is bounded by the cap and always points back INTO the cell.
- **The blocked cross-pull at (260,83,452).** The arrest's cross pull (`0.75·crossErr` toward the
  TARGET centreline — the class-3 frame mix, measured to a line the bot is not yet on while gated
  on the FROM cell) pressed the bot laterally against a blocker (bamboo) it cannot model, and
  held it there. Normalization fixes the frame half (the response re-anchors to the takeoff
  centre, §3) — the blocker-blindness half of this incident is NOT a control-law problem and
  explicitly stays with the arrest-servo/hazard-modeling track (§6).

Prior convictions the same classes explain, already documented in-tree: `steerTowards` on ice
(SC:1174–1179), `recenterOn`'s standing overshoot on the Fall into (82,115,218) (SC:432–440), the
mazeportal inside-pull (`swimServo`'s laneGate fix), and the flagship-cliff (68,149,245) chained
step-off slip that stepOffGate itself was ratified to fix (owner, 2026-07-30).

## §2 The unified law — one cascade, three modes, one actuation

### §2.1 The control law (ratified, owner 2026-08-19)

One position→velocity→thrust cascade, per tick, stateless, allocation-free:

```
anchor a                                  (per-mode; may move tick-to-tick in PASS-THROUGH)
desired_vel = unit(a − pos) · min(cap, gainP · |a − pos|)
err         = desired_vel − vel           (horizontal, blocks/tick)
thrust      = min(1, gainV · |err|)       (deadband: |err| < SERVO_DEADBAND → coast)
```

Three modes, distinguished ONLY by anchor and cap:

- **PASS-THROUGH** — `a` = the pursuit point `q` (computeGeom, SC:262–320: lookahead, cteGain,
  laneGate all unchanged — pursuit geometry is anchor SELECTION, not control law), `cap` = the
  cruise ceiling (`0.35`, deliberately unreachable). Because the cap saturates at pursuit
  distance, `gainP` never engages and the law reduces to today's safe branches — `groundServo`
  safe, `swimServo` safe, `uprightSwimServo` segment, `steerViaGate` — **unchanged by intent**.
  Full cruise through the waypoint stays by design; handoff = cursor advance.
- **ARRIVE** — `a` fixed (a waypoint centre, a hazard near-face point, a landing column).
  Braking is EMERGENT: as `pos → a`, `desired_vel → 0` smoothly, and past the anchor
  `unit(a − pos)` reverses, so overshoot is answered with position-anchored reverse thrust — no
  ramp constants, no brake branch. Where a real gap exists past the anchor, ARRIVE takes the
  optional **predictive boundary floor** from `parkourAirborne`'s `cnSafe` pattern
  (SC:1479–1565): never command a braking input whose full-reverse prediction lands short of
  `C − 0.5 + margin`. That invariant survives verbatim (ruling, §D of the ratification).
- **HOLD** — ARRIVE with a small ACHIEVABLE cap. This is `uprightSwimServo`'s proven degenerate
  branch (SC:1338–1379) promoted to a mode: the zero-velocity setpoint is derived from the
  position error, so at the anchor the servo actively brakes external push — an anchored
  station-keep, never a dead-band no-op. The cap must be achievable in the medium or the loop
  sits saturated and never converges (the `UPRIGHT_SWIM_SPEED` Javadoc's own rule).

**Out of scope, unchanged:** every VERTICAL bang-bang hold — `holdDepth`/`holdDepthAt`
(SC:1926/1945), `holdClimbableStance` (SC:2097–2222), `settleIntoBand`'s band + velocity
feed-forward (SC:802–810), `holdUntilOverTargetColumn`'s sneak (SC:812–827). They are
boundary-safe today (inventory closing table) and stay as-is. `holdDepth` ownership stays with
the swim moves and `drive()`'s in-water branch (ruling).

### §2.2 The actuation layer (owner refinement, 2026-08-19)

Control (what velocity to want) is separated from actuation (what inputs express it). **Yaw is
SEMANTIC**: face the final target (ARRIVE/HOLD) or the travel direction (PASS-THROUGH) — never
the error vector for small corrections. The error is expressed in the facing frame:

```
setForward = err · heading          (SIGNED — backpedal is legal; the moon-walk brake)
setStrafe  = err × heading          (cross)
vector-saturated, never per-component
```

This is `arriveOnTarget`'s existing owner-ratified actuation (SC:449–466: "heading is held,
braking is REVERSE input" + "cross-axis error is corrected by STRAFE, not by yawing" — both
2026-08-06 rulings, the no-pirouettes conviction) **generalized to the norm**. Today
`groundServo`/`swimServo`/`stepOffGate` face the raw error vector every thrust tick, which is the
observed yaw-pirouette/±90° flap jitter: a sub-saturation correction spins the head. Under the
norm the head points where the bot is GOING and the hands do the correcting.

**Face-the-error escape hatch:** the servo yaws onto the error only above a threshold. The
physical justification: sprint authority exists only forward (+30% thrust, and vanilla sprint
requires forward input) — so the threshold is "corrections needing sprint-class thrust," i.e.
corrections the strafe/backpedal channels cannot deliver. The exact value is an owner call (§7).

**Coexistence rules (all survive verbatim, ruling §D):** swim PITCH stays owned by the depth
target (`faceTowards(pursuit, swimDepthTarget)`) — yaw semantic, pitch depth, they already
cooperate; `SERVO_FORWARD_MIN` (0.08) client-pose legality while prone; the climbable
**zero-horizontal-input** ruling (2026-07-31 vine-bounce) overrides EVERYTHING on climbables,
exactly as today; laneGate; the corner racing-line blend (`blendLeavesLane`-guarded) as
PASS-THROUGH anchor modification; `arriveOnTarget`'s pinned-heading actuation (now the norm);
the rejected `steppingOff` pre-brake STAYS rejected (SC:473–489 — do not reintroduce).

### §2.3 Constants — what maps, what's derived, what's new

A key identity makes the ARRIVE gains derived rather than tuned: with `gainP = 1/coast =
(1−q)/q` for the medium's per-tick horizontal drag `q`, the cascade's error is exactly
`gainP · (a − (pos + coast·vel))` — **the unified law with that gainP IS `arriveOnTarget`'s
projected-stop law** (SC:423–424). Physically: `desired_vel(d)` is precisely the speed from which
a pure-drag coast stops ON the anchor. So ARRIVE's ground/air gains come from
NOTES-movement-physics §1, not from tuning sessions.

| Unified name | Value | Source (existing constant) | Notes |
|---|---|---|---|
| `gainV` | 18.0 | `SERVO_GAIN` (SC:170) | universal today (ground/swim/arrest); `arriveOnTarget` maps with `gainV = coast` instead (see its §3 row) |
| deadband | 0.02 | `SERVO_DEADBAND` (SC:173) | = `REST_HSPEED`, so HOLD quiescence and `atRest` coincide (DESIGN-arrest-servo §3) |
| PASS-THROUGH cap | 0.35 | `SERVO_CRUISE` / `SERVO_GROUND_CRUISE` / `PARKOUR_CRUISE` | unreachable ceiling, by design |
| ARRIVE gainP (ground) | 0.831 | derived: `(1−0.546)/0.546 = 1/GROUND_COAST` | not new — a re-expression of `GROUND_COAST` (SC:424) |
| ARRIVE gainP (air) | 0.099 | derived: `(1−0.91)/0.91 = 1/AIR_COAST` | ditto (SC:423) |
| ARRIVE gainP (fluid) | derive | from the swim drag per `mc-swim-physics-model` / DESIGN-submerged-upright-swim.md | matters only inside the last `cap/gainP` blocks; see §6 |
| HOLD gainP (ground) | 0.75 | `SERVO_CROSS_GAIN` (SC:233) | note: just soft of the derived stone 0.831 — the landscape is already near-critical |
| HOLD cap (ground) | 0.13 | `SERVO_CROSS_CAP` (SC:234) | stepOffGate's proven pull cap |
| HOLD gainP (swim) | 1.0 | `UPRIGHT_STATION_GAIN` (SC:1394) | saturates the cap beyond 0.11 bl — "full speed until nearly centred, then ease" |
| HOLD cap (swim) | 0.11 | `UPRIGHT_SWIM_SPEED` (SC:1386) | the achievable-ceiling rule |
| pursuit geometry | 1.5 / 6.0 / 0.15 | `LOOKAHEAD` / `SWIM_CTE_GAIN` / `LANE_ADMIT` | anchor-selection layer, unchanged |
| ARRIVE anchor offset (hazard) | 0.45 | `TURN_ARRIVE_OFFSET` (SC:133) | the near-face point survives as an ANCHOR; the ramp toward it retires |
| prone thrust floor | 0.08 | `SERVO_FORWARD_MIN` (SC:213) | client-pose legality, survives |
| **`FACE_ERR_THRESHOLD`** | **NEW** | — | the §2.2 yaw escape hatch. The only genuinely new constant. Justification: the sprint-class-thrust boundary; candidate values in §7 |

**Retired constants** (their behavior becomes emergent): the hazard speed-ramp family —
`SERVO_HAZARD_RAMP` (0.16), `SERVO_TURN_FLOOR` (0.11), `SERVO_CTE_HALT` (0.40),
`SERVO_ALONG_HALT_FLOOR`, `TURN_BRAKE_STOP` (0.1), `TURN_BRAKE_RAMP` (2.0),
`TURN_CRAWL_THROTTLE` (0.28). Hazard handling becomes a MODE SWITCH: the existing hazard
predicates (`overshootHazard`/`flankHazard`/`pathDropsAhead`, SC:1762+, all unchanged) select
ARRIVE anchored at the near-face point instead of selecting a bespoke speed ramp. Braking then
falls out of the cascade with the derived gainP — the arrest-study math (DESIGN-arrest-servo §2:
counter-thrust stops in ~1.0 bl/8 t where the coast slides ~2.7 bl/26 t on blue ice, and
degenerates to a near-no-op on stone) is the evidence the cascade brakes at least as well as
every ramp on every medium.

### §2.4 The ballistic-runup composition — ratified 2026-08-19

`parkourRunupAlign`'s law is a PER-AXIS composition in the jump-axis frame — two instances of the
same unified cascade, one per axis, sharing the core's actuation tail:

- **Along axis** (unit axis `(ux,uz)`): ARRIVE at the LANDING CENTRE's along-coordinate —
  `desired_along = signum(dAlong) · min(SERVO_GROUND_CRUISE, ARRIVE_GAIN_GROUND · |dAlong|)`. Over
  any real runup the 0.35 cap saturates, so the drive is full cruise toward the landing; the anchor
  is always well ahead of the takeoff stand, never underfoot.
- **Cross axis**: HOLD on the jump-axis centreline (the line through the landing centre with
  direction `(ux,uz)`) — `desired_cross = −clamp(SERVO_CROSS_GAIN · c, ±SERVO_CROSS_CAP)` for the
  bot's cross offset `c` from the line — a bounded pull back onto the axis.

Why not one isotropic ARRIVE at the landing: an isotropic ARRIVE converges the cross error only
linearly over the remaining distance — the intercept happens AT the anchor — so a long jump
(gap-4) would launch while still displaced off-axis, violating the planner's prism assumption
(hitbox inside the 1-wide corridor). The per-axis HOLD makes the cross convergence rate independent
of jump length. The lane bound ±0.2 is DERIVED, not new: `0.5 − PARKOUR_CELL_MARGIN` (0.3 = the
player half-width).

**Phase-4 note.** The hard "never LAUNCH while outside the lane" invariant is a containment
predicate on the jump arm that must be ONE GATE with the cross-HOLD centring actuation (the
stepOffGate architecture — a refusal predicate without a centring actuation deadlocks, the
arrival-containment lesson), to be designed when the straight-Parkour runup migrates in Phase 4.

### §2.5 Gate-armed steps drive ARRIVE at the step target — ratified 2026-08-19

The second stepOffGate conviction, from a 60k-tick flagship log at (259,78,448): plan wp4
`Traverse +z → (259,77,449)` then wp5 `Traverse −x`, and the bot sat in a bit-identical TWO-TICK
limit cycle for 46k ticks — `src=servo:thrust` (the normal pursuit drive, corner-cutting toward
wp5's racing line via the 1.5-block lookahead, pushing the bot to x=259.299) alternating with
`src=hold:takeoff` (at x=259.299 the verbatim containment predicate reads crossErr 0.201 + carry >
the ±0.2 lane → uncontained → the HOLD restores exactly the pre-drive state). The gate polices the
CURRENT step's target centreline; the drive steers for the NEXT waypoint's racing line — two
controllers, two lane definitions, both saturated at fwd=1.00, no geometry involved (STUCK dump:
floor solid, all air above). The §3 takeoff-centre re-anchor fixed the gate's RESPONSE; this fixes
the other controller in the dispute.

The ruling: **while the step-off gate is ARMED for the current step (`arrestCarryFrom` declared,
still grounded on the from column), the drive anchors on the CURRENT step's target centre — ARRIVE
at `SERVO_GROUND_CRUISE`/`ARRIVE_GAIN_GROUND` (full cruise beyond ~0.42 blocks, emergent easing
inside), semantic yaw down the step's travel direction, tagged `arrive:step`(`:dead`)** — the
one-gate principle (refusal, centring and drive share one lane definition) extended to the DRIVE.
The gate's HOLD (uncontained ticks) is untouched, and the lookahead corner-cut survives everywhere
else; the efficiency cost on gate-armed ticks is accepted (owner, 2026-08-19: the look-ahead
steering made the bot a little faster without sacrificing invariants — anchoring on the current
step's ARRIVE is safe for reaching the goal, just a little less efficient). Convergence: the gate
predicate is CROSS-axis only (`crossErr + vCross/(1−f)`), the ARRIVE pull is toward the same
centreline, so the cross error converges and stays contained while the along axis advances the
step until the foot leaves the from column and the gate disarms.

Wiring: `PhaseRunner` plumbs the armed bit (`SteerControl.stepGateArmed`, set/cleared around the
phase drive) and the routing lives at `drive()`'s land branch — which is exactly the set of
gate-armed drives whose anchor could leave the current lane: `computeGeom` clamps the pursuit
point to the segment, so the direct `steerTowards` callers (Ascend's jump-climb, WalkOff's sprint
lip) are already in-lane and keep their own jump/sprint inputs, and Descend/Fall already drive
`arriveOnTarget` at the step target. This is the forward-pulled slice of Phase 2's
`groundServo`-hazard→ARRIVE mode switch.

#### §2.5.1 Gate-armed ARRIVE consults the hazard predicates — ratified 2026-08-19

The (57,172,255) flagship conviction: a gate-armed approach (wp9 Diagonal → (58,172,255) chaining
into wp10 Descend → (59,171,255)) drove `arrive:step` at full cruise for the whole approach —
bypassing groundServo's hazard machinery entirely, so nothing braked for the drop lip — and on the
disarm tick the legacy `servo:hazard` branch received the full-cruise bot one cell from the lip,
whose one-tick saturated reverse (the class-2 overshoot) handed the Descend backward momentum and
slid it off the from-column → envelope fail→HOLD. The ruling: **`arriveOnStep` consults the SAME
hazard predicate call groundServo's hazard branch uses — `groundOvershootHazard ||
(groundFlankHazard && crossTrack > FLANK_DRIFT)`, verbatim — and while it fires the ARRIVE anchor
moves from the step's target centre to the near-face point (target centre − `TURN_ARRIVE_OFFSET`
along the step's travel direction, groundServo's own hazard aim). Same law otherwise** (cap
`SERVO_GROUND_CRUISE`, gainP `ARRIVE_GAIN_GROUND`, semantic yaw = travel direction) — braking
emerges across the whole approach from the anchored easing, and the handoff speed at the lip is
bounded. Zero new constants; this is §2.3's "hazard handling becomes a MODE SWITCH" paragraph and
the §3 groundServo-hazard row pulled forward into the gate-armed drive. Tagged
`arrive:stephaz`(`:dead`) per §4 so the exec log shows the mode switch.

§4 stamp landed with this change: the convicted exec record's NEXT tick — the Descend's first tick,
owned by its CLEAR phase's hand-off drive whose only steering-capable call is
`holdUntilOverTargetColumn` — was untagged (the `src` counter froze; the §4 trap verbatim).
`holdUntilOverTargetColumn` now stamps unconditionally at entry (`hold:overcol:dead`) and re-stamps
`hold:overcol` on its sneak write; callers that follow it with a real drive overwrite the stamp
(last-wins, the stationKeep precedent). Diagnostic-only, zero behavior change.

### §2.6 Arrival settle — HOLD at the final plan cell — ratified 2026-08-19

On plan completion (the COME→STAY arrival flip) the bot does not merely drop its inputs wherever it
first clipped the arrival radius: the arrival captures a settle anchor — the completed plan's FINAL
waypoint cell, deliberately NOT the come target (under come-loose tolerance the plan ends NEAR that
cell and the bot must not stand ON it) — and STAY's tick drives the unified core in HOLD at that
cell's centre (`hold:rest`/`hold:rest:dead`, the proven `SERVO_CROSS_CAP`/`SERVO_CROSS_GAIN` ground
pull, semantic facing = the anchor direction while off-centre, last yaw held once at rest). The
deadband quiescence is the rest state, and the anchored pull actively re-centres an externally
pushed bot — "stand EXACTLY there" is the intent, e.g. keeping a mob spawner active from exact afk
coordinates (owner's rationale). The conviction: IceCourse `icediag`'s plan ends at (45,151,45) with
the goal at (46,151,44); on foot-cell entry `done` fired, the mode dropped to STAY, and the bot
parked on the cell's near corner — 1.87 from the goal centre where the cell CENTRE is 1.41 — so the
pre-fix "passes" were delivered by accidental post-STAY ice glide, not by the controller. Scope:
LAND arrivals only this round (grounded, not in water, not on a climbable — a bot arriving swimming
keeps today's behavior); `/bot stay` (the command — no plan, no anchor) is unchanged, and a
command-STAY settle would be a separate owner call. The anchor is armed only at TRUE arrival and
cleared on every mode change / new command, so mid-plan rest states (DESIGN-replan-handoff.md §10's
drop→rest→reinstall dynamics) never see it. Wiring: `BotNavigator` captures the walked block plan's
last waypoint on the arrival tick (before `clearPlan` drops it), `AllyBotEntity` arms
`settleAnchor` at the mode flip and `holdPosition` routes to `SteerControl.restHold`.

## §3 Per-servo migration table

Every inventory row. Expected-change legend: **BI** = byte-identical parameterization exists;
**COS** = cosmetic-only (same converged trajectory; yaw/tag differences); **BEH** = behavioral,
with rationale. Anchors are horizontal; vertical columns are untouched per §2.1.

| Servo (SC:) | → Mode | Anchor | cap / gainP | Actuation | Change |
|---|---|---|---|---|---|
| `drive` (2224) | survives — becomes the mode DISPATCHER | — | — | — | BI (dispatch order unchanged: climbable release → stance → water → land) |
| `steerTowards` (328) | PASS-THROUGH | pursuit `q` | 0.35 / — | semantic yaw | BEH: gains the velocity loop it never had — the ice conviction (SC:1174–1179) is the rationale. `-Dorebit.ground.drive=legacy` keeps it during migration |
| `groundServo` safe (1198) | PASS-THROUGH | pursuit `q` + corner blend (survives as anchor mod) | 0.35 / — | semantic yaw | COS: identical desired-velocity law; yaw stops flapping onto sub-threshold errors |
| `groundServo` hazard (1198) | ARRIVE (mode switch on the same hazard predicates) | near-face point (target − 0.45 along the leg) | ∞ / 0.831 | semantic yaw | BEH: ramp constants retire; braking emerges. Corner speed profile changes shape but not intent — DESIGN-arrest-servo §2 bounds it |
| `recenterOnTarget`/`recenterOn` (386) | **RETIRED** → HOLD | target column | 0.13 / 0.75 | semantic yaw | BEH: kills the documented standing-overshoot + deadband-exactly-at-max-momentum defect (SC:432–440). EXCEPTION: on climbables the zero-input ruling holds — HOLD writes nothing horizontal there, exactly today's carve-out |
| `arriveOnTarget` (491) | ARRIVE (the canonical instance) | target column, projected | ∞ / `1/coast`, `gainV = coast` | ALREADY the norm (SC:449–466) | **BI** — the §2.3 identity is exact; its actuation rulings become the layer |
| `stationKeep` (653) | HOLD (horizontal) | bot's OWN column | 0.13 / 0.75 | semantic yaw | BEH (small): own-column P-law → anchored station-keep; vertical branch BI |
| `settleOnOwnColumn` (718) | **RETIRED** (orphan, no callers) | — | — | — | delete |
| `settleIntoBand` (802) | HOLD (horizontal only) | own column | 0.13 / 0.75 | semantic yaw | vertical band + velocity FF **BI**; horizontal BEH (small) |
| `inRestingPose` (758) | survives (predicate) | — | — | — | BI |
| **`stepOffGate` (857)** | predicate **survives as the engage condition** (`predictedOffset` containment, verbatim incl. `PARKOUR_CELL_MARGIN` and the `v/(1−f)` horizon); response **RETIRED** → HOLD | **the TAKEOFF cell centre** (from-cell — kills the class-3 frame mix) | 0.13 / 0.75 | semantic yaw | **BEH — the Phase-1 conviction fix.** The along axis gains a position anchor: carry is answered by a bounded pull back to the takeoff centre, not naked full-key reverse. The (419,66,596) walk-out becomes geometrically impossible (desired always points into the cell, magnitude ≤ 0.13). **Extended (ratified 2026-08-19): gate-ARMED CONTAINED ticks drive ARRIVE at the step target — §2.5** (the (259,78,448) thrust/hold limit-cycle conviction; the drive joins the gate's lane) |
| `holdUntilOverTargetColumn` (812) | survives (sneak-only) | — | — | — | BI |
| `holdClimbableStance` (2097) | survives (vertical) | — | — | — | BI |
| `holdDepth`/`At` (1926) | survives (vertical; swim-move ownership per ruling) | — | — | — | BI |
| `swimTowards` (897) | PASS-THROUGH | pursuit (cteGain 6) | 0.35 / — | semantic yaw, pitch = depth | BEH: closes the open loop |
| `swimPitched` (921) | PASS-THROUGH | pursuit | 0.35 / — | yaw semantic, pitch = depth | BEH: same |
| `swimPitchedCentered` (942) | **RETIRED** → ARRIVE | waypoint centre | ∞ / fluid gainP | pitch = depth | BEH: it is the convicted `recenterOn` P-law in a swim frame (no deadband, no velocity term) |
| `swimPitchedBraked` (977) | **RETIRED** — subsumed by hazard ARRIVE | near-face point | ∞ / fluid gainP | pitch = depth | BEH: `TURN_CRAWL_THROTTLE` retires with the ramp family; drag-assisted braking becomes cascade braking |
| `swimPitchedDirectional` (1013) | survives as MODE dispatch (hazard predicate → ARRIVE, else PASS-THROUGH) | — | — | — | COS |
| `swimServo` (1059) | PASS-THROUGH safe / ARRIVE hazard; degenerate column branch → HOLD | pursuit / near-face / column | 0.35 / fluid gains | `SERVO_FORWARD_MIN` + laneGate survive | BEH at hazard + degenerate (the P-only column pull gains its velocity term); safe branch COS. **Gets a tag (§4)** |
| `uprightSwimServo` segment (1338) | PASS-THROUGH | pursuit | 0.35 / — | semantic yaw | COS |
| `uprightSwimServo` degenerate (1338) | HOLD — **the mode's proven prototype** | target column | 0.11 / 1.0 | semantic yaw | **BI** by construction |
| `parkourAirborne` (1479) | survives — ARRIVE with the predictive boundary floor | landing centre (+`CARRY_AHEAD` colinear / `cnSafe` ice) | prediction-clamped | cross P→V unchanged | BI intent; the `cnSafe` never-brake-into-the-gap invariant survives **verbatim** (ruling) |
| **`parkourRunupAlign` (1612)** | **RETIRED** → the §2.4 ballistic-runup composition (along ARRIVE + cross HOLD) | the LANDING CENTRE (along) / the jump-axis centreline through it (cross) | 0.35 / 0.831 along; 0.13 / 0.75 cross | semantic yaw (face the axis) | **BEH — the second Phase-1 fix.** The cross axis gains the position anchor it structurally lacks (class 1). The first cut anchored ARRIVE at the GATE point and was REFUTED by the ParkourCourse regression (9–10 trials, 2026-08-19): the takeoff tick IS the whole live exposure — the earlier "delta ≈ nil / eases only inside the final fraction of a block" reasoning was wrong, because the easing region sat exactly on the one live tick — and ARRIVE at a point underfoot made err = −vel on the jump tick, yawed the bot over `FACE_ERR_THRESHOLD` and commanded a backwards launch as the jump arm fired (the backwards phantom hop). The landing-centre anchor saturates to full cruise instead |
| `parkourLaunchShort` (1642) | survives (predicate) | — | — | — | BI |
| `steerViaGate`/`pastGate` (1700/1750) | PASS-THROUGH with a gate-staged anchor (gate until `along ≥ gate − 0.05`, then target) | gate → target | 0.35 / — | semantic yaw | **BI** — pass-through by contract (ruling); the no-cross-return / no-arrival-ease omissions ARE the contract; a STOP consumer hands off to a HOLD instance (today: `recenterOnTarget`) |
| hazard helpers (1762+) | survive — become MODE-SELECTION predicates | — | — | — | BI (incl. the `travelFrame.cy` known caveat and `pathDropsAhead`) |
| RideBubbleColumn local `holdColumn` | **RETIRED** → HOLD | column centre | 0.13 / 0.75 (in-water caps where submerged) | semantic yaw | BEH: class-2 pure-P law |
| `swimTowardsDirectional` | **RETIRED** (orphan) | — | — | — | delete |

## §4 The tag/telemetry fix

The unified core stamps `tag()` **unconditionally, first thing, every tick it runs** — the
stale-tag trap (an untagged servo's ticks reading the PREVIOUS servo's `src` in the exec log,
which cost real diagnosis time in the swimServo era — inventory bug-class 4) dies structurally,
not by auditing callers. Tag vocabulary: `pass:<family>` / `arrive:<family>` / `hold:<family>`
plus the existing `:dead` quiescent suffix — e.g. `pass:ground`, `arrive:swim`, `hold:takeoff`
(the stepOffGate replacement), `hold:col`. The surviving vertical holds keep their tags
(`hold:depth`, `hold:floor`, `hold:sneak`); `holdClimbableStance` keeps `lastStance`. Rule going
forward: **a method that writes any steering input writes a tag in the same call** — the eight
untagged servos are covered automatically as each migrates onto the core, and no un-migrated
servo may ship new ticks untagged. Tagging is a static field write — zero cost, allocation-free,
per the hot-path rule.

Migration-order corollary (§6): the tag stamp for a family lands WITH or BEFORE that family's
behavior change, so every behavioral diff is attributable in the exec log from its first tick.

## §5 Phased migration

Byte-identical behavior is **NOT expected** at any phase (the point is behavior change on the
convicted laws); the oracle is **green** on every named gate. **JMH is not applicable — there is
no search-side change**: SteerControl is follower-side, never on the planner hot path, so per the
perf-process rules no A/B bench is run for any phase. Each phase is a separate commit at a
tested-working point (commit-hygiene rule). No timers, no new state, no recovery machinery at
any phase.

- **Phase 1 — the core + the two convictions.** Extract the unified cascade + actuation layer
  into SteerControl (one static entry, mode-parameterized; stateless, statics-only,
  allocation-free) with tags per §4. Re-anchor exactly two servos onto it: `stepOffGate`'s
  response (predicate untouched; response → HOLD at the takeoff centre — the **along** fix) and
  `parkourRunupAlign` (→ ARRIVE — the **cross** fix). Everything else still calls its existing
  law. **Acceptance:** the two flagship freeze sites — the rear-lip walk-out at (419,66,596) and
  the blocked cross-pull at (260,83,452) — must pass on the long flagship (-MasterWorld, per the
  worldgen rule); full unit suite; ALL course harnesses green (SwimCourse 21 cards,
  ParkourCourse, IceCourse, TrapdoorCourse, GateCourse, ReplanCourse).
- **Phase 2 — ground family.** `groundServo` safe/hazard → PASS-THROUGH/ARRIVE; `steerTowards`'s
  ground callers (Ascend, WalkOff, legacy drive) onto PASS-THROUGH; the hazard ramp constants
  retire. Gate: IceCourse (the steerTowards conviction), TrapdoorCourse, GateCourse,
  ReplanCourse, flagship sync.
- **Phase 3 — swim family.** The swim pitch family + `swimServo` + `uprightSwimServo` onto the
  modes; laneGate, pitch ownership, `SERVO_FORWARD_MIN` all preserved. Gate: SwimCourse — all 21
  cards — plus flagship sync.
- **Phase 4 — parkour family.** `steerTowards`'s runup/takeoff callers; `parkourAirborne`
  re-expressed as ARRIVE-with-boundary-floor (or left verbatim if re-expression risks the
  `cnSafe` invariant — implementer's call; the invariant is non-negotiable). Gate: ParkourCourse,
  IceCourse.
- **Phase 5 — arrive/recenter retirement.** `recenterOn`/`recenterOnTarget` → HOLD everywhere
  (climbable carve-out intact); `stationKeep`/`settleIntoBand` horizontal onto HOLD;
  `swimPitchedCentered`; RideBubbleColumn `holdColumn`; delete the orphans. Gate: full suite +
  every harness above + flagship sync.

## §6 Risks

- **Per-medium gainP retuning.** The migration collapses {0.16 ramp, 0.75 cross, 1.0 station,
  implicit 1/coast} into per-mode gains. Mitigations: the §2.3 identity makes ARRIVE's gains
  physics, not tuning (and the derived stone 0.831 sits next to the proven 0.75 — the landscape
  is already near-critical); HOLD's gain matters only inside the last `cap/gainP` blocks
  (~0.17 bl ground, 0.11 bl swim); each family migrates behind its own phase gate; the
  `-Dorebit.ground.drive` / `orebit.swim.bleed` A/B sysprop pattern (SC:145–156) gives every
  phase a same-build legacy leg.
- **Untagged-servo migration order.** Changing an untagged servo's behavior produces diffs the
  exec log attributes to the WRONG servo. Hard ordering rule: §4's tag stamp lands with or
  before each family's behavior change — never a behavioral phase whose ticks are untagged.
- **Interaction with the fail→hold diagnostic policy.** A normalization bug produces off-envelope
  motion → `step FAILED (validity envelope)` → HOLD, **loudly** — which is the desired
  diagnostic mode (the no-recovery rule: nothing masks it). One trap carried over from the
  autotest notes: everything after the first movement failure is void for metrics, so flagship
  runs judge the FIRST failure only.
- **The bamboo blocked-pull is NOT solved here.** Normalization fixes (260,83,452)'s frame flaw,
  but a servo pulling toward a correctly-chosen anchor THROUGH an unmodeled blocker will still
  press against it — that is world-model/hazard blindness, and it stays with the
  arrest-servo/hazard-modeling track (the DESIGN-arrest-servo §8 follow-up family). Do not
  extend the servo core with blocker special cases — that is the bandaid class the CLAUDE.md
  header forbids.
- **Twin-implementation drift with DESIGN-arrest-servo.md.** Its `arrest()` is exactly a HOLD
  instance (no-target form: desired = 0; seam form: anchor = the seam column with the 0.75/0.13
  pull). If the two land independently they duplicate the cascade. Resolution is §7 Q2 — but
  whichever sequencing the owner picks, `arrest()` must be (or become) a caller of the Phase-1
  core, never a sibling implementation.

## §7 Open questions for the owner

1. **`FACE_ERR_THRESHOLD` — the face-the-error value.** Candidates: (a) the saturation point
   `1/gainV ≈ 0.055` b/t — yaw onto the error exactly when proportional thrust maxes out, the
   minimal reading of "needs sprint-class thrust"; (b) a hysteresis pair around it (engage
   0.07 / release 0.04) if flagship runs show flapping AT the threshold; (c) never — pure
   semantic yaw, accepting that saturated corrections lose the sprint channel. Recommendation:
   (a) first, promote to (b) only on observed chatter.
2. **Does Phase 1 also pick up DESIGN-arrest-servo.md's `arrest()` hooks** (the WAIT / HOLD /
   seam-CAUTION / U5 stand-still sites), since `arrest()` is a HOLD instance of the same core —
   or does that stay sequenced after normalization as its own reviewed change? Folding it in
   makes Phase 1 serve three convictions with one law; keeping it out keeps Phase 1's blast
   radius at exactly two servos. This doc takes no position; the arrest doc's `icelava` course
   tile belongs to whichever phase adopts the hooks.
3. **Ratify the derived-gainP rule** (`gainP = (1−q)/q` per medium) as the ARRIVE default — it
   is the §2.3 identity with `arriveOnTarget`, but stating it as a RULE means future media (ice
   ARRIVE: q = 0.900 → gainP 0.111) get their gains from NOTES-movement-physics instead of
   tuning sessions. The alternative keeps per-site constants and treats the identity as
   documentation only.

---

## §8 Gates and completion tests are NOT control laws (added 2026-08-26)

§2 governs the LAW — what the servo drives toward. This section governs the TEST — when a phase, a move or a
re-centre is allowed to declare itself finished. They are different problems and the same tolerances do not
serve both.

### §8.1 The incident

The parkour ALIGN phase (added the same day, to stop a jump being committed while carrying cross-axis
momentum) shipped with this gate:

```java
return Math.abs(crossErr) < COLUMN_DEADBAND && Math.abs(crossVel) < SERVO_DEADBAND;
```

Position term, velocity term, both present — and it still wedged the long flagship at `(278,113,352)` inside
600 ticks. The trace is unambiguous:

```
phase=0/5 src=parkour:align x=278.465 vel=(-0.0346,0) yaw=-90 fwd=1.00
phase=0/5 src=parkour:align x=278.528 vel=(+0.0346,0) yaw=+90 fwd=1.00
phase=0/5 src=parkour:align x=278.465 vel=(-0.0346,0) yaw=-90 fwd=1.00
```

The bot sat **0.035** off the centreline — deep inside `COLUMN_DEADBAND` — while its cross velocity chattered
at `±0.0346`, permanently outside `SERVO_DEADBAND`. The servo's own error landed just past
`FACE_ERR_THRESHOLD`, so `actuate` took the yaw-onto-the-error escape and commanded **full throttle**, which
moves cross velocity by ~0.069 in a single tick and overshot a 0.035 error symmetrically. Forever.

### §8.2 The rule

**A gate must not name a tolerance finer than one tick of the smallest useful actuation.** On stone that
quantum is `A_G · QG ≈ 0.069` b/t of velocity; `SERVO_DEADBAND` is 0.02. Any velocity gate below the quantum
is unsatisfiable *while the servo is driving*, and a phase whose advance condition is unsatisfiable is a wedge
by construction — no timer, no recovery and no amount of servo tuning will rescue it.

Note carefully that the servos themselves use `SERVO_DEADBAND` safely. They are allowed to, because they are
POSITION-ANCHORED: the chatter costs them a little thrust and nothing else, since the position term keeps
pulling them in regardless. **The dead-band is fine as a "stop pushing" threshold and fatal as a "we are
done" threshold.** That distinction is the whole of §8.

### §8.3 The shape a gate should take

Ask where the bot will END UP, not where it is:

```java
double coast = q / (1.0 - q);                      // q = blockFriction × 0.91, the medium's own drag
return Math.abs(offset) + Math.abs(velocity) * coast < tolerance;
```

This is a projected resting position. It is a smooth function of the state (no chatter), it needs no tolerance
of its own beyond the geometric one the phase already has, and it separates the real cases cleanly — measured
on the two flagship states this gate has to tell apart:

| state | offset | cross vel | projected | verdict |
|---|---|---|---|---|
| step-10 entry (a +Z `Ascend` into an +X jump) | 0.011 | 0.130 | **0.167** | align — it launched 0.181 off and lost the jump |
| the `(278,113,352)` chatter | 0.035 | 0.0346 | **0.077** | settle — harmless, and releasing is what avoids the wedge |

Pinned by `SteerControlTest.parkourCrossSettled_firesOnTheFlagshipCarry_andReleasesTheChatterThatWedgedIt`,
which asserts both rows directly.

### §8.4 Generalisation

Every completion test in the follower should be read against this: `Movement.atWaypoint`'s containment band,
`Swim.reachedSwim`'s band, `PhaseRunner` advance conditions, and every `failWhen` envelope. A test that names
an instantaneous velocity, or a distance smaller than a tick of travel at the speed in play, is the same bug.
See also the `cell-quantized-length-bugs` memory — that is this rule in the position axis.
