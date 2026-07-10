# DESIGN — Hazard media: magma floors + lava (driven by existing descriptor properties)

STATUS: IMPLEMENTED, owner-directed (2026-07-07, commit d0039fa; gates green both eras).

## Owner ruling (the durable decision — echoes the CLAUDE.md CRITICAL banner)

Lava's nav properties already exist — it is FLUID, DAMAGING, and SLOWING — and those three drive
ALL normal navigation: fluid ⇒ swim moves available; damaging ⇒ high cost via `costPerHitpoint`;
slowing ⇒ cost multiplier. A* then avoids lava naturally BY COST; it must NOT be special-cased as a
solid. Magma is a solid you can stand on that deals damage: factor the damage into cost, otherwise
100% traversable. **No special driver branches, no new descriptor data.**

## What shipped (mechanism)

- **STANDABLE returned to pure geometry.** The `!isDamaging(d)` conjunct that had stripped STANDABLE
  from every damaging solid (MAGMA_BLOCK, CAMPFIRE, …) was removed, so magma is a normal floor. Ground
  and landing moves (Traverse/Ascend/Descend/Fall/Parkour) whose DESTINATION floor `isDamaging` add
  contact damage (expectedContactHP × `caps.costPerHitpoint`), caps-gated — an immune bot
  (`takesDamage=false`) walks magma free; a mortal one takes the priced detour.
- **Swim generalized to lava.** A swimmable-lava predicate (lava fluid + empty shape) admits the
  Swim/StartSprintSwim family into lava with per-cell immersion damage (the dominant term — dry/water
  routes win whenever they exist) and the lava swim-speed factor folded into move cost; no sprint-swim
  in lava (vanilla has none). `SteerControl.holdDepth`'s gate generalized from `inWater` to in-fluid so
  the depth autopilot carries over. Slow classification moved to `Block.getSpeedFactor()`; slow pricing
  is a MULTIPLIER (`Traverse.SLOW_COST_FACTOR`); STANDABLE also gained the `topY<=16` SHAPE_OTHER
  widening (soul sand/honey were unstandable via the fence net, which would have made SURFACE_SLOW dead
  code).
- **Region tier.** `LeafCostComputer`'s water fast-path gained the lava analog with the damage term
  folded in, so skeletons don't promise lava swims the block tier then prices out.

## The recurring lesson (why this doc is kept)

An entombed / start-dead bot (its own cells emit no candidates) REPORTS what its feet/head contain (one
chat + log line) and HOLDS — no automatic escape of any kind. The dig-out and swim-out rescue
subroutines written earlier that day were assumption-driven bandaids and were REVERTED; entombment
classes get *designed* fixes from reported data, flowing from descriptor properties, never a bespoke
driver branch. See the CRITICAL INSTRUCTIONS banner in CLAUDE.md.
