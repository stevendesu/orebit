# NOTES — Vanilla fluid physics, pose rules, and damage cadence

**Permanent reference, not a design doc.** These are vanilla facts that were expensive to establish
(each one adjudicated from disassembled bytecode, not from a wiki and not inferred), and that future
cost-model / follower / hazard work will need again. Nothing here is a plan; nothing here expires when
an arc ships.

**Provenance.** Disassembled from the Loom-cached merged jar, **MC 1.21.11, official (Mojang) mappings**:

```
JAR=~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged/\
1.21.11-loom.mappings.1_21_11.layered+hash.40545-v2/minecraft-merged-1.21.11-…-v2.jar
unzip -o "$JAR" 'net/minecraft/world/entity/LivingEntity.class' … && javap -p -c <class>
```

Re-verify the same way after any MC-version bump — these are *not* portable constants by assumption,
they are portable because they were checked.

---

## §1 The per-tick in-fluid integrator (WATER)

### §1.1 Call order inside one tick

`LivingEntity.aiStep()` applies the swim impulse **before** it calls `travel`
(bytecode offsets in `aiStep`: `jumpInLiquid` at 397/429, `travel` at 618):

```
aiStep:
  if (isInWater() && (!onGround() || fluidHeight > getFluidJumpThreshold()))
      jumpInLiquid(FluidTags.WATER)          // dm.y += 0.04   — EVERY tick while jump is held
  …
  travel(input)
     → travelInFluid(input)                  // isFalling := dm.y <= 0 ; gravity := getEffectiveGravity()
        → travelInWater(input, gravity, isFalling, y0)
             moveRelative(0.02, input)       // horizontal accel
             move(SELF, getDeltaMovement())  // ← POSITION IS APPLIED HERE, BEFORE THE DRAG
             dm = dm.multiply(f, 0.8, f)     // f = 0.9 sprinting / 0.8 otherwise / 0.96 dolphin's grace
             dm = getFluidFallingAdjustedMovement(gravity, isFalling, dm)   // dm.y -= gravity/16
```

The `move()`-before-drag ordering is load-bearing — see §1.4.

### §1.2 The constants (all bytecode-confirmed)

