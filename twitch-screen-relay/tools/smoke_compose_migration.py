#!/usr/bin/env python3
"""Verify real Compose shell/env-file obsolete-auth rejection using synthetic values.

Build the service first with smoke_compose.py. Uses no private env, never prints
expanded config or values, and retains the isolated test-owned state volume.
"""
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import tempfile

KEYS = ("RELAY_HTTP_AUTH_API_TOKEN", "RELAY_HTTP_AUTH_BASIC_USERNAME",
        "RELAY_HTTP_AUTH_BASIC_PASSWORD_HASH")


def main():
    root = Path(__file__).resolve().parents[1]
    project = "relay-migration-" + secrets.token_hex(4)
    env = {key: value for key, value in os.environ.items() if not key.startswith("RELAY_")}
    with tempfile.TemporaryDirectory(prefix="compose-migration-") as temporary:
        env_file = Path(temporary) / "synthetic.env"
        base = ["docker", "compose", "--env-file", str(env_file), "-p", project]
        try:
            env_file.write_text("")
            subprocess.run(base + ["build", "relay"], cwd=root, env=env, check=True,
                           capture_output=True, text=True, timeout=600)
            for origin in ("shell", "env-file"):
                for key in KEYS:
                    for value in ("synthetic-migration-value", "   "):
                        environment = dict(env)
                        env_file.write_text(key + "='" + value + "'\n" if origin == "env-file" else "")
                        env_file.chmod(0o600)
                        if origin == "shell":
                            environment[key] = value
                        rendered = subprocess.run(base + ["config", "--format", "json"], cwd=root,
                                                  env=environment, capture_output=True, text=True, check=True)
                        configuration = json.loads(rendered.stdout)
                        assert configuration["services"]["relay"]["environment"][key] == value
                        result = subprocess.run(base + ["run", "--rm", "--no-deps", "relay"], cwd=root,
                                                env=environment, capture_output=True, text=True, timeout=45)
                        assert result.returncode != 0, (origin, key, "startup did not reject")
                        assert "Obsolete management authentication environment keys" in result.stderr
                        assert key in result.stderr
                        assert "synthetic-migration-value" not in result.stderr + result.stdout
                        print(json.dumps({"source": origin, "key": key, "whitespace": value.isspace(),
                                          "runtimeRejected": True}))
                # The supported wrapper also guards arbitrary prefix keys that YAML cannot enumerate.
                key = "RELAY_HTTP_AUTH_FUTURE_OBSOLETE_KEY"
                env_file.write_text(key + "=synthetic-migration-value\n" if origin == "env-file" else "")
                environment = dict(env)
                if origin == "shell":
                    environment[key] = "synthetic-migration-value"
                result = subprocess.run([sys.executable, "tools/compose.py", "--env-file", str(env_file),
                                         "-p", project, "up", "-d", "relay"], cwd=root, env=environment,
                                        capture_output=True, text=True, timeout=30)
                assert result.returncode == 2 and key in result.stderr
                assert "synthetic-migration-value" not in result.stdout + result.stderr
                print(json.dumps({"source": origin, "key": key, "preflightRejected": True}))
            env_file.write_text("\n".join(key + "=\n" for key in KEYS))
            clean = subprocess.run([sys.executable, "tools/compose.py", "--env-file", str(env_file),
                                    "-p", project, "config", "--services"], cwd=root, env=env,
                                   capture_output=True, text=True, check=True)
            assert clean.stdout.strip() == "relay"
            print(json.dumps({"emptyObsoleteKeys": "allowed"}))
        finally:
            env_file.write_text("")
            subprocess.run(base + ["down", "--timeout", "5"], cwd=root, env=env, check=True,
                           capture_output=True, text=True)


if __name__ == "__main__":
    main()
