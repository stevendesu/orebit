/**
 * BehaviorManager.java
 *
 * Main Component: behavior/
 * Environment: MAIN
 *
 * This class is responsible for managing the behavior-related state of all bots
 * in the world. It maintains the active `BehaviorProfile` assigned to each bot,
 * facilitates profile swapping or overrides, and coordinates access to per-bot
 * `BehaviorSettings` which may customize or extend the core profile traits.
 *
 * The `BehaviorManager` also serves as the integration point between high-level
 * bot personalities and the runtime systems that act on them, including AI, task
 * assignment, relationship evaluation, and autonomy. It acts as a query layer
 * between other systems and the bot's identity, preferences, and decision-making
 * tendencies.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Tracks the current `BehaviorProfile` and `BehaviorSettings` for each bot
 * - Assigns default profiles to bots during creation or loading
 * - Allows dynamic updates or overrides to bot behavior
 * - Exposes access to relevant behavioral traits at runtime
 *
 * ---------------------------
 * How This File Differs from Others:
 * ---------------------------
 * - Unlike `BehaviorProfile`, this is dynamic and bot-specific, not a reusable definition
 * - Unlike `BehaviorRegistry`, this tracks current assignments, not predefined options
 * - Unlike `AIStateMachine`, this controls preferences and tendencies, not actions
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Each bot has one active profile and one corresponding `BehaviorSettings` instance
 * - Bots may share profiles, but `BehaviorSettings` are always bot-local
 * - Profiles are assumed to be immutable and thread-safe
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `BehaviorProfile`: The core personality model shared across bots
 * - `BehaviorSettings`: Bot-specific tunable traits
 * - `BehaviorRegistry`: May be used to look up available profiles
 * - `BotId`, `VirtualPlayer`: Used to associate bots with their behaviors
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `AIStateMachine`: Uses behavior info to weight decisions
 * - `TaskExecutor`: Consults personality when selecting task candidates
 * - `RelationshipManager`: May use behavior traits to evaluate compatibility
 * - `Debug tools`: May expose behavior state for inspection or editing
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Internally maps bots to:
 *     - `BehaviorProfile` (shared definition)
 *     - `BehaviorSettings` (per-bot copy)
 * - May support:
 *     - `assignProfile(botId, profile)`
 *     - `getSettings(botId)`
 *     - `tick()` for dynamic behavioral updates (e.g. adaptive personalities)
 */
package com.orebit.mod.behavior;
