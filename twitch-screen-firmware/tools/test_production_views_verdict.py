#!/usr/bin/env python3
"""Negative mutations of actual canonical production application captures."""
import argparse
import copy
import json
from pathlib import Path
import unittest
from test_production_views import verify

CAPTURES = []


class VerdictTests(unittest.TestCase):
    def rejected(self, mutate):
        for records, fixture in CAPTURES:
            with self.subTest(source=fixture['source']):
                rows, inputs = copy.deepcopy(records), copy.deepcopy(fixture)
                mutate(rows, inputs)
                with self.assertRaises((AssertionError, StopIteration, ValueError, KeyError)):
                    verify(rows, inputs)

    def test_real_captures(self):
        self.assertEqual(len(CAPTURES), 2)
        for records, fixture in CAPTURES:
            self.assertTrue(verify(records, fixture)['passed'])

    def test_disconnected_orbit_after_original_expiry_is_permitted(self):
        for records, fixture in CAPTURES:
            rows = copy.deepcopy(records)
            down = next(i for i, row in enumerate(rows) if row['event'] == 'link_down')
            view = next(i for i in range(down + 1, len(rows)) if rows[i]['event'] == 'application_view')
            self.assertEqual((rows[view]['baseScreen'], rows[view]['reason']), (1, 1))
            # Timing mutation of a real disconnect observation, not a new
            # fabricated acceptance. Offline orbit remains correct after expiry.
            latest = [row['atMs'] for row in rows if row['event'] == 'application_telemetry'
                      and row['cpuRevision'] == fixture['cpu']['revision']
                      and row['weatherRevision'] == fixture['weather']['revision']]
            rows[view + 1]['atMs'] = min(latest) + fixture[fixture['source']]['data']['freshnessMs'] + 1
            self.assertTrue(verify(rows, fixture)['passed'])

    def test_disconnected_orbit_requires_actual_link_down(self):
        def mutate(rows, _):
            rows[:] = [row for row in rows if row['event'] != 'link_down']
        self.rejected(mutate)

    def test_disconnected_orbit_cannot_show_telemetry(self):
        def mutate(rows, _):
            next(row for row in rows if row['event'] == 'application_view' and row['reason'] == 1
                 and row['connection'] != '0000000000000000')['cpuVisible'] = True
        self.rejected(mutate)

    def test_zero_epoch_views_and_numeric_fail(self):
        def mutate(rows, _):
            for row in rows:
                if row['event'] in ('application_view', 'application_telemetry') and row['connection'] != '0000000000000000':
                    row['epoch'] = '0000000000000000'
        self.rejected(mutate)

    def test_foreign_matching_epoch_views_and_numeric_fail(self):
        def mutate(rows, _):
            for row in rows:
                if row['event'] in ('application_view', 'application_telemetry') and row['connection'] != '0000000000000000':
                    row['epoch'] = 'ffffffffffffffff'
        self.rejected(mutate)

    def test_detached_numeric_epoch_fails(self):
        self.rejected(lambda rows, _: next(r for r in rows if r['event'] == 'application_telemetry'
                                          and r['connection'] != '0000000000000000').__setitem__('epoch', 'ffffffffffffffff'))

    def test_foreign_matching_connection_views_and_numeric_fail(self):
        def mutate(rows, _):
            for row in rows:
                if row['event'] in ('application_view', 'application_telemetry') and row['connection'] != '0000000000000000':
                    row['connection'] = 'ffffffffffffffff'
        self.rejected(mutate)

    def test_detached_numeric_connection_fails(self):
        self.rejected(lambda rows, _: next(r for r in rows if r['event'] == 'application_telemetry'
                                          and r['connection'] != '0000000000000000').__setitem__('connection', 'ffffffffffffffff'))

    def test_numeric_weather_revision_outside_accepted_history_fails(self):
        self.rejected(lambda rows, _: next(r for r in rows if r['event'] == 'application_telemetry'
                                          and r['connection'] != '0000000000000000').__setitem__('weatherRevision', 999))

    def test_zero_ready_epoch_fails(self):
        self.rejected(lambda rows, _: [r.__setitem__('epoch', '0000000000000000') for r in rows
                                      if r['event'] == 'desk_ready'])

    def test_overgrant(self):
        self.rejected(lambda rows, _: next(r for r in rows if r['event'] == 'device_report_written' and r['pending'] == 8).__setitem__('grantTotal', 99))

    def test_missing_zero_credit_recovery(self):
        self.rejected(lambda rows, _: rows.remove([r for r in rows if r['event'] == 'desk_ready'][1]))

    def test_wrong_pending_identity(self):
        self.rejected(lambda rows, _: next(r for r in rows if r['event'] == 'device_report_written' and r['pending'] == 8)['pendingEntries'][0].__setitem__('seq', 99999))

    def test_reversed_phase(self):
        self.rejected(lambda rows, _: next(r for r in rows if r['event'] == 'device_report_written' and r['phase'] == 3).__setitem__('phase', 1))

    def test_single_late_report_resurrection(self):
        self.rejected(lambda rows, _: [r for r in rows if r['event'] == 'device_report_written'][-1].__setitem__('baseScreen', 4))

    def test_single_uncover_flash(self):
        def mutate(rows, fixture):
            connection = [r for r in rows if r['event'] == 'desk_ready'][1]['connection']
            row = next(r for r in rows if r['event'] == 'application_view' and r['connection'] == connection
                       and r['displayRevision'] == fixture['finalRevision'] and not r['covered'])
            row['cpuVisible'] = True
        self.rejected(mutate)

    def test_missing_one_uncover(self):
        def mutate(rows, fixture):
            connection = [r for r in rows if r['event'] == 'desk_ready'][1]['connection']
            rows.remove(next(r for r in rows if r['event'] == 'application_view' and r['connection'] == connection
                             and r['displayRevision'] == fixture['finalRevision'] and not r['covered']))
        self.rejected(mutate)

    def test_late_regressed_revision_reveal(self):
        def mutate(rows, fixture):
            row = copy.deepcopy([r for r in rows if r['event'] == 'application_view'][-1])
            row.update(displayRevision=fixture['finalRevision'] - 1, covered=False,
                       baseScreen=4 if fixture['source'] == 'cpu' else 3,
                       cpuVisible=fixture['source'] == 'cpu', weatherVisible=fixture['source'] == 'weather')
            numeric = copy.deepcopy([r for r in rows if r['event'] == 'application_telemetry'][-1])
            numeric['displayRevision'] = row['displayRevision']
            rows[-1:-1] = [row, numeric]
        self.rejected(mutate)

    def test_covered_stale_root_visible(self):
        def mutate(rows, fixture):
            row = copy.deepcopy([r for r in rows if r['event'] == 'application_view'][-1])
            row.update(covered=True, cpuVisible=True)
            numeric = copy.deepcopy([r for r in rows if r['event'] == 'application_telemetry'][-1])
            rows[-1:-1] = [row, numeric]
        self.rejected(mutate)

    def test_late_zero_source_generation(self):
        self.rejected(lambda rows, _: [r for r in rows if r['event'] == 'device_report_written'][-1].__setitem__('sourceGeneration', 0))

    def test_late_old_source_generation(self):
        self.rejected(lambda rows, fixture: [r for r in rows if r['event'] == 'device_report_written'][-1].__setitem__('sourceGeneration', fixture['sourceGeneration'] - 1))

    def test_late_episode_regression(self):
        self.rejected(lambda rows, _: [r for r in rows if r['event'] == 'device_report_written'][-1].__setitem__('twitchEpisode', 1))

    def test_presync_hidden_view_is_permitted(self):
        for records, fixture in CAPTURES:
            rows = copy.deepcopy(records)
            position = next(i for i, row in enumerate(rows) if row['event'] == 'desk_ready')
            ready = rows[position]
            original_position = max(i for i, candidate in enumerate(rows[:position])
                                    if candidate['event'] == 'application_view'
                                    and candidate['epoch'] == ready['epoch']
                                    and candidate['connection'] == ready['connection'])
            row = copy.deepcopy(rows[original_position])
            numeric = copy.deepcopy(rows[original_position + 1])
            row.update(baseScreen=0, reason=2, statsSourceGeneration=0, cpuVisible=False, weatherVisible=False)
            rows[position:position] = [row, numeric]
            self.assertTrue(verify(rows, fixture)['passed'])

    def test_late_sync_label_cannot_hide_reveal(self):
        self.rejected(lambda rows, _: [r for r in rows if r['event'] == 'application_view'][-1].__setitem__('reason', 2))

    def test_early_invented_episode(self):
        def mutate(rows, fixture):
            for row in rows:
                if row['event'] == 'device_report_written' and row['displayRevision'] < fixture['overrideRevision']:
                    row['twitchEpisode'] += 1
        self.rejected(mutate)

    def test_late_invented_episode(self):
        self.rejected(lambda rows, _: [r for r in rows if r['event'] == 'device_report_written'][-1].__setitem__('twitchEpisode', 999))

    def test_late_zero_control_revision(self):
        self.rejected(lambda rows, _: [r for r in rows if r['event'] == 'device_report_written'][-1].__setitem__('displayRevision', 0))

    def test_report_wrong_epoch(self):
        self.rejected(lambda rows, _: [r for r in rows if r['event'] == 'device_report_written'][-1].__setitem__('epoch', '0000000000000001'))

    def test_late_view_source_regression(self):
        self.rejected(lambda rows, _: [r for r in rows if r['event'] == 'application_view'][-1].__setitem__('statsSourceGeneration', 1))

    def test_renewed_http_age(self):
        self.rejected(lambda _, fixture: fixture['finalCpu'].__setitem__('status', 'fresh'))

    def test_changed_original_source_revision(self):
        self.rejected(lambda _, fixture: fixture['finalWeather'].__setitem__('revision', 999))

    def test_missing_source_fence(self):
        def mutate(rows, _):
            for row in rows:
                if row['event'] == 'device_report_written': row['sourceGeneration'] = 0
        self.rejected(mutate)

    def test_missing_numeric_presence_proof(self):
        def mutate(rows, _):
            for row in rows:
                if row['event'] == 'application_telemetry': row['cpuPresence'] = 1
        self.rejected(mutate)

    def test_original_hold_shortened(self):
        def mutate(rows, _):
            next(r for r in rows if r['event'] == 'started')['holdMs'] = 100
        self.rejected(mutate)

    def test_external_provenance_changed(self):
        self.rejected(lambda rows, _: next(r for r in rows if r['event'] == 'started').__setitem__('origin', 1))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence', required=True, type=Path)
    args, remaining = parser.parse_known_args()
    for source in ('cpu', 'weather'):
        folder = args.evidence / source
        rows = [json.loads(line) for line in (folder / 'observations.jsonl').read_text().splitlines()]
        fixture = json.loads((folder / 'fixture.json').read_text())
        CAPTURES.append((rows, fixture))
    unittest.main(argv=[__file__] + remaining)


if __name__ == '__main__':
    main()
