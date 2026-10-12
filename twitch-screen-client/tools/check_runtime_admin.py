"""Installed synthetic Notify/measured CPU correlated with actual admin SSE.

Uses an explicitly disposable production assembly, private synthetic bus and
foreground installed companion. No desktop bus, service, provider or board access.
This gate does not certify the panel, final Compose deployment or physical pixels.
"""
import argparse
import asyncio
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import sys
import tempfile
import traceback

import httpx
from twitch_screen_client.delivery import strict_json
from twitch_screen_client.responses import matches_submitted_cpu
from check_runtime_monitoring import Runtime, scenario, synthetic_capture

ROOT = Path(__file__).resolve().parents[2]


def verify_correlations(proof, events):
    """Require exact accepted measurements/cards in complete pushed snapshots."""
    cpu_matches, card_matches = [], []
    for transition in proof['transitions']:
        accepted, payload, readback = (transition[key] for key in
                                      ('cpuAccepted', 'cpuSubmitted', 'cpuReadback'))
        assert matches_submitted_cpu(accepted, payload)
        assert all(accepted[key] == readback[key] for key in ('relayEpoch', 'revision', 'data'))
        matching = [event for event in events
                    if event['snapshot']['relayEpoch'] == accepted['relayEpoch']
                    and event['snapshot']['sources']['cpu']['relayEpoch'] == accepted['relayEpoch']
                    and event['snapshot']['sources']['cpu']['revision'] == accepted['revision']
                    and event['snapshot']['sources']['cpu']['data'] == accepted['data']]
        assert matching, 'accepted measured CPU revision/data absent from actual push'
        cpu_matches.append({'relayEpoch': accepted['relayEpoch'], 'revision': accepted['revision'],
                            'eventIds': [event['id'] for event in matching]})
    for captured in proof['captures']:
        card = captured['card']
        matching = [(event, row) for event in events for row in event['snapshot']['notifications']
                    if event['snapshot']['relayEpoch'] == card['relayEpoch']
                    and row['identity']['originalRelayEpoch'] == card['relayEpoch']
                    and row['identity']['seq'] == card['seq']]
        assert matching, 'captured external identity absent from actual push'
        for _, row in matching:
            assert row['title'] == card['title'] and row['body'] == card['body'] == ''
            assert row['identity']['origin'] == card['origin'] == 'external'
            assert row['sourceGeneration'] == row['twitchEpisode'] == 0
        card_matches.append({'relayEpoch': card['relayEpoch'], 'seq': card['seq'],
                             'eventIds': [event['id'] for event, _ in matching]})
    assert len(cpu_matches) == len(card_matches) == 3
    return {'cpuCorrelations': cpu_matches, 'cardCorrelations': card_matches}


async def observe(base, events, changed):
    # The checkout canonical schema is shared with runtime peers, not invented here.
    sys.path.insert(0, str(ROOT / 'tools'))
    from check_desk_contract import validate
    schema = json.loads((ROOT / 'protocol/desk-v1-http.schema.json').read_text())
    try:
        async with httpx.AsyncClient(trust_env=False, timeout=httpx.Timeout(20, connect=1)) as client:
            async with client.stream('GET', base + '/api/v1/admin/events') as response:
                assert response.status_code == 200 and response.headers['cache-control'] == 'no-store'
                assert response.headers['content-type'].startswith('text/event-stream')
                fields = {}
                # Bound the raw SSE event before decoding its JSON, even if no newline arrives.
                pending = bytearray()
                async for chunk in response.aiter_bytes(chunk_size=4096):
                    pending.extend(chunk)
                    assert len(pending) <= 524288 + 4096, 'admin event exceeds fixture bound'
                    while b'\n' in pending:
                        raw, _, rest = pending.partition(b'\n')
                        pending[:] = rest
                        line = raw.rstrip(b'\r').decode('utf-8', errors='strict')
                        if not line:
                            if 'data' in fields:
                                value = strict_json(fields['data'].encode())
                                validate(value, schema['$defs']['AdminSnapshot'], schema)
                                assert fields['event'] in ('snapshot', 'reset')
                                assert fields['id'] == value['relayEpoch'] + ':' + str(value['revision'])
                                if events:
                                    previous = events[-1]['snapshot']
                                    assert value['relayEpoch'] == previous['relayEpoch'], 'unexpected runtime reset'
                                    assert value['revision'] >= previous['revision'], 'push revision regressed'
                                assert len(events) < 1000, 'finite observer record bound exceeded'
                                events.append({'event': fields['event'], 'id': fields['id'], 'snapshot': value})
                                changed.set()
                            fields = {}
                        elif not line.startswith(':'):
                            key, value = line.split(':', 1)
                            assert key not in fields, 'duplicate SSE fixture field'
                            fields[key] = value.lstrip(' ')
                    assert len(pending) + sum(len(value.encode()) for value in fields.values()) <= 524288 + 256
    finally:
        changed.set()


