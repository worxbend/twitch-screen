#!/usr/bin/env python3
"""Exercise canonical APIs and the actual native application on an owned relay.

The caller must supply an isolated loopback relay. This gate mutates its display,
monitoring and RAM telemetry, then restores desired settings. It never accesses
hardware, provider credentials, desktop notifications or production services.
"""
import argparse
import json
import os
import queue
import signal
from pathlib import Path
import subprocess
import threading
import urllib.request
from runtime_lifecycle import verify_callbacks, verify_report_phases
from runtime_observations import session_key, verify_application_sessions


def verify(records, fixture):
    """Fail closed for this nine-card, retained-eight, original-age fixture."""
    verify_report_phases(records)
    sessions = verify_application_sessions(records)
    ready = [r for r in records if r['event'] == 'desk_ready']
    reports = [r for r in records if r['event'] == 'device_report_written']
    started = [r for r in records if r['event'] == 'started']
    expected = [r['seq'] for r in fixture['published']]
    summary = next(r for r in records if r['event'] == 'summary')
    assert len(ready) == 2 and ready[0]['epoch'] == ready[1]['epoch']
    assert ready[0]['connection'] != ready[1]['connection']
    assert len(expected) == len(set(expected)) == 9 and expected == sorted(expected)
    assert [r['seq'] for r in started] == expected
    verify_callbacks(records, [5000] + [100] * 8)
    assert all(r['origin'] == 0 and r['epoch'] == ready[0]['epoch'] for r in started)
    assert summary['success'] and all(summary[k] == 9 for k in ('started', 'holds', 'exits', 'completed'))
    groups = [[r for r in reports if session_key(r) == session_key(session)] for session in ready]
    assert sum(map(len, groups)) == len(reports) and all(groups)
    assert groups[0][0]['pending'] == groups[0][0]['phase'] == 0 and groups[0][0]['grantTotal'] == 8
    assert any(r['pending'] == 8 and r['activeSeq'] == expected[0] for r in groups[0])
    assert groups[1][0]['pending'] == 8 and groups[1][0]['grantTotal'] == 0
    assert groups[1][0]['activeSeq'] == expected[0] and groups[1][0]['activeEpoch'] == ready[0]['epoch']
    final_fence = None
    initial_generation = groups[0][0]['sourceGeneration']
    initial_episode = groups[0][0]['twitchEpisode']
    for index, rows in enumerate(groups):
        dequeue_floor = index  # Active first card is retained at reconnect.
        floor = (0, 0, 0)
        for row in rows:
            assert row['epoch'] == ready[index]['epoch'], 'Report epoch differs from carrying session'
            fence = tuple(row[k] for k in ('displayRevision', 'sourceGeneration', 'twitchEpisode'))
            assert all(0 < value <= 0xffffffff for value in fence), 'Invalid applied report fence'
            assert all(value >= previous for value, previous in zip(fence, floor)), 'Applied report fence regressed'
            assert fence[0] <= fixture['finalRevision'] and initial_generation <= fence[1] <= fixture['sourceGeneration'], 'Unaccepted applied fence'
            # This fixture starts with override off. Each accepted source
            # switch increments the episode, then its two override writes do
            # so once each. Coalescing may skip intermediate reports, not fences.
            toggles = int(fence[0] >= fixture['overrideRevision']) + int(fence[0] >= fixture['finalRevision'])
            assert fence[2] == initial_episode + fence[1] - initial_generation + toggles, 'Episode differs from accepted control history'
            floor = fence
            if fence[0] == fixture['finalRevision']:
                assert fence[1] == fixture['sourceGeneration'], 'Final display has obsolete source'
                if final_fence is None:
                    final_fence = fence
                assert fence == final_fence, 'Final applied fence changed across reconnect'
            assert row['enqueuedSeq'] in [fixture['baseline']] + expected
            count = 0 if row['enqueuedSeq'] == fixture['baseline'] else expected.index(row['enqueuedSeq']) + 1
            if row['phase']:
                assert row['activeSeq'] in expected and row['activeOrigin'] == 0
                assert row['activeEpoch'] == ready[0]['epoch']
                current = expected.index(row['activeSeq']) + 1
                assert dequeue_floor <= current <= dequeue_floor + 1
                dequeue_floor = current
            else:
                assert row['activeSeq'] == 0
            assert row['pending'] == count - dequeue_floor
            assert row['grantTotal'] == (8 if index == 0 else -1) + dequeue_floor
            assert row['pendingEntries'] == [dict(epoch=ready[0]['epoch'], seq=seq, origin=0)
                                             for seq in expected[dequeue_floor:count]]
            assert row['expiredTotal'] == row['suppressedTotal'] == row['refusedTotal'] == 0
            assert row['enqueuedSeq'] <= row['processedSeq']
        assert all(b['revision'] > a['revision'] and b['observedDeviceMs'] >= a['observedDeviceMs']
                   for a, b in zip(rows, rows[1:]))
    assert groups[1][-1]['phase'] == groups[1][-1]['pending'] == 0 and groups[1][-1]['grantTotal'] == 8
    assert final_fence is not None and tuple(groups[1][-1][k] for k in
           ('displayRevision', 'sourceGeneration', 'twitchEpisode')) == final_fence, 'Missing final applied fence'
    assert any(r['baseScreen'] == 2 and r['displayRevision'] == fixture['overrideRevision'] for r in reports)
    latest = [r for r in records if r['event'] == 'application_telemetry'
              and session_key(r) == session_key(ready[0])
              and r['cpuRevision'] == fixture['cpu']['revision'] and r['weatherRevision'] == fixture['weather']['revision']]
    assert any(r['cpuBasisPoints'] == 2511 and r['cpuPresence'] == 0 and r['memoryBasisPoints'] == 0
               and r['temperatureCenti'] == -1011 and r['weatherPresence'] == 1 and r['humidityPercent'] == 0 for r in latest)
    assert latest, 'Missing current numeric adapter observation'
    # Complete receipt cannot precede publication. Even without assuming a
    # symmetric calibrated offset, receipt plus the original duration is a
    # conservative latest possible local expiry, never a renewal on reconnect.
    expiry_upper = min(r['atMs'] for r in latest) + fixture[fixture['source']]['data']['freshnessMs']
    first_exit = next(r['atMs'] for r in records if r['event'] == 'card_lifecycle' and r['seq'] == expected[0] and r['phase'] == 3)
    assert first_exit > 3500
    stale = [r for r in reports if r['observedDeviceMs'] >= first_exit]
    assert stale and all((r['baseScreen'], r['reason']) == (0, 5) for r in stale), 'Expired telemetry report resurrection'
    views = [r for r in records if r['event'] == 'application_view']
    assert any(r['baseScreen'] == (4 if fixture['source'] == 'cpu' else 3)
               and r['displayRevision'] == fixture['initialRevision'] for r in views)
    floors = {}
    numeric_floors = {}
    expired_connections = set()
    after_exit = False
    final_views = []
    connections = sessions
    synchronized = set()
    link_available = False
    for position, row in enumerate(records):
        if row['event'] == 'link_down':
            link_available = False
        elif row['event'] == 'welcome':
            link_available = True
        if row['event'] == 'desk_ready':
            synchronized.add(session_key(row))
        if row['event'] == 'card_lifecycle' and row['seq'] == expected[0] and row['phase'] == 3:
            after_exit = True
        if row['event'] != 'application_view':
            continue
        assert position + 1 < len(records), 'Missing timestamped adapter observation'
        observation = records[position + 1]
        assert observation['event'] == 'application_telemetry' and all(
            observation[k] == row[k] for k in ('epoch', 'connection', 'displayRevision', 'cpuRevision')), 'View/numeric observation identity differs'
        observed_at = observation['atMs']
        connection = session_key(row)
        if not link_available:
            # The controller retains original session identity while offline.
            # Require the actual chronological transport loss, not a zero
            # connection ID or an arbitrary fallback label masking telemetry.
            assert (row['baseScreen'], row['reason']) == (1, 1), 'Offline view lacks disconnected orbit'
            assert not row['cpuVisible'] and not row['weatherVisible'], 'Offline telemetry root exposed'
            continue
        assert (row['baseScreen'], row['reason']) != (1, 1), 'Disconnected label without actual link loss'
        if connection[1] == '0000000000000000':
            raise AssertionError('Linked view lacks carrying session identity')
        assert connection in connections, 'View belongs to an unknown connection'
        revisions = tuple(observation[source + 'Revision'] for source in ('cpu', 'weather'))
        assert all(0 <= revision <= fixture[source]['revision'] for source, revision in zip(('cpu', 'weather'), revisions)), 'Numeric revision outside accepted history'
        assert all(value >= previous for value, previous in zip(revisions, numeric_floors.get(connection, (0, 0)))), 'Numeric source revision regressed'
        numeric_floors[connection] = revisions
        if row['reason'] == 2:
            assert connection not in synchronized, 'Ready connection returned to unscoped synchronization'
            assert row['baseScreen'] == 0 and not row['cpuVisible'] and not row['weatherVisible'], 'Staged telemetry exposed'
            continue
        fence = (row['displayRevision'], row['statsSourceGeneration'])
        floor = floors.get(connection, (0, 0))
        assert all(value >= previous for value, previous in zip(fence, floor)), 'Applied view fence regressed'
        assert all(value > 0 for value in fence), 'View has an invalid applied fence'
        assert fence[0] <= fixture['finalRevision'] and fence[1] <= fixture['sourceGeneration']
        floors[connection] = fence
        if fence[0] == fixture['finalRevision']:
            assert fence[1] == fixture['sourceGeneration'], 'Final view has obsolete source'
        assert not row['covered'] or (not row['cpuVisible'] and not row['weatherVisible']), 'Covered telemetry root exposed'
        if (row['baseScreen'], row['reason']) == (0, 5) and fence[0] == fixture['finalRevision']:
            expired_connections.add(connection)
        # View records are synchronous adapter transitions. Unlike delayed
        # socket reports, their stream position follows the actual callbacks.
        if after_exit or observed_at >= expiry_upper or connection in expired_connections:
            assert (row['baseScreen'], row['reason']) == (0, 5), 'Expired telemetry view resurrection'
            assert not row['cpuVisible'] and not row['weatherVisible'], 'Expired telemetry root exposed'
        if connection == session_key(ready[1]) and not row['covered']:
            assert fence[0] == fixture['finalRevision'], 'Uncover lacks final accepted control'
            assert (row['baseScreen'], row['reason']) == (0, 5)
            assert not row['cpuVisible'] and not row['weatherVisible']
            final_views.append(row)
    assert len(final_views) == 9, 'Missing an original-expiry uncover boundary'
    assert floors[session_key(ready[1])] == (fixture['finalRevision'], fixture['sourceGeneration'])
    assert fixture['finalCpu']['status'] == fixture['finalWeather']['status'] == 'stale'
    assert fixture['finalCpu']['revision'] == fixture['cpu']['revision']
    assert fixture['finalWeather']['revision'] == fixture['weather']['revision']
    return dict(passed=True, source=fixture['source'], readyConnections=2, completedCards=9,
                reportsChecked=len(reports), retainedPending=8, initialReconnectCredit=0,
                originalFreshnessVerified=True, latestNumericPresenceVerified=True,
                physicalVerified=False, defaultEsp32Verified=False)


