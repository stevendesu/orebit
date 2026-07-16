# DESIGN — Trapdoors (future arc, NOT implemented)

**Status:** DESIGN + open questions. Written after the doors arc (P0-P3 + state-agnostic model + Ascend/Descend). Trapdoors reuse the door *toggle/edit* machinery almost wholesale, but add a new blocked-**face** model, hit a different (vertical) movement set, introduce genuinely new **clearance** modeling that doors deliberately punted, and pull in a new **Crawl** movement. Realistic budget: ~2-3× the doors effort if Crawl is included. Plan as its own multi-session arc, not a doors footnote.

## Vanilla trapdoor facts (bytecode-verified, this session's research — stable 1.17→26.x)
Blockstate: `facing` (2-bit cardinal), `half` (top/bottom of the cell), `open`, `waterlogged`. Panel is 3/16 thick.
- **CLOSED** → horizontal slab: `half=bottom` blocks **DOWN** (occupies y 0-3), `half=top` blocks **UP** (y 13-16).
- **OPEN** → vertical panel on the edge **opposite `facing`**: facing N→blocks S, S→N, E→W, W→E.
- Right-click toggles wooden/bamboo/copper(≥1.21); iron needs redstone. `waterlogged` matters (a trapdoor can hold water).

## What reuses ~as-is from doors (cheap)
- **Absolute-SET edit kind** (`SET_OPEN`/`SET_CLOSED`) + latest-wins fold — already handles the *stacked-trapdoors-need-two-opens* case (same as the hallway double-toggle, just vertical). Toggle cost, `EditScratch.setDoor` dedup, `PathEdits`/`StepEdits` SET kinds all reuse.
- **State-agnostic "blocking → toggle to clear" principle** — a closed trapdoor blocks a vertical face; toggling swaps it to a horizontal face (perpendicular set), so "toggle always clears my crossed face" still holds.
- **Classification-at-static-init pattern** + the `openable` field already carries `TRAPDOOR=2`.
- **Descriptor bits: likely ~0 new.** A cell is exactly one of {stair, door, trapdoor, gate}, so trapdoor `facing`/`half`/`open`/`toggleable` can SHARE the existing facing (8-9), half (10, stair's), open (43), toggleable (50) fields — `openable`/`isStair` disambiguates the interpretation. Only the *derivation* is trapdoor-specific. (VERIFY the sharing holds; if `half` semantics collide with stair-half, may need 1 new bit.)

## What extends (medium)
- **Blocked-EDGE → blocked-FACE.** Doors block 1 of 4 horizontal cardinals (`doorBlockedEdge`, 2-bit). Trapdoors block 1 of **6 faces**: UP/DOWN when closed (per half), a cardinal when open. Needs a 6-way `trapBlockedFace` accessor + the crossing checks generalized from 4-edge to 6-face (the shared `exitDoorDecision`/`requireBodyClearToward`/`setCurrentDoorEdge` seam grows a vertical-face variant).
- **Different movement set — the "covers a hole" case hits the VERTICAL family:** `Fall`, `Climb`, `MineDown`, `Pillar`. Plus 2-tall-hallway cases hit the *horizontal* body column (`Traverse`/`Ascend`/`Descend` — a trapdoor in the feet or head cell). The shared crossing seam just factored for doors is the right foundation; it extends to these movements.
- **Executor.** `TrapDoorBlock` likely lacks the `DoorBlock.setOpen(entity,level,state,pos,open)` convenience that absorbed all door drift — a new `WorldEdits.setTrapdoorOpen` is a hand-rolled `state.setValue(OPEN,…)+setBlock(flag)+playSound+gameEvent`, **more version-fragile** (verify the OPEN property + sound/gameEvent drift across the range; possible overlay). Single-block (no two-half sync concern, unlike doors).

## What's genuinely NEW / the hard parts (large)
- **CLEARANCE modeling — the real cost, no door precedent.** Doors let us punt the 3px hinge as a follower quirk *because it never affected passability*. For trapdoors the 3px is **load-bearing**:
  - Two *open* stacked trapdoors = **6px** residual → may not fit a 2-tall body (impassable even both-open).
  - A *closed* trapdoor's slab cutting the feet box (top-half) or head box (bottom-half) **blocks horizontal walking**.
  So a trapdoor is not "passable except one face" — it's "passable **minus a residual-height panel**." This breaks the binary blocked-face model and needs an actual per-cell **residual-clearance computation** (how much walkable height a trapdoor leaves in a body cell, and whether the bot's pose fits). This is the main unknown; scope it carefully before committing to an approach.
