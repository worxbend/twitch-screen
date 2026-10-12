"""Calibration response boundaries and late-response fencing; synthetic peers only."""
import asyncio
import json
import unittest
from collections.abc import Callable

from test_runtime import EPOCH, FIXTURES, Relay, configuration
from twitch_screen_client.clock import Calibration
from twitch_screen_client.delivery import Delivery
from twitch_screen_client.queue import CardQueue
from twitch_screen_client.runtime import initial_status

NEXT_EPOCH = 'fedcba9876543210'
CARD = {**FIXTURES['notification_bodyless'], 'ttlMs': 8000, 'expiresInMs': 60000}


class HeaderPeer:
    def __init__(self):
        self.requests = []
        self.clock_headers = {'cache-control': 'no-store'}
        self.write_headers = {'x-relay-epoch': EPOCH, 'x-relay-monotonic-ms': '1000'}
        self.code = 200
        self.before_response: Callable[[], None] | None = None

    async def request(self, method, path, payload=None):
        self.requests.append((method, payload))
        await asyncio.sleep(0)
        if method == 'GET':
            return 200, self.clock_headers, json.dumps({'relayEpoch': EPOCH, 'monotonicMs': 1000}).encode(), 1000, 1000
        if self.before_response is not None:
            self.before_response()
        if self.code == 409:
            body = FIXTURES['clock_epoch_changed']
        elif self.code == 200:
            assert payload is not None
            body = {**FIXTURES['notification_accepted'], **{key: payload[key] for key in CARD}}
        else:
            body = {}
        return self.code, self.write_headers, json.dumps(body).encode(), 1000, 1000


