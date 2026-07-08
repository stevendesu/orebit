# Physics-Derived Parkour Envelopes — Design

**Status:** design for owner review. No code changed anywhere; read-only investigation of
`C:/Users/steve/Repos/personal/orebit-mc121-wt` (mc-1.21 era worktree) + javap of the
loom-cached, mojang-mapped 1.21.11 Minecraft jar.

**Deliverable of:** the owner-ratified direction to replace the hard-coded Parkour /
DiagonalParkour gap envelopes with boot-time constants derived from a **closed-form solution
of the discrete Minecraft sprint-jump recurrences** (owner directive: no tick-by-tick
simulator; the recurrences are geometric series with exact closed forms; floor conservatively).

Supporting file: `verify_closed_form.py` (this directory) — evaluates every closed form below
numerically and cross-checks it against the raw recurrence (same discrete system, exact match
to 4+ decimals at every checked tick).

---

## 1. Ratified vs. proposed

### Ratified by the owner (this design does not revisit)

| Decision | Content |
|---|---|
| One hard-coded policy constant | `MAX_CLEARED_AIR = 3.0` blocks, with the comment: the physical single-sprint-jump limit is ~4 blocks of cleared air, but 4 requires tick-perfect last-pixel takeoff; 3 removes the tick-perfect requirement and keeps routes human-followable. |
| Everything else derived | Max flat gap, max rising gap, max falling gap per drop depth, max diagonal gaps (incl. rising/falling diagonals if they exist) — computed once at JVM startup from the discrete sprint-jump ballistics, stored in global constants. |
| Closed form, not simulation | The discrete recurrences are geometric series; derive exact closed forms, solve for the landing tick, **floor toward shorter reach**. Continuous-calculus approximations only as sanity cross-checks. |
| Rising 3 is OUT | Insufficient airtime — the inverse of why Falling 4 is fine. |

### Proposed by this design (needs owner sign-off)

1. **The admission rule** (§5.4): a row `(Δy, g)` ships iff the closed-form arc, taking off
   **from the executor's own takeoff trigger point** (`TAKEOFF_EDGE` = 0.35 past cell centre,
   cardinal; 0.40 along-line, diagonal) at **full-sprint steady speed**, achieves horizontal
   overlap with the landing column no later than the last tick its feet are at/above the
   landing top. The anti-tick-perfect margin is *structural*: the physical maximum uses the
   extra 0.45-block hitbox-overhang runway (centre up to edge+0.3) that the planned jump never
   schedules. This single rule reproduces **all six ratified outcomes** with ≥ 0.06-block
   margins (§5.5) and needs no tunable slack quantum.
2. **Policy-cap generalization** (§5.6): cleared air ≤ `MAX_CLEARED_AIR + drop` for falling
   rows, and drop depth ≤ 3 (today's depth bound, restated as policy — `Fall` owns deeper
   descents). At current physics constants the cap never binds below the physics bound, so it
   is a pure safety clamp + intent documentation.
3. **Where the table lives**: a new `movements/ParkourEnvelope.java` (static-init smart object,
   §7.2), consumed by table lookup — zero hot-path change.
4. **Rising/falling diagonals stay out of scope** for this change (they don't exist today,
   §3.3); the table makes adding them trivial later, and §5.5 gives their derived verdicts.
5. **Costs stay hard-coded in phase 1**; deriving airtime costs from the same closed form is
   scoped as an optional, separately-measured phase 2 (§7.6) — it changes f-values and search
   behavior, which the perf-process rules treat as its own reviewed change.
6. **The rising-3 "takeoff-tuning pathology" gets closed, not tuned** (§6): the closed form
   proves the row was never makeable from the planned takeoff point even at full sprint
   (budget 2.488 blocks vs. 2.85 required). The in-game undershoot was the physics, not the
   sprint windup.

---

## 2. Current envelope code (evidence)

All paths under `src/main/java/com/orebit/mod/pathfinding/blockpathfinder/` in the worktree.

### 2.1 `movements/Parkour.java` (909 lines)

One shared per-cardinal scan (`scanDirection`, lines 468–611) walks columns
`c = 1..maxGapAll+1` reading the node-level cell, and classifies landings into four classes.
The envelope is four hard-coded constants:

| Constant | Value | Line | Meaning |
|---|---|---|---|
| `PARKOUR_MAX_GAP` | `3` (public, mutable) | 270 | flat-row cap; the v1 tuning knob, tests mutate/restore it |
| `RISE_MAX` | `3` | 275 | rising(+1) gap cap ("full parabola-legal envelope"; known in-game undershoot at 3) |
| `FALL_MAX` | `{0, 4, 4, 4}` | 283 | falling gap cap by drop (index = drop depth 1–3; array length = deepest drop offered) |
| `OFFSET_C_LIMIT` | `3` | 344 | offset-tier shapes `(2,±1)`,`(3,±1)`; legality `c² ≤ flatMax·(flatMax+2)` (line 734) |

Scan mechanics that consume these: `flatMax = min(PARKOUR_MAX_GAP, COST.length−1)` (line 422),
`capsDrop = min(fallMax.length−1, caps.maxFallDistance())` (line 426), `fallGapCap` = max over
still-capable drop rows (lines 430–434), `maxGapAll = max(flatMax, riseMax, fallGapCap)`
(line 437, the scan horizon). Rising emission is gated `g <= riseMax` (lines 523, 547);
falling emission `g <= fallMax[dr]` (line 587).

