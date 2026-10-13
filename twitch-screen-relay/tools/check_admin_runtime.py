"""Exercise canonical admin HTTP/SSE on an isolated, installed Main artifact. No board or provider access."""
from pathlib import Path
import argparse
import hashlib
import http.client
import json
import os
import socket
import subprocess
import sys
import tempfile
import time
from typing import Any

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'tools'))
from check_desk_contract import validate


def free_port():
    with socket.socket() as listener:
        listener.bind(('127.0.0.1', 0))
        return listener.getsockname()[1]


def describe_failure(error):
    return {'type': type(error).__name__, 'message': str(error)[:2000]}


def capture_threads(child, evidence, prefix):
    """Owned synthetic JVM only. Include virtual threads, which a traditional jstack omits when unmounted."""
    failures = []
    commands = (
        ('jstack', ['jstack', str(child.pid)]),
        ('jcmd', ['jcmd', str(child.pid), 'Thread.dump_to_file', '-format=json', str(evidence / f'{prefix}-threads.json')]),
    )
    for name, command in commands:
        try:
            with (evidence / f'{prefix}-{name}.txt').open('w') as output:
                completed = subprocess.run(command, stdout=output, stderr=subprocess.STDOUT, timeout=5)
            if completed.returncode != 0:
                failures.append(f'{name}: exit {completed.returncode}')
        except (OSError, subprocess.TimeoutExpired) as error:
            failures.append(f'{name}: {type(error).__name__}')
    return failures


def finish_run(result, evidence, child, feeds, ports, close_feed, failure=None, shutdown_seconds=30):
    """Reap even a hung Main and persist the verdict before surfacing cleanup failure. Never replace the primary exception."""
    errors = []
    if failure is not None:
        result.update(passed=False, failure=describe_failure(failure))
    for connection, response in feeds:
        try:
            close_feed(connection, response)
        except Exception as error:
            errors.append({'phase': 'feed_close', **describe_failure(error)})
    if child is not None:
        result.update(mainKilled=False, mainReaped=False)
        try:
            if child.poll() is None:
                child.terminate()
            child.wait(timeout=shutdown_seconds)
        except subprocess.TimeoutExpired as error:
            errors.append({'phase': 'main_shutdown', **describe_failure(error)})
            result['shutdownDiagnostics'] = capture_threads(child, evidence, 'shutdown')
        except Exception as error:
            errors.append({'phase': 'main_shutdown', **describe_failure(error)})
        finally:
            if child.poll() is None:
                try:
                    child.kill()
                    result['mainKilled'] = True
                    child.wait(timeout=5)
                except Exception as error:
                    errors.append({'phase': 'main_reap', **describe_failure(error)})
            result['mainReaped'] = child.poll() is not None
            result['mainExitCode'] = child.returncode
    result['listenersClosed'] = True
    for port in ports:
        try:
            with socket.socket() as probe:
                probe.settimeout(1)
                result['listenersClosed'] &= probe.connect_ex(('127.0.0.1', port)) != 0
        except OSError as error:
            result['listenersClosed'] = False
            errors.append({'phase': 'listener_probe', **describe_failure(error)})
    if not result['listenersClosed']:
        errors.append({'phase': 'listener_probe', 'type': 'AssertionError', 'message': 'owned listeners still open'})
    result['cleanupErrors'] = errors
    if errors:
        result['passed'] = False
    (evidence / 'result.json').write_text(json.dumps(result, indent=2))
    if errors and failure is None:
        raise RuntimeError(f'owned Main cleanup failed; see {evidence / "result.json"}')


