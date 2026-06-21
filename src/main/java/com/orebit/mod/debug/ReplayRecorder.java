/**
 * ReplayRecorder.java
 *
 * Main Component: debug/
 * Environment: MAIN
 *
 * This class records time-ordered snapshots of bot behavior, world interactions,
 * and AI state for later playback or analysis. It is used primarily for debugging,
 * testing, or reproducing bugs. Recordings may include position, state transitions,
 * task execution events, and memory changes. It is designed to run server-side
 * and operate independently of visual renderers.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Captures periodic snapshots of bot activity and environment state
 * - Stores serialized logs of movement, task changes, path transitions, and events
 * - Supports replay, scrubbing, or export via external tools or in-game commands
 * - Can be used to compare planned vs. actual behavior during test sessions
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike `PathTraceRenderer` or `BotDebuggerOverlay`, this class produces *data*, not visuals
 * - Unlike `BotSaveData`, this class stores short-term, time-ordered logs, not persistent bot state
 * - Unlike `PerformanceMonitor`, it logs behavior, not performance metrics
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Replay data is bounded in size and duration to avoid bloating memory or disk
 * - Logged data can be serialized efficiently (e.g., compressed binary or JSONL)
 * - Replay format is versioned or modular for future extensions
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `VirtualPlayerContext`, `AIStateMachine`, `TaskExecutor`: Source of behavior data
 * - `SimulationClock`: Used to timestamp entries and replay them consistently
 * - `SaveManager` or dedicated log file system: For long-term storage (optional)
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `ReplayPlayer` (if implemented): A viewer or ghost entity that plays back logs
 * - `DebugCommandHandler`: May start/stop recordings or extract replay segments
 * - `BotDebuggerOverlay`: May render in sync with replay if visual playback is supported
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Data may be recorded as structured events (e.g., JSON), binary snapshots, or command diffs
 * - Supports filtering by bot, event type, or system
 * - Can be extended with per-bot replay buffers or global world event logs
 * - Replay format should support frame seeking, timestamps, and minimal replay drift
 */
package com.orebit.mod.debug;
