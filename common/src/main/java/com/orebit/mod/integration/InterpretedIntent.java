/**
 * InterpretedIntent.java
 *
 * Main Component: integration/
 * Environment: MAIN
 *
 * This data structure represents the result of an LLM interpretation of a player's
 * freeform request or chat message. It acts as a bridge between natural language input
 * and symbolic high-level task identifiers understood by the Requirements system.
 * It allows the mod to introspect what the LLM *thinks* the player wants, without
 * immediately committing to executing it.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Stores the symbolic goal ID selected by the LLM (e.g., "acquire_diamond_armor")
 * - Optionally includes:
 *     - The original user message
 *     - A confidence score or quality rating
 *     - A human-readable explanation or rationale
 *     - Alternate goal candidates or ambiguities
 *     - A suggested follow-up message (e.g., “Do you want enchanted or unenchanted gear?”)
 * - May be logged, previewed, or validated before generating a task plan
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike `ParsedCommand`, this is the result of a *semantic interpretation*, not syntactic parsing
 * - Unlike `TaskNode`, this structure is not actionable—it must be passed to the Requirements system
 * - Unlike `LLMInterface`, this is passive—it stores data but doesn't invoke APIs
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - The LLM responds with a primary goal ID, and optionally other metadata
 * - This class is immutable or treated as a stable snapshot
 * - The symbolic goal ID maps cleanly to an entry point in the Requirements system
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `LLMInterface`: Produces this class from raw LLM responses
 * - JSON (for structured parsing from the LLM response format)
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `TaskPlanner`, `GoalDispatcher`, or other systems: Use this to determine what to plan
 * - `Debug tools`, `ReplayRecorder`, `CommandHandler`: May display or log this for review
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Fields may include:
 *     - `String originalMessage`
 *     - `String interpretedGoalId`
 *     - `String rationale`
 *     - `float confidenceScore`
 *     - `List<String> alternativeGoalIds`
 *     - `@Nullable String followUpPrompt`
 * - Designed to support explainability and human oversight in ambiguous cases
 * - May be serializable for inclusion in memory, replays, or save state
 */
package com.orebit.mod.integration;
