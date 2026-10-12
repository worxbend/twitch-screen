"""Real socket rejection/response-loss tests against an explicit synthetic relay."""
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
NEXT_EPOCH = 'fedcba9876543210'


class ContractPeer(Relay):
    """Fake HTTP peer: records commits separately from responses, never product proof."""
    def __init__(self):
        super().__init__()
        self.epoch = EPOCH
        self.commits = []
        self.post_outcomes = []
        self.advance_ms = 0
        self.delay_post = 0.0

    async def handle(self, reader, writer):
        task = asyncio.current_task()
        self.handlers.add(task)
        self.connections.add(writer)
        try:
            header = await asyncio.wait_for(reader.readuntil(b'\r\n\r\n'), 3)
            rows = header.decode().split('\r\n')
            method, path, _ = rows[0].split()
            headers = dict(row.split(': ', 1) for row in rows[1:] if ': ' in row)
            wire = await reader.readexactly(int(headers.get('Content-Length', 0)))
            payload = json.loads(wire) if wire else None
            self.requests.append((method, path, payload))
            self.changed.set()
            code = 200
            if path == '/api/v1/clock':
                output = {'relayEpoch': self.epoch, 'monotonicMs': boottime_ms() + self.advance_ms}
            else:
                assert isinstance(payload, dict)
                if method == 'POST' and self.delay_post:
                    await asyncio.sleep(self.delay_post)
                outcome = self.post_outcomes.pop(0) if method == 'POST' and self.post_outcomes else 'accept'
                if outcome == 'epoch':
                    self.epoch = NEXT_EPOCH
                now = boottime_ms() + self.advance_ms
                if outcome == 'limited':
                    code, output = 429, FIXTURES['rate_limited']
                elif payload['deadline']['relayEpoch'] != self.epoch:
                    code, output = 409, FIXTURES['clock_epoch_changed']
                elif payload['deadline']['monotonicMs'] <= now:
                    code, output = 410, FIXTURES['expired']
                else:
                    self.commits.append((method, payload))
                    if outcome == 'lose_response':
                        return  # Committed POST, but no response: client must never replay it.
                    output = {**FIXTURES['notification_accepted'], **{key: payload[key] for key in CARD},
                              'relayEpoch': self.epoch}
            body = json.dumps(output).encode()
            response_headers = 'Cache-Control: no-store\r\n' if path == '/api/v1/clock' else ''
            if code == 200 and path != '/api/v1/clock':
                response_headers = f'X-Relay-Epoch: {self.epoch}\r\nX-Relay-Monotonic-Ms: {boottime_ms() + self.advance_ms}\r\n'
            writer.write(f'HTTP/1.1 {code} Synthetic\r\nContent-Type: application/json\r\nContent-Length: {len(body)}\r\nConnection: close\r\n{response_headers}\r\n'.encode() + body)
            await writer.drain()
        except (ConnectionError, asyncio.IncompleteReadError, TimeoutError):
            pass
        finally:
            writer.close()
            with contextlib.suppress(ConnectionError):
                await writer.wait_closed()
            self.connections.discard(writer)
            self.handlers.discard(task)


class HttpRecoveryTests(unittest.IsolatedAsyncioTestCase):
    async def create_delivery(self, peer):
        delivery = Delivery(configuration(peer.url), boottime_ms, CardQueue(boottime_ms), initial_status())
        self.addAsyncCleanup(delivery.close)
        return delivery

    async def test_epoch_and_rate_rejection_retry_without_renewing_capture_age(self):
        async with ContractPeer() as peer:
            peer.post_outcomes = ['epoch', 'limited', 'accept']
            delivery = await self.create_delivery(peer)
            original_deadline = boottime_ms() + 60000
            delivery.submit(Card(CARD, original_deadline, 100))
            worker = asyncio.create_task(delivery.notifications())
            try:
                async with asyncio.timeout(6):
                    while delivery.status['notification_accepted'] != 1:
                        await asyncio.sleep(.01)
                posts = [payload for method, _, payload in peer.requests if method == 'POST']
                self.assertEqual(len(posts), 3)
                self.assertEqual(len(peer.commits), 1)
                self.assertEqual([row['deadline']['relayEpoch'] for row in posts], [EPOCH, NEXT_EPOCH, NEXT_EPOCH])
                self.assertTrue(all(row['deadline']['monotonicMs'] < original_deadline for row in posts))
                self.assertLessEqual(posts[2]['deadline']['monotonicMs'], posts[1]['deadline']['monotonicMs'])
                self.assertEqual(sum(path == '/api/v1/clock' for _, path, _ in peer.requests), 2)
            finally:
                worker.cancel()
                await asyncio.gather(worker, return_exceptions=True)

    async def test_delayed_receipt_expires_without_mutation_or_renewed_lifetime(self):
        async with ContractPeer() as peer:
            delivery = await self.create_delivery(peer)
            # A strictly fresh local record becomes expired during actual HTTP residence.
            deadline = boottime_ms() + 200
            peer.delay_post = .3
            outcome = await delivery.upload(delivery.exchanges[0], 'POST', '/api/v1/notifications', CARD, deadline)
            self.assertEqual(outcome, 'expired')
            self.assertEqual(peer.commits, [])
            self.assertEqual(sum(method == 'POST' for method, _, _ in peer.requests), 1)

    async def test_lost_acceptance_response_drops_once_and_keeps_generic_fifo(self):
        async with ContractPeer() as peer:
            peer.post_outcomes = ['lose_response', 'accept', 'accept']
            delivery = await self.create_delivery(peer)
            for title in ('Lost response', 'Second', 'Third'):
                delivery.submit(Card({**CARD, 'title': title}, boottime_ms() + 60000, 100))
            worker = asyncio.create_task(delivery.notifications())
            try:
                async with asyncio.timeout(3):
                    while delivery.status['notification_accepted'] != 2:
                        await asyncio.sleep(.01)
                await asyncio.sleep(.1)
                self.assertEqual(delivery.status['notification_ambiguous'], 1)
                self.assertEqual([payload['title'] for _, payload in peer.commits], ['Lost response', 'Second', 'Third'])
                self.assertEqual(sum(method == 'POST' for method, _, _ in peer.requests), 3)
                self.assertEqual(len(delivery.cards.cards), 0)
            finally:
                worker.cancel()
                await asyncio.gather(worker, return_exceptions=True)
