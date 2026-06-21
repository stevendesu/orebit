/**
 * CommandDispatcher.java
 *
 * Main Component: commands/
 * Environment: MAIN
 *
 * This class serves as the central routing layer for all parsed bot commands.
 * Once a chat message is parsed into a structured command, the dispatcher determines
 * which registered handler should be invoked, validates its arguments, and executes the command.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Receives `ParsedCommand` objects from the `ChatCommandParser`
 * - Looks up the correct `CommandHandler` based on command name or alias
 * - Validates command arguments and permissions (e.g., owner-only commands)
 * - Invokes the registered handler to carry out the command’s logic
 * - Emits success or error feedback via chat or events
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike `ChatCommandParser`, this class doesn't analyze raw strings—it operates on structured input
 * - Unlike `CommandRegistry`, this class doesn't store metadata—it consumes it for lookup
 * - Unlike `CommandHandler`, this class doesn't define behavior—it delegates to those that do
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - All available commands are registered and accessible via the `CommandRegistry`
 * - Each command is uniquely identified by name or alias
 * - Handlers are deterministic, side-effect-aware, and safe to call on the server thread
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `ParsedCommand` (data class, possibly internal)
 * - `CommandRegistry`: Provides mapping from command name to handler
 * - `CommandHandler`: Interface implemented by all concrete handlers
 * - `EventBus`: May emit command success/failure or pre/post execution events
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `ChatCommandParser`: Passes parsed commands here for dispatch
 * - `DebugCommandHandler`: May dynamically register debug commands at runtime
 * - Any system that listens for command execution events
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - May include internal error reporting or fallback handling
 * - Should allow for aliasing and partial name matching
 * - Could support permissions, cooldowns, or rate-limiting in the future
 * - May include logging hooks or developer instrumentation
 */
package com.orebit.mod.commands;
