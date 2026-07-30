package com.orebit.mod;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.orebit.mod.building.PaletteResolver;
import com.orebit.mod.building.Schematic;
import com.orebit.mod.config.ConfigLoader;
import com.orebit.mod.platform.BotInventory;
import com.orebit.mod.platform.ItemLookup;
import com.orebit.mod.platform.WorldEdits;
import com.orebit.mod.platform.Worlds;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * The {@code /bot build} machine, owned by {@link AllyBotEntity} (the {@link BotGatherer}
 * component pattern — DESIGN-bot-abilities.md §2.1/§6): execute a parsed {@link Schematic} at an
 * origin, from the bot's REAL inventory. A DIFF-driven reactive loop (idempotent + resumable —
 * the Baritone lesson set, minus its client-side orientation failure class):
 * <ul>
 *   <li><b>SCAN</b> diffs schematic vs LIVE world, bottom-up (y-ascending, nearest-first within a
 *       layer), IGNORING the self-healing connection properties (fence/pane/wall sides, stair
 *       shape — vanilla {@code updateShape} converges them once neighbours exist) and treating
 *       multi-block partner cells (door {@code half=upper}, bed {@code part=head}) as
 *       satisfied-by-partner.</li>
 *   <li><b>WORK</b> drains the queue: a wrong occupant is CLEARED through the timed
 *       {@link BotMining} break (real drops; {@code Config.mayBreak} refuses protected — those
 *       cells are counted and reported, never forced); an empty cell is PLACED with the EXACT
 *       palette state ({@code WorldEdits.placeBlock} — §10-D4: direct server-side placement,
 *       survival costs charged by consuming the matching {@link BlockItem}); every act is
 *       VERIFIED by re-reading the world.</li>
 *   <li>Convergence without timers: SCAN→WORK sweeps repeat while a sweep makes PROGRESS (a
 *       verified place/clear); a sweep with pending work but zero progress means the remainder is
 *       unbuildable from here (unsupported/unreachable/refused) — reported honestly, then STAY.</li>
 * </ul>
 */
final class BotBuilder {

    private final AllyBotEntity bot;

    /** Phases of a {@code /bot build} run. Stepped one phase per tick by {@link #buildLoopTick}. */
    private enum BuildPhase { SCAN, WORK }

    private enum ActionKind { PLACE, CLEAR }

    /** One actionable cell ({@code wanted} null for a pure clear-to-air). */
    private record BuildAction(ActionKind kind, BlockPos cell, BlockState wanted) {}

    /** Player interaction reach (blocks, eye→block-centre) — the shared work-cell bound. */
    private static final double BUILD_REACH = 4.5;
    private static final double BUILD_ARRIVE_DIST = 0.6;
    private static final double BUILD_ARRIVE_Y = 0.6;

    /** Connection-ish property NAMES excluded from the diff — vanilla {@code updateShape}
     *  recomputes them from neighbours, so palette values converge on their own (comparing them
     *  strictly is Baritone's oscillation bug). */
    private static final java.util.Set<String> SELF_HEALING_PROPS =
            java.util.Set.of("north", "south", "east", "west", "up", "down", "shape",
                    "distance", "persistent");

    private BuildPhase buildPhase;      // current phase (null = inactive)
    private Schematic schematic;
    private BlockPos origin;            // world position of the schematic origin
    /** Resolved palette per region (same index space; null entry = unknown block id here). */
    private final List<BlockState[]> resolved = new ArrayList<>();
    private final ArrayDeque<BuildAction> workQueue = new ArrayDeque<>();
    private final HashSet<Long> unreachableCells = new HashSet<>();
    private final HashSet<Long> refusedClears = new HashSet<>();
    /** Missing material counts by block id (the report + the future work-tree planner's input). */
    private final Map<String, Integer> missing = new LinkedHashMap<>();
    private int placed;
    private int cleared;
    private int unknownCells;
    /** Whether the CURRENT sweep verified any place/clear — the convergence signal. */
    private boolean sweepProgress;
    private boolean firstSweep;

    BotBuilder(AllyBotEntity bot) {
        this.bot = bot;
    }

    // ---- Read-only observation seams (headless harness only) -----------------------------------

    String phaseName() {
        return buildPhase == null ? "IDLE" : buildPhase.name();
    }

    int placedCount() {
        return placed;
    }

    int clearedCount() {
        return cleared;
    }

    /** {@code /bot build <name> <x y z>} entry point (mode switch lives on
     *  {@link AllyBotEntity#startBuild}). {@code origin} anchors every region's min corner offset. */
    void startBuild(Schematic schematic, BlockPos origin) {
        this.schematic = schematic;
        this.origin = origin.immutable();
        this.resolved.clear();
        for (Schematic.Region r : schematic.regions()) {
            final BlockState[] states = new BlockState[r.palette().size()];
            for (int i = 0; i < states.length; i++) {
                states[i] = PaletteResolver.resolve(r.palette().get(i));
            }
            this.resolved.add(states);
        }
        this.workQueue.clear();
        this.unreachableCells.clear();
        this.refusedClears.clear();
        this.missing.clear();
        this.placed = 0;
        this.cleared = 0;
        this.unknownCells = 0;
        this.sweepProgress = false;
        this.firstSweep = true;
        bot.navigator().clearNavGaveUp();
        this.buildPhase = BuildPhase.SCAN;
    }

