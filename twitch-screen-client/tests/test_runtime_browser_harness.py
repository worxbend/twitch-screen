"""Synthetic verdict fixtures cannot substitute for actual Chromium evidence."""
import copy
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import check_runtime_browser as browser


class BrowserJoinTests(unittest.TestCase):
    def fixture(self):
        epoch = '0123456789abcdef'
        connection = '1111111111111111'
        card = {'relayEpoch': epoch, 'seq': 1, 'title': 'Synthetic first', 'body': '', 'origin': 'external'}
        second = {**card, 'seq': 2, 'title': 'Synthetic second'}
        accepted = {'relayEpoch': epoch, 'revision': 3, 'data': {'cpuPercent': 12.34, 'memoryPercent': 56.78}}
        application = {'accepted': accepted, 'wire': {'connection': connection}}
        device = {'connectionId': connection, 'reportStatus': 'fresh', 'reportRevision': 2,
                  'active': {'identity': {'originalRelayEpoch': epoch, 'seq': 1, 'origin': 'external'}},
                  'pending': [{'originalRelayEpoch': epoch, 'seq': 2, 'origin': 'external'}]}
        row = {'snapshot': {'relayEpoch': epoch, 'revision': 7, 'sources': {'cpu': {**accepted, 'status': 'fresh'}},
                           'notifications': [{'identity': {'originalRelayEpoch': epoch, 'seq': c['seq'], 'origin': 'external'},
                                              'title': c['title'], 'body': ''} for c in (card, second)], 'devices': [device]},
               'id': epoch + ':7', 'cpuText': 'Host: CPU 12.34% · memory 56.78%. fresh: fresh',
               'feedText': 'Feed connected', 'historyText': 'Synthetic first Synthetic second',
               'queueText': 'Active: Synthetic first · external · ' + epoch + ':1 Pending 1: Synthetic second · external · ' + epoch + ':2',
               'deviceText': 'fresh firmware observation'}
        return {'cpuApplications': [application], 'cards': [card, second]}, [row]

    def test_exact_numeric_history_and_ordered_report_join(self):
        proof, rows = self.fixture()
        result = browser.verify_browser(proof, rows)
        self.assertEqual(len(result['cpuCorrelations']), 1)
        self.assertEqual(len(result['cardCorrelations']), 2)
        self.assertEqual(result['fifoCorrelation']['reportRevision'], 2)

    def test_numeric_identity_or_dom_cannot_be_rescued(self):
        for mutation in ('epoch', 'revision', 'numeric', 'stale', 'dom_cpu', 'dom_memory', 'feed', 'id'):
            proof, rows = self.fixture()
            row = rows[0]
            source = row['snapshot']['sources']['cpu']
            if mutation == 'epoch': source['relayEpoch'] = 'fedcba9876543210'
            elif mutation == 'revision': source['revision'] = 4
            elif mutation == 'numeric': source['data']['cpuPercent'] = 99
            elif mutation == 'stale': source['status'] = 'stale'
            elif mutation == 'dom_cpu': row['cpuText'] = 'CPU 99% · memory 56.78%'
            elif mutation == 'dom_memory': row['cpuText'] = 'CPU 12.34% · memory unavailable'
            elif mutation == 'feed': row['feedText'] = 'Feed disconnected'
            else: row['id'] = 'fedcba9876543210:7'
            with self.subTest(mutation=mutation), self.assertRaises(AssertionError):
                browser.verify_browser(proof, rows)

    def test_fifo_requires_exact_connection_origin_order_and_dom(self):
        for mutation in ('connection', 'stale', 'active_epoch', 'pending_seq', 'origin', 'queue_dom', 'unknown'):
            proof, rows = self.fixture()
            device = rows[0]['snapshot']['devices'][0]
            if mutation == 'connection': device['connectionId'] = '2222222222222222'
            elif mutation == 'stale': device['reportStatus'] = 'stale'
            elif mutation == 'active_epoch': device['active']['identity']['originalRelayEpoch'] = 'fedcba9876543210'
            elif mutation == 'pending_seq': device['pending'][0]['seq'] = 1
            elif mutation == 'origin': device['pending'][0]['origin'] = 'twitch'
            elif mutation == 'queue_dom': rows[0]['queueText'] = 'Reported empty'
            else: device['pending'] = None
            with self.subTest(mutation=mutation), self.assertRaises(AssertionError):
                browser.verify_browser(proof, rows)

    def test_real_newer_canonical_readback_supersedes_queued_push_only(self):
        proof, rows = self.fixture()
        old = copy.deepcopy(rows[0])
        old['canonicalReadback'] = copy.deepcopy(old['snapshot'])
        old['canonicalReadback']['revision'] = 8
        old['canonicalReadback']['sources']['cpu']['data']['cpuPercent'] = 99
        old['cpuText'] = 'CPU 99% · memory 56.78%. fresh: fresh'
        browser.verify_browser(proof, [old, *rows])
        with self.assertRaises(AssertionError):
            browser.verify_browser(proof, [old])
        old['cpuText'] = 'CPU 77%'
        with self.assertRaises(AssertionError):
            browser.verify_browser(proof, [old, *rows])

    def test_superseded_memory_absent_zero_and_measured_state(self):
        for memory in (None, 0, 56.78):
            proof, rows = self.fixture()
            old = copy.deepcopy(rows[0])
            old['canonicalReadback'] = copy.deepcopy(old['snapshot'])
            old['canonicalReadback']['revision'] = 8
            old['canonicalReadback']['sources']['cpu']['data']['memoryPercent'] = memory
            expected = 'unavailable' if memory is None else browser.number(memory) + '%'
            old['cpuText'] = 'CPU 12.34% · memory ' + expected + '. fresh: fresh'
            browser.verify_browser(proof, [old, *rows])
            for wrong in ('unavailable', '0%', '99%'):
                if wrong == expected:
                    continue
                old['cpuText'] = 'CPU 12.34% · memory ' + wrong + '. fresh: fresh'
                with self.subTest(memory=memory, wrong=wrong), self.assertRaises(AssertionError):
                    browser.verify_browser(proof, [old, *rows])

    def test_superseded_missing_measurement_and_freshness_fail_closed(self):
        proof, rows = self.fixture()
        old = copy.deepcopy(rows[0])
        old['canonicalReadback'] = copy.deepcopy(old['snapshot'])
        old['canonicalReadback']['revision'] = 8
        source = old['canonicalReadback']['sources']['cpu']
        source['data'] = None
        source['status'] = 'missing'
        old['cpuText'] = 'CPU 12.34% · memory 56.78%. fresh: fresh'
        with self.assertRaises(AssertionError):
            browser.verify_browser(proof, [old, *rows])
        old['cpuText'] = 'missing: never_supplied. No measurement.'
        browser.verify_browser(proof, [old, *rows])
        source['data'] = copy.deepcopy(rows[0]['snapshot']['sources']['cpu']['data'])
        source['status'] = 'stale'
        with self.assertRaises(AssertionError):
            browser.verify_browser(proof, [old, *rows])
        old['cpuText'] = 'CPU 12.34% · memory 56.78%. stale: expired'
        browser.verify_browser(proof, [old, *rows])

    def test_history_requires_exact_external_content(self):
        for mutation in ('title', 'body', 'epoch', 'origin', 'dom'):
            proof, rows = self.fixture()
            card = rows[0]['snapshot']['notifications'][0]
            if mutation == 'epoch': card['identity']['originalRelayEpoch'] = 'fedcba9876543210'
            elif mutation == 'origin': card['identity']['origin'] = 'twitch'
            elif mutation == 'dom': rows[0]['historyText'] = 'Synthetic second'
            else: card[mutation] = 'changed'
            with self.subTest(mutation=mutation), self.assertRaises(AssertionError):
                browser.verify_browser(proof, rows)


