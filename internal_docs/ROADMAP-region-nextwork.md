# ROADMAP — the region-tier next-work SET (items #1–#6)

Status: **DRAFT for owner review of the SET, 2026-07-25.** This is the sequencing/interactions
preamble the HANDOFF (§F) asked for — the six items are a **coherent set, not silos**. Read this
first, then the per-item designs. After the owner reviews the SET, implementation proceeds
per-increment.

**The design docs (all in `internal_docs/`, authored on `core`):**
- `DESIGN-virtual-start-fragment.md` — #2 (start fragment S) + #3 (from-fragment/approach keys).
  Owner-ratified in dialogue; **§8 rewritten** this session per the 2026-07-24 correction.
- `DESIGN-anti-flap.md` — #1 (defer the blame to the closest launch). **New this session.**
- `DESIGN-boxed-in-reachability.md` — #4 (harvest negative reachability) + #5 (block-A* prune).
  **New this session.** Grounded in `FINDINGS-reverse-reachability.md` (the §F forensic).
- `DESIGN-capability-aware-region-graphs.md` — #6 (per-cap-axis structural flood). **New this session.**
- `FINDINGS-reverse-reachability.md` — the §F forensic that gated #4/#5.

---

## §1. The one sentence that ties the set together

Every item is a facet of **making a flood into a correctly-scoped proof**: a flood proves "the goal is
unreachable **from this position, via this approach, under these caps, within this box**" — and today
the code throws away or mis-scopes each qualifier. The set restores them:

| qualifier the flood is really scoped to | item that restores it |
|---|---|
| **from this position** (not from far away) | **#1 anti-flap** — defer blame to the closest launch |
| **from this fragment / via this approach** (not the crossing globally) | **#2/#3 virtual-start** — S node + from-fragment/approach keys |
| **within this box** → is it *actually* disconnected, or just budget-starved? | **#4 boxed-in** — the closed-flood harvest is the arbiter |
| don't waste block-A* expansion flooding a proven-dead region | **#5 prune** — hard reject on the INFINITE set |
| **under these caps** — fragment MEMBERSHIP is passability-based; it should be movement-based | **#6 caps-aware graph** — flood connectivity = the real movement predicates; preceded by a perf pass |

The HANDOFF's open question — *"#1/#2/#3 must agree with #4 on WHEN a flood becomes a persisted
proof"* — is answered by the set: **a flood becomes a *persisted* proof only when it is BOTH terminal
(anti-flap: the closest launch floods) AND structural (boxed-in: the closed harvest confirms
INFINITE).** A flood that is terminal-but-not-structural is a **budget artifact** — journey-scoped
reroute, no persist. A flood that is neither just advances. This is the unifying rule; §3 makes it
precise.

---

## §2. Sequencing (dependency order, each increment independently shippable/testable)

```
 #2/#3 virtual-start incr.1   ──►   #1 anti-flap incr.1   ──►   #4/#5 boxed-in incr.1   ──►   #6 caps-aware
 (blame is STRUCTURALLY               (blame is TIMELY:            (the ARBITER + the           (reduce how OFTEN
  correct: from-fragment,              closest-launch only;        block-A* prune; the           #1/#4/#5 fire, by
  fixes A==G collapse,                 kills the flap +            structural BLOCKED vs         making disconnection
  gives S→A→V to blame into)           budget give-up)             budget discriminator)         STRUCTURAL at build)
```

**Why this order:**

1. **#2/#3 first — it makes blame *correct*.** Without from-fragment/approach keys, every downstream
   blame still over-condemns (the A==G collapse: one blacklist row disconnects all V approaches,
   virtual-start §4). It also promotes the start into a first-class **S** node so a no-progress flood
   has a structurally-blameable first crossing (`A → V`, never `S → A`). Increment 1 is fully specified
   and owner-ratified; it is the foundation.
2. **#1 second — it makes blame *timely*.** Anti-flap blames *into* the from-fragment vocabulary #2/#3
   provides, but decides *when*: only at the closest reachable launch (adjacency, or the
   no-forward-progress bottom). This kills the route flap and the budget-laundered give-up. Depends on
   #2/#3 (it needs a correctly-keyed edge to blame) and on the shipped rolling-skeleton increment A (it
   reuses the forward-slide to walk the retained plan).
