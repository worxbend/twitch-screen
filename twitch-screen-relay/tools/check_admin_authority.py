"""Real Main/Chromium epoch races and stalled-feed expiry. Holds actual browser callbacks/GET responses, never invents snapshots."""
from pathlib import Path
import argparse
import hashlib
import http.client
import json
import os
import socket
import subprocess
import tempfile
import time

from playwright.sync_api import sync_playwright, expect
from check_admin_runtime import ROOT, free_port

# Test-only transport scheduling: retain actual MessageEvents and native Response objects.
# Deliver callbacks even after close to model browser tasks queued before subscription retirement.
SCHEDULER = r"""(() => {
  const NativeEventSource = window.EventSource, nativeFetch = window.fetch.bind(window);
  const control = window.authorityTransport = {holdEvents:false, holdReads:false, events:[], reads:[], observed:[], feeds:[]};
  window.EventSource = class {
    constructor(url) {
      this.native = new NativeEventSource(url); this.handlers = {}; this.closed = false;
      control.feeds.push(this);
      this.native.onerror = event => this.deliver('error', event);
    }
    deliver(type, event) {
      if (type !== 'error') control.observed.push({type, data:event.data, id:event.lastEventId});
      const invoke = () => type === 'error' ? this.onerror?.(event) : this.handlers[type]?.(event);
      if (control.holdEvents) control.events.push({type, data:event.data, id:event.lastEventId, invoke});
      else invoke();
    }
    addEventListener(type, handler) {
      this.handlers[type] = handler;
      this.native.addEventListener(type, event => this.deliver(type, event));
    }
    close() { this.closed = true; this.native.close(); }
  };
  window.fetch = async (...args) => {
    const response = await nativeFetch(...args);
    if (control.holdReads && String(args[0]).endsWith('/admin/snapshot')) {
      const parse = response.json.bind(response);
      response.json = async () => {
        // Receive and decode the real body before delaying its fulfilled callback. Do not weaken the request's abort deadline.
        const data = await parse();
        return new Promise(resolve => control.reads.push({data, resolve:() => resolve(data)}));
      };
    }
    return response;
  };
  control.flush = (count = control.events.length) => {
    const events = control.events.splice(0, count); events.forEach(event => event.invoke());
  };
})()"""


