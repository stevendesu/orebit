/**
 * LLMBackend.java
 *
 * Main Component: integration/
 * Environment: MAIN
 *
 * This interface defines the contract for all LLM backends used by the mod.
 * It allows `LLMInterface` to delegate interpretation requests to any concrete
 * backend—local or cloud—without knowing the specifics of the API, protocol,
 * or format. Implementations must convert player messages into structured
 * responses that can be parsed into an `InterpretedIntent`.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Declares a unified method for interpreting natural language input
 * - Enables support for multiple backend types (OpenAI, local, CLI, etc.)
 * - Allows `LLMInterface` to remain agnostic to backend details
 * - Supports synchronous or asynchronous implementations
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike `LLMInterface`, this is a *pluggable backend contract*, not a router
 * - Unlike `LLMResponseHandler`, this class does not parse output—it fetches it
 * - Unlike backend classes like `OpenAILLMBackend`, this is purely abstract
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Input is a raw message or user instruction in natural language
 * - Output is a raw string or JSON response, passed to the response handler
 * - Errors may be handled internally or thrown for the caller to manage
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `LLMResponseHandler`: May be used by implementers to convert output
 * - `InterpretedIntent` (indirectly): The expected downstream format
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `LLMInterface`: Selects a backend based on `Config` and uses it to interpret intent
 * - Any future adapter or plugin system needing backend-specific control
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - A single method like:
 *     `String queryLLM(String userMessage) throws LLMBackendException;`
 * - May be extended later for async, streaming, or structured output
 * - Could support diagnostic hooks for testing and replay
 */
package com.orebit.mod.integration;
