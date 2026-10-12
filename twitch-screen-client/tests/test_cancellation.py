"""A dependency swallowing cancellation must not orphan owned send workers."""
import asyncio
from types import SimpleNamespace
import unittest

from test_runtime import configuration
from twitch_screen_client.delivery import Delivery
from twitch_screen_client.metrics import boottime_ms
from twitch_screen_client.normalize import Card
from twitch_screen_client.queue import CardQueue
from twitch_screen_client.runtime import initial_status


class CancellationTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.status = initial_status()
        self.delivery = Delivery(configuration('http://localhost'), boottime_ms,
                                 CardQueue(boottime_ms), self.status)
        self.addAsyncCleanup(self.delivery.close)

    async def test_exchange_reasserts_cancellation_swallowed_by_dependency(self):
        started = asyncio.Event()

        async def request(*args):
            started.set()
            try:
                await asyncio.Future()
            except asyncio.CancelledError:
                return (200, {}, b'{}', boottime_ms(), boottime_ms())

        task = asyncio.create_task(self.delivery.clock_exchange(SimpleNamespace(request=request),
                                                               'GET', '/api/v1/clock'))
        await started.wait()
        task.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await asyncio.wait_for(task, .2)

    async def check_worker(self, kind):
        started = asyncio.Event()
        completed = asyncio.Event()

        async def swallowed(*args, **kwargs):
            started.set()
            try:
                await asyncio.Future()
            except asyncio.CancelledError:
                completed.set()
                return 'accepted'

        self.delivery.upload = swallowed
        if kind == 'cpu':
            self.delivery.sample(boottime_ms(), {'cpuPercent': 1})
            worker = self.delivery.metrics()
        else:
            self.delivery.submit(Card({'type': 'message', 'title': 'Synthetic', 'body': ''},
                                      boottime_ms() + 60000, 100))
            worker = self.delivery.notifications()
        task = asyncio.create_task(worker)
        try:
            await started.wait()
            task.cancel()
            await completed.wait()
            # Observe without sending a second cancellation, which would hide
            # the original cancellation loss in an unbounded idle event wait.
            done, _ = await asyncio.wait({task}, timeout=.1)
            self.assertIn(task, done, 'cancelled worker entered another idle wait')
            self.assertTrue(task.cancelled())
            self.assertEqual(self.status['cpu_accepted'], 0)
            self.assertEqual(self.status['notification_accepted'], 0)
        finally:
            if not task.done():
                task.cancel()
            await asyncio.gather(task, return_exceptions=True)

    async def test_cpu_owner_does_not_idle_or_commit_after_swallowed_cancel(self):
        await self.check_worker('cpu')

    async def test_notification_owner_does_not_idle_or_commit_after_swallowed_cancel(self):
        await self.check_worker('notification')
