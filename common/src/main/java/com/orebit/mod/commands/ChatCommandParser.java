/**
 * ChatCommandParser.java
 *
 * Main Component: commands/
 * Environment: MAIN
 *
 * This class is responsible for parsing raw chat messages into structured command objects
 * that can be understood and executed by the bot command system. It converts human-readable
 * strings like “mine diamonds” or “follow me” into command identifiers and argument sets,
 * serving as the first stage of user input processing.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Listens to incoming chat messages (player messages or server-issued)
 * - Matches messages against a set of supported command patterns
 * - Extracts command names and arguments into a normalized format
 * - Passes parsed results to the `CommandDispatcher` for routing
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike `CommandDispatcher`, this class does not execute or route commands—it only parses them
 * - Unlike `CommandRegistry`, this class does not store command metadata—it consumes it
 * - Unlike Minecraft's built-in command system, this works via chat rather than slash commands
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - Messages are prefixed or structured in a way that distinguishes them from normal chat
 * - Command formats may be pattern-based (regex) or keyword-driven
 * - Each message results in either a valid `ParsedCommand` or a rejection/error event
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `CommandRegistry`: Provides known commands and expected argument formats
 * - `EventBus`: May be used to listen for or dispatch chat events
 * - `CommandDispatcher`: Receives parsed commands for execution
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `CommandDispatcher`: Relies on this to produce valid command objects
 * - `DebugCommandHandler`: May hook into parsing or provide feedback on errors
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Parsing may use regex, tokenization, or keyword extraction
 * - Output is likely a `ParsedCommand` object with command name, arguments, and source
 * - Should include error handling, fallback suggestions, and ambiguous match resolution
 * - Future extensions may support LLM-assisted parsing or contextual prediction
 */
package com.orebit.mod.commands;
