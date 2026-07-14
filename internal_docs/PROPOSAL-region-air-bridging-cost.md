# Proposal — make the FORWARD region A* pillar cost tool/removal-aware (fix the pillar-to-sky)

Status: **PROPOSAL for owner design-review — NOT implemented.** SUPERSEDES this file's earlier "MIXED-region
bridging" proposal, which was WRONG (owner correction: MIXED regions have standable fragments and must not be
charged bridging). Diagnosis: `DIAGNOSIS-region-pillar-to-sky.md`. Owner picked this (forward-pillar) target.

## Verified defect (code agent + region-trace analyzer)

The bot pillars to the world ceiling NOT because the search prefers up (it's 67% lateral / 21% down-toward-goal
/ 10% up; `air-pillar` up is a flat expensive 96). It's because the search **floods the cheap air/`[unbuilt]`
lattice and can't afford the dig-down to the deep goal, exhausts its 20k budget, and returns a high-altitude
PARTIAL** the bot executes by pillaring. Two under-pricings enable the flood; THIS proposal addresses the
tool-blind one:

- The **forward** search's air-pillar / walk-rise cost is a flat `PILLAR_PER_BLOCK = 6.0`/block
  (`RegionPathfinder.java:1420`, `:1331`, `reverse=false`), **identical for a bare-handed and a diamond-pickaxe
  bot** — no place-base, no tool-scaled mine-out. The forward `plan()` path never even receives a
  `RegionPlaceModel` (`:484-487` "the pillarField arg is read only on the reverse field edges").
- The caps-aware value already exists: `RegionPlaceModel.pillarPerBlock() = (Pillar.COST + placeBase +
  placeRemovalPremium)/WALK_REAL_TICKS` (`RegionPlaceModel.java:50-51`), where `placeRemovalPremium` IS the
  tool-scaled mine-out time of the placed block. It's wired ONLY to the reverse cost-to-goal field.

So a bare-handed bot — which should find pillaring brutal (placing cobble it can barely mine back out) — is
charged 6/block, same as a diamond bot. That's the tool-blindness.

## Proposed change

Thread a `RegionPlaceModel` (built `from(botInventoryView)` so `placeRemovalPremium` is tool-scaled) into the
FORWARD search — `plan()`/`planWithin()` → `planLevelFragments` → `expandNode` → `uniformTransitCost`/`walkCost`
— exactly as `RegionMineModel` is already threaded. Replace the forward `PILLAR_PER_BLOCK` at the two forward
sites (`:1420` uniform-air up, `:1331` walk-rise) with `place.pillarPerBlock()`. Result: bare-handed up-pillaring
becomes expensive (place-base + slow mine-out), diamond-pickaxe stays cheap.

## Design decision this forces (owner call)

The forward search's flat `WALK 1 / PILLAR 6 / FALL 1` is a **deliberate, documented** "behavioral compressed,
self-consistent" set, with honest `/4.6` costs "reserved for the cost-to-goal FIELD only" (`RegionPathfinder.
java:133-143`). Making forward-PILLAR caps-aware while WALK/FALL stay behavioral **breaks that self-consistency
by intent**. Is that acceptable, or do you want the whole forward triple moved onto the honest `/4.6` scale
(bigger change, but restores internal consistency)? This is the crux to sign off before I code.

## Scope limit — this alone may NOT fully fix the repro

This raises the cost of climbing **built** air regions (the L1.0-L1.4 `[air-no-floor]` climb to reach the
unbuilt zone). It does **NOT** touch `[unbuilt]` regions, which return `0f` (free) in all directions
(`:1386-1388`) — the bulk of the sky highway. If the search can reach the free `[unbuilt]` lattice by a cheap
LATERAL air walk (not just up), the tool-aware pillar won't stop it. A parallel investigation is running on WHY
so many regions are `[unbuilt]` (owner's suspicion: explored all-air regions may be indistinguishable from
never-seen — a persistence/build gap). The unbuilt-free lever may need its own change; we'll know after that
result. **Verify empirically:** does the tool-aware forward pillar alone flip the `-Barehanded` repro to
descend? If not, it's necessary-but-insufficient and pairs with the unbuilt fix.

## Invariants / verification

- Do NOT change `WALK_PER_BLOCK` (relax monotonicity floor `:1129`, `RegionCostField.MIN_CROSS`, heuristic
  scale). `DEFAULT` (no-inventory) `RegionPlaceModel` falls back to `PLACE_BASE_COST` + zero premium ≈ 2.29 —
  so headless/no-inventory bots keep ~legacy pricing; the fix only bites when a real inventory view (tool)
  is present. Confirm the `-Barehanded` bot supplies a real InventoryView with a tool-scaled cobble removal
  premium (else it'd fall back to DEFAULT and the fix would no-op).
- Verify: `-Barehanded` repro (does it stop pillaring?), pickaxe unchanged (descends), ~30 headless region
  tests green, paired A/B JMH region bench (dearer up → fewer up expansions — should help or be neutral).

Nothing lands until you approve the design-decision above and the measured checks pass.