Cost constants (today hard-coded, envelope-adjacent):

| Constant | Value | Line | Note |
|---|---|---|---|
| `RUNUP_COST` | `Traverse.FLAT_COST` = 4.633 | 286 | |
| `AIR_COST` | `{0, 8, 11, 14, 16}` | 293 | `[4]` used only by the falling 4-gap |
| `RISE_EARLY_TICKS` | `2` | 300 | credit for the +1 floor intercepting the arc early (~t8–9 vs ~t12) |
| `FALL_EXTRA` | `{0, 2, 3, 5}` | 307 | marginal descent ticks (t14/t15/t17 − t12) — *already parabola-derived by hand* |
| `COMMIT_PENALTY` | `3` | 310 | |
| `COST` | RUNUP+AIR+COMMIT → 15.6/18.6/21.6 | 313–318 | |
| `OFFSET_COST` | interp. at √(c²+1) → ≈16.34/19.12 | 373–377 | already computed at class init via `interpolateAir` (line 384) |
| `TAKEOFF_EDGE` | `0.35` | 400 | executor takeoff trigger, blocks past cell centre |

The class Javadoc (lines 67–100) documents the envelope table and its hand-derived parabola
(`vy₀ = 0.42`, `vy ← (vy − 0.08)·0.98`, feet cross 0 ≈ t12 / −1 ≈ t14 / −2 ≈ t15 / −3 ≈ t17,
apex +1.25 at t6, horizontal ≈ 0.28 b/t) — the closed form below confirms every one of these
numbers exactly. Lines 84–91 document the rising-3 undershoot as a takeoff-tuning pathology
(this design overturns that diagnosis, §6).

Executor (`plan()`, lines 827–907): RUNUP → TAKEOFF → AIRBORNE → LAND. Sprint is asserted in
every driving phase (`b.setSprinting(sprint)`, lines 860/869/898; `sprint` = displacement² ≥ 9
or rising, line 841). The takeoff trigger fires when along-line progress from the from-cell
centre reaches `TAKEOFF_EDGE` (line 864–865). Forward input is `1.0f`
(`SteerControl.steerTowards` → `b.setForward(1.0f)`, `SteerControl.java:101`).

### 2.2 `movements/DiagonalParkour.java` (311 lines)

| Constant | Value | Line | Meaning |
|---|---|---|---|
| `MAX_GAP` | `3` | 89 | diagonal gap cap (2 owner-verified; 3 "at the physics limit", shipped since s52) |
| `AIR_COST` | `{0, 10.5f, 14.5f, 19f}` | 95 | cardinal flat table interpolated at displacement `(g+1)·√2` |
| `RUNUP_COST` | `Diagonal.COST` ≈ 6.55 | 98 | |
| `TAKEOFF_EDGE_ALONG` | `0.40` | 104 | diagonal takeoff trigger, blocks along the line past centre |
| `CORNER_MAX_TOP_Y` | `16` | 111 | corner arc-over rule (not envelope) |

`scanDirection` (lines 146–192) is flat-only: the landing test is `ctx.standable(fd)` at
**node level `y`** (line 157) and the emitted candidate is `out.accept(cx, y, cz, …)`
(line 181) — `dy` is always 0.

### 2.3 `MovementContext.java` — the block-height canon

`JUMP_RISE = 20` sixteenths (line 40; derivation comment lines 34–38 cites the same discrete
parabola: apex ≈ 1.25 → `1.25 × 16 = 20`), `STEP_ASSIST_MAX_RISE = 9` (line 51),
`rise(dyBlocks, destTopY, startTopY)` (line 60), `floorSurface(x,y,z)` (line 75). Parkour's
rising emission already runs `rise(1, landTop, startSurf) > JUMP_RISE` (Parkour lines 674–675)
— slab-aware. The closed form's apex `y(6) = 1.2522` **confirms** `JUMP_RISE = 20` (20/16 =
1.25 ≤ 1.2522); no canon change needed.

### 2.4 Owner's question: do rising/falling DIAGONAL variants exist today?

**No.** DiagonalParkour v1 is flat-only by explicit design (class Javadoc lines 13–15:
"v1 is FLAT only; rising/falling diagonal landings are deferred") and by code: the scan only
tests the node-level cell for standability and always emits at the source `y` (§2.2). Cardinal
Parkour's offset tier is also flat-only (Parkour Javadoc lines 194–196).

**Would the physics table make adding them trivial?** Yes — the vertical closed form (landing
tick per Δy, §5.1) is direction-agnostic; a diagonal rising/falling row needs only the diagonal
horizontal geometry (§5.3) against the same per-Δy budget, i.e. one more lookup row in the same
boot-time table. What the two named candidates would require, and their derived verdicts:

- **Diagonal Falling 3 (drop 1):** required centre-travel `(3+0.2)·√2 − 0.40 = 4.126` vs.
  budget `X(T(−1)) = 3.914` → **OUT by 0.21 blocks** from the planned takeoff. (It becomes
  feasible at drop ≥ 2: budget 4.199 ≥ 4.126, margin +0.07 — marginal.) Beyond the envelope
  row, implementing it needs a falling down-scan per diagonal column + `Fall`-style descended
  column pricing + the drop-control handoff in the diagonal plan (all precedented in cardinal
  Parkour), plus corner-pair rules for the descent column.
- **Diagonal Rising 2:** required `2.711` vs. rising budget `X(T(+1)) = 2.488` → **OUT by
  0.22 blocks**. Diagonal rising max derives to **1**. Implementing would need the rising y+4
  row swept along the diagonal supercover + the raised corner prisms.

