#!/usr/bin/env python3
"""Actual firmware TCP/application failure gates using explicitly synthetic traffic.

No production relay, desktop session, credentials or physical board is accessed.
"""
import argparse
import json
import os
from pathlib import Path
import socket
import subprocess
import threading
import unittest
from test_capable_pairing import FixtureTraffic, receive

ROOT = Path(__file__).resolve().parents[2]
BINARY = None
EVIDENCE = None


class RuntimeFailureTests(unittest.TestCase):
    def run_peer(self, name, duration, serve, delay=None):
        if BINARY is None:
            self.skipTest('Explicit --binary required')
        fixture = FixtureTraffic()
        raw, hellos, errors, acks = [], [], [], []
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0))
            listener.listen(4)
            listener.settimeout(duration + 3)
            port = listener.getsockname()[1]

            def worker():
                try:
                    serve(listener, fixture, raw, hellos, acks)
                except BaseException as error:
                    errors.append(repr(error))

            thread = threading.Thread(target=worker)
            thread.start()
            env = {k: v for k, v in os.environ.items() if not k.startswith('FIRMWARE_PEER_')}
            env.update(FIRMWARE_PEER_PORT=str(port), FIRMWARE_PEER_SECONDS=str(duration),
                       FIRMWARE_PEER_REQUIRE_DESK='1', FIRMWARE_PEER_PROFILE_READY='1',
                       FIRMWARE_PEER_MAX_WRITE_BYTES='13')
            if delay is not None:
                env['FIRMWARE_PEER_START_DELAY_SECONDS'] = str(delay)
            try:
                child = subprocess.run([str(BINARY)], env=env, capture_output=True, text=True, timeout=duration + 8)
            finally:
                thread.join(timeout=duration + 4)
            self.assertFalse(thread.is_alive(), 'Test-owned socket worker did not finish')
        records = [json.loads(line) for line in child.stdout.splitlines()]
        evidence = dict(records=records, receivedReports=raw, helloPayloads=hellos,
                        acknowledgments=acks, errors=errors, exitCode=child.returncode,
                        stderr=child.stderr, productionRelayVerified=False, physicalVerified=False)
        if EVIDENCE is not None:
            EVIDENCE.mkdir(parents=True, exist_ok=True)
            (EVIDENCE / (name + '.json')).write_text(json.dumps(evidence, indent=2) + '\n')
        self.assertFalse(errors, evidence)
        self.assertEqual(child.returncode, 0, child.stderr)
        reports = [r for r in records if r['event'] == 'device_report_written']
        self.assertEqual(len(reports), len(raw))
        for report, encoded in zip(reports, raw):
            payload = bytes.fromhex(encoded)
            for key, offset, width in (('revision', 24, 4), ('grantTotal', 44, 8),
                                       ('processedSeq', 52, 4), ('enqueuedSeq', 56, 4),
                                       ('expiredTotal', 88, 4), ('suppressedTotal', 92, 4), ('refusedTotal', 96, 4)):
                self.assertEqual(report[key], int.from_bytes(payload[offset:offset + width], 'little'))
        return records, reports, hellos, acks

    def greet(self, connection, fixture, hellos, identity, latest=1, epoch=None, enabled=0, complete=True):
        kind, hello = receive(connection)
        self.assertEqual((kind, len(hello)), (1, 92))
        hellos.append(hello.hex())
        probe = int.from_bytes(hello[84:92], 'little')
        fields = dict(connectionId=identity)
        if epoch is not None:
            fields['relayEpoch'] = epoch
        sync = fixture.vectors['sync_end']['fields']
        frames = [fixture.frame('welcome', **fields, latestSeq=latest,
                                echoedProbeDeviceMs=probe, sampledRelayMs=5000 + probe),
                  fixture.frame('display_cpu', **fields, revision=sync['displayRevision'], enabled=enabled),
                  fixture.frame('weather_missing', **fields, revision=sync['weatherRevision']),
                  fixture.frame('cpu_missing', **fields, revision=sync['cpuRevision']),
                  fixture.frame('stats_observed', **fields, statsRevision=sync['statsRevision']),
                  fixture.frame('sync_end', **fields)]
        for frame in frames if complete else frames[:2]:
            connection.sendall(frame)

    @staticmethod
    def reports(connection, raw, acks):
        while True:
            try:
                kind, payload = receive(connection)
            except EOFError:
                return
            if kind == 4:
                acks.append(int.from_bytes(payload, 'little'))
            if kind == 6:
                raw.append(payload.hex())
                yield payload

    def test_mixed_terminal_dispositions_and_source_fences_finish_active_card(self):
        def serve(listener, fixture, raw, hellos, acks):
            with listener.accept()[0] as connection:
                connection.settimeout(15)
                self.greet(connection, fixture, hellos, 101, enabled=1)
                stage = 0
                for payload in self.reports(connection, raw, acks):
                    phase, pending = payload[82:84]
                    active = int.from_bytes(payload[108:112], 'little')
                    processed = int.from_bytes(payload[52:56], 'little')
                    if stage == 0:
                        connection.sendall(fixture.frame('external_twitch_kind', seq=2, kind=0, ttlDs=30,
                                                         origin=1, sourceGeneration=3, twitchEpisode=5, deadlineRelayMs=65000))
                        stage = 1
                    elif stage == 1 and active == 2 and phase == 1:
                        for seq, origin, deadline in ((3, 1, 65000), (4, 0, 65000), (5, 0, 1)):
                            connection.sendall(fixture.frame('external_twitch_kind', seq=seq, kind=4, ttlDs=1,
                                                             origin=origin, sourceGeneration=3 if origin else 0,
                                                             twitchEpisode=5 if origin else 0, deadlineRelayMs=deadline))
                        stage = 2
                    elif stage == 2 and processed == 5 and pending == 2:
                        connection.sendall(fixture.frame('display_cpu', connectionId=101, revision=10,
                                                         enabled=1, sourceGeneration=4, twitchEpisode=6))
                        # Old-generation STATS must not apply. Current-generation STATS follows.
                        connection.sendall(fixture.frame('stats_observed', connectionId=101, statsRevision=8))
                        stage = 3
                    elif stage == 3 and int.from_bytes(payload[60:64], 'little') == 10:
                        connection.sendall(fixture.frame('stats_observed', connectionId=101, statsRevision=8, sourceGeneration=4))
                        connection.sendall(fixture.frame('external_twitch_kind', seq=6, kind=0, ttlDs=1,
                                                         origin=1, sourceGeneration=3, twitchEpisode=5, deadlineRelayMs=65000))
                        connection.sendall(fixture.frame('external_twitch_kind', seq=4, kind=4, ttlDs=1, deadlineRelayMs=65000))
                        connection.sendall(fixture.frame('barrier', connectionId=101, throughSeq=7))
                        connection.sendall(fixture.frame('display_cpu', connectionId=101, revision=11,
                                                         enabled=0, sourceGeneration=4, twitchEpisode=7))
                        connection.sendall(fixture.frame('display_cpu', connectionId=101, revision=12,
                                                         enabled=1, sourceGeneration=4, twitchEpisode=8))
                        stage = 4
                self.assertEqual(stage, 4)
        records, reports, hellos, acks = self.run_peer('terminal-source-fences', 9, serve)
        self.assertEqual(len(hellos), 1)
        self.assertEqual([r['seq'] for r in records if r['event'] == 'started'], [2, 4])
        summary = next(r for r in records if r['event'] == 'summary')
        self.assertEqual([summary[k] for k in ('started', 'holds', 'exits', 'completed')], [2] * 4)
        final = reports[-1]
        self.assertEqual([final[k] for k in ('processedSeq', 'enqueuedSeq', 'grantTotal', 'expiredTotal',
                                            'suppressedTotal', 'refusedTotal', 'pending', 'phase')], [7, 4, 14, 1, 2, 0, 0, 0])
        self.assertTrue(any(r['activeSeq'] == 2 and r['displayRevision'] == 12 and r['pending'] == 1 for r in reports))
        views = [r for r in records if r['event'] == 'application_view']
        self.assertTrue(any(r['displayRevision'] == 10 and r['statsAvailability'] == 0
                            and r['statsSourceGeneration'] == 3 and r['statsRevision'] == 7 for r in views))
        self.assertTrue(any(r['displayRevision'] == 12 and r['statsAvailability'] == 1
                            and r['statsSourceGeneration'] == 4 and r['statsRevision'] == 8 for r in views))
        self.assertTrue(acks and max(acks) == 4 and all(a <= 4 for a in acks))
        self.assertTrue(all(r['grantTotal'] == 8 + r['expiredTotal'] + r['suppressedTotal']
                            + (1 if r['processedSeq'] >= 7 else 0)
                            + (0 if r['enqueuedSeq'] == 1 else (2 if r['activeSeq'] == 4 or not r['phase'] else 1))
                            for r in reports))

    def test_overcredit_refusal_recovers_without_crossing_unhandled_event(self):
        def serve(listener, fixture, raw, hellos, acks):
            for session in range(2):
                with listener.accept()[0] as connection:
                    connection.settimeout(12)
                    self.greet(connection, fixture, hellos, 101 + session, latest=1 if session == 0 else 10)
                    stage = 0
                    for payload in self.reports(connection, raw, acks):
                        if session == 0 and stage == 0:
                            for seq in range(2, 10):
                                connection.sendall(fixture.frame('external_twitch_kind', seq=seq, kind=0,
                                                                 ttlDs=1, deadlineRelayMs=65000))
                            stage = 1
                        elif session == 0 and stage == 1 and payload[83] == 8:
                            connection.sendall(fixture.frame('external_twitch_kind', seq=10, kind=0,
                                                             ttlDs=1, deadlineRelayMs=65000))
                            stage = 2
                    if session == 0:
                        self.assertEqual(stage, 2)
        records, reports, hellos, _ = self.run_peer('overcredit-recovery', 7, serve, delay=7)
        self.assertEqual(len(hellos), 2)
        second = [r for r in reports if r['connection'] == '0000000000000066']
        self.assertTrue(second)
        self.assertEqual([second[0][k] for k in ('processedSeq', 'enqueuedSeq', 'grantTotal', 'pending', 'refusedTotal')], [9, 9, 0, 8, 1])
        self.assertEqual(int.from_bytes(bytes.fromhex(hellos[1])[72:76], 'little'), 9)
        self.assertFalse([r for r in records if r['event'] == 'started'])
        self.assertTrue(all(r['processedSeq'] < 10 for r in reports))

    def test_new_epoch_preserves_generic_identities_and_active_completion(self):
        new_epoch = 0x0123456789ABCDF0

        def serve(listener, fixture, raw, hellos, acks):
            for session in range(2):
                with listener.accept()[0] as connection:
                    connection.settimeout(15)
                    self.greet(connection, fixture, hellos, 101 + session,
                               latest=1 if session == 0 else 0, epoch=None if session == 0 else new_epoch)
                    stage = 0
                    for payload in self.reports(connection, raw, acks):
                        active = int.from_bytes(payload[108:112], 'little')
                        if session == 0 and stage == 0:
                            connection.sendall(fixture.frame('external_twitch_kind', seq=2, kind=0,
                                                             ttlDs=40, deadlineRelayMs=65000))
                            stage = 1
                        elif session == 0 and stage == 1 and active == 2:
                            connection.sendall(fixture.frame('external_twitch_kind', seq=3, kind=0,
                                                             ttlDs=1, deadlineRelayMs=65000))
                            stage = 2
                        elif session == 0 and stage == 2 and payload[83] == 1:
                            break  # Test-owned epoch reset; closes only this socket.
                        elif session == 1 and stage == 0:
                            self.assertEqual((payload[83], active), (1, 2))
                            self.assertEqual(int.from_bytes(payload[44:52], 'little'), 7)
                            connection.sendall(fixture.frame('external_twitch_kind', relayEpoch=new_epoch,
                                                             seq=1, kind=0, ttlDs=1, deadlineRelayMs=65000))
                            stage = 1
        records, reports, hellos, _ = self.run_peer('new-epoch-generic-retention', 12, serve)
        self.assertEqual(len(hellos), 2)
        started = [r for r in records if r['event'] == 'started']
        self.assertEqual([r['seq'] for r in started], [2, 3, 1])
        self.assertEqual([r['epoch'] for r in started], ['0123456789abcdef'] * 2 + [f'{new_epoch:016x}'])
        summary = next(r for r in records if r['event'] == 'summary')
        self.assertEqual([summary[k] for k in ('started', 'holds', 'exits', 'completed')], [3] * 4)
        second = [r for r in reports if r['epoch'] == f'{new_epoch:016x}']
        self.assertEqual([second[0][k] for k in ('processedSeq', 'enqueuedSeq', 'grantTotal', 'pending', 'activeSeq')], [0, 0, 7, 1, 2])
        self.assertEqual(second[0]['activeEpoch'], '0123456789abcdef')
        self.assertEqual(second[0]['pendingEntries'], [dict(epoch='0123456789abcdef', seq=3, origin=0)])
        self.assertEqual([second[-1][k] for k in ('processedSeq', 'enqueuedSeq', 'grantTotal', 'pending', 'phase')], [1, 1, 9, 0, 0])
        self.assertTrue(all(r['refusedTotal'] == r['suppressedTotal'] == r['expiredTotal'] == 0 for r in reports))

    def test_plain_event_violation_does_not_cross_following_barrier(self):
        def serve(listener, fixture, raw, hellos, acks):
            for session in range(2):
                with listener.accept()[0] as connection:
                    connection.settimeout(12)
                    self.greet(connection, fixture, hellos, 101 + session)
                    sent = False
                    for _ in self.reports(connection, raw, acks):
                        if session == 0 and not sent:
                            frame = bytearray(fixture.frame('external_twitch_kind', seq=2)[:176])
                            frame[3] = 0x21  # Classic EVENT, forbidden after DESK negotiation.
                            frame[4:6] = (168).to_bytes(2, 'little')
                            frame[7] = 255 ^ (sum((i + 1) * frame[i] for i in range(7)) & 255)
                            connection.sendall(frame + fixture.frame('barrier', connectionId=101, throughSeq=100))
                            sent = True
        records, reports, hellos, _ = self.run_peer('plain-event-refusal', 5, serve)
        self.assertEqual(len(hellos), 2)
        self.assertFalse([r for r in records if r['event'] == 'started'])
        self.assertTrue(all(r['processedSeq'] == r['enqueuedSeq'] == 1 for r in reports))
        self.assertEqual(int.from_bytes(bytes.fromhex(hellos[1])[72:76], 'little'), 1)

    def test_equal_revision_conflicts_recover_without_crossing_barrier(self):
        for conflict in ('display', 'cpu'):
            with self.subTest(conflict=conflict):
                def serve(listener, fixture, raw, hellos, acks):
                    for session in range(2):
                        with listener.accept()[0] as connection:
                            connection.settimeout(15)
                            self.greet(connection, fixture, hellos, 101 + session,
                                       latest=1 if session == 0 else 3)
                            stage = 0
                            for payload in self.reports(connection, raw, acks):
                                active = int.from_bytes(payload[108:112], 'little')
                                if session == 0 and stage == 0:
                                    connection.sendall(fixture.frame('external_twitch_kind', seq=2, kind=0,
                                                                     ttlDs=40, deadlineRelayMs=65000))
                                    stage = 1
                                elif session == 0 and stage == 1 and active == 2:
                                    connection.sendall(fixture.frame('external_twitch_kind', seq=3, kind=0,
                                                                     ttlDs=1, deadlineRelayMs=65000))
                                    stage = 2
                                elif session == 0 and stage == 2 and payload[83] == 1:
                                    sync = fixture.vectors['sync_end']['fields']
                                    frame = (fixture.frame('display_cpu', connectionId=101,
                                                           revision=sync['displayRevision'], enabled=1)
                                             if conflict == 'display' else
                                             fixture.frame('cpu_memory_absent', connectionId=101,
                                                           revision=sync['cpuRevision'],
                                                           deadlineRelayMs=65000, cpuBasisPoints=5000))
                                    connection.sendall(frame + fixture.frame('barrier', connectionId=101, throughSeq=100))
                                    stage = 3
                                elif session == 1 and stage == 0:
                                    self.assertEqual((payload[83], active), (1, 2))
                                    self.assertEqual(int.from_bytes(payload[44:52], 'little'), 7)
                                    # Old connection traffic must not become current control or progress.
                                    connection.sendall(fixture.frame('display_cpu', connectionId=101,
                                                                     revision=99, enabled=1))
                                    connection.sendall(fixture.frame('barrier', connectionId=101, throughSeq=100))
                                    stage = 1
                            self.assertEqual(stage, 3 if session == 0 else 1)

                records, reports, hellos, _ = self.run_peer('equal-revision-' + conflict, 10, serve)
                self.assertEqual(len(hellos), 2)
                self.assertEqual([r['seq'] for r in records if r['event'] == 'started'], [2, 3])
                summary = next(r for r in records if r['event'] == 'summary')
                self.assertEqual([summary[k] for k in ('started', 'holds', 'exits', 'completed')], [2] * 4)
                self.assertEqual(int.from_bytes(bytes.fromhex(hellos[1])[72:76], 'little'), 3)
                second = [r for r in reports if r['connection'] == '0000000000000066']
                self.assertTrue(second)
                self.assertEqual([second[0][k] for k in ('processedSeq', 'enqueuedSeq', 'grantTotal', 'pending', 'activeSeq')],
                                 [3, 3, 7, 1, 2])
                self.assertEqual(second[0]['pendingEntries'], [dict(epoch='0123456789abcdef', seq=3, origin=0)])
                self.assertEqual([second[-1][k] for k in ('grantTotal', 'pending', 'phase')], [8, 0, 0])
                self.assertTrue(all(r['processedSeq'] <= 3 and r['enqueuedSeq'] <= 3 for r in reports))
                display_revision = FixtureTraffic().vectors['sync_end']['fields']['displayRevision']
                self.assertTrue(all(r['displayRevision'] == display_revision for r in reports))
                self.assertTrue(all(r['refusedTotal'] == r['suppressedTotal'] == r['expiredTotal'] == 0 for r in reports))
                self.assertTrue(all(r['grantTotal'] == 8 - r['pending'] for r in second))

    def test_missing_sync_end_times_out_then_new_connection_recovers(self):
        def serve(listener, fixture, raw, hellos, acks):
            for session in range(2):
                with listener.accept()[0] as connection:
                    connection.settimeout(12)
                    self.greet(connection, fixture, hellos, 101 + session, complete=session == 1)
                    for _ in self.reports(connection, raw, acks):
                        self.assertEqual(session, 1, 'No report before complete synchronization')
        records, reports, hellos, _ = self.run_peer('sync-timeout-recovery', 9, serve)
        self.assertEqual(len(hellos), 2)
        ready = [r for r in records if r['event'] == 'desk_ready']
        self.assertEqual([r['connection'] for r in ready], ['0000000000000066'])
        self.assertTrue(reports)
        self.assertTrue(all(r['connection'] == ready[0]['connection'] for r in reports))
        self.assertGreaterEqual(int.from_bytes(bytes.fromhex(hellos[1])[84:92], 'little'), 5000)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binary', type=Path)
    parser.add_argument('--evidence', type=Path)
    args = parser.parse_args()
    os.umask(0o077)
    BINARY = args.binary.resolve() if args.binary else None
    EVIDENCE = args.evidence.resolve() if args.evidence else None
    if (ROOT / '.agent-workflow/STOP').exists():
        raise SystemExit('STOP present')
    unittest.main(argv=[__file__], verbosity=2)
