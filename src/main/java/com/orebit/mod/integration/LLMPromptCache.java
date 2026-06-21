/**
 * LLMPromptCache.java
 *
 * Main Component: integration/
 * Environment: MAIN
 *
 * This component acts as a shared cache for prompts sent to the LLM backend,
 * enabling reuse of recent or identical interpretations. Its goal is to avoid
 * redundant calls to the LLM for queries that have already been resolved in
 * the same or similar context. While the cache may be relatively naive at first
 * (e.g., input-only hashing), it is designed to evolve into a context-aware
 * semantic memory that supports partial matches and time-decay.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Stores recent prompts and their corresponding `InterpretedIntent` results
 * - Provides fast lookups based on exact or fuzzy prompt matches
 * - May incorporate contextual hashes to distinguish meaning-sensitive queries
 * - Supports time-to-live, eviction, or frequency-based expiration
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike `PromptBuilder`, this does not build prompts—it stores and retrieves them
 * - Unlike `LLMInterface`, this does not interact with the backend—it short-circuits it
 * - Unlike `ReplayRecorder`, this is ephemeral—not designed for archival
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Prompt caching is most valuable for repeated requests in similar contexts
 * - Not all user input is cacheable (“continue”, “help”, etc.)
 * - Cache keys may be exact strings, context-augmented hashes, or semantic digests
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `PromptBuilder`: May generate canonical keys or normalized inputs
 * - `InterpretedIntent`: The cached result
 * - Optional: player UUID, world state digest, recent memory fingerprints
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `LLMInterface`: Checks this cache before forwarding to a backend
 * - `Debug tools`: May inspect, clear, or simulate prompt reuse
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - May use a simple `Map<String, InterpretedIntent>` at first
 * - Future implementations might include:
 *     - Hash-based prompt similarity
 *     - Memory-aware context windows
 *     - Expiration by age or LRU policy
 *     - Confidence threshold-based reuse
 * - Should allow enabling/disabling or scoping via `Config.LLMConfig`
 */
package com.orebit.mod.integration;
