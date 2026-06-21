/**
 * PlayerBotLinker.java
 *
 * Main Component: manager/
 * Environment: MAIN
 *
 * This class is responsible for establishing and maintaining the relationship
 * between a human player and their associated bot. It links a player UUID
 * to a specific bot (typically a `VirtualPlayerContext`), manages updates
 * to that association, and may optionally enforce ownership or trust logic.
 * It is used during login, bot spawning, or gameplay moments when a bot
 * becomes aligned with a player (e.g., through trading or scripted events).
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Creates or updates a link between a player and a bot
 * - May store:
 *     - Player UUID
 *     - Bot UUID
 *     - Ownership flags (e.g., temporary, exclusive)
 *     - Link timestamp or lifecycle state
 * - May validate or enforce trust/permission rules
 * - Can be queried to determine who owns which bot (or vice versa)
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike `PlayerMonitor`, this class does not monitor players—it manages link state
 * - Unlike `BotMetadata`, this class is dynamic and runtime-bound—it can change as bots switch hands
 * - Unlike `RelationshipManager`, this class is focused solely on the player ↔ bot relationship—not bot ↔ bot or abstract social scores
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Each player may have one primary bot, but systems may support more in the future
 * - Ownership links are created when the bot spawns, or dynamically during gameplay
 * - A bot’s trust and commandability may depend on the strength of the link
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `BotManager`: Used to resolve bots and validate identifiers
 * - `BotMetadata` or `BotOwnership`: May store link status or trust flags
 * - `VirtualPlayerContext`: The core entity being linked
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `PlayerMonitor`: Uses this to formally establish ownership when a player joins
 * - `CommandHandler`, `LLMInterface`, `GoalDispatcher`: Use this to route input to the correct bot
 * - `RelationshipManager` (if present): May use this to seed initial trust state
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Likely manages a map: `Map<UUID playerId, UUID botId>`
 * - May include inverse mapping or utility methods like:
 *     - `boolean isLinked(UUID playerId)`
 *     - `UUID getLinkedBot(UUID playerId)`
 *     - `void unlink(UUID playerId)`
 * - May dispatch events such as `PlayerBotLinkedEvent`
 * - Designed to be stable across sessions and recoverable from save state
 */
package com.orebit.mod.manager;