So if the owner ever wants diagonal verticals, the derived table admits: rising {1}, falling
drop-1 {1–2}, drop-2/3 {1–3 marginal}. Not in scope here.

---

## 3. Verified sprint-jump physics (MC 1.21.11, decompiled evidence)

Source: `javap -p -c` on the **mojang-mapped** merged jar in the loom cache —
`~/.gradle/caches/fabric-loom/1.21.11/neoforge/21.11.42/minecraft-merged-mojang.jar`
(mojang-official names; the physics methods below are vanilla bodies). Full disassemblies
saved as `../LivingEntity.javap.txt`, `../Player.javap.txt`, `../Entity.javap.txt` in the
scratchpad. The bot is a `ServerPlayerEntity` subclass running the full vanilla player tick
(CLAUDE.md, s38), so **player** physics applies verbatim.

| Quantity | Value | Decompiled evidence |
|---|---|---|
| Jump initial vy | **0.42** | `LivingEntity.getJumpPower(F)` = `Attributes.JUMP_STRENGTH` attribute × blockJumpFactor + jumpBoostPower; `Attributes` static-init registers `jump_strength` default `ldc2_w 0.41999998688697815`. `jumpFromGround()` sets `setDeltaMovement(x, max(jumpPower, vy), z)`. No jump-boost effect on the bot ⇒ 0.42. |
| Sprint-jump horizontal impulse | **+0.2 in facing** | `jumpFromGround()`: `if (isSprinting()) addDeltaMovement(new Vec3(−sin(yaw)·0.2, 0, cos(yaw)·0.2))` (constant `ldc2_w 0.2d`). |
| Gravity per tick | **0.08** | `LivingEntity.getDefaultGravity()` = `Attributes.GRAVITY` value; registered default `ldc2_w 0.08d`. Applied in `travelInAir`: `vy − getEffectiveGravity()`. |
| Vertical drag | **× 0.98** | `travelInAir`: `setDeltaMovement(vx·f4, (vy − g)·0.98f, vz·f4)` (constant `ldc 0.98f`, applied after gravity, after the move). |
| Horizontal air drag | **× 0.91** | `travelInAir`: `f = onGround() ? blockFriction : 1.0f;  f4 = f · 0.91f` — airborne f4 = 0.91. |
| Horizontal ground drag | **× 0.546** | same expression grounded: default `Block.getFriction()` = 0.6 → 0.6·0.91 = 0.546. **Quirk:** f4 is computed at `travelInAir` entry, *before* the move — on the jump tick `onGround()` is still true (jump happens earlier in `aiStep`), so the jump tick's drag is the *ground* 0.546 and its input acceleration is the *ground* rate. |
| Ground accel per tick | **speed · 0.21600002/f³ · input** | `getFrictionInfluencedSpeed(F)`: `onGround() ? getSpeed() · (0.21600002f / (f·f·f)) : getFlyingSpeed()`; `moveRelative(speed, input)` adds `getInputVector(input, speed, yaw)` to the velocity **before** the move. |
| Air accel per tick (sprinting) | **0.025999999 · input** | `Player.getFlyingSpeed()` (override): not flying → `isSprinting() ? 0.025999999f : 0.02f`. |
| Player speed | **0.1 walk, 0.13 sprint** | `Player.createAttributes()`: `MOVEMENT_SPEED, ldc2_w 0.10000000149011612`; `LivingEntity` sprint modifier `ADD_MULTIPLIED_TOTAL, ldc2_w 0.30000001192092896` → ×1.3. |
| Input magnitude | **0.98** | `LivingEntity.aiStep()`: `xxa *= 0.98f; zza *= 0.98f`. The bot holds full forward (`SteerControl.steerTowards` → `setForward(1.0f)`), so effective input = 0.98 (length² = 0.9604 ≤ 1 ⇒ `getInputVector` does not normalize, scales by speed). |
| Integration order per tick | input-accel → move → drag/gravity | `handleRelativeFrictionAndCalculateMovement`: `moveRelative(…)` then `move(SELF, delta)`; `travelInAir` then stores `(vx·f4, (vy−g)·0.98, vz·f4)`. So position advances by the *post-acceleration, pre-drag* velocity. |

**Cross-checks.** Steady sprint ground speed from these constants:
`a_g = 0.13·(0.21600002/0.216)·0.98 = 0.127400`; `v∞ = a_g/(1−0.546) = 0.280617` b/t =
**5.612 m/s** — exactly the community-documented sprint speed. Terminal fall velocity from the
vertical fixed point: −3.92 b/t — the documented terminal velocity. Apex `+1.2522` at t6 and
feet-cross ticks 12/14/15/17 for 0/−1/−2/−3 — exactly the numbers the existing Parkour Javadoc
(lines 93–94) and `docs/movements.md` derived by hand. The existing docs' airtime assumptions
(~12-tick flat arc, ~0.28 b/t sprint, drops at t14/t15/t17) are all **confirmed**, with one
refinement: the sustained airborne speed under held sprint input is 0.283 b/t and the *jump
tick itself* moves 0.4806 (boost + ground-accel + steady momentum), so total 12-tick reach is
3.34 blocks of centre-travel from the takeoff point — not `12 × 0.28 = 3.4` from naive
multiplication; the difference is where flat-4 lives or dies (§5.5).

