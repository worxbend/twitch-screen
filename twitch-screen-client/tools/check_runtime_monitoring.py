"""Installed companion across actual canonical monitoring transitions.

Requires a disposable assembled relay. No provider credentials, desktop bus,
service installation, board or existing deployment is used. Optional --capture
uses only a private synthetic bus and fake daemon; it is not popup/device proof.
"""
import argparse
import asyncio
import contextlib
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import sys
import tempfile
import traceback

import httpx
from twitch_screen_client.delivery import Delivery, strict_json
from twitch_screen_client.metrics import boottime_ms
from twitch_screen_client.config import parse
from twitch_screen_client.queue import CardQueue
from twitch_screen_client.runtime import initial_status
from check_runtime_recovery import (Runtime, FaultGateway, correlated_cpu_readback,
                                    history, reap_child, wait_until)


def verify_display_independent(before, after):
    """Source fences may advance revision, never alter remembered display intent."""
    assert before['relayEpoch'] == after['relayEpoch']
    assert after['revision'] >= before['revision']
    for field in ('twitchDisplayEnabled', 'preferredDashboard'):
        assert after[field] == before[field]
    return True


def verify_capture_switch(proof):
    """Correlate one ordinary Notify reply with one original-age external card."""
    assert proof['reply'] == 42 and proof['pid'] == proof['expectedPid']
    assert proof['configurationUnchanged'] is True
    assert proof['status']['capture'] == 'available'
    assert proof['status']['captured'] >= 1 and proof['status']['notification_accepted'] >= 1
    records = [row for row in proof['records'] if row['method'] == 'POST'
               and row['payload']['title'] == proof['title']]
    assert len(records) == 1 and records[0]['status'] == 200
    payload, accepted, card = records[0]['payload'], records[0]['response'], proof['card']
    assert payload['title'] == card['title'] == proof['title']
    assert payload['body'] == accepted['body'] == card['body'] == ''
    assert payload['deadline']['relayEpoch'] == accepted['relayEpoch'] == card['relayEpoch']
    assert payload['deadline']['monotonicMs'] > 0
    for key in ('id', 'seq', 'title', 'origin', 'sourceGeneration', 'twitchEpisode'):
        assert accepted[key] == card[key]
    assert card['origin'] == 'external' and card['sourceGeneration'] == card['twitchEpisode'] == 0
    return True


@contextlib.asynccontextmanager
async def synthetic_capture(proof, denied=False):
    """Own only a private synthetic bus/daemon; never inspect the desktop bus."""
    from dbus_fast import Message, MessageType
    from twitch_screen_client.dbus_transport import bus_call
    tests = Path(__file__).resolve().parents[1] / 'tests'
    sys.path.insert(0, str(tests))
    from test_transport import PrivateBus, NAME, PATH, ROOT as fixture
    proof['fixtureDirectory'] = str(fixture)
    async with PrivateBus(denied=denied) as bus:
        daemon = await bus.connect('monitor switch synthetic daemon')
        assert (await daemon.call(bus_call('RequestName', 'su', [NAME, 0]))).body == [1]
        producer = await bus.connect('monitor switch synthetic producer')

        async def serve():
            while True:
                message = await daemon.receive()
                if (message.message_type == MessageType.METHOD_CALL and message.path == PATH
                        and message.interface == NAME and message.member == 'Notify'):
                    await daemon.send(Message.new_method_return(message, signature='u', body=[42]))

        async def notify(title):
            reply = await asyncio.wait_for(producer.call(Message(destination=NAME, path=PATH, interface=NAME,
                member='Notify', signature='susssasa{sv}i', body=['twitch-screen-e2e', 0, '', title,
                'PRIVATE SYNTHETIC SWITCH BODY', [], {}, 0])), 3)
            assert reply.message_type == MessageType.METHOD_RETURN and reply.body == [42]
            return reply.body[0]

        worker = asyncio.create_task(serve())
        try:
            yield {'address': 'unix:path=' + bus.path, 'notify': notify, 'denied': denied}
        finally:
            worker.cancel()
            await asyncio.gather(worker, return_exceptions=True)
            proof['privateBusPid'] = bus.process.pid
    proof['privateBusReaped'] = bus.process.returncode is not None


