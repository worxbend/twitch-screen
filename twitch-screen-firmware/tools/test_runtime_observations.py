#!/usr/bin/env python3
"""Synthetic negative identity fixtures; not production acceptance evidence."""
import copy
import unittest
from runtime_observations import ZERO, verify_application_sessions

EPOCH = '0000000000000001'
CONNECTION = '0000000000000002'


def fixture():
    identity = dict(epoch=EPOCH, connection=CONNECTION, displayRevision=1, cpuRevision=1)
    return [dict(event='desk_ready', **identity),
            dict(event='application_view', baseScreen=0, reason=4, cpuVisible=False, weatherVisible=False, **identity),
            dict(event='application_telemetry', **identity)]


class IdentityTests(unittest.TestCase):
    def reject(self, rows):
        with self.assertRaises((AssertionError, KeyError)):
            verify_application_sessions(rows)

    def test_matching_session(self):
        self.assertEqual(verify_application_sessions(fixture()), {(EPOCH, CONNECTION)})

    def test_connected_presync_pair(self):
        rows = fixture()
        self.assertEqual(verify_application_sessions(rows[1:] + rows[:1]), {(EPOCH, CONNECTION)})

    def test_foreign_mutually_matching_epoch(self):
        rows = fixture()
        for row in rows[1:]: row['epoch'] = 'ffffffffffffffff'
        self.reject(rows)

    def test_zero_mutually_matching_epoch(self):
        rows = fixture()
        for row in rows[1:]: row['epoch'] = ZERO
        self.reject(rows)

    def test_detached_numeric_epoch(self):
        rows = fixture(); rows[2]['epoch'] = 'ffffffffffffffff'
        self.reject(rows)

    def test_missing_numeric_epoch(self):
        rows = fixture(); del rows[2]['epoch']
        self.reject(rows)

    def test_foreign_report(self):
        rows = fixture()
        rows.append(dict(event='device_report_written', epoch=EPOCH, connection='ffffffffffffffff'))
        self.reject(rows)

    def test_zero_ready_identity(self):
        rows = fixture(); rows[0]['epoch'] = ZERO
        self.reject(rows)

    def test_malformed_ready_identity(self):
        rows = fixture(); rows[0]['epoch'] = '1'
        self.reject(rows)

    def test_duplicate_ready_identity(self):
        rows = fixture(); rows.append(copy.deepcopy(rows[0]))
        self.reject(rows)

    def test_disconnected_pair_is_not_current_numeric_authority(self):
        rows = fixture()
        lost = copy.deepcopy(rows[1:])
        for row in lost: row['connection'] = ZERO
        lost[0].update(baseScreen=1, reason=1)
        self.assertEqual(verify_application_sessions(rows + lost), {(EPOCH, CONNECTION)})

    def test_disconnected_telemetry_cannot_be_visible(self):
        rows = fixture()
        for row in rows[1:]: row['connection'] = ZERO
        rows[1].update(baseScreen=1, reason=1, cpuVisible=True)
        self.reject(rows)

    def test_reused_connection_with_fresh_epoch(self):
        rows = fixture()
        restarted = copy.deepcopy(rows)
        for row in restarted: row['epoch'] = '0000000000000003'
        self.assertEqual(len(verify_application_sessions(rows + restarted)), 2)

    def test_return_to_old_epoch_with_reused_connection(self):
        rows = fixture()
        restarted = copy.deepcopy(rows)
        for row in restarted: row['epoch'] = '0000000000000003'
        self.reject(rows + restarted + copy.deepcopy(rows[1:]))

    def test_orphan_numeric(self):
        rows = fixture(); rows.append(copy.deepcopy(rows[2]))
        self.reject(rows)


if __name__ == '__main__':
    unittest.main(verbosity=2)
