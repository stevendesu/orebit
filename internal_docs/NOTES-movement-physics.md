# NOTES — Movement physics & per-move envelopes (durable reference)

**What this is.** The bytecode-derived Minecraft constants, closed-form arcs, and hard-won
connectivity verdicts behind the `Movement` set. Everything here was expensive to obtain
(javap/Vineflower on the mojang-mapped 1.21.11 merged jar, plus in-world harness runs) and is
cheap to *use* but painful to *re-derive*. The arcs that produced it have all shipped; their
design docs were deleted. Code Javadocs cite this file by §-anchor.

**Scope note.** Ground/jump/climb physics live here. FLUID physics (upright-swim rungs, prone
sprint-swim, pose fit) live in `DESIGN-submerged-upright-swim.md` + the `mc-swim-physics-model`
memory — that arc is newer and still has open items, so it keeps its own doc.

**Version stability.** Every constant in §1 and §3 has been stable since well before 1.17; the
1.20.5 attribute migration (`JUMP_STRENGTH`/`GRAVITY` becoming attributes) preserved the default
values. `ParkourEnvelope` is pure Java with no MC imports — it lives in common `src/`, no overlay,
both eras.

---

## §1 Sprint-jump ballistics (the ground-movement physics canon)

Source: `javap -p -c` on the **mojang-mapped** merged jar in the loom cache
(`~/.gradle/caches/fabric-loom/1.21.11/.../minecraft-merged-mojang.jar`). The bot is a
`ServerPlayerEntity` subclass running the full vanilla player tick (CLAUDE.md, s38), so **player**
physics applies verbatim.

| Quantity | Value | Decompiled evidence |
|---|---|---|
| Jump initial vy | **0.42** | `LivingEntity.getJumpPower(F)` = `Attributes.JUMP_STRENGTH` × blockJumpFactor + jumpBoostPower; `Attributes` static-init registers `jump_strength` default `ldc2_w 0.41999998688697815`. `jumpFromGround()` sets `setDeltaMovement(x, max(jumpPower, vy), z)`. |
| Sprint-jump horizontal impulse | **+0.2 in facing** | `jumpFromGround()`: `if (isSprinting()) addDeltaMovement(new Vec3(−sin(yaw)·0.2, 0, cos(yaw)·0.2))`. |
| Gravity per tick | **0.08** | `LivingEntity.getDefaultGravity()` = `Attributes.GRAVITY`, registered default `ldc2_w 0.08d`. Applied in `travelInAir`: `vy − getEffectiveGravity()`. |
| Vertical drag | **× 0.98** | `travelInAir`: `setDeltaMovement(vx·f4, (vy − g)·0.98f, vz·f4)` — after gravity, after the move. |
| Horizontal air drag | **× 0.91** | `travelInAir`: `f = onGround() ? blockFriction : 1.0f; f4 = f · 0.91f`. |
| Horizontal ground drag | **× 0.546** | same expression grounded: default `Block.getFriction()` 0.6 → 0.6·0.91. |
| Ground accel / tick | **speed · 0.21600002/f³ · input** | `getFrictionInfluencedSpeed(F)`; `moveRelative(speed, input)` adds `getInputVector(...)` **before** the move. |
| Air accel / tick (sprinting) | **0.025999999 · input** | `Player.getFlyingSpeed()` override: not flying → `isSprinting() ? 0.025999999f : 0.02f`. |
| Player speed | **0.1 walk, 0.13 sprint** | `Player.createAttributes()` MOVEMENT_SPEED `0.10000000149011612`; `LivingEntity` sprint modifier `ADD_MULTIPLIED_TOTAL 0.30000001192092896` → ×1.3. |
| Input magnitude | **0.98** | `LivingEntity.aiStep()`: `xxa *= 0.98f; zza *= 0.98f`. The bot holds full forward (`SteerControl.steerTowards` → `setForward(1.0f)`), so effective input = 0.98 (length² 0.9604 ≤ 1 ⇒ `getInputVector` does not normalize). |
| Integration order | input-accel → move → drag/gravity | `handleRelativeFrictionAndCalculateMovement`: `moveRelative(…)` then `move(SELF, delta)`; `travelInAir` then stores the dragged velocity. Position advances by the *post-acceleration, pre-drag* velocity. |

