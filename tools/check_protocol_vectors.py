#!/usr/bin/env python3
"""Check both implementations against the normative TSB/3 vectors.

Usage: check_protocol_vectors.py
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


def fenced_blocks(section):
    """Yield (number, hex) for the first fenced block after each `### V<n>.` heading."""
    number, block = None, None
    for line in section.splitlines():
        if block is not None:
            if line == "```":
                yield number, "\n".join(block)
                number, block = None, None
            else:
                block.append(line)
        elif line.startswith("### V"):
            digits, dot, _ = line[len("### V"):].partition(".")
            number = digits if dot and digits.isdigit() else None
        elif line == "```" and number is not None:
            block = []


def spec_vectors(spec=SPEC):
    source = spec.read_text(encoding="utf-8")
    start, end = "<!-- tsb3-golden-vectors:start -->", "<!-- tsb3-golden-vectors:end -->"
    if source.count(start) != 1 or source.count(end) != 1 or source.index(start) >= source.index(end):
        raise ValueError("PROTOCOL.md must contain one ordered pair of tsb3-golden-vectors anchors")
    section = source.split(start, 1)[1].split(end, 1)[0]
    blocks = list(fenced_blocks(section))
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


def main(spec=SPEC):
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


def run(spec=SPEC):
    try:
        main(spec)
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        raise SystemExit(f"Protocol vector check failed: {error}") from None


if __name__ == "__main__":
    run()
