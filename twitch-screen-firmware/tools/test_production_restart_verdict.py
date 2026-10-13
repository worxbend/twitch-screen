#!/usr/bin/env python3
"""Reject mutations of actual default-Main restart evidence; not runtime output."""
import argparse
import copy
import json
from pathlib import Path
import subprocess
import unittest
from test_production_restart import verify

RECORDS = FIXTURE = None


class RestartVerdictTests(unittest.TestCase):
    def setUp(self):
        if RECORDS is None:
            self.skipTest('Explicit --evidence from actual production restart required')
        assert RECORDS is not None and FIXTURE is not None
        self.records = copy.deepcopy(RECORDS)
        self.fixture = copy.deepcopy(FIXTURE)
        self.ready = [r for r in self.records if r['event'] == 'desk_ready']
        self.new = self.ready[1]['epoch']

    def report(self):
        return next(r for r in self.records if r['event'] == 'device_report_written' and r['epoch'] == self.new)

    def pending_report(self):
        rows = [r for r in self.records if r['event'] == 'device_report_written' and r['pending'] >= 2]
        # Prefer retained entries carried by the new epoch. In delayed runs
        # they already completed, so mutate their original admission evidence.
        return next((r for r in rows if r['epoch'] == self.new), rows[0])

    def active_report(self):
        rows = [r for r in self.records if r['event'] == 'device_report_written' and r['phase']
                and r['activeEpoch'] == self.ready[0]['epoch']]
        return next((r for r in rows if r['epoch'] == self.new), rows[0])

    def view(self):
        return next(r for r in self.records if r['event'] == 'application_view' and r['epoch'] == self.new and r['reason'] == 4 and not r['covered'])

    def reject(self):
        with self.assertRaises((AssertionError, ValueError, KeyError)):
            verify(self.records, self.fixture)

    def test_unmodified_inputs_pass(self):
        self.assertTrue(verify(self.records, self.fixture)['passed'])

    def test_expired_pending_card_started_fails(self):
        started = [r for r in self.records if r['event'] == 'started']
        started[1]['seq'] = self.fixture['published'][2]['seq']
        self.reject()

    def test_old_epoch_cursor_reuse_fails(self):
        self.report()['processedSeq'] = 4
        self.reject()

    def test_retained_active_relabel_fails(self):
        self.active_report()['activeEpoch'] = self.new
        self.reject()

    def test_pending_identity_relabel_fails(self):
        self.pending_report()['pendingEntries'][0]['epoch'] = self.new
        self.reject()

    def test_duplicated_pending_identity_fails(self):
        report = self.pending_report()
        report['pendingEntries'][1] = copy.deepcopy(report['pendingEntries'][0])
        self.reject()

    def test_wrong_remaining_pending_with_conserved_credit_fails(self):
        report = self.pending_report()
        report['pendingEntries'].pop()
        report['pending'] -= 1
        report['grantTotal'] += 1
        self.reject()

    def test_idle_to_active_identity_resurrection_fails(self):
        report = next(r for r in reversed(self.records) if r['event'] == 'device_report_written' and not r['phase'])
        report.update(activeEpoch=self.ready[0]['epoch'], activeSeq=self.fixture['published'][0]['seq'])
        self.reject()

    def test_completed_identity_reappears_as_active_fails(self):
        report = next(r for r in reversed(self.records) if r['event'] == 'device_report_written' and not r['phase'])
        report.update(phase=2, activeEpoch=self.ready[0]['epoch'], activeSeq=self.fixture['published'][0]['seq'])
        self.reject()

    def test_restart_credit_overgrant_fails(self):
        self.report()['grantTotal'] += 1
        self.reject()

    def test_expiry_counter_loss_fails(self):
        self.report()['expiredTotal'] = 0
        self.reject()

    def test_fresh_epoch_control_fence_regression_fails(self):
        reports = [r for r in self.records if r['event'] == 'device_report_written']
        reports[-1]['sourceGeneration'] = 0
        self.reject()

    def test_cached_telemetry_report_resurrection_fails(self):
        reports = [r for r in self.records if r['event'] == 'device_report_written']
        reports[-1].update(baseScreen=4, reason=0, cpuRevision=2)
        self.reject()

    def test_cached_telemetry_first_reveal_fails(self):
        self.view().update(cpuVisible=True, baseScreen=4, reason=0)
        self.reject()

    def test_numeric_view_epoch_mismatch_fails(self):
        view = self.view()
        position = next(i for i, r in enumerate(self.records) if r is view)
        self.records[position + 1]['epoch'] = self.ready[0]['epoch']
        self.reject()

    def test_view_epoch_reversion_with_matching_numeric_fails(self):
        view = self.view()
        position = next(i for i, r in enumerate(self.records) if r is view)
        view['epoch'] = self.records[position + 1]['epoch'] = self.ready[0]['epoch']
        self.reject()

    def test_cached_numeric_presence_after_restart_fails(self):
        view = self.view()
        position = next(i for i, r in enumerate(self.records) if r is view)
        self.records[position + 1]['cpuPresence'] = 1
        self.reject()

    def test_missing_reveal_fails(self):
        self.view()['covered'] = True
        self.reject()

    def test_shortened_original_hold_fails(self):
        self.records[next(i for i, r in enumerate(self.records) if r['event'] == 'started')]['holdMs'] -= 1
        self.reject()

    def test_shortened_original_callback_hold_fails(self):
        hold = next(r for r in self.records if r['event'] == 'card_lifecycle' and r['phase'] == 2)
        exit_card = next(r for r in self.records if r['event'] == 'card_lifecycle' and r['phase'] == 3)
        exit_card['atMs'] = hold['atMs'] + 11999
        self.reject()

    def test_unchanged_epoch_claim_fails(self):
        assert self.ready is not None
        self.ready[1]['epoch'] = self.ready[0]['epoch']
        self.reject()

    def test_rejected_live_mutates_monitoring_fails(self):
        self.fixture['monitorAfter']['sourceGeneration'] += 1
        self.reject()

    def test_wrong_rejected_live_reason_fails(self):
        self.fixture['rejectedLive']['code'] = 'different'
        self.reject()

    def test_persisted_intent_changed_fails(self):
        self.fixture['displayAfter']['preferredDashboard'] = 'generic'
        self.reject()


class OwnershipTests(unittest.TestCase):
    def test_invalid_restart_delays_rejected_before_inputs(self):
        for delay in ('-1', '15.1', 'nan', 'inf'):
            with self.subTest(delay=delay):
                result = subprocess.run(['python3', str(Path(__file__).with_name('test_production_restart.py')),
                                         '--assembly', '/unused', '--binary', '/unused', '--evidence', '/unused',
                                         '--test-owned', '--restart-delay-seconds', delay],
                                        capture_output=True, text=True, timeout=5)
                self.assertEqual(result.returncode, 2)
                self.assertIn('finite and in 0..15', result.stderr)

    def test_restart_requires_disposable_ownership_before_inputs(self):
        result = subprocess.run(['python3', str(Path(__file__).with_name('test_production_restart.py')),
                                 '--assembly', '/unused', '--binary', '/unused', '--evidence', '/unused'],
                                capture_output=True, text=True, timeout=5)
        self.assertEqual(result.returncode, 2)
        self.assertIn('--test-owned', result.stderr)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence', type=Path)
    args, extra = parser.parse_known_args()
    if args.evidence:
        RECORDS = [json.loads(line) for line in (args.evidence / 'observations.jsonl').read_text().splitlines()]
        FIXTURE = json.loads((args.evidence / 'fixture.json').read_text())
    unittest.main(argv=[__file__] + extra, verbosity=2)