def run(assembly, evidence, long_seconds=65, native=None, slow_reader=False, browser_executable=None):
    os.umask(0o077)
    evidence.mkdir(parents=True, exist_ok=True, mode=0o700)
    schema = json.loads((ROOT / 'protocol/desk-v1-http.schema.json').read_text())
    http_port, device = free_port(), free_port()
    while http_port == device:
        device = free_port()
    base = f'http://127.0.0.1:{http_port}'
    env = {key: value for key, value in os.environ.items() if not key.startswith('RELAY_')}
    env.update(RELAY_TWITCH_MODE='disabled', RELAY_TWITCH_CHANNEL='', RELAY_HTTP_HOST='127.0.0.1',
               RELAY_DEVICE_HOST='127.0.0.1', RELAY_HTTP_PORT=str(http_port), RELAY_DEVICE_PORT=str(device),
               RELAY_HTTP_ALLOWED_HOSTS=f'127.0.0.1:{http_port},127.0.0.1:8080,localhost:8080',
               RELAY_HTTP_ALLOWED_ORIGINS=f'{base},http://127.0.0.1:8080,http://localhost:8080',
               OTEL_TRACES_EXPORTER='none', OTEL_METRICS_EXPORTER='none', OTEL_LOGS_EXPORTER='none')
    result = {'assemblySha256': hashlib.sha256(assembly.read_bytes()).hexdigest(), 'productAccepted': False, 'checks': []}
    feeds = []
    child = None
    failure = None

    def exchange(path, method='GET', data=None, headers=None):
        connection = http_port_client()
        body = None if data is None else json.dumps(data).encode()
        fields = {'Content-Type': 'application/json'} if data is not None else {}
        fields.update(headers or {})
        try:
            connection.request(method, path, body, fields)
            response = connection.getresponse()
            raw = response.read(524289)
            assert len(raw) <= 524288
            return response.status, dict(response.getheaders()), json.loads(raw)
        finally:
            connection.close()

    def http_port_client(source='127.0.0.1'):
        return http.client.HTTPConnection('127.0.0.1', http_port, timeout=20, source_address=(source, 0))

    def feed(source):
        connection = http_port_client(source)
        feeds.append((connection, None))  # Own the socket even when request/getresponse times out.
        connection.request('GET', '/api/v1/admin/events')
        response = connection.getresponse()
        feeds[-1] = (connection, response)
        return connection, response

    def close_feed(connection, response):
        try:
            if response is not None:
                response.close()
        finally:
            connection.close()

    def event(response) -> dict[str, Any]:
        fields: dict[str, Any] = {}
        while True:
            line = response.readline(524289)
            assert line, 'feed unexpectedly ended'
            assert len(line) <= 524288, 'feed line exceeded snapshot bound'
            text = line.decode().rstrip('\r\n')
            if text.startswith(':'):
                return {'comment': text}
            if not text:
                if 'data' in fields:
                    value = json.loads(fields['data'])
                    validate(value, schema['$defs']['AdminSnapshot'], schema)
                    assert fields['id'] == f"{value['relayEpoch']}:{value['revision']}"
                    fields['snapshot'] = value
                    return fields
                continue
            name, value = text.split(':', 1)
            fields[name] = value.lstrip(' ')

    with tempfile.TemporaryDirectory(prefix='relay-admin-', dir=os.environ['TMPDIR']) as temporary:
        runtime = Path(temporary)
        env.update(RELAY_RUNTIME_STATE_FILE=str(runtime / 'state/runtime-state.json'),
                   RELAY_TWITCH_TOKEN_FILE=str(runtime / 'unused-token.json'))
        with (evidence / 'main.log').open('w') as log:
            try:
                assert not (ROOT / '.agent-workflow/STOP').exists()
                child = subprocess.Popen(['java', '-jar', str(assembly)], cwd=runtime, env=env, stdout=log, stderr=subprocess.STDOUT)
                until = time.monotonic() + 60
                while True:
                    try:
                        status, _, initial = exchange('/api/v1/admin/snapshot')
                        if status == 200:
                            break
                    except OSError:
                        pass
                    assert time.monotonic() < until and child.poll() is None, 'owned Main did not become ready'
                    time.sleep(.05)
                validate(initial, schema['$defs']['AdminSnapshot'], schema)
                result['checks'].append('actual Main canonical schema and missing-source snapshot')
                assert exchange('/api/v1/admin/snapshot', headers={'Origin': 'https://hostile.invalid'})[0] == 403
                assert exchange('/api/v1/admin/events', headers={'Origin': 'null'})[0] == 403
                assert exchange('/api/v1/admin/events', headers={'X-Forwarded-For': '127.0.0.99'})[0] == 403
                result['checks'].append('Host/origin/untrusted-forwarding defenses before feed admission')
                connection, response = feed('127.0.0.1')
                assert response.status == 200
                assert response.getheader('Cache-Control') == 'no-store'
                assert response.getheader('X-Accel-Buffering') == 'no'
                first = event(response)
                assert first['event'] == 'snapshot'
                assert first['snapshot']['revision'] >= initial['revision']
                connection2, response2 = feed('127.0.0.1')
                assert response2.status == 200 and event(response2)['event'] == 'snapshot'
                excess, rejected = feed('127.0.0.1')
                assert rejected.status == 429
                close_feed(excess, rejected)
                for suffix in (2, 3, 4):
                    for _ in range(2):
                        _, extra = feed(f'127.0.0.{suffix}')
                        assert extra.status == 200 and event(extra)['event'] == 'snapshot'
                excess, rejected = feed('127.0.0.5')
                assert rejected.status == 429
                close_feed(excess, rejected)
                result['checks'].append('two per address and eight global admissions; ninth rejected')
                for extra_connection, extra_response in feeds[1:]:
                    close_feed(extra_connection, extra_response)
                feeds = feeds[:1]
                until = time.monotonic() + 5
                while True:
                    retry, reopened = feed('127.0.0.1')
                    if reopened.status == 200:
                        assert event(reopened)['event'] == 'snapshot'
                        close_feed(retry, reopened)
                        break
                    close_feed(retry, reopened)
                    assert time.monotonic() < until, 'disconnected feeds did not release admission'
                    time.sleep(.02)
                feeds = feeds[:1]
                result['checks'].append('real disconnect releases admission within five seconds')
                accepted = exchange('/api/v1/notifications', 'POST', {'type': 'info', 'title': '😀' * 200, 'body': 'x' * 200})
                assert accepted[0] == 200
                seq = accepted[2]['seq']
                until = time.monotonic() + 5
                while True:
                    update = event(response)
                    if 'snapshot' in update and any(row['identity']['seq'] == seq for row in update['snapshot']['notifications']):
                        break
                    assert time.monotonic() < until
                row = next(row for row in update['snapshot']['notifications'] if row['identity']['seq'] == seq)
                assert len(row['title']) == 160 and len(row['body']) == 160 and row['identity']['origin'] == 'external'
                result['checks'].append('actual publication appears in schema-checked complete SSE with bounded previews')
                status, _, cpu = exchange('/api/v1/cpu', 'PUT', {'sourceId': 'admin-test', 'host': 'synthetic',
                                                               'cpuPercent': 42.25, 'memoryPercent': 0, 'freshnessMs': 1000})
                assert status == 200
                until = time.monotonic() + 5
                while True:
                    update = event(response)
                    source = update.get('snapshot', {}).get('sources', {}).get('cpu', {})
                    if source.get('revision') == cpu['revision'] and source.get('status') == 'stale':
                        assert source['data']['cpuPercent'] == 42.25 and source['data']['memoryPercent'] == 0
                        assert source['remainingFreshnessMs'] == 0
                        break
                    assert time.monotonic() < until
                result['checks'].append('actual source deadline pushes stale unchanged numeric data without another write')
                started = time.monotonic()
                comments = 0
                snapshots = 0
                while time.monotonic() - started <= long_seconds:
                    update = event(response)
                    if 'comment' in update:
                        comments += 1
                    else:
                        snapshots += 1
                elapsed = time.monotonic() - started
                assert comments >= 4 and elapsed > 60
                result.update(idleFeedSeconds=elapsed, heartbeatComments=comments, timedChartSnapshots=snapshots)
                result['checks'].append('production SSE exceeds sixty seconds without inbound application traffic')
                close_feed(connection, response)
                feeds = []
                if slow_reader:
                    # Synthetic worst-case escaped previews fill the real kernel window; no invented slow-writer response.
                    content = 'x' + '\x01' * 159
                    for _ in range(80):
                        status, _, _ = exchange('/api/v1/notifications', 'POST', {'type': 'info', 'title': content, 'body': content})
                        assert status == 200
                        time.sleep(.11)
                    slow = socket.socket()
                    slow.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 1024)
                    slow.bind(('127.0.0.6', 0))
                    slow.connect(('127.0.0.1', http_port))
                    slow.sendall(f'GET /api/v1/admin/events HTTP/1.1\r\nHost: 127.0.0.1:{http_port}\r\n\r\n'.encode())
                    try:
                        steady, steady_response = feed('127.0.0.6')
                        assert steady_response.status == 200 and event(steady_response)['event'] == 'snapshot'
                        extra, excess = feed('127.0.0.6')
                        assert excess.status == 429
                        close_feed(extra, excess)
                        start_pressure = time.monotonic()
                        until = start_pressure + 45
                        released = False
                        while time.monotonic() < until:
                            status, _, _ = exchange('/api/v1/cpu', 'PUT', {'sourceId': 'pressure', 'host': 'synthetic', 'cpuPercent': 42})
                            assert status == 200
                            event(steady_response)
                            if time.monotonic() - start_pressure > 12:
                                extra, replacement = feed('127.0.0.6')
                                if replacement.status == 200:
                                    assert event(replacement)['event'] == 'snapshot'
                                    close_feed(extra, replacement)
                                    released = True
                                    break
                                assert replacement.status == 429
                                close_feed(extra, replacement)
                            time.sleep(.11)
                        assert released, 'real unread feed did not release its bounded write/admission owner'
                        result['slowReaderReleasedAfterSeconds'] = time.monotonic() - start_pressure
                        result['checks'].append('real unread kernel-window pressure releases feed while CPU and HTTP remain responsive')
                    finally:
                        slow.close()
                        for extra_connection, extra_response in feeds:
                            close_feed(extra_connection, extra_response)
                        feeds = []
                if native is not None:
                    command = [sys.executable, str(ROOT / 'twitch-screen-firmware/tools/pair_runtime.py'),
                               '--binary', str(native), '--http-port', str(http_port), '--device-port', str(device),
                               '--evidence', str(evidence / 'pairing'), '--queue-pressure', '--require-desk', '--desk-reconnect']
                    with (evidence / 'pairing.log').open('w') as pairing_log:
                        completed = subprocess.run(command, cwd=ROOT, stdout=pairing_log, stderr=subprocess.STDOUT, timeout=180)
                    result['pairingExitCode'] = completed.returncode
                    assert completed.returncode == 0
                    result['nativeSha256'] = hashlib.sha256(native.read_bytes()).hexdigest()
                    result['checks'].append('actual native classic-pressure DESK lifecycle and same-epoch reconnect')
                    legacy_evidence = evidence / 'legacy-running'
                    for script, extra in (
                        ('test_legacy_production.py', ['--http-port', str(http_port), '--device-port', str(device), '--test-owned']),
                        ('test_legacy_production_verdict.py', []),
                    ):
                        command = [sys.executable, str(ROOT / 'twitch-screen-firmware/tools' / script),
                                   '--evidence', str(legacy_evidence), *extra]
                        with (evidence / (script + '.log')).open('w') as legacy_log:
                            completed = subprocess.run(command, cwd=ROOT, stdout=legacy_log,
                                                       stderr=subprocess.STDOUT, timeout=90)
                        assert completed.returncode == 0, script
                    result['checks'].append('immutable baseline firmware chronological transfers across actual running Main reconnect and negative verdicts')
                if browser_executable is not None:
                    from check_admin_browser import run as browser_run
                    result['browser'] = browser_run(base, evidence / 'browser', browser_executable, native=native, device_port=device)
                    result['checks'].append('actual production-origin responsive controls sources XSS no-polling and two-tab Chromium')
                status, _, final = exchange('/api/v1/admin/snapshot')
                assert status == 200
                validate(final, schema['$defs']['AdminSnapshot'], schema)
                if native is not None:
                    assert final['devices'] and all(row['reportStatus'] == 'historical' for row in final['devices'])
                    assert any(row['reportRevision'] is not None for row in final['devices'])
                (evidence / 'snapshot.json').write_text(json.dumps(final, indent=2))
                result['passed'] = True
            except BaseException as error:
                failure = error
                result.update(passed=False, failure=describe_failure(error))
                if child is not None and child.poll() is None:
                    result['failureDiagnostics'] = capture_threads(child, evidence, 'failure')
                raise
            finally:
                finish_run(result, evidence, child, feeds, (http_port, device), close_feed, failure=failure)
    print(json.dumps(result, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--assembly', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--native', type=Path)
    parser.add_argument('--slow-reader', action='store_true', help='Exercise actual unread kernel-window backpressure')
    parser.add_argument('--browser-executable', type=Path, help='Optional pinned test-only Chromium checks')
    args = parser.parse_args()
    run(args.assembly.resolve(), args.evidence.resolve(), native=args.native.resolve() if args.native else None,
        slow_reader=args.slow_reader, browser_executable=args.browser_executable.resolve() if args.browser_executable else None)


if __name__ == '__main__':
    main()
