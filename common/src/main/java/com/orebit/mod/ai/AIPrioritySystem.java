/**
 * AIPrioritySystem.java
 *
 * Main Component: ai/
 * Environment: MAIN
 *
 * This class manages the arbitration of *competing AI goals* by assigning priority values
 * to various instincts, interrupts, and task desires. It helps the AIStateMachine decide
 * which behavior should be active at any given moment by weighing hunger, danger, curiosity,
 * task goals, social motives, and other internal or environmental pressures.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Evaluates and scores multiple candidate behaviors per tick
 * - Returns the highest-priority goal to the AI state machine for activation
 * - May take into account current context, world conditions, memory, relationships, and more
 * - Allows layering of instinctual behaviors (e.g., flee when health is low) over long-term goals
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike `AIStateMachine`, this class does *not* manage state transitions directly
 * - Unlike `TaskExecutor`, it does not consider task structure—it focuses only on motivation
 * - Unlike `AITickScheduler`, it operates per-bot rather than across all bots
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Each VirtualPlayerContext provides enough information (via sensors or simulators)
 *   to score each priority function
 * - Instincts may be static (e.g., “avoid creepers”) or dynamic (e.g., “eat if hungry”)
 * - External task queues or interrupts may inject temporary high-priority desires
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `VirtualPlayerContext`: Provides hunger, health, location, memory, relationships, etc.
 * - `TaskExecutor`: May be queried to understand pending tasks or waiting actions
 * - `RelationshipManager`, `BotMemory`, `InventorySimulator`, etc. as needed for scoring
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `AIStateMachine`: Uses this system to decide whether to switch behaviors
 * - `InterruptibleState`: May call into this system to determine whether it should yield control
 * - `Debug tools`: May visualize or log priority scores for introspection
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - May use a weighted priority system, behavior tree scoring, or simple rule-based logic
 * - Should return a prioritized list of candidate states or intents
 * - May support confidence thresholds or hysteresis to reduce flip-flopping
 * - Can be extended via behavior profiles (e.g., cautious vs. aggressive bots)
 */
package com.orebit.mod.ai;
