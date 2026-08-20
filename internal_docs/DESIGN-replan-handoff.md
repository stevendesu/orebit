# DESIGN — Seam-anchored replans (the replan/adoption handoff)

Status: §1–§9 RATIFIED + IMPLEMENTED (course-verified), 2026-08-18. §10 ratified same day in a first
shape, SUPERSEDED same day by the unified U1–U6 design (see §10 for the sync blind spot that killed
the first shape), and implemented in the unified form.
Companion evidence: the 2026-08-18 long-flagship wedge at (1328,76,1456) (`orebit-mc121-wt`
latest.log 07:13:52), the three code-read reports behind every file:line cited here, and the
external survey (Baritone / Detour / Unreal / robotics) in the session memory
`replan-adoption-handoff-research`. Line numbers are as-of core @ HEAD 2026-08-18 and will drift.

## §1 Problem — the convicted mechanism

A mid-motion re-search adopted a plan whose step 0 reversed the bot's travel, and the bot froze
(`step FAILED (validity envelope) Descend step 0`). Three stacked facts, all verified in code:

1. **The settled boundary has a mid-move hole.** All boundary machinery (adoption, window-swap,
   forward-slide re-search) gates on `currentFloor.equals(settledFloor) && planAnchor`
   (BotNavigator:697). But `settledFloor` is written at *move completion* (BotNavigator:1324), and
   the bot then spends the first ~half of the NEXT move still inside that cell — grounded, at
   speed, near the far edge. "Settled" today includes "sprinting out of the settled cell."
   The wedge's swap passed the gate legitimately: mid-Traverse, feet still in the settled cell,
   0.044 blocks from the boundary, velocity 0.113 b/t outward.
2. **The plan starts from a cell anchor; executability depends on continuous state.** The search
   start is `botFloor` = the settled cell (PathPlan:1354/1365). Sub-cell position, velocity, and
   pose are not part of the start state. Sync/same-tick planning does NOT close this — it is a
   quantization gap, not a latency gap (the wedge run was `pathing.async=false`).
3. **The adoption tick can be input-silent, and `zza` persists.** The swap happens in
   `driveToward` BEFORE `steerAlongPath` in the same tick; the new step 0 IS armed and run that
   tick. But Descend's `clear` phase on plain ground writes no inputs and no tag
   (`holdUntilOverTargetColumn` opens `if (!b.onClimbable()) return false;` — SteerControl:813)
   and advances unconditionally; the tick-top input reset zeroes `xxa/yya/jump/sprint/sneak` but
   deliberately NOT `zza` (AllyBotEntity:556-562), and nothing in the swap block, `clearPlan`, or
   `PhaseRunner.begin/clear` touches inputs. So the superseded Traverse's `setForward(0.98)` was
   re-applied by physics under the new plan, in the stale yaw. This is the SAME hole commit
   `bfca4a5` ("arm the carry-arrest on the clear phase — the stale-thrust handoff gap") convicted
   and fixed for the same-plan Traverse→Descend cursor advance, before the whole series was
   reverted (1f26d2f). The window-swap adoption reaches it through a second door.

Prior casualty of the same mechanism, already in the code's own docs: the 2026-08-06 Descend that
failed its envelope by 0.001 blocks after a plan-impacted re-search "rebuilds the plan and resets
the waypoint cursor mid-move" (PathPlan:936-938, 1106).

## §2 Ratified direction (owner, 2026-08-18)

Four rules. External precedent per rule in `replan-adoption-handoff-research`.

- **R1 — Seed ahead.** A re-search launched while a plan is executing starts from the first
  waypoint of the CURRENT plan the bot settles on AFTER a max-duration search completes (the
  "budget horizon"). Sync degenerate case: the current move's destination.
- **R2 — Park until the seam.** The result installs only when the bot settles on the seed
  waypoint (move-boundary adoption). The not-yet-finished moves of the old plan are the retained
  prefix — no splice code, just deferred adoption.
- **R3 — Fast-forward.** If the bot has advanced past the seam but its floor lies ON the new
  plan, adopt and let the reached-scan enter mid-plan (the existing "advance SKIPPED n steps"
  mechanism).
- **R4 — Panic stop.** If the bot has advanced past the seam and is NOT on the new plan, drop the
  result AND the old plan, stand still (WAIT), and resubmit from the live floor at rest.

Why this closes §1 geometrically: adoption at a move boundary means the bot has just entered the
seam cell — `reached` fires at FIRST TOUCH (block-exact feet cell, no center/velocity threshold;
Movement:93-95, atWaypoint:142-177) — so its momentum points INTO the plan's start cell with the
whole cell width as runway (~8-10 ticks at walk speed under drag), instead of 1 tick out of it.
A full 180° step 0 has time to take control even before any braking.

