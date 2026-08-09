package com.orebit.mod.pathfinding.blockpathfinder;

import java.util.Arrays;

import com.orebit.mod.worldmodel.navblock.NavBlock;

import net.minecraft.core.BlockPos;

/**
 * A per-pathfind, reusable accumulator a {@link Movement} fills while testing the cells its geometry
 * touches, turning blocked-but-fixable cells into a break/place edit-set with an added cost
 * (MOVEMENT-DESIGN.md §1, decision 1). It centralises the "is this cell already fine, can I break/place
 * my way through it, or is the move impossible" decision so every movement reads it through one
 * vocabulary ({@link #requireAir}, {@link #requireFloor}) instead of re-deriving the bit checks.
 *
 * <p><b>Reused, not re-allocated.</b> One scratch lives on the {@link MovementContext} (single-threaded
 * per pathfind, like the underlying grid cursor). A movement calls {@link #reset()} before each
 * candidate, requires the cells it needs, then — if still {@link #valid()} — emits the destination with
 * {@code baseCost + }{@link #extraCost()} and hands this scratch to the {@link CandidateSink}. Only <i>after</i>
 * its relaxation gate accepts the candidate does the sink {@link #copyInto} a {@link StepEdits} drawn from
 * the search's per-search arena — so a rejected (non-improving) candidate allocates nothing, and an
 * accepted one reuses a pooled set rather than minting a fresh one. An edit-free move never reaches that
 * path at all: it stays on the plain {@link CandidateSink#accept(int, int, int, float)} call.
 */
public final class EditScratch {

    private final MovementContext ctx;

    // Small fixed buffers: a Tier 1 move touches ≤ ~3 body cells and places ≤ 1 floor; grown defensively.
    private long[] breaks = new long[6];
    private int breakCount;
    private long[] places = new long[3];
    private int placeCount;
    // Openable OPEN/CLOSE sets (DOORS P2; trapdoors DESIGN-trapdoors.md §5 share the channel) — a door
    // crossing folds a SET on each of its two body cells, a trapdoor on its single cell; almost always
    // empty. The parallel {@code doorOpens} says whether each target state is OPEN (true) or CLOSED;
    // {@code doorHalves} marks which entries are DOOR halves — scratch-only bookkeeping for the per-door
    // one-cost dedup (a trapdoor SET must never vertically pair with anything), never copied out.
    private long[] doors = new long[2];
    private boolean[] doorOpens = new boolean[2];
    private boolean[] doorHalves = new boolean[2];
    private int doorCount;
    // The clutch this candidate chose, if any (ClutchModel.NONE otherwise) — the block a deep Fall places
    // into its own landing cell mid-drop. A scalar, not a buffer: a step has at most ONE clutch, because a
    // clutch is a property of the single landing that terminates the drop.
    private int clutchKind = ClutchModel.NONE;
    private long clutchCell;
    private float extraCost;
    private boolean valid;
    private boolean allowEdits;

    EditScratch(MovementContext ctx) {
        this.ctx = ctx;
    }

    /** Clear the accumulator for a fresh candidate, edits permitted; returns {@code this} for fluent use. */
    public EditScratch reset() {
        return reset(true);
    }

    /**
     * Clear the accumulator for a fresh candidate. When {@code allowEdits} is false, no break or place is
     * folded — a blocked/empty required cell makes the move <i>invalid</i> instead of editing through it.
     * Movements pass {@code false} to honour the {@code RISKY_EDIT} flag: editing this floor cell's body
     * space could release a fluid or drop a gravity block, so the bot must reach it without editing or not
     * at all. Returns {@code this} for fluent use.
     */
    public EditScratch reset(boolean allowEdits) {
        breakCount = 0;
        placeCount = 0;
        doorCount = 0;
        clutchKind = ClutchModel.NONE; // cleared like the counts — a stale kind would clutch an unrelated step
        clutchCell = 0L;
        extraCost = 0f;
        valid = true;
        this.allowEdits = allowEdits;
        return this;
    }

