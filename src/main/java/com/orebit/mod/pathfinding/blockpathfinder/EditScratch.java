package com.orebit.mod.pathfinding.blockpathfinder;

import java.util.Arrays;

import com.orebit.mod.worldmodel.navblock.NavBlock;
import com.orebit.mod.worldmodel.pathing.NavFlags;

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
    // Parallel to breaks: each break's fold verdict as a PathEdits kind — BROKEN (dry), BROKEN_WATER or
    // BROKEN_LAVA (the fluidVerdict funnel, decided at fold time in addBreak) — carried out to StepEdits
    // verbatim, so the diff reads the mined cell as air / water / lava.
    private byte[] breakKind = new byte[6];
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

    EditScratch(MovementContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Clear the accumulator for a fresh candidate; returns {@code this} for fluent use.
     *
     * <p><b>There is no longer an {@code allowEdits} parameter</b> (owner ruling 2026-08-21). The old
     * {@code reset(boolean)} carried a gate the CALLER had read from ONE cell — under the floor-framed
     * {@code RISKY_EDIT}, typically the movement's start or destination FLOOR — and then applied to every
     * edit the candidate folded, some of them one to three cells away. That is exactly the defect that
     * killed the 2026-08-21 flagship: {@code Pillar} seeded the gate from its start floor and folded
     * landing-body breaks at {@code y+J+1}/{@code y+J+2} that NOTHING gated. The bit is now cell-centred
     * ({@code NavFlags.RISKS_GRAVITY}) and the gate is <b>fold-sited</b> — see {@link #mayEdit} — so no
     * movement can forget it and none can apply it to the wrong cell.
     */
    public EditScratch reset() {
        breakCount = 0;
        placeCount = 0;
        doorCount = 0;
        clutchKind = ClutchModel.NONE; // cleared like the counts — a stale kind would clutch an unrelated step
        clutchCell = 0L;
        extraCost = 0f;
        valid = true;
        return this;
    }

    /**
     * <b>THE EDIT GATE</b> (owner ruling 2026-08-21). {@code NavFlags.RISKS_GRAVITY} is CELL-CENTRED —
     * "editing THIS cell drops a gravity block" (a supported gravity block resting directly on it, or an
     * unsupported one orthogonally adjacent) — so it is tested at the cell actually being edited, at the
     * single point each edit kind is folded: {@link #addBreak} and {@link #addPlace}. No movement can
     * forget it, which is exactly the defect it replaces: {@code Pillar}'s landing-body breaks,
     * {@code Descend}'s {@code y+2} break, {@code Ascend}'s {@code y+3} break and staircase support place,
     * and every ground move's head-cell break were all folded under a gate read at a DIFFERENT cell.
     *
     * <p>Costs one grid read, and only on the fold path: every caller tests the cheap descriptor predicate
     * ({@code breakable} / {@code placeable}) first, so an unedited required cell reads nothing.
     */
    private boolean mayEdit(int x, int y, int z) {
        return !ctx.risksGravityAt(x, y, z);
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
     *   <li><b>fence gate (DESIGN-fence-gates.md §3)</b>: a toggleable CLOSED gate ({@link
     *       MovementContext#gateSetClears} — FACE-AGNOSTIC: the centered 4/16 plate blocks every crossing
     *       and {@code face} is unused, so the arm serves vertical faces too) → fold a {@code SET_OPEN}
     *       and admit; the toggled state is {@code Shapes.empty()}, passable by construction, so there is
     *       no post-toggle re-evaluation and no gate analog of the face-vs-travel check;</li>
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
        // Gate arm (DESIGN-fence-gates.md §3) — face-agnostic: a closed gate blocks every crossing and its
        // open state is empty collision, so one SET_OPEN clears any face (incl. vertical — `face` unused)
        // with no post-toggle re-check. Behind the door/trapdoor arms: the common blocked cell pays one
        // almost-always-false kind test.
        if (ctx.gateSetClears(d)) {
            setGate(x, y, z, true);
            return NavBlock.withOpenableOpen(d, true);
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
        // Gate arm (DESIGN-fence-gates.md §3) — a closed gate in a head cell (over a t>3 floor) opens to
        // empty collision; face-agnostic, no post-toggle re-check (see requireAirToward's gate arm).
        if (ctx.gateSetClears(d)) {
            setGate(x, y, z, true);
            return NavBlock.withOpenableOpen(d, true);
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
     * other SET folds — and unlike the break tail — the toggle bypasses the {@code RISKS_GRAVITY} edit gate
     * (the door-symmetric precedent, see {@link #requireFloorOrToggle}). Returns the cell's EFFECTIVE
     * descriptor, as {@link #requireAirToward}.
     */
    public long requireAirVertical(int x, int y, int z) {
        return requireAirVertical(x, y, z, 0f);
    }

    /**
     * {@link #requireAirVertical(int, int, int)} with an EXPLICIT mining-stance multiplier for the break
     * fold ({@code stanceMult ≤ 0} = the node's own per-pop stance, the plain form's behaviour). The one
     * caller that needs it is {@code MineDown}'s macro shaft: the collapsed jump folds breaks the bot
     * performs from positions BELOW the expanding node (standing one cell above each successive floor it
     * digs), so the per-pop stance is only right for level 1 — deeper levels dig grounded, submerged
     * exactly when THAT level's head cell reads water through the diff and this scratch's own earlier
     * folds (the per-level stance, see {@code MineDown.candidates} and {@link #readsWater}).
     */
    public long requireAirVertical(int x, int y, int z, float stanceMult) {
        if (!valid) return MovementContext.AIR_DESC;
        long d = ctx.descriptorAt(x, y, z);
        if (ctx.bodyPassable(d)) return d;
        if (ctx.trapdoorSetClearsVertical(d)) {
            long toggled = NavBlock.withOpenableOpen(d, true); // SET_OPEN — swing the hatch out of the shaft
            setTrapdoor(x, y, z, true);
            if (!ctx.bodyPassable(toggled)) valid = false; // always-true today; parity with the other arms
            return toggled;
        }
        foldBreakOrFail(x, y, z, d, stanceMult);
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
     *  (adding its mining cost at the node's per-pop stance) when edits are allowed, else invalidate the move. */
    private void foldBreakOrFail(int x, int y, int z, long d) {
        foldBreakOrFail(x, y, z, d, 0f);
    }

    /** {@link #foldBreakOrFail(int, int, int, long)} with an explicit mining-stance multiplier
     *  ({@code stanceMult ≤ 0} = the node's per-pop stance) — the macro-shaft seam, see
     *  {@link #requireAirVertical(int, int, int, float)}. */
    private void foldBreakOrFail(int x, int y, int z, long d, float stanceMult) {
        if (ctx.breakable(d) && addBreak(x, y, z)) {
            extraCost += stanceMult > 0f ? ctx.breakCost(d, stanceMult) : ctx.breakCost(d);
        } else {
            valid = false; // blocked, and either the bot can't break it or breaking it drops gravity on us
        }
    }

    /**
     * Record a break of cell {@code (x,y,z)}, deciding at fold time whether fluid floods the opened cell —
     * the {@linkplain #fluidVerdict evaluation funnel} (DESIGN-fluid-flow-prediction.md §5). The verdict is
     * stored as the break's {@link PathEdits} kind and carried verbatim out to {@link StepEdits}, so every
     * later read of the cell — this candidate's own tiers ({@link #scratchDescriptorAt}), later pops on
     * this path ({@code descriptorAt}), spliced searches ({@link EditSnapshot}) and the invalidation
     * seam's expectation arm — sees the same air / water / lava answer the fold's price was computed from.
     *
     * <p><b>This is the single break-fold funnel, and {@link #mayEdit} lives here so it cannot be
     * bypassed</b> — a refused cell returns {@code false} and folds nothing (the caller decides whether that
     * invalidates the move or merely declines an optional break). The openable SET folds are deliberately
     * NOT breaks and do not pass through here — see {@link #setOpenable}.
     *
     * @return whether the break was folded ({@code false} = refused by the gravity gate)
     */
    private boolean addBreak(int x, int y, int z) {
        if (!mayEdit(x, y, z)) return false;
        byte kind = fluidVerdict(x, y, z);
        if (breakCount == breaks.length) {
            breaks = Arrays.copyOf(breaks, breaks.length * 2);
            breakKind = Arrays.copyOf(breakKind, breaks.length);
        }
        breaks[breakCount] = BlockPos.asLong(x, y, z);
        breakKind[breakCount] = kind;
        breakCount++;
        return true;
    }

    // ---- The fluid-flow evaluation funnel (DESIGN-fluid-flow-prediction.md §5) ----------------------
    // "Will breaking this cell admit fluid?" — answered lazily, at fold time, never at classify time (§2:
    // drain distance is a property of the neighbourhood and cannot be a bit). Vanilla facts it mirrors are
    // §1's bytecode-verified FlowingFluid.spread(); constants below are the fluid spread parameters.

    /** Lateral neighbour offsets in the {@code NavFlags.SIX}-table order with the vertical entries dropped:
     *  x−, x+, z−, z+. Tier 1 scans candidates in this order and the FIRST fluid verdict wins (§5's
     *  accepted arbitrary tie-break when water and lava both border the break — vanilla would convert
     *  blocks, out of scope §10). Index {@code i ^ 1} is the opposite direction (the backtrack). */
    private static final int[] LAT_DX = {-1, 1, 0, 0};
    private static final int[] LAT_DZ = {0, 0, -1, 1};

    /** Vanilla's "no hole found within slopeFind" sentinel — every direction that finds nothing ties here,
     *  which is what makes the blind band (§1.1: water reaches 6–7 but detects only to 5) spread-everywhere. */
    private static final int SLOPE_UNFOUND = 1000;
    /** The unified {@code getSlopeFindDistance()} for BOTH fluids — detects a hole to distance 5
     *  (recursion starts at depth 1). Exact for water everywhere and for nether lava; for lava this is
     *  the <b>NETHER pin</b> (owner-ratified 2026-08-17, DESIGN-fluid-flow-prediction.md §5): dimension
     *  identity is unreachable from candidate emission ({@code NavGridView.level} is null on planner
     *  threads), so one constant must serve both dimensions — and the tie arithmetic decides which. Ties
     *  win, so a SMALLER slope-find can only remove drain detections, which can only flip verdicts toward
     *  WET: pinning overworld's 2 would predict floods in the nether that real nether lava (slope-find 4)
     *  drains away — phantom fluid, the one §8-unrecoverable error class. Pinning 4 instead errs DRY in
     *  the overworld: a drain at slope distance 4–5 is visible here but not to real overworld lava, so
     *  the dig is predicted dry and real lava floods in — recoverable (the arrival is a real block change
     *  → invalidation → replan, with lava's 30-tick spread delay, §1.4, as escape margin). */
    private static final int SLOPE_FIND = 4;

    /**
     * The <b>evaluation funnel</b>: will breaking cell {@code (x,y,z)} admit fluid, and which — the fold
     * verdict as a {@link PathEdits} kind: {@link PathEdits#BROKEN} (dry), {@link PathEdits#BROKEN_WATER},
     * or {@link PathEdits#BROKEN_LAVA} (DESIGN-fluid-flow-prediction.md §5–§6). No verdict refuses the
     * break — there is no feasibility case here at all (§6: a predicted flood is PRICED, via the stance
     * multiplier and the fluid cell the diff then reports; lava's damage pricing already exists §4.2).
     *
     * <p><b>Read discipline (§5, owner-ratified):</b> every read at every tier goes through
     * {@link #scratchDescriptorAt} — this candidate's own in-scratch folds FIRST (they are invisible to
     * {@code descriptorAt} until the edge is accepted — a macro shaft's level {@code k} sits under level
     * {@code k−1}'s in-scratch break; a {@code Traverse} folds two breaks per step and the second cell's
     * verdict must see the first's), then the {@link PathEdits}-layered grid. <b>No memoisation</b>
     * (owner-ratified §5): the slope search reads through the path's own edits, which differ per BRANCH of
     * the search, not per search — a cached verdict shared across branches could err WET, the one
     * unrecoverable direction (§8). Recursion is pure primitives — depth ≤ {@link #SLOPE_FIND},
     * branching ≤ 3, no allocation, no visited set (vanilla has none; backtrack exclusion only).
     *
     * <p><b>Tier 0a — the certain vertical rule, per-fluid</b> (free — one read). Vanilla falling fluid
     * ALWAYS fills the cell below, water and lava alike, so fluid directly above the break is the most
     * certain flood there is: swimmable water above ⇒ {@code BROKEN_WATER}, swimmable lava above ⇒
     * {@code BROKEN_LAVA} (the per-fluid generalisation of the shipped 2026-08-16 water-only wet-above
     * rule, whose lava hole — digging under lava folded plain {@code BROKEN} — this closes). Read
     * scratch-first, so an own-fold break above chains ITS kind down a shaft, and an own-fold PLACE above
     * seals the column — which no longer returns dry outright but <b>falls THROUGH to the lateral
     * tiers</b> (a sealed column can still flood sideways; deliberate improvement over the old early
     * return). Accepted inexactness, errs dry: a bubble column or a waterlogged solid above reads dry
     * ({@code isSwimmableWater/Lava} exclude both), though a vanilla bubble cell IS water and would flood.
     *
     * <p><b>Tier 0b — the flag early-out</b> (the common exit). {@code HAS_FLUID_NEIGHBOR} clear at the
     * broken cell (cell-centred frame — §4: read AT the cell actually broken, never a floor/body frame) ⇒
     * dry, done. Exact against COMMITTED state and deliberately blind to plan-created fluid
     * ({@code flagsAt} never layers {@code PathEdits} — §8 table row, errs dry, KNOWN AND DEFERRED to the
     * PathEdits-scatter workflow; do not fix here). On fluid-free terrain every break exits here with the
     * verdict — and the folded kinds and prices — it always had.
     *
     * <p><b>Tier 1 — spread eligibility per lateral neighbour W</b> (~4–6 reads each, §1.1's steady-state
     * table): only a {@code genuineOpenFluidCell} counts (swimmable water/lava — waterlogged partials
     * excluded, §8.2's owner-ratified inexactness, errs dry); a min-level cell ({@code isFluidMinLevel})
     * cannot spread at all; a cell that {@linkplain #canFlowDown can flow down} (below reads genuinely
     * empty through scratch+diff — the plan's own earlier breaks are what open that path) spreads
     * laterally only as a near-source ({@code sourceNeighborCount ≥ 3}); otherwise it spreads iff it is a
     * source or is not already draining into a hole ({@code isSource || !isHole}, the isSame-first vanilla
     * shape). Eligible is necessary, not sufficient — every survivor still faces tier 2.
     *
     * <p><b>Tier 2 — the slope-distance tie test</b> (rare, bounded): does the broken cell's direction tie
     * or beat the minimum slope distance over W's other candidate directions (§1.1 — {@code <=}, TIES ALL
     * WIN; we never compute the winning direction or the flood shape)? Mirrors {@code getSlopeDistance}:
     * shortest path over passable cells, backtrack excluded, depth-capped at the unified
     * {@link #SLOPE_FIND} (the nether pin — see its note for the accepted overworld-lava errs-dry
     * consequence). The broken cell itself reads as OPEN in this competition (it is the
     * candidate being evaluated) and, per §1.5, is never a hole at the moment of its own break UNLESS the
     * scratch/diff already opened the cell below it — the honest below-read gives exactly that. A tying W
     * returns its fluid's kind; a losing W keeps the scan going; all lose ⇒ dry. Cascades past the first
     * cell are not modelled (§10 — errs dry, recoverable).
     */
    private byte fluidVerdict(int x, int y, int z) {
        // Tier 0a — the certain vertical rule, per-fluid, scratch-first (an own place falls through).
        long above = scratchDescriptorAt(x, y + 1, z);
        if (NavBlock.isSwimmableWater(above)) return (byte) PathEdits.BROKEN_WATER;
        if (NavBlock.isSwimmableLava(above)) return (byte) PathEdits.BROKEN_LAVA;

        // Tier 0b — the flag early-out at the BROKEN cell (cell-centred; committed state only).
        if (!NavFlags.hasFluidNeighbor(ctx.flagsAt(x, y, z))) return (byte) PathEdits.BROKEN;

        // Tiers 1–2 — the lateral funnel, first fluid verdict in LAT (SIX-lateral) order wins.
        for (int i = 0; i < 4; i++) {
            int wx = x + LAT_DX[i], wz = z + LAT_DZ[i];
            long dW = scratchDescriptorAt(wx, y, wz);
            boolean isWater = NavBlock.isSwimmableWater(dW);
            if (!isWater && !NavBlock.isSwimmableLava(dW)) continue; // not a genuine open fluid cell (§8.2)
            if (NavBlock.isFluidMinLevel(dW)) continue;             // one more step yields amount 0
            int fluidCode = NavBlock.fluid(dW);
            boolean eligible;
            if (canFlowDown(wx, y, wz)) {
                // The down-branch is live: lateral spread happens only beside ≥3 same-fluid sources.
                eligible = sourceNeighborCount(wx, y, wz, fluidCode) >= 3;
            } else {
                eligible = NavBlock.isFluidSource(dW) || !isHole(wx, y, wz, fluidCode);
            }
            if (!eligible) continue;
            if (slopeTieAdmits(wx, y, wz, i, SLOPE_FIND, fluidCode, x, z)) {
                return (byte) (isWater ? PathEdits.BROKEN_WATER : PathEdits.BROKEN_LAVA);
            }
        }
        return (byte) PathEdits.BROKEN;
    }

    /**
     * The funnel's read seam: cell {@code (x,y,z)}'s effective descriptor, this candidate's own scratch
     * FIRST — an own-fold break reads as its verdict's constant ({@code AIR_DESC} /
     * {@code WATER_DESC} / {@code LAVA_DESC}, the same constants {@code descriptorAt} resolves the
     * accepted edge to), an own-fold place as {@code PLACED_DESC} — then the {@link PathEdits}-layered
     * {@link MovementContext#descriptorAt}. Linear scans over the tiny per-candidate buffers, reached only
     * on the rare fold path. In-scratch door SETs are deliberately NOT consulted (same as the shipped
     * wet-above read this generalises): a toggled openable is neither fluid nor a seal the funnel's
     * verdicts care about, and the accepted edge's diff resolves it properly downstream.
     */
    private long scratchDescriptorAt(int x, int y, int z) {
        long cell = BlockPos.asLong(x, y, z);
        for (int i = 0; i < breakCount; i++) {
            if (breaks[i] == cell) {
                byte k = breakKind[i];
                if (k == PathEdits.BROKEN_WATER) return MovementContext.WATER_DESC;
                if (k == PathEdits.BROKEN_LAVA) return MovementContext.LAVA_DESC;
                return MovementContext.AIR_DESC;
            }
        }
        for (int i = 0; i < placeCount; i++) {
            if (places[i] == cell) return MovementContext.PLACED_DESC;
        }
        return ctx.descriptorAt(x, y, z);
    }

    /** Vanilla's down-branch gate mirrored (§1.1): the cell below {@code (wx,y,wz)} is genuinely empty —
     *  passable SHAPE holding no fluid — through the diff and this scratch's own folds (the plan's earlier
     *  breaks are what open the path; in practice near-unreachable for the bot, modelled anyway — §5). */
    private boolean canFlowDown(int wx, int y, int wz) {
        long db = scratchDescriptorAt(wx, y - 1, wz);
        return NavBlock.isPassable(db) && NavBlock.fluid(db) == 0;
    }

    /** How many of {@code (wx,y,wz)}'s four lateral neighbours are genuine-open SAME-fluid sources
     *  ({@code sourceNeighborCount} mirrored — the ≥3 near-source test of §1.1's down-branch). */
    private int sourceNeighborCount(int wx, int y, int wz, int fluidCode) {
        int n = 0;
        for (int i = 0; i < 4; i++) {
            long d = scratchDescriptorAt(wx + LAT_DX[i], y, wz + LAT_DZ[i]);
            if (NavBlock.fluid(d) == fluidCode && NavBlock.isPassable(d) && NavBlock.isFluidSource(d)) n++;
        }
        return n;
    }

    /** Vanilla {@code isWaterHole} mirrored, isSame-first (§1.1): the cell below {@code (cx,y,cz)} holds
     *  the SAME fluid, or is open (passable shape) to receive it — read honestly through scratch+diff, so
     *  the broken cell becomes a hole only once the plan itself opened the cell beneath it (§1.5). */
    private boolean isHole(int cx, int y, int cz, int fluidCode) {
        long db = scratchDescriptorAt(cx, y - 1, cz);
        return NavBlock.fluid(db) == fluidCode || NavBlock.isPassable(db);
    }

    /** Vanilla {@code canMaybePassThrough} mirrored (§1.2, geometry reduced to the descriptor's passable
     *  shape — per-face occlusion is §8.2's accepted loss): the cell admits lateral spread flow unless
     *  solid or an already-source of this fluid. The broken cell {@code (bx,·,bz)} reads OPEN
     *  unconditionally — it is the candidate being evaluated (§5; every tier-2 read is at the break's own
     *  {@code y}, so the XZ compare identifies it exactly). */
    private boolean canPassInto(int cx, int y, int cz, int fluidCode, int bx, int bz) {
        if (cx == bx && cz == bz) return true;
        long d = scratchDescriptorAt(cx, y, cz);
        if (!NavBlock.isPassable(d)) return false;
        return !(NavBlock.fluid(d) == fluidCode && NavBlock.isFluidSource(d));
    }

    /**
     * Tier 2's verdict for one eligible fluid neighbour {@code W = (wx,y,wz)}: does the direction from
     * {@code W} toward the broken cell {@code (bx,·,bz)} tie or beat ({@code <=} — ties all win, §1.1) the
     * minimum slope distance over {@code W}'s other candidate directions? {@code dirBtoW} is the LAT index
     * that took the funnel from the break to {@code W}, so {@code dirBtoW ^ 1} is the direction back —
     * which by construction lands exactly on the broken cell.
     */
    private boolean slopeTieAdmits(int wx, int y, int wz, int dirBtoW, int slopeFind, int fluidCode,
                                   int bx, int bz) {
        int backToB = dirBtoW ^ 1;
        int distB = SLOPE_UNFOUND;
        int minOther = SLOPE_UNFOUND;
        for (int i = 0; i < 4; i++) {
            int nx = wx + LAT_DX[i], nz = wz + LAT_DZ[i];
            if (!canPassInto(nx, y, nz, fluidCode, bx, bz)) continue; // excluded from the spread map (§1.2)
            int dist = isHole(nx, y, nz, fluidCode) ? 0
                    : slope(nx, y, nz, 1, i ^ 1, slopeFind, fluidCode, bx, bz);
            if (i == backToB) distB = dist;         // (nx,nz) == (bx,bz) exactly here
            else if (dist < minOther) minOther = dist;
        }
        return distB <= minOther;
    }

    /** Vanilla {@code getSlopeDistance} mirrored over scratch+diff reads: the smallest depth at which a
     *  lateral walk from {@code (cx,y,cz)} (which sits at depth {@code depth}, backtrack {@code backtrack}
     *  excluded) finds a hole, recursing only while {@code depth < slopeFind}; {@link #SLOPE_UNFOUND} when
     *  none. Pure primitives — depth ≤ 4, branching ≤ 3, no allocation, no visited set (§5). */
    private int slope(int cx, int y, int cz, int depth, int backtrack, int slopeFind, int fluidCode,
                      int bx, int bz) {
        int min = SLOPE_UNFOUND;
        for (int i = 0; i < 4; i++) {
            if (i == backtrack) continue;
            int nx = cx + LAT_DX[i], nz = cz + LAT_DZ[i];
            if (!canPassInto(nx, y, nz, fluidCode, bx, bz)) continue;
            if (isHole(nx, y, nz, fluidCode)) return depth; // any deeper find is ≥ depth+1 — this IS the min
            if (depth < slopeFind) {
                int d = slope(nx, y, nz, depth + 1, i ^ 1, slopeFind, fluidCode, bx, bz);
                if (d < min) min = d;
            }
        }
        return min;
    }

    /**
     * The funnel's verdict for a hypothetical break of {@code (x,y,z)} <b>without folding anything</b> —
     * a {@link PathEdits} kind, computed against this scratch's current folds exactly as {@link #addBreak}
     * would compute it. The one caller is {@code MineDown}'s macro jump-sizing (its per-step
     * {@code moveCost} wants level 1's flood verdict before the fold loop runs); public because the
     * movements live in a subpackage. Call on a freshly {@link #reset} scratch (or mid-fold, if the
     * already-folded state is the intended baseline) — the verdict is relative to whatever is in the
     * scratch, like every funnel read.
     */
    public int peekBreakKind(int x, int y, int z) {
        return fluidVerdict(x, y, z);
    }

    /**
     * Whether cell {@code (x,y,z)} reads as swimmable WATER through this candidate's own folds first, then
     * the path diff ({@link #scratchDescriptorAt}) — the read {@code MineDown}'s macro shaft derives each
     * deep level's mining stance from (DESIGN-fluid-flow-prediction.md §7): level {@code k}'s head cell is
     * a known earlier-folded cell (level {@code k−2}'s break — whose {@code BROKEN_WATER} kind chains the
     * flood down the shaft) or the original mouth, and water there is exactly vanilla's eye-under-water
     * 5×. Water only, matching {@code breakStanceMult} — a lava-flooded head does not submerge the eye
     * (vanilla tests {@code FluidTags.WATER}); the lava is priced through the cell's damage/transit facts
     * instead. Public because the movements live in a subpackage.
     */
    public boolean readsWater(int x, int y, int z) {
        return NavBlock.isSwimmableWater(scratchDescriptorAt(x, y, z));
    }

    /**
     * Whether cell {@code (x,y,z)} reads as swimmable LAVA through this candidate's own folds first, then
     * the path diff ({@link #scratchDescriptorAt}) — the lava sibling of {@link #readsWater}, read by
     * {@code MineDown}'s per-level <b>lava-exposure</b> term (DESIGN-fluid-flow-prediction.md §6, the
     * 2026-08-17 adversarial-review correction): a dig level whose FEET cell reads lava — the mouth pool
     * for level 1, or an earlier level's {@code BROKEN_LAVA} fold chained down the shaft — burns the bot
     * for that level's whole mining duration, priced via
     * {@link MovementContext#lavaExposureCost}. Deliberately NOT folded into the mining-stance multiplier:
     * lava does not submerge the eye (vanilla tests {@code FluidTags.WATER}), so the stance stays 1× and
     * the exposure is a separate damage term in the one {@code costPerHitpoint} currency. Public because
     * the movements live in a subpackage.
     */
    public boolean readsLava(int x, int y, int z) {
        return NavBlock.isSwimmableLava(scratchDescriptorAt(x, y, z));
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
        if (ctx.placeable(x, y, z, d) && addPlace(x, y, z)) return;
        valid = false; // no footing, and either the bot can't place or placing here drops gravity on us
    }

    /**
     * {@link #requireFloor} grown the trapdoor arm (DESIGN-trapdoors.md §5): a dest floor cell that is not
     * standable but is a toggleable OPEN trapdoor ({@link MovementContext#trapdoorSetFloors} — {@code
     * doors.toggle} on, not iron) folds a {@code SET_CLOSED} — the closed state (3/16 plate for a BOTTOM
     * half, flush 16/16 hatch for a TOP half) is standable by construction — at the same single {@link
     * MovementContext#DOOR_TOGGLE_COST}. Ordered standable → toggle → place, so every non-open-trapdoor
     * cell behaves bit-identically to {@code requireFloor} (Stage 3 movements switch their dest-floor call
     * sites over without behavior change on trapdoor-free worlds). Like the crossing SET folds — and unlike
     * the break/place folds — the toggle deliberately bypasses the {@code RISKS_GRAVITY} edit gate. The
     * sharper reason (not merely the door-symmetric precedent): toggling a door / trapdoor / fence gate
     * changes no cell's occupancy for SUPPORT purposes, so it cannot drop a gravity block.
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
        if (ctx.placeable(x, y, z, d) && addPlace(x, y, z)) {
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
        // The FOOTING cell is pre-checked (rather than left to addPlace's own gate) so that a refused
        // footing can never leave a stray SUPPORT folded on the two-placement arm below.
        if (!mayEdit(fx, fy, fz)) { valid = false; return; }
        if (ctx.placeable(fx, fy, fz, fd)) { // footing already has a face — one placement
            addPlace(fx, fy, fz);
            return;
        }
        // No face of the footing's own: place a support beneath it, then the footing rests on it. The
        // support's face is the floor the bot stands on, which reads solid via the PathEdits diff (real
        // terrain or a preceding step's block), so a plain placeable() check finds it — staircase chains.
        long sd = ctx.descriptorAt(sx, sy, sz);
        // The SUPPORT cell carries its own gate through addPlace — closing the last ungated place in the
        // codebase (its flags were never read by any frame under the old floor-framed bit).
        if (ctx.openForPlace(fd) && ctx.placeable(sx, sy, sz, sd) && addPlace(sx, sy, sz)) {
            addPlace(fx, fy, fz);
        } else {
            valid = false;
        }
    }

    /**
     * Record a place of cell {@code (x,y,z)} and charge its cost. The second half of the fold-sited edit
     * gate ({@link #mayEdit}): a PLACE is a block update at the cell too, so RISKS_GRAVITY rule (b) — an
     * adjacent UNSUPPORTED gravity block that any neighbouring update pokes loose — applies identically.
     * Rule (a) can never refuse a legal place: a cell that supports a gravity block is solid, hence not
     * {@code placeable}. Returns whether the place was folded ({@code false} = refused by the gate).
     */
    private boolean addPlace(int x, int y, int z) {
        if (!mayEdit(x, y, z)) return false;
        places = push(places, placeCount, x, y, z);
        placeCount++;
        extraCost += ctx.placeCost(x, y, z); // real ticks-to-place (+ inventory premium when consuming) — 1d
        return true;
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

    /**
     * Fold the {@code SET_OPEN} that turns a CLOSED trapdoor mouth into a climbable ladder extension
     * (DESIGN-trapdoor-ladder-climb.md §4) — {@code Climb}'s two mouth arms (the from-below continue and
     * the rim top-entry). The caller has already proven the toggle offered AND climb-enabling via
     * {@link MovementContext#trapdoorMouthOpensForClimb} (hand-toggleable + facing equality against the
     * ladder below — a facing-mismatched mouth must never fold: opened, it would extend nothing and the
     * climb premise would be false). Public, unlike the package-private {@link #setTrapdoor}, because Climb
     * reads its cells through {@code packedAt}/{@code descriptorOf} (its UNBUILT discipline) and so cannot
     * ride a self-verdicting {@code require*} that re-reads via {@code descriptorAt}. Rides the
     * kind-agnostic doors[] channel and charges one {@link MovementContext#DOOR_TOGGLE_COST}, exactly like
     * every other single-cell trapdoor SET; like all SET folds it bypasses the {@code RISKS_GRAVITY} edit
     * gate (a toggle changes no cell's occupancy for support purposes — see
     * {@link #requireFloorOrToggle}).
     */
    public void openClimbMouth(int x, int y, int z) {
        setTrapdoor(x, y, z, true);
    }

    /**
     * Fold an OPEN/CLOSE of the (hand-toggleable) fence gate at cell {@code (x,y,z)} to {@code targetOpen}
     * (DESIGN-fence-gates.md §3) — the gate twin of {@link #setTrapdoor}, riding the SAME kind-agnostic
     * doors[] channel ({@code descriptorAt} resolves through the unified {@link NavBlock#withOpenableOpen}).
     * A gate is ONE cell, so it never participates in the per-door two-half dedup — each gate SET charges
     * its own {@link MovementContext#DOOR_TOGGLE_COST} (re-folding the exact same cell within one candidate
     * stays a no-op). The caller has already proven the toggle offered
     * ({@link MovementContext#gateSetClears}).
     */
    void setGate(int x, int y, int z, boolean targetOpen) {
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
     *
     * @return whether the break was folded; {@code false} = the fold-sited gravity gate refused it, and the
     *         caller must charge the intact-transit price instead (never invalidate — transiting is legal)
     */
    boolean breakThrough(int x, int y, int z, float cost) {
        if (!addBreak(x, y, z)) return false;
        extraCost += cost;
        return true;
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
     * {@link #reset()} clears the slot between candidates, so there is no accumulation to reconcile.
     */
    public void setClutch(int kind, int x, int y, int z) {
        clutchKind = kind;
        clutchCell = BlockPos.asLong(x, y, z);
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
        e.load(breaks, breakKind, breakCount, places, placeCount, doors, doorOpens, doorCount,
               clutchKind, clutchCell);
    }

    private static long[] push(long[] buf, int count, int x, int y, int z) {
        if (count == buf.length) buf = Arrays.copyOf(buf, buf.length * 2);
        buf[count] = BlockPos.asLong(x, y, z);
        return buf;
    }
}
