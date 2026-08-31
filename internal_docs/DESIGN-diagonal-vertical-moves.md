# DESIGN — DiagonalAscend + DiagonalDescend (the dry 3-axis moves) + vertex-gate fidelity

**Status: IMPLEMENTED + SHIPPED 2026-08-29** (owner acceptance O5 below). Companion to
`DESIGN-region-corner-crossing-v2.md` §2.1, which named these moves as the fast-follows that make the
3-axis vertex chain real. Line facts verified 2026-08-29 and will drift.

**Verification record:** unit 1175/0 → **1189/0**; parkour **112/112** — `diagstair` (7 forced
DiagonalAscends), `diagstair2x`, `diagdescend`, and the 3-axis vertex pin `regioncornerXYZ` all PASS,
both prior region pins hold; full nine-family sweep at baseline; 3-reviewer adversarial pass, no
blockers/majors. **Perf (owner-accepted, O5 — 3 paired counterbalanced A/B rounds + pinned
fresh-JVM confirmations):** ground scenarios ~+4–8%/node (the price of two always-on `candidates()`
per standing pop; a DiagonalDescend probe reorder cut CLIFFS +14%→~+6% and zeroed SETUP), SPIRAL
+10–16% (live diagonal-vertical geometry — legitimate branching), swim family noise
(pinned: SWIM1X1 +0.6%, SWIMPOOL −2.6% — the forks=0 cross-scenario JIT-layout phantom), region tier
≤±2% except ZERO_CAP +4.4% (no-place only). In the perf model's currency: the ~2 µs/node upper band
becomes ~2.1–2.2 µs.

| # | Ruling |
|---|---|
| O5 | **Perf cost ACCEPTED, ship** (owner, 2026-08-29): "the perf cost is acceptable — plus I'm about to begin an arc to improve perf drastically." The remaining cost is genuine candidate evaluation (1-resolve rejects per direction already in place; the section-prefilter-bit lever is off the table per the standing no-anyX ruling) |

## Owner rulings (verbatim intent, 2026-08-29)

| # | Ruling |
|---|---|
| O1 | The vertex gate's conservative edge arm was a wrong deviation — the design intended 3-axis moves as imminent, so vertex connectivity gets full §4.1 precondition-3 fidelity in this arc |
| O2 | **No folded edits** on either move: grid-aligned edits are per-axis by necessity, and per-axis editing is `Ascend`+`Traverse`'s job. Geometry is a precondition; a changed world is the envelope's business (the `Diagonal` doctrine) |
| O3 | Emission windows mirror `Ascend`'s ownership carve-outs: `DiagonalAscend` emits only for `STEP_ASSIST_MAX_RISE < rise ≤ JUMP_RISE` (double emission is only waste, not a correctness threat — avoid it anyway) |
| O4 | `DiagonalDescend` mirrors **`Descend`'s** servo shape (controlled step-down, projected-stop braking via `arriveOnTarget`), NOT `WalkOff` — WalkOff is the honey-gap edge case with weird history |

## Orchestrator decisions within those rulings (D1–D8)

