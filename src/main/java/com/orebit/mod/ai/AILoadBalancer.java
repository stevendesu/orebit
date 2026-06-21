/**
 * AILoadBalancer.java
 *
 * Main Component: ai/
 * Environment: MAIN
 *
 * This component is responsible for optimizing the distribution of bots across
 * AI tick buckets based on performance measurements. It aims to ensure that no
 * single bucket is overloaded and that AI computation is spread evenly over time.
 * It works in conjunction with the AITickScheduler to improve runtime efficiency
 * and responsiveness, especially under high bot counts.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Monitors AI tick cost per bot and/or per bucket
 * - Evaluates the load across all AITickBuckets at regular intervals
 * - Suggests or performs redistribution of bots to balance tick time
 * - Optionally smooths or dampens sudden bucket changes
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike `AITickProfiler`, this class is not debug-only—it participates in core runtime logic
 * - Unlike `AITickScheduler`, it does not control tick timing—it only informs the scheduler of better layouts
 * - Unlike `AITickBucket`, it operates across all buckets, not within one
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - The system has multiple tick buckets, and bots can be reassigned between them
 * - AITickScheduler calls into this component at regular intervals (e.g., every 100 ticks)
 * - Each bot or bucket can report approximate tick duration (possibly via profiler hook or heuristic)
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `AITickBucket`: Source of per-bucket timing and bot lists
 * - `VirtualPlayerContext`: May be tagged with recent tick cost or activity score
 * - `AITickScheduler`: Invokes this class to rebalance when needed
 * - `SettingsManager`: May provide thresholds, dampening, or balancing policies
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `AITickScheduler`: Applies rebalancing suggestions produced here
 * - `Debug tools`: May query this component for analysis or metrics
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - May use simple averages, moving windows, or exponential smoothing to track tick costs
 * - Could implement greedy, round-robin, or least-cost bin-packing algorithms
 * - Should avoid frequent rebalance to prevent bot thrashing across buckets
 * - Designed to be extensible with future rules (e.g., behavior class weighting, profile-based clustering)
 */
package com.orebit.mod.ai;
