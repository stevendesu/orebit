# DESIGN — the climb/vine vocabulary arc (fall-arrest hang nodes + climb gap edges)

Status: DESIGN RATIFIED (owner 2026-07-31: arc delegated via HANDOFF-abilities.md item 4;
§2/§3 amended same day per two owner rulings — no jumps from climbable stances; run-length
fall-arrest bounds). Authored from the 8-reader recon (decompiled-1.21.11 physics + full code
forensics). Code Javadocs cite this file by §-anchor ("DESIGN-climb-vocabulary.md §3.1").

The arc makes the bot smart around ladders, vines, and scaffolding: jump-grab a climbable
overhead; arrest a fall INSIDE a passable climbable column (hang node); sink into a climbable
it stands on top of — and refuse, on physics evidence, what vanilla cannot deterministically
execute.

## §1 Physics ground truth (decompiled 1.21.11 mojmap, Vineflower; all claims source-verified)

- **onClimbable is a feet-cell state**: `LivingEntity.onClimbable()` tests only
  `getInBlockState()` (the block at `blockPosition()`) against `#minecraft:climbable`
  (ladder, vine, scaffolding, weeping/twisting/cave vines + `_PLANT`s), plus the open-trapdoor-
  above-same-facing-ladder special case. No head/body test. (LivingEntity.java:1695-1722)
- **The climb clamp runs pre-move**: while onClimbable, `handleOnClimbable` resets fallDistance,
  clamps horizontal to ±0.15, clamps ONLY downward to −0.15 (`Math.max(y, -0.15)`; upward is
  untouched), and holds a sneaking *Player* at 0 — **scaffolding explicitly exempt** from the
  sneak-hold. (LivingEntity.java:2583-2598)
- **"Climb up" = 0.2 post-move**: `(horizontalCollision || jumping) && onClimbable()` (re-tested
  AFTER the move) sets vy=0.2 for the next tick; gravity 0.08 ×0.98 drag makes the realized
  climb 0.1176 b/t. (LivingEntity.java:2560-2563, 2365-2388)
- **A hanging entity can NEVER jump**: the 0.42 `jumpFromGround` impulse fires only under
  `onGround()` (outside fluids). Holding jump on a climbable gives the 0.2 climb branch, not a
  jump. (LivingEntity.java:2954-2979)
- **A grounded jump with feet INSIDE a climbable is truncated**: if after the first +0.42 move
  the feet are still in a climbable cell, the post-move branch overwrites vy to 0.2 — an
  in-column ground jump degenerates to climb rate. (LivingEntity.java:2589 + 2561-2563)