**The jump-tick quirk (easy to get wrong).** `f4` is computed at `travelInAir` entry, *before* the
move. On the jump tick `onGround()` is still true (the jump fires earlier in `aiStep`), so the jump
tick uses **ground** drag 0.546 and the **ground** accel rate.

**Block-modifier plumbing** (`Entity.move`):
- `stuckSpeedMultiplier` (set by `makeStuckInBlock` last tick): if `lengthSqr > 1e-7` and type ≠ PISTON,
  the **position delta is scaled on all three axes**, then the multiplier is zeroed and stored velocity
  is zeroed. Values: cobweb `(0.25, 0.05, 0.25)`, sweet berry `(0.8, 0.75, 0.8)`, powder snow `(0.9, 1.5, 0.9)`.
- `getBlockSpeedFactor` at the END of `move`: scales stored velocity **X/Z only** (Y untouched), i.e.
  affects the NEXT tick. Reads the block at the feet; if 1.0 it falls through to the block BELOW — so
  standing on soul sand gives 0.4, and once airborne (~0.5 up) the block below is air → 1.0. **The jump
  tick's trailing drag is therefore still gsf'd.** Soul sand `speedFactor(0.4)` (jumpFactor 1.0, so vy₀
  is unchanged); honey `speedFactor(0.4) jumpFactor(0.5)`.

### §1.1 Closed forms

Both axes are linear recurrences with constant coefficients — exact geometric series, no simulation.
Tick 1 is the jump tick; positions are displacements of the bot **centre** from the takeoff point;
heights are feet above the takeoff floor top.

**Vertical** (`vy(t+1) = (vy(t) − g)·q_v`, `g = 0.08`, `q_v = 0.98`, `vy(1) = 0.42`):

```
K    = g·q_v/(1 − q_v) = 3.92                      (terminal velocity, exactly)
vy(t)= (vy(1) + K)·q_v^(t−1) − K
y(T) = (vy(1)+K)·(1 − q_v^T)/(1 − q_v) − K·T
```

Apex: `vy(t) > 0 ⇔ t ≤ 6`; **`y(6) = +1.2522`** — this is where `MovementContext.JUMP_RISE = 20/16`
comes from (20/16 = 1.25 ≤ 1.2522).

**Landing tick `T(Δy)`** = the **largest** integer T with `y(T) ≥ Δy` (largest, *not* first failure —
the rising arc dips below +1 at t1–2 and is back above it t3–8):

| Δy | T(Δy) | y(T) | y(T+1) |
|---|---|---|---|
| +1 | 8 | +1.0244 | +0.7967 |
| 0 | 11 | +0.1213 | −0.3235 |
| −1 | 13 | −0.8379 | −1.4203 |
| −2 | 14 | −1.4203 | −2.0695 |
| −3 | 16 | −2.7841 | −3.5628 |

(The older hand-derived table said "crosses 0 at ~t12, −1 at ~t14, −2 at ~t15, −3 at ~t17" — same
events; the crossing happens *during* tick T+1, and the last supported tick is T.)

**Horizontal** (ground drag `q_g = 0.546`, ground accel `a_g = 0.13·(0.21600002/0.216)·0.98 = 0.127400`;
air drag `q_h = 0.91`, air accel `a_a = 0.025999999·0.98 = 0.025480`):

```
v∞   = a_g/(1 − q_g) = 0.280617      (= 5.612 b/s, the documented sprint speed — cross-check)
v(1) = v∞·q_g + 0.2 + a_g = 0.480617 (jump tick: dragged carry-in + boost + GROUND accel)
v(2) = v(1)·q_g + a_a     = 0.287897
m    = a_a/(1 − q_h)      = 0.283111 (airborne fixed point)
v(t) = m + (v(2) − m)·q_h^(t−2)                                  (t ≥ 2)
X(T) = v(1) + (T−1)·m + (v(2) − m)·(1 − q_h^(T−1))/(1 − q_h)
```

Budgets at the landing ticks: `X(8) = 2.4881`, `X(11) = 3.3442`, `X(13) = 3.9140`,
`X(14) = 4.1986`, `X(16) = 4.7675`.

**Cross-checks that validate the whole system:** steady sprint 5.612 m/s (community-documented);
terminal fall velocity −3.92 b/t (documented); apex +1.2522 confirming `JUMP_RISE = 20`. Note the
12-tick reach is **3.34** blocks of centre-travel, not `12 × 0.28 = 3.4` — the naive multiplication is
wrong, and the difference is exactly where flat-4 lives or dies.

