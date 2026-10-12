"""Foreground process remains bounded during a hung relay and synthetic bursts."""
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


class OutageChildTests(unittest.IsolatedAsyncioTestCase):
    async def test_hung_relay_preserves_delivery_bounds_and_signal_shutdown(self):
        async with PrivateBus() as bus, Relay() as relay:
            relay.mode = 'hang'
            with tempfile.TemporaryDirectory(dir=os.environ['TMPDIR'], prefix='co-') as directory:
                root = Path(directory)
                root.chmod(0o700)
                config = root / 'config.toml'
                config.write_text(f'relay_url = "{relay.url}"\nsource_id = "outage-child"\nsample_interval_ms = 1000\nnotifications_enabled = true\ninclude_apps = ["twitch-screen-e2e"]\n')
                daemon = await bus.connect('test-owned notification daemon')
                await daemon.call(bus_call('RequestName', 'su', [NAME, 0]))
                producer = await bus.connect('test-owned notification producer')
                deliveries = []

                async def serve():
                    while True:
                        message = await daemon.receive()
                        if message.message_type == MessageType.METHOD_CALL and message.member == 'Notify':
                            deliveries.append(message.serial)
                            await daemon.send(Message.new_method_return(message, signature='u', body=[42]))

                daemon_task = asyncio.create_task(serve())
                environment = {**os.environ, 'DBUS_SESSION_BUS_ADDRESS': 'unix:path=' + bus.path,
                               'XDG_RUNTIME_DIR': directory,
                               'PYTHONPATH': '' if os.environ.get('TSC_TEST_INSTALLED') == '1' else str(ROOT / 'twitch-screen-client/src')}
                process = await asyncio.create_subprocess_exec(sys.executable, '-m', 'twitch_screen_client', 'run', '--config', str(config),
                    env=environment, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
                status_path = root / 'twitch-screen-client-outage-child.json'
                try:
                    async with asyncio.timeout(5):
                        while not status_path.exists() or json.loads(status_path.read_text())['capture'] != 'available':
                            await asyncio.sleep(.01)
                    # All replies are real daemon returns, not monitor or HTTP acknowledgments.
                    async with asyncio.timeout(3):
                        for index in range(40):
                            reply = await producer.call(Message(destination=NAME, path=PATH, interface=NAME, member='Notify',
                                signature='susssasa{sv}i', body=['twitch-screen-e2e', 0, '', f'Synthetic burst {index}',
                                                              'PRIVATE SYNTHETIC BODY', [], {}, 0]))
                            self.assertEqual(reply.body, [42])
                    await relay.wait_path('/api/v1/cpu')
                    await relay.wait_path('/api/v1/notifications')
                    async with asyncio.timeout(3):
                        while True:
                            status = json.loads(status_path.read_text())
                            # A real scheduling gap correctly rejects its sample. Require
                            # measured recovery instead of assuming an earlier PUT means
                            # this later independent diagnostics snapshot is available.
                            if status['captured'] == 40 and status['cpu'] == 'available':
                                break
                            await asyncio.sleep(.02)
                    self.assertEqual(len(deliveries), 40)
                    self.assertGreaterEqual(status['notification_refused'], 7)
                    self.assertLessEqual(status['pendingNotifications'], 32)
                    self.assertLessEqual(status['pendingNotificationBytes'], 65536)
                    self.assertEqual(status['capture'], 'available')
                    self.assertEqual(status['cpu'], 'available')
                    self.assertEqual(status['cpu_accepted'], 0)
                    self.assertIsNone(process.returncode)
                    process.send_signal(signal.SIGTERM)
                    stdout, stderr = await asyncio.wait_for(process.communicate(), 5)
                    self.assertEqual(process.returncode, 0, stderr.decode())
                    self.assertEqual(stderr, b'')
                    self.assertNotIn(b'PRIVATE SYNTHETIC', stdout + stderr + status_path.read_bytes())
                    stopped = json.loads(status_path.read_text())
                    self.assertEqual(stopped['capture'], 'stopped')
                    self.assertEqual(stopped['cpu'], 'stopped')
                    # A second foreground process acquires the released source lock.
                    # No pending queue is persisted or revived by this restart.
                    relay.mode = 'normal'
                    config.write_text(f'relay_url = "{relay.url}"\nsource_id = "outage-child"\nsample_interval_ms = 1000\n')
                    restarted = await asyncio.create_subprocess_exec(sys.executable, '-m', 'twitch_screen_client', 'run', '--config', str(config),
                        env=environment, stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
                    try:
                        async with asyncio.timeout(4):
                            while json.loads(status_path.read_text())['pid'] != restarted.pid:
                                await asyncio.sleep(.02)
                        fresh = json.loads(status_path.read_text())
                        self.assertEqual(fresh['pendingNotifications'], 0)
                        self.assertEqual(fresh['pendingNotificationBytes'], 0)
                        self.assertEqual(fresh['captured'], 0)
                        self.assertEqual(fresh['capture'], 'disabled')
                        restarted.send_signal(signal.SIGINT)
                        _, error = await asyncio.wait_for(restarted.communicate(), 5)
                        self.assertEqual(restarted.returncode, 0, error.decode())
                    finally:
                        if restarted.returncode is None:
                            restarted.terminate()
                            await asyncio.wait_for(restarted.communicate(), 5)
                finally:
                    if process.returncode is None:
                        process.terminate()
                        await asyncio.wait_for(process.communicate(), 5)
                    daemon_task.cancel()
                    await asyncio.gather(daemon_task, return_exceptions=True)