| Constant | Value | Source |
|---|---|---|
| swim-up impulse | `+0.04` /t | `LivingEntity.jumpInLiquid` (`ldc2_w 0.03999999910593033`) |
| sink impulse | `−0.04` /t | `LivingEntity.goDownInWater` (Orebit's `AllyBotEntity.sinkInWater` mirrors it) |
| vertical drag | `0.8` (**hardcoded**) | `travelInWater`: `dm.multiply(f, 0.8, f)` — the **Y** factor is the literal `0.8`, never `f` |
| horizontal drag | `getWaterSlowDown()` = `0.8`; `0.9` when sprinting; `0.96` with Dolphin's Grace | `travelInWater` |
| horizontal accel | `0.02` (`WATER_MOVEMENT_EFFICIENCY` interpolates toward `getSpeed()`; 0 by default off-ground) | `travelInWater` |
| in-fluid gravity | `gravity/16` = `0.08/16` = **`0.005`** /t | `getFluidFallingAdjustedMovement`; `Attributes.GRAVITY` default `0.08` (`RangedAttribute("attribute.name.gravity", 0.08, -1.0, 1.0)`) |
| fluid jump threshold | `getEyeHeight() < 0.4 ? 0.0 : 0.4` | `Entity.getFluidJumpThreshold` |

**`getFluidFallingAdjustedMovement` skips the gravity term entirely when `isSprinting()`** — the very
first branch is `if (gravity != 0 && !isSprinting())`. This is why Orebit's upright swim rungs
explicitly *clear* the sprint flag: a sprinting bot has different vertical dynamics. (The `isFalling`
argument does **not** gate the subtraction; it only enables a ±0.003 "hover snap" special case.)

### §1.3 Terminal speeds

One tick is `v' = drag·(v ± impulse) − gravity`, so the fixed point is
`v* = (drag·impulse ∓ gravity) / (1 − drag)`:

| | arithmetic | `v*` (blocks/tick) | ticks/block |
|---|---|---|---|
| rise (hold jump) | `(0.8·0.04 − 0.005)/0.2` | **0.135** | **7.41** |
| sink (hold sneak/`goDownInWater`) | `(0.8·0.04 + 0.005)/0.2` | **0.185** | **5.41** |
| idle (no impulse) | `(0 − 0.005)/0.2` | −0.025 | 40 (= 0.5 b/s) |

**Sinking is faster than rising because gravity assists it** — this is the qualitative fact, and it is
robust to §1.4 below.

The idle row is the model's independent cross-check: 0.025 b/t × 20 = **0.5 blocks/second**, which is
exactly the observed "a floating player slowly sinks" rate (there is **no buoyancy** in vanilla water).

Consumed by `movements/Swim.java` — the constants are written there as the *expression*
(`FLUID_DRAG`/`FLUID_GRAVITY`/`SWIM_IMPULSE` → `RISE_PER_TICK`/`SINK_PER_TICK`), never as literals.

### §1.4 ⚠ OPEN: `v*` is the stored delta-movement, not necessarily the travelled distance

`move()` runs **before** the drag multiply and **after** that tick's impulse (§1.1), so the distance
covered during tick *n* is `v_n + impulse`, not `v_n`. Substituting `u = v + impulse` gives
`u* = (impulse·(1 − drag) + drag·impulse ∓ gravity)/(1 − drag)`:

| | stored `v*` (what the code uses) | actual per-tick displacement `u*` |
|---|---|---|
| rise | 0.135 b/t → 7.41 t/block | **0.175 b/t → 5.71 t/block** |
| sink | 0.185 b/t → 5.41 t/block | **0.225 b/t → 4.44 t/block** |

The idle cross-check in §1.3 does **not** discriminate between the two conventions (no impulse ⇒ they
coincide). What *does* speak: the 2026-08-07 flagship run climbed the 1×1 waterfall column at an
observed **7–9 ticks per cell**, which matches the code's 7.41, not 5.71.

**Do not "fix" `Swim.UP_COST`/`DOWN_COST` on the strength of this note.** The bytecode ordering is
certain; the right *interpretation* is not, and the observed cadence contradicts the naive reading
(plausible causes: the follower does not hold jump on literally every tick, the arrival test costs a
tick or two per cell, or the bot never reaches terminal velocity within one cell). This is the
softest number in the swim cost model and the only honest resolution is an instrumented in-game
measurement of Y-displacement per tick under a held jump.

---

## §2 LAVA's integrator is NOT water's

`travelInFluid` dispatches on `isInWater()`; everything else goes to `travelInLava`, which is a
**structurally different** integrator:

```
travelInLava(input, gravity, isFalling, y0):
    moveRelative(0.02, input)
    move(SELF, dm)
    if (getFluidHeight(LAVA) <= getFluidJumpThreshold())        // SHALLOW / at the surface
        dm = dm.multiply(0.5, 0.8, 0.5)
        dm = getFluidFallingAdjustedMovement(gravity, isFalling, dm)   //  −gravity/16
    else                                                        // DEEP / submerged
        dm = dm.scale(0.5)                                      //  0.5 on ALL THREE axes, no /16 term
    if (gravity != 0) dm = dm.add(0, -gravity/4, 0)             //  −0.02 /t, ALWAYS, no sprint exemption
```

Consequences that matter to the cost model:

- **Lateral.** Terminal horizontal displacement is `accel/(1 − drag)`: water `0.02/0.2 = 0.1` b/t,
  lava `0.02/0.5 = 0.04` b/t. The ratio is exactly **2.5**. So
  `MovementContext.LAVA_SWIM_COST_FACTOR = 2.5` is not a tuned number — it is
  `(1 − 0.5)/(1 − 0.8)`, the drag ratio, and it is **correct for the lateral rungs**.
  (Aside: the same formula puts water's lateral swim at 0.1 b/t = **2.0 b/s**; `Swim.COST` uses the
  wiki's 2.2 b/s. Minor, and the difference is the `WATER_MOVEMENT_EFFICIENCY`/`getSpeed()`
  interpolation.)
- **Vertical, deep lava.** `jumpInLiquid(FluidTags.LAVA)` also fires from `aiStep`, but the fixed
  point of `v' = 0.5·(v + 0.04) − 0.02` is **`v* = 0`**. Under the stored-dm convention a bot holding
  jump in submerged lava **does not rise at all** — it hovers. Under the displacement convention
  (§1.4) it climbs at `u* = 0.04` b/t = **25 ticks/block**.
  Either way the flat 2.5× multiplier **under-prices a lava rise** (it claims `7.41 × 2.5 = 18.5`
  t/block). If lava-column ascent ever becomes a real route, price it from this section, not from the
  lateral factor.
- The `getFluidFallingAdjustedMovement` "hover snap" and the sprint exemption apply **only in the
  shallow branch**; deep lava never sees them.

---

## §3 Two hard vanilla gates on the pose/mode model

### §3.1 You cannot sprint-swim in lava

`Entity.updateSwimming()`:

```java
if (this.isSwimming())            // STAY branch
    setSwimming(isSprinting() && isInWater() && !isPassenger());
else                              // ENTER branch
    setSwimming(isSprinting() && isUnderWater() && !isPassenger()
             && level().getFluidState(blockPosition).is(FluidTags.WATER));
```

The ENTER branch carries an explicit `FluidTags.WATER` test and the STAY branch's `isInWater()` is
water-only. Lava fails both, so `Pose.SWIMMING` is **unreachable and unholdable** in lava. This is why
the prone family (`SprintSwim`/`StartSprintSwim`/`EndSprintSwim`/`DiagonalSprintSwim`) gates on
`MovementContext.water` while the upright `Swim` rungs are fluid-agnostic. Not a modelling choice —
a vanilla constraint we decline to model as reachable.

### §3.2 You cannot stand up in a 1-tall gap

`Player.updatePlayerPose()`:

```java
if (!canPlayerFitWithinBlocksAndEntitiesWhen(Pose.SWIMMING)) return;   // can't even fit prone → keep pose
Pose desired = getDesiredPose();                                        // STANDING once isSwimming() is false
if (isSpectator() || isPassenger() || canPlayerFitWithinBlocksAndEntitiesWhen(desired)) pose = desired;
else if (canPlayerFitWithinBlocksAndEntitiesWhen(Pose.CROUCHING))       pose = Pose.CROUCHING;
else                                                                    pose = Pose.SWIMMING;
```

`canPlayerFitWithinBlocksAndEntitiesWhen` is a real `noCollision` box test (the pose's
`EntityDimensions`, deflated by 1e-7). Dropping sprint clears the `isSwimming()` **flag** immediately,
so `getDesiredPose()` returns `STANDING` — but the fit test then falls back through `CROUCHING` (1.5
tall) to `Pose.SWIMMING` (0.6 tall) when there is no headroom.

**Two consequences the code depends on:**
1. Standing up underwater is a **fit gate**, not a policy — `EndSprintSwim` may only be emitted where
   two non-solid body cells exist above the floor.
2. **The flag and the pose diverge in exactly this state**: `isSwimming()` is `false` while the hitbox
   is still 0.6 tall (vanilla crawling). Any "am I prone?" seam **must read the POSE**, never the
   flag — `AllyBotEntity`/`BotSteering.prone()` does.

---

## §4 Damage cadence — and the invulnerability window is **10 ticks**, not 20

### §4.1 Lava contact: 4.0 HP per landed hit

`LavaFluid.entityInside` applies `CLEAR_FREEZE` + `LAVA_IGNITE`, then
`runAfter(LAVA_IGNITE, Entity::lavaHurt)` (confirmed via the class's single `BootstrapMethods` entry:
`REF_invokeVirtual net/minecraft/world/entity/Entity.lavaHurt:()V`). `Entity.lavaHurt` is
`if (!fireImmune()) hurtServer(level, damageSources().lava(), 4.0f)`. It is attempted **every tick**
the entity is inside lava.

### §4.2 The i-frame gate: field = 20, effective cadence = **10**

`LivingEntity.hurtServer`:

```java
if (this.invulnerableTime > 10.0F && !source.is(DamageTypeTags.BYPASSES_COOLDOWN)) {
    if (amount <= this.lastHurt) return false;      // equal-magnitude repeat → nothing happens
    actuallyHurt(level, source, amount - this.lastHurt);
    this.lastHurt = amount;
} else {
    this.lastHurt = amount;
    this.invulnerableTime = 20;                     // ← the FIELD is 20 …
    actuallyHurt(level, source, amount);
    this.hurtDuration = 10;
}
```

The field is set to 20 but **the gate is `> 10`**, and the counter decrements once per tick. So for a
*constant-magnitude* repeating source such as lava, the sequence is: hit at t, blocked for t+1…t+9
(`invulnerableTime` 19→11), and at t+10 (`invulnerableTime == 10`, not `> 10`) the else-branch runs
again at full damage. **Lava therefore deals 4.0 HP every 10 ticks — 8 HP/s** — which is the
long-standing "4 hearts every half second" behaviour.

⚠ Decrement site is split, and it matters for a `ServerPlayer`-derived bot:
`LivingEntity.baseTick` decrements only `if (invulnerableTime > 0 && !(this instanceof ServerPlayer))`;
`ServerPlayer.tick()` does its own decrement. Orebit's bot runs the full `super.tick()`, so it gets
exactly one decrement per tick — but a future change that skips `ServerPlayer.tick` would freeze the
counter and make the bot effectively lava-immune.

**`MovementContext.lavaSwimCellCost`'s javadoc ("≈4 HP per 10-tick i-frame window over the ~23 ticks a
lava cell takes") is CORRECT and self-consistent**: a lava swim cell is `9.09 × 2.5 ≈ 22.7` ticks,
`22.7/10 × 4 ≈ 9.1 HP`, ratified as `LAVA_HP_PER_CELL = 10`. Do not "correct" it to 20.

### §4.3 Burning aftermath: 1 HP per 20 ticks

`Entity.baseTick`, server side only:

```java
if (remainingFireTicks > 0) {
    if (fireImmune()) clearFire();
    else {
        if (remainingFireTicks % 20 == 0 && !isInLava())
            hurtServer(level, damageSources().onFire(), 1.0f);
        setRemainingFireTicks(remainingFireTicks - 1);
    }
}
```

Note the `!isInLava()` guard: burn damage is explicitly *suppressed while still in lava*, so lava
immersion and burning never double-charge (and would not anyway — 1.0 ≤ `lastHurt` 4.0 fails the §4.2
repeat test). Also: `baseTick` halves `fallDistance` every tick spent in lava.

### §4.4 A fire BLOCK deals no direct damage

`BaseFireBlock.entityInside` applies `CLEAR_FREEZE` + `FIRE_IGNITE` and
`runAfter(FIRE_IGNITE, this::fireIgnite)` — `fireIgnite` only sets `remainingFireTicks`. **All** of a
fire block's damage arrives later, through §4.3's 1 HP / 20 ticks. `Entity.igniteForSeconds(s)` =
`igniteForTicks(floor(s × 20))`, and `igniteForTicks` only ever *raises* the counter.

---

## §5 The open question this leaves behind (owner-flagged, unanswered)

**Pricing is not feasibility.** A* charges a mortal bot ~1023 ticks per lava cell (`9.09 × 2.5 + 10 HP
× 100 ticks/HP`), which reads as "only when nothing else exists" — but it is still a *price*, and the
bot would physically **die** crossing two cells. Goal tolerance will also happily end a plan inside
lava. Since the head test was relaxed from "air" to "not solid" (fluid *interiors* became enterable,
not just surfaces), this gap is materially more reachable than it was.

**The owner's planned answer is a "budgets" concept** — a health / stamina / breath ledger the planner
spends against, so N hazard cells price superlinearly as they approach lethality instead of each
paying a flat scalar. (`MovementContext`'s own hazard-cost comment already names this as the ratified
successor to the flat per-cell term.) Not designed; recorded so the next arc starts from the right
frame.

**The cost-model unification worth building with it.** Make the damage charge **dwell-scaled**:

```
HP ≈ (moveTicks / iframeTicks) × HP_per_hit          with iframeTicks = 10  (§4.2)
```

One rule with no lava branch reproduces all three existing hand-set constants:

| case | dwell | rule gives | current constant |
|---|---|---|---|
| lava swim, one cell | 22.7 t | `22.7/10 × 4 ≈ 9.1 HP` | `LAVA_HP_PER_CELL = 10` |
| hazard walk-through, one cell | 4.63 t | `4.63/10 × 4 ≈ 1.9 HP` | flat `1 HP` pass-through charge |
| damaging floor contact | 4.63 t | ≈ 0.5 HP at 1 HP/hit | flat `1 HP` (deliberate over-charge) |

Pair it with giving the descriptor's transit-slow field **multiplier** semantics for fluids
(`NavBlock.TRANSIT_FLUID` already carries lava's `0.4` = `1/2.5`, but the generic transit constants are
flat *walk-derived* tick surcharges, which is the wrong SHAPE for a dwell problem: an 88-tick cobweb
surcharge on a 9.09-tick swim rung is wrong by shape, not magnitude) and
`MovementContext.lavaSwimCellCost` disappears entirely. Deferred because it touches every
hazard-pricing site at once. Caveat from §4.4: a *fire* block's per-hit is 1 HP via burn, not 4, so
the `HP_per_hit` term must come from the hazard, not be a constant.

---

## §6 Code pointers

**Scope note (2026-08-16):** this document covers the **entity-side** fluid model — how a body moves,
poses and takes damage *in* a fluid. It says nothing about how fluid itself **spreads**. That is a
disjoint subsystem (`FlowingFluid.spread`/`getSpread`/`getSlopeDistance`, the waterlogging and
face-occlusion rules, and the block-update/BUD scheduling that makes a settled world stay settled), and
it lives in **`DESIGN-fluid-flow-prediction.md` §1**, bytecode-verified to the same standard.

| Fact | Lives in |
|---|---|
| §1 water rise/sink cost derivation (inline) | `pathfinding/blockpathfinder/movements/Swim.java` — `FLUID_DRAG`/`FLUID_GRAVITY`/`SWIM_IMPULSE`, `UP_COST`/`DOWN_COST` |
| §2 lateral lava factor | `MovementContext.LAVA_SWIM_COST_FACTOR`, `MovementContext.FLUID_TRANSIT_COST`, `NavBlock.TRANSIT_FLUID` |
| §3.1 water-only prone family | `movements/SprintSwim`, `StartSprintSwim`, `EndSprintSwim`, `DiagonalSprintSwim` (all gate on `MovementContext.water`) |
| §3.2 pose-not-flag, headroom gate | `AllyBotEntity.prone()`, `BotSteering.prone()`, `EndSprintSwim`, `Surface` |
| §4 hazard pricing | `MovementContext.lavaSwimCellCost` / `floorHazardCost` / `cellTransitCost`, `NavBlock.isDamaging` |
| depth autopilot (jump/sink actuation) | `SteerControl.holdDepth`; upright horizontal servo = `SteerControl`'s yaw-only velocity servo |
