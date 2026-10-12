"""Measurement recovery and independent-reference checks, with injected counters."""
import importlib.util
from pathlib import Path
import unittest

from test_foundations import Reader
from twitch_screen_client.metrics import Sampler, memory_percent

spec = importlib.util.spec_from_file_location('check_metrics', Path(__file__).resolve().parents[1] / 'tools/check_metrics.py')
assert spec is not None and spec.loader is not None
reference = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reference)


class MeasurementChecks(unittest.TestCase):
    def test_independent_accounting_all_fields_and_guest_exclusion(self):
        old = 'cpu 100 100 100 100 100 100 100 100 1000 1000'
        new = 'cpu 110 120 130 140 150 160 170 180 1000000 1000000'
        percent, deltas = reference.reference_cpu(old, new)
        self.assertEqual(deltas, [10, 20, 30, 40, 50, 60, 70, 80])
        self.assertEqual(percent, 75)
        self.assertEqual(reference.reference_memory('MemTotal: 1000 kB\nMemFree: 0 kB\nMemAvailable: 250 kB'), 75)

    def test_failed_proc_read_requires_new_warmup_then_recovers(self):
        reader = Reader()
        now = [0]
        sampler = Sampler(reader=reader, clock=lambda: now[0], active_clock=lambda: now[0])
        self.assertIsNone(sampler.sample())
        original = reader.read

        def denied(name):
            raise OSError('synthetic unavailable counters')

        reader.read = denied
        now[0] = 2000
        self.assertIsNone(sampler.sample())
        self.assertEqual(sampler.reason, 'cpu_unavailable')
        reader.read = original
        reader.stat = 'cpu 20 0 20 180 0 0 0 0\n'
        now[0] = 4000
        self.assertIsNone(sampler.sample())
        self.assertEqual(sampler.reason, 'warmup')
        reader.stat = 'cpu 30 0 30 260 0 0 0 0\n'
        now[0] = 6000
        result = sampler.sample()
        assert result is not None
        self.assertEqual(result[1], {'cpuPercent': 20.0, 'memoryPercent': 75.0})

    def test_counter_reset_and_iowait_decrease_never_fabricate_measurement(self):
        for invalid in ('cpu 5 0 10 80 0 0 0 0', 'cpu 20 0 20 180 4 0 0 0'):
            with self.subTest(invalid=invalid):
                reader = Reader()
                reader.stat = 'cpu 10 0 10 80 5 0 0 0'
                now = [0]
                sampler = Sampler(reader=reader, clock=lambda: now[0], active_clock=lambda: now[0])
                sampler.sample()
                now[0] = 2000
                reader.stat = invalid
                self.assertIsNone(sampler.sample())
                self.assertEqual(sampler.reason, 'counter_reset')

    def test_memory_missing_invalid_duplicates_and_measured_endpoints(self):
        for text in ('MemTotal: 100 kB\nMemTotal: 100 kB\nMemAvailable: 50 kB',
                     'MemTotal: 100 kB\nMemAvailable: -1 kB', 'MemAvailable: 50 kB',
                     'MemTotal: 100 kB\nMemAvailable: 50 MB'):
            self.assertIsNone(memory_percent(text))
        self.assertEqual(memory_percent('MemTotal: 100 kB\nMemAvailable: 100 kB'), 0)
        self.assertEqual(memory_percent('MemTotal: 100 kB\nMemAvailable: 0 kB'), 100)
