#!/usr/bin/env python3
"""Run the shipped Caddy/relay overlay with test-owned ports, CA and resources.

No private env files, host CA installation, provider actions or volume deletion.
"""
import argparse
import http.client
import ipaddress
import json
import os
from pathlib import Path
import secrets
import socket
import ssl
import subprocess
import sys
import tempfile
import time


ROOT = Path(__file__).resolve().parents[1]
HOST = "desk-smoke.home.arpa"
ORIGIN = "https://" + HOST


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def unused_subnet():
    ids = subprocess.run(["docker", "network", "ls", "-q"], check=True, capture_output=True, text=True).stdout.split()
    networks = json.loads(subprocess.run(["docker", "network", "inspect", *ids], check=True,
                                        capture_output=True, text=True).stdout) if ids else []
    occupied = [ipaddress.ip_network(item["Subnet"]) for network in networks
                for item in (network.get("IPAM", {}).get("Config") or []) if item.get("Subnet")]
    for third in range(80, 240):
        candidate = ipaddress.ip_network(f"172.30.{third}.0/29")
        if not any(candidate.overlaps(other) for other in occupied):
            return candidate
    raise RuntimeError("No test subnet available; choose an unused private Docker subnet")


def exchange(port, path, method="GET", body=None, headers=(), context=None, authority=None):
    connection = socket.create_connection(("127.0.0.1", port), timeout=5)
    try:
        if context is not None:
            connection = context.wrap_socket(connection, server_hostname=HOST)
        payload = b"" if body is None else json.dumps(body).encode()
        lines = [("Host", authority or (HOST if context else f"127.0.0.1:{port}")),
                 ("Connection", "close"), ("Content-Length", str(len(payload)))]
        if body is not None:
            lines.append(("Content-Type", "application/json"))
        lines.extend(headers)
        head = f"{method} {path} HTTP/1.1\r\n" + "".join(f"{name}: {value}\r\n" for name, value in lines)
        connection.sendall(head.encode() + b"\r\n" + payload)
        response = http.client.HTTPResponse(connection)
        response.begin()
        data = response.read(1024 * 1024)
        return response.status, data
    finally:
        connection.close()