3. **#4/#5 third — it makes the give-up *honest* and stops the flood.** #4 is the **arbiter** that runs
   at anti-flap's terminal flood and decides structural-BLOCKED vs budget-artifact; #5 is the block-A*
   hard prune on the INFINITE set. Depends on #1 (it fires at the terminal flood) and supplies the
   **durable-proof half** of anti-flap's persistence decision (§3).
4. **#6 last — the *structural* cure, preceded by a performance pass (v2 design, owner-corrected).** #6
   makes the flood's connectivity test the **actual movement predicates** so a fragment is "cells
   *mutually reachable* by the movement vocabulary", not "connected air". A no-cap bot's shelf and
   cliff-top then become **different fragments** joined only by a one-way `Fall` crossing — the region
   tier routes around the cliff structurally, no flood. Two design keys (see `DESIGN-capability-aware-
   region-graphs.md` v2): (a) membership = **locally-mutual** components + **directed one-way crossings**
   (correct — separates fall-pockets; cheap — no Tarjan, since one-way free moves are strictly
   descending); (b) the 4 cap-combo graphs are **union-find overlays** on one capability-independent base
   flood (+break/+place add crossings, union where a crossing becomes mutual) — the owner's "no-capa
   graph, merge on added capability", **not 4 floods**. #6 is **performance-centric** and preceded by a
   **movement-check performance pass** (mask-backed predicates + per-node envelope precompute) that also
   speeds **search** — which is why #6 was always slotted *after* a perf pass. #6 *reduces* rather than
   *replaces* #4/#5 (which still own unbuilt-terrain optimism and collapsed mass) and **composes with**
   virtual-start (which makes the same merge *correct* reactively, so #6's split is a perf/accuracy
   optimization, not a correctness fix — its sequencing is measurement-gated, not urgent).

The four are a chain of increasing altitude: correct blame → timely blame → honest give-up → fewer
give-ups. Each ships and is field-verified on its own repro before the next. **The perf pass (movement-
check reuse) is a shared predecessor** — it lands before #6 and independently speeds search.

---

## §3. The cross-cutting decision the whole set depends on: persistence scope

This is the single most important thing for the owner to rule on, because it appears in three docs and
they must agree. It reconciles the **§8 ruling** ("a no-progress flood IS a persistable proof") with the
**ratified evidence model** ("realized-evidence rows only; start-region-scoped blames never persist").

**The tension (verified in code):** a terminal anti-flap flood realizes nothing and is launched from the
bot's own start region → under the evidence model it is start-scoped → per-plan blacklist only, never
durable (anti-flap §6; `onBlocked:688`). So the literal §8 "persist the flood" collides with the model.

**The recommended resolution (anti-flap §6 option C + boxed-in §7):** split the labor.
- **#1 anti-flap owns the *in-journey* reroute** — the terminal flood invalidates the scoped edge for
  the current journey (per-plan blacklist), fixes the flap, and does **not** persist. It is a budget
  artifact; it stays in-journey.
- **#4 boxed-in owns the *durable* proof** — a **structural** INFINITE (the closed reverse-flood proves
  a region is outside the goal's caps-conditioned connected component) is *realized negative evidence*,
  sound by construction, not a budget artifact. **That** is what persists (caps-keyed, terrain-bump
  invalidated), and only when the world is genuinely disconnected.

So "flood → proof → persist" is **unchanged in spirit** (the §8 ruling holds — a flood is a valid
proof) but **cleanly scoped**: the flood proves the in-journey reroute; the *structural* confirmation
(#4) is what earns durability. This keeps the evidence model's "no speculative persist" principle
intact. **If the owner prefers the literal §8 reading**, anti-flap §6 option B (a `PROV_NEG_REACH`
durable row from the flood itself) is the alternative — but it carries the permanent-cage risk the
`-KeepWorld` oracle exists to catch. **Recommendation: option C.**

---

## §4. Consolidated ratification calls (one place for the owner)

Pulled from the per-doc "For ratification" sections so the owner can rule on the SET in one pass:

**Set-level (decide first — they gate the others):**
- **RS-1 — persistence scope: option C** (§3; anti-flap §6). #1 = journey-scoped reroute, #4 = durable
  structural proof. *Recommended.* Alternatives A (journey-only everywhere) / B (durable flood rows).
- **RS-2 — sequencing** (§2): #2/#3 → #1 → #4/#5 → #6. Confirm, or reprioritize (e.g., #6 earlier if
  the caps-blindness is judged the higher-value root cause — but it is the largest build).

