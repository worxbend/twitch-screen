#!/usr/bin/env python3
"""Exercise all four baseline/current peer combinations over actual HTTP/TCP.

Baseline means the immutable mission commit, not a synthetic wire server. Its
original LinkSession/NotifyQueue are compiled with a small test-only socket
adapter. Current firmware uses real LVGL callbacks. Neither is ESP32 evidence.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import tempfile
import threading
import time
import urllib.request
from runtime_lifecycle import verify_callbacks, verify_report_phases
from test_production_views import observation_lines

BASELINE = 'b30b91f4a29bc70a50428ac1bf6894e3ba4b7ac2'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binary', type=Path, required=True)
    parser.add_argument('--new-assembly', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    args = parser.parse_args()
    assert args.binary.is_file() and args.new_assembly.is_file()
    os.umask(0o077)
    root = Path(__file__).resolve().parents[2]
    assert not (root / '.agent-workflow/STOP').exists()
    args.evidence.mkdir(parents=True, exist_ok=True)
    scratch = Path(tempfile.mkdtemp(prefix='firmware-four-peer-', dir=os.environ['TMPDIR']))
    relay = scratch / 'relay'; firmware = scratch / 'firmware'
    paths = subprocess.check_output(['git', 'ls-tree', '-r', '--name-only', BASELINE, '--',
                                     'twitch-screen-relay/src', 'twitch-screen-relay/resources',
                                     'twitch-screen-relay/build.mill', 'twitch-screen-relay/mill',
                                     'twitch-screen-relay/.scalafmt.conf'], cwd=root, text=True).splitlines()
    paths += ['twitch-screen-firmware/src/' + name for name in
              ('link_client.h', 'link_client.cpp', 'link_transport.h', 'notification.h',
               'notification_wire.h', 'notify_queue.h', 'proto_codec.h', 'proto_codec.cpp', 'stats.h')]
    manifest = []
    for name in paths:
        data = subprocess.check_output(['git', 'show', BASELINE + ':' + name], cwd=root)
        prefix, relative = name.split('/', 1)
        path = (relay if prefix == 'twitch-screen-relay' else firmware) / relative
        path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(data)
        manifest.append(dict(path=name, sha256=hashlib.sha256(data).hexdigest()))
    (relay / 'mill').chmod(0o700)
    (args.evidence / 'baseline-manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    legacy = scratch / 'legacy-program'
    with (args.evidence / 'baseline-firmware-build.log').open('w') as log:
        subprocess.run(['g++', '-std=c++11', '-Wall', '-Wextra', '-Werror', '-fsanitize=address,undefined',
                        '-fno-sanitize-recover=all', '-fno-pie', '-no-pie', '-I', str(firmware / 'src'),
                        str(Path(__file__).with_name('legacy_runtime_peer.cpp')), str(firmware / 'src/link_client.cpp'),
                        str(firmware / 'src/proto_codec.cpp'), '-o', str(legacy)], stdout=log, stderr=subprocess.STDOUT, check=True)
    env = {k: v for k, v in os.environ.items() if not k.startswith(('RELAY_', 'FIRMWARE_PEER_', 'OTEL_'))}
    with (args.evidence / 'baseline-relay-build.log').open('w') as log:
        subprocess.run(['./mill', '--no-server', '-j', '1', 'assembly'], cwd=relay, env=env,
                       stdout=log, stderr=subprocess.STDOUT, timeout=500, check=True)
    old_jar = relay / 'out/assembly.dest/out.jar'
    results = []
    for relay_kind, jar in (('old', old_jar), ('new', args.new_assembly.resolve())):
        out = args.evidence / relay_kind; out.mkdir()
        ports = []
        while len(ports) < 2:
            with socket.socket() as s: s.bind(('127.0.0.1', 0)); port = s.getsockname()[1]
            if port not in ports: ports.append(port)
        http, device = ports
        token = secrets.token_urlsafe(32) if relay_kind == 'old' else None
        runenv = env.copy()
        runenv.update(RELAY_TWITCH_MODE='disabled', RELAY_HTTP_HOST='127.0.0.1', RELAY_DEVICE_HOST='127.0.0.1',
                      RELAY_HTTP_PORT=str(http), RELAY_DEVICE_PORT=str(device), RELAY_HTTP_ALLOWED_HOSTS=f'127.0.0.1:{http}',
                      RELAY_RUNTIME_STATE_FILE=str(scratch / relay_kind / 'state/runtime-state.json'))
        if token: runenv['RELAY_HTTP_AUTH_API_TOKEN'] = token

        def request(path, payload=None):
            headers = {'Content-Type': 'application/json'} if payload else {}
            if token: headers['Authorization'] = 'Bearer ' + token
            query = urllib.request.Request(f'http://127.0.0.1:{http}/api/v1/' + path,
                                           None if payload is None else json.dumps(payload).encode(), headers)
            with urllib.request.urlopen(query, timeout=5) as response:
                assert response.status in (200, 201, 202)
                return json.load(response)

        with (out / 'relay.log').open('w') as log:
            server = subprocess.Popen(['java', '-jar', str(jar)], cwd=scratch, env=runenv, stdout=log, stderr=subprocess.STDOUT)
            try:
                until = time.monotonic() + 60
                while True:
                    try: request('health'); break
                    except OSError: pass
                    assert server.poll() is None and time.monotonic() < until
                    time.sleep(.1)
                for firmware_kind, binary in (('old', legacy), ('new', args.binary.resolve())):
                    assert not (root / '.agent-workflow/STOP').exists()
                    evidence = out / firmware_kind; evidence.mkdir()
                    records, published = [], []
                    peer_env = env.copy()
                    capable = relay_kind == firmware_kind == 'new'
                    peer_env.update(FIRMWARE_PEER_PORT=str(device), FIRMWARE_PEER_SECONDS='12',
                                    FIRMWARE_PEER_PROFILE_READY='1', FIRMWARE_PEER_REQUIRE_DESK='1' if capable else '0',
                                    FIRMWARE_PEER_RECONNECT_SECONDS='4')
                    command = [str(binary)] + ([str(device)] if firmware_kind == 'old' else [])
                    with (evidence / 'peer-stderr.log').open('w') as errors:
                        child = subprocess.Popen(command, env=peer_env, text=True, stdout=subprocess.PIPE, stderr=errors)
                        deadline = threading.Timer(20, child.kill); deadline.start()
                        try:
                            assert child.stdout is not None
                            with (evidence / 'observations.jsonl').open('w') as capture:
                                for line in observation_lines(child):
                                    capture.write(line); capture.flush()
                                    row = json.loads(line); records.append(row)
                                    if row['event'] == ('desk_ready' if capable else 'welcome') and len(published) < 2:
                                        published.append(request('notifications', dict(type='info', title='Synthetic compatibility', body='', ttlMs=500)))
                            code = child.wait(timeout=5)
                        finally:
                            deadline.cancel()
                            if child.poll() is None: child.kill(); child.wait(timeout=5)
                    assert code == 0 and len(published) == 2, (relay_kind, firmware_kind, code)
                    expected = [p['seq'] for p in published]
                    welcomes = [r for r in records if r['event'] == 'welcome']
                    assert len(welcomes) == 2 and all(bool(r['caps'] & 16) == capable for r in welcomes)
                    if firmware_kind == 'old':
                        assert [r['seq'] for r in records if r['event'] == 'dequeued'] == expected
                    else:
                        assert [r['seq'] for r in records if r['event'] == 'started'] == expected
                        verify_callbacks(records, [500, 500]); verify_report_phases(records)
                        if not capable:
                            assert not any(r['event'] in ('desk_ready', 'device_report_written') for r in records)
                    result = dict(relay=relay_kind, firmware=firmware_kind, passed=True, publishedSequences=expected,
                                  negotiatedDesk=capable, physicalVerified=False,
                                  testedLayer='Original baseline LinkSession/NotifyQueue TCP adapter' if firmware_kind == 'old' else 'Current actual LinkSession/Controller/Receiver/LVGL TCP application',
                                  assemblySha256=hashlib.sha256(jar.read_bytes()).hexdigest(), peerSha256=hashlib.sha256(binary.read_bytes()).hexdigest())
                    results.append(result)
                    (evidence / 'verified.json').write_text(json.dumps(result, indent=2) + '\n')
                    (args.evidence / 'verified.json').write_text(json.dumps(results, indent=2) + '\n')
            finally:
                if server.poll() is None: server.terminate()
                server.wait(timeout=30)
                closed = []
                for port in ports:
                    with socket.socket() as s: s.settimeout(1); closed.append(s.connect_ex(('127.0.0.1', port)) != 0)
                (out / 'cleanup.json').write_text(json.dumps(dict(ownedServerReaped=True, listenersClosed=all(closed))) + '\n')
                assert all(closed)
    assert len(results) == 4
    print(json.dumps(results, indent=2))


if __name__ == '__main__':
    main()
