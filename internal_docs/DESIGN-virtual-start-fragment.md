# DESIGN — The Virtual Start Fragment (and honest "blocked" semantics)

Status: **RATIFIED 2026-07-26 (from-fragment-in-KEY) — see §0.5, which SUPERSEDES the increment-1/2
split (§3/§4/§7) and design ruling D3 below. Earlier §§ kept for provenance.**
Relationships: completes the symmetry of the **virtual goal fragment** (HPA-CASCADE.md §"The virtual
goal fragment"); supplies the skeleton vocabulary the **rolling skeleton** (DESIGN-rolling-skeleton.md)
lacks at its terminal window; extends the **evidence model** (DESIGN-persisted-invalidation-memory.md);
and feeds the future **boxed-in-goals** arc by making "BLOCKED" mean only what that arc needs it to mean.

---

## §0. TL;DR

Wrap the search *start* in a virtual fragment **S**, mirroring the virtual goal **V**. Every search's
skeleton then begins `S → A → …` (A = the fragment physically containing the bot) and ends `… → G → V`
(G = the fragment containing the goal; possibly `G == A`). This buys three things:

1. **A structurally-blameable first crossing.** A no-progress flood always has a real edge to invalidate
   (`A → V`, or the first unrealized hop), so the region tier stops emitting *"exhausted its repairs"*
   when it never actually **proved** anything.
2. **Region-tier vocabulary for the "which way out" decision**, which today the block tier re-litigates
   from scratch every replan — the cause of the 615-search route-flap observed at the cliff base.
3. **A principled home for the evidence model's start-region carve-out**: S-rows are journey-scoped *by
   construction*, exactly as V-rows are.

The load-bearing enabler (owner's catch) is that the `A → V` invalidation must be **approach-conditioned**
— because `A → V` is dead only *from the S-side approach*, and remains valid from the staircase approach.

---

## §0.5. RATIFIED DESIGN (2026-07-26) — from-fragment lives in the SEARCH NODE KEY

> This section is the design of record. It **supersedes** the increment-1 (entry-face on the row) /
> increment-2 (from-node row keying) split in §3/§4/§7, and **supersedes ruling D3** (which kept the hot
> node key at 59 bits). Ratified with the owner in dialogue 2026-07-26. Verified against source
> (`RegionPathfinder.java`, `RegionAddress.java`, `RegionEdgeBlacklist.java`, `RegionCrossingMemory.java`,
> `CostPyramidCodec.java`, `PathPlan.java`) — file:line evidence in the session transcript.

**THE DECISION.** Put the **from-fragment** (the fragment the search last hopped from) into the **search
node identity** — `(region, current-fragment, entry-face, from-fragment)` — NOT merely on the invalidation
row, and NOT in a fat value.

**WHY key-level, not row-level (corrects §6).** §6's claim that from-node *row* keying discriminates the
two-hallways case "at the evidence layer… efficiency not correctness" is **WRONG for a single search**.
Two hallways H1→Room1, H2→Room2 that flood into ONE fragment F (joined by a 1×1 gap a no-cap bot can't
cross), both entering F on the same face: the search interns both approaches to the **same node**
`(F, +X)` and keeps only the cheaper (H1). Even an *exact* row `(H1,F,V)` doesn't help — after it's
blamed, re-expansion re-derives the same `(F,+X)` node (H1 still cheaper, already closed) and **never
offers F→V via H2** → false give-up. Only making `(F,+X,from=H1)` and `(F,+X,from=H2)` **distinct nodes**
lets the search try H2 after H1 is blamed. The from-fragment must be in the KEY.

**Accepted cost + its retirement.** From-fragment in the identity means one `(region,fragment)` can be
visited once per `(entry-face, from-fragment)` — extra scans in flood-over-optimism regions. Owner ruling:
those scans are **legitimate proof work** (the flood merged two real routes; the search must prove which
reach the goal). **#6 (capability-aware fragments)** splits F by real traversability, eliminating
"two routes into one fragment from the same face" — so the extra scans evaporate. **This reframes #6 from a
correctness prerequisite into a PERFORMANCE optimization.**

**S is a from-fragment SENTINEL VALUE, not a skeleton node (resolves R1/R2/B).** The search root (bot's
fragment A) has no predecessor → its from-fragment = **`VIRTUAL_START_FRAG = 62`** (the free 6-bit id;
62 was reserved, 63 = `VIRTUAL_GOAL_FRAG`). Consequences:
- `(A|from=S → V)` vs `(A′|from=B → V)` are distinct **by construction** — the cliff A==G collapse is gone.
- **Journey-scoping is structural (INV-3 for free):** a `from=S` row names a journey-local sentinel (S
  differs next `/bot goto`) → inherently un-persistable; `from=<real fragment>` crossings are world
  knowledge and persist. No positional carve-out.
- **No separate S skeleton step, no per-face S→A exit seeds** for correctness. The per-face "which-way-out"
  damping (pathology **b**, the flap) is NOT a correctness need and stays with **item #1 (anti-flap)**.

**KEY LAYOUT (repack + version bump; disk is a cache, no wild copies).** `ry` needs only **5 bits** (32
level-0 Y-regions, `minY`-offset non-negative → bit 5 of the `ry` field is always 0). Defrag `packLevelKey`
(`ry` 5b, `rz`/`rx` shift down) so free bits are contiguous at the top, then:
`region(49) + current-fragment(6) + entry-face(3) + from-fragment(6) = 64` bits, no spare. **Entry-face is
retained** — from-fragment-ID is region-scoped, so the face is needed to say *which neighbor region* the
predecessor sits in; face + from-fragment-ID together name the predecessor compactly (full predecessor key
would be 56 bits — too big). Repacking `packLevelKey` moves the physical-key bit positions → persisted
`RegionCrossingMemory` rows change format → **version bump, rebuild-from-live** (the code already treats
`hpa.bin`/`res.bin` as a cache).

**ROW keying.** The **V-approach** row keys on the full node key (from-fragment included) — session-only
(V-rows are already journey-scoped OUT of persistence via the `virtualGoalHop` guard). **Regular crossing**
rows stay **physical `(region,fragment)` pairs, entry-independent** ("a dead crossing is unrealizable
regardless of how the FROM region was entered" — `relaxFrag` comment) — so the persisted format is
unchanged *except* for the `packLevelKey` bit-position shift. `RegionEdgeBlacklist` stays a `(long,long)`
pair-set (it stores opaque longs; the from-side long now simply carries from-fragment for V-approach rows).

**BLAME ANCHOR (resolves A).** `PathPlan.blameHop`'s resume-anchor scan (which finds "where the bot is" to
avoid blaming hops behind it — the wall-repro treadmill guard) currently uses region-key equality and so
mistakes the unreached goal V for the bot's position when A==G (V shares the goal region) → `-1` give-up.
Fix: the anchor scan **skips VIRTUAL fragments (S and V)** — the bot cannot be *in* S (a null move already
put it in A) nor *in* V (arriving ends the journey). We still blame the hop *into* V. This is a structural
"virtual nodes aren't physical positions" rule, not a "refuse to blame" carve-out.

**S is stable.** S is the journey root (like V is the journey end); sliding the window does NOT move S.
Invalidations conditioned on `from=S` are trustworthy only while S is stable (the journey) — which is
exactly why they don't persist. If the WHOLE skeleton is rebuilt (L1 slide), S is re-established at the
bot's then-current start; that boundary is out of scope here (flagged for the rolling-skeleton arc).

**Edit surface (grounded, for the plan).** (1) `RegionAddress.packLevelKey` repack + all unpack/mask sites
+ `RegionCrossingMemory.REGION_MASK` + persistence version. (2) `RegionPathfinder.searchKey`/`fragmentKey`
gain the from-fragment field; `Nodes.intern` + the SoA carry it (or derive it from `frag[parent]`). (3) the
relax sites (`relaxFrag`, `relaxVirtualGoal`) stamp `from-fragment = frag[curRow]` on the new node; the
root is stamped `VIRTUAL_START_FRAG`. (4) `PathPlan.blockedHop` mints the add-side key with
`from-fragment = skeleton.fragmentId(hop-1)`; `PathPlan.blameHop` skips virtual fragments in the anchor
scan. (5) region-tier **paired A/B perf gate** + fixtures: cliff reroutes at DEFAULT budget, wall gives up
honestly, up-cliff passes, plus new **spiral-staircase** (fragment-ID already disambiguates stacked steps)
and **two-hallways** (from-fragment disambiguates) correctness fixtures.

**Review refinements (2026-07-26, post-grounding).**
- **Row = the FULL node key; entry-face is DERIVED, not stored.** `RegionPathPlan` keeps only per-step
  region+fragment, so the blame add-side (`blockedHop`) reconstructs the approach's entry-face
  geometrically: region hops are single-axis, so `entry = face(region(hop-1) → region(hop))`
  (`ENTRY_START` at the root `hop-1<0`; `ENTRY_INTERIOR` when same region) — identical to what the search
  stamped. Keeping entry-face in the row (vs. a reduced `(region, frag, from-frag)` key) avoids a rare
  **over-invalidation** when two predecessors share a fragment-id but enter A from different faces
  (fragment ids are region-local, so the collision is realistic). No skeleton schema change.
- **Reverse-Dijkstra field gating (correctness).** `relaxFrag` is shared with the goal-rooted reverse
  cost field (`dijkstra=true`); splitting its nodes by from-fragment would blow it up / corrupt queries.
  Pass the `VIRTUAL_START_FRAG` constant when `dijkstra` → the field stays byte-identical
  (`RegionFieldBuildBenchmark` flat proves it).
- **Version = RESET to 1, not bumped** (no wild copies of Orebit exist). Stale `hpa.bin`/`res.bin` version
  mismatch → rebuild-from-live; clear stale autotest/run caches. `RegionEdgeBlacklist` needs no structural
  change (opaque `(long,long)` pair-set; the from-side long now carries from-fragment bits for V rows).
- **from-fragment storage:** STORE a `Nodes.fromFrag[]` SoA field (one write in the cold region tier, no
  per-read branch).
- **Top silent-breakage guard:** rebased `ry` (0..31, 5 bits) must be unpacked with a PLAIN MASK, never
  `signExtend` (bit 4 would flip 16..31 negative → key corruption). `ry` is provably non-negative.

**Post-implementation review (2026-07-26).** Steps 1-4 implemented + compiled green (javap-verified);
`VirtualStartFragmentKeyTest` 7/7 pass (two-hallways rows distinct + blacklist distinguishes; A==G
approaches distinct; blame leaves the staircase approach alive; spiral stacked-frags distinct). Two
adversarial reviews found no functional defect (add/check key parity holds field-by-field via the shared
`approachRowKey`; reverse Dijkstra field gated byte-identical; no from-fragment bits reach disk). Three
carry-forward items:
- **`targetStep` edge case (latent, NOT the cliff).** `blockedTargetStep = windowTargetStep = choice.step`
  from `WindowTargeting`. For a **strictly-same-leaf-region goal**, the GOAL branch can return
  `step = windowStart`, making `hi ≤ windowStart` → the `-1` give-up returns *despite* the anchor skip. The
  cliff fixture is unaffected — its goal sits one Y-region above the start (start region `4,7,-5`, goal
  `4,8,-5`), so `targetStep = V's index = 1`. Do NOT paper over with an `hi`-extension guard (it would blame
  hops the search was never asked to reach). Confirm at the autotest gate.
- **State-space (accepted degradation, retired by #6).** from-fragment multiplies per-cell node identity
  from ≤8 (entry-face) to ≤8×62. The `CAP_SAFE_NODES` box bound no longer strictly bounds *in-box* node
  count, so a dense merged-fragment wall can hit `MAX_REGION_EXPANSIONS` and return a partial where it
  previously FOUND (bounded — ~≤496 block-tier floods before an honest give-up, never infinite). This is the
  "legitimate proof work" the owner accepted; #6 (capability-aware fragments) retires it. WATCH at the perf
  gate; if a real fixture partials-out, decide raise-cap vs accept.
- **Parity rests on single-axis L0 hops.** `approachEntryFace`'s FROM−TO delta reconstruction assumes
  region hops are single-axis (true for L0 skeletons). A multi-axis coarse hop entering an L0 skeleton would
  silently diverge from the stamped face — add an assert if that's ever introduced.

**Deferred gate (machine-gated — run when the dev machine is free).** Region A/B JMH
(`RegionPathfinderBenchmark` bounded delta; `RegionFieldBuildBenchmark` FLAT proves the dijkstra gating) +
headless autotests: cliff **reroutes at the DEFAULT 10k budget** (the acceptance test), wall gives up
honestly, up-cliff still passes.

---

## §1. Motivation — two pinned pathologies (the cliff corner)

Fixture: Gather Issues Repro, no-capability bot (`canPlace=canMine=false`), `(70,63,-68) → (77,72,-78)`.
The bot starts in the **same region and same fragment** as the goal; the goal sits on top of a cliff and
the only capability-legal route is the long walk around to a staircase on the far side.

**Pathology (a) — the false give-up (no hop to blame).**
The skeleton degenerates to a single hop (start fragment == goal fragment). The block tier floods trying
to climb the cliff face directly, returns a no-progress budget-fail, and the cascade reports
`navigation gave up (region tier exhausted its repairs)` — **though nothing was proven**. Evidence: the
same search FINDS a 79-waypoint route at a 40k node budget (`battC2`); at the default 10k sync pin it
floods. The give-up is a *budget* artifact laundered into a *reachability* claim.

**Pathology (b) — route flap.**
Raise the budget so the give-up disappears and the next layer surfaces: with a trivial one-hop skeleton,
*every* 40-tick recheck re-decides the entire route at the block tier. Two near-equal 40k-node routes
exist from the beach; successive searches alternate; the bot oscillates at the spiral's base
(`bestDistXZ = 9.02`, 615 searches, positions bouncing `(60,62,-106) ↔ (63,62,-109)`) and never commits.
The missing damping is **skeleton-level commitment** — which requires the region tier to have an opinion
about this stretch, which requires it to have *nodes* here, which it does not today.

Both pathologies share a root: **when start and goal share a fragment, the region tier has nothing to
say, so a fundamentally region-scale problem (route around a large obstacle) is dumped on the block
tier**, whose only tool is to flood. Flooding is, was, and remains the cause of most of our pain
(design principle §11). The cure is to give the region tier vocabulary here — a start node with exits.

---

## §2. The model (owner's S / A / B / G / V)

- **S** — virtual start fragment. Journey-positional; no persistent identity (like V).
- **A** — the real fragment physically containing the bot's start cell.
- **G** — the real fragment containing the goal cell. **May equal A** (the cliff case).
- **V** — virtual goal fragment (existing).
- **B, C, …** — neighbour fragments the skeleton routes through.

Minimum skeleton: **`S → A/G → V`**. In the cliff case this reads "virtual-start → containing-fragment →
virtual-goal", and the middle `A → V` traversal is what floods.

**The invariant on a no-progress flood (INV-1).** The blame target is the **first crossing the search
failed to realize**, which is `A → V` — *never* `S → A`. Blaming `S → A` would trap the bot in its own
start fragment forever; the existing blame-walk start-position skip already refuses hops at/behind the
search start, and S makes that refusal *structural* (see §5) rather than positional.

**The approach condition (INV-2, load-bearing).** The invalidated edge is `A → V` **conditioned on the
approach** — "`A → V` *entered from S*". Because `A → V` entered from the staircase-side neighbour is
perfectly valid (had the bot been standing on the cliff top, the search would never have flooded). An
*unconditioned* `A → V` blacklist is a correctness bug: see §4.

After invalidating `(A → V | from S)`, the region tier re-derives a longer skeleton:

```
S → A → B → … → A(re-entered from the staircase face) → V
```

This is **expressible today** because a search node is `(region, fragment, entry)` — the same
`(region, fragment)` may legitimately appear twice under different entries (the committed-advance fix
already documents this). The re-entry into A is a *different node* than the start-side A, so the
now-valid `A → V` (from the staircase face) is offered while the dead `A → V` (from S) stays blacklisted.

**The self-correcting optimism.** The flood merged the shelf and the cliff-top into one fragment A (a
no-cap bot cannot actually pass between them, but the region tier can't see that). The design does **not**
try to split A. Instead, **approach conditioning carries the missing pocket identity**: enter A from the
staircase face and the from-staircase `A → V` realizes; enter from S and it does not. The merged-pocket
optimism self-corrects through invalidation — the standing optimism-plus-invalidation bargain (PRD §6.3).

---

## §3. Keying — what the code actually is (verified), and the one gap

Verified against `RegionPathfinder.java`:

- **Physical node key** `fragmentKey` = `packLevelKey(rx,ry,rz)` (bits 0..49) XOR `frag<<50` (bits 50..55)
  = **56 bits**, `(region, fragment)`.
- **Search node key** `searchKey` = physical XOR `entry<<56` (bits 56..58) = **59 bits**,
  `(region, fragment, entry)`. **Bits 59..63 are free** (5 spare bits in the long).
- The **3-bit entry field already uses all 8 values**: faces `0..5`, `ENTRY_START = 6`,
  `ENTRY_INTERIOR = 7`. **The owner's proposed "SAME_REGION" already exists as `ENTRY_INTERIOR`** — it is
  the entry stamped on a node reached by an intra-region mine edge. S-exits and V-approaches reuse it.
- **The current start node is already `(A, ENTRY_START)`** — a *proto* virtual-start. This design promotes
  that sentinel into a first-class **S node with its own exit seeds**, and (critically) fixes the blame/row
  layer so the promotion pays off.

**The one gap — the row layer strips entry.** Invalidation rows are pairs of *physical* `fragmentKey`s
(`RegionEdgeBlacklist.add/contains(fromKey, toKey)`; `RegionCrossingMemory` rows likewise). Entry never
leaves the search (`fragmentNodeKey` is physical by design). This is fine for face-distinct crossings but
is exactly the A==G bug: `relaxVirtualGoal` checks
`blacklist.contains(fragmentKey(cur), fragmentKey(V))` — **entry-stripped** — so when `A == G`, *every*
approach to V from A collapses to the single row `(A → V)`, and one blacklist entry disconnects V
entirely. §4 is the fix.

**Design ruling (keying):** the hot search node key **stays 59 bits, unchanged** — the 64-bit budget is
why entry is a compact 3-bit face rather than a full from-node, and widening it would touch the
open-addressed maps and SoA rows on the per-node path (perf model: no hot-loop key growth). Approach
fidelity is added at the **row layer only**, which is cold (blame path). Two increments (§7):

- **Increment 1** — retain the approach's **entry** in the V-approach (and S-exit) rows. For the cliff
  A==G case this suffices, because the S-side approach and the staircase approach differ in *entry*
  (S-side = `ENTRY_START`/interior; staircase = a real face). This is the load-bearing fix.
- **Increment 2** — key rows on the **actual prior node** recovered from the `cameFrom` chain at record
  time (full `(from-region, from-fragment) → (to-region, to-fragment)` fidelity, no node-key widening).
  This subsumes the previously-ratified **entry-face-keys** arc (a from-node implies its entry face via
  the region delta) and handles the owner's "two hallways" case (§6) that entry-alone cannot.

---

## §4. The load-bearing fix — approach-conditioned V-edges (INV-2 in code)

**Bug, confirmed:** `relaxVirtualGoal` keys the per-approach blacklist on the entry-stripped
`fragmentKey(cur)`. When `A == G`, `fragmentKey(A-entered-from-S) == fragmentKey(A-entered-from-staircase)`,
so blacklisting the S-approach kills the staircase approach → V has no surviving in-edge → region-tier heap
exhaustion → the give-up. This is *precisely* the `V`-row blame seen preceding the cliff give-ups
(`gen=5 … hop=?->S0(4,8,-5:f0)`).

**Fix (increment 1):** the `(approach → V)` blacklist row retains the approach's distinguishing identity.
The minimal form keeps the **entry** on the approach side of the row:

- Record `(searchKey(approachPhys, approachEntry) → V)` instead of `(fragmentKey(approach) → V)`.
- Check the same at relax time.

Then `(A|ENTRY_START → V)` and `(A|face → V)` are distinct rows; blaming the S-side approach leaves the
staircase approach alive, and the region A* re-derives the go-around skeleton.

**Symmetric on the S side:** an S-exit is `(S → A|entry)`; if a *particular* exit is later proven dead
(rare — S→A is trivially realized because the bot stands there), it is journey-scoped and independently
blacklistable, never collapsing the others.

**Why entry is enough for the cliff:** the correct route re-enters the goal region **through a different
face** than the direct S-side approach — the staircase deposits the bot at the top from an adjacent
region. Face-distinct ⇒ entry-distinct ⇒ increment 1 discriminates it. The residual case that entry
cannot split (same face, same neighbour region, two distinct approach fragments) is §6, increment 2.

---

## §5. S mechanics

**Identity.** S is journey-positional: it exists only for the current search/journey and has no cell of
its own. Its physical key can reuse the start region's coordinates with a reserved fragment sentinel
(mirroring `VIRTUAL_GOAL_FRAG = 63`; a `VIRTUAL_START_FRAG` sentinel, or the existing `ENTRY_START` stamp
promoted — see the ratification list). It never persists (INV-3).

**Exit seeds.** S connects to A via per-**exit** seeds, mirroring V's per-**approach** seeds
(`buildGoalApproaches` / `DigSeedSet`). The exits are the crossings reachable from **the bot's flood
component** inside A — which the L0 flood / containment-anchor machinery already computes
(`RegionGrid.containedFragment` / `startFragmentByFlood`). The minimal seed is the single `S → A` edge
(entry = interior); the richer form seeds `S → A(via each face the bot's component touches)` so the region
tier can choose *which way out* as a first-class skeleton decision — this is the damping that kills the
route flap (§1b).

**Realization.** `S → A` is realized by construction (the bot stands in A). It is therefore the hop the
blame walk **always skips**, which is the structural replacement for today's positional start-region skip:
instead of "skip every hop whose FROM is in the search-start region" (which wrongly skipped `A → V` when
A is the start region — the "no hop to blame" corner), the walk skips **only the `S → A` virtual hop**.
`A → V` is now one hop past the virtual start and is blameable. **This is the direct fix for pathology (a).**

**Journey scoping (INV-3).** S-rows and the entry-conditioned `(A→V | from S)` rows are journey-scoped
*by construction* — S has no persistent identity, so a row naming it cannot be world knowledge. This
**replaces** the evidence model's current positional heuristic ("FROM == search-start region ⇒ don't
persist") with a structural rule, and composes with the existing V-row scoping unchanged.

---

## §6. Accuracy: the "two hallways", and the accepted degradation

**Two hallways (increment 2).** A goal region with two air pockets joined by a 1×1 hole floods to one
fragment, but a no-cap bot cannot pass between them. If a neighbour region has two hallways approaching
the two sides of the dividing wall — **same face, same neighbour region, two distinct approach
fragments** — then one approach is valid and one is not, yet entry-face-keying merges them. Increment 2's
**from-node row keying** (cameFrom-recovered `(from-region, from-fragment)`) discriminates them at the
evidence layer, where it matters, with zero hot-loop cost. (Note the *rows already carry the from-fragment*
via the from-side `fragmentKey`; what increment 2 adds is retaining it on the **V/approach** rows that §4
currently keys by the approach node — so the fix is "don't strip", not "widen".)

**The mod-3 world (accepted degradation, owner-ruled).** A world of alternating stone/air/air planes
forces multi-hop *intra-region* dig chains — several `(A,frag_i) → (A,frag_{i+1})` moves all stamped
`ENTRY_INTERIOR`. A 3D maze of such regions can legitimately require re-entering a region from a different
intra-region fragment, which a single `ENTRY_INTERIOR` value cannot distinguish → the **search** may merge
some node identities and misprice. **Ruling:** this degrades *efficiency*, not *correctness* — the **row
layer stays exact** (cameFrom-derived pairs), so invalidation still converges; the bot is slow on
adversarial dig-mazes, never wrong. Named and accepted.

**Scoping argument (why it's safe to stop here).** The only intra-region crossings are `S → A`, `G → V`,
and dig-out. `S → A` and `G → V` are **bounded and terminal** — no onward connections — so they cannot
participate in the pathological chains. Only dig-out chains can, and a **dig-capable bot rarely floods**
(its block-tier graph is dense; it powers through walls rather than flooding around them). The pathology
window is precisely *no-cap bots on dig-mazes*, and a no-cap bot cannot dig-out. The case closes itself.

---

## §7. Increments & battery gates

**Increment 1 — S node + exit seeds + approach-conditioned V-edges + blame integration + taxonomy.**
- Promote the start node to an explicit S with per-exit seeds; make the blame-walk skip structural
  (`S → A` only).
- Retain approach entry on the `(approach → V)` and `(S → A)` rows (§4); journey-scope both structurally
  (§5).
- Taxonomy rename (§8).
- **Acceptance test (the honest one):** the cliff fixture must **PASS at the DEFAULT 10k sync budget** by
  *rerouting* (region-tier invalidation grows the skeleton around the cliff), **not** by brute-force
  budget. If it only passes at 40k, increment 1 has not done its job.
- **Flap test:** the 615-search oscillation at the spiral base must collapse to a committed route (a
  stable skeleton; per-window searches small).
- Regression battery: `-KeepWorld` oracle PASS (no re-blames of persisted rows; S/V rows absent from
  disk — byte-verified); wall fixture semantics unchanged (true BLOCKED still terminates); full suite green.

**Increment 2 — from-node row keying via cameFrom (subsumes entry-face-keys).**
- Rows recorded as `(from-node → to-node)` recovered from the surviving `cameFrom` forest at blame time.
- Handles the two-hallways case (§6); a ravine fixture with a teleport-both-sides oracle is the test
  (inherited from the entry-face-keys arc it replaces).

**Perf accounting.** S/V edges are per-search **cold** (a handful per search, like the existing goal
approaches). No hot-loop node-key change (the 59-bit key is untouched). Row recovery from `cameFrom` is
cold blame-path work. The JMH search benchmarks should be **flat** (same expansions; the seeds add a few
cold edges) — state and verify, don't assume.

---

## §8. Taxonomy — a flood is a SCOPED proof, and "BLOCKED" means boxed-in

> **[REWRITTEN 2026-07-24 per owner ruling — supersedes the earlier "BUDGET-UNPROVEN =
> don't-persist" framing, which was WRONG.]** A no-forward-progress flood **IS a valid, persistable
> proof** — "the goal is unreachable within budget **from here**". Persist-vs-not is **UNCHANGED**:
> flooding → proof → persist stays. The two real defects are **scoping**, and both are fixed by
> sibling designs in this set, not by refusing to persist.

**The owner's proof-semantics critique, restated correctly.** Today the flood → give-up path condemns
too much, too soon, from the wrong vantage. The flood *does* prove something real — it is not "we ran
out of information"; a search that expanded its whole budget and never advanced toward V has
**demonstrated** that no route reaches V under that budget from the position it started at. The bug was
never that we persisted a proof; it is that the proof was scoped to the whole crossing (globally, all
approaches) when it only supports a claim about **one from-position** and **one from-fragment**.

**The two scope defects and their fixes (both are sibling designs in this set):**

- **Position over-scope → ANTI-FLAP (item #1, DESIGN-anti-flap.md).** A slid-window flood proves only
  "target unreachable **from this launch position**". The bot may be standing far short of the target,
  where the region tier's honest sub-goal is still a distant portal; a flood there says nothing about a
  launch *adjacent* to the target. **Fix:** retain the last non-flooded plan, walk it forward one
  region, and re-attempt the far target from each successively-closer committed position; conclude the
  proof only when the **closest reachable position** (adjacent to the target) also floods. Only *that*
  flood is the terminal, persistable "unreachable from here" claim. (This is the natural extension of
  the rolling skeleton's per-crossing forward advance — DESIGN-rolling-skeleton.md — kept aimed at the
  far goal until the closest launch also fails.)

- **Approach over-scope → FROM-FRAGMENT KEYS (item #3, §4 of this doc).** A proof condemns only the
  **FROM fragment / approach**, never the crossing globally. This is exactly the load-bearing
  approach-conditioning of §4: `(A → V | from S)` dies while `(A → V | from staircase)` survives.
  Increment 1 keys the row on the approach entry; increment 2 on the cameFrom from-node (§6). An
  *unconditioned* condemnation is the A==G collapse bug (§4) — one blacklist row disconnecting every
  approach to V.

**With both scopings in place, the taxonomy is a two-way split — never "don't persist":**

- **SCOPED-PROOF (re-derive, and persist scoped).** A flood that is *terminal* under anti-flap (the
  closest launch also floods) yields a proof keyed to `(from-fragment, approach)` — and, once #4's
  negative-reachability harvest lands, cross-checked against the goal-rooted reverse-Dijkstra's
  infinite-cost set (DESIGN-boxed-in-reachability.md). The region tier invalidates that scoped edge,
  **persists it** at the from-fragment scope the evidence model already supports for realized rows
  (DESIGN-persisted-invalidation-memory.md — see the persistence-scope note below), and **re-derives**
  the skeleton around it. Each subsequent window search is small. This is escalation-by-invalidation,
  the region tier's whole purpose. Non-terminal floods (anti-flap has forward positions left) persist
  **nothing** and simply advance — there is no proof yet, so there is nothing to record.

- **BLOCKED (boxed-in).** Genuine region-tier **heap exhaustion**: every caps-legal approach to V has
  been eliminated (as an invalidated edge, or as an infinite-cost region from #4) and V has no
  surviving in-edge. *This* is boxed-in — the honest give-up the future **boxed-in-goals** arc (#4/#5)
  consumes, now backed by the reverse-Dijkstra negative-reachability proof rather than an unproven
  budget artifact. Nothing else may claim it. `"exhausted its repairs"` fires **only here**.

**Persistence-scope note (resolved jointly with #1/#3/#4, flagged here, not decided here).** The
evidence model persists *realized-evidence* rows and journey-scopes S/V rows by construction (§5,
INV-3). A terminal flooding proof is **negative** evidence — nothing was realized — so it does not enter
as a realized-crossing row. It is world-meaningful **iff** keyed at the from-fragment with the bot's
capability axis (a no-cap bot's "can't climb out of fragment A toward V" is durable world knowledge, not
a journey artifact), which is precisely what from-fragment keying (#3) + capability-aware graphs (#6)
provide, and what #4's reverse-Dijkstra harvest lets us assert *without* a speculative persist. The
exact persisted row kind (a new negative-reachability provenance vs. reuse of the crossing-memory row
with a negative flag) is decided in the anti-flap and boxed-in designs, which own the evidence-model
interaction; §8 only fixes the *ruling* (persist the scoped proof; the earlier don't-persist framing is
withdrawn). See the SET preamble (ROADMAP) for how #1/#3/#4/#6 divide this responsibility.

Result statuses and log lines follow the two-way split. Escalation between resolution levels stays the
existing cascade machinery; the *skeleton growth within a level* is the escalation this design adds.

---

## §9. Invariants (checkable)

- **INV-1** — a no-progress flood blames the first *unrealized* crossing, never `S → A`.
- **INV-2** — every `(approach → V)` and `(exit ← S)` invalidation is approach-conditioned; blaming one
  approach never disconnects another (regression: A==G must keep the staircase approach alive after the
  S-approach is blamed).
- **INV-3** — S-rows and `(·|from S)` rows never persist (byte-verified absent from shards).
- **INV-4** — `S → A` is the only hop the blame walk skips (structural, not positional); `A → V` in the
  start region is blameable.
- **INV-5** — BLOCKED is emitted only on region-tier heap exhaustion (every caps-legal approach to V
  eliminated). A flood is never BLOCKED: while anti-flap (#1) has a closer launch position untried it
  advances (persisting nothing); a *terminal* flood (closest launch also floods) emits SCOPED-PROOF —
  a `(from-fragment, approach)`-keyed invalidation — and re-derives. The earlier "BUDGET-UNPROVEN =
  don't persist" state is withdrawn (§8).

---

## §10. Decisions log

- **D1** — Wrap the start in S, mirroring V. *Rationale:* symmetry gives the blame walk a structural
  first hop and the region tier a "which way out" decision; both cliff pathologies are downstream of the
  region tier having no node here.
- **D2** — `A → V` invalidation is approach-conditioned (INV-2). *Rationale:* owner catch — unconditioned
  `A → V` disconnects V when A==G (confirmed in `relaxVirtualGoal`'s entry-stripped row). Load-bearing,
  therefore increment 1, not a follow-up.
- **D3** — Hot node key stays 59-bit; approach fidelity lives in the cold row layer. *Rationale:* 64-bit
  budget + hot-path perf model; the row layer is where evidence accuracy actually matters.
- **D4** — `ENTRY_INTERIOR` (already present) is the owner's "SAME_REGION"; S-exits/V-approaches reuse it.
  No new entry value needed.
- **D5** — Increment 1 keeps approach **entry** on the row (sufficient for the cliff A==G, whose correct
  approach is face-distinct); increment 2 upgrades to from-node via cameFrom (two-hallways), subsuming the
  entry-face-keys arc.
- **D6** — mod-3 dig-maze degrades efficiency not correctness; accepted (owner ruling), justified by the
  S→A / G→V / dig-out scoping argument.
- **D7** — Taxonomy (rewritten §8, owner ruling 2026-07-24): a no-progress flood is a valid,
  **persistable** proof — flooding→proof→persist is unchanged. The split is SCOPED-PROOF (terminal
  flood under anti-flap → `(from-fragment, approach)`-keyed invalidation + re-derive + persist scoped)
  vs BLOCKED (region-tier heap exhaustion → boxed-in, feeds #4/#5). The earlier "BUDGET-UNPROVEN =
  don't persist" framing is withdrawn; the two defects it tried to name are scoping, fixed by #1
  (position scope) and #3 (approach/from-fragment scope).
- **D8** — Acceptance is *reroute at the default budget*, not *find at a raised budget*. The 40k FOUND
  result is the existence proof that the route is there; increment 1 must reach it via the region tier.

## §11. For ratification (genuinely open)

- **R1** — S identity representation: a new `VIRTUAL_START_FRAG` sentinel fragment id, vs. promoting the
  existing `ENTRY_START` stamp into a first-class node. (Leaning: a sentinel fragment, symmetric with V,
  so the skeleton literally carries an S node the driver/dump can name.)
- **R2** — S-exit seed richness in increment 1: single `S → A` edge (minimal; fixes the give-up) vs.
  per-face `S → A(face)` seeds (fixes the flap too, more cold work per search). (Leaning: per-face, since
  the flap is the second half of the same fixture and the cost is cold.)
- **R3** — Whether increment 1 also needs the `RegionCrossingMemory` (persistent) rows touched, or only
  the in-search `RegionEdgeBlacklist`. The cliff repro is single-journey; persistence enters only if a
  learned `(A→V|from-face)` should survive restart. (Leaning: blacklist-only in increment 1; persistence
  in increment 2 with from-node keys, where the row is world-meaningful.)
- **R4** — Log/status vocabulary for the taxonomy split (names, and whether `PathStatus` gains a value or
  reuses PARTIAL/FAILED with a reason).