**#2/#3 virtual-start (already ratified in dialogue; confirm the increments):**
- Increment 1 = S node + per-exit seeds + approach-conditioned V-edges (entry-keyed) + structural
  blame-skip. Increment 2 = from-node cameFrom keying (two-hallways). R1: S identity (VIRTUAL_START_FRAG
  sentinel vs ENTRY_START promotion). R2: per-face S-exit seeds (flap damping) vs single S→A edge.

**#1 anti-flap:**
- R1 = persistence scope (folds into RS-1). R2 = `ANTIFLAP_ADJ = 1 region`. R3 = held-target clear
  conditions.

**#4/#5 boxed-in:**
- R1 = **verify** whether the flood expands *through* optimistic-unbuilt regions (drops the no-escape
  guard if so). R2 = durable structural INFINITE persistence in increment 1 or deferred to increment 3
  (*recommend defer*). R3 = harvest trigger breadth (terminal-flood-only vs also proactive mid-search).

**#6 caps-aware (largest; v2 design — the movement-predicate flood, `DESIGN-capability-aware-region-
graphs.md` §14):**
- **R1 — perf-pass-first.** The movement-check performance pass (mask-backed predicates + per-node
  envelope precompute) ships first, on search, JFR-driven — independently valuable and de-risks #6's
  flood cost. Confirm vs co-designing it into #6. (Leaning: perf-pass-first.)
- **R2 (core model) — locally-mutual membership + one-way directed crossings.** Confirm this reading of
  "internally mutually reachable" (vs undirected-merge, which mis-merges fall-pockets; vs full SCC, which
  is unnecessary because one-way free moves are strictly descending).
- **R3 — node domain** (occupiable-position flood) + the floor↔fragment cell convention.
- **R4 — persistence:** persist base fragments + one-way crossings, recompute the capability overlays
  lazily (one set on disk, not four) vs persist all four corners.
- **R5 — the ~38 ms is NOT in the repo** (pre-repo memory). Re-establish under current code via
  `ChunkBuildBenchmark` before treating it as the budget ceiling — is there a saved scenario to
  reproduce? The lattice IS settled (union-find overlay, not 4 floods; base is capability-independent).

---

## §5. What is already true (don't re-litigate)

- **Rolling skeleton increment A is SHIPPED** (mc-1.21 wt, suite green) — the forward-slide/extend the
  anti-flap and virtual-start designs build on. Increments B/C/D deferred (DESIGN-rolling-skeleton.md
  §13).
- **The FLAP FIX is verified working** (per-chunk terrain-recheck; HANDOFF §0) — it is the
  terrain-bump invalidation trigger #4's durable proofs reference (boxed-in BR-4).
- **The reverse flood `costToGoalField` is L0-only, caps-conditioned, box-confined edge-expansion** —
  the harvest is "add-a-swept-condition then harvest", not "harvest existing" (FINDINGS).
- **Owner invariants (HANDOFF §5) bind all six:** find-path-iff-exists, prove-nonexistence, no world
  assumptions, no baked capability assumptions, principled/generic (no per-WORLD-feature special-casing),
  benchmark anything hot (paired A/B), no arbitrary timers, no recovery machinery that masks movement
  bugs (fail→HOLD, loud), region tier stays tick-confined.

---

## §6. Open forensic threads for implementation time (not blocking the SET review)

- **Boxed-in R1** — the unbuilt-expansion behaviour of `grid.ensureLeaf` inside the flood (decides the
  no-escape guard). Verify before #4 increment 1.
- **#6** — the WHERE-cap-changes-connectivity analysis (vertical up = place-gated vs the geometric
  flood's 6-connectivity) must be code-verified before #6 increment 1; see its design.