    /**
     * Require cell {@code (x,y,z)} be clear for the bot's body. Already passable → free. Solid but
     * {@link MovementContext#breakable breakable} (and the bot may break) → fold a break in and add its
     * mining cost. Otherwise the move is impossible ({@link #valid()} goes false).
     */
    public void requireAir(int x, int y, int z) {
        if (!valid) return;
        long d = ctx.descriptorAt(x, y, z); // one read; reused by passable/breakable/breakCost below
        if (ctx.passable(d)) return;
        foldBreakOrFail(x, y, z, d);
    }

    /**
     * {@link #requireAir} made OPENABLE-AWARE for a crossing (doors P1/P2; trapdoors DESIGN-trapdoors.md
     * §4–§5) — the TRANSIT / LOWER-BODY cell primitive. {@code face} is the face of THIS cell the body
     * crosses to enter it: the cardinal entry-edge ordinal (0..3) — every live consumer is horizontal
     * (Traverse/Ascend via {@code requireBodyClearToward}, Descend's transit cells); the vertical family
     * clears its cells through {@link #requireAirVertical} instead. Verdicts, in order:
     * <ul>
     *   <li>passable → free (the untouched hot path);</li>
     *   <li>an intact door not blocking {@code face} → free ({@link MovementContext#doorEntryClear});</li>
     *   <li>a hand-toggleable door blocking {@code face} → fold a SET to the toggled state ({@link
     *       MovementContext#doorSetClears}, cost {@link MovementContext#DOOR_TOGGLE_COST} once per door);</li>
     *   <li>an OPEN trapdoor whose panel is not across {@code face} → free ({@link
     *       MovementContext#trapdoorEntryClear} — §4: the panel coexists with the 0.6 body);</li>
     *   <li>an OPEN trapdoor blocking {@code face} → fold a {@code SET_CLOSED} ({@link
     *       MovementContext#trapdoorSetClears}, single cell, same cost) and re-check the TOGGLED geometry
     *       against the transit rule: the closed plate bisects this cell, so the candidate self-refuses
     *       (§5 — the flat arm dies; the step-up/jump arms own the closed-plate geometry via {@link
     *       #requireFloorOrToggle});</li>
     *   <li><b>TOGGLE-FOR-CLEARANCE, trigger (b) (§5 — required, not optional)</b>: a toggleable CLOSED
     *       trapdoor bisecting this body cell ({@link MovementContext#toggleableClosedTrapdoor} — its
     *       UP/DOWN blocked face never matches a horizontal crossing, so the face arm above cannot fire) →
     *       fold a {@code SET_OPEN} and admit the opened panel, INCLUDING the face-vs-travel check ({@link
     *       MovementContext#panelParallel}): a panel that would lie ACROSS the travel axis (trapdoor facing
     *       along the corridor) refuses the toggle and falls through — the closed-blocks-headroom /
     *       open-blocks-travel combination is genuinely impassable without breaking;</li>
     *   <li>else the break fold ({@link #requireAir}'s tail), unchanged.</li>
     * </ul>
     * Returns the cell's EFFECTIVE descriptor for the caller's remaining within-candidate checks (§5
     * threading — {@code PathEdits} only covers ANCESTOR steps, so a fold made here is invisible to {@code
     * descriptorAt} until the next node): the read descriptor when passed free, the TOGGLED descriptor after
     * a SET fold, air after a break fold. Meaningful only while {@link #valid()}.
     */
    public long requireAirToward(int x, int y, int z, int face) {
        if (!valid) return MovementContext.AIR_DESC;
        long d = ctx.descriptorAt(x, y, z);
        if (ctx.passable(d)) return d;
        if (ctx.doorEntryClear(d, face)) return d; // intact door, not blocking our entry → free passage
        // P2: a hand-toggleable door blocking our entry edge — fold a cheap OPEN/CLOSE SET (prefer over smashing)
        // when doors.toggle is on. Toggling always moves the blocked panel to the perpendicular edge, so the
        // OTHER state frees this entry edge (see MovementContext.doorSetClears). Iron / non-toggleable doors and
        // the flag-off case fall through to the P1 break fold unchanged.
        if (ctx.doorSetClears(d, face)) {
            boolean target = ctx.doorToggledOpen(d);
            setDoor(x, y, z, target);
            return NavBlock.withOpenableOpen(d, target);
        }
        // Trapdoor arms (§4–§5) — behind the door tests (a cell is one openable kind; the common blocked cell
        // is neither and pays only the almost-always-false bit tests).
        if (ctx.trapdoorEntryClear(d, face)) return d; // open panel parallel to travel → free passage
        if (ctx.trapdoorSetClears(d, face)) {
            boolean target = ctx.doorToggledOpen(d); // shared open bit 43 — the openable toggled state
            long toggled = NavBlock.withOpenableOpen(d, target);
            setTrapdoor(x, y, z, target);
            // Post-toggle transit re-check: CLOSING a panel leaves a plate that bisects a transit cell —
            // this candidate self-refuses on the post-toggle geometry (the step-up/jump arms own the plate).
            if (!ctx.bodyPassable(toggled)) valid = false;
            return toggled;
        }
        // §5 TOGGLE-FOR-CLEARANCE trigger (b): a toggleable CLOSED trapdoor bisecting this body cell — its
        // blocked face is UP/DOWN while the crossing is horizontal, so only this clearance arm can offer the
        // SET_OPEN. The opened panel must lie PARALLEL to travel (a side wall — the §4 guide-rail admit);
        // along-axis facings refuse the toggle and fall through to the break fold (which the default
        // PROTECTED-trapdoors config then refuses — route around, per §5).
        if (face <= 3 && ctx.toggleableClosedTrapdoor(d)) {
            long toggled = NavBlock.withOpenableOpen(d, true);
            if (MovementContext.panelParallel(toggled, face)) {
                setTrapdoor(x, y, z, true);
                return toggled;
            }
        }
        foldBreakOrFail(x, y, z, d);
        return valid ? MovementContext.AIR_DESC : d;
    }