### §1.2 Required travel geometry

Hitbox half-width 0.3 (0.6×0.6 box); coordinates from the takeoff-cell centre.

```
D_req(g)      = g + 0.2 − 0.35                 (cardinal; landing overlap at (g+1) − 0.5 − 0.3)
D_req_diag(g) = (g + 0.2)·√2 − 0.40            (diagonal, along-line)
```

**Why the budget is `X(T(Δy))` and not `X(T+1)`:** `Entity.move` resolves Y before X/Z. On the first
tick with `y < Δy` the downward move is resolved at the *current* horizontal position — if the box
does not already overlap the landing column, the feet pass below the landing top and the subsequent
X/Z move face-hits the landing block. So overlap must exist **by the end of tick `T(Δy)`**; the
crossing tick contributes no usable horizontal distance. Enumerating integer `g` under
`D_req(g) ≤ X(T(Δy))` *is* the conservative floor.

---

## §2 The parkour envelope (derived, shipped)

**Live code is the authority:** `movements/ParkourEnvelope.java` bakes the table at class-load;
`internal_docs/parkour_envelope_params.py` is the validated derivation the Java ports verbatim
(run it: `python internal_docs/parkour_envelope_params.py`). This section records only what neither
file states.

**Shipped maxima for a clean full-block takeoff:** flat **3**, rise **2**, fall **4/4/4** (drops 1–3),
diagonal **2**; offset shapes `(2,±1)`,`(3,±1)` unchanged. `MAX_CLEARED_AIR = 3.0` is the one
hard-coded POLICY constant — the physical single-sprint-jump limit is ~4 blocks of cleared air, but 4
requires tick-perfect last-pixel takeoff; 3 removes that requirement and keeps routes
human-followable. Generalized: cleared air ≤ `MAX_CLEARED_AIR` for Δy ≥ 0, ≤ `MAX_CLEARED_AIR + drop`
for Δy < 0 (a drop buys airtime, which is the tick-perfection relief the cap exists to guarantee).
Drop depth ≤ 3 is policy, not physics — `Fall` owns deeper descents.

### §2.1 The six ratified outcomes and their margins

Derived under `D_req(g) ≤ X(T(Δy))` from the executor's own takeoff trigger at full-sprint arrival:

| Ratified | Verdict | Margin (blocks) |
|---|---|---|
| flat 3 ✓ | admitted | +0.494 |
| flat 4 ✗ | rejected | −0.506 |
| rising 3 ✗ | rejected | −0.362 |
| falling 4 ✓ | admitted | +0.064 (thin) |
| diagonal 2 ✓ | admitted | +0.633 |
| diagonal 3 ✗ | rejected | −0.781 |

**Independent in-world confirmation** (the `run-parkour.ps1` harness, head-on, full sprint, 1.21.11;
"margin" = how far into the landing cell the centre settled): flat 1/2/3 ✓ (flat-3 lands ~0.84 in —
comfortable); rise 1/2 ✓; **rise 3 lands, but only ~0.16 in** → excluded on the *wiggle-room* bar, not
the impossibility bar; fall −1 to −3 at gap 4 ✓; diag 1/2 ✓; **diag 3 fails 2/2** — it face-hits the
landing edge with feet ~0.3 below its top, genuinely unmakeable.

**The model/reality discrepancy, and why the model was NOT changed.** The closed form measures reach
from the takeoff TRIGGER (`centre + 0.35`), but the executor's TAKEOFF phase holds `setJumping` for
~2–3 more ticks, so the bot actually leaves the ground ~1 block further along (traces show the
airborne transition at `proj ≈ 1.3–1.4`). That coast is **not** in `X(T)`. Consequence: the model
calls rise-3 impossible when reality says "makeable but marginal" — *right answer, wrong reason*. The
ratified resolution was to **keep the +0.35 trigger as the model's takeoff point** and treat the
unmodelled coast (plus the ~0.45-block hitbox-overhang runway the plan never schedules) as the
structural anti-tick-perfect wiggle room. So: **if you ever re-derive, do not "fix" the coast term
without re-ratifying the whole table** — adding it admits rows the wiggle-room bar is meant to cut.

