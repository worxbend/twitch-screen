"""Test-only Chromium witness for the installed producer/Compose/native gate.

No substitute responses, HTTP polling, desktop bus, service or physical claims.
Playwright is an optional hash-locked test dependency, never a wheel dependency.
"""
import asyncio
import hashlib
from pathlib import Path
import re
from urllib.parse import urlsplit


def number(value):
    return format(value, '.12g')


def verify_cpu_dom(source, text):
    """Validate the full numeric/absence state, including superseded push rows."""
    data = source['data']
    assert source['status'] + ':' in text, 'CPU freshness label contradicts render authority'
    if data is None:
        assert 'No measurement.' in text and 'CPU ' not in text and 'memory ' not in text
        return
    memory = data['memoryPercent']
    expected = 'unavailable' if memory is None else number(memory) + '%'
    assert 'CPU ' + number(data['cpuPercent']) + '% · memory ' + expected + '.' in text
    assert ('memory unavailable' in text) == (memory is None)


def verify_browser(proof, rows):
    """Join raw pushed snapshots and the DOM rendered from that very event."""
    assert rows and len(rows) <= 256
    for row in rows:
        snapshot = row['snapshot']
        assert re.fullmatch('[0-9a-f]{16}', snapshot['relayEpoch'])
        assert snapshot['relayEpoch'] != '0000000000000000'
        assert row['id'] == snapshot['relayEpoch'] + ':' + str(snapshot['revision'])
        assert 'Feed connected' in row['feedText']
    visible = []
    for row in rows:
        readback = row.get('canonicalReadback')
        if (readback is not None and readback['relayEpoch'] == row['snapshot']['relayEpoch']
                and readback['revision'] > row['snapshot']['revision']):
            # A real mutation GET may overtake older queued SSE. The panel
            # correctly ignores that event; preserve it, not a false render claim.
            verify_cpu_dom(readback['sources']['cpu'], row['cpuText'])
            continue
        visible.append(row)
    cpus, cards = [], []
    for application in proof['cpuApplications']:
        accepted = application['accepted']
        matching = [row for row in visible if row['snapshot']['relayEpoch'] == accepted['relayEpoch']
                    and row['snapshot']['sources']['cpu']['relayEpoch'] == accepted['relayEpoch']
                    and row['snapshot']['sources']['cpu']['revision'] == accepted['revision']
                    and row['snapshot']['sources']['cpu']['data'] == accepted['data']
                    and row['snapshot']['sources']['cpu']['status'] == 'fresh']
        assert matching, 'exact measured native application missing from browser push'
        for row in matching:
            verify_cpu_dom(row['snapshot']['sources']['cpu'], row['cpuText'])
        cpus.append({'relayEpoch': accepted['relayEpoch'], 'revision': accepted['revision'],
                     'eventIds': [row['id'] for row in matching]})
    for card in proof.get('cards', []):
        matching = [(row, item) for row in visible for item in row['snapshot']['notifications']
                    if row['snapshot']['relayEpoch'] == card['relayEpoch']
                    and item['identity']['originalRelayEpoch'] == card['relayEpoch']
                    and item['identity']['seq'] == card['seq']]
        assert matching, 'captured card identity missing from browser push'
        for row, item in matching:
            assert item['title'] == card['title'] and item['body'] == card['body'] == ''
            assert item['identity']['origin'] == card['origin'] == 'external'
            assert card['title'] in row['historyText']
        cards.append({'relayEpoch': card['relayEpoch'], 'seq': card['seq'],
                      'eventIds': [row['id'] for row, _ in matching]})
    result = {'cpuCorrelations': cpus, 'cardCorrelations': cards}
    if proof.get('cards'):
        first, second = proof['cards']
        connection = proof['cpuApplications'][0]['wire']['connection']
        fifo = []
        for row in visible:
            if row['snapshot']['relayEpoch'] != first['relayEpoch']:
                continue
            for device in row['snapshot']['devices']:
                if (device['connectionId'] != connection or device['reportStatus'] != 'fresh'
                        or device['active'] is None or device['pending'] is None or len(device['pending']) != 1):
                    continue
                active, pending = device['active']['identity'], device['pending'][0]
                expected = lambda card: {'originalRelayEpoch': card['relayEpoch'], 'seq': card['seq'], 'origin': 'external'}
                if not all(active.get(key) == value for key, value in expected(first).items()):
                    continue
                if not all(pending.get(key) == value for key, value in expected(second).items()):
                    continue
                assert 'fresh firmware observation' in row['deviceText']
                for label, card in (('Active: ', first), ('Pending 1: ', second)):
                    assert label + card['title'] in row['queueText']
                    assert card['relayEpoch'] + ':' + str(card['seq']) in row['queueText']
                fifo.append({'eventId': row['id'], 'connectionId': connection,
                             'reportRevision': device['reportRevision'], 'device': device})
        assert fifo, 'exact reported active/pending FIFO missing from browser'
        result['fifoCorrelation'] = fifo[0]
    return result


