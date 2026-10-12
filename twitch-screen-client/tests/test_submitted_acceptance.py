"""Runtime acceptance must preserve submitted measurements, not trust an echo."""
from copy import deepcopy
import unittest

from test_runtime import FIXTURES
import test_boundaries
import test_acceptance_consistency
from twitch_screen_client.responses import matches_submitted_cpu


def accepted(value, method, payload):
    assert method == 'PUT'
    return matches_submitted_cpu(value, payload)


class SubmittedAcceptanceTests(unittest.TestCase):
    def response(self, payload):
        value = deepcopy(FIXTURES['cpu_fresh'])
        value['observedAtSource'] = 'producer' if 'observedAt' in payload else 'relay_receipt'
        value['data'].update(sourceId=payload['sourceId'], host=payload['host'],
                             cpuPercent=payload['cpuPercent'],
                             memoryPercent=payload.get('memoryPercent'),
                             temperatureCelsius=payload.get('temperatureCelsius'),
                             freshnessMs=payload.get('freshnessMs', 10000),
                             observedAt=payload.get('observedAt', value['receivedAt']))
        return value

    def payload(self):
        return {'sourceId': 'test-desktop', 'host': 'Synthetic host', 'cpuPercent': 25,
                'memoryPercent': 50, 'temperatureCelsius': -12.35}

    def test_changed_numeric_measurements_never_count_as_accepted(self):
        payload = self.payload()
        for field in ('cpuPercent', 'memoryPercent', 'temperatureCelsius'):
            with self.subTest(field=field):
                value = self.response(payload)
                value['data'][field] = 99
                self.assertFalse(accepted(value, 'PUT', payload))

    def test_optional_absence_and_measured_zero_are_distinct(self):
        for field in ('memoryPercent', 'temperatureCelsius'):
            for present in (True, False):
                with self.subTest(field=field, present=present):
                    payload = self.payload()
                    if present:
                        payload[field] = 0
                    else:
                        del payload[field]
                    value = self.response(payload)
                    self.assertTrue(accepted(value, 'PUT', payload))
                    value['data'][field] = None if present else 0
                    self.assertFalse(accepted(value, 'PUT', payload))

    def test_invalid_submitted_numeric_values_fail_closed(self):
        for field in ('cpuPercent', 'memoryPercent', 'temperatureCelsius'):
            for invalid in (None, True, '25', float('nan'), 10**400):
                with self.subTest(field=field, invalid=type(invalid).__name__):
                    payload = self.payload()
                    value = self.response(payload)
                    payload[field] = invalid
                    self.assertFalse(accepted(value, 'PUT', payload))

    def test_half_away_from_zero_rounding_and_default_freshness(self):
        payload = {**self.payload(), 'cpuPercent': 25.005,
                   'memoryPercent': 50.005, 'temperatureCelsius': -12.345}
        value = self.response(payload)
        value['data'].update(cpuPercent=25.01, memoryPercent=50.01, temperatureCelsius=-12.35)
        self.assertTrue(accepted(value, 'PUT', payload))
        for field, wrong in (('cpuPercent', 25), ('memoryPercent', 50), ('temperatureCelsius', -12.34)):
            changed = deepcopy(value)
            changed['data'][field] = wrong
            self.assertFalse(accepted(changed, 'PUT', payload))
        value['data']['freshnessMs'] = 20000
        self.assertFalse(accepted(value, 'PUT', payload))

    def test_observation_default_and_explicit_provenance_are_preserved(self):
        for explicit in (True, False):
            payload = self.payload()
            if explicit:
                payload['observedAt'] = '2026-10-11T10:00:01.000Z'
            value = self.response(payload)
            self.assertTrue(accepted(value, 'PUT', payload))
            changed = deepcopy(value)
            changed['observedAtSource'] = 'relay_receipt' if explicit else 'producer'
            self.assertFalse(accepted(changed, 'PUT', payload))
            value['data']['observedAt'] = '2026-10-11T10:00:02.000Z'
            self.assertFalse(accepted(value, 'PUT', payload))


class ChangedMeasurementChildTests(test_boundaries.FullChildTests):
    """Invalid measured echo cannot stop normal synthetic notification delivery."""
    relay_mode = 'changed_measurement'


class SubmittedExchangeTests(test_acceptance_consistency.AcceptanceExchangeTests):
    async def test_changed_cpu_measurement_invalidates_runtime_calibration(self):
        await self.check_outcome('PUT', lambda response: response['data'].update(cpuPercent=99))
