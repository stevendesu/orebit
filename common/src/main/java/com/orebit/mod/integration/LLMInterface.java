/**
 * LLMInterface.java
 *
 * Main Component: integration/
 * Environment: MAIN
 *
 * This class serves as the interface between the Minecraft mod and an external
 * large language model (LLM). Its sole responsibility is to interpret natural language
 * input from the player (or admin) and return a structured high-level goal. It does
 * not generate or manage task plans—that is the job of the Requirements system.
 * The LLM simply maps freeform chat like “I need to gear up for the Nether” into
 * something like `acquire_full_enchanted_diamond_armor`.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Sends player messages or questions to an LLM backend for interpretation
 * - Receives a structured goal, such as a symbolic task identifier
 * - Optionally returns short summaries or follow-up clarification messages
 * - Provides an abstraction over different backend types (OpenAI, local, etc.)
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike `TaskPlanner` or the Requirements system, this class performs *no* planning
 * - Unlike `ChatCommandParser`, it handles freeform or ambiguous input—not rigid syntax
 * - Unlike `CommandDispatcher`, this class is not authoritative—it merely suggests goals
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - The LLM backend returns a structured response (e.g., a string ID or JSON object) representing a goal
 * - This result is passed into the Requirements system to generate a task tree
 * - Errors or ambiguities are resolved through retry, fallback, or clarification messages
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `Config`: Specifies the LLM provider, API endpoint, model name, and other settings
 * - JSON or structured response handler
 * - Optional: logging, rate limiting, or error recovery utilities
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `ChatCommandHandler` or `ParsedCommand`: Routes ambiguous or conversational input here
 * - `TaskPlanner`: Consumes the interpreted goal as a root node
 * - `Debug tools`: May inspect request/response for review or feedback
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Prompts are templated for consistent responses (e.g., “Return a goal ID based on the following request…”)
 * - Supports multiple backend types, including local and hosted models
 * - Should log both raw input and interpreted result for transparency
 * - May include timeout handling, fallback suggestions, or streaming support in the future
 */
package com.orebit.mod.integration;