The old diagnosis that rising-3's in-game undershoot was a *takeoff-tuning* pathology (insufficient
sprint windup) is **refuted**: at full steady sprint the rising budget is 2.488 vs 2.85 required, and
even the last-pixel budget (2.94) only grazes it. The arc doesn't have three gaps of airtime above +1.

### §2.2 Why the table is takeoff-condition-parameterized

The **vertical** (can-I-gain-this-height) axis was always partial-block-aware via
`MovementContext.rise(dyBlocks, destTopY, startTopY)` gated by `JUMP_RISE=20` /
`STEP_ASSIST_MAX_RISE=9`. The **horizontal** maxima were not: a slab / snow / enchanting-table takeoff
was offered full-block reach, which is optimistic — a lower takeoff surface shifts the effective Δy
toward "rising" and shrinks reach (`effΔy = classΔy + (1.0 − takeoffSurfaceY)`). Hence
`MAX_GAP[startTopY 1..16][gsfBucket][occBucket]`. Dripstone/bamboo are `SHAPE_OTHER` (non-standable)
so the bot never takes off from them; slabs it does. Landing-surface height stayed OUT of the table —
the existing `rise()` gate covers it, and no harness probe showed a miss it misses.

**The no-help clamp** (subtle, do not remove): each occupied (`occBucket=1`) row is clamped to its own
`(surface, gsf)` occ=none ceiling, because powder snow's `occV = 1.5` would otherwise *invent* airtime
that a velocity-zeroing block never actually gives. Honey floors (`jumpFactor 0.5`) and cobweb body
cells never reach the table at all — the moves refuse them earlier via `reducesJump` / `noJumpFromBody`.

---

## §3 Climbable physics ground truth (ladder / vine / scaffolding)

Decompiled 1.21.11 mojmap (Vineflower); every claim source-verified. Line numbers are from that
decompilation and are indicative, not exact across versions.

- **`onClimbable` is a FEET-CELL state.** `LivingEntity.onClimbable()` tests only `getInBlockState()`
  (the block at `blockPosition()`) against `#minecraft:climbable` (ladder, vine, scaffolding,
  weeping/twisting/cave vines + `_PLANT`s), plus the open-trapdoor-above-same-facing-ladder special
  case. **No head or body test.** (LivingEntity.java:1695-1722)
- **The climb clamp runs PRE-move.** While `onClimbable`, `handleOnClimbable` resets fallDistance,
  clamps horizontal to ±0.15, clamps ONLY downward to −0.15 (`Math.max(y, -0.15)`; upward untouched),
  and holds a sneaking *Player* at 0 — **scaffolding is explicitly exempt from the sneak-hold**.
  (LivingEntity.java:2583-2598)
- **"Climb up" = 0.2, applied POST-move.** `(horizontalCollision || jumping) && onClimbable()`
  (re-tested AFTER the move) sets vy = 0.2 for the next tick; gravity 0.08 × 0.98 drag makes the
  realized climb **0.1176 b/t**. (LivingEntity.java:2560-2563, 2365-2388)
- **A hanging entity can NEVER jump.** The 0.42 `jumpFromGround` impulse fires only under `onGround()`
  (outside fluids). Holding jump on a climbable gives the 0.2 branch, not a jump. (LivingEntity.java:2954-2979)
- **A grounded jump with feet INSIDE a climbable is truncated.** If after the first +0.42 move the feet
  are still in a climbable cell, the post-move branch overwrites vy to 0.2 — an in-column ground jump
  degenerates to climb rate.
- **Apexes.** Climb-out-the-top: last in-cell vy-set 0.2 → stored 0.1176 → feet peak **≈ +0.154** above
  the cell top. **Feet can never gain +1.0 from a hang.** Grounded 0.42 jump: apex **≈ +1.2522**, feet
  cross +1.0 during tick 3.
- **Fall arrest is automatic** when feet BEGIN a travel tick inside a climbable cell (pre-move clamp to
  −0.15 + fallDistance reset). Feet are sampled once per tick, so a fall step of `dy` b/t can only be
  GUARANTEED to sample inside a window of height > `dy`. Separately, `Entity.move` raycasts any
  ≥1.0-block displacement against `#fall_damage_resetting` (= #climbable + berry bush + cobweb, as full
  cubes) and resets fallDistance on pass-through — that resets DISTANCE, it does **not** arrest.
  (Entity.java:744-756)