## §3 Seam selection (R1)

**Per-step costs (new).** `BlockPathPlan` stores no per-step cost today (only total `cost()`), and
`Movement` exposes no standalone cost function — re-deriving follower-side is not realistic. Add a
`float[] stepCosts` parallel array (the `floorYs` pattern) filled in `BlockPathfinder.reconstruct`
from per-edge g-deltas (`nodes.g[n] - nodes.g[p]`, rows walked pairwise at reconstruct:1497-1500);
a macro edge re-expanding into j waypoints divides its one edge cost by j (macro runs are uniform,
so uniform division is exact). Costs are already real ticks (physically-derived-costs invariant),
so no unit conversion exists anywhere in this design.

**The walk.** Candidate seams are waypoints k ≥ `waypointIndex` (the current move's destination is
the floor — the bot is already committed to it). Budget in ticks = `executor.budgetNanos() /
50_000_000L` (precedent for budget→physical conversion at exactly this layer:
AsyncWindowSearch:177-179's walk-outrun adoption tolerance). Pick the smallest k with
`sum(stepCosts[waypointIndex..k]) >= budgetTicks`, subject to:

- **Stop before commitment:** the walk never crosses a step with `commitsAcrossArrival()`
  (Parkour/DiagonalParkour/WalkOff) or a Fall deeper than `safeFallDistance` — i.e. the existing
  `stepCommitsRisk` predicate (BotNavigator:477-485). If such a step lies inside the horizon, the
  seam is the waypoint BEFORE it (its takeoff stand cell). Rationale in §7 (this is what lets the
  CAUTION hold move rather than grow).
- **Terminus cap:** the walk never passes `path.size()-1`. (At the cap this degenerates toward
  P4's existing seed, `path.floor(last)`.)
- **Sync mode:** `budgetNanos == 0` → budgetTicks 0 → k = `waypointIndex`. One rule, both modes.

The seam is carried as the plan's search start (`blockPlanStart` already exists as "this plan's
implicit step −1", PathPlan:182) — no new identity concept.

## §4 Seeding the search

**Start cell.** `PathPlan.submit(...)`'s first argument becomes `path.floor(k)` (search-native
floor, the same carry P4 uses — never a `floorOf` re-derivation) when an incumbent plan exists and
the trigger is mid-motion; `botFloor` otherwise. Per-site:

| Site | Seed |
|---|---|
| S1 fresh plan (ctor) | `botFloor` — no incumbent; the 2026-07-30 planAnchor launch gate STAYS here |
| S2 forward-slide | horizon seam (the wedge's site) |
| S3 blocked-null | `botFloor` (plan is gone) |
| S4 cascade SWAPPED | horizon seam if a block plan is still loaded, else `botFloor` |
| S5 refreshWindow consumed-plan | `botFloor` (bot at terminus, consumption is a settle event) |
| S5 refreshWindow plan-impacted | horizon seam; a prefix the impact broke is handled by §10's move-compatibility machinery (the U1 prompt trigger + the U2 seam clamp) — the original claim here, that the prefix's per-tick envelopes/Need re-verification were the safety net, was **verified FALSE for a plain wall** (a placed block across a Traverse fails no envelope — Traverse's envelope accepts standing-in-src — arms no Need, and the bot presses the wall forever) |
| S6 repairBlocked | `botFloor` (bot is holding) |
| S7 async RETRY | horizon seam if a plan is loaded, else live floor (current behavior) |
| S8 P4 pre-plan | unchanged (`path.floor(last)` → next window target) |

**`botFloor`'s other readers stay live-anchored.** Only the search start changes. The
forward-slide tolerance test, `WindowTargeting.target(..., botFloor)`, `cuboidCapBox`, and
`regionFieldFor` keep reading the live anchor — exactly the split P4 already demonstrates
(predicted start into `submit`, live `botFloor` into the cap box; PathPlan:1617/1944). The seam is
at most a few blocks ahead, inside every box these produce.

**Baseline is a SUB-RANGE fold — a correctness point.** A search seeded at waypoint k must see the
world as it will be when the bot stands at k: fold edits of steps `(lastEditedIndex+1)..k`
INCLUSIVE. Folding the whole unexecuted suffix (all `EditSnapshot.fromRemainingSteps` supports
today) would inject phantom edits from steps k+1..end that will never execute. New overload
`EditSnapshot.fromSteps(plan, fromStep, toStep)` — mechanically the existing last-to-first
latest-wins loop with the upper bound at `toStep` instead of `size()-1`.

**Mailbox plumbing.** The existing latest-wins submit (cancels any pending handle) carries over.
The P4 one-attempt guard and `quiet()` gate are keyed per window target and assume one precompute
per target; seeded boundary replans go through the normal pending slot (not the parked-preplan
slot), so the only new interaction is: a parked P4 for the same target is dropped when a seeded
boundary replan is submitted (already the rule — AsyncWindowSearch submit:99).

## §5 Adoption — the four cases (R2–R4)

> **SUPERSEDED IN PART by §11 (owner ruling 2026-08-20).** The PARK/stay-parked and
> park-on-completion machinery below stands, but every LOCATION test in cases 1–4 (settled-floor
> `startMatches`, live-floor body membership, the cursor-only `pastSeam` bit) is replaced by §11's
> execution-position trichotomy: verdicts key on WHERE THE BOT IS IN PLAN EXECUTION (cursor vs the
> seam's waypoint index), rule on the in-flight move's LANDING cell, and consummate only at that
> move's completion — no plan ever swaps mid-move. The pre-§11 geometric arms survive only in the
> degenerate no-move-in-flight regime (planless / consumed / holding at a truncated terminal). Read
> §11 first; the text below is kept for the mechanism it still owns (parking, staleness, tolerance
> formulas, the hot-entry analysis).

Adoption reuses the parked-P4 contract verbatim: results for seeded searches PARK on completion
instead of installing at the next boundary. At each settled boundary (and via `pollWhenPlanless`
when planless) run, in order:

1. **PARK** — `!seam.accepts(actualFloor)` where the seam's predicted start is `path.floor(k)`:
   result stays parked. (SpliceSeam Chebyshev; keep the budget-scaled tolerance
   `max(3, ceil(budgetSeconds×6))` — walk-outrun compensation, AsyncWindowSearch:177-180.)
2. **ADOPT** — bot settled at the seam (`settledFloor == path.floor(k)` — `onStartOrPlan`'s
   "floor IS the searched start" arm): install the plan, cursor 0. Standard window-swap
   (BotNavigator:750-760).
3. **FAST-FORWARD** — bot past the seam but `onStartOrPlan` finds its floor ON the plan
   (fluid yTol ±1 as today): install, cursor 0; the reached-scan's END→START pass enters mid-plan
   on the first steer tick ("advance SKIPPED"). Legality of the skip is guaranteed by the §4
   baseline: the skipped steps' cells were walked under the OLD plan whose edits the seed folded,
   so the world already matches. (Known inherited caveat: `planPlacedAt` vouches break-policy
   exemptions by prefix membership, not executed-place record — unchanged by this design,
   documented at BotNavigator:390-408.)
4. **PANIC** — bot past the seam and NOT on the plan: drop the parked result, null the block plan
   (status stays RUNNING), resubmit from the live `settledFloor`. The follower's existing planless
   WAIT (BotNavigator:827-845, which ZEROES `setForward` — not merely omits it) is the stop; the
   existing `pollWhenPlanless` tick-rate adoption (planAnchor-gated) is the pickup. No new halt
   machinery. The blocked-null site (S3) firing on the next boundary is harmless — the in-flight
   skip-guard (PathPlan:1343-1350) prevents a duplicate submit.

Case-4 semantics, stated plainly: the world told the bot its heading is wrong AND it overshot the
turn point; it stands still (a defined settle state, not a timer) and thinks. By construction this
requires the search to have outrun its own hard budget (scheduling slop, the 256-pop check
granularity) — a corner of a corner.

**Hot entry at the seam (owner question, 2026-08-18).** `reached` fires at FIRST TOUCH of the seam
cell (block-exact, no center/velocity threshold), so the adopted plan's step 0 is armed with the bot
at the near lip, hot. Handled, deliberately without new machinery: (a) this is byte-identical entry
state to an ordinary same-plan step transition — plans zigzag internally and every cursor advance is
a first-touch handoff, which is why the lip-margin fixes exist ("Parkour re-centres on backwards hot
entry", core 2bb6033; the guaranteed half-block run-up re-bake, 7e588f5+daf43ca); (b) the momentum
direction is the favorable one — a step 0 REVERSING travel means the entry carry points at the
run-up start, away from the gap/edge; momentum toward the lip corresponds to a same-direction step,
the ordinary flowing case; (c) with §6's swap-time input zeroing, un-steered coast from the lip
totals ~0.25 blocks (0.113 b/t × ground drag 0.546 geometric decay) — the bot drifts to a near-stop
a quarter cell in before any phase must act. **The teedUp mismatch, resolved (2026-08-18):** `reached` at
the seam evaluates `teedUp` against the OLD plan's successor, not the new step 0. Enumerated: the
only `entryReady` overrides are the fluid family (Swim/SprintSwim/StartSprintSwim/EndSprintSwim/
ExitWater/RideBubbleColumn), all `return true`; every ground move uses the default `atWaypoint`.
So the skipped consultation is VACUOUS today: a ground step 0's `entryReady` asks exactly what the
case-2 adoption gate (`currentFloor.equals(settledFloor)`) already established — including the Y
constraint a fluid old-move + fluid old-successor pair would have left untested, since the boundary
gate defers adoption until the live floor equals the seam floor — and a fluid step 0 is permissive.
Nonetheless case-2 adoption SHALL call `newPlan.movement(0).entryReady(bot, seam)` — one virtual
call on a cold path, provably always-true for the current movement set — so a future movement with
a real (directional/velocity) entry test is honored automatically; a refusal falls back to case 1
(stay parked one more boundary). **§11 re-sites this consultation from verdict time to CONSUMMATION
time** (owner ruling 2026-08-20): the bot has just completed a move and holds centered at the seam,
so the pose the gate examines is the settled one the plan's step 0 is actually framed from — a
deferred verdict consults nothing, and the gate runs when the install actually happens.

**Race: old plan consumed before the result lands** (seam near terminus). Consumption fires
`refreshWindow` → `dropParked()` + `replanBlock`; the in-flight skip-guard keeps the seeded search
alive if the target is unchanged, and the bot WAITs at the terminus until it adopts — the natural
stop-and-think at the one place it is most defensible. No change needed; noted so nobody "fixes" it.

## §6 Input hygiene at the swap

Independent of R1–R4: **every plan-install site zeroes the persistent inputs** — add
`bot.setForward(0f)` (and yaw is left alone; all other inputs are already tick-top-reset) to the
three swap blocks (boundary swap BotNavigator:750-760, planless adoption :791-802, fresh replan
:980-991). Worst case becomes "no thrust for one tick at a move boundary, momentum inward" instead
of "old thrust in the old yaw, momentum outward". This is Baritone's clearKeys-on-transition
reduced to the one input that actually persists.

Deliberately NOT in this design: resurrecting `bfca4a5`'s carry-arrest on Descend's `clear`. With
boundary adoption + swap-time zeroing, the full-cell runway makes the arrest non-load-bearing for
the handoff; whether the same-plan Traverse→Descend cursor advance still wants it is a separate
question with its own (reverted) history — re-read `bfca4a5`'s sibling audit before reopening it.

## §7 What this deletes, moves, and amends

- **DELETED: the mid-move adoption hole** — not by adding a gate but by making adoption
  seam-anchored; the boundary gate itself (BotNavigator:697) is unchanged and now actually means
  what it says for adoption.
- **MOVED: the PENDING-SEARCH CAUTION hold** (BotNavigator:1350-1362). Today: at ANY step
  transition into a committing step, hold while ANY search is pending. New: because the §3 walk
  never places a seam beyond a committing step, the only step whose outcome a pending seeded
  search can change is the committing step AT the seam. The hold becomes: *at the seam waypoint,
  if the seeded search is still pending and the next step commits risk, hold until it lands*
  (Baritone's `shouldPause` analog, scoped to one cell). Same intent, strictly narrower trigger,
  and non-committing step transitions no longer stutter under a pending search.
  **DELETED by §11 (owner ruling 2026-08-20)** — subsumed, not narrowed further: a pending seeded
  search now TRUNCATES the walked plan at the seam (the §11 terminal view), so the cursor can never
  enter seam+1 under one at all; the uniform SEAM-PAUSE hold at the truncated terminal replaces the
  committing/invalidated-next-step special case for ANY pending seeded search, and the hold is a
  centered `restHold` station-keep instead of a bare input drop.
- **AMENDED: the 2026-07-30 owner ruling** ("never plan while the bot is in motion",
  DESIGN-background-pathfinding.md §5). Its ADOPT half is strengthened (seam-anchored). Its
  LAUNCH half is superseded for incumbent-plan re-searches: a seeded search's start is a future
  settled cell, which honors the ruling's intent (the incident was about anchoring frames on
  transient cells) but not its letter. Fresh plans (S1) keep the launch gate unchanged. **This
  amendment requires explicit owner sign-off with the rest of this doc.**
- **UNCHANGED and out of scope:** the fail→hold policy (stays until movement bugs are done being
  hunted); the `!lastPlanPartial` follow-to-terminus gate; P4; anti-flap (plan-stability cost
  bias, hysteresis) — registered separately in `region-tier-deferred-work`; mining mid-break
  progress loss on swap (vanilla-consistent, wasted work not corruption).

## §8 Change inventory

| File | Change |
|---|---|
| `BlockPathfinder.reconstruct` | fill `float[] stepCosts` from per-edge g-deltas; macro edges divide uniformly |
| `BlockPathPlan` | `stepCosts` field + `stepCost(i)` accessor (mirrors `floorYs`) |
| `EditSnapshot` | `fromSteps(plan, fromStep, toStep)` overload |
| `BotNavigator` | horizon walk (uses `stepCosts` + `stepCommitsRisk`); pass seam to `PathPlan`; CAUTION hold rescoped to the seam; `setForward(0f)` at the three install sites |
| `PathPlan` | `replanBlock` seeds `submit`/sync `findPath` from the seam when incumbent+mid-motion (per-site table §4); boundary results park (pending→parked path for seeded searches); PANIC = drop parked + null plan + resubmit |
| `AsyncWindowSearch` | generalize `pollParked`'s accept/onStartOrPlan tests to boundary-seeded results; keep budget-scaled tolerance |

**§11 delta (owner ruling 2026-08-20):** `BotNavigator` additionally owns the follower-side
terminal view (`planTerminalIndex` + the `planLimit()` clamps + `refreshSeamTruncation` +
`seamPauseHold`/`SEAM-PAUSE`); `AsyncWindowSearch.pollSeededParked` computes the §11 trichotomy
(deferred verdicts + `consummateSeeded`), and `drainPending`'s post-plan-reconcile body arm probes
the in-flight move's LANDING cell and carries its matched index; `PathPlan` owns the armed
consummation (`consummationTick`) and threads the landing + execution position; `SteerControl`
gains the `terminalArrive` centered-terminal drive flag.

Perf note: nothing here touches a per-node hot path. `stepCosts` is one reconstruct-time array per
found plan. Process still applies: paired interleaved A/B on the full JMH suite (SHORT/MULTI
guards) before merge, expectation FLAT.

## §9 Verification plan

1. **Unit:** horizon-walk selection (prefix-sum; commits-step early stop; sync k=cursor; terminus
   cap; empty/one-step plans). `EditSnapshot.fromSteps` sub-range (latest-wins across the range
   boundary; phantom-edit exclusion). Adoption state machine: the four cases as a table test over
   (botFloor, seamFloor, plan membership).
2. **Course harness:** a `ReplanCourse` beside TrapdoorCourse/SwimCourse — scripted target change
   mid-Traverse on a built course, asserting (a) no adoption before the seam, (b) adoption at the
   seam with a reversing step 0 executed cleanly, (c) the fast-forward case, (d) the panic case
   (forced by delaying the result past the seam).
3. **Live:** the flagship (must stay green, byte-behavior changes expected only at replan
   boundaries) and the long flagship past (1328,76,1457) with `-MasterWorld`; sync AND async runs
   of both.
4. **The wedge itself:** re-run the manual long-flagship leg; the 07:13:52 shape (forward-slide +
   reversing plan mid-Traverse) must now park to the Traverse's completion and turn around without
   a `step FAILED`.

## §10 Edit-epoch move-compatibility — the unified design (ratified 2026-08-18)

R1's premise is a PRECONDITION, not a guarantee: "the bot settles on the seam after the search
completes" assumes the retained prefix — the steps between the bot and the seam, plus the move it is
executing — stays walkable while the search runs. A world edit landing on those steps voids it, and
as originally shipped nothing enforced it: arming a step does not verify realizability, Traverse's
validity envelope accepts standing-in-src (a wall across the step fails nothing), no Need re-arms for
a plain wall, and no timers exist — the bot pressed the new wall forever.

**The first §10 shape (same-day, superseded).** A side-mechanism — a cursor+3 prefix-validation
window, a `prefixDropArmed` drop-at-boundary arm, and blanket `atRest` gates on every live-floor
launch site — was implemented and then killed by a sync course failure: the plan-impacted replan
trigger was DEBOUNCED (~40-tick terrain recheck), so a seal at cursor+1 was detected only after the
cursor had advanced ONTO the sealed step, and the seam walk then seeded the re-search FROM the
sealed cell — parked unreachable, permanent hold. The blind spot was structural (a lookahead bolted
beside a debounced trigger), so the owner replaced the whole shape with the unified rules below
rather than patching it.

**The unified ratified design (owner, 2026-08-18, final):**

- **U1 — PROMPT TRIGGER.** On the per-tick edit-epoch advance (one cheap read/compare — the existing
  pattern, `BotNavigator.scanPlanOnEditEpoch`), run a cold cell-level test: does any edited region
  intersect the incumbent plan's REMAINING envelope — the WHOLE remainder, `cursor..end` (cold, and
  the plan is ≤~48 steps) — in a way that invalidates the SELECTED move (move-compatibility, U4)?
  If yes → fire the S5 plan-impacted replan THE SAME TICK (`PathPlan.promptImpactedReplan` →
  `refreshWindow(true)`, bypassing the 40-tick debounce for this case; the debounced recheck stays
  as the backstop for everything else, and must not double-fire — the prompt path re-baselines the
  plan-chunk snapshot and re-arms the debounce when it fires, and is itself gated once-per-breakage
  on the stored (plan, step) fact). If the edit does not intersect, or is move-compatible
  (solid→solid, décor outside the envelope), do NOTHING — this deliberately REDUCES today's replan
  noise (vine growth outside the envelope stops triggering the prompt path at all).
- **U2 — EDIT-AWARE SEAM.** The horizon seam walk never places the seam AT or PAST a
  move-invalidated step — it clamps to the last safe waypoint, exactly the way the walk already
  refuses to cross a commits-risk step (`horizonSeamWalk`'s second early-stop, reading
  `incompatibleCell` against a live grid; the instance memo re-keys on the edit epoch). Evaluated at
  replan time against the live grid, so no trigger-latency race can seed from a broken cell, in
  either pathing mode. Additionally the §7 seam CAUTION hold's trigger is EXTENDED: a bot standing
  at the seam whose NEXT step is move-invalidated holds while the seeded search is pending (a state
  test, mirroring the commits-risk arm; it reads the stored U4 fact, no per-tick scan) — this covers
  the async search still being in flight when the bot arrives.
- **U3 — ADOPTION UNCHANGED.** The §5 four cases handle everything downstream. A bot reaching the
  clamped seam before the search lands waits parked — that pause is intrinsic and ACCEPTED (the bot
  just watched its road disappear).
- **U4 — MOVE-COMPATIBILITY** is the breakage predicate (replacing the first shape's geometry-only
  rule): a cell edit invalidates a step iff the new NavType no longer supports that step's SELECTED
  movement (`BotNavigator.firstIncompatibleStep`/`incompatibleCell`, pure statics, table-tested —
  `PrefixIntegrityTest`). Ground family (Traverse/Diagonal/Ascend/Descend/Fall landings): the dest
  floor must remain standable solid — floor→fluid (water OR lava) INVALIDATES (you cannot walk on
  fluid; two carve-outs, both in the compatibility direction: a Fall whose feet cell also reads
  non-damaging fluid keeps a fluid floor — the water-cushion arrangement the planner itself prices —
  and a feet cell that currently reads climbable takes no floor verdict at all, the bot hangs on it —
  the first shape's guard, kept), a floor offering nothing at all
  invalidates; body/head cells must remain passable (a full-height wall invalidates; openables NEVER
  invalidate — the Need machinery owns doors/trapdoors/gates); solid→different-solid stays
  compatible — except a planned-MINE cell (a folded, not-yet-executed break), where any detectable
  change that defeats the mine — flooded, or an unbreakable/protected occupant swapped in —
  invalidates conservatively (became-air stays compatible: the break is already satisfied; a
  different breakable solid is undetectable without a plan-time baseline and still mines). Fluid
  family (Swim/SprintSwim/DiagonalSprintSwim/StartSprintSwim/RideBubbleColumn): planned fluid feet
  cells that became solid or air → invalidate; keep everything else compatible (the bank moves —
  Surface/ExitWater/EndSprintSwim — take no verdict). Climb: the climbable cell no longer climbable
  → invalidate (walled column, or a vanished mid-shaft rung with nothing below; a dismount stand
  over real ground stays compatible). Every other movement: NO invalidation — be conservative in the
  DIRECTION of compatibility (do not invent invalidations beyond these).
- **U5 — THE ONE EMERGENCY.** No safe waypoint at all (the CURRENT move's cells are invalidated) →
  cut inputs NOW, drop the plan (the existing `dropWalkedPlan`/`dropBlockPlan` idiom — the pending
  seeded search + parked result die with it), wait `atRest`, replan from rest via the rest-gated
  planless pickup. This and PANIC (§5 case 4, null-only) are the ONLY `atRest` consumers. Companion
  re-anchor (kept from the first shape): **rest is a settle event** for a planless bot — wherever an
  input-zeroed bot stops becomes `settledFloor`, so the boundary machinery (and the S3 relaunch,
  `restForLaunch`) can fire from a cell that was never a completed waypoint, in both modes.
- **U6 — RETIRED.** The first shape's side-mechanism is gone: `prefixDropArmed`, the boundary-drop
  arm, the null-now-on-LATER-prefix rule, and the `atRest` gates on the S1 fresh-replan and repair
  sites (both reverted to their pre-existing planAnchor-only gates — the owner explicitly rejected
  the FOLLOW stop-start cadence a blanket rest gate produced). KEPT: `BotSteering.atRest()` + its
  derived `REST_HSPEED` epsilon, the planless pickup's rest gate + rest-drain + the planless
  settledFloor re-anchor, `dropBlockPlan`, PANIC-as-null-only, and all §1–§9 machinery.

**Detection cost** is unchanged from the first shape's budget: ONE monotone `NavGridUpdater.editEpoch`
read + compare per steer tick; only on an epoch advance does the (cold) U4 scan run.

**Why not servo-to-the-seed instead** (the considered alternative: keep the plan and steer the bot to
the pending search's seed cell): rejected — the planless flow already re-anchors CONTINUOUSLY until
rest (WAIT zeroes inputs every tick; the settle re-anchor tracks wherever the bot actually stops; the
relaunch seeds from that true cell), whereas a servo toward a seed chosen before the edit drives the
bot toward a premise already known broken, with new steering machinery and no better anchor at the
end of it.

**Proof tiles** (ReplanCourse): `prefixseal` (U1+U2 — same-tick prompt, clamped-seam ADOPT, NO drop,
identical shape in both pathing modes), `currentseal` (U5), `currentseal-on-ice` (U5 + the kept rest
gate — the install must wait out the slide).

## §11 Seam as execution edge (owner-ratified 2026-08-20)

**The ruling, verbatim intent:** "The seam shouldn't be about the bot's LOCATION, but about where it
is in the plan execution. [...] The seam becomes the razor-thin edge between one movement completing
and the next starting. [...] we probably never want to swap a plan mid-move. Plans should swap
between moves. One move completes and instead of picking up with the OLD plan's next move we pick up
with the NEW plan's next move. [...] Once a re-plan has completed [...] we should be able to
truncate the old plan and say 'the plan will now END when the current movement ends' — which should
drive the bot to the center of a cell before adopting the new plan."

**What convicted the location seam** (the 2026-08-20 run-2 and run-6 convictions, on top of the
run-5 stale-frame Pillar wedge §5/R3 had already patched): verdicts keyed on the bot's INSTANTANEOUS
floor cell install plans under a move still in flight — the settled-floor equality gate approximates
"between moves" only up to the tick quantization, and every arm of the §5 pump (startMatches on a
mid-move floor, body membership of a cell the bot is passing THROUGH rather than landing ON,
`pastSeam` as a bare cursor bit) inherited that approximation. §11 removes the approximation instead
of patching its corners.

### The model

- **Truncation is a follower-side terminal VIEW, never a copied plan.** `BotNavigator.planTerminalIndex`
  (default `MAX_VALUE`) caps every "how far does execution run" read via `planLimit()`;
  `BlockPathPlan` stays immutable and the swap detector's identity test stays sound. Derived
  state-based each tick (`refreshSeamTruncation`): an ARMED verdict imposes its terminal exactly; a
  pending/parked seeded search truncates at its seam, latched at `max(seam, cursor)` once per
  episode (never behind the in-flight move, never re-extended by the cursor's own advance); a dead
  seam state restores `MAX_VALUE`. The §3 walk, the §10 U1 scan, the reached loop, the consumed
  tests, the corner-blend look-ahead and the P4 preplan all read the terminal view.
- **The verdict trichotomy** (`AsyncWindowSearch.pollSeededParked`, cursor vs the seam's waypoint
  index, identity-guarded on the plan the walk chose the seam on):
  - **before-seam** (`cursor < seamIndex`): park, keep executing the old plan — regardless of
    geometry (subsumes the 2026-08-18 reversal ruling structurally: pre-seam NEVER installs).
  - **at-seam** (`cursor == seamIndex` — the move ENDING at the seam is in flight): deferred ADOPT —
    truncate at this move, consummate at its completion, settled at the seam.
  - **beyond** (`cursor > seamIndex`): the in-flight move's LANDING floor (`seamPlan.floor(cursor)`)
    is ruled against the new plan — the seam itself → deferred ADOPT; a body cell (fluid yTol ±1) →
    deferred FAST-FORWARD carrying the matched index (the install seed starts past the executed
    prefix); neither → deferred PANIC. The budget-scaled walk-outrun Chebyshev box survives as a
    SANITY bound on the live floor (never a long-range PANIC).
  - **degenerate** (no move in flight: planless via `pollWhenPlanless`, a consumed incumbent, or
    holding at a truncated terminal): the verdict IS the consummation, immediately, on the settled
    live floor — the pre-§11 geometric arms, in the one regime where they are exact.
- **Consummation at completion** (`PathPlan.consummationTick`): a deferred verdict arms
  (`armedVerdict`/`armedPlan`/`armedTerminal`/`armedMatched`) and owns every boundary until
  `seamCursor > armedTerminal` — the reached-advance is the sole completion authority — then
  installs (ADOPT/FAST-FORWARD through the existing install block + `installSeed`) or drops (PANIC:
  null-only; the §10 rest-gated planless machinery owns the relaunch). State-based disarms on every
  premise death (plan swapped/dropped, parked result gone, window target moved). The invariant is
  absolute: NO plan swap while a move is in flight, and the new plan's step-0 frame equals the bot's
  actual settled cell at install. The step-0 `entryReady` gate is consulted HERE (re-sited from
  verdict time — the bot is settled and centered, the pose the plan actually starts from).
- **Centered terminal.** While the terminal move executes, `SteerControl.terminalArrive` routes the
  land drive onto the step-target ARRIVE (`arriveOnStep` — cruise-capped easing, hazard near-face
  branch; the `stepGateArmed` plumbing discipline), and the terminal-view clamp kills the
  corner-blend look-ahead, so the move ends squared-up at the cell centre. After completion, the
  SEAM-PAUSE hold (`seamPauseHold`) station-keeps on the terminal waypoint centre via `restHold`
  (§2.6 scope: grounded/!inWater/!onClimbable; `clingHold` keeps climbable/fluid) until
  consummation — drift (ice) is actively re-centred, which is what re-establishes the settled-floor
  equality the consummation boundary needs.
- **PANIC = finish the committed move.** The parked slot drops at verdict time (the result answers a
  dead premise); the PLAN drops only at consummation — the bot finishes its committed move, ends
  centered at ITS landing, and the existing rest-gated planless pickup relaunches from there. The
  old drop-immediately-mid-move shape is gone.
- **The second mouth** (`drainPending`'s post-plan reconcile, the non-seeded boundary-result arm):
  the body-membership probe is the in-flight move's LANDING cell (live floor only when planless),
  and a body hit carries `resultMatchedIndex` so the install seed enters PAST the executed prefix —
  the cursor-0 mid-plan install is gone from this arm too.
- **The old CAUTION hold is deleted** (see §7): a pending seeded search truncates the plan at the
  seam, so the cursor can never enter seam+1 under one; the uniform hold-at-seam covers ANY pending
  seeded search, committing next step or not.

### §11.1 The SEAM-PAUSE diagnostic (§8's successor)

`driveState = "SEAM-PAUSE"` while holding at the truncated terminal; per-episode tick counter
(`seamPauseTicks`, keyed on the terminal index); ONE INFO line at consummation —
`[Orebit] seam-pause: held Nt at seam (x,y,z) waiting for the seeded search` — logged at the swap
site, silent on zero-tick consummations and on episodes that die without an install. Frequent or
long pauses are the SEAM-PADDING TUNING SIGNAL: the §3 walk's budget→distance conversion
under-reached (the search outlives the bot's walk to the seam), so the padding — not the hold — is
what wants tuning. Episode reads: seam-seed → seam-park → seam-verdict → [seam-pause Nt] →
seam-adopt/seam-consummate.

### §11.2 Test surface

`SeamAdoptionTest` rewritten to the trichotomy (the pre-§11 location pins are deliberately
superseded — each rewritten case cites this ruling); `HorizonSeamWalkTest` gains the terminal-view
cases; `SeamInstallSeedTest` gains the PANIC null-install case; `ReplanCourse`'s reversal trial is
retightened to the now-deterministic seam ADOPT; `prefixseal`'s park-at-seam pause is the SEAM-PAUSE
hold. `planBodyIndex`/`startMatches`/stale-target-drop/`parkSeededResult` and the §5 install
plumbing (`blockPlanStart`/`adoptedMatchedIndex`/`installSeed`) are unchanged.
