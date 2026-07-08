package com.orebit.mod;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.orebit.mod.commands.OrebitCommands;
import com.orebit.mod.config.ConfigLoader;
import com.orebit.mod.pathfinding.async.PlanExecutor;
import com.orebit.mod.pathfinding.blockpathfinder.MiningModel;
import com.orebit.mod.platform.PlatformEvents;
import com.orebit.mod.platform.Worlds;
import com.orebit.mod.worldmodel.hpa.HpaMaintenance;
import com.orebit.mod.worldmodel.pathing.ChunkNavLoader;
import com.orebit.mod.worldmodel.pathing.NavGridUpdater;
import com.orebit.mod.worldmodel.pathing.NavReclaim;
import com.orebit.mod.worldmodel.pathing.NavWarmup;

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

        // Boot-time pathfinder JIT warm-up (internal_docs/PERF-DESIGN-warmup-searches.md): ~500 synthetic
        // searches over a private in-memory fixture so the first REAL search doesn't run interpreted/C1-cold
        // (~16 ms for a 2-node search — PERF-PROFILE-2026-07.md S1 — landing on a live player tick, since the
        // bot spawns on join). SYNCHRONOUS on the server thread deliberately: JIT warmth is JVM-global, but
        // the ThreadLocal search scratch and the unsynchronized NavSectionPool are tick-thread-confined.
        // Registered AFTER ConfigLoader::load (reads the pathing.warmup keys + the owner's real BotCaps) and
        // AFTER MiningModel.buildTable (movements price breaks from its table; NavWarmup refuses to run
        // un-baked). SERVER_STARTED fires before the tick loop serves players, so no real search races it.
        events.onServerStarted(server -> {
            if (ConfigLoader.config().warmup()) {
                NavWarmup.run(ConfigLoader.config().warmupBudgetMs());
            }
        });

        // Background planner pool (DESIGN-background-pathfinding.md §3): started once, AFTER ConfigLoader::load
        // (reads pathing.async/maxThreads/asyncSearchBudgetMs) and after the warm-up above (JIT is warm before
        // the first submitted search; each pool thread warms its own ThreadLocal scratch as it starts). When
        // pathing.async=false this never runs, PlanExecutor.instance() stays null, and every search remains
        // synchronous on the tick thread, node-capped by pathing.syncSearchBudgetNodes.
        events.onServerStarted(server -> {
            if (ConfigLoader.config().asyncPathing()) {
                PlanExecutor.start(ConfigLoader.config().maxThreads(), ConfigLoader.config().asyncSearchBudgetMs());
            }
        });

        // NavSection retirement drain (DESIGN-background-pathfinding.md §4.1): once per level-tick, advance
        // the reclamation epoch and return retired sections to the pool once no in-flight background search
        // can still hold them. With async off this degrades to a one-tick recycle deferral (minActiveStamp()
        // is MAX_VALUE with no executor) — behaviourally invisible, so it is registered unconditionally.
        events.onWorldTickEnd(level -> NavReclaim.tick(PlanExecutor.minActiveStamp()));

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
            // Marshal the teardown onto the SERVER THREAD (mirrors the deferred spawn in onPlayerJoin).
            // This event fires on the network (Netty) thread; removeBotFor -> PlayerList.remove(bot) mutates
            // the player list + entity section manager and SAVES the bot's player data. Doing that off-thread
            // races the server thread — most visibly on Save & Exit, where IntegratedServer.halt saves player
            // data at the same instant: the two saves collide on the bot's <uuid>.dat rename
            // (NoSuchFileException) and the concurrent PlayerList mutation kills the shutdown before it
            // completes. The server may already be stopping, in which case the task simply never runs (the
            // whole server is going away) — either way the bot is never removed off-thread.
            ((ServerLevel) Worlds.of(player)).getServer().execute(() -> BotManager.removeBotFor(player));
        });

        // Headless end-to-end scenario harness (the :fabric:<ver>:runAutotest run config): spawns one
        // bot on a known-seed dedicated server, drives one goto, writes a result file, halts. INERT
        // unless the JVM was launched with -Dorebit.autotest (register() is a single property read in
        // production). Registered LAST deliberately: its SERVER_STARTED hook must run after the config /
        // mining-table / warm-up / planner-pool installs above (loader events fire in registration order).
        HeadlessAutotest.register(events);
    }
}
