/**
 * PlayerMonitor.java
 *
 * Main Component: manager/
 * Environment: MAIN
 *
 * This class observes the lifecycle of human players within the game world.
 * It tracks player join and leave events (in both multiplayer and singleplayer),
 * and is responsible for spawning and despawning bots that are associated
 * with specific players. It maintains the mapping between player UUIDs and
 * their commandable bot instances, ensuring a seamless experience regardless
 * of game mode or server type.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Listens for player join and leave events from the Minecraft server
 * - Calls into `BotLifecycleHooks` to create or destroy player-linked bots
 * - Stores and maintains mappings between players and their assigned bots
 * - Provides APIs for resolving a player UUID to their bot (or vice versa)
 * - Handles edge cases such as reconnection, delayed login, or singleplayer session boot
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike `BotManager`, this class is concerned only with **player-associated** bots
 * - Unlike `BotLifecycleHooks`, this class does not spawn bots directly—it orchestrates lifecycle calls
 * - Unlike `CommandHandler` or `LLMInterface`, this class doesn't interpret chat—it wires the connection
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Each player is assigned a single persistent bot upon joining
 * - Bots are despawned cleanly when players disconnect or switch dimensions
 * - This system must work consistently across multiplayer and singleplayer
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `BotLifecycleHooks`: Used to spawn or despawn bots
 * - `BotManager`: Registers and looks up bot instances
 * - Minecraft's server-side player event system (via Fabric or vanilla hooks)
 * - `VirtualPlayerContext`: Used to track assigned bot instances
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `LLMInterface`, `ChatCommandParser`, or `GoalDispatcher`: May need to route input to a player's bot
 * - `Debug tools`, `EventBus`: May observe player-bot mappings for analysis
 * - `SaveManager`: May use this to identify which bot data to persist or load
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Internally stores a map: `Map<UUID playerId, UUID botId>`
 * - Handles both multiplayer and singleplayer join/leave logic
 * - May emit events such as `PlayerBotLinkedEvent` or `PlayerBotUnlinkedEvent`
 * - Can be extended later to support dynamic reassignment or multi-bot players
 */
package com.orebit.mod.manager;
