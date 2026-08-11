# DESIGN — Trapdoors (RATIFIED 2026-08-08, implementation arc)

**Status:** RATIFIED. Owner rulings 2026-08-08 + the 2026-08-09 region-tier openable-as-air flood
(§8b) — see §11. Supersedes the earlier "future arc" draft.
Trapdoors reuse the shipped DOORS machinery (SET edits, `Need.OPEN` executor pre-pass, `doors.toggle`
caps gate) and add: shared-bit trapdoor classification, a 6-way blocked-FACE model, generalized
residual-clearance rules, toggle folds on the vertical movement family, a same-level jump arm, and a
hand-rolled executor verb. Crawl/crouch, the ladder-climb rule, waterlogged swim modeling, and two
fractional-pricing refinements are explicitly DEFERRED (§10).

## §1 Vanilla facts (bytecode-verified 1.17.1 → 26.2; 13 versions javap'd)

Blockstate: `facing` (4 cardinals, inherited HORIZONTAL_FACING), `half` (TOP/BOTTOM), `open`,
`powered`, `waterlogged` — the property SET is drift-free across the whole range (only the Java type
of FACING changes at 1.21.2, `DirectionProperty`→`EnumProperty<Direction>`; serialized names stable).

Collision shapes — numerically constant across the range (explicit AABBs ≤1.21.4; `SHAPES =
Shapes.rotateAll(Block.boxZ(16,13,16))` from 1.21.5, equivalence verified):
- **closed, half=BOTTOM** → `(0,0,0, 16,3,16)` — a 3/16 floor plate.
- **closed, half=TOP** → `(0,13,0, 16,16,16)` — plate flush with the cell top; 13/16 gap beneath.
- **open** → 3/16-thick full-height panel on the wall **opposite `facing`** (facing=N → panel on the
  south strip z 13..16). `half` does NOT affect the open shape. getShape == collision == support.
- So 6 collision-distinct shapes total: 2 closed (per half) + 4 open (per facing).

Interaction: hand-toggle works for every trapdoor except IRON at every version (copper trapdoors,
added 1.20.3, ARE hand-openable — `BlockSetType.COPPER.canOpenByHand=true`). Gate: `material==METAL`
≤1.19.4, `BlockSetType.canOpenByHand()` ≥1.20; `Blocks.IRON_TRAPDOOR` is the complete vanilla
exception set → the door idiom `block != Blocks.IRON_TRAPDOOR` is range-stable and vanilla-complete.
**`TrapDoorBlock` has NO `setOpen` convenience at any version** (unlike `DoorBlock`) — toggle is
`state.cycle(OPEN)` + `setBlock(pos, s, 2)` + sound/`GameEvent.BLOCK_OPEN/CLOSE` + (if waterlogged)
a water fluid tick. Redstone forces `OPEN := signal` on powered-flag CHANGE, both directions, under
anyone's feet; wind charges toggle unpowered trapdoors from 1.20.3. Toggling never changes
`waterlogged` (a waterlogged cell is a full water source regardless of pose).

Blocks: 9 at 1.17.1 (8 wood + iron); +mangrove 1.19, +bamboo 1.19.3, +cherry 1.19.4, +8 copper
1.20.3, +pale_oak 1.21.2; 26.x collapses the 8 copper Blocks-fields into one `WeatheringCopperCollection`
(blocks still individually registered). `instanceof TrapDoorBlock` is a complete, stable detector
(copper unwaxed = `WeatheringCopperTrapDoorBlock extends TrapDoorBlock`).

Fit math (drives §4): pose fit = `noCollision(bbox.deflate(1.0E-7))` with STRICT AABB overlap; player
1.8 standing / 1.5 crouch / 0.6 crawl. A 1.8 bot fits a 1.8125 gap (even 1.8 exactly), fails 1.625
standing (vanilla would auto-CROUCH there; we have no crouch mode → refuse, §10).

Vanilla mobs: `WalkNodeEvaluator` maps `#trapdoors` → `PathType.TRAPDOOR` state-blind (OPEN never
read) — the "mobs fall in the hole" bug. Do not copy. The climbable rule (`LivingEntity.
trapdoorUsableAsLadder`, present 1.17.1→26.2): OPEN + exactly `Blocks.LADDER` directly below +
EQUAL facing. Planner modeling deferred (§10); the executor's `onClimbable` gets it free.

