# DESIGN — async step-transition safety (the "mid-air adoption" ruling, resolved)

Status: IMPLEMENTED 2026-07-31 (owner-delegated: "brainstorm and implement the best solution…
really think hard about how async could break it", plus the mid-design refinement that routine
window slides / P4 pre-plans deserve caution too). Grounded in a 5-reader recon incl. a dedicated
adversarial pass (8 breakage scenarios, §4). Supersedes the pending-rulings item "mid-air adoption".

## §1 The problem

`grounded` at search-SUBMIT time says nothing about the world at ADOPTION or at STEP COMMITMENT.
With `pathing.asyncSearchBudgetMs` raised to 500–1000ms and long windows, the gap between "search
launched" and "result lands" is 10–20+ ticks of the bot executing the OLD plan — including
launching committed jumps. Three distinct danger windows: (i) step transitions while ballistic,
(ii) adoption while ballistic, (iii) committing to danger while a search is outstanding.

## §2 The convicted step-transition bug (deterministic repro on the owner's world)

Log conviction (owner world "Autotest Master - Copy" + owner config, `-Start 29,143,198
-Goal 34,144,194`): the vine-topout Ascend steers by HELD JUMP; the tick its feet touched the
takeoff cell, the follower's reached-scan fired (uncommitted moves' default `reached` is an
UNGATED feet-block match — Movement.java) and the transition block abandoned the Ascend mid-phase
("ABANDONED Ascend … reached fired before done") while the held jump had just launched a fresh
0.42 vertical hop. The next step's Parkour plan was built AIRBORNE ("PLAN Parkour …
grounded=false"); its grounded runup never ran; the "gap-4 jump" executed as a standing vertical
hop with air drift; the envelope correctly failed it → fail→HOLD on the forest floor.

**Fix (the existing seam, per the adversarial guidance): `Ascend.reached` adopts the
grounded-gated committed-move idiom.** Completing a jump-up is inherently a grounded event; the
transition then fires on the first grounded steer tick BEFORE that tick's drive can re-press jump.
Cost: the ballistic ticks only (the scan runs pre-drive, so no bunny-hop). Deliberately NOT a
global anchor gate on transitions: §4-E1 (a Traverse→Fall handoff NEEDS the airborne transition —
Fall's drop-control is what reaches the anchor) and §4-E2 (a mid-hop bot transiently reading
`onClimbable` in canopy defeats any planAnchor-based gate) both break the general form.

## §3 Pending-search caution (the owner's "play it safe-ish", generalized per the slide refinement)

**Mechanism** (all follower-side, tick-rate, state-based — no timers):
- `AsyncWindowSearch` records per outstanding search: `pendingSuspect` — true when the launch site
  implies the CURRENT plan may be invalid: terrain-impacted refresh, repair resubmit, blocked-null,
  and RETRY resubmits INHERIT the drained search's flag (`lastDrainSuspect`) so a retried suspect
  search stays suspect (resolves §4's two-horned "what is in-flight": the flag lives with the
  handle until the drain classifies it, which covers finished-but-undrained results through the
  touchdown window). Routine: fresh plan, forward-slide, cascade re-derive, P4 pre-plan.
  `BotNavigator`'s refresh site now evaluates `planImpacted()` INDEPENDENTLY of `consumed` (the
  old short-circuit laundered consumed+impacted as routine — §4-E6) and passes it to
  `refreshWindow(boolean suspect)`.
- Public accessors: `PathPlan.searchPending()` (ANY outstanding search — in flight or
  finished-but-undrained; a PARKED pre-plan does not count) and `suspectSearchPending()`.
