#!/usr/bin/env python3
"""Negative tests against real immutable-baseline/running-relay observations."""
import argparse
import copy
import json
from pathlib import Path
import subprocess
import unittest
from test_legacy_production import verify

RECORDS = PUBLICATIONS = None


class LegacyVerdictTests(unittest.TestCase):
    def setUp(self):
        if RECORDS is None:
            self.skipTest('Explicit --evidence required from real running-relay gate')
        assert RECORDS is not None and PUBLICATIONS is not None
        self.records = copy.deepcopy(RECORDS)
        self.published = copy.deepcopy(PUBLICATIONS)

    def reject(self):
        with self.assertRaises(AssertionError):
            verify(self.records, self.published)

    def test_real_queue_transfers_pass(self):
        self.assertEqual(verify(self.records, self.published), [r['seq'] for r in self.published])

    def test_second_welcome_after_both_transfers_fails(self):
        second = [r for r in self.records if r['event'] == 'welcome'][1]
        self.records.remove(second)
        self.records.insert(len(self.records) - 1, second)
        self.reject()

    def test_early_second_transfer_fails(self):
        second = [r for r in self.records if r['event'] == 'welcome'][1]
        transfer = [r for r in self.records if r['event'] in ('enqueued', 'dequeued') and r['seq'] == self.published[1]['seq']]
        for row in transfer:
            self.records.remove(row)
        position = self.records.index(second)
        self.records[position:position] = transfer
        self.reject()

    def test_dequeue_before_enqueue_fails(self):
        enqueue = next(r for r in self.records if r['event'] == 'enqueued')
        dequeue = next(r for r in self.records if r['event'] == 'dequeued')
        self.records.remove(dequeue)
        self.records.insert(self.records.index(enqueue), dequeue)
        self.reject()

    def test_summary_before_transfer_fails(self):
        summary = next(r for r in self.records if r['event'] == 'baseline_summary')
        self.records.remove(summary)
        self.records.insert(0, summary)
        self.reject()

    def test_false_capability_fails(self):
        next(r for r in self.records if r['event'] == 'welcome')['caps'] |= 16
        self.reject()

    def test_missing_transfer_fails(self):
        self.records.remove(next(r for r in self.records if r['event'] == 'dequeued'))
        self.reject()

    def test_reversed_transfer_fails(self):
        rows = [r for r in self.records if r['event'] == 'dequeued']
        rows[0]['seq'], rows[1]['seq'] = rows[1]['seq'], rows[0]['seq']
        self.reject()

    def test_duplicate_publication_fails(self):
        self.published[1]['seq'] = self.published[0]['seq']
        self.reject()

    def test_missing_enqueue_fails(self):
        self.records.remove(next(r for r in self.records if r['event'] == 'enqueued'))
        self.reject()

    def test_fabricated_capable_observation_fails(self):
        self.records.append(dict(event='desk_ready'))
        self.reject()

    def test_false_summary_fails(self):
        next(r for r in self.records if r['event'] == 'baseline_summary')['dequeued'] += 1
        self.reject()


class OwnershipTests(unittest.TestCase):
    def test_running_relay_requires_explicit_ownership(self):
        result = subprocess.run(['python3', str(Path(__file__).with_name('test_legacy_production.py')),
                                 '--http-port', '1', '--device-port', '2', '--evidence', '/unused'],
                                capture_output=True, text=True, timeout=5)
        self.assertEqual(result.returncode, 2)
        self.assertIn('--test-owned', result.stderr)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence', type=Path)
    args, extra = parser.parse_known_args()
    if args.evidence:
        RECORDS = [json.loads(line) for line in (args.evidence / 'observations.jsonl').read_text().splitlines()]
        PUBLICATIONS = [r['response'] for r in json.loads((args.evidence / 'http.json').read_text()) if r['path'] == 'notifications']
    unittest.main(argv=[__file__] + extra, verbosity=2)