    /**
     * The UPPER-BODY (head) cell counterpart of {@link #requireAirToward} for a STANDING occupancy over a
     * floor of top {@code floorTopY} (sixteenths) — DESIGN-trapdoors.md §4. Identical verdict chain plus the
     * one extra face-blind admit: a <b>uniform high ceiling</b> ({@link MovementContext#ceilingAdmits}:
     * {@code ceilingMinY(d) ≥ floorTopY − 3} — a closed-TOP trapdoor or top slab whose underside clears the
     * body top), tested after the face-crossing toggle arm so a panel ACROSS the crossed face still folds its
     * SET (whose closed-TOP result the ceiling arm then admits — the §5 close-overhead case; a closed-BOTTOM
     * result bisects the head space and self-refuses) — and BEFORE the §5 trigger-(b) arm, so a closed-TOP
     * hatch that already admits as a ceiling is never needlessly toggled: trigger (b) fires only for a
     * closed plate that genuinely FAILS the §4 residual rules (a closed-BOTTOM head plate), folding a
     * {@code SET_OPEN} whose panel must lie parallel to travel ({@link MovementContext#panelParallel}).
     * Callers do not consult this cell at all when {@code floorTopY ≤ 3} (the exact-fit rule — {@link
     * MovementContext#requireBodyClearToward}); {@code face} is the cardinal entry edge (the vertical family
     * has no standing head cell). Returns the effective descriptor, as {@link #requireAirToward}.
     */
    public long requireUpperBodyToward(int x, int y, int z, int face, int floorTopY) {
        if (!valid) return MovementContext.AIR_DESC;
        long d = ctx.descriptorAt(x, y, z);
        if (ctx.passable(d)) return d;
        if (ctx.doorEntryClear(d, face)) return d;
        if (ctx.doorSetClears(d, face)) {
            boolean target = ctx.doorToggledOpen(d);
            setDoor(x, y, z, target);
            return NavBlock.withOpenableOpen(d, target);
        }
        if (ctx.trapdoorEntryClear(d, face)) return d;
        if (ctx.trapdoorSetClears(d, face)) {
            boolean target = ctx.doorToggledOpen(d);
            long toggled = NavBlock.withOpenableOpen(d, target);
            setTrapdoor(x, y, z, target);
            // Post-toggle head-space re-check: an opened panel is body-passable; a closed one admits only
            // via the ceiling band (closed-TOP → flush overhead hatch; closed-BOTTOM bisects → refuse).
            if (!ctx.bodyPassable(toggled) && !ctx.ceilingAdmits(toggled, floorTopY)) valid = false;
            return toggled;
        }
        if (ctx.ceilingAdmits(d, floorTopY)) return d; // §4: flush top-band ceiling (closed-top hatch / top slab)
        // §5 TOGGLE-FOR-CLEARANCE trigger (b), head-cell flavor: a toggleable CLOSED plate that failed the
        // ceiling arm above (a closed-BOTTOM head plate — the head-bisection case) folds a SET_OPEN when the
        // opened panel lies parallel to travel; along-axis facings fall through to the break fold (§5).
        if (face <= 3 && ctx.toggleableClosedTrapdoor(d)) {
            long toggled = NavBlock.withOpenableOpen(d, true);
            if (MovementContext.panelParallel(toggled, face)) {
                setTrapdoor(x, y, z, true);
                return toggled;
            }
        }
        foldBreakOrFail(x, y, z, d);
        return valid ? MovementContext.AIR_DESC : d;
    }

