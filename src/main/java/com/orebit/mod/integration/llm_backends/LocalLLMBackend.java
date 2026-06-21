/**
 * LocalLLMBackend.java
 *
 * Subcomponent: integration/llm_backends/
 * Environment: MAIN
 *
 * This class implements an LLM backend that connects to a locally hosted model,
 * such as Ollama, LM Studio, or a custom API. It is used by `LLMInterface` when
 * the configuration specifies a local provider. This backend communicates with
 * the local service over HTTP (or optionally CLI or socket) and converts the
 * user’s prompt into a structured `InterpretedIntent`.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Sends HTTP requests to a locally hosted LLM endpoint
 * - Packages the user's raw message into a consistent prompt format
 * - Parses the JSON response into a usable format for `LLMResponseHandler`
 * - Implements fallback, retry, and timeout logic for local instability
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike `OpenAILLMBackend`, this backend assumes local-only communication
 * - Unlike `LLMInterface`, this class is one of many pluggable backend implementations
 * - Unlike `LLMResponseHandler`, this class performs the I/O—not the parsing
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - The local model is hosted at a fixed and reachable endpoint (e.g., http://localhost:11434)
 * - The API accepts prompts and responds with structured JSON (or plaintext convertible to it)
 * - Errors can be recovered through retries or default responses
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `LLMInterface`: Selects and calls this backend based on config
 * - `LLMResponseHandler`: Parses the raw response from this backend
 * - HTTP client or socket API (e.g., Java's built-in or OkHttp)
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `LLMInterface`: Delegates all interaction to this backend if `provider = "local"`
 * - `DebugCommandHandler`: May test local models independently from gameplay
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Uses standard HTTP POST to send prompt and receive structured response
 * - Timeout and retry values are configurable via `Config.LLMConfig`
 * - Logs raw responses for debugging and failover support
 * - Future extension could allow running the model via CLI or embedded runtime
 */
package com.orebit.mod.integration.llm_backends;