INIT = r"""(() => {
  window.producerWitness = {rows: [], errors: [], canonicalReadback: null};
  const fetchOriginal = window.fetch;
  window.fetch = async (...args) => {
    const response = await fetchOriginal(...args);
    if (String(args[0]).endsWith('/admin/snapshot') && response.ok) {
      const body = await response.clone().text();
      if (body.length > 524288) throw new Error('canonical readback exceeds witness bound');
      window.producerWitness.canonicalReadback = JSON.parse(body);
    }
    return response;
  };
  const Original = window.EventSource;
  window.EventSource = class extends Original {
    constructor(...args) {
      super(...args);
      // Register after panel handlers so DOM and raw snapshot share one event.
      queueMicrotask(() => {
        for (const type of ['snapshot', 'reset']) this.addEventListener(type, event => {
          try {
            if (event.data.length > 524288 || window.producerWitness.rows.length >= 256)
              throw new Error('finite browser witness bound exceeded');
            const value = JSON.parse(event.data);
            const get = id => document.getElementById(id)?.textContent || '';
            window.producerWitness.rows.push({id:event.lastEventId, event:type, snapshot:value,
              canonicalReadback:window.producerWitness.canonicalReadback,
              cpuText:get('cpu-value'), historyText:get('history'), queueText:get('device-queue'),
              deviceText:get('device-detail'), feedText:get('feed-status')});
          } catch (error) { window.producerWitness.errors.push(String(error)); }
        });
      });
    }
  };
})();"""


