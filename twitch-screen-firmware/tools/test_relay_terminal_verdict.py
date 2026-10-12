#!/usr/bin/env python3
"""Negative verdict regressions based on real captured owner observations, not invented runtime output."""
import argparse
import copy
import json
from pathlib import Path
import unittest
from test_relay_terminal import verify_terminal

RECORDS = []


class TerminalVerdictTests(unittest.TestCase):
    def setUp(self):
        self.records = copy.deepcopy(RECORDS)
        self.ready = [r for r in self.records if r['event'] == 'desk_ready']
        self.reports = [r for r in self.records if r['event'] == 'device_report_written']
        self.second = [r for r in self.reports if r['connection'] == self.ready[1]['connection']]

    def rejected(self):
        with self.assertRaises((AssertionError, ValueError)):
            verify_terminal(self.records)

    def test_wrong_matching_view_numeric_epoch_fails(self):
        for row in self.records:
            if row['event'] in ('application_view', 'application_telemetry') and row['connection'] != '0000000000000000':
                row['epoch'] = 'ffffffffffffffff'
        self.rejected()

    def test_detached_numeric_epoch_fails(self):
        next(r for r in self.records if r['event'] == 'application_telemetry'
             and r['connection'] != '0000000000000000')['epoch'] = 'ffffffffffffffff'
        self.rejected()

    def test_actual_observations_pass(self):
        self.assertTrue(verify_terminal(self.records)['passed'])

    def test_phase_order_survives_reconnect(self):
        before = next(r for r in reversed(self.reports) if r['connection']==self.ready[0]['connection'] and r['phase'])
        before['phase'] = 3
        self.second[0]['phase'] = 2
        self.rejected()

    def test_hold_cannot_reverse_to_entrance(self):
        first, later = next((a,b) for a,b in zip(self.reports,self.reports[1:])
                            if a['phase']==b['phase']==2 and a['activeSeq']==b['activeSeq'])
        later['phase'] = 1
        self.rejected()

    def test_no_false_fallback_or_failed_lifecycle_pass(self):
        summary = next(r for r in self.records if r['event'] == 'summary')
        summary['success'] = False
        self.rejected()

    def test_readiness_without_second_session_delivery_fails(self):
        self.records = [r for r in self.records if not (r['event'] == 'started' and r['seq'] == 11)]
        self.rejected()

    def test_resume_cursors_remain_independent(self):
        self.second[0]['enqueuedSeq'] = 10
        self.rejected()

    def test_terminal_progress_never_becomes_enqueue_ack(self):
        self.second[0]['processedSeq'] = 9
        self.rejected()

    def test_early_grant_cannot_create_a_slot(self):
        self.second[0]['grantTotal'] += 1
        self.rejected()

    def test_completion_releases_no_extra_credit(self):
        self.second[-1]['grantTotal'] += 1
        self.rejected()

    def test_active_identity_is_not_relabelled(self):
        self.second[0]['activeEpoch'] = '0000000000000001'
        self.rejected()

    def test_retained_fifo_not_inferred_from_counts(self):
        entries = self.second[0]['pendingEntries']
        entries[0], entries[1] = entries[1], entries[0]
        self.rejected()

    def test_pending_disposition_is_not_count_only(self):
        self.second[0]['pendingEntries'][0]['seq'] = 2
        self.rejected()

    def test_unknown_local_queue_is_not_empty(self):
        self.second[-1]['pendingEntries'] = [dict(epoch=self.ready[0]['epoch'], seq=11, origin=0)]
        self.rejected()

    def test_old_connection_report_is_not_current(self):
        self.second[0]['connection'] = '0000000000000001'
        self.rejected()

    def test_refusal_cannot_cross_a_barrier(self):
        self.second[0]['refusedTotal'] = 1
        self.rejected()

    def test_revived_freshness_does_not_pass(self):
        for r in self.second:
            r.update(baseScreen=4, reason=0)
        self.rejected()

    def test_single_late_report_cannot_revive_expired_cpu(self):
        row = next(r for r in self.second if r['phase'] == 3)
        row.update(baseScreen=4, reason=0)
        self.assertEqual(self.second[-1]['baseScreen'], 0)
        self.rejected()

    def test_first_uncovered_view_cannot_revive_expired_cpu(self):
        row = next(r for r in self.records if r['event'] == 'application_view'
                   and r['connection'] == self.ready[1]['connection'] and not r['covered'])
        row.update(baseScreen=4, reason=0, cpuVisible=True)
        self.assertEqual(self.second[-1]['baseScreen'], 0)
        self.rejected()

    def test_covered_view_cannot_expose_cpu_root(self):
        row = next(r for r in self.records if r['event'] == 'application_view'
                   and r['connection'] == self.ready[1]['connection'] and r['covered'])
        row['cpuVisible'] = True
        self.rejected()

    def test_uncovered_view_cannot_expose_weather_root(self):
        row = next(r for r in self.records if r['event'] == 'application_view'
                   and r['connection'] == self.ready[1]['connection'] and not r['covered'])
        row['weatherVisible'] = True
        self.rejected()

    def test_hidden_root_does_not_excuse_wrong_reveal_selection(self):
        row = next(r for r in self.records if r['event'] == 'application_view'
                   and r['connection'] == self.ready[1]['connection'] and not r['covered'])
        row.update(baseScreen=4, reason=0)
        self.rejected()

    def test_missing_application_views_fail_closed(self):
        self.records = [r for r in self.records if not (r['event'] == 'application_view'
                        and r['connection'] == self.ready[1]['connection'])]
        self.rejected()

    def test_missing_reveal_observations_fail_closed(self):
        self.records = [r for r in self.records if not (r['event'] == 'application_view'
                        and r['connection'] == self.ready[1]['connection'] and not r['covered'])]
        self.rejected()

    def test_missing_single_reveal_fails_closed(self):
        row = next(r for r in self.records if r['event'] == 'application_view'
                   and r['connection'] == self.ready[1]['connection'] and not r['covered'])
        self.records.remove(row)
        self.rejected()

    def test_ready_view_cannot_hide_behind_synchronization(self):
        row = next(r for r in self.records if r['event'] == 'application_view'
                   and r['connection'] == self.ready[1]['connection'] and not r['covered'])
        row.update(baseScreen=0, reason=2)
        self.rejected()

    def test_ready_view_cannot_hide_behind_link_loss(self):
        row = next(r for r in self.records if r['event'] == 'application_view'
                   and r['connection'] == self.ready[1]['connection'] and not r['covered'])
        row.update(baseScreen=1, reason=1)
        self.rejected()

    def test_explicit_pre_ready_synchronization_is_allowed(self):
        ready_index = self.records.index(self.ready[1])
        row = next(r for r in self.records[:ready_index] if r['event'] == 'application_view'
                   and r['connection'] == self.ready[1]['connection'])
        row.update(baseScreen=0, reason=2)
        self.assertTrue(verify_terminal(self.records)['passed'])

    def test_explicit_post_delivery_link_loss_is_allowed(self):
        original = next(i for i, r in enumerate(self.records) if r['event'] == 'application_view'
                        and r['connection'] == self.ready[1]['connection'])
        row, numeric = copy.deepcopy(self.records[original:original + 2])
        row.update(baseScreen=1, reason=1, covered=False)
        self.records[-1:-1] = [row, numeric]
        self.assertTrue(verify_terminal(self.records)['passed'])

    def test_terminal_card_cannot_start_after_reconnect(self):
        started = next(r for r in self.records if r['event'] == 'started' and r['seq'] == 4)
        started['seq'] = 3
        self.rejected()


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--observations', required=True, type=Path)
    args = parser.parse_args()
    RECORDS = [json.loads(line) for line in args.observations.read_text().splitlines()]
    unittest.main(argv=[__file__], verbosity=2)
