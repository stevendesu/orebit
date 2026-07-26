#!/usr/bin/env python3
"""Minimal Minecraft RCON client: connect, auth, run a list of commands from a file (one per line).
Usage: python rcon.py <host> <port> <password> <commands_file> [--wait SECONDS]
Blank lines and lines starting with # are skipped. Prints each command's response.
Retries the initial connect for --wait seconds (server may still be booting)."""
import socket, struct, sys, time

def _pkt(req_id, ptype, body):
    data = struct.pack('<ii', req_id, ptype) + body.encode('utf-8') + b'\x00\x00'
    return struct.pack('<i', len(data)) + data

def _recv(sock):
    raw_len = b''
    while len(raw_len) < 4:
        chunk = sock.recv(4 - len(raw_len))
        if not chunk: raise EOFError('socket closed')
        raw_len += chunk
    (length,) = struct.unpack('<i', raw_len)
    data = b''
    while len(data) < length:
        chunk = sock.recv(length - len(data))
        if not chunk: raise EOFError('socket closed')
        data += chunk
    req_id, ptype = struct.unpack('<ii', data[:8])
    body = data[8:-2].decode('utf-8', 'replace')
    return req_id, ptype, body

def main():
    host, port, password, cmdfile = sys.argv[1], int(sys.argv[2]), sys.argv[3], sys.argv[4]
    wait = 60.0
    if '--wait' in sys.argv:
        wait = float(sys.argv[sys.argv.index('--wait') + 1])
    # connect with retry
    deadline = time.time() + wait
    sock = None
    while time.time() < deadline:
        try:
            sock = socket.create_connection((host, port), timeout=5)
            break
        except OSError:
            time.sleep(2)
    if sock is None:
        print('ERROR: could not connect to RCON', file=sys.stderr); sys.exit(2)
    sock.settimeout(30)
    # auth
    sock.sendall(_pkt(1, 3, password))
    rid, ptype, body = _recv(sock)
    if rid == -1:
        print('ERROR: RCON auth failed', file=sys.stderr); sys.exit(3)
    print('[rcon] authenticated')
    with open(cmdfile, 'r', encoding='utf-8') as f:
        cmds = [ln.rstrip('\n') for ln in f]
    n = 0
    for cmd in cmds:
        c = cmd.strip()
        if not c or c.startswith('#'):
            continue
        n += 1
        sock.sendall(_pkt(2, 2, c))
        rid, ptype, body = _recv(sock)
        body = body.strip()
        # keep output terse for the huge fill loops
        if body:
            print(f'[{n}] {c[:70]} -> {body[:120]}')
        else:
            print(f'[{n}] {c[:70]} -> (ok)')
    sock.close()
    print(f'[rcon] done ({n} commands)')

if __name__ == '__main__':
    main()
