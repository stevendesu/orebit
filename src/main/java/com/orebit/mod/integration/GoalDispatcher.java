/**
 * GoalDispatcher.java
 *
 * Main Component: integration/
 * Environment: MAIN
 *
 * This class serves as the bridge between interpreted user intent (via the LLM)
 * and the Requirements system that generates executable task trees.
 * It evaluates the incoming `InterpretedIntent`, determines whether the goal is
 * complete or requires clarification, and either dispatches it immediately
 * or pauses the bot and requests more information via chat.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Accepts `InterpretedIntent` instances from the `LLMInterface`
 * - Determines if the intent contains enough information to proceed
 * - If so: calls into the Requirements system to generate a task tree and queue it
 * - If not: generates a follow-up question and waits for a player response
 * - Tracks bots that are awaiting clarification using `PendingGoalInteraction`
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike `LLMInterface`, this class doesn't interpret messages—it acts on them
 * - Unlike `TaskPlanner`, this class doesn’t build the task tree—it ensures the right task is requested
 * - Unlike `CommandDispatcher`, this class does not route structured commands—it routes high-level intent
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Intents may be incomplete or ambiguous (e.g., missing item type, quantity, or context)
 * - The bot must pause or stall its current task while clarification is pending
 * - Responses to follow-up prompts are captured via the chat or command system
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `InterpretedIntent`: The structured result from the LLM
 * - `PendingGoalInteraction`: Tracks bots waiting for clarification
 * - `TaskPlanner`, `RequirementsSystem`: Generates the final `TaskNode` tree
 * - `VirtualPlayerContext`, `AIStateMachine`: Used to queue or pause bot activity
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `LLMInterface`: Routes intent results here
 * - `ChatCommandHandler`: May deliver follow-up responses to complete interactions
 * - `Debug tools`: May log pending or dispatched goals for analysis
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - May generate follow-up prompts from the `InterpretedIntent` metadata
 * - Stores `PendingGoalInteraction` objects in a temporary cache
 * - Validates follow-up input before proceeding
 * - Removes the interaction once goal resolution is complete
 */
package com.orebit.mod.integration;
