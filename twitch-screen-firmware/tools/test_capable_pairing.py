#!/usr/bin/env python3
"""Test pairing verdicts and the actual runtime executable with a synthetic TCP peer.

Canonical fixture traffic is explicitly synthetic, never production relay acceptance.
No board, credentials, HTTP service or desktop session is accessed.
"""
import argparse
import json
import os
from pathlib import Path
import socket
import subprocess
import threading
import unittest
from pair_runtime import capable_verdict

ROOT = Path(__file__).resolve().parents[2]
BINARY = None


def observations():
    ready = dict(event="desk_ready", epoch="0123456789abcdef", connection="fedcba9876543210", grantTotal=8)
    records: list[dict] = [ready]
    for index, (seq, phase, grant) in enumerate(((0, 0, 8), (2, 1, 9), (2, 2, 9), (2, 3, 9),
                                                (3, 1, 10), (3, 2, 10), (3, 3, 10), (0, 0, 10))):
        records.append(dict(event="device_report_written", epoch=ready["epoch"], connection=ready["connection"],
                            revision=index + 1, grantTotal=grant, pending=1 if seq == 2 else 0, phase=phase, activeSeq=seq,
                            activeEpoch=ready["epoch"] if seq else "0000000000000000", activeOrigin=0,
                            pendingEntries=[dict(epoch=ready["epoch"], seq=3, origin=0)] if seq == 2 else [],
                            expiredTotal=0, suppressedTotal=0, refusedTotal=0,
                            processedSeq=3 if index else 1, enqueuedSeq=3 if index else 1,
                            observedDeviceMs=index * 200))
    records += [dict(event="started", seq=seq, epoch=ready["epoch"], origin=0) for seq in (2, 3)]
    records.append(dict(event="summary", deskReadyObserved=True, started=2, holds=2, exits=2, completed=2))
    return records


class VerdictTests(unittest.TestCase):
    def verdict(self, records, code=0):
        return capable_verdict(code, records, [{"seq": 2}, {"seq": 3}])["passed"]

    def test_complete_observations(self):
        self.assertTrue(self.verdict(observations()))

    def test_phase_set_cannot_hide_exit_to_hold(self):
        records = observations()
        active = [r for r in records if r['event']=='device_report_written' and r['activeSeq']==2]
        active[1]['phase'], active[2]['phase'] = active[2]['phase'], active[1]['phase']
        self.assertEqual({r['phase'] for r in active}, {1,2,3})
        self.assertFalse(self.verdict(records))

    def test_fallback_and_missing_summary(self):
        self.assertFalse(self.verdict([], 1))
        self.assertFalse(self.verdict(observations()[:-1]))

    def test_readiness_without_written_reports_is_not_acceptance(self):
        self.assertFalse(self.verdict([r for r in observations() if r["event"] != "device_report_written"]))

    def test_wrong_identity_or_provenance(self):
        for field, value in (("epoch", "0000000000000000"), ("origin", 1), ("seq", 99)):
            records = observations()
            next(r for r in records if r["event"] == "started")[field] = value
            self.assertFalse(self.verdict(records), field)

    def test_report_session_revision_credit_and_phase(self):
        for field, value in (("connection", "0000000000000000"), ("revision", 1), ("grantTotal", 7),
                             ("phase", 0), ("pending", 9), ("enqueuedSeq", 4), ("observedDeviceMs", 0)):
            records = observations()
            reports = [r for r in records if r["event"] == "device_report_written"]
            reports[2][field] = value
            self.assertFalse(self.verdict(records), field)

    def test_final_empty_report_and_single_dequeue_credit(self):
        for field, value in (("pending", 1), ("phase", 3), ("grantTotal", 12), ("enqueuedSeq", 2)):
            records = observations()
            [r for r in records if r["event"] == "device_report_written"][-1][field] = value
            self.assertFalse(self.verdict(records), field)

    def test_early_overgrant_is_rejected(self):
        records = observations()
        for report in (r for r in records if r["event"] == "device_report_written"):
            if report["revision"] > 1:
                report["grantTotal"] = 10
            if report["activeSeq"] == 2:
                report["pending"] = 1
        self.assertFalse(self.verdict(records))

    def test_late_or_missing_replacement_is_rejected(self):
        for index in (1, 4):
            records = observations()
            reports = [r for r in records if r["event"] == "device_report_written"]
            reports[index]["grantTotal"] -= 1
            self.assertFalse(self.verdict(records))

    def test_duplicate_report_cannot_create_credit(self):
        records = observations()
        reports = [r for r in records if r["event"] == "device_report_written"]
        records.insert(3, dict(reports[1]))
        self.assertFalse(self.verdict(records))

    def test_completion_does_not_grant_and_coalescing_is_valid(self):
        records = observations()
        reports = [r for r in records if r["event"] == "device_report_written"]
        idle = dict(reports[3], revision=5, phase=0, activeSeq=0, activeEpoch="0000000000000000")
        for report in reports[4:]:
            report["revision"] += 1
        records.insert(5, idle)
        self.assertTrue(self.verdict(records))
        idle["grantTotal"] = 10
        self.assertFalse(self.verdict(records))

    def test_coalesced_admission_preserves_conservation(self):
        records = observations()
        first = next(r for r in records if r["event"] == "device_report_written" and r["activeSeq"] == 2)
        first.update(enqueuedSeq=2, processedSeq=2, pending=0, pendingEntries=[])
        self.assertTrue(self.verdict(records))

    def test_pending_identity_and_disposition_must_match_fixture(self):
        for field, value in (("pendingEntries", []), ("activeOrigin", 1), ("expiredTotal", 1),
                             ("suppressedTotal", 1), ("refusedTotal", 1)):
            records = observations()
            next(r for r in records if r["event"] == "device_report_written" and r["activeSeq"] == 2)[field] = value
            self.assertFalse(self.verdict(records), field)

    def test_incomplete_or_failed_child(self):
        records = observations()
        records[-1]["completed"] = 1
        self.assertFalse(self.verdict(records))
        self.assertFalse(self.verdict(observations(), 1))


