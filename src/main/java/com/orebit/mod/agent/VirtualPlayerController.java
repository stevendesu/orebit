/**
 * VirtualPlayerController.java
 *
 * Main Component: agent/
 * Environment: MAIN
 *
 * This class acts as the *low-level actuator* for a VirtualPlayer. It issues direct commands
 * to the underlying entity to move, look, interact with the world, use items, attack targets,
 * or break/place blocks. It does not decide *what* the bot should do—only *how* to physically
 * perform a given action.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Controls the VirtualPlayer’s movement (walk, jump, crouch, turn)
 * - Executes interaction commands (right-click, use item, attack)
 * - Handles block placement and destruction mechanics
 * - Acts on behalf of systems like path following, task execution, or AI
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike `AIStateMachine` or `TaskExecutor`, this class does not contain logic, goals, or conditions
 * - Unlike `AnimationHandler`, this class affects *mechanics* (not visuals)
 * - Unlike `VirtualPlayerContext`, this class is active—it changes the world state
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - The VirtualPlayer is a valid, ticking entity within a loaded chunk
 * - The world permits the bot to interact (e.g., proper game mode, permissions, etc.)
 * - Block and entity interactions are expected to match vanilla player behavior
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `VirtualPlayer`: The physical entity this controller manipulates
 * - `World`, `ServerWorld`: Used to resolve actions (e.g., block state, placement, raycast)
 * - `MovementType`, `InventorySimulator`, etc.: Provide movement context and item selection
 * - `SettingsManager`: May gate behavior like whether block breaking is allowed
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `PathFollower`: Issues movement steps
 * - `TaskExecutor`: Issues action sequences (e.g., mine block, place torch)
 * - `AIStateMachine`: May temporarily override or issue commands directly during interrupts
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Methods should be synchronous and side-effectful (e.g., "moveTo(x, y, z)" moves instantly)
 * - May include basic queuing or smoothing to simulate real movement delays
 * - Must interact with Minecraft systems exactly as a player would (e.g., follow tool rules)
 * - Emits animation-related events when appropriate, rather than calling AnimationHandler directly
 */
package com.orebit.mod.agent;