class BrowserCleanupTests(unittest.IsolatedAsyncioTestCase):
    async def startup_failure(self, primary, diagnostic, cleanup=None):
        from unittest.mock import AsyncMock, MagicMock, patch
        import types
        page = MagicMock()
        page.goto = AsyncMock(side_effect=primary)
        page.locator.return_value.inner_text = AsyncMock(side_effect=diagnostic)
        context = MagicMock()
        context.add_init_script = AsyncMock()
        context.new_page = AsyncMock(return_value=page)
        engine = MagicMock()
        engine.new_context = AsyncMock(return_value=context)
        playwright = MagicMock()
        playwright.chromium.launch = AsyncMock(return_value=engine)
        manager = MagicMock()
        manager.start = AsyncMock(return_value=playwright)
        module = types.ModuleType('playwright.async_api')
        setattr(module, 'async_playwright', lambda: manager)
        setattr(module, 'expect', MagicMock())
        witness = browser.BrowserWitness('http://127.0.0.1:1', Path('unused'), Path('unused'), {})
        witness.close = AsyncMock(side_effect=cleanup)
        with patch.dict(sys.modules, {'playwright': types.ModuleType('playwright'), 'playwright.async_api': module}):
            with self.assertRaises(type(primary)) as raised:
                import asyncio
                await asyncio.wait_for(witness.__aenter__(), 1.5)
        self.assertIs(raised.exception, primary)
        witness.close.assert_awaited_once()
        return witness

    async def test_startup_diagnostic_failure_preserves_primary_and_closes(self):
        witness = await self.startup_failure(RuntimeError('primary navigation failure'),
                                             ValueError('closed page diagnostic failure'))
        self.assertIn('closed page diagnostic failure', witness.proof['browserInitialDiagnosticError'])

    async def test_startup_cleanup_failure_is_secondary(self):
        witness = await self.startup_failure(RuntimeError('primary navigation failure'),
                                             ValueError('closed page'), RuntimeError('cleanup failed'))
        self.assertIn('cleanup failed', witness.proof['browserCleanupError'])

    async def test_startup_cancellation_closes_without_replacing_cancellation(self):
        import asyncio
        await self.startup_failure(asyncio.CancelledError(), ValueError('closed page'))

    async def test_startup_hung_diagnostic_is_bounded_and_closes(self):
        import asyncio
        async def hung():
            await asyncio.Event().wait()
        started = asyncio.get_running_loop().time()
        witness = await self.startup_failure(RuntimeError('primary navigation failure'), hung)
        self.assertLess(asyncio.get_running_loop().time() - started, 2)
        self.assertIn('TimeoutError', witness.proof['browserInitialDiagnosticError'])

    async def test_collection_failure_closes_and_fails_successful_scenario(self):
        from unittest.mock import AsyncMock
        witness = browser.BrowserWitness('http://127.0.0.1:1', Path('unused'), Path('unused'), {})
        witness.collect = AsyncMock(side_effect=ValueError('bad witness'))
        witness.close = AsyncMock()
        with self.assertRaises(ValueError):
            await witness.__aexit__(None, None, None)
        witness.close.assert_awaited_once()

    async def test_process_inventory_failure_still_closes_every_browser_owner(self):
        from unittest.mock import AsyncMock
        witness = browser.BrowserWitness('http://127.0.0.1:1', Path('unused'), Path('unused'), {})
        witness.process_ids = AsyncMock(side_effect=RuntimeError('inventory failed'))
        witness.context = AsyncMock()
        witness.browser = AsyncMock()
        witness.playwright = AsyncMock()
        with self.assertRaises(RuntimeError):
            await witness.close()
        witness.context.close.assert_awaited_once()
        witness.browser.close.assert_awaited_once()
        witness.playwright.stop.assert_awaited_once()

    async def test_collection_failure_does_not_replace_primary_scenario_failure(self):
        from unittest.mock import AsyncMock
        witness = browser.BrowserWitness('http://127.0.0.1:1', Path('unused'), Path('unused'), {})
        witness.collect = AsyncMock(side_effect=ValueError('bad witness'))
        witness.close = AsyncMock()
        original = RuntimeError('original scenario failure')
        self.assertIsNone(await witness.__aexit__(RuntimeError, original, None))
        self.assertIn('bad witness', witness.proof['browserCollectionError'])
        witness.close.assert_awaited_once()
