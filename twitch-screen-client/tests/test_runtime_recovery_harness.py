"""Test recovery-harness transport ownership; synthetic upstream only."""
import asyncio
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

import httpx

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('runtime_recovery', ROOT / 'tools/check_runtime_recovery.py')
assert SPEC is not None and SPEC.loader is not None
RECOVERY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RECOVERY)


class HarnessTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.requests = []
        self.tasks = set()
        self.response = (b'HTTP/1.1 200 OK\r\nContent-Length: 18\r\n'
                         b'Connection: close\r\n\r\n{"synthetic":true}')
        self.server = await asyncio.start_server(self.upstream, '127.0.0.1', 0)
        self.port = self.server.sockets[0].getsockname()[1]

    async def upstream(self, reader, writer):
        task = asyncio.current_task()
        self.tasks.add(task)
        try:
            header = await reader.readuntil(b'\r\n\r\n')
            rows = header.decode().split('\r\n')
            fields = dict(row.lower().split(':', 1) for row in rows[1:] if ':' in row)
            body = await reader.readexactly(int(fields.get('content-length', 0)))
            self.requests.append((header, body))
            # Split the actual response across scheduling turns, not one read.
            for byte in self.response:
                writer.write(bytes([byte]))
                await writer.drain()
                await asyncio.sleep(0)
        finally:
            writer.close()
            await writer.wait_closed()
            self.tasks.discard(task)

    async def asyncTearDown(self):
        self.server.close()
        await self.server.wait_closed()
        tasks = list(self.tasks)
        for task in tasks:
            task.cancel()
        await asyncio.gather(*tasks, return_exceptions=True)

    async def test_corrupt_body_fault_preserves_actual_upstream_record_and_is_path_scoped(self):
        import hashlib
        async with RECOVERY.FaultGateway(self.port) as gateway, httpx.AsyncClient(trust_env=False) as client:
            gateway.next_fault, gateway.fault_path = 'corrupt_response', '/api/v1/notifications'
            cpu = await client.put(gateway.url + '/api/v1/cpu', json={'cpuPercent': 25})
            self.assertEqual(cpu.json(), {'synthetic': True})
            self.assertEqual(gateway.next_fault, 'corrupt_response')
            post = await client.post(gateway.url + '/api/v1/notifications', json={'title': 'Synthetic'})
            self.assertEqual(post.status_code, 200)
            self.assertEqual(post.content, b'{broken')
            self.assertEqual(post.headers['content-length'], '7')
            self.assertNotIn('transfer-encoding', post.headers)
            with self.assertRaises(ValueError):
                post.json()
            record = gateway.records[-1]
            self.assertEqual(record['response'], {'synthetic': True})
            self.assertEqual(record['deliveredBodyHex'], b'{broken'.hex())
            self.assertEqual(record['upstreamResponseSha256'], hashlib.sha256(self.response).hexdigest())
            self.assertIsNone(gateway.next_fault)
            next_post = await client.post(gateway.url + '/api/v1/notifications', json={'title': 'Next'})
            self.assertEqual(next_post.json(), {'synthetic': True})
            self.assertEqual([row['fault'] for row in gateway.records], [None, 'corrupt_response', None])

    async def test_stubborn_owned_child_is_killed_and_reaped(self):
        child = await asyncio.create_subprocess_exec(sys.executable, '-c',
            "import signal, time; signal.signal(signal.SIGTERM, signal.SIG_IGN); "
            "print('ready', flush=True); time.sleep(30)",
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
        try:
            assert child.stdout is not None
            self.assertEqual(await asyncio.wait_for(child.stdout.readline(), 2), b'ready\n')
            await RECOVERY.reap_child(child, timeout=.1)
            self.assertIsNotNone(child.returncode)
        finally:
            if child.returncode is None:
                child.kill()
                await child.wait()

    async def test_blocked_start_preserves_failure_and_records_removed_scratch(self):
        owned = []

        class FailedRuntime:
            def __init__(self, assembly, directory, same_ports=False):
                self.directory, self.stops = directory, []
                owned.append(self)

            async def start(self):
                raise OSError('synthetic listener bind failure')

            async def stop(self):
                self.stops.append({'forcedKill': False, 'exitCode': 1})

        with tempfile.TemporaryDirectory(dir=os.environ['TMPDIR']) as temporary:
            root = Path(temporary)
            assembly = root / 'synthetic.jar'
            assembly.write_bytes(b'synthetic artifact, never launched')
            with patch.object(RECOVERY, 'Runtime', FailedRuntime), patch.object(RECOVERY.traceback, 'print_exc') as trace:
                result = await RECOVERY.exercise(assembly, root / 'evidence', ROOT / 'tests', same_ports=True)
            self.assertEqual(result['status'], 'blocked')
            self.assertTrue(result['scratchRemoved'])
            self.assertFalse(owned[0].directory.exists())
            self.assertEqual(len(owned[0].stops), 1)
            self.assertEqual(result['restartPortPolicy'], 'same')
            self.assertFalse(result['relayGracefulStopsVerified'])
            self.assertTrue(result['blockers'][0].startswith('OSError:'))
            self.assertEqual(json.loads((root / 'evidence/recovery.json').read_text()), result)
            trace.assert_called_once()

    async def test_runtime_stop_retains_forced_kill_and_does_not_duplicate_records(self):
        child = await asyncio.create_subprocess_exec(sys.executable, '-c',
            "import signal, time; signal.signal(signal.SIGTERM, signal.SIG_IGN); "
            "print('ready', flush=True); time.sleep(30)",
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
        runtime = RECOVERY.Runtime(Path('/unused.jar'), Path('/unused'), same_ports=True)
        runtime.child, runtime.launches = child, 1
        try:
            assert child.stdout is not None
            self.assertEqual(await asyncio.wait_for(child.stdout.readline(), 2), b'ready\n')
            await runtime.stop(graceful_timeout=.1)
            self.assertEqual(len(runtime.stops), 1)
            row = runtime.stops[0]
            self.assertTrue(row['forcedKill'])
            self.assertEqual(row['exitCode'], -9)
            self.assertEqual(row['pid'], child.pid)
            await runtime.stop(graceful_timeout=.1)
            self.assertEqual(runtime.stops, [row])
        finally:
            if child.returncode is None:
                child.kill()
            await child.communicate()

    async def test_gateway_preserves_real_response_and_forwards_exact_body(self):
        async with RECOVERY.FaultGateway(self.port) as gateway, httpx.AsyncClient(trust_env=False) as client:
            response = await client.post(gateway.url + '/api/v1/notifications', content=b'{"title":"Synthetic"}')
            self.assertEqual(response.content, b'{"synthetic":true}')
            self.assertEqual(len(self.requests), 1)
            self.assertEqual(self.requests[0][1], b'{"title":"Synthetic"}')
            self.assertIn(f'Host: 127.0.0.1:{self.port}'.encode(), self.requests[0][0])
            self.assertEqual(gateway.records[0]['status'], 200)
            self.assertEqual(gateway.records[0]['response'], {'synthetic': True})
        self.assertFalse(gateway.tasks)
        self.assertFalse(gateway.writers)

    async def test_gateway_retains_chunked_notification_acceptance(self):
        self.response = (b'HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n'
                         b'Connection: close\r\n\r\n9\r\n{"synthet\r\n'
                         b'9\r\nic":true}\r\n0\r\n\r\n')
        async with RECOVERY.FaultGateway(self.port) as gateway, httpx.AsyncClient(trust_env=False) as client:
            response = await client.post(gateway.url + '/api/v1/notifications', json={'title': 'Synthetic'})
            self.assertEqual(response.json(), {'synthetic': True})
            self.assertEqual(len(gateway.records), 1)
            self.assertEqual(gateway.records[0]['response'], response.json())
        self.assertFalse(gateway.tasks)
        self.assertFalse(gateway.writers)

    async def test_unavailable_upstream_records_attempt_without_fabricating_response(self):
        self.server.close()
        await self.server.wait_closed()
        payload = {'title': 'Synthetic unavailable upstream'}
        async with RECOVERY.FaultGateway(self.port) as gateway, httpx.AsyncClient(trust_env=False) as client:
            with self.assertRaises(httpx.RemoteProtocolError):
                await client.post(gateway.url + '/api/v1/notifications', json=payload)
            await RECOVERY.wait_until(lambda: not gateway.tasks, 1)
            self.assertEqual(gateway.requests, [{'method': 'POST', 'path': '/api/v1/notifications',
                                                 'payload': payload}])
            self.assertEqual(gateway.records, [])
            self.assertEqual(self.requests, [])
        self.assertFalse(gateway.tasks)
        self.assertFalse(gateway.writers)

    async def test_lost_response_is_forwarded_once_not_fabricated_or_retried(self):
        async with RECOVERY.FaultGateway(self.port) as gateway, httpx.AsyncClient(trust_env=False) as client:
            gateway.next_fault = 'lose_response'
            with self.assertRaises(httpx.RemoteProtocolError):
                await client.post(gateway.url + '/api/v1/notifications', json={'title': 'Synthetic'})
            self.assertEqual(len(self.requests), 1)
            self.assertEqual(len(gateway.records), 1)
            self.assertEqual(gateway.records[0]['fault'], 'lose_response')
            self.assertEqual(gateway.records[0]['status'], 200)

    async def test_held_response_preserves_commit_and_allows_parallel_request(self):
        async with RECOVERY.FaultGateway(self.port) as gateway, httpx.AsyncClient(trust_env=False) as client:
            gateway.next_fault = 'hold_response'
            held = asyncio.create_task(client.post(gateway.url + '/api/v1/notifications', json={'title': 'Synthetic'}))
            try:
                await asyncio.wait_for(gateway.response_held.wait(), 1)
                self.assertFalse(held.done())
                other = await client.put(gateway.url + '/api/v1/cpu', json={'cpuPercent': 25})
                self.assertEqual(other.content, b'{"synthetic":true}')
                self.assertEqual(len(self.requests), 2)
                self.assertFalse(held.done())
                gateway.release_response.set()
                self.assertEqual((await asyncio.wait_for(held, 1)).content, b'{"synthetic":true}')
            finally:
                held.cancel()
                await asyncio.gather(held, return_exceptions=True)
        self.assertFalse(gateway.tasks)

    async def test_path_scoped_fault_does_not_hold_independent_cpu(self):
        async with RECOVERY.FaultGateway(self.port) as gateway, httpx.AsyncClient(trust_env=False) as client:
            gateway.next_fault, gateway.fault_path = 'hold_response', '/api/v1/notifications'
            response = await client.put(gateway.url + '/api/v1/cpu', json={'cpuPercent': 25})
            self.assertEqual(response.content, b'{"synthetic":true}')
            self.assertEqual(gateway.next_fault, 'hold_response')
            self.assertFalse(gateway.response_held.is_set())
            held = asyncio.create_task(client.post(gateway.url + '/api/v1/notifications', json={'title': 'Synthetic'}))
            try:
                await asyncio.wait_for(gateway.response_held.wait(), 1)
                self.assertFalse(held.done())
                gateway.release_response.set()
                self.assertEqual((await asyncio.wait_for(held, 1)).content, b'{"synthetic":true}')
            finally:
                held.cancel()
                await asyncio.gather(held, return_exceptions=True)

    async def test_cancelled_held_response_is_reaped_without_resend(self):
        async with RECOVERY.FaultGateway(self.port) as gateway, httpx.AsyncClient(trust_env=False) as client:
            gateway.next_fault = 'hold_response'
            held = asyncio.create_task(client.post(gateway.url + '/api/v1/notifications', json={'title': 'Synthetic'}))
            try:
                await asyncio.wait_for(gateway.response_held.wait(), 1)
            finally:
                held.cancel()
                await asyncio.gather(held, return_exceptions=True)
        self.assertEqual(len(self.requests), 1)
        self.assertEqual(len(gateway.records), 1)
        self.assertFalse(gateway.tasks)
        self.assertFalse(gateway.writers)

    async def test_gateway_closes_owned_incomplete_connection_on_exit(self):
        async with RECOVERY.FaultGateway(self.port) as gateway:
            reader, writer = await asyncio.open_connection('127.0.0.1', gateway.server.sockets[0].getsockname()[1])
            writer.write(b'POST /api/v1/notifications HTTP/1.1\r\nContent-Length: 100\r\n\r\n{')
            await writer.drain()
            await asyncio.sleep(.01)
        self.assertEqual(await asyncio.wait_for(reader.read(), 1), b'')
        writer.close()
        await writer.wait_closed()
        self.assertEqual(self.requests, [])
        self.assertFalse(gateway.tasks)


class DeniedCaptureTests(unittest.TestCase):
    def fixture(self):
        import copy
        examples = json.loads((ROOT.parent / 'protocol/desk-v1-http-examples.json').read_text())['examples']
        cpu = copy.deepcopy(next(row['value'] for row in examples if row['name'] == 'cpu_fresh'))
        cpu['data']['memoryPercent'] = 50
        payload = {key: value for key, value in cpu['data'].items()
                   if key in ('sourceId', 'host', 'cpuPercent', 'memoryPercent', 'freshnessMs', 'observedAt') and value is not None}
        status = {'capture': 'monitor_denied', 'captured': 0, 'notification_accepted': 0,
                  'cpu_accepted': 2, 'capture_failures': 1}
        return status, cpu, payload

    def test_denial_requires_real_cpu_readback_and_no_publication(self):
        status, cpu, payload = self.fixture()
        result = RECOVERY.check_denied_capture(status, [], [], cpu, [payload], 2)
        self.assertTrue(result['measuredCpuReadbackVerified'])
        self.assertTrue(result['noNotificationPublication'])

    def test_denial_compares_retained_history_without_renewing_age(self):
        status, cpu, payload = self.fixture()
        before = [{'relayEpoch': '0123456789abcdef', 'seq': 1, 'title': 'Synthetic', 'remainingExpiryMs': 5000}]
        after = [{**before[0], 'remainingExpiryMs': 4000}]
        result = RECOVERY.check_denied_capture(status, before, after, cpu, [payload], 2)
        self.assertTrue(result['noNotificationPublication'])
        for field, value in (('remainingExpiryMs', 5001), ('title', 'Changed'), ('seq', 2)):
            with self.subTest(field=field), self.assertRaises(AssertionError):
                RECOVERY.check_denied_capture(status, before, [{**after[0], field: value}], cpu, [payload], 2)

    def test_denial_rejects_capture_health_counter_or_history_changes(self):
        status, cpu, payload = self.fixture()
        for field, value in (('capture', 'available'), ('captured', 1), ('notification_accepted', 1),
                             ('cpu_accepted', 1), ('capture_failures', 0)):
            with self.subTest(field=field), self.assertRaises(AssertionError):
                RECOVERY.check_denied_capture({**status, field: value}, [], [], cpu, [payload], 2)
        with self.assertRaises(AssertionError):
            RECOVERY.check_denied_capture(status, [], [{'seq': 1}], cpu, [payload], 2)
        with self.assertRaises(AssertionError):
            RECOVERY.check_denied_capture(status, [], [], cpu, [payload], 1)

    def test_foreground_restart_rejects_stale_pid_and_persisted_spool(self):
        from twitch_screen_client.runtime import initial_status
        status = {**initial_status(), 'pid': 102, 'capture': 'monitor_denied', 'cpu_accepted': 2,
                  'pendingNotifications': 0, 'pendingNotificationBytes': 0}
        result = RECOVERY.check_foreground_restart(101, status)
        self.assertTrue(result['sourceLockReacquired'])
        for field, value in (('pid', 101), ('pid', True), ('capture', 'stopped'), ('cpu_accepted', 0),
                             ('captured', 1), ('notification_accepted', 1), ('pendingNotifications', 1),
                             ('pendingNotificationBytes', 1), ('notification_ambiguous', 1)):
            with self.subTest(field=field), self.assertRaises(AssertionError):
                RECOVERY.check_foreground_restart(101, {**status, field: value})

    def test_denial_rejects_unmeasured_corrupt_or_absent_memory(self):
        status, cpu, payload = self.fixture()
        cases = [([], cpu), ([{**payload, 'cpuPercent': -1}], cpu),
                 ([payload], {**cpu, 'data': {**cpu['data'], 'memoryPercent': None}}),
                 ([payload], {**cpu, 'status': 'stale', 'remainingFreshnessMs': 0})]
        for submitted, readback in cases:
            with self.subTest(submitted=submitted), self.assertRaises(AssertionError):
                RECOVERY.check_denied_capture(status, [], [], readback, submitted, 2)


class CaptureBusRestartTests(unittest.TestCase):
    def fixture(self):
        status, cpu, payload = DeniedCaptureTests().fixture()
        before = {'pid': 101, 'cpu_accepted': 2, 'notification_accepted': 2,
                  'captured': 2, 'capture_failures': 1, 'capture': 'unavailable'}
        after = {**before, 'cpu_accepted': 4, 'cpu': 'available'}
        payload['deadline'] = {'relayEpoch': cpu['relayEpoch'], 'monotonicMs': 9000}
        cards = [{'relayEpoch': cpu['relayEpoch'], 'seq': 1, 'remainingExpiryMs': 5000}]
        return before, after, cpu, payload, cards

    def test_bus_outage_requires_same_owner_independent_measured_cpu_and_no_cards(self):
        before, after, cpu, payload, cards = self.fixture()
        result = RECOVERY.check_capture_bus_outage(before, after, cpu, [payload], cards,
            [{**cards[0], 'remainingExpiryMs': 4000}])
        self.assertTrue(result['sameForegroundProcess'])
        self.assertTrue(result['measuredCpuDuringBusOutage'])
        self.assertTrue(result['noNotificationPublication'])
        self.assertEqual(result['proof']['readback'], cpu)
        self.assertEqual(result['proof']['submitted'], [payload])
        self.assertEqual(result['proof']['before'], before)

    def test_bus_outage_rejects_changed_owner_capture_and_missing_uploads(self):
        before, after, cpu, payload, cards = self.fixture()
        for field, value in (('pid', 102), ('pid', True), ('capture', 'available'),
                             ('cpu_accepted', 3), ('captured', 3),
                             ('notification_accepted', 3), ('capture_failures', 0)):
            with self.subTest(field=field), self.assertRaises(AssertionError):
                RECOVERY.check_capture_bus_outage(before, {**after, field: value}, cpu, [payload], cards, cards)

    def test_current_sampler_warmup_does_not_erase_actual_recent_acceptance(self):
        before, after, cpu, payload, cards = self.fixture()
        result = RECOVERY.check_capture_bus_outage(before, {**after, 'cpu': 'warmup'}, cpu,
                                                   [payload], cards, cards)
        self.assertTrue(result['measuredCpuDuringBusOutage'])

    def test_bus_outage_rejects_renewed_history_and_uncorrelated_measurement(self):
        before, after, cpu, payload, cards = self.fixture()
        for submitted, readback, retained in (
            ([], cpu, cards), ([{**payload, 'cpuPercent': 99}], cpu, cards),
            ([{**payload, 'deadline': {'relayEpoch': 'fedcba9876543210'}}], cpu, cards),
            ([payload], {**cpu, 'status': 'stale'}, cards),
            ([payload], {**cpu, 'data': {**cpu['data'], 'memoryPercent': None}}, cards),
            ([payload], cpu, [{**cards[0], 'remainingExpiryMs': 5001}]),
            ([payload], cpu, [{**cards[0], 'seq': 2}]),
        ):
            with self.subTest(retained=retained), self.assertRaises(AssertionError):
                RECOVERY.check_capture_bus_outage(before, after, readback, submitted, cards, retained)


class CaptureRelayRestartTests(unittest.TestCase):
    def fixture(self):
        epoch = 'fedcba9876543210'
        before = {'pid': 101, 'cpu_accepted': 2, 'notification_accepted': 2}
        after = {**before, 'capture': 'available', 'cpu_accepted': 3, 'notification_accepted': 3}
        card = {'relayEpoch': epoch, 'title': 'Synthetic after relay restart', 'body': '', 'origin': 'external'}
        status, cpu, payload = DeniedCaptureTests().fixture()
        cpu['relayEpoch'] = epoch
        payload['deadline'] = {'relayEpoch': epoch, 'monotonicMs': 9000}
        return before, after, epoch, cpu, payload, card

    def test_relay_restart_keeps_process_and_requires_new_epoch_measurement_and_card(self):
        before, after, epoch, cpu, payload, card = self.fixture()
        result = RECOVERY.check_capture_relay_restart(before, after, epoch, cpu, [payload], [card], card['title'])
        self.assertTrue(result['sameForegroundProcess'])
        self.assertTrue(result['newEpochMeasuredReadback'])
        self.assertTrue(result['noOldNotificationReplay'])

    def test_relay_restart_rejects_changed_process_stale_counters_epoch_and_replayed_cards(self):
        before, after, epoch, cpu, payload, card = self.fixture()
        cases = [({**after, 'pid': 102}, cpu, [payload], [card]),
                 ({**after, 'capture': 'stopped'}, cpu, [payload], [card]),
                 ({**after, 'cpu_accepted': 2}, cpu, [payload], [card]),
                 ({**after, 'notification_accepted': 4}, cpu, [payload], [card]),
                 (after, {**cpu, 'relayEpoch': '0123456789abcdef'}, [payload], [card]),
                 (after, cpu, [{**payload, 'deadline': {'relayEpoch': '0123456789abcdef'}}], [card]),
                 (after, cpu, [{**payload, 'cpuPercent': 99}], [card]),
                 (after, cpu, [payload], [card, card]),
                 (after, cpu, [payload], [{**card, 'body': 'Private body'}]),
                 (after, cpu, [payload], [{**card, 'origin': 'twitch'}])]
        for final, readback, submitted, cards in cases:
            with self.subTest(final=final, readback=readback, cards=cards), self.assertRaises(AssertionError):
                RECOVERY.check_capture_relay_restart(before, final, epoch, readback, submitted, cards, card['title'])


class RestartStateTests(unittest.TestCase):
    def fixture(self):
        before = {'relayEpoch': '0123456789abcdef', 'twitchDisplayEnabled': True,
                  'preferredDashboard': 'cpu'}
        epoch = 'fedcba9876543210'
        after = {**before, 'relayEpoch': epoch,
                 'weather': {'status': 'missing', 'remainingFreshnessMs': 0},
                 'cpu': {'status': 'missing', 'remainingFreshnessMs': 0}}
        source = {'relayEpoch': epoch, 'status': 'missing', 'data': None, 'remainingFreshnessMs': 0}
        return before, after, epoch, source

    def test_restart_preserves_intent_but_not_ram_availability(self):
        before, after, epoch, source = self.fixture()
        result = RECOVERY.check_restart_state(before, after, epoch, source, source, [])
        self.assertTrue(result['persistedDisplayIntentVerified'])
        self.assertTrue(result['ramAvailabilityResetVerified'])
        self.assertEqual(result['preferredDashboard'], 'cpu')

    def test_restart_rejects_lost_preference_override_or_reused_epoch(self):
        before, after, epoch, source = self.fixture()
        for field, value in (('preferredDashboard', 'generic'), ('twitchDisplayEnabled', False),
                             ('relayEpoch', before['relayEpoch'])):
            with self.subTest(field=field), self.assertRaises(AssertionError):
                RECOVERY.check_restart_state(before, {**after, field: value}, epoch, source, source, [])

    def test_restart_rejects_retained_data_history_or_stale_readiness(self):
        before, after, epoch, source = self.fixture()
        cases = [({**source, 'data': {'synthetic': True}}, source, [], after),
                 (source, {**source, 'status': 'fresh'}, [], after),
                 ({**source, 'relayEpoch': before['relayEpoch']}, source, [], after),
                 (source, source, [{'seq': 1}], after),
                 (source, source, [], {**after, 'cpu': {'status': 'fresh', 'remainingFreshnessMs': 10}})]
        for weather, cpu, history, display in cases:
            with self.subTest(weather=weather, cpu=cpu, history=history), self.assertRaises(AssertionError):
                RECOVERY.check_restart_state(before, display, epoch, weather, cpu, history)


class OwnershipTests(unittest.TestCase):
    def test_expired_calibration_requires_clock_before_new_epoch_writes(self):
        epoch = '0123456789abcdef'
        clock = {'method': 'GET', 'path': '/api/v1/clock', 'status': 200, 'payload': None}
        put = {'method': 'PUT', 'path': '/api/v1/cpu', 'status': 200,
               'payload': {'deadline': {'relayEpoch': epoch}}}
        self.assertEqual(RECOVERY.check_recovery_calibration([clock, put], epoch, False),
                         'expired_calibration_refreshed')
        for rows in ([put, clock], [clock], [clock, {**put, 'payload': {'deadline': {'relayEpoch': 'fedcba9876543210'}}}],
                     [{**clock, 'status': 500}, put], [clock, {**put, 'status': 409}]):
            with self.assertRaises(AssertionError):
                RECOVERY.check_recovery_calibration(rows, epoch, False)

    def test_usable_cached_calibration_requires_actual_old_epoch_rejection(self):
        epoch = '0123456789abcdef'
        self.assertEqual(RECOVERY.check_recovery_calibration([{'status': 409}], epoch, True),
                         'old_epoch_rejected')
        with self.assertRaises(AssertionError):
            RECOVERY.check_recovery_calibration([{'status': 200}], epoch, True)

    def test_explicit_same_ports_keeps_both_owned_listeners_across_restarts(self):
        with patch.object(RECOVERY, 'free_port', side_effect=[18001, 18002]) as allocate:
            runtime = RECOVERY.Runtime(Path('/unused.jar'), Path('/unused'), same_ports=True)
            runtime.launches = 1
            runtime.prepare_ports()
            self.assertEqual((runtime.http_port, runtime.device_port), (18001, 18002))
            self.assertEqual(runtime.base, 'http://127.0.0.1:18001')
            self.assertEqual(allocate.call_count, 2)

    def test_default_restart_still_allocates_distinct_fresh_ports(self):
        with patch.object(RECOVERY, 'free_port', side_effect=[18001, 18002, 18003, 18003, 18004]):
            runtime = RECOVERY.Runtime(Path('/unused.jar'), Path('/unused'))
            runtime.launches = 1
            runtime.prepare_ports()
            self.assertEqual((runtime.http_port, runtime.device_port), (18003, 18004))
            self.assertEqual(runtime.base, 'http://127.0.0.1:18003')

    def test_outage_duration_is_explicitly_bounded(self):
        for value in ('3200', '15000', '60000'):
            self.assertEqual(RECOVERY.outage_duration(value), int(value))
        for value in ('3199', '60001', '-1', '1.5', 'not-a-duration'):
            with self.assertRaises(Exception) as failure:
                RECOVERY.outage_duration(value)
            self.assertIsInstance(failure.exception, RECOVERY.argparse.ArgumentTypeError)

    def test_cli_rejects_invalid_outage_before_launch(self):
        result = subprocess.run([sys.executable, str(ROOT / 'tools/check_runtime_recovery.py'),
                                 '--assembly', '/missing.jar', '--evidence', '/missing-evidence',
                                 '--outage-ms', '60001', '--test-owned'],
                                capture_output=True, text=True, timeout=5)
        self.assertEqual(result.returncode, 2)
        self.assertIn('outage must be 3200..60000 ms', result.stderr)
        self.assertNotIn('Traceback', result.stderr)

    def test_cli_requires_explicit_disposable_ownership_before_launch(self):
        result = subprocess.run([sys.executable, str(ROOT / 'tools/check_runtime_recovery.py'),
                                 '--assembly', '/missing.jar', '--evidence', '/missing-evidence'],
                                capture_output=True, text=True, timeout=5)
        self.assertEqual(result.returncode, 2)
        self.assertIn('--test-owned is required', result.stderr)
        self.assertNotIn('Traceback', result.stderr)
