"""Checker safety/readback gates; injected replies are explicitly synthetic."""
import asyncio
import contextlib
import importlib.util
import json
from pathlib import Path
import unittest
from unittest.mock import patch

from test_runtime import EPOCH, FIXTURES, Relay, configuration
from twitch_screen_client.metrics import boottime_ms

spec = importlib.util.spec_from_file_location('check_relay', Path(__file__).resolve().parents[1] / 'tools/check_relay.py')
assert spec is not None and spec.loader is not None
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


class SubmittedCpuChecks(unittest.TestCase):
    def test_default_duration_and_receipt_observation(self):
        value = {**FIXTURES['cpu_fresh'], 'observedAtSource': 'relay_receipt'}
        payload = {'sourceId': value['data']['sourceId'], 'host': value['data']['host'], 'cpuPercent': 0}
        self.assertTrue(checker.matches_submitted_cpu(value, payload))
        for changed in ({'observedAtSource': 'producer'},
                        {'data': {**value['data'], 'observedAt': '2026-10-11T10:00:01.000Z'}},
                        {'data': {**value['data'], 'freshnessMs': 20000}}):
            with self.subTest(changed=changed):
                self.assertFalse(checker.matches_submitted_cpu({**value, **changed}, payload))

    def test_explicit_observation_is_preserved_with_producer_provenance(self):
        value = FIXTURES['cpu_fresh']
        payload = {'sourceId': value['data']['sourceId'], 'host': value['data']['host'],
                   'cpuPercent': 0, 'observedAt': value['data']['observedAt']}
        self.assertTrue(checker.matches_submitted_cpu(value, payload))
        self.assertFalse(checker.matches_submitted_cpu({**value, 'observedAtSource': 'relay_receipt'}, payload))
        self.assertFalse(checker.matches_submitted_cpu(value, {**payload, 'observedAt': '2026-10-11T10:00:01.000Z'}))


