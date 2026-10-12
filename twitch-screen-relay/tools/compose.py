#!/usr/bin/env python3
"""Guard the Compose interpolation environment before credential-free deployment.

Expanded environment and configuration are captured, never printed. Docker Compose
owns env-file parsing and precedence; this wrapper does not parse secrets itself.
"""
import argparse
import json
from pathlib import Path
import subprocess
import sys

PREFIX = "RELAY_HTTP_AUTH_"


def obsolete_keys(environment, configuration):
    keys = {key for line in environment.splitlines()
            for key, separator, value in [line.partition("=")]
            if separator and key.startswith(PREFIX) and value}
    for service in configuration.get("services", {}).values():
        values = service.get("environment", {})
        keys.update(key for key, value in values.items()
                    if key.startswith(PREFIX) and value is not None and str(value) != "")
    return sorted(keys)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--env-file", action="append", default=[])
    parser.add_argument("-p", "--project-name")
    parser.add_argument("-f", "--file", action="append", default=[])
    parser.add_argument("--project-directory")
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args(argv)
    if not args.command:
        parser.error("a Compose command is required")
    base = ["docker", "compose"]
    for path in args.env_file:
        base.extend(["--env-file", path])
    for path in args.file:
        base.extend(["--file", path])
    if args.project_name:
        base.extend(["--project-name", args.project_name])
    if args.project_directory:
        base.extend(["--project-directory", args.project_directory])
    root = Path(__file__).resolve().parents[1]
    try:
        environment = subprocess.run(base + ["config", "--environment"], cwd=root,
                                     capture_output=True, text=True, check=True)
        configuration = subprocess.run(base + ["config", "--format", "json"], cwd=root,
                                       capture_output=True, text=True, check=True)
        keys = obsolete_keys(environment.stdout, json.loads(configuration.stdout))
    except (OSError, subprocess.CalledProcessError, ValueError):
        print("Compose migration preflight failed; verify configuration and Docker availability. Expanded values were withheld.",
              file=sys.stderr)
        return 2
    if keys:
        print("Obsolete management authentication environment keys: " + ", ".join(keys)
              + "; remove them and restrict network access", file=sys.stderr)
        return 2
    return subprocess.run(base + args.command, cwd=root, check=False).returncode


if __name__ == "__main__":
    sys.exit(main())
