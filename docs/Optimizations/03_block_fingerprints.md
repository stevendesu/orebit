In the [chapter on reading blocks](01_block_reading.md) we got the cost of reading a
single block down to a handful of nanoseconds. But a pathfinder doesn't ask about
a block *once* — it asks the same handful of questions about the same cells over
and over: *can I stand here? walk through it? break it? is it lava?* Even at a few
nanoseconds a read, doing that a million times a search adds up, and most of those
reads are re-answering a question we already answered for an identical block a
moment ago.

So we don't read blocks during a search at all. We **precompute the answers** —
once, when a chunk loads — into a compact form built for exactly the questions
pathfinding asks. That form is what this page is about, along with a few decisions
that turned out to be far less obvious than they looked.

## One block, one `long`

Everything the pathfinder needs to know about a block — its collision shape, how
tall you stand on it, whether it holds a fluid, how hard it is to break, whether
it hurts to touch — gets packed into a single **64-bit `long`** we call the
block's *fingerprint*. Each property is a few bits at a known offset:

```
 bits  field
 0–4   topY      (collision height, in 1/16ths)
 5–7   shape      (full cube / slab / stair / …)
 16–17 fluid      (none / water / lava)
 22    damaging   (lava, fire, cactus…)
 24–31 hardness   (how long to mine)
 …
```

Why one `long`? Because then *reading a property is free*. There's no object to
chase a pointer to, no method call to dispatch — just one array load to get the
`long`, then a shift-and-mask to pull out the field you want, both of which the
CPU does in a cycle or two on a value already sitting in a register. "Is this
block damaging?" becomes `(d & DAMAGE_BIT) != 0`. That's the whole read.

## Twenty-eight thousand blocks, a few hundred answers

Here's the trick that makes this cheap to *store*, not just to read. Minecraft has
about **28,000 distinct block states** (every facing of every stair, every
moisture level of farmland, and so on). But to a *pathfinder*, the overwhelming
majority navigate identically — dirt, stone, ore, deepslate, netherrack and a
thousand other blocks are all just "a full solid cube you stand on top of and can
mine." Their fingerprints come out **bit-for-bit identical**.

So we dedup on the fingerprint itself: two blocks that pack to the same `long` are
the same *navtype*, and we keep one copy. Those ~28,000 states collapse to **a few
hundred** distinct navtypes. We store that little table of `long`s once (a few
kilobytes — small enough to live entirely in L1 cache), and the world grid just
stores a small **index** into it per cell. The fingerprint is simultaneously the
data *and* the dedup key.

That index has a budget: we give it **10 bits per cell**, so there's room for
**1024 navtypes**. We measured around 500 in practice — comfortable, but the
budget is real, and it's the thing that makes the next two decisions interesting.

## The guessing game

We designed this fingerprint *before* the pathfinder existed. So the choice of
*which* properties to pack was, honestly, educated guessing about what movement
code would eventually want. Some guesses aged well:

- **Per-block-state, not per-block.** A north-facing stair and a south-facing
  stair navigate differently, so they get different fingerprints. That falls out
  for free from packing the geometry, and it's correct.
- **Leaving light emission *out*.** A torch-lit block and an unlit one are
  identical to walk on, but light has up to 16 values per block — including it
  would have multiplied the navtype count ~15× for information pathfinding never
  uses. Easy call to exclude.

And then there was the one we got wrong.

## The bits that cost half our table

We reserved **6 bits** for a "sturdy faces" mask — one bit per face, recording
which sides of a block are solid enough to place something against. It seemed
obviously useful: surely the bot needs to know what it can build on?

It turns out you can place a block against **almost anything** — fences, ladders,
signs, buttons, carpets, saplings. The "sturdy face" distinction only matters for
attaching *support-requiring* objects like torches and buttons, which is a feature
the bot doesn't have and won't for a long time. So those 6 bits were answering a
question nobody asked.

How much were they costing? We ran the numbers across the whole table:

```
navtypes (total)            : 503
navtypes if faces removed   : 246   (faces costs 257 navtypes — over half the table)
```

**Over half.** And the reason is beautiful in hindsight: the face mask is what
encodes a stair's *facing*. With it, the four facings of every stair material are
four separate navtypes; **216 of our ~500 navtypes were stairs**, distinguished by
a fact the planner never reads (it treats every stair the same). Strip the face
bits and they all collapse together.

So we're reclaiming all 6 bits. The lesson is sharper than "don't store unused
data" — it's that in a **deduplicated** table, a speculative field isn't just dead
weight, it's a *multiplier*: every value it can take splits otherwise-identical
blocks into separate navtypes and eats your budget. When a real feature needs
face data, we'll add a purpose-built field for it (a 2-bit stair facing, say)
instead of a vague 6-bit mask we hoped would cover everything.

## The free lunch: store the *answer*, not the ingredients

That cost — "every distinguishing bit splits the table" — has a wonderful flip
side, and it shapes how we extend the fingerprint from here.

The expensive bits are the ones carrying **raw data** that separates blocks. But a
bit that's a **pure function of bits already in the fingerprint** costs *nothing*.
Take "can I stand on this?" — it's computable from the shape, the fluid, and the
damaging flag, all of which are already in the `long`. If we precompute that
answer and store it as its own bit, two blocks that were identical before are
*still* identical after (same inputs, same answer), so **no new navtype is ever
created**. The table doesn't grow by a single entry.

This is the rule we navigate by now:

> **Raw data costs navtypes. Derived data is free.**

So the predicates the pathfinder evaluates millions of times — *standable*,
*passable*, *breakable* — don't have to re-run their little chains of shifts and
comparisons on every call. We bake each answer into a dedicated bit at table-build
time, turning a four-branch question into a single bit-test, and it costs us
nothing in the budget because the answer was always implied by bits we already
had. (We keep the precomputed bit honest with a startup check that re-derives it
against the live predicate for every navtype, so the two can never silently drift
apart.)

## A small trick with fluids

One last bit of fun, because it's the kind of thing that's satisfying to get
right. We store fluid as two bits, and the pathfinder asks two questions: "is
there *any* fluid here?" and "is it lava?" The obvious encoding is `00` none,
`01` water, `10` lava — but then "is there any fluid" means *either* bit is set,
which is an OR.

Number them differently — `00` none, `01` water, `11` lava — and the bits line up
with the questions: the **low bit alone** means "there's a fluid," and the **high
bit alone** means "it's lava." Two independent single-bit tests, no OR. It saves
one CPU instruction. Utterly trivial on its own, but it's *free*, and a hot loop
is built out of exactly these.

## The payoff

Put it together and the picture is this: every block in the world is one of a few
hundred navtypes; each navtype is 8 bytes; the whole table fits in L1 cache; the
grid stores a 10-bit index per cell; and every question the pathfinder asks is a
shift-and-mask — increasingly, a single bit-test — on a value already in a
register. No live block reads, no objects, no pointer chasing, no megamorphic
dispatch. Just the answers it needs, precomputed and packed, waiting to be read a
million times without flinching.