- **Apexes** (from the verified update rule v' = (v−0.08)×0.98):
  - climb-out-the-top: last in-cell vy-set 0.2 → stored 0.1176 → feet peak **≈ +0.154** above
    the cell top. Feet can never gain +1.0 from a hang.
  - grounded 0.42 jump: displacements 0.42+0.3332+0.2481+… → apex **≈ +1.2522**; feet cross
    +1.0 during tick 3.
- **Fall arrest is automatic** when feet BEGIN a travel tick inside a climbable cell (pre-move
  clamp to −0.15 + fallDistance reset). Feet are sampled once per tick, so a fall step of
  dy b/t can only be GUARANTEED to sample inside a window of height > dy. Separately,
  `Entity.move` raycasts any ≥1.0-block displacement against `#fall_damage_resetting`
  (= #climbable + berry bush + cobweb, as full cubes) and resets fallDistance on any
  pass-through — resets DISTANCE, does NOT arrest. (LivingEntity.java:2583-2598;
  Entity.java:744-756)
- **Fall speed vs distance** (exact per-tick recurrence; terminal velocity = 0.08·0.98/0.02 =
  **3.92 b/t** exactly): speed stays < 1.0 b/t for ≈7.5 blocks of prior fall — the
  guaranteed-arrest regime for a 1-cell climbable, and THE shipped bound (flat
  `HANG_MAX_DROP = 7`, asserted against the recurrence by `HangBoundTest`). Longer-run
  relaxations (< 2.0 for ≈40 blocks → a 2-run catches; < 3.0 for ≈132; a ≥4-run catches from
  any height) are physically sound but deliberately NOT shipped — owner ruling 2026-07-31: the
  deep-column hangable sweeps they require cost TOWER +8-13% / FLOOD +14-18% in the paired A/B,
  and a vine arresting a long fall is too rare to tax every deep-drop scan for. §3.1's arrest
  detection therefore rides ONLY reads the pre-arc code already made.
- **Ladder**: real collision — a 3/16 full-face plate against the supporting wall (the face
  opposite FACING), full cell height. FACING is NOT packed in the descriptor (only stairs/doors
  pack facing), so plate side is invisible to the planner. Geometry consequences:
  - Standing on the plate top with ANOTHER same-side ladder in the cell above is IMPOSSIBLE
    (the stance strip and the upper plate occupy the same 3/16 through the body height), and
    the planner cannot distinguish same-side from alternating-side → plate stances are never
    planned as jump launches (owner ruling).
  - Entering a ladder cell from ABOVE is knife-edge: a centered 0.6-wide bot clears the plate
    by 0.0125 blocks (hang) vs feet catching the plate top (stand) — a nondeterministic landing.
  - Entering from BELOW is robust: a rising bot beside/overlapping the strip is pushed
    sideways, never caught — feet always enter the cell (jump-grab is safe).
- **Vine family** (vine, cave/twisting/weeping vines + plants): `noCollision()` — EMPTY
  collision, nothing to stand on, nothing to catch on — arrest position within the cell is
  exact.
- **Scaffolding**: in #climbable; `getCollisionShape` is context-dependent — stand-on-top
  2px slab (y14-16 + corner posts) only when the entity is above and NOT descending (sneak
  removes it → you sink in); a 2px bottom plate only for floating bottom blocks; **no collision
  inside**. Inside: jump → 0.2 branch (jumping alone suffices), sneak → −0.15 descent (the
  sneak-hold exemption), idle → −0.15 slide, horizontal clamped ±0.15. Falling onto scaffolding
  from above LANDS ON TOP (the stable shape catches any non-sneaking entity above) — never an
  interior entry. (ScaffoldingBlock.java:138-149; LivingEntity.java:2590)
- **Classifier truth** (adjudicated against `EntityCollisionContext$Empty` bytecode: `isAbove`
  returns the passed default, `placement=false`, no NPE): NavBlock's null-context query gets
  SHAPE_STABLE → unit-cube bounds → scaffolding classifies **SHAPE_FULL, topY 16, STANDABLE,
  COLLISION, BREAKABLE (hardness 0), CLIMB set, no NARROW_TOP** (2 navtypes, dry/waterlogged).
  Ladder classifies SHAPE_OTHER, topY 16, STANDABLE, COLLISION, NARROW_TOP, CLIMB. Vines
  classify SHAPE_EMPTY, passable, not standable, CLIMB. **No descriptor change is needed for
  this arc** — bit 20 is already present on every climbable.

## §2 Connectivity verdicts (what the search may and may not emit)

The two governing rules (owner-ratified 2026-07-31):
**R1 — no jump launches from climbable stances** (`solidFooting` floors only; plate/deck
stances never launch). **R2 — hang landings only where the landing position is deterministic**:
passable climbables (vines) within the flat guaranteed-arrest bound (prior drop ≤ 7), landing at
the run's BOTTOM cell (post-arrest the −0.15 slide converges every catch point there).

| Case | Verdict | Mechanism |
|---|---|---|
| Grounded on solidFooting, feet cell non-climb, climbable at feet+1 | **CONNECTS up** (jump-grab) | 0.42 jump, feet cross +1.0 into the climbable, arrest. Feet+2 is NEVER reachable (apex 1.25). Ladder bottoms included — from-below entry is robust (§1). |
| Ladder/air/ladder upward | **REFUSED** | R1: the plate stance can't launch, and same-side uppers make the stance itself impossible — undetectable without packing FACING. |
| Vine/air/vine upward | **REFUSED** | no stance, no jump; climb-exit peaks +0.154. **Corrects the handoff's "connect both ways" — downward only.** |
| Fall through a climbable cell | **REFUSED as transit** (the arcPassable principle: ±0.15 arrest) — a passable climbable IS the landing; a solid climbable (ladder/scaffold) still refuses the candidate. |
| Fall INTO a passable-climbable run (vines) | **CONNECTS down** as a hang at the run's bottom cell, when prior drop ≤ 7 (the flat §1 bound). Zero fall damage (arrest resets fallDistance). |
| Fall INTO vines beyond the bound | **REFUSED** (tunneling nondeterminism; deep-fall arrests deliberately unsupported — owner ruling). |
| Hang → gap below → vines/floor (chains) | **CONNECTS down** per-hop under the same ≤7 bound (each hop restarts from −0.15). Vine/air/vine descends any TOTAL depth when each gap fits the bound. |
| Gapped ladder column, downward | **REFUSED across the gap** (from-above ladder entry is the 0.0125 knife-edge, §1). Contiguous ladder columns still climb down normally (powered, in-column); the gap ends the column. |
| Standing atop a climbable (ladder plate / scaffold deck) → into the column | **CONNECTS down** (sink-in): ladder = recenter off the plate + gravity + arrest — deterministic (the plate can't re-catch feet already below its top); scaffolding = hold sneak, the top shape vanishes. |
| Scaffold column interior, vertical | **CONNECTS** both ways (cells carry CLIMB; jump=up 0.1176, sneak/idle=down 0.15). |
| Hang at scaffold-column top → deck | **CONNECTS up** (exit-top): pop +0.154, the full top face catches — robust. Gated `!isNarrowTop` so ladder plates are excluded. |
| Fall onto scaffolding from above | **standing landing on top** (vanilla catches you), priced with NORMAL fall damage. (Scaffolding is in #fall_damage_resetting, so vanilla may actually forgive it — empirical follow-up; overpricing is the safe error.) |
| Lateral grab INTO scaffolding | **REFUSED (new guard)** | the lateral-hold steer uses sneak; scaffolding is sneak-exempt → the bot sinks while crossing and block-exact reached never fires. Guard: grab allowed iff destination climbable `isPassable || isNarrowTop` (vine ✔, ladder ✔, scaffold ✘). |

## §3 Planner increments

Node model is UNCHANGED: hang nodes are ordinary `(x,y,z,MODE_STANDING)` nodes whose feet cell
`(x,y+1,z)` carries CLIMB (Climb's existing geometry-derived convention; no support invariant,
no new mode, no depth-nibble change — the nibble stays byte-identical).

`hangable(d) := isPassable(d) && isClimbable(d)` — the vine family exactly. One AND on an
already-loaded long; the single new predicate the arc introduces (MovementContext).

### §3.1 Fall: climbable arrest (transit-reject + hang landing)

`tryLanding`'s transit verify already reads EVERY descended cell's descriptor. Fold arrest
detection into that same loop — **single pass, byte-identical when no climbable is present**:

- The existing ascending verify loop (fy+1..y) additionally records the climbable cells seen
  (one extra AND per already-loaded long). No climbable found → the accumulated cost and
  emission are BIT-IDENTICAL to today (same reads, same float summation order).
- Any NON-hangable climbable in the column (ladder/scaffold) → emit nothing (today's refusal,
  now explicit).
- Hangable cells found: identify the TOPMOST maximal hangable run [runTop..runBot] in the
  column; prior drop = `y − runTop` (step-off feet at y+1, run entry plane at runTop+1).
  - prior drop > HANG_MAX_DROP (7) → emit NOTHING (the flat tunneling bound §1).
  - else → emit the HANG landing at the run's BOTTOM cell: node `(nx, runBot−1, nz)` (feet
    cell = runBot), cost = walk-off + fall ticks to runTop + slide ticks through the run
    (runLen−1 cells at 20/3 t — the −0.15 clamp) + per-cell transit priced over the shortened
    column (runTop+1..y, recomputed ascending — the rare branch pays the extra loop), **no
    fall-damage term** (arrest resets fallDistance before any impact), + ARREST_SETTLE (2 t).
    The deeper standable landing is NOT emitted (the fall never reaches it).
- The phase-1 legacy scan (nibble UNKNOWN/edited; spans to maxFall) checks per cell in its
  existing top-down order: first hangable → the run-walk + hang emission above (or a bound
  refusal); first standable → today's `tryLanding` unchanged. UNBUILT still refuses. The
  `fg==DEPTH_SAT` window and the phase-2 beyond-maxFall scan get NO hangable sweep (owner
  ruling — the measured TOWER/FLOOD regression lived exactly there; a vine in those windows is
  past the flat bound in every default-caps case anyway), and the softness-reject path returns
  with zero extra reads exactly as pre-arc.
- The nibble fast path needs no trust change: fg supplies the standable floor; the verify pass
  catches any climbable hiding in the (nibble-invisible) column and overrides/refuses. fg==0 /
  Descend-yield unchanged (a ladder at y−1 is standable → Descend steps onto the plate; sink-in
  §3.5 continues from there). Falling onto a scaffold TOP is the plain standable landing the
  nibble already reports — normal damage pricing, vanilla-exact.

### §3.2 Fall: in-column drop from a hang node

New emission, prefiltered by one AND on the node's feet descriptor (rare): when the node IS a
hang (feet cell hangable) and the cell below the node floor is passable-non-climbable, scan
straight down (dx=dz=0) with the SAME landing rules as §3.1 (hangable runs under BOUND → next
hang at run bottom; standable within the existing fall caps → standing landing, fall-damage
measured FROM the hang — fallDistance restarts there; ladder/scaffold cells → refuse). This is
"release and drop the column": vine/air/vine descends at any total depth. Fall owns descending;
Climb owns powered motion (the b25a23b ownership phrasing).

### §3.3 Climb: jump-grab (+1 across one air cell, solidFooting only)

From a node with `!onClimb` (feet cell y+1 NOT climbable — the truncation rule §1), floor cell
`(x,y,z)` **solidFooting** (standable && !climbable — owner ruling R1: ground yes, plate/deck
no; the predicate and its "no jump may launch from a ladder/vine floor" doc already exist),
cell `(x,y+2,z)` climbable of any kind (from-below entry is robust even for ladders, §1), cell
`(x,y+3,z)` passableOrClimbable (jump clearance; a ceiling at y+4 still lets feet reach +1.2):
accept `(x, y+1, z)` — the bot jumps, feet enter the climbable, arrest.
Cost JUMP_GRAB_COST = 3 (rise ticks to +1.0, §1 apex table) + 2 (arrest settle) + 3 (the
established jump-commit surcharge) ≈ **8 t**.
Covers: vine or ladder bottom overhead — "jump to reach the bottom of a vine or ladder".

### §3.4 Climb: exit-top (+1 from a hang onto a full-faced climbable top — scaffolding)

From a hang (`onClimb`), when the feet cell `(x,y+1,z)` is STANDABLE **and NOT narrow-top**
(scaffold deck; ladder plates excluded — the strip stance is unplannable, §1/R1) and
`(x,y+2,z)` is passable-non-climbable: accept `(x, y+1, z)` — climb out the top (+0.154 pop)
and settle grounded on the deck. Cost = CLIMB_UP_COST + 2 (settle) ≈ **10.5 t**.

### §3.5 Climb: sink-in (−1 from standing atop a climbable, into its column)

From a node with `!onClimb`, feet cell passable, whose FLOOR cell `(x,y,z)` is CLIMBABLE
(standing ON a ladder plate top or scaffold deck): accept `(x, y−1, z)` — enter the column
below (feet cell becomes the climbable). Execution: scaffolding = hold sneak (top shape
vanishes, −0.15 descent); ladder = recenter to the cell centre (off the 3/16 plate — the open
13/16 fits the 0.6 bot), gravity, arrest — deterministic, and a plate-edge re-land just retries
the converging recenter (the plate cannot catch feet already below its top face).
Cost = CLIMB_DOWN_COST + 2 ≈ **8.7 t**.
Eliminates the atop-ladder-plate dead end and answers "climb down into the underground base".

### §3.6 Climb: scaffold lateral-grab guard

The existing lateral grab adds one condition on the destination climbable descriptor:
`isPassable(d) || isNarrowTop(d)` (§2 last row). One AND on an already-loaded long.

## §4 Execution increments

- **BotSteering seam** gains two reads (the inWater()/solidAt() pattern):
  `onClimbable()` — surfaces the inherited vanilla `LivingEntity.onClimbable()` (present,
  un-overridden, across the range); `scaffoldingBelow()` — the block under the feet is
  `Blocks.SCAFFOLDING` (live read; range-stable direct reference per the file's convention).
- **Fall.plan done**: `footMatch && (grounded() || onClimbable())` — one predicate for both
  landing kinds. A standing landing still requires grounded (onClimbable false there); a hang
  landing fires arrested-in-cell (vine cells have no collision — footMatch position is exact).
  The FALL phase keeps recenterOnTarget (forward eases to ~0 centred, so the
  horizontalCollision→0.2 ratchet risk is idle-level). The **Fall failWhen envelope stays
  deferred** (the standing owner-flagged item; this arc narrows the wedge class via the hang
  done but does not claim it).
- **Climb.steer** (legacy hook, extended in place):
  - Δy > 0.1: `setJumping(true)` — UNCHANGED, and already correct for §3.3/§3.4: grounded →
    real 0.42 jump (grab); onClimbable → 0.2 climb (exit-top). Block-exact reached (ungated —
    Climb doesn't commit) fires mid-pop/mid-grab as needed.
  - |Δy| ≤ 0.1: sneak-hold — UNCHANGED (ladder/vine); scaffolding laterals are refused
    planner-side (§3.6).
  - Δy < −0.1: today no-input (in-column slide ✔ unchanged). NEW grounded branch (the sink-in
    step): `grounded && !onClimbable()` → hold sneak when `scaffoldingBelow()`, else no input +
    recenter (ladder plate: sneak would edge-guard-pin the bot ON the plate; recenter-to-centre
    + gravity performs the sink).
- **Anchors/arrival**: `planAnchor` already admits a hang (grounded || inWater || inLava ||
  onClimbable — verified live); ARRIVAL keeps the narrower stableMedium — a goal ON a hang
  never "arrives" and the bot slides to the column base and arrives grounded. Accepted v1
  behavior, documented.
- **`solidFooting` gains a THIRD conjunct** (found by the suite: the sink-in hang node — solid
  stone floor, feet in the ladder cell — passed the floor-only gate and got offered a Parkour
  launch): the FEET cell must be non-climbable too, because vanilla truncates a grounded jump
  whose feet start inside a climbable back to the 0.2 climb (§1) — the 0.42 launch never
  happens. This also fixes a PRE-EXISTING latent bug: the walk-into-a-floor-vine stance (feet in
  vine over stone — reachable via plain Traverse since forever) was being offered ballistic
  jumps. One extra descriptor read per takeoff gate (Parkour/DiagonalParkour/WalkOff); honest
  for WalkOff too (the ±0.15 clamp kills its momentum-preserving crossing).

## §5 Non-goals / v1 bounds (each with its reason)

1. **No climbable-gap ascent of any kind** — vine: no stance; ladder: R1 + the same-side
   impossibility + unpacked FACING (§1). A gapped column ascends via Pillar/break or not at all.
2. **Gapped ladder columns don't descend across the gap** — the from-above 0.0125 knife-edge.
   Contiguous ladder columns are unaffected.
3. **Flat HANG_MAX_DROP = 7** — the 1-cell sampling proof (§1), asserted from the recurrence.
   Run-length relaxations and deep-window hangable sweeps deliberately dropped (owner ruling —
   the measured regression; deep falls onto climbables are refused, not detected).
4. **Scaffold lateral grabs refused** (§3.6) — the sneak-hold exemption. A duty-cycled
   jump-hold lateral is future executor work.
5. **Scaffold-top fall landings price normal damage** — vanilla may forgive them
   (#fall_damage_resetting raycast); empirical card decides later; overpricing is the safe error.
6. **Fall failWhen envelope deferred** (§4) — separate owner-flagged item.
7. **Trapdoor-above-ladder** climbable extension ignored (planner never emits into it).
8. **Tag drift**: NavBlock's climbable list is identity-based (not `#climbable`); modded/datapack
   climbables are invisible to the planner (pre-existing, unchanged).
9. **Waterlogged climbables**: fluid kills STANDABLE — existing classification; swim moves own
   wet columns.

## §6 Performance contract

**Standing rule recorded during this arc (owner, 2026-07-31): NO section-level `anyX` prefilter
bits** for block-tier performance — only `anyPortal` is permitted (it changes REGION-planning
semantics). The measured TOWER/FLOOD regression was resolved by SHRINKING the arrest feature's
coverage (the flat ≤7 bound, no deep sweeps) rather than by an `anyClimbable` grid bit.


- All new search-side work rides descriptors ALREADY LOADED per cell (§3.1 single-pass; §3.2's
  emission is prefiltered by the node's own feet descriptor — one AND, zero cost for non-hang
  nodes; §3.3-§3.6 are one-AND gates on reads the moves already make). No new grid reads, no
  allocation, no new branch on any per-read path outside Fall/Climb candidate emission.
- Clean-column Fall emission is bit-identical (same reads, same float order) — the JMH suite
  (CLIFFS targeted; SHORT/MULTI setup guards; full scenario set, paired interleaved A/B,
  mode-matched) gates the arc like every hot-path change.

## §7 Test plan

Unit (suite, JDK 21 `:1.21.11:test`):
- ClimbTest additions (worldmodel/pathing, synthetic NavGridView): vine/air/vine DOWN plan
  exists (hang chain) and UP has NO plan (refusal pin); ladder/air/ladder has NO plan in
  EITHER direction (refusal pins, both owner rulings); jump-grab from ground reaches a ladder
  bottom and a vine bottom; jump-grab REFUSED from a plate/deck stance (solidFooting pin);
  fall-arrest: vine run at depth ≤7 → plan lands the hang at run bottom (not a deeper floor);
  single vine at depth >7 in a sealed shaft → NO plan; 4-run vine curtain at depth 30 → plan
  (unbounded-N pin); sink-in from atop-ladder-plate and atop-scaffold reach the column base;
  scaffold interior vertical connectivity + exit-top onto the deck; scaffold lateral-grab
  refusal.
- ClimbSteerTest additions: grounded Δy=+1 (jump held); grounded Δy=−1 scaffold floor (sneak)
  vs ladder floor (no sneak, recenter); hang Δy=−1 unchanged (no input).
- A BOUND-table unit test asserting the derived floors (7/32/110/∞) against the recurrence.

In-world (mc-1.21 era, ParkourCourse west-cells — explicit bases (−18,60)+(−18,86)… inside the
boot bubble, NEVER appended into the nav-dead tail; sanity-check non-zero searches):
- climbgrab1 (jump-grab a ladder bottom), vinegapdown (vine/air/vine descend),
  ladgapup.refusal + vinegapup.refusal (expectRefusal), fallvine2/fallvine6 (arrest depths),
  fallvine9.refusal (single-cell bound), fallcurtain (4-run deep arrest), sinkladder1,
  sinkscaffold1, scaffoldtop (exit-top). Verdicts per the existing card machinery.
- The frozen-world climb autotest (`run-autotest-climb.ps1`) re-run as a regression check.

## §8 Docs increment (after code lands)

- docs/movements.md: Falling §(88-101) — arrest rule, hang landings, the run-length bounds,
  no-damage derivation; Climbing §(109-120) — the new edges, the refusal story (no gap ascent),
  scaffold laterals; roster rows + derived costs; the Parkour section's refuse-list gains the
  shipped arc-gate sentence (stale since b25a23b).
- Fix the two stale code-doc claims (MovementContext.java:946-947, Climb.java:23-24 — ladder IS
  standable; scaffolding is SHAPE_FULL+standable+climb).
- MOVEMENT-DESIGN.md §2 gains one pointer line to this card. CLAUDE.md's "Pillar and Parkour
  execute via plan()" note is stale (14 moves convert) — correct in the same pass.
- HANDOFF-abilities.md item 4: record the vine-up refutation + both owner rulings + this card.

## §9 The held-jump × climbable-transit elevator (2026-07-31, log-convicted + fixed)

The flagship class witnessed 2026-07-31 22:12 (near-vertical deviation up a vine curtain into
the leaf underside during an Ascend chain), reproduced deterministically by the ParkourCourse
`ascvine.*` cards and convicted from the per-tick `j/c/h` trace columns.

**Convicted mechanism** (`ascvine.face`, pre-fix): Ascend's climb-phase drive held
`setJumping(true)` unconditionally. The tick the bot's FEET cell samples a climbable in the
landing stance, vanilla's `jumping && onClimbable → +0.2/t` (§1) converts the held jump into a
climb: the bot rises THROUGH its own landing stance, and at the curtain top enters a 4-tick
hover limit cycle — one `c=1` tick re-launches it off every falling re-entry, so with jump held
it can never descend through a vine cell and never grounds. `done`/`resetWhen`/`failWhen` are
all settled-gated (grounded/inWater/inLava) and a hover is none of those — structurally
un-terminable. Measured: 370 ticks dead-centred on the target column, feet one block above the
stance, `h=0` throughout (the horizontalCollision arm was NOT involved; the held jump alone
sustains the class).

**Two refinements the cards forced on the hypothesis**: (1) walk momentum defeats
takeoff-column capture (`ascvine.pin` WALKIN passes even pre-fix — the centre crosses the
column boundary before the feet sample the curtain); the dangerous entry is the MOMENTUM-LESS
one (`ascvine.pin.rest`, the flagship's chained-second-Ascend condition). (2) Pillar is
structurally SAFE: its held jump is phase-bounded BELOW the capture heights (the `jump` phase
advances at `y ≥ fy+2` into `place`/`land` drives that never press jump — a feet-cell vine
gives a slow +0.2 rise to the advance threshold, then a clamped settle onto the placed
footing). No other converted move holds jump across its landing stance.

**The fix (execution, Ascend climb drive)**: hold jump only while the climb still NEEDS
height — `setJumping(!(onClimbable() && footY() >= landFootY))`. Below the target the held
press is right in every medium (ballistic launch on land, +0.2/t ratchet up a curtain, swim-up
in water — a water cell is never a climbable cell, so the swim-out behaviour cannot overlap);
at/above it in a climbable, release and let the −0.15/t descent clamp settle the bot onto the
floor where `done` fires. Post-fix traces: capture→done in 7 ticks (`ascvine.face`), and the
REST entry's eastward steer during the clamped descent carries it across the column boundary
onto the landing with no bounce loop (`ascvine.pin.rest`, capture→done in 5 ticks).

**Open (owner ruling required — the generic net, NOT implemented)**: widen the failWhen
envelopes' settled-set to include `onClimbable()` (a hang is a stable stance — the bot can
stay indefinitely, so it IS "settled" for off-plan purposes). That would make ANY residual
capture shape (e.g. transient hcol ratchets beside curtains on forward-driving moves) fail
fast into a replan-from-the-hang instead of hovering invisible to every envelope. Blast
radius: every converted move's failWhen; the fixed Ascend transit stays in-band (verified
against the post-fix traces), but this is a cross-move behaviour change and waits for the
owner.