def observation_lines(child):
    """Drain stdout independently of HTTP actions, with bounded fixture memory.

    A blocking observer must not turn its own pipe into firmware backpressure.
    Overflow is a harness failure, not permission to drop observed transitions.
    """
    pending = queue.Queue(maxsize=1024)
    failures = []
    ended = threading.Event()

    def read():
        try:
            assert child.stdout is not None
            # Bypass TextIOWrapper's unbounded line iterator. The binary
            # readline limit applies before UTF-8 decoding or queue residence.
            stream = child.stdout.buffer
            while True:
                raw = stream.readline(8193)
                if not raw:
                    break
                assert len(raw) <= 8192, 'Observation line exceeded fixture bound'
                pending.put_nowait(raw.decode('utf-8', errors='strict'))
        except Exception as error:
            failures.append(error)
            child.kill()
        finally:
            ended.set()

    reader = threading.Thread(target=read, daemon=True)
    reader.start()
    try:
        while failures or not ended.is_set() or not pending.empty():
            if failures:
                raise failures[0]
            try:
                yield pending.get(timeout=.2)
            except queue.Empty:
                pass
    finally:
        if not ended.is_set() and child.poll() is None:
            child.terminate()
        reader.join(timeout=5)
        assert not reader.is_alive(), 'Owned observation reader did not close'


