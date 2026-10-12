#!/usr/bin/env python3
"""Exercise installed admin snapshots and SSE through the shipped private Caddy overlay.

Uses isolated loopback ports, disposable configuration and a test-owned CA. Does not
install trust, access providers/hardware or remove token/CA volumes.
"""
import argparse
import hashlib
import http.client
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
from typing import Any

from smoke_private_https import HOST, ORIGIN, exchange, free_port, unused_subnet

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools'))
from check_desk_contract import validate


def read_event(response, schema) -> dict[str, Any]:
    fields = {}
    while True:
        line = response.readline(524289)
        if not line or len(line) > 524288:
            raise ValueError('admin feed ended or exceeded encoded limit')
        text = line.decode('utf-8').rstrip('\r\n')
        if text.startswith(':'):
            return {'comment': text}
        if not text:
            if 'data' in fields:
                value = json.loads(fields.pop('data'))
                validate(value, schema['$defs']['AdminSnapshot'], schema)
                if fields.get('id') != f"{value['relayEpoch']}:{value['revision']}":
                    raise ValueError('SSE identity differs from complete snapshot')
                if fields.get('event') not in ('snapshot', 'reset'):
                    raise ValueError('unknown admin event')
                return {**fields, 'snapshot': value}
            continue
        name, value = text.split(':', 1)
        fields[name] = value.lstrip(' ')


