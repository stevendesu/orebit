# DESIGN — Trapdoor-Ladder Climb

Status: **implementing** (owner brief 2026-08-09: "populate the FACING bit for ladders, update
Climb to allow climbing on trapdoors iff equal-facing ladder below, ensure edit folding understands
we can open a trapdoor to allow such a climb, then figure out how/if we need to update the
follower"). Closes the `DESIGN-trapdoors.md` §10 deferred "ladder-climb rule". Recon evidence:
session reports `trapdoor-ladder-vanilla.md` / `trapdoor-ladder-bot.md`; durable facts folded here.

## §1 The vanilla rule (bytecode-pinned, 1.17.1 → 26.2, drift-free)

`LivingEntity.onClimbable()`: feet-cell state in `#minecraft:climbable`, OR the hardcoded special
case `trapdoorUsableAsLadder(pos, state)` =

- feet cell is **any** `TrapDoorBlock` (wood AND iron — `instanceof`) with **`OPEN == true`**, and
- the cell **below** is exactly **`Blocks.LADDER`** (block identity — vines/scaffolding do NOT
  qualify), and
- **`LadderBlock.FACING == TrapDoorBlock.FACING`** (`HALF` and waterlogging not consulted).

Climbability of the trapdoor cell is therefore **neighbor-dependent** — it can never be a
descriptor bit. Consumption is the generic `LivingEntity.travel` physics: the +0.2/tick rise gates
on `horizontalCollision || jumping` (the bot must press into the face or hold jump; occupancy alone
only clamps descent to −0.15). The clientless bot runs the full vanilla tick, so the executor
climbs for free **once the follower keeps the right inputs held** (§5).

## §2 Descriptor — ladder FACING joins shared bits 8–9 (owner-ratified)

- `LadderBlock` packs `FACING` into the shared facing bits 8–9 (today stair XOR door XOR trapdoor;
  ladder joins the XOR family). Expands the single ladder navtype into 4. No new bits.
- Discriminator: `isLadder(d)` — recon claims `CLIMB_BIT ∧ SHAPE_OTHER` is unique to ladders
  (vine/scaffolding classify differently); the implementer MUST verify that uniqueness against the
  full interned table (assert in tests: no non-ladder navtype satisfies the predicate) and, if it
  does not hold, derive a sound discriminator without new bits.
- Vines/scaffolding do NOT pack facing (the vanilla rule is ladder-identity-only).
- Accessor `ladderFacing(d)` gated on `isLadder`. Bit-identity: every registered ladder state's
  descriptor pinned against the classifier (facing × waterlogged sweep).
- No `CostPyramidCodec.VERSION` bump (flood outputs unchanged — ladder cells were and remain
  flood-relevant only via existing bits).

## §3 Planner — Climb learns the neighbor rule

New `MovementContext` predicate (name suggestion `trapdoorClimbable(dFeet, dBelow)`):
`openTrapdoor(dFeet) ∧ isLadder(dBelow) ∧ trapdoorFacing(dFeet) == ladderFacing(dBelow)`. All
tests hide behind the almost-always-false `openTrapdoor` bit test; Climb runs per-pop, not
per-read; trapdoor-free worlds stay byte-identical.

Sites (recon-verified against Climb.java at `0aaa3fd`; line refs may drift):

- **Climb-continue up** (~:192): entered feet cell may be climbable OR trapdoor-climbable — the
  below cell for the entered rung is the CURRENT node's feet cell, whose descriptor is already in
  hand (zero extra reads).
- **EXIT-TOP** (~:200): today requires a plain-`passable` feet destination. Two changes: (a) from a
  ladder rung whose above cell is a trapdoor-climbable mouth, the climb continues (case above) and
  EXIT-TOP then fires FROM the mouth cell onto the rim; (b) verify the EXIT-TOP feet gate itself
  needs no widening beyond that (the mouth cell is entered by climb-continue, not by EXIT-TOP).
- **Descent / top-entry** (top→bottom): the implementer must FIRST pin how plain-ladder-column
  top-entry works today (unknown at design time — recon only walked the up direction). Whatever
  emission lets the bot step from a rim floor into a plain ladder column top must learn the
  trapdoor-mouth variant: entering the open-trapdoor mouth cell from the rim, then descending
  through it onto the ladder. If NO plain-ladder top-entry exists today, that is a PRE-EXISTING gap:
  report it, still add the trapdoor variant beside whatever mechanism does exist (e.g. WalkOff into
  the shaft is refused — ladder cells are not passable — so if nothing works, say so loudly; the
  course's control tiles will show it live).

## §4 Edit folding — opening the hatch to climb (owner-ratified scope)

- **From below (bottom→top, closed mouth):** a new Climb toggle arm — when the cell above a ladder
  rung holds a CLOSED trapdoor that would be trapdoor-climbable if open (`isTrapdoor ∧ ¬open ∧
  isLadder(self) ∧ facings equal`), fold `SET_OPEN` on the mouth cell (the existing
  `EditScratch.setOpenable` fold shape; gated `caps.mayToggleDoors ∧ handToggleable` — an IRON
  closed mouth refuses and routes around/gives up, while an iron OPEN mouth climbs fine, §1).
  Climb is currently edit-free; its `plan()` must now surface the `requireTrapdoor`/`Need.OPEN`
  pre-pass need for that step — the MovePlan/PhaseRunner machinery is movement-agnostic and should
  carry it; verify, don't duplicate.
- **From above (top→bottom, closed mouth):** check what the existing Descend/MineDown hatch arms
  already offer on this geometry before adding anything (the trapdoor arc's hatch-drop opens a
  trapdoor underfoot; with a ladder column below, the post-open route should be the §3 descent, not
  a priced 14-block fall). Add a top-entry toggle arm ONLY if the existing arms provably cannot
  reach this geometry — smallest sufficient change, no speculative arms.

## §5 Follower

`AllyBotEntity.climbableBelow()` (~:1094) reads `BlockTags.CLIMBABLE` → false on the trapdoor
mouth, and it gates `Climb.reached` and the top-out jump-hold — without a fix the final node
livelocks (the measured curtain-case shape). Fix: teach it the §1 rule. Prefer vanilla logic over a
hand-rolled copy where practical — the bot IS a `LivingEntity`, and `onClimbable()` is the very
predicate physics uses; where the check is about a cell the bot is not yet inside, mirror the §1
predicate exactly (live tick-thread reads are legal there). Keep the jump-hold/press-into-face
input shape the physics needs (§1). NO timers, no recovery — if the climb stalls, the existing
envelope/log-and-hold rules apply.

## §6 Region tier & persistence

- Region tier: ~~ZERO work (mouth already flood-passable via openable-as-air; ladder-column flood
  blindness is pre-existing and out of scope)~~ **SUPERSEDED same day (owner follow-up ruling
  2026-08-09): climbable-as-air.** `NavBlock.floodPassable` gained the CLIMB disjunct
  (`isPassable || openable != NONE || climbable`) — "a ladder is passable on 5/6 faces, and the
  other face adjoins a wall by definition. You can always walk past a ladder, and it's arguably
  MORE passable than air since you can climb up and down it." Ladder (SHAPE_OTHER) and scaffolding
  (SHAPE_FULL) rows join fragment membership + goal resolution; vines were already SHAPE_EMPTY
  passable. Flood MEMBERSHIP + goal resolution only — `standable[]` and every block-tier predicate
  untouched; optimism absorbed by `RegionEdgeBlacklist` + capsSig invalidations (the §8b pattern).
  **Half-fix asymmetry:** a flush bare-ladder shaft now region-connects BOTH ways, but the block
  tier still has no bare-ladder TOP-ENTRY (NARROW_TOP, tracked outside this arc) — so top-down
  bare shafts region-connect then blacklist-absorb honestly, while bottom-up routes plan end to
  end **only when the ladder itself reaches rim level** (the `TrapdoorLadderClimbTest.plainShaft`
  pin geometry). A ladder stopping below a bare mouth is a TWO-sided block-tier gap (probed
  2026-08-09: `findPath` null in either direction — the §3.4 exit-top tops out ON the NARROW_TOP
  plate one below rim feet, which no jump/walk move may leave), so `ShaftCourse`'s plain tiles run
  the ladder through the mouth cell to match the pin (control-plain-bottomup now expects ARRIVAL;
  control-plain-topdown keeps GAP).
  `CostPyramidCodec.VERSION` stays 1 per the pre-release pin; the flood-semantics change is a
  dated history entry in its Javadoc (stale dev caches carry ladder-blind connectivity until
  rebuilt or `<world>/orebit/` is deleted).
- ~~`INVAL_SIG_SCHEMA_VERSION` 4 → 5~~ **SUPERSEDED mid-arc (owner ruling 2026-08-09): all
  persistence versions PINNED at 1 pre-release** — zero wild installs, so the planner-strength
  staleness (persisted negative crossings recorded pre-arc can now be false — a shaft crossing
  becomes realizable) ships as the sig-schema history's v5 ENTRY in the codec Javadoc while the
  constant stays 1. Stale dev caches: delete `<world>/orebit/`.

## §7 Tests + live course

Unit/planner (the gate-arc idioms):
- `LadderClassificationTest` — facing packed ×4, waterlogged sweep, bit-identity vs interned
  states, `isLadder` discriminator UNIQUENESS over the whole table.
- `TrapdoorLadderClimbTest` (planner) — the owner's shaft in synthetic grids: bottom→top open
  (route found, feet pass through mouth cell, zero edits), bottom→top closed (route with exactly
  one SET_OPEN at the mouth), facing-MISMATCH control (refuses — no route), iron-closed control
  (refuses), plain-shaft controls, top→bottom variants per §3 findings, `mayToggleDoors=false`
  closed control (refuses).
- `PhaseRunner` coverage only if Climb's plan() gains needs (the pre-pass on a Climb step).

Live course — `ShaftCourse` (**BUILT, run pending** — GateCourse clone; `-Dorebit.shaft`; `runShaft`
task + `scripts/run-shaft.ps1` + `scripts/shaft/` templates; result
`run/shaft/orebit-shaft-result.properties`, trace `orebit-shaft-trace.txt`): flat stone layers, 1×1
bore (depth ~10), full ladder column, trapdoor mouth (facing == ladder facing; per-tile PROBE dumps
the placed mouth+rung states as the facing double-check). Tiles: (1) top→bottom OPEN, (2) bottom→top
OPEN, (3) top→bottom CLOSED (bot opens it itself), (4) bottom→top CLOSED (bot opens from below), plus
(5) plain-shaft top→bottom and (6) plain-shaft bottom→top as PRE-EXISTING-GAP CONTROLS (no trapdoor —
they record the distinct verdict `GAP`, excluded from `failed=`, so a no-route reads as pre-existing,
not this arc), and (7) iron-closed bottom→top refusal control (honest give-up via `navGaveUp` behind
the nav-readiness gate, hatch untouched, zero toggles, never past the mouth plane). Arrival asserted
alive at FULL health for the WHOLE trial — a per-tick MINIMUM-health watch, not an arrival sample,
because the peaceful course world regenerates (naturalRegeneration default-on + hunger-off pins food
full → saturated fast-regen, javap-verified `FoodData.tick`) and an arrival-only sample would let a
small recovered fall heal back to full inside the budget (the climbed-vs-fell discriminator — a
10-deep plummet costs real HP under `takesDamage=true`); closed tiles assert exactly one own toggle
via the per-tick hatch watch (no redstone on any tile, so every state change is bot-authored).

## §8 Deferred / out of scope

- Vines/scaffolding facing packing (vanilla rule is ladder-only).
- ~~Ladder-column region-flood blindness (pre-existing).~~ CLOSED by the 2026-08-09 climbable-as-air
  follow-up ruling (§6) — what remains open is the BLOCK-tier bare-ladder top-entry (NARROW_TOP),
  tracked outside this arc.
- Any Climb macro/run compression (Climb stays per-cell rungs).
- The stale Climb class-doc claim that Ascend handles top-outs (recon-found): FIX the comment in
  passing (doc-only), but no Ascend behavior change.

## §9 Rulings log

- 2026-08-09 owner: ladder FACING into shared bits (1 navtype → 4) — ratified in the brief.
- 2026-08-09 owner: edit folding may OPEN a trapdoor to enable the climb — ratified in the brief.
- 2026-08-09: INVAL_SIG 4→5 per §6 (planner-strength staleness class, v3/v4 precedent) — SUPERSEDED
  same day by the owner's pre-release pin-at-1 ruling; ships as a Javadoc history entry instead.
- 2026-08-09: iron trapdoors climb when open, refuse toggling when closed (vanilla `instanceof` +
  the family handToggleable rule).
- 2026-08-09 owner (follow-up, post-merge): climbable-as-air region flood — "a ladder is passable
  on 5/6 faces, and the other face adjoins a wall by definition. You can always walk past a
  ladder, and it's arguably MORE passable than air since you can climb up and down it."
  Scaffolding and vines included. Flood membership + goal resolution only; see §6.
