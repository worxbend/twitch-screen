"""Derived regression snapshots, NOT recordings or runtime execution evidence.

Timing summaries below are reduced from three investigated restart scenarios.
Identities and all other verifier inputs are synthetic; no private capture paths
or whole captures belong in this repository.
"""
import copy
import unittest
import test_production_restart_verdict as verdict
from test_production_restart import verify

ZERO = '0000000000000000'
OLD, NEW, CONNECTION = '0000000000000011', '0000000000000022', '0000000000000001'
TIMINGS = {
    'baseline': ((169, 541, 12543, 12839), (12839, 13194, 13298, 13582),
                 (13582, 13942, 14046, 14329), (14329, 14689, 14793, 15079), 7423),
    'delay5': ((166, 538, 12541, 12829), (12829, 13188, 13292, 13579),
               (13579, 13938, 14041, 14324), (15523, 15890, 15994, 16276), 15433),
    'delay12': ((170, 541, 12542, 12828), (12828, 13192, 13296, 13594),
                (13594, 13953, 14057, 14339), (31540, 31905, 32009, 32323), 31439),
}

# Synthetic boundary probes derived from baseline callbacks, not actual captures.
for scenario, at in (('retained-second', 13199), ('retained-second-exit', 13399),
                     ('retained-fourth', 13947)):
    TIMINGS[scenario] = TIMINGS['baseline'][:4] + (at,)


def snapshot(name):
    """Build small synthetic inputs with the investigated callback boundaries."""
    timings = TIMINGS[name]
    identities = [(OLD, 1), (OLD, 2), (OLD, 4), (NEW, 1)]
    rows = []
    revision = {OLD: 0, NEW: 0}

    def view(epoch, covered, *, disconnected=False):
        row = dict(event='application_view', epoch=epoch, connection=ZERO if disconnected else CONNECTION,
                   baseScreen=1 if disconnected else (4 if epoch == OLD else 0),
                   reason=1 if disconnected else (0 if epoch == OLD else 4), covered=covered,
                   cpuVisible=epoch == OLD and not covered and not disconnected, weatherVisible=False,
                   displayRevision=2 if epoch == OLD else 1, cpuRevision=2 if epoch == OLD else 1)
        numeric = dict(event='application_telemetry', **{k: row[k] for k in
                       ('epoch', 'connection', 'displayRevision', 'cpuRevision')},
                       cpuPresence=int(row['cpuVisible']), weatherPresence=0,
                       cpuBasisPoints=0, memoryBasisPoints=0, temperatureCenti=0)
        return [row, numeric]

    def report(epoch, at, count, expired):
        active = next(((identity, t) for identity, t in zip(identities, timings[:4])
                       if t[0] <= at < t[3]), None)
        admitted = ([(OLD, seq) for seq in range(1, count + 1)] if epoch == OLD else
                    [(OLD, 2), (OLD, 4)] + ([(NEW, 1)] if count else []))
        pending = [identity for identity in admitted if not (identity == (OLD, 3) and expired)
                   and not any(identity == card and t[0] <= at for card, t in zip(identities, timings[:4]))]
        revision[epoch] += 1
        identity, phase = (ZERO, 0), 0
        if active:
            identity, t = active
            phase = 1 if at < t[1] else (2 if at < t[2] else 3)
        return dict(event='device_report_written', epoch=epoch, connection=CONNECTION,
                    revision=revision[epoch], observedDeviceMs=at, observedRelayLowerMs=at,
                    activeEpoch=identity[0], activeSeq=identity[1], activeOrigin=0, phase=phase,
                    pending=len(pending), pendingEntries=[dict(epoch=e, seq=s, origin=0) for e, s in pending],
                    grantTotal=8 + count - len(pending), processedSeq=count, enqueuedSeq=count,
                    expiredTotal=expired, suppressedTotal=0, refusedTotal=0,
                    displayRevision=2 if epoch == OLD else 1, sourceGeneration=1, twitchEpisode=1,
                    cpuRevision=2 if epoch == OLD else 1, weatherRevision=1,
                    baseScreen=4 if epoch == OLD else 0, reason=0 if epoch == OLD else 4)

    rows += view(OLD, False)
    rows.append(dict(event='desk_ready', epoch=OLD, connection=CONNECTION, grantTotal=8))
    rows.append(report(OLD, 78, 0, 0))
    events = []
    for (epoch, seq), t in zip(identities, timings[:4]):
        events.append((t[0], 2, dict(event='started', epoch=epoch, seq=seq, origin=0, replay=False,
                                  atMs=t[0], holdMs=12000 if (epoch, seq) == (OLD, 1) else 100)))
        for phase, at in zip((2, 3, 0), t[1:]):
            events.append((at, 0 if phase == 0 else 3,
                           dict(event='card_lifecycle', epoch=epoch, seq=seq, phase=phase, atMs=at)))
    events += [(278, 4, report(OLD, 278, 4, 0)),
               (timings[4], 1, dict(event='desk_ready', epoch=NEW, connection=CONNECTION,
                                   grantTotal=8 - sum(t[0] > timings[4] for t in timings[1:3])))]
    for at, _, event in sorted(events, key=lambda x: (x[0], x[1])):
        if event['event'] == 'desk_ready':
            rows += view(NEW, any(t[0] <= at < t[2] for t in timings[:4]))
            rows.append(event)
            rows.append(report(NEW, at, 0, 1))
        elif event['event'] == 'started':
            rows.append(event)
            rows += view(NEW if at > timings[4] else OLD, True, disconnected=at < timings[4] and event['seq'] != 1)
        elif event['event'] == 'card_lifecycle' and event['phase'] == 3:
            rows.append(event)
            rows += view(NEW if at > timings[4] else OLD, False, disconnected=at < timings[4])
        else:
            rows.append(event)
    rows.append(report(NEW, timings[3][3] + 21, 1, 1))
    rows.append(dict(event='summary', success=True, started=4, holds=4, exits=4, completed=4))
    monitoring = dict(relayEpoch=OLD, revision=1, desired=dict(mode='disabled'), effective={},
                      sourceGeneration=1, transition={})
    fixture = dict(published=[dict(relayEpoch=OLD, seq=s) for s in range(1, 5)],
                   successor=dict(relayEpoch=NEW, seq=1), originalCpu=dict(status='fresh'),
                   restartedCpu=dict(status='missing', data=None), displayBefore=dict(revision=2),
                   displayAfter=dict(revision=1, preferredDashboard='cpu', twitchDisplayEnabled=False),
                   stopExitCode=143, outageMs=7524 if name == 'baseline' else 12530,
                   rejectedLive=dict(code='configuration_missing'), monitorBefore=monitoring,
                   monitorAfter=copy.deepcopy(monitoring))
    return rows, fixture


