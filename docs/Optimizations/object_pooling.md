## Primer on RAM

Every program on your computer must share the same RAM. In order to ensure
programs play nicely, the Operating System assigns sections of RAM to each
process. For example, when I launch a program the Operating System may set
aside 50 megabytes of the 16,000 megabytes available to my computer and say
"this memory is __exclusively__ for use by program A".

Throughout its execution, the program may decide it needs more memory in order
to complete its tasks. When this happens, the program asks the Operating System
for additional RAM and the Operating System finds an unused section of memory,
assigning it to the program. This process is known as "memory allocation".

Memory allocation can take some time. Using memory that is already assigned to
your program is much, much faster.

When your program is finished with memory, it has the option to give it back to
the Operating System so that another program can use it later. This process is
called "memory deallocation". This is significantly faster than memory
allocation. A completely different issue arises here, though.

Theoretically every piece of memory should be allocated once and deallocated
once. However subtle bugs in code can lead to allocating memory and never
deallocating it (memory leak) or attempting to deallocate it twice (double
free). You might also try to read from memory afer it has been deallocated
(segmentation fault).

To save developers from these mistakes, many modern programming languages
(Java included) have what's known as a "Garbage Collector". The Garabge
Collector's job is to scan your memory occasionally looking for memory that is
no longer being used and is safe to deallocate.

The problem with Garbage Collectors is that they're slow. __Very__ slow. So the
more we can avoid them, the faster we'll be.

## Object Pooling

So the lesson from the primer is simple: **allocating memory is slow, and the
Garbage Collector that cleans it up is slower still.** The fastest memory is the
memory you already have.

This is the whole idea behind an **object pool**. Instead of allocating a fresh
object every time you need one and throwing it away when you're done (feeding the
Garbage Collector a steady diet of trash), you keep a little stash of objects
that you hand out and take back. The object gets allocated *once*, early in the
program's life, and then lives forever — borrowed, returned, borrowed, returned.

Orebit's clearest example is the **NavSection pool**. A NavSection is our compact
description of a 16×16×16 cube of the world — 4,096 cells, each packed into a
`short`. As you walk around, Minecraft is constantly loading chunks in front of
you and unloading them behind you, and we build a NavSection for every one. If we
allocated a brand-new section (and its 4,096-element array) on every chunk load
and let the Garbage Collector reap it on every unload, we'd produce a *steady
drizzle* of garbage for as long as the player is moving — which is to say,
always.

So we don't. We keep a pool:

```java
public final class NavSectionPool {
    private static final ArrayDeque<NavSection> POOL = new ArrayDeque<>(256);

    public static NavSection get(BlockPos origin) {
        NavSection section = POOL.isEmpty() ? new NavSection() : POOL.pop();
        section.reset(origin);   // wipe it clean and re-aim it at a new location
        return section;
    }

    public static void recycle(NavSection section) {
        POOL.push(section);      // hand it back for the next chunk
    }
}
```

When a chunk loads we `get()` a section — popping a recycled one if we have it,
allocating a new one only if the pool is empty. When a chunk unloads we
`recycle()` it back into the pool. After the first few seconds of play the pool
reaches a steady size and we essentially **stop allocating sections entirely**,
no matter how far the player travels. The Garbage Collector never has a reason to
wake up and scan them, because nothing is ever thrown away.

## Scratch Pads

Object pooling is great when you have a *population* of interchangeable objects
coming and going. But there's an even simpler pattern for the case where you just
need *one* temporary object, over and over, in a tight loop. We call it a
**scratch pad**: a single reusable object you fill in, read off, and wipe clean —
never reallocating.

You've actually already met one. In the
[chapter on reading blocks](block_reading.md) we replaced `new BlockPos(x, y, z)`
inside a loop with a single `BlockPos.Mutable` that we `.set(x, y, z)` on each
pass. That mutable position is a scratch pad. We allocate it once, then reuse the
same chunk of memory thousands of times.

The same trick shows up all over Orebit's hot paths. When the pathfinder
considers a move that requires breaking or placing blocks, it needs somewhere to
jot down "this move breaks *these* cells and places *those* cells, for *this*
extra cost." The naive approach allocates a fresh little list for every candidate
move — and a single search weighs hundreds of thousands of candidate moves. So
instead, a single `EditScratch` lives on the search context, and every candidate
borrows it the same way:

```java
EditScratch e = ctx.edits().reset();  // wipe the pad — no allocation
e.requireAir(x, y + 1, z);            // jot down what this move needs
e.requireAir(x, y + 2, z);
if (e.valid()) {
    out.accept(x, y, z, baseCost + e.extraCost(), e.snapshot());
}
```

`reset()` doesn't allocate anything — it just sets a couple of counters back to
zero so the next candidate overwrites the old scratch. We only ever pay for real
memory at the very end, in `snapshot()`, and *only* when a move genuinely carries
edits — the overwhelmingly common "just walk forward" move records nothing and
allocates nothing.

That "allocates nothing" is the recurring theme, and it reaches its full
expression in the pathfinder's innermost loop — where we threw out Java's
standard `HashMap` and `PriorityQueue` entirely to keep the Garbage Collector out
of the hot path. That story is worth a page of its own:
[**Pathfinding on a Tick Budget**](pathfinding_hot_path.md).