    /** One tick of the {@code /bot build} state machine — dispatch to the current phase. */
    void buildLoopTick() {
        if (buildPhase == null) { bot.setMode(AllyBotEntity.Mode.STAY); return; } // defensive
        final ServerLevel level = (ServerLevel) Worlds.of(bot);
        switch (buildPhase) {
            case SCAN -> buildScan(level);
            case WORK -> buildWork(level);
        }
    }

    /** SCAN: one full diff pass (bottom-up, nearest-first per layer) into the work queue. */
    private void buildScan(ServerLevel level) {
        bot.setForward(0.0f);
        if (!firstSweep && !sweepProgress) {
            // The previous sweep changed nothing — whatever remains is unbuildable from here.
            finish(level);
            return;
        }
        firstSweep = false;
        sweepProgress = false;
        workQueue.clear();
        unknownCells = 0;
        final List<BuildAction> actions = new ArrayList<>();
        for (int ri = 0; ri < schematic.regions().size(); ri++) {
            final Schematic.Region r = schematic.regions().get(ri);
            final BlockState[] states = resolved.get(ri);
            for (int y = 0; y < r.sizeY(); y++) {
                for (int z = 0; z < r.sizeZ(); z++) {
                    for (int x = 0; x < r.sizeX(); x++) {
                        final int pi = r.paletteIndexAt(x, y, z);
                        final BlockState wanted = pi >= 0 && pi < states.length ? states[pi] : null;
                        final boolean wantAir = pi == 0
                                || "minecraft:air".equals(r.palette().get(pi).blockId());
                        if (!wantAir && wanted == null) { unknownCells++; continue; }
                        if (!wantAir && isPartnerCell(r.palette().get(pi))) continue;
                        final BlockPos cell = new BlockPos(
                                origin.getX() + r.minX() + x,
                                origin.getY() + r.minY() + y,
                                origin.getZ() + r.minZ() + z);
                        if (unreachableCells.contains(cell.asLong())
                                || refusedClears.contains(cell.asLong())) continue;
                        final BlockState world = level.getBlockState(cell);
                        if (wantAir) {
                            if (!world.isAir()) {
                                actions.add(new BuildAction(ActionKind.CLEAR, cell, null));
                            }
                            continue;
                        }
                        if (statesMatch(world, wanted)) continue;
                        if (world.isAir()) {
                            actions.add(new BuildAction(ActionKind.PLACE, cell, wanted));
                        } else {
                            // Wrong occupant: clear first (the follow-up sweep places).
                            actions.add(new BuildAction(ActionKind.CLEAR, cell, wanted));
                        }
                    }
                }
            }
        }
        if (actions.isEmpty()) {
            finish(level);
            return;
        }
        // Bottom-up, nearest-first within a layer: lower Y strictly first (support before
        // supported), distance breaks ties (short walks).
        final double bx = bot.getX(), by = bot.getY(), bz = bot.getZ();
        actions.sort((p, q) -> {
            final int dy = Integer.compare(p.cell().getY(), q.cell().getY());
            if (dy != 0) return dy;
            return Double.compare(distSq(p.cell(), bx, by, bz), distSq(q.cell(), bx, by, bz));
        });
        workQueue.addAll(actions);
        buildPhase = BuildPhase.WORK;
    }

