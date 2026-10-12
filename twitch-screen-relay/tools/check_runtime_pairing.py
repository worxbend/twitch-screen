#!/usr/bin/env python3
"""Exercise the assembled relay with the actual companion HTTP delivery code.

Own the disposable child, ports and private state. Never touch board, services,
OAuth tokens, real notification buses or unrelated listeners. Always reap child.
"""
import argparse
import asyncio
import errno
import importlib
import json
import os
from pathlib import Path
import runpy
import socket
import subprocess
import sys
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
EVIDENCE = ROOT / '.agent-workflow/evidence/relay/implement-06'


def free_port():
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        return listener.getsockname()[1]


def request(base, path, payload=None, method=None):
    wire = None if payload is None else json.dumps(payload, allow_nan=False).encode()
    headers = {'Content-Type': 'application/json'} if wire else {}
    with urllib.request.urlopen(urllib.request.Request(base + path, data=wire, headers=headers, method=method), timeout=3) as response:
        return json.load(response)


def listener_closed(port):
    with socket.socket() as probe:
        probe.settimeout(1)
        return probe.connect_ex(('127.0.0.1', port)) == errno.ECONNREFUSED


def wait_ready(child, base):
    deadline = time.monotonic() + 30
    while True:
        if child.poll() is not None:
            raise RuntimeError('test-owned assembled relay exited before readiness')
        try:
            request(base, '/api/v1/clock')
            return
        except (OSError, ValueError):
            if time.monotonic() >= deadline:
                raise RuntimeError('test-owned assembled relay readiness deadline') from None
            time.sleep(0.1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--evidence-dir', type=Path, default=EVIDENCE)
    parser.add_argument('--verify-ingress', action='store_true')
    args = parser.parse_args()
    evidence = args.evidence_dir.resolve()
    runtime = evidence / 'pairing-runtime'
    os.umask(0o077)
    runtime.mkdir(parents=True, exist_ok=True, mode=0o700)
    http_port, device_port = free_port(), free_port()
    while device_port == http_port:
        device_port = free_port()
    owned_ports = [http_port, device_port]
    env = {'PATH': os.environ['PATH'], 'HOME': os.environ['HOME'], 'LANG': 'C.UTF-8',
           'RELAY_HTTP_HOST': '127.0.0.1', 'RELAY_HTTP_PORT': str(http_port),
           'RELAY_HTTP_ALLOWED_HOSTS': f'127.0.0.1:{http_port}',
           'RELAY_HTTP_ALLOWED_ORIGINS': f'http://127.0.0.1:{http_port}',
           'RELAY_DEVICE_HOST': '127.0.0.1', 'RELAY_DEVICE_PORT': str(device_port),
           'RELAY_TWITCH_MODE': 'disabled', 'RELAY_TWITCH_CHANNEL': 'synthetic',
           'RELAY_RUNTIME_STATE_FILE': str(runtime / 'state.json'),
           'RELAY_TWITCH_TOKEN_FILE': str(runtime / 'unused-token.json'),
           'OTEL_TRACES_EXPORTER': 'none', 'OTEL_METRICS_EXPORTER': 'none', 'OTEL_LOGS_EXPORTER': 'none'}
    base = f'http://127.0.0.1:{http_port}'
    report = {'status': 'blocked', 'testedLayer': 'assembled production relay + actual companion HTTP delivery',
              'productionVerified': False, 'physicalVerified': False, 'productAccepted': False,
              'checks': [], 'blockers': [], 'childStopped': False}
    with (evidence / 'pairing-runtime.log').open('w') as log:
        child = subprocess.Popen(['java', '-jar', str(ROOT / 'twitch-screen-relay/out/assembly.dest/out.jar')],
                                 cwd=runtime, env=env, stdout=log, stderr=subprocess.STDOUT)
        try:
            wait_ready(child, base)
            sys.path.insert(0, str(ROOT / 'twitch-screen-client/src'))
            Config = importlib.import_module('twitch_screen_client.config').Config
            companion = runpy.run_path(str(ROOT / 'twitch-screen-client/tools/check_relay.py'))
            paired = asyncio.run(companion['check'](Config(base, 'relay06-pair', host='synthetic-pair', sample_interval_ms=1000), exercise=True))
            (evidence / 'companion-pairing.json').write_text(json.dumps(paired, indent=2, allow_nan=False) + '\n')
            report['checks'].extend(paired['checks'])
            report['blockers'].extend(paired['blockers'])
            if paired['status'] != 'passed':
                raise RuntimeError('actual companion HTTP pairing incomplete')
            clock = request(base, '/api/v1/clock')
            payload = {'sourceId': 'relay06-expiry', 'host': 'synthetic-expiry', 'cpuPercent': 12.345,
                       'freshnessMs': 1000,
                       'deadline': {'relayEpoch': clock['relayEpoch'], 'monotonicMs': clock['monotonicMs'] + 200}}
            accepted = request(base, '/api/v1/cpu', payload, 'PUT')
            assert accepted['status'] == 'fresh' and 0 < accepted['remainingFreshnessMs'] <= 200
            time.sleep(0.25)
            stale = request(base, '/api/v1/cpu')
            assert stale['revision'] == accepted['revision'] and stale['data'] == accepted['data']
            assert stale['status'] == 'stale' and stale['remainingFreshnessMs'] == 0
            report['checks'].append('actual CPU deadline expiry readback preserves revision/data without receipt renewal')
            history = request(base, '/api/v1/notifications?pageSize=80')['notifications']
            assert any(row['title'] == 'twitch-screen-e2e pairing' and row['origin'] == 'external' and
                       row['sourceGeneration'] == row['twitchEpisode'] == 0 for row in history)
            report['checks'].append('exact synthetic accepted notification remains in original retained history')
            if args.verify_ingress:
                from ingress_contract_probe import verify
                report['checks'].extend(verify(base))
                display = request(base, '/api/v1/display')
                assert display['cpu']['status'] == 'fresh' and display['weather']['status'] == 'missing'
                from weather_contract_probe import verify as verify_weather
                report['checks'].extend(verify_weather(base))
                changed = request(base, '/api/v1/display', {'twitchDisplayEnabled': not display['twitchDisplayEnabled']}, 'PATCH')
                assert changed['revision'] == display['revision'] + 1
                assert changed['preferredDashboard'] == display['preferredDashboard']
                accepted = request(base, '/api/v1/display', {'twitchDisplayEnabled': True, 'preferredDashboard': 'cpu'}, 'PATCH')
                fields = ('relayEpoch', 'revision', 'twitchDisplayEnabled', 'preferredDashboard')
                assert all(request(base, '/api/v1/display')[key] == accepted[key] for key in fields)
                persisted = (runtime / 'state.json').read_bytes()
                equivalent = request(base, '/api/v1/display', {'preferredDashboard': 'cpu'}, 'PATCH')
                assert all(equivalent[key] == accepted[key] for key in fields)
                assert (runtime / 'state.json').read_bytes() == persisted
                report['checks'].append('actual display PATCH merges desired fields, GET correlates accepted revision, equivalent writes are no-ops')
                old_epoch = accepted['relayEpoch']
                child.terminate()
                child.wait(timeout=15)
                report['restartChildStopped'] = child.poll() is not None
                assert all(listener_closed(port) for port in owned_ports)
                # Preserve state, not TIME_WAIT sockets; use new test-owned authorities for the fresh process.
                http_port, device_port = free_port(), free_port()
                while device_port == http_port or http_port in owned_ports or device_port in owned_ports:
                    http_port, device_port = free_port(), free_port()
                owned_ports.extend((http_port, device_port))
                env.update(RELAY_HTTP_PORT=str(http_port), RELAY_DEVICE_PORT=str(device_port),
                           RELAY_HTTP_ALLOWED_HOSTS=f'127.0.0.1:{http_port}',
                           RELAY_HTTP_ALLOWED_ORIGINS=f'http://127.0.0.1:{http_port}')
                base = f'http://127.0.0.1:{http_port}'
                child = subprocess.Popen(['java', '-jar', str(ROOT / 'twitch-screen-relay/out/assembly.dest/out.jar')],
                                         cwd=runtime, env=env, stdout=log, stderr=subprocess.STDOUT)
                wait_ready(child, base)
                restored = request(base, '/api/v1/display')
                assert restored['relayEpoch'] != old_epoch
                assert restored['revision'] == 1 and restored['twitchDisplayEnabled'] is True
                assert restored['preferredDashboard'] == 'cpu'
                assert restored['weather']['status'] == restored['cpu']['status'] == 'missing'
                assert (runtime / 'state.json').read_bytes() == persisted
                report['checks'].append('fresh actual Main restores persisted display intent with new epoch and missing telemetry without rewriting state')
            report['status'] = 'passed'
        except Exception as failure:
            import traceback
            traceback.print_exc(file=log)
            report['blockers'].append(type(failure).__name__ + ': bounded pairing did not complete; inspect private log')
        finally:
            if child.poll() is None:
                child.terminate()
                try:
                    child.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    child.kill()
                    child.wait(timeout=5)
            report['childStopped'] = child.poll() is not None
            # TIME_WAIT can prevent immediate rebinding after a correctly reaped child.
            # Verify the exact owned listeners refuse connections instead of claiming bind reuse.
            closed = [listener_closed(port) for port in owned_ports]
            report['listenersClosed'] = all(closed)
            if not all(closed):
                report['status'] = 'blocked'
                report['blockers'].append('owned listener cleanup not verified')
    (evidence / 'runtime-pairing.json').write_text(json.dumps(report, indent=2, allow_nan=False) + '\n')
    print(json.dumps(report, allow_nan=False))
    return 0 if report['status'] == 'passed' else 1


if __name__ == '__main__':
    raise SystemExit(main())
