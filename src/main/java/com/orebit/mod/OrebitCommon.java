package com.orebit.mod;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.orebit.mod.commands.OrebitCommands;
import com.orebit.mod.config.ConfigLoader;
import com.orebit.mod.pathfinding.blockpathfinder.MiningModel;
import com.orebit.mod.platform.PlatformEvents;
import com.orebit.mod.platform.Worlds;
import com.orebit.mod.worldmodel.hpa.HpaMaintenance;
import com.orebit.mod.worldmodel.pathing.ChunkNavLoader;
import com.orebit.mod.worldmodel.pathing.NavGridUpdater;

import net.minecraft.server.level.ServerLevel;

/**
 * Loader-agnostic entry point. Each platform module (fabric, forge, neoforge) calls
 * {@link #init(PlatformEvents)} from its native initializer, passing an implementation
 * that bridges that loader's events to ours.
 */
public final class OrebitCommon {
    public static final String MOD_ID = "orebit";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private OrebitCommon() {}

    public static void init(PlatformEvents events) {
        // Owner capability config (PRD §10 Phase 1a / AGENCY-LAYER-PLAN): load config/orebit.properties
        // from the run dir once the server is up (the MinecraftServer is the loader-agnostic anchor the
        // ConfigDir seam reads the run dir from), generating a commented default file on first run. This
        // installs the active Config + the derived BotCaps the pathfinder and follower read; defaults
        // reproduce today's behaviour, so nothing changes until the owner edits the file.
        events.onServerStarted(ConfigLoader::load);

        // Mining tick/feasibility table (PRD §10 Phase 1c-1d / AGENCY-LAYER-PLAN "Tool use" + "Tick costs"):
        // precompute the resident mining-tick tables ONCE the block registry (and thus the NavBlock navtype
        // table) is populated. Referencing MiningModel.buildTable() forces NavBlock's static-init first, so
        // the tables cover every navtype/field-key. Threads the loaded mining-time config (ticksByHardness /
        // ticksToMineFlat) — registered AFTER ConfigLoader::load above, so the active Config is already
        // installed here. Off any hot path (one-time at server start); the A* then reads the table by index,
        // never recomputing mining time per node (favour-cpu-over-ram). Re-baked on /bot config reload too
        // (ConfigLoader.reload) so a changed mining-time model takes effect without a restart.
        events.onServerStarted(server -> MiningModel.buildTable(
                ConfigLoader.config().ticksByHardness(), ConfigLoader.config().ticksToMineFlat()));

        // World-model pipeline (PRD Phase 1): recompute a per-chunk nav grid on load and store it
        // (NavStore), recycling on unload. NavBlock is now a short-indexed packed-long table (the
        // old byte index overflowed) and the section reader is the self-degrading SectionPalette, so
        // this is safe across loaders/versions. Builds are deferred to the tick thread and budgeted
        // (ChunkNavLoader.MAX_BUILDS_PER_TICK) so idle players take no measurable hit. Nothing
        // consumes the grid yet — the pathfinder is the next milestone.
        ChunkNavLoader.register(events);

        // Block-update hook: keep the nav grid live as the world changes (player/bot mining, building,
        // pistons, fluids) by patching the changed cell + its within-section neighbourhood — no whole-
        // chunk rebuild. The trigger is the setBlockState mixin (overlay) firing BlockChangeEvents; until
        // that overlay lands this is inert. Retires the follower's per-replan refreshNavData shim.
        NavGridUpdater.register();

        // HPA* region-tier maintenance (PRD §6.3–6.5 Phase 3; HPA-IMPLEMENTATION.md §10/§12 "3f"):
        // attach the block-change listener that marks the containing level-0 leaf dirty so the cost
        // pyramid stays live as the world changes. Like NavGridUpdater, this is inert until the
        // setBlockState mixin overlay fires BlockChangeEvents, so registering it is harmless. The
        // debounced, budgeted recompute side (HpaMaintenance.flush) is driven from the world-tick-end
        // cadence below — the same cadence ChunkNavLoader drains its build queue on.
        HpaMaintenance.register();

        // World-tick-end drain for the HPA* dirty-leaf backlog: recompute up to MAX_LEAVES_PER_TICK
        // dirty leaves' faces and re-merge their ancestors, once per level per tick. A separate listener
        // (not folded into ChunkNavLoader's tick hook) keeps the region tier's wiring self-contained and
        // ChunkNavLoader untouched. No-op when nothing is dirty / no pyramid exists for the dimension.
        events.onWorldTickEnd(HpaMaintenance::flush);

        // Deterministic /bot come|stay|follow|here command surface (no LLM). The common command tree
        // builds on vanilla Brigadier; the loader seam only translates WHEN registration fires.
        OrebitCommands.register(events);

        events.onPlayerJoin(player -> {
            // CRITICAL: placeNewPlayer makes the bot a real PlayerList member, so the join
            // event fires for the bot too. Without this guard, spawning a bot spawns a bot
            // for the bot, ad infinitum (OOM). Never spawn a bot for one of our own bots.
            if (player instanceof FakePlayerEntity) {
                return;
            }
            LOGGER.info("[Orebit] Player {} connected.", player.getName().getString());
            // Defer the spawn to the next server tick. Since 1.20.2 the join handshake gained
            // a configuration phase, so JOIN fires while the client is still entering the PLAY
            // phase; spawning immediately races that transition.
            // Server via the level — Entity.getServer() was removed in MC 1.21.9.
            ((ServerLevel) Worlds.of(player)).getServer().execute(() -> BotManager.spawnBotFor(player));
        });

        events.onPlayerDisconnect(player -> {
            if (player instanceof FakePlayerEntity) {
                return; // our own bot leaving — nothing to clean up for it
            }
            LOGGER.info("[Orebit] Player {} disconnected.", player.getName().getString());
            BotManager.removeBotFor(player);
        });
    }
}
