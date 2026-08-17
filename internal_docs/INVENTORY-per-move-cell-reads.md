# Per-move cell-read inventory (block A* expansion path)

**Status:** DATA GATHERING, 2026-08-11. This is a *map*, not a measurement. Every count below is a
static read of the source (file:line traceable); nothing here has been profiled. Any perf claim derived
from it is a hypothesis that must go through the paired-interleaved-A/B protocol before it is believed.

**Scope:** the `candidates()` path only — what runs per popped A* node. `plan()` / `steer()` / `failWhen`
run once per *executed* step and are excluded (verified: no movement performs a grid read in `plan()`;
the follower-side reads go through `BotSteering`, not `MovementContext`).

---

## 1. What counts as a read

A **read** = one resolution of a grid slot through `NavGridView`:
`built()` · `descriptorAt()` · `packedAt()` · `flagsAt()` · `floorGapAt()` · `runUpAt()`.

Cost of one warm read: single-slot chunk-key compare → section array index → array index. Behind that
slot sits a 512-entry open-addressed `long`→`NavSection[]` cache (murmur finalizer, no boxing).

Three things that look like reads and are **not**:
- `descriptorOf(x,y,z,packed)` and every `*(long d)` predicate — pure bit math on an already-loaded
  `long`. Free to call repeatedly.
- `headroomProves(flags,…)` / `editsDisjointFromColumn(…)` — flags int + `PathEdits` bbox compares.
- `placeCost()` / `breakCost(long)` / `clutchMask()` — scalar field loads.

**The `built()` + `descriptorAt()` anti-pattern costs TWO resolves of the same cell.** `packedAt()` +
`descriptorOf()` is the read-once seam that collapses them to one. Only some movements use it.

**PathEdits interaction:** `descriptorAt`/`descriptorOf` layer the edit diff (`size==0` test → 6-compare
bbox test → murmur probe). `flagsAt`/`packedAt`/`floorGapAt` do **not** — they are raw grid. This is why
edit-heavy scenarios cost 2,000–3,000 ns/node against 600–900: the same reads, dearer each.

---

## 2. The dispatch contract — why every overlap is a re-read

`BlockPathfinder.java:897-901`:
```java
for (int mi = 0, mn = tier1.size(); mi < mn; mi++) {
    relaxer.move = mi;
    tier1.get(mi).candidates(ctx, cx, cy, cz, relaxer);
}
```
`Movement.candidates(MovementContext, int x, int y, int z, CandidateSink)` — **bare ints, no descriptor
payload** (`Movement.java:32`).

The entire shared per-pop prologue is **two reads**: `ctx.setCurrentDoorEdge(cx,cy,cz)` resolves the feet
cell `(0,+1,0)` and head cell `(0,+2,0)` (`MovementContext.java:498, 512`). It shares a derived *verdict*
(`currentDoorEdge` / `currentHeadEdge`), never a descriptor.

**There is no mechanism by which two movements can share a cell read.** 18 movements are registered in
`MovementRegistry.TIER1`; each self-gates on `ctx.mode()`, which partitions them:

| Pop mode | Movements that can read | n |
|---|---|---|
| `MODE_STANDING` | Traverse, Diagonal, Ascend, Descend, Fall, Pillar, MineDown, Swim, StartSprintSwim, Climb, Parkour, DiagonalParkour, WalkOff, RideBubbleColumn | 14 |
| `MODE_PRONE` | SprintSwim, DiagonalSprintSwim, Surface, EndSprintSwim | 4 |

---

## 3. Per-move inventory

Offsets are `(dx, dy, dz)` **relative to the popped floor cell F = (x,y,z)**. Feet = `(0,+1,0)`,
head = `(0,+2,0)`. `d` = a cardinal/diagonal direction (4 iterations unless noted).
Plane: **G** = descriptor/navtype/packed · **F** = flags · **N** = depth nibble.

### 3.1 Traverse — 4 cardinals, ≤1 candidate/direction