    /**
     * The VERTICAL-family cell requirement (DESIGN-trapdoors.md §5) — for a cell the body crosses through
     * BOTH vertical faces, or must wholly clear for a jump: {@code Pillar}'s overhead cell {@code y+3} (the
     * shaft the jump rises through) and {@code MineDown}'s own floor cell (the hatch it drops through).
     * Verdicts, in order:
     * <ul>
     *   <li>{@link MovementContext#bodyPassable body-passable} (passable, or an OPEN trapdoor — §4: the
     *       wall-hugging panel coexists with the centred 0.6 shaft body, and its cardinal blocked face is
     *       never a vertical crossing's) → free;</li>
     *   <li>a toggleable CLOSED trapdoor ({@link MovementContext#trapdoorSetClearsVertical} — EITHER half:
     *       a closed plate's blocked face is always vertical, and the vertical pass-through/jump crosses
     *       both vertical faces) → fold a {@code SET_OPEN}; the opened panel is body-passable by
     *       construction (re-checked for parity with the other toggle arms);</li>
     *   <li>else the break fold ({@link #requireAir}'s tail) — iron / flag-off trapdoors and every other
     *       blocked cell, byte-identical verdict and cost to the historical {@code requireAir}.</li>
     * </ul>
     * Deliberately does NOT free-pass intact doors (unlike the vertical faces of {@link #requireAirToward}):
     * the historical vertical-family behaviour broke/refused a door cell and no ruling widened it. Like the
     * other SET folds — and unlike the break tail — the toggle bypasses the {@code RISKY_EDIT}
     * ({@code allowEdits}) gate (the door-symmetric precedent, see {@link #requireFloorOrToggle}). Returns
     * the cell's EFFECTIVE descriptor, as {@link #requireAirToward}.
     */
    public long requireAirVertical(int x, int y, int z) {
        if (!valid) return MovementContext.AIR_DESC;
        long d = ctx.descriptorAt(x, y, z);
        if (ctx.bodyPassable(d)) return d;
        if (ctx.trapdoorSetClearsVertical(d)) {
            long toggled = NavBlock.withOpenableOpen(d, true); // SET_OPEN — swing the hatch out of the shaft
            setTrapdoor(x, y, z, true);
            if (!ctx.bodyPassable(toggled)) valid = false; // always-true today; parity with the other arms
            return toggled;
        }
        foldBreakOrFail(x, y, z, d);
        return valid ? MovementContext.AIR_DESC : d;
    }

    /**
     * The FACE-BLIND lower-body cell requirement — {@link #requireAir} plus the §4 residual admit ({@link
     * MovementContext#bodyPassable}: passable OR open-trapdoor, no face test — pure occupancy). The door-blind
     * movements' {@code requireBodyClear} c1 primitive; deliberately does NOT admit doors (byte-identity with
     * the historical door-blind path). Returns the effective descriptor, as {@link #requireAirToward}.
     */
    public long requireBodyCell(int x, int y, int z) {
        if (!valid) return MovementContext.AIR_DESC;
        long d = ctx.descriptorAt(x, y, z);
        if (ctx.bodyPassable(d)) return d;
        foldBreakOrFail(x, y, z, d);
        return valid ? MovementContext.AIR_DESC : d;
    }

