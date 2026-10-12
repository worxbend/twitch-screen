#!/usr/bin/env python3
"""Exercise runtime-peer argument refusal and actual no-listener failure."""
import argparse
import json
import os
from pathlib import Path
import socket
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", required=True, type=Path)
    args = parser.parse_args()
    env = {key: value for key, value in os.environ.items() if not key.startswith("FIRMWARE_PEER_")}
    cases = [({}, 2), ({"FIRMWARE_PEER_PORT": "0", "FIRMWARE_PEER_SECONDS": "1"}, 2),
             ({"FIRMWARE_PEER_PORT": "65536", "FIRMWARE_PEER_SECONDS": "1"}, 2),
             ({"FIRMWARE_PEER_PORT": "1", "FIRMWARE_PEER_SECONDS": "301"}, 2),
             ({"FIRMWARE_PEER_PORT": "1", "FIRMWARE_PEER_SECONDS": "1", "FIRMWARE_PEER_REQUIRE_DESK": "yes"}, 2),
             ({"FIRMWARE_PEER_PORT": "1", "FIRMWARE_PEER_SECONDS": "1", "FIRMWARE_PEER_RECONNECT_SECONDS": "2"}, 2),
             ({"FIRMWARE_PEER_PORT": "1", "FIRMWARE_PEER_SECONDS": "1", "FIRMWARE_PEER_START_DELAY_SECONDS": "2"}, 2),
             ({"FIRMWARE_PEER_PORT": "1", "FIRMWARE_PEER_SECONDS": "1", "FIRMWARE_PEER_PROFILE_READY": "yes"}, 2),
             ({"FIRMWARE_PEER_PORT": "1", "FIRMWARE_PEER_SECONDS": "1", "FIRMWARE_PEER_PROFILE_READY": "0", "FIRMWARE_PEER_REQUIRE_DESK": "1"}, 2),
             ({"FIRMWARE_PEER_PORT": "1", "FIRMWARE_PEER_SECONDS": "1", "FIRMWARE_PEER_MAX_WRITE_BYTES": "0"}, 2),
             ({"FIRMWARE_PEER_PORT": "1", "FIRMWARE_PEER_SECONDS": "1", "FIRMWARE_PEER_MAX_WRITE_BYTES": "257"}, 2),
             ({"FIRMWARE_PEER_PORT": "1", "FIRMWARE_PEER_SECONDS": "1", "FIRMWARE_PEER_MAX_READ_BYTES": "0"}, 2),
             ({"FIRMWARE_PEER_PORT": "1", "FIRMWARE_PEER_SECONDS": "1", "FIRMWARE_PEER_MAX_READ_BYTES": "257"}, 2),
             ({"FIRMWARE_PEER_PORT": "1", "FIRMWARE_PEER_SECONDS": "1", "FIRMWARE_PEER_SEND_BUFFER_BYTES": "1023"}, 2),
             ({"FIRMWARE_PEER_PORT": "1", "FIRMWARE_PEER_SECONDS": "1", "FIRMWARE_PEER_SEND_BUFFER_BYTES": "65537"}, 2)]
    results = []
    for values, expected in cases:
        result = subprocess.run([str(args.binary.resolve())], env=dict(env, **values), capture_output=True, text=True, timeout=5)
        assert result.returncode == expected, (values, result.returncode, result.stderr)
        results.append({"inputs": values, "exitCode": result.returncode})
    # Keep the allocated socket bound but not listening, so another test cannot
    # occupy this no-listener target between port allocation and connect.
    with socket.socket() as reservation:
        reservation.bind(("127.0.0.1", 0))
        values = {"FIRMWARE_PEER_PORT": str(reservation.getsockname()[1]), "FIRMWARE_PEER_SECONDS": "1"}
        result = subprocess.run([str(args.binary.resolve())], env=dict(env, **values), capture_output=True, text=True, timeout=5)
        assert result.returncode == 1, (result.returncode, result.stderr)
        summary = next(json.loads(line) for line in result.stdout.splitlines() if json.loads(line)["event"] == "summary")
        assert not summary["success"] and summary["greetings"] == 0 and not summary["deskReadyObserved"]
        results.append({"case": "actual-no-listener", "exitCode": result.returncode, "summary": summary})
    print(json.dumps({"cases": results, "passed": True}, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
