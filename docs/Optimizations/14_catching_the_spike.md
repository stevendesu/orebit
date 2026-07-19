# Catching the Spike

With the [saved map](13_persisting_the_map.md) in place, the real test was a long walk: send the bot
three thousand blocks into terrain it had explored a session earlier, then send it home through ground
that had since unloaded. It worked. The persisted coarse route carried it out, the shards paged back in as
it approached, and the compass stayed accurate the whole way. Ninety-nine point nine percent smooth.

The other tenth of a percent was two full **~2-second freezes**, mid-walk, once each run. The kind of stall
you feel. And the interesting thing about them — the thing that made them hard — is that they moved. Run it
again and the freeze happened somewhere else entirely.

## A spike that won't hold still

A stall that changes location every run rules out a whole class of causes. It isn't a pathological chunk or
a bad spot in the map — those would freeze at the *same* place every time. We confirmed the negatives
directly: cranking the memory cap so nothing could ever be evicted still froze, so it wasn't eviction churn;
and the freeze point tracked with *time into the walk*, not with any coordinate. Something was firing on a
clock, not on a place. The prime suspect for a multi-second, location-independent, occasional pause on the
JVM is a garbage-collection pause — and the map build does allocate in bursts. Plausible. But "plausible"
is where the [house rule](08_measure_everything.md) starts, not where it ends. We had no measurement, and a
2-second tick is far too long to guess at.

## Build the instrument

Per-operation timing wouldn't catch this — the freeze was some single tick swallowing 2000 ms whole, and no
individual operation we time is anywhere near that. What we needed was to decompose *a slow tick itself*:
when a tick runs long, where did the time go? So we built one small monitor that, whenever a tick blew past
a threshold, attributed it across every phase of our own work, the garbage collector, and everything left
over (which is vanilla's own tick):

```
[Orebit][SLOWTICK] tick=Xms ourOps=Yms gc=Zms other=Wms (top: …)
  | navBuild= mergeUp= hpaFlush= shardLoad= evict= persist= botTick=
```

`gc` reads straight off the JVM's collector counters, so a GC pause can't hide inside "our" time or
vanilla's. Everything Orebit does on the tick thread is wrapped and named. One line, emitted only on a slow
tick, behind a debug flag. Then reproduce the walk with it on.

## It named the culprit in one line

```
tick=1971ms  ourOps=1888ms  gc=0ms  other=83ms  (top: ourOps)  | persist=1883
```

The GC theory was dead on arrival — `gc=0`. It wasn't vanilla either (`other` was noise). It was
**`persist`**: the periodic crash-insurance save, 1883 milliseconds of it, in a single tick.

And that explains the ghost perfectly. The periodic save fires on a **tick counter** — roughly every five
minutes — not on any location. So it lands wherever the bot happens to be when the timer comes due:
a different place every run, immune to every cap and coordinate we'd tried. We'd been hunting a GC pause;
the telemetry pointed at a *feature*, working exactly as designed, just far too expensively.

## Why the save was so heavy

Three costs had compounded, none of them visible until the save was the thing under the light:

- It was **unbudgeted**. Every other periodic job Orebit runs — building nav data, refreshing the map after
  edits, paging shards — drains under a per-tick time budget. The save didn't. When its timer fired it wrote
  *everything* dirty, right then, in one tick.
- The encode was **quadratic**. Writing each dirty shard re-scanned the whole dimension's data to find that
  shard's rows. Ten dirty shards meant ten full scans. Five minutes of exploration dirties a lot of shards.
- The two summary files were **rewritten every save**, whole, whether or not that was the part that changed.

## Fix it the way everything else already worked

The shape of the fix was sitting in every neighbouring subsystem: they all trickle their work under a budget.
So the periodic save learned the same discipline. It now writes under a small per-tick budget
(`hpa.persistFlushBudgetMs`), and if there's more backlog than fits, it **resumes on the next tick** and the
one after until the backlog clears — with a guaranteed floor of at least one shard per tick so it always makes
progress. A shard's "dirty" flag is cleared only *after* its bytes are safely written, so spreading the work
across ticks can't drop a change. And the quadratic scan became a single pass: bucket every dirty row to its
shard once, then write, turning N scans into one. The summary files are written last, deferred to a later tick
if the budget's already spent.

That's safe precisely *because* this is the crash-insurance save and not the authoritative one — the complete
save still happens atomically on a clean shutdown, so a periodic save that takes a few extra ticks to finish
costs nothing but a little latency on the backup. The ~2-second spike became a sub-millisecond trickle no one
can feel.

## The lesson

The profiler in the [earlier chapters](12_field_build.md) is a scalpel for the *search* — it tells you which
method inside a hot loop is slow. It is the wrong tool for a whole-tick spike, because the spike isn't in any
one method; it's some scheduled job that fired at a bad time. That needs a different instrument: not "which
line is slow" but "when a tick runs long, what was running." An hour spent building that instrument turned an
un-reproducible, wrong-theory ghost into a single self-explaining log line. Measure the thing you're actually
confused about — and if you can't, build the smallest tool that lets you.
