"""Synthetic bus and real loopback HTTP tests; never use the desktop bus."""
import asyncio
import contextlib
from decimal import Decimal, ROUND_HALF_UP
import json
import os
from pathlib import Path
import signal
import sys
import tempfile
import unittest

from dbus_fast import Message, MessageType
from test_transport import PrivateBus, NAME, PATH
from twitch_screen_client.capture import Capture
from twitch_screen_client.clock import Calibration
from twitch_screen_client.config import parse
from twitch_screen_client.delivery import Delivery, Exchange
from twitch_screen_client.metrics import boottime_ms
from twitch_screen_client.normalize import Card
from twitch_screen_client.queue import CardQueue
from twitch_screen_client.runtime import initial_status
from twitch_screen_client.dbus_transport import bus_call

ROOT = Path(__file__).resolve().parents[2]
EPOCH = '0123456789abcdef'
FIXTURES = {example['name']: example['value'] for example in
            json.loads((ROOT / 'protocol/desk-v1-http-examples.json').read_text())['examples']}


class Relay:
    """Test-owned fake HTTP provider, using canonical response fixtures."""
    def __init__(self):
        self.requests = []
        self.mode = 'normal'
        self.clock_cache_control = 'no-store'
        self.write_calibration = True
        self.changed = asyncio.Event()
        self.handlers = set()
        self.connections = set()

    async def __aenter__(self):
        self.server = await asyncio.start_server(self.handle, '127.0.0.1', 0)
        self.url = 'http://127.0.0.1:' + str(self.server.sockets[0].getsockname()[1])
        return self

    async def handle(self, reader, writer):
        task = asyncio.current_task()
        self.handlers.add(task)
        self.connections.add(writer)
        try:
            header = await asyncio.wait_for(reader.readuntil(b'\r\n\r\n'), 5)
            rows = header.decode().split('\r\n')
            method, path, _ = rows[0].split()
            headers = dict(row.split(': ', 1) for row in rows[1:] if ': ' in row)
            body = await reader.readexactly(int(headers.get('Content-Length', 0)))
            value = json.loads(body) if body else None
            self.requests.append((method, path, value))
            self.changed.set()
            response_headers = ''
            if path == '/api/v1/clock':
                code, output = 200, {'relayEpoch': EPOCH, 'monotonicMs': boottime_ms()}
                response_headers = f'Cache-Control: {self.clock_cache_control}\r\n'
            elif self.mode == 'hang':
                await reader.read()
                return
            elif self.mode == 'uncertain':
                code, output = 503, {'error': 'uncertain'}
            elif self.mode == 'epoch':
                code, output = 409, FIXTURES['clock_epoch_changed']
            elif self.mode == 'limited':
                code, output = 429, FIXTURES['rate_limited']
            elif self.mode == 'oversize':
                code, output = 200, {'padding': 'x' * 65536}
            elif self.mode == 'corrupt':
                code, output = 200, None
            else:
                if path == '/api/v1/notifications':
                    output = {**FIXTURES['notification_accepted'], **{k: value[k] for k in ('type', 'title', 'body', 'ttlMs', 'expiresInMs')}}
                else:
                    assert isinstance(value, dict)
                    output = {**FIXTURES['cpu_fresh'],
                              'observedAtSource': 'producer' if 'observedAt' in value else 'relay_receipt',
                              'data': {**FIXTURES['cpu_fresh']['data'],
                                       'memoryPercent': None, 'temperatureCelsius': None,
                                       'observedAt': value.get('observedAt', FIXTURES['cpu_fresh']['receivedAt']),
                                       **{key: item for key, item in value.items() if key != 'deadline'}}}
                    for field in ('cpuPercent', 'memoryPercent', 'temperatureCelsius'):
                        if field in value:
                            output['data'][field] = float(Decimal(str(value[field])).quantize(
                                Decimal('0.01'), rounding=ROUND_HALF_UP))
                if self.mode == 'changed_measurement' and path == '/api/v1/cpu':
                    output['data']['cpuPercent'] = 99 if output['data']['cpuPercent'] != 99 else 98
                if self.mode == 'numeric' and path == '/api/v1/cpu':
                    output['data']['cpuPercent'] = 10**400
                code = 200
                if self.write_calibration:
                    response_headers = 'X-Relay-Epoch: ' + EPOCH + '\r\nX-Relay-Monotonic-Ms: ' + str(boottime_ms()) + '\r\n'
            wire = json.dumps(output).encode() if output is not None else b'{broken'
            writer.write(f'HTTP/1.1 {code} Fixture\r\nContent-Type: application/json\r\nContent-Length: {len(wire)}\r\nConnection: close\r\n{response_headers}\r\n'.encode() + wire)
            await writer.drain()
        except (ConnectionError, asyncio.IncompleteReadError, TimeoutError):
            pass
        finally:
            writer.close()
            with contextlib.suppress(ConnectionError):
                await writer.wait_closed()
            self.connections.discard(writer)
            self.handlers.discard(task)

    async def wait_path(self, path):
        async with asyncio.timeout(8):
            while not any(row[1] == path for row in self.requests):
                self.changed.clear()
                await self.changed.wait()

    async def __aexit__(self, *exc):
        self.server.close()
        await self.server.wait_closed()
        for writer in list(self.connections):
            writer.close()
        for task in list(self.handlers):
            task.cancel()
        await asyncio.gather(*list(self.handlers), return_exceptions=True)