def exact(connection, size):
    data = bytearray()
    while len(data) < size:
        chunk = connection.recv(size - len(data))
        if not chunk:
            raise EOFError
        data.extend(chunk)
    return bytes(data)


def receive(connection):
    header = exact(connection, 8)
    assert header[:3] == bytes.fromhex("a75303")
    assert header[7] == (255 ^ (sum((i + 1) * header[i] for i in range(7)) & 255))
    return header[3], exact(connection, int.from_bytes(header[4:6], "little"))


class FixtureTraffic:
    def __init__(self):
        self.vectors = {v["name"]: v for v in json.loads((ROOT / "protocol/desk-v1-vectors.json").read_text())["vectors"]}
        self.layouts = {r["name"]: r for r in json.loads((ROOT / "protocol/desk-v1-layouts.json").read_text())["records"]}

    def frame(self, name, **changes):
        vector = self.vectors[name]
        frame = bytearray.fromhex(vector["hex"])
        fields = {f["name"]: f for f in self.layouts[vector["record"]]["fields"]}
        for name, value in changes.items():
            field = fields[name]
            offset = 8 + field["offset"]
            frame[offset:offset + field["width"]] = int(value).to_bytes(field["width"], "little", signed=field["type"].startswith("i"))
        return bytes(frame)

    def greeting(self, probe):
        sync = self.vectors["sync_end"]["fields"]
        return [self.frame("welcome", latestSeq=1, echoedProbeDeviceMs=probe),
                self.frame("display_cpu", revision=sync["displayRevision"]),
                self.frame("weather_missing", revision=sync["weatherRevision"]),
                self.frame("cpu_missing", revision=sync["cpuRevision"]),
                self.frame("stats_observed", statsRevision=sync["statsRevision"]),
                self.frame("sync_end")]


