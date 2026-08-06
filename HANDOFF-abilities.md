# HANDOFF — corner-blend conflict fixed; the problem is now ROUTING, not movement

Written 2026-08-02. Supersedes the previous handoff (the climbable arc, committed as `b7eeecc`).

**Goal:** the bot completes the autotest flagship `(60,180,253) → (201,-28,90)` end-to-end.
**Status:** not yet — but it now *moves* well and *routes* badly, which is a different problem than the
one this arc started on. See §4.

---

## 0. READ FIRST — the harness is not reproducing run-to-run

Adding **one log statement** moved a flagship run from `bestDistXZ` 171.04 to 65.84, with a completely
different route. Frozen-world mode guarantees identical TERRAIN, not identical SCHEDULING.

Suspected mechanism, from `scripts/autotest/orebit.properties`' own comments: the deterministic drain
relies on `pathing.chunkBuildBudgetMs=100` / `hpaFlushBudgetMs=100` **never binding**, so the fixed
count backstops. Heavy `-BotDebug` logging on the tick thread can push a tick past 100 ms — at which
point the ms budget DOES bind and the drain becomes wall-clock dependent.

**Consequence: single-run A/B comparisons on this harness are not trustworthy**, including several drawn
on 2026-08-01. Before believing any flagship delta, either (a) run both arms with identical logging
flags, or (b) fix the determinism first. This is the highest-value open item — everything else is being
measured through it.

---

## 1. Uncommitted in all three worktrees

Unit **673/0**, parkour **82/0/1** (best ever), `chiseledCompileCommon` (28 versions) and 26-era
`chiseledCompile` both green. Worktrees verified byte-identical.

| change | file | what |
|---|---|---|
| **lane containment** | `SteerControl` | THE BIG ONE — see §2 |
| narrow tops not standable | `NavBlock` (+ its `verifyDerivedBits` twin) | kills the dripstone stance at the source |
| sink-in gate | `Climb` | `isClimbable && !passable` instead of `&& standable` |
| `Diagonal` → `plan()` | `Diagonal` | envelope + carry arrest; last unconverted ground mover |
| `settled()` revert + `Climb.reached` | `BotSteering`, `Climb` | top-out scoped to the one move that emits it |
| `atWaypoint` | `Movement` | block-exact, with a DO-NOT-RE-ADD note (§3) |
| exec-line instrumentation | `BotNavigator` | `x/z/velX/velZ` + sub-cell tenths in the dedup key |

Also uncommitted, harness only: `pathing.syncSearchBudgetNodes=40000` in
`scripts/autotest/orebit.properties`.

---

## 2. The corner-blend conflict (fixed) — the session's main finding

`SteerControl.steerTowards` (and its `groundServo` twin) has a **racing-line corner blend**: as the bot
nears its waypoint it rotates the desired heading toward the NEXT leg — up to `CORNER_BLEND_MAX` = 55%
— plus a `CORNER_RACING_BIAS` = 0.5 OUTWARD push, starting `CORNER_BLEND_DIST` = **1.3 blocks** out,
i.e. more than a full cell before arrival.

Every converted movement's `failWhen` envelope admits only **that step's own columns**. So on any corner
where the next leg turns, the steer deliberately drove the bot out of its lane and the envelope
fail→HOLDed it for obeying the steer. **Three sites in one day** — `(58,113,160)`, `(62,135,189)`,
`(143,113,13)` — in unrelated terrain, which is why it kept looking like a new bug each time.

**The instrumented capture that settled it** (`(143,113,13)`, a constant-z `−x` Descend):

```
x=144.498 z=14.419  vel=( 0.0000, 0.0000)   <- starts AT REST, near-centred
x=144.250 z=14.395  vel=(-0.0822,-0.0092)
x=144.074 z=14.360  vel=(-0.0964,-0.0193)
x=143.892 z=14.292  vel=(-0.0991,-0.0371)
x=143.701 z=14.222  vel=(-0.1046,-0.0380)
x=143.516 z=14.127  vel=(-0.1005,-0.0520)
x=143.606 z=14.002  vel=( 0.0510,-0.0322)   <- z crosses 14.0 -> FAILED
```

