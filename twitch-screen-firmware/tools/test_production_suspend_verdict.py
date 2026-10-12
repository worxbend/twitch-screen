#!/usr/bin/env python3
"""Fail-closed mutations of actual production process-suspension evidence."""
import argparse
import copy
import json
from pathlib import Path
import unittest
from test_production_suspend import verify

EVIDENCE = None


class SuspendVerdictTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if EVIDENCE is None:
            raise unittest.SkipTest('Supply --evidence from an actual process-suspension run')
        cls.records = [json.loads(line) for line in (EVIDENCE / 'observations.jsonl').read_text().splitlines()]
        cls.fixture = json.loads((EVIDENCE / 'fixture.json').read_text())

    def rejected(self, change):
        records, fixture = copy.deepcopy(self.records), copy.deepcopy(self.fixture)
        change(records, fixture)
        with self.assertRaises((AssertionError, KeyError, StopIteration)):
            verify(records, fixture)

    def test_actual_capture(self):
        self.assertTrue(verify(self.records, self.fixture)['passed'])

    def test_pause_below_gap_threshold(self):
        self.rejected(lambda rows, f: f.update(pausedMs=59999))

    def test_not_actually_stopped(self):
        self.rejected(lambda rows, f: f.update(stoppedSignal=0))

    def test_waiting_expiry_renewal(self):
        def change(rows, f):
            next(r for r in rows if r['event'] == 'started' and r['seq'] == f['published'][2]['seq'])['seq'] = f['published'][1]['seq']
        self.rejected(change)

    def test_active_hold_shortened(self):
        def change(rows, f):
            next(r for r in rows if r['event'] == 'started')['holdMs'] = 100
        self.rejected(change)

    def test_active_callback_cancelled(self):
        def change(rows, f):
            rows.remove(next(r for r in rows if r['event'] == 'card_lifecycle' and r['phase'] == 3))
        self.rejected(change)

    def test_late_callback_recovery(self):
        def change(rows, f):
            next(r for r in rows if r['event'] == 'card_lifecycle' and r['phase'] == 3)['atMs'] += 10000
        self.rejected(change)

    def test_ordinary_successor_hold_shortened(self):
        def change(rows, f):
            next(r for r in rows if r['event'] == 'started' and r['seq'] == f['successor']['seq'])['holdMs'] = 0
        self.rejected(change)

    def test_one_gap_view_telemetry_flash(self):
        def change(rows, f):
            index = next(i for i, r in enumerate(rows) if r['event'] == 'link_down')
            r = next(r for r in rows[index:] if r['event'] == 'application_view')
            r.update(baseScreen=4, reason=0, cpuVisible=True)
        self.rejected(change)

    def test_expired_view_before_transport_loss(self):
        def change(rows, f):
            boundary = next(i for i, r in enumerate(rows) if r['event'] == 'link_down')
            index = next(i for i, r in enumerate(rows[:boundary])
                         if r['event'] == 'application_view' and r['cpuVisible'])
            view, numeric = copy.deepcopy(rows[index:index + 2])
            numeric['atMs'] = rows[boundary]['atMs']
            rows[boundary:boundary] = [view, numeric]
        self.rejected(change)

    def test_paused_card_completion_overlaps_successor(self):
        def change(rows, f):
            first = f['published'][0]
            next(r for r in rows if r['event'] == 'card_lifecycle' and r['phase'] == 0
                 and (r['epoch'], r['seq']) == (first['relayEpoch'], first['seq']))['atMs'] += 1500
        self.rejected(change)

    def test_one_reconnected_report_telemetry_flash(self):
        def change(rows, f):
            second = [r for r in rows if r['event'] == 'desk_ready'][1]['connection']
            next(r for r in rows if r['event'] == 'device_report_written' and r['connection'] == second).update(baseScreen=3, reason=0)
        self.rejected(change)

    def test_missing_independent_resume_timing(self):
        self.rejected(lambda rows, f: f.pop('pauseTiming'))

    def test_resume_anchor_is_not_a_teardown_marker(self):
        def change(rows, f):
            f['pauseTiming']['anchorRecordIndex'] = next(i for i, r in enumerate(rows) if r['event'] == 'link_down')
        self.rejected(change)

    def test_resume_anchor_device_time_is_not_rewritten(self):
        self.rejected(lambda rows, f: f['pauseTiming'].update(anchorDeviceMs=f['pauseTiming']['anchorDeviceMs'] + 1))

    def test_resume_timing_is_bounded(self):
        def change(rows, f):
            f['pauseTiming']['launchBeforeNs'] -= 2000000000
        self.rejected(change)

    def test_pause_duration_matches_parent_clock(self):
        self.rejected(lambda rows, f: f.update(pausedMs=f['pausedMs'] + 1))

    def test_stale_hidden_view_before_loss_is_valid(self):
        rows = copy.deepcopy(self.records)
        boundary = next(i for i, r in enumerate(rows) if r['event'] == 'link_down')
        index = next(i for i, r in enumerate(rows[boundary:], boundary)
                     if r['event'] == 'application_view' and r['covered'])
        view, numeric = copy.deepcopy(rows[index:index + 2])
        # The disconnected adapter may keep diagnostic values cached but hidden.
        self.assertGreater(numeric['cpuBasisPoints'], 0)
        rows[boundary:boundary] = [view, numeric]
        self.assertTrue(verify(rows, self.fixture)['passed'])

    def test_no_view_may_escape_through_delayed_loss_marker(self):
        def change(rows, f):
            boundary = next(i for i, r in enumerate(rows) if r['event'] == 'link_down')
            index = next(i for i, r in enumerate(rows[:boundary])
                         if r['event'] == 'application_view' and r['cpuVisible'])
            view, numeric = copy.deepcopy(rows[index:index + 2])
            timing = f['pauseTiming']
            numeric['atMs'] = timing['anchorDeviceMs'] + f['pausedMs']
            rows[boundary:boundary] = [view, numeric]
        self.rejected(change)

    def test_missing_transport_loss(self):
        self.rejected(lambda rows, f: rows.__setitem__(slice(None), [r for r in rows if r['event'] != 'link_down']))

    def test_credit_leak(self):
        def change(rows, f):
            [r for r in rows if r['event'] == 'device_report_written'][-2]['grantTotal'] += 1
        self.rejected(change)

    def test_missing_expiration_counter(self):
        def change(rows, f):
            [r for r in rows if r['event'] == 'device_report_written'][-1]['expiredTotal'] = 0
        self.rejected(change)

    def test_old_source_report(self):
        def change(rows, f):
            [r for r in rows if r['event'] == 'device_report_written'][-2]['sourceGeneration'] = 1
        self.rejected(change)

    def test_old_control_report(self):
        def change(rows, f):
            [r for r in rows if r['event'] == 'device_report_written'][-2]['displayRevision'] = 2
        self.rejected(change)

    def test_old_episode_report(self):
        def change(rows, f):
            [r for r in rows if r['event'] == 'device_report_written'][-2]['twitchEpisode'] = 1
        self.rejected(change)

    def test_foreign_epoch_report(self):
        def change(rows, f):
            [r for r in rows if r['event'] == 'device_report_written'][-1]['epoch'] = '0123456789abcdef'
        self.rejected(change)

    def test_cpu_renewed_at_relay(self):
        self.rejected(lambda rows, f: f['finalCpu'].update(status='fresh'))

    def test_weather_renewed_at_relay(self):
        self.rejected(lambda rows, f: f['finalWeather'].update(revision=99))

    def test_reconnect_numeric_renewal(self):
        def change(rows, f):
            second = [r for r in rows if r['event'] == 'desk_ready'][1]['connection']
            next(r for r in rows if r['event'] == 'application_telemetry' and r['connection'] == second)['cpuBasisPoints'] = 3500
        self.rejected(change)

    def test_gap_reason_cannot_be_generic_socket_loss(self):
        def change(rows, f):
            next(r for r in rows if r['event'] == 'link_down')['deskDeadline'] = False
        self.rejected(change)

    def test_failed_source_teardown_not_ready(self):
        self.rejected(lambda rows, f: f['monitorReadback'].update(transition='failed'))

    def test_episode_self_report_is_not_authority(self):
        def change(rows, f):
            f['finalEpisode'] += 1
            second = [r for r in rows if r['event'] == 'desk_ready'][1]['connection']
            for r in rows:
                if r['event'] == 'device_report_written' and r['connection'] == second:
                    r['twitchEpisode'] = f['finalEpisode']
        self.rejected(change)

    def test_unpublished_enqueue_cursor(self):
        def change(rows, f):
            [r for r in rows if r['event'] == 'device_report_written'][-2].update(enqueuedSeq=99, processedSeq=99)
        self.rejected(change)

    def test_progress_regression(self):
        def change(rows, f):
            [r for r in rows if r['event'] == 'device_report_written'][-2]['processedSeq'] -= 1
        self.rejected(change)

    def test_missing_reveal(self):
        def change(rows, f):
            i = next(i for i, r in enumerate(rows) if r['event'] == 'application_view' and not r['covered'] and i > 20)
            del rows[i:i + 2]
        self.rejected(change)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence', type=Path)
    args, remaining = parser.parse_known_args()
    EVIDENCE = args.evidence.resolve() if args.evidence else None
    unittest.main(argv=[__file__] + remaining, verbosity=2)
