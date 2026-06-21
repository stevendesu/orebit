/**
 * BotMetadata.java
 *
 * Main Component: manager/
 * Environment: MAIN
 *
 * This class defines structured metadata associated with a bot’s identity,
 * classification, and long-term characteristics. It is not tied to runtime
 * behavior (like AI or inventory), but instead provides a central record of
 * the bot's role, origin, ownership, and social tags. It acts as a lightweight,
 * persistent information layer for introspection, LLM interaction, or profile-based logic.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Stores persistent identity information about a bot, including:
 *     - Display name or nickname
 *     - Profile name or behavior archetype
 *     - Spawn origin (e.g., system, worldgen, command)
 *     - Owner UUID (if the bot is player-linked)
 *     - Trust state or relationship tags
 *     - Visibility, faction, or script binding info
 * - May be serialized to disk alongside other bot save data
 * - Can be used for UI, debugging, AI context, or LLM prompts
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike `VirtualPlayerContext`, this class stores static metadata—not live subsystems
 * - Unlike `ProfileManager`, this class does not define behavior—it records the current assigned profile
 * - Unlike `SettingsManager`, this class does not govern capabilities—it describes identity and affiliation
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - All bots have a metadata record created during lifecycle initialization
 * - Metadata is accessible at runtime and optionally persisted
 * - This data may be used by multiple systems without ownership conflicts
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - None directly; this is a POJO (Plain Old Java Object)
 * - May be consumed by `BotManager`, `LLMInterface`, `ProfileManager`, `SaveManager`, etc.
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `BotManager`: Stores and queries metadata for lookup or filtering
 * - `LLMInterface`: May inject metadata into prompts
 * - `GoalDispatcher`, `ReplayRecorder`, `Debug tools`: May display or analyze identity information
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Designed to be immutable or stable during a bot’s lifetime
 * - May include:
 *     - `String displayName`
 *     - `String profileId`
 *     - `UUID ownerId`
 *     - `String spawnOrigin`
 *     - `boolean trusted`
 *     - `Set<String> tags`
 * - May support serialization to disk via `SaveManager`
 */
