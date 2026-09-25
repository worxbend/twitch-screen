#!/usr/bin/env python3
"""Check both implementations against the normative TSB/3 vectors.

Usage: check_protocol_vectors.py [PROTOCOL.md]   (defaults to the firmware specification)
"""

from pathlib import Path
import re
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
FIRMWARE = ROOT / "twitch-screen-firmware"
SPEC = FIRMWARE / "docs/PROTOCOL.md"
RELAY_VECTORS = ROOT / "twitch-screen-relay/test/src/twitchscreen/relay/protocol/Tsb3Vectors.scala"


def decode_hex(number, block, origin):
    try:
        return bytes.fromhex(block)
    except ValueError as error:
        raise ValueError(f"V{number}: malformed hex block in {origin}: {error}") from error


def spec_vectors(spec=SPEC):
    source = spec.read_text(encoding="utf-8")
    start, end = "<!-- tsb3-golden-vectors:start -->", "<!-- tsb3-golden-vectors:end -->"
    if source.count(start) != 1 or source.count(end) != 1 or source.index(start) >= source.index(end):
        raise ValueError("PROTOCOL.md must contain one ordered pair of tsb3-golden-vectors anchors")
    section = source.split(start, 1)[1].split(end, 1)[0]
    blocks = re.findall(r"^### V(\d+)\..*?\n```\n(.*?)```", section, re.M | re.S)
    vectors = {int(number): decode_hex(number, block, spec.name) for number, block in blocks}
    if len(blocks) != 20 or set(vectors) != set(range(1, 21)):
        raise ValueError("Expected exactly V1–V20 in the specification")
    return vectors


def relay_vectors(source=RELAY_VECTORS):
    return {
        int(number): decode_hex(number, multiline or single_line, "relay Tsb3Vectors.scala")
        for number, multiline, single_line in re.findall(
            r'val V(\d+): String = (?:"""(.*?)"""|"([^"\n]*)")', source.read_text(encoding="utf-8"), re.S
        )
    }


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    if len(argv) > 1:
        raise SystemExit("usage: check_protocol_vectors.py [PROTOCOL.md]")
    spec = Path(argv[0]) if argv else SPEC

    expected = spec_vectors(spec)
    with tempfile.TemporaryDirectory() as directory:
        generated = Path(directory) / "vectors.h"
        subprocess.run(
            [sys.executable, str(FIRMWARE / "test/test_proto_codec/gen_vectors.py"),
             str(spec), str(generated)], check=True
        )
        if generated.read_bytes() != (FIRMWARE / "test/test_proto_codec/vectors.h").read_bytes():
            raise SystemExit("Firmware vectors.h has drifted from PROTOCOL.md; regenerate it.")

    actual = relay_vectors()
    if len(expected) != 20 or actual.keys() != expected.keys():
        raise SystemExit("Expected exactly V1–V20 in the spec and relay vectors.")
    for number, frame in expected.items():
        if actual[number] != frame:
            raise SystemExit(f"Relay V{number} has drifted from PROTOCOL.md.")
    print("All 20 TSB/3 vectors match the specification, relay and firmware.")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        raise SystemExit(f"Protocol vector check failed: {error}") from None