def run(assembly, evidence, executable, native):
    os.umask(0o077)
    evidence.mkdir(parents=True, exist_ok=True)
    assert not (ROOT / '.agent-workflow/STOP').exists()
    http_port, device_port = free_port(), free_port()
    while device_port == http_port:
        device_port = free_port()
    origin = f'http://127.0.0.1:{http_port}'
    result = {'testedLayer': 'actual assembled Main restarts and Chromium with scheduled real HTTP/SSE callbacks; actual native reports, no board/providers',
              'assemblySha256': hashlib.sha256(assembly.read_bytes()).hexdigest(),
              'panelSha256': hashlib.sha256((ROOT / 'twitch-screen-relay/resources/admin/panel.js').read_bytes()).hexdigest(),
              'nativeSha256': hashlib.sha256(native.read_bytes()).hexdigest(), 'cases': [], 'productAccepted': False}
    child = peer = None
    children, logs = [], []

    def exchange(path, method='GET', payload=None):
        connection = http.client.HTTPConnection('127.0.0.1', http_port, timeout=7)
        try:
            connection.request(method, '/api/v1/' + path, None if payload is None else json.dumps(payload),
                               {} if payload is None else {'Content-Type': 'application/json'})
            response = connection.getresponse()
            raw = response.read(524289)
            assert len(raw) <= 524288
            value = json.loads(raw)
            assert response.status == 200, (path, response.status, value)
            return value
        finally:
            connection.close()

    def stop():
        nonlocal child
        if child is not None:
            if child.poll() is None:
                child.terminate()
            child.wait(timeout=30)
            assert child.returncode == 143, child.returncode
            child = None

    with tempfile.TemporaryDirectory(prefix='relay-authority-', dir=os.environ['TMPDIR']) as temporary:
        runtime = Path(temporary)
        env = {key: value for key, value in os.environ.items() if not key.startswith('RELAY_')}
        env.update(RELAY_TWITCH_MODE='disabled', RELAY_TWITCH_CHANNEL='', RELAY_HTTP_HOST='127.0.0.1',
                   RELAY_DEVICE_HOST='127.0.0.1', RELAY_HTTP_PORT=str(http_port), RELAY_DEVICE_PORT=str(device_port),
                   RELAY_HTTP_ALLOWED_HOSTS=f'127.0.0.1:{http_port}', RELAY_HTTP_ALLOWED_ORIGINS=origin,
                   RELAY_RUNTIME_STATE_FILE=str(runtime / 'state/runtime-state.json'),
                   RELAY_TWITCH_TOKEN_FILE=str(runtime / 'unused-token.json'),
                   OTEL_TRACES_EXPORTER='none', OTEL_METRICS_EXPORTER='none', OTEL_LOGS_EXPORTER='none')

        def start(name):
            nonlocal child
            assert not (ROOT / '.agent-workflow/STOP').exists()
            log = (evidence / f'{name}.log').open('w')
            logs.append(log)
            child = subprocess.Popen(['java', '-jar', str(assembly)], cwd=runtime, env=env, stdout=log, stderr=subprocess.STDOUT)
            children.append(child)
            until = time.monotonic() + 60
            while True:
                try:
                    state = exchange('admin/snapshot')
                    return state
                except OSError:
                    assert time.monotonic() < until and child.poll() is None, 'owned Main startup failed'
                    time.sleep(.05)

        try:
            with sync_playwright() as playwright:
                browser = playwright.chromium.launch(executable_path=str(executable), headless=True)
                try:
                    for first in (False, True):
                        old = start(f'epoch-{first}-old')
                        context = browser.new_context(service_workers='block')
                        context.add_init_script(SCHEDULER.replace('holdEvents:false', 'holdEvents:true') if first else SCHEDULER)
                        page = context.new_page()
                        errors = []
                        page.on('pageerror', lambda error: errors.append(str(error)))
                        try:
                            page.goto(origin)
                            if not first:
                                expect(page.locator('#feed-status')).to_contain_text('Feed connected')
                                page.evaluate('authorityTransport.holdEvents = true')
                                for i in range(8):
                                    exchange('display', 'PATCH', {'preferredDashboard': 'cpu' if i % 2 == 0 else 'weather'})
                            page.wait_for_function('() => authorityTransport.events.some(event => event.type !== "error")')
                            # Keep at least one actual old frame for delivery after the canonical epoch changes.
                            if not first:
                                page.wait_for_function('() => authorityTransport.events.filter(event => event.type !== "error").length >= 2', timeout=10000)
                            buffered = page.evaluate('authorityTransport.events.filter(event => event.type !== "error").map(({type,data,id}) => ({type,data:JSON.parse(data),id}))')
                            stop()
                            new = start(f'epoch-{first}-new')
                            assert new['relayEpoch'] != old['relayEpoch']
                            page.evaluate('authorityTransport.holdReads = true')
                            page.locator('[data-display="generic"]').click()
                            page.wait_for_function('() => authorityTransport.reads.length === 1')
                            canonical = page.evaluate('authorityTransport.reads[0].data')
                            assert canonical['relayEpoch'] == new['relayEpoch']
                            if not first:
                                # Same old epoch advances while a real new-epoch GET response is held.
                                page.evaluate('authorityTransport.flush(1)')
                            page.evaluate('authorityTransport.holdReads = false; authorityTransport.reads.shift().resolve()')
                            expect(page.locator('#test-card')).to_be_enabled()
                            page.wait_for_function('() => authorityTransport.feeds[0].closed && authorityTransport.feeds.length >= 2')
                            # These are exact retired-subscription callbacks, not synthesized frame payloads.
                            page.evaluate('authorityTransport.flush()')
                            assert old['relayEpoch'] not in page.locator('#feed-status').inner_text()
                            page.evaluate('authorityTransport.holdEvents = false; authorityTransport.flush()')
                            expect(page.locator('#feed-status')).to_contain_text('epoch ' + new['relayEpoch'])
                            expect(page.locator('[data-display="generic"]')).to_have_attribute('aria-pressed', 'true')
                            observed = page.evaluate('authorityTransport.observed')
                            assert not errors, errors
                            result['cases'].append({'heldFirstSnapshot': first, 'oldEpoch': old['relayEpoch'], 'newEpoch': new['relayEpoch'],
                                                    'bufferedOldFrames': buffered, 'canonicalRead': canonical, 'realObservedFrames': observed,
                                                    'retiredCallbacksRejected': True, 'sameOldPushBeforeNewRead': not first})
                        finally:
                            context.close()
                            stop()
                    old = start('delayed-read-old')
                    context = browser.new_context(service_workers='block')
                    context.add_init_script(SCHEDULER)
                    page = context.new_page()
                    try:
                        page.goto(origin)
                        expect(page.locator('#feed-status')).to_contain_text('Feed connected')
                        page.evaluate('authorityTransport.holdReads = true')
                        page.locator('[data-display="cpu"]').click()
                        page.wait_for_function('() => authorityTransport.reads.length === 1')
                        delayed = page.evaluate('authorityTransport.reads[0].data')
                        assert delayed['relayEpoch'] == old['relayEpoch']
                        stop()
                        new = start('delayed-read-new')
                        expect(page.locator('#feed-status')).to_contain_text('epoch ' + new['relayEpoch'], timeout=15000)
                        page.evaluate('authorityTransport.holdReads = false; authorityTransport.reads.shift().resolve()')
                        expect(page.locator('#test-card')).to_be_enabled()
                        expect(page.locator('#action-status')).to_contain_text('Relay accepted')
                        assert new['relayEpoch'] in page.locator('#feed-status').inner_text()
                        result['cases'].append({'delayedOldReadAfterNewFeed': True, 'oldEpoch': old['relayEpoch'],
                                                'newEpoch': new['relayEpoch'], 'heldActualRead': delayed,
                                                'realObservedFrames': page.evaluate('authorityTransport.observed')})
                    finally:
                        context.close()
                        stop()
                    start('stalled-feed')
                    context = browser.new_context(service_workers='block')
                    context.add_init_script(SCHEDULER)
                    page = context.new_page()
                    try:
                        page.goto(origin)
                        expect(page.locator('#feed-status')).to_contain_text('Feed connected')
                        peer_env = {key: value for key, value in os.environ.items() if not key.startswith('FIRMWARE_PEER_')}
                        peer_env.update(FIRMWARE_PEER_PORT=str(device_port), FIRMWARE_PEER_SECONDS='40',
                                        FIRMWARE_PEER_PROFILE_READY='1', FIRMWARE_PEER_REQUIRE_DESK='1')
                        peer_log = (evidence / 'native-observations.jsonl').open('w')
                        logs.append(peer_log)
                        peer = subprocess.Popen([str(native)], env=peer_env, stdout=peer_log, stderr=subprocess.STDOUT)
                        expect(page.locator('#device-detail')).to_contain_text('fresh firmware observation', timeout=10000)
                        cpu = exchange('cpu', 'PUT', {'sourceId': 'authority', 'host': 'Synthetic', 'cpuPercent': 12.34, 'memoryPercent': 0, 'freshnessMs': 1000})
                        weather = exchange('weather', 'PUT', {'sourceId': 'authority', 'location': 'Synthetic', 'temperature': 0, 'condition': 'clear', 'freshnessMs': 1000})
                        expect(page.locator('#cpu-value')).to_contain_text('CPU 12.34%')
                        expect(page.locator('#weather-value')).to_contain_text('0 C')
                        page.evaluate('authorityTransport.holdEvents = true')
                        started = time.monotonic()
                        expect(page.locator('#cpu-value')).to_contain_text('captured freshness elapsed', timeout=5000)
                        expect(page.locator('#weather-value')).to_contain_text('captured freshness elapsed', timeout=5000)
                        expect(page.locator('#device-detail')).to_contain_text('stale; not a current queue', timeout=16000)
                        assert 'Feed connected' in page.locator('#feed-status').inner_text()
                        elapsed = time.monotonic() - started
                        assert elapsed < 16, elapsed
                        frozen = {key: page.locator('#' + key).inner_text() for key in ('cpu-value', 'weather-value', 'device-detail', 'feed-status')}
                        page.evaluate('authorityTransport.holdEvents = false; authorityTransport.flush()')
                        cpu_recovery = exchange('cpu', 'PUT', {'sourceId': 'authority', 'host': 'Synthetic', 'cpuPercent': 45.67, 'memoryPercent': 0})
                        expect(page.locator('#cpu-value')).to_contain_text('CPU 45.67%')
                        expect(page.locator('#cpu-value')).to_contain_text('fresh: fresh')
                        expect(page.locator('#device-detail')).to_contain_text('fresh firmware observation')
                        result['cases'].append({'stalledOpenSseSeconds': elapsed, 'cpuAccepted': cpu, 'weatherAccepted': weather,
                                                'expiredDom': frozen, 'recoveryAccepted': cpu_recovery,
                                                'realObservedFrames': page.evaluate('authorityTransport.observed')})
                    finally:
                        context.close()
                finally:
                    browser.close()
            result['passed'] = True
        finally:
            if peer is not None:
                if peer.poll() is None:
                    peer.terminate()
                peer.wait(timeout=5)
                result['nativeReaped'] = peer.poll() is not None
            stop()
            for log in logs:
                log.close()
            result['mainExitCodes'] = [process.returncode for process in children]
            result['ownedChildrenReaped'] = all(process.poll() is not None for process in children)
            result['listenersClosed'] = True
            for port in (http_port, device_port):
                with socket.socket() as probe:
                    probe.settimeout(1)
                    result['listenersClosed'] &= probe.connect_ex(('127.0.0.1', port)) != 0
            (evidence / 'result.json').write_text(json.dumps(result, indent=2))
            assert result['ownedChildrenReaped'] and result['listenersClosed']
    print(json.dumps({key: value for key, value in result.items() if key != 'cases'}, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--assembly', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--browser-executable', type=Path, required=True)
    parser.add_argument('--native', type=Path, required=True)
    args = parser.parse_args()
    run(args.assembly.resolve(), args.evidence.resolve(), args.browser_executable.resolve(), args.native.resolve())


if __name__ == '__main__':
    main()
