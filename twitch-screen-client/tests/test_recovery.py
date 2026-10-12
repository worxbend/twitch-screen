"""Capture recovers from owned bus termination; no desktop bus involved."""
import asyncio
import unittest

from dbus_fast import Message, MessageType
from test_transport import PrivateBus, NAME, PATH
from test_runtime import configuration
from twitch_screen_client.capture import Capture
from twitch_screen_client.dbus_transport import bus_call
from twitch_screen_client.runtime import initial_status


class RecoveryTests(unittest.IsolatedAsyncioTestCase):
    async def test_historical_monitor_denial_does_not_mask_later_bus_loss(self):
        async with PrivateBus(denied=True) as bus:
            status = initial_status()
            capture = Capture(configuration('http://localhost'), lambda card: None, status,
                              address='unix:path=' + bus.path)
            task = asyncio.create_task(capture.run())
            try:
                async with asyncio.timeout(5):
                    while status['capture'] != 'monitor_denied':
                        await asyncio.sleep(.01)
                failures = int(status['capture_failures'])
                bus.process.terminate()
                await asyncio.wait_for(bus.process.communicate(), 3)
                async with asyncio.timeout(5):
                    while int(status['capture_failures']) <= failures:
                        await asyncio.sleep(.01)
                self.assertEqual(status['capture'], 'unavailable')
                self.assertEqual(status['captured'], 0)
                self.assertEqual(capture.connections, [])
            finally:
                task.cancel()
                await asyncio.wait_for(asyncio.gather(task, return_exceptions=True), 1)

    async def test_bus_loss_reconnects_capture_and_preserves_normal_delivery(self):
        async with PrivateBus() as recovered:
            server = await recovered.connect('recovered fake daemon')
            await server.call(bus_call('RequestName', 'su', [NAME, 0]))
            cards = []
            status = initial_status()
            capture = Capture(configuration('http://localhost'), cards.append, status)
            task = None
            try:
                async with PrivateBus() as initial:
                    first = await initial.connect('initial fake daemon')
                    await first.call(bus_call('RequestName', 'su', [NAME, 0]))
                    capture.address = 'unix:path=' + initial.path
                    task = asyncio.create_task(capture.run())
                    async with asyncio.timeout(3):
                        while status['capture'] != 'available':
                            await asyncio.sleep(.01)
                    generation = capture.generation
                # Only the test-owned first bus stopped. Reconnect points at second private bus.
                capture.address = 'unix:path=' + recovered.path
                async with asyncio.timeout(5):
                    while (not capture.connections or capture.connections[0].path != recovered.path
                           or status['capture'] != 'available'):
                        await asyncio.sleep(.01)
                self.assertGreater(capture.generation, generation)
                self.assertGreaterEqual(status['capture_failures'], 1)
                producer = await recovered.connect('synthetic producer')
                async def serve_once():
                    while True:
                        message = await server.receive()
                        if message.message_type == MessageType.METHOD_CALL and message.member == 'Notify':
                            await server.send(Message.new_method_return(message, signature='u', body=[99]))
                            return
                server_task = asyncio.create_task(serve_once())
                reply = await asyncio.wait_for(producer.call(Message(destination=NAME, path=PATH, interface=NAME,
                    member='Notify', signature='susssasa{sv}i', body=['twitch-screen-e2e', 0, '', 'Recovered', '', [], {}, 0])), 3)
                await server_task
                self.assertEqual(reply.body, [99])
                async with asyncio.timeout(3):
                    while not cards:
                        await asyncio.sleep(.01)
                self.assertEqual(cards[0].payload['title'], 'Recovered')
            finally:
                if task is not None:
                    task.cancel()
                    await asyncio.gather(task, return_exceptions=True)
                self.assertEqual(capture.connections, [])
