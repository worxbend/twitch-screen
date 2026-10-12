#!/usr/bin/env python3
"""Pair immutable baseline firmware with an explicitly disposable running relay.

This adapter executes original transport/queue code under ASan/UBSan. It does not
execute LVGL or access a physical board. The caller owns the actual Main/container
and must preserve its exact artifact identity and cleanup evidence separately.
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
import urllib.request
from test_four_peer_matrix import BASELINE
from test_production_views import observation_lines


def verify(records, published):
    """Validate original queue transfers without claiming LVGL or pixels."""
    assert len(published) == 2
    expected = [r['seq'] for r in published]
    assert len(set(expected)) == 2 and expected == sorted(expected)
    assert all(r['event'] in ('welcome', 'enqueued', 'dequeued', 'baseline_summary') for r in records)
    # This runner publishes one card in response to each welcome. Aggregate
    # counts cannot prove reconnect transfer: require each complete transfer
    # inside its carrying welcome interval, with dequeue strictly after enqueue.
    assert [r['event'] for r in records] == [
        'welcome', 'enqueued', 'dequeued', 'welcome', 'enqueued', 'dequeued', 'baseline_summary'
    ], 'Transfer occurred outside its chronological welcome interval'
    assert [r['seq'] for r in records if r['event'] == 'enqueued'] == expected
    assert [r['seq'] for r in records if r['event'] == 'dequeued'] == expected
    welcomes = [r for r in records if r['event'] == 'welcome']
    assert len(welcomes) == 2 and all(not r['desk'] and not r['caps'] & 16 for r in welcomes)
    summaries = [r for r in records if r['event'] == 'baseline_summary']
    assert len(summaries) == 1
    summary = summaries[0]
    assert summary['welcomes'] == summary['admitted'] == summary['dequeued'] == 2 and summary['stats'] > 0
    return expected


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--http-port', type=int, required=True)
    parser.add_argument('--device-port', type=int, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--test-owned', action='store_true')
    args = parser.parse_args()
    if not args.test_owned:
        parser.error('--test-owned is required for publication to a disposable running relay')
    if not all(1 <= port <= 65535 for port in (args.http_port, args.device_port)):
        parser.error('Ports must be within 1..65535')
    os.umask(0o077)
    root = Path(__file__).resolve().parents[2]
    assert not (root / '.agent-workflow/STOP').exists()
    args.evidence.mkdir(parents=True, exist_ok=True)
    scratch = Path(tempfile.mkdtemp(prefix='firmware-baseline-running-', dir=os.environ['TMPDIR']))
    manifest = []
    for name in ('link_client.h', 'link_client.cpp', 'link_transport.h', 'notification.h',
                 'notification_wire.h', 'notify_queue.h', 'proto_codec.h', 'proto_codec.cpp', 'stats.h'):
        source = 'twitch-screen-firmware/src/' + name
        data = subprocess.check_output(['git', 'show', BASELINE + ':' + source], cwd=root)
        (scratch / name).write_bytes(data)
        manifest.append(dict(path=source, sha256=hashlib.sha256(data).hexdigest()))
    (args.evidence / 'baseline-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    binary = scratch / 'baseline-peer'
    command = ['g++', '-std=c++11', '-Wall', '-Wextra', '-Werror', '-fsanitize=address,undefined',
               '-fno-sanitize-recover=all', '-fno-pie', '-no-pie', '-I', str(scratch),
               str(Path(__file__).with_name('legacy_runtime_peer.cpp')), str(scratch / 'link_client.cpp'),
               str(scratch / 'proto_codec.cpp'), '-o', str(binary)]
    with (args.evidence / 'build.log').open('w') as log:
        subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=60, check=True)
    exchanges = []

    def request(path, payload=None, trigger_welcome=None):
        body = None if payload is None else json.dumps(payload).encode()
        query = urllib.request.Request(f'http://127.0.0.1:{args.http_port}/api/v1/{path}', body,
                                       {'Content-Type': 'application/json'} if body else {})
        with urllib.request.urlopen(query, timeout=5) as response:
            value = json.load(response)
            assert response.status in (200, 201, 202)
        exchanges.append(dict(path=path, request=payload, response=value, triggerWelcome=trigger_welcome))
        (args.evidence / 'http.json').write_text(json.dumps(exchanges, indent=2) + '\n')
        return value

    assert request('twitch/monitoring')['desired']['mode'] == 'disabled', 'Use a disposable disabled relay'
    records, published = [], []
    peer = deadline = None
    try:
        with (args.evidence / 'peer-stderr.log').open('w') as errors:
            peer = subprocess.Popen([str(binary), str(args.device_port)], text=True, stdout=subprocess.PIPE, stderr=errors)
            deadline = threading.Timer(20, peer.kill); deadline.start()
            with (args.evidence / 'observations.jsonl').open('w') as capture:
                for line in observation_lines(peer):
                    capture.write(line); capture.flush()
                    row = json.loads(line); records.append(row)
                    assert not (root / '.agent-workflow/STOP').exists()
                    if row['event'] == 'welcome' and len(published) < 2:
                        assert not row['desk'] and not row['caps'] & 16
                        ordinal = sum(r['event'] == 'welcome' for r in records)
                        assert ordinal == len(published) + 1
                        published.append(request('notifications', dict(type='info', title='Synthetic baseline container', body='', ttlMs=500),
                                                 trigger_welcome=ordinal))
            code = peer.wait(timeout=5)
        assert code == 0
        expected = verify(records, published)
        result = dict(passed=True, baseline=BASELINE, peerSha256=hashlib.sha256(binary.read_bytes()).hexdigest(),
                      publishedSequences=expected, negotiatedDesk=False, chronologicalReconnectTransferVerified=True,
                      publicationWelcomeOrdinals=[r['triggerWelcome'] for r in exchanges if r['path'] == 'notifications'],
                      testedLayer='Immutable original LinkSession/NotifyQueue with actual running relay over TCP',
                      physicalVerified=False, productAccepted=False)
        (args.evidence / 'verified.json').write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps(result))
    finally:
        if deadline:
            deadline.cancel()
        if peer is not None and peer.poll() is None:
            peer.kill(); peer.wait(timeout=5)
        with socket.socket() as probe:
            probe.settimeout(1)
            still_running = probe.connect_ex(('127.0.0.1', args.http_port)) == 0
        (args.evidence / 'cleanup.json').write_text(json.dumps(dict(ownedPeerReaped=peer is None or peer.poll() is not None,
                                                                 callerOwnedRelayLeftRunning=still_running)) + '\n')
        assert still_running, 'Caller-owned relay unexpectedly closed'


if __name__ == '__main__':
    main()
