#!/usr/bin/env python3
"""Mutate actual CPU/weather recovery observations to reject transient stale reveals."""
import argparse
import copy
import json
from pathlib import Path
import unittest
from test_runtime_recovery import verify_expired_recovery

CAPTURES = {}


class RecoveryFreshnessTests(unittest.TestCase):
    def cases(self):
        for source, capture in CAPTURES.items():
            records = copy.deepcopy(capture)
            connection = [r for r in records if r['event'] == 'desk_ready'][1]['connection']
            yield source, records, connection

    def row(self, records, connection, event):
        return next(r for r in records if r['event'] == event and r.get('connection') == connection
                    and r['displayRevision'] == 12 and (event != 'application_view' or not r['covered']))

    def test_foreign_mutually_matching_view_numeric_epoch_fails(self):
        for source, records, connection in self.cases():
            with self.subTest(source=source):
                for row in records:
                    if row['event'] in ('application_view', 'application_telemetry') and row['connection'] == connection:
                        row['epoch'] = 'ffffffffffffffff'
                with self.assertRaises(AssertionError):
                    verify_expired_recovery(records, connection)

    def test_actual_cpu_and_weather_observations_pass(self):
        for source, records, connection in self.cases():
            with self.subTest(source=source):
                verify_expired_recovery(records, connection)

    def test_single_late_report_cannot_revive_source(self):
        for source, records, connection in self.cases():
            with self.subTest(source=source):
                self.row(records, connection, 'device_report_written').update(baseScreen=4 if source == 'cpu' else 3, reason=0)
                with self.assertRaises(AssertionError):
                    verify_expired_recovery(records, connection)

    def test_single_uncovered_view_cannot_revive_source(self):
        for source, records, connection in self.cases():
            with self.subTest(source=source):
                self.row(records, connection, 'application_view').update({source + 'Visible': True})
                with self.assertRaises(AssertionError):
                    verify_expired_recovery(records, connection)

    def test_hidden_root_does_not_excuse_wrong_selection(self):
        for source, records, connection in self.cases():
            with self.subTest(source=source):
                self.row(records, connection, 'application_view').update(baseScreen=4 if source == 'cpu' else 3, reason=0)
                with self.assertRaises(AssertionError):
                    verify_expired_recovery(records, connection)

    def test_wrong_override_revision_cannot_mask_expiry(self):
        for source, records, connection in self.cases():
            with self.subTest(source=source):
                self.row(records, connection, 'device_report_written').update(baseScreen=2, reason=6)
                with self.assertRaises(AssertionError):
                    verify_expired_recovery(records, connection)

    def test_missing_post_expiry_views_fail_closed(self):
        for source, records, connection in self.cases():
            with self.subTest(source=source):
                records = [r for r in records if not (r['event'] == 'application_view'
                           and r.get('connection') == connection)]
                with self.assertRaises(AssertionError):
                    verify_expired_recovery(records, connection)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence', type=Path, required=True,
                        help='Directory containing actual cpu/weather-recovery-observations.json captures')
    args = parser.parse_args()
    CAPTURES = {source: json.loads((args.evidence / (source + '-recovery-observations.json')).read_text())['records']
                for source in ('cpu', 'weather')}
    unittest.main(argv=[__file__], verbosity=2)
