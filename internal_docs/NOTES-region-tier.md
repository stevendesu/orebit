# NOTES — region tier: durable facts worth not re-deriving

Permanent reference notes for the HPA\* region tier. Everything here is SHIPPED and verified against
live code; the design narratives that produced it have been deleted (they were completed-work
provenance). Sections are §-anchored because code Javadocs cite them.

Companion cards (still live, each a §-map for its own subsystem): `HPA-FRAGMENTS.md` (the fragment
model), `HPA-IMPLEMENTATION.md` (pyramid/driver/persistence), `HPA-CASCADE.md` (the nested per-level
skeleton stack, the virtual goal fragment V, the containment anchor), `DESIGN-typed-fragments.md`
(per-fragment S/W types + gate/cost table), `DESIGN-rolling-skeleton.md` (slide-and-extend; increments
B/C/D still deferred).

---

## §1 The region-search node key — exact bit layout

The region A\*'s **search node identity** is `(region, current-fragment, entry-face, from-fragment)`,
packed into exactly 64 bits with **no spare**:

```
bits  0..48  region   (49)  RegionAddress.packLevelKey: rx[27..48] | rz[5..26] | ry[0..4]
bits 49..54  fragment  (6)  RegionPathfinder.fragmentKey  (XOR, not OR — see below)
bits 55..57  entry-face(3)  0..5 face, 6 = ENTRY_START, 7 = ENTRY_INTERIOR
bits 58..63  from-fragment (6)  the fragment the search last hopped FROM; VIRTUAL_START_FRAG at the root
```

Code: `RegionAddress.packLevelKey/unpackRX/unpackRY/unpackRZ`,
`RegionPathfinder.fragmentKey/fragmentNodeKey/searchKey/approachRowKey`.

- **`ry` is 5 bits, not 6** (32 leaf Y-regions, a non-negative offset from `minY`). It **must** be
  unpacked with a PLAIN MASK — `signExtend` would read bit 4 as a sign bit and turn 16..31 negative,
  silently corrupting every key in the upper half of the world. `rx`/`rz` are 22-bit sign-extended.
- The fragment is **XOR**ed into the free high bits so fragment 0 leaves the key equal to
  `packLevelKey` (a uniform/single-fragment region keys exactly as the old center model did). A
  low-bit XOR would clash with `ry`.
- **CONSUMERS stay physical.** Entry-face and from-fragment never leave the search: the blacklist /
  crossing-memory row for a *regular* crossing is the entry-independent physical
  `fragmentNodeKey` pair. Only the **(approach → V)** row carries the full search key.

### §1.1 Why from-fragment is in the KEY, not just on the invalidation row

This is the non-obvious part and the thing a future session will be tempted to "simplify" away.

Two hallways `H1→Room1` and `H2→Room2` flood into ONE fragment `F` (joined by a 1×1 gap a no-cap bot
cannot cross), both entering `F` through the same face. With a row-level fix only, the search interns
both approaches to the **same node** `(F, +X)` and keeps only the cheaper (H1). Even an *exact* row
`(H1,F,V)` does not help: after it is blamed, re-expansion re-derives the same `(F,+X)` node (H1 is
still cheaper and already closed) and **never offers F→V via H2** → a false give-up. Only making
`(F,+X,from=H1)` and `(F,+X,from=H2)` **distinct nodes** lets the search try H2 after H1 dies.

Entry-face is **retained** alongside from-fragment (not reduced away): fragment ids are region-local,
so two predecessors can share an id while entering through different faces — dropping the face
over-invalidates.

**Add-side / check-side parity is load-bearing.** `RegionPathfinder.approachRowKey` is the ONE builder
used by both the search-time check (`relaxVirtualGoal`) and the blame add-side
(`PathPlan.blockedHop` → `approachRowKeyForStep`), so the two can never drift.

### §1.2 Accepted degradations (both named, both deliberate)

