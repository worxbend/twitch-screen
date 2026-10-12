"""Check an explicit relay; optional writes require a disposable test-owned target.

No bus, service, device, display-setting or provider operation is performed.
Output contains bounded results, not response bodies or endpoint URLs.
"""
import argparse
import asyncio
import json
from pathlib import Path

from twitch_screen_client.config import load
from twitch_screen_client.delivery import Delivery, Exchange, strict_json
from twitch_screen_client.metrics import Sampler, boottime_ms
from twitch_screen_client.queue import CardQueue
from twitch_screen_client.responses import accepted, matches_submitted_cpu
from twitch_screen_client.runtime import initial_status


class ObservedExchange(Exchange):
    """Keep one bounded write response solely for exact-target readback correlation."""
    def __init__(self, exchange):
        self.exchange = exchange
        self.write_response = None

    async def request(self, method, path, payload=None):
        result = await self.exchange.request(method, path, payload)
        if method in ('POST', 'PUT') and result[0] == 200:
            self.write_response = strict_json(result[2])
        return result

    async def close(self):
        await self.exchange.close()


async def check(config, exercise=False):
    report = {'status': 'blocked', 'testedLayer': 'configured relay HTTP with actual companion delivery',
              'writesAttempted': False, 'productionVerified': False, 'physicalVerified': False,
              'desktopVerified': False, 'checks': [], 'blockers': []}
    delivery = Delivery(config, boottime_ms, CardQueue(boottime_ms), initial_status())
    exchanges = [ObservedExchange(exchange) for exchange in delivery.exchanges]
    delivery.exchanges[:] = exchanges
    try:
        try:
            calibration = await delivery.calibrated(delivery.exchanges[0])
        except (OSError, ValueError, TimeoutError, RecursionError):
            report['blockers'].append('canonical non-cacheable clock calibration unavailable')
            return report
        report['checks'].append('canonical no-store clock calibration')
        if not exercise:
            report['status'] = 'passed'
            report['checks'].append('read-only preflight; uploads and readback not exercised')
            return report
        sampler = Sampler(config.sample_interval_ms)
        sampler.sample()
        await asyncio.sleep(config.sample_interval_ms / 1000)
        sample = sampler.sample()
        if sample is None:
            report['blockers'].append('valid measured CPU sample unavailable')
            return report
        measured, values = sample
        payload = {'sourceId': config.source_id, 'host': config.host,
                   'freshnessMs': config.freshness_ms, **values}
        report['writesAttempted'] = True
        outcome = await delivery.upload(delivery.exchanges[1], 'PUT', '/api/v1/cpu', payload,
                                        measured + config.freshness_ms)
        if outcome != 'accepted':
            report['blockers'].append('CPU acceptance did not match submitted measurement'
                                      if delivery.status['relay'] == 'invalid_response'
                                      else 'CPU upload not accepted: ' + outcome)
            return report
        written = exchanges[1].write_response
        if not isinstance(written, dict) or not matches_submitted_cpu(written, payload):
            report['blockers'].append('CPU acceptance did not match submitted measurement')
            return report
        code, _, body, _, _ = await delivery.exchanges[1].request('GET', '/api/v1/cpu')
        value = strict_json(body)
        if (code != 200 or not isinstance(value, dict) or
                value.get('relayEpoch') != calibration.epoch or not accepted(value, 'PUT', payload) or
                value.get('revision') != written.get('revision') or value.get('data') != written.get('data')):
            report['blockers'].append('canonical CPU readback did not match test source')
            return report
        report['checks'].append('measured CPU accepted and canonical source read back')
        card = {'type': 'message', 'title': 'twitch-screen-e2e pairing', 'body': '',
                'ttlMs': 8000, 'expiresInMs': 60000}
        outcome = await delivery.upload(delivery.exchanges[0], 'POST', '/api/v1/notifications',
                                        card, boottime_ms() + 60000)
        if outcome != 'accepted':
            report['blockers'].append('synthetic card not accepted: ' + outcome)
            return report
        code, _, body, _, _ = await delivery.exchanges[0].request('GET', '/api/v1/notifications?pageSize=80')
        value = strict_json(body)
        records = value.get('notifications') if isinstance(value, dict) else None
        written = exchanges[0].write_response
        if (code != 200 or not isinstance(written, dict) or not isinstance(records, list) or len(records) > 80 or not any(
                isinstance(row, dict) and row.get('relayEpoch') == calibration.epoch and
                row.get('seq') == written.get('seq') and row.get('id') == written.get('id') and
                row.get('title') == card['title'] and row.get('body') == '' and
                accepted(row, 'POST', card) for row in records)):
            report['blockers'].append('synthetic accepted card missing from canonical history readback')
            return report
        report['checks'].append('synthetic card accepted and publication history read back')
        report['status'] = 'passed'
        # HTTP pairing alone does not prove delay/loss/epoch/device acceptance.
        return report
    except Exception:
        report['blockers'].append('bounded HTTP pairing failed; no response content retained')
        return report
    finally:
        async with asyncio.timeout(4):
            await delivery.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--exercise', action='store_true')
    parser.add_argument('--test-owned', action='store_true',
                        help='confirm disposable relay ownership; CPU slot and notification history will change')
    args = parser.parse_args()
    if args.exercise and not args.test_owned:
        parser.error('--exercise requires --test-owned; never use a shared relay')
    config = load(args.config)
    report = asyncio.run(check(config, args.exercise))
    wire = json.dumps(report, indent=2, allow_nan=False) + '\n'
    args.output.write_text(wire)
    args.output.chmod(0o600)
    print(wire, end='')
    return 0 if report['status'] == 'passed' else 2


if __name__ == '__main__':
    raise SystemExit(main())