- **Vertically-stacked trapdoors** forming a "door-like" 2-cell space that needs *both* opened — the multi-toggle fold handles the edits, but the geometry (two SET edits on two cells + the 6px clearance above) is new.

## The Crawl movement (its own sub-arc)
A new movement: **prone locomotion on LAND** (distinct from the swim-prone `SprintSwim` we have). Pulls in:
- A new movement class + follower (prone drive on land) + mode transitions (enter/exit crawl on land, vs. only-in-water today).
- **Trapdoor-crawl initiation** (deferred movement #6): stand next to a trapdoor and close it on your head to force prone — a SET edit that *creates* the prone state. More trapdoor shenanigans.
- **Water-exit-into-crawl** (Crawl consideration 1): you can enter a crawl directly from water into a 1×1 gap when the flow is stopped by a sign/pressure-plate (we don't path flowing water, so only the stopped-flow case is reachable). A swim↔crawl mode transition.
- A trapdoor as a normal door in a 1×1 passage is only reachable *via* Crawl (or the existing SprintSwim), so Crawl unlocks that case too.

## Suggested phasing
1. **Trapdoor v1** — vertical "covers a hole" (`Fall`/`Climb`/`MineDown`/`Pillar`) + single-trapdoor 2-tall hallway, on the reused edit machinery + blocked-face extension + the new executor. The common case, mostly reused code.
2. **Trapdoor v2** — the clearance edge cases (stacked-open 6px, closed-slab-in-hitbox), gated on the clearance model from an owner design pass.
3. **Crawl** — its own arc (subsumes #6 and trapdoor-in-1×1 + water-exit-crawl), later.

## OPEN QUESTIONS (for owner)
1. **Bit sharing:** OK to share facing (8-9) / half (10) / open (43) / toggleable (50) with stairs/doors, or do you want trapdoor fields distinct? (Sharing = ~0 new bits but couples the interpretation.) Does the stair-`half` bit's semantics (top/bottom **half of a stair**) cleanly double as trapdoor-`half` (top/bottom **of the cell**)?
2. **Clearance model:** how deep do you want v1 to go? Option A: v1 treats a trapdoor cell conservatively (blocked unless fully clear) and defers ALL residual-clearance to v2 — simpler, sometimes over-conservative. Option B: model residual height from the start. (A is the lower-risk v1.)
3. **Blocked-face representation:** a 6-way `trapBlockedFace` (3 bits) vs. reusing the door `blockedEdge` (cardinals) + a separate vertical bit. Which fits the shared seam more cleanly?
4. **Executor fragility:** confirm `TrapDoorBlock` has no `setOpen` convenience (verify at scope time) and whether `setTrapdoorOpen` needs an overlay or lives in `src/` like the door one.
5. **Crawl scope:** is Crawl a prerequisite for trapdoor v1, or fully separable? (v1's vertical "covers a hole" case doesn't need Crawl; only the 1×1-passage trapdoor does.)
6. **Waterlogged trapdoors:** interaction with the swim family — a waterlogged trapdoor in a swim path. Likely rare; flag for v2/Crawl.
7. **Performance:** trapdoors add more per-pop checks across MORE movements (the vertical family). Worth a perf pass (owner's queued startup-cost + node-flooding ideas) BEFORE this arc, since it widens the per-pop door-check surface.
