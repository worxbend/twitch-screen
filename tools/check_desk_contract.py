#!/usr/bin/env python3
"""Independent DESK/1 fixture and strict HTTP contract checker (stdlib only).

This does not import peer codecs or claim production route/device conformance.
JSON Schema custom keywords express UTF-16/text requirements that OpenAPI cannot.
"""
from datetime import datetime
from decimal import Decimal, ROUND_HALF_UP
import hashlib
import json
import math
from pathlib import Path
import re
import sys
import unicodedata

ROOT = Path(__file__).resolve().parents[1]
PROTOCOL = ROOT / 'protocol'
# Independently transcribed scalar widths; no use of layout JSON to choose widths.
TYPES = {
    'HELLO': (1, 92, '4 4 2 2 b32 b16 2 2 8 4 8 8'),
    'WELCOME': (32, 60, '4 4 4 2 2 2 2 4 2 2 8 8 8 8'),
    'STATS': (34, 60, '4 4 4 4 4 4 4 2 1 1 8 8 4 4 1 b3'),
    'TIME_REQUEST': (5, 8, '8'),
    'DEVICE_REPORT': (6, 244, '8 8 8 4 8 8 8 4 4 4 4 4 4 4 1 1 1 1 4 4 4 4 ' + '8 4 1 1 2 ' * 9),
    'TIME_RESPONSE': (38, 32, '8 8 8 8'),
    'DISPLAY_STATE': (39, 32, '8 8 4 4 4 1 1 2'),
    'WEATHER': (40, 68, '8 8 4 8 1 1 1 1 -2 1 1 b32'),
    'CPU': (41, 68, '8 8 4 8 1 1 2 2 -2 b32'),
    'SYNC_END': (42, 40, '8 8 4 4 4 4 4 4'),
    'DESK_EVENT': (43, 196, '4 4 4 b4 2 2 1 1 1 1 b48 b96 8 4 4 8 1 b3'),
    'PROGRESS_BARRIER': (44, 20, '8 8 4'),
}


def load(name):
    return strict_json((PROTOCOL / name).read_bytes(), max_bytes=2_000_000)


def strict_json(raw, max_bytes=65536):
    """Reject duplicates, non-finite constants, invalid UTF-8 and oversized input."""
    if isinstance(raw, str):
        raw = raw.encode('utf-8', errors='strict')
    if len(raw) > max_bytes:
        raise ValueError('oversized_body')

    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError('duplicate_key')
            result[key] = value
        return result

    def constant(_):
        raise ValueError('non_finite')

    result = json.loads(raw.decode('utf-8'), object_pairs_hook=pairs, parse_constant=constant)
    valid_unicode(result)
    return result


def valid_unicode(value):
    if isinstance(value, str):
        if any(0xD800 <= ord(ch) <= 0xDFFF for ch in value):
            raise ValueError('invalid_unicode')
    elif isinstance(value, dict):
        for key, item in value.items():
            valid_unicode(key)
            valid_unicode(item)
    elif isinstance(value, list):
        for item in value:
            valid_unicode(item)