**Version stability:** every constant above (0.42, 0.08, 0.98, 0.91, 0.6, 0.2, 0.1×1.3,
0.026/0.02, 0.98 input) has been stable since well before 1.17; the 1.20.5 attribute migration
(JUMP_STRENGTH/GRAVITY becoming attributes) kept the default values. The envelope class is pure
Java with no MC imports — it lives in common `src/`, no overlay, both eras.

---

## 4. What the executor actually does at takeoff (run-up decision)

Evidence (Parkour `plan()`, §2.1): the RUNUP phase drives full-forward **with sprint asserted**
from wherever the bot is on the from-cell, and TAKEOFF triggers at 0.35 blocks past the cell
centre. The bot normally *arrives* at the from-cell carrying momentum from the previous
waypoint (Traverse chains at up to `v∞`); `RUNUP_COST` prices "one walk step onto the takeoff
edge".

**Decision: derive at full-sprint steady-state arrival** (`v_pre = v∞·0.546` stored at the
jump tick, i.e. the bot has been sprinting into the cell). Rationale:

- This is the state the executor *aims for* (sprint asserted the whole approach), and the
  owner's in-game verifications that ratified the envelope (flat 3 ✓, falling 4 ✓) were made
  by exactly this executor — the derivation must reproduce those, and does (§5.5).
