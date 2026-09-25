#!/usr/bin/env python3
"""Extract the 20 golden vectors of docs/PROTOCOL.md section 18 mechanically.

Every vector is taken from the hex block; every field expectation is taken from
the prose bullet list underneath it; the two are then CROSS-CHECKED against the
field tables of section 6.  Any disagreement is a hard error, because it means
either the spec or this extractor is wrong -- never something to paper over.
"""
import re, sys, os

SPEC = sys.argv[1]
OUT  = sys.argv[2]

src = open(SPEC, encoding='utf-8').read()
sec18 = src.split('## 18. Golden test vectors', 1)[1].split('## 19.', 1)[0]

HEAD = re.compile(r'^### V(\d+)\. `([a-z0-9_]+)` — (\d+) bytes\s*$', re.M)

heads = list(HEAD.finditer(sec18))
if len(heads) != 20:
    sys.exit('expected 20 vector headings, found %d' % len(heads))

def hchk(b):
    s = sum((i + 1) * b[i] for i in range(7)) & 0xFF
    return 0xFF ^ s

def u16(b, o): return b[o] | (b[o+1] << 8)
def u32(b, o): return b[o] | (b[o+1] << 8) | (b[o+2] << 16) | (b[o+3] << 24)

def cstr(b, o, w):
    f = b[o:o+w]
    if len(f) != w: sys.exit('short string field')
    if f[-1] != 0:  sys.exit('string field not NUL-terminated')
    n = f.index(0)
    return f[:n].decode('utf-8')

BASE = {0x01: 60, 0x02: 4, 0x03: 4, 0x04: 4,
        0x20: 24, 0x21: 168, 0x22: 32, 0x23: 4, 0x24: 4, 0x25: 32}

vectors = []
for i, m in enumerate(heads):
    idx  = int(m.group(1))
    name = m.group(2)
    size = int(m.group(3))
    if idx != i + 1: sys.exit('vector numbering gap at %s' % name)
    body = sec18[m.end(): heads[i+1].start() if i + 1 < len(heads) else len(sec18)]

    blocks = re.findall(r'```\n(.*?)```', body, re.S)
    if not blocks: sys.exit('no hex block for %s' % name)
    hexs = blocks[0].split()
    if not all(re.fullmatch(r'[0-9a-f]{2}', h) for h in hexs):
        sys.exit('non-hex token in %s' % name)
    frame = bytes(int(h, 16) for h in hexs)
    if len(frame) != size:
        sys.exit('%s: heading says %d bytes, block holds %d' % (name, size, len(frame)))

    # --- structural validation against sections 3 and 4.2 -------------------
    if frame[0] != 0xa7 or frame[1] != 0x53: sys.exit('%s: bad magic' % name)
    if frame[2] != 0x03: sys.exit('%s: version != 3' % name)
    if frame[3] == 0x00: sys.exit('%s: type 0' % name)
    length = u16(frame, 4)
    if length > 248: sys.exit('%s: length %d > 248' % (name, length))
    if frame[6] & 0xfe: sys.exit('%s: reserved flag bit set' % name)
    if frame[7] != hchk(frame):
        sys.exit('%s: hchk is %02x, section 3.1 computes %02x' % (name, frame[7], hchk(frame)))
    if len(frame) != 8 + length:
        sys.exit('%s: frame is %d bytes, header.length says %d' % (name, len(frame), 8 + length))
    mtype = frame[3]
    if mtype not in BASE: sys.exit('%s: unknown type 0x%02x' % (name, mtype))
    if length != BASE[mtype]:
        sys.exit('%s: length %d != base %d for type 0x%02x' % (name, length, BASE[mtype], mtype))

    p = frame[8:]

    # --- fields decoded from the bytes, per the section 6 tables ------------
    if mtype == 0x01:
        f = dict(last_seq=u32(p,0), caps=u32(p,4), rx_max=u16(p,8), reserved0=u16(p,10),
                 device_id=cstr(p,12,32), fw_version=cstr(p,44,16))
    elif mtype == 0x20:
        f = dict(latest_seq=u32(p,0), server_time=u32(p,4), session_id=u32(p,8),
                 max_frame=u16(p,12), ping_interval_s=u16(p,14), idle_timeout_s=u16(p,16),
                 replay_window=u16(p,18), caps=u32(p,20))
    elif mtype == 0x21:
        f = dict(seq=u32(p,0), ts=u32(p,4), value=u32(p,8),
                 months=u16(p,16), ttl_ds=u16(p,18), kind=p[20], tier=p[21],
                 eflags=p[22], reserved2=p[23], actor=cstr(p,24,48), text=cstr(p,72,96))
        if p[12:16] != b'\0\0\0\0': sys.exit('%s: reserved1 not zero' % name)
        if p[23] != 0:              sys.exit('%s: reserved2 not zero' % name)
        if f['seq'] == 0:           sys.exit('%s: EVENT seq 0 is illegal' % name)
        if f['tier'] > 4:           sys.exit('%s: tier > 4' % name)
        if f['kind'] == 0x18:       sys.exit('%s: kind 0x18 is permanently reserved' % name)
    elif mtype == 0x22:
        f = dict(viewers=u32(p,0), msg_total=u32(p,4), uptime_s=u32(p,8), followers=u32(p,12),
                 subs=u32(p,16), server_time=u32(p,20), stream_started_at=u32(p,24),
                 chat_rate=u16(p,28), live=p[30], sflags=p[31])
        if p[31] != 0: sys.exit('%s: sflags not zero' % name)
    elif mtype == 0x25:
        f = dict(code=u16(p,0), detail=u16(p,2), retry_after_s=u16(p,4),
                 reserved0=u16(p,6), reason=cstr(p,8,24))
    else:
        f = dict(token=u32(p,0))
        f['seq'] = f['token']          # ACK names the same four bytes `seq`

    # --- expectations parsed out of the prose bullets, then compared --------
    prose = {}
    for line in body.splitlines():
        bm = re.match(r'^- `([A-Za-z0-9_.]+)` = (.*)$', line.strip())
        if not bm: continue
        key, rest = bm.group(1), bm.group(2).strip()
        if key.startswith('header.'):
            key = 'H' + key[7:]
        if key == 'reserved1':
            prose[key] = rest.split('—')[0].strip()
            continue
        qm = re.match(r'^"(.*?)"', rest)
        if qm:
            prose[key] = qm.group(1); continue
        if rest.startswith('empty'):
            prose[key] = ''; continue
        tm = re.match(r'^\d+ bytes: `(.*?)`', rest)
        if tm:
            prose[key] = tm.group(1); continue
        if rest.startswith('a7 53'):
            prose[key] = rest.split('—')[0].strip(); continue
        nm = re.match(r'^(0x[0-9a-fA-F]+|\d+)\b', rest)
        if nm:
            prose[key] = int(nm.group(1), 0); continue

    checked = 0
    def cmp(key, got):
        global checked
        if key not in prose: return
        want = prose[key]
        if want != got:
            sys.exit('%s: section 18 prose says %s = %r, its own bytes decode to %r'
                     % (name, key, want, got))
        checked += 1

    cmp('Htype', mtype); cmp('Hlength', length); cmp('Hflags', frame[6])
    for k, v in f.items():
        cmp(k, v)
    if 'reserved1' in prose and prose['reserved1'] != '00 00 00 00':
        sys.exit('%s: reserved1 prose is %r' % (name, prose['reserved1']))

    vectors.append(dict(idx=idx, name=name, size=size, frame=frame, type=mtype,
                        length=length, flags=frame[6], fields=f, checked=checked))