def configuration(url, **kw):
    return parse({'relay_url': url, 'source_id': 'test-desktop', 'host': 'Synthetic host',
                  'notifications_enabled': True, 'include_apps': ['twitch-screen-e2e'], **kw})


class HandoffTests(unittest.TestCase):
    def test_calibration_boundaries_and_example(self):
        calibration = Calibration.checked(EPOCH, 5000, 1000, 1100)
        self.assertEqual(calibration.deadline(61000, 1100)['monotonicMs'], 64790)
        self.assertFalse(calibration.usable(61101))
        for arguments in (('BAD', 0, 0, 0), (EPOCH, True, 0, 0), (EPOCH, 0, 0, 1001),
                          ('0000000000000000', 0, 0, 0)):
            with self.assertRaises(ValueError):
                Calibration.checked(*arguments)

    def test_queue_count_bytes_expiry_no_replacement_coalescing(self):
        now = [0]
        queue = CardQueue(lambda: now[0])
        for index in range(32):
            self.assertTrue(queue.offer(Card({'title': str(index)}, 1000, 100)))
        self.assertFalse(queue.offer(Card({}, 1000, 1)))
        self.assertEqual(queue.refused, 1)
        self.assertEqual(queue.take().payload['title'], '0')
        now[0] = 1000
        self.assertIsNone(queue.take())
        self.assertEqual(queue.bytes, 0)
        self.assertEqual(queue.expired, 31)
        self.assertTrue(queue.offer(Card({}, 2000, 65536)))
        self.assertFalse(queue.offer(Card({}, 2000, 1)))


class CaptureTests(unittest.IsolatedAsyncioTestCase):
    async def test_production_capture_owner_restart_and_normal_delivery(self):
        async with PrivateBus() as bus:
            cards = []
            status = initial_status()
            capture = Capture(configuration('http://localhost'), cards.append, status,
                              address='unix:path=' + bus.path)
            producer = await bus.connect('synthetic producer')
            tasks = []
            async def daemon():
                server = await bus.connect('fake daemon')
                self.assertEqual((await server.call(bus_call('RequestName', 'su', [NAME, 0]))).body, [1])
                async def serve():
                    while True:
                        message = await server.receive()
                        if message.message_type == MessageType.METHOD_CALL and message.member == 'Notify':
                            await server.send(Message.new_method_return(message, signature='u', body=[17]))
                task = asyncio.create_task(serve())
                tasks.append(task)
                return server, task
            server, daemon_task = await daemon()
            capture_task = asyncio.create_task(capture.session())
            tasks.append(capture_task)
            try:
                async with asyncio.timeout(3):
                    while status['capture'] != 'available':
                        await asyncio.sleep(.01)
                async def submit(title):
                    reply = await producer.call(Message(destination=NAME, path=PATH, interface=NAME,
                           member='Notify', signature='susssasa{sv}i',
                           body=['twitch-screen-e2e', 17, '', title, 'PRIVATE SYNTHETIC BODY', [], {}, 0]))
                    self.assertEqual(reply.body, [17])
                await submit('One')
                async with asyncio.timeout(3):
                    while len(cards) < 1:
                        await asyncio.sleep(.01)
                old_owner = capture.owner
                daemon_task.cancel()
                await asyncio.gather(daemon_task, return_exceptions=True)
                server.close()
                server, daemon_task = await daemon()
                async with asyncio.timeout(3):
                    while capture.owner == old_owner or not capture.owner:
                        await asyncio.sleep(.01)
                await submit('Two')
                async with asyncio.timeout(3):
                    while len(cards) < 2:
                        await asyncio.sleep(.01)
                self.assertEqual([card.payload['title'] for card in cards], ['One', 'Two'])
                self.assertTrue(all(card.payload['body'] == '' for card in cards))
                self.assertEqual(capture.connections[1].sent_after_monitor, 0)
            finally:
                for task in tasks:
                    task.cancel()
                await asyncio.gather(*tasks, return_exceptions=True)
                self.assertEqual(capture.connections, [])