def run(args, source):
    out = args.evidence / source
    out.mkdir(parents=True, exist_ok=True)
    exchanges, records = [], []
    base = f'http://127.0.0.1:{args.http_port}'

    def request(path, payload=None, method=None):
        data = None if payload is None else json.dumps(payload).encode()
        query = urllib.request.Request(base + '/api/v1/' + path, data,
                                       {'Content-Type': 'application/json'} if data else {}, method=method)
        with urllib.request.urlopen(query, timeout=5) as response:
            value = json.load(response)
            assert response.status in (200, 201, 202)
        exchanges.append(dict(path=path, method=method or 'GET', request=payload, response=value))
        (out / 'http.json').write_text(json.dumps(exchanges, indent=2) + '\n')
        return value

    original = request('display')
    monitor = request('twitch/monitoring')
    assert monitor['desired']['mode'] == 'disabled', 'Use a disposable disabled relay'
    fixture = dict(source=source, published=[])
    env = {k: v for k, v in os.environ.items() if not k.startswith('FIRMWARE_PEER_')}
    env.update(FIRMWARE_PEER_PORT=str(args.device_port), FIRMWARE_PEER_SECONDS='45',
               FIRMWARE_PEER_REQUIRE_DESK='1',
               FIRMWARE_PEER_PROFILE_READY='1', FIRMWARE_PEER_MAX_WRITE_BYTES='13', FIRMWARE_PEER_MAX_READ_BYTES='7')
    child = None
    deadline = None
    stage = 'initial'
    try:
        request('display', dict(twitchDisplayEnabled=False, preferredDashboard='generic'), 'PATCH')
        with (out / 'peer-stderr.log').open('w') as errors:
            child = subprocess.Popen([str(args.binary.resolve())], env=env, text=True, stdout=subprocess.PIPE, stderr=errors)
            deadline = threading.Timer(55, child.kill)
            deadline.start()
            assert child.stdout is not None
            with (out / 'observations.jsonl').open('w') as log:
                for line in observation_lines(child):
                    log.write(line); log.flush()
                    r = json.loads(line); records.append(r)
                    assert not (Path(__file__).resolve().parents[2] / '.agent-workflow/STOP').exists()
                    if stage == 'initial' and r['event'] == 'device_report_written':
                        assert any(x['event'] == 'desk_ready' for x in records)
                        fixture['baseline'] = r['enqueuedSeq']
                        for index in range(12):
                            fixture['cpu'] = request('cpu', dict(sourceId='firmware-fixture', host='Synthetic CPU',
                                                               cpuPercent=25 + index / 100, freshnessMs=3500), 'PUT')
                            fixture['weather'] = request('weather', dict(sourceId='firmware-fixture', location='Synthetic weather',
                                                                       temperature=-10 - index / 100, condition='snow',
                                                                       humidityPercent=0, freshnessMs=3500), 'PUT')
                        display = request('display', dict(twitchDisplayEnabled=False, preferredDashboard=source), 'PATCH')
                        fixture['initialRevision'] = display['revision']; stage = 'fresh'
                    elif stage == 'fresh' and r['event'] == 'application_telemetry' and r['cpuBasisPoints'] == 2511 and r['temperatureCenti'] == -1011:
                        fixture['published'].append(request('notifications', dict(type='info', title='Synthetic production active', body='', ttlMs=5000), 'POST'))
                        stage = 'active'
                    elif stage == 'active' and r['event'] == 'started':
                        for index in range(8):
                            fixture['published'].append(request('notifications', dict(type='info', title=f'Synthetic pending {index}', body='', ttlMs=100), 'POST'))
                        stage = 'full'
                    elif stage == 'full' and r['event'] == 'device_report_written' and r['pending'] == 8:
                        request('twitch/monitoring', dict(mode='simulated'), 'PATCH')
                        monitored = request('twitch/monitoring', dict(mode='disabled'), 'PATCH')
                        fixture['sourceGeneration'] = monitored['sourceGeneration']
                        display = request('display', dict(twitchDisplayEnabled=True), 'PATCH')
                        fixture['overrideRevision'] = display['revision']; stage = 'override'
                    elif stage == 'override' and r['event'] == 'device_report_written' and r['displayRevision'] == fixture['overrideRevision'] and r['baseScreen'] == 2:
                        display = request('display', dict(twitchDisplayEnabled=False, preferredDashboard=source), 'PATCH')
                        fixture['finalRevision'] = display['revision']; stage = 'return'
                    elif stage == 'return' and r['event'] == 'device_report_written' and r['displayRevision'] == fixture['finalRevision']:
                        assert r['pending'] == 8 and r['activeSeq'] == fixture['published'][0]['seq'], 'Pressure cleared before scoped reconnect'
                        child.send_signal(signal.SIGUSR1)
                        stage = 'finish'
            code = child.wait(timeout=5)
        assert code == 0 and stage == 'finish', (code, stage)
        fixture['finalCpu'] = request('cpu'); fixture['finalWeather'] = request('weather')
        (out / 'fixture.json').write_text(json.dumps(fixture, indent=2) + '\n')
        result = verify(records, fixture)
        (out / 'verified.json').write_text(json.dumps(result, indent=2) + '\n')
        return result
    finally:
        if deadline: deadline.cancel()
        if child is not None and child.poll() is None:
            child.terminate()
            child.wait(timeout=5)
        request('twitch/monitoring', monitor['desired'], 'PATCH') if monitor['desired'].get('channel') else request('twitch/monitoring', {'mode': 'disabled'}, 'PATCH')
        restored_monitor = request('twitch/monitoring')
        assert restored_monitor['desired'] == monitor['desired'], 'Monitoring restore differs'
        request('display', {k: original[k] for k in ('twitchDisplayEnabled', 'preferredDashboard')}, 'PATCH')
        restored = request('display')
        assert all(restored[k] == original[k] for k in ('twitchDisplayEnabled', 'preferredDashboard'))
        (out / 'cleanup.json').write_text(json.dumps(dict(ownedPeerReaped=child is None or child.poll() is not None,
                                                         displayRestored=True, monitoringRestored=True)) + '\n')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binary', type=Path, required=True)
    parser.add_argument('--http-port', type=int, required=True)
    parser.add_argument('--device-port', type=int, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--test-owned', action='store_true', required=True)
    args = parser.parse_args()
    assert args.binary.is_file() and all(1 <= p <= 65535 for p in (args.http_port, args.device_port))
    os.umask(0o077)
    args.evidence.mkdir(parents=True, exist_ok=True)
    results = [run(args, source) for source in ('cpu', 'weather')]
    (args.evidence / 'verified.json').write_text(json.dumps(results, indent=2) + '\n')
    print(json.dumps(results, indent=2))


if __name__ == '__main__':
    main()