def validate(value, schema, document, path='$'):
    """Validate the explicit Schema vocabulary used by this contract.

    Unknown assertion keywords fail closed, rather than silently skipping checks.
    This is not intended to replace a general-purpose JSON Schema library.
    """
    annotations = {'$schema', '$id', '$defs', 'description', 'default', 'title'}
    supported = {'$ref', 'type', 'enum', 'const', 'properties', 'required', 'additionalProperties', 'minProperties', 'items', 'maxItems', 'minLength', 'maxLength', 'pattern', 'format', 'minimum', 'maximum', 'anyOf', 'allOf', 'if', 'then', 'else', 'x-maxUtf16CodeUnits', 'x-validUnicode', 'x-nonblank'}
    if set(schema) - annotations - supported:
        raise ValueError(f'{path}: unsupported schema assertion')
    if '$ref' in schema:
        target = document
        for segment in schema['$ref'].removeprefix('#/').split('/'):
            target = target[segment]
        validate(value, target, document, path)
    if 'anyOf' in schema:
        errors = []
        for option in schema['anyOf']:
            try:
                validate(value, option, document, path)
                break
            except ValueError as error:
                errors.append(error)
        else:
            raise ValueError(f'{path}: no anyOf branch matched: {errors}')
    for option in schema.get('allOf', []):
        validate(value, option, document, path)
    if 'if' in schema:
        try:
            validate(value, schema['if'], document, path)
            branch = 'then'
        except ValueError:
            branch = 'else'
        if branch in schema:
            validate(value, schema[branch], document, path)
    typ = schema.get('type')
    checks = {
        'object': isinstance(value, dict), 'array': isinstance(value, list),
        'string': isinstance(value, str), 'boolean': type(value) is bool,
        'integer': type(value) in (int, float) and math.isfinite(value) and value == int(value),
        'number': type(value) in (int, float) and math.isfinite(value),
        'null': value is None,
    }
    if typ and not checks[typ]:
        raise ValueError(f'{path}: expected {typ}')
    if 'const' in schema and (value != schema['const'] or type(value) is bool and type(schema['const']) is not bool):
        raise ValueError(f'{path}: const')
    if 'enum' in schema and value not in schema['enum']:
        raise ValueError(f'{path}: enum')
    if isinstance(value, dict):
        if set(schema.get('required', [])) - set(value):
            raise ValueError(f'{path}: required')
        if len(value) < schema.get('minProperties', 0):
            raise ValueError(f'{path}: empty patch')
        props = schema.get('properties', {})
        if schema.get('additionalProperties') is False and set(value) - set(props):
            raise ValueError(f'{path}: unknown field')
        for key, item in value.items():
            if key in props:
                validate(item, props[key], document, path + '.' + key)
    if isinstance(value, list):
        if len(value) > schema.get('maxItems', len(value)):
            raise ValueError(f'{path}: maxItems')
        if 'items' in schema:
            for i, item in enumerate(value):
                validate(item, schema['items'], document, f'{path}[{i}]')
    if isinstance(value, str):
        valid_unicode(value)
        if not schema.get('minLength', 0) <= len(value) <= schema.get('maxLength', len(value)):
            raise ValueError(f'{path}: length')
        if 'pattern' in schema and not re.search(schema['pattern'], value):
            raise ValueError(f'{path}: pattern')
        if schema.get('format') == 'date-time':
            try:
                datetime.fromisoformat(value.replace('Z', '+00:00'))
            except ValueError as error:
                raise ValueError(f'{path}: date-time') from error
        if schema.get('x-nonblank') and not value.strip():
            raise ValueError(f'{path}: blank')
        if len(value.encode('utf-16-le')) // 2 > schema.get('x-maxUtf16CodeUnits', sys.maxsize):
            raise ValueError(f'{path}: UTF-16 code-unit limit')
    if type(value) in (int, float):
        if not math.isfinite(value) or not schema.get('minimum', -math.inf) <= value <= schema.get('maximum', math.inf):
            raise ValueError(f'{path}: numeric range')


def validate_dto(name, value):
    document = load('desk-v1-http.schema.json')
    validate(value, document['$defs'][name], document)


def encode_independent(record, fields, layout, flags=0):
    code, length, types = TYPES[record]
    tokens = types.split()
    if len(tokens) != len(layout['fields']):
        raise ValueError('field count mismatch')
    payload = bytearray()
    for token, field in zip(tokens, layout['fields']):
        if token.startswith('b'):
            width = int(token[1:])
            data = fields[field['name']].encode('ascii')
            if len(data) >= width and data:
                raise ValueError('unterminated field')
            payload.extend(data + bytes(width-len(data)))
        else:
            width = abs(int(token))
            number = fields[field['name']]
            minimum = -(1 << (8*width-1)) if token.startswith('-') else 0
            maximum = (1 << (8*width-1))-1 if token.startswith('-') else (1 << (8*width))-1
            if not minimum <= number <= maximum:
                raise ValueError('wire range')
            number &= (1 << (8*width))-1
            for _ in range(width):
                payload.append(number & 255)
                number >>= 8
    if len(payload) != length:
        raise ValueError('wire length')
    header = bytearray([167, 83, 3, code, length & 255, length >> 8, flags])
    header.append(255 ^ (sum((i+1)*v for i,v in enumerate(header)) & 255))
    return bytes(header + payload)


