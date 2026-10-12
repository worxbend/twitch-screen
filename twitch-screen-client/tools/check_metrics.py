"""Compare real Linux samples with independent accounting of captured kernel input.

No load is generated. No relay, desktop bus, credentials or service is accessed.
The comparison proves accounting, not an independent physical CPU sensor.
"""
import argparse
import asyncio
import json
from pathlib import Path
import sys

from twitch_screen_client.metrics import ProcReader, Sampler


class RecordingReader:
    def __init__(self):
        self.reader = ProcReader()
        self.stat = []
        self.memory = None

    def read(self, name):
        text = self.reader.read(name)
        if name == 'stat':
            self.stat.append(text.splitlines()[0])
        else:
            self.memory = text
        return text


def reference_cpu(before, after):
    # Independent from production parsers; Linux aggregate starts at the first row.
    rows = [row.split() for row in (before, after)]
    if any(row[0] != 'cpu' or len(row) < 9 for row in rows):
        raise ValueError('aggregate reference row unavailable')
    old, new = [list(map(int, row[1:9])) for row in rows]
    delta = [new[index] - old[index] for index in range(8)]
    if min(delta) < 0 or sum(delta) <= 0:
        raise ValueError('reference interval invalid')
    busy = sum(delta[index] for index in (0, 1, 2, 5, 6, 7))
    return 100 * busy / sum(delta), delta


def reference_memory(text):
    lines = [line.split() for line in text.splitlines()]
    total = next(int(row[1]) for row in lines if row[0] == 'MemTotal:')
    available = next(int(row[1]) for row in lines if row[0] == 'MemAvailable:')
    return 100 * (total - available) / total


async def check(interval_ms):
    reader = RecordingReader()
    sampler = Sampler(interval_ms, reader=reader)
    if sampler.sample() is not None:
        raise AssertionError('warmup must not publish a CPU measurement')
    await asyncio.sleep(interval_ms / 1000)
    sample = sampler.sample()
    if sample is None:
        raise ValueError('real measurement unavailable: ' + sampler.reason)
    measured, payload = sample
    expected_cpu, delta = reference_cpu(*reader.stat)
    expected_memory = reference_memory(reader.memory)
    if abs(payload['cpuPercent'] - expected_cpu) > 1e-10:
        raise AssertionError('CPU accounting differs from independent reference')
    if abs(payload['memoryPercent'] - expected_memory) > 1e-10:
        raise AssertionError('memory accounting differs from independent reference')
    return {'testedLayer': 'real /proc sample with independent same-input accounting',
            'physicalSensorComparison': False, 'loadGenerated': False,
            'sampleIntervalMs': interval_ms, 'measuredBoottimeMs': measured,
            'cpuPercent': payload['cpuPercent'], 'memoryPercent': payload['memoryPercent'],
            'referenceCpuPercent': expected_cpu, 'referenceMemoryPercent': expected_memory,
            'aggregateDeltas': delta, 'comparisonTolerancePercentagePoints': 1e-10,
            'reason': sampler.reason}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sample-interval-ms', type=int, default=2000)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    if not 1000 <= args.sample_interval_ms <= 60000:
        parser.error('sample interval must be 1000..60000 ms')
    try:
        report = asyncio.run(check(args.sample_interval_ms))
    except (OSError, ValueError, AssertionError, KeyError, StopIteration) as exc:
        print('Measurement check failed: ' + str(exc), file=sys.stderr)
        return 1
    wire = json.dumps(report, indent=2, allow_nan=False) + '\n'
    if args.output is not None:
        args.output.write_text(wire)
    print(wire, end='')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
