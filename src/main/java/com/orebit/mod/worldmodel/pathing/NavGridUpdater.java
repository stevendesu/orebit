package com.orebit.mod.worldmodel.pathing;

import java.util.concurrent.ConcurrentHashMap;

import com.orebit.mod.OrebitCommon;
import com.orebit.mod.platform.BlockChangeEvents;
import com.orebit.mod.platform.LevelBounds;
import com.orebit.mod.worldmodel.navblock.NavBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * Keeps the nav grid live as the world changes — the block-update hook that retires the follower's
 * per-replan {@code refreshNavData} rebuild. Registers a {@link BlockChangeEvents.Listener}; for a
 * server-side change inside a built section it records the cell's new navtype in the level's
 * {@link PendingPatches} queue (defer + last-state-wins dedup —
 * PERF-DESIGN-navgrid-edit-batching.md §4.2) and the whole set is drained through
 * {@link NavSectionBuilder#patchCells} at the next {@link #flush} barrier. Changes in chunks we don't
 * track yet are ignored — they build fresh on load.
 *
 * <p><b>Flush barriers (§4.4 — read-your-writes for every server-thread reader):</b> block changes
 * originate in MULTIPLE phases of {@code ServerLevel.tick} (scheduled/random ticks before entities,
 * TNT/mob griefing DURING entity ticking interleaved with bot ticks, piston block-events elsewhere),
 * so no fixed drain point can both precede and follow all same-tick producers. Instead every
 * server-thread read path drains on entry: the live {@code NavGridView(ServerLevel)} ctor (every sync
 * block search), the start of {@code AllyBotEntity}'s tick (region-tier reads that bypass
 * {@code NavGridView} — the lazy leaf-cost mini-pathfinds), and a world-tick-end catch-all registered
 * BEFORE {@code HpaMaintenance::flush} (its leaf recomputes read patched grids; also guarantees the
 * queue is empty across tick boundaries — invariant §4.6-2). A clean flush costs one static int test.
 * Async planner workers get no weaker guarantee than before: the drain issues the identical
 * {@code grid.set} writes on the same (server) thread, just later within the tick.
 *
 * <p>The trigger is the {@code setBlockState} mixin firing {@link BlockChangeEvents#fire}; until that
 * overlay is wired this listener simply never runs (registering it is harmless).
 */
public final class NavGridUpdater {
    private NavGridUpdater() {}

    /**
     * Per-level count of TRACKED-grid block edits (bumped once per grid-visible enqueued change). This
     * is the cheap "did the world change at all?" signal the follower's terrain-recheck debounce gates
     * on: an unchanged epoch means no built nav cell changed since the plan's last window search, so
     * the periodic re-search would be byte-identical and is skipped entirely (a stationary bot in a
     * quiet world never re-searches). The bump happens at ENQUEUE time (§4.5's recommended variant),
     * keeping today's semantics verbatim — "the world may have changed", immediately; the one consumer
     * reads it inside the bot's tick, which sits BEHIND the bot-tick-start flush barrier, so an
     * advanced epoch is never observed while its change still sits queued. Tick-thread confined (the
     * mixin fires on the server thread; the driver reads on the server thread) — no synchronization.
     * Known coarseness, documented: the epoch is level-global (an edit anywhere re-arms every bot's
     * recheck — one wasted-but-correct search), and it includes the bot's OWN plan edits (excluding
     * those needs per-edit attribution; a plan's own assumed edits are already modelled by PathEdits,
     * so those re-searches return equivalent routes).
     */
    private static final java.util.WeakHashMap<ServerLevel, int[]> EDIT_EPOCH = new java.util.WeakHashMap<>();

    /**
     * The per-level deferred-patch queues (§4.2) — the same {@code WeakHashMap} idiom and tick-thread
     * confinement as {@link #EDIT_EPOCH} (off-thread worldgen fires hit the untracked-chunk early-out
     * before ever touching a queue).
     */
    private static final java.util.WeakHashMap<ServerLevel, PendingPatches> PENDING = new java.util.WeakHashMap<>();

    /**
     * Total dirty cells across every level — the one-test clean gate {@link #flush} pays when nothing
     * is pending (the common case for every barrier crossing). Exact: incremented per first insert of
     * a key, decremented by the drained count. Server-thread confined like everything else here.
     */
    private static int pendingGlobal;

    /** The current edit epoch for {@code level} (0 until its first tracked edit). Server thread only. */
    public static int editEpoch(ServerLevel level) {
        final int[] c = EDIT_EPOCH.get(level);
        return c == null ? 0 : c[0];
    }

    /**
     * Advance the epoch for a NON-block-change grid mutation — chunk nav sections built or dropped
     * ({@code ChunkNavLoader}). A newly BUILT area is exactly as plan-relevant as an edited one: without
     * this, a bot whose first search ran before its chunks built (seconds after world open) had no signal
     * to re-search until some block changed (the s52b cold-open false START-DEAD). Server thread only.
     */
    public static void bumpEpoch(ServerLevel level) {
        EDIT_EPOCH.computeIfAbsent(level, l -> new int[1])[0]++;
    }

    /**
     * Per-chunk-column grid-change version — the PLAN-RELEVANCE gate (owner ratification 2026-07-24). The
     * dimension-global {@link #EDIT_EPOCH} answers "did anything change anywhere", which a travelling bot
     * defeats by its own exploration: chunk nav builds/drops at its frontier (and a house built 50k blocks
     * away) all bump the global epoch, so the follower's terrain-recheck re-searches every debounce window
     * forever (the open-ocean flap, verified 2026-07-24). A plan instead snapshots the versions of the chunks
     * ITS PATH traverses and re-searches only when one of THOSE changed. Server-thread confined, like the epoch.
     */
    private static final java.util.WeakHashMap<ServerLevel, java.util.HashMap<Long, int[]>> CHUNK_VERSION =
            new java.util.WeakHashMap<>();

    /** The current change-version of chunk column {@code chunkKey} ({@link NavStore#key}); 0 until its first
     *  tracked change. Server thread only. */
    public static int chunkVersion(ServerLevel level, long chunkKey) {
        final java.util.HashMap<Long, int[]> m = CHUNK_VERSION.get(level);
        if (m == null) {
            return 0;
        }
        final int[] v = m.get(chunkKey);
        return v == null ? 0 : v[0];
    }

    /**
     * Advance BOTH the dimension epoch (the cheap "anything changed" early-out) AND the specific chunk
     * column's version (the plan-relevance gate). Every grid-mutation site — chunk nav build/drop and each
     * enqueued block change — routes through here, so the global epoch stays the running max of the per-chunk
     * versions and a plan's chunk-version snapshot is exact. Server thread only.
     */
    public static void bumpChunk(ServerLevel level, int chunkX, int chunkZ) {
        EDIT_EPOCH.computeIfAbsent(level, l -> new int[1])[0]++;
        CHUNK_VERSION.computeIfAbsent(level, l -> new java.util.HashMap<>())
                .computeIfAbsent(NavStore.key(chunkX, chunkZ), k -> new int[1])[0]++;
    }

    /**
     * DIAGNOSTIC: the most recent grid-visible block change in a chunk column — what actually moved the
     * version {@link #chunkVersion} reports.
     *
     * <p><b>Why record it</b> (owner request 2026-08-06). A {@code plan-impacted} re-search says only "some
     * chunk this path traverses changed"; it never says WHICH block, so "a vine grew beside the bot" and "a
     * redstone clock is running far away" produce the identical log line. Without the change itself there is
     * no way to confirm the plan-relevance gate is doing its job rather than being defeated by an
     * over-broad chunk list — and a re-search rebuilds the plan mid-move, so the difference matters.
     *
     * <p>Zero steady-state allocation: one mutable record per chunk column, overwritten in place. Old/new
     * {@code BlockState}s are the registry's interned singletons, so holding them is two reference writes
     * with no retention beyond what the registry already keeps alive; they are stringified only when a
     * forensic line is actually emitted. Keyed by the same chunk-key set {@link #CHUNK_VERSION} already
     * retains, so this adds no new growth class. Server thread only, like the version map.
     */
    public static final class ChunkChange {
        public long pos;
        public BlockState oldState;
        public BlockState newState;
        /** The {@link #chunkVersion} value this change produced — proves the record IS the impacting change
         *  and not an older one in the same column that a later bump already superseded. */
        public int version;
    }

    private static final java.util.WeakHashMap<ServerLevel, java.util.HashMap<Long, ChunkChange>> LAST_CHANGE =
            new java.util.WeakHashMap<>();

    /** Record (diagnostic only) the block change that just bumped this position's chunk column. */
    private static void recordChange(ServerLevel level, BlockPos pos, BlockState oldState, BlockState newState) {
        final long key = NavStore.key(pos.getX() >> 4, pos.getZ() >> 4);
        final ChunkChange c = LAST_CHANGE.computeIfAbsent(level, l -> new java.util.HashMap<>())
                .computeIfAbsent(key, k -> new ChunkChange());
        c.pos = pos.asLong();
        c.oldState = oldState;
        c.newState = newState;
        c.version = chunkVersion(level, key);
    }

    /** The last recorded grid-visible change in {@code chunkKey}, or {@code null} if none is on record — the
     *  column was bumped by a nav build/drop or the fluid-edge fold rather than by a block change. */
    public static ChunkChange lastChange(ServerLevel level, long chunkKey) {
        final java.util.HashMap<Long, ChunkChange> m = LAST_CHANGE.get(level);
        return m == null ? null : m.get(chunkKey);
    }

    /**
     * Per-chunk-column count of <b>FOREIGN</b> grid-visible changes — every change EXCEPT one the follower
     * announced ({@link #expectChange}, {@link #expectFloodedBreak}, {@link #expectToggle}) or the predicted
     * fluid arrival of an earlier fluid-folded break (the pending-flood residual — see
     * {@link #observePendingFlood}). This, not {@link #CHUNK_VERSION}, is what a plan snapshots to decide
     * whether the world diverged from it.
     *
     * <p><b>Why identity and not a count</b> (owner ruling 2026-08-06). The first cut let a plan tolerate N
     * version bumps in a column, N being the number of edits it had executed there. That is sound only while
     * "an executed edit bumps the version exactly once" holds — and it fails silently in the dangerous
     * direction if an acknowledged edit ever bumps ZERO times (a grid-invisible change; see
     * {@code NavGridEpochTest}), because the unspent credit then absorbs the next foreign change instead.
     * A budget also cannot answer the question actually being asked. The question is not "how many things
     * changed here" but "did we expect THIS mutation": a stone vanishing from the cell we were mining is our
     * plan executing, a vine appearing in that same cell is not, and no counter can tell those apart.
     *
     * <p>So the classification happens at the change itself, where the position and the before/after states
     * are in hand, and only unexpected changes move this counter. A plan's snapshot of it is then exact:
     * unchanged means every change since the snapshot was one the plan predicted.
     */
    private static final java.util.WeakHashMap<ServerLevel, java.util.HashMap<Long, int[]>> FOREIGN_VERSION =
            new java.util.WeakHashMap<>();

    /** The current FOREIGN-change version of chunk column {@code chunkKey}; 0 until its first unexpected
     *  change. Server thread only. */
    public static int foreignVersion(ServerLevel level, long chunkKey) {
        final java.util.HashMap<Long, int[]> m = FOREIGN_VERSION.get(level);
        if (m == null) {
            return 0;
        }
        final int[] v = m.get(chunkKey);
        return v == null ? 0 : v[0];
    }

    // ---- The one-shot expectation the follower arms immediately before it edits ----------------------
    private static ServerLevel expectLevel;
    private static long expectPos;
    private static boolean expectAir;
    /** {@link #FLOOD_NONE} for a plain single-phase arm, else the fluid a {@link #expectFloodedBreak
     *  fluid-folded break} predicted will follow the air — the slot's widened payload. */
    private static byte expectFluid;
    private static boolean expectArmed;

    /**
     * Announce the <b>exact mutation</b> the follower is about to make: cell {@code pos} is about to become
     * air ({@code toAir}) or to become filled ({@code !toAir}). The very next matching change is classified as
     * EXPECTED and does not move {@link #foreignVersion}; everything else does.
     *
     * <p>Armed immediately before the edit and consumed by the change it predicts, both on the server tick
     * thread, so the window is a single synchronous call. That is what makes it exact rather than a heuristic:
     * a cascading change the edit triggers (gravel falling into the hole, a neighbour's fluid flowing in)
     * arrives AFTER the slot is spent, and is counted as foreign — the plan never modelled it. Exactly ONE
     * cascade is exempt from that rule: a break the plan folded as {@code BROKEN_WATER}/{@code BROKEN_LAVA}
     * <i>predicted</i> its own flood, so the fluid's later arrival is the documented second half of a
     * two-phase edit, not the world diverging — such a break is armed through {@link #expectFloodedBreak}
     * instead, and its arrival is forgiven via the pending-flood residual there
     * (DESIGN-fluid-flow-prediction.md §8.3). Every other cascade stays foreign.
     *
     * <p>One slot, not a set: an edit is a single block mutation and is consumed on the same tick it is armed,
     * so a second arm can only mean the first never fired (a refused place, a break vanilla declined). Arming
     * overwrites, which is the conservative resolution — the stale expectation is dropped rather than left
     * lying in wait to forgive an unrelated change later.
     *
     * <p><b>Caller contract</b>: only arm for a mutation the CURRENT PLAN prescribed. The follower's actuators
     * are shared with {@code /bot mine} and gather, and forgiving one of those would blind the gate for a
     * change no plan predicted ({@code BotNavigator.expectOwnEdit} does the check).
     */
    public static void expectChange(ServerLevel level, BlockPos pos, boolean toAir) {
        expectLevel = level;
        expectPos = pos.asLong();
        expectAir = toAir;
        expectFluid = FLOOD_NONE;
        expectArmed = true;
    }

    // ---- The TWO-PHASE expectation a fluid-folded break arms (DESIGN-fluid-flow-prediction.md §8.3) ----

    /** No predicted flood — the classic single-phase expectation ({@link #expectChange}). */
    public static final byte FLOOD_NONE = 0;
    /** The armed break was folded as water-flooded ({@code PathEdits.BROKEN_WATER}): the cell is expected
     *  to become AIR and then, ≥5 ticks later, open WATER (DESIGN-fluid-flow-prediction.md §1.4, §6). */
    public static final byte FLOOD_WATER = 1;
    /** The lava analog ({@code PathEdits.BROKEN_LAVA}): AIR, then — ≥30 ticks later overworld — open LAVA. */
    public static final byte FLOOD_LAVA = 2;

    /**
     * Announce a break the CURRENT PLAN folded as fluid-flooded ({@code PathEdits.BROKEN_WATER} /
     * {@code BROKEN_LAVA}): cell {@code pos} is about to be mined out, and the planner already predicted that
     * the opened cell floods with {@code fluid} ({@link #FLOOD_WATER}/{@link #FLOOD_LAVA} —
     * DESIGN-fluid-flow-prediction.md §6). The expectation is therefore a <b>set of two states</b>,
     * {air, that fluid}, not a single direction, because vanilla delivers the edit in TWO PHASES (§1.4/§8.3):
     * the break writes air immediately, and the fluid arrives on its own spread schedule — ≥5 ticks later for
     * water, ≥30 for overworld lava, plus ~one delay per cell of travel. Before this arm existed the arrival
     * was classified as a foreign "cascading change", so every flooded break the plan itself prescribed cost
     * one guaranteed invalidation + re-search — and that re-search planned against the transient AIR state,
     * deriving affordances (walk/fall through the hole) that the arriving fluid then revoked with a second
     * invalidation (§8.3's churn analysis; lava's 30-tick lag sits outside the 40-tick terrain-recheck
     * debounce, guaranteeing the re-searches are distinct).
     *
     * <p><b>Consumption is split across the phases.</b> The very next change at the cell spends the one-shot
     * slot as usual: observed AIR (the normal mined break) consumes it and deposits a per-level, per-cell
     * <b>pending-flood residual</b> that the fluid's later arrival consumes on match; observed {@code fluid}
     * IMMEDIATELY consumes it outright with no residual — the WATERLOGGED-break case, where vanilla's
     * {@code destroyBlock} leaves the fluid's legacy block in place of air, which the old direction-only
     * match read as a false foreign change from the bot's own prescribed break; any other observed state is
     * foreign, exactly as before ({@link #observeFloodedBreak} is the match rule). The residual is released
     * by STATE only — consumed by the matching arrival, superseded by any other change at its cell, dropped
     * with its level — never by a tick countdown ({@code no-arbitrary-timers}; §8.3 pins "state-based, not a
     * timer": the expectation describes what the cell WILL hold, not when).
     *
     * <p>Same caller contract as {@link #expectChange}: armed immediately before the mutation, server thread,
     * only for a break the CURRENT PLAN prescribed with that fold kind ({@code PathPlan.expectOwnEdit}
     * recovers the kind from the plan's own {@code StepEdits} and routes here).
     */
    public static void expectFloodedBreak(ServerLevel level, BlockPos pos, byte fluid) {
        expectLevel = level;
        expectPos = pos.asLong();
        expectAir = true;
        expectFluid = fluid;
        expectArmed = true;
    }

    /**
     * Per-level pending-flood residuals — {@code packed BlockPos → FLOOD_*} for every fluid-folded break
     * whose AIR phase has landed but whose predicted fluid has not yet arrived
     * (DESIGN-fluid-flow-prediction.md §8.3, §11.4). Unlike the one-shot slots beside it this must be a
     * MULTI-ENTRY, per-level table: the arrival lag outlives the synchronous arm→mutate→consume call stack,
     * several breaks can be in flight at once (a MineDown macro folds one flooded break per column level),
     * and multiple bots' plans hold expectations concurrently. Entries are released by STATE only (see
     * {@link #observePendingFlood}), with {@link #PENDING_FLOOD_CAP} as the documented hard backstop. Keyed
     * weakly by level — the same {@code WeakHashMap} idiom as every other per-level map here — so an
     * unloading level drops its residuals wholesale. Tick-thread cold path (touched per grid-visible block
     * change, never per-A*-node), so a plain boxed {@code HashMap} is acceptable; the hot-path allocation
     * rules do not bind.
     */
    private static final java.util.WeakHashMap<ServerLevel, java.util.HashMap<Long, Byte>> PENDING_FLOOD =
            new java.util.WeakHashMap<>();

    /**
     * Hard SAFETY BACKSTOP on a level's pending-flood residuals — deliberately NOT an eviction policy
     * (release stays state-based only). A residual persists past its usefulness only when the predicted
     * fluid never arrives AND nothing else ever changes at its cell (an errs-wet verdict at a cell the world
     * then leaves alone forever); the cap merely bounds that leak. At the cap new deposits are REFUSED and
     * logged ({@link #observeFloodedBreak}), which errs toward invalidation — the un-deposited arrival reads
     * foreign and costs one re-search, exactly the pre-arc behavior — never toward forgiving a change no
     * plan predicted.
     */
    static final int PENDING_FLOOD_CAP = 4096;

    /**
     * The fluid-armed slot's MATCH-AND-DEPOSIT rule, level-free (the {@link #changesGrid} pattern: the slot
     * machinery around it is welded to a live {@code ServerLevel}, so the arm→observe logic a unit test
     * drives lives here against a plain map + {@code BlockState}). Returns whether the observed state is
     * forgiven:
     * <ul>
     *   <li><b>AIR</b> — phase 1 of the two-phase edit: forgiven, and the predicted fluid is deposited as a
     *       pending-flood residual at the cell (overwriting a stale one; refused + logged past
     *       {@link #PENDING_FLOOD_CAP}).</li>
     *   <li><b>the predicted fluid</b> — the waterlogged-break case (vanilla leaves the fluid block
     *       immediately; there is no air phase): forgiven outright, no residual.</li>
     *   <li><b>anything else</b> — foreign, and any stale residual at the cell is superseded (removed): the
     *       cell no longer holds what the old prediction described.</li>
     * </ul>
     */
    static boolean observeFloodedBreak(byte fluid, BlockState newState,
                                       java.util.HashMap<Long, Byte> floods, long posKey) {
        if (newState.isAir()) {
            if (floods.size() >= PENDING_FLOOD_CAP && !floods.containsKey(posKey)) {
                OrebitCommon.LOGGER.warn(
                        "[Orebit] pending-flood residual cap ({}) hit — the flood at ({},{},{}) will read as"
                                + " a foreign change (one wasted-but-correct re-search)",
                        PENDING_FLOOD_CAP, BlockPos.getX(posKey), BlockPos.getY(posKey), BlockPos.getZ(posKey));
            } else {
                floods.put(posKey, fluid);
            }
            return true;
        }
        floods.remove(posKey);
        return isPredictedFluid(fluid, newState);
    }

    /**
     * The pending-flood residual's release rule, level-free and STATE-BASED
     * (DESIGN-fluid-flow-prediction.md §8.3): the FIRST change of any kind at a residual's cell resolves it —
     * the entry is removed on sight, then forgiven only when the new state IS the predicted open fluid
     * ({@link #isPredictedFluid}). The fluid arriving consumes the entry (not foreign); anything else
     * supersedes it AND classifies foreign, because the world genuinely diverged from what the plan folded.
     * No entry at the cell ⇒ plain foreign classification, semantics untouched. There is deliberately no
     * timer and no age field: a residual whose cell never changes again simply rests until its level unloads
     * ({@link #PENDING_FLOOD_CAP} bounds the accumulation).
     */
    static boolean observePendingFlood(java.util.HashMap<Long, Byte> floods, long posKey, BlockState newState) {
        final Byte kind = floods.remove(posKey);
        return kind != null && isPredictedFluid(kind, newState);
    }

    /**
     * Whether {@code state} IS the open fluid a {@code FLOOD_*} expectation predicted — the arrival match of
     * the two-phase edit. Uses the descriptor's OPEN-fluid tests ({@code isSwimmableWater} /
     * {@code isSwimmableLava}: fluid present AND passable), not the bare fluid field, so a waterlogged SOLID
     * appearing at the cell (someone places a waterlogged stair there) is not mistaken for the predicted
     * flood — what vanilla actually delivers, in both the lagged-arrival and the waterlogged-break case, is
     * the plain fluid block. Pure function of the state (descriptor interning), level-free.
     */
    static boolean isPredictedFluid(byte fluid, BlockState state) {
        final long d = NavBlock.descriptorFor(state);
        return fluid == FLOOD_WATER ? NavBlock.isSwimmableWater(d)
                : fluid == FLOOD_LAVA && NavBlock.isSwimmableLava(d);
    }

    /** Level-welded wrapper over {@link #observePendingFlood}: resolve {@code level}'s residual table (a
     *  missing/empty table is the common case and costs one map probe). Consulted at the classification site
     *  only after the one-shot and toggle slots MISS — which also keeps it behind {@code onBlockChanged}'s
     *  untracked-chunk early-out, so off-thread worldgen fires never reach the table. */
    private static boolean consumePendingFlood(ServerLevel level, BlockPos pos, BlockState newState) {
        final java.util.HashMap<Long, Byte> floods = PENDING_FLOOD.get(level);
        if (floods == null || floods.isEmpty()) {
            return false;
        }
        return observePendingFlood(floods, pos.asLong(), newState);
    }

    /** Whether {@code (pos, newState)} is the mutation the follower just announced — and consume the slot. A
     *  fluid-armed slot ({@link #expectFloodedBreak}) matches against its two-state expectation set instead
     *  of the single direction, and its AIR phase leaves the pending-flood residual behind. */
    private static boolean consumeExpected(ServerLevel level, BlockPos pos, BlockState newState) {
        if (!expectArmed || expectLevel != level || expectPos != pos.asLong()) {
            return false;
        }
        expectArmed = false;
        if (expectFluid != FLOOD_NONE) {
            return observeFloodedBreak(expectFluid, newState,
                    PENDING_FLOOD.computeIfAbsent(level, l -> new java.util.HashMap<>()), pos.asLong());
        }
        // A single-phase own edit landing at this cell supersedes any stale pending-flood residual there —
        // the plan re-edited the cell, so the old flood prediction no longer describes it. Same state-based
        // release discipline as the residual's own rules (observePendingFlood); removed whether or not the
        // direction below matches, because either way the cell's state moved past the prediction.
        final java.util.HashMap<Long, Byte> floods = PENDING_FLOOD.get(level);
        if (floods != null && !floods.isEmpty()) {
            floods.remove(pos.asLong());
        }
        // The STATE must match the announced direction too, so a vine growing into the very cell we were
        // about to mine is not forgiven just because the position lines up.
        return expectAir == newState.isAir();
    }

    // ---- The one-shot STATE-TOGGLE expectation the follower arms before an openable toggle ------------
    //
    // The break/place slot above matches a DIRECTION (cell becomes air / becomes filled); a door or trapdoor
    // toggle is neither — the SAME Block changes state in place. This is its own slot with its own match rule
    // (pos-matched, oldState != newState, same Block — DESIGN-trapdoors.md §7), because widening the
    // direction slot to "or any state change" would forgive mutations the plan never predicted. A door's
    // vanilla setOpen mutates TWO cells (the clicked half plus the updateShape-synced other half), so the
    // slot carries up to two cells — the armed cell and, for a door, its derived other half — each consumed
    // AT MOST ONCE (the one-shot discipline, per cell). Arming overwrites the whole slot, exactly like
    // expectChange: a leftover cell can only mean the predicted change never fired, and the stale expectation
    // is dropped rather than left lying in wait.
    private static ServerLevel toggleLevel;
    private static long togglePosA, togglePosB;
    private static boolean toggleArmedA, toggleArmedB;

    /**
     * Announce that the follower is about to TOGGLE the openable at {@code pos} — a same-block state change
     * (OPEN flip), not a break/place. The very next matching change at each covered cell is classified as
     * EXPECTED and does not move {@link #foreignVersion}; everything else does. For a <b>door</b> the slot
     * also covers the door's OTHER half (derived here from the pre-toggle state's {@code DoubleBlockHalf} —
     * vanilla's {@code setOpen} syncs it via {@code updateShape} in the same synchronous {@code setBlock}
     * cascade, so both halves' changes are the one prescribed toggle); a trapdoor or fence gate is
     * single-cell. Matching is
     * exact per cell: position, {@code oldState != newState}, and {@code oldState.getBlock() ==
     * newState.getBlock()} — so a door being BROKEN (block changes) or a vine growing there is never forgiven.
     *
     * <p>Same caller contract as {@link #expectChange}: armed immediately before the mutation, server thread,
     * only for a toggle the CURRENT PLAN prescribed ({@code PathPlan.expectOwnToggle} does the check).
     */
    public static void expectToggle(ServerLevel level, BlockPos pos) {
        toggleLevel = level;
        togglePosA = pos.asLong();
        toggleArmedA = true;
        toggleArmedB = false;
        final BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof DoorBlock
                && state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)) {
            final boolean lower =
                    state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER;
            togglePosB = (lower ? pos.above() : pos.below()).asLong();
            toggleArmedB = true;
        }
    }

    /** Whether {@code (pos, oldState → newState)} is (half of) the toggle the follower just announced — and
     *  consume that cell's one-shot. A pos match spends the cell either way (like {@link #consumeExpected}'s
     *  direction check); forgiveness additionally requires a same-Block state change. */
    private static boolean consumeExpectedToggle(ServerLevel level, BlockPos pos,
                                                 BlockState oldState, BlockState newState) {
        if ((!toggleArmedA && !toggleArmedB) || toggleLevel != level) {
            return false;
        }
        final long p = pos.asLong();
        if (toggleArmedA && p == togglePosA) {
            toggleArmedA = false;
        } else if (toggleArmedB && p == togglePosB) {
            toggleArmedB = false;
        } else {
            return false;
        }
        return forgivableToggle(oldState, newState);
    }

    /**
     * The forgiveness MATCH RULE of {@link #consumeExpectedToggle} (a pos-matched cell's state change is
     * forgiven only when this holds): a genuine OPENABLE state flip — same block, different state, AND the
     * block is a door/trapdoor/fence gate. The instanceof guards the dangling-arm case — the executor verbs
     * no-op on a stale-grid cell that no longer holds an openable, leaving the one-shot armed; without the
     * kind check it would forgive ONE later same-block state change of whatever occupies the cell (a
     * comparator, an observer), suppressing a legitimate foreignVersion bump. (A dangling arm over a REAL
     * door can still forgive one genuine foreign toggle there — known, narrow; the grid patch itself still
     * lands either way.) Package-private seam: the slot machinery around it is welded to a live
     * {@code ServerLevel} (the {@code PathPlanOwnEditTest} split), so the kind rule is pinned here
     * ({@code NavGridToggleForgivenessTest}) — each openable kind added to the executor verbs MUST be added
     * to this chain, or its own toggles read as foreign changes and burn a window re-search apiece.
     */
    static boolean forgivableToggle(BlockState oldState, BlockState newState) {
        return oldState != newState && oldState.getBlock() == newState.getBlock()
                && (oldState.getBlock() instanceof DoorBlock || oldState.getBlock() instanceof TrapDoorBlock
                        || oldState.getBlock() instanceof FenceGateBlock);
    }

    /** Register the nav-grid patcher against the block-change seam (once, at init). */
    public static void register() {
        BlockChangeEvents.register(NavGridUpdater::onBlockChanged);
    }

    private static void onBlockChanged(Level level, BlockPos pos, BlockState oldState, BlockState newState) {
        if (!(level instanceof ServerLevel server)) return; // server authority only
        if (oldState == newState) return;                    // interned states: reference-equal == no change

        int sectionIndex = (pos.getY() - LevelBounds.minY(server)) >> 4;
        if (sectionIndex < 0) return;
        NavSection[] sections = NavStore.get(server, NavStore.key(pos.getX() >> 4, pos.getZ() >> 4));
        if (sections == null || sectionIndex >= sections.length) return; // chunk not tracked
        NavSection section = sections[sectionIndex];
        if (section == null) return;

        final int lx = pos.getX() & 15, ly = pos.getY() & 15, lz = pos.getZ() & 15;
        final short newNavtype = NavBlock.navtypeFor(newState); // interned ONCE here, stored in the queue

        // EFFECTIVE-NAVTYPE NO-OP EARLY-OUT (the Phase-0 filter generalized over the queue, §4.2 step 2):
        // a change whose navtype equals what the grid WILL hold once drained — the pending value if the
        // cell is dirty, else the resident navtype — changes nothing any plan can see: this cell's
        // descriptor is identical, so every neighbour's flag/depth window (which reads only navtypes) is
        // identical too. An extend-then-retract piston pair in one tick collapses to a pending value equal
        // to the resident one, which the drain skips outright. Skipping the patch is pure saved work;
        // skipping the epoch bump is what keeps the follower's terrain-recheck debounce MEANINGFUL —
        // without it a single redstone clock anywhere in the level re-arms every bot's periodic re-search
        // forever (PERF-DESIGN-navgrid-edit-batching.md phase 0).
        final PendingPatches queue = PENDING.computeIfAbsent(server, l -> new PendingPatches());
        if (!enqueueIfChanges(queue, section, lx, ly, lz, pos.asLong(), newNavtype)) {
            return;
        }

        // A grid-visible change was queued — the world visibly changed for every plan over THIS chunk.
        // Enqueue-time bump (§4.5): the epoch may only ever run AHEAD of the drained grid, never behind
        // it, so a debounce read behind any barrier can never observe queued-but-unbumped state. Per-chunk
        // (owner 2026-07-24): a change bumps only its own column's version, so a plan that doesn't traverse
        // this chunk is not re-searched (the redstone-clock / far-house false re-arm named above).
        NavGridUpdater.bumpChunk(server, pos.getX() >> 4, pos.getZ() >> 4);
        // …and remember WHICH block did it, so a plan-impacted re-search can name its cause instead of
        // leaving "a vine grew" and "a redstone clock is running" indistinguishable (see ChunkChange).
        recordChange(server, pos, oldState, newState);
        // Classify it. Only a change the follower did NOT announce moves the foreign version — the counter a
        // plan actually snapshots. This is the whole own-edit gate: our own prescribed break/place — or
        // prescribed door/trapdoor TOGGLE (the state-toggle slot), or the PREDICTED FLUID ARRIVAL of an
        // earlier fluid-folded break (the pending-flood residual, DESIGN-fluid-flow-prediction.md §8.3) —
        // is the plan executing, not the world diverging from it (see FOREIGN_VERSION). The residual is
        // consulted last, only after both one-shot slots miss, so a slot armed for this very cell always
        // resolves first (and, on a fluid arm's AIR phase, is what deposits the residual).
        if (!consumeExpected(server, pos, newState)
                && !consumeExpectedToggle(server, pos, oldState, newState)
                && !consumePendingFlood(server, pos, newState)) {
            FOREIGN_VERSION.computeIfAbsent(server, l -> new java.util.HashMap<>())
                    .computeIfAbsent(NavStore.key(pos.getX() >> 4, pos.getZ() >> 4), k -> new int[1])[0]++;
        }

        // Nether-portal index maintenance (NetherPortalIndex incremental feed), from the EVENT params:
        // under deferral the resident grid can be stale-by-one-pending-write, but the event's old/new
        // states are order-exact (§4.6-6), and the navtype is a pure function of the state — identical to
        // the old grid read while the grid was patched inline. Two descriptor bit-tests per block change —
        // the index mutates only when a portal actually toggles (vanishingly rare), and this path is
        // per-block-change, never per-A*-node.
        boolean wasPortal = NavBlock.isNetherPortal(NavBlock.descriptorFor(oldState));
        boolean nowPortal = NavBlock.isNetherPortal(NavBlock.descriptor(newNavtype));
        if (wasPortal != nowPortal) {
            if (nowPortal) NetherPortalIndex.add(server, pos.getX(), pos.getY(), pos.getZ());
            else NetherPortalIndex.removeCell(server, pos.getX(), pos.getY(), pos.getZ());
        }
    }

    /**
     * The enqueue decision + insert (package-private: the headless identity/epoch tests drive this seam
     * directly — {@code onBlockChanged} itself needs a live {@code ServerLevel}, which cannot be stood
     * up under the Knot test classloader). Returns {@code false} when the change is invisible to the
     * nav grid — {@code newNavtype} equals the cell's EFFECTIVE navtype (pending value if dirty, else
     * resident) — in which case nothing is queued and the caller must not bump the epoch; {@code true}
     * means the change was queued and the epoch must bump. The {@code true}-iff-queue-changed coupling
     * is what invariant §4.6-5 (epoch never under-reports) rests on.
     */
    static boolean enqueueIfChanges(PendingPatches queue, NavSection section, int lx, int ly, int lz,
                                    long posKey, short newNavtype) {
        final int pending = queue.get(posKey);
        if (pending >= 0 ? newNavtype == (short) pending
                         : !changesGrid(section, lx, ly, lz, newNavtype)) {
            return false;
        }
        if (queue.put(posKey, newNavtype)) pendingGlobal++;
        return true;
    }

    /**
     * Flush barrier (§4.4): drain {@code level}'s pending queue so the caller's subsequent nav-grid
     * reads observe every block change fired before this point (read-your-writes, invariant §4.6-1 —
     * equivalent to the old inline patch). A no-op costing one static int test when nothing is pending
     * anywhere (the common case), plus one map lookup + count test when another level owns the pending
     * cells. Server thread only — the same confinement as the queue itself.
     */
    public static void flush(ServerLevel level) {
        if (pendingGlobal == 0) return;
        final PendingPatches queue = PENDING.get(level);
        final int n = queue == null ? 0 : queue.count();
        if (n == 0) return;
        pendingGlobal -= n;
        final int minY = LevelBounds.minY(level);
        final ConcurrentHashMap<Long, NavSection[]> chunks = NavStore.chunksOf(level);
        // DURABLE cross-chunk fluid edge fold (#7 step 3): the drain's authoritative recomputeWindow rewrites
        // each edge cell's flags from the LOCAL scratch, dropping the cross-face fluid-neighbour term. Note
        // which faces the batch touches BEFORE the drain clears the queue, then re-derive that term
        // authoritatively (both sides) AFTER — resolving the lateral neighbour from the same live store. This
        // sits OUTSIDE drain() deliberately: BatchDrainIdentityTest drives drain() directly against a
        // lateral-free sequential reference, and flush() is not exercised under the Knot test classloader.
        EdgeScatter.collect(queue, minY);
        drain(queue, minY, chunks);
        EdgeScatter.reconcile(chunks, minY, k -> bumpChunk(level, NavStore.keyX(k), NavStore.keyZ(k)));
    }

    // Drain sort-key layout (one long per pending cell, grouped by section when sorted):
    // [chunkX+BIAS:22][chunkZ+BIAS:22][sectionIndex:8][packedCell:12]. Only ADJACENCY of equal
    // (chunk, section) prefixes and ascending section order within a chunk matter — the signed sort's
    // cross-chunk order is irrelevant — and the cell's world position is fully reconstructible from the
    // key, so the pending navtype is re-fetched with one queue probe per cell.
    private static final int CHUNK_BIAS = 1 << 21;

    /**
     * The drain (§4.3's outer loop; package-private, headless-testable — the level-free core
     * {@link #flush} delegates to, parameterized exactly like {@code NavGridView}'s synthetic seam):
     * sort the pending cells so same-section cells are adjacent (sections ascending within a chunk, so
     * a below-seam batch always runs after the section under it was itself patched-or-skipped), resolve
     * each section group FRESH from the store — a chunk unloaded since enqueue drops its entries, never
     * a stale {@code NavSection} ref (§4.6-4) — and hand each group to
     * {@link NavSectionBuilder#patchCells} (the Phase-2 phased per-section patch). Ends with
     * {@link PendingPatches#clear}, so the queue is empty behind every barrier. Allocation-free: the
     * sort/group buffers are the queue's own reusable scratch.
     */
    static void drain(PendingPatches queue, int minY, ConcurrentHashMap<Long, NavSection[]> chunks) {
        final int n = queue.count();
        final long[] sorted = queue.sortScratch(n);
        for (int i = 0; i < n; i++) {
            final long posKey = queue.keyAt(i);
            final int x = BlockPos.getX(posKey), y = BlockPos.getY(posKey), z = BlockPos.getZ(posKey);
            sorted[i] = ((long) ((x >> 4) + CHUNK_BIAS) << 42)
                    | ((long) ((z >> 4) + CHUNK_BIAS) << 20)
                    | ((long) ((y - minY) >> 4) << 12)
                    | ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
        }
        java.util.Arrays.sort(sorted, 0, n);

        final short[] cells = queue.cellScratch(n);
        final short[] navs = queue.navScratch(n);
        int i = 0;
        while (i < n) {
            final long groupPrefix = sorted[i] >>> 12;
            final int chunkX = ((int) (sorted[i] >>> 42) & 0x3FFFFF) - CHUNK_BIAS;
            final int chunkZ = ((int) (sorted[i] >>> 20) & 0x3FFFFF) - CHUNK_BIAS;
            final int sectionIndex = (int) (sorted[i] >>> 12) & 0xFF;
            int m = 0;
            do {
                final int cell = (int) sorted[i] & 0xFFF;
                // Reconstruct the world position to re-fetch this cell's pending navtype.
                final int cx = (chunkX << 4) | (cell & 15);
                final int cy = minY + (sectionIndex << 4) + (cell >>> 8);
                final int cz = (chunkZ << 4) | ((cell >>> 4) & 15);
                cells[m] = (short) cell;
                navs[m] = (short) queue.get(BlockPos.asLong(cx, cy, cz));
                m++;
                i++;
            } while (i < n && (sorted[i] >>> 12) == groupPrefix);

            final NavSection[] sections = chunks == null ? null : chunks.get(NavStore.key(chunkX, chunkZ));
            if (sections == null || sectionIndex >= sections.length) continue; // unloaded since enqueue: drop
            final NavSection section = sections[sectionIndex];
            if (section == null) continue;
            final NavSection above = sectionIndex + 1 < sections.length ? sections[sectionIndex + 1] : null;
            final NavSection below = sectionIndex > 0 ? sections[sectionIndex - 1] : null;
            NavSectionBuilder.patchCells(section, above, below, cells, navs, m);
        }
        queue.clear();
    }

    /**
     * The grid-visibility decision for a CLEAN cell (package-private: the headless epoch test drives
     * this seam directly). {@code false} means the change is invisible to the nav grid: equal navtype ⇒
     * equal descriptor ⇒ identical inputs to every neighbour's flag/depth window ⇒ the patch would
     * recompute byte-identical values, so skipping BOTH the patch and the epoch bump exactly satisfies
     * the epoch contract above (unchanged epoch ⇒ re-search byte-identical). A DIRTY cell's decision
     * compares against its pending value instead — see {@link #enqueueIfChanges}.
     */
    static boolean changesGrid(NavSection section, int lx, int ly, int lz, short newNavtype) {
        return newNavtype != (short) section.getTraversalGrid().navtype(lx, ly, lz);
    }
}
