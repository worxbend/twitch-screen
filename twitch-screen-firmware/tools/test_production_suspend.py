#!/usr/bin/env python3
"""Stop only an owned native application for 61 seconds against production Main.

This gate exercises real process suspension and the device loop-gap path, not
machine suspend, live providers, the ESP32 adapter or physical LCD acceptance.
Only disposable loopback children, synthetic content and private state are used.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import signal
import socket
import subprocess
import tempfile
import threading
import time
import urllib.request
from runtime_lifecycle import verify_callbacks, verify_report_phases
from runtime_observations import session_key, verify_application_sessions
from test_production_views import observation_lines

ROOT = Path(__file__).resolve().parents[2]


def resume_device_bounds(records, fixture):
    """Conservatively bracket resume without using teardown or rendering output.

    The trigger report was sampled before confirmed SIGSTOP. Its device time
    plus the parent's confirmed-stop duration is a lower resume bound. The
    parent launch-before timestamp precedes construction of the native clock,
    so elapsed parent time supplies an independent upper bound. Both clocks
    advance during this process-only stop; this does not model machine suspend.
    """
    timing = fixture['pauseTiming']
    launch, stopped, before, after = (timing[k] for k in
                                     ('launchBeforeNs', 'stoppedNs', 'resumeBeforeNs', 'resumeAfterNs'))
    assert all(type(value) is int and value > 0 for value in (launch, stopped, before, after))
    assert launch < stopped < before <= after and after - before < 1000000000
    anchor_index = timing['anchorRecordIndex']
    assert type(anchor_index) is int and 0 <= anchor_index < len(records)
    anchor = records[anchor_index]
    assert anchor['event'] == 'device_report_written' and anchor['pending'] == 2
    assert anchor['phase'] == 2 and anchor['activeSeq'] == fixture['published'][0]['seq']
    assert anchor['epoch'] == fixture['published'][0]['relayEpoch']
    assert anchor['observedDeviceMs'] == timing['anchorDeviceMs']
    elapsed = (before - stopped) // 1000000
    assert elapsed == fixture['pausedMs'] and elapsed >= 61000
    lower = timing['anchorDeviceMs'] + elapsed
    upper = (after - launch + 999999) // 1000000
    assert 0 <= upper - lower <= 1000, 'Unbounded stop/resume device-time uncertainty'
    return lower, upper


def verify(records, fixture):
    """Require every post-gap report/view to preserve original age and fences."""
    verify_application_sessions(records)
    verify_report_phases(records)
    ready = [r for r in records if r['event'] == 'desk_ready']
    assert len(ready) == 2 and ready[0]['epoch'] == ready[1]['epoch']
    assert ready[0]['connection'] != ready[1]['connection']
    assert fixture['stoppedSignal'] == signal.SIGSTOP and fixture['pausedMs'] >= 61000
    published = fixture['published']
    expected = [(published[i]['relayEpoch'], published[i]['seq']) for i in (0, 2)]
    expected += [(fixture['successor']['relayEpoch'], fixture['successor']['seq'])]
    assert all(epoch == ready[0]['epoch'] for epoch, _ in expected)
    started = [r for r in records if r['event'] == 'started']
    assert [(r['epoch'], r['seq']) for r in started] == expected, 'Expired pending or changed FIFO'
    assert all(r['origin'] == 0 for r in started)
    active_rows = [r for r in records if r['event'] in ('started', 'card_lifecycle')
                   and (r['epoch'], r['seq']) == expected[0]]
    assert len(active_rows) == 4 and active_rows[0]['holdMs'] == 30000
    assert [r['phase'] for r in active_rows[1:]] == [2, 3, 0]
    start, hold, exit_card, complete = active_rows
    assert start['atMs'] <= hold['atMs'] < exit_card['atMs'] < complete['atMs']
    # Relax only the stopped card's hold callback latency. Every original card
    # remains in one shared chronology, including the paused-to-successor seam.
    verify_callbacks(records, [30000, 100, 100],
                     hold_upper_ms={expected[0]: fixture['pausedMs'] + 2000})
    summary = next(r for r in records if r['event'] == 'summary')
    assert summary['success'] and all(summary[k] == 3 for k in ('started', 'holds', 'exits', 'completed'))
    assert fixture['finalCpu']['status'] == fixture['finalWeather']['status'] == 'stale'
    assert fixture['finalCpu']['revision'] == fixture['cpu']['revision']
    assert fixture['finalWeather']['revision'] == fixture['weather']['revision']
    assert fixture['finalDisplay']['preferredDashboard'] == 'weather' and not fixture['finalDisplay']['twitchDisplayEnabled']
    assert fixture['finalMonitoring']['desired']['mode'] == 'disabled'
    assert fixture['monitorReadback']['transition'] == 'ready' and fixture['monitorReadback']['effective']['mode'] == 'disabled'
    assert fixture['monitorReadback']['sourceGeneration'] == fixture['finalMonitoring']['sourceGeneration']
    assert fixture['monitorReadback']['revision'] == fixture['finalMonitoring']['revision']
    first_key, second_key = map(session_key, ready)
    reports = [r for r in records if r['event'] == 'device_report_written']
    groups = [[r for r in reports if session_key(r) == key] for key in (first_key, second_key)]
    assert all(groups) and sum(map(len, groups)) == len(reports)
    assert any(r['pending'] == 2 and r['activeSeq'] == expected[0][1] for r in groups[0])
    assert fixture['finalEpisode'] == groups[0][0]['twitchEpisode'] + fixture['finalMonitoring']['sourceGeneration'] - groups[0][0]['sourceGeneration'], 'Episode differs from accepted source switches'
    first = groups[1][0]
    retained_pending = int(first['pending'] == 1)
    assert first['pending'] in (0, 1) and first['grantTotal'] == 8 - first['pending'] and first['expiredTotal'] == 1
    assert first['pendingEntries'] == ([dict(epoch=expected[1][0], seq=expected[1][1], origin=0)] if retained_pending else []), 'Lost retained generic identity'
    # Eligible generic cards must progress offline; reconnect may follow their
    # completion. Do not require reporting/network recovery to stall that work.
    if first['phase']:
        assert (first['activeEpoch'], first['activeSeq']) in expected[:2]
    assert first['processedSeq'] == first['enqueuedSeq'] == published[2]['seq']
    for index, rows in enumerate(groups):
        previous = None
        for r in rows:
            assert r['refusedTotal'] == r['suppressedTotal'] == 0
            assert r['pending'] == len(r['pendingEntries']) <= 8
            assert r['enqueuedSeq'] <= r['processedSeq'] <= fixture['successor']['seq']
            assert r['enqueuedSeq'] in ([0] + [p['seq'] for p in published] if not index else [published[2]['seq'], fixture['successor']['seq']])
            if index:
                assert r['expiredTotal'] == 1
                admitted = int(r['enqueuedSeq'] >= fixture['successor']['seq'])
                assert r['grantTotal'] + r['pending'] == 8 - retained_pending + admitted, 'Reconnected credit leaked'
                assert (r['baseScreen'], r['reason']) == (0, 5), 'Resynchronization renewed stale telemetry'
                assert r['displayRevision'] == fixture['finalDisplay']['revision']
                assert r['sourceGeneration'] == fixture['finalMonitoring']['sourceGeneration']
                assert r['twitchEpisode'] == fixture['finalEpisode']
                assert r['cpuRevision'] == fixture['cpu']['revision'] and r['weatherRevision'] == fixture['weather']['revision']
                assert all((entry['epoch'], entry['seq']) in expected[1:] for entry in r['pendingEntries'])
                assert [(e['epoch'], e['seq']) for e in r['pendingEntries']] == sorted((e['epoch'], e['seq']) for e in r['pendingEntries'])
            else:
                assert r['expiredTotal'] == 0
                count = sum(r['enqueuedSeq'] >= p['seq'] for p in published)
                assert r['grantTotal'] + r['pending'] == 8 + count, 'Initial credit leaked'
            if r['phase']:
                assert (r['activeEpoch'], r['activeSeq']) in expected
            if previous:
                assert r['revision'] > previous['revision'] and r['observedDeviceMs'] >= previous['observedDeviceMs']
                assert all(r[k] >= previous[k] for k in ('grantTotal', 'enqueuedSeq', 'processedSeq')), 'Connection progress regressed'
            previous = r
    final = groups[1][-1]
    assert final['pending'] == final['phase'] == 0 and final['grantTotal'] == 9 - retained_pending
    assert final['processedSeq'] == final['enqueuedSeq'] == fixture['successor']['seq']
    resume_lower, resume_upper = resume_device_bounds(records, fixture)
    gap_down = [i for i, r in enumerate(records) if r['event'] == 'link_down' and r['atMs'] >= resume_lower]
    assert gap_down, 'No actual gap-triggered transport loss'
    boundary = gap_down[0]
    assert records[boundary]['deskDeadline'] and not records[boundary]['writeTimeout'], 'Loss did not follow the actual receiver gap deadline'
    assert boundary < next(i for i, r in enumerate(records) if r['event'] == 'desk_ready' and session_key(r) == second_key)
    assert resume_lower <= records[boundary]['atMs'] < resume_upper + 2000
    assert resume_lower <= exit_card['atMs'] < resume_upper + 2000
    for r in reports:
        if r['observedDeviceMs'] >= resume_lower:
            assert (r['baseScreen'], r['reason']) in ((1, 1), (0, 2), (0, 5)), 'Resumed report renewed stale telemetry'
    views_checked = reveals = 0
    last_covered = True
    for i, r in enumerate(records):
        if r['event'] != 'application_view':
            continue
        numeric = records[i + 1]
        assert numeric['event'] == 'application_telemetry'
        if numeric['atMs'] < resume_lower:
            continue
        assert not r['cpuVisible'] and not r['weatherVisible'], 'Resumed stale telemetry root exposed'
        assert (r['baseScreen'], r['reason']) in ((1, 1), (0, 2), (0, 5)), 'Resumed stale/old-source base exposed'
        if session_key(r) == second_key and r['reason'] != 2:
            assert r['displayRevision'] == fixture['finalDisplay']['revision']
            assert (r['baseScreen'], r['reason']) == (0, 5)
            assert numeric['cpuPresence'] == numeric['weatherPresence'] == 0
            assert numeric['cpuBasisPoints'] == numeric['memoryBasisPoints'] == numeric['temperatureCenti'] == 0
        if last_covered and not r['covered']:
            reveals += 1
        last_covered = r['covered']
        views_checked += 1
    assert views_checked >= 4 and reveals == 3, 'Missing post-gap reveal evidence'
    for epoch, seq in expected:
        callback = next(r for r in records if r['event'] == 'card_lifecycle'
                        and (r['epoch'], r['seq']) == (epoch, seq) and r['phase'] == 3)
        candidates = [records[i + 1] for i, r in enumerate(records[:-1])
                      if r['event'] == 'application_view' and not r['covered']
                      and callback['atMs'] <= records[i + 1]['atMs'] < callback['atMs'] + 200]
        assert candidates, 'Missing first exit/reveal observation'
    result: dict = dict(passed=True, actualProcessPauseMs=fixture['pausedMs'], readyConnections=2,
                       reportsChecked=len(reports), postGapViewsChecked=views_checked,
                       completedCards=3, expiredWaitingCards=1, originalLifetimesVerified=True,
                       resumeDeviceLowerMs=resume_lower, resumeDeviceUpperMs=resume_upper,
                       machineSuspendVerified=False, providerVerified=False, defaultEsp32Verified=False,
                       physicalVerified=False, productAccepted=False)
    return result


def run(args):
    os.umask(0o077)
    assert not (ROOT / '.agent-workflow/STOP').exists()
    args.evidence.mkdir(parents=True, exist_ok=True)
    scratch = Path(tempfile.mkdtemp(prefix='firmware-process-suspend-', dir=os.environ['TMPDIR']))
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
    relay = peer = timer = None
    stopped = False
    fixture: dict = dict(published=[])
    records = []

    def request(path, data=None, method=None):
        body = None if data is None else json.dumps(data).encode()
        query = urllib.request.Request(f'http://127.0.0.1:{http}/api/v1/{path}', body,
                                       {'Content-Type': 'application/json'} if body else {}, method=method)
        with urllib.request.urlopen(query, timeout=5) as response:
            value = json.load(response)
            assert response.status in (200, 201, 202)
        with (args.evidence / 'http.jsonl').open('a') as log:
            log.write(json.dumps(dict(path=path, request=data, response=value)) + '\n')
        return value

    try:
        with (args.evidence / 'relay.log').open('w') as relay_log, (args.evidence / 'peer-stderr.log').open('w') as errors:
            relay = subprocess.Popen(['java', '-jar', str(args.assembly.resolve())], cwd=scratch, env=env,
                                     stdout=relay_log, stderr=subprocess.STDOUT)
            until = time.monotonic() + 60
            while True:
                try:
                    request('clock'); break
                except OSError:
                    pass
                assert relay.poll() is None and time.monotonic() < until, 'Owned Main failed startup'
                time.sleep(.1)
            request('display', dict(twitchDisplayEnabled=False, preferredDashboard='cpu'), 'PATCH')
            fixture['cpu'] = request('cpu', dict(sourceId='suspend-fixture', host='Synthetic CPU', cpuPercent=35,
                                                memoryPercent=0, freshnessMs=60000), 'PUT')
            fixture['weather'] = request('weather', dict(sourceId='suspend-fixture', location='Synthetic weather',
                                                        temperature=-10, condition='snow', freshnessMs=60000), 'PUT')
            peer_env = env.copy()
            peer_env.update(FIRMWARE_PEER_PORT=str(device), FIRMWARE_PEER_SECONDS='76',
                            FIRMWARE_PEER_REQUIRE_DESK='1', FIRMWARE_PEER_PROFILE_READY='1',
                            FIRMWARE_PEER_MAX_WRITE_BYTES='13', FIRMWARE_PEER_MAX_READ_BYTES='7')
            launch_before_ns = time.monotonic_ns()
            peer = subprocess.Popen([str(args.binary.resolve())], env=peer_env, text=True, stdout=subprocess.PIPE, stderr=errors)
            timer = threading.Timer(90, peer.kill); timer.start()
            stage = 'initial'
            with (args.evidence / 'observations.jsonl').open('w') as capture:
                for line in observation_lines(peer):
                    capture.write(line); capture.flush()
                    r = json.loads(line); records.append(r)
                    assert not (ROOT / '.agent-workflow/STOP').exists()
                    if stage == 'initial' and r['event'] == 'device_report_written':
                        assert r['baseScreen'] == 4
                        fixture['published'].append(request('notifications', dict(type='info', title='Synthetic active gap', body='', ttlMs=30000, expiresInMs=600000), 'POST'))
                        stage = 'hold'
                    elif stage == 'hold' and r['event'] == 'card_lifecycle' and r['phase'] == 2:
                        for lifetime in (60000, 600000):
                            fixture['published'].append(request('notifications', dict(type='info', title='Synthetic waiting gap', body='', ttlMs=100, expiresInMs=lifetime), 'POST'))
                        stage = 'pending'
                    elif stage == 'pending' and r['event'] == 'device_report_written' and r['pending'] == 2:
                        peer.send_signal(signal.SIGSTOP)
                        stopped = True
                        pid, status = os.waitpid(peer.pid, os.WUNTRACED)
                        assert pid == peer.pid and os.WIFSTOPPED(status), 'Owned peer did not stop'
                        fixture['stoppedSignal'] = os.WSTOPSIG(status)
                        stopped_ns = time.monotonic_ns()
                        stop_at = stopped_ns / 1000000000
                        fixture['pauseTiming'] = dict(launchBeforeNs=launch_before_ns, stoppedNs=stopped_ns,
                                                      anchorRecordIndex=len(records) - 1,
                                                      anchorDeviceMs=r['observedDeviceMs'])
                        request('twitch/monitoring', dict(mode='simulated', channel='synthetic'), 'PATCH')
                        fixture['finalMonitoring'] = request('twitch/monitoring', dict(mode='disabled'), 'PATCH')
                        fixture['finalDisplay'] = request('display', dict(twitchDisplayEnabled=False, preferredDashboard='weather'), 'PATCH')
                        # A real finite stop is intentional: do not fake local clock values.
                        while time.monotonic() - stop_at < 61.1:
                            assert not (ROOT / '.agent-workflow/STOP').exists()
                            time.sleep(max(0, min(.2, 61.1 - (time.monotonic() - stop_at))))
                        fixture['finalCpu'] = request('cpu'); fixture['finalWeather'] = request('weather')
                        fixture['monitorReadback'] = request('twitch/monitoring')
                        resume_before_ns = time.monotonic_ns()
                        fixture['pausedMs'] = (resume_before_ns - stopped_ns) // 1000000
                        peer.send_signal(signal.SIGCONT); stopped = False
                        fixture['pauseTiming'].update(resumeBeforeNs=resume_before_ns, resumeAfterNs=time.monotonic_ns())
                        stage = 'recover'
                    elif stage == 'recover' and r['event'] == 'device_report_written' and r['connection'] != next(x['connection'] for x in records if x['event'] == 'desk_ready'):
                        fixture['finalEpisode'] = r['twitchEpisode']
                        fixture['successor'] = request('notifications', dict(type='follow', title='Synthetic external successor', body='', ttlMs=100, expiresInMs=600000), 'POST')
                        stage = 'finish'
            assert peer.wait(timeout=5) == 0 and stage == 'finish', stage
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
            if stopped:
                peer.send_signal(signal.SIGCONT)
            peer.terminate(); peer.wait(timeout=5)
        if relay is not None:
            if relay.poll() is None:
                relay.terminate()
            relay.wait(timeout=30)
        closed = []
        for port in ports:
            with socket.socket() as sock:
                sock.settimeout(1); closed.append(sock.connect_ex(('127.0.0.1', port)) != 0)
        cleanup = dict(ownedPeerReaped=peer is None or peer.poll() is not None,
                       ownedRelayReaped=relay is None or relay.poll() is not None,
                       relayExitCode=relay.returncode if relay else None, ownedListenersClosed=all(closed))
        (args.evidence / 'cleanup.json').write_text(json.dumps(cleanup, indent=2) + '\n')
        assert all(closed), 'Owned listener remains'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--assembly', type=Path, required=True)
    parser.add_argument('--binary', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--test-owned', action='store_true')
    args = parser.parse_args()
    if not args.test_owned:
        parser.error('--test-owned is required; SIGSTOP/SIGCONT target only the child started by this gate')
    if not args.assembly.is_file() or not args.binary.is_file():
        parser.error('Actual production assembly and native application must exist')
    run(args)


if __name__ == '__main__':
    main()