def validate_wire(vector):
    """Fixture semantic invariants, beyond framing and scalar width checks."""
    rec, f = vector['record'], vector['fields']
    for name, value in f.items():
        if 'reserved' in name.lower() and value not in (0, ''):
            raise ValueError('nonzero reserved')
    if rec in ('HELLO', 'WELCOME') and f['profile'] != 1:
        raise ValueError('profile')
    if rec not in ('HELLO', 'TIME_REQUEST'):
        if not f['relayEpoch']:
            raise ValueError('zero epoch')
    if 'connectionId' in f and not f['connectionId']:
        raise ValueError('zero connection')
    if rec == 'DISPLAY_STATE':
        if f['enabled'] not in (0, 1) or f['preferred'] not in range(3):
            raise ValueError('control enum')
    if rec in ('WEATHER', 'CPU'):
        if f['status'] not in range(3):
            raise ValueError('snapshot status')
        values = ['deadlineRelayMs', 'presence', 'temperatureCenti', 'location', 'unit', 'condition', 'humidityPercent'] if rec == 'WEATHER' else ['deadlineRelayMs','presence','temperatureCenti','host','cpuBasisPoints','memoryBasisPoints']
        if f['status'] != 1 and any(f[x] not in (0, '') for x in values):
            raise ValueError('missing/stale must zero fill')
        if f['status'] == 1 and not f['deadlineRelayMs']:
            raise ValueError('fresh deadline')
        if rec == 'WEATHER':
            if f['unit'] not in range(2) or f['condition'] not in range(9) or f['presence'] & ~1:
                raise ValueError('weather enum/presence')
            lo, hi = (-10000, 10000) if f['unit'] == 0 else (-14800, 21200)
            if not lo <= f['temperatureCenti'] <= hi or not 0 <= f['humidityPercent'] <= 100:
                raise ValueError('weather range')
            if not f['presence'] & 1 and f['humidityPercent']:
                raise ValueError('absent humidity must zero fill')
        else:
            if f['presence'] & ~3 or not 0 <= f['cpuBasisPoints'] <= 10000 or not 0 <= f['memoryBasisPoints'] <= 10000:
                raise ValueError('cpu range/presence')
            if not f['presence'] & 1 and f['memoryBasisPoints'] or not f['presence'] & 2 and f['temperatureCenti']:
                raise ValueError('absent cpu fields must zero fill')
            if f['presence'] & 2 and not -2000 <= f['temperatureCenti'] <= 15000:
                raise ValueError('cpu temperature range')
    if rec == 'STATS' and f['availability'] not in range(3):
        raise ValueError('stats availability')
    if rec == 'DESK_EVENT':
        if not f['seq'] or f['origin'] not in (0, 1, 2) or not 1 <= f['ttlDs'] <= 300:
            raise ValueError('event required field')
        if f['origin'] == 1 and (not f['sourceGeneration'] or not f['twitchEpisode']):
            raise ValueError('twitch identity')
        if f['origin'] != 1 and (f['sourceGeneration'] or f['twitchEpisode']):
            raise ValueError('generic identity')
    if rec == 'DEVICE_REPORT':
        if not f['deviceBootId'] or not f['reportRevision'] or f['pendingCount'] > 8 or f['baseScreen'] not in range(5) or f['reason'] not in range(9) or f['activePhase'] not in range(4):
            raise ValueError('report shape')
        if f['enqueuedSeq'] > f['processedSeq']:
            raise ValueError('cursor order')
        for i in range(9):
            entry = {k: f[f'entry{i}.{k}'] for k in ('originalRelayEpoch','seq','kind','origin','reserved')}
            used = bool(f['activePhase']) if i == 0 else i <= f['pendingCount']
            if not used and any(entry.values()):
                raise ValueError('unused report entry')
            if used:
                if not entry['seq'] or entry['origin'] not in (0, 1, 2, 255):
                    raise ValueError('report entry identity')
                if (entry['originalRelayEpoch'] == 0) != (entry['origin'] == 255):
                    raise ValueError('legacy identity')
        if not f['activePhase'] and f['activeRemainingMs']:
            raise ValueError('idle remaining')


