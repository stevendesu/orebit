# DESIGN — Boxed-in reachability: negative reachability from the reverse flood (#4) + the block-A\* prune (#5)

Status: **SHIPPED (2026-07-27).** This is the as-built design card for the live boxed-in machinery, cited
by `§`-anchor from `RegionPathfinder`, `RegionCostField`, `PathPlan`, `BlockPathfinder`, `BoxedInCourse`
and `BoxedInReachabilityTest`. **§14 is the mechanism that actually runs** (the multi-level PROACTIVE
goal-box scan); §1–§13 are the soundness argument and the model it rests on — keep them, they are the
reason `INFINITE` is a *proof* rather than a heuristic, and breaking them breaks find-path-iff-exists.

Live entry points: `PathPlan.maybeProactiveBoxedIn` / `harvestBoxedInProof`,
`RegionPathfinder.isSealedWithin` / `costToGoalField(…, level)`, `RegionCostField.markInfinite` /
`isBlocked`, the block-A\* hard reject in `BlockPathfinder`, and the `BoxedInCourse` headless course.

> **OWNER RULINGS 2026-07-25 (decisive; they supersede §7's earlier persistence discussion and BR-4):**
> - **Boxed-in is NEVER persisted — recompute-only.** Reachability is a property of *(goal, start)*: a bot
>   that starts *inside* the box reaches the "boxed-in" goal trivially, so persisting would require keying
>   on the starting position, which blows up storage — and it is cheap to recompute. The `INFINITE` set is
>   a per-search, ephemeral structure on the cost field, consumed by #5's prune within the search and
>   discarded. No durable rows, no restart oracle, no terrain-bump invalidation. (The durable rows in this
>   arc are the **crossing invalidations** of the existing evidence model, from-fragment-keyed via #3 — a
>   *separate* mechanism.)
> - **Soundness = the entire boxing perimeter must be built.** Any **unbuilt** perimeter region → stay
>   optimistic → do NOT declare boxed-in. This is exactly the closed-flood condition + the no-escape guard.
> - **Harvest when we are struggling** — "if we're having trouble finding the goal, pause to make sure it's
>   actually possible." Folded into the plan-entry/approach check (§14), not a separate terminal trigger.

---

## §0. TL;DR

The goal-rooted reverse Dijkstra `costToGoalField` floods the goal's **caps-conditioned** connected
component, confined to a bounding box. It could not be *harvested as-is* (see §1). With three small
additions it yields a **sound** negative-reachability proof:

1. **Closed-flood detection** — the flood terminated by **heap exhaustion** (not the fat-skeleton
   early-exit, not the `MAX_REGION_EXPANSIONS` backstop) **and** never rejected an out-of-box target.
2. **Built-membership** — `grid.fragmentRecord(0,R) != null`.
3. **A no-escape guard** — every in-box neighbour of `R` is itself built.

Then `INFINITE(R) ⟺ closedFlood ∧ built(R) ∧ ¬reached(R) ∧ neighboursAllBuiltInBox(R)` is a sound proof
that `R` cannot reach the goal under these caps. #5 consumes the `INFINITE` set as a **hard** block-A\*
reject and a region-A\* crossing invalidation.

---

## §1. Why the field could not be harvested as-is

`costToGoalField` is **edge-expansion clipped by a bounding box**, not a swept census: `expandNode`
`ensureNode`s only the six face-neighbours of *settled* nodes, so an in-box region not graph-adjacent to
the reached set is never considered, never labeled. The box exists only to **stop** the flood (the
per-relax admission gate in `relaxFrag`), never to **enumerate** a negative claim. Three concrete
blockers in the pre-#4 field:

1. **It never represented "infinite".** `costAt` never returned `UNREACHED` — every unsettled or
   out-of-box query returned `max(floorCost, cheb × MIN_CROSS)`. The frontier-floor design was explicitly
   *"guidance, never exclusion"*.
2. **No built-membership over unreached in-box slots.** All baked metadata (`cheapSlot[]`,
   `reachedFrags[]`, `slabs[]`) is keyed off *reached* slots.
3. **The two in-box "unlabeled" kinds were indistinguishable** — walled-off-and-built vs
   unbuilt/unresident both read as `cost[i] = UNREACHED, reachedFrags[ri] = 0`. Only out-of-box was
   distinguishable (`regionIndex < 0`).

So the harvest is **"add a swept condition, then harvest"**, not "harvest existing" — that is what §3 adds.

---

## §2. The soundness argument (the load-bearing section)

The owner's invariants are **find-path-iff-exists** and **prove-nonexistence**. A false `INFINITE`
(claiming a reachable region is dead) would make #5 prune a valid route — a correctness bug and a false
give-up. So every `INFINITE` label must be a *proof*. Three legs:

**Leg 1 — region-tier optimism makes region-unreachable a SOUND witness for block-unreachable.** The
region graph *over-connects* reality: merged pockets (the L0 flood connects on passability, so a shelf
and the cliff-top above it merge through the open air column), footprint-overlap crossings, and an
always-connected dig fallback (`RegionFragments` "always fully connected"). Therefore, under matched caps, `Reachable_region ⊇ Reachable_block`. Contrapositive:
**`R ∉ Reachable_region ⟹ R ∉ Reachable_block`.** If even the optimistic coarse flood cannot connect `R`
to the goal, the stricter block tier cannot either. The reverse flood is caps-conditioned by construction
(it drops mine edges for `!canBreak`, gates air fragments for `!canPlace`), so the witness is
caps-correct. *Corollary:* a **break-capable** bot is almost never boxed-in — `expandNode` always emits a
dig-through edge for it, so its flood reaches all diggable terrain. The target case is **no-break** bots
on real walls.

**Leg 2 — the closed flood computes the component EXACTLY.** Leg 1 needs the *whole* component. The flood
can stop three ways: heap exhaustion, the fat-skeleton early-exit, or the 20k expansion backstop. Only
**heap exhaustion** guarantees completeness — the other two are prefixes. And completeness *within the
box* is completeness *period* only if the component never left the box: `relaxFrag` rejects out-of-box
targets, so if that reject **ever fired**, a reached region was adjacent to an out-of-box region and a
corridor could exit-and-re-enter. Hence:

> **closedFlood ≡ terminated by heap exhaustion (not early-exit, not backstop) AND the out-of-box reject
> never fired during the build.**

Under a closed flood, `reached` = the exact caps-conditioned goal-connected component.

**Leg 3 — the no-escape guard closes the unbuilt hole.** An `UNBUILT` region (`fragmentRecord == null`)
is optimistically passable; the harvest must never claim anything about terrain it has not classified. If
the flood provably expands *through* optimistic-unbuilt regions, all unbuilt-reachable regions are
already in `reached` and the guard is redundant — but to be **robust regardless**, require every in-box
neighbour of `R` to be built. Cheap (≤6 `fragmentRecord` reads) and it makes the proof independent of the
unbuilt-expansion question.

**The sound label:** `INFINITE(R) ⟺ closedFlood ∧ built(R) ∧ ¬reached(R) ∧ (∀ in-box neighbour N:
built(N))`. Everything else stays **optimistic** — out-of-box, unbuilt, any region under a non-closed
flood, and any region with an unbuilt/out-of-box neighbour. Strictly conservative: we prove `INFINITE`
for a subset of the truly-disconnected built regions and stay silent on the rest. **No false give-up is
possible.**

---

## §3. The harvest mechanism

Additions to `costToGoalField` / `RegionCostField`, **all tick-thread** (the field builds on the tick
thread; no planner-pool exposure):

- **Closed-flood flag.** A `LAST_FIELD_STATS` slot for `outOfBoxRejected` (set when `relaxFrag` returns
  false for the box reason) plus an exhaustion flag (the loop exited with `heapSize == 0`, neither break).
  `closedFlood = !earlyExit && !backstopHit && !outOfBoxRejected`. **When a harvest is wanted the flood
  must run WITHOUT the fat-skeleton early-exit** (a harvest-mode flag suppressing `marking`) so it drains
  to exhaustion. That is the extra cost.
- **INFINITE tri-state on `RegionCostField`.** A per-region state {SETTLED, INFINITE, OPTIMISTIC-UNKNOWN},
  default OPTIMISTIC. After a closed harvest flood, `markInfinite` sweeps the box's built regions and sets
  `INFINITE` where `reachedFrags == 0` and all in-box neighbours are built. `costAt` keeps its
  floored-optimistic value for everything; a new `isBlocked(rx,ry,rz)` returns true **only** for INFINITE.
  The field already rides read-only to the async `SearchRequest`, so the tri-state rides with it — no new
  plumbing.
- **Frontier optimization (not shipped, still available).** Instead of sweeping the whole box, mark only
  the *frontier* — regions `expandNode` evaluated a face toward but emitted no caps-legal crossing into
  (the proven-wall set). Sufficient for #5, since block-A\* can only flood *into* a region across its
  frontier. Add it only if the whole-box sweep profiles hot.

---

## §4. When the harvest runs

> **Superseded in shape by §14** — the reactive terminal-flood trigger below became a **proactive,
> multi-level, goal-centered scan at plan entry**, and §4.1's coarse fast-filter was absorbed into it as
> the scan's coarse levels. Read §14 for what runs; this section records the two roles, which survive.

1. **§4.1 — the coarse walk-only fast-filter (cheap, first).** For a **no-break** bot, "is the goal's coarse
   fragment sealed?" is an `O(1)` structural test: fetch the goal fragment's record and its neighbours'
   and test `RegionFragments.touchesFace` on both halves of each shared face (coarse `faceMask` is rolled
   up by `PyramidMerger`). No open shared face with any built neighbour ⇒ boxed-in at that scale — an
   immediate structural BLOCKED that serves "see massive obstacles from afar" and skips the L0 flood
   entirely. Sound only on the **no-break** axis (a break-capable bot tunnels — see §8).

2. **§4.2 — the L0 harvest as the give-up arbiter (general).** When the driver reaches a *terminal* flood,
   run the **closed** L0 harvest flood once and use it as the discriminator:
   - **INFINITE(goal region), or the terminal crossing lands in an INFINITE region** → the goal is
     structurally unreachable under these caps → **BLOCKED**: the honest, principled give-up.
   - **Not INFINITE** (open flood, or reachable within the component) → the flood was a **budget**
     artifact, not a wall → no structural claim; the in-journey reroute handles it and a residual budget
     give-up is acceptable.

   This is the **structural-BLOCKED vs budget-unproven** discriminator. `PathPlan` exposes it as the
   "last region-tier give-up was a proof, not a budget artifact" bit consumed by the driver.

---

## §5. #5 — the block-A\* prune (and region-A\* invalidation)

Before #5 the region field was a **dead soft-max**: `h()` guarded on `rc < UNREACHED`, which never held,
so a provably-dead region's cells were merely deprioritised and then still expanded. The prune replaces
that with a **hard reject**:

- **Block-A\* hard reject.** At the relax/accept path — the same site as the existing `RegionBound
  confineBound` geometric reject — reject any candidate cell whose enclosing L0 region has
  `field.isBlocked(rx,ry,rz)`. Keyed on a reachability set instead of a geometric box; identical shape to
  machinery that already existed. This is what stops the flood *into* provably-dead regions (the
  cone-of-partial-pillars / cliff-face flood).
- **Region-A\* crossing invalidation.** A crossing whose TO-region is `INFINITE` is unrealizable under
  these caps → invalidate it (same machinery as blame). This is a **structural** proof, not a budget
  artifact.
- **Optimism preserved (INV BR-3).** Everything not `INFINITE` keeps the floored-optimistic `costAt`. The
  prune only ever *removes* provably-dead expansion; no f-value on a non-INFINITE cell changes, so
  non-pruned searches are **byte-identical**. A field built by the normal per-search path carries no
  INFINITE set at all, so `isBlocked` is false for every cell on an ordinary search.

---

## §6. The optimism boundary (checklist)

| region state | label | rationale |
|---|---|---|
| out-of-box (`regionIndex < 0`) | OPTIMISTIC | never considered — say nothing |
| open flood (early-exit / backstop / out-of-box reject fired) | OPTIMISTIC (all) | component not proven complete — no harvest at all this build |
| in-box, unbuilt (`fragmentRecord == null`) | OPTIMISTIC | unclassified terrain — never claim |
| in-box, built, reached | SETTLED (cost) | in the component |
| in-box, built, unreached, an unbuilt/out-of-box neighbour | OPTIMISTIC | possible escape hatch — conservative |
| in-box, built, unreached, all in-box neighbours built, **closed flood** | **INFINITE** | provably disconnected (§2) |

---

## §7. Persistence — NONE (recompute-only)

Per the owner ruling in the banner: the `INFINITE` set lives **only on the per-search `RegionCostField`**,
is consumed by #5's prune within that search, and is discarded. No `RegionCrossingMemory` write, no
restart oracle, no terrain-bump invalidation — there is nothing durable to invalidate, so the
permanent-cage risk is gone by construction. The durable rows in this arc are the **crossing
invalidations** (a property of a *crossing*, legitimately durable); boxed-in is a property of
*(goal, start)*, which is not. Keep the two mechanisms distinct.

---

## §8. Coarse boxed-in and the "see obstacles from afar" invariant

The **walk-only, no-break** coarse check is trivial and ships (as §14's coarse scan levels): a sealed goal
region at a coarse level is a massive obstacle seen from afar. The **caps-general** coarse proof is *not*
answerable from existing accessors — dig-out is always available to break-capable bots, there is **no
per-face unbreakable-seal bit** on the fragment record, and coarse collapse (`fc == 63`) reads as
optimistic passable mass. Closing that needs either the per-face seal bit or the region-tier connectivity
pass of item #6 — **deferred, see `NOTES-region-deferred.md` §6/§8**. #4 therefore stays at L0 for the
general mechanism plus the cheap coarse no-break filter.

---

## §9. Relationship to the deferred #6

#6 (capability-aware region graphs, `NOTES-region-deferred.md`) would bake capability into the graph so a
no-cap bot's cliff shelf and cliff top become **different fragments** — the disconnection becomes
structural, visible at construction, so the region-A\* heap-exhausts to a true BLOCKED without ever
flooding and this harvest never needs to run for that case. But #4/#5 remain necessary regardless: for
**optimistic unbuilt terrain** (both tiers stay optimistic there by design), for **coarse-collapse**
regions, and as the **block-tier** prune (#6 is a region-tier construction). **#6 would reduce how often
the harvest fires; it does not replace it.**

---

## §10. Invariants

- **BR-1 (soundness).** `INFINITE` is set only under the §2 conjunction; a false INFINITE is impossible by
  the optimism-⊇ + closed-flood + no-escape-guard argument. No reachable region is ever pruned.
- **BR-2 (optimism boundary).** Out-of-box, unbuilt, open-flood and escape-hatch regions stay optimistic
  (§6). The harvest never claims anything outside a closed flood.
- **BR-3 (byte-identical non-pruned search).** #5 is a pure reject of INFINITE cells; no f-value on any
  non-INFINITE cell changes → non-pruned searches expand identically.
- **BR-4 — VOID (superseded by the never-persist ruling).** There are no durable INFINITE rows, so there
  is no terrain-change invalidation to perform. If persistence is ever revisited, this invariant returns:
  an INFINITE proof must never outlive the terrain that made it true.
- **BR-5 (tick-confinement).** The harvest builds on the tick thread (like the field itself); the async
  planner reads the tri-state read-only via `SearchRequest`. No planner-pool thread touches `RegionGrid`.

---

## §11. Increments and test plan — SHIPPED

All increments landed; the persistence increment was resolved to "never". Regression coverage lives in
`BoxedInReachabilityTest` (the closed-flood conjunction and the INFINITE label on synthetic
`RegionGrid.headless` worlds: a walled pocket → INFINITE; a corridor-out → not; an unbuilt-neighbour
pocket → optimistic; plus the BR-3 byte-identical guard) and in the `BoxedInCourse` headless course.
**The soundness regression to preserve if this code is ever touched:** a fixture whose route leaves and
re-enters the box must **not** produce INFINITE — the out-of-box reject fires, so the flood is not closed
and the harvest is disabled.

---

## §12. Perf

See §14 for the measured numbers of the shipped scan. Shape: the harvest flood runs to exhaustion (no
early-exit) but is bounded by the box and the expansion backstop, and runs once per journey — not per
tick, not per search. The block-A\* prune is a net **win** (one `isBlocked` array read at the accept path
removes the expansions it prevents), but it adds a data-dependent branch to a branch-dense path, so any
change here must show no regression on **non-boxed** scenarios. The tri-state is a per-region byte on a
field that is already allocated per build — no new per-tick allocation.

---

## §13. Decisions log

- **D1** — The harvest is "add a swept condition, then harvest", not "harvest existing" (§1). The three
  additions: closed-flood flag, INFINITE tri-state, no-escape guard.
- **D2** — Soundness rests on region-tier optimism (`Reachable_region ⊇ Reachable_block`) + closed-flood
  completeness + the no-escape guard (§2). INFINITE is a proof, never a heuristic.
- **D3** — `closedFlood = heap-exhaustion ∧ ¬earlyExit ∧ ¬backstop ∧ ¬outOfBoxRejected`; the harvest flood
  suppresses the fat-skeleton early-exit so it drains fully.
- **D4** — #5 is a HARD reject at the block-A\* accept path (replacing the dead soft-max); non-INFINITE
  cost values are unchanged → byte-identical non-pruned search (BR-3).
- **D5** — #4 is the **arbiter** of a terminal flood: INFINITE → structural BLOCKED; not-INFINITE →
  budget artifact.
- **D6** — The coarse walk-only no-break filter is the front-line; caps-general coarse is deferred into #6.
- **D7** — #6 reduces the harvest's frequency but does not replace #4/#5 (§9).
- **D8** — Boxed-in is **never persisted** (owner, 2026-07-25): recompute-only, per-search, ephemeral.

---

## §14. AS BUILT (2026-07-27) — the multi-level PROACTIVE goal-box scan

Increment 1 first shipped §4.2's mechanism as a single **L0** harvest rooted at the window target
(reactive, `PathPlan.harvestBoxedInProof`) plus a proactive L0 probe rooted at the journey goal over an
`around(bot, goal, pad=3)` box. Owner-directed grounding evolved that into what actually ships — four
changes, all owner-directed:

1. **PROACTIVE, at plan entry** (owner: "do the boxed-in check at the very beginning… the reverse
   Dijkstra is fast"). A goal walled by BUILT solid with optimistic-UNBUILT terrain between it and the bot
   never reaches a region-tier give-up — the unbuilt reads as passable AIR, `PARTIAL_PATH` keeps the
   forward search RUNNING, and the bot wanders forever. So the check runs **before** committing an
   optimistic skeleton (`PathPlan` ctor) and, gated, as the bot approaches (`onBotMoved`) — not only at a
   terminal flood.
2. **MULTI-LEVEL coarse→fine** (owner: "small box around the goal at L5; if not boxed-in, L4; … down to
   L0"). A tomb is not inherently L0 — a pocket large enough to flood L0 but ringed by solid seals only at
   a coarser level (a 1024³ obsidian box is an L3/L4 seal). So the scan runs
   `level = MAX_COARSE_LEVEL(6) → 0`, flooding at each level over a small box centered on the goal, and
   takes the **first CLOSED flood**. This unifies §4.1's coarse filter and §4.2's L0 harvest into one
   descending scan and generalizes both.
3. **Small GOAL-centered box** (`RegionBox.around(goalRegion, goalRegion, radius)`), NOT §0's
   `around(bot, goal, pad=3)`. The bot↔goal span made the L0 field array
   (`dimX·dimY·dimZ·MAX_FRAGMENTS`, sized to the box) explode for a far goal — a 100k-away goal → a
   ~6250-region-long corridor → tens of MB of alloc + fill on the tick thread. The goal-centered box makes
   the scan **distance-INDEPENDENT**: a 100k-away goal probes exactly as cheaply as a near one. (Rolling
   the *search* up to a coarser level for far goals — "don't build a 10M-block skeleton" — is a separate
   item.)
4. **Verdict = `closedFlood`, not `isBlocked(bot)`.** With a small goal box the bot is typically OUTSIDE
   it, so its region carries no INFINITE mark. But a CLOSED flood at any level already proves the goal's
   caps-legal component is SEALED within the box — unreachable from **any** exterior cell, so the bot's
   position is irrelevant. `RegionPathfinder.isSealedWithin(grid, minY, goal, level, radius, caps…)`
   returns exactly that, VERDICT-ONLY.

**Monotone soundness (the load-bearing property of the descent).** "Sealed at level N" is a hard, monotone
proof: if the goal's level-N component has no caps-legal crossing leaving a box centered on it, nothing at
any *finer* scale can escape either. Region-tier optimism (§2 Leg 1) only ever *over*-connects, so a
coarse CLOSED flood is a real seal (no false INFINITE); a coarse NOT-closed is a *soft* signal
(over-connection may hide a finer seal) → **descend**. Hence the first close at any level is sound, and L0
is the precise floor. A seal larger than the L6 box (~7k blocks at radius 3) falls through to the give-up
backstop.

**Caps.** Coarse crossings are **walk-sound for no-dig bots** (the common boxed-in case — `faceMask`
roll-up). For **dig** bots the coarse view is optimistic about dig-through (no unbreakable-seal bit), so a
dig bot's coarse flood stays open → the scan descends/proceeds (correct: a breakable-walled region is not
a tomb for a digger). A dig bot's genuinely-unbreakable tomb is the rare residual deferred to #6
(`NOTES-region-deferred.md` §8). **The scan never produces a false give-up**; worst case it descends a
level.

**Level-parameterization (implementation).** `costToGoalField` gained an `int level` (12-arg overload; the
10/11-arg delegate passes `level = 0` — byte-identical, since every region derivation was already
`regionX(w,0)`). `level > 0` floods the coarse fragment pyramid; `bakeSlabs`/`markInfinite` and the
per-cell `costAt`/`isBlocked` reads are L0-only, so a `level > 0` build is VERDICT-ONLY (read
`closedFlood`, never the field). The goal dig-flood multi-seed is L0-only too (no resident section at
coarse), so a coarse break-capable build falls back to the single nearest-centroid seed — acceptable for a
gross-seal probe. `ensureLevel(level,…)` builds the coarse node on demand.

**The reactive L0 harvest is RETAINED** (`harvestBoxedIn` via `harvestBoxedInProof`) as the region-tier
give-up backstop: it roots at the window target and **persists the harvested `RegionCostField`** (in
memory, for the journey) so #5's block-A\* `isBlocked` prune has teeth on a continuation search. The
proactive multi-level scan is verdict-only (no field persisted → a NOT-boxed verdict leaves every
subsequent block search byte-identical, INV BR-3).

**Perf (measured, JMH `RegionFieldBuildBenchmark`).** A radius-3 box ≈ `boxSize = 7` → ~510 µs per level
(worst-case EXHAUST); the scan is up to ~5 full-Y levels + 2 collapsed coarse ones → **~2–3 ms per scan**,
once per journey and gated by the goal-neighbourhood build signal. Dominated by the per-level
`RegionCostField` alloc+fill, **not** the flood. A **field-less verdict-only mode** (the coarse levels
never read the field, only `closedFlood`) would drop it to sub-ms by skipping that alloc — a worthwhile
fast-follow, not a blocker. The hot search path is unchanged (level-0 byte-identical; JMH region-search
flat).

**Config.** Coarsest level = `RegionAddress.MAX_COARSE_LEVEL` (6); per-level box radius = the
`pathing.boxedInScanRadius` knob (default 3), read off `caps.boxedInScanRadius()` in
`maybeProactiveBoxedIn` (rides `BotCaps` like `maxNodes`; excluded from the realizability signature).
Clamped `1..16`.

**Tests.** `BoxedInReachabilityTest`: `isSealedWithin` at L0 (sealed → true / connected → false /
unbuilt-border → false) plus a coarse rolled-up interior-pocket seal (closes at L0 AND L1 — validates that
the level-parameterized flood reads coarse fragments). `BoxedInCourse` covers the end-to-end
give-up-vs-wander case.
