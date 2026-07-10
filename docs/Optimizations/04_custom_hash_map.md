All over Orebit's hottest code, the same little question keeps coming up: *given
a block position, what do I know about it?* The pathfinder asks "have I visited
this cell, and what row of my search table is it in?" The planned-edits tracker
asks "is this cell one the bot is about to place a block in, or break?" Both are
the same shape of question — a lookup keyed by a block position — and Minecraft
hands us that position as a single packed `long`.

So we want a map from `long` to *something*. Java has a perfectly good `HashMap`
for that. We don't use it. This page is about the map we built instead, why we
bothered, and the two small tricks that make it punch above its weight.

## Why not just use `HashMap`?

The short answer is a hidden cost called **boxing** — and it's worth understanding
in full, because it's the whole reason this map exists.

A `HashMap` can only store objects, not primitives. So every time you look
something up with a `long` key, Java silently wraps that primitive in a `Long`
*object* on the heap just to hand it to the map. Our keys are block positions —
huge 64-bit numbers, well outside the tiny range of values Java keeps pre-made —
so every single lookup mints a brand-new throwaway object. Do that a few million
times in one pathfinding search and you've buried the
[Garbage Collector](02_reusing_memory.md) in short-lived trash, which it then has to
stop and sweep up at unpredictable moments.

There's a second, quieter cost too. A `HashMap` resolves collisions — two keys
that land in the same bucket — by stringing them together into a linked list of
`Entry` objects. Those entries are *more* heap objects, scattered across memory,
each one a little pointer-chase away from the last. It's a structure built for
flexibility, not for speed in a tight loop.

We want neither cost. So we want a map that:

1. keeps keys as raw `long`s, never boxing them, and
2. has no per-entry objects at all.

That combination has a name: **open addressing.**

## Open addressing: a map that's just two arrays

The idea behind open addressing is to throw away the linked lists entirely and
store everything in two flat, parallel arrays — one for keys, one for values:

```java
long[] keys;     // the packed block position in each slot
int[]  values;   // what we're storing for that key
```

To find a key's slot, we hash it down to an index. If that slot is already
occupied by a *different* key — a collision — we don't build a linked list. We
just walk forward to the next slot, and the next, until we find our key or an
empty slot. This is called **linear probing**, and the whole lookup looks like
this:

```java
int slot = hash(key) & mask;       // mask = capacity - 1, capacity a power of two
for (;;) {
    if (isEmpty(slot))    return MISSING;   // ran into an empty slot → not here
    if (keys[slot] == key) return values[slot];  // found it
    slot = (slot + 1) & mask;      // occupied by someone else → try the next slot
}
```

That's the entire data structure. No objects are created during a lookup or an
insert. The key never leaves its `long[]`. There is nothing for the Garbage
Collector to do. And because the keys and values live in contiguous arrays, the
CPU's cache loves them — a probe that has to step a slot or two forward is almost
certainly stepping into memory that's already been loaded.

But that `isEmpty(slot)` check hides a question: how do we know a slot is empty?
That's where the two tricks come in.

## Trick #1: let an impossible value mean "empty"

The naive way to track which slots are full is a third array of booleans. That's
an extra array to allocate, an extra read on every probe, and an extra thing to
keep in sync. We can do better by noticing that, very often, *one of the values we
could store is impossible* — so we can borrow it to mean "empty."

In the pathfinder's map, the value is a **row number** in the search table: 0, 1,
2, 3, and so on. Row numbers are never negative. So we initialize the value array
to `-1` and let that stand for "nobody's home":

```java
if (mapRow[slot] == -1) {          // -1 is impossible as a real row → empty slot
    mapRow[slot] = newRow(key);    // claim it
    return mapRow[slot];
}
```

One array does double duty: it stores the row numbers *and* marks the empty slots,
with no separate bookkeeping and no extra memory.

## Trick #2: when "empty" and "absent" are the same thing

The planned-edits map is even slicker. It maps a block position to a tiny code:
the bot plans to **place** a block here (`1`), **break** the block here (`2`), or
do **nothing** (`0`). And here's the thing — "this slot is empty" and "this cell
has no planned edit" are *the exact same statement*. Both mean zero.

So the value array doesn't just mark empty slots; the value `0` **is** the empty
marker, for free, because it's already the answer we'd return anyway:

```java
byte k = kinds[slot];
if (k == 0) return NONE;          // empty slot AND "no edit here" — same thing
if (keys[slot] == pos) return k;  // found a real placed/break entry
```

This collapses two concepts into one and buys us a lovely bonus: **clearing the
whole map is a single `Arrays.fill(kinds, 0)`.** The pathfinder rebuilds this
edit map constantly — once for every node it expands along an edit-carrying path —
so a near-instant reset, with no per-entry cleanup, matters a great deal.

## Spreading the keys out

There's one subtlety that can quietly ruin an open-addressing map: a bad hash. If
many keys land on the same slot, those "walk forward to the next slot" probes get
longer and longer, and the map slows to a crawl.

Minecraft's packed block positions are *especially* dangerous here, because
they're not random — they're structured. The low bits hold the Y coordinate, the
next bits hold Z, the high bits hold X. The cells a pathfinder examines are all
neighbors, so their packed values are almost identical, differing only in a few
low bits. Feed those straight into `key & mask` and they'd all pile onto a handful
of slots.

So before we take the modulo, we scramble the key with the finalizer from
**MurmurHash3** — a short sequence of shifts and multiplications whose entire job
is to stir every bit of the input into every bit of the output:

```java
private static int slotFor(long k, int mask) {
    k ^= k >>> 33;
    k *= 0xff51afd7ed558ccdL;
    k ^= k >>> 33;
    k *= 0xc4ceb9fe1a85ec53L;
    k ^= k >>> 33;
    return (int) k & mask;
}
```

Two block positions one step apart go in looking nearly identical and come out
looking completely unrelated, scattered evenly across the table. The probe chains
stay short, and lookups stay fast.

## When we *do* allocate

There's exactly one moment this map touches the heap: when it gets too full. Pack
an open-addressing table past about three-quarters full and the probe chains start
to lengthen, so when we cross that line we allocate a fresh pair of arrays at
double the size and re-insert everything into them.

That's a real allocation — but it's the good kind. It happens a *handful* of times
over an entire search, not once per node, so its cost is amortized down to
nothing. Doubling means a search that visits ten thousand cells resizes maybe a
dozen times total. The hot path — the millions of lookups *between* those resizes
— stays perfectly allocation-free.

## What ~40 lines bought us

It feels almost absurd to reimplement a hash map. They're a solved problem; the
standard library's is excellent. But "excellent for general use" and "excellent
called a million times in a tight loop under a deadline" are different bars. By
owning these ~40 lines instead of reaching for `HashMap<Long, …>`, we get:

- **no boxing** — keys stay raw `long`s start to finish;
- **no per-entry objects** — the whole map is two arrays, not a forest of `Entry`
  nodes;
- **cache-friendly** layout — contiguous arrays the CPU can prefetch;
- **a free, instant `clear()`** on the edit map, thanks to Trick #2.

Add it up and this little structure is a big part of how the pathfinder's per-node
cost fell from ~8,000 nanoseconds to ~1,290 — and later, once the last hidden
allocations were chased out, to ~950 — the story told in full over in
[Pathfinding on a Tick Budget](05_pathfinding_hot_path.md).
