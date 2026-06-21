/**
 * CommandHandler.java
 *
 * Main Component: commands/
 * Environment: MAIN
 *
 * This interface defines the contract for all executable bot commands.
 * Each command (e.g., "mine", "follow", "equip", "build") is implemented as a class
 * that fulfills this interface, allowing it to receive a `ParsedCommand` and
 * carry out the corresponding logic for the VirtualPlayer or its systems.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Provides a single `execute(ParsedCommand command)` method (or similar)
 * - Is implemented by all command logic classes
 * - Accepts structured input from the dispatcher and interacts with the bot systems
 * - May return a result, status, or emit events on success/failure
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike `ChatCommandParser`, it does not interpret raw strings
 * - Unlike `CommandDispatcher`, it does not select or manage routing—it *is* the logic
 * - Unlike `CommandRegistry`, it is not involved in registration or metadata—just execution
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - The command has already been validated and parsed before it reaches this class
 * - Argument validation and error handling are still the responsibility of the handler
 * - Handlers are stateless or handle state cleanly across executions
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `ParsedCommand`: Supplies command name, arguments, and source
 * - `VirtualPlayerContext`, `TaskExecutor`, `AIStateMachine`, etc.: May be invoked by handler logic
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `CommandDispatcher`: Invokes these handlers during command routing
 * - Individual command implementations (e.g., `MineCommand`, `FollowCommand`) implement this interface
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - May use a simple `boolean execute(ParsedCommand command)` signature,
 *   or return a richer result type (e.g., `CommandResult`)
 * - Error handling and feedback generation are part of each implementation
 * - Commands may be asynchronous (e.g., start a task) or immediate (e.g., toggle crouch)
 * - Future extensions could include permission checks, cooldowns, or dynamic registration
 */
package com.orebit.mod.commands;