- **Fall speed vs distance** (same recurrence as §1.1; terminal 3.92 b/t exactly): speed stays < 1.0 b/t
  for ≈**7.5 blocks** of prior fall — the guaranteed-arrest regime for a 1-cell climbable, and the
  shipped bound (`Fall.HANG_MAX_DROP = 7`, asserted against the recurrence by `HangBoundTest`).
  Longer-run relaxations (< 2.0 b/t for ≈40 blocks so a 2-run catches; < 3.0 for ≈132; a ≥4-run catches
  from any height) are physically sound but **deliberately NOT shipped** — the deep-column hangable
  sweeps they need measured TOWER +8–13% / FLOOD +14–18% in the paired A/B (owner ruling 2026-07-31).
- **Ladder**: real collision — a 3/16 full-face plate against the wall opposite `FACING`, full cell
  height. FACING is **not** packed in the descriptor (only stairs/doors pack facing), so plate side is
  invisible to the planner. Consequences: (a) standing on the plate top with another same-side ladder
  above is geometrically IMPOSSIBLE, and the planner cannot tell same-side from alternating → plate
  stances are never planned as jump launches; (b) entering a ladder cell **from above** is a knife-edge
  — a centred 0.6-wide bot clears the plate by 0.0125 blocks (hang) vs. catching on the plate top
  (stand), a nondeterministic landing; (c) entering **from below** is robust — a rising bot is pushed
  sideways, never caught, so jump-grab is safe.
- **Vine family** (vine, cave/twisting/weeping vines + plants): `noCollision()` — EMPTY collision,
  nothing to stand on or catch on; arrest position within the cell is exact.
- **Scaffolding**: in #climbable; `getCollisionShape` is context-dependent — a stand-on-top 2px slab
  (y14–16 + corner posts) only when the entity is above and NOT descending (sneak removes it → you sink
  in), a 2px bottom plate only for floating bottom blocks, and **no collision inside**. Inside: jump →
  0.2 branch, sneak → −0.15 descent (the sneak-hold exemption), idle → −0.15 slide, horizontal ±0.15.
  Falling onto scaffolding from above **lands on top** — never an interior entry.
  (ScaffoldingBlock.java:138-149)
- **Classifier truth** (adjudicated against `EntityCollisionContext$Empty` bytecode: `isAbove` returns
  the passed default, `placement=false`, no NPE). NavBlock's null-context query gets SHAPE_STABLE →
  unit-cube bounds, so **scaffolding classifies SHAPE_FULL, topY 16, STANDABLE, COLLISION, BREAKABLE
  (hardness 0), CLIMB set, no NARROW_TOP** (2 navtypes, dry/waterlogged). Ladder → SHAPE_OTHER, topY 16,
  STANDABLE, COLLISION, NARROW_TOP, CLIMB. Vines → SHAPE_EMPTY, passable, not standable, CLIMB.

---

## §4 Climb connectivity verdicts (what the search may and may not emit)

Two governing rules (owner-ratified 2026-07-31):
**R1 — no jump launches from climbable stances** (`solidFooting` floors only; plate/deck stances never
launch). **R2 — hang landings only where the landing position is deterministic**: passable climbables
(vines) within the flat guaranteed-arrest bound (prior drop ≤ 7), landing at the run's BOTTOM cell
(post-arrest the −0.15 slide converges every catch point there).

A hang node is an ordinary `(x,y,z,MODE_STANDING)` node whose feet cell `(x,y+1,z)` carries CLIMB — no
new mode, no support invariant, no depth-nibble change. `hangable(d) := isPassable(d) && isClimbable(d)`
(the vine family exactly) is the one predicate the arc added.