    /** WORK: drain the queue cell by cell; every act verified against the LIVE world. */
    private void buildWork(ServerLevel level) {
        final BuildAction action = workQueue.peekFirst();
        if (action == null) {
            buildPhase = BuildPhase.SCAN;
            return;
        }
        final BlockPos cell = action.cell();
        final BlockState world = level.getBlockState(cell);
        // Re-validate against the live world; completions are observed HERE (the mining break
        // lands after this machine each tick — the gatherMine ordering rule).
        switch (action.kind()) {
            case CLEAR -> {
                if (world.isAir()) { // our break landed (or someone cleared it) — progress
                    cleared++;
                    sweepProgress = true;
                    workQueue.pollFirst();
                    return;
                }
            }
            case PLACE -> {
                if (!world.isAir()) { // occupied since the scan (or our earlier place) — re-check at SCAN
                    workQueue.pollFirst();
                    return;
                }
            }
        }
        if (!withinReach(cell)) {
            final boolean arrived = bot.navigator().driveToward(
                    cell.getX() + 0.5, cell.getY() + 0.5, cell.getZ() + 0.5, cell,
                    BUILD_ARRIVE_DIST, BUILD_ARRIVE_Y);
            if (!arrived && bot.navigator().navGaveUp()) {
                unreachableCells.add(cell.asLong());
                workQueue.pollFirst();
                bot.navigator().clearNavGaveUp();
                bot.navigator().clearPlan();
            }
            return;
        }
        bot.setForward(0.0f);
        switch (action.kind()) {
            case CLEAR -> {
                // The executor-side policy gate: a protected/unbreakable occupant is REFUSED here
                // (BotMining's backstop would release silently every tick = a stall) — count once
                // and move on; the final report names the count.
                final float destroy = world.getDestroySpeed(level, cell);
                if (!ConfigLoader.config().clearMismatches()
                        || !ConfigLoader.config().mayBreak(world, destroy)) {
                    refusedClears.add(cell.asLong());
                    workQueue.pollFirst();
                    return;
                }
                bot.mining().request(cell); // timed break, real drops; completion observed above
            }
            case PLACE -> {
                final BotInventory inv = new BotInventory(bot);
                final int slot = inv.findSlotMatching(s -> s.getItem() instanceof BlockItem bi
                        && bi.getBlock() == action.wanted().getBlock());
                if (slot < 0) {
                    missing.merge(ItemLookup.idOf(action.wanted().getBlock().asItem()), 1, Integer::sum);
                    workQueue.pollFirst();
                    return;
                }
                bot.lookAtCell(cell.getX(), cell.getY(), cell.getZ());
                bot.getInventory().removeItem(slot, 1);
                WorldEdits.placeBlock(level, cell, action.wanted());
                bot.swing(InteractionHand.MAIN_HAND);
                // Verify synchronously: the exact state may self-heal its connection props via the
                // neighbour updates (fine — those are diff-ignored); a POP (unsupported torch) is
                // NOT progress and the cell retries on a later sweep once its support exists.
                if (level.getBlockState(cell).getBlock() == action.wanted().getBlock()) {
                    placed++;
                    sweepProgress = true;
                }
                workQueue.pollFirst();
            }
        }
    }

    /** End the run: the honest tally (what happened AND what could not), then STAY. */
    private void finish(ServerLevel level) {
        final StringBuilder sb = new StringBuilder("build done — placed " + placed
                + ", cleared " + cleared + ".");
        int remaining = 0;
        if (!workQueue.isEmpty()) remaining += workQueue.size();
        if (remaining > 0) sb.append(" couldn't finish ").append(remaining).append(" cells.");
        if (!unreachableCells.isEmpty()) sb.append(" unreachable: ").append(unreachableCells.size()).append(".");
        if (!refusedClears.isEmpty()) sb.append(" protected/unbreakable: ").append(refusedClears.size()).append(".");
        if (unknownCells > 0) sb.append(" unknown blocks (skipped): ").append(unknownCells).append(".");
        if (!missing.isEmpty()) {
            sb.append(" missing:");
            int shown = 0;
            for (Map.Entry<String, Integer> e : missing.entrySet()) {
                sb.append(' ').append(e.getValue()).append("x ").append(shortId(e.getKey()));
                if (++shown >= 3) break;
            }
            if (missing.size() > 3) sb.append(" …");
            sb.append('.');
        }
        bot.chat(sb.toString());
        bot.setMode(AllyBotEntity.Mode.STAY);
    }

    /** The diff: same block + every wanted property equal, EXCEPT the self-healing set. */
    private static boolean statesMatch(BlockState world, BlockState wanted) {
        if (world.getBlock() != wanted.getBlock()) return false;
        for (Property<?> p : wanted.getProperties()) {
            if (SELF_HEALING_PROPS.contains(p.getName())) continue;
            if (!world.getProperties().contains(p)) continue;
            if (!world.getValue(p).equals(wanted.getValue(p))) return false;
        }
        return true;
    }

    /** Multi-block partner cells are satisfied by placing their root: door tops carry
     *  {@code half=upper} (stairs use top/bottom — disjoint values), bed heads {@code part=head}. */
    private static boolean isPartnerCell(Schematic.PaletteEntry entry) {
        return "upper".equals(entry.properties().get("half"))
                || "head".equals(entry.properties().get("part"));
    }

    private static String shortId(String id) {
        final int colon = id.indexOf(':');
        return colon >= 0 ? id.substring(colon + 1) : id;
    }

    private static double distSq(BlockPos p, double x, double y, double z) {
        final double dx = p.getX() + 0.5 - x, dy = p.getY() + 0.5 - y, dz = p.getZ() + 0.5 - z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** True when {@code cell}'s centre is within {@link #BUILD_REACH} of the bot's eyes. */
    private boolean withinReach(BlockPos cell) {
        final double dx = cell.getX() + 0.5 - bot.getX();
        final double dy = cell.getY() + 0.5 - bot.getEyeY();
        final double dz = cell.getZ() + 0.5 - bot.getZ();
        return dx * dx + dy * dy + dz * dz <= BUILD_REACH * BUILD_REACH;
    }
}
