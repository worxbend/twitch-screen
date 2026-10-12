import unittest
from dbus_fast import Variant
from twitch_screen_client.config import ConfigError, parse
from twitch_screen_client.normalize import normalize, plain
from twitch_screen_client.metrics import Sampler, cpu_counters, memory_percent


class ConfigTests(unittest.TestCase):
    def test_defaults_and_filters(self):
        c = parse({'relay_url': 'http://localhost:8080/', 'source_id': 'desk'})
        self.assertFalse(c.notifications_enabled)
        self.assertFalse(c.permits('app'))
        c = parse({'relay_url': 'https://relay.local', 'source_id': 'desk', 'notifications_enabled': True,
                   'include_apps': ['Test'], 'exclude_apps': ['BLOCK']})
        self.assertTrue(c.permits('TEST'))
        self.assertFalse(c.permits('block'))
        self.assertFalse(c.permits('twitch-screen-client'))
        self.assertFalse(c.forward_body)
        self.assertEqual(c.freshness_ms, 10000)

    def test_strict_config(self):
        for change in ({'unknown': True}, {'sample_interval_ms': True}, {'cpu_enabled': 1},
                       {'source_id': 'invalid space'}, {'host': ''}, {'include_apps': 'test'},
                       {'relay_url': 'http://user:pass@host'}, {'relay_url': 'ftp://host'},
                       {'relay_url': 'http://host/path'}, {'relay_url': 'http://host?'},
                       {'relay_url': 'http://host#'}, {'relay_url': 'http://host:0'},
                       {'relay_url': 'http://host\\evil'}, {'relay_url': 'http://host\n'}):
            with self.subTest(change=change), self.assertRaises(ConfigError):
                parse({'relay_url': 'http://localhost', 'source_id': 'desk', **change})


class NormalizeTests(unittest.TestCase):
    def config(self, **kw):
        return parse({'relay_url': 'http://localhost', 'source_id': 'desk', 'notifications_enabled': True, **kw})

    def body(self, timeout=-1, urgency=1):
        return ['Test', 17, '/private/icon', '<b>Hello &amp; goodbye</b>', '<i>Body</i>', ['action'],
                {'urgency': Variant('y', urgency), 'secret': Variant('s', 'not forwarded')}, timeout]

    def test_privacy_urgency_and_timeout(self):
        for urgency, kind in enumerate(('info', 'message', 'alert')):
            card = normalize(self.body(urgency=urgency), self.config(), 100)
            self.assertEqual(card.payload, {'type': kind, 'title': 'Hello & goodbye', 'body': '',
                                           'ttlMs': 8000, 'expiresInMs': 60000})
            self.assertEqual(card.deadline_ms, 60100)
        for timeout, expected in ((0, 60000), (-1, 60000), (1, 1000), (100000, 60000)):
            self.assertEqual(normalize(self.body(timeout), self.config(), 0).deadline_ms, expected)
        self.assertEqual(normalize(self.body(), self.config(forward_body=True), 0).payload['body'], 'Test: Body')
        escaped = self.body()
        escaped[4] = '&amp;lt;script&amp;gt;'
        self.assertEqual(normalize(escaped, self.config(forward_body=True), 0).payload['body'], 'Test: &lt;script&gt;')
        self.assertIsNone(normalize(self.body(), self.config(exclude_apps=['test']), 0))

    def test_controls_entities_and_utf16(self):
        self.assertEqual(plain('a\x00b\u202ec<script>private</script><br>d &amp;lt;'), 'a b c d &lt;')
        self.assertEqual(plain('😀' * 3000), '😀' * 2048)
        self.assertEqual(plain('😀x', 2), '😀')
        body = self.body()
        body[3] = ''
        self.assertEqual(normalize(body, self.config(), 0).payload['title'], 'Test')
        body[3] = '漢' * 4096
        body[4] = '漢' * 4096
        self.assertIsNone(normalize(body, self.config(forward_body=True), 0))


class Reader:
    def __init__(self):
        self.stat = 'cpu 10 0 10 80 0 0 0 0 10000 10000\n'
        self.meminfo = 'MemTotal: 1000 kB\nMemAvailable: 250 kB\n'
    def read(self, name):
        return self.stat if name == 'stat' else self.meminfo


class MetricsTests(unittest.TestCase):
    def test_cpu_delta_guest_and_memory(self):
        reader = Reader()
        now = [0]
        sampler = Sampler(reader=reader, clock=lambda: now[0], active_clock=lambda: now[0])
        self.assertIsNone(sampler.sample())
        now[0] = 2000
        reader.stat = 'cpu 20 0 20 140 20 0 0 0 20000 20000\n'
        measured, data = sampler.sample()
        self.assertEqual(measured, 2000)
        self.assertEqual(data, {'cpuPercent': 20.0, 'memoryPercent': 75.0})
        self.assertEqual(len(cpu_counters(reader.stat)), 8)

    def test_reset_suspend_gap_and_absent_memory(self):
        reader = Reader()
        now, active = [0], [0]
        sampler = Sampler(reader=reader, clock=lambda: now[0], active_clock=lambda: active[0])
        sampler.sample()
        now[0], active[0] = 2000, 1000
        self.assertIsNone(sampler.sample())
        self.assertEqual(sampler.reason, 'sampling_gap')
        now[0], active[0] = 4000, 3000
        reader.stat = 'cpu 0 0 0 0 0 0 0 0\n'
        self.assertIsNone(sampler.sample())
        self.assertEqual(sampler.reason, 'counter_reset')
        now[0], active[0] = 6000, 5000
        reader.stat = 'cpu 1 0 0 0 0 0 0 0\n'
        reader.meminfo = 'MemTotal: 1 kB\n'
        self.assertEqual(sampler.sample()[1], {'cpuPercent': 100.0})
        self.assertEqual(sampler.reason, 'memory_unavailable')
        now[0], active[0] = 12000, 11000
        self.assertIsNone(sampler.sample())

    def test_memory_invalid_and_real_zero(self):
        for text in ('', 'MemTotal: 0 kB\nMemAvailable: 0 kB',
                     'MemTotal: 1 kB\nMemAvailable: 2 kB', 'MemTotal: x kB\nMemAvailable: 0 kB',
                     'MemTotal: 1 MB\nMemAvailable: 0 kB'):
            self.assertIsNone(memory_percent(text))
        self.assertEqual(memory_percent('MemTotal: 1 kB\nMemAvailable: 1 kB'), 0.0)
