"""Exercise installed companion recovery against a disposable assembled relay.

Requires --test-owned. Launches only an isolated disabled-ingestion JVM, private
synthetic notification bus and foreground companion. No board, desktop session,
service, provider or existing relay is accessed. Fault gateway forwards real
responses; it never fabricates acceptance or calibration data.
"""
import argparse
import asyncio
import contextlib
import hashlib
import http.client
import io
import json
import os
from pathlib import Path
import signal
import shutil
import socket
import sys
import tempfile
import traceback

import httpx
from dbus_fast import Message, MessageType
from twitch_screen_client.config import parse
from twitch_screen_client.dbus_transport import bus_call
from twitch_screen_client.delivery import Delivery, strict_json
from twitch_screen_client.metrics import Sampler, boottime_ms
from twitch_screen_client.normalize import Card
from twitch_screen_client.queue import CardQueue
from twitch_screen_client.responses import matches_submitted_cpu
from twitch_screen_client.runtime import initial_status


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


class FaultGateway:
    """Bounded test-only one-request HTTP gateway to the real loopback relay."""
    def __init__(self, port):
        self.port = port
        self.next_fault: str | None = None
        self.fault_path: str | None = None
        self.records = []
        self.requests = []
        self.tasks = set()
        self.writers = set()
        self.response_held = asyncio.Event()
        self.release_response = asyncio.Event()
        self.records_changed = asyncio.Event()
        self.observations = []
        self.quota_observations = []

    async def __aenter__(self):
        self.server = await asyncio.start_server(self.handle, '127.0.0.1', 0)
        self.url = 'http://127.0.0.1:' + str(self.server.sockets[0].getsockname()[1])
        return self

    async def handle(self, reader, writer):
        task = asyncio.current_task()
        self.tasks.add(task)
        self.writers.add(writer)
        upstream = None
        try:
            async with asyncio.timeout(5):
                header = await reader.readuntil(b'\r\n\r\n')
                rows = header.decode('ascii').split('\r\n')
                method, path, _ = rows[0].split()
                fields = dict(row.lower().split(':', 1) for row in rows[1:] if ':' in row)
                length = int(fields.get('content-length', '0'))
                if not 0 <= length <= 16384:
                    raise ValueError('test request bound')
                body = await reader.readexactly(length)
                fault = self.next_fault if (method in ('POST', 'PUT') and
                    (self.fault_path is None or path == self.fault_path)) else None
                if fault:
                    self.next_fault = None
                payload = strict_json(body) if body else None
                self.requests.append({'method': method, 'path': path, 'payload': payload})
                if fault == 'delay':
                    await asyncio.sleep(.4)
                upstream_reader, upstream = await asyncio.open_connection('127.0.0.1', self.port)
                forwarded = [rows[0], f'Host: 127.0.0.1:{self.port}', 'Connection: close']
                forwarded.extend(row for row in rows[1:] if row and
                                 row.split(':', 1)[0].lower() not in ('host', 'connection'))
                upstream.write(('\r\n'.join(forwarded) + '\r\n\r\n').encode('ascii') + body)
                await upstream.drain()
                response = await upstream_reader.read(65537)
                # Read to EOF; a read may return only the header or a partial body.
                while response and len(response) <= 65536:
                    chunk = await upstream_reader.read(65537 - len(response))
                    if not chunk:
                        break
                    response += chunk
                if not response or len(response) > 65536:
                    raise ValueError('test response bound')
                code = int(response.split(b' ', 2)[1])
                record = {'method': method, 'path': path, 'status': code,
                          'fault': fault, 'payload': payload}
                if ((method == 'PUT' and path == '/api/v1/cpu') or
                        (method == 'POST' and path == '/api/v1/notifications')):
                    # Decode the actual framed response, including chunked bodies;
                    # equal content alone does not identify an accepted publication.
                    class ResponseSocket:
                        def makefile(self, *args):
                            return io.BytesIO(response)
                    decoded = http.client.HTTPResponse(ResponseSocket())  # type: ignore[arg-type]
                    decoded.begin()
                    record['response'] = strict_json(decoded.read(65537))
                    decoded.close()
                self.records.append(record)
                self.records_changed.set()
                if fault == 'hold_response':
                    self.response_held.set()
                    await self.release_response.wait()
                if fault == 'corrupt_response':
                    # Preserve the real upstream status/headers and decoded
                    # acceptance above. This explicit fixture corrupts only the
                    # delivered body; it is never recorded as relay output.
                    headers, _ = response.split(b'\r\n\r\n', 1)
                    lines = headers.split(b'\r\n')
                    lines = [line for line in lines if line.split(b':', 1)[0].lower()
                             not in (b'content-length', b'transfer-encoding')]
                    record['deliveredBodyHex'] = b'{broken'.hex()
                    record['upstreamResponseSha256'] = hashlib.sha256(response).hexdigest()
                    response = b'\r\n'.join(lines) + b'\r\nContent-Length: 7\r\n\r\n{broken'
                if fault != 'lose_response':
                    writer.write(response)
                    await writer.drain()
        except (OSError, ValueError, TimeoutError, asyncio.IncompleteReadError):
            pass
        finally:
            for connection in (upstream, writer):
                if connection is not None:
                    connection.close()
                    with contextlib.suppress(OSError):
                        await connection.wait_closed()
            self.writers.discard(writer)
            self.tasks.discard(task)

    async def __aexit__(self, *exc):
        self.server.close()
        await self.server.wait_closed()
        for writer in list(self.writers):
            writer.close()
        tasks = list(self.tasks)
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)
        assert not self.tasks and not self.writers


class Runtime:
    def __init__(self, assembly, directory, same_ports=False):
        self.assembly, self.directory = assembly, directory
        self.same_ports = same_ports
        self.http_port, self.device_port = free_port(), free_port()
        while self.device_port == self.http_port:
            self.device_port = free_port()
        self.base = f'http://127.0.0.1:{self.http_port}'
        self.child = None
        self.launches = 0
        self.stops = []

    def prepare_ports(self):
        # The default isolates restart recovery from listener rebinding. Explicit
        # same-port mode tests the actual relay without hiding bind failures.
        if self.launches and not self.same_ports:
            self.http_port, self.device_port = free_port(), free_port()
            while self.device_port == self.http_port:
                self.device_port = free_port()
            self.base = f'http://127.0.0.1:{self.http_port}'

    async def start(self):
        self.prepare_ports()
        self.launches += 1
        env = {key: os.environ[key] for key in ('PATH', 'HOME', 'LANG', 'JAVA_HOME') if key in os.environ}
        env.update(RELAY_HTTP_HOST='127.0.0.1', RELAY_HTTP_PORT=str(self.http_port),
                   RELAY_HTTP_ALLOWED_HOSTS=f'127.0.0.1:{self.http_port}',
                   RELAY_DEVICE_HOST='127.0.0.1', RELAY_DEVICE_PORT=str(self.device_port),
                   RELAY_TWITCH_MODE='disabled', RELAY_TWITCH_CHANNEL='',
                   RELAY_RUNTIME_STATE_FILE=str(self.directory / 'runtime-state.json'),
                   RELAY_TWITCH_TOKEN_FILE=str(self.directory / 'unused-token.json'),
                   OTEL_TRACES_EXPORTER='none', OTEL_METRICS_EXPORTER='none', OTEL_LOGS_EXPORTER='none')
        self.log = (self.directory / f'relay-{self.launches}.log').open('wb')
        self.child = await asyncio.create_subprocess_exec('java', '-jar', str(self.assembly),
            cwd=self.directory, env=env, stdout=self.log, stderr=asyncio.subprocess.STDOUT)
        async with httpx.AsyncClient(trust_env=False, timeout=.5) as client, asyncio.timeout(30):
            while True:
                assert self.child.returncode is None, 'owned relay exited before readiness'
                try:
                    reply = await client.get(self.base + '/api/v1/clock')
                    if reply.status_code == 200:
                        return reply.json()['relayEpoch']
                except httpx.HTTPError:
                    pass
                await asyncio.sleep(.05)

    async def stop(self, graceful_timeout=15):
        forced = False
        started = boottime_ms()
        if self.child is not None and self.child.returncode is None:
            self.child.terminate()
            try:
                await asyncio.wait_for(self.child.wait(), graceful_timeout)
            except TimeoutError:
                forced = True
                self.child.kill()
                await asyncio.wait_for(self.child.wait(), 5)
        if self.child is not None and not any(row['pid'] == self.child.pid for row in self.stops):
            self.stops.append({'launch': self.launches, 'pid': self.child.pid,
                               'exitCode': self.child.returncode, 'forcedKill': forced,
                               'stopElapsedMs': boottime_ms() - started,
                               'httpPort': self.http_port, 'devicePort': self.device_port})
        if hasattr(self, 'log'):
            self.log.close()
        assert self.child is None or self.child.returncode is not None
        for port in (self.http_port, self.device_port):
            try:
                reader, writer = await asyncio.wait_for(asyncio.open_connection('127.0.0.1', port), .5)
            except (OSError, TimeoutError):
                continue
            writer.close()
            await writer.wait_closed()
            raise AssertionError('owned listener remains open')