    /**
     * The FACE-BLIND upper-body cell requirement over a floor of top {@code floorTopY} — {@link
     * #requireBodyCell} plus the §4 {@link MovementContext#ceilingAdmits uniform-top-band} admit. Callers skip
     * this cell entirely at {@code floorTopY ≤ 3} (the exact-fit rule). Door-blind like {@code
     * requireBodyCell}. Returns the effective descriptor.
     */
    public long requireUpperBody(int x, int y, int z, int floorTopY) {
        if (!valid) return MovementContext.AIR_DESC;
        long d = ctx.descriptorAt(x, y, z);
        if (ctx.bodyPassable(d)) return d;
        if (ctx.ceilingAdmits(d, floorTopY)) return d;
        foldBreakOrFail(x, y, z, d);
        return valid ? MovementContext.AIR_DESC : d;
    }

    /** Shared tail of {@link #requireAir}/{@link #requireAirToward}: fold a break of a breakable blocked cell
     *  (adding its mining cost) when edits are allowed, else invalidate the move. */
    private void foldBreakOrFail(int x, int y, int z, long d) {
        if (allowEdits && ctx.breakable(d)) {
            breaks = push(breaks, breakCount, x, y, z);
            breakCount++;
            extraCost += ctx.breakCost(d);
        } else {
            valid = false; // blocked, and either the bot can't break it or an edit here is forbidden (risky)
        }
    }

    /**
     * Require footing at floor cell {@code (x,y,z)}. Already {@link MovementContext#standable standable}
     * → free. Empty but {@link MovementContext#placeable placeable} (and the bot may place) → fold a
     * place in and add its cost. Otherwise the move is impossible.
     */
    public void requireFloor(int x, int y, int z) {
        if (!valid) return;
        long d = ctx.descriptorAt(x, y, z); // one read; reused by standable/placeable below
        if (ctx.standable(d)) return;
        if (allowEdits && ctx.placeable(x, y, z, d)) {
            addPlace(x, y, z);
        } else {
            valid = false; // no footing, and either the bot can't place or an edit here is forbidden (risky)
        }
    }

    /**
     * {@link #requireFloor} grown the trapdoor arm (DESIGN-trapdoors.md §5): a dest floor cell that is not
     * standable but is a toggleable OPEN trapdoor ({@link MovementContext#trapdoorSetFloors} — {@code
     * doors.toggle} on, not iron) folds a {@code SET_CLOSED} — the closed state (3/16 plate for a BOTTOM
     * half, flush 16/16 hatch for a TOP half) is standable by construction — at the same single {@link
     * MovementContext#DOOR_TOGGLE_COST}. Ordered standable → toggle → place, so every non-open-trapdoor
     * cell behaves bit-identically to {@code requireFloor} (Stage 3 movements switch their dest-floor call
     * sites over without behavior change on trapdoor-free worlds). Like the crossing SET folds — and unlike
     * the break/place folds — the toggle deliberately bypasses the {@code RISKY_EDIT} ({@code allowEdits})
     * gate, the door-symmetric precedent ({@link #requireAirToward} folds door SETs unconditionally too).
     *
     * <p>Returns the EFFECTIVE floor descriptor — the read one when already standable, the TOGGLED one after
     * the SET fold (so the movement's rise/topY math uses the real closed topY, 3 or 16 per half — the §5
     * threading contract: the caller must NOT re-read the cell via {@code descriptorAt}, which only sees
     * ANCESTOR steps' edits), or the conjured full-cube {@code PLACED} descriptor after a place fold.
     * The caller keeps its own gates: this method never judges rise — "return the toggled descriptor, let
     * the movement's own gates decide". Meaningful only while {@link #valid()}.
     */
    public long requireFloorOrToggle(int x, int y, int z) {
        if (!valid) return MovementContext.AIR_DESC;
        long d = ctx.descriptorAt(x, y, z);
        if (ctx.standable(d)) return d;
        if (ctx.trapdoorSetFloors(d)) {
            setTrapdoor(x, y, z, false); // SET_CLOSED — close the hatch into a floor
            return NavBlock.withOpenableOpen(d, false);
        }
        if (allowEdits && ctx.placeable(x, y, z, d)) {
            addPlace(x, y, z);
            return MovementContext.PLACED_DESC;
        }
        valid = false;
        return d;
    }

