"""Original-age CPU backoff and replacement handoff, with no API polling."""
import asyncio
import unittest

from test_runtime import configuration
from twitch_screen_client.delivery import Delivery
from twitch_screen_client.metrics import boottime_ms
from twitch_screen_client.queue import CardQueue
from twitch_screen_client.runtime import initial_status


class RetryExpiryTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.delivery = Delivery(configuration('http://localhost'), boottime_ms,
                                 CardQueue(boottime_ms), initial_status())
        self.addAsyncCleanup(self.delivery.close)

    async def test_expired_cpu_is_removed_before_retry_budget_ends(self):
        self.delivery.cpu = (boottime_ms() + 20, {'cpuPercent': 1})
        task = asyncio.create_task(self.delivery.wait_cpu_retry(.2))
        try:
            async with asyncio.timeout(.15):
                while self.delivery.cpu is not None:
                    await asyncio.sleep(.005)
            self.assertFalse(task.done(), 'expiry must not bypass the retry budget')
            self.assertEqual(self.delivery.status['cpu_expired'], 1)
            await task
            self.assertEqual(self.delivery.status['cpu_expired'], 1)
        finally:
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)

    async def test_replacement_wakes_pruning_but_never_resets_backoff(self):
        started = asyncio.get_running_loop().time()
        task = asyncio.create_task(self.delivery.wait_cpu_retry(.15))
        try:
            for percent in range(5):
                self.delivery.cpu = (boottime_ms() + 10, {'cpuPercent': percent})
                self.delivery.cpu_event.set()
                await asyncio.sleep(.02)
                self.assertIsNone(self.delivery.cpu)
            await asyncio.wait_for(task, .2)
            elapsed = asyncio.get_running_loop().time() - started
            self.assertGreaterEqual(elapsed, .14)
            self.assertLess(elapsed, .3)
            self.assertEqual(self.delivery.status['cpu_expired'], 5)
        finally:
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)

    async def test_newest_fresh_snapshot_retains_original_deadline(self):
        self.delivery.cpu = (boottime_ms() + 10, {'cpuPercent': 1})
        task = asyncio.create_task(self.delivery.wait_cpu_retry(.08))
        try:
            await asyncio.sleep(.025)
            deadline = boottime_ms() + 1000
            observation = (deadline, {'cpuPercent': 99})
            self.delivery.cpu = observation
            self.delivery.cpu_event.set()
            await task
            self.assertIs(self.delivery.cpu, observation)
            self.assertEqual(self.delivery.cpu[0], deadline)
            self.assertEqual(self.delivery.status['cpu_expired'], 1)
        finally:
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)

    async def test_metrics_worker_prunes_failed_sample_and_sends_only_newest(self):
        calls = []
        attempted = asyncio.Event()

        async def upload(exchange, method, path, payload, deadline, observation=None):
            calls.append((payload['cpuPercent'], deadline))
            attempted.set()
            return 'retry' if len(calls) == 1 else 'accepted'

        self.delivery.upload = upload
        self.delivery.cpu = (boottime_ms() + 20, {'cpuPercent': 1})
        task = asyncio.create_task(self.delivery.metrics())
        try:
            await asyncio.wait_for(attempted.wait(), .2)
            async with asyncio.timeout(.2):
                while self.delivery.cpu is not None:
                    await asyncio.sleep(.005)
            self.assertEqual(self.delivery.status['cpu_expired'], 1)
            for percent in range(2, 100):
                self.delivery.sample(boottime_ms(), {'cpuPercent': percent})
            newest_deadline = self.delivery.cpu[0]
            await asyncio.sleep(.05)
            self.assertEqual(len(calls), 1, 'replacement must not bypass failure backoff')
            async with asyncio.timeout(2):
                while self.delivery.status['cpu_accepted'] != 1:
                    await asyncio.sleep(.01)
            self.assertEqual(calls[1], (99, newest_deadline))
            self.assertEqual(len(calls), 2)
            self.assertIsNone(self.delivery.cpu)
        finally:
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)

    async def test_cancellation_interrupts_long_backoff(self):
        task = asyncio.create_task(self.delivery.wait_cpu_retry(30))
        await asyncio.sleep(0)
        task.cancel()
        with self.assertRaises(asyncio.CancelledError):
            await asyncio.wait_for(task, .2)
