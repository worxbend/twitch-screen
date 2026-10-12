#!/usr/bin/env python3
"""Run actual relay owners and firmware on isolated loopback, without enabling production DESK.

Copies only relay source/resources/tests/build inputs into private scratch. The
explicit internal profile opt-in is not default-production or hardware acceptance.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
from runtime_lifecycle import verify_report_phases
from runtime_observations import verify_application_sessions


def verify_terminal(records) -> dict[str, object]:
    """Verify every observation for this explicitly published finite fixture."""
    verify_report_phases(records)
    verify_application_sessions(records)
    ready = [r for r in records if r['event'] == 'desk_ready']
    reports = [r for r in records if r['event'] == 'device_report_written']
    started = [r for r in records if r['event'] == 'started']
    summary = next(r for r in records if r['event'] == 'summary')
    assert len(ready) == 2 and ready[0]['epoch'] == ready[1]['epoch'] != '0000000000000000'
    assert ready[0]['connection'] != ready[1]['connection']
    expected = [1, 4, 5, 6, 7, 8, 9, 11]
    assert [r['seq'] for r in started] == expected
    assert all(r['epoch'] == ready[0]['epoch'] and r['origin'] == 0 for r in started)
    assert all(summary[k] == len(expected) for k in ('started', 'holds', 'exits', 'completed'))
    assert summary['deskReadyObserved'] and summary['success']
    groups = [[r for r in reports if r['connection'] == session['connection']] for session in ready]
    assert sum(map(len, groups)) == len(reports) and all(groups)
    for session, rows in zip(ready, groups):
        assert all(r['epoch'] == session['epoch'] and r['pending'] == len(r['pendingEntries']) <= 8
                   and r['enqueuedSeq'] <= r['processedSeq'] and r['refusedTotal'] == 0 for r in rows)
        assert all(b['revision'] > a['revision'] and b['observedDeviceMs'] >= a['observedDeviceMs']
                   and b['processedSeq'] >= a['processedSeq'] and b['enqueuedSeq'] >= a['enqueuedSeq']
                   and b['grantTotal'] >= a['grantTotal'] for a, b in zip(rows, rows[1:]))
        for r in rows:
            seqs = [entry['seq'] for entry in r['pendingEntries']]
            assert seqs == sorted(set(seqs))
            assert all(entry['epoch'] == session['epoch'] and entry['origin'] == (1 if entry['seq'] == 3 else 0)
                       for entry in r['pendingEntries'])
            assert not r['phase'] or r['activeSeq'] in expected
    first, second = groups
    assert first[0]['pending'] == first[0]['phase'] == 0 and first[0]['grantTotal'] == 8
    assert any(r['pending'] == 8 and r['activeSeq'] == 1 for r in first)
    active_seen = 0
    for r in first:
        assert 0 <= r['enqueuedSeq'] <= 9 and r['processedSeq'] <= 10
        if r['phase']:
            assert r['activeSeq'] == 1 and r['activeOrigin'] == 0 and r['activeEpoch'] == ready[0]['epoch']
            active_seen = 1
        assert r['grantTotal'] == 8 + r['enqueuedSeq'] - r['pending']
        assert r['expiredTotal'] in (0, 1) and r['suppressedTotal'] in (0, 1)
        assert r['enqueuedSeq'] - r['pending'] == active_seen + r['expiredTotal'] + r['suppressedTotal']
        assert all(1 <= entry['seq'] <= r['enqueuedSeq'] and entry['seq'] != (r['activeSeq'] if r['phase'] else 0)
                   for entry in r['pendingEntries'])
        assert not r['expiredTotal'] or all(e['seq'] != 2 for e in r['pendingEntries'])
        assert not r['suppressedTotal'] or all(e['seq'] != 3 for e in r['pendingEntries'])
    baseline = second[0]
    assert baseline['pending'] == 6 and baseline['grantTotal'] == 2
    assert baseline['activeSeq'] == 1 and baseline['activeEpoch'] == ready[0]['epoch']
    assert baseline['enqueuedSeq'] == 9 and baseline['processedSeq'] == 10
    assert [e['seq'] for e in baseline['pendingEntries']] == list(range(4, 10))
    dequeued = 0
    for r in second:
        assert r['enqueuedSeq'] in (9, 11) and r['processedSeq'] in (10, 11)
        new_admitted = int(r['enqueuedSeq'] == 11)
        if r['phase']:
            assert r['activeOrigin'] == 0 and r['activeEpoch'] == ready[0]['epoch']
            if r['activeSeq'] != 1:
                current = expected[1:].index(r['activeSeq']) + 1
                assert dequeued <= current <= dequeued + 1
                dequeued = current
        else:
            assert r['activeSeq'] == 0
        assert r['grantTotal'] == 2 + dequeued
        assert r['grantTotal'] == 2 + 6 + new_admitted - r['pending']
        assert r['expiredTotal'] == r['suppressedTotal'] == 1
        wanted = expected[1:][dequeued:6 + new_admitted]
        assert [e['seq'] for e in r['pendingEntries']] == wanted
    assert dequeued == 7
    assert second[-1]['pending'] == second[-1]['phase'] == 0
    assert second[-1]['processedSeq'] == second[-1]['enqueuedSeq'] == 11 and second[-1]['grantTotal'] == 9
    # This fixture never refreshes CPU after its original deadline. One stale
    # observation cannot excuse a later transient resurrection during exit.
    assert all((r['baseScreen'], r['reason']) == (0, 5) for r in second), 'Expired CPU reappeared in a ready report'
    second_connection = ready[1]['connection']
    views = [r for r in records if r['event'] == 'application_view' and r['connection'] == second_connection]
    assert views and any(r['covered'] for r in views) and any(not r['covered'] for r in views), 'Missing covered/reveal application observations'
    ready_index = next(i for i, r in enumerate(records) if r['event'] == 'desk_ready' and r['connection'] == second_connection)
    final_report_index = max(i for i, r in enumerate(records) if r['event'] == 'device_report_written' and r['connection'] == second_connection)
    for i, r in enumerate(records):
        if r['event'] != 'application_view' or r['connection'] != second_connection:
            continue
        assert not r['cpuVisible'] and not r['weatherVisible'], 'Expired telemetry root visible at application boundary'
        state = (r['baseScreen'], r['reason'])
        if state == (0, 2):  # Explicit synchronization is allowed only before readiness.
            assert i < ready_index, 'Ready view regressed to synchronizing'
        elif state == (1, 1):  # Explicit link loss is allowed only after this finite delivery.
            assert i > final_report_index, 'Unexpected link loss during ready delivery'
        else:
            assert state == (0, 5), 'Expired CPU reappeared at application reveal'
    assert sum(not r['covered'] and (r['baseScreen'], r['reason']) == (0, 5)
               for r in views) == summary['exits'], 'Missing a stale-data reveal boundary'
    return dict(passed=True, readyConnections=2, completedCards=len(expected), socketWrittenReports=len(reports),
                expiredPending=1, suppressedPending=1, independentResumeCursors=True,
                everyReportCreditConserved=True, everyReadyReportStale=True,
                applicationViewsChecked=len(views),
                uncoveredViewsChecked=sum(not r['covered'] for r in views),
                physicalVerified=False, productionAdvertisementVerified=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binary', required=True, type=Path)
    parser.add_argument('--evidence', required=True, type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    assert not (root / '.agent-workflow/STOP').exists(), 'STOP present'
    assert args.binary.is_file(), 'Build native-runtime-peer first'
    os.umask(0o077)
    args.evidence.mkdir(parents=True, exist_ok=True)
    source = root / 'twitch-screen-relay'
    scratch = Path(tempfile.mkdtemp(prefix='firmware-owner-terminal-', dir=os.environ['TMPDIR']))
    relay = scratch / 'relay'
    relay.mkdir()
    for name in ('src', 'resources'):
        shutil.copytree(source / name, relay / name)
    # Compile only the actual socket helper and this owned integration main.
    # Peer unit suites run separately; unrelated concurrent edits cannot turn
    # this focused owner gate into a false full-relay test claim.
    helper = Path('test/src/twitchscreen/relay/device/TestDevice.scala')
    (relay / helper).parent.mkdir(parents=True)
    shutil.copy2(source / helper, relay / helper)
    for name in ('build.mill', 'mill', '.scalafmt.conf'):
        shutil.copy2(source / name, relay / name)
    manifest = []
    for path in sorted(relay.rglob('*')):
        if path.is_file():
            relative = path.relative_to(relay)
            assert path.read_bytes() == (source / relative).read_bytes(), 'Concurrent source change during snapshot'
            manifest.append(dict(path=str(relative), sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
    (args.evidence / 'source-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    shutil.copy2(Path(__file__).with_name('RelayTerminalPairing.scala'),
                 relay / 'test/src/twitchscreen/relay/device/RelayTerminalPairing.scala')
    command = ['./mill', '--no-server', '-j', '1', 'test.runMain',
               'twitchscreen.relay.device.RelayTerminalPairing', str(args.binary.resolve()), str(args.evidence.resolve())]
    env = {k: v for k, v in os.environ.items() if not k.startswith(('RELAY_', 'FIRMWARE_PEER_', 'OTEL_'))}
    with (args.evidence / 'owner.log').open('w') as log:
        result = subprocess.run(command, cwd=relay, env=env, stdout=log, stderr=subprocess.STDOUT, timeout=500)
    assert result.returncode == 0, ('actual owner execution', result.returncode)
    records = [json.loads(line) for line in (args.evidence / 'native-observations.jsonl').read_text().splitlines()]
    verdict = verify_terminal(records)
    frame = bytes.fromhex((args.evidence / 'remote-final-report.hex').read_text().strip())
    layout = next(r for r in json.loads((root / 'protocol/desk-v1-layouts.json').read_text())['records']
                  if r['name'] == 'DEVICE_REPORT')
    assert len(frame) == layout['payloadBytes'] + 8 and frame[3] == layout['code']
    decoded = {field['name']: int.from_bytes(frame[8 + field['offset']:8 + field['offset'] + field['width']], 'little')
               for field in layout['fields'] if field['offset'] < 100}
    final = next(r for r in reversed(records) if r['event'] == 'device_report_written')
    for raw_key, local_key in (('reportRevision', 'revision'), ('grantTotal', 'grantTotal'),
                               ('processedSeq', 'processedSeq'), ('enqueuedSeq', 'enqueuedSeq'),
                               ('activePhase', 'phase'), ('pendingCount', 'pending'),
                               ('baseScreen', 'baseScreen'), ('reason', 'reason'),
                               ('expiredTotal', 'expiredTotal'), ('suppressedTotal', 'suppressedTotal'),
                               ('refusedTotal', 'refusedTotal')):
        assert decoded[raw_key] == final[local_key], ('Remote accepted report differs', raw_key)
    assert f"{decoded['relayEpoch']:016x}" == final['epoch']
    assert f"{decoded['connectionId']:016x}" == final['connection']
    verdict['remoteFinalReportAccepted'] = True
    owner = json.loads((args.evidence / 'owner-result.json').read_text())
    assert owner['ownedChildReaped'] and owner['ownedListenerClosed'] and owner['internalProfileOptIn']
    verdict.update(owner=owner, scratchSource=str(relay), sourceFiles=len(manifest))
    (args.evidence / 'verified.json').write_text(json.dumps(verdict, indent=2) + '\n')
    print(json.dumps(verdict, indent=2))


if __name__ == '__main__':
    main()
