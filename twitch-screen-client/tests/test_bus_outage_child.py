"""Foreground CPU continuity across a test-owned bus outage and capture recovery."""
import asyncio
import json
import os
from pathlib import Path
import signal
import sys
import tempfile
import unittest

from dbus_fast import Message, MessageType
from test_runtime import Relay, PrivateBus, ROOT, NAME, PATH
from twitch_screen_client.dbus_transport import bus_call


class BusOutageChildTests(unittest.IsolatedAsyncioTestCase):
    async def test_cpu_continues_during_bus_outage_and_capture_recovers_same_endpoint(self):
        async with PrivateBus() as bus, Relay() as relay:
            with tempfile.TemporaryDirectory(dir=os.environ['TMPDIR'], prefix='cc-bus-') as directory:
                root = Path(directory)
                root.chmod(0o700)
                config = root / 'config.toml'
                config.write_text(f'relay_url = "{relay.url}"\nsource_id = "bus-child"\nsample_interval_ms = 1000\nnotifications_enabled = true\ninclude_apps = ["twitch-screen-e2e"]\n')
                deliveries = []
                daemon_tasks = []

                async def daemon():
                    server = await bus.connect('test-owned notification daemon')
                    await server.call(bus_call('RequestName', 'su', [NAME, 0]))
                    async def serve():
                        while True:
                            message = await server.receive()
                            if message.message_type == MessageType.METHOD_CALL and message.member == 'Notify':
                                deliveries.append(message.body[3])
                                await server.send(Message.new_method_return(message, signature='u', body=[42]))
                    task = asyncio.create_task(serve())
                    daemon_tasks.append(task)
                    return server

                await daemon()
                environment = {**os.environ, 'DBUS_SESSION_BUS_ADDRESS': 'unix:path=' + bus.path,
                               'XDG_RUNTIME_DIR': directory,
                               'PYTHONPATH': '' if os.environ.get('TSC_TEST_INSTALLED') == '1' else str(ROOT / 'twitch-screen-client/src')}
                process = await asyncio.create_subprocess_exec(sys.executable, '-m', 'twitch_screen_client', 'run', '--config', str(config),
                    env=environment, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
                status_path = root / 'twitch-screen-client-bus-child.json'

                async def wait_status(predicate, timeout=8):
                    async with asyncio.timeout(timeout):
                        while True:
                            if status_path.exists():
                                status = json.loads(status_path.read_text())
                                if predicate(status):
                                    return status
                            self.assertIsNone(process.returncode)
                            await asyncio.sleep(.02)

                def cpu_requests():
                    return [value for method, _, value in relay.requests if method == 'PUT']

                async def notify(title):
                    producer = await bus.connect('test-owned synthetic producer')
                    reply = await producer.call(Message(destination=NAME, path=PATH, interface=NAME, member='Notify',
                        signature='susssasa{sv}i', body=['twitch-screen-e2e', 0, '', title, 'NEVER FORWARD THIS BODY', [], {}, 0]))
                    self.assertEqual(reply.body, [42])

                try:
                    await wait_status(lambda status: status['capture'] == 'available' and status['cpu_accepted'] >= 1)
                    await notify('Before bus outage')
                    await wait_status(lambda status: status['notification_accepted'] == 1)
                    # Stop only the owned private daemon, never the desktop session bus.
                    bus.process.terminate()
                    await asyncio.wait_for(bus.process.wait(), 3)
                    await bus.process.communicate()
                    await asyncio.gather(daemon_tasks[0], return_exceptions=True)
                    failed = await wait_status(lambda status: status['capture'] == 'unavailable' and status['capture_failures'] >= 1)
                    count = len(cpu_requests())
                    # Status publication can lag the server's request readback. Require
                    # two new actual uploads from the same baseline before proceeding.
                    degraded = await wait_status(lambda status:
                        status['cpu_accepted'] >= failed['cpu_accepted'] + 2
                        and len(cpu_requests()) >= count + 2)
                    self.assertEqual(degraded['capture'], 'unavailable')
                    self.assertEqual(degraded['cpu'], 'available')
                    self.assertGreaterEqual(len(cpu_requests()), count + 2)
                    self.assertTrue(all(0 <= sample['cpuPercent'] <= 100 and 0 <= sample['memoryPercent'] <= 100
                                        for sample in cpu_requests()))
                    # Restart the same owned endpoint; child environment/config stays unchanged.
                    Path(bus.path).unlink(missing_ok=True)
                    bus.process = await asyncio.create_subprocess_exec('dbus-daemon', '--nofork', '--print-address=1',
                        '--config-file=' + str(Path(bus.tmp.name) / 'bus.conf'),
                        stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
                    assert bus.process.stdout is not None
                    address = (await asyncio.wait_for(bus.process.stdout.readline(), 3)).decode().strip()
                    self.assertTrue(address.startswith('unix:path=' + bus.path))
                    await daemon()
                    recovered = await wait_status(lambda status: status['capture'] == 'available')
                    self.assertGreaterEqual(recovered['capture_failures'], 1)
                    await notify('After bus recovery')
                    await wait_status(lambda status: status['notification_accepted'] == 2)
                    self.assertEqual(deliveries, ['Before bus outage', 'After bus recovery'])
                    posts = [value for method, _, value in relay.requests if method == 'POST']
                    self.assertEqual([value['title'] for value in posts], deliveries)
                    self.assertTrue(all(value['body'] == '' for value in posts))
                    process.send_signal(signal.SIGTERM)
                    stdout, stderr = await asyncio.wait_for(process.communicate(), 5)
                    self.assertEqual(process.returncode, 0, stderr.decode())
                    self.assertEqual(stderr, b'')
                    self.assertNotIn(b'NEVER FORWARD', stdout + stderr + status_path.read_bytes())
                    stopped = json.loads(status_path.read_text())
                    self.assertEqual((stopped['capture'], stopped['cpu'], stopped['relay']), ('stopped', 'stopped', 'stopped'))
                finally:
                    if process.returncode is None:
                        process.terminate()
                        await asyncio.wait_for(process.communicate(), 5)
                    for task in daemon_tasks:
                        task.cancel()
                    await asyncio.gather(*daemon_tasks, return_exceptions=True)
