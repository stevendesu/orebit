# Orebit — Movement Vocabulary Design (CONDENSED — implemented through Tier 2; full text in git history pre-s52)

**Status: SHIPPED for Tiers 1–2.** The canonical framing/vocabulary doc for the `Movement` set; its
ratified decisions live on in code.

**Where the code lives now:**
- `src/main/java/com/orebit/mod/pathfinding/blockpathfinder/` — `Movement.java` (base class:
  candidate emission + cold `steer`/`plan`/`reached` hooks; the default `reached` is
  grounded-gated for `commitsAcrossArrival()` moves — see the execution addendum below),
  `MovementRegistry.java` (TIER1 — **21 moves**, appended-at-end tie-priority order),
  `BotCaps.java`, `EditScratch.java`, `StepEdits.java`, `MovementContext.java`,
  `MovePlan.java`/`PhaseRunner.java` (the phase-execution framework)
- `movements/` — Traverse, Diagonal, Ascend, Descend, DiagonalAscend, DiagonalDescend (the dry
  3-axis pair — DESIGN-diagonal-vertical-moves.md), Fall, Pillar, MineDown, Climb, Parkour,
  DiagonalParkour, WalkOff (no-jump gap-1/descend-1 crossing, self-gates on jump-REFUSED start
  cells), RideBubbleColumn, and the **water family** Swim, SprintSwim, DiagonalSprintSwim,
  StartSprintSwim, EndSprintSwim, Surface — plus `ParkourEnvelope.java` (derived gap-jump
  admission table, not a Movement). **20 of the 21 carry a `plan()`**; only `DiagonalSprintSwim`
  has none, because it SUBCLASSES `SprintSwim` and inherits its execution whole.
- Grid encoding: `worldmodel/pathing/TraversalGrid.java` + `NavFlags.java`

**The water family (owner ruling 2026-08-07 — "fluid is a MEDIUM, not a pose"; full design in
`DESIGN-submerged-upright-swim.md`, status in the `waterfall-arc` memory):**
- **`Swim`** is the upright fluid-medium move and the structural analogue of `Climb` — ONE movement,
  **six rungs** (4 cardinals + a straight rise + a straight sink), all in `MODE_STANDING`, each priced
  from its own vanilla rate. It is the move that un-walls fluid, and its entry head-test is
  "**not solid**" (air *or* fluid), NOT `passable` — `passable` excludes fluid, which is exactly what
  once made a dry bot unable to walk into the body of a waterfall. Works in water AND lava; the medium
  is priced (damage + slow), never special-cased.
- **`SprintSwim`** (prone, `MODE_PRONE`) is **fast LATERAL travel and nothing else** — its pure-up and
  pure-down rungs were DELETED (a swimming look clamps near 80°, so a pure-vertical heading always leaks
  lateral drift, and in a 1×1 shaft that is ejection at speed). The vertical axis belongs entirely to
  upright `Swim`. `DiagonalSprintSwim` still owns its 20 multi-axis steps *including* the
  vertical-diagonals — the clamp forbids only the degenerate pure-vertical heading. Water only:
  `Pose.SWIMMING` is unreachable in lava (bytecode-verified `FluidTags.WATER` in `Entity.updateSwimming`).
- **`StartSprintSwim` / `EndSprintSwim`** are the in-place STANDING↔PRONE pose transitions (mode is part
  of the node key, so a same-cell edge lands on a distinct row — a real search edge, not a self-loop).
  `EndSprintSwim`'s headroom gate is a POSE-FIT test, not a policy: `Player.updatePlayerPose` fit-tests
  STANDING, then CROUCHING, then falls back to `Pose.SWIMMING` in a 1-tall gap.
- **`Surface`** narrowed to the **bank crawl-out** rung only: prone → standing onto a standable cardinal
  neighbour, for a bot that cannot stand up *where it is*. It never inspects the source's `y+2` — it is
  the SOURCE that lacks headroom, not the bank. Standing up IN PLACE is now `EndSprintSwim`.

