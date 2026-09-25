#!/usr/bin/env python3
"""Build-independent container smoke: public HTTP, protected HTTP and TSB/3."""

import json
import os
import secrets
import socket
import struct
import subprocess
import sys
import time
import urllib.error
import urllib.request
from check_protocol_vectors import spec_vectors


def docker(*args, **kwargs):
    return subprocess.check_output(["docker", *args], text=True, **kwargs).strip()


def read_frame(connection):
    def read_exact(size):
        data = bytearray()
        while len(data) < size:
            chunk = connection.recv(size - len(data))
            if not chunk:
                raise RuntimeError("Device connection closed before the expected frame")
            data.extend(chunk)
        return bytes(data)

    header = read_exact(8)
    assert header[:3] == bytes.fromhex("a7 53 03"), header.hex()
    assert header[7] == (0xFF ^ (sum((i + 1) * b for i, b in enumerate(header[:7])) & 0xFF))
    return header[3], read_exact(struct.unpack_from("<H", header, 4)[0])


def main():
    image = sys.argv[1] if len(sys.argv) == 2 else "twitch-screen-relay:review"
    token = secrets.token_hex(32)
    container = docker(
        "run", "-d", "--memory=512m", "--memory-swap=512m",
        "-p", "127.0.0.1::8080", "-p", "127.0.0.1::8099",
        "-e", "RELAY_HTTP_AUTH_API_TOKEN", "-e", "RELAY_TWITCH_MODE=simulated",
        image, env={**os.environ, "RELAY_HTTP_AUTH_API_TOKEN": token}
    )
    try:
        ports = json.loads(docker("inspect", "--format", "{{json .NetworkSettings.Ports}}", container))
        base = "http://127.0.0.1:" + ports["8080/tcp"][0]["HostPort"]

        def request(path, credential=None, payload=None):
            headers = {} if credential is None else {"Authorization": "Bearer " + credential}
            data = None if payload is None else json.dumps(payload).encode()
            if data is not None:
                headers["Content-Type"] = "application/json"
            try:
                with urllib.request.urlopen(urllib.request.Request(base + path, headers=headers, data=data), timeout=3) as response:
                    return response.status, response.read()
            except urllib.error.HTTPError as error:
                return error.code, error.read()

        deadline = time.monotonic() + 90
        while True:
            try:
                if request("/api/v1/health")[0] == 200:
                    break
            except (OSError, urllib.error.URLError):
                pass
            if time.monotonic() >= deadline:
                raise RuntimeError("Container did not become healthy within 90 seconds")
            time.sleep(0.5)

        for path in ("/api/v1/health", "/api/v1/stats", "/docs/"):
            assert request(path)[0] == 200, path
        for path in ("/api/v1/config", "/api/v1/devices", "/api/v1/status"):
            for credential in (None, "incorrect"):
                status, body = request(path, credential)
                assert status == 401, (path, status)
                assert isinstance(json.loads(body), dict), path
            status, body = request(path, token)
            assert status == 200, (path, status)
            assert token.encode() not in body, "Management token leaked in response"

        hello = spec_vectors()[1]
        with socket.create_connection(("127.0.0.1", int(ports["8099/tcp"][0]["HostPort"])), timeout=5) as connection:
            connection.sendall(hello)
            kind, payload = read_frame(connection)
            assert kind == 0x20 and len(payload) == 24, "Expected TSB/3 WELCOME"
            kind, payload = read_frame(connection)
            assert kind == 0x22 and len(payload) == 32, "Expected greeting STATS"
            status, body = request("/api/v1/notifications", token,
                                   {"type": "info", "title": "Smoke", "body": "Container delivery"})
            assert status == 200, (status, body)
            sequence = json.loads(body)["seq"]
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline:
                kind, payload = read_frame(connection)
                if kind == 0x21 and struct.unpack_from("<I", payload)[0] == sequence:
                    assert len(payload) == 168 and b"Container delivery" in payload, "Wrong EVENT payload"
                    break
            else:
                raise AssertionError("Expected injected EVENT within five seconds")
            docker("kill", "--signal=TERM", container)
            deadline = time.monotonic() + 10
            while kind != 0x25 and time.monotonic() < deadline:
                kind, payload = read_frame(connection)
            assert kind == 0x25 and struct.unpack_from("<H", payload)[0] == 8, "Expected SERVER_SHUTDOWN BYE"
            assert connection.recv(1) == b"", "BYE must be the final frame"
        subprocess.run(["docker", "wait", container], check=True, timeout=15, stdout=subprocess.DEVNULL)
        print("Container smoke passed: public/protected HTTP, redaction, WELCOME, STATS, EVENT and shutdown BYE (512 MiB limit).")
    except Exception:
        # The only credential this isolated simulated container knows is generated above.
        print(docker("logs", container, stderr=subprocess.STDOUT).replace(token, "[test-token]"), file=sys.stderr)
        raise
    finally:
        docker("rm", "-fv", container)


if __name__ == "__main__":
    main()
