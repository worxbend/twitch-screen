"""Additional end-to-end and failure-boundary tests for production modules."""
import asyncio
import contextlib
import json
import os
from pathlib import Path
import signal
import socket
import sys
import tempfile
import unittest

from dbus_fast import Message, MessageType
from test_runtime import Relay, PrivateBus, ROOT, NAME, PATH, EPOCH, configuration, FIXTURES
from test_transport import wire_message
from twitch_screen_client.clock import Calibration
from twitch_screen_client.dbus_transport import Connection, bus_call
from twitch_screen_client.delivery import Delivery
from twitch_screen_client.metrics import boottime_ms
from twitch_screen_client.queue import CardQueue
from twitch_screen_client.responses import accepted
from twitch_screen_client.runtime import initial_status


class ResponseTests(unittest.TestCase):
    def test_response_shape_and_absent_memory(self):
        payload = FIXTURES['notification_bodyless']
        response = {**FIXTURES['notification_accepted'], 'type': 'message',
                    'title': payload['title'], 'body': payload['body'],
                    'ttlMs': 8000, 'expiresInMs': 60000}
        self.assertTrue(accepted(response, 'POST', payload))
        for key in response:
            malformed = dict(response)
            del malformed[key]
            self.assertFalse(accepted(malformed, 'POST', payload))
        malformed = {**response, 'seq': True}
        self.assertFalse(accepted(malformed, 'POST', payload))
        malformed = {**response, 'title': '\ud800'}
        self.assertFalse(accepted(malformed, 'POST', payload))
        cpu = FIXTURES['cpu_fresh']
        self.assertTrue(accepted(cpu, 'PUT', cpu['data']))
        missing = {**cpu, 'data': {**cpu['data'], 'memoryPercent': None}}
        self.assertTrue(accepted(missing, 'PUT', cpu['data']))
        malformed = {**cpu, 'data': {**cpu['data'], 'cpuPercent': float('nan')}}
        self.assertFalse(accepted(malformed, 'PUT', cpu['data']))


class FailureTests(unittest.IsolatedAsyncioTestCase):
    async def test_partial_frame_timeout_and_cancellation_close(self):
        for attack in ('partial_header', 'missing_body', 'cancel'):
            writer, reader = socket.socketpair()
            writer.setblocking(False)
            reader.setblocking(False)
            connection = Connection('/unused', 'fixture')
            connection.sock.close()
            connection.sock = reader
            wire = wire_message()
            try:
                if attack == 'cancel':
                    task = asyncio.create_task(connection.receive())
                    await asyncio.sleep(0)
                    task.cancel()
                    with self.assertRaises(asyncio.CancelledError):
                        await task
                else:
                    await asyncio.get_running_loop().sock_sendall(writer, wire[:1] if attack == 'partial_header' else wire[:16])
                    with self.assertRaises(TimeoutError):
                        await connection.receive()
                self.assertEqual(connection.sock.fileno(), -1)
            finally:
                writer.close()
                connection.close()

    async def test_calibration_failure_never_sends_and_expiry_stays_original(self):
        async with Relay() as relay:
            status = initial_status()
            delivery = Delivery(configuration(relay.url), boottime_ms, CardQueue(boottime_ms), status)
            try:
                deadline = boottime_ms() - 1
                outcome = await delivery.upload(delivery.exchanges[0], 'POST', '/api/v1/notifications',
                    FIXTURES['notification_bodyless'], deadline)
                self.assertEqual(outcome, 'retry')
                self.assertFalse(any(method == 'POST' for method, _, _ in relay.requests))
                deadline = boottime_ms() + 60000
                relay.mode = 'limited'
                await delivery.upload(delivery.exchanges[0], 'POST', '/api/v1/notifications', FIXTURES['notification_bodyless'], deadline)
                first = relay.requests[-1][2]['deadline']['monotonicMs']
                now = boottime_ms()
                delivery.calibration = Calibration.checked(EPOCH, now + 500, now, now)
                await delivery.upload(delivery.exchanges[0], 'POST', '/api/v1/notifications', FIXTURES['notification_bodyless'], deadline)
                self.assertLessEqual(relay.requests[-1][2]['deadline']['monotonicMs'], first)
            finally:
                await delivery.close()

    async def test_latest_cpu_replaces_backlog_while_notification_hangs(self):
        async with Relay() as relay:
            status = initial_status()
            delivery = Delivery(configuration(relay.url), boottime_ms, CardQueue(boottime_ms), status)
            gate, started = asyncio.Event(), asyncio.Event()
            exchange = delivery.exchanges[1]
            original = exchange.request
            calls = []
            async def slow_request(method, path, payload=None):
                if method == 'PUT':
                    calls.append(payload['cpuPercent'])
                    if len(calls) == 1:
                        started.set()
                        await gate.wait()
                return await original(method, path, payload)
            exchange.request = slow_request
            task = asyncio.create_task(delivery.metrics())
            try:
                delivery.sample(boottime_ms(), {'cpuPercent': 1, 'memoryPercent': 2})
                await asyncio.wait_for(started.wait(), 3)
                for percent in range(2, 100):
                    delivery.sample(boottime_ms(), {'cpuPercent': percent, 'memoryPercent': 2})
                self.assertEqual(delivery.cpu[1]['cpuPercent'], 99)
                gate.set()
                async with asyncio.timeout(3):
                    while len(calls) < 2:
                        await asyncio.sleep(.01)
                self.assertEqual(calls, [1, 99])
            finally:
                task.cancel()
                await asyncio.gather(task, return_exceptions=True)
                await delivery.close()


