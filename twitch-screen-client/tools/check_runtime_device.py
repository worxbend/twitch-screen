"""Installed foreground companion → owned Compose → native DESK application.

Only private synthetic Notify traffic and measured /proc CPU/memory are used.
Requires an immutable local relay image and a native runtime-peer binary. Starts
an isolated Compose project, never targets existing deployments, boards, desktop
buses, services or providers. Native LVGL observations are not LCD evidence.
"""
import argparse
import asyncio
import contextlib
from decimal import Decimal, ROUND_HALF_UP
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import signal
import struct
import sys
import tempfile
import traceback

import httpx
from twitch_screen_client.delivery import strict_json
from twitch_screen_client.responses import matches_submitted_cpu
from check_runtime_monitoring import synthetic_capture
from check_runtime_recovery import FaultGateway, free_port, history, reap_child, wait_until

ROOT = Path(__file__).resolve().parents[2]


async def read_observations(stream, records, changed):
    """Bound each native witness before decoding and wake waiters on failure/EOF."""
    try:
        while raw := await stream.readline():
            if len(raw) > 8192 or len(records) >= 10000:
                raise ValueError('native observation exceeds fixture bound')
            row = strict_json(raw)
            if not isinstance(row, dict) or not isinstance(row.get('event'), str):
                raise ValueError('native observation must be an event object')
            records.append(row)
            changed.set()
    finally:
        changed.set()


def verify_cpu_application(proof):
    """Require numeric adapter evidence, not just a matching visible revision."""
    accepted, payload, wire, view, telemetry = (proof[key] for key in
        ('accepted', 'payload', 'wire', 'view', 'telemetry'))
    epoch = accepted['relayEpoch']
    assert isinstance(epoch, str) and re.fullmatch('[0-9a-f]{16}', epoch) and epoch != '0000000000000000'
    assert epoch == payload['deadline']['relayEpoch'] == wire['epoch'] == view['epoch'] == telemetry['epoch']
    assert accepted['revision'] == wire['revision'] == view['cpuRevision'] == telemetry['cpuRevision']
    assert wire['connection'] == view['connection'] == telemetry['connection']
    assert view['displayRevision'] == telemetry['displayRevision']
    assert telemetry['event'] == 'application_telemetry' and view['event'] == 'application_view'
    assert type(telemetry['atMs']) is int and telemetry['atMs'] >= 0
    assert wire['status'] == 1
    # This measured fixture requires real memory and supplies no temperature.
    assert type(wire['presence']) is int and wire['presence'] == 1
    assert type(telemetry['cpuPresence']) is int and telemetry['cpuPresence'] == wire['presence']
    for field, wire_field, adapter_field in (('cpuPercent', 'cpu', 'cpuBasisPoints'),
                                            ('memoryPercent', 'memory', 'memoryBasisPoints')):
        expected = int((Decimal(str(payload[field])) * 100).quantize(Decimal('1'), rounding=ROUND_HALF_UP))
        canonical = Decimal(str(accepted['data'][field])) * 100
        assert canonical == expected and 0 <= expected <= 10000
        assert type(wire[wire_field]) is int and type(telemetry[adapter_field]) is int
        assert wire[wire_field] == telemetry[adapter_field] == expected
    assert 0 < wire['deadline'] <= payload['deadline']['monotonicMs']
    assert view['baseScreen'] == 4 and view['cpuVisible'] is True
    assert view['weatherVisible'] is False and view['covered'] is False
    return True


def cpu_application_proof(accepted, payload, wire, records):
    """Join the adjacent view/numeric records emitted by one native view call.

    Connection IDs and revisions may repeat across relay epochs. Require the
    exact epoch before joining adjacent display/numeric observations.
    """
    for index in range(len(records) - 1):
        view, telemetry = records[index:index + 2]
        if (view['event'] != 'application_view' or telemetry['event'] != 'application_telemetry'
                or view.get('epoch') != wire['epoch']
                or view['cpuRevision'] != wire['revision'] or view['connection'] != wire['connection']
                or not view['cpuVisible'] or view['covered']):
            continue
        proof = {'accepted': accepted, 'payload': payload, 'wire': wire, 'view': view,
                 'telemetry': telemetry, 'observationIndex': index}
        verify_cpu_application(proof)
        return proof
    return None