| Cell | Plane | API | Why |
|---|---|---|---|
| (0,0,0) | G | `descriptorAt` :113 | start floor topY + stair-ness + climbable stance |
| (0,+1,0),(0,+2,0) | G | `exitDoorDecision` :138 | does an openable in my own body block this exit edge (0 reads unless the pop cell holds one) |
| (d,0,d) | G | `packedAt` :149 | dest floor: built + navtype + flags in one resolve |
| (d,+1,d),(d,+2,d) | G | `requireBodyClearToward` :193 | dest body fits (face-aware) — **0 reads when HEADROOM proves WALK** |
| (d,+1,d),(d,+2,d) | G | `bodyTransitCost` :196 | hazard/slow surcharge — 0 reads unless `CLEARABLE_HAZARD`/`SLOW_TRANSIT` set. **Re-reads the two cells :193 just read** |
| (d,0,d) | G | `requireFloorOrToggle` :213 | close an open hatch into a floor (2nd resolve of :149's cell) |
| (d,+1,d) | G | `packedAt` :232 | one-up floor for step-assist |
| (d,+2,d),(d,+3,d) | G | `requireBodyClearToward` :243 / `bodyTransitCost` :246 | raised body clear + priced |
| (d,+1,d) | G | `requireFloorOrToggle` :258 | close-and-stand +1 (2nd resolve of :232's cell) |
| (d,0,d) | G | `requireFloor` :285 | bridge: place a plank (+ ≤6 `supportsPlacement` probes) |
| macro: (k·d,0,k·d) k=1..J | F+G | `flagsAt` :358, `requireFloor` :363, `requireAirToward` :364/:365 | per-run-cell RISKY_EDIT + footing + body |

**Totals** — best (4 dirs flag-proven, no doors/hazards): **5 reads / 5 cells**. Worst micro: **≈75–80
calls / 19 cells**. Macro adds 4 calls per run cell, doubled when the run clamps (see §7).

### 3.2 Diagonal — 4 diagonals, ≤1 candidate each, **no edits**

| Cell | Plane | API | Why |
|---|---|---|---|
| (0,0,0) | G | `descriptorAt` :53 | start surface for the rise gate |
| (d,0,d) | G | `packedAt` :60 | dest floor built + navtype + flags |
| (d,+1,d),(d,+2,d) | G | `passable` :75 | dest body — **only when `headroomProves` :74 fails** |
| **(dx,+1,0)** | G | `descriptorAt` :84 | corner column A feet — no wall-clip |
| **(dx,+2,0)** | G | `descriptorAt` :86 | corner column A head |
| **(0,+1,dz)** | G | `descriptorAt` :88 | corner column B feet |
| **(0,+2,dz)** | G | `descriptorAt` :90 | corner column B head |
| (d,+1,d),(d,+2,d) | G | `bodyTransitCost` :95 | dest body surcharge — re-reads :75's cells when the prefilter fires |

**The corner reads are ungated by any flag bit and resolve only 8 distinct cells across the four
diagonals — each read exactly twice.** Typical open ground: **21 calls / 13 cells**; worst **37 / 21**.

### 3.3 Ascend — 4 cardinals, two arms (same-level jump, +1)

Prologue resolves **F five times**: `reducesJump` :87 (G), `noJumpFromBody` :88 (F, +2 G if
`SLOW_TRANSIT`), `solidFooting` :110 (G ×2 — floor + feet), `flagsAt` :115 (F), `descriptorAt` :123 (G).

| Cell | Plane | API | Why |
|---|---|---|---|
| (0,0,0) | G×3, F×2 | see above | honey floor · cobweb body · solid footing · HEADROOM_JUMP · topY/stair |
| (d,0,d) | G | `packedAt` :252 | same-level neighbour (gated `sTop ≤ 6`) |
| (0,+3,0) | G | `requireAir` :279/:218 | takeoff head clearance — gated `!srcClear` |
| (d,+3,d) | G | `requireAir` :282 | jump headroom over the dest column |
| (d,+1,d),(d,+2,d) | G | `requireBodyClearToward` :285 / `bodyTransitCost` :290 | landing body + price (2nd read) |
| (d,+1,d) | G | `packedAt` :160 | +1 dest floor: standable/topY/flags in one resolve |
| (d,+1,d) | G | `requireFloorOrToggle` :201 / `requireFootingOn` :211 | hatch-close, or build-a-step (+ ≤12 `placeable` probes) |
| (d,+2,d),(d,+3,d) | G | `requireBodyClearToward` :222 / `bodyTransitCost` :232 | landing body + price (2nd read) |

**Totals** — best: **10 calls / 6 cells**. Worst: **≈56–60 calls / 20 cells**.

### 3.4 Descend — 4 cardinals. No prologue reads at all.

| Cell | Plane | API | Why |
|---|---|---|---|
| (d,−1,d) | G | `packedAt` :64 | landing floor standable + flags |
| (d,−1,d) | G | `requireFloorOrToggle` :82 / `requireFloor` :84 | hatch-close or place a step-down floor (2nd resolve) |
| (d,+2,d) | G | `requireAir` :97 | head clearance stepping off — gated `!headroomProves(JUMP)` :95 |
| (d,+1,d) | G | `requireAirToward` :98 | transit feet / new head |
| (d,0,d) | G | `requireAirToward` :99 | new feet cell |
| (d,0,d),(d,+1,d) | G | `bodyTransitCost` :109 | landing body price — re-reads :98/:99's cells |

**Totals** — best **4 calls / 4 cells**; worst **28 calls / 16 cells**.

### 3.5 WalkOff — 4 cardinals, landing **2 cells out**, no edits

| Cell | Plane | API | Why |
|---|---|---|---|
| (d,0,d) | **N** | `floorGapAt` :120 | `fg==0` ⇒ standable below ⇒ this is a Descend, not a gap. **The only read on flat ground** |
| (2d,−1,2d) | G | `packedAt` :124 | landing floor + flags |
| (d,0,d) | G | `packedAt` :131 | gap at foot level passable & not standable |
| (d,−1,d) | G | `packedAt` :137 | gap one below |
| (d,+1,d),(d,+2,d) | G | `passable` :148 | gap column body sweep — gated `headroomProves` :146 |
| (2d,0,2d),(2d,+1,2d) | G | `passable` :153 | landing body — **NOT flag-gated, though `landFlags` is in hand from :124** |
| (0,0,0)(+feet) | G,F | `solidFooting`/`reducesJump`/`noJumpFromBody`/`flagsAt` :195-197 | jump-refused eligibility — ≤1× per pop, lazily |

**Totals** — best **4 calls / 4 cells** (4 nibbles — the design's zero-cost path). Worst **≈55 calls / 31
cells**.

### 3.6 MineDown — vertical, no direction loop

| Cell | Plane | API | Why |
|---|---|---|---|
| (0,−1,0) | G | `packedAt` :81 | destination floor exists + standable |
| (0,0,0) | F | `flagsAt` :87 | RISKY_EDIT on the floor being undermined |
| (0,0,0) | G | `requireAirVertical` :98 (micro) / `descriptorAt` :108 (macro) | break the floor (2nd/3rd resolve) |
| (0,−(k−1),0) k=2..J | F | `flagsAt` :124 | per-level RISKY_EDIT; clamps J |
| (0,−(k−1),0) k=1..J | G | `requireAirVertical` :126 | break the step-k floor |

**Totals** — micro **3 calls / 2 cells**; macro **2J+3 calls / J+1 cells**. Per extra level = 2 reads
(one F, one G) that a single `packedAt` would serve.
*Correctness note:* for J>1 only `(0,−1,0)` is verified standable; the true landing `(0,−J,0)` is never
read — soundness rests entirely on the cuboid uniformity certificate.

### 3.7 Pillar — vertical, no direction loop

| Cell | Plane | API | Why |
|---|---|---|---|
| (0,0,0) | G | `reducesJump` :95 | honey floor kills the apex |
| (0,0,0) | F(+2G) | `noJumpFromBody` :96 | cobweb body kills takeoff velocity |
| (0,+1,0) | G | `packedAt` :99 | feet cell open for placement + flags |
| (0,0,0) | G | `floorSurface` :115 | start surface must be full-height (3rd resolve) |
| (0,+1,0) | G | `requireFloor` :126 | place the footing (2nd resolve, + ≤6 `placeable` probes) |
| (0,+3,0) | G | `requireAirVertical` :132 | takeoff head clearance |
| (0,+k,0) k=2..J | F | `flagsAt` :163 | per-level RISKY_EDIT |
| (0,+k,0) k=1..J | G | `requireFloor` :164 | place the per-level support |
| (0,J+1,0),(0,J+2,0) | G | `requireAirVertical` :176/:177 | landing body |

**Totals** — best **3 calls / 2 cells**; micro full **6–8 calls / 3–4 cells**; macro **2J+6 calls /
J+3 cells**.

### 3.8 Parkour — 4 cardinals × gap columns × 3 tiers. The most expensive move.

Prologue resolves **F eight times**: `reducesJump` :462, `noJumpFromBody` :463, `solidFooting` :464,
`flagsAt` :472, `descriptorAt` :486, `bodyTransitLight` :488, `floorSurface` :491, and `floorSurface`
again inside `emitRising` :846.

| Cell | Plane | API | Why |
|---|---|---|---|
| (0,+3,0) | G | `packedAt` :474 | takeoff head clearance — gated `headroomProves(JUMP)` |
| (d·c,0,d·c) c=1..maxGap+1 | G | `packedAt` :561 | landing vs gap vs blocked — the forward walk |
| (d·c,+1,d·c) | G | `packedAt` :619 | flat-landing feet — gated `headroomProves(WALK)` |
| (d·c,+2,d·c) | G | `packedAt` :626 | flat-landing head |
| (d·c,+1,d·c) | G | `packedAt` :689 | floating-ledge rising detect — gated `headroomProves(CRAWL)` |
| (d·c,−dr,d·c) dr=1..capsDrop | G | `packedAt` :735 | **falling down-scan** — first standable below the gap column |
| (d·k,+1..+3,d·k) k=1..n | G | `packedAt` :800/:804/:808 | transit prism, verified lazily backwards on landing |
| (d·c,+2,d·c),(d·c,+3,d·c) | G | `packedAt` :853/:857 | rising landing body |
| (d·k,+4,d·k) k=0..c | G | `packedAt` :866 | raised-arc extra row (apex ≈ y+4.05) |
| offset tier (c,±1) | G | `packedAt` :916/:955/:960/:974/:981 | off-line landing + swept cover columns |

**Totals** — best (flat ground, column 1 standable non-triggering): **≈6 calls / 5 cells**. Typical
air-heavy: **≈84 calls/pop**, dominated by the falling down-scan (12/direction). Worst: **≈330–350 calls
/ 240–280 cells**.

### 3.9 DiagonalParkour — 4 diagonals. Corner-dominated.

Same 8-fold prologue shape as Parkour (:161-186).

| Cell | Plane | API | Why |
|---|---|---|---|
| (d·c,0,d·c) c=1..maxGap+1 | G | `packedAt` :216 | landing vs gap vs blocked |
| (d·c,+1,d·c),(d·c,+2,d·c) | G | `packedAt` :253/:254 | landing body — **both issued before either is tested** |
| (d·k,+1..+3,d·k) k=1..g | G | `packedAt` :317/:321/:325 | gap-column transit prism |
| corner A (d·k,0,d·(k−1)), corner B (d·(k−1),0,d·k) | G | `packedAt` :357 | swept corner floor arc-safe |
| each corner column +1..+3 | G | `packedAt` :365 | corner column body clear + priced |
| final-transition corner pair | G | `cornerPairCost` :271 | 8 reads, checked last as the most expensive gate |

**Totals** — best **4 calls**; worst **≈148 calls/pop, of which ≈128 (86%) are corner-column reads.**
The k=1 corner columns are shared between pairs of diagonals — **up to 16 cells read twice per pop.**

### 3.10 Fall — 4 cardinals + an optional self-column release-drop

| Cell | Plane | API | Why |
|---|---|---|---|
| (0,+1,0) | G | `packedAt` :291 | own feet hangable (a vine) ⇒ release-drop |
| (0,0,0) | G | `packedAt` :295 | cell below the hang passable & non-climbable ⇒ let go |
| (d,0,d) | **F** | `flagsAt` :311 | HEADROOM at the step-off lip |
| (d,+1,d),(d,+2,d) | G | `passable` :314 | seam-verify the WALK claim — only when `headroomProves` fails |
| (d,0,d) | **N** | `floorGapAt` :327 | **distance to the first standable below — the landing in one read** |
| (d,fy−y,d) | G | `packedAt` :357 | phase-1 column scan (only on the legacy/SAT paths) |
| (d,fy−y,d) | G | `packedAt` :387 | phase-2 extended soft-landing hunt |
| (d,fy+1−y,d) | G | `water` :433 | **water cushion** at the landing feet ⇒ m=0 from any height |
| (d,fy+1+w−y,d) | G | `water` :659 | walk the water column up to find the surface |
| (d,fy−y,d) | G | `descriptorAt` :439 | `fallSoftness` of the landing floor |
| (d,k−y,d) k=landY+1..y | G | `descriptorAt` :514 | drop-column transit: passable + climbable + priced (3 facts, one read) |
| 4 cells around landing feet | G | `placeable` :470 | BED clutch second-half footprint |

**The nibble decision table** (`floorGapAt`, the reason it exists):

| `fg` | Behaviour | Downward reads |
|---|---|---|
| `DEPTH_UNKNOWN (15)` or edits intersect the column | legacy fallback from `y−2` | **15/cardinal = 60/pop** at default `maxFall` 16 |
| `0` | standable at `y−1` — a Descend, not a Fall | 0 |
| `1..13` | exact landing `y−1−fg` | **0** |
| `DEPTH_SAT (14)` | proven none in `y−1..y−14`, resume at `y−15` | 2 |

**Totals** — flat plateau (`fg==0` ×4): **9 calls / 5 cells**. Legacy fallback ×4 deep cardinals:
**≈150 calls**. Worst realistic (phase-2 ledges ×4, `SOFT_SCAN_LIMIT = 384`): **≈3,100 calls** —
by far the largest single-move outlier in the search.

### 3.11 Climb — fixed structural window, **no column scan**

Vertical reach is `y+3` up, `y−1` down. Chained climbs are repeated ±1 edges, one cell per pop.

| Cell | Plane | API | Why |
|---|---|---|---|
| (0,+1,0) | G | `packedAt` :204 | feet cell climbable ⇒ on-climb stance |
| (0,0,0) | G | `packedAt` :213 | trapdoor-climbable: equal-facing ladder under an open mouth |
| (0,+2,0) | G | `packedAt` :223 | does the climb surface continue, or is it a top-out |
| (0,+3,0) | G | `packedAt` :232/:268/:312 | head clearance above the entered rung |
| (0,0,0) | G | `packedAt` :322 | climb-down: surface continues below? (2nd resolve) |
| (0,−1,0) | G | `packedAt` :328 | standable ground under a passable cell ⇒ base dismount |
| (0,0,0) | G | `standable`→`descriptorAt` :438 | genuine HANG test (3rd resolve) |
| (d,0,d) | G | `packedAt` :395 | top-entry: is the adjacent cell a trapdoor mouth |
| (d,−1,d) | G | `packedAt` :399 | the ladder the mouth must extend |
| (d,+1,d),(d,+2,d) | G | `packedAt` :409/:414 | crossing cells above the mouth |
| (d,+1,d) | G | `packedAt` :446 | **grab loop — unconditional every standing pop** |
| (d,0,d),(d,+2,d) | G | `packedAt` :480/:485 | dismount: ledge standable at this height + head |
| (d,0,d),(d,−1,d) | G | `packedAt` :527/:530 | grab guard: is there walkable footing (⇒ Traverse/Descend own it) |

**Totals** — typical grounded flat ground: **11 calls / 11 cells**. Worst grounded (mouths on all four
cardinals): **≈36 calls / 20 cells, 16 of them duplicates.**

### 3.12 Swim — STANDING, six rungs (4 lateral + rise + sink)

| Cell | Plane | API | Why |
|---|---|---|---|
| (0,+1,0) | G | `packedAt` :119 | am I wet — gates the verticals and dominance |
| (0,+2,0) | G | `packedAt` :127 | rise destination feet is fluid |
| (0,+3,0) | G | `packedAt` :195 | **rise head "not solid"** (passable OR fluid) — the upright pose fits |
| (0,0,0) | G | `packedAt` :136 | sink destination is fluid |
| (0,+2,0),(0,0,0) | G | `built`+`water` :234/:236 | dominance: 2-deep here ⇒ prone is possible, suppress the paddle |
| 4× (d,pf,d) | G | `built`+`water` :242 | can prone sprint-swim make lateral progress |
| 4 dirs × ≤5 layers (d,wf−y,d), wf ∈ [y+1…y−3] | G | `built` :169 + `descriptorAt` :170 | lateral entry column down-scan — **two resolves per layer** |
| (d,wf+1−y,d) | G | `built` :176 + `descriptorAt` :177 | **head "not solid"** — what lets a dry bot walk into a waterfall body |

**Totals** — dry land: **9 calls / 5 cells**. Worst: **64 calls / 28 cells**.

### 3.13 SprintSwim — PRONE, 4 lateral, no verticals

`built`+`water` on (0,+1,0) :112 and on 4× (d,+1,d) :120 — destination **feet water** is the entire
admissibility rule; the 0.6-tall pose means **no head or solidity test at all**.
**Fixed: 10 calls / 5 cells.**

### 3.14 DiagonalSprintSwim — PRONE, 20 destinations

`built`+`water` on (0,+1,0) :160, then on 20 offsets from `MOVES` :168 (4 same-Y diagonals, 8
vertical-diagonal edges, 8 corners), then a swept-subset check :181 (edges 2 cells, corners 6 cells),
all feet-layer, all pure water membership.
**Worst: 186 calls / 27 cells.** No solidity or standability test anywhere.

### 3.15 StartSprintSwim / EndSprintSwim / Surface / RideBubbleColumn

| Move | Cells | Why | Totals |
|---|---|---|---|
| **StartSprintSwim** (STANDING) | (0,+1,0) :32, (0,+2,0) :37, (0,0,0) :45 — each `built`+`water` | feet wet ⇒ can go prone; 2-deep ⇒ prone in place; deep below a tread ⇒ dive | **6 calls / 3 cells** |
| **EndSprintSwim** (PRONE) | (0,+1,0) :50 `built`+`water`; (0,+2,0) :55 `built` + :56 `descriptorAt` | pose-fit: head **not solid** (`water \|\| passable`) | **4 calls / 2 cells** |
| **Surface** (PRONE) | 4× (d,0,d) `built`+`descriptorAt` :53/:54; 4× (d,+1,d),(d,+2,d) `passable` :56 | bank floor **standable** + hazard; dest body clear (ends upright and dry) | **16 calls / 12 cells** |
| **RideBubbleColumn** (STANDING) | 4× (d,+1,d) `built`+`bubbleUp` :128; column scan `bubbleUp` :132; termination + ≤4 exits :137-165 | lateral entry into an UP column; ride to the top; exit to bank or float out | **8 calls / 4 cells on every standing pop**; +H+51 per column |

`RideBubbleColumn` costs **8 reads on every STANDING pop** for a feature almost never present. Its class
doc (`:65`) claims it "adds no cost … beyond one `bubbleUp` probe" — that is not what the code does.
Its column scan `while (ctx.bubbleUp(cx, topY+1, cz)) topY++;` has **no numeric cap and no `built` guard**
(contrast Swim's explicit `MAX_SINK = 4`); termination relies on unbuilt/air reading `false`.

---

## 4. The contended cell set

Let F be the popped floor cell. The cells fought over by multiple movements in one pop:

```
   (0,+1,0) (0,+2,0) (0,+3,0)        own body column
  (±1,+1,0) (±1,+2,0)                cardinal neighbour body columns
  (0,+1,±1) (0,+2,±1)
  (±1, 0,0) (0,0,±1)                 cardinal neighbour floors
  (±1,-1,0) (0,-1,±1)                cardinal neighbour sub-floors
```

Verified multi-reader cells:

| Cell group | Readers in one STANDING pop |
|---|---|
| **F itself (0,0,0)** | Traverse :113 · Diagonal :53 · Ascend ×3 (:87,:110,:123) · Pillar ×3 (:95,:96,:115) · Parkour ×4 (:462,:464,:486,:491) · DiagonalParkour ×4 · MineDown ×2 (:87 F, :108 G) · Climb ×3 (:213,:322,:438) · Fall :295 · Swim :136 · StartSprintSwim :45 |
| **feet (0,+1,0)** | prologue `setCurrentDoorEdge` :498 · Ascend `solidFooting` :110 · Fall :291 · Climb :204 · Swim :119 · StartSprintSwim :32 · (+ `noJumpFromBody` fan-out in Ascend/Pillar/Parkour/DiagonalParkour/WalkOff) |
| **8 cardinal-neighbour body cells** | Traverse :193 · **Diagonal :84-90 (ungated)** · WalkOff :148 · Descend :97-98 · Ascend :285 · Climb :409/:446 · Parkour prisms |
| **4 cardinal-neighbour floors** | Traverse :149 · Diagonal :60 · Descend (−1) :64 · Fall :311/:327 · WalkOff :131 · Climb :395/:527 · Parkour :561 · Surface :53 |

**F is resolved on the order of 20+ times per STANDING pop**, across ~10 movements, each independently.

---

## 5. Read amplification

| Pop kind | Distinct cells | Read calls | Amplification |
|---|---|---|---|
| PRONE, worst (fluid family) | 27 | **216** | **8.0×** |
| STANDING dry land, fluid family only | 5 | 19 | 3.8× |
| DiagonalParkour worst | ~35/dir | ~148 | ~4× (corner sharing) |
| Diagonal typical | 13 | 21 | 1.6× |
| Traverse worst micro | 19 | ~78 | ~4× |

Two independent causes:
1. **`built()` + `descriptorAt()` instead of `packedAt()` + `descriptorOf()`** — a flat 2× on every cell.
   Of the fluid family only `Swim` uses the read-once seam; the other six do not. Converting them is a
   mechanical, behaviour-preserving change that halves 216 → 108.
2. **No per-pop cell memo across movements** — the dispatch contract (§2). A 27-entry memo would take
   the PRONE worst case to 27.

---

## 6. Flag-bit scorecard — reads removed per pop

| Bit | Reads removed today | Verdict |
|---|---|---|
| **HEADROOM (2-bit)** | The only bit that removes reads on the mainline walking path. `requireBodyClear*` returns with **0 reads** when it proves ≥WALK; Traverse/Ascend/Descend/Diagonal/WalkOff/Parkour all exploit it. | Earning its keep |
| **CLEARABLE_HAZARD** | Zero-read fast path for `bodyTransitCost` on ordinary cells — which is most cells. | Earning its keep |
| **SLOW_TRANSIT** | Same, plus it prefilters `noJumpFromBody`/`bodyTransitLight` down to one flag read. | Earning its keep |
| **RISKY_EDIT** | **Zero.** It is a policy gate ("may I edit here"), not a read-avoidance bit. | Does not pay rent in reads |
| **PLACEABLE_NEIGHBOR** | **Zero — nothing reads it.** Only consumer in `src/main` is `/bot probe`. | Inert |

### 6.1 The `PLACEABLE_NEIGHBOR` mismatch

`NavFlags.hasPlaceableNeighbor` tests `!isPassable(n) && fluid(n)==0`.
`MovementContext.supportsPlacement` tests `!isReplaceable(n)`.

They disagree in both directions (a torch is passable but not replaceable; a snow layer has collision
but is replaceable), so the bit **cannot be swapped in as-is** — that would be a behaviour change.

But the fan-out it was named after is live and unprefiltered at four sites, each of which **already holds
the relevant flags word in a register**:

| Site | Fan-out | Flags already in hand |
|---|---|---|
| `Traverse.requireFloor` :285 (bridge) | ≤6 | `flags = flagsOf(p)` :282 |
| `Ascend.requireFootingOn` :211 | ≤12 (two `placeable` calls) | `dstFlags` from `packedAt` :160 |
| `Descend.requireFloor` :84 | ≤6 | `flags` from `packedAt` :64 |
| `Pillar.requireFloor` :126/:164 | ≤6 | flags from `packedAt` :99 |

**Redefining the bit to `!isReplaceable` over the six neighbours makes it exact for this fan-out**, and
it needs no new bits.

**But it is no longer the only claimant.** The owner's `CLIMBABLE_IN_BODY` proposal (§6.2 caveat 2) wants
the same bit and, on this inventory's numbers, has the stronger case. **See §6.5 — do not treat this
redefinition as decided.**

### 6.2 HEADROOM is exactly the parkour prism — and the answer is being thrown away

`NavFlags` HEADROOM counts clear cells starting at `y+1`, with `HEADROOM_JUMP = 3` — i.e. **exactly
`y+1..y+4`, exactly the parkour transit prism (the apex head row `y+4` was added 2026-08-17 — the ballistic head-top reaches takeoff-feet+3.05, so per-column reads in this file are one higher than the older 3-row counts below).**

| Site | Reads today | With the bit | Note |
|---|---|---|---|
| `Parkour.verifyPrisms` :800-811 | 3/gap column (≤12/dir) | 0 | `p_c` for that column was **already read at :561 and discarded** |
| `DiagonalParkour.verifyArc` :317-328 | 3/gap column | 0 | flags free from :216 |
| `DiagonalParkour.cornerColumnCost` :365 | 4/corner column | 1 | **128 → 32 reads in the worst pop** |
| `Parkour.emitOffset` cover prisms :981 | 4/swept column | 1 | flags free from :974 |

Pricing does not block it: `cellTransitCost` is nonzero only for hazard/slow cells, which are exactly
`CLEARABLE_HAZARD`/`SLOW_TRANSIT`. **`headroom >= 3 && !clearableHazard && !slowTransit` proves both
"prism clear" and "surcharge = 0" with zero reads.**

**Two caveats — both now have owner rulings (2026-08-11):**

1. **The section-seam refusal must be FIXED, not worked around** (owner ruling — read headroom across
   the seam so the bit is accurate everywhere, the same move the lava `RISKY_EDIT` term made when
   `scatterLavaRisky` started writing through the real below/above grids instead of the upward-only
   scratch).

   **The producer side is already done for LIVE grids** — this is the key finding, and it makes the fix
   much smaller than it looks. `NavFlags.compute` accepts `OVERSCAN_ROWS = 3` rows above the section, and
   `fillOverscan` populates them from the section above's rows 0-2 whenever an `above` grid is supplied
   (`NavSectionBuilder.java:400-409`). The live chunk build uses the two-pass column form
   (`classifyNavtypes` per section, then `computeFlags` with the above grid in hand) and the live patch
   path supplies `above`/`below` too. So **in a running game the HEADROOM bits at a section top are
   already honest.** `classifyInto` is documented at `:153-159` as the **headless/test** entry — that is
   the one producer left air-optimistic above.

   What blocks the win is therefore the CONSUMER-side guard in `MovementContext.headroomProves`
   (`(y & 15) + need <= 15`), which cannot tell which producer built the grid and so refuses
   unconditionally. Its cost is exact and quantifiable: a WALK proof is refused for `y&15 ∈ {14,15}` =
   **12.5% of floor cells**, a JUMP proof for `y&15 ∈ {13,14,15}` = **18.75%** — every one of which falls
   back to real reads. Closing it means either making `classifyInto` seam-exact as well, or giving the
   consumer a way to know the grid had column context. **Do not simply delete the guard** — on a
   headless/test grid it is the only thing preventing a trusted-but-optimistic bit.

2. **The climb-blind relaxation is REFUSED.** *Owner, verbatim: "that's NOT an acceptable behavior
   change. Vines arrest horizontal momentum so a vine in the flight path would prevent the bot from
   making the jump."* So HEADROOM alone may never stand in for `arcPassable` on a transit prism.
   **Owner's counter-proposal: spend a flag bit on "has climbable in the body column"** — then
   `headroom >= 3 && !climbableInBody` proves `arcPassable` exactly, with no relaxation at all, and the
   §6.2 win is unlocked honestly. This fact is independently valuable because the climb subsystem is
   large (`Climb` is the single biggest per-pop reader after the parkour pair). See §6.5 — it competes
   for the same bit as §6.1.

### 6.3 HEADROOM is fluid-blind — useless for the swim family, exact for its banks

`walkClear` = passable **AND fluid-free** AND non-portal. In fluid it always reads NONE/CRAWL, so it is
not merely useless but **actively wrong** for every submerged test (`Swim` :179/:198, `EndSprintSwim`
:57) — it would reject exactly the dry→wet waterfall entry that Swim's six-directional design exists to
permit.

It is exactly right for the two **dry-bank** tests: `Surface` :56 (16 → 4 reads/pop) and
`RideBubbleColumn` :148-149.

A "body-clear *including* fluid" variant would serve the submerged path — that is a **new bit**, and
there are none free.

### 6.4 The depth nibble is under-used

`floorGapAt` is read by exactly two movements: `Fall` :327 and `WalkOff` :120. Two more want it:

- **`Parkour`'s falling down-scan** (:735) walks up to `capsDrop` cells asking "where is the first
  standable floor below this gap column" — *literally the question the E3 nibble memoizes*. That is up to
  **12 reads/direction, 48/pop**, and it is the dominant open-air cost. It would prefilter rather than
  replace (the scan also needs the landing descriptor for `isNarrowTop` and per-cell transit pricing).
- **`Climb`'s standability guard** (:527 + :530) asks `floorStandable || belowStandable` — that is
  `fg == 0 || fg == 1` on `floorGapAt(nx, y+1, nz)`: **two reads → one**.

### 6.5 ONE free bit, TWO good uses — the contention to resolve

Bit 4 is currently `PLACEABLE_NEIGHBOR`, inert (§6). There is exactly one such bit, and the inventory
turned up two distinct high-value claims on it. They are mutually exclusive at 16 bits.

| Candidate | Definition | Serves | Reads removed |
|---|---|---|---|
| **A. `SUPPORT_NEIGHBOR`** (redefine to `!isReplaceable` over the 6 neighbours) | makes the bit EXACT for the `placeable()` fan-out it was named after | `Traverse` bridge :285 · `Ascend` footing :211 · `Descend` floor :84 · `Pillar` floor :126/:164 — all four already hold the flags word | ≤6 per site (≤12 at `Ascend.requireFootingOn`), on the PLACE-bearing path only |
| **B. `CLIMBABLE_IN_BODY`** (owner-proposed) | any climbable in `y+1..y+3` | unlocks §6.2 honestly (`headroom>=3 && !climbableInBody` ⟺ `arcPassable` prism) — `Parkour.verifyPrisms` 3→0/column, `DiagonalParkour.cornerColumnCost` 4→1/column (**128→32 in the worst pop**), `emitOffset` covers 4→1; plus the climb subsystem broadly | large, on the JUMP-bearing path — which is every pop on open terrain |

**Assessment.** B removes more reads and removes them from a hotter path: parkour runs on every standing
pop over open terrain, whereas A's sites only fire when a place is actually being folded. B also
*unblocks a win that is otherwise refused outright* (the owner has ruled the relaxation unacceptable, so
without B the §6.2 saving does not exist at any price), while A is a pure efficiency gain whose absence
costs nothing but time. On that reading **B is the stronger claim on a single bit**.

But note what the contention itself argues: **two independently justified facts competing for one bit is
the clearest evidence in this inventory for the 32-bit widening.** At 22 bits both land, plus the
fluid-aware clearance field §6.3 needs. That is the honest way to pose the widening question — not
"is a wider cell faster" but "how many facts do we have that pay for themselves, and do they fit."

Both A and B are **behaviour-neutral to compute** (pure functions of the six/three neighbour descriptors,
computed at build/patch like every other flag) and both need the same seam treatment as §6.2 caveat 1.

---

## 7. Redundancy catalogue (fixable without any new bit)

1. **F resolved 3–8× within a single movement.** Parkour ×8, Ascend ×5, Pillar ×3, Climb ×3, MineDown ×3.
   `reducesJump` / `noJumpFromBody` / `solidFooting` / `flagsAt` / `descriptorAt` / `floorSurface` /
   `bodyTransitLight` each resolve independently. **One `packedAt` + `flagsOf`/`descriptorOf` serves all
   of them** — the seam already exists (`MovementContext.java:941-944`) and is simply not used here.
2. **Diagonal's corners:** 16 calls → 8 cells, ungated (`Diagonal.java:84-90`).
3. **`requireBodyClear*` then `bodyTransitCost` read the same two body cells back-to-back** whenever the
   hazard/slow prefilter fires, with no descriptor threaded between them — Traverse :193+:196, :217+:220,
   :243+:246, :262+:265, :286+:289; Diagonal :75+:95; Ascend :222+:232, :285+:290; Descend :98/:99+:109.
4. **Traverse's macro clamp re-fold** (:391-400) re-reads the entire `1..valid` run prefix verbatim.
5. **Traverse reads `(d,0,d)` up to 3× per direction** (:149 `packedAt`, :213 / :285 via `EditScratch`,
   which re-resolves internally — `EditScratch.java:319, 348`).
6. **`Climb` :530 is read eagerly** even when :527 already proved `floorStandable` — the `||` at :533 is
   evaluated after both reads. Pure ordering; free to fix.
7. **`DiagonalParkour` :253/:254** issue both `packedAt` calls before either is tested at :255, so the
   `y+2` read is paid even when `y+1` is UNBUILT.
8. **`RideBubbleColumn`** resolves `(cx,topY+1,cz)` **4×** (:132, :137 built, :137 descriptorAt, :138
   built) and each exit neighbour up to 3–4× across its two exit arms.
9. **Fluid family:** six of seven classes use `built()` + `descriptorAt()` instead of the `packedAt` seam
   — a flat 2× on every cell they touch.
10. **The fluid family asks the same defensive question three times.** On a PRONE pop `SprintSwim` :112,
    `DiagonalSprintSwim` :160 and `EndSprintSwim` :50 each pay `built` + `water` on the feet cell; all
    three source comments state the answer is true by construction.

---

## 8. Divergences found between doc and code — **Javadoc CORRECTED 2026-08-11**

Owner ruling: trust the code, make the docs match, so a later perf/correctness change is not designed
against a stale understanding. The code was NOT changed; only the claims about it.

1. **`Parkour` prism-blocked does not end the direction.** [Javadoc FIXED] Class Javadoc :55 stated "Once
   one demanded prism fails, the direction returns outright." In the flat branches a `PRISM_BLOCKED` only
   skips the emit (:610, :631) while `overfly` was already latched true (:604, :623), so :660 does not
   return. Only `tryFalling` returns (:748). The cursor `verified`/`verifiedTransit` is written back
   **only on the falling path** (:749-750) — the flat and rising call sites discard it — so every later
   landing re-walks the prism prefix from column 1 and fails on the same column.
   **Correctness is intact** — the re-verify fails identically, so no bad candidate escapes. It is
   wasted reads, O(landings × blocked-column-index) instead of O(1). The companion claim "each prism
   cell is read at most once per direction" was false for the same reason and was also corrected.
   *The CODE was left alone: persisting the cursor at the flat/rising sites (or returning on
   `PRISM_BLOCKED` there) is a real fix, but it is a behaviour-adjacent change for the A/B protocol,
   not a doc edit.*
2. **`RideBubbleColumn` class doc :65** [FIXED] claimed it adds no cost beyond one `bubbleUp` probe; it
   costs **8 grid reads on every STANDING pop** (4 cardinals × `built` + `bubbleUp`, :125-128).
   Separately, the code resolves `built(cx,topY+1,cz)` twice at :137/:138 — a plain CSE, left unfixed
   as it is a code change.
3. **`TraversalGrid` :18** [FIXED] carried a hardcoded "measured ~590 navtypes, ~1.7× headroom" that
   directly contradicted the "Never hardcode a navtype count" paragraph lower in the same file. Removed;
   the durable statement (10 bits addresses 1024) kept.
4. **`MineDown` macro** never reads the true landing cell `(0,−J,0)` for J>1 (§3.6). **This is NOT a doc
   divergence** — the class doc :52-53 correctly states that skipping intermediate cells is sound
   *because* the cuboid certifies cross-section uniformity over the run. Recorded as a standing question
   about whether the certificate covers the landing cell itself, not as a defect.

---

## 9. Inputs to the 32-bit widening question

**Correcting the premise.** `TraversalGrid` is `short[4096]` (8,192 B) **plus** a parallel `byte[4096]`
depth array (4,096 B) = 12,288 B/section. Widening the cell to `int` gives 16,384 + 4,096 = 20,480 B.
That is **+67% per section, not +100%** — the depth array does not widen.

**The real cost is not bytes.** It is cache-line density on the bulk-scan paths: `short[]` = 32 cells per
64-byte line, `int[]` = 16. The consumer is `CuboidExtractor.rectUniform`, which resolves a section's
backing array once via `sectionRawAt` and scans `(raw[idx] & NAVTYPE_MASK) != nav` in memory order
(`:333, :355-362`). Halving its line efficiency directly doubles the bytes touched by that scan.

**But the depth-nibble work moved much of extraction off the `short[]`.** Column mode (`:342-353`)
verifies a ≥3-row sub-box with **one** bottom-row navtype read plus `columnRunOk`, which reads one
**depth byte** per ≤14 cells; and `runSkipUp` (`:409-424`) replaces the per-layer slab loop with one
`runUpAt` byte read per column. That is the measured 75–80% extraction cut. **The share of extraction
still riding the `short[]` is therefore much smaller than when the anti-widening ruling was made** — the
historical argument is weaker than it was, though not void.

**What widening would buy, from this inventory.** 22 flag bits instead of 6. The facts that have actually
earned a bit here:
- **`CLIMBABLE_IN_BODY`** (§6.2 caveat 2, owner-proposed) — the only honest way to unlock the parkour
  prism/corner saving, since the climb-blind relaxation is refused outright
- **`SUPPORT_NEIGHBOR`** (`!isReplaceable` over the 6 neighbours, §6.1) — retires the `placeable()` fan-out
- a **fluid-aware body-clearance** field (§6.3) — would serve the submerged path, ~180 of the 216 PRONE reads
- a "standable within N below" field — **already exists**, as the depth nibble in the parallel `byte[]`
  that costs nothing to widen. Not an argument for widening; an argument for *using* it (§6.4).

**The sharpest argument for widening is §6.5:** the first two are independently justified, behaviour-neutral
to compute, and there is exactly ONE bit. A 16-bit cell forces a choice that costs whichever loses; a
32-bit cell takes all three. That framing — "how many facts pay for themselves, and do they fit" — is the
one to measure, rather than "is a wider cell faster" in the abstract.

**What the inventory says about sequencing.** Most of the fan-out measured here is recoverable *without*
new bits: §7's redundancy list (mechanical), §6.1's redefinition (one predicate), §6.2/§6.4's use of bits
and nibbles already computed. Those should be measured first — they change the baseline the widening
would be judged against, and several of them (the per-pop memo, the `packedAt` conversion) reduce read
count by more than a wider flag word plausibly could.

---

## 10. What this inventory does NOT tell us

- **No measurement.** These are static call-site counts. A read's *cost* varies by an order of magnitude
  between a warm chunk-cache hit and an edit-bearing probe inside the `PathEdits` bbox.
- **No frequency weighting.** Worst cases are quoted alongside best cases; which dominates depends on
  terrain. `SHORELINE` and `FLOOD` exist precisely because the distribution matters.
- **No claim that fewer reads = faster.** The historical record here is explicit: the Hilbert-curve
  indexing and the neighbour-prefetch stencil both reduced apparent work and regressed. Every item in
  §6/§7 is a hypothesis for the A/B protocol, not a conclusion.

### 10.1 The companion instrument — `ReadCensus`

The first two gaps above are closed by **`pathfinding/blockpathfinder/ReadCensus.java`**, which counts the
real thing on real terrain rather than bounding it statically. Arm it for the JVM and drive a scenario:

```
JAVA_TOOL_OPTIONS=-Dorebit.readcensus=true      # static final gate; off, the JIT erases every hook
/bot census reset                                # start a measurement window
...drive the scenario...
/bot census dump                                 # -> <run dir>/orebit-read-census.txt
```

The headless autotest dumps automatically at server stop (it has no chat surface), so
`scripts/run-autotest.ps1 -MasterWorld …` is a reproducible real-terrain census scenario. Note its config
template pins `pathing.async=false`, so that census is single-threaded and deterministic.

What it reports, mapped onto this document:

| Report section | Answers |
|---|---|
| Repeat tax | the real counterpart to §5's 3.52x — and the hard ceiling on what any read-once scheme recovers |
| Per-offset table | §4's contended set, measured; carries `calls/pop` **and** `popCover%` |
| Prefetch-envelope scoring | sizes the prism §7 proposes — fill/pop vs saved/pop for five candidate shapes |
| Path-edit layer | decomposes the 2–3x edit multiplier into empty / bbox-reject / probe-miss / probe-HIT |
| By accessor, By movement | attribution, against §3's per-move tables |

**Read the two together, not interchangeably.** The inventory says which cells a movement *can* read and
*why* — the semantics no counter recovers. The census says how often that actually happens. Neither
measures TIME: with the census armed every read pays bucket arithmetic, so ns/node from a census run is
meaningless. Timing stays with JMH under the paired-interleaved A/B protocol.

The decisive column is `popCover%`. An offset with high traffic **and** high coverage is a prefetch
candidate; high traffic with LOW coverage is a memo candidate instead, because an eager fill is wasted on
every pop that never asks. The reverted neighbour-prefetch stencil failed on exactly that distinction —
it paid a membership test on every read to serve a set of cells it had not proven were usually wanted.
