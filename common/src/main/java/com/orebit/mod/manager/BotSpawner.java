/**
 * BotSpawner.java
 *
 * Main Component: manager/
 * Environment: MAIN
 *
 * This class provides a generic, high-level API for spawning bots into the world.
 * It serves as the entry point for all non-player-lifecycle-based bot creation—
 * including debug commands, scripted events, world generation, and test tools.
 * It simplifies bot creation by wrapping `BotLifecycleHooks` and ensuring that
 * all relevant systems (metadata, ownership, profile assignment, etc.) are initialized
 * consistently, regardless of the caller.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Spawns a new bot into the world, with options for:
 *     - Position and dimension
 *     - Assigned profile
 *     - Ownership status (player-linked or autonomous)
 *     - Custom display name or metadata
 *     - Spawn reason (enum: PLAYER_JOIN, WORLDGEN, SCRIPTED, DEBUG, etc.)
 * - Calls into `BotLifecycleHooks` to initialize core subsystems
 * - Registers the bot with `BotManager` and other relevant systems
 * - Optionally dispatches a `BotSpawnedEvent` for observers
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike `PlayerMonitor`, this class is not tied to player events—it’s generic
 * - Unlike `BotLifecycleHooks`, this class doesn’t implement spawning—it orchestrates it
 * - Unlike `BotManager`, this class doesn’t track bots—it creates them
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - All bots created through this class will be fully initialized and registered
 * - Spawn metadata and ownership may vary based on the source of the request
 * - Spawn calls may come from anywhere: commands, mods, events, etc.
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `BotLifecycleHooks`: Performs low-level bot creation and teardown
 * - `BotMetadata`: Used to attach identity and role info
 * - `BotManager`: Registers the newly spawned bot
 * - `VirtualPlayerContext`: Returned as the bot’s primary handle
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `DebugCommandHandler`: May call this to spawn test bots
 * - `ScriptRunner`, `LLMInterface`, `VillageGenerator`: May use this to create bots in response to goals
 * - `ReplayRecorder`, `EventBus`: May observe or record spawn events
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Exposes overloads like:
 *     - `spawnAutonomousBot(BlockPos pos, String profileId)`
 *     - `spawnPlayerBot(UUID playerId)`
 *     - `spawnWithMetadata(BotMetadata metadata)`
 * - Uses a `SpawnReason` enum to allow consumers to distinguish bots by origin
 * - Supports deferred or queued spawning if needed in the future
 */
package com.orebit.mod.manager;
