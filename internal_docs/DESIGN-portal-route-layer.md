# DESIGN — Portal Route Layer (CONDENSED — splice half SHIPPED, route layer PENDING; full text in git history pre-s52)

**Status (2026-07-03, unchanged):**
- **SHIPPED (s44, as shared infrastructure):** the **splice primitive (§4)** —
  `pathfinding/splice/SpliceSeam.java` (seed → accept → adopt, Chebyshev tol) +
  `blockpathfinder/EditSnapshot.java` (latest-wins fold of a plan's unexecuted suffix) +
  `PathEdits.addSnapshot` (**§4.3** baseline seeding: appended AFTER the cameFrom walk so path edits
  shadow the baseline) + the `findPath(..., baseline, budgetNanos)` params. Also live:
  `worldmodel/pathing/NetherPortalIndex.java` (§5.1 discovery) and the portal-seek/ENTER terminal states
  in `AllyBotEntity` (§2/§3.2 — still inline, not yet extracted to an `EnterPortalAction`).
- **PENDING (the route layer proper — none implemented):** `RouteDriver`/`RouteLeg`/`Route` (§3, §8.1)
  multi-leg driver above `PathPlan`; `PortalPairings` observation + canonical keys (§5.2); the
  break-even estimator + margin gate (§6); the per-dimension `EditLedger` carry rule (§4.4,
  owner-ratified: PathEdits carry on splice EXCEPT across dimension changes — see the
  `path-splice-primitive` memory); lazy-vs-eager leg policy (§4.6); END_PORTAL (§7).

**✅ §7's descriptor blocker is RESOLVED — re-verified against `NavBlock` 2026-08-07.** Every earlier
statement of it is stale, in both directions:

- The old "PORTAL is bit 43, widen it into 43–44" plan is dead twice over: the portal marker **moved down
  to bits 11–12**, and **bit 43 is now `DOOR_OPEN_BIT`** (44 is `PROTECTED`).
- The follow-up "put a fresh 2-bit `PORTAL_KIND` at 45–46, free bits are 8–13 and 45–63" is also wrong:
  8–13 are fully claimed (stair/door facing 8–9, stair half 10, portal field 11–12, door hinge 13), and
  45–51 are claimed too (45 `REDUCED_JUMP`, 46–47 `BUBBLE`, 48–49 `FALLSOFT`, 50 `DOOR_TOGGLEABLE`,
  51 `NARROW_TOP`). **The free bits are 52–63** — nothing above 51 is in use.
- **No new bits are needed anyway.** The portal field at 11–12 is *already* the 2-bit kind field this
  section wanted, mirroring the `fluid` field's low/high shape:
  `00 none / 01 end (end_portal + end_gateway) / 11 nether / 10 unused`. The LOW bit is what the walker's
  passability gate subtracts (route around ALL portals); the HIGH bit is what `NetherPortalIndex` and the
  follower read (enter nether portals deliberately, never chase an end portal). §7 can be built on the
  existing encoding; only a *gateway-vs-end* split would need the spare `10` code.

**§ map (sections cited by code Javadocs):** §1 problem/scope; §2 what exists; §3 the leg model (route =
legs + transits, one PathPlan per leg); **§4 THE SPLICE PRIMITIVE** — §4.1 what is spliced, §4.2 why the
later path needs the earlier path's edits, **§4.3 baseline seeding mechanism + its hot-path bill**,
§4.4 PathEdits-across-dimensions rule, §4.5 seam validity (acceptance predicate + fallback), §4.6 lazy vs
eager legs; §5 portal knowledge & pairing; §6 the break-even gate (nether 8:1 arithmetic); §7 END_PORTAL;
§8 execution & failure handling (route driver, per-leg replan semantics, failure ladder, **§8.4 the
unverified fake-player portal runtime checklist — still the gating dependency**); §9 phasing; §10 risks.
