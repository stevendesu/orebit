/**
 * BehaviorSettings.java
 *
 * Main Component: behavior/
 * Environment: MAIN
 *
 * This class defines a per-bot behavioral modifier that complements a shared
 * `BehaviorProfile`. While a `BehaviorProfile` defines a fixed personality
 * archetype (e.g., explorer, builder, merchant), the `BehaviorSettings`
 * instance allows fine-tuned control over how that personality is expressed by
 * an individual bot.
 *
 * Settings may evolve over time due to environmental influences, relationship
 * history, memory, or player interaction—allowing bots to feel more unique and
 * responsive without needing a separate profile class for every nuance.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Stores bot-specific preferences, weights, and tendencies
 * - Allows overrides of traits from the shared `BehaviorProfile`
 * - Enables adaptive, personalized behavior per bot instance
 *
 * ---------------------------
 * How This File Differs from Others:
 * ---------------------------
 * - Unlike `BehaviorProfile`, this is mutable and unique per bot
 * - Unlike `BehaviorManager`, this stores state—not control logic
 * - Unlike `Memory`, this does not record events—just behavioral traits
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - All bots share a common profile class, but each has unique settings
 * - Settings may adapt over time via decay, reinforcement, or influence
 * - Settings can be serialized and restored on server restart
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `BehaviorProfile`: May use it as a fallback/default when traits are unset
 * - (Optional) `Memory`, `Relationships`: Could feed into adaptive changes
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `BehaviorManager`: Owns the settings map and provides access to them
 * - `AIStateMachine`: Uses settings to weight decisions
 * - `TaskExecutor`: Uses settings to prefer certain types of tasks
 * - `Debug UI` or `config` tools: May expose settings for editing or inspection
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - May include values such as:
 *     - Preferred task weights (e.g., mining vs farming)
 *     - Social tolerance or aggression level
 *     - Reactive tendencies (e.g., flee vs fight)
 *     - Cooldowns or timers for behavior switches
 * - Should support JSON or NBT serialization
 */
package com.orebit.mod.behavior;