| Case | Verdict | Mechanism |
|---|---|---|
| Grounded on solidFooting, feet cell non-climb, climbable at feet+1 | **CONNECTS up** (jump-grab) | 0.42 jump, feet cross +1.0 into the climbable, arrest. Feet+2 is NEVER reachable (apex 1.25). Ladder bottoms included — from-below entry is robust (§3). |
| Ladder / air / ladder, upward | **REFUSED** | R1: the plate stance can't launch, and same-side uppers make the stance itself impossible — undetectable without packing FACING. |
| Vine / air / vine, upward | **REFUSED** | No stance, no jump; climb-exit peaks at only +0.154. (Downward only — the intuitive "connects both ways" is wrong.) |
| Fall THROUGH a climbable cell | **REFUSED as transit** | The ±0.15 arrest means a passable climbable IS the landing; a solid climbable (ladder/scaffold) refuses the candidate outright. |
| Fall INTO a passable-climbable run (vines) | **CONNECTS down** as a hang at the run's BOTTOM cell, when prior drop ≤ 7. Zero fall damage (arrest resets fallDistance). |
| Fall INTO vines beyond the bound | **REFUSED** | Tunneling nondeterminism; deep-fall arrests deliberately unsupported. |
| Hang → gap below → vines/floor (chains) | **CONNECTS down** per-hop under the same ≤ 7 bound (each hop restarts from −0.15), so vine/air/vine descends any TOTAL depth. |
| Gapped ladder column, downward | **REFUSED across the gap** | From-above ladder entry is the 0.0125 knife-edge. Contiguous ladder columns still climb down normally; the gap ends the column. |
| Standing atop a climbable (ladder plate / scaffold deck) → into the column | **CONNECTS down** (sink-in) | Ladder = recentre off the plate + gravity + arrest (the plate cannot re-catch feet already below its top); scaffolding = hold sneak, the top shape vanishes. |
| Scaffold column interior, vertical | **CONNECTS** both ways | Cells carry CLIMB; jump = up 0.1176, sneak/idle = down 0.15. |
| Hang at scaffold-column top → deck | **CONNECTS up** (exit-top) | Pop +0.154, the full top face catches. Gated `!isNarrowTop` so ladder plates are excluded. |
| Fall onto scaffolding from above | **standing landing on top**, priced with NORMAL fall damage | Vanilla catches you. Scaffolding is in #fall_damage_resetting so vanilla may forgive it — overpricing is the safe error. |
| Lateral grab INTO scaffolding | **REFUSED** | The lateral-hold steer uses sneak; scaffolding is sneak-exempt → the bot sinks while crossing and block-exact `reached` never fires. Guard: grab allowed iff destination climbable `isPassable || isNarrowTop` (vine ✔, ladder ✔, scaffold ✘). |

Shipped costs (ticks): jump-grab ≈ **8** (3 rise + 2 arrest settle + 3 jump-commit surcharge);
exit-top ≈ **10.5**; sink-in ≈ **8.7**; in-run slide 20/3 t per cell (the −0.15 clamp); arrest settle 2.

---

## §5 Climb execution facts

- **`solidFooting` has THREE conjuncts, and the third is load-bearing:** the FEET cell must be
  non-climbable too, because vanilla truncates a grounded jump whose feet start inside a climbable back
  to the 0.2 climb (§3). Without it the walk-into-a-floor-vine stance (feet in vine over stone —
  reachable via plain Traverse forever) gets offered ballistic jumps. One extra descriptor read per
  takeoff gate (Parkour / DiagonalParkour / WalkOff).
- **`Fall.plan` done** = `footMatch && (grounded() || onClimbable())` — one predicate for both landing
  kinds. A standing landing still requires grounded (a standing landing reads `onClimbable` false); a
  hang landing fires arrested-in-cell (vine cells have no collision, so `footMatch` is exact).
- **`Climb.steer` branches** (the `BotSteering.onClimbable()` / `scaffoldingBelow()` seam reads exist
  for exactly this): Δy > 0.1 → `setJumping(true)` (grounded → real 0.42 grab; on-climbable → 0.2
  climb). |Δy| ≤ 0.1 → sneak-hold (ladder/vine; scaffold laterals are refused planner-side).
  **Δy < −0.1 grounded** (the sink-in step) → hold sneak when `scaffoldingBelow()`, else no input +
  recentre — a ladder plate needs the recentre because sneak would edge-guard-pin the bot ON the plate.
- **Arrival on a hang.** `planAnchor` admits a hang (`grounded || inWater || inLava || onClimbable`),
  but ARRIVAL keeps the narrower `stableMedium` — so a goal ON a hang never "arrives"; the bot slides to
  the column base and arrives grounded. Accepted v1 behaviour.

### §5.1 The held-jump × climbable-transit elevator (convicted 2026-07-31, fixed)

