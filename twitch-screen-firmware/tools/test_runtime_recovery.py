#!/usr/bin/env python3
"""Exercise actual application reconnect/control with labeled synthetic TCP traffic.

No physical board or production relay is used by this regression.
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
from runtime_observations import session_key, verify_application_sessions

ROOT = Path(__file__).resolve().parents[2]
BINARY = None
EVIDENCE = None


def verify_expired_recovery(records, connection):
    """Reject any telemetry resurrection after this fixture's first expiry.

    Revision 11 explicitly enables Twitch; revision 12 restores the still-stale
    preference. No fresh snapshot is supplied after either source expires.
    """
    sessions = verify_application_sessions(records)
    matches = [key for key in sessions if key[1] == connection]
    assert len(matches) == 1, 'Recovery connection is ambiguous across epochs'
    rows = [r for r in records if r['event'] in ('device_report_written', 'application_view')
            and session_key(r) == matches[0]]
    expired_index = next(i for i, r in enumerate(rows) if (r['baseScreen'], r['reason']) == (0, 5))
    reveals = 0
    for r in rows[expired_index:]:
        state = (r['baseScreen'], r['reason'])
        assert state == (0, 5) or (state == (2, 6) and r['displayRevision'] == 11), 'Expired recovery telemetry reappeared'
        if r['event'] == 'application_view':
            assert not r['cpuVisible'] and not r['weatherVisible'], 'Expired recovery telemetry root visible'
            reveals += int(not r['covered'])
    assert reveals, 'Missing post-expiry reveal observations'


class RecoveryTests(unittest.TestCase):
    def test_retained_eight_zero_credit_cpu_control_and_completion(self):
        self.run_recovery('cpu', 2, 4, 'cpu_memory_zero')

    def test_retained_eight_zero_credit_weather_control_and_completion(self):
        self.run_recovery('weather', 1, 3, 'weather_negative')

    def run_recovery(self, source, preferred, selected_base, snapshot):
        if BINARY is None:
            self.skipTest('Explicit --binary required')
        fixture = FixtureTraffic()
        errors, reports, hellos = [], [], []
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0))
            listener.listen(2)
            listener.settimeout(15)
            port = listener.getsockname()[1]

            def serve():
                stage = 0
                try:
                    for session in range(2):
                        with listener.accept()[0] as connection:
                            connection.settimeout(15)
                            kind, hello = receive(connection)
                            self.assertEqual(kind, 1)
                            self.assertEqual(len(hello), 92)
                            hellos.append(hello.hex())
                            probe = int.from_bytes(hello[84:92], 'little')
                            identity = 101 + session
                            sync = fixture.vectors['sync_end']['fields']
                            batch = [
                                fixture.frame('welcome', latestSeq=1 if session == 0 else 9, connectionId=identity,
                                              echoedProbeDeviceMs=probe, sampledRelayMs=5000 + probe),
                                fixture.frame('display_cpu', connectionId=identity, revision=sync['displayRevision']),
                                fixture.frame('weather_missing', connectionId=identity, revision=sync['weatherRevision']),
                                fixture.frame('cpu_missing', connectionId=identity, revision=sync['cpuRevision']),
                                fixture.frame('stats_observed', connectionId=identity, statsRevision=sync['statsRevision']),
                                fixture.frame('sync_end', connectionId=identity),
                            ]
                            for frame in batch:
                                connection.sendall(frame)
                            stage = 0
                            while True:
                                try:
                                    kind, payload = receive(connection)
                                except EOFError:
                                    break
                                if kind == 5:
                                    request = int.from_bytes(payload, 'little')
                                    connection.sendall(fixture.frame('time_response', connectionId=identity,
                                                                    echoedProbeDeviceMs=request, sampledRelayMs=5000 + request))
                                if kind != 6:
                                    continue
                                reports.append(payload.hex())
                                pending = payload[83]
                                phase = payload[82]
                                grant = int.from_bytes(payload[44:52], 'little')
                                base, reason = payload[80:82]
                                if session == 0 and stage == 0:
                                    self.assertEqual(grant, 8)
                                    for seq in range(2, 10):
                                        connection.sendall(fixture.frame('external_twitch_kind', seq=seq, kind=0,
                                                                        ttlDs=1, deadlineRelayMs=65000))
                                    stage = 1
                                elif session == 1 and stage == 0:
                                    self.assertEqual((pending, phase, grant), (8, 0, 0))
                                    connection.sendall(fixture.frame(snapshot, connectionId=identity,
                                                                    revision=7, deadlineRelayMs=5000 + probe + 2000))
                                    connection.sendall(fixture.frame('display_cpu', connectionId=identity,
                                                                    revision=10, preferred=preferred))
                                    stage = 1
                                elif session == 1 and stage == 1 and base == selected_base:
                                    self.assertEqual((pending, grant), (8, 0))
                                    stage = 2
                                elif session == 1 and stage == 2 and base == 0 and reason == 5:
                                    self.assertEqual((pending, grant), (8, 0))
                                    connection.sendall(fixture.frame('display_cpu', connectionId=identity,
                                                                    revision=11, enabled=1, twitchEpisode=6, preferred=preferred))
                                    stage = 3
                                elif session == 1 and stage == 3 and base == 2:
                                    self.assertEqual((pending, grant), (8, 0))
                                    connection.sendall(fixture.frame('display_cpu', connectionId=identity,
                                                                    revision=12, enabled=0, twitchEpisode=7, preferred=preferred))
                                    stage = 4
                                elif session == 1 and stage == 4 and pending == 0 and phase == 0:
                                    self.assertEqual(grant, 8)
                                    stage = 5
                    self.assertEqual(stage, 5)
                except EOFError:
                    # First session closes by the explicit runtime reconnect.
                    if len(hellos) != 2:
                        errors.append('Connection closed before second greeting')
                except BaseException as error:
                    errors.append(repr(error))

            # EOF must end only the current connection, not the accept loop.
            worker = threading.Thread(target=serve)
            worker.start()
            env = {k: v for k, v in os.environ.items() if not k.startswith('FIRMWARE_PEER_')}
            env.update(FIRMWARE_PEER_PORT=str(port), FIRMWARE_PEER_SECONDS='24',
                       FIRMWARE_PEER_REQUIRE_DESK='1', FIRMWARE_PEER_PROFILE_READY='1',
                       FIRMWARE_PEER_RECONNECT_SECONDS='1', FIRMWARE_PEER_START_DELAY_SECONDS='6',
                       FIRMWARE_PEER_MAX_WRITE_BYTES='13')
            result = subprocess.run([str(BINARY)], env=env, capture_output=True, text=True, timeout=32)
            worker.join(timeout=16)
            self.assertFalse(worker.is_alive())
            self.assertFalse(errors, (errors, result.stderr))
        records = [json.loads(line) for line in result.stdout.splitlines()]
        ready = [r for r in records if r['event'] == 'desk_ready']
        self.assertEqual([r['connection'] for r in ready], ['0000000000000065', '0000000000000066'])
        summary = next(r for r in records if r['event'] == 'summary')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([r['seq'] for r in records if r['event'] == 'started'], list(range(2, 10)))
        self.assertEqual([summary[k] for k in ('started', 'holds', 'exits', 'completed')], [8] * 4)
        self.assertEqual(int.from_bytes(bytes.fromhex(hellos[1])[72:76], 'little'), 9)
        second_reports = [r for r in records if r['event'] == 'device_report_written' and r['connection'] == ready[1]['connection']]
        self.assertEqual(second_reports[0]['grantTotal'], 0)
        self.assertEqual(second_reports[0]['pending'], 8)
        self.assertEqual(second_reports[-1]['grantTotal'], 8)
        self.assertEqual(second_reports[-1]['phase'], 0)
        self.assertEqual(second_reports[-1]['pending'], 0)
        self.assertTrue(any(r['baseScreen'] == selected_base and r['displayRevision'] == 10 and r['grantTotal'] == 0 for r in second_reports))
        self.assertTrue(any(r['baseScreen'] == 2 and r['displayRevision'] == 11 and r['grantTotal'] == 0 for r in second_reports))
        self.assertTrue(all(r['grantTotal'] == 8 - r['pending'] for r in second_reports))
        views = [r for r in records if r['event'] == 'application_view' and r['connection'] == ready[1]['connection']]
        visible_field = source + 'Visible'
        self.assertTrue(any(r[visible_field] and r['baseScreen'] == selected_base and r['displayRevision'] == 10 for r in views))
        self.assertTrue(any(r['baseScreen'] == 0 and r['reason'] == 5 and not r[visible_field] for r in views))
        self.assertTrue(all(not r['cpuVisible'] and not r['weatherVisible'] for r in views if r['covered']))
        verify_expired_recovery(records, ready[1]['connection'])
        written = [r for r in records if r['event'] == 'device_report_written' and r['connection'] == ready[1]['connection']]
        raw_reports = [bytes.fromhex(raw) for raw in reports if int.from_bytes(bytes.fromhex(raw)[8:16], 'little') == 102]
        self.assertEqual(len(written), len(raw_reports))
        for item, raw in zip(written, raw_reports):
            for key, offset in (('displayRevision', 60), ('sourceGeneration', 64), ('twitchEpisode', 68),
                                ('weatherRevision', 72), ('cpuRevision', 76)):
                self.assertEqual(item[key], int.from_bytes(raw[offset:offset + 4], 'little'))
            self.assertEqual(item['baseScreen'], raw[80])
            self.assertEqual(item['reason'], raw[81])
        if EVIDENCE is not None:
            EVIDENCE.mkdir(parents=True, exist_ok=True)
            (EVIDENCE / (source + '-recovery-observations.json')).write_text(json.dumps(dict(records=records, receivedReports=reports,
                                                                                        helloPayloads=hellos), indent=2) + '\n')
        print(json.dumps(dict(syntheticRecoveryPassed=True, source=source, reportsReceived=len(reports), readyConnections=len(ready),
                             retainedCardsCompleted=8, productionRelayVerified=False, physicalVerified=False)))


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
