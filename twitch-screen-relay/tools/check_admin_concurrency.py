"""Check installed Main admin capture under concurrent canonical writes. Test-owned resources only."""
from pathlib import Path
import argparse
from concurrent.futures import ThreadPoolExecutor
import hashlib
import http.client
import json
import os
import socket
import subprocess
import tempfile
import threading
import time

from check_admin_runtime import ROOT, free_port
from check_desk_contract import validate


def run(assembly: Path, evidence: Path, browser_executable=None, native=None):
    os.umask(0o077)
    evidence.mkdir(parents=True, exist_ok=True)
    assert not (ROOT / '.agent-workflow/STOP').exists()
    schema = json.loads((ROOT / 'protocol/desk-v1-http.schema.json').read_text())
    http_port, device_port = free_port(), free_port()
    while http_port == device_port:
        device_port = free_port()
    origin = f'http://127.0.0.1:{http_port}'
    result = {'productAccepted': False, 'assemblySha256': hashlib.sha256(assembly.read_bytes()).hexdigest()}
    responses, snapshots, failures = [], [[], []], []
    stop = threading.Event()
    feeds, threads = [], []
    lock = threading.Lock()
    child = None

    def exchange(path, method='GET', payload=None):
        started = time.monotonic()
        connection = http.client.HTTPConnection('127.0.0.1', http_port, timeout=7)
        try:
            connection.request(method, path, None if payload is None else json.dumps(payload),
                               {} if payload is None else {'Content-Type': 'application/json'})
            response = connection.getresponse()
            raw = response.read(524289)
            assert len(raw) <= 524288
            body = json.loads(raw)
            with lock:
                responses.append({'path': path, 'method': method, 'status': response.status,
                                  'elapsedMs': round((time.monotonic() - started) * 1000, 3), 'body': body})
            assert response.status == 200, (path, response.status, body)
            return body
        finally:
            connection.close()

    def check(snapshot):
        validate(snapshot, schema['$defs']['AdminSnapshot'], schema)
        assert snapshot['relayEpoch'] == snapshot['monitoring']['relayEpoch'] == snapshot['display']['relayEpoch']
        assert snapshot['monitoring']['sourceGeneration'] == snapshot['charts']['sourceGeneration']
        for source in snapshot['sources'].values():
            assert source['relayEpoch'] == snapshot['relayEpoch']
        return snapshot

    def reader(index, response):
        try:
            fields = {}
            while not stop.is_set():
                line = response.readline(524289)
                assert line and len(line) <= 524288, 'feed ended during concurrent writes'
                text = line.decode().rstrip('\r\n')
                if text.startswith(':'):
                    continue
                if text:
                    name, value = text.split(':', 1)
                    fields[name] = value.lstrip(' ')
                elif 'data' in fields:
                    snapshot = check(json.loads(fields['data']))
                    assert fields['id'] == f"{snapshot['relayEpoch']}:{snapshot['revision']}"
                    previous = snapshots[index][-1] if snapshots[index] else None
                    if previous and previous['relayEpoch'] == snapshot['relayEpoch']:
                        assert snapshot['revision'] > previous['revision'], 'duplicate or regressive feed revision'
                    snapshots[index].append(snapshot)
                    fields = {}
        except Exception as error:
            if not stop.is_set():
                failures.append(repr(error))

    with tempfile.TemporaryDirectory(prefix='relay-admin-concurrency-', dir=os.environ['TMPDIR']) as temporary:
        runtime = Path(temporary)
        env = {key: value for key, value in os.environ.items() if not key.startswith('RELAY_')}
        env.update(RELAY_TWITCH_MODE='disabled', RELAY_TWITCH_CHANNEL='', RELAY_HTTP_HOST='127.0.0.1',
                   RELAY_DEVICE_HOST='127.0.0.1', RELAY_HTTP_PORT=str(http_port), RELAY_DEVICE_PORT=str(device_port),
                   RELAY_HTTP_ALLOWED_HOSTS=f'127.0.0.1:{http_port}', RELAY_HTTP_ALLOWED_ORIGINS=origin,
                   RELAY_RUNTIME_STATE_FILE=str(runtime / 'state/runtime-state.json'),
                   RELAY_TWITCH_TOKEN_FILE=str(runtime / 'unused-token.json'),
                   OTEL_TRACES_EXPORTER='none', OTEL_METRICS_EXPORTER='none', OTEL_LOGS_EXPORTER='none')
        with (evidence / 'main.log').open('w') as log:
            try:
                child = subprocess.Popen(['java', '-jar', str(assembly)], cwd=runtime, env=env,
                                         stdout=log, stderr=subprocess.STDOUT)
                deadline = time.monotonic() + 60
                while True:
                    try:
                        check(exchange('/api/v1/admin/snapshot'))
                        break
                    except OSError:
                        assert child.poll() is None and time.monotonic() < deadline
                        time.sleep(.05)
                for index in range(2):
                    connection = http.client.HTTPConnection('127.0.0.1', http_port, timeout=7)
                    connection.request('GET', '/api/v1/admin/events')
                    response = connection.getresponse()
                    assert response.status == 200
                    feeds.append((connection, response))
                    thread = threading.Thread(target=reader, args=(index, response))
                    threads.append(thread)
                    thread.start()
                deadline = time.monotonic() + 5
                while not all(snapshots):
                    assert time.monotonic() < deadline and not failures
                    time.sleep(.01)

                def monitoring():
                    for index in range(30):
                        exchange('/api/v1/twitch/monitoring', 'PATCH',
                                 {'mode': 'disabled', 'channel': f'synthetic{index}'})

                def display():
                    for index in range(30):
                        exchange('/api/v1/display', 'PATCH', {'twitchDisplayEnabled': bool(index % 2)})
                        exchange('/api/v1/display', 'PATCH', {'preferredDashboard': ('cpu', 'weather', 'generic')[index % 3]})

                def observations():
                    for _ in range(60):
                        check(exchange('/api/v1/admin/snapshot'))

                with ThreadPoolExecutor(max_workers=3) as workers:
                    tasks = [workers.submit(function) for function in (monitoring, display, observations)]
                    for task in tasks:
                        task.result(timeout=60)
                final_cpu = exchange('/api/v1/cpu', 'PUT',
                                     {'sourceId': 'concurrency', 'host': 'synthetic', 'cpuPercent': 42.25,
                                      'memoryPercent': 0, 'freshnessMs': 10000})
                final = check(exchange('/api/v1/admin/snapshot'))
                assert final['monitoring']['desired']['channel'] == 'synthetic29'
                assert final['display']['preferredDashboard'] == 'generic'
                assert final['display']['twitchDisplayEnabled'] is True
                deadline = time.monotonic() + 10
                while True:
                    assert not failures, failures
                    if all(any(row['monitoring']['sourceGeneration'] == final['monitoring']['sourceGeneration']
                               and row['display']['revision'] == final['display']['revision']
                               and row['sources']['cpu']['revision'] == final_cpu['revision'] for row in rows)
                           for rows in snapshots):
                        break
                    assert time.monotonic() < deadline, 'feeds did not converge to final accepted authority'
                    time.sleep(.02)
                if browser_executable is not None:
                    stop.set()
                    for connection, _ in feeds:
                        if connection.sock is not None:
                            connection.sock.shutdown(socket.SHUT_RDWR)
                    for thread in threads:
                        thread.join(8)
                    for connection, response in feeds:
                        response.close()
                        connection.close()
                    feeds.clear()
                    from check_admin_browser import run as browser_run
                    result['browser'] = browser_run(origin, evidence / 'browser', browser_executable,
                                                    native=native, device_port=device_port)
                result.update(passed=True, responseCount=len(responses), feedCounts=[len(rows) for rows in snapshots],
                              finalSourceGeneration=final['monitoring']['sourceGeneration'],
                              finalDisplayRevision=final['display']['revision'], finalCpuRevision=final_cpu['revision'])
            finally:
                stop.set()
                for connection, _ in feeds:
                    if connection.sock is not None:
                        try:
                            connection.sock.shutdown(socket.SHUT_RDWR)
                        except OSError:
                            pass
                for thread in threads:
                    thread.join(8)
                for connection, response in feeds:
                    response.close()
                    connection.close()
                if child is not None:
                    if child.poll() is None:
                        child.terminate()
                    child.wait(timeout=30)
                    result['mainExitCode'] = child.returncode
                result['readersClosed'] = all(not thread.is_alive() for thread in threads)
                result['listenersClosed'] = True
                for port in (http_port, device_port):
                    with socket.socket() as probe:
                        probe.settimeout(1)
                        result['listenersClosed'] &= probe.connect_ex(('127.0.0.1', port)) != 0
                result['failures'] = failures
                (evidence / 'responses.json').write_text(json.dumps(responses, indent=2))
                (evidence / 'feeds.json').write_text(json.dumps(snapshots, indent=2))
                (evidence / 'result.json').write_text(json.dumps(result, indent=2))
                assert result['readersClosed'] and result['listenersClosed']
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--assembly', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--browser-executable', type=Path)
    parser.add_argument('--native', type=Path)
    args = parser.parse_args()
    run(args.assembly.resolve(), args.evidence.resolve(),
        browser_executable=args.browser_executable.resolve() if args.browser_executable else None,
        native=args.native.resolve() if args.native else None)