## §2 Descriptor plan (ZERO new bits — owner-ratified)

Shared fields, discriminated by `openable` (bits 14–15: `0 NONE / 1 DOOR / 2 TRAPDOOR / 3 GATE`,
already packed for all three families):
- **facing** bits 8–9 (today stair XOR door) — now stair XOR door XOR trapdoor.
- **half** bit 10 (today stairs-only; slabs do NOT use it — slab-ness is SHAPE_SLAB_*) — now
  stair XOR trapdoor. 1 = TOP.
- **open** bit 43 (today doors-only) — now door XOR trapdoor.
- **toggleable** bit 50 = generic "hand-toggleable"; set for every `TrapDoorBlock` except
  `Blocks.IRON_TRAPDOOR` (door idiom). Kind discrimination is `openable`, NOT a new bit.
Geometry fields keep their generic derivation (closed-bottom: topY=3 SHAPE_PARTIAL_LOW STANDABLE;
closed-top: topY=16 SHAPE_OTHER STANDABLE; open: topY=16 SHAPE_OTHER + NARROW_TOP, not standable).
`POWERED` stays unread (powered variants dedup; hand-toggle works regardless, redstone re-forcing is
an external change the grid repatch absorbs).

**`withTrapdoorOpen(d, open)`** — unlike `withDoorOpen` (bit-43 flip only), the trapdoor version must
RE-DERIVE geometry: topY (open?16 : half=TOP?16:3), shape (open?OTHER : half=TOP?OTHER:PARTIAL_LOW),
NARROW_TOP (open only), then re-derive STANDABLE/BREAKABLE/COLLISION/OPEN_PLACE. Non-geometry fields
(fluid, hardness, tool, protected, transit, …) carry over. **Pinned by test: for EVERY trapdoor
blockstate S and both targets o, `withTrapdoorOpen(desc(S), o) == desc(S.setValue(OPEN, o))`
bit-for-bit** (the resolver must reproduce the interned real state exactly).

Unified SET resolver: `NavBlock.withOpenableOpen(d, open)` = isDoor → withDoorOpen; isTrapdoor →
withTrapdoorOpen. `PathEdits.SET_OPEN/SET_CLOSED` are reused unchanged — no new edit kinds.

Navtype budget: facing×half×open splits grow trapdoor navtypes from ~3 to ~16 per behavior family
(×2 waterlogged, ×~3 families wood/iron/copper) — a low-tens-of-navtypes addition against the **1024 cap**
(the 10-bit navtype field, the one durable number here). The "on ~433 live" baseline this line used to
quote was removed 2026-08-11: the live count moves with the MC version *and* at runtime with
`mining.protectedBlocks`, so no such figure can be written down as fact — measure it in your own config
from the boot line `[Orebit] NavBlock: … states -> … navtypes`.

## §3 Blocked-FACE model (6-way)

`trapdoorBlockedFace(d)` derived at query time (like `doorBlockedEdge`, never stored):
- closed, half=BOTTOM → **DOWN**; closed, half=TOP → **UP**;
- open → the cardinal **opposite facing** (facing N→S, S→N, E→W, W→E) — the wall the panel hugs.
Encoding: 0..3 = door-edge cardinal convention, 4 = UP, 5 = DOWN. The other 5 faces are crossable
(subject to §4 clearance). Crossing the blocked face requires a SET fold (§5) — toggling always
clears the crossed face (closed↔open swaps the blocked face to a perpendicular set), same principle
as doors. Iron (toggleable=0): blocked face is a wall — break-fold applies (subject to PROTECTED —
default config protects all trapdoors from BREAKING; the toggle path bypasses PROTECTED per the
ratified door stance, ProtectedBlocks.java:72–76).

## §4 Clearance model — generalized residual (owner-ratified "generalized")

All in integer 16ths; floor topY `t` of the node's floor cell; body cells c1 = floor+1, c2 = floor+2
(when the floor cell is a partial block, the bot's body starts inside the floor cell itself — the
formulas below already account for that via `t`).