async def wait_until(predicate, timeout=10):
    async with asyncio.timeout(timeout):
        while not predicate():
            await asyncio.sleep(.01)


async def correlated_cpu_readback(client, base, gateway, boundary, diagnostics, timeout=5):
    """Wait for exact successful PUT observation, not a local acceptance counter.

    GET may see a commit before the gateway sees response EOF. Wake on actual
    gateway records and reread after newer commits; never resend a measurement.
    Retain bounded raw attempts on success, timeout and validation failure.
    """
    proof = {'boundary': boundary, 'matched': False, 'attempts': []}
    diagnostics.append(proof)
    try:
        async with asyncio.timeout(timeout):
            for _ in range(64):
                gateway.records_changed.clear()
                response = await client.get(base + '/api/v1/cpu')
                response.raise_for_status()
                readback = response.json()
                records = [row for row in gateway.records[boundary:] if row['method'] == 'PUT']
                proof.update(readback=readback, records=records)
                proof['attempts'].append({'readback': readback, 'recordCount': len(records)})
                for row in records:
                    accepted, payload = row.get('response'), row['payload']
                    if (row['status'] == 200 and isinstance(accepted, dict)
                        and accepted.get('relayEpoch') == readback.get('relayEpoch')
                        and accepted.get('revision') == readback.get('revision')
                        and accepted.get('data') == readback.get('data')
                        and payload.get('deadline', {}).get('relayEpoch') == readback.get('relayEpoch')
                        and matches_submitted_cpu(accepted, payload)
                        and matches_submitted_cpu(readback, payload)
                        and readback['status'] == 'fresh' and readback['remainingFreshnessMs'] > 0
                        and readback['data']['memoryPercent'] is not None):
                        proof.update(matched=True, accepted=accepted, submitted=payload)
                        return readback, [payload]
                await gateway.records_changed.wait()
            raise AssertionError('CPU correlation exceeded bounded observation attempts')
    except BaseException as error:
        proof['error'] = type(error).__name__
        raise


async def history(client, base):
    response = await client.get(base + '/api/v1/notifications?pageSize=80')
    response.raise_for_status()
    return response.json()['notifications']


async def prepare_restart_state(client, base):
    """Seed only disposable desired intent and explicit synthetic RAM fixtures."""
    intent = {'twitchDisplayEnabled': True, 'preferredDashboard': 'cpu'}
    response = await client.patch(base + '/api/v1/display', json=intent)
    response.raise_for_status()
    accepted = response.json()
    assert all(accepted.get(key) == value for key, value in intent.items())
    for name, payload in (
        ('weather', {'sourceId': 'repair08-fixture', 'location': 'Synthetic restart fixture',
                     'temperature': 20, 'condition': 'clear', 'freshnessMs': 60000}),
        ('cpu', {'sourceId': 'repair08-fixture', 'host': 'Synthetic restart fixture',
                 'cpuPercent': 25, 'memoryPercent': 50, 'freshnessMs': 60000}),
    ):
        response = await client.put(base + '/api/v1/' + name, json=payload)
        response.raise_for_status()
        supplied = response.json()
        response = await client.get(base + '/api/v1/' + name)
        response.raise_for_status()
        current = response.json()
        assert current['status'] == 'fresh' and current['remainingFreshnessMs'] > 0
        assert current['relayEpoch'] == accepted['relayEpoch'] == supplied['relayEpoch']
        assert current['revision'] == supplied['revision'] and current['data'] == supplied['data']
    # Exercise both connection policies on actual ingress before stopping the JVM.
    # The ordinary HTTPX exchanges above retain keepalive; this closes explicitly.
    response = await client.get(base + '/api/v1/display', headers={'Connection': 'close'})
    response.raise_for_status()
    current = response.json()
    assert current['relayEpoch'] == accepted['relayEpoch'] and current['revision'] == accepted['revision']
    assert all(current.get(key) == value for key, value in intent.items())
    assert all(current[name]['status'] == 'fresh' for name in ('weather', 'cpu'))
    return current


def check_restart_state(before, after, epoch, weather, cpu, notifications):
    """Require new-epoch canonical readback; persistence is not availability."""
    assert before['relayEpoch'] != epoch == after['relayEpoch']
    for key in ('twitchDisplayEnabled', 'preferredDashboard'):
        assert before[key] == after[key]
    assert not notifications
    for name, source in (('weather', weather), ('cpu', cpu)):
        assert source['relayEpoch'] == epoch and source['status'] == 'missing'
        assert source['data'] is None and source['remainingFreshnessMs'] == 0
        assert after[name]['status'] == 'missing' and after[name]['remainingFreshnessMs'] == 0
    return {'persistedDisplayIntentVerified': True, 'ramAvailabilityResetVerified': True,
            'epochRotated': True, 'historyResetVerified': True,
            'twitchDisplayEnabled': after['twitchDisplayEnabled'],
            'preferredDashboard': after['preferredDashboard']}


async def reap_child(child, timeout=5):
    """Always reap a disposable child, even when graceful termination stalls."""
    if child.returncode is None:
        child.terminate()
    try:
        await asyncio.wait_for(child.communicate(), timeout)
    except TimeoutError:
        if child.returncode is None:
            child.kill()
        await asyncio.wait_for(child.communicate(), timeout)
    assert child.returncode is not None


def check_capture_relay_restart(before, after, epoch, readback, submitted, cards, title) -> dict[str, object]:
    """Correlate a surviving foreground owner with real new-epoch writes."""
    assert type(after['pid']) is int and after['pid'] == before['pid']
    assert after['capture'] == 'available' and after['cpu_accepted'] > before['cpu_accepted']
    assert after['notification_accepted'] == before['notification_accepted'] + 1
    assert readback['relayEpoch'] == epoch and readback['status'] == 'fresh'
    assert readback['remainingFreshnessMs'] > 0 and readback['data']['memoryPercent'] is not None
    assert any(payload.get('deadline', {}).get('relayEpoch') == epoch and
               matches_submitted_cpu(readback, payload) for payload in submitted)
    assert len(cards) == 1
    assert cards[0]['relayEpoch'] == epoch and cards[0]['title'] == title
    assert cards[0]['body'] == '' and cards[0]['origin'] == 'external'
    return {'sameForegroundProcess': True, 'newEpochMeasuredReadback': True,
            'noOldNotificationReplay': True, 'summaryOnlyPreserved': True,
            'proof': {'before': before, 'after': after, 'relayEpoch': epoch,
                      'readback': readback, 'submitted': submitted, 'cards': cards, 'title': title}}


