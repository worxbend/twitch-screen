"""Peer-review regressions using independent clocks and synthetic peers only."""
import asyncio
import json
import unittest
import test_boundaries

from test_runtime import EPOCH, FIXTURES, configuration, Relay
from twitch_screen_client.clock import Calibration
from twitch_screen_client.delivery import Delivery
from twitch_screen_client.metrics import boottime_ms
from twitch_screen_client.queue import CardQueue
from twitch_screen_client.responses import accepted, number
from twitch_screen_client.runtime import initial_status

CARD = {**FIXTURES['notification_bodyless'], 'ttlMs': 8000, 'expiresInMs': 60000}


class FakeExchange:
    def __init__(self, now):
        self.now = now
        self.requests = []
        self.unavailable = False
        self.suspend_on: str | None = None

    async def request(self, method, path, payload=None):
        sent = self.now[0]
        self.requests.append((method, payload))
        await asyncio.sleep(0)
        if method == self.suspend_on:
            self.now[0] += 20000
        if method == 'GET':
            if self.unavailable:
                raise ValueError('synthetic unavailable clock')
            value = {'relayEpoch': EPOCH, 'monotonicMs': 1000}
        elif method == 'POST':
            assert payload is not None
            value = {**FIXTURES['notification_accepted'], **{key: payload[key] for key in
                     ('type', 'title', 'body', 'ttlMs', 'expiresInMs')}}
        else:
            assert payload is not None
            value = {**FIXTURES['cpu_fresh'], 'data': {**FIXTURES['cpu_fresh']['data'],
                     **{key: value for key, value in payload.items() if key != 'deadline'}}}
        headers = {'cache-control': 'no-store'} if method == 'GET' else {
            'x-relay-epoch': EPOCH, 'x-relay-monotonic-ms': '1000'}
        return 200, headers, json.dumps(value).encode(), sent, self.now[0]


class SuspendTests(unittest.IsolatedAsyncioTestCase):
    async def make_delivery(self):
        now, active = [1000], [1000]
        delivery = Delivery(configuration('http://localhost'), lambda: now[0],
                            CardQueue(lambda: now[0]), initial_status(), lambda: active[0])
        self.addAsyncCleanup(delivery.close)
        delivery.calibration = Calibration.checked(EPOCH, 1000, 1000, 1000, 1000)
        return delivery, now, active

    async def test_short_suspend_recalibrates_both_workers_without_renewal(self):
        delivery, now, active = await self.make_delivery()
        now[0] = 21000
        exchanges = [FakeExchange(now), FakeExchange(now)]
        card = CARD
        cpu = {key: value for key, value in FIXTURES['cpu_fresh']['data'].items() if value is not None}
        results = await asyncio.gather(
            delivery.upload(exchanges[0], 'POST', '/api/v1/notifications', card, 61000),
            delivery.upload(exchanges[1], 'PUT', '/api/v1/cpu', cpu, 61000))
        self.assertEqual(results, ['accepted', 'accepted'])
        self.assertEqual(sum(method == 'GET' for e in exchanges for method, _ in e.requests), 1)
        for exchange in exchanges:
            deadline = exchange.requests[-1][1]['deadline']['monotonicMs']
            self.assertLessEqual(deadline, 40910)
        self.assertEqual(active[0], 1000)

    async def test_long_suspend_discards_expired_work(self):
        delivery, now, active = await self.make_delivery()
        now[0] = 71000
        exchange = FakeExchange(now)
        self.assertEqual(await delivery.upload(exchange, 'POST', '/api/v1/notifications',
                         CARD, 61000), 'retry')
        self.assertEqual([method for method, _ in exchange.requests], ['GET'])

    async def test_unavailable_recalibration_never_uploads(self):
        delivery, now, active = await self.make_delivery()
        now[0] = 21000
        exchange = FakeExchange(now)
        exchange.unavailable = True
        for method, payload in (('POST', CARD), ('PUT', FIXTURES['cpu_fresh']['data'])):
            self.assertEqual(await delivery.upload(exchange, method, '/unused', payload, 61000), 'retry')
        self.assertTrue(all(method == 'GET' for method, _ in exchange.requests))
        self.assertIsNone(delivery.calibration)

    async def test_suspend_during_clock_or_upload_cannot_refresh(self):
        for method in ('GET', 'POST', 'PUT'):
            with self.subTest(method=method):
                delivery, now, active = await self.make_delivery()
                if method == 'GET':
                    delivery.calibration = None
                exchange = FakeExchange(now)
                exchange.suspend_on = method
                operation = 'PUT' if method == 'PUT' else 'POST'
                payload = FIXTURES['cpu_fresh']['data'] if operation == 'PUT' else CARD
                outcome = await delivery.upload(exchange, operation, '/unused', payload, 61000)
                self.assertEqual(outcome, 'ambiguous' if method == 'POST' else 'retry')
                self.assertIsNone(delivery.calibration)

    async def test_same_epoch_recalibration_cannot_extend_retry(self):
        delivery, now, active = await self.make_delivery()
        exchange = FakeExchange(now)
        await delivery.upload(exchange, 'POST', '/unused', CARD, 61000)
        first = exchange.requests[-1][1]['deadline']['monotonicMs']
        delivery.calibration = Calibration.checked(EPOCH, 5000, 1000, 1000, 1000)
        await delivery.upload(exchange, 'POST', '/unused', CARD, 61000)
        self.assertLessEqual(exchange.requests[-1][1]['deadline']['monotonicMs'], first)