def verify_cards(proof):
    starts = [row for row in proof['records'] if row['event'] == 'started']
    assert len(starts) == len(proof['cards']) == 2
    for card, start in zip(proof['cards'], starts):
        assert start['seq'] == card['seq'] and start['epoch'] == card['relayEpoch']
        assert start['origin'] == 0
        phases = [row['phase'] for row in proof['records'] if row['event'] == 'card_lifecycle'
                  and row['seq'] == card['seq'] and row['epoch'] == card['relayEpoch']]
        assert phases == [2, 3, 0]
    return True


def verify_publications(proof):
    """Correlate real capture POST/acceptance/history with unchanged DESK deadlines."""
    posts = [row for row in proof['responses'] if row['method'] == 'POST']
    assert len(posts) == len(proof['cards']) == 2
    events = []
    for frame in proof['frames']:
        raw = bytes.fromhex(frame['hex'])
        if frame['direction'] == 'relay' and raw[3] == 0x2b:
            assert len(raw) == 204
            payload = raw[8:]
            events.append({'seq': struct.unpack_from('<I', payload)[0],
                'epoch': f'{struct.unpack_from("<Q", payload, 168)[0]:016x}',
                'deadline': struct.unpack_from('<Q', payload, 184)[0], 'origin': payload[192]})
    assert len(events) == 2
    for card in proof['cards']:
        rows = [row for row in posts if row['response']['id'] == card['id']]
        assert len(rows) == 1 and rows[0]['status'] == 200
        row = rows[0]
        assert row['payload']['title'] == row['response']['title'] == card['title']
        assert row['payload']['body'] == row['response']['body'] == card['body'] == ''
        assert row['response']['relayEpoch'] == card['relayEpoch'] == row['payload']['deadline']['relayEpoch']
        assert row['response']['seq'] == card['seq'] and card['origin'] == 'external'
        matching = [event for event in events if event['seq'] == card['seq'] and event['epoch'] == card['relayEpoch']]
        assert len(matching) == 1 and matching[0]['origin'] == 0
        assert 0 < matching[0]['deadline'] <= row['payload']['deadline']['monotonicMs']
    assert [row['fault'] for row in posts] == [None, proof.get('responseFault', 'lose_response')]
    if proof.get('responseFault') == 'corrupt_response':
        assert posts[1]['deliveredBodyHex'] == b'{broken'.hex()
        assert re.fullmatch('[0-9a-f]{64}', posts[1]['upstreamResponseSha256'])
    return True


