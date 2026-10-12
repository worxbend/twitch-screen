#!/usr/bin/env python3
"""Fail-closed negative mutations of real writer/control captures, not runtime output."""
import argparse
import copy
import json
from pathlib import Path
import unittest
from test_relay_writers import verify_control, verify_remote

RECORDS, SCHEDULE, REMOTE, LAYOUT = [], [], [], {}


class WriterVerdictTests(unittest.TestCase):
    def test_wrong_matching_view_numeric_epoch_fails(self):
        def mutate(rows):
            for row in rows:
                if row['event'] in ('application_view', 'application_telemetry') and row['connection'] != '0000000000000000':
                    row['epoch'] = 'ffffffffffffffff'
        self.reject_local(mutate)

    def test_detached_numeric_epoch_fails(self):
        self.reject_local(lambda rows: next(r for r in rows if r['event'] == 'application_telemetry'
                                           and r['connection'] != '0000000000000000').__setitem__('epoch', 'ffffffffffffffff'))

    def test_real_capture_passes(self):
        self.assertTrue(verify_control(RECORDS,SCHEDULE)['passed'])
        self.assertGreater(verify_remote(RECORDS,SCHEDULE,REMOTE,LAYOUT)['remoteAcceptedSnapshotsChecked'],0)

    def reject_local(self,mutate):
        records = copy.deepcopy(RECORDS)
        mutate(records)
        with self.assertRaises((AssertionError,ValueError,KeyError,StopIteration)):
            verify_control(records,SCHEDULE)

    def reject_matching_phase_mutation(self, after):
        records = copy.deepcopy(RECORDS)
        remote = copy.deepcopy(REMOTE)
        reports = [r for r in records if r['event']=='device_report_written']
        pair = next((a,b) for a,b in zip(reports,reports[1:])
                    if a['phase']==b['phase']==2 and a['activeSeq']==b['activeSeq'])
        first = pair[1] if after==1 else pair[0]
        first['phase'] = after
        field = next(f for f in LAYOUT['fields'] if f['name']=='activePhase')
        changed = 0
        for sample in remote:
            if sample['reportHex'] is None:
                continue
            frame = bytearray.fromhex(sample['reportHex'])
            if int.from_bytes(frame[32:36],'little')==first['revision']:
                frame[8+field['offset']] = after
                sample['reportHex'] = frame.hex()
                changed += 1
        self.assertGreater(changed,0, 'Mutation must match actual remotely accepted payloads')
        with self.assertRaisesRegex(AssertionError,'phase|lifecycle'):
            verify_control(records,SCHEDULE)
        with self.assertRaisesRegex(AssertionError,'phase|lifecycle'):
            verify_remote(records,SCHEDULE,remote,LAYOUT)

    def test_hold_to_entrance_with_matching_remote_payload(self):
        self.reject_matching_phase_mutation(1)

    def test_exit_to_hold_with_matching_remote_payload(self):
        self.reject_matching_phase_mutation(3)

    def test_missing_lifecycle_callback(self):
        self.reject_local(lambda rows: rows.__setitem__(slice(None),
                          [r for r in rows if not (r['event']=='card_lifecycle' and r['seq']==1 and r['phase']==3)]))

    def test_original_hold_cannot_be_shortened_by_controls(self):
        def mutate(rows):
            hold = next(r for r in rows if r['event']=='card_lifecycle' and r['seq']==1 and r['phase']==2)
            exit_card = next(r for r in rows if r['event']=='card_lifecycle' and r['seq']==1 and r['phase']==3)
            exit_card['atMs'] = hold['atMs'] + 9999
        self.reject_local(mutate)

    def test_callback_identity_cannot_change(self):
        def mutate(rows):
            next(r for r in rows if r['event']=='card_lifecycle' and r['phase']==2)['seq']=2
        self.reject_local(mutate)

    def test_callback_phase_cannot_reverse(self):
        def mutate(rows):
            next(r for r in rows if r['event']=='card_lifecycle' and r['phase']==3)['phase']=2
        self.reject_local(mutate)

    def test_coalescing_may_skip_report_phases(self):
        # Keep the reports that establish actual queue/dequeue identities.
        # Exit observations may coalesce; timestamped completion remains required.
        records = [r for r in RECORDS if not (r['event']=='device_report_written' and r['phase']==3)]
        self.assertTrue(verify_control(records,SCHEDULE)['passed'])

    def test_single_report_overgrant(self):
        def mutate(rows):
            next(r for r in rows if r['event']=='device_report_written' and r['phase']==2)['grantTotal']+=1
        self.reject_local(mutate)

    def test_early_pending_removal_cannot_create_dequeue(self):
        def mutate(rows):
            r = next(r for r in rows if r['event']=='device_report_written' and r['pending']==2)
            r['pendingEntries'].pop()
            r['pending']-=1
            r['grantTotal']+=1
        self.reject_local(mutate)

    def test_single_wrong_source_fence(self):
        def mutate(rows):
            next(r for r in rows if r['event']=='device_report_written' and r['displayRevision']>10)['sourceGeneration']+=1
        self.reject_local(mutate)

    def test_single_wrong_episode(self):
        def mutate(rows):
            next(r for r in rows if r['event']=='device_report_written' and r['displayRevision']>10)['twitchEpisode']+=1
        self.reject_local(mutate)

    def test_single_late_wrong_report_base(self):
        def mutate(rows):
            next(r for r in rows if r['event']=='device_report_written' and r['displayRevision']>100)['baseScreen']=4
        self.reject_local(mutate)

    def test_one_covered_view_telemetry_flash(self):
        def mutate(rows):
            next(r for r in rows if r['event']=='application_view' and r['displayRevision']>100 and r['covered'])['cpuVisible']=True
        self.reject_local(mutate)

    def test_one_view_old_generation_availability(self):
        def mutate(rows):
            next(r for r in rows if r['event']=='application_view' and r['displayRevision']>100)['statsAvailability']=1
        self.reject_local(mutate)

    def test_link_loss_cannot_mask_ready_view(self):
        def mutate(rows):
            next(r for r in rows if r['event']=='application_view' and r['displayRevision']>100 and r['covered']).update(baseScreen=1,reason=1)
        self.reject_local(mutate)

    def test_missing_one_reveal(self):
        def mutate(rows):
            previous = False
            for i,r in enumerate(rows):
                if r['event']!='application_view':
                    continue
                if previous and not r['covered']:
                    del rows[i]
                    return
                previous = r['covered']
            self.fail('Real capture has no reveal')
        self.reject_local(mutate)

    def test_waiting_twitch_cannot_resurrect(self):
        def mutate(rows):
            r = next(r for r in rows if r['event']=='device_report_written' and r['suppressedTotal']==1 and r['pending']==1)
            r['pendingEntries'][0]['seq']=2
        self.reject_local(mutate)

    def test_unknown_control_cannot_bypass_view_check(self):
        def mutate(rows):
            next(r for r in rows if r['event']=='application_view' and r['displayRevision']>100)['displayRevision']=999
        self.reject_local(mutate)

    def test_missing_final_source_convergence(self):
        self.reject_local(lambda rows: rows.__setitem__(slice(None),[r for r in rows if not(r['event']=='device_report_written' and r['displayRevision']==SCHEDULE[-1]['revision'])]))

    def test_remote_payload_mismatch(self):
        remote = copy.deepcopy(REMOTE)
        entry = next(r for r in remote if r['reportHex'] is not None)
        frame = bytearray.fromhex(entry['reportHex'])
        frame[8+44]+=1
        entry['reportHex']=frame.hex()
        with self.assertRaises(AssertionError):
            verify_remote(RECORDS,SCHEDULE,remote,LAYOUT)

    def test_new_controls_cannot_mask_stale_remote_application(self):
        remote = copy.deepcopy(REMOTE)
        first = next(r['reportHex'] for r in remote if r['reportHex'] is not None)
        # Reuse a real earlier accepted report at a later sample. Keep final
        # good report unchanged so final-state equality cannot excuse the gap.
        remote[-2]['reportHex']=first
        with self.assertRaises(AssertionError):
            verify_remote(RECORDS,SCHEDULE,remote,LAYOUT)

    def test_oldest_control_budget_is_not_reset_by_newer_writes(self):
        remote = copy.deepcopy(REMOTE)
        first = next(r['reportHex'] for r in remote if r['reportHex'] is not None)
        for sample in remote[:-1]:
            if sample['reportHex'] is not None:
                sample['reportHex']=first
        with self.assertRaisesRegex(AssertionError,'oldest remotely unapplied'):
            verify_remote(RECORDS,SCHEDULE,remote,LAYOUT)

    def test_remote_pending_identity_cannot_change(self):
        remote = copy.deepcopy(REMOTE)
        sample = next(r for r in remote if r['reportHex'] is not None and bytes.fromhex(r['reportHex'])[91]>0)
        frame = bytearray.fromhex(sample['reportHex'])
        frame[132]+=1
        sample['reportHex']=frame.hex()
        with self.assertRaises(AssertionError):
            verify_remote(RECORDS,SCHEDULE,remote,LAYOUT)

    def test_missing_remote_after_acceptance(self):
        remote = copy.deepcopy(REMOTE)
        remote[-2]['reportHex']=None
        with self.assertRaises(AssertionError):
            verify_remote(RECORDS,SCHEDULE,remote,LAYOUT)


def main():
    global RECORDS,SCHEDULE,REMOTE,LAYOUT
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence',required=True,type=Path)
    args = parser.parse_args()
    RECORDS = [json.loads(line) for line in (args.evidence/'native-observations.jsonl').read_text().splitlines()]
    SCHEDULE = [json.loads(line) for line in (args.evidence/'control-schedule.jsonl').read_text().splitlines()]
    REMOTE = [json.loads(line) for line in (args.evidence/'remote-report-observations.jsonl').read_text().splitlines()]
    root = Path(__file__).resolve().parents[2]
    LAYOUT = next(r for r in json.loads((root/'protocol/desk-v1-layouts.json').read_text())['records'] if r['name']=='DEVICE_REPORT')
    result = unittest.TextTestRunner(verbosity=2).run(unittest.defaultTestLoader.loadTestsFromTestCase(WriterVerdictTests))
    raise SystemExit(not result.wasSuccessful())


if __name__=='__main__':
    main()
