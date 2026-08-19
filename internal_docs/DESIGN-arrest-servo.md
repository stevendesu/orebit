# DESIGN — The arrest (braking) servo

**Status: DRAFT for owner review — NOT implemented.** 2026-08-18 design study, companion to
DESIGN-replan-handoff.md §10 (the unified U1–U6 design). Line numbers are as-of core @ HEAD
2026-08-18 and will drift.

## §0 Problem

Every stand-still state the follower owns today is **cut-inputs-and-coast**: the planless WAIT
(BotNavigator:1271-1287), the HOLD branch (BotNavigator:1264-1270), the seam CAUTION hold
(BotNavigator:1821-1856), the §10 U5 emergency drop (BotNavigator:646-668 → `dropWalkedPlan`
:699-713), the rest-gate deferral (BotNavigator:1219-1225, which drains through the WAIT branch),
and the nav-unready hold (BotNavigator:1688-1689) all end in `bot.setForward(0.0f)` and let ground
friction do the rest. On default ground that is fine (sprint carry decays below the
`REST_HSPEED = 0.02` rest threshold in ~5 ticks, ~0.6 blocks — BotSteering:102-119). On ice it is
not: blue-ice drag is ×0.900/tick, so the same input-zeroed bot slides **~2.7 blocks over ~26
ticks** before `atRest` (BotSteering:143-152) admits it.

Three consumers want better:

- **(i) Stopping ON the clamped seam** while a seeded re-search is parked/pending (the §7/U2
  CAUTION hold) — today the hold zeroes forward and *hopes* the bot stays on the seam cell; on ice
  the carry can skate it across the boundary it is supposed to hold.
- **(ii) The U5/PANIC `atRest` wait** — the rest-gated planless pickup cannot fire until the slide
  ends; on ice that is ~23 ticks of dead latency *plus* ~2 blocks of uncontrolled displacement of
  the relaunch anchor.
- **(iii) The motivating hazard**: full speed on blue ice when the floor ahead becomes lava. U4
  already classifies floor→fluid as move-invalidating (`incompatibleCell`'s ground-family floor
  verdict, BotNavigator:754, 848-851 — lava IS fluid), so U1/U5 fire the same tick — but the
  response is a friction coast that delivers the bot into the very cell the trigger just convicted.

## §1 What exists — the control surface inventory (all verified)

**The bot can already brake.** Braking-by-input is an established, owner-ratified idiom in three
separate places:

1. **The ground velocity servo** (`SteerControl.groundServo`, SteerControl:1198-1310; selected by
   `-Dorebit.ground.drive`, default `"servo"`, SteerControl:145-156, dispatched from `drive()`'s
   land branch SteerControl:2264-2268). It closes the loop on actual momentum: velocity error
   `desired − current`, **face along the error, forward ∝ |error|** — "an overshoot is killed with
   REVERSE thrust (the error points up-track → the yaw flips 180° → the W key becomes a brake —
   essential on ice, where merely releasing forward coasts forever)" (SteerControl:1177-1181). It
   can and does target *low* velocities (the hazard ramp floors at `SERVO_TURN_FLOOR = 0.11`,
   SteerControl:180-187) — but it is **plan-shaped**: it needs a `SteerView` segment, and it is
   only ever called from `drive()` while a step is executing. In WAIT/HOLD/CAUTION there is no
   step, so it never runs. It has no zero-velocity target today (the deadband coasts, the cruise
   ceiling saturates), but the actuation primitive is exactly what an arrest needs.
2. **The step-off gate's arrest** (`stepOffGate`, SteerControl:857-885): when the predicted coast
   `v/(1−f)` would leave the landing lane, it WRITES arrest inputs for the tick — "the pure cross
   servo — desired along-speed **ZERO**, desired cross velocity toward the centreline"
   (SteerControl:844-849), actuated as `faceHorizontally(err); setForward(min(1, SERVO_GAIN·|err|))`
   (SteerControl:874-884). This is a *bounded, stateless, per-tick* stop servo already in tree —
   the arrest servo below is this method with the lane geometry removed.