| # | Decision | Grounding |
|---|---|---|
| D1 | **Cost = `Traverse.FLAT_COST · √2` ≈ 6.552 for BOTH moves** ("a diagonal walk step whose jump/drop overlaps the motion"). The octile's 3-axis term is `FLAT·√3` ≈ 8.02, so h mildly over-estimates these steps — the SAME relationship cardinal `Ascend` (4.633) already has to its 2-axis octile term (6.55), and deliberate per the heuristic's own doctrine (`BlockPathfinder` H-constants comment: "verticality carries no special penalty… base cost = a walk step"; the search is weighted/greedy by design). State this relationship in the new moves' javadoc |
| D2 | **DiagonalAscend corner-column sweep = `y+1..y+3`, strict passable** — mirrors `Ascend`'s own `HEADROOM_JUMP` bar and its truncated-apex sufficiency argument (a y+4 ceiling caps feet at +1.2, still ≥ +1.0; the apex head pokes 0.05 into y+4, which cardinal Ascend already accepts over its own columns; the rise-20/16-under-tight-ceiling marginal case is the same one Ascend ships today). `DiagonalParkour`'s `y+1..y+4` is the BALLISTIC-gap contrast (full apex mid-gap at sprint speed) and deliberately not copied. Corner cells priced at the full per-cell rate, Diagonal-style |
| D3 | **DiagonalDescend sweep = flat `Diagonal`'s corner rule at the start level (`y+1, y+2`)** + `Descend`'s dest-column gates; `commitsAcrossArrival` stays false (Descend precedent) |
| D4 | **The `dy=+1, rise ≤ 9` diagonal remains UNOWNED** (e.g. full block → slab-one-up diagonally, rise 8): cardinal `Ascend` defers that band to Traverse's step-assist, but no diagonal move emits it — a pre-existing hole (never routable before either), documented, not closed in this arc |
| D5 | **No same-level jump arm, no trapdoor arm, no build arm** on the new moves (v1) — those are the edit-folding families O2 excludes; the plate-takeoff same-level nicety can follow if a card ever wants it |
| D6 | **Registry: append both at the END of `TIER1`** (order = cost-tie priority; appending is the registry's own stated safe form). 19 → 21 movements |
| D7 | **Vertex edge-gate fidelity = the two-leg test** (the corrected geometry: A→E is itself a 2-axis corner, not a face hop): reject the vertex iff for some edge region E, ∃ fragment `fe` with (i) `fe` qualifying as the D-side of the corner-2 A→E (`cornerFragQualifiesD` with the matching axis pair — corner-touch evidence, no footprint overlap, exactly as corner-2 tests it) AND (ii) `fe` offering a gate-passing face route into D (mirror `faceRouteExists`' arms with E as source: unbuilt-D ⇒ routable, uniform-D arms incl. the `f != 2` air-gate exemption and `canBreak` fallbacks, MIXED-D via `touchesFace(opp)` + type gate + `footprintsOverlap(fe@dFace, fd@opp)` against ANY `fd`). Uniform/unbuilt E: no record ⇒ no corner-2 into E ⇒ E contributes NO rejection — this deliberately drops the old gate's uniform-WATER auto-reject (a water E with no face access is locally unreachable, so it cannot substitute for the vertex; §2.1 note 3's swimmer argument covers the reachable-water case via the face arm). Two-leg is chosen over one-leg because the owner's complaint was OVER-rejection; it remains an under-approximation of full routability (E→D via a further intermediate is uncovered), which is acceptable for an economy gate and stated here |
| D8 | **Course fixtures that DiagonalAscend would bypass get substrate fixes, not looser assertions** (`dont-weaken-model-for-outdated-test`): `ascvine.pin`/`ascvine.face` (REACH's 3-wide platform side cells become diagonal entries around the vine curtain) and `diagvine.top` (Climb-lower + DiagonalAscend may replace the pinned Diagonal-off-climbable) — close the bypass in the geometry so each card keeps pinning what it pins |

## Mechanism summary (implementation contract)

**DiagonalAscend** (`(±1, +1, ±1)`, STANDING↔STANDING, appended to TIER1):
- Hoisted takeoff gates = Ascend's verbatim set: `MODE_STANDING`, `jumpHeight() ≥ 1`, `reducesJump` (honey), `noJumpFromBody` (cobweb), `solidFooting` (R1 — no jump from climbable stance), plus source `HEADROOM_JUMP` as a REFUSAL (`!srcClear ⇒ continue` — O2 forbids the `requireAir` fold).
- Per diagonal `(dx,dz)`: dest floor at `y+1` must be ALREADY standable (no toggle/build arms — D5); window `rise(1, directionalTopY(dst, −dx, −dz), sTop) ∈ (STEP_ASSIST_MAX_RISE, JUMP_RISE]` (O3; `sTop` directional on a stair start, Ascend-style); dest body clear at the raised level (headroom-prove or passable y+2/y+3); both corner columns `(nx,z)` and `(x,nz)` passable `y+1..y+3` (D2), descriptors read once and re-used for pricing.
- Cost = `(isSlow(dst) ? COST·SLOW_COST_FACTOR : COST) + floorHazardCost(dst) + bodyTransitCost(flags,…)` + `cellTransitCost` over all six corner cells — the non-folding Diagonal form throughout.
- plan(): Ascend's BUILD-less CLIMB shape — `arrestCarryFrom(fx,fz)`, held jump gated `footY < landFootY` (the vine-elevator discriminator), `launched[]`-armed `resetWhen`, grounded-gated `reached`; failWhen = Ascend's height bands × Diagonal's 2×2 column admit, with the face-press allowance (`landFootY−1`) on the non-start columns; DiagonalParkour's cross-axis ALIGN precedent where the takeoff needs centering.

**DiagonalDescend** (`(±1, −1, ±1)`): Descend's candidates mirrored diagonally + the D3 sweep; plan() = Descend's phase shape (full-drive CLEAR, `arriveOnTarget` projected-stop STEP, settled-not-grounded done) with the 2×2 admit and Descend's lip-transit band; no needs (O2).

**Vertex gate**: replace `edgeRegionLooksRoutable` with the D7 two-leg test inside `vertexPreconditions` (single definition site — all three evaluation sites inherit); per-precondition stats unchanged.

**Cards** (all `Template.OFFSET` — REACH fills corner cell A and voids diagonal forcing, the DIAG_TOP-documented trap):
- `diagstair.walkin` — base (−64, 32), rd=(1,1), jd=(1,7,1), stairRunway, stairBase=0: NO region boundary anywhere; 7 forced DiagonalAscends.
- `diagstair2x.walkin` — base (−39, 32), stairBase=3, jd=(1,10,1): X+Y corner-2 on the final step only (ordinary decomposition class).
- `regioncornerXYZ.walkin` — base (−39, −23), stairBase=3, jd=(1,10,1): takeoff (−33,159,−17) rx −3/ry 13/rz −2 → landing (−32,160,−16) rx −2/ry 14/rz −1 — all three axes cross on the one step, none elsewhere; the vertex-chain pin.
- `diagdescend.walkin` — descendRunway with rd=(1,1) + a flat diagonal jd=(1,0,1): one forced DiagonalDescend chained into a diagonal jump (the minimal Descend-mirror exercise; a full stairDown branch is future work).

## Known residuals
- D4's unowned rise-≤9 diagonal band; the descending staircase buildTile branch (deferred); `hotdiag1`/`hotoffset3`'s "must chain Descend→Parkour" forcing note re-verified post-landing; SUBSYSTEMS/MOVEMENT-DESIGN rosters were already stale at "18" (real: 19 → now 21); CLAUDE.md's "18" is owner-maintained — flagged, not edited.
- **DiagonalDescend's corner cell at START-FLOOR level is unswept and unpriced** (review 2026-08-29): on an off-center crossing the descending feet can transit `(nx,y,z)`/`(x,y,nz)` — verified benign for clearance (a solid there is landable support inside the envelope's corner band; `arrestCarryFrom` containment makes corner-only free-fall unreachable), but a passable hazard there is transited unpriced. One-cell search-preference error, accepted v1, documented at the sweep.
- The slowstep incident (registration-order base shift) exposed a PRE-EXISTING positional degeneracy — confirmed corner-free from the failing run's log (zero corner ids, all face hops, and a FAIL-exhausted block search that strictly-additive candidates cannot un-fail): a no-place bot whose takeoff cell sits on a region's first column (x ≡ 0 mod 16) near a V-approach window FAIL-exhausts and cycles V-approach hop identities through the blacklist (the key is wider than (region,frag), so one physical crossing consumes several repair generations) before an honest give-up. Registered in the open-bugs memory, not chased in this arc.

## Amendment 2026-08-31 — the arc rule applies to DiagonalAscend (owner-ratified)

The v1 design ported cardinal Ascend's TAKEOFF gates (R1 `solidFooting` et al.) but not the
2026-07-31 **arc rule** (`MovementContext.arcPassable`: a jump arc must not fly through a climbable)
to the swept cells. Convicted on the 2026-08-30 flagship at (207,118,297): a vine in the landing
feet cell arrests the arc at touchdown, the drive's held jump becomes vanilla's climb, and the bot
rides the curtain top in a permanent never-grounded hover (envelope gated on grounded/water/lava —
silent). Deterministic card: `run-vinebridge.ps1 -Variant vineup` (9 cuts; the race needs cruise
carry cross-axis against the step, an intermediate-waypoint landing, and a bare crossed corner).
Fix: corner cells c1–c6 → `arcPassable` (mask widening, zero new reads); landing FEET cell always
arc-checked when built (the headroom bit is passability-aligned and climb-blind); landing HEAD keeps
the flags fast path (Parkour's landing-body idiom — `onClimbable` is a feet-block test); UNBUILT
stays optimistic. Vined diagonals compose from the vetted vocabulary instead (Traverse-in/Climb-up/
Traverse-out, cardinal Ascend's discriminator) — the vineup card's own cuts 4/6/8 show the planner
finding those routes whenever the cells offer them. DiagonalDescend is NOT arc-gated: it is a
walk-off, not a jump, and inherits Descend's deliberate settled-on-climbable handling. Known gap
left open (logged): an isolated curtain reachable ONLY by jumping into it has no route post-rule —
would need a diagonal/lateral jump-grab Climb edge, built only if terrain demands it.
