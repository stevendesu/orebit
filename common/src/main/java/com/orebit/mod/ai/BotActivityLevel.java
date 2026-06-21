/**
 * BotActivityLevel.java
 *
 * Main Component: ai/
 * Environment: MAIN
 *
 * This file defines an enum (or similar structure) that categorizes bots by their
 * current level of activity or priority for AI updates. It is used by systems like
 * the `AITickScheduler`, `AILoadBalancer`, and `AITickBucket` to filter or prioritize
 * which bots should be updated each tick, and to avoid wasting resources on idle bots.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Declares activity levels such as:
 *     - `IDLE` – Not performing tasks or interacting
 *     - `LOW` – Roaming, observing, waiting for input
 *     - `NORMAL` – Performing routine actions
 *     - `HIGH` – In combat, under threat, or pathing intensely
 * - May include helper methods for determining thresholds or decay rules
 * - Used to throttle update frequency or skip low-priority bots under load
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike `AIState`, this class doesn’t drive behavior—it classifies behavior
 * - Unlike `AITickBucket`, it doesn’t own bots—it describes their current load profile
 * - Unlike `AIPrioritySystem`, this doesn’t score competing goals—it tags general activity
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Each `VirtualPlayerContext` is assigned a current `BotActivityLevel`
 * - This level may be determined heuristically (e.g., current state or task intensity)
 * - Systems consuming this level must recheck it periodically or when state changes
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - None directly, but expected to be used by:
 *     - `VirtualPlayerContext`
 *     - `AITickScheduler`, `AITickBucket`
 *     - `AILoadBalancer`
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `AITickScheduler`: May deprioritize or skip low-activity bots during load
 * - `AILoadBalancer`: May factor activity levels into distribution decisions
 * - `Debug tools`: May use this level to display bot state summaries
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Should be simple and lightweight (enum or sealed class)
 * - May be paired with timestamps to measure how long a bot has remained in each level
 * - Optionally supports decay (e.g., degrade from HIGH to NORMAL to IDLE over time)
 * - May include estimated “cost weight” multipliers for use in scheduling decisions
 */
package com.orebit.mod.ai;