def check_capture_bus_outage(before, after, readback, submitted, cards_before, cards_after) -> dict[str, object]:
    """CPU acceptance remains independently measured while capture is unavailable."""
    assert type(after['pid']) is int and after['pid'] == before['pid']
    assert before['capture'] == after['capture'] == 'unavailable'
    assert after['capture_failures'] >= before['capture_failures'] >= 1
    # A current sampler warmup/reset may coexist with newly accepted valid
    # earlier measurements; correlate actual payload/readback, not that label.
    assert after['cpu_accepted'] >= before['cpu_accepted'] + 2
    for key in ('captured', 'notification_accepted'):
        assert after[key] == before[key]
    assert readback['status'] == 'fresh' and readback['remainingFreshnessMs'] > 0
    assert readback['data']['memoryPercent'] is not None
    assert any(payload.get('deadline', {}).get('relayEpoch') == readback['relayEpoch'] and
               matches_submitted_cpu(readback, payload) for payload in submitted)
    retained = lambda rows: [{key: value for key, value in row.items() if key != 'remainingExpiryMs'} for row in rows]
    assert retained(cards_before) == retained(cards_after)
    assert all(new['remainingExpiryMs'] <= old['remainingExpiryMs'] for old, new in zip(cards_before, cards_after))
    return {'sameForegroundProcess': True, 'measuredCpuDuringBusOutage': True,
            'noNotificationPublication': True, 'summaryOnlyPreserved': True,
            'proof': {'before': before, 'after': after, 'readback': readback,
                      'submitted': submitted, 'cardsBefore': cards_before, 'cardsAfter': cards_after}}


