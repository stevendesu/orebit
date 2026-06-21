/**
 * ReplayViewer.java
 *
 * Main Component: debug/
 * Environment: CLIENT ONLY
 *
 * This class plays back recorded bot behavior from a `ReplayRecorder` session.
 * It allows developers to visualize historical bot movement, decision-making,
 * pathing, and task transitions over time. Replay playback may be shown in the
 * 3D world using ghost entities or as part of a UI-based timeline overlay.
 * This tool is intended for client-side use during debugging or automated testing.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Loads replay logs recorded by `ReplayRecorder`
 * - Reconstructs bot state at each frame and plays it back over time
 * - Supports play, pause, seek, frame-step, speed control, and scrub bar navigation
 * - May render:
 *     - Ghost versions of bots moving along recorded paths
 *     - State transitions, task changes, or memory events
 *     - Visual overlays of decision flow or pathfinding attempts
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike `ReplayRecorder`, this is read-only and client-side
 * - Unlike `PathTraceRenderer`, this shows *historical* pathing, not live paths
 * - Unlike `BotDebuggerOverlay`, this may simulate multiple timepoints simultaneously
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Replay logs are loaded from disk or received via sync
 * - The replay format includes tick-aligned snapshots or events
 * - This class runs only in client-side debug or testing environments
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `ReplayRecorder`: Produces the data this class replays
 * - `VirtualPlayerContext`, `TaskSnapshot`, or similar structures: Used to represent bot state
 * - Minecraft’s render and input systems (camera, mouse, overlay HUD)
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `ClientInit`: May register this in singleplayer or dev mode
 * - `DebugCommandHandler`: May start, pause, or scrub the replay
 * - `BotDebuggerOverlay`: May sync with replay viewer for visual enhancements
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Playback should be deterministic and match recorded timing via `SimulationClock`
 * - Ghost bots should not interact with the world (no collisions or AI)
 * - May support multi-bot viewing, sync/async modes, or jump-to-task navigation
 * - Designed for use in postmortems, test automation, or exploratory debugging
 */
package com.orebit.mod.debug;