class PairingChecks(unittest.IsolatedAsyncioTestCase):
    async def test_real_http_read_only_preflight_never_writes(self):
        async with Relay() as relay:
            result = await checker.check(configuration(relay.url))
            self.assertEqual(result['status'], 'passed')
            self.assertFalse(result['writesAttempted'])
            self.assertFalse(result['productionVerified'])
            self.assertEqual([row[:2] for row in relay.requests], [('GET', '/api/v1/clock')])

    async def test_cacheable_clock_blocks_before_any_mutation(self):
        async with Relay() as relay:
            relay.clock_cache_control = 'public'
            result = await checker.check(configuration(relay.url), exercise=True)
            self.assertEqual(result['status'], 'blocked')
            self.assertFalse(result['writesAttempted'])
            self.assertEqual([row[0] for row in relay.requests], ['GET'])

    async def exercise(self, corruption=None, sample=None, accepted_data=None):
        stored, calls = {}, []

        async def request(exchange, method, path, payload=None):
            calls.append((method, path))
            now = boottime_ms()
            headers = {'cache-control': 'no-store', 'x-relay-epoch': EPOCH,
                       'x-relay-monotonic-ms': str(now)}
            if path == '/api/v1/clock':
                response = {'relayEpoch': EPOCH, 'monotonicMs': now}
            elif method == 'PUT':
                assert payload is not None
                response = {**FIXTURES['cpu_fresh'], 'observedAtSource': 'relay_receipt',
                    'data': {**FIXTURES['cpu_fresh']['data'],
                    'memoryPercent': None, 'temperatureCelsius': None,
                    **{key: value for key, value in payload.items() if key != 'deadline'},
                    **(accepted_data or {})}}
                stored['cpu'] = response
            elif path == '/api/v1/cpu':
                response = stored['cpu']
                if corruption == 'cpu':
                    response = {**response, 'revision': response['revision'] + 1}
            elif method == 'POST':
                assert payload is not None
                response = {**FIXTURES['notification_accepted'],
                            **{key: payload[key] for key in ('type', 'title', 'body', 'ttlMs', 'expiresInMs')}}
                stored['card'] = response
            else:
                card = stored['card']
                if corruption == 'card':
                    card = {**card, 'seq': card['seq'] + 1}
                response = {'notifications': [card]}
            return 200, headers, json.dumps(response).encode(), now, now

        async def no_wait(_):
            return None

        class Sampler:
            def sample(self):
                return boottime_ms(), sample if sample is not None else {'cpuPercent': 25, 'memoryPercent': 50}

        with patch.object(checker.Exchange, 'request', request), patch.object(checker, 'Sampler', return_value=Sampler()), \
                patch.object(checker.asyncio, 'sleep', no_wait):
            result = await checker.check(configuration('http://127.0.0.1:1'), exercise=True)
        return result, calls

    async def test_exercise_reads_back_exact_cpu_revision_and_card_sequence(self):
        result, calls = await self.exercise()
        self.assertEqual(result['status'], 'passed')
        self.assertTrue(result['writesAttempted'])
        self.assertFalse(result['productionVerified'])
        self.assertEqual(calls[-1], ('GET', '/api/v1/notifications?pageSize=80'))
        self.assertEqual(sum(method == 'POST' for method, _ in calls), 1)

    async def assert_measurement_blocked(self, sample, data):
        result, calls = await self.exercise(sample=sample, accepted_data=data)
        self.assertEqual(result['status'], 'blocked')
        self.assertTrue(any('submitted measurement' in item for item in result['blockers']))
        self.assertFalse(any(method == 'POST' for method, _ in calls))
        self.assertFalse(any(method == 'GET' and path == '/api/v1/cpu' for method, path in calls))

    async def test_changed_measurements_block_before_readback_or_post(self):
        sample = {'cpuPercent': 25, 'memoryPercent': 50, 'temperatureCelsius': -12.345}
        for data in ({'cpuPercent': 99}, {'memoryPercent': 99}, {'temperatureCelsius': 99}):
            with self.subTest(data=data):
                await self.assert_measurement_blocked(sample, data)

    async def test_supplied_optional_measurements_cannot_become_null(self):
        for field in ('memoryPercent', 'temperatureCelsius'):
            with self.subTest(field=field):
                await self.assert_measurement_blocked({'cpuPercent': 25, field: 0}, {field: None})

    async def test_omitted_optional_measurements_cannot_be_invented(self):
        for field in ('memoryPercent', 'temperatureCelsius'):
            with self.subTest(field=field):
                await self.assert_measurement_blocked({'cpuPercent': 25}, {field: 0})

    async def test_omitted_optional_measurements_are_canonical_null(self):
        result, calls = await self.exercise(sample={'cpuPercent': 25})
        self.assertEqual(result['status'], 'passed')
        self.assertEqual(sum(method == 'POST' for method, _ in calls), 1)

    async def test_canonical_half_away_rounding_is_accepted(self):
        for sample, data in (
                ({'cpuPercent': 25.005, 'memoryPercent': 50.005, 'temperatureCelsius': -12.345},
                 {'cpuPercent': 25.01, 'memoryPercent': 50.01, 'temperatureCelsius': -12.35}),
                ({'cpuPercent': 0, 'memoryPercent': 100, 'temperatureCelsius': 12.345},
                 {'cpuPercent': 0, 'memoryPercent': 100, 'temperatureCelsius': 12.35}),
                ({'cpuPercent': 25.0049, 'memoryPercent': 50.0049, 'temperatureCelsius': -0.0049},
                 {'cpuPercent': 25, 'memoryPercent': 50, 'temperatureCelsius': 0})):
            with self.subTest(sample=sample):
                result, calls = await self.exercise(sample=sample, accepted_data=data)
                self.assertEqual(result['status'], 'passed')
                self.assertEqual(sum(method == 'POST' for method, _ in calls), 1)

    async def test_changed_freshness_is_not_measurement_evidence(self):
        await self.assert_measurement_blocked({'cpuPercent': 25}, {'freshnessMs': 20000})

    async def test_competing_cpu_slot_fails_and_never_posts(self):
        result, calls = await self.exercise('cpu')
        self.assertEqual(result['status'], 'blocked')
        self.assertTrue(any('CPU readback' in item for item in result['blockers']))
        self.assertFalse(any(method == 'POST' for method, _ in calls))

    async def test_real_http_changed_cpu_echo_cannot_certify_sample(self):
        calls, handlers, stored = [], set(), {}

        async def handle(reader, writer):
            task = asyncio.current_task()
            handlers.add(task)
            try:
                head = await reader.readuntil(b'\r\n\r\n')
                rows = head.decode().split('\r\n')
                method, path, _ = rows[0].split()
                headers = dict(row.split(': ', 1) for row in rows[1:] if ': ' in row)
                body = await reader.readexactly(int(headers.get('Content-Length', 0)))
                payload = json.loads(body) if body else None
                calls.append((method, path))
                now = boottime_ms()
                if path == '/api/v1/clock':
                    response = {'relayEpoch': EPOCH, 'monotonicMs': now}
                elif method == 'PUT':
                    assert payload is not None
                    response = {**FIXTURES['cpu_fresh'], 'observedAtSource': 'relay_receipt',
                        'data': {**FIXTURES['cpu_fresh']['data'],
                                 **{key: value for key, value in payload.items() if key != 'deadline'},
                                 'cpuPercent': 99}}
                    stored['cpu'] = response
                elif path == '/api/v1/cpu':
                    response = stored['cpu']
                elif method == 'POST':
                    assert payload is not None
                    response = {**FIXTURES['notification_accepted'],
                                **{key: payload[key] for key in ('type', 'title', 'body', 'ttlMs', 'expiresInMs')}}
                    stored['card'] = response
                else:
                    response = {'notifications': [stored['card']]}
                wire = json.dumps(response).encode()
                writer.write((f'HTTP/1.1 200 Fixture\r\nContent-Length: {len(wire)}\r\n'
                              f'Cache-Control: no-store\r\nX-Relay-Epoch: {EPOCH}\r\n'
                              f'X-Relay-Monotonic-Ms: {now}\r\nConnection: close\r\n\r\n').encode() + wire)
                await writer.drain()
            finally:
                writer.close()
                with contextlib.suppress(ConnectionError):
                    await writer.wait_closed()
                handlers.discard(task)

        class Sampler:
            def sample(self):
                return boottime_ms(), {'cpuPercent': 25, 'memoryPercent': 50}

        server = await asyncio.start_server(handle, '127.0.0.1', 0)
        port = server.sockets[0].getsockname()[1]
        try:
            with patch.object(checker, 'Sampler', return_value=Sampler()):
                result = await checker.check(configuration(f'http://127.0.0.1:{port}', sample_interval_ms=1000), True)
            self.assertEqual(result['status'], 'blocked')
            self.assertIn('CPU acceptance did not match submitted measurement', result['blockers'])
            self.assertEqual(calls, [('GET', '/api/v1/clock'), ('PUT', '/api/v1/cpu')])
        finally:
            server.close()
            await server.wait_closed()
            for task in list(handlers):
                task.cancel()
            await asyncio.gather(*list(handlers), return_exceptions=True)

    async def test_unrelated_same_title_history_is_not_exact_target_evidence(self):
        result, calls = await self.exercise('card')
        self.assertEqual(result['status'], 'blocked')
        self.assertEqual(sum(method == 'POST' for method, _ in calls), 1)
        self.assertFalse(result['productionVerified'])
