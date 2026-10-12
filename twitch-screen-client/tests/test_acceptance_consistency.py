"""Canonical acceptance invariants; synthetic responses are not relay acceptance."""
import json
import unittest

from test_runtime import FIXTURES, Relay, configuration
from twitch_screen_client.delivery import Delivery
from twitch_screen_client.metrics import boottime_ms
from twitch_screen_client.queue import CardQueue
from twitch_screen_client.runtime import initial_status
from twitch_screen_client.responses import accepted


class AcceptanceConsistencyTests(unittest.TestCase):
    def test_cpu_status_reason_and_age_must_agree(self):
        original = FIXTURES['cpu_fresh']
        for status, reason, remaining in (
                ('fresh', 'expired', 1000), ('fresh', 'clock_unavailable', 1000),
                ('fresh', 'fresh', 0), ('stale', 'fresh', 0),
                ('stale', 'expired', 1), ('stale', 'clock_unavailable', 1)):
            with self.subTest(status=status, reason=reason, remaining=remaining):
                response = {**original, 'status': status, 'reason': reason,
                            'remainingFreshnessMs': remaining}
                self.assertFalse(accepted(response, 'PUT', original['data']))
        for reason in ('expired', 'clock_unavailable'):
            response = {**original, 'status': 'stale', 'reason': reason,
                        'remainingFreshnessMs': 0}
            self.assertTrue(accepted(response, 'PUT', original['data']))

    def test_cpu_remaining_age_cannot_exceed_accepted_duration(self):
        original = FIXTURES['cpu_fresh']
        response = {**original, 'remainingFreshnessMs': 10001,
                    'data': {**original['data'], 'freshnessMs': 10000}}
        self.assertFalse(accepted(response, 'PUT', original['data']))
        response['remainingFreshnessMs'] = 10000
        self.assertTrue(accepted(response, 'PUT', original['data']))

    def test_notification_hold_must_be_the_effective_requested_quantum(self):
        original = FIXTURES['notification_accepted']
        for requested, effective in ((100, 100), (101, 200), (29999, 30000)):
            payload = {**original, 'ttlMs': requested}
            for reported in (101, 8000, 30000):
                response = {**original, 'ttlMs': reported}
                self.assertEqual(accepted(response, 'POST', payload), reported == effective)
            self.assertTrue(accepted({**original, 'ttlMs': effective}, 'POST', payload))

    def test_notification_content_matches_submitted_canonical_text(self):
        original = FIXTURES['notification_accepted']
        payload = {**original, 'title': '  Synthetic title\t', 'body': 'Body \u00e9\n'}
        canonical = {**original, 'title': 'Synthetic title', 'body': payload['body']}
        self.assertTrue(accepted(canonical, 'POST', payload))
        for field, changed in (('title', 'Different title'), ('body', 'Different body'),
                               ('body', payload['body'].strip())):
            with self.subTest(field=field):
                self.assertFalse(accepted({**canonical, field: changed}, 'POST', payload))
        # Java String.trim removes only code points <= U+0020, not Unicode spaces.
        payload['title'] = '\u00a0Synthetic\u00a0'
        self.assertTrue(accepted({**canonical, 'title': payload['title']}, 'POST', payload))
        self.assertFalse(accepted({**canonical, 'title': payload['title'].strip()}, 'POST', payload))

    def test_notification_expiry_response_must_match_original_duration(self):
        original = FIXTURES['notification_accepted']
        payload = {**original, 'expiresInMs': 1000}
        self.assertFalse(accepted(original, 'POST', payload))
        response = {**original, 'expiresInMs': 1000, 'remainingExpiryMs': 500}
        self.assertTrue(accepted(response, 'POST', payload))


class AcceptanceExchangeTests(unittest.IsolatedAsyncioTestCase):
    async def check_outcome(self, method, corrupt):
        async with Relay() as relay:
            status = initial_status()
            delivery = Delivery(configuration(relay.url), boottime_ms, CardQueue(boottime_ms), status)
            exchange = delivery.exchanges[0 if method == 'POST' else 1]
            original = exchange.request

            async def request(operation, path, payload=None):
                result = await original(operation, path, payload)
                if operation == method:
                    code, headers, body, sent, received = result
                    response = json.loads(body)
                    corrupt(response)
                    return code, headers, json.dumps(response).encode(), sent, received
                return result

            exchange.request = request
            payload = ({'type': 'message', 'title': 'Synthetic acceptance', 'body': '',
                        'ttlMs': 8000, 'expiresInMs': 60000} if method == 'POST' else
                       {'sourceId': 'test-desktop', 'host': 'Synthetic host', 'cpuPercent': 25,
                        'freshnessMs': 10000})
            try:
                outcome = await delivery.upload(exchange, method,
                    '/api/v1/notifications' if method == 'POST' else '/api/v1/cpu',
                    payload, boottime_ms() + 60000)
                self.assertEqual(outcome, 'ambiguous' if method == 'POST' else 'retry')
                self.assertEqual(status['relay'], 'invalid_response')
                self.assertIsNone(delivery.calibration)
                self.assertEqual(sum(row[0] == method for row in relay.requests), 1)
            finally:
                await delivery.close()

    async def test_changed_post_title_is_ambiguous_not_accepted(self):
        await self.check_outcome('POST', lambda response: response.update(title='Different title'))

    async def test_changed_post_body_is_ambiguous_not_accepted(self):
        await self.check_outcome('POST', lambda response: response.update(body='Different body'))

    async def test_changed_post_duration_is_ambiguous_not_accepted(self):
        await self.check_outcome('POST', lambda response: response.update(ttlMs=30000))

    async def test_contradictory_cpu_freshness_is_retryable_without_acceptance(self):
        await self.check_outcome('PUT', lambda response: response.update(status='stale'))
