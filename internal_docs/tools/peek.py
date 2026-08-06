"""Minimal Anvil (1.18+) block reader: dump a box of block ids from a world save."""
import sys, zlib, struct, io

# ---- NBT ----
def rd(f, n):
    b = f.read(n)
    assert len(b) == n
    return b

def read_tag(f, tid):
    if tid == 0: return None
    if tid == 1: return struct.unpack('>b', rd(f,1))[0]
    if tid == 2: return struct.unpack('>h', rd(f,2))[0]
    if tid == 3: return struct.unpack('>i', rd(f,4))[0]
    if tid == 4: return struct.unpack('>q', rd(f,8))[0]
    if tid == 5: return struct.unpack('>f', rd(f,4))[0]
    if tid == 6: return struct.unpack('>d', rd(f,8))[0]
    if tid == 7:
        n = struct.unpack('>i', rd(f,4))[0]; return rd(f, n)
    if tid == 8:
        n = struct.unpack('>H', rd(f,2))[0]; return rd(f, n).decode('utf8', 'replace')
    if tid == 9:
        it = struct.unpack('>b', rd(f,1))[0]; n = struct.unpack('>i', rd(f,4))[0]
        return [read_tag(f, it) for _ in range(n)]
    if tid == 10:
        out = {}
        while True:
            t = struct.unpack('>b', rd(f,1))[0]
            if t == 0: return out
            ln = struct.unpack('>H', rd(f,2))[0]
            name = rd(f, ln).decode('utf8', 'replace')
            out[name] = read_tag(f, t)
    if tid == 11:
        n = struct.unpack('>i', rd(f,4))[0]
        return list(struct.unpack('>%di' % n, rd(f, 4*n)))
    if tid == 12:
        n = struct.unpack('>i', rd(f,4))[0]
        return list(struct.unpack('>%dq' % n, rd(f, 8*n)))
    raise ValueError('tag %d' % tid)

def parse_nbt(data):
    f = io.BytesIO(data)
    t = struct.unpack('>b', rd(f,1))[0]
    ln = struct.unpack('>H', rd(f,2))[0]
    rd(f, ln)
    return read_tag(f, t)

# ---- region ----
def get_chunk(path, cx, cz):
    with open(path, 'rb') as f:
        hdr = f.read(4096)
        i = ((cx & 31) + (cz & 31) * 32) * 4
        off = (hdr[i] << 16 | hdr[i+1] << 8 | hdr[i+2]) * 4096
        cnt = hdr[i+3]
        if off == 0: return None
        f.seek(off)
        length = struct.unpack('>i', f.read(4))[0]
        comp = f.read(1)[0]
        raw = f.read(length - 1)
    if comp == 2: raw = zlib.decompress(raw)
    elif comp == 1: raw = zlib.decompress(raw, 47)
    return parse_nbt(raw)

def section_blocks(sec):
    """-> (palette list of names, 4096 index array) or None."""
    bs = sec.get('block_states')
    if bs is None: return None
    pal = [p['Name'] for p in bs.get('palette', [])]
    if not pal: return None
    data = bs.get('data')
    if data is None:
        return pal, [0]*4096
    bits = max(4, (len(pal)-1).bit_length())
    per = 64 // bits
    mask = (1 << bits) - 1
    out = []
    for word in data:
        w = word & 0xFFFFFFFFFFFFFFFF
        for k in range(per):
            if len(out) >= 4096: break
            out.append((w >> (k*bits)) & mask)
    return pal, out

def dump(world, x0, x1, y0, y1, z0, z1):
    cache = {}
    for y in range(y1, y0-1, -1):
        row = []
        for z in range(z0, z1+1):
            for x in range(x0, x1+1):
                cx, cz = x >> 4, z >> 4
                key = (cx, cz)
                if key not in cache:
                    rp = '%s/region/r.%d.%d.mca' % (world, cx >> 5, cz >> 5)
                    try: cache[key] = get_chunk(rp, cx, cz)
                    except Exception as e: cache[key] = None
                ch = cache[key]
                name = '??'
                if ch:
                    secs = ch.get('sections') or ch.get('Sections') or []
                    for s in secs:
                        if s.get('Y') == (y >> 4):
                            sb = section_blocks(s)
                            if sb:
                                pal, idx = sb
                                name = pal[idx[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)]]
                            break
                row.append('(%d,%d,%d)=%s' % (x, y, z, name.replace('minecraft:', '')))
        print('  '.join(row))

if __name__ == '__main__':
    w = sys.argv[1]
    a = list(map(int, sys.argv[2:8]))
    dump(w, *a)
