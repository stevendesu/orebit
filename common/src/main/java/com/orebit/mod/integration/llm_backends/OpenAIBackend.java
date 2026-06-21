/**
 * OpenAILLMBackend.java
 *
 * Subcomponent: integration/llm_backends/
 * Environment: MAIN
 *
 * This class implements an LLM backend that connects to OpenAI’s API
 * (or any provider compatible with the OpenAI API schema). It is used by
 * `LLMInterface` when the configuration specifies OpenAI as the provider.
 * It sends messages using the chat completion API and parses structured goals
 * from the result using `LLMResponseHandler`.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Sends HTTP requests to the OpenAI API using API keys and model selection
 * - Includes system prompts and user messages formatted for the chat API
 * - Receives streamed or full responses, depending on config
 * - Forwards the content to `LLMResponseHandler` to generate an `InterpretedIntent`
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike `LocalLLMBackend`, this backend authenticates and communicates with a remote API
 * - Unlike `LLMInterface`, this is a backend implementation—not an interface
 * - Unlike `LLMResponseHandler`, this does not parse content—it only transports it
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - API keys and model names are set in `Config.LLMConfig`
 * - Rate-limits and quota errors are recoverable or retryable
 * - Output will be in JSON or predictable text format for further parsing
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `LLMInterface`: Calls this backend if the selected provider is "openai"
 * - `LLMResponseHandler`: Parses the result of each API call
 * - Secure HTTP client with header support (e.g., OkHttp, Apache HTTP)
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `LLMInterface`: Uses this class for cloud-based model interpretation
 * - `Debug tools`: May surface API latency, response rate, or key status
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Sends `application/json` POST requests with structured message arrays
 * - May use streaming for large responses or typing effects
 * - Should handle status codes 429, 401, and 500 with retry/backoff logic
 * - API key should be redacted in logs unless explicitly enabled
 */
package com.orebit.mod.integration.llm_backends;