    /**
     * Require footing at {@code (fx,fy,fz)}, building a <b>support</b> at {@code (sx,sy,sz)} directly
     * beneath it first if the footing has nothing of its own to place against — the two-block staircase
     * step (MOVEMENT-DESIGN §2). Three cases: already {@link MovementContext#standable standable} → free;
     * directly {@link MovementContext#placeable placeable} (a face already exists — terrain or a wall) →
     * place just the footing; otherwise, if the footing cell is {@link MovementContext#openForPlace open}
     * and the support is {@link MovementContext#placeable placeable}, place <b>both</b> the support and the
     * footing on top of it. Else the move is impossible.
     *
     * <p>This is what lets the bot build a diagonal staircase up through open air / off a ledge: a lone
     * footing one-up-and-over has no face to attach to, but a support placed beside the floor the bot
     * stands on gives it one. That floor reads SOLID even when it's a block a <i>preceding step placed</i>,
     * because the search feeds the path's {@link PathEdits} diff into {@code descriptorAt} — so the
     * support's {@code placeable} check finds the face and the staircase <b>chains across steps</b> (without
     * the diff it would dead-end after one step, the next support looking unanchored). Two placements per
     * step, so A* prefers a natural slope or (later) a Pillar where those are cheaper.
     */
    public void requireFootingOn(int fx, int fy, int fz, int sx, int sy, int sz) {
        if (!valid) return;
        long fd = ctx.descriptorAt(fx, fy, fz);
        if (ctx.standable(fd)) return;
        if (!allowEdits) { valid = false; return; }
        if (ctx.placeable(fx, fy, fz, fd)) { // footing already has a face — one placement
            addPlace(fx, fy, fz);
            return;
        }
        // No face of the footing's own: place a support beneath it, then the footing rests on it. The
        // support's face is the floor the bot stands on, which reads solid via the PathEdits diff (real
        // terrain or a preceding step's block), so a plain placeable() check finds it — staircase chains.
        long sd = ctx.descriptorAt(sx, sy, sz);
        if (ctx.openForPlace(fd) && ctx.placeable(sx, sy, sz, sd)) {
            addPlace(sx, sy, sz);
            addPlace(fx, fy, fz);
        } else {
            valid = false;
        }
    }

    private void addPlace(int x, int y, int z) {
        places = push(places, placeCount, x, y, z);
        placeCount++;
        extraCost += ctx.placeCost(x, y, z); // real ticks-to-place (+ inventory premium when consuming) — 1d
    }

    /**
     * Fold an OPEN/CLOSE of the (hand-toggleable) door at cell {@code (x,y,z)} to {@code targetOpen} (DOORS P2)
     * — the "right-click the door" alternative to smashing it (or to skipping the direction). The caller has
     * already proven, via {@link MovementContext#doorSetClears}, that the door is toggleable, that {@code
     * doors.toggle} is on, and that reaching {@code targetOpen} clears the crossing edge.
     *
     * <p><b>One interaction, one cost — even though a door is two body cells.</b> A crossing folds a SET on
     * BOTH the door's cells (feet + head) so every downstream {@code descriptorAt} of the door reads the same
     * state; but the toggle is a single right-click, so the {@link MovementContext#DOOR_TOGGLE_COST} is charged
     * only for the FIRST cell of a door — the second (vertically adjacent, same target, itself a door half) is
     * recognised as the other half and folded free. Re-folding the exact same cell is a no-op. This keeps the
     * g-cost honest (one toggle ≈ 6 ticks ≪ breaking both halves) without needing to know which half is the
     * lower one. The two-half dedup is DOOR-GATED (DESIGN-trapdoors.md §5): only entries folded through
     * {@code setDoor} participate, so a single-cell trapdoor SET vertically adjacent to a door half — or to
     * another trapdoor — is always charged its own toggle.
     */
    void setDoor(int x, int y, int z, boolean targetOpen) {
        setOpenable(x, y, z, targetOpen, true);
    }

