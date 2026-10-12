#!/usr/bin/env python3
"""Check the reconnect verdict and actual application with synthetic TCP traffic."""
import argparse
import json
import os
from pathlib import Path
import socket
import subprocess
import threading
import unittest
from pair_runtime import reconnect_verdict
from test_capable_pairing import FixtureTraffic, observations, receive

BINARY = None
EVIDENCE = None


def reconnect_observations():
    """Explicit verdict-only fixture; never a runtime result."""
    records = observations()[:-1]
    first = records[0]
    records.append(dict(event='reconnect_requested'))
    second = dict(first, connection='0000000000000066')
    records.append(second)
    for index, phase in enumerate((0, 1, 2, 3, 0)):
        records.append(dict(event='device_report_written', epoch=first['epoch'], connection=second['connection'],
                            revision=index + 1, grantTotal=8 if index == 0 else 9, pending=0, phase=phase,
                            activeSeq=4 if phase else 0, activeEpoch=first['epoch'] if phase else '0000000000000000',
                            activeOrigin=0, pendingEntries=[], processedSeq=3 if index == 0 else 4,
                            enqueuedSeq=3 if index == 0 else 4, observedDeviceMs=2000 + index * 200,
                            expiredTotal=0, suppressedTotal=0, refusedTotal=0))
    records.append(dict(event='started', seq=4, epoch=first['epoch'], origin=0))
    records.append(dict(event='summary', started=3, holds=3, exits=3, completed=3))
    return records


class ReconnectVerdictTests(unittest.TestCase):
    def verdict(self, records, code=0):
        return reconnect_verdict(code, records, [dict(seq=s) for s in (2, 3, 4)])['passed']

    def test_conserved_session_reset(self):
        self.assertTrue(self.verdict(reconnect_observations()))

    def test_phase_set_cannot_hide_exit_to_hold(self):
        records = reconnect_observations()
        active = [r for r in records if r['event']=='device_report_written' and r['activeSeq']==4]
        active[1]['phase'], active[2]['phase'] = active[2]['phase'], active[1]['phase']
        self.assertEqual({r['phase'] for r in active}, {1,2,3})
        self.assertFalse(self.verdict(records))

    def test_repeated_readiness_without_delivery_fails(self):
        records = reconnect_observations()
        records.insert(-1, dict(records[0], connection='0000000000000067'))
        self.assertFalse(self.verdict(records))
        self.assertFalse(self.verdict([r for r in records if r['event'] != 'started']))

    def test_epoch_connection_and_resume_boundary(self):
        for field, value in (('epoch', '0000000000000000'), ('connection', 'fedcba9876543210')):
            records = reconnect_observations()
            [r for r in records if r['event'] == 'desk_ready'][1][field] = value
            self.assertFalse(self.verdict(records))
        for field, value in (('enqueuedSeq', 0), ('processedSeq', 2), ('grantTotal', 10), ('revision', 0)):
            records = reconnect_observations()
            reports = [r for r in records if r['event'] == 'device_report_written' and r['connection'] == '0000000000000066']
            reports[0][field] = value
            # Revision zero is structurally impossible for the real observer.
            if field == 'revision':
                reports[1]['revision'] = 0
            self.assertFalse(self.verdict(records), field)

    def test_early_credit_and_completion_credit_fail(self):
        for index in (1, -1):
            records = reconnect_observations()
            reports = [r for r in records if r['event'] == 'device_report_written' and r['connection'] == '0000000000000066']
            reports[index]['grantTotal'] = 10
            self.assertFalse(self.verdict(records))

    def test_missing_phase_failed_child_and_late_first_completion(self):
        records = reconnect_observations()
        self.assertFalse(self.verdict(records, 1))
        self.assertFalse(self.verdict([r for r in records if r.get('phase') != 2]))
        first_reports = [r for r in records if r['event'] == 'device_report_written' and r['connection'] == records[0]['connection']]
        first_reports[-1].update(phase=3, activeSeq=3)
        self.assertFalse(self.verdict(records))


