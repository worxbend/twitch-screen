#!/usr/bin/env python3
"""Verify the explicit hub-reset NativeDeskReconnect fixture from raw application observations.

This fixture publishes exactly two old-epoch and one new-epoch external cards,
without barriers or terminal skips. Its processed cursor therefore also counts
received current-connection events. Do not reuse that equation for arbitrary
production traces. This is internal-gated application evidence, not an automatic
clock detector/source-recreation or physical acceptance gate.
"""
import argparse
import json
from pathlib import Path


def verify(rows, state):
    old, new = state['oldEpoch'], state['newEpoch']
    assert old != new and int(old, 16) != 0 and int(new, 16) != 0
    assert state['intentPreserved'] and state['replayEmpty'] and state['latestSeq'] == 0
    assert state['cpuMissing'] and state['weatherMissing']
    ready = [row for row in rows if row.get('event') == 'desk_ready']
    assert len(ready) == 2 and [row['epoch'] for row in ready] == [old, new]
    assert ready[0]['connection'] != ready[1]['connection']
    expected = [(old, 1), (old, 2), (new, 1)]
    started = [row for row in rows if row.get('event') == 'started']
    assert [(row['epoch'], row['seq']) for row in started] == expected
    assert all(row['origin'] == 0 and not row['replay'] for row in started)
    reports = [row for row in rows if row.get('event') == 'device_report_written']
    assert reports
    sessions = {}
    phases = {identity: set() for identity in expected}
    for report in reports:
        epoch, connection = report['epoch'], report['connection']
        assert epoch in (old, new)
        assert connection == next(row['connection'] for row in ready if row['epoch'] == epoch)
        previous = sessions.get(connection)
        assert report['pending'] == len(report['pendingEntries']) <= 8
        assert 0 <= report['processedSeq'] <= (2 if epoch == old else 1)
        assert report['enqueuedSeq'] == report['processedSeq']
        assert report['grantTotal'] == 8 - report['pending'] + report['processedSeq']
        assert report['expiredTotal'] == report['suppressedTotal'] == report['refusedTotal'] == 0
        if previous:
            for field in ('revision', 'observedDeviceMs'):
                assert report[field] > previous[field]
            for field in ('grantTotal', 'processedSeq', 'enqueuedSeq', 'observedRelayLowerMs'):
                assert report[field] >= previous[field]
        else:
            assert report['revision'] == 1
        pending = [(entry['epoch'], entry['seq']) for entry in report['pendingEntries']]
        assert len(pending) == len(set(pending))
        assert all(identity in expected for identity in pending)
        assert all(entry['origin'] == 0 for entry in report['pendingEntries'])
        assert pending == sorted(pending, key=expected.index)
        if report['phase']:
            active = (report['activeEpoch'], report['activeSeq'])
            assert active in expected and active not in pending and report['remainingMs'] > 0
            assert report['activeOrigin'] == 0
            phases[active].add(report['phase'])
        else:
            assert report['activeSeq'] == report['remainingMs'] == 0
        if epoch == new:
            assert report['cpuRevision'] == report['weatherRevision'] == 1
            assert report['displayRevision'] == 1 and report['baseScreen'] == 0 and report['reason'] == 4
        sessions[connection] = report
    assert all(value == {1, 2, 3} for value in phases.values())
    old_reports = [row for row in reports if row['epoch'] == old]
    new_reports = [row for row in reports if row['epoch'] == new]
    first = new_reports[0]
    assert first['processedSeq'] == first['enqueuedSeq'] == 0 and first['grantTotal'] == 7
    assert (first['activeEpoch'], first['activeSeq']) == (old, 1)
    assert [(entry['epoch'], entry['seq']) for entry in first['pendingEntries']] == [(old, 2)]
    assert first['remainingMs'] < old_reports[-1]['remainingMs']
    assert new_reports[-1]['phase'] == new_reports[-1]['pending'] == 0 and new_reports[-1]['grantTotal'] == 9
    summaries = [row for row in rows if row.get('event') == 'summary']
    assert len(summaries) == 1
    summary = summaries[0]
    assert summary['success'] and summary['deskNegotiated'] and summary['deskReadyObserved']
    assert summary['greetings'] == 2
    assert all(summary[field] == 3 for field in ('started', 'holds', 'exits', 'completed'))
    assert summary['admittedClassic'] == summary['lastClassicSeq'] == 0
    views = [row for row in rows if row.get('event') == 'application_view']
    assert any(row['baseScreen'] == 1 and row['covered'] for row in views)
    assert not any(row['cpuVisible'] or row['weatherVisible'] for row in views if row['connection'] == ready[1]['connection'])
    return {'status': 'passed', 'readySessions': len(ready), 'completedCards': summary['completed'],
            'socketWrittenReports': len(reports), 'allReportsConserveFixtureCredit': True,
            'oldIdentitiesAndFifoPreserved': True, 'oldTelemetryInvalidated': True,
            'originalActiveRemainingTimeNotRenewed': True, 'automaticResetVerified': False,
            'physicalVerified': False, 'productAccepted': False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('evidence', type=Path)
    args = parser.parse_args()
    rows = [json.loads(line) for line in (args.evidence / 'native-observations.jsonl').read_text().splitlines()]
    state = json.loads((args.evidence / 'reset-state.json').read_text())
    result = verify(rows, state)
    (args.evidence / 'verdict.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(result))


if __name__ == '__main__':
    main()
