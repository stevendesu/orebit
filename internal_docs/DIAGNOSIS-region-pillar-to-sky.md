# Diagnosis — bot pillars to the world ceiling instead of descending (region-tier cost inversion)

Session 2026-07-13, mc-1.21 era, node `1.21.11`, JDK 21. Frozen master world (scripts/autotest-world-master),
start `(60,180,253)` = top of a 25-block jungle tree, goal `(201,-28,90)` = trial chamber ~240 blocks off and
~208 blocks DOWN. Owner saw a bare-handed bot in a live integrated client pillar straight to y=319 then bridge
across the world ceiling, overshoot to ~(264,72), and oscillate. HEADLESS-REPRODUCED (below).

## Root cause (verified: live `/bot rtrace` + code + headless repro)

The region A* prices **horizontal transit through an all-air (`air-no-floor`) region as a FREE WALK**:
`RegionPathfinder.WALK_PER_BLOCK = LeafCostComputer.AIR_TRANSIT_TICKS / LEAF = 1.0` tick/block. Crossing empty
mid-air costs 1/block — as if you could walk on air. Meanwhile `air-pillar` up = 96/region, `air-fall`/down =
cheap (`FALL_PER_BLOCK = 1.0`), and mining THROUGH a terrain region scales with the bot's TOOL: bare-handed
≈ 215/region, pickaxe ≈ cheap. Live region-trace candidate edges (bot at start):
```
walk sideways-in-air (y=176)  cost = 1.0 – 6.0     air-pillar up  cost = 96.0
walk DOWN into terrain (y=160) cost = 208 – 216
```
So the cost landscape is INVERTED: the empty sky is a ~1-tick/block highway; the real ground descent is a wall.
For a bare-handed bot the ground route is so dear that the region A* climbs the Y-region axis (7→12, up to
y≈320) through `air-no-floor` + `unbuilt` regions and bridges across the top — a 20 000-expansion flood,
`reachedGoal=false`, returning a partial UP route the bot then executes by pillaring. The 22 MB
`orebit-region-trace.txt` is that flood.

**The latent defect:** an `air-no-floor` region grants a cheap horizontal `walk` edge. Crossing a FLOOR-LESS
region actually requires BRIDGING (place-base cost per block, for a can-place bot) or is impassable (no-place
bot) — never a 1-tick/block walk. This is the region-tier analog of the block-tier open-air-pillar flood
(CLAUDE.md "octile heuristic blind to per-block place cost").

## Why it needs TWO ingredients (headless A/B, definitive)

| Setup | Result |
|---|---|
| pickaxe + clean master | descends |
| pickaxe + persisted HPA | descends (skeleton shrinks 33→4, still down) |
| bare hands + clean master | descends |
| **bare hands + persisted HPA** | **PILLARS y=180→319, bridges across** |

- **Persisted HPA** (built during the owner's high-altitude spectator flight) carries the SKY regions above the
  start pre-built as cheap `air-no-floor` → supplies the up-highway. A fresh grid doesn't price those sky
  leaves the same way, so the block tier descends first.
- **Bare hands** makes the ground descent's mine-through cost (~215/region) dwarf the free-air highway. A
  pickaxe makes mining-down cheap enough that the ground route wins — which MASKED the bug in every
  pickaxe-carrying headless run.

Ruled OUT as the cause (all descend headless): placement/mining base cost, `pathing.async`, view/simulation
distance, and either ingredient alone. NOT a teleport/unbuilt-nav timing race (owner waits 2 s, stands at
start in spectator).

## Headless repro recipe (fix-verification vehicle)

Master `scripts/autotest-world-master/world` carries the persisted HPA (from `Autotest Master - Copy (2)`,
md5 `2a04…`). Then:
```
scripts/run-autotest.ps1 -MasterWorld <master>\world -Start 60,180,253 -Goal 201,-28,90 -Barehanded -BudgetTicks 1500
```
→ Y climbs 180→319 (pillar). Drop `-Barehanded` (stone pickaxe) → descends. `-Barehanded` is the new harness
seam (`-Dorebit.autotest.barehanded`, HeadlessAutotest + build.gradle.kts + run-autotest.ps1).

## Fix — NOT YET IMPLEMENTED (region cost model is ratified + hot; owner design-review first)

Candidate direction: price horizontal all-air transit as BRIDGING (place-base per block) for a can-place bot
rather than `WALK_PER_BLOCK = 1.0`, so the sky stops undercutting the ground route. Open questions for the
owner: does this belong in `LeafCostComputer.AIR_TRANSIT_TICKS`, or is the deeper issue that terrain regions
are priced by vertical mine-through (215) when the real path follows the SURFACE (cheap)? Perf: the region
cost model is behavior-shaping and measured — treat any change as a design review + measured A/B.

## Artifacts (worktree, uncommitted)

`HeadlessAutotest.java` (+`barehanded` seam, start-relative probe, frozen-world default start `60,180,253`),
`fabric/build.gradle.kts` (barehanded/probeOnly keys), `scripts/run-autotest.ps1` (`-MasterWorld`/`-ProbeOnly`
/`-Barehanded`), `scripts/run-startprobe.ps1`, `scripts/autotest-world-master/world` (frozen master + persisted
HPA), `internal_docs/DIAGNOSIS-worldgen-nondeterminism.md`. Live traces in `run/orebit-*trace*.txt`.
