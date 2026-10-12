"""Real Chromium checks against a running production relay. Browser tooling is test-only; never access providers or hardware."""
from pathlib import Path
import argparse
import json
import os
import subprocess
from urllib.parse import urlsplit
from playwright.sync_api import sync_playwright, expect


def run(base, evidence, executable, native=None, device_port=None):
    evidence.mkdir(parents=True, exist_ok=True)
    result = {'testedLayer': 'actual production-origin Chromium panel; not physical/mobile/provider acceptance', 'cases': []}
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(executable_path=str(executable), headless=True)
        try:
            for width in (320, 768, 1024, 1440):
                context = browser.new_context(viewport={'width': width, 'height': 900}, reduced_motion='reduce', service_workers='block')
                page = context.new_page()
                errors = []
                requests = []
                page.on('pageerror', lambda error: errors.append(str(error)))
                page.on('request', lambda request: requests.append({'url': request.url, 'method': request.method}))
                try:
                    response = page.goto(base)
                    assert response.status == 200
                    assert "connect-src 'self'" in response.headers['content-security-policy']
                    expect(page.locator('#feed-status')).to_contain_text('Feed connected', timeout=10000)
                    geometry = page.evaluate('''() => ({width:innerWidth, content:document.documentElement.scrollWidth,
                        targets:[...document.querySelectorAll('button,a,input,select')].filter(e=>e.getClientRects().length).map(e=>({height:e.getBoundingClientRect().height,width:e.getBoundingClientRect().width})),
                        animations:[...document.querySelectorAll('*')].filter(e=>getComputedStyle(e).animationName!=='none').length})''')
                    assert geometry['content'] <= width, geometry
                    assert all(row['height'] >= 44 and row['width'] >= 44 for row in geometry['targets']), geometry
                    assert geometry['animations'] == 0
                    page.keyboard.press('Tab')
                    assert page.evaluate("document.activeElement.matches('button,a,input,select')")
                    assert all(urlsplit(request['url']).netloc == urlsplit(base).netloc for request in requests)
                    assert not errors, errors
                    page.screenshot(path=str(evidence / f'panel-{width}.png'), full_page=True)
                    result['cases'].append({'width': width, 'responsiveTargetsKeyboardReducedMotion': True, 'externalRequests': False})
                finally:
                    context.close()
            context = browser.new_context(viewport={'width': 1024, 'height': 900}, service_workers='block')
            page = context.new_page()
            requests = []
            errors = []
            page.on('request', lambda request: requests.append((request.method, urlsplit(request.url).path)))
            page.on('pageerror', lambda error: errors.append(str(error)))
            page.goto(base)
            expect(page.locator('#feed-status')).to_contain_text('Feed connected')
            for choice in ('cpu', 'twitch', 'weather', 'generic'):
                page.locator(f'[data-display="{choice}"]').click()
                expect(page.locator(f'[data-display="{choice}"]')).to_have_attribute('aria-pressed', 'true')
                expect(page.locator('#action-status')).to_contain_text('Relay accepted')
                expect(page.locator('#test-card')).to_be_enabled()
                state = page.request.get(base + '/api/v1/display').json()
                assert state['twitchDisplayEnabled'] == (choice == 'twitch')
                if choice != 'twitch':
                    assert state['preferredDashboard'] == choice
                elif choice == 'twitch':
                    assert state['preferredDashboard'] == 'cpu'
            result['cases'].append({'displayChoices': 4, 'rememberedPreferenceAndCanonicalReadback': True})
            page.locator('#monitor-channel').fill('synthetic')
            page.locator('#monitor-form button').click()
            expect(page.locator('#monitor-detail')).to_contain_text('disabled/synthetic')
            expect(page.locator('#test-card')).to_be_enabled()
            result['cases'].append({'actualMonitoringApply': True, 'independentDisabledMode': True})
            response = page.request.put(base + '/api/v1/cpu', data={'sourceId': 'browser', 'host': 'synthetic', 'cpuPercent': 37.25, 'memoryPercent': 0, 'freshnessMs': 1000})
            assert response.status == 200
            expect(page.locator('#cpu-value')).to_contain_text('CPU 37.25%')
            expect(page.locator('#cpu-value')).to_contain_text('memory 0%')
            expect(page.locator('#cpu-value')).to_contain_text('stale', timeout=5000)
            payload = '<img src=x onerror="window.panelXss=1"><script>window.panelXss=1</script>'
            response = page.request.post(base + '/api/v1/notifications', data={'type': 'info', 'title': payload, 'body': payload})
            assert response.status == 200
            expect(page.locator('#history')).to_contain_text(payload)
            assert page.locator('#history img, #history script').count() == 0
            assert page.evaluate('window.panelXss === undefined')
            result['cases'].append({'actualNumericSourceStalenessAndStoredXssSafeText': True})
            before = sum(method == 'GET' and path.startswith('/api/') and path != '/api/v1/admin/events' for method, path in requests)
            page.wait_for_timeout(7000)
            after = sum(method == 'GET' and path.startswith('/api/') and path != '/api/v1/admin/events' for method, path in requests)
            assert after == before, requests
            result['cases'].append({'idleSeconds': 7, 'periodicApiFetches': 0})
            page.locator('#test-card').click()
            expect(page.locator('#action-status')).to_contain_text('Relay accepted')
            expect(page.locator('#test-card')).to_be_enabled()
            assert sum(method == 'POST' and path == '/api/v1/notifications' for method, path in requests) == 1
            result['cases'].append({'explicitTestCardPosts': 1, 'noAutomaticResend': True})
            second = context.new_page()
            second.goto(base)
            expect(second.locator('#feed-status')).to_contain_text('Feed connected')
            third = context.new_page()
            third.goto(base)
            expect(third.locator('#feed-status')).to_contain_text('disconnected', timeout=5000)
            third.close()
            second.close()
            page.locator('[data-display="cpu"]').click()
            expect(page.locator('[data-display="cpu"]')).to_have_attribute('aria-pressed', 'true')
            expect(page.locator('#test-card')).to_be_enabled()
            result['cases'].append({'actualTwoTabAdmissionThirdRejectedAndFirstStillControls': True})
            before_monitoring = page.request.get(base + '/api/v1/twitch/monitoring').json()
            page.locator('#monitor-channel').fill('synthetic')
            page.locator('#monitor-mode').select_option('live')
            page.locator('#monitor-form button').click()
            expect(page.locator('#action-status')).to_contain_text('No automatic resend')
            expect(page.locator('#test-card')).to_be_enabled()
            after_monitoring = page.request.get(base + '/api/v1/twitch/monitoring').json()
            assert after_monitoring['desired'] == before_monitoring['desired']
            assert after_monitoring['sourceGeneration'] == before_monitoring['sourceGeneration']
            result['cases'].append({'actualMissingConfigurationRejectsMonitoringWithoutMutation': True})
            context.set_offline(True)
            try:
                expect(page.locator('#feed-status')).to_contain_text('disconnected', timeout=10000)
                expect(page.locator('#cpu-value')).to_contain_text('not current')
                expect(page.locator('#weather-value')).to_contain_text('not current')
                expect(page.locator('#charts caption').first).to_contain_text('not current')
                before_posts = sum(method == 'POST' and path == '/api/v1/notifications' for method, path in requests)
                page.locator('#test-card').click()
                expect(page.locator('#action-status')).to_contain_text('Canonical readback unavailable', timeout=10000)
                expect(page.locator('#test-card')).to_be_enabled()
                assert sum(method == 'POST' and path == '/api/v1/notifications' for method, path in requests) == before_posts + 1
                page.wait_for_timeout(1200)
                assert sum(method == 'POST' and path == '/api/v1/notifications' for method, path in requests) == before_posts + 1
            finally:
                context.set_offline(False)
            expect(page.locator('#feed-status')).to_contain_text('Feed connected', timeout=15000)
            assert 'not current' not in page.locator('#cpu-value').inner_text()
            response = page.request.put(base + '/api/v1/cpu', data={'sourceId': 'browser-recovery', 'host': 'synthetic', 'cpuPercent': 48.75, 'memoryPercent': 0})
            assert response.status == 200
            expect(page.locator('#cpu-value')).to_contain_text('CPU 48.75%')
            result['cases'].append({'actualBrowserOfflineFrozenSourcesChartsAndPushRecovery': True, 'offlinePostAttempts': 1, 'automaticPostRetries': 0})
            page.evaluate("document.documentElement.style.zoom = '2'")
            zoom = page.evaluate('''() => ({width:innerWidth,content:document.documentElement.scrollWidth,
                overflowing:[...document.querySelectorAll('main *')].filter(e=>e.getBoundingClientRect().right>innerWidth).map(e=>({tag:e.tagName,id:e.id,right:e.getBoundingClientRect().right}))})''')
            assert zoom['content'] <= zoom['width'], zoom
            page.evaluate("document.documentElement.style.zoom = ''")
            result['cases'].append({'cssZoom200PercentNoHorizontalOverflow': True})
            assert not errors, errors
            if native is not None:
                assert device_port is not None
                env = {key: value for key, value in os.environ.items() if not key.startswith('FIRMWARE_PEER_')}
                env.update(FIRMWARE_PEER_PORT=str(device_port), FIRMWARE_PEER_SECONDS='25',
                           FIRMWARE_PEER_PROFILE_READY='1', FIRMWARE_PEER_REQUIRE_DESK='1')
                peer = None
                publications = []
                try:
                    with (evidence / 'native-observations.jsonl').open('w') as log, (evidence / 'native-stderr.log').open('w') as stderr:
                        peer = subprocess.Popen([str(native)], env=env, stdout=log, stderr=stderr)
                        fresh_option = page.locator('#device-select option').filter(has_text=' · fresh')
                        expect(fresh_option).to_have_count(1, timeout=10000)
                        page.locator('#device-select').select_option(fresh_option.get_attribute('value'))
                        expect(page.locator('#device-detail')).to_contain_text('fresh firmware observation', timeout=10000)
                        for index, hold in enumerate((5000, 1000)):
                            response = page.request.post(base + '/api/v1/notifications', data={
                                'type': 'info', 'title': f'Synthetic browser FIFO {index}', 'body': '', 'ttlMs': hold})
                            assert response.status == 200
                            publications.append(response.json())
                        expect(page.locator('#device-queue')).to_contain_text('Active: Synthetic browser FIFO 0', timeout=5000)
                        expect(page.locator('#device-queue')).to_contain_text('Pending 1: Synthetic browser FIFO 1', timeout=5000)
                        current = page.request.get(base + '/api/v1/admin/snapshot').json()
                        device = next(row for row in current['devices'] if row['reportStatus'] == 'fresh' and row['active'] is not None)
                        assert device['active']['identity']['originalRelayEpoch'] == publications[0]['relayEpoch']
                        assert device['active']['identity']['seq'] == publications[0]['seq']
                        assert device['pending'][0]['originalRelayEpoch'] == publications[1]['relayEpoch']
                        assert device['pending'][0]['seq'] == publications[1]['seq']
                        page.locator('[data-display="twitch"]').click()
                        expect(page.locator('[data-display="twitch"]')).to_have_attribute('aria-pressed', 'true')
                        expect(page.locator('#device-detail')).to_contain_text('Base becomes visible after the active card finishes.')
                        page.screenshot(path=str(evidence / 'actual-device-fifo.png'), full_page=True)
                        expect(page.locator('#device-queue')).to_contain_text('Reported empty', timeout=15000)
                        assert peer.wait(timeout=30) == 0
                    rows = [json.loads(line) for line in (evidence / 'native-observations.jsonl').read_text().splitlines()]
                    started = [row for row in rows if row['event'] == 'started']
                    assert [row['seq'] for row in started] == [row['seq'] for row in publications]
                    assert all(row['origin'] == 0 for row in started)
                    assert next(row for row in rows if row['event'] == 'summary')['completed'] == 2
                    result['cases'].append({'actualNativeFifoAndActiveIdentity': True,
                                             'canonicalObservation': device, 'publications': publications,
                                             'activeCardFinishesAcrossBrowserControl': True})
                finally:
                    if peer is not None and peer.poll() is None:
                        peer.terminate()
                        peer.wait(timeout=5)
            context.close()
            result['passed'] = True
        finally:
            browser.close()
    (evidence / 'result.json').write_text(json.dumps(result, indent=2))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--browser-executable', type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(run(args.url, args.evidence.resolve(), args.browser_executable.resolve()), indent=2))


if __name__ == '__main__':
    main()
