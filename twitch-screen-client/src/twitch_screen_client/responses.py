"""Validate the upload responses consumed by this client, independently of test helpers."""
from datetime import datetime
from decimal import Decimal, ROUND_HALF_UP
import math
import re


def integer(value, low, high):
    return type(value) is int and low <= value <= high


def number(value, low, high):
    # Check the domain before math.isfinite can convert an unbounded int to float.
    return type(value) in (int, float) and low <= value <= high and math.isfinite(value)


def text(value, units, blank=True):
    if not isinstance(value, str) or (not blank and not value.strip()):
        return False
    try:
        return len(value.encode('utf-16-le')) // 2 <= units
    except UnicodeError:
        return False


def timestamp(value, nullable=False):
    if value is None:
        return nullable
    if not isinstance(value, str) or not re.fullmatch(r'\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z', value):
        return False
    try:
        datetime.fromisoformat(value)
        return True
    except ValueError:
        return False


def accepted(value, method, payload):
    if method == 'POST':
        fields = {'seq', 'id', 'type', 'title', 'body', 'at', 'ttlMs', 'relayEpoch', 'origin',
                  'sourceGeneration', 'twitchEpisode', 'expiresInMs', 'remainingExpiryMs'}
        return (set(value) == fields and integer(value['seq'], 1, 4294967295)
                and text(value['id'], 128, False) and value['type'] == payload['type']
                and text(value['title'], 4096, False) and text(value['body'], 4096)
                # Match the relay's Java String.trim boundary, not Unicode strip.
                and value['title'] == payload['title'].strip(''.join(chr(code) for code in range(33)))
                and value['body'] == payload['body']
                and timestamp(value['at'], True) and integer(value['ttlMs'], 100, 30000)
                and value['ttlMs'] == ((payload.get('ttlMs', 8000) + 99) // 100) * 100
                and value['origin'] == 'external' and type(value['sourceGeneration']) is int
                and value['sourceGeneration'] == 0 and type(value['twitchEpisode']) is int
                and value['twitchEpisode'] == 0 and integer(value['expiresInMs'], 1, 600000)
                and value['expiresInMs'] == payload.get('expiresInMs', 60000)
                and integer(value['remainingExpiryMs'], 0, value['expiresInMs']))
    fields = {'relayEpoch', 'revision', 'status', 'reason', 'receivedAt', 'observedAtSource', 'remainingFreshnessMs', 'data'}
    if (set(value) != fields or not integer(value['revision'], 1, 4294967295)
            or value['status'] not in ('fresh', 'stale') or value['reason'] not in ('fresh', 'expired', 'clock_unavailable')
            or not timestamp(value['receivedAt']) or value['observedAtSource'] not in ('producer', 'relay_receipt')
            or not integer(value['remainingFreshnessMs'], 0, 60000)):
        return False
    if value['status'] == 'fresh':
        if value['reason'] != 'fresh' or value['remainingFreshnessMs'] == 0:
            return False
    elif value['reason'] == 'fresh' or value['remainingFreshnessMs'] != 0:
        return False
    data = value['data']
    if not isinstance(data, dict) or set(data) != {'sourceId', 'host', 'cpuPercent', 'memoryPercent', 'temperatureCelsius', 'observedAt', 'freshnessMs'}:
        return False
    return (data['sourceId'] == payload['sourceId'] and data['host'] == payload['host']
            and number(data['cpuPercent'], 0, 100)
            and (data['memoryPercent'] is None or number(data['memoryPercent'], 0, 100))
            and (data['temperatureCelsius'] is None or number(data['temperatureCelsius'], -20, 150))
            and timestamp(data['observedAt']) and integer(data['freshnessMs'], 1000, 60000)
            and value['remainingFreshnessMs'] <= data['freshnessMs'])


def matches_submitted_cpu(value, payload):
    """Check canonical rounding, optional absence and producer observation identity."""
    if not isinstance(value, dict) or not accepted(value, 'PUT', payload):
        return False
    data = value['data']
    for field in ('cpuPercent', 'memoryPercent', 'temperatureCelsius'):
        low, high = (-20, 150) if field == 'temperatureCelsius' else (0, 100)
        if field in payload and not number(payload[field], low, high):
            return False
        expected = (float(Decimal(str(payload[field])).quantize(Decimal('0.01'), rounding=ROUND_HALF_UP))
                    if field in payload else None)
        if data[field] != expected:
            return False
    return (data['freshnessMs'] == payload.get('freshnessMs', 10000)
            and value['observedAtSource'] == ('producer' if 'observedAt' in payload else 'relay_receipt')
            and data['observedAt'] == payload.get('observedAt', value['receivedAt']))
