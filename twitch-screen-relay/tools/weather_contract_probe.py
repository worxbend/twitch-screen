#!/usr/bin/env python3
"""Verify mounted weather admission against a test-owned assembled relay.

Use synthetic external data only. The caller owns child/listener lifecycle.
This checks HTTP state and never claims device application or physical pixels.
"""
import json
import time
import urllib.error
import urllib.request


def exchange(base, method, path, payload=None):
    wire = None if payload is None else json.dumps(payload, allow_nan=False).encode()
    headers = {} if wire is None else {'Content-Type': 'application/json'}
    request = urllib.request.Request(base + path, data=wire, headers=headers, method=method)
    try:
        response = urllib.request.urlopen(request, timeout=3)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        return response.code, json.load(response), response.headers


def verify(base):
    checks = []
    _, clock, _ = exchange(base, 'GET', '/api/v1/clock')
    _, before, _ = exchange(base, 'GET', '/api/v1/display')
    code, initial, _ = exchange(base, 'GET', '/api/v1/weather')
    assert code == 200 and initial['status'] == 'missing' and initial['data'] is None
    payload = {'sourceId': 'relay07-weather', 'location': 'synthetic desk',
               'temperature': -0.005, 'condition': 'snow', 'humidityPercent': 0,
               'freshnessMs': 1000,
               'deadline': {'relayEpoch': clock['relayEpoch'], 'monotonicMs': clock['monotonicMs'] + 700}}
    code, accepted, headers = exchange(base, 'PUT', '/api/v1/weather', payload)
    assert code == 200 and accepted['status'] == 'fresh' and accepted['revision'] == 2
    assert accepted['data']['temperature'] == -0.01 and accepted['data']['humidityPercent'] == 0
    assert accepted['data']['unit'] == 'celsius' and accepted['observedAtSource'] == 'relay_receipt'
    assert headers['X-Relay-Epoch'] == clock['relayEpoch'] and int(headers['X-Relay-Monotonic-Ms']) >= clock['monotonicMs']
    _, readback, _ = exchange(base, 'GET', '/api/v1/weather')
    assert readback['data'] == accepted['data'] and readback['revision'] == accepted['revision']
    _, display, _ = exchange(base, 'GET', '/api/v1/display')
    for key in ('revision', 'twitchDisplayEnabled', 'preferredDashboard'):
        assert display[key] == before[key]
    assert display['weather']['status'] == 'fresh' and display['weather']['revision'] == accepted['revision']
    checks.append('actual weather PUT/GET preserves rounded data, measured zero, default unit, calibration and independent display intent')
    bad_epoch = dict(payload, deadline={'relayEpoch': f"{int(clock['relayEpoch'], 16) ^ 1 or 2:016x}", 'monotonicMs': 0})
    assert exchange(base, 'PUT', '/api/v1/weather', bad_epoch)[0] == 409
    assert exchange(base, 'PUT', '/api/v1/weather', dict(payload, deadline={'relayEpoch': clock['relayEpoch'], 'monotonicMs': 0}))[0] == 410
    for update in ({'unit': None}, {'humidityPercent': None}, {'humidityPercent': 1.1}, {'temperature': 100.001}, {'unit': 'Celsius'}, {'unexpected': 1}):
        assert exchange(base, 'PUT', '/api/v1/weather', dict(payload, **update))[0] == 400
    _, unchanged, _ = exchange(base, 'GET', '/api/v1/weather')
    assert unchanged['revision'] == accepted['revision'] and unchanged['data'] == accepted['data']
    checks.append('actual weather malformed/wrong-epoch/expired writes leave accepted data and revision unchanged')
    # A fixed wait verifies deadline passage, not a production data polling loop.
    time.sleep(0.75)
    _, stale, _ = exchange(base, 'GET', '/api/v1/weather')
    assert stale['status'] == 'stale' and stale['remainingFreshnessMs'] == 0
    assert stale['revision'] == accepted['revision'] and stale['data'] == accepted['data']
    _, display, _ = exchange(base, 'GET', '/api/v1/display')
    assert display['weather']['status'] == 'stale' and display['weather']['remainingFreshnessMs'] == 0
    checks.append('actual weather original calibrated deadline expires without renewing readback or display readiness')
    for path, field, body in (
        ('/api/v1/weather', 'location', {'sourceId': 'relay07-label', 'temperature': 1, 'condition': 'clear'}),
        ('/api/v1/cpu', 'host', {'sourceId': 'relay07-label', 'cpuPercent': 1}),
    ):
        for label in ('Desk\tNorth', 'Desk\nNorth', 'Desk\rNorth', 'Desk\u0000North', 'Desk\u007fNorth', 'Desk\u0085North', '😀' * 128):
            code, latest, _ = exchange(base, 'PUT', path, dict(body, **{field: label}))
            assert code == 200 and latest['data'][field] == label
            code, readback, _ = exchange(base, 'GET', path)
            assert code == 200 and readback['data'] == latest['data'] and readback['revision'] == latest['revision']
        for label in ('😀' * 129, 'Desk\ud800North', '\t\n'):
            assert exchange(base, 'PUT', path, dict(body, **{field: label}))[0] == 400
            _, readback, _ = exchange(base, 'GET', path)
            assert readback['revision'] == latest['revision'] and readback['data'] == latest['data']
    checks.append('actual CPU/weather diagnostic labels preserve C0/DEL/C1 and 128 Unicode scalars; 129/lone surrogate/blank reject without mutation')
    return checks
