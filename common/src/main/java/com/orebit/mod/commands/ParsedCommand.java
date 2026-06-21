/**
 * ParsedCommand.java
 *
 * Main Component: commands/
 * Environment: MAIN
 *
 * This data class represents a structured command parsed from a raw chat message.
 * It is the output of `ChatCommandParser` and the input to `CommandDispatcher`.
 * A ParsedCommand encapsulates everything needed to route and execute a command
 * issued by a player or system, including the command name, arguments, and source.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Stores the normalized command name (e.g., "mine", "follow", "equip")
 * - Stores arguments as a raw string list, token list, or key-value map
 * - Tracks the origin of the command (e.g., player UUID, entity, system)
 * - Optionally includes a timestamp or context metadata
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike `ChatCommandParser`, this is a *data object*, not a parser
 * - Unlike `CommandDispatcher`, this doesn’t route commands—it carries parsed data
 * - Unlike `CommandHandler`, this class doesn’t contain execution logic—it’s passive
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - One ParsedCommand is created per successful chat parsing
 * - Arguments are stored in a way that handlers can validate and interpret safely
 * - May optionally include a confidence score or origin context if integrated with LLM parsing
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - Likely used with `CommandDispatcher`, `CommandHandler`, and `CommandRegistry`
 * - `UUID`, `ServerPlayerEntity`, or similar types for command source tracking
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `CommandDispatcher`: Uses this class to determine what to run and how
 * - `CommandHandler`: May introspect this class to extract arguments
 * - `Debug tools`: May log or inspect parsed commands
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Fields may include:
 *     - `String commandName`
 *     - `List<String> arguments` or `Map<String, String> namedArguments`
 *     - `CommandSource source` (player, system, etc.)
 *     - `long timestamp`
 * - Should support immutability or defensively copied arguments
 * - Could support serialization for deferred execution or replay
 */
package com.orebit.mod.commands;