- A true standing start does **not** reach steady speed in 0.35 blocks; its takeoff deficit
  (~0.02–0.03 b/t) compounds to ~0.1–0.15 blocks over a 12–14-tick arc, which would kill the
  owner-verified falling 4 (margin +0.064). So standing-start is the *wrong* model for the
  ratified data. Known consequence, stated honestly: a bot that genuinely jumps from a cold
  start (first move of a plan onto an immediate gap) is outside the model on the two thinnest
  rows (falling 4, and rising 2's diagonal cousin if ever added). If that ever shows up
  in-game, the fix is a takeoff-speed demand in the RUNUP phase (executor), not envelope
  shrinkage — the same division of labor s52 ratified.

---

## 5. The closed-form derivation

The discrete per-tick system (verified in §3) is linear with constant coefficients — both axes
are geometric series with exact closed forms. Notation: tick 1 is the jump tick; positions are
displacements of the bot **centre** from the takeoff point; heights are feet above takeoff
floor top.

### 5.1 Vertical

Recurrence (from `travelInAir`): the velocity used on tick t+1 is
`vy(t+1) = (vy(t) − g)·q_v`, with `g = 0.08`, `q_v = 0.98`, `vy(1) = 0.42`.

Substituting `vy(t) = w(t) − K` turns this into pure geometric decay when
`−K = (−K − g)·q_v`, i.e.

```
K = g·q_v / (1 − q_v) = 0.08·0.98 / 0.02 = 3.92        (the terminal velocity)
vy(t) = (vy(1) + K)·q_v^(t−1) − K = 4.34·0.98^(t−1) − 3.92
```

Feet height after T ticks is the geometric sum:

```
y(T) = Σ_{t=1..T} vy(t) = (vy(1)+K)·(1 − q_v^T)/(1 − q_v) − K·T
     = 217·(1 − 0.98^T) − 3.92·T
```

Apex: `vy(t) > 0 ⇔ 0.98^(t−1) > 3.92/4.34` ⇔ t ≤ 6; `y(6) = +1.2522` (confirms
`JUMP_RISE = 20/16 = 1.25`).

**Landing tick** `T(Δy)` = the **largest** integer T with `y(T) ≥ Δy` (largest, not first
failure: the rising arc is below +1 at t1–2 and above it t3–8). Solved at boot by evaluating
the closed form at integer T (the equation is transcendental in T; evaluating an exact formula
at ≤ ~20 integers is not a simulation — no state is integrated). Values:

| Δy | T(Δy) | y(T) | y(T+1) |
|---|---|---|---|
| +1 | 8 | +1.0244 | +0.7967 |
| 0 | 11 | +0.1213 | −0.3235 |
| −1 | 13 | −0.8379 | −1.4203 |
| −2 | 14 | −1.4203 | −2.0695 |
| −3 | 16 | −2.7841 | −3.5628 |

(The old hand table said "crosses 0 at ~t12, −1 at ~t14…" — same events: the crossing happens
*during* tick T+1; the last supported tick is T.)

### 5.2 Horizontal

Steady sprint on the ground (drag `q_g = 0.6·0.91 = 0.546`, accel
`a_g = 0.13·(0.21600002/0.216)·0.98 = 0.127400`): fixed point
`v∞ = a_g/(1 − q_g) = 0.280617`.

Jump tick (t = 1): stored velocity from the previous tick is `v∞·q_g = 0.153217`; `aiStep`
adds the sprint-jump boost 0.2, `travel` adds the **ground** accel (onGround still true at
travel entry, §3 quirk):

```
v(1) = v∞·q_g + 0.2 + a_g = 0.480617      — moved this tick; then dragged by q_g (0.546)
```

Air ticks (t ≥ 2, drag `q_h = 0.91`, accel `a_a = 0.025999999·0.98 = 0.025480`):

```
v(2) = v(1)·q_g + a_a = 0.287897
v(t+1) = v(t)·q_h + a_a          ⇒ fixed point m = a_a/(1 − q_h) = 0.283111
v(t) = m + (v(2) − m)·q_h^(t−2)                          (t ≥ 2)
```

Cumulative centre-travel after T ticks (geometric sum again):

```
X(T) = v(1) + (T−1)·m + (v(2) − m)·(1 − q_h^(T−1))/(1 − q_h)
```

Budgets at the landing ticks: `X(8) = 2.4881`, `X(11) = 3.3442`, `X(13) = 3.9140`,
`X(14) = 4.1986`, `X(16) = 4.7675`.

### 5.3 Geometry: required centre-travel per row

Hitbox half-width 0.3 (0.6×0.6 box). Coordinates from the takeoff-cell centre.

**Cardinal, gap g:** landing-cell centre at `g+1`; hitbox first overlaps the landing column at
centre `= (g+1) − 0.5 − 0.3 = g + 0.2`. Takeoff at the executor trigger `+0.35`:

```
D_req(g) = g + 0.2 − 0.35 = g − 0.15
```

**Diagonal, gap g** (along-line coordinates; cell centre-to-centre `= (g+1)·√2`): the box
overlaps the landing cell when the centre is within 0.3 of the corner **in both axes**, i.e.
`0.3·√2 = 0.424` along-line before the corner projection at `(g+1)√2 − 0.5·√2`; takeoff at
`TAKEOFF_EDGE_ALONG = 0.40` along-line:

```
D_req_diag(g) = (g+1)·√2 − 0.707 − 0.424 − 0.40 = (g + 0.2)·√2 − 0.40
```

(These are the same 0.3-overhang / 0.3-early-contact terms the existing offset-tier and
diagonal Javadocs already use — Parkour lines 199–204, DiagonalParkour lines 25–39.)

**Landing model (why the budget is `X(T(Δy))`, conservative):** `Entity.move` resolves the Y
axis before X/Z. On the first tick with `y < Δy` (tick T+1), the downward move is resolved at
the *current* horizontal position — if the box does not already overlap the landing column,
the feet pass below the landing top and the subsequent X/Z move face-hits the landing block.
So overlap must exist **by the end of tick T(Δy)**; the crossing tick contributes no usable
horizontal distance. This also encodes the conservative rounding the owner asked for: the
enumeration `admit g while D_req(g) ≤ X(T(Δy))` over integers *is* the floor.

### 5.4 The admission rule (proposed — the ONE rule)

> Row `(Δy, g)` is admitted iff `D_req(g) ≤ X(T(Δy))` — the jump the **executor actually
> plans** (takeoff at its own trigger point, full-sprint arrival) makes the landing with the
> axis-ordered collision model above. Plus the policy clamp of §5.6.

The tick-perfect margin the owner wants excluded is exactly the takeoff runway this rule never
uses: physically, takeoff can be delayed until centre `= edge + 0.3 = +0.8` past centre
(0.45 blocks past the trigger), and *that* is what "last-pixel" means. The rule needs no
tunable slack constant because the slack is the geometric distance between the planned trigger
and the physical last pixel.

### 5.5 Results, and reproduction of the six ratified outcomes

Budgets and admitted maxima (all numbers from `verify_closed_form.py`; recurrence
cross-checked):

| Row | Landing tick T | Budget X(T) | Cardinal max g (margin at max / shortfall at max+1) | Diagonal max g |
|---|---|---|---|---|
| rising +1 | 8 | 2.4881 | **2** (+0.638 / g=3 short 0.362) | 1 |
| flat 0 | 11 | 3.3442 | **3** (+0.494 / g=4 short 0.506) | **2** (2.711 ≤ 3.344; g=3 needs 4.126, short 0.781) |
| falling −1 | 13 | 3.9140 | **4** (+0.064 / g=5 short 0.936) | 2 |
| falling −2 | 14 | 4.1986 | **4** (+0.349) | 3 (marginal +0.073) |
| falling −3 | 16 | 4.7675 | **4** (+0.918 / g=5 short 0.082) | 3 |

**The six ratified outcomes:**

| Ratified | Rule verdict | Margin |
|---|---|---|
| flat 3 ✓ | admitted | +0.494 blocks |
| flat 4 ✗ | rejected | −0.506 |
| rising 3 ✗ | rejected | −0.362 (insufficient airtime: T(+1)=8 vs T(0)=11 — the inverse of falling 4) |
| falling 4 ✓ | admitted | +0.064 (thin; owner-verified in-game — see §4 takeoff-speed assumption) |
| diagonal 2 ✓ | admitted | +0.633 |
| diagonal 3 ✗ | rejected | −0.781 |

**All six reproduce under the one rule.** No fudging, no comparison table needed — but for
completeness, the prompt's alternative formulation ("required ≤ physics-max-from-last-pixel −
one tick of travel ≈ 0.283"): it also reproduces all six, with different margins (flat 4 short
by 0.34, diag 3 short by 0.33, falling 4 clear by 0.23). It was not chosen because it carries
two free choices (last-pixel takeoff as the physics baseline, and the one-tick slack quantum)
where the executor-trigger rule carries zero, and its diagonal-3 rejection margin is the
weaker of the two. Either rule ships the same table today.

**Last-pixel side-note (supports the owner's `MAX_CLEARED_AIR` comment):** even from the
physical last pixel at steady sprint, flat 4 needs 3.4 and the budget is 3.344 — short by
0.056. The community's "4-block jump" additionally needs above-steady momentum (bunny-hop
carry-in / 45° strafe), i.e. beyond even tick-perfect *positioning*. The comment's "~4 blocks
of cleared air, tick-perfect" is the right characterization of the physical edge.

### 5.6 The policy cap and its generalization (proposed)