- **The caution gate** sits at the ONE step-transition site (BotNavigator's
  `waypointIndex != activePlanStep` block): when `searchPending()` AND the next step
  `stepCommitsRisk` (a `commitsAcrossArrival` move — the parkour family — OR a Fall deeper than
  `caps.safeFallDistance()`, computed from waypoint frames; Fall deliberately carries no
  commitsAcrossArrival, its reached must stay ungated for buoyant landings) AND the bot is in a
  stable medium AND NOT on a damaging floor → the transition defers: zero forward, stand at the
  just-settled anchor, log once per step (VERBOSE, labeled SUSPECT/routine).
  Safe steps are never deferred — the async pipeline keeps walking through routine searches.
- **Why keyed on ANY search, not just suspect** (owner refinement): even a routine window slide
  can change the route's direction; a committed jump launched into that unresolved future can land
  the bot mid-air off whatever plan gets adopted. The deferral is bounded by the search budget:
  the drain runs at this very anchor on the next boundary tick, and a STANDING bot always
  seam-accepts — which is also what breaks §4-E4's retry storm.
- **Safe-step drift reconciliation** (the "4-5 blocks off-path at adoption" half): the adoption
  seam's OUTER box now scales with the configured budget — `max(3, ceil(budgetSec × 6.0))`
  (sprint 5.6 b/s + margin) in `AsyncWindowSearch.drainPending` — so a bot that legitimately
  walked the shared corridor during a long search is still CONSIDERED; adoption still requires
  the exact on-plan membership (`onStartOrPlan`), so the widened box only lets the existing
  mid-plan-entry mechanism do its job, else the honest RETRY-from-actual-floor.

## §4 Adversarial findings and their dispositions

- **E1 anchor-deferral starves Traverse→Fall** → global transition gate REJECTED; fix scoped to
  Ascend's reached (§2). The general airborne-transition class (uncommitted reached firing
  mid-ballistic into a next-step build) remains OPEN — each member needs its own physics argument.
- **E2 onClimbable one-tick false anchor mid-hop** → no planAnchor-based transition gate; grounded
  only, per-move.
- **E3 caution-hold on magma/lava kills the bot** → the gate carries `!onDamagingFloor()` (the
  arrival gate's own keep-moving rule) and a stable-medium requirement; on hazard it proceeds.
- **E4 walk-outruns-the-seam retry storm at big budgets** → the standing caution breaks it for
  dangerous steps; the budget-scaled seam box reconciles safe-step drift (§3).
- **E5 done-but-undrained suspect escapes at touchdown** → the flag lives until the drain
  classifies (pending stays non-null), so caution covers that window.
- **E6 consumed||impacted short-circuit launders suspect** → impacted evaluated independently.
- **E7 fail→HOLD × caution deadlock/log-masking** → caution sits in the transition block; a held
  runner returns from the fail branch BEFORE reaching it — no interaction; caution has its own
  log line. The pre-existing HOLD-never-drains latch is unchanged (deliberate owner policy).
- **E8 adoption-gating recreates the parked-veto wedge / loses paid boundary results** → adoption
  is NOT gated at all; caution gates only step transitions.

## §5 Non-goals / open (each deliberate)

1. The general uncommitted-airborne-transition class (E1's flip side) — per-move physics work.
2. Suspect vs routine currently differ only in logging/telemetry; a stronger suspect response
   (e.g. also deferring edits) is future headroom the plumbing now supports.
3. The E4 storm for LONG safe-step stretches at 1000ms+ budgets is mitigated (seam scaling), not
   eliminated; the true fix if it ever bites is the planless-WAIT terminus behavior that already
   bounds it.
4. Fail→HOLD's ambient-refresh leak (documented, owner-deprioritized) is unchanged.
5. Sync mode: `searchPending()` is always false — searches resolve within their tick; no doubt
   window exists, byte-identical behavior.

## §6 Verification

Suite green. Oracles: the deterministic owner-world repro (vine approach → gap-4, was FAIL with
the bot holding on the forest floor) must PASS; the `/tp`-equivalent control (start on the takeoff
cell) must stay PASS. Follower-side only — no search-emission changes, no JMH exposure.
