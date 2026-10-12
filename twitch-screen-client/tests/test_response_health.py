"""Malformed real HTTP responses must not preserve healthy delivery diagnostics."""
import asyncio
import unittest

from test_runtime import Relay, configuration, FIXTURES
from twitch_screen_client.clock import Calibration
from twitch_screen_client.delivery import Delivery
from twitch_screen_client.metrics import boottime_ms
from twitch_screen_client.queue import CardQueue
from twitch_screen_client.runtime import initial_status

CARD = {**FIXTURES['notification_bodyless'], 'ttlMs': 8000, 'expiresInMs': 60000}


class ResponseHealthTests(unittest.IsolatedAsyncioTestCase):
    async def make_delivery(self, peer):
        delivery = Delivery(configuration(peer.url), boottime_ms,
                            CardQueue(boottime_ms), initial_status())
        self.addAsyncCleanup(delivery.close)
        return delivery

    async def test_corrupt_real_response_clears_only_used_calibration_and_health(self):
        async with Relay() as peer:
            delivery = await self.make_delivery(peer)
            for method, payload in (('POST', CARD), ('PUT', {'sourceId': 'test-desktop',
                    'host': 'Synthetic host', 'cpuPercent': 25, 'freshnessMs': 10000})):
                with self.subTest(method=method):
                    peer.mode = 'normal'
                    path = '/api/v1/notifications' if method == 'POST' else '/api/v1/cpu'
                    self.assertEqual(await delivery.upload(delivery.exchanges[0], method, path,
                                     payload, boottime_ms() + 10000), 'accepted')
                    self.assertEqual(delivery.status['relay'], 'available')
                    self.assertIsNotNone(delivery.calibration)
                    peer.mode = 'corrupt'
                    outcome = await delivery.upload(delivery.exchanges[0], method, path,
                                                    payload, boottime_ms() + 10000)
                    self.assertEqual(outcome, 'ambiguous' if method == 'POST' else 'retry')
                    self.assertEqual(delivery.status['relay'], 'invalid_response')
                    self.assertIsNone(delivery.calibration)
                    boundary = len(peer.requests)
                    peer.mode = 'normal'
                    self.assertEqual(await delivery.upload(delivery.exchanges[0], method, path,
                                     payload, boottime_ms() + 10000), 'accepted')
                    self.assertEqual([row[0] for row in peer.requests[boundary:]], ['GET', method])

    async def test_malformed_late_response_does_not_erase_newer_worker_calibration(self):
        async with Relay() as peer:
            delivery = await self.make_delivery(peer)
            peer.mode = 'corrupt'
            exchange = delivery.exchanges[0]
            original = exchange.request
            newer = Calibration.checked('fedcba9876543210', 2000,
                                        boottime_ms(), boottime_ms())
            async def late(method, path, payload=None):
                result = await original(method, path, payload)
                if method == 'POST':
                    delivery.calibration = newer
                return result
            exchange.request = late
            self.assertEqual(await delivery.upload(exchange, 'POST', '/api/v1/notifications',
                             CARD, boottime_ms() + 10000), 'ambiguous')
            self.assertIs(delivery.calibration, newer)
            self.assertEqual(delivery.status['relay'], 'invalid_response')

    async def test_malformed_post_worker_drops_without_resend_and_cpu_recovers(self):
        from twitch_screen_client.normalize import Card
        async with Relay() as peer:
            delivery = await self.make_delivery(peer)
            peer.mode = 'corrupt'
            delivery.submit(Card(CARD, boottime_ms() + 60000, 100))
            worker = asyncio.create_task(delivery.notifications())
            try:
                async with asyncio.timeout(2):
                    while delivery.status['notification_ambiguous'] != 1:
                        await asyncio.sleep(.005)
                self.assertEqual(delivery.status['relay'], 'invalid_response')
                peer.mode = 'normal'
                cpu = {'sourceId': 'test-desktop', 'host': 'Synthetic host',
                       'cpuPercent': 25, 'memoryPercent': 50, 'freshnessMs': 10000}
                self.assertEqual(await delivery.upload(delivery.exchanges[1], 'PUT', '/api/v1/cpu',
                                 cpu, boottime_ms() + 10000), 'accepted')
                self.assertEqual(delivery.status['relay'], 'available')
                self.assertEqual([row[0] for row in peer.requests], ['GET', 'POST', 'GET', 'PUT'])
                self.assertEqual(delivery.status['notification_ambiguous'], 1)
                self.assertEqual(delivery.status['notification_accepted'], 0)
                self.assertFalse(delivery.cards.cards)
            finally:
                worker.cancel()
                await asyncio.gather(worker, return_exceptions=True)
