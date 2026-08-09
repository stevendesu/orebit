# DESIGN — Fence Gates

Status: **implementing** (owner brief 2026-08-09; recon verified same day). Companion to
`DESIGN-trapdoors.md` — gates are the third openable kind on the shared-bit pattern, and by design
the *degenerate* case: no blocked-face model, no residual clearance, no exit machinery. Everything
here was bytecode- or code-verified during recon (17 MC versions javap'd for §1; every code claim
in §2–§8 read at file:line); full evidence trails live in the arc's recon reports (session
scratchpad `gates/reports/`), with the durable facts folded into this doc.

## §1 Vanilla facts (bytecode-verified, 1.17.1 → 26.2, 17 versions inspected)

- **Properties:** exactly `FACING` / `OPEN` / `POWERED` / `IN_WALL` across the whole range. **No
  `WATERLOGGED` at any version** — gates cannot be waterlogged (class never implements
  `SimpleWaterloggedBlock`).
- **Collision:** closed = a centered plate along `FACING.axis`, **24/16 tall × 4/16 thick**
  (`box(0,0,6→16,24,10)` Z-flavor / `(6,0,0→10,24,16)` X-flavor). Open = **`Shapes.empty()` — zero
  hitbox** — confirmed in the disassembly of every fully-decompiled version. `IN_WALL` is *never*
  read in `getCollisionShape` — the 3px lowering is outline/occlusion only. The outline (`getShape`)
  ignores `OPEN` — a classifier must read *collision*, which ours does.