Worth keeping because the failure mode is invisible to every envelope and will recur if anyone adds a
move that holds jump across its landing stance.

**Mechanism:** Ascend's climb-phase drive held `setJumping(true)` unconditionally. The tick the bot's
FEET cell samples a climbable in the landing stance, vanilla's `jumping && onClimbable → +0.2/t` (§3)
converts the held jump into a climb: the bot rises THROUGH its own landing stance and enters a 4-tick
hover limit cycle at the curtain top — one on-climbable tick re-launches it off every falling re-entry,
so with jump held it can never descend through a vine cell and never grounds. `done` / `resetWhen` /
`failWhen` are ALL settled-gated (grounded / inWater / inLava) and a hover is none of those, so the
state is **structurally un-terminable**. Measured: 370 ticks dead-centred on the target column, feet one
block above the stance, `horizontalCollision = 0` throughout (the held jump alone sustains the class).

**Two refinements the repro cards forced:** (1) walk momentum defeats takeoff-column capture — a WALKIN
entry passes even pre-fix because the centre crosses the column boundary before the feet sample the
curtain; **the dangerous entry is the MOMENTUM-LESS one** (a chained second Ascend). (2) Pillar is
structurally SAFE: its held jump is phase-bounded BELOW the capture heights. No other converted move
holds jump across its landing stance.

**The fix (execution side):** hold jump only while the climb still NEEDS height —
`setJumping(!(onClimbable() && footY() >= landFootY))`. Below the target the press is right in every
medium (ballistic launch on land, +0.2/t ratchet up a curtain, swim-up in water — a water cell is never
a climbable cell, so the two cannot overlap); at/above it in a climbable, release and let the −0.15/t
descent clamp settle the bot onto the floor where `done` fires. Post-fix: capture→done in 5–7 ticks.

---

## §6 Deliberate climb bounds (v1 non-goals, each with its reason)

Do not "fix" these without re-opening the ruling — each is a decision, not an oversight.

1. **No climbable-gap ascent of any kind.** Vine: no stance. Ladder: R1 + the same-side impossibility +
   unpacked FACING (§3). A gapped column ascends via Pillar/break or not at all.
2. **Gapped ladder columns don't descend across the gap** — the from-above 0.0125 knife-edge.
   Contiguous ladder columns are unaffected.
3. **Flat `HANG_MAX_DROP = 7`** — the 1-cell sampling proof (§3), asserted from the recurrence.
   Run-length relaxations and deep-window hangable sweeps dropped on the measured perf regression;
   deep falls onto climbables are REFUSED, not detected.
4. **Scaffold lateral grabs refused** — the sneak-hold exemption. A duty-cycled jump-hold lateral is
   future executor work.
5. **Scaffold-top fall landings price NORMAL damage** — vanilla may forgive them (the
   #fall_damage_resetting raycast); overpricing is the safe error, an empirical card decides later.
6. **`Fall` has no `failWhen` validity envelope** — see `DESIGN-validity-envelopes.md` §3.
7. **Trapdoor-above-ladder** climbable extension ignored (the planner never emits into it) — see
   `DESIGN-trapdoors.md`.
8. **Tag drift**: NavBlock's climbable list is identity-based, not `#climbable`, so modded/datapack
   climbables are invisible to the planner (pre-existing).
9. **Waterlogged climbables**: fluid kills STANDABLE (existing classification); the swim moves own wet
   columns.

**Standing perf rule recorded during this arc (owner, 2026-07-31): NO section-level `anyX` prefilter
bits** for block-tier performance — only `anyPortal` is permitted (it changes REGION-planning
semantics). The TOWER/FLOOD regression was resolved by SHRINKING the arrest feature's coverage (the flat
≤ 7 bound, no deep sweeps), not by an `anyClimbable` grid bit. See the `no-anyx-grid-bits` memory.

### §6.1 The one open owner ruling

**Widen the `failWhen` envelopes' settled-set to include `onClimbable()`** — a hang is a stable stance
(the bot can sit there indefinitely), so it IS "settled" for off-plan purposes. That would make any
residual capture shape (e.g. a transient horizontal-collision ratchet beside a curtain on a
forward-driving move) fail fast into a replan-from-the-hang instead of hovering invisibly. Blast radius:
every converted move's `failWhen`. **Not implemented — needs the owner's ruling.**