**Execution-side addendum (2026-07-23 — the follower vocabulary grew past this doc's scope):**
the phase-execution framework's full current vocabulary — `MovePlan` phases/needs (AIR/FOOTING/OPEN
with live collision-based `solidAt` semantics), `drive`/`advanceWhen`/`done`/`resetWhen` **plus the
`failWhen` validity-envelope vocabulary** (`PhaseRunner.failed()`/`doneNow`), the per-move envelopes
(Parkour landing-column band, DiagonalParkour exact stands + gate-triggered takeoff, Ascend
from-column band + fluid clause + contract tripwire, Traverse run-line/step-assist bands, Descend
lip-transit band), the owner-ratified **fail→log→HOLD** policy, the grounded-gated `reached` for
committed moves, the floor-frame carry (`BlockPathPlan.floorY`), gate-point steering
(`SteerControl.steerViaGate`/`pastGate`), and place/break attribution logging — is documented in
**`DESIGN-validity-envelopes.md`** (the canonical execution-side doc). The parkour gap envelope is
now DERIVED at class-load (`ParkourEnvelope.java`, from `parkour_envelope_params.py`); the physics
constants, closed forms, and the six ratified outcomes behind it are in
**`NOTES-movement-physics.md`** §1–§2, which also carries the climb/vine physics + connectivity
verdicts (§3–§6) that the shipped climb arc left behind.

**§ map (sections cited by code Javadocs):**
- §1 framing — movement-centric, not block-centric; the two-resolution interplay; the three CANONICAL
  decisions (new movement KIND vs. cost MODIFIER vs. separate SYSTEM).
- §2 the movement vocabulary, tiered: Tier 1 ground; Tier 2 climb/gap/water; Tier 3 special &
  interaction — **doors are BUILT** (DOORS P3: `MovePlan.Need.OPEN`/`requireDoor`, door-sets folded
  onto `StepEdits` and injected by `BotNavigator`, opened by hand — never mined — in `PhaseRunner`'s
  door pre-pass); trapdoors designed (`DESIGN-trapdoors.md`), crawl still unbuilt; Tier 4 separate
  planning SYSTEMS (boats, elytra — deliberately NOT discrete cell-to-cell movements).
- §3 NavBlock fact additions. §4 material effects — fact vs execution vs cost.
- §5 `BotCaps` — the capability gate (PRD §7.3): every movement/candidate is filtered by what THIS bot
  may do (break/place/fall/damage), folded from owner config via `Config.toBotCaps()`.
  `mayFall` is the one axis NOT owner-config: it is derived per-mode (`AllyBotEntity.caps()` →
  `withMayFall(false)` in `Mode.ROAM`) and read by `Fall.candidates` as its self-gate, so a roaming bot
  plans no walk-off drop at any depth. It is deliberately NOT modelled as a squeezed `safeFall`/`maxFall`
  window — that window PRICES a drop and cannot forbid one (the free, immune and soft-landing/clutch
  branches all read through it), and narrowing it would also move Parkour's falling-landing tier and the
  region tier's dy pricing.
- §6 open questions / deferred decisions. §7 status & build order (historical).
- §8 nav-grid cell encoding (RATIFIED s17): packed `short` = [6 NavFlags neighbour-property bits |
  10 navtype bits]; fluid+gravity merged into one RISKY_EDIT bit; work items built per consumer, not
  speculatively; the block-change hook wired via the mixin/overlay pattern.
  - **Amended 2026-08-21 — the bit is `RISKS_GRAVITY`, cell-centred, and its gate is fold-sited.**
    Bit 0 no longer means "editing in MY body space could drop gravel"; it means *"breaking or placing at
    THIS CELL drops a gravity block"* — (a) the cell directly under a SUPPORTED gravity block, or (b) a
    cell orthogonally adjacent to an UNSUPPORTED one (DESIGN-fluid-flow-prediction.md §4.3). Ownership is
    split: `NavFlags.compute` writes half A (`hasGravity(above)`), the `computeDepth` scatter / patch
    gather / `EdgeScatter` own half B. **The per-movement `reset(!risksEdit(flags))` gate is DELETED** —
    every movement now calls the bare `reset()` and the single gate lives inside `EditScratch`'s
    break/place fold, reading the cell actually edited. That is what closed the class of bug where
    `Pillar` tested its start floor and then folded ungated landing-body breaks three cells away.
  - **Amended 2026-08-10/11 — "fluid" over-states what RISKY_EDIT carries.** The fluid half is
    **LAVA-only and unconditional**: water never sets the bit, and lava sets it via a 1-cell dilation over
    the 6 orthogonal neighbours with no flowing/impoundment test. Modelling vanilla's slope-distance
    spread needs source-vs-flowing and level bits the 2-bit `NavBlock` fluid field does not store. See
    `NavFlags`' lava-term section for the bytecode evidence.
