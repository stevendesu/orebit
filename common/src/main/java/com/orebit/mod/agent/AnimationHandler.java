/**
 * AnimationHandler.java
 *
 * Main Component: agent/
 * Environment: MAIN
 *
 * This file is responsible for controlling the *visual feedback* of a VirtualPlayer,
 * such as swing animations, sneaking, jumping posture, arm poses, and head movement.
 * These animations are purely cosmetic and do not affect the mechanical state or behavior
 * of the bot—they are rendered to enhance realism and immersion.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Listens for animation-related events via the global EventBus
 * - Triggers the appropriate visual animation on the VirtualPlayer entity
 * - Interfaces with the mock network layer to send animation packets to clients
 * - Manages animation timers and resets to ensure smooth visual playback
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Does not initiate behavior or perform logic checks—only reacts to published events
 * - Unlike VirtualPlayerController (which issues mechanical commands), this class purely animates
 * - Unlike AI or Task systems, this class has no awareness of behavior goals—only their visual symptoms
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Animation triggers will be published as events on the EventBus
 * - The VirtualPlayer is a network-visible entity that can be animated through packet injection
 * - Each animation type has a known mapping to Minecraft packet or metadata-based animation
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `EventBus` (events/): Subscribes to animation events
 * - `VirtualPlayer` (agent/): The bot entity this handler modifies
 * - `MockNetworkClient` (mocks/): Used to simulate animation packets
 * - Animation event types such as `SwingArmEvent`, `CrouchToggleEvent`, `HeadLookEvent`
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - Indirectly triggered by:
 *   - `TaskExecutor` (tasks/), `AIStateMachine` (ai/), and `VirtualPlayerController` (agent/)
 *     via event dispatch
 * - `DebugCommandHandler` (debug/) may dispatch events to test specific animations
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Animations are registered to specific event types and triggered via subscriptions
 * - Cooldowns and state resets are handled via tick counters or system time comparisons
 * - Future extension may include contextual animations (e.g., idle look-around, gestures)
 */
package com.orebit.mod.agent;
