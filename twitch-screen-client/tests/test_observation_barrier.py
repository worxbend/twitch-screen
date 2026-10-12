"""Exact recovery readback ordering on real test-owned HTTP sockets."""
import asyncio
import copy
import json
import unittest
from unittest.mock import patch
from types import SimpleNamespace

import httpx

from test_runtime_recovery_harness import RECOVERY
import test_runtime_recovery_harness as HARNESS


class QuotaSetupTests(unittest.IsolatedAsyncioTestCase):
    async def test_refill_setup_is_bounded_and_every_actual_attempt_is_retained(self):
        gateway = SimpleNamespace(records=[], quota_observations=[])
        owners = []
        class RefilledDelivery:
            def __init__(self, *args):
                self.exchanges = [None, None]
                self.status = {'cpu_accepted': 0}
                self.closed = False
                owners.append(self)
            async def calibrated(self, exchange):
                pass
            def sample(self, measured, values):
                self.values = values
            async def metrics(self):
                gateway.records.append({'method': 'PUT', 'status': 200, 'payload': self.values})
                self.status['cpu_accepted'] = 1
                await asyncio.Event().wait()
            async def close(self):
                self.closed = True
        async def respond(request):
            if request.method == 'PUT':
                return httpx.Response(429, json={'code': 'rate_limited'}, headers={'Retry-After': '1'})
            return httpx.Response(200, json={'revision': 1, 'data': {'synthetic': True}})
        async with httpx.AsyncClient(transport=httpx.MockTransport(respond)) as client:
            with patch.object(RECOVERY, 'Delivery', RefilledDelivery):
                with self.assertRaisesRegex(AssertionError, 'within five observed setup attempts'):
                    await RECOVERY.quota_checks(None, gateway, client, 'http://synthetic.test', 0, {})
        self.assertEqual(len(gateway.quota_observations[0]['attempts']), 5)
        self.assertEqual(len(gateway.records), 5)
        self.assertTrue(all(row['records'][0]['status'] == 200
                            for row in gateway.quota_observations[0]['attempts']))
        self.assertTrue(all(owner.closed for owner in owners))


class ObservationBarrierTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        _, self.cpu, self.payload = HARNESS.DeniedCaptureTests().fixture()
        self.payload['deadline'] = {'relayEpoch': self.cpu['relayEpoch'], 'monotonicMs': 9000}
        self.committed = asyncio.Event()
        self.read = asyncio.Event()
        self.release = asyncio.Event()
        self.tasks = set()
        self.server = await asyncio.start_server(self.handle, '127.0.0.1', 0)
        self.port = self.server.sockets[0].getsockname()[1]
        self.base = f'http://127.0.0.1:{self.port}'

    async def handle(self, reader, writer):
        task = asyncio.current_task()
        self.tasks.add(task)
        try:
            header = await reader.readuntil(b'\r\n\r\n')
            fields = dict(row.lower().split(':', 1) for row in header.decode().split('\r\n')[1:] if ':' in row)
            await reader.readexactly(int(fields.get('content-length', 0)))
            body = json.dumps(self.cpu).encode()
            writer.write(b'HTTP/1.1 200 OK\r\nContent-Length: ' + str(len(body)).encode() +
                         b'\r\nConnection: close\r\n\r\n' + body)
            await writer.drain()
            if header.startswith(b'PUT '):
                self.committed.set()
                await self.release.wait()
            else:
                self.read.set()
        finally:
            writer.close()
            await writer.wait_closed()
            self.tasks.discard(task)

    async def asyncTearDown(self):
        self.release.set()
        self.server.close()
        await self.server.wait_closed()
        for task in list(self.tasks):
            task.cancel()
        await asyncio.gather(*list(self.tasks), return_exceptions=True)

    async def test_get_before_gateway_record_waits_for_exact_success_without_resending(self):
        diagnostics = []
        async with RECOVERY.FaultGateway(self.port) as gateway, httpx.AsyncClient(trust_env=False) as client:
            put = asyncio.create_task(client.put(gateway.url + '/api/v1/cpu', json=self.payload))
            observation = None
            try:
                await asyncio.wait_for(self.committed.wait(), 1)
                observation = asyncio.create_task(RECOVERY.correlated_cpu_readback(
                    client, self.base, gateway, 0, diagnostics, timeout=1))
                await asyncio.wait_for(self.read.wait(), 1)
                self.assertFalse(observation.done())
                self.assertEqual(gateway.records, [])
                self.release.set()
                await put
                readback, submitted = await observation
                self.assertEqual(readback, self.cpu)
                self.assertEqual(submitted, [self.payload])
                self.assertEqual(len(gateway.requests), 1)
                self.assertTrue(diagnostics[-1]['matched'])
                self.assertEqual(diagnostics[-1]['accepted']['revision'], self.cpu['revision'])
            finally:
                self.release.set()
                await asyncio.gather(put, return_exceptions=True)
                if observation is not None:
                    observation.cancel()
                    await asyncio.gather(observation, return_exceptions=True)

    async def test_equal_measurements_do_not_match_a_different_revision_or_epoch(self):
        diagnostics = []
        async with RECOVERY.FaultGateway(self.port) as gateway, httpx.AsyncClient(trust_env=False) as client:
            accepted = copy.deepcopy(self.cpu)
            accepted['revision'] -= 1
            gateway.records.append({'method': 'PUT', 'status': 200, 'payload': self.payload, 'response': accepted})
            with self.assertRaises(TimeoutError):
                await RECOVERY.correlated_cpu_readback(client, self.base, gateway, 0, diagnostics, timeout=.05)
            self.assertFalse(diagnostics[-1]['matched'])
            self.assertEqual(diagnostics[-1]['readback'], self.cpu)
            self.assertEqual(diagnostics[-1]['records'][0]['response'], accepted)
            accepted['revision'] = self.cpu['revision']
            accepted['relayEpoch'] = 'fedcba9876543210'
            with self.assertRaises(TimeoutError):
                await RECOVERY.correlated_cpu_readback(client, self.base, gateway, 0, diagnostics, timeout=.05)

    async def test_rejected_or_changed_submission_never_certifies_get(self):
        async with RECOVERY.FaultGateway(self.port) as gateway, httpx.AsyncClient(trust_env=False) as client:
            for code, payload in ((429, self.payload), (200, {**self.payload, 'cpuPercent': 99})):
                gateway.records[:] = [{'method': 'PUT', 'status': code, 'payload': payload, 'response': self.cpu}]
                diagnostics = []
                with self.subTest(code=code), self.assertRaises(TimeoutError):
                    await RECOVERY.correlated_cpu_readback(client, self.base, gateway, 0, diagnostics, timeout=.05)
                self.assertFalse(diagnostics[-1]['matched'])

    async def test_boundary_excludes_prior_process_success(self):
        async with RECOVERY.FaultGateway(self.port) as gateway, httpx.AsyncClient(trust_env=False) as client:
            gateway.records.append({'method': 'PUT', 'status': 200, 'payload': self.payload, 'response': self.cpu})
            diagnostics = []
            with self.assertRaises(TimeoutError):
                await RECOVERY.correlated_cpu_readback(client, self.base, gateway, 1, diagnostics, timeout=.05)
            self.assertEqual(diagnostics[-1]['records'], [])