class DerivedRestartTests(unittest.TestCase):
    def test_callback_derived_restart_scenarios(self):
        for name in TIMINGS:
            with self.subTest(name=name):
                records, fixture = snapshot(name)
                self.assertTrue(verify(records, fixture)['passed'])

    def test_strict_active_path_requires_observed_overlap(self):
        records, fixture = snapshot('baseline')
        self.assertTrue(verify(records, fixture, restart_mode='active-overlap')['activeOverlapVerified'])
        for name in ('delay5', 'delay12'):
            records, fixture = snapshot(name)
            with self.assertRaisesRegex(AssertionError, 'Active-overlap precondition'):
                verify(records, fixture, restart_mode='active-overlap')

    def test_strict_delayed_path_requires_completed_originals(self):
        for name in ('delay5', 'delay12'):
            records, fixture = snapshot(name)
            result = verify(records, fixture, restart_mode='completed-originals')
            self.assertTrue(result['originalsCompletedBeforeRestart'])
            self.assertEqual(result['revealBoundariesChecked'], 2)
        records, fixture = snapshot('baseline')
        with self.assertRaisesRegex(AssertionError, 'Delayed-completion precondition'):
            verify(records, fixture, restart_mode='completed-originals')


class DerivedBaselineVerdicts(verdict.RestartVerdictTests):
    """Run the same mutation contract on explicitly derived unit inputs."""
    scenario = 'baseline'

    def setUp(self):
        self.records, self.fixture = snapshot(self.scenario)
        self.ready = [r for r in self.records if r['event'] == 'desk_ready']
        self.new = self.ready[1]['epoch']


class DerivedDelay5Verdicts(DerivedBaselineVerdicts):
    scenario = 'delay5'


class DerivedDelay12Verdicts(DerivedBaselineVerdicts):
    scenario = 'delay12'


if __name__ == '__main__':
    unittest.main(verbosity=2)