`MAX_CLEARED_AIR = 3.0` — cleared air = the open span crossed: `g` blocks (cardinal),
`g·√2` (diagonal).

- **Δy ≥ 0 (flat + rising, cardinal + diagonal):** cleared air ≤ `MAX_CLEARED_AIR`.
  Flat: g ≤ 3. Diagonal: g·√2 ≤ 3 ⇒ g ≤ 2. Rising 3 would pass this cap (3.0 ≤ 3.0) — it is
  the *physics* (airtime) that rejects it, exactly per the owner's ruling.
- **Δy < 0 (falling):** cleared air ≤ `MAX_CLEARED_AIR + drop` — a drop buys airtime, which is
  precisely the tick-perfection relief the cap exists to guarantee (falling 4 at drop 1 lands
  3 ticks after the flat arc would have; the last-pixel reserve is no longer needed to make
  the distance). Additionally **drop ≤ 3** (today's `FALL_MAX.length−1`, restated as policy:
  landing precision degrades with depth and `Fall` owns deep descents — Parkour Javadoc
  lines 95–97).

At current constants the physics bound (§5.5) is at or below the cap on every row (falling
−2/−3 physics = 4 < cap 5/6), so the cap **never widens anything and currently never binds
below physics except at flat/diagonal Δy=0 where the two coincide**. It ships as a `min()`
clamp + the intent comment, so a future physics-constant drift (or a derivation bug) can never
silently widen the envelope past human-followable. Flag for the owner: if you'd rather the
falling family stay uniformly at cleared-air ≤ 4 (`MAX_CLEARED_AIR + 1`) instead of `+drop`,
nothing changes today either way — pick the comment you want to be true later.

### 5.7 Derived table that ships (identical to today except the two ratified removals)

| Constant | Today | Derived | Change |
|---|---|---|---|
| flat max gap | 3 | 3 | — |
| rising max gap | 3 | **2** | **rising 3 removed (ratified)** |
| falling max gap, drops 1–3 | 4/4/4 | 4/4/4 | — |
| falling max drop | 3 | 3 (policy) | — |
| diagonal max gap | 3 | **2** | **diagonal 3 removed (ratified)** |
| offset shapes | (2,±1),(3,±1) | (2,±1),(3,±1) | — (flat reach unchanged; legality rule §7.3) |

---

## 6. The rising-3 pathology is CLOSED by this derivation

Parkour Javadoc lines 84–91 (and the s52 memory) document the in-game rising-3 undershoot as
"sprint speed at takeoff — a one-block runup hasn't reached the ~0.28 b/t the envelope
assumes… fix is takeoff tuning, not envelope-hiding." The closed form refutes that: **at full
steady sprint** the rising budget is `X(8) = 2.488` and rising 3 requires `2.85`. The row was
never makeable from the planned takeoff at any achievable speed (even the last-pixel budget,
2.488 + 0.45 = 2.94, only barely grazes it with zero landing margin). The bot wasn't slow —
the arc doesn't have 3 gaps of airtime above +1. The "takeoff tuning" work item dies with the
row; the envelope removal *is* the fix, and it is derived, not flag-hidden — consistent with
the s52 ruling that a misbehaving row gets its pathology fixed at the source.

---

## 7. Implementation plan

### 7.1 New class: `movements/ParkourEnvelope.java`

A `final` package-mate of Parkour/DiagonalParkour (matches conventions: a smart object owning
its derivation, like `MovementContext` owns the block-height canon; not a `*Utils`). Static
init evaluates the closed forms of §5.1–5.3 — a handful of `Math.pow`/loop-free formula
evaluations plus one ≤ 25-iteration integer scan per Δy for `T(Δy)` — and bakes:

```java
public static final double MAX_CLEARED_AIR = 3.0;   // THE policy constant (owner comment verbatim)
public static final int    FLAT_MAX;                 // 3
public static final int    RISE_MAX;                 // 2
public static final int[]  FALL_MAX;                 // {0, 4, 4, 4}  (length = 1 + max drop)
public static final int    DIAG_MAX;                 // 2
static final int[]         LANDING_TICK;             // T(Δy)+1 per Δy (+1..−3) — for §7.6 cost derivation & tests
```

Also holds (package-private) the physics constants with their §3 citations and the
`X(T)`/`y(T)` closed-form helpers so `ParkourEnvelopeTest` can assert the margins. Javadoc =
§5 of this document (the geometric-series math written out), plus the §4 takeoff-speed
assumption and the §5.6 cap semantics. Class-load cost: nanoseconds-to-microseconds, before
`NavWarmup`; no config dependency (BotCaps continues to clamp falling depth at query time via
`maxFallDistance`, Parkour line 426 — unchanged).

Static init must contain **no assertions of expected values** (that would defeat derivation);
the pins live in tests (§7.5).

### 7.2 How the movements consume it (allocation-free, no per-node math)

- `Parkour.candidates` line 422: `flatMax = Math.min(PARKOUR_MAX_GAP, ParkourEnvelope.FLAT_MAX)`
  (also drop the `COST.length−1` term — keep `COST` sized to `FLAT_MAX`).
  Line 424: `riseMax = ParkourEnvelope.RISE_MAX`. Line 425: `fallMax = ParkourEnvelope.FALL_MAX`.
  These are already hoisted into locals once per expansion — the hot path sees identical code
  (static final int loads JIT-fold to constants; strictly cheaper than today's mutable-field
  reads for `RISE_MAX`/`FALL_MAX`, identical for the knob).