- **State-space.** From-fragment multiplies per-cell node identity from ≤8 (entry-face) to ≤8×62, so
  `CAP_SAFE_NODES` (8192) no longer strictly bounds *in-box* node count — a dense merged-fragment
  cluster can hit `MAX_REGION_EXPANSIONS` (20000) and return a PARTIAL where it previously FOUND.
  Bounded, never infinite. Owner-accepted as "legitimate proof work"; **capability-aware fragments
  (#6) retires it** by splitting F along real traversability. This is documented on `CAP_SAFE_NODES`.
- **The mod-3 dig-maze.** Multi-hop *intra-region* dig chains are all stamped `ENTRY_INTERIOR`, so a
  3D maze of them can merge node identities and misprice. Degrades **efficiency, not correctness** —
  the row layer stays exact, so invalidation still converges. Scoping argument for why it is safe:
  the only intra-region crossings are `S→A`, `G→V` (both terminal, no onward connections) and
  dig-out; and a dig-capable bot rarely floods, while a no-cap bot cannot dig-out at all.

### §1.3 Latent items carried forward (NOT bugs seen in the field — watch for them)

- **`targetStep` same-leaf-region goal.** `blockedTargetStep = windowTargetStep = choice.step` from
  `WindowTargeting`. For a **strictly-same-leaf-region goal**, the GOAL branch can return
  `step == windowStart`, making `hi ≤ windowStart` in `PathPlan.blameHop` → the `-1` give-up returns
  *despite* the virtual-fragment anchor skip. **Do NOT paper over this with an `hi`-extension guard** —
  that would blame hops the search was never asked to reach. Fix it at the targeting end if it ever
  reproduces.
- **Single-axis hop assumption.** `RegionPathfinder.approachEntryFaceForStep` reconstructs the
  entry-face from the FROM−TO region delta and assumes region hops are **single-axis** (true for L0
  skeletons). A multi-axis coarse hop feeding an L0 skeleton would silently diverge from the face the
  search stamped — add an assert if that is ever introduced.
- The region-tier paired A/B JMH gate for the from-fragment key change
  (`RegionPathfinderBenchmark` bounded delta, `RegionFieldBuildBenchmark` FLAT) was deferred as
  machine-gated and never recorded as run.

### §1.4 The reverse-Dijkstra field must stay from-fragment-blind

`relaxFrag` is shared with the goal-rooted reverse cost field (`dijkstra=true`). Splitting *its* nodes
by from-fragment would blow the field up and corrupt queries, so the dijkstra path passes the
`VIRTUAL_START_FRAG` constant unconditionally — the field stays byte-identical. Do not "unify" this.

---

## §2 The virtual fragment sentinels (id space)

`RegionFragments.MAX_FRAGMENTS = 62` ⇒ real fragment ids are **0..61**. The two ids above them are
search-only sentinels in `RegionPathfinder`:

| id | name | meaning |
|----|------|---------|
| 62 | `VIRTUAL_START_FRAG` | the search ROOT's *from-fragment* (the bot's fragment A has no predecessor); also the from-fragment stamped on every reverse-Dijkstra node |
| 63 | `VIRTUAL_GOAL_FRAG` (V) | the synthetic goal node the level-0 A\* terminates on (per-approach goal seeding — HPA-CASCADE.md) |

**Distinct value space:** `RegionFragments.FRAGMENT_COUNT_COLLAPSED` is also 63, but it lives in the
persisted 6-bit *count* field, not the id field. Never conflate them.

**Structural journey-scoping (this is why it is a sentinel and not a real node).** A row naming
`from = VIRTUAL_START_FRAG` names a journey-local value (S differs each `/bot goto`), so it **cannot**
be world knowledge and is inherently un-persistable — no positional carve-out needed. Likewise a
`(… → V)` row names only the goal REGION, not the goal cell, so persisting it would poison a different
goal's approaches forever; `CostPyramidCodec.decode` drops any legacy row whose TO fragment id ≥
`MAX_FRAGMENTS` so old files self-clean.

**Blame anchor.** `PathPlan.blameHop`'s resume-anchor scan (which finds "where the bot is" so it never
blames a hop behind the bot) **skips virtual fragments**: the bot can be in neither S (a null move
already put it in A) nor V (arriving ends the journey). Without the skip, an unreached V is mistaken
for the bot's position whenever the start fragment equals the goal fragment (A==G) → false give-up.

---

## §3 Flood taxonomy — what "BLOCKED" is allowed to mean (owner ruling 2026-07-24)

Superseded framing to NOT resurrect: *"a budget-exhausted flood is unproven, therefore don't persist."*
That was **wrong**. A no-forward-progress flood **is** a valid, persistable proof — "the goal is
unreachable within budget **from here**". The real defects were both **scoping**:

- **Position over-scope.** A slid-window flood proves only "unreachable from *this launch position*".
  The cure is to walk the last non-flooded plan forward and re-attempt from successively closer
  committed positions; only the flood from the **closest reachable launch** is terminal and
  persistable. (Owned by the anti-flap arc.)
- **Approach over-scope.** A proof condemns only the **FROM fragment / approach**, never the crossing
  globally. This is the §1.1 from-fragment keying: `(A → V | from S)` dies while
  `(A → V | from staircase)` survives. An *unconditioned* condemnation is the A==G collapse bug — one
  row disconnecting every approach to V.

The resulting two-way split:

- **SCOPED-PROOF** — a *terminal* flood yields a `(from-fragment, approach)`-keyed invalidation; the
  region tier re-derives around it. Non-terminal floods persist **nothing** and simply advance.
- **BLOCKED (boxed-in)** — genuine region-tier **heap exhaustion**: every caps-legal approach to V has
  been eliminated and V has no surviving in-edge. `"exhausted its repairs"` fires **only** here.
  Nothing else may claim it.

Related invariants worth keeping checkable: a no-progress flood blames the first *unrealized* crossing,
never the bot's own start hop; blaming one approach must never disconnect another (regression: with
A==G, blaming the direct approach must leave the staircase approach alive).

---

## §4 On-disk versions were RESET to 1 — do not "bump to v8"

`CostPyramidCodec.VERSION` and `INVAL_SIG_SCHEMA_VERSION` are **1** — reset here on the repack, and
since 2026-08-09 PINNED at 1 pre-release by owner ruling (zero wild installs; semantic changes append
to the codec Javadoc history — mayFall/trapdoors/gates/ladder-climb are entries there — instead of
bumping; the point of this section is that the OLD v2..v7 history must never be "restored" either).
The long v2..v7 history is
described in `CostPyramidCodec`'s class doc for semantics only; the constant itself was reset on the
2026-07 `packLevelKey` repack (`ry` 6→5 bits shifted every field down one bit). Disk is a **cache** —
a version mismatch simply cache-misses and rebuilds from live, which is why collapsing the history was
safe. When people say "the v7 record layout" they mean the *shape* (2 type bits per MIXED fragment),
not the number on disk.
