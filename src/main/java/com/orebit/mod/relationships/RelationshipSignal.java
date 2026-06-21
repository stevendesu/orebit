/**
 * RelationshipSignal.java
 *
 * Main Component: relationships/
 * Environment: MAIN
 *
 * This abstract class represents a socially meaningful signal—an action,
 * behavior, or interaction that can influence the relationship between two
 * entities. A `RelationshipSignal` models how bots interpret cues from others,
 * such as helpful gestures, aggressive behavior, neglect, or cooperation.
 *
 * Signals serve as the behavioral input to the relationship system. When a
 * signal is observed or triggered (e.g., a bot is attacked, gifted, ignored),
 * it is passed to the `RelationshipManager`, which interprets the signal and
 * adjusts the underlying `RelationshipScore` accordingly.
 *
 * Signals allow for a diverse and extensible vocabulary of social interactions,
 * enabling emergent behavior, emotional modeling, and social alignment tracking.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Defines the interface or base class for all social cues affecting relationships
 * - Enables the relationship system to respond to dynamic, context-aware inputs
 * - Allows modular definition of interaction types (e.g., help, hurt, heal, follow)
 *
 * ---------------------------
 * How This File Differs from Others:
 * ---------------------------
 * - Unlike `Relationship`, this is a trigger—not a state container
 * - Unlike `Task`, this is not goal-directed behavior—it's a perceived social cue
 * - Unlike `Event`, this is interpreted symbolically rather than mechanically
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - A signal is always directional (source → target)
 * - Signals are ephemeral and interpreted at the time of dispatch
 * - Each signal defines its own rules for score modification or interpretation
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `RelationshipScore` or `RelationshipManager`: Applies scoring deltas
 * - Entity references or `BotId`s to identify source and target
 * - (Optional) World or context state to determine signal severity
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `RelationshipManager`: Receives signals and applies them to the graph
 * - `TaskExecutor`, `AIStateMachine`: May generate signals as side effects
 * - `Debug Tools`: May visualize signal flow or display recent events
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Subclasses may define:
 *     - `float getTrustDelta()`
 *     - `float getAffinityDelta()`
 *     - `boolean shouldDecayRelationship()`
 *     - `RelationshipTag getSuggestedTag()`
 * - Examples include:
 *     - `AttackSignal`
 *     - `HelpSignal`
 *     - `IgnoreSignal`
 *     - `FollowSignal`
 *     - `TradeSignal`
 */
package com.orebit.mod.relationships;