3. **The projected-stop brake** (`arriveOnTarget`, SteerControl:426-536 — the lip-margin work,
   core 2bb6033): servo on `target − (position + coast·velocity)` with
   `GROUND_COAST = 0.546/(1−0.546)` / `AIR_COAST = 0.91/(1−0.91)` (SteerControl:423-424), so it
   "brakes the moment the projection overshoots". Two owner rulings live here and bind any new
   brake: **heading is held, braking is signed REVERSE `setForward`** — "vanilla scales `zza`
   symmetrically, so reverse thrust has the same authority as forward … the bot simply moon-walks
   the last few centimetres instead of turning around" (SteerControl:449-456, negative `along` fed
   to `setForward` at :524-534); and **cross-axis error is corrected by STRAFE, not by yawing**
   (`setStrafe`, SteerControl:458-466; seam method BotSteering:301-332).

**Control authority.** From NOTES-movement-physics.md:32-38: ground accel/tick =
`speed · 0.21600002/f³ · input·0.98`; ground drag = `f·0.91`. On stone (f=0.6) walk-thrust braking
is 0.098 b/t² against a 0.546 retention; on blue ice (f=0.989) thrust collapses to 0.0219 b/t² but
retention rises to 0.900 — thrust is weak there, but friction is weaker, which is precisely why
counter-thrust wins big on ice (§2). Reverse and forward authority are identical (symmetric `zza`,
SteerControl:453-455). Sprint (+30% speed attribute → ×1.3 thrust) is available via
`setSprinting` but is reset at tick-top (AllyBotEntity:560) and is deliberately NOT used here
(§6).

**Who owns inputs and yaw in the stand-still states.** The tick-top reset zeroes
`xxa/yya/jump/sprint/sneak` but deliberately NOT `zza` (AllyBotEntity:556-561; the §1 wedge's
third fact) and never touches yaw. In WAIT/HOLD/CAUTION nothing writes yaw at all — the sites
write only `setForward(0f)`. So an arrest servo hooked at those sites has **uncontested ownership
of yaw, forward, and strafe**, and the §6 install-time zeroing (BotNavigator:1242, 1432, and the
boundary-swap site) already overrides it on the tick a plan installs.

## §2 The math — coast vs counter-thrust stopping distances

Recurrence (integration order per NOTES-movement-physics.md:39): `v_move = v − a·input`,
displacement advances by `v_move`, then `v ← v_move · q`. Braking input = `min(1, 18·|v|)`
opposing motion (the `SERVO_GAIN` proportional form — see §3 for why proportional, not bang-bang).
"Stop" = |v| < `REST_HSPEED` (0.02), the same threshold `atRest` reads. Walk-thrust `a` and drag
`q` per medium as derived above; v₀ = that medium's walk/sprint terminal, plus the servo's
`SERVO_GROUND_CRUISE = 0.35` ceiling as the worst speed the ground servo ever permits
(SteerControl:214-222).

| Medium | v₀ | **Coast** dist/ticks | **Brake (walk thrust)** dist/ticks | Brake (sprint thrust) |
|---|---|---|---|---|
| stone (q=0.546, a=0.098) | walk 0.216 | 0.43 bl / 4 t | 0.12 bl / 2 t | 0.09 / 2 |
| | sprint 0.281 | 0.59 / 5 | 0.18 / 2 | 0.15 / 2 |
| | 0.35 | 0.73 / 5 | 0.29 / 3 | 0.22 / 2 |
| packed/plain ice (q=0.8918, a=0.0225) | walk 0.208 | 1.75 / 21 | 0.57 / 6 | 0.46 / 5 |
| | sprint 0.270 | 2.32 / 23 | 0.89 / 8 | 0.74 / 7 |
| | 0.35 | 3.05 / 25 | 1.32 / 9 | 1.13 / 8 |
| blue ice (q=0.900, a=0.0219) | walk 0.219 | 1.99 / 23 | 0.65 / 7 | 0.53 / 6 |
| | **sprint 0.284** | **2.66 / 26** | **1.00 / 8** | 0.84 / 7 |
| | 0.35 | 3.32 / 28 | 1.40 / 10 | 1.19 / 8 |

