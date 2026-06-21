/**
 * PendingGoalInteraction.java
 *
 * Main Component: integration/
 * Environment: MAIN
 *
 * This class tracks a goal that could not be immediately dispatched because it
 * was missing required clarification (e.g., “What kind of armor do you want?”).
 * It stores the original `InterpretedIntent`, the bot awaiting instructions,
 * and the prompt issued to the user. Once the response is received, this class
 * is used to finalize the goal and complete the dispatch.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Stores incomplete goal requests for a specific bot
 * - Captures the original LLM intent and the clarification prompt
 * - Waits for a follow-up message from the user
 * - Provides a method to finalize the interaction once clarified
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike `InterpretedIntent`, this is a runtime wrapper—not a result from the LLM
 * - Unlike `GoalDispatcher`, this class doesn’t contain logic—it holds context
 * - Unlike `TaskNode`, this is not executable—it’s part of the interpretation pipeline
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Each interaction is uniquely tied to one bot (by UUID)
 * - The player’s follow-up response is routed back into this object
 * - The interaction is deleted or marked complete after use
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `InterpretedIntent`: The original incomplete goal
 * - `VirtualPlayerContext`: The bot awaiting clarification
 * - `GoalDispatcher`: Uses this class to track and resume goal resolution
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `GoalDispatcher`: Stores active instances and manages lifecycle
 * - `ChatCommandHandler`: May route follow-up responses to the correct interaction
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - May include timestamps or expiration logic to auto-clear stale interactions
 * - Optionally supports multiple missing fields or progressive clarification steps
 * - Could store a callback to be invoked once resolution occurs
 * - May include metadata for display in debug tools (e.g., “Waiting on armor type...”)
 */
package com.orebit.mod.integration;