class FullChildTests(unittest.IsolatedAsyncioTestCase):
    async def test_actual_child_notify_to_http_cpu_and_normal_daemon_delivery(self):
        async with PrivateBus() as bus, Relay() as relay:
            relay.mode = getattr(self, 'relay_mode', 'normal')
            with tempfile.TemporaryDirectory(dir=os.environ['TMPDIR'], prefix='cf-') as directory:
                root = Path(directory)
                root.chmod(0o700)
                config = root / 'config.toml'
                config.write_text(f'relay_url = "{relay.url}"\nsource_id = "full-child"\nsample_interval_ms = 1000\nnotifications_enabled = true\ninclude_apps = ["twitch-screen-e2e"]\n')
                server = await bus.connect('fake daemon')
                await server.call(bus_call('RequestName', 'su', [NAME, 0]))
                producer = await bus.connect('synthetic producer')
                deliveries = []
                async def serve():
                    while True:
                        message = await server.receive()
                        if message.message_type == MessageType.METHOD_CALL and message.member == 'Notify':
                            deliveries.append(message.serial)
                            await server.send(Message.new_method_return(message, signature='u', body=[42]))
                daemon_task = asyncio.create_task(serve())
                env = {**os.environ, 'DBUS_SESSION_BUS_ADDRESS': 'unix:path=' + bus.path, 'XDG_RUNTIME_DIR': directory,
                       'PYTHONPATH': '' if os.environ.get('TSC_TEST_INSTALLED') == '1' else str(ROOT / 'twitch-screen-client/src')}
                process = await asyncio.create_subprocess_exec(sys.executable, '-m', 'twitch_screen_client', 'run', '--config', str(config),
                    env=env, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
                status_path = root / 'twitch-screen-client-full-child.json'
                try:
                    async with asyncio.timeout(5):
                        while not status_path.exists() or json.loads(status_path.read_text())['capture'] != 'available':
                            await asyncio.sleep(.02)
                    if relay.mode in ('numeric', 'changed_measurement'):
                        await relay.wait_path('/api/v1/cpu')
                        async with asyncio.timeout(4):
                            while json.loads(status_path.read_text())['relay'] != 'invalid_response':
                                await asyncio.sleep(.02)
                        self.assertIsNone(process.returncode)
                    reply = await producer.call(Message(destination=NAME, path=PATH, interface=NAME, member='Notify',
                        signature='susssasa{sv}i', body=['twitch-screen-e2e', 0, '', 'Synthetic child card', 'NEVER FORWARD THIS BODY', [], {}, 0]))
                    self.assertEqual(reply.body, [42])
                    await relay.wait_path('/api/v1/notifications')
                    await relay.wait_path('/api/v1/cpu')
                    posted = next(value for method, _, value in relay.requests if method == 'POST')
                    self.assertEqual(posted['title'], 'Synthetic child card')
                    self.assertEqual(posted['body'], '')
                    self.assertEqual(posted['deadline']['relayEpoch'], EPOCH)
                    self.assertEqual(len(deliveries), 1)
                    async with asyncio.timeout(4):
                        while json.loads(status_path.read_text())['notification_accepted'] < 1:
                            await asyncio.sleep(.02)
                    if relay.mode in ('numeric', 'changed_measurement'):
                        self.assertEqual(json.loads(status_path.read_text())['cpu_accepted'], 0)
                    else:
                        async with asyncio.timeout(4):
                            while json.loads(status_path.read_text())['cpu_accepted'] < 1:
                                await asyncio.sleep(.02)
                    process.send_signal(signal.SIGTERM)
                    stdout, stderr = await asyncio.wait_for(process.communicate(), 5)
                    self.assertEqual(process.returncode, 0, stderr.decode())
                    self.assertNotIn(b'NEVER FORWARD', stdout + stderr + status_path.read_bytes())
                finally:
                    if process.returncode is None:
                        process.terminate()
                        await asyncio.wait_for(process.communicate(), 5)
                    daemon_task.cancel()
                    await asyncio.gather(daemon_task, return_exceptions=True)