async def synthetic_child(config, base, client, directory, tests, runtime=None, gateway=None):
    # Reuse the shipped private-bus fixture, never the inherited desktop bus.
    sys.path.insert(0, str(tests))
    from test_transport import PrivateBus, NAME, PATH
    async with PrivateBus() as bus:
        producer = await bus.connect('synthetic producer')
        servers = []
        tasks = []
        delivered = []

        async def daemon():
            connection = await bus.connect('synthetic notification daemon')
            assert (await connection.call(bus_call('RequestName', 'su', [NAME, 0]))).body == [1]
            servers.append(connection)
            async def serve():
                while True:
                    message = await connection.receive()
                    if message.message_type == MessageType.METHOD_CALL and message.member == 'Notify':
                        delivered.append(message.serial)
                        await connection.send(Message.new_method_return(message, signature='u', body=[42]))
            task = asyncio.create_task(serve())
            tasks.append(task)
            return connection, task

        connection, daemon_task = await daemon()
        private = directory / 'client-runtime'
        private.mkdir(mode=0o700)
        path = private / 'config.toml'
        path.write_text(f'relay_url="{base}"\nsource_id="repair06-child"\nhost="Synthetic host"\n'
                        'sample_interval_ms=1000\nnotifications_enabled=true\ninclude_apps=["twitch-screen-e2e"]\n')
        path.chmod(0o600)
        environment = {**os.environ, 'PYTHONPATH': '', 'XDG_RUNTIME_DIR': str(private),
                       'DBUS_SESSION_BUS_ADDRESS': 'unix:path=' + bus.path}
        bootstrap = (
            "import asyncio, faulthandler, runpy, signal, sys; "
            "faulthandler.enable(); "
            "signal.signal(signal.SIGUSR1, lambda *_: "
            "[task.print_stack(file=sys.stderr) for task in asyncio.all_tasks()]); "
            "sys.argv = ['twitch_screen_client', 'run', '--config', sys.argv[1]]; "
            "runpy.run_module('twitch_screen_client', run_name='__main__')"
        )
        child = await asyncio.create_subprocess_exec(sys.executable, '-c', bootstrap,
            str(path), env=environment, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
        status_path = private / 'twitch-screen-client-repair06-child.json'
        def status():
            return strict_json(status_path.read_bytes()) if status_path.exists() else {}
        try:
            await wait_until(lambda: status().get('capture') == 'available' and status().get('cpu_accepted', 0) >= 1, 15)
            titles = ['repair06 captured first', 'repair06 captured after owner restart']
            if runtime is not None:
                assert gateway is not None
                titles.extend(['wave10 captured after bus restart', 'repair09 captured after relay restart'])
            bus_restart_result = None
            restart_result = None
            request_boundary = 0
            restart_before = {}
            next_epoch = previous_epoch = ''
            config_bytes = b''
            for index, title in enumerate(titles):
                if title.endswith('bus restart'):
                    assert gateway is not None
                    await wait_until(lambda: status().get('notification_accepted') == index)
                    cards_before = await history(client, base)
                    config_before = path.read_bytes()
                    old_bus_pid = bus.process.pid
                    bus.process.terminate()
                    await asyncio.wait_for(bus.process.communicate(), 3)
                    await asyncio.gather(daemon_task, return_exceptions=True)
                    await wait_until(lambda: status().get('capture') == 'unavailable' and
                                     status().get('capture_failures', 0) >= 1)
                    failed = status()
                    boundary = len(gateway.records)
                    await wait_until(lambda: status().get('cpu_accepted', 0) >= failed['cpu_accepted'] + 2, 12)
                    readback, submitted = await correlated_cpu_readback(
                        client, base, gateway, boundary, gateway.observations)
                    bus_restart_result = check_capture_bus_outage(failed, status(), readback, submitted,
                                                                 cards_before, await history(client, base))
                    assert path.read_bytes() == config_before and child.returncode is None
                    Path(bus.path).unlink(missing_ok=True)
                    bus.process = await asyncio.create_subprocess_exec('dbus-daemon', '--nofork', '--print-address=1',
                        '--config-file=' + str(Path(bus.tmp.name) / 'bus.conf'),
                        stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
                    assert bus.process.stdout is not None
                    address = (await asyncio.wait_for(bus.process.stdout.readline(), 3)).decode().strip()
                    assert address.startswith('unix:path=' + bus.path)
                    connection, daemon_task = await daemon()
                    await wait_until(lambda: status().get('capture') == 'available', 40)
                    bus_restart_result.update(unchangedConfiguration=path.read_bytes() == config_before,
                                              sameBusEndpoint=True, oldBusPid=old_bus_pid,
                                              newBusPid=bus.process.pid, childPid=child.pid)
                    assert bus.process.pid != old_bus_pid
                    producer = await bus.connect('synthetic producer after bus restart')
                elif title.endswith('relay restart'):
                    assert runtime is not None and gateway is not None
                    await wait_until(lambda: status().get('notification_accepted') == index)
                    restart_before = status()
                    previous_epoch = (await client.get(base + '/api/v1/clock')).json()['relayEpoch']
                    request_boundary = len(gateway.records)
                    config_bytes = path.read_bytes()
                    await runtime.stop()
                    next_epoch = await runtime.start()
                    gateway.port = runtime.http_port
                    assert next_epoch != previous_epoch
                    # Local counters can publish a late old-epoch acceptance.
                    # Require an actual successful new-epoch PUT before readback.
                    await wait_until(lambda: status().get('cpu_accepted', 0) > restart_before['cpu_accepted'] and
                        any(row['method'] == 'PUT' and row['status'] == 200 and
                            row['payload'].get('deadline', {}).get('relayEpoch') == next_epoch
                            for row in gateway.records[request_boundary:]), 15)
                    assert child.returncode is None and path.read_bytes() == config_bytes
                elif title.endswith('owner restart'):
                    daemon_task.cancel()
                    await asyncio.gather(daemon_task, return_exceptions=True)
                    connection.close()
                    await wait_until(lambda: status().get('capture') == 'daemon_unavailable')
                    connection, daemon_task = await daemon()
                    await wait_until(lambda: status().get('capture') == 'available')
                reply = await asyncio.wait_for(producer.call(Message(destination=NAME, path=PATH, interface=NAME,
                    member='Notify', signature='susssasa{sv}i',
                    body=['twitch-screen-e2e', 0, '', title, 'PRIVATE SYNTHETIC BODY', [], {}, 0])), 3)
                assert reply.message_type == MessageType.METHOD_RETURN and reply.body == [42]
                async with asyncio.timeout(8):
                    while True:
                        rows = await history(client, base)
                        matches = [row for row in rows if row['title'] == title]
                        if matches:
                            assert len(matches) == 1 and matches[0]['body'] == '' and matches[0]['origin'] == 'external'
                            break
                        await asyncio.sleep(.05)
            await wait_until(lambda: status().get('notification_accepted') == len(titles))
            final = status()
            if runtime is not None:
                assert gateway is not None
                readback, submitted = await correlated_cpu_readback(
                    client, base, gateway, request_boundary, gateway.observations)
                restart_result = check_capture_relay_restart(restart_before, final, next_epoch,
                    readback, submitted, await history(client, base), titles[-1])
                restart_result.update(unchangedConfiguration=path.read_bytes() == config_bytes,
                                      childPid=child.pid, previousEpoch=previous_epoch, relayEpoch=next_epoch)
            child.send_signal(signal.SIGTERM)
            communication = asyncio.create_task(child.communicate())
            try:
                stdout, stderr = await asyncio.wait_for(asyncio.shield(communication), 5)
            except TimeoutError:
                child.send_signal(signal.SIGUSR1)
                await asyncio.sleep(.1)
                child.kill()
                stdout, stderr = await asyncio.wait_for(communication, 5)
                (directory / 'companion-shutdown-stacks.log').write_bytes(stderr)
                raise
            assert child.returncode == 0 and not stderr
            assert b'PRIVATE SYNTHETIC BODY' not in stdout + status_path.read_bytes()
            assert status()['capture'] == status()['cpu'] == 'stopped'
            return {'ordinaryDaemonDeliveries': len(delivered), 'notificationAccepted': final['notification_accepted'],
                    'measuredCpuAccepted': final['cpu_accepted'], 'childExit': child.returncode,
                    'ownerRestartCaptured': True, 'summaryOnlyPreserved': True,
                    'busRestart': bus_restart_result, 'relayRestart': restart_result}
        finally:
            (directory / 'companion-capture-observations.json').write_text(json.dumps({
                'status': status(), 'observations': gateway.observations if gateway else [],
                'requests': gateway.requests if gateway else [],
                'records': gateway.records if gateway else [], 'ordinaryDeliveries': delivered}, indent=2) + '\n')
            try:
                await reap_child(child)
            finally:
                for task in tasks:
                    task.cancel()
                await asyncio.gather(*tasks, return_exceptions=True)
                path.unlink(missing_ok=True)


def check_denied_capture(status, before, after, readback, submitted, deliveries):
    """Denied monitoring never counts capture; CPU requires submitted-value proof."""
    assert status['capture'] == 'monitor_denied' and status['capture_failures'] >= 1
    assert status['captured'] == status['notification_accepted'] == 0
    assert status['cpu_accepted'] >= 2 and deliveries == 2
    # Remaining lifetime decreases on GET without any publication or mutation.
    def retained(rows):
        return [{key: value for key, value in row.items() if key != 'remainingExpiryMs'} for row in rows]
    assert retained(before) == retained(after)
    assert all(new.get('remainingExpiryMs', 0) <= old.get('remainingExpiryMs', 0)
               for old, new in zip(before, after))
    assert readback['status'] == 'fresh' and readback['remainingFreshnessMs'] > 0
    assert readback['data']['memoryPercent'] is not None
    assert any(matches_submitted_cpu(readback, payload) for payload in submitted)
    return {'monitorDenied': True, 'ordinaryDaemonDeliveries': deliveries,
            'measuredCpuAccepted': status['cpu_accepted'], 'measuredCpuReadbackVerified': True,
            'noNotificationPublication': True}


def check_foreground_restart(previous_pid, status) -> dict[str, object]:
    """A restarted source has new process ownership and no persisted card spool."""
    assert type(status['pid']) is int and status['pid'] > 0 and status['pid'] != previous_pid
    assert status['capture'] == 'monitor_denied' and status['cpu_accepted'] >= 2
    for field in ('captured', 'notification_accepted', 'notification_ambiguous', 'notification_expired',
                  'notification_refused', 'notification_rejected', 'pendingNotifications', 'pendingNotificationBytes'):
        assert status[field] == 0
    return {'sourceLockReacquired': True, 'newProcessVerified': True, 'noPersistedNotificationSpool': True}


async def denied_capture_child(base, client, directory, tests):
    """Installed foreground child on a denied private bus and actual relay HTTP."""
    sys.path.insert(0, str(tests))
    from test_transport import PrivateBus, NAME, PATH
    before = await history(client, base)
    async with PrivateBus(denied=True) as bus, FaultGateway(int(base.rsplit(':', 1)[1])) as gateway:
        daemon = await bus.connect('synthetic denied notification daemon')
        producer = await bus.connect('synthetic denied producer')
        assert (await daemon.call(bus_call('RequestName', 'su', [NAME, 0]))).body == [1]
        delivered = []

        async def serve():
            while True:
                message = await daemon.receive()
                if message.message_type == MessageType.METHOD_CALL and message.member == 'Notify':
                    delivered.append(message.serial)
                    await daemon.send(Message.new_method_return(message, signature='u', body=[42]))

        daemon_task = asyncio.create_task(serve())
        private = directory / 'denied-client-runtime'
        private.mkdir(mode=0o700)
        path = private / 'config.toml'
        path.write_text(f'relay_url="{gateway.url}"\nsource_id="wave09-denied"\nhost="Synthetic denied host"\n'
                        'sample_interval_ms=1000\nnotifications_enabled=true\ninclude_apps=["twitch-screen-e2e"]\n')
        path.chmod(0o600)
        environment = {**os.environ, 'PYTHONPATH': '', 'XDG_RUNTIME_DIR': str(private),
                       'DBUS_SESSION_BUS_ADDRESS': 'unix:path=' + bus.path}
        child = None
        status_path = private / 'twitch-screen-client-wave09-denied.json'

        def status():
            return strict_json(status_path.read_bytes()) if status_path.exists() else {}

        try:
            child = await asyncio.create_subprocess_exec(sys.executable, '-m', 'twitch_screen_client',
                'run', '--config', str(path), env=environment,
                stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
            await wait_until(lambda: status().get('capture') == 'monitor_denied' and
                             status().get('cpu_accepted', 0) >= 1, 15)
            for title in ('wave09 denied first', 'wave09 denied second'):
                reply = await asyncio.wait_for(producer.call(Message(destination=NAME, path=PATH, interface=NAME,
                    member='Notify', signature='susssasa{sv}i',
                    body=['twitch-screen-e2e', 0, '', title, 'PRIVATE SYNTHETIC DENIED BODY', [], {}, 0])), 3)
                assert reply.message_type == MessageType.METHOD_RETURN and reply.body == [42]
            await wait_until(lambda: status().get('cpu_accepted', 0) >= 2, 10)
            # Responses and payloads come from real forwarded HTTP, not fixture echoes.
            readback, submitted = await correlated_cpu_readback(
                client, base, gateway, 0, gateway.observations)
            result = check_denied_capture(status(), before, await history(client, base),
                                         readback, submitted, len(delivered))
            assert not any(row['method'] == 'POST' for row in gateway.requests)
            child.send_signal(signal.SIGTERM)
            stdout, stderr = await asyncio.wait_for(child.communicate(), 5)
            (directory / 'companion-denied-shutdown.log').write_bytes(stderr)
            assert child.returncode == 0 and not stderr
            assert b'PRIVATE SYNTHETIC' not in stdout + status_path.read_bytes()
            stopped = status()
            assert all(stopped[key] == 'stopped' for key in ('capture', 'cpu', 'relay'))
            result.update(childExit=child.returncode, terminalStatusVerified=True,
                          noNotificationHttpAttempt=True, privacyPreserved=True)
            previous_pid = child.pid
            request_boundary = len(gateway.records)
            config_bytes = path.read_bytes()
            child = await asyncio.create_subprocess_exec(sys.executable, '-m', 'twitch_screen_client',
                'run', '--config', str(path), env=environment,
                stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
            await wait_until(lambda: status().get('pid') == child.pid and
                             status().get('capture') == 'monitor_denied' and
                             status().get('cpu_accepted', 0) >= 2, 15)
            restart = check_foreground_restart(previous_pid, status())
            readback, submitted = await correlated_cpu_readback(
                client, base, gateway, request_boundary, gateway.observations)
            check_denied_capture(status(), before, await history(client, base),
                                 readback, submitted, len(delivered))
            assert path.read_bytes() == config_bytes
            assert not any(row['method'] == 'POST' for row in gateway.requests)
            # A previous policy denial must not mask a later transport failure.
            # Stop only this private bus; the same installed child keeps sampling.
            denied_bus_pid = bus.process.pid
            bus.process.terminate()
            await asyncio.wait_for(bus.process.communicate(), 3)
            await asyncio.gather(daemon_task, return_exceptions=True)
            await wait_until(lambda: status().get('capture') == 'unavailable', 40)
            failed = status()
            boundary = len(gateway.records)
            await wait_until(lambda: status().get('cpu_accepted', 0) >= failed['cpu_accepted'] + 2, 12)
            readback, submitted = await correlated_cpu_readback(
                client, base, gateway, boundary, gateway.observations)
            outage = check_capture_bus_outage(failed, status(), readback, submitted,
                                             before, await history(client, base))
            assert path.read_bytes() == config_bytes and child.returncode is None
            assert not any(row['method'] == 'POST' for row in gateway.requests)
            outage.update(historicalDenialCleared=True, unchangedConfiguration=True,
                          childPid=child.pid, stoppedBusPid=denied_bus_pid)
            result['busOutageAfterDenial'] = outage
            child.send_signal(signal.SIGINT)
            stdout, stderr = await asyncio.wait_for(child.communicate(), 5)
            (directory / 'companion-denied-restart-shutdown.log').write_bytes(stderr)
            assert child.returncode == 0 and not stderr
            assert b'PRIVATE SYNTHETIC' not in stdout + status_path.read_bytes()
            assert all(status()[key] == 'stopped' for key in ('capture', 'cpu', 'relay'))
            restart.update(childExit=child.returncode, unchangedConfiguration=True,
                           newMeasuredCpuReadbackVerified=True, sigintTerminalStatusVerified=True)
            result['restart'] = restart
            return result
        finally:
            (directory / 'companion-denied-observations.json').write_text(json.dumps({
                'status': status(), 'historyBefore': before, 'ordinaryDeliveries': delivered,
                'observations': gateway.observations, 'requests': gateway.requests,
                'records': gateway.records}, indent=2) + '\n')
            try:
                if child is not None:
                    await reap_child(child)
            finally:
                daemon_task.cancel()
                await asyncio.gather(daemon_task, return_exceptions=True)
                path.unlink(missing_ok=True)


async def pressure_checks(delivery, gateway, client, base, measured, values):
    """Actual admission/readback under a committed but held POST response."""
    payload = {'type': 'message', 'title': 'wave07 held committed response', 'body': '',
               'ttlMs': 8000, 'expiresInMs': 60000}
    gateway.response_held.clear()
    gateway.release_response.clear()
    gateway.next_fault = 'hold_response'
    notification_before = delivery.status['notification_ambiguous']
    cpu_before = delivery.status['cpu_accepted']
    delivery.submit(Card(payload, boottime_ms() + 60000, 128))
    notification_worker = asyncio.create_task(delivery.notifications())
    cpu_worker = asyncio.create_task(delivery.metrics())
    try:
        await asyncio.wait_for(gateway.response_held.wait(), 2)
        # Capture-side admission remains synchronous while HTTP cannot complete.
        deadline = boottime_ms() + 350
        admitted = [delivery.submit(Card({**payload, 'title': f'wave07 expired burst {index}'},
                                         deadline, 128)) for index in range(40)]
        assert sum(admitted) == 32 and delivery.cards.refused == 8
        assert len(delivery.cards.cards) == 32 and delivery.cards.bytes <= 65536
        delivery.sample(measured, values)
        await wait_until(lambda: delivery.status['cpu_accepted'] == cpu_before + 1, 2)
        assert delivery.status['notification_ambiguous'] == notification_before
        readback = (await client.get(base + '/api/v1/cpu')).json()
        assert matches_submitted_cpu(readback, {'sourceId': delivery.config.source_id,
            'host': delivery.config.host, 'freshnessMs': delivery.config.freshness_ms, **values})
        await wait_until(lambda: delivery.status['notification_ambiguous'] == notification_before + 1, 5)
        await wait_until(lambda: delivery.cards.expired == 32)
        assert not delivery.cards.cards and delivery.cards.bytes == 0
        gateway.release_response.set()
        await wait_until(lambda: not gateway.tasks)
        rows = await history(client, base)
        assert sum(row['title'] == payload['title'] for row in rows) == 1
        assert not any(row['title'].startswith('wave07 expired burst') for row in rows)
        posts = [row for row in gateway.records if row['method'] == 'POST' and
                 row['payload']['title'] == payload['title']]
        assert len(posts) == 1 and posts[0]['status'] == 200
        return {'admitted': sum(admitted), 'refused': delivery.cards.refused,
                'expiredWithoutPublication': delivery.cards.expired,
                'cpuAcceptedBeforePostTimeout': True, 'postPublishedOnce': True,
                'pendingBytesAfterDrain': delivery.cards.bytes}
    finally:
        gateway.release_response.set()
        for worker in (notification_worker, cpu_worker):
            worker.cancel()
        await asyncio.wait_for(asyncio.gather(notification_worker, cpu_worker, return_exceptions=True), 1)


async def quota_checks(config, gateway, client, base, measured, values):
    """Exhaust the actual CPU bucket; retry only the replaced latest slot."""
    delivery = Delivery(config, boottime_ms, CardQueue(boottime_ms), initial_status())
    worker = None
    try:
        await delivery.calibrated(delivery.exchanges[1])
        primer = {'sourceId': 'wave07-quota-primer', 'host': 'Synthetic quota primer',
                  'cpuPercent': 1, 'freshnessMs': 10000}
        setup = {'primers': [], 'attempts': []}
        gateway.quota_observations.append(setup)
        responses = await asyncio.gather(*(client.put(base + '/api/v1/cpu', json=primer) for _ in range(48)))
        setup['primers'].extend({'atBoottimeMs': boottime_ms(), 'status': response.status_code,
                                 'response': response.json()} for response in responses)
        assert all(response.status_code in (200, 429) for response in responses)
        rejected = [response for response in responses if response.status_code == 429]
        assert rejected and all(response.json()['code'] == 'rate_limited' and
                                response.headers.get('retry-after') == '1' for response in rejected)
        # Pressure setup is bounded and visible. A token can refill between a
        # primer and the real worker PUT; that accepted setup write is not a
        # quota failure or the final retry gate. Preserve it and re-establish
        # pressure, never silently rerun the suite or change production limits.
        for setup_attempt in range(5):
            before = (await client.get(base + '/api/v1/cpu')).json()
            for _ in range(48):
                response = await client.put(base + '/api/v1/cpu', json=primer)
                value = response.json()
                setup['primers'].append({'atBoottimeMs': boottime_ms(), 'status': response.status_code,
                                         'response': value})
                assert response.status_code in (200, 429)
                if response.status_code == 429:
                    assert value['code'] == 'rate_limited' and response.headers.get('retry-after') == '1'
                    break
                before = value
            else:
                raise AssertionError('CPU quota could not be exhausted within bounded primer')
            start = len(gateway.records)
            delivery.sample(boottime_ms(), {'cpuPercent': 1, 'memoryPercent': 2})
            worker = asyncio.create_task(delivery.metrics())
            await wait_until(lambda: any(row['method'] == 'PUT' for row in gateway.records[start:]), 2)
            attempts = [row for row in gateway.records[start:] if row['method'] == 'PUT']
            setup['attempts'].append({'attempt': setup_attempt, 'before': before, 'records': attempts})
            assert len(attempts) == 1 and attempts[0]['status'] in (200, 429)
            if attempts[0]['status'] == 429:
                break
            await wait_until(lambda: delivery.status['cpu_accepted'] == 1, 2)
            worker.cancel()
            await asyncio.gather(worker, return_exceptions=True)
            worker = None
            # The setup observation completed; start a new test-owned sender
            # with no hidden accepted count in the actual rejection/retry gate.
            await delivery.close()
            delivery = Delivery(config, boottime_ms, CardQueue(boottime_ms), initial_status())
            await delivery.calibrated(delivery.exchanges[1])
        else:
            raise AssertionError('CPU pressure not established within five observed setup attempts')
        preserved = (await client.get(base + '/api/v1/cpu')).json()
        assert preserved['revision'] == before['revision'] and preserved['data'] == before['data']
        # New observations replace the single slot but do not defeat backoff.
        for percent in range(3, 10):
            delivery.sample(boottime_ms(), {'cpuPercent': percent, 'memoryPercent': percent})
        delivery.sample(measured, values)
        newest = delivery.cpu
        await asyncio.sleep(.2)
        assert delivery.cpu is newest
        assert len([row for row in gateway.records[start:] if row['method'] == 'PUT']) == 1
        await wait_until(lambda: delivery.status['cpu_accepted'] == 1, 3)
        attempts = [row for row in gateway.records[start:] if row['method'] == 'PUT']
        assert len(attempts) == 2 and [row['status'] for row in attempts] == [429, 200]
        assert attempts[-1]['payload']['cpuPercent'] == values['cpuPercent']
        accepted = (await client.get(base + '/api/v1/cpu')).json()
        assert matches_submitted_cpu(accepted, {'sourceId': config.source_id, 'host': config.host,
                                              'freshnessMs': config.freshness_ms, **values})
        assert delivery.cpu is None
        return {'actualRateLimitedResponses': len(rejected), 'attemptStatuses': [row['status'] for row in attempts],
                'rejectionPreservedRevisionAndData': True, 'newestOnlyAfterBackoff': True}
    finally:
        if worker is not None:
            worker.cancel()
            await asyncio.wait_for(asyncio.gather(worker, return_exceptions=True), 1)
        await delivery.close()


async def outage_checks(runtime, gateway, client, config, outage_ms=3200):
    """No calibration fallback during a real stopped-JVM outage and recovery."""
    delivery = Delivery(config, boottime_ms, CardQueue(boottime_ms), initial_status())
    workers = []
    try:
        await runtime.stop()
        began = boottime_ms()
        request_start = len(gateway.requests)
        payload = {'type': 'message', 'title': 'wave07 expired during outage', 'body': '',
                   'ttlMs': 8000, 'expiresInMs': 60000}
        for index in range(32):
            assert delivery.submit(Card({**payload, 'title': payload['title'] + str(index)}, began + 800, 128))
        # Explicit aged synthetic observation tests pruning, not host measurement.
        delivery.sample(began - config.freshness_ms + 350, {'cpuPercent': 1})
        workers = [asyncio.create_task(delivery.notifications()), asyncio.create_task(delivery.metrics())]
        await wait_until(lambda: delivery.status['notification_expired'] == 1 and
                         delivery.cards.expired == 31 and delivery.cpu is None, 2)
        await asyncio.sleep(max(0, (began + outage_ms - boottime_ms()) / 1000))
        unavailable = gateway.requests[request_start:]
        assert unavailable and all(row['method'] == 'GET' and row['path'] == '/api/v1/clock' for row in unavailable)
        assert delivery.status['cpu_accepted'] == delivery.status['notification_accepted'] == 0
        assert delivery.cards.bytes == 0 and delivery.calibration is None
        duration = boottime_ms() - began
        epoch = await runtime.start()
        gateway.port = runtime.http_port
        assert await history(client, runtime.base) == []
        assert (await client.get(runtime.base + '/api/v1/cpu')).json()['status'] == 'missing'
        sampler = Sampler(1000)
        sampler.sample()
        await asyncio.sleep(1)
        sample = sampler.sample()
        assert sample is not None
        measured, values = sample
        delivery.sample(measured, values)
        title = 'wave07 fresh after actual outage'
        assert delivery.submit(Card({**payload, 'title': title}, boottime_ms() + 60000, 128))
        await wait_until(lambda: delivery.status['cpu_accepted'] == delivery.status['notification_accepted'] == 1, 10)
        readback = (await client.get(runtime.base + '/api/v1/cpu')).json()
        assert readback['relayEpoch'] == epoch and matches_submitted_cpu(readback,
            {'sourceId': config.source_id, 'host': config.host, 'freshnessMs': config.freshness_ms, **values})
        rows = await history(client, runtime.base)
        assert len(rows) == 1 and rows[0]['title'] == title and rows[0]['relayEpoch'] == epoch
        return {'stoppedRelayMs': duration, 'clockOnlyDuringOutage': True, 'expiredCards': 32,
                'expiredCpuPruned': True, 'freshMeasuredCpuAndCardReadback': True,
                'noStalePublication': True}
    finally:
        for worker in workers:
            worker.cancel()
        await asyncio.wait_for(asyncio.gather(*workers, return_exceptions=True), 1)
        await delivery.close()


def check_recovery_calibration(responses, epoch, cached_usable):
    """Require rejection of usable old calibration or refresh before any write."""
    if cached_usable:
        assert any(row['status'] == 409 for row in responses)
        return 'old_epoch_rejected'
    assert responses and responses[0]['method'] == 'GET' and responses[0]['path'] == '/api/v1/clock'
    assert responses[0]['status'] == 200
    writes = [row for row in responses if row['method'] in ('PUT', 'POST')]
    assert writes and all(row['payload']['deadline']['relayEpoch'] == epoch for row in writes)
    assert not any(row['status'] == 409 for row in responses)
    return 'expired_calibration_refreshed'


async def cached_outage_checks(runtime, gateway, client, config, outage_ms):
    """Retry a real outage safely with a previously valid shared calibration."""
    delivery = Delivery(config, boottime_ms, CardQueue(boottime_ms), initial_status())
    workers = []
    try:
        calibration = await delivery.calibrated(delivery.exchanges[0])
        await runtime.stop()
        began = boottime_ms()
        request_start = len(gateway.requests)
        record_start = len(gateway.records)
        payload = {'type': 'message', 'title': 'repair07 cached outage expired', 'body': '',
                   'ttlMs': 8000, 'expiresInMs': 60000}
        for index in range(32):
            assert delivery.submit(Card({**payload, 'title': payload['title'] + str(index)}, began + 800, 128))
        delivery.sample(began - config.freshness_ms + 350, {'cpuPercent': 1})
        workers = [asyncio.create_task(delivery.notifications()), asyncio.create_task(delivery.metrics())]
        try:
            await wait_until(lambda: int(delivery.status['notification_ambiguous']) > 0 and
                             int(delivery.status['notification_ambiguous']) +
                             int(delivery.status['notification_expired']) + delivery.cards.expired == 32 and
                             delivery.cpu is None, 5)
        except TimeoutError:
            print(json.dumps({'cachedOutageDiagnostic': delivery.status, 'queuedExpired': delivery.cards.expired,
                              'queuedCount': len(delivery.cards.cards), 'queuedBytes': delivery.cards.bytes,
                              'requests': gateway.requests[request_start:],
                              'workerErrors': [str(worker.exception()) for worker in workers if worker.done()]}), flush=True)
            raise
        attempts = gateway.requests[request_start:]
        posts = [row for row in attempts if row['method'] == 'POST']
        assert len(posts) == delivery.status['notification_ambiguous']
        assert len({row['payload']['title'] for row in posts}) == len(posts)
        assert all(row['payload']['title'].startswith(payload['title']) for row in posts)
        assert any(row['method'] == 'PUT' for row in attempts)
        assert delivery.status['cpu_accepted'] == delivery.status['notification_accepted'] == 0
        assert not gateway.records[record_start:]  # No upstream response or invented acceptance.
        assert delivery.cards.bytes == 0 and not delivery.cards.cards
        assert delivery.calibration is calibration
        expired_cards = delivery.cards.expired + int(delivery.status['notification_expired'])
        ambiguous_cards = delivery.status['notification_ambiguous']
        await asyncio.sleep(max(0, (began + outage_ms - boottime_ms()) / 1000))
        assert gateway.requests[request_start:] == attempts  # Idle workers do not poll relay health.
        duration = boottime_ms() - began
        epoch = await runtime.start()
        gateway.port = runtime.http_port
        assert epoch != calibration.epoch
        assert await history(client, runtime.base) == []
        assert (await client.get(runtime.base + '/api/v1/cpu')).json()['status'] == 'missing'
        sampler = Sampler(1000)
        sampler.sample()
        await asyncio.sleep(1)
        sample = sampler.sample()
        assert sample is not None
        measured, values = sample
        title = 'repair07 fresh after cached outage'
        cached_usable = calibration.usable(boottime_ms(), delivery.active_clock())
        delivery.sample(measured, values)
        assert delivery.submit(Card({**payload, 'title': title}, boottime_ms() + 60000, 128))
        await wait_until(lambda: delivery.status['cpu_accepted'] == delivery.status['notification_accepted'] == 1, 10)
        responses = gateway.records[record_start:]
        calibration_path = check_recovery_calibration(responses, epoch, cached_usable)
        posts = [row for row in responses if row['method'] == 'POST' and row['status'] == 200]
        assert len(posts) == 1 and posts[0]['payload']['title'] == title
        readback = (await client.get(runtime.base + '/api/v1/cpu')).json()
        assert readback['relayEpoch'] == epoch and matches_submitted_cpu(readback,
            {'sourceId': config.source_id, 'host': config.host, 'freshnessMs': config.freshness_ms, **values})
        rows = await history(client, runtime.base)
        assert len(rows) == 1 and rows[0]['title'] == title and rows[0]['relayEpoch'] == epoch
        return {'stoppedRelayMs': duration, 'cachedCalibrationAttemptedWrites': True,
                'uncertainGatewayPostDroppedWithoutRetry': True, 'expiredCards': expired_cards,
                'ambiguousCards': ambiguous_cards, 'expiredCpuPruned': True, 'idleWorkersDidNotPoll': True,
                'oldEpochRejectedBeforeRecalibration': calibration_path == 'old_epoch_rejected',
                'expiredCalibrationRefreshedBeforeWrites': calibration_path == 'expired_calibration_refreshed',
                'calibrationRecoveryPath': calibration_path, 'freshMeasuredCpuAndCardReadback': True,
                'noStalePublication': True}
    finally:
        for worker in workers:
            worker.cancel()
        await asyncio.wait_for(asyncio.gather(*workers, return_exceptions=True), 1)
        await delivery.close()


async def exercise(assembly, evidence, tests, outage_ms=3200, same_ports=False):
    report = {'status': 'blocked', 'testedLayer': 'installed companion + actual assembled relay + fault gateway/private bus',
              'checks': [],
              'productAccepted': False, 'allSoftwareVerified': False, 'desktopVerified': False,
              'physicalVerified': False, 'productionVerified': False, 'blockers': []}
    evidence.mkdir(parents=True, exist_ok=True, mode=0o700)
    with tempfile.TemporaryDirectory(dir=os.environ['TMPDIR'], prefix='companion-recovery-') as scratch:
        directory = Path(scratch)
        snapshot = directory / 'assembly.jar'
        shutil.copyfile(assembly, snapshot)
        with snapshot.open('rb') as artifact:
            report['assemblySha256'] = hashlib.file_digest(artifact, 'sha256').hexdigest()
        runtime = Runtime(snapshot, directory, same_ports=same_ports)
        report['restartPortPolicy'] = 'same' if same_ports else 'fresh'
        delivery = None
        gateway = None
        try:
            epoch = await runtime.start()
            async with FaultGateway(runtime.http_port) as gateway, httpx.AsyncClient(trust_env=False, timeout=3) as client:
                config = parse({'relay_url': gateway.url, 'source_id': 'repair06-recovery', 'host': 'Synthetic host',
                                'sample_interval_ms': 1000})
                delivery = Delivery(config, boottime_ms, CardQueue(boottime_ms), initial_status())
                card = {'type': 'message', 'title': 'repair06 delayed expired', 'body': '', 'ttlMs': 8000, 'expiresInMs': 60000}
                before = await history(client, runtime.base)
                await delivery.calibrated(delivery.exchanges[0])
                gateway.next_fault = 'delay'
                outcome = await delivery.upload(delivery.exchanges[0], 'POST', '/api/v1/notifications', card, boottime_ms() + 250)
                assert outcome == 'expired' and await history(client, runtime.base) == before
                assert gateway.records[-1]['status'] == 410
                report['checks'].append('actual delayed receipt: 410 and exact unchanged history')
                gateway.next_fault = 'lose_response'
                for title in ('repair06 lost committed response', 'repair06 next FIFO'):
                    delivery.submit(Card({**card, 'title': title}, boottime_ms() + 60000, 100))
                worker = asyncio.create_task(delivery.notifications())
                try:
                    await wait_until(lambda: delivery.status['notification_accepted'] == 1 and
                                     delivery.status['notification_ambiguous'] == 1)
                    await asyncio.sleep(1.3)
                    rows = await history(client, runtime.base)
                    posts = [row for row in gateway.records if row['method'] == 'POST' and row['status'] == 200]
                    assert [row['payload']['title'] for row in posts] == ['repair06 lost committed response', 'repair06 next FIFO']
                    assert sum(row['title'] == 'repair06 lost committed response' for row in rows) == 1
                    assert sum(row['title'] == 'repair06 next FIFO' for row in rows) == 1
                    report['checks'].append('actual committed response loss: one publication, no resend, next FIFO accepted')
                finally:
                    worker.cancel()
                    await asyncio.gather(worker, return_exceptions=True)
                old = await delivery.calibrated(delivery.exchanges[0])
                restart_before = await prepare_restart_state(client, runtime.base)
                report['restartTraffic'] = {'keepaliveAndCloseIngressExercised': True,
                                            'freshCpuAndWeatherReadbackVerified': True}
                await runtime.stop()
                next_epoch = await runtime.start()
                gateway.port = runtime.http_port
                assert next_epoch != epoch
                display_after = await client.get(runtime.base + '/api/v1/display')
                weather_after = await client.get(runtime.base + '/api/v1/weather')
                cpu_after = await client.get(runtime.base + '/api/v1/cpu')
                for response in (display_after, weather_after, cpu_after):
                    response.raise_for_status()
                report['restartState'] = check_restart_state(
                    restart_before, display_after.json(), next_epoch, weather_after.json(), cpu_after.json(),
                    await history(client, runtime.base))
                report['checks'].append('actual relay restart: persisted display intent, new epoch, RAM telemetry and history reset')
                await wait_until(lambda: not gateway.tasks)
                assert delivery.calibration is old
                card['title'] = 'repair06 after restart'
                deadline = boottime_ms() + 60000
                assert await delivery.upload(delivery.exchanges[0], 'POST', '/api/v1/notifications', card, deadline) == 'retry'
                assert gateway.records[-1]['status'] == 409 and delivery.calibration is None
                assert await history(client, runtime.base) == []
                assert await delivery.upload(delivery.exchanges[0], 'POST', '/api/v1/notifications', card, deadline) == 'accepted'
                assert delivery.status['relay'] == 'available'
                rows = await history(client, runtime.base)
                assert len(rows) == 1 and rows[0]['relayEpoch'] == next_epoch and rows[0]['title'] == card['title']
                report['checks'].append('actual relay restart: old epoch 409 without mutation, recalibrated original-age card accepted once')
                sampler = Sampler(1000)
                sampler.sample()
                await asyncio.sleep(1)
                sample = sampler.sample()
                assert sample is not None
                measured, values = sample
                payload = {'sourceId': config.source_id, 'host': config.host, 'freshnessMs': config.freshness_ms, **values}
                assert await delivery.upload(delivery.exchanges[1], 'PUT', '/api/v1/cpu', payload,
                                             measured + config.freshness_ms) == 'accepted'
                accepted = (await client.get(runtime.base + '/api/v1/cpu')).json()
                assert accepted['relayEpoch'] == next_epoch and matches_submitted_cpu(accepted, payload)
                report['checks'].append('actual measured CPU/memory accepted with exact canonical readback after restart')
                gateway.next_fault = 'delay'
                assert await delivery.upload(delivery.exchanges[1], 'PUT', '/api/v1/cpu', payload,
                                             boottime_ms() + 250) == 'expired'
                preserved = (await client.get(runtime.base + '/api/v1/cpu')).json()
                assert preserved['revision'] == accepted['revision'] and preserved['data'] == accepted['data']
                assert gateway.records[-1]['status'] == 410
                report['checks'].append('actual delayed CPU receipt: 410 preserves exact accepted revision/data')
                report['pressure'] = await pressure_checks(delivery, gateway, client, runtime.base, measured, values)
                report['checks'].append('actual held POST: independent measured CPU, bounded 32-entry burst, expiry without publication and no ambiguous resend')
                report['quota'] = await quota_checks(config, gateway, client, runtime.base, measured, values)
                report['checks'].append('actual CPU quota: canonical 429 preserves state, backoff accepts only newest original-age observation')
                await delivery.close()
                delivery = None
                report['outage'] = await outage_checks(runtime, gateway, client, config, outage_ms)
                report['checks'].append('actual stopped relay: clock-only failed exchanges, stale work pruned, fresh measured CPU/card recovered without stale publication')
                report['cachedOutage'] = await cached_outage_checks(runtime, gateway, client, config, outage_ms)
                report['checks'].append('actual cached-calibration outage: uncertain gateway POST dropped once, stale work pruned, idle workers do not poll, original calibration rejected or expired before fresh CPU/card recovery')
                report['capture'] = await synthetic_child(config, gateway.url, client, directory, tests, runtime, gateway)
                assert report['capture']['ordinaryDaemonDeliveries'] == 4
                report['checks'].append('installed foreground child: private Notify and owner restart reach actual relay, ordinary delivery and measured CPU preserved')
                report['checks'].append('installed surviving capture child: private bus outage preserves measured CPU acceptance, same-endpoint bus restart resumes ordinary delivery without configuration or PID changes')
                report['checks'].append('installed surviving capture child: actual relay restart preserves PID/configuration, new-epoch measured CPU and fresh card without old notification replay')
                report['deniedCapture'] = await denied_capture_child(runtime.base, client, directory, tests)
                report['checks'].append('installed denied-monitor child: ordinary synthetic delivery, no notification HTTP attempt, continued measured CPU with exact submitted-value readback')
                report['checks'].append('installed historical-denial child: later private bus loss reports unavailable, same-PID measured CPU continues without notification attempts')
                report['status'] = 'passed'
        except Exception as error:
            report['blockers'].append(type(error).__name__ + ': actual recovery assertion failed; inspect private logs')
            # Preserve the initiating traceback, but finish owned cleanup and
            # record scratch removal before returning the blocked verdict.
            traceback.print_exc()
        finally:
            if gateway is not None:
                (evidence / 'gateway-observations.json').write_text(json.dumps({
                    'requests': gateway.requests, 'records': gateway.records,
                    'correlations': gateway.observations, 'quotaSetup': gateway.quota_observations}, indent=2) + '\n')
            if delivery is not None:
                await delivery.close()
            await runtime.stop()
            report['relayStops'] = runtime.stops
            report['relayGracefulStopsVerified'] = bool(runtime.stops) and all(
                not row['forcedKill'] and row['exitCode'] == 143 for row in runtime.stops)
            report['childReapedAndListenersClosed'] = True
            for log in (*directory.glob('relay-*.log'), *directory.glob('companion-*.log'),
                        *directory.glob('companion-*-observations.json')):
                (evidence / log.name).write_bytes(log.read_bytes())
            (evidence / 'recovery.json').write_text(json.dumps(report, indent=2) + '\n')
    report['scratchRemoved'] = not directory.exists()
    (evidence / 'recovery.json').write_text(json.dumps(report, indent=2) + '\n')
    return report


def outage_duration(value):
    try:
        duration = int(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError('outage must be 3200..60000 ms') from error
    if not 3200 <= duration <= 60000:
        raise argparse.ArgumentTypeError('outage must be 3200..60000 ms')
    return duration


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--assembly', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--tests', type=Path, default=Path(__file__).resolve().parents[1] / 'tests')
    parser.add_argument('--outage-ms', type=outage_duration, default=3200,
                        help='each stopped-JVM interval, 3200..60000 ms (default: 3200)')
    parser.add_argument('--same-ports', action='store_true',
                        help='restart both owned relay listeners on their original ports; bind failures remain failures')
    parser.add_argument('--test-owned', action='store_true')
    args = parser.parse_args()
    if not args.test_owned:
        parser.error('--test-owned is required; never target shared resources')
    os.umask(0o077)
    if (Path(__file__).resolve().parents[2] / '.agent-workflow/STOP').exists():
        parser.error('workflow STOP is present; no resources started')
    result = asyncio.run(exercise(args.assembly.resolve(), args.evidence.resolve(), args.tests.resolve(),
                                  args.outage_ms, args.same_ports))
    print(json.dumps(result, indent=2))
    return 0 if result['status'] == 'passed' else 2


if __name__ == '__main__':
    raise SystemExit(main())