    /**
     * Fold an OPEN/CLOSE of the (hand-toggleable) trapdoor at cell {@code (x,y,z)} to {@code targetOpen}
     * (DESIGN-trapdoors.md §5) — the single-cell twin of {@link #setDoor}, riding the SAME doors[] channel
     * ({@code SET_OPEN}/{@code SET_CLOSED} are kind-agnostic; {@code descriptorAt} resolves through the
     * unified {@link NavBlock#withOpenableOpen}). A trapdoor is ONE cell, so it never participates in the
     * per-door two-half dedup — each trapdoor SET charges its own {@link MovementContext#DOOR_TOGGLE_COST}
     * (re-folding the exact same cell within one candidate stays a no-op). The caller has already proven the
     * toggle offered ({@link MovementContext#trapdoorSetClears} / {@link MovementContext#trapdoorSetFloors}).
     */
    void setTrapdoor(int x, int y, int z, boolean targetOpen) {
        setOpenable(x, y, z, targetOpen, false);
    }

    /** Shared tail of {@link #setDoor}/{@link #setTrapdoor}: exact-cell no-op dedup, the door-gated two-half
     *  cost dedup ({@code doorHalf} entries only, both sides), append, one toggle cost per openable. */
    private void setOpenable(int x, int y, int z, boolean targetOpen, boolean doorHalf) {
        long cell = BlockPos.asLong(x, y, z);
        for (int i = 0; i < doorCount; i++) {
            if (doors[i] == cell) return; // already folded this exact cell
        }
        boolean sameDoor = false;
        if (doorHalf) {
            long below = BlockPos.asLong(x, y - 1, z), above = BlockPos.asLong(x, y + 1, z);
            for (int i = 0; i < doorCount; i++) {
                if ((doors[i] == below || doors[i] == above) && doorOpens[i] == targetOpen && doorHalves[i]) {
                    sameDoor = true;
                    break;
                }
            }
        }
        if (doorCount == doors.length) {
            doors = Arrays.copyOf(doors, doors.length * 2);
            doorOpens = Arrays.copyOf(doorOpens, doorOpens.length * 2);
            doorHalves = Arrays.copyOf(doorHalves, doorHalves.length * 2);
        }
        doors[doorCount] = cell;
        doorOpens[doorCount] = targetOpen;
        doorHalves[doorCount] = doorHalf;
        doorCount++;
        if (!sameDoor) extraCost += MovementContext.DOOR_TOGGLE_COST; // one right-click per openable, not per half
    }

    /**
     * Fold a <b>break-through</b> of a PASSABLE hazard/through-slow body cell (berry bush, cobweb, fire —
     * cells {@link #requireAir} leaves alone because nothing blocks) at a caller-computed cost — the
     * "punch the bush and walk through" option. The caller ({@link MovementContext#bodyTransitCost(EditScratch,
     * int, int, int, int)}) has already gated on {@link MovementContext#breakableThrough} and proven the
     * break cheaper than transiting the cell intact; {@code cost} is the real mining ticks plus the
     * {@code mining.breakBaseCost} surcharge, charged here in place of the transit surcharge the cell
     * would otherwise add. Package-private: only the context's transit vocabulary emits these.
     */
    void breakThrough(int x, int y, int z, float cost) {
        breaks = push(breaks, breakCount, x, y, z);
        breakCount++;
        extraCost += cost;
    }

    /**
     * Record that this candidate survives its drop by placing the {@link ClutchModel} block of {@code kind}
     * into cell {@code (x,y,z)} mid-fall — the planner's CHOICE of clutch, carried to the executor.
     *
     * <p><b>This is a channel, not a cost.</b> It folds no break and no place and adds nothing to
     * {@link #extraCost()}: {@link com.orebit.mod.pathfinding.blockpathfinder.movements.Fall} has already
     * priced the clutch itself (residual {@link ClutchModel#damageBlocks} × ticks-per-HP, plus the placement
     * price where the kind folds one), because only the caller knows the drop depth and the chosen kind's
     * absorption curve. Adding a price here would double-charge it.
     *
     * <p><b>Why the cell is a parameter rather than derived from the node.</b> The two landing geometries put
     * the block in different cells (ClutchModel §Landing): a SINK-THROUGH kind (water, powder snow) goes in
     * the landing FEET cell {@code (fy+1)} while the bot comes to rest on the pre-existing floor {@code fy},
     * so the node is unmoved and NO geometry edit is folded — folding a place there would make the node read
     * its own body cell as solid and dead-end itself. A LANDS-ON-TOP kind (slime, hay) goes in {@code fy+1}
     * as the FLOOR the bot stands on, moving the node up one and shortening the effective drop, and DOES fold
     * an ordinary place through {@link #requireFloor}. One scalar cell covers both without the scratch having
     * to know which geometry applies.
     *
     * <p>Last call wins (a plain overwrite): a movement evaluates one landing per candidate, and
     * {@link #reset(boolean)} clears the slot between candidates, so there is no accumulation to reconcile.
     */
    public void setClutch(int kind, int x, int y, int z) {
        clutchKind = kind;
        clutchCell = BlockPos.asLong(x, y, z);
    }

