package com.orebit.mod;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import com.orebit.mod.config.ConfigLoader;
import com.orebit.mod.farming.CropKind;
import com.orebit.mod.farming.CropKinds;
import com.orebit.mod.platform.BotInventory;
import com.orebit.mod.platform.FluidRead;
import com.orebit.mod.platform.ItemLookup;
import com.orebit.mod.platform.ItemUse;
import com.orebit.mod.platform.Worlds;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The {@code /bot farm} machine, owned by {@link AllyBotEntity} (the {@link BotGatherer} component
 * pattern — DESIGN-bot-abilities.md §2.1/§4): one TENDING PASS over the farm around where the
 * command was issued. It harvests every fully-grown crop (the narrowly-waived
 * {@link BotMining#requestHarvest} break — real drops, auto-picked-up), replants each harvested
 * cell from carried/collected seeds, plants any bare farmland it can seed, and — when it carries
 * a hoe and {@code farming.till} allows — tills hydrated ground (the exact vanilla water rule:
 * any water fluid in the 9×9×2 box, Chebyshev radius 4 at the cell's Y and Y+1, diagonals
 * included, waterlogged counts) and plants that too. When a full re-survey finds nothing left to
 * do, it reports the tally and STAYs.
 *
 * <p>Everything is state-verified, never assumed: a till/plant is performed via the REAL vanilla
 * use path ({@link ItemUse#useOnTop} — {@code ItemStack#useOn}, the same code a right-click
 * runs, with its own air-above/canSurvive gates + durability) and then confirmed by re-reading
 * the block; a cell that refuses is blacklisted for the run. Movement rides
 * {@link BotNavigator#driveToward} + the {@code navGaveUp} protocol. Farmland is only ever
 * WALKED on (trampling needs {@code fallDistance > 0.5} — a jump; flat-farm driving never
 * tramples).
 */
final class BotFarmer {

    private final AllyBotEntity bot;

    /** Phases of a {@code /bot farm} run. Stepped one phase per tick by {@link #farmLoopTick}.
     *  SWEEPUP chases the pass's own drops (yield + seeds, which carry vanilla's pickup delay)
     *  by ITEM LIFECYCLE — the gather-COLLECT pattern, no timers — before the final re-survey,
     *  so the pass never ends with its harvest lying on the field or its replant unseeded. */
    private enum FarmPhase { SURVEY, WORK, SWEEPUP }

    /** What a queued work cell needs done. */
    private enum TaskKind { HARVEST, PLANT, TILL }

    /** One actionable cell (crop cell for HARVEST; the SOIL cell for PLANT/TILL). */
    private record FarmTask(TaskKind kind, BlockPos cell) {}

    /** Player interaction reach (blocks, eye→block-centre) — the shared work-cell bound. */
    private static final double FARM_REACH = 4.5;
    /** Tight drive-arrival tolerance while approaching a work cell (the BotGatherer rationale). */
    private static final double FARM_ARRIVE_DIST = 0.6;
    private static final double FARM_ARRIVE_Y = 0.6;
    /** Vertical survey band around the anchor (farms are flat-ish; ±4 covers terraces). */
    private static final int SURVEY_DY = 4;

    private FarmPhase farmPhase;        // current phase (null = inactive)
    private BlockPos farmAnchor;        // where /bot farm was issued — the survey centre
    private final ArrayDeque<FarmTask> workQueue = new ArrayDeque<>();
    /** Cells that refused their action this run (planting failed, unreachable, …) — skipped. */
    private final HashSet<Long> refusedCells = new HashSet<>();
    private int harvested;              // mature crops broken
    private int planted;                // seeds planted (verified by the crop appearing)
    private int tilled;                 // blocks turned to farmland (verified)
    /** Whether the LAST completed WORK sweep did anything — gates the one follow-up re-survey. */
    private boolean sweepActed;
    /** SWEEPUP: the drop currently being chased (lifecycle-tracked; null = pick the next one). */
    private net.minecraft.world.entity.item.ItemEntity sweepDrop;
    /** SWEEPUP: drops proven unreachable this run (by entity id) — skipped. */
    private final HashSet<Integer> refusedDrops = new HashSet<>();
    /** Forensics only: the last front task logged (Debug-gated change detector). */
    private FarmTask lastLoggedTask;
    /** Reused cursor for the survey/hydration scans (no per-cell allocation). */
    private final BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();

    BotFarmer(AllyBotEntity bot) {
        this.bot = bot;
    }

    // ---- Read-only observation seams (headless harness only; NO logic change) -------------------

    /** Current farm phase name for the harness ({@code "IDLE"} when no run is active). Read-only. */
    String phaseName() {
        return farmPhase == null ? "IDLE" : farmPhase.name();
    }

    /** Mature crops harvested this run. Read-only. */
    int harvestedCount() {
        return harvested;
    }

    /** Seeds planted (crop verified present) this run. Read-only. */
    int plantedCount() {
        return planted;
    }

    /** Cells tilled to farmland (verified) this run. Read-only. */
    int tilledCount() {
        return tilled;
    }

    /** {@code /bot farm} entry point (mode switch/plan reset live on {@link AllyBotEntity#startFarm}). */
    void startFarm() {
        this.farmAnchor = bot.blockPosition().immutable();
        this.workQueue.clear();
        this.refusedCells.clear();
        this.harvested = 0;
        this.planted = 0;
        this.tilled = 0;
        this.sweepActed = true; // let the first survey run unconditionally
        this.sweepDrop = null;
        this.refusedDrops.clear();
        bot.navigator().clearNavGaveUp();
        this.farmPhase = FarmPhase.SURVEY;
    }

    /** One tick of the {@code /bot farm} state machine — dispatch to the current phase. */
    void farmLoopTick() {
        if (farmPhase == null) { bot.setMode(AllyBotEntity.Mode.STAY); return; } // defensive
        final ServerLevel level = (ServerLevel) Worlds.of(bot);
        switch (farmPhase) {
            case SURVEY -> farmSurvey(level);
            case WORK -> farmWork(level);
            case SWEEPUP -> farmSweepUp(level);
        }
    }

    /**
     * SURVEY: one pass over the work box (radius {@code farming.workRadius}, ±{@value #SURVEY_DY}
     * vertically) collecting actionable cells — harvests first (they fund the replants), then
     * bare-farmland plants, then hydrated tills — each nearest-first. Nothing actionable (after a
     * sweep that did something) → report the tally + STAY.
     */
    private void farmSurvey(ServerLevel level) {
        bot.setForward(0.0f);
        if (!sweepActed) { // the previous sweep did nothing new — the pass is complete
            finish();
            return;
        }
        sweepActed = false;
        workQueue.clear();
        final int radius = ConfigLoader.config().workRadius();
        final BotInventory inv = new BotInventory(bot);
        final boolean haveHoe = findHoeSlot(inv) >= 0;
        final boolean mayTill = haveHoe && ConfigLoader.config().till();
        final List<FarmTask> harvests = new ArrayList<>();
        final List<FarmTask> plants = new ArrayList<>();
        final List<FarmTask> tills = new ArrayList<>();
        final BlockPos a = farmAnchor;
        for (int dy = -SURVEY_DY; dy <= SURVEY_DY; dy++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    scratch.set(a.getX() + dx, a.getY() + dy, a.getZ() + dz);
                    if (refusedCells.contains(scratch.asLong())) continue;
                    final BlockState state = level.getBlockState(scratch);
                    final CropKind kind = CropKinds.byState(state);
                    if (kind != null && kind.isMature(state)) {
                        harvests.add(new FarmTask(TaskKind.HARVEST, scratch.immutable()));
                        continue;
                    }
                    if (state.is(Blocks.FARMLAND)
                            && level.getBlockState(scratch.above()).isAir()
                            && haveAnySeed(inv)) {
                        plants.add(new FarmTask(TaskKind.PLANT, scratch.immutable()));
                        continue;
                    }
                    if (mayTill && isTillable(state)
                            && level.getBlockState(scratch.above()).isAir()
                            && nearWater(level, scratch)
                            && haveAnySeed(inv)) {
                        tills.add(new FarmTask(TaskKind.TILL, scratch.immutable()));
                    }
                }
            }
        }
        sortNearestFirst(harvests);
        sortNearestFirst(plants);
        sortNearestFirst(tills);
        workQueue.addAll(harvests);
        workQueue.addAll(plants);
        workQueue.addAll(tills);
        if (workQueue.isEmpty()) {
            // Nothing block-actionable — but the pass's own drops may still lie on the field
            // (pickup delay / rolled away). Sweep them up first; SWEEPUP returns here when the
            // field is clear, and only then does an empty survey end the pass.
            if (nextFieldDrop(level) != null) {
                sweepDrop = null;
                farmPhase = FarmPhase.SWEEPUP;
                return;
            }
            finish();
            return;
        }
        farmPhase = FarmPhase.WORK;
    }

    /**
     * SWEEPUP: collect the pass's remaining drops by LIFECYCLE (the gather-COLLECT rules —
     * removed → next; airborne → stand and wait; grounded → drive to its LIVE position;
     * unreachable → skip honestly). Field clear → re-survey (picked-up seeds fund the replants;
     * {@code sweepActed} rides the pickup so the survey actually runs).
     */
    private void farmSweepUp(ServerLevel level) {
        if (sweepDrop == null || sweepDrop.isRemoved() || refusedDrops.contains(sweepDrop.getId())) {
            if (sweepDrop != null && sweepDrop.isRemoved()) {
                sweepActed = true; // something reached the inventory (or despawned — re-survey is cheap)
            }
            sweepDrop = nextFieldDrop(level);
            if (sweepDrop == null) {
                bot.navigator().clearPlan();
                farmPhase = FarmPhase.SURVEY;
                return;
            }
            if (Debug.ENABLED) { // cold, fires only on target CHANGE — sweep-chase forensics
                OrebitCommon.LOGGER.info("[Orebit] farm sweep chasing {} x{} at ({}, {}, {})",
                        ItemLookup.idOf(sweepDrop.getItem().getItem()), sweepDrop.getItem().getCount(),
                        String.format("%.2f", sweepDrop.getX()), String.format("%.2f", sweepDrop.getY()),
                        String.format("%.2f", sweepDrop.getZ()));
            }
        }
        if (!com.orebit.mod.platform.EntityState.onGround(sweepDrop) && !sweepDrop.isInWater()) {
            bot.setForward(0.0f); // mid-air: it lands within ticks
            return;
        }
        // The drop's goal FLOOR cell: an item resting on a PARTIAL-height block (farmland 15/16,
        // slab, carpet) sits INSIDE that block's cell — blockPosition() IS its floor; only an item
        // whose own cell is air (resting exactly on a full block's top plane) floors one below.
        // blockPosition().below() unconditionally would aim a buried, impossible goal under
        // farmland and hold forever (the partial-floor family, diagnosed live).
        final BlockPos feetCell = sweepDrop.blockPosition();
        final BlockPos dropFloor = level.getBlockState(feetCell).isAir() ? feetCell.below() : feetCell;
        final boolean arrived = bot.navigator().driveToward(sweepDrop.getX(), sweepDrop.getY(),
                sweepDrop.getZ(), dropFloor, FARM_ARRIVE_DIST, FARM_ARRIVE_Y, 0, 0);
        // Standing ON a live drop with NO free slot = it can never land in us (vanilla only merges
        // into free/partial space; a full inventory would stand here forever) — leave it honestly.
        // With a free slot, standing here is finite: the pickup delay expires and vanilla takes it.
        if (arrived && bot.getInventory().getFreeSlot() < 0) {
            refusedDrops.add(sweepDrop.getId());
            return;
        }
        if (bot.navigator().navGaveUp()) {
            refusedDrops.add(sweepDrop.getId());
            bot.navigator().clearNavGaveUp();
            bot.navigator().clearPlan();
        }
    }

    /** The nearest live drop in the work box worth sweeping (any item — a farm pass cleans its
     *  field; junk walked over is harmless), or {@code null} when the field is clear. */
    private net.minecraft.world.entity.item.ItemEntity nextFieldDrop(ServerLevel level) {
        final int radius = ConfigLoader.config().workRadius();
        final BlockPos a = farmAnchor;
        final net.minecraft.world.phys.AABB box = new net.minecraft.world.phys.AABB(
                a.getX() - radius, a.getY() - SURVEY_DY, a.getZ() - radius,
                a.getX() + radius + 1, a.getY() + SURVEY_DY + 1, a.getZ() + radius + 1);
        net.minecraft.world.entity.item.ItemEntity nearest = null;
        double bestD = Double.MAX_VALUE;
        for (net.minecraft.world.entity.item.ItemEntity e : level.getEntitiesOfClass(
                net.minecraft.world.entity.item.ItemEntity.class, box,
                net.minecraft.world.entity.item.ItemEntity::isAlive)) {
            if (refusedDrops.contains(e.getId())) continue;
            final double d = e.distanceToSqr(bot.getX(), bot.getY(), bot.getZ());
            if (d < bestD) { bestD = d; nearest = e; }
        }
        return nearest;
    }

    /**
     * WORK: drain the queue nearest-cell-by-cell. Each task re-validates its cell's LIVE state
     * (someone may have beaten us to it), drives within reach, acts through the real vanilla
     * paths, and VERIFIES by re-reading the world. Queue drained → one re-survey (harvest drops
     * fund replants; a till exposes a plant) — the re-survey ends the run when nothing new shows.
     */
    private void farmWork(ServerLevel level) {
        final FarmTask task = workQueue.peekFirst();
        if (task == null) {
            farmPhase = FarmPhase.SURVEY;
            return;
        }
        if (Debug.ENABLED && !task.equals(lastLoggedTask)) { // cold, fires only on front-task CHANGE
            lastLoggedTask = task;
            OrebitCommon.LOGGER.info("[Orebit] farm work {} at ({},{},{}) queue={}",
                    task.kind(), task.cell().getX(), task.cell().getY(), task.cell().getZ(),
                    workQueue.size());
        }
        final BlockPos cell = task.cell();
        // Re-validate against the LIVE world; a stale task just drops.
        final BlockState state = level.getBlockState(cell);
        final boolean stillValid = switch (task.kind()) {
            case HARVEST -> {
                final CropKind kind = CropKinds.byState(state);
                yield kind != null && kind.isMature(state);
            }
            case PLANT -> state.is(Blocks.FARMLAND) && level.getBlockState(cell.above()).isAir();
            case TILL -> isTillable(state) && level.getBlockState(cell.above()).isAir();
        };
        if (!stillValid) {
            // The break lands in mining.tick AFTER this machine (the gatherMine ordering rule), so
            // a HARVEST completion is observed HERE, at the top of the next tick: mature → air
            // while its task was front-of-queue = our harvest. Count it; the bare farmland is
            // replanted by the follow-up re-survey (NOT immediately — the drops carry vanilla's
            // ~10-tick pickup delay, so the seeds aren't in the inventory yet).
            if (task.kind() == TaskKind.HARVEST && state.isAir()) {
                countHarvest();
            }
            workQueue.pollFirst();
            return;
        }
        if (!withinReach(cell)) {
            final boolean arrived = bot.navigator().driveToward(
                    cell.getX() + 0.5, cell.getY() + 0.5, cell.getZ() + 0.5, cell,
                    FARM_ARRIVE_DIST, FARM_ARRIVE_Y);
            if (!arrived && bot.navigator().navGaveUp()) {
                refusedCells.add(cell.asLong());
                workQueue.pollFirst();
                bot.navigator().clearNavGaveUp();
                bot.navigator().clearPlan();
            }
            return;
        }
        bot.setForward(0.0f);
        final BotInventory inv = new BotInventory(bot);
        switch (task.kind()) {
            case HARVEST ->
                // Timed break through the hands (instant for hardness-0 crops, real drops, the §4
                // harvest waiver). Completion is observed by the NEXT tick's re-validate above
                // (mature → air), which counts it and queues the replant.
                bot.mining().requestHarvest(cell);
            case PLANT -> {
                final int seedSlot = findAnySeedSlot(inv);
                if (seedSlot < 0) {
                    // Out of seeds RIGHT NOW (fresh harvest drops carry a pickup delay) — drop the
                    // task WITHOUT blacklisting the cell: the follow-up re-survey re-lists it once
                    // seeds have been walked over and picked up.
                    workQueue.pollFirst();
                    return;
                }
                bot.lookAtCell(cell.getX(), cell.getY(), cell.getZ());
                inv.equipSlot(seedSlot);
                ItemUse.useOnTop(bot, cell);
                bot.swing(InteractionHand.MAIN_HAND);
                if (!level.getBlockState(cell.above()).isAir()) { // the crop appeared — verified
                    planted++;
                    sweepActed = true;
                } else {
                    refusedCells.add(cell.asLong()); // vanilla refused (light/soil) — don't loop
                }
                workQueue.pollFirst();
            }
            case TILL -> {
                final int hoeSlot = findHoeSlot(inv);
                if (hoeSlot < 0) { // hoe broke mid-run
                    refusedCells.add(cell.asLong());
                    workQueue.pollFirst();
                    return;
                }
                bot.lookAtCell(cell.getX(), cell.getY(), cell.getZ());
                inv.equipSlot(hoeSlot);
                ItemUse.useOnTop(bot, cell);
                bot.swing(InteractionHand.MAIN_HAND);
                if (level.getBlockState(cell).is(Blocks.FARMLAND)) { // verified
                    tilled++;
                    sweepActed = true;
                    // Plant it right away next tick, from where we stand.
                    workQueue.pollFirst();
                    workQueue.addFirst(new FarmTask(TaskKind.PLANT, cell));
                    return;
                }
                refusedCells.add(cell.asLong());
                workQueue.pollFirst();
            }
        }
    }

    /** End the pass: tally chat + STAY. */
    private void finish() {
        if (harvested == 0 && planted == 0 && tilled == 0) {
            bot.chat("nothing to farm here.");
        } else {
            bot.chat("farm pass done — harvested " + harvested + ", planted " + planted
                    + ", tilled " + tilled + ".");
        }
        bot.setMode(AllyBotEntity.Mode.STAY);
    }

    /** HARVEST completion accounting: called by the re-validate drop path in {@link #farmWork}. */
    private void countHarvest() {
        harvested++;
        sweepActed = true;
    }

    /**
     * The vanilla farmland hydration test, verbatim (FarmBlock#isNearWater): ANY water fluid in
     * the 9×9×2 box — Chebyshev radius 4 horizontally (diagonals included), at the cell's own Y
     * and Y+1; waterlogged blocks count; intervening blocks are irrelevant.
     */
    private boolean nearWater(ServerLevel level, BlockPos cell) {
        final BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dy = 0; dy <= 1; dy++) {
            for (int dz = -4; dz <= 4; dz++) {
                for (int dx = -4; dx <= 4; dx++) {
                    m.set(cell.getX() + dx, cell.getY() + dy, cell.getZ() + dz);
                    if (FluidRead.isWater(level, m)) return true;
                }
            }
        }
        return false;
    }

    /** The hoe-tillable ground set (HoeItem TILLABLES → farmland; coarse/rooted dirt excluded —
     *  they till to plain dirt, a two-step the farmer doesn't chase in v1). */
    private static boolean isTillable(BlockState state) {
        return state.is(Blocks.GRASS_BLOCK) || state.is(Blocks.DIRT) || state.is(Blocks.DIRT_PATH);
    }

    /** First storage slot holding any hoe (id suffix {@code _hoe} — the ItemClasses convention). */
    private static int findHoeSlot(BotInventory inv) {
        return inv.findSlotMatching(s -> ItemLookup.idOf(s.getItem()).endsWith("_hoe"));
    }

    /** First storage slot holding any known crop seed (registration order via the id match). */
    private static int findAnySeedSlot(BotInventory inv) {
        return inv.findSlotMatching(s -> CropKinds.isSeedItem(s.getItem()));
    }

    private static boolean haveAnySeed(BotInventory inv) {
        return findAnySeedSlot(inv) >= 0;
    }

    private void sortNearestFirst(List<FarmTask> tasks) {
        final double bx = bot.getX(), by = bot.getY(), bz = bot.getZ();
        tasks.sort((p, q) -> Double.compare(distSq(p.cell(), bx, by, bz), distSq(q.cell(), bx, by, bz)));
    }

    private static double distSq(BlockPos p, double x, double y, double z) {
        final double dx = p.getX() + 0.5 - x, dy = p.getY() + 0.5 - y, dz = p.getZ() + 0.5 - z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** True when {@code cell}'s centre is within {@link #FARM_REACH} of the bot's eyes. */
    private boolean withinReach(BlockPos cell) {
        final double dx = cell.getX() + 0.5 - bot.getX();
        final double dy = cell.getY() + 0.5 - bot.getEyeY();
        final double dz = cell.getZ() + 0.5 - bot.getZ();
        return dx * dx + dy * dy + dz * dz <= FARM_REACH * FARM_REACH;
    }
}
