package com.orebit.mod.pathfinding.async;

import com.orebit.mod.pathfinding.blockpathfinder.BotCaps;
import com.orebit.mod.pathfinding.blockpathfinder.EditSnapshot;
import com.orebit.mod.pathfinding.blockpathfinder.MovementContext;
import com.orebit.mod.pathfinding.blockpathfinder.RegionBound;
import com.orebit.mod.pathfinding.regionpathfinder.RegionCostField;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * One block-tier search, snapshotted on the tick thread at submit time (DESIGN-background-pathfinding.md
 * §3.2) — everything a planner thread needs, and nothing live: {@code level} is used for IDENTITY only
 * (it keys the {@code NavStore} chunk map and min-Y; the worker's {@link
 * com.orebit.mod.worldmodel.pathing.NavGridView#background background} view never reads live chunks),
 * {@code caps} and {@code inventory} are already immutable records, {@code cuboidCap} is a value box, and
 * {@code baseline} is the unpooled splice seed. The submit/complete queue handoff gives the worker
 * happens-before on everything the tick thread wrote before submit — the search sees the world as of no
 * earlier than submit time, and divergence after that is absorbed by seam acceptance + the executor's
 * plans-are-hints backstops.
 *
 * @param level       dimension identity (chunk-map key + min-Y) — never dereferenced for live blocks
 * @param startFloor  the floor cell the search starts from (= the seam's predicted start)
 * @param target      the window target ({@code PathPlan.windowTarget()}, computed on the tick thread)
 * @param caps        the bot's capability gate (immutable record)
 * @param inventory   the per-pathfind inventory feasibility snapshot, or {@code null} (caps-only)
 * @param startMode   the bot's live pose seed (STANDING/PRONE/{@code BlockPathfinder.MODE_AUTO})
 * @param cuboidCap   the macro growth cap (value box), or {@code null}
 * @param baseline    the splice seed ({@code null} = unseeded)
 * @param budgetNanos the wall-clock search budget ({@code 0} = node-cap only)
 * @param field       the region-informed cost-to-goal heuristic field, built on the tick thread at submit and
 *                    read-only (write-once) on the worker, or {@code null} (no region heuristic)
 */
public record SearchRequest(ServerLevel level, BlockPos startFloor, BlockPos target, BotCaps caps,
                            MovementContext.InventoryView inventory, int startMode, RegionBound cuboidCap,
                            EditSnapshot baseline, long budgetNanos, RegionCostField field) {
}
