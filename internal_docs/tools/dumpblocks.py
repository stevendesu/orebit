"""Dump every non-air block in a world box as `x y z block` lines, for rebuilding a fixture by hand.

Usage:  python internal_docs/tools/dumpblocks.py "<world dir>" x0 x1 y0 y1 z0 z1 [out.txt]

Companion to slices.py (which renders the same box as a picture). This one emits raw coordinates plus a
setblock-per-line block so a box can be reproduced exactly in a creative world. Reads the SAVE, not the
fixture's builder, so what it prints is what the planner actually saw.
"""
import sys
from peek import get_chunk, section_blocks

AIR = ('air', 'cave_air', 'void_air')


def load(world, x0, x1, y0, y1, z0, z1):
    cache, out = {}, {}
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            cx, cz = x >> 4, z >> 4
            if (cx, cz) not in cache:
                rp = '%s/region/r.%d.%d.mca' % (world, cx >> 5, cz >> 5)
                try:
                    cache[(cx, cz)] = get_chunk(rp, cx, cz)
                except Exception:
                    cache[(cx, cz)] = None
            ch = cache[(cx, cz)]
            secs = (ch.get('sections') or ch.get('Sections') or []) if ch else []
            by_y = {}
            for s in secs:
                sb = section_blocks(s)
                if sb:
                    by_y[s.get('Y')] = sb
            for y in range(y0, y1 + 1):
                name = '??'
                sb = by_y.get(y >> 4)
                if sb:
                    pal, idx = sb
                    name = pal[idx[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)]].replace('minecraft:', '')
                out[(x, y, z)] = name
    return out


def main():
    world = sys.argv[1]
    x0, x1, y0, y1, z0, z1 = (int(v) for v in sys.argv[2:8])
    dest = sys.argv[8] if len(sys.argv) > 8 else None
    blocks = load(world, x0, x1, y0, y1, z0, z1)

    lines, counts, air = [], {}, 0
    for y in range(y0, y1 + 1):
        for x in range(x0, x1 + 1):
            for z in range(z0, z1 + 1):
                name = blocks[(x, y, z)]
                base = name.split('[')[0]
                if base in AIR:
                    air += 1
                    continue
                counts[base] = counts.get(base, 0) + 1
                lines.append('%d %d %d %s' % (x, y, z, name))

    hdr = [
        '# box x=[%d..%d] y=[%d..%d] z=[%d..%d]  (%d cells: %d non-air, %d air)'
        % (x0, x1, y0, y1, z0, z1, (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1), len(lines), air),
        '# tally: ' + ', '.join('%s=%d' % kv for kv in sorted(counts.items())),
        '# format: x y z block   (air omitted; ordered y, then x, then z)',
        '#',
        '# To rebuild in creative, paste the setblock section at the bottom, or use the coordinates',
        '# directly. Coordinates are ABSOLUTE, exactly as the fixture built them.',
        '',
    ]
    out = '\n'.join(hdr + lines)
    out += '\n\n# ---- setblock commands (absolute coords) ----\n'
    out += '\n'.join('setblock ' + l for l in lines)
    out += '\n'

    if dest:
        with open(dest, 'w', encoding='utf-8') as f:
            f.write(out)
        print('wrote %s (%d non-air blocks)' % (dest, len(lines)))
        print(hdr[1])
    else:
        print(out)


if __name__ == '__main__':
    main()
