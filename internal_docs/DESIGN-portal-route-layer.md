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

**⚠ Design blocker carried forward (§7):** the doc's plan to widen PORTAL bit 43 into bits 43–44 is DEAD —
bit 44 is now PROTECTED (s43). Current free descriptor bits: 8–13 and 45–63. Recommended: a fresh 2-bit
`PORTAL_KIND` at bits 45–46 (0 none / 1 nether / 2 end / 3 gateway), migrating the bit-43 flag; or a lone
END_PORTAL bit at 45 if gateway is never needed. Verify against `NavBlock` before building §7.

**§ map (sections cited by code Javadocs):** §1 problem/scope; §2 what exists; §3 the leg model (route =
legs + transits, one PathPlan per leg); **§4 THE SPLICE PRIMITIVE** — §4.1 what is spliced, §4.2 why the
later path needs the earlier path's edits, **§4.3 baseline seeding mechanism + its hot-path bill**,
§4.4 PathEdits-across-dimensions rule, §4.5 seam validity (acceptance predicate + fallback), §4.6 lazy vs
eager legs; §5 portal knowledge & pairing; §6 the break-even gate (nether 8:1 arithmetic); §7 END_PORTAL;
§8 execution & failure handling (route driver, per-leg replan semantics, failure ladder, **§8.4 the
unverified fake-player portal runtime checklist — still the gating dependency**); §9 phasing; §10 risks.
