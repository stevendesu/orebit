# DESIGN — Boxed-in reachability: harvesting negative reachability from the reverse flood (#4) and pruning block-A* (#5)

Status: **DRAFT — pending owner review as part of the region-tier next-work SET
(`ROADMAP-region-nextwork.md`). Gated on and grounded in `FINDINGS-reverse-reachability.md`.**
Relationships: consumes `RegionPathfinder.costToGoalField`; produces the `INFINITE` region set that
**#5** (block-A* prune) and the region-A* crossing-invalidation consume; is the **arbiter** that turns
`DESIGN-anti-flap.md`'s (#1) *terminal* flood into either an honest structural BLOCKED (persistable) or
a "keep trying" (budget artifact); supplies the durable half of anti-flap §6 option C; is the concrete
mechanism behind `DESIGN-virtual-start-fragment.md` §8's BLOCKED = boxed-in; and is progressively
*reduced in frequency* (not replaced) by `DESIGN-capability-aware-region-graphs.md` (#6). Cites are the
`orebit-mc121-wt` worktree.

---

> **[OWNER RULINGS 2026-07-25 — decisive; supersede §7's persistence discussion, BR-4, and R2.]**
> - **Boxed-in is NEVER persisted — recompute-only.** Reachability is a property of *(goal, start)*: if
>   the bot starts inside the box, the boxed-in goal is trivially reachable — so persisting would require
>   keying on the *starting position*, not just the goal, which blows up storage. And it is cheap to
>   recompute. So the `INFINITE` set is a **per-search, ephemeral** structure on the cost field, consumed
>   by #5's prune within the search and discarded. No durable rows, no restart oracle, no terrain-bump
>   invalidation for boxed-in. (The durable rows in this arc are the **#1 crossing invalidations** —
>   existing evidence model, from-fragment-keyed via #3 — a *separate* mechanism.)
> - **Soundness = the entire boxing perimeter must be built.** We can only *prove* boxed-in if every
>   perimeter region is built; any **unbuilt** perimeter region → stay optimistic → do NOT declare
>   boxed-in (an unbuilt path to the goal might exist). This is exactly the closed-flood condition (no
>   out-of-box reject) + the no-escape guard (all in-box neighbours built) below.
> - **Harvest on flood-partial** — "if we're having trouble finding the goal, pause to make sure it's
>   actually possible." Fold the reachability check into the flood/partial decision point (§4), not a
>   separate "terminal-flood-only" trigger.

## §0. TL;DR

The goal-rooted reverse Dijkstra `costToGoalField` (`RegionPathfinder.java:999-1127`) already floods
the goal's **caps-conditioned** connected component, confined to a bounding box
`RegionBox.around(start, goal, pad=3)` (`:1221`). It cannot be *harvested as-is* (the field floors every
unlabeled slot to an optimistic bound — "guidance never exclusion", `RegionCostField.java:33-36` — and
records no built-membership; see FINDINGS §2). But with three small, verified additions it yields a
**sound** negative-reachability proof:

1. **Closed-flood detection** — the flood terminated by **heap exhaustion** (not the fat-skeleton
   early-exit `LAST_FIELD_STATS[1]`, not the `MAX_REGION_EXPANSIONS` backstop `:1085`) **and** never
   rejected an out-of-box target (`relaxFrag:1494`). A closed flood's reached set **is** the complete
   caps-conditioned goal-connected component within the box.
2. **Built-membership** — `grid.fragmentRecord(0,R) != null` (`RegionGrid.java:904`).
3. **A no-escape guard** — every in-box neighbour of `R` is itself built (no unbuilt/out-of-box escape
   hatch).

Then `INFINITE(R) ⟺ closedFlood ∧ built(R) ∧ ¬reached(R) ∧ neighboursAllBuiltInBox(R)` is a **sound**
proof that `R` cannot reach the goal under these caps. #5 consumes the `INFINITE` set as a **hard**
block-A* reject (today it is a dead soft-`max`, `BlockPathfinder.java:1063-1074`) and a region-A*
crossing invalidation. The harvest runs at exactly one place: the moment anti-flap's flood becomes
*terminal* (the closest launch floods) — that is when we must distinguish "structurally boxed-in
(persist + honest give-up)" from "hard but reachable (budget artifact, keep trying)".

---

## §1. Why the field cannot be harvested as-is (FINDINGS recap)

`costToGoalField` is **edge-expansion clipped by a bounding box**, not a swept census (FINDINGS §1):
`expandNode` `ensureNode`s only the six face-neighbours of *settled* nodes (`:1338,:1371`), so an in-box
region not graph-adjacent to the reached set is never considered. Three blockers to a naive read
(FINDINGS §2): `costAt` never returns `UNREACHED` (it floors to `max(floorCost, cheb×MIN_CROSS)`,
`RegionCostField.java:224-247`); no built-membership is recorded over unreached slots; and the two
in-box "unlabeled" kinds (walled vs unbuilt) are indistinguishable today (FINDINGS §4). The box exists
only to **stop** the flood (`relaxFrag:1494`), never to **enumerate** a negative claim. So harvesting
requires *adding* the three signals of §0 — it is "add a swept-range condition then harvest", not
"harvest existing".

---

## §2. The soundness argument (the load-bearing section)

The owner's invariants are **find-path-iff-exists** and **prove-nonexistence**: a false `INFINITE`
(claiming a reachable region is dead) would make #5 prune a valid route — a correctness bug and a false
give-up. So every `INFINITE` label must be a *proof*, not a heuristic. Two independent legs:

**Leg 1 — region-tier optimism makes region-unreachable a SOUND witness for block-unreachable.** The
region graph *over-connects* reality: merged pockets, footprint-overlap crossings, an always-connected
dig fallback (virtual-start §2; `RegionFragments` "always fully connected"). Therefore, under matched
caps, `Reachable_region ⊇ Reachable_block`. Contrapositive: **`R ∉ Reachable_region ⟹ R ∉
Reachable_block`.** If even the optimistic coarse flood cannot connect `R` to the goal, the stricter
block tier cannot either. The reverse flood is caps-conditioned by construction — it drops mine edges
for `!canBreak`, gates air fragments for `!canPlace` (FINDINGS; `RegionPathfinder.plan` caps gating) —
so the witness is caps-correct. (Corollary: this is why a **break-capable** bot is almost never
boxed-in — `expandNode` always emits a dig-through edge for a break-capable bot, `:1334-1360`, so its
flood reaches all diggable terrain. The harvest's target case is **no-break** bots on real walls,
exactly the cliff/orbit fixtures.)

**Leg 2 — the closed flood computes the component EXACTLY.** Leg 1 needs the flood to have found the
*whole* component. The flood can stop three ways (verified §RegionPathfinder loop): heap exhaustion
(`while heapSize>0` drains), the fat-skeleton early-exit (`marking && pendingMarked==0`,
`LAST_FIELD_STATS[1]==1`), or the 20k backstop (`expansions>MAX_REGION_EXPANSIONS`). Only **heap
exhaustion** guarantees completeness — the other two are prefixes. And completeness *within the box* is
only completeness *period* if the component never left the box: `relaxFrag` rejects out-of-box targets
(`:1494`), so if that reject **ever fired**, a reached region was adjacent to an out-of-box region and a
corridor could exit-and-re-enter — the box did not contain the component. Hence:

> **closedFlood ≡ terminated by heap exhaustion (not early-exit, not backstop) AND the out-of-box
> reject at `:1494` never fired during the build.**

Under a closed flood, `reached` = the exact caps-conditioned goal-connected component.

**Leg 3 — the no-escape guard closes the unbuilt hole.** An `UNBUILT` region (`fragmentRecord==null`)
is optimistically passable; the harvest must never claim anything about terrain it hasn't classified
(FINDINGS §4 optimism boundary). If the flood expands *through* optimistic-unbuilt regions (i.e.,
`grid.ensureLeaf` fabricates an open record for `null` regions — **VERIFY at implementation**; see R1),
then all unbuilt-reachable regions are already in `reached` and the guard is redundant. To be **robust
regardless** of that unbuilt-handling, require every in-box neighbour of `R` to be built: an `INFINITE`
region then has no unbuilt escape hatch and no out-of-box escape (closedFlood). Cheap (≤6
`fragmentRecord` reads) and makes the proof independent of the unbuilt-expansion question.

**The sound label:** `INFINITE(R) ⟺ closedFlood ∧ built(R) ∧ ¬reached(R) ∧
(∀ in-box neighbour N: built(N))`. Everything else stays **optimistic** — out-of-box (`regionIndex<0`),
unbuilt, any region under an open (non-closed) flood, and any region with an unbuilt/out-of-box
neighbour. This is strictly conservative: we prove `INFINITE` for a subset of the truly-disconnected
built regions and stay silent (optimistic) on the rest. No false give-up is possible.

---

## §3. The harvest mechanism

Additions to `costToGoalField` / `RegionCostField`, all tick-thread (the field builds on the tick
thread; no planner-pool exposure — INV region-tier-tick-confined):

- **Closed-flood flag.** Add a third `LAST_FIELD_STATS` slot `[2] = outOfBoxRejected` set when
  `relaxFrag:1494` returns false for the box reason; and expose `terminatedByExhaustion` (loop exited
  with `heapSize==0`, neither break). `closedFlood = !earlyExit && !backstopHit && !outOfBoxRejected`.
  (When the harvest is wanted, the flood must run **without** the fat-skeleton early-exit — a harvest
  mode flag that suppresses `marking`, so it drains to exhaustion. This is the extra cost, §12.)
- **INFINITE tri-state on `RegionCostField`.** A per-region 2-bit state {SETTLED, INFINITE,
  OPTIMISTIC-UNKNOWN}, default OPTIMISTIC. After a closed harvest flood, sweep the box's built regions
  (`grid.fragmentRecord(0,·)!=null`) and set `INFINITE` for those with `reachedFrags==0` and all in-box
  neighbours built. `costAt` keeps its floored-optimistic value for everything; a new
  `isBlocked(rx,ry,rz)` predicate returns true only for INFINITE. The field already rides read-only to
  the async `SearchRequest` (`BlockPathfinder.java:680-687`) — the tri-state rides with it, no new
  plumbing.
- **Frontier optimization (optional).** Rather than sweep the whole box, mark the *frontier* — regions
  `expandNode` evaluated a face toward but emitted no caps-legal crossing into (the proven-wall set) —
  and check only those. Sufficient for #5 (block-A* can only flood *into* a region across its frontier;
  pruning the frontier stops it). Ship the whole-box sweep first (simpler, bounded by the box); add the
  frontier mark only if the sweep profiles hot.

---

## §4. When the harvest runs — the anti-flap arbiter + the coarse fast-filter

The harvest is not free (§12), so it runs where a proof is actually needed, not every search:

1. **Coarse walk-only fast-filter (cheap, runs first).** For a **no-break** bot, a high-altitude
   "is the goal's coarse (e.g. L3) fragment sealed?" test is trivial (FINDINGS §6): fetch the goal
   fragment's record and its neighbours', and test `RegionFragments.touchesFace` on both halves of each
   shared face (`:253`; coarse faceMask `PyramidMerger.java:461-471`). If the goal's coarse fragment
   shares **no** open face with any built neighbour, the goal is boxed-in at that scale — an immediate,
   `O(1)` structural BLOCKED that serves "see massive obstacles from afar" and skips the L0 flood
   entirely. Sound only for the **no-break** axis (a break-capable bot tunnels; caps-general coarse is
   deferred to #6 / a connectivity pass — FINDINGS §6). Ship this as the front-line filter.
2. **L0 harvest at the terminal flood (general).** When anti-flap (#1) reaches its *terminal* flood —
   the bot stands at the closest reachable launch and the far target still floods (anti-flap §4) — run
   the **closed** L0 harvest flood once. Outcome:
   - **INFINITE(goal region) or the terminal crossing lands in an INFINITE region** → the goal is
     structurally unreachable under these caps → **BLOCKED** (§8's honest give-up), and the proof
     persists (§7). This is the honest, principled give-up the owner ratified as legitimate (HANDOFF
     §4): "this world was too pathological / genuinely disconnected".
   - **Not INFINITE (open flood, or reachable within the component)** → the terminal flood was a
     **budget** artifact, not a wall. Do **not** persist a structural proof; anti-flap's journey-scoped
     edge invalidation (option C) handles the in-journey reroute, and the search may legitimately keep
     trying / the give-up is a pure budget safety call (HANDOFF §4 — a residual budget give-up is
     acceptable). This is the exact discriminator §8 needs: **structural BLOCKED vs budget-unproven.**

This makes #4 the *arbiter* that upgrades anti-flap's position-scoped flood into either a durable
structural proof or a bounded in-journey reroute — closing the loop between #1, #4, and §8.

---

## §5. #5 — the block-A* prune (and region-A* invalidation)

Today the region field is a **dead soft-max**: `h()` guards `rc < UNREACHED` which never holds, so a
provably-dead region's cells are merely deprioritised then still expanded (FINDINGS §3,
`BlockPathfinder.java:1063-1074`). The prune replaces this with a **hard reject**:

- **Block-A* hard reject.** At the relax/accept path (`BlockPathfinder.java:1080-1083`, the same site as
  the existing `RegionBound confineBound` geometric reject), reject any candidate cell whose enclosing
  L0 region has `field.isBlocked(rx,ry,rz)`. Keyed on a reachability set instead of a geometric box —
  identical shape to machinery that already exists. This is what stops the flood *into* provably-dead
  regions (the cone-of-partial-pillars / cliff-face flood).
- **Region-A* crossing invalidation.** A crossing whose TO-region is `INFINITE` is unrealizable under
  these caps; invalidate it (feeds the region tier's re-derive, same machinery as blame). Because this
  is a **structural** proof (not a budget artifact), it is legitimately more persistable than an
  anti-flap flood (§7) — it is the realized-negative evidence the evidence model can carry.
- **Optimism preserved.** Everything not `INFINITE` keeps today's floored-optimistic `costAt` — the
  prune only ever *removes* provably-dead expansion, never redirects live search. No f-value on a
  non-INFINITE cell changes, so non-pruned searches are byte-identical (the perf-model "byte-identical
  search results" bar; the prune is a pure reject, not a re-weighting).

---

## §6. The optimism boundary (restated as a checklist)

| region state | label | rationale |
|---|---|---|
| out-of-box (`regionIndex<0`) | OPTIMISTIC | never considered — say nothing (HANDOFF §4 scope limit) |
| open flood (early-exit / backstop / out-of-box reject fired) | OPTIMISTIC (all) | component not proven complete — no harvest at all this build |
| in-box, unbuilt (`fragmentRecord==null`) | OPTIMISTIC | unclassified terrain — never claim |
| in-box, built, reached | SETTLED (cost) | in the component |
| in-box, built, unreached, an unbuilt/out-of-box neighbour | OPTIMISTIC | possible escape hatch — conservative |
| in-box, built, unreached, all in-box neighbours built, **closed flood** | **INFINITE** | provably disconnected (§2) |

---

## §7. Persistence — NONE (recompute-only, owner ruling 2026-07-25)

**Boxed-in is never persisted.** Reachability is a property of *(goal, start)*, not of the goal alone —
a bot standing *inside* the box reaches the "boxed-in" goal trivially. Persisting a boxed-in verdict
would therefore have to key on the **starting position**, not just the goal, and that (goal × start)
space blows up storage for no benefit, because the proof is **cheap to recompute** (the reverse flood is
box-bounded + backstopped, and only runs when we are already struggling — §4). So the `INFINITE` set
lives **only on the per-search `RegionCostField`** (already an ephemeral per-search structure), is
consumed by #5's prune *within that search*, and is discarded when the search ends. No
`RegionCrossingMemory` write, no restart oracle, no terrain-bump invalidation — there is nothing durable
to invalidate, so the permanent-cage risk is gone by construction.

**The durable rows in this arc are #1's crossing invalidations**, not boxed-in: a flood that hits (a)
irreversible-start or (b) no-progress durably invalidates the *first non-reached crossing*
(from-fragment-keyed via #3) through the existing evidence model. That is a property of a *crossing*
(unrealizable under budget), legitimately durable; boxed-in is a property of *(goal, start)*, which is
not. Keep the two mechanisms distinct.

---

## §8. Coarse boxed-in (owner's L3 add) and the "see obstacles from afar" invariant

Per FINDINGS §6 and §4.1: the **walk-only, no-break** coarse check is trivial and ships as the
front-line fast-filter (a sealed goal region at L3 is a massive obstacle seen from afar, serving that
invariant at `O(1)`). The **caps-general** coarse boxed-in proof is **not** answerable from existing
accessors (dig-out always available to break-capable bots; no per-face unbreakable-seal bit; coarse
collapse `fc==63` is optimistic — FINDINGS §6). It needs either a per-face unbreakable-seal bit on the
fragment record or the dedicated region-tier flood-fill connectivity pass — and that is precisely what
**#6 (capability-aware region graphs)** builds. So the caps-general coarse boxed-in is **deferred into
#6**, not attempted here; #4 stays at L0 (the general mechanism) + the L0-cheap coarse no-break filter.

---

## §9. Relationship to #6 — reduced-in-frequency, not replaced

#6 bakes capability into the graph so a no-cap bot's cliff shelf and cliff top are **different
fragments with no crossing** — the disconnection is **structural**, visible at construction, so the
region-A* **heap-exhausts (true BLOCKED)** without ever flooding, and #4's harvest never needs to run
for that case. But #4/#5 remain necessary: (a) as the **near-term** mechanism (no graph rebuild, ships
before #6); (b) for the **residual** #6 cannot cover — optimistic *unbuilt* terrain (both tiers stay
optimistic there by design), and coarse-collapse regions; (c) as the **block-tier** prune (#6 is a
region-tier construction; #5's hard reject still needs the INFINITE set to stop block-A* flooding). So:
**#6 reduces how often #4's harvest fires; #4/#5 remain the runtime proof + block-tier prune.** They
compose; #6 does not remove them. (Sequencing: ship #4/#5 first as the runtime safety net, #6 later as
the structural reduction — see ROADMAP.)

---

## §10. Invariants

- **BR-1 (soundness).** `INFINITE` is set only under the §2 conjunction; a false INFINITE is impossible
  by the optimism-⊇ + closed-flood + no-escape-guard argument. No reachable region is ever pruned.
- **BR-2 (optimism boundary).** Out-of-box, unbuilt, open-flood, and escape-hatch regions stay
  optimistic (§6). The harvest never claims anything outside a closed flood.
- **BR-3 (byte-identical non-pruned search).** #5 is a pure reject of INFINITE cells; no f-value on any
  non-INFINITE cell changes → non-pruned searches expand identically (perf-model bar).
- **BR-4 (terrain-change invalidation).** A durable INFINITE row drops on any chunk-version bump in its
  box (the FLAP-FIX recheck) — an INFINITE proof never outlives the terrain that made it true.
- **BR-5 (tick-confinement).** The harvest builds on the tick thread (like the field itself); the async
  planner reads the tri-state read-only via `SearchRequest`. No planner-pool thread touches
  `RegionGrid`.

---

## §11. Increments + test plan

- **Increment 1 — the L0 harvest + block-A* hard prune (journey-scoped, no durable persist).**
  Closed-flood detection (`LAST_FIELD_STATS[2]` + exhaustion flag), the harvest-mode flood (no
  early-exit), the INFINITE tri-state + `isBlocked`, the block-A* hard reject, and the region-A*
  crossing invalidation — all **in-journey** (no `RegionCrossingMemory` write yet). Wired to the
  anti-flap terminal-flood arbiter (§4.2).
  - **Acceptance:** on the boxed-in wall fixture, the block-A* **stops flooding** into the dead region
    (measure expansions — the cone-of-partials collapses); the give-up is emitted as a **structural
    BLOCKED** with the INFINITE proof, not a budget artifact; **0 repeat blames**. On a genuinely
    reachable-but-hard fixture, the flood is NOT closed / not INFINITE → no false prune, route still
    found (find-path-iff-exists regression).
  - **Soundness regression (the critical one):** a fixture where a route leaves and re-enters the box
    (corridor) must **not** produce INFINITE (the out-of-box reject fires → not a closed flood → no
    harvest). Assert `outOfBoxRejected` disables the harvest.
- **Increment 2 — the coarse walk-only fast-filter (§4.1).** `O(1)` sealed-goal detection for no-break
  bots; front-line before the L0 flood. Independent; its own repro (a fully-walled goal region).
- **Increment 3 — durable structural INFINITE persistence (§7), IF owner approves R2.** The
  `(to-fragment, caps)` negative row + terrain-bump invalidation + the `-KeepWorld` restart oracle
  (no-repeat + convergence; a corridor-opening terrain edit must drop the row).

**Headless seams:** the closed-flood conjunction and the INFINITE label are unit-testable on synthetic
`RegionGrid.headless` worlds (a walled pocket → INFINITE; a corridor-out → not; an unbuilt-neighbour
pocket → optimistic). The block-A* prune is testable via expansion counts on the wall scenario.

---

## §12. Perf accounting

- **The harvest flood runs to exhaustion, not early-exit** — more expensive than the heuristic field
  build (which early-exits on the fat skeleton). But it is bounded by the box + the 20k backstop, runs
  **only** at the terminal-flood arbiter (§4.2) — a rare event (once per genuinely-stuck journey, after
  anti-flap has walked to the closest launch), **gated behind the `O(1)` coarse no-break filter** which
  catches the common sealed-goal case without any flood. Not a per-tick or per-search cost.
- **The block-A* prune is a net WIN**: a hard reject of INFINITE cells *removes* the exact flood
  expansions (the cone of partials) that dominate a boxed-in search — the reject is one `isBlocked`
  read (an array index on the field already in cache) at the accept path, cheaper than the expansion it
  prevents. The `isBlocked` read is added to a branch-dense path (perf model warns on new
  data-dependent branches) — measure it does not regress the **non-boxed** scenarios (where `isBlocked`
  is always false → one predictable branch). JMH region + block suites, paired A/B; keep only on a
  boxed-scenario win with no healthy-scenario regression beyond noise.
- **The tri-state is a per-region byte on the field** (already allocated per build) — no new per-tick
  allocation.

---

## §13. Decisions log + For ratification

**Decisions:**
- **D1** — Harvest is "add-a-swept-condition then harvest", not "harvest existing" (FINDINGS §2). The
  three additions: closed-flood flag, INFINITE tri-state, no-escape guard.
- **D2** — Soundness rests on region-tier optimism (`Reachable_region ⊇ Reachable_block`) + closed-flood
  completeness + the no-escape guard (§2). INFINITE is a proof, never a heuristic.
- **D3** — `closedFlood = heap-exhaustion ∧ ¬earlyExit ∧ ¬backstop ∧ ¬outOfBoxRejected`; the harvest
  flood suppresses the fat-skeleton early-exit to drain fully.
- **D4** — #5 is a HARD reject at the block-A* accept path (replacing the dead soft-max); non-INFINITE
  cost values unchanged → byte-identical non-pruned search (BR-3).
- **D5** — #4 is the **arbiter** of anti-flap's terminal flood: INFINITE → structural BLOCKED (§8's
  boxed-in); not-INFINITE → budget artifact (anti-flap's journey reroute / acceptable give-up).
- **D6** — The coarse walk-only no-break filter ships as the `O(1)` front-line; caps-general coarse is
  deferred into #6.
- **D7** — #6 reduces the harvest's frequency but does not replace #4/#5 (§9).

**For ratification (genuinely owner-level):**
- **R1 — VERIFY the unbuilt-expansion behaviour** of `grid.ensureLeaf` during the flood. If the flood
  provably expands *through* optimistic-unbuilt regions, the no-escape guard (Leg 3) is redundant and
  can be dropped (cheaper, broader harvest). If not, the guard is required. Decide after the
  implementation-time verification; the guard is the safe default.
- **R2 — RESOLVED (owner 2026-07-25): NEVER persist boxed-in.** Recompute-only; reachability is a
  *(goal, start)* property, so persistence would need per-start keying (storage blowup), and it is cheap
  to recompute. The `INFINITE` set is per-search + ephemeral (§7). No durable rows, no restart oracle.
- **R3 — RESOLVED (owner 2026-07-25): harvest on flood-partial.** "If we're having trouble finding the
  goal, pause to make sure it's actually possible." The reachability check folds into the flood/partial
  decision point (§4), not a separate terminal-flood-only trigger.
