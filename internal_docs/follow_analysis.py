#!/usr/bin/env python3
"""follow_analysis.py — offline reader for the follower's per-tick execution record.

`/bot debug verbose` writes one `exec` line per tick to `<run dir>/logs/latest.log`, plus a `PLAN`
line when a step's MovePlan is built and a `step FAILED` / `envelope:` pair when a validity envelope
trips. Those logs run to thousands of lines; this pulls the answers out.

Reads plain `.log` or gzipped `.log.gz`, so archived runs work without unpacking:

    python internal_docs/follow_analysis.py run/logs/latest.log              # step summary
    python internal_docs/follow_analysis.py run/logs/latest.log --step 3     # per-tick dump of step 3
    python internal_docs/follow_analysis.py run/logs/latest.log --fail       # the failing step, per tick
    python internal_docs/follow_analysis.py run/logs/latest.log --move Parkour
    python internal_docs/follow_analysis.py a.log.gz --diff b.log            # compare two runs

The three questions it exists to answer, none of which the raw log makes easy:

  1. "Where in the block did the previous move leave us, and how fast were we going?"  Every movement's
     geometry is framed from the CENTRE of its takeoff cell, and several price their reach from an
     ASSUMED approach speed. The summary prints entry/exit offCentre and speed per step, so an
     admission the entry state never earned is visible at a glance.
  2. "What did the follower actually command, tick by tick?"  The per-tick dump prints the inputs and
     the servo branch that wrote them next to the realized velocity and the displacement it produced,
     so a commanded input that produced nothing (a wall press, a sneak edge-guard) reads directly.
  3. "What changed between two runs?"  Chained moves make small perturbations travel: --diff aligns
     the two runs by step and prints where their entry states start to separate.

No dependencies (stdlib only) — unlike trace_analysis.py this is text in, text out.
"""

import argparse
import gzip
import io
import re
import sys
from collections import OrderedDict

# ---- parsing -------------------------------------------------------------------------------------

# A named float field that may be negative, e.g. `x=59.409` / `fwd=-0.86`.
def _f(name):
    return rf"{name}=(?P<{name}>-?\d+\.?\d*)"


EXEC_RE = re.compile(
    r"exec (?P<move>\w+) wp(?P<wp>\d+) -> \((?P<tx>-?\d+),(?P<ty>-?\d+),(?P<tz>-?\d+)\) "
    r"\((?P<medium>\w+)\) phase=(?P<phase>\d+)/(?P<phases>\d+) "
    r"botFoot=\((?P<fx>-?\d+),(?P<fy>-?\d+),(?P<fz>-?\d+)\) " + _f("botY") + r" "
    r"grounded=(?P<grounded>\w+) climbable=(?P<climbable>\w+) reached=(?P<reached>\w+) "
    + _f("targetY") + r" " + _f("x") + r" " + _f("z")
    + r" vel=\((?P<vx>-?\d+\.?\d*),(?P<vz>-?\d+\.?\d*)\)"
    r" sneak=(?P<sneak>\w+) jump=(?P<jump>\w+) " + _f("fwd")
)
# Fields added 2026-08-06; absent in older logs, so they are matched separately and default to None.
STR_RE = re.compile(_f("str"))
SRC_RE = re.compile(r"src=(\S+)")
YAW_RE = re.compile(_f("yaw"))
HCOL_RE = re.compile(r"hcol=(\w+)")

PLAN_RE = re.compile(
    r"PLAN (?P<move>\w+) from-floor=\((?P<ffx>-?\d+),(?P<ffy>-?\d+),(?P<ffz>-?\d+)\) "
    r"to-floor=\((?P<tfx>-?\d+),(?P<tfy>-?\d+),(?P<tfz>-?\d+)\)"
)
ENTRY_RE = re.compile(
    r"offCentre=\((?P<ox>-?\d+\.?\d*),(?P<oz>-?\d+\.?\d*)\) "
    r"vel=\((?P<vx>-?\d+\.?\d*),(?P<vz>-?\d+\.?\d*)\) " + _f("speed")
)
FAIL_RE = re.compile(r"step FAILED \(validity envelope\) (?P<move>\w+) step (?P<step>\d+) phase (?P<phase>\d+)/(?P<phases>\d+)")
RESEARCH_RE = re.compile(r"block re-search: site=(?P<site>\S+) reason=(?P<reason>\S+) wpIdx=(?P<wp>\d+)/(?P<size>\d+)")


def _open(path):
    if path.endswith(".gz"):
        return io.TextIOWrapper(gzip.open(path, "rb"), errors="replace")
    return open(path, "r", errors="replace")


