/**
 * BotOwnership.java
 *
 * Main Component: manager/
 * Environment: MAIN
 *
 * This class represents the ownership relationship between a player and a bot.
 * It stores the player UUID, the bot UUID, and optionally additional metadata
 * such as trust level, ownership status (e.g., temporary or permanent), and
 * whether the bot is currently obeying commands. It provides a lightweight
 * identity link between player and bot, separate from runtime systems like
 * AI or memory.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Models the player ↔ bot ownership relationship
 * - Stores identifiers and metadata about the bond between them
 * - May include:
 *     - Trust level
 *     - Control permissions
 *     - Relationship stage (e.g., “met”, “traded”, “bound”)
 * - May be used for LLM context, permissions, or behavior gating
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike `VirtualPlayerContext`, this is not a bot runtime container—it is a relationship record
 * - Unlike `RelationshipManager`, this class is not a system—it’s a data object
 * - Unlike `BotMetadata`, this is not about the bot’s identity—it’s about a link to a specific player
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Each bot may have one or more owners (if allowed), or zero (if autonomous)
 * - Ownership data is stable and updated over time
 * - May be persisted, synced, or queried during bot interactions
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - None directly; this is a POJO or record
 * - May be used by `PlayerMonitor`, `RelationshipManager`, `BotManager`, or `LLMInterface`
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `PlayerMonitor`: May use this to link players to bots on login
 * - `LLMInterface`: May include this relationship in prompt context
 * - `GoalDispatcher`: May check trust level before accepting intent
 * - `CommandHandler`: May enforce permissions via this structure
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Likely includes fields like:
 *     - `UUID playerId`
 *     - `UUID botId`
 *     - `boolean isTrusted`
 *     - `OwnershipStatus status` (enum: TEMPORARY, BOUND, DISOWNED, etc.)
 *     - `long timestampEstablished`
 * - May be stored in memory, serialized to disk, or regenerated on player join
 */
package com.orebit.mod.manager;
