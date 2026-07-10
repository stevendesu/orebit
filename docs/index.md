Welcome to Orebit! Orebit is your personal assistant for navigating the world
of MineCraft. He's here to do the tasks you don't want to do, freeing up your
time to do what you'd rather do.

Check out the side bar for a list of features, functions, and general info on
how Orebit works. If you're looking for something in particular, check out the
search bar in the top-right.

## Installation

Looking to install Orebit on your own server? Check out our
[Installation Guide](./getting_started/installation.md).

## Contributing

Want to contribute to the Orebit project? Thanks! I'll provide details here
later on how you can help.

## Why "Orebit"?

 * **Ore** = the stuff you mine for
 * **Bit** = a computer

Also, like a good little helper, he's always hanging out in your orbit.

## Special Thanks and Inspiration

Orebit was inspired by my wife, who I keep asking to play MineCraft with me but
she always says she finds it so boring to gather resources and wishes she could
just build and play without all of the grinding.

While the task system and pathfinding were originally conceived without prior
knowledge of existing mods or tools, shortly into the project I looked up what
tools might already exist.

While I take pride in stating plainly that I did not copy or steal ideas from
these projects, I __did__ look at their implementation *after* planning my own
to see if others had the same ideas or if I was building something completely
out of left field.

### Baritone

[Baritone](https://github.com/cabaletta/baritone) is advertised as "Google Maps
for Block Game". It primarily provides a pathfinding system allowing your
character to automatically travel to any specified location or block type.

I was pleased to see that Baritone also:

 * Settled on a modified version of `A*` for pathfinding, building a graph of
   valid moves rather than using simple unit steps on a grid
 * Represented distant locations using a compact format and only focused on
   local path correctness, lazily evaluating more distant parts of the path when
   they became relevant
 * Wrote replacements for block access utilities since the built-in ones are
   terribly slow and inefficient
 * Considered how placing or breaking blocks could mutate the grid during the
   course of following a path, and factored this into path planning

That said, there are a few key areas where I believe my implementation will
outshine Baritone:

 * Rather than simply attempting to speed up block data access, I bypass it
   completely by generating an alternate representation of the world that's
   optimized specifically for pathfinding
 * Rather than the simple but hardly-useful 2 bits per chunk representation of
   distant locations, I build a compact [region-level cost model](./worldmodel.md#region-level)
   of the world, enabling truly hierarchical pathfinding — plans that reach
   thousands of blocks — and feed that same coarse map *back down* into the block
   search as a [topology-aware heuristic](./Optimizations/11_region_heuristic.md), so
   the fine search stops flooding walls it can't see around

The end result is faster and more accurate block search and pathfinding over
significantly larger distances.

### Alto Clef

[Alto Clef](https://github.com/gaucho-matrero/altoclef) is a mod built on top
of Baritone that adds an extra layer of complexity: **tasks**. This way you can
give your bot a complex string of commands such as:

 * Navigate to a tree
 * Break it
 * Collect the wood
 * Craft a crafting table
 * Craft a wooden pick
 * Navigate to stone
 * Mine it
 * Craft a furnace

This bot was the first one to autonomously beat MineCraft without any human
input.

I was pleased to see that Alto Clef also:

 * Uses a tree to represent the prerequisites for tasks
 * Uses multiple state machines to interrupt behavior for higher priority tasks
   like eating or self-defense
 * Adds an Event Bus for updating bot state

Truth be told, the implementation was almost *exactly* what I planned on
building, with maybe minor variation in the representation of task sub-trees.
