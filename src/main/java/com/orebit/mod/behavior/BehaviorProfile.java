/**
 * BehaviorProfile.java
 *
 * Main Component: behavior/
 * Environment: MAIN
 *
 * This class defines the core behavioral traits of a bot, functioning as a
 * high-level personality blueprint. A `BehaviorProfile` dictates how bots
 * perceive, prioritize, and react to stimuli—including how they assign tasks
 * to themselves, respond to player interactions, or navigate social dynamics
 * like trust, loyalty, or rivalry.
 *
 * Profiles are distinct from state or memory—they are stable, reusable objects
 * that guide decision-making across all stages of a bot's lifecycle. They can be
 * assigned to bots dynamically or preloaded into NPC-like villagers at world
 * generation time.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Encapsulates the personality traits and decision-weighting for a bot
 * - Supports defining autonomous preferences (e.g., explorer vs builder)
 * - Influences social and reactive behaviors (e.g., how easily a bot makes friends)
 *
 * ---------------------------
 * How This File Differs from Others:
 * ---------------------------
 * - Unlike `PersonalitySettings`, this is a reusable, shared profile, not an instance-specific copy
 * - Unlike `MemoryEntry`, this is static and non-volatile
 * - Unlike `TaskExecutor`, this defines *what* to do, not *how* to do it
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - A single `BehaviorProfile` may be used by multiple bots concurrently
 * - Bots can override or tweak aspects via `PersonalitySettings` if needed
 * - Profile values are read during decision-making, not every tick
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - None directly, but interacts with AI, task generation, and relationship systems
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `ProfileManager`: Tracks active profiles and matches them to bots
 * - `PersonalitySettings`: May derive from or override profile defaults
 * - `AIStateMachine`, `TaskExecutor`: Use profile weights to influence behavior
 * - `Memory` and `Relationships`: May incorporate profile-driven thresholds
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Can include fields like:
 *     - Exploration vs crafting bias
 *     - Social openness vs isolation
 *     - Aggression vs passivity
 *     - Preferred tasks or goals
 * - Should be immutable and optionally serializable
 * - Profiles may be pre-defined in `ProfileRegistry`
 */
package com.orebit.mod.behavior;
