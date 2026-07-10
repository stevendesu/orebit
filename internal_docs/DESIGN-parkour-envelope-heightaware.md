# Parkour Envelope — Height-Aware Re-derivation (Design + Handoff)

**Status:** design for a fresh session to implement. Supersedes the *model* of
`DESIGN-parkour-envelope.md` (whose closed form is now known to be ~1 block too conservative — see §3).
Written after a long diagnostic session that built the harness, fixed the overshoot, and pinned the
undershoot root. Everything below §7 (working-tree state, how to run) is the handoff; §1–§6 is the design.

---

## 0. TL;DR

- The pathfinder currently **offers parkour jumps that are physically unmakeable** — specifically the
  **diagonal 3-gap** (`DiagonalParkour.MAX_GAP = 3`) and, per the owner's "wiggle-room" bar, the marginal
  **rising 3-gap** (`Parkour.RISE_MAX = 3`). This is the *un-finished* "restrict pathfinding to makeable
  jumps" work (NOT the deferred replan/miss-detection work — those are two different "envelopes"; see §1).
- The greedy block-A* (weight 2.0, beelines to goal) picks an unmakeable diagonal-3 as a **corner-cut**
  off a turn, the bot attempts it and falls. That is the reported "bot runs along, fails a jump, gets
  stuck." **Confirmed by instrumentation** (the routed edge was `seg=(86,139)→(90,143)`, Δ+4+4 = a 3-gap
  diagonal) and by a head-on `diag3` test that fails 2/2 at full sprint.
- **Fix = finish the makeable-jumps-only envelope**, as a boot-time-derived, **height-aware**,
  **hot-path-free** `ParkourEnvelope`. No planner/search changes; no per-node math.

---

## 1. The two "parkour envelopes" (the conflation to avoid)

1. **Gap/reach envelope** — *which parkour jumps are physically makeable, so only offer those.* This is
   the subject here and of `DESIGN-parkour-envelope.md`. It was designed + owner-reviewed but **never
   implemented** (`ParkourEnvelope.java` does not exist; `RISE_MAX`/`MAX_GAP` still ship the unmakeable
   rows).
2. **Validity envelope** — *per-move miss-detection for replanning* (state-based recovery when the outside
   world knocks the bot off course). This is the owner's deliberately-deferred work ("movements should
   work every time in a vacuum; only replan on external interference"). **Not** this doc.

The diag-3 bug is a #1 problem. Do not conflate with #2.

## 2. The measured envelope (ground truth from the harness)

All head-on, full sprint, on 1.21.11 via the `runParkour` harness (§8). "margin" = how far into the
landing cell the bot's centre settled (bigger = more wiggle room).

| Jump (cardinal unless noted) | Result | Note |
|---|---|---|
| flat 1/2/3 | ✅ | flat-3 lands ~0.84 into cell (comfortable) |
| **flat 4** | (not offered) | correctly excluded — the frame-perfect row |
| rise 1/2 | ✅ | |
| **rise 3** | ✅ **but marginal** | lands only ~0.16 into cell → **exclude** (owner: "technically possible, not easily made" is exactly what the model is meant to cut) |
| fall −1 (gaps to 4) | ✅ | 1-wide-ledge overshoot was the *separate* bug, now fixed (§7) |
| fall −2/−3 (gap 4) | ✅ | |
| diag 1/2 | ✅ | diag-2 span 2·√2 = 2.83, just inside the "3-block cleared air" bar |
| **diag 3** | ❌ **fails 2/2** | span 4·√2 = 4.24; genuinely unmakeable — face-hits the landing edge with feet ~0.3 below its top |

**Target envelope: flat 3, rise 2, fall 4, diag 2** (offset (2,±1)/(3,±1) unchanged). Same table
`DESIGN-parkour-envelope.md` §5.7 derived — but reached here by **measurement**, and for a different
reason on rise-3 (marginal, not impossible; see §3).

## 3. Why the old model is wrong (must be re-derived)

`DESIGN-parkour-envelope.md`'s closed form measures reach from the **takeoff trigger** (`center + 0.35`).
But the executor's TAKEOFF phase holds `setJumping` for ~2–3 more ticks after the trigger fires, so the
bot actually **leaves the ground ~1 block further along** — in every trace the airborne transition is at
`proj ≈ 1.3–1.4`, not `0.35`. That extra ~1 block of ground-sprint reach is **not** in the old model's
air budget `X(T)`.

Consequences:
- The old model calls **rise-3 unmakeable** (budget 2.488 vs req 2.85, short 0.36). Reality: it *lands*
  (the +1 block of coast covers it) — but with only 0.16 margin, so it's excluded on the **wiggle-room**
  bar, not the impossibility bar. The old model got the right answer for the wrong reason.
- **diag-3 still fails** even with the coast (its descent face-hits the landing edge), so the exclusion
  holds — but the numeric margin the old model reports (−0.78) is not the real one.

