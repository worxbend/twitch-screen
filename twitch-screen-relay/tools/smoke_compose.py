#!/usr/bin/env python3
"""Exercise the actual Compose service with isolated ports and test-owned state.

Never reads a private env file, prints expanded config, or removes token volumes.
Management is credential-free; test ports have explicit allowed authorities.
"""
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import sys
import time
import urllib.request


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def verify_publication(payload, accepted, history):
    """Verify the canonical acceptance and one exact retained identity, never pixels."""
    assert accepted["type"] == payload["type"]
    assert accepted["title"] == payload["title"] and accepted["body"] == payload["body"]
    assert accepted["ttlMs"] == payload["ttlMs"] and accepted["origin"] == "external"
    matches = [item for item in history["notifications"]
               if item["id"] == accepted["id"] and item["seq"] == accepted["seq"]
               and item["relayEpoch"] == accepted["relayEpoch"]]
    assert len(matches) == 1
    for key in ("type", "title", "body", "ttlMs", "origin"):
        assert matches[0][key] == accepted[key]
    return True


def main():
    root = Path(__file__).resolve().parents[1]
    project = "relay-wave1-" + secrets.token_hex(4)
    http_port, device_port = free_port(), free_port()
    env = {key: value for key, value in os.environ.items() if not key.startswith("RELAY_")}
    env.update(RELAY_BIND_IP="127.0.0.1", RELAY_PUBLISHED_HTTP_PORT=str(http_port),
               RELAY_PUBLISHED_DEVICE_PORT=str(device_port),
               RELAY_HTTP_ALLOWED_HOSTS=f"localhost:8080,127.0.0.1:8080,127.0.0.1:{http_port}",
               RELAY_HTTP_ALLOWED_ORIGINS=f"http://localhost:8080,http://127.0.0.1:8080,http://127.0.0.1:{http_port}")
    # Exercise checked-in startup defaults, not equivalent hardcoded test values.
    base = [sys.executable, "tools/compose.py", "--env-file", ".env.example", "-p", project]

    def run(*args, capture=False):
        return subprocess.run(base + list(args), cwd=root, env=env, check=True,
                              text=True, capture_output=capture)

    try:
        run("up", "--build", "-d", "relay")
        deadline = time.monotonic() + 60
        while True:
            try:
                with urllib.request.urlopen(f"http://127.0.0.1:{http_port}/api/v1/health", timeout=2) as response:
                    assert json.load(response) == {"status": "Up"}
                break
            except (OSError, AssertionError):
                if time.monotonic() >= deadline:
                    raise RuntimeError("Compose liveness did not become ready") from None
                time.sleep(0.2)
        container = run("ps", "-q", "relay", capture=True).stdout.strip()
        metadata = json.loads(subprocess.run(["docker", "inspect", container], check=True,
                                            capture_output=True, text=True).stdout)[0]
        values = dict(entry.split("=", 1) for entry in metadata["Config"]["Env"] if "=" in entry)
        assert values["RELAY_TWITCH_MODE"] == "disabled"
        assert values["RELAY_TWITCH_CHANNEL"] == ""
        assert values["RELAY_CHAT_NOTIFICATIONS"] == "hide"
        assert values["RELAY_TWITCH_REDIRECT_URL"] == "http://localhost:8080/api/v1/twitch/callback"
        assert all(binding["HostIp"] == "127.0.0.1"
                   for bindings in metadata["HostConfig"]["PortBindings"].values() for binding in bindings)
        request = urllib.request.Request(f"http://127.0.0.1:{http_port}/api/v1/devices")
        with urllib.request.urlopen(request, timeout=3) as response:
            assert response.status == 200
        payload = {"type": "info", "title": "Synthetic template quickstart", "body": "Generic notification", "ttlMs": 8000}
        request = urllib.request.Request(
            f"http://127.0.0.1:{http_port}/api/v1/notifications", json.dumps(payload).encode(),
            {"Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=3) as response:
            assert response.status == 200
            accepted = json.load(response)
        with urllib.request.urlopen(f"http://127.0.0.1:{http_port}/api/v1/notifications", timeout=3) as response:
            history = json.load(response)
        verify_publication(payload, accepted, history)
        result = {"composeStartup": "passed", "mode": "disabled", "channel": "",
                  "chat": "hide", "loopbackPublishing": True, "management": "credential-free",
                  "project": project, "containerId": container, "imageId": metadata["Image"],
                  "template": ".env.example", "publicationId": accepted["id"],
                  "publicationSeq": accepted["seq"], "publicationReadbackVerified": True,
                  "devicePresentationVerified": False}
    finally:
        run("down", "--timeout", "5")
        remaining = subprocess.run(
            ["docker", "ps", "-a", "-q", "--filter", f"label=com.docker.compose.project={project}"],
            check=True, capture_output=True, text=True).stdout.strip()
        assert not remaining, "Owned Compose container remains after cleanup"
        for port in (http_port, device_port):
            with socket.socket() as probe:
                probe.settimeout(1)
                assert probe.connect_ex(("127.0.0.1", port)) != 0, "Owned listener remains after cleanup"
        # Keep the test-owned volume: never use down -v or remove a token store.
        retained = subprocess.run(
            ["docker", "volume", "inspect", project + "_relay-data", "--format", "{{.Name}}"],
            check=True, capture_output=True, text=True).stdout.strip()
        assert retained == project + "_relay-data"
    result["cleanup"] = {"exactContainerAbsent": True, "ownedListenersClosed": True,
                         "testOwnedVolumeRetained": retained}
    print(json.dumps(result))


if __name__ == "__main__":
    main()