def run(args):
    os.umask(0o077)
    if (ROOT / '.agent-workflow/STOP').exists():
        raise RuntimeError('STOP exists')
    evidence = args.evidence.resolve()
    evidence.mkdir(parents=True, exist_ok=True, mode=0o700)
    project = 'relay-admin-https-' + secrets.token_hex(4)
    http_port, device_port, tls_port = free_port(), free_port(), free_port()
    if len({http_port, device_port, tls_port}) != 3:
        raise RuntimeError('test port reservation collision')
    subnet = unused_subnet()
    proxy_ip, relay_ip = str(subnet[2]), str(subnet[3])
    env = {key: value for key, value in os.environ.items() if not key.startswith(('RELAY_', 'CADDY_', 'FIRMWARE_PEER_'))}
    base = f'http://127.0.0.1:{http_port}'
    env.update(RELAY_BIND_IP='127.0.0.1', CADDY_BIND_IP='127.0.0.1', CADDY_HTTPS_PORT=str(tls_port),
               RELAY_PUBLISHED_HTTP_PORT=str(http_port), RELAY_PUBLISHED_DEVICE_PORT=str(device_port),
               RELAY_PROXY_SUBNET=str(subnet), RELAY_PROXY_IP=proxy_ip, RELAY_UPSTREAM_IP=relay_ip,
               RELAY_PRIVATE_HOST=HOST, RELAY_HTTP_PUBLIC_ORIGIN=ORIGIN,
               RELAY_HTTP_ALLOWED_HOSTS=f'localhost:8080,127.0.0.1:8080,127.0.0.1:{http_port},{HOST}',
               RELAY_HTTP_ALLOWED_ORIGINS=f'http://localhost:8080,http://127.0.0.1:8080,{base},{ORIGIN}',
               RELAY_TWITCH_REDIRECT_URL=ORIGIN + '/api/v1/twitch/callback')
    command = [sys.executable, 'tools/compose.py', '--env-file', '/dev/null', '-p', project,
               '-f', 'compose.yaml', '-f', 'deploy/private-https/compose.yaml']
    schema = json.loads((ROOT / 'protocol/desk-v1-http.schema.json').read_text())
    result = {'project': project, 'productAccepted': False, 'testedLayer': 'actual documented Compose and certificate-verified private Caddy admin SSE; not physical mobile consent', 'checks': []}
    feeds = []

    def compose(*parts, capture=False):
        return subprocess.run(command + list(parts), cwd=ROOT / 'twitch-screen-relay', env=env,
                              capture_output=capture, text=True, check=True, timeout=300)

    try:
        with (evidence / 'compose-up.log').open('w') as log:
            up = subprocess.run(command + ['up', '-d', '--build', 'relay', 'private-https'], cwd=ROOT / 'twitch-screen-relay', env=env,
                                stdout=log, stderr=subprocess.STDOUT, timeout=300)
        assert up.returncode == 0
        until = time.monotonic() + 90
        while True:
            try:
                assert exchange(http_port, '/api/v1/health')[0] == 200
                break
            except (OSError, AssertionError):
                assert time.monotonic() < until, 'owned Compose did not start'
                time.sleep(.1)
        ids = compose('ps', '-q', capture=True).stdout.split()
        metadata = json.loads(subprocess.run(['docker', 'inspect', *ids], check=True, capture_output=True, text=True).stdout)
        services = {item['Config']['Labels']['com.docker.compose.service']: item for item in metadata}
        peers = {name: list(item['NetworkSettings']['Networks'].values())[0]['IPAddress'] for name, item in services.items()}
        assert peers == {'relay': relay_ip, 'private-https': proxy_ip}
        result.update(imageId=services['relay']['Image'], proxyImageId=services['private-https']['Image'], exactProxyPeer=proxy_ip)
        with tempfile.TemporaryDirectory(prefix='relay-admin-https-', dir=os.environ['TMPDIR']) as temporary:
            temporary = Path(temporary)
            assembly = temporary / 'relay.jar'
            subprocess.run(['docker', 'cp', services['relay']['Id'] + ':/home/relay/relay.jar', str(assembly)], check=True)
            result['assemblySha256'] = hashlib.sha256(assembly.read_bytes()).hexdigest()
            certificate = temporary / 'root.crt'
            while True:
                copied = subprocess.run(['docker', 'cp', services['private-https']['Id'] + ':/data/caddy/pki/authorities/local/root.crt', str(certificate)], capture_output=True)
                if copied.returncode == 0:
                    break
                assert time.monotonic() < until, 'Caddy CA unavailable'
                time.sleep(.1)
            context = ssl.create_default_context(cafile=str(certificate))

            def api(path, method='GET', body=None):
                status, raw = exchange(tls_port, '/api/v1/' + path, method, body, [('Origin', ORIGIN)], context)
                value = json.loads(raw)
                assert status == 200, (path, status, value)
                return value

            def feed(headers=()):
                sock = context.wrap_socket(socket.create_connection(('127.0.0.1', tls_port), timeout=20), server_hostname=HOST)
                lines = [('Host', HOST), ('Origin', ORIGIN), *headers]
                sock.sendall(('GET /api/v1/admin/events HTTP/1.1\r\n' + ''.join(f'{name}: {value}\r\n' for name, value in lines) + '\r\n').encode())
                response = http.client.HTTPResponse(sock)
                response.begin()
                feeds.append((sock, response))
                return response

            initial = api('admin/snapshot')
            validate(initial, schema['$defs']['AdminSnapshot'], schema)
            for path, mime in (('/', 'text/html'), ('/admin/assets/v1/panel.css', 'text/css'), ('/admin/assets/v1/panel.js', 'text/javascript')):
                sock = context.wrap_socket(socket.create_connection(('127.0.0.1', tls_port), timeout=5), server_hostname=HOST)
                try:
                    sock.sendall(f'GET {path} HTTP/1.1\r\nHost: {HOST}\r\nOrigin: {ORIGIN}\r\nConnection: close\r\n\r\n'.encode())
                    response = http.client.HTTPResponse(sock)
                    response.begin()
                    assert response.status == 200 and response.getheader('Content-Type', '').startswith(mime)
                    assert response.getheader('X-Content-Type-Options') == 'nosniff'
                    assert response.read(262145)
                finally:
                    sock.close()
            result['checks'].append('certificate-verified exact panel/assets MIME and canonical snapshot')
            response = feed()
            assert response.status == 200 and response.getheader('Cache-Control') == 'no-store'
            assert response.getheader('X-Accel-Buffering') == 'no'
            events = [read_event(response, schema)]
            assert events[0]['event'] == 'snapshot'
            second = feed()
            assert second.status == 200 and read_event(second, schema)['event'] == 'snapshot'
            forged = feed([('X-Forwarded-For', '192.168.42.42')])
            assert forged.status == 429, 'client spoof bypassed trusted-proxy admission'
            feeds[-1][1].close()
            feeds[-1][0].close()
            feeds[-2][1].close()
            feeds[-2][0].close()
            result['checks'].append('trusted-proxy client admission and stripped forged client identity')
            last = None
            for index in range(12):
                last = api('cpu', 'PUT', {'sourceId': 'https-admin', 'host': 'synthetic', 'cpuPercent': index + .25, 'memoryPercent': 0, 'freshnessMs': 1000})
            assert last is not None
            until = time.monotonic() + 10
            while True:
                item = read_event(response, schema)
                events.append(item)
                cpu = item.get('snapshot', {}).get('sources', {}).get('cpu', {})
                if cpu.get('revision') == last['revision']:
                    assert cpu['data']['cpuPercent'] == 11.25 and cpu['data']['memoryPercent'] == 0
                    break
                assert time.monotonic() < until
            assert any(item.get('event') == 'reset' for item in events), 'coalesced revision gap lacked reset'
            while True:
                item = read_event(response, schema)
                events.append(item)
                cpu = item.get('snapshot', {}).get('sources', {}).get('cpu', {})
                if cpu.get('revision') == last['revision'] and cpu.get('status') == 'stale':
                    assert cpu['remainingFreshnessMs'] == 0 and cpu['data'] == last['data']
                    break
                assert time.monotonic() < until
            result['checks'].append('actual burst latest-state coalescing reset and original expiry through TLS without buffering')
            switched = api('twitch/monitoring', 'PATCH', {'mode': 'disabled', 'channel': 'synthetic'})
            until = time.monotonic() + 10
            while True:
                item = read_event(response, schema)
                events.append(item)
                value = item.get('snapshot')
                if value and value['monitoring']['sourceGeneration'] == switched['sourceGeneration']:
                    assert value['monitoring']['desired']['channel'] == 'synthetic'
                    assert value['charts']['sourceGeneration'] == switched['sourceGeneration']
                    assert all(point is None for point in value['charts']['viewers']['values'])
                    assert value['sources']['cpu']['revision'] == last['revision']
                    break
                assert time.monotonic() < until
            started = time.monotonic()
            comments = 0
            while time.monotonic() - started <= 65:
                item = read_event(response, schema)
                events.append(item)
                comments += 'comment' in item
            result.update(idleFeedSeconds=time.monotonic() - started, heartbeatComments=comments)
            assert result['idleFeedSeconds'] > 60 and comments >= 4
            result['checks'].append('source-generation reset and >60-second inbound-silent certificate-verified proxy feed')
            for sock, response in feeds:
                response.close()
                sock.close()
            feeds.clear()
            if args.native:
                native = args.native.resolve()
                result['nativeSha256'] = hashlib.sha256(native.read_bytes()).hexdigest()
                with (evidence / 'pairing.log').open('w') as log:
                    pairing = subprocess.run([sys.executable, str(ROOT / 'twitch-screen-firmware/tools/pair_runtime.py'), '--binary', str(native),
                                              '--http-port', str(http_port), '--device-port', str(device_port), '--evidence', str(evidence / 'pairing'),
                                              '--queue-pressure', '--require-desk', '--desk-reconnect'], cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT, timeout=180)
                result['pairingExitCode'] = pairing.returncode
                assert pairing.returncode == 0
                result['checks'].append('exact rebuilt documented Compose mandatory actual native pairing')
            if args.browser_executable:
                from check_admin_browser import run as browser_run
                result['browser'] = browser_run(base, evidence / 'browser', args.browser_executable.resolve(),
                                              native=args.native.resolve() if args.native else None, device_port=device_port)
                result['checks'].append('actual installed Compose panel Chromium; direct loopback, not physical HTTPS mobile')
            final = api('admin/snapshot')
            validate(final, schema['$defs']['AdminSnapshot'], schema)
            (evidence / 'snapshot.json').write_text(json.dumps(final, indent=2))
            (evidence / 'events.json').write_text(json.dumps(events, indent=2))
            result['passed'] = True
    finally:
        for sock, response in feeds:
            response.close()
            sock.close()
        compose('down', '--timeout', '5')
        remaining = subprocess.run(['docker', 'ps', '-aq', '--filter', f'label=com.docker.compose.project={project}'], check=True, capture_output=True, text=True).stdout.strip()
        networks = subprocess.run(['docker', 'network', 'ls', '-q', '--filter', f'label=com.docker.compose.project={project}'], check=True, capture_output=True, text=True).stdout.strip()
        assert not remaining and not networks
        for port in (http_port, device_port, tls_port):
            with socket.socket() as probe:
                probe.settimeout(1)
                assert probe.connect_ex(('127.0.0.1', port)) != 0
        volumes = [project + '_' + name for name in ('relay-data', 'private-caddy-data', 'private-caddy-config')]
        for name in volumes:
            assert subprocess.run(['docker', 'volume', 'inspect', name, '--format', '{{.Name}}'], check=True, capture_output=True, text=True).stdout.strip() == name
        result['cleanup'] = {'exactContainersAndNetworkAbsent': True, 'listenersClosed': True, 'ownedVolumesRetained': volumes}
        (evidence / 'result.json').write_text(json.dumps(result, indent=2))
    print(json.dumps(result, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--native', type=Path)
    parser.add_argument('--browser-executable', type=Path)
    run(parser.parse_args())


if __name__ == '__main__':
    main()
