# Finding & Gathering

Orebit exists because [gathering is boring](index.md#special-thanks-and-inspiration). So
the point of the whole navigation stack — the [region map](worldmodel.md#region-level),
the [two-tier search](pathfinding.md), the [survival mining](world_edits.md) — is this:
you say `/bot gather iron 5`, and five iron shows up in the bot's pockets without you
swinging a pick.

## Knowing where the ore is

The bot can't path to a resource it can't find, and scanning the whole world on demand
would be hopeless. So Orebit keeps a running census as it perceives the world.

Every chunk the bot loads already gets scanned once, to build the
[nav grid](worldmodel.md#block-level). That same single pass — no second scan — also
tallies the blocks worth knowing about: a fixed catalogue of resource classes (the ores
and valuables, plus a builder's palette of common materials), counted per region. Those
counts roll up the [region pyramid](worldmodel.md#region-level) exactly like navigation
costs do — a region knows how much iron it holds, its parent knows the sum, and so on up
— so "where's the nearest iron?" is a **best-first drill-down** over the pyramid, ranking
by quantity and proximity, never a brute scan. The store is sparse: a region only earns a
row once it actually contains something indexed, which keeps the whole resource memory
inside a few percent of a save's footprint.

`/bot find <resource>` runs exactly that query and reports the nearest known
concentration — a way to ask the bot what it has noticed without sending it anywhere.

## Going and getting it

`/bot gather <resource> [count]` is the query wired to the legs. It's a small state
machine riding on top of the same navigation and mining the bot uses for everything else:

1. **Find** the best candidate region from the pyramid.
2. **Path** there with the two-tier search.
3. **Scan** the area on arrival — the pyramid is a *compass*, not a map. It says "iron,
   that way," but the exact block is confirmed by a fresh local look once the bot is close
   and the chunk is truly loaded, so a stale or coarse count never sends the bot digging
   at empty rock.
4. **Mine** it — walk into line of sight, dig it out with [real mining time](world_edits.md),
   and then collect the drop off the ground.
5. **Repeat** until the quota is met, then **return** to where the gather started.

A detail that matters: the quota is counted as **items actually picked up into the
inventory**, not blocks broken. Fortune enchants, ore that drops raw material, blocks that
scatter into several items — the bot counts what it *has*, so "gather five iron" ends with
five iron regardless of drop mechanics. If the resource genuinely runs out before the
quota is met, the bot stops and tells you rather than wandering forever.

Because gather reuses the general navigation, everything the pathfinder knows comes along
for free — it will drop off a cliff into a cave if that's the cheap way down to the ore,
dig through a wall if [its tools make that cheaper](Optimizations/region_heuristic.md) than
walking around, and route around lava if it's mortal. The gathering behaviour *is* the
pathfinding behaviour, pointed at a block the bot found itself.

> **On the roadmap.** The resource census currently lives in memory and is rebuilt as
> the bot explores; persisting it across restarts (so a returning bot remembers where the
> diamonds were) is planned. Prospecting *unloaded* chunks — reasoning about ore the bot
> has never seen — is a later step.