class WireGateway:
    """Transparent bounded frame witness; forwards unchanged actual TCP bytes."""
    def __init__(self, upstream):
        self.upstream = upstream
        self.frames = []
        self.changed = asyncio.Event()
        self.tasks = set()
        self.writers = set()

    async def __aenter__(self):
        self.server = await asyncio.start_server(self.handle, '127.0.0.1', 0)
        self.port = self.server.sockets[0].getsockname()[1]
        return self

    async def handle(self, reader, writer):
        task = asyncio.current_task()
        self.tasks.add(task)
        self.writers.add(writer)
        upstream = None
        pumps = []
        try:
            other, upstream = await asyncio.open_connection('127.0.0.1', self.upstream)
            self.writers.add(upstream)

            async def pump(source, destination, direction):
                pending = bytearray()
                while True:
                    chunk = await source.read(1024)
                    if not chunk:
                        return
                    destination.write(chunk)
                    await destination.drain()
                    pending.extend(chunk)
                    while len(pending) >= 8:
                        size = struct.unpack_from('<H', pending, 4)[0]
                        assert size <= 248 and pending[:2] == b'\xa7S' and pending[2] == 3
                        assert pending[7] == (0xff ^ (sum((i + 1) * pending[i] for i in range(7)) & 0xff))
                        if len(pending) < 8 + size:
                            break
                        raw = bytes(pending[:8 + size])
                        del pending[:8 + size]
                        assert len(self.frames) < 10000, 'finite witness bound exceeded'
                        self.frames.append({'direction': direction, 'hex': raw.hex()})
                        self.changed.set()
                    assert len(pending) <= 255

            pumps = [asyncio.create_task(pump(reader, upstream, 'device')),
                     asyncio.create_task(pump(other, writer, 'relay'))]
            done, _ = await asyncio.wait(pumps, return_when=asyncio.FIRST_COMPLETED)
            for completed in done:
                completed.result()
        finally:
            for pump in pumps:
                pump.cancel()
            await asyncio.gather(*pumps, return_exceptions=True)
            for connection in (upstream, writer):
                if connection is not None:
                    connection.close()
                    with contextlib.suppress(OSError):
                        await connection.wait_closed()
                    self.writers.discard(connection)
            self.tasks.discard(task)

    def cpus(self):
        result = []
        for frame in self.frames:
            raw = bytes.fromhex(frame['hex'])
            if frame['direction'] != 'relay' or raw[3] != 0x29:
                continue
            assert len(raw) == 76
            payload = raw[8:]
            epoch, connection, revision, deadline, status, presence, cpu, memory = struct.unpack_from('<QQIQBBHH', payload)
            result.append({'epoch': f'{epoch:016x}', 'connection': f'{connection:016x}',
                           'revision': revision, 'deadline': deadline, 'status': status,
                           'presence': presence, 'cpu': cpu, 'memory': memory})
        return result

    async def __aexit__(self, *exc):
        self.server.close()
        await self.server.wait_closed()
        for writer in list(self.writers):
            writer.close()
        for task in list(self.tasks):
            task.cancel()
        await asyncio.gather(*list(self.tasks), return_exceptions=True)
        assert not self.tasks and not self.writers


