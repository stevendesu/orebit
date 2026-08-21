package com.orebit.mod.worldmodel.navblock;

import java.util.HashMap;
import java.util.Map;

import com.orebit.mod.OrebitCommon;
import com.orebit.mod.platform.BlockKinds;
import com.orebit.mod.platform.BlockLookup;
import com.orebit.mod.platform.MineableTags;
import com.orebit.mod.platform.Replaceable;

import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.PointedDripstoneBlock;
import net.minecraft.world.level.block.SnowLayerBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Per-{@link BlockState} navigation fingerprint, stored as a packed 64-bit {@code long}.
 *
 * <h2>The model (PRD §6.1, decision #1)</h2>
 * Identity is <b>per-BlockState, not per-Block</b>: a north-facing stair and a south-facing
 * stair navigate differently, and that difference is intrinsic to the state (different solid
 * faces, different shape), so each state computes its own fingerprint and behaviourally-equal
 * states dedup losslessly. Because the fingerprint is a single packed {@code long}, the
 * descriptor table maps a {@code short} <b>navtype</b> index → that {@code long}; one array read
 * plus bit-extraction yields every field, with no objects and no pointer chasing on the hot path.
 *
 * <p><b>The packed {@code long} is simultaneously the dedup key and the descriptor</b> — two
 * states that pack to the same bits are the same navtype. ~28k block states collapse to a few hundred
 * navtypes; the table is a few KB and L1-resident.
 *
 * <p><b>The only number worth writing down is the CAP: 1024.</b> {@code TraversalGrid} packs the navtype
 * into <b>10 bits</b> of its per-cell {@code short}, so 1024 is a hard structural limit — exceeding it
 * would truncate indices and silently corrupt the grid. {@code intern} itself does NOT enforce the cap
 * (it silently doubles its table past 1024); the alarms live where truncation would bite — {@code
 * NavSectionBuilder}'s static-init check against {@code TraversalGrid.NAVTYPE_CAPACITY}, and {@link
 * #applyProtected}'s post-split re-check — and both are {@code LOGGER.error}-only, neither throws.
 * The live count sits in the low hundreds (400–600 territory) but is <b>deliberately not pinned here</b>:
 * it moves with the Minecraft version (new blocks, new state properties), with any new discriminator added
 * to {@link #fingerprint}, and at runtime with {@link #applyProtected} (each protected entry SPLITS the
 * navtypes its states occupied). Three different docs once quoted three different exact values, all stale.
 *
 * <p><b>To get the real number, read the log</b> — static init prints it on every start:
 * {@code [Orebit] NavBlock: <states> states -> <navtypes> navtypes (<bytes> B table, <n> errors)}.
 *
 * <p><b>Take that number from a REAL GAME, never from the headless harness — the harness UNDERCOUNTS.</b>
 * Measured 2026-08-07 on MC 1.21.11, same 29,671 states both ways: <b>live server 433</b>, headless test
 * <b>396</b>. The cause is {@link #bestTool}, whose result is a BASE field (the {@code tool} ordinal at bits
 * 32–34, so it splits navtypes): it classifies via {@code BlockTags.MINEABLE_WITH_*}, and a headless
 * {@code Bootstrap.bootStrap()} has no datapack tag manager, so every tag test returns false and the whole
 * registry collapses onto one {@code Tool} value. A live server loads the tags and the field fans back out.
 * Any navtype census from JMH/unit-test output is therefore a floor, not the number.
 *
 * <p><b>And it MOVES — a lot — even holding the Minecraft version fixed.</b> Reconstructed from the run logs,
 * all at 29,671 states (MC 1.21.11): <b>530</b> (2026-06-23) → <b>271</b> (2026-07-10, roughly halved by a
 * classification change) → 355 → 357 → 361 → 408 → <b>433</b> (2026-08-07). A ~2× swing inside six weeks on
 * one MC version, because every added or merged discriminator moves it. This is exactly why no exact figure
 * belongs in prose: three separate docs once quoted 503, ~587 and ~250, and all three were simply snapshots
 * of different weeks. <b>Quote the cap, estimate the count, and read the log when you need the truth.</b>
 *
 * <h2>What changed from the deprecated byte scheme</h2>
 * The old design keyed per-Block and packed a {@code (mode<<6)|counter} <b>byte</b> index, which
 * overflowed on several versions ("Too many blocks registered for mode 3") and forced lossy
 * lumping. It also bolted block <i>facing</i> onto the index via {@code registerDirection} —
 * needed only because identity was per-Block. Per-BlockState identity makes facing fall out of
 * the geometry fields for free, so {@code directional} and the direction machinery are gone.
 * Light emission is <b>not</b> stored here (it has up to 16 values per otherwise-identical block,
 * would inflate the navtype count ~15×, and does not affect traversal — it belongs in region
 * metadata for target selection). "Currently waterlogged" is not a separate field: a waterlogged
 * state reports water from {@link BlockState#getFluidState()}, so it is captured by the fluid field.
 *
 * <h2>Bit layout</h2>
 * <pre>
 *   bits  width field
 *   0–4     5   topY        collision top surface, round(maxY*16), clamped 0..31
 *   5–7     3   shape       ShapeClass ordinal (EMPTY..OTHER) — see {@link #SHAPE_FULL} etc.
 *   8–9     2   horizFacing SHARED horizontal FACING (0=N 1=E 2=S 3=W); 0 for cells with no facing. Populated for
 *                           {@link StairBlock} states (via {@link #stairFacing}), {@link DoorBlock} states (via
 *                           {@link #doorFacing}), {@link TrapDoorBlock} states (via {@link #trapdoorFacing}) AND
 *                           {@link LadderBlock} states (via {@link #ladderFacing}) — a cell is a stair XOR a
 *                           door XOR a trapdoor XOR a ladder (disambiguated by {@link #isStair} / {@link #isDoor}
 *                           / {@link #isTrapdoor} / {@link #isLadder}), so the field is reused (DESIGN-trapdoors.md
 *                           §2, DESIGN-trapdoor-ladder-climb.md §2 — zero new bits). On a BOTTOM stair the HIGH
 *                           16/16 half is on the FACING side, the LOW 8/16 front opposite (empirically verified,
 *                           StairVoxelProbe), so a walk-up reads as a +0.5 step-assist rather than a +1.0 jump.
 *                           Ladder facing feeds ONLY the vanilla trapdoor-over-ladder climb rule (equal-facing
 *                           test); vines/scaffolding do NOT pack facing (the vanilla rule is ladder-identity-only).
 *   10      1   half        SHARED HALF bit (1 = TOP): {@link StairBlock} HALF (via {@link #stairHalf}) XOR
 *                           {@link TrapDoorBlock} HALF (via {@link #trapdoorHalfTop}); 0 for everything else
 *                           (incl. doors and slabs — a door does NOT set this bit; slab-ness is the
 *                           SHAPE_SLAB_* classes). A TOP stair's top surface is flat 16/16 everywhere, so it
 *                           needs no directional handling; a trapdoor's HALF picks its CLOSED plate position
 *                           (BOTTOM = 3/16 floor plate, TOP = plate flush with the cell top).
 *   11      1   portal      ANY teleport portal — LOW bit of a 2-bit field (mirrors {@code fluid}: low = is-portal,
 *                           high = is-nether). Base identity field, NOT derived. The walker routes AROUND any set cell.
 *   12      1   netherPortal HIGH bit of the portal field: 00 none / 01 end (end_portal + end_gateway) / 11 nether / 10 unused.
 *   13      1   doorHinge   {@link DoorBlock} DOOR_HINGE (1 = RIGHT, 0 = LEFT); 0 for non-doors. Colocated with
 *                           the geometry cluster; consumed only by {@link #doorBlockedEdge} to pick the OPEN door's
 *                           perpendicular blocked edge. (Formerly the remainder of the reclaimed sturdy-faces mask.)
 *   14–15   2   openable    0 none / 1 door / 2 trapdoor / 3 fence-gate
 *   16–17   2   fluid       00 none / 01 water / 11 lava (low bit = is-fluid, high = is-lava; water incl. waterlogged)
 *   18–19   2   surface     0 none / 1 slow (2–3 UNUSED). Prices standing ON a slow FLOOR (soul sand /
 *                           honey, {@code Block.getSpeedFactor() < 1}); read only by {@code
 *                           MovementContext.isSlow}. A SLIPPERY value (2) existed until 2026-08-10 and was
 *                           removed as dead: nothing in the planner ever read it. Slipperiness IS used, but
 *                           by the FOLLOWER, which reads the CONTINUOUS vanilla friction live off the level
 *                           ({@code BotSteering.slipperinessAt} → {@code Block.getFriction()}, 0.6 / 0.98 /
 *                           0.989) — a servo gain, not a class, so it can never consume a grid bit. The field
 *                           stays 2 BITS ON PURPOSE: narrowing it would either leave a hole at bit 19 or
 *                           renumber every field above it, and freeing descriptor bits is not scarce here
 *                           (56–63 are free). Keeping the width makes this an assignment-only change with a
 *                           byte-identical layout.
 *   20      1   climbable
 *   21      1   gravity     falling block (sand/gravel/concrete-powder)
 *   22      1   damaging
 *   23      1   replaceable
 *   24–31   8   hardness    255 = unbreakable, else min(254, round(destroyTime*5))
 *   32–34   3   tool        Tool ordinal (NONE..SHEARS)
 *   35      1   toolRequired
 *   36      1   waterloggable (static: has the WATERLOGGED property — bucket-clutch fails)
 *   37–40   4   precomputed predicate bits (standable / breakable / open-for-place / collision) — see {@link #withDerived}
 *   41–42   2   transit     through-slow class for PASSABLE cells the body moves through:
 *                           0 none / 1 light (sweet berry bush, powder snow ~0.75×) / 2 heavy (cobweb ~0.05×).
 *                           Distinct from the {@code surface} slow field (bits 18–19), which prices standing
 *                           ON a slow floor (soul sand / honey); this prices moving THROUGH a slowing cell.
 *   43      1   open        SHARED OPEN bit (1 = open, 0 = closed): {@link DoorBlock} OPEN (via {@link #doorOpen})
 *                           XOR {@link TrapDoorBlock} OPEN (via {@link #trapdoorOpen}) XOR {@link FenceGateBlock}
 *                           OPEN (via {@link #gateOpen}); 0 for non-openables.
 *                           Colocated with the movement-impact cluster; consumed by {@link #doorBlockedEdge}
 *                           (open ⇒ perpendicular edge, closed ⇒ opposite edge) and {@link #trapdoorBlockedFace}
 *                           (closed ⇒ UP/DOWN per half, open ⇒ the cardinal opposite facing). (Formerly the
 *                           nether-portal bit — the portal field moved down to bits 11–12, widened to a 2-bit
 *                           any-portal / is-nether field.)
 *   44      1   protected   owner-protected block ({@code mining.protectedBlocks}) — the bot must NEVER
 *                           break it. A base identity field applied AFTER static-init by {@link
 *                           #applyProtected} (the list needs the loaded config + bound datapack tags), so
 *                           it SPLITS navtypes (a protected chest is a different navtype from an identical
 *                           unprotected one — deliberate: navtypes conflate blocks, so protected-ness must
 *                           be part of the fingerprint for the planner to see it). The derived BREAKABLE
 *                           bit excludes it, so every planner break gate refuses in one bit test.
 *   45      1   reducedJump a floor whose {@code Block.getJumpFactor()} is below 1.0 (honey block, 0.5 —
 *                           the only vanilla case): standing on it caps the jump apex at ~0.384 blocks, so
 *                           the jump-takeoff movements (Ascend / Pillar / Parkour / DiagonalParkour) refuse
 *                           to launch from it. A base identity field (like {@code surface}/{@code portal}),
 *                           read as one mask-and-test. Distinct from the {@code surface} SLOW field (soul
 *                           sand / honey slow WALK speed, priced as a floor multiplier) — this is JUMP power.
 *   46–47   2   bubble      bubble-column interior cell + its DRAG direction: 0 none / 1 up (soul-sand column,
 *                           {@code DRAG_DOWN=false}, pushes entities up) / 2 down (magma column,
 *                           {@code DRAG_DOWN=true}, drags entities down). A base identity field (like {@code
 *                           portal}), detected from {@link net.minecraft.world.level.block.BubbleColumnBlock}
 *                           at classification. A bubble column is water fluid + empty shape, so it WOULD read
 *                           as swimmable — but the column's vertical push overrides swim depth control, so
 *                           {@link #isSwimmableWater} now EXCLUDES bubbles (impassable → routed around). The
 *                           direction is reserved for a future RideBubbleColumn move (ride the push up/down).
 *   48–49   2   fallSoftness the LANDING block's fall-damage class — how much fall damage it absorbs when a
 *                           bot lands on / falls into it: 00 full (×1.0, ordinary ground) / 01 half (×0.5,
 *                           beds) / 10 fifth (×0.2, hay bale / honey block) / 11 zero (×0.0, slime, water,
 *                           powder snow, sweet berry bush, cobweb, bubble column). Classified by a curated
 *                           block-identity/instanceof map (no single vanilla getter exists — the multiplier is
 *                           applied inside per-block {@code fallOn} overrides / fall-distance resets) and
 *                           rounded UP conservatively toward MORE damage. The {@code Fall} movement multiplies
 *                           its excess-fall damage budget + cost by {1.0,0.5,0.2,0.0} (00 = unchanged; 11 =
 *                           uncapped). A base identity field (like {@code portal}/{@code bubble}), one
 *                           mask-and-shift on the hot path.
 *   50      1   handToggleable a GENERIC hand-toggleable openable — one the bot can OPEN/CLOSE BY HAND vs a
 *                           redstone-only iron one. Set for every {@link DoorBlock} except {@link
 *                           Blocks#IRON_DOOR} (DOORS P2), every {@link TrapDoorBlock} except {@link
 *                           Blocks#IRON_TRAPDOOR} (DESIGN-trapdoors.md §2) — the {@code Blocks.*} idiom of the
 *                           door/portal/bubble reads (range-stable, no platform seam; copper doors AND copper
 *                           trapdoors are non-iron and hand-openable, so the test needs no version gate —
 *                           pre-copper the non-iron set is simply the wood species) — and every {@link
 *                           FenceGateBlock} UNCONDITIONALLY (DESIGN-fence-gates.md §1: no iron gate exists at
 *                           any version). Kind discrimination is the {@code openable} field (14–15), NOT this
 *                           bit. Meaningful only when {@link #isDoor} / {@link #isTrapdoor} / {@link #isGate}.
 *                           Consumed by the planner's prefer-open-over-smash SET fold (an iron door/trapdoor
 *                           has no SET option → break/route as in P1).
 *   51      1   narrowTop   the collision footprint is a NARROW POST — either XZ extent of the shape's
 *                           bounding box under the bot's own 0.6-block body width (bamboo ~3px with a
 *                           position-hash offset, chain 3px, end/lightning rod 4px, pointed dripstone,
 *                           narrow décor). Derived from collision GEOMETRY at classification (no block
 *                           lists), except the force-solid pair (bamboo/dripstone), whose null-context
 *                           shape query NPEs and which the same identity knowledge marks narrow. Such a
 *                           cell stays STANDABLE (a real floor for walking/grid/region purposes) but
 *                           precision-LANDING moves (Parkour/DiagonalParkour) refuse it as a target —
 *                           landing square on a post the bot's whole body overhangs is shaolin parkour a
 *                           human can't reasonably follow (owner ruling 2026-07-31). A base identity
 *                           field.
 *   52      1   fluidSource the cell's {@link FluidState} is a SOURCE ({@code isSource()}, amount 8) — always
 *                           lateral-spread-eligible, the eligibility fact the flood funnel's tier 1 reads per
 *                           fluid neighbour (DESIGN-fluid-flow-prediction.md §1.1/§3). Set for waterlogged
 *                           states too (their fluid state IS a water source — see {@link #isFluidSource}), so
 *                           the bit does NOT imply an open water cell. A base identity field: splits water and
 *                           lava navtypes into source vs flowing variants.
 *  53-55    3   fluidLevel  the fluid's AMOUNT, stored as {@code amount - 1} so the eight legal amounts
 *                           (1–8) fit exactly in three bits; {@link #fluidLevel} decodes it back to 1–8 and
 *                           answers 0 for a non-fluid cell. Superseded the single fluidMinLevel bit on
 *                           2026-08-20 (owner-ratified): the minimum-level fact is now the derived predicate
 *                           {@code fluidLevel(d) == 1}, and the exact amount is what the eye-submersion test
 *                           needs — vanilla starts a sprint-swim only when the EYES are in fluid, and a
 *                           partial flow block in the head cell can sit below eye height (the noodle-cave
 *                           wedge, 2026-08-20). Keeping the full amount rather than one more threshold bit
 *                           means the next movement to need a level does not cost another descriptor bit.
 *                           <p>Amount is a per-blockstate fact; the SURFACE height vanilla actually tests is
 *                           neighbour-dependent ({@code FlowingFluid.getHeight} returns 1.0 when the same
 *                           fluid sits directly above, else {@code amount / 9F}), so a consumer that needs
 *                           the true height must ALSO look at the cell above — the descriptor cannot carry
 *                           that. It is flat across the cell: the sloped surface is client-side rendering
 *                           ({@code LiquidBlockRenderer}) and never reaches server physics.
 *                           <p>Deliberately fluid-agnostic, as fluidMinLevel was: exact for water and nether
 *                           lava (dropOff 1), merely conservative for overworld lava (errs wet, the safe
 *                           direction). Do NOT make it dimension-aware; the descriptor is interned globally,
 *                           not per-dimension (DESIGN-fluid-flow-prediction.md §3). A base identity field:
 *                           it splits water/lava navtypes per amount. Bits 56–63 remain wholly unused.
 * </pre>
 */
public final class NavBlock {

    private NavBlock() {}

    // ---- ShapeClass ordinals (3 bits) --------------------------------------------------------
    /** No collision (air, plants, fluids) — passable. */
    public static final int SHAPE_EMPTY       = 0;
    /** Full cube — wall / floor. */
    public static final int SHAPE_FULL        = 1;
    /** Solid lower half (bottom slab) — walk-on step-up, no jump. */
    public static final int SHAPE_SLAB_BOTTOM = 2;
    /** Solid upper half (top slab) — stand at 1.0; eats headroom of the cell below. */
    public static final int SHAPE_SLAB_TOP    = 3;
    /** Stairs — half-step approach on the open side, full block on the back; facing via faces. */
    public static final int SHAPE_STAIR       = 4;
    /** Variable-height layer (snow/carpet) — step-up if {@code topY} small. */
    public static final int SHAPE_LAYER       = 5;
    /** Misc partial collision with top ≤ 0.5 — walk-on step-up. */
    public static final int SHAPE_PARTIAL_LOW = 6;
    /** Anything else (fence/wall/pane, oversized or irregular) — treat as obstacle. */
    public static final int SHAPE_OTHER       = 7;

    // ---- Tool (3 bits) -----------------------------------------------------------------------
    /** Best tool for breaking; ordinal is stored in the descriptor. */
    public enum Tool { NONE, PICKAXE, AXE, SHOVEL, HOE, SWORD, SHEARS }

    // Fluid encoding: low bit = "is a fluid", high bit = "is lava". So none=00, water=01, lava=11 —
    // and "is fluid" is the low bit alone (no OR of two values), "is lava" the high bit alone.
    private static final int FLUID_NONE = 0, FLUID_WATER = 1, FLUID_LAVA = 3;
    // Surface: the only live value is SLOW (a floor you walk slowly across). Encodings 2–3 of the 2-bit
    // field are unused — SURFACE_SLIPPERY was removed 2026-08-10 as dead (no reader; see the bit-layout
    // note above). The field keeps its 2-bit width so no other field's shift moves.
    private static final int SURFACE_NONE = 0, SURFACE_SLOW = 1;
    private static final int OPEN_NONE = 0, OPEN_DOOR = 1, OPEN_TRAPDOOR = 2, OPEN_GATE = 3;

    // ---- Transit-slow class (2 bits, 41–42): moving THROUGH a passable cell is slowed ---------
    /** No through-slow: the cell doesn't impede a body moving through it. */
    public static final int TRANSIT_NONE  = 0;
    /** Mild through-slow (~0.75× speed): sweet berry bush, powder snow. */
    public static final int TRANSIT_LIGHT = 1;
    /** Severe through-slow (~0.05× speed): cobweb — near-stops the body, planner should route/mine around. */
    public static final int TRANSIT_HEAVY = 2;
    /**
     * Fluid through-slow (~0.4× speed): LAVA. The fourth encoding of the field, claimed 2026-08-07 so the
     * descriptor states lava's slowness as DATA rather than leaving it implicit in a movement-side constant
     * (owner: <i>"set the slow bit on lava so that we don't need anything special to handle that"</i>).
     * The {@code 0.4} is the reciprocal of the ratified {@code MovementContext.LAVA_SWIM_COST_FACTOR = 2.5}
     * (s52b, from lava's ~0.02 vs water's ~0.05 travel acceleration) — the same number, now stated once.
     *
     * <p><b>This bit alone does not retire {@code lavaSwimCellCost}</b>, and it is worth being precise about
     * why: the transit constants are flat tick SURCHARGES derived from the walk cost, while a swim rung needs
     * a MULTIPLIER on whichever swim rate applies. Setting the class makes the fact available to every
     * consumer (and correctly prices a ground move's body cells through lava, which previously charged damage
     * but no slowness at all); converting the swim path to consume it is the cost-model arc that also makes
     * the damage charge dwell-scaled. See NOTES-vanilla-fluid-physics.md §5.
     */
    public static final int TRANSIT_FLUID = 3;

    // ---- Bubble-column class (2 bits, 46–47): a bubble column + its DRAG direction --------------
    /** Not a bubble column. */
    public static final int BUBBLE_NONE = 0;
    /** Upward bubble column (soul-sand floor, {@code DRAG_DOWN=false}) — pushes entities up. */
    public static final int BUBBLE_UP   = 1;
    /** Downward bubble column (magma floor, {@code DRAG_DOWN=true}) — drags entities down. */
    public static final int BUBBLE_DOWN = 2;

    // ---- Fall-softness class (2 bits, 48–49): the LANDING block's fall-damage multiplier -------
    /** Full fall damage (multiplier 1.0) — ordinary ground (stone, dirt, …). */
    public static final int FALLSOFT_NONE  = 0;
    /** Half fall damage (multiplier 0.5) — beds. */
    public static final int FALLSOFT_HALF  = 1;
    /** One-fifth fall damage (multiplier 0.2) — hay bale, honey block. */
    public static final int FALLSOFT_FIFTH = 2;
    /** Zero fall damage (multiplier 0.0) — slime; and the fall-distance-reset media (water, powder snow,
     *  sweet berry bush, cobweb, bubble column). */
    public static final int FALLSOFT_ZERO  = 3;

    // ---- Bit field shifts/masks --------------------------------------------------------------
    // Bits 8–10 hold the shared facing (2) + half (1) fields, populated for StairBlock, DoorBlock (facing
    // only) and TrapDoorBlock states (0 elsewhere, so only those families split navtypes); bits 11–12 hold
    // the 2-bit portal field (any-portal / is-nether, see below); bits 13 and 43 were freed when the portal
    // marker moved down into 11–12, and were then CLAIMED by the door encoding below (13 = hinge, 43 = open)
    // — this comment claimed both were still free long after they were not, so do not read it as a free list.
    // THE FREE BITS ARE 56–63; the highest bit in use is the top of FLUID_LEVEL (53–55). The fluid-flow arc
    // claimed 52–53 (FLUID_SOURCE / FLUID_MIN_LEVEL, DESIGN-fluid-flow-prediction.md §3, owner-ratified
    // 2026-08-17); the eye-submersion fix then WIDENED 53 into the 3-bit FLUID_LEVEL field (owner-ratified
    // 2026-08-20), leaving FLUID_SOURCE at 52 untouched so every existing sourcehood reader is unaffected.
    // The trapdoor arc added ZERO bits:
    // facing/half/open/hand-toggleable are all SHARED fields discriminated by the openable kind (14–15) —
    // DESIGN-trapdoors.md §2, owner-ratified 2026-08-08. Adding a descriptor bit needs explicit owner
    // sign-off (retroactive ruling after handToggleable@50).
    private static final int TOP_Y_SHIFT = 0,  TOP_Y_MASK = 0x1F;
    private static final int SHAPE_SHIFT = 5,  SHAPE_MASK = 0x07;
    private static final int STAIR_FACING_SHIFT = 8, STAIR_FACING_MASK = 0x03; // 0=N 1=E 2=S 3=W (stairs AND doors)
    private static final long STAIR_HALF_BIT = 1L << 10;                       // 0=bottom 1=top (stairs only)
    // Doors reuse the bits-8–9 facing field (a cell is stair XOR door). Hinge (bit 13) + open (bit 43) complete
    // the directional-solidity encoding; the blocked edge is DERIVED at query time (see #doorBlockedEdge), never
    // precomputed. DOOR_FACING_SHIFT/_MASK alias the stair facing field deliberately (same 2-bit HORIZONTAL_FACING).
    private static final int DOOR_FACING_SHIFT = STAIR_FACING_SHIFT, DOOR_FACING_MASK = STAIR_FACING_MASK;
    private static final long DOOR_HINGE_BIT = 1L << 13; // 1 = RIGHT hinge, 0 = LEFT (doors only)
    private static final long DOOR_OPEN_BIT  = 1L << 43; // 1 = open, 0 = closed (doors XOR trapdoors)
    // Trapdoors reuse the shared fields too (DESIGN-trapdoors.md §2 — ZERO new bits): facing rides bits 8–9,
    // HALF rides bit 10 (1 = TOP), OPEN rides bit 43. A cell is stair XOR door XOR trapdoor (the openable kind
    // at 14–15 disambiguates), so no read ever sees another family's packing. The blocked FACE is DERIVED at
    // query time (see #trapdoorBlockedFace), never stored — the doorBlockedEdge discipline.
    private static final int TRAPDOOR_FACING_SHIFT = STAIR_FACING_SHIFT, TRAPDOOR_FACING_MASK = STAIR_FACING_MASK;
    private static final long TRAPDOOR_HALF_BIT = STAIR_HALF_BIT; // 1 = TOP half (trapdoors; stairs read via stairHalf)
    private static final long TRAPDOOR_OPEN_BIT = DOOR_OPEN_BIT;  // 1 = open (trapdoors; doors read via doorOpen)
    // Fence gates reuse the shared fields too (DESIGN-fence-gates.md §2 — ZERO new bits): OPEN rides bit 43,
    // hand-toggleable bit 50 (set unconditionally — no iron gate exists at any version). FACING is deliberately
    // NOT packed: it is behaviorally void for gates (open collision is Shapes.empty() regardless of facing;
    // closed blocks every crossing regardless of axis), and packing it would 4x the gate navtypes for zero
    // consumers. POWERED/IN_WALL are likewise unread (IN_WALL is collision-invariant — outline/occlusion only),
    // so those states collapse in dedup.
    private static final long GATE_OPEN_BIT = DOOR_OPEN_BIT;      // 1 = open (gates; doors/trapdoors read via doorOpen/trapdoorOpen)
    // Ladders reuse the shared facing field (DESIGN-trapdoor-ladder-climb.md §2 — ZERO new bits): a cell is
    // stair XOR door XOR trapdoor XOR ladder, discriminated for ladders by CLIMB ∧ SHAPE_OTHER (see #isLadder —
    // uniqueness over the interned table is a classifier invariant, pinned by test). Fans the ladder navtypes
    // 1 → 4 (× waterlogged); consumed only by the equal-facing half of the vanilla trapdoor-over-ladder rule.
    private static final int LADDER_FACING_SHIFT = STAIR_FACING_SHIFT, LADDER_FACING_MASK = STAIR_FACING_MASK;
    private static final int OPEN_SHIFT  = 14, OPEN_MASK  = 0x03;
    private static final int FLUID_SHIFT = 16, FLUID_MASK = 0x03;
    private static final int SURF_SHIFT  = 18, SURF_MASK  = 0x03;
    private static final int CLIMB_BIT   = 1 << 20;
    private static final int GRAVITY_BIT = 1 << 21;
    private static final int DAMAGE_BIT  = 1 << 22;
    private static final int REPLACE_BIT = 1 << 23;
    private static final int HARD_SHIFT  = 24, HARD_MASK  = 0xFF;
    private static final int TOOL_SHIFT  = 32, TOOL_MASK  = 0x07;
    private static final long TOOLREQ_BIT  = 1L << 35;
    private static final long WLOGABLE_BIT = 1L << 36;
    // Portal is a 2-bit field mirroring FLUID (low = "any teleport portal", high = "is nether portal"): 00 none /
    // 01 end (end_portal + end_gateway) / 11 nether / 10 unused. The LOW bit is what the walker's passability gate
    // subtracts (route around ALL portals); the HIGH bit is what the nether portal index / follower read (enter
    // nether portals deliberately, never chase an end portal). Base identity fields, NOT derived.
    private static final long PORTAL_BIT        = 1L << 11;           // any teleport portal (walker-avoidance low bit)
    private static final long NETHER_PORTAL_BIT = 1L << 12;           // is-nether-portal (index/follower high bit)
    private static final int TRANSIT_SHIFT = 41, TRANSIT_MASK = 0x03; // bits 37–40 are the derived predicates
    private static final long PROTECTED_BIT = 1L << 44;               // owner-protected: never break (base field)
    private static final long REDUCED_JUMP_BIT = 1L << 45;            // reduced-jump floor: honey (base field)
    private static final int BUBBLE_SHIFT = 46, BUBBLE_MASK = 0x03;   // bubble column + drag dir (base field)
    private static final int FALLSOFT_SHIFT = 48, FALLSOFT_MASK = 0x03; // landing fall-damage class (base field)
    private static final long HAND_TOGGLEABLE_BIT = 1L << 50;          // hand-openable door/trapdoor, not iron
    private static final long NARROW_TOP_BIT = 1L << 51;                // narrow-post collision top (base field)
    // Fluid facts (DESIGN-fluid-flow-prediction.md §3, owner-ratified 2026-08-17; widened 2026-08-20): the
    // per-blockstate facts the 2-bit fluid field cannot express — sourcehood and the exact amount. Base
    // identity fields (they SPLIT water/lava navtypes per source/amount), read by the flood funnel's tier 1
    // at candidate-emission time and by the swim moves' eye-submersion test.
    //
    // Sourcehood is kept as its OWN bit rather than folded into the level field because it is not derivable
    // from the amount: a source and a FALLING flow block are both amount 8. `!isFluidSource(d) &&
    // fluidLevel(d) == 8` is exactly "full-height flow" — the middle of a waterfall.
    private static final long FLUID_SOURCE_BIT    = 1L << 52;           // FluidState is a source (amount 8)
    private static final int FLUID_LEVEL_SHIFT = 53, FLUID_LEVEL_MASK = 0x07; // amount-1 (0..7 <-> 1..8)

    // ---- Precomputed predicate bits (37+) ----------------------------------------------------
    // Each is a PURE function of the fields above, so it adds ZERO navtypes (a function of existing bits
    // can't split a dedup class) and turns a multi-branch movement check into one mask-and-test. Computed
    // once per navtype by {@link #deriveBits} at table build, asserted consistent at init.
    private static final long STANDABLE_BIT  = 1L << 37; // stand on top: solid-topped, no fluid, not damaging
    private static final long BREAKABLE_BIT  = 1L << 38; // mineable geometry: solid, no fluid, not unbreakable/protected
    private static final long OPEN_PLACE_BIT = 1L << 39; // a placed block could fill it: replaceable/empty, no fluid
    private static final long COLLISION_BIT  = 1L << 40; // has a face to build against: solid, no fluid

    /** All derived predicate bits — stripped before a re-derivation ({@link #applyProtected}). */
    private static final long DERIVED_MASK = STANDABLE_BIT | BREAKABLE_BIT | OPEN_PLACE_BIT | COLLISION_BIT;

    // ---- The tables --------------------------------------------------------------------------
    // descriptor (packed long) -> navtype index, for lossless dedup at build time.
    private static final Map<Long, Short> DESCRIPTOR_TO_NAVTYPE = new HashMap<>();
    // navtype index -> descriptor (the table consulted at runtime). Grown during init, then frozen.
    private static long[] descriptors = new long[1024];
    private static int navtypeCount = 0;
    // BlockState -> navtype index. Built once at init; consulted per palette entry (not per block).
    private static final Map<BlockState, Short> STATE_TO_NAVTYPE = new HashMap<>();

    /** A conservative "solid full cube" descriptor used when a block's geometry query throws. */
    private static final long SOLID_FALLBACK = withDerived(
            ((long) 16 << TOP_Y_SHIFT)
            | ((long) SHAPE_FULL << SHAPE_SHIFT)
            | ((long) 8 << HARD_SHIFT)            // ~1.5 hardness
            | ((long) Tool.PICKAXE.ordinal() << TOOL_SHIFT));

    /** Navtype 0 is always air (a zeroed grid/palette defaults to passable air). */
    public static final short AIR;

    private static int errorCount = 0;

    static {
        // Air first so it is navtype 0. CAVE_AIR / VOID_AIR share its fingerprint and dedup to it.
        AIR = intern(fingerprint(Blocks.AIR, Blocks.AIR.defaultBlockState()));
        STATE_TO_NAVTYPE.put(Blocks.AIR.defaultBlockState(), AIR);

        BlockLookup.forEachBlock(block -> {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                if (STATE_TO_NAVTYPE.containsKey(state)) continue; // air already done
                short navtype;
                try {
                    navtype = intern(fingerprint(block, state));
                } catch (Throwable t) {
                    // A misbehaving (often modded) block whose collision/face query throws with a
                    // null world/pos: treat it as a solid full-cube obstacle rather than crash init.
                    errorCount++;
                    navtype = intern(SOLID_FALLBACK);
                }
                STATE_TO_NAVTYPE.put(state, navtype);
            }
        });

        verifyDerivedBits();
        OrebitCommon.LOGGER.info("[Orebit] NavBlock: {} states -> {} navtypes ({} B table, {} errors)",
                STATE_TO_NAVTYPE.size(), navtypeCount, navtypeCount * 8, errorCount);
    }

    // ---- Build-time fingerprinting -----------------------------------------------------------

    /** Intern a descriptor, returning its (existing or freshly assigned) navtype index. */
    private static short intern(long descriptor) {
        Short existing = DESCRIPTOR_TO_NAVTYPE.get(descriptor);
        if (existing != null) return existing;
        if (navtypeCount == descriptors.length) {
            long[] grown = new long[descriptors.length * 2];
            System.arraycopy(descriptors, 0, grown, 0, descriptors.length);
            descriptors = grown;
        }
        short navtype = (short) navtypeCount;
        descriptors[navtypeCount++] = descriptor;
        DESCRIPTOR_TO_NAVTYPE.put(descriptor, navtype);
        return navtype;
    }

    /** Compute the packed descriptor for one block state. */
    private static long fingerprint(Block block, BlockState state) {
        // Bamboo stalks and pointed dripstone give a null-world collision query that NPEs or misleads, so
        // they are forced to a full solid obstacle (matches the dedup study). Block-entity blocks were ALSO
        // blanket-forced solid here — but that wrongly walled off the ZERO-COLLISION décor block entities
        // (signs, banners): their collision shape is genuinely EMPTY (you walk through a sign like air), yet
        // forcing SHAPE_FULL made them non-passable obstacles the planner broke-and-pillared around instead
        // of parkouring past. So a block entity now consults its REAL collision shape: an EMPTY result is
        // honoured (passable air, exactly like the block's geometry demands), while a NON-EMPTY null-context
        // shape stays conservatively solid (context-dependent partials aren't trusted — chest/furnace/skull
        // keep their prior full-cube fingerprint). A block entity whose query THROWS (shulker box, moving
        // piston) is caught by the static-init loop and falls back to SOLID_FALLBACK, exactly as before.
        boolean forceSolid = BlockKinds.isBambooStalk(block) || block instanceof PointedDripstoneBlock;
        // (forceSolid is also the narrow-top identity mark — see the bit-51 fold below.)
        VoxelShape shape;
        if (forceSolid) {
            shape = null;
        } else if (block instanceof BaseEntityBlock) {
            // A block entity's null-context collision query can THROW (shulker box, moving piston) — the old
            // blanket guard skipped the query entirely for these. Try it defensively: a throw or a NON-empty
            // result keeps the conservative full cube (with the block's own hardness/tool, computed below —
            // NOT SOLID_FALLBACK, so no error is counted and throwing states fingerprint exactly as before);
            // only a genuine EMPTY collision (sign/banner décor) is honoured as passable air.
            VoxelShape cs;
            try {
                cs = state.getCollisionShape(null, null);
            } catch (Throwable t) {
                cs = null;
            }
            shape = (cs == null || !cs.isEmpty()) ? null : cs;
        } else {
            shape = state.getCollisionShape(null, null);
        }
        boolean special = shape == null;

        int shapeClass = computeShape(block, state, shape, special);
        int topY = special ? 16
                : shape.isEmpty() ? 0
                : clamp((int) Math.round(shape.max(Direction.Axis.Y) * 16.0), 0, 31);

        FluidState fs = state.getFluidState();
        int fluid = fs.isEmpty() ? FLUID_NONE
                : (fs.getType() == Fluids.LAVA || fs.getType() == Fluids.FLOWING_LAVA) ? FLUID_LAVA
                : FLUID_WATER;

        int openable = block instanceof DoorBlock ? OPEN_DOOR
                : block instanceof TrapDoorBlock ? OPEN_TRAPDOOR
                : block instanceof FenceGateBlock ? OPEN_GATE
                : OPEN_NONE;

        int surface = isSlow(block) ? SURFACE_SLOW : SURFACE_NONE;

        float destroyTime = block.defaultDestroyTime();
        int hardness = destroyTime < 0 ? 255 : clamp(Math.round(destroyTime * 5f), 0, 254);

        Tool tool = hardness > 1 ? bestTool(state) : Tool.NONE; // weak blocks just use hands
        boolean toolRequired = hardness > 1 && state.requiresCorrectToolForDrops();

        long d = 0L;
        d |= (long) (topY & TOP_Y_MASK) << TOP_Y_SHIFT;
        d |= (long) (shapeClass & SHAPE_MASK) << SHAPE_SHIFT;
        d |= (long) (openable & OPEN_MASK) << OPEN_SHIFT;
        d |= (long) (fluid & FLUID_MASK) << FLUID_SHIFT;
        // Fluid facts (DESIGN-fluid-flow-prediction.md §3), read off the same FluidState as the fluid
        // field: sourcehood (amount 8 — waterlogged states qualify, their fluid state IS a water source)
        // and the exact amount, stored as amount-1 so 1..8 fits in three bits. Guarded like the fluid
        // ternary above: an EmptyFluid answers false/0 anyway, but the guard keeps the reads
        // mirror-symmetric with the field derivation AND keeps a non-fluid cell's level field at zero, which
        // is what lets fluidLevel() answer 0 for "no fluid" without a second stored bit.
        if (!fs.isEmpty()) {
            if (fs.isSource()) d |= FLUID_SOURCE_BIT;
            int amount = fs.getAmount();
            if (amount > 0) {
                d |= (long) ((amount - 1) & FLUID_LEVEL_MASK) << FLUID_LEVEL_SHIFT;
            }
        }
        d |= (long) (surface & SURF_MASK) << SURF_SHIFT;
        if (isClimbable(block))               d |= CLIMB_BIT;
        if (block instanceof FallingBlock)    d |= GRAVITY_BIT;
        if (isDamaging(block))                d |= DAMAGE_BIT;
        if (Replaceable.isReplaceable(state)) d |= REPLACE_BIT;
        d |= (long) (hardness & HARD_MASK) << HARD_SHIFT;
        d |= (long) (tool.ordinal() & TOOL_MASK) << TOOL_SHIFT;
        if (toolRequired)                     d |= TOOLREQ_BIT;
        if (state.hasProperty(BlockStateProperties.WATERLOGGED)) d |= WLOGABLE_BIT;
        d |= (long) (transitSlow(block) & TRANSIT_MASK) << TRANSIT_SHIFT;
        // Teleport portals — a 2-bit field (low = any-portal for walker avoidance, high = is-nether for the
        // index/follower). NETHER sets BOTH bits; END_PORTAL / END_GATEWAY set only the low any-portal bit, so
        // the walker routes around them but the nether index never chases them. Explicit vanilla block IDs (no
        // clean nether-vs-end interface exists); all range-stable 1.17.1→26.x, so no platform adapter — matching
        // the direct Blocks.* references throughout this method.
        if (block == Blocks.NETHER_PORTAL)    d |= PORTAL_BIT | NETHER_PORTAL_BIT;
        if (block == Blocks.END_PORTAL || block == Blocks.END_GATEWAY) d |= PORTAL_BIT;
        if (reducesJump(block))               d |= REDUCED_JUMP_BIT;
        // Bubble column: a water cell whose vertical push overrides swim control. Blocks.BUBBLE_COLUMN and
        // BubbleColumnBlock.DRAG_DOWN are range-stable 1.17+, so no platform adapter is needed (matches the
        // direct Blocks.* / NETHER_PORTAL references above). DRAG_DOWN=false → up (soul sand), true → down (magma).
        if (block == Blocks.BUBBLE_COLUMN)
            d |= (long) (state.getValue(BubbleColumnBlock.DRAG_DOWN) ? BUBBLE_DOWN : BUBBLE_UP) << BUBBLE_SHIFT;
        // Fall-softness (bits 48–49): the block's fall-damage class, for a bot LANDING on / falling into it.
        d |= (long) (fallSoftness(block) & FALLSOFT_MASK) << FALLSOFT_SHIFT;
        // NARROW-TOP (bit 51): the collision footprint is too narrow to stand square on — either XZ extent
        // of the shape's bounding box under the bot's own 0.6-block body width (Parkour's documented
        // 0.6×0.6 square). Geometry-derived like topY, no block lists; the force-solid pair (bamboo stalk
        // — a real ~3px column with a position-hash offset — and pointed dripstone) can't be shape-queried
        // (the null-context read NPEs, see above) and is marked by the same identity knowledge that forces
        // it solid. A conservative-full-cube block entity (chest/furnace) is NOT narrow; an empty shape
        // needs no mark (nothing to land on — keeps every passable's descriptor unsplit).
        if (forceSolid) {
            d |= NARROW_TOP_BIT;
        } else if (shape != null && !shape.isEmpty()) {
            AABB nb = shape.bounds();
            if ((nb.maxX - nb.minX) < 0.6 || (nb.maxZ - nb.minZ) < 0.6) d |= NARROW_TOP_BIT;
        }
        // Stair facing/half — the directional standing-surface facts (bits 8–10), populated ONLY for stairs so
        // only stair states split navtypes. A north/east/south/west stair now dedups per FACING (as the old
        // per-Block sturdy-faces mask did), but bounded: stairs conflate across material, so this adds at most
        // 4 facings × 2 halves × the distinct base stair descriptors (a few dozen navtypes, well within cap).
        if (block instanceof StairBlock) {
            d |= (long) horizontalFacingOrdinal(state) << STAIR_FACING_SHIFT;
            if (state.getValue(BlockStateProperties.HALF) == Half.TOP) d |= STAIR_HALF_BIT;
        }
        // Door directional solidity (P0): pack HORIZONTAL_FACING into the SHARED facing field (bits 8–9), the
        // hinge into bit 13, and open/closed into bit 43 — for ALL doors incl. iron (an open iron door is still a
        // passable doorway). The TOGGLEABLE bit (50, P2) additionally marks a HAND-openable door (wood/copper) so
        // the planner may prefer opening it over smashing it; iron doors lack it and fall through to break/route.
        // The blocked edge is DERIVED at query time (#doorBlockedEdge), not stored. HORIZONTAL_FACING / OPEN /
        // DOOR_HINGE are range-stable 1.17.1→26.x (same discipline as the stair/bubble reads above), so no platform
        // seam. A door is SHAPE_OTHER (not a stair), so these bits never collide with a stair's reads of the same
        // field — the isStair / isDoor gate disambiguates. Both door halves share FACING/OPEN/HINGE, so they dedup.
        if (block instanceof DoorBlock) {
            d |= (long) horizontalFacingOrdinal(state) << DOOR_FACING_SHIFT;
            if (state.getValue(BlockStateProperties.DOOR_HINGE) == DoorHingeSide.RIGHT) d |= DOOR_HINGE_BIT;
            if (state.getValue(BlockStateProperties.OPEN)) d |= DOOR_OPEN_BIT;
            // Hand-openable iff NOT an iron door. The Blocks.IRON_DOOR identity check mirrors the Blocks.*
            // idiom used for portals/bubble columns above (range-stable, no BlockSetType.canOpenByHand seam).
            // Copper doors (≥1.21) are non-iron + hand-openable, so this needs no explicit version gate: on
            // versions without copper the non-iron door set is exactly the wood species.
            if (block != Blocks.IRON_DOOR) d |= HAND_TOGGLEABLE_BIT;
        }
        // Trapdoor directional solidity (DESIGN-trapdoors.md §2, owner-ratified 2026-08-08): pack
        // HORIZONTAL_FACING into the SHARED facing field (bits 8–9), HALF into the shared bit 10 (1 = TOP),
        // and OPEN into the shared bit 43 — ZERO new bits (a cell is stair XOR door XOR trapdoor; the openable
        // kind at 14–15 disambiguates every read). Geometry keeps its generic collision-shape derivation
        // above: closed-BOTTOM → topY 3 SHAPE_PARTIAL_LOW (a standable 3/16 floor plate), closed-TOP → topY 16
        // SHAPE_OTHER (a standable hatch flush with the cell top; its 13/16 gap beneath is invisible to topY —
        // see #ceilingMinY), open → topY 16 SHAPE_OTHER + NARROW_TOP (a 3/16 full-height panel on the wall
        // opposite facing, not standable). POWERED is deliberately unread (powered variants dedup; hand-toggle
        // works regardless, and a redstone re-force is an external change the grid repatch absorbs).
        // Hand-toggleable iff NOT the iron trapdoor — the Blocks.IRON_DOOR idiom above, vanilla-complete
        // across the range (copper trapdoors, ≥1.20.3, are non-iron + hand-openable, so no version gate).
        if (block instanceof TrapDoorBlock) {
            d |= (long) horizontalFacingOrdinal(state) << TRAPDOOR_FACING_SHIFT;
            if (state.getValue(BlockStateProperties.HALF) == Half.TOP) d |= TRAPDOOR_HALF_BIT;
            if (state.getValue(BlockStateProperties.OPEN)) d |= TRAPDOOR_OPEN_BIT;
            if (block != Blocks.IRON_TRAPDOOR) d |= HAND_TOGGLEABLE_BIT;
        }
        // Fence gates (DESIGN-fence-gates.md §2, owner-ratified 2026-08-09): pack OPEN into the shared bit 43
        // and hand-toggleable into the shared bit 50 — UNCONDITIONALLY (the vanilla roster is all-wood, all
        // hand-openable at every version 1.17.1→26.x; no iron gate exists, so unlike the door/trapdoor branches
        // there is no Blocks.* exception test). Geometry keeps its generic collision-shape derivation above:
        // closed → topY 24 SHAPE_OTHER + NARROW_TOP (a centered 4/16 plate — a whole-cell blocker, never
        // standable), open → Shapes.empty() SHAPE_EMPTY (passable air). FACING is deliberately NOT packed
        // (behaviorally void — see the GATE_OPEN_BIT comment); POWERED/IN_WALL are deliberately unread
        // (IN_WALL is collision-invariant), so all those states collapse in dedup.
        if (block instanceof FenceGateBlock) {
            if (state.getValue(BlockStateProperties.OPEN)) d |= GATE_OPEN_BIT;
            d |= HAND_TOGGLEABLE_BIT;
        }
        // Ladder FACING (DESIGN-trapdoor-ladder-climb.md §2, owner-ratified 2026-08-09): pack
        // HORIZONTAL_FACING into the SHARED facing field (bits 8–9) — the ladder joins the stair XOR door
        // XOR trapdoor XOR ladder family, ZERO new bits, ladder navtypes fan 1 → 4 (trivial vs the cap).
        // Consumed only by the vanilla trapdoor-over-ladder climb rule (LivingEntity.trapdoorUsableAsLadder:
        // OPEN trapdoor + exactly Blocks.LADDER below + EQUAL facing — HALF/waterlogging not consulted).
        // Vines/scaffolding deliberately do NOT pack facing: the vanilla rule is ladder-identity-only.
        if (block instanceof LadderBlock) {
            d |= (long) horizontalFacingOrdinal(state) << LADDER_FACING_SHIFT;
        }
        return withDerived(d);
    }

    /**
     * OR the precomputed predicate bits onto a base descriptor — each a pure function of the base fields,
     * so this never changes the dedup class (zero extra navtypes). The single source of truth for {@link
     * #isStandable}/{@link #isBreakable}/{@link #isOpenForPlace}/{@link #hasCollision}; {@link
     * #verifyDerivedBits} re-checks it at init so a packing slip can't ship silently.
     */
    private static long withDerived(long d) {
        int shape = shape(d);
        boolean solid = shape != SHAPE_EMPTY;
        boolean noFluid = fluid(d) == 0;
        // STANDABLE is pure GEOMETRY (owner ruling, s52b hazard-media): a solid top you can stand on.
        // Damaging floors (magma, campfire) ARE standable — the damage is a COST the movement layer
        // charges via caps.costPerHitpoint (an immune bot walks them free); excluding them here made
        // them caps-blind walls and turned magma fields into hard BLOCKED. SHAPE_OTHER is standable
        // when nothing pokes above the unit cube (topY <= 16): soul sand (14), honey (15), chests,
        // anvils are all real vanilla floors that the old blanket OTHER-exclusion walled off — that
        // net exists for fences/walls (topY 24), which the topY test still catches. Without this,
        // getSpeedFactor-classified slow floors (soul sand/honey, both OTHER) could never be stood on
        // and SURFACE_SLOW would be dead code.
        // NARROW TOPS ARE NOT FLOORS (owner ruling 2026-08-01). Technically you CAN stand on a ladder's 3px
        // plate or a dripstone tip when your box overlaps it — but the servo, the momentum-conservation
        // logic and the ballistics all assume a full-width, full-depth block, and we already refuse to walk,
        // run, jump off or land on narrow tops everywhere it matters. Modelling them as floors bought
        // nothing and cost a livelock: the flagship wedged at (148,30,7) hopping on a pointed_dripstone tip
        // (botY 29.688 == 29 + 11/16) that an Ascend had planned to stand ON at feet 30.0.
        //
        // A SUBTRACTION ONLY — it does NOT make the cell passable (isPassable is shape == SHAPE_EMPTY,
        // independent of this bit), so these cells were already walls for transit and are no more walkable
        // through than before. BREAKABLE / COLLISION derive from `solid` below and are untouched: still
        // mineable, still a face to build against. Same technique as the topY<=16 net beside it, which
        // already excludes fences/walls (topY 24) for the same "not really a floor" reason.
        //
        // KNOWN COST: STANDABLE feeds the depth nibble (floorGap = distance-to-first-standable-below), so a
        // bamboo/dripstone column no longer terminates that downward scan and a Fall over one predicts a
        // longer drop than it takes — conservative (over-estimates damage), and accepted.
        if (solid && noFluid && !isNarrowTop(d)
                && (shape != SHAPE_OTHER || topY(d) <= 16))                  d |= STANDABLE_BIT;
        if (solid && noFluid && hardness(d) != 255 && !isProtected(d))       d |= BREAKABLE_BIT;
        // OPEN_PLACE also excludes protected: filling the cell would REPLACE (destroy) its occupant — a
        // protected bush/grass must not be cleared by a placement any more than by a punch. (Protected
        // air is a degenerate config nobody should write; it would just make the cell unfillable.)
        // FLUIDS ARE OPEN (owner ruling, s52b): water and lava are vanilla-replaceable — placing into
        // them is completely valid (sealing a lava source with cobble is a standard technique). The old
        // noFluid conjunct wrongly barred every fluid cell. (Since 2026-08-17 no flag gate refuses
        // fluid-adjacent edits at all: RISKY_EDIT is strictly gravity and a fluid-admitting break is
        // PRICED by the fold funnel — DESIGN-fluid-flow-prediction.md §4.1/§4.2.)
        if ((isReplaceable(d) || shape == SHAPE_EMPTY) && !isProtected(d))           d |= OPEN_PLACE_BIT;
        if (solid && noFluid)                                                d |= COLLISION_BIT;
        return d;
    }

    /**
     * Re-derive every precomputed bit from the base fields and assert it matches what's stored, over the
     * whole table — the safety net that keeps the precomputed answer from drifting from its definition (a
     * bad shift, an overlap with another field). Runs once at init over a few hundred navtypes.
     */
    private static void verifyDerivedBits() {
        for (int i = 0; i < navtypeCount; i++) {
            long d = descriptors[i];
            int shape = shape(d);
            boolean solid = shape != SHAPE_EMPTY, noFluid = fluid(d) == 0;
            boolean standable = solid && noFluid && !isNarrowTop(d)
                    && (shape != SHAPE_OTHER || topY(d) <= 16);
            boolean breakable = solid && noFluid && hardness(d) != 255 && !isProtected(d);
            boolean openPlace = (isReplaceable(d) || shape == SHAPE_EMPTY) && !isProtected(d);
            boolean collision = solid && noFluid;
            if (isStandable(d) != standable || isBreakable(d) != breakable
                    || isOpenForPlace(d) != openPlace || hasCollision(d) != collision) {
                throw new IllegalStateException("NavBlock precomputed predicate bit mismatch at navtype " + i
                        + " (descriptor 0x" + Long.toHexString(d) + ")");
            }
        }
    }

    private static int computeShape(Block block, BlockState state, VoxelShape shape, boolean special) {
        if (special) return SHAPE_FULL;
        if (shape == null || shape.isEmpty()) return SHAPE_EMPTY;
        if (block instanceof StairBlock) return SHAPE_STAIR;
        if (state.hasProperty(BlockStateProperties.SLAB_TYPE)) {
            SlabType t = state.getValue(BlockStateProperties.SLAB_TYPE);
            if (t == SlabType.DOUBLE) return SHAPE_FULL;
            return t == SlabType.TOP ? SHAPE_SLAB_TOP : SHAPE_SLAB_BOTTOM;
        }
        if (block instanceof SnowLayerBlock) return SHAPE_LAYER;
        AABB b = shape.bounds();
        boolean unitCube = b.minX == 0 && b.minY == 0 && b.minZ == 0
                && b.maxX == 1 && b.maxY == 1 && b.maxZ == 1;
        if (unitCube) return SHAPE_FULL;            // stairs/slabs already returned above
        return b.maxY <= 0.5 ? SHAPE_PARTIAL_LOW : SHAPE_OTHER;
    }

    private static boolean isClimbable(Block block) {
        return block == Blocks.LADDER || block == Blocks.SCAFFOLDING
                || block == Blocks.VINE
                || block == Blocks.CAVE_VINES || block == Blocks.CAVE_VINES_PLANT
                || block == Blocks.TWISTING_VINES || block == Blocks.TWISTING_VINES_PLANT
                || block == Blocks.WEEPING_VINES || block == Blocks.WEEPING_VINES_PLANT;
    }

    /**
     * A slow FLOOR, classified from vanilla's own per-block walk-speed factor ({@code
     * Block.getSpeedFactor()} — soul sand and honey are 0.4, everything else 1.0). The principled source
     * (owner ruling, s52b): the old hand list wrongly included soul soil / slime / cobweb, none of which
     * reduce walk speed in vanilla (cobweb's real cost is the through-transit class; slime only changes
     * bounce). Also auto-classifies any modded slow floor. Priced as a MULTIPLIER by the movement layer
     * ({@code Traverse.SLOW_COST_FACTOR} = 1/0.4).
     */
    private static boolean isSlow(Block block) {
        return block.getSpeedFactor() < 0.999f;
    }

    /**
     * A REDUCED-JUMP floor, classified from vanilla's own per-block jump-power factor ({@code
     * Block.getJumpFactor()} — honey block is 0.5, everything else 1.0, so honey is the only vanilla
     * case). Standing on such a floor caps the jump apex (~0.384 blocks — {@code getBlockJumpFactor}
     * samples the feet block else the floor block, so a bot standing ON honey gets the honey factor);
     * the jump movements refuse to take off from it. Mirrors {@link #isSlow}'s {@code getSpeedFactor}
     * read (JUMP power here, WALK speed there) and auto-classifies any modded reduced-jump floor.
     */
    private static boolean reducesJump(Block block) {
        return block.getJumpFactor() < 0.999f;
    }

    /**
     * The 2-bit horizontal-facing ordinal (0=N 1=E 2=S 3=W) from a state's {@link
     * BlockStateProperties#HORIZONTAL_FACING} — shared by stairs (the FACING side is where a BOTTOM stair's HIGH
     * 16/16 half sits, StairVoxelProbe), doors (the facing that {@link #doorBlockedEdge} rotates from),
     * trapdoors (the facing whose OPPOSITE wall the open panel hugs, {@link #trapdoorBlockedFace}) and ladders
     * (the equal-facing half of the vanilla trapdoor-over-ladder rule, {@link #ladderFacing}). Every
     * {@link StairBlock}, {@link DoorBlock}, {@link TrapDoorBlock} and {@link LadderBlock} carries
     * HORIZONTAL_FACING on every supported version.
     */
    private static int horizontalFacingOrdinal(BlockState state) {
        switch (state.getValue(BlockStateProperties.HORIZONTAL_FACING)) {
            case EAST:  return 1;
            case SOUTH: return 2;
            case WEST:  return 3;
            default:    return 0; // NORTH
        }
    }

    /**
     * Through-slow class for a body cell (the {@code transit} field, bits 41–42) — a PASSABLE cell that
     * slows an entity moving through it, priced by the movement layer per transited cell. Distinct from
     * {@link #isSlow} (a slow FLOOR you stand on). Cobweb is heavy (~0.05× speed — near a wall for a
     * planner without shears); sweet berry bush and powder snow are light (~0.75×; both also carry the
     * damaging bit — the bush pricks, powder snow freezes — priced separately by the movement layer).
     * Lava is {@link #TRANSIT_FLUID} (~0.4×): a body moving through it IS slowed, and saying so here means
     * the movement layer reads the fact instead of re-deriving it. Water is deliberately {@link
     * #TRANSIT_NONE} — the swim rungs' costs ARE the water rates, so a transit surcharge would double-charge.
     */
    private static int transitSlow(Block block) {
        if (block == Blocks.COBWEB) return TRANSIT_HEAVY;
        if (block == Blocks.SWEET_BERRY_BUSH || block == Blocks.POWDER_SNOW) return TRANSIT_LIGHT;
        if (block == Blocks.LAVA) return TRANSIT_FLUID;
        return TRANSIT_NONE;
    }

    /**
     * The LANDING FALL-SOFTNESS class (the {@code fallSoftness} field, bits 48–49) — how much fall damage
     * the block absorbs when a bot lands ON (or falls INTO) it, encoded as one of four classes rounded UP
     * conservatively (toward MORE damage) from the block's true vanilla fall-damage multiplier:
     * <ul>
     *   <li>{@link #FALLSOFT_ZERO} (×0.0) — slime block ({@code SlimeBlock.fallOn} cancels ALL fall damage
     *       when not sneaking; the bot never sneaks), and the fall-distance-RESET media: water (any depth),
     *       bubble columns, powder snow, sweet berry bush, cobweb (each resets the entity's fall distance so
     *       the landing deals nothing);</li>
     *   <li>{@link #FALLSOFT_FIFTH} (×0.2) — hay bale + honey block ({@code fallOn} scales damage to 0.2);</li>
     *   <li>{@link #FALLSOFT_HALF} (×0.5) — beds ({@code BedBlock.fallOn} scales damage to 0.5);</li>
     *   <li>{@link #FALLSOFT_NONE} (×1.0) — every other block (full fall damage).</li>
     * </ul>
     * There is no single vanilla getter for the multiplier (it lives inside per-block {@code fallOn}
     * overrides / fall-distance resets), so this is a CURATED block-identity/instanceof map — the same
     * pattern as {@code openable} (DoorBlock/TrapDoorBlock/FenceGateBlock) and the {@code bubble}
     * (Blocks.BUBBLE_COLUMN) classification. Every block referenced is range-stable 1.17.1→26.x, so no
     * platform adapter is needed (matching the direct {@code Blocks.*} / {@code instanceof} references
     * throughout this method).
     *
     * <p><b>V1 landing scope:</b> {@code Fall} only lands on a {@link #isStandable standable} cell, so only
     * the STANDABLE soft-landers (slime / hay / honey / bed) affect pathing today; the fall-distance-reset
     * media (water / powder snow / berry / cobweb / bubble column) are classified here for correctness and
     * future use but are not yet landing targets (a Fall→swim landing predicate is deferred to v1.1).
     */
    private static int fallSoftness(Block block) {
        if (block == Blocks.SLIME_BLOCK
                || block == Blocks.WATER || block == Blocks.BUBBLE_COLUMN
                || block == Blocks.POWDER_SNOW
                || block == Blocks.SWEET_BERRY_BUSH || block == Blocks.COBWEB) {
            return FALLSOFT_ZERO;
        }
        if (block == Blocks.HAY_BLOCK || block == Blocks.HONEY_BLOCK) {
            return FALLSOFT_FIFTH;
        }
        if (block instanceof BedBlock) {
            return FALLSOFT_HALF;
        }
        return FALLSOFT_NONE;
    }

    private static boolean isDamaging(Block block) {
        return block == Blocks.LAVA || block == Blocks.FIRE || block == Blocks.SOUL_FIRE
                || block == Blocks.CAMPFIRE || block == Blocks.SOUL_CAMPFIRE
                || block == Blocks.MAGMA_BLOCK || block == Blocks.CACTUS
                || block == Blocks.SWEET_BERRY_BUSH || block == Blocks.WITHER_ROSE
                || block == Blocks.POWDER_SNOW; // freezing damage while inside (1.17+, the support floor)
    }

    private static Tool bestTool(BlockState state) {
        if (state.is(Blocks.VINE) || state.is(Blocks.COBWEB)) return Tool.SHEARS;
        if (state.is(BlockTags.MINEABLE_WITH_PICKAXE)) return Tool.PICKAXE;
        if (state.is(BlockTags.MINEABLE_WITH_AXE)) return Tool.AXE;
        if (state.is(BlockTags.MINEABLE_WITH_SHOVEL)) return Tool.SHOVEL;
        if (state.is(BlockTags.MINEABLE_WITH_HOE)) return Tool.HOE;
        if (MineableTags.swordEfficient(state)) return Tool.SWORD;
        return Tool.NONE;
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }

    // ---- Post-init policy application (cold, once per config load/reload) ---------------------

    /**
     * Fold the owner's {@code mining.protectedBlocks} list into the classification fingerprint: for every
     * known {@link BlockState}, set (or clear) the {@link #isProtected PROTECTED} bit according to
     * {@code isProtected} and re-derive the predicate bits — remapping the state to the matching (existing
     * or freshly interned) navtype. States whose protected-ness didn't change keep their navtype, so
     * applying the same list twice is a no-op (returns {@code 0}).
     *
     * <p><b>Why post-init, not in {@link #fingerprint}:</b> the list lives in the config, which loads at
     * server start ({@code ConfigLoader.install}) — potentially AFTER this class's static-init — and tag
     * entries ({@code #minecraft:beds}) need the datapack tags, which are only bound by server start. So
     * the base table builds config-blind and this pass patches it; it runs BEFORE any nav grid is built
     * (chunk nav builds are deferred to the world tick, after server-started), so live grids always hold
     * post-policy navtypes. On a {@code /bot config reload} that CHANGES the list, the table is re-derived
     * (this method is fully reversible — un-protected states re-derive back to their base navtype and
     * remap to it) but already-built grid sections still hold the old navtypes until rebuilt: the caller
     * warns that a restart (or chunk rebuilds) is needed for the planner to fully see the change. The
     * execution-side {@code Config.mayBreak} guard applies immediately regardless.
     *
     * <p>Splitting is bounded by the list: each protected entry adds at most as many navtypes as its
     * states had distinct base navtypes (typically a handful per entry against a base count in the
     * low hundreds, versus the hard 1024 cap). Old navtypes never disappear (grid data may still reference them); a re-protect after an
     * un-protect re-uses the previously interned protected descriptor. Cold: a full-table pass over ~28k
     * states, once per config load — never per node, never per tick.
     *
     * @return the number of block states whose navtype changed (0 ⇒ nothing to re-see; no staleness)
     */
    public static synchronized int applyProtected(java.util.function.Predicate<BlockState> isProtected) {
        int remapped = 0;
        for (Map.Entry<BlockState, Short> e : STATE_TO_NAVTYPE.entrySet()) {
            long current = descriptors[e.getValue() & 0xFFFF];
            long base = current & ~(DERIVED_MASK | PROTECTED_BIT);
            long want = withDerived(isProtected.test(e.getKey()) ? base | PROTECTED_BIT : base);
            if (want != current) {
                e.setValue(intern(want));
                remapped++;
            }
        }
        if (navtypeCount > 1024) {
            // TraversalGrid packs a 10-bit navtype — beyond 1024 grid cells would truncate and
            // mis-resolve (NavSectionBuilder's static-init raises the same alarm, but it may have run
            // BEFORE this split). Surface the cause so the owner trims the list.
            OrebitCommon.LOGGER.error(
                    "[Orebit] NavBlock: navtype count {} exceeds the 10-bit nav-grid capacity (1024) after "
                            + "protected-block splitting — grid cells will mis-resolve; trim mining.protectedBlocks",
                    navtypeCount);
        }
        return remapped;
    }

    // ---- Lookup (cold / per-palette-entry) ---------------------------------------------------

    /** The navtype index for a block state ({@link #AIR} for unknown states). */
    public static short navtypeFor(BlockState state) {
        Short n = STATE_TO_NAVTYPE.get(state);
        return n == null ? AIR : n;
    }

    /** The packed descriptor for a navtype index. */
    public static long descriptor(short navtype) {
        return descriptors[navtype & 0xFFFF];
    }

    /** The packed descriptor for a block state (convenience: {@code descriptor(navtypeFor(state))}). */
    public static long descriptorFor(BlockState state) {
        return descriptors[navtypeFor(state) & 0xFFFF];
    }

    /** Number of distinct navtypes registered. */
    public static int navtypeCount() {
        return navtypeCount;
    }

    /** Size of the descriptor table in bytes (for cache analysis). */
    public static int tableBytes() {
        return navtypeCount * 8;
    }

    /** Number of states whose geometry query threw and fell back to a solid cube. */
    public static int errorCount() {
        return errorCount;
    }

    // ---- Field extraction (hot path: pure bit ops on the packed long) ------------------------

    /** Collision top surface in 1/16ths (0..31): e.g. 8 = half block, 16 = full. */
    public static int topY(long d)        { return (int) (d >>> TOP_Y_SHIFT) & TOP_Y_MASK; }
    /** ShapeClass ordinal — one of {@link #SHAPE_EMPTY}..{@link #SHAPE_OTHER}. */
    public static int shape(long d)        { return (int) (d >>> SHAPE_SHIFT) & SHAPE_MASK; }
    /** Whether this cell is a stair ({@link #SHAPE_STAIR}) — the gate on the directional-surface path. */
    public static boolean isStair(long d)  { return shape(d) == SHAPE_STAIR; }
    /** Stair horizontal FACING ordinal (0=N 1=E 2=S 3=W); 0 for non-stairs. On a BOTTOM stair the HIGH
     *  16/16 half is on the FACING side (StairVoxelProbe) — see the movement layers directional-surface resolver. */
    public static int stairFacing(long d)  { return (int) (d >>> STAIR_FACING_SHIFT) & STAIR_FACING_MASK; }
    /** Stair HALF: 0 = bottom (directional surface), 1 = top (flat 16/16 top — no directional handling).
     *  Reads the SHARED half bit (10) — a trapdoor packs its own HALF there ({@link #trapdoorHalfTop}), so
     *  gate on {@link #isStair} first. */
    public static int stairHalf(long d)    { return (d & STAIR_HALF_BIT) != 0 ? 1 : 0; }
    /** Openable kind: 0 none, 1 door, 2 trapdoor, 3 fence-gate. */
    public static int openable(long d)     { return (int) (d >>> OPEN_SHIFT) & OPEN_MASK; }

    // ---- Door directional solidity (P0) — meaningful only when isDoor(d) ----------------------
    /** Whether this cell is a {@link DoorBlock} (any material incl. iron) — the gate on the door queries below. */
    public static boolean isDoor(long d)   { return openable(d) == OPEN_DOOR; }
    /** A door's HORIZONTAL_FACING ordinal (0=N 1=E 2=S 3=W); reads the shared facing field (bits 8–9). 0 for
     *  non-doors — always gate on {@link #isDoor} first (a stair/trapdoor uses the same bits via
     *  {@link #stairFacing} / {@link #trapdoorFacing}). */
    public static int doorFacing(long d)   { return (int) (d >>> DOOR_FACING_SHIFT) & DOOR_FACING_MASK; }
    /** A door's DOOR_HINGE side: {@code true} = RIGHT, {@code false} = LEFT (bit 13). 0 for non-doors. */
    public static boolean doorHinge(long d){ return (d & DOOR_HINGE_BIT) != 0; }
    /** Whether a door is OPEN ({@code true}) or CLOSED ({@code false}); reads the SHARED open bit (43) — a
     *  trapdoor packs its own OPEN there ({@link #trapdoorOpen}), so gate on {@link #isDoor} first. */
    public static boolean doorOpen(long d) { return (d & DOOR_OPEN_BIT) != 0; }
    /**
     * The single cardinal edge (0=N 1=E 2=S 3=W) a door BLOCKS in its current state — DERIVED, never stored, so
     * both states cost 2 bits (facing) + 2 bits (hinge/open) rather than a 5-bit precomputed pair (owner-final
     * bit plan). A CLOSED door blocks the edge OPPOSITE its facing ({@code (facing+2)&3}); an OPEN door swings to
     * block a PERPENDICULAR edge chosen by hinge ({@code RIGHT → (facing+1)&3}, {@code LEFT → (facing+3)&3}).
     * Ground truth (owner-verified table, asserted in DoorClassificationTest): CLOSED N→S S→N E→W W→E; OPEN
     * facing N L→W R→E, S L→E R→W, E L→N R→S, W L→S R→N. Undefined for non-doors — gate on {@link #isDoor}.
     */
    public static int doorBlockedEdge(long d) {
        int facing = doorFacing(d);
        if (doorOpen(d)) return doorHinge(d) ? (facing + 1) & 3 : (facing + 3) & 3;
        return (facing + 2) & 3;
    }
    /**
     * Whether a door can be opened/closed BY HAND (wood + copper), vs a redstone-only iron door (DOORS P2).
     * Reads the SHARED hand-toggleable bit (50) — a hand-openable trapdoor sets the same bit
     * ({@link #handToggleable} is the kind-neutral read) — so gate on {@link #isDoor} first. The planner
     * offers a cheap OPEN/CLOSE SET edit only for toggleable doors; an iron door has no SET option and falls
     * through to break/route.
     */
    public static boolean doorToggleable(long d) { return (d & HAND_TOGGLEABLE_BIT) != 0; }
    /**
     * The same door descriptor forced into the {@code open} state — sets/clears the OPEN bit (43), leaving
     * facing/hinge/toggleable and every derived predicate bit untouched (none depends on OPEN: a door is
     * {@code SHAPE_OTHER}/non-passable open or closed). So {@code withDoorOpen(closedDesc, true)} is bit-identical
     * to the same door's real OPEN navtype descriptor, and {@link #doorBlockedEdge} of the result is the target
     * state's blocked edge. This is how {@code MovementContext.descriptorAt} resolves a planned {@code SET_OPEN}/
     * {@code SET_CLOSED} door edit against THAT door's own facing/hinge (unlike a PLACED cell, which resolves to a
     * universal cobblestone constant). Undefined for non-doors — the caller applies it only to door cells.
     */
    public static long withDoorOpen(long d, boolean open) {
        return open ? (d | DOOR_OPEN_BIT) : (d & ~DOOR_OPEN_BIT);
    }

    // ---- Trapdoor directional solidity (DESIGN-trapdoors.md §2–§4) — meaningful only when isTrapdoor(d) ----
    /** Whether this cell is a {@link TrapDoorBlock} (any material incl. iron) — the gate on the trapdoor
     *  queries below (the trapdoor analog of {@link #isDoor}). */
    public static boolean isTrapdoor(long d) { return openable(d) == OPEN_TRAPDOOR; }
    /** A trapdoor's HORIZONTAL_FACING ordinal (0=N 1=E 2=S 3=W); reads the shared facing field (bits 8–9).
     *  0 for non-trapdoors — always gate on {@link #isTrapdoor} first (a stair/door uses the same bits via
     *  {@link #stairFacing} / {@link #doorFacing}). The OPEN panel hugs the wall OPPOSITE this facing. */
    public static int trapdoorFacing(long d) { return (int) (d >>> TRAPDOOR_FACING_SHIFT) & TRAPDOOR_FACING_MASK; }
    /** A trapdoor's HALF: {@code true} = TOP (its CLOSED plate is flush with the cell top, 13..16/16),
     *  {@code false} = BOTTOM (a 3/16 floor plate); reads the shared half bit (10). HALF does NOT affect the
     *  OPEN shape (vanilla fact, DESIGN-trapdoors.md §1) but is packed unconditionally so a toggle round-trips
     *  to the right closed geometry. Gate on {@link #isTrapdoor} (a stair shares the bit via {@link #stairHalf}). */
    public static boolean trapdoorHalfTop(long d) { return (d & TRAPDOOR_HALF_BIT) != 0; }
    /** Whether a trapdoor is OPEN ({@code true}, a 3/16 full-height panel on the wall opposite facing) or
     *  CLOSED ({@code false}, a horizontal plate per its half); reads the shared open bit (43). Gate on
     *  {@link #isTrapdoor} (a door shares the bit via {@link #doorOpen}). */
    public static boolean trapdoorOpen(long d) { return (d & TRAPDOOR_OPEN_BIT) != 0; }
    /**
     * An OPEN trapdoor cell — <b>body-passable for occupancy and transit</b> (Traverse/Diagonal bodies,
     * Fall/Climb columns, Pillar shafts) in every direction EXCEPT across its {@link #trapdoorBlockedFace
     * blocked face} (DESIGN-trapdoors.md §4): the 3/16 wall-hugging panel coexists with the centered 0.6
     * body — worst case opposite-wall panels leave 10/16 = 0.625 &gt; 0.6. A PREDICATE over existing fields,
     * not a descriptor change: {@link #isPassable} stays {@code false} (the shape is non-empty), and the
     * clearance rules admit the cell explicitly instead. <b>WATERLOGGED cells are excluded</b> (§10:
     * waterlogged trapdoor cells stay conservative walls — a waterlogged cell is a full water source, so
     * admitting it would walk a dry body into water the walk family neither prices nor survives; the
     * pre-arc verdict for these cells was "wall", and this conjunct keeps it).
     */
    public static boolean openTrapdoor(long d) { return isTrapdoor(d) && trapdoorOpen(d) && !isFluid(d); }
    /** {@link #trapdoorBlockedFace} vertical encodings — 0..3 are the door-edge cardinals (0=N 1=E 2=S 3=W). */
    public static final int FACE_UP = 4, FACE_DOWN = 5;
    /**
     * The single FACE (0=N 1=E 2=S 3=W / {@link #FACE_UP} / {@link #FACE_DOWN}) a trapdoor BLOCKS in its
     * current state — DERIVED at query time like {@link #doorBlockedEdge}, never stored (DESIGN-trapdoors.md
     * §3). CLOSED half=BOTTOM is a floor plate → blocks DOWN; CLOSED half=TOP is a ceiling plate → blocks UP;
     * OPEN is a full-height panel on the wall OPPOSITE facing → blocks that cardinal ({@code (facing+2)&3}:
     * facing N→S, S→N, E→W, W→E). The other 5 faces are crossable (subject to the §4 clearance rules);
     * crossing the blocked face requires a SET fold, and toggling always clears the crossed face (closed↔open
     * swaps the blocked face to a perpendicular set — the door principle). Undefined for non-trapdoors — gate
     * on {@link #isTrapdoor}.
     */
    public static int trapdoorBlockedFace(long d) {
        if (trapdoorOpen(d)) return (trapdoorFacing(d) + 2) & 3;
        return trapdoorHalfTop(d) ? FACE_UP : FACE_DOWN;
    }
    /**
     * Whether an OPENABLE cell (door / trapdoor / fence gate) can be toggled BY HAND — the kind-neutral read
     * of the shared bit 50 ({@link #doorToggleable} is the door-flavored twin). Set for every {@link
     * DoorBlock} except {@link Blocks#IRON_DOOR}, every {@link TrapDoorBlock} except {@link
     * Blocks#IRON_TRAPDOOR} (each the complete vanilla exception set), and every {@link FenceGateBlock}
     * UNCONDITIONALLY (no iron gate exists at any version — DESIGN-fence-gates.md §1). Gate on the matching
     * kind ({@link #isDoor} / {@link #isTrapdoor} / {@link #isGate}); {@code false} for every non-openable
     * cell.
     */
    public static boolean handToggleable(long d) { return (d & HAND_TOGGLEABLE_BIT) != 0; }
    /**
     * The lowest Y (in 16ths of the cell, 0..16) of a UNIFORM full-footprint collision band at the CELL TOP —
     * the "high ceiling" height consumed by the generalized residual clearance rule (DESIGN-trapdoors.md §4):
     * an upper body cell admits a bot standing on floor topY {@code t} when {@code ceilingMinY(c2) >= t - 3}
     * (the integer-conservative form of −3.2). Only shapes whose collision is CONFINED to such a band
     * qualify: a CLOSED TOP-half trapdoor → <b>13</b> (its plate spans 13..16/16), a {@link #SHAPE_SLAB_TOP
     * top slab} → <b>8</b>; everything else → <b>0</b> (no admissible band — notably TOP-half STAIRS report
     * 0, since their riser descends to the floor on one side and admitting them is unsound without direction
     * math). Passable cells also report 0 — callers test passability/open-trapdoor first; 0 simply never
     * admits via the ceiling arm. <b>WATERLOGGED cells report 0</b> (§10 conservative-wall rule, same as
     * {@link #openTrapdoor}): a waterlogged closed-TOP hatch / top slab is a full water source below its
     * band — admitting it as a walking ceiling would put the bot's head inside water, unpriced and
     * breath-blind; pre-arc these cells were walls, and the ceiling arm must not newly admit them.
     */
    public static int ceilingMinY(long d) {
        if (isFluid(d)) return 0;
        if (isTrapdoor(d) && !trapdoorOpen(d) && trapdoorHalfTop(d)) return 13;
        if (shape(d) == SHAPE_SLAB_TOP) return 8;
        return 0;
    }
    /**
     * The same trapdoor descriptor forced into the {@code open} state — unlike {@link #withDoorOpen} (a bare
     * bit-43 flip: a door's geometry is open/closed-invariant), a trapdoor toggle CHANGES GEOMETRY, so this
     * RE-DERIVES it (DESIGN-trapdoors.md §2): topY ({@code open ? 16 : half=TOP ? 16 : 3}), shape
     * ({@code open ? OTHER : half=TOP ? OTHER : PARTIAL_LOW}), NARROW_TOP (open only — the 3/16 panel), then
     * the derived predicate bits (STANDABLE/BREAKABLE/OPEN_PLACE/COLLISION) via the same {@link #withDerived}
     * the classifier uses. Every non-geometry field (fluid, hardness, tool, toolRequired, protected, transit,
     * damage, surface, climb, gravity, replaceable, waterloggable, bubble, fallSoftness, reducedJump,
     * openable, facing, half, hand-toggleable) carries over untouched. Pinned by test
     * (TrapdoorClassificationTest): for EVERY trapdoor blockstate S and both targets o,
     * {@code withTrapdoorOpen(desc(S), o) == desc(S.setValue(OPEN, o))} <b>bit-for-bit</b> — the resolver
     * reproduces the interned real state exactly, so a planned {@code SET_OPEN}/{@code SET_CLOSED} edit
     * resolves to true geometry. Undefined for non-trapdoors — the caller applies it only to trapdoor cells.
     */
    public static long withTrapdoorOpen(long d, boolean open) {
        long base = d & ~(DERIVED_MASK | TRAPDOOR_OPEN_BIT | NARROW_TOP_BIT
                | ((long) TOP_Y_MASK << TOP_Y_SHIFT) | ((long) SHAPE_MASK << SHAPE_SHIFT));
        int topY, shapeClass;
        if (open) {
            base |= TRAPDOOR_OPEN_BIT | NARROW_TOP_BIT;
            topY = 16; shapeClass = SHAPE_OTHER;
        } else if ((d & TRAPDOOR_HALF_BIT) != 0) {
            topY = 16; shapeClass = SHAPE_OTHER;
        } else {
            topY = 3;  shapeClass = SHAPE_PARTIAL_LOW;
        }
        base |= (long) (topY & TOP_Y_MASK) << TOP_Y_SHIFT
              | (long) (shapeClass & SHAPE_MASK) << SHAPE_SHIFT;
        return withDerived(base);
    }
    // ---- Fence gates (DESIGN-fence-gates.md §2–§3) — meaningful only when isGate(d) ------------------
    /** Whether this cell is a {@link FenceGateBlock} — the gate on the fence-gate queries below (the
     *  fence-gate analog of {@link #isDoor} / {@link #isTrapdoor}). */
    public static boolean isGate(long d) { return openable(d) == OPEN_GATE; }
    /** Whether a fence gate is OPEN ({@code true}, {@code Shapes.empty()} — zero collision, passable air)
     *  or CLOSED ({@code false}, a centered 4/16-thick × 24/16-tall plate — a whole-cell blocker); reads
     *  the SHARED open bit (43). Gate on {@link #isGate} (doors/trapdoors share the bit via
     *  {@link #doorOpen} / {@link #trapdoorOpen}). Gates have NO blocked-face model (unlike
     *  {@link #doorBlockedEdge} / {@link #trapdoorBlockedFace}): closed blocks every crossing — the plate
     *  leaves 6/16 on each side, under the 0.6 body width — and open blocks none. */
    public static boolean gateOpen(long d) { return (d & GATE_OPEN_BIT) != 0; }
    /**
     * The same fence-gate descriptor forced into the {@code open} state — like {@link #withTrapdoorOpen}
     * (and unlike {@link #withDoorOpen}'s bare bit flip), a gate toggle CHANGES GEOMETRY, so this
     * RE-DERIVES it (DESIGN-fence-gates.md §2): open → topY 0 + {@code SHAPE_EMPTY} + bit 43, NARROW_TOP
     * cleared (empty shapes are never marked); closed → topY 24 + {@code SHAPE_OTHER} + NARROW_TOP (the
     * centered plate's 4/16 thin axis) − bit 43; then the derived predicate bits
     * (STANDABLE/BREAKABLE/OPEN_PLACE/COLLISION) via the same {@link #withDerived} the classifier uses —
     * an open result is passable with no collision, a closed result has collision, is breakable per
     * hardness/protected, and is never standable (topY 24 + narrow). Every non-geometry field (openable
     * kind, hand-toggleable, protected, hardness, tool, toolRequired, transit, damage, surface, climb,
     * gravity, replaceable, waterloggable, bubble, fallSoftness, reducedJump) carries over untouched.
     * Pinned by test (GateClassificationTest, the {@link #withTrapdoorOpen} contract): for EVERY
     * fence-gate blockstate S and both targets o, {@code withGateOpen(desc(S), o) ==
     * desc(S.setValue(OPEN, o))} <b>bit-for-bit</b>. Undefined for non-gates — the caller applies it only
     * to gate cells.
     */
    public static long withGateOpen(long d, boolean open) {
        long base = d & ~(DERIVED_MASK | GATE_OPEN_BIT | NARROW_TOP_BIT
                | ((long) TOP_Y_MASK << TOP_Y_SHIFT) | ((long) SHAPE_MASK << SHAPE_SHIFT));
        if (open) {
            base |= GATE_OPEN_BIT; // SHAPE_EMPTY + topY 0 are the cleared fields' zero values
        } else {
            base |= NARROW_TOP_BIT
                  | (long) (24 & TOP_Y_MASK) << TOP_Y_SHIFT
                  | (long) (SHAPE_OTHER & SHAPE_MASK) << SHAPE_SHIFT;
        }
        return withDerived(base);
    }
    /**
     * Unified SET-edit resolver for OPENABLE cells (DESIGN-trapdoors.md §2; gates DESIGN-fence-gates.md
     * §2): dispatches on the openable kind — a door is a bare bit flip ({@link #withDoorOpen}); a trapdoor
     * and a fence gate re-derive geometry ({@link #withTrapdoorOpen} / {@link #withGateOpen}). This is how
     * a planned {@code SET_OPEN}/{@code SET_CLOSED} edit resolves against the cell's own descriptor
     * regardless of kind. Undefined for non-openable cells — the caller applies it only to openable cells.
     */
    public static long withOpenableOpen(long d, boolean open) {
        return isTrapdoor(d) ? withTrapdoorOpen(d, open)
                : isGate(d) ? withGateOpen(d, open)
                : withDoorOpen(d, open);
    }
    // ---- Ladders (DESIGN-trapdoor-ladder-climb.md §2) — meaningful only when isLadder(d) -------------
    /**
     * Whether this cell is a {@link LadderBlock} — the gate on {@link #ladderFacing} (the ladder analog of
     * {@link #isStair} / {@link #isDoor} / {@link #isTrapdoor}, disambiguating the shared facing bits 8–9).
     * <b>Derived from existing bits, zero new bits:</b> {@code CLIMB ∧ SHAPE_OTHER} is unique to ladders
     * over the whole interned table — the climbable set is CLOSED (the 8-block identity list in
     * {@link #isClimbable(Block)}: ladder, scaffolding, the vine family), and within it the shapes diverge:
     * ladder = the 3/16 wall plate ({@code SHAPE_OTHER}), scaffolding = {@code SHAPE_FULL} (its null-context
     * stand-on-top shape), every vine = {@code SHAPE_EMPTY}. No non-climbable cell can satisfy the
     * conjunction (the CLIMB bit is only ever set for that list). Uniqueness is pinned by test over every
     * interned navtype, so a future climbable whose shape drifts into OTHER fails loudly instead of
     * silently reading a bogus facing.
     */
    public static boolean isLadder(long d) { return (d & CLIMB_BIT) != 0 && shape(d) == SHAPE_OTHER; }
    /** A ladder's HORIZONTAL_FACING ordinal (0=N 1=E 2=S 3=W); reads the shared facing field (bits 8–9).
     *  0 for non-ladders — always gate on {@link #isLadder} first (a stair/door/trapdoor uses the same bits
     *  via {@link #stairFacing} / {@link #doorFacing} / {@link #trapdoorFacing}). Consumed by the vanilla
     *  trapdoor-over-ladder climb rule: an OPEN trapdoor whose cell below is a ladder with EQUAL facing is
     *  climbable ({@code LivingEntity.trapdoorUsableAsLadder} — the {@code MovementContext.trapdoorClimbable}
     *  neighbor predicate; never a descriptor bit, the rule is neighbor-dependent). */
    public static int ladderFacing(long d) { return (int) (d >>> LADDER_FACING_SHIFT) & LADDER_FACING_MASK; }
    /** Fluid: 0 none, 1 water (incl. waterlogged), 3 lava (low bit = is-fluid, high bit = is-lava). */
    public static int fluid(long d)        { return (int) (d >>> FLUID_SHIFT) & FLUID_MASK; }
    /** Any fluid present (water or lava) — the low fluid bit. */
    public static boolean isFluid(long d)  { return (d & ((long) 1 << FLUID_SHIFT)) != 0; }
    /** Lava specifically — the high fluid bit. */
    public static boolean isLava(long d)   { return (d & ((long) 2 << FLUID_SHIFT)) != 0; }
    /**
     * The cell's fluid is a SOURCE ({@code FluidState.isSource()}, amount 8) — always lateral-spread-eligible
     * (DESIGN-fluid-flow-prediction.md §1.1/§3), the eligibility fact the flood funnel's tier 1 reads per
     * fluid neighbour.
     * <p><b>Waterlogged states carry this bit too</b>: a waterlogged block's {@code getFluidState()} IS a
     * water source (the fluid field already reads waterlogged as water), so the bit does NOT imply an open
     * water cell. The funnel is safe only because its tier-1 first clause excludes non-genuine-open fluid
     * cells before consulting sourcehood (§8.2); any consumer outside the funnel must gate on open geometry
     * itself, never on this bit alone.
     */
    public static boolean isFluidSource(long d)   { return (d & FLUID_SOURCE_BIT) != 0; }
    /**
     * The cell's fluid is at MINIMUM level ({@code fluidLevel(d) == 1}) — one more lateral step
     * yields amount 0, so the cell can never spread (the flood funnel's cheapest tier-1 continue,
     * DESIGN-fluid-flow-prediction.md §3/§5). Deliberately fluid-agnostic: exact for water and nether lava
     * (dropOff 1), merely conservative for overworld lava at amount 2 (assumed able to spread when it
     * cannot — errs wet, the safe direction for lava). Do not make it dimension-aware; the descriptor is
     * interned globally, not per-dimension.
     */
    public static boolean isFluidMinLevel(long d) { return fluidLevel(d) == 1; }
    /**
     * The cell's fluid AMOUNT, 1–8, or 0 when the cell holds no fluid — {@code FluidState.getAmount()} as
     * classified. A source is 8; so is FALLING flow, which is why sourcehood keeps its own bit
     * ({@link #isFluidSource}) instead of being folded in here.
     * <p><b>This is the fluid's OWN amount, not the surface height vanilla tests.</b> {@code
     * FlowingFluid.getHeight} returns {@code 1.0} whenever the SAME fluid occupies the cell directly above,
     * and only otherwise falls back to {@code amount / 9F}. A consumer reasoning about where the fluid's
     * surface actually sits — the eye-submersion test that gates starting a sprint-swim, above all — must
     * therefore consult the cell above as well; this field alone under-reports a stacked column. The height
     * is flat across the whole cell: the sloped surface is client-side rendering only
     * ({@code LiquidBlockRenderer}), so there is no sub-cell variation in server physics.
     * <p>Fluid-agnostic, like the sourcehood bit: exact for water and nether lava, conservative for
     * overworld lava (dropOff 2), and never dimension-aware — the descriptor is interned globally.
     */
    public static int fluidLevel(long d) {
        return isFluid(d) ? (int) ((d >>> FLUID_LEVEL_SHIFT) & FLUID_LEVEL_MASK) + 1 : 0;
    }
    /** Surface: 0 none, 1 slow (2–3 unused; the SLIPPERY class was removed 2026-08-10 — see the bit layout). */
    public static int surface(long d)      { return (int) (d >>> SURF_SHIFT) & SURF_MASK; }
    public static boolean isClimbable(long d)   { return (d & CLIMB_BIT) != 0; }
    public static boolean hasGravity(long d)    { return (d & GRAVITY_BIT) != 0; }
    public static boolean isDamaging(long d)    { return (d & DAMAGE_BIT) != 0; }
    public static boolean isReplaceable(long d) { return (d & REPLACE_BIT) != 0; }
    /**
     * A REDUCED-JUMP floor — the block's {@link Block#getJumpFactor()} is below 1.0 (honey block, 0.5, the
     * only vanilla case). Standing on it caps the jump apex at ~0.384 blocks, clearing nothing, so the
     * jump-takeoff movements (Ascend / Pillar / Parkour / DiagonalParkour) refuse to launch from such a
     * floor. A base identity field (packed in {@link #fingerprint}), read as one mask-and-test.
     */
    public static boolean reducesJump(long d)   { return (d & REDUCED_JUMP_BIT) != 0; }

    /**
     * Whether the cell's collision top is a NARROW POST — either XZ extent of the collision bounding box
     * under the bot's 0.6-block body width (bamboo with its position-hash offset, chain, end/lightning
     * rod, pointed dripstone, narrow décor; bit 51). Still {@link #isStandable standable} — a real floor
     * for walking, the grid's depth sweep, and the region tier — but the precision-LANDING moves
     * (Parkour/DiagonalParkour) refuse it as a jump target: the bot's whole body overhangs the support,
     * so the landing demands shaolin precision a human can't reasonably follow (owner ruling 2026-07-31).
     */
    public static boolean isNarrowTop(long d) { return (d & NARROW_TOP_BIT) != 0; }
    /** Quantized hardness: 255 = unbreakable, else round(destroyTime*5). */
    public static int hardness(long d)     { return (int) (d >>> HARD_SHIFT) & HARD_MASK; }
    /** Best-tool ordinal ({@link Tool}). */
    public static int tool(long d)         { return (int) (d >>> TOOL_SHIFT) & TOOL_MASK; }
    public static boolean toolRequired(long d)  { return (d & TOOLREQ_BIT) != 0; }
    /** Static: the block can be waterlogged (a bucket-clutch on it is absorbed). */
    public static boolean isWaterloggable(long d) { return (d & WLOGABLE_BIT) != 0; }
    /**
     * Through-slow class ({@link #TRANSIT_NONE} / {@link #TRANSIT_LIGHT} / {@link #TRANSIT_HEAVY}) — how
     * severely this cell slows a body moving THROUGH it (cobweb / berry bush / powder snow). Only ever
     * non-zero on passable cells; the floor-surface slow lives in {@link #surface}.
     */
    public static int transitSlow(long d)  { return (int) (d >>> TRANSIT_SHIFT) & TRANSIT_MASK; }
    /**
     * ANY teleport portal — nether portal, end portal, OR end gateway (the LOW bit of the 2-bit portal
     * field). Every such cell classifies {@link #SHAPE_EMPTY} (empty collision), so the raw geometry would
     * let a walker graze it and get teleported; the movement layer therefore SUBTRACTS this from its
     * body-occupancy gate ({@code MovementContext.passable}, mirroring the {@code isBubble} exclusion), so
     * the A* walker routes AROUND every portal and never occupies one mid-path. Distinct from {@link
     * #isNetherPortal}: the nether portal <i>index</i> / <i>follower</i> read that narrower bit to enter a
     * nether portal DELIBERATELY (an end portal must only ever be avoided, never chased). A base identity
     * field, not derived.
     */
    public static boolean isPortal(long d) { return (d & PORTAL_BIT) != 0; }
    /**
     * A NETHER portal specifically (the HIGH bit of the portal field ⇒ both bits set). The single reader the
     * portal discovery index ({@code worldmodel.pathing.NetherPortalIndex}) and {@code BotPortalFollower}
     * gate on — the follower enters nether portals on purpose, so it must not be fed end portals (which
     * carry {@link #isPortal} but not this bit). One mask-and-test on the already-loaded long.
     */
    public static boolean isNetherPortal(long d) { return (d & NETHER_PORTAL_BIT) != 0; }
    /**
     * An END portal or end gateway (any-portal set, nether NOT set — encoding {@code 01}). The cold reader
     * carries the negate; no live consumer needs it today (the walker avoids via {@link #isPortal}, the
     * follower enters via {@link #isNetherPortal}), but it names the third portal case for completeness/tests.
     */
    public static boolean isEndPortal(long d) { return (d & (PORTAL_BIT | NETHER_PORTAL_BIT)) == PORTAL_BIT; }
    /**
     * Owner-protected block ({@code mining.protectedBlocks}) — the bot must NEVER break it. A base
     * identity field applied post-init by {@link #applyProtected}. The derived {@link #isBreakable
     * BREAKABLE} bit already excludes protected cells, so the ordinary solid-break gates need no extra
     * test; only the gates that do NOT go through BREAKABLE (the passable break-through fold, the
     * unbreakable-opt-in arm) test this bit explicitly. One mask-and-test on an already-loaded long —
     * the hot path never touches a registry or the config list.
     */
    public static boolean isProtected(long d) { return (d & PROTECTED_BIT) != 0; }
    /**
     * Bubble-column drag direction: {@link #BUBBLE_NONE} (not a bubble) / {@link #BUBBLE_UP} (soul-sand
     * column, pushes up) / {@link #BUBBLE_DOWN} (magma column, drags down). A bubble column is water fluid +
     * empty shape, so it would otherwise read as swimmable — but its vertical push overrides swim depth
     * control, so {@link #isSwimmableWater} now treats it as impassable and the bot routes AROUND it. The
     * direction is reserved for a future RideBubbleColumn move (deliberately ride the column up/down).
     */
    public static int bubbleDir(long d) { return (int) (d >>> BUBBLE_SHIFT) & BUBBLE_MASK; }
    /** Whether this cell is a bubble column ({@link #bubbleDir} != 0) — impassable-to-swim (routed around);
     *  direction reserved for a future RideBubbleColumn move. */
    public static boolean isBubble(long d) { return bubbleDir(d) != 0; }
    /**
     * The LANDING fall-softness class ({@link #FALLSOFT_NONE}/{@link #FALLSOFT_HALF}/{@link #FALLSOFT_FIFTH}/
     * {@link #FALLSOFT_ZERO}) — the block's fall-damage class the {@code Fall} movement multiplies its
     * excess-fall damage BUDGET (survivable depth) and COST by (×1.0 / ×0.5 / ×0.2 / ×0.0). One
     * mask-and-shift on the already-loaded descriptor long. See {@link #fallSoftness(Block)} for the map. */
    public static int fallSoftness(long d) { return (int) (d >>> FALLSOFT_SHIFT) & FALLSOFT_MASK; }
    /**
     * Derived: water is present in this cell right now — a water source/flow <b>or</b> a <b>waterlogged
     * solid</b> (a waterlogged fence/stair has a water fluid state). This is the "would water flow if I edit
     * here" / fire-out / no-spawn concept; it is <b>NOT</b> "can the bot swim here" — a waterlogged fence holds
     * water but you cannot move through it. For swimming use {@link #isSwimmableWater}.
     */
    public static boolean isWaterloggedNow(long d) { return fluid(d) == FLUID_WATER; }
    /**
     * Derived: a full <b>water cell the bot can swim through</b> — water fluid AND no collision (empty shape).
     * The single source of truth for "swimmable" (the swim movements' {@code MovementContext.water} delegates
     * here). Distinct from {@link #isWaterloggedNow}: a waterlogged solid has water fluid but a collision shape,
     * so it is occupiable-as-water {@code false} (you can't float through a fence). Plants in water (kelp,
     * seagrass — empty shape, water fluid) ARE swimmable. Lava (fluid 3) is never swimmable.
     *
     * <p><b>Bubble columns are EXCLUDED</b> ({@link #isBubble}): a soul-sand/magma bubble column is water
     * fluid + empty shape, but its vertical push overrides the swim depth autopilot (ejects the bot up or
     * drags it under), so the planner treats it as impassable and routes AROUND it rather than swimming in.
     * The column's drag direction ({@link #bubbleDir}) is preserved in the descriptor, reserved for a future
     * RideBubbleColumn move that would deliberately ride the push.
     */
    public static boolean isSwimmableWater(long d) { return fluid(d) == FLUID_WATER && isPassable(d) && !isBubble(d); }

    /** A swimmable LAVA cell (lava fluid, empty shape) — the lava analog of {@link #isSwimmableWater}.
     *  The swim layer admits these with the hard-coded lava adjustments (extra damage + extra slow —
     *  s52b hazard-media, owner-ratified); lava remains excluded from {@code passable} (walk clearance)
     *  and from the prone sprint-swim family (no prone pose in lava — a 1x1 lava gap is impassable). */
    public static boolean isSwimmableLava(long d) { return fluid(d) == FLUID_LAVA && isPassable(d); }
    /** True if nothing collides here (air/plant/fluid). */
    public static boolean isPassable(long d) { return shape(d) == SHAPE_EMPTY; }
    /**
     * CONNECTIVITY-ONLY passability for the REGION tier's flood-fill (fragment membership + region
     * goal/seed resolution): geometrically {@link #isPassable passable}, OR any OPENABLE cell —
     * doors, trapdoors, fence gates, <b>including the iron variants the bot cannot hand-toggle</b> —
     * OR any CLIMBABLE cell ({@link #isClimbable}: ladder, scaffolding, the vine family).
     * Owner ruling 2026-08-09 (DESIGN-trapdoors.md §8b): an openable blocks at most 1 of its 6 faces
     * — "5/6 faces are passable — there's a chance you can path through it just fine — and if you
     * can't, we'll figure that out and invalidate." The region tier is deliberately optimistic;
     * unrealizable hops are absorbed by {@code RegionEdgeBlacklist} + the capsSig-keyed persisted
     * invalidations ({@code mayToggleDoors} is sig bit 3), which is why even an iron door reads as
     * open space here.
     *
     * <p><b>Climbable-as-air</b> (owner follow-up ruling 2026-08-09, DESIGN-trapdoor-ladder-climb.md
     * §6): "a ladder is passable on 5/6 faces, and the other face adjoins a wall by definition. You
     * can always walk past a ladder, and it's arguably MORE passable than air since you can climb up
     * and down it." Only the ladder (SHAPE_OTHER plate) and scaffolding (SHAPE_FULL) rows are newly
     * admitted — every vine is already SHAPE_EMPTY passable. Known asymmetry: this makes a flush
     * bare-ladder shaft region-CONNECTED in both directions, but the block tier still has no
     * bare-ladder TOP-ENTRY (a ladder plate is NARROW_TOP — the gap is tracked outside this arc), so
     * a top-down bare shaft region-connects and is then absorbed honestly by the blacklist while
     * bottom-up routes plan end to end.
     *
     * <p><b>NOT for the block tier</b> — movements keep exact geometry ({@link #doorBlockedEdge},
     * {@link #trapdoorBlockedFace}, the DESIGN-trapdoors.md §4 residual-clearance rules). The only
     * consumers are {@code FragmentLeafComputer}'s flood masks and {@code RegionGrid.goalDigSeeds}'
     * passable tests. Plain FENCES are not openable ({@code openable == NONE}) and correctly stay
     * walls; OPEN fence gates were already {@link #SHAPE_EMPTY} passable — the cells this predicate
     * newly admits are closed gates/doors/plates, open door/trapdoor panels, ladders, and scaffolds.
     */
    public static boolean floodPassable(long d) {
        return isPassable(d) || openable(d) != OPEN_NONE || isClimbable(d);
    }

    // ---- Precomputed predicate bits (see #withDerived) — a single mask-and-test on the hot path ------

    /** Can the bot stand on top of this cell? Solid-topped, no fluid, not damaging. */
    public static boolean isStandable(long d)    { return (d & STANDABLE_BIT) != 0; }
    /** Is this cell's geometry mineable? Solid, no fluid, not unbreakable, not owner-{@link #isProtected
     *  protected} (still gate on the bot's caps). */
    public static boolean isBreakable(long d)    { return (d & BREAKABLE_BIT) != 0; }
    /** Could a placed block fill this cell? Replaceable/empty, no fluid, and not owner-{@link #isProtected
     *  protected} — filling replaces (destroys) the occupant (still gate on the bot's caps). */
    public static boolean isOpenForPlace(long d) { return (d & OPEN_PLACE_BIT) != 0; }
    /** Does this cell have a solid face to build against? Solid, no fluid. */
    public static boolean hasCollision(long d)   { return (d & COLLISION_BIT) != 0; }
}