# ---------------------------------------------------------------------------
# Emit vectors.h
# ---------------------------------------------------------------------------
def ident(n): return n.upper()

def carr(b, indent='    '):
    out = []
    for i in range(0, len(b), 16):
        out.append(indent + ' '.join('0x%02x,' % x for x in b[i:i+16]))
    return '\n'.join(out)

def cstrarr(s):
    # Explicit (char) casts: plain char is signed on the ESP32 and on x86, so a
    # UTF-8 continuation byte would otherwise be a narrowing conversion error.
    b = list(s.encode('utf-8')) + [0]
    parts, line, out = [], '    ', []
    for x in b:
        tok = '(char)0x%02x,' % x
        if len(line) + len(tok) > 96:
            out.append(line.rstrip())
            line = '    '
        line += tok + ' '
    out.append(line.rstrip())
    return '{\n' + '\n'.join(out) + '\n}'

L = []
L.append('#pragma once')
L.append('//')
L.append('// TSB/3 golden test vectors — docs/PROTOCOL.md section 18, all twenty of them.')
L.append('//')
L.append('// GENERATED FILE — DO NOT HAND-EDIT.')
L.append('//')
L.append('// Produced by test/test_proto_codec/gen_vectors.py, which reads the')
L.append('// specification, takes the bytes from each hex block, re-derives every field')
L.append('// from the section 6 offset')
L.append('// tables, and cross-checks that against the prose bullet list printed under the')
L.append('// block. The generator also re-validates magic, version, type, length, the')
L.append('// reserved flag bits and the section 3.1 header check on every frame, and')
L.append('// refuses to emit anything if one disagrees. If a vector here looks wrong, the')
L.append('// specification is what gets fixed, and this file is regenerated.')
L.append('//')
L.append('// There is no donation vector and no monetary field anywhere below: section 8.')
L.append('// EVENT payload bytes +12..+15 and +23 are reserved1/reserved2, zero on the wire')
L.append('// and MUST-IGNORE on receipt.')
L.append('//')
L.append('#include <stdint.h>')
L.append('#include <stddef.h>')
L.append('')
L.append('namespace gv {')
L.append('')

for v in vectors:
    L.append('// V%d. %s — %d bytes' % (v['idx'], v['name'], v['size']))
    L.append('static const uint8_t %s[%d] = {' % (ident(v['name']), v['size']))
    L.append(carr(v['frame']))
    L.append('};')
    L.append('')