So a correct model must integrate the **actual leave-ground point** (center + the takeoff-phase coast,
derived from the same ballistics as one or two extra ground-sprint ticks past the `TAKEOFF_EDGE`
trigger), and must still reproduce the measured truth: **flat-3 IN, flat-4 OUT (frame-perfect),
rise-3 OUT (marginal), fall-4 IN, diag-2 IN, diag-3 OUT.** The reference `parkour_envelope_verify.py`
has the closed forms (`y(T)`, `X(T)`, `T(Δy)`) to build on — extend it with the coast term and re-check
all six outcomes before trusting it.

## 4. Height-aware requirement (the new dimension)

Current state (verified): the **vertical** (can-I-gain-this-height) axis IS partial-block-aware —
`MovementContext.rise(dyBlocks, destTopY, startTopY)` gated by `JUMP_RISE=20`/`STEP_ASSIST_MAX_RISE=9`,
with `topY` from `NavBlock` (16=full, 8=slab, 2=repeater), and `PartialHeightTest` covers slab
takeoffs/landings for that gate. But the **horizontal gap maxima are fixed full-block constants** — a
slab/snow/enchanting-table/stonecutter takeoff (standable partial blocks) is offered the *full-block*
reach, which is optimistic: a lower takeoff surface shifts the effective Δy toward "rising" (less reach).
Dripstone/bamboo are `SHAPE_OTHER` (non-standable) so the bot won't take off from them — but slabs etc.
it will.

So the envelope must be a function of the **takeoff surface height** (and, ideally, the landing surface
height). A jump from an 8/16 slab is effectively half a "rise" harder than from a 16/16 block.

## 5. The hot-path-free table (owner's ratified structure)

**Hard constraint: no per-node arithmetic.** The block-A* candidate scan reads the maxima on the hot
path; they must be a plain indexed array load, not a computed value. So:

**Bake, at class load, per-takeoff-surface-height arrays:**

```
// startTopY in sixteenths, 1..16 (16 = full block, 8 = slab, ...). Indexed directly by NavBlock.topY.
static final int[][] MAX_GAP;      // MAX_GAP[startTopY] = { flatMax, riseMax, fall1Max, fall2Max, fall3Max, diagMax, ... }
```

