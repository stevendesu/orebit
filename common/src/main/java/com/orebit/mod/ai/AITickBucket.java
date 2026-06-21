/**
 * AITickBucket.java
 *
 * Main Component: ai/
 * Environment: MAIN
 *
 * This class is part of the AI tick scheduling system. It represents a single "bucket"
 * of bots that are scheduled to receive AI updates on a given tick. Tick buckets help
 * distribute the processing load of AIStateMachine updates across multiple server ticks,
 * preventing lag spikes caused by updating all bots at once.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Holds a list of VirtualPlayers (or contexts) assigned to this bucket
 * - Ticks each assigned bot’s AIStateMachine when the bucket is activated
 * - Can be rotated or distributed dynamically based on server load or total bot count
 * - Acts as a batching mechanism to divide work over time
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike `AITickScheduler`, this is not a global controller—it’s a *container* for one slice of work
 * - Unlike `AIStateMachine`, this class does not hold behavioral logic—it just triggers it
 * - Unlike `AITickProfiler`, this does not measure performance—it simply runs scheduled updates
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Each bot is assigned to exactly one tick bucket
 * - A small, fixed number of buckets (e.g., 5–10) is used to distribute AI evenly
 * - Bots may be rebalanced between buckets dynamically to adjust for load
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `VirtualPlayerContext`: Used to access the AIStateMachine for each bot
 * - `AIStateMachine`: The actual behavior engine invoked by this class
 * - `AITickScheduler`: Coordinates multiple buckets and determines when each should tick
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `AITickScheduler`: Owns and rotates through all buckets
 * - `BotManager`: May assign or reassign bots to buckets during spawn/despawn
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Each bucket may be a simple list or priority queue of VirtualPlayerContext references
 * - Ticking should be deterministic and lightweight—do not perform sorting or scanning
 * - If needed, buckets can be paused or deferred during high-load conditions
 * - Future extensions may allow per-bucket performance profiling or rebalance triggers
 */
package com.orebit.mod.ai;
