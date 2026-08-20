package com.orebit.mod.platform;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Version-selected per-tick player chunk-ticket recentre — the server-side stand-in for the ticket
 * maintenance a real client's move packets drive. Baseline flavor (MC 1.17.1 upward): the public
 * {@code ServerChunkCache.move(ServerPlayer)} call, byte-identical 1.20.2 → 1.21.11 (javap-verified);
 * kept baseline-only until a compile gate proves an older version drifted (then a lower-era override
 * splits — do not pre-split).
 *
 * <p><b>Why this exists (the 2026-08-19 frozen-chunk forensic).</b> Vanilla plants a player's chunk
 * tickets once at login ({@code placeNewPlayer} → {@code ChunkMap.addEntity} →
 * {@code DistanceManager.addPlayer}) and from then on recentres them in exactly ONE place:
 * {@code ServerGamePacketListenerImpl.handleMovePlayer}, which calls
 * {@code this.player.level().getChunkSource().move(this.player)} after applying each inbound move
 * packet (~20/s from a real client). {@code ServerPlayer.tick()}'s own move call is
 * spectator-camera-gated, so the packet handler is the only driver. Our bot's
 * {@code FakeClientConnection} suppresses all I/O — no move packets are ever delivered, the handler
 * never runs, and the bot's tickets stay planted at its SPAWN section forever while the entity walks
 * away from them.
 *
 * <p><b>Consequence:</b> beyond the spawn bubble the world is loaded-but-frozen. Player tickets grant
 * BLOCK_TICKING (scheduled + random ticks) only to {@code simulation-distance} and mere LOADING out to
 * {@code view-distance}, both centred on the ticket, not the entity. Conviction (BREAKDIAG autotest,
 * 2026-08-19): the bot broke a podzol supporting a bamboo stalk at (260, 83, 452); vanilla armed the
 * bamboo's 1-tick destroy correctly ({@code scheduledTick=true canSurvive=false}) and the tripwire
 * watched it stay armed, undelivered, for 4,400+ ticks — floating bamboo, wedging the bot against the
 * un-popped stalk. The radius arithmetic closed the case with zero exceptions: start chunk (3, 15),
 * every observed random-tick vine growth within Chebyshev ≤ 10 (sim distance) of it, and the bamboo
 * chunk (16, 28) at distance 13 — inside view-16 (loaded) but outside sim-10 (never ticked). A repro
 * started at chunk (15, 24), distance ~4 from the same cell, cascaded cleanly.
 *
 * <p><b>The fix is the vanilla call itself.</b> {@code ServerChunkCache.move} is precisely what
 * {@code handleMovePlayer} invokes; it self-guards on section change ({@code ChunkMap.move} compares
 * {@code getLastSectionPos()} against the current section and touches the {@code DistanceManager}
 * remove/add pair only when they differ — plus the same entity-tracking refresh a real move drives),
 * so calling it every tick costs a section compare when the bot hasn't crossed a section boundary.
 * Bots then cost exactly what players cost under the server's own view/sim distances.
 */
public final class ChunkTracking {
    private ChunkTracking() {}

    /** Recentre {@code bot}'s player chunk tickets (and tracking view) on its current section. */
    public static void recenter(ServerPlayer bot) {
        ((ServerLevel) Worlds.of(bot)).getChunkSource().move(bot);
    }
}