def parse(path):
    """Return (ticks, plans, fails, researches). `ticks` are dicts in file order."""
    ticks, plans, fails, researches = [], [], [], []
    with _open(path) as fh:
        for lineno, line in enumerate(fh, 1):
            m = EXEC_RE.search(line)
            if m:
                d = m.groupdict()
                rec = {k: d[k] for k in d}
                for k in ("botY", "targetY", "x", "z", "vx", "vz", "fwd"):
                    rec[k] = float(rec[k])
                for k in ("wp", "phase", "phases", "fx", "fy", "fz", "tx", "ty", "tz"):
                    rec[k] = int(rec[k])
                for k in ("grounded", "climbable", "reached", "sneak", "jump"):
                    rec[k] = rec[k] == "true"
                s = STR_RE.search(line)
                rec["str"] = float(s.group(1)) if s else None
                s = SRC_RE.search(line)
                rec["src"] = s.group(1) if s else None
                s = YAW_RE.search(line)
                rec["yaw"] = float(s.group(1)) if s else None
                s = HCOL_RE.search(line)
                rec["hcol"] = (s.group(1) == "true") if s else None
                rec["line"] = lineno
                ticks.append(rec)
                continue
            m = PLAN_RE.search(line)
            if m:
                d = m.groupdict()
                e = ENTRY_RE.search(line)
                plans.append({
                    "move": d["move"], "line": lineno,
                    "from": (int(d["ffx"]), int(d["ffy"]), int(d["ffz"])),
                    "to": (int(d["tfx"]), int(d["tfy"]), int(d["tfz"])),
                    "off": (float(e.group("ox")), float(e.group("oz"))) if e else None,
                    "vel": (float(e.group("vx")), float(e.group("vz"))) if e else None,
                    "speed": float(e.group("speed")) if e else None,
                })
                continue
            m = FAIL_RE.search(line)
            if m:
                fails.append({"move": m.group("move"), "step": int(m.group("step")), "line": lineno})
                continue
            m = RESEARCH_RE.search(line)
            if m:
                researches.append({"site": m.group("site"), "reason": m.group("reason"),
                                   "wp": int(m.group("wp")), "line": lineno})
    return ticks, plans, fails, researches


def group_steps(ticks):
    """Split the tick stream into consecutive runs of the same (wp, move) — one entry per executed step.

    Keyed on the RUN, not on wp alone: a mid-step re-search resets the waypoint cursor (wp3 -> wp0 on the
    same move), and collapsing those would hide exactly the discontinuity worth seeing.
    """
    steps = []
    for t in ticks:
        if steps and steps[-1]["wp"] == t["wp"] and steps[-1]["move"] == t["move"]:
            steps[-1]["ticks"].append(t)
        else:
            steps.append({"wp": t["wp"], "move": t["move"], "ticks": [t]})
    return steps


# ---- reporting -----------------------------------------------------------------------------------

def off_centre(t):
    """Signed offset from the bot's own foot-cell centre — the 'did we start centred' number."""
    return t["x"] - (t["fx"] + 0.5), t["z"] - (t["fz"] + 0.5)


def speed(t):
    return (t["vx"] ** 2 + t["vz"] ** 2) ** 0.5


def summarize(steps, fails, researches, move_filter=None):
    fail_lines = {f["line"] for f in fails}
    res_by_line = {r["line"]: r for r in researches}
    print(f"{'#':>3} {'wp':>3} {'move':<18} {'ticks':>5}  "
          f"{'entry offCentre':>17} {'spd':>6}   {'exit offCentre':>17} {'spd':>6}  notes")
    print("-" * 108)
    for i, s in enumerate(steps):
        if move_filter and move_filter.lower() not in s["move"].lower():
            continue
        a, b = s["ticks"][0], s["ticks"][-1]
        ao, bo = off_centre(a), off_centre(b)
        notes = []
        # A failure/re-search is attributed to the step whose tick span brackets its log line.
        for ln in fail_lines:
            if a["line"] <= ln <= b["line"] + 3:
                notes.append("FAILED")
        for ln, r in res_by_line.items():
            if a["line"] <= ln <= b["line"] + 3:
                notes.append(f"re-search({r['reason']})")
        print(f"{i:>3} {s['wp']:>3} {s['move']:<18} {len(s['ticks']):>5}  "
              f"({ao[0]:+.3f},{ao[1]:+.3f}) {speed(a):>6.4f}   "
              f"({bo[0]:+.3f},{bo[1]:+.3f}) {speed(b):>6.4f}  {' '.join(notes)}")