- **Exact fit rule**: body top = t/16 + 1.8 above the floor-cell base. Therefore:
  - **c2 is not consulted at all when `t ≤ 3`** (body top ≤ 1.9875 < 2.0) — this admits standing on
    a floor plate in a 2-tall hallway with a hard ceiling.
  - Otherwise c2 admits: passable (edit-aware), OR open-trapdoor (§ below), OR a **uniform high
    ceiling**: `ceilingMinY(c2) ≥ t − 3` (the −3 is the integer-conservative form of −3.2).
- **`ceilingMinY(d)`** — derived, only shapes whose collision is confined to a FULL-footprint band at
  the cell top qualify: closed-TOP trapdoor → 13; `SHAPE_SLAB_TOP` → 8; everything else → 0
  (notably top-half STAIRS are 0 — their riser descends to the floor on one side, unsound without
  direction math). Worked admissions: full floor (t=16) under closed-top trapdoor (13 ≥ 13 ✓) but NOT
  under a top slab (8 < 13 ✓ refuse); slab floor (t=8) under top slab (8 ≥ 5 ✓) or closed-top
  trapdoor ✓; plate floor — c2 irrelevant.
- **c1 (lower body cell)** must be passable OR **open-trapdoor** (a 3/16 wall-hugging panel coexists
  with the centered 0.6 body: worst case opposite-wall panels leave 10/16 = 0.625 > 0.6). ANY closed
  trapdoor in c1 bisects → blocked (this is what refuses the 1.625 double-plate hallway and both
  head-bisection cases from the owner's brief).
- **Stacked-panel soundness (owner edge case, 2026-08-08)**: the per-cell open-trapdoor exemption is
  sound for EVERY stacked combination, because a cell holds at most one 3/16 panel against one wall:
  a single panel leaves 13/16; two open panels in c1/c2 on OPPOSITE walls (opposite facings) leave a
  10/16 = 0.625 slot — strictly wider than the 0.6 body, admitted. The follower threads a ±0.0125
  center window there; panels parallel to travel act as guide rails (clipping one slides the body
  along it, forward motion unimpeded), so the centered drive passes without special handling. The
  per-cell blocked-face check still applies independently in each cell: a panel ACROSS the travel
  direction is a blocked-face crossing regardless of width.
- **Open-trapdoor cells are body-passable for occupancy and transit** (Traverse bodies, Fall/Climb
  columns, Pillar shafts) in every direction EXCEPT across their blocked face. This is a predicate
  (`openTrapdoor(d)` = openable==TRAPDOOR && open), not a descriptor change. **Diagonal is the one
  carve-out**: its corner/body cells do NOT get the open-panel exemption (a diagonal path is not
  wall-parallel, so the guide-rail argument fails and corner-clip math would be needed — keep
  Diagonal passable-only, conservative).
- Jump headroom (Ascend/Parkour y+3, HEADROOM_JUMP) stays whole-cell conservative in v1.
- The HEADROOM grid flags (whole-cell walkClear) are untouched — they remain the strong fast path;
  the residual rules live in the exact fallback (`requireBodyClear*` / movement checks). Cells
  containing trapdoors/top-slabs never had the flag, so flag-proven paths are byte-identical.

## §5 Edit folds (planner)

- Reuse `SET_OPEN/SET_CLOSED` + `EditScratch` doors[] channel + `DOOR_TOGGLE_COST` (6.0) + the
  latest-wins fold. The per-door two-half dedup in `EditScratch.setDoor` is door-gated; trapdoors are
  single-cell. Caps gate: `BotCaps.mayToggleDoors` (`doors.toggle`, sig bit 3) governs trapdoors too
  (owner-ratified single family key).
- **Face-aware entry**: `requireAirToward` grows the trapdoor case: cell blocked + is toggleable
  trapdoor + crossing its blocked face → fold SET(opposite), then evaluate the TOGGLED descriptor for
  the remaining within-candidate geometry (closing a panel may create a plate that changes which node
  is reachable — see below). Cell blocked but crossed face ≠ blocked face + §4 admits → FREE pass.
- **TOGGLE-FOR-CLEARANCE (the second fold trigger — required, not optional)**: the SET fold is
  offered on BOTH triggers, not just face crossings: (a) crossing the blocked face; (b) a toggleable
  CLOSED trapdoor sitting in a body cell and failing the §4 residual rules (a bisecting plate is a
  CLEARANCE failure, not a face crossing — its blocked face is UP/DOWN while travel is horizontal, so
  the face rule alone never fires). Trigger (b) folds SET_OPEN and re-evaluates the cell with the
  toggled descriptor, INCLUDING the face-vs-travel check on the resulting panel: if the opened
  panel would lie ACROSS the travel direction (trapdoor facing along the corridor axis), the
  candidate REFUSES — the closed-blocks-headroom / open-blocks-travel combination is genuinely
  impassable without breaking, and no toggle chain may pretend otherwise.
- **Multiple SETs per step**: a vertically-sandwiched pair (closed-TOP in the feet cell + closed-
  BOTTOM in the head cell of the same column — the owner's needle case) folds TWO SET_OPENs in one
  candidate at 2× DOOR_TOGGLE_COST, then admits via the stacked-panel rule (§4) when the facings put
  the panels on the walls. The `EditScratch` doors buffer (historically sized 2) must GROW on demand
  like the StepEdits arrays — a door exit-toggle plus a trapdoor pair in one step is 3 SETs and must
  not overflow or silently drop.
- **`requireFloorOrToggle`** (new): dest floor cell not standable but is a toggleable OPEN trapdoor →
  fold SET_CLOSED; the toggled state (closed-bottom t=3 / closed-top t=16 per its half) is standable
  by construction; the movement's rise/topY math MUST use the toggled topY. Used by the ground moves'
  dest-floor checks and by landings that stand on the cell.
- **Within-candidate toggled-descriptor threading**: folds return/expose the effective descriptor so
  later checks in the SAME candidate see the toggled geometry (PathEdits shadowing only covers
  cells of ANCESTOR steps). Concretely: closing an open panel with half=BOTTOM turns the feet cell
  into a plate → the correct emission is the dy=+1 step-assist candidate onto the plate cell
  (rise(1,3,16)=3 ≤ 9), and the flat dy=0 candidate must self-refuse on the post-toggle geometry;
  half=TOP → the Ascend jump-onto-hatch candidate (rise(1,16,16)=16 ∈ (9,20]). No new movement is
  needed for these — the folds land on existing arms.
- **Exit-face context**: `setCurrentDoorEdge` generalizes to an openable context: feet-cell door →
  edge (unchanged); feet-cell OPEN trapdoor → its panel face; exit crossings across that face fold
  the SET like `exitDoorDecision` does for doors.
- **Vertical family folds**: MineDown gains a toggle arm (floor cell is a toggleable closed trapdoor
  → fold SET_OPEN instead of a break; then the standard descend-into checks run against the open
  descriptor). Pillar's overhead cell (y+3) gains air-or-SET_OPEN for toggleable closed trapdoors
  (the opened panel is a wall-hugging vertical, clear for the 0.6 shaft). Fall's column scan passes
  open-trapdoor cells (body-passable); closed trapdoors remain landings (standable floors).
- The floorGap depth nibble reflects the REAL grid; SET-edited cells must not be trusted through the
  nibble fast path — follow the exact same edit-awareness pattern the break-a-floor path uses today.

## §6 Movement deltas

- **Traverse**: body checks via §4 rules; entry/exit face folds; `requireFloorOrToggle` on dest.
  Macro/cuboid: unchanged (uniformity is navtype equality; trapdoor cells fragment runs naturally).
  The exit-door gate already skips macro collapse when a toggle is owed — same for trapdoor exits.
- **Diagonal** (bug fix, owner-ratified): add the missing rise gate — refuse when
  `rise(0, destTopY, startTopY) > STEP_ASSIST_MAX_RISE` (today it emits physically unwalkable
  plate/carpet → full-block diagonals). Trapdoor cells in corner/body columns follow §4.
- **Ascend**: §4 body rules on the raised column; face folds; **new same-level jump arm**
  (owner-ratified): dy=0, `rise ∈ (STEP_ASSIST_MAX_RISE, JUMP_RISE]` (covers plate→full 13, carpet→
  full 15), MODE_STANDING, standard jump gates (`reducesJump`, `noJumpFromBody`, `solidFooting`,
  jump headroom above BOTH cells), dest standable + §4 body. This is what lets a bot step out of a
  flush-sunk hatch pocket (and fixes the documented carpet-lip pocket). Costed like the +1 jump.
- **Descend**: §4 rules on the destination column; face folds on the lower cells (already door-aware).
- **Fall**: column scan admits open-trapdoor cells; landings on closed trapdoors unchanged
  (standable). Depth stays whole-cell in v1 (§10 defers the 13/16 fractional correction — KNOWN
  optimistic edge on plate landings, up to ~0.8 HP under-priced).
- **Pillar**: overhead air-or-toggle (§5). Start gate (`floorSurface==16`) unchanged — pillaring off
  a 3/16 plate stays refused (physically correct).
- **MineDown**: toggle arm (§5) — "stand on hatch, open it, drop through".
- **WalkOff**: inherits §4/standability verdicts; no bespoke changes.
- **Climb**: open-trapdoor cells become body-passable → climbing through open hatches above ladder
  shafts plans naturally where the ladder continues; the vanilla ladder-extension rule is deferred (§10).
- **Parkour/DiagonalParkour**: takeoff rows already read real topY (plate takeoffs exact). Landing
  gate (standable ∧ ¬narrow) admits closed trapdoors; envelope keeps the full-block landing
  assumption (§10 defers landing-row awareness — conservative under-admission). Arc prisms stay
  conservative (open panels in the arc refuse — v1). "Close hatch then parkour from it" works via
  the ARRIVING move's `requireFloorOrToggle` + SET resolution (downstream nodes read the closed
  descriptor, so the takeoff row sees the plate automatically).
- **Swim family/Surface/RideBubbleColumn**: unchanged; waterlogged trapdoor cells remain
  conservative walls (§10).

## §7 Executor / follower

- **`WorldEdits.setTrapdoorOpen(level, pos, actor, open)`** — hand-rolled authoritative write (house
  stance: no interaction stack): guard `instanceof TrapDoorBlock && block != Blocks.IRON_TRAPDOOR`;
  no-op when already at target; `level.setBlock(pos, state.setValue(BlockStateProperties.OPEN, open), 3)`
  + sound + `GameEvent.BLOCK_OPEN/CLOSE` parity with vanilla where the API is range-stable —
  implementer must javap-pin the chosen sound/gameevent surface (candidates: `Level.levelEvent`
  wood ids 1007/1013, stable since 1.17.1; accept generic wood sound for copper in v1) and drop to a
  minimal setBlock-only overlay-free form if any piece drifts. Single block, no two-half sync.
- **BotSteering verbs**: `setTrapdoorOpen(x,y,z,open)` + reuse `doorOpenAt` semantics for trapdoors
  (both blocks share `BlockStateProperties.OPEN`; widen the read to any openable, or add a mirror
  verb — implementer's choice, keep the seam explicit). `AllyBotEntity` impl: `lookAtCell` + the
  WorldEdits call, exactly like `setDoorOpen` (no swing).
- **PhaseRunner**: the `requireDoor` pre-pass covers trapdoor cells — re-validated per tick against
  the live OPEN property; an externally re-flipped trapdoor is simply re-toggled next tick (the
  anti-trick property, no timers). `isDoorCell` generalizes to openable cells so `Need.AIR` never
  mines a cell governed by a SET req. `BotNavigator` injects trapdoor SETs into `requireDoor` reqs
  identically to door SETs; `applyEdits` replays them via the openable dispatch.
- **Own-edit forgiveness (fixes a known doors gap too)**: arm `expectOwnEdit`-style forgiveness for
  the bot's own door/trapdoor SET executions (extend the `NavGridUpdater.expectChange` slot to
  accept a same-block state toggle) and extend `PathPlan.prescribesEdit` to doorSets — today every
  own-toggle counts as a foreign change and triggers one wasted re-search.
- **Corridor test** (`movementBlockedAt`): already geometry-based — open panels parallel to travel
  pass, plates below the 9/16 corridor floor are not swung at. No block-type gates added (owner rule).

## §8 Maintenance, persistence, cleanup

- External toggles (player/redstone/wind) already flow: `LevelChunkMixin` → `BlockChangeEvents` →
  `NavGridUpdater.onBlockChanged` → navtype differs (open/closed descriptors differ) → patch +
  `editEpoch` bump → `planImpacted` → window re-search. Verified chain; nothing new needed beyond
  the descriptor split making states distinguishable (they already are, geometrically).
- *(2026-08-09 note: all persistence versions have since been PINNED at 1 pre-release — owner
  ruling; the bumps below survive as codec-Javadoc history entries, not live values.)*
  **`CostPyramidCodec.VERSION` 1 → 2** (classification semantics change: open trapdoors become
  passable, plate floors/toggle affordances change leaf costs — the v5-era precedent: stale shards
  must read as absent). **`INVAL_SIG_SCHEMA_VERSION` 2 → 3** (bit 3's meaning broadens to the
  door-family + new realizability from trapdoor toggling/jump arm — old negatives are
  over-pessimistic; drop the invalidation section, cost rows survive).
- **Remove the dead `anyDoor` section gate** (owner-queued cleanup, measured useless): the per-pop
  openable context becomes an unconditional feet-cell descriptor read. Do NOT add any anyTrapdoor/
  anyOpenable section bit (hard rule). A/B-benched with the rest.

## §8b Region-tier openable-as-air flood (RATIFIED 2026-08-09)

The region tier's leaf flood-fill treats **ALL openable cells — doors, trapdoors, fence gates,
INCLUDING the iron variants the bot cannot toggle — as PASSABLE** for connectivity. Owner rationale
(2026-08-09): an openable blocks at most 1 of 6 faces — "5/6 faces are passable — there's a chance
you can path through it just fine — and if you can't, we'll figure that out and invalidate." The
region tier is deliberately optimistic; unrealizable hops are absorbed by `RegionEdgeBlacklist` +
the capsSig-keyed persisted invalidations (`mayToggleDoors` is already sig bit 3).

Mechanism: ONE predicate, `NavBlock.floodPassable(d)` = `isPassable(d) || openable(d) != NONE`,
with connectivity-only consumers — the `FragmentLeafComputer` flood masks (`computeLeaf` + the
`fragmentContaining`/`labelFragments` wrapper scan) and `RegionGrid.goalDigSeeds`' exposed-goal /
pocket-touch tests, so a goal standing in a doorway/hatch cell resolves into the fragment it now
belongs to. `standable[]` and every other mask keep their exact predicates — a closed-bottom
trapdoor stays a FLOOR and fragment typing is undisturbed (one accepted optimism: a WATERLOGGED
openable now reads as a water cell in the type source, while the swim family still treats it as a
wall — §10). Plain fences are NOT openable and correctly stay walls; open fence gates were already
`SHAPE_EMPTY` passable, so the delta set is closed gates/doors/plates plus open door/trapdoor
panels. The block tier is untouched — movements keep exact geometry (§3–§6). No codec bump beyond
the already-staged v2 (its version-history note now names this change). Pinned by
`OpenableFloodConnectivityTest` (real-BlockState wall scenes with sealed fence/stone controls).

## §9 Tests & verification

Planner (clone the door-suite shapes; scenes from real BlockStates via `classifyInto`):
1. `TrapdoorClassificationTest` — bit pinning for all 6 shapes × wood/iron/copper × waterlogged;
   the `withTrapdoorOpen` bit-identity sweep over every trapdoor state; blocked-face table.
2. `TrapdoorClearanceTest` — the owner's cases: walk over floor plate in 2-tall hallway ✓; walk
   under ceiling plate ✓; 1.625 double-plate refused; head-bisections refused (both halves); slab
   floor under top slab admitted (generalized rule); full floor under top slab refused.
3. `TrapdoorToggleTest` — hallway open-to-progress and close-to-progress; flush-hatch pocket
   (enter by step-down, exit by the same-level jump arm); MineDown hatch-drop; Pillar up through a
   hatch; iron refusal (routes around); `doors.toggle=false` byte-identity; SET-resolution overlay
   (downstream nodes see toggled geometry).
3b. **`TrapdoorSandwichTest` (owner edge case, 2026-08-08)** — 1-wide 2-tall hallway; ONE column
   holds a closed-TOP trapdoor in the feet cell AND a closed-BOTTOM trapdoor in the head cell,
   facings OPPOSITE and perpendicular to the corridor (open panels land on opposite side walls):
   (a) both closed → path found, exactly TWO SET_OPENs folded on that column, cost 2× toggle;
   (b) both already open → free pass, zero edits (the 10/16 = 0.625 needle slot admits the 0.6 body);
   (c) adversarial: same setup but one trapdoor facing ALONG the corridor axis (its open panel blocks
   travel) → the column is REFUSED (no toggle chain works; route around or no path);
   (d) same-wall variant (both facings equal → both panels on one wall, 13/16 slot) → admitted;
   (e) three-SETs-in-one-step construction (door exit toggle + the sandwich pair) → no overflow,
   all three SETs carried (the EditScratch grow test).
4. `TrapdoorParkourTest` — parkour from a plate (takeoff row 3), onto a plate (landing gate),
   close-then-parkour (arriving fold + takeoff on closed descriptor).
5. `AscendSameLevelJumpTest` + `DiagonalRiseGateTest` — the two ratified behavior changes, incl.
   carpet cases and refusal-under-low-ceiling (jump clearance).
6. `PhaseRunnerTrapdoorTest` — FakeBot executor: SET req toggled not mined; external mid-run flip
   re-toggled; open-hatch corridor pass.
7. Regression: full existing suite green; door tests untouched.
Follower (live, headless): a `TrapdoorCourse` harness (sibling of SwimCourse; tiles for
hallway-open, hallway-close, flush-hatch crossing, hatch-drop, close-then-parkour, an
external-toggle trick tile flipping the trapdoor mid-crossing, and the SANDWICH needle tile —
open both stacked trapdoors then thread the 10/16 slot live, proving the centered drive holds the
±0.0125 window with panels as guide rails) — the end-to-end proof. `PhaseRunnerTrapdoorTest` also
pins two same-column SET reqs both re-validated and issued.
Perf: paired interleaved A/B on the full `PathfinderBenchmark` suite (SHORT/MULTI setup guards
included) + `PatchStormBenchmark`; a new trapdoor-heavy scenario (HATCHES) added for coverage
(no A/B baseline claim). forks=0 anomalies re-checked with pinned `-Pscenario` pairs.

## §10 DEFERRED (explicit)

- **Fall fractional-depth correction** (13/16 under-measure onto plates; owner deselected) — the one
  known non-conservative edge; document, revisit.
- **Landing-aware parkour envelope** (plate landings judged as flat; conservative; owner deselected).
- **TrapdoorCrawl / Crawl / crouch modes** — incl. the 1.625 hallway (vanilla auto-crouch would fit
  1.5 < 1.625) and close-on-own-head crawl entry. Node key has a spare mode value when the day comes.
- **Open-trapdoor-above-ladder climb rule** — needs ladder facing in the descriptor (ladders could
  share bits 8–9 later); executor already honors it via vanilla `onClimbable`.
- **Waterlogged trapdoor swim modeling** — cells stay conservative walls to the swim family.
- **Fence-gate toggling** — `OPEN_GATE` kind reserved; not in this arc.
- Jump-headroom residual refinement (jumps under plates stay whole-cell conservative).
- **Region-tier capability/cost awareness of openables** — flood CONNECTIVITY is handled (§8b:
  every openable is flood-passable), but fragments remain capability-blind (an iron door reads the
  same as an oak one — the capsSig invalidation memory is what learns the difference per bot) and
  leaf/edge costs price no toggle. Still deferred: capability-aware fragments and cost-model
  awareness of toggling.

## §11a Verification record (2026-08-08, arc complete in the mc-1.21 worktree, uncommitted)

- **Tests**: 846 tests / 0 failures / 0 errors (144 classes) on :1.21.11, incl. the full trapdoor
  suite (§9 items 1–7 + the §9-3b sandwich, all green) and the untouched door suite.
- **Cross-version**: `chiseledCompileCommon` BUILD SUCCESSFUL, every node 1.17.1 → 1.21.11.
- **Live follower proof**: `TrapdoorCourse` (new harness, `scripts/run-trapdoor.ps1`) — **7/7 tiles
  PASS** under full vanilla physics: hallway-open, hallway-close (stands the §5 plate node),
  flush-hatch pocket (same-level jump arm live), hatch-drop (MineDown toggle arm), close-then-
  parkour (3/16 takeoff row), the §9-3b sandwich needle (CC→OO, threads the 10/16 slot at full walk
  speed, z pinned at cell center), external-toggle trick (re-toggles ONE tick after the flip).
  Zero follower bugs; two behavior findings: a flat 3-gap Parkour legally clears an open panel lying
  below its arc, and Descend's toggle fold preempts MineDown's hatch-drop whenever an adjacent floor
  exists at hatch level (both correct per §5/§6).
- **Perf** (paired interleaved fresh-JVM pinned cycles, order-counterbalanced; forks=0 traps
  respected): the arc's first cut cost +5.6% SHORT / +4.9% CLIFFS / +2.6% MULTI — JFR-attributed to
  the same-level jump arm's per-pop dest reads (`Ascend.candidates` self 3.1%→9.0%) and fixed by the
  candidate-set-equivalent `SAME_LEVEL_MAX_START_TOP ≤ 6` takeoff short-circuit. Final record:
  SHORT −1.3%, MULTI ~0%, BRIDGE +0.5%, swims/SETUP/SHORELINE/FLOOD ~0, PatchStorm ≤1%, region tier
  ~0, **residuals on vertical-heavy scenarios: CLIFFS +2.0%, SPIRAL ~+2.8%, UPOVER_OPEN ~+3.5%,
  UPOVER_WALL ~+2.7%** (settled-cycle numbers; hypothesis, not yet profile-pinned: the +1 arm's
  trapdoor-dest checks + the per-pop openable exit context). Owner decision pending: accept as the
  capability's price or order a targeted optimization pass. New standing guard: the HATCHES scenario
  (~90 µs/op, 14 SET_OPENs sanity-pinned).
- **Bench-protocol lesson recorded**: single unpaired full-suite runs after long sessions are
  direction-mixed garbage (swims "improved" 15%); settling machines drift monotonically and a fixed
  B-first pair order systematically penalizes B — counterbalance the order and prefer the settled
  tail.

## §11 Owner rulings log (2026-08-08)

1. Descriptor: SHARE facing/half/open bits (verified slab-half is a non-issue — slabs use shape
   classes, bit 10 is stairs-only); NO new toggleable bit — `openable` (14–15) is the ratified
   kind enum (NONE/DOOR/TRAPDOOR/GATE covers the complete vanilla toggleable family), bit 50 =
   generic hand-toggleable.
2. Clearance: **generalized residual** (trapdoors + uniform top-band shapes: SLAB_TOP=8; top-half
   stairs excluded as unsound).
3. Config/caps: trapdoor toggling rides `doors.toggle` / `mayToggleDoors` (sig bit 3); schema bumps
   as §8.
4. v1 scope: same-level jump arm IN; Diagonal rise gate IN; Fall fractional depth OUT; landing-aware
   envelope OUT.
5. (second round) The vertically-sandwiched opposite-hinge pair is a required test case
   (§9 3b); it ratified the TOGGLE-FOR-CLEARANCE fold trigger (§5), the stacked-panel width
   soundness note + Diagonal carve-out (§4), and the grow-on-demand doors buffer (§5).
6. (2026-08-09) **Region-tier openable-as-air** (§8b): ALL openables — doors, trapdoors, fence
   gates, iron included — are flood-PASSABLE for leaf connectivity and region goal resolution.
   "5/6 faces are passable — there's a chance you can path through it just fine — and if you
   can't, we'll figure that out and invalidate." Optimism absorbed by `RegionEdgeBlacklist` +
   the capsSig-keyed invalidations; the block tier keeps exact geometry.