- `DiagonalParkour.candidates` line 131: `maxGap = ParkourEnvelope.DIAG_MAX`.
- Offset tier: unchanged mechanics; `reach2 = flatMax·(flatMax+2)` (line 734) already keys off
  the (now envelope-clamped) `flatMax`. `OFFSET_C_LIMIT`/`OFFSET_COVER` stay static at c ≤ 3
  — sized by `FLAT_MAX`; since the derived flat max equals 3, no table change. Add a comment
  that the cover tables' size assumption (`c ≤ FLAT_MAX`) is pinned by a test.
- Zero change to `scanDirection`/`verifyPrisms`/`emitRising`/`plan()` logic.

### 7.3 Constants deleted / kept

**Deleted (replaced by ParkourEnvelope):**
- `Parkour.RISE_MAX = 3` (line 275)
- `Parkour.FALL_MAX = {0,4,4,4}` (line 283)
- `DiagonalParkour.MAX_GAP = 3` (line 89)
- `DiagonalParkour.AIR_COST[3] = 19f` row (the g=3 slot dies with the row; lines 95, and the
  Javadoc's "academic until the aggressive gate opens" note at 62–63)

**Kept:**
- `Parkour.PARKOUR_MAX_GAP` — remains the public *narrowing* knob (its exact v1 semantics,
  "lower to 2 to restore the conservative flat row"); now clamped by `FLAT_MAX` instead of the
  cost-table length. Tests mutate/restore it (ParkourTest line ~119, ParkourLandingsTest
  islandInGap, ParkourOffsetTest).
- All cost constants (`RUNUP_COST`, `AIR_COST`, `RISE_EARLY_TICKS`, `FALL_EXTRA`,
  `COMMIT_PENALTY`, `COST`, `OFFSET_COST`, `interpolateAir`) — phase 1 is envelope-only (§7.6).
- `TAKEOFF_EDGE`/`TAKEOFF_EDGE_ALONG` — now *inputs* to the derivation (they define the
  takeoff point); their Javadoc gains a note that widening them changes the derived envelope
  at next boot (a feature: the envelope tracks the executor).
- `OFFSET_FALLBACK`, `OFFSET_C_LIMIT`, `OFFSET_COVER`, `OFFSET_FLOOR_MAX_TOP_Y`,
  `CORNER_MAX_TOP_Y` — untouched.

**Javadoc rewrites:** Parkour class Javadoc envelope section (lines 67–100: the table, the
"owner in-game data" framing → derived-from-ballistics framing, and the rising-3 pathology
paragraph 84–91 → §6's closure); `RISE_MAX`/`FALL_MAX` comments; DiagonalParkour Javadoc
lines 17–23 (envelope) and the g=3 references.

### 7.4 Tests: what pins the old envelope and what each needs

- **`ParkourLandingsTest`** — `risingThreeGapIsOffered` (line 111) pins rising 3 IN → becomes
  the negative: rising 3 must NOT be offered (assertNull on the single-route course, mirroring
  `fallingFiveGapIsNeverOffered`). Its Javadoc (lines 42–44, "rising 3-gap in the same one
  unconditional envelope… undershoot is a takeoff-tuning pathology") rewritten per §6.
  Everything else in the file survives: rising 2 positives (lines 84–109),
  `risingArcNeedsTheExtraRow` (2-gap, line 128), falling 3/4/−2/depth-caps/5-gap negatives
  (lines 155–244), `islandInGapEndsTheScan` (line 251).
- **`ParkourTest`** — unaffected (flat 1/2/3 positives, flat-4 negative line 136, knob
  restore) — these are the ratified outcomes; keep as-is. Optionally re-point the "no flat 4
  row exists" comments at ParkourEnvelope.
- **`DiagonalParkourTest`** — `threeGap…` positive (lines 110–117, "offered unconditionally")
  → becomes the negative (diagonal 3 never offered); 1-gap/2-gap positives and the fence
  corner negative survive. Javadoc lines 42–46 rewritten.
- **`ParkourOffsetTest`** — unaffected (flat reach unchanged at 3; knob-shrink case already
  covers the derived-clamp path).
- **`PartialHeightTest`** — rule 4 ("Parkour rising start deficit", lines 49, 167–172) uses
  the rising **2-gap** course — unaffected; verify at implementation that no case uses a
  3-gap rising jump.
- **New `ParkourEnvelopeTest`** — pins the derived table (§5.7) as the ratified contract, the
  six outcome margins of §5.5 (so a physics-constant edit or derivation regression fails
  loudly with the actual margin in the message), `y(6)` vs `JUMP_RISE`, and the cover-table
  size assumption (`OFFSET_C_LIMIT == FLAT_MAX`).

### 7.5 docs/movements.md

The Gap jumps section (lines 107–142) is **already stale** (still describes the deleted
aggressive flag and rising default 1–2). Rewrite: the envelope table per §5.7, a short
statement of the derivation (closed-form discrete ballistics from the executor's takeoff
point; `MAX_CLEARED_AIR = 3.0` policy with the owner's comment), rising 3 & diagonal 3 out
with the airtime rationale, DiagonalParkour envelope 1–2. Cost sentences unchanged in phase 1
(15.6/18.6/21.6 and ≈20.1/24.1 stay; drop the diagonal "aggressive flag" clause and the 28.6
g=3 total).

### 7.6 Costs — optional phase 2 (flagged, owner philosophy says yes eventually)

The same closed form yields airtime: landing on tick `T(Δy)+1` → flat 12, rising 9, falling
14/15/17. Today's `FALL_EXTRA = {2,3,5}` already equals `T(−d)−T(0)` exactly (it was
hand-derived from the same parabola) — deriving it is byte-identical. But `AIR_COST = {8,11,14}`
prices shorter flat hops *below* the physical 12-tick arc (the arc length is gap-independent;
the executor jumps at the same trigger regardless of gap), and `RISE_EARLY_TICKS = 2` vs. the
derived 3 (12−9). Deriving those changes edge costs → f-values → search behavior and JMH
baselines, and per the performance-model rules that is a separate design-reviewed, paired-A/B
measured change. Recommendation: phase 2 derives `FALL_EXTRA` (byte-identical) and leaves
`AIR_COST`/`RISE_EARLY_TICKS` for an owner decision with the A/B numbers on the table.
`physically-derived-costs` memory supports going all the way once the measurement exists.

### 7.7 Step-by-step checklist

All authoring on `core` (common src + tests), then the usual merge dance; no overlay, no
build-script change.

1. Add `movements/ParkourEnvelope.java`: §3 constants (with javap citations), §5.1–5.3 closed
   forms, §5.4 rule + §5.6 policy clamp, baked `public static final` table. No MC imports.
2. `Parkour.java`: swap `RISE_MAX`/`FALL_MAX` for envelope reads (lines 422–426); delete the
   two constants; rewrite the envelope + rising-pathology Javadoc (§7.3).
3. `DiagonalParkour.java`: `MAX_GAP` → `ParkourEnvelope.DIAG_MAX`; shrink `AIR_COST` to g ≤ 2;
   rewrite envelope Javadoc.
4. Flip `ParkourLandingsTest.risingThreeGapIsOffered` and `DiagonalParkourTest`'s 3-gap case
   to negatives; sweep both files' Javadocs; confirm `PartialHeightTest` rule 4 still builds a
   2-gap course.
5. Add `ParkourEnvelopeTest` (derived-table pins + six-outcome margins + `JUMP_RISE`
   consistency + `OFFSET_C_LIMIT == FLAT_MAX`).
6. `docs/movements.md` Gap jumps rewrite (also clears the pre-existing aggressive-flag
   staleness) — public site, so wording per docs conventions.
7. Gates: unit tests + `chiseledCompileCommon --continue` (all 28) + 26.2 compile via the
   era merge; **owner runs** the JMH paired A/B (BRIDGE/TOWER/OPEN + SHORT/MULTI at minimum —
   §8) since candidate sets change; in-game smoke: rising-2 ledge, falling-4, a diagonal-2,
   and a course that used to draw the rising-3 (should now route around or two-jump).
8. In-game verify → commit dance per commit-hygiene (core → era branches).

---

## 8. Risks

- **Behavior change scope (intended):** two candidate rows vanish — rising 3 (cardinal) and
  diagonal 3. Any route that used them re-plans (two smaller jumps, a detour, or Pillar for
  placing bots — cf. `islandInGapEndsTheScan`'s two-jump precedent). Rising-3 users were
  *failing in-game* (the undershoot), so live behavior improves; diagonal-3 was owner-ratified
  out as physics-marginal. E2E route diffs are expected and correct; anything that newly
  strands is a planner-vs-executor seam bug to report, not to bandaid.
- **Search-shape / JMH:** `PathfinderBenchmark` **BRIDGE** relies on parkour explicitly ("2
  places + a parkour hop over the remaining 3-gap", PathfinderBenchmark lines 229–230, 636,
  731) — that is **flat 3, unchanged**; the scenario's optimum survives. `FullSearchScenarios`
  has no parkour references. The scan **horizon** `maxGapAll = max(3, 2, 4) = 4` is unchanged
  (falling dominates), so per-expansion read counts are nearly identical (`riseMax` 3→2 trims
  a few rising-detection reads at g=3); but fewer emitted candidates can shift expansion
  order/counts anywhere near ledges, so baselines move and the change is a BEHAVIOR change
  under the perf rules: full paired interleaved A/B suite, not byte-identical assertions.
- **Thin margins:** falling-4's +0.064 and (if ever added) diagonal-falling-2/3's ±0.07 sit
  within the takeoff-speed assumption's sensitivity (§4). Falling 4 is owner-verified in-game
  under the real executor, which is the evidence that matters; the margin and the assumption
  are documented on the class so a future in-game miss gets diagnosed as takeoff speed, not
  re-derived.
- **Float vs. double drift:** MC mixes float constants into double math; the derivation uses
  doubles with the float-promoted constant values (e.g. 0.025999999f, 0.41999998688697815).
  Worst-case discrepancy vs. the exact mixed-precision recurrence is ~1e-7/tick — no admitted
  row is within 0.05 of its boundary except those listed above (≥ 0.06), so no rounding flip
  is possible. `ParkourEnvelopeTest`'s margin pins make this a loud failure if it ever moves.
- **Version drift:** constants verified on 1.21.11 only (this design's jar). They are
  documented-stable across 1.17→26.x (§3), and the class is version-agnostic common code. If
  Mojang ever changes one, the envelope re-derives *correctly* at boot on that version — but
  our per-version jars share one common source, so a real divergence would need a platform
  seam; flag only if it ever happens.
- **Class-init order:** `ParkourEnvelope` must not touch MC classes (it doesn't — pure
  arithmetic), so static init is safe wherever the first Movement class loads, including the
  synthetic-grid unit tests and JMH harness.