class BrowserWitness:
    def __init__(self, base, executable, directory, proof):
        self.base, self.executable, self.directory, self.proof = base, executable, directory, proof
        self.requests, self.errors = [], []
        self.browser = self.context = self.playwright = None

    async def __aenter__(self):
        from playwright.async_api import async_playwright, expect
        try:
            self.playwright = await async_playwright().start()
            self.browser = await self.playwright.chromium.launch(executable_path=str(self.executable), headless=True)
            self.context = await self.browser.new_context(viewport={'width': 1024, 'height': 900},
                                                         reduced_motion='reduce', service_workers='block')
            await self.context.add_init_script(INIT)
            self.page = await self.context.new_page()
            self.page.on('request', lambda request: self.requests.append({'method': request.method,
                'path': urlsplit(request.url).path, 'authority': urlsplit(request.url).netloc}))
            self.page.on('pageerror', lambda error: self.errors.append(str(error)))
            response = await self.page.goto(self.base)
            assert response.status == 200
            assert "connect-src 'self'" in response.headers['content-security-policy']
            await expect(self.page.locator('#feed-status')).to_contain_text('Feed connected', timeout=10000)
            self.proof['browserExecutableSha256'] = hashlib.sha256(Path(self.executable).read_bytes()).hexdigest()
            self.proof['browserVersion'] = self.browser.version
            await self.process_ids()
            return self
        except BaseException as primary:
            self.proof['browserErrors'] = list(self.errors)
            self.proof['browserRequests'] = list(self.requests)
            self.proof['browserInitializationError'] = repr(primary)
            try:
                if hasattr(self, 'page') and not isinstance(primary, asyncio.CancelledError):
                    try:
                        async with asyncio.timeout(1):
                            self.proof['browserInitialText'] = await self.page.locator('#feed-status').inner_text()
                    except BaseException as error:
                        self.proof['browserInitialDiagnosticError'] = repr(error)
            finally:
                try:
                    async with asyncio.timeout(5):
                        await self.close()
                except BaseException as error:
                    self.proof['browserCleanupError'] = repr(error)
            raise

    async def has_cpu(self, accepted):
        return await self.page.evaluate('''a => window.producerWitness.rows.some(row =>
          row.snapshot.relayEpoch === a.relayEpoch && row.snapshot.sources.cpu.revision === a.revision &&
          Object.keys(a.data).length === Object.keys(row.snapshot.sources.cpu.data || {}).length &&
          Object.keys(a.data).every(key => row.snapshot.sources.cpu.data[key] === a.data[key]))''', accepted)

    async def fifo(self, first, second):
        from playwright.async_api import expect
        await expect(self.page.locator('#device-queue')).to_contain_text('Active: ' + first['title'], timeout=5000)
        await expect(self.page.locator('#device-queue')).to_contain_text('Pending 1: ' + second['title'], timeout=5000)
        await self.page.screenshot(path=str(self.directory / 'producer-fifo.png'), full_page=True)

    async def display(self, payload):
        from playwright.async_api import expect
        choice = 'twitch' if payload.get('twitchDisplayEnabled') else 'cpu'
        await self.page.locator('[data-display="' + choice + '"]').click()
        await expect(self.page.locator('[data-display="' + choice + '"]')).to_have_attribute('aria-pressed', 'true')
        await expect(self.page.locator('#test-card')).to_be_enabled()

    async def recover_feed(self):
        from playwright.async_api import expect
        # Chromium's offline flag does not sever an already-open SSE socket.
        # Exercise real page subscription teardown/reload, not a synthetic error.
        prior = await self.page.evaluate('window.producerWitness')
        self.proof['browserRowsBeforeReload'] = prior['rows']
        assert not prior['errors']
        await self.page.reload()
        await expect(self.page.locator('#feed-status')).to_contain_text('Feed connected', timeout=10000)
        self.proof['browserPageReloadRecovered'] = True

    async def collect(self):
        value = await self.page.evaluate('window.producerWitness')
        self.proof['browserRows'] = self.proof.get('browserRowsBeforeReload', []) + value['rows']
        self.proof['browserErrors'] = self.errors + value['errors']
        self.proof['browserRequests'] = self.requests
        # Validate actual complete pushed/readback DTOs against the peer contract.
        import json
        import sys
        root = Path(__file__).resolve().parents[2]
        sys.path.insert(0, str(root / 'tools'))
        from check_desk_contract import validate_dto
        for row in self.proof['browserRows']:
            validate_dto('AdminSnapshot', row['snapshot'])
            assert len(json.dumps(row['snapshot']).encode()) <= 524288
            if row.get('canonicalReadback') is not None:
                validate_dto('AdminSnapshot', row['canonicalReadback'])
        assert not self.proof['browserErrors']
        assert all(row['authority'] == urlsplit(self.base).netloc for row in self.requests)
        # Only one initial GET plus canonical reconciliation after each explicit display mutation.
        ordinary = [row for row in self.requests if row['method'] == 'GET' and row['path'].startswith('/api/')
                    and row['path'] != '/api/v1/admin/events']
        display = [row for row in self.requests if row['method'] == 'PATCH' and row['path'] == '/api/v1/display']
        assert len(ordinary) == 1 + int(self.proof.get('browserPageReloadRecovered', False)) + len(display)
        assert all(row['path'] == '/api/v1/admin/snapshot' for row in ordinary)
        assert not any(row['method'] == 'POST' for row in self.requests)
        self.proof['browserNoPollingOrResend'] = True
        self.proof['browserCorrelations'] = verify_browser(self.proof, self.proof['browserRows'])

    async def process_ids(self):
        assert self.browser is not None
        session = await self.browser.new_browser_cdp_session()
        try:
            value = await session.send('SystemInfo.getProcessInfo')
            self.proof['browserPids'] = sorted(set(self.proof.get('browserPids', [])) |
                                               {int(row['id']) for row in value['processInfo']})
        finally:
            await session.detach()

    async def close(self):
        try:
            if self.browser is not None:
                await self.process_ids()
        finally:
            try:
                if self.context is not None:
                    await self.context.close()
            finally:
                try:
                    if self.browser is not None:
                        await self.browser.close()
                finally:
                    if self.playwright is not None:
                        await self.playwright.stop()
        self.proof['browserClosed'] = True

    async def __aexit__(self, *exc):
        try:
            try:
                await self.collect()
            except Exception as error:
                if exc[0] is None:
                    raise
                # Preserve the scenario's primary failure; retain secondary
                # witness failure rather than replacing the original traceback.
                self.proof['browserCollectionError'] = repr(error)
        finally:
            await self.close()
