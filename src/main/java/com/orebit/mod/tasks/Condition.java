/**
 * Condition.java
 *
 * Main Component: tasks/
 * Environment: MAIN
 *
 * This interface represents a logical check used to control task flow. A `Condition`
 * evaluates whether some requirement or world state is satisfied, such as whether
 * the bot is hungry, near its goal, under attack, or missing an item.
 *
 * Conditions are used in branching task trees, conditional execution, fallbacks,
 * and behavior gating. They are always passive—conditions never perform actions,
 * only evaluate context.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Provides a boolean logic check during task planning or evaluation
 * - Enables branching or short-circuiting of tasks based on bot/world state
 * - Allows reusable predicates across behavior trees
 *
 * ---------------------------
 * How This File Differs from Others:
 * ---------------------------
 * - Unlike `Action`, this does not execute behavior—it only evaluates
 * - Unlike `TaskNode`, this is atomic—it doesn’t have children
 * - Unlike `Requirement`, this does not imply planning or cost—it’s logic only
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Conditions do not mutate the world or bot state
 * - May be evaluated many times per tick or task pass
 * - Should be lightweight and side-effect-free
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `BotContext`, `WorldModel`, `InventorySimulator`, `Memory`, etc.
 * - May read settings, profiles, tasks, or relationships
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `TaskNode`: Often uses conditions to gate behavior
 * - `TaskExecutor`: May use conditions for pre-checks or interrupts
 * - `BehaviorProfile`: May build reusable condition libraries
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Should expose:
 *     - `boolean evaluate(BotContext context)`
 * - May be stateless or dynamic
 * - May include built-in combinators like AND, OR, NOT
 */
package com.orebit.mod.tasks;