async def scenario(runtime, directory, proof, capture=None):
    async with FaultGateway(runtime.http_port) as gateway, httpx.AsyncClient(trust_env=False, timeout=3) as client:
        private = directory / 'client'
        private.mkdir(mode=0o700)
        config = private / 'config.toml'
        config.write_text(f'relay_url="{gateway.url}"\nsource_id="wave11-monitor"\n'
                          'host="Synthetic monitor host"\nsample_interval_ms=1000\n' +
                          ('notifications_enabled=true\ninclude_apps=["twitch-screen-e2e"]\n' if capture else ''))
        config.chmod(0o600)
        original = config.read_bytes()
        path = private / 'twitch-screen-client-wave11-monitor.json'
        environment = {**os.environ, 'PYTHONPATH': '', 'XDG_RUNTIME_DIR': str(private)}
        environment.pop('DBUS_SESSION_BUS_ADDRESS', None)
        if capture:
            environment['DBUS_SESSION_BUS_ADDRESS'] = capture['address']
        child = await asyncio.create_subprocess_exec(sys.executable, '-m', 'twitch_screen_client',
            'run', '--config', str(config), env=environment,
            stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
        sender = Delivery(parse({'relay_url': gateway.url, 'source_id': 'wave11-generic'}),
                          boottime_ms, CardQueue(boottime_ms), initial_status())
        proof['childPid'] = child.pid

        def status():
            return strict_json(path.read_bytes()) if path.exists() else {}

        async def get(route):
            response = await client.get(runtime.base + route)
            response.raise_for_status()
            return response.json()

        try:
            await wait_until(lambda: status().get('cpu_accepted', 0) >= 1, 15)
            if capture:
                await wait_until(lambda: status().get('capture') == 'available', 10)
                proof['captures'] = []
            else:
                assert status()['capture'] == 'disabled'
            baseline = await get('/api/v1/twitch/monitoring')
            display = await get('/api/v1/display')
            proof.update(baselineMonitoring=baseline, baselineDisplay=display, transitions=[])
            for index, patch in enumerate(({'mode': 'simulated', 'channel': 'demo'},
                                            {'channel': 'second_synthetic'}, {'mode': 'disabled'})):
                boundary = len(gateway.records)
                before = await get('/api/v1/twitch/monitoring')
                accepted = await client.patch(runtime.base + '/api/v1/twitch/monitoring', json=patch)
                accepted.raise_for_status()
                value = accepted.json()
                assert value['revision'] > before['revision'] and value['sourceGeneration'] > before['sourceGeneration']
                assert all(value['desired'][key] == item for key, item in patch.items())
                capture_boundary = len(gateway.records)
                capture_title = f'repair11 captured switch {index}'
                ordinary_reply = None
                if capture:
                    ordinary_reply = await capture['notify'](capture_title)
                readback, submitted = await correlated_cpu_readback(client, runtime.base, gateway, boundary,
                                                                    gateway.observations, timeout=5)
                # Reconcile one accepted asynchronous transition at the finite test boundary.
                observed = await get('/api/v1/twitch/monitoring')
                assert observed['desired'] == value['desired'] and observed['revision'] == value['revision']
                assert observed['transition'] == 'ready' and observed['effective']['mode'] == value['desired']['mode']
                if value['desired']['mode'] == 'simulated':
                    assert observed['effective']['channel'] == value['desired']['channel']
                control = await get('/api/v1/display')
                proof['inFlightTransition'] = {'patch': patch, 'before': before, 'accepted': value,
                    'observed': observed, 'display': control, 'cpuReadback': readback, 'cpuSubmitted': submitted[0]}
                assert verify_display_independent(display, control)
                assert child.returncode is None and config.read_bytes() == original
                payload = {'type': 'message', 'title': f'wave11 generic source switch {index}', 'body': '',
                           'ttlMs': 8000, 'expiresInMs': 60000}
                assert await sender.upload(sender.exchanges[0], 'POST', '/api/v1/notifications', payload,
                                           boottime_ms() + 60000) == 'accepted'
                cards = [row for row in await history(client, runtime.base) if row['title'] == payload['title']]
                assert len(cards) == 1 and cards[0]['origin'] == 'external' and cards[0]['body'] == ''
                proof['transitions'].append({'patch': patch, 'before': before, 'accepted': value,
                    'observed': observed, 'display': control, 'cpuReadback': readback,
                    'cpuSubmitted': submitted[0], 'cpuAccepted': gateway.observations[-1]['accepted'],
                    'card': cards[0], 'status': status(), 'samePidAndConfiguration': True})
                if capture:
                    # Observe actual response publication; never retry POST or poll relay history.
                    async with asyncio.timeout(5):
                        while True:
                            gateway.records_changed.clear()
                            captured_records = [row for row in gateway.records[capture_boundary:]
                                if row['method'] == 'POST' and row['payload']['title'] == capture_title]
                            if captured_records:
                                break
                            await gateway.records_changed.wait()
                    await wait_until(lambda: status().get('notification_accepted', 0) == index + 1, 5)
                    captured_cards = [row for row in await history(client, runtime.base)
                                      if row['title'] == capture_title]
                    assert len(captured_cards) == 1
                    capture_proof = {'title': capture_title, 'reply': ordinary_reply,
                        'pid': status()['pid'], 'expectedPid': child.pid,
                        'configurationUnchanged': config.read_bytes() == original,
                        'status': status(), 'card': captured_cards[0], 'records': captured_records}
                    proof['captures'].append(capture_proof)
                    assert verify_capture_switch(capture_proof)
            before = await get('/api/v1/twitch/monitoring')
            invalid = await client.patch(runtime.base + '/api/v1/twitch/monitoring', json={'mode': 'live'})
            assert invalid.status_code == 409 and invalid.json()['code'] == 'configuration_missing'
            after = await get('/api/v1/twitch/monitoring')
            assert before['desired'] == after['desired'] and before['revision'] == after['revision']
            proof['missingConfiguration'] = {'response': invalid.json(), 'before': before, 'after': after}
            child.send_signal(signal.SIGTERM)
            stdout, stderr = await asyncio.wait_for(child.communicate(), 5)
            proof['exitCode'], proof['stopped'] = child.returncode, status()
            (directory / 'companion-monitor-child.log').write_bytes(stdout + stderr)
            assert child.returncode == 0 and not stderr
            assert all(proof['stopped'][key] == 'stopped' for key in ('capture', 'cpu', 'relay'))
            assert all(row['payload']['title'].startswith(('wave11 generic source switch ',
                           'repair11 captured switch ') if capture else ('wave11 generic source switch ',))
                       for row in gateway.requests if row['method'] == 'POST')
            assert b'PRIVATE SYNTHETIC SWITCH BODY' not in stdout + stderr + path.read_bytes()
            if capture:
                assert proof['stopped']['captured'] == proof['stopped']['notification_accepted'] == 3
                assert proof['stopped']['notification_ambiguous'] == 0
                assert proof['stopped']['pendingNotifications'] == proof['stopped']['pendingNotificationBytes'] == 0
                final_cards = [row for row in await history(client, runtime.base)
                               if row['title'].startswith('repair11 captured switch ')]
                assert len(final_cards) == 3
                proof['finalCapturedHistory'] = final_cards
        finally:
            await reap_child(child)
            await sender.close()
            proof.update(lastStatus=status(), requests=gateway.requests, records=gateway.records,
                         correlations=gateway.observations, childReaped=child.returncode is not None)
    proof['gatewayReleased'] = not gateway.tasks and not gateway.writers


async def exercise(assembly, evidence, capture_enabled=False):
    result = {'status': 'blocked', 'proof': {}, 'productAccepted': False, 'allSoftwareVerified': False,
              'desktopVerified': False, 'physicalVerified': False, 'productionVerified': False}
    evidence.mkdir(parents=True, exist_ok=True, mode=0o700)
    with tempfile.TemporaryDirectory(dir=os.environ['TMPDIR'], prefix='companion-monitor-') as scratch:
        directory = Path(scratch)
        snapshot = directory / 'assembly.jar'
        shutil.copyfile(assembly, snapshot)
        with snapshot.open('rb') as stream:
            result['assemblySha256'] = hashlib.file_digest(stream, 'sha256').hexdigest()
        runtime = Runtime(snapshot, directory)
        try:
            await runtime.start()
            if capture_enabled:
                async with synthetic_capture(result['proof']) as capture:
                    await scenario(runtime, directory, result['proof'], capture)
            else:
                await scenario(runtime, directory, result['proof'])
            result['status'] = 'passed'
        except Exception:
            result['error'] = traceback.format_exc()
            traceback.print_exc()
        finally:
            await runtime.stop()
            result.update(relayStops=runtime.stops, ownedListenersClosed=True)
            for log in directory.glob('*.log'):
                (evidence / log.name).write_bytes(log.read_bytes())
            fixture = result['proof'].get('fixtureDirectory')
            if fixture is not None:
                fixture = Path(fixture)
                assert fixture.parent.resolve() == Path(os.environ['TMPDIR']).resolve()
                assert fixture.name.startswith('client-test-')
                for log in fixture.glob('*.log'):
                    (evidence / ('companion-monitor-' + log.name)).write_bytes(log.read_bytes())
                shutil.rmtree(fixture)
                result['fixtureEvidenceSavedAndRemoved'] = not fixture.exists()
    result['scratchRemoved'] = not directory.exists()
    (evidence / 'monitoring.json').write_text(json.dumps(result, indent=2) + '\n')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--assembly', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--test-owned', action='store_true')
    parser.add_argument('--capture', action='store_true', help='Exercise only private synthetic Notify capture')
    args = parser.parse_args()
    if not args.test_owned:
        parser.error('--test-owned is required')
    if (Path(__file__).resolve().parents[2] / '.agent-workflow/STOP').exists():
        parser.error('workflow STOP is present')
    os.umask(0o077)
    result = asyncio.run(exercise(args.assembly.resolve(), args.evidence.resolve(), args.capture))
    print(json.dumps({key: value for key, value in result.items() if key != 'proof'}, indent=2))
    return 0 if result['status'] == 'passed' else 2


if __name__ == '__main__':
    raise SystemExit(main())