class ComposeRuntime:
    """Actual checked-in Compose service, immutable image, fresh owned state volume."""
    def __init__(self, image, directory):
        self.image, self.directory = image, directory
        self.project = 'companion-device-' + secrets.token_hex(6)
        self.http_port, self.device_port = free_port(), free_port()
        while self.device_port == self.http_port:
            self.device_port = free_port()
        self.base = f'http://127.0.0.1:{self.http_port}'
        self.env = {key: value for key, value in os.environ.items() if not key.startswith('RELAY_')}
        self.env.update(RELAY_BIND_IP='127.0.0.1', RELAY_PUBLISHED_HTTP_PORT=str(self.http_port),
                        RELAY_PUBLISHED_DEVICE_PORT=str(self.device_port), RELAY_TWITCH_MODE='disabled',
                        RELAY_TWITCH_CHANNEL='', RELAY_CHAT_NOTIFICATIONS='hide',
                        RELAY_HTTP_ALLOWED_HOSTS=f'127.0.0.1:8080,127.0.0.1:{self.http_port}',
                        RELAY_HTTP_ALLOWED_ORIGINS=self.base)
        override = directory / 'compose-override.json'
        override.write_text(json.dumps({'services': {'relay': {'image': image, 'restart': 'no',
            'environment': {'OTEL_TRACES_EXPORTER': 'none', 'OTEL_METRICS_EXPORTER': 'none',
                            'OTEL_LOGS_EXPORTER': 'none'}}}}))
        self.command = [sys.executable, str(ROOT / 'twitch-screen-relay/tools/compose.py'),
                        '--env-file', '/dev/null', '-p', self.project,
                        '-f', str(ROOT / 'twitch-screen-relay/compose.yaml'), '-f', str(override)]
        self.container = None

    async def command_output(self, *arguments):
        child = await asyncio.create_subprocess_exec(*arguments, env=self.env,
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
        try:
            stdout, stderr = await asyncio.wait_for(child.communicate(), 90)
        except BaseException:
            await reap_child(child)
            raise
        with (self.directory / 'compose.log').open('ab') as log:
            log.write(stdout + stderr)
        assert child.returncode == 0, f'owned command exited {child.returncode}'
        return stdout.decode().strip()

    async def start(self):
        assert self.image.startswith('sha256:') and len(self.image) == 71
        await self.command_output(*self.command, 'up', '--no-build', '--pull', 'never', '-d', 'relay')
        self.container = await self.command_output(*self.command, 'ps', '-q', 'relay')
        image = await self.command_output('docker', 'inspect', self.container, '--format', '{{.Image}}')
        assert image == self.image
        mounts = json.loads(await self.command_output('docker', 'inspect', self.container, '--format', '{{json .Mounts}}'))
        assert len(mounts) == 1 and mounts[0]['Type'] == 'volume'
        assert mounts[0]['Name'] == self.project + '_relay-data'
        assert mounts[0]['Destination'] == '/home/relay/data'
        self.volume = mounts[0]['Name']
        async with httpx.AsyncClient(trust_env=False, timeout=.5) as client, asyncio.timeout(45):
            while True:
                try:
                    reply = await client.get(self.base + '/api/v1/clock')
                    if reply.status_code == 200:
                        return
                except httpx.HTTPError:
                    pass
                await asyncio.sleep(.1)

    async def stop(self):
        if self.container:
            logs = await self.command_output('docker', 'logs', self.container)
            (self.directory / 'relay.log').write_text(logs)
        await self.command_output(*self.command, 'down', '--timeout', '15')
        remaining = await self.command_output('docker', 'ps', '-a', '-q', '--filter', f'label=com.docker.compose.project={self.project}')
        assert not remaining
        volume = getattr(self, 'volume', None)
        if volume:
            retained = await self.command_output('docker', 'volume', 'inspect', volume, '--format', '{{.Name}}')
            assert retained == volume
        return {'project': self.project, 'container': self.container, 'containerRemoved': True,
                'stateVolume': volume, 'stateVolumeRetained': bool(volume),
                'httpPort': self.http_port, 'devicePort': self.device_port}


async def scenario(runtime, binary, directory, proof, capture, browser=None):
    records = []
    proof['records'] = records
    async with FaultGateway(runtime.http_port) as http, WireGateway(runtime.device_port) as wire, httpx.AsyncClient(trust_env=False, timeout=3) as client:
        proof['gatewayPorts'] = [http.server.sockets[0].getsockname()[1], wire.port]
        environment = {key: value for key, value in os.environ.items() if not key.startswith('FIRMWARE_PEER_')}
        environment.update(FIRMWARE_PEER_PORT=str(wire.port), FIRMWARE_PEER_SECONDS='20' if capture['denied'] else '55',
                           FIRMWARE_PEER_PROFILE_READY='1', FIRMWARE_PEER_REQUIRE_DESK='1',
                           FIRMWARE_PEER_MAX_WRITE_BYTES='13')
        peer = await asyncio.create_subprocess_exec(str(binary), env=environment,
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE, limit=8192)
        proof['peerPid'] = peer.pid
        changed = asyncio.Event()

        async def observation(predicate, timeout=15):
            async with asyncio.timeout(timeout):
                while True:
                    changed.clear()
                    if reader.done():
                        reader.result()  # Surface malformed/overflow evidence immediately.
                    for row in records:
                        if predicate(row):
                            return row
                    if reader.done():
                        raise EOFError('native observer ended before required evidence')
                    await changed.wait()

        reader = asyncio.create_task(read_observations(peer.stdout, records, changed))
        private = directory / 'client'
        private.mkdir(mode=0o700)
        config = private / 'config.toml'
        config.write_text(f'relay_url="{http.url}"\nsource_id="wave12-device"\nhost="Measured synthetic test host"\n'
                          'sample_interval_ms=1000\nnotifications_enabled=true\ninclude_apps=["twitch-screen-e2e"]\n')
        config.chmod(0o600)
        original = config.read_bytes()
        status_path = private / 'twitch-screen-client-wave12-device.json'
        env = {**os.environ, 'PYTHONPATH': '', 'XDG_RUNTIME_DIR': str(private),
               'DBUS_SESSION_BUS_ADDRESS': capture['address']}
        child = await asyncio.create_subprocess_exec(sys.executable, '-m', 'twitch_screen_client', 'run', '--config', str(config),
            env=env, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
        proof['childPid'] = child.pid

        def status():
            return strict_json(status_path.read_bytes()) if status_path.exists() else {}

        async def patch(path, payload):
            if browser is not None and path == '/api/v1/display':
                await browser.display(payload)
                response = await client.get(runtime.base + path)
            else:
                response = await client.patch(runtime.base + path, json=payload)
            response.raise_for_status()
            value = response.json()
            proof.setdefault('mutations', []).append({'path': path, 'payload': payload, 'response': value})
            return value

        async def measured_view(boundary=0):
            async with asyncio.timeout(8):
                while True:
                    wire.changed.clear()
                    changed.clear()
                    if reader.done():
                        reader.result()
                    for item in http.records[boundary:]:
                        if item['method'] != 'PUT' or item['status'] != 200:
                            continue
                        accepted, payload = item['response'], item['payload']
                        assert matches_submitted_cpu(accepted, payload)
                        for snapshot in wire.cpus():
                            if snapshot['revision'] != accepted['revision'] or snapshot['epoch'] != accepted['relayEpoch'] or snapshot['status'] != 1:
                                continue
                            result = cpu_application_proof(accepted, payload, snapshot, records)
                            if result is not None:
                                ready = [row for row in records[:result['observationIndex']]
                                         if row['event'] == 'desk_ready' and row['epoch'] == snapshot['epoch']
                                         and row['connection'] == snapshot['connection']]
                                assert ready, 'application lacks prior exact epoch/connection readiness'
                                result['ready'] = ready[-1]
                                if browser is not None and not await browser.has_cpu(accepted):
                                    # Native and browser updates independently coalesce. Join an
                                    # actual shared revision; never substitute nearby numeric values.
                                    continue
                                proof.setdefault('cpuApplications', []).append(result)
                                return result
                    if reader.done():
                        raise EOFError('native observer ended before measured application')
                    # Wake on actual application/TCP/HTTP evidence, not relay API polling.
                    waits = [asyncio.create_task(event.wait()) for event in (changed, wire.changed, http.records_changed)]
                    http.records_changed.clear()
                    try:
                        await asyncio.wait(waits, return_when=asyncio.FIRST_COMPLETED)
                    finally:
                        for waiter in waits:
                            waiter.cancel()
                        await asyncio.gather(*waits, return_exceptions=True)

        try:
            ready = await observation(lambda row: row['event'] == 'desk_ready')
            await wait_until(lambda: status().get('capture') == ('monitor_denied' if capture['denied'] else 'available')
                             and status().get('cpu_accepted', 0) >= 1, 15)
            control = await patch('/api/v1/display', {'preferredDashboard': 'cpu', 'twitchDisplayEnabled': False})
            await measured_view()
            if browser is not None:
                boundary = len(http.records)
                await browser.recover_feed()
                await measured_view(boundary)
            if capture['denied']:
                proof['ordinaryReplies'] = []
                for index, mutation in enumerate(({'mode': 'simulated', 'channel': 'demo'}, {'mode': 'disabled'})):
                    boundary = len(http.records)
                    await patch('/api/v1/twitch/monitoring', mutation)
                    proof['ordinaryReplies'].append(await capture['notify'](f'wave12 denied device {index}'))
                    await measured_view(boundary)
                    assert status()['capture'] == 'monitor_denied' and status()['captured'] == 0
                    assert child.returncode is None and config.read_bytes() == original
                assert not any(row['method'] == 'POST' for row in http.requests)
                assert status()['notification_accepted'] == status()['notification_ambiguous'] == 0
                child.send_signal(signal.SIGTERM)
                stdout, stderr = await asyncio.wait_for(child.communicate(), 5)
                assert child.returncode == 0 and not stderr
                proof['stopped'] = status()
                assert all(proof['stopped'][key] == 'stopped' for key in ('capture', 'cpu', 'relay'))
                assert proof['stopped']['pendingNotifications'] == proof['stopped']['pendingNotificationBytes'] == 0
                assert proof['stopped']['captured'] == proof['stopped']['notification_accepted'] == proof['stopped']['notification_ambiguous'] == 0
                (directory / 'companion.log').write_bytes(stdout + stderr)
                await asyncio.wait_for(peer.wait(), 30)
                await reader
                assert peer.returncode == 0 and not any(row['event'] == 'started' for row in records)
                reports = [row for row in records if row['event'] == 'device_report_written']
                assert reports and all(row['phase'] == row['pending'] == row['enqueuedSeq'] == 0 and row['grantTotal'] == 8 for row in reports)
                proof['peerExitCode'] = peer.returncode
                proof['everyReportCreditConserved'] = True
                proof['deniedMonitorCpuApplicationVerified'] = True
                proof['admin'] = {}
                for route in ('/api/v1/admin/snapshot', '/api/v1/admin/events', '/'):
                    async with client.stream('GET', runtime.base + route) as response:
                        proof['admin'][route] = response.status_code
                return
            await patch('/api/v1/twitch/monitoring', {'mode': 'simulated', 'channel': 'demo'})
            proof['ordinaryReplies'] = [await capture['notify']('wave12 companion device first')]
            await wait_until(lambda: status().get('notification_accepted') == 1, 5)
            cards = [row for row in await history(client, runtime.base) if row['title'] == 'wave12 companion device first']
            assert len(cards) == 1
            proof['cards'] = cards
            first = cards[0]
            await observation(lambda row: row['event'] == 'card_lifecycle' and row['seq'] == first['seq'] and row['phase'] == 2)
            before = len(http.records)
            override = await patch('/api/v1/display', {'twitchDisplayEnabled': True})
            proof['overriddenView'] = await observation(lambda row: row['event'] == 'application_view' and row['displayRevision'] == override['revision']
                                                        and row['epoch'] == override['relayEpoch'] == ready['epoch']
                                                        and row['connection'] == ready['connection']
                                                        and row['baseScreen'] == 2 and row['covered'] and not row['cpuVisible'])
            await wait_until(lambda: any(row['method'] == 'PUT' and row['status'] == 200 for row in http.records[before:]), 5)
            hidden = [row for row in http.records[before:] if row['method'] == 'PUT' and row['status'] == 200][-1]
            assert matches_submitted_cpu(hidden['response'], hidden['payload'])
            proof['hiddenCpu'] = hidden
            second_boundary, second = 0, []

            async def second_card():
                boundary = len(http.records)
                http.next_fault, http.fault_path = proof['responseFault'], '/api/v1/notifications'
                proof['ordinaryReplies'].append(await capture['notify']('wave12 companion device response lost'))
                await wait_until(lambda: status().get('notification_ambiguous') == 1, 5)
                second = [row for row in await history(client, runtime.base) if row['title'] == 'wave12 companion device response lost']
                assert len(second) == 1
                proof['cards'].extend(second)
                return boundary, second

            if browser is not None:
                second_boundary, second = await second_card()
                await browser.fifo(first, second[0])
            await observation(lambda row: row['event'] == 'card_lifecycle' and row['seq'] == first['seq'] and row['phase'] == 0)
            await patch('/api/v1/display', {'twitchDisplayEnabled': False})
            if browser is None:
                await measured_view(before)
            await patch('/api/v1/twitch/monitoring', {'mode': 'disabled'})
            if browser is None:
                second_boundary, second = await second_card()
            await observation(lambda row: row['event'] == 'card_lifecycle' and row['seq'] == second[0]['seq'] and row['phase'] == 0, 20)
            await observation(lambda row: row['event'] == 'device_report_written' and row['enqueuedSeq'] == second[0]['seq']
                              and row['phase'] == row['pending'] == 0)
            if browser is not None:
                assert second_boundary is not None
                await measured_view(second_boundary)
            if proof['responseFault'] == 'corrupt_response':
                if browser is None:
                    await measured_view(second_boundary)
                corrupt_index = next(index for index, row in enumerate(http.records)
                                     if row['fault'] == 'corrupt_response')
                assert any(row['method'] == 'GET' and row['path'] == '/api/v1/clock'
                           for row in http.records[corrupt_index + 1:])
                proof['malformedResponseRecoveryVerified'] = True
            verify_cards(proof)
            proof['lifecycleCallbacks'] = {'status': 'pending'}
            proof['admin'] = {}
            for route in ('/api/v1/admin/snapshot', '/api/v1/admin/events', '/'):
                async with client.stream('GET', runtime.base + route) as response:
                    proof['admin'][route] = response.status_code
            child.send_signal(signal.SIGTERM)
            stdout, stderr = await asyncio.wait_for(child.communicate(), 5)
            assert child.returncode == 0 and not stderr
            proof['stopped'] = status()
            assert proof['stopped']['captured'] == 2 and proof['stopped']['notification_accepted'] == 1
            assert proof['stopped']['notification_ambiguous'] == 1
            assert proof['stopped']['pendingNotifications'] == proof['stopped']['pendingNotificationBytes'] == 0
            assert config.read_bytes() == original and proof['stopped']['pid'] == child.pid
            assert b'PRIVATE SYNTHETIC SWITCH BODY' not in stdout + stderr + status_path.read_bytes()
            (directory / 'companion.log').write_bytes(stdout + stderr)
            assert len([row for row in http.requests if row['method'] == 'POST']) == 2
            proof['finalHistory'] = await history(client, runtime.base)
            captured_history = [row for row in proof['finalHistory'] if row['title'].startswith('wave12 companion device ')]
            assert len(captured_history) == 2
            assert all(row['body'] == '' and row['origin'] == 'external' for row in captured_history)
            # Complete the peer's bounded natural run; do not call terminated output a pass.
            await asyncio.wait_for(peer.wait(), 60)
            await reader
            assert peer.returncode == 0
            proof['peerExitCode'] = peer.returncode
            proof.update(responses=http.records, frames=wire.frames)
            verify_publications(proof)
            from pair_runtime import conserved_fixture_credit
            reports = [row for row in records if row['event'] == 'device_report_written']
            assert conserved_fixture_credit(reports, [card['seq'] for card in proof['cards']], proof['cards'][0]['relayEpoch'])
            proof['everyReportCreditConserved'] = True
            from runtime_lifecycle import verify_callbacks, verify_report_phases
            verify_report_phases(records)
            try:
                proof['lifecycleCallbacks'] = {'status': 'passed', **verify_callbacks(records, [8000, 8000])}
            except AssertionError as error:
                proof['lifecycleCallbacks'] = {'status': 'blocked', 'reason': str(error)}
                raise
        finally:
            proof.update(status=status(), requests=http.requests, responses=http.records,
                         frames=wire.frames, cpuFrames=wire.cpus())
            reader.cancel()
            await asyncio.gather(reader, return_exceptions=True)
            await reap_child(child)
            # stdout has one owner; cancel it before communicate-based child cleanup.
            await reap_child(peer)
            errors = await peer.stderr.read()
            (directory / 'native-peer.log').write_bytes(errors)
            proof.update(childExitCode=child.returncode,
                         peerReaped=peer.returncode is not None, childReaped=child.returncode is not None)
    proof['gatewaysReleased'] = not http.tasks and not wire.tasks


async def exercise(image, binary, evidence, denied=False, malformed=False, browser_executable=None):
    result = {'status': 'blocked', 'imageId': image, 'monitorDeniedFixture': denied,
              'proof': {'responseFault': 'corrupt_response' if malformed else 'lose_response'}, 'productAccepted': False,
              'allSoftwareVerified': False, 'desktopVerified': False, 'physicalVerified': False}
    evidence.mkdir(parents=True, exist_ok=True, mode=0o700)
    with tempfile.TemporaryDirectory(dir=os.environ['TMPDIR'], prefix='companion-device-') as scratch:
        directory = Path(scratch)
        frozen = directory / 'native-peer'
        shutil.copyfile(binary, frozen)
        frozen.chmod(0o700)
        result['nativeBinarySha256'] = hashlib.sha256(frozen.read_bytes()).hexdigest()
        runtime = ComposeRuntime(image, directory)
        sys.path.insert(0, str(ROOT / 'twitch-screen-firmware/tools'))
        try:
            await runtime.start()
            async with synthetic_capture(result['proof'], denied=denied) as capture:
                if browser_executable is None:
                    await scenario(runtime, frozen, directory, result['proof'], capture)
                else:
                    from check_runtime_browser import BrowserWitness
                    async with BrowserWitness(runtime.base, browser_executable, evidence, result['proof']) as browser:
                        await scenario(runtime, frozen, directory, result['proof'], capture, browser)
            result['status'] = 'passed'
        except Exception:
            result['error'] = traceback.format_exc()
            traceback.print_exc()
        finally:
            result['cleanup'] = await runtime.stop()
            for log in directory.glob('*.log'):
                (evidence / log.name).write_bytes(log.read_bytes())
            fixture = result['proof'].get('fixtureDirectory')
            if fixture:
                path = Path(fixture)
                assert path.parent.resolve() == Path(os.environ['TMPDIR']).resolve() and path.name.startswith('client-test-')
                for log in path.glob('*.log'):
                    (evidence / ('private-bus-' + log.name)).write_bytes(log.read_bytes())
                shutil.rmtree(path)
                result['fixtureRemoved'] = not path.exists()
    result['scratchRemoved'] = not directory.exists()
    (evidence / 'device.json').write_text(json.dumps(result, indent=2) + '\n')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--image-id', required=True)
    parser.add_argument('--binary', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--test-owned', action='store_true')
    parser.add_argument('--deny-monitor', action='store_true', help='Prove measured CPU application with private monitoring denied')
    parser.add_argument('--browser-executable', type=Path,
                        help='Join actual installed producer/native application with mounted Chromium panel')
    parser.add_argument('--malformed-response', action='store_true',
                        help='Corrupt one committed POST response body instead of losing its response')
    args = parser.parse_args()
    if args.deny_monitor and args.malformed_response:
        parser.error('--malformed-response requires the capture scenario, not --deny-monitor')
    if not args.test_owned or (ROOT / '.agent-workflow/STOP').exists():
        parser.error('--test-owned required and workflow STOP must be absent')
    if not re.fullmatch(r'sha256:[0-9a-f]{64}', args.image_id):
        parser.error('--image-id must be an exact lowercase sha256 image identity')
    if not args.binary.is_file() or not os.access(args.binary, os.X_OK):
        parser.error('--binary must be an existing executable native runtime peer')
    if args.browser_executable is not None and (not args.browser_executable.is_file()
            or not os.access(args.browser_executable, os.X_OK)):
        parser.error('--browser-executable must be an existing executable reviewed Chromium artifact')
    os.umask(0o077)
    result = asyncio.run(exercise(args.image_id, args.binary.resolve(), args.evidence.resolve(),
                                  args.deny_monitor, args.malformed_response,
                                  args.browser_executable.resolve() if args.browser_executable else None))
    print(json.dumps({key: value for key, value in result.items() if key != 'proof'}, indent=2))
    return 0 if result['status'] == 'passed' else 2


if __name__ == '__main__':
    raise SystemExit(main())