def dump(step):
    a = step["ticks"][0]
    print(f"step wp{step['wp']} {step['move']} -> ({a['tx']},{a['ty']},{a['tz']})  "
          f"{len(step['ticks'])} ticks, log lines {a['line']}..{step['ticks'][-1]['line']}")
    print(f"{'t':>3} {'ph':>4} {'x':>9} {'y':>9} {'z':>9} {'dx':>7} {'dz':>7} "
          f"{'vx':>8} {'vz':>8} {'fwd':>6} {'str':>6} {'src':<14} {'yaw':>7} gnd rch")
    print("-" * 126)
    prev = None
    for n, t in enumerate(step["ticks"]):
        dx = t["x"] - prev["x"] if prev else 0.0
        dz = t["z"] - prev["z"] if prev else 0.0
        st = f"{t['str']:>6.2f}" if t["str"] is not None else "     -"
        yw = f"{t['yaw']:>7.1f}" if t["yaw"] is not None else "      -"
        print(f"{n:>3} {t['phase']:>2}/{t['phases']:<1} {t['x']:>9.3f} {t['botY']:>9.3f} {t['z']:>9.3f} "
              f"{dx:>+7.3f} {dz:>+7.3f} {t['vx']:>+8.4f} {t['vz']:>+8.4f} {t['fwd']:>6.2f} {st} "
              f"{(t['src'] or '-'):<14} {yw} {'Y' if t['grounded'] else '.'}   {'Y' if t['reached'] else '.'}")
        prev = t
    # Displacement per tick is the honest check on a servo: an input that commands motion and produces
    # none is a press into geometry, and only the (fwd, dx/dz) pair shows it.
    total_dx = step["ticks"][-1]["x"] - step["ticks"][0]["x"]
    total_dz = step["ticks"][-1]["z"] - step["ticks"][0]["z"]
    print(f"\nnet displacement: dx={total_dx:+.3f} dz={total_dz:+.3f}")
    if step["ticks"][0]["src"] is None:
        print("note: this log predates the str=/src= fields — re-run to get input authorship.")


def diff(steps_a, steps_b, label_a, label_b):
    print(f"comparing {label_a} (A) vs {label_b} (B) — aligned by step index\n")
    print(f"{'#':>3} {'move':<18} {'A entry offCentre':>19} {'A spd':>7}  "
          f"{'B entry offCentre':>19} {'B spd':>7}  {'drift':>7}")
    print("-" * 92)
    for i in range(max(len(steps_a), len(steps_b))):
        sa = steps_a[i] if i < len(steps_a) else None
        sb = steps_b[i] if i < len(steps_b) else None
        if sa is None or sb is None:
            name = (sa or sb)["move"]
            print(f"{i:>3} {name:<18} {'(missing in B)' if sb is None else '(missing in A)':>19}")
            continue
        if sa["move"] != sb["move"]:
            print(f"{i:>3} {sa['move']:<18} ROUTE DIVERGES — B has {sb['move']}")
            break
        ao, bo = off_centre(sa["ticks"][0]), off_centre(sb["ticks"][0])
        d = ((ao[0] - bo[0]) ** 2 + (ao[1] - bo[1]) ** 2) ** 0.5
        print(f"{i:>3} {sa['move']:<18} ({ao[0]:+.3f},{ao[1]:+.3f}) {speed(sa['ticks'][0]):>7.4f}  "
              f"({bo[0]:+.3f},{bo[1]:+.3f}) {speed(sb['ticks'][0]):>7.4f}  {d:>7.3f}")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("log", help="latest.log or an archived .log.gz")
    ap.add_argument("--step", type=int, help="per-tick dump of this step INDEX (the '#' column)")
    ap.add_argument("--wp", type=int, help="per-tick dump of the step(s) with this waypoint index")
    ap.add_argument("--move", help="filter the summary to moves whose name contains this")
    ap.add_argument("--fail", action="store_true", help="per-tick dump of the step that failed")
    ap.add_argument("--diff", metavar="OTHER", help="compare against another run's log")
    args = ap.parse_args()

    ticks, plans, fails, researches = parse(args.log)
    if not ticks:
        print("no exec lines found — was /bot debug verbose on for this run?", file=sys.stderr)
        return 1
    steps = group_steps(ticks)

    if args.diff:
        ticks_b, _, _, _ = parse(args.diff)
        diff(steps, group_steps(ticks_b), args.log, args.diff)
        return 0

    if args.fail:
        if not fails:
            print("no validity-envelope failure in this log.")
            return 0
        for f in fails:
            for s in steps:
                if s["ticks"][0]["line"] <= f["line"] <= s["ticks"][-1]["line"] + 3:
                    print(f"=== FAILED {f['move']} step {f['step']} (log line {f['line']}) ===")
                    dump(s)
                    print()
        return 0

    if args.step is not None:
        dump(steps[args.step])
        return 0
    if args.wp is not None:
        for s in steps:
            if s["wp"] == args.wp:
                dump(s)
                print()
        return 0

    summarize(steps, fails, researches, args.move)
    if researches:
        print(f"\n{len(researches)} block re-search(es):")
        for r in researches:
            print(f"  line {r['line']:>6}  site={r['site']:<16} reason={r['reason']:<16} wpIdx={r['wp']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
