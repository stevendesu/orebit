# DESIGN — Hazard media: magma floors + lava, driven by existing descriptor properties

STATUS: IMPLEMENTED (owner-directed model, 2026-07-07 — commit d0039fa; gates green both eras.
Implementation notes vs this doc: no new descriptor bits were needed; slow classification moved to
Block.getSpeedFactor(); slow pricing is a MULTIPLIER (Traverse.SLOW_COST_FACTOR); STANDABLE also gained
the topY<=16 SHAPE_OTHER widening — soul sand/honey were unstandable via the fence net, which would have
made SURFACE_SLOW dead code; lava swims via Swim + a lava-only rise rung, SprintSwim stays water-only.
OWED: JMH A/B on the mc-1.21 era (candidate-set change) + in-game verify: magma walk (immune free /
mortal detours), lava crossing only-when-forced, soul-sand routing.)

Owner ruling (verbatim intent): lava's nav properties already exist — it is FLUID, DAMAGING,
and SLOWING — and those three should drive all normal navigation logic (fluid ⇒ swim moves
available; damaging ⇒ high cost via costPerHitpoint; slowing ⇒ cost multiplier). A* then
avoids lava naturally by cost; it must NOT be treated as a solid. Magma is a solid block you
can stand on that deals damage: factor the damage into cost, otherwise 100% traversable.
No special driver branches, no new data.

## VERIFIED mechanism (why both are walls today — one line each)

1. `NavBlock.withDerived`:
   `STANDABLE = solid && shape != SHAPE_OTHER && noFluid && !isDamaging(d)`
   — the `!isDamaging(d)` conjunct strips STANDABLE from every damaging SOLID (MAGMA_BLOCK,
   CAMPFIRE, …) at classification time, caps-blind. Delete that conjunct and magma is a
   normal floor.
2. `MovementContext.passable = NavBlock.isPassable(d) && fluid(d)==0` bars all fluids from
   walk clearance (correct — water is barred the same way), and the swim moves' re-entry
   door `water()` / `NavBlock.isSwimmableWater` explicitly excludes lava ("never swimmable").
   Both barred ⇒ total wall.

## The change (no new descriptor bits — DAMAGE_BIT and the fluid bits already exist)

1. STANDABLE returns to pure geometry: drop `!isDamaging(d)` from the derived bit (and from
   `verifyDerivedBits`). Ground moves whose DESTINATION floor `isDamaging` add
   floor-contact damage: expectedContactHP × caps.costPerHitpoint, caps-gated
   (takesDamage=false ⇒ 0 ⇒ immune bots walk magma free). Landing moves (Fall/Parkour)
   price the landing floor identically.
2. Swim medium generalizes: a swimmable-lava predicate (lava fluid + empty shape) admits the
   Swim/StartSprintSwim family into lava cells with (a) immersion damage per cell
   (lava tick + fire damage × costPerHitpoint — the dominant term; dry/water routes always
   win when they exist), (b) the lava swim-speed factor folded into move cost (~0.2× water —
   the "slowing" property; lava currently has no transit-slow class since it was never
   traversable: give FLUID_LAVA the heavy transit class or fold the factor into the swim
   moves' lava cost directly, whichever reads cleaner at implementation), (c) no sprint-swim
   in lava (vanilla has none). `SteerControl.holdDepth`'s `inWater()` gate generalizes to
   in-fluid so the depth autopilot carries over.
3. Region tier: LeafCostComputer's water fast-path gains the lava analog with the damage
   term folded, so skeletons don't promise lava swims the block tier prices out.

## Consumer audit required at implementation (STANDABLE/swim-predicate readers)

WindowTargeting (probe/isUsableTarget/snapInFootprint/projectToStandableFloor),
MovementContext.standable/floorSurface consumers (all ground moves, PartialHeightTest),
FragmentBuilder/FragmentLeafComputer leaf kinds, NetherPortalIndex descent walk,
SkeletonDump probes, headless tests that build courses over magma-adjacent fixtures.
Candidate-set change ⇒ full JMH A/B per the perf protocol + new headless tests (immune bot
walks magma free / mortal bot priced; lava crossing chosen only when no dry route exists).

## Interim behavior (shipped s52b, after the owner's course-correction)

A start-dead bot (its own cells emit no candidates) REPORTS what its feet/head contain
(one chat + log line) and holds. No automatic escape of any kind — the dig-out and
swim-out rescue subroutines written earlier that day were assumption-driven bandaids and
were REVERTED; entombment classes get designed fixes from reported data. See the CRITICAL
INSTRUCTIONS banner in CLAUDE.md.