class ActualReconnectTests(unittest.TestCase):
    def test_actual_short_write_same_epoch_reconnect(self):
        if BINARY is None:
            self.skipTest('Explicit --binary required')
        fixture = FixtureTraffic()
        errors, received, hellos = [], [], []
        with socket.socket() as listener:
            listener.bind(('127.0.0.1', 0))
            listener.listen(2)
            listener.settimeout(20)
            port = listener.getsockname()[1]

            def serve():
                try:
                    for session in range(2):
                        with listener.accept()[0] as connection:
                            connection.settimeout(20)
                            kind, hello = receive(connection)
                            self.assertEqual(kind, 1)
                            hellos.append(hello.hex())
                            probe = int.from_bytes(hello[84:92], 'little')
                            identity = 101 + session
                            sync = fixture.vectors['sync_end']['fields']
                            batch = [fixture.frame('welcome', latestSeq=1 if session == 0 else 3,
                                                   connectionId=identity, echoedProbeDeviceMs=probe, sampledRelayMs=5000 + probe),
                                     fixture.frame('display_cpu', connectionId=identity, revision=sync['displayRevision']),
                                     fixture.frame('weather_missing', connectionId=identity, revision=sync['weatherRevision']),
                                     fixture.frame('cpu_missing', connectionId=identity, revision=sync['cpuRevision']),
                                     fixture.frame('stats_observed', connectionId=identity, statsRevision=sync['statsRevision']),
                                     fixture.frame('sync_end', connectionId=identity)]
                            for frame in batch:
                                connection.sendall(frame)
                            sent = False
                            while True:
                                try:
                                    kind, payload = receive(connection)
                                except EOFError:
                                    break
                                if kind != 6:
                                    continue
                                received.append(payload.hex())
                                if not sent:
                                    for seq in ((2, 3) if session == 0 else (4,)):
                                        connection.sendall(fixture.frame('external_twitch_kind', seq=seq, kind=0, ttlDs=10,
                                                                        deadlineRelayMs=65000))
                                    sent = True
                except BaseException as error:
                    errors.append(repr(error))

            worker = threading.Thread(target=serve)
            worker.start()
            env = {k: v for k, v in os.environ.items() if not k.startswith('FIRMWARE_PEER_')}
            env.update(FIRMWARE_PEER_PORT=str(port), FIRMWARE_PEER_SECONDS='14',
                       FIRMWARE_PEER_REQUIRE_DESK='1', FIRMWARE_PEER_PROFILE_READY='1',
                       FIRMWARE_PEER_RECONNECT_SECONDS='6', FIRMWARE_PEER_MAX_WRITE_BYTES='13')
            result = subprocess.run([str(BINARY)], env=env, capture_output=True, text=True, timeout=22)
            worker.join(timeout=21)
            self.assertFalse(worker.is_alive())
            self.assertFalse(errors, (errors, result.stderr))
        records = [json.loads(line) for line in result.stdout.splitlines()]
        verdict = reconnect_verdict(result.returncode, records, [dict(seq=s) for s in (2, 3, 4)])
        self.assertTrue(verdict['passed'], (verdict, records, result.stderr))
        self.assertEqual(int.from_bytes(bytes.fromhex(hellos[1])[72:76], 'little'), 3)
        self.assertEqual(int.from_bytes(bytes.fromhex(hellos[1])[0:4], 'little'), 0)
        written = [r for r in records if r['event'] == 'device_report_written']
        self.assertEqual(len(written), len(received))
        for report, raw in zip(written, received):
            payload = bytes.fromhex(raw)
            self.assertEqual(report['connection'], f'{int.from_bytes(payload[8:16], "little"):016x}')
            for field, offset, width in (('revision', 24, 4), ('grantTotal', 44, 8),
                                         ('processedSeq', 52, 4), ('enqueuedSeq', 56, 4)):
                self.assertEqual(report[field], int.from_bytes(payload[offset:offset + width], 'little'))
        evidence = dict(verdict=verdict, records=records, receivedReports=received, helloPayloads=hellos,
                        productionRelayVerified=False, physicalVerified=False)
        if EVIDENCE is not None:
            EVIDENCE.mkdir(parents=True, exist_ok=True)
            (EVIDENCE / 'capable-reconnect.json').write_text(json.dumps(evidence, indent=2) + '\n')
        print(json.dumps(dict(syntheticReconnectPassed=True, reportCount=len(received), verdict=verdict,
                             productionRelayVerified=False, physicalVerified=False)))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binary', type=Path)
    parser.add_argument('--evidence', type=Path)
    args = parser.parse_args()
    os.umask(0o077)
    BINARY = args.binary.resolve() if args.binary else None
    EVIDENCE = args.evidence.resolve() if args.evidence else None
    root = Path(__file__).resolve().parents[2]
    if (root / '.agent-workflow/STOP').exists():
        raise SystemExit('STOP present')
    unittest.main(argv=[__file__], verbosity=2)
