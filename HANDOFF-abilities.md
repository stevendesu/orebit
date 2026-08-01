# HANDOFF — bot-abilities arc (crafting/farming/fighting/building), 2026-07-29

## ⏭ NEXT SESSION (owner rulings, 2026-07-30 close-out — work in THIS order)

Everything below is committed + merged (core/mc-1.21/main), suites + 28+4 matrices green, NOTHING
pushed. SPIRAL +8.4% from the self-edit coherence gate (core `7e850fb`) is OWNER-ACCEPTED — do not
revert or "optimize" it without a new ruling; the escalation (exact per-column membership probe) is
only if the owner reopens it.

1. ✅ **DONE 2026-07-31 (core `367aa2f`) — Break policy (the reconcile hole).** `AllyBotEntity.mine()`
   (sole caller = the PhaseRunner `Need.AIR` reconcile) now applies `Config.mayBreak` for real — the
   lying pre-flight became the gate — with the ruled plan-authored-placement exemption
   (`BotNavigator.planPlacedAt`, prefix `0..waypointIndex` over the path's own StepEdits places; the
   isDoorCell shape). BotMining stays hands-exempt (64e132c); stale backstop/waiver docs swept. Known
   accepted residuals (javadoc'd): the prefix vouches skipped/pending place CELLS (closing it needs an
   executed-place record — new state, owner sign-off); the refusal log stays `Debug.VERBOSE` (the
   place-refusal idiom), so a production protected-hold is silent; prior-window own-placements are not
   vouched (planner routes around them — stale-grid only).
2. ✅ **DONE 2026-07-31 (core `367aa2f`) — Mid-motion plan gate + post-plan reconcile.** All four
   driver launch/adopt points (fresh replan, settled boundary, planless adoption, repairStep) gate on
   PLAN-ANCHOR stability = `grounded || inWater || inLava || onClimbable` (adversarial review: the
   narrow arrival pair slid a hanging bot down its ladder in a climb/slide livelock and made a
   lava-borne bot sink before planning; only BALLISTIC states defer). Async adoption = seam accept +
   ON-PLAN membership (`AsyncWindowSearch.onStartOrPlan`, ±1 Y in fluid; follower's reached-scan does
   the mid-plan entry) else RETRY-from-actual-floor; `refreshWindow` drops a parked P4 precompute (the
   parked-veto wedge). Slides untouched. Verified: suite green, 28× common compile, FARM PASS, flagship
   GOTO best-ever 251.8 blocks / zero wedges → ended at item 4 below at (73,107,154). **Evidence for
   the deferred Fall wrong-landing envelope:** that run built SEVEN `PLAN Fall … grounded=false` — all
   mid-plan Climb-chain step transitions (adoption is settled-gated now), all survived on this roll;
   run 1 of the session wedged terminally on the same class at (55,152,258) (target feet cell also
   read solid). The envelope (+ the walk-off phase's advanceWhen(!grounded) instant-skip at airborne
   build) is where the remaining Fall risk lives.
