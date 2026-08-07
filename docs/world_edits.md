# Breaking & Placing

Orebit's bot doesn't just walk the world — it edits it: digging through hills,
bridging gaps, pillaring up cliffs, punching through cobwebs. This page documents how
those edits work, what they cost, and the guarantees around them. The organizing
principle throughout is **planner/executor parity**: the planner never plans an edit
the executor would refuse, and the executor never performs one the planner didn't
price — the same rules, checked on both sides.

## Mining takes real time

The bot digs like a player, not like a command block. When a plan calls for a break,
the mining actuator:

1. **equips its fastest hotbar tool** for the block (so the held item you see is the
   one doing the work),
2. **faces the block and swings**, showing the vanilla crack overlay as it progresses,
3. accumulates vanilla's own per-tick destroy progress — the real formula, folding
   tool tier, Efficiency, Haste, on-ground and in-water modifiers against the block's
   hardness — and
4. breaks the block **on the exact tick vanilla mining would**, through the survival
   break path: proper drops, XP, and tool wear (the bot's real player inventory
   auto-collects what falls).

The whole thing is reactive, like holding the mouse button: the break continues only
while the mover keeps requesting the same block each tick. Release the "button" —
because the plan changed, or the block is no longer needed — and progress resets,
exactly like vanilla.

Because the planner's break costs come from the same vanilla timing model, the ticks
the search *charged* for a dig are the ticks the bot actually *spends* digging. Two
knobs bend this: `mining.ticksByHardness = false` switches to a flat per-block time,
and `mining.breakBaseCost` adds a flat surcharge to every break **at planning time** —
a behavioral "reluctance to edit the world" that biases the bot toward detouring over
digging without forbidding anything (the mining-side mirror of the placement
reluctance below).

## Placing

Placing is near-instant in-game, so the planner's placement cost is **behavioral, not
physical**: `placement.placeBaseCost` (default 6.0 ticks) is the bot's reluctance to
scaffold, and `placement.removalCostWeight` adds a premium scaled by how hard the
placed block would be to mine back out — a bot carrying dirt and obsidian bridges with
the dirt. By default the bot conjures an infinite supply of a throwaway block
(`placement.conjuredBlock`); with `placement.consumesBlocks = true` it builds from its
real inventory and can run out — and every placement then carries an extra flat
10-tick premium in the planner, so a bot spending real blocks scaffolds noticeably
less readily than one conjuring them.

One subtlety the executor handles: the planner's "open to place into" test is broader
than vanilla's own — a cell counts as open when it is either vanilla-*replaceable*
(air, water, lava, fire, tall grass) **or** simply has no collision shape (a berry
bush, a torch, a sapling). A player placing into a grass cell just places, and the
game clears the soft occupant; where the occupant really is vanilla-replaceable the
bot does exactly that. Where it isn't — the empty-shape-but-not-replaceable cases — a
bare placement would silently no-op and leave the bot stepping onto footing that never
appeared, so the bot clears the soft occupant first, the way a player would, and then
places. Only an occupant it may not break at all (owner-protected, or unbreakable)
aborts the place — the plan re-checks and routes around.

## Punching through hazards

Some blocks you don't walk around *or* dig politely — you punch through them. A cobweb
slows movement to ~5% of walk speed, so wading through one webbed cell costs
4.633 ÷ 0.05 ≈ 93 ticks; a berry bush charges a mortal bot one hitpoint
(`pathing.costPerHitpoint` — 100 ticks at the default) on top of its slow.

So wherever a movement that edits as it goes (the ground moves — Traverse, Ascend,
Descend) would carry the bot's body through a hazard or slow cell, the planner prices
**both options at the same node** — pass through intact, or break the cell first — and
folds whichever is cheaper:

$$ \min(\ \text{transit surcharge},\ \ \text{mining time} + \texttt{breakBaseCost}\ ) $$

The arbitration is exact, not a heuristic: both options land on the identical search
node, so taking the minimum gives the same answer as searching both. And because the
mining time is the *real* tool-aware time, the answer changes with the loadout: a
sword cuts a cobweb in ~20 ticks (clear win over ~93 wading), while bare-handed a web
takes ~400 ticks — wading wins. A berry bush breaks near-instantly, so where the bush
is breakable at all a mortal bot punches it rather than paying the 100-tick prick —
unless the owner raised `mining.breakBaseCost` to make world edits expensive, in which
case it detours. (Out of the box the sweet berry bush is in the **default protected
list** below, and a protected cell is never punched through, so the stock bot wades or
routes around one; drop it from `mining.protectedBlocks` to get the punch-through.)

