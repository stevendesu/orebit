/**
 * CommandRegistry.java
 *
 * Main Component: commands/
 * Environment: MAIN
 *
 * This class serves as the central repository of all known command types.
 * It maps command names (and aliases) to their corresponding `CommandHandler`
 * implementations and provides metadata about each command. The registry allows
 * the dispatcher to locate the correct handler at runtime and optionally supports
 * help text, usage examples, permissions, and categories.
 *
 * ---------------------------
 * What This File Does:
 * ---------------------------
 * - Maintains a mapping of command names and aliases to `CommandHandler` instances
 * - Provides command metadata for introspection (e.g., usage instructions)
 * - Registers all available commands at mod initialization or dynamically
 * - Optionally supports grouping commands into categories or modules
 *
 * ---------------------------
 * How This File Differs:
 * ---------------------------
 * - Unlike `CommandDispatcher`, this class does not execute commands—it only stores references
 * - Unlike `ChatCommandParser`, it does not parse strings—it only exposes known commands
 * - Unlike `CommandHandler`, it does not define logic—it references implementations
 *
 * ---------------------------
 * Assumptions:
 * ---------------------------
 * - All commands are uniquely identifiable by name or alias
 * - Aliases are normalized and case-insensitive by default
 * - Registration occurs early (e.g., on mod load or server startup)
 *
 * ---------------------------
 * Dependencies:
 * ---------------------------
 * - `CommandHandler`: The functional target of each registered command
 * - May consume or expose types like `ParsedCommand`, `CommandMetadata`, or `CommandCategory`
 *
 * ---------------------------
 * Dependents:
 * ---------------------------
 * - `CommandDispatcher`: Queries this registry to resolve handler targets
 * - `ChatCommandParser`: May consult for keyword hints or tab completion
 * - `Debug tools`: May use this to list available commands for inspection or help
 *
 * ---------------------------
 * Implementation Details:
 * ---------------------------
 * - Internally uses a `Map<String, CommandHandler>` or similar structure
 * - May include alias mapping and command metadata (e.g., help strings, argument templates)
 * - Future versions may allow dynamic command injection for scripting or modular features
 */
package com.orebit.mod.commands;