# string payloads
for v in vectors:
    for k, val in sorted(v['fields'].items()):
        if isinstance(val, str):
            L.append('static const char %s_%s[] = %s;' % (ident(v['name']), k.upper(), cstrarr(val)))
L.append('')

L.append('// --------------------------------------------------------------------------')
L.append('// Typed expectation tables. One row per vector; `frame`/`size` are the golden')
L.append('// bytes, the remaining members are the fields the specification says those')
L.append('// bytes carry.')
L.append('// --------------------------------------------------------------------------')
L.append('')

tables = [
 ('Hello', 0x01, 'ExpHello', [('uint32_t','last_seq'),('uint32_t','caps'),('uint16_t','rx_max'),
                              ('uint16_t','reserved0'),('const char *','device_id'),('const char *','fw_version')]),
 ('Welcome', 0x20, 'ExpWelcome', [('uint32_t','latest_seq'),('uint32_t','server_time'),('uint32_t','session_id'),
                                  ('uint16_t','max_frame'),('uint16_t','ping_interval_s'),('uint16_t','idle_timeout_s'),
                                  ('uint16_t','replay_window'),('uint32_t','caps')]),
 ('Event', 0x21, 'ExpEvent', [('uint32_t','seq'),('uint32_t','ts'),('uint32_t','value'),('uint16_t','months'),
                              ('uint16_t','ttl_ds'),('uint8_t','kind'),('uint8_t','tier'),('uint8_t','eflags'),
                              ('uint8_t','reserved2'),('const char *','actor'),('const char *','text')]),
 ('Stats', 0x22, 'ExpStats', [('uint32_t','viewers'),('uint32_t','msg_total'),('uint32_t','uptime_s'),
                              ('uint32_t','followers'),('uint32_t','subs'),('uint32_t','server_time'),
                              ('uint32_t','stream_started_at'),('uint16_t','chat_rate'),('uint8_t','live'),
                              ('uint8_t','sflags')]),
 ('Bye', 0x25, 'ExpBye', [('uint16_t','code'),('uint16_t','detail'),('uint16_t','retry_after_s'),
                          ('uint16_t','reserved0'),('const char *','reason')]),
 ('Token', None, 'ExpToken', [('uint32_t','token')]),
]

TOKEN_TYPES = {0x02, 0x03, 0x04, 0x23, 0x24}

for label, mtype, sname, members in tables:
    rows = [v for v in vectors
            if (v['type'] in TOKEN_TYPES if mtype is None else v['type'] == mtype)]
    L.append('struct %s {' % sname)
    L.append('  const char *name;')
    L.append('  const uint8_t *frame;')
    L.append('  uint16_t size;')
    L.append('  uint8_t type;')
    L.append('  uint16_t length;')
    L.append('  uint8_t flags;')
    for t, n in members:
        L.append('  %s%s;' % (t + (' ' if t.endswith('*') else ' '), n))
    L.append('};')
    L.append('')
    L.append('static const %s %s_VECTORS[] = {' % (sname, label.upper()))
    for v in rows:
        vals = []
        for t, n in members:
            val = v['fields'][n]
            vals.append('%s_%s' % (ident(v['name']), n.upper()) if isinstance(val, str)
                        else ('0x%08xu' % val if t == 'uint32_t' else str(val)))
        L.append('  { "V%d %s", %s, %d, 0x%02x, %d, 0x%02x, %s },'
                 % (v['idx'], v['name'], ident(v['name']), v['size'], v['type'],
                    v['length'], v['flags'], ', '.join(vals)))
    L.append('};')
    L.append('static const size_t %s_COUNT = sizeof(%s_VECTORS) / sizeof(%s_VECTORS[0]);'
             % (label.upper(), label.upper(), label.upper()))
    L.append('')

L.append('// Every vector, in specification order, for the whole-corpus sweeps.')
L.append('struct ExpFrame { const char *name; const uint8_t *frame; uint16_t size;')
L.append('                  uint8_t type; uint16_t length; uint8_t flags; };')
L.append('')
L.append('static const ExpFrame ALL_VECTORS[] = {')
for v in vectors:
    L.append('  { "V%d %s", %s, %d, 0x%02x, %d, 0x%02x },'
             % (v['idx'], v['name'], ident(v['name']), v['size'], v['type'], v['length'], v['flags']))
L.append('};')
L.append('static const size_t ALL_COUNT = sizeof(ALL_VECTORS) / sizeof(ALL_VECTORS[0]);')
L.append('')
L.append('}  // namespace gv')
L.append('')

open(OUT, 'w', encoding='utf-8').write('\n'.join(L))

tot = sum(v['checked'] for v in vectors)
print('extracted %d vectors, %d bytes of frame data' % (len(vectors), sum(v['size'] for v in vectors)))
print('cross-checked %d prose field assertions against the decoded bytes' % tot)
for v in vectors:
    print('  V%-2d %-22s type 0x%02x len %3d flags 0x%02x  %2d prose fields agreed'
          % (v['idx'], v['name'], v['type'], v['length'], v['flags'], v['checked']))