class ActualRuntimeTests(unittest.TestCase):
    def test_actual_socket_report_and_lifecycle(self):
        self.run_runtime(256)

    def test_short_socket_writes_preserve_report_observation(self):
        self.run_runtime(13)

    def run_runtime(self, maximum_write):
        if BINARY is None:
            self.skipTest("Explicit --binary required for actual runtime")
        fixture = FixtureTraffic()
        errors, received = [], []
        with socket.socket() as listener:
            listener.bind(("127.0.0.1", 0)); listener.listen(1); listener.settimeout(15)
            port = listener.getsockname()[1]

            def serve():
                try:
                    with listener.accept()[0] as connection:
                        connection.settimeout(12)
                        kind, hello = receive(connection)
                        self.assertEqual(kind, 1)
                        self.assertEqual(len(hello), 92)
                        self.assertTrue(int.from_bytes(hello[4:8], "little") & 16)
                        probe = int.from_bytes(hello[84:92], "little")
                        # Fragment every initial frame to exercise actual short reads.
                        for frame in fixture.greeting(probe):
                            for offset in range(0, len(frame), 7):
                                connection.sendall(frame[offset:offset + 7])
                        sent = False
                        while True:
                            kind, payload = receive(connection)
                            if kind == 6:
                                received.append(payload.hex())
                                if not sent:
                                    self.assertEqual(int.from_bytes(payload[44:52], "little"), 8)
                                    for seq in (2, 3):
                                        connection.sendall(fixture.frame("external_twitch_kind", seq=seq, kind=0, ttlDs=10,
                                                                        deadlineRelayMs=30000))
                                    sent = True
                except EOFError:
                    pass
                except BaseException as error:
                    errors.append(error)

            worker = threading.Thread(target=serve)
            worker.start()
            env = {k: v for k, v in os.environ.items() if not k.startswith("FIRMWARE_PEER_")}
            env.update(FIRMWARE_PEER_PORT=str(port), FIRMWARE_PEER_SECONDS="8", FIRMWARE_PEER_REQUIRE_DESK="1",
                       FIRMWARE_PEER_PROFILE_READY="1", FIRMWARE_PEER_MAX_WRITE_BYTES=str(maximum_write))
            result = subprocess.run([str(BINARY)], env=env, capture_output=True, text=True, timeout=15)
            worker.join(timeout=15)
            self.assertFalse(worker.is_alive())
            self.assertFalse(errors, errors)
            records = [json.loads(line) for line in result.stdout.splitlines()]
            metrics = next(r for r in records if r['event'] == 'runtime_metrics')
            self.assertGreater(metrics['loopSamples'], 0)
            self.assertEqual(metrics['uiSamples'], metrics['loopSamples'] + 1)
            self.assertGreater(metrics['loopMaxUs'], 0)
            self.assertLess(metrics['loopP95UpperUs'], 2**32 - 1)
            self.assertLess(metrics['uiP95UpperUs'], 2**32 - 1)
            self.assertFalse(metrics['physicalVerified'])
            verdict = capable_verdict(result.returncode, records, [{"seq": 2}, {"seq": 3}])
            self.assertTrue(verdict["passed"], (verdict, result.stderr, records))
            written = [r for r in records if r["event"] == "device_report_written"]
            self.assertEqual(len(written), len(received))
            for observation, payload in zip(written, received):
                raw = bytes.fromhex(payload)
                self.assertEqual(observation["revision"], int.from_bytes(raw[24:28], "little"))
                self.assertEqual(observation["grantTotal"], int.from_bytes(raw[44:52], "little"))
                self.assertEqual(observation["activeSeq"], int.from_bytes(raw[108:112], "little"))
                self.assertEqual(observation["activeEpoch"], f'{int.from_bytes(raw[100:108], "little"):016x}')
                self.assertEqual(observation["activeOrigin"], raw[113])
                self.assertEqual(observation["pending"], raw[83])
                self.assertEqual(observation["pendingEntries"], [
                    dict(epoch=f'{int.from_bytes(raw[offset:offset + 8], "little"):016x}',
                         seq=int.from_bytes(raw[offset + 8:offset + 12], "little"), origin=raw[offset + 13])
                    for offset in range(116, 116 + 16 * raw[83], 16)])
                for key, offset in (("expiredTotal", 88), ("suppressedTotal", 92), ("refusedTotal", 96)):
                    self.assertEqual(observation[key], int.from_bytes(raw[offset:offset + 4], "little"))
            print(json.dumps({"syntheticSocketRuntimeVerdict": verdict, "receivedReportCount": len(received),
                              "runtimeMetrics": metrics, "maximumWriteBytes": maximum_write,
                              "productionRelayVerified": False, "physicalVerified": False}), flush=True)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", type=Path)
    args = parser.parse_args()
    BINARY = args.binary.resolve() if args.binary else None
    if (ROOT / ".agent-workflow/STOP").exists():
        raise SystemExit("STOP present")
    unittest.main(argv=[__file__], verbosity=2)
