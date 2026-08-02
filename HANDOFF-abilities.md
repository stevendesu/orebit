# HANDOFF — the climbable arc (landed) → dripstone next

Written 2026-08-01. Supersedes the previous handoff, whose diagnosis (a "curtain-top equilibrium
height" in the stance servo) was **wrong** — see §2.

**Goal:** the bot completes the autotest flagship `(60,180,253) → (201,-28,90)` end-to-end.
**Status:** not yet, but the failure moved ~150 blocks deeper. See §4 for the honest numbers.

---

## 1. What landed in this commit

All owner-ruled, all unit-green. Two independent stacks were committed together because the servo
retires the other's open `latvine` item.

**The climbable arc (this session):**

| change | what |
|---|---|
| `SteerControl.stationKeep` | `PhaseRunner`'s needs-hold re-centres on the bot's OWN column, not the step target. It was driving full-forward into the block it was mining. |
| `SteerControl.holdClimbableStance` | extracted from `drive`, now also used by the hold and by `Climb.steer`'s lateral regime |
| `Climb` exit-top | dropped BOTH the `standable` and `!isNarrowTop` gates — vines and ladders top out |
| `MovementContext.solidFooting` | `parkourLandable && !climbableFeet`. A scaffold DECK is now a legal jump takeoff. |
| `Climb` §3.3 jump-grab | same predicate; now a sibling `if` so a deck gets grab AND sink-in |
| `Traverse` | no step-assist from a climbable stance (`noAutoStepFromStance`) |
| `BotSteering.settled()` | `\|\| climbableBelow()` — the top-out is held, not ballistic |

**The owner's lateral-cling work** (predates the session): `Climb` three-regime steer,
`AllyBotEntity.sneakHeld()`, the `ParkourCourse` `latvine` card, `ClimbSteerTest`.

---

## 2. The physics — owner-verified by manual in-game testing. Ground truth.

Do not re-derive from code or docs; `DESIGN-climb-vocabulary.md` §2's `Vine/air/vine upward → REFUSED`
row is **wrong**.

- The climb precondition is on the cell the feet **ARE IN**, never on the cell they ENTER. While
  `onClimbable`, jump drives `vy=+0.2` every tick the feet remain inside, so the bot rises out of the
  column's top cell into whatever passable cell is above it.
- You can climb to **JUST ABOVE** the top and traverse onto a lateral block. Staggered columns chain.
- **Standing on top of a climbable is a real stance**, held by **sneak** (feet inside) or **jump**
  (feet above). Jump does not cancel the sink — it out-runs it by re-climbing at the surface.
- Sneak also holds the top-out, but its ledge edge-guard forbids stepping OFF a lip. Use jump there.
- Sneak does not block a simultaneous climb, and climb speed is identical either way.
- **You cannot jump unless BOTH:** there is solid ground below you, AND no climbable in your FEET
  cell (which truncates the 0.42 impulse to the 0.2 climb). *Standing on a scaffold deck satisfies
  both* — the deck is solid and the climbable is below the feet, not in them. That is a legal jump.
- **`NARROW_TOP` is a TAKEOFF and precision-LANDING restriction, never a standing one.** Vanilla WOULD
  let you jump off a ladder's 3/16 plate; we refuse it only because planning it opens the alternating
  ladder/air/ladder ascent, which needs the ladders to swap sides for headroom — and FACING is not
  packed in the descriptor.
- **Step assist needs ground contact.** Never from a curtain. From a ladder top it works only toward
  the mounted face; the other way walks off the ledge — unknowable without FACING, so refused.
- **Hanging vines (cave vines)** anchor to the block BOTTOM — no "above the top".

---

## 3. NEXT: dripstone — convicted, fix already chosen

The flagship's final wedge is at **`(148,30,7)`**, and it is the pathology the owner watched manually.

Master world: `(148,29,7) = pointed_dripstone` on `dripstone_block`. The bot rests at
**`botY=29.688`** = `29 + 0.6875`, the exact 11/16 tip height. The `Ascend` targets feet cell 30
(it believes standing on `(148,29,7)` puts the feet at 30.0), so `reached` — which needs
`footY == 30` — can never fire. A clean 3-tick limit cycle: jump, peak, fall back onto the tip.

**The ruled fix (owner, 2026-08-01): `STANDABLE &= !narrowTop` at descriptor classification.**
Not teaching `Ascend` about `topY` — just stop treating narrow tops as floors at all. Rationale: the
servo, momentum conservation and ballistics all assume full-width blocks, and we already refuse to
walk/run/jump/land on narrow tops, so the honest model is "not a floor."

- One conjunct at `NavBlock` line ~481, beside the existing `topY <= 16` net that already excludes
  fences/walls (topY 24) — extending a technique that is already there.
- **Does NOT affect passability.** `isPassable` is `shape == SHAPE_EMPTY`, independent of `STANDABLE`.
  These cells already have collision, so they were already walls for transit. Only "can I stand on
  top" changes. `BREAKABLE`/`COLLISION` derive from `solid` and are untouched — still mineable, still
  buildable-against.
- **Known cost:** `STANDABLE` feeds the depth nibble (`floorGap` = distance-to-first-standable-below,
  consumed by `Fall`), so a bamboo/dripstone column stops terminating that scan and a `Fall` over one
  predicts a longer drop than it takes. Conservative (over-estimates damage), but real, and it is why
  `isNarrowTop`'s javadoc currently says "still standable — a real floor for the grid's depth sweep."
- Cautionary precedent in the same comment block: excluding *damaging* floors from `STANDABLE` made
  magma "caps-blind walls" and had to be reverted. Doesn't apply here (already walls), but be careful.
- It also makes several just-added gates redundant: `parkourLandable` collapses to `standable`,
  `solidFooting`'s `!narrowTop` term, and `noAutoStepFromStance`'s ladder half.

---

## 4. The measurement — read this before claiming anything

| | committed `c84c4b9` | stance servo only | + top-out, no exec fix | **this commit** |
|---|---|---|---|---|
| `bestDistXZ` | **58.43** | 173.57 | 185.14 | 66.17 |
| `distY` at end | — | 161 | 168 | **58.71** |
| `distanceTraveled` | — | 205.74 | 123.44 | **1014.84** |
| `routeEfficiency` | 0.95 | 0.95 | 0.57 | 4.71 |
| end state | stops | stops | livelock | still moving, wp 25/49 |
| `capHit` / `partial` | — | 0/0 | 0/0 | **50 / 47** |

**`bestDistXZ` is WORSE than the committed baseline.** The run is much deeper (150 blocks) and still
travelling at budget exhaustion, but do not report this as a pass. The committed run's `distY` was
never captured, so there is no honest 3D comparison — **re-run `c84c4b9` and record its full end
state** if you need a real baseline.

The 50 cap hits are against the SYNC 10k node cap (`pathing.syncSearchBudgetNodes`). Owner recalls
earlier runs at **40k**, which would explain both the partials and the different wedge site — so the
next run should set that key before drawing conclusions about route efficiency.

---

## 5. Open, designed but not built

**The thin-panel directional bit** (owner-ratified direction). Ladders, open trapdoors and doors share
a shape: *large enough to stand on, but pressed against the edge of its cell so a body walks past.*
Today the planner calls a ladder non-passable and folds a BREAK to walk past it in a 1-wide hallway
(`EditScratch.requireAirToward` → `foldBreakOrFail`); its `entryEdge` logic is **door-specific**. The
executor disagrees — `movementBlockedAt` is a real direction-aware VoxelShape test — so the bot walks
past a ladder while its plan paid for a break, and a `canBreak=false` bot refuses the corridor.

Design: a directional bit mirroring `DOOR_FACING` (bits 8–9) so all three cases share the
"blocked edge = this one, pass in the other three" logic. For ladders the blocked edge always holds
its support block, so it is never a direction the bot could have travelled anyway. **Bits 20–23 and
52–63 are free.** Do NOT do this by flipping `SHAPE` to `EMPTY` — `BREAKABLE` and `COLLISION` both
derive from `solid`, so a "passable" ladder becomes unmineable and unbuildable-against.

**Keep `arrestIn` excluding solid climbables regardless.** Owner: catching a ladder's *side* mid-fall
is normal play, but landing on its *top* is real fall damage — so passability and fall-arrest must
stay separate questions.

**`Ascend` into its own landing column.** At `(148,·,7)` the bot had already walked into the landing
column at the lower level, so the floor it wanted to land on was the cell its feet occupied. The
envelope does not catch it (the bot IS on the destination column, just one level low). Same family as
the post-replan `Ascend` hold that opened this session. May be subsumed by the dripstone fix here, but
the class is real.

---

## 6. Verification protocol

The mistake that cost this session most: claiming "7 cards fixed" off a **single** run. It did not
reproduce under a paired A/B and had to be retracted. Always baseline first, always A/B.

**GOTCHA — quote the coords AND invoke with `&`.** `$Start` is `[string]`, so PowerShell coerces a
bare `60,180,253` to the array `"60 180 253"` and the mod dies at init with
`orebit.autotest.start must be 'x,y,z'`. A nested `powershell script.ps1 -Start "60,180,253"` does
NOT survive either — the quotes are stripped before the child parses. Call it in-session:

```powershell
$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot"   # JDK 21, mc-1.21 era
cd C:\Users\steve\Repos\personal\orebit-mc121-wt

.\gradlew.bat :1.21.11:test                     # expect 673 / 0
powershell scripts\run-parkour.ps1              # → run\parkour\orebit-parkour-result.properties
& .\scripts\run-autotest.ps1 `
  -MasterWorld '..\orebit-autotest-world\scripts\autotest-world-master\world' `
  -Start '60,180,253' -Goal '201,-28,90' -BudgetTicks 24000 -BotDebug
```

Grep the log for `step FAILED`, `advance SKIPPED`, `region re-derive`. **A livelock produces NONE of
those** — both wedges this session were steps that never *completed* rather than failed, so also
check whether the tail of the log is one `exec` line repeating on a short period.

`internal_docs/trace_analysis.py` reads `-Trace` dumps. For raw geometry, read the frozen master's
region files directly — that is how both the vine and dripstone geometries were pinned, and it beats
inferring from envelope lines.

**Do not flip `fail→hold` to auto-replan** to make the flagship pass. It manufactures a green run by
hiding exactly the pathologies this arc exists to fix.
