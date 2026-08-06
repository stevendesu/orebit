"""Render a world box as per-X slices (y rows x z columns), with a legend."""
import sys
from peek import get_chunk, section_blocks

SYM = {
    'air': '.', 'cave_air': '.', 'void_air': '.',
    'vine': 'V', 'jungle_leaves': 'L', 'jungle_log': 'W', 'jungle_wood': 'W',
    'water': '~', 'lava': '!', 'stone': '#', 'dirt': 'd', 'grass_block': 'g',
    'pointed_dripstone': 'i', 'dripstone_block': 'D', 'cocoa': 'c',
}

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
    x0, x1, y0, y1, z0, z1 = map(int, sys.argv[2:8])
    g = load(world, x0, x1, y0, y1, z0, z1)

    seen = {}
    for v in g.values():
        seen[v] = SYM.get(v, '?')
    unknown = sorted(n for n in seen if n not in SYM)
    for i, n in enumerate(unknown):
        seen[n] = chr(ord('a') + i) if i < 26 else '?'

    zs = list(range(z1, z0 - 1, -1))          # north (low z) to the right
    for x in range(x0, x1 + 1):
        print('  x=%d      z:  %s' % (x, ' '.join('%3d' % z for z in zs)))
        for y in range(y1, y0 - 1, -1):
            row = ' '.join('  %s' % seen[g[(x, y, z)]] for z in zs)
            print('    y=%-4d       %s' % (y, row))
        print()
    print('  legend: ' + '  '.join('%s=%s' % (seen[n], n) for n in sorted(seen)))

main()
