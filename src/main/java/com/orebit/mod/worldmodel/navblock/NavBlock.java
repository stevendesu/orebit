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
 * states that pack to the same bits are the same navtype. Measured (profile.BlockStateDedupTest):
 * ~28k states collapse to a few hundred navtypes (≪ 65,536, so a {@code short} index fits with
 * ~100× headroom; the table is a few KB, L1-resident).
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
 *                           BOTH {@link StairBlock} states (via {@link #stairFacing}) AND {@link DoorBlock} states
 *                           (via {@link #doorFacing}) — a cell is a stair XOR a door (disambiguated by
 *                           {@link #isStair} / {@link #isDoor}), so the field is reused. On a BOTTOM stair the HIGH
 *                           16/16 half is on the FACING side, the LOW 8/16 front opposite (empirically verified,
 *                           StairVoxelProbe), so a walk-up reads as a +0.5 step-assist rather than a +1.0 jump.
 *   10      1   stairHalf   {@link StairBlock} HALF (0=bottom 1=top); 0 for non-stairs (incl. doors — a door does
 *                           NOT set this bit). A TOP stair's top surface is flat 16/16 everywhere, so it needs no
 *                           directional handling.
 *   11      1   portal      ANY teleport portal — LOW bit of a 2-bit field (mirrors {@code fluid}: low = is-portal,
 *                           high = is-nether). Base identity field, NOT derived. The walker routes AROUND any set cell.
 *   12      1   netherPortal HIGH bit of the portal field: 00 none / 01 end (end_portal + end_gateway) / 11 nether / 10 unused.
 *   13      1   doorHinge   {@link DoorBlock} DOOR_HINGE (1 = RIGHT, 0 = LEFT); 0 for non-doors. Colocated with
 *                           the geometry cluster; consumed only by {@link #doorBlockedEdge} to pick the OPEN door's
 *                           perpendicular blocked edge. (Formerly the remainder of the reclaimed sturdy-faces mask.)
 *   14–15   2   openable    0 none / 1 door / 2 trapdoor / 3 fence-gate
 *   16–17   2   fluid       00 none / 01 water / 11 lava (low bit = is-fluid, high = is-lava; water incl. waterlogged)
 *   18–19   2   surface     0 none / 1 slow / 2 slippery
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
 *   43      1   doorOpen    {@link DoorBlock} OPEN (1 = open, 0 = closed); 0 for non-doors. Colocated with the
 *                           movement-impact cluster; consumed by {@link #doorBlockedEdge} (open ⇒ perpendicular
 *                           edge, closed ⇒ opposite edge). (Formerly the nether-portal bit — the portal field moved
 *                           down to bits 11–12, widened to a 2-bit any-portal / is-nether field.)
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
 *   50      1   doorToggleable a door the bot can OPEN/CLOSE BY HAND (wood + copper) vs a redstone-only iron
 *                           door (DOORS P2). Set for every {@link DoorBlock} except {@link Blocks#IRON_DOOR}
 *                           (the {@code Blocks.*} idiom of the door/portal/bubble reads — range-stable, no
 *                           platform seam; copper doors, added in 1.21, are non-iron and hand-openable, so the
 *                           test needs no version gate — pre-1.21 the non-iron door set is simply the wood
 *                           species). Meaningful only when {@link #isDoor}. Consumed by the planner's
 *                           prefer-open-over-smash SET fold (an iron door has no SET option → break/route as
 *                           in P1). Bits 51–63 remain wholly unused.
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
    private static final int SURFACE_NONE = 0, SURFACE_SLOW = 1, SURFACE_SLIPPERY = 2;
    private static final int OPEN_NONE = 0, OPEN_DOOR = 1, OPEN_TRAPDOOR = 2, OPEN_GATE = 3;

    // ---- Transit-slow class (2 bits, 41–42): moving THROUGH a passable cell is slowed ---------
    /** No through-slow: the cell doesn't impede a body moving through it. */
    public static final int TRANSIT_NONE  = 0;
    /** Mild through-slow (~0.75× speed): sweet berry bush, powder snow. */
    public static final int TRANSIT_LIGHT = 1;
    /** Severe through-slow (~0.05× speed): cobweb — near-stops the body, planner should route/mine around. */
    public static final int TRANSIT_HEAVY = 2;

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
    // Bits 8–10 hold the stair facing (2) + half (1), populated ONLY for StairBlock states (0 elsewhere, so
    // only stairs split navtypes); bits 11–12 hold the 2-bit portal field (any-portal / is-nether, see below);
    // bit 13 remains FREE (the rest of the reclaimed 6-bit sturdy-faces mask, unread by pathfinding — see the
    // block-fingerprints doc). Bit 43 is now also free (the portal marker moved down from it into 11–12).
    private static final int TOP_Y_SHIFT = 0,  TOP_Y_MASK = 0x1F;
    private static final int SHAPE_SHIFT = 5,  SHAPE_MASK = 0x07;
    private static final int STAIR_FACING_SHIFT = 8, STAIR_FACING_MASK = 0x03; // 0=N 1=E 2=S 3=W (stairs AND doors)
    private static final long STAIR_HALF_BIT = 1L << 10;                       // 0=bottom 1=top (stairs only)
    // Doors reuse the bits-8–9 facing field (a cell is stair XOR door). Hinge (bit 13) + open (bit 43) complete
    // the directional-solidity encoding; the blocked edge is DERIVED at query time (see #doorBlockedEdge), never
    // precomputed. DOOR_FACING_SHIFT/_MASK alias the stair facing field deliberately (same 2-bit HORIZONTAL_FACING).
    private static final int DOOR_FACING_SHIFT = STAIR_FACING_SHIFT, DOOR_FACING_MASK = STAIR_FACING_MASK;
    private static final long DOOR_HINGE_BIT = 1L << 13; // 1 = RIGHT hinge, 0 = LEFT (doors only)
    private static final long DOOR_OPEN_BIT  = 1L << 43; // 1 = open, 0 = closed (doors only)
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
    private static final long DOOR_TOGGLEABLE_BIT = 1L << 50;           // hand-openable door (wood/copper), not iron

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

        int surface = isSlippery(block) ? SURFACE_SLIPPERY
                : isSlow(block) ? SURFACE_SLOW
                : SURFACE_NONE;

        float destroyTime = block.defaultDestroyTime();
        int hardness = destroyTime < 0 ? 255 : clamp(Math.round(destroyTime * 5f), 0, 254);

        Tool tool = hardness > 1 ? bestTool(state) : Tool.NONE; // weak blocks just use hands
        boolean toolRequired = hardness > 1 && state.requiresCorrectToolForDrops();

        long d = 0L;
        d |= (long) (topY & TOP_Y_MASK) << TOP_Y_SHIFT;
        d |= (long) (shapeClass & SHAPE_MASK) << SHAPE_SHIFT;
        d |= (long) (openable & OPEN_MASK) << OPEN_SHIFT;
        d |= (long) (fluid & FLUID_MASK) << FLUID_SHIFT;
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
            if (block != Blocks.IRON_DOOR) d |= DOOR_TOGGLEABLE_BIT;
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
        if (solid && noFluid && (shape != SHAPE_OTHER || topY(d) <= 16))     d |= STANDABLE_BIT;
        if (solid && noFluid && hardness(d) != 255 && !isProtected(d))       d |= BREAKABLE_BIT;
        // OPEN_PLACE also excludes protected: filling the cell would REPLACE (destroy) its occupant — a
        // protected bush/grass must not be cleared by a placement any more than by a punch. (Protected
        // air is a degenerate config nobody should write; it would just make the cell unfillable.)
        // FLUIDS ARE OPEN (owner ruling, s52b): water and lava are vanilla-replaceable — placing into
        // them is completely valid (sealing a lava source with cobble is a standard technique). The old
        // noFluid conjunct wrongly barred every fluid cell. (NOTE: the NavFlags RISKY_EDIT fold gate
        // still refuses edits whose body space borders fluid — a separate, break-motivated guard.)
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
            boolean standable = solid && noFluid && (shape != SHAPE_OTHER || topY(d) <= 16);
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

    private static boolean isSlippery(Block block) {
        return block == Blocks.ICE || block == Blocks.PACKED_ICE
                || block == Blocks.BLUE_ICE || block == Blocks.FROSTED_ICE;
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
     * 16/16 half sits, StairVoxelProbe) and doors (the facing that {@link #doorBlockedEdge} rotates from). Every
     * {@link StairBlock} and {@link DoorBlock} carries HORIZONTAL_FACING on every supported version.
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
     */
    private static int transitSlow(Block block) {
        if (block == Blocks.COBWEB) return TRANSIT_HEAVY;
        if (block == Blocks.SWEET_BERRY_BUSH || block == Blocks.POWDER_SNOW) return TRANSIT_LIGHT;
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
     * states had distinct base navtypes (typically a handful per entry against the ~587/1024 measured
     * headroom). Old navtypes never disappear (grid data may still reference them); a re-protect after an
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
    /** Stair HALF: 0 = bottom (directional surface), 1 = top (flat 16/16 top — no directional handling). */
    public static int stairHalf(long d)    { return (d & STAIR_HALF_BIT) != 0 ? 1 : 0; }
    /** Openable kind: 0 none, 1 door, 2 trapdoor, 3 fence-gate. */
    public static int openable(long d)     { return (int) (d >>> OPEN_SHIFT) & OPEN_MASK; }

    // ---- Door directional solidity (P0) — meaningful only when isDoor(d) ----------------------
    /** Whether this cell is a {@link DoorBlock} (any material incl. iron) — the gate on the door queries below. */
    public static boolean isDoor(long d)   { return openable(d) == OPEN_DOOR; }
    /** A door's HORIZONTAL_FACING ordinal (0=N 1=E 2=S 3=W); reads the shared facing field (bits 8–9). 0 for
     *  non-doors — always gate on {@link #isDoor} first (a stair uses the same bits via {@link #stairFacing}). */
    public static int doorFacing(long d)   { return (int) (d >>> DOOR_FACING_SHIFT) & DOOR_FACING_MASK; }
    /** A door's DOOR_HINGE side: {@code true} = RIGHT, {@code false} = LEFT (bit 13). 0 for non-doors. */
    public static boolean doorHinge(long d){ return (d & DOOR_HINGE_BIT) != 0; }
    /** Whether a door is OPEN ({@code true}) or CLOSED ({@code false}) (bit 43). 0 for non-doors. */
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
     * Whether a door can be opened/closed BY HAND (wood + copper), vs a redstone-only iron door (bit 50, DOORS
     * P2). {@code false} for non-doors — gate on {@link #isDoor} first. The planner offers a cheap OPEN/CLOSE
     * SET edit only for toggleable doors; an iron door has no SET option and falls through to break/route.
     */
    public static boolean doorToggleable(long d) { return (d & DOOR_TOGGLEABLE_BIT) != 0; }
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
    /** Fluid: 0 none, 1 water (incl. waterlogged), 3 lava (low bit = is-fluid, high bit = is-lava). */
    public static int fluid(long d)        { return (int) (d >>> FLUID_SHIFT) & FLUID_MASK; }
    /** Any fluid present (water or lava) — the low fluid bit. */
    public static boolean isFluid(long d)  { return (d & ((long) 1 << FLUID_SHIFT)) != 0; }
    /** Lava specifically — the high fluid bit. */
    public static boolean isLava(long d)   { return (d & ((long) 2 << FLUID_SHIFT)) != 0; }
    /** Surface: 0 none, 1 slow, 2 slippery. */
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