async def exercise(assembly, evidence):
    result = {'status': 'blocked', 'productAccepted': False, 'allSoftwareVerified': False,
              'desktopVerified': False, 'serviceVerified': False, 'physicalVerified': False,
              'proof': {}, 'events': []}
    evidence.mkdir(parents=True, exist_ok=True, mode=0o700)
    with tempfile.TemporaryDirectory(prefix='companion-admin-', dir=os.environ['TMPDIR']) as scratch:
        directory = Path(scratch)
        frozen = directory / 'assembly.jar'
        shutil.copyfile(assembly, frozen)
        result['assemblySha256'] = hashlib.sha256(frozen.read_bytes()).hexdigest()
        runtime = Runtime(frozen, directory)
        observer = None
        changed = asyncio.Event()
        async def correlated(predicate):
            async with asyncio.timeout(5):
                while True:
                    changed.clear()
                    assert observer is not None
                    if observer.done():
                        observer.result()
                        raise EOFError('actual admin push ended before correlation')
                    if predicate():
                        return
                    await changed.wait()
        try:
            await runtime.start()
            observer = asyncio.create_task(observe(runtime.base, result['events'], changed))
            await correlated(lambda: bool(result['events']))
            async with synthetic_capture(result['proof']) as capture:
                await scenario(runtime, directory, result['proof'], capture)
            def complete():
                try:
                    verify_correlations(result['proof'], result['events'])
                    return True
                except AssertionError:
                    return False
            await correlated(complete)
            result.update(verify_correlations(result['proof'], result['events']))
            async with httpx.AsyncClient(trust_env=False, timeout=3) as client:
                response = await client.get(runtime.base + '/api/v1/admin/snapshot')
                response.raise_for_status()
                result['finalSnapshot'] = strict_json(response.content)
                sys.path.insert(0, str(ROOT / 'tools'))
                from check_desk_contract import validate
                schema = json.loads((ROOT / 'protocol/desk-v1-http.schema.json').read_text())
                validate(result['finalSnapshot'], schema['$defs']['AdminSnapshot'], schema)
                result['rootStatus'] = (await client.get(runtime.base + '/')).status_code
            assert not observer.done()
            result['status'] = 'passed'
        except Exception:
            result['error'] = traceback.format_exc()
            traceback.print_exc()
        finally:
            if observer:
                observer.cancel()
                outcomes = await asyncio.gather(observer, return_exceptions=True)
                result['observerClosed'] = observer.done()
                result['observerException'] = None if isinstance(outcomes[0], asyncio.CancelledError) else repr(outcomes[0])
            await runtime.stop()
            result['relayStops'] = runtime.stops
            result['listenersClosed'] = True
            for port in (runtime.http_port, runtime.device_port):
                with socket.socket() as sock:
                    sock.settimeout(.3)
                    result['listenersClosed'] &= sock.connect_ex(('127.0.0.1', port)) != 0
            for log in directory.glob('*.log'):
                (evidence / log.name).write_bytes(log.read_bytes())
            fixture = result['proof'].get('fixtureDirectory')
            if fixture:
                fixture = Path(fixture)
                assert fixture.parent.resolve() == Path(os.environ['TMPDIR']).resolve() and fixture.name.startswith('client-test-')
                for log in fixture.glob('*.log'):
                    (evidence / ('private-bus-' + log.name)).write_bytes(log.read_bytes())
                shutil.rmtree(fixture)
                result['fixtureRemoved'] = not fixture.exists()
    result['scratchRemoved'] = not directory.exists()
    (evidence / 'admin.json').write_text(json.dumps(result, indent=2) + '\n')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--assembly', type=Path, required=True)
    parser.add_argument('--evidence', type=Path, required=True)
    parser.add_argument('--test-owned', action='store_true')
    args = parser.parse_args()
    if not args.test_owned or (ROOT / '.agent-workflow/STOP').exists():
        parser.error('--test-owned required and workflow STOP must be absent')
    if not args.assembly.is_file():
        parser.error('--assembly must be an existing disposable production artifact')
    os.umask(0o077)
    result = asyncio.run(exercise(args.assembly.resolve(), args.evidence.resolve()))
    print(json.dumps({key: value for key, value in result.items() if key not in ('proof', 'events', 'finalSnapshot')}, indent=2))
    return 0 if result['status'] == 'passed' else 2


if __name__ == '__main__':
    raise SystemExit(main())
