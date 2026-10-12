#!/usr/bin/env python3
"""Restart an exclusively owned production Main while the actual application runs.

The exact supplied assembly is started twice on owned ephemeral loopback ports.
Only disposable desired settings and synthetic cards/telemetry are used. This
proves process-restart recovery, not provider/suspend, ESP32 or physical pixels.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import threading
import time
import urllib.error
import urllib.request
from runtime_lifecycle import verify_callbacks, verify_report_phases
from runtime_observations import verify_application_sessions
from test_production_views import observation_lines


ROOT = Path(__file__).resolve().parents[2]


def verify(records, fixture):
    """Check every report and chronological reveal, not only final state."""
    verify_report_phases(records)
    verify_application_sessions(records)
    ready = [r for r in records if r['event'] == 'desk_ready']
    assert len(ready) == 2 and ready[0]['epoch'] != ready[1]['epoch'], 'Missing fresh runtime epoch'
    old, new = (r['epoch'] for r in ready)
    published = fixture['published']
    expected = [(old, published[i]['seq']) for i in (0, 1, 3)] + [(new, fixture['successor']['seq'])]
    started = [r for r in records if r['event'] == 'started']
    assert [(r['epoch'], r['seq']) for r in started] == expected, 'Retained FIFO changed or expired card started'
    assert all(r['origin'] == 0 for r in started)
    verify_callbacks(records, [12000, 100, 100, 100])
    summary = next(r for r in records if r['event'] == 'summary')
    assert summary['success'] and all(summary[k] == 4 for k in ('started', 'holds', 'exits', 'completed'))
    assert fixture['originalCpu']['status'] == 'fresh'
    assert fixture['restartedCpu']['status'] == 'missing' and fixture['restartedCpu']['data'] is None
    assert fixture['displayAfter']['preferredDashboard'] == 'cpu' and not fixture['displayAfter']['twitchDisplayEnabled']
    assert fixture['displayBefore']['revision'] == 2 and fixture['displayAfter']['revision'] == 1
    assert fixture['successor']['relayEpoch'] == new and fixture['successor']['seq'] == 1
    assert all(r['relayEpoch'] == old for r in published)
    assert fixture['stopExitCode'] in (0, 143) and fixture['outageMs'] >= 1500
    assert fixture['rejectedLive']['code'] == 'configuration_missing'
    assert all(fixture['monitorBefore'][key] == fixture['monitorAfter'][key] for key in
               ('relayEpoch', 'revision', 'desired', 'effective', 'sourceGeneration', 'transition'))
    assert fixture['monitorAfter']['desired']['mode'] == 'disabled'
    reports = [r for r in records if r['event'] == 'device_report_written']
    groups = [[r for r in reports if r['connection'] == session['connection'] and r['epoch'] == session['epoch']] for session in ready]
    assert sum(map(len, groups)) == len(reports) and all(groups), 'Unscoped report'
    assert any(r['pending'] == 3 and (r['activeEpoch'], r['activeSeq']) == expected[0] for r in groups[0])
    first = groups[1][0]
    assert first['processedSeq'] == first['enqueuedSeq'] == 0, 'Old epoch cursor carried into restart'
    assert (first['activeEpoch'], first['activeSeq']) == expected[0], 'Active identity relabeled on restart'
    assert first['pending'] == 2 and first['grantTotal'] == 6 and first['expiredTotal'] == 1
    pending_before = [dict(epoch=old, seq=published[i]['seq'], origin=0) for i in (1, 3)]
    assert first['pendingEntries'] == pending_before, 'Retained original identities changed'
    floors = (0, 0, 0)
    for index, rows in enumerate(groups):
        previous = None
        dequeued = 0
        for r in rows:
            assert r['displayRevision'] == (2 if index == 0 else 1)
            assert r['suppressedTotal'] == r['refusedTotal'] == 0
            assert 0 <= r['expiredTotal'] <= 1
            assert len(r['pendingEntries']) == r['pending'] <= 8
            assert r['enqueuedSeq'] == r['processedSeq']
            count = r['enqueuedSeq'] if index == 0 else int(r['enqueuedSeq'] == 1)
            assert 0 <= count <= (4 if index == 0 else 1)
            assert r['grantTotal'] + r['pending'] == 8 + count, 'Restart credit conservation failed'
            if index == 1:
                assert r['enqueuedSeq'] in (0, 1)
                assert r['expiredTotal'] == 1
                assert r['cpuRevision'] == r['weatherRevision'] == 1
                assert (r['baseScreen'], r['reason']) == (0, 4), 'Restart renewed telemetry availability'
                fence = tuple(r[k] for k in ('displayRevision', 'sourceGeneration', 'twitchEpisode'))
                assert all(a >= b for a, b in zip(fence, floors)), 'Fresh epoch fence regressed'
                floors = fence
            assert r['sourceGeneration'] == r['twitchEpisode'] == 1
            identities = [(entry['epoch'], entry['seq']) for entry in r['pendingEntries']]
            assert len(set(identities)) == len(identities), 'Duplicated pending identity'
            if index:
                if r['phase']:
                    current = expected.index((r['activeEpoch'], r['activeSeq']))
                    assert dequeued <= current <= dequeued + 1, 'Retained dequeue order changed'
                    dequeued = current
                admitted = expected[1:3] + (expected[3:] if count else [])
                assert identities == admitted[dequeued:], 'Retained pending FIFO differs from admissions/dequeues'
            else:
                assert all(epoch == old and seq in [p['seq'] for p in published[1:]] for epoch, seq in identities)
                assert identities == sorted(identities)
            if r['phase']:
                assert (r['activeEpoch'], r['activeSeq']) in expected
            if previous:
                assert r['revision'] > previous['revision'] and r['observedDeviceMs'] >= previous['observedDeviceMs']
            previous = r
    final = groups[1][-1]
    assert final['pending'] == final['phase'] == 0 and final['grantTotal'] == 9
    assert final['enqueuedSeq'] == final['processedSeq'] == 1
    synchronized = set()
    new_view_seen = False
    reveals = 0
    for position, r in enumerate(records):
        if r['event'] == 'desk_ready':
            synchronized.add((r['epoch'], r['connection']))
        if r['event'] != 'application_view':
            continue
        assert position + 1 < len(records)
        numeric = records[position + 1]
        assert numeric['event'] == 'application_telemetry'
        assert all(r[k] == numeric[k] for k in ('epoch', 'connection', 'displayRevision', 'cpuRevision'))
        if r['connection'] != '0000000000000000':
            assert (r['epoch'], r['connection']) in {(old, ready[0]['connection']), (new, ready[1]['connection'])}, 'Unscoped application view'
            assert not new_view_seen or r['epoch'] == new, 'Application view returned to old runtime epoch'
        if (r['epoch'], r['connection']) == (new, ready[1]['connection']):
            new_view_seen = True
            assert numeric['cpuPresence'] == numeric['weatherPresence'] == 0
            assert numeric['cpuBasisPoints'] == numeric['memoryBasisPoints'] == numeric['temperatureCenti'] == 0
            assert not r['cpuVisible'] and not r['weatherVisible'], 'Restart exposed cached telemetry'
            if r['reason'] == 2:
                assert (new, r['connection']) not in synchronized
                assert r['baseScreen'] == 0
            else:
                assert r['displayRevision'] == r['cpuRevision'] == 1
                assert (r['baseScreen'], r['reason']) == (0, 4)
                if not r['covered']:
                    reveals += 1
        if r['connection'] == '0000000000000000':
            assert (r['baseScreen'], r['reason']) == (1, 1)
            assert not r['cpuVisible'] and not r['weatherVisible']
    assert reveals == 4, 'Missing actual restart reveal boundaries'
    return dict(passed=True, readyEpochs=2, completedCards=4, expiredPending=1,
                reportsChecked=len(reports), retainedOriginalIdentities=True,
                originalHoldVerified=True, persistedIntentVerified=True,
                telemetryNotRenewed=True, physicalVerified=False, productAccepted=False)


def run(args):
    os.umask(0o077)
    assert not (ROOT / '.agent-workflow/STOP').exists()
    args.evidence.mkdir(parents=True, exist_ok=True)
    scratch = Path(tempfile.mkdtemp(prefix='firmware-production-restart-', dir=os.environ['TMPDIR']))
    ports = []
    while len(ports) < 2:
        with socket.socket() as sock:
            sock.bind(('127.0.0.1', 0)); port = sock.getsockname()[1]
        if port not in ports:
            ports.append(port)
    http, device = ports
    env = {k: v for k, v in os.environ.items() if not k.startswith(('RELAY_', 'FIRMWARE_PEER_', 'OTEL_'))}
    env.update(RELAY_TWITCH_MODE='disabled', RELAY_TWITCH_CHANNEL='', RELAY_HTTP_HOST='127.0.0.1',
               RELAY_DEVICE_HOST='127.0.0.1', RELAY_HTTP_PORT=str(http), RELAY_DEVICE_PORT=str(device),
               RELAY_HTTP_ALLOWED_HOSTS=f'127.0.0.1:{http}', RELAY_RUNTIME_STATE_FILE=str(scratch / 'state/runtime-state.json'),
               RELAY_TWITCH_TOKEN_FILE=str(scratch / 'unused-token.json'), OTEL_TRACES_EXPORTER='none',
               OTEL_METRICS_EXPORTER='none', OTEL_LOGS_EXPORTER='none')
    children, logs, records = [], [], []
    peer = timer = None
    fixture: dict = dict(published=[])

    def request(path, data=None, method=None, expected_status=None):
        body = None if data is None else json.dumps(data).encode()
        query = urllib.request.Request(f'http://127.0.0.1:{http}/api/v1/{path}', body,
                                       {'Content-Type': 'application/json'} if body else {}, method=method)
        try:
            response = urllib.request.urlopen(query, timeout=5)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            value = json.load(response)
            status = response.code
            assert status == expected_status if expected_status is not None else status in (200, 201, 202)
        with (args.evidence / 'http.jsonl').open('a') as log:
            log.write(json.dumps(dict(path=path, request=data, status=status, response=value)) + '\n')
        return value

    def start():
        log = (args.evidence / f'relay-{len(children) + 1}.log').open('w'); logs.append(log)
        child = subprocess.Popen(['java', '-jar', str(args.assembly.resolve())], cwd=scratch, env=env, stdout=log, stderr=subprocess.STDOUT)
        children.append(child)
        until = time.monotonic() + 60
        while True:
            try:
                request('clock'); return
            except OSError:
                pass
            assert child.poll() is None and time.monotonic() < until, 'Owned Main failed readiness'
            time.sleep(.1)

    try:
        start()
        fixture['displayBefore'] = request('display', dict(twitchDisplayEnabled=False, preferredDashboard='cpu'), 'PATCH')
        fixture['originalCpu'] = request('cpu', dict(sourceId='restart-fixture', host='Synthetic CPU', cpuPercent=35, memoryPercent=0, freshnessMs=60000), 'PUT')
        peer_env = env.copy()
        peer_env.update(FIRMWARE_PEER_PORT=str(device), FIRMWARE_PEER_SECONDS='20', FIRMWARE_PEER_PROFILE_READY='1',
                        FIRMWARE_PEER_REQUIRE_DESK='1', FIRMWARE_PEER_MAX_WRITE_BYTES='13', FIRMWARE_PEER_MAX_READ_BYTES='7')
        with (args.evidence / 'peer-stderr.log').open('w') as errors:
            peer = subprocess.Popen([str(args.binary.resolve())], env=peer_env, text=True, stdout=subprocess.PIPE, stderr=errors)
            timer = threading.Timer(35, peer.kill); timer.start()
            stage = 'initial'
            with (args.evidence / 'observations.jsonl').open('w') as capture:
                for line in observation_lines(peer):
                    capture.write(line); capture.flush()
                    row = json.loads(line); records.append(row)
                    assert not (ROOT / '.agent-workflow/STOP').exists()
                    if stage == 'initial' and row['event'] == 'device_report_written':
                        assert row['baseScreen'] == 4
                        fixture['monitorBefore'] = request('twitch/monitoring')
                        fixture['rejectedLive'] = request('twitch/monitoring', dict(mode='live', channel='synthetic'), 'PATCH', 409)
                        fixture['monitorAfter'] = request('twitch/monitoring')
                        fixture['published'].append(request('notifications', dict(type='info', title='Synthetic active restart', body='', ttlMs=12000), 'POST'))
                        stage = 'active'
                    elif stage == 'active' and row['event'] == 'started':
                        for i, lifetime in enumerate((60000, 1000, 60000)):
                            fixture['published'].append(request('notifications', dict(type='info', title=f'Synthetic pending {i}', body='', ttlMs=100, expiresInMs=lifetime), 'POST'))
                        stage = 'full'
                    elif stage == 'full' and row['event'] == 'device_report_written' and row['pending'] == 3:
                        stopped_at = time.monotonic()
                        children[0].terminate(); children[0].wait(timeout=30)
                        fixture['stopExitCode'] = children[0].returncode
                        # Deliberate finite test-owned outage, not runtime polling.
                        time.sleep(1.6)
                        start()
                        fixture['outageMs'] = int((time.monotonic() - stopped_at) * 1000)
                        fixture['restartedCpu'] = request('cpu')
                        fixture['displayAfter'] = request('display')
                        stage = 'restarted'
                    elif stage == 'restarted' and row['event'] == 'device_report_written' and row['epoch'] != fixture['published'][0]['relayEpoch']:
                        fixture['successor'] = request('notifications', dict(type='info', title='Synthetic fresh epoch', body='', ttlMs=100), 'POST')
                        stage = 'finish'
            code = peer.wait(timeout=5)
        assert code == 0 and stage == 'finish', (code, stage)
        (args.evidence / 'fixture.json').write_text(json.dumps(fixture, indent=2) + '\n')
        result = verify(records, fixture)
        result.update(assemblySha256=hashlib.sha256(args.assembly.read_bytes()).hexdigest(),
                      nativeSha256=hashlib.sha256(args.binary.read_bytes()).hexdigest())
        (args.evidence / 'verified.json').write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps(result))
    finally:
        if timer:
            timer.cancel()
        if peer is not None and peer.poll() is None:
            peer.terminate(); peer.wait(timeout=5)
        for child in children:
            if child.poll() is None:
                child.terminate()
            child.wait(timeout=30)
        for log in logs:
            log.close()
        closed = []
        for port in ports:
            with socket.socket() as sock:
                sock.settimeout(1); closed.append(sock.connect_ex(('127.0.0.1', port)) != 0)
        cleanup = dict(ownedChildrenReaped=all(c.poll() is not None for c in children),
                       ownedPeerReaped=peer is None or peer.poll() is not None,
                       ownedListenersClosed=all(closed), relayExitCodes=[c.returncode for c in children])
        (args.evidence / 'cleanup.json').write_text(json.dumps(cleanup, indent=2) + '\n')
        assert all(closed), 'Owned listener remains'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--assembly', type=Path, required=True)
    parser.add_argument('--binary', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--test-owned', action='store_true', help='Acknowledge disposable child/settings ownership')
    args = parser.parse_args()
    if not args.test_owned:
        parser.error('--test-owned is required; this gate starts and restarts disposable relay children')
    if not args.assembly.is_file() or not args.binary.is_file():
        parser.error('Both actual assembly and native application must exist')
    run(args)


if __name__ == '__main__':
    main()