- **Single block.** No half property. An open gate in a 1-tall hallway is pure air (owner edge case
  #1). Closed collision protrudes 8/16 into the cell above, like fences.
- **No `setOpen` convenience method at any version** (like `TrapDoorBlock`, unlike `DoorBlock`) →
  the executor is setBlock-only, same as trapdoors. The vanilla use handler: open→close; on close→
  open it first flips `FACING := player.getDirection()` iff `FACING == its opposite`. The flip is
  always 180°, so **`FACING.axis` is invariant for the block's lifetime** and collision is empty
  before `FACING` is consulted when open ⇒ **facing is behaviorally irrelevant; the executor writes
  `OPEN` with `FACING` as-read at every version.** Sound APIs churned three times across the range
  (levelEvent → SoundEvents fields → WoodType) — irrelevant to a setBlock executor; bot toggles are
  silent, same accepted limitation as trapdoors.
- **Redstone** forces `OPEN := signal` in both directions (`neighborChanged`, server-only). Wind
  charges toggle unpowered gates (1.20.3+). External state changes are handled by the same
  own-toggle-vs-foreign-change machinery doors/trapdoors use.
- **No iron variant.** The roster is all-wood (8 at 1.17.1 → 12 from 1.21.2, stable through 26.2,
  no 26.x `ColorCollection` collapse), all hand-toggleable, `instanceof FenceGateBlock` is complete
  and subclass-free. ⇒ **hand-toggleable bit 50 is set unconditionally for gates.**

## §2 Descriptor (zero new bits — owner-ratified)

Kind `OPEN_GATE` (openable bits 14–15, value 3) is **already assigned** at classification
(`instanceof FenceGateBlock`, shipped with the trapdoor arc). The generic collision path already
yields exactly the owner's model: closed = `SHAPE_OTHER`, `topY=24`, `NARROW_TOP`, collision,
not standable, not passable; open = `SHAPE_EMPTY`, passable. This arc adds only:

- **bit 43** (shared open bit) ← blockstate `OPEN`.
- **bit 50** (hand-toggleable) ← unconditional (no iron gate exists, §1).
- **`FACING` is NOT packed** — deliberate deviation from the brief's "there's already facing":
  facing is behaviorally void for gates (§1), packing it would 4× the gate navtypes for zero
  consumers. One-line to add if a consumer ever appears. `POWERED`/`IN_WALL` are unread and
  collapse in dedup (IN_WALL is collision-invariant, §1).
- **`withGateOpen`** — the third `withOpenableOpen` arm (the current door-bit-flip fallback would
  mis-route gates; today unreachable only because gates carry no toggleable bit). Re-derives
  geometry both directions: open → `SHAPE_EMPTY`/topY 0/passable/¬narrow/¬collision + bit 43;
  closed → `SHAPE_OTHER`/topY 24/narrow/collision/¬passable − bit 43. Pinned **bit-for-bit against
  the interned real states** over every registered gate state (the trapdoor-sweep pattern).
- Accessors: `isGate(d)`, `gateOpen(d)`.

## §3 Movement model — closed gate is a whole-cell blocker, open gate is air

The centered 4/16 plate leaves 6/16 on each side; a 0.6-wide (9.6/16) bot cannot squeeze past ⇒
**approach direction never matters.** No blocked-face model, no residual-clearance interaction
(closed topY 24 caps the cell outright; open contributes nothing), no exit machinery, no
open-panel geometry. The ONE new planner behavior is a **face-agnostic toggle-for-passage fold**:

- `MovementContext.gateSetClears(d)` = `caps.mayToggleDoors ∧ isGate ∧ handToggleable ∧ ¬gateOpen`.
- Folded in `EditScratch.requireAirToward` / `requireUpperBodyToward` as a third arm after the
  door/trapdoor arms: fold `SET_OPEN` on the gate cell; the result is passable **by construction**
  (open = empty collision) — no post-toggle re-evaluation.
- Toggling bypasses `PROTECTED` exactly as for doors/trapdoors — which matters here:
  `#minecraft:fence_gates` is in the default `mining.protectedBlocks`, so **the toggle fold is the
  only way through a closed gate under default config** (no break fallback). With `doors.toggle`
  off, a closed gate is a hard wall the planner routes around — correct and conservative.

**No per-movement file edits.** Every ground/diagonal/vertical movement inherits the fold through
the existing `requireAirToward`/`requireUpperBodyToward` chain. Standing/landing refusals are
already netted: Ascend (dest not standable + rise 24 > `JUMP_RISE` 20), Parkour arcs
(`overJumpable = topY ≤ 16` at every gap column), Fall/Pillar (`bodyPassable`). Diagonal keeps its
ratified passable-only carve-out (no folds). The Ascend same-level-jump arm and its
`SAME_LEVEL_MAX_START_TOP` short-circuit are untouched (the JFR +5% lesson — gates add **no new
candidate generation anywhere**, only a branch inside the already-cold non-passable-openable fold
path). Swim: `Surface`/`EndSprintSwim` destination predicates already admit an open gate cell and
refuse a closed one (owner edge case #3) — pinned by new tests, zero code.

## §4 Executor chain

- `platform/WorldEdits.setGateOpen(level, pos, actor, open)` — version-STABLE `src/` platform class
  (verified zero overlay flavors needed): guard `instanceof FenceGateBlock`, write
  `state.setValue(OPEN, open)` with `FACING` as-read (§1), setBlock flags matching
  `setTrapdoorOpen`. Silent (no sound/gameevent — API churned 3×, same trapdoor ruling). No iron
  exclusion (none exists).
- `BotSteering.setGateOpen` default no-op (keeps test doubles compiling — trapdoor pattern);
  `AllyBotEntity` override: lookAtCell + `expectOwnToggle` **before** the edit + `WorldEdits`.
- **`AllyBotEntity.doorOpenAt` readback must add `FenceGateBlock`** — without it the `Need.OPEN`
  readback never observes "open", and PhaseRunner re-issues the toggle forever (recon-flagged
  CRITICAL).
- `MovePlan`: `Req`'s door/trapdoor boolean becomes a 3-way kind; add `requireGate`. `PhaseRunner`:
  3-way verb dispatch (`isOpenableCell` is already kind-generic). `BotNavigator`: the injection
  dispatch and the legacy `applyEdits` replay each gain the third `instanceof` arm.
- `NavGridUpdater.consumeExpectedToggle` must add `FenceGateBlock`, else every own gate toggle
  reads as a foreign world change and burns a re-search.

## §5 Region tier — NOTHING TO DO

`floodPassable(d) = isPassable(d) || openable(d) != OPEN_NONE` already admits closed gates (shipped
with the openable-as-air flood, owner-ratified incl. the iron rationale);
`OpenableFloodConnectivityTest.closedFenceGate_connects` / `plainFence_staysSealed` pin both sides.
The owner's "ensure the region tier treats gates as passable" requirement was satisfied before this
arc began.

## §6 Persistence

- `CostPyramidCodec.VERSION` **stays 2** — gate classification changes no flood output (gates were
  already flood-passable in v2); prose note only.
- `INVAL_SIG_SCHEMA_VERSION` **3 → 4** — bit 3 (`mayToggleDoors`) broadens its meaning again
  (doors → +trapdoors at v3 → +gates at v4). A persisted "unrealizable even with toggling"
  negative recorded under v3 semantics can be false once gates toggle ⇒ same precedent that forced
  v3.

## §7 Tests

Pattern-match the trapdoor test idioms (Bootstrap + interned real states over synthetic
`NavGridView`; FakeBot `BotSteering` double for PhaseRunner):

- `GateClassificationTest` — closed/open descriptor pins (§2 geometry + kind + bits 43/50);
  facing/powered/in_wall/wood-type **collapse** assertions (all intern to the same navtype per
  open-state); the `withGateOpen` **bit-identity sweep** both directions over every registered gate
  state.
- `GateToggleTest` (planner) — corridor through a closed gate plans exactly one `SET_OPEN` at the
  gate cell; already-open gate plans **zero** SETs; `mayToggleDoors=false` + default-protected ⇒ no
  route through (hard wall); Ascend-onto-gate and Parkour-over-gate refusals; `Surface` /
  `EndSprintSwim` water-exit into an open-gate cell OK, closed refused.
- `PhaseRunnerGateTest` — `Need.OPEN` pre-pass issues the gate verb; readback clears it;
  already-open issues nothing.
- `NavGridToggleForgivenessTest` — the own-toggle forgiveness KIND rule
  (`NavGridUpdater.forgivableToggle`, the extracted match rule of `consumeExpectedToggle`): a gate
  OPEN flip is forgiven like a door/trapdoor flip; broken gates and non-openable same-block flips
  are not. The slot/`foreignVersion` machinery around it is welded to a live `ServerLevel` (the
  `PathPlanOwnEditTest` split — same as the door/trapdoor arcs), so no-epoch-churn end-to-end is a
  live-harness assertion, not a unit one.
- Live harness (**BUILT, run pending — see §8**): `GateCourse` (TrapdoorCourse clone — open-to-cross
  with exactly-one-own-toggle, already-open zero-SET, external-toggle anti-trick, toggle-off
  protected-wall control) + the `runGate` task + `scripts/run-gate.ps1` + `scripts/gate/` templates
  all EXIST; the live RUN has not happened yet. Two deliberate deviations from the trapdoor clone:
  (1) TrapdoorCourse has no per-tile config, so the toggle-off tile installs `doors.toggle=false`
  through the real owner mechanism — rewrite the live `config/orebit.properties` +
  `ConfigLoader.reload`, the `/bot config reload` path — and runs LAST so the flip is never
  reverted; (2) its refusal verdict follows the BoxedInCourse tomb convention (PASS on a
  `navGaveUp` give-up with the gate untouched; timeout-without-give-up = FAIL), with the goto
  gated on BoxedInCourse's nav-residency check so a premature give-up can't fake the refusal.
  The course runs `mining.canMine=false` (unlike the trapdoor course) — no gate tile needs a
  MineDown arm, and no-break makes the toggle-off hard wall exact (no dig-around route).

## §8 Deferred / out of scope

- **Live harness RUN (§7's `GateCourse` course)** — the harness itself now EXISTS (`GateCourse` +
  the `runGate` run config + `scripts/run-gate.ps1` + `scripts/gate/` templates, built in a
  follow-up session as planned), but it has **not been executed yet**: the legs it verifies (the
  `WorldEdits.setGateOpen` mutation, the PhaseRunner anti-trick re-issue against a live level,
  own-toggle no-epoch-churn end-to-end, the toggle-off honest give-up) are exactly the
  only-verifiable-in-game set `PhaseRunnerGateTest`'s header names. Until the run happens, the
  gate arc is unit-verified only — the trapdoor precedent's 7/7 live course has no gate
  counterpart yet. Launch: `powershell scripts/run-gate.ps1` (JAVA_HOME → JDK 21).
- **Pillar-shaft vertical gate fold** (closed gate capping a pillar column): skipped — conservative
  refusal routes around; one fold-site to add on demand. Fall-through-a-closed-gate likewise (an
  *open* gate already falls through as plain air).
- **Diagonal corner topY seam** (pre-existing, recon-found, gates merely share it): corner FLOOR
  cells are never topY-tested, so a fence/wall/closed-gate in a corner floor cell protrudes 0.5
  into the swept feet path unmodeled — within step-assist, likely stutter not wedge; runtime
  severity unverified. Filed for the follower-envelope backlog, not this arc.
- Close-gate-behind (matches doors/trapdoors: never close behind); `IN_WALL` awareness (visual
  only); crawl-height traversal (no Crawl movement exists); gate sounds (setBlock executor is
  silent, family-wide ruling).

## §9 Owner-brief deltas (flagged for owner review)

1. Region-tier requirement and GATE-kind classification were **already shipped** (trapdoor arc).
2. **FACING not packed** (§2) — verified behaviorally void; deviation from the brief's list of
   leverageable bits, chosen to keep navtypes minimal. One-line reversal if wanted.
3. **`INVAL_SIG_SCHEMA_VERSION` 3→4** (§6) — required by the same rule that forced v3.
4. Pillar/Fall vertical folds deferred (§8).
