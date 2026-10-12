"""Latest-only CPU preparation across calibration awaits; synthetic HTTP only."""
import asyncio
import unittest
from unittest.mock import patch

import httpx

from test_runtime import Relay, configuration
from twitch_screen_client.delivery import Delivery
from twitch_screen_client.metrics import boottime_ms
from twitch_screen_client.queue import CardQueue
from twitch_screen_client.runtime import initial_status


class CpuHandoffTests(unittest.IsolatedAsyncioTestCase):
    async def create_delivery(self, peer):
        delivery = Delivery(configuration(peer.url), boottime_ms,
                            CardQueue(boottime_ms), initial_status())
        self.addAsyncCleanup(delivery.close)
        return delivery

    async def start_worker(self, delivery):
        task = asyncio.create_task(delivery.metrics())
        async def stop():
            task.cancel()
            await asyncio.gather(task, return_exceptions=True)
        self.addAsyncCleanup(stop)
        return task

    async def wait_accepted(self, delivery):
        async with asyncio.timeout(3):
            while delivery.status['cpu_accepted'] != 1:
                await asyncio.sleep(.005)

    def hold_clock_response(self, delivery):
        entered, release = asyncio.Event(), asyncio.Event()
        exchange = delivery.exchanges[1]
        request = exchange.request
        async def held(method, path, payload=None):
            result = await request(method, path, payload)
            if method == 'GET':
                entered.set()
                await release.wait()
            return result
        exchange.request = held
        self.addCleanup(release.set)
        return entered, release

    async def test_replacement_during_clock_exchange_sends_only_latest(self):
        async with Relay() as peer:
            delivery = await self.create_delivery(peer)
            entered, release = self.hold_clock_response(delivery)
            delivery.sample(boottime_ms(), {'cpuPercent': 1})
            await self.start_worker(delivery)
            await asyncio.wait_for(entered.wait(), 1)
            self.assertFalse(any(method == 'PUT' for method, _, _ in peer.requests))
            delivery.sample(boottime_ms(), {'cpuPercent': 99})
            assert delivery.cpu is not None
            deadline = delivery.cpu[0]
            release.set()
            await self.wait_accepted(delivery)
            puts = [value for method, _, value in peer.requests if method == 'PUT']
            self.assertEqual([value['cpuPercent'] for value in puts], [99])
            self.assertLess(puts[0]['deadline']['monotonicMs'], deadline)
            self.assertEqual(delivery.status['cpu_dropped'], 0)
            self.assertIsNone(delivery.cpu)

    async def test_replacement_while_waiting_for_other_worker_calibration_lock(self):
        async with Relay() as peer:
            delivery = await self.create_delivery(peer)
            await delivery.calibration_lock.acquire()
            try:
                delivery.sample(boottime_ms(), {'cpuPercent': 1})
                await self.start_worker(delivery)
                # Prove the selected observation reached the shared lock wait.
                async with asyncio.timeout(1):
                    while not delivery.calibration_lock._waiters:
                        await asyncio.sleep(0)
                self.assertEqual(peer.requests, [])
                delivery.sample(boottime_ms(), {'cpuPercent': 99})
            finally:
                delivery.calibration_lock.release()
            await self.wait_accepted(delivery)
            self.assertEqual([value['cpuPercent'] for method, _, value in peer.requests
                              if method == 'PUT'], [99])

    async def test_original_expiry_during_calibration_prevents_put(self):
        async with Relay() as peer:
            delivery = await self.create_delivery(peer)
            entered, release = self.hold_clock_response(delivery)
            delivery.sample(boottime_ms(), {'cpuPercent': 1})
            assert delivery.cpu is not None
            _, payload = delivery.cpu
            delivery.cpu = (boottime_ms() + 80, payload)
            await self.start_worker(delivery)
            await asyncio.wait_for(entered.wait(), 1)
            await asyncio.sleep(.1)
            release.set()
            async with asyncio.timeout(1):
                while delivery.cpu is not None:
                    await asyncio.sleep(.005)
            self.assertFalse(any(method == 'PUT' for method, _, _ in peer.requests))
            self.assertEqual(delivery.status['cpu_accepted'], 0)

    async def test_superseded_preparation_preserves_existing_failure_backoff(self):
        async with Relay() as peer:
            delivery = await self.create_delivery(peer)
            entered, release = self.hold_clock_response(delivery)
            exchange = delivery.exchanges[1]
            request = exchange.request
            failed, retry_started = asyncio.Event(), asyncio.Event()
            waits = []
            original_wait = delivery.wait_cpu_retry
            async def wait(delay):
                waits.append(delay)
                retry_started.set()
                await original_wait(.08)
            delivery.wait_cpu_retry = wait
            attempts = []
            async def failing(method, path, payload=None):
                if method == 'PUT':
                    assert payload is not None
                    attempts.append(payload['cpuPercent'])
                    if len(attempts) <= 2:
                        failed.set()
                        delivery.calibration = None
                        raise httpx.ConnectError('synthetic pre-send failure')
                return await request(method, path, payload)
            exchange.request = failing
            # Initial preparation does not wait; recovery calibration will wait.
            release.set()
            delivery.sample(boottime_ms(), {'cpuPercent': 1})
            with patch('twitch_screen_client.delivery.random.uniform', return_value=1):
                await self.start_worker(delivery)
                await asyncio.wait_for(retry_started.wait(), 1)
                entered.clear()
                release.clear()
                await asyncio.wait_for(entered.wait(), 1)
                delivery.sample(boottime_ms(), {'cpuPercent': 99})
                release.set()
                await self.wait_accepted(delivery)
            self.assertEqual(attempts, [1, 99, 99])
            self.assertEqual(waits, [1, 2], 'superseded preparation must not reset failure backoff')
            self.assertEqual([value['cpuPercent'] for method, _, value in peer.requests
                              if method == 'PUT'], [99])

    async def test_replacement_after_put_started_remains_for_next_exchange(self):
        async with Relay() as peer:
            delivery = await self.create_delivery(peer)
            entered, release = asyncio.Event(), asyncio.Event()
            request = delivery.exchanges[1].request
            async def held(method, path, payload=None):
                result = await request(method, path, payload)
                if method == 'PUT' and not entered.is_set():
                    entered.set()
                    await release.wait()
                return result
            delivery.exchanges[1].request = held
            self.addCleanup(release.set)
            delivery.sample(boottime_ms(), {'cpuPercent': 1})
            await self.start_worker(delivery)
            await asyncio.wait_for(entered.wait(), 1)
            delivery.sample(boottime_ms(), {'cpuPercent': 99})
            release.set()
            async with asyncio.timeout(3):
                while delivery.status['cpu_accepted'] != 2:
                    await asyncio.sleep(.005)
            self.assertEqual([value['cpuPercent'] for method, _, value in peer.requests
                              if method == 'PUT'], [1, 99])
            self.assertIsNone(delivery.cpu)
