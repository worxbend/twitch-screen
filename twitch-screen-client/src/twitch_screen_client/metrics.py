"""Linux aggregate CPU deltas and physical RAM availability."""
from pathlib import Path
import time


def boottime_ms():
    return time.clock_gettime_ns(time.CLOCK_BOOTTIME) // 1000000


class ProcReader:
    def read(self, name):
        with (Path('/proc') / name).open() as stream:
            value = stream.read(65537)
        if len(value) > 65536:
            raise ValueError('proc input exceeds bound')
        return value


def cpu_counters(text):
    row = next((line.split() for line in text.splitlines() if line.startswith('cpu ')), [])
    if len(row) < 9:
        raise ValueError('missing aggregate CPU counters')
    values = tuple(int(value) for value in row[1:9])
    if any(value < 0 for value in values):
        raise ValueError('negative CPU counter')
    return values


def memory_percent(text):
    fields = {}
    for line in text.splitlines():
        row = line.split()
        if row and row[0] in ('MemTotal:', 'MemAvailable:'):
            if row[0] in fields or len(row) != 3 or row[2] != 'kB':
                return None
            try:
                fields[row[0]] = int(row[1])
            except ValueError:
                return None
    total, available = fields.get('MemTotal:', 0), fields.get('MemAvailable:', -1)
    if total <= 0 or not 0 <= available <= total:
        return None
    return (total - available) / total * 100


class Sampler:
    def __init__(self, interval_ms=2000, reader=None, clock=boottime_ms, active_clock=None):
        self.interval_ms = interval_ms
        self.reader = reader or ProcReader()
        self.clock = clock
        self.active_clock = active_clock or (lambda: time.monotonic_ns() // 1000000)
        self.previous = None
        self.reason = 'warmup'

    def sample(self):
        now, active = self.clock(), self.active_clock()
        try:
            counters = cpu_counters(self.reader.read('stat'))
        except (OSError, ValueError):
            self.previous = None
            self.reason = 'cpu_unavailable'
            return None
        previous, self.previous = self.previous, (now, active, counters)
        if previous is None:
            self.reason = 'warmup'
            return None
        before, before_active, old = previous
        elapsed = now - before
        if elapsed <= 0 or elapsed > 2 * self.interval_ms or abs(elapsed - (active - before_active)) > 100:
            self.reason = 'sampling_gap'
            return None
        deltas = [value - prior for value, prior in zip(counters, old)]
        total = sum(deltas)
        if any(delta < 0 for delta in deltas) or total <= 0:
            self.reason = 'counter_reset'
            return None
        try:
            memory = memory_percent(self.reader.read('meminfo'))
        except (OSError, ValueError):
            memory = None
        payload = {'cpuPercent': (total - deltas[3] - deltas[4]) / total * 100}
        if memory is not None:
            payload['memoryPercent'] = memory
        self.reason = 'available' if memory is not None else 'memory_unavailable'
        return now, payload
