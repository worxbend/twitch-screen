"""Shutdown failures must not skip another client or signal-handler cleanup."""
import asyncio
import signal
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from twitch_screen_client.config import parse
from twitch_screen_client.delivery import Delivery
from twitch_screen_client.runtime import run


class ShutdownTests(unittest.IsolatedAsyncioTestCase):
    async def test_both_http_clients_close_when_first_close_fails(self):
        closed = []

        async def first():
            closed.append('first')
            raise OSError('synthetic close failure')

        async def second():
            await asyncio.sleep(0)
            closed.append('second')

        delivery = object.__new__(Delivery)
        clients = patch.object(delivery, 'exchanges',
                               [SimpleNamespace(close=first), SimpleNamespace(close=second)], create=True)
        clients.start()
        self.addCleanup(clients.stop)
        with self.assertRaises(OSError):
            await Delivery.close(delivery)
        self.assertEqual(closed, ['first', 'second'])

    async def test_other_client_closes_even_if_first_close_stalls(self):
        started, closed, cancelled = asyncio.Event(), asyncio.Event(), asyncio.Event()

        async def first():
            started.set()
            try:
                await asyncio.Future()
            finally:
                cancelled.set()

        async def second():
            closed.set()

        delivery = object.__new__(Delivery)
        clients = patch.object(delivery, 'exchanges',
                               [SimpleNamespace(close=first), SimpleNamespace(close=second)], create=True)
        clients.start()
        self.addCleanup(clients.stop)
        task = asyncio.create_task(Delivery.close(delivery))
        try:
            await asyncio.wait_for(started.wait(), 1)
            await asyncio.wait_for(closed.wait(), .1)
        finally:
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)
        self.assertTrue(cancelled.is_set())

    async def check_runtime_cleanup(self, failure):
        loop = asyncio.get_running_loop()
        handlers, removed, cancelled, snapshots = {}, [], [], []

        async def capture_worker():
            handlers[signal.SIGTERM]()
            try:
                await asyncio.Future()
            finally:
                cancelled.append('capture')

        async def waiting_worker(name):
            try:
                await asyncio.Future()
            finally:
                cancelled.append(name)

        class FakeDelivery:
            def __init__(self, *args):
                self.cpu = None

            def submit(self, card):
                raise AssertionError('no real desktop or notification input expected')

            async def notifications(self):
                await waiting_worker('notifications')

            async def metrics(self):
                await waiting_worker('metrics')

            async def close(self):
                if failure == 'stall':
                    try:
                        await asyncio.Future()
                    finally:
                        cancelled.append('close')
                else:
                    raise failure

        config = parse({'relay_url': 'http://localhost', 'source_id': 'shutdown', 'cpu_enabled': False})
        with (patch.object(loop, 'add_signal_handler', side_effect=lambda sig, callback: handlers.update({sig: callback})),
              patch.object(loop, 'remove_signal_handler', side_effect=lambda sig: removed.append(sig)),
              patch('twitch_screen_client.runtime.Capture', return_value=SimpleNamespace(run=capture_worker)),
              patch('twitch_screen_client.runtime.Delivery', FakeDelivery),
              patch('twitch_screen_client.runtime.write_status', side_effect=lambda path, value: snapshots.append(dict(value)))):
            expected = TimeoutError if failure == 'stall' else type(failure)
            async with asyncio.timeout(5):
                with self.assertRaises(expected):
                    await run(config, None)
        self.assertEqual(removed, [signal.SIGTERM, signal.SIGINT])
        self.assertEqual(set(cancelled) - {'close'}, {'capture', 'notifications', 'metrics'})
        self.assertTrue(snapshots)
        self.assertEqual([snapshots[-1][key] for key in ('capture', 'cpu', 'relay')], ['stopped'] * 3)
        if failure == 'stall':
            self.assertIn('close', cancelled)

    async def test_runtime_releases_handlers_after_client_close_error(self):
        await self.check_runtime_cleanup(OSError('synthetic close failure'))

    async def test_runtime_releases_handlers_after_real_close_timeout(self):
        await self.check_runtime_cleanup('stall')

    async def check_startup_failure(self, failure_index):
        loop = asyncio.get_running_loop()
        registered, removed, closed, snapshots = [], [], [], []

        def add(sig, callback):
            if len(registered) == failure_index:
                raise OSError('synthetic signal registration failure')
            registered.append(sig)

        class FakeDelivery:
            def __init__(self, *args):
                self.cpu = None

            def submit(self, card):
                raise AssertionError('startup must not capture notifications')

            async def close(self):
                closed.append('delivery')

        config = parse({'relay_url': 'http://localhost', 'source_id': 'startup', 'cpu_enabled': False})
        with (patch.object(loop, 'add_signal_handler', side_effect=add),
              patch.object(loop, 'remove_signal_handler', side_effect=lambda sig: removed.append(sig)),
              patch('twitch_screen_client.runtime.Delivery', FakeDelivery),
              patch('twitch_screen_client.runtime.write_status', side_effect=lambda path, value: snapshots.append(dict(value)))):
            with self.assertRaisesRegex(OSError, 'synthetic signal registration failure'):
                await run(config, None)
        self.assertEqual(closed, ['delivery'], 'startup failure must close owned HTTP clients')
        self.assertEqual(removed, registered, 'remove only successfully registered handlers')
        self.assertEqual(len(snapshots), 1)
        self.assertEqual([snapshots[0][key] for key in ('capture', 'cpu', 'relay')], ['stopped'] * 3)

    async def test_first_signal_registration_failure_closes_delivery(self):
        await self.check_startup_failure(0)

    async def test_partial_signal_registration_failure_releases_owned_handler(self):
        await self.check_startup_failure(1)
