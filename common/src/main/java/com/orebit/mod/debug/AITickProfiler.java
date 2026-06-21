/**
 * AITickProfiler.java
 *
 * Main Component: debug/
 * Environment: MAIN
 *
 * This class provides developer-facing diagnostics and performance metrics for AI ticking.
 * It measures the duration of AI updates for individual bots or entire tick buckets,
 * helping mod developers and server admins identify which bots or behaviors
 * are contributing most to tick load. It is intended for debug and profiling use only,
 * and should be disabled in production environments unless explicitly needed.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Tracks AI tick duration at per-bot or per-bucket granularity
 * - Logs timing data and exposes summaries via debug commands or overlays
 * - Assists in identifying slow states, overloaded buckets, or lag spikes
 * - Optionally integrates with AILoadBalancer for measurement but not decision-making
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike `AILoadBalancer` (ai/), this class does not rebalance or alter behavior
 * - Unlike `AITickScheduler`, it does not execute or control AI ticks—it just observes them
 * - Unlike `AITickBucket`, it is not involved in scheduling—it is purely diagnostic
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Timing instrumentation is enabled via a config setting or command
 * - Results may be routed to log files, overlays, or commands for inspection
 * - This class should have negligible impact when disabled
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `AITickScheduler` and `AITickBucket`: Entry points for timing scopes
 * - `VirtualPlayerContext`: Used to label and report per-bot metrics
 * - `DebugCommandHandler`: Provides runtime access to profiler reports
 * - `SettingsManager`: Toggles profiling features on/off
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `Debug tools`: Live analysis, visualization, or tracing overlays
 * - `Development builds`: For profiling pathfinding, state switching, etc.
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Uses high-resolution timers (e.g., System.nanoTime)
 * - Supports sampling, rolling averages, or burst detection
 * - Timing data is cached per tick and reset regularly
 * - Should include internal toggle checks to reduce runtime cost when inactive
 */
package com.orebit.mod.debug;
