"""Installed foreground shutdown during an actual committed, withheld POST.

Only disposable assembled relay, private synthetic bus and test-owned children.
Never uses the real desktop, services, board or provider credentials.
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
from dbus_fast import Message, MessageType
from twitch_screen_client.dbus_transport import bus_call
from twitch_screen_client.delivery import strict_json
from twitch_screen_client.metrics import boottime_ms

from check_runtime_recovery import (FaultGateway, Runtime, correlated_cpu_readback,
                                    history, reap_child, wait_until)


def verify_stop(proof):
    """Reject final-only/counter-only claims; require real held commit and readback."""
    from twitch_screen_client.responses import matches_submitted_cpu
    assert proof['heldAtSignal'] is True
    assert proof['signalBoottimeMs'] - proof['heldBoottimeMs'] < 3000
    assert proof['stopElapsedMs'] < 5000 and proof['exitCode'] == 0
    assert proof['ordinaryReplies'] == [42, 42]
    assert proof['stopped']['pid'] == proof['firstPid']
    assert all(proof['stopped'][key] == 'stopped' for key in ('capture', 'cpu', 'relay'))
    assert proof['stopped']['notification_accepted'] == 0
    assert proof['stopped']['notification_ambiguous'] == 0
    assert proof['stopped']['notification_expired'] == 0
    posts = [r for r in proof['records'] if r['method'] == 'POST']
    assert len(posts) == 1 and posts[0]['status'] == 200 and posts[0]['fault'] == 'hold_response'
    assert posts[0]['payload']['title'] == proof['title'] and posts[0]['payload']['body'] == ''
    assert proof['readback']['revision'] == proof['cpuAccepted']['revision']
    assert proof['readback']['relayEpoch'] == proof['cpuAccepted']['relayEpoch']
    assert proof['readback']['data'] == proof['cpuAccepted']['data']
    assert matches_submitted_cpu(proof['readback'], proof['cpuSubmitted'])
    assert proof['cpuSubmitted']['deadline']['relayEpoch'] == proof['readback']['relayEpoch']
    assert proof['readback']['status'] == 'fresh' and proof['readback']['data']['memoryPercent'] is not None
    cpu_records = [r for r in proof['records'] if r['method'] == 'PUT' and r['status'] == 200
                   and r.get('response', {}).get('revision') == proof['readback']['revision']]
    assert len(cpu_records) == 1 and cpu_records[0]['response']['data'] == proof['readback']['data']
    assert cpu_records[0]['payload'] == proof['cpuSubmitted']
    assert proof['firstPid'] != proof['restart']['pid'] and proof['restartExitCode'] == 0
    assert proof['restart']['cpu_accepted'] >= 1
    for key in ('captured', 'notification_accepted', 'notification_ambiguous', 'pendingNotifications', 'pendingNotificationBytes'):
        assert proof['restart'][key] == 0
    assert proof['configurationUnchanged'] is True
    identity = lambda rows: [{k: v for k, v in row.items() if k != 'remainingExpiryMs'} for row in rows]
    assert len(proof['historyBefore']) == len(proof['historyAfter']) == 1
    assert identity(proof['historyBefore']) == identity(proof['historyAfter'])
    card = proof['historyAfter'][0]
    assert card['title'] == proof['title'] and card['body'] == '' and card['origin'] == 'external'
    assert card['remainingExpiryMs'] <= proof['historyBefore'][0]['remainingExpiryMs']
    return True


async def child_check(runtime, gateway, client, directory, tests, proof):
    sys.path.insert(0, str(tests))
    from test_transport import PrivateBus, NAME, PATH, ROOT as bus_evidence
    proof['fixtureDirectory'] = str(bus_evidence)
    async with PrivateBus() as bus:
        daemon = await bus.connect('stop-check synthetic daemon')
        assert (await daemon.call(bus_call('RequestName', 'su', [NAME, 0]))).body == [1]
        producer = await bus.connect('stop-check synthetic producer')

        async def serve():
            while True:
                message = await daemon.receive()
                if message.message_type == MessageType.METHOD_CALL and message.member == 'Notify':
                    await daemon.send(Message.new_method_return(message, signature='u', body=[42]))

        server_task = asyncio.create_task(serve())
        private = directory / 'client'
        private.mkdir(mode=0o700)
        config = private / 'config.toml'
        config.write_text(f'relay_url="{gateway.url}"\nsource_id="wave11-stop"\n'
                          'host="Synthetic stop host"\nsample_interval_ms=1000\n'
                          'notifications_enabled=true\ninclude_apps=["twitch-screen-e2e"]\n')
        config.chmod(0o600)
        original = config.read_bytes()
        status_path = private / 'twitch-screen-client-wave11-stop.json'
        environment = {**os.environ, 'PYTHONPATH': '', 'XDG_RUNTIME_DIR': str(private),
                       'DBUS_SESSION_BUS_ADDRESS': 'unix:path=' + bus.path}
        children = []

        def status():
            return strict_json(status_path.read_bytes()) if status_path.exists() else {}

        async def launch():
            child = await asyncio.create_subprocess_exec(sys.executable, '-m', 'twitch_screen_client',
                'run', '--config', str(config), env=environment,
                stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
            children.append(child)
            return child

        async def notify(title):
            reply = await asyncio.wait_for(producer.call(Message(destination=NAME, path=PATH, interface=NAME,
                member='Notify', signature='susssasa{sv}i',
                body=['twitch-screen-e2e', 0, '', title, 'PRIVATE SYNTHETIC STOP BODY', [], {}, 0])), 3)
            assert reply.message_type == MessageType.METHOD_RETURN and reply.body == [42]
            proof.setdefault('ordinaryReplies', []).append(reply.body[0])

        try:
            child = await launch()
            await wait_until(lambda: status().get('capture') == 'available' and status().get('cpu_accepted', 0) >= 1, 15)
            proof['firstPid'] = child.pid
            gateway.fault_path = '/api/v1/notifications'
            gateway.next_fault = 'hold_response'
            await notify(proof['title'])
            await asyncio.wait_for(gateway.response_held.wait(), 2)
            proof['heldBoottimeMs'] = boottime_ms()
            boundary = len(gateway.records)
            proof['historyBefore'] = await history(client, runtime.base)
            readback, submitted = await correlated_cpu_readback(client, runtime.base, gateway,
                                                                boundary, gateway.observations, timeout=2)
            proof['readback'], proof['cpuSubmitted'] = readback, submitted[0]
            proof['cpuAccepted'] = gateway.observations[-1]['accepted']
            proof['heldAtSignal'] = not gateway.release_response.is_set()
            proof['signalBoottimeMs'] = boottime_ms()
            child.send_signal(signal.SIGTERM)
            stdout, stderr = await asyncio.wait_for(child.communicate(), 5)
            proof.update(exitCode=child.returncode, stopElapsedMs=boottime_ms() - proof['signalBoottimeMs'],
                         stopped=status())
            (directory / 'companion-stop-child.log').write_bytes(stdout + stderr)
            assert child.returncode == 0 and not stderr
            assert b'PRIVATE SYNTHETIC STOP BODY' not in stdout + status_path.read_bytes()
            # Desktop daemon remains normal after the companion has exited.
            await notify('wave11 ordinary delivery after stop')
            gateway.release_response.set()
            await wait_until(lambda: not gateway.tasks, 2)
            restart = await launch()
            await wait_until(lambda: status().get('pid') == restart.pid and
                status().get('capture') == 'available' and status().get('cpu_accepted', 0) >= 1, 15)
            proof['restart'] = status()
            proof['configurationUnchanged'] = original == config.read_bytes()
            proof['historyAfter'] = await history(client, runtime.base)
            restart.send_signal(signal.SIGINT)
            stdout, stderr = await asyncio.wait_for(restart.communicate(), 5)
            proof['restartExitCode'] = restart.returncode
            (directory / 'companion-stop-restart.log').write_bytes(stdout + stderr)
            assert restart.returncode == 0 and not stderr
            proof['records'] = list(gateway.records)
            assert verify_stop(proof)
        finally:
            proof['lastStatus'] = status()
            proof['requests'] = list(gateway.requests)
            proof['records'] = list(gateway.records)
            proof['correlations'] = gateway.observations
            gateway.release_response.set()
            for child in children:
                await reap_child(child)
            server_task.cancel()
            await asyncio.gather(server_task, return_exceptions=True)
            proof['childPids'] = [child.pid for child in children]
            proof['childrenReaped'] = all(child.returncode is not None for child in children)
            proof['privateBusPid'] = bus.process.pid
    proof['privateBusReaped'] = bus.process.returncode is not None


async def exercise(assembly, evidence, tests):
    report = {'status': 'blocked', 'checks': [], 'productAccepted': False, 'allSoftwareVerified': False,
              'desktopVerified': False, 'physicalVerified': False, 'productionVerified': False,
              'proof': {'title': 'wave11 held foreground stop'}}
    evidence.mkdir(parents=True, exist_ok=True, mode=0o700)
    with tempfile.TemporaryDirectory(dir=os.environ['TMPDIR'], prefix='companion-stop-') as scratch:
        directory = Path(scratch)
        snapshot = directory / 'assembly.jar'
        shutil.copyfile(assembly, snapshot)
        with snapshot.open('rb') as stream:
            report['assemblySha256'] = hashlib.file_digest(stream, 'sha256').hexdigest()
        runtime = Runtime(snapshot, directory, same_ports=True)
        try:
            await runtime.start()
            async with FaultGateway(runtime.http_port) as gateway, httpx.AsyncClient(trust_env=False, timeout=3) as client:
                await child_check(runtime, gateway, client, directory, tests, report['proof'])
            report['gatewayReleased'] = not gateway.tasks and not gateway.writers
            report['checks'] = ['actual committed POST withheld while measured CPU continues',
                                'installed SIGTERM within five seconds while response remains withheld',
                                'ordinary synthetic delivery preserved after stop',
                                'same-config restart acquires source lock without old-card replay']
            report['status'] = 'passed'
        except Exception:
            report['error'] = traceback.format_exc()
            traceback.print_exc()
        finally:
            await runtime.stop()
            report['relayStops'] = runtime.stops
            report['listenersClosed'] = True
            for log in directory.glob('*.log'):
                (evidence / log.name).write_bytes(log.read_bytes())
            fixture = report['proof'].get('fixtureDirectory')
            if fixture is not None:
                fixture = Path(fixture)
                assert fixture.parent.resolve() == Path(os.environ['TMPDIR']).resolve()
                assert fixture.name.startswith('client-test-')
                for log in fixture.glob('*.log'):
                    (evidence / ('companion-stop-' + log.name)).write_bytes(log.read_bytes())
                shutil.rmtree(fixture)
                report['fixtureEvidenceSavedAndRemoved'] = not fixture.exists()
    report['scratchRemoved'] = not directory.exists()
    (evidence / 'stop.json').write_text(json.dumps(report, indent=2) + '\n')
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--assembly', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--tests', type=Path, default=Path(__file__).resolve().parents[1] / 'tests')
    parser.add_argument('--test-owned', action='store_true')
    args = parser.parse_args()
    if not args.test_owned:
        parser.error('--test-owned is required')
    if (Path(__file__).resolve().parents[2] / '.agent-workflow/STOP').exists():
        parser.error('workflow STOP is present')
    os.umask(0o077)
    result = asyncio.run(exercise(args.assembly.resolve(), args.evidence.resolve(), args.tests.resolve()))
    print(json.dumps({key: value for key, value in result.items() if key != 'proof'}, indent=2))
    return 0 if result['status'] == 'passed' else 2


if __name__ == '__main__':
    raise SystemExit(main())
