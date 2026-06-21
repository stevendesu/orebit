/**
 * PromptBuilder.java
 *
 * Main Component: integration/
 * Environment: MAIN
 *
 * This utility class is responsible for generating consistent, structured prompts
 * to be sent to the LLM backend. It transforms a player's raw message (e.g., "can you help me gear up?")
 * into a well-formed prompt that clearly instructs the LLM to produce a machine-readable
 * `InterpretedIntent`. It ensures that all outgoing LLM queries follow a shared format
 * for reliability, testability, and parsing robustness.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Takes the player’s original input and wraps it with system-level instructions
 * - May include examples, goals schema, or formatting constraints (e.g., “return JSON only”)
 * - Produces a `String` that is passed to the selected `LLMBackend`
 * - Enforces prompt templates for consistent and predictable interpretation
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike `LLMInterface`, this class doesn’t send prompts—it only builds them
 * - Unlike `LLMResponseHandler`, this class generates the *input* to the LLM, not parses output
 * - Unlike `InterpretedIntent`, this class doesn't represent meaning—it crafts the question
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - LLMs are more reliable when given clear, templated prompts
 * - Prompt templates may evolve over time as model behavior changes
 * - The player message is clean and safe to include verbatim, or properly escaped
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `Config.LLMConfig`: May include global prompt template strings or tuning parameters
 * - Optional: prompt caching, schema registry, or debugging flags
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `LLMInterface`: Uses this to format the outgoing prompt
 * - `Debug tools`: May preview the prompt before it’s sent to the backend
 * - `ReplayRecorder`: May log the exact prompt for future audit or replays
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Prompts may be assembled from:
 *     - System instructions (e.g., “You are a goal resolver...”)
 *     - Schema examples (e.g., “Respond in the form: { goal: ..., rationale: ... }”)
 *     - The player’s raw message (included as `input`)
 * - Should support different formatting styles per backend if needed
 * - Should sanitize or escape input where necessary
 * - May use templates, builders, or string interpolation libraries
 */
package com.orebit.mod.integration;
