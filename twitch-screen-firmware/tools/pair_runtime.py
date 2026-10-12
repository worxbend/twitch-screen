#!/usr/bin/env python3
"""Pair the actual firmware application with an already running isolated relay.

Only loopback targets are allowed. This tool sends two synthetic INFO cards.
It does not start services, access credentials or use a physical device.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import threading
import urllib.request
from runtime_lifecycle import verify_report_phases
from test_production_views import observation_lines


def phases_valid(records):
    try:
        verify_report_phases(records)
    except (AssertionError, KeyError, TypeError):
        return False
    return True


def notification(port, title):
    request = urllib.request.Request(
        f"http://127.0.0.1:{port}/api/v1/notifications",
        data=json.dumps({"type": "info", "title": title, "body": "Synthetic firmware runtime pairing", "ttlMs": 1000}).encode(),
        headers={"Content-Type": "application/json"}, method="POST")
    with urllib.request.urlopen(request, timeout=5) as response:
        if response.status not in (200, 201, 202):
            raise RuntimeError(f"Notification rejected: {response.status}")
        return json.load(response)


def pressure_pairing(args, root, env):
    """Fill all eight pending slots, then prove TCP resume drains ten cards in order."""
    env = env.copy()
    env.pop("FIRMWARE_PEER_RECONNECT_SECONDS", None)
    env.update(FIRMWARE_PEER_SECONDS="40", FIRMWARE_PEER_REQUIRE_DESK="0",
               FIRMWARE_PEER_PROFILE_READY="0", FIRMWARE_PEER_START_DELAY_SECONDS="4")
    records, published = [], []
    with (args.evidence / "pressure-stderr.log").open("w") as errors:
        peer = subprocess.Popen([str(args.binary.resolve())], env=env, text=True,
                                stdout=subprocess.PIPE, stderr=errors)
        deadline = threading.Timer(50, peer.kill)
        deadline.start()
        try:
            assert peer.stdout is not None
            for line in observation_lines(peer):
                record = json.loads(line)
                records.append(record)
                if record["event"] == "welcome" and not published:
                    for index in range(10):
                        published.append(notification(args.http_port, f"Runtime queue card {index}"))
                if (root / ".agent-workflow/STOP").exists():
                    raise RuntimeError("STOP present")
            code = peer.wait(timeout=5)
        finally:
            deadline.cancel()
            if peer.poll() is None:
                peer.kill()
                peer.wait(timeout=5)
    (args.evidence / "pressure-observations.json").write_text(json.dumps(records, indent=2) + "\n")
    summary = next(item for item in records if item["event"] == "summary")
    queue = next(item for item in records if item["event"] == "queue_observation")
    observed = [item["seq"] for item in records if item["event"] == "enqueued"]
    expected = [item["seq"] for item in published]
    passed = (code == 0 and len(expected) == 10 and observed == expected and queue["peakPending"] == 8
              and summary["admittedClassic"] == summary["started"] == summary["holds"] == summary["exits"] == summary["completed"] == 10)
    return {"passed": passed, "exitCode": code, "peakPending": queue["peakPending"],
            "expectedSequences": expected, "observedSequences": observed, "summary": summary}


def conserved_fixture_credit(reports, expected, epoch):
    """Check every observation for this unexpired external-card fixture.

    Reports may coalesce admission/completion changes. Queue removal is justified
    only by ordered active identities; completion itself never releases credit.
    This narrow gate does not certify arbitrary traffic or terminal dispositions.
    """
    baseline = reports[0]["enqueuedSeq"]
    dequeued = 0
    counters = tuple(reports[0].get(key) for key in ("expiredTotal", "suppressedTotal", "refusedTotal"))
    if None in counters or not expected or len(set(expected)) != len(expected) or not all(seq > baseline for seq in expected):
        return False
    for report in reports:
        cursor = report["enqueuedSeq"]
        if cursor not in [baseline] + expected:
            return False
        admitted = 0 if cursor == baseline else expected.index(cursor) + 1
        if report["phase"]:
            if report["activeSeq"] not in expected or report.get("activeEpoch") != epoch or report.get("activeOrigin") != 0:
                return False
            current = expected.index(report["activeSeq"]) + 1
            if current < dequeued or current > dequeued + 1:
                return False
            dequeued = current
        elif report["activeSeq"] != 0:
            return False
        pending = report.get("pendingEntries")
        wanted = [{"epoch": epoch, "seq": seq, "origin": 0} for seq in expected[dequeued:admitted]]
        if (admitted < dequeued or report["grantTotal"] != 8 + dequeued
                or report["pending"] != admitted - dequeued or pending != wanted
                or tuple(report.get(key) for key in ("expiredTotal", "suppressedTotal", "refusedTotal")) != counters):
            return False
    return dequeued == len(expected)


def reconnect_verdict(code, records, published):
    """Require usable same-epoch recovery, not repeated local readiness alone.

    This fixture completes two cards before reconnect and one afterwards. The
    post-reconnect delivery witnesses initial report admission at a real relay;
    socket-written final reports still do not prove remote acceptance or pixels.
    """
    ready = [r for r in records if r['event'] == 'desk_ready']
    expected = [p['seq'] for p in published]
    summary = next((r for r in records if r['event'] == 'summary'), None)
    valid = bool(code == 0 and phases_valid(records) and summary and len(ready) == 2 and len(expected) == 3
                 and len(set(expected)) == 3 and expected == sorted(expected)
                 and ready[0]['epoch'] == ready[1]['epoch'] != '0000000000000000'
                 and ready[0]['connection'] != ready[1]['connection']
                 and [r['seq'] for r in records if r['event'] == 'started'] == expected
                 and all(summary.get(k) == 3 for k in ('started', 'holds', 'exits', 'completed')))
    reconnects = [i for i, r in enumerate(records) if r['event'] == 'reconnect_requested']
    if valid:
        second = records.index(ready[1])
        valid = (len(reconnects) == 1 and reconnects[0] < second
                 and [r['seq'] for r in records[:reconnects[0]] if r['event'] == 'started'] == expected[:2]
                 and [r['seq'] for r in records[second:] if r['event'] == 'started'] == expected[2:])
    counts = []
    if valid:
        for session, cards in zip(ready, (expected[:2], expected[2:])):
            reports = [r for r in records if r['event'] == 'device_report_written'
                       and r['epoch'] == session['epoch'] and r['connection'] == session['connection']]
            counts.append(len(reports))
            valid = bool(valid and reports and reports[0]['pending'] == reports[0]['phase'] == 0
                         and reports[0]['grantTotal'] == 8 and reports[0]['revision'] >= 1
                         and reports[-1]['pending'] == reports[-1]['phase'] == 0
                         and reports[-1]['enqueuedSeq'] == cards[-1]
                         and all(r['enqueuedSeq'] <= r['processedSeq'] for r in reports)
                         and all(b['revision'] > a['revision'] and b['processedSeq'] >= a['processedSeq']
                                 and b['enqueuedSeq'] >= a['enqueuedSeq']
                                 and b['observedDeviceMs'] >= a['observedDeviceMs']
                                 for a, b in zip(reports, reports[1:]))
                         and all({r['phase'] for r in reports if r['activeSeq'] == seq} >= {1, 2, 3} for seq in cards)
                         and conserved_fixture_credit(reports, cards, session['epoch']))
            if session is ready[0] and reports:
                valid = valid and records.index(reports[-1]) < reconnects[0]
            if session is ready[1] and reports:
                valid = valid and reports[0]['enqueuedSeq'] == expected[1] and reports[0]['processedSeq'] >= expected[1]
        valid = valid and all(r['epoch'] == ready[0]['epoch'] and r['origin'] == 0
                              for r in records if r['event'] == 'started')
        valid = valid and sum(counts) == len([r for r in records if r['event'] == 'device_report_written'])
    return dict(passed=bool(valid), exitCode=code, expectedSequences=expected,
                readyConnections=len(ready), socketWrittenReportCounts=counts,
                blocker=None if valid else 'Same-epoch capable reconnect did not deliver new work with conserved per-connection credit',
                physicalVerified=False)


def capable_verdict(code, records, published):
    """Validate observed lifecycle and complete socket-written reports, not ACK guesses."""
    summary = next((item for item in records if item["event"] == "summary"), None)
    ready = next((item for item in records if item["event"] == "desk_ready"), None)
    expected = [item["seq"] for item in published]
    started = [item for item in records if item["event"] == "started"]
    observed = [item["seq"] for item in started]
    reports = [item for item in records if item["event"] == "device_report_written"]
    lifecycles = bool(code == 0 and summary and ready and summary["deskReadyObserved"] and len(expected) == 2
                     and observed == expected and all(item["origin"] == 0 and item["epoch"] == ready["epoch"] for item in started)
                     and ready["epoch"] != "0000000000000000"
                     and summary["started"] == summary["holds"] == summary["exits"] == summary["completed"] == 2)
    report_ok = bool(ready and reports and all(item["epoch"] == ready["epoch"] and item["connection"] == ready["connection"]
                                             and item["pending"] <= 8 and item["enqueuedSeq"] <= item["processedSeq"] for item in reports))
    if report_ok and ready is not None:
        report_ok = (reports[0]["pending"] == reports[0]["phase"] == 0 and reports[0]["grantTotal"] == 8
                     and reports[-1]["pending"] == reports[-1]["phase"] == 0
                     and reports[-1]["grantTotal"] == 10 and reports[-1]["enqueuedSeq"] == (expected[-1] if expected else -1)
                     and all(later["revision"] > earlier["revision"] and later["grantTotal"] >= earlier["grantTotal"]
                             and later["processedSeq"] >= earlier["processedSeq"] and later["enqueuedSeq"] >= earlier["enqueuedSeq"]
                             and later["observedDeviceMs"] >= earlier["observedDeviceMs"]
                             for earlier, later in zip(reports, reports[1:]))
                     and all({item["phase"] for item in reports if item["activeSeq"] == seq} >= {1, 2, 3} for seq in expected)
                     and conserved_fixture_credit(reports, expected, ready["epoch"]))
    report_ok = report_ok and phases_valid(records)
    passed = lifecycles and report_ok
    return {"passed": passed, "exitCode": code, "expectedSequences": expected,
            "observedSequences": observed, "summary": summary, "socketWrittenReportCount": len(reports),
            "reportLifecyclePassed": report_ok,
            "blocker": None if passed else "Actual capable synchronization, ordered generic lifecycle and socket-written reports not observed"}


def capable_pairing(args, root, env):
    """Require real DESK synchronization and two ordered generic card lifecycles."""
    env = env.copy()
    env.pop("FIRMWARE_PEER_RECONNECT_SECONDS", None)
    env.update(FIRMWARE_PEER_SECONDS="10", FIRMWARE_PEER_REQUIRE_DESK="1",
               FIRMWARE_PEER_PROFILE_READY="1")
    records, published = [], []
    with (args.evidence / "capable-stderr.log").open("w") as errors:
        peer = subprocess.Popen([str(args.binary.resolve())], env=env, text=True,
                                stdout=subprocess.PIPE, stderr=errors)
        deadline = threading.Timer(15, peer.kill)
        deadline.start()
        try:
            assert peer.stdout is not None
            for line in observation_lines(peer):
                record = json.loads(line)
                records.append(record)
                if record["event"] == "desk_ready" and not published:
                    for index in range(2):
                        published.append(notification(args.http_port, f"Runtime DESK card {index}"))
                if (root / ".agent-workflow/STOP").exists():
                    raise RuntimeError("STOP present")
            code = peer.wait(timeout=5)
        finally:
            deadline.cancel()
            if peer.poll() is None:
                peer.kill()
                peer.wait(timeout=5)
    (args.evidence / "capable-observations.json").write_text(json.dumps(records, indent=2) + "\n")
    return capable_verdict(code, records, published)


def capable_reconnect_pairing(args, root, env):
    """Publish after each synchronized connection; refuse readiness-only recovery."""
    env = env.copy()
    env.update(FIRMWARE_PEER_SECONDS='18', FIRMWARE_PEER_REQUIRE_DESK='1',
               FIRMWARE_PEER_PROFILE_READY='1', FIRMWARE_PEER_RECONNECT_SECONDS='6',
               FIRMWARE_PEER_MAX_WRITE_BYTES='13')
    records, published = [], []
    ready_count = 0
    with (args.evidence / 'capable-reconnect-stderr.log').open('w') as errors:
        peer = subprocess.Popen([str(args.binary.resolve())], env=env, text=True,
                                stdout=subprocess.PIPE, stderr=errors)
        deadline = threading.Timer(25, peer.kill)
        deadline.start()
        try:
            assert peer.stdout is not None
            for line in observation_lines(peer):
                record = json.loads(line)
                records.append(record)
                if record['event'] == 'desk_ready':
                    ready_count += 1
                    if ready_count <= 2:
                        for index in range(2 if ready_count == 1 else 1):
                            published.append(notification(args.http_port, f'Runtime reconnect card {ready_count}/{index}'))
                if (root / '.agent-workflow/STOP').exists():
                    raise RuntimeError('STOP present')
            code = peer.wait(timeout=5)
        finally:
            deadline.cancel()
            if peer.poll() is None:
                peer.kill()
                peer.wait(timeout=5)
    (args.evidence / 'capable-reconnect-observations.json').write_text(json.dumps(records, indent=2) + '\n')
    return reconnect_verdict(code, records, published)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", required=True, type=Path)
    parser.add_argument("--http-port", required=True, type=int)
    parser.add_argument("--device-port", required=True, type=int)
    parser.add_argument("--evidence", required=True, type=Path)
    parser.add_argument("--queue-pressure", action="store_true", help="Also exercise ten cards across eight pending slots")
    parser.add_argument("--require-desk", action="store_true", help="Fail unless actual DESK synchronization and generic lifecycles pass")
    parser.add_argument("--desk-reconnect", action="store_true", help="Also require usable same-epoch capable reconnect; needs --require-desk")
    args = parser.parse_args()
    if args.desk_reconnect and not args.require_desk:
        parser.error('--desk-reconnect requires --require-desk')
    if not all(1 <= port <= 65535 for port in (args.http_port, args.device_port)):
        parser.error("Ports must be in 1..65535")
    root = Path(__file__).resolve().parents[2]
    if (root / ".agent-workflow/STOP").exists():
        raise SystemExit("STOP present")
    os.umask(0o077)
    args.evidence.mkdir(parents=True, exist_ok=True)
    with urllib.request.urlopen(f"http://127.0.0.1:{args.http_port}/api/v1/health", timeout=5) as response:
        if response.status != 200:
            raise RuntimeError("Relay health check failed")
    env = {key: value for key, value in os.environ.items() if not key.startswith("FIRMWARE_PEER_")}
    env.update(FIRMWARE_PEER_PORT=str(args.device_port), FIRMWARE_PEER_SECONDS="18",
               FIRMWARE_PEER_RECONNECT_SECONDS="4", FIRMWARE_PEER_REQUIRE_DESK="0",
               FIRMWARE_PEER_PROFILE_READY="0")
    records, publications = [], []
    with (args.evidence / "classic-stderr.log").open("w") as errors:
        peer = subprocess.Popen([str(args.binary.resolve())], env=env, text=True,
                                stdout=subprocess.PIPE, stderr=errors)
        deadline = threading.Timer(25, peer.kill)
        deadline.start()
        try:
            assert peer.stdout is not None
            for line in observation_lines(peer):
                record = json.loads(line)
                records.append(record)
                if record["event"] == "welcome" and not publications:
                    publications.append(notification(args.http_port, "Runtime before reconnect"))
                elif record["event"] == "reconnect_requested":
                    publications.append(notification(args.http_port, "Runtime retained during disconnect"))
                if (root / ".agent-workflow/STOP").exists():
                    raise RuntimeError("STOP present")
            exit_code = peer.wait(timeout=5)
        finally:
            deadline.cancel()
            if peer.poll() is None:
                peer.kill()
                peer.wait(timeout=5)
    (args.evidence / "classic-observations.json").write_text(json.dumps(records, indent=2) + "\n")
    summary = next(item for item in records if item["event"] == "summary")
    enqueued = [item for item in records if item["event"] == "enqueued"]
    classic_ok = (exit_code == 0 and summary["greetings"] >= 2 and len(publications) == 2
                  and len(enqueued) == 2 and len({item["seq"] for item in enqueued}) == 2
                  and any(item["replay"] for item in enqueued)
                  and summary["started"] == summary["holds"] == summary["exits"] == summary["completed"] == 2)
    env.pop("FIRMWARE_PEER_RECONNECT_SECONDS")
    env.update(FIRMWARE_PEER_SECONDS="3", FIRMWARE_PEER_REQUIRE_DESK="1", FIRMWARE_PEER_PROFILE_READY="1")
    required = subprocess.run([str(args.binary.resolve())], env=env, capture_output=True, text=True, timeout=10)
    (args.evidence / "required-desk-stderr.log").write_text(required.stderr)
    desk_records = [json.loads(line) for line in required.stdout.splitlines()]
    (args.evidence / "required-desk-observations.json").write_text(json.dumps(desk_records, indent=2) + "\n")
    desk_summary = next(item for item in desk_records if item["event"] == "summary")
    honest_desk = ((required.returncode == 0 and desk_summary["deskReadyObserved"])
                   or (required.returncode == 1 and not desk_summary["deskReadyObserved"] and not desk_summary["success"]))
    pressure = pressure_pairing(args, root, env) if args.queue_pressure else None
    capable = capable_pairing(args, root, env) if args.require_desk else None
    reconnect = capable_reconnect_pairing(args, root, env) if args.desk_reconnect else None
    result = {"classicApplicationPairingPassed": classic_ok, "classicPeerExitCode": exit_code,
              "capableReconnect": reconnect,
              "capableApplicationPairing": capable,
              "classicQueuePressure": pressure,
              "requiredDeskPeerExitCode": required.returncode, "requiredDeskReadinessPassed": desk_summary["deskReadyObserved"],
              "requiredDeskFailureIsHonest": honest_desk, "publications": publications,
              "testedLayer": "Actual current-source Scala HTTP/TCP and firmware LinkSession/Controller/Receiver/LVGL memory display",
              "physicalVerified": False, "productAccepted": False}
    (args.evidence / "pairing-result.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))
    return 0 if (classic_ok and honest_desk and (pressure is None or pressure["passed"])
                 and (capable is None or capable["passed"]) and (reconnect is None or reconnect['passed'])) else 1


if __name__ == "__main__":
    raise SystemExit(main())
