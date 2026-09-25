#!/usr/bin/env python3
"""Check both implementations against the normative TSB/3 vectors."""

from pathlib import Path
import re
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
FIRMWARE = ROOT / "twitch-screen-firmware"
SPEC = FIRMWARE / "docs/PROTOCOL.md"


def main():
    with tempfile.TemporaryDirectory() as directory:
        generated = Path(directory) / "vectors.h"
        subprocess.run(
            [sys.executable, str(FIRMWARE / "test/test_proto_codec/gen_vectors.py"),
             str(SPEC), str(generated)], check=True
        )
        if generated.read_bytes() != (FIRMWARE / "test/test_proto_codec/vectors.h").read_bytes():
            raise SystemExit("Firmware vectors.h has drifted from PROTOCOL.md; regenerate it.")

    section = SPEC.read_text().split("## 18. Golden test vectors", 1)[1].split("## 19.", 1)[0]
    expected = {
        int(number): bytes.fromhex(block)
        for number, block in re.findall(
            r"^### V(\d+)\..*?\n```\n(.*?)```", section, re.M | re.S
        )
    }
    source = (ROOT / "twitch-screen-relay/test/src/twitchscreen/relay/protocol/Tsb3Vectors.scala").read_text()
    actual = {
        int(number): bytes.fromhex(multiline or single_line)
        for number, multiline, single_line in re.findall(
            r'val V(\d+): String = (?:"""(.*?)"""|"([^"\n]*)")', source, re.S
        )
    }
    if len(expected) != 20 or actual.keys() != expected.keys():
        raise SystemExit("Expected exactly V1–V20 in the spec and relay vectors.")
    for number, frame in expected.items():
        if actual[number] != frame:
            raise SystemExit(f"Relay V{number} has drifted from PROTOCOL.md.")
    print("All 20 TSB/3 vectors match the specification, relay and firmware.")


if __name__ == "__main__":
    main()
