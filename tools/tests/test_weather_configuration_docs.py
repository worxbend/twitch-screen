"""Weather deployment examples must enable a real, documented relay provider."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]


class WeatherConfigurationDocsTests(unittest.TestCase):
    def test_safe_template_documents_builtin_weather_without_enabling_network_calls(self):
        text = (ROOT / 'twitch-screen-relay/.env.example').read_text()
        values = dict(line.split('=', 1) for line in text.splitlines()
                      if line and not line.startswith('#'))
        self.assertEqual(values.get('RELAY_WEATHER_ENABLED'), 'false')
        self.assertEqual(values.get('RELAY_WEATHER_UNIT'), 'celsius')
        self.assertIn('RELAY_WEATHER_LATITUDE', values)
        self.assertIn('RELAY_WEATHER_LONGITUDE', values)
        self.assertIn('Open-Meteo', text)
        self.assertNotIn('weather collection is not implemented', text)

    def test_guide_documents_backend_fetch_and_tcp_delivery(self):
        text = (ROOT / 'docs/guides/weather.md').read_text()
        self.assertIn('RELAY_WEATHER_ENABLED=true', text)
        self.assertIn('50.45466', text)
        self.assertIn('30.5238', text)
        self.assertIn('WeatherSnapshot', text)
        self.assertIn('preferredDashboard', text)
        self.assertIn('twitchDisplayEnabled', text)
        self.assertIn('stale', text)
        self.assertIn('non-commercial', text)
        self.assertIn('https://open-meteo.com/en/licence', text)

    def test_weather_settings_are_forwarded_by_compose_and_documented(self):
        example = (ROOT / 'twitch-screen-relay/.env.example').read_text()
        names = set(re.findall(r'^(RELAY_WEATHER_[A-Z_]+)=', example, re.M))
        self.assertGreaterEqual(len(names), 8)
        config = (ROOT / 'twitch-screen-relay/resources/application.conf').read_text()
        compose = (ROOT / 'twitch-screen-relay/compose.yaml').read_text()
        reference = (ROOT / 'docs/reference/relay-configuration.md').read_text()
        for name in names:
            with self.subTest(name=name):
                self.assertIn('${?' + name + '}', config)
                self.assertIn(name + ':', compose)
                self.assertIn('`' + name + '`', reference)


if __name__ == '__main__':
    unittest.main()