Headline: **on blue ice at sprint speed, counter-thrust stops in ~1.0 block / 8 ticks where the
coast slides ~2.7 blocks over ~26 ticks** — a ~2.7× distance cut and ~3× latency cut. For the
ice-into-lava scenario, U1/U5 fire the tick the edit epoch advances (BotNavigator:1746-1760); the
lava seal at the current step's destination is typically ≥1–1.5 blocks from the bot's centre when
it lands, so braking stops short of the cell where coasting slides through its near face with
~1.7 blocks of momentum to spare. (A seal directly *under the feet* is unsavable by any brake and
is out of scope — the replan's lava-swim moves own that.) On stone the arrest is a near-no-op:
2 ticks vs 4–5 — consistent with the servo philosophy that on normal friction the closed loop
degenerates to today's behavior.

## §3 Mechanism — one method, two targets, zero new state

Add to `SteerControl` (beside `stepOffGate`'s arrest, whose actuation it generalizes):

```java
/** Ground ARREST servo: actively brake to rest (tx,tz = NaN → stop anywhere) or station-keep on a
 *  column. Engages ONLY grounded-on-land; every other medium falls back to a plain zero-forward.
 *  Stateless: re-derived from live velocity every tick; quiescent below REST_HSPEED. */
public static void arrest(BotSteering b, double tx, double tz)
```

Per tick:

1. **Medium gate** (§5): `if (!b.grounded() || b.inWater() || b.inLava() || b.onClimbable()) {
   b.setForward(0f); b.setStrafe(0f); return; }` — identical net effect to today's sites in every
   non-ground medium; `atRest` already reads those media as at-rest (BotSteering:143-146) so no
   consumer is starved.
2. **Desired velocity.** No target (`tx` NaN — the WAIT/U5 form): desired = **0**. With a target
   (the seam form): desired = `min(SERVO_CROSS_CAP, SERVO_CROSS_GAIN · dist) · dir(target −
   bot)` — the capped proportional pull `stepOffGate` already uses (SteerControl:872-873), which
   both kills the slide and re-centres on the seam column; at the centre it reduces to desired 0.
3. **Error** `e = desired − v` (horizontal). If `|e| < REST_HSPEED`: write `setForward(0f)`,
   `setStrafe(0f)`, leave yaw alone — quiescent, and (no-target form) `atRest` reads true on the
   same threshold the same tick, so the arrest's "done" and the rest gate's "go" coincide by
   construction, no second epsilon.
4. **Actuation — the owner's arriveOnTarget frame, not a yaw-flip** (SteerControl:449-466):
   heading = current velocity direction (well-defined here: |v| ≥ |e| ≥ 0.02 in the no-target
   form); `faceHorizontally(heading)`; decompose `e` into along/cross; vector-saturate
   (arriveOnTarget:526-534); `setForward((float) along·k)` — **negative**, the moon-walk brake —
   and `setStrafe((float) cross·k)`, with `k = min(1, SERVO_GAIN·|e|)/|e|` (proportional
   magnitude). The bot faces its direction of travel — i.e. faces the hazard it is stopping in
   front of — and backpedals. (Alternative in-tree actuation: `stepOffGate`'s face-the-error +
   positive forward, SteerControl:878-883. Same authority; the moon-walk form is recommended for
   consistency with the 2026-08-06 "no pirouettes" ruling. Owner's pick.)

**Why proportional, not full-thrust bang-bang** (the one real convergence subtlety): on stone,
full-thrust reversal overshoots a small residual velocity and the sign-alternating fixed point
`|v*| = a·q/(1+q) = 0.098·0.546/1.546 ≈ 0.035` sits ABOVE the 0.02 deadband — a permanent
chatter that never satisfies `atRest`. With the `SERVO_GAIN`-scaled input the thrust shrinks with
|e| (effective `a(v) ≈ 0.173·v` near zero on stone, strictly less than v), so the servo converges
monotonically into the deadband on every medium; on ice even bang-bang converges
(`a·q/(1+q) = 0.0219·0.9/1.9 ≈ 0.010 < 0.02`), so the proportional form is safe everywhere. No
timers, no state, no new constants — the deadband IS `REST_HSPEED`, the gains are the existing
`SERVO_GAIN`/`SERVO_CROSS_GAIN`/`SERVO_CROSS_CAP`.

## §4 Hook points

All are 1-line substitutions of an existing `bot.setForward(0.0f)`; none change control flow.

| Site | Today | Becomes | Form |
|---|---|---|---|
| Planless WAIT (BotNavigator:1287) | zero forward, coast | `arrest(bot)` (no target) | (ii) — shortens the U5/PANIC rest-drain from ~26 t to ~8 t on ice and pins the relaunch anchor ~1.7 blocks earlier |
| HOLD (nav gave up / window BLOCKED) (BotNavigator:1269) | zero forward | `arrest(bot)` | same stand-still semantics, honest stop |
| Seam CAUTION hold (BotNavigator:1855) | zero forward | `arrest(bot, seamX+0.5, seamZ+0.5)` with the seam cell = `path.waypoint(pendingSeam)` (the cell the bot just settled on — the hold fires at `waypointIndex == pendingSeam+1`, BotNavigator:1839) | (i) — actively holds the bot ON the seam while the seeded search is in flight, instead of hoping friction does |
| U5 same-tick return (steerAlongPath:1758-1760, after `dropWalkedPlan`) | forward zeroed by `dropWalkedPlan`:712, first braking tick lost | call `arrest(bot)` after the drop, before the return | (iii) — the drop tick is the fastest tick of the slide (~0.28 bl on ice); braking it is free |
| Rest-gate deferral (BotNavigator:1219-1225) | falls through to WAIT | covered by the WAIT hook — no separate change | (ii) |
| Nav-unready hold (BotNavigator:1689), post-terminus zero (BotNavigator:1810) | zero forward | optional, same substitution | consistency |

Deliberately NOT hooked: the three §6 install-site zeroings (they precede STEER, not a
stand-still state) and anything inside `drive()`/the movement steer path — the plan-shaped servos
own those.

## §5 Hazards and their gates (each maps to a prompt-listed hazard)

- **Never mid-air/ballistic.** The `grounded()` gate is re-read every tick; a bot that slides over
  an edge mid-arrest reverts to plain zero-input that tick. Air-braking a fall is `arriveOnTarget`'s
  job under a *plan*; a planless ballistic bot WAITs to touchdown exactly as today
  (BotNavigator:1043-1044). The CAUTION-hold site is additionally already gated
  `(grounded || isInWater)` and `!onDamagingFloor()` (BotNavigator:1841-1843) — the arrest adds no
  hold on a damaging floor that the hold itself would refuse.
- **Never fight the swim depth autopilot.** `inWater()/inLava()` fall back to zero-forward;
  `holdDepth` (SteerControl:1926) is only ever called by swim moves and `drive()`'s in-water
  branch (SteerControl:2261-2263), neither of which runs in a stand-still state — and `atRest`
  exempts fluids anyway (BotSteering:143-146), so a floating bot adopts at tick rate, unchanged.
- **Never press near a climbable.** `onClimbable()` falls back: horizontal input near a vine trips
  vanilla's involuntary climb (`(horizontalCollision || jumping) && onClimbable → vy=+0.2`,
  the release rule at SteerControl:2225-2253); the climbable stance servos own that medium.
- **Never reintroduce stale thrust** (the §1 wedge's third fact, `zza` spared by the tick-top
  reset, AllyBotEntity:556-561). The arrest *writes* `setForward`/`setStrafe` every tick it runs —
  including an explicit 0 in its quiescent and fallback branches — at exactly the sites that today
  write `setForward(0f)`, so the invariant "every stand-still tick writes `zza`" is preserved,
  and the §6 install-site zeroing still overrides on the adoption tick. Strafe (`xxa`) is
  tick-top-reset anyway (AllyBotEntity:556).
- **Braking direction is intrinsically safe.** Thrust strictly opposes velocity (or pulls toward
  the seam column), so the arrest can only move the bot *away* from whatever it was sliding
  toward; it can never inject motion into a hazard the way a mis-aimed pursuit could.

House-rules check: no timers (pure per-tick state test on live velocity); no recovery machinery
(this changes *how* the already-ratified stops stop, not *when* anything replans — U1–U6 flow
untouched); allocation-free (statics, primitives, the existing `Geom`-style scratch is not even
needed); no new constants.

## §6 Rejected/deferred variants

- **Sprint-assisted braking** (`setSprinting(true)` while braking, +30% thrust: blue-ice sprint
  stop 1.00 → 0.84 bl): real but small; adds an input the tick-top reset must keep fighting and a
  vanilla sprint-state edge (server-side sprint cancellation checks); not worth it for the first
  cut. Recorded so the numbers don't get re-derived.
- **Jump-braking / sneak-braking**: sneak arms `maybeBackOffFromEdge` (the step-off killer,
  SteerControl:816-820) and neither beats reverse thrust; rejected.
- **Pre-emptive braking while a plan still executes** (brake *before* U1 convicts anything):
  already exists where it is wanted — the ground servo's hazard ramp (`groundOvershootHazard`,
  SteerControl:1819) brakes toward planned hazard corners. Extending arrest into the executing
  path would re-litigate the REJECTED `steppingOff` pre-brake (SteerControl:474-489).

## §7 Verification plan

1. **Unit** (`ArrestServoTest`, the `PrefixIntegrityTest` pure-static pattern with a scripted
   `BotSteering` that integrates the vanilla recurrence): (a) stop distance/ticks within the §2
   table ±1 tick on stone/packed/blue ice from walk+sprint terminals; (b) monotone convergence, no
   deadband chatter (the §3 fixed-point cases); (c) medium gates — airborne/water/lava/climbable
   ticks write exactly forward 0 / strafe 0; (d) quiescence ⇔ `atRest` on the same tick
   (no-target form, grounded); (e) seam form settles inside the seam cell from an entry carry of
   0.28 b/t on ice.
2. **Course tile** — the ice-into-lava shape, extending the §10 proof-tile family (`prefixseal` /
   `currentseal` / `currentseal-on-ice` in ReplanCourse): **`icelava`** — a ≥12-block blue-ice
   runway (1-wide, walled or void-flanked so the lane is honest), bot driven to sprint-terminal
   slide mid-runway, script converts the CURRENT step's destination floor to lava on a single
   epoch advance. Assert: U5 fires the same tick (the `prefix-break: CURRENT step` log,
   BotNavigator:660-666); the bot's feet never enter the lava cell and it takes zero damage;
   `atRest` within ≤12 ticks of the drop; the rest-gated pickup relaunches from the stopped cell
   and the new plan avoids the seal. A/B leg with the arrest disabled (temporary
   `-Dorebit.arrest=legacy` switch, the `GROUND_DRIVE`/`orebit.swim.bleed` A/B pattern,
   SteerControl:145-156) documents the motivating failure: coast ~2.7 blocks → slides into the
   lava, sinks, and only then rests. A second variant seals at the SEAM under a pending seeded
   search (the `prefixseal` shape on ice) asserting the CAUTION hold + seam arrest keep the feet
   on the seam cell until adoption.
3. **Regression:** ice `iceturn`, swim 17/17, parkour, and the flagship must stay green — the
   arrest only replaces zero-input ticks inside stand-still states, so steady execution is
   byte-identical; deltas are expected only in stop length/latency at replan boundaries. No JMH
   run needed (no planner hot path is touched), per the perf-process rules.

## §8 Related registered follow-up — hazard-class floor changes as move-incompatibility

U4's ground-family verdict invalidates **floor→fluid** (BotNavigator:754, 848-851) — which is why
the ice-into-**lava** scenario arms U1/U5 at all — but "solid→different-solid stays compatible"
(DESIGN-replan-handoff.md §10 U4). Two gaps follow, registered as the safe→damaging follow-up:

- a floor swapped to a **solid damaging** block (magma, campfire, cactus-adjacent shapes) stays
  "compatible": no prompt trigger, no U5, and the bot walks onto it;
- a **passable damaging** occupant appearing in a body/head cell (fire, sweet berry) passes the
  "must remain passable" test and likewise never triggers.

The fix composes with, and is sequenced after, this servo: extend `incompatibleCell`'s floor/body
verdicts with a `NavBlock.isDamaging` class check (the exact read `onDamagingFloor` already uses,
BotNavigator:878-889), gated on `caps.takesDamage` so an immortal bot is bit-identical — in the
conservative U4 direction (a *newly* damaging cell on a previously safe step; never the reverse).
Once that lands, the same U1 prompt → U2 clamp → U5 emergency machinery arms for magma/fire
exactly as for lava today, and the arrest servo is what turns those triggers into a stop that
actually happens short of the hazard. Without the servo the extension would fire triggers the
coast then betrays — which is why the servo ships first.