Cross velocity **manufactured from zero**, growing monotonically with `w` as `dCorner` shrinks. Not
momentum, not terrain, not landing accuracy.

**The fix** (`SteerControl.blendLeavesLane`, owner ruling): the blend may round toward the next leg only
while that keeps the bot inside the current step's lane. Positional, not predictive — no horizon, no new
constant; reuses the step-off gate's own bound (`0.5 − PARKOUR_CELL_MARGIN` = 0.2). Once the bot is at
the bound AND the blended heading still points outward, the blend is dropped for that tick.

**It cost nothing.** All five turn cards — which exist *because* the blend was added for an orthogonal
run-up into a parkour — pass with **byte-identical takeoff speeds** (0.4659 / 0.4672 / 0.4672 / 0.4558 /
0.4558). On a run-up the bot never reaches the lane bound, so the gate never fires.

---

## 3. Two wrong turns this session — recorded so they aren't repeated

**Blaming `settled()`.** I attributed a wedge to `settled() |= climbableBelow()` firing mid-parkour-arc.
The block below was `jungle_leaves`, not climbable, so it could not have fired; the logged line even said
`reached=false`; and `ABANDONED ... (reached fired before done)` is a **documented false positive** for a
grounded-gated parkour landing (`PhaseRunner`'s own javadoc). The `settled()` revert was kept anyway, on
principle — `settled()` is shared with the plan-anchor rule — but NOT on evidence.

**Inventing a partial-top `reached` bug.** I produced a table showing slabs/snow/soul sand livelocking on
arrival. It was wrong: `BlockPathfinder.feetYOf` already builds waypoints topY-aware, so planner and
follower never disagreed. The "fix" accepted the bot one cell too low on every correct waypoint and timed
out the `stairup` card. Reverted; `Movement.atWaypoint` now carries a **DO NOT add a partial-top
tolerance here** note explaining why it looks necessary and which card it breaks.

Dripstone was never a partial-top case: `pointed_dripstone` is **force-classified `SHAPE_FULL`** in
`NavBlock.fingerprint` (its null-world collision query misleads), so `topY` reads 16 while vanilla seats
the bot at `+11/16`. Fixed where it belongs — narrow tops are no longer `STANDABLE`.

Both errors share a shape: a plausible mechanism accepted without checking the one cheap fact that would
have refuted it (a block lookup, a grep for `feetYOf`). The region-file dump in §6 is that cheap fact.

---

## 4. Where the flagship actually is

Latest run (40k, instrumented, lane containment):

| | prev (same flags) | now |
|---|---|---|
| `step FAILED` | 3 | **1** |
| `distY` at end | 141 | **85** |
| `bestDistXZ` | 65.84 | 65.84 |
| end state | — | alive, pillaring, **not wedged** |

The bot is no longer failing to *execute*. It ends the budget at `(146,57,-56)` — goal is `z=90` — having
worked its way to `z=-56` and turned around. `routeEfficiency` 3.34, 244 searches, 17 cap hits at 40k.

**So the next arc is the region tier / heuristic, not movement.** Why does the route go to `z=-56`?
Nothing in this session touched that, and it is now the dominant cost.

`bestDistXZ` has still never beaten the `c84c4b9` baseline's **58.43**, and that baseline's `distY` was
never captured, so there is no honest 3D comparison. If a real baseline matters, re-run `c84c4b9` with
today's flags and record its full end state.

---

## 4b. THE ACTIVE ARC — region-tier cost & targeting

Four convicted issues with full evidence, a live `/bot rtrace`, and the owner's rulings are written up
in **`internal_docs/FINDINGS-region-cost-audit.md`**. Read that before touching the region tier. In
short:

1. `UNSAFE_VERTICAL_PENALTY` hits every DRY fragment, not just `KIND_AIR` — a cave descent is charged
   291 vs 35 honest, losing to a 99 dig. Owner ruling: restrict it to `KIND_AIR`. Flips cave-vs-dig on
   its own.
2. L2 flooded at `MAX_REGION_EXPANSIONS = 20000`; owner expects a flood at the top to escalate a level.
   It currently classifies as no-route instead.
3. `mineSpans` floors intra-region digs at half a region AND zeroes the vertical span — over-estimating
   (inadmissible) and converting the cheap axis into the expensive one.
4. Only 2 of 15 skeleton portals are `[stand]`; the rest are `[air-no-floor]`/`[buried]`, which is what
   drives `WindowTargeting` into its snap/CENTER fallbacks. Prime suspect: `portalCell` is a bbox
   CENTROID, and a bbox centroid need not be a member of the set it bounds.

The window-slide behaviour (§5 there) is DEFERRED and is **not** a regression — both gating conjuncts
are deliberate; see that doc before proposing to remove either.

## 5. Next, in the order I'd take them

1. **Harness determinism** (§0) — everything else is measured through it.
2. **Routing**: the `z=-56` excursion. Region tier / heuristic, untouched today.
3. **The wall-hug diagonal** — structural. `Diagonal.candidates` requires BOTH corner columns clear;
   the owner's ruling is that only ONE need be, because MC resolves collision per-axis: the wall clamps
   the blocked axis while the free axis keeps resolving, so the box slides along the wall face and never
   spends a tick squarely over the hole. Convicted at `(60,133,189)→(61,133,188)`: corner `(61,134,189)`
   is leaves (the **wall**), corner `(60,133,188)` is vine (the **hole**, which would arrest a slip
   rather than drop the bot). Refusing it forces an `Ascend` onto the very blocking block and a `Descend`
   back down — the detour that produced the `(62,135,189)` wedge. A one-side variant was built and
   REVERTED once ("servo fights the hug slide"); the owner's quantization argument says why — a servo
   steering at the destination CENTRE fights a wall standing between it and that centre. Steering along
   the FREE axis and letting the wall clamp the other is a different control law, not a tuning change.
4. `magmaov.rest` — the one remaining course gap (hazard-overfly from a standstill).

---

## 6. Tools that earned their keep

**Read the frozen master's region files directly.** `internal_docs` has no tool for this; two throwaway
scripts in the session scratchpad did the work — a minimal Anvil reader plus a per-X slice renderer
(`.`=air `L`=leaves `V`=vine `W`=log). Both vine and dripstone geometries were pinned this way in
seconds, and it refuted two of my own hypotheses. Worth promoting into `internal_docs/`.

**The exec-line instrumentation** (`x`, `z`, `velX`, `velZ`, sub-cell tenths in the dedup key) is what
cracked the corner blend. Foot-cell granularity structurally cannot show a sub-cell drift. Keep it.

---

## 7. Verification protocol

**GOTCHA — quote the coords AND invoke with `&`.** `$Start` is `[string]`, so a bare `60,180,253` is
array-coerced to `"60 180 253"` and the mod dies at init. A nested `powershell script.ps1 -Start "..."`
does not survive either — the quotes are stripped before the child parses. Call it in-session:

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"   # JDK 21, mc-1.21 era
cd C:\Users\steve\Repos\personal\orebit-mc121-wt

.\gradlew.bat :1.21.11:test                     # expect 673 / 0
powershell scripts\run-parkour.ps1              # expect 82 / 0 / 1
& .\scripts\run-autotest.ps1 `
  -MasterWorld '..\orebit-autotest-world\scripts\autotest-world-master\world' `
  -Start '60,180,253' -Goal '201,-28,90' -BudgetTicks 24000 -BotDebug
```

Grep for `step FAILED`, `advance SKIPPED`, `region re-derive` — **but a LIVELOCK emits none of them.**
Two wedges this session were steps that never *completed* rather than failed. Also check whether the log
tail is one `exec` line repeating on a short period.

**Do not flip `fail→hold` to auto-replan** to make the flagship pass. It manufactures a green run by
hiding exactly the pathologies this arc exists to fix.