class NumericTests(unittest.TestCase):
    def test_all_numeric_fields_reject_bounded_huge_integers(self):
        for field in ('cpuPercent', 'memoryPercent', 'temperatureCelsius'):
            for value in (10**400, -(10**400), float('inf'), float('-inf'), float('nan'), True, {}, []):
                with self.subTest(field=field, value_type=type(value).__name__):
                    response = {**FIXTURES['cpu_fresh'], 'data': {**FIXTURES['cpu_fresh']['data'], field: value}}
                    self.assertFalse(accepted(response, 'PUT', FIXTURES['cpu_fresh']['data']))
        self.assertTrue(number(0, 0, 100))


class MalformedChildTests(test_boundaries.FullChildTests):
    """Actual daemon capture remains operational after malformed CPU acceptance."""
    relay_mode = 'numeric'


class WorkerTests(unittest.IsolatedAsyncioTestCase):
    async def test_malformed_cpu_keeps_taskgroup_and_notifications_alive(self):
        async with Relay() as relay:
            delivery = Delivery(configuration(relay.url), boottime_ms, CardQueue(boottime_ms), initial_status())
            exchange = delivery.exchanges[1]
            original = exchange.request
            malformed = asyncio.Event()
            async def request(method, path, payload=None):
                result = await original(method, path, payload)
                if method == 'PUT':
                    code, headers, body, sent, received = result
                    value = json.loads(body)
                    value['data']['cpuPercent'] = 10**400
                    malformed.set()
                    return code, headers, json.dumps(value).encode(), sent, received
                return result
            exchange.request = request
            tasks = []
            try:
                async with asyncio.TaskGroup() as group:
                    tasks = [group.create_task(delivery.metrics()), group.create_task(delivery.notifications())]
                    delivery.sample(boottime_ms(), {'cpuPercent': 1, 'memoryPercent': 2})
                    await asyncio.wait_for(malformed.wait(), 3)
                    await asyncio.sleep(.05)
                    self.assertFalse(tasks[0].done())
                    from twitch_screen_client.normalize import Card
                    delivery.submit(Card(CARD, boottime_ms() + 60000, 100))
                    async with asyncio.timeout(3):
                        while delivery.status['notification_accepted'] != 1:
                            await asyncio.sleep(.01)
                    for task in tasks:
                        task.cancel()
            finally:
                for task in tasks:
                    task.cancel()
                await delivery.close()