3. **Window-slide livelock (#5, planner).** Mechanism fully characterized (empty-plan-consumed × tail
   re-derive from identical inputs × ±2 tolerance satisfied THROUGH STONE); full repro log =
   `run/autotest/logs/2026-07-30-4.log.gz` (11,209 FOUND-0wp at (24,106,167)). Flagship reproduction is
   UNRELIABLE (vine-growth ambience reroutes every run) — build a deterministic repro world first: uniform
   stone slab, goal 40+ straight down, canMine (dig-frontier-following is that world's only dynamic),
   then capture `-Rtrace` at the livelock to answer WHY the L0 re-derive dead-ends in the bot's region
   (budget partial vs fragment frontier vs blacklists).
4. **Parkour landing misses — CASE 1 ✅ DONE 2026-07-31 (core `ecb357c`), CASE 2 open.** The forensic
   split the item into two distinct sub-classes:
   - **CASE 1 (the (73,107,154) phase-1/4 fail, owner-diagnosed in-game): FIXED, two bugs.** The
     landings were BAMBOO tops (force-solid classification hid the ~3px offset stalk) → new NARROW_TOP
     descriptor bit 51 (XZ-extent < 0.6 bot width, geometry-derived) gates Parkour/DiagonalParkour
     LANDING emits; STANDABLE untouched. And the bot never jumped: PhaseRunner's drive-then-advance
     gap loses the trigger tick on a hot entry (Descend-chained momentum grounds past TAKEOFF_EDGE) →
     runup now presses jump SAME-TICK on pure hot entries only (normal timing byte-identical; the
     Phase-4 uniform-earlier-takeoff rejection honored). Pins: descendRunway course cards hotoffset3
     (the wedge shape) + hotdiag1, both PASS; suite 53 = baseline 51 byte-identical + the 2 pins;
     JMH A/B all-noise, SETUP guards flat; navtypes 408→433.
   - **CASE 2 ✅ CLOSED 2026-07-31 (core `b25a23b`): VINE ARREST.** The arc flew through climbable
     cells (vanilla clamps to ±0.15 b/t while the feet block is climbable) — confirmed empirically:
     the arcPassable gate (passable && !climbable on all transit prisms/descent columns, both parkour
     moves) removes those Parkour candidates from the trace, the search takes the trivial walk, and
     the repro autotest PASSES (arrived, 59t). Same commit: the "walled off" false positive fixed —
     the seal probe's goal seed now resolves by CONTAINMENT (anchorFragment; the faceless-pocket
     region-center centroid mis-seed closed, + regression fixture + the sealed branch's first log
     line). NOTE for the owner's drifted world: a sealed verdict requires canBreak=false at all —
     check the old no-capa repro pin in that copy's orebit.properties. De-greed: built, measured,
     REVERTED (cannot satisfy the pinned jump-preference suites and the walk-over-jump case on the
     same numbers — the goal-tolerance box makes the first in-box pop terminal); owner: leave greedy.
   - **✅ DONE 2026-07-31 — the climb/vine VOCABULARY arc** (design card =
     `internal_docs/DESIGN-climb-vocabulary.md`, §-cited from code; 8-reader recon + decompiled-
     1.21.11 physics first). Shipped: Fall arrests in PASSABLE climbables only (vine family) as hang
     nodes at the run's bottom cell, damage-free, under the FLAT guaranteed-arrest bound (prior drop
     ≤ 7 — `HangBoundTest` re-derives it from the recurrence; run-length relaxations + deep-window
     sweeps DROPPED per owner ruling after the paired A/B measured TOWER +8-13% / FLOOD +14-18% on
     them; an `anyClimbable` grid prefilter was built then REVERTED on the owner's standing no-anyX
     rule — coverage was shrunk instead); the
     in-column release-drop from hangs (vine/air/vine descends any depth); Climb gains jump-grab
     (solidFooting only), scaffold exit-top (NARROW_TOP excludes ladder plates), sink-in from the
     plate/deck (the trap fix), and a scaffold lateral-grab refusal (sneak-exempt); seam reads
     onClimbable()/scaffoldingBelow(); Fall done = footMatch && (grounded || onClimbable). TWO
     handoff assumptions REFUTED by physics + owner rulings: no gap ASCENT of any kind (a hanging
     entity can never jump — 0.42 needs onGround; a same-side upper ladder makes the plate stance
     geometrically impossible and FACING isn't packed), and ladder cells are NEVER fall/drop landings
     (from-above entry is a 0.0125-block hang-vs-plate knife-edge; powered sink-in + climb-down own
     ladder descent). Scaffolding needed NO reclassification (bytecode-adjudicated: the empty-context
     query returns SHAPE_STABLE → SHAPE_FULL with CLIMB set — standable deck, interior via climb
     edges). BONUS BUG FIX the suite caught: `solidFooting` gained a third conjunct (feet cell
     non-climbable) — vanilla truncates a grounded jump with feet inside a climbable to the 0.2
     climb, so the sink-in hang stance AND the pre-existing walk-into-a-floor-vine stance were being
     offered impossible parkour launches. Suite fully green (12 new tests); JMH A/B + in-world course
     cards (west cells) + the flagship rerun are the verification tail. Scaffold-top fall landings
     price NORMAL damage (vanilla may forgive them via #fall_damage_resetting — empirical follow-up;
     overpricing is the safe error).
   - **✅ DONE 2026-07-31 — ASYNC STEP-TRANSITION SAFETY (the "mid-air adoption" pending ruling,
     resolved; design card = `internal_docs/DESIGN-async-step-safety.md`).** Deterministic owner-world
     repro (vine-topout Ascend → gap-4 Parkour undershoot → fail→HOLD on the forest floor) convicted
     the step-transition seam: Ascend's HELD-JUMP steer was preempted by the ungated reached the tick
     its feet touched the takeoff, and the Parkour plan was built AIRBORNE on the parasitic hop. Fixes
     (adversarially reviewed — 8 breakage scenarios in the card): (1) `Ascend.reached` grounded-gated
     (the committed-move idiom; NOT a global anchor gate — E1/E2 break it); (2) PENDING-SEARCH CAUTION:
     step transitions into committed moves / deeper-than-safeFall Falls defer (stand at the settled
     anchor, never on damaging floors) while ANY async search is outstanding — suspect origins
     (terrain-impacted evaluated independently of consumed now / repair / blocked-null, retries
     inheriting) recorded via `PathPlan.suspectSearchPending()` for the log + future stronger handling;
     (3) the adoption seam's outer box scales with the search budget (max(3, ceil(budgetSec×6)) — the
     walk-outruns-the-seam storm fix; membership still exact). Oracles: repro FAIL→PASS (123t, zero
     ABANDONED), control PASS unchanged, suite green. OPEN (deliberate): the general
     uncommitted-airborne-transition class (per-move physics work); the Descend vine-bounce servo fix
     (owner-sketched: zero horizontal input once XZ-aligned — no wall-press → no auto-climb) is the
     next follower increment.
   - **✅ RESOLVED (2026-07-31 late) — THE HELD-JUMP × VINE-TRANSIT ELEVATOR.** Log-convicted and
     fixed the same night (full record: DESIGN-climb-vocabulary.md §9). Repro'd deterministically by
     the new ParkourCourse `ascvine.*` cards with per-tick `j/c/h` trace columns (jump input held /
     onClimbable / horizontalCollision — `AllyBotEntity.jumpHeld()` diagnostic seam). CONVICTED
     MECHANISM (pre-fix `ascvine.face`): Ascend's climb-phase held jump + a climbable in the landing
     stance → vanilla's `jumping && onClimbable → +0.2/t` climbs the bot THROUGH its own target into
     a 4-tick hover limit cycle at the curtain top (one c=1 tick re-launches every falling re-entry —
     with jump held the bot can NEVER descend through a vine cell), never grounded → the settled-gated
     done/resetWhen/failWhen structurally cannot fire; 370 ticks dead-centred one block above the
     stance. `h=0` throughout — the held jump ALONE sustains the class; the hcol arm was innocent
     here. Two hypothesis corrections the cards forced: walk momentum defeats takeoff-column capture
     (WALKIN pin passes even pre-fix; the dangerous entry is MOMENTUM-LESS — the flagship's chained
     second Ascend, pinned by `ascvine.pin.rest`), and Pillar is structurally SAFE (its held jump is
     phase-bounded below the capture heights; place/land drives never press jump). THE FIX: Ascend's
     climb drive holds jump only while the climb still needs height —
     `setJumping(!(onClimbable() && footY() >= landFootY))` — release lets the −0.15/t clamp settle
     the bot onto the floor where done fires (capture→done in 5–7 ticks in the post-fix traces; water
     untouched — a water cell is never a climbable cell). All 4 climb cards PASS; unit suite green.
     🟡 OWNER RULING WANTED (the generic net, deliberately NOT implemented): widen the failWhen
     envelopes' settled-set to include onClimbable (a hang is a stable stance → "settled"), so any
     residual capture shape (e.g. transient hcol ratchets beside curtains on forward-driving moves)
     fails fast into a replan-from-the-hang instead of hovering invisible to every envelope.
     Cross-move blast radius — waits for the owner. AFTER: rerun the flagship with raised
     -BudgetTicks (this class was the prime suspect for the residual 0.51 b/s pace).
   - **🔴 OPEN (owner-witnessed 2026-07-31 23:36, notes-only — battery cut the session) — THE
     POST-REPLAN ASCEND HOLD.** Repro recipe (owner says headless-reproducible): a FRESH copy of
     "Autotest Master" with the world's `orebit/` subdirectory DELETED (cold HPA/persistence — the
     deletion may be load-bearing: the region layer rebuilds from live, so early planning runs many
     more / slower region+window searches), the owner's client config
     (`run/config/orebit.properties`: placeBaseCost=600, breakBaseCost=60, protectedBlocks empty),
     full flagship (60,180,253)→(201,-28,90). SYMPTOM: the bot walks to (46,147,215), is about to
     Parkour −Z, a replan reroutes it to an Ascend −X — solid ground, nothing in the way — and it
     HOLDS POSITION indefinitely, no motion at all. Screenshot run/screenshots/2026-07-31_23.36.41.png:
     bot standing ON canopy, RED intended trail runs NE, NO blue deviation trail → an ON-PLAN
     executor HOLD (inputs withheld), not an off-course wedge. CANDIDATE MECHANISMS (unverified —
     evidence first, in suspicion order): (1) the NEW pending-search caution gate (d1bb065,
     BotNavigator): `searchPending() && stepCommitsRisk(waypointIndex) && grounded → setForward(0)`
     — if the replan/window-slide left a search PENDING that never completes/clears (cold region
     layer grinding, P4 pre-plan parked, or a suspect-flag lifecycle hole), the hold is indefinite
     and looks exactly like this; FIRST PROBE: does Ascend read as stepCommitsRisk
     (commitsAcrossArrival?), and grep the -BotDebug log for the CAUTION vlog line + the async
     pool's submitted/finished counters. (2) an adopted plan whose PhaseRunner "build" phase waits
     on a Need it can't establish under this config. (3) the window-slide livelock (#5, FOUND-0wp ×
     tail re-derive) resurfacing under the cold-cache condition (the owner notes it "doesn't repro
     at the moment" otherwise). NEXT SESSION: reproduce headlessly per the undershoot-forensic
     pattern (owner-config overlay + master copy WITH `orebit/` deleted — keep the deletion, it is
     part of the recipe) with -BotDebug, convict from the log, THEN fix.
   - **🟡 IN FLIGHT (2026-07-31 midnight) — THE LATERAL-CLING ENTRY (run-autotest-climb regression,
     found by finally re-running the queued regression check; predates tonight — HEAD-without-tonight
     fails identically).** Two mechanisms LOG-CONVICTED via the new `latvine` course card (the
     autotest miniaturized: feet-level vine row on a 2-HIGH wall face across a floorless gap; note
     the card's first geometry LEAKED — a 1-high support wall + the wide REACH runway made a walkable
     bridge, fixed 2-high): (a) SNEAK EDGE-GUARD LATCH — the lateral Climb's held sneak while still
     GROUNDED at the lip forbids the walk-off the ledge→vine transfer IS (measured: frozen forever at
     box-edge 0.001 past the ledge, input pressing, spd 0.0000); (b) BOTTOM-EXIT FALL — an un-sneaked
     walk-off enters the one-cell vine row at its exact lower edge and drops out the bottom on the
     first airborne tick (feet-cell sampling; measured: fell). FIX IMPLEMENTED (Climb.steer lateral
     branch, three regimes off existing reads): hanging → sneak (suppress the slide); grounded on a
     ledge (feet not climbable) → JUMP-grab (the arc's falling re-entry crosses the vine cell with
     sneak already engaged → arrests in-cell — the vanilla player idiom); grounded ON the climbable
     (ladder plate) → plain walk. ClimbSteerTest updated (FakeBot.grounded configurable; entry
     jump-grab + plate-walk pins). STATE: UNCOMMITTED in BOTH worktrees (core-wt + mc121-wt copies);
     the verification chain (unit suite → climb cards → run-autotest-climb) was IN FLIGHT when the
     session ended — if its result isn't recorded below, RE-RUN IT before touching anything:
     suite green + latvine PASS + autotest PASS = commit on core + merge; any red = read the latvine
     trace's j/s/c/h columns first. Prior chain evidence: edge-guard fix alone → latvine FAIL(fell)
     (mechanism b). CHAIN RESULT (killed at owner request, outcome already legible): the autotest was
     going to FAIL again — but with dy=−0.12 at (8,1,0) vs the original dy=0.00: the JUMP-GRAB ENTRY
     WORKS (the bot is off the ledge, hanging in the first vine cell, sagging 0.12 below feet target)
     and now STALLS THERE with zero lateral progress — a THIRD layer: the HANGING-lateral drive
     itself (suspects: recenterOnTarget's COLUMN_DEADBAND zeroing forward against the NEXT-column
     target frame, sneak's 0.3 input factor × the ±0.15 climbable clamp interacting, or the reached
     scan never advancing wp5 so the drive recenters on the CURRENT column). The latvine card's
     j/s/c/h per-tick trace is the conviction vehicle — run `run-parkour.ps1 -ClimbOnly`, read the
     hanging-lateral window, THEN fix. latvine's verdict from the same chain: FAIL(fell) at maxProj 4.20 of 5.35 (was 1.48) — on the
     CARD the hanging lateral DOES creep (crossed 4 of 5 cells) and fell at the FAR edge (the exit /
     last-cell transfer), while the AUTOTEST stalls at the FIRST cell — the two scenarios now fail
     DIFFERENTLY, so diff their geometries (wall height / vine support arrangement) before assuming
     one mechanism. Root cause held: tiles beyond
     the boot view-distance bubble never fire a durable CHUNK_LOAD, so the nav pipeline never built
     them. Fix: `ChunkNavLoader.buildNow(level,cx,cz)` (public synchronous per-chunk build, skip-if-
     built, same riders as the tick drain) called from `ParkourCourse.enter` over each tile's padded
     chunk footprint, PLUS the BoxedInCourse-style `navReadyAround` residency gate in the settle
     branch (an unbuilt tile is now an explicit `HARNESS:` verdict, never a silent bot-FAIL or a
     vacuous refusal-PASS). VALIDATED: full suite 2026-07-31 ran 82/82 trials ALIVE — zero
     "nav gave up", zero HARNESS verdicts; the formerly-dead ~28-tile tail is green except the
     honestly-measured pre-existing shortfalls. Post-triage true suite state: 72 PASS / 9 real FAIL /
     1 intended planner-gap. The 9: `owner.honeyflyover` + `.runup` (newly-alive honestCross twins,
     genuinely short at maxProj 0.96/1.16 of 3.0 — the standing honey walk-off shortfall, now
     measured instead of masked) + the offset family (`offset2p/3p/2n` walkin=fell at ~0 takeoff
     speed, rest=timeout) + `diag2.walkin` (fell, takeoffSpd 0.013) — the known offset-probe /
     chained-momentum servo debt, climb-free tiles, untouched by this arc. A `-ClimbOnly` fast gate
     (script switch + `orebit.parkour.climbonly`) runs just the climb cards in ~1 min.

Arc design: `internal_docs/DESIGN-bot-abilities.md` (ratified order: Crafting → Farming →
Fighting → Building; component template + decision log §10). This file tracks arc state only —
the owner's region-tier arc handoff stays in `HANDOFF.md`.

## ✅ ADOPTION COMPLETE (2026-07-29, evening) — the wrinkle below is HISTORY

Everything is landed and verified on all three branches; the verify worktree/branch are gone.

- **Follower-envelope arc COMMITTED**: core `1c6f539` (topY-aware fromFootY/toFootY on plan();
  BotSteering.movementBlockedAt direction-keyed body-corridor obstruction) + `2d79ef1`
  (NavFlagsTest follows the fluid-scatter move — the suite's one standing failure is FIXED).
- **The interrupted session's owner-ruling increment COMMITTED**: core `64e132c` —
  mining.protectedBlocks is a PATHING policy (planner + route executors), deliberate hands
  exempt (D3 waiver machinery removed); gather-MINE protected-sight-line ESCALATES to an exact
  approach instead of dropping the vein; /bot farm is a PERSISTENT state (WATCH re-surveys).
- **mc-1.21 adopted everything**: FF onto `mc121-abilities-verify` (exact tested lineage) then
  `git merge core` (`2a6fefd`); era-owned follow-ups `c6074db` (counts-based FARM verdict),
  `119e0dc` (.gitignore carpet master), `4522433` (workRadius pin dropped). **main merged core**
  (`85a3ee6`). `core` also gained `56dfed5` (owner's future-work.txt notepad).
- **Verified post-adoption (mc-1.21 node, 1.21.11)**: unit suite 115 classes ZERO failures
  (first fully-green suite); FARM autotest PASS ×2 (pinned 126t; default-radius 106t —
  harvested 8, tilled 2); CRAFT scenario B PASS 118t (incl. the protected crafting-table
  reclaim under the NEW policy — the deliberate-hands exemption in action); FIGHT PASS 79t;
  BUILD PASS 1157t (9 cleared + 9/9 placed). 26-era chiseledCompile green ×4.
- **⚠ Flagship GOTO: three follower bugs FIXED (2026-07-30), one momentum pathology remains —
  owner ruling needed.** The route is a jungle-cliff gauntlet; each fix surfaced the next
  blocker. FIXED (each evidence-first, committed on core with pins): (1) `89a8537` the step-0
  steer-segment frame fed Climb's Δy a floor/feet mixed frame → a first-step vine climb-DOWN
  read as lateral → sneak edge-guard pinned the bot on a lip (progress 7.5→41 blocks);
  (2) `3d8b639` falling parkour onto non-ice kept an open-loop drive that cannot arrest a
  small-gap deep-drop arc → landed one cell past (course cards falld2g1/falld3g1 added; A/B 45→50
  PASS, falling family now lands dead-centre); (3) `9cc2eb3` DiagonalParkour's envelope
  refused the takeoff face-spill its gate can't actually prevent under axis-dominant approach
  momentum (the ratified lip-crossing admission, applied). (4) The chained-step MOMENTUM
  CORNER-SLIP — a −z Descend into a +x Descend step-off carries ~0.2 b/t of cross velocity and
  grounds the bot on the diagonally adjacent cell, a REAL off-plan settle the envelope rightly
  fail→HOLDs — is FIXED by the owner-ratified VELOCITY-ALIGNMENT GATE
  (`SteerControl.stepOffGate`, wired into Descend's STEP phase): the step-off may drive only
  when the friction-horizon prediction (|crossOffset + vCross/(1−slip·0.91)| ≤ 0.2, the support
  block's REAL slipperiness — ×2.2 stone, ×9+ ice) keeps the bot inside the one-wide lane;
  until then a pure cross servo arrests the carry. Verified: gate unit tests (the repro numbers
  + the ice horizon), suite green, FARM PASS 74t, ice-course A/B (icedescend PASS both sides;
  the icediag FAIL is byte-identical WITHOUT the gate — pre-existing diagonal-on-ice arrival,
  the P5/hug class), and the flagship run cleared the corner-slip zone with ZERO envelope
  failures, best-distance 203→166.
  **NEXT BLOCKER (#5, PLANNER side — the region/window seam, owner's call):** at (24,106,167)
  with window target (24,104,168) the block search returns FOUND-0wp with ZERO nodes expanded,
  dozens of times per second, forever — the start floor is within the ±2 goal tolerance of the
  window target, the empty plan is consumed as settled, and the WINDOW NEVER SLIDES forward.
  An empty-complete-plan × forward-slide livelock at a vertical (dy=2) tolerance edge.
  Also observed: ambient plan-impacted refreshes RESET a held runner, so fail→HOLD leaks into
  retry in live worlds (owner 2026-07-30: rare enough to deprioritise; the ambience is VINE
  GROWTH — vines random-tick spread; leaves only decay after their logs are removed).
- Observation for the owner: the autotest conjures COBBLESTONE bridges while the default
  protected list contains cobblestone — the planner/route executors can therefore never re-break
  the bot's own placed bridge blocks (pathing-only consequence; deliberate hands unaffected).

## DONE — Crafting (SHIPPED)

- core `89e856e` feat(crafting), merged → main `b03b1af`. NOT yet on `mc-1.21` (see below).
- `/bot craft <item> [count]`: BotCrafter (PLAN/SEEK_TABLE/PLACE_TABLE/CRAFT/RECLAIM),
  `crafting/` package (RecipeIndex/KnownRecipe/IngredientSlot/CraftAssignment),
  `platform/CraftingOps` ×10 flavors (every signature javap-pinned; boundaries 1.17 / 1.19.4 /
  1.20 / 1.20.2 / 1.20.5 / 1.21 / 1.21.2 / 1.21.4 [Ingredient#items List→Stream] / 1.21.11
  [ResourceLocation→Identifier] / 26 [assemble one-arg]), `ItemLookup.byId` (+1.21.11 flavor),
  `crafting.*` config (CONFIG.md + docs/configuration.md), `BotMining.requestReclaim` (the one
  narrow protectedBlocks waiver, §10-D3).
- Verified: chiseledCompileCommon green ×28 (mc-1.21 era) + chiseledCompile green ×4 (26 era);
  unit suite 592 (6 new; only the pre-existing NavFlagsTest failure); headless CRAFT autotest
  PASS ×2 in-world on 1.21.11 (2x2 in place 46t; craft-table→place→craft-pickaxe→reclaim 136t).

## BRANCH STATE (the temporary wrinkle)

The owner's follower-envelope arc sits UNCOMMITTED in `orebit-mc121-wt` (AllyBotEntity et al.
dirty) → `git merge core` into `mc-1.21` is blocked there. Until that lands:

- mc-1.21-era work is verified in the THROWAWAY worktree `../orebit-mc121-verify`, branch
  **`mc121-abilities-verify`** = mc-1.21 HEAD (3dca500) + `53fb13e` test(autotest) CRAFT mode
  (era-owned: HeadlessAutotest + runAutotest whitelist + run-autotest.ps1 -Craft/-Give) +
  merge of core. **When the follower arc lands on `mc-1.21`: merge/cherry-pick `53fb13e`
  into `mc-1.21`, then the normal `git merge core`.** Then delete the verify worktree.
- 26-era in-game verify of `/bot craft` (runClient on main) still pending — headless coverage
  is mc-1.21-only (26 era has no harness).

## DONE — Farming (SHIPPED)

- core `b9b24f4` feat(farming), merged → main `7baaafa`; verify branch `ae26a17` (FARM harness) +
  `23a7fb6` (core merge).
- `/bot farm`: BotFarmer (SURVEY/WORK/SWEEPUP), `farming/CropKinds` Strategy classes
  (wheat/carrots/potatoes/beetroots), `platform/ItemUse` (version-STABLE src seam —
  ItemStack#useOn, javap-pinned), `platform/FluidRead` (1.17 + 26 flavors — 26.x removed
  FluidState#is), `BotMining.requestHarvest` (mature-crop protected waiver), seed exclusion in
  bridging selection, `farming.*` config, BotInventory findSlotMatching/equipSlot.
- **NAVIGATOR FIX shipped with it** (owner-review-worthy): `driveToward`'s `newRegionGoal` also
  fires on (exact tolerance && plan COMPLETE && stored goal cell != live goal cell) — the
  moved-goal freeze diagnosed live (bot nudges a chased drop one cell → frozen forever); also
  latent in gather-COLLECT. Non-exact callers byte-identical. PathPlan gains `goalFloor()`.
- Verified: 28+4 compile matrices green; suite 597 (only the pre-existing NavFlags failure);
  all farm mechanics observed working in-world (8/8 harvest, 10 plants, 2 tills).

## ⚠ KNOWN-BLOCKED e2e (re-verify after the follower arc lands)

The COMMITTED mc-1.21 state's envelope increment uses a full-block footY test — any converted
Traverse STARTING FROM a partial floor (farmland 15/16) trips fail→HOLD. A/B-verified
pre-existing: the flagship GOTO autotest freezes at (55,177,256) wp0/47 on the UNMODIFIED
committed branch too. The owner's in-flight follower-envelope arc (topY-aware fromFootY/toFootY)
fixes it. After it lands on mc-1.21: cherry-pick `53fb13e`+`ae26a17`, merge core, then re-run:
- `powershell scripts/run-autotest.ps1 -MasterWorld ../orebit-autotest-world/scripts/autotest-world-master/world` (GOTO, expect PASS)
- `... -MasterWorld ../orebit-autotest-world/scripts/gather-issues-repro-master/world -Start 70,63,-68 -Farm -BudgetTicks 6000` (FARM, expect PASS; then re-widen the template's farming.workRadius=5 pin)
- the two CRAFT scenarios (already PASS on the committed base).

## DONE — Fighting (SHIPPED)

- core `171ae95` feat(combat), merged → main `f239c82`; verify branch `69eec46` (FIGHT harness) +
  `5334adb` (core merge).
- BotFighter = pre-dispatch consumed-tick interrupt (NOT a Mode; paused mode machines resume
  free). Strategies: CreeperStrategy (source-verified swell constants), SkeletonStrategy (family
  classed via the new `platform/MobKinds` seam — the skeleton/zombie CLASSES moved to
  monster.skeleton / monster.zombie subpackages at exactly 1.21.11), MeleeStrategy fallback.
  Cadence reads the REAL getAttackStrengthScale ticker. `combat.*` config;
  BotInventory.equipBestWeapon (id-string ranking, WeaponRankTest).
- Verified: 28+4 matrices green; suite 601 (only the pre-existing NavFlags failure); headless
  FIGHT autotest PASS on 1.21.11 (zombie targets bot → interrupt preempts STAY → 4 full-charge
  strikes → dead in 79t, bot 20hp — the zombie WALKS to the bot, so the scenario dodges the
  committed follower gap entirely).

## DONE — Building (SHIPPED) — THE ARC'S FOUR FEATURES ARE COMPLETE

- core `b3c8b29` feat(building), merged → main `70c0da9`; verify branch `7607841` (BUILD harness)
  + `c56ad87` (core merge).
- `building/NbtReader` (dependency-free NBT wire reader) + `building/Schematic` (.litematic,
  byte-faithful: negative-Size corners, LSB-first SPANNING bit reads, Version<5 rejected) +
  `building/PaletteResolver` (exact BlockState via BlockLookup + generic Property-by-name — NO
  version flavors needed) + `BotBuilder` (SCAN/WORK diff sweeps, bottom-up, self-healing props
  diff-ignored, partner cells deduped, exact-state place + timed clears, sweep-progress
  convergence, honest missing-materials report) + `/bot build` (files from
  `<server dir>/orebit-schematics/`) + `building.clearMismatches`.
- Verified: 28+4 matrices green; suite 604; BUILD autotest PASS (9 cleared + 9/9 placed, 1155t).
- FINAL integrated regression sweep on the verify branch head `c56ad87`: CRAFT scenario B PASS,
  FIGHT PASS, BUILD PASS (FARM + flagship GOTO remain known-blocked on the committed follower
  gap — see the ⚠ section; re-verify commands there).

## NEXT (future arcs, in the owner's order)

1. **Work trees** — the requirements/planner layer composing gather/craft/farm/build (the stub
   `requirements/` spec's hand-authored recipes govern THIS layer; `RecipeIndex` stays the
   execution truth). Components already expose the seams: `startX()` entries, observation
   getters, BotBuilder's missing-materials map = the shopping list.
2. Skeleton-strategy refinement (strafe/LOS-break needs a driver strafe primitive), shield/bow
   use, eating (mandatory before `survival.hunger` defaults on).
3. TileEntities/Entities in schematics; Sponge `.schem` fallback; farmland-landing avoidance in
   the planner (a jump onto farmland tramples — plan-cost work, owner-gated).
4. LLM integration (intent only, per the PRD).

Building plan (DESIGN-bot-abilities.md §6 + the building-formats recon): (1) `.litematic` parser
in core — gzip NBT (consider a dependency-free mini-NBT reader for full version independence);
per-region minCorner = per-axis `min(pos, pos + (size>=0 ? size-1 : size+1))`, dims=abs(size);
palette = list of {Name, Properties(strings)}; BlockStates long[] with
bits = max(2, ceil(log2(paletteSize))), LSB-first and SPANNING long boundaries (LitematicaBitArray
semantics — vanilla SimpleBitStorage PADS and mis-decodes); index = x + z*dimX + y*dimX*dimZ;
palette→BlockState behind a platform seam (BlockLookup.byId + property-by-name application).
(2) BotBuilder (Mode.BUILD): diff-vs-world reactive loop, bottom-up support-aware, SKIP
self-healing connection props (stair SHAPE, fence/pane/wall sides — updateShape converges),
dedupe multi-block roots (door lower, bed foot), direct exact-state placement
`setBlock(pos, state, 0x12)` + real-inventory consumption + reach gating, missing-materials
chat, unreachable-cell terminal state (no timers). Schematics in `<world>/orebit/schematics/`
(ConfigDir pattern); `/bot build <name> <x y z>`; `building.*` config. Tests: pure-JVM parser
round-trip with a fixture file + a superflat-plot autotest (movement-light so the follower gap
doesn't block: build a small flat 5×5 platform schematic within reach). NOTE walking-dependent
scenarios stay blocked until the follower arc lands.
