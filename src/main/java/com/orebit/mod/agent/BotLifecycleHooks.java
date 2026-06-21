/**
 * BotLifecycleHooks.java
 *
 * Main Component: agent/
 * Environment: MAIN
 *
 * This class manages the internal lifecycle of a single virtual bot agent.
 * It defines the setup and teardown processes required to prepare an agent
 * for operation within the world, including the instantiation and coordination
 * of all its subsystems: AI, animation, memory, inventory, and context.
 * It also handles cleanup and deregistration when the bot is despawned.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Spawns and initializes all components that define a functioning bot
 * - Creates and links:
 *     - `VirtualPlayer`
 *     - `VirtualPlayerContext`
 *     - `AIStateMachine`
 *     - `SettingsManager`, `BotMemory`, `InventoryAdapter`, etc.
 * - Tears down and detaches bot systems when removed from the world
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike `BotManager`, this class does not track multiple bots—it manages *one*
 * - Unlike `VirtualPlayerController`, this class does not control AI—it assembles it
 * - Unlike `SaveManager`, this class does not persist state—it activates it
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Each bot has a full set of modular systems created through this entrypoint
 * - Lifecycle hooks may be triggered by player joins, village spawns, or commands
 * - Despawning includes cleanup of all memory, tick state, and subscriptions
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `VirtualPlayerContext`: Core container for all bot-related systems
 * - `AIStateMachine`, `TaskExecutor`, `BotMemory`, `SettingsManager`: Instantiated or restored
 * - `EventBus`: Used to dispatch spawn/despawn events
 * - `SaveManager`: Loads or stores bot save data during lifecycle transitions
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `BotManager`, `PlayerMonitor`, or world event listeners: Call into this to create/remove bots
 * - `ReplayRecorder`, `EventBus`, `Debug tools`: May subscribe to lifecycle events
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Lifecycle methods include:
 *     - `VirtualPlayerContext spawnBot(UUID botId, ...)`
 *     - `void despawnBot(VirtualPlayerContext context, DespawnReason reason)`
 * - Future versions may support partial lifecycles (e.g., pause, hibernate, detach)
 * - Bot identity, relationships, and save state should be established *before* tick logic begins
 */
package com.orebit.mod.agent;
