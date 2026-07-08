# Orebit — Movement Vocabulary Design (CONDENSED — implemented through Tier 2; full text in git history pre-s52)

**Status: SHIPPED for Tiers 1–2.** The canonical framing/vocabulary doc for the `Movement` set; its
ratified decisions live on in code.

**Where the code lives now:**
- `src/main/java/com/orebit/mod/pathfinding/blockpathfinder/` — `Movement.java` (base class:
  candidate emission + cold `steer`/`plan`/`reached` hooks), `MovementRegistry.java` (TIER1 — 14 moves),
  `BotCaps.java`, `EditScratch.java`, `StepEdits.java`, `MovementContext.java`
- `movements/` — Traverse, Diagonal, Ascend, Descend, Fall, Pillar, MineDown, Climb, Parkour,
  DiagonalParkour, Swim, SprintSwim, StartSprintSwim, Surface
- Grid encoding: `worldmodel/pathing/TraversalGrid.java` + `NavFlags.java`

**§ map (sections cited by code Javadocs):**
- §1 framing — movement-centric, not block-centric; the two-resolution interplay; the three CANONICAL
  decisions (new movement KIND vs. cost MODIFIER vs. separate SYSTEM).
- §2 the movement vocabulary, tiered: Tier 1 ground; Tier 2 climb/gap/water; Tier 3 special &
  interaction (doors/trapdoors/crawl — **still unbuilt**, on the roadmap); Tier 4 separate planning
  SYSTEMS (boats, elytra — deliberately NOT discrete cell-to-cell movements).
- §3 NavBlock fact additions. §4 material effects — fact vs execution vs cost.
- §5 `BotCaps` — the capability gate (PRD §7.3): every movement/candidate is filtered by what THIS bot
  may do (break/place/fall/damage), folded from owner config via `Config.toBotCaps()`.
- §6 open questions / deferred decisions. §7 status & build order (historical).
- §8 nav-grid cell encoding (RATIFIED s17): packed `short` = [6 NavFlags neighbour-property bits |
  10 navtype bits]; fluid+gravity merged into one RISKY_EDIT bit; work items built per consumer, not
  speculatively; the block-change hook wired via the mixin/overlay pattern.