Hot path: `int[] env = ParkourEnvelope.MAX_GAP[startTopY]; int flatMax = env[FLAT]; ...` — one array
index + field read, JIT-folded, zero math. (Mirror `DESIGN-parkour-envelope.md` §7.2's "static final loads
fold to constants" property, now one level of indexing deeper.)

Open design choice for the implementer (decide from the model + a couple of harness probes):
- **Landing surface height.** Does the reach max also need a landing-`topY` dimension (→ a 2-D table
  `MAX_GAP[startTopY][landTopY]`), or is landing height adequately handled by the existing `rise()`
  jumpability gate + treating the landing as its integer level? Start 1-D (takeoff only); add the landing
  axis only if a slab-landing harness probe shows a real miss the `rise()` gate doesn't already catch.
- **Index range.** Only a handful of `topY` values occur (16, 8, and a few low partials like 2, 9, 12);
  a full 1..16 table is cheap (≤16×N ints) and simplest — bake all 16, most rows will be identical.

Everything is computed **once** in the static initializer from the §3 re-derived model. Static init must
contain **no hard-coded expected maxima** (that defeats the derivation); the pins live in tests (§6).

## 6. Implementation plan

1. **`parkour_envelope_verify.py`** — add the takeoff-coast term to the model; confirm it reproduces the
   six measured outcomes (§2) with the real leave-ground point. This is the spec for the Java.
2. **`movements/ParkourEnvelope.java`** — a `final` smart object (package-mate of Parkour/DiagonalParkour).
   Static init evaluates the closed forms per `startTopY` (1..16) and bakes `MAX_GAP[startTopY][class]`
   plus `MAX_CLEARED_AIR = 3.0` (the policy cap: cleared air ≤ 3 for Δy≥0, ≤ 3+drop for Δy<0; keeps the
   frame-perfect-4 out even if the model drifts). No MC imports (pure arithmetic → safe static-init order,
   works in the headless harness/JMH).
3. **Wire the movements (hot path, no math):**
   - `Parkour.candidates`: read `flatMax`/`riseMax`/`fallMax` from `ParkourEnvelope.MAX_GAP[startTopY]`
     instead of the `PARKOUR_MAX_GAP`/`RISE_MAX`/`FALL_MAX` fields. `startTopY` = the takeoff cell's
     `ctx.floorSurface(x,y,z)` (already computed for the rise gate — reuse it).
   - `DiagonalParkour.candidates`: `maxGap` = `ParkourEnvelope.MAX_GAP[startTopY][DIAG]`.
   - Keep `PARKOUR_MAX_GAP` as the public *narrowing* knob (min with the envelope value), per the old §7.3.
   - **Costs stay hard-coded (phase 1).** Deriving `AIR_COST`/`FALL_EXTRA` from the same model changes
     f-values/search behavior → a separate, JMH-A/B'd change (old doc §7.6). Envelope-only here.
4. **Tests:** flip `ParkourLandingsTest.risingThreeGapIsOffered` and `DiagonalParkourTest`'s 3-gap case to
   negatives (assert NOT offered); add a `ParkourEnvelopeTest` pinning the derived table (flat 3, rise 2,
   fall 4, diag 2 for a full-block takeoff) + the six-outcome margins + a slab-takeoff row (tighter maxima)
   + `OFFSET_C_LIMIT == flatMax`. Keep the harness `diag3`/`rise3` trials as the runtime negatives.
5. **Docs:** rewrite `docs/movements.md` Gap-jumps section (it's already stale). Note this doc supersedes
   `DESIGN-parkour-envelope.md`'s model.
6. **Gates:** unit tests + `chiseledCompileCommon --continue` (all 28) + the era merge for 26.2. **Owner
   runs the JMH paired-A/B** (the candidate set changes → search behavior changes): BRIDGE/TOWER/OPEN +
   SHORT/MULTI at minimum, per the perf-process rules. Then in-game smoke on a turn-into-a-jump and a
   slab-takeoff jump.

## 7. State of the working tree (uncommitted, on `core` worktree `orebit-core-wt`)

Authored on `core`, mirrored into `orebit-mc121-wt` for building/running (JDK 21, 1.21.11). **Nothing
committed** (owner is holding for one batch). Changes present:

- **KEEP — overshoot fix (verified):** `movements/Parkour.java` — reach-aware sprint: a falling jump
  sprints only at `gap ≥ 3` (the drop's airtime lets a walk clear shorter gaps; the sprint boost was
  overshooting a 1-wide ledge — the `fall2` bug). Verified: `fall2` passes, deep falls unregressed,
  parkour unit tests green. Executor-only (in `plan()`), does not touch the search.
- **KEEP — the harness + instrumentation:** `ParkourCourse.java` (new), `OrebitCommon.java` (registers it),
  `fabric/build.gradle.kts` (`runParkour` config), `scripts/parkour/*` + `scripts/run-parkour.ps1` (new),
  `BotNavigator.java` (added diagnostic `segFromX()..segToZ()` getters + snapshot — lets the harness log the
  ACTUAL routed jump cells). `ParkourCourse` currently includes the `diag3`/`rise3` probe trials.
- **REVERTED (do not resurrect as-is):** two executor experiments that treated the undershoot as a
  takeoff-alignment bug — an ALIGN phase and a two-axis takeoff trigger in `DiagonalParkour`. Both aligned
  the takeoff but did NOT fix `turnflat2/3`, because the real cause is the envelope (unmakeable diag-3),
  not alignment. A velocity seam (`BotSteering.motionX/Z`) was added then reverted with them.

## 8. How to run the harness (fresh session)

From `orebit-mc121-wt` (JDK 21):
```
JAVA_HOME=<jdk-21>
./gradlew "Set active project to 1.21.11"
./scripts/run-parkour.ps1        # preps run/parkour, launches headless, prints the PASS/FAIL table
```
Results: `run/parkour/orebit-parkour-result.properties` (per-trial PASS/FAIL + `takeoffSpd` + `finalY`).
Trajectories: `run/parkour/orebit-parkour-trace.txt` — per-tick `x y z | spd vy | onGround | move`, with
`MOVE <name> seg=(from)->(to)` on each move change (the actual routed jump) and a `TAKEOFF` marker.
The course is a compact grid of floating tiles; each shape runs `walkin` (runway) + `rest` (standstill on
the takeoff block). Config `scripts/parkour/orebit.properties` turns placement+mining OFF (a jump is the
only way across) and `takesDamage` ON (a miss falls ~200 blocks and dies). Add trials in
`ParkourCourse.buildTrialList()` (helpers `card`/`diag`/`offset`/`turn`).

**Gotchas learned:** (1) the mc-1.21 era can serve **stale classes** — verify a run is fresh via the
`trials=` count / distinct `takeoffSpd`, and prefer reading the result from the run's own stdout, not a
raced shared file. (2) A long linear course leaves far tiles nav-unbuilt (the `diag2` "no route" artifact)
— the grid layout + per-trial settle fixed it; keep it compact.

## 9. Open questions for the implementer

- Confirm the coast term in the re-derived model reproduces all six outcomes (§3), then decide rise-3
  strictly on the wiggle-room bar (it's IN by physics, OUT by margin).
- 1-D (takeoff-`topY`) table vs 2-D (+ landing-`topY`) — probe a slab-*landing* jump in the harness to see
  if `rise()` already covers it (§5).
- Does the bot ever take off from a partial block whose `topY` the classifier reads oddly (snow layers vary
  1..15/16)? A quick `/bot probe` or harness slab/snow tile settles it.
- Costs (phase 2): whether to also derive `AIR_COST`/`FALL_EXTRA` height-aware (separate JMH-gated change).