Two things are never on the break side of that minimum. **Fluids** aren't "broken" —
water and lava are swum, routed around, or sealed by a placement, so a body cell
holding one always pays its intact price (for lava that means its damage charge plus a
slow-media surcharge; the swim moves price a lava cell on their own terms instead).
And **movements that fold no edits by design** always pay the intact-transit price too:
the airborne family (Parkour, Fall — you can't mine mid-flight) and `Diagonal`'s two
corner columns.

## Protected blocks

`mining.protectedBlocks` is a comma-separated list of block ids and `#`-prefixed block
tags the bot must **never break — nor destroy by placing over** (placing into a
grass-like cell destroys its occupant, so protection covers that path too):

```properties
mining.protectedBlocks = minecraft:chest, #minecraft:beds, minecraft:diamond_ore
```

**This is the one setting that doesn't default to empty.** Out of the box it protects a broad set of
player-placed and decorative blocks — logs, planks, stairs, slabs, walls, fences, doors, glass, carpets,
work stations, chests, torches and other light sources, redstone components, cultivated plants, beds, signs,
banners, ladders, and more — so a bot won't chew through someone's build. Wild grass/ferns and leaves are
**not** protected (the bot may cut through those), and the list is fully editable — set
`mining.protectedBlocks=` (empty) to let the bot break anything. See
[the default set in the config reference](configuration.md#the-default-protected-blocks-set).

Protection stops the bot **breaking** a block, not **using** it: a protected wooden or copper door is still
opened and closed to walk through (a non-destructive action) — only iron doors, which can't be hand-operated,
get routed around. And protecting a block never stops it being a deliberate *target*: `/bot mine iron` still
works even though ores can appear in a protected list, because protection only governs breaking blocks *to
clear a path*.

It's enforced on both sides of the parity rule:

- **Planner:** protection is folded into the block-fingerprint table itself, so every
  break decision in the search refuses a protected block with a single bit test —
  routes are planned *around* protected blocks, never through them.
- **Executor:** every ROUTE break (a path's folded break/place edits, the line-of-sight
  dig on the way to an ore, a build clearing what's in a schematic cell's way) re-checks
  the actual block state against the list at the moment of breaking — the backstop that
  also covers a nav grid built before the list changed. Deliberate task breaks (a gather
  target, a mature-crop harvest, reclaiming the bot's own temporary crafting table,
  `/bot mine`) never consult the list — that's what makes `/bot gather wood` work with
  logs protected.

One caveat worth knowing: because protection is baked into cached nav data at
classification time, **changing the list needs a server restart** (or waiting for
chunks to rebuild) before the *planner* fully sees it — `/bot config reload` warns
about this, and the executor-side refusal applies immediately either way. A tag that
doesn't exist on your server parses fine and simply matches nothing.

## Unbreakable blocks

Bedrock, barriers, end portal frames — anything vanilla gives a negative destroy time
— are detected by that sign at classification time (no hardcoded block list, so it
tracks whatever the running version considers unbreakable). By default the bot treats
them as walls.

`mining.allowUnbreakable = true` opts the bot into "mining" them anyway, at a
**tool-derived** stand-in price — vanilla defines no time, so the price is policy.
`mining.unbreakableHardness` (default `3200`) is a pretend hardness fed through the
normal mining-time formula assuming a pickaxe, so a **better pickaxe digs faster**
(a diamond pickaxe lands on ~2400 ticks / 2 minutes at the default; a stone one is
slower, bare hands far slower still). The default keeps unbreakable mining an extreme
last resort — a break-even of ~518 walk-blocks of detour with a diamond pick — that
the planner routes around whenever any cheaper path exists. The executor keeps the
parity promise literally: the bot stands there grinding for exactly the priced time,
crack overlay advancing, then forces the break — the planner's price is the executor's
time, both scaled by the bot's pickaxe tier. This is its own gate, deliberately
independent of `mining.maxHardness` (unbreakable isn't "very hard"; it's a different
axis), and `mining.protectedBlocks` always overrides it: a protected bedrock stays.

## Every edit is attributed

Each block the bot actually breaks or places writes one line to the server log saying
**what it did, where, and which movement of the plan wanted it**:

```
[Orebit] break executed at (120, 63, -35) for step Traverse -> (121, 63, -35)
[Orebit] place executed at (88, 70, 14) for step Pillar -> (88, 71, 14)
```

Refused edits are logged the same way (e.g. a place aborted because the occupant is
protected), so "who dug this hole?" and "why didn't it bridge here?" are both a grep
away. If your server has bots and a block changed, the log says exactly which move of
which plan did it — world edits are never silent.

## Checking what the planner sees

If the bot does something surprising around a hazard or a protected block, the
`/bot probe <x> <y> <z>` command dumps exactly what the planner would read at that
cell — the classified block fingerprint, the precomputed hazard/slow flags, the
per-cell transit surcharge and break-through price, and the capability settings in
force — which pins down in one command whether a surprise is stale nav data, a
misclassified block, or a config choice doing what you told it to.