def hold_ms(value=8000):
    if type(value) is not int or not 100 <= value <= 30000:
        raise ValueError('invalid hold')
    return ((value+99)//100)*100


def centi(value):
    return int((Decimal(str(value))*100).quantize(Decimal('1'), rounding=ROUND_HALF_UP))


def guard(delta, rtt):
    return 50 + (abs(delta)+rtt+999)//1000


def calibrated_deadline(d0, d1, r, deadline, now, previous=None):
    if not 0 <= d1-d0 <= 1000 or now < d1 or now-d1 > 60000:
        raise ValueError('clock_unavailable')
    result = d0 + deadline-r - guard(deadline-r, d1-d0)
    if not -(1 << 63) <= result < (1 << 63):
        raise ValueError('clock overflow')
    return min(result, previous) if previous is not None else result


def producer_deadline(d0, d1, r, local_deadline):
    if not 0 <= d1-d0 <= 1000:
        raise ValueError('clock_unavailable')
    result = local_deadline+r-d1-guard(local_deadline-d1, d1-d0)
    if not 0 <= result < (1 << 63):
        raise ValueError('expired/overflow')
    return result


def observation_lower(d0, d1, r, observation):
    return max(0, observation+r-d1-guard(observation-d1, d1-d0))


def wire_text(value, width, utf8=False, placeholder=None):
    clean = ''.join(' ' if ord(c) < 32 or 127 <= ord(c) <= 159 else c for c in value)
    if not utf8:
        # Required mappings used by shared vectors. Full legacy mapping stays in
        # PROTOCOL section 9 and WireStrings; this is not a runtime replacement.
        mappings = {'ł':'l','Ł':'L','Ж':'Zh','ж':'zh','ß':'ss','…':'...','°':' deg','—':'-','–':'-'}
        clean = ''.join(mappings.get(c, ''.join(x for x in unicodedata.normalize('NFD', c) if ' ' <= x <= '~')) for c in clean)
        clean = ' '.join(clean.split()) or (placeholder or '')
    data = clean.encode('utf-8')
    truncated = len(data) >= width
    if truncated:
        cut = width-4
        while cut > 0 and data[cut] & 192 == 128:
            cut -= 1
        data = data[:cut]+b'...'
    return data + bytes(width-len(data)), truncated


class CreditModel:
    """Small independent ordered-admission oracle, not a peer implementation."""
    def __init__(self, pending=0, baseline=0):
        self.pending = pending
        self.grant = 8-pending
        self.sent = 0
        self.processed = baseline
        self.enqueued = baseline
        self.waiting_seq = None
        self.inflight = False

    def reserve(self, seq):
        if self.inflight or self.waiting_seq is not None and seq != self.waiting_seq:
            raise ValueError('reservation overtakes waiting event')
        if self.sent >= self.grant:
            self.waiting_seq = seq
            return False
        self.sent += 1
        self.waiting_seq = seq
        self.inflight = True
        return True

    def receive(self, seq, disposition):
        if not self.inflight or seq != self.waiting_seq or self.sent > self.grant:
            raise ValueError('unordered/uncredited event')
        if disposition == 'refused' or disposition == 'enqueue' and self.pending == 8:
            raise ValueError('refused; disconnect without progress')
        if disposition == 'enqueue':
            self.pending += 1
            self.enqueued = seq
        elif disposition in ('expired','suppressed','duplicate'):
            self.grant += 1
        else:
            raise ValueError('unknown disposition')
        self.processed = max(self.processed, seq)
        self.waiting_seq = None
        self.inflight = False

    def release(self):
        if not self.pending:
            raise ValueError('double release')
        self.pending -= 1
        self.grant += 1

    def barrier(self, seq):
        if self.waiting_seq is not None:
            raise ValueError('barrier overtakes unhandled event')
        self.processed = max(self.processed, seq)


def peer_profile(new_relay, new_device):
    return 'desk' if new_relay and new_device else 'legacy'


def frame_payload(frame, expected_code, minimum):
    if len(frame) < 8 or frame[:3] != bytes([167, 83, 3]):
        raise ValueError('frame prefix')
    if frame[7] != 255 ^ (sum((i+1)*b for i,b in enumerate(frame[:7])) & 255):
        raise ValueError('header check')
    size = int.from_bytes(frame[4:6], 'little')
    if frame[3] != expected_code or size < minimum or size > 248 or len(frame) != size+8:
        raise ValueError('frame type/length')
    return frame[8:]


def negotiate_hello(frame):
    payload = frame_payload(frame, 1, 60)
    caps = int.from_bytes(payload[4:8], 'little')
    if not caps & 16:
        return 'legacy'
    valid = len(payload) >= 92 and payload[60:64] == b'\x01\x00\x00\x00' and any(payload[76:84])
    return 'desk' if valid else 'legacy'


def negotiate_welcome(frame, probe):
    payload = frame_payload(frame, 32, 24)
    caps = int.from_bytes(payload[20:24], 'little')
    if not caps & 16:
        return 'legacy'
    if len(payload) < 60 or payload[24:28] != b'\x01\x00\x00\x00' or not any(payload[28:36]) or not any(payload[36:44]) or int.from_bytes(payload[52:60], 'little') != probe:
        raise ValueError('malformed negotiated welcome; resynchronize')
    return 'desk'


class SyncModel:
    """Independent coherent-batch oracle for synchronization boundary tests."""
    def __init__(self, epoch, connection, welcomed_at):
        self.epoch, self.connection = epoch, connection
        self.deadline = welcomed_at+5000
        self.staged = {}
        self.ready = False

    def stage(self, record, fields):
        if fields['relayEpoch'] != self.epoch or fields['connectionId'] != self.connection:
            raise ValueError('obsolete session')
        revision_key = 'statsRevision' if record == 'STATS' else 'revision'
        previous = self.staged.get(record)
        if previous:
            if fields[revision_key] < previous[revision_key]:
                return
            if fields[revision_key] == previous[revision_key] and fields != previous:
                raise ValueError('equal revision unequal content')
        self.staged[record] = dict(fields)

    def finish(self, end, now):
        if now >= self.deadline or end['relayEpoch'] != self.epoch or end['connectionId'] != self.connection:
            raise ValueError('sync timeout/session')
        for record, end_key in [('DISPLAY_STATE','displayRevision'),('WEATHER','weatherRevision'),('CPU','cpuRevision'),('STATS','statsRevision')]:
            fields = self.staged.get(record)
            revision_key = 'statsRevision' if record == 'STATS' else 'revision'
            if fields is None or fields[revision_key] != end[end_key]:
                raise ValueError('incomplete revisions')
        control, stats = self.staged['DISPLAY_STATE'], self.staged['STATS']
        if control['sourceGeneration'] != end['sourceGeneration'] or stats['sourceGeneration'] != end['sourceGeneration'] or control['twitchEpisode'] != end['twitchEpisode']:
            raise ValueError('incomplete generation fences')
        self.ready = True


def report_context(fields, epoch, connection, boot, latest_seq, sent, now, previous=None):
    if (fields['relayEpoch'],fields['connectionId'],fields['deviceBootId']) != (epoch,connection,boot):
        raise ValueError('obsolete report identity')
    if not 0 <= fields['enqueuedSeq'] <= fields['processedSeq'] <= latest_seq:
        raise ValueError('report cursor bounds')
    if fields['grantTotal'] > sent+8-fields['pendingCount']:
        raise ValueError('implausible cumulative grant')
    if previous:
        if fields['reportRevision'] < previous['reportRevision']:
            raise ValueError('old report')
        if fields['reportRevision'] == previous['reportRevision'] and fields != previous:
            raise ValueError('equal report unequal content')
        if fields['grantTotal'] < previous['grantTotal'] or fields['processedSeq'] < previous['processedSeq'] or fields['enqueuedSeq'] < previous['enqueuedSeq']:
            raise ValueError('regressive report')
    lower = fields['observedRelayLowerMs']
    if lower > now:
        raise ValueError('future report observation')
    if not lower:
        return 'unknown'
    return 'stale' if now-lower > 15000 else 'fresh'


def safe_post_rejection(status, value):
    # Only a validated canonical relay rejection is safe, not arbitrary proxy
    # status alone. Expired work is dropped, never retried with renewed age.
    try:
        validate_dto('Error', value)
    except ValueError:
        return 'ambiguous'
    expected = {409: 'clock_epoch_changed', 410: 'expired', 429: 'rate_limited'}
    if value.get('code') != expected.get(status) or status not in expected:
        return 'ambiguous'
    return 'drop' if status == 410 else 'recalibrate' if status == 409 else 'backoff'


def check():
    layouts = load('desk-v1-layouts.json')
    records = {r['name']:r for r in layouts['records']}
    if set(records) != set(TYPES) or layouts['capability'] != 16:
        raise ValueError('record registry')
    for name, (code, length, types) in TYPES.items():
        r = records[name]
        if r['code'] != code or r['payloadBytes'] != length or length > 248:
            raise ValueError('record bounds')
        offset = 0
        for token, field in zip(types.split(), r['fields']):
            width = int(token[1:]) if token.startswith('b') else abs(int(token))
            expected_type = f'bytes{width}' if token.startswith('b') else ('i' if token.startswith('-') else 'u')+str(width*8)
            if field['offset'] != offset or field['width'] != width or field['type'] != expected_type:
                raise ValueError(f'{name}.{field["name"]}: offset/type mismatch')
            offset += width
        if offset != length or len(types.split()) != len(r['fields']):
            raise ValueError('layout length')
    offsets = (PROTOCOL/'desk-v1-offsets.md').read_text()
    for record in records.values():
        heading = f'## {record["name"]}: 0x{record["code"]:02x}, {record["payloadBytes"]} payload bytes, {record["payloadBytes"]+8} frame bytes'
        if offsets.count(heading) != 1:
            raise ValueError('offset table heading drift')
        table = offsets.split(heading, 1)[1].split('\n## ', 1)[0]
        expected_rows = [f'| {field["offset"]} | {field["width"]} | {field["type"]} | {field["name"]} |' for field in record['fields']]
        actual_rows = [line for line in table.splitlines() if re.match(r'^\| [0-9]+ \|', line)]
        if actual_rows != expected_rows:
            raise ValueError(f'{record["name"]}: normative offset table drift')
    vectors = load('desk-v1-vectors.json')['vectors']
    if set(v['record'] for v in vectors) != set(TYPES) or len({v['name'] for v in vectors}) != len(vectors):
        raise ValueError('vector coverage')
    tsv = (PROTOCOL/'desk-v1-vectors.tsv').read_text().splitlines()
    if len(tsv) != len(vectors):
        raise ValueError('TSV count')
    for v, line in zip(vectors, tsv):
        if encode_independent(v['record'],v['fields'],records[v['record']],v['flags']) != bytes.fromhex(v['hex']):
            raise ValueError(f'{v["name"]}: independent encoding mismatch')
        validate_wire(v)
        values = [str(v['fields'][f['name']]) if not f['type'].startswith('bytes') else v['fields'][f['name']].encode('ascii').hex() for f in records[v['record']]['fields']]
        if line != '\t'.join([v['name'],v['record'],str(v['flags']),v['hex'],','.join(values)]):
            raise ValueError('TSV drift')
    schema = load('desk-v1-http.schema.json')
    examples = load('desk-v1-http-examples.json')['examples']
    for example in examples:
        validate(example['value'],schema['$defs'][example['schema']],schema)
    openapi = load('desk-v1-openapi.json')
    normalized = json.loads(json.dumps(openapi['components']['schemas']).replace('#/components/schemas/','#/$defs/'))
    if normalized != schema['$defs'] or openapi['security'] != []:
        raise ValueError('OpenAPI schema/security drift')
    for methods in openapi['paths'].values():
        for operation in methods.values():
            if operation['security'] != []:
                raise ValueError('unexpected authentication')
    spec = (ROOT/'twitch-screen-firmware/docs/PROTOCOL.md').read_text()
    section = spec.split('<!-- tsb3-golden-vectors:start -->')[1].split('<!-- tsb3-golden-vectors:end -->')[0]
    preservation = load('legacy-vector-preservation.json')
    if hashlib.sha256(section.encode()).hexdigest() != preservation['sectionSha256']:
        raise ValueError('legacy vector section changed')
    print(f'DESK/1: {len(records)} layouts, {len(vectors)} additive vectors, {len(examples)} HTTP examples checked; legacy vector section unchanged.')


if __name__ == '__main__':
    try:
        check()
    except (ValueError, KeyError, OSError) as error:
        raise SystemExit(f'DESK contract check failed: {error}') from error
