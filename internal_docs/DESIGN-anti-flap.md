# DESIGN — Anti-flap: defer the blame until the closest launch also floods

Status: **DRAFT — owner-ratified in substance (HANDOFF item #1 + the §3 correction, 2026-07-24),
pending review as part of the region-tier next-work SET (see `ROADMAP-region-nextwork.md`).**
Relationships: this is the **WHEN-to-blame** half of the flood→proof story; the **WHAT-to-blame**
half is `DESIGN-virtual-start-fragment.md` (#2/#3, from-fragment/approach scope). It rides the
forward-advance machinery already shipped as `DESIGN-rolling-skeleton.md` increment A. It hands its
terminal proof to `DESIGN-boxed-in-reachability.md` (#4/#5), and its durable-persistence question is
resolved there. All `file:line` cites are the `orebit-mc121-wt` worktree at the current uncommitted
stack.

---

> **[OWNER CLARIFICATION 2026-07-25 — the authoritative framing; supersedes the §2/§4 elaboration
> below and the §6 "journey-scoped (option C)" lean.]**
>
> **Current (as-should-be, pre-#1) blame rule:**
> - **Heap-exhaust** → invalidate the *first non-reached crossing* (with #3: keyed "coming from the
>   prior crossing"). Durable proof.
> - **No exhaust, no flood** → a path to the goal was found. Nothing to do.
> - **Flood** → a partial is returned; then:
>   - **(a)** partial *starts with an irreversible move* → invalidate the first non-reached crossing
>     ("not reachable under budget"). **Durable.**
>   - **(b)** partial *gets no closer to the goal than the current cell* → invalidate the first
>     non-reached crossing ("not reachable under budget"). **Durable.**
>   - **(c)** otherwise → follow the partial to its terminus and search again.
>
> **#1 (anti-flap) adds ONE bullet, evaluated BEFORE (a/b/c):** on a flood-partial, first check whether
> the block-tier path we were already following is still functional to continue. If so, **keep following
> the known-good path** (no reroute, no invalidate). Only with no known-good path to continue do we fall
> through to (a/b/c). *Rationale:* continuing a known-good path beats rerouting to a possibly-bad one.
>
> This **strictly reduces how often we invalidate**; it does NOT change that "flood → invalidate" is a
> legitimate under-budget proof — the durable rows (a)/(b) already write. So **flood-invalidation is
> durable, not journey-scoped** (correcting §6's earlier lean). The §2 "hold-the-far-target /
> closest-launch-adjacency" narrative is the wordier form of the same behavior: the closest launch
> emerges naturally as the bot exhausts the known-good path before falling through to (a/b/c). Final
> persistence scope per the owner's RS-1 ruling (pending). The rest of this doc is context; the rule
> above is what to build.

## §0. TL;DR

A slid-window region/block search that **floods** (expands its whole budget without advancing toward
the target) proves only "**the target is unreachable from *this* launch position**". Today the code
launders that budget artifact into an immediate blame + skeleton re-derive on the **first tick**
(`resultStatus` → `blockedGeneration++` → `repairBlocked`, `PathPlan.java:972`, `:1351`). From a
launch position far short of the target, that blame is unproven, and the re-derive it triggers is the
**route flap** (the 615-search orbit of region `(4,8,-5)`, HANDOFF §6; the cliff give-up,
virtual-start §1a).

**Anti-flap defers the blame.** On a flood, hold the flooding **far target** fixed, keep walking the
**last non-flooded plan** (the committed skeleton the bot is already on) forward one region, and
**re-attempt the same far target from each successively-closer launch position**. Only when the bot
stands at the **closest reachable position** (adjacent to the target, or a position from which no
forward progress toward it is possible at all) and the target **still floods** is the flood *terminal*
— and only then does it become the persistable, `(from-fragment, approach)`-scoped proof of §8.

Everything else in the loop is unchanged: the search algorithm, the commit hysteresis, the blame
machinery — anti-flap adds one gate (defer blame while not-yet-closest) and one held value (the far
target), both cold-path.

---

## §1. The pathology — a flood is position-relative, blame is not

**Fixture:** the `(68,64,-76)` orbit repro (HANDOFF §6) — no-capability bot, clean cliff master,
`syncSearchBudgetNodes = 40000`, cliff goal. The bot orbits region `(4,8,-5)`, never converging. Also
the cliff corner give-up (virtual-start §1a): the same search that FLOODS at the 10k default FINDS a
79-waypoint route at 40k — the give-up is a *budget* artifact laundered into a *reachability* claim.

**The mechanism, verified in code:**

1. A window search toward a far target floods. `BlockPathfinder` (`:926-965`) detects a budget-hit
   with `(startGeo − commitGeo) ≤ PARTIAL_MIN_PROGRESS`, **suppresses the partial → returns null**;
   `resultStatus` (`:972`) classifies null-with->1-expansion as BLOCKED, bumps `blockedGeneration`,
   and snapshots the blame inputs.
2. On the **very next** tick `repairBlocked` (`:1351`) → `blockedHop` (`:1215`) → `blameHop` (`:1283`)
   → `hier.onBlocked(...)` (`HierarchicalRegionPlan.java:669`) condemns the first unrealized window
   crossing and **re-derives the skeleton**.
3. The re-derived skeleton is a different near-equal route; the bot half-follows it, floods again from
   another too-far launch, blames again → **orbit**.

**The root defect:** the flood is a fact about the **(launch position, target)** pair, but the blame
condemns the **crossing** (globally, or at best V-scoped) on the strength of a launch that may be many
regions short of the target. Two independent scopings are wrong:

- **Position scope (this doc, #1).** The proof supports only "unreachable *from here*"; it says nothing
  about a launch *adjacent* to the target.
- **Approach scope (`DESIGN-virtual-start-fragment.md` §4, #3).** The proof condemns only the FROM
  fragment/approach, never the crossing for every approach (the A==G collapse bug).

`DESIGN-virtual-start-fragment.md` fixes the *approach* scope and gives the flood a **structurally
blameable** first crossing (`S → A → … → V`). Anti-flap fixes the *position* scope: it decides **when**
that blameable crossing is actually blamed.

---

## §2. The mechanism — hold the target, walk the last good plan, blame only at the closest

Four elements; §3 says which exist and which are added.

1. **Hold the far target.** When a window search toward far target `T` floods, remember `T` (its
   skeleton index / portal cell) as the **held anti-flap target** on the driver. Subsequent re-probes
   aim at the *same* `T` — not the per-position farthest-occupiable step `WindowTargeting.target()`
   re-picks today (`WindowTargeting.java:91`). Holding `T` is what lets the driver ask "am I adjacent
   to *it* yet".
2. **Retain the last non-flooded plan.** The bot keeps walking the plan it is already committed to —
   the near-window plan toward the committed skeleton, which does **not** flood because it aims at a
   nearby, reachable portal. This is the rolling skeleton's forward-slide (EXTENDED verdict,
   `stepCascade` `:682-701`) — the live `blockPlan` is retained, the committed cursor advances one
   region per crossing. Anti-flap does **not** re-derive on the flood (the SWAPPED path
   `:702-713`/`resetWindow` is *not* taken).
3. **Re-attempt `T` from each closer launch.** At each committed advance the bot is one region closer
   to `T`; re-probe `T` from `botFloor`. A probe that now FINDS clears the held target — the flood was
   a distance/budget artifact, no blame, the bot continues (this is the common, correct outcome, and
   it is exactly the "found at 40k / a closer launch" existence proof of §1).
4. **Blame only at the closest reachable position.** Fire the terminal blame (`repairBlocked` /
   `onBlocked`) only when the launch is **closest** to `T` (§4) and `T` still floods. That flood is
   *terminal*: it is the §8 SCOPED-PROOF, keyed `(from-fragment, approach)` — and, once #4 lands,
   cross-checked against the reverse-Dijkstra infinite-cost set.

**Why this kills the flap.** The re-derive that flips the route (element 2's SWAPPED path) fires only
on a *terminal* flood now. While the bot is short of `T`, there is no re-derive, so no route flip; the
bot rides its committed skeleton to the closest launch, and only there does the region tier grow the
skeleton around the obstacle. Non-convergence is impossible because either a closer launch finds `T`
(done) or the bot reaches the closest launch and the flood becomes a real, scoped proof that the
region tier reroutes around exactly once.

---

## §3. Code reality — what exists, what is added

From the forensic (all `file:line` verified):

**Exists (reuse):**
- The single re-search funnel `PathPlan.replanBlock()` (`:869`) and its forward-slide commit loop
  (`onBotMoved` `:642`, advancing `committedIndex`/`windowStart` toward the tail).
- Rich forward-progress state: `committedIndex`/`windowStart`/`windowTargetStep` (`:236,235,273`),
  `forwardIndexOf(botFloor)` (`:1518`, alloc-free bot→skeleton-step resolution), `botFloor`, and the
  BLOCKED snapshot `blockedStartFloor`/`blockedWindowStart`/`blockedTargetStep` (`:1047-1049`).
- Plan retention: the EXTENDED verdict already **keeps** the live `blockPlan` (no reset) shifting only
  cursors; FOLLOW-TO-TERMINUS (`onBotMoved:623`, gated `!lastPlanPartial`) already **defers sliding**
  while a plan is a PARTIAL. Anti-flap generalizes "defer" from *sliding* to *blame*.
- Launch-position awareness in blame: `blameHop` already has a **start-position blind spot**
  (`:1289-1306`) — hops ending at/before the last window step in the start's own region are
  unblamable. Anti-flap makes the *whole* blame position-gated, not just the start region.

**Added (the delta):**
- **A held anti-flap target `T`** on `PathPlan` — set on the first flood toward a far step, cleared on
  any FOUND probe of `T` or a genuine skeleton SWAP. (Not a timer; a latched target, cleared by
  success or by structural change.)
- **A blame-suppression gate** in `resultStatus`/`onBotMoved`: while `!closestTo(T)` (§4), a flood does
  **not** bump `blockedGeneration` / call `repairBlocked`; it instead advances the committed cursor
  along the retained skeleton and re-arms the probe. This is the one behavioral addition.
- **The closest-position test** `closestTo(T)` (§4).

No change to the search algorithm, the commit hysteresis, or the blame keys.

---

## §4. "Closest reachable position" — the gate, and why it always bottoms out

`closestTo(T)` is true when **either**:

- **(a) Adjacency.** `forwardIndexOf(botFloor)` is within `ANTIFLAP_ADJ` (= 1 region) of `T`'s skeleton
  index — the bot stands in the region immediately before `T`. A flood from an adjacent launch cannot
  be blamed on distance; it is a real local block.
- **(b) No-forward-progress bottom.** The bot cannot advance along the retained skeleton toward `T` at
  all — the *near* window step (not just `T`) also floods, so there is no closer launch to reach. The
  current position **is** the closest reachable, by construction, and blame fires here.

Case (b) is the termination guarantee: anti-flap never loops. Each non-terminal flood either (i) is
cleared by a closer FOUND probe, or (ii) advances the bot at least one region toward `T` (element 2),
strictly decreasing `T − forwardIndexOf(botFloor)` until (a) holds; and if advancement itself is
blocked, (b) fires immediately. There is no state in which the bot re-probes `T` from the *same*
launch twice without either advancing or blaming — so no orbit, no livelock, no arbitrary retry
counter (honoring the no-arbitrary-timers rule; the loop is bounded by the strictly-decreasing
region-distance, not a tick budget).

**Interaction with async.** A re-probe of `T` rides the planner pool exactly as every `replanBlock`
does today; the bot keeps walking the retained plan until seam-gated adoption. If adoption starves all
the way to the closest launch, (a)/(b) take over — the retained plan is the safe fallback, no new
waiting state.

---

## §5. Composition with the rolling skeleton and the virtual start

- **Rolling skeleton (shipped, increment A)** provides the forward-advance: the committed cursor slides
  and the L0 skeleton extends per crossing, keeping the live block plan (EXTENDED). Anti-flap **is** the
  rolling forward-progression *with the flooding far target held and blame deferred* until the closest
  launch. In one line: rolling re-picks the window target each crossing; anti-flap additionally pins the
  *flooding* target across those crossings so it can tell when the bot is finally adjacent to it.
- **Virtual start (#2/#3)** supplies the vocabulary anti-flap blames *into*: the terminal flood's
  proof is `(A → V | from the closest-launch approach)`, approach-conditioned per virtual-start §4.
  Anti-flap decides the *timing* (closest-only); virtual-start decides the *key* (from-fragment,
  approach). They are orthogonal and both required: without anti-flap the from-fragment proof still
  fires too early (flap); without from-fragment keys the closest-launch proof still over-condemns
  (A==G collapse). The SET preamble sequences them (virtual-start increment 1 first — it makes the
  blame *structurally correct*; anti-flap second — it makes the blame *timely*).

---

## §6. The persistence-scope decision — FLAG FOR OWNER

**The tension (verified).** The evidence model (`DESIGN-persisted-invalidation-memory.md`;
`invalidation-evidence-model` memory) persists **realized-evidence rows only** and **start-region
scopes** a blame whose FROM == the failing search's own start region (`onBlocked:688`, the ravine
carve-out; PROV_ESCALATION is session-only). A terminal anti-flap flood **realizes nothing** (that is
what "flood" means), and it is launched **from** the closest position — so its FROM == the start
region **by construction**. Under today's ratified model it therefore persists to the per-plan
blacklist **only, never to durable memory**. The owner's §8 ruling ("flooding → proof → persist is
UNCHANGED") and the ratified evidence model **collide** exactly here. This is an owner-level call.

**Options:**

- **(A) Journey-scoped only.** The terminal flood invalidates the scoped edge for the *current journey*
  (per-plan blacklist), fixes the flap, and does **not** persist. Aligns with the ratified
  realized-evidence-only model; contradicts the literal §8 "persist" wording. On restart the bot
  re-derives the same reroute cheaply (the skeleton growth is fast once the edge is dead in-journey).
- **(B) New negative-reachability provenance.** Add a `PROV_NEG_REACH` row kind keyed at
  `(from-fragment, caps-axis)` that **overrides** the start-region exclusion and persists. Honors §8
  literally; adds a persisted class the evidence model currently forbids *on principle*, and a
  restart-oracle risk (a mis-scoped negative row is a permanent cage — the `-KeepWorld` PROOF-row
  escalation 3→4→5 already observed, HANDOFF §6).
- **(C) [RECOMMENDED] Journey-scoped flood + durable proof from #4.** The anti-flap flood is
  journey-scoped (option A) — it is a *budget* artifact and stays in-journey. **Durable** negative
  reachability comes only from #4's **structural** reverse-Dijkstra proof (a region provably outside
  the goal's caps-conditioned connected component — not a budget artifact, sound by construction). So
  the flood fixes the *flap* now (in-journey), and #4 supplies the *persistable* world knowledge when a
  region is genuinely disconnected. This keeps the evidence model's "no speculative persist" principle
  intact **and** honors §8's "the flood is a valid proof" — the flood *is* valid, it is simply consumed
  in-journey unless #4 independently upgrades the same region to a structural INFINITE.

Recommendation **(C)** — it is the only option that satisfies both the §8 ruling and the ratified
evidence model, and it makes the #1↔#4 division of labor crisp: **#1 = timely in-journey reroute,
#4 = the durable proof**. Decide before implementation; it sets whether anti-flap touches
`RegionCrossingMemory` at all (under C it does not — blacklist-only, like virtual-start increment 1's
R3 leaning).

---

## §7. Invariants

- **AF-1 (position scope).** A flood blames only from the closest reachable launch (§4); a
  non-terminal flood persists nothing and advances.
- **AF-2 (termination).** Every non-terminal flood strictly decreases `T − forwardIndexOf(botFloor)`
  or fires case (b); no launch re-probes `T` twice without advancing or blaming. (The orbit is
  structurally impossible.)
- **AF-3 (no re-derive on non-terminal flood).** The SWAPPED/`resetWindow` path fires only on a
  terminal flood (or the existing deviation/flood-widen/top-collapse triggers) — never on a deferred
  flood. This is the flap fix, and it upholds rolling INV-1 (prefix stability).
- **AF-4 (honest give-up preserved).** A truly boxed-in goal still terminates: case (b) fires at the
  closest launch, the scoped proof is recorded, the region tier re-derives, and when every approach is
  eliminated the region-tier heap exhausts → BLOCKED (the wall repro's honest FAIL, unchanged — §8
  INV-5). Anti-flap must not turn a give-up into a loop (the wall repro's 0-repeat-blames oracle).
- **AF-5 (journey scope of the flood proof).** Under the recommended option C, no anti-flap flood row
  reaches durable `RegionCrossingMemory`; byte-verify absent from shards on the `-KeepWorld` oracle.

---

## §8. Increments + test plan

- **Increment 1 — the held target + blame-defer gate + closest test (blacklist-only, option C).**
  - Add the held `T`, the `closestTo(T)` gate (a/b), the suppression of `blockedGeneration++`/
    `repairBlocked` while `!closestTo(T)`, and the advance-along-retained-skeleton fallback.
  - **Acceptance (the honest one):** the `(68,64,-76)` orbit repro **converges** — the 615-search
    orbit of region `(4,8,-5)` collapses to a committed reroute (a stable skeleton; per-window searches
    small), arrival or an honest give-up, **no repeat blames**. Reproduce first (HANDOFF §6 setup:
    no-capa, clean cliff master, `syncSearchBudgetNodes=40000`, cliff goal), then fix.
  - **Cliff give-up (virtual-start §1a):** the `(70,63,-68) → (77,72,-78)` fixture rerouted at the
    default 10k budget (jointly with virtual-start increment 1), **not** brute-forced at 40k.
  - **Regression:** wall repro budget-FAIL semantics UNCHANGED (honest give-up, 0 repeat blames —
    AF-4); `-KeepWorld` oracle PASS (no anti-flap rows on disk — AF-5); full suite green.
- **Increment 2 — (only if owner picks option B) the `PROV_NEG_REACH` persisted row.** Gated on the §6
  decision; carries its own restart-oracle repro. Under the recommended C this increment does not exist
  (durability is #4's job).

**Headless seams:** the closest-position gate is unit-testable on a synthetic skeleton
(`RegionGrid.headless` idiom) — drive a bot toward a flooding held target, assert no blame until
adjacency/no-progress, then exactly one scoped blame; assert AF-2's strictly-decreasing distance.

---

## §9. Perf accounting

Cold-path only. The held `T` is one field; `closestTo` is `forwardIndexOf` (already alloc-free) + one
int compare; the deferral *removes* work (a suppressed flood does **not** run `repairBlocked` or the
re-derive it triggers). The re-probe of `T` is a `replanBlock` the code already runs — anti-flap does
not add searches, it *retimes* them (aim at the held `T` instead of a re-picked step) and *removes* the
flap's redundant re-derives (48 of longrun-7's 76 healthy replans were tail-clamped same-target
re-aims; the flap adds far more on the orbit repro). JMH search benchmarks unaffected by construction
(same `findPath`, different scheduling). The region-tier headless suite is the gate; expect the orbit
repro's search *count* to drop by orders of magnitude — measure, don't assume.

---

## §10. Decisions log + For ratification

**Decisions:**
- **D1** — A flood is position-relative; blame is deferred to the closest reachable launch (§2/§4).
- **D2** — Hold the flooding far target across launches; retain the last non-flooded plan and advance
  via the existing rolling forward-slide; re-derive only on a terminal flood (§2, AF-3).
- **D3** — `closestTo(T)` = adjacency (≤1 region) OR no-forward-progress bottom; the loop terminates on
  a strictly-decreasing region-distance, not a timer (§4, AF-2).
- **D4** — Composition: #1 sets *when* to blame (position), virtual-start #2/#3 sets *what/how* to key
  it (from-fragment/approach); sequenced virtual-start-first (§5).

**For ratification (genuinely owner-level):**
- **R1 (the load-bearing one) — persistence scope (§6): A vs B vs C.** Recommend **C** (journey-scoped
  flood; durable negative reachability is #4's structural proof). This decides whether anti-flap touches
  `RegionCrossingMemory` at all.
- **R2 — `ANTIFLAP_ADJ` = 1 region** (adjacency radius). 1 is the strict "standing next to it" reading
  of the owner's "adjacent to the target"; a larger radius blames slightly sooner (cheaper, less
  precise). Leaning: 1.
- **R3 — held-target clear conditions.** Clear `T` on any FOUND probe of `T` or a structural SWAP
  (deviation/flood-widen/top-collapse). Confirm no other clear trigger is wanted (e.g., a new user
  goal, which resets the whole plan anyway).

---

## §11. Implementation — built then REVERTED 2026-07-25 (owner call); re-do AFTER #2/#3

> **[REVERTED 2026-07-25.]** The block-tier keep-guard below was implemented (async pollPending + a sync
> mirror in replanBlock, driver-`waypointIndex` field, `skeletonGeneration` SWAP-scope), compiled, passed
> the suite, and cleared re-red-team on its own axes (freeze/give-up/scope — that design is sound). It was
> then **reverted byte-identical** because diagnosis proved it MISALIGNED with the actual flap:
> - **The cliff repro (70,63,-68→77,72,-78) is the #2/#3 fixture, not #1's** (goal reachable only via the
>   walk-around; #1's fixture is the ORBIT repro 68,64,-76). The observed flap is a **region-crossing
>   invalidation oscillation** (`region-crossing BLOCKED (gen 1,2,4,6,7) -> REROUTED`, over-condemning
>   crossings) that fires at **consumption boundaries where the block-tier keep is INERT** (no forward
>   waypoint). #1 can't fix it → **#3 (from-fragment keying) can.**
> - The keep also **perpetuated a wrong-way partial** (kept a partial heading away from the goal). The
>   re-do **needs a progress-toward-goal conjunct** (keep only if the plan's terminus is closer to the goal
>   than the bot) — the v1 red-team's dropped `cond5`, now proven necessary.
> - Live-vs-headless divergence was **persisted crossing-memory** (owner's world seeds 4 remembered
>   crossings; the clean master seeds 0) → different routes.
>
> **Re-do plan (after #2/#3):** re-implement the block-tier keep + the progress-toward-goal conjunct;
> verify on the **orbit fixture (68,64,-76)**. The mechanism below is the design of record for that re-do.

### The reverted mechanism (async keep-guard; design of record for the re-do)

The owner's a/b/c reframing (banner) + an understand→plan→**red-team**→revise→**re-red-team** loop
landed a mechanism much simpler than §2/§4's "hold-far-target / closest-launch". The red-team caught a
BLOCKER in the first plan (a PathPlan-geometry guard would freeze on a weaving/consumed plan — it can't
see the driver's `waypointIndex`); the revision moved the decision to the **async adoption point** with a
driver-supplied follow-state. Re-red-team verdict: **NO BLOCKERS**.

**Mechanism (as built, all in `PathPlan.java` + one `BotNavigator` line):** in async mode a background
re-search's result is adopted in `pollPending`'s RESULT case (`blockPlan = async.resultPlan()`;
`resultStatus` bumps `blockedGeneration` on a null → the sole durable-invalidate trigger). The guard,
inserted at the top of that case, **DISCARDS a flood result — keeps the plan the bot is walking — iff
all six hold:**
```
async.resultBudgetHit()            // it's a FLOOD (node/time cap), NOT heap-exhaust (budgetHit=false → never suppressed)
&& driverHasForwardWaypoints       // the driver's REAL waypointIndex < path.size() (channel param; NOT geometry — kills the freeze)
&& blockPlan != null
&& skeletonGeneration == planSkeletonGen   // the region skeleton has NOT swapped since this plan was built (SWAP-scope)
&& !planImpacted()                 // the plan's traversed chunks are unchanged → provably still walkable (no-wedge)
&& botOnBlockPlan(actualFloor)     // the bot is on that plan
```
On keep: `status = RUNNING; return;` — `blockPlan` untouched, `blockedGeneration` NOT bumped (no durable
invalidate), and the `return` skips `pollParked` (so it can't clobber the kept plan). Everything else is
byte-identical.

**Key correctness properties (red-team-verified):**
- **No freeze:** a consumed plan has `driverHasForwardWaypoints=false` → keep inert → normal a/b/c. The
  keep only ever fires when there's a genuine forward waypoint, so `steerAlongPath` always runs.
- **Heap-exhaust untouched:** `budgetHit` is false for a drained open set → the guard is never even
  consulted → the "heap-exhaust → durable proof" rule is preserved verbatim.
- **Give-up preserved:** at a walled give-up the bot consumes its partial to the terminus
  (`waypointIndex ≥ size`) → keep inert even for a TIME-flood (`budgetHit=true`) → FAILED + 0-repeat-blames.
- **SWAP-scope:** `skeletonGeneration` is bumped in `resetWindow()` — whose only callers are the two true
  swaps (cascade SWAPPED, `repairBlocked`); EXTENDED never calls it. `planSkeletonGen` is stamped in
  `snapshotPlanChunks()` (every plan install). A kept plan can never walk toward a route the cascade
  abandoned. A cross-tick generation counter (not a per-tick flag) is required because the swap happens
  at a prior tick and `pollPending` runs before `stepCascade`.
- **Async-centered:** the guard lives only in `pollPending`; sync mode (`executor==null`, non-production)
  is byte-identical.

**Two accepted MINOR behavior notes (documented as intended):** (1) a keep suppresses a reroute while the
bot is stalled mid-plan by an *execution* failure — policy-aligned (don't blame a region crossing for a
movement-execution defect; a real wall still gives up via consumption). (2) the keep's `return` delays a
legitimate parked-splice adoption by 1–3 ticks in a corner (P4 latency erosion; self-corrects).

**Edits:** `PathPlan.onBotMoved`/`pollPending` gain a `driverHasForwardWaypoints` param; new
`skeletonGeneration`/`planSkeletonGen` int fields (bump in `resetWindow`, stamp in `snapshotPlanChunks`);
the 6-conjunct guard in `pollPending` RESULT; `pollWhenPlanless` passes `false`; `BotNavigator` computes
`hasForwardWaypoints = path != null && waypointIndex < path.size()` and passes it. No new API, no test
churn.

**Verification status:** compiles clean; full suite green (562/0/5, no regression); red-team NO BLOCKERS.
**OWED (behavioral oracle):** the `(68,64,-76)` orbit-convergence repro + the wall honest-give-up repro
(headless `-MasterWorld` autotest) — needs the session-specific repro world/coords; the R1/R2/R3
ratification bullets above are SUPERSEDED by this shipped design (durable flood-invalidation via the
existing evidence model; no `RegionCrossingMemory` change; no `ANTIFLAP_ADJ`/held-target — the keep is
per-adoption, not target-held).
