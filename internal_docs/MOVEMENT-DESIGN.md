# Orebit — Movement Vocabulary Design (CONDENSED — implemented through Tier 2; full text in git history pre-s52)

**Status: SHIPPED for Tiers 1–2.** The canonical framing/vocabulary doc for the `Movement` set; its
ratified decisions live on in code.

**Where the code lives now:**
- `src/main/java/com/orebit/mod/pathfinding/blockpathfinder/` — `Movement.java` (base class:
  candidate emission + cold `steer`/`plan`/`reached` hooks; the default `reached` is
  grounded-gated for `commitsAcrossArrival()` moves — see the execution addendum below),
  `MovementRegistry.java` (TIER1 — **17 moves**, appended-at-end tie-priority order),
  `BotCaps.java`, `EditScratch.java`, `StepEdits.java`, `MovementContext.java`,
  `MovePlan.java`/`PhaseRunner.java` (the phase-execution framework)
- `movements/` — Traverse, Diagonal, Ascend, Descend, Fall, Pillar, MineDown, Climb, Parkour,
  DiagonalParkour, Swim, SprintSwim, StartSprintSwim, Surface, WalkOff (no-jump gap-1/descend-1
  crossing, self-gates on jump-REFUSED start cells), DiagonalSprintSwim, RideBubbleColumn —
  plus `ParkourEnvelope.java` (derived gap-jump admission table, not a Movement)
- Grid encoding: `worldmodel/pathing/TraversalGrid.java` + `NavFlags.java`

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
now DERIVED at class-load (`ParkourEnvelope` — see `DESIGN-parkour-envelope.md`'s status header).

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
- §6 open questions / deferred decisions. §7 status & build order (historical).
- §8 nav-grid cell encoding (RATIFIED s17): packed `short` = [6 NavFlags neighbour-property bits |
  10 navtype bits]; fluid+gravity merged into one RISKY_EDIT bit; work items built per consumer, not
  speculatively; the block-change hook wired via the mixin/overlay pattern.