class DeliveryTests(unittest.IsolatedAsyncioTestCase):
    async def test_real_http_accepted_and_ambiguous_outcomes(self):
        async with Relay() as relay:
            status = initial_status()
            delivery = Delivery(configuration(relay.url), boottime_ms, CardQueue(boottime_ms), status)
            try:
                payload = {'type': 'message', 'title': 'Synthetic', 'body': '', 'ttlMs': 8000, 'expiresInMs': 60000}
                deadline = boottime_ms() + 60000
                self.assertEqual(await delivery.upload(delivery.exchanges[0], 'POST', '/api/v1/notifications', payload, deadline), 'accepted')
                self.assertLess(relay.requests[-1][2]['deadline']['monotonicMs'], deadline)
                for mode, expected in (('uncertain', 'ambiguous'), ('corrupt', 'ambiguous'),
                                       ('oversize', 'ambiguous'), ('epoch', 'retry'), ('limited', 'retry')):
                    relay.mode = mode
                    self.assertEqual(await delivery.upload(delivery.exchanges[0], 'POST', '/api/v1/notifications', payload, deadline), expected)
            finally:
                await delivery.close()

    async def test_worker_drops_ambiguous_post_without_replay(self):
        async with Relay() as relay:
            relay.mode = 'uncertain'
            status = initial_status()
            delivery = Delivery(configuration(relay.url), boottime_ms, CardQueue(boottime_ms), status)
            card = Card({'type': 'message', 'title': 'Synthetic', 'body': '', 'ttlMs': 8000, 'expiresInMs': 60000}, boottime_ms() + 60000, 100)
            delivery.submit(card)
            task = asyncio.create_task(delivery.notifications())
            try:
                await relay.wait_path('/api/v1/notifications')
                async with asyncio.timeout(3):
                    while status['notification_ambiguous'] != 1:
                        await asyncio.sleep(.01)
                await asyncio.sleep(1.2)
                self.assertEqual(sum(row[1] == '/api/v1/notifications' for row in relay.requests), 1)
                self.assertEqual(len(delivery.cards.cards), 0)
            finally:
                task.cancel()
                await asyncio.gather(task, return_exceptions=True)
                await delivery.close()

    async def test_response_total_deadline(self):
        async with Relay() as relay:
            relay.mode = 'hang'
            status = initial_status()
            delivery = Delivery(configuration(relay.url), boottime_ms, CardQueue(boottime_ms), status)
            try:
                start = asyncio.get_running_loop().time()
                outcome = await delivery.upload(delivery.exchanges[0], 'POST', '/api/v1/notifications',
                    {'type': 'info', 'title': 'Synthetic', 'body': '', 'ttlMs': 8000, 'expiresInMs': 60000}, boottime_ms() + 60000)
                self.assertEqual(outcome, 'ambiguous')
                self.assertLess(asyncio.get_running_loop().time() - start, 4)
            finally:
                await delivery.close()


class ChildTests(unittest.IsolatedAsyncioTestCase):
    async def test_denied_monitor_does_not_stop_measured_cpu_and_signal_cleanup(self):
        async with PrivateBus(denied=True) as bus, Relay() as relay:
            with tempfile.TemporaryDirectory(dir=os.environ['TMPDIR'], prefix='cc-') as directory:
                root = Path(directory)
                root.chmod(0o700)
                config = root / 'config.toml'
                config.write_text(f'relay_url = "{relay.url}"\nsource_id = "child-test"\nsample_interval_ms = 1000\nnotifications_enabled = true\ninclude_apps = ["twitch-screen-e2e"]\n')
                environment = {**os.environ, 'XDG_RUNTIME_DIR': directory, 'DBUS_SESSION_BUS_ADDRESS': 'unix:path=' + bus.path,
                               'PYTHONPATH': '' if os.environ.get('TSC_TEST_INSTALLED') == '1' else str(ROOT / 'twitch-screen-client/src')}
                process = await asyncio.create_subprocess_exec(sys.executable, '-m', 'twitch_screen_client', 'run', '--config', str(config),
                    env=environment, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
                try:
                    await relay.wait_path('/api/v1/cpu')
                    sample = next(row[2] for row in relay.requests if row[1] == '/api/v1/cpu')
                    self.assertTrue(0 <= sample['cpuPercent'] <= 100)
                    self.assertTrue(0 <= sample['memoryPercent'] <= 100)
                    status = json.loads((root / 'twitch-screen-client-child-test.json').read_text())
                    self.assertEqual(status['capture'], 'monitor_denied')
                    self.assertEqual(status['cpu'], 'available')
                    # A duplicate instance cannot replace the live source.
                    duplicate = await asyncio.create_subprocess_exec(sys.executable, '-m', 'twitch_screen_client', 'run', '--config', str(config),
                        env=environment, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
                    stdout, stderr = await asyncio.wait_for(duplicate.communicate(), 3)
                    self.assertEqual(duplicate.returncode, 2)
                    process.send_signal(signal.SIGTERM)
                    stdout, stderr = await asyncio.wait_for(process.communicate(), 5)
                    self.assertEqual(process.returncode, 0, stderr.decode())
                    status = json.loads((root / 'twitch-screen-client-child-test.json').read_text())
                    self.assertEqual(status['capture'], 'stopped')
                    self.assertEqual(status['cpu'], 'stopped')
                    self.assertEqual(stderr, b'')
                finally:
                    if process.returncode is None:
                        process.terminate()
                        await asyncio.wait_for(process.communicate(), 5)