    /**
     * Whether this candidate may fold edits at all — {@code reset(false)} (a {@code RISKY_EDIT} floor)
     * forbids them, and an already-invalid scratch has nothing to gain. The gate the context's
     * break-through fold checks before recording a break (mirrors {@link #requireAir}'s own gate).
     */
    boolean editsAllowed() {
        return allowEdits && valid;
    }

    /** Whether every required cell was satisfiable (directly or via an allowed break/place). */
    public boolean valid() {
        return valid;
    }

    /** The mining + placing cost to add to the move's base traversal cost. */
    public float extraCost() {
        return extraCost;
    }

    /**
     * Whether this candidate carries ANYTHING the executor must be told about — a break, a place, a door-set,
     * or a {@linkplain #setClutch clutch} — and therefore whether the search must copy it into a
     * {@link StepEdits} instead of leaving the edge a plain {@code null}. This is the gate {@code
     * BlockPathfinder}'s relaxer tests before drawing from the edit arena.
     *
     * <p>The clutch disjunct is load-bearing, not defensive: a SINK-THROUGH clutch (water, powder snow) folds
     * NO geometry by design, so without it a water-bucket clutch would be silently discarded at the relaxer
     * and the bot would walk off the cliff with no plan to place anything. When no clutch is set the term is
     * one always-false compare against a scalar field — {@code hasEdits()} is then bit-for-bit the old
     * predicate, so an unclutched search is unchanged.
     */
    public boolean hasEdits() {
        return breakCount != 0 || placeCount != 0 || doorCount != 0 || clutchKind != ClutchModel.NONE;
    }

    /**
     * Whether any WORLD-GEOMETRY edit (break / place / door-set) was folded — {@link #hasEdits()} minus the
     * clutch channel. Distinct because {@code BlockPathfinder}'s {@code anyEdits} flag gates the per-pop
     * {@link PathEdits} diff rebuild, and a clutch alone contributes nothing to that diff
     * ({@link PathEdits#add} reads only the three geometry lists): folding the clutch into {@code anyEdits}
     * would switch on a whole-chain walk per expansion that provably produces an empty diff. Identical to
     * {@link #hasEdits()} whenever no clutch is set.
     */
    boolean hasGeometryEdits() {
        return breakCount != 0 || placeCount != 0 || doorCount != 0;
    }

    /**
     * Load this candidate's accumulated edits into a <b>pooled</b> {@link StepEdits} drawn from the
     * search's per-search arena — the allocation-free replacement for the old {@code snapshot()} once the
     * sink has decided to keep the candidate (the rejected majority never reaches here). The pooled
     * instance reuses/grows its own buffers, so steady state touches no heap. Call only when
     * {@link #hasEdits()} (the search gates on it; an empty set should stay a plain {@code null} edge).
     *
     * <p>The {@linkplain #setClutch clutch} kind + cell ride along as two scalar stores — always written, so a
     * recycled arena slot cannot leak a previous edge's clutch onto this one.
     */
    void copyInto(StepEdits e) {
        e.load(breaks, breakCount, places, placeCount, doors, doorOpens, doorCount, clutchKind, clutchCell);
    }

    private static long[] push(long[] buf, int count, int x, int y, int z) {
        if (count == buf.length) buf = Arrays.copyOf(buf, buf.length * 2);
        buf[count] = BlockPos.asLong(x, y, z);
        return buf;
    }
}
