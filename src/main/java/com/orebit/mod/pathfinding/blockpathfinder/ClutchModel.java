package com.orebit.mod.pathfinding.blockpathfinder;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The <b>clutch</b> vocabulary: the five blocks a bot can PLACE INTO ITS OWN LANDING CELL, mid-drop, to
 * survive a fall {@link com.orebit.mod.pathfinding.blockpathfinder.movements.Fall} would otherwise refuse
 * (MOVEMENT-DESIGN.md §2, the deep-drop branch) — of which <b>four are currently offered</b>; {@link #BED}
 * is modelled in full but withheld from {@link #PREFERENCE}, see below. This class is the single authority
 * on <i>which</i> kinds exist, <i>where the bot ends up</i> after landing on each ({@link #landsOnTop}),
 * <i>how much</i> damage each leaves, and <i>what</i> each costs the follower to execute — pure
 * integer/float math and static tables over an {@code int kind}, with <b>no world access and no
 * allocation</b>, so {@link com.orebit.mod.pathfinding.blockpathfinder.movements.Fall} can consult it from
 * inside a candidate scan.
 *
 * <p><b>Why a clutch is priceable at all.</b> {@code Fall} already models a landing's absorption via
 * {@link com.orebit.mod.worldmodel.navblock.NavBlock#fallSoftness} — the multiplier {@code m} the block
 * ALREADY on the ground applies. A clutch is the same physics with the block supplied from inventory
 * instead of from the terrain: the residual damage is {@code (depth − safeFall) × m} exactly as for a
 * resident soft landing, so a clutch slots into the existing acceptance test rather than bypassing it. What
 * differs is (a) feasibility now depends on what the bot CARRIES (the {@code clutchMask} in
 * {@code MovementContext.InventoryView}), and (b) one kind — {@link #BED} — scales the DISTANCE rather than
 * the damage, which is a genuinely different curve and the reason {@link #scalesDistance} exists.
 *
 * <p><b>The five kinds and the 1.21.11 bytecode behind each</b> (Mojang-mapped; all damage figures follow
 * vanilla {@code Entity.causeFallDamage} → {@code damage = floor((fallDistance + 1e-6 − 3.0) × multiplier)},
 * whose default {@code Block.fallOn} multiplier is {@code 1.0}):
 * <ul>
 *   <li><b>{@link #WATER}</b> — not a {@code fallOn} override at all. {@code LivingEntity.checkFallDamage}
 *       calls {@code updateInWaterStateAndDoFluidPushing()} FIRST, which calls {@code resetFallDistance()}
 *       when the post-move AABB overlaps water, and only THEN calls {@code Entity.checkFallDamage} where
 *       {@code fallOn} applies damage. The reset lands strictly before the impact, off the FINAL position —
 *       so water is damage-free from ANY height, multiplier {@code 0}. This is the same mechanism
 *       {@code Fall}'s resident water cushion already rides. Its one restriction is dimensional: an
 *       ultrawarm dimension evaporates a placed water source, hence {@link #needsNonEvaporating}.</li>
 *   <li><b>{@link #POWDER_SNOW}</b> — {@code PowderSnowBlock.getCollisionShape} returns a SOLID 0.9-tall box
 *       whenever {@code entity.fallDistance > 2.5}, so a genuinely falling bot lands ON the block rather than
 *       sinking through it, and {@code PowderSnowBlock.fallOn} only plays a sound — it never calls
 *       {@code causeFallDamage}. Multiplier {@code 0}. Below {@code fallDistance = 2.5} the bot does sink
 *       through, but 2.5 is under the 3.0 damage threshold anyway, so the sink case is damage-free for a
 *       different reason and needs no separate branch. Works in EVERY dimension — it is a block, not a
 *       fluid, so nothing evaporates it.</li>
 *   <li><b>{@link #SLIME}</b> — {@code SlimeBlock.fallOn} calls {@code causeFallDamage(d, 0.0f)} when the
 *       entity is not sneaking: multiplier {@code 0}, damage-free from any height. It also BOUNCES; see
 *       {@link #restitution} and the sneak-divergence note below.</li>
 *   <li><b>{@link #HAY}</b> — {@code HayBlock.fallOn} calls {@code causeFallDamage(d, 0.2f)}. This is
 *       MULTIPLIER scaling, matching the model exactly: the vanilla 3.0 threshold still applies to the raw
 *       distance and only the resulting damage is scaled. ({@code HoneyBlock.fallOn} is the same 0.2f, but
 *       honey is not a carried clutch — it is only ever encountered as terrain, where
 *       {@code NavBlock.fallSoftness} already handles it.)</li>
 *   <li><b>{@link #BED}</b> — {@code BedBlock.fallOn} calls {@code causeFallDamage(d × 0.5, 1.0f)}. That is
 *       DISTANCE scaling, and it is why the bed is the weak one; see below.</li>
 * </ul>
 *
 * <p><b>Why BED is the weak one.</b> Because the bed halves the DISTANCE before the vanilla 3.0 threshold is
 * subtracted, its residual is {@code 0.5×d − 3} rather than {@code (d − 3)×m}. Consequences, with the
 * default {@code safeFall = 3}:
 * <ul>
 *   <li>Damage-free only while {@code 0.5d − 3 < 1}, i.e. {@code d < 8}. Hay's free window is
 *       {@code (d − 3)×0.2 < 1}, i.e. {@code d < 8} as well — the two curves CROSS exactly at the depth
 *       {@code 0.5d − s = (d − s)×0.2 ⇒ d = (8/3)s}, which is 8 for {@code s = 3}.</li>
 *   <li>Past that crossing the bed degrades 2.5× faster (0.5 residual per block vs hay's 0.2). At
 *       {@code d = 20} the bed leaves 7.0 HP-blocks and hay 3.4; at {@code d = 40}, 17.0 vs 7.4.</li>
 *   <li>Absolute survivability on a 20 HP bot: {@code floor(0.5×45 − 3) = 19} (survives with 1 HP) but
 *       {@code floor(0.5×46 − 3) = 20} (lethal), so a bed tops out near {@code d = 46}. Hay reaches
 *       {@code floor((98 − 3)×0.2) = 19}, roughly twice as far.</li>
 * </ul>
 * A bed is therefore <b>NOT an any-height clutch</b> the way water, powder snow and slime are — it is a
 * finite-budget absorber {@link #best} would have to gate on the actual depth, which is exactly what
 * {@link #damageBlocks} feeding the budget test does (and would keep doing unchanged if the bed were ever
 * put back into {@link #PREFERENCE}). It also carries two execution
 * costs the others do not: it BOUNCES ({@link #restitution} 0.66) and it is a two-cell multiblock
 * ({@link #footprint} 2 — placement needs two horizontally adjacent free cells, so the follower cannot
 * clutch a bed into a 1-wide shaft).
 *
 * <p><b>Landing geometry splits the kinds in two — {@link #landsOnTop}.</b> A clutch does not just absorb
 * damage; it decides WHICH CELL the bot comes to rest in, and therefore which node {@code Fall} may emit and
 * whether a {@code PathEdits.PLACED} belongs on the step at all:
 * <ul>
 *   <li><b>Sink-through</b> ({@link #WATER}, {@link #POWDER_SNOW}) — the placed block occupies the landing
 *       FEET cell {@code fy+1} and the bot settles standing on the PRE-EXISTING floor at {@code fy}. The
 *       emitted node is {@code (nx, fy, nz)} and the drop depth is unchanged. These kinds fold <b>no</b>
 *       geometry edit: the floor they land on was already standable, and folding a place at the feet cell
 *       would make the node read its own BODY cell as solid — a self-blocking dead end the search would
 *       expand into and never leave.</li>
 *   <li><b>Lands-on-top</b> ({@link #SLIME}, {@link #HAY}) — the placed block BECOMES the floor at
 *       {@code fy+1} and the bot stands on it. The emitted node is {@code (nx, fy+1, nz)}, feet {@code fy+2},
 *       and the effective drop is one block SHORTER. These kinds DO fold a place at {@code fy+1}, which is
 *       correct and not self-blocking precisely because that cell is the floor, not a body cell.</li>
 * </ul>
 * Powder snow sits on the sink-through side despite being SOLID during the fall, and the reason is a timing
 * one: {@code PowderSnowBlock.getCollisionShape} only returns its 0.9-tall box while
 * {@code entity.fallDistance > 2.5}, and the landing itself resets {@code fallDistance}, after which the
 * shape is empty again and the bot sinks in to rest on the original floor. Modelling it as lands-on-top
 * would leave the planner expecting a floor one block higher than the bot actually occupies a tick later.
 *
 * <p><b>Why {@link #BED} is withheld from {@link #PREFERENCE} this pass.</b> The physics above is correct and
 * stays tabulated, but the EXECUTOR cannot place a bed safely across 1.17.1..26.2, so offering the kind would
 * hand {@code Fall} a landing the follower must then fail:
 * <ul>
 *   <li><b>Multiblock placement.</b> A bed is head + foot, and {@code BedBlock.updateShape} returns
 *       {@code Blocks.AIR.defaultBlockState()} whenever the matching half is absent. Two independent
 *       {@code placeBlock} calls therefore survive only if the shape updates fire in an order that leaves
 *       both halves present at every intermediate step — ordering this class cannot assert, and which the
 *       {@code updateShape} signature itself churns across the range (it gained
 *       {@code ScheduledTickAccess}/{@code RandomSource} parameters by 1.21.11, javap-verified).</li>
 *   <li><b>The block identity does not exist on 26.x.</b> {@code Blocks.RED_BED} is a plain {@code Block}
 *       field through 1.21.11 but is GONE on 26.2, where the dyed beds collapse into a single
 *       {@code Blocks.BED} of type {@code ColorCollection<Block>} — the same collapse
 *       {@code overlays/26}'s {@code ConcretePowder} handles. A direct reference breaks the common
 *       source's multi-version compile gate, which is why {@link #blockState} returns {@code null} for
 *       {@link #BED} rather than naming a bed block.</li>
 * </ul>
 * Re-enabling the bed needs two things this pass does not build: a {@code platform/} seam for the bed
 * identity (per-era, exactly as {@code ConcretePowder} does it) and a real multiblock place in the executor
 * that sets both halves atomically. Every bed row in every table here is deliberately left INTACT — damage
 * multiplier, distance scaling, footprint 2, restitution 0.66, reclaimable, {@code landsOnTop} false — so
 * that re-enabling it is a one-line change: put {@link #BED} back into {@link #PREFERENCE}.
 *
 * <p><b>The 1.21.2 slime sneak divergence, and why no overlay is needed.</b> {@code SlimeBlock.fallOn}
 * branches on sneaking. Not-sneaking is {@code causeFallDamage(d, 0.0f)} — zero damage plus a bounce — on
 * every supported version. SNEAKING diverges: on 1.21.2+ it returns immediately (zero damage, no bounce),
 * but on 1.21.1 and older it falls through to {@code super.fallOn}, which is FULL damage. A follower that
 * "sneaks to cancel the bounce" on the impact tick would therefore be safe on new versions and would
 * <b>kill the bot on old ones</b>. The ratified servo sidesteps the branch entirely: <i>allow the bounce</i>
 * (not-sneaking, which is the zero-damage path on BOTH sides), then sneak only once the predicted rebound
 * apex — {@link #bounceApex} — is already within {@code safeFallDistance}. At that point the remaining drop
 * is harmless whichever {@code fallOn} branch runs, so the behaviour is correct on both sides of 1.21.2 with
 * ONE code path and no {@code overlays/} entry. Recording this here because the temptation to add a
 * version-selected sneak rule will recur.
 *
 * <p><b>Reach and reclaim.</b> Block interaction reach is 4.5 blocks by default and air terminal velocity is
 * 3.92 b/t, so the target cell is within reach for at least one whole tick on any drop — attempting the
 * place every tick is provably sufficient and needs no lead-time model. Every kind is
 * {@linkplain #reclaimable reclaimable} afterwards, so a clutch costs the bot time rather than the item:
 * water and powder snow are scooped back with the same bucket ({@code WaterFluid.getTickDelay = 5}, so a
 * source reclaimed within 5 ticks never spreads and leaves no mess), and slime, hay and bed are mined back.
 *
 * <p><b>Shape.</b> Every per-kind property is a {@code static final} primitive array indexed by the kind
 * ordinal — never a {@code switch} — so a lookup is one bounds-checked array load the JIT folds into the
 * caller. {@code Fall} calls {@link #best} only inside its {@code depth > safeFall} branch, so this is
 * cold-ish code, but it still allocates nothing: {@link #best} walks a static preference table and
 * {@link #bounceApex} is a scalar loop over doubles. {@link #blockState} is the one member that touches the
 * MC registries, and it is behind a lazy holder so merely CLASSLOADING {@code ClutchModel} never requires
 * {@code Bootstrap.bootStrap()} to have run (headless benchmarks and unit tests touch this class early).
 *
 * @see com.orebit.mod.pathfinding.blockpathfinder.movements.Fall
 */
public final class ClutchModel {

    // ---- Kind ordinals ------------------------------------------------------------------------------
    //
    // Dense from 0 so every property table is a plain array and bit(k) is a shift. The ORDER here is the
    // storage order, NOT the preference order — see PREFERENCE.

    /** Water source from a bucket. Damage-free from any height; refused where the dimension evaporates it. */
    public static final int WATER = 0;
    /** Powder snow from a bucket. Damage-free (solid collision above {@code fallDistance 2.5}); any dimension. */
    public static final int POWDER_SNOW = 1;
    /** Slime block. Damage-free from any height, and bounces at full restitution. */
    public static final int SLIME = 2;
    /** Hay bale. Multiplier 0.2 — the cheapest carried absorber, and the deepest-reaching of the two lossy ones. */
    public static final int HAY = 3;
    /**
     * Bed. Distance-scaled (0.5×d), bounces at 0.66, and occupies TWO cells — the weak one. <b>Modelled but
     * NOT offered:</b> it is absent from {@link #PREFERENCE}, so {@link #best} can never return it and
     * {@link #blockState} returns {@code null} for it. Tables are intact for a one-line re-enable; see the
     * class doc for the executor and 26.x-identity reasons.
     */
    public static final int BED = 4;
    /** Number of kinds; also the width of the {@code clutchMask} bit set. */
    public static final int COUNT = 5;

    /** "No clutch qualifies" — the {@link #best} miss result. Never a valid index into any table here. */
    public static final int NONE = -1;

    // ---- Per-kind property tables (index = kind ordinal) --------------------------------------------

    /**
     * The vanilla {@code causeFallDamage} multiplier each kind's {@code fallOn} passes. Zero for the three
     * any-height absorbers (water's pre-impact {@code resetFallDistance}, powder snow's sound-only override,
     * slime's explicit {@code 0.0f}), {@code 0.2f} for hay, {@code 0.5f} for the bed. <b>The bed's 0.5 is
     * recorded here for completeness but is NOT the multiplier its damage formula uses</b> — the bed scales
     * the distance, so {@link #damageBlocks} routes it through {@link #scalesDistance} instead. Anything
     * reading this array must consult {@code scalesDistance} first or it will misprice a bed.
     */
    private static final float[] MULTIPLIER = {0f, 0f, 0f, 0.2f, 0.5f};

    /**
     * Whether the kind scales the fall DISTANCE rather than the resulting DAMAGE. True for {@link #BED}
     * alone ({@code BedBlock.fallOn} → {@code causeFallDamage(d × 0.5, 1.0f)}); every other kind hands the
     * raw distance to a scaled multiplier. The distinction moves the vanilla 3.0 threshold to the far side
     * of the scaling and is what makes the bed's curve steeper past {@code d = (8/3)×safeFall}.
     */
    private static final boolean[] SCALES_DISTANCE = {false, false, false, false, true};

    /**
     * Horizontally adjacent free cells the placement consumes. One for every kind except the {@link #BED},
     * whose {@code BedBlock} is a two-part multiblock (head + foot). A footprint of 2 makes a bed
     * unplaceable in a 1-wide shaft, so the caller must check this against the landing cell's neighbours
     * before committing — this class only reports the requirement, it cannot see the world.
     */
    private static final int[] FOOTPRINT = {1, 1, 1, 1, 2};

    /**
     * Whether the kind requires a dimension that does not evaporate placed water. True for {@link #WATER}
     * alone: an ultrawarm dimension (the Nether) destroys a placed water source instantly, so a water bucket
     * is not a clutch there. Powder snow is a BLOCK rather than a fluid and is unaffected — it works in every
     * dimension, which is precisely why it is carried in the mask separately rather than folded in with water.
     */
    private static final boolean[] NEEDS_NON_EVAPORATING = {true, false, false, false, false};

    /**
     * Whether landing on the kind rebounds the bot. {@code SlimeBlock} and {@code BedBlock} both route
     * {@code updateEntityAfterFallOn} into {@code bounceUp}, which negates the Y component of
     * {@code getDeltaMovement}; the other three do not override it and simply stop the bot. A bounce is not a
     * damage concern (the impact itself is already priced by {@link #damageBlocks}) but it IS a follower
     * concern: the rebound is a second, uncontrolled fall the servo has to land, so a bouncing clutch needs
     * {@link #bounceApex} before it can decide when to sneak.
     */
    private static final boolean[] BOUNCES = {false, false, true, false, true};

    /**
     * The Y-velocity reversal factor {@code bounceUp} applies. {@code SlimeBlock} passes {@code 1.0} for a
     * {@code LivingEntity} (0.8 only for non-living entities, which the bot is not) — a full-energy rebound.
     * {@code BedBlock} passes {@code 0.66}. Zero for the three non-bouncing kinds so {@link #bounceApex}
     * needs no special case: a zero rebound velocity falls straight out of its loop on the first iteration.
     */
    private static final float[] RESTITUTION = {0f, 0f, 1.0f, 0f, 0.66f};

    /**
     * Whether the block can be recovered after the landing. True for all five, which is the whole reason a
     * clutch is priced as TIME rather than as a consumed resource: water and powder snow are scooped back
     * into the same bucket, and slime, hay and bed are mined back. Retained as a table (rather than assumed)
     * because a future clutch kind — a placed-and-lost consumable — would be the exception, and the callers
     * that price reclaim time should already be asking.
     */
    private static final boolean[] RECLAIMABLE = {true, true, true, true, true};

    /**
     * Whether the bot comes to rest ON TOP of the placed block rather than sinking through it into the cell
     * the block occupies. True for {@link #SLIME} and {@link #HAY}; false for {@link #WATER},
     * {@link #POWDER_SNOW} and {@link #BED}. This is the geometry switch described in the class doc, and it
     * decides three things at once for the caller ({@code Fall}): which node the landing produces, how deep
     * the drop effectively is, and whether a place edit may be folded onto the step.
     *
     * <p><b>Lands-on-top = true</b> ({@link #SLIME}, {@link #HAY}). The block is placed at {@code fy+1} and
     * BECOMES the floor there; the node is {@code (nx, fy+1, nz)} with feet at {@code fy+2} and the effective
     * drop is one block shorter than the raw distance to {@code fy}. The step folds a place at {@code fy+1},
     * which is sound because that cell is the node's FLOOR — a solid floor is what the node needs, not what
     * blocks it.
     *
     * <p><b>Lands-on-top = false</b> ({@link #WATER}, {@link #POWDER_SNOW}). The placed block occupies the
     * landing FEET cell {@code fy+1} and the bot ends up standing on the pre-existing floor at {@code fy}:
     * node {@code (nx, fy, nz)}, depth unchanged, and <b>no place edit is folded</b>. The floor was already
     * standable so there is nothing to add, and folding {@code PathEdits.PLACED} at {@code fy+1} would make
     * the node read its own body cell as solid cobblestone-equivalent geometry, turning the landing into a
     * self-blocking dead end that the search expands and cannot leave.
     *
     * <p><b>Why powder snow is false despite being SOLID during the fall.</b>
     * {@code PowderSnowBlock.getCollisionShape} returns its 0.9-tall box only while
     * {@code entity.fallDistance > 2.5}. The landing is what RESETS {@code fallDistance}, so from the very
     * next evaluation the shape is empty again and the bot sinks in and rests on the original floor. Treating
     * it as lands-on-top would leave the planner believing in a floor one block above where the bot actually
     * is, and every subsequent move off that node would be planned from the wrong Y.
     *
     * <p>{@link #BED} is false as a placeholder value consistent with the physics ({@code BedBlock} has a
     * 0.5625-tall collision box, so a bed landing is genuinely on-top-ish) — but the value is unreachable
     * today because the bed is not in {@link #PREFERENCE}. If the bed is ever re-enabled, this entry MUST be
     * revisited alongside the executor's multiblock place; it is not a settled answer.
     */
    private static final boolean[] LANDS_ON_TOP = {false, false, true, true, false};

    /**
     * {@link #best}'s search order: <b>HAY, SLIME, POWDER_SNOW, WATER</b> — most expendable item first, so
     * the bot spends a hay bale before a water bucket whenever either would do. This deliberately tries the
     * one LOSSY absorber ahead of the three any-height ones, which is safe because {@code best} returns a
     * kind only after {@link #damageBlocks} has proved it fits the caller's budget: trying hay first can
     * never accept an unsurvivable landing, it can only avoid burning a scarcer item on a shallow drop. The
     * tail order encodes item scarcity rather than physics — powder snow ahead of water because a powder
     * snow bucket is a single-purpose item while a water bucket is the bot's most generally useful tool.
     *
     * <p><b>{@link #BED} is deliberately absent</b>, which is the ONLY reason {@link #best} can never return
     * it. The bed's rows in every other table are intact; the class doc gives the full argument (a two-cell
     * multiblock the executor cannot place safely across 1.17.1..26.2, plus {@code Blocks.RED_BED} not
     * existing on 26.x). Re-enabling it is this one line — restore {@code BED} between {@code HAY} and
     * {@code SLIME}, its scarcity slot — once a {@code platform/} bed-identity seam and an atomic two-half
     * place exist. Nothing else in this class needs to change.
     *
     * <p><b>{@link #SLIME} is absent too, for a FOLLOWER reason rather than a physical one</b> — worth stating
     * because the physics all works: slime is the best absorber in the table (multiplier 0, damage-free from
     * any height, and unlike water it needs no dimension gate). What it cannot yet do is FINISH. A clutch is
     * complete only once the block is reclaimed, and the reclaim survives only if it lands on the SAME tick as
     * the landing: {@code Movement.reached} is {@code settled() && atWaypoint()}, which {@code BotNavigator}
     * uses to advance the waypoint cursor independently of the phase plan — its own comment says whatever the
     * remaining phases owed is "dropped on the floor". Water, powder snow and hay all stop dead, so their
     * reclaim runs inside the landing tick's drive and {@code done}/{@code reached} flip together. Slime
     * BOUNCES: the bot is grounded at the waypoint (so {@code reached} fires) while the servo is still waiting
     * for the rebound apex to fall inside the safe window, so the cursor would advance MID-BOUNCE — dropping
     * the reclaim and starting the next move on a bot about to be launched back into the air. Re-enabling
     * needs a way for a move to hold the cursor past arrival ({@link
     * com.orebit.mod.pathfinding.blockpathfinder.Movement#commitsAcrossArrival} is the goal-ARRIVAL preempt, a
     * different gate, and does not cover this). Slime's table rows and Fall's whole bounce servo stay, so this
     * is a one-line change once that seam exists.
     */
    private static final int[] PREFERENCE = {HAY, POWDER_SNOW, WATER};

    // ---- Vanilla air recurrence (shared with Fall's WATER_PENETRATION derivation) --------------------

    /** Per-tick gravity applied to a free entity before drag ({@code Attributes.GRAVITY}, 0.08 b/t²). */
    private static final double AIR_GRAVITY = 0.08;
    /** Per-tick vertical drag in air ({@code LivingEntity.travelInAir} — the Y factor is hardcoded 0.98). */
    private static final double AIR_DRAG = 0.98;

    private ClutchModel() {
    }

    // ---- Per-kind accessors -------------------------------------------------------------------------

    /**
     * The {@code clutchMask} bit for a kind — {@code 1 << kind}. The mask carried on
     * {@code MovementContext.InventoryView} is the OR of this over every kind the bot actually CARRIES and
     * that is usable in the search's dimension, so a mask test answers "can I clutch with this?" without
     * re-deriving either fact per node.
     */
    public static int bit(int kind) {
        return 1 << kind;
    }

    /**
     * The vanilla {@code causeFallDamage} multiplier for the kind — see {@link #MULTIPLIER}. Callers pricing
     * damage should use {@link #damageBlocks} instead, which already handles the bed's distance scaling;
     * this accessor exists for the acceptance-side arithmetic that mirrors {@code Fall}'s resident-softness
     * {@code m}.
     */
    public static float multiplier(int kind) {
        return MULTIPLIER[kind];
    }

    /** Whether the kind scales fall DISTANCE rather than damage — {@link #BED} only. See {@link #SCALES_DISTANCE}. */
    public static boolean scalesDistance(int kind) {
        return SCALES_DISTANCE[kind];
    }

    /** Horizontally adjacent free cells the placement needs: 1, except {@link #BED} = 2. See {@link #FOOTPRINT}. */
    public static int footprint(int kind) {
        return FOOTPRINT[kind];
    }

    /** Whether the kind is unusable where the dimension evaporates water — {@link #WATER} only. */
    public static boolean needsNonEvaporating(int kind) {
        return NEEDS_NON_EVAPORATING[kind];
    }

    /** Whether landing on the kind rebounds the bot ({@link #SLIME}, {@link #BED}). See {@link #BOUNCES}. */
    public static boolean bounces(int kind) {
        return BOUNCES[kind];
    }

    /** The {@code bounceUp} Y-reversal factor: 1.0 slime, 0.66 bed, 0 otherwise. See {@link #RESTITUTION}. */
    public static float restitution(int kind) {
        return RESTITUTION[kind];
    }

    /** Whether the placed block can be recovered afterwards — true for all five. See {@link #RECLAIMABLE}. */
    public static boolean reclaimable(int kind) {
        return RECLAIMABLE[kind];
    }

    /**
     * Whether the bot comes to rest ON the placed block ({@link #SLIME}, {@link #HAY}) rather than sinking
     * through it to the floor that was already there ({@link #WATER}, {@link #POWDER_SNOW}, and
     * {@link #BED}'s unreachable entry). See {@link #LANDS_ON_TOP} for the full geometry argument, including
     * why powder snow is false despite carrying a solid collision box for the whole of the fall.
     *
     * <p>Callers use this to pick the landing node and the edit: true means node {@code (nx, fy+1, nz)}, an
     * effective drop one block shorter, and a folded place at {@code fy+1} (the node's floor); false means
     * node {@code (nx, fy, nz)}, the raw drop, and NO folded place (folding one would put solid geometry in
     * the node's own body cell and dead-end the search).
     */
    public static boolean landsOnTop(int kind) {
        return LANDS_ON_TOP[kind];
    }

    // ---- Pricing ------------------------------------------------------------------------------------

    /**
     * Residual fall damage for a {@code depth}-block drop onto this clutch, expressed in <b>HP-equivalent
     * blocks</b> — the same unit {@code Fall}'s excess-fall term uses, so the caller converts to ticks by
     * multiplying by {@link BotCaps#costPerHitpoint()} and nothing else. Two formulas, selected by
     * {@link #scalesDistance}:
     * <ul>
     *   <li><b>distance-scaled</b> ({@link #BED}) — {@code max(0, 0.5×depth − safeFall)}: vanilla halves the
     *       distance and then subtracts the 3.0 threshold, so the threshold protects only half the drop;</li>
     *   <li><b>multiplier-scaled</b> (everything else) — {@code max(0, (depth − safeFall) × multiplier)}: the
     *       threshold protects the full drop and only the excess is scaled. With {@code multiplier == 0}
     *       (water, powder snow, slime) this is identically zero at every depth, which is the arithmetic
     *       statement of "any-height clutch".</li>
     * </ul>
     * {@code safeFall} stands in for vanilla's 3.0 so an immune or feather-falling bot's wider window is
     * honoured by the same expression. The {@code max(0, …)} clamps a drop shallower than the safe window to
     * zero rather than letting it become a negative credit against some other term.
     *
     * <p>Uses {@code 1 HP ≈ 1 block} exactly as {@code Fall}'s existing penalty does, and deliberately does
     * NOT reproduce vanilla's {@code floor()}: the planner prices in a continuous currency, and flooring here
     * would make a 7.9-block-equivalent hit look identical to a 7.0 one and flatten A*'s preference between
     * two nearly-equal descents.
     */
    public static float damageBlocks(int kind, int depth, int safeFall) {
        if (SCALES_DISTANCE[kind]) {
            return Math.max(0f, 0.5f * depth - safeFall);
        }
        return Math.max(0f, (depth - safeFall) * MULTIPLIER[kind]);
    }

    /**
     * The cheapest carried clutch that makes a {@code depth}-block drop acceptable, or {@link #NONE}.
     *
     * <p>"Acceptable" is exactly {@code Fall}'s existing soft-landing acceptance test, restated in the
     * damage currency: the residual {@link #damageBlocks} must fit the same HP budget a hard landing at
     * {@code maxFallDistance} already spends, {@code maxFall − safeFall}. So a clutch never widens what the
     * bot will survive — it only lets a deeper drop reuse the budget the caps already sanction. The budget is
     * clamped at zero for a degenerate {@code maxFall < safeFall} caps set, which is the conservative
     * direction (it admits only the zero-residual any-height kinds).
     *
     * <p>Scans {@link #PREFERENCE} — HAY, SLIME, POWDER_SNOW, WATER — and returns the first kind that is
     * both present in {@code mask} and within budget, so the bot spends the most expendable sufficient item.
     * At most 4 iterations of an array load, a mask test and one branch; no allocation, no world access, and
     * no {@link #blockState} resolution (so this stays callable before {@code Bootstrap}).
     *
     * <p><b>{@link #BED} is never returned</b>, however set the {@code mask} bit is: the bed is absent from
     * {@link #PREFERENCE} because the executor cannot place a two-cell bed across 1.17.1..26.2 (class doc for
     * the full argument). A caller that ORs {@link #bit}{@code (BED)} into its mask — {@code BotInventory}
     * still classifies a carried bed, so this happens — simply gets one dead bit; the loop never reads it.
     * This is the guarantee the rest of the pipeline relies on, and it is why {@link #blockState} may return
     * {@code null} for the bed rather than resolving a block that does not exist on 26.x.
     *
     * <p><b>The mask is authoritative about availability.</b> {@code best} does NOT re-check
     * {@link #needsNonEvaporating} or {@link #footprint}: the mask's owner has already excluded kinds the bot
     * does not carry and kinds this dimension destroys, and footprint is a property of the specific landing
     * cell, which this class cannot see. A caller that builds a mask by other means must apply both filters
     * itself or it will be handed a kind it cannot place.
     *
     * @param mask     OR of {@link #bit} over the carried, dimension-legal kinds
     * @param depth    blocks of drop to the landing cell
     * @param safeFall the bot's free-fall window ({@link BotCaps#safeFallDistance()})
     * @param maxFall  the bot's hard cap ({@link BotCaps#maxFallDistance()}); the budget is its excess over
     *                 {@code safeFall}
     * @return the chosen kind ordinal, or {@link #NONE} when no carried kind fits the budget
     */
    public static int best(int mask, int depth, int safeFall, int maxFall) {
        if (mask == 0) return NONE;
        float budget = Math.max(0f, (float) (maxFall - safeFall));
        for (int i = 0; i < PREFERENCE.length; i++) {
            int kind = PREFERENCE[i];
            if ((mask & (1 << kind)) == 0) continue;
            if (damageBlocks(kind, depth, safeFall) <= budget) return kind;
        }
        return NONE;
    }

    /**
     * Apex height in blocks of the rebound off a {@linkplain #bounces bouncing} clutch, given the downward
     * {@code impactSpeed} (b/t; sign ignored) the bot arrives with. This is the follower's sneak input: after
     * a slime or bed landing the bot is thrown back up and has to fall AGAIN, and the servo may only sneak
     * once that second fall is already inside {@code safeFallDistance} — see the 1.21.2 divergence note in
     * the class doc.
     *
     * <p><b>Derivation</b> (the same class of closed-form-from-recurrence reasoning as {@code Fall}'s
     * {@code WATER_PENETRATION} table, evaluated per call rather than tabulated because the input is a
     * continuous impact speed rather than a whole-block depth). The rebound leaves at
     * {@code v0 = |impactSpeed| × restitution(kind)}, and vanilla's rising-air recurrence is
     * {@code v' = (v − 0.08) × 0.98} — gravity first, then drag. The sum is taken from the FIRST POST-BOUNCE
     * TICK: {@code bounceUp} runs inside {@code move()}, i.e. AFTER {@code travelInAir} has already applied
     * this tick's gravity and displacement, so {@code v0} itself is never turned into motion and must not be
     * counted. The apex is therefore {@code Σ vₜ for t ≥ 1 while vₜ > 0}.
     *
     * <p><b>Cross-check against a vanilla jump.</b> A jump is the same recurrence with {@code v0 = 0.42},
     * except that {@code jumpFromGround} sets the velocity inside {@code aiStep} BEFORE {@code travel}, so
     * the jump tick DOES displace by {@code v0}. Hence jump height {@code = 0.42 + bounceApex-of(0.42)}. The
     * series runs 0.3332, 0.248136, 0.16477328, 0.0830778144, 0.003016258 and then goes negative, summing to
     * {@code 0.8322033…}; {@code 0.42 + 0.8322033… = 1.2522}, which is exactly the known vanilla jump height.
     * If a refactor breaks that identity, the loop's phase is wrong.
     *
     * <p><b>Termination</b> is arithmetic, not a bounded loop: {@code v' − v = −0.02v − 0.0784} is strictly
     * negative for every {@code v > −3.92}, so the sequence decreases monotonically from any non-negative
     * {@code v0} and crosses zero in a few dozen ticks — 6 iterations for a jump, and 35 for the fastest
     * rebound physically reachable (a full-restitution slime landing off air terminal velocity, which throws
     * the bot 57.6 blocks back up: restitution 1.0 means a slime bounce very nearly returns the bot to the
     * height it fell from, so the servo will normally ride several bounces down). No allocation — the whole
     * method is three doubles in registers.
     * A non-bouncing kind has {@code restitution == 0}, so {@code v0 = 0}, the first iteration yields
     * {@code −0.0784} and the method returns {@code 0} with no special case.
     *
     * @param kind        the clutch landed on
     * @param impactSpeed downward speed at impact in b/t (magnitude is used; air terminal is 3.92)
     * @return the rebound apex in blocks above the landing surface, {@code 0} for a non-bouncing kind
     */
    public static double bounceApex(int kind, double impactSpeed) {
        double v = Math.abs(impactSpeed) * RESTITUTION[kind];
        double apex = 0.0;
        while (true) {
            v = (v - AIR_GRAVITY) * AIR_DRAG;
            if (v <= 0.0) return apex;
            apex += v;
        }
    }

    // ---- Block identity (lazily resolved) ------------------------------------------------------------

    /**
     * The {@link BlockState} a clutch of this kind places, for the follower's placement call and for any
     * descriptor-side pricing that wants the real geometry.
     *
     * <p>Resolved LAZILY through {@link States}: touching the MC block registry at {@code ClutchModel}'s own
     * class-init would make merely referencing {@link #COUNT} or calling {@link #best} require
     * {@code Bootstrap.bootStrap()} to have run, and the headless JMH harness and the unit tests load this
     * class long before that. The holder-class idiom gives the initialization for free and thread-safely —
     * the JVM initializes {@link States} on the first access to its field and nowhere else.
     *
     * <p>{@link #WATER} maps to {@code Blocks.WATER} for identity/geometry purposes even though the follower
     * places it from a bucket rather than by setting that state directly.
     *
     * <p><b>{@link #BED} maps to {@code null}, and that is not an oversight.</b> There is no bed block this
     * common source may name: {@code Blocks.RED_BED} is a plain {@code Block} field through 1.21.11 but does
     * NOT exist on 26.2, where the dyed beds collapse into one {@code Blocks.BED} of type
     * {@code ColorCollection<Block>} (javap-verified against the unobfuscated 26.2 jar; the same collapse
     * {@code overlays/26}'s {@code ConcretePowder} already handles for concrete powder). Naming either
     * spelling here breaks the multi-version compile gate on one side of the range, and resolving it
     * properly means a {@code platform/} seam — which this pass does not build, because {@link #best} cannot
     * return the bed anyway.
     *
     * <p>Consequently <b>callers must never ask for a kind {@link #best} cannot return</b>. Every kind
     * {@code best} DOES return — {@link #WATER}, {@link #POWDER_SNOW}, {@link #SLIME}, {@link #HAY} — has a
     * real state here on every supported version ({@code Blocks.WATER}, {@code Blocks.POWDER_SNOW},
     * {@code Blocks.SLIME_BLOCK}, {@code Blocks.HAY_BLOCK} are all present and all plain {@code Block} on
     * both 1.21.11 and 26.2, javap-verified). A {@code null} return therefore means the caller invented a
     * kind rather than taking one from {@code best}, and it will surface as an immediate NPE at the
     * placement site rather than as a silently wrong block — the loud failure is the intent.
     */
    public static BlockState blockState(int kind) {
        return States.BY_KIND[kind];
    }

    /**
     * Lazy holder for the registry-dependent states — see {@link #blockState}. The {@link #BED} slot is
     * {@code null} rather than a bed state: no bed identity is nameable across 1.17.1..26.2 from the common
     * source (26.x has only {@code Blocks.BED}, a {@code ColorCollection}). The slot is kept so the array
     * stays indexed by the kind ordinal like every other table.
     */
    private static final class States {
        static final BlockState[] BY_KIND = {
                Blocks.WATER.defaultBlockState(),
                Blocks.POWDER_SNOW.defaultBlockState(),
                Blocks.SLIME_BLOCK.defaultBlockState(),
                Blocks.HAY_BLOCK.defaultBlockState(),
                null, // BED — see the class doc; unreachable while BED is absent from PREFERENCE
        };
    }
}
