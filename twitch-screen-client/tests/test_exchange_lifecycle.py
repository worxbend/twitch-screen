"""Actual HTTP residence and independent worker gates; synthetic relay only."""
import asyncio
import contextlib
import json
import unittest

from test_runtime import EPOCH, FIXTURES, Relay, configuration
from twitch_screen_client.delivery import Delivery
from twitch_screen_client.metrics import boottime_ms
from twitch_screen_client.normalize import Card
from twitch_screen_client.queue import CardQueue
from twitch_screen_client.runtime import initial_status

CARD = {**FIXTURES['notification_bodyless'], 'ttlMs': 8000, 'expiresInMs': 60000}


class ResidencePeer(Relay):
    """Keep POST response unfinished; CPU and clock exchanges remain ordinary."""
    def __init__(self, mode):
        super().__init__()
        self.residence_mode = mode
        self.post_started = asyncio.Event()
        self.first_fragment = asyncio.Event()
        self.post_closed = asyncio.Event()
        self.commits = []

    async def handle(self, reader, writer):
        task = asyncio.current_task()
        self.handlers.add(task)
        self.connections.add(writer)
        is_post = False
        try:
            header = await asyncio.wait_for(reader.readuntil(b'\r\n\r\n'), 3)
            rows = header.decode().split('\r\n')
            method, path, _ = rows[0].split()
            headers = dict(row.split(': ', 1) for row in rows[1:] if ': ' in row)
            body = await reader.readexactly(int(headers.get('Content-Length', 0)))
            payload = json.loads(body) if body else None
            self.requests.append((method, path, payload))
            self.changed.set()
            is_post = method == 'POST'
            if path != '/api/v1/clock':
                assert isinstance(payload, dict)
            if path == '/api/v1/clock':
                output = {'relayEpoch': EPOCH, 'monotonicMs': boottime_ms()}
                samples = 'Cache-Control: no-store\r\n'
            elif is_post:
                self.commits.append(payload)
                self.post_started.set()
                output = {**FIXTURES['notification_accepted'], **{key: payload[key] for key in CARD}}
                samples = ''
            else:
                output = {**FIXTURES['cpu_fresh'],
                          'observedAtSource': 'producer' if 'observedAt' in payload else 'relay_receipt',
                          'data': {**FIXTURES['cpu_fresh']['data'], 'temperatureCelsius': None,
                                   'observedAt': payload.get('observedAt', FIXTURES['cpu_fresh']['receivedAt']),
                                   **{key: value for key, value in payload.items() if key != 'deadline'}}}
                samples = f'X-Relay-Epoch: {EPOCH}\r\nX-Relay-Monotonic-Ms: {boottime_ms()}\r\n'
            wire = json.dumps(output).encode()
            writer.write(f'HTTP/1.1 200 Synthetic\r\nContent-Type: application/json\r\nContent-Length: {len(wire)}\r\nConnection: close\r\n{samples}\r\n'.encode())
            if is_post and self.residence_mode == 'truncated':
                writer.write(wire[:-1])
                await writer.drain()
                return
            if is_post:
                # Bytes arrive within the read-idle budget, but never complete.
                # Only the total exchange budget or cancellation can stop this.
                for byte in wire:
                    writer.write(bytes([byte]))
                    await writer.drain()
                    self.first_fragment.set()
                    await asyncio.sleep(.2)
                return
            writer.write(wire)
            await writer.drain()
        except (ConnectionError, asyncio.IncompleteReadError, TimeoutError):
            pass
        finally:
            writer.close()
            with contextlib.suppress(ConnectionError):
                await writer.wait_closed()
            self.connections.discard(writer)
            self.handlers.discard(task)
            if is_post:
                self.post_closed.set()


class ExchangeLifecycleTests(unittest.IsolatedAsyncioTestCase):
    def delivery(self, peer):
        delivery = Delivery(configuration(peer.url), boottime_ms,
                            CardQueue(boottime_ms), initial_status())
        self.addAsyncCleanup(delivery.close)
        return delivery

    async def test_continuous_response_bytes_cannot_extend_total_post_budget(self):
        async with ResidencePeer('trickle') as peer:
            delivery = self.delivery(peer)
            start = asyncio.get_running_loop().time()
            outcome = await asyncio.wait_for(delivery.upload(delivery.exchanges[0], 'POST',
                '/api/v1/notifications', CARD, boottime_ms() + 60000), 4)
            elapsed = asyncio.get_running_loop().time() - start
            self.assertEqual(outcome, 'ambiguous')
            self.assertTrue(peer.first_fragment.is_set())
            self.assertGreaterEqual(elapsed, 2.5)
            self.assertLess(elapsed, 4)
            self.assertEqual(len(peer.commits), 1)
            await asyncio.wait_for(peer.post_closed.wait(), 1)

    async def test_truncated_accepted_post_is_dropped_without_retry(self):
        async with ResidencePeer('truncated') as peer:
            delivery = self.delivery(peer)
            delivery.submit(Card(CARD, boottime_ms() + 60000, 100))
            worker = asyncio.create_task(delivery.notifications())
            try:
                async with asyncio.timeout(2):
                    while delivery.status['notification_ambiguous'] != 1:
                        await asyncio.sleep(.005)
                # Allow a full retry interval: an ambiguous commit stays final.
                await asyncio.sleep(1.3)
                self.assertEqual(len(peer.commits), 1)
                self.assertEqual(delivery.status['notification_accepted'], 0)
                self.assertEqual(len(delivery.cards.cards), 0)
                self.assertFalse(worker.done())
            finally:
                worker.cancel()
                await asyncio.wait_for(asyncio.gather(worker, return_exceptions=True), 1)

    async def test_cpu_continues_during_post_residence_and_workers_cancel(self):
        async with ResidencePeer('trickle') as peer:
            delivery = self.delivery(peer)
            delivery.submit(Card(CARD, boottime_ms() + 60000, 100))
            workers = [asyncio.create_task(delivery.notifications()),
                       asyncio.create_task(delivery.metrics())]
            try:
                await asyncio.wait_for(peer.first_fragment.wait(), 1)
                for expected, percent in enumerate((12, 99), 1):
                    delivery.sample(boottime_ms(), {'cpuPercent': percent, 'memoryPercent': 25})
                    async with asyncio.timeout(1):
                        while delivery.status['cpu_accepted'] != expected:
                            await asyncio.sleep(.005)
                self.assertEqual([value['cpuPercent'] for method, _, value in peer.requests
                                  if method == 'PUT'], [12, 99])
                self.assertEqual(delivery.status['notification_ambiguous'], 0)
                self.assertEqual(delivery.status['notification_accepted'], 0)
                self.assertFalse(peer.post_closed.is_set())
                self.assertTrue(all(not worker.done() for worker in workers))
            finally:
                start = asyncio.get_running_loop().time()
                for worker in workers:
                    worker.cancel()
                results = await asyncio.wait_for(asyncio.gather(*workers, return_exceptions=True), 1)
                self.assertTrue(all(isinstance(result, asyncio.CancelledError) for result in results))
                self.assertLess(asyncio.get_running_loop().time() - start, 1)
            await delivery.close()
            await asyncio.wait_for(peer.post_closed.wait(), 1)