def publication_identity(items):
    """Compare retained identity/content, not the advancing remaining-expiry clock."""
    return [{key: value for key, value in item.items() if key != "remainingExpiryMs"}
            for item in items]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--no-build", action="store_true")
    args = parser.parse_args()
    os.umask(0o077)
    project = "relay-https-smoke-" + secrets.token_hex(4)
    http_port, device_port, tls_port = free_port(), free_port(), free_port()
    subnet = unused_subnet()
    proxy_ip, relay_ip = str(subnet[2]), str(subnet[3])
    env = {key: value for key, value in os.environ.items()
           if not key.startswith(("RELAY_", "CADDY_"))}
    env.update(RELAY_BIND_IP="127.0.0.1", CADDY_BIND_IP="127.0.0.1", CADDY_HTTPS_PORT=str(tls_port),
               RELAY_PUBLISHED_HTTP_PORT=str(http_port), RELAY_PUBLISHED_DEVICE_PORT=str(device_port),
               RELAY_PROXY_SUBNET=str(subnet), RELAY_PROXY_IP=proxy_ip, RELAY_UPSTREAM_IP=relay_ip,
               RELAY_PRIVATE_HOST=HOST, RELAY_HTTP_PUBLIC_ORIGIN=ORIGIN,
               RELAY_HTTP_ALLOWED_HOSTS=f"localhost:8080,127.0.0.1:8080,127.0.0.1:{http_port},{HOST}",
               RELAY_HTTP_ALLOWED_ORIGINS=f"http://localhost:8080,http://127.0.0.1:8080,http://127.0.0.1:{http_port},{ORIGIN}",
               RELAY_TWITCH_REDIRECT_URL=ORIGIN + "/api/v1/twitch/callback")
    base = [sys.executable, "tools/compose.py", "--env-file", "/dev/null", "-p", project,
            "-f", "compose.yaml", "-f", "deploy/private-https/compose.yaml"]

    def run(*command, capture=False):
        return subprocess.run(base + list(command), cwd=ROOT, env=env, check=True,
                              capture_output=capture, text=True)

    try:
        run("up", "-d", *([] if args.no_build else ["--build"]), "relay", "private-https")
        deadline = time.monotonic() + 90
        while True:
            try:
                status, data = exchange(http_port, "/api/v1/health")
                assert status == 200 and json.loads(data) == {"status": "Up"}
                break
            except (OSError, AssertionError):
                if time.monotonic() >= deadline:
                    raise RuntimeError("Current Compose relay did not become ready") from None
                time.sleep(0.2)
        container = run("ps", "-q", "private-https", capture=True).stdout.strip()
        with tempfile.TemporaryDirectory(prefix="relay-https-smoke-", dir=os.environ["TMPDIR"]) as directory:
            certificate = Path(directory) / "root.crt"
            while True:
                copied = subprocess.run(["docker", "cp", container + ":/data/caddy/pki/authorities/local/root.crt",
                                         str(certificate)], capture_output=True)
                if copied.returncode == 0:
                    break
                if time.monotonic() >= deadline:
                    raise RuntimeError("Test Caddy did not issue its private CA")
                time.sleep(0.2)
            context = ssl.create_default_context(cafile=str(certificate))
            while True:
                try:
                    status, data = exchange(tls_port, "/api/v1/health", headers=[("Origin", ORIGIN)], context=context)
                    assert status == 200 and json.loads(data) == {"status": "Up"}
                    break
                except (OSError, AssertionError):
                    if time.monotonic() >= deadline:
                        raise RuntimeError("Certificate-verified Caddy-to-relay HTTPS did not become ready") from None
                    time.sleep(0.2)
            assert exchange(tls_port, "/docs/docs.yaml", context=context)[0] == 200
            assert exchange(tls_port, "/api/v1/devices", context=context)[0] == 200
            title = "Synthetic HTTPS " + secrets.token_hex(4)
            card = {"type": "info", "title": title, "body": ""}
            assert exchange(tls_port, "/api/v1/notifications", "POST", card,
                            [("Origin", ORIGIN), ("Sec-Fetch-Site", "same-origin")], context)[0] == 200
            status, data = exchange(tls_port, "/api/v1/notifications", context=context)
            assert status == 200 and any(item["title"] == title for item in json.loads(data)["notifications"])
            before = json.loads(data)["notifications"]
            assert exchange(tls_port, "/api/v1/notifications", "POST", card,
                            [("Origin", "https://evil.example")], context)[0] == 403
            wrong_host = exchange(tls_port, "/api/v1/devices", context=context, authority="evil.example")[0]
            assert wrong_host == 421, f"Caddy SNI/Host mismatch status={wrong_host}"
            duplicate_host = exchange(tls_port, "/api/v1/devices", headers=[("Host", "evil.example")], context=context)[0]
            assert duplicate_host in (400, 403, 421), f"Duplicate Host status={duplicate_host}"
            # Client-supplied proxy chains are stripped by Caddy, not trusted by the relay.
            assert exchange(tls_port, "/api/v1/health", headers=[("Forwarded", "proto=http"),
                            ("X-Forwarded-For", "1.2.3.4,5.6.7.8"), ("X-Forwarded-Host", "evil.example")], context=context)[0] == 200
            # A direct host connection arrives as the bridge gateway, which is deliberately NOT trusted.
            forged = [("X-Forwarded-Proto", "https"), ("X-Forwarded-For", "192.168.1.23")]
            assert exchange(http_port, "/api/v1/notifications", "POST", card, forged, authority=HOST)[0] == 403
            assert exchange(http_port, "/docs/docs.yaml", headers=forged + [("Forwarded", "proto=https")], authority=HOST)[0] == 403
            status, after = exchange(tls_port, "/api/v1/notifications", context=context)
            assert status == 200
            assert publication_identity(json.loads(after)["notifications"]) == publication_identity(before)
            # Disabled ingestion has no callback route. Focused provider-route tests prove state/signature validation separately.
            assert exchange(tls_port, "/api/v1/twitch/callback?state=synthetic", context=context)[0] == 404
        ids = run("ps", "-q", capture=True).stdout.split()
        metadata = json.loads(subprocess.run(["docker", "inspect", *ids], check=True, capture_output=True, text=True).stdout)
        peers = {item["Config"]["Labels"]["com.docker.compose.service"]:
                 list(item["NetworkSettings"]["Networks"].values())[0]["IPAddress"] for item in metadata}
        assert peers == {"relay": relay_ip, "private-https": proxy_ip}, peers
        print(json.dumps({"httpsHealthDocsManagement": "passed", "certificateVerification": True,
                          "notificationReadback": True, "hostOriginForwardingRejection": True,
                          "exactProxyPeer": proxy_ip, "mobileConsent": "not tested", "providerCallback": "disabled/404"}))
    finally:
        run("down", "--timeout", "5")
        remaining = subprocess.run(["docker", "ps", "-aq", "--filter", "label=com.docker.compose.project=" + project],
                                   check=True, capture_output=True, text=True).stdout.strip()
        networks = subprocess.run(["docker", "network", "ls", "-q", "--filter", "label=com.docker.compose.project=" + project],
                                  check=True, capture_output=True, text=True).stdout.strip()
        assert not remaining and not networks, "Test-owned container/network cleanup incomplete"
        print("Test-owned containers/network removed; named token and CA volumes preserved.")


if __name__ == "__main__":
    main()