class CalibrationHeaderTests(unittest.IsolatedAsyncioTestCase):
    async def make_delivery(self):
        delivery = Delivery(configuration('http://localhost'), lambda: 1000,
                            CardQueue(lambda: 1000), initial_status(), lambda: 1000)
        self.addAsyncCleanup(delivery.close)
        return delivery

    async def upload(self, delivery, peer):
        return await delivery.upload(peer, 'POST', '/api/v1/notifications', CARD, 61000)

    async def test_cacheable_clock_response_cannot_authorize_any_upload(self):
        for header in (None, '', 'public, max-age=60', 'no-cache', 'x-no-store', 'no-store=1',
                       'private="x,no-store,y"', 'no-store, extension="unterminated',
                       'extension="x\\\",no-store,y"'):
            with self.subTest(header=header):
                delivery = await self.make_delivery()
                peer = HeaderPeer()
                peer.clock_headers = {} if header is None else {'cache-control': header}
                for method in ('POST', 'PUT'):
                    self.assertEqual(await delivery.upload(peer, method, '/unused', CARD, 61000), 'retry')
                self.assertTrue(all(method == 'GET' for method, _ in peer.requests))
                self.assertIsNone(delivery.calibration)

    async def test_no_store_directive_is_case_insensitive_and_may_be_combined(self):
        delivery = await self.make_delivery()
        peer = HeaderPeer()
        peer.clock_headers = {'cache-control': 'private, NO-STORE, no-cache'}
        self.assertEqual(await self.upload(delivery, peer), 'accepted')
        self.assertIsNotNone(delivery.calibration)

    async def test_missing_headers_force_fresh_get_without_replaying_accepted_post(self):
        delivery = await self.make_delivery()
        peer = HeaderPeer()
        peer.write_headers = {}
        self.assertEqual(await self.upload(delivery, peer), 'accepted')
        first = peer.requests[-1][1]['deadline']['monotonicMs']
        self.assertIsNone(delivery.calibration)
        self.assertEqual(await self.upload(delivery, peer), 'accepted')
        self.assertEqual([method for method, _ in peer.requests], ['GET', 'POST', 'GET', 'POST'])
        self.assertLessEqual(peer.requests[-1][1]['deadline']['monotonicMs'], first)

    async def test_invalid_sample_headers_do_not_refresh_or_retain_calibration(self):
        for sample in ('+1000', '1_000', ' 1000 ', '١٠٠٠', '-1', '1.0', '9007199254740992', '9' * 100):
            with self.subTest(sample=sample):
                delivery = await self.make_delivery()
                peer = HeaderPeer()
                peer.write_headers['x-relay-monotonic-ms'] = sample
                self.assertEqual(await self.upload(delivery, peer), 'accepted')
                self.assertIsNone(delivery.calibration)
        for epoch in (NEXT_EPOCH, '0000000000000000'):
            with self.subTest(epoch=epoch):
                delivery = await self.make_delivery()
                peer = HeaderPeer()
                peer.write_headers['x-relay-epoch'] = epoch
                self.assertEqual(await self.upload(delivery, peer), 'accepted')
                self.assertIsNone(delivery.calibration)

    async def test_valid_write_headers_reuse_current_accepted_epoch(self):
        delivery = await self.make_delivery()
        peer = HeaderPeer()
        self.assertEqual(await self.upload(delivery, peer), 'accepted')
        self.assertEqual(await self.upload(delivery, peer), 'accepted')
        self.assertEqual([method for method, _ in peer.requests], ['GET', 'POST', 'POST'])
        assert delivery.calibration is not None
        self.assertEqual(delivery.calibration.epoch, EPOCH)

    async def test_real_http_cacheable_clock_never_sends_post_or_put(self):
        async with Relay() as peer:
            peer.clock_cache_control = 'private="x,no-store,y", max-age=60'
            delivery = Delivery(configuration(peer.url), lambda: 1000,
                                CardQueue(lambda: 1000), initial_status(), lambda: 1000)
            self.addAsyncCleanup(delivery.close)
            for method in ('POST', 'PUT'):
                self.assertEqual(await delivery.upload(delivery.exchanges[0], method, '/unused', CARD, 61000), 'retry')
            self.assertEqual([method for method, _, _ in peer.requests], ['GET', 'GET'])

    async def test_real_http_omitted_write_headers_force_next_upload_to_recalibrate(self):
        from twitch_screen_client.metrics import boottime_ms
        async with Relay() as peer:
            peer.write_calibration = False
            delivery = Delivery(configuration(peer.url), boottime_ms,
                                CardQueue(boottime_ms), initial_status())
            self.addAsyncCleanup(delivery.close)
            for title in ('First accepted card', 'Second accepted card'):
                self.assertEqual(await delivery.upload(delivery.exchanges[0], 'POST', '/api/v1/notifications',
                                 {**CARD, 'title': title}, boottime_ms() + 60000), 'accepted')
                self.assertIsNone(delivery.calibration)
            self.assertEqual([method for method, _, _ in peer.requests], ['GET', 'POST', 'GET', 'POST'])
            self.assertEqual([body['title'] for method, _, body in peer.requests if method == 'POST'],
                             ['First accepted card', 'Second accepted card'])

    async def test_late_response_cannot_erase_another_workers_new_calibration(self):
        for response in ('missing', 'epoch', 'invalid_body', 'valid'):
            with self.subTest(response=response):
                delivery = await self.make_delivery()
                peer = HeaderPeer()
                newer = Calibration.checked(NEXT_EPOCH, 2000, 1000, 1000, 1000)
                peer.before_response = lambda: setattr(delivery, 'calibration', newer)
                if response == 'missing':
                    peer.write_headers = {}
                elif response == 'epoch':
                    peer.code = 409
                elif response == 'invalid_body':
                    # The wrong accepted epoch makes the otherwise valid body uncertain.
                    original = peer.request
                    async def invalid(method, path, payload=None):
                        code, headers, wire, sent, received = await original(method, path, payload)
                        if method != 'GET':
                            body = json.loads(wire)
                            body['relayEpoch'] = NEXT_EPOCH
                            wire = json.dumps(body).encode()
                        return code, headers, wire, sent, received
                    peer.request = invalid
                expected = 'retry' if response == 'epoch' else 'ambiguous' if response == 'invalid_body' else 'accepted'
                self.assertEqual(await self.upload(delivery, peer), expected)
                self.assertIs(delivery.calibration, newer)
